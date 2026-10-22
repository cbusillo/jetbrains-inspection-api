#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

MODE=${1:-}
CI_MODE=0
if [ "$MODE" = "--ci" ] || [ "${CI:-}" = "true" ]; then
    CI_MODE=1
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
    uv run scripts/lint-inspection-boundary.py --sources src/main/kotlin --sources src/full/kotlin --sources src/stable/kotlin --boundary src/full/kotlin/com/shiny/inspectionmcp/InspectionBuildEdition.kt --manifest config/plugin-verifier/full-internal-api-allowlist.txt
    uv run scripts/lint-cancellation-catches.py --sources src/main/kotlin
    ./scripts/verify-internal-api-allowlist.py --manifest config/plugin-verifier/full-internal-api-allowlist.txt >/dev/null
    ./scripts/verify-internal-api-allowlist.py --manifest config/plugin-verifier/stable-internal-api-allowlist.txt >/dev/null
    ./scripts/test-changed-file-lanes.sh
    uv run scripts/test-smoke-lifecycle.py
    ./scripts/test-red-lane-smoke-script.sh
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

# Stable build: no internal API use beyond its exempted allowlist, and truthful verdicts without them.
./gradlew buildPlugin verifyPlugin -PinspectionBuild=stable -PpluginVerifierIdes=target
./gradlew :platformTest -PinspectionBuild=stable --tests '*.StableBuildVerdictPlatformTest'

./gradlew :test
./gradlew :inspection-core:jacocoTestCoverageVerification
./gradlew :mcp-server-jvm:jacocoTestCoverageVerification
./gradlew buildPlugin
