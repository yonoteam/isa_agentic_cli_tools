/*  Title:      Pure/Tools/proof_state_at.scala
    Author:     Isabelle contributors

Print the proof state at a given line of a theory file.

Uses ML_Process with Toplevel transition evaluation: only transitions up to
the target line are processed.  The logic session and sibling imports are
resolved automatically.
*/

package isabelle

import scala.collection.mutable


object Proof_State_At {

  /** sentinel markers for extracting proof state output from ML stdout **/

  private val sentinel_start = "===PROOF_STATE_BEGIN==="
  private val sentinel_end   = "===PROOF_STATE_END==="


  /** generate ML script **/

  private def ml_script(thy_file: Path, line: Int): String = {
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

  (* apply transitions up to and including target_line *)
  val final_st = fold (fn tr => fn st =>
    let
      val line_opt = Position.line_of (Toplevel.pos_of tr);
      val dominated = (case line_opt of SOME l => l > target_line | NONE => false);
    in
      if dominated then st
      else
        Toplevel.command_exception true tr st
        handle exn =>
          let
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
    end
  ) transitions (Toplevel.make_state NONE);

  (* print the proof state between sentinels *)
  val output = Toplevel.pretty_state final_st;
  val () = writeln ${sentinel_start_ml};

in
  (if null output then writeln "No proof state."
   else List.app (fn p => writeln (Pretty.string_of p)) output);
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


  /** proof_state_at **/

  def proof_state_at(
    options: Options,
    thy_file: Path,
    line: Int,
    logic: String = "",
    dirs: List[Path] = Nil,
    unicode_symbols: Boolean = false,
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

    Isabelle_System.with_tmp_dir("proof_state_at") { tmp_dir =>

      val script_content = ml_script(thy_file, line)
      val script_path = tmp_dir + Path.explode("proof_state.ML")
      File.write(script_path, script_content)

      val store = Store(options)
      val qd_options = options + "quick_and_dirty"
      val session_background =
        Sessions.background(qd_options, effective_logic, dirs = all_dirs).check_errors
      val session_heaps =
        store.session_heaps(session_background, logic = effective_logic)

      val process =
        ML_Process(qd_options, session_background, session_heaps,
          args = List("--use", File.platform_path(script_path)),
          cwd = thy_dir,
          redirect = false)

      val result = process.result()

      def recode(s: String): String = Symbol.output(unicode_symbols, s)

      /* extract output between sentinels */

      val all_lines = split_lines(result.out)
      val output = all_lines
        .dropWhile(_ != sentinel_start).drop(1)
        .takeWhile(_ != sentinel_end)
        .map(recode)

      if (output.isEmpty && result.rc != 0) {
        progress.echo("proof_state_at failed (return code " + result.rc + ")")
        if (result.err.nonEmpty) progress.echo(result.err)
      }
      else output.foreach(s => progress.echo(s))

      if (verbose && result.err.nonEmpty)
        progress.echo_warning(result.err)
    }
  }


  /** Isabelle tool wrapper **/

  val isabelle_tool = Isabelle_Tool("proof_state_at",
    "print proof state at a given line of a theory file",
    Scala_Project.here,
    { args =>
      val dirs = new mutable.ListBuffer[Path]
      var logic = ""
      var options = Options.init()
      var unicode_symbols = false
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle proof_state_at [OPTIONS] THY_FILE LINE

  Options are:
    -S           show sorts (implies types)
    -T           show types
    -U           output Unicode symbols
    -d DIR       include session directory for import resolution
    -l NAME      logic session name (override automatic derivation)
    -o OPTION    override Isabelle system option
    -v           verbose: show derived logic session, build progress,
                 and ML process errors

  Print the proof state at LINE in THY_FILE, as shown in the Prover
  IDE Output panel.

  The logic session is derived automatically from the theory's imports.
  Sibling imports in the same directory are loaded automatically.

  If LINE is inside a proof, the current subgoals are displayed.
  If LINE is at theory level, "No proof state." is printed.

  Options must precede positional arguments.

  Examples:
    isabelle proof_state_at Foo.thy 42
    isabelle proof_state_at -T Foo.thy 42
    isabelle proof_state_at -S Foo.thy 42
""",
        "S" -> (_ => { options = options + "show_sorts"; options = options + "show_types" }),
        "T" -> (_ => options = options + "show_types"),
        "U" -> (_ => unicode_symbols = true),
        "d:" -> (arg => dirs += Path.explode(arg)),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "v" -> (_ => verbose = true))

      val (thy_file, line) =
        getopts(args) match {
          case List(f, l) => (Path.explode(f), Value.Int.parse(l))
          case _ => getopts.usage()
        }

      val progress = new Console_Progress(verbose = verbose)

      proof_state_at(options, thy_file, line, logic = logic,
        dirs = dirs.toList, unicode_symbols = unicode_symbols,
        verbose = verbose, progress = progress)
    })
}
