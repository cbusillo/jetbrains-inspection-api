#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

assert_lanes() {
  local expected="$1"
  shift
  local actual
  actual=$(printf '%s\n' "$@" | ./scripts/changed-file-lanes.sh | tr '\n' ' ')
  if [ "$actual" != "$expected" ]; then
    echo "FAIL: changed files [$*] selected lanes [$actual], expected [$expected]" >&2
    exit 1
  fi
}

assert_lanes "" README.md docs/guide/setup.md LICENSE
assert_lanes "contracts " .github/workflows/release.yml
assert_lanes "contracts " scripts/release.sh README.md
assert_lanes "contracts gradle " .github/workflows/ci.yml
assert_lanes "contracts gradle " scripts/commit-gate.sh
assert_lanes "contracts gradle " README.md src/main/kotlin/com/shiny/inspectionmcp/InspectionHandler.kt
assert_lanes "contracts gradle " build.gradle.kts
assert_lanes "contracts gradle " gradle.properties
assert_lanes "contracts gradle " test-fixtures/verdicts/status-green-clean.json
assert_lanes "contracts gradle " "src/main/resources/notes.md.kt"
assert_lanes "contracts gradle " ""

if [ "$(./scripts/changed-file-lanes.sh </dev/null | tr '\n' ' ')" != "contracts gradle " ]; then
  echo "FAIL: an unknown change set must select every lane" >&2
  exit 1
fi

echo "Changed-file lane tests passed."
