# Per-command Replay Timeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `desorry` and `eval_at` abort with a precise "stuck at line N" message when any replayed command exceeds a per-command time limit, so a looping agent-written tactic can no longer hang the tools.

**Architecture:** Each tool generates an ML script that folds Isabelle `Toplevel` transitions through an executor function (`execute_transition` in `desorry`, `exec_timing` in `eval_at`). We wrap the inner `Toplevel.command_exception` call in `Timeout.apply` (one shared `Event_Timer`, negligible cost; `0` disables). On `Timeout.TIMEOUT` we raise a dedicated ML exception carrying the line number and text, caught at the top of the script, which prints a `[RESULT]`/`eval_at:`-tagged message between the existing stdout sentinels and exits **before any file mutation**.

**Tech Stack:** Scala (Isabelle `Tools` layer) generating Isabelle/ML; Isabelle `Getopts`; `install.sh` (`isabelle scala_build`); CLI end-to-end tests.

## Global Constraints

- Do **not** modify anything under `isabelle_dev/` (authoritative API tree) except via `install.sh`.
- `Getopts` supports **single-character options only** (`"x"` or `"x:"`). No long options.
- Reinstall after **every** source edit: `bash install.sh /Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev`.
- The tools **never build a heap**; tests must point `-l`/imports at an already-built session (`HOL` covers `Main`).
- Per-command timeout default is **60s**; `-t 0` disables it. `desorry`'s Sledgehammer time is **hard-coded to 50s**.
- ML constructor: use `Time.fromMilliseconds (1000 * t)` (not `Time.fromSeconds`).
- A passing Sledgehammer preplay is **not** sufficient evidence for `desorry`; the filled theory must reload.
- `DEV` shorthand below: `DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev`.

---

### Task 1: Looping-tactic test fixture

**Files:**
- Create: `tst/Thy_Tests/test9/Loop_Test.thy`

**Interfaces:**
- Produces: a theory whose line 5 is a reliably *interruptible* non-terminating tactic, with a `sorry` at line 9. Used by Tasks 2 and 3 as the timeout red/green fixture.

- [ ] **Step 1: Create the fixture**

Create `tst/Thy_Tests/test9/Loop_Test.thy` with exactly this content (the `\<open>…\<close>` cartouche keeps it ASCII; the loop polls interrupts via `Isabelle_Thread.expose_interrupt`, so `Timeout` can stop it — a tight ML loop could not):

```
theory Loop_Test
  imports Main
begin

lemma will_loop: "True"
  apply (tactic \<open>fn st => let fun loop (i:int) = (Isabelle_Thread.expose_interrupt (); loop (i + 1)) in loop 0 end\<close>)
  done

lemma needs_filling: "1 + 1 = (2::nat)"
  sorry

end
```

- [ ] **Step 2: Confirm the looping line and sorry line**

Run: `grep -n 'apply (tactic\|sorry' tst/Thy_Tests/test9/Loop_Test.thy`
Expected: the `apply (tactic …)` line is **line 6** and `sorry` is **line 9** (record the actual numbers; later assertions use them).

- [ ] **Step 3: Commit**

```bash
git add tst/Thy_Tests/test9/Loop_Test.thy
git commit -m "test: add looping-tactic fixture for per-command timeout"
```

---

### Task 2: `desorry` — per-command timeout + Sledgehammer fixed at 50s

**Files:**
- Modify: `isa_cli_tools/src/Pure/Tools/desorry.scala` (object `Desorry`: header comment, `ml_script_phase1`, `derive`/`desorry` def, `isabelle_tool` wrapper)

**Interfaces:**
- Consumes: Task 1 fixture (`tst/Thy_Tests/test9/Loop_Test.thy`).
- Produces: `desorry` CLI where `-t SECS` is the per-command replay timeout (default 60, `0` disables) and Sledgehammer is fixed at 50s. ML exception `Desorry_Timeout of int * string`. Scala `ml_script_phase1(thy_file, stop_line, sledge_timeout, cmd_timeout, target_lines, phase2_path)` and `desorry(options, thy_file, stop_line, cmd_timeout, target_lines, logic, dirs, verbose, progress)`.

- [ ] **Step 1: Write the failing test (record the command)**

The test is a CLI run on a writable copy (desorry rewrites in place). Run it now against the **current** build to confirm the old behavior (no timeout message; outer `timeout` must kill it):

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
mkdir -p agents/temp/t2 && cp tst/Thy_Tests/test9/Loop_Test.thy agents/temp/t2/
timeout 90 "$DEV/bin/isabelle" desorry -l HOL -t 10 agents/temp/t2/Loop_Test.thy; echo "rc=$?"
```

Expected (RED): no line containing `timed out after`; the outer `timeout` kills it (`rc=124`) because today `-t 10` is the Sledgehammer timeout and the replay loop is unbounded.

- [ ] **Step 2: Add the Sledgehammer constant and update `ml_script_phase1`**

In `desorry.scala`, add a constant near the other private vals (just after `result_tag`):

```scala
  /** Sledgehammer timeout per sorry (seconds), fixed **/

  private val sledgehammer_timeout = 50
```

Replace the entire `ml_script_phase1` function with this version (adds `sledge_timeout` + `cmd_timeout`, the `Desorry_Timeout` exception, the `Timeout.apply`-wrapped `execute_transition`, `line_content`, and a `sentinel_start`-first body wrapped in a timeout handler so nothing is written on abort):

```scala
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

  val master_dir = Path.dir (File.absolute_path thy_file);
  val header = Thy_Header.read Position.none original;
  val options = Options.default [];

  val _ = desorry_with_local_protocol_handlers (fn () =>
    List.app (fn (imp, _) =>
      if Thy_Info.defined_theory imp then ()
      else (Thy_Info.use_theories options "" [(imp, Position.none)]; ())
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
```

- [ ] **Step 3: Update the `desorry` def signature and call site**

In `def desorry(...)`, rename the `timeout: Int = 30` parameter to `cmd_timeout: Int = 60`:

```scala
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
```

And update the `ml_script_phase1` call inside it (currently `ml_script_phase1(thy_file, stop_line, timeout, target_lines, phase2_path)`):

```scala
      val script_content =
        ml_script_phase1(thy_file, stop_line, sledgehammer_timeout, cmd_timeout,
          target_lines, phase2_path)
```

- [ ] **Step 4: Update the `isabelle_tool` wrapper (flag meaning + usage)**

Replace `var timeout = 30` with `var cmd_timeout = 60`. In the `Getopts(...)` usage string, replace the `-t SECS` line and add a note, and update the option handler and final call. The relevant pieces become:

Usage text option line:
```
    -t SECS      per-command timeout for replayed transitions
                 (default: 60; 0 disables). Sledgehammer is fixed at 50s/sorry.
```

Add to the description paragraph (after the `-L` sentence):
```
  Each replayed command is bounded by -t seconds; if a tactic exceeds it
  (e.g. a non-terminating proof) desorry stops, reports the line, and writes
  nothing.
```

Change the example `isabelle desorry -t 60 Foo.thy` to:
```
    isabelle desorry -t 120 Foo.thy
```

Option handler (replace the `"t:"` line):
```scala
        "t:" -> (arg => cmd_timeout = Value.Int.parse(arg)),
```

Final call (replace `timeout = timeout`):
```scala
      desorry(options, thy_file, stop_line = line, cmd_timeout = cmd_timeout,
        target_lines = target_lines, logic = logic, dirs = dirs.toList,
        verbose = verbose, progress = progress)
```

- [ ] **Step 5: Reinstall**

Run:
```bash
bash isa_cli_tools/install.sh /Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
```
Expected: `scala_build` completes with no Scala compile errors. If it fails, fix the reported error and re-run before proceeding.

- [ ] **Step 6: Run the timeout test to verify it passes (GREEN)**

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
rm -rf agents/temp/t2 && mkdir -p agents/temp/t2 && cp tst/Thy_Tests/test9/Loop_Test.thy agents/temp/t2/
timeout 90 "$DEV/bin/isabelle" desorry -l HOL -t 10 agents/temp/t2/Loop_Test.thy; echo "rc=$?"
```
Expected (GREEN): output contains `desorry: timed out after 10s at line 6 (` followed by the tactic text and `); no changes written.`; `rc=0` (self-aborted, not killed by outer `timeout`).

- [ ] **Step 7: Verify no file mutation on abort**

```bash
ls agents/temp/t2/
diff agents/temp/t2/Loop_Test.thy tst/Thy_Tests/test9/Loop_Test.thy && echo "UNCHANGED"
```
Expected: **no** `Loop_Test.thy.backup` present; `UNCHANGED` printed (target identical to the fixture).

- [ ] **Step 8: Regression — normal fill still works and reloads**

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
rm -rf agents/temp/t2b && mkdir -p agents/temp/t2b && cp tst/Thy_Tests/test7/Test_Sorry.thy agents/temp/t2b/
"$DEV/bin/isabelle" desorry -l HOL agents/temp/t2b/Test_Sorry.thy
"$DEV/bin/isabelle" eval_at -l HOL agents/temp/t2b/Test_Sorry.thy 1 'theory'  2>/dev/null || true
"$DEV/bin/isabelle" desorry -l HOL agents/temp/t2b/Test_Sorry.thy   # 2nd run: should report no sorry's found
```
Expected: first run reports `replaced N of N sorry(s)`, writes `Test_Sorry.thy.backup`, and the filled proofs are non-`smt`. Confirm it **reloads** by running `eval_at` at the last line (proof state / no error). Second `desorry` run reports `no sorry's found` (i.e. the file genuinely has no remaining sorrys and parses).

- [ ] **Step 9: Commit**

```bash
git add isa_cli_tools/src/Pure/Tools/desorry.scala
git commit -m "feat(desorry): per-command replay timeout via -t; fix Sledgehammer at 50s"
```

---

### Task 3: `eval_at` — flag remap + per-command timeout

**Files:**
- Modify: `isa_cli_tools/src/Pure/Tools/eval_at.scala` (header comment, `ml_script_state`, `ml_script_inject`, `eval_at` def, `isabelle_tool` wrapper)

**Interfaces:**
- Consumes: Task 1 fixture.
- Produces: `eval_at` CLI where `-S` shows sorts+types, `-T` reports timings, `-t SECS` is the per-command timeout (default 60, `0` disables), and `-T`-as-show-types is gone. ML exception `Eval_Timeout of int * string`. Scala `eval_at(options, thy_file, line, command, logic, dirs, unicode_symbols, show_state, timing, cmd_timeout, verbose, progress)`; `ml_script_state(thy_file, line, timing, cmd_timeout)`; `ml_script_inject(thy_file, line, command, show_state, timing, cmd_timeout)`.

- [ ] **Step 1: Write the failing test (record commands)**

Run against the **current** build to confirm old behavior:

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
# (a) timeout: a loop before the target line currently hangs
timeout 60 "$DEV/bin/isabelle" eval_at -l HOL tst/Thy_Tests/test9/Loop_Test.thy 9; echo "rc=$?"
# (b) -T currently means "show types", not "report timings"
"$DEV/bin/isabelle" eval_at -l HOL -T tst/Thy_Tests/test6/Scratch.thy 8 2>&1 | head
```
Expected (RED): (a) no `timed out after` message, killed by outer `timeout` (`rc=124`); (b) `-T` affects type display, and there is **no** `Timing line` output.

- [ ] **Step 2: Update `ml_script_state`**

Add the exception before the `let`, add `cmd_timeout` to the signature, add `line_content`, wrap `exec_timing` with `Timeout.apply`, make the fold handlers pass `Eval_Timeout` through, and wrap the body in a timeout handler. Replace the whole function:

```scala
  private def ml_script_state(thy_file: Path, line: Int, timing: Boolean, cmd_timeout: Int): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val timing_ml = if (timing) "true" else "false"
    val cmd_timeout_ml = ML_Syntax.print_int(cmd_timeout)
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
${ml_local_protocol_handlers}

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

  val master_dir = Path.dir (File.absolute_path thy_file);
  val header = Thy_Header.read Position.none original;
  val options = Options.default [];
  val _ = eval_at_with_local_protocol_handlers (fn () =>
    List.app (fn (imp, _) =>
      if Thy_Info.defined_theory imp then ()
      else (Thy_Info.use_theories options "" [(imp, Position.none)]; ())
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

  fun exec_timing tr st =
    let
      val start = Timing.start ();
      val res =
        (Timeout.apply (Time.fromMilliseconds (1000 * cmd_timeout))
           (fn () => Toplevel.command_exception tr st) ()
         handle Timeout.TIMEOUT _ =>
           let val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
           in raise Eval_Timeout (l, line_content l) end);
      val t = Timing.result start;
      val _ = if do_timing andalso not (Toplevel.is_ignored tr) then
                let
                  val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
                  val name = Toplevel.name_of tr
                in writeln ("Timing line " ^ Int.toString l ^ " (" ^ name ^ "): " ^ Timing.message t) end
              else ();
    in res end;

  val () = writeln ${sentinel_start_ml};
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
             writeln ("Error at line " ^ Int.toString fail_line ^
                      " (" ^ line_content fail_line ^ "): " ^ Runtime.exn_message exn);
             writeln ${sentinel_end_ml};
             Exn.reraise exn
           end
       ) before_trs (Toplevel.make_state NONE);

       val (final_st, _) = fold (fn tr => fn (st, errored) =>
         if errored then (st, true)
         else
           (exec_timing tr st, false)
           handle Eval_Timeout e => raise Eval_Timeout e
                | exn =>
             (writeln ("Error at line " ^ Int.toString target_line ^
                       " (" ^ line_content target_line ^ "): " ^ Runtime.exn_message exn);
              (st, true))
       ) at_trs (pre_st, false);

       val ps_output = Toplevel.pretty_state final_st;
       val _ =
         if null ps_output then
           (if null at_trs then writeln "No proof state." else ())
         else
           List.app (fn p => writeln (Pretty.string_of p)) ps_output;
     in () end)
    handle Eval_Timeout (line, content) =>
      writeln ("eval_at: timed out after " ^ Int.toString cmd_timeout ^
               "s at line " ^ Int.toString line ^ " (" ^ content ^ ").");
in
  writeln ${sentinel_end_ml}
end;
"""
  }
```

- [ ] **Step 3: Update `ml_script_inject`**

Apply the same treatment to the inject-mode script: exception (shared name `Eval_Timeout` — declared once per generated script, and these two functions are never concatenated, so re-declaring is fine), `cmd_timeout` param, `line_content`, `Timeout`-wrapped `exec_timing`, `Eval_Timeout` passthrough in both fold handlers, and a body wrapped in the timeout handler. Replace the whole function:

```scala
  private def ml_script_inject(
    thy_file: Path, line: Int, command: String, show_state: Boolean, timing: Boolean, cmd_timeout: Int
  ): String = {
    val thy_path_ml = ML_Syntax.print_string_bytes(File.platform_path(thy_file.absolute))
    val line_ml = ML_Syntax.print_int(line)
    val command_ml = ML_Syntax.print_string_bytes(command)
    val show_state_ml = if (show_state) "true" else "false"
    val timing_ml = if (timing) "true" else "false"
    val cmd_timeout_ml = ML_Syntax.print_int(cmd_timeout)
    val sentinel_start_ml = ML_Syntax.print_string_bytes(sentinel_start)
    val sentinel_end_ml = ML_Syntax.print_string_bytes(sentinel_end)

    s"""
${ml_local_protocol_handlers}

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

  val master_dir = Path.dir (File.absolute_path thy_file);
  val header = Thy_Header.read Position.none original;
  val options = Options.default [];
  val _ = eval_at_with_local_protocol_handlers (fn () =>
    List.app (fn (imp, _) =>
      if Thy_Info.defined_theory imp then ()
      else (Thy_Info.use_theories options "" [(imp, Position.none)]; ())
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

  fun exec_timing tr st =
    let
      val start = Timing.start ();
      val res =
        (Timeout.apply (Time.fromMilliseconds (1000 * cmd_timeout))
           (fn () => Toplevel.command_exception tr st) ()
         handle Timeout.TIMEOUT _ =>
           let val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
           in raise Eval_Timeout (l, line_content l) end);
      val t = Timing.result start;
      val _ = if do_timing andalso not (Toplevel.is_ignored tr) then
                let
                  val l = (case Position.line_of (Toplevel.pos_of tr) of SOME l => l | NONE => 0)
                  val name = Toplevel.name_of tr
                in writeln ("Timing line " ^ Int.toString l ^ " (" ^ name ^ "): " ^ Timing.message t) end
              else ();
    in res end;

  val () = writeln ${sentinel_start_ml};
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
             writeln ("Error before injection at line " ^ Int.toString fail_line ^
                      " (" ^ line_content fail_line ^ "): " ^ Runtime.exn_message exn);
             writeln ${sentinel_end_ml};
             Exn.reraise exn
           end
       ) pre_trs (Toplevel.make_state NONE);

       val (post_st, _) = fold (fn tr => fn (st, errored) =>
         if errored then (st, true)
         else
           (exec_timing tr st, false)
           handle Eval_Timeout e => raise Eval_Timeout e
                | exn =>
             (writeln ("Error at line " ^ Int.toString inject_line ^
                       " (" ^ line_content_inj ^ "): " ^ Runtime.exn_message exn);
              (st, true))
       ) inj_trs (pre_st, false);

       val _ = if show_state then
         let val output = Toplevel.pretty_state post_st in
           if null output then writeln "No proof state."
           else List.app (fn p => writeln (Pretty.string_of p)) output
         end
       else ();
     in () end)
    handle Eval_Timeout (line, content) =>
      writeln ("eval_at: timed out after " ^ Int.toString cmd_timeout ^
               "s at line " ^ Int.toString line ^ " (" ^ content ^ ").");
in
  writeln ${sentinel_end_ml}
end;
"""
  }
```

- [ ] **Step 4: Update the `eval_at` def signature and call sites**

Add `cmd_timeout: Int = 60` to `def eval_at(...)` (after `timing: Boolean = false`):

```scala
    timing: Boolean = false,
    cmd_timeout: Int = 60,
    verbose: Boolean = false,
```

Update the two script call sites inside the `with_tmp_dir` block:

```scala
      val script_content =
        if (inject_mode) ml_script_inject(thy_file, line, command, show_state, timing, cmd_timeout)
        else ml_script_state(thy_file, line, timing, cmd_timeout)
```

- [ ] **Step 5: Update the `isabelle_tool` wrapper (flag remap + usage)**

In the wrapper: add `var cmd_timeout = 60`. Change the option specs so `-S` keeps sorts+types, `-T` becomes timings, `-t` becomes the timeout, and the old `-T` show-types handler is removed. The handler list becomes (showing the changed/removed lines):

```scala
        "S" -> (_ => { options = options + "show_sorts"; options = options + "show_types" }),
        "U" -> (_ => unicode_symbols = true),
        "d:" -> (arg => dirs += Path.explode(arg)),
        "l:" -> (arg => logic = arg),
        "o:" -> (arg => options = options + arg),
        "s" -> (_ => show_state = true),
        "t:" -> (arg => cmd_timeout = Value.Int.parse(arg)),
        "T" -> (_ => timing = true),
        "v" -> (_ => verbose = true))
```

(The previous `"T" -> (_ => options = options + "show_types")` and `"t" -> (_ => timing = true)` lines are deleted; `-t` now takes an argument.)

Replace the options block of the usage string with:

```
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
```

Update the final call to pass `cmd_timeout`:

```scala
      eval_at(options, thy_file, line, command = command, logic = logic,
        dirs = dirs.toList, unicode_symbols = unicode_symbols,
        show_state = show_state, timing = timing, cmd_timeout = cmd_timeout,
        verbose = verbose, progress = progress)
```

- [ ] **Step 6: Reinstall**

Run:
```bash
bash isa_cli_tools/install.sh /Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
```
Expected: `scala_build` succeeds. Fix any compile error and re-run before proceeding.

- [ ] **Step 7: Run tests to verify GREEN**

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
# (a) timeout on a loop before the target line
timeout 60 "$DEV/bin/isabelle" eval_at -l HOL -t 8 tst/Thy_Tests/test9/Loop_Test.thy 9; echo "rc=$?"
# (b) -T now reports timings
"$DEV/bin/isabelle" eval_at -l HOL -T tst/Thy_Tests/test6/Scratch.thy 8 2>&1 | grep -m1 'Timing line'
# (c) inject-mode timeout
timeout 60 "$DEV/bin/isabelle" eval_at -l HOL -t 8 tst/Thy_Tests/test6/Scratch.thy 4 'apply (tactic \<open>fn st => let fun loop (i:int) = (Isabelle_Thread.expose_interrupt (); loop (i+1)) in loop 0 end\<close>)'; echo "rc=$?"
# (d) -S still works, normal state query unaffected
"$DEV/bin/isabelle" eval_at -l HOL -S tst/Thy_Tests/test6/Scratch.thy 8 2>&1 | head -3
```
Expected: (a) prints `eval_at: timed out after 8s at line 6 (...)`, `rc=0`; (b) prints a `Timing line ...` line; (c) prints `eval_at: timed out after 8s at line 5 (...)` (the injected line), `rc=0`; (d) prints a proof state with no error.

- [ ] **Step 8: Confirm old `-T`-as-show-types is gone and normal eval still works**

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
"$DEV/bin/isabelle" eval_at -l HOL tst/Thy_Tests/test6/Scratch.thy 8 2>&1 | head -3
```
Expected: a normal proof-state result (regression check that default behavior is intact).

- [ ] **Step 9: Commit**

```bash
git add isa_cli_tools/src/Pure/Tools/eval_at.scala
git commit -m "feat(eval_at): per-command timeout (-t); -S=sorts+types, -T=report timings"
```

---

### Task 4: Documentation sync

**Files:**
- Modify: `isa_cli_tools/README.md`
- Modify: `CLAUDE.md`
- Modify: `/Users/jonathan/.claude/skills/isabelle-proof-workflow/SKILL.md`

**Interfaces:**
- Consumes: the final flag semantics from Tasks 2–3.
- Produces: docs consistent with the shipped tools.

- [ ] **Step 1: Update `isa_cli_tools/README.md`**

Make these edits (use `grep -n` to locate each):
- `eval_at` options table: replace the `-T` show-types row with `-S … show sorts and types` (remove the separate `-T`/`-S` split), add `-t SECS … per-command timeout (default 60; 0 disables)`, and change the timing row to `-T … report timing for each processed line`.
- The "`-t` flag (Timing)" section (~line 433): retitle to "`-T` flag (Timing)" and change every `eval_at -t` example to `eval_at -T`.
- `desorry` options table (~line 516): change `-t SECS … Sledgehammer timeout per sorry (default: 30)` to `-t SECS … per-command timeout for replayed transitions (default: 60; 0 disables)`, and add a sentence that Sledgehammer is fixed at 50s/sorry.
- `desorry` example (~line 531): change `desorry -t 60 Foo.thy` and its comment to reflect the timeout meaning (e.g. `# Use a 120-second per-command timeout`).
- Troubleshooting (~line 611): the "longer timeout (`-t 60` for desorry…)" tip no longer applies to Sledgehammer — reword to point at the fixed 50s and the new per-command `-t`.
- Add a short subsection describing the per-command timeout / abort-and-report behavior for both tools.

- [ ] **Step 2: Verify README has no stale `-t`-as-Sledgehammer wording**

Run: `grep -nE 'Sledgehammer timeout|-t 60|-t SECS' isa_cli_tools/README.md`
Expected: no remaining claim that `-t` sets Sledgehammer time; `-t SECS` rows describe the per-command timeout.

- [ ] **Step 3: Update `CLAUDE.md`**

In the "Testing the tools" section, ensure any `desorry`/`eval_at` invocation flags match the new semantics (no `-t` as Sledgehammer). Add one line noting the per-command timeout (`-t`, default 60s) and that a looping tactic now aborts with the offending line.

- [ ] **Step 4: Update the `isabelle-proof-workflow` skill**

In `/Users/jonathan/.claude/skills/isabelle-proof-workflow/SKILL.md`, the `desorry … [-t 60] …` line (~line 44): `-t` now means per-command timeout, not Sledgehammer time. Reword so agents know `-t` bounds each replayed command (and that a hung tactic is reported by line), and drop any implication that `-t` raises Sledgehammer time.

- [ ] **Step 5: Commit**

```bash
git add isa_cli_tools/README.md CLAUDE.md
git commit -m "docs: per-command timeout semantics; desorry Sledgehammer fixed at 50s"
```
(The skill file lives outside the repo; note in the commit body that `~/.claude/skills/isabelle-proof-workflow/SKILL.md` was also updated.)

---

## Self-Review

**Spec coverage:**
- Per-command (not total) timeout → Tasks 2 (desorry `execute_transition`) & 3 (eval_at `exec_timing`). ✓
- `Timeout.apply` mechanism, `0` disables → both tasks (`Time.fromMilliseconds (1000 * cmd_timeout)`; `Timeout.ignored` short-circuits at 0). ✓
- Abort on first timeout, no file mutation (desorry) → Task 2 Steps 6–7 (handler wraps body before `File.write`; verified by diff + no `.backup`). ✓
- desorry: remove `-t` Sledgehammer, hard-code 50s; `-t` = per-command → Task 2. ✓
- eval_at: `-S` sorts+types, delete `-T` show-types, `-t` timeout, `-T` timings → Task 3. ✓
- `-t` means the same in both tools → Tasks 2 & 3 both use `cmd_timeout`. ✓
- Scope desorry + eval_at only; no isabelle_dev edits → respected. ✓
- Docs: README, CLAUDE.md, skill, source usage strings → usage strings in Tasks 2/3; prose docs in Task 4. ✓
- Tests: looping fixture + test7 no-regression + reload check → Task 1, Task 2 Step 8. ✓
- Known limitation (uninterruptible loop) → fixture uses `expose_interrupt` so the test exercises the realistic interruptible case. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; every test step shows the command and expected output. ✓

**Type/name consistency:** `cmd_timeout` used uniformly across desorry/eval_at defs, wrappers, and ML; ML exceptions `Desorry_Timeout`/`Eval_Timeout` each carry `(int * string)` and are raised+caught within the same generated script; `ml_script_phase1` arity (6) matches its single call site; `ml_script_state`/`ml_script_inject` new arities match their call sites; `sledgehammer_timeout` constant defined in Task 2 Step 2 and used in Step 3. ✓
