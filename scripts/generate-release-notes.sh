#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/generate-release-notes.sh --version VERSION --output FILE

Generates GitHub release notes for the current tag from commit subjects since
the previous GitHub release.
USAGE
}

version=""
output=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      version="${2:-}"
      shift 2
      ;;
    --output)
      output="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$version" || -z "$output" ]]; then
  usage
  exit 2
fi

last_release="$(gh release list --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null || true)"
if [[ -n "$last_release" && "$last_release" != "null" ]] && git rev-parse -q "$last_release" >/dev/null; then
  range="${last_release}..HEAD"
else
  range="-30"
fi

commit_summary="$(mktemp)"
fixes="$(mktemp)"
features="$(mktemp)"
maintenance="$(mktemp)"
other_updates="$(mktemp)"
seen_subjects="$(mktemp)"

git log --pretty=format:'%s' --no-merges "$range" > "$commit_summary"

while IFS= read -r subject; do
  [[ -n "$subject" ]] || continue
  if grep -Fxq -- "$subject" "$seen_subjects"; then
    continue
  fi
  printf '%s\n' "$subject" >> "$seen_subjects"
  lower_subject="$(printf '%s' "$subject" | tr '[:upper:]' '[:lower:]')"
  if [[ "$subject" =~ ^(fix|revert)(\(|:) ]]; then
    printf '%s\n' "$subject" >> "$fixes"
  elif [[ "$subject" =~ ^feat(\(|:) ]]; then
    printf '%s\n' "$subject" >> "$features"
  elif [[ "$subject" =~ ^(docs|test|chore|ci|build)(\(|:) ]] \
    || [[ "$lower_subject" == *gradle* ]] \
    || [[ "$lower_subject" == *release* ]]; then
    printf '%s\n' "$subject" >> "$maintenance"
  else
    printf '%s\n' "$subject" >> "$other_updates"
  fi
done < "$commit_summary"

write_section() {
  local title="$1"
  local file="$2"
  if [[ -s "$file" ]]; then
    echo "### $title"
    echo ""
    while IFS= read -r subject; do
      [[ -n "$subject" ]] || continue
      echo "- $subject"
    done < "$file"
    echo ""
  fi
}

{
  echo "## What's Changed"
  echo ""
  echo "This release publishes JetBrains Inspection API $version."
  if [[ -n "$last_release" && "$last_release" != "null" ]]; then
    echo "It includes changes since $last_release."
  fi
  echo ""

  write_section "Fixes" "$fixes"
  write_section "Features" "$features"
  write_section "Maintenance" "$maintenance"
  write_section "Other Updates" "$other_updates"

  echo "## Verification"
  echo ""
  echo "- Commit gate passed in CI."
  echo "- Plugin distribution was built with Gradle."
  echo "- Plugin was published to JetBrains Marketplace."
  echo ""
  echo "## Installation & Setup"
  echo ""
  echo "Download the \`jetbrains-inspection-api-${version}.zip\` file below."
  echo ""
  echo "For installation and setup instructions, see the [README Quick Start Guide](https://github.com/cbusillo/jetbrains-inspection-api#quick-start)."
} > "$output"
