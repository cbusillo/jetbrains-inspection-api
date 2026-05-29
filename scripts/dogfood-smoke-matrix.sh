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

CASE_FILTER="all"
SCOPE="changed_files"
TIMEOUT_MS=180000
PREPARE_TIMEOUT_MS=180000
WORKTREE_ROOT=""
JSON_OUT=""
KEEP_WORKTREES=0
DRY_RUN=0

REPOS=()
IDES=()

usage() {
	cat <<'USAGE'
Usage: ./scripts/dogfood-smoke-matrix.sh [options]

Runs a local dogfood matrix around the jetbrains-inspection helper closeout
flow. The matrix records IDE identity, plugin version, cleanup evidence, and a
failure bucket linked to the current hardening issue.

Options:
  --helper PATH              Path to jb-inspect.py.
  --repo [LABEL=]PATH        Repo/worktree to inspect. Repeatable.
  --ide NAME                 IDE selector, e.g. "IntelliJ IDEA". Repeatable.
  --case NAME                all, preexisting, or helper-opened. Default: all.
  --scope SCOPE              Helper closeout scope. Default: changed_files.
  --timeout-ms MS            Helper wait timeout. Default: 180000.
  --prepare-timeout-ms MS    Helper prepare/open timeout. Default: 180000.
  --worktree-root PATH       Parent directory for disposable worktrees.
  --json-out PATH            Write the full JSON report to PATH.
  --keep-worktrees           Leave helper-opened worktrees on disk.
  --dry-run                  Print rows without running the helper.
  -h, --help                 Show this help.

Default repos are this repo plus ~/Developer/mediaforce when present. Default
IDEs are IntelliJ IDEA, PyCharm, and WebStorm.
USAGE
}

die() {
	echo "ERROR: $*" >&2
	exit 2
}

slugify() {
	printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//'
}

abs_path() {
	local path=$1
	if [ -d "$path" ]; then
		(cd "$path" 2>/dev/null && pwd -P) || return 1
	elif [ -e "$path" ]; then
		local dir
		dir=$(dirname "$path") || return 1
		local base
		base=$(basename "$path") || return 1
		(cd "$dir" 2>/dev/null && printf '%s/%s\n' "$(pwd -P)" "$base") || return 1
	else
		case "$path" in
		/*) printf '%s\n' "$path" ;;
		*) printf '%s/%s\n' "$PWD" "$path" ;;
		esac
	fi
}

parse_repo_spec() {
	local spec=$1
	local label path
	if [[ "$spec" == *=* ]]; then
		label=${spec%%=*}
		path=${spec#*=}
	else
		path=$spec
		label=$(basename "$path")
	fi
	printf '%s=%s\n' "$label" "$path"
}

while [ $# -gt 0 ]; do
	case "$1" in
	--helper)
		[ $# -ge 2 ] || die "--helper requires a path"
		HELPER=$2
		shift 2
		;;
	--repo)
		[ $# -ge 2 ] || die "--repo requires a value"
		REPOS+=("$(parse_repo_spec "$2")")
		shift 2
		;;
	--ide)
		[ $# -ge 2 ] || die "--ide requires a value"
		IDES+=("$2")
		shift 2
		;;
	--case)
		[ $# -ge 2 ] || die "--case requires a value"
		CASE_FILTER=$2
		shift 2
		;;
	--scope)
		[ $# -ge 2 ] || die "--scope requires a value"
		SCOPE=$2
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
	--worktree-root)
		[ $# -ge 2 ] || die "--worktree-root requires a value"
		WORKTREE_ROOT=$2
		shift 2
		;;
	--json-out)
		[ $# -ge 2 ] || die "--json-out requires a path"
		JSON_OUT=$2
		shift 2
		;;
	--keep-worktrees)
		KEEP_WORKTREES=1
		shift
		;;
	--dry-run)
		DRY_RUN=1
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

case "$CASE_FILTER" in
all | preexisting | helper-opened) ;;
*) die "--case must be all, preexisting, or helper-opened" ;;
esac

if [ ${#REPOS[@]} -eq 0 ]; then
	REPOS+=("plugin=$ROOT")
	if [ -d "$HOME/Developer/mediaforce" ]; then
		REPOS+=("mediaforce=$HOME/Developer/mediaforce")
	fi
fi

if [ ${#IDES[@]} -eq 0 ]; then
	IDES+=("IntelliJ IDEA" "PyCharm" "WebStorm")
fi

if [ -z "$WORKTREE_ROOT" ]; then
	WORKTREE_ROOT="$HOME/.code/working/jetbrains-inspection-api/dogfood-smoke"
fi

if [ ! -x "$HELPER" ]; then
	die "helper is not executable: $HELPER"
fi

TMP_DIR=$(mktemp -d)
ROW_INDEX=0
CREATED_WORKTREE_REPOS=()
CREATED_WORKTREE_PATHS=()
LAST_WORKTREE=""

# shellcheck disable=SC2329
cleanup() {
	if [ "$KEEP_WORKTREES" -eq 0 ]; then
		local index repo worktree
		for index in "${!CREATED_WORKTREE_PATHS[@]}"; do
			repo=${CREATED_WORKTREE_REPOS[$index]}
			worktree=${CREATED_WORKTREE_PATHS[$index]}
			git -C "$repo" worktree remove --force "$worktree" >/dev/null 2>&1 || rm -rf "$worktree"
		done
	fi
	rm -rf "$TMP_DIR"
}
trap cleanup EXIT

write_row() {
	ROW_INDEX=$((ROW_INDEX + 1))
	cat >"$TMP_DIR/row-$ROW_INDEX.json"
}

command_json() {
	jq -n '$ARGS.positional' --args -- "$@"
}

issue_for_bucket() {
	case "$1" in
	opaque_error | helper_error | invalid_helper_json | capture_incomplete* | cleanup_* | lifecycle_* | skipped_preexisting_not_open | dry_run | clean | findings) echo "#68" ;;
	*) echo "#65" ;;
	esac
}

classify_payload() {
	local scenario=$1
	local exit_code=$2
	local payload=$3
	local valid_json=$4

	if [ "$DRY_RUN" -eq 1 ]; then
		echo "dry_run"
		return
	fi

	if [ "$valid_json" != "true" ]; then
		echo "invalid_helper_json"
		return
	fi

	local status error_reason capture cleanup_status clean total
	status=$(jq -r '.status // ""' "$payload")
	error_reason=$(jq -r '.error_reason // ""' "$payload")
	capture=$(jq -r '(.capture_incomplete // false) | tostring' "$payload")
	cleanup_status=$(jq -r '.cleanup.status // ""' "$payload")
	clean=$(jq -r '(.clean // false) | tostring' "$payload")
	total=$(jq -r '(.total_problems // .wait.total_problems // 0) | tostring' "$payload")

	if [ "$scenario" = "preexisting" ] && [ "$exit_code" -ne 0 ] && [ "$status" = "error" ]; then
		case "$error_reason" in
		inspection_api_unavailable | missing_project | project_not_open | route_not_found | no_open_project | invalid_project | ide_open_failed | inspection_helper_error | worktree_route_mismatch)
			echo "skipped_preexisting_not_open"
			return
			;;
		esac
	fi

	if [ "$scenario" = "preexisting" ] && [ -n "$cleanup_status" ] && [ "$cleanup_status" != "not_needed" ]; then
		echo "cleanup_expected_not_needed"
		return
	fi

	if [ "$status" = "error" ]; then
		if [ -z "$error_reason" ]; then
			echo "opaque_error"
		else
			echo "helper_error"
		fi
		return
	fi

	if [ "$capture" = "true" ] || [ "$status" = "capture_incomplete" ]; then
		local reason
		reason=$(
			jq -r '
        .capture_incomplete_reason
        // .wait.capture_incomplete_reason
        // (if (.capture_diagnostic.exit_reason // .wait.capture_diagnostic.exit_reason) == "deadline" then "timeout" else null end)
        // .capture_diagnostic.exit_reason
        // .wait.capture_diagnostic.exit_reason
        // "unknown"
      ' "$payload"
		)
		echo "capture_incomplete:$reason"
		return
	fi

	if [ "$scenario" = "helper-opened" ] && [ "$cleanup_status" != "closed" ]; then
		echo "cleanup_expected_closed"
		return
	fi

	if [ "$clean" = "true" ] || [ "$status" = "clean" ]; then
		echo "clean"
		return
	fi

	if [ "$status" = "findings" ] || { [ "$total" != "0" ] && [ "$total" != "null" ]; }; then
		echo "findings"
		return
	fi

	if [ "$exit_code" -ne 0 ]; then
		echo "helper_error"
		return
	fi

	echo "unknown"
}

build_row() {
	local label=$1
	local source_repo=$2
	local target_repo=$3
	local ide=$4
	local scenario=$5
	local exit_code=$6
	local bucket=$7
	local command=$8
	local payload_file=$9
	local valid_json=${10}
	local raw_file=${11}
	local stderr_file=${12}
	local worktree_path=${13}
	local issue
	issue=$(issue_for_bucket "$bucket")

	local command_payload payload_json raw_payload stderr_payload
	command_payload=$(cat "$command")
	if [ "$valid_json" = "true" ]; then
		payload_json=$(cat "$payload_file")
	else
		payload_json='{}'
	fi
	raw_payload=$(head -c 4000 "$raw_file" 2>/dev/null || true)
	stderr_payload=$(head -c 4000 "$stderr_file" 2>/dev/null || true)

	jq -n \
		--arg label "$label" \
		--arg source_repo "$source_repo" \
		--arg target_repo "$target_repo" \
		--arg ide "$ide" \
		--arg scenario "$scenario" \
		--arg bucket "$bucket" \
		--arg issue "$issue" \
		--argjson exit_code "$exit_code" \
		--argjson command "$command_payload" \
		--argjson payload "$payload_json" \
		--arg raw_output "$raw_payload" \
		--arg stderr "$stderr_payload" \
		--arg worktree_path "$worktree_path" '
      def route:
        $payload.route
        // $payload.wait.route
        // $payload.trigger.route
        // $payload.prepared.lease.route
        // $payload.prepared.claim.route
        // {};
      def identity: route.ide // {};
      {
        label: $label,
        source_repo: $source_repo,
        target_repo: $target_repo,
        worktree_path: (if $worktree_path == "" then null else $worktree_path end),
        ide: $ide,
        scenario: $scenario,
        exit_code: $exit_code,
        bucket: $bucket,
        issue: $issue,
        status: ($payload.status // null),
        clean: ($payload.clean // null),
        total_problems: ($payload.total_problems // $payload.wait.total_problems // null),
        capture_incomplete: ($payload.capture_incomplete // null),
        capture_incomplete_reason: (
          $payload.capture_incomplete_reason
          // $payload.wait.capture_incomplete_reason
          // (if ($payload.capture_diagnostic.exit_reason // $payload.wait.capture_diagnostic.exit_reason) == "deadline" then "timeout" else null end)
          // $payload.capture_diagnostic.exit_reason
          // $payload.wait.capture_diagnostic.exit_reason
          // null
        ),
        cleanup: ($payload.cleanup // null),
        prepared: {
          opened_by_helper: ($payload.prepared.lease.opened_by_helper // $payload.prepared.opened_by_helper // null),
          open_method: ($payload.prepared.lease.open_method // $payload.prepared.open_method // null),
          lease_id: ($payload.prepared.lease.lease_id // $payload.prepared.claim.lease_id // null),
          project_key: ($payload.prepared.lease.project_key // $payload.prepared.claim.project_key // null)
        },
        route: {
          base_path: (route.base_path // null),
          project_file_path: (route.project_file_path // null),
          project_key: (route.project_key // null),
          project_instance_id: (route.project_instance_id // null),
          focused: (route.focused // null),
          port: (route.port // null),
          session_id: (route.session_id // null)
        },
        identity: {
          name: (identity.name // null),
          product_code: (identity.product_code // null),
          version: (identity.version // null),
          plugin_version: (identity.plugin_version // null),
          pid: (identity.pid // null)
        },
        command: $command,
        payload: $payload,
        raw_output_excerpt: (if $raw_output == "" then null else $raw_output end),
        stderr_excerpt: (if $stderr == "" then null else $stderr end)
      }'
}

make_worktree() {
	local source_repo=$1
	local label=$2
	local ide=$3
	local parent
	parent=$(abs_path "$WORKTREE_ROOT") || return 1
	mkdir -p "$parent"
	local worktree
	worktree="$parent/$(slugify "$label")-$(slugify "$ide")-$(date +%Y%m%d%H%M%S)-$$"
	git -C "$source_repo" worktree add --detach "$worktree" HEAD >/dev/null 2>&1 || return 1
	CREATED_WORKTREE_REPOS+=("$source_repo")
	CREATED_WORKTREE_PATHS+=("$worktree")
	LAST_WORKTREE=$worktree
}

write_static_row() {
	local label=$1
	local source_repo=$2
	local target_repo=$3
	local ide=$4
	local scenario=$5
	local bucket=$6
	local message=$7
	local cmd_file="$TMP_DIR/cmd-static-$ROW_INDEX.json"
	local payload_file="$TMP_DIR/payload-static-$ROW_INDEX.json"
	local raw_file="$TMP_DIR/raw-static-$ROW_INDEX.txt"
	local stderr_file="$TMP_DIR/stderr-static-$ROW_INDEX.txt"

	command_json >"$cmd_file"
	jq -n \
		--arg message "$message" \
		--arg bucket "$bucket" \
		'{status:"error", error:$message, error_message:$message, error_reason:$bucket}' >"$payload_file"
	: >"$raw_file"
	: >"$stderr_file"
	build_row "$label" "$source_repo" "$target_repo" "$ide" "$scenario" 2 "$bucket" "$cmd_file" "$payload_file" true "$raw_file" "$stderr_file" "" | write_row
}

run_case() {
	local label=$1
	local source_repo=$2
	local ide=$3
	local scenario=$4
	local target_repo=$source_repo
	local worktree_path=""

	if [ ! -d "$source_repo" ]; then
		write_static_row "$label" "$source_repo" "$source_repo" "$ide" "$scenario" "missing_repo" "repo path does not exist"
		return
	fi

	if [ "$scenario" = "helper-opened" ] && [ "$DRY_RUN" -eq 0 ]; then
		if ! git -C "$source_repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
			write_static_row "$label" "$source_repo" "$source_repo" "$ide" "$scenario" "missing_git_worktree" "helper-opened case requires a git worktree"
			return
		fi
		if ! make_worktree "$source_repo" "$label" "$ide"; then
			write_static_row "$label" "$source_repo" "$source_repo" "$ide" "$scenario" "worktree_create_failed" "could not create disposable worktree"
			return
		fi
		worktree_path=$LAST_WORKTREE
		target_repo=$worktree_path
	elif [ "$scenario" = "helper-opened" ]; then
		worktree_path="$WORKTREE_ROOT/$(slugify "$label")-$(slugify "$ide")-dry-run"
		target_repo=$worktree_path
	fi

	local cmd=(uv run "$HELPER" --json closeout --repo "$target_repo" --ide "$ide" --scope "$SCOPE" --timeout-ms "$TIMEOUT_MS" --prepare-timeout-ms "$PREPARE_TIMEOUT_MS")
	if [ "$scenario" = "preexisting" ]; then
		cmd+=(--no-open)
	fi

	local cmd_file="$TMP_DIR/cmd-$ROW_INDEX.json"
	command_json "${cmd[@]}" >"$cmd_file"

	local payload_file="$TMP_DIR/payload-$ROW_INDEX.json"
	local raw_file="$TMP_DIR/raw-$ROW_INDEX.txt"
	local stderr_file="$TMP_DIR/stderr-$ROW_INDEX.txt"
	local exit_code=0
	local valid_json=true

	if [ "$DRY_RUN" -eq 1 ]; then
		jq -n --arg status dry_run '{status:$status, clean:null}' >"$payload_file"
		: >"$raw_file"
		: >"$stderr_file"
	else
		"${cmd[@]}" >"$raw_file" 2>"$stderr_file"
		exit_code=$?
		if jq empty "$raw_file" >/dev/null 2>&1; then
			cp "$raw_file" "$payload_file"
		else
			valid_json=false
			jq -n '{status:"error", error_reason:"invalid_helper_json"}' >"$payload_file"
		fi
	fi

	local bucket
	bucket=$(classify_payload "$scenario" "$exit_code" "$payload_file" "$valid_json")
	build_row "$label" "$source_repo" "$target_repo" "$ide" "$scenario" "$exit_code" "$bucket" "$cmd_file" "$payload_file" "$valid_json" "$raw_file" "$stderr_file" "$worktree_path" | write_row
}

SCENARIOS=()
if [ "$CASE_FILTER" = "all" ]; then
	SCENARIOS+=("preexisting" "helper-opened")
else
	SCENARIOS+=("$CASE_FILTER")
fi

for repo_spec in "${REPOS[@]}"; do
	label=${repo_spec%%=*}
	repo_path=${repo_spec#*=}
	repo_abs=$(abs_path "$repo_path" || printf '%s' "$repo_path")
	for ide in "${IDES[@]}"; do
		for scenario in "${SCENARIOS[@]}"; do
			echo "dogfood: $label $scenario on $ide" >&2
			run_case "$label" "$repo_abs" "$ide" "$scenario"
		done
	done
done

ROWS_FILE="$TMP_DIR/rows.json"
if compgen -G "$TMP_DIR/row-*.json" >/dev/null; then
	jq -s '.' "$TMP_DIR"/row-*.json >"$ROWS_FILE"
else
	echo '[]' >"$ROWS_FILE"
fi

STATUS=$(jq -r '
  def pass_bucket:
    . == "clean" or . == "dry_run" or . == "skipped_preexisting_not_open";
  if any(.[]; (.bucket | pass_bucket | not)) then "failed" else "ok" end
' "$ROWS_FILE")

REPORT=$(
	jq -n \
		--slurpfile rows "$ROWS_FILE" \
		--arg status "$STATUS" \
		--arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
		--arg helper "$HELPER" \
		--arg scope "$SCOPE" \
		--arg case_filter "$CASE_FILTER" \
		--argjson timeout_ms "$TIMEOUT_MS" \
		--argjson prepare_timeout_ms "$PREPARE_TIMEOUT_MS" '
    {
      status: $status,
      generated_at: $generated_at,
      helper: $helper,
      scope: $scope,
      case_filter: $case_filter,
      timeout_ms: $timeout_ms,
      prepare_timeout_ms: $prepare_timeout_ms,
      issue_buckets: {
        "#68": "active dogfood smoke matrix result bucket",
        "#65": "parent dogfood hardening plan"
      },
      summary: {
        rows: ($rows[0] | length),
        by_bucket: ($rows[0] | group_by(.bucket) | map({key: .[0].bucket, value: length}) | from_entries),
        by_issue: ($rows[0] | group_by(.issue) | map({key: .[0].issue, value: length}) | from_entries)
      },
      rows: $rows[0]
    }'
)

printf '%s\n' "$REPORT" | jq -r '
  "status=\(.status) rows=\(.summary.rows)",
  (.rows[] | "[\(.bucket)] \(.scenario) \(.label) on \(.ide) cleanup=\(.cleanup.status // "-") open=\(.prepared.opened_by_helper // "-") plugin=\(.identity.plugin_version // "unknown") issue=\(.issue)")
'

if [ -n "$JSON_OUT" ]; then
	mkdir -p "$(dirname "$JSON_OUT")"
	printf '%s\n' "$REPORT" >"$JSON_OUT"
	echo "wrote $JSON_OUT" >&2
fi

if [ "$STATUS" = "ok" ]; then
	exit 0
fi

exit 1
