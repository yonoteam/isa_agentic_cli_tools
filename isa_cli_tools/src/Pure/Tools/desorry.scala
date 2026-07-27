/*  Title:      Pure/Tools/desorry.scala
    Author:     Isabelle contributors

Replace sorry proofs in a theory file with Sledgehammer results.

Uses ML_Process to replay Toplevel transitions, collect proof states at
each sorry, run Sledgehammer in parallel on all of them, and replace
sorry's in-place (with atomic rename) after saving a .backup.  Theory loading
follows the same approach as eval_at: session derivation, a heap-availability
check (the heap is required to be pre-built; it is never built here), and
sibling import resolution via Thy_Info.use_theories with cwd = thy_dir.

The ML script is split into two phases:
  Phase 1 (top-level): uses only Pure structures to replay transitions
    and collect proof states at sorry positions.
  Phase 2 (theory context): evaluated within the HOL theory context via
    ML_Context.eval_file, so that Sledgehammer and related HOL structures
    are accessible.  (After Pure.thy sets ML_write_global = false, HOL
    structures only exist in the theory-local ML namespace.)
Communication between phases uses refs in a global ML structure.

Replay stops at the first ordinary error, before proof search or mutation.
Explicit -L targets must be unique, positive, in range, reachable before the
optional stop line, and parsed sorry transitions.  Phase progress and
diagnostics are streamed to stderr; proof results go to stdout.  After 15
seconds of visible silence a sparse heartbeat reports the latest replay or
proof-search position.  Fatal outcomes exit nonzero.

An overall wall-clock safeguard hard-terminates the spawned ML process group if
the whole run exceeds a bound (default 900s; override via the env var
ISABELLE_CLI_TOOLS_WALL_TIMEOUT, 0 disables).  This bounds hangs the per-command
and Sledgehammer timeouts cannot preempt (e.g. session-heap loading, GC/swap
thrash) that would otherwise leave an orphaned multi-GB poly process behind; the
file is left unchanged if the safeguard fires.  It is a machine-protection
safeguard, deliberately not a per-call flag.
*/

package isabelle

import scala.collection.mutable


object Desorry {

  /** Sledgehammer timeout per sorry (seconds), fixed **/

  private val sledgehammer_timeout = 50

  /** Phase 2 ML script: Sledgehammer invocation (evaluated within theory context) **/

  private def ml_script_phase2(): String =
    """
let
  val content_of_pretty =
    Protocol_Message.clean_output o Pretty.unformatted_string_of;

  (* --- Proof text generation --- *)

  (* used_facts here are the per-method facts taken from a preplay_result,
     i.e. (Pretty.T * stature) list, so `fst` is already a Pretty.T. *)
  fun replacement_text pref_method used_facts =
    let
      val non_chained = filter_out
        (fn (_, (sc, _)) => sc = ATP_Problem_Generate.Chained) used_facts
      val fact_pretties = map fst non_chained
      val (indirect, direct) =
        if Sledgehammer_Proof_Methods.is_proof_method_direct pref_method
        then ([], fact_pretties)
        else (fact_pretties, [])
      val using_text =
        if null indirect then ""
        else "using " ^ space_implode " "
          (map content_of_pretty indirect) ^ " "
      val method_pretty =
        Sledgehammer_Proof_Methods.pretty_proof_method
          "by " "" direct pref_method
    in using_text ^ content_of_pretty method_pretty end;

  fun is_smt_method (Sledgehammer_Proof_Methods.SMT_Method _) = true
    | is_smt_method _ = false;

  fun self_verified_replacement result state =
    let
      val ctxt = Proof.context_of state
      val {goal, facts = chained, ...} = Proof.goal state
      val {used_facts, used_from, preferred_methss, ...} = result
      val used_thm_facts =
        Sledgehammer_Prover.filter_used_facts false used_facts used_from
      val global_facts = map snd used_thm_facts
      val pretty_used_facts =
        map (fn ((name, stature), _) => (Pretty.str name, stature)) used_thm_facts
      val method_candidates =
        fst preferred_methss :: flat (snd preferred_methss)
        |> filter_out is_smt_method
        |> distinct (op =)

      fun closes_goal meth =
        let
          val tac = Sledgehammer_Proof_Methods.tac_of_proof_method ctxt
            (chained, global_facts) meth 1
        in
          (case Seq.pull (tac goal) of
            SOME (goal', _) => Thm.nprems_of goal' = 0
          | NONE => false)
        end
        handle ERROR _ => false
             | THM _ => false
             | TERM _ => false
             | CTERM _ => false
             | TYPE _ => false;

      fun first_verified [] = NONE
        | first_verified (meth :: meths) =
            if closes_goal meth then SOME (replacement_text meth pretty_used_facts)
            else first_verified meths
    in
      first_verified method_candidates
    end;


  (* --- Sledgehammer invocation --- *)

  fun try_sledgehammer timeout state =
    let
      val thy = Proof.theory_of state
      (* smt_proofs = false: SMT solvers may still be used to *find* a proof,
         but Sledgehammer reconstructs with structured/metis methods rather
         than emitting a fragile `by (smt ...)` call.  If no non-smt method
         preplays, the sorry is left in place (reported as "no proof found"). *)
      val params = Sledgehammer_Commands.default_params thy
        [("timeout", Int.toString timeout), ("smt_proofs", "false")]
      val (found, (outcome, _)) =
        Sledgehammer.run_sledgehammer params Sledgehammer_Prover.Normal
          NONE 1 Sledgehammer_Fact.no_fact_override state
    in
      case (found, outcome) of
        (true, Sledgehammer.SH_Some (result, preplay_results)) =>
          (* preplay_results is sorted best-first.  Accept a reconstruction
             only if its preplay actually succeeded (Played); otherwise leave
             the sorry in place.  Using #preferred_methss instead would take
             the prover's *unverified* suggestion, which (with smt_proofs =
             false) can be a metis call whose preplay failed and that then
             breaks on reload --- the bug this guards against. *)
          (case preplay_results of
            (meth, (Sledgehammer_Proof_Methods.Played _, facts)) :: _ =>
              SOME (replacement_text meth facts)
          | _ => self_verified_replacement result state)
      | _ => NONE
    end;


  (* --- Run Sledgehammer in parallel on all collected proof states --- *)

  val sorry_states = ! Desorry_Comm.sorry_states;
  val timeout = ! Desorry_Comm.timeout;
  val n = length sorry_states;
  val completed = Synchronized.var "desorry_completed" 0;

  fun note_completed () =
    let
      val current =
        Synchronized.change_result completed
          (fn count => (count + 1, count + 1));
      val _ = CLI_Tool_Event.status "proof search"
        NONE NONE (SOME current) (SOME n);
    in () end;

  val results =
    Par_List.map (fn (line, state) =>
      let
        val result =
          (case Exn.result (fn () =>
            case try_sledgehammer timeout state of
              SOME text =>
                (CLI_Tool_Event.result
                   ("sorry replaced at line " ^ Int.toString line ^
                    " with " ^ text);
                 SOME (line, text))
            | NONE =>
                (CLI_Tool_Event.result
                   ("no proof found at line " ^ Int.toString line);
                 NONE)) () of
            Exn.Res value => value
          | Exn.Exn exn =>
              (CLI_Tool_Event.warning
                 ("[line " ^ Int.toString line ^ "] " ^
                  Runtime.exn_message exn);
               NONE));
        val _ = note_completed ();
      in result end
    ) sorry_states
    |> List.mapPartial I;

in
  Desorry_Comm.replacements := results
end;
"""


  /** Phase 1 ML script: transition replay and orchestration (Pure structures only) **/

  private def ml_script_phase1(
    thy_file: Path, stop_line: Int, sledge_timeout: Int, cmd_timeout: Int,
    target_lines: List[Int], phase2_path: Path
  ): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val stop_line_ml = ML_Syntax.print_int(stop_line)
    val sledge_timeout_ml = ML_Syntax.print_int(sledge_timeout)
    val cmd_timeout_ml = ML_Syntax.print_int(cmd_timeout)
    val target_lines_ml = ML_Syntax.print_list(ML_Syntax.print_int)(target_lines)
    val phase2_path_ml = ML_Syntax.print_string_bytes(File.platform_path(phase2_path))

    s"""
${Cli_Tool_Common.ml_protocol_handlers}
${Cli_Tool_Common.ml_event_protocol}

structure Desorry_Comm = struct
  val sorry_states : (int * Proof.state) list Unsynchronized.ref = Unsynchronized.ref [];
  val timeout : int Unsynchronized.ref = Unsynchronized.ref 50;
  val replacements : (int * string) list Unsynchronized.ref = Unsynchronized.ref [];
end;

exception Desorry_Timeout of int * string;
exception Desorry_Replay_Error of int * string * string;

let
  val thy_path = ${thy_path_ml};
  val stop_line = ${stop_line_ml} : int;
  val sledge_timeout = ${sledge_timeout_ml} : int;
  val cmd_timeout = ${cmd_timeout_ml} : int;
  val target_lines = ${target_lines_ml} : int list;
  val phase2_path = ${phase2_path_ml};

  val thy_file = Path.explode thy_path;
  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;

  fun line_content line =
    if line >= 1 andalso line <= length file_lines
    then List.nth (file_lines, line - 1) else "";

  val master_dir = Path.dir (Path.absolute thy_file);
  val header = Thy_Header.read Position.none original;

  val _ = cli_tool_with_local_protocol_handlers (fn () =>
    List.app (fn (imp, _) =>
      if Thy_Info.defined_theory imp then ()
      else (Thy_Info.use_theories "" [((imp, Position.none), [])]; ())
    ) (#imports header)) ();

  fun mk_thy () =
    let val parents = map (fn (imp, _) => Thy_Info.get_theory imp)
          (#imports header)
    in Resources.begin_theory master_dir header parents end;

  val init_thy = mk_thy ();

  val pos = Position.file (Path.implode thy_file);
  val transitions =
    Outer_Syntax.parse_text init_thy (fn () => init_thy) pos original;

  fun transition_line tr = Position.line_of (Toplevel.pos_of tr);
  fun is_sorry tr = Toplevel.name_of tr = "sorry";

  val scoped_transitions =
    if stop_line > 0 then
      take_prefix (fn tr =>
        (case transition_line tr of
          SOME line => line < stop_line
        | NONE => true)) transitions
    else transitions;

  val all_sorry_lines =
    transitions
    |> map_filter (fn tr => if is_sorry tr then transition_line tr else NONE);
  val scoped_sorry_lines =
    scoped_transitions
    |> map_filter (fn tr => if is_sorry tr then transition_line tr else NONE);
  val not_sorry_targets =
    filter_out (fn line => member (op =) all_sorry_lines line) target_lines;
  val excluded_targets =
    filter (fn line =>
      member (op =) all_sorry_lines line andalso
      not (member (op =) scoped_sorry_lines line)) target_lines;
  val target_errors =
    (if null not_sorry_targets then []
     else ["target line(s) are not sorry transitions: " ^
       commas (map Int.toString not_sorry_targets)]) @
    (if null excluded_targets then []
     else ["target line(s) excluded by stop line: " ^
       commas (map Int.toString excluded_targets)]);

  val replay_total =
    length (filter_out Toplevel.is_ignored scoped_transitions);
  val replay_completed = Unsynchronized.ref 0;

  fun note_progress tr =
    if Toplevel.is_ignored tr then ()
    else
      let
        val current = ! replay_completed + 1;
        val _ = replay_completed := current;
      in
        if current mod 250 = 0 orelse current = replay_total
        then CLI_Tool_Event.status "replay"
          (SOME current) (SOME replay_total) NONE NONE
        else ()
      end;

  fun execute_transition tr st =
    let
      val line = the_default 0 (transition_line tr);
      val result =
        Exn.result (fn () =>
          Timeout.apply (Time.fromMilliseconds (1000 * cmd_timeout))
            (fn () => Toplevel.command_exception tr st) ()) ();
    in
      (case result of
        Exn.Res st' => (note_progress tr; st')
      | Exn.Exn (Timeout.TIMEOUT _) =>
          raise Desorry_Timeout (line, line_content line)
      | Exn.Exn (Runtime.EXCURSION_FAIL (exn, _)) =>
          raise Desorry_Replay_Error
            (line, line_content line, Runtime.exn_message exn)
      | Exn.Exn exn =>
          raise Desorry_Replay_Error
            (line, line_content line, Runtime.exn_message exn))
    end;

  fun process [] _ acc = rev acc
    | process (tr :: rest) st acc =
        let
          val tr_line = the_default 0 (transition_line tr)
        in
          if is_sorry tr then
            let
              val keep_sorry = null target_lines orelse Library.member (op =) target_lines tr_line
              val sorry_state =
                if keep_sorry andalso Toplevel.is_proof st
                then SOME (Toplevel.proof_of st)
                else NONE
              val st' = execute_transition tr st
            in
              process rest st'
                (case sorry_state of
                  SOME ps => (tr_line, ps) :: acc
                | NONE => acc)
            end
          else
            process rest (execute_transition tr st) acc
        end;

  type replacement = {line: int, text: string};

  fun apply_replacements original (replacements : replacement list) =
    let
      val lines = String.fields (fn c => c = #"\\n") original
      val repl_map = fold (fn {line, text} =>
        Symtab.update (Int.toString line, text))
        replacements Symtab.empty
      fun process_line (i, line) =
        case Symtab.lookup repl_map (Int.toString (i + 1)) of
          SOME proof_text =>
            let val indent = implode (take_prefix
              (fn c => c = " " orelse c = "\\t") (raw_explode line))
            in indent ^ proof_text end
        | NONE => line
    in
      map_index process_line lines
      |> String.concatWith "\\n"
    end;

  val () =
    if not (null target_errors) then
      CLI_Tool_Event.fatal (cat_lines target_errors)
    else
      ((let
         val _ = Desorry_Comm.timeout := sledge_timeout;
         val _ = CLI_Tool_Event.status "replay"
           (SOME 0) (SOME replay_total) NONE NONE;
         val sorry_states =
           process scoped_transitions (Toplevel.make_state NONE) [];
         val captured_lines = sort int_ord (map fst sorry_states);
         val requested_lines = sort int_ord target_lines;
         val exact_targets =
           null target_lines orelse captured_lines = requested_lines;
         val n_total = length sorry_states;
       in
         if not exact_targets then
           CLI_Tool_Event.fatal
             ("captured sorry lines did not match requested targets: requested " ^
              commas (map Int.toString requested_lines) ^ "; captured " ^
              commas (map Int.toString captured_lines))
         else if n_total = 0 then
           CLI_Tool_Event.result "no sorry's found"
         else
           let
             val _ = Desorry_Comm.sorry_states := sorry_states;
             val _ = CLI_Tool_Event.status "proof search"
               NONE NONE (SOME 0) (SOME n_total);
             val _ = Context.setmp_generic_context
               (SOME (Context.Theory init_thy))
               (fn () => ML_Context.eval_file ML_Compiler.flags
                 (Path.explode phase2_path)) ();
             val replacements = map (fn (l, t) =>
               {line = l, text = t} : replacement)
               (! Desorry_Comm.replacements);
             val n_found = length replacements;
           in
             if n_found = 0 then ()
             else
               let
                 val modified = apply_replacements original replacements;
                 val backup_path = Path.ext "backup" thy_file;
                 val tmp_path = Path.ext "desorry_tmp" thy_file;
                 val _ = File.write backup_path original;
                 val _ = File.write tmp_path modified;
                 val _ = OS.FileSys.rename {
                   old = File.platform_path tmp_path,
                   new = File.platform_path thy_file};
               in
                 CLI_Tool_Event.warning
                   ("replaced " ^ Int.toString n_found ^ " of " ^
                    Int.toString n_total ^ " sorry(s); backup written to " ^
                    Path.implode backup_path)
               end
           end
       end)
       handle Desorry_Timeout (line, content) =>
         CLI_Tool_Event.fatal
           ("timed out after " ^ Int.toString cmd_timeout ^
            "s at line " ^ Int.toString line ^ " (" ^ content ^
            "); no changes written.")
        | Desorry_Replay_Error (line, content, message) =>
         CLI_Tool_Event.fatal
           ("replay error at line " ^ Int.toString line ^
            " (" ^ content ^ "): " ^ message ^ "; no changes written."));
in
  ()
end;
"""
  }


  /** desorry **/

  private def validate_target_numbers(lines: List[Int]): Unit = {
    val nonpositive = lines.filter(_ <= 0).distinct.sorted
    val duplicates =
      lines.groupMapReduce(identity)(_ => 1)(_ + _)
        .collect { case (line, count) if count > 1 => line }.toList.sorted
    val messages =
      List(
        if (nonpositive.nonEmpty)
          Some(
            "nonpositive target line(s): " +
              commas(nonpositive.map(_.toString)))
        else None,
        if (duplicates.nonEmpty)
          Some(
            "duplicate target line(s): " +
              commas(duplicates.map(_.toString)))
        else None).flatten
    if (messages.nonEmpty) error(cat_lines(messages))
  }

  def desorry(
    options: Options,
    thy_file: Path,
    stop_line: Int = 0,
    cmd_timeout: Int = 60,
    target_lines: List[Int] = Nil,
    logic: String = "",
    dirs: List[Path] = Nil,
    verbose: Boolean = false,
    progress: Progress = new Progress
  ): Int = {
    validate_target_numbers(target_lines)

    Cli_Tool_Common.with_reporter("desorry", verbose, identity) { reporter =>
      reporter.phase(
        "preparing theory and checking session heap...",
        Cli_Tool_Common.Status(phase = "preflight"))

      val prepared =
        Cli_Tool_Common.prepare_theory(options, thy_file, logic, dirs)
      val beyond_end =
        target_lines.filter(_ > prepared.file_lines.length).distinct.sorted
      if (beyond_end.nonEmpty) {
        error(
          "target line(s) beyond end of file: " +
            commas(beyond_end.map(_.toString)))
      }

      reporter.diagnostic_if_verbose("Logic session: " + prepared.logic)
      Cli_Tool_Common.check_logic_heap(
        options,
        prepared,
        progress,
        "Example for a theory importing HOL-Algebra.* when HOL-Algebra is not built:\n" +
          "  isabelle desorry -l HOL-Computational_Algebra -d " +
          "$ISABELLE_HOME/src/HOL FILE.thy")

      reporter.phase(
        "starting ML process...",
        Cli_Tool_Common.Status(phase = "startup"))

      val outcome =
        Cli_Tool_Common.run_ml_process(options, prepared, reporter) { tmp_dir =>
          val phase2_path = tmp_dir + Path.explode("desorry_phase2.ML")
          File.write(phase2_path, ml_script_phase2())
          val script_path = tmp_dir + Path.explode("desorry.ML")
          File.write(
            script_path,
            ml_script_phase1(
              thy_file,
              stop_line,
              sledgehammer_timeout,
              cmd_timeout,
              target_lines,
              phase2_path))
          script_path
        }

      outcome.exit_code
    }
  }


  /** Isabelle tool wrapper **/

  val isabelle_tool = Isabelle_Tool("desorry",
    "replace sorry proofs with Sledgehammer results",
    Scala_Project.here,
    { args =>
      val dirs = new mutable.ListBuffer[Path]
      var logic = ""
      var options = Options.init()
      var stop_line = 0
      var target_lines = List.empty[Int]
      var cmd_timeout = 60
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle desorry [OPTIONS] THY_FILE [LINE]

  Options are:
    -L LINES     unique, positive lines of parsed, reachable sorry commands
                 (comma-separated, e.g., 42,105)
    -d DIR       include session directory for import resolution
    -l NAME      logic session name (override automatic derivation)
    -o OPTION    override Isabelle system option
    -t SECS      per-command timeout for replayed transitions
                 (default: 60; 0 disables). Sledgehammer is fixed at 50s/sorry.
    -v           verbose

  Process THY_FILE: find all sorry proofs (up to LINE if given),
  run Sledgehammer on each in parallel, and replace sorry's in-place
  with the found proofs.  A backup is saved to THY_FILE.backup.
  If -L is provided, only sorry proofs at the specified lines are processed.

  The logic session is derived automatically from the theory's imports.
  Sibling imports in the same directory are loaded automatically.
  Progress and diagnostics are written to stderr; proof results are written
  to stdout. A heartbeat appears after 15 seconds of silence. Replay and
  target-validation failures exit nonzero and write nothing.
  Each replayed command is bounded by -t seconds; if a tactic exceeds it
  (e.g. a non-terminating proof) desorry stops, reports the line, and writes
  nothing.

  Examples:
    isabelle desorry Foo.thy
    isabelle desorry Foo.thy 42
    isabelle desorry -t 120 Foo.thy
    isabelle desorry -L 42,105 Foo.thy
    isabelle desorry -l HOL-Analysis Foo.thy
""",
        "L:" -> (arg =>
          try { target_lines = space_explode(',', arg).map(Value.Int.parse) }
          catch { case ERROR(_) => error("Malformed line numbers in -L option (expected comma-separated integers)") }),
        "d:" -> (arg => dirs += Path.explode(arg)),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "t:" -> (arg => cmd_timeout = Value.Int.parse(arg)),
        "v" -> (_ => verbose = true))

      val (thy_file, line) =
        getopts(args) match {
          case List(f) => (Path.explode(f), 0)
          case List(f, l) => (Path.explode(f), Value.Int.parse(l))
          case _ => getopts.usage()
        }

      val progress = new Console_Progress(verbose = verbose)

      val rc =
        desorry(options, thy_file, stop_line = line, cmd_timeout = cmd_timeout,
          target_lines = target_lines, logic = logic, dirs = dirs.toList,
          verbose = verbose, progress = progress)
      if (rc != 0) sys.exit(rc)
    })
}
