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

if [ -z "${PUBLISH_TOKEN:-}" ]; then
  echo "ERROR: CANARY_PUBLISH_TOKEN is not available as PUBLISH_TOKEN." >&2
  exit 1
fi

curl \
  --fail-with-body \
  --silent \
  --show-error \
  --header "Authorization: Bearer $PUBLISH_TOKEN" \
  --form "xmlId=com.shiny.inspection.api" \
  --form "file=@$ARCHIVE" \
  --form "channel=canary" \
  https://plugins.jetbrains.com/api/updates/upload
