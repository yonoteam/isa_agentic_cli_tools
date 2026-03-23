#!/usr/bin/env bash
# install.sh — Install (or upgrade) eval_at and desorry into an existing Isabelle installation.
#
# Usage:
#   bash install.sh [-f] /path/to/isabelle
#
# Options:
#   -f   Force reinstallation: re-register tools in build.props and
#        isabelle_tool.scala even if entries already exist (useful if
#        registrations are corrupted or out of date).
#
# The script is idempotent: safe to re-run on an existing installation.
# Source files are always overwritten. Registration entries in build.props
# and isabelle_tool.scala are only patched if not already present (unless
# -f is given).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FORCE=false

while getopts "f" opt; do
  case "$opt" in
    f) FORCE=true ;;
    *) echo "Usage: $0 [-f] /path/to/isabelle" >&2; exit 1 ;;
  esac
done
shift $((OPTIND - 1))

ISABELLE_HOME="${1:-}"

if [ -z "$ISABELLE_HOME" ]; then
  echo "Error: Isabelle home directory required as first argument." >&2
  echo "Usage: $0 [-f] /path/to/isabelle" >&2
  exit 1
fi

if [ ! -f "$ISABELLE_HOME/bin/isabelle" ]; then
  echo "Error: '$ISABELLE_HOME/bin/isabelle' not found." >&2
  echo "Please provide the root of an Isabelle installation." >&2
  exit 1
fi

BUILD_PROPS="$ISABELLE_HOME/etc/build.props"
TOOL_SCALA="$ISABELLE_HOME/src/Pure/System/isabelle_tool.scala"

echo "=== Installing eval_at + desorry into $ISABELLE_HOME ==="

# ── Step 1: copy Scala sources ───────────────────────────────────────────
echo ""
echo "Step 1/6: Copying eval_at.scala..."
cp "$SCRIPT_DIR/src/Pure/Tools/eval_at.scala" "$ISABELLE_HOME/src/Pure/Tools/eval_at.scala"
echo "  -> $ISABELLE_HOME/src/Pure/Tools/eval_at.scala"

echo ""
echo "Step 2/6: Copying desorry.scala..."
cp "$SCRIPT_DIR/src/Pure/Tools/desorry.scala" "$ISABELLE_HOME/src/Pure/Tools/desorry.scala"
echo "  -> $ISABELLE_HOME/src/Pure/Tools/desorry.scala"

# ── Step 2: register sources in build.props ──────────────────────────────
echo ""
echo "Step 3/6: Registering sources in etc/build.props..."

if grep -qF "eval_at.scala" "$BUILD_PROPS" && [ "$FORCE" = false ]; then
  echo "  eval_at.scala (already present, skipping — use -f to force)"
else
  # Remove existing entry first (if any) to avoid duplicates
  sed -i.bak '/src\/Pure\/Tools\/eval_at\.scala/d' "$BUILD_PROPS"
  sed -i.bak \
    's|  src/Pure/Tools/flarum\.scala \\|  src/Pure/Tools/eval_at.scala \\\n  src/Pure/Tools/flarum.scala \\|' \
    "$BUILD_PROPS"
  if grep -qF "eval_at.scala" "$BUILD_PROPS"; then
    echo "  eval_at.scala -> added before flarum.scala"
  else
    echo "ERROR: Could not insert eval_at.scala into build.props." >&2
    exit 1
  fi
fi

if grep -qF "desorry.scala" "$BUILD_PROPS" && [ "$FORCE" = false ]; then
  echo "  desorry.scala (already present, skipping — use -f to force)"
else
  # Remove existing entry first (if any) to avoid duplicates
  sed -i.bak '/src\/Pure\/Tools\/desorry\.scala/d' "$BUILD_PROPS"
  # Insert alphabetically before eval_at.scala
  sed -i.bak \
    's|  src/Pure/Tools/eval_at\.scala \\|  src/Pure/Tools/desorry.scala \\\n  src/Pure/Tools/eval_at.scala \\|' \
    "$BUILD_PROPS"
  if grep -qF "desorry.scala" "$BUILD_PROPS"; then
    echo "  desorry.scala -> added before eval_at.scala"
  else
    echo "ERROR: Could not insert desorry.scala into build.props." >&2
    exit 1
  fi
fi

# ── Step 3: register tools in isabelle_tool.scala ────────────────────────
echo ""
echo "Step 4/6: Registering tools in isabelle_tool.scala..."

if grep -qF "Eval_At" "$TOOL_SCALA" && [ "$FORCE" = false ]; then
  echo "  Eval_At (already present, skipping — use -f to force)"
else
  # Remove existing entry first (if any) to avoid duplicates
  sed -i.bak '/Eval_At\.isabelle_tool/d' "$TOOL_SCALA"
  sed -i.bak \
    's|  Export\.isabelle_tool,|  Eval_At.isabelle_tool,\n  Export.isabelle_tool,|' \
    "$TOOL_SCALA"
  if grep -qF "Eval_At" "$TOOL_SCALA"; then
    echo "  Eval_At.isabelle_tool -> added before Export"
  else
    echo "ERROR: Could not insert Eval_At into isabelle_tool.scala." >&2
    exit 1
  fi
fi

if grep -qF "Desorry" "$TOOL_SCALA" && [ "$FORCE" = false ]; then
  echo "  Desorry (already present, skipping — use -f to force)"
else
  # Remove existing entry first (if any) to avoid duplicates
  sed -i.bak '/Desorry\.isabelle_tool/d' "$TOOL_SCALA"
  # Insert alphabetically before Eval_At.isabelle_tool
  sed -i.bak \
    's|  Eval_At\.isabelle_tool,|  Desorry.isabelle_tool,\n  Eval_At.isabelle_tool,|' \
    "$TOOL_SCALA"
  if grep -qF "Desorry" "$TOOL_SCALA"; then
    echo "  Desorry.isabelle_tool -> added before Eval_At"
  else
    echo "ERROR: Could not insert Desorry into isabelle_tool.scala." >&2
    exit 1
  fi
fi

# ── Step 4: rebuild ──────────────────────────────────────────────────────
echo ""
echo "Step 5/6: Rebuilding Isabelle/Scala (this takes a moment)..."
"$ISABELLE_HOME/bin/isabelle" scala_build

# ── Step 5: verify ───────────────────────────────────────────────────────
echo ""
echo "Step 6/6: Verifying installation..."
# Invoke with no args: registered tools print usage (rc=1), unregistered tools
# print "Unknown Isabelle tool" (rc=2).  Check the output for "Usage:".
# Use a subshell to avoid pipefail propagating the non-zero rc from the tool.
(set +o pipefail; "$ISABELLE_HOME/bin/isabelle" eval_at 2>&1 | grep -q "^Usage:") && echo "  eval_at: OK" || echo "  eval_at: FAILED"
(set +o pipefail; "$ISABELLE_HOME/bin/isabelle" desorry 2>&1 | grep -q "^Usage:") && echo "  desorry: OK" || echo "  desorry: FAILED"

# ── Done ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Installation complete ==="
echo ""
echo "Tools:"
echo "  isabelle eval_at THY_FILE LINE [COMMAND]"
echo "  isabelle desorry THY_FILE [LINE]"
echo ""
echo "Examples:"
echo "  isabelle eval_at Foo.thy 42"
echo "  isabelle eval_at -t Foo.thy 17 'sledgehammer'"
echo "  isabelle desorry Foo.thy"
echo "  isabelle desorry -t 60 Foo.thy 42"
echo ""
echo "Run with no arguments for all options:"
echo "  isabelle eval_at"
echo "  isabelle desorry"
