# eval_at — Execute Any Isabelle Command at a Theory Line

`isabelle eval_at` is a CLI tool that evaluates arbitrary Isabelle commands at
any line of a `.thy` file, without jEdit or PIDE.

```
isabelle eval_at [OPTIONS] THY_FILE LINE [COMMAND]
```

---

## Installation

### Prerequisites

```bash
# Isabelle is installed and on PATH
isabelle version   # must print 2025-2
```

### Install

```bash
cd eval_at
bash install.sh /path/to/isabelle
```

The installer copies `eval_at.scala` into the Isabelle source tree, registers
it in `etc/build.props` and `isabelle_tool.scala`, then rebuilds
(`isabelle scala_build`). It is idempotent.

### Verify

```bash
isabelle eval_at --help
```

---

## Sessions: How eval_at Finds the Right Logic

**This is the most important concept for using `eval_at` correctly.**

Every Isabelle theory imports parent theories (`imports Main`, `imports
Complex_Main`, `imports "HOL-Algebra.Ring"`, etc.). These parents live in
**sessions** — Isabelle's compilation units, defined in `ROOT` files. Before
`eval_at` can process a theory, it must load the correct session heap containing
all the imported theories.

### How automatic session derivation works

`eval_at` reads the theory's `imports` line and maps each import to a session:

| Import in `.thy` file | Derived session | Why |
|---|---|---|
| `Main` | `HOL` | `Main` is a global theory in the `HOL` session |
| `Complex_Main` | `HOL` | `Complex_Main` is a global theory in the `HOL` session |
| `"HOL-Library.Multiset"` | `HOL-Library` | Qualified name: everything before the last `.` |
| `"HOL-Algebra.Ring"` | `HOL-Algebra` | Qualified name: `HOL-Algebra` |
| `"HOL-Analysis.Analysis"` | `HOL-Analysis` | Qualified name: `HOL-Analysis` |
| `Pure` | `Pure` | The base logic |

When a theory imports from multiple sessions, `eval_at` picks the **deepest**
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
   that `eval_at` cannot find without a ROOT file directory:

   ```isabelle
   theory MyWork imports Ring   (* bare name, not "HOL-Algebra.Ring" *)
   begin
   ```

   ```bash
   # eval_at cannot map bare "Ring" to HOL-Algebra
   # Fix: tell it which session
   isabelle eval_at -l HOL-Algebra MyWork.thy 10
   ```

2. **The theory lives inside a custom session** defined by a local ROOT file,
   but the ROOT file is in a different directory:

   ```bash
   # The ROOT file defining session "My_Session" is in /project/root/
   isabelle eval_at -l My_Session -d /project/root MyWork.thy 10
   ```

### When you need `-d` (session directory)

The `-d DIR` flag tells `eval_at` where to find ROOT files that define
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

### First run builds the heap

The first time `eval_at` runs with a given session (e.g., HOL), it builds the
session heap. **This can take minutes** (HOL alone takes 5-15 minutes depending
on hardware). Subsequent runs reuse the cached heap and start in seconds.

```bash
# First run: builds HOL heap (slow)
isabelle eval_at MyTheory.thy 10

# Second run: reuses heap (fast)
isabelle eval_at MyTheory.thy 20
```

Heaps are stored in `~/.isabelle/heaps/`. If you delete them or change Isabelle
versions, Isabelle rebuilds them automatically.

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

For each of these, `eval_at` derives the correct session automatically from the
qualified import names.

---

## Usage

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
| `-S` | Show sorts (implies types) in output |
| `-T` | Show types in output |
| `-U` | Output Unicode symbols instead of Isabelle's ASCII encoding |
| `-d DIR` | Add a session directory for ROOT file resolution (repeatable) |
| `-l NAME` | Override the automatically derived logic session |
| `-o OPTION` | Override an Isabelle system option |
| `-s` | Show proof state after command output (for injection mode) |
| `-v` | Verbose: show derived session, build progress, ML errors |

Options must come before positional arguments.

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

---

## Precondition: All Prior Commands Must Succeed

`eval_at` replays every Isabelle command from the top of the theory file down
to LINE. **All commands before LINE must be accepted by Isabelle** (no errors).

If a prior command fails, `eval_at` reports the error:

```
Error at line 20 (  apply (rule foo)): Unknown fact "foo"
```

### Before calling eval_at:

1. **Verify the theory is error-free up to LINE.** Run `eval_at` without a
   command first — if it shows state without errors, the context is valid.

2. **`sorry` is tolerated** (accepted under `quick_and_dirty`), but changes
   the logical context. Proofs found after a `sorry` may depend on sorry'd
   facts and fail when the sorry is replaced.

3. **LINE must be inside a proof** for proof-related commands (`apply`,
   `sledgehammer`, `find_theorems intro`). At theory level, use informational
   commands (`term`, `thm`, `value`, `find_theorems "pattern"`).

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

**Very slow first run**
The session heap is being built. This is a one-time cost per session.
Subsequent runs reuse the cached heap. Building HOL takes 5-15 minutes;
HOL-Analysis can take 30+ minutes.

**Sledgehammer finds nothing**
Try: longer timeout (`eval_at Foo.thy 15 'sledgehammer [timeout = 60]'`),
different provers (`eval_at Foo.thy 15 'sledgehammer [provers = "vampire e cvc5"]'`),
or simplify the goal with manual tactics first.

---

## Known Limitations

- **Startup cost:** Full theory elaboration from line 1 to LINE runs every
  invocation. Deep lines in large files can be slow.
- **Bare-name imports** from non-sibling directories are not auto-detected.
  Use `-d DIR` to point at the directory containing the ROOT file.
- **Transitive path-based imports** inside sibling theories are not
  auto-detected. Use `-d`.
- **Position is start-of-command:** The tool uses the starting line of each
  Isabelle command. If a command spans multiple lines, only its first line
  counts as the "position" for that command.
- **External provers** (for `sledgehammer`, `nitpick`) must be available in
  the Isabelle contrib directory. Standard Isabelle distributions include them.
