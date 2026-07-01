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

PRODUCT="intellij"
IDE=""
IDE_APP=""
IDE_CHANNEL=""
IDE_VERSION=""
TIMEOUT_MS=180000
PREPARE_TIMEOUT_MS=180000
WORK_ROOT="$HOME/.code/working/jetbrains-inspection-api/red-lane-smoke"
JSON_OUT=""
KEEP_PROJECT=0

usage() {
	cat <<'USAGE'
Usage: ./scripts/dogfood-red-lane-smoke.sh [options]

Copies the maintained inspection-red-lane fixture to a disposable local project,
runs the JetBrains inspection helper readiness inspection, and requires a RED verdict.
This is a live IDE dogfood smoke, not a normal CI unit test.

Options:
  --helper PATH              Path to jb-inspect.py.
  --product NAME             Fixture product: intellij, pycharm, webstorm. Default: intellij.
  --ide NAME                 IDE selector. Defaults from --product.
  --ide-app NAME             Exact macOS app bundle name to launch. Defaults to --ide.
  --ide-channel CHANNEL      IDE channel selector: stable, eap, or any.
  --ide-version VERSION      Exact IDE version selector, e.g. 2026.2.
  --timeout-ms MS            Helper wait timeout. Default: 180000.
  --prepare-timeout-ms MS    Helper prepare/open timeout. Default: 180000.
  --work-root PATH           Parent directory for disposable fixture copies.
  --json-out PATH            Write JSON report to PATH.
  --keep-project             Leave the disposable fixture project on disk.
  -h, --help                 Show this help.

Each fixture intentionally contains a product-specific inspection finding. A
passing smoke means IDE inspection produced actionable findings, the plugin
captured them, and the helper reported VERDICT=RED.
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
	--product)
		[ $# -ge 2 ] || die "--product requires a value"
		PRODUCT=$2
		shift 2
		;;
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
	--ide-app)
		[ $# -ge 2 ] || die "--ide-app requires a value"
		IDE_APP=$2
		shift 2
		;;
	--ide-channel)
		[ $# -ge 2 ] || die "--ide-channel requires a value"
		IDE_CHANNEL=$2
		shift 2
		;;
	--ide-version)
		[ $# -ge 2 ] || die "--ide-version requires a value"
		IDE_VERSION=$2
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

case "$PRODUCT" in
intellij | idea)
	PRODUCT="intellij"
	FIXTURE="$ROOT/test-fixtures/inspection-red-lane"
	PROJECT_SLUG="inspection-red-lane"
	DEFAULT_IDE="IntelliJ IDEA"
	REQUIRED_PROFILE_TOOLS=("UnusedDeclaration")
	;;
pycharm | python)
	PRODUCT="pycharm"
	FIXTURE="$ROOT/test-fixtures/inspection-red-lane-pycharm"
	PROJECT_SLUG="inspection-red-lane-pycharm"
	DEFAULT_IDE="PyCharm"
	REQUIRED_PROFILE_TOOLS=("PyUnresolvedReferencesInspection")
	;;
webstorm | javascript | js)
	PRODUCT="webstorm"
	FIXTURE="$ROOT/test-fixtures/inspection-red-lane-webstorm"
	PROJECT_SLUG="inspection-red-lane-webstorm"
	DEFAULT_IDE="WebStorm"
	REQUIRED_PROFILE_TOOLS=("JsonDuplicatePropertyKeys" "JsonStandardCompliance")
	;;
*)
	die "unknown product: $PRODUCT"
	;;
esac

if [ -z "$IDE" ]; then
	IDE=$DEFAULT_IDE
fi
if [ -z "$IDE_APP" ]; then
	IDE_APP=$IDE
fi

[ -x "$HELPER" ] || die "helper is not executable: $HELPER"
[ -d "$FIXTURE" ] || die "fixture is missing: $FIXTURE"

WORK_ROOT=$(abs_path "$WORK_ROOT")
mkdir -p "$WORK_ROOT"

RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$$
PROJECT="$WORK_ROOT/$PROJECT_SLUG-$RUN_ID"
RAW_OUT="$WORK_ROOT/$PROJECT_SLUG-$RUN_ID.raw.json"
ERR_OUT="$WORK_ROOT/$PROJECT_SLUG-$RUN_ID.stderr.txt"
PAYLOAD_FILE="$WORK_ROOT/$PROJECT_SLUG-$RUN_ID.payload.json"
COMMAND_FILE="$WORK_ROOT/$PROJECT_SLUG-$RUN_ID.command.json"

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

PROFILE_FILE="$PROJECT/.idea/inspectionProfiles/RedLane.xml"
[ -f "$PROFILE_FILE" ] || die "fixture profile is missing: $PROFILE_FILE"
for tool in "${REQUIRED_PROFILE_TOOLS[@]}"; do
	grep -q "class=\"$tool\"[^>]*enabled=\"true\"" "$PROFILE_FILE" ||
		die "fixture profile does not enable required inspection tool: $tool"
done

CMD=(uv run "$HELPER" --json inspect-closeout --repo "$PROJECT" --ide "$IDE" --ide-app "$IDE_APP")
if [ -n "$IDE_CHANNEL" ]; then
	CMD+=(--ide-channel "$IDE_CHANNEL")
fi
if [ -n "$IDE_VERSION" ]; then
	CMD+=(--ide-version "$IDE_VERSION")
fi
CMD+=(--scope whole_project --profile RedLane --timeout-ms "$TIMEOUT_MS" --prepare-timeout-ms "$PREPARE_TIMEOUT_MS")
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
		--arg product "$PRODUCT" \
		--arg ide "$IDE" \
		--arg ide_channel "$IDE_CHANNEL" \
		--arg ide_version "$IDE_VERSION" \
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
        product: $product,
        ide: $ide,
        ide_channel: (if $ide_channel == "" then null else $ide_channel end),
        ide_version: (if $ide_version == "" then null else $ide_version end),
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
