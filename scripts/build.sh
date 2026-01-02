#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

JAVA_HOME=${JAVA_HOME_21:-${JAVA_HOME:-}}
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

is_java21() {
  [ "$(java_major "$1")" = "21" ]
}

resolve_java_home() {
  local candidate

  if [ -n "${JAVA_HOME_21:-}" ]; then
    if is_java21 "$JAVA_HOME_21"; then
      echo "$JAVA_HOME_21"
      return 0
    fi
    echo "ERROR: JAVA_HOME_21 is set but not Java 21." >&2
    return 1
  fi

  if [ -n "${JAVA_HOME:-}" ] && is_java21 "$JAVA_HOME"; then
    echo "$JAVA_HOME"
    return 0
  fi

  if [[ "$OSTYPE" == "darwin"* ]]; then
    candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    if [ -n "$candidate" ] && is_java21 "$candidate"; then
      echo "$candidate"
      return 0
    fi
  else
    for candidate in /usr/lib/jvm/java-21-* /usr/lib/jvm/java-21 /usr/lib/jvm/jdk-21*; do
      if [ -d "$candidate" ] && is_java21 "$candidate"; then
        echo "$candidate"
        return 0
      fi
    done
  fi

  return 1
}

JAVA_HOME=$(resolve_java_home) || {
  echo "ERROR: Java 21 not found. Please set JAVA_HOME_21." >&2
  exit 1
}

export JAVA_HOME

./gradlew test
./gradlew buildPlugin "$@"
