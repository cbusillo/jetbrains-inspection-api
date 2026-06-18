#!/usr/bin/env bash

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT" || exit 1

DEFAULT_HELPER_DEV="$HOME/Developer/codex-skills/jetbrains-inspection/scripts/jb-inspect.py"
DEFAULT_HELPER_HOME="${CODE_HOME:-${CODEX_HOME:-$HOME/.code}}/skills/jetbrains-inspection/scripts/jb-inspect.py"

if [ -x "$DEFAULT_HELPER_DEV" ]; then
	HELPER="$DEFAULT_HELPER_DEV"
else
	HELPER="$DEFAULT_HELPER_HOME"
fi

FIXTURE="$ROOT/test-fixtures/inspection-red-lane"
IDE="IntelliJ IDEA"
TIMEOUT_MS=180000
PREPARE_TIMEOUT_MS=180000
WORK_ROOT="$HOME/.code/working/jetbrains-inspection-api/red-lane-smoke"
JSON_OUT=""
KEEP_PROJECT=0

usage() {
	cat <<'USAGE'
Usage: ./scripts/dogfood-red-lane-smoke.sh [options]

Copies the maintained inspection-red-lane fixture to a disposable local project,
runs the JetBrains inspection helper closeout, and requires a RED verdict.
This is a live IDE dogfood smoke, not a normal CI unit test.

Options:
  --helper PATH              Path to jb-inspect.py.
  --ide NAME                 IDE selector. Default: IntelliJ IDEA.
  --timeout-ms MS            Helper wait timeout. Default: 180000.
  --prepare-timeout-ms MS    Helper prepare/open timeout. Default: 180000.
  --work-root PATH           Parent directory for disposable fixture copies.
  --json-out PATH            Write JSON report to PATH.
  --keep-project             Leave the disposable fixture project on disk.
  -h, --help                 Show this help.

The fixture intentionally contains unresolved Java symbols. A passing smoke means:
IDE inspection produced actionable findings, the plugin captured them, and the
helper reported VERDICT=RED.
USAGE
}

die() {
	echo "ERROR: $*" >&2
	exit 2
}

abs_path() {
	local path=$1
	case "$path" in
	/*) printf '%s\n' "$path" ;;
	*) printf '%s/%s\n' "$PWD" "$path" ;;
	esac
}

while [ $# -gt 0 ]; do
	case "$1" in
	--helper)
		[ $# -ge 2 ] || die "--helper requires a path"
		HELPER=$2
		shift 2
		;;
	--ide)
		[ $# -ge 2 ] || die "--ide requires a value"
		IDE=$2
		shift 2
		;;
	--timeout-ms)
		[ $# -ge 2 ] || die "--timeout-ms requires a value"
		TIMEOUT_MS=$2
		shift 2
		;;
	--prepare-timeout-ms)
		[ $# -ge 2 ] || die "--prepare-timeout-ms requires a value"
		PREPARE_TIMEOUT_MS=$2
		shift 2
		;;
	--work-root)
		[ $# -ge 2 ] || die "--work-root requires a path"
		WORK_ROOT=$2
		shift 2
		;;
	--json-out)
		[ $# -ge 2 ] || die "--json-out requires a path"
		JSON_OUT=$2
		shift 2
		;;
	--keep-project)
		KEEP_PROJECT=1
		shift
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		die "unknown option: $1"
		;;
	esac
done

[ -x "$HELPER" ] || die "helper is not executable: $HELPER"
[ -d "$FIXTURE" ] || die "fixture is missing: $FIXTURE"

WORK_ROOT=$(abs_path "$WORK_ROOT")
mkdir -p "$WORK_ROOT"

RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$$
PROJECT="$WORK_ROOT/inspection-red-lane-$RUN_ID"
RAW_OUT="$WORK_ROOT/inspection-red-lane-$RUN_ID.raw.json"
ERR_OUT="$WORK_ROOT/inspection-red-lane-$RUN_ID.stderr.txt"
PAYLOAD_FILE="$WORK_ROOT/inspection-red-lane-$RUN_ID.payload.json"
COMMAND_FILE="$WORK_ROOT/inspection-red-lane-$RUN_ID.command.json"

# shellcheck disable=SC2329
cleanup() {
	if [ "$KEEP_PROJECT" -eq 0 ]; then
		rm -rf "$PROJECT"
	fi
}
trap cleanup EXIT

rm -rf "$PROJECT"
mkdir -p "$PROJECT"
cp -R "$FIXTURE"/. "$PROJECT"/

CMD=(uv run "$HELPER" --json closeout --repo "$PROJECT" --ide "$IDE" --scope whole_project --profile RedLane --timeout-ms "$TIMEOUT_MS" --prepare-timeout-ms "$PREPARE_TIMEOUT_MS")
jq -n '$ARGS.positional' --args -- "${CMD[@]}" >"$COMMAND_FILE"

"${CMD[@]}" >"$RAW_OUT" 2>"$ERR_OUT"
EXIT_CODE=$?

if jq empty "$RAW_OUT" >/dev/null 2>&1; then
	cp "$RAW_OUT" "$PAYLOAD_FILE"
else
	jq -n --arg error "helper did not emit valid JSON" --arg raw "$(head -c 4000 "$RAW_OUT" 2>/dev/null || true)" '{status:"error", error_reason:"invalid_helper_json", error:$error, raw_output_excerpt:$raw}' >"$PAYLOAD_FILE"
fi

VERDICT=$(jq -r '.verdict // .inspection_verdict // ""' "$PAYLOAD_FILE")
TOTAL=$(jq -r '.total_problems // 0' "$PAYLOAD_FILE")
CLEANUP_STATUS=$(jq -r '.cleanup.status // ""' "$PAYLOAD_FILE")

if [ "$EXIT_CODE" -le 1 ] && [ "$VERDICT" = "RED" ] && [ "$TOTAL" != "0" ] && [ "$TOTAL" != "null" ] && [ "$CLEANUP_STATUS" = "closed" ]; then
	STATUS="ok"
	BUCKET="red_confirmed"
else
	STATUS="failed"
	BUCKET="red_not_confirmed"
fi

REPORT=$(
	jq -n \
		--arg status "$STATUS" \
		--arg bucket "$BUCKET" \
		--arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
			--arg helper "$HELPER" \
			--arg ide "$IDE" \
			--arg fixture "$FIXTURE" \
			--arg project "$PROJECT" \
			--argjson exit_code "$EXIT_CODE" \
			--slurpfile command "$COMMAND_FILE" \
			--slurpfile payload "$PAYLOAD_FILE" \
			--arg stderr "$(head -c 4000 "$ERR_OUT" 2>/dev/null || true)" '
      def command: $command[0];
      def payload: $payload[0];
      {
        status: $status,
        bucket: $bucket,
        generated_at: $generated_at,
        helper: $helper,
        ide: $ide,
        fixture: $fixture,
        project: $project,
        exit_code: $exit_code,
        verdict: (payload.verdict // payload.inspection_verdict // null),
        verdict_reason: (payload.verdict_reason // payload.inspection_verdict_reason // null),
        total_problems: (payload.total_problems // null),
        problems_shown: (payload.problems_shown // null),
        cleanup: (payload.cleanup // null),
        route: (payload.route // null),
        command: command,
        payload: payload,
        stderr_excerpt: (if $stderr == "" then null else $stderr end)
      }'
)

printf '%s\n' "$REPORT" | jq -r '"status=\(.status) bucket=\(.bucket) verdict=\(.verdict) total=\(.total_problems // "-") cleanup=\(.cleanup.status // "-")"'

if [ -n "$JSON_OUT" ]; then
	mkdir -p "$(dirname "$JSON_OUT")"
	printf '%s\n' "$REPORT" >"$JSON_OUT"
	echo "wrote $JSON_OUT" >&2
fi

if [ "$STATUS" = "ok" ]; then
	exit 0
fi

exit 1
