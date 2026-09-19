#!/usr/bin/env bash

set -euo pipefail

needs_contracts=0
needs_gradle=0
saw_path=0

while IFS= read -r changed_path || [ -n "$changed_path" ]; do
  [ -n "$changed_path" ] || continue
  saw_path=1
  case "$changed_path" in
    *.md|LICENSE|docs/*)
      ;;
    scripts/commit-gate.sh|scripts/changed-file-lanes.sh|.github/workflows/ci.yml)
      needs_contracts=1
      needs_gradle=1
      ;;
    .github/*|scripts/*)
      needs_contracts=1
      ;;
    *)
      needs_contracts=1
      needs_gradle=1
      ;;
  esac
done

if [ "$saw_path" -eq 0 ]; then
  needs_contracts=1
  needs_gradle=1
fi

[ "$needs_contracts" -eq 0 ] || echo contracts
[ "$needs_gradle" -eq 0 ] || echo gradle
