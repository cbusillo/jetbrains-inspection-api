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

if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo "ERROR: --archive must identify an existing Stable plugin zip." >&2
  exit 1
fi
if ! [[ "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "ERROR: Stable tag must be in vX.Y.Z format (got '${TAG:-<empty>}')." >&2
  exit 1
fi
VERSION="${BASH_REMATCH[1]}"
if ! [[ "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: --source-sha must be the lowercase 40-character Stable source commit." >&2
  exit 1
fi

EXPECTED_SINCE_BUILD="251"
EXPECTED_UNTIL_BUILD="262.*"
"$ROOT/scripts/validate-marketplace-publication.sh" --version "$VERSION"

EXPECTED_ARCHIVE_NAME="jetbrains-inspection-api-$VERSION.zip"
if [ "$(basename "$ARCHIVE")" != "$EXPECTED_ARCHIVE_NAME" ]; then
  echo "ERROR: Stable archive must be named $EXPECTED_ARCHIVE_NAME." >&2
  exit 1
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
PLUGIN_JAR_PATH="jetbrains-inspection-api/lib/jetbrains-inspection-api-$VERSION.jar"
PLUGIN_JAR_COUNT=$(unzip -Z1 "$ARCHIVE" | awk -v target="$PLUGIN_JAR_PATH" '$0 == target { count++ } END { print count + 0 }')
if [ "$PLUGIN_JAR_COUNT" -ne 1 ]; then
  echo "ERROR: Stable archive must contain exactly one $PLUGIN_JAR_PATH." >&2
  exit 1
fi
unzip -p "$ARCHIVE" "$PLUGIN_JAR_PATH" > "$TEMP_DIR/plugin.jar"

PLUGIN_XML_COUNT=$(unzip -Z1 "$TEMP_DIR/plugin.jar" | awk '$0 == "META-INF/plugin.xml" { count++ } END { print count + 0 }')
if [ "$PLUGIN_XML_COUNT" -ne 1 ]; then
  echo "ERROR: Stable plugin jar must contain exactly one META-INF/plugin.xml." >&2
  exit 1
fi
unzip -p "$TEMP_DIR/plugin.jar" META-INF/plugin.xml > "$TEMP_DIR/plugin.xml"

BUILD_INFO_PATH="com/shiny/inspectionmcp/inspection-build.properties"
BUILD_INFO_COUNT=$(unzip -Z1 "$TEMP_DIR/plugin.jar" | awk -v target="$BUILD_INFO_PATH" '$0 == target { count++ } END { print count + 0 }')
if [ "$BUILD_INFO_COUNT" -ne 1 ]; then
  echo "ERROR: Stable plugin jar must contain exactly one $BUILD_INFO_PATH." >&2
  exit 1
fi
unzip -p "$TEMP_DIR/plugin.jar" "$BUILD_INFO_PATH" > "$TEMP_DIR/inspection-build.properties"

python3 - \
  "$TEMP_DIR/plugin.xml" \
  "$TEMP_DIR/inspection-build.properties" \
  "$VERSION" \
  "$SOURCE_SHA" \
  "$EXPECTED_SINCE_BUILD" \
  "$EXPECTED_UNTIL_BUILD" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

plugin_xml_path = Path(sys.argv[1])
build_info_path = Path(sys.argv[2])
expected_version = sys.argv[3]
expected_source_sha = sys.argv[4]
expected_since_build = sys.argv[5]
expected_until_build = sys.argv[6]

try:
    plugin_root = ET.parse(plugin_xml_path).getroot()
except ET.ParseError as error:
    raise SystemExit(f"ERROR: Stable archive plugin.xml is not valid XML: {error}") from error

plugin_id = (plugin_root.findtext("id") or "").strip()
plugin_version = (plugin_root.findtext("version") or "").strip()
idea_version = plugin_root.find("idea-version")
if plugin_id != "com.shiny.inspection.api":
    raise SystemExit("ERROR: Stable archive does not preserve plugin ID com.shiny.inspection.api.")
if plugin_version != expected_version:
    raise SystemExit(
        f"ERROR: Stable archive version {plugin_version or '<missing>'} "
        f"does not match tag version {expected_version}."
    )
if idea_version is None:
    raise SystemExit("ERROR: Stable archive does not declare an idea-version compatibility range.")
since_build = (idea_version.get("since-build") or "").strip()
until_build = (idea_version.get("until-build") or "").strip()
if since_build != expected_since_build or until_build != expected_until_build:
    raise SystemExit(
        "ERROR: Stable archive compatibility range "
        f"{since_build or '<missing>'}..{until_build or '<missing>'} does not match "
        f"trusted range {expected_since_build}..{expected_until_build}."
    )

build_info = {}
for raw_line in build_info_path.read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line or line.startswith(("#", "!")):
        continue
    key, separator, value = line.partition("=")
    if not separator:
        raise SystemExit("ERROR: Stable archive build provenance is malformed.")
    build_info[key.strip()] = value.strip()

expected_provenance = {
    "plugin.version": expected_version,
    "plugin.build.commit": expected_source_sha,
    "plugin.build.short_commit": expected_source_sha[:12],
    "plugin.build.dirty": "false",
    "plugin.build.fingerprint": f"{expected_source_sha}-clean",
}
for key, expected_value in expected_provenance.items():
    actual_value = build_info.get(key, "")
    if actual_value != expected_value:
        raise SystemExit(
            f"ERROR: Stable archive provenance {key}={actual_value or '<missing>'} "
            f"does not match trusted value {expected_value}."
        )
PY
