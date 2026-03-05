/*  Title:      Pure/Tools/sledgehammer_at.scala
    Author:     Isabelle contributors

Run Sledgehammer at a given line position in a theory file.

Uses ML_Process with Toplevel transition evaluation: only transitions up to
and including the injected sledgehammer command are processed.  The logic
session and sibling imports are resolved automatically.
*/

package isabelle

import scala.collection.mutable


object Sledgehammer_At {

  /** sentinel markers for extracting sledgehammer output from ML stdout **/

  private val sentinel_start = "===SLEDGEHAMMER_BEGIN==="
  private val sentinel_end   = "===SLEDGEHAMMER_END==="


  /** generate ML script **/

  private def ml_script(thy_file: Path, line: Int, opts: String): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val opts_ml = ML_Syntax.print_string_bytes(opts)
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
let
  val thy_file    = Path.explode ${thy_path_ml};
  val inject_line = ${line_ml} : int;
  val opts_str    = ${opts_ml};

  (* read original theory and inject sledgehammer after line N *)
  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;
  val insert_off =
    List.foldl (fn (l, acc) => acc + String.size l + 1) 0
               (List.take (file_lines, Int.min (inject_line, length file_lines)));
  val sh_bracket = if opts_str = "" then "" else " [" ^ opts_str ^ "]";
  val injection = "sledgehammer" ^ sh_bracket ^ "\\n";
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

  (* apply transitions, stop after the injected sledgehammer *)
  val inject_pos_line = inject_line + 1;

  val _ = fold (fn tr => fn st =>
    let
      val line_opt = Position.line_of (Toplevel.pos_of tr);
      val tr_name  = Toplevel.name_of tr;
      val past_injection =
        (case line_opt of SOME l => l > inject_pos_line | NONE => false);
    in
      if past_injection then st
      else
        let
          val is_injection =
            (line_opt = SOME inject_pos_line andalso tr_name = "sledgehammer");
          val () = if is_injection then writeln ${sentinel_start_ml} else ();
          val st' =
            if is_injection then
              Toplevel.command_exception true tr st
              handle exn =>
                (writeln ("Error at line " ^ Int.toString inject_line ^
                          " (" ^ line_content ^ "): " ^
                          Runtime.exn_message exn);
                 st)
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
                  writeln ("Error before injection at line " ^
                           Int.toString fail_line ^
                           " (" ^ fail_content ^ "): " ^
                           Runtime.exn_message exn);
                  writeln ${sentinel_end_ml};
                  Exn.reraise exn
                end;
          val () = if is_injection then writeln ${sentinel_end_ml} else ();
        in st' end
    end
  ) transitions (Toplevel.make_state NONE);

in () end;
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
        // Pick the session with the most transitive predecessors
        // (most specific / highest in the dependency chain)
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


  /** sledgehammer_at **/

  def sledgehammer_at(
    options: Options,
    thy_file: Path,
    line: Int,
    opts: String = "",
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

    Isabelle_System.with_tmp_dir("sledgehammer_at") { tmp_dir =>

      val script_content = ml_script(thy_file, line, opts)
      val script_path = tmp_dir + Path.explode("sledgehammer.ML")
      File.write(script_path, script_content)

      /* start bash_process server for external ATP invocation */
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
        // Output contains either sledgehammer results or a caught error
        // with the line number and content (e.g. "Error at line 4 (...): No subgoal!")
        output.foreach(s => progress.echo(s))
      }
      else if (result.rc != 0) {
        // Sentinels never written — ML script crashed before reaching injection
        progress.echo("sledgehammer_at: line " + line +
          " (" + line_content + "): failed (return code " + result.rc + ")")
        if (result.err.nonEmpty) progress.echo(result.err)
      }
      else {
        // Sentinels never written — injection line not reached (e.g. LINE
        // is past the end of the theory commands)
        progress.echo("sledgehammer_at: line " + line +
          " (" + line_content + "): no sledgehammer command was reached." +
          " Check that LINE is inside a proof with an active goal.")
      }

      if (verbose && result.err.nonEmpty)
        progress.echo_warning(result.err)
    }
  }


  /** Isabelle tool wrapper **/

  val isabelle_tool = Isabelle_Tool("sledgehammer_at",
    "run Sledgehammer at a given line of a theory file",
    Scala_Project.here,
    { args =>
      val dirs    = new mutable.ListBuffer[Path]
      var logic   = ""     // empty = derive from imports; set by -l
      var options = Options.init()
      var provers = ""
      var timeout = 0
      var unicode_symbols = false
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle sledgehammer_at [OPTIONS] THY_FILE LINE [OPTS]

  Options are:
    -d DIR       include session directory for import resolution
    -l NAME      override logic session (normally derived from imports)
    -o OPTION    override Isabelle system option
    -P PROVERS   space-separated prover list (e.g. "e cvc5 vampire")
    -T SECONDS   timeout per prover in seconds
    -U           output Unicode symbols
    -v           verbose: show derived logic session, build progress,
                 and ML process errors

  Run Sledgehammer at LINE in THY_FILE. LINE must be inside a proof with
  an active goal. All commands before LINE must be accepted by Isabelle
  (no errors); otherwise Sledgehammer may produce incorrect suggestions.

  The optional OPTS argument is placed verbatim inside Sledgehammer's
  [...] bracket syntax:

    isabelle sledgehammer_at Foo.thy 17 'provers = "e cvc5" timeout = 30'

  is equivalent to injecting: sledgehammer [provers = "e cvc5" timeout = 30]

  Shortcuts -P and -T generate the provers/timeout opts automatically.

  Examples:
    isabelle sledgehammer_at Foo.thy 17
    isabelle sledgehammer_at -P "e cvc5" -T 15 Foo.thy 17
    isabelle sledgehammer_at Foo.thy 17 'provers = "e" timeout = 30'
""",
        "d:" -> (arg => dirs    += Path.explode(arg)),
        "l:" -> (arg => logic    = arg),
        "o:" -> (arg => options  = options + arg),
        "P:" -> (arg => provers  = arg),
        "T:" -> (arg => timeout  = Value.Int.parse(arg)),
        "U" ->  (_ => unicode_symbols = true),
        "v" ->  (_ => verbose = true))

      val (thy_file, line, raw_opts) =
        getopts(args) match {
          case List(f, l)    => (Path.explode(f), Value.Int.parse(l), "")
          case List(f, l, q) => (Path.explode(f), Value.Int.parse(l), q)
          case _             => getopts.usage()
        }

      // Merge shortcut flags into the options string
      val provers_opt = if (provers.nonEmpty) "provers = \"" + provers + "\"" else ""
      val timeout_opt = if (timeout > 0)      "timeout = " + timeout           else ""
      val opts = List(provers_opt, timeout_opt, raw_opts).filter(_.nonEmpty).mkString(" ")

      val progress = new Console_Progress(verbose = verbose)

      sledgehammer_at(options, thy_file, line, opts = opts, logic = logic,
        dirs = dirs.toList, unicode_symbols = unicode_symbols,
        verbose = verbose, progress = progress)
    })
}
