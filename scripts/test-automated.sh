#!/usr/bin/env bash
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
HELPER="${JB_INSPECT_HELPER:-$HOME/Developer/codex-skills/skills/jetbrains-inspection/scripts/jb-inspect.py}"
if [ "${1:-}" = "--install" ]; then
	shift
	exec uv run "$ROOT/scripts/install-local-plugin.py" --helper "$HELPER" "$@"
fi
if [ "${1:-}" = "--help" ]; then
	cat <<'USAGE'
Usage: scripts/test-automated.sh [inspection-helper options]
       scripts/test-automated.sh --install --archive ZIP --source-sha SHA          --ide-app /exact/IDE.app --artifact-root /existing/evidence          --maintenance-window [--repo /exact/clean/source] [--helper PATH]

Default: inspect the configured TEST_PROJECT_PATH without installing or restarting.
Pass helper --repo/--ide/--scope selectors explicitly when no local config exists.
Installation requires a separately authorized maintenance window; see MAINTAINING.md.
USAGE
	exit 0
fi
TEST_PROJECT_PATH=""
IDE_TYPE=""
IDE_VERSION=""
if [ -f "$ROOT/AGENTS.local.md" ]; then
	TEST_PROJECT_PATH=$(sed -n 's/^TEST_PROJECT_PATH="\([^"]*\)".*/\1/p' "$ROOT/AGENTS.local.md" | head -n 1)
	IDE_VERSION=$(sed -n 's/^IDE_VERSION="\([^"]*\)".*/\1/p' "$ROOT/AGENTS.local.md" | head -n 1)
	IDE_TYPE=$(sed -n 's/^IDE_TYPE="\([^"]*\)".*/\1/p' "$ROOT/AGENTS.local.md" | head -n 1)
fi
OPTIONS=()
[ -z "$TEST_PROJECT_PATH" ] || OPTIONS+=(--repo "$TEST_PROJECT_PATH")
[ -z "$IDE_TYPE" ] || OPTIONS+=(--ide "$IDE_TYPE")
[ -z "$IDE_VERSION" ] || OPTIONS+=(--ide-version "$IDE_VERSION")
exec uv run "$HELPER" inspect-closeout --json "${OPTIONS[@]}" "$@"
