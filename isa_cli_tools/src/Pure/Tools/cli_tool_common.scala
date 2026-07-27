/*  Title:      Pure/Tools/cli_tool_common.scala

Shared preparation and supervised execution support for eval_at and desorry.
*/

package isabelle


object Cli_Tool_Common {
  final case class Prepared_Theory(
    thy_file: Path,
    thy_dir: Path,
    content: String,
    file_lines: List[String],
    header: Thy_Header,
    dirs: List[Path],
    logic: String
  )

  val ml_protocol_handlers: String =
    """
fun cli_tool_with_local_protocol_handlers f x =
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

  private def derive_logic(
    options: Options,
    thy_file: Path,
    header: Thy_Header,
    dirs: List[Path]
  ): String = {
    try {
      val theory_names =
        header.imports.map { case (s, _) => Thy_Header.import_name(s) }
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
        session_candidates.maxBy { session =>
          try { graph.all_preds(List(session)).size }
          catch { case _: Graph.Undefined[_] => 0 }
        }
      }
    }
    catch {
      case ERROR(_) => Isabelle_System.default_logic()
    }
  }

  def prepare_theory(
    options: Options,
    thy_file: Path,
    requested_logic: String,
    dirs: List[Path]
  ): Prepared_Theory = {
    val content = File.read(thy_file)
    val file_lines = split_lines(content)
    val thy_name =
      Thy_Header.get_thy_name(thy_file.base.implode)
        .getOrElse(error("Cannot determine theory name from " + thy_file))
    val thy_dir = thy_file.absolute.dir
    val node_name =
      Document.Node.Name(thy_file.absolute.implode, theory = thy_name)
    val header =
      Thy_Header.read(
        node_name, Scan.char_reader(content), command = false, strict = false)

    val import_dirs = header.imports.flatMap { case (s, _) =>
      try {
        val raw = Path.explode(s)
        if (raw.implode.contains("/")) {
          val dir =
            if (raw.is_absolute) raw.dir.absolute
            else (thy_dir + raw).dir.absolute
          if (dir.is_dir) Some(dir) else None
        }
        else None
      }
      catch { case ERROR(_) => None }
    }.distinct
    val all_dirs = (dirs ::: import_dirs).distinct
    val logic =
      if (requested_logic.nonEmpty) requested_logic
      else derive_logic(options, thy_file, header, all_dirs)

    Prepared_Theory(
      thy_file, thy_dir, content, file_lines, header, all_dirs, logic)
  }

  def check_logic_heap(
    options: Options,
    prepared: Prepared_Theory,
    progress: Progress,
    example: String
  ): Unit = {
    val results =
      Build.build(
        options,
        selection = Sessions.Selection.session(prepared.logic),
        progress = progress,
        build_heap = true,
        no_build = true,
        dirs = prepared.dirs)

    if (!results.ok) {
      error(
        "Session heap for " + quote(prepared.logic) +
          " is not available or not up to date; refusing to build it automatically.\n\n" +
          "How to run this safely:\n" +
          "  1. Choose a session whose heap is already built.\n" +
          "  2. If the theory belongs to an unbuilt session, use that session's built parent " +
          "with -l and pass -d for the directory containing the ROOT file.\n" +
          "  3. Check first with: isabelle build -n SESSION [-d ROOT_DIR]\n\n" +
          example)
    }
  }
}
