/*  Title:      Pure/Tools/find_thms_at.scala
    Author:     Isabelle contributors

Find theorems at a given line position in a theory file.

Uses ML_Process with Toplevel transition evaluation: only transitions up to
and including the injected find_theorems command are processed.  The logic
session and sibling imports are resolved automatically.
*/

package isabelle

import scala.collection.mutable


object Find_Thms_At {

  /** sentinel markers for extracting find_theorems output from ML stdout **/

  private val sentinel_start = "===FIND_THMS_BEGIN==="
  private val sentinel_end   = "===FIND_THMS_END==="


  /** generate ML script **/

  private def ml_script(thy_file: Path, line: Int, query: String): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val query_ml = ML_Syntax.print_string_bytes(query)
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
let
  val thy_file    = Path.explode ${thy_path_ml};
  val inject_line = ${line_ml} : int;
  val query_str   = ${query_ml};

  (* read original theory and inject find_theorems after line N *)
  val original = File.read thy_file;
  val file_lines = String.fields (fn c => c = #"\\n") original;
  val insert_off =
    List.foldl (fn (l, acc) => acc + String.size l + 1) 0
               (List.take (file_lines, Int.min (inject_line, length file_lines)));
  val injection = "find_theorems " ^ query_str ^ "\\n";
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

  (* apply transitions, stop after the injected find_theorems *)
  val inject_pos_line = inject_line + 1;

  val _ = fold (fn tr => fn st =>
    let
      val line_opt = Position.line_of (Toplevel.pos_of tr);
      val tr_name  = Toplevel.name_of tr;
      val is_injection =
        (line_opt = SOME inject_pos_line andalso tr_name = "find_theorems");
      val () = if is_injection then writeln ${sentinel_start_ml} else ();
      val st' =
        Toplevel.command_exception true tr st
        handle exn =>
          let
            val fail_line = (case line_opt of SOME l => l | NONE => 0);
            val fail_content =
              if fail_line >= 1 andalso fail_line <= length file_lines
              then List.nth (file_lines, fail_line - 1) else "";
          in
            if not is_injection then writeln ${sentinel_start_ml} else ();
            writeln (
              (if is_injection then "Error at line " else "Error before injection at line ") ^
              Int.toString (if is_injection then inject_line else fail_line) ^
              " (" ^ (if is_injection then line_content else fail_content) ^ "): " ^
              Runtime.exn_message exn);
            writeln ${sentinel_end_ml};
            Exn.reraise exn
          end;
      val () = if is_injection then writeln ${sentinel_end_ml} else ();
    in
      if (case line_opt of SOME l => l > inject_pos_line | NONE => false) then st
      else st'
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


  /** find_thms_at **/

  def find_thms_at(
    options: Options,
    thy_file: Path,
    line: Int,
    query: String,
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

    Isabelle_System.with_tmp_dir("find_thms_at") { tmp_dir =>

      val script_content = ml_script(thy_file, line, query)
      val script_path = tmp_dir + Path.explode("find_thms.ML")
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

      /* extract output between sentinels */

      def recode(s: String): String = Symbol.output(unicode_symbols, s)

      val all_lines = split_lines(result.out)
      val output = all_lines
        .dropWhile(_ != sentinel_start).drop(1)
        .takeWhile(_ != sentinel_end)
        .map(recode)

      if (output.isEmpty && result.rc != 0) {
        progress.echo("find_thms_at failed (return code " + result.rc + ")")
        if (result.err.nonEmpty) progress.echo(result.err)
      }
      else if (output.isEmpty) progress.echo("No theorems found.")
      else output.foreach(s => progress.echo(s))

      if (verbose && result.err.nonEmpty)
        progress.echo_warning(result.err)
    }
  }


  /** Isabelle tool wrapper **/

  val isabelle_tool = Isabelle_Tool("find_thms_at",
    "find theorems at a given line of a theory file",
    Scala_Project.here,
    { args =>
      val dirs = new mutable.ListBuffer[Path]
      var logic = ""
      var options = Options.init()
      var unicode_symbols = false
      var verbose = false

      val getopts = Getopts("""
Usage: isabelle find_thms_at [OPTIONS] THY_FILE LINE QUERY

  Options are:
    -d DIR       include session directory for import resolution
    -l NAME      logic session name (override automatic derivation)
    -o OPTION    override Isabelle system option
    -U           output Unicode symbols
    -v           verbose: show derived logic session, build progress,
                 and ML process errors

  Find theorems matching QUERY at LINE in THY_FILE, using find_theorems.

  The logic session is derived automatically from the theory's imports
  (e.g. Complex_Main maps to HOL).  Sibling imports in the same
  directory are loaded automatically.

  QUERY syntax (same as the find_theorems command):
    "pattern"           match by term pattern
    name: "string"      match theorem names containing string
    intro               match intro rules for the goal at LINE
    elim                match elim rules
    dest                match dest rules
    simp: "term"        match simp rules for term
    (N)                 limit to N results (default: 40)
    (N with_dups)       limit and include duplicates

  Multiple criteria can be combined, e.g. 'intro name: "List"'.
  Options must precede positional arguments.

  Examples:
    isabelle find_thms_at Foo.thy 42 '"_ + _ = _ + _"'
    isabelle find_thms_at -v Foo.thy 17 'intro'
    isabelle find_thms_at Foo.thy 10 '(5) name: "assoc"'
""",
        "d:" -> (arg => dirs += Path.explode(arg)),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "U" -> (_ => unicode_symbols = true),
        "v" -> (_ => verbose = true))

      val (thy_file, line, query) =
        getopts(args) match {
          case List(f, l, q) => (Path.explode(f), Value.Int.parse(l), q)
          case _ => getopts.usage()
        }

      val progress = new Console_Progress(verbose = verbose)

      find_thms_at(options, thy_file, line, query, logic = logic,
        dirs = dirs.toList, unicode_symbols = unicode_symbols,
        verbose = verbose, progress = progress)
    })
}
