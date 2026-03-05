# Agent Instructions: find_thms_at

This section tells a GenAI agent (any LLM with shell access) how to install and use
`isabelle find_thms_at`, a command-line tool that runs Isabelle's `find_theorems`
query at any line of a `.thy` file.

---

## Prerequisites

Before starting, confirm:

```bash
# 1. Isabelle is installed — this must print a path
which isabelle || echo "NOT FOUND — set ISABELLE_HOME/bin in PATH"

# 2. The Isabelle version is 2025-2 (other versions may need manual patching)
isabelle version
```

If `isabelle` is not on PATH, use the full path everywhere:
```bash
ISABELLE=/path/to/isabelle/bin/isabelle
$ISABELLE version
```

---

## Installation

### Option A — Automated (recommended)

Unpack this archive and run the installer, passing the Isabelle home directory:

```bash
# Unpack (if not already done)
tar -xzf isabelle_cli_tools.tar.gz
cd isabelle_cli_tools

# Install
bash install.sh /path/to/isabelle
```

The installer will:
1. Copy `src/Pure/Tools/find_thms_at.scala` into the Isabelle source tree
2. Register the source in `etc/build.props` (idempotent)
3. Register the tool in `src/Pure/System/isabelle_tool.scala` (idempotent)
4. Rebuild the Isabelle/Scala layer (`isabelle scala_build`)

The installer is idempotent: safe to re-run. The `.scala` file is overwritten;
`build.props` and `isabelle_tool.scala` are only patched if the entries are
absent.

### Option B — Manual

If the installer's `sed` patches fail (different Isabelle version), make these
three changes by hand:

**1. Copy the Scala source:**
```bash
cp src/Pure/Tools/find_thms_at.scala /path/to/isabelle/src/Pure/Tools/
```

**2. Add to `etc/build.props`** — in the `sources` block, add this line in
alphabetical order among the `Pure/Tools/` entries:
```
  src/Pure/Tools/find_thms_at.scala \
```

**3. Add to `src/Pure/System/isabelle_tool.scala`** — in `class Tools extends
Isabelle_Scala_Tools(`, add after `Export.isabelle_tool,`:
```scala
  Find_Thms_At.isabelle_tool,
```

**4. Rebuild:**
```bash
/path/to/isabelle/bin/isabelle scala_build
```

### Verify installation

```bash
isabelle find_thms_at --help
```

Expected: prints usage information. If you see "Unknown Isabelle tool", the
rebuild did not complete successfully — re-run `isabelle scala_build`.

---

## Usage

```
isabelle find_thms_at [OPTIONS] THY_FILE LINE QUERY
```

| Argument | Description |
|----------|-------------|
| `THY_FILE` | Absolute or relative path to the `.thy` file |
| `LINE` | Line number (1-based) after which `find_theorems` is injected |
| `QUERY` | Query string, same syntax as Isabelle's `find_theorems` command |

| Option | Description |
|--------|-------------|
| `-d DIR` | Add a session directory for import resolution (repeatable) |
| `-l NAME` | Override the automatically derived logic session name |
| `-o OPTION` | Override an Isabelle system option |
| `-U` | Output Unicode symbols instead of ASCII |
| `-v` | Verbose: show derived logic session, build progress, and ML process errors |

**Important:** Options must come before the positional arguments.

### QUERY syntax

| Query | Meaning |
|-------|---------|
| `"_ + _ = _ + _"` | Match by term pattern (wildcards use `_`) |
| `name: "substring"` | Match theorem names containing substring |
| `intro` | Intro rules applicable to the current goal |
| `elim` | Elimination rules |
| `dest` | Destruction rules |
| `simp: "term"` | Simp rules for a specific term |
| `(10)` | Limit results to 10 (default: 40) |
| `(10 with_dups)` | Limit and include duplicates |
| `intro name: "List"` | Combine multiple criteria |

---

## Examples

```bash
# Term pattern — find commutativity lemmas
isabelle find_thms_at MyTheory.thy 42 '"_ + _ = _ + _"'

# Find intro rules at line 17
isabelle find_thms_at MyTheory.thy 17 'intro'

# Limit to 5 results
isabelle find_thms_at MyTheory.thy 42 '(5) "_ + _ = _ + _"'

# Theory with sibling import (resolved automatically)
isabelle find_thms_at Thy_Test2.thy 5 '"is_line _"'

# Theory with ROOT file and sorry proofs (handled automatically — only
# transitions up to the query line are processed)
isabelle find_thms_at Top1.thy 35 'name: "topology"'

# Verbose output to debug issues
isabelle find_thms_at -v Foo.thy 10 '"_ * _"'

# Override logic session (when automatic derivation is wrong)
isabelle find_thms_at -l HOL-Algebra Foo.thy 10 '"_ * _"'

# Extra directory for imports from non-standard locations
isabelle find_thms_at -d /path/to/extra/session Foo.thy 10 'intro'
```

---

## Troubleshooting

**"isabelle: command not found"**
```bash
export PATH="/path/to/isabelle/bin:$PATH"
```

**"scala_build failed"**
Run `isabelle scala_build` manually and read the compiler output. A stale `.class`
file sometimes causes this; deleting `~/.isabelle/contrib/scala*` and retrying
usually fixes it.

**"No theorems found."**
The query matched nothing at that position, or the theory could not be
elaborated up to that line. Try a broader query, or add `-v` to see ML errors.

**"find_thms_at failed (return code ...)"**
A transition before the query line raised an error. Add `-v` to see the full
ML process stderr. Common causes: missing imports (use `-d`), or a proof error
before line N.

---

## Known limitations

- Only transitions up to line N are processed.  If the query line is deep in a
  large file (e.g. line 9750 of a 10K-line file), startup can be slow.
- Bare-name imports from directories other than the theory's own directory are
  not auto-detected. Use `-d`.
- Transitive path-based imports inside sibling theories are not auto-detected.
  Use `-d`.
- Theories declaring custom outer-syntax keywords in their own header may fail
  to parse. This is extremely rare in practice.

---

# Agent Instructions: proof_state_at

This section documents `isabelle proof_state_at`, a command-line tool that prints
the proof state (as shown in jEdit's Output panel) at any line of a `.thy` file.

It is installed alongside `find_thms_at` by the same `install.sh` script.

---

## Usage

```
isabelle proof_state_at [OPTIONS] THY_FILE LINE
```

| Argument | Description |
|----------|-------------|
| `THY_FILE` | Absolute or relative path to the `.thy` file |
| `LINE` | Line number (1-based) up to which transitions are processed |

| Option | Description |
|--------|-------------|
| `-S` | Show sorts in output (implies types) |
| `-T` | Show types in output |
| `-U` | Output Unicode symbols instead of ASCII |
| `-d DIR` | Add a session directory for import resolution (repeatable) |
| `-l NAME` | Override the automatically derived logic session name |
| `-o OPTION` | Override an Isabelle system option |
| `-v` | Verbose: show derived logic session, build progress, and ML process errors |

**Important:** Options must come before the positional arguments.

### Output

- If LINE is inside a proof, the current subgoals are displayed (same as jEdit's
  Output panel).
- If LINE is at theory level (outside any proof), "No proof state." is printed.

---

## Examples

```bash
# Print proof state at line 9
isabelle proof_state_at Foo.thy 9

# Show types in the proof state
isabelle proof_state_at -T Foo.thy 9

# Show sorts (implies types)
isabelle proof_state_at -S Foo.thy 9

# Theory level (no proof) — prints "No proof state."
isabelle proof_state_at Foo.thy 3

# Verbose output to debug issues
isabelle proof_state_at -v Foo.thy 9

# Override logic session
isabelle proof_state_at -l HOL-Algebra Foo.thy 9

# Extra directory for imports
isabelle proof_state_at -d /path/to/extra/session Foo.thy 9
```

---

## Troubleshooting

**"Unknown Isabelle tool"**
Run `isabelle scala_build` again.

**"proof_state_at failed"**
Use `-v` to see the ML process error.

**"No proof state."**
The line is at theory level (outside a proof block).

---

## Known limitations

Same as `find_thms_at`:
- Only transitions up to line N are processed. Deep lines in large files can be slow.
- Bare-name imports from directories other than the theory's own directory are
  not auto-detected. Use `-d`.
- Transitive path-based imports inside sibling theories are not auto-detected. Use `-d`.

---

# Agent Instructions: sledgehammer_at

This section documents `isabelle sledgehammer_at`, a command-line tool that runs
Isabelle's Sledgehammer proof finder at any line of a `.thy` file.

It is installed alongside `find_thms_at` and `proof_state_at` by the same
`install.sh` script.

---

## Usage

```
isabelle sledgehammer_at [OPTIONS] THY_FILE LINE [OPTS]
```

| Argument | Description |
|----------|-------------|
| `THY_FILE` | Absolute or relative path to the `.thy` file |
| `LINE` | Line number (1-based) after which `sledgehammer` is injected |
| `OPTS` | Raw Sledgehammer options placed inside `[...]` brackets (optional) |

| Option | Description |
|--------|-------------|
| `-d DIR` | Add a session directory for import resolution (repeatable) |
| `-l NAME` | Override the automatically derived logic session name |
| `-o OPTION` | Override an Isabelle system option |
| `-P PROVERS` | Space-separated prover list (e.g. `"e cvc5 vampire"`) |
| `-T SECONDS` | Timeout per prover in seconds |
| `-U` | Output Unicode symbols instead of ASCII |
| `-v` | Verbose: show derived logic session, build progress, and ML process errors |

**Important:** Options must come before the positional arguments.

---

## Precondition: All Prior Transitions Must Succeed

**This is the most important requirement for correct results.**

`sledgehammer_at` replays every Isabelle command in the theory file from the
top up to LINE. Each command updates the logical context (definitions, lemma
statements, proof steps). Sledgehammer operates on the context that results
from this sequential replay.

**All commands before LINE must be accepted by Isabelle** — i.e., no red
error markers in jEdit up to that point. If a prior command fails:

- The tool **reports the error** with the failing line number, line content,
  and the Isabelle error message (e.g., `Error before injection at line 16
  (  apply (rule exI[of ?x])): Unbound schematic variable: ?x`), or
- It produces results against a **corrupted context**, which may suggest
  proofs that do not actually work.

### Before calling `sledgehammer_at`:

1. **Ensure the theory file has no errors before LINE.** You can verify this
   with `isabelle proof_state_at THY_FILE LINE` — if it prints a proof state
   without errors, the context is valid.

2. **`sorry` is tolerated** (it is accepted by Isabelle under
   `quick_and_dirty`) but changes the logical context. Sledgehammer may
   suggest proofs that depend on sorry'd facts.

3. **If a prior proof step is broken**, fix it first or choose a LINE before
   the broken step.

4. **LINE must be inside a proof with an active goal.** If LINE is at theory
   level (outside any proof), the tool reports an error like:
   ```
   Error at line 4 (): Illegal application of proof command in "theory" mode
   ```

### Why this matters

Consider a theory where line 20 has a type error. If you call
`sledgehammer_at` at line 50, the tool will report:
```
Error before injection at line 20 (  <line content>): <Isabelle error message>
```

Or consider a theory where line 30 uses `sorry` to skip a proof. Calling
`sledgehammer_at` at line 50 may produce proofs that silently depend on the
sorry'd proposition — these proofs will fail when `sorry` is replaced with a
real proof.

---

## Examples

```bash
# Basic — find a proof for the goal at line 7
isabelle sledgehammer_at Thy_Test.thy 7

# Limit to specific provers with a 15-second timeout
isabelle sledgehammer_at -P "e cvc5" -T 15 Thy_Test.thy 7

# Pass raw Sledgehammer options directly
isabelle sledgehammer_at Thy_Test.thy 7 'provers = "e" timeout = 30'

# Sibling import (resolved automatically)
isabelle sledgehammer_at Thy_Test2.thy 5

# Verbose output to debug issues
isabelle sledgehammer_at -v Thy_Test.thy 7

# Override logic session (when automatic derivation is wrong)
isabelle sledgehammer_at -l HOL-Algebra Foo.thy 17

# Extra directory for imports from non-standard locations
isabelle sledgehammer_at -d /path/to/extra/session Foo.thy 17
```

---

## Understanding the Output

### Success — proof found

```
cvc5 found a proof...
Try this: by (metis add.commute add.left_commute) (87 ms)
```

The `Try this:` line is the suggested proof method. Copy it into your theory
file to replace the current proof attempt.

### No proof found

```
No proof found
```

Sledgehammer tried all configured provers and none found a proof. Try:
- Different provers: `-P "vampire e spass"`
- Longer timeout: `-T 60`
- Simplifying the goal with manual tactics first

### Error — wrong line

```
Error at line 4 (): Illegal application of proof command in "theory" mode
```

LINE is outside a proof block. Choose a line inside a `lemma`/`theorem`
proof, after the statement and before `qed`/`done`/`oops`/`sorry`.

### Error — no subgoal

```
Error at line 10 (  by auto): No subgoal!
```

The proof was already closed by the tactic on that line. Choose an earlier
line where subgoals are still open.

### Error — transition failure before LINE

```
Error before injection at line 16 (  apply (rule exI[of ?x])): Unbound schematic variable: ?x
```

A command before LINE raised an error. The theory file has issues that must
be fixed before LINE. Use `isabelle proof_state_at` to find the first broken
line.

---

## Troubleshooting

**"isabelle: command not found"**
```bash
export PATH="/path/to/isabelle/bin:$PATH"
```

**"scala_build failed"**
Run `isabelle scala_build` manually and read the compiler output. A stale
`.class` file sometimes causes this; deleting `~/.isabelle/contrib/scala*`
and retrying usually fixes it.

**"sledgehammer_at: line N (...): failed (return code ...)"**
A transition before the target line raised an error. Add `-v` to see the full
ML process stderr. Common causes: missing imports (use `-d`), or a proof/type
error before line N. Fix the error or choose an earlier LINE.

**"sledgehammer_at: line N (...): no sledgehammer command was reached."**
The injection line was not reached during transition replay. This usually
means LINE is past the last command in the file, or the `sledgehammer`
keyword was not recognized (e.g., the logic does not include HOL). Check
that `-l` is set correctly or that the theory imports HOL.

**Empty output with no error**
Add `-v` to see build and ML diagnostics. If the theory imports a non-HOL
logic, Sledgehammer may not be available — it is defined in `HOL/Sledgehammer.thy`.

---

## Known Limitations

- **All transitions before LINE must succeed** — fix errors before LINE
  first, or move LINE earlier. Use `isabelle proof_state_at` to verify.
- **Requires an active proof goal at LINE** — choose a line inside a proof
  block.
- **External provers must be available** — standard Isabelle contrib bundles
  usually suffice.
- **Long-running** — full theory elaboration up to LINE before Sledgehammer
  starts. Set `-T` to limit per-prover time.
- **Bare-name imports from directories other than the theory's own directory**
  are not auto-detected. Use `-d`.
- **Transitive path-based imports inside sibling theories** are not
  auto-detected. Use `-d`.
