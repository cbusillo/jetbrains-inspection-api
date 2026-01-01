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

text = Path("gradle.properties").read_text()
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
text = path.read_text()
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
    path.write_text(new_text)
    print(f"Updated plugin.xml version to {version}")
PY

if [ "$CI_MODE" -eq 0 ]; then
    git add src/main/resources/META-INF/plugin.xml
fi

JAVA_HOME=${JAVA_HOME_21:-${JAVA_HOME:-}}
if [ -z "$JAVA_HOME" ]; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
        JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    else
        for dir in /usr/lib/jvm/java-21-* /usr/lib/jvm/java-21 /usr/lib/jvm/jdk-21*; do
            if [ -d "$dir" ]; then
                JAVA_HOME="$dir"
                break
            fi
        done
    fi
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "ERROR: Java 21 not found. Set JAVA_HOME_21." >&2
    exit 1
fi

export JAVA_HOME

./gradlew test
./gradlew :mcp-server-jvm:test
./gradlew buildPlugin
