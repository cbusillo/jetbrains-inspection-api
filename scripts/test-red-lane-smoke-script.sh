#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

FIXTURE_SOURCE="test-fixtures/inspection-red-lane/src/main/java/com/example/redlane/DefinitelyRed.java"
FIXTURE_PROFILE="test-fixtures/inspection-red-lane/.idea/inspectionProfiles/RedLane.xml"

grep -q 'INTENTIONAL RED-LANE FIXTURE' "$FIXTURE_SOURCE"
grep -q 'MissingType value = MissingType.create();' "$FIXTURE_SOURCE"
grep -q 'private void unusedMethod()' "$FIXTURE_SOURCE"
grep -q 'JavaErrorInspection' "$FIXTURE_PROFILE"
grep -q 'UnusedDeclaration' "$FIXTURE_PROFILE"
grep -q -- '--scope whole_project' scripts/dogfood-red-lane-smoke.sh
grep -q -- '--profile RedLane' scripts/dogfood-red-lane-smoke.sh

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
args = sys.argv[1:]
while args:
    arg = args.pop(0)
    if arg == "--repo" and args:
        repo = args.pop(0)

fixture = Path(repo) / "src/main/java/com/example/redlane/DefinitelyRed.java"
if not repo or not fixture.exists():
    print(json.dumps({"status": "error", "error_reason": "missing_fixture"}))
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
    "route": {"base_path": repo, "project_name": "inspection-red-lane", "ide": {"name": "IntelliJ IDEA"}},
    "problems": [{"severity": "error", "file": str(fixture), "line": 6, "description": "Cannot resolve symbol MissingType"}],
}))
STUB
chmod +x "$HELPER"

JSON_OUT="$TMP_DIR/report.json"
./scripts/dogfood-red-lane-smoke.sh \
  --helper "$HELPER" \
  --work-root "$TMP_DIR/work" \
  --json-out "$JSON_OUT"

jq -e '
  .status == "ok" and
  .bucket == "red_confirmed" and
  .verdict == "RED" and
  .total_problems == 1 and
  .cleanup.status == "closed" and
  (.payload.problems[0].description | contains("MissingType"))
' "$JSON_OUT" >/dev/null

echo "red-lane smoke script contract passed"
