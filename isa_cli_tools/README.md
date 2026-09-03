# isa_cli_tools — CLI Tools for Isabelle/HOL

Two command-line tools for Isabelle that work without jEdit or PIDE:

- **`isabelle eval_at`** — evaluate any Isabelle command at a given theory line
- **`isabelle desorry`** — replace `sorry` proofs with Sledgehammer results

Both tools attempt to derive the logic session from the target theory's imports
and load sibling imports from source. Custom sessions can require explicit
`-l` and `-d` options. The chosen logic's session heap must be **already
built**; the tools verify this before starting Isabelle/ML work (see
[Sessions and Logic Selection](#sessions-and-logic-selection)).

## Contents

- [Recommended workflow: sorry skeletons](#recommended-workflow-sorry-skeletons)
- [For AI agents: session preflight](#for-ai-agents-session-preflight)
- [Installation](#installation)
- [Shared execution behavior](#shared-execution-behavior)
- [`eval_at`](#eval_at--evaluate-a-command-at-a-theory-line)
- [`desorry`](#desorry--replace-sorry-proofs-with-sledgehammer-results)
- [Sessions and logic selection](#sessions-and-logic-selection)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)

---

## Recommended Workflow: sorry Skeletons

**Keep the theory loadable at every step.** A theory whose unfinished leaves are all
`sorry` still loads, which is what makes these tools fast: `eval_at` state mode reports
*every* error in one pass, and `desorry` attacks *every* open leaf in parallel from one
heap load. A theory containing one half-written proof loads up to the break and tells
you nothing past it.

Do not write proofs and then check them. Write structure, keep it loading, and let the
tools fill in the leaves:

1. **Preflight the session once** — `isabelle build -b -n -d . SESSION`. Fix `-l PARENT
   -d .` here and reuse it for every later command. See
   [Session Preflight](#for-ai-agents-session-preflight).
2. **Replace the obligation with a skeleton** — the Isar structure you believe the proof
   has (induction, case split, intermediate `have`s), every branch terminated by `sorry`.
   Close nothing yet.
3. **Validate the skeleton in one pass** — `isabelle eval_at -l PARENT -d . T.thy $(wc -l
   < T.thy)`. State mode reports every error and recovers after each, so one run surfaces
   all structural problems. Repeat until clean.
4. **Let `desorry` close the leaves** — `isabelle desorry -l PARENT -d . T.thy`. One
   replay, then Sledgehammer on all leaves in parallel; only verified proofs are written.
5. **Work the survivors** — `grep -n sorry T.thy`, then `eval_at` at those lines to see
   the goal, try methods with `-s`, or `find_theorems`. A survivor usually means the leaf
   is too big a step: split it into a finer skeleton and return to step 3.
6. **Build only as the final gate** — `grep` for leftover `sorry`, `rm -f *.thy.backup`,
   then `isabelle build -d . -o quick_and_dirty=false SESSION`. A session build costs
   minutes to tens of minutes; it is the acceptance check, not the feedback loop.

Two traps worth knowing before you start: `sorry` is accepted under `quick_and_dirty` but
changes the logical context, so a proof found above a remaining `sorry` can fail once
that `sorry` is replaced — re-run step 3 after every `desorry` pass. And `desorry` writes
`T.thy.backup` beside the theory on every successful commit, which is a new file in the
project; delete backups before any final build or "only these files changed" check.

The full version of this workflow, with a worked example and a mistakes table, is in
[`skills/proving-with-sorry-skeletons/SKILL.md`](../skills/proving-with-sorry-skeletons/SKILL.md).

---

## For AI Agents: Session Preflight

Before invoking `eval_at` or `desorry`:

1. Read the target theory's `imports` and its local `ROOT`.
2. Choose a logic session whose actual heap is already built. The tools never
   build heaps and immediately refuse an unavailable or outdated heap.
3. Check the candidate with `isabelle build -b -n SESSION`; for a custom
   session, use `isabelle build -b -n -d ROOT_DIR SESSION`. The `-b` option
   makes the dry run check for an actual heap image. If the theory's own
   session is unbuilt, use its built parent with `-l`.
4. Pass `-d ROOT_DIR` when sibling or bare-name imports depend on a local
   `ROOT`.

Typical invocation for a theory in an unbuilt custom session:

```bash
isabelle eval_at -l Parent_Session -d /project/root My_Theory.thy 15
isabelle desorry -l Parent_Session -d /project/root My_Theory.thy
```

A requirements image such as `My_Session_requirements(Parent_Session)` contains
the session's ancestry, not `My_Session`'s own theories; use the built parent
heap in that case. See [Sessions and Logic Selection](#sessions-and-logic-selection)
for derivation rules and detailed examples.

---

## Installation

### Prerequisites

Choose the repository branch that matches the Isabelle installation:

| Branch | Compatible Isabelle version |
|---|---|
| `main` | Isabelle development repository at changeset `5085f506929bfb3124fbfb1ab0d50063e537fc25` (`tuned names`, 2026-07-27) |
| `2025` | Isabelle2025 |
| `2025-2` | Isabelle2025-2 |

For example:

```bash
git switch 2025-2
cd isa_cli_tools
bash install.sh /Applications/Isabelle2025-2.app
```

The branches contain release-specific source adaptations. Select the branch
before installing, and do not install sources from one branch into a different
Isabelle release.

Check the Isabelle installation you intend to modify explicitly:

```bash
/path/to/isabelle/bin/isabelle version

# For main, also verify the Isabelle development checkout:
hg -R /path/to/isabelle id -i
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
cp src/Pure/Tools/cli_tool_common.scala $ISA/src/Pure/Tools/
cp src/Pure/Tools/eval_at.scala  $ISA/src/Pure/Tools/
cp src/Pure/Tools/desorry.scala  $ISA/src/Pure/Tools/
```

**Step 2.** Register the source files in `$ISA/etc/build.props`.
Find the alphabetically sorted list of `src/Pure/Tools/*.scala` entries
and add three lines (maintaining alphabetical order):

```
  src/Pure/Tools/cli_tool_common.scala \
  src/Pure/Tools/desorry.scala \
  src/Pure/Tools/eval_at.scala \
```

For example, they go right before the `flarum.scala` entry:

```
  ...
  src/Pure/Tools/cli_tool_common.scala \
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
cp src/Pure/Tools/cli_tool_common.scala $ISA/src/Pure/Tools/
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

## Shared Execution Behavior

### Per-command replay timeout (`-t`)

Both tools accept `-t SECS` (default: 60; `0` disables it). This bounds each
individual Isabelle transition replayed from the target theory, such as one
`apply`, `by`, or `have` command. For `eval_at` injection mode it also bounds
the injected command. It is not a total runtime limit.

When a command exceeds the limit, the tool reports its line and exits nonzero.
`desorry` aborts before committing any proof replacements:

```text
eval_at: timed out after 60s at line 42 (apply (induct n)).
desorry: timed out after 60s at line 17 (apply auto); no changes written.
```

The `-t` option does not control Sledgehammer proof-search time inside
`desorry`; see [`desorry` proof search](#proof-search).

### Overall wall-clock safeguard

Both tools impose a separate hard limit on the complete spawned Isabelle/ML
process: 900 seconds (15 minutes) by default. This safeguard also catches
delays outside individual command evaluation, such as heap loading or severe
GC and memory pressure, and terminates the process group to avoid leaving an
orphaned `poly` process.

Override the default with `ISABELLE_CLI_TOOLS_WALL_TIMEOUT` in seconds. A value
of `0` disables the safeguard:

```bash
ISABELLE_CLI_TOOLS_WALL_TIMEOUT=3600 isabelle eval_at Big.thy 200
```

### Progress, heartbeats, and output streams

Both tools report preparation, heap checking, ML startup, and replay phases.
If 15 seconds pass without a visible result, warning, or phase change, a
heartbeat reports the latest replay or proof-search position:

```text
desorry: preparing theory and checking session heap...
desorry: starting ML process...
desorry: replaying 2,400 transitions...
desorry: still working: replay 1,250/2,400 transitions (15s without output)
```

Command and proof results go to stdout. Progress, heartbeats, warnings, and
fatal diagnostics go to stderr. Fatal replay, timeout, ML-process, and
watchdog outcomes exit nonzero.

Ordinary replay errors in `eval_at` state mode are intentionally diagnostic:
they are reported on stderr, evaluation continues from the preceding state,
and the command exits successfully unless a fatal condition occurs.

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
[recommended skeleton workflow](#recommended-workflow-sorry-skeletons) every leaf
is `sorry`, so failures are predominantly theory-level and recover cleanly.)

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
| `-v` | Show the derived logic and verbose heap-check diagnostics |

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
Sledgehammer on each in parallel, and replaces the `sorry` commands for which
it finds verified proofs. If no proof is found, the file is not modified.

### Options

| Option | Description |
|--------|-------------|
| `-L LINES` | Unique, positive lines of parsed, reachable `sorry` transitions (e.g., `42,105`) |
| `-d DIR` | Add session directory for import resolution (repeatable) |
| `-l NAME` | Override automatically derived logic session |
| `-o OPT` | Override Isabelle system option |
| `-t SECS` | Per-command timeout for replayed transitions (default: 60; 0 disables) |
| `-v` | Show the derived logic and verbose heap-check diagnostics |

### Examples

```bash
# Replace all sorry commands in a file
isabelle desorry Foo.thy

# Only process sorry commands up to line 100
isabelle desorry Foo.thy 100

# Only process sorry commands at specific lines
isabelle desorry -L 42,105 Foo.thy

# Use a 120-second per-command timeout
isabelle desorry -t 120 Foo.thy

# Specify the logic session explicitly
isabelle desorry -l HOL-Analysis Foo.thy
```

### How it works

1. **Phase 1 (sequential):** Replays all transitions from the theory
   header, collecting the proof state at each `sorry` position. If the `-L`
   flag is used, every requested line is checked before proof search: values
   must be unique, positive, within the file, before the optional stop line,
   and the starting line of a parsed `sorry` transition. `desorry` aborts on
   the first ordinary replay error.
2. **Phase 2 (parallel):** Runs Sledgehammer concurrently on all
   collected proof states. A generated proof is accepted only when its
   reconstruction successfully closes the captured goal.
3. **Commit:** The replacement theory is staged separately. Only after proof
   search exits cleanly does `desorry` create the backup and atomically replace
   `THY_FILE`. Each processed `sorry` is replaced by the Sledgehammer-found
   proof text (e.g. `by simp`, `by (metis foo bar)`), preserving indentation.
   `sorry` commands for which no proof was found (or those excluded by `-L`)
   are left unchanged.

### Proof search

Each selected `sorry` receives a separate Sledgehammer invocation with a fixed
50-second scheduling budget. The value is not configurable. It stops new prover
slices from starting after the budget has elapsed, but slices already running
may finish later; it is therefore not a strict 50-second wall-clock deadline.
The [overall watchdog](#overall-wall-clock-safeguard) remains the hard bound for
the complete Isabelle/ML process.

### Backup mechanism

When at least one proof replacement is ready to commit, `desorry` saves the
version of the theory read at invocation start to `THY_FILE.backup` and then
atomically replaces `THY_FILE`. If no proof is found, neither file is changed.
Do not edit the target while `desorry` is running: concurrent changes can be
overwritten. The backup provides a safety net:

```bash
# A successful replacement creates Foo.thy.backup from the original Foo.thy
isabelle desorry Foo.thy

# You review and accept the changes (edit Foo.thy, add new sorry commands, etc.)
# A later successful replacement backs up the version read by that invocation
isabelle desorry Foo.thy
```

The backup reflects the invocation-start snapshot used by the most recent
successful replacement. To revert, copy it back:

```bash
cp Foo.thy.backup Foo.thy
```

Replay, target-validation, timeout, and proof-search startup failures occur
before the mutation branch: `desorry` exits nonzero, leaves the theory
unchanged, and creates no backup.

### Validation boundary

`desorry` loads the target theory's imports and replays the target itself. It
does not discover or validate downstream theories that import the target. In
other words, it validates the dependency direction needed to process the
target, not the target's reverse-dependent session closure.

---

## Sessions and Logic Selection

Every Isabelle theory imports parent theories (`Main`, `Complex_Main`,
`"HOL-Algebra.Ring"`, and so on). Those theories belong to sessions, Isabelle's
compilation units defined in `ROOT` files. To process a target theory, the
tools must start from a built session heap that contains the required imported
theories.

### Automatic logic derivation

The tools inspect the target's imports and map known theory names to sessions:

| Import in the theory | Derived session |
|---|---|
| `Pure` | `Pure` |
| `Main` or `Complex_Main` | `HOL` |
| `"HOL-Library.Multiset"` | `HOL-Library` |
| `"HOL-Algebra.Ring"` | `HOL-Algebra` |
| `"HOL-Analysis.Analysis"` | `HOL-Analysis` |

For imports from multiple sessions, the tools choose the candidate with the
deepest dependency ancestry. This is a convenience heuristic, not a
replacement for explicit selection in custom projects. If derivation cannot
identify a session, it falls back to Isabelle's default logic.

### When to use `-l`

Use `-l SESSION` to override automatic derivation when:

- a custom or AFP theory uses unqualified import names;
- automatic derivation selects the theory's unbuilt owning session;
- you deliberately want to start from a particular built parent heap.

```bash
isabelle eval_at -l HOL-Algebra MyTheory.thy 10
isabelle desorry -l HOL-Algebra MyTheory.thy
```

### When to use `-d`

Use repeatable `-d DIR` options to add directories containing `ROOT` files.
This is normally required for local sessions, AFP entries outside the standard
search path, and bare-name sibling imports:

```bash
isabelle eval_at -l My_Parent -d /project/root MyTheory.thy 10
isabelle eval_at -d /project/sessions -d /path/to/afp/thys/Foo MyTheory.thy 10
```

The tools also run Isabelle/ML from the target theory's directory, which lets
ordinary sibling imports resolve without an additional working-directory
option.

### The selected heap must already be built

The tools never build session heaps. They perform a no-build check and stop
with an actionable error if the selected heap is unavailable or outdated:

```text
Session heap for "My_Session" is not available or not up to date;
refusing to build it automatically.
```

Useful checks are:

```bash
# Resolve a session, including a local ROOT directory
isabelle sessions -d /project/root My_Session

# Check whether an up-to-date heap image exists without building it
isabelle build -b -n -d /project/root My_Session

# Show the heap location
isabelle getenv ISABELLE_HEAPS
```

Heap files normally live below `$ISABELLE_HEAPS/polyml-*`. A file named
`Foo_requirements(Bar)` is a requirements image created by commands such as
`isabelle build -R Foo` or `isabelle jedit -R Foo`. It contains `Foo`'s
ancestry, not the theories belonging to `Foo` itself.

Building a required heap is an explicit setup operation:

```bash
isabelle build -b HOL
```

### Using a built parent for an unbuilt custom session

Suppose a local `ROOT` contains:

```isabelle
session My_Session = "Parent_Session" +
  theories
    My_Theory
```

If `My_Session` itself is unbuilt but `Parent_Session` is built, start from the
parent heap and provide the `ROOT` directory:

```bash
isabelle eval_at -l Parent_Session -d /project/root My_Theory.thy 15
isabelle desorry -l Parent_Session -d /project/root My_Theory.thy
```

The tools then replay `My_Theory` and source imports on top of the built parent.
Selecting the unbuilt owning session instead is refused rather than triggering
a build:

```bash
isabelle eval_at -l My_Session -d /project/root My_Theory.thy 15
# Session heap for "My_Session" is not available or not up to date.
```

---

## Troubleshooting

**"Unknown Isabelle tool: eval_at"**
Run `isabelle scala_build` to rebuild, then retry.

**"No output at this line."**
The target line has no Isabelle command and no proof state. Try a different
line.

**"ML process failed (return code ...)"**
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
yourself first with `isabelle build -b [-d ROOT_DIR] SESSION`. See
[Sessions](#sessions-and-logic-selection). Build time depends strongly on the
selected session and machine, which is why the tools leave that decision to
you.

**Sledgehammer finds nothing**
`desorry` uses its fixed 50-second scheduling budget and default Sledgehammer
portfolio. Use `eval_at` to experiment with a longer budget, selected provers,
or manual simplification before editing the theory:

```bash
isabelle eval_at MyTheory.thy 42 'sledgehammer [timeout = 60, provers = "vampire e cvc5"]'
```

**Sibling imports not found**
Use `-d DIR` to point at the directory containing the ROOT file that
defines the session.

---

## Known Limitations

- **Startup cost:** Full theory elaboration from line 1 to LINE runs every
  `eval_at` invocation. `desorry` replays through its optional stop line, or
  through the complete theory when no stop line is given. Large files can be
  slow.
- **Bare-name imports** from non-sibling directories are not auto-detected.
  Use `-d DIR` to point at the directory containing the ROOT file.
- **Transitive path-based imports** inside sibling theories are not
  auto-detected. Use `-d`.
- **Position is start-of-command:** The tools use the starting line of each
  Isabelle command. If a command spans multiple lines, only its first line
  counts as the "position" for that command.
- **External provers** (for `sledgehammer`, `nitpick`) must be available in
  the Isabelle contrib directory. Standard Isabelle distributions include them.
- **`desorry` proof-search memory:** `desorry` retains every selected proof
  state until its parallel proof-search phase finishes; it does not batch goals
  according to available memory. Use `-L` to select fewer goals, or the
  positional stop line to limit both replay and collection.
- **No reverse-dependent validation:** `desorry` does not inspect theories that
  import its target; it validates only the target and the imports needed to
  process it.
