/*  Title:      Pure/Tools/eval_at.scala
    Author:     Isabelle contributors

Evaluate an arbitrary Isabelle command at a given line of a theory file.

Uses ML_Process with Toplevel transition evaluation: only transitions up to
and including the target line (or injected command) are processed.  When no
command is given, the output and state at the target line are shown.  The
logic session and sibling imports are resolved automatically.
*/

package isabelle

import scala.collection.mutable


object Eval_At {

  /** sentinel markers for extracting output from ML stdout **/

  private val sentinel_start = "===EVAL_AT_BEGIN==="
  private val sentinel_end   = "===EVAL_AT_END==="


  /** generate ML script: state mode (no command injection) **/

  private def ml_script_state(thy_file: Path, line: Int): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
let
  val thy_file    = Path.explode ${thy_path_ml};
  val target_line = ${line_ml} : int;

  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;

  (* pre-load local imports via Thy_Info so they are available *)
  val master_dir = Path.dir (File.absolute_path thy_file);
  val header = Thy_Header.read Position.none original;
  val options = Options.default ();
  val _ = List.app (fn (imp, _) =>
    if Thy_Info.defined_theory imp then ()
    else (Thy_Info.use_theories options "" [(imp, Position.none)]; ())
  ) (#imports header);

  (* init function: resolve imports from Thy_Info, create theory via Resources *)
  val init = fn () =>
    let
      val parents = map (fn (imp, _) => Thy_Info.get_theory imp) (#imports header);
    in Resources.begin_theory master_dir header parents end;

  val init_thy = init ();
  val pos = Position.file (Path.implode thy_file);
  val transitions = Outer_Syntax.parse_text init_thy (fn () => init_thy) pos original;

  (* split transitions: before target line, at target line, after *)
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

  (* Phase 1: execute transitions before target line silently *)
  val pre_st = fold (fn tr => fn st =>
    Toplevel.command_exception true tr st
    handle exn =>
      let
        val line_opt = Position.line_of (Toplevel.pos_of tr);
        val fail_line = (case line_opt of SOME l => l | NONE => 0);
        val fail_content =
          if fail_line >= 1 andalso fail_line <= length file_lines
          then List.nth (file_lines, fail_line - 1) else "";
      in
        writeln ${sentinel_start_ml};
        writeln ("Error at line " ^
                 Int.toString fail_line ^
                 " (" ^ fail_content ^ "): " ^
                 Runtime.exn_message exn);
        writeln ${sentinel_end_ml};
        Exn.reraise exn
      end
  ) before_trs (Toplevel.make_state NONE);

  (* Phase 2: execute transitions at target line with output capture *)
  val () = writeln ${sentinel_start_ml};
  val (final_st, _) = fold (fn tr => fn (st, errored) =>
    if errored then (st, true)
    else
      (Toplevel.command_exception true tr st, false)
      handle exn =>
        let
          val fail_content =
            if target_line >= 1 andalso target_line <= length file_lines
            then List.nth (file_lines, target_line - 1) else "";
        in
          (writeln ("Error at line " ^
                    Int.toString target_line ^
                    " (" ^ fail_content ^ "): " ^
                    Runtime.exn_message exn);
           (st, true))
        end
  ) at_trs (pre_st, false);

  (* also print proof state if available *)
  val ps_output = Toplevel.pretty_state final_st;
  val _ =
    if null ps_output then
      (if null at_trs then writeln "No proof state." else ())
    else
      List.app (fn p => writeln (Pretty.string_of p)) ps_output;

in
  writeln ${sentinel_end_ml}
end;
"""
  }


  /** generate ML script: command injection mode **/

  private def ml_script_inject(
    thy_file: Path, line: Int, command: String, show_state: Boolean
  ): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val command_ml = ML_Syntax.print_string_bytes(command)
    val show_state_ml = if (show_state) "true" else "false"
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
let
  val thy_file    = Path.explode ${thy_path_ml};
  val inject_line = ${line_ml} : int;
  val command_str = ${command_ml};
  val show_state  = ${show_state_ml};

  (* read original theory and inject command after line N *)
  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;
  val insert_off =
    List.foldl (fn (l, acc) => acc + String.size l + 1) 0
               (List.take (file_lines, Int.min (inject_line, length file_lines)));
  val injection = command_str ^ "\\n";
  val modified =
    String.substring (original, 0, Int.min (insert_off, String.size original)) ^ injection;

  (* content of the user-supplied line, for error diagnostics *)
  val line_content =
    if inject_line >= 1 andalso inject_line <= length file_lines
    then List.nth (file_lines, inject_line - 1)
    else "";

  (* pre-load local imports via Thy_Info so they are available *)
  val master_dir = Path.dir (File.absolute_path thy_file);
  val header = Thy_Header.read Position.none original;
  val options = Options.default ();
  val _ = List.app (fn (imp, _) =>
    if Thy_Info.defined_theory imp then ()
    else (Thy_Info.use_theories options "" [(imp, Position.none)]; ())
  ) (#imports header);

  (* init function: resolve imports from Thy_Info, create theory via Resources *)
  val init = fn () =>
    let
      val parents = map (fn (imp, _) => Thy_Info.get_theory imp) (#imports header);
    in Resources.begin_theory master_dir header parents end;

  val init_thy = init ();
  val pos = Position.file (Path.implode thy_file);
  val transitions = Outer_Syntax.parse_text init_thy (fn () => init_thy) pos modified;

  (* split transitions into pre-injection and injected *)
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

  (* Phase 1: execute pre-injection transitions *)
  val pre_st = fold (fn tr => fn st =>
    Toplevel.command_exception true tr st
    handle exn =>
      let
        val line_opt = Position.line_of (Toplevel.pos_of tr);
        val fail_line = (case line_opt of SOME l => l | NONE => 0);
        val fail_content =
          if fail_line >= 1 andalso fail_line <= length file_lines
          then List.nth (file_lines, fail_line - 1) else "";
      in
        writeln ${sentinel_start_ml};
        writeln ("Error before injection at line " ^
                 Int.toString fail_line ^
                 " (" ^ fail_content ^ "): " ^
                 Runtime.exn_message exn);
        writeln ${sentinel_end_ml};
        Exn.reraise exn
      end
  ) pre_trs (Toplevel.make_state NONE);

  (* Phase 2: execute injected transitions between sentinels *)
  val () = writeln ${sentinel_start_ml};
  val (post_st, _) = fold (fn tr => fn (st, errored) =>
    if errored then (st, true)
    else
      (Toplevel.command_exception true tr st, false)
      handle exn =>
        (writeln ("Error at line " ^ Int.toString inject_line ^
                  " (" ^ line_content ^ "): " ^
                  Runtime.exn_message exn);
         (st, true))
  ) inj_trs (pre_st, false);

  (* optionally print proof state after injected command *)
  val _ = if show_state then
    let val output = Toplevel.pretty_state post_st in
      if null output then writeln "No proof state."
      else List.app (fn p => writeln (Pretty.string_of p)) output
    end
  else ();

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
        theory = Thy_Header.get_thy_name(thy_file.base.implode).getOrElse(""))
      val header = Thy_Header.read(node_name,
        Scan.char_reader(File.read(thy_file)), command = false, strict = false)

      val theory_names = header.imports.map { case (s, _) => Thy_Header.import_name(s) }
      if (theory_names.isEmpty) return Isabelle_System.default_logic()

      val sessions_structure = Sessions.load_structure(options, dirs = dirs)

      val session_candidates = theory_names.flatMap { name =>
        val qualifier = sessions_structure.theory_qualifier(name)
        if (qualifier.nonEmpty && sessions_structure.defined(qualifier)) Some(qualifier)
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
    verbose: Boolean = false,
    progress: Progress = new Progress
  ): Unit = {

    /* read theory content */

    val content = File.read(thy_file)
    val file_lines = content.split("\n", -1)

    if (line < 1 || line > file_lines.length)
      error("Line " + line + " out of range (file has " + file_lines.length + " lines)")

    val theory_name =
      Thy_Header.get_thy_name(thy_file.base.implode)
        .getOrElse(error("Cannot determine theory name from " + thy_file))


    /* parse header and resolve path-based imports to session directories */

    val thy_dir = thy_file.absolute.dir

    val node_name = Document.Node.Name(thy_file.absolute.implode, theory = theory_name)
    val header =
      Thy_Header.read(node_name, Scan.char_reader(content), command = false, strict = false)

    val import_dirs: List[Path] = header.imports.flatMap { case (s, _) =>
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


    /* ensure logic heap is available */

    Build.build_logic(options, effective_logic, dirs = all_dirs, progress = progress,
      build_heap = true, strict = true)


    /* run the ML script via ML_Process */

    Isabelle_System.with_tmp_dir("eval_at") { tmp_dir =>

      val inject_mode = command.nonEmpty
      val script_content =
        if (inject_mode) ml_script_inject(thy_file, line, command, show_state)
        else ml_script_state(thy_file, line)
      val script_path = tmp_dir + Path.explode("eval_at.ML")
      File.write(script_path, script_content)

      /* start bash_process server for external tool invocation */
      val server = Bash.Server.start()

      val store = Store(options)
      val qd_options = options + "quick_and_dirty" +
        ("bash_process_address=" + server.address) +
        ("bash_process_password=" + server.password)
      val session_background =
        Sessions.background(qd_options, effective_logic, dirs = all_dirs).check_errors
      val session_heaps =
        store.session_heaps(session_background, logic = effective_logic)

      val process =
        ML_Process(qd_options, session_background, session_heaps,
          args = List("--use", File.platform_path(script_path)),
          cwd = thy_dir,
          redirect = false)

      val result = try { process.result() } finally { server.stop() }

      /* extract output between sentinels */

      def recode(s: String): String = Symbol.output(unicode_symbols, s)

      val all_lines = split_lines(result.out)
      val output = all_lines
        .dropWhile(_ != sentinel_start).drop(1)
        .takeWhile(_ != sentinel_end)
        .filter(s => s != "don't export proof")
        .map(recode)

      val line_content = if (line >= 1 && line <= file_lines.length) file_lines(line - 1) else ""

      if (output.nonEmpty) {
        output.foreach(s => progress.echo(s))
      }
      else if (result.rc != 0) {
        progress.echo("eval_at: line " + line +
          " (" + line_content + "): failed (return code " + result.rc + ")")
        if (result.err.nonEmpty) progress.echo(result.err)
      }
      else if (inject_mode) {
        progress.echo("eval_at: line " + line +
          " (" + line_content + "): no command output was produced." +
          " Use -s to show the resulting proof state.")
      }
      else {
        progress.echo("No output at this line.")
      }

      if (verbose && result.err.nonEmpty)
        progress.echo_warning(result.err)
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
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle eval_at [OPTIONS] THY_FILE LINE [COMMAND]

  Options are:
    -S           show sorts (implies types) in output
    -T           show types in output
    -U           output Unicode symbols
    -d DIR       include session directory for import resolution
    -l NAME      logic session name (override automatic derivation)
    -o OPTION    override Isabelle system option
    -s           show proof state after command execution
    -v           verbose: show derived logic session, build progress,
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
    isabelle eval_at -s Foo.thy 15 'apply auto'
    isabelle eval_at Foo.thy 10 'term "map f xs"'
    isabelle eval_at Foo.thy 10 'thm conjI'
    isabelle eval_at Foo.thy 10 'value "[1,2,3::nat]"'
""",
        "S" -> (_ => { options = options + "show_sorts"; options = options + "show_types" }),
        "T" -> (_ => options = options + "show_types"),
        "U" -> (_ => unicode_symbols = true),
        "d:" -> (arg => dirs += Path.explode(arg)),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "s" -> (_ => show_state = true),
        "v" -> (_ => verbose = true))

      val (thy_file, line, command) =
        getopts(args) match {
          case List(f, l)    => (Path.explode(f), Value.Int.parse(l), "")
          case List(f, l, c) => (Path.explode(f), Value.Int.parse(l), c)
          case _             => getopts.usage()
        }

      val progress = new Console_Progress(verbose = verbose)

      eval_at(options, thy_file, line, command = command, logic = logic,
        dirs = dirs.toList, unicode_symbols = unicode_symbols,
        show_state = show_state, verbose = verbose, progress = progress)
    })
}
