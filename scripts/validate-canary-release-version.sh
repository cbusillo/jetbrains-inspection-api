#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
REPO="$ROOT"

usage() {
  cat <<'USAGE'
Usage: ./scripts/validate-canary-release-version.sh --tag canary/vX.Y.Z-canary.N --channel canary [--repo PATH]

Validates the isolated canary tag, plugin versions, unchanged Stable plugin ID,
and required non-default Marketplace channel. Prints X.Y.Z-canary.N on success.
USAGE
}

TAG="${GITHUB_REF_NAME:-}"
CHANNEL="${MARKETPLACE_CHANNEL:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --tag requires canary/vX.Y.Z-canary.N." >&2
        exit 1
      fi
      TAG="$1"
      ;;
    --channel)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --channel requires canary." >&2
        exit 1
      fi
      CHANNEL="$1"
      ;;
    --repo)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --repo requires a repository path." >&2
        exit 1
      fi
      REPO="$1"
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

cd "$REPO"

if ! [[ "$TAG" =~ ^canary/v([0-9]+\.[0-9]+\.[0-9]+)-canary\.([1-9][0-9]*)$ ]]; then
  echo "ERROR: Canary tag must be in canary/vX.Y.Z-canary.N format with N >= 1 (got '${TAG:-<empty>}')." >&2
  exit 1
fi

BASE_VERSION="${BASH_REMATCH[1]}"
CANARY_NUMBER="${BASH_REMATCH[2]}"

if [ "$CHANNEL" != "canary" ]; then
  echo "ERROR: Canary publication requires the explicit Marketplace channel 'canary' (got '${CHANNEL:-<empty>}')." >&2
  exit 1
fi

VERSION="$BASE_VERSION-canary.$CANARY_NUMBER"

python3 - "$VERSION" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

expected = sys.argv[1]

properties = Path("gradle.properties").read_text(encoding="utf-8")
property_match = re.search(r"^\s*pluginVersion\s*=\s*(.+?)\s*$", properties, re.M)
if not property_match:
    raise SystemExit("ERROR: pluginVersion not found in gradle.properties")
property_version = property_match.group(1).strip()

plugin_xml = Path("src/main/resources/META-INF/plugin.xml").read_text(encoding="utf-8")
try:
    plugin_root = ET.fromstring(plugin_xml)
except ET.ParseError as error:
    raise SystemExit(f"ERROR: plugin.xml is not valid XML: {error}") from error
xml_version = (plugin_root.findtext("version") or "").strip()
plugin_id = (plugin_root.findtext("id") or "").strip()
if not xml_version:
    raise SystemExit("ERROR: <version> not found in plugin.xml")
if not plugin_id:
    raise SystemExit("ERROR: <id> not found in plugin.xml")

if property_version != expected:
    raise SystemExit(
        f"ERROR: Canary tag version {expected} does not match pluginVersion {property_version}."
    )
if xml_version != expected:
    raise SystemExit(
        f"ERROR: Canary tag version {expected} does not match plugin.xml version {xml_version}."
    )
if plugin_id != "com.shiny.inspection.api":
    raise SystemExit(
        f"ERROR: Canary must preserve the Stable plugin ID com.shiny.inspection.api (got {plugin_id})."
    )
PY

printf '%s\n' "$VERSION"
