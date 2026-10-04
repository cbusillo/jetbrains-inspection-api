#!/usr/bin/env bash

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT" || exit 1

HELPER="${JB_INSPECT_HELPER:-$HOME/Developer/codex-skills/skills/jetbrains-inspection/scripts/jb-inspect.py}"
WORKTREE_TOOL="$ROOT/scripts/smoke-worktree.py"

PRODUCT="intellij"
IDE=""
IDE_APP=""
IDE_CHANNEL=""
IDE_VERSION=""
TIMEOUT_MS=180000
PREPARE_TIMEOUT_MS=180000
WORK_ROOT=""
JSON_OUT=""
KEEP_PROJECT=0

usage() {
	cat <<'USAGE'
Usage: ./scripts/dogfood-red-lane-smoke.sh [options]

Checks out the maintained inspection-red-lane fixture in a disposable linked worktree,
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
  --work-root PATH           Portable hosts: explicit worktree parent; omit on Chris-Studio.
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
	REQUIRED_PROFILE_TOOLS=("PyDictDuplicateKeysInspection")
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

EVIDENCE=$(mktemp -d)
echo "Retained smoke evidence: $EVIDENCE" >&2
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$$
RECEIPT="$EVIDENCE/worktree.json"
CREATE=(uv run "$WORKTREE_TOOL" create --source "$ROOT" --slug "smoke-$PROJECT_SLUG-$RUN_ID" --receipt "$RECEIPT")
[ -z "$WORK_ROOT" ] || CREATE+=(--parent "$WORK_ROOT")
WORKTREE=$("${CREATE[@]}") || exit 2
if [[ "$WORKTREE" == /Volumes/Developer-Artifacts/worktrees/* ]]; then
	EVIDENCE_PARENT="/Volumes/Developer-Artifacts/task-evidence/jetbrains-inspection-api/smoke"
	mkdir -p "$EVIDENCE_PARENT"
	MOVED_EVIDENCE="$EVIDENCE_PARENT/$(basename "$WORKTREE")"
	mv "$EVIDENCE" "$MOVED_EVIDENCE"
	EVIDENCE=$MOVED_EVIDENCE
	RECEIPT="$EVIDENCE/worktree.json"
fi
PROJECT="$WORKTREE/${FIXTURE#"$ROOT/"}"
RAW_OUT="$EVIDENCE/raw.json"
ERR_OUT="$EVIDENCE/stderr.txt"
PAYLOAD_FILE="$EVIDENCE/payload.json"
COMMAND_FILE="$EVIDENCE/command.json"
# Never delete a project on EXIT: the complete response controls retirement.
trap 'echo "Smoke evidence retained: $EVIDENCE; worktree: $WORKTREE" >&2' EXIT

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

if jq -se 'length == 1 and (.[0] | type == "object")' "$RAW_OUT" >/dev/null 2>&1; then
	cp "$RAW_OUT" "$PAYLOAD_FILE"
else
	jq -n --arg error "helper did not emit valid JSON" --arg raw "$(head -c 4000 "$RAW_OUT" 2>/dev/null || true)" '{status:"error", error_reason:"invalid_helper_json", error:$error, raw_output_excerpt:$raw}' >"$PAYLOAD_FILE"
fi

RETIREMENT="$EVIDENCE/retirement.json"
if [ "$KEEP_PROJECT" -eq 0 ]; then
	uv run "$WORKTREE_TOOL" retire --receipt "$RECEIPT" --payload "$PAYLOAD_FILE" --helper "$HELPER" --out "$RETIREMENT" >&2 || true
else
	printf '%s\n' '{"status":"retained","reason":"keep_requested"}' >"$RETIREMENT"
fi

if ! jq -e 'type == "object" and (.status == "removed" or .status == "retained")' "$RETIREMENT" >/dev/null 2>&1; then
	printf '%s\n' '{"status":"retained","reason":"retirement_result_unproven"}' >"$RETIREMENT"
fi

RETIREMENT_STATUS=$(jq -r .status "$RETIREMENT")
VERDICT=$(jq -r '.verdict // .inspection_verdict // ""' "$PAYLOAD_FILE")
TOTAL=$(jq -r '.total_problems // 0' "$PAYLOAD_FILE")
CLEANUP_STATUS=$(jq -r '.cleanup.status // ""' "$PAYLOAD_FILE")
AGENT_BUCKET=$(jq -r '.agent_result.bucket // .bucket // ""' "$PAYLOAD_FILE")
AGENT_RETRY=$(jq -r '(.agent_result.retry_policy.retry // .retry_policy.retry // false) | tostring' "$PAYLOAD_FILE")

if [ "$EXIT_CODE" -le 1 ] && [ "$VERDICT" = "RED" ] && { [ "$AGENT_BUCKET" = "" ] || [ "$AGENT_BUCKET" = "actionable_findings" ]; } && [ "$TOTAL" != "0" ] && [ "$TOTAL" != "null" ] && [ "$CLEANUP_STATUS" = "closed" ] && { [ "$KEEP_PROJECT" -eq 1 ] || [ "$RETIREMENT_STATUS" = "removed" ]; }; then
	STATUS="ok"
	BUCKET="red_confirmed"
else
	STATUS="failed"
	if [ -n "$AGENT_BUCKET" ] && [ "$AGENT_BUCKET" != "actionable_findings" ]; then
		if [ "$AGENT_RETRY" = "true" ]; then
			BUCKET="red_unknown_retryable:$AGENT_BUCKET"
		else
			BUCKET="red_unknown_terminal:$AGENT_BUCKET"
		fi
	elif [ "$VERDICT" = "RED" ] && [ "$TOTAL" != "0" ] && [ "$TOTAL" != "null" ]; then
		BUCKET="red_confirmed_project_retained"
	else
		BUCKET="red_not_confirmed"
	fi
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
		--arg worktree "$WORKTREE" \
		--arg evidence "$EVIDENCE" \
		--slurpfile retirement "$RETIREMENT" \
		--argjson exit_code "$EXIT_CODE" \
		--slurpfile command "$COMMAND_FILE" \
		--slurpfile payload "$PAYLOAD_FILE" \
		--arg stderr "$(head -c 4000 "$ERR_OUT" 2>/dev/null || true)" '
      def command: $command[0];
      def payload: $payload[0];
      def route: payload.route // payload.prepared.route // payload.prepared.lease.route // payload.prepared.claim.route // {};
      def identity: route.ide // {};
      def open_attempts: payload.open_attempts // payload.prepared.open_attempts // payload.prepared.lease.open_attempts // payload.lease.open_attempts // [];
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
        worktree: $worktree,
        evidence: $evidence,
        worktree_retirement: $retirement[0],
        exit_code: $exit_code,
        verdict: (payload.verdict // payload.inspection_verdict // null),
        verdict_reason: (payload.verdict_reason // payload.inspection_verdict_reason // null),
        error_reason: (payload.error_reason // null),
        blocked_diagnostic: (payload.blocked_diagnostic // null),
        route_diagnostic: (payload.route_diagnostic // null),
        open_attempts: open_attempts,
        open_attempt_count: (open_attempts | length),
        open_methods: (open_attempts | map(.method) | unique),
        first_attempt_reliable: ((open_attempts | length) <= 1),
        agent_result: (payload.agent_result // {
          bucket: (payload.bucket // null),
          retry_policy: (payload.retry_policy // null),
          agent_report: (payload.agent_report // null)
        }),
        total_problems: (payload.total_problems // null),
        problems_shown: (payload.problems_shown // null),
        cleanup: (payload.cleanup // null),
        route: (if route == {} then null else route end),
        identity: {
          name: (identity.name // null),
          product_code: (identity.product_code // null),
          version: (identity.version // null),
          plugin_version: (identity.plugin_version // null),
          plugin_build_fingerprint: (identity.plugin_build_fingerprint // null),
          pid: (identity.pid // null)
        },
        command: command,
        payload: payload,
        stderr_excerpt: (if $stderr == "" then null else $stderr end)
      }'
) || exit 2

printf '%s\n' "$REPORT" | jq -r '"status=\(.status) bucket=\(.bucket) verdict=\(.verdict) reason=\(.verdict_reason // .error_reason // "-") agent=\(.agent_result.bucket // "-") retry=\(.agent_result.retry_policy.retry // false) total=\(.total_problems // "-") cleanup=\(.cleanup.status // "-") attempts=\(.open_attempt_count) methods=\((.open_methods // []) | join(",")) plugin=\(.identity.plugin_version // "unknown")"'

if [ -n "$JSON_OUT" ]; then
	mkdir -p "$(dirname "$JSON_OUT")" || exit 2
	printf '%s\n' "$REPORT" >"$JSON_OUT" || exit 2
	echo "wrote $JSON_OUT" >&2
fi

if [ "$STATUS" = "ok" ]; then
	exit 0
fi

exit 1
