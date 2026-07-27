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

  /** sentinel markers for extracting output from ML stdout **/

  private val sentinel_start = "===DESORRY_BEGIN==="
  private val sentinel_end   = "===DESORRY_END==="

  /** prefix for user-facing result lines (filtered in non-verbose mode) **/

  private val result_tag = "[RESULT] "

  /** Sledgehammer timeout per sorry (seconds), fixed **/

  private val sledgehammer_timeout = 50

  private val ml_local_protocol_handlers =
    """
fun desorry_with_local_protocol_handlers f x =
  let
    val old_protocol_message_fn = ! Private_Output.protocol_message_fn;

    fun local_protocol_message props _ =
      if Properties.get props "function" = SOME "invoke_scala" andalso
         Properties.get props Markup.nameN = SOME "bibtex_session_entries"
      then
        (case Properties.get props Markup.idN of
          SOME id =>
            Protocol_Command.run "Scala.result"
              [Bytes.string id, Bytes.string "1"]
        | NONE => ())
      else old_protocol_message_fn props [];
  in
    Unsynchronized.setmp Private_Output.protocol_message_fn
      local_protocol_message f x
  end;
"""


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
  val _ = writeln ("desorry: running Sledgehammer on " ^
                   Int.toString n ^ " sorry(s) in parallel...");

  val results =
    Par_List.map (fn (line, state) =>
      (case Exn.result (fn () =>
        case try_sledgehammer timeout state of
          SOME text =>
            (writeln ("[RESULT] sorry replaced at line " ^
                      Int.toString line ^ " with " ^ text);
             SOME (line, text))
        | NONE =>
            (writeln ("[RESULT] no proof found at line " ^
                      Int.toString line);
             NONE)) () of
        Exn.Res r => r
      | Exn.Exn exn =>
          (warning ("desorry: [line " ^ Int.toString line ^ "] " ^
                    Runtime.exn_message exn); NONE))
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
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
${ml_local_protocol_handlers}

structure Desorry_Comm = struct
  val sorry_states : (int * Proof.state) list Unsynchronized.ref = Unsynchronized.ref [];
  val timeout : int Unsynchronized.ref = Unsynchronized.ref 50;
  val replacements : (int * string) list Unsynchronized.ref = Unsynchronized.ref [];
end;

exception Desorry_Timeout of int * string;

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

  fun execute_transition tr st =
    let
      val line = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
    in
      (case Exn.result (fn () =>
          Timeout.apply (Time.fromMilliseconds (1000 * cmd_timeout))
            (fn () => Toplevel.command_exception tr st) ()) () of
        Exn.Res st' => st'
      | Exn.Exn (Timeout.TIMEOUT _) => raise Desorry_Timeout (line, line_content line)
      | Exn.Exn exn =>
          (warning ("desorry: error at line " ^ Int.toString line ^ ": " ^
                    Runtime.exn_message exn); st))
    end;

  val master_dir = Path.dir (Path.absolute thy_file);
  val header = Thy_Header.read Position.none original;

  val _ = desorry_with_local_protocol_handlers (fn () =>
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

  fun process [] _ acc = rev acc
    | process (tr :: rest) st acc =
        let
          val tr_line = (case Position.line_of (Toplevel.pos_of tr) of
              SOME l => l | NONE => 0)
        in
          if stop_line > 0 andalso tr_line >= stop_line then rev acc
          else if Toplevel.name_of tr = "sorry" then
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

  val () = writeln ${sentinel_start_ml};
  val () =
    (let
       val _ = Desorry_Comm.timeout := sledge_timeout;
       val sorry_states = process transitions (Toplevel.make_state NONE) [];
       val n_total = length sorry_states;
     in
       if n_total = 0 then
         writeln "[RESULT] no sorry's found"
       else
         let
           val _ = Desorry_Comm.sorry_states := sorry_states;
           val _ = Context.setmp_generic_context
             (SOME (Context.Theory init_thy))
             (fn () => ML_Context.eval_file ML_Compiler.flags
               (Path.explode phase2_path)) ();
           val replacements = map (fn (l, t) =>
             {line = l, text = t} : replacement)
             (! Desorry_Comm.replacements);
           val n_found = length replacements;
         in
           if n_found = 0 then
             writeln
               "[RESULT] Sledgehammer could not find proofs for any sorry"
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
               writeln ("desorry: backup written to " ^
                        Path.implode backup_path);
               writeln ("[RESULT] replaced " ^ Int.toString n_found ^
                        " of " ^ Int.toString n_total ^ " sorry(s)")
             end
         end
     end)
    handle Desorry_Timeout (line, content) =>
      writeln ("[RESULT] desorry: timed out after " ^ Int.toString cmd_timeout ^
               "s at line " ^ Int.toString line ^ " (" ^ content ^
               "); no changes written.");
in
  writeln ${sentinel_end_ml}
end;
"""
  }


  /** derive logic session from theory imports **/

  private def derive_logic(
    options: Options,
    thy_file: Path,
    dirs: List[Path]
  ): String = {
    try {
      val node_name = Document.Node.Name(thy_file.absolute.implode,
        theory = Thy_Header.get_thy_name(
          thy_file.base.implode).getOrElse(""))
      val header = Thy_Header.read(node_name,
        Scan.char_reader(File.read(thy_file)),
        command = false, strict = false)

      val theory_names =
        header.imports.map { case (s, _) => Thy_Header.import_name(s) }
      if (theory_names.isEmpty)
        return Isabelle_System.default_logic()

      val sessions_structure =
        Sessions.load_structure(options, dirs = dirs)

      val session_candidates = theory_names.flatMap { name =>
        val qualifier = sessions_structure.theory_qualifier(name)
        if (qualifier.nonEmpty && sessions_structure.defined(qualifier))
          Some(qualifier)
        else None
      }.distinct

      if (session_candidates.isEmpty) Isabelle_System.default_logic()
      else {
        val graph = sessions_structure.imports_graph
        session_candidates.maxBy { s =>
          try { graph.all_preds(List(s)).size }
          catch { case _: Graph.Undefined[_] => 0 }
        }
      }
    }
    catch {
      case ERROR(_) => Isabelle_System.default_logic()
    }
  }


  /** check logic session without building heaps **/

  private def check_logic_heap(
    options: Options,
    logic: String,
    dirs: List[Path],
    progress: Progress
  ): Unit = {
    val results =
      Build.build(options,
        selection = Sessions.Selection.session(logic),
        progress = progress,
        build_heap = true,
        no_build = true,
        dirs = dirs)

    if (!results.ok) {
      error("Session heap for " + quote(logic) + " is not available or not up to date; " +
        "refusing to build it automatically.\n\n" +
        "How to run this safely:\n" +
        "  1. Choose a session whose heap is already built.\n" +
        "  2. If the theory belongs to an unbuilt session, use that session's built parent " +
        "with -l and pass -d for the directory containing the ROOT file.\n" +
        "  3. Check first with: isabelle build -n SESSION [-d ROOT_DIR]\n\n" +
        "Example for a theory importing HOL-Algebra.* when HOL-Algebra is not built:\n" +
        "  isabelle desorry -l HOL-Computational_Algebra -d $ISABELLE_HOME/src/HOL FILE.thy")
    }
  }


  /** desorry **/

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
  ): Unit = {

    /* read theory content */

    val content = File.read(thy_file)
    val file_lines = split_lines(content)

    Thy_Header.get_thy_name(thy_file.base.implode)
      .getOrElse(error(
        "Cannot determine theory name from " + thy_file))


    /* parse header and resolve path-based imports */

    val thy_dir = thy_file.absolute.dir

    val node_name = Document.Node.Name(thy_file.absolute.implode,
      theory = Thy_Header.get_thy_name(
        thy_file.base.implode).getOrElse(""))
    val header = Thy_Header.read(node_name,
      Scan.char_reader(content), command = false, strict = false)

    val import_dirs: List[Path] = header.imports.flatMap {
      case (s, _) =>
        try {
          val raw = Path.explode(s)
          if (raw.implode.contains("/")) {
            val import_dir =
              if (raw.is_absolute) raw.dir.absolute
              else (thy_dir + raw).dir.absolute
            if (import_dir.is_dir) Some(import_dir) else None
          }
          else None
        }
        catch { case ERROR(_) => None }
    }.distinct

    val all_dirs = (dirs ::: import_dirs).distinct


    /* determine the logic session */

    val effective_logic =
      if (logic.nonEmpty) logic
      else derive_logic(options, thy_file, all_dirs)

    progress.echo_if(verbose, "Logic session: " + effective_logic)


    /* ensure logic heap is available without building */

    check_logic_heap(options, effective_logic, all_dirs, progress)


    /* run the ML scripts via ML_Process */

    Isabelle_System.with_tmp_dir("desorry") { tmp_dir =>

      val phase2_path = tmp_dir + Path.explode("desorry_phase2.ML")
      File.write(phase2_path, ml_script_phase2())

      val script_content =
        ml_script_phase1(thy_file, stop_line, sledgehammer_timeout, cmd_timeout,
          target_lines, phase2_path)
      val script_path = tmp_dir + Path.explode("desorry.ML")
      File.write(script_path, script_content)

      val server = Bash.Server.start(Logger.none)

      val store = Store(options)
      val qd_options = options + "quick_and_dirty" +
        ("bash_process_address=" + server.address) +
        ("bash_process_password=" + server.password)
      val session_background =
        Sessions.background(qd_options, effective_logic,
          dirs = all_dirs).check_errors
      val session_heaps =
        store.session_heaps(session_background,
          logic = effective_logic)

      val (_, process) =
        ML_Process(qd_options, session_background, session_heaps,
          args = List("--use", File.platform_path(script_path)),
          cwd = thy_dir,
          redirect = false)

      /* overall wall-clock watchdog: hard-terminate the ML process group if the
         run exceeds the safety bound, so a hang the per-command / Sledgehammer
         timeouts cannot preempt (e.g. session-heap loading or GC/swap thrash)
         cannot leave an orphaned multi-GB poly process behind.  Machine-protection
         safeguard, not a tunable: default 900s (15 min), overridable only via
         ISABELLE_CLI_TOOLS_WALL_TIMEOUT (tests / rare huge heaps; 0 disables). */
      val wall_timeout =
        sys.env.get("ISABELLE_CLI_TOOLS_WALL_TIMEOUT") match {
          case Some(s) if s.nonEmpty => Value.Int.parse(s)
          case _ => 900
        }
      val timed_out = Synchronized(false)
      val watchdog =
        if (wall_timeout > 0)
          Some(Future.thread("desorry_watchdog") {
            Time.seconds(wall_timeout.toDouble).sleep()
            timed_out.change(_ => true)
            process.terminate()
          })
        else None

      val result =
        try process.result(strict = false)
        finally { watchdog.foreach(_.cancel()); server.stop() }

      if (timed_out.value)
        progress.echo("desorry: wall-clock timeout after " + wall_timeout +
          "s; ML process terminated to avoid an orphaned session (file left unchanged).")


      /* extract output between sentinels */

      val all_lines = split_lines(result.out)
      val raw_output = all_lines
        .dropWhile(_ != sentinel_start).drop(1)
        .takeWhile(_ != sentinel_end)

      if (raw_output.nonEmpty) {
        if (verbose)
          raw_output.foreach(s =>
            progress.echo(
              if (s.startsWith(result_tag)) s.stripPrefix(result_tag)
              else s))
        else
          raw_output.collect {
            case s if s.startsWith(result_tag) => s.stripPrefix(result_tag)
          }.foreach(s => progress.echo(s))
      }
      else if (result.rc != 0) {
        progress.echo("desorry: failed (return code " + result.rc + ")")
        if (result.err.nonEmpty) progress.echo(result.err)
        if (result.out.nonEmpty) progress.echo(result.out)
      }
      else
        progress.echo("desorry: no output")

      if (verbose && result.err.nonEmpty)
        progress.echo_warning(result.err)
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
    -L LINES     comma-separated list of line numbers to target (e.g., 42,105)
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

      desorry(options, thy_file, stop_line = line, cmd_timeout = cmd_timeout,
        target_lines = target_lines, logic = logic, dirs = dirs.toList,
        verbose = verbose, progress = progress)
    })
}
