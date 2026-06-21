#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

grep -q -- '--product NAME' scripts/dogfood-red-lane-smoke.sh
grep -q -- 'inspection-red-lane-pycharm' scripts/dogfood-red-lane-smoke.sh
grep -q -- 'inspection-red-lane-webstorm' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--scope whole_project' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--profile RedLane' scripts/dogfood-red-lane-smoke.sh

assert_fixture_intellij() {
	local fixture_source="test-fixtures/inspection-red-lane/src/main/java/com/example/redlane/DefinitelyRed.java"
	local fixture_profile="test-fixtures/inspection-red-lane/.idea/inspectionProfiles/RedLane.xml"

	grep -q 'INTENTIONAL RED-LANE FIXTURE' "$fixture_source"
	grep -q 'private String redLaneField' "$fixture_source"
	grep -q 'redLaneField must stay non-final' "$fixture_source"
	grep -q 'UnusedDeclaration' "$fixture_profile"
}

assert_fixture_pycharm() {
	local fixture_source="test-fixtures/inspection-red-lane-pycharm/src/definitely_red.py"
	local fixture_profile="test-fixtures/inspection-red-lane-pycharm/.idea/inspectionProfiles/RedLane.xml"

	grep -q 'INTENTIONAL RED-LANE FIXTURE' "$fixture_source"
	grep -q 'definitely_missing_symbol()' "$fixture_source"
	test -f test-fixtures/inspection-red-lane-pycharm/pyproject.toml
	grep -q 'PyUnresolvedReferencesInspection' "$fixture_profile"
}

assert_fixture_webstorm() {
	local fixture_source="test-fixtures/inspection-red-lane-webstorm/src/definitely-red.json"
	local fixture_profile="test-fixtures/inspection-red-lane-webstorm/.idea/inspectionProfiles/RedLane.xml"

	grep -q 'INTENTIONAL RED-LANE FIXTURE' "$fixture_source"
	grep -q '"duplicate": "first"' "$fixture_source"
	grep -q '"duplicate": "second"' "$fixture_source"
	test -f test-fixtures/inspection-red-lane-webstorm/package.json
	grep -q 'JsonDuplicatePropertyKeys' "$fixture_profile"
	grep -q 'JsonStandardCompliance' "$fixture_profile"
}

TMP_DIR=$(mktemp -d)
cleanup() {
	rm -rf "$TMP_DIR"
}
trap cleanup EXIT

HELPER="$TMP_DIR/jb-inspect.py"
cat >"$HELPER" <<'STUB'
#!/usr/bin/env python3
import json
import sys
from pathlib import Path

repo = ""
ide = ""
args = sys.argv[1:]
while args:
    arg = args.pop(0)
    if arg == "--repo" and args:
        repo = args.pop(0)
    elif arg == "--ide" and args:
        ide = args.pop(0)

fixtures = [
    ("IntelliJ IDEA", "src/main/java/com/example/redlane/DefinitelyRed.java", "redLaneField"),
    ("PyCharm", "src/definitely_red.py", "definitely_missing_symbol"),
    ("WebStorm", "src/definitely-red.json", "duplicate"),
]

selected = None
for expected_ide, rel_path, marker in fixtures:
    path = Path(repo) / rel_path
    if path.exists():
        selected = (expected_ide, path, marker)
        break

if not repo or selected is None:
    print(json.dumps({"status": "error", "error_reason": "missing_fixture", "repo": repo}))
    sys.exit(3)

expected_ide, fixture, marker = selected
if ide != expected_ide:
    print(json.dumps({"status": "error", "error_reason": "wrong_ide", "expected_ide": expected_ide, "actual_ide": ide}))
    sys.exit(3)

print(json.dumps({
    "status": "findings",
    "verdict": "RED",
    "verdict_reason": "actionable_findings",
    "verdict_message": "Inspection worked and returned actionable findings.",
    "total_problems": 1,
    "problems_shown": 1,
    "clean": False,
    "cleanup": {"status": "closed"},
    "route": {"base_path": repo, "project_name": Path(repo).name, "ide": {"name": ide}},
    "problems": [{"severity": "error", "file": str(fixture), "line": 1, "description": f"Cannot resolve symbol {marker}"}],
}))
STUB
chmod +x "$HELPER"

run_case() {
	local product=$1
	local expected_ide=$2
	local expected_marker=$3
	local json_out="$TMP_DIR/$product-report.json"

	./scripts/dogfood-red-lane-smoke.sh \
		--product "$product" \
		--helper "$HELPER" \
		--work-root "$TMP_DIR/work" \
		--json-out "$json_out"

	jq -e \
		--arg product "$product" \
		--arg expected_ide "$expected_ide" \
		--arg expected_marker "$expected_marker" '
    .status == "ok" and
    .bucket == "red_confirmed" and
    .product == $product and
    .ide == $expected_ide and
    .verdict == "RED" and
    .total_problems == 1 and
    .cleanup.status == "closed" and
    (.payload.problems[0].description | contains($expected_marker))
  ' "$json_out" >/dev/null
}

assert_fixture_intellij
assert_fixture_pycharm
assert_fixture_webstorm

run_case intellij "IntelliJ IDEA" redLaneField
run_case pycharm PyCharm definitely_missing_symbol
run_case webstorm WebStorm duplicate

echo "red-lane smoke script contract passed"
