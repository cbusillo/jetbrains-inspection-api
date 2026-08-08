#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

java_major() {
  local java_bin="$1/bin/java"
  if [ ! -x "$java_bin" ]; then
    return 1
  fi
  local major
  major=$("$java_bin" -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')
  if [ "$major" = "1" ]; then
    major=$("$java_bin" -version 2>&1 | awk -F'[".]' '/version/ {print $3; exit}')
  fi
  echo "$major"
}

required_java_major() {
  local plugin_version
  plugin_version=$(sed -n 's/^pluginVersion=//p' gradle.properties)
  if [[ "$plugin_version" == *-canary.* ]]; then
    echo 25
  else
    echo 21
  fi
}

resolve_java_home() {
  local candidate required_major override_name override_value
  required_major=$(required_java_major)
  override_name="JAVA_HOME_$required_major"
  override_value="${!override_name:-}"

  if [ -n "$override_value" ]; then
    if [ "$(java_major "$override_value")" = "$required_major" ]; then
      echo "$override_value"
      return 0
    fi
    echo "ERROR: $override_name is set but not Java $required_major." >&2
    return 1
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME")" = "$required_major" ]; then
    echo "$JAVA_HOME"
    return 0
  fi
  if [[ "$OSTYPE" == "darwin"* ]]; then
    candidate=$(/usr/libexec/java_home -v "$required_major" 2>/dev/null || true)
    if [ -n "$candidate" ] && [ "$(java_major "$candidate")" = "$required_major" ]; then
      echo "$candidate"
      return 0
    fi
  else
    for candidate in "/usr/lib/jvm/java-$required_major"-* "/usr/lib/jvm/java-$required_major" "/usr/lib/jvm/jdk-$required_major"*; do
      if [ -d "$candidate" ] && [ "$(java_major "$candidate")" = "$required_major" ]; then
        echo "$candidate"
        return 0
      fi
    done
  fi
  return 1
}

JAVA_HOME=$(resolve_java_home) || {
  required_major=$(required_java_major)
  echo "ERROR: Java $required_major not found. Set JAVA_HOME_$required_major." >&2
  exit 1
}
export JAVA_HOME

./gradlew buildPlugin verifyPluginStructure verifyPlugin
