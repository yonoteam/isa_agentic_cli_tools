# Skill: isabelle-cli-tools

## proof_state_at

Install and use `isabelle find_thms_at` to query Isabelle's `find_theorems` at
any line of a `.thy` file from the terminal.

For full documentation see `AGENT_INSTRUCTIONS.md` in this package.

## Quick install

```bash
tar -xzf isabelle_cli_tools.tar.gz && cd isabelle_cli_tools
bash install.sh /path/to/isabelle
```

## Quick usage

```bash
# Term pattern
isabelle find_thms_at MyTheory.thy 42 '"_ + _ = _ + _"'

# Intro rules for goal at line 17
isabelle find_thms_at MyTheory.thy 17 'intro'

# Limit to 5 results
isabelle find_thms_at MyTheory.thy 42 '(5) "_ + _ = _ + _"'

# Verbose (shows derived session, build progress, ML process errors)
isabelle find_thms_at -v Foo.thy 10 '"_ * _"'
```

## Options

Options must come before positional arguments.

| Flag | Effect |
|------|--------|
| `-d DIR` | Add a session directory for import resolution |
| `-l NAME` | Override the automatically derived logic session |
| `-o OPTION` | Override Isabelle system option |
| `-U` | Unicode output |
| `-v` | Verbose |

## proof_state_at

The package also includes `isabelle proof_state_at`, which prints the proof state
(as shown in jEdit's Output panel) at any line of a `.thy` file.

```bash
# Print proof state at line 9
isabelle proof_state_at Foo.thy 9

# Show types
isabelle proof_state_at -T Foo.thy 9

# Show sorts (implies types)
isabelle proof_state_at -S Foo.thy 9
```

## sledgehammer_at

The package also includes `isabelle sledgehammer_at`, which runs Sledgehammer
at any line of a `.thy` file to find proofs automatically.

**Important:** All commands before LINE must be accepted by Isabelle (no
errors). Use `isabelle proof_state_at` to verify before calling Sledgehammer.

```bash
# Basic — find a proof for the goal at line 7
isabelle sledgehammer_at Thy_Test.thy 7

# Specific provers with timeout
isabelle sledgehammer_at -P "e cvc5" -T 15 Thy_Test.thy 7

# Raw Sledgehammer options
isabelle sledgehammer_at Thy_Test.thy 7 'provers = "e" timeout = 30'

# Verbose output
isabelle sledgehammer_at -v Thy_Test.thy 7
```

| Flag | Effect |
|------|--------|
| `-P PROVERS` | Space-separated prover list |
| `-T SECONDS` | Timeout per prover |
| `-d DIR` | Add a session directory for import resolution |
| `-l NAME` | Override the automatically derived logic session |
| `-o OPTION` | Override Isabelle system option |
| `-U` | Unicode output |
| `-v` | Verbose |

## Troubleshooting

- **"Unknown Isabelle tool"** → run `isabelle scala_build` again
- **"find_thms_at failed"** → use `-v` to see the ML process error
- **"No theorems found."** → query matched nothing, or try `-v` to see errors
- **"proof_state_at failed"** → use `-v` to see the ML process error
- **"No proof state."** → the line is at theory level (outside a proof)
- **"Error before injection at line N (...): ..."** → a command before LINE failed; fix it first
- **"Error at line N (...): No subgoal!"** → proof already closed at that line; choose an earlier line
- **"sledgehammer_at: line N (...): no sledgehammer command was reached."** → LINE is past the last command or outside HOL
