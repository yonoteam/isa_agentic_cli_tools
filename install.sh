#!/usr/bin/env bash
# install.sh — Install (or upgrade) find_thms_at, proof_state_at, and
# sledgehammer_at into an existing Isabelle installation.
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

echo "=== Installing find_thms_at, proof_state_at, and sledgehammer_at into $ISABELLE_HOME ==="

# ── Step 1: copy Scala sources ─────────────────────────────────────────────
echo ""
echo "Step 1/8: Copying find_thms_at.scala..."
cp src/Pure/Tools/find_thms_at.scala "$ISABELLE_HOME/src/Pure/Tools/find_thms_at.scala"
echo "  -> $ISABELLE_HOME/src/Pure/Tools/find_thms_at.scala"

echo ""
echo "Step 2/8: Copying proof_state_at.scala..."
cp src/Pure/Tools/proof_state_at.scala "$ISABELLE_HOME/src/Pure/Tools/proof_state_at.scala"
echo "  -> $ISABELLE_HOME/src/Pure/Tools/proof_state_at.scala"

echo ""
echo "Step 3/8: Copying sledgehammer_at.scala..."
cp src/Pure/Tools/sledgehammer_at.scala "$ISABELLE_HOME/src/Pure/Tools/sledgehammer_at.scala"
echo "  -> $ISABELLE_HOME/src/Pure/Tools/sledgehammer_at.scala"

# ── Step 2: register sources in build.props ────────────────────────────────
echo ""
echo "Step 4/8: Registering find_thms_at.scala in etc/build.props..."

if grep -qF "find_thms_at.scala" "$BUILD_PROPS"; then
  echo "  (already present, skipping)"
else
  sed -i.bak \
    's|  src/Pure/Tools/flarum\.scala \\|  src/Pure/Tools/find_thms_at.scala \\\n  src/Pure/Tools/flarum.scala \\|' \
    "$BUILD_PROPS"

  if grep -qF "find_thms_at.scala" "$BUILD_PROPS"; then
    echo "  -> Added before src/Pure/Tools/flarum.scala"
  else
    echo ""
    echo "ERROR: Could not automatically insert into build.props." >&2
    echo "Please add the following line manually to the 'sources' block in" >&2
    echo "$BUILD_PROPS, in alphabetical order among the Pure/Tools entries:" >&2
    echo "" >&2
    echo "  src/Pure/Tools/find_thms_at.scala \\" >&2
    exit 1
  fi
fi

echo ""
echo "Step 5/8: Registering proof_state_at.scala in etc/build.props..."

if grep -qF "proof_state_at.scala" "$BUILD_PROPS"; then
  echo "  (already present, skipping)"
else
  # Insert alphabetically after profiling.scala
  sed -i.bak \
    's|  src/Pure/Tools/profiling\.scala \\|  src/Pure/Tools/profiling.scala \\\n  src/Pure/Tools/proof_state_at.scala \\|' \
    "$BUILD_PROPS"

  if grep -qF "proof_state_at.scala" "$BUILD_PROPS"; then
    echo "  -> Added after src/Pure/Tools/profiling.scala"
  else
    echo ""
    echo "ERROR: Could not automatically insert into build.props." >&2
    echo "Please add the following line manually to the 'sources' block in" >&2
    echo "$BUILD_PROPS, in alphabetical order among the Pure/Tools entries:" >&2
    echo "" >&2
    echo "  src/Pure/Tools/proof_state_at.scala \\" >&2
    exit 1
  fi
fi

echo ""
echo "Step 6/8: Registering sledgehammer_at.scala in etc/build.props..."

if grep -qF "sledgehammer_at.scala" "$BUILD_PROPS"; then
  echo "  (already present, skipping)"
else
  # Insert alphabetically before spell_checker.scala
  sed -i.bak \
    's|  src/Pure/Tools/spell_checker\.scala \\|  src/Pure/Tools/sledgehammer_at.scala \\\n  src/Pure/Tools/spell_checker.scala \\|' \
    "$BUILD_PROPS"

  if grep -qF "sledgehammer_at.scala" "$BUILD_PROPS"; then
    echo "  -> Added before src/Pure/Tools/spell_checker.scala"
  else
    echo ""
    echo "ERROR: Could not automatically insert into build.props." >&2
    echo "Please add the following line manually to the 'sources' block in" >&2
    echo "$BUILD_PROPS, in alphabetical order among the Pure/Tools entries:" >&2
    echo "" >&2
    echo "  src/Pure/Tools/sledgehammer_at.scala \\" >&2
    exit 1
  fi
fi

# ── Step 3: register tools in isabelle_tool.scala ──────────────────────────
echo ""
echo "Step 7/8: Registering tools in isabelle_tool.scala..."

if grep -qF "Find_Thms_At" "$TOOL_SCALA"; then
  echo "  Find_Thms_At (already present, skipping)"
else
  sed -i.bak \
    's|  Export\.isabelle_tool,|  Export.isabelle_tool,\n  Find_Thms_At.isabelle_tool,|' \
    "$TOOL_SCALA"

  if grep -qF "Find_Thms_At" "$TOOL_SCALA"; then
    echo "  -> Added Find_Thms_At.isabelle_tool after Export.isabelle_tool"
  else
    echo ""
    echo "ERROR: Could not automatically insert Find_Thms_At into isabelle_tool.scala." >&2
    echo "Please add the following line manually to 'class Tools extends Isabelle_Scala_Tools('," >&2
    echo "after 'Export.isabelle_tool,', in $TOOL_SCALA:" >&2
    echo "" >&2
    echo "  Find_Thms_At.isabelle_tool," >&2
    exit 1
  fi
fi

if grep -qF "Proof_State_At" "$TOOL_SCALA"; then
  echo "  Proof_State_At (already present, skipping)"
else
  # Insert after Profiling.isabelle_tool (alphabetical slot P)
  sed -i.bak \
    's|  Profiling\.isabelle_tool,|  Profiling.isabelle_tool,\n  Proof_State_At.isabelle_tool,|' \
    "$TOOL_SCALA"

  if grep -qF "Proof_State_At" "$TOOL_SCALA"; then
    echo "  -> Added Proof_State_At.isabelle_tool after Profiling.isabelle_tool"
  else
    echo ""
    echo "ERROR: Could not automatically insert Proof_State_At into isabelle_tool.scala." >&2
    echo "Please add the following line manually to 'class Tools extends Isabelle_Scala_Tools('," >&2
    echo "after 'Profiling.isabelle_tool,', in $TOOL_SCALA:" >&2
    echo "" >&2
    echo "  Proof_State_At.isabelle_tool," >&2
    exit 1
  fi
fi

if grep -qF "Sledgehammer_At" "$TOOL_SCALA"; then
  echo "  Sledgehammer_At (already present, skipping)"
else
  # Insert after Sessions.isabelle_tool (alphabetical slot S)
  sed -i.bak \
    's|  Sessions\.isabelle_tool,|  Sessions.isabelle_tool,\n  Sledgehammer_At.isabelle_tool,|' \
    "$TOOL_SCALA"

  if grep -qF "Sledgehammer_At" "$TOOL_SCALA"; then
    echo "  -> Added Sledgehammer_At.isabelle_tool after Sessions.isabelle_tool"
  else
    echo ""
    echo "ERROR: Could not automatically insert Sledgehammer_At into isabelle_tool.scala." >&2
    echo "Please add the following line manually to 'class Tools extends Isabelle_Scala_Tools('," >&2
    echo "after 'Sessions.isabelle_tool,', in $TOOL_SCALA:" >&2
    echo "" >&2
    echo "  Sledgehammer_At.isabelle_tool," >&2
    exit 1
  fi
fi

# ── Step 4: rebuild ──────────────────────────────────────────────────────
echo ""
echo "Step 8/8: Rebuilding Isabelle/Scala (this takes a moment)..."
"$ISABELLE_HOME/bin/isabelle" scala_build

# ── Done ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Installation complete ==="
echo ""
echo "Usage:"
echo "  $ISABELLE_HOME/bin/isabelle find_thms_at THY_FILE LINE QUERY"
echo "  $ISABELLE_HOME/bin/isabelle proof_state_at THY_FILE LINE"
echo "  $ISABELLE_HOME/bin/isabelle sledgehammer_at THY_FILE LINE [OPTS]"
echo ""
echo "Examples:"
echo "  $ISABELLE_HOME/bin/isabelle find_thms_at Foo.thy 42 '\"_ + _ = _ + _\"'"
echo "  $ISABELLE_HOME/bin/isabelle proof_state_at Foo.thy 42"
echo "  $ISABELLE_HOME/bin/isabelle proof_state_at -T Foo.thy 42"
echo "  $ISABELLE_HOME/bin/isabelle sledgehammer_at Foo.thy 17"
echo "  $ISABELLE_HOME/bin/isabelle sledgehammer_at -P 'e cvc5' -T 15 Foo.thy 17"
echo ""
echo "Run --help for all options:"
echo "  $ISABELLE_HOME/bin/isabelle find_thms_at --help"
echo "  $ISABELLE_HOME/bin/isabelle proof_state_at --help"
echo "  $ISABELLE_HOME/bin/isabelle sledgehammer_at --help"
