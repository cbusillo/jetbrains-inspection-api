#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

usage() {
  cat <<'USAGE'
Usage: ./scripts/release.sh (--major|--minor|--patch)
                           [--no-push] [--remote <name>]
                           [--yes] [--allow-non-default-branch]

Bumps pluginVersion, runs tests + commit gate, commits, and tags vX.Y.Z.
By default it pushes commit + tag and enforces the default branch.
Use --no-push to keep it local, --allow-non-default-branch to bypass branch check,
and --yes to skip the IDE restart confirmation.
USAGE
}

if [ $# -lt 1 ]; then
  usage
  exit 1
fi

BUMP=""
PUSH=1
REMOTE="origin"
ALLOW_NON_DEFAULT_BRANCH=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --major|--minor|--patch)
      if [ -n "$BUMP" ]; then
        echo "ERROR: Only one of --major/--minor/--patch is allowed." >&2
        exit 1
      fi
      BUMP="${1#--}"
      ;;
    --no-push)
      PUSH=0
      ;;
    --remote)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --remote requires a name." >&2
        exit 1
      fi
      REMOTE="$1"
      ;;
    --allow-non-default-branch)
      ALLOW_NON_DEFAULT_BRANCH=1
      ;;
    --yes)
      ASSUME_YES=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

if [ -z "$BUMP" ]; then
  echo "ERROR: Missing bump type (--major/--minor/--patch)." >&2
  usage
  exit 1
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if [ -z "$BRANCH" ]; then
  echo "ERROR: Unable to determine current branch." >&2
  exit 1
fi

DEFAULT_BRANCH=""
if git remote get-url "$REMOTE" >/dev/null 2>&1; then
  DEFAULT_BRANCH=$(git symbolic-ref --quiet --short "refs/remotes/$REMOTE/HEAD" 2>/dev/null | sed "s#^$REMOTE/##")
fi
if [ -z "$DEFAULT_BRANCH" ]; then
  DEFAULT_BRANCH="main"
fi

if [ "$ALLOW_NON_DEFAULT_BRANCH" -ne 1 ] && [ "$BRANCH" != "$DEFAULT_BRANCH" ]; then
  echo "ERROR: Release must be run from $DEFAULT_BRANCH. Use --allow-non-default-branch to override." >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: Working tree is dirty. Commit or stash changes first." >&2
  exit 1
fi

CURRENT_VERSION=$(python3 - <<'PY'
from pathlib import Path
import re

text = Path("gradle.properties").read_text(encoding="utf-8")
match = re.search(r"^\s*pluginVersion\s*=\s*(.+?)\s*$", text, re.M)
if not match:
    raise SystemExit(1)
print(match.group(1).strip())
PY
)

if ! [[ "$CURRENT_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "ERROR: pluginVersion must be in X.Y.Z format (got $CURRENT_VERSION)." >&2
  exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"
case "$BUMP" in
  major)
    MAJOR=$((MAJOR + 1))
    MINOR=0
    PATCH=0
    ;;
  minor)
    MINOR=$((MINOR + 1))
    PATCH=0
    ;;
  patch)
    PATCH=$((PATCH + 1))
    ;;
esac

NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"

python3 - <<'PY' "$NEW_VERSION"
from pathlib import Path
import re
import sys

new_version = sys.argv[1]
path = Path("gradle.properties")
text = path.read_text(encoding="utf-8")
updated, count = re.subn(r"^\s*pluginVersion\s*=\s*.+$", f"pluginVersion={new_version}", text, flags=re.M)
if count != 1:
    raise SystemExit("ERROR: Failed to update pluginVersion in gradle.properties")
path.write_text(updated, encoding="utf-8")
PY

./scripts/test-all.sh

if [ "$ASSUME_YES" -ne 1 ]; then
  echo ""
  echo "This will stop any running IDE instance for the automated test."
  read -r -p "Continue with ./scripts/test-automated.sh? [y/N] " reply
  case "$reply" in
    [Yy]*)
      ;;
    *)
      echo "Aborted before automated IDE test."
      exit 1
      ;;
  esac
fi

./scripts/test-automated.sh
./scripts/commit-gate.sh

git add gradle.properties src/main/resources/META-INF/plugin.xml
git commit -m "Bump version to ${NEW_VERSION}"
git tag "v${NEW_VERSION}"

if [ "$PUSH" -eq 1 ]; then
  if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
    echo "ERROR: Remote '$REMOTE' not found." >&2
    exit 1
  fi
  git push "$REMOTE" HEAD
  git push "$REMOTE" "v${NEW_VERSION}"
fi

echo "Release prepared: v${NEW_VERSION}"
