#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

source "$ROOT/scripts/java21.sh"
use_java21

./gradlew buildPlugin verifyPluginStructure verifyPlugin
