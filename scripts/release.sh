#!/usr/bin/env bash

if [ -n "${ZSH_VERSION:-}" ]; then
  exec /bin/bash "$0" "$@"
fi

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/release.sh [prepare] [--major|--minor|--patch]
                       [--no-push] [--remote <name>] [--yes]
  ./scripts/release.sh tag vX.Y.Z [--no-push] [--remote <name>]

prepare (default): creates release/vX.Y.Z from the current remote default
branch, updates versions, runs release validation, commits, pushes the task
branch, and opens a pull request. It never commits or pushes directly to the
protected default branch and never creates a release tag.

tag: after the release pull request is merged, validates that the clean local
default branch exactly matches the remote default branch and vX.Y.Z matches
pluginVersion and plugin.xml, then creates and pushes the release tag.
USAGE
}

MODE="prepare"
if [ "${1:-}" = "prepare" ] || [ "${1:-}" = "tag" ]; then
  MODE="$1"
  shift
fi

BUMP=""
TAG=""
PUSH=1
REMOTE="origin"
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --major|--minor|--patch)
      if [ "$MODE" != "prepare" ]; then
        echo "ERROR: Version bump flags are only valid in prepare mode." >&2
        exit 1
      fi
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
    --yes)
      if [ "$MODE" != "prepare" ]; then
        echo "ERROR: --yes is only valid in prepare mode." >&2
        exit 1
      fi
      ASSUME_YES=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    v*)
      if [ "$MODE" != "tag" ] || [ -n "$TAG" ]; then
        echo "ERROR: Unexpected release tag argument: $1" >&2
        exit 1
      fi
      TAG="$1"
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

detect_default_branch() {
  local remote="$1"
  local head_ref=""
  local remote_prefix="${remote}/"

  if git remote get-url "$remote" >/dev/null 2>&1; then
    head_ref=$(git symbolic-ref --quiet --short "refs/remotes/$remote/HEAD" 2>/dev/null || true)
    if [ -z "$head_ref" ]; then
      head_ref=$(git remote show "$remote" 2>/dev/null | awk '/HEAD branch/ {print $NF; exit}' || true)
    fi
  fi

  if [ -n "$head_ref" ]; then
    echo "${head_ref#"$remote_prefix"}"
    return 0
  fi

  echo "main"
}

require_remote() {
  if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
    echo "ERROR: Remote '$REMOTE' not found." >&2
    exit 1
  fi
}

require_clean_tree() {
  if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: Working tree is dirty. Commit or remove local changes first." >&2
    exit 1
  fi
}

require_synced_default_branch() {
  local current_branch="$1"
  local default_branch="$2"
  local remote_default="$REMOTE/$default_branch"

  if [ "$current_branch" != "$default_branch" ]; then
    echo "ERROR: Run this command from the local $default_branch branch." >&2
    exit 1
  fi
  if [ "$(git rev-parse HEAD)" != "$(git rev-parse "$remote_default")" ]; then
    echo "ERROR: Local $default_branch must exactly match $remote_default." >&2
    exit 1
  fi
}

read_plugin_version() {
  python3 - <<'PY'
from pathlib import Path
import re

text = Path("gradle.properties").read_text(encoding="utf-8")
match = re.search(r"^\s*pluginVersion\s*=\s*(.+?)\s*$", text, re.M)
if not match:
    raise SystemExit(1)
print(match.group(1).strip())
PY
}

choose_bump_prompt() {
  local reply=""
  local tty="/dev/tty"
  if [ -r "$tty" ]; then
    read -r -p "Release type (patch/minor/major): " reply <"$tty"
  else
    read -r -p "Release type (patch/minor/major): " reply
  fi
  echo "$reply"
}

choose_bump_osascript() {
  if [[ "$OSTYPE" != "darwin"* ]] || ! command -v osascript >/dev/null 2>&1; then
    return 1
  fi
  osascript <<'APPLESCRIPT'
set picked to missing value
set choices to {"patch", "minor", "major"}
try
  tell application "System Events"
    activate
    set picked to choose from list choices with prompt "Select release type" default items {"patch"} with title "Release"
  end tell
on error
  set frontApp to (path to frontmost application as text)
  tell application frontApp to activate
  set picked to choose from list choices with prompt "Select release type" default items {"patch"} with title "Release"
end try
if picked is false then
  return "cancel"
else if picked is missing value then
  return ""
else
  return item 1 of picked
end if
APPLESCRIPT
}

resolve_bump() {
  if [ -n "$BUMP" ]; then
    return
  fi
  BUMP=$(choose_bump_osascript || true)
  if [ -z "$BUMP" ]; then
    if [ -t 0 ] || [ -r /dev/tty ]; then
      echo "Picker unavailable; please type a release type." >&2
      BUMP=$(choose_bump_prompt || true)
    else
      echo "ERROR: Non-interactive shell. Pass --patch/--minor/--major." >&2
      exit 1
    fi
  fi
  if [ -z "$BUMP" ] || [ "$BUMP" = "cancel" ]; then
    echo "Aborted." >&2
    exit 1
  fi
  case "$BUMP" in
    major|minor|patch) ;;
    *)
      echo "ERROR: Invalid selection: $BUMP" >&2
      exit 1
      ;;
  esac
}

next_version() {
  local current_version="$1"
  local major minor patch
  if ! [[ "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "ERROR: pluginVersion must be in X.Y.Z format (got $current_version)." >&2
    exit 1
  fi
  IFS='.' read -r major minor patch <<< "$current_version"
  case "$BUMP" in
    major)
      major=$((major + 1))
      minor=0
      patch=0
      ;;
    minor)
      minor=$((minor + 1))
      patch=0
      ;;
    patch)
      patch=$((patch + 1))
      ;;
  esac
  echo "${major}.${minor}.${patch}"
}

update_plugin_version() {
  local version="$1"
  python3 - "$version" <<'PY'
from pathlib import Path
import re
import sys

version = sys.argv[1]
properties_path = Path("gradle.properties")
text = properties_path.read_text(encoding="utf-8")
updated, count = re.subn(
    r"^\s*pluginVersion\s*=\s*.+$",
    f"pluginVersion={version}",
    text,
    flags=re.M,
)
if count != 1:
    raise SystemExit("ERROR: Failed to update pluginVersion in gradle.properties")
properties_path.write_text(updated, encoding="utf-8")

plugin_xml_path = Path("src/main/resources/META-INF/plugin.xml")
plugin_xml = plugin_xml_path.read_text(encoding="utf-8")
updated_xml, xml_count = re.subn(
    r"<version>[^<]*</version>",
    f"<version>{version}</version>",
    plugin_xml,
    count=1,
)
if xml_count != 1:
    raise SystemExit("ERROR: Failed to update version in plugin.xml")
plugin_xml_path.write_text(updated_xml, encoding="utf-8")
PY
}

run_release_validation() {
  local version="$1"
  ./scripts/test-all.sh

  if [ "$ASSUME_YES" -ne 1 ]; then
    echo ""
    echo "This will stop any running IDE instance for the automated test."
    read -r -p "Continue with ./scripts/test-automated.sh? [y/N] " reply
    case "$reply" in
      [Yy]*) ;;
      *)
        echo "Aborted before automated IDE test."
        exit 1
        ;;
    esac
  fi

  ./scripts/test-automated.sh
  ./scripts/commit-gate.sh
  ./scripts/release-compatibility-gate.sh
  ./scripts/validate-release-version.sh --tag "v$version" >/dev/null
}

open_release_pr() {
  local branch="$1"
  local version="$2"
  local body_file
  body_file=$(mktemp)
  trap 'rm -f "$body_file"' RETURN
  cat > "$body_file" <<EOF
## Summary

- prepare release v$version
- validate plugin metadata and compatibility gates

## Validation

- \`./scripts/test-all.sh\`
- \`./scripts/test-automated.sh\`
- \`./scripts/commit-gate.sh\`
- \`./scripts/release-compatibility-gate.sh\`
EOF
  gh pr create \
    --base "$DEFAULT_BRANCH" \
    --head "$branch" \
    --title "Release v$version" \
    --body-file "$body_file"
  rm -f "$body_file"
  trap - RETURN
}

prepare_release() {
  local branch current_version new_version release_branch
  branch=$(git branch --show-current)
  require_remote
  require_clean_tree
  git fetch "$REMOTE" "$DEFAULT_BRANCH" --tags
  require_synced_default_branch "$branch" "$DEFAULT_BRANCH"
  if [ "$PUSH" -eq 1 ]; then
    if ! command -v gh >/dev/null 2>&1; then
      echo "ERROR: gh is required to open the release pull request." >&2
      exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
      echo "ERROR: gh is not authenticated; authenticate before preparing a release." >&2
      exit 1
    fi
  fi
  resolve_bump

  current_version=$(read_plugin_version) || {
    echo "ERROR: pluginVersion not found in gradle.properties." >&2
    exit 1
  }
  new_version=$(next_version "$current_version")
  release_branch="release/v${new_version}"

  if git show-ref --verify --quiet "refs/heads/$release_branch" ||
    git ls-remote --exit-code --heads "$REMOTE" "$release_branch" >/dev/null 2>&1; then
    echo "ERROR: Release branch $release_branch already exists." >&2
    exit 1
  fi

  git switch -c "$release_branch" "$REMOTE/$DEFAULT_BRANCH"
  update_plugin_version "$new_version"
  run_release_validation "$new_version"

  git add gradle.properties src/main/resources/META-INF/plugin.xml
  git commit -m "Bump version to ${new_version}"

  if [ "$PUSH" -eq 1 ]; then
    git push -u "$REMOTE" "$release_branch"
    open_release_pr "$release_branch" "$new_version"
  else
    echo "Release branch prepared locally: $release_branch"
    echo "Push it and open a pull request into $DEFAULT_BRANCH before tagging."
  fi

  echo "Release preparation complete for v${new_version}; no tag was created."
}

tag_release() {
  local branch
  if [ -z "$TAG" ]; then
    echo "ERROR: tag mode requires vX.Y.Z." >&2
    exit 1
  fi

  branch=$(git branch --show-current)
  require_remote
  require_clean_tree
  git fetch "$REMOTE" "$DEFAULT_BRANCH" --tags
  require_synced_default_branch "$branch" "$DEFAULT_BRANCH"
  ./scripts/validate-release-version.sh --tag "$TAG" >/dev/null

  if git show-ref --verify --quiet "refs/tags/$TAG" ||
    git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "ERROR: Release tag $TAG already exists." >&2
    exit 1
  fi

  git tag -a "$TAG" -m "Release $TAG"
  if [ "$PUSH" -eq 1 ]; then
    git push "$REMOTE" "$TAG"
    echo "Release tag pushed: $TAG"
  else
    echo "Release tag created locally: $TAG"
  fi
}

require_remote
DEFAULT_BRANCH=$(detect_default_branch "$REMOTE")

case "$MODE" in
  prepare) prepare_release ;;
  tag) tag_release ;;
esac
