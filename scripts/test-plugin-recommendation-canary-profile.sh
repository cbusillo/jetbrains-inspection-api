#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
IDE_HOME=${CANARY_TEST_IDE_HOME:-}

if [ -z "$IDE_HOME" ]; then
  echo "SKIP: Set CANARY_TEST_IDE_HOME to a JetBrains 262 Contents directory."
  exit 0
fi

JDK_HOME="$IDE_HOME/jbr"
if [ -d "$JDK_HOME/Contents/Home" ]; then
  JDK_HOME="$JDK_HOME/Contents/Home"
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
CLASS_OUTPUT="$TEMP_DIR/classes"
CONFIG_DIR="$TEMP_DIR/config"
mkdir -p "$CLASS_OUTPUT" "$CONFIG_DIR"

"$JDK_HOME/bin/javac" \
  --release 25 \
  -cp "$IDE_HOME/lib/*" \
  -d "$CLASS_OUTPUT" \
  "$ROOT/scripts/support/PluginRecommendationCanaryProfileSeeder.java"

WRITE_OUTPUT=$("$JDK_HOME/bin/java" \
  -cp "$CLASS_OUTPUT:$IDE_HOME/lib/*" \
  PluginRecommendationCanaryProfileSeeder write "$CONFIG_DIR")
VERIFY_OUTPUT=$("$JDK_HOME/bin/java" \
  -cp "$CLASS_OUTPUT:$IDE_HOME/lib/*" \
  PluginRecommendationCanaryProfileSeeder verify "$CONFIG_DIR")

[ "$WRITE_OUTPUT" = "$VERIFY_OUTPUT" ] || {
  echo "ERROR: cache verification output changed after reopening the database" >&2
  exit 1
}
printf '%s\n' "$VERIFY_OUTPUT" | rg -q -F 'cache_entries=2'
printf '%s\n' "$VERIFY_OUTPUT" | rg -q -F '*.go=PluginData(pluginIdString=org.jetbrains.plugins.go'
printf '%s\n' "$VERIFY_OUTPUT" | rg -q -F '*.properties=PluginData(pluginIdString=com.intellij.properties'

if "$JDK_HOME/bin/java" \
  -cp "$CLASS_OUTPUT:$IDE_HOME/lib/*" \
  PluginRecommendationCanaryProfileSeeder write "$CONFIG_DIR" >/dev/null 2>&1; then
  echo "ERROR: seeder replaced an existing state database" >&2
  exit 1
fi

echo "Plugin recommendation canary profile seeder test passed."
