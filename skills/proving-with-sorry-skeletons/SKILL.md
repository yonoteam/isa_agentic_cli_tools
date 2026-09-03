---
name: proving-with-sorry-skeletons
description: Use when completing `sorry` proof obligations in an Isabelle/HOL theory from the shell with isa_cli_tools (`isabelle eval_at`, `isabelle desorry`), without jEdit or PIDE. Also use when a proof is too large to write in one attempt, when `isabelle build` feedback is too slow to iterate on, or when a proof attempt fails and you need the intermediate goal state.
---

# Proving with sorry Skeletons

## Overview

**Keep the theory loadable at every step.**

A theory whose unfinished leaves are all `sorry` still loads. That single property is
what makes the CLI tools fast: `eval_at` state mode reports *every* error in one pass,
and `desorry` attacks *every* open leaf in parallel from one heap load. A theory
containing one half-written proof loads up to the break and tells you nothing past it.

So you do not write proofs and then check them. You write *structure*, keep it loading,
and let the tools fill in the leaves.

## The Loop

**1. Preflight the session, once.**

```bash
isabelle build -b -n -d . SESSION      # -b checks for an actual built heap
```

The tools never build heaps. If the theory's own session is unbuilt, pass its built
parent to `-l` and the project root to `-d`. Establish this once and reuse the same
`-l`/`-d` for every later command.

**2. Replace the obligation with a skeleton whose every leaf is `sorry`.**

Write the Isar structure you believe the proof has — the induction, the case split, the
intermediate `have`s — and terminate every branch with `sorry`.

```isabelle
lemma const_subst_wffs:
  shows "\<^bold>S\<^sub>c (c, \<tau>) x A \<in> wffs\<^bsub>\<alpha>\<^esub>"
proof (induction rule: wffs_of_type_induct)
  case (var_is_wff \<beta> y)
  show ?case sorry
next
  case (app_is_wff \<beta> B C)
  have "C \<in> wffs\<^bsub>\<beta>\<^esub>" sorry
  then show ?case sorry
qed
```

The skeleton is the deliverable of this step. Do not try to close any leaf yet.

**3. Validate the skeleton in one pass.**

```bash
isabelle eval_at -l PARENT -d . MyTheory.thy $(wc -l < MyTheory.thy)
```

State mode reports every `Error at line N` and every `Warning at line N` up to that
line, recovering after each. Because your leaves are `sorry`, the errors it finds are
real structural errors — wrong case names, ill-typed statements, a bad induction rule —
not follow-on noise. Fix them and repeat until this pass is clean.

**4. Let `desorry` close the leaves.**

```bash
isabelle desorry -l PARENT -d . MyTheory.thy              # every sorry in the file
isabelle desorry -l PARENT -d . -L 42,58,71 MyTheory.thy  # only these
```

One invocation replays the theory once, then runs Sledgehammer on all selected leaves
**in parallel**, and rewrites only the ones whose proof it verified. Leaves it cannot
close are left as `sorry`. `-L` lines must each be the line the `sorry` keyword sits on.

**5. Work the survivors interactively.**

```bash
grep -n sorry MyTheory.thy                                    # what is left
isabelle eval_at -l PARENT -d . MyTheory.thy 58               # see the goal
isabelle eval_at -s -l PARENT -d . MyTheory.thy 58 'apply (induct xs)'
isabelle eval_at -l PARENT -d . MyTheory.thy 58 'find_theorems intro'
isabelle eval_at -l PARENT -d . MyTheory.thy 58 'sledgehammer'
```

A survivor usually means the leaf is too big a step. Split it into a finer skeleton —
more `have`s, each `sorry` — and go back to step 3. Refining the skeleton is the
primary move; hand-writing the whole proof is the last resort, not the first.

**6. Build only as the final gate.**

```bash
grep -rn 'sorry\|oops\|axiomatization' *.thy
rm -f *.thy.backup
isabelle build -d . -o quick_and_dirty=false SESSION
```

A session build replays every theory in the session and costs minutes to tens of
minutes. It is the acceptance check you run once at the end, not the feedback loop you
iterate against. Everything before this point is file-level, and file-level is seconds
to a minute.

## Quick Reference

| Need | Command |
|---|---|
| Is the heap built? | `isabelle build -b -n -d . SESSION` |
| Validate whole file, all errors | `isabelle eval_at -l P -d . T.thy $(wc -l < T.thy)` |
| See the goal at a leaf | `isabelle eval_at -l P -d . T.thy N` |
| Try a method, see resulting goal | `isabelle eval_at -s -l P -d . T.thy N 'apply auto'` |
| Find relevant facts | `isabelle eval_at -l P -d . T.thy N 'find_theorems "?x + ?y"'` |
| Close all open leaves | `isabelle desorry -l P -d . T.thy` |
| Close specific leaves | `isabelle desorry -l P -d . -L 42,58 T.thy` |
| Slow replay times out | add `-t 120` |
| Unicode instead of ASCII | add `-U` |

## Why This Order

- **`eval_at` state mode recovers from errors; injection mode does not.** State mode
  continues from the state before each failed transition, so one run surfaces every
  independent error. Injection mode (`COMMAND` given) aborts with `Error before
  injection at line N` if anything above the injection point failed. Get the file clean
  in state mode *before* you try to inject anything.
- **`desorry` parallelises across leaves but replays sequentially.** N leaves in one
  file cost roughly one replay plus one parallel Sledgehammer round. N separate
  hand-attempts cost N replays. Batch your leaves.
- **Both tools replay one theory; a build replays a session.** Use the cheap loop for
  iteration and the expensive one for acceptance.

## Common Mistakes

| Mistake | What actually happens | Do instead |
|---|---|---|
| Writing complete proofs first, then checking | One error blinds you to everything after it, and each retry costs a full replay | Skeleton with `sorry` leaves, validate, then fill |
| Using `isabelle build` as the iteration loop | Minutes to tens of minutes per attempt; most of the run is spent waiting | Iterate with `eval_at` on the file; build once at the end |
| Never invoking `desorry` | Sledgehammer is doing the same search you are doing by hand, faster and in parallel | Run `desorry` on the skeleton before hand-writing anything |
| Trusting a proof found above a remaining `sorry` | `sorry` is accepted under `quick_and_dirty` but changes the context — that proof can fail once the `sorry` is replaced | Re-run step 3 after every `desorry` pass; a leaf is done only when the file validates with no `sorry` left |
| Leaving `MyTheory.thy.backup` behind | `desorry` writes a backup beside the theory on every successful commit; it is a new file in the project | `rm -f *.thy.backup` before the final build and before any "only these files changed" check |
| Editing the theory while `desorry` runs | Concurrent edits get overwritten by the atomic commit | Wait for it to exit |
| Omitting `-l` / `-d` on a custom session | Derivation picks the theory's own unbuilt session and the tool refuses | Fix `-l PARENT -d .` once at preflight, reuse everywhere |

## Red Flags

- You are about to write a multi-step proof body with no `sorry` in it.
- You are about to run `isabelle build` and you have not yet run `desorry`.
- You are waiting on a build to learn whether a proof step is right.
- A leaf has failed twice and you are writing a third full attempt instead of splitting it.

**All of these mean: go back to step 2 and make the skeleton finer.**
