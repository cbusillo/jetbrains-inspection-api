#!/usr/bin/env bash

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARCHIVE=""
TAG="${GITHUB_REF_NAME:-}"
EXPECTED_SHA256=""
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
    --expected-sha256)
      shift
      EXPECTED_SHA256="${1:-}"
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

if ! [[ "$EXPECTED_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "ERROR: --expected-sha256 must be the lowercase SHA-256 of the verified Stable artifact." >&2
  exit 1
fi
ACTUAL_SHA256=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "ERROR: Stable artifact SHA-256 does not match the independently verified artifact." >&2
  exit 1
fi

if [ -z "${PUBLISH_TOKEN:-}" ]; then
  echo "ERROR: PUBLISH_TOKEN is not available." >&2
  exit 1
fi

curl \
  --fail-with-body \
  --silent \
  --show-error \
  --header "Authorization: Bearer $PUBLISH_TOKEN" \
  --form "xmlId=com.shiny.inspection.api" \
  --form "file=@$ARCHIVE" \
  https://plugins.jetbrains.com/api/updates/upload
