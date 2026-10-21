#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

MODE=${1:-}
CI_MODE=0
if [ "$MODE" = "--ci" ] || [ "${CI:-}" = "true" ]; then
    CI_MODE=1
fi

VERSION=$(python3 - <<'PY'
from pathlib import Path
import re
import sys

text = Path("gradle.properties").read_text(encoding="utf-8")
match = re.search(r"^\s*pluginVersion\s*=\s*(.+?)\s*$", text, re.M)
if not match:
    sys.exit(1)
print(match.group(1).strip())
PY
) || {
    echo "ERROR: pluginVersion not found in gradle.properties" >&2
    exit 1
}

python3 - <<'PY' "$VERSION" "$CI_MODE"
import re
import sys
from pathlib import Path

version = sys.argv[1]
ci_mode = sys.argv[2] == "1"
path = Path("src/main/resources/META-INF/plugin.xml")
text = path.read_text(encoding="utf-8")
expected = f"<version>{version}</version>"

if expected not in text:
    if ci_mode:
        print(
            f"ERROR: plugin.xml version mismatch (expected {version}). "
            "Run scripts/commit-gate.sh locally to sync.",
            file=sys.stderr,
        )
        sys.exit(1)
    new_text = re.sub(r"<version>[^<]*</version>", expected, text, count=1)
    if new_text == text:
        print("ERROR: Could not update plugin.xml version", file=sys.stderr)
        sys.exit(1)
    path.write_text(new_text, encoding="utf-8")
    print(f"Updated plugin.xml version to {version}")
PY

if [ "$CI_MODE" -eq 0 ]; then
    git add src/main/resources/META-INF/plugin.xml
fi

selected_lanes() {
    if [ -n "${COMMIT_GATE_CHANGED_FILES:-}" ]; then
        if [ -r "$COMMIT_GATE_CHANGED_FILES" ]; then
            ./scripts/changed-file-lanes.sh < "$COMMIT_GATE_CHANGED_FILES"
            return
        fi
    elif [ "$CI_MODE" -eq 0 ]; then
        git diff --cached --name-only --no-renames | ./scripts/changed-file-lanes.sh
        return
    fi
    printf 'contracts\ngradle\n'
}

LANES=$(selected_lanes)

lane_selected() {
    grep -qx "$1" <<<"$LANES"
}

for variable in $(git rev-parse --local-env-vars); do
    unset "$variable"
done

if lane_selected contracts; then
    uv run scripts/lint-workflow-action-pins.py .github/workflows
    uv run scripts/lint-inspection-boundary.py --sources src/main/kotlin --manifest config/plugin-verifier/stable-internal-api-allowlist.txt
    ./scripts/verify-internal-api-allowlist.py --manifest config/plugin-verifier/stable-internal-api-allowlist.txt >/dev/null
    ./scripts/test-changed-file-lanes.sh
    uv run scripts/test-ide-retention.py
    ./scripts/test-release-contracts.sh
    uv run scripts/test-agents-release.py
else
    echo "Commit gate: only documentation changed; skipping contract tests."
fi

if ! lane_selected gradle; then
    echo "Commit gate: no changed file needs the Gradle lanes; skipping tests, coverage, and buildPlugin."
    exit 0
fi

source "$ROOT/scripts/java21.sh"
use_java21

./gradlew :test
./gradlew :inspection-core:jacocoTestCoverageVerification
./gradlew :mcp-server-jvm:jacocoTestCoverageVerification
./gradlew buildPlugin
