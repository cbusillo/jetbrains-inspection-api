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

if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo "ERROR: --archive must identify an existing canary plugin zip." >&2
  exit 1
fi
if ! [[ "$TAG" =~ ^canary/v([0-9]+\.[0-9]+\.[0-9]+)-canary\.([1-9][0-9]*)$ ]]; then
  echo "ERROR: Canary tag must be in canary/vX.Y.Z-canary.N format (got '${TAG:-<empty>}')." >&2
  exit 1
fi

VERSION="${BASH_REMATCH[1]}-canary.${BASH_REMATCH[2]}"
EXPECTED_SINCE_BUILD="262"
EXPECTED_UNTIL_BUILD="262.*"
"$ROOT/scripts/validate-marketplace-publication.sh" \
  --version "$VERSION" \
  --channel "$CHANNEL"

EXPECTED_ARCHIVE_NAME="jetbrains-inspection-api-$VERSION.zip"
if [ "$(basename "$ARCHIVE")" != "$EXPECTED_ARCHIVE_NAME" ]; then
  echo "ERROR: Canary archive must be named $EXPECTED_ARCHIVE_NAME." >&2
  exit 1
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
PLUGIN_JAR_PATH="jetbrains-inspection-api/lib/jetbrains-inspection-api-$VERSION.jar"
PLUGIN_JAR_COUNT=$(unzip -Z1 "$ARCHIVE" | awk -v target="$PLUGIN_JAR_PATH" '$0 == target { count++ } END { print count + 0 }')
if [ "$PLUGIN_JAR_COUNT" -ne 1 ]; then
  echo "ERROR: Canary archive must contain exactly one $PLUGIN_JAR_PATH." >&2
  exit 1
fi
if ! unzip -p "$ARCHIVE" "$PLUGIN_JAR_PATH" > "$TEMP_DIR/plugin.jar"; then
  echo "ERROR: Canary archive does not contain $PLUGIN_JAR_PATH." >&2
  exit 1
fi
PLUGIN_XML_COUNT=$(unzip -Z1 "$TEMP_DIR/plugin.jar" | awk '$0 == "META-INF/plugin.xml" { count++ } END { print count + 0 }')
if [ "$PLUGIN_XML_COUNT" -ne 1 ]; then
  echo "ERROR: Canary plugin jar must contain exactly one META-INF/plugin.xml." >&2
  exit 1
fi
if ! unzip -p "$TEMP_DIR/plugin.jar" META-INF/plugin.xml > "$TEMP_DIR/plugin.xml"; then
  echo "ERROR: Canary plugin jar does not contain META-INF/plugin.xml." >&2
  exit 1
fi

python3 - \
  "$TEMP_DIR/plugin.xml" \
  "$VERSION" \
  "$EXPECTED_SINCE_BUILD" \
  "$EXPECTED_UNTIL_BUILD" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

plugin_xml_path = Path(sys.argv[1])
expected_version = sys.argv[2]
expected_since_build = sys.argv[3]
expected_until_build = sys.argv[4]

try:
    plugin_root = ET.parse(plugin_xml_path).getroot()
except ET.ParseError as error:
    raise SystemExit(f"ERROR: Canary archive plugin.xml is not valid XML: {error}") from error
plugin_id = (plugin_root.findtext("id") or "").strip()
plugin_version = (plugin_root.findtext("version") or "").strip()
idea_version = plugin_root.find("idea-version")
if plugin_id != "com.shiny.inspection.api":
    raise SystemExit("ERROR: Canary archive does not preserve plugin ID com.shiny.inspection.api.")
if plugin_version != expected_version:
    actual = plugin_version or "<missing>"
    raise SystemExit(
        f"ERROR: Canary archive version {actual} does not match tag version {expected_version}."
    )
if idea_version is None:
    raise SystemExit("ERROR: Canary archive does not declare an idea-version compatibility range.")
since_build = (idea_version.get("since-build") or "").strip()
until_build = (idea_version.get("until-build") or "").strip()
if since_build != expected_since_build or until_build != expected_until_build:
    raise SystemExit(
        "ERROR: Canary archive compatibility range "
        f"{since_build or '<missing>'}..{until_build or '<missing>'} does not match "
        f"trusted range {expected_since_build}..{expected_until_build}."
    )
PY
