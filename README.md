# Isabelle agentic CLI tools

Command-line tools that expose Isabelle functionality for agentic and other
non-PIDE workflows. The current tools are `eval_at` and `desorry`.

## Isabelle compatibility

Choose the branch that matches the Isabelle installation:

| Branch | Compatible Isabelle version |
|---|---|
| `main` | Isabelle development repository at changeset `5085f506929bfb3124fbfb1ab0d50063e537fc25` (`tuned names`, 2026-07-27) |
| `2025` | Isabelle2025 |
| `2025-2` | Isabelle2025-2 |

The branches contain release-specific source adaptations and should not be
mixed with a different Isabelle version.

See the [isa_cli_tools guide](isa_cli_tools/README.md) for installation,
session selection, usage, and troubleshooting.

## Recommended workflow

These tools are built around a **sorry-skeleton loop**. When an obligation does not fall
to a short direct proof — or when the proof you are about to write runs past ten lines —
replace it with the Isar structure you believe the proof has,
terminating every branch in `sorry`; validate the whole file in one `eval_at` pass; let
`desorry` close the leaves in parallel; refine the skeleton for whatever survives. In
either case `isabelle build` is the final acceptance check, never the iteration loop.

**Read [`skills/proving-with-sorry-skeletons/SKILL.md`](skills/proving-with-sorry-skeletons/SKILL.md)
before your first tool invocation.** It is short, and it carries the worked skeleton, the
session-preflight rules, and the mistakes that cost the most time here — chiefly using
`isabelle build` as a feedback loop, and hand-writing a long proof that a skeleton plus
`desorry` would have closed in parallel. The summary above is orientation, not a
substitute for it.

This repository originated as a proof of concept for spec-driven
autoformalization workflows such as
[isa_top_autoform1](https://github.com/JUrban/isa_top_autoform1).
