#!/usr/bin/env bash

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARCHIVE=""
TAG="${GITHUB_REF_NAME:-}"
CHANNEL="${MARKETPLACE_CHANNEL:-}"

java_major_version() {
  local java_home="$1"
  "$java_home/bin/java" -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }'
}

use_java_21() {
  local candidate=""

  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && \
    [ "$(java_major_version "$JAVA_HOME")" = "21" ]; then
    return
  fi

  if [ -n "${JAVA_HOME_21:-}" ] && [ -x "$JAVA_HOME_21/bin/java" ]; then
    candidate="$JAVA_HOME_21"
  elif [ -x /usr/libexec/java_home ]; then
    candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  fi

  if [ -z "$candidate" ] || [ ! -x "$candidate/bin/java" ] || \
    [ "$(java_major_version "$candidate")" != "21" ]; then
    echo "ERROR: Canary verification requires Java 21. Set JAVA_HOME_21 to a JDK 21 installation." >&2
    exit 1
  fi

  export JAVA_HOME="$candidate"
  export PATH="$JAVA_HOME/bin:$PATH"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --archive)
      shift
      ARCHIVE="${1:-}"
      ;;
    --tag)
      shift
      TAG="${1:-}"
      ;;
    --channel)
      shift
      CHANNEL="${1:-}"
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      exit 1
      ;;
  esac
  shift
done

"$ROOT/scripts/validate-canary-artifact.sh" \
  --archive "$ARCHIVE" \
  --tag "$TAG" \
  --channel "$CHANNEL"

ARCHIVE=$(cd "$(dirname "$ARCHIVE")" && pwd)/$(basename "$ARCHIVE")
VERSION="${TAG#canary/v}"

cd "$ROOT"
use_java_21
rm -rf build/reports/pluginVerifier
./gradlew \
  --no-build-cache \
  --no-daemon \
  verifyPlugin \
  "-PpluginVersion=$VERSION" \
  "-PpluginVerificationArchive=$ARCHIVE"
