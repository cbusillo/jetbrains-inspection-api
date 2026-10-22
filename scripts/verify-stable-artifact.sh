#!/usr/bin/env bash

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARCHIVE=""
TAG="${GITHUB_REF_NAME:-}"
SOURCE_SHA="${STABLE_SOURCE_SHA:-}"

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
    --source-sha)
      shift
      SOURCE_SHA="${1:-}"
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      exit 1
      ;;
  esac
  shift
done

"$ROOT/scripts/validate-stable-artifact.sh" \
  --archive "$ARCHIVE" \
  --tag "$TAG" \
  --source-sha "$SOURCE_SHA"

ARCHIVE=$(cd "$(dirname "$ARCHIVE")" && pwd)/$(basename "$ARCHIVE")
VERSION="${TAG#v}"

cd "$ROOT"
source "$ROOT/scripts/java21.sh"
use_java21
rm -rf build/reports/pluginVerifier
./gradlew \
  --no-build-cache \
  --no-daemon \
  verifyPlugin \
  -PinspectionBuild=stable \
  "-PpluginVersion=$VERSION" \
  "-PpluginVerificationArchive=$ARCHIVE"
