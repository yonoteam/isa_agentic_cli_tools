/*  Title:      Pure/Tools/eval_at.scala
    Author:     Isabelle contributors

Evaluate an arbitrary Isabelle command at a given line of a theory file.

Uses ML_Process with Toplevel transition evaluation: only transitions up to
and including the target line (or injected command) are processed.  When no
command is given, the output and state at the target line are shown.  The
logic session and sibling imports are resolved automatically.

In state mode (no command) every error up to the target line is reported, each
with its line, instead of stopping at the first; after a failed transition
processing continues from the previous state.
Warnings and legacy-feature messages emitted in state mode are likewise
attributed to their line ("Warning at line N: ...") rather than the unattributed
"### msg" default.  Injection mode (with a command) keeps fast-fail: an error
before the injection point aborts, since the injected command cannot run on a
broken context.

A per-command timeout (-t SECS, default 60, 0 disables) aborts evaluation
and reports the offending line if any transition exceeds the limit.
Optional timing information (-T) can be reported for each processed command.

An overall wall-clock safeguard hard-terminates the spawned ML process group if
the whole run exceeds a bound (default 900s; override via the env var
ISABELLE_CLI_TOOLS_WALL_TIMEOUT, 0 disables).  Unlike -t, this also bounds hangs
outside command evaluation (session-heap loading, GC/swap thrash) that would
otherwise leave an orphaned multi-GB poly process behind.  It is a
machine-protection safeguard, deliberately not a per-call flag.
*/

package isabelle

import scala.collection.mutable


object Eval_At {

  /** generate ML script: state mode (no command injection) **/

  private def ml_script_state(thy_file: Path, line: Int, timing: Boolean, cmd_timeout: Int): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val timing_ml = if (timing) "true" else "false"
    val cmd_timeout_ml = ML_Syntax.print_int(cmd_timeout)

    s"""
${Cli_Tool_Common.ml_protocol_handlers}
${Cli_Tool_Common.ml_event_protocol}

exception Eval_Timeout of int * string;

let
  val thy_file    = Path.explode ${thy_path_ml};
  val target_line = ${line_ml} : int;
  val do_timing   = ${timing_ml};
  val cmd_timeout = ${cmd_timeout_ml} : int;

  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;

  fun line_content l =
    if l >= 1 andalso l <= length file_lines
    then List.nth (file_lines, l - 1) else "";

  val master_dir = Path.dir (Path.absolute thy_file);
  val header = Thy_Header.read Position.none original;
  val _ = cli_tool_with_local_protocol_handlers (fn () =>
    List.app (fn (imp, _) =>
      if Thy_Info.defined_theory imp then ()
      else (Thy_Info.use_theories "" [((imp, Position.none), [])]; ())
    ) (#imports header)) ();

  val init = fn () =>
    let
      val parents = map (fn (imp, _) => Thy_Info.get_theory imp) (#imports header);
    in Resources.begin_theory master_dir header parents end;

  val init_thy = init ();
  val pos = Position.file (Path.implode thy_file);
  val transitions = Outer_Syntax.parse_text init_thy (fn () => init_thy) pos original;

  val (before_trs, at_trs) =
    let
      fun split trs bef ats =
        (case trs of
          [] => (rev bef, rev ats)
        | tr :: rest =>
            (case Position.line_of (Toplevel.pos_of tr) of
              SOME l =>
                if l < target_line then split rest (tr :: bef) ats
                else if l = target_line then split rest bef (tr :: ats)
                else (rev bef, rev ats)
            | NONE => split rest (tr :: bef) ats))
    in split transitions [] [] end;

  val replay_trs = before_trs @ at_trs;
  val total = length (filter_out Toplevel.is_ignored replay_trs);
  val completed = Unsynchronized.ref 0;
  val _ = CLI_Tool_Event.status "replay" (SOME 0) (SOME total) NONE NONE;

  fun note_progress tr =
    if Toplevel.is_ignored tr then ()
    else
      let
        val current = ! completed + 1;
        val _ = completed := current;
      in
        if current mod 250 = 0 orelse current = total
        then CLI_Tool_Event.status "replay"
          (SOME current) (SOME total) NONE NONE
        else ()
      end;

  fun exec_errors tr st =
    let
      val start = Timing.start ();
      val res =
        Exn.result (fn () =>
          Timeout.apply (Time.fromMilliseconds (1000 * cmd_timeout))
            (fn () => Toplevel.command_exception tr st) ()) ();
      val t = Timing.result start;
      val _ = if do_timing andalso not (Toplevel.is_ignored tr) then
                let
                  val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
                  val name = Toplevel.name_of tr
                in CLI_Tool_Event.result
                  ("Timing line " ^ Int.toString l ^ " (" ^ name ^ "): " ^
                   Timing.message t) end
              else ();
    in
      (case res of
        Exn.Res st' => ([], SOME st')
      | Exn.Exn (Timeout.TIMEOUT _) =>
          let val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
          in raise Eval_Timeout (l, line_content l) end
      | Exn.Exn (Runtime.EXCURSION_FAIL (exn, _)) =>
          (Runtime.exn_messages exn, NONE)
      | Exn.Exn exn => (Runtime.exn_messages exn, NONE))
    end;

  fun report_errors report_line errs =
    List.app (fn ((_, msg), _) =>
      CLI_Tool_Event.warning
        ("Error at line " ^ Int.toString report_line ^
         " (" ^ line_content report_line ^ "): " ^ msg)) errs;

  (* Run one transition, collecting ordinary failures but allowing a timeout
     interrupt to escape.  On an ordinary failure we report the errors and
     continue from the previous state, so state mode reports ALL errors up to
     the target line instead of stopping at the first. *)
  (* Capture warning/legacy messages emitted while a transition runs and re-emit
     them attributed to that transition's line (we process one transition at a
     time, so the attribution is exact).  Replaces the unattributed "### msg"
     default with "Warning at line N (...): msg", matching the error format. *)
  fun step report_line tr st =
    let
      val warn_buf = Unsynchronized.ref ([] : string list);
      val capture = (fn ss => warn_buf := implode ss :: ! warn_buf);
      val (errs, st_opt) =
        Unsynchronized.setmp Private_Output.warning_fn capture
          (fn () => Unsynchronized.setmp Private_Output.legacy_fn capture
             (fn () => exec_errors tr st) ()) ();
      val _ = List.app (fn msg =>
        CLI_Tool_Event.warning
          ("Warning at line " ^ Int.toString report_line ^
           " (" ^ line_content report_line ^ "): " ^ msg)) (rev (! warn_buf));
      val _ = note_progress tr;
    in
      (case st_opt of
        SOME st' => st'
      | NONE => (report_errors report_line errs; st))
    end;

  val () =
    (let
       val pre_st = fold (fn tr => fn st =>
         step (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0) tr st
       ) before_trs (Toplevel.make_state NONE);

       val final_st = fold (fn tr => fn st =>
         step target_line tr st
       ) at_trs pre_st;

       val ps_output = Toplevel.pretty_state final_st;
       val _ =
         if null ps_output then
           (if null at_trs then CLI_Tool_Event.result "No proof state." else ())
         else
           List.app (CLI_Tool_Event.result o Pretty.string_of) ps_output;
     in () end)
    handle Eval_Timeout (line, content) =>
      CLI_Tool_Event.fatal
        ("timed out after " ^ Int.toString cmd_timeout ^
         "s at line " ^ Int.toString line ^ " (" ^ content ^ ").");
in
  ()
end;
"""
  }


  /** generate ML script: command injection mode **/

  private def ml_script_inject(
    thy_file: Path, line: Int, command: String, show_state: Boolean, timing: Boolean, cmd_timeout: Int
  ): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val command_ml = ML_Syntax.print_string_bytes(command)
    val show_state_ml = if (show_state) "true" else "false"
    val timing_ml = if (timing) "true" else "false"
    val cmd_timeout_ml = ML_Syntax.print_int(cmd_timeout)

    s"""
${Cli_Tool_Common.ml_protocol_handlers}
${Cli_Tool_Common.ml_event_protocol}

exception Eval_Timeout of int * string;

let
  val thy_file    = Path.explode ${thy_path_ml};
  val inject_line = ${line_ml} : int;
  val command_str = ${command_ml};
  val show_state  = ${show_state_ml};
  val do_timing   = ${timing_ml};
  val cmd_timeout = ${cmd_timeout_ml} : int;

  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;

  fun line_content l =
    if l >= 1 andalso l <= length file_lines
    then List.nth (file_lines, l - 1) else "";

  val insert_off =
    List.foldl (fn (l, acc) => acc + String.size l + 1) 0
               (List.take (file_lines, Int.min (inject_line, length file_lines)));
  val injection = command_str ^ "\\n";
  val modified =
    String.substring (original, 0, Int.min (insert_off, String.size original)) ^ injection;

  val line_content_inj =
    if inject_line >= 1 andalso inject_line <= length file_lines
    then List.nth (file_lines, inject_line - 1)
    else "";

  val master_dir = Path.dir (Path.absolute thy_file);
  val header = Thy_Header.read Position.none original;
  val _ = cli_tool_with_local_protocol_handlers (fn () =>
    List.app (fn (imp, _) =>
      if Thy_Info.defined_theory imp then ()
      else (Thy_Info.use_theories "" [((imp, Position.none), [])]; ())
    ) (#imports header)) ();

  val init = fn () =>
    let
      val parents = map (fn (imp, _) => Thy_Info.get_theory imp) (#imports header);
    in Resources.begin_theory master_dir header parents end;

  val init_thy = init ();
  val pos = Position.file (Path.implode thy_file);
  val transitions = Outer_Syntax.parse_text init_thy (fn () => init_thy) pos modified;

  val inject_pos_line = inject_line + 1;

  val (pre_trs, inj_trs) =
    let
      fun split [] pre = (rev pre, [])
        | split (tr :: rest) pre =
            (case Position.line_of (Toplevel.pos_of tr) of
              SOME l =>
                if l >= inject_pos_line then (rev pre, tr :: rest)
                else split rest (tr :: pre)
            | NONE => split rest (tr :: pre))
    in split transitions [] end;

  val replay_trs = pre_trs @ inj_trs;
  val total = length (filter_out Toplevel.is_ignored replay_trs);
  val completed = Unsynchronized.ref 0;
  val _ = CLI_Tool_Event.status "replay" (SOME 0) (SOME total) NONE NONE;

  fun note_progress tr =
    if Toplevel.is_ignored tr then ()
    else
      let
        val current = ! completed + 1;
        val _ = completed := current;
      in
        if current mod 250 = 0 orelse current = total
        then CLI_Tool_Event.status "replay"
          (SOME current) (SOME total) NONE NONE
        else ()
      end;

  fun exec_timing tr st =
    let
      val start = Timing.start ();
      val res =
        (Timeout.apply (Time.fromMilliseconds (1000 * cmd_timeout))
           (fn () => Toplevel.command_exception tr st) ()
         handle Timeout.TIMEOUT _ =>
           let val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
           in
             if l >= inject_pos_line
             then raise Eval_Timeout (inject_line, command_str)
             else raise Eval_Timeout (l, line_content l)
           end);
      val t = Timing.result start;
      val _ = if do_timing andalso not (Toplevel.is_ignored tr) then
                let
                  val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
                  val name = Toplevel.name_of tr
                in CLI_Tool_Event.result
                  ("Timing line " ^ Int.toString l ^ " (" ^ name ^ "): " ^
                   Timing.message t) end
              else ();
      val _ = note_progress tr;
    in res end;

  val () =
    (let
       val pre_st = fold (fn tr => fn st =>
         exec_timing tr st
         handle Eval_Timeout e => raise Eval_Timeout e
              | exn =>
           let
             val line_opt = Position.line_of (Toplevel.pos_of tr);
             val fail_line = (case line_opt of SOME l => l | NONE => 0);
           in
             CLI_Tool_Event.fatal
               ("Error before injection at line " ^ Int.toString fail_line ^
                " (" ^ line_content fail_line ^ "): " ^
                Runtime.exn_message exn);
             Exn.reraise exn
           end
       ) pre_trs (Toplevel.make_state NONE);

       val (post_st, _) = fold (fn tr => fn (st, errored) =>
         if errored then (st, true)
         else
           (exec_timing tr st, false)
           handle Eval_Timeout e => raise Eval_Timeout e
                | exn =>
             (CLI_Tool_Event.warning
                ("Error at line " ^ Int.toString inject_line ^
                 " (" ^ line_content_inj ^ "): " ^
                 Runtime.exn_message exn);
              (st, true))
       ) inj_trs (pre_st, false);

       val _ = if show_state then
         let val output = Toplevel.pretty_state post_st in
           if null output then CLI_Tool_Event.result "No proof state."
           else List.app (CLI_Tool_Event.result o Pretty.string_of) output
         end
       else ();
     in () end)
    handle Eval_Timeout (line, content) =>
      CLI_Tool_Event.fatal
        ("timed out after " ^ Int.toString cmd_timeout ^
         "s at line " ^ Int.toString line ^ " (" ^ content ^ ").");
in
  ()
end;
"""
  }


  /** eval_at **/

  def eval_at(
    options: Options,
    thy_file: Path,
    line: Int,
    command: String = "",
    logic: String = "",
    dirs: List[Path] = Nil,
    unicode_symbols: Boolean = false,
    show_state: Boolean = false,
    timing: Boolean = false,
    cmd_timeout: Int = 60,
    verbose: Boolean = false,
    progress: Progress = new Progress
  ): Int = {
    def recode(s: String): String = Symbol.output(unicode_symbols, s)

    Cli_Tool_Common.with_reporter("eval_at", verbose, recode) { reporter =>
      reporter.phase(
        "preparing theory and checking session heap...",
        Cli_Tool_Common.Status(phase = "preflight"))

      val prepared =
        Cli_Tool_Common.prepare_theory(options, thy_file, logic, dirs)
      val file_lines = prepared.file_lines

      if (line < 1 || line > file_lines.length)
        error(
          "Line " + line + " out of range (file has " +
            file_lines.length + " lines)")

      reporter.diagnostic_if_verbose("Logic session: " + prepared.logic)
      Cli_Tool_Common.check_logic_heap(
        options,
        prepared,
        progress,
        "Example for a theory importing HOL-Algebra.* when HOL-Algebra is not built:\n" +
          "  isabelle eval_at -l HOL-Computational_Algebra -d " +
          "$ISABELLE_HOME/src/HOL FILE.thy LINE")

      reporter.phase(
        "starting ML process...",
        Cli_Tool_Common.Status(phase = "startup"))

      val inject_mode = command.nonEmpty
      val outcome =
        Cli_Tool_Common.run_ml_process(options, prepared, reporter) { tmp_dir =>
          val script_content =
            if (inject_mode)
              ml_script_inject(
                thy_file, line, command, show_state, timing, cmd_timeout)
            else ml_script_state(thy_file, line, timing, cmd_timeout)
          val script_path = tmp_dir + Path.explode("eval_at.ML")
          File.write(script_path, script_content)
          script_path
        }

      if (outcome.exit_code == 0 && !reporter.has_results) {
        val line_content = file_lines(line - 1)
        if (inject_mode) {
          reporter.result(
            "eval_at: line " + line + " (" + line_content +
              "): no command output was produced. " +
              "Use -s to show the resulting proof state.")
        }
        else reporter.result("No output at this line.")
      }

      outcome.exit_code
    }
  }


  /** Isabelle tool wrapper **/

  val isabelle_tool = Isabelle_Tool("eval_at",
    "evaluate a command at a given line of a theory file",
    Scala_Project.here,
    { args =>
      val dirs = new mutable.ListBuffer[Path]
      var logic = ""
      var options = Options.init()
      var unicode_symbols = false
      var show_state = false
      var timing = false
      var cmd_timeout = 60
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle eval_at [OPTIONS] THY_FILE LINE [COMMAND]

  Options are:
    -S           show sorts and types in output
    -U           output Unicode symbols
    -d DIR       include session directory for import resolution
    -l NAME      logic session name (override automatic derivation)
    -o OPTION    override Isabelle system option
    -s           show proof state after command execution
    -t SECS      per-command timeout for replayed transitions
                 (default: 60; 0 disables)
    -T           report timing for each processed line
    -v           verbose: show derived logic session, heap-check progress,
                 and ML process errors

  Evaluate a command at LINE in THY_FILE and print its output.

  If COMMAND is omitted, the output and proof state at LINE are
  printed (e.g. for 'term' or 'thm' commands the printed result
  is shown, and inside proofs the current subgoals are displayed).
  If COMMAND is given, it is injected after LINE and executed.

  The logic session is derived automatically from the theory's imports.
  Sibling imports in the same directory are loaded automatically.

  COMMAND is any valid Isabelle outer-syntax command text.

  Use -s to also print the resulting proof state after the command.

  Examples:
    isabelle eval_at Foo.thy 42
    isabelle eval_at -T Foo.thy 42
    isabelle eval_at Foo.thy 42 'find_theorems "_ + _"'
    isabelle eval_at Foo.thy 17 'sledgehammer'
    isabelle eval_at -t 30 Foo.thy 17 'sledgehammer'
    isabelle eval_at -s Foo.thy 15 'apply auto'
    isabelle eval_at Foo.thy 10 'term "map f xs"'
    isabelle eval_at Foo.thy 10 'thm conjI'
    isabelle eval_at Foo.thy 10 'value "[1,2,3::nat]"'
""",
        "S" -> (_ => { options = options + "show_sorts"; options = options + "show_types" }),
        "U" -> (_ => unicode_symbols = true),
        "d:" -> (arg => dirs += Path.explode(arg)),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "s" -> (_ => show_state = true),
        "t:" -> (arg => cmd_timeout = Value.Int.parse(arg)),
        "T" -> (_ => timing = true),
        "v" -> (_ => verbose = true))

      val (thy_file, line, command) =
        getopts(args) match {
          case List(f, l)    => (Path.explode(f), Value.Int.parse(l), "")
          case List(f, l, c) => (Path.explode(f), Value.Int.parse(l), c)
          case _             => getopts.usage()
        }

      val progress = new Console_Progress(verbose = verbose)

      val rc =
        eval_at(options, thy_file, line, command = command, logic = logic,
          dirs = dirs.toList, unicode_symbols = unicode_symbols,
          show_state = show_state, timing = timing, cmd_timeout = cmd_timeout,
          verbose = verbose, progress = progress)
      if (rc != 0) sys.exit(rc)
    })
}
