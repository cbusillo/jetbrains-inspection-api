#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

if [ "${1:-}" != "--ci" ] && [ "${CI:-}" != "true" ]; then
    echo "commit-gate.sh runs the complete CI suite. Run it with --ci, or run the focused local tests in TESTING.md." >&2
    exit 2
fi

for variable in $(git rev-parse --local-env-vars); do
    unset "$variable"
done

uv run scripts/lint-workflow-action-pins.py .github/workflows
uv run scripts/lint-inspection-boundary.py --sources src/main/kotlin --sources src/full/kotlin --sources src/stable/kotlin --boundary src/full/kotlin/com/shiny/inspectionmcp/InspectionBuildEdition.kt --manifest config/plugin-verifier/full-internal-api-allowlist.txt
uv run scripts/lint-cancellation-catches.py --sources src/main/kotlin
./scripts/verify-internal-api-allowlist.py --manifest config/plugin-verifier/full-internal-api-allowlist.txt >/dev/null
./scripts/verify-internal-api-allowlist.py --manifest config/plugin-verifier/stable-internal-api-allowlist.txt >/dev/null
uv run scripts/test-smoke-lifecycle.py
./scripts/test-red-lane-smoke-script.sh
uv run scripts/test-ide-retention.py
for desktop_client_test in scripts/desktop-client/test_*.py; do
    uv run "$desktop_client_test"
done
./scripts/test-release-contracts.sh
uv run scripts/test-agents-release.py

source "$ROOT/scripts/java21.sh"
use_java21

# Stable build: no internal API use beyond its exempted allowlist, and truthful verdicts without them.
./gradlew buildPlugin verifyPlugin -PinspectionBuild=stable -PpluginVerifierIdes=target \
    -I scripts/test-plugin-verifier-isolation.init.gradle
./gradlew :platformTest -PinspectionBuild=stable --tests '*.StableBuildVerdictPlatformTest'

./gradlew :test
./gradlew :inspection-core:jacocoTestCoverageVerification
./gradlew :mcp-server-jvm:jacocoTestCoverageVerification
./gradlew buildPlugin
