#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

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

if "--profile" not in sys.argv:
    repo = sys.argv[sys.argv.index("--repo") + 1]
    cleanup = "not_needed" if "--no-open" in sys.argv else os.environ.get("JB_INSPECT_STUB_CLEANUP", "closed")
    print(json.dumps({"status": "clean", "clean": True, "cleanup": {"status": cleanup},
                      "native_detail": "x" * int(os.environ.get("JB_INSPECT_STUB_SIZE", "0")),
                      "route": {"ide": {"plugin_build_fingerprint": "native-matrix-clean"}},
                      "prepared": {"lease": {"opened_by_helper": "--no-open" not in sys.argv}}}))
    sys.exit(0)
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
        "cleanup": {"status": os.environ.get("JB_INSPECT_STUB_CLEANUP", "closed")},
        "agent_result": {
            "bucket": stub_bucket,
            "retry_policy": {"retry": retry, "max_attempts": 1 if retry else 0},
            "agent_report": "Stubbed unknown result",
        },
        "open_attempts": [{"method": "running_ide", "accepted": False, "reason": "project_open_blocked"}],
        "route_diagnostic": {"reason": "target_ide_running_without_target_project"},
        "blocked_diagnostic": {"reason": "jetbrains_project_open_blocked"},
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
    "cleanup": {"status": os.environ.get("JB_INSPECT_STUB_CLEANUP", "closed")},
    "agent_result": {
        "bucket": "actionable_findings",
        "retry_policy": {"retry": False, "max_attempts": 0},
        "agent_report": "Inspection worked and returned actionable findings.",
    },
    "open_attempts": [{"method": "running_ide", "accepted": True, "endpoint_status": "opening"}],
    "ide_selection": {"channel": ide_channel or None, "version": ide_version or None},
    "route": {"base_path": repo, "project_name": Path(repo).name, "ide": {"name": ide, "plugin_version": "test-1.0.0", "plugin_build_fingerprint": "test-clean"}},
    "problems": [{"severity": "error", "file": str(fixture), "line": 1, "description": f"Cannot resolve symbol {marker}"}],
}))
STUB
chmod +x "$HELPER"
mkdir -p "$TMP_DIR/bin"
cat >"$TMP_DIR/bin/uv" <<'UV'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$2" == */smoke-worktree.py ]]; then
    shift 2
    command=$1
    shift
    source=""; parent=""; slug=""; receipt=""; payload=""; out=""
    while [ $# -gt 0 ]; do
        case "$1" in
        --source) source=$2;; --slug) slug=$2;; --parent) parent=$2;;
        --receipt) receipt=$2;; --payload) payload=$2;; --out) out=$2;; --helper) :;;
        *) exit 2;;
        esac
        shift 2
    done
    if [ "$command" = create ]; then
        project="$parent/$slug"
        mkdir -p "$project/test-fixtures"
        cp -R "$source/test-fixtures"/. "$project/test-fixtures"/
        jq -n --arg root "$project" '{root:$root}' > "$receipt"
        printf '%s\n' "$project"
    else
        if [ "${JB_RETIRE_STUB_MISSING:-}" = 1 ]; then exit 1; fi
        project=$(jq -r .root "$receipt")
        if jq -e '.cleanup.status == "closed"' "$payload" >/dev/null; then
            rm -rf "$project"
            printf '%s\n' '{"status":"removed"}' > "$out"
        else
            printf '%s\n' '{"status":"retained","reason":"lifecycle_cleanup_unresolved"}' > "$out"
            exit 1
        fi
    fi
else
    exec "$REAL_UV" "$@"
fi
UV
chmod +x "$TMP_DIR/bin/uv"
export REAL_UV
REAL_UV=$(command -v uv)
export PATH="$TMP_DIR/bin:$PATH"

export REAL_JQ
REAL_JQ=$(command -v jq)
cat >"$TMP_DIR/bin/jq" <<'JQ'
#!/usr/bin/env bash
if [ "${JB_JQ_STUB_MERGE_FAILURE:-}" = 1 ] && [ "${1:-}" = --slurpfile ]; then exit 5; fi
exec "$REAL_JQ" "$@"
JQ
chmod +x "$TMP_DIR/bin/jq"

run_case() {
	local product=$1
	local expected_ide=$2
	local expected_marker=$3
	local json_out="$TMP_DIR/$product-report.json"

	./scripts/dogfood-red-lane-smoke.sh \
		--product "$product" \
		--helper "$HELPER" \
		--work-root "$TMP_DIR/work" \
		--ide-channel stable \
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
    .ide_channel == "stable" and
    .ide_version == "2026.2" and
    .verdict == "RED" and
    .total_problems == 1 and
    .cleanup.status == "closed" and
    .agent_result.bucket == "actionable_findings" and
    .payload.agent_result.bucket == "actionable_findings" and
    .open_attempt_count == 1 and
    .first_attempt_reliable == true and
    .open_methods == ["running_ide"] and
    .identity.plugin_version == "test-1.0.0" and
    .identity.plugin_build_fingerprint == "test-clean" and
    .worktree_retirement.status == "removed" and
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
    .agent_result.retry_policy.retry == $stub_retry and
    .open_attempt_count == 1 and
    .open_methods == ["running_ide"] and
    .route_diagnostic.reason == "target_ide_running_without_target_project" and
    .blocked_diagnostic.reason == "jetbrains_project_open_blocked"
  ' "$json_out" >/dev/null
}

run_case intellij "IntelliJ IDEA" redLaneField
run_case pycharm PyCharm duplicate
run_case webstorm WebStorm duplicate
run_unknown_case pycharm capture_not_ready true red_unknown_retryable:capture_not_ready
run_unknown_case pycharm tool_bug false red_unknown_terminal:tool_bug

retained_report="$TMP_DIR/retained.json"
if JB_INSPECT_STUB_CLEANUP=deferred ./scripts/dogfood-red-lane-smoke.sh --helper "$HELPER" --work-root "$TMP_DIR/work" --json-out "$retained_report"; then
	echo "expected deferred lifecycle to fail" >&2
	exit 1
fi
jq -e '.cleanup.status == "deferred" and .worktree_retirement.status == "retained"' "$retained_report" >/dev/null
retained_project=$(jq -r .project "$retained_report")
test -f "$retained_project/src/main/java/com/example/redlane/DefinitelyRed.java"
matrix_report="$TMP_DIR/matrix.json"
./scripts/dogfood-smoke-matrix.sh --helper "$HELPER" --repo "fixture=$ROOT" --ide PyCharm --case all --worktree-root "$TMP_DIR/work" --json-out "$matrix_report"
jq -e '.status == "ok" and all(.rows[]; .identity.plugin_build_fingerprint == .payload.route.ide.plugin_build_fingerprint and .plugin_build_fingerprint == .identity.plugin_build_fingerprint) and any(.rows[]; .scenario == "preexisting" and .cleanup.status == "not_needed") and any(.rows[]; .scenario == "helper-opened" and .worktree_retirement.status == "removed")' "$matrix_report" >/dev/null
if JB_INSPECT_STUB_CLEANUP=deferred ./scripts/dogfood-smoke-matrix.sh --helper "$HELPER" --repo "fixture=$ROOT" --ide PyCharm --case helper-opened --worktree-root "$TMP_DIR/work" --json-out "$matrix_report"; then
	echo "expected deferred matrix lifecycle to fail" >&2
	exit 1
fi
jq -e '.status == "failed" and .rows[0].worktree_retirement.status == "retained"' "$matrix_report" >/dev/null
test -d "$(jq -r '.rows[0].worktree_path' "$matrix_report")"
if JB_RETIRE_STUB_MISSING=1 ./scripts/dogfood-smoke-matrix.sh --helper "$HELPER" --repo "fixture=$ROOT" --ide PyCharm --case all --worktree-root "$TMP_DIR/work" --json-out "$matrix_report"; then
	echo "missing retirement result must fail matrix" >&2
	exit 1
fi
jq -e '.status == "failed" and any(.rows[]; .scenario == "preexisting") and any(.rows[]; .scenario == "helper-opened" and .status == "clean" and .worktree_retirement.reason == "retirement_result_unproven")' "$matrix_report" >/dev/null
if JB_RETIRE_STUB_MISSING=1 ./scripts/dogfood-red-lane-smoke.sh --helper "$HELPER" --work-root "$TMP_DIR/work" --json-out "$retained_report"; then
	echo "missing retirement result must fail red smoke" >&2
	exit 1
fi
jq -e '.status == "failed" and .verdict == "RED" and .bucket == "red_confirmed_project_retained" and .worktree_retirement.reason == "retirement_result_unproven"' "$retained_report" >/dev/null
large_size=250000
JB_INSPECT_STUB_SIZE="$large_size" ./scripts/dogfood-smoke-matrix.sh --helper "$HELPER" --repo "fixture=$ROOT" --ide PyCharm --case preexisting --json-out "$TMP_DIR/large-row.json"
jq -e --argjson size "$large_size" '.status == "ok" and all(.rows[]; (.payload.native_detail | length) == $size)' "$TMP_DIR/large-row.json" >/dev/null
if JB_JQ_STUB_MERGE_FAILURE=1 JB_RETIRE_STUB_MISSING=1 ./scripts/dogfood-smoke-matrix.sh --helper "$HELPER" --repo "fixture=$ROOT" --ide PyCharm --case all --worktree-root "$TMP_DIR/work" --json-out "$TMP_DIR/jq-failure.json" 2>"$TMP_DIR/jq-failure.stderr"; then
	echo "payload merge failure must abort the matrix" >&2
	exit 1
fi
test ! -e "$TMP_DIR/jq-failure.json"
merge_evidence=$(sed -n 's/^Smoke evidence retained: //p' "$TMP_DIR/jq-failure.stderr" | tail -n 1)
merge_receipt=$(find "$merge_evidence" -maxdepth 1 -name 'worktree-*.json' -print -quit)
test -f "$merge_receipt"
test -d "$(jq -r .root "$merge_receipt")"
# Stock macOS Bash must delegate when no local options are configured.
mkdir "$TMP_DIR/plain-repo"
git -C "$TMP_DIR/plain-repo" init -q
(cd "$TMP_DIR/plain-repo" && JB_INSPECT_HELPER="$HELPER" /bin/bash "$ROOT/scripts/test-automated.sh" --repo "$ROOT") >"$TMP_DIR/delegation.json"
jq -e '.status == "clean"' "$TMP_DIR/delegation.json" >/dev/null
echo "red-lane and matrix smoke script contracts passed"
