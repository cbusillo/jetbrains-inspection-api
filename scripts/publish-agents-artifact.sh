#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARCHIVE=""
VERSION=""
SOURCE_SHA=""
EXPECTED_SHA256=""
while [ $# -gt 0 ]; do
  case "$1" in
    --archive) shift; ARCHIVE="${1:-}" ;;
    --version) shift; VERSION="${1:-}" ;;
    --source-sha) shift; SOURCE_SHA="${1:-}" ;;
    --expected-sha256) shift; EXPECTED_SHA256="${1:-}" ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
  shift
done

uv run "$ROOT/scripts/agents-release.py" verify \
  --archive "$ARCHIVE" --version "$VERSION" --source-sha "$SOURCE_SHA" \
  --expected-sha256 "$EXPECTED_SHA256"
if [ -z "${PUBLISH_TOKEN:-}" ]; then
  echo "ERROR: PUBLISH_TOKEN is not available." >&2
  exit 1
fi

printf 'Authorization: Bearer %s\n' "$PUBLISH_TOKEN" | curl \
  --fail-with-body --silent --show-error --header @- \
  --form "xmlId=com.shiny.inspection.api" --form "file=@$ARCHIVE" --form "channel=agents" \
  https://plugins.jetbrains.com/api/updates/upload
