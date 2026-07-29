# Isabelle agentic CLI tools

Command-line tools that expose Isabelle functionality for agentic and other
non-PIDE workflows. The current tools are `eval_at` and `desorry`. Earlier
command-specific experiments are retained under `legacy/`.

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

This repository originated as a proof of concept for spec-driven
autoformalization workflows such as
[isa_top_autoform1](https://github.com/JUrban/isa_top_autoform1).
