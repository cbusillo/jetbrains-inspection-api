#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

grep -q -- '--product NAME' scripts/dogfood-red-lane-smoke.sh
grep -q -- 'inspection-red-lane-pycharm' scripts/dogfood-red-lane-smoke.sh
grep -q -- 'inspection-red-lane-webstorm' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--scope whole_project' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--profile RedLane' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--ide-channel' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--ide-version' scripts/dogfood-red-lane-smoke.sh

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
	grep -q '"duplicate": "first"' "$fixture_source"
	grep -q '"duplicate": "second"' "$fixture_source"
	test -f test-fixtures/inspection-red-lane-pycharm/pyproject.toml
	grep -q 'PyDictDuplicateKeysInspection' "$fixture_profile"
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
import os
import sys
from pathlib import Path

repo = ""
ide = ""
ide_channel = ""
ide_version = ""
args = sys.argv[1:]
while args:
    arg = args.pop(0)
    if arg == "--repo" and args:
        repo = args.pop(0)
    elif arg == "--ide" and args:
        ide = args.pop(0)
    elif arg == "--ide-channel" and args:
        ide_channel = args.pop(0)
    elif arg == "--ide-version" and args:
        ide_version = args.pop(0)

fixtures = [
    ("IntelliJ IDEA", "src/main/java/com/example/redlane/DefinitelyRed.java", "redLaneField"),
    ("PyCharm", "src/definitely_red.py", "duplicate"),
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

stub_bucket = os.environ.get("JB_INSPECT_STUB_BUCKET", "")
if stub_bucket:
    retry = os.environ.get("JB_INSPECT_STUB_RETRY", "false") == "true"
    print(json.dumps({
        "status": "no_results",
        "verdict": "UNKNOWN",
        "verdict_reason": "no_results",
        "total_problems": 0,
        "cleanup": {"status": "closed"},
        "agent_result": {
            "bucket": stub_bucket,
            "retry_policy": {"retry": retry, "max_attempts": 1 if retry else 0},
            "agent_report": "Stubbed unknown result",
        },
    }))
    sys.exit(1)

print(json.dumps({
    "status": "findings",
    "verdict": "RED",
    "verdict_reason": "actionable_findings",
    "verdict_message": "Inspection worked and returned actionable findings.",
    "total_problems": 1,
    "problems_shown": 1,
    "clean": False,
    "cleanup": {"status": "closed"},
    "agent_result": {
        "bucket": "actionable_findings",
        "retry_policy": {"retry": False, "max_attempts": 0},
        "agent_report": "Inspection worked and returned actionable findings.",
    },
    "ide_selection": {"channel": ide_channel or None, "version": ide_version or None},
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
		--ide-channel eap \
		--ide-version 2026.2 \
		--json-out "$json_out"

	jq -e \
		--arg product "$product" \
		--arg expected_ide "$expected_ide" \
		--arg expected_marker "$expected_marker" '
    .status == "ok" and
    .bucket == "red_confirmed" and
    .product == $product and
    .ide == $expected_ide and
    .ide_channel == "eap" and
    .ide_version == "2026.2" and
    .verdict == "RED" and
    .total_problems == 1 and
    .cleanup.status == "closed" and
    .agent_result.bucket == "actionable_findings" and
    .payload.agent_result.bucket == "actionable_findings" and
    (.payload.problems[0].description | contains($expected_marker))
  ' "$json_out" >/dev/null
}

run_unknown_case() {
	local product=$1
	local stub_bucket=$2
	local stub_retry=$3
	local expected_bucket=$4
	local json_out="$TMP_DIR/$product-$stub_bucket-report.json"

	if JB_INSPECT_STUB_BUCKET="$stub_bucket" JB_INSPECT_STUB_RETRY="$stub_retry" \
		./scripts/dogfood-red-lane-smoke.sh \
		--product "$product" \
		--helper "$HELPER" \
		--work-root "$TMP_DIR/work" \
		--json-out "$json_out"; then
		echo "expected unknown red-lane smoke case to fail: $product $stub_bucket" >&2
		return 1
	fi

	jq -e \
		--arg expected_bucket "$expected_bucket" \
		--arg stub_bucket "$stub_bucket" \
		--argjson stub_retry "$stub_retry" '
    .status == "failed" and
    .bucket == $expected_bucket and
    .verdict == "UNKNOWN" and
    .total_problems == 0 and
    .cleanup.status == "closed" and
    .agent_result.bucket == $stub_bucket and
    .agent_result.retry_policy.retry == $stub_retry
  ' "$json_out" >/dev/null
}

assert_fixture_intellij
assert_fixture_pycharm
assert_fixture_webstorm

run_case intellij "IntelliJ IDEA" redLaneField
run_case pycharm PyCharm duplicate
run_case webstorm WebStorm duplicate
run_unknown_case pycharm capture_not_ready true red_unknown_retryable:capture_not_ready
run_unknown_case pycharm tool_bug false red_unknown_terminal:tool_bug

echo "red-lane smoke script contract passed"
