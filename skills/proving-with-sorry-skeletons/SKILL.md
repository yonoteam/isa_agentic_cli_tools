---
name: proving-with-sorry-skeletons
description: Use when completing `sorry` proof obligations in an Isabelle/HOL theory from the shell with isa_cli_tools (`isabelle eval_at`, `isabelle desorry`), without jEdit or PIDE. Also use when an obligation does not fall to a direct one-line proof, when a proof attempt fails and you need the intermediate goal state, or when `isabelle build` feedback is too slow to iterate on.
---

# Proving with sorry Skeletons

## Overview

**Keep the theory loadable at every step, and never learn from a build what a
file-level command could have told you.**

A theory whose unfinished leaves are all `sorry` still loads. That property is what
makes these tools fast: `eval_at` state mode reports *every* error in one pass, and
`desorry` attacks *every* open leaf in parallel from one heap load. A theory containing
one half-written proof loads up to the break and tells you nothing past it.

## Two Modes, One Predicate

Not every obligation needs a skeleton. Choose by the size of the gap:

| Observable | Mode |
|---|---|
| The surrounding development gives you a plausible proof of a few lines | **Direct** — write it, validate with `eval_at`, move on |
| The proof you are about to write is longer than about ten lines | **Skeleton** |
| A direct attempt failed, or the statement needs an induction or case analysis you cannot close in one step | **Skeleton** |

The length trigger is the one agents talk themselves out of, so be concrete about what
it costs: **the skeleton is the outline you were going to write anyway.** Terminating its
branches with `sorry` instead of filling them in immediately costs you nothing, and buys
a structural check before you invest in any detail plus a parallel Sledgehammer attempt
on every leaf. Confidence that you can write the whole thing is not a reason to skip it;
you will write the same structure either way.

Two failed direct attempts on the same obligation means you are in skeleton mode
whether you have admitted it or not.

Both modes share the same rule about builds: **`isabelle build` is the final acceptance
gate, never the iteration loop.** A session build costs minutes to tens of minutes. File-
level `eval_at` costs seconds to a minute and tells you the same thing about your file.

## Preflight, Once

```bash
isabelle build -b -n -d . SESSION      # -b checks for an actual built heap
```

The tools never build heaps. If the theory's own session is unbuilt, pass a **built
parent** to `-l` and the project root to `-d`.

**A theory with several parent sessions still needs only one `-l`.** Pick the deepest
parent that is already built; the remaining imports load from source through `-d`. Fix
`-l PARENT -d .` here and reuse it verbatim in every later command.

Building a scratch base session that covers the union of the parents costs minutes of
heap build up front, and it buys something only when source reloading actually dominates
your `eval_at` passes. You cannot know that yet. **Run one `eval_at` pass first and time
it**; build the scratch session only if that measured cost, multiplied by the passes you
expect, exceeds the heap build. Never build one before the first pass.

## The Skeleton Loop

**1. Replace the obligation with a skeleton whose every leaf is `sorry`.**

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

The skeleton is the deliverable of this step. Close nothing yet.

**2. Validate the skeleton in one pass.**

```bash
isabelle eval_at -l PARENT -d . MyTheory.thy $(wc -l < MyTheory.thy)
```

State mode reports every `Error at line N` and every `Warning at line N`, recovering
after each. Because your leaves are `sorry`, what it finds is real structural error —
wrong case names, ill-typed `have`s, a bad induction rule — not follow-on noise. Repeat
until clean.

**3. Let `desorry` close the leaves.**

```bash
isabelle desorry -l PARENT -d . MyTheory.thy              # every sorry in the file
isabelle desorry -l PARENT -d . -L 42,58,71 MyTheory.thy  # only these
```

One invocation replays the theory once, then runs Sledgehammer on all selected leaves
**in parallel**, writing back only proofs it verified. Leaves it cannot close stay
`sorry`. `-L` lines must each be the line the `sorry` keyword sits on.

This is where the skeleton pays for itself. `desorry` on a whole unbroken obligation is
one Sledgehammer call against one large goal and will usually find nothing; `desorry` on
a ten-leaf skeleton is ten calls against ten small goals, for the price of one replay.

**4. Work the survivors.**

```bash
grep -n sorry MyTheory.thy                                    # what is left
isabelle eval_at -l PARENT -d . MyTheory.thy 58               # see the goal
isabelle eval_at -s -l PARENT -d . MyTheory.thy 58 'apply (induct xs)'
isabelle eval_at -l PARENT -d . MyTheory.thy 58 'find_theorems intro'
isabelle eval_at -l PARENT -d . MyTheory.thy 58 'sledgehammer'
```

A survivor means that leaf is still too big a step. Split it into finer `have`s, each
`sorry`, and return to step 2. **Refining the skeleton is the primary move.**

**5. Gate.**

```bash
grep -rn 'sorry\|oops\|axiomatization' *.thy
rm -f *.thy.backup
isabelle build -d . -o quick_and_dirty=false SESSION
```

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
  injection at line N` if anything above it failed. Get the file clean in state mode
  before injecting.
- **`desorry` parallelises across leaves but replays sequentially.** N leaves in one
  file cost one replay plus one parallel Sledgehammer round. N separate hand-attempts
  cost N replays. Batch your leaves.
- **Both tools replay one theory; a build replays a session.** Cheap loop for iteration,
  expensive one for acceptance.

## Common Mistakes

| Mistake | What actually happens | Do instead |
|---|---|---|
| Using `isabelle build` to check a proof you just wrote | Minutes per attempt; most of the session becomes waiting | `eval_at` state mode on the file; build once at the end |
| Attacking a large obligation with repeated whole-proof attempts | Each attempt costs a full replay and teaches you one bit | After the second failure, write a skeleton and let `desorry` work the leaves |
| Running `desorry` on an unbroken obligation and concluding it is useless | One Sledgehammer call against one large goal rarely lands | Break the goal into leaves first; `desorry` is a leaf tool |
| Writing a long proof straight through because you are confident | You find out whether the structure was right only at the end, and you pay a full replay per attempt | Same structure, `sorry` at every leaf; `desorry` closes what it can while you check the shape |
| Building a scratch session before timing an `eval_at` pass | Minutes of heap engineering bought against a cost you never measured | Run one pass, time it, then decide |
| Trusting a proof found above a remaining `sorry` | `sorry` is accepted under `quick_and_dirty` but changes the context — that proof can fail once the `sorry` is replaced | Re-run step 2 after every `desorry` pass; a leaf is done only when the file validates with no `sorry` left |
| Leaving `MyTheory.thy.backup` behind | `desorry` writes a backup beside the theory on every successful commit; it is a new file in the project | `rm -f *.thy.backup` before the final build and before any "only these files changed" check |
| Editing the theory while `desorry` runs | Concurrent edits are overwritten by the atomic commit | Wait for it to exit |

## Red Flags

- You are about to write a proof body longer than about ten lines in one go.
- You are about to run `isabelle build` to find out whether a proof step is right.
- A single obligation has failed two direct attempts and you are writing a third.
- You are reading a 70-line neighbouring proof to imitate it, with no skeleton on disk.
- You are about to build a session before you have timed a single `eval_at` pass.

**The first four mean: write the skeleton and run `desorry` on its leaves. The last means:
run the pass, then decide.**
