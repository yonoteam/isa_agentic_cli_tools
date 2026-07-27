# isa_cli_tools — CLI Tools for Isabelle/HOL

Two command-line tools for Isabelle that work without jEdit or PIDE:

- **`isabelle eval_at`** — evaluate any Isabelle command at a given theory line
- **`isabelle desorry`** — replace `sorry` proofs with Sledgehammer results

Both tools auto-detect the logic session and handle sibling imports
automatically. They require the chosen logic's session heap to be **already
built**, and verify this before doing any work (see
[Sessions](#sessions-how-the-tools-find-the-right-logic)).

---

## Installation

### Prerequisites

An Isabelle development installation compatible with repository changeset
`5085f506929bfb3124fbfb1ab0d50063e537fc25` on the `default` branch
(2026-07-27, `tuned names`).

```bash
isabelle version   # must print a version string
```

### Automated install

```bash
bash install.sh /path/to/isabelle
```

The installer copies the Scala sources into the Isabelle source tree,
registers them in `etc/build.props` and `isabelle_tool.scala`, then
rebuilds (`isabelle scala_build`). It is idempotent — safe to re-run.

To update to newer sources, re-run the same command. The Scala files are
always overwritten; registration entries are skipped if already present.
Use `-f` to force re-registration:

```bash
bash install.sh -f /path/to/isabelle
```

### Manual install

If you prefer to install by hand (or the installer doesn't work for your
Isabelle version), follow these four steps. `$ISA` refers to your
Isabelle installation root (the directory containing `bin/isabelle`).

**Step 1.** Copy the Scala source files:

```bash
cp src/Pure/Tools/eval_at.scala  $ISA/src/Pure/Tools/
cp src/Pure/Tools/desorry.scala  $ISA/src/Pure/Tools/
```

**Step 2.** Register the source files in `$ISA/etc/build.props`.
Find the alphabetically sorted list of `src/Pure/Tools/*.scala` entries
and add two lines (maintaining alphabetical order):

```
  src/Pure/Tools/desorry.scala \
  src/Pure/Tools/eval_at.scala \
```

For example, they go right before the `flarum.scala` entry:

```
  ...
  src/Pure/Tools/desorry.scala \
  src/Pure/Tools/eval_at.scala \
  src/Pure/Tools/flarum.scala \
  ...
```

**Step 3.** Register the tools in
`$ISA/src/Pure/System/isabelle_tool.scala`. Find the alphabetically
sorted list of `*.isabelle_tool` entries and add two lines:

```scala
  Desorry.isabelle_tool,
  Eval_At.isabelle_tool,
```

For example, they go right before the `Export.isabelle_tool` entry:

```scala
  ...
  Desorry.isabelle_tool,
  Eval_At.isabelle_tool,
  Export.isabelle_tool,
  ...
```

**Step 4.** Rebuild the Scala layer:

```bash
$ISA/bin/isabelle scala_build
```

### Manual update (sources already registered)

If the tools were previously installed and you only need to pick up
source changes, you can skip the registration steps:

```bash
cp src/Pure/Tools/eval_at.scala  $ISA/src/Pure/Tools/
cp src/Pure/Tools/desorry.scala  $ISA/src/Pure/Tools/
$ISA/bin/isabelle scala_build
```

### Verify

```bash
isabelle eval_at   # prints usage
isabelle desorry   # prints usage
```

---

## Sessions: How the Tools Find the Right Logic

**This is the most important concept for using these tools correctly.**

Every Isabelle theory imports parent theories (`imports Main`, `imports
Complex_Main`, `imports "HOL-Algebra.Ring"`, etc.). These parents live in
**sessions** — Isabelle's compilation units, defined in `ROOT` files. Before
the tools can process a theory, they must load the correct session heap
containing all the imported theories.

### How automatic session derivation works

Both tools read the theory's `imports` line and map each import to a session:

| Import in `.thy` file | Derived session | Why |
|---|---|---|
| `Main` | `HOL` | `Main` is a global theory in the `HOL` session |
| `Complex_Main` | `HOL` | `Complex_Main` is a global theory in the `HOL` session |
| `"HOL-Library.Multiset"` | `HOL-Library` | Qualified name: everything before the last `.` |
| `"HOL-Algebra.Ring"` | `HOL-Algebra` | Qualified name: `HOL-Algebra` |
| `"HOL-Analysis.Analysis"` | `HOL-Analysis` | Qualified name: `HOL-Analysis` |
| `Pure` | `Pure` | The base logic |

When a theory imports from multiple sessions, the tools pick the **deepest**
one (most dependencies in the session graph). For example, if a theory imports
both `Complex_Main` (HOL) and `"HOL-Library.Multiset"` (HOL-Library),
HOL-Library is selected because it depends on HOL.

### When automatic derivation works

For the vast majority of theories, automatic derivation just works:

```bash
# Theory importing Main or Complex_Main -> derives HOL automatically
isabelle eval_at MyTheory.thy 10

# Theory importing HOL-Library -> derives HOL-Library automatically
isabelle eval_at MyTheory.thy 10

# Theory importing HOL-Algebra.Ring -> derives HOL-Algebra automatically
isabelle eval_at AlgebraWork.thy 15
```

### When you need `-l` (override session)

Override session derivation with `-l SESSION` when:

1. **The theory imports only unqualified names from an AFP or custom session**
   that the tools cannot find without a ROOT file directory:

   ```isabelle
   theory MyWork imports Ring   (* bare name, not "HOL-Algebra.Ring" *)
   begin
   ```

   ```bash
   # The tools cannot map bare "Ring" to HOL-Algebra
   # Fix: tell them which session
   isabelle eval_at -l HOL-Algebra MyWork.thy 10
   isabelle desorry -l HOL-Algebra MyWork.thy
   ```

2. **The theory lives inside a custom session** defined by a local ROOT file,
   but the ROOT file is in a different directory:

   ```bash
   # The ROOT file defining session "My_Session" is in /project/root/
   isabelle eval_at -l My_Session -d /project/root MyWork.thy 10
   isabelle desorry -l My_Session -d /project/root MyWork.thy
   ```

### When you need `-d` (session directory)

The `-d DIR` flag tells the tools where to find ROOT files that define
sessions. Use it when:

1. **The theory imports siblings from a directory with a ROOT file**:

   ```bash
   # ROOT file is in /project/ defining session "My_Project"
   isabelle eval_at -d /project Subdir/MyTheory.thy 10
   ```

2. **The theory imports from an AFP entry** not in the standard search path:

   ```bash
   isabelle eval_at -d /path/to/afp/thys/Coinductive MyWork.thy 10
   ```

3. **Multiple session directories are needed**:

   ```bash
   isabelle eval_at -d /project/sessions -d /path/to/afp/thys/Foo MyWork.thy 10
   ```

### The session heap must already be built

The tools **do not build session heaps.** Before processing a theory, each
tool verifies that the chosen logic's heap is built and up to date. If it is
not, the tool stops immediately with an actionable error instead of launching
a (potentially multi-hour) compile:

```bash
isabelle eval_at -l HOL-Algebra MyTheory.thy 10
#> *** Session heap for "HOL-Algebra" is not available or not up to date;
#> *** refusing to build it automatically.
#> *** How to run this safely:
#> ***   1. Choose a session whose heap is already built.
#> ***   2. If the theory belongs to an unbuilt session, use that session's
#> ***      built parent with -l and pass -d for the directory containing the ROOT.
#> ***   3. Check first with: isabelle build -n SESSION [-d ROOT_DIR]
```

This is deliberate: a runaway background compile is the main hazard in an
unattended/agent workflow. Build the heap yourself, once, as an explicit
setup step, then query against it:

```bash
# One-time setup: build the heap (slow — minutes for HOL, longer for others)
isabelle build -b HOL

# Thereafter every run reuses the cached heap and starts in seconds
isabelle eval_at MyTheory.thy 10
isabelle eval_at MyTheory.thy 20
```

Heaps live under `$ISABELLE_HEAPS/polyml-*/` (typically
`~/.isabelle/<identifier>/heaps/...` for a release, or `~/.isabelle/heaps/...`
for an unversioned repository build). List the built ones to see what is
already available — each filename is a built session
(`isabelle getenv ISABELLE_HEAPS` prints the exact directory).

### Session examples from the Isabelle distribution

Here are real theories and the sessions they require:

```isabelle
(* Session: HOL (the default for most work) *)
theory MyBasics imports Main begin ... end
theory MyAnalysis imports Complex_Main begin ... end

(* Session: HOL-Library *)
theory MyMultisets imports "HOL-Library.Multiset" begin ... end
theory MyTimeFunctions imports Complex_Main "HOL-Library.Time_Functions" begin ... end

(* Session: HOL-Algebra *)
theory MyRings imports "HOL-Algebra.Ring" begin ... end

(* Session: HOL-Analysis *)
theory MyMeasure imports "HOL-Analysis.Analysis" begin ... end
```

For each of these, the tools derive the correct session automatically from the
qualified import names.

### Agent preflight: choosing the logic session

**Read this before scripting `eval_at`/`desorry` (especially from an AI agent).**

Before doing anything else, both tools check that the chosen logic's heap is
built and up to date. If it is not, they **stop with an error** rather than
building it (a custom session can take hours to compile). The single most
important rule is therefore: *make `-l` point at a session whose heap already
exists.* Pick an unbuilt session and the tool refuses — so choosing the right
session is on you, the caller.

Preflight checklist:

1. **Read the theory's `imports` line and the local `ROOT`.** Identify which
   session the theory belongs to (the `theories` clause of a `session` entry)
   and what it actually depends on. A theory that only imports siblings +
   `HOL-Analysis.Analysis` only needs `HOL-Analysis` in scope, regardless of
   how big its own session is.

2. **Check what is already built — never assume.**
   - List built heaps:
     `ls "$(isabelle getenv -b ISABELLE_HEAPS)"/polyml-*/`
     (each filename is a built session; the directory is
     `~/.isabelle/<identifier>/heaps/...` for a release such as Isabelle2025,
     or `~/.isabelle/heaps/...` for an unversioned repository build). Names of the form
     `Foo_requirements(Bar)` are **requirement images** produced by
     `isabelle build -R Foo` / `isabelle jedit -R Foo` — they contain Foo's
     *ancestry*, **not** Foo's own theories.
   - Dry-run a session: `isabelle build -n SESSION` (add `-d DIR` if the
     session is defined by a local ROOT). If it lists nothing to build, the
     heap is up to date.
   - Check session resolvability: `isabelle sessions [-d DIR] SESSION`.

3. **Choose `-l` deliberately:**
   - If the theory's *own* session heap is built, use it.
   - If only a **requirements image** is built (the common case after
     `isabelle jedit -d DIR -R SESSION`), the session's own theories are *not*
     in any heap. Pass the **parent session** instead — the `PARENT` in
     `session SESSION = "PARENT" + ...` in the ROOT — provided its heap is
     built and contains the needed ancestry. The tool then replays the target
     theory *and its siblings* from source on top of that heap.
   - **Prefer a session whose heap is already built.** Passing
     `-l <unbuilt session>` is not catastrophic — the tool refuses up front
     rather than building — but it just wastes a round-trip, so aim to get it
     right the first time.

4. **Pass `-d DIR`** (the directory containing the ROOT) so sibling and
   bare-name imports resolve. It is best to set `-l` explicitly as well: with
   `-d` and **no** `-l`, automatic derivation may map a bare sibling import to
   its member session — which may be the *unbuilt* one — and the tool then
   refuses, costing you a round-trip.

Worked example. A theory belongs to a local session `My_Session` whose own
heap has **not** been built — only a requirements image exists (e.g. from
`isabelle jedit -d . -R My_Session`). The ROOT declares
`session My_Session = "Parent_Session" + ...`, and `Parent_Session` is built
and already contains everything the theory imports. Then:

```bash
# Correct: reuses the built parent heap, replays the theory + siblings from
# source on top, and builds nothing.
isabelle eval_at -l Parent_Session -d . My_Theory.thy 15

# Refused: My_Session's heap is not built -> the tool stops with an error
# (it does not compile). You then re-run with the parent session as above.
isabelle eval_at -l My_Session -d . My_Theory.thy 15

# Also refused: no -l -> derivation may pick the unbuilt member session,
# and the tool stops with the same error. Set -l to avoid the round-trip.
isabelle eval_at -d . My_Theory.thy 15
```

---

## eval_at — Evaluate a Command at a Theory Line

```
isabelle eval_at [OPTIONS] THY_FILE LINE [COMMAND]
```

Replays every transition from the top of `THY_FILE` down to `LINE`, then
either shows the state (no COMMAND) or injects and executes COMMAND after
LINE.

### State mode (no COMMAND)

When COMMAND is omitted, `eval_at` shows the output and proof state at LINE.
This captures everything jEdit's Output panel would show: command output (for
`term`, `thm`, `value` etc.) and proof subgoals.

```bash
# Inside a proof: shows current subgoals
isabelle eval_at MyTheory.thy 15
#> proof (prove)
#> goal (1 subgoal):
#>  1. P x

# At a 'term' command: shows the term's type
isabelle eval_at MyTheory.thy 5
#> "2"
#>   :: "nat"

# At theory level (blank line): no output
isabelle eval_at MyTheory.thy 3
#> No proof state.
```

State mode reports **every** error up to LINE, not just the first: after a
failed transition it continues from the previous state, so one run surfaces all
independent (e.g. theory-level) errors instead of one-per-run. Point LINE at the
last line to validate a whole file in a single pass.

```bash
isabelle eval_at MyTheory.thy 40    # last line
#> Error at line 7 (thm foo): Undefined fact: "foo" ...
#> Error at line 19 (lemma bar: "X = Y"): Type unification failed ...
```

(A failure inside a proof body can still produce follow-on noise; in the
intended skeleton workflow every leaf is `sorry`, so failures are predominantly
theory-level and recover cleanly.)

Warnings and legacy-feature messages in state mode are attributed to their line
too, as `Warning at line N (...): msg` (instead of the unattributed `### msg`),
so they are as easy to locate and grep as errors:

```bash
isabelle eval_at MyTheory.thy 40
#> Warning at line 12 (...): Introduced fixed type variable(s): 'a in "x__"
```

### Command injection mode

When COMMAND is given, it is injected after LINE and executed. The command's
output is captured and printed.

```bash
# Print a theorem
isabelle eval_at MyTheory.thy 10 'thm conjI'

# Check a term's type
isabelle eval_at MyTheory.thy 10 'term "map f (filter P xs)"'

# Evaluate an expression
isabelle eval_at MyTheory.thy 10 'value "[1,2,3::nat]"'

# Search for theorems
isabelle eval_at MyTheory.thy 10 'find_theorems "_ + _ = _ + _"'
isabelle eval_at MyTheory.thy 10 'find_theorems name: "assoc"'
isabelle eval_at MyTheory.thy 15 'find_theorems intro'

# Apply a proof method and see the new state (-s flag)
isabelle eval_at -s MyTheory.thy 15 'apply auto'
isabelle eval_at -s MyTheory.thy 15 'apply (induction xs)'
isabelle eval_at -s MyTheory.thy 15 'apply (rule conjI)'

# Run sledgehammer (automatic proof search)
isabelle eval_at MyTheory.thy 15 'sledgehammer'

# Try proof methods automatically
isabelle eval_at MyTheory.thy 15 'try'

# Search for counterexamples
isabelle eval_at MyTheory.thy 15 'nitpick'
```

### Options

| Option | Description |
|--------|-------------|
| `-S` | Show sorts and types in output |
| `-U` | Output Unicode symbols instead of Isabelle's ASCII encoding |
| `-d DIR` | Add a session directory for ROOT file resolution (repeatable) |
| `-l NAME` | Override the automatically derived logic session |
| `-o OPTION` | Override an Isabelle system option |
| `-s` | Show proof state after command output (for injection mode) |
| `-t SECS` | Per-command timeout in seconds (default: 60; 0 disables) |
| `-T` | Report timing for each processed line |
| `-v` | Verbose: show derived session, heap-check progress, ML errors |

Options must come before positional arguments.

### The `-T` flag (Timing)

Use `-T` to see how long Isabelle takes to process each command from the beginning
of the theory up to the target line. This is useful for identifying slow
steps in a proof or evaluating the performance of an injected command.

```bash
# Report timing for all lines leading up to line 15
isabelle eval_at -T MyTheory.thy 15

# Report timing for an injected command (e.g. sledgehammer)
isabelle eval_at -T MyTheory.thy 15 'sledgehammer'
#> ...
#> Timing line 15 (sledgehammer): 0.438s elapsed time, 0.711s cpu time, 0.167s GC time
```

Timing is reported for each non-ignored transition and includes the command
name (e.g., `theory`, `lemma`, `apply`, `done`).

### The `-s` flag

Commands like `apply`, `rule`, `simp`, etc. modify the proof state but produce
no text output. Use `-s` to print the resulting proof state after the command:

```bash
# Without -s: no output shown (the tactic ran but printed nothing)
isabelle eval_at MyTheory.thy 15 'apply auto'
#> eval_at: line 15 (...): no command output was produced. Use -s to show the resulting proof state.

# With -s: shows the new proof state
isabelle eval_at -s MyTheory.thy 15 'apply auto'
#> proof (prove)
#> goal (1 subgoal):
#>  1. ...
```

Informational commands (`thm`, `term`, `value`, `find_theorems`) produce their
own output; `-s` is unnecessary for them (but harmless).

### Per-command timeout (`-t`)

Both `eval_at` and `desorry` accept `-t SECS` (default: 60; use 0 to disable).
This bounds the time allowed for each individual replayed Isabelle transition
(e.g. a single `apply`, `by`, or `have` step). It is a **per-command** timeout,
not a total timeout — so large theories with many fast commands are not
penalized, but a single looping tactic is caught and named.

When a command exceeds `-t`:

- **`eval_at`** aborts and prints the offending line, e.g.:
  ```
  eval_at: timed out after 60s at line 42 (apply (induct n)).
  ```
  In injection mode the injected command and its line are reported.

- **`desorry`** aborts immediately, prints the offending line, and writes
  **nothing** — the input file is left unchanged and no `.backup` is created:
  ```
  desorry: timed out after 60s at line 17 (apply auto); no changes written.
  ```

To disable the timeout entirely (not recommended for agent use): `-t 0`.

### Overall wall-clock safeguard

Both tools also enforce an **overall wall-clock bound** that hard-terminates the
spawned ML process group if a whole run runs too long (default **900 s / 15 min**).
Unlike `-t`, this also catches hangs *outside* command evaluation — session-heap
loading, GC/swap thrash — that would otherwise leave an **orphaned multi-GB
`poly` process** behind after the controlling tool is gone.

This is a machine-protection safeguard, **not** a per-call flag, and agents
should not need to think about it. For the rare genuinely-huge heap (or for
tests) it can be overridden via the environment variable
`ISABELLE_CLI_TOOLS_WALL_TIMEOUT` (seconds; `0` disables):

```bash
ISABELLE_CLI_TOOLS_WALL_TIMEOUT=3600 isabelle eval_at Big.thy 200
```

### Prior commands and errors

`eval_at` replays every Isabelle command from the top of the theory file down to
LINE. How prior errors are handled depends on the mode:

- **State mode (no COMMAND)** reports *every* error up to LINE and keeps going,
  continuing from the state before each failed transition. Use it to validate a
  file (point LINE at the last line). The proof state shown at LINE is only
  meaningful when the commands it depends on succeeded.
- **Injection mode (COMMAND given)** requires a clean prefix: an error before the
  injection point aborts with `Error before injection at line N`, because the
  injected command cannot run on a broken context.

```
Error at line 20 (  apply (rule foo)): Undefined fact: "foo"
```

Before calling eval_at:

1. **To get a valid state/output at LINE**, make sure the commands it depends on
   are error-free — run state mode first and check for any `Error at line` lines.

2. **`sorry` is tolerated** (accepted under `quick_and_dirty`), but changes
   the logical context. Proofs found after a `sorry` may depend on sorry'd
   facts and fail when the sorry is replaced.

3. **LINE must be inside a proof** for proof-related commands (`apply`,
   `sledgehammer`, `find_theorems intro`). At theory level, use informational
   commands (`term`, `thm`, `value`, `find_theorems "pattern"`).

---

## desorry — Replace sorry Proofs with Sledgehammer Results

```
isabelle desorry [OPTIONS] THY_FILE [LINE]
```

Finds all `sorry` proofs in `THY_FILE` (up to `LINE` if given), runs
Sledgehammer on each in parallel, and overwrites the file in-place with
found proofs substituted for the corresponding `sorry`s.

### Options

| Option | Description |
|--------|-------------|
| `-L LINES` | Comma-separated list of line numbers to target (e.g., `42,105`) |
| `-d DIR` | Add session directory for import resolution (repeatable) |
| `-l NAME` | Override automatically derived logic session |
| `-o OPT` | Override Isabelle system option |
| `-t SECS` | Per-command timeout for replayed transitions (default: 60; 0 disables) |
| `-v` | Verbose |

Sledgehammer is run at a fixed timeout of 50 seconds per sorry (not configurable).

### Examples

```bash
# Replace all sorry's in a file
isabelle desorry Foo.thy

# Only process sorry's up to line 100
isabelle desorry Foo.thy 100

# Only process sorry's at specific lines
isabelle desorry -L 42,105 Foo.thy

# Use a 120-second per-command timeout
isabelle desorry -t 120 Foo.thy

# Specify the logic session explicitly
isabelle desorry -l HOL-Analysis Foo.thy
```

### How it works

1. **Phase 1 (sequential):** Replays all transitions from the theory
   header, collecting the proof state at each `sorry` position. If the `-L`
   flag is used, only `sorry` commands at the specified lines are collected.
2. **Phase 2 (parallel):** Runs Sledgehammer concurrently on all
   collected proof states.
3. **Output:** Overwrites `THY_FILE` in-place with each processed `sorry`
   replaced by the Sledgehammer-found proof text (e.g. `by simp`,
   `by (metis foo bar)`), preserving indentation. Sorry's for which no
   proof was found (or those excluded by `-L`) are left unchanged.

### Backup mechanism

Before modifying `THY_FILE`, desorry saves a copy to `THY_FILE.backup`.
This provides a safety net if you want to revert:

```bash
# First run: Foo.thy.backup is created from the original Foo.thy
isabelle desorry Foo.thy

# You review and accept the changes (edit Foo.thy, add new sorry's, etc.)
# Second run: Foo.thy.backup is overwritten with your current Foo.thy
isabelle desorry Foo.thy
```

The backup always reflects the state of the file *before* the most recent
desorry run. To revert, simply copy the backup back:

```bash
cp Foo.thy.backup Foo.thy
```

### Technical note

Sledgehammer structures (defined in HOL) are not in the Poly/ML global
namespace (Pure.thy sets `ML_write_global = false`). The tool handles
this by evaluating the Sledgehammer code within the HOL theory context
via `ML_Context.eval_file`.

---

## Troubleshooting

**"Unknown Isabelle tool: eval_at"**
Run `isabelle scala_build` to rebuild, then retry.

**"No output at this line."**
The target line has no Isabelle command and no proof state. Try a different
line.

**"eval_at: line N (...): failed (return code ...)"**
The ML process crashed. Add `-v` to see details. Common causes:
- Missing imports — use `-d` to add the session directory
- Wrong session — use `-l` to override

**"eval_at: line N (...): no command output was produced."**
The injected command ran but produced no text output. Use `-s` to see the
resulting proof state. This is normal for `apply`, `by`, `rule`, etc.

**"Error before injection at line ..."**
A theory command before LINE failed. Fix the theory file at the indicated
line, or choose an earlier LINE.

**"Session heap for ... is not available or not up to date; refusing to build it automatically"**
The chosen logic's heap is not built. The tools never build heaps. Either
pick a session whose heap is already built (`-l`), or build the needed heap
yourself first with `isabelle build -b SESSION [-d ROOT_DIR]`. See
[Sessions](#sessions-how-the-tools-find-the-right-logic). Building HOL takes
5-15 minutes; HOL-Analysis can take 30+ minutes — which is exactly why the
tools leave that decision to you.

**Sledgehammer finds nothing**
`desorry` runs Sledgehammer at a fixed 50-second timeout per sorry (not
configurable). For `eval_at`, try a longer timeout:
`'sledgehammer [timeout = 60]'`. In both cases, try different provers
(`'sledgehammer [provers = "vampire e cvc5"]'`), or simplify the goal
with manual tactics first.

**Sibling imports not found**
Use `-d DIR` to point at the directory containing the ROOT file that
defines the session.

---

## Known Limitations

- **Startup cost:** Full theory elaboration from line 1 to LINE runs every
  invocation. Deep lines in large files can be slow.
- **Bare-name imports** from non-sibling directories are not auto-detected.
  Use `-d DIR` to point at the directory containing the ROOT file.
- **Transitive path-based imports** inside sibling theories are not
  auto-detected. Use `-d`.
- **Position is start-of-command:** The tools use the starting line of each
  Isabelle command. If a command spans multiple lines, only its first line
  counts as the "position" for that command.
- **External provers** (for `sledgehammer`, `nitpick`) must be available in
  the Isabelle contrib directory. Standard Isabelle distributions include them.
