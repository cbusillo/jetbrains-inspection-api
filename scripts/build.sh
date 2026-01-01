#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

JAVA_HOME=${JAVA_HOME_21:-${JAVA_HOME:-}}
if [ -z "$JAVA_HOME" ]; then
  if [[ "$OSTYPE" == "darwin"* ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  else
    for dir in /usr/lib/jvm/java-21-* /usr/lib/jvm/java-21 /usr/lib/jvm/jdk-21*; do
      if [ -d "$dir" ]; then
        JAVA_HOME="$dir"
        break
      fi
    done
  fi
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
  echo "ERROR: Java 21 not found. Please set JAVA_HOME_21." >&2
  exit 1
fi

export JAVA_HOME

./gradlew test
./gradlew buildPlugin "$@"
