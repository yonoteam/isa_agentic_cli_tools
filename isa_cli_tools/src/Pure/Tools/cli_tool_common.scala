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


  /* structured ML events */

  private val event_prefix = "@@ISABELLE_CLI_EVENT@@"

  final case class Status(
    phase: String = "starting",
    current: Option[Int] = None,
    total: Option[Int] = None,
    completed: Option[Int] = None,
    work_total: Option[Int] = None
  )

  sealed trait Event
  final case class Status_Event(status: Status) extends Event
  final case class Result_Event(message: String) extends Event
  final case class Warning_Event(message: String) extends Event
  final case class Fatal_Event(message: String) extends Event

  def ml_event_protocol(token: String): String = {
    val prefix_ml =
      ML_Syntax.print_string_bytes(event_prefix + token)

    raw"""
structure CLI_Tool_Event:
sig
  val status:
    string -> int option -> int option -> int option -> int option -> unit
  val result: string -> unit
  val warning: string -> unit
  val fatal: string -> unit
end =
struct
  val prefix = ${prefix_ml};

  fun escape_char "\\" = "\\\\"
    | escape_char "\t" = "\\t"
    | escape_char "\r" = "\\r"
    | escape_char "\n" = "\\n"
    | escape_char c = c;

  val escape = implode o map escape_char o raw_explode;
  fun field (name, value) = name ^ "=" ^ escape value;
  fun optional_int _ NONE = []
    | optional_int name (SOME value) = [(name, Int.toString value)];

  fun emit kind fields =
    Output.physical_writeln
      (space_implode "\t" (prefix :: kind :: map field fields));

  fun status phase current total completed work_total =
    emit "STATUS"
      ([("phase", phase)] @
       optional_int "current" current @
       optional_int "total" total @
       optional_int "completed" completed @
       optional_int "work_total" work_total);

  fun result message = emit "RESULT" [("message", message)];
  fun warning message = emit "WARNING" [("message", message)];
  fun fatal message = emit "FATAL" [("message", message)];
end;
"""
  }

  private def malformed_event(message: String): Fatal_Event =
    Fatal_Event("malformed CLI event: " + message)

  private def unescape_event_value(value: String): String = {
    val result = new StringBuilder
    var index = 0
    while (index < value.length) {
      val c = value.charAt(index)
      if (c != '\\') {
        result.append(c)
        index += 1
      }
      else {
        if (index + 1 >= value.length) error("trailing escape")
        value.charAt(index + 1) match {
          case '\\' => result.append('\\')
          case 't' => result.append('\t')
          case 'r' => result.append('\r')
          case 'n' => result.append('\n')
          case other => error("unknown escape \\" + other)
        }
        index += 2
      }
    }
    result.toString
  }

  private def decode_event(line: String, token: String): Option[Event] = {
    val expected_prefix = event_prefix + token
    if (!line.startsWith(expected_prefix)) None
    else {
      Some(
        try {
          val parts = space_explode('\t', line)
          if (parts.length < 2 || parts.head != expected_prefix)
            error("missing event kind")
          val kind = parts(1)
          val entries = parts.drop(2).map { entry =>
            val equals = entry.indexOf('=')
            if (equals <= 0) error("malformed property " + quote(entry))
            val name = entry.substring(0, equals).nn
            val value = unescape_event_value(entry.substring(equals + 1).nn)
            name -> value
          }
          val duplicate_names =
            entries.groupMapReduce(_._1)(_ => 1)(_ + _)
              .collect { case (name, count) if count > 1 => name }.toList.sorted
          if (duplicate_names.nonEmpty)
            error("duplicate properties " + commas(duplicate_names))
          val properties = entries.toMap

          def require_only(allowed: Set[String]): Unit = {
            val unknown = properties.keySet.diff(allowed).toList.sorted
            if (unknown.nonEmpty) error("unknown properties " + commas(unknown))
          }
          def required(name: String): String =
            properties.getOrElse(name, error("missing property " + quote(name)))
          def optional_int(name: String): Option[Int] =
            properties.get(name).map { text =>
              val value = Value.Int.parse(text)
              if (value < 0) error("negative counter " + quote(name))
              value
            }

          kind match {
            case "STATUS" =>
              require_only(Set("phase", "current", "total", "completed", "work_total"))
              Status_Event(
                Status(
                  phase = required("phase"),
                  current = optional_int("current"),
                  total = optional_int("total"),
                  completed = optional_int("completed"),
                  work_total = optional_int("work_total")))
            case "RESULT" =>
              require_only(Set("message"))
              Result_Event(required("message"))
            case "WARNING" =>
              require_only(Set("message"))
              Warning_Event(required("message"))
            case "FATAL" =>
              require_only(Set("message"))
              Fatal_Event(required("message"))
            case _ => error("unknown event kind " + quote(kind))
          }
        }
        catch {
          case ERROR(message) => malformed_event(message)
          case exn: Throwable => malformed_event(Exn.message(exn))
        })
    }
  }


  /* live reporting */

  private final case class Reporter_State(
    status: Status,
    last_visible: Time,
    fatal_message: Option[String],
    result_count: Int
  )

  final class Reporter private[Cli_Tool_Common](
    tool_name: String,
    verbose: Boolean,
    recode: String => String
  ) {
    val event_token: String = UUID.random_string()

    private val heartbeat_interval = Time.seconds(15)
    private val state =
      Synchronized(Reporter_State(Status(), Time.now(), None, 0))
    private val output_lock = new AnyRef

    private def format_count(value: Int): String =
      value.toString.reverse.grouped(3).mkString(",").reverse

    private def visible(message: String, stdout: Boolean = false): Unit =
      output_lock.synchronized {
        Output.writeln(recode(message), stdout = stdout)
        state.change(st => st.copy(last_visible = Time.now()))
      }

    private def phase_message(status: Status): String =
      status match {
        case Status("replay", _, Some(total), _, _) =>
          "replaying " + format_count(total) + " transitions..."
        case Status("proof search", _, _, _, Some(total)) =>
          "proving " + format_count(total) + " sorry(s)..."
        case _ => status.phase + "..."
      }

    private def heartbeat_detail(status: Status): String =
      status match {
        case Status(phase, Some(current), Some(total), _, _) =>
          phase + " " + format_count(current) + "/" + format_count(total) +
            " transitions"
        case Status(phase, _, _, Some(completed), Some(total)) =>
          phase + " " + format_count(completed) + "/" + format_count(total) +
            " goals"
        case _ => status.phase
      }

    def phase(message: String, status: Status): Unit = {
      state.change(st => st.copy(status = status))
      visible(tool_name + ": " + message)
    }

    def update(status: Status): Unit = {
      val previous =
        state.change_result(st => (st.status, st.copy(status = status)))
      if (previous.phase != status.phase)
        visible(tool_name + ": " + phase_message(status))
    }

    def result(message: String): Unit = {
      state.change(st => st.copy(result_count = st.result_count + 1))
      visible(message, stdout = true)
    }

    def warning(message: String): Unit = visible(message)

    def diagnostic(message: String): Unit = visible(message)

    def diagnostic_if_verbose(message: String): Unit =
      if (verbose && message.nonEmpty) diagnostic(message)

    def fatal(message: String): Unit = {
      val first =
        state.change_result { st =>
          val is_first = st.fatal_message.isEmpty
          val next =
            if (is_first) st.copy(fatal_message = Some(message))
            else st
          (is_first, next)
        }
      if (first) visible(tool_name + ": " + message)
    }

    def fatal_message: Option[String] = state.value.fatal_message

    def has_results: Boolean = state.value.result_count > 0

    def handle(event: Event): Unit =
      event match {
        case Status_Event(status) => update(status)
        case Result_Event(message) => result(message)
        case Warning_Event(message) => warning(message)
        case Fatal_Event(message) => fatal(message)
      }

    private def maybe_heartbeat(): Unit = {
      output_lock.synchronized {
        val snapshot = state.value
        if (Time.now() - snapshot.last_visible >= heartbeat_interval) {
          Output.writeln(
            recode(
              tool_name + ": still working: " +
                heartbeat_detail(snapshot.status) +
                " (15s without output)"))
          state.change(st => st.copy(last_visible = Time.now()))
        }
      }
    }

    def start_heartbeat(): Future[Unit] =
      Future.thread(tool_name + "_heartbeat", daemon = true) {
        while (true) {
          Time.seconds(1).sleep()
          maybe_heartbeat()
        }
      }

    def stop_heartbeat(thread: Future[Unit]): Unit = {
      thread.cancel()
      thread.join_result
    }
  }

  def with_reporter(
    tool_name: String,
    verbose: Boolean,
    recode: String => String
  )(body: Reporter => Int): Int = {
    val reporter = new Reporter(tool_name, verbose, recode)
    val heartbeat = reporter.start_heartbeat()
    try { body(reporter) }
    finally { reporter.stop_heartbeat(heartbeat) }
  }


  /* supervised ML process */

  final case class Run_Outcome(
    process_result: Process_Result,
    fatal_message: Option[String],
    timed_out: Boolean,
    artifacts: Map[String, String]
  ) {
    def exit_code: Int =
      if (fatal_message.nonEmpty || timed_out || process_result.rc != 0) 1 else 0
  }

  def run_ml_process(
    options: Options,
    prepared: Prepared_Theory,
    reporter: Reporter,
    artifact_names: List[String] = Nil
  )(write_program: Path => Path): Run_Outcome = {
    Isabelle_System.with_tmp_dir("cli_tool") { tmp_dir =>
      val script_path = write_program(tmp_dir)
      val wall_timeout =
        sys.env.get("ISABELLE_CLI_TOOLS_WALL_TIMEOUT") match {
          case Some(s) if s.nonEmpty => Value.Int.parse(s)
          case _ => 900
        }
      val server = Bash.Server.start(Logger.none)
      var watchdog: Option[Future[Unit]] = None
      var process: Option[Bash.Process] = None
      var process_joined = false

      try {
        val store = Store(options)
        val qd_options =
          options + "quick_and_dirty" +
            ("bash_process_address=" + server.address) +
            ("bash_process_password=" + server.password)
        val session_background =
          Sessions.background(
            qd_options, prepared.logic, dirs = prepared.dirs).check_errors
        val session_heaps =
          store.session_heaps(session_background, logic = prepared.logic)
        val (_, ml_process) =
          ML_Process(
            qd_options,
            session_background,
            session_heaps,
            args = List("--use", File.platform_path(script_path)),
            cwd = prepared.thy_dir,
            redirect = false)
        process = Some(ml_process)
        val timed_out = Synchronized(false)
        val received_event = Synchronized(false)
        watchdog =
          if (wall_timeout > 0) {
            Some(
              Future.thread("cli_tool_watchdog") {
                Time.seconds(wall_timeout.toDouble).sleep()
                timed_out.change(_ => true)
                reporter.fatal(
                  "wall-clock timeout after " + wall_timeout +
                    "s; ML process terminated to avoid an orphaned session.")
                ml_process.terminate()
              })
          }
          else None

        def stdout(line: String): Unit =
          decode_event(line, reporter.event_token) match {
            case Some(event @ Fatal_Event(_)) =>
              received_event.change(_ => true)
              reporter.handle(event)
              ml_process.terminate()
            case Some(event) =>
              received_event.change(_ => true)
              reporter.handle(event)
            case None =>
              if (
                received_event.value &&
                line != "don't export proof" &&
                line != "val it = (): unit"
              )
                reporter.diagnostic(line)
          }

        def stderr(line: String): Unit =
          reporter.diagnostic(line)

        val result =
          ml_process.result(
            progress_stdout = stdout,
            progress_stderr = stderr,
            strict = false)
        process_joined = true

        if (result.rc != 0 && reporter.fatal_message.isEmpty) {
          reporter.fatal("ML process failed (return code " + result.rc + ").")
          if (result.err.nonEmpty) reporter.diagnostic(result.err)
          if (result.out.nonEmpty && !received_event.value)
            reporter.diagnostic(result.out)
        }

        val preliminary =
          Run_Outcome(
            result, reporter.fatal_message, timed_out.value, Map.empty)
        val artifacts =
          if (preliminary.exit_code == 0) {
            artifact_names.flatMap { name =>
              val path = tmp_dir + Path.basic(name)
              if (path.is_file) Some(name -> File.read(path)) else None
            }.toMap
          }
          else Map.empty

        preliminary.copy(artifacts = artifacts)
      }
      finally {
        watchdog.foreach { thread =>
          thread.cancel()
          thread.join_result
        }
        if (!process_joined) process.foreach(_.terminate())
        server.stop()
      }
    }
  }
}
