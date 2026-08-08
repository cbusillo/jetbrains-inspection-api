#!/usr/bin/env bash

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARCHIVE=""
TAG="${GITHUB_REF_NAME:-}"
CHANNEL="${MARKETPLACE_CHANNEL:-}"

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
rm -rf build/reports/pluginVerifier
./gradlew \
  --no-build-cache \
  --no-daemon \
  verifyPlugin \
  "-PpluginVersion=$VERSION" \
  "-PpluginVerificationArchive=$ARCHIVE"
