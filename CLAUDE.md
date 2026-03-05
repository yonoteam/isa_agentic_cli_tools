# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This directory contains the full distribution of the Isabelle proof assistant (Isabelle2025-2). It provides the theorem proving environment and formal logic engines (Pure, HOL).

The `src/` directory is the source of truth for understanding Isabelle's ML API.

## Key Commands

```bash
# Start interactive Prover IDE (main development workflow)
bin/isabelle jedit -l HOL

# Build a specific session
bin/isabelle build -b HOL

# Full test build (required before any Mercurial push)
bin/isabelle build -a

# Parallel build (2 jobs, 4 threads)
bin/isabelle build -a -j2 -o threads=4

# Update/check components
bin/isabelle components -a

# View system manual
bin/isabelle doc system
```

## Architecture

### Language Stack
- **Isabelle/Pure (ML)** — Core logical kernel; natural deduction, type theory, term unification
- **Object Logics (ML)** — Built on Pure: HOL (main), FOL, ZF
- **Isabelle/Scala** — System tools, PIDE framework, IDE plugins, build system
- **Bash** — Cross-platform system scripting

### Key Source Directories
- `src/Pure/` — Core logical framework: `Toplevel`, `Context`, proof state, ML compiler
- `src/HOL/` — Higher-Order Logic library (~177 subdirs of theories)
- `src/Provers/` — Generic proof-search engines (blast, simp, etc.)
- `src/Tools/` — Applications: `jEdit/` (main IDE), `VSCode/`, `Metis/`, `Code/`
- `lib/Tools/` — Build tools: `scala_build`, `scalac`, `java` wrappers
- `bin/` — Entry-point executables (`isabelle`, `isabelle_java`)
- `etc/` — Configuration: `settings` (JVM/paths), `options` (build options), `symbols`

### Session/Module System
Sessions are the build unit, defined in `ROOT` files. Dependencies are explicit:
```
session HOL in "HOL" = Pure + ...
session HOL-Library in "HOL/Library" = HOL + ...
```
Session heaps are stored in `~/.isabelle/heaps/` (user) or `$ISABELLE_HOME/heaps/` (system).

## Discovering ML APIs

To find ML module signatures and interfaces within `src/`:
```bash
grep -r "signature MODULE_NAME" src/
grep -r "structure MODULE_NAME" src/
```

- `src/Pure/` — kernel structures: `Thm`, `Context`, `Toplevel`, `Proof`
- `src/HOL/` — higher-level tactics and tools

## Programmatic Theory Loading via ML_Process

When writing tools that need to process `.thy` files outside of the PIDE/jEdit
environment, use `ML_Process` (`src/Pure/ML/ml_process.scala`) with a session
heap. Key lessons:

### Session and heap setup (Scala side)

```scala
val session_background = Sessions.background(options, logic, dirs = dirs).check_errors
val session_heaps = Store(options).session_heaps(session_background, logic = logic)
val process = ML_Process(options, session_background, session_heaps,
  args = List("--use", script_path),
  cwd = thy_dir,       // sets ML working directory for local import resolution
  redirect = false)
```

- `Sessions.background` + `Store.session_heaps` resolve the session graph and
  locate the heap files for the given logic (e.g. `"HOL"`).
- On startup, `ML_Process` loads the heap and calls `Resources.init_session_env()`
  which populates session directories, global theory mappings, and the set of
  loaded theories from the `ISABELLE_INIT_SESSION` environment variable.

### Accessing theories in ML_Process

- **Session theories** (e.g. `Complex_Main`, `HOL.List`) are registered in
  `Thy_Info` after heap loading. Use `Thy_Info.get_theory "Complex_Main"` or
  `Thy_Info.lookup_theory name` to retrieve them. Names may or may not be
  qualified (check `Thy_Info.get_names ()` to see what's available).
- **`Theory.get_pure ()`** returns only the Pure theory — it does NOT include
  HOL or any session-specific content.
- **`Context.the_global_context ()`** raises `ERROR "Unknown context"` in
  ML_Process — it only works inside PIDE/jEdit.
- **Local theories** (sibling `.thy` files not in the session) must be loaded
  explicitly via `Thy_Info.use_theories options qualifier [(name, pos)]`.
  This requires the ML working directory (`cwd`) to point to the directory
  containing those files, since `use_theories` resolves bare names relative
  to `Path.current`.

### Parsing theory text with Outer_Syntax.parse_text

```ml
val transitions = Outer_Syntax.parse_text thy (fn () => thy) pos text;
```

- The first argument `thy` supplies **keywords** for outer-syntax tokenization.
  Use the most complete theory available (e.g. from `Thy_Info.get_theory`).
- The second argument `init : unit -> theory` is critical: `parse_text` calls
  `Toplevel.modify_init init` on any `theory` command, **replacing** the
  command's built-in import resolution with this function. If you pass
  `fn () => Theory.get_pure ()`, the `theory X imports Y begin` command will
  produce a Pure-only context and all subsequent commands will lack HOL syntax.
- Correct approach: parse the theory header with `Thy_Header.read`, resolve
  each import via `Thy_Info.get_theory`, then call `Resources.begin_theory`
  with the resolved parents:

```ml
val header = Thy_Header.read Position.none text;
val init = fn () =>
  let val parents = map (fn (imp, _) => Thy_Info.get_theory imp) (#imports header)
  in Resources.begin_theory master_dir header parents end;
```

### Deriving the logic session from theory imports (Scala side)

Use Isabelle's own `Thy_Header` parser — never regex — to extract imports:

```scala
val node_name = Document.Node.Name(thy_file.absolute.implode,
  theory = Thy_Header.get_thy_name(thy_file.base.implode).getOrElse(""))
val header = Thy_Header.read(node_name,
  Scan.char_reader(content), command = false, strict = false)
```

Then derive the session for each import:

```scala
val sessions_structure = Sessions.load_structure(options, dirs = dirs)
header.imports.map { case (s, _) =>
  val theory_name = Thy_Header.import_name(s)
  sessions_structure.theory_qualifier(theory_name)  // e.g. "HOL-Algebra"
}
```

`theory_qualifier` calls `Long_Name.qualifier` which splits on `.`, so
`"HOL-Algebra.Bij"` correctly yields session `"HOL-Algebra"`. Regex-based
approaches break on session names containing hyphens.

### ML string splitting

Use `String.fields (fn c => c = #"\n")` to split text into lines.
`String.tokens` drops empty entries, corrupting byte offsets for line-based
injection.

## Version Control

This repository uses **Mercurial** (not Git). Key workflows are documented in `README_REPOSITORY`.
