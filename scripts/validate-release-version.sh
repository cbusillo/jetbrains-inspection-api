#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

usage() {
  cat <<'USAGE'
Usage: ./scripts/validate-release-version.sh --tag vX.Y.Z

Validates that the release tag, gradle.properties pluginVersion, and plugin.xml
version are identical. Prints the normalized X.Y.Z version on success.
USAGE
}

TAG="${GITHUB_REF_NAME:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --tag requires vX.Y.Z." >&2
        exit 1
      fi
      TAG="$1"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if ! [[ "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "ERROR: Release tag must be in vX.Y.Z format (got '${TAG:-<empty>}')." >&2
  exit 1
fi

VERSION="${BASH_REMATCH[1]}"

python3 - "$VERSION" <<'PY'
from pathlib import Path
import re
import sys

expected = sys.argv[1]

properties = Path("gradle.properties").read_text(encoding="utf-8")
property_match = re.search(r"^\s*pluginVersion\s*=\s*(.+?)\s*$", properties, re.M)
if not property_match:
    raise SystemExit("ERROR: pluginVersion not found in gradle.properties")
property_version = property_match.group(1).strip()

plugin_xml = Path("src/main/resources/META-INF/plugin.xml").read_text(encoding="utf-8")
xml_match = re.search(r"<version>\s*([^<]+?)\s*</version>", plugin_xml)
if not xml_match:
    raise SystemExit("ERROR: <version> not found in plugin.xml")
xml_version = xml_match.group(1).strip()

if property_version != expected:
    raise SystemExit(
        f"ERROR: Release tag version {expected} does not match pluginVersion {property_version}."
    )
if xml_version != expected:
    raise SystemExit(
        f"ERROR: Release tag version {expected} does not match plugin.xml version {xml_version}."
    )
PY

printf '%s\n' "$VERSION"
