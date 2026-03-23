#!/usr/bin/env bash
# install.sh — Install (or upgrade) eval_at into an existing Isabelle installation.
#
# Usage:
#   bash install.sh /path/to/isabelle
#
# The script is idempotent: safe to re-run on an existing installation.
# Source files are overwritten; build.props and isabelle_tool.scala are only
# patched if the entries are not already present.

set -euo pipefail

ISABELLE_HOME="${1:-}"

if [ -z "$ISABELLE_HOME" ]; then
  echo "Error: Isabelle home directory required as first argument." >&2
  echo "Usage: $0 /path/to/isabelle" >&2
  exit 1
fi

if [ ! -f "$ISABELLE_HOME/bin/isabelle" ]; then
  echo "Error: '$ISABELLE_HOME/bin/isabelle' not found." >&2
  echo "Please provide the root of an Isabelle installation." >&2
  exit 1
fi

BUILD_PROPS="$ISABELLE_HOME/etc/build.props"
TOOL_SCALA="$ISABELLE_HOME/src/Pure/System/isabelle_tool.scala"

echo "=== Installing eval_at into $ISABELLE_HOME ==="

# ── Step 1: copy Scala source ──────────────────────────────────────────────
echo ""
echo "Step 1/4: Copying eval_at.scala..."
cp src/Pure/Tools/eval_at.scala "$ISABELLE_HOME/src/Pure/Tools/eval_at.scala"
echo "  -> $ISABELLE_HOME/src/Pure/Tools/eval_at.scala"

# ── Step 2: register source in build.props ─────────────────────────────────
echo ""
echo "Step 2/4: Registering eval_at.scala in etc/build.props..."

if grep -qF "eval_at.scala" "$BUILD_PROPS"; then
  echo "  (already present, skipping)"
else
  # Insert alphabetically: try before flarum.scala (always present)
  sed -i.bak \
    's|  src/Pure/Tools/flarum\.scala \\|  src/Pure/Tools/eval_at.scala \\\n  src/Pure/Tools/flarum.scala \\|' \
    "$BUILD_PROPS"

  if grep -qF "eval_at.scala" "$BUILD_PROPS"; then
    echo "  -> Added before src/Pure/Tools/flarum.scala"
  else
    echo ""
    echo "ERROR: Could not automatically insert into build.props." >&2
    echo "Please add the following line manually to the 'sources' block in" >&2
    echo "$BUILD_PROPS, in alphabetical order among the Pure/Tools entries:" >&2
    echo "" >&2
    echo "  src/Pure/Tools/eval_at.scala \\" >&2
    exit 1
  fi
fi

# ── Step 3: register tool in isabelle_tool.scala ───────────────────────────
echo ""
echo "Step 3/4: Registering Eval_At in isabelle_tool.scala..."

if grep -qF "Eval_At" "$TOOL_SCALA"; then
  echo "  (already present, skipping)"
else
  # Insert alphabetically before Export.isabelle_tool
  sed -i.bak \
    's|  Export\.isabelle_tool,|  Eval_At.isabelle_tool,\n  Export.isabelle_tool,|' \
    "$TOOL_SCALA"

  if grep -qF "Eval_At" "$TOOL_SCALA"; then
    echo "  -> Added Eval_At.isabelle_tool before Export.isabelle_tool"
  else
    echo ""
    echo "ERROR: Could not automatically insert Eval_At into isabelle_tool.scala." >&2
    echo "Please add the following line manually to 'class Tools extends Isabelle_Scala_Tools('," >&2
    echo "before 'Export.isabelle_tool,', in $TOOL_SCALA:" >&2
    echo "" >&2
    echo "  Eval_At.isabelle_tool," >&2
    exit 1
  fi
fi

# ── Step 4: rebuild ────────────────────────────────────────────────────────
echo ""
echo "Step 4/4: Rebuilding Isabelle/Scala (this takes a moment)..."
"$ISABELLE_HOME/bin/isabelle" scala_build

# ── Done ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Installation complete ==="
echo ""
echo "Usage:"
echo "  $ISABELLE_HOME/bin/isabelle eval_at THY_FILE LINE [COMMAND]"
echo ""
echo "Examples:"
echo "  $ISABELLE_HOME/bin/isabelle eval_at Foo.thy 42"
echo "  $ISABELLE_HOME/bin/isabelle eval_at Foo.thy 42 'find_theorems \"_ + _\"'"
echo "  $ISABELLE_HOME/bin/isabelle eval_at Foo.thy 17 'sledgehammer'"
echo "  $ISABELLE_HOME/bin/isabelle eval_at -s Foo.thy 15 'apply auto'"
echo "  $ISABELLE_HOME/bin/isabelle eval_at Foo.thy 10 'term \"True\"'"
echo "  $ISABELLE_HOME/bin/isabelle eval_at Foo.thy 10 'thm conjI'"
echo "  $ISABELLE_HOME/bin/isabelle eval_at Foo.thy 10 'value \"[1,2,3::nat]\"'"
echo ""
echo "Run --help for all options:"
echo "  $ISABELLE_HOME/bin/isabelle eval_at --help"
