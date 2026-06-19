# CLAUDE.md — isa_cli_tools

Repository for building, updating, and testing two Isabelle CLI tools used in
agentic / autoformalization workflows:

- **`isabelle eval_at`** — evaluate any Isabelle command at a given theory line
  (show proof state, run `sledgehammer`/`try`/`nitpick`, print a `thm`/`term`/
  `value`, inject `apply ...`, etc.).
- **`isabelle desorry`** — replace `sorry` proofs in a theory with verified
  Sledgehammer results, in place, with a `.backup`.

Source of both tools: `isa_cli_tools/src/Pure/Tools/{eval_at.scala,desorry.scala}`.
Full user documentation: `isa_cli_tools/README.md` (keep it in sync with the
sources when behavior changes).

## How the tools work (shared architecture)

Both tools are a thin **Scala orchestrator** that generates an **ML script**,
runs it inside a real prover process, and scrapes the result — they do *not*
use the document model / PIDE. Read `eval_at.scala` first; `desorry.scala` is
the same skeleton with an extra phase. The pipeline in each `def eval_at` /
`def desorry`:

1. Read the `.thy`, parse its header (`Thy_Header.read`), and resolve
   **path-based imports** (those containing `/`) into extra session dirs.
2. **Derive the logic session** (`derive_logic`) from the imports unless `-l`
   overrides: map each import to its session qualifier, keep the defined ones,
   and pick the one with the largest ancestry in `imports_graph` (the "richest"
   built session). Falls back to `Isabelle_System.default_logic()`.
3. **`check_logic_heap`** — calls `Build.build(..., no_build = true)` purely as a
   probe. If `!results.ok` it `error`s out. This is the "never build a heap"
   invariant enforced in code; the long error string here is the user-facing
   guidance and must stay in sync with the README.
4. Generate ML source into a tmp dir, start a `Bash.Server` (so `sledgehammer`
   can shell out), and launch `ML_Process` on the **pre-built session heap**
   with `quick_and_dirty` set, `--use`-ing the script.
5. The ML script brackets its real output with **sentinel strings**
   (`===EVAL_AT_BEGIN===` / `===DESORRY_BEGIN===`, etc.); Scala slices stdout
   between the sentinels. `desorry` additionally tags user-facing lines with
   `[RESULT] ` and shows only those unless `-v`.

Inside the ML script, theory loading deliberately avoids `Thy_Info`'s full
build path: local/sibling imports are pre-loaded with `Thy_Info.use_theories`,
then the theory is built with `Resources.begin_theory` over those parents, and
`Outer_Syntax.parse_text` turns the source into a list of `Toplevel.transition`s
that are folded over a `Toplevel.state`. `eval_at` splits the transitions at the
target line (state mode) or injects a command after it (inject mode).

### desorry's two-phase design and the preplay guard

`desorry` is split because after `Pure.thy` sets `ML_write_global = false`, HOL
structures (incl. `Sledgehammer`) live only in the **theory-local** ML
namespace:

- **Phase 1** (top level, Pure only): replay transitions, and at each `sorry`
  capture `(line, Proof.state)`. Then it `ML_Context.eval_file`s phase 2 *within
  the theory context*, reads results back, and does the atomic file rewrite
  (write `.backup`, write `.desorry_tmp`, `OS.FileSys.rename` over the original).
- **Phase 2** (theory context): runs `Sledgehammer` on every captured state via
  `Par_List.map`. The two phases communicate through `Unsynchronized.ref`s in a
  global `Desorry_Comm` structure.

The **central correctness rule** lives in phase 2's `try_sledgehammer`: it
accepts a reconstruction **only if the top preplay result is `Played`**, and
runs with `smt_proofs = false` so the emitted proof is a structured/metis/etc.
method, never a fragile `by (smt ...)`. If nothing preplays it falls back to
`self_verified_replacement`, which re-runs each candidate method's tactic and
checks the goal actually closes (`Thm.nprems_of goal' = 0`). This guards the one
bug the tool exists to prevent: a Sledgehammer *suggestion* whose preplay failed
but which gets emitted anyway and then breaks on reload. Hence the testing rule
below — a green preplay is not proof; reload the filled theory.

## Repository layout / conventions

- `isabelle_dev/` — **authoritative source of truth** for the current
  Isabelle Scala/ML API. **Do not modify it** except to install the tools into
  it. When the tool sources don't compile, port them to match this tree.
- `isa_cli_tools/` — the tool sources, `install.sh`, and `README.md`.
- `tst/Thy_Tests/` — test theories (`test1`–`test8`). Useful fixtures:
  - `test6/Scratch.thy` — `Main`, an `apply`-style proof (good for `eval_at`).
  - `test7/Test_Sorry.thy` — `Main`, four `sorry`s (simplest `desorry` case).
  - `test8/Library_Session_Test.thy` — imports `HOL-Computational_Algebra.*`
    (exercises non-`HOL` session derivation).
- `agents/` — put **all scripts, scratch theories, and temporary files here**
  (e.g. `agents/temp/`). Never scatter temp files elsewhere; `desorry`
  requires the theory's filename to match its `theory` name, so copy fixtures
  into a subdir under `agents/temp/` before running it.
- `legacy/`, `legacy_2025/` — superseded single-purpose tools; for reference.

## Managing Isabelle sessions correctly (the most important thing)

**Neither tool ever builds a session heap.** Before doing any work, each tool
checks that the chosen logic session's heap is built and up to date. If it is
not, the tool **stops with an error** ("Session heap for ... is not available
or not up to date; refusing to build it automatically") instead of launching a
compile that could take hours. This is deliberate: an unattended runaway build
is the main hazard in an agent workflow. Choosing the right session is the
caller's responsibility.

Consequences for how you invoke the tools:

1. **Find out which Isabelle you are using and where its heaps are.** This repo
   uses `isabelle_dev`, an unversioned repository build, whose heaps live in
   `~/.isabelle/heaps/polyml-*/` — **not** the versioned
   `~/.isabelle/Isabelle2025/heaps/...` used by a normal release. Always
   resolve the directory rather than assuming:

   ```bash
   DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
   HEAPS=$("$DEV/bin/isabelle" getenv -b ISABELLE_HEAPS)   # the heaps root
   "$DEV/bin/isabelle" getenv -b ML_SYSTEM                 # e.g. polyml-5.9.2
   ls "$HEAPS"/polyml-*/                                   # inventory all dirs
   ```

   Caveats that bite here: there can be **more than one `polyml-*` dir**, and
   they hold different (sometimes stale) session sets — only the one matching
   the active `ML_SYSTEM` platform is live, and its full suffix is fixed up at
   ML-process startup so you cannot reliably reconstruct the dir name from
   `getenv` (`ML_PLATFORM`/`ML_IDENTIFIER` read empty outside a running
   process). So **treat the `ls` as a rough inventory only and confirm any
   specific session with `isabelle build -n SESSION`** (item 2) — that is the
   authoritative, platform-correct check, and it is exactly what the tools' own
   heap probe (`Build.build(no_build = true)`) uses. Do not hardcode "which
   sessions are built" anywhere; it drifts. Names like `Foo_requirements(Bar)`
   are **requirement images** (Foo's ancestry, *not* Foo's own theories).

2. **Pick `-l SESSION` to point at a heap that is already built.** Verify
   before running: `isabelle build -n SESSION [-d ROOT_DIR]` — if it lists
   nothing to build, the heap is up to date. Auto-derivation (no `-l`) reads
   the theory's `imports` and usually picks the right session, but it can land
   on an unbuilt one; passing `-l` explicitly avoids a wasted round-trip.

3. **For a theory in an unbuilt custom/AFP session, use its built PARENT.**
   If the ROOT says `session My_Session = "Parent_Session" + ...` and
   `Parent_Session`'s heap is built (and contains everything the theory
   imports), run with `-l Parent_Session -d <dir-with-ROOT>`. The tool replays
   the target theory and its siblings from source on top of the parent heap and
   builds nothing.

4. **Use `-d DIR`** (the directory containing the ROOT) so sibling and
   bare-name imports resolve. Pair it with an explicit `-l`.

5. **If you actually need an unbuilt heap, build it yourself first**, as an
   explicit, separate step — never as a side effect of a query:
   `isabelle build -b SESSION [-d ROOT_DIR]` (HOL ≈ 5–15 min; HOL-Analysis
   30+ min). Then re-run the tool.

Passing an unbuilt session is **not catastrophic** — the tool refuses up front,
so the worst case is a fast error and a re-run, not a runaway compile.

See `isa_cli_tools/README.md` → "Sessions" and "Agent preflight" for the full
treatment, including the worked example.

## Build / install workflow

The tools are Scala sources compiled into the Isabelle `Tools` layer.

```bash
cd isa_cli_tools
bash install.sh /Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev
```

`install.sh` copies the two `.scala` files into `$ISABELLE/src/Pure/Tools/`,
registers them in `etc/build.props` and `src/Pure/System/isabelle_tool.scala`,
then runs `isabelle scala_build`. It is idempotent; re-run after **any** edit to
the sources (including comments/usage strings) so the live tool matches.
`bash install.sh -f <root>` forces re-registration.

## Testing the tools

```bash
DEV=/Users/jonathan/Programs/repos/isa_cli_tools/isabelle_dev

# eval_at: proof state at a line (Main theory -> derives/uses HOL)
"$DEV/bin/isabelle" eval_at tst/Thy_Tests/test6/Scratch.thy 8

# eval_at: inject a command; -T reports per-line timing; -S shows sorts+types
"$DEV/bin/isabelle" eval_at tst/Thy_Tests/test6/Scratch.thy 5 'thm conjI'

# desorry: copy a fixture into agents/temp first (filename must match theory)
mkdir -p agents/temp/run && cp tst/Thy_Tests/test7/Test_Sorry.thy agents/temp/run/
"$DEV/bin/isabelle" desorry -l HOL agents/temp/run/Test_Sorry.thy
# -> replaces each sorry with a verified non-smt proof; writes *.backup
```

**Timeout flag (`-t`, default 60s):** both tools accept `-t SECS` to bound each
individual replayed Isabelle command. If any transition exceeds the limit the
tool aborts, reports the offending line, and (for `desorry`) writes nothing.
A looping tactic is caught and named rather than running forever. Use `-t 0` to
disable. This is a per-command limit, not a total timeout, so large theories
with many fast commands are not penalized.

`desorry`'s Sledgehammer timeout is fixed at 50 seconds per sorry and is not
configurable via a flag.

When verifying `desorry`, remember a passing Sledgehammer *preplay* is not
sufficient evidence — confirm the filled theory actually loads/builds, since
the bug the tool guards against is precisely preplay-vs-reload disagreement.

## Keeping documentation honest

When the tools' behavior changes, update **both** the Scala sources (header
comments + `Getopts` usage strings) **and** `isa_cli_tools/README.md`, then
reinstall and re-verify. The central invariant to preserve in the docs: the
tools **require a pre-built heap and never build one** — describe this as a
positive precondition, not as a list of things the tool won't do.
