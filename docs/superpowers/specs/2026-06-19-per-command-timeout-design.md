# Design: per-command replay timeout for `desorry` and `eval_at`

Date: 2026-06-19
Status: approved (brainstorming complete)

## Problem

Agents are instructed to write a proof script with `sorry`s and then run
`desorry` to discharge them. In practice they also write concrete tactics
(`by blast`, `by metis`, `apply …`) above those `sorry`s. When one of those
tactics does not terminate, `desorry`'s Phase-1 replay hangs on that transition
and never reaches the `sorry`s — the whole tool spins indefinitely with no
diagnostic. `eval_at` has the same exposure: it replays transitions up to a
target line (and can inject a command), so a looping tactic — or an injected
`apply` that loops — hangs it too.

We want the tools to **stop when a single command runs too long and report the
exact line**, so the agent can fix or replace that tactic and re-run.

## Why per-command, not a total budget

A *total* wall-clock budget cannot distinguish the two cases it must separate:

- a **large but legitimate** theory — many transitions, each fast; total time
  large, every individual line fast;
- a **looping tactic** — one transition that never returns; total time
  infinite, one line at fault.

Any total budget tight enough to catch loops quickly is also tight enough to
kill a big honest file, which would make the tools useless as files grow. The
distinguishing signal is **how long a single transition runs**, so the limit is
applied **per command**. This is independent of file size: a 10,000-line file
with fast proofs is never penalized, while a single looping `by blast` is
aborted shortly after it starts. It still satisfies "the tool won't run
forever": a loop dies in `t` seconds instead of hanging.

Per-command timing adds negligible cost: Isabelle's `Timeout.apply` uses one
shared `Event_Timer`; wrapping each transition is a cheap request/cancel pair
per command, dwarfed by the cost of actually running the tactic. A timeout of
`0` short-circuits to a plain call (no timer, free).

## Mechanism

The single touch point in each tool is the function every replayed transition
flows through:

- `desorry`: `execute_transition` in Phase 1.
- `eval_at`: `exec_timing` (used in both state mode and inject mode).

In each, wrap the inner call:

```sml
Timeout.apply (Time.fromSeconds t) (fn () => Toplevel.command_exception tr st) ()
```

Because the limit is per command, the `Timeout.TIMEOUT` exception surfaces
*inside* that wrapper where the transition `tr` is in scope, so the exact line
(`Position.line_of (Toplevel.pos_of tr)`) and the line's source text are
available for the diagnostic — no separate "current line" tracking ref is
needed.

`Timeout.apply` is subject to the `timeout_scale` system option (default `1.0`);
we use the standard (logical) variant.

### Behavior on timeout: abort and report

A `Timeout.TIMEOUT` is treated differently from an ordinary command error.
Today both tools *warn and continue* on a failed transition (continuing makes
sense for an ordinary error). For a timeout we **abort the run immediately**,
because once a command has hung the proof state is meaningless and continuing
only produces cascade errors.

- `desorry`: abort **before any file mutation** — no `.backup` is written, the
  theory file is left exactly as-is. Emit one user-facing (`[RESULT]`-tagged,
  so it shows in non-verbose mode) line:

  ```
  desorry: timed out after 60s at line 47 (by blast); no changes written.
  ```

- `eval_at`: abort and report the offending line in the same style; print
  nothing else.

Implementation note for the plan: the timeout message must be emitted *between*
the existing stdout sentinels so the Scala side extracts it through the normal
path. In `desorry` the Phase-1 replay (`process`) currently runs *before*
`writeln sentinel_start`; `sentinel_start` must move ahead of the replay and the
body be wrapped in a handler that, on the timeout exception, writes the message
then `sentinel_end`. In `eval_at`, `sentinel_start` is already written before
the replay folds, so only a timeout case is added to the existing per-transition
handler (alongside the current error/`sentinel_end`/reraise path).

A dedicated ML exception (e.g. `exception Timed_Out of int * string` carrying
line and content) raised from the wrapper and caught at the top of the script is
the cleanest way to unwind without writing the file.

### Known limitation

`Timeout.apply` works via interrupts, so a pathological tactic stuck in a tight
ML loop that never polls for interrupts would not be stopped. This essentially
does not occur with real `by`/`apply` tactics — `Timeout` is the same mechanism
Isabelle uses to bound Sledgehammer and session proofs, and `simp`/`auto`/
`blast` all poll. We accept this limitation rather than add an external
process-kill layer (rejected during design as more plumbing for a case that
does not arise in practice).

## CLI changes

Isabelle's `Getopts` supports **single-character options only** (`"x"` or
`"x:"`); multi-character/long options (e.g. `-times`) are not possible and
`Getopts` itself is in the off-limits `isabelle_dev` tree, so the design stays
within single letters.

### `desorry`

- **Remove** `-t`-as-Sledgehammer-timeout. Sledgehammer is **hard-coded to
  50s** per sorry (was a configurable default of 30).
- **Add** `-t SECS` = **per-transition replay timeout**, default **60**;
  `-t 0` disables it (today's unbounded behavior).
- Unchanged: `-L`, `-d`, `-l`, `-o`, `-v`.

Final set: `-L`, `-d`, `-l`, `-o`, `-t SECS` (per-transition timeout, default
60), `-v`. Sledgehammer fixed at 50s.

### `eval_at`

- **`-S`** = show sorts **and** types. (No behavior change — `-S` already sets
  both `show_sorts` and `show_types`; this just removes the now-redundant
  separate types-only flag.)
- **Delete** `-T`-as-show-types.
- **Add** `-t SECS` = **per-transition replay timeout** (consistent with
  `desorry`); default **60**, `-t 0` disables.
- **Repurpose** `-T` = **report timings** (the old `-t` "report timing for each
  processed line" feature moves here; capital `T` = **T**iming).
- Unchanged: `-U`, `-d`, `-l`, `-o`, `-s`, `-v`.

Final set: `-S` (sorts+types), `-U`, `-d`, `-l`, `-o`, `-s`, `-t SECS`
(per-transition timeout, default 60), `-T` (report timings), `-v`.

Result: `-t SECS` means the **same thing** (per-transition timeout) in both
tools.

## Scope

In scope: `desorry` and `eval_at` Scala sources, their `Getopts` usage strings
and header comments, and the doc/skill updates below. Out of scope: any change
to `isabelle_dev`; any external process-kill backstop; a total-runtime budget.

## Documentation to update (must stay in sync)

- `isa_cli_tools/src/Pure/Tools/desorry.scala` — header comment + `Getopts`
  usage + examples.
- `isa_cli_tools/src/Pure/Tools/eval_at.scala` — header comment + `Getopts`
  usage + examples.
- `isa_cli_tools/README.md` — option tables for both tools; the `-t` timing
  section (now `-T`); examples using `desorry -t 60` (now means timeout, and the
  Sledgehammer-timeout wording must change to "fixed at 50s"); the
  troubleshooting tip recommending `-t 60` for desorry.
- `CLAUDE.md` — any flag references in the testing section.
- `~/.claude/skills/isabelle-proof-workflow/SKILL.md` — the
  `desorry … [-t 60] …` line (its meaning changes from Sledgehammer time to
  per-transition timeout; reword accordingly).
- Reinstall via `bash install.sh <root>` after editing sources.

## Testing

Fixtures in `tst/Thy_Tests/` (new test theory, e.g. `test9`):

1. **Looping tactic above a sorry** — a non-terminating tactic (e.g. a
   self-referential `apply`/recursive proof) on a known line, with a `sorry`
   below it.
2. Reuse `test7/Test_Sorry.thy` (four `sorry`s, `Main`) for the no-regression
   case.

Because `desorry` rewrites its target in place, copy fixtures into
`agents/temp/<run>/` before destructive runs (theory name must match filename).

Verify:

- `desorry` on fixture 1 aborts at ~the timeout, names the **correct line and
  content**, and leaves the file **and** `.backup` untouched (no `.backup`
  created).
- `desorry -t 0` on fixture 1 restores unbounded behavior (hangs / must be
  killed manually — run with an outer wrapper timeout in the test).
- `desorry` on fixture 2 still fills all four `sorry`s with verified non-smt
  proofs and the filled theory **reloads/builds** (preplay-vs-reload check).
- `eval_at` on a file with a looping tactic before the target line aborts and
  names the line; `eval_at … 'apply (<loop>)'` (inject mode) aborts and reports.
- `eval_at -T` reports timings (old `-t` behavior); `eval_at -t 5` enforces the
  timeout; `eval_at -S` shows sorts+types; `-T`-as-show-types is gone.

Per project rule: a passing Sledgehammer preplay is not sufficient evidence for
the `desorry` no-regression case — confirm the filled theory actually loads.
