#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""JetBrains inspection helper for Codex skills.

The helper talks to the JetBrains Inspection API plugin over HTTP. It keeps the
LLM-facing workflow deterministic: resolve a route, trigger inspection, wait for
completion, fetch results, and classify the outcome for readiness.
"""

from __future__ import annotations

import argparse
import errno
import fnmatch
import hashlib
import http.client
import json
import os
import plistlib
import re
import signal
import stat
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from xml.etree import ElementTree
from xml.parsers.expat import ExpatError

if sys.platform == "win32":
    import msvcrt

    fcntl = None
else:
    import fcntl

    msvcrt = None


DEFAULT_PORT_RANGE = range(63340, 63350)
DEFAULT_TIMEOUT_SECONDS = 3.0
MIN_DIAGNOSTIC_PROBE_TIMEOUT_SECONDS = 0.25
DEFAULT_WAIT_TIMEOUT_MS = 120_000
DEFAULT_POLL_MS = 1_000
DEFAULT_PREPARE_TIMEOUT_MS = 300_000
DEFAULT_REPOSITORY_PREPARATION_TIMEOUT_MS = 120_000
DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS = 300_000
DEFAULT_OUTCOME_ROUTING_LOCK_TIMEOUT_MS = 300_000
DEFAULT_OUTCOME_APPEND_LOCK_TIMEOUT_MS = 300_000
DEFAULT_CANCELLATION_SETTLE_TIMEOUT_MS = 10_000
ALREADY_OPENING_RETRY_LIMIT = 15
LOOPBACK_HOST = "127.0.0.1"
REDACTED = "<redacted>"
UNKNOWN_LOG_ENV = "JB_INSPECT_UNKNOWN_LOG"
OUTCOME_LOG_ENV = "JB_INSPECT_OUTCOME_LOG"
DEPLOYMENT_MANIFEST_ENV = "JB_INSPECT_DEPLOYMENT_MANIFEST"
UNKNOWN_LOG_ASSESSMENT_COMMANDS = frozenset({"agent", "run", "closeout", "wait", "status", "problems"})
OUTCOME_ASSESSMENT_COMMANDS = frozenset({"agent", "run", "closeout"})
OUTCOME_OBSERVATION_COMMANDS = frozenset({"wait", "status", "problems"})
UNKNOWN_LOG_INFORMATIONAL_STATUSES = frozenset({"ok", "prepared", "resolved", "triggered", "claimed"})
UNKNOWN_RETRY_BUCKETS = frozenset({"ide_not_ready", "stale_results", "capture_not_ready"})
INTERNAL_RETRY_BUCKETS = frozenset({"stale_results", "capture_not_ready"})
UNKNOWN_RETRY_WAIT_MS = 30_000
NATIVE_BROAD_SCOPE_PROOF_VERSION = 2
MAX_WORKTREE_MUTATION_PATHS = 25
MAX_LANE_FILE_PATHS = 100
MAX_AGENT_FINDINGS = 20
MAX_INSPECTION_STAGE_HISTORY = 8
MAX_INSPECTION_FAILURE_HISTORY = 3
MAX_INSPECTION_WORKER_STACK = 64
MAX_INSPECTION_WORKER_FRAME_LENGTH = 1_024
MAX_INSPECTION_WORKER_THREAD_LENGTH = 256
MAX_INSPECTION_TOOL_SHORT_NAME_LENGTH = 160
MAX_INSPECTION_FILE_LENGTH = 4_096
MAX_INSPECTION_ATTEMPT_DIAGNOSTICS = 4
LANE_MUTATION_SETTLE_DELAY_MS = 5_000
INTERNAL_RETRY_READY_TIMEOUT_MS = 90_000
INTERNAL_RETRY_READY_STABLE_OBSERVATIONS = 3
ROUTE_READY_STABLE_OBSERVATIONS = 3
READINESS_BARRIER_SCHEMA_VERSION = 1
LIFECYCLE_OWNERSHIP_PROTOCOL = "lease_bound_v1"
INSPECTION_ATTRIBUTION_SCHEMA_VERSION = 1
OUTCOME_LOG_SCHEMA_VERSION = 2
QUALIFICATION_SCHEMA_VERSION = 1
AGENT_RESULT_SCHEMA_VERSION = 1
INSPECTION_LANE_SCHEMA_VERSION = 1
REPOSITORY_PREPARATION_SOURCE = "qualityGate.inspection.prepare"
REPOSITORY_PREPARATION_NOT_CONFIGURED = "not_configured"
REPOSITORY_PREPARATION_NOT_RUN = "not_run"
REPOSITORY_PREPARATION_SKIPPED = "skipped_opt_out"
REPOSITORY_PREPARATION_REUSED = "reused"
REPOSITORY_PREPARATION_SUCCEEDED = "succeeded"
MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH = 1024
MAX_REPOSITORY_PREPARATION_OUTPUT_LENGTH = 16_384
MAX_REPOSITORY_PREPARATION_GENERATED_STATE_PATHS = 25
MAX_REPOSITORY_PREPARATION_TEST_ROOTS = 10
MAX_REPOSITORY_PREPARATION_EXTRAS = 10
MAX_REPOSITORY_PREPARATION_PEP723_SCRIPTS = 25
MAX_REPOSITORY_PREPARATION_TIMEOUT_MS = 900_000
REPOSITORY_PREPARATION_RECEIPT_SCHEMA_VERSION = 1
REPOSITORY_PREPARATION_RECEIPT_DIRNAME = "repository-preparation"
REPOSITORY_PREPARATION_ACTIVE_ENV = "JB_INSPECT_REPOSITORY_PREPARATION_ACTIVE"
REPOSITORY_PREPARATION_TERMINAL_REASONS = frozenset(
    {
        "repository_preparation_untrusted",
        "repository_preparation_opted_out",
        "repository_preparation_recursion",
        "repository_preparation_runtime_unavailable",
        "repository_preparation_command_failed",
        "repository_preparation_timeout",
        "repository_preparation_tracked_mutation",
        "repository_preparation_index_mutation",
        "repository_preparation_generated_state_missing",
    }
)
INSPECTION_LANE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
AGENT_USAGE_ERROR_REASON = "helper_usage_error"
QUALIFICATION_MIN_DECISIVE_RATE = 0.95
QUALIFICATION_CLEANUP_STATUSES = frozenset({"closed", "not_needed"})
QUALIFICATION_CONFIGURATION_CODES = frozenset({"ide_selection_required", "ide_config_ambiguous", "ide_config_missing"})
FULL_SHA256_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
RECOVERABLE_HELPER_LEASE_STATES = frozenset(
    {
        "route_resolved",
        "ownership_claimed",
        "prepared",
        "kept_warm_after_indexing_timeout",
        "cleanup_pending",
        "cleanup_failed",
        "cleanup_skipped",
    }
)
POTENTIAL_OPEN_LEASE_STATES = frozenset({"open_requesting", "open_registered", "cleanup_pending"})
SETTLED_LEASE_STATES = frozenset(
    {"cleanup_pending", "prepared", "kept_warm_after_indexing_timeout", "cleanup_failed", "cleanup_skipped"}
)
FAILURE_REASONS_RAISED_BEFORE_OPEN_REQUEST = frozenset(
    {"timeout", "connectionreseterror", "identity_port_mismatch", "invalid_identity_port", "inspection_api_unavailable"}
)
PREFERRED_COMMANDS = {
    "list": "list-projects",
    "route": "resolve-route",
    "trigger": "start-inspection",
    "wait": "wait-for-inspection",
    "status": "get-status",
    "problems": "get-problems",
    "claim": "claim-worktree",
    "prepare": "open-worktree",
    "prepare-worktree": "open-worktree",
    "open-worktree": "open-worktree",
    "closeout": "inspect-closeout",
    "run": "inspect",
    "cleanup-leases": "cleanup-helper-leases",
    "summarize-outcomes": "summarize-outcomes",
}
COMMAND_ALIASES = {
    "list-projects": "list",
    "resolve-route": "route",
    "prepare": "open-worktree",
    "prepare-worktree": "open-worktree",
    "open-worktree": "open-worktree",
    "inspect": "run",
    "inspect-closeout": "closeout",
    "get-status": "status",
    "get-problems": "problems",
    "start-inspection": "trigger",
    "wait-for-inspection": "wait",
    "claim-worktree": "claim",
    "cleanup-helper-leases": "cleanup-leases",
}
ROLLOUT_FILE_ENVS = (
    "JB_INSPECT_ROLLOUT_FILE",
    "CODE_ROLLOUT_FILE",
    "CODEX_ROLLOUT_FILE",
    "CODE_SESSION_FILE",
    "CODEX_SESSION_FILE",
)
VERDICT_SOURCE_KEYS = (
    "inspection_verdict",
    "inspection_verdict_reason",
    "inspection_verdict_message",
    "inspection_verdict_next_action",
    "inspection_attribution",
    "proof_failures",
    "inspection_proof",
)
INSPECTION_STAGE_LABEL_PATTERN = re.compile(r"[a-z][a-z0-9_]{0,63}")
INSPECTION_TERMINAL_OUTCOMES = frozenset({"completed", "cancelled", "failed", "timed_out", "preempted"})
INSPECTION_FAILURE_SOURCES = frozenset(
    {"wait_timeout", "capture_deadline", "cancellation", "exact_proof_deadline", "exact_proof_write_preempted"}
)
INSPECTION_FAILURE_OUTCOMES = frozenset({"timeout", "cancelled", "preempted"})
INSPECTION_WORKER_PHASES = frozenset({"execution"})
INSPECTION_STAGE_EVIDENCE_KEYS = (
    "inspection_stage",
    "inspection_stage_elapsed_ms",
    "inspection_run_elapsed_ms",
    "inspection_stage_history",
    "inspection_terminal_outcome",
    "inspection_failure_diagnostic",
    "inspection_failure_history",
)
INSPECTION_STAGE_RUN_ID_KEY = "inspection_stage_run_id"
INSPECTION_ATTEMPT_SUMMARY_KEYS = (
    "attempt_index",
    "status",
    "bucket",
    "verdict",
    "verdict_reason",
    "retry",
    "phase",
    "proof_failures",
    "wait_completion_reason",
    "snapshot_change_kind",
    "stale_reasons",
    "capture_incomplete_reason",
)
INSPECTION_DIAGNOSTIC_CHILD_KEYS = (
    "inspection_failure",
    "wait",
    "status",
    "raw",
    "cancellation",
    "response",
    "last_status",
    "capture_diagnostic",
    "diagnostic",
)
SEMANTIC_COVERAGE_MISSING_REASON = "scope_semantic_coverage_missing"
SEMANTIC_COVERAGE_TRUNCATED_REASON = "scope_semantic_coverage_truncated"
RED_COMPATIBLE_PROOF_FAILURES = frozenset(
    {
        "execution_not_proven",
        SEMANTIC_COVERAGE_MISSING_REASON,
        SEMANTIC_COVERAGE_TRUNCATED_REASON,
    }
)
PROJECT_CONTENT_ROOTS_MISSING_REASON = "project_content_roots_missing"
PROJECT_METADATA_COVERAGE_REASON = "project_metadata_coverage_not_required"
NON_SEMANTIC_PSI_VALUES = frozenset({"text", "plaintext", "textmate"})
NON_SEMANTIC_PSI_CLASS_MARKERS = frozenset({"plaintext", "textmate"})
PROJECT_METADATA_FILE_TYPES = frozenset({"ideamodule"})
PROJECT_METADATA_COVERAGE_ROLE = "project_metadata"
EXCLUDED_DEPENDENCY_LOCKFILE_COVERAGE_ROLE = "excluded_dependency_lockfile"
DEPENDENCY_LOCKFILE_NAMES = frozenset(
    {
        "bun.lock",
        "bun.lockb",
        "cargo.lock",
        "composer.lock",
        "flake.lock",
        "gemfile.lock",
        "go.sum",
        "npm-shrinkwrap.json",
        "package-lock.json",
        "package.resolved",
        "packages.lock.json",
        "pipfile.lock",
        "pnpm-lock.yaml",
        "poetry.lock",
        "uv.lock",
        "yarn.lock",
    }
)
SENSITIVE_KEY_PARTS = ("token", "secret", "password", "credential", "authorization")
SENSITIVE_OUTPUT_ASSIGNMENT_PATTERN = re.compile(
    r"(?i)\b(token|secret|password|credential|authorization|api[_-]?key)\b(\s*[:=]\s*)([^\s,;]+)"
)
SENSITIVE_OUTPUT_BEARER_PATTERN = re.compile(r"(?i)(\bBearer\s+)([^\s,;]+)")
DURABLE_POSIX_PATH_ROOTS = frozenset(
    {
        "Applications",
        "Library",
        "System",
        "Users",
        "Volumes",
        "bin",
        "dev",
        "etc",
        "home",
        "lib",
        "lib64",
        "media",
        "mnt",
        "nix",
        "opt",
        "private",
        "proc",
        "root",
        "run",
        "sbin",
        "srv",
        "sys",
        "tmp",
        "usr",
        "var",
        "workspace",
        "workspaces",
    }
)
DURABLE_QUOTED_PATH_PATTERN = re.compile(
    r'''(?P<quote>["'])(?P<path>(?:file://(?:localhost)?/|(?:path|file):/|[A-Za-z]:[\\/]|\\\\|~/|/(?!/))[^"'\r\n]+)(?P=quote)'''
)
DURABLE_SPACED_FILE_PATH_PATTERN = re.compile(
    r'''(?<![A-Za-z0-9._/\\-])(?P<path>
        (?:file://(?:localhost)?/|(?:path|file):/|[A-Za-z]:[\\/]|\\\\|~/|/(?!/))
        (?:[^\\/"'<>\r\n|,;!?]+[\\/])+
        [^\\/"'<>\r\n|,;!?]*?\.[A-Za-z0-9][A-Za-z0-9._-]{0,15}
        (?::\d+(?::\d+)*)?
    )''',
    re.VERBOSE,
)
DURABLE_SPACED_PATH_TAIL_PATTERN = re.compile(
    rf'''(?<![A-Za-z0-9._/\\-])(?P<path>
        (?:
            file://(?:localhost)?/
            |(?:path|file):/
            |[A-Za-z]:[\\/]
            |\\\\
            |~/
            |/(?:{"|".join(re.escape(root) for root in sorted(DURABLE_POSIX_PATH_ROOTS))})(?:/|(?=\s|$))
        )
        [^"'<>\r\n|]+
    )''',
    re.VERBOSE,
)
DURABLE_PATH_TOKEN_PATTERN = re.compile(
    r'''(?<![A-Za-z0-9._/\\-])(?P<path>(?:file://(?:localhost)?/|(?:path|file):/|[A-Za-z]:[\\/]|\\\\|~/|/(?!/))[^\s"'<>|]+)'''
)
PROJECT_OPEN_BLOCKED_REASON = "jetbrains_project_open_blocked"
PROJECT_OPEN_BLOCKED_HINT = (
    "JetBrains may be waiting on a Trust Project, safe-mode, or open-project prompt "
    "in a foreground or background IDE window. Bring the IDE forward, answer the prompt, "
    "then retry inspection."
)
_HELPER_REVISION: str | None = None
_ACTIVE_CLIENT_RUN_ID: str | None = None


class InspectError(Exception):
    def __init__(self, message: str, exit_code: int = 2, payload: dict[str, Any] | None = None):
        super().__init__(message)
        self.exit_code = exit_code
        self.payload = payload or {}


@dataclass
class HttpResult:
    status: int
    body: dict[str, Any]
    url: str


@dataclass(frozen=True)
class IdeProduct:
    key: str
    display_name: str
    config_prefixes: tuple[str, ...]
    product_codes: tuple[str, ...]
    app_names: tuple[str, ...]
    aliases: tuple[str, ...]
    launcher_names: tuple[str, ...]
    env_var_base_name: str

    @property
    def vm_options_name(self) -> str:
        return f"{self.launcher_names[0]}.vmoptions"


@dataclass(frozen=True)
class IdeCandidate:
    product_key: str
    name: str
    path: Path | None
    version: tuple[int, ...]
    channel: str
    source: str


@dataclass(frozen=True)
class IdeSelection:
    requested: str | None
    product_key: str | None
    product: str | None
    mode: str
    channel: str
    version: tuple[int, ...]
    app_name: str | None
    app_path: Path | None
    config_dir: Path | None
    source: str
    exact: bool

    def public(self) -> dict[str, Any]:
        return {
            "requested": self.requested,
            "product_key": self.product_key,
            "product": self.product,
            "mode": self.mode,
            "channel": self.channel,
            "is_eap": self.channel == "eap",
            "explicit_eap": self.channel == "eap" and self.exact,
            "version": format_version(self.version),
            "app_name": self.app_name,
            "app_path": str(self.app_path) if self.app_path else None,
            "config_dir": str(self.config_dir) if self.config_dir else None,
            "source": self.source,
            "exact": self.exact,
        }


@dataclass(frozen=True)
class InspectionLane:
    lane_id: str
    ide: str
    required: bool
    include: tuple[str, ...]
    exclude: tuple[str, ...]
    project_path: str | None

    def public(self) -> dict[str, Any]:
        payload = {
            "id": self.lane_id,
            "ide": self.ide,
            "required": self.required,
            "include": list(self.include),
            "exclude": list(self.exclude),
        }
        if self.project_path is not None:
            payload["projectPath"] = self.project_path
        return payload


IDE_PRODUCTS = {
    "intellij": IdeProduct(
        key="intellij",
        display_name="IntelliJ IDEA",
        config_prefixes=("IntelliJIdea", "IdeaIC"),
        product_codes=("IU", "IC"),
        app_names=("IntelliJ IDEA",),
        aliases=("intellijidea", "intellij", "idea", "iu", "ic"),
        launcher_names=("idea",),
        env_var_base_name="IDEA",
    ),
    "pycharm": IdeProduct(
        key="pycharm",
        display_name="PyCharm",
        config_prefixes=("PyCharm",),
        product_codes=("PY", "PC"),
        app_names=("PyCharm", "PyCharm CE"),
        aliases=("pycharm", "pycharmce", "py", "pc"),
        launcher_names=("pycharm",),
        env_var_base_name="PYCHARM",
    ),
    "webstorm": IdeProduct(
        key="webstorm",
        display_name="WebStorm",
        config_prefixes=("WebStorm",),
        product_codes=("WS",),
        app_names=("WebStorm",),
        aliases=("webstorm", "ws"),
        launcher_names=("webstorm",),
        env_var_base_name="WEBIDE",
    ),
}
ALL_IDE_LAUNCHER_NAMES = tuple(name for product in IDE_PRODUCTS.values() for name in product.launcher_names)
IDE_PRODUCT_BY_ALIAS = {
    alias: product
    for product in IDE_PRODUCTS.values()
    for alias in product.aliases
}


def main() -> int:
    global _ACTIVE_CLIENT_RUN_ID
    previous_client_run_id = _ACTIVE_CLIENT_RUN_ID
    raw_argv = list(sys.argv[1:])
    parser = build_parser()
    args = parse_cli_args(parser, raw_argv)
    args.command = canonical_command(args.command)
    args.client_run_id = str(uuid.uuid4())
    _ACTIVE_CLIENT_RUN_ID = args.client_run_id
    try:
        if args.command == "list":
            result = command_list(args)
            return emit(result, args.json, 0, command=args.command_input, assess=False)
        if args.command == "route":
            context = build_context(args)
            result = command_route(args, context)
            return emit(result, args.json, 0, command=args.command_input, assess=False)
        if args.command == "trigger":
            context = build_context(args)
            result = command_trigger(args, context)
            return emit(result, args.json, 0, command=args.command_input, assess=False)
        if args.command == "wait":
            context = build_context(args)
            result = command_wait(args, context)
            return emit(result, args.json, classify_wait_exit(result), command=args.command_input)
        if args.command == "status":
            context = build_context(args)
            result = command_status(args, context)
            return emit(result, args.json, classify_status_exit(result), command=args.command_input)
        if args.command == "problems":
            context = build_context(args)
            result = command_problems(args, context)
            return emit(result, args.json, classify_problems_exit(result), command=args.command_input)
        if args.command == "claim":
            context = build_context(args)
            result = command_claim(args, context)
            return emit(result, args.json, 0, command=args.command_input, assess=False)
        if args.command == "open-worktree":
            context = build_context(args)
            result = command_prepare(args, context)
            exit_code = classify_prepare_exit(result)
            return emit(result, args.json, exit_code, command=args.command_input, assess=exit_code != 0)
        if args.command == "closeout":
            context = build_context(args)
            result = command_closeout(args, context)
            return emit(result, args.json, classify_closeout_exit(result), command=args.command_input)
        if args.command == "cleanup-leases":
            result = command_cleanup_leases(args)
            # Lease cleanup is not an inspection; its exit code and per-lease reasons carry the outcome.
            return emit(result, args.json, classify_cleanup_leases_exit(result), command=args.command_input, assess=False)
        if args.command in {"remove-worktree", "cleanup-helper-sdks"}:
            result = command_retire_sdks(args)
            return emit(result, args.json, 0, command=args.command_input, assess=False)
        if args.command == "summarize-outcomes":
            result = command_summarize_outcomes(args)
            return emit(result, args.json, summarize_outcomes_exit_code(result), command=args.command_input, assess=False)
        if args.command == "run":
            context = build_context(args)
            result = command_run(args, context)
            return emit(result, args.json, classify_run_exit(result), command=args.command_input)
    except InspectError as error:
        payload = error_payload(error, args)
        return emit(payload, getattr(args, "json", False), error.exit_code, command=getattr(args, "command_input", getattr(args, "command", None)))
    finally:
        _ACTIVE_CLIENT_RUN_ID = previous_client_run_id
    return 2


def error_payload(error: InspectError, args: argparse.Namespace | None = None) -> dict[str, Any]:
    payload = dict(error.payload)
    message = str(error)
    prior_status = payload.pop("status", None)
    if prior_status and prior_status != "error":
        if isinstance(prior_status, dict):
            payload.setdefault("last_status", prior_status)
        else:
            payload.setdefault("reason", prior_status)
    payload["status"] = "error"
    payload.setdefault("error", message)
    payload.setdefault("error_message", message)
    payload.setdefault("error_reason", infer_error_reason(error, payload))
    payload.setdefault("exit_code", error.exit_code)
    command = getattr(args, "command_input", None) or getattr(args, "command", None)
    if command:
        payload.setdefault("command", preferred_command(str(command)))
    client_run_id = getattr(args, "client_run_id", None)
    if client_run_id:
        payload.setdefault("client_run_id", client_run_id)
    if "hint" not in payload:
        hint = hint_for_error_reason(str(payload.get("error_reason") or ""), payload.get("context"))
        if hint:
            payload["hint"] = hint
    return payload


def canonical_command(command: str) -> str:
    return COMMAND_ALIASES.get(command, command)


def preferred_command(command: str) -> str:
    return PREFERRED_COMMANDS.get(command, command)


def normalize_command_argv(argv: list[str]) -> list[str]:
    normalized = list(argv)
    for index, token in enumerate(normalized):
        if token == "--":
            break
        if token.startswith("-"):
            continue
        normalized[index] = preferred_command(token)
        break
    return normalized


def parse_cli_args(parser: argparse.ArgumentParser, argv: list[str] | None = None) -> argparse.Namespace:
    raw_argv = list(sys.argv[1:] if argv is None else argv)
    normalized_argv = normalize_command_argv(raw_argv)
    args = parser.parse_args(normalized_argv)
    args.command_input = args.command
    return args


def infer_error_reason(error: InspectError, payload: dict[str, Any]) -> str:
    for key in ("error_reason", "reason", "status"):
        value = payload.get(key)
        if value and value != "error":
            return normalize_reason(value)
    message = str(error).lower()
    if "invalid json" in message or "non-object json" in message:
        return "invalid_api_response"
    if "http " in message and "inspection api" in message:
        return "inspection_api_http_error"
    if "unavailable" in message or "no jetbrains inspection plugin" in message:
        return "inspection_api_unavailable"
    if "timed out" in message or "timeout" in message:
        return "timeout"
    if "wrong tree" in message or "exact current worktree" in message:
        return "worktree_route_mismatch"
    if "exact worktree is not open" in message:
        return "target_project_not_open"
    if "no open jetbrains project matched" in message:
        return "target_project_not_open"
    if "trusted" in message:
        return "untrusted_auto_open_root"
    if "launch" in message or "open" in message:
        return "ide_open_failed"
    return "inspection_helper_error"


def normalize_reason(value: Any) -> str:
    reason = str(value).strip().lower().replace("-", "_").replace(" ", "_")
    return reason or "inspection_helper_error"


def ide_selection_advice(reason: str, context: Any) -> str | None:
    if not isinstance(context, dict) or reason not in IDE_ROUTE_CONFIGURATION_REASONS:
        return None
    if reason == "ide_config_missing" and not (context.get("inspection_lane") or context.get("inspection_lanes")):
        if context.get("ide") or context.get("ide_config_dir"):
            return "Install the selected JetBrains IDE if needed and launch it once to create its configuration before retrying; ask before changing the selected IDE/version in repository metadata."
        return "Install and launch a JetBrains IDE appropriate for this repository. " + IDE_ROUTE_CONFIGURATION_NEXT_ACTION
    if context.get("inspection_lane") or (context.get("inspection_lanes") and reason != "ide_selection_required"):
        return (
            "Check that the selected IDE matches the intended configured lane and installed IDE/version; install if needed and launch that IDE once "
            "to create its configuration before another assessment or open. "
            "Preserve qualityGate.inspection.lanes in .github/github.json; ask before changing lane policy."
        )
    if context.get("inspection_lanes"):
        return (
            "Use agent-inspect to prepare and inspect the configured lanes. "
            "To open one project without inspecting, use open-worktree --ide <lane IDE> "
            "at the worktree root; for a nested lane project with project markers, "
            "pass that directory as --repo. Assessments resolve lane projectPath automatically. "
            "Preserve qualityGate.inspection.lanes in .github/github.json."
        )
    return None


def hint_for_error_reason(reason: str, context: Any = None) -> str | None:
    if reason == "ide_launch_disabled":
        return "Open the exact worktree in the intended IDE with the Inspection API plugin, pass --ide-launch to start the IDE when none is running, or use --backend native."
    if reason == "ide_launch_refused":
        return "The selected IDE app is running, or could not be proven absent, without a usable Inspection API identity. Enable or update the plugin there and restart it yourself, or use --backend native; the helper never launches or activates a running app."
    lane_advice = ide_selection_advice(reason, context)
    if lane_advice:
        return lane_advice
    return {
        "inspection_api_unavailable": "Open the repo in the configured JetBrains IDE with the inspection plugin installed, or pass --ide-launch when that IDE is not running.",
        "invalid_api_response": "Check the installed inspection plugin version and IDE logs; the helper could not parse the API response.",
        "inspection_api_http_error": "Inspect the API error body and IDE logs for the failing endpoint.",
        "timeout": "Increase the timeout or check whether the IDE is indexing, opening, or blocked by a modal dialog.",
        "worktree_route_mismatch": "Open the exact worktree in the IDE or use inspect-closeout so the helper can claim the correct project.",
        "target_project_not_open": "Use inspect or open-worktree to lifecycle-open the exact worktree, or open that worktree manually in the configured IDE.",
        "untrusted_auto_open_root": "Move the worktree under a trusted auto-open root or update the repo/global trusted roots configuration.",
        "ide_open_failed": "Check the configured JetBrains app name and whether macOS can launch it with open -a.",
        "ide_selection_required": "Add preferred JetBrains IDE metadata to .github/github.json, or pass --ide for this one run.",
        "ide_config_ambiguous": "Add preferred JetBrains IDE metadata to .github/github.json so the helper updates the intended JetBrains config.",
        "ide_config_missing": "Launch the selected JetBrains IDE once, or choose an installed IDE/version in .github/github.json.",
        AGENT_USAGE_ERROR_REASON: "Use only documented agent-inspect arguments and report the mismatch instead of trying another inspection command.",
    }.get(reason)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run JetBrains IDE inspections through the local plugin API.")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON only.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    command_specs = {
        "list-projects": ("List discovered IDE projects without inspecting.", False),
        "resolve-route": ("Resolve an already-open IDE/project route; does not open projects by default.", False),
        "start-inspection": ("Start an inspection run without waiting for results.", True),
        "wait-for-inspection": ("Wait for a previously triggered inspection.", False),
        "get-status": ("Read current route-pinned inspection status.", False),
        "get-problems": ("Fetch current inspection problem details.", True),
        "claim-worktree": ("Claim an already-open exact worktree without opening an IDE.", False),
        "open-worktree": ("Open and claim the exact worktree; does not inspect.", False),
        "inspect-closeout": ("Readiness inspection: open if needed, inspect, and clean up helper-opened projects.", True),
        "inspect": ("Inspect now: open if needed, trigger, wait, fetch problems, and clean up helper-opened projects.", True),
    }
    for name, (help_text, include_scope) in command_specs.items():
        add_common(subparsers.add_parser(name, help=help_text), include_scope=include_scope)
    subparsers.add_parser("cleanup-helper-leases", help="Remove stale local helper lifecycle leases.")
    subparsers.add_parser("summarize-outcomes", help="Summarize helper outcome JSONL logs without inspecting.")
    for name in ("remove-worktree", "cleanup-helper-sdks"):
        command = subparsers.add_parser(name, help="Unregister only SDKs proved helper-owned through the live IDE API.")
        command.add_argument("--json", action="store_true", default=argparse.SUPPRESS)
        command.add_argument("--dry-run", action=argparse.BooleanOptionalAction, default=True)
        command.add_argument("--lifecycle-lock-timeout-ms", type=int, default=DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)
    subparsers.choices["cleanup-helper-sdks"].add_argument("--worktree-path", action="append", help="Exact orphan path from the reviewed dry-run; repeat for each path to apply.")
    subparsers.choices["remove-worktree"].add_argument("--repo", required=True, help="Exact eligible linked worktree to remove; establish cleanup disposition first.")
    subparsers.choices["remove-worktree"].add_argument("--offline-only", action="store_true", help="Use only local cleanup proof without discovering or contacting an IDE; retain the worktree if proof is unavailable.")

    for name in ("wait-for-inspection", "inspect", "inspect-closeout"):
        subparsers.choices[name].add_argument("--timeout-ms", type=int, default=DEFAULT_WAIT_TIMEOUT_MS)
        subparsers.choices[name].add_argument("--poll-ms", type=int, default=DEFAULT_POLL_MS)
    for name in ("open-worktree", "inspect", "inspect-closeout"):
        subparsers.choices[name].set_defaults(open=True)
        subparsers.choices[name].add_argument("--background-open", dest="background_open", action="store_true", default=True, help="Launch the target IDE hidden/background before lifecycle opens. Default for lifecycle opens.")
        subparsers.choices[name].add_argument("--foreground-open", dest="background_open", action="store_false", help="Allow the IDE to take focus while launching.")
        subparsers.choices[name].add_argument("--prepare-timeout-ms", type=int, default=DEFAULT_PREPARE_TIMEOUT_MS)
        subparsers.choices[name].add_argument("--lifecycle-lock-timeout-ms", type=int, default=DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)
        subparsers.choices[name].add_argument("--keep-warm", action="store_true", help="Leave helper-opened projects open after inspect or inspect-closeout.")
    for name in ("open-worktree", "inspect", "inspect-closeout"):
        subparsers.choices[name].add_argument(
            "--repository-preparation-timeout-ms",
            type=int,
            default=DEFAULT_REPOSITORY_PREPARATION_TIMEOUT_MS,
            help="Bound repository preparation separately from IDE lifecycle timeouts.",
        )
        subparsers.choices[name].add_argument(
            "--skip-preparation",
            "--no-repository-preparation",
            dest="skip_preparation",
            action="store_true",
            help="Explicitly bypass automatic repository preparation for this run.",
        )
        subparsers.choices[name].add_argument(
            "--force-preparation",
            "--force-refresh-preparation",
            dest="force_preparation",
            action="store_true",
            help="Ignore a reusable preparation receipt and execute preparation again.",
        )
    subparsers.choices["cleanup-helper-leases"].add_argument("--max-age-ms", type=int, default=24 * 60 * 60 * 1000)
    subparsers.choices["cleanup-helper-leases"].add_argument(
        "--lease-id", action="append", metavar="UUID",
        help="Limit cleanup to one exact stale lease; specify this option only once.",
    )
    subparsers.choices["cleanup-helper-leases"].add_argument("--dry-run", action=argparse.BooleanOptionalAction, default=True)
    subparsers.choices["cleanup-helper-leases"].add_argument("--lifecycle-lock-timeout-ms", type=int, default=DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)
    subparsers.choices["summarize-outcomes"].add_argument("--log", dest="log_path", help="Outcome JSONL log path. Defaults to JB_INSPECT_OUTCOME_LOG or the standard Code log path.")
    subparsers.choices["summarize-outcomes"].add_argument("--limit", type=int, default=10, help="Maximum number of recent events to include.")
    subparsers.choices["summarize-outcomes"].add_argument("--qualification-file", help="Qualification schema v1 JSON file for strict artifact-pinned gating.")
    subparsers.choices["summarize-outcomes"].add_argument("--sample-size", type=int, default=50, help="Required strict qualification sample size. Defaults to 50.")
    for name in ("get-problems", "inspect", "inspect-closeout"):
        subparsers.choices[name].add_argument("--severity", default="all")
        subparsers.choices[name].add_argument("--problem-type", default="all")
        subparsers.choices[name].add_argument("--file-pattern", default="all")
        subparsers.choices[name].add_argument("--limit", type=int, default=100)
        subparsers.choices[name].add_argument("--offset", type=int, default=0)
        subparsers.choices[name].add_argument(
            "--include-stale",
            "--allow-stale",
            dest="include_stale",
            action="store_true",
            help="Return cached stale findings for diagnostics. Stale results still exit non-zero.",
        )
    for name in ("wait-for-inspection", "get-status", "get-problems", "inspect", "inspect-closeout"):
        subparsers.choices[name].add_argument(
            "--allow-text-only-coverage",
            action="store_true",
            help="Allow TextMate/PlainText-only scoped files instead of failing semantic coverage closed.",
        )
    return parser


def add_common(command: argparse.ArgumentParser, include_scope: bool) -> None:
    command.add_argument("--json", action="store_true", default=argparse.SUPPRESS, help="Emit machine-readable JSON only.")
    command.add_argument("--repo", default=".", help="Repo/worktree path to inspect. Defaults to cwd.")
    command.add_argument("--port", type=int, help="Use a specific IDE built-in server port.")
    command.add_argument("--ide", help="Preferred IDE selector, e.g. PyCharm, IntelliJ, WebStorm.")
    command.add_argument("--ide-app", help="Exact macOS application bundle name to launch, e.g. WebStorm 2026.2 EAP. Defaults to --ide.")
    command.add_argument("--ide-channel", choices=("stable", "eap", "any"), help="IDE channel for product-level selection. Defaults to stable for product selectors.")
    command.add_argument("--ide-version", help="Exact IDE version selector, e.g. 2026.2.")
    command.add_argument("--project-key", help="Stable project key returned by resolve-route or list-projects.")
    command.add_argument("--project-path", help="Project root/path selector.")
    command.add_argument("--worktree-path", help="Worktree path selector.")
    command.add_argument("--cwd", help="Cwd selector passed to the route API.")
    command.add_argument("--project", help="Project name selector. Prefer project-key/path when possible.")
    command.add_argument("--session-id", help="Expected IDE session id for drift detection.")
    command.add_argument("--open", action="store_true", help="Open the repo in the preferred IDE if no route is available, then retry briefly.")
    command.add_argument("--ide-launch", dest="ide_launch", action="store_true", help="Allow launching the IDE app when none is running; never launches or activates an app that is already running.")
    command.add_argument("--no-ide-launch", dest="ide_launch", action="store_false", help="Require a running IDE; the default.")
    command.add_argument("--no-worktree-check", action="store_true", help="Allow routes outside the current worktree.")
    if include_scope:
        command.add_argument("--native-project-scope", action="store_true", help="Assess the selected nested lane project with its native scope instead of dispatching the worktree lanes.")
        command.add_argument("--scope", help="Inspection scope. Defaults from repo config or changed_files.")
        command.add_argument("--dir", dest="directory", help="Directory for directory scope.")
        command.add_argument("--file", dest="files", action="append", default=[], help="File for files scope; repeatable.")
        command.add_argument("--include-unversioned", action=argparse.BooleanOptionalAction, default=True)
        command.add_argument("--changed-files-mode", choices=("all", "staged", "unstaged"), default="all")
        command.add_argument("--max-files", type=int)
        command.add_argument("--profile", default="")


def build_context(args: argparse.Namespace) -> dict[str, Any]:
    repo_arg = Path(args.repo).expanduser()
    repo_path = repo_arg if repo_arg.is_absolute() else Path.cwd() / repo_arg
    repo_path = repo_path.resolve()
    worktree_root = git_root(repo_path) or repo_path
    explicit_project_path = repo_path if repo_path != worktree_root and has_project_markers(repo_path) else None
    main_worktree = git_common_worktree(worktree_root)
    config = read_repo_config(worktree_root)
    jetbrains = config.get("jetbrains", {}) if isinstance(config.get("jetbrains"), dict) else {}
    quality = config.get("qualityGate", {}) if isinstance(config.get("qualityGate"), dict) else {}
    inspection = quality.get("inspection", {}) if isinstance(quality.get("inspection"), dict) else {}
    inspection_lanes = parse_inspection_lanes(inspection)
    validate_inspection_lane_project_paths(inspection_lanes, worktree_root)
    repository_preparation = parse_repository_preparation(inspection)

    main_config = jetbrains.get("mainWorktreePath") or jetbrains.get("main_worktree_path")
    if main_config:
        main_worktree = resolve_config_path(main_config, worktree_root)

    open_project_path = jetbrains.get("openProjectPath") or jetbrains.get("open_project_path")
    configured_project_path = resolve_config_path(open_project_path, worktree_root) if open_project_path else None

    ide = args.ide or inspection.get("ide") or jetbrains.get("ide")
    ide_app = getattr(args, "ide_app", None) or jetbrains.get("ideApp") or jetbrains.get("ide_app")
    ide_channel = (
        getattr(args, "ide_channel", None)
        or jetbrains.get("ideChannel")
        or jetbrains.get("ide_channel")
    )
    ide_version = (
        getattr(args, "ide_version", None)
        or jetbrains.get("ideVersion")
        or jetbrains.get("ide_version")
    )
    if hasattr(args, "profile") and not clean_optional(getattr(args, "profile", None)):
        args.profile = str(inspection.get("profile") or "")
    scope = getattr(args, "scope", None) or first_scope(inspection.get("scopePreference")) or first_scope(jetbrains.get("scopePreference")) or "changed_files"
    worktree_strategy = jetbrains.get("worktreeStrategy") or jetbrains.get("worktree_strategy") or "prefer-current"

    lifecycle_target_path = explicit_project_path or configured_project_path or worktree_root

    context = {
        "repo_path": str(repo_path),
        "worktree_root": str(worktree_root),
        "repo_head_sha": git_head_sha(worktree_root),
        "main_worktree": str(main_worktree) if main_worktree else None,
        "project_path": str(lifecycle_target_path),
        "exact_route_path": str(lifecycle_target_path),
        "lifecycle_target_path": str(lifecycle_target_path),
        "ide": ide,
        "ide_app": ide_app,
        "ide_channel": ide_channel,
        "ide_version": str(ide_version) if ide_version else None,
        "scope": scope,
        "worktree_strategy": worktree_strategy,
        "client_run_id": getattr(args, "client_run_id", None),
        "config_path": str(worktree_root / ".github" / "github.json") if (worktree_root / ".github" / "github.json").exists() else None,
        "repository_preparation": bounded_repository_preparation(
            repository_preparation,
            target_worktree=str(worktree_root),
        ),
    }
    if repository_preparation.get("_argv") is not None:
        context["_repository_preparation_argv"] = repository_preparation["_argv"]
    if inspection_lanes:
        context["inspection_lane_schema_version"] = INSPECTION_LANE_SCHEMA_VERSION
        context["inspection_lanes"] = [lane.public() for lane in inspection_lanes]
        context["_inspection_lanes"] = inspection_lanes
    scope_descriptor = canonical_scope_descriptor(args, context, worktree_root)
    context["scope_descriptor"] = scope_descriptor
    context["scope_descriptor_sha256"] = canonical_json_sha256(scope_descriptor)
    selection = resolve_ide_selection(context)
    if selection:
        context["ide_selection"] = selection.public()
        if not context.get("ide") and selection.product:
            context["ide"] = selection.product
        if not context.get("ide_app") and selection.app_name:
            context["ide_app"] = selection.app_name
        if selection.config_dir:
            context["ide_config_dir"] = str(selection.config_dir)
        if selection.app_path:
            context["ide_app_path"] = str(selection.app_path)
    return context


def parse_repository_preparation(inspection: dict[str, Any]) -> dict[str, Any]:
    if "prepare" not in inspection:
        return {
            "configured": False,
            "command": None,
            "source": REPOSITORY_PREPARATION_SOURCE,
            "execution_state": REPOSITORY_PREPARATION_NOT_CONFIGURED,
        }

    raw_prepare = inspection.get("prepare")
    if isinstance(raw_prepare, dict):
        return parse_structured_repository_preparation(raw_prepare, inspection)
    if not isinstance(raw_prepare, str):
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare must be a non-empty command string or supported preparation object when configured."
        )
    command = raw_prepare.strip()
    if not command:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare must be a non-empty command string when configured."
        )
    if len(command) > MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare exceeds the bounded command length."
        )
    if any(ord(character) < 32 and character not in {"\t"} for character in command):
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare must not contain control characters."
        )
    try:
        argv = shlex.split(command, posix=True)
    except ValueError as error:
        raise repository_preparation_config_error(
            f"qualityGate.inspection.prepare is not a valid shell-style command: {error}"
        ) from error
    if not argv:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare must contain at least one command argument."
        )
    generated_state = normalize_repository_preparation_paths(
        inspection.get("requiredGeneratedState", inspection.get("required_generated_state", [])),
        field_name="qualityGate.inspection.requiredGeneratedState",
        maximum=MAX_REPOSITORY_PREPARATION_GENERATED_STATE_PATHS,
    )
    return {
        "configured": True,
        "command": command,
        "source": REPOSITORY_PREPARATION_SOURCE,
        "execution_state": REPOSITORY_PREPARATION_NOT_RUN,
        "required_generated_state": generated_state,
        "_argv": argv,
    }


def parse_structured_repository_preparation(
    raw_prepare: dict[str, Any],
    inspection: dict[str, Any],
) -> dict[str, Any]:
    if set(raw_prepare) != {"python"} or not isinstance(raw_prepare.get("python"), dict):
        raise repository_preparation_config_error(
            "Structured qualityGate.inspection.prepare must contain exactly one 'python' object."
        )
    if "requiredGeneratedState" in inspection or "required_generated_state" in inspection:
        raise repository_preparation_config_error(
            "Structured preparation must place requiredGeneratedState inside qualityGate.inspection.prepare.python."
        )
    python = raw_prepare["python"]
    allowed_keys = {
        "version",
        "moduleName",
        "testRoots",
        "sync",
        "extras",
        "pep723Scripts",
        "projectPath",
        "requiredGeneratedState",
    }
    unknown_keys = sorted(set(python) - allowed_keys)
    if unknown_keys:
        raise repository_preparation_config_error(
            f"Structured Python preparation contains unsupported fields: {', '.join(unknown_keys)}."
        )
    project_path = "."
    if "projectPath" in python:
        try:
            project_path = parse_inspection_lane_project_path(python["projectPath"], "Python preparation")
        except InspectError as error:
            raise repository_preparation_config_error(
                "prepare.python.projectPath must be a safe relative project directory."
            ) from error
        if project_path is None:
            raise repository_preparation_config_error("Python preparation projectPath must name a relative directory.")
    version = python.get("version")
    if not isinstance(version, str) or re.fullmatch(r"\d+\.\d+", version) is None:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare.python.version must use major.minor form, for example '3.13'."
        )
    module_name = python.get("moduleName")
    if (
        not isinstance(module_name, str)
        or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", module_name) is None
    ):
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare.python.moduleName must contain only letters, numbers, '-' or '_'."
        )
    test_roots = normalize_repository_preparation_paths(
        python.get("testRoots", []),
        field_name="qualityGate.inspection.prepare.python.testRoots",
        maximum=MAX_REPOSITORY_PREPARATION_TEST_ROOTS,
    )
    generated_state = normalize_repository_preparation_paths(
        python.get("requiredGeneratedState", []),
        field_name="qualityGate.inspection.prepare.python.requiredGeneratedState",
        maximum=MAX_REPOSITORY_PREPARATION_GENERATED_STATE_PATHS,
    )
    sync = python.get("sync", False)
    if not isinstance(sync, bool):
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare.python.sync must be a boolean."
        )
    scripts = normalize_repository_preparation_paths(
        python.get("pep723Scripts", []),
        field_name="qualityGate.inspection.prepare.python.pep723Scripts",
        maximum=MAX_REPOSITORY_PREPARATION_PEP723_SCRIPTS,
    )
    if scripts and sync:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare.python.pep723Scripts and sync are separate dependency sources."
        )
    extras = python.get("extras", [])
    if not isinstance(extras, list) or len(extras) > MAX_REPOSITORY_PREPARATION_EXTRAS:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare.python.extras must be a bounded array."
        )
    normalized_extras: list[str] = []
    for extra in extras:
        if not isinstance(extra, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", extra) is None:
            raise repository_preparation_config_error(
                "qualityGate.inspection.prepare.python.extras must contain valid package extra names."
            )
        normalized_extras.append(extra)
    if normalized_extras and not sync:
        raise repository_preparation_config_error(
            "qualityGate.inspection.prepare.python.extras requires sync=true."
        )
    script = Path(__file__).resolve().parent / "prepare-python-project.py"
    argv = [
        "uv",
        "run",
        str(script),
        "--repo",
        project_path,
        "--python",
        version,
        "--module-name",
        module_name,
    ]
    for test_root in test_roots:
        argv.extend(("--test-root", test_root))
    if sync:
        argv.append("--sync")
    for extra in normalized_extras:
        argv.extend(("--extra", extra))
    for script_path in scripts:
        argv.extend(("--pep723-script", script_path))
    validate_script_preparation_command(argv)
    generated_state = list(dict.fromkeys([
        *generated_state, ".venv", f".idea/{module_name}.iml", ".idea/modules.xml", ".idea/misc.xml",
    ]))
    generated_state = [(Path(project_path) / path).as_posix() for path in generated_state]
    return {
        "configured": True,
        "command": shlex.join(argv),
        "source": REPOSITORY_PREPARATION_SOURCE,
        "execution_state": REPOSITORY_PREPARATION_NOT_RUN,
        "kind": "python",
        "required_generated_state": generated_state,
        "_argv": argv,
    }


def validate_script_preparation_command(argv: list[str]) -> None:
    if "--pep723-script" in argv and len(shlex.join(argv)) > MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH:
        error = repository_preparation_config_error(
            "The PEP 723 preparation command exceeds the command reporting limit. "
            "Choose fewer representative scripts covering the inspected dependencies, or shorten their paths, "
            "so the complete manual rerun command, including the selected IDE SDK-table path, can be reported."
        )
        error.payload["next_action"] = (
            "Reduce the representative script set or shorten its paths; the selected IDE SDK-table path "
            "also counts toward the command reporting bound."
        )
        raise error


def normalize_repository_preparation_paths(
    generated_state: Any,
    *,
    field_name: str,
    maximum: int,
) -> list[str]:
    if generated_state is None:
        generated_state = []
    if not isinstance(generated_state, list) or len(generated_state) > maximum:
        raise repository_preparation_config_error(
            f"{field_name} must be a bounded array of relative paths."
        )
    normalized_generated_state: list[str] = []
    for value in generated_state:
        if not isinstance(value, str) or not value.strip():
            raise repository_preparation_config_error(
                f"{field_name} must contain non-empty relative paths."
            )
        path = Path(value.strip())
        if path.is_absolute() or ".." in path.parts:
            raise repository_preparation_config_error(
                f"{field_name} paths must stay inside the target worktree."
            )
        normalized_generated_state.append(path.as_posix())
    return normalized_generated_state


def repository_preparation_config_error(message: str) -> InspectError:
    return InspectError(
        message,
        2,
        {
            "error_reason": "repository_preparation_config_invalid",
            "failure_phase": "configuration",
            "repository_preparation": {
                "configured": False,
                "command": None,
                "source": REPOSITORY_PREPARATION_SOURCE,
                "execution_state": REPOSITORY_PREPARATION_NOT_CONFIGURED,
                "configuration_status": "invalid",
            },
            "next_action": (
                "Fix qualityGate.inspection.prepare in .github/github.json so it is a valid non-empty command, "
                "then rerun the inspection command."
            ),
        },
    )


def repository_preparation_target(context: dict[str, Any]) -> Path:
    value = context.get("worktree_root") or context.get("repo_path")
    if not isinstance(value, str) or not value.strip():
        raise InspectError(
            "Repository preparation cannot determine the exact target worktree.",
            3,
            {"error_reason": "repository_preparation_untrusted", "failure_phase": "preparation_policy"},
        )
    return Path(value).expanduser().resolve()


def repository_preparation_sdk_table(context: dict[str, Any]) -> Path | None:
    config_dir = context.get("ide_config_dir")
    lanes = context.get("_repository_preparation_lanes") or configured_inspection_lanes(context)
    if lanes:
        python_lanes = [
            lane for lane in lanes
            if (product := product_for_selector(lane.ide)) is not None and product.key == "pycharm"
        ]
        argv = context.get("_repository_preparation_argv", [])
        project_path = argv[argv.index("--repo") + 1] if "--repo" in argv else None
        matching = [lane for lane in python_lanes if lane.project_path == project_path] if project_path else []
        candidates = matching or python_lanes or lanes
        if len(candidates) != 1:
            return None
        selection = resolve_ide_selection({"ide": candidates[0].ide})
        config_dir = selection.config_dir if selection else None
    override = os.environ.get("JETBRAINS_INSPECTION_IDE_CONFIG_DIR")
    if override:
        config_dir = Path(override).expanduser().resolve()
    return Path(config_dir) / "options" / "jdk.table.xml" if config_dir else None


def repository_preparation_command_hash(argv: list[str], target: Path | None = None) -> str:
    helper_sha256 = None
    sdk_table_sha256 = None
    script_hashes = {}
    if len(argv) >= 3 and Path(argv[2]).name == "prepare-python-project.py":
        project = target or Path.cwd()
        if "--repo" in argv:
            project = project / argv[argv.index("--repo") + 1]
        try:
            helper_sha256 = hashlib.sha256(Path(argv[2]).read_bytes()).hexdigest()
        except OSError:
            helper_sha256 = "unavailable"
        for index, argument in enumerate(argv[:-1]):
            if argument == "--pep723-script":
                script_path = argv[index + 1]
                try:
                    script_hashes[script_path] = hashlib.sha256(
                        (project / script_path).read_bytes()
                    ).hexdigest()
                except OSError:
                    script_hashes[script_path] = "unavailable"
        if "--sdk-table" in argv:
            try:
                sdk_table = Path(argv[argv.index("--sdk-table") + 1])
                sdk_table_sha256 = hashlib.sha256(sdk_table.read_bytes()).hexdigest()
            except (OSError, IndexError):
                sdk_table_sha256 = "unavailable"
    return text_sha256(
        json.dumps(
            {"argv": argv, "helper_sha256": helper_sha256, "sdk_table_sha256": sdk_table_sha256,
             "pep723_script_sha256": script_hashes},
            sort_keys=True,
            separators=(",", ":"),
        )
    )


def repository_preparation_config_hash(context: dict[str, Any]) -> str:
    configured_path = context.get("config_path")
    if isinstance(configured_path, str) and configured_path:
        path = Path(configured_path).expanduser().resolve()
        try:
            return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError:
            pass
    preparation = context.get("repository_preparation")
    if not isinstance(preparation, dict):
        preparation = {}
    return text_sha256(
        json.dumps(
            {
                "command": preparation.get("command"),
                "required_generated_state": preparation.get("required_generated_state", []),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )


def repository_preparation_receipt_path(context: dict[str, Any]) -> Path:
    target = repository_preparation_target(context)
    return cache_dir() / REPOSITORY_PREPARATION_RECEIPT_DIRNAME / f"{stable_value_hash(str(target))}.json"


def repository_preparation_generated_state(context: dict[str, Any]) -> list[str]:
    preparation = context.get("repository_preparation")
    if not isinstance(preparation, dict):
        preparation = {}
    values = preparation.get("required_generated_state")
    if not isinstance(values, list):
        return []
    return [str(value) for value in values if isinstance(value, str) and value.strip()]


def repository_preparation_generated_state_snapshot(context: dict[str, Any]) -> dict[str, Any]:
    target = repository_preparation_target(context)
    entries: list[dict[str, Any]] = []
    for relative in repository_preparation_generated_state(context):
        path = (target / relative).resolve()
        if not path.is_relative_to(target):
            entries.append({"path": relative, "exists": False, "reason": "outside_worktree"})
            continue
        try:
            stat = path.stat()
        except OSError:
            entries.append({"path": relative, "exists": False})
            continue
        entry: dict[str, Any] = {
            "path": relative,
            "exists": True,
            "kind": "directory" if path.is_dir() else "file" if path.is_file() else "other",
            "size": stat.st_size if path.is_file() else None,
        }
        if path.is_file():
            try:
                entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            except OSError:
                entry.update(exists=False, reason="unreadable")
        entries.append({key: value for key, value in entry.items() if value is not None})
    return {"paths": entries, "all_present": all(entry.get("exists") is True for entry in entries)}


def repository_preparation_sensitive_values(environment: dict[str, str], argv: list[str]) -> list[str]:
    values = {
        value
        for key, value in environment.items()
        if is_sensitive_key(key) and isinstance(value, str) and len(value) >= 4
    }
    redact_next = False
    for argument in argv:
        if redact_next:
            if len(argument) >= 4:
                values.add(argument)
            redact_next = False
            continue
        if argument.startswith("--") and "=" in argument:
            name, value = argument.split("=", 1)
            if is_sensitive_key(name) and len(value) >= 4:
                values.add(value)
        elif argument.startswith("--") and is_sensitive_key(argument):
            redact_next = True
    return sorted(values, key=len, reverse=True)


def repository_preparation_output(
    value: bytes | str | None,
    environment: dict[str, str],
    argv: list[str],
) -> str:
    if isinstance(value, bytes):
        text = value.decode("utf-8", errors="replace")
    else:
        text = str(value or "")
    for sensitive_value in repository_preparation_sensitive_values(environment, argv):
        text = text.replace(sensitive_value, REDACTED)
    text = SENSITIVE_OUTPUT_BEARER_PATTERN.sub(lambda match: f"{match.group(1)}{REDACTED}", text)
    text = SENSITIVE_OUTPUT_ASSIGNMENT_PATTERN.sub(
        lambda match: f"{match.group(1)}{match.group(2)}{REDACTED}",
        text,
    )
    text = redact_durable_text(text)
    if len(text) <= MAX_REPOSITORY_PREPARATION_OUTPUT_LENGTH:
        return text
    return text[:MAX_REPOSITORY_PREPARATION_OUTPUT_LENGTH] + "\n<output truncated>"


def repository_preparation_is_trusted(context: dict[str, Any]) -> tuple[bool, list[str]]:
    target = repository_preparation_target(context)
    roots = [Path(root).expanduser().resolve() for root in trusted_auto_open_roots()]
    return any(target == root or target.is_relative_to(root) for root in roots), [str(root) for root in roots]


def repository_preparation_receipt_public(receipt: dict[str, Any]) -> dict[str, Any]:
    return redact_durable_log(
        {
            key: value
            for key, value in receipt.items()
            if key not in {"stdout", "stderr"} or isinstance(value, str)
        }
    )


def read_repository_preparation_receipt(context: dict[str, Any]) -> dict[str, Any] | None:
    path = repository_preparation_receipt_path(context)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def write_repository_preparation_receipt(context: dict[str, Any], receipt: dict[str, Any]) -> Path:
    path = repository_preparation_receipt_path(context)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".json.tmp")
    temp.write_text(public_json(repository_preparation_receipt_public(receipt)), encoding="utf-8")
    temp.replace(path)
    return path


def repository_preparation_receipt_matches(
    receipt: dict[str, Any] | None,
    context: dict[str, Any],
    argv: list[str],
    status_snapshot: dict[str, Any],
    generated_state: dict[str, Any],
) -> bool:
    if not isinstance(receipt, dict) or receipt.get("status") != REPOSITORY_PREPARATION_SUCCEEDED:
        return False
    target = repository_preparation_target(context)
    return (
        receipt.get("schema_version") == REPOSITORY_PREPARATION_RECEIPT_SCHEMA_VERSION
        and receipt.get("command_sha256") == repository_preparation_command_hash(argv, target)
        and receipt.get("config_sha256") == repository_preparation_config_hash(context)
        and receipt.get("worktree_identity_hash") == stable_value_hash(str(target))
        and receipt.get("post_git_snapshot_sha256") == canonical_json_sha256(status_snapshot)
        and receipt.get("generated_state_sha256") == canonical_json_sha256(generated_state)
        and generated_state.get("all_present") is True
    )


def repository_preparation_error(
    reason: str,
    message: str,
    preparation: dict[str, Any],
    receipt: dict[str, Any] | None = None,
) -> InspectError:
    payload: dict[str, Any] = {
        "error_reason": reason,
        "failure_phase": "repository_preparation",
        "repository_preparation": preparation,
        "next_action": repository_preparation_next_action(reason, preparation),
    }
    if receipt is not None:
        payload["repository_preparation_receipt"] = receipt
    return InspectError(message, 3, payload)


def repository_preparation_next_action(reason: str, preparation: dict[str, Any]) -> str:
    command = preparation.get("command") or "the configured preparation command"
    target = preparation.get("target_worktree") or "the exact target worktree"
    if reason == "repository_preparation_untrusted":
        return f"Run `{command}` manually in `{target}`, then rerun with --skip-preparation, or move the worktree under a trusted root."
    if reason == "repository_preparation_opted_out":
        return f"Run `{command}` manually in `{target}`, then rerun with --skip-preparation if the bypass is intentional."
    if reason == "repository_preparation_recursion":
        return "Remove the recursive jb-inspect invocation from qualityGate.inspection.prepare and rerun."
    if reason == "repository_preparation_runtime_unavailable":
        return "Install `uv` and restore the bundled `prepare-python-project.py` helper before rerunning repository preparation."
    if reason == "repository_preparation_generated_state_missing":
        return f"Run `{command}` in `{target}` and ensure all required generated state exists before rerunning."
    return f"Fix repository preparation in `{target}`, then rerun the inspection command."


def run_repository_preparation(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    state = context.get("repository_preparation")
    if not isinstance(state, dict):
        state = {}
    if state.get("configured") is not True:
        return bounded_repository_preparation(state, target_worktree=str(repository_preparation_target(context)))
    target = repository_preparation_target(context)
    preparation = bounded_repository_preparation(state, target_worktree=str(target))
    argv = context.get("_repository_preparation_argv")
    if not isinstance(argv, list) or not argv:
        raise repository_preparation_config_error("Repository preparation command validation did not produce argv.")
    if state.get("kind") == "python":
        if "--repo" in argv:
            project = (target / argv[argv.index("--repo") + 1]).resolve()
            if not project.is_relative_to(target) or not project.is_dir() or (argv[argv.index("--repo") + 1] != "." and project == target):
                preparation["execution_state"] = "blocked"
                raise repository_preparation_error(
                    "repository_preparation_project_unavailable",
                    "Restore the configured Python project directory inside the exact worktree; no preparation ran.",
                    preparation,
                )
            if project != target:
                for relative in repository_preparation_generated_state(context):
                    if not (target / relative).resolve().is_relative_to(project):
                        preparation["execution_state"] = "blocked"
                        raise repository_preparation_error(
                            "repository_preparation_project_unavailable",
                            "Restore generated-state paths inside the nested Python project; no preparation ran.",
                            preparation,
                        )
        sdk_table = repository_preparation_sdk_table(context)
        if sdk_table is not None:
            argv = [*argv, "--sdk-table", str(sdk_table)]
            preparation["command"] = shlex.join(argv)
    preparation["command_sha256"] = repository_preparation_command_hash(argv, target)
    preparation["config_sha256"] = repository_preparation_config_hash(context)
    preparation["worktree_identity_hash"] = stable_value_hash(str(target))
    preparation["required_generated_state"] = repository_preparation_generated_state(context)
    if os.environ.get(REPOSITORY_PREPARATION_ACTIVE_ENV) == "1":
        preparation["execution_state"] = "blocked"
        preparation["failure_reason"] = "repository_preparation_recursion"
        raise repository_preparation_error(
            "repository_preparation_recursion",
            "Repository preparation refused a recursive inspection-helper invocation.",
            preparation,
        )
    if getattr(args, "skip_preparation", False):
        preparation["execution_state"] = REPOSITORY_PREPARATION_SKIPPED
        preparation["skip_reason"] = "explicit_opt_out"
        preparation["authorized_opt_out"] = True
        receipt_path = repository_preparation_receipt_path(context)
        if receipt_path.exists():
            preparation["receipt_path"] = str(receipt_path)
        return preparation

    trusted, trusted_roots = repository_preparation_is_trusted(context)
    if not trusted:
        validate_script_preparation_command(argv)
        preparation["execution_state"] = "blocked"
        preparation["failure_reason"] = "repository_preparation_untrusted"
        preparation["trusted_root_count"] = len(trusted_roots)
        receipt = {
            "schema_version": REPOSITORY_PREPARATION_RECEIPT_SCHEMA_VERSION,
            "status": "blocked",
            "reason": "repository_preparation_untrusted",
            "command": preparation.get("command"),
            "command_sha256": preparation["command_sha256"],
            "config_sha256": preparation["config_sha256"],
            "worktree_identity_hash": preparation["worktree_identity_hash"],
            "duration_ms": 0,
            "exit_status": None,
            "stdout": "",
            "stderr": "",
        }
        receipt_path = write_repository_preparation_receipt(context, receipt)
        preparation["receipt_path"] = str(receipt_path)
        raise repository_preparation_error(
            "repository_preparation_untrusted",
            "Repository preparation is configured but the exact worktree is outside trusted roots; no repository-controlled command was executed.",
            preparation,
            receipt,
        )

    before = git_worktree_status_snapshot(target)
    generated_before = repository_preparation_generated_state_snapshot(context)
    receipt = read_repository_preparation_receipt(context)
    if not getattr(args, "force_preparation", False) and repository_preparation_receipt_matches(receipt, context, argv, before, generated_before):
        preparation["execution_state"] = REPOSITORY_PREPARATION_REUSED
        preparation["receipt_reused"] = True
        preparation["receipt_path"] = str(repository_preparation_receipt_path(context))
        preparation["generated_state_snapshot"] = generated_before
        return preparation

    validate_script_preparation_command(argv)

    if state.get("kind") == "python" and (
        shutil.which(str(argv[0])) is None or len(argv) < 3 or not Path(argv[2]).is_file()
    ):
        preparation["execution_state"] = "blocked"
        preparation["failure_reason"] = "repository_preparation_runtime_unavailable"
        raise repository_preparation_error(
            "repository_preparation_runtime_unavailable",
            "Structured Python repository preparation requires uv and the bundled prepare-python-project.py helper.",
            preparation,
        )

    timeout_ms = min(
        MAX_REPOSITORY_PREPARATION_TIMEOUT_MS,
        max(1, int(getattr(args, "repository_preparation_timeout_ms", DEFAULT_REPOSITORY_PREPARATION_TIMEOUT_MS))),
    )
    environment = os.environ.copy()
    environment[REPOSITORY_PREPARATION_ACTIVE_ENV] = "1"
    started = monotonic_ms()
    timed_out = False
    try:
        process = subprocess.Popen(
            argv,
            cwd=target,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
            start_new_session=os.name == "posix",
        )
    except OSError as error:
        stdout = ""
        stderr = repository_preparation_output(str(error), environment, argv)
        completed = subprocess.CompletedProcess(argv, 127, b"", str(error).encode("utf-8", errors="replace"))
    else:
        try:
            raw_stdout, raw_stderr = process.communicate(timeout=timeout_ms / 1000.0)
        except subprocess.TimeoutExpired:
            timed_out = True
            terminate_repository_preparation_process(process)
            try:
                raw_stdout, raw_stderr = process.communicate(timeout=5.0)
            except subprocess.TimeoutExpired as error:
                terminate_repository_preparation_process(process)
                raw_stdout = error.stdout or b""
                raw_stderr = error.stderr or b""
        completed = subprocess.CompletedProcess(argv, process.returncode, raw_stdout, raw_stderr)
        stdout = repository_preparation_output(raw_stdout, environment, argv)
        stderr = repository_preparation_output(raw_stderr, environment, argv)

    after = git_worktree_status_snapshot(target)
    generated_after = repository_preparation_generated_state_snapshot(context)
    mutation = summarize_worktree_mutations(before, after)
    index_changed = before.get("index_sha256") != after.get("index_sha256")
    duration_ms = max(0, monotonic_ms() - started)
    exit_status = None if timed_out else completed.returncode
    reason = None
    if timed_out:
        reason = "repository_preparation_timeout"
    elif mutation.get("tracked_change_count", 0) > 0:
        reason = "repository_preparation_tracked_mutation"
    elif index_changed:
        reason = "repository_preparation_index_mutation"
    elif exit_status != 0:
        reason = "repository_preparation_command_failed"
    elif not generated_after.get("all_present", True):
        reason = "repository_preparation_generated_state_missing"
    execution_state = REPOSITORY_PREPARATION_SUCCEEDED if reason is None else "failed"
    preparation.update(
        {
            "execution_state": execution_state,
            "failure_reason": reason,
            "duration_ms": duration_ms,
            "exit_status": exit_status,
            "stdout": stdout,
            "stderr": stderr,
            "generated_state_snapshot": generated_after,
            "git_mutation": mutation,
            "index_mutation_detected": index_changed,
        }
    )
    receipt = {
        "schema_version": REPOSITORY_PREPARATION_RECEIPT_SCHEMA_VERSION,
        "status": execution_state,
        "reason": reason,
        "command": preparation.get("command"),
        "command_sha256": preparation["command_sha256"],
        "config_sha256": preparation["config_sha256"],
        "worktree_identity_hash": preparation["worktree_identity_hash"],
        "duration_ms": duration_ms,
        "exit_status": exit_status,
        "stdout": stdout,
        "stderr": stderr,
        "pre_git_snapshot": before,
        "post_git_snapshot": after,
        "post_git_snapshot_sha256": canonical_json_sha256(after),
        "generated_state": generated_after,
        "generated_state_sha256": canonical_json_sha256(generated_after),
    }
    receipt_path = write_repository_preparation_receipt(context, receipt)
    preparation["receipt_path"] = str(receipt_path)
    if reason is not None:
        raise repository_preparation_error(
            reason,
            f"Repository preparation blocked inspection: {reason}.",
            preparation,
            receipt,
        )
    return preparation


def terminate_repository_preparation_process(process: subprocess.Popen[bytes]) -> None:
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except ProcessLookupError:
        return
    except OSError:
        try:
            process.kill()
        except OSError:
            return


def parse_inspection_lanes(inspection: dict[str, Any]) -> tuple[InspectionLane, ...]:
    raw_lanes = inspection.get("lanes")
    if raw_lanes is None:
        return ()
    if not isinstance(raw_lanes, list) or not raw_lanes:
        raise inspection_lane_config_error("qualityGate.inspection.lanes must be a non-empty array when configured.")

    lanes: list[InspectionLane] = []
    lane_ids: set[str] = set()
    for index, raw_lane in enumerate(raw_lanes):
        if not isinstance(raw_lane, dict):
            raise inspection_lane_config_error(f"Inspection lane at index {index} must be an object.")
        raw_lane_id = raw_lane.get("id")
        lane_id = clean_optional(raw_lane_id) if isinstance(raw_lane_id, str) else None
        if lane_id is None or INSPECTION_LANE_ID_PATTERN.fullmatch(lane_id) is None:
            raise inspection_lane_config_error(
                f"Inspection lane at index {index} must have an id containing only letters, numbers, '.', '_', or '-'."
            )
        if lane_id in lane_ids:
            raise inspection_lane_config_error(f"Inspection lane id is duplicated: {lane_id}")
        lane_ids.add(lane_id)

        raw_ide = raw_lane.get("ide")
        ide = clean_optional(raw_ide) if isinstance(raw_ide, str) else None
        if ide is None:
            raise inspection_lane_config_error(f"Inspection lane {lane_id} must name an ide.")
        required = raw_lane.get("required", True)
        if not isinstance(required, bool):
            raise inspection_lane_config_error(f"Inspection lane {lane_id} required must be true or false.")
        include = parse_inspection_lane_patterns(raw_lane.get("include"), lane_id, "include", required=True)
        exclude = parse_inspection_lane_patterns(raw_lane.get("exclude", []), lane_id, "exclude", required=False)
        project_path = parse_inspection_lane_project_path(raw_lane.get("projectPath"), lane_id)
        unsupported = sorted(set(raw_lane) - {"id", "ide", "required", "include", "exclude", "projectPath"})
        if unsupported:
            raise inspection_lane_config_error(
                f"Inspection lane {lane_id} has unsupported fields: {', '.join(unsupported)}"
            )
        lanes.append(
            InspectionLane(
                lane_id=lane_id,
                ide=ide,
                required=required,
                include=include,
                exclude=exclude,
                project_path=project_path,
            )
        )
    return tuple(lanes)


def parse_inspection_lane_project_path(value: Any, lane_id: str) -> str | None:
    if value is None:
        return None
    project_path = clean_optional(value) if isinstance(value, str) else None
    if project_path is None:
        raise inspection_lane_config_error(f"Inspection lane {lane_id} projectPath must be a non-empty string.")
    normalized = project_path.replace("\\", "/")
    segments = normalized.split("/")
    drive_path = re.match(r"^[A-Za-z]:/", normalized) is not None
    if (
        project_path != normalized
        or len(normalized) > 1024
        or normalized.startswith(("/", "~/"))
        or drive_path
        or "." in segments
        or ".." in segments
        or "" in segments
        or "\x00" in normalized
        or any(character in normalized for character in "*?[]")
    ):
        raise inspection_lane_config_error(
            f"Inspection lane {lane_id} projectPath must be a safe repository-relative POSIX directory: {project_path}"
        )
    return normalized


def validate_inspection_lane_project_paths(lanes: tuple[InspectionLane, ...], worktree_root: Path) -> None:
    for lane in lanes:
        if lane.project_path is None:
            continue
        resolved = (worktree_root / lane.project_path).resolve()
        if resolved != worktree_root and not resolved.is_relative_to(worktree_root):
            raise inspection_lane_config_error(
                f"Inspection lane {lane.lane_id} projectPath resolves outside the exact worktree: {lane.project_path}"
            )


def parse_inspection_lane_patterns(
    value: Any,
    lane_id: str,
    field: str,
    required: bool,
) -> tuple[str, ...]:
    if not isinstance(value, list) or (required and not value):
        qualifier = "a non-empty array" if required else "an array"
        raise inspection_lane_config_error(f"Inspection lane {lane_id} {field} must be {qualifier} of patterns.")
    patterns: list[str] = []
    for raw_pattern in value:
        pattern = clean_optional(raw_pattern) if isinstance(raw_pattern, str) else None
        if pattern is None:
            raise inspection_lane_config_error(f"Inspection lane {lane_id} {field} contains an empty pattern.")
        validate_inspection_lane_pattern(pattern, lane_id, field)
        patterns.append(pattern)
    return tuple(patterns)


def validate_inspection_lane_pattern(pattern: str, lane_id: str, field: str) -> None:
    normalized = pattern.replace("\\", "/")
    drive_path = re.match(r"^[A-Za-z]:/", normalized) is not None
    segments = normalized.split("/")
    if (
        pattern != normalized
        or normalized.startswith(("/", "~/"))
        or drive_path
        or ".." in segments
        or "." in segments
        or "" in segments
        or "\x00" in pattern
    ):
        raise inspection_lane_config_error(
            f"Inspection lane {lane_id} {field} pattern must be a safe repository-relative POSIX glob: {pattern}"
        )
    if pattern.count("[") != pattern.count("]"):
        raise inspection_lane_config_error(f"Inspection lane {lane_id} {field} pattern has unbalanced brackets: {pattern}")


def inspection_lane_config_error(message: str) -> InspectError:
    return InspectError(
        message,
        2,
        {
            "error_reason": "inspection_lane_config_invalid",
            "failure_phase": "configuration",
            "next_action": "Fix qualityGate.inspection.lanes in .github/github.json, then rerun the inspection command.",
        },
    )


def canonical_scope_descriptor(args: argparse.Namespace, context: dict[str, Any], worktree_root: Path) -> dict[str, Any]:
    file_hashes = (
        stable_value_hash(canonical_scope_path(file_path, worktree_root))
        for file_path in (getattr(args, "files", []) or [])
        if clean_optional(file_path)
    )
    files = sorted({file_hash for file_hash in file_hashes if file_hash is not None})
    directory = clean_optional(getattr(args, "directory", None))
    descriptor: dict[str, Any] = {
        "scope": str(context.get("scope") or "changed_files").strip().lower(),
        "include_unversioned": bool(getattr(args, "include_unversioned", True)),
        "changed_files_mode": str(getattr(args, "changed_files_mode", "all") or "all").strip().lower(),
        "profile": str(getattr(args, "profile", "") or ""),
        "severity": str(getattr(args, "severity", "all") or "all").strip().lower(),
        "problem_type": str(getattr(args, "problem_type", "all") or "all").strip().lower(),
        "file_pattern": durable_scope_value(getattr(args, "file_pattern", "all") or "all"),
        "allow_text_only_coverage": bool(getattr(args, "allow_text_only_coverage", False)),
        "include_stale": bool(getattr(args, "include_stale", False)),
    }
    if directory:
        descriptor["directory_hash"] = stable_value_hash(canonical_scope_path(directory, worktree_root))
    if files:
        descriptor["file_hashes"] = files
    max_files = getattr(args, "max_files", None)
    if max_files is not None:
        descriptor["max_files"] = int(max_files)
    if hasattr(args, "limit"):
        descriptor["limit"] = int(getattr(args, "limit", 100))
    if hasattr(args, "offset"):
        descriptor["offset"] = int(getattr(args, "offset", 0))
    return descriptor


def canonical_scope_path(value: Any, worktree_root: Path) -> str:
    path = Path(str(value)).expanduser()
    if not path.is_absolute():
        path = worktree_root / path
    return str(path.resolve())


def configured_inspection_lanes(context: dict[str, Any]) -> tuple[InspectionLane, ...]:
    lanes = context.get("_inspection_lanes")
    if isinstance(lanes, tuple) and all(isinstance(lane, InspectionLane) for lane in lanes):
        return lanes
    return ()


def resolve_inspection_lane_selection(
    args: argparse.Namespace,
    context: dict[str, Any],
) -> dict[str, Any]:
    lanes = configured_inspection_lanes(context)
    if not lanes:
        return {}
    worktree_root = Path(str(context.get("worktree_root"))).expanduser().resolve()
    scope = str(context.get("scope") or "changed_files").strip().lower()
    raw_paths = inspection_scope_paths(args, worktree_root, scope)
    selected_files: list[dict[str, str]] = []
    skipped_files: list[dict[str, str]] = []
    seen: set[str] = set()
    for raw_path in raw_paths:
        normalized = normalize_inspection_lane_path(raw_path, worktree_root)
        relative_path = normalized.relative_to(worktree_root).as_posix()
        if relative_path in seen:
            continue
        seen.add(relative_path)
        if not normalized.exists():
            skipped_files.append({"file": relative_path, "reason": "missing_or_deleted"})
            continue
        if not normalized.is_file():
            skipped_files.append({"file": relative_path, "reason": "not_a_file"})
            continue
        selected_files.append({"file": relative_path, "absolute_path": str(normalized)})

    max_files = getattr(args, "max_files", None)
    if max_files is not None and len(selected_files) > int(max_files):
        raise InspectError(
            f"Resolved lane scope contains {len(selected_files)} files, exceeding --max-files={max_files}.",
            2,
            {
                "error_reason": "inspection_lane_scope_too_large",
                "failure_phase": "scope_resolution",
                "selected_file_count": len(selected_files),
                "max_files": int(max_files),
            },
        )

    lane_files: dict[str, list[dict[str, str]]] = {lane.lane_id: [] for lane in lanes}
    excluded_files: list[dict[str, str]] = []
    explicit_exclusion_overrides: list[dict[str, str]] = []
    unmatched_files: list[str] = []
    for selected in selected_files:
        relative_path = selected["file"]
        matched_lane: InspectionLane | None = None
        for lane in lanes:
            if any(inspection_lane_pattern_matches(relative_path, pattern) for pattern in lane.include):
                matched_lane = lane
                break
        if matched_lane is None:
            unmatched_files.append(relative_path)
            continue
        excluding_pattern = next(
            (
                pattern
                for pattern in matched_lane.exclude
                if inspection_lane_pattern_matches(relative_path, pattern)
            ),
            None,
        )
        if excluding_pattern is not None and scope != "files":
            excluded_files.append(
                {
                    "file": relative_path,
                    "lane_id": matched_lane.lane_id,
                    "pattern": excluding_pattern,
                }
            )
            continue
        if excluding_pattern is not None:
            explicit_exclusion_overrides.append(
                {
                    "file": relative_path,
                    "lane_id": matched_lane.lane_id,
                    "pattern": excluding_pattern,
                }
            )
        lane_files[matched_lane.lane_id].append(selected)

    return {
        "schema_version": INSPECTION_LANE_SCHEMA_VERSION,
        "scope": scope,
        "selected_file_count": len(selected_files),
        "selected_files": [selected["file"] for selected in selected_files],
        "skipped_files": skipped_files,
        "excluded_files": excluded_files,
        "explicit_exclusion_overrides": explicit_exclusion_overrides,
        "unmatched_files": unmatched_files,
        "lane_files": lane_files,
    }


def inspection_scope_paths(args: argparse.Namespace, worktree_root: Path, scope: str) -> list[str]:
    if scope == "files":
        files = [str(path) for path in (getattr(args, "files", []) or []) if clean_optional(path)]
        if not files:
            raise InspectError(
                "files scope requires at least one --file argument when inspection lanes are configured.",
                2,
                {"error_reason": "inspection_lane_scope_empty", "failure_phase": "scope_resolution"},
            )
        return files
    if scope == "changed_files":
        return changed_inspection_scope_paths(args, worktree_root)
    if scope in {"directory", "whole_project"}:
        pathspec: list[str] = []
        if scope == "directory":
            directory = clean_optional(getattr(args, "directory", None))
            if directory is None:
                raise InspectError(
                    "directory scope requires --dir when inspection lanes are configured.",
                    2,
                    {"error_reason": "inspection_lane_scope_empty", "failure_phase": "scope_resolution"},
                )
            normalized_directory = normalize_inspection_lane_path(directory, worktree_root)
            pathspec = [normalized_directory.relative_to(worktree_root).as_posix()]
        command = ["git", "-C", str(worktree_root), "ls-files", "-z", "--cached"]
        if getattr(args, "include_unversioned", True):
            command.extend(["--others", "--exclude-standard"])
        if pathspec:
            command.extend(["--", *pathspec])
        return git_null_paths(command, worktree_root)
    raise InspectError(
        f"Inspection lanes do not support scope: {scope}",
        2,
        {
            "error_reason": "inspection_lane_scope_unsupported",
            "failure_phase": "scope_resolution",
            "scope": scope,
        },
    )


def changed_inspection_scope_paths(args: argparse.Namespace, worktree_root: Path) -> list[str]:
    mode = str(getattr(args, "changed_files_mode", "all") or "all").strip().lower()
    commands: list[list[str]] = []
    if mode in {"all", "staged"}:
        commands.append(
            [
                "git",
                "-C",
                str(worktree_root),
                "diff",
                "--cached",
                "--name-only",
                "-z",
                "--diff-filter=ACMRTUXB",
            ]
        )
    if mode in {"all", "unstaged"}:
        commands.append(
            [
                "git",
                "-C",
                str(worktree_root),
                "diff",
                "--name-only",
                "-z",
                "--diff-filter=ACMRTUXB",
            ]
        )
    paths: list[str] = []
    for command in commands:
        paths.extend(git_null_paths(command, worktree_root))
    if getattr(args, "include_unversioned", True) and mode in {"all", "unstaged"}:
        paths.extend(
            git_null_paths(
                ["git", "-C", str(worktree_root), "ls-files", "--others", "--exclude-standard", "-z"],
                worktree_root,
            )
        )
    return paths


def git_null_paths(command: list[str], worktree_root: Path) -> list[str]:
    try:
        completed = subprocess.run(command, check=True, capture_output=True)
    except subprocess.CalledProcessError as error:
        raise InspectError(
            "Could not resolve the repository file scope for inspection lanes.",
            3,
            {
                "error_reason": "inspection_lane_scope_resolution_failed",
                "failure_phase": "scope_resolution",
                "git_exit_code": error.returncode,
                "worktree_root": str(worktree_root),
            },
        ) from error
    return [os.fsdecode(path) for path in completed.stdout.split(b"\0") if path]


def normalize_inspection_lane_path(value: Any, worktree_root: Path) -> Path:
    path = Path(str(value)).expanduser()
    if not path.is_absolute():
        path = worktree_root / path
    normalized = path.resolve()
    if normalized != worktree_root and not normalized.is_relative_to(worktree_root):
        raise InspectError(
            "Inspection lane scope contains a path outside the exact worktree.",
            2,
            {
                "error_reason": "inspection_lane_path_outside_worktree",
                "failure_phase": "scope_resolution",
                "path": str(path),
                "worktree_root": str(worktree_root),
            },
        )
    return normalized


def inspection_lane_pattern_matches(relative_path: str, pattern: str) -> bool:
    path_segments = tuple(relative_path.split("/"))
    pattern_segments = tuple(pattern.split("/"))
    memo: dict[tuple[int, int], bool] = {}

    def matches(pattern_index: int, path_index: int) -> bool:
        key = (pattern_index, path_index)
        if key in memo:
            return memo[key]
        if pattern_index == len(pattern_segments):
            result = path_index == len(path_segments)
        elif pattern_segments[pattern_index] == "**":
            result = matches(pattern_index + 1, path_index) or (
                path_index < len(path_segments) and matches(pattern_index, path_index + 1)
            )
        else:
            result = (
                path_index < len(path_segments)
                and fnmatch.fnmatchcase(path_segments[path_index], pattern_segments[pattern_index])
                and matches(pattern_index + 1, path_index + 1)
            )
        memo[key] = result
        return result

    return matches(0, 0)


def durable_scope_value(value: Any) -> str:
    text = str(value)
    return redact_durable_text(text) if is_durable_path_candidate(split_durable_path_suffix(text)[0]) else text


def resolve_ide_selection(context: dict[str, Any]) -> IdeSelection | None:
    requested = clean_optional(context.get("ide"))
    explicit_app = clean_optional(context.get("ide_app"))
    channel = normalize_ide_channel(context.get("ide_channel"))
    version = parse_version_tuple(clean_optional(context.get("ide_version")))
    selector = explicit_app or requested
    product = product_for_selector(selector) or product_for_selector(requested)
    if not selector and not product and not channel and not version:
        return None
    exact = bool(explicit_app or version or channel == "eap" or selector_contains_exact_marker(selector))
    app_candidates = discover_ide_app_candidates()
    app = select_ide_candidate(app_candidates, product, explicit_app or selector, channel, version, exact)
    selected_product = product or product_for_candidate(app)
    config_candidates = discover_ide_config_candidates()
    config_version = app.version if app and app.version else version
    config_channel = channel if channel and channel != "eap" and not app else None
    config = select_ide_candidate(config_candidates, selected_product, selector, config_channel, config_version, exact)
    selected_product = selected_product or product_for_candidate(config)
    app_name = explicit_app or (app.name if app else None) or (selected_product.display_name if selected_product and not exact else None) or (requested if not exact else None)
    app_path = app.path if app else exact_app_path(explicit_app)
    resolved_channel = (app.channel if app else None) or (config.channel if config else None) or channel or "unknown"
    resolved_version = (app.version if app and app.version else ()) or (config.version if config and config.version else ()) or version
    mode = "exact" if exact else ("product" if selected_product else "selector")
    return IdeSelection(
        requested=requested,
        product_key=selected_product.key if selected_product else None,
        product=selected_product.display_name if selected_product else requested,
        mode=mode,
        channel=resolved_channel,
        version=resolved_version,
        app_name=app_name,
        app_path=app_path,
        config_dir=config.path if config else None,
        source="repo_or_cli",
        exact=exact,
    )


def dict_field(mapping: dict[str, Any], key: str) -> dict[str, Any]:
    value = mapping.get(key)
    return value if isinstance(value, dict) else {}


def list_field(mapping: dict[str, Any], key: str) -> list[Any]:
    value = mapping.get(key)
    return value if isinstance(value, list) else []


def clean_optional(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def normalize_selector(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


def normalize_ide_channel(value: Any) -> str | None:
    text = str(value or "").strip().lower()
    if not text:
        return None
    if text in {"stable", "release", "current"}:
        return "stable"
    if text in {"eap", "preview", "early-access", "early_access"}:
        return "eap"
    if text == "any":
        return "any"
    return text


def selector_contains_exact_marker(selector: str | None) -> bool:
    if not selector:
        return False
    return bool(parse_version_tuple(selector)) or "eap" in selector.lower() or selector.endswith(".app") or os.sep in selector


def product_for_selector(selector: str | None) -> IdeProduct | None:
    normalized = normalize_selector(selector)
    if not normalized:
        return None
    if normalized in IDE_PRODUCT_BY_ALIAS:
        return IDE_PRODUCT_BY_ALIAS[normalized]
    for product in IDE_PRODUCTS.values():
        if any(normalized.startswith(normalize_selector(prefix)) for prefix in product.config_prefixes):
            return product
        long_aliases = [alias for alias in product.aliases if len(alias) > 2]
        if any(alias in normalized for alias in long_aliases):
            return product
    return None


def product_for_candidate(candidate: IdeCandidate | None) -> IdeProduct | None:
    if not candidate:
        return None
    return IDE_PRODUCTS.get(candidate.product_key)


def parse_version_tuple(value: str | None) -> tuple[int, ...]:
    if not value:
        return ()
    match = re.search(r"(20\d{2})(?:[.\-](\d+))?(?:[.\-](\d+))?", value)
    if not match:
        return ()
    return tuple(int(part) for part in match.groups() if part is not None)


def version_from_jetbrains_text(value: str | None) -> tuple[int, ...]:
    parsed = parse_version_tuple(value)
    if parsed:
        return parsed
    match = re.search(r"\b(?:IU|IC|PY|PC|WS)-(\d{3})\.", str(value or ""))
    if not match:
        return ()
    build_major = int(match.group(1))
    return 2000 + build_major // 10, build_major % 10


def format_version(version: tuple[int, ...]) -> str | None:
    return ".".join(str(part) for part in version) if version else None


def candidate_channel(name: str, bundle_id: str | None = None) -> str:
    text = f"{name} {bundle_id or ''}".lower()
    return "eap" if "eap" in text else "stable"


def discover_ide_config_candidates() -> list[IdeCandidate]:
    if sys.platform != "darwin":
        return []
    base = Path.home() / "Library" / "Application Support" / "JetBrains"
    if not base.exists():
        return []
    candidates: list[IdeCandidate] = []
    for path in base.iterdir():
        if not path.is_dir() or not (path / "options").exists():
            continue
        product = product_for_selector(path.name)
        if not product:
            continue
        candidates.append(
            IdeCandidate(
                product_key=product.key,
                name=path.name,
                path=path,
                version=version_from_jetbrains_text(path.name),
                channel=candidate_channel(path.name),
                source="config",
            )
        )
    return candidates


def discover_ide_app_candidates() -> list[IdeCandidate]:
    if sys.platform != "darwin":
        return []
    candidates: list[IdeCandidate] = []
    for base in (Path("/Applications"), Path.home() / "Applications"):
        if not base.exists():
            continue
        for path in base.glob("*.app"):
            candidate = ide_app_candidate(path)
            if candidate:
                candidates.append(candidate)
    return candidates


def ide_app_candidate(path: Path) -> IdeCandidate | None:
    app_name = path.stem
    info_path = path / "Contents" / "Info.plist"
    bundle_id = None
    short_version = ""
    if info_path.exists():
        try:
            with info_path.open("rb") as handle:
                info = plistlib.load(handle)
            app_name = str(info.get("CFBundleName") or info.get("CFBundleDisplayName") or app_name)
            bundle_id = str(info.get("CFBundleIdentifier") or "")
            short_version = str(info.get("CFBundleShortVersionString") or "")
        except (OSError, plistlib.InvalidFileException, ValueError, ExpatError):
            pass
    product = product_for_selector(" ".join(part for part in (app_name, path.stem, bundle_id) if part))
    if not product:
        return None
    display_name = path.stem if path.stem != app_name and "eap" in path.stem.lower() else app_name
    return IdeCandidate(
        product_key=product.key,
        name=display_name,
        path=path,
        version=version_from_jetbrains_text(" ".join((path.stem, short_version))),
        channel=candidate_channel(" ".join((path.stem, short_version)), bundle_id),
        source="app",
    )


def select_ide_candidate(
    candidates: list[IdeCandidate],
    product: IdeProduct | None,
    selector: str | None,
    channel: str | None,
    version: tuple[int, ...],
    exact: bool,
) -> IdeCandidate | None:
    selected = candidates
    if product:
        selected = [candidate for candidate in selected if candidate.product_key == product.key]
    if channel and channel != "any":
        selected = [candidate for candidate in selected if candidate.channel == channel]
    elif not exact:
        stable = [candidate for candidate in selected if candidate.channel == "stable"]
        selected = stable
    if version:
        selected = [candidate for candidate in selected if versions_match(candidate.version, version)]
    if selector and selector_contains_exact_marker(selector):
        selector_norm = normalize_selector(selector)
        exact_matches = [candidate for candidate in selected if selector_norm in normalize_selector(candidate.name) or (candidate.path and selector_norm in normalize_selector(str(candidate.path)))]
        selected = exact_matches
    if not selected:
        return None
    return sorted(selected, key=ide_candidate_sort_key, reverse=True)[0]


def versions_match(candidate: tuple[int, ...], requested: tuple[int, ...]) -> bool:
    if not candidate or not requested:
        return True
    size = min(len(candidate), len(requested))
    return candidate[:size] == requested[:size]


def ide_candidate_sort_key(candidate: IdeCandidate) -> tuple[int, tuple[int, ...], str]:
    channel_score = 1 if candidate.channel == "stable" else 0
    return channel_score, candidate.version, candidate.name.lower()


def exact_app_path(value: str | None) -> Path | None:
    if not value:
        return None
    path = Path(value).expanduser()
    if path.suffix == ".app" or path.is_absolute():
        return path
    return None


def has_project_markers(path: Path) -> bool:
    markers = (
        ".idea",
        "settings.gradle",
        "settings.gradle.kts",
        "build.gradle",
        "build.gradle.kts",
        "pom.xml",
        "package.json",
        "pyproject.toml",
        "Cargo.toml",
        "go.mod",
    )
    return any((path / marker).exists() for marker in markers)


def command_list(args: argparse.Namespace) -> dict[str, Any]:
    identities = discover_identities(args.port)
    projects: list[dict[str, Any]] = []
    for identity in identities:
        for project in identity.get("open_projects", []) or []:
            projects.append(flatten_project(identity, project))
    result = {"status": "ok", "mode": "http", "projects": projects, "count": len(projects)}
    if identities and not projects:
        result["zero_project_hint"] = zero_project_hint()
        result["identities"] = [public_identity_summary(identity) for identity in identities]
    return result


def command_route(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    route = resolve_route(args, context)
    return {"status": "resolved", "context": public_context(context), "route": route}


def command_trigger(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    route = resolve_route(args, context)
    body = call_contextual_endpoint(route, "trigger", trigger_params(args, context, route), context)
    return {"status": body.get("status", "triggered"), "context": public_context(context), "route": body.get("route") or route, "trigger": body}


def command_wait(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    route = resolve_route(args, context)
    timeout_ms = getattr(args, "timeout_ms", DEFAULT_WAIT_TIMEOUT_MS)
    body = call_contextual_endpoint(route, "wait", route_params(args, context, route) | {
        "timeout_ms": timeout_ms,
        "poll_ms": getattr(args, "poll_ms", DEFAULT_POLL_MS),
    }, context, timeout=wait_http_timeout(timeout_ms))
    result = {
        "status": body.get("completion_reason") or body.get("status", "unknown"),
        "context": public_context(context),
        "route": body.get("route") or route,
        "wait": body,
    }
    if getattr(args, "allow_text_only_coverage", False):
        result["allow_text_only_coverage"] = True
    copy_verdict_evidence(result, body)
    apply_verdict(result)
    return result


def command_status(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    route = resolve_route(args, context)
    body = call_contextual_endpoint(route, "status", route_params(args, context, route), context)
    status = status_label(body)
    result = {
        "status": status,
        "clean": classify_status_body_clean(body),
        "context": public_context(context),
        "route": body.get("route") or route,
        "is_scanning": body.get("is_scanning", False),
        "indexing": body.get("indexing", False),
        "inspection_in_progress": body.get("inspection_in_progress", False),
        "has_inspection_results": body.get("has_inspection_results", False),
        "clean_inspection": body.get("clean_inspection", False),
        "session_drift": body.get("session_drift", False),
        "ambiguous": body.get("ambiguous", False),
        "unavailable": body.get("unavailable", False),
        "capture_incomplete": body.get("capture_incomplete", False),
        "results_may_be_stale": body.get("results_may_be_stale", False),
        "timed_out": body.get("timed_out", False),
        "raw": body,
    }
    if getattr(args, "allow_text_only_coverage", False):
        result["allow_text_only_coverage"] = True
    copy_verdict_evidence(result, body)
    apply_verdict(result)
    return result


def copy_verdict_evidence(target: dict[str, Any], source: dict[str, Any]) -> None:
    for key in (
        "total_problems",
        "problems_shown",
        "cached_total_problems",
        "cached_problems_shown",
        "capture_incomplete_reason",
        "capture_diagnostic",
        *INSPECTION_STAGE_EVIDENCE_KEYS,
        *VERDICT_SOURCE_KEYS,
    ):
        if key in source:
            target[key] = source[key]


def command_problems(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    route = resolve_route(args, context)
    body = call_contextual_endpoint(route, "problems", problems_params(args, context, route), context)
    if getattr(args, "include_stale", False):
        body.setdefault("include_stale", True)
    return summarize_problems(
        context,
        body.get("route") or route,
        body,
        allow_text_only_coverage=getattr(args, "allow_text_only_coverage", False),
    )


def command_claim(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    lease = create_local_lease(context, state="claimed")
    return {"status": "claimed", "context": public_context(context), "lease": public_lease(lease)}


def command_prepare(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    with lifecycle_lock(getattr(args, "lifecycle_lock_timeout_ms", DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)):
        prepared = prepare_lifecycle(args, context)
    return prepared


def command_closeout(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    nested = explicit_nested_lane_context(args, context)
    if nested is not None:
        return run_prepared_inspection(args, nested)
    if configured_inspection_lanes(context):
        return run_configured_inspection_lanes(args, context)
    return run_prepared_inspection(args, context)


def command_run(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    return command_closeout(args, context)


def explicit_nested_lane_context(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any] | None:
    lanes = configured_inspection_lanes(context)
    root_value, repo_value = context.get("worktree_root"), context.get("repo_path")
    if not getattr(args, "native_project_scope", False) or not lanes or not root_value or not repo_value:
        return None
    root, project = Path(root_value).resolve(), Path(repo_value).resolve()
    if project == root:
        raise inspection_lane_config_error("Native project scope requires an exact nested lane projectPath.")
    if not project.is_relative_to(root):
        raise inspection_lane_config_error("The requested nested project must stay inside the exact worktree.")
    matching = [lane for lane in lanes if lane.project_path is not None
                and (root / lane.project_path).resolve() == project]
    if len(matching) != 1:
        raise inspection_lane_config_error("The explicit nested project must match exactly one declared lane projectPath.")
    lane = matching[0]
    lane_product = product_for_selector(lane.ide)
    for selector in (getattr(args, "ide", None), getattr(args, "ide_app", None)):
        requested_product = product_for_selector(selector)
        if lane_product and requested_product and requested_product.key != lane_product.key:
            raise inspection_lane_config_error("The explicit IDE selector conflicts with the selected nested lane IDE.")
    result = {key: value for key, value in context.items()
              if key not in {"inspection_lanes", "_inspection_lanes", "ide_selection", "ide_config_dir", "ide_app_path"}}
    result.update(ide=lane.ide, ide_app=getattr(args, "ide_app", None),
                  ide_channel=getattr(args, "ide_channel", None), ide_version=getattr(args, "ide_version", None),
                  inspection_lane=lane.public(), project_path=str(project),
                  exact_route_path=str(project), lifecycle_target_path=str(project))
    args.ide = lane.ide
    args.project_path = str(project)
    result["_repository_preparation_lanes"] = lanes
    selection = resolve_ide_selection(result)
    if selection:
        result["ide_selection"] = selection.public()
        if selection.config_dir:
            result["ide_config_dir"] = str(selection.config_dir)
        if selection.app_path:
            result["ide_app_path"] = str(selection.app_path)
        if selection.app_name:
            result["ide_app"] = selection.app_name
    return result


def run_configured_inspection_lanes(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    lanes = configured_inspection_lanes(context)
    selection = resolve_inspection_lane_selection(args, context)
    lane_files = selection.get("lane_files")
    if not isinstance(lane_files, dict):
        lane_files = {}
    if any(isinstance(files, list) and files for files in lane_files.values()):
        with lifecycle_lock(getattr(args, "lifecycle_lock_timeout_ms", DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)):
            repository_preparation = run_repository_preparation(args, context)
        context = dict(context)
        context["repository_preparation"] = repository_preparation
        context["_repository_preparation_completed"] = True
    lane_results: list[dict[str, Any]] = []
    for execution_order, lane in enumerate(lanes):
        selected = lane_files.get(lane.lane_id)
        if not isinstance(selected, list):
            selected = []
        if not selected:
            lane_results.append(noop_inspection_lane_result(lane, execution_order, context))
            continue
        lane_args = inspection_lane_args(args, lane, selected)
        lane_context = dict(context)
        try:
            lane_context = inspection_lane_context(context, lane_args, lane, selected)
            lane_payload = run_prepared_inspection(lane_args, lane_context)
        except InspectError as error:
            lane_payload = error_payload(error, lane_args)
        except Exception as error:
            lane_payload = inspection_exception_result(error)
        apply_verdict(lane_payload)
        lane_results.append(
            compact_inspection_lane_result(
                lane,
                execution_order,
                lane_context,
                selected,
                lane_payload,
            )
        )

    result = {
        "status": "inspection_lanes_complete",
        "context": public_context(context),
        "lane_schema_version": INSPECTION_LANE_SCHEMA_VERSION,
        "repository_preparation": aggregate_repository_preparation_for_lanes(lane_results, context),
        "lane_selection": {
            key: value
            for key, value in selection.items()
            if key != "lane_files"
        },
        "lane_results": lane_results,
    }
    apply_multi_lane_verdict(result)
    return result


def inspection_lane_args(
    args: argparse.Namespace,
    lane: InspectionLane,
    selected: list[dict[str, str]],
) -> argparse.Namespace:
    values = dict(vars(args))
    values.update(
        {
            "ide": lane.ide,
            "ide_app": None,
            "ide_channel": None,
            "ide_version": None,
            "project_key": None,
            "project": None,
            "session_id": None,
            "scope": "files",
            "directory": None,
            "files": [item["absolute_path"] for item in selected],
            "max_files": None,
            "client_run_id": str(uuid.uuid4()),
        }
    )
    return argparse.Namespace(**values)


def inspection_lane_context(
    context: dict[str, Any],
    lane_args: argparse.Namespace,
    lane: InspectionLane,
    selected: list[dict[str, str]],
) -> dict[str, Any]:
    lane_context = {
        key: value
        for key, value in context.items()
        if key not in {"ide_selection", "ide_config_dir", "ide_app_path", "inspection_lanes", "_inspection_lanes"}
    }
    lane_context.update(
        {
            "ide": lane.ide,
            "ide_app": None,
            "ide_channel": None,
            "ide_version": None,
            "scope": "files",
            "client_run_id": lane_args.client_run_id,
            "inspection_lane": lane.public(),
            "selected_files": [item["absolute_path"] for item in selected],
        }
    )
    worktree_root = Path(str(lane_context["worktree_root"])).expanduser().resolve()
    if lane.project_path is not None:
        project_path = (worktree_root / lane.project_path).resolve()
        if project_path != worktree_root and not project_path.is_relative_to(worktree_root):
            raise inspection_lane_config_error(
                f"Inspection lane {lane.lane_id} projectPath resolves outside the exact worktree after repository preparation: {lane.project_path}"
            )
        if not project_path.is_dir():
            raise inspection_lane_config_error(
                f"Inspection lane {lane.lane_id} projectPath must resolve to an existing directory after repository preparation: {lane.project_path}"
            )
        outside_project = [
            item["file"]
            for item in selected
            if not Path(item["absolute_path"]).expanduser().resolve().is_relative_to(project_path)
        ]
        if outside_project:
            raise inspection_lane_config_error(
                f"Inspection lane {lane.lane_id} selected files outside projectPath {lane.project_path}: "
                + ", ".join(outside_project[:5])
            )
        lane_args.project_path = str(project_path)
        lane_context.update(
            {
                "repo_path": str(project_path),
                "project_path": str(project_path),
                "exact_route_path": str(project_path),
                "lifecycle_target_path": str(project_path),
            }
        )
    scope_descriptor = canonical_scope_descriptor(lane_args, lane_context, worktree_root)
    lane_context["scope_descriptor"] = scope_descriptor
    lane_context["scope_descriptor_sha256"] = canonical_json_sha256(scope_descriptor)
    ide_selection = resolve_ide_selection(lane_context)
    if ide_selection:
        lane_context["ide_selection"] = ide_selection.public()
        if ide_selection.config_dir:
            lane_context["ide_config_dir"] = str(ide_selection.config_dir)
        if ide_selection.app_path:
            lane_context["ide_app_path"] = str(ide_selection.app_path)
        if ide_selection.app_name:
            lane_context["ide_app"] = ide_selection.app_name
    return lane_context


def noop_inspection_lane_result(
    lane: InspectionLane,
    execution_order: int,
    context: dict[str, Any],
) -> dict[str, Any]:
    return {
        "schema_version": INSPECTION_LANE_SCHEMA_VERSION,
        "id": lane.lane_id,
        "required": lane.required,
        "execution_order": execution_order,
        "status": "no_matching_files",
        "verdict": "NOT_RUN",
        "bucket": "no_matching_files",
        "blocker_stage": None,
        "next_action": "No IDE action required because this lane has no selected files.",
        "worktree": context.get("worktree_root"),
        "scope": "files",
        "files": [],
        "ide": {"requested": lane.ide},
        "route": None,
        "proof": None,
        "cleanup": {"status": "not_needed", "reason": "lane_empty"},
        "diagnostic": None,
        "repository_preparation": bounded_repository_preparation(
            context.get("repository_preparation"),
            target_worktree=str(repository_preparation_target(context)),
        ),
    }


def compact_inspection_lane_result(
    lane: InspectionLane,
    execution_order: int,
    context: dict[str, Any],
    selected: list[dict[str, str]],
    payload: dict[str, Any],
) -> dict[str, Any]:
    compact = compact_agent_result_payload(payload, classify_run_exit(payload))
    agent_result = compact.get("agent_result")
    if not isinstance(agent_result, dict):
        agent_result = {}
    route = payload_route(payload)
    ide_selection = context.get("ide_selection")
    if not isinstance(ide_selection, dict):
        ide_selection = {}
    cleanup = payload.get("cleanup")
    if not isinstance(cleanup, dict):
        cleanup = {}
    evidence_ids = payload.get("evidence_ids")
    if not isinstance(evidence_ids, dict):
        evidence_ids = {}
    compact_identity = compact.get("identity")
    if not isinstance(compact_identity, dict):
        compact_identity = {}
    return public_payload(
        {
            "schema_version": INSPECTION_LANE_SCHEMA_VERSION,
            "id": lane.lane_id,
            "required": lane.required,
            "execution_order": execution_order,
            "status": payload.get("status"),
            "verdict": agent_result.get("verdict"),
            "bucket": agent_result.get("bucket"),
            "retry_policy": agent_result.get("retry_policy"),
            "blocker_stage": payload.get("failure_phase"),
            "next_action": agent_result.get("next_action"),
            "batch_inspection_delegations": agent_result.get("batch_inspection_delegations"),
            "batch_inspection_delegation_count": agent_result.get("batch_inspection_delegation_count"),
            "batch_inspection_delegations_omitted_count": agent_result.get("batch_inspection_delegations_omitted_count"),
            "worktree": context.get("worktree_root"),
            "scope": "files",
            "file_count": len(selected),
            "files": [item["absolute_path"] for item in selected],
            "relative_files": [item["file"] for item in selected],
            "ide": {
                "requested": lane.ide,
                "product": ide_selection.get("product"),
                "channel": ide_selection.get("channel"),
                "version": compact_identity.get("ide_version") or ide_selection.get("version"),
                "app_name": ide_selection.get("app_name"),
                "config_dir": ide_selection.get("config_dir"),
                "product_code": compact_identity.get("ide_product_code"),
                "plugin_version": compact_identity.get("plugin_version"),
                "plugin_build_fingerprint": compact_identity.get("plugin_build_fingerprint"),
                "helper_revision": compact_identity.get("helper_revision"),
            },
            "route": {
                "project_name": route.get("project_name"),
                "project_key": route.get("project_key"),
                "project_instance_id": route.get("project_instance_id"),
                "session_id": route.get("session_id"),
                "base_path": route.get("base_path"),
            } if route else None,
            "evidence_ids": {
                "client_run_id": evidence_ids.get("client_run_id"),
                "request_id": evidence_ids.get("request_id"),
                "session_id": evidence_ids.get("session_id") or route.get("session_id"),
                "project_instance_id": evidence_ids.get("project_instance_id") or route.get("project_instance_id"),
                "inspection_run_id": evidence_ids.get("inspection_run_id") or compact_identity.get("inspection_run_id"),
            },
            "proof": compact.get("inspection_proof"),
            "finding_count": compact.get("finding_count"),
            "findings": compact.get("findings"),
            "findings_truncated": compact.get("findings_truncated"),
            "findings_artifact": compact.get("findings_artifact"),
            "cleanup": {
                "status": cleanup.get("status"),
                "reason": cleanup.get("reason"),
                "mutation_evidence": payload.get("worktree_mutation_evidence"),
            },
            "inspection_outcome": inspection_outcome_for_payload(payload),
            "lifecycle_outcome": lifecycle_outcome_for_payload(payload),
            "diagnostic": compact.get("diagnostic"),
            "repository_preparation": repository_preparation_for_payload(payload),
        }
    )


def aggregate_repository_preparation_for_lanes(
    lane_results: list[dict[str, Any]],
    context: dict[str, Any],
) -> dict[str, Any]:
    preparations = [
        preparation
        for lane in lane_results
        if isinstance(lane, dict) and isinstance(preparation := lane.get("repository_preparation"), dict)
    ]
    precedence = {
        "failed": 6,
        "blocked": 6,
        REPOSITORY_PREPARATION_SUCCEEDED: 5,
        REPOSITORY_PREPARATION_REUSED: 4,
        REPOSITORY_PREPARATION_SKIPPED: 3,
        REPOSITORY_PREPARATION_NOT_RUN: 2,
        REPOSITORY_PREPARATION_NOT_CONFIGURED: 1,
    }
    selected = max(
        preparations,
        key=lambda preparation: precedence.get(str(preparation.get("execution_state")), 0),
        default=context.get("repository_preparation"),
    )
    return bounded_repository_preparation(
        selected,
        target_worktree=str(repository_preparation_target(context)),
    )


def apply_multi_lane_verdict(payload: dict[str, Any]) -> dict[str, Any]:
    lane_results = payload.get("lane_results")
    if not isinstance(lane_results, list):
        lane_results = []
    required = [
        lane
        for lane in lane_results
        if isinstance(lane, dict) and lane.get("required") is True and lane.get("verdict") != "NOT_RUN"
    ]
    red = [lane for lane in required if lane.get("verdict") == "RED"]
    unknown = [lane for lane in required if lane.get("verdict") not in {"GREEN", "RED"}]
    if red:
        verdict = "RED"
        reason = "required_lane_red"
        bucket = "multi_lane_findings"
        status = "findings"
        affected = [str(lane.get("id")) for lane in red]
        next_action = f"Fix actionable findings in required lane(s): {', '.join(affected)}."
    elif unknown:
        verdict = "UNKNOWN"
        reason = "required_lane_unknown"
        bucket = "multi_lane_unknown"
        status = "inspection_lanes_unknown"
        affected = [str(lane.get("id")) for lane in unknown]
        next_action = f"Resolve the fail-closed outcome in required lane(s): {', '.join(affected)}."
    elif any(isinstance(lane, dict) and lane.get("verdict") != "NOT_RUN" for lane in lane_results):
        verdict = "GREEN"
        reason = "all_required_lanes_green" if required else "no_required_lane_files"
        bucket = "multi_lane_clean"
        status = "clean"
        next_action = "No inspection action required for the configured required lanes."
    else:
        verdict = "UNKNOWN"
        reason = "no_required_lane_files"
        bucket = "multi_lane_unknown"
        status = "inspection_scope_empty"
        if selected_inspection_file_count(payload) == 0:
            next_action = (
                "No files were selected. Select tracked files with --scope files and repeatable --file arguments. "
                "changed_files covers working-tree changes, not committed branch changes."
            )
        else:
            next_action = (
                "No selected files reached an inspection lane. Review unmatched_files, excluded_files, and lane include/exclude globs. "
                "Choose files supported by the configured lanes; explicit --scope files selectors override lane exclusions."
            )

    retry_policies = [
        policy
        for lane in required
        if isinstance(policy := lane.get("retry_policy"), dict)
    ]
    retry = verdict == "UNKNOWN" and any(policy.get("retry") is True for policy in retry_policies)
    retry_policy = {
        "retry": retry,
        "max_attempts": max((int(policy.get("max_attempts") or 0) for policy in retry_policies), default=0) if retry else 0,
        "wait_ms": max((int(policy.get("wait_ms") or 0) for policy in retry_policies), default=0) if retry else 0,
    }
    payload.update(
        {
            "status": status,
            "verdict": verdict,
            "verdict_reason": reason,
            "verdict_message": "Configured JetBrains inspection lanes were aggregated deterministically.",
            "verdict_next_action": next_action,
            "bucket": bucket,
            "retry_policy": retry_policy,
            "agent_report": f"{verdict}: {reason}. {next_action}",
            "agent_result": {
                "verdict": verdict,
                "bucket": bucket,
                "retry_policy": retry_policy,
                "next_action": next_action,
                "agent_report": f"{verdict}: {reason}. {next_action}",
                "repository_preparation": repository_preparation_for_payload(payload),
            },
            "required_lane_count": len(required),
            "lane_count": len(lane_results),
        }
    )
    return payload


def run_prepared_inspection(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    inspection_error: BaseException | None = None
    with lifecycle_lock(getattr(args, "lifecycle_lock_timeout_ms", DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)):
        command = canonical_command(str(getattr(args, "command", "")))
        if (
            context.get("_repository_preparation_completed") is not True
            and isinstance(context.get("repository_preparation"), dict)
            and command in {"agent", "run", "closeout", ""}
        ):
            repository_preparation = run_repository_preparation(args, context)
            context = dict(context)
            context["repository_preparation"] = repository_preparation
            context["_repository_preparation_completed"] = True
        mutation_before = git_worktree_status_snapshot(context.get("worktree_root"))
        prepared, lease, close_proof = prepare_lifecycle_details(args, context)
        try:
            result = run_inspection_with_internal_retry(args, context, prepared["route"])
            result["inspection_result"] = compact_inspection_result(result)
            findings_artifact = preserve_inspection_findings(args, context, result)
            if findings_artifact is not None:
                result["findings_artifact"] = findings_artifact
        except BaseException as error:
            inspection_error = error
            result = inspection_exception_result(error)
        finally:
            if getattr(args, "keep_warm", False):
                if lease_may_own_open_project(lease):
                    cleanup = {
                        "status": "kept_warm",
                        "reason": "keep_warm_requested",
                        "lease_id": lease.get("lease_id"),
                    }
                else:
                    cleanup = {
                        "status": "not_needed",
                        "reason": "helper_did_not_open_project",
                        "lease_id": lease.get("lease_id"),
                    }
            else:
                if should_defer_lifecycle_cleanup(result, lease):
                    cleanup = defer_lifecycle_cleanup(lease, result)
                else:
                    cleanup = cleanup_lifecycle(lease, prepared.get("route") or {}, close_proof)
            if lease_may_own_open_project(lease):
                mutation_after = post_cleanup_worktree_status_snapshot(context)
                result["worktree_mutation_evidence"] = summarize_worktree_mutations(
                    mutation_before,
                    mutation_after,
                )
                apply_worktree_mutation_blocker(result)
        result["prepared"] = public_payload(prepared)
        apply_prepared_retry_evidence(result, prepared)
        result["cleanup"] = cleanup
        if cleanup.get("status") == "deferred":
            result["cleanup_deferred"] = True
        elif cleanup.get("status") not in {"closed", "not_needed", "skipped", "kept_warm"}:
            result["cleanup_failed"] = True
        if cleanup.get("cleanup_skipped"):
            result["cleanup_skipped"] = True
        apply_verdict(result)
        if inspection_error is not None:
            if isinstance(inspection_error, InspectError):
                inspection_error.payload.setdefault("context", public_context(context))
                inspection_error.payload.setdefault("prepared", public_payload(prepared))
                inspection_error.payload.setdefault("cleanup", public_payload(cleanup))
                inspection_error.payload["inspection_failure"] = public_payload(result)
                raise inspection_error
            if isinstance(inspection_error, (KeyboardInterrupt, SystemExit, GeneratorExit)):
                raise inspection_error
            raise InspectError(
                "Inspection helper failed unexpectedly.",
                3,
                {
                    "error_reason": result.get("error_reason"),
                    "error_type": inspection_error.__class__.__name__,
                    "error_message": str(inspection_error) or inspection_error.__class__.__name__,
                    "context": public_context(context),
                    "prepared": public_payload(prepared),
                    "cleanup": public_payload(cleanup),
                    "inspection_failure": public_payload(result),
                },
            ) from inspection_error
    return result


def preserve_inspection_findings(
    args: argparse.Namespace, context: dict[str, Any], result: dict[str, Any],
) -> dict[str, Any] | None:
    """Save accepted run evidence before closing; an artifact is never a new assessment."""
    total = result.get("total_problems")
    displayed = min(MAX_AGENT_FINDINGS, len(result.get("problems") or []))
    if result.get("verdict") != "RED" or not isinstance(total, int) or isinstance(total, bool) or total <= displayed:
        return None
    route = payload_route(result)
    run_id = inspection_run_id(result.get("trigger") or {}) or inspection_run_id(result)
    receipt: dict[str, Any] = {
        "status": "incomplete", "expected_finding_count": total,
        "inspection_run_id": run_id, "finding_count": 0,
    }
    if run_id is None or not all(route.get(key) for key in ("project_key", "session_id", "project_instance_id")):
        return receipt | {"status": "unavailable", "reason": "missing_run_or_route_proof"}
    request = problems_params(args, context, route) | {"inspection_run_id": run_id}
    # Display pagination does not restrict the preserved matching finding set.
    request["offset"] = 0
    offset = 0
    problems: list[dict[str, Any]] = []
    pages: list[dict[str, Any]] = []
    deadline = time.monotonic() + 30.0
    first = result.get("raw")
    if not isinstance(first, dict):
        first = {}
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                receipt["reason"] = "findings_capture_timeout"
                break
            page = first if offset == 0 and getattr(args, "offset", 0) == 0 else call_endpoint(
                route, "problems", request | {"offset": offset}, timeout=min(DEFAULT_TIMEOUT_SECONDS, remaining),
            )
            page_route = page.get("route")
            if not isinstance(page_route, dict):
                page_route = {}
            page_problems = page.get("problems")
            pagination = page.get("pagination")
            if not isinstance(pagination, dict):
                pagination = {}
            page_summary = summarize_problems(
                context, page_route, page,
                allow_text_only_coverage=getattr(args, "allow_text_only_coverage", False),
            )
            if (
                inspection_run_id(page) != run_id
                or inspection_result_run_changed(page, run_id)
                or any(page_route.get(key) != route.get(key) for key in ("project_key", "session_id", "project_instance_id"))
                or page_summary.get("verdict") != "RED"
                or page.get("results_may_be_stale") is True
                or page.get("inspection_in_progress") is True
                or page.get("total_problems") != total
            ):
                receipt["reason"] = "findings_page_proof_changed"
                break
            if (
                not isinstance(page_problems, list)
                or not all(isinstance(problem, dict) for problem in page_problems)
                or not page_problems
                or page.get("problems_shown") != len(page_problems)
                or pagination.get("offset") != offset
                or offset + len(page_problems) > total
            ):
                receipt["reason"] = "findings_pagination_inconsistent"
                break
            following = offset + len(page_problems)
            if (
                pagination.get("has_more") is not (following < total)
                or (following < total and pagination.get("next_offset") != following)
            ):
                receipt["reason"] = "findings_pagination_inconsistent"
                break
            problems.extend(page_problems)
            pages.append({
                "offset": offset, "finding_count": len(page_problems),
                "inspection_attribution": page_summary.get("inspection_attribution"),
                "proof_failures": page_summary.get("proof_failures"),
                "inspection_proof": compact_inspection_proof(page_summary),
            })
            if following == total:
                receipt["status"] = "complete"
                break
            offset = following
    except InspectError as error:
        receipt["reason"] = infer_error_reason(error, error.payload)
    except Exception as error:
        receipt["reason"] = "findings_capture_failed"
        receipt["error_type"] = type(error).__name__
    receipt["finding_count"] = len(problems)
    artifact = {
        "schema_version": 1, "recorded_at": datetime.now(timezone.utc).isoformat(),
        "evidence_kind": "accepted_run_findings", "helper_revision": helper_revision(),
        "retrieval": receipt, "request": request, "assessment": result,
        "pages": pages, "problems": problems,
    }
    path: Path | None = None
    try:
        data = public_json(artifact).encode("utf-8")
        directory = cache_dir() / "findings"
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        destination = directory / f"{uuid.uuid4()}.json"
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        path = destination
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
        return receipt | {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}
    except Exception:
        if path is not None:
            with suppress(OSError):
                path.unlink(missing_ok=True)
        return receipt | {"status": "unavailable", "reason": "findings_artifact_write_failed"}


def apply_prepared_retry_evidence(result: dict[str, Any], prepared: dict[str, Any]) -> None:
    readiness = prepared.get("readiness_barrier")
    if not isinstance(readiness, dict):
        return
    for key in (
        "internal_retry_count",
        "internal_retry_reason",
        "internal_retry_readiness",
    ):
        if key in readiness:
            result.setdefault(key, readiness[key])
            result[f"prepared_{key}"] = readiness[key]


def run_inspection_with_internal_retry(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any]:
    result = run_inspection_on_route(args, context, route)
    if not should_retry_unknown_result(result):
        return result
    retry_policy = dict_field(result, "retry_policy")
    max_attempts = max(0, int(retry_policy.get("max_attempts") or 0))
    retry_summaries: list[dict[str, Any]] = []
    readiness_history: list[dict[str, Any]] = []
    current_result = result
    attempt = 0
    while attempt < max_attempts:
        retry_summaries.append(compact_retry_result(current_result, attempt=attempt))
        readiness = wait_for_internal_retry_readiness(args, context, route, current_result)
        readiness_history.append(readiness)
        if readiness.get("ready") is not True:
            prior_retry_summaries = retry_summaries[:-1]
            if prior_retry_summaries:
                current_result["internal_retries"] = prior_retry_summaries
            current_result["internal_retry_count"] = attempt
            current_result["internal_retry_skipped"] = True
            current_result["internal_retry_skip_reason"] = "retry_readiness_not_proven"
            current_result["internal_retry_readiness"] = readiness
            current_result["internal_retry_readiness_history"] = readiness_history
            current_result["retry_exhausted"] = True
            return current_result
        try:
            current_result = run_inspection_on_route(args, context, route)
        except InspectError as error:
            if error.payload.get("error_reason") == "ide_memory_exhausted":
                error.payload["internal_retries"] = retry_summaries
                error.payload["internal_retry_count"] = attempt if error.payload.get("trigger_not_sent") is True else attempt + 1
                error.payload["internal_retry_skipped"] = error.payload.get("trigger_not_sent") is True
                error.payload["internal_retry_readiness"] = readiness
                error.payload["internal_retry_readiness_history"] = readiness_history
                if current_result.get("transport_state_unknown") is True:
                    error.payload["transport_state_unknown"] = True
            raise
        current_result["internal_retries"] = retry_summaries
        current_result["internal_retry_count"] = attempt + 1
        current_result["internal_retry_readiness"] = readiness
        current_result["internal_retry_readiness_history"] = readiness_history
        if not should_retry_unknown_result(current_result):
            current_result["recovered_from_unknown"] = current_result.get("verdict") in {"GREEN", "RED"}
            current_result["retry_exhausted"] = False
            return current_result
        current_retry_policy = dict_field(current_result, "retry_policy")
        max_attempts = max(max_attempts, max(0, int(current_retry_policy.get("max_attempts") or 0)))
        attempt += 1
    current_result["recovered_from_unknown"] = False
    current_result["retry_exhausted"] = True
    return current_result


def wait_for_internal_retry_readiness(
    args: argparse.Namespace,
    context: dict[str, Any],
    route: dict[str, Any],
    first_result: dict[str, Any],
) -> dict[str, Any]:
    retry_policy = dict_field(first_result, "retry_policy")
    min_wait_ms = max(0, int(retry_policy.get("wait_ms") or 0))
    poll_ms = max(DEFAULT_POLL_MS, int(getattr(args, "poll_ms", DEFAULT_POLL_MS) or DEFAULT_POLL_MS))
    timeout_ms = max(INTERNAL_RETRY_READY_TIMEOUT_MS, min_wait_ms + poll_ms * INTERNAL_RETRY_READY_STABLE_OBSERVATIONS)
    started_at_ms = monotonic_ms()
    deadline_ms = started_at_ms + timeout_ms
    sample_count = 0
    stable_observations = 0
    first_status: dict[str, Any] | None = None
    last_status: dict[str, Any] | None = None
    exit_reason = "not_observed"

    while monotonic_ms() <= deadline_ms:
        try:
            body = call_endpoint(route, "status", route_params(args, context, route))
        except InspectError as error:
            return retry_readiness_result(
                first_result,
                status="unavailable",
                ready=False,
                exit_reason=infer_error_reason(error, error.payload),
                min_wait_ms=min_wait_ms,
                timeout_ms=timeout_ms,
                poll_ms=poll_ms,
                sample_count=sample_count,
                stable_observations=stable_observations,
                elapsed_ms=max(0, monotonic_ms() - started_at_ms),
                first_status=first_status,
                last_status=last_status,
            )
        sample_count += 1
        compact_status = compact_retry_status(body)
        if first_status is None:
            first_status = compact_status
        last_status = compact_status
        ready, exit_reason = retry_activity_ready(body)
        stable_observations = stable_observations + 1 if ready else 0
        elapsed_ms = max(0, monotonic_ms() - started_at_ms)
        if elapsed_ms >= min_wait_ms and stable_observations >= INTERNAL_RETRY_READY_STABLE_OBSERVATIONS:
            return retry_readiness_result(
                first_result,
                status="ready",
                ready=True,
                exit_reason="ready",
                min_wait_ms=min_wait_ms,
                timeout_ms=timeout_ms,
                poll_ms=poll_ms,
                sample_count=sample_count,
                stable_observations=stable_observations,
                elapsed_ms=elapsed_ms,
                first_status=first_status,
                last_status=last_status,
            )
        time.sleep(poll_ms / 1000.0)

    return retry_readiness_result(
        first_result,
        status="timeout",
        ready=False,
        exit_reason=exit_reason,
        min_wait_ms=min_wait_ms,
        timeout_ms=timeout_ms,
        poll_ms=poll_ms,
        sample_count=sample_count,
        stable_observations=stable_observations,
        elapsed_ms=max(0, monotonic_ms() - started_at_ms),
        first_status=first_status,
        last_status=last_status,
    )


def retry_readiness_result(
    first_result: dict[str, Any],
    *,
    status: str,
    ready: bool,
    exit_reason: str,
    min_wait_ms: int,
    timeout_ms: int,
    poll_ms: int,
    sample_count: int,
    stable_observations: int,
    elapsed_ms: int,
    first_status: dict[str, Any] | None,
    last_status: dict[str, Any] | None,
) -> dict[str, Any]:
    result = {
        "schema_version": READINESS_BARRIER_SCHEMA_VERSION,
        "strategy": "route_activity_quiet_gate_v1",
        "status": status,
        "ready": ready,
        "exit_reason": exit_reason,
        "trigger_bucket": first_result.get("bucket"),
        "trigger_reason": first_result.get("verdict_reason"),
        "min_wait_ms": min_wait_ms,
        "timeout_ms": timeout_ms,
        "poll_ms": poll_ms,
        "required_stable_observations": INTERNAL_RETRY_READY_STABLE_OBSERVATIONS,
        "stable_observations": stable_observations,
        "sample_count": sample_count,
        "elapsed_ms": elapsed_ms,
        "first_status": first_status,
        "last_status": last_status,
        "observation_scope": "ide_status_only",
        "same_worktree_writer_observation": "not_available",
    }
    return {key: value for key, value in result.items() if value is not None}


def retry_activity_ready(body: dict[str, Any]) -> tuple[bool, str]:
    if body.get("session_drift"):
        return False, "session_drift"
    if body.get("ambiguous"):
        return False, "ambiguous"
    if body.get("unavailable"):
        return False, "unavailable"
    if body.get("timed_out"):
        return False, "timed_out"
    if body.get("indexing"):
        return False, "indexing"
    if body.get("is_scanning"):
        return False, "scanning"
    if body.get("inspection_in_progress"):
        return False, "inspection_in_progress"
    lifecycle_readiness = lifecycle_readiness_from_payload(body)
    if lifecycle_readiness and lifecycle_readiness.get("ready") is False:
        return False, str(lifecycle_readiness.get("reason") or "lifecycle_not_ready")
    status = normalize_reason(body.get("status") or body.get("completion_reason"))
    if status in {
        "indexing",
        "running",
        "timed_out",
        "session_drift",
        "ambiguous",
        "unavailable",
    }:
        return False, status
    return True, "ready"


def compact_retry_status(body: dict[str, Any]) -> dict[str, Any]:
    lifecycle_readiness = lifecycle_readiness_from_payload(body) or {}
    summary = {
        "status": status_label(body),
        "completion_reason": body.get("completion_reason"),
        "indexing": body.get("indexing"),
        "is_scanning": body.get("is_scanning"),
        "inspection_in_progress": body.get("inspection_in_progress"),
        "results_may_be_stale": body.get("results_may_be_stale"),
        "capture_incomplete": body.get("capture_incomplete"),
        "capture_incomplete_reason": body.get("capture_incomplete_reason"),
        "timed_out": body.get("timed_out"),
        "session_drift": body.get("session_drift"),
        "ambiguous": body.get("ambiguous"),
        "unavailable": body.get("unavailable"),
        "inspection_run_id": inspection_run_id(body),
        "lifecycle_ready": lifecycle_readiness.get("ready"),
        "lifecycle_reason": lifecycle_readiness.get("reason"),
    }
    return {key: value for key, value in summary.items() if value is not None}


def should_retry_unknown_result(result: dict[str, Any]) -> bool:
    retry_policy = dict_field(result, "retry_policy")
    return (
        result.get("verdict") == "UNKNOWN"
        and result.get("bucket") in INTERNAL_RETRY_BUCKETS
        and retry_policy.get("retry") is True
        and int(retry_policy.get("max_attempts") or 0) > 0
    )


def compact_retry_result(result: dict[str, Any], attempt: int) -> dict[str, Any]:
    wait = dict_field(result, "wait")
    attribution = dict_field(result, "inspection_attribution")
    retry_policy = dict_field(result, "retry_policy")
    summary = {
        "attempt": attempt,
        "status": result.get("status"),
        "verdict": result.get("verdict"),
        "verdict_reason": result.get("verdict_reason"),
        "bucket": result.get("bucket"),
        "retry": retry_policy.get("retry"),
        "attribution_class": result.get("attribution_class") or attribution.get("classification"),
        "phase": result.get("failure_phase") or attribution.get("phase"),
        "inspection_run_id": attribution.get("inspection_run_id") or inspection_run_id(result),
        "total_problems": result.get("total_problems"),
        "cached_total_problems": result.get("cached_total_problems"),
        "proof_failures": result.get("proof_failures"),
        "wait_completion_reason": wait.get("completion_reason"),
        "snapshot_change_kind": result.get("snapshot_change_kind"),
        "results_may_be_stale": result.get("results_may_be_stale"),
        "stale_reasons": result.get("stale_reasons"),
        "capture_incomplete_reason": result.get("capture_incomplete_reason"),
        "snapshot_run_id": result.get("snapshot_run_id"),
        "snapshot_trigger_time_ms": result.get("snapshot_trigger_time_ms"),
        "results_timestamp_ms": result.get("results_timestamp_ms"),
    }
    summary.update(inspection_stage_diagnostics(result, positive_run_id(summary.get("inspection_run_id"))))
    return summary


def compact_inspection_result(result: dict[str, Any]) -> dict[str, Any]:
    return {
        "verdict": result.get("verdict"),
        "reason": result.get("verdict_reason"),
        "message": result.get("verdict_message"),
        "status": result.get("status"),
        "total_problems": result.get("total_problems"),
        "problems_shown": result.get("problems_shown"),
        "attribution": result.get("attribution_class"),
        "phase": result.get("failure_phase"),
        "results_may_be_stale": result.get("results_may_be_stale"),
        "inspection_run_id": inspection_diagnostic_target_run_id(result) or inspection_run_id(result),
    }


def inspection_outcome_for_payload(payload: dict[str, Any]) -> dict[str, Any]:
    existing = payload.get("inspection_outcome")
    if isinstance(existing, dict) and existing.get("status") in {"clean", "red", "unknown", "not_run"}:
        return dict(existing)
    native_result = payload.get("inspection_result")
    has_native_result = isinstance(native_result, dict) and (
        native_result.get("inspection_run_id") is not None
        or native_result.get("status") in {
            "clean", "findings", "results_available", "stale_results", "capture_incomplete", "timed_out"
        }
    )
    native = native_result if isinstance(native_result, dict) else payload
    verdict = native.get("verdict")
    status = native.get("status") or payload.get("status")
    has_inspection_evidence = has_native_result or any(
        payload.get(key) is not None
        for key in ("inspection_verdict", "inspection_run_id", "total_problems", "problems", "inspection_proof")
    ) or verdict in {"GREEN", "RED"}
    freshness = "unknown" if not has_inspection_evidence else "stale" if (
        native.get("results_may_be_stale")
        or payload.get("results_may_be_stale")
        or status == "stale_results"
    ) else "fresh" if verdict in {"GREEN", "RED"} or status in {"clean", "findings", "results_available"} else "unknown"
    return {
        "status": "clean" if verdict == "GREEN" else "red" if verdict == "RED" else "unknown" if has_inspection_evidence else "not_run",
        "verdict": verdict,
        "native_status": status,
        "reason": native.get("reason") or native.get("verdict_reason") or payload.get("verdict_reason"),
        "freshness": freshness,
        "finding_count": native.get("total_problems", payload.get("total_problems")),
        "attribution": native.get("attribution") or native.get("attribution_class"),
        "phase": native.get("phase") or native.get("failure_phase"),
        "inspection_run_id": native.get("inspection_run_id") or inspection_run_id(payload),
    }


def lifecycle_outcome_for_payload(payload: dict[str, Any]) -> dict[str, Any]:
    evidence = payload.get("worktree_mutation_evidence")
    existing = dict_field(payload, "lifecycle_outcome")
    if not isinstance(evidence, dict):
        nested_evidence = existing.get("worktree_mutation_evidence")
        evidence = nested_evidence if isinstance(nested_evidence, dict) else None
    if not isinstance(evidence, dict):
        evidence = {
            "status": "unavailable",
            "paths_limit": MAX_WORKTREE_MUTATION_PATHS,
        }
    before_status = evidence.get("before_status")
    after_status = evidence.get("after_status")
    snapshots_available = before_status == "ok" and after_status == "ok"
    mutated = int(evidence.get("new_or_changed_path_count") or 0) > 0 or int(evidence.get("removed_path_count") or 0) > 0
    lifecycle_status = "mutated" if mutated else "unchanged" if snapshots_available else "unknown"
    return {
        "status": existing.get("status") or lifecycle_status,
        "error_reason": existing.get("error_reason") or ("worktree_mutation_detected" if mutated else None),
        "detection_phase": existing.get("detection_phase") or payload.get("detection_phase"),
        "attribution": existing.get("attribution") or ("unattributed" if mutated else None),
        "snapshot_status": "available" if snapshots_available else "unavailable",
        "worktree_mutation_evidence": evidence,
    }


def inspection_exception_result(error: BaseException) -> dict[str, Any]:
    if isinstance(error, InspectError):
        reason = infer_error_reason(error, error.payload)
        message = str(error)
    else:
        reason = normalize_reason(error.__class__.__name__)
        message = str(error) or error.__class__.__name__
    result = {
        "status": "error",
        "error": message,
        "error_message": message,
        "error_reason": reason,
        "transport_state_unknown": True,
    }
    if isinstance(error, InspectError):
        if error.payload.get("trigger_not_sent") is True:
            result["trigger_not_sent"] = True
            result["transport_state_unknown"] = error.payload.get("transport_state_unknown") is True
        if isinstance(error.payload.get("ide_memory"), dict):
            result["ide_memory"] = error.payload["ide_memory"]
    apply_verdict(result)
    return result


def is_inspection_in_progress_conflict(payload: dict[str, Any]) -> bool:
    return (
        payload.get("status") == "inspection_in_progress"
        or payload.get("error") == "inspection_in_progress"
        or payload.get("inspection_in_progress") is True
    )


def inspection_conflict_request_proof(payload: dict[str, Any]) -> tuple[tuple[str, str] | None, str | None]:
    layouts: list[tuple[str, dict[str, Any], str, str]] = [
        ("requested", payload, "requested_scope", "requested_profile"),
        ("active", payload, "active_scope", "active_profile"),
        ("direct", payload, "scope", "profile"),
    ]
    for key in ("active_request", "requested_request"):
        nested = payload.get(key)
        if isinstance(nested, dict):
            layouts.append((key, nested, "scope", "profile"))

    proofs: list[tuple[str, str]] = []
    partial_layout = False
    invalid_layout = False
    for _, source, scope_key, profile_key in layouts:
        has_scope = scope_key in source
        has_profile = profile_key in source
        if has_scope != has_profile:
            partial_layout = True
            continue
        if not has_scope:
            continue
        scope = source.get(scope_key)
        profile = source.get(profile_key)
        if not isinstance(scope, str) or not scope.strip() or not isinstance(profile, str):
            invalid_layout = True
            continue
        proofs.append((scope.strip().casefold(), profile.strip()))

    if partial_layout or invalid_layout:
        return None, "inspection_in_progress_scope_profile_proof_ambiguous"
    if not proofs:
        return None, "inspection_in_progress_scope_profile_proof_missing"
    if len(set(proofs)) != 1:
        return None, "inspection_in_progress_scope_profile_proof_ambiguous"
    return proofs[0], None


def inspection_conflict_run_id(payload: dict[str, Any]) -> tuple[int | None, str | None]:
    values: list[int] = []
    invalid = False
    for key in ("inspection_run_id", "run_id"):
        if key not in payload:
            continue
        value = positive_run_id(payload.get(key))
        if value is None:
            invalid = True
        else:
            values.append(value)
    if invalid or len(set(values)) > 1:
        return None, "inspection_in_progress_run_id_ambiguous"
    if not values:
        return None, "inspection_in_progress_run_id_missing"
    return values[0], None


def inspection_conflict_proof_failures(
    payload: dict[str, Any],
    trigger_request: dict[str, Any],
) -> tuple[list[str], int | None]:
    failures: list[str] = []
    proof, proof_error = inspection_conflict_request_proof(payload)
    if proof_error is not None:
        failures.append(proof_error)
    else:
        expected_scope = str(trigger_request.get("scope") or "").strip().casefold()
        expected_profile = str(trigger_request.get("profile") or "").strip()
        assert proof is not None
        proof_scope, proof_profile = proof
        if proof_scope != expected_scope:
            failures.append("inspection_in_progress_scope_mismatch")
        if proof_profile != expected_profile:
            failures.append("inspection_in_progress_profile_mismatch")

    expected_scope = str(trigger_request.get("scope") or "").strip().casefold()
    for key in ("include_unversioned", "changed_files_mode"):
        proof_values = []
        for proof_key in (f"requested_{key}", key):
            if proof_key not in payload:
                continue
            value = payload.get(proof_key)
            if key == "include_unversioned" and isinstance(value, bool):
                proof_values.append(str(value).lower())
            elif isinstance(value, str):
                proof_values.append(value.strip().casefold())
            else:
                proof_values.append("<invalid>")
        if not proof_values and expected_scope == "changed_files":
            failures.append(f"inspection_in_progress_{key}_proof_missing")
        elif len(set(proof_values)) > 1 or "<invalid>" in proof_values:
            failures.append(f"inspection_in_progress_{key}_proof_ambiguous")
        elif proof_values:
            expected_value = str(trigger_request.get(key) or "").strip().casefold()
            if proof_values[0] != expected_value:
                failures.append(f"inspection_in_progress_{key}_mismatch")

    run_id, run_id_error = inspection_conflict_run_id(payload)
    if run_id_error is not None:
        failures.append(run_id_error)
    return failures, run_id


def unproven_inspection_conflict_result(
    route: dict[str, Any],
    trigger: dict[str, Any],
    proof_failures: list[str],
) -> dict[str, Any]:
    existing_attribution = trigger.get("inspection_attribution")
    attribution = dict(existing_attribution) if isinstance(existing_attribution, dict) else {}
    attribution.update(
        {
            "source": "helper",
            "classification": "tool_caused",
            "code": "inspection_proof_failed",
            "phase": "trigger",
            "endpoint": "trigger",
            "http_status": 409,
        }
    )
    result = {
        "status": "inspection_in_progress_unproven",
        "error_reason": "inspection_proof_failed",
        "endpoint": "trigger",
        "http_status": 409,
        "response_code": "inspection_in_progress",
        "transport_state_unknown": True,
        "route": route,
        "trigger": trigger,
        "proof_failures": proof_failures,
        "inspection_attribution": attribution,
    }
    apply_verdict(result)
    return result


def run_inspection_on_route(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any]:
    try:
        result = execute_inspection_on_route(args, context, route)
    except InspectError as error:
        reason = infer_error_reason(error, error.payload)
        if reason == "ide_memory_exhausted":
            raise
        memory_error = ide_memory_failure(route)
        if memory_error is not None:
            error.payload["ide_memory"] = memory_error.payload["ide_memory"]
            if memory_error.payload["error_reason"] == "ide_memory_exhausted" and (
                reason in {"timeout", "inspection_api_timeout", "inspection_api_unavailable"}
                or int(error.payload.get("http_status") or 0) >= 500
            ):
                memory_error.payload["original_failure"] = error.payload
                raise memory_error from error
        raise
    if result.get("verdict") == "UNKNOWN":
        memory_error = ide_memory_failure(route)
        if memory_error is not None:
            result["ide_memory"] = memory_error.payload["ide_memory"]
            if memory_error.payload["error_reason"] == "ide_memory_exhausted" and (
                result.get("verdict_reason") in {
                    "timeout", "inspection_api_timeout", "inspection_api_unavailable", "no_results", "capture_incomplete",
                }
                or int(result.get("http_status") or 0) >= 500
            ):
                result["original_failure_reason"] = result.get("verdict_reason")
                result["status"] = "error"
                result["error_reason"] = memory_error.payload["error_reason"]
                apply_verdict(result)
    return result


def execute_inspection_on_route(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any]:
    try:
        trigger_request = trigger_params(args, context, route)
        trigger = call_endpoint(route, "trigger", trigger_request)
    except InspectError as error:
        if infer_error_reason(error, error.payload) != "inspection_api_timeout":
            raise
        return recover_inspection_transport_timeout(args, context, route, error)
    active_route = trigger.get("route") or route

    adopted_conflicting_run = is_inspection_in_progress_conflict(trigger)
    if adopted_conflicting_run:
        proof_failures, accepted_run_id = inspection_conflict_proof_failures(trigger, trigger_request)
        if proof_failures:
            return unproven_inspection_conflict_result(active_route, trigger, proof_failures)
    else:
        accepted_run_id = inspection_run_id(trigger)
    timeout_ms = getattr(args, "timeout_ms", DEFAULT_WAIT_TIMEOUT_MS)
    wait_params = route_params(args, context, active_route) | {
        "timeout_ms": timeout_ms,
        "poll_ms": getattr(args, "poll_ms", DEFAULT_POLL_MS),
    }
    if accepted_run_id is not None:
        wait_params["inspection_run_id"] = accepted_run_id
    try:
        wait = call_endpoint(active_route, "wait", wait_params, timeout=wait_http_timeout(timeout_ms))
    except InspectError as error:
        if infer_error_reason(error, error.payload) != "inspection_api_timeout":
            raise
        return recover_inspection_transport_timeout(
            args,
            context,
            active_route,
            error,
            trigger,
            owns_run=not adopted_conflicting_run,
        )
    wait_run_id = inspection_run_id(wait)
    if accepted_run_id is not None and wait_result_run_changed(wait, accepted_run_id):
        return inspection_run_changed_result(active_route, trigger, wait)
    cancellation = cancel_timed_out_inspection(
        args,
        context,
        active_route,
        wait,
        expected_run_id=accepted_run_id or wait_run_id,
        owns_run=not adopted_conflicting_run,
    )
    problems_request = problems_params(args, context, active_route)
    if accepted_run_id is not None:
        problems_request["inspection_run_id"] = accepted_run_id
    try:
        problems = call_endpoint(active_route, "problems", problems_request)
    except InspectError as error:
        return inspection_endpoint_failure_result(error, active_route, trigger, wait, cancellation)
    if accepted_run_id is not None and inspection_result_run_changed(problems, accepted_run_id):
        return inspection_run_changed_result(active_route, trigger, problems, phase="problems")
    if getattr(args, "include_stale", False):
        problems.setdefault("include_stale", True)
    summary = summarize_problems(
        context,
        problems.get("route") or active_route,
        problems,
        allow_text_only_coverage=getattr(args, "allow_text_only_coverage", False),
    )
    summary["trigger"] = trigger
    summary["wait"] = wait
    if cancellation is not None:
        summary["cancellation"] = cancellation
    summary["status"] = classify_run_status(wait, problems)
    apply_verdict(summary)
    return summary


def inspection_run_changed_result(
    route: dict[str, Any],
    trigger: dict[str, Any],
    observed: dict[str, Any],
    phase: str = "wait",
) -> dict[str, Any]:
    result = {
        "status": "run_changed",
        "error_reason": "run_changed",
        "run_changed": True,
        "transport_state_unknown": True,
        "expected_inspection_run_id": inspection_run_id(trigger),
        "inspection_run_id": inspection_run_id(observed) or positive_run_id(observed.get("snapshot_run_id")),
        "route": route,
        "trigger": trigger,
        phase: observed,
    }
    copy_verdict_evidence(result, observed)
    for key in INSPECTION_STAGE_EVIDENCE_KEYS:
        result.pop(key, None)
    apply_verdict(result)
    return result


def inspection_result_run_changed(payload: dict[str, Any], expected_run_id: int) -> bool:
    current_run_id = inspection_run_id(payload)
    snapshot_run_id = positive_run_id(payload.get("snapshot_run_id"))
    if current_run_id is not None and current_run_id != expected_run_id:
        return True
    if payload.get("inspection_in_progress") is True:
        return False
    return snapshot_run_id is not None and snapshot_run_id != expected_run_id


def wait_result_run_changed(payload: dict[str, Any], expected_run_id: int) -> bool:
    current_run_id = inspection_run_id(payload)
    if current_run_id is not None and current_run_id != expected_run_id:
        return True
    if payload.get("inspection_in_progress") is True:
        return False
    snapshot_run_id = positive_run_id(payload.get("snapshot_run_id"))
    return snapshot_run_id is not None and snapshot_run_id != expected_run_id


def positive_run_id(value: Any) -> int | None:
    if isinstance(value, int) and not isinstance(value, bool) and value > 0:
        return value
    if isinstance(value, str) and value.isdigit() and int(value) > 0:
        return int(value)
    return None


def direct_inspection_run_id(payload: dict[str, Any]) -> int | None:
    for key in ("inspection_run_id", "run_id"):
        run_id = positive_run_id(payload.get(key))
        if run_id is not None:
            return run_id
    evidence_ids = dict_field(payload, "evidence_ids")
    evidence_run_id = positive_run_id(evidence_ids.get("inspection_run_id"))
    if evidence_run_id is not None:
        return evidence_run_id
    attribution = dict_field(payload, "inspection_attribution")
    attribution_run_id = positive_run_id(attribution.get("inspection_run_id"))
    if attribution_run_id is not None:
        return attribution_run_id
    return None


def inspection_diagnostic_payloads(payload: dict[str, Any]) -> list[tuple[dict[str, Any], int | None]]:
    candidates: list[tuple[dict[str, Any], int | None]] = []
    pending: list[tuple[dict[str, Any], int | None]] = [(payload, None)]
    visited: set[int] = set()
    while pending:
        candidate, inherited_run_id = pending.pop(0)
        identity = id(candidate)
        if identity in visited:
            continue
        visited.add(identity)
        candidate_run_id = direct_inspection_run_id(candidate) or inherited_run_id
        if candidate_run_id is None:
            candidate_run_id = positive_run_id(candidate.get("expected_inspection_run_id"))
        candidates.append((candidate, candidate_run_id))
        for key in INSPECTION_DIAGNOSTIC_CHILD_KEYS:
            child = candidate.get(key)
            if isinstance(child, dict):
                pending.append((child, candidate_run_id))
    return candidates


def inspection_diagnostic_target_run_id(payload: dict[str, Any]) -> int | None:
    expected_run_id = positive_run_id(payload.get("expected_inspection_run_id"))
    if expected_run_id is not None:
        return expected_run_id
    for candidate in (
        payload,
        payload.get("inspection_failure"),
        payload.get("trigger"),
    ):
        if isinstance(candidate, dict):
            run_id = direct_inspection_run_id(candidate)
            if run_id is not None:
                return run_id
    observed_run_ids = {
        run_id
        for _, run_id in inspection_diagnostic_payloads(payload)
        if run_id is not None
    }
    if len(observed_run_ids) == 1:
        return next(iter(observed_run_ids))
    return None


def nonnegative_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    return None


def is_inspection_stage_label(value: Any) -> bool:
    return isinstance(value, str) and INSPECTION_STAGE_LABEL_PATTERN.fullmatch(value) is not None


def bounded_inspection_stage_history(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    history: list[dict[str, Any]] = []
    for entry in value[-MAX_INSPECTION_STAGE_HISTORY:]:
        if not isinstance(entry, dict):
            continue
        stage = entry.get("stage")
        elapsed_ms = nonnegative_int(entry.get("elapsed_ms"))
        if not is_inspection_stage_label(stage) or elapsed_ms is None:
            continue
        history.append({"stage": stage, "elapsed_ms": elapsed_ms})
    return history


def bounded_inspection_failure_diagnostic(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    source = value.get("source")
    outcome = value.get("outcome")
    if source not in INSPECTION_FAILURE_SOURCES or outcome not in INSPECTION_FAILURE_OUTCOMES:
        return {}
    diagnostic: dict[str, Any] = {"source": source, "outcome": outcome}
    stage = value.get("inspection_stage_at_failure")
    if is_inspection_stage_label(stage):
        diagnostic["inspection_stage_at_failure"] = stage
    for key in ("inspection_stage_elapsed_ms", "inspection_run_elapsed_ms"):
        elapsed_ms = nonnegative_int(value.get(key))
        if elapsed_ms is not None:
            diagnostic[key] = elapsed_ms
    if isinstance(value.get("dumb_mode"), bool):
        diagnostic["dumb_mode"] = value["dumb_mode"]
    tool_short_name = value.get("inspection_tool_short_name")
    if isinstance(tool_short_name, str) and tool_short_name:
        diagnostic["inspection_tool_short_name"] = redact_inspection_diagnostic_text(tool_short_name)[
            :MAX_INSPECTION_TOOL_SHORT_NAME_LENGTH
        ]
    inspection_file = value.get("inspection_file")
    if isinstance(inspection_file, str) and inspection_file:
        diagnostic["inspection_file"] = inspection_file[:MAX_INSPECTION_FILE_LENGTH]
    worker_phase = value.get("inspection_worker_phase")
    if worker_phase in INSPECTION_WORKER_PHASES:
        diagnostic["inspection_worker_phase"] = worker_phase
    worker_thread = value.get("inspection_worker_thread")
    if isinstance(worker_thread, str) and worker_thread:
        diagnostic["inspection_worker_thread"] = redact_inspection_diagnostic_text(worker_thread)[
            :MAX_INSPECTION_WORKER_THREAD_LENGTH
        ]
    stack = value.get("inspection_worker_stack")
    if isinstance(stack, list):
        frames = [
            redact_inspection_diagnostic_text(frame)[:MAX_INSPECTION_WORKER_FRAME_LENGTH]
            for frame in stack
            if isinstance(frame, str) and frame
        ][:MAX_INSPECTION_WORKER_STACK]
        if frames:
            diagnostic["inspection_worker_stack"] = frames
    return diagnostic


def bounded_inspection_failure_history(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    history: list[dict[str, Any]] = []
    for entry in value:
        diagnostic = bounded_inspection_failure_diagnostic(entry)
        if diagnostic:
            history.append(diagnostic)
        if len(history) >= MAX_INSPECTION_FAILURE_HISTORY:
            break
    return history


def redact_inspection_diagnostic_text(value: str) -> str:
    bearer_redacted = SENSITIVE_OUTPUT_BEARER_PATTERN.sub(
        lambda match: f"{match.group(1)}{REDACTED}",
        value,
    )
    return SENSITIVE_OUTPUT_ASSIGNMENT_PATTERN.sub(
        lambda match: f"{match.group(1)}{match.group(2)}{REDACTED}",
        bearer_redacted,
    )


def native_completion_observation(payload: dict[str, Any], target_run_id: int | None = None) -> dict[str, Any]:
    target_run_id = target_run_id or inspection_diagnostic_target_run_id(payload)
    if target_run_id is None:
        return {}
    for candidate, candidate_run_id in inspection_diagnostic_payloads(payload):
        if candidate_run_id not in {None, target_run_id}:
            continue
        observation = candidate.get("native_tool_completion_observation")
        if not isinstance(observation, dict) or positive_run_id(observation.get("inspection_run_id")) != target_run_id:
            continue
        if observation.get("schema_version") != 1 or observation.get("mode") != "report_only":
            continue
        bounded: dict[str, Any] = {
            "schema_version": observation["schema_version"],
            "mode": "report_only",
            "inspection_run_id": target_run_id,
            "enumeration_complete": observation.get("enumeration_complete") is True,
        }
        for key in (
            "candidate_execution_count", "completion_event_count", "distinct_completion_count",
            "matched_candidate_count", "missing_completion_count", "unmatched_completion_count",
            "negative_problem_count_events", "examples_limit",
        ):
            count = nonnegative_int(observation.get(key))
            if count is not None:
                bounded[key] = count
        reason = observation.get("unavailable_reason")
        if isinstance(reason, str):
            bounded["unavailable_reason"] = redact_inspection_diagnostic_text(reason)[:200]
        for key in ("missing_examples", "completed_examples", "exclusion_examples", "unexplained_missing_examples"):
            examples = observation.get(key)
            if isinstance(examples, list):
                bounded[key] = [
                    {field: redact_inspection_diagnostic_text(value)[:1024]
                     for field, value in example.items()
                     if field in {"tool", "file", "file_hash", "reason"} and isinstance(value, str)}
                    for example in examples[:25] if isinstance(example, dict)
                ]
        exclusions = observation.get("exclusions")
        if isinstance(exclusions, dict):
            bounded["exclusions"] = {
                key: exclusions[key] for key in ("language_not_applicable", "disabled_for_file", "disabled_or_unresolved_scope")
                if nonnegative_int(exclusions.get(key)) is not None
            }
        limitations = observation.get("limitations")
        if isinstance(limitations, list):
            bounded["limitations"] = [redact_inspection_diagnostic_text(value)[:200]
                                      for value in limitations[:10] if isinstance(value, str)]
        would_block = observation.get("candidate_rule_would_block_clean")
        if not bounded["enumeration_complete"] or not isinstance(would_block, bool):
            would_block = None
        actual = inspection_outcome_for_payload(payload).get("verdict") or payload.get("verdict")
        bounded["candidate_rule_would_block_clean"] = would_block
        bounded["actual_verdict"] = actual
        bounded["hypothetical_candidate_rule_verdict"] = (
            None if would_block is None else "UNKNOWN" if actual == "GREEN" and would_block else actual
        )
        classified = bounded["enumeration_complete"] and observation.get("missing_classification_complete") is True
        bounded["missing_classification_complete"] = classified
        reason = observation.get("missing_classification_unavailable_reason")
        if isinstance(reason, str):
            bounded["missing_classification_unavailable_reason"] = redact_inspection_diagnostic_text(reason)[:200]
        counts = observation.get("missing_classification_counts")
        if classified and isinstance(counts, dict):
            bounded["missing_classification_counts"] = {
                key: counts[key] for key in ("empty_visitor", "non_empty_visitor", "external_annotator_batch", "not_probed")
                if nonnegative_int(counts.get(key)) is not None
            }
        unexplained = nonnegative_int(observation.get("unexplained_missing_completion_count"))
        if classified and unexplained is not None:
            bounded["unexplained_missing_completion_count"] = unexplained
        if not classified:
            bounded.pop("unexplained_missing_examples", None)
        silent_skip_block = observation.get("silent_skip_rule_would_block_clean")
        if not classified or not isinstance(silent_skip_block, bool):
            silent_skip_block = None
        bounded["silent_skip_rule_would_block_clean"] = silent_skip_block
        bounded["hypothetical_silent_skip_rule_verdict"] = (
            None if silent_skip_block is None else "UNKNOWN" if actual == "GREEN" and silent_skip_block else actual
        )
        return bounded
    return {}


def inspection_stage_diagnostics(payload: Any, target_run_id: int | None = None) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    target_run_id = target_run_id or inspection_diagnostic_target_run_id(payload)
    if target_run_id is None:
        return {}
    candidates = [
        (index, candidate)
        for index, (candidate, run_id) in enumerate(inspection_diagnostic_payloads(payload))
        if run_id in {None, target_run_id}
    ]

    stage_snapshots: list[tuple[tuple[int, int, int, int], dict[str, Any]]] = []
    stage_histories: list[tuple[tuple[int, int, int], list[dict[str, Any]]]] = []
    failure_histories: list[tuple[tuple[int, int, int], list[dict[str, Any]]]] = []
    failure_diagnostics: list[tuple[int, dict[str, Any]]] = []
    for index, candidate in candidates:
        snapshot: dict[str, Any] = {}
        stage = candidate.get("inspection_stage")
        if is_inspection_stage_label(stage):
            snapshot["inspection_stage"] = stage
        for key in ("inspection_stage_elapsed_ms", "inspection_run_elapsed_ms"):
            elapsed_ms = nonnegative_int(candidate.get(key))
            if elapsed_ms is not None:
                snapshot[key] = elapsed_ms
        terminal_outcome = candidate.get("inspection_terminal_outcome")
        if terminal_outcome in INSPECTION_TERMINAL_OUTCOMES:
            snapshot["inspection_terminal_outcome"] = terminal_outcome
        stage_history = bounded_inspection_stage_history(candidate.get("inspection_stage_history"))
        run_elapsed_ms = snapshot.get("inspection_run_elapsed_ms", -1)
        if stage_history:
            stage_histories.append(((len(stage_history), run_elapsed_ms, index), stage_history))
        if snapshot:
            stage_snapshots.append(
                (
                    (
                        1 if "inspection_terminal_outcome" in snapshot else 0,
                        run_elapsed_ms,
                        len(stage_history),
                        index,
                    ),
                    snapshot,
                )
            )
        failure = bounded_inspection_failure_diagnostic(candidate.get("inspection_failure_diagnostic"))
        if failure:
            failure_diagnostics.append((index, failure))
        failure_history = bounded_inspection_failure_history(candidate.get("inspection_failure_history"))
        if failure_history:
            failure_elapsed_ms = max(
                (entry.get("inspection_run_elapsed_ms", -1) for entry in failure_history),
                default=-1,
            )
            failure_histories.append(((len(failure_history), failure_elapsed_ms, index), failure_history))

    result: dict[str, Any] = {}
    if stage_snapshots:
        result.update(max(stage_snapshots, key=lambda item: item[0])[1])
    if stage_histories:
        result["inspection_stage_history"] = max(stage_histories, key=lambda item: item[0])[1]

    failure_history = max(failure_histories, key=lambda item: item[0])[1] if failure_histories else []
    observed_failures = [diagnostic for _, diagnostic in failure_diagnostics]
    primary_failure = failure_history[0] if failure_history else next(
        (diagnostic for diagnostic in observed_failures if diagnostic.get("source") != "cancellation"),
        observed_failures[0] if observed_failures else None,
    )
    if primary_failure is not None:
        result["inspection_failure_diagnostic"] = primary_failure
    if not failure_history and primary_failure is not None:
        failure_history = [primary_failure]
        for failure in observed_failures:
            if failure not in failure_history:
                failure_history.append(failure)
            if len(failure_history) >= MAX_INSPECTION_FAILURE_HISTORY:
                break
    if len(failure_history) > 1:
        result["inspection_failure_history"] = failure_history
    if result:
        result[INSPECTION_STAGE_RUN_ID_KEY] = target_run_id
    return result


def inspection_endpoint_failure_result(
    error: InspectError,
    route: dict[str, Any],
    trigger: dict[str, Any],
    wait: dict[str, Any],
    cancellation: dict[str, Any] | None,
) -> dict[str, Any]:
    cancellation_settled = cancellation is not None and cancellation.get("settled") is True
    result: dict[str, Any] = {
        "status": "error",
        "error": str(error),
        "error_message": str(error),
        "error_reason": infer_error_reason(error, error.payload),
        "endpoint": error.payload.get("endpoint"),
        "transport_state_unknown": wait.get("inspection_in_progress") is True and not cancellation_settled,
        "route": route,
        "trigger": trigger,
        "wait": wait,
    }
    for key in ("http_status", "response_code", "client_run_id"):
        if error.payload.get(key) is not None:
            result[key] = error.payload[key]
    copy_verdict_evidence(result, error.payload)
    attribution = dict_field(result, "inspection_attribution")
    result.setdefault("http_status", attribution.get("http_status"))
    result.setdefault("response_code", attribution.get("code"))
    result.setdefault("client_run_id", attribution.get("client_run_id"))
    result = {key: value for key, value in result.items() if value is not None}
    if cancellation is not None:
        result["cancellation"] = cancellation
    apply_verdict(result)
    return result


def recover_inspection_transport_timeout(
    args: argparse.Namespace,
    context: dict[str, Any],
    route: dict[str, Any],
    error: InspectError,
    trigger: dict[str, Any] | None = None,
    owns_run: bool = True,
) -> dict[str, Any]:
    expected_run_id = inspection_run_id(trigger or {})
    status: dict[str, Any] = {}
    status_error: InspectError | None = None
    try:
        status = call_endpoint(route, "status", route_params(args, context, route))
    except InspectError as probe_error:
        status_error = probe_error

    wait = dict(status)
    wait["timed_out"] = True
    if expected_run_id is not None:
        wait.setdefault("inspection_run_id", expected_run_id)

    cancellation: dict[str, Any] | None = None
    if status.get("inspection_in_progress") is True:
        active_run_id = inspection_run_id(status)
        if expected_run_id is not None and active_run_id == expected_run_id:
            cancellation = cancel_timed_out_inspection(
                args,
                context,
                route,
                wait,
                expected_run_id=expected_run_id,
                owns_run=owns_run,
            )
        else:
            cancellation = {
                "status": "run_changed" if expected_run_id is not None else "not_requested",
                "requested": False,
                "settled": False,
                "reason": "run_changed" if expected_run_id is not None else "run_id_unknown",
                "expected_inspection_run_id": expected_run_id,
                "inspection_run_id": active_run_id,
                "last_status": status,
            }

    result: dict[str, Any] = {
        "status": "error",
        "error": str(error),
        "error_message": str(error),
        "error_reason": "inspection_api_timeout",
        "timeout_endpoint": error.payload.get("endpoint"),
        "transport_timeout": True,
        "transport_state_unknown": status_error is not None or trigger is None,
        "route": route,
        "wait": wait,
    }
    if trigger:
        result["trigger"] = trigger
    if cancellation is not None:
        result["cancellation"] = cancellation
    if status_error is not None:
        result["status_probe_error"] = {
            "reason": infer_error_reason(status_error, status_error.payload),
            "message": str(status_error),
        }
    apply_verdict(result)
    return result


def inspection_run_id(payload: dict[str, Any]) -> int | None:
    for key in ("inspection_run_id", "run_id"):
        run_id = positive_run_id(payload.get(key))
        if run_id is not None:
            return run_id
    return None


def cancel_timed_out_inspection(
    args: argparse.Namespace,
    context: dict[str, Any],
    route: dict[str, Any],
    wait: dict[str, Any],
    expected_run_id: int | None = None,
    owns_run: bool = True,
) -> dict[str, Any] | None:
    if wait.get("timed_out") is not True or wait.get("inspection_in_progress") is not True:
        return None
    expected_run_id = expected_run_id or inspection_run_id(wait)
    if not owns_run:
        return {
            "status": "not_requested",
            "requested": False,
            "settled": False,
            "reason": "foreign_run_not_owned",
            "expected_inspection_run_id": expected_run_id,
            "last_status": wait,
        }
    if expected_run_id is None:
        return {
            "status": "not_requested",
            "requested": False,
            "settled": False,
            "reason": "run_id_unknown",
            "last_status": wait,
        }
    cancel_params = route_params(args, context, route)
    cancel_params["inspection_run_id"] = expected_run_id
    try:
        response = call_endpoint(route, "cancel", cancel_params)
    except InspectError as error:
        return {
            "status": "failed",
            "requested": False,
            "settled": False,
            "reason": infer_error_reason(error, error.payload),
        }
    if response.get("status") == "run_changed":
        return {
            "status": "run_changed",
            "requested": False,
            "settled": False,
            "reason": "run_changed",
            "response": response,
            "last_status": wait,
        }
    response_run_id = inspection_run_id(response)
    if response_run_id is not None and response_run_id != expected_run_id:
        return {
            "status": "run_changed",
            "requested": False,
            "settled": False,
            "reason": "run_changed",
            "response": response,
            "last_status": wait,
        }

    deadline = now_ms() + DEFAULT_CANCELLATION_SETTLE_TIMEOUT_MS
    last_status = wait
    while now_ms() <= deadline:
        try:
            last_status = call_endpoint(route, "status", route_params(args, context, route))
        except InspectError as error:
            return {
                "status": "failed",
                "requested": response.get("inspection_cancellation_requested") is True,
                "settled": False,
                "reason": infer_error_reason(error, error.payload),
                "response": response,
            }
        last_status_run_id = inspection_run_id(last_status)
        if last_status_run_id != expected_run_id:
            return {
                "status": "run_changed",
                "requested": response.get("inspection_cancellation_requested") is True,
                "settled": False,
                "reason": "run_changed",
                "expected_inspection_run_id": expected_run_id,
                "inspection_run_id": last_status_run_id,
                "response": response,
                "last_status": last_status,
            }
        if last_status.get("inspection_in_progress") is not True:
            return {
                "status": "settled",
                "requested": response.get("inspection_cancellation_requested") is True,
                "settled": True,
                "response": response,
                "last_status": last_status,
            }
        time.sleep(max(DEFAULT_POLL_MS, 1_000) / 1000.0)
    return {
        "status": "pending",
        "requested": response.get("inspection_cancellation_requested") is True,
        "settled": False,
        "response": response,
        "last_status": last_status,
    }


def prepare_lifecycle(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    prepared, _, _ = prepare_lifecycle_details(args, context)
    return prepared


def prepare_lifecycle_details(args: argparse.Namespace, context: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], str | None]:
    command = canonical_command(str(getattr(args, "command", "")))
    if context.get("_repository_preparation_completed") is True:
        repository_preparation = bounded_repository_preparation(
            context.get("repository_preparation"),
            target_worktree=str(repository_preparation_target(context)),
        )
    elif command in {"open-worktree", "agent", "run", "closeout", ""}:
        repository_preparation = run_repository_preparation(args, context)
    else:
        repository_preparation = bounded_repository_preparation(
            context.get("repository_preparation"),
            target_worktree=str(repository_preparation_target(context)),
        )
    lease = create_local_lease(context, state="preparing")
    validated_route: dict[str, Any] | None = None
    opened_by_helper = False
    open_attempts: list[dict[str, Any]] = []
    close_proof: str | None = None
    preparation_stage = "route_discovery"
    python_sdk_preparation: dict[str, Any] | None = None
    try:
        exact_route = find_exact_route(args, context)
        if exact_route is None:
            if not getattr(args, "open", False):
                lease["open_not_attempted"] = True
                raise InspectError(
                    "Exact worktree is not open in a JetBrains IDE.",
                    3,
                    {"context": public_context(context), "lease": public_lease(lease)},
                )
            preparation_stage = "open_policy"
            ensure_trusted_auto_open_root(context)
            ensure_jetbrains_trusted_locations(context)
            preparation_stage = "project_open"
            persist_preparation_lease(
                lease,
                state="open_requesting",
                stage="project_open",
                opened_by_helper=False,
                open_method=None,
                open_attempts=open_attempts,
            )
            open_method, open_attempts, opened_by_helper = open_project_for_lifecycle(args, context, lease)
            open_response_unknown = lifecycle_open_response_unknown(open_attempts)
            persist_preparation_lease(
                lease,
                state="open_registered" if opened_by_helper else "open_requesting" if open_response_unknown else "open_observed",
                stage="route_wait",
                opened_by_helper=opened_by_helper,
                open_method=open_method,
                open_attempts=open_attempts,
            )
            preparation_stage = "route_wait"
            exact_route = wait_for_exact_route_after_open(
                args,
                context,
                getattr(args, "prepare_timeout_ms", DEFAULT_PREPARE_TIMEOUT_MS),
                open_attempts,
                lease,
            )
        else:
            open_method = "preexisting"
            reclaimed_lease = matching_deferred_lease(context, exact_route)
            if reclaimed_lease is not None:
                remove_lease(lease)
                lease = reclaimed_lease
                opened_by_helper = True
                open_method = "reclaimed_deferred"

        preparation_stage = "route_validation"
        ensure_exact_worktree(exact_route, context, args)
        ensure_helper_open_route_identity(lease, exact_route)
        validated_route = exact_route
        persist_preparation_lease(
            lease,
            state="route_resolved",
            stage="lifecycle_claim",
            opened_by_helper=opened_by_helper,
            open_method=open_method,
            open_attempts=open_attempts,
            route=validated_route,
        )
        preparation_stage = "lifecycle_claim"
        claim_metadata, close_proof, opened_by_helper, validated_route = claim_lifecycle(
            args,
            context,
            validated_route,
            lease,
        )
        persist_preparation_lease(
            lease,
            state="ownership_claimed" if opened_by_helper else "ownership_not_proven",
            stage="python_sdk_preparation",
            opened_by_helper=opened_by_helper,
            open_method=open_method,
            open_attempts=open_attempts,
            route=validated_route,
            claim_metadata=claim_metadata,
        )
        preparation_stage = "python_sdk_preparation"
        python_sdk_preparation = prepare_owned_python_sdk(
            args, context, validated_route, lease, close_proof, repository_preparation,
        )
        lease["python_sdk_preparation"] = python_sdk_preparation
        persist_preparation_lease(
            lease,
            state="ownership_claimed" if opened_by_helper else "ownership_not_proven",
            stage="readiness_wait",
            opened_by_helper=opened_by_helper,
            open_method=open_method,
            open_attempts=open_attempts,
            route=validated_route,
            claim_metadata=claim_metadata,
        )
        preparation_stage = "readiness_wait"
        readiness = wait_until_route_ready_with_prepared_sdk_retry(
            args,
            context,
            validated_route,
            getattr(args, "prepare_timeout_ms", DEFAULT_PREPARE_TIMEOUT_MS),
        )
        persist_preparation_lease(
            lease,
            state="prepared",
            stage="prepared",
            opened_by_helper=opened_by_helper,
            open_method=open_method,
            open_attempts=open_attempts,
            route=validated_route,
            claim_metadata=claim_metadata,
            prepared_at_ms=now_ms(),
        )
        prepared = {
            "status": "prepared",
            "context": public_context(context),
            "repository_preparation": repository_preparation,
            "route": validated_route,
            "lease": public_lease(lease),
            "opened_by_helper": opened_by_helper,
            "open_method": open_method,
            "open_attempts": open_attempts,
            "claim": claim_metadata,
            "python_sdk_preparation": python_sdk_preparation,
        }
        if isinstance(readiness, dict):
            prepared["readiness_barrier"] = readiness
        return prepared, lease, close_proof
    except BaseException as error:
        if isinstance(error, InspectError) and validated_route is not None:
            memory_error = ide_memory_failure(validated_route)
            if memory_error is not None:
                reason = infer_error_reason(error, error.payload)
                error.payload["ide_memory"] = memory_error.payload["ide_memory"]
                if memory_error.payload["error_reason"] == "ide_memory_exhausted" and reason in {
                    "timeout", "ide_not_ready_timeout", "inspection_api_timeout", "inspection_api_unavailable",
                }:
                    error.payload["original_failure_reason"] = reason
                    error.payload["error_reason"] = "ide_memory_exhausted"
                    error.payload["error_message"] = str(memory_error)
        if python_sdk_preparation is not None and isinstance(error, InspectError):
            error.payload.setdefault("python_sdk_preparation", python_sdk_preparation)
        cleanup = cleanup_failed_preparation(
            lease=lease,
            route=validated_route,
            close_proof=close_proof,
            error=error,
            stage=preparation_stage,
        )
        if isinstance(error, InspectError):
            error.payload.setdefault("context", public_context(context))
            error.payload.setdefault("preparation_stage", preparation_stage)
            cleanup_payload = public_payload(cleanup)
            lease_payload = public_lease(lease)
            error.payload.setdefault("cleanup", cleanup_payload)
            error.payload.setdefault("lease", lease_payload)
            error.payload["preparation_cleanup"] = cleanup_payload
            error.payload["preparation_lease"] = lease_payload
        elif (
            python_sdk_preparation is not None
            and python_sdk_preparation.get("status") == "prepared"
            and isinstance(error, Exception)
        ):
            raise InspectError(
                "Project preparation failed after the Python SDK preparation stage.",
                3,
                {"error_reason": "helper_plugin_error", "error_type": type(error).__name__,
                 "error_message": str(error), "python_sdk_preparation": python_sdk_preparation,
                 "preparation_stage": preparation_stage, "context": public_context(context),
                 "cleanup": public_payload(cleanup), "lease": public_lease(lease)},
            ) from error
        raise


def persist_preparation_lease(
    lease: dict[str, Any],
    state: str,
    stage: str,
    opened_by_helper: bool,
    open_method: str | None,
    open_attempts: list[dict[str, Any]],
    route: dict[str, Any] | None = None,
    claim_metadata: dict[str, Any] | None = None,
    prepared_at_ms: int | None = None,
) -> None:
    potential_open = lease.get("open_request_may_have_been_accepted") is True
    if lifecycle_open_response_unknown(open_attempts):
        potential_open = True
    if opened_by_helper or (
        claim_metadata is not None
        and claim_metadata.get("ownership_determined") is True
    ):
        potential_open = False
    updates: dict[str, Any] = {
        "opened_by_helper": opened_by_helper,
        "open_request_may_have_been_accepted": potential_open,
        "open_method": open_method,
        "open_attempts": open_attempts,
        "preparation_stage": stage,
        "pid": os.getpid(),
    }
    if route is not None:
        updates.update(
            {
                "route": route,
                "project_instance_id": route.get("project_instance_id"),
                "project_key": route.get("project_key"),
                "session_id": route.get("session_id"),
            }
        )
    else:
        updates.update(accepted_open_identity(open_attempts))
    if claim_metadata is not None:
        updates["plugin_claim"] = claim_metadata
    if prepared_at_ms is not None:
        updates["prepared_at_ms"] = prepared_at_ms
    lease.update({key: value for key, value in updates.items() if value is not None})
    mark_lease_state(lease, state)


def accepted_open_identity(open_attempts: list[dict[str, Any]]) -> dict[str, Any]:
    for attempt in reversed(open_attempts):
        if not attempt.get("ownership_registered"):
            continue
        identity = dict_field(attempt, "identity")
        session_id = identity.get("session_id") or attempt.get("session_id")
        port = identity.get("port")
        return {
            "session_id": session_id,
            "ide_port": port,
        }
    return {}


def lifecycle_open_response_unknown(open_attempts: list[dict[str, Any]]) -> bool:
    return any(attempt.get("request_may_have_been_accepted") is True for attempt in open_attempts)


def project_open_was_not_requested(attempts: Any) -> bool:
    if not isinstance(attempts, list):
        return False
    for attempt in attempts:
        if not isinstance(attempt, dict) or any(key in attempt for key in (
            "identity", "lease_id", "ownership_registered", "lifecycle_ownership_protocol",
            "open_outcome", "request_may_have_been_accepted", "endpoint_status", "opening_scheduled",
        )):
            return False
        if attempt.get("method") == "bootstrap_ide":
            continue
        if attempt.get("reason") == "no_matching_running_ide" and attempt.get("accepted") is False:
            continue
        return False
    return True


def lease_proves_open_not_attempted(lease: dict[str, Any]) -> bool:
    return (
        lease.get("state") in POTENTIAL_OPEN_LEASE_STATES
        and (
            (
                lease.get("preparation_failure_stage") == "project_open"
                and lease.get("preparation_failure_reason") in FAILURE_REASONS_RAISED_BEFORE_OPEN_REQUEST
            )
            or lease.get("open_not_attempted") is True
        )
        and lease.get("opened_by_helper") is False
        and lease.get("open_request_may_have_been_accepted") is False
        and (
            lease.get("open_attempts") == []
            or lease.get("open_not_attempted") is True and project_open_was_not_requested(lease.get("open_attempts"))
        )
        and not lease.get("session_id")
        and not lease.get("ide_port")
        and not lease.get("project_instance_id")
        and not lease.get("project_key")
        and not lease.get("route")
    )


def lease_may_own_open_project(lease: dict[str, Any]) -> bool:
    if lease_proves_open_not_attempted(lease):
        return False
    return (
        lease.get("opened_by_helper") is True
        or lease.get("open_request_may_have_been_accepted") is True
        or lease.get("state") in POTENTIAL_OPEN_LEASE_STATES
    )


def ensure_helper_open_route_identity(lease: dict[str, Any], route: dict[str, Any]) -> None:
    if not lease_may_own_open_project(lease):
        return
    expected_session_id = lease.get("session_id")
    actual_session_id = route.get("session_id")
    expected_port = lease.get("ide_port")
    actual_port = route.get("port") or (route.get("ide") or {}).get("port")
    if not expected_session_id or not actual_session_id or expected_session_id != actual_session_id:
        raise InspectError(
            "Lifecycle open resolved in a different or unverified IDE session.",
            3,
            {
                "error_reason": "session_drift",
                "session_drift": True,
                "expected_session_id": expected_session_id,
                "actual_session_id": actual_session_id,
            },
        )
    if expected_port and actual_port and int(expected_port) != int(actual_port):
        raise InspectError(
            "Lifecycle open resolved on a different IDE port.",
            3,
            {
                "error_reason": "session_drift",
                "session_drift": True,
                "expected_port": int(expected_port),
                "actual_port": int(actual_port),
            },
        )


def cleanup_failed_preparation(
    lease: dict[str, Any],
    route: dict[str, Any] | None,
    close_proof: str | None,
    error: BaseException,
    stage: str,
) -> dict[str, Any]:
    if not lease_may_own_open_project(lease):
        try:
            return cleanup_lifecycle(lease, route or {}, None)
        except BaseException as cleanup_error:
            return {
                "status": "failed",
                "cleanup_failed": True,
                "reason": "preexisting_lease_release_failed",
                "message": str(cleanup_error),
            }

    if isinstance(error, InspectError) and lease.get("opened_by_helper") and stage == "readiness_wait":
        last_status = dict_field(error.payload, "last_status")
        if ide_churn_present(last_status):
            return defer_active_preparation_cleanup(lease, error, stage, last_status)

    proof = close_proof
    ownership_proven: bool | None = True if proof is not None else None
    reclaim_error: BaseException | None = None
    if proof is None and route is not None and route.get("project_instance_id"):
        try:
            ownership_proven, proof, claim_metadata, route = reclaim_lifecycle_claim(lease, route)
            if ownership_proven is True:
                persist_claimed_cleanup_ownership(lease, route, claim_metadata)
            elif ownership_proven is False:
                lease["opened_by_helper"] = False
                return cleanup_lifecycle(lease, route, None)
        except BaseException as error_reclaiming_proof:
            proof = None
            reclaim_error = error_reclaiming_proof
    if ownership_proven is True and proof is not None and route is not None:
        try:
            cleanup = cleanup_lifecycle(lease, route, proof)
            if cleanup.get("status") == "closed":
                return cleanup
            return defer_failed_preparation_cleanup(
                lease,
                route,
                error,
                stage,
                cleanup_result=cleanup,
            )
        except BaseException as cleanup_error:
            return defer_failed_preparation_cleanup(lease, route, error, stage, cleanup_error)
    return defer_failed_preparation_cleanup(lease, route, error, stage, reclaim_error)


def defer_active_preparation_cleanup(
    lease: dict[str, Any],
    error: InspectError,
    stage: str,
    last_status: dict[str, Any],
) -> dict[str, Any]:
    lease.update(
        {
            "preparation_failure_stage": stage,
            "preparation_failure_reason": infer_error_reason(error, error.payload),
            "preparation_last_status": public_payload(last_status),
            "cleanup_next_action": f"Rerun inspect-closeout after indexing/scanning settles; use cleanup-helper-leases --lease-id {lease.get('lease_id')} --no-dry-run if the warm project becomes stale.",
        }
    )
    mark_lease_state(lease, "kept_warm_after_indexing_timeout")
    return {
        "status": "deferred",
        "cleanup_deferred": True,
        "reason": "indexing_or_inspection_still_running",
        "lease_state": "kept_warm_after_indexing_timeout",
        "next_action": lease["cleanup_next_action"],
    }


def persist_claimed_cleanup_ownership(
    lease: dict[str, Any],
    route: dict[str, Any],
    claim_metadata: dict[str, Any],
) -> None:
    lease.update(
        {
            "opened_by_helper": True,
            "route": route,
            "project_instance_id": route.get("project_instance_id"),
            "project_key": route.get("project_key"),
            "session_id": route.get("session_id"),
            "plugin_claim": claim_metadata,
            "preparation_stage": "cleanup_pending",
        }
    )
    mark_lease_state(lease, "cleanup_pending")


def defer_failed_preparation_cleanup(
    lease: dict[str, Any],
    route: dict[str, Any] | None,
    error: BaseException,
    stage: str,
    cleanup_error: BaseException | None = None,
    cleanup_result: dict[str, Any] | None = None,
) -> dict[str, Any]:
    failure_reason = infer_error_reason(error, error.payload) if isinstance(error, InspectError) else normalize_reason(error.__class__.__name__)
    updates: dict[str, Any] = {
        "preparation_failure_stage": stage,
        "preparation_failure_reason": failure_reason,
        "cleanup_next_action": f"Run cleanup-helper-leases --lease-id {lease.get('lease_id')} --no-dry-run after the exact IDE route is available or the helper process exits.",
    }
    sdk_worker_active = (
        isinstance(cleanup_result, dict)
        and cleanup_result.get("reason") == "python_sdk_preparation_in_progress"
    )
    if sdk_worker_active:
        updates["cleanup_next_action"] = (
            "The IDE is still finishing or cancelling Python SDK preparation. Keep this lease; "
            f"run cleanup-helper-leases --lease-id {lease.get('lease_id')} --no-dry-run after the worker exits. "
            "Do not repeat SDK preparation or force-close the project."
        )
    if route is not None:
        updates.update(
            {
                "route": route,
                "project_instance_id": route.get("project_instance_id"),
                "project_key": route.get("project_key"),
                "session_id": route.get("session_id"),
            }
        )
    if cleanup_error is not None:
        updates["cleanup_error"] = cleanup_error.__class__.__name__
    if cleanup_result is not None:
        updates["cleanup_result"] = public_payload(cleanup_result)
    lease.update({key: value for key, value in updates.items() if value is not None})
    state_write_error: BaseException | None = None
    try:
        mark_lease_state(lease, "cleanup_pending")
    except BaseException as error_writing_state:
        lease["state"] = "cleanup_pending"
        state_write_error = error_writing_state
    result = {
        "status": "failed" if state_write_error is not None else "deferred",
        "cleanup_deferred": state_write_error is None,
        "cleanup_failed": state_write_error is not None,
        "reason": "preparation_cleanup_state_write_failed" if state_write_error is not None else (
            "python_sdk_preparation_in_progress" if sdk_worker_active else "preparation_cleanup_pending"
        ),
        "lease_state": "cleanup_pending",
        "next_action": lease["cleanup_next_action"],
    }
    if cleanup_error is not None:
        result["cleanup_error"] = cleanup_error.__class__.__name__
    if cleanup_result is not None:
        result["close_result"] = public_payload(cleanup_result)
    if state_write_error is not None:
        result["state_write_error"] = state_write_error.__class__.__name__
    return result


def find_exact_route(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any] | None:
    probe_args = copy_args(args, open=False)
    try:
        route = resolve_route(probe_args, context)
        ensure_exact_worktree(route, context, args)
    except InspectError:
        return None
    return route


def wait_for_exact_route_after_open(
    args: argparse.Namespace,
    context: dict[str, Any],
    timeout_ms: int,
    open_attempts: list[dict[str, Any]],
    lease: dict[str, Any] | None = None,
) -> dict[str, Any]:
    deadline = now_ms() + timeout_ms
    retry_opening = any(
        attempt.get("open_outcome") == "already_opening" for attempt in open_attempts
    )
    already_opening_retries = 0
    last_error: InspectError | None = None
    last_lifecycle_open_probe: dict[str, Any] | None = None
    diagnostic_probe_supported = any(
        int(attempt.get("lifecycle_open_diagnostic_version") or 0) >= 1
        for attempt in open_attempts
    )
    if diagnostic_probe_supported:
        retry_opening = False
    while now_ms() <= deadline:
        route = find_exact_route(args, context)
        if route is not None:
            return route
        if diagnostic_probe_supported:
            current_probe = probe_lifecycle_open(args, context, lease, deadline_ms=deadline)
            if current_probe is not None:
                last_lifecycle_open_probe = current_probe
        if retry_opening:
            try:
                retry_attempt = open_via_running_ide(
                    args,
                    context,
                    open_attempts,
                    "running_ide_retry",
                    lease,
                )
            except InspectError as error:
                last_error = error
            else:
                if retry_attempt is not None and retry_attempt.get("open_outcome") == "already_opening":
                    already_opening_retries += 1
                    if already_opening_retries >= ALREADY_OPENING_RETRY_LIMIT:
                        payload = auto_open_timeout_payload(args, context, timeout_ms)
                        payload.update(
                            {
                                "error_reason": "already_opening_stuck",
                                "open_attempts": open_attempts,
                                "already_opening_retry_count": already_opening_retries,
                            }
                        )
                        raise InspectError(
                            "JetBrains lifecycle open remained stuck in already_opening.",
                            3,
                            payload,
                        )
                elif retry_attempt is not None:
                    retry_opening = False
        time.sleep(2)
    payload = auto_open_timeout_payload(args, context, timeout_ms)
    payload["error_reason"] = "project_open_blocked"
    payload["open_attempts"] = open_attempts
    if last_lifecycle_open_probe is not None:
        payload["lifecycle_open_probe"] = last_lifecycle_open_probe
    if last_error is not None:
        payload["last_error"] = str(last_error)
    raise InspectError("Timed out waiting for JetBrains IDE to open the exact worktree.", 3, payload)


def auto_open_timeout_payload(args: argparse.Namespace, context: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    causes = [
        "JetBrains trust or safe-mode prompt is waiting for confirmation.",
        "The IDE is asking whether to open the project in a new window, current window, or attach mode.",
        "The configured macOS app name did not launch the IDE product that has this plugin installed.",
        "The inspection plugin is disabled, missing, or has not written its registry heartbeat yet.",
    ]
    payload = {
        "context": public_context(context),
        "ide": context.get("ide"),
        "worktree_root": context.get("worktree_root"),
        "target_worktree": lifecycle_target_path(context),
        "selected_trusted_root": selected_trusted_root_for_payload(context),
        "global_config": str(global_config_path()),
        "background_open": getattr(args, "background_open", False),
        "prepare_timeout_ms": timeout_ms,
        "blocked_diagnostic": project_open_blocked_diagnostic(args, context, timeout_ms),
        "likely_causes": causes,
        "hint": "Run again with --foreground-open, trust the project if prompted, set JetBrains project opening to New Window, or open the worktree manually once.",
    }
    payload.update(route_diagnostic_payload(args, context))
    return payload


def project_open_blocked_diagnostic(args: argparse.Namespace, context: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return {
        "reason": PROJECT_OPEN_BLOCKED_REASON,
        "message": PROJECT_OPEN_BLOCKED_HINT,
        "background_open": getattr(args, "background_open", False),
        "prepare_timeout_ms": timeout_ms,
        "requested_ide": context.get("ide"),
        "requested_ide_app": context.get("ide_app"),
        "target_worktree": lifecycle_target_path(context),
        "selected_trusted_root": selected_trusted_root_for_payload(context),
    }


def selected_trusted_root_for_payload(context: dict[str, Any]) -> str | None:
    try:
        return str(trusted_root_for_worktree(context))
    except InspectError:
        return None


def lifecycle_target_path(context: dict[str, Any]) -> str | None:
    return context.get("lifecycle_target_path") or context.get("exact_route_path") or context.get("project_path") or context.get("worktree_root")


def zero_project_hint() -> str:
    return (
        "Discovered a JetBrains inspection plugin identity but zero open projects. "
        "A pending Trust Project, safe-mode, or open-project prompt may be preventing project loading."
    )


def route_diagnostic_payload(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    try:
        identities = discover_diagnostic_identities(getattr(args, "port", None))
    except InspectError as error:
        return {"route_diagnostic": {"discovery_error": str(error)}}
    target_ide = context.get("ide")
    projects = [
        flatten_project(identity, project)
        for identity in identities
        for project in identity.get("open_projects", []) or []
    ]
    matching_identities = [identity for identity in identities if identity_matches_context(identity, context)]
    matching_projects = [
        project
        for project in projects
        if flat_project_matches_context(project, context)
    ]
    other_projects = [
        project
        for project in projects
        if project not in matching_projects
    ]
    diagnostic = {
        "requested_ide": target_ide,
        "target_worktree": lifecycle_target_path(context),
        "target_project_path": context.get("project_path"),
        "discovered_identity_count": len(identities),
        "matching_identity_count": len(matching_identities),
        "discovered_project_count": len(projects),
        "matching_project_count": len(matching_projects),
        "identities": [public_identity_summary(identity) for identity in identities],
        "matching_projects": matching_projects[:10],
        "other_projects": other_projects[:10],
    }
    if identities and not matching_identities and target_ide:
        diagnostic["reason"] = "different_jetbrains_product_running"
        diagnostic["next_action"] = f"Open the worktree in {target_ide} with the inspection plugin installed and up to date for that IDE, or update repo config/--ide to one of the discovered JetBrains products."
    elif matching_identities and not matching_projects:
        diagnostic["reason"] = "target_ide_running_without_target_project"
        diagnostic["next_action"] = "Open the exact worktree in the configured IDE, check for a pending Trust Project, safe-mode, or open-project prompt, verify that IDE has the inspection plugin installed and up to date, or allow inspect-closeout to open it under a trusted root."
    elif not identities:
        diagnostic["reason"] = "no_plugin_instances_discovered"
        diagnostic["next_action"] = "Launch the configured JetBrains IDE with the inspection plugin installed; if an IDE was launched hidden/background, also check whether it is blocked before plugin registration by a Trust Project, safe-mode, or open-project prompt."
    return {"route_diagnostic": diagnostic}


def discover_diagnostic_identities(port: int | None) -> list[dict[str, Any]]:
    if port:
        return discover_identities(port)
    return merged_registry_and_port_identities()


def merged_registry_and_port_identities() -> list[dict[str, Any]]:
    identities_by_key: dict[str, dict[str, Any]] = {}
    for identity in registry_identities():
        identities_by_key[identity_key(identity)] = identity
    for candidate_port in configured_ports():
        try:
            identity = identity_for_port(candidate_port)
        except InspectError:
            continue
        key = identity_key(identity)
        identities_by_key[key] = merge_identity(identities_by_key.get(key), identity)
    return list(identities_by_key.values())


def merge_identity(existing: dict[str, Any] | None, live: dict[str, Any]) -> dict[str, Any]:
    if not existing:
        return live
    merged = existing.copy()
    for key, value in live.items():
        if value not in (None, "", []):
            merged[key] = value
    return merged


def identity_key(identity: dict[str, Any]) -> str:
    session_id = identity.get("session_id")
    if session_id:
        return f"session:{session_id}"
    port = identity.get("port")
    if port:
        return f"port:{port}"
    return json.dumps(public_identity_summary(identity), sort_keys=True)


def public_identity_summary(identity: dict[str, Any]) -> dict[str, Any]:
    return {
        "ide_name": identity.get("ide_name") or identity.get("name"),
        "ide_product_code": identity.get("ide_product_code") or identity.get("product_code"),
        "ide_version": identity.get("ide_version") or identity.get("version"),
        "plugin_version": identity.get("plugin_version"),
        "plugin_build_fingerprint": identity.get("plugin_build_fingerprint"),
        "plugin_build_dirty": identity.get("plugin_build_dirty"),
        "inspection_execution_proof_version": identity.get("inspection_execution_proof_version"),
        "lifecycle_ownership_protocol": identity.get("lifecycle_ownership_protocol"),
        "lifecycle_open_diagnostic_version": identity.get("lifecycle_open_diagnostic_version"),
        "session_id": identity.get("session_id"),
        "port": identity.get("port"),
        "pid": identity.get("pid"),
        "open_project_count": len(identity.get("open_projects", []) or []),
    }


def plugin_identity_label(identity: dict[str, Any]) -> str:
    version = identity.get("plugin_version") or "unknown"
    fingerprint = identity.get("plugin_build_fingerprint")
    if fingerprint:
        return f"{version}@{fingerprint}"
    return str(version)


def flat_project_matches_context(project: dict[str, Any], context: dict[str, Any]) -> bool:
    if not identity_matches_context(
        {
            "ide_name": project.get("ide_name"),
            "ide_product_code": project.get("ide_product_code"),
        },
        context,
    ):
        return False
    target = context.get("exact_route_path") or context.get("project_path") or context.get("worktree_root")
    base_path = project.get("base_path")
    if not target or not base_path:
        return False
    try:
        return Path(str(base_path)).resolve() == Path(str(target)).resolve()
    except OSError:
        return str(base_path) == str(target)


def wait_until_route_ready(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    started_at_ms = monotonic_ms()
    deadline = started_at_ms + timeout_ms
    last_status: dict[str, Any] | None = None
    stable_ready_count = 0
    sample_count = 0
    while monotonic_ms() <= deadline:
        body = call_endpoint(route, "status", route_params(args, context, route))
        last_status = body
        sample_count += 1
        if route_status_ready(body):
            stable_ready_count += 1
            if stable_ready_count >= ROUTE_READY_STABLE_OBSERVATIONS:
                return {
                    "schema_version": READINESS_BARRIER_SCHEMA_VERSION,
                    "strategy": "route_activity_ready_gate_v1",
                    "status": "ready",
                    "ready": True,
                    "required_stable_observations": ROUTE_READY_STABLE_OBSERVATIONS,
                    "stable_observations": stable_ready_count,
                    "sample_count": sample_count,
                    "elapsed_ms": max(0, monotonic_ms() - started_at_ms),
                    "last_status": compact_retry_status(body),
                    "observation_scope": "ide_status_only",
                    "same_worktree_writer_observation": "not_available",
                }
        else:
            stable_ready_count = 0
        time.sleep(max(DEFAULT_POLL_MS, 1_000) / 1000.0)
    lifecycle_readiness = lifecycle_readiness_from_payload(last_status or {})
    readiness_reason = str(lifecycle_readiness.get("reason") or "") if lifecycle_readiness else ""
    content_root_failure = readiness_reason in {"no_content_roots", "content_roots_outside_target"}
    language_sdk_missing = readiness_reason == "python_sdk_missing"
    project_analysis_not_ready = readiness_reason in {
        "python_sdk_updating",
        "python_sdk_update_state_unavailable",
        "project_analysis_running",
        "analysis_readiness_unavailable",
    }
    error_reason: str
    if content_root_failure:
        error_reason = PROJECT_CONTENT_ROOTS_MISSING_REASON
    elif language_sdk_missing:
        error_reason = "language_sdk_missing"
    elif project_analysis_not_ready:
        error_reason = "project_analysis_not_ready"
    else:
        error_reason = "ide_not_ready_timeout"
    raise InspectError(
        (
            "JetBrains opened the worktree but did not establish a content root covering it."
            if content_root_failure
            else "JetBrains opened the worktree but no Python SDK is configured for the selected project files."
            if language_sdk_missing
            else "Timed out waiting for the configured language SDK and project analysis to settle."
            if project_analysis_not_ready
            else "Timed out waiting for JetBrains indexing/scanning to settle."
        ),
        3,
        {
            "status": "timeout",
            "error_reason": error_reason,
            "lifecycle_readiness": lifecycle_readiness,
            "last_status": last_status or {},
            "readiness_barrier": {
                "schema_version": READINESS_BARRIER_SCHEMA_VERSION,
                "strategy": "route_activity_ready_gate_v1",
                "status": "timeout",
                "ready": False,
                "required_stable_observations": ROUTE_READY_STABLE_OBSERVATIONS,
                "stable_observations": stable_ready_count,
                "sample_count": sample_count,
                "elapsed_ms": max(0, monotonic_ms() - started_at_ms),
                "last_status": compact_retry_status(last_status or {}),
                "observation_scope": "ide_status_only",
                "same_worktree_writer_observation": "not_available",
            },
            "route": route,
        }
        | route_diagnostic_payload(args, context),
    )


def wait_until_route_ready_with_prepared_sdk_retry(
    args: argparse.Namespace,
    context: dict[str, Any],
    route: dict[str, Any],
    timeout_ms: int,
) -> dict[str, Any]:
    try:
        return wait_until_route_ready(args, context, route, timeout_ms)
    except InspectError as error:
        retry_payload = dict(error.payload)
        retry_payload.setdefault("context", public_context(context))
        if (
            infer_error_reason(error, retry_payload) != "language_sdk_missing"
            or not prepared_python_sdk_discovery_pending(retry_payload)
        ):
            raise

        retry_trigger = {
            "bucket": "capture_not_ready",
            "verdict_reason": "language_sdk_missing",
            "retry_policy": {
                "retry": True,
                "max_attempts": 1,
                "wait_ms": UNKNOWN_RETRY_WAIT_MS,
            },
        }
        retry_readiness = wait_for_internal_retry_readiness(
            args,
            context,
            route,
            retry_trigger,
        )
        if retry_readiness.get("ready") is not True:
            error.payload["internal_retry_count"] = 0
            error.payload["internal_retry_readiness"] = retry_readiness
            raise

        readiness = dict(retry_readiness)
        readiness["internal_retry_count"] = 1
        readiness["internal_retry_reason"] = "language_sdk_missing"
        readiness["internal_retry_readiness"] = retry_readiness
        return readiness


def route_status_ready(body: dict[str, Any]) -> bool:
    if body.get("session_drift") or body.get("ambiguous") or body.get("unavailable"):
        return False
    if body.get("indexing") or body.get("is_scanning") or body.get("inspection_in_progress"):
        return False
    lifecycle_readiness = lifecycle_readiness_from_payload(body)
    if lifecycle_readiness and lifecycle_readiness.get("ready") is False:
        return False
    status = str(body.get("status") or body.get("completion_reason") or "").lower()
    if status in {"indexing", "running", "timed_out", "session_drift", "ambiguous", "unavailable"}:
        return False
    return True


def lifecycle_readiness_from_payload(payload: dict[str, Any]) -> dict[str, Any] | None:
    direct = payload.get("lifecycle_readiness")
    if isinstance(direct, dict):
        return direct
    route = payload.get("route")
    if isinstance(route, dict) and isinstance(route.get("lifecycle_readiness"), dict):
        return route["lifecycle_readiness"]
    return None


def open_attempt_payload(
    method: str,
    context: dict[str, Any],
    accepted: bool,
    identity: dict[str, Any] | None = None,
    error: InspectError | None = None,
    command: list[str] | None = None,
    response: dict[str, Any] | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "method": method,
        "accepted": accepted,
        "target_worktree": lifecycle_target_path(context),
        "requested_ide": context.get("ide"),
    }
    if identity:
        payload["identity"] = public_identity_summary(identity)
    if command:
        payload["command"] = command
    if response:
        payload["endpoint_status"] = response.get("status")
        payload["reason"] = response.get("reason")
        payload["opening_scheduled"] = response.get("opening_scheduled")
        payload["opened"] = response.get("opened")
        payload["session_id"] = response.get("session_id")
        payload["lease_id"] = response.get("lease_id")
        payload["ownership_registered"] = response.get("ownership_registered")
        payload["lifecycle_ownership_protocol"] = response.get("lifecycle_ownership_protocol")
        payload["lifecycle_open_diagnostic_version"] = response.get("lifecycle_open_diagnostic_version")
        if isinstance(response.get("lifecycle_open_diagnostic"), dict):
            payload["lifecycle_open_diagnostic"] = response["lifecycle_open_diagnostic"]
    if error:
        payload["error_reason"] = infer_error_reason(error, error.payload)
        payload["message"] = str(error)
    return payload


def probe_lifecycle_open(
    args: argparse.Namespace,
    context: dict[str, Any],
    lease: dict[str, Any] | None,
    *,
    deadline_ms: int | None = None,
) -> dict[str, Any] | None:
    try:
        identities = discover_open_identities(args, context)
    except InspectError:
        return None
    best_error: dict[str, Any] | None = None
    for identity in identities:
        if not identity_matches_context(identity, context):
            continue
        if int(identity.get("lifecycle_open_diagnostic_version") or 0) < 1:
            continue
        port = identity.get("port")
        if not port:
            continue
        timeout_seconds = max(DEFAULT_TIMEOUT_SECONDS, 30.0)
        if deadline_ms is not None:
            remaining_seconds = (deadline_ms - now_ms()) / 1000.0
            if remaining_seconds < MIN_DIAGNOSTIC_PROBE_TIMEOUT_SECONDS:
                break
            timeout_seconds = min(timeout_seconds, remaining_seconds)
        if lease is not None and identity.get("session_id") == lease.get("session_id") and port == lease.get("ide_port"):
            memory_error = ide_memory_failure(identity, timeout_seconds=min(2.0, timeout_seconds))
            if memory_error is not None and memory_error.payload["error_reason"] == "ide_memory_exhausted":
                raise memory_error
        if deadline_ms is not None:
            remaining_seconds = (deadline_ms - now_ms()) / 1000.0
            if remaining_seconds < MIN_DIAGNOSTIC_PROBE_TIMEOUT_SECONDS:
                break
            timeout_seconds = min(timeout_seconds, remaining_seconds)
        try:
            response = http_get(
                int(port),
                "lifecycle/open",
                {
                    "worktree_path": lifecycle_target_path(context),
                    "project_path": context.get("project_path"),
                    "ide": context.get("ide"),
                    "session_id": identity.get("session_id"),
                    "lease_id": lease.get("lease_id") if lease is not None else None,
                    "probe": "true",
                },
                timeout=timeout_seconds,
            )
        except InspectError as error:
            payload = public_payload(error.payload)
            payload.update({
                "status": "error",
                "error_reason": infer_error_reason(error, error.payload),
                "message": str(error),
            })
            if best_error is None or (
                not isinstance(best_error.get("lifecycle_open_diagnostic"), dict)
                and isinstance(payload.get("lifecycle_open_diagnostic"), dict)
            ):
                best_error = payload
            continue
        return public_payload(response.body)
    return best_error


def open_via_running_ide(
    args: argparse.Namespace,
    context: dict[str, Any],
    attempts: list[dict[str, Any]] | None = None,
    method: str = "running_ide",
    lease: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    try:
        identities = discover_open_identities(args, context)
    except InspectError as error:
        reason = infer_error_reason(error, error.payload)
        if getattr(args, "port", None) and reason in {"inspection_api_unavailable", "timeout"}:
            return None
        raise
    matching = [identity for identity in identities if identity_matches_context(identity, context)]
    first_memory_error: InspectError | None = None
    for identity in matching:
        port = identity.get("port")
        if not port:
            continue
        try:
            check_ide_memory(identity)
        except InspectError as error:
            first_memory_error = first_memory_error or error
            continue
        try:
            if lease is not None:
                persist_open_request_identity(lease, identity, method, attempts if attempts is not None else [])
            response = http_get(
                int(port),
                "lifecycle/open",
                {
                    "worktree_path": lifecycle_target_path(context),
                    "project_path": context.get("project_path"),
                    "ide": context.get("ide"),
                    "session_id": identity.get("session_id"),
                    "lease_id": lease.get("lease_id") if lease is not None else None,
                },
                timeout=max(DEFAULT_TIMEOUT_SECONDS, 30.0),
            )
            attempt = open_attempt_payload(method, context, True, identity, response=response.body)
            outcome, ownership_registered = lifecycle_open_outcome(
                identity,
                response.body,
                lease.get("lease_id") if lease is not None else None,
            )
            attempt["open_outcome"] = outcome
            attempt["ownership_registered"] = ownership_registered
            if attempts is not None:
                attempts.append(attempt)
            return attempt
        except InspectError as error:
            attempt = open_attempt_payload(method, context, False, identity, error)
            if attempts is not None:
                attempts.append(attempt)
            if lease is not None:
                lease["open_attempts"] = attempts or [attempt]
                if ambiguous_lifecycle_open_error(error):
                    attempt["open_outcome"] = "response_unknown"
                    attempt["request_may_have_been_accepted"] = True
                    attempt["ownership_registered"] = False
                    lease["open_request_may_have_been_accepted"] = True
                    mark_lease_state(lease, "open_requesting")
                    return attempt
                mark_lease_state(lease, "open_requesting")
                raise
            continue
    if first_memory_error is not None:
        if lease is not None and project_open_was_not_requested(attempts or []):
            lease["open_not_attempted"] = True
            lease["open_request_may_have_been_accepted"] = False
            lease["open_attempts"] = attempts or []
        raise first_memory_error
    if attempts is not None and not matching:
        attempts.append(
            {
                "method": method,
                "accepted": False,
                "target_worktree": lifecycle_target_path(context),
                "requested_ide": context.get("ide"),
                "reason": "no_matching_running_ide",
                "discovered_identity_count": len(identities),
            }
        )
    return None


def persist_open_request_identity(
    lease: dict[str, Any],
    identity: dict[str, Any],
    method: str,
    open_attempts: list[dict[str, Any]],
) -> None:
    lease.update(
        {
            "opened_by_helper": False,
            "open_method": method,
            "open_attempts": open_attempts,
            "preparation_stage": "project_open",
            "session_id": identity.get("session_id"),
            "ide_port": identity.get("port"),
            "lifecycle_ownership_protocol": identity.get("lifecycle_ownership_protocol"),
            "pid": os.getpid(),
        }
    )
    mark_lease_state(lease, "open_requesting")


def ambiguous_lifecycle_open_error(error: InspectError) -> bool:
    reason = infer_error_reason(error, error.payload)
    message = str(error).lower()
    return reason in {"inspection_api_timeout", "timeout"} and "timed out" in message


def lifecycle_open_outcome(
    identity: dict[str, Any],
    response: dict[str, Any],
    lease_id: str | None,
) -> tuple[str, bool]:
    status = str(response.get("status") or "")
    reason = str(response.get("reason") or "")
    response_session_id = response.get("session_id")
    identity_session_id = identity.get("session_id")
    protocol_matches = (
        identity.get("lifecycle_ownership_protocol") == LIFECYCLE_OWNERSHIP_PROTOCOL
        and response.get("lifecycle_ownership_protocol") == LIFECYCLE_OWNERSHIP_PROTOCOL
    )
    ownership_registered = bool(
        lease_id
        and response.get("ownership_registered") is True
        and response.get("lease_id") == lease_id
        and response_session_id
        and identity_session_id
        and response_session_id == identity_session_id
        and protocol_matches
    )
    if ownership_registered:
        return "helper_registered", True
    if response.get("opening_scheduled") is True:
        return "scheduled_unregistered", False
    if status == "already_open":
        return "already_open", False
    if reason == "already_opening":
        return "already_opening", False
    return "unverified_response", False


def open_method_for_outcome(method: str, attempt: dict[str, Any]) -> str:
    if attempt.get("ownership_registered"):
        return method
    outcome = attempt.get("open_outcome")
    if outcome == "already_open":
        return "preexisting"
    if outcome == "already_opening":
        return "unproven_opening"
    return "unproven_open"


def open_project_for_lifecycle(
    args: argparse.Namespace,
    context: dict[str, Any],
    lease: dict[str, Any] | None = None,
) -> tuple[str, list[dict[str, Any]], bool]:
    attempts: list[dict[str, Any]] = []
    open_attempt = open_via_running_ide(args, context, attempts, "running_ide", lease)
    if open_attempt is not None:
        return open_method_for_outcome("running_ide", open_attempt), attempts, bool(open_attempt.get("ownership_registered"))
    if not getattr(args, "ide_launch", False):
        record_open_not_attempted(lease, attempts)
        raise InspectError(
            "The running IDE did not accept the lifecycle open; application launch is disabled.",
            3,
            {"error_reason": "ide_launch_disabled", "open_attempts": attempts},
        )
    try:
        bootstrap_attempt = bootstrap_ide_app(context, background=getattr(args, "background_open", False))
    except InspectError as error:
        if error.payload.get("error_reason") == "ide_launch_refused":
            record_open_not_attempted(lease, attempts)
            error.payload["open_attempts"] = attempts
        raise
    attempts.append(bootstrap_attempt)
    timeout_ms = getattr(args, "prepare_timeout_ms", DEFAULT_PREPARE_TIMEOUT_MS)
    wait_for_matching_ide_identity(args, context, timeout_ms)
    open_attempt = wait_for_lifecycle_open(args, context, timeout_ms, attempts, lease)
    if open_attempt is not None:
        return open_method_for_outcome("bootstrapped_ide", open_attempt), attempts, bool(open_attempt.get("ownership_registered"))
    raise InspectError(
        "Bootstrapped JetBrains IDE did not accept the lifecycle open request.",
        3,
        auto_open_timeout_payload(args, context, timeout_ms) | {"open_attempts": attempts},
    )


def record_open_not_attempted(lease: dict[str, Any] | None, attempts: list[dict[str, Any]]) -> None:
    if lease is not None and project_open_was_not_requested(attempts):
        lease["open_not_attempted"] = True
        lease["open_request_may_have_been_accepted"] = False
        lease["open_attempts"] = attempts


def wait_for_lifecycle_open(
    args: argparse.Namespace,
    context: dict[str, Any],
    timeout_ms: int,
    attempts: list[dict[str, Any]] | None = None,
    lease: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    deadline = now_ms() + timeout_ms
    while now_ms() <= deadline:
        open_attempt = open_via_running_ide(args, context, attempts, "bootstrapped_ide", lease)
        if open_attempt is not None:
            return open_attempt
        time.sleep(1)
    return None


def wait_for_matching_ide_identity(args: argparse.Namespace, context: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    deadline = now_ms() + timeout_ms
    last_error: InspectError | None = None
    while now_ms() <= deadline:
        try:
            identities = discover_open_identities(args, context)
            for identity in identities:
                if identity_matches_context(identity, context):
                    return identity
        except InspectError as error:
            last_error = error
        time.sleep(1)
    payload = auto_open_timeout_payload(args, context, timeout_ms)
    if last_error:
        payload["last_error"] = str(last_error)
    payload.update(
        {
            "error_reason": "inspection_api_unavailable",
            "failure_phase": "bootstrap_identity",
            "hint": "Install or enable the compatible Inspection API plugin in the selected IDE, restart that IDE, and rerun the lane.",
        }
    )
    raise InspectError("Timed out waiting for the target JetBrains IDE plugin after hidden bootstrap.", 3, payload)


def discover_open_identities(args: argparse.Namespace, context: dict[str, Any]) -> list[dict[str, Any]]:
    identities = discover_identities(args.port)
    if args.port or not context.get("ide") or any(identity_matches_context(identity, context) for identity in identities):
        return identities
    return discover_diagnostic_identities(None)


def identity_matches_context(identity: dict[str, Any], context: dict[str, Any]) -> bool:
    selection = dict_field(context, "ide_selection")
    product_key = selection.get("product_key")
    ide = str(context.get("ide") or "").lower().replace(" ", "")
    if not ide and not product_key:
        return True
    values = [
        str(identity.get("ide_name") or "").lower().replace(" ", ""),
        str(identity.get("ide_product_code") or "").lower().replace(" ", ""),
        str(identity.get("product_code") or "").lower().replace(" ", ""),
        str(identity.get("ide_version") or "").lower().replace(" ", ""),
        str(identity.get("version") or "").lower().replace(" ", ""),
        str(identity.get("build_number") or "").lower().replace(" ", ""),
    ]
    if product_key and product_key in IDE_PRODUCTS:
        product = IDE_PRODUCTS[product_key]
        needles = tuple(alias.replace(" ", "") for alias in product.aliases) + tuple(code.lower() for code in product.product_codes)
    else:
        product = product_for_selector(context.get("ide"))
        needles = tuple(alias.replace(" ", "") for alias in product.aliases) + tuple(code.lower() for code in product.product_codes) if product else (ide,)
    identity_text = " ".join(values)
    selection_channel = selection.get("channel")
    if selection.get("version"):
        identity_version = version_from_jetbrains_text(identity_text)
        requested_version = parse_version_tuple(str(selection.get("version")))
        if requested_version and selection.get("exact") and not identity_version:
            return False
        if identity_version and requested_version and not versions_match(identity_version, requested_version):
            return False
    if selection_channel == "eap" and "eap" not in identity_text:
        return False
    if selection_channel == "stable" and "eap" in identity_text:
        return False
    return any(needle in value for needle in needles for value in values)


def claim_lifecycle(
    args: argparse.Namespace,
    context: dict[str, Any],
    route: dict[str, Any],
    lease: dict[str, Any],
) -> tuple[dict[str, Any], str | None, bool, dict[str, Any]]:
    project_instance_id = route.get("project_instance_id")
    if not project_instance_id:
        raise InspectError(
            "Inspection plugin route does not include project_instance_id; install the updated plugin before inspect-closeout.",
            3,
            {"route": route},
        )
    claim = call_endpoint(route, "lifecycle/claim", route_params(args, context, route) | {
        "project_instance_id": project_instance_id,
        "lease_id": lease.get("lease_id"),
    })
    claimed_route = claim.get("route")
    if not isinstance(claimed_route, dict):
        claimed_route = route
    ensure_exact_worktree(claimed_route, context, args)
    ensure_claim_route_matches(route, claimed_route)
    ownership_proven, close_proof = lifecycle_claim_ownership(claim, lease)
    if ownership_proven is True and close_proof is None:
        raise InspectError(
            "JetBrains lifecycle claim proved ownership without returning a close token.",
            3,
            {"error_reason": "lifecycle_claim_incomplete", "claim": public_payload(claim)},
        )
    claim_metadata = {
        "status": claim.get("status") or "unknown",
        "ownership_proven": ownership_proven is True,
        "ownership_determined": ownership_proven is not None,
        "reason": claim.get("reason"),
        "lifecycle_ownership_protocol": claim.get("lifecycle_ownership_protocol"),
        "project_key": claimed_route.get("project_key"),
        "project_instance_id": claimed_route.get("project_instance_id"),
        "session_id": claimed_route.get("session_id"),
        "lease_id": lease.get("lease_id"),
        "claimed_at_ms": now_ms(),
        "lifecycle_readiness": lifecycle_readiness_from_payload(claim),
    }
    return claim_metadata, close_proof, ownership_proven is True, claimed_route


def prepare_owned_python_sdk(
    args: argparse.Namespace,
    context: dict[str, Any],
    route: dict[str, Any],
    lease: dict[str, Any],
    close_proof: str | None,
    preparation: dict[str, Any],
) -> dict[str, Any]:
    if canonical_command(str(getattr(args, "command", ""))) not in {"open-worktree", "agent", "run", "closeout", ""}:
        return {"status": "skipped", "reason": "preparation_not_requested"}
    if preparation.get("kind") != "python" or not prepared_python_sdk_discovery_pending(
        {"context": context, "repository_preparation": preparation}
    ):
        return {"status": "skipped", "reason": "local_python_environment_not_prepared"}
    ide = dict_field(route, "ide")
    if ide.get("product_code") == "WS":
        return {"status": "skipped", "reason": "non_python_ide_lane"}
    selected_files = getattr(args, "files", None)
    if selected_files and not any(Path(path).suffix.lower() in {".py", ".pyi", ".pyw"} for path in selected_files):
        return {"status": "skipped", "reason": "python_not_selected"}
    if lease.get("opened_by_helper") is not True or not close_proof or not lease.get("lease_id"):
        return {"status": "skipped", "reason": "helper_ownership_not_proven"}
    prior_cleanup = lease.get("cleanup_result")
    if isinstance(prior_cleanup, dict) and prior_cleanup.get("reason") == "python_sdk_preparation_in_progress":
        raise InspectError(
            "A prior SDK worker has not been reconciled for this lease; finish exact-lease cleanup first.",
            3, {"error_reason": "python_sdk_preparation_in_progress", "python_sdk_preparation": {
                "status": "not_attempted", "reason": "python_sdk_preparation_in_progress",
            }},
        )
    version = ide.get("python_sdk_preparation_version")
    if type(version) is not int or version != 1:
        return {"status": "skipped", "reason": "plugin_capability_unavailable"}
    if not all(isinstance(route.get(field), str) and route[field] for field in (
        "project_key", "project_instance_id", "session_id",
    )):
        raise InspectError(
            "Python SDK preparation requires a complete exact-project route.",
            3, {"error_reason": "python_sdk_preparation_route_incomplete"},
        )
    params = route_params(args, context, route) | {
        "lease_id": lease.get("lease_id"),
        "close_token": close_proof,
    }
    try:
        response = http_post(
            route_port(route), "lifecycle/prepare-python-sdk", params, timeout=75.0,
        ).body
    except InspectError as error:
        original_reason = (
            error.payload.get("error_reason") or error.payload.get("reason")
            or error.payload.get("response_code") or "preparation_request_failed"
        )
        error.payload.setdefault("python_sdk_preparation", public_payload(error.payload) | {
            "status": "failed", "reason": original_reason,
        })
        error.payload["error_reason"] = (
            original_reason if str(original_reason).startswith("python_sdk_preparation_")
            else "python_sdk_preparation_request_failed"
        )
        raise
    for field in ("project_key", "project_instance_id", "session_id"):
        if not route.get(field) or response.get(field) != route[field]:
            raise InspectError(
                "Python SDK preparation did not confirm the exact IDE project instance.",
                3,
                {"error_reason": "python_sdk_preparation_session_drift", "session_drift": True, "field": field,
                 "python_sdk_preparation": public_payload(response)},
            )
    counts = [response.get(key) for key in (
        "registered_local_python_sdk_count", "assigned_local_python_sdk_count",
    )]
    module_count = response.get("python_module_count")
    assigned_module_count = response.get("assigned_python_module_count")
    if (
        response.get("status") != "prepared"
        or response.get("operation") not in ("created", "reused", "already_assigned")
        or type(response.get("sdk_preparation_version")) is not int
        or response.get("sdk_preparation_version") != 1
        or not all(type(count) is int and count == 1 for count in counts)
        or type(module_count) is not int or module_count < 1
        or type(assigned_module_count) is not int or assigned_module_count != module_count
        or response.get("project_sdk_assigned") is not True
    ):
        raise InspectError(
            "The IDE could not prepare this worktree's Python SDK.",
            3,
            {"error_reason": "python_sdk_preparation_invalid_response", "python_sdk_preparation": public_payload(response)},
        )
    return public_payload(response)


def python_sdk_preparation_for_payload(payload: dict[str, Any]) -> dict[str, Any] | None:
    for candidate in (payload, payload.get("prepared"), payload.get("inspection_failure")):
        if isinstance(candidate, dict) and isinstance(candidate.get("python_sdk_preparation"), dict):
            return public_payload(candidate["python_sdk_preparation"])
    return None


def ensure_claim_route_matches(expected_route: dict[str, Any], claimed_route: dict[str, Any]) -> None:
    for field in ("session_id", "project_instance_id", "project_key"):
        expected = expected_route.get(field)
        actual = claimed_route.get(field)
        if expected and actual and expected != actual:
            raise InspectError(
                "Lifecycle claim resolved a different project route.",
                3,
                {
                    "error_reason": "session_drift",
                    "session_drift": True,
                    "field": field,
                    "expected": expected,
                    "actual": actual,
                },
            )


def lifecycle_claim_ownership(claim: dict[str, Any], lease: dict[str, Any]) -> tuple[bool | None, str | None]:
    close_proof = claim.pop("close_" + "token", None)
    protocol_matches = claim.get("lifecycle_ownership_protocol") == LIFECYCLE_OWNERSHIP_PROTOCOL
    lease_matches = claim.get("lease_id") == lease.get("lease_id")
    if claim.get("ownership_proven") is True and protocol_matches and lease_matches:
        return True, str(close_proof) if close_proof else None
    if claim.get("ownership_proven") is False and protocol_matches:
        return False, None
    return None, None


def cleanup_lifecycle(lease: dict[str, Any], route: dict[str, Any], close_proof: str | None = None) -> dict[str, Any]:
    if not lease.get("opened_by_helper"):
        reason = "open_not_attempted" if lease_proves_open_not_attempted(lease) or lease.get("open_not_attempted") is True else "project_preexisted"
        mark_lease_state(lease, "released")
        remove_lease(lease)
        return {"status": "not_needed", "reason": reason}
    project_instance_id = lease.get("project_instance_id")
    if not close_proof or not project_instance_id:
        mark_lease_state(lease, "cleanup_skipped")
        return {"status": "skipped", "cleanup_skipped": True, "reason": "missing_close_token"}
    try:
        close_params = {
            "client_run_id": lease.get("client_run_id"),
            "project_key": lease.get("project_key") or route.get("project_key"),
            "project_path": route.get("base_path"),
            "worktree_path": route.get("base_path"),
            "session_id": lease.get("session_id") or route.get("session_id"),
            "project_instance_id": project_instance_id,
            "lease_id": lease.get("lease_id"),
            "close_" + "token": close_proof,
        }
        close_result = call_lifecycle_close(route, close_params)
    except InspectError as error:
        mark_lease_state(lease, "cleanup_failed")
        result = {
            "status": "failed",
            "cleanup_failed": True,
            "reason": public_cleanup_reason(error),
        }
        for key in ("endpoint", "http_status", "response_code", "client_run_id"):
            if error.payload.get(key) is not None:
                result[key] = error.payload[key]
        copy_verdict_evidence(result, error.payload)
        return result
    status = str(close_result.get("status") or "")
    mark_lease_state(lease, "closed" if status == "closed" else "cleanup_skipped")
    if status == "closed":
        remove_lease(lease)
    return close_result


def call_lifecycle_close(route: dict[str, Any], params: dict[str, Any]) -> dict[str, Any]:
    port = route_port(route)
    try:
        body = private_http_get_body(port, "lifecycle/close", params, timeout=35.0)
    except InspectError as error:
        body = error.payload if isinstance(error.payload, dict) else {}
        attribution = dict_field(body, "inspection_attribution")
        try:
            http_status = int(body.get("http_status") or attribution.get("http_status") or 0)
        except (TypeError, ValueError):
            http_status = 0
        if (
            body.get("session_drift")
            or not body
            or "status" not in body
            or str(body.get("status") or "") == "error"
            or http_status >= 500
            or attribution.get("code") == "inspection_api_http_error"
        ):
            raise
    status = str(body.get("status") or "closed")
    if status == "closed":
        result = {
            "status": "closed",
            "reason": body.get("reason"),
            "cleanup_skipped": False,
            "cleanup_failed": False,
        }
    else:
        result = {
            "status": status,
            "reason": body.get("reason") or status,
            "cleanup_skipped": True,
            "cleanup_failed": False,
            "message": body.get("message"),
            "close_attempts": body.get("close_attempts"),
        }
    for field in (
        "project_instance_id",
        "project_key",
        "lease_id",
        "session_id",
        "closed_at_ms",
        "inspection_run_id",
        "inspection_in_progress",
        "close_attempts",
        "inspection_attribution",
        "http_status",
        "response_code",
        "client_run_id",
    ):
        if body.get(field) is not None:
            result[field] = body[field]
    return result


def public_cleanup_reason(error: InspectError) -> str:
    reason = error.payload.get("reason") or error.payload.get("error_reason") or error.payload.get("status")
    if isinstance(reason, str) and reason:
        return reason
    return "close_failed"


def route_port(route: dict[str, Any]) -> int:
    port = int(route.get("port") or route.get("ide", {}).get("port") or 0)
    if port:
        return port
    base_url = route.get("base_url") or ""
    parsed = urllib.parse.urlparse(base_url)
    port = parsed.port or 0
    if not port:
        raise InspectError("Route did not include an IDE port.", 3, {"route": route})
    return port


def private_http_get_body(port: int, endpoint: str, params: dict[str, Any], timeout: float | None = None) -> dict[str, Any]:
    return http_get(
        port,
        endpoint,
        params,
        timeout=timeout or max(DEFAULT_TIMEOUT_SECONDS, 10.0),
    ).body


def inspection_transport_error(port: int, endpoint: str, error: BaseException) -> InspectError:
    reason = getattr(error, "reason", None)
    timed_out = isinstance(error, TimeoutError) or isinstance(reason, TimeoutError) or "timed out" in str(error).lower()
    error_reason = "inspection_api_timeout" if timed_out else "inspection_api_unavailable"
    condition = "timed out" if timed_out else "unavailable"
    return InspectError(
        f"Inspection API {condition} on port {port}: {error}",
        3,
        {"error_reason": error_reason, "endpoint": endpoint, "port": port},
    )


def resolve_route(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    attempted_open = False
    while True:
        identities = discover_identities(args.port)
        if not identities and args.open and not attempted_open:
            attempted_open = True
            ensure_trusted_auto_open_root(context)
            ensure_jetbrains_trusted_locations(context)
            open_project_for_lifecycle(args, context)
            time.sleep(4)
            continue
        if not identities:
            raise InspectError("No JetBrains inspection plugin instances discovered.", 3, {"hint": "Open the repo in the preferred JetBrains IDE with the inspection plugin installed."})

        candidates: list[dict[str, Any]] = []
        for identity in identities:
            if not identity_matches_context(identity, context):
                continue
            port = identity.get("port")
            if not port:
                continue
            params = selector_params(args, context)
            try:
                result = http_get(int(port), "route", params)
                candidate_route = result.body.get("route")
                if result.status == 200 and isinstance(candidate_route, dict) and candidate_route:
                    candidates.append(candidate_route)
            except InspectError:
                continue
        if not candidates and args.open and not attempted_open:
            attempted_open = True
            ensure_trusted_auto_open_root(context)
            ensure_jetbrains_trusted_locations(context)
            open_project_for_lifecycle(args, context)
            time.sleep(4)
            continue
        if not candidates:
            diagnostic_payload = route_diagnostic_payload(args, context)
            diagnostic = diagnostic_payload.get("route_diagnostic")
            if not isinstance(diagnostic, dict):
                diagnostic = {}
            matching_project_count = int(diagnostic.get("matching_project_count") or 0)
            error_reason = "matching_project_route_unavailable" if matching_project_count else "target_project_not_open"
            message = (
                "Matching JetBrains project is visible but route resolution is unavailable."
                if matching_project_count
                else "No open JetBrains project matched this repo/worktree."
            )
            raise InspectError(
                message,
                3,
                {"selector": selector_params(args, context), "error_reason": error_reason}
                | diagnostic_payload,
            )
        selected_route = max(candidates, key=lambda item: route_sort_key(item, context))
        ensure_worktree_safe(selected_route, context, args)
        return selected_route
    raise InspectError("Route resolution exhausted without a terminal result.", 3)


def copy_args(args: argparse.Namespace, **overrides: Any) -> argparse.Namespace:
    values = vars(args).copy()
    values.update(overrides)
    return argparse.Namespace(**values)


def discover_identities(port: int | None) -> list[dict[str, Any]]:
    if port:
        return [identity_for_port(port)]
    return merged_registry_and_port_identities()


def registry_identities() -> list[dict[str, Any]]:
    instances = registry_dir()
    if not instances.exists():
        return []
    now_ms = int(time.time() * 1000)
    identities = []
    for path in sorted(instances.glob("*.json")):
        try:
            data = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        heartbeat = int(data.get("heartbeat_ms") or 0)
        if heartbeat and now_ms - heartbeat > 60_000:
            continue
        pid = data.get("pid")
        if pid and not pid_alive(int(pid)):
            continue
        identities.append(data)
    return identities


def identity_for_port(port: int) -> dict[str, Any]:
    result = http_get(port, "identity", {})
    body = result.body
    try:
        reported_port = int(body.get("port") or 0)
    except (TypeError, ValueError) as error:
        raise InspectError(
            f"Inspection API identity on port {port} reported invalid port {body.get('port')!r}.",
            3,
            {"error_reason": "invalid_identity_port", "requested_port": port, "reported_port": body.get("port")},
        ) from error
    if reported_port and reported_port != port:
        raise InspectError(
            f"Inspection API identity on port {port} reported port {reported_port}.",
            3,
            {"error_reason": "identity_port_mismatch", "requested_port": port, "reported_port": reported_port},
        )
    if "port" not in body or not body.get("port"):
        body["port"] = port
    return body


def http_get(port: int, endpoint: str, params: dict[str, Any], timeout: float = DEFAULT_TIMEOUT_SECONDS) -> HttpResult:
    return http_request(port, endpoint, params, timeout, method="GET")


def http_post(port: int, endpoint: str, params: dict[str, Any], timeout: float = DEFAULT_TIMEOUT_SECONDS) -> HttpResult:
    return http_request(port, endpoint, params, timeout, method="POST")


def http_request(port: int, endpoint: str, params: dict[str, Any], timeout: float, *, method: str) -> HttpResult:
    clean_params = {key: str(value) for key, value in params.items() if value is not None and value != ""}
    if _ACTIVE_CLIENT_RUN_ID and "client_run_id" not in clean_params:
        clean_params["client_run_id"] = _ACTIVE_CLIENT_RUN_ID
    query = urllib.parse.urlencode(clean_params, doseq=True)
    display_query = urllib.parse.urlencode(redact_payload(clean_params), doseq=True)
    # The plugin's built-in server is loopback-only and does not expose TLS.
    # noinspection HttpUrlsUsage
    base_url = f"http://{LOOPBACK_HOST}:{port}/api/inspection/{endpoint}"
    request_url = f"{base_url}?{query}" if query else base_url
    display_url = f"{base_url}?{display_query}" if display_query else base_url
    request = urllib.request.Request(
        request_url, headers={"Accept": "application/json"}, method=method,
        data=b"" if method == "POST" else None,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = parse_http_json(response.read(), endpoint, response.status, clean_params.get("client_run_id"))
            return HttpResult(response.status, body, display_url)
    except urllib.error.HTTPError as error:
        body = parse_http_json(error.read(), endpoint, error.code, clean_params.get("client_run_id"))
        if method == "GET" and error.code == 409 and is_inspection_in_progress_conflict(body):
            return HttpResult(error.code, body, display_url)
        payload = dict(body)
        payload.setdefault("endpoint", endpoint)
        payload.setdefault("http_status", error.code)
        if clean_params.get("client_run_id"):
            payload.setdefault("client_run_id", clean_params["client_run_id"])
        attribution = dict_field(payload, "inspection_attribution")
        payload.setdefault(
            "response_code",
            attribution.get("code") or payload.get("error_reason") or payload.get("reason") or payload.get("status") or "inspection_api_http_error",
        )
        if error.code == 409 and payload.get("session_drift"):
            raise InspectError("IDE session changed; resolve route and re-trigger before trusting results.", 4, payload)
        if error.code == 400:
            raise InspectError(payload.get("message") or payload.get("error") or "Bad inspection request.", 3, payload)
        raise InspectError(f"HTTP {error.code} from inspection API", 3, payload)
    except (urllib.error.URLError, TimeoutError) as error:
        failure = inspection_transport_error(port, endpoint, error)
        failure.payload.setdefault("client_run_id", clean_params.get("client_run_id"))
        raise failure from error


def parse_http_json(raw: bytes, endpoint: str, http_status: int, client_run_id: str | None) -> dict[str, Any]:
    try:
        return parse_json(raw)
    except InspectError as error:
        error.payload.setdefault("endpoint", endpoint)
        error.payload.setdefault("http_status", http_status)
        error.payload.setdefault("client_run_id", client_run_id)
        error.payload.setdefault("response_code", "invalid_api_response")
        raise


def call_endpoint(
    route: dict[str, Any],
    endpoint: str,
    params: dict[str, Any],
    timeout: float | None = None,
) -> dict[str, Any]:
    port = route_port(route)
    if endpoint == "trigger":
        try:
            check_ide_memory(route)
        except InspectError as error:
            error.payload["trigger_not_sent"] = True
            raise
    return http_get(port, endpoint, params, timeout=timeout or max(DEFAULT_TIMEOUT_SECONDS, 10.0)).body


def check_ide_memory(
    identity: dict[str, Any],
) -> None:
    failure = ide_memory_failure(identity)
    if failure is not None and failure.payload["error_reason"] == "ide_memory_exhausted":
        raise failure


def ide_memory_failure(identity: dict[str, Any], timeout_seconds: float = 2.0) -> InspectError | None:
    if identity.get("ide_memory_diagnostic_version") != 1:
        return None
    expected_session = identity.get("session_id")
    if not expected_session:
        return None
    try:
        response = http_get(route_port(identity), "memory", {}, timeout=timeout_seconds).body
    except (InspectError, OSError, http.client.HTTPException):
        return None
    if response.get("session_id") != expected_session:
        return None
    memory = response.get("ide_memory")
    if not isinstance(memory, dict) or memory.get("status") not in {"exhausted", "low_memory"}:
        return None
    exhausted = memory["status"] == "exhausted"
    reason = "ide_memory_exhausted" if exhausted else "ide_memory_pressure"
    payload: dict[str, Any] = {
        "error_reason": reason,
        "ide_memory": {key: memory.get(key) for key in (
            "status", "out_of_memory_at_ms", "low_memory_signal_at_ms", "heap_used_bytes", "heap_max_bytes",
        )},
        "session_id": expected_session,
        "port": route_port(identity),
    }
    message = (
        "IDE memory exhausted. Ask the Director to dismiss any memory-error dialog (such as Java heap space) and restart the IDE before inspecting again."
        if exhausted else
        "Recent IDE memory pressure is diagnostic context and does not prove exhaustion."
    )
    return InspectError(message, 3, payload)


def call_contextual_endpoint(
    route: dict[str, Any],
    endpoint: str,
    params: dict[str, Any],
    context: dict[str, Any],
    timeout: float | None = None,
) -> dict[str, Any]:
    try:
        return call_endpoint(route, endpoint, params, timeout=timeout)
    except InspectError as error:
        error.payload.setdefault("context", public_context(context))
        error.payload.setdefault("route", route)
        error.payload.setdefault("endpoint", endpoint)
        raise


def selector_params(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    params = {
        "project_key": args.project_key,
        "project_path": args.project_path or context.get("project_path"),
        "worktree_path": args.worktree_path or lifecycle_target_path(context),
        "cwd": args.cwd or lifecycle_target_path(context),
        "project": args.project,
        "ide": args.ide or context.get("ide"),
        "session_id": args.session_id,
    }
    client_run_id = getattr(args, "client_run_id", None) or context.get("client_run_id")
    if client_run_id:
        params["client_run_id"] = client_run_id
    return params


def route_params(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any]:
    params = {
        "project_key": args.project_key or route.get("project_key"),
        "session_id": args.session_id or route.get("session_id"),
        "project_path": args.project_path or context.get("project_path"),
        "worktree_path": args.worktree_path or lifecycle_target_path(context),
        "cwd": args.cwd or lifecycle_target_path(context),
        "project": args.project,
        "ide": args.ide or context.get("ide"),
    }
    client_run_id = getattr(args, "client_run_id", None) or context.get("client_run_id")
    if client_run_id:
        params["client_run_id"] = client_run_id
    if route.get("project_instance_id"):
        params["project_instance_id"] = route.get("project_instance_id")
    return params


def inspection_scope_params(
    args: argparse.Namespace,
    context: dict[str, Any],
    default_scope: str = "changed_files",
) -> dict[str, Any]:
    params: dict[str, Any] = {
        "scope": getattr(args, "scope", None) or context.get("scope") or default_scope,
        "include_unversioned": str(getattr(args, "include_unversioned", True)).lower(),
        "changed_files_mode": getattr(args, "changed_files_mode", "all") or "all",
        "profile": getattr(args, "profile", "") or "",
    }
    directory = getattr(args, "directory", None)
    if directory:
        params["dir"] = directory
    files = getattr(args, "files", []) or []
    if files:
        params["files"] = "\n".join(files)
    max_files = getattr(args, "max_files", None)
    if max_files:
        params["max_files"] = max_files
    return params


def trigger_params(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any]:
    params = route_params(args, context, route)
    params.update(inspection_scope_params(args, context))
    return params


def problems_params(args: argparse.Namespace, context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any]:
    params = route_params(args, context, route)
    params.update(inspection_scope_params(args, context))
    params.update({
        "severity": getattr(args, "severity", "all"),
        "problem_type": getattr(args, "problem_type", "all"),
        "file_pattern": getattr(args, "file_pattern", "all"),
        "limit": getattr(args, "limit", 100),
        "offset": getattr(args, "offset", 0),
    })
    if getattr(args, "include_stale", False):
        params["include_stale"] = "true"
    return params


def wait_http_timeout(timeout_ms: int) -> float:
    return max(DEFAULT_TIMEOUT_SECONDS, (timeout_ms / 1000.0) + 5.0)


def summarize_problems(
    context: dict[str, Any],
    route: dict[str, Any],
    body: dict[str, Any],
    allow_text_only_coverage: bool = False,
) -> dict[str, Any]:
    problems = body.get("problems") or []
    status = body.get("status", "unknown")
    results_may_be_stale = body.get("results_may_be_stale", False) or status == "stale_results"
    total_problems = body.get("total_problems")
    has_explicit_zero_result = isinstance(total_problems, int) and total_problems == 0
    summary: dict[str, Any] = {
        "status": status,
        "clean": status == "results_available" and has_explicit_zero_result and not body.get("capture_incomplete") and not results_may_be_stale,
        "context": public_context(context),
        "route": route,
        "capture_incomplete": body.get("capture_incomplete", False),
        "results_may_be_stale": results_may_be_stale,
        "problems": problems,
        "raw": body,
    }
    if allow_text_only_coverage:
        summary["allow_text_only_coverage"] = True
    if "total_problems" in body:
        summary["total_problems"] = body["total_problems"]
    if "problems_shown" in body:
        summary["problems_shown"] = body["problems_shown"]
    elif not results_may_be_stale:
        summary["problems_shown"] = len(problems)
    for key in (
        "cached_total_problems",
        "cached_problems_shown",
        "include_stale",
        "snapshot_outcome",
        "snapshot_change_kind",
        "snapshot_run_id",
        "snapshot_trigger_time_ms",
        "results_source",
        "results_timestamp_ms",
        "stale_reasons",
        "capture_diagnostic",
        *INSPECTION_STAGE_EVIDENCE_KEYS,
        *VERDICT_SOURCE_KEYS,
    ):
        if key in body:
            summary[key] = body[key]
    apply_verdict(summary)
    return summary


def classify_run_status(wait: dict[str, Any], problems: dict[str, Any]) -> str:
    if wait.get("timed_out"):
        return "timed_out"
    if wait.get("capture_incomplete") or problems.get("capture_incomplete"):
        return "capture_incomplete"
    if wait.get("results_may_be_stale") or problems.get("results_may_be_stale") or problems.get("status") == "stale_results":
        return "stale_results"
    total = problems.get("total_problems")
    if problems.get("status") == "results_available" and (
        (problems.get("problems") or []) or (isinstance(total, int) and total > 0)
    ):
        return "findings"
    if problems.get("status") == "results_available" and (isinstance(total, int) and total == 0 or problems.get("clean") is True):
        return "clean"
    if wait.get("completion_reason") == "clean" or wait.get("clean_inspection") is True or wait.get("inspection_verdict") == "GREEN":
        return "clean"
    return problems.get("status") or wait.get("completion_reason") or "unknown"


def should_defer_lifecycle_cleanup(result: dict[str, Any], lease: dict[str, Any]) -> bool:
    if not lease.get("opened_by_helper"):
        return False
    if result.get("verdict") in {"GREEN", "RED"}:
        return False
    cancellation_value = result.get("cancellation")
    cancellation = cancellation_value if isinstance(cancellation_value, dict) else {}
    if cancellation.get("settled") is True:
        last_status_value = cancellation.get("last_status")
        last_status = last_status_value if isinstance(last_status_value, dict) else {}
        return ide_churn_present(last_status)
    if result.get("transport_state_unknown") is True:
        return True
    wait_value = result.get("wait")
    wait = wait_value if isinstance(wait_value, dict) else {}
    return active_ide_churn(result) or active_ide_churn(wait)


def active_ide_churn(payload: dict[str, Any]) -> bool:
    if not payload:
        return False
    return payload.get("timed_out") is True and ide_churn_present(payload)


def ide_churn_present(payload: dict[str, Any]) -> bool:
    status = str(payload.get("status") or payload.get("completion_reason") or "").lower()
    proof_failures_value = payload.get("proof_failures")
    proof_failures = proof_failures_value if isinstance(proof_failures_value, list) else []
    return (
        payload.get("indexing") is True
        or payload.get("is_scanning") is True
        or payload.get("inspection_in_progress") is True
        or status in {"indexing", "running"}
        or any(reason in {"indexing", "inspection_still_running"} for reason in proof_failures)
    )


def defer_lifecycle_cleanup(lease: dict[str, Any], result: dict[str, Any]) -> dict[str, Any]:
    mark_lease_state(lease, "kept_warm_after_indexing_timeout")
    return {
        "status": "deferred",
        "reason": "indexing_or_inspection_still_running",
        "cleanup_deferred": True,
        "lease_id": lease.get("lease_id"),
        "project_key": lease.get("project_key"),
        "project_instance_id": lease.get("project_instance_id"),
        "next_action": f"Rerun inspect-closeout after indexing/scanning settles; use cleanup-helper-leases --lease-id {lease.get('lease_id')} --no-dry-run if the warm project becomes stale.",
        "verdict_reason": result.get("verdict_reason"),
    }


def normalized_psi_marker(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").casefold())


def scope_file_is_project_metadata(file_diagnostic: dict[str, Any]) -> bool:
    file_type = normalized_psi_marker(file_diagnostic.get("file_type"))
    return (
        file_diagnostic.get("directory") is not True
        and file_diagnostic.get("valid") is True
        and not file_diagnostic.get("diagnostic_error")
        and file_diagnostic.get("in_content") is True
        and file_type in PROJECT_METADATA_FILE_TYPES
    )


def scope_file_is_excluded_dependency_lockfile(file_diagnostic: dict[str, Any]) -> bool:
    path = file_diagnostic.get("path")
    filename = Path(str(path)).name.casefold() if path else ""
    return (
        file_diagnostic.get("directory") is not True
        and file_diagnostic.get("valid") is True
        and not file_diagnostic.get("diagnostic_error")
        and file_diagnostic.get("in_content") is False
        and file_diagnostic.get("is_excluded") is True
        and filename in DEPENDENCY_LOCKFILE_NAMES
    )


def scope_file_declared_coverage_role(file_diagnostic: dict[str, Any]) -> str | None:
    classification = file_diagnostic.get("classification")
    coverage_role = file_diagnostic.get("coverage_role")
    if classification is not None and coverage_role is not None and str(classification) != str(coverage_role):
        return None
    role = classification if classification is not None else coverage_role
    return str(role) if role is not None else None


def scope_file_has_declared_coverage_role(file_diagnostic: dict[str, Any]) -> bool:
    return file_diagnostic.get("classification") is not None or file_diagnostic.get("coverage_role") is not None


def scope_file_metadata_classification(file_diagnostic: dict[str, Any]) -> str | None:
    declared_role = scope_file_declared_coverage_role(file_diagnostic)
    if declared_role == PROJECT_METADATA_COVERAGE_ROLE:
        return declared_role if scope_file_is_project_metadata(file_diagnostic) else None
    if declared_role == EXCLUDED_DEPENDENCY_LOCKFILE_COVERAGE_ROLE:
        return declared_role if scope_file_is_excluded_dependency_lockfile(file_diagnostic) else None
    if declared_role is not None:
        return None
    if scope_file_has_declared_coverage_role(file_diagnostic):
        return None
    if scope_file_is_project_metadata(file_diagnostic):
        return PROJECT_METADATA_COVERAGE_ROLE
    return None


def scope_file_semantic_coverage_reasons(file_diagnostic: dict[str, Any]) -> list[str]:
    if file_diagnostic.get("directory") is True:
        return []
    if scope_file_metadata_classification(file_diagnostic) is not None:
        return []
    reasons: list[str] = []
    if scope_file_has_declared_coverage_role(file_diagnostic):
        reasons.append("invalid_metadata_role")
    file_type = normalized_psi_marker(file_diagnostic.get("file_type"))
    psi_language = normalized_psi_marker(file_diagnostic.get("psi_language"))
    psi_class = normalized_psi_marker(file_diagnostic.get("psi_class"))
    if (
        file_type in NON_SEMANTIC_PSI_VALUES
        or psi_language in NON_SEMANTIC_PSI_VALUES
        or any(marker in psi_class for marker in NON_SEMANTIC_PSI_CLASS_MARKERS)
    ):
        reasons.append("non_semantic_fallback")
    if file_diagnostic.get("valid") is False:
        reasons.append("invalid_file")
    if file_diagnostic.get("in_content") is False:
        reasons.append("outside_project_content")
    if file_diagnostic.get("diagnostic_error"):
        reasons.append("diagnostic_error")
    return reasons


def semantic_coverage_for_payload(payload: dict[str, Any]) -> dict[str, Any] | None:
    existing = payload.get("semantic_coverage")
    diagnostic = payload.get("capture_diagnostic")
    if not isinstance(diagnostic, dict):
        return existing if isinstance(existing, dict) else None
    raw_scope_files = diagnostic.get("scope_file_diagnostics")
    scope_files = [item for item in raw_scope_files if isinstance(item, dict)] if isinstance(raw_scope_files, list) else []
    resolved_value = diagnostic.get("scope_file_resolved_count")
    resolved_count = resolved_value if isinstance(resolved_value, int) and not isinstance(resolved_value, bool) else None
    emitted_diagnostic_count = len(scope_files)
    raw_summary = diagnostic.get("scope_file_semantic_coverage")
    summary = raw_summary if isinstance(raw_summary, dict) and raw_summary.get("schema_version") == 1 else None
    evaluated_value = summary.get("evaluated_file_count") if summary is not None else None
    evaluated_count = evaluated_value if isinstance(evaluated_value, int) and not isinstance(evaluated_value, bool) else None
    unproven_value = summary.get("unproven_file_count") if summary is not None else None
    summary_unproven_count = unproven_value if isinstance(unproven_value, int) and not isinstance(unproven_value, bool) else None
    missing_count_value = summary.get("missing_file_count") if summary is not None else None
    summary_missing_count = missing_count_value if isinstance(missing_count_value, int) and not isinstance(missing_count_value, bool) else None
    metadata_count_value = summary.get("metadata_file_count") if summary is not None else None
    summary_metadata_count = metadata_count_value if isinstance(metadata_count_value, int) and not isinstance(metadata_count_value, bool) else None
    raw_reason_counts = summary.get("reason_counts") if summary is not None else None
    summary_reason_counts = {
        str(reason): count
        for reason, count in raw_reason_counts.items()
        if isinstance(reason, str) and isinstance(count, int) and not isinstance(count, bool) and count > 0
    } if isinstance(raw_reason_counts, dict) else None
    reason_count_total = sum(summary_reason_counts.values()) if summary_reason_counts is not None else None
    raw_summary_metadata_files = summary.get("metadata_files") if summary is not None else None
    summary_metadata_rows = [
        item for item in raw_summary_metadata_files if isinstance(item, dict)
    ] if isinstance(raw_summary_metadata_files, list) else []
    invalid_summary_metadata_rows = [
        item for item in summary_metadata_rows if scope_file_metadata_classification(item) is None
    ]
    summary_counts_consistent = (
        evaluated_count is not None
        and evaluated_count >= 0
        and summary_unproven_count is not None
        and summary_unproven_count >= 0
        and summary_missing_count is not None
        and summary_missing_count >= 0
        and summary_metadata_count is not None
        and summary_metadata_count >= 0
        and summary_missing_count + summary_metadata_count <= evaluated_count
        and reason_count_total is not None
        and (
            (summary_missing_count == 0 and reason_count_total == 0)
            or (0 < summary_missing_count <= reason_count_total)
        )
    )
    aggregate_proof_complete = (
        diagnostic.get("scope_file_semantic_evidence_complete") is True
        and summary is not None
        and summary_unproven_count == 0
        and summary_reason_counts is not None
        and not invalid_summary_metadata_rows
        and summary_counts_consistent
        and (resolved_count is None or evaluated_count == resolved_count)
    )
    proven_file_count = (
        evaluated_count
        if aggregate_proof_complete and evaluated_count is not None
        else emitted_diagnostic_count
    )
    truncated = resolved_count is not None and resolved_count > proven_file_count
    if not scope_files and not truncated:
        if not aggregate_proof_complete or (summary_missing_count == 0 and summary_metadata_count == 0):
            return existing if isinstance(existing, dict) else None
    missing_files: list[dict[str, Any]] = []
    metadata_files: list[dict[str, Any]] = []
    summary_missing_files = summary.get("missing_files") if aggregate_proof_complete and summary is not None else None
    summary_metadata_files = summary.get("metadata_files") if aggregate_proof_complete and summary is not None else None
    coverage_scope_files = (
        [item for item in summary_missing_files if isinstance(item, dict)]
        if isinstance(summary_missing_files, list)
        else [] if aggregate_proof_complete else scope_files
    )
    coverage_metadata_files = (
        [item for item in summary_metadata_files if isinstance(item, dict)]
        if isinstance(summary_metadata_files, list)
        else [] if aggregate_proof_complete else scope_files
    )
    for item in coverage_metadata_files:
        classification = scope_file_metadata_classification(item)
        if classification is not None:
            path = item.get("path")
            metadata_summary = {
                "classification": classification,
                "coverage_required": False,
                "path": path,
                "requested_language_hint": Path(str(path)).suffix.lstrip(".").casefold() if path else None,
                "file_type": item.get("file_type"),
                "psi_language": item.get("psi_language"),
                "psi_class": item.get("psi_class"),
                "in_content": item.get("in_content"),
                "in_source": item.get("in_source"),
                "is_excluded": item.get("is_excluded"),
            }
            metadata_files.append({key: value for key, value in metadata_summary.items() if value is not None})
    for item in coverage_scope_files:
        reasons = scope_file_semantic_coverage_reasons(item)
        if not reasons and isinstance(item.get("reasons"), list):
            reasons = [reason for reason in item["reasons"] if isinstance(reason, str) and reason]
        if not reasons:
            continue
        path = item.get("path")
        file_summary = {
            "path": path,
            "requested_language_hint": Path(str(path)).suffix.lstrip(".").casefold() if path else None,
            "file_type": item.get("file_type"),
            "psi_language": item.get("psi_language"),
            "psi_class": item.get("psi_class"),
            "in_content": item.get("in_content"),
            "in_source": item.get("in_source"),
            "reasons": reasons,
        }
        missing_files.append({key: value for key, value in file_summary.items() if value is not None})
    for item in invalid_summary_metadata_rows:
        path = item.get("path")
        missing_files.append(
            {
                "path": path,
                "classification": scope_file_declared_coverage_role(item),
                "reasons": ["invalid_metadata_role"],
            }
        )
    missing_file_count = (
        summary_missing_count
        if aggregate_proof_complete and summary_missing_count is not None
        else len(missing_files)
    )
    metadata_file_count = (
        summary_metadata_count
        if aggregate_proof_complete and summary_metadata_count is not None
        else len(metadata_files)
    )
    if missing_file_count == 0 and metadata_file_count == 0 and not truncated:
        return existing if isinstance(existing, dict) else None
    allow_text_only = payload.get("allow_text_only_coverage") is True
    text_only = (
        missing_file_count > 0
        and (
            summary_reason_counts == {"non_semantic_fallback": missing_file_count}
            if aggregate_proof_complete
            else all(file.get("reasons") == ["non_semantic_fallback"] for file in missing_files)
        )
    )
    if missing_file_count > 0 or truncated:
        status = "text_only_allowed" if (allow_text_only and text_only and not truncated) else "missing"
        reason = (
            SEMANTIC_COVERAGE_TRUNCATED_REASON
            if truncated
            else "text_only_coverage_allowed" if status == "text_only_allowed" else SEMANTIC_COVERAGE_MISSING_REASON
        )
    else:
        status = "satisfied"
        reason = PROJECT_METADATA_COVERAGE_REASON
    coverage = {
        "status": status,
        "reason": reason,
        "scope_kind": diagnostic.get("scope_kind"),
        "scope_resolution_status": diagnostic.get("scope_resolution_status"),
        "requested_file_count": diagnostic.get("scope_file_requested_count"),
        "resolved_file_count": diagnostic.get("scope_file_resolved_count"),
        "diagnostic_file_count": emitted_diagnostic_count,
        "evaluated_file_count": evaluated_count if aggregate_proof_complete else None,
        "diagnostic_details_truncated": diagnostic.get("scope_file_diagnostics_truncated"),
        "unproven_file_count": max(0, resolved_count - proven_file_count) if resolved_count is not None else None,
        "missing_file_count": missing_file_count,
        "files": missing_files,
        "metadata_file_count": metadata_file_count,
        "metadata_files": metadata_files,
    }
    if allow_text_only:
        coverage["allow_text_only_coverage"] = True
    return {key: value for key, value in coverage.items() if value is not None}


def apply_semantic_coverage(payload: dict[str, Any]) -> dict[str, Any]:
    coverage = semantic_coverage_for_payload(payload)
    if coverage is not None:
        payload["semantic_coverage"] = coverage
    return payload


def semantic_coverage_unknown_verdict(payload: dict[str, Any]) -> dict[str, str] | None:
    coverage = semantic_coverage_for_payload(payload)
    if coverage is None or coverage.get("status") != "missing":
        return None
    reason = str(coverage.get("reason") or SEMANTIC_COVERAGE_MISSING_REASON)
    return {
        "verdict": "UNKNOWN",
        "verdict_reason": reason,
        "verdict_message": (
            "Inspection did not emit semantic diagnostics for every resolved file."
            if reason == SEMANTIC_COVERAGE_TRUNCATED_REASON
            else "Inspection could not prove language-aware coverage for every requested file."
        ),
        "verdict_next_action": next_action_for_unknown(reason, payload),
    }


def semantic_coverage_allowed_verdict(payload: dict[str, Any]) -> dict[str, str] | None:
    coverage = semantic_coverage_for_payload(payload)
    if coverage is None:
        return None
    if coverage.get("status") == "satisfied":
        return {
            "verdict": "GREEN",
            "verdict_reason": PROJECT_METADATA_COVERAGE_REASON,
            "verdict_message": "Inspection found no actionable findings; classified project metadata does not require language-aware PSI.",
            "verdict_next_action": "No inspection action required for classified project metadata.",
        }
    if coverage.get("status") != "text_only_allowed":
        return None
    return {
        "verdict": "GREEN",
        "verdict_reason": "text_only_coverage_allowed",
        "verdict_message": "Inspection found no actionable findings with explicitly allowed text-only coverage.",
        "verdict_next_action": "No inspection action required; text-only coverage was explicitly accepted for this scope.",
    }


def payload_scope(payload: dict[str, Any]) -> str:
    descriptor = dict_field(payload, "scope_descriptor")
    context = dict_field(payload, "context")
    context_descriptor = dict_field(context, "scope_descriptor")
    return str(
        payload.get("scope")
        or descriptor.get("scope")
        or context.get("scope")
        or context_descriptor.get("scope")
        or ""
    ).strip().lower()


def inspection_execution_proof_version(payload: dict[str, Any]) -> int | None:
    candidates: list[dict[str, Any]] = [payload]
    for container_name in ("ide", "route", "prepared", "inspection_attribution", "raw", "context"):
        container = payload.get(container_name)
        if isinstance(container, dict):
            candidates.append(container)
            nested_ide = container.get("ide")
            if isinstance(nested_ide, dict):
                candidates.append(nested_ide)
            nested_route = container.get("route")
            if isinstance(nested_route, dict):
                candidates.append(nested_route)
                route_ide = nested_route.get("ide")
                if isinstance(route_ide, dict):
                    candidates.append(route_ide)
    for candidate in candidates:
        value = candidate.get("inspection_execution_proof_version")
        if isinstance(value, bool):
            continue
        if isinstance(value, int):
            return value
        if isinstance(value, str) and value.strip().isdigit():
            return int(value.strip())
    return None


def broad_scope_deployment_verdict(payload: dict[str, Any]) -> dict[str, str] | None:
    if payload_scope(payload) not in {"whole_project", "directory", "all"}:
        return None
    problems = payload.get("problems") or []
    total = payload.get("total_problems")
    if problems or (isinstance(total, int) and total > 0) or payload.get("inspection_verdict") == "RED":
        return None
    proof_version = inspection_execution_proof_version(payload)
    if proof_version is not None and proof_version >= NATIVE_BROAD_SCOPE_PROOF_VERSION:
        return None
    payload["deployment_mismatch"] = {
        "kind": "inspection_execution_proof_capability",
        "required_version": NATIVE_BROAD_SCOPE_PROOF_VERSION,
        "observed_version": proof_version,
        "plugin_build_fingerprint": next(
            (
                source.get("plugin_build_fingerprint")
                for source in (
                    payload,
                    dict_field(payload, "ide"),
                    dict_field(payload, "inspection_attribution"),
                )
                if source.get("plugin_build_fingerprint")
            ),
            None,
        ),
    }
    return {
        "verdict": "UNKNOWN",
        "verdict_reason": "plugin_deployment_mismatch",
        "verdict_message": "The installed inspection plugin cannot prove clean broad-scope execution for this helper contract.",
        "verdict_next_action": "Install a plugin with native broad-scope execution proof, restart the IDE, resolve the route again, and rerun inspection.",
    }


def verdict_for_payload(payload: dict[str, Any]) -> dict[str, str]:
    wait_value = payload.get("wait")
    wait = wait_value if isinstance(wait_value, dict) else {}
    cleanup_value = payload.get("cleanup")
    cleanup = cleanup_value if isinstance(cleanup_value, dict) else {}
    plugin_verdict = payload.get("inspection_verdict")
    blocker_reason = blocking_unknown_reason(payload, wait)
    if blocker_reason is not None:
        return {
            "verdict": "UNKNOWN",
            "verdict_reason": blocker_reason,
            "verdict_message": "Inspection did not produce a trustworthy GREEN or RED result.",
            "verdict_next_action": next_action_for_unknown(blocker_reason, payload),
        }

    if cleanup.get("status") in {"failed", "skipped"} or payload.get("cleanup_failed") or payload.get("cleanup_skipped"):
        reason = unknown_reason(payload, wait, cleanup)
        return {
            "verdict": "UNKNOWN",
            "verdict_reason": reason,
            "verdict_message": "Readiness inspection did not complete cleanly after inspection.",
            "verdict_next_action": next_action_for_unknown(reason, payload),
        }

    if payload.get("status") == "error":
        reason = str(payload.get("error_reason") or "helper_error")
        return {
            "verdict": "UNKNOWN",
            "verdict_reason": reason,
            "verdict_message": "Inspection tooling failed before it could prove GREEN or RED.",
            "verdict_next_action": next_action_for_unknown(reason, payload),
        }

    if selected_inspection_file_count(payload) == 0:
        return {
            "verdict": "UNKNOWN",
            "verdict_reason": "inspection_scope_empty",
            "verdict_message": "The inspection scope resolved to zero files.",
            "verdict_next_action": (
                "Select tracked files with --scope files and repeatable --file arguments. "
                "changed_files covers working-tree changes, not committed branch changes."
            ),
        }

    deployment_verdict = broad_scope_deployment_verdict(payload)
    if deployment_verdict is not None:
        return deployment_verdict

    semantic_coverage_verdict = semantic_coverage_allowed_verdict(payload)
    if semantic_coverage_verdict is not None and plugin_verdict in {"GREEN", "UNKNOWN"}:
        proof_failures = payload.get("proof_failures")
        normalized_failures = {
            normalize_reason(str(reason))
            for reason in proof_failures
        } if isinstance(proof_failures, list) else set()
        if not normalized_failures or normalized_failures <= {SEMANTIC_COVERAGE_MISSING_REASON}:
            return semantic_coverage_verdict

    proof_failure_reason = proof_failure_unknown_reason(payload)
    if proof_failure_reason is not None:
        return {
            "verdict": "UNKNOWN",
            "verdict_reason": proof_failure_reason,
            "verdict_message": "Inspection returned contradictory proof and did not establish a trustworthy GREEN or RED result.",
            "verdict_next_action": next_action_for_unknown(proof_failure_reason, payload),
        }

    if plugin_verdict == "GREEN":
        semantic_coverage_verdict = semantic_coverage_unknown_verdict(payload)
        if semantic_coverage_verdict is not None:
            return semantic_coverage_verdict
        semantic_coverage_verdict = semantic_coverage_allowed_verdict(payload)
        if semantic_coverage_verdict is not None:
            return semantic_coverage_verdict
    if plugin_verdict in {"GREEN", "RED", "UNKNOWN"}:
        return {
            "verdict": str(plugin_verdict),
            "verdict_reason": str(payload.get("inspection_verdict_reason") or "plugin_verdict"),
            "verdict_message": str(payload.get("inspection_verdict_message") or "Inspection plugin provided the verdict."),
            "verdict_next_action": str(payload.get("inspection_verdict_next_action") or "Follow the inspection plugin verdict."),
        }

    problems = payload.get("problems") or []
    total = payload.get("total_problems")
    has_current_findings = (problems or (isinstance(total, int) and total > 0)) and not payload.get("capture_incomplete") and not payload.get("results_may_be_stale")
    if payload.get("status") == "findings" or has_current_findings:
        return {
            "verdict": "RED",
            "verdict_reason": "actionable_findings",
            "verdict_message": "Inspection worked and returned actionable findings.",
            "verdict_next_action": "Fix the reported findings, then rerun inspection.",
        }

    has_explicit_zero_result = isinstance(total, int) and total == 0
    if (
        (payload.get("status") == "results_available" and (has_explicit_zero_result or payload.get("clean") is True))
        or payload.get("status") == "clean"
        or payload.get("clean") is True
    ):
        semantic_coverage_verdict = semantic_coverage_unknown_verdict(payload)
        if semantic_coverage_verdict is not None:
            return semantic_coverage_verdict
        semantic_coverage_verdict = semantic_coverage_allowed_verdict(payload)
        if semantic_coverage_verdict is not None:
            return semantic_coverage_verdict
        return {
            "verdict": "GREEN",
            "verdict_reason": "no_matching_findings" if payload.get("status") == "results_available" else "clean_confirmed",
            "verdict_message": "Inspection worked and found no actionable findings for the selected scope/filter.",
            "verdict_next_action": "No inspection action required for this scope/filter.",
        }

    reason = unknown_reason(payload, wait, cleanup)
    return {
        "verdict": "UNKNOWN",
        "verdict_reason": reason,
        "verdict_message": "Inspection did not produce a trustworthy GREEN or RED result.",
        "verdict_next_action": next_action_for_unknown(reason, payload),
    }


def blocking_unknown_reason(payload: dict[str, Any], wait: dict[str, Any]) -> str | None:
    preparation = repository_preparation_for_payload(payload)
    preparation_reason = preparation.get("failure_reason")
    if isinstance(preparation_reason, str) and preparation_reason.strip():
        return normalize_reason(preparation_reason)
    if preparation.get("execution_state") in {"failed", "blocked"}:
        return "repository_preparation_failure"
    if payload.get("session_drift") or wait.get("session_drift"):
        return "session_drift"
    if payload.get("ambiguous"):
        return "ambiguous_route"
    if payload.get("error_reason") == "ide_memory_exhausted":
        return str(payload["error_reason"])
    if payload.get("unavailable"):
        return "inspection_api_unavailable"
    if payload.get("results_may_be_stale") or wait.get("results_may_be_stale") or payload.get("status") == "stale_results":
        return "stale_results"
    if payload.get("capture_incomplete") or wait.get("capture_incomplete") or payload.get("status") == "capture_incomplete":
        return str(payload.get("capture_incomplete_reason") or wait.get("capture_incomplete_reason") or "capture_incomplete")
    if payload.get("error_reason") == "inspection_api_timeout":
        return "inspection_api_timeout"
    if payload.get("run_changed") or payload.get("error_reason") == "run_changed":
        return "run_changed"
    if wait.get("status") == "run_changed":
        trigger = dict_field(payload, "trigger")
        expected_run_id = inspection_run_id(trigger) or positive_run_id(wait.get("expected_inspection_run_id"))
        if expected_run_id is None or wait_result_run_changed(wait, expected_run_id):
            return "run_changed"
    if payload.get("timed_out") or wait.get("timed_out") or payload.get("status") == "timed_out":
        if text_only_coverage_wait_timeout_is_recoverable(payload, wait):
            return None
        return "timeout"
    if payload.get("indexing") or payload.get("is_scanning") or payload.get("inspection_in_progress") or payload.get("status") in {"indexing", "running"}:
        return "inspection_still_running"
    return None


def text_only_coverage_wait_timeout_is_recoverable(payload: dict[str, Any], wait: dict[str, Any]) -> bool:
    if wait.get("timed_out") is not True or wait.get("completion_reason") != "timeout":
        return False
    if wait.get("wait_completed") is True:
        return False
    if normalize_reason(wait.get("status")) in {"indexing", "running", "inspection_in_progress", "run_changed"}:
        return False
    if wait.get("inspection_verdict") != "UNKNOWN":
        return False
    if normalize_reason(wait.get("inspection_verdict_reason")) != SEMANTIC_COVERAGE_MISSING_REASON:
        return False
    coverage = payload.get("semantic_coverage")
    if not isinstance(coverage, dict):
        coverage = semantic_coverage_for_payload(payload)
    if not isinstance(coverage, dict) or coverage.get("status") != "text_only_allowed":
        return False
    if payload.get("clean") is not True or payload.get("total_problems") != 0 or payload.get("problems"):
        return False
    if payload.get("snapshot_outcome") != "clean_confirmed":
        return False
    if any(
        source.get(key)
        for source in (payload, wait)
        for key in (
            "capture_incomplete",
            "results_may_be_stale",
            "indexing",
            "is_scanning",
            "inspection_in_progress",
        )
    ):
        return False
    if payload.get("inspection_verdict") != "UNKNOWN":
        return False
    if normalize_reason(payload.get("inspection_verdict_reason")) != SEMANTIC_COVERAGE_MISSING_REASON:
        return False
    failures = payload.get("proof_failures")
    normalized_failures = {
        normalize_reason(str(reason))
        for reason in failures
    } if isinstance(failures, list) else set()
    return normalized_failures == {SEMANTIC_COVERAGE_MISSING_REASON}


def proof_failure_unknown_reason(payload: dict[str, Any]) -> str | None:
    failures = payload.get("proof_failures")
    if not isinstance(failures, list) or not failures:
        return None
    normalized_failures = [normalize_reason(str(reason)) for reason in failures]
    total_problems = payload.get("total_problems")
    problems = payload.get("problems")
    has_current_findings = bool(problems) or (isinstance(total_problems, int) and total_problems > 0)
    if (
        payload.get("inspection_verdict") == "RED"
        and has_current_findings
        and set(normalized_failures) <= RED_COMPATIBLE_PROOF_FAILURES
    ):
        return None
    if SEMANTIC_COVERAGE_MISSING_REASON in normalized_failures:
        return SEMANTIC_COVERAGE_MISSING_REASON
    if SEMANTIC_COVERAGE_TRUNCATED_REASON in normalized_failures:
        return SEMANTIC_COVERAGE_TRUNCATED_REASON
    retryable_reasons = (
        "no_results",
        "view_not_ready",
        "view_updating_unreadable",
        "unreadable_tree",
        "capture_incomplete",
        "inspection_trigger_empty_model",
        "current_run_psi_churn",
        "inspection_inputs_changed",
        "project_analysis_not_ready",
        "stale_results",
        "timeout",
        "inspection_still_running",
    )
    if any(reason not in retryable_reasons for reason in normalized_failures):
        return "inspection_proof_failed"
    for retryable_reason in retryable_reasons:
        if retryable_reason in normalized_failures:
            return retryable_reason
    return "inspection_proof_failed"


def unknown_reason(payload: dict[str, Any], wait: dict[str, Any], cleanup: dict[str, Any]) -> str:
    if cleanup.get("status") in {"failed", "skipped"} or payload.get("cleanup_failed") or payload.get("cleanup_skipped"):
        return f"cleanup_{cleanup.get('status') or 'failed'}"
    blocker_reason = blocking_unknown_reason(payload, wait)
    if blocker_reason is not None:
        return blocker_reason
    if payload.get("status") == "no_results" or wait.get("completion_reason") == "no_results":
        return "no_results"
    return str(payload.get("status") or wait.get("completion_reason") or "unknown")


IDE_ROUTE_CONFIGURATION_REASONS = frozenset({"ide_selection_required", "ide_config_ambiguous", "ide_config_missing"})
IDE_ROUTE_CONFIGURATION_NEXT_ACTION = (
    "This repository has no usable JetBrains IDE route, so repeating the inspection cannot succeed. "
    "Recommend to the user that the repository record its IDE in .github/github.json under "
    'qualityGate.inspection, for example {"ide": "PyCharm"}; name the IDE that fits the '
    "repository's main language and ask before writing it, because it is durable repository policy. "
    "For this one assessment, rerun once with --ide <IDE name> if the user names an IDE. "
    "Report the verdict as UNKNOWN with this recommendation; do not keep reporting that inspection is unavailable."
)


def inspection_tool_file_examples(payload: dict[str, Any], field: str) -> list[dict[str, str]]:
    diagnostic = dict_field(payload, "capture_diagnostic")
    examples = diagnostic.get(field)
    if not isinstance(examples, list):
        return []
    return [
        {"tool": example["tool"], "file": example["file"]}
        for example in examples
        if isinstance(example, dict) and isinstance(example.get("tool"), str) and isinstance(example.get("file"), str)
    ]


def unproven_batch_annotators(payload: dict[str, Any]) -> list[dict[str, str]]:
    return inspection_tool_file_examples(payload, "execution_proof_unproven_batch_annotators")


def batch_inspection_delegations(payload: dict[str, Any]) -> list[dict[str, str]]:
    return inspection_tool_file_examples(payload, "batch_inspection_delegations")


def unproven_batch_annotator_suffix(payload: dict[str, Any]) -> str:
    pairs = unproven_batch_annotators(payload)
    if not pairs:
        return ""
    return " Blocking: " + "; ".join(f"{pair['tool']} on {pair['file']}" for pair in pairs) + "."


UNKNOWN_RETRY_ACTION = "Wait for IDE/capture readiness to settle, then retry once and report helper diagnostics if it remains UNKNOWN."
UNKNOWN_TERMINAL_ACTION = "Stop retrying this result and report the helper diagnostic payload. Resolve the reported IDE/plugin or ownership prerequisite before any further assessment."


def disk_freshness_recovery(reason: str, payload: dict[str, Any]) -> str | None:
    diagnostic = payload.get("capture_diagnostic")
    if reason != "inspection_inputs_changed" or not isinstance(diagnostic, dict):
        return None
    if diagnostic.get("exit_reason") not in {
        "disk_psi_content_mismatch", "scoped_file_unavailable", "scoped_psi_unavailable", "scoped_disk_read_failed",
    }:
        return None
    return (
        "Resolve the scoped file and save intended editor changes. Synchronize it in the exact IDE project "
        "using Reload from Disk, or rewrite it with an updated modification time, before a fresh inspection. "
        "Retries alone cannot repair a timestamp-preserving disk/PSI mismatch."
    )


def next_action_for_unknown(reason: str, payload: dict[str, Any]) -> str:
    reason = normalize_reason(reason)
    if reason == "isolated_backend_unavailable":
        return str(payload["hint"])
    if reason == "ide_memory_exhausted":
        action = "Ask the Director to dismiss any memory-error dialog (such as Java heap space) and restart the IDE before inspecting again."
        cleanup = dict_field(payload, "cleanup")
        if cleanup.get("status") in {"deferred", "kept_warm"} or payload.get("cleanup_deferred"):
            action += " Then run cleanup-helper-leases for the retained lease before another inspection."
        return action
    if reason in REPOSITORY_PREPARATION_TERMINAL_REASONS or reason == "repository_preparation_failure":
        preparation = repository_preparation_for_payload(payload)
        return repository_preparation_next_action(reason, preparation)
    diagnostic = dict_field(payload, "capture_diagnostic")
    execution_proof_reason = normalize_reason(
        diagnostic.get("execution_proof_block_reason") or diagnostic.get("execution_proof_skipped_reason")
    )
    if reason in IDE_ROUTE_CONFIGURATION_REASONS:
        return ide_selection_advice(reason, payload.get("context")) or IDE_ROUTE_CONFIGURATION_NEXT_ACTION
    if reason == "plugin_deployment_mismatch":
        return "Install a plugin with native broad-scope execution proof, restart the IDE, resolve the route again, and rerun inspection."
    if reason == "execution_not_proven":
        if execution_proof_reason == "batch_inspection_delegated_to_editor_service":
            action = payload.get("inspection_verdict_next_action")
            if isinstance(action, str) and action.strip():
                return action.strip()
            return (
                "Run stock inspect.sh in an isolated profile, or turn off the TypeScript language service in the exact "
                "project and request a fresh API inspection. Keep the unresolved-reference inspection enabled; do not retry unchanged."
            )
        if execution_proof_reason in {"native_inspection_not_completed", "native_inspection_aborted"}:
            return "Wait for the IDE inspection run to settle, then retry once with a fresh run and include execution_proof diagnostics if it remains UNKNOWN."
        if execution_proof_reason in {"whole_project_execution_not_proven", "directory_execution_not_proven"}:
            return "Update the inspection plugin to a native broad-scope proof build, restart the IDE, and rerun; the installed plugin cannot prove this clean scope."
        if execution_proof_reason == "native_attestation_context_creation_failed":
            return "Update or reinstall the inspection plugin, restart the IDE, resolve the route again, and rerun; native attestation could not be initialized."
        if execution_proof_reason == "native_cpp_batch_annotator_unproven":
            return (
                "Do not report GREEN and do not retry: C/C++ files in this scope use batch-annotator inspections "
                "(such as CLion Radler) that never report completion. Inspect a scope without C/C++ files, or report UNKNOWN."
                + unproven_batch_annotator_suffix(payload)
            )
        if execution_proof_reason == "native_batch_annotator_unproven":
            return (
                "Do not report GREEN and do not retry: files in this scope use batch-annotator inspections "
                "(such as ShellCheck or ESLint) that never report completion. Inspect a scope without those files, or report UNKNOWN."
                + unproven_batch_annotator_suffix(payload)
            )
        if execution_proof_reason == "native_tool_completion_unproven":
            return (
                "Do not report GREEN and do not retry: not every inspection tool that applies to this scope reported "
                "finishing. Inspect a narrower scope, or report UNKNOWN with native_tool_completion_observation."
            )
        if execution_proof_reason == "native_scope_enumeration_failed":
            return "Stop retrying and report the execution_proof diagnostics; the plugin could not enumerate the requested native inspection scope."
        if execution_proof_reason in {"native_inspection_failures", "native_inspection_reported_problems"}:
            return "Stop retrying and report the execution_proof diagnostics; the native inspection run or result mapping failed."
        if execution_proof_reason in {
            "native_inspection_no_tools_completed",
            "native_inspection_no_files_analyzed",
            "native_inspection_no_file_scoped_tools_completed",
            "native_inspection_scope_empty",
            "native_inspection_scope_incomplete",
            "native_inspection_scope_mismatch",
        }:
            return "Check the requested scope and active inspection profile; no affirmative native execution was observed. Do not report GREEN."
        return "Stop retrying and report the execution_proof diagnostic payload; this run did not establish clean execution."
    if reason == SEMANTIC_COVERAGE_TRUNCATED_REASON:
        return (
            "Treat this as a plugin/helper proof gap: update the inspection plugin or helper so it emits one semantic "
            "diagnostic per resolved file, then rerun."
        )
    if reason == SEMANTIC_COVERAGE_MISSING_REASON:
        coverage = semantic_coverage_for_payload(payload) or {}
        coverage_files = [file for file in coverage.get("files", []) if isinstance(file, dict)]
        has_outside_content = any(
            "outside_project_content" in (file.get("reasons") or []) for file in coverage_files
        )
        has_non_semantic_source = any(
            "non_semantic_fallback" in (file.get("reasons") or [])
            and normalized_psi_marker(file.get("file_type")) not in PROJECT_METADATA_FILE_TYPES
            for file in coverage_files
        )
        if has_outside_content:
            actions = [
                "Open or import the exact worktree so every requested file belongs to the intended JetBrains module/content root."
            ]
            if has_non_semantic_source:
                actions.append(
                    "Then use a JetBrains IDE with semantic language support for the remaining PlainText/TextMate source files, "
                    "install or enable the required language plugins, or update the repository's preferred IDE metadata."
                )
            actions.append("Do not use --allow-text-only-coverage to override outside-project-content, then rerun.")
            return " ".join(actions)
        language_hints = sorted({
            str(file.get("requested_language_hint"))
            for file in coverage_files
            if file.get("requested_language_hint")
        })
        languages = f" for {', '.join(language_hints)}" if language_hints else ""
        return (
            f"Open the worktree in a JetBrains IDE with semantic language support{languages}, install or enable the required language plugins, "
            "or update the repository's preferred IDE metadata, then rerun. Use --allow-text-only-coverage only when text-only coverage is intentionally sufficient."
        )
    if reason in {"non_empty_unmapped_tree", "extractor_failure", "helper_plugin_error"}:
        return "Treat this as a plugin/helper bug: capture the diagnostic payload, update the inspection plugin or helper skill, and rerun."
    if reason in {"view_not_ready", "view_updating_unreadable", "unreadable_tree", "no_results"}:
        return "Open the IDE Inspection Results or Problems view for the exact worktree, then rerun inspection."
    if reason == "current_run_psi_churn":
        return "Save documents and rerun inspection after the IDE finishes updating PSI state."
    if reason == "inspection_inputs_changed":
        recovery = disk_freshness_recovery(reason, payload)
        if recovery:
            return recovery
        return "Wait for same-worktree writers and IDE indexing/project-model updates to settle, then rerun after project files, VCS state, and inspection settings stop changing."
    if reason == "language_sdk_missing":
        return language_sdk_missing_next_action(payload)
    if reason.startswith("python_sdk_preparation_"):
        return "Inspect python_sdk_preparation and the outcome bucket for the exact SDK, ownership, transport, or plugin failure. Resolve that condition before another request; do not retry SDK preparation automatically."
    if reason == PROJECT_CONTENT_ROOTS_MISSING_REASON:
        preparation_action = repository_preparation_action(payload)
        if preparation_action:
            return preparation_action
    if reason == "project_analysis_not_ready":
        return "Wait for the configured language SDK and background analysis to settle, then rerun inspection."
    if reason == "stale_results":
        return "Wait for same-worktree writers and IDE indexing/project-model updates to settle, then rerun; stale cached findings must not be treated as current."
    if reason == "timeout":
        return "Wait for indexing/scanning to settle or rerun with a larger timeout."
    if reason == "lifecycle_lock_timeout":
        return "Wait for the owning lifecycle operation to finish, then run assessments sequentially. No IDE inspection started."
    if reason == "inspection_still_running":
        return "Wait for indexing/scanning to finish, then rerun inspection."
    if reason == "inspection_api_timeout":
        return "The IDE inspection API was busy. Wait for the active inspection or lifecycle operation to settle, then retry once."
    if reason == "already_opening_stuck":
        return (
            "The configured JetBrains session retained a stale lifecycle opening for this exact worktree. "
            "Close any partial project window, restart that IDE session, run cleanup-helper-leases, then rerun inspection."
        )
    if reason == "run_changed":
        return "Another inspection replaced the accepted run. Wait for that run to settle, then resolve the route and retry once."
    if reason == "inspection_api_unavailable":
        return "Open the exact worktree in the configured JetBrains IDE with the inspection plugin installed."
    if reason == "repository_preparation_config_invalid":
        return (
            "Fix qualityGate.inspection.prepare in .github/github.json so it is a valid non-empty command, "
            "then rerun the inspection command."
        )
    if reason == "ambiguous_route":
        return "Pass project_key, project_path, or worktree_path so the helper can inspect the exact project."
    if reason == "session_drift":
        return "Resolve the route again and rerun; the IDE/plugin session changed."
    if reason == "worktree_mutation_detected":
        return (
            "Inspect the mutation evidence, preserve user and agent edits, coordinate same-worktree writers, "
            "and rerun after the worktree is stable."
        )
    if reason.startswith("cleanup_"):
        return "Inspect lifecycle cleanup output; close helper-opened IDE projects or rerun inspect-closeout after cleanup succeeds."
    if diagnostic.get("observed_non_empty_inspection_tree") is True:
        return "Treat this as a plugin/helper capture bug and include capture_diagnostic when reporting it."
    return UNKNOWN_TERMINAL_ACTION


def helper_revision() -> str:
    global _HELPER_REVISION
    if _HELPER_REVISION is None:
        try:
            digest = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
            _HELPER_REVISION = f"sha256:{digest}"
        except OSError:
            _HELPER_REVISION = "unavailable"
    return _HELPER_REVISION


def file_content_sha256(path: str | Path) -> str | None:
    digest = hashlib.sha256()
    try:
        with Path(path).expanduser().open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        return None
    return f"sha256:{digest.hexdigest()}"


def canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def stable_value_hash(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return text_sha256(text)


def text_sha256(text: str) -> str:
    return f"sha256:{hashlib.sha256(text.encode('utf-8')).hexdigest()}"


def attribution_payloads(payload: dict[str, Any]) -> list[dict[str, Any]]:
    candidates = [payload]
    for key in ("wait", "status", "problems", "trigger", "cancellation", "cleanup", "last_status"):
        nested = payload.get(key)
        if isinstance(nested, dict):
            candidates.append(nested)
    return candidates


def existing_inspection_attribution(payload: dict[str, Any]) -> dict[str, Any]:
    for candidate in attribution_payloads(payload):
        attribution = candidate.get("inspection_attribution")
        if isinstance(attribution, dict):
            return dict(attribution)
    return {}


def payload_route(payload: dict[str, Any]) -> dict[str, Any]:
    for candidate in attribution_payloads(payload):
        route = candidate.get("route")
        if isinstance(route, dict):
            return route
    return {}


def attribution_classification(code: str, payload: dict[str, Any]) -> str:
    normalized = normalize_reason(code)
    diagnostic = payload.get("capture_diagnostic")
    if normalized == "execution_not_proven" and isinstance(diagnostic, dict) and (
        diagnostic.get("execution_proof_block_reason") == "batch_inspection_delegated_to_editor_service"
    ):
        return "configuration_blocked"
    if payload.get("verdict") in {"GREEN", "RED"} or normalized in {
        "clean",
        "clean_confirmed",
        "findings",
        "no_matching_findings",
        "actionable_findings",
    }:
        return "decisive"
    if normalized in {
        "inspection_api_http_error",
        "identity_port_mismatch",
        "invalid_identity_port",
        "invalid_api_response",
        "extractor_failure",
        "helper_plugin_error",
        "inspection_proof_failed",
        "inspection_trigger_empty_model",
        "non_empty_unmapped_tree",
        "open_schedule_failed",
    }:
        return "tool_caused"
    if normalized in {
        "timeout",
        "inspection_api_timeout",
        "inspection_api_unavailable",
        "missing_session_id",
        "no_project",
        "ide_selection_required",
        "ide_config_ambiguous",
        "ide_config_missing",
        "implicit_eap_selection",
        "ide_not_ready_timeout",
        "ide_open_failed",
        "ide_launch_disabled",
        "isolated_backend_unavailable",
        "ide_launch_refused",
        "project_open_blocked",
        "already_opening_stuck",
        PROJECT_OPEN_BLOCKED_REASON,
        PROJECT_CONTENT_ROOTS_MISSING_REASON,
        "language_sdk_missing",
        "profile_resolution_error",
        "inspection_lane_config_invalid",
        "inspection_scope_empty",
        SEMANTIC_COVERAGE_MISSING_REASON,
        "untrusted_auto_open_root",
        *REPOSITORY_PREPARATION_TERMINAL_REASONS,
        "repository_preparation_failure",
    }:
        return "configuration_blocked"
    if normalized in {
        "ambiguous_route",
        "already_opening",
        "capture_incomplete",
        "cleanup_deferred",
        "cleanup_failed",
        "cleanup_skipped",
        "close_failed",
        "current_run_psi_churn",
        "inspection_inputs_changed",
        "project_analysis_not_ready",
        "inspection_in_progress",
        "inspection_still_running",
        "interrupted",
        "lease_mismatch",
        "matching_project_route_unavailable",
        "no_recent_inspection",
        "no_results",
        "not_claimed",
        "open_state_unknown",
        "ownership_not_proven",
        "project_not_open",
        "project_preexisted",
        "project_instance_reused",
        "project_mismatch",
        "route_missing",
        "route_mismatch",
        "route_path_invalid",
        "route_path_missing",
        "run_changed",
        "scope_mismatch",
        "scope_not_covered",
        SEMANTIC_COVERAGE_TRUNCATED_REASON,
        "session_drift",
        "stale_results",
        "target_project_not_open",
        "token_mismatch",
        "unreadable_tree",
        "view_not_ready",
        "view_updating_unreadable",
        "worktree_route_mismatch",
        "worktree_mutation_detected",
    }:
        return "legitimate_fail_closed"
    if payload.get("cleanup_failed") or payload.get("cleanup_skipped") or payload.get("cleanup_deferred"):
        return "legitimate_fail_closed"
    return "unattributed"


def attribution_failure_phase(payload: dict[str, Any], attribution: dict[str, Any], code: str) -> str:
    phase = clean_optional(attribution.get("phase"))
    if phase:
        return phase
    if payload.get("cleanup_failed") or payload.get("cleanup_skipped") or payload.get("cleanup_deferred"):
        return "cleanup"
    normalized = normalize_reason(code)
    if normalized in {"ide_selection_required", "ide_config_ambiguous", "ide_config_missing", "implicit_eap_selection"}:
        return "selection"
    if normalized == "inspection_lane_config_invalid":
        return "configuration"
    if normalized in {
        "ide_not_ready_timeout",
        "project_open_blocked",
        PROJECT_OPEN_BLOCKED_REASON,
        PROJECT_CONTENT_ROOTS_MISSING_REASON,
        *REPOSITORY_PREPARATION_TERMINAL_REASONS,
        "repository_preparation_failure",
    }:
        return "readiness_wait"
    if isinstance(payload.get("wait"), dict) or normalized in {"timeout", "run_changed", "inspection_api_timeout"}:
        return "wait"
    endpoint = clean_optional(payload.get("endpoint"))
    if endpoint:
        return endpoint.removeprefix("/api/inspection/").replace("/", "_")
    return canonical_command(str(payload.get("command") or "inspection"))


def apply_inspection_attribution(payload: dict[str, Any]) -> dict[str, Any]:
    if payload.get("verdict") not in {"GREEN", "RED", "UNKNOWN"} and not any(
        payload.get(key) for key in ("cleanup_failed", "cleanup_skipped", "cleanup_deferred")
    ):
        return payload
    attribution = existing_inspection_attribution(payload)
    cleanup = dict_field(payload, "cleanup")
    route = payload_route(payload)
    ide = dict_field(route, "ide")
    context = dict_field(payload, "context")
    selection = dict_field(context, "ide_selection")
    if attribution.get("ide_channel"):
        ide_channel_source = attribution.get("ide_channel_source") or "plugin_attribution"
    elif ide.get("channel") or ide.get("ide_channel"):
        ide_channel_source = "plugin_identity"
    elif selection.get("channel"):
        ide_channel_source = "selector_fallback"
    else:
        ide_channel_source = None
    code = str(
        attribution.get("code")
        or payload.get("error_reason")
        or payload.get("verdict_reason")
        or cleanup.get("reason")
        or payload.get("status")
        or "unknown"
    )
    helper_decisive_override = (
        payload.get("verdict") == "GREEN"
        and payload.get("verdict_reason") == "text_only_coverage_allowed"
        and attribution.get("classification") not in (None, "", "decisive")
    )
    if helper_decisive_override:
        code = str(payload.get("verdict_reason") or code)
        classification = "decisive"
        attribution["source"] = "helper"
    else:
        helper_classification = attribution_classification(code, payload)
        plugin_classification = str(attribution.get("classification") or "")
        classification = (
            helper_classification
            if plugin_classification in {"", "unattributed"} and helper_classification != "unattributed"
            else plugin_classification or helper_classification
        )
    phase = attribution_failure_phase(payload, attribution, code)
    normalized_code = "unattributed_unknown" if classification == "unattributed" else normalize_reason(code)
    cleanup_attribution = dict(attribution)
    cleanup_attribution.update({"code": normalized_code, "phase": phase})
    cleanup_status, cleanup_reason = derived_cleanup_status(
        outcome_event_kind(preferred_command(str(payload.get("command") or ""))),
        inspection_started_for_payload(payload),
        cleanup_attribution,
        cleanup,
    )
    local_client_run_id = context.get("client_run_id") or payload.get("client_run_id")
    plugin_client_run_id = attribution.get("client_run_id")
    client_run_id = local_client_run_id or plugin_client_run_id
    request_id = attribution.get("request_id")
    session_id = attribution.get("session_id") or payload.get("session_id") or route.get("session_id")
    run_id = attribution.get("inspection_run_id") or inspection_run_id(payload)
    if run_id is None:
        for candidate in attribution_payloads(payload):
            run_id = inspection_run_id(candidate)
            if run_id is not None:
                break
    project_instance_id = attribution.get("project_instance_id") or route.get("project_instance_id")
    attribution.update(
        {
            "schema_version": INSPECTION_ATTRIBUTION_SCHEMA_VERSION,
            "source": attribution.get("source") or "helper",
            "observed_by": "helper",
            "classification": classification,
            "code": normalized_code,
            "phase": phase,
            "endpoint": attribution.get("endpoint") or payload.get("endpoint"),
            "http_status": attribution.get("http_status") or payload.get("http_status"),
            "request_id": request_id,
            "client_run_id": plugin_client_run_id or client_run_id,
            "session_id": session_id,
            "project_instance_id": project_instance_id,
            "project_key_hash": attribution.get("project_key_hash") or stable_value_hash(route.get("project_key")),
            "inspection_run_id": run_id,
            "plugin_build_fingerprint": attribution.get("plugin_build_fingerprint") or ide.get("plugin_build_fingerprint"),
            "plugin_build_dirty": attribution.get("plugin_build_dirty") if attribution.get("plugin_build_dirty") is not None else ide.get("plugin_build_dirty"),
            "plugin_version": attribution.get("plugin_version") or ide.get("plugin_version"),
            "inspection_execution_proof_version": (
                attribution.get("inspection_execution_proof_version")
                or ide.get("inspection_execution_proof_version")
            ),
            "ide_product_code": attribution.get("ide_product_code") or ide.get("product_code"),
            "ide_version": attribution.get("ide_version") or ide.get("version"),
            "ide_channel": (
                attribution.get("ide_channel")
                or ide.get("channel")
                or ide.get("ide_channel")
                or selection.get("channel")
            ),
            "ide_channel_source": ide_channel_source,
            "helper_revision": helper_revision(),
            "cleanup_status": cleanup_status,
            "cleanup_reason": cleanup_reason,
        }
    )
    attribution = {key: value for key, value in attribution.items() if value not in (None, "", {}, [])}
    evidence_ids = {
        "client_run_id": client_run_id,
        "request_id": request_id,
        "session_id": session_id,
        "project_instance_id": project_instance_id,
        "inspection_run_id": run_id,
    }
    payload["inspection_attribution"] = attribution
    payload["attribution_class"] = classification
    payload["failure_phase"] = phase
    payload["evidence_ids"] = {key: value for key, value in evidence_ids.items() if value not in (None, "")}
    if payload.get("verdict") == "UNKNOWN" and classification == "unattributed":
        payload["unattributed_unknown"] = True
    return payload


def apply_verdict(payload: dict[str, Any]) -> dict[str, Any]:
    if isinstance(payload.get("lane_results"), list):
        return apply_multi_lane_verdict(payload)
    apply_semantic_coverage(payload)
    payload.update(verdict_for_payload(payload))
    wait = dict_field(payload, "wait")
    if payload.get("verdict") == "GREEN" and text_only_coverage_wait_timeout_is_recoverable(payload, wait):
        payload["status"] = "clean"
        payload["semantic_coverage_wait_timeout_recovered"] = True
    apply_inspection_attribution(payload)
    if payload.get("error_reason") == "worktree_mutation_detected":
        payload["failure_phase"] = payload.get("detection_phase") or "post_run_verification"
    apply_agent_result(payload)
    return payload


def apply_agent_result(payload: dict[str, Any]) -> dict[str, Any]:
    verdict = str(payload.get("verdict") or verdict_for_payload(payload).get("verdict") or "UNKNOWN")
    reason = str(payload.get("verdict_reason") or "unknown")
    bucket = outcome_bucket(payload, reason)
    retry_policy = retry_policy_for(verdict, bucket, reason)
    if verdict == "UNKNOWN" and payload.get("retry_exhausted") is True:
        retry_policy = {"retry": False, "max_attempts": 0, "wait_ms": 0}
        payload["verdict_next_action"] = exhausted_retry_next_action(reason, payload)
    diagnosis = unknown_diagnosis_for(reason, payload) if verdict == "UNKNOWN" else None
    if diagnosis is not None:
        payload["unknown_diagnosis"] = diagnosis
    next_action = str(payload.get("verdict_next_action") or next_action_for_bucket(verdict, bucket, reason, payload))
    if verdict == "UNKNOWN" and next_action in {UNKNOWN_RETRY_ACTION, UNKNOWN_TERMINAL_ACTION}:
        next_action = UNKNOWN_RETRY_ACTION if retry_policy["retry"] else UNKNOWN_TERMINAL_ACTION
        payload["verdict_next_action"] = next_action
    if verdict == "UNKNOWN" and not retry_policy["retry"] and payload.get("retry_exhausted") is not True:
        plugin_action_used = bool(payload.get("inspection_verdict_next_action")) and (
            next_action == payload["inspection_verdict_next_action"]
        )
        reason_bucket = outcome_bucket({
            "verdict": "UNKNOWN", "capture_diagnostic": payload.get("capture_diagnostic"),
        }, reason)
        diagnostic = payload.get("capture_diagnostic")
        delegated_batch_recovery = reason == "execution_not_proven" and isinstance(diagnostic, dict) and (
            normalize_reason(diagnostic.get("execution_proof_block_reason") or diagnostic.get("execution_proof_skipped_reason"))
            == "batch_inspection_delegated_to_editor_service"
        )
        if (plugin_action_used and not delegated_batch_recovery) or retry_policy_for("UNKNOWN", reason_bucket, reason)["retry"]:
            next_action = UNKNOWN_TERMINAL_ACTION
            payload["verdict_next_action"] = next_action
    next_action = guidance_for_command(next_action, payload.get("command"))
    report = agent_report_for(verdict, bucket, reason, payload, next_action)
    payload["bucket"] = bucket
    payload["retry_policy"] = retry_policy
    payload["agent_report"] = report
    agent_result = {
        "verdict": verdict,
        "bucket": bucket,
        "retry_policy": retry_policy,
        "next_action": next_action,
        "agent_report": report,
        "repository_preparation": repository_preparation_for_payload(payload),
    }
    proof_failures = payload.get("proof_failures")
    if isinstance(proof_failures, list) and proof_failures:
        agent_result["proof_failures"] = [str(failure) for failure in proof_failures]
    delegations = batch_inspection_delegations(payload)
    if delegations:
        agent_result["batch_inspection_delegations"] = delegations
        diagnostic = payload["capture_diagnostic"]
        for field in ("batch_inspection_delegation_count", "batch_inspection_delegations_omitted_count"):
            count = diagnostic.get(field)
            if type(count) is int and count >= 0:
                agent_result[field] = count
    inspection_proof = compact_inspection_proof(payload)
    if inspection_proof:
        agent_result["inspection_proof"] = inspection_proof
    if verdict == "UNKNOWN" and (blocking_batch_annotators := unproven_batch_annotators(payload)):
        agent_result["unproven_batch_annotators"] = blocking_batch_annotators
    payload["agent_result"] = agent_result
    return payload


def compact_inspection_proof(payload: dict[str, Any]) -> dict[str, Any]:
    proof = payload.get("inspection_proof")
    if not isinstance(proof, dict):
        return {}
    return {
        key: proof.get(key)
        for key in (
            "status",
            "capture_complete",
            "capture_incomplete_reason",
            "scope",
            "snapshot_outcome",
        )
        if proof.get(key) is not None
    }


def exhausted_retry_next_action(reason: str, payload: dict[str, Any]) -> str:
    retry_count = max(0, int(payload.get("internal_retry_count") or 0))
    readiness = dict_field(payload, "internal_retry_readiness")
    recovery = disk_freshness_recovery(reason, payload)
    if recovery:
        return "Stop retrying this result and report the diagnostic payload. " + recovery
    if payload.get("internal_retry_skipped") is True:
        return (
            "The helper withheld its internal retry because IDE readiness did not remain stable. Stop retrying this result and report "
            "internal_retry_readiness. Resolve same-worktree writer and IDE indexing/project-model activity before any further assessment."
        )
    if reason not in {"stale_results", "inspection_inputs_changed", "project_analysis_not_ready"}:
        return f"The helper already used {retry_count} internal retry attempt(s). Stop retrying this result and report the diagnostic payload."
    barrier_status = str(readiness.get("status") or "unknown")
    return (
        f"The helper waited for sustained IDE readiness ({barrier_status}) and used {retry_count} internal retry attempt(s), but the result remained {reason}. "
        "Stop retrying this result and report both attempts plus internal_retry_readiness. Do not attribute it to source edits unless "
        "changed-file evidence identifies them."
    )


def unknown_diagnosis_for(reason: str, payload: dict[str, Any]) -> dict[str, Any] | None:
    if reason not in {"stale_results", "inspection_inputs_changed"}:
        return None
    readiness = dict_field(payload, "internal_retry_readiness")
    diagnosis = {
        "classification": "inspection_state_changed",
        "proven": "The inspection result was rejected because the IDE could not prove a current, stable project snapshot.",
        "not_proven": "This result does not identify the changing process or prove that requested source files changed.",
        "worktree_isolation_limit": (
            "A linked Git worktree isolates checkout state; it does not stop same-worktree processes or IDE VFS, indexing, and project-model churn."
        ),
        "readiness_barrier_status": readiness.get("status"),
        "readiness_barrier_exit_reason": readiness.get("exit_reason"),
        "stale_reasons": payload.get("stale_reasons"),
        "snapshot_change_kind": payload.get("snapshot_change_kind"),
    }
    return {key: value for key, value in diagnosis.items() if value not in (None, "", [], {})}


def outcome_bucket(payload: dict[str, Any], reason: str) -> str:
    verdict = payload.get("verdict")
    if verdict == "GREEN":
        return "clean"
    if verdict == "RED":
        return "actionable_findings"
    normalized = normalize_reason(reason)
    if normalized == "lifecycle_lock_timeout":
        return "lifecycle_lock_busy"
    if normalized == "ide_memory_exhausted":
        return normalized
    if normalized == "plugin_deployment_mismatch":
        return "environment_blocked"
    if normalized == "execution_not_proven":
        diagnostic = dict_field(payload, "capture_diagnostic")
        execution_proof_reason = normalize_reason(
            diagnostic.get("execution_proof_block_reason") or diagnostic.get("execution_proof_skipped_reason")
        )
        if execution_proof_reason in {"native_inspection_not_completed", "native_inspection_aborted"}:
            return "capture_not_ready"
        if execution_proof_reason in {
            "native_scope_enumeration_failed",
            "native_inspection_failures",
            "native_inspection_reported_problems",
        }:
            return "tool_bug"
        if execution_proof_reason in {
            "whole_project_execution_not_proven",
            "directory_execution_not_proven",
            "native_attestation_context_creation_failed",
            "native_inspection_no_tools_completed",
            "native_inspection_no_files_analyzed",
            "native_inspection_no_file_scoped_tools_completed",
            "native_inspection_scope_empty",
            "native_inspection_scope_incomplete",
            "native_inspection_scope_mismatch",
            "native_cpp_batch_annotator_unproven",
            "native_batch_annotator_unproven",
            "native_tool_completion_unproven",
            "batch_inspection_delegated_to_editor_service",
        }:
            return "environment_blocked"
        return "tool_bug"
    if normalized.startswith("python_sdk_preparation_"):
        suffix = normalized.removeprefix("python_sdk_preparation_")
        if suffix in {
            "session_drift", "not_claimed", "token_mismatch", "lease_mismatch",
            "claim_mismatch", "ownership_changed", "route_incomplete",
        }:
            return "route_not_ready"
        if suffix in {
            "venv_configuration_missing", "interpreter_missing", "project_untrusted",
            "unsupported", "no_python_modules", "sdk_conflict", "ambiguous_registered_sdk",
            "existing_sdk_incomplete", "project_root_missing", "module_model_changed",
            "existing_sdk_changed", "in_progress",
            "inspection_in_progress", "cancelled",
        }:
            return "environment_blocked"
        return "tool_bug"
    if normalized in REPOSITORY_PREPARATION_TERMINAL_REASONS or normalized == "repository_preparation_failure":
        return "environment_blocked"
    attribution = dict_field(payload, "inspection_attribution")
    if payload.get("unattributed_unknown") is True or attribution.get("classification") == "unattributed":
        return "tool_bug"
    if attribution.get("classification") == "tool_caused":
        return "tool_bug"
    if normalized in {"timeout", "inspection_still_running", "inspection_api_timeout", "run_changed", "indexing", "running", "current_run_psi_churn", "already_opening", "open_state_unknown"}:
        return "ide_not_ready"
    if normalized in {"stale_results"}:
        return "stale_results"
    if normalized in {"view_not_ready", "view_updating_unreadable", "unreadable_tree", "no_results", "capture_incomplete", "scope_not_covered", "inspection_inputs_changed", "project_analysis_not_ready"}:
        return "capture_not_ready"
    if normalized in {"session_drift", "ambiguous_route", "target_project_not_open", "worktree_route_mismatch", "matching_project_route_unavailable", "route_mismatch", "route_path_invalid", "route_path_missing", "project_not_open", "ownership_not_proven"}:
        return "route_not_ready"
    if normalized == "worktree_mutation_detected" or normalized.startswith("cleanup_") or payload.get("cleanup_failed") or payload.get("cleanup_skipped") or payload.get("cleanup_deferred"):
        return "cleanup_not_clean"
    if normalized in {
        "invalid_api_response",
        "inspection_api_http_error",
        "extractor_failure",
        "helper_plugin_error",
        "inspection_trigger_empty_model",
        "non_empty_unmapped_tree",
        "inspection_proof_failed",
        AGENT_USAGE_ERROR_REASON,
        SEMANTIC_COVERAGE_TRUNCATED_REASON,
    }:
        return "tool_bug"
    if normalized in {
        "inspection_api_unavailable",
        "ide_open_failed",
        "ide_launch_disabled",
        "isolated_backend_unavailable",
        "ide_launch_refused",
        "untrusted_auto_open_root",
        "project_open_blocked",
        "ide_not_ready_timeout",
        PROJECT_CONTENT_ROOTS_MISSING_REASON,
        "language_sdk_missing",
        SEMANTIC_COVERAGE_MISSING_REASON,
    }:
        return "environment_blocked"
    if normalized in {
        "ide_selection_required",
        "ide_config_ambiguous",
        "ide_config_missing",
        "implicit_eap_selection",
        "profile_resolution_error",
        "inspection_lane_config_invalid",
        "inspection_scope_empty",
        "repository_preparation_config_invalid",
        "repository_preparation_opted_out",
    }:
        return "policy_required"
    if normalized in REPOSITORY_PREPARATION_TERMINAL_REASONS or normalized == "repository_preparation_failure":
        return "environment_blocked"
    if attribution.get("classification") == "configuration_blocked":
        return "environment_blocked"
    return "unknown"


def retry_policy_for(verdict: str, bucket: str, reason: str = "") -> dict[str, Any]:
    retry = verdict == "UNKNOWN" and bucket in UNKNOWN_RETRY_BUCKETS
    max_attempts = 3 if retry and reason == "project_analysis_not_ready" else 1 if retry else 0
    return {
        "retry": retry,
        "max_attempts": max_attempts,
        "wait_ms": UNKNOWN_RETRY_WAIT_MS if retry else 0,
    }


def next_action_for_bucket(verdict: str, bucket: str, reason: str, payload: dict[str, Any]) -> str:
    if verdict == "GREEN":
        return "No inspection action required for this scope/filter."
    if verdict == "RED":
        return "Fix the reported findings, then rerun inspection."
    if bucket == "ide_memory_exhausted":
        return next_action_for_unknown(reason, payload)
    if bucket == "lifecycle_lock_busy":
        return next_action_for_unknown(reason, payload)
    if bucket in UNKNOWN_RETRY_BUCKETS:
        return next_action_for_unknown(reason, payload)
    if bucket == "route_not_ready":
        return "Open or select the exact target worktree in the configured IDE, then rerun inspection."
    if bucket == "cleanup_not_clean":
        return "Resolve helper lifecycle cleanup or rerun inspect-closeout after the IDE settles."
    if bucket == "tool_bug":
        return "Stop retrying and report the helper/plugin diagnostic payload."
    if bucket == "environment_blocked":
        return "Fix the IDE/plugin/environment blocker, then rerun inspection."
    if bucket == "policy_required":
        return "Add explicit repo or CLI IDE policy for this inspection path, then rerun."
    return next_action_for_unknown(reason, payload)


def guidance_for_command(value: Any, command: Any) -> Any:
    if not isinstance(value, str):
        return value
    if canonical_command(str(command or "")) == "agent":
        return value.replace("inspect-closeout", preferred_command("agent"))
    return value


def agent_report_for(
    verdict: str,
    bucket: str,
    reason: str,
    payload: dict[str, Any],
    next_action: str | None = None,
) -> str:
    if verdict == "GREEN":
        coverage = semantic_coverage_for_payload(payload)
        if coverage is not None and coverage.get("status") == "text_only_allowed":
            count = int(coverage.get("missing_file_count") or 0)
            return f"JetBrains inspection passed with explicitly allowed text-only coverage for {count} file(s)."
        return "JetBrains inspection passed for the selected scope."
    if verdict == "RED":
        total = payload.get("total_problems")
        if isinstance(total, int):
            return f"JetBrains inspection found {total} actionable finding(s)."
        return "JetBrains inspection found actionable findings."
    action = next_action or str(payload.get("verdict_next_action") or next_action_for_bucket(verdict, bucket, reason, payload))
    return f"JetBrains inspection was inconclusive ({bucket}: {reason}). {action}"


def log_assessment_records(payload: dict[str, Any], exit_code: int) -> None:
    log_unknown = payload.get("verdict") == "UNKNOWN" and should_log_unknown_verdict(payload)
    log_assessment = should_log_outcome(payload)
    if not log_unknown and not log_assessment:
        return
    try:
        with outcome_routing_lock():
            if log_unknown:
                try:
                    write_unknown_verdict_record(payload)
                except OSError as error:
                    payload["unknown_log_error"] = str(error)
            if log_assessment:
                try:
                    write_outcome_record(payload, exit_code)
                except OSError as error:
                    payload["outcome_log_error"] = str(error)
    except OSError as error:
        if log_unknown:
            payload["unknown_log_error"] = str(error)
        if log_assessment:
            payload["outcome_log_error"] = str(error)


def write_unknown_verdict_record(payload: dict[str, Any]) -> None:
    log_path = unknown_log_path()
    if log_path is None:
        return
    append_jsonl_record(log_path, unknown_log_record(payload))
    payload["unknown_log_path"] = str(log_path)


def write_outcome_record(payload: dict[str, Any], exit_code: int) -> None:
    log_path = outcome_log_path()
    if log_path is None:
        return
    append_jsonl_record(log_path, outcome_log_record(payload, exit_code))
    payload["outcome_log_path"] = str(log_path)


def should_log_outcome(payload: dict[str, Any]) -> bool:
    if payload.get("usage_error") is True:
        return False
    command = canonical_command(str(payload.get("command") or ""))
    if command:
        return command in UNKNOWN_LOG_ASSESSMENT_COMMANDS
    return payload.get("verdict") in {"GREEN", "RED", "UNKNOWN"} and payload.get("status") not in UNKNOWN_LOG_INFORMATIONAL_STATUSES


def should_log_unknown_verdict(payload: dict[str, Any]) -> bool:
    command = canonical_command(str(payload.get("command") or ""))
    has_failure_evidence = any(
        payload.get(key)
        for key in (
            "error_reason",
            "capture_incomplete",
            "results_may_be_stale",
            "timed_out",
            "session_drift",
            "ambiguous",
            "unavailable",
            "indexing",
            "is_scanning",
            "inspection_in_progress",
            "cleanup_failed",
            "cleanup_skipped",
            "capture_diagnostic",
            "route_diagnostic",
            "blocked_diagnostic",
        )
    )
    if payload.get("status") in UNKNOWN_LOG_INFORMATIONAL_STATUSES and not has_failure_evidence:
        return False
    if command and command not in UNKNOWN_LOG_ASSESSMENT_COMMANDS and not has_failure_evidence:
        return False
    return True


def unknown_log_path() -> Path | None:
    return configured_log_path(UNKNOWN_LOG_ENV, "unknown-verdicts.jsonl")


def outcome_log_path() -> Path | None:
    return configured_log_path(OUTCOME_LOG_ENV, "outcomes.jsonl")


def configured_log_path(env_name: str, default_name: str) -> Path | None:
    configured = os.environ.get(env_name)
    if configured is not None:
        value = configured.strip()
        if value.lower() in {"", "0", "false", "no", "off"}:
            return None
        return Path(value).expanduser().resolve()
    return Path(code_home()) / "jetbrains-inspection" / default_name


def code_home() -> Path:
    return Path(os.environ.get("CODE_HOME") or os.environ.get("CODEX_HOME") or str(Path.home() / ".code")).expanduser()


def append_jsonl_record(
    log_path: Path,
    record: dict[str, Any],
    lock_timeout_ms: int = DEFAULT_OUTCOME_APPEND_LOCK_TIMEOUT_MS,
) -> None:
    payload = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    original_size: int | None = None
    locked = False
    try:
        if fcntl is not None:
            timeout_ms = max(0, int(lock_timeout_ms))
            deadline = time.monotonic() + (timeout_ms / 1000.0)
            while True:
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    locked = True
                    break
                except BlockingIOError as error:
                    if timeout_ms == 0 or time.monotonic() >= deadline:
                        raise TimeoutError(
                            f"Timed out waiting for the JetBrains inspection outcome log lock: {log_path}"
                        ) from error
                    time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        elif msvcrt is not None:
            timeout_ms = max(0, int(lock_timeout_ms))
            deadline = time.monotonic() + (timeout_ms / 1000.0)
            while True:
                try:
                    os.lseek(descriptor, 0, os.SEEK_SET)
                    msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
                    locked = True
                    break
                except OSError as error:
                    if timeout_ms == 0 or time.monotonic() >= deadline:
                        raise TimeoutError(
                            f"Timed out waiting for the JetBrains inspection outcome log lock: {log_path}"
                        ) from error
                    time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        else:
            raise OSError("Unsupported platform for JetBrains inspection outcome log locking")
        original_size = os.lseek(descriptor, 0, os.SEEK_END)
        remaining = memoryview(payload)
        while remaining:
            written = os.write(descriptor, remaining)
            if written <= 0:
                raise OSError("JSONL append made no progress")
            remaining = remaining[written:]
        os.fsync(descriptor)
    except BaseException:
        if original_size is not None:
            try:
                os.ftruncate(descriptor, original_size)
                os.fsync(descriptor)
            except OSError:
                pass
        raise
    finally:
        if locked:
            if fcntl is not None:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            elif msvcrt is not None:
                os.lseek(descriptor, 0, os.SEEK_SET)
                msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
        os.close(descriptor)


def ensure_outcome_event_metadata(payload: dict[str, Any]) -> tuple[str, str, int, str | None]:
    event_id = clean_optional(payload.get("_outcome_event_id"))
    if event_id is None:
        event_id = str(uuid.uuid4())
        payload["_outcome_event_id"] = event_id
    timestamp_ms = payload.get("_outcome_timestamp_ms")
    if not isinstance(timestamp_ms, int):
        timestamp_ms = int(time.time() * 1000)
        payload["_outcome_timestamp_ms"] = timestamp_ms
    timestamp = clean_optional(payload.get("_outcome_timestamp"))
    if timestamp is None:
        timestamp = utc_timestamp(timestamp_ms)
        payload["_outcome_timestamp"] = timestamp
    if "_deployment_manifest_sha256" not in payload:
        manifest_path = discover_deployment_manifest_file()
        payload["_deployment_manifest_sha256"] = file_content_sha256(manifest_path) if manifest_path else None
    manifest_sha256 = clean_optional(payload.get("_deployment_manifest_sha256"))
    return event_id, timestamp, timestamp_ms, manifest_sha256


def outcome_event_kind(command: Any) -> str | None:
    canonical = canonical_command(str(command or ""))
    if canonical in OUTCOME_ASSESSMENT_COMMANDS:
        return "inspection_assessment"
    if canonical in OUTCOME_OBSERVATION_COMMANDS:
        return "inspection_observation"
    return None


def inspection_started_for_payload(payload: dict[str, Any]) -> bool:
    explicit_values = []
    for candidate in attribution_payloads(payload):
        value = candidate.get("inspection_started")
        if isinstance(value, bool):
            explicit_values.append(value)
        attribution = candidate.get("inspection_attribution")
        if isinstance(attribution, dict) and isinstance(attribution.get("inspection_started"), bool):
            explicit_values.append(attribution["inspection_started"])
    if any(explicit_values):
        return True
    if explicit_values:
        return False
    if any(inspection_run_id(candidate) is not None for candidate in attribution_payloads(payload)):
        return True
    if payload.get("verdict") in {"GREEN", "RED"}:
        return True
    if payload.get("status") in {"clean", "findings", "results_available"}:
        return True
    for key in ("trigger", "wait", "problems"):
        candidate = payload.get(key)
        if not isinstance(candidate, dict):
            continue
        if candidate.get("status") in {"triggered", "running", "clean", "findings", "results_available"}:
            return True
        if candidate.get("inspection_in_progress") is True or candidate.get("has_inspection_results") is True:
            return True
    return False


def logged_attempt_summary(source: dict[str, Any], attempt_index: int, terminal: bool) -> dict[str, Any]:
    retry_policy = dict_field(source, "retry_policy")
    attribution = dict_field(source, "inspection_attribution")
    retry = source.get("retry")
    if not isinstance(retry, bool):
        retry = retry_policy.get("retry")
    if not isinstance(retry, bool) and not terminal:
        retry = source.get("verdict") == "UNKNOWN" and source.get("bucket") in INTERNAL_RETRY_BUCKETS
    summary = {
        "attempt_index": attempt_index,
        "terminal": terminal,
        "status": source.get("status"),
        "verdict": source.get("verdict"),
        "verdict_reason": source.get("verdict_reason") or source.get("reason"),
        "bucket": source.get("bucket"),
        "retry": retry,
        "attribution_class": source.get("attribution_class") or attribution.get("classification"),
        "phase": source.get("failure_phase") or attribution.get("phase"),
        "inspection_run_id": source.get("inspection_run_id") or attribution.get("inspection_run_id"),
        "cleanup_status": source.get("cleanup_status"),
        "total_problems": source.get("total_problems"),
        "proof_failures": source.get("proof_failures"),
        "wait_completion_reason": source.get("wait_completion_reason"),
        "snapshot_change_kind": source.get("snapshot_change_kind"),
        "results_may_be_stale": source.get("results_may_be_stale"),
        "stale_reasons": source.get("stale_reasons"),
        "capture_incomplete_reason": source.get("capture_incomplete_reason"),
        "snapshot_run_id": source.get("snapshot_run_id"),
        "snapshot_trigger_time_ms": source.get("snapshot_trigger_time_ms"),
        "results_timestamp_ms": source.get("results_timestamp_ms"),
    }
    summary.update(inspection_stage_diagnostics(source, positive_run_id(summary.get("inspection_run_id"))))
    return {key: value for key, value in summary.items() if value not in (None, {}, [])}


def ordered_internal_attempts(payload: dict[str, Any]) -> list[dict[str, Any]]:
    attempts: list[dict[str, Any]] = []
    retries_value = payload.get("internal_retries")
    retries = retries_value if isinstance(retries_value, list) else []
    next_index = 0
    for retry in retries:
        if not isinstance(retry, dict):
            continue
        raw_index = retry.get("attempt_index", retry.get("attempt", next_index))
        attempt_index = int(raw_index) if isinstance(raw_index, int) or str(raw_index).isdigit() else next_index
        attempts.append(logged_attempt_summary(retry, attempt_index, terminal=False))
        next_index = max(next_index, attempt_index + 1)
    attempts.append(logged_attempt_summary(payload, next_index, terminal=True))
    return attempts


def compact_inspection_attempt_diagnostics(payload: dict[str, Any]) -> list[dict[str, Any]]:
    retries_value = payload.get("internal_retries")
    retries = retries_value if isinstance(retries_value, list) else []
    if not retries:
        return []
    diagnostics: list[dict[str, Any]] = []
    for attempt in ordered_internal_attempts(payload)[-MAX_INSPECTION_ATTEMPT_DIAGNOSTICS:]:
        diagnostic = {
            "terminal": attempt.get("terminal"),
            **{
                key: attempt.get(key)
                for key in (
                    "inspection_run_id",
                    INSPECTION_STAGE_RUN_ID_KEY,
                    *INSPECTION_ATTEMPT_SUMMARY_KEYS,
                    *INSPECTION_STAGE_EVIDENCE_KEYS,
                )
                if attempt.get(key) not in (None, {}, [])
            },
        }
        if len(diagnostic) > 1:
            diagnostics.append(diagnostic)
    return diagnostics


def derived_cleanup_status(
    event_kind: str | None,
    inspection_started: bool,
    attribution: dict[str, Any],
    cleanup: dict[str, Any],
) -> tuple[str | None, str | None]:
    status = clean_optional(cleanup.get("status") or attribution.get("cleanup_status"))
    reason = clean_optional(cleanup.get("reason") or attribution.get("cleanup_reason"))
    if status is None and event_kind == "inspection_observation":
        return "not_applicable", reason
    code = normalize_reason(attribution.get("code") or "")
    phase = normalize_reason(attribution.get("phase") or "")
    if status is None and not inspection_started and code in QUALIFICATION_CONFIGURATION_CODES and phase == "selection":
        return "not_needed", reason or "inspection_not_started"
    return status, reason


def outcome_record_base(payload: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    event_id, timestamp, timestamp_ms, manifest_sha256 = ensure_outcome_event_metadata(payload)
    public = public_payload(payload)
    context = dict_field(public, "context")
    selection = dict_field(context, "ide_selection")
    cleanup = dict_field(public, "cleanup")
    route = payload_route(public)
    ide = dict_field(route, "ide")
    attribution = dict_field(public, "inspection_attribution")
    evidence_ids = dict_field(public, "evidence_ids")
    command = preferred_command(str(public.get("command"))) if public.get("command") else None
    event_kind = outcome_event_kind(command)
    client_run_id = evidence_ids.get("client_run_id") or attribution.get("client_run_id") or public.get("client_run_id") or context.get("client_run_id")
    started = inspection_started_for_payload(public)
    cleanup_status, cleanup_reason = derived_cleanup_status(event_kind, started, attribution, cleanup)
    scope_descriptor = context.get("scope_descriptor") if isinstance(context.get("scope_descriptor"), dict) else public.get("scope_descriptor")
    scope_descriptor_sha256 = context.get("scope_descriptor_sha256") or public.get("scope_descriptor_sha256")
    worktree_root = clean_optional(context.get("worktree_root") or public.get("worktree_root"))
    worktree_path = Path(worktree_root) if worktree_root else None
    repo_head_sha = git_head_sha(worktree_path) if worktree_path is not None and worktree_path.exists() else context.get("repo_head_sha") or public.get("repo_head_sha")
    lane_results = list_field(public, "lane_results")
    lane_summaries = [
        {
            "id": lane.get("id"),
            "required": lane.get("required"),
            "execution_order": lane.get("execution_order"),
            "verdict": lane.get("verdict"),
            "bucket": lane.get("bucket"),
            "selected_file_count": len(lane.get("files") or []),
            "ide": (lane.get("ide") or {}).get("product") or (lane.get("ide") or {}).get("requested"),
            "ide_version": (lane.get("ide") or {}).get("version"),
            "plugin_version": (lane.get("ide") or {}).get("plugin_version"),
            "plugin_build_fingerprint": (lane.get("ide") or {}).get("plugin_build_fingerprint"),
            "cleanup_status": (lane.get("cleanup") or {}).get("status"),
            "client_run_id": (lane.get("evidence_ids") or {}).get("client_run_id"),
            "request_id": (lane.get("evidence_ids") or {}).get("request_id"),
            "session_id": (lane.get("evidence_ids") or {}).get("session_id"),
            "project_instance_id": (lane.get("evidence_ids") or {}).get("project_instance_id"),
            "inspection_run_id": (lane.get("evidence_ids") or {}).get("inspection_run_id"),
            "inspection_outcome": lane.get("inspection_outcome"),
            "lifecycle_outcome": lane.get("lifecycle_outcome"),
            "inspection_attempts": (lane.get("diagnostic") or {}).get("inspection_attempts"),
            "native_tool_completion_observation": native_completion_observation(lane),
            **inspection_stage_diagnostics(
                lane,
                positive_run_id((lane.get("evidence_ids") or {}).get("inspection_run_id")),
            ),
        }
        for lane in lane_results
        if isinstance(lane, dict)
    ]
    record: dict[str, Any] = {
        "schema_version": OUTCOME_LOG_SCHEMA_VERSION,
        "event_id": event_id,
        "event_kind": event_kind,
        "assessment_id": client_run_id,
        "timestamp": timestamp,
        "timestamp_ms": timestamp_ms,
        "command": command,
        "backend": public.get("backend"),
        "verdict": public.get("verdict"),
        "bucket": public.get("bucket"),
        "verdict_reason": public.get("verdict_reason"),
        "status": public.get("status"),
        "scope": (scope_descriptor or {}).get("scope") if isinstance(scope_descriptor, dict) else context.get("scope") or public.get("scope"),
        "scope_descriptor": scope_descriptor,
        "scope_descriptor_sha256": scope_descriptor_sha256,
        "repo_path_hash": stable_value_hash(context.get("repo_path") or public.get("repo_path")),
        "worktree_root_hash": stable_value_hash(worktree_root),
        "repo_head_sha": repo_head_sha,
        "ide": attribution.get("ide_name") or ide.get("name") or selection.get("product") or context.get("ide") or public.get("ide"),
        "ide_channel": attribution.get("ide_channel") or ide.get("channel") or ide.get("ide_channel") or selection.get("channel"),
        "ide_channel_source": attribution.get("ide_channel_source"),
        "ide_product_code": attribution.get("ide_product_code") or ide.get("product_code"),
        "ide_version": attribution.get("ide_version") or ide.get("version") or selection.get("version"),
        "eap_explicit": selection.get("explicit_eap"),
        "project_key_hash": attribution.get("project_key_hash") or stable_value_hash(route.get("project_key")),
        "base_path_hash": stable_value_hash(route.get("base_path")),
        "project_instance_id": evidence_ids.get("project_instance_id") or attribution.get("project_instance_id") or route.get("project_instance_id"),
        "plugin_build_fingerprint": attribution.get("plugin_build_fingerprint") or ide.get("plugin_build_fingerprint"),
        "plugin_build_dirty": attribution.get("plugin_build_dirty") if attribution.get("plugin_build_dirty") is not None else ide.get("plugin_build_dirty"),
        "plugin_version": attribution.get("plugin_version") or ide.get("plugin_version"),
        "inspection_execution_proof_version": (
            attribution.get("inspection_execution_proof_version")
            or ide.get("inspection_execution_proof_version")
        ),
        "helper_revision": helper_revision(),
        "deployment_manifest_sha256": manifest_sha256,
        "rollout_file_hash": manifest_sha256,
        "failure_phase": public.get("failure_phase") or attribution.get("phase"),
        "attribution_class": public.get("attribution_class") or attribution.get("classification"),
        "response_code": public.get("response_code") or attribution.get("code"),
        "endpoint": attribution.get("endpoint"),
        "http_status": public.get("http_status") or attribution.get("http_status"),
        "observed_by": attribution.get("observed_by"),
        "client_run_id": client_run_id,
        "request_id": evidence_ids.get("request_id") or attribution.get("request_id"),
        "session_id": evidence_ids.get("session_id") or attribution.get("session_id") or route.get("session_id"),
        "inspection_run_id": evidence_ids.get("inspection_run_id") or attribution.get("inspection_run_id"),
        "inspection_started": started,
        "inspection_attribution": attribution,
        "unattributed_unknown": public.get("unattributed_unknown"),
        "cleanup_status": cleanup_status,
        "cleanup_reason": cleanup_reason,
        "repository_preparation": durable_repository_preparation_for_payload(public),
        "python_sdk_preparation": python_sdk_preparation_for_payload(public),
        "total_problems": public.get("total_problems"),
        "problems_shown": public.get("problems_shown"),
        "preexisting_finding_count": public.get("preexisting_finding_count"),
        "advisory_finding_count": public.get("advisory_finding_count"),
        "proof_failure_count": len(public["proof_failures"]) if isinstance(public.get("proof_failures"), list) else None,
        "uncovered_inspection_count": (
            len(public["uncovered_inspections"]) if isinstance(public.get("uncovered_inspections"), list) else None
        ),
        "internal_attempts": ordered_internal_attempts(public),
        "internal_retry_count": public.get("internal_retry_count"),
        "internal_retry_reason": public.get("internal_retry_reason"),
        "internal_retry_skipped": public.get("internal_retry_skipped"),
        "internal_retry_skip_reason": public.get("internal_retry_skip_reason"),
        "internal_retry_readiness": public.get("internal_retry_readiness"),
        "prepared_internal_retry_count": public.get("prepared_internal_retry_count"),
        "prepared_internal_retry_reason": public.get("prepared_internal_retry_reason"),
        "prepared_internal_retry_readiness": public.get("prepared_internal_retry_readiness"),
        "unknown_diagnosis": public.get("unknown_diagnosis"),
        "deployment_mismatch": public.get("deployment_mismatch"),
        "worktree_mutation_evidence": public.get("worktree_mutation_evidence"),
        "inspection_outcome": inspection_outcome_for_payload(public),
        "lifecycle_outcome": lifecycle_outcome_for_payload(public),
        "inspection_lanes": lane_summaries or None,
    }
    record.update(inspection_stage_diagnostics(public, positive_run_id(record.get("inspection_run_id"))))
    record["native_tool_completion_observation"] = native_completion_observation(
        public, positive_run_id(record.get("inspection_run_id")),
    )
    return public, record


def unknown_log_record(payload: dict[str, Any]) -> dict[str, Any]:
    public, record = outcome_record_base(payload)
    wait = dict_field(public, "wait")
    retry_policy = dict_field(public, "retry_policy")
    record.update({
        "retry": retry_policy.get("retry"),
        "verdict_message": public.get("verdict_message"),
        "verdict_next_action": durable_repository_preparation_text(
            public.get("verdict_next_action"),
            public,
        ),
        "capture_incomplete_reason": public.get("capture_incomplete_reason") or wait.get("capture_incomplete_reason"),
        "snapshot_change_kind": public.get("snapshot_change_kind"),
    })
    for key in ("capture_diagnostic", "route_diagnostic", "blocked_diagnostic"):
        if key in public:
            record[key] = public[key]
    bounded = {key: value for key, value in record.items() if value not in (None, {}, [])}
    return redact_durable_log(bounded)


def outcome_log_record(payload: dict[str, Any], exit_code: int) -> dict[str, Any]:
    public, record = outcome_record_base(payload)
    retry_policy = dict_field(public, "retry_policy")
    agent_result = dict_field(public, "agent_result")
    agent_retry_policy = dict_field(agent_result, "retry_policy")
    record.update({
        "exit_code": exit_code,
        "retry": retry_policy.get("retry"),
        "retry_max_attempts": retry_policy.get("max_attempts"),
        "retry_wait_ms": agent_retry_policy.get("wait_ms") or retry_policy.get("wait_ms"),
        "next_action": durable_repository_preparation_text(
            agent_result.get("next_action") or public.get("verdict_next_action"),
            public,
        ),
        "agent_report": durable_repository_preparation_text(
            agent_result.get("agent_report") or public.get("agent_report"),
            public,
        ),
    })
    bounded = {key: value for key, value in record.items() if value not in (None, {}, [])}
    return redact_durable_log(bounded)


def command_summarize_outcomes(args: argparse.Namespace) -> dict[str, Any]:
    log_path = Path(args.log_path).expanduser().resolve() if getattr(args, "log_path", None) else outcome_log_path()
    qualification_file = clean_optional(getattr(args, "qualification_file", None))
    if qualification_file:
        sample_size = int(getattr(args, "sample_size", 50))
        criteria, error = load_qualification_criteria(Path(qualification_file).expanduser())
        if error is not None:
            return qualification_error_summary(error[0], error[1], sample_size)
        assert criteria is not None
        if log_path is None:
            return qualification_error_summary(
                "outcome_log_disabled",
                f"Outcome logging is disabled by {OUTCOME_LOG_ENV}.",
                sample_size,
                criteria,
            )
        return summarize_qualified_outcomes(log_path, criteria, sample_size)
    if log_path is None:
        return {
            "status": "disabled",
            "message": f"Outcome logging is disabled by {OUTCOME_LOG_ENV}.",
            "events": 0,
            "invalid_lines": 0,
        }
    return summarize_outcome_log(log_path, limit=max(0, int(getattr(args, "limit", 10))))


def summarize_outcomes_exit_code(result: dict[str, Any]) -> int:
    if result.get("mode") != "qualification":
        return 0
    return 0 if result.get("gate_status") == "pass" else 1


def summarize_outcome_log(log_path: Path, limit: int = 10) -> dict[str, Any]:
    if not log_path.exists():
        return {
            "status": "missing",
            "path": str(log_path),
            "events": 0,
            "invalid_lines": 0,
            "summary": empty_outcome_summary(),
            "recent": [],
        }

    events: list[dict[str, Any]] = []
    invalid_lines = 0
    try:
        with log_path.open("r", encoding="utf-8") as handle:
            for line in handle:
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    entry = json.loads(stripped)
                except json.JSONDecodeError:
                    invalid_lines += 1
                    continue
                if isinstance(entry, dict):
                    events.append(public_outcome_event(entry))
                else:
                    invalid_lines += 1
    except OSError as error:
        return {
            "status": "error",
            "path": str(log_path),
            "error_reason": "outcome_log_unreadable",
            "error_message": str(error),
            "events": 0,
            "invalid_lines": invalid_lines,
            "summary": empty_outcome_summary(),
            "recent": [],
        }

    return {
        "status": "ok",
        "path": str(log_path),
        "events": len(events),
        "invalid_lines": invalid_lines,
        "summary": summarize_outcome_events(events),
        "recent": events[-limit:] if limit else [],
    }


def public_outcome_event(event: dict[str, Any]) -> dict[str, Any]:
    allowed = (
        "timestamp",
        "command",
        "exit_code",
        "verdict",
        "bucket",
        "verdict_reason",
        "retry",
        "retry_max_attempts",
        "retry_wait_ms",
        "next_action",
        "agent_report",
        "status",
        "scope",
        "ide",
        "ide_channel",
        "ide_version",
        "eap_explicit",
        "cleanup_status",
        "cleanup_reason",
        "total_problems",
        "problems_shown",
    )
    return {key: event[key] for key in allowed if key in event and event[key] not in (None, {}, [])}


def summarize_outcome_events(events: list[dict[str, Any]]) -> dict[str, Any]:
    retryable_unknowns = sum(1 for event in events if event.get("verdict") == "UNKNOWN" and event.get("retry") is True)
    return {
        "by_verdict": count_by(events, "verdict"),
        "by_bucket": count_by(events, "bucket"),
        "by_command": count_by(events, "command"),
        "by_retry": count_by(events, "retry"),
        "by_ide_channel": count_by(events, "ide_channel"),
        "by_cleanup_status": count_by(events, "cleanup_status"),
        "retryable_unknowns": retryable_unknowns,
    }


def empty_outcome_summary() -> dict[str, Any]:
    return {
        "by_verdict": {},
        "by_bucket": {},
        "by_command": {},
        "by_retry": {},
        "by_ide_channel": {},
        "by_cleanup_status": {},
        "retryable_unknowns": 0,
    }


def count_by(events: list[dict[str, Any]], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for event in events:
        value = event.get(key)
        if value in (None, {}, []):
            label = "unknown"
        elif isinstance(value, bool):
            label = "true" if value else "false"
        else:
            label = str(value)
        counts[label] = counts.get(label, 0) + 1
    return dict(sorted(counts.items()))


def load_qualification_criteria(path: Path) -> tuple[dict[str, Any] | None, tuple[str, str] | None]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError:
        return None, ("qualification_file_unreadable", "The qualification file could not be read.")
    except json.JSONDecodeError:
        return None, ("qualification_file_invalid", "The qualification file is not valid JSON.")
    if not isinstance(value, dict) or type(value.get("schema_version")) is not int or value.get("schema_version") != QUALIFICATION_SCHEMA_VERSION:
        return None, ("qualification_schema_invalid", "Qualification schema_version must be 1.")
    boundary = value.get("boundary")
    if not isinstance(boundary, dict):
        return None, ("qualification_boundary_invalid", "Qualification boundary must be an object.")
    if not isinstance(boundary.get("since"), str):
        return None, ("qualification_boundary_invalid", "Qualification boundary.since must be an ISO-8601 timestamp with an offset.")
    since = clean_optional(boundary.get("since"))
    since_ms = parse_iso_timestamp_ms(since)
    if since is None or since_ms is None:
        return None, ("qualification_boundary_invalid", "Qualification boundary.since must be an ISO-8601 timestamp with an offset.")
    after_event_id = boundary.get("after_event_id")
    if after_event_id is not None and (not isinstance(after_event_id, str) or clean_optional(after_event_id) is None):
        return None, ("qualification_boundary_invalid", "Qualification boundary.after_event_id must be a non-empty string when present.")
    helper = clean_optional(value.get("helper_revision")) if isinstance(value.get("helper_revision"), str) else None
    deployment = clean_optional(value.get("deployment_manifest_sha256")) if isinstance(value.get("deployment_manifest_sha256"), str) else None
    plugin = clean_optional(value.get("plugin_build_fingerprint")) if isinstance(value.get("plugin_build_fingerprint"), str) else None
    if helper is None or FULL_SHA256_PATTERN.fullmatch(helper) is None:
        return None, ("qualification_helper_revision_invalid", "Qualification helper_revision must be a full sha256 digest.")
    if deployment is None or FULL_SHA256_PATTERN.fullmatch(deployment) is None:
        return None, ("qualification_deployment_manifest_invalid", "Qualification deployment_manifest_sha256 must be a full sha256 digest.")
    if plugin is None:
        return None, ("qualification_plugin_fingerprint_invalid", "Qualification plugin_build_fingerprint must be non-empty.")
    normalized_boundary = {"since": since}
    if after_event_id is not None:
        normalized_boundary["after_event_id"] = str(after_event_id).strip()
    return {
        "schema_version": QUALIFICATION_SCHEMA_VERSION,
        "boundary": normalized_boundary,
        "helper_revision": helper,
        "plugin_build_fingerprint": plugin,
        "deployment_manifest_sha256": deployment,
        "_since_ms": since_ms,
    }, None


def public_qualification_criteria(criteria: dict[str, Any], sample_size: int) -> dict[str, Any]:
    return {
        "schema_version": criteria.get("schema_version"),
        "boundary": criteria.get("boundary"),
        "helper_revision": criteria.get("helper_revision"),
        "plugin_build_fingerprint": criteria.get("plugin_build_fingerprint"),
        "deployment_manifest_sha256": criteria.get("deployment_manifest_sha256"),
        "sample_size": sample_size,
        "minimum_decisive_rate": QUALIFICATION_MIN_DECISIVE_RATE,
    }


def qualification_error_summary(
    reason: str,
    message: str,
    sample_size: int,
    criteria: dict[str, Any] | None = None,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "status": "error",
        "mode": "qualification",
        "gate_status": "fail",
        "error_reason": reason,
        "error_message": message,
        "sample_count": 0,
        "remaining_to_sample": max(0, sample_size),
        "decisive_count": 0,
        "decisive_rate": 0.0,
        "hard_failure_count": 1,
        "hard_failure_counts": {reason: 1},
        "exclusions": [],
        "exclusion_counts": {},
        "groups": [],
        "qualifying_sample": [],
        "summaries": empty_qualification_summaries(),
        "concentration": empty_qualification_concentration(),
    }
    if criteria is not None:
        result["qualification"] = public_qualification_criteria(criteria, sample_size)
    return result


def parse_iso_timestamp_ms(value: Any) -> int | None:
    text = clean_optional(value)
    if text is None:
        return None
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    try:
        instant = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if instant.tzinfo is None:
        return None
    return int(instant.timestamp() * 1000)


def strict_event_timestamp_ms(event: dict[str, Any]) -> int | None:
    timestamp_ms = event.get("timestamp_ms")
    parsed = parse_iso_timestamp_ms(event.get("timestamp"))
    if not isinstance(timestamp_ms, int) or isinstance(timestamp_ms, bool) or parsed is None:
        return None
    return timestamp_ms if abs(timestamp_ms - parsed) <= 1 else None


def qualification_boundary_timestamp_ms(event: dict[str, Any]) -> int | None:
    has_timestamp = "timestamp" in event
    has_timestamp_ms = "timestamp_ms" in event
    parsed = parse_iso_timestamp_ms(event.get("timestamp")) if has_timestamp else None
    timestamp_ms = event.get("timestamp_ms")
    numeric = timestamp_ms if isinstance(timestamp_ms, int) and not isinstance(timestamp_ms, bool) else None
    if has_timestamp and has_timestamp_ms:
        if parsed is None or numeric is None or abs(numeric - parsed) > 1:
            return None
        return numeric
    if has_timestamp:
        return parsed
    if has_timestamp_ms:
        return numeric
    return None


def qualification_exclusion(
    event: dict[str, Any] | None,
    line_number: int,
    reason: str,
    hard_failure: bool,
    detail: dict[str, Any] | None = None,
) -> dict[str, Any]:
    exclusion: dict[str, Any] = {
        "line_number": line_number,
        "reason": reason,
        "hard_failure": hard_failure,
    }
    if isinstance(event, dict):
        for key in ("event_id", "assessment_id", "timestamp", "command", "verdict", "bucket"):
            if event.get(key) not in (None, "", {}, []):
                exclusion[key] = event[key]
    if detail:
        exclusion["detail"] = detail
    return exclusion


def qualification_post_boundary_rows(
    rows: list[tuple[int, dict[str, Any] | None]],
    criteria: dict[str, Any],
) -> tuple[list[tuple[int, dict[str, Any] | None]], tuple[str, str] | None]:
    boundary = criteria["boundary"]
    after_event_id = boundary.get("after_event_id")
    anchor_line: int | None = None
    if after_event_id:
        anchors = [line_number for line_number, event in rows if isinstance(event, dict) and event.get("event_id") == after_event_id]
        if not anchors:
            return [], ("boundary_event_not_found", "The boundary after_event_id was not found in the outcome log.")
        if len(anchors) > 1:
            return [], ("boundary_event_ambiguous", "The boundary after_event_id occurs more than once in the outcome log.")
        anchor_line = anchors[0]
    elif any(event is None or qualification_boundary_timestamp_ms(event) is None for _, event in rows):
        return [], (
            "boundary_event_required_for_invalid_log",
            "The outcome log contains malformed or timestamp-indeterminate rows, so qualification requires boundary.after_event_id to prove which rows are post-boundary.",
        )
    since_ms = int(criteria["_since_ms"])
    post_boundary: list[tuple[int, dict[str, Any] | None]] = []
    timestamp_boundary_reached = False
    for line_number, event in rows:
        if anchor_line is not None and line_number <= anchor_line:
            continue
        boundary_timestamp = qualification_boundary_timestamp_ms(event) if isinstance(event, dict) else None
        if boundary_timestamp is not None:
            if boundary_timestamp < since_ms:
                continue
            timestamp_boundary_reached = True
            post_boundary.append((line_number, event))
            continue
        if anchor_line is not None or timestamp_boundary_reached:
            post_boundary.append((line_number, event))
    return post_boundary, None


def normalize_qualification_attempts(event: dict[str, Any]) -> tuple[list[dict[str, Any]] | None, str | None]:
    raw_attempts = event.get("internal_attempts")
    if not isinstance(raw_attempts, list) or not raw_attempts:
        return None, "malformed_internal_attempts"
    attempts: list[dict[str, Any]] = []
    indexes: list[int] = []
    for raw in raw_attempts:
        if not isinstance(raw, dict) or not isinstance(raw.get("attempt_index"), int):
            return None, "malformed_internal_attempts"
        verdict = raw.get("verdict")
        if verdict not in {"GREEN", "RED", "UNKNOWN"}:
            return None, "malformed_internal_attempts"
        indexes.append(raw["attempt_index"])
        attempt = {
            key: raw[key]
            for key in (
                "attempt_index",
                "terminal",
                "status",
                "verdict",
                "verdict_reason",
                "bucket",
                "retry",
                "attribution_class",
                "phase",
                "inspection_run_id",
                "cleanup_status",
                "total_problems",
                "proof_failures",
                "wait_completion_reason",
                "snapshot_change_kind",
            )
            if key in raw and raw[key] not in (None, {}, [])
        }
        attempts.append(attempt)
    if indexes != sorted(set(indexes)) or attempts[-1].get("terminal") is not True:
        return None, "malformed_internal_attempts"
    if any(attempt.get("terminal") is True for attempt in attempts[:-1]):
        return None, "malformed_internal_attempts"
    if attempts[-1].get("verdict") != event.get("verdict"):
        return None, "internal_attempt_outcome_mismatch"
    return attempts, None


def helper_text_only_attribution_allowed(event: dict[str, Any]) -> bool:
    scope_descriptor = event.get("scope_descriptor")
    internal_attempts = event.get("internal_attempts")
    terminal_attempt = internal_attempts[-1] if isinstance(internal_attempts, list) and internal_attempts else {}
    proof_failures = terminal_attempt.get("proof_failures") if isinstance(terminal_attempt, dict) else None
    return (
        event.get("verdict") == "GREEN"
        and event.get("verdict_reason") == "text_only_coverage_allowed"
        and event.get("response_code") == "text_only_coverage_allowed"
        and event.get("attribution_class") == "decisive"
        and event.get("inspection_started") is True
        and event.get("total_problems") == 0
        and isinstance(scope_descriptor, dict)
        and scope_descriptor.get("allow_text_only_coverage") is True
        and isinstance(terminal_attempt, dict)
        and terminal_attempt.get("verdict") == "GREEN"
        and terminal_attempt.get("verdict_reason") == "text_only_coverage_allowed"
        and proof_failures == [SEMANTIC_COVERAGE_MISSING_REASON]
    )


def plugin_attribution_mismatches(event: dict[str, Any]) -> list[str]:
    attribution = event.get("inspection_attribution")
    if not isinstance(attribution, dict):
        return ["inspection_attribution"]
    mismatches: list[str] = []
    if attribution.get("schema_version") != INSPECTION_ATTRIBUTION_SCHEMA_VERSION:
        mismatches.append("schema_version")
    expected_source = "helper" if helper_text_only_attribution_allowed(event) else "plugin"
    if attribution.get("source") != expected_source:
        mismatches.append("source")
    if event.get("ide_channel_source") != "plugin_attribution":
        mismatches.append("ide_channel_source")
    required = {
        "classification": event.get("attribution_class"),
        "code": event.get("response_code"),
        "phase": event.get("failure_phase"),
        "endpoint": event.get("endpoint"),
        "http_status": event.get("http_status"),
        "request_id": event.get("request_id"),
        "observed_by": event.get("observed_by"),
        "client_run_id": event.get("client_run_id"),
        "session_id": event.get("session_id"),
        "project_instance_id": event.get("project_instance_id"),
        "project_key_hash": event.get("project_key_hash"),
        "inspection_run_id": event.get("inspection_run_id"),
        "plugin_version": event.get("plugin_version"),
        "plugin_build_fingerprint": event.get("plugin_build_fingerprint"),
        "ide_product_code": event.get("ide_product_code"),
        "ide_version": event.get("ide_version"),
        "ide_channel": event.get("ide_channel"),
        "ide_channel_source": event.get("ide_channel_source"),
        "helper_revision": event.get("helper_revision"),
        "cleanup_status": event.get("cleanup_status"),
    }
    for key, expected in required.items():
        if expected in (None, "", {}, []) or attribution.get(key) != expected:
            mismatches.append(key)
    return sorted(set(mismatches))


def configuration_attribution_mismatches(event: dict[str, Any]) -> list[str]:
    attribution = event.get("inspection_attribution")
    if not isinstance(attribution, dict):
        return ["inspection_attribution"]
    required = {
        "schema_version": INSPECTION_ATTRIBUTION_SCHEMA_VERSION,
        "source": "helper",
        "observed_by": "helper",
        "classification": event.get("attribution_class"),
        "code": event.get("response_code"),
        "phase": event.get("failure_phase"),
        "client_run_id": event.get("client_run_id"),
        "helper_revision": event.get("helper_revision"),
        "cleanup_status": event.get("cleanup_status"),
    }
    mismatches = [
        key
        for key, expected in required.items()
        if expected in (None, "", {}, []) or attribution.get(key) != expected
    ]
    for key in ("endpoint", "http_status", "request_id", "ide_channel_source"):
        expected = event.get(key)
        actual = attribution.get(key)
        if expected not in (None, "", {}, []) or actual not in (None, "", {}, []):
            if actual != expected:
                mismatches.append(key)
    return sorted(set(mismatches))


def positive_inspection_run_id(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def qualification_event_candidate(
    event: dict[str, Any],
    line_number: int,
    criteria: dict[str, Any],
) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    if event.get("backend") not in (None, "api"):
        return None, qualification_exclusion(event, line_number, "backend_not_qualified", False)
    client_run_id = clean_optional(event.get("client_run_id"))
    assessment_id = clean_optional(event.get("assessment_id"))
    if client_run_id is None or assessment_id is None:
        return None, qualification_exclusion(event, line_number, "missing_client_run_id", True)
    if client_run_id != assessment_id:
        return None, qualification_exclusion(event, line_number, "assessment_id_mismatch", True)
    if event.get("helper_revision") != criteria.get("helper_revision"):
        return None, qualification_exclusion(
            event,
            line_number,
            "helper_revision_mismatch",
            True,
            {"actual": event.get("helper_revision"), "expected": criteria.get("helper_revision")},
        )
    if event.get("deployment_manifest_sha256") != criteria.get("deployment_manifest_sha256"):
        return None, qualification_exclusion(
            event,
            line_number,
            "deployment_manifest_mismatch",
            True,
            {"actual": event.get("deployment_manifest_sha256"), "expected": criteria.get("deployment_manifest_sha256")},
        )
    scope_descriptor = event.get("scope_descriptor")
    scope_sha256 = event.get("scope_descriptor_sha256")
    if not isinstance(scope_descriptor, dict) or not scope_descriptor or not isinstance(scope_sha256, str):
        return None, qualification_exclusion(event, line_number, "missing_scope", True)
    if scope_sha256 != canonical_json_sha256(scope_descriptor):
        return None, qualification_exclusion(event, line_number, "scope_hash_mismatch", True)
    inspection_started = event.get("inspection_started")
    if not isinstance(inspection_started, bool):
        return None, qualification_exclusion(event, line_number, "missing_inspection_started", True)
    classification = clean_optional(event.get("attribution_class"))
    raw_response_code = clean_optional(event.get("response_code"))
    raw_phase = clean_optional(event.get("failure_phase"))
    if classification is None:
        return None, qualification_exclusion(event, line_number, "missing_attribution_class", True)
    if raw_response_code is None:
        return None, qualification_exclusion(event, line_number, "missing_response_code", True)
    if raw_phase is None:
        return None, qualification_exclusion(event, line_number, "missing_failure_phase", True)
    response_code = normalize_reason(raw_response_code)
    phase = normalize_reason(raw_phase)
    cleanup_status = clean_optional(event.get("cleanup_status"))
    if cleanup_status is None:
        return None, qualification_exclusion(event, line_number, "missing_cleanup_status", True)
    attempts, attempts_error = normalize_qualification_attempts(event)
    if attempts_error is not None:
        return None, qualification_exclusion(event, line_number, attempts_error, True)
    assert attempts is not None
    verdict = event.get("verdict")
    if classification == "configuration_blocked" and inspection_started is False:
        preparation = dict_field(event, "repository_preparation")
        preparation_reason = preparation.get("failure_reason")
        if not isinstance(preparation_reason, str) or not preparation_reason.strip():
            if preparation.get("execution_state") in {"failed", "blocked"}:
                preparation_reason = "repository_preparation_failure"
        if isinstance(preparation_reason, str) and preparation_reason.strip():
            return None, qualification_exclusion(
                event,
                line_number,
                normalize_reason(preparation_reason),
                True,
                {"preparation": preparation},
            )
        if cleanup_status not in QUALIFICATION_CLEANUP_STATUSES:
            return None, qualification_exclusion(event, line_number, "non_clean_cleanup", True)
        attribution_mismatches = configuration_attribution_mismatches(event)
        if attribution_mismatches == ["inspection_attribution"]:
            return None, qualification_exclusion(event, line_number, "missing_inspection_attribution", True)
        if attribution_mismatches:
            return None, qualification_exclusion(
                event,
                line_number,
                "inspection_attribution_mismatch",
                True,
                {"fields": attribution_mismatches},
            )
        if verdict == "UNKNOWN" and raw_response_code in QUALIFICATION_CONFIGURATION_CODES and raw_phase == "selection":
            return None, qualification_exclusion(event, line_number, "configuration_blocked_before_start", False)
        return None, qualification_exclusion(
            event,
            line_number,
            "configuration_blocked_not_excludable",
            True,
            {"response_code": response_code, "phase": phase, "inspection_started": False},
        )
    if inspection_started is False:
        return None, qualification_exclusion(event, line_number, "inspection_not_started", True)
    inspection_run = event.get("inspection_run_id")
    if not positive_inspection_run_id(inspection_run):
        return None, qualification_exclusion(event, line_number, "missing_inspection_run_id", True)
    for key, reason in (
        ("ide_product_code", "missing_ide_product_code"),
        ("ide_version", "missing_ide_version"),
        ("ide_channel", "missing_ide_channel"),
    ):
        if clean_optional(event.get(key)) is None:
            return None, qualification_exclusion(event, line_number, reason, True)
    if event.get("plugin_build_fingerprint") != criteria.get("plugin_build_fingerprint"):
        return None, qualification_exclusion(
            event,
            line_number,
            "plugin_fingerprint_mismatch",
            True,
            {"actual": event.get("plugin_build_fingerprint"), "expected": criteria.get("plugin_build_fingerprint")},
        )
    for key, reason in (
        ("repo_path_hash", "missing_repo_hash"),
        ("worktree_root_hash", "missing_worktree_hash"),
        ("project_key_hash", "missing_project_hash"),
    ):
        value = clean_optional(event.get(key))
        if value is None or FULL_SHA256_PATTERN.fullmatch(value) is None:
            return None, qualification_exclusion(event, line_number, reason, True)
    for key, reason in (
        ("plugin_version", "missing_plugin_version"),
        ("repo_head_sha", "missing_repo_head_sha"),
        ("session_id", "missing_session_id"),
        ("project_instance_id", "missing_project_instance_id"),
    ):
        if clean_optional(event.get(key)) is None:
            return None, qualification_exclusion(event, line_number, reason, True)
    if re.fullmatch(r"[0-9a-fA-F]{40,64}", str(event.get("repo_head_sha"))) is None:
        return None, qualification_exclusion(event, line_number, "missing_repo_head_sha", True)
    semantic_failures: list[str] = []
    attribution_mismatches = plugin_attribution_mismatches(event)
    if attribution_mismatches == ["inspection_attribution"]:
        semantic_failures.append("missing_inspection_attribution")
    elif attribution_mismatches:
        semantic_failures.append("inspection_attribution_mismatch")
    if verdict == "UNKNOWN" and (classification in {None, "unattributed"} or event.get("unattributed_unknown") is True):
        semantic_failures.append("unattributed_unknown")
    if cleanup_status not in QUALIFICATION_CLEANUP_STATUSES:
        semantic_failures.append("non_clean_cleanup")
    preparation = dict_field(event, "repository_preparation")
    preparation_reason = preparation.get("failure_reason")
    if not isinstance(preparation_reason, str) or not preparation_reason.strip():
        if preparation.get("execution_state") in {"failed", "blocked"}:
            preparation_reason = "repository_preparation_failure"
    if isinstance(preparation_reason, str) and preparation_reason.strip():
        semantic_failures.append(normalize_reason(preparation_reason))
    if classification == "configuration_blocked":
        semantic_failures.append("configuration_blocked_after_start")
    candidate = {
        "assessment_id": assessment_id,
        "event_id": event.get("event_id"),
        "timestamp": event.get("timestamp"),
        "timestamp_ms": event.get("timestamp_ms"),
        "command": event.get("command"),
        "verdict": verdict,
        "bucket": event.get("bucket"),
        "classification": classification,
        "phase": event.get("failure_phase"),
        "response_code": event.get("response_code"),
        "cleanup_status": cleanup_status,
        "cleanup_reason": event.get("cleanup_reason"),
        "inspection_run_id": inspection_run,
        "repo_path_hash": event.get("repo_path_hash"),
        "worktree_root_hash": event.get("worktree_root_hash"),
        "project_key_hash": event.get("project_key_hash"),
        "repo_head_sha": event.get("repo_head_sha"),
        "scope_descriptor_sha256": scope_sha256,
        "internal_attempts": attempts,
        "_line_number": line_number,
        "_hard_failures": sorted(set(semantic_failures)),
    }
    return candidate, None


def qualification_group(candidate_events: list[dict[str, Any]]) -> dict[str, Any]:
    ordered = sorted(candidate_events, key=lambda event: (event["_line_number"], event.get("timestamp_ms", 0), str(event.get("event_id") or "")))
    attempts: list[dict[str, Any]] = []
    hard_failures = {reason for event in ordered for reason in event.get("_hard_failures", [])}
    if len(ordered) > 1:
        hard_failures.add("multiple_assessment_events")
    for event_order, event in enumerate(ordered):
        for attempt in event["internal_attempts"]:
            attempts.append(dict(attempt) | {"event_id": event.get("event_id"), "event_order": event_order})
    decisive = {attempt.get("verdict") for attempt in attempts if attempt.get("verdict") in {"GREEN", "RED"}}
    conflicting = len(decisive) > 1
    if conflicting:
        hard_failures.add("conflicting_outcomes")
    last_attempt = attempts[-1]
    final_verdict = "CONFLICT" if conflicting else last_attempt.get("verdict")
    unknown_before_final = any(attempt.get("verdict") == "UNKNOWN" for attempt in attempts[:-1])
    hidden_terminal_failure = any(
        attempt.get("verdict") == "UNKNOWN" and attempt.get("retry") is not True
        for attempt in attempts[:-1]
    ) or (last_attempt.get("verdict") == "UNKNOWN" and bool(decisive))
    if hidden_terminal_failure:
        hard_failures.add("hidden_terminal_failure")
    identity_fields = ("repo_path_hash", "worktree_root_hash", "project_key_hash", "repo_head_sha", "scope_descriptor_sha256")
    for field in identity_fields:
        if len({event.get(field) for event in ordered}) > 1:
            hard_failures.add("assessment_identity_conflict")
    final = ordered[-1]
    inspection_run_ids = []
    for event in ordered:
        run_id = event.get("inspection_run_id")
        if run_id not in inspection_run_ids:
            inspection_run_ids.append(run_id)
    return {
        "assessment_id": final["assessment_id"],
        "event_ids": [event.get("event_id") for event in ordered],
        "event_count": len(ordered),
        "first_timestamp": ordered[0].get("timestamp"),
        "final_timestamp": final.get("timestamp"),
        "verdict": final_verdict,
        "classification": final.get("classification"),
        "phase": final.get("phase"),
        "cleanup_status": final.get("cleanup_status"),
        "inspection_run_ids": inspection_run_ids,
        "repo_path_hash": final.get("repo_path_hash"),
        "worktree_root_hash": final.get("worktree_root_hash"),
        "project_key_hash": final.get("project_key_hash"),
        "repo_head_sha": final.get("repo_head_sha"),
        "scope_descriptor_sha256": final.get("scope_descriptor_sha256"),
        "recovered_from_unknown": (
            len(ordered) == 1
            and final_verdict in {"GREEN", "RED"}
            and unknown_before_final
            and not hidden_terminal_failure
        ),
        "conflicting_decisive_outcomes": conflicting,
        "hidden_terminal_failure": hidden_terminal_failure,
        "hard_failures": sorted(hard_failures),
        "internal_attempts": attempts,
        "_first_line_number": ordered[0]["_line_number"],
    }


def public_qualification_group(group: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in group.items() if not key.startswith("_") and value not in (None, {}, [])}


def repeated_counts(groups: list[dict[str, Any]], key: str) -> dict[str, int]:
    return {label: count for label, count in count_by(groups, key).items() if label != "unknown" and count > 1}


def empty_qualification_summaries() -> dict[str, Any]:
    return {
        "by_verdict": {},
        "by_classification": {},
        "by_phase": {},
        "by_cleanup_status": {},
        "recovered_from_unknown": 0,
        "conflicting_decisive_outcomes": 0,
        "hidden_terminal_failures": 0,
    }


def empty_qualification_concentration() -> dict[str, Any]:
    return {"repeated_repositories": {}, "repeated_projects": {}}


def summarize_qualified_outcomes(log_path: Path, criteria: dict[str, Any], sample_size: int) -> dict[str, Any]:
    if sample_size <= 0:
        return qualification_error_summary("invalid_sample_size", "sample-size must be greater than zero.", sample_size, criteria)
    base = {
        "status": "ok",
        "mode": "qualification",
        "qualification": public_qualification_criteria(criteria, sample_size),
    }
    if not log_path.exists():
        return base | {
            "gate_status": "incomplete",
            "post_boundary_events": 0,
            "sample_count": 0,
            "remaining_to_sample": sample_size,
            "decisive_count": 0,
            "decisive_rate": 0.0,
            "hard_failure_count": 0,
            "hard_failure_counts": {},
            "exclusions": [],
            "exclusion_counts": {},
            "groups": [],
            "qualifying_sample": [],
            "summaries": empty_qualification_summaries(),
            "concentration": empty_qualification_concentration(),
            "gate_failures": ["sample_incomplete"],
        }
    rows: list[tuple[int, dict[str, Any] | None]] = []
    try:
        with log_path.open("r", encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, start=1):
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    value = json.loads(stripped)
                except json.JSONDecodeError:
                    value = None
                rows.append((line_number, value if isinstance(value, dict) else None))
    except OSError:
        return qualification_error_summary("outcome_log_unreadable", "The outcome log could not be read.", sample_size, criteria)
    post_boundary, boundary_error = qualification_post_boundary_rows(rows, criteria)
    if boundary_error is not None:
        return qualification_error_summary(boundary_error[0], boundary_error[1], sample_size, criteria)
    exclusions: list[dict[str, Any]] = []
    candidates_by_assessment: dict[str, list[dict[str, Any]]] = {}
    post_boundary_lines = {line_number for line_number, _ in post_boundary}
    seen_event_ids: dict[str, str] = {}
    for line_number, event in rows:
        if line_number in post_boundary_lines or not isinstance(event, dict):
            continue
        event_id = clean_optional(event.get("event_id"))
        if event_id is None:
            continue
        event_signature = canonical_json_sha256(event)
        previous_signature = seen_event_ids.get(event_id)
        if previous_signature is None:
            seen_event_ids[event_id] = event_signature
        elif previous_signature != event_signature:
            seen_event_ids[event_id] = "pre_boundary_conflict"
    for line_number, event in post_boundary:
        if event is None:
            exclusions.append(qualification_exclusion(None, line_number, "invalid_json", True))
            continue
        if event.get("schema_version") != OUTCOME_LOG_SCHEMA_VERSION:
            exclusions.append(qualification_exclusion(event, line_number, "legacy_schema", True))
            continue
        if strict_event_timestamp_ms(event) is None:
            exclusions.append(qualification_exclusion(event, line_number, "invalid_timestamp", True))
            continue
        event_id = clean_optional(event.get("event_id"))
        if event_id is None:
            exclusions.append(qualification_exclusion(event, line_number, "missing_event_id", True))
            continue
        event_signature = canonical_json_sha256(event)
        if event_id in seen_event_ids:
            if seen_event_ids[event_id] == event_signature:
                exclusions.append(qualification_exclusion(event, line_number, "duplicate_event", False))
            else:
                exclusions.append(qualification_exclusion(event, line_number, "conflicting_event_id", True))
            continue
        seen_event_ids[event_id] = event_signature
        if event.get("event_kind") != "inspection_assessment":
            exclusions.append(qualification_exclusion(event, line_number, "non_assessment_command", False))
            continue
        if canonical_command(str(event.get("command") or "")) not in OUTCOME_ASSESSMENT_COMMANDS:
            exclusions.append(qualification_exclusion(event, line_number, "non_assessment_command", True))
            continue
        candidate, exclusion = qualification_event_candidate(event, line_number, criteria)
        if exclusion is not None:
            exclusions.append(exclusion)
            continue
        assert candidate is not None
        candidates_by_assessment.setdefault(candidate["assessment_id"], []).append(candidate)
    groups = sorted(
        (qualification_group(events) for events in candidates_by_assessment.values()),
        key=lambda group: (group["_first_line_number"], str(group["assessment_id"])),
    )
    selected_assessment_ids = [group["assessment_id"] for group in groups[:sample_size]]
    if len(selected_assessment_ids) == sample_size:
        frozen_sample_cutoff_line = groups[sample_size - 1]["_first_line_number"]
        sample_candidates_by_assessment = {
            assessment_id: [
                candidate
                for candidate in candidates_by_assessment[assessment_id]
                if candidate["_line_number"] <= frozen_sample_cutoff_line
            ]
            for assessment_id in selected_assessment_ids
        }
    else:
        frozen_sample_cutoff_line = max((line_number for line_number, _ in post_boundary), default=0)
        sample_candidates_by_assessment = {
            assessment_id: list(candidates_by_assessment[assessment_id])
            for assessment_id in selected_assessment_ids
        }
    sample = [
        qualification_group(sample_candidates_by_assessment[assessment_id])
        for assessment_id in selected_assessment_ids
    ]
    sample_exclusions = [
        exclusion
        for exclusion in exclusions
        if int(exclusion.get("line_number") or 0) <= frozen_sample_cutoff_line
    ]
    decisive_count = sum(1 for group in sample if group.get("verdict") in {"GREEN", "RED"})
    decisive_rate = decisive_count / len(sample) if sample else 0.0
    exclusion_counts = count_by(exclusions, "reason")
    sample_exclusion_counts = count_by(sample_exclusions, "reason")
    hard_failure_counts: dict[str, int] = {}
    for exclusion in sample_exclusions:
        if exclusion.get("hard_failure") is True:
            reason = str(exclusion.get("reason"))
            hard_failure_counts[reason] = hard_failure_counts.get(reason, 0) + 1
    for group in sample:
        for reason in group.get("hard_failures", []):
            hard_failure_counts[reason] = hard_failure_counts.get(reason, 0) + 1
    hard_failure_counts = dict(sorted(hard_failure_counts.items()))
    hard_failure_count = sum(hard_failure_counts.values())
    all_hard_failure_counts: dict[str, int] = {}
    for exclusion in exclusions:
        if exclusion.get("hard_failure") is True:
            reason = str(exclusion.get("reason"))
            all_hard_failure_counts[reason] = all_hard_failure_counts.get(reason, 0) + 1
    for group in groups:
        for reason in group.get("hard_failures", []):
            all_hard_failure_counts[reason] = all_hard_failure_counts.get(reason, 0) + 1
    post_sample_hard_failure_counts = {
        reason: count - hard_failure_counts.get(reason, 0)
        for reason, count in sorted(all_hard_failure_counts.items())
        if count > hard_failure_counts.get(reason, 0)
    }
    remaining = max(0, sample_size - len(sample))
    gate_failures = list(hard_failure_counts)
    if remaining:
        gate_status = "fail" if hard_failure_count else "incomplete"
        gate_failures.append("sample_incomplete")
    elif decisive_rate < QUALIFICATION_MIN_DECISIVE_RATE:
        gate_status = "fail"
        gate_failures.append("decisive_rate_below_threshold")
    elif hard_failure_count:
        gate_status = "fail"
    else:
        gate_status = "pass"
    public_groups = [public_qualification_group(group) for group in groups]
    public_sample = [public_qualification_group(group) for group in sample]
    summaries = {
        "by_verdict": count_by(sample, "verdict"),
        "by_classification": count_by(sample, "classification"),
        "by_phase": count_by(sample, "phase"),
        "by_cleanup_status": count_by(sample, "cleanup_status"),
        "recovered_from_unknown": sum(1 for group in sample if group.get("recovered_from_unknown") is True),
        "conflicting_decisive_outcomes": sum(1 for group in sample if group.get("conflicting_decisive_outcomes") is True),
        "hidden_terminal_failures": sum(1 for group in sample if group.get("hidden_terminal_failure") is True),
    }
    concentration = {
        "repeated_repositories": repeated_counts(sample, "repo_path_hash"),
        "repeated_projects": repeated_counts(sample, "project_key_hash"),
    }
    result = base | {
        "gate_status": gate_status,
        "post_boundary_events": len(post_boundary),
        "sample_cutoff_line": frozen_sample_cutoff_line,
        "post_sample_events": sum(1 for line_number, _ in post_boundary if line_number > frozen_sample_cutoff_line),
        "assessment_groups": len(groups),
        "sample_count": len(sample),
        "remaining_to_sample": remaining,
        "decisive_count": decisive_count,
        "decisive_rate": round(decisive_rate, 6),
        "hard_failure_count": hard_failure_count,
        "hard_failure_counts": hard_failure_counts,
        "post_sample_hard_failure_counts": post_sample_hard_failure_counts,
        "exclusions": exclusions,
        "exclusion_counts": exclusion_counts,
        "sample_window_exclusion_counts": sample_exclusion_counts,
        "groups": public_groups,
        "qualifying_sample": public_sample,
        "summaries": summaries,
        "concentration": concentration,
        "gate_failures": sorted(set(gate_failures)),
    }
    return redact_durable_log(result)


def utc_timestamp(timestamp_ms: int | None = None) -> str:
    instant = datetime.fromtimestamp((timestamp_ms if timestamp_ms is not None else int(time.time() * 1000)) / 1000, tz=timezone.utc)
    return instant.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def discover_rollout_file() -> str | None:
    for env_name in ROLLOUT_FILE_ENVS:
        value = os.environ.get(env_name)
        if value:
            return str(Path(value).expanduser())
    return None


def discover_deployment_manifest_file() -> str | None:
    configured = os.environ.get(DEPLOYMENT_MANIFEST_ENV)
    if configured and configured.strip():
        return str(Path(configured).expanduser())
    return discover_rollout_file()


def verdict_exit_code(payload: dict[str, Any], success_verdicts: set[str]) -> int:
    verdict = payload.get("verdict")
    if verdict not in {"GREEN", "RED", "UNKNOWN"}:
        verdict = verdict_for_payload(payload).get("verdict")
    return 0 if verdict in success_verdicts else 1


def classify_run_exit(result: dict[str, Any]) -> int:
    return verdict_exit_code(result, {"GREEN"})


def classify_prepare_exit(result: dict[str, Any]) -> int:
    return 0 if result.get("status") == "prepared" else 1


def classify_closeout_exit(result: dict[str, Any]) -> int:
    if result.get("cleanup_failed") or result.get("cleanup_skipped"):
        return 1
    return classify_run_exit(result)


def classify_cleanup_leases_exit(result: dict[str, Any]) -> int:
    return 1 if result.get("failed") or result.get("unresolved") else 0


def classify_wait_exit(result: dict[str, Any]) -> int:
    return verdict_exit_code(result, {"GREEN", "RED"})


def classify_problems_exit(result: dict[str, Any]) -> int:
    return verdict_exit_code(result, {"GREEN"})


def classify_status_body_clean(body: dict[str, Any]) -> bool:
    if body.get("session_drift") or body.get("ambiguous") or body.get("unavailable"):
        return False
    if body.get("timed_out") or body.get("capture_incomplete") or body.get("results_may_be_stale"):
        return False
    if body.get("is_scanning") or body.get("indexing") or body.get("inspection_in_progress"):
        return False
    status = str(body.get("status") or body.get("completion_reason") or "").lower()
    if status:
        return status == "clean"
    return body.get("clean_inspection") is True


def status_label(body: dict[str, Any]) -> str:
    explicit_status = body.get("status") or body.get("completion_reason")
    if explicit_status:
        return str(explicit_status)
    if body.get("session_drift"):
        return "session_drift"
    if body.get("ambiguous"):
        return "ambiguous"
    if body.get("unavailable"):
        return "unavailable"
    if body.get("results_may_be_stale"):
        return "stale_results"
    if body.get("capture_incomplete"):
        return "capture_incomplete"
    if body.get("timed_out"):
        return "timed_out"
    if body.get("indexing"):
        return "indexing"
    if body.get("is_scanning") or body.get("inspection_in_progress"):
        return "running"
    if body.get("clean_inspection") is True:
        return "clean"
    if body.get("has_inspection_results") is True:
        return "results_available"
    return "unknown"


def classify_status_exit(result: dict[str, Any]) -> int:
    return verdict_exit_code(result, {"GREEN", "RED"})


def selected_inspection_file_count(payload: dict[str, Any]) -> int | None:
    selection = payload.get("lane_selection")
    if isinstance(selection, dict):
        count = selection.get("selected_file_count")
    else:
        diagnostic = payload.get("capture_diagnostic")
        context = dict_field(payload, "context")
        scope = (diagnostic.get("scope_kind") if isinstance(diagnostic, dict) else None) or context.get("scope")
        if scope in {"whole_project", "directory"}:
            # Broad scopes report zero here as a placeholder; their native
            # traversal proof establishes execution separately.
            return None
        if isinstance(diagnostic, dict) and "scope_file_resolved_count" in diagnostic:
            count = diagnostic["scope_file_resolved_count"]
        else:
            coverage = payload.get("semantic_coverage") or {}
            count = coverage.get("resolved_file_count") if isinstance(coverage, dict) else None
    return count if isinstance(count, int) and not isinstance(count, bool) and count >= 0 else None


def compact_agent_result_payload(payload: dict[str, Any], helper_exit_code: int) -> dict[str, Any]:
    if isinstance(payload.get("lane_results"), list):
        return compact_multi_lane_agent_result_payload(payload, helper_exit_code)
    agent_result = dict(payload.get("agent_result") or {})
    retry_policy = dict_field(agent_result, "retry_policy")
    agent_result["terminal"] = retry_policy.get("retry") is not True
    agent_result["next_action"] = guidance_for_command(agent_result.get("next_action"), "agent-inspect")
    agent_result["agent_report"] = guidance_for_command(agent_result.get("agent_report"), "agent-inspect")
    cleanup = dict_field(payload, "cleanup")
    context = dict_field(payload, "context")
    attribution = dict_field(payload, "inspection_attribution")
    route = dict_field(payload, "route")
    ide = dict_field(route, "ide")
    problems = list_field(payload, "problems")
    available_findings = [problem for problem in problems if isinstance(problem, dict)]
    compact_findings = [compact_agent_finding(problem) for problem in available_findings[:MAX_AGENT_FINDINGS]]
    total_problems = payload.get("total_problems")
    problems_shown = payload.get("problems_shown")
    findings_truncated = len(available_findings) > len(compact_findings)
    if isinstance(total_problems, int):
        findings_truncated = findings_truncated or total_problems > len(compact_findings)
    if isinstance(problems_shown, int):
        findings_truncated = findings_truncated or problems_shown > len(compact_findings)
    proof_failures = payload.get("proof_failures")
    compact_proof_failures = [str(failure) for failure in proof_failures] if isinstance(proof_failures, list) else []
    inspection_proof = compact_inspection_proof(payload)
    identity_run_id = attribution.get("inspection_run_id") or inspection_run_id(payload)
    stage_pin_run_id = positive_run_id(identity_run_id) or inspection_run_id(payload)
    stage_diagnostic = inspection_stage_diagnostics(payload, stage_pin_run_id)
    lifecycle_outcome = lifecycle_outcome_for_payload(payload)
    diagnostic = {
        "error_reason": payload.get("error_reason"),
        "endpoint": payload.get("endpoint") or attribution.get("endpoint"),
        "http_status": payload.get("http_status") or attribution.get("http_status"),
        "response_code": payload.get("response_code"),
        "error_message": guidance_for_command(payload.get("error_message") or payload.get("error"), "agent-inspect"),
        "hint": guidance_for_command(payload.get("hint"), "agent-inspect"),
        "attribution_class": payload.get("attribution_class"),
        "failure_phase": payload.get("failure_phase"),
        "detection_phase": payload.get("detection_phase"),
        "unknown_diagnosis": payload.get("unknown_diagnosis"),
        "internal_retry_count": payload.get("internal_retry_count"),
        "internal_retry_reason": payload.get("internal_retry_reason"),
        "internal_retry_skipped": payload.get("internal_retry_skipped"),
        "prepared_internal_retry_count": payload.get("prepared_internal_retry_count"),
        "prepared_internal_retry_reason": payload.get("prepared_internal_retry_reason"),
        "python_sdk_preparation": python_sdk_preparation_for_payload(payload),
        "ide_memory": payload.get("ide_memory"),
        "original_failure_reason": payload.get("original_failure_reason") or (
            payload.get("original_failure", {}).get("error_reason")
            if isinstance(payload.get("original_failure"), dict) else None
        ),
        "unknown_log_path": payload.get("unknown_log_path"),
        "unknown_log_error": payload.get("unknown_log_error"),
        "outcome_log_path": payload.get("outcome_log_path"),
        "outcome_log_error": payload.get("outcome_log_error"),
    }
    diagnostic.update(stage_diagnostic)
    observation = native_completion_observation(payload, stage_pin_run_id)
    if observation:
        diagnostic["native_tool_completion_observation"] = observation
    attempt_diagnostics = compact_inspection_attempt_diagnostics(payload)
    if attempt_diagnostics:
        diagnostic["inspection_attempts"] = attempt_diagnostics
    result = {
        "schema_version": AGENT_RESULT_SCHEMA_VERSION,
        "command": "agent-inspect",
        "status": payload.get("status"),
        "agent_result": agent_result,
        "helper_exit_code": helper_exit_code,
        "scope": context.get("scope"),
        "selected_file_count": selected_inspection_file_count(payload),
        "finding_count": total_problems,
        "problems_shown": problems_shown,
        "findings": compact_findings,
        "findings_limit": MAX_AGENT_FINDINGS,
        "findings_truncated": findings_truncated,
        "findings_artifact": payload.get("findings_artifact"),
        "proof_failures": compact_proof_failures or None,
        "inspection_proof": inspection_proof or None,
        "inspection_outcome": inspection_outcome_for_payload(payload),
        "lifecycle_outcome": lifecycle_outcome,
        "cleanup": {
            "status": cleanup.get("status"),
            "reason": cleanup.get("reason"),
        },
        "repository_preparation": repository_preparation_for_payload(payload),
        "identity": {
            "helper_revision": attribution.get("helper_revision"),
            "ide_name": ide.get("name"),
            "ide_version": attribution.get("ide_version") or ide.get("version"),
            "ide_product_code": attribution.get("ide_product_code") or ide.get("product_code"),
            "plugin_version": attribution.get("plugin_version") or ide.get("plugin_version"),
            "plugin_build_fingerprint": attribution.get("plugin_build_fingerprint") or ide.get("plugin_build_fingerprint"),
            "inspection_run_id": identity_run_id,
        },
        "diagnostic": diagnostic,
    }
    raw_mutation_evidence = payload.get("worktree_mutation_evidence")
    if not isinstance(raw_mutation_evidence, dict):
        nested_lifecycle = dict_field(payload, "lifecycle_outcome")
        raw_mutation_evidence = nested_lifecycle.get("worktree_mutation_evidence")
    if isinstance(raw_mutation_evidence, dict) and "before_status" in raw_mutation_evidence and "after_status" in raw_mutation_evidence:
        result["worktree_mutation_evidence"] = lifecycle_outcome["worktree_mutation_evidence"]
    return public_payload(result)


def compact_multi_lane_agent_result_payload(payload: dict[str, Any], helper_exit_code: int) -> dict[str, Any]:
    apply_multi_lane_verdict(payload)
    agent_result = dict(payload.get("agent_result") or {})
    agent_result["terminal"] = agent_result.get("retry_policy", {}).get("retry") is not True
    lanes = [lane for lane in payload.get("lane_results", []) if isinstance(lane, dict)]
    compact_lanes = [bounded_inspection_lane_result(lane) for lane in lanes]
    findings: list[dict[str, Any]] = []
    total_findings = 0
    findings_truncated = False
    for lane in lanes:
        lane_count = lane.get("finding_count")
        if isinstance(lane_count, int):
            total_findings += lane_count
        lane_findings = list_field(lane, "findings")
        for finding in lane_findings:
            if not isinstance(finding, dict):
                continue
            if len(findings) >= MAX_AGENT_FINDINGS:
                findings_truncated = True
                break
            findings.append({"lane_id": lane.get("id"), **finding})
        findings_truncated = findings_truncated or lane.get("findings_truncated") is True
    result = {
        "schema_version": AGENT_RESULT_SCHEMA_VERSION,
        "lane_schema_version": INSPECTION_LANE_SCHEMA_VERSION,
        "command": "agent-inspect",
        "status": payload.get("status"),
        "agent_result": agent_result,
        "helper_exit_code": helper_exit_code,
        "scope": payload.get("lane_selection", {}).get("scope"),
        "selected_file_count": selected_inspection_file_count(payload),
        "finding_count": total_findings,
        "findings": findings,
        "findings_limit": MAX_AGENT_FINDINGS,
        "findings_truncated": findings_truncated or total_findings > len(findings),
        "selection": bounded_lane_selection(payload.get("lane_selection")),
        "lanes": compact_lanes,
        "repository_preparation": repository_preparation_for_payload(payload),
        "aggregate": {
            "verdict": payload.get("verdict"),
            "bucket": payload.get("bucket"),
            "reason": payload.get("verdict_reason"),
            "required_lane_count": payload.get("required_lane_count"),
            "lane_count": payload.get("lane_count"),
        },
        "identity": {"helper_revision": helper_revision()},
    }
    return public_payload(result)


def bounded_inspection_lane_result(lane: dict[str, Any]) -> dict[str, Any]:
    bounded = {
        key: value
        for key, value in lane.items()
        if key not in INSPECTION_STAGE_EVIDENCE_KEYS
    }
    capture_diagnostic = lane.get("capture_diagnostic")
    if isinstance(capture_diagnostic, dict):
        bounded["capture_diagnostic"] = {
            key: value
            for key, value in capture_diagnostic.items()
            if key not in INSPECTION_STAGE_EVIDENCE_KEYS
        }
    for key in (
        "inspection_failure",
        "wait",
        "raw",
        "cancellation",
        "response",
        "last_status",
    ):
        if isinstance(lane.get(key), dict):
            bounded.pop(key, None)
    raw_diagnostic = lane.get("diagnostic")
    existing_diagnostic = raw_diagnostic if isinstance(raw_diagnostic, dict) else {}
    diagnostic = {
        key: value
        for key, value in existing_diagnostic.items()
        if key not in {
            "inspection_run_id",
            *INSPECTION_STAGE_EVIDENCE_KEYS,
            *INSPECTION_DIAGNOSTIC_CHILD_KEYS,
        }
    }
    raw_evidence_ids = lane.get("evidence_ids")
    lane_evidence_ids = raw_evidence_ids if isinstance(raw_evidence_ids, dict) else {}
    lane_run_id = positive_run_id(lane_evidence_ids.get("inspection_run_id")) or inspection_run_id(lane)
    diagnostic.update(inspection_stage_diagnostics(lane, lane_run_id))
    bounded["diagnostic"] = diagnostic
    for field in ("files", "relative_files"):
        raw_values = lane.get(field)
        values = raw_values if isinstance(raw_values, list) else []
        bounded[field] = values[:MAX_LANE_FILE_PATHS]
        bounded[f"{field}_limit"] = MAX_LANE_FILE_PATHS
        bounded[f"{field}_omitted_count"] = max(0, len(values) - MAX_LANE_FILE_PATHS)
    bounded["file_count"] = int(lane.get("file_count") or len(lane.get("files") or []))
    return bounded


def bounded_lane_selection(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    bounded = dict(value)
    for field in (
        "selected_files",
        "skipped_files",
        "excluded_files",
        "explicit_exclusion_overrides",
        "unmatched_files",
    ):
        raw_values = value.get(field)
        values = raw_values if isinstance(raw_values, list) else []
        bounded[field] = values[:MAX_LANE_FILE_PATHS]
        bounded[f"{field}_count"] = len(values)
        bounded[f"{field}_limit"] = MAX_LANE_FILE_PATHS
        bounded[f"{field}_omitted_count"] = max(0, len(values) - MAX_LANE_FILE_PATHS)
    return bounded


def compact_agent_finding(problem: dict[str, Any]) -> dict[str, Any]:
    return {
        "file": problem.get("file"),
        "line": problem.get("line"),
        "column": problem.get("column"),
        "severity": problem.get("severity"),
        "inspection": problem.get("inspectionType") or problem.get("inspection") or problem.get("inspection_tool"),
        "category": problem.get("category"),
        "description": problem.get("description") or problem.get("message"),
    }


def emit(payload: dict[str, Any], json_only: bool, exit_code: int, command: str | None = None, assess: bool = True) -> int:
    if command is not None:
        payload["command"] = preferred_command(command)
    elif payload.get("command"):
        payload["command"] = preferred_command(str(payload["command"]))
    if not assess:
        payload = public_payload(payload)
        if json_only:
            print(public_json(payload))
        else:
            print_human(payload, assess=False)
        return exit_code
    apply_verdict(payload)
    log_assessment_records(payload, exit_code)
    payload = public_payload(payload)
    if json_only:
        # codeql[py/clear-text-logging-sensitive-data]
        print(public_json(payload))
        return exit_code
    print_human(payload)
    return exit_code


def print_findings_artifact(payload: dict[str, Any]) -> None:
    artifact = payload.get("findings_artifact")
    if isinstance(artifact, dict):
        print(safe_text("FINDINGS_ARTIFACT: status={status} findings={finding_count}/{expected_finding_count} path={path} reason={reason}", {
            key: artifact.get(key) for key in ("status", "finding_count", "expected_finding_count", "path", "reason")
        }))


def print_findings(problems: list[dict[str, Any]]) -> None:
    if problems:
        print("\nFINDINGS:")
        for problem in problems[:MAX_AGENT_FINDINGS]:
            location = problem.get("file") or "unknown"
            line = problem.get("line")
            if line:
                location = f"{location}:{line}"
            print(safe_text("- [{severity}] {location} {description}", {
                "severity": problem.get("severity") or "unknown",
                "location": location,
                "description": problem.get("description") or "",
            }))


def print_human(payload: dict[str, Any], assess: bool = True) -> None:
    if assess:
        apply_verdict(payload)
    lane_results = list_field(payload, "lane_results")
    if lane_results:
        print(f"VERDICT: {payload.get('verdict')} ({payload.get('verdict_reason')})")
        for lane in lane_results:
            if not isinstance(lane, dict):
                continue
            ide = dict_field(lane, "ide")
            cleanup = dict_field(lane, "cleanup")
            print(
                "LANE: "
                f"{lane.get('id')} required={str(lane.get('required')).lower()} "
                f"ide={ide.get('product') or ide.get('requested')} files={len(lane.get('files') or [])} "
                f"verdict={lane.get('verdict')} bucket={lane.get('bucket')} cleanup={cleanup.get('status')} "
                f"findings={len(lane.get('findings') or [])}/{lane.get('finding_count')} "
                f"truncated={str(lane.get('findings_truncated')).lower()}"
            )
            print_findings_artifact(lane)
            print_findings(lane.get("findings") or [])
        selection = dict_field(payload, "lane_selection")
        if selection.get("excluded_files"):
            print(f"EXCLUDED_FILES: {len(selection['excluded_files'])}")
        if selection.get("unmatched_files"):
            print(f"UNMATCHED_FILES: {len(selection['unmatched_files'])}")
        print(f"NEXT: {payload.get('verdict_next_action')}")
        return
    route = payload.get("route") or payload.get("trigger", {}).get("route") or {}
    if route:
        print(safe_text("ROUTE: {ide_name} project={project_name} project_key={project_key} base_path={base_path}", {
            "ide_name": route.get("ide", {}).get("name") or "JetBrains IDE",
            "project_name": route.get("project_name"),
            "project_key": route.get("project_key"),
            "base_path": route.get("base_path"),
        }))
    status = payload.get("status")
    if status:
        print(safe_text("STATUS: {status}", {"status": status}))
    if "stale" in payload and "removed" in payload:
        mode = "PREVIEW" if payload.get("dry_run") else "APPLIED"
        print(f"LEASE_CLEANUP: {mode} stale={len(payload['stale'])} "
              f"would_remove={len(payload.get('would_remove') or [])} removed={len(payload['removed'])} "
              f"unresolved={len(payload.get('unresolved') or [])} failed={len(payload.get('failed') or [])}")
        for item in [*(payload.get("unresolved") or []), *(payload.get("failed") or [])]:
            if isinstance(item, dict):
                next_action = item.get("next_action") or item.get("message")
                print(safe_text("LEASE_UNRESOLVED: lease_id={lease_id} reason={reason}"
                                + (" next_action={next_action}" if next_action else ""), {
                    "lease_id": item.get("lease_id"),
                    "reason": item.get("reason"),
                    "next_action": next_action,
                }))
        if payload.get("dry_run"):
            print("No leases changed. Apply with cleanup-helper-leases --no-dry-run; retain any unresolved leases.")
    print_outcome_summary(payload)
    verdict = payload.get("verdict")
    if verdict:
        print(safe_text("VERDICT: {verdict} reason={reason} message={message}", {
            "verdict": verdict,
            "reason": payload.get("verdict_reason"),
            "message": payload.get("verdict_message"),
        }))
        if payload.get("bucket"):
            print(safe_text("AGENT_RESULT: bucket={bucket} retry={retry} report={report}", {
                "bucket": payload.get("bucket"),
                "retry": (payload.get("retry_policy") or {}).get("retry"),
                "report": payload.get("agent_report"),
            }))
        if payload.get("verdict_next_action"):
            print(safe_text("NEXT_ACTION: {action}", {"action": payload.get("verdict_next_action")}))
    native_outcome = inspection_outcome_for_payload(payload)
    if native_outcome.get("status") != "not_run":
        lifecycle_outcome = lifecycle_outcome_for_payload(payload)
        print(safe_text(
            "INSPECTION_OUTCOME: status={status} verdict={verdict} freshness={freshness} findings={finding_count}",
            native_outcome,
        ))
        print(safe_text(
            "LIFECYCLE_OUTCOME: status={status} detection_phase={detection_phase} attribution={attribution}",
            lifecycle_outcome,
        ))
    if payload.get("unknown_log_path"):
        print(safe_text("UNKNOWN_LOG: {path}", {"path": payload.get("unknown_log_path")}))
    if payload.get("unknown_log_error"):
        print(safe_text("UNKNOWN_LOG_ERROR: {error}", {"error": payload.get("unknown_log_error")}))
    if payload.get("outcome_log_path"):
        print(safe_text("OUTCOME_LOG: {path}", {"path": payload.get("outcome_log_path")}))
    if payload.get("outcome_log_error"):
        print(safe_text("OUTCOME_LOG_ERROR: {error}", {"error": payload.get("outcome_log_error")}))
    if payload.get("zero_project_hint"):
        print(safe_text("PROJECT_OPEN_HINT: {hint}", {"hint": payload.get("zero_project_hint")}))
    print_ide_selection(payload.get("ide_selection") or (payload.get("context") or {}).get("ide_selection"))
    if status == "error":
        print_error_details(payload)
    print_result_flags(payload)
    print_findings_artifact(payload)
    if "total_problems" in payload or "problems_shown" in payload:
        total = payload.get("total_problems", 0)
        shown = payload.get("problems_shown", len(payload.get("problems") or []))
        clean = payload.get("clean")
        print(safe_text("SUMMARY: clean={clean} total_problems={total} problems_shown={shown}", {
            "clean": clean,
            "total": total,
            "shown": shown,
        }))
    if "cached_total_problems" in payload or "cached_problems_shown" in payload:
        total = payload.get("cached_total_problems", "unknown")
        shown = payload.get("cached_problems_shown", len(payload.get("problems") or []))
        print(safe_text("CACHED: total_problems={total} problems_shown={shown}", {"total": total, "shown": shown}))
    cleanup = payload.get("cleanup") or {}
    if cleanup:
        print(safe_text("CLEANUP: status={status} reason={reason}", {
            "status": cleanup.get("status"),
            "reason": cleanup.get("reason"),
        }))
    if payload.get("status") == "stale_results" and not payload.get("include_stale"):
        print("STALE: cached findings withheld; re-run inspection or pass --include-stale for diagnostics.")
    if payload.get("snapshot_change_kind"):
        print(safe_text("SNAPSHOT: change_kind={kind}", {"kind": payload["snapshot_change_kind"]}))
    print_semantic_coverage(payload.get("semantic_coverage"))
    print_capture_diagnostic(payload.get("capture_diagnostic"))
    wait = payload.get("wait") or {}
    if wait:
        print_capture_diagnostic(wait.get("capture_diagnostic"))
    print_findings(payload.get("problems") or [])
    if not route and not status:
        # codeql[py/clear-text-logging-sensitive-data]
        print(public_json(payload))


def print_outcome_summary(payload: dict[str, Any]) -> None:
    if payload.get("mode") == "qualification":
        print_qualification_summary(payload)
        return
    summary = payload.get("summary")
    if not isinstance(summary, dict):
        return
    print(safe_text("OUTCOMES: events={events} invalid_lines={invalid_lines} path={path}", {
        "events": payload.get("events", 0),
        "invalid_lines": payload.get("invalid_lines", 0),
        "path": payload.get("path"),
    }))
    for key, label in (
        ("by_verdict", "BY_VERDICT"),
        ("by_bucket", "BY_BUCKET"),
        ("by_command", "BY_COMMAND"),
        ("by_retry", "BY_RETRY"),
        ("by_ide_channel", "BY_IDE_CHANNEL"),
        ("by_cleanup_status", "BY_CLEANUP_STATUS"),
    ):
        counts = summary.get(key)
        if not isinstance(counts, dict) or not counts:
            continue
        rendered = " ".join(f"{name}={count}" for name, count in counts.items())
        print(f"{label}: {rendered}")
    print(safe_text("RETRYABLE_UNKNOWNS: {count}", {
        "count": summary.get("retryable_unknowns", 0),
    }))


def print_qualification_summary(payload: dict[str, Any]) -> None:
    qualification = dict_field(payload, "qualification")
    print(safe_text(
        "QUALIFICATION_GATE: status={gate_status} sample={sample_count}/{sample_size} decisive_rate={decisive_rate} remaining={remaining} hard_failures={hard_failures}",
        {
            "gate_status": payload.get("gate_status"),
            "sample_count": payload.get("sample_count", 0),
            "sample_size": qualification.get("sample_size", 0),
            "decisive_rate": payload.get("decisive_rate", 0.0),
            "remaining": payload.get("remaining_to_sample", 0),
            "hard_failures": payload.get("hard_failure_count", 0),
        },
    ))
    exclusions = payload.get("exclusion_counts")
    if isinstance(exclusions, dict) and exclusions:
        print("QUALIFICATION_EXCLUSIONS: " + " ".join(f"{reason}={count}" for reason, count in exclusions.items()))
    failures = payload.get("hard_failure_counts")
    if isinstance(failures, dict) and failures:
        print("QUALIFICATION_FAILURES: " + " ".join(f"{reason}={count}" for reason, count in failures.items()))


def print_error_details(payload: dict[str, Any]) -> None:
    details = {
        "reason": payload.get("error_reason") or payload.get("reason"),
        "message": payload.get("error_message") or payload.get("error"),
        "command": payload.get("command"),
        "exit_code": payload.get("exit_code"),
    }
    print(safe_text("ERROR: reason={reason} message={message} command={command} exit_code={exit_code}", details))
    context = payload.get("context") or {}
    route = payload.get("route") or {}
    identity = payload.get("identity") or {}
    context_details = {
        "repo": context.get("repo_path") or payload.get("repo_path"),
        "worktree": context.get("worktree_root") or payload.get("worktree_root"),
        "ide": context.get("ide") or identity.get("name") or route.get("ide", {}).get("name") or payload.get("ide"),
        "endpoint": payload.get("endpoint"),
        "url": payload.get("url"),
    }
    if any(value is not None for value in context_details.values()):
        print(safe_text("CONTEXT: repo={repo} worktree={worktree} ide={ide} endpoint={endpoint} url={url}", context_details))
    print_blocked_diagnostic(payload.get("blocked_diagnostic"))
    print_lifecycle_open_probe(payload.get("lifecycle_open_probe"))
    print_route_diagnostic(payload.get("route_diagnostic"))
    if payload.get("hint"):
        print(safe_text("HINT: {hint}", {"hint": payload.get("hint")}))
    if payload.get("next_action"):
        print(safe_text("NEXT_ACTION: {action}", {"action": payload.get("next_action")}))


def print_ide_selection(selection: Any) -> None:
    if not isinstance(selection, dict) or not selection:
        return
    print(
        safe_text(
            "IDE_SELECTION: requested={requested} product={product} mode={mode} channel={channel} version={version} app={app} config={config}",
            {
                "requested": selection.get("requested"),
                "product": selection.get("product"),
                "mode": selection.get("mode"),
                "channel": selection.get("channel"),
                "version": selection.get("version"),
                "app": selection.get("app_path") or selection.get("app_name"),
                "config": selection.get("config_dir"),
            },
        )
    )


def print_semantic_coverage(coverage: Any) -> None:
    if not isinstance(coverage, dict) or not coverage:
        return
    print(
        safe_text(
            "SEMANTIC_COVERAGE: status={status} reason={reason} missing_files={missing_files} metadata_files={metadata_files} allow_text_only={allow_text_only}",
            {
                "status": coverage.get("status"),
                "reason": coverage.get("reason"),
                "missing_files": coverage.get("missing_file_count"),
                "metadata_files": coverage.get("metadata_file_count", 0),
                "allow_text_only": coverage.get("allow_text_only_coverage", False),
            },
        )
    )
    for file in (coverage.get("files") or [])[:25]:
        if not isinstance(file, dict):
            continue
        print(
            safe_text(
                "SEMANTIC_COVERAGE_FILE: path={path} language_hint={language_hint} file_type={file_type} psi_language={psi_language} reasons={reasons}",
                {
                    "path": file.get("path"),
                    "language_hint": file.get("requested_language_hint"),
                    "file_type": file.get("file_type"),
                    "psi_language": file.get("psi_language"),
                    "reasons": file.get("reasons"),
                },
            )
        )
    for file in (coverage.get("metadata_files") or [])[:25]:
        if not isinstance(file, dict):
            continue
        print(
            safe_text(
                "SEMANTIC_COVERAGE_METADATA_FILE: classification={classification} path={path} language_hint={language_hint} file_type={file_type} psi_language={psi_language} psi_class={psi_class} coverage_required={coverage_required}",
                {
                    "classification": file.get("classification"),
                    "path": file.get("path"),
                    "language_hint": file.get("requested_language_hint"),
                    "file_type": file.get("file_type"),
                    "psi_language": file.get("psi_language"),
                    "psi_class": file.get("psi_class"),
                    "coverage_required": file.get("coverage_required"),
                },
            )
        )


def print_blocked_diagnostic(diagnostic: Any) -> None:
    if not isinstance(diagnostic, dict):
        return
    print(
        safe_text(
            "PROJECT_OPEN_BLOCKED: reason={reason} requested_ide={requested_ide} target_worktree={target_worktree} background_open={background_open} prepare_timeout_ms={prepare_timeout_ms} selected_trusted_root={selected_trusted_root}",
            {
                "reason": diagnostic.get("reason"),
                "requested_ide": diagnostic.get("requested_ide"),
                "target_worktree": diagnostic.get("target_worktree"),
                "background_open": diagnostic.get("background_open"),
                "prepare_timeout_ms": diagnostic.get("prepare_timeout_ms"),
                "selected_trusted_root": diagnostic.get("selected_trusted_root"),
            },
        )
    )
    if diagnostic.get("message"):
        print(safe_text("PROJECT_OPEN_BLOCKED_HINT: {message}", {"message": diagnostic.get("message")}))


def print_lifecycle_open_probe(probe: Any) -> None:
    if not isinstance(probe, dict):
        return
    diagnostic = probe.get("lifecycle_open_diagnostic")
    diagnostic = diagnostic if isinstance(diagnostic, dict) else {}
    print(
        safe_text(
            "LIFECYCLE_OPEN_PROBE: status={status} reason={reason} phase={phase} outcome={outcome} elapsed_ms={elapsed_ms} open_returned={open_returned} ownership_registered={ownership_registered} readiness_waiting={readiness_waiting} unresolved={unresolved}",
            {
                "status": probe.get("status"),
                "reason": probe.get("reason"),
                "phase": diagnostic.get("phase"),
                "outcome": diagnostic.get("outcome_phase"),
                "elapsed_ms": diagnostic.get("elapsed_ms"),
                "open_returned": diagnostic.get("open_returned"),
                "ownership_registered": diagnostic.get("ownership_registered"),
                "readiness_waiting": diagnostic.get("readiness_waiting"),
                "unresolved": diagnostic.get("unresolved"),
            },
        )
    )


def print_route_diagnostic(diagnostic: Any) -> None:
    if not isinstance(diagnostic, dict):
        return
    print(
        safe_text(
            "ROUTE_DIAGNOSTIC: requested_ide={requested_ide} target_worktree={target_worktree} identities={identities} matching_identities={matching_identities} projects={projects} matching_projects={matching_projects} reason={reason}",
            {
                "requested_ide": diagnostic.get("requested_ide"),
                "target_worktree": diagnostic.get("target_worktree"),
                "identities": diagnostic.get("discovered_identity_count"),
                "matching_identities": diagnostic.get("matching_identity_count"),
                "projects": diagnostic.get("discovered_project_count"),
                "matching_projects": diagnostic.get("matching_project_count"),
                "reason": diagnostic.get("reason"),
            },
        )
    )
    for identity in (diagnostic.get("identities") or [])[:5]:
        print(
            safe_text(
                "ROUTE_IDENTITY: ide={ide} product={product} port={port} projects={projects} plugin={plugin}",
                {
                    "ide": identity.get("ide_name"),
                    "product": identity.get("ide_product_code"),
                    "port": identity.get("port"),
                    "projects": identity.get("open_project_count"),
                    "plugin": plugin_identity_label(identity),
                },
            )
        )
    for project in (diagnostic.get("other_projects") or [])[:5]:
        print(
            safe_text(
                "ROUTE_OTHER_PROJECT: ide={ide} product={product} plugin={plugin} name={name} base_path={base_path}",
                {
                    "ide": project.get("ide_name"),
                    "product": project.get("ide_product_code"),
                    "name": project.get("project_name") or project.get("name"),
                    "base_path": project.get("base_path"),
                    "plugin": plugin_identity_label(project),
                },
            )
        )
    if diagnostic.get("next_action"):
        print(safe_text("ROUTE_NEXT_ACTION: {next_action}", {"next_action": diagnostic.get("next_action")}))


def public_json(payload: dict[str, Any]) -> str:
    return json.dumps(public_payload(payload), indent=2, sort_keys=True)


def safe_text(template: str, values: dict[str, Any]) -> str:
    clean_values = {key: safe_scalar(value) for key, value in public_payload(values).items()}
    return template.format(**clean_values)


def safe_scalar(value: Any) -> str:
    if value is None:
        return "None"
    if isinstance(value, (str, int, float, bool)):
        return str(value)
    return json.dumps(public_payload({"value": value})["value"], sort_keys=True)


def redact_payload(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): REDACTED if is_sensitive_key(str(key)) else redact_payload(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact_payload(item) for item in value]
    return value


def split_durable_path_suffix(value: str) -> tuple[str, str]:
    path = value
    suffix = ""
    while path and path[-1] in '.,;!?)]}':
        suffix = path[-1] + suffix
        path = path[:-1]
    line_suffix = re.fullmatch(r"(.+?)(:\d+(?::\d+)*)", path)
    if line_suffix is not None:
        path = line_suffix.group(1)
        suffix = line_suffix.group(2) + suffix
    return path, suffix


def is_durable_path_candidate(value: str) -> bool:
    lowered = value.casefold()
    if lowered.startswith(("file://", "file:/", "path:/", "~/")):
        return True
    if re.match(r"^[a-z]:[\\/]", value, re.IGNORECASE) or value.startswith("\\\\"):
        return True
    if not value.startswith("/") or value.startswith("//"):
        return False
    parts = [part for part in value.split("/") if part]
    if not parts:
        return False
    return parts[0] in DURABLE_POSIX_PATH_ROOTS


def redact_durable_path_candidate(value: str) -> str:
    path, suffix = split_durable_path_suffix(value)
    if not is_durable_path_candidate(path):
        return value
    return f"<path:{stable_value_hash(path)}>{suffix}"


def redact_ambiguous_spaced_path_tail(value: str) -> str:
    path, suffix = split_durable_path_suffix(value)
    if not any(character.isspace() for character in path):
        return value
    first_token, _, remainder = path.partition(" ")
    if not is_durable_path_candidate(first_token):
        return value
    non_posix_prefix = first_token.casefold().startswith(("file://", "file:/", "path:/", "~/")) or bool(
        re.match(r"^[a-z]:[\\/]", first_token, re.IGNORECASE)
    ) or first_token.startswith("\\\\")
    root = next((part for part in first_token.split("/") if part), "")
    likely_space_bearing_root = root in {
        "Users",
        "Volumes",
        "home",
        "media",
        "mnt",
        "private",
        "tmp",
        "var",
        "workspace",
        "workspaces",
    }
    if not non_posix_prefix and not likely_space_bearing_root and "/" not in remainder and "\\" not in remainder:
        return value
    return f"<path:{stable_value_hash(path)}>{suffix}"


def redact_durable_text(value: str) -> str:
    def replace_quoted(match: re.Match[str]) -> str:
        quote = match.group("quote")
        path = match.group("path")
        return f"{quote}{redact_durable_path_candidate(path)}{quote}"

    def replace_token(match: re.Match[str]) -> str:
        return redact_durable_path_candidate(match.group("path"))

    def replace_ambiguous_tail(match: re.Match[str]) -> str:
        return redact_ambiguous_spaced_path_tail(match.group("path"))

    sensitive_redacted = SENSITIVE_OUTPUT_BEARER_PATTERN.sub(
        lambda match: f"{match.group(1)}{REDACTED}",
        value,
    )
    sensitive_redacted = SENSITIVE_OUTPUT_ASSIGNMENT_PATTERN.sub(
        lambda match: f"{match.group(1)}{match.group(2)}{REDACTED}",
        sensitive_redacted,
    )
    quoted_redacted = DURABLE_QUOTED_PATH_PATTERN.sub(replace_quoted, sensitive_redacted)
    spaced_redacted = DURABLE_SPACED_FILE_PATH_PATTERN.sub(replace_token, quoted_redacted)
    ambiguous_redacted = DURABLE_SPACED_PATH_TAIL_PATTERN.sub(replace_ambiguous_tail, spaced_redacted)
    return DURABLE_PATH_TOKEN_PATTERN.sub(replace_token, ambiguous_redacted)


def redact_durable_log(value: Any) -> Any:
    if isinstance(value, dict):
        durable: dict[str, Any] = {}
        for key, item in value.items():
            text_key = str(key)
            lowered = text_key.lower()
            if is_sensitive_key(text_key):
                durable[text_key] = REDACTED
            elif not lowered.endswith("_hash") and (
                lowered in {
                    "path",
                    "file",
                    "directory",
                    "cwd",
                    "project_key",
                    "repo_path",
                    "worktree_root",
                    "base_path",
                    "rollout_file",
                    "scope_directory_requested",
                    "target_worktree",
                    "inspection_file",
                }
                or lowered.endswith("_path")
                or lowered.endswith("_root")
            ):
                serialized = json.dumps(item, sort_keys=True) if isinstance(item, (dict, list)) else item
                durable[f"{text_key}_hash"] = stable_value_hash(serialized)
            else:
                durable[text_key] = redact_durable_log(item)
        return durable
    if isinstance(value, list):
        return [redact_durable_log(item) for item in value]
    if isinstance(value, str):
        return redact_durable_text(value)
    return value


def is_sensitive_key(key: str) -> bool:
    lowered = key.lower()
    return any(part in lowered for part in SENSITIVE_KEY_PARTS)


def public_payload(payload: dict[str, Any]) -> dict[str, Any]:
    return redact_payload(strip_private_fields(payload))


def bounded_repository_preparation(
    value: Any,
    target_worktree: str | None = None,
) -> dict[str, Any]:
    state = value if isinstance(value, dict) else {}
    execution_state = state.get("execution_state")
    if not isinstance(execution_state, str) or not execution_state.strip():
        execution_state = REPOSITORY_PREPARATION_NOT_CONFIGURED
    execution_state = execution_state.strip()
    configured = state.get("configured") is True
    command = state.get("command") if isinstance(state.get("command"), str) else None
    if configured and command:
        command = command.strip()[:MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH]
    else:
        command = None
    result: dict[str, Any] = {
        "configured": configured,
        "command": command,
        "source": REPOSITORY_PREPARATION_SOURCE,
        "execution_state": execution_state if configured else REPOSITORY_PREPARATION_NOT_CONFIGURED,
    }
    resolved_target = target_worktree or state.get("target_worktree")
    if isinstance(resolved_target, str) and resolved_target.strip():
        result["target_worktree"] = resolved_target.strip()
    configuration_status = state.get("configuration_status")
    if isinstance(configuration_status, str) and configuration_status.strip():
        result["configuration_status"] = configuration_status.strip()
    for key in (
        "kind",
        "required_generated_state",
        "generated_state_snapshot",
        "receipt_reused",
        "receipt_path",
        "command_sha256",
        "config_sha256",
        "worktree_identity_hash",
        "duration_ms",
        "exit_status",
        "stdout",
        "stderr",
        "skip_reason",
        "authorized_opt_out",
        "failure_reason",
        "git_mutation",
        "index_mutation_detected",
    ):
        if key in state:
            result[key] = state[key]
    return result


def redact_repository_preparation_command(command: str) -> str:
    try:
        argv = shlex.split(command, posix=True)
    except ValueError:
        return redact_durable_text(command)[:MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH]

    redacted_argv: list[str] = []
    redact_next = False
    for token in argv:
        if redact_next:
            redacted_argv.append(REDACTED)
            redact_next = False
            continue
        if "=" in token:
            name, value = token.split("=", 1)
            if is_sensitive_key(name):
                redacted_argv.append(f"{name}={REDACTED}")
                continue
        if token.startswith("-") and is_sensitive_key(token):
            redacted_argv.append(token)
            redact_next = True
            continue
        redacted_argv.append(redact_durable_text(token))
    return shlex.join(redacted_argv)[:MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH]


def repository_preparation_for_payload(payload: dict[str, Any]) -> dict[str, Any]:
    candidates: list[dict[str, Any]] = [payload]
    context = payload.get("context")
    if not isinstance(context, dict):
        context = {}
    candidates.append(context)
    prepared = payload.get("prepared")
    if not isinstance(prepared, dict):
        prepared = {}
    candidates.append(prepared)
    failure = payload.get("inspection_failure")
    if not isinstance(failure, dict):
        failure = {}
    candidates.append(failure)
    for candidate in candidates:
        state = candidate.get("repository_preparation")
        if isinstance(state, dict):
            target = state.get("target_worktree")
            if not isinstance(target, str) or not target.strip():
                target = candidate.get("lifecycle_target_path") or candidate.get("project_path") or candidate.get("worktree_root")
            return bounded_repository_preparation(state, target_worktree=target if isinstance(target, str) else None)
    target = context.get("lifecycle_target_path") or context.get("project_path") or context.get("worktree_root")
    return bounded_repository_preparation({}, target_worktree=target if isinstance(target, str) else None)


def prepared_python_sdk_discovery_pending(payload: dict[str, Any]) -> bool:
    preparation = repository_preparation_for_payload(payload)
    if preparation.get("execution_state") not in {
        REPOSITORY_PREPARATION_SUCCEEDED,
        REPOSITORY_PREPARATION_REUSED,
    }:
        return False
    generated = preparation.get("generated_state_snapshot")
    if not isinstance(generated, dict) or generated.get("all_present") is not True:
        return False
    paths = generated.get("paths")
    if not isinstance(paths, list):
        return False
    target_worktree = preparation.get("target_worktree")
    if not isinstance(target_worktree, str) or not target_worktree.strip():
        return False
    target = Path(target_worktree).expanduser().resolve()
    project = target
    context = dict_field(payload, "context")
    for candidate in (payload, context):
        project_value = candidate.get("project_path") or candidate.get("lifecycle_target_path")
        if isinstance(project_value, str) and project_value.strip():
            project = Path(project_value).expanduser().resolve()
            break
    if project != target and not project.is_relative_to(target):
        return False
    return any(
        isinstance(item, dict)
        and item.get("exists") is True
        and item.get("kind") == "directory"
        and isinstance(item.get("path"), str)
        and (target / item["path"]).resolve().name == ".venv"
        and (target / item["path"]).resolve().parent == project
        for item in paths
    )


def durable_repository_preparation_for_payload(payload: dict[str, Any]) -> dict[str, Any]:
    preparation = repository_preparation_for_payload(payload)
    command = preparation.get("command")
    if isinstance(command, str) and command:
        preparation["command"] = redact_repository_preparation_command(command)
    return preparation


def durable_repository_preparation_text(value: Any, payload: dict[str, Any]) -> Any:
    if not isinstance(value, str) or not value:
        return value
    command = repository_preparation_for_payload(payload).get("command")
    if not isinstance(command, str) or not command:
        return value
    return value.replace(command, redact_repository_preparation_command(command))


def repository_preparation_action(
    payload: dict[str, Any],
    *,
    rerun_action: str = "then rerun inspection.",
) -> str | None:
    preparation = repository_preparation_for_payload(payload)
    if preparation.get("configured") is not True or preparation.get("execution_state") != REPOSITORY_PREPARATION_NOT_RUN:
        return None
    command = preparation.get("command")
    target = preparation.get("target_worktree")
    if not isinstance(command, str) or not command:
        return None
    if isinstance(target, str) and target:
        return (
            f"Run the configured repository preparation command `{command}` in the exact target worktree `{target}`, "
            f"{rerun_action}"
        )
    return f"Run the configured repository preparation command `{command}`, {rerun_action}"


def language_sdk_missing_next_action(payload: dict[str, Any]) -> str:
    preparation_action = repository_preparation_action(
        payload,
        rerun_action=(
            "then run one fresh `agent-inspect` assessment after the prerequisite changes. "
            "Do not repeat the failed assessment unchanged."
        ),
    )
    if preparation_action:
        return preparation_action
    return (
        "Use the exact worktree's documented language/SDK setup to restore the missing language SDK and configure the "
        "resulting SDK for the selected files in the current IDE project. For Python, use the repository's documented Python "
        "setup; do not guess a Python version. Do not commit IDE configuration. Then run one fresh `agent-inspect` assessment. "
        "Do not repeat the failed assessment unchanged."
        " If the documented setup is absent, ambiguous, or requires global/system changes, report that blocker instead."
    )


def public_context(context: dict[str, Any]) -> dict[str, Any]:
    public = dict(context)
    return public


def strip_private_fields(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): strip_private_fields(item)
            for key, item in value.items()
            if not str(key).startswith("_")
        }
    if isinstance(value, list):
        return [strip_private_fields(item) for item in value]
    return value


def print_capture_diagnostic(diagnostic: Any) -> None:
    if not isinstance(diagnostic, dict) or not diagnostic:
        return
    parts: list[str] = []
    for key in (
        "exit_reason",
        "view_ready_ok",
        "observed_inspection_view",
        "inspection_view_updating",
        "observed_settled_empty_inspection_view",
        "observed_stable_readable_empty_inspection_view",
        "observed_stable_empty_results_without_inspection_view",
        "successful_extraction_count",
        "extraction_failure_count",
        "polling_elapsed_ms",
    ):
        if key in diagnostic:
            parts.append(f"{key}={diagnostic[key]}")
    if parts:
        # codeql[py/clear-text-logging-sensitive-data]
        print(f"CAPTURE_DIAGNOSTIC: {' '.join(parts)}")


def print_result_flags(payload: dict[str, Any]) -> None:
    flags: list[str] = []
    if payload.get("capture_incomplete"):
        flags.append("capture_incomplete")
    if payload.get("results_may_be_stale"):
        flags.append("results_may_be_stale")
    wait = payload.get("wait") or {}
    if wait.get("timed_out"):
        flags.append("timed_out")
    if payload.get("timed_out"):
        flags.append("timed_out")
    if wait.get("capture_incomplete"):
        flags.append("wait_capture_incomplete")
    if wait.get("results_may_be_stale"):
        flags.append("wait_results_may_be_stale")
    if payload.get("cleanup_failed"):
        flags.append("cleanup_failed")
    if payload.get("cleanup_skipped"):
        flags.append("cleanup_skipped")
    if flags:
        print(f"FLAGS: {', '.join(flags)}")


def git_root(path: Path) -> Path | None:
    try:
        output = subprocess.check_output(["git", "-C", str(path), "rev-parse", "--show-toplevel"], text=True, stderr=subprocess.DEVNULL)
        return Path(output.strip()).resolve()
    except subprocess.CalledProcessError:
        return None


def git_head_sha(path: Path) -> str | None:
    try:
        output = subprocess.check_output(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None
    return output if re.fullmatch(r"[0-9a-fA-F]{40,64}", output) else None


def git_worktree_status_snapshot(path_value: Any) -> dict[str, Any]:
    if path_value in (None, ""):
        return {"status": "unavailable", "reason": "worktree_path_missing", "entries": {}}
    try:
        path = Path(str(path_value)).expanduser().resolve()
        completed = subprocess.run(
            ["git", "-C", str(path), "status", "--porcelain=v1", "-z", "--untracked-files=all"],
            capture_output=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return {
            "status": "unavailable",
            "reason": error.__class__.__name__,
            "entries": {},
        }
    if completed.returncode != 0:
        return {
            "status": "unavailable",
            "reason": "git_status_failed",
            "entries": {},
        }
    entries = parse_git_porcelain_z(completed.stdout)
    index_sha256 = git_index_sha256(path)
    tracked_content_sha256: dict[str, str] = {}
    for relative_path, status in entries.items():
        if status.startswith("??"):
            continue
        tracked_path = path / relative_path
        try:
            digest = hashlib.sha256(tracked_path.read_bytes()).hexdigest() if tracked_path.is_file() else "missing"
        except OSError:
            digest = "unreadable"
        tracked_content_sha256[relative_path] = f"sha256:{digest}"
    return {
        "status": "ok",
        "entries": entries,
        "index_sha256": index_sha256,
        "head_sha": git_head_sha(path),
        "tracked_content_sha256": tracked_content_sha256,
    }


def git_index_sha256(path: Path) -> str | None:
    try:
        index_path = subprocess.check_output(
            ["git", "-C", str(path), "rev-parse", "--git-path", "index"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
        resolved = Path(index_path)
        if not resolved.is_absolute():
            resolved = path / resolved
        return "sha256:" + hashlib.sha256(resolved.resolve().read_bytes()).hexdigest()
    except (OSError, subprocess.CalledProcessError):
        return None


def parse_git_porcelain_z(output: bytes) -> dict[str, str]:
    records = output.split(b"\0")
    entries: dict[str, str] = {}
    index = 0
    while index < len(records):
        record = records[index]
        index += 1
        if len(record) < 4 or record[2:3] != b" ":
            continue
        status = record[:2].decode("ascii", errors="replace")
        path = record[3:].decode("utf-8", errors="surrogateescape")
        entries[path] = status
        if "R" in status or "C" in status:
            index += 1
    return entries


def summarize_worktree_mutations(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    before_entries = before.get("entries")
    if not isinstance(before_entries, dict):
        before_entries = {}
    after_entries = after.get("entries")
    if not isinstance(after_entries, dict):
        after_entries = {}
    status_changed_paths = {
        path
        for path, status in after_entries.items()
        if before_entries.get(path) != status
    }
    removed_paths = {path for path in before_entries if path not in after_entries}
    before_content = before.get("tracked_content_sha256")
    if not isinstance(before_content, dict):
        before_content = {}
    after_content = after.get("tracked_content_sha256")
    if not isinstance(after_content, dict):
        after_content = {}
    tracked_content_changed_paths = {
        path
        for path in set(before_content) | set(after_content)
        if before_content.get(path) != after_content.get(path)
    }
    changed_paths = sorted(status_changed_paths | tracked_content_changed_paths)
    removed_paths = sorted(removed_paths)
    emitted_paths = changed_paths[:MAX_WORKTREE_MUTATION_PATHS]
    emitted_removed_paths = removed_paths[:MAX_WORKTREE_MUTATION_PATHS]
    emitted_content_paths = sorted(tracked_content_changed_paths)[:MAX_WORKTREE_MUTATION_PATHS]

    return {
        "schema_version": 1,
        "before_status": before.get("status"),
        "after_status": after.get("status"),
        "settle_delay_ms": after.get("settle_delay_ms", 0),
        "dirty_before": bool(before_entries),
        "dirty_after": bool(after_entries),
        "new_or_changed_path_count": len(changed_paths),
        "removed_path_count": len(removed_paths),
        "paths_limit": MAX_WORKTREE_MUTATION_PATHS,
        "paths_omitted_count": max(0, len(changed_paths) - len(emitted_paths)),
        "new_or_changed_paths": emitted_paths,
        "removed_paths_omitted_count": max(0, len(removed_paths) - len(emitted_removed_paths)),
        "removed_paths": emitted_removed_paths,
        "tracked_change_count": sum(
            1
            for path in changed_paths
            if not after_entries.get(path, before_entries.get(path, "")).startswith("??")
        ),
        "untracked_change_count": sum(
            1 for path in changed_paths if after_entries.get(path, before_entries.get(path, "")).startswith("??")
        ),
        "tracked_content_changed_paths": emitted_content_paths,
        "tracked_content_changed_path_count": len(tracked_content_changed_paths),
        "tracked_content_changed_paths_omitted_count": max(0, len(tracked_content_changed_paths) - len(emitted_content_paths)),
    }


def post_cleanup_worktree_status_snapshot(context: dict[str, Any]) -> dict[str, Any]:
    settle_delay_ms = LANE_MUTATION_SETTLE_DELAY_MS if context.get("inspection_lane") else 0
    if settle_delay_ms:
        time.sleep(settle_delay_ms / 1000.0)
    snapshot = git_worktree_status_snapshot(context.get("worktree_root"))
    snapshot["settle_delay_ms"] = settle_delay_ms
    return snapshot


def apply_worktree_mutation_blocker(result: dict[str, Any]) -> None:
    evidence = result.get("worktree_mutation_evidence")
    if not isinstance(evidence, dict):
        return
    changed_count = int(evidence.get("new_or_changed_path_count") or 0)
    removed_count = int(evidence.get("removed_path_count") or 0)
    if changed_count == 0 and removed_count == 0:
        return
    result.update(
        {
            "status": "error",
            "error_reason": "worktree_mutation_detected",
            "error_message": "The worktree changed during the inspection lifecycle; the writing process was not identified.",
            "worktree_mutation_detected": True,
            "detection_phase": "post_run_verification",
            "lifecycle_outcome": {
                "status": "mutated",
                "error_reason": "worktree_mutation_detected",
                "detection_phase": "post_run_verification",
                "attribution": "unattributed",
                "worktree_mutation_evidence": evidence,
            },
        }
    )


def git_common_worktree(path: Path) -> Path | None:
    try:
        common = subprocess.check_output(["git", "-C", str(path), "rev-parse", "--git-common-dir"], text=True, stderr=subprocess.DEVNULL).strip()
        common_path = Path(common)
        if not common_path.is_absolute():
            common_path = (path / common_path).resolve()
        if common_path.name == ".git":
            return common_path.parent.resolve()
        return None
    except subprocess.CalledProcessError:
        return None


def read_repo_config(worktree_root: Path) -> dict[str, Any]:
    config_path = worktree_root / ".github" / "github.json"
    if not config_path.exists():
        return {}
    try:
        return json.loads(config_path.read_text())
    except json.JSONDecodeError as error:
        raise InspectError(f"Invalid JSON in {config_path}: {error}", 2)


def global_config_path() -> Path:
    override = os.environ.get("JETBRAINS_INSPECTION_GLOBAL_CONFIG")
    if override:
        return Path(override).expanduser()
    shared_config = Path.home() / ".config" / "jetbrains-inspection" / "config.json"
    if shared_config.exists():
        return shared_config
    code_home = os.environ.get("CODE_HOME") or os.environ.get("CODEX_HOME") or str(Path.home() / ".code")
    legacy_config = Path(code_home).expanduser() / "jetbrains-inspection.json"
    return legacy_config if legacy_config.exists() else shared_config


def read_global_config() -> dict[str, Any]:
    path = global_config_path()
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise InspectError(f"Invalid JSON in {path}: {error}", 2)
    if not isinstance(value, dict):
        raise InspectError(f"Global inspection config must be a JSON object: {path}", 2)
    return value


def trusted_auto_open_roots() -> list[str]:
    env = os.environ.get("JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS")
    if env:
        raw_roots = [part for part in env.split(os.pathsep) if part.strip()]
    else:
        config = read_global_config()
        jetbrains = config.get("jetbrains", {}) if isinstance(config.get("jetbrains"), dict) else {}
        raw_roots = (
            jetbrains.get("trustedAutoOpenRoots")
            or jetbrains.get("trusted_auto_open_roots")
            or config.get("trustedAutoOpenRoots")
            or config.get("trusted_auto_open_roots")
        )
    if not raw_roots:
        return []
    if isinstance(raw_roots, str):
        raw_roots = [raw_roots]
    roots: list[str] = []
    if isinstance(raw_roots, list):
        for root in raw_roots:
            try:
                roots.append(str(Path(str(root)).expanduser().resolve()))
            except OSError:
                continue
    return roots


def ensure_trusted_auto_open_root(context: dict[str, Any]) -> None:
    worktree = lifecycle_target_path(context)
    roots = trusted_auto_open_roots()
    if not worktree:
        raise InspectError("Cannot auto-open IDE because the worktree path is unknown.", 3)
    if not roots:
        raise InspectError(
            "Exact worktree is not open and no trusted auto-open roots are configured.",
            3,
            {
                "worktree_root": worktree,
                "lifecycle_target_path": lifecycle_target_path(context),
                "global_config": str(global_config_path()),
                "hint": "Add jetbrains.trustedAutoOpenRoots to the global inspection config, or open/trust the worktree manually once.",
            },
        )
    worktree_path = Path(str(worktree)).expanduser().resolve()
    trusted = []
    for root in roots:
        root_path = Path(str(root)).expanduser().resolve()
        trusted.append(str(root_path))
        if worktree_path == root_path or worktree_path.is_relative_to(root_path):
            return
    raise InspectError(
        "Exact worktree is not open and is outside trusted auto-open roots.",
        3,
        {
            "worktree_root": str(worktree_path),
            "lifecycle_target_path": lifecycle_target_path(context),
            "trusted_auto_open_root_count": len(trusted),
            "global_config": str(global_config_path()),
            "hint": "Move the worktree under a trusted root, add a trusted root globally, or open/trust the project manually once.",
        },
    )


def ensure_jetbrains_trusted_locations(context: dict[str, Any]) -> dict[str, Any]:
    if sys.platform != "darwin":
        return {"status": "skipped", "reason": "unsupported_platform"}
    worktree = lifecycle_target_path(context)
    if not worktree:
        raise InspectError("Cannot seed JetBrains trusted locations because the worktree path is unknown.", 3)
    trust_root = trusted_root_for_worktree(context)
    config_dirs = jetbrains_config_dirs(context)
    if not config_dirs:
        raise InspectError(
            "Cannot seed JetBrains trusted locations because no matching IDE config directory was found.",
            3,
            {
                "ide": context.get("ide"),
                "worktree_root": str(Path(str(worktree)).expanduser().resolve()),
                "lifecycle_target_path": lifecycle_target_path(context),
                "hint": "Launch the target JetBrains IDE once, install the inspection plugin, or set jetbrains.ide to an installed app name.",
            },
        )
    results = []
    for config_dir in config_dirs:
        results.append(
            {
                "config_dir": str(config_dir),
                "trusted_locations": ensure_trusted_location_file(config_dir, trust_root),
                "project_opening": ensure_project_opening_policy(config_dir),
            }
        )
    return {
        "status": "trusted",
        "path": trust_path_token(trust_root),
        "config_updates": results,
    }


def trusted_root_for_worktree(context: dict[str, Any]) -> Path:
    worktree_path = Path(str(lifecycle_target_path(context))).expanduser().resolve()
    matches: list[Path] = []
    for root in trusted_auto_open_roots():
        root_path = Path(str(root)).expanduser().resolve()
        if worktree_path == root_path or worktree_path.is_relative_to(root_path):
            matches.append(root_path)
    if not matches:
        raise InspectError("Worktree is outside trusted auto-open roots.", 3, {"worktree_root": str(worktree_path)})
    return sorted(matches, key=lambda path: len(path.parts), reverse=True)[0]


def jetbrains_config_dirs(context: dict[str, Any]) -> list[Path]:
    override = os.environ.get("JETBRAINS_INSPECTION_IDE_CONFIG_DIR")
    if override:
        return [Path(override).expanduser().resolve()]
    if sys.platform != "darwin":
        return []
    configured_dir = context.get("ide_config_dir")
    if configured_dir:
        path = Path(str(configured_dir)).expanduser().resolve()
        if path.exists():
            return [path]
        advice = ide_selection_advice("ide_config_missing", context)
        raise InspectError(
            "Cannot seed JetBrains trusted locations because the configured IDE directory is unavailable.",
            3,
            {"available_config_dirs": [], "error_reason": "ide_config_missing",
             "context": public_context(context), "next_action": advice, "hint": advice},
        )
    selection = resolve_ide_selection(context)
    if selection and selection.config_dir:
        return [selection.config_dir]
    candidates = discover_ide_config_candidates()
    candidate_paths = [candidate.path for candidate in candidates if candidate.path]
    ide = str(context.get("ide") or "")
    if ide:
        product = product_for_selector(ide)
        channel = normalize_ide_channel(context.get("ide_channel"))
        version = parse_version_tuple(clean_optional(context.get("ide_version")))
        match = select_ide_candidate(candidates, product, ide, channel, version, bool(version or channel == "eap" or selector_contains_exact_marker(ide)))
        if match and match.path:
            return [match.path]
        available = [candidate.name for candidate in sorted(candidates, key=lambda item: item.name)]
        raise InspectError(
            "Cannot seed JetBrains trusted locations because no installed IDE config matched the requested IDE.",
            3,
            {
                "ide": context.get("ide"),
                "ide_selection": selection.public() if selection else None,
                "available_config_dirs": available,
                "error_reason": "ide_config_missing",
                "context": public_context(context),
                "next_action": ide_selection_advice("ide_config_missing", context) or "Launch the selected JetBrains IDE once, or update .github/github.json to name an installed JetBrains IDE/version.",
                "hint": ide_selection_advice("ide_config_missing", context) or "Use product-level metadata such as jetbrains.ide = WebStorm for latest stable. EAP requires explicit metadata such as jetbrains.ideChannel = eap and jetbrains.ideVersion = 2026.2.",
                "matched_product": product.display_name if product else None,
            },
        )
    if len(candidate_paths) == 1:
        return candidate_paths
    if not candidate_paths:
        reason = "ide_config_missing"
        advice = ide_selection_advice(reason, context) or hint_for_error_reason(reason, context)
        raise InspectError(
            "Cannot seed JetBrains trusted locations because no installed IDE config directories exist.",
            3,
            {"available_config_dirs": [], "error_reason": reason,
             "context": public_context(context), "next_action": advice, "hint": advice},
        )
    raise InspectError(
        "Cannot seed JetBrains trusted locations because multiple IDE config directories exist and no IDE was selected.",
        3,
        {
            "available_config_dirs": [candidate.name for candidate in sorted(candidates, key=lambda item: item.name)],
            "error_reason": "ide_selection_required",
            "context": public_context(context),
            "next_action": ide_selection_advice("ide_selection_required", context) or "Add preferred JetBrains IDE metadata to .github/github.json, for example jetbrains.ide = WebStorm, PyCharm, or IntelliJ IDEA. Use --ide only for a one-off run.",
            "hint": ide_selection_advice("ide_selection_required", context) or "Set jetbrains.ide in repo metadata so the helper updates the intended JetBrains product instead of guessing across installed IDEs.",
        },
    )


def ensure_trusted_location_file(config_dir: Path, trust_root: Path) -> dict[str, Any]:
    options_dir = config_dir / "options"
    options_dir.mkdir(parents=True, exist_ok=True)
    path = options_dir / "trusted-paths.xml"
    token = trust_path_token(trust_root)
    created = not path.exists()
    if path.exists():
        tree = ElementTree.parse(path)
        root = tree.getroot()
    else:
        root = ElementTree.Element("application")
        tree = ElementTree.ElementTree(root)
    trusted_settings = ensure_component(root, "Trusted.Paths.Settings")
    trusted_option = ensure_option(trusted_settings, "TRUSTED_PATHS")
    trusted_list = ensure_child(trusted_option, "list")
    changed = ensure_list_option(trusted_list, token)
    trusted_projects = ensure_component(root, "Trusted.Paths")
    projects_option = ensure_option(trusted_projects, "TRUSTED_PROJECT_PATHS")
    projects_map = ensure_child(projects_option, "map")
    changed = ensure_map_entry(projects_map, token, "true") or changed
    if changed or created:
        backup = backup_file(path) if path.exists() else None
        indent_xml(root)
        tree.write(path, encoding="utf-8", xml_declaration=False)
        return {"path": str(path), "changed": True, "created": created, "backup": str(backup) if backup else None}
    return {"path": str(path), "changed": False, "created": False}


def ensure_project_opening_policy(config_dir: Path) -> dict[str, Any]:
    options_dir = config_dir / "options"
    options_dir.mkdir(parents=True, exist_ok=True)
    path = options_dir / "ide.general.xml"
    created = not path.exists()
    if path.exists():
        tree = ElementTree.parse(path)
        root = tree.getroot()
    else:
        root = ElementTree.Element("application")
        tree = ElementTree.ElementTree(root)
    settings = ensure_component(root, "GeneralSettings")
    option = ensure_option(settings, "confirmOpenNewProject2")
    changed = option.get("value") != "-1"
    if changed:
        option.set("value", "-1")
    if changed or created:
        backup = backup_file(path) if path.exists() else None
        indent_xml(root)
        tree.write(path, encoding="utf-8", xml_declaration=False)
        return {"path": str(path), "changed": True, "created": created, "backup": str(backup) if backup else None}
    return {"path": str(path), "changed": False, "created": False}


def trust_path_token(path: Path) -> str:
    home = Path.home().resolve()
    resolved = path.expanduser().resolve()
    if resolved == home:
        return "$USER_HOME$"
    if resolved.is_relative_to(home):
        return "$USER_HOME$/" + str(resolved.relative_to(home))
    return str(resolved)


def ensure_component(root: ElementTree.Element, name: str) -> ElementTree.Element:
    for child in root.findall("component"):
        if child.get("name") == name:
            return child
    return ElementTree.SubElement(root, "component", {"name": name})


def ensure_option(parent: ElementTree.Element, name: str) -> ElementTree.Element:
    for child in parent.findall("option"):
        if child.get("name") == name:
            return child
    return ElementTree.SubElement(parent, "option", {"name": name})


def ensure_child(parent: ElementTree.Element, tag: str) -> ElementTree.Element:
    child = parent.find(tag)
    if child is not None:
        return child
    return ElementTree.SubElement(parent, tag)


def ensure_list_option(parent: ElementTree.Element, value: str) -> bool:
    for child in parent.findall("option"):
        if child.get("value") == value:
            return False
    ElementTree.SubElement(parent, "option", {"value": value})
    return True


def ensure_map_entry(parent: ElementTree.Element, key: str, value: str) -> bool:
    for child in parent.findall("entry"):
        if child.get("key") == key:
            if child.get("value") == value:
                return False
            child.set("value", value)
            return True
    ElementTree.SubElement(parent, "entry", {"key": key, "value": value})
    return True


def backup_file(path: Path) -> Path:
    backup = path.with_suffix(path.suffix + f".bak-{now_ms()}")
    shutil.copy2(path, backup)
    return backup


def indent_xml(element: ElementTree.Element, level: int = 0) -> None:
    indent = "\n" + level * "  "
    child_indent = "\n" + (level + 1) * "  "
    children = list(element)
    if children:
        if not element.text or not element.text.strip():
            element.text = child_indent
        for child in children:
            indent_xml(child, level + 1)
        if not element.tail or not element.tail.strip():
            element.tail = indent
    elif level and (not element.tail or not element.tail.strip()):
        element.tail = indent


def resolve_config_path(value: Any, base: Path) -> Path:
    path = Path(str(value)).expanduser()
    if not path.is_absolute():
        path = base / path
    return path.resolve()


def first_scope(value: Any) -> str | None:
    if isinstance(value, list):
        return str(value[0]) if value else None
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None


def registry_dir() -> Path:
    override = os.environ.get("JETBRAINS_INSPECTION_REGISTRY_DIR")
    if override:
        return Path(override).expanduser()
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Caches" / "jetbrains-inspection-api" / "instances"
    return Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "jetbrains-inspection-api" / "instances"


def cache_dir() -> Path:
    override = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
    if override:
        return Path(override).expanduser()
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Caches" / "jetbrains-inspection-api"
    return Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "jetbrains-inspection-api"


def lease_dir() -> Path:
    return cache_dir() / "leases"


def lifecycle_lock_path() -> Path:
    return cache_dir() / "lifecycle.lock"


def outcome_routing_lock_path() -> Path:
    return cache_dir() / "outcome-routing.lock"


class outcome_routing_lock:
    def __init__(self, timeout_ms: int = DEFAULT_OUTCOME_ROUTING_LOCK_TIMEOUT_MS):
        self.timeout_ms = max(0, int(timeout_ms))
        self.handle = None

    def __enter__(self):
        path = outcome_routing_lock_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = path.open("a+", encoding="utf-8")
        if fcntl is not None:
            deadline = time.monotonic() + (self.timeout_ms / 1000.0)
            while True:
                try:
                    fcntl.flock(self.handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError as error:
                    if self.timeout_ms == 0 or time.monotonic() >= deadline:
                        self.handle.close()
                        raise TimeoutError(
                            f"Timed out waiting for the JetBrains inspection outcome routing lock: {path}"
                        ) from error
                    time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        elif msvcrt is not None:
            self.handle.seek(0, os.SEEK_END)
            if self.handle.tell() == 0:
                self.handle.write("\0")
                self.handle.flush()
            deadline = time.monotonic() + (self.timeout_ms / 1000.0)
            while True:
                try:
                    self.handle.seek(0)
                    msvcrt.locking(self.handle.fileno(), msvcrt.LK_NBLCK, 1)
                    break
                except OSError as error:
                    if self.timeout_ms == 0 or time.monotonic() >= deadline:
                        self.handle.close()
                        raise TimeoutError(
                            f"Timed out waiting for the JetBrains inspection outcome routing lock: {path}"
                        ) from error
                    time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
        else:
            self.handle.close()
            raise OSError("Unsupported platform for JetBrains inspection outcome routing locking")
        return self

    def __exit__(self, exc_type, exc, tb):
        if fcntl is not None and self.handle is not None:
            fcntl.flock(self.handle.fileno(), fcntl.LOCK_UN)
        elif msvcrt is not None and self.handle is not None:
            self.handle.seek(0)
            msvcrt.locking(self.handle.fileno(), msvcrt.LK_UNLCK, 1)
        if self.handle is not None:
            self.handle.close()


LIFECYCLE_QUEUE_DIRNAME = "lifecycle-queue"
LIFECYCLE_TICKET_GLOB = "*.ticket"


class fifo_turn:
    """Hold one kernel-locked FIFO ticket in `directory` until exit."""

    def __init__(self, directory: Path, label: str = "Inspection lifecycle", on_wait=None):
        self.directory = directory
        self.label = label
        self.on_wait = on_wait
        self.ticket = None
        self.ticket_path = None
        self.queued_at = None
        self.waited_ms = 0

    @staticmethod
    def try_lock(handle):
        try:
            if fcntl is not None:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            elif msvcrt is not None:
                handle.seek(0, os.SEEK_END)
                if handle.tell() == 0:
                    handle.write("\0")
                    handle.flush()
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                raise OSError("Unsupported platform for lifecycle file locking")
        except BlockingIOError:
            return False
        except OSError as error:
            if error.errno in {errno.EACCES, errno.EAGAIN, errno.EDEADLK}:
                return False
            raise
        return True

    @staticmethod
    def unlock(handle):
        if fcntl is not None:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        elif msvcrt is not None:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)

    def _register_lock(self, gate):
        while not self.try_lock(gate):
            time.sleep(0.05)

    def __enter__(self):
        try:
            self._queue_turn()
        except BaseException:
            self._release_ticket()
            raise
        return self

    def __exit__(self, exc_type, exc, tb):
        self._release_ticket()

    def _queue_turn(self):
        directory = self.directory
        directory.mkdir(parents=True, exist_ok=True)
        registration = directory / "registration.lock"
        self.queued_at = time.monotonic()
        with registration.open("a+", encoding="utf-8") as gate:
            self._register_lock(gate)
            try:
                tickets = list(directory.glob(LIFECYCLE_TICKET_GLOB))
                sequence = max((int(path.stem) for path in tickets), default=0) + 1
                self.ticket_path = directory / f"{sequence:020d}.ticket"
                self.ticket = self.ticket_path.open("x+", encoding="utf-8")
                if not self.try_lock(self.ticket):
                    raise OSError("New lifecycle ticket is already locked")
                if tickets:
                    print(f"{self.label} queued behind {len(tickets)} caller(s).", file=sys.stderr, flush=True)
            finally:
                self.unlock(gate)
        while True:
            with registration.open("a+", encoding="utf-8") as gate:
                self._register_lock(gate)
                try:
                    predecessor = next((path for path in sorted(directory.glob(LIFECYCLE_TICKET_GLOB))
                                        if path < self.ticket_path), None)
                    if predecessor is None:
                        self.waited_ms = round((time.monotonic() - self.queued_at) * 1000)
                        return
                    with predecessor.open("r+", encoding="utf-8") as holder:
                        abandoned = self.try_lock(holder)
                    if abandoned:
                        # Kernel ownership, rather than a PID or age, proves an abandoned ticket.
                        predecessor.unlink()
                finally:
                    self.unlock(gate)
            if self.on_wait is not None:
                self.on_wait()
            time.sleep(0.05)

    def _release_ticket(self):
        ticket_path = self.ticket_path
        if self.ticket is not None and ticket_path is not None:
            with ticket_path.with_name("registration.lock").open("a+", encoding="utf-8") as gate:
                self._register_lock(gate)
                try:
                    self.ticket.close()
                    self.ticket = None
                    ticket_path.unlink(missing_ok=True)
                finally:
                    self.unlock(gate)


class lifecycle_lock:
    try_lock = staticmethod(fifo_turn.try_lock)
    unlock = staticmethod(fifo_turn.unlock)

    def __init__(self, timeout_ms: int = DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS):
        self.timeout_ms = max(0, int(timeout_ms))
        self.handle = None
        self.turn = None
        self.waited_ms = 0

    def __enter__(self):
        path = lifecycle_lock_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = path.open("a+", encoding="utf-8")
        if fcntl is not None or msvcrt is not None:
            self.turn = fifo_turn(path.with_name(LIFECYCLE_QUEUE_DIRNAME))
            try:
                self.turn.__enter__()
                self.waited_ms = self.turn.waited_ms
                deadline = time.monotonic() + (self.timeout_ms / 1000.0)
                while True:
                    if self.try_lock(self.handle):
                        break
                    if self.timeout_ms == 0 or time.monotonic() >= deadline:
                        raise InspectError(
                            "Timed out waiting for the JetBrains inspection lifecycle lock.",
                            3,
                            {
                                "error_reason": "lifecycle_lock_timeout",
                                "lock_path": str(path),
                                "timeout_ms": self.timeout_ms,
                                "hint": "A caller using the older lifecycle lock is still running. Wait for it to finish or increase --lifecycle-lock-timeout-ms.",
                            },
                        )
                    time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
            except BaseException:
                self.handle.close()
                self.turn.__exit__(None, None, None)
                raise
        else:
            self.handle.close()
            raise InspectError("Lifecycle serialization is unavailable on this platform.", 3)
        return self

    def __exit__(self, exc_type, exc, tb):
        if self.handle is not None:
            self.unlock(self.handle)
        if self.handle is not None:
            self.handle.close()
        if self.turn is not None:
            self.turn.__exit__(exc_type, exc, tb)


def now_ms() -> int:
    return int(time.time() * 1000)


def monotonic_ms() -> int:
    return int(time.monotonic() * 1000)


def create_local_lease(context: dict[str, Any], state: str) -> dict[str, Any]:
    lease = {
        "lease_id": str(uuid.uuid4()),
        "client_run_id": context.get("client_run_id"),
        "state": state,
        "repo_path": context.get("repo_path"),
        "worktree_root": context.get("worktree_root"),
        "lifecycle_target_path": lifecycle_target_path(context),
        "created_at_ms": now_ms(),
        "updated_at_ms": now_ms(),
        "pid": os.getpid(),
    }
    write_lease(lease)
    return lease


def lease_path(lease: dict[str, Any]) -> Path:
    return lease_dir() / f"{lease['lease_id']}.json"


def write_lease(lease: dict[str, Any]) -> None:
    directory = lease_dir()
    directory.mkdir(parents=True, exist_ok=True)
    lease["updated_at_ms"] = now_ms()
    path = lease_path(lease)
    temp = path.with_suffix(".json.tmp")
    # codeql[py/clear-text-storage-sensitive-data]
    temp.write_text(public_json(public_lease(lease)), encoding="utf-8")
    temp.replace(path)


def mark_lease_state(lease: dict[str, Any], state: str) -> None:
    if not lease.get("lease_id"):
        return
    lease["state"] = state
    write_lease(lease)


def remove_lease(lease: dict[str, Any]) -> None:
    if not lease.get("lease_id"):
        return
    try:
        lease_path(lease).unlink()
    except FileNotFoundError:
        pass


def public_lease(lease: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in lease.items()
        if not str(key).startswith("_")
    }


def read_local_leases() -> list[tuple[Path, dict[str, Any]]]:
    directory = lease_dir()
    if not directory.exists():
        return []
    leases: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted(directory.glob("*.json")):
        try:
            lease = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        leases.append((path, lease))
    return leases


def matching_deferred_lease(context: dict[str, Any], route: dict[str, Any]) -> dict[str, Any] | None:
    candidates = [
        lease
        for _, lease in read_local_leases()
        if deferred_lease_matches_route(lease, context, route)
    ]
    if not candidates:
        return None
    return sorted(candidates, key=lambda item: int(item.get("updated_at_ms") or 0), reverse=True)[0]


def deferred_lease_matches_route(lease: dict[str, Any], context: dict[str, Any], route: dict[str, Any]) -> bool:
    if not lease.get("opened_by_helper"):
        return False
    if lease.get("state") not in RECOVERABLE_HELPER_LEASE_STATES:
        return False
    if not lease_has_exact_route_identity(lease):
        return False
    if route.get("project_instance_id") != lease.get("project_instance_id"):
        return False
    if route.get("session_id") != lease.get("session_id"):
        return False
    if lease.get("project_key") and route.get("project_key") and lease.get("project_key") != route.get("project_key"):
        return False
    lease_target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
    context_target = lifecycle_target_path(context)
    route_base = route.get("base_path")
    return paths_same(lease_target, context_target) and paths_same(route_base, context_target)


def lease_has_exact_route_identity(lease: dict[str, Any]) -> bool:
    lease_target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
    return bool(lease_target and lease.get("project_instance_id") and lease.get("session_id"))


def paths_same(left: Any, right: Any) -> bool:
    if not left or not right:
        return False
    try:
        return Path(str(left)).resolve() == Path(str(right)).resolve()
    except OSError:
        return str(left) == str(right)


def retirement_worktree(path: Path, dry_run: bool = False, offline_only: bool = False) -> Path:
    root = path.expanduser().resolve()
    if git_root(root) != root:
        raise InspectError("Removal requires the exact linked worktree root.", 3)
    common = Path(subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "--path-format=absolute", "--git-common-dir"], text=True,
    ).strip()).resolve()
    git_dir = Path(subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "--absolute-git-dir"], text=True,
    ).strip()).resolve()
    if git_dir == common or (not dry_run and (git_dir / "locked").exists()):
        raise InspectError("Primary or locked worktrees cannot be removed; finish the cleanup ownership checks first.", 3)
    if git_common_worktree(root) is None:
        raise InspectError("Cannot resolve the primary checkout for worktree removal.", 3)
    if subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True).strip():
        raise InspectError("Preserve uncommitted work before removing this worktree.", 3)
    reconcile_retirement_leases(root, dry_run, offline_only)
    return root


def reconcile_retirement_leases(root: Path, dry_run: bool, offline_only: bool = False) -> None:
    leases = [(path, lease) for path, lease in read_local_leases()
              if paths_same(lease.get("worktree_root"), root)]
    if not leases:
        return
    result = {} if offline_only else cleanup_stale_helper_leases(argparse.Namespace(
        max_age_ms=24 * 60 * 60 * 1000, dry_run=dry_run, worktree_root=root,
    ))
    released = set(result.get("would_remove" if dry_run else "removed") or [])
    blockers = [(path, lease) for path, lease in leases if str(path) not in released]
    if blockers:
        ids = [str(lease.get("lease_id") or path.stem) for path, lease in blockers]
        commands = [] if offline_only else [f"uv run {shlex.quote(str(Path(__file__).resolve()))} cleanup-helper-leases "
                    f"--lease-id {shlex.quote(lease_id)} --no-dry-run" for lease_id in ids]
        raise InspectError(
            (f"IDE leases block offline retirement: {', '.join(ids)}. Retain the worktree until a session authorized to contact the IDE resolves them."
             if offline_only else f"IDE leases block retirement: {', '.join(ids)}. After resolving ownership/activity, run: " + "; ".join(commands)), 3,
            {"blocking_lease_ids": ids, "cleanup_commands": commands, "lease_cleanup": result},
        )


FINDER_METADATA_NAME = ".DS_Store"
FINDER_RACE_ATTEMPTS = 3


def entry_signature(status: os.stat_result, is_directory: bool) -> tuple[int, ...]:
    # Deleting children changes a folder's mtime, so folders are matched by inode alone.
    return (status.st_ino,) if is_directory else (status.st_ino, status.st_size, status.st_mtime_ns)


def worktree_entries(root: Path) -> dict[str, tuple[int, ...]]:
    """Map every entry below root to its identity and content signature without following symlinks."""
    entries: dict[str, tuple[int, ...]] = {}
    pending = [root]
    while pending:
        directory = pending.pop()
        with os.scandir(directory) as listing:
            for entry in listing:
                path = Path(entry.path)
                is_directory = entry.is_dir(follow_symlinks=False)
                entries[str(path.relative_to(root))] = entry_signature(entry.stat(follow_symlinks=False), is_directory)
                if is_directory:
                    pending.append(path)
    return entries


def delete_verified_entries(root: Path, directory: Path, before: dict[str, tuple[int, ...]], unexpected: list[str],
                            delete: bool = True) -> None:
    """Delete entries unchanged since the snapshot, and Finder metadata, unless only checking; collect every other entry."""
    try:
        with os.scandir(directory) as listing:
            entries = list(listing)
    except FileNotFoundError:
        return
    for entry in entries:
        path = Path(entry.path)
        relative = str(path.relative_to(root))
        try:
            # Check each entry immediately before deleting it so a file written mid-pass is kept.
            is_directory = entry.is_dir(follow_symlinks=False)
            status = os.lstat(path)
            if (not is_directory and entry.name == FINDER_METADATA_NAME) or before.get(relative) == entry_signature(status, is_directory):
                if is_directory:
                    delete_verified_entries(root, path, before, unexpected, delete)
                    if not delete:
                        continue
                    with suppress(OSError):
                        # Finder may have refilled it; the next attempt retries.
                        os.rmdir(path)
                elif delete:
                    os.unlink(path)
            else:
                unexpected.append(relative)
        except FileNotFoundError:
            continue


def finish_raced_worktree_removal(common: Path, root: Path, before: dict[str, tuple[int, ...]], git_error: str) -> None:
    """Finish a removal Git unregistered but could not empty, keeping anything it did not verify."""
    # Git deletes the checkout before its registration, so a registered path was refused rather than raced.
    listed = subprocess.run(["git", "-C", str(common), "worktree", "list", "--porcelain"],
                            capture_output=True, text=True, check=True).stdout.splitlines()
    if any(line.startswith("worktree ") and paths_same(line.removeprefix("worktree "), root) for line in listed):
        raise InspectError("Git worktree removal failed; SDK cleanup may already have completed.", 3, {"git_error": git_error})
    # Finder writes .DS_Store into folders while they are deleted; every other leftover must be unchanged since the snapshot.
    for _ in range(FINDER_RACE_ATTEMPTS):
        if not root.exists() and not root.is_symlink():
            return
        if root.is_symlink() or not root.is_dir():
            break
        unexpected: list[str] = []
        # Check the whole tree first so a hold keeps every leftover, then recheck each entry as it is deleted.
        delete_verified_entries(root, root, before, unexpected, delete=False)
        if not unexpected:
            delete_verified_entries(root, root, before, unexpected)
        if unexpected:
            unexpected.sort()
            raise InspectError("Worktree removal left files that were not in the verified checkout; they are kept.", 3,
                               {"git_error": git_error, "retained_entry_count": len(unexpected), "retained_entries": unexpected[:50]})
        with suppress(OSError):
            os.rmdir(root)
    if root.exists() or root.is_symlink():
        remaining: list[str] = []
        if root.is_dir() and not root.is_symlink():
            delete_verified_entries(root, root, before, remaining, delete=False)
        raise InspectError("Git unregistered the worktree but its directory could not be emptied; inspect what remains.", 3,
                           {"git_error": git_error, "retained_entry_count": len(remaining), "retained_entries": sorted(remaining)[:50]})


HELPER_SDK_RECORD_FILENAME = "inspection-helper-sdks.xml"
HELPER_PROJECT_CACHE_MARKER = ".inspection-helper-owned.json"
HELPER_SEALED_CACHE_PREFIX = ".inspection-retired-"
HELPER_FINALIZING_CACHE_PREFIX = ".inspection-finalizing-"


def offline_ide_processes() -> list[int]:
    """A missing plugin identity does not prove the IDE process has exited."""
    try:
        return ide_launcher_pids(ALL_IDE_LAUNCHER_NAMES)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise InspectError("Cannot prove IDE processes have exited; restore process discovery or use live IDE cleanup.", 3) from error


def ide_launcher_pids(launcher_names: tuple[str, ...]) -> list[int]:
    launcher = re.compile(rf"\.app/Contents/MacOS/(?:{'|'.join(map(re.escape, launcher_names))})$", re.IGNORECASE)
    output = subprocess.check_output(["ps", "-axo", "pid=,comm="], text=True, timeout=5)
    pids = []
    for line in output.splitlines():
        parts = line.strip().split(maxsplit=1)
        if len(parts) == 2 and launcher.search(parts[1]):
            pids.append(int(parts[0]))
    return pids


def offline_retirement_records(root: Path) -> list[str]:
    """Prove the no-cleanup case from disk; never edit an offline IDE's state."""
    if sys.platform != "darwin":
        raise InspectError("Offline retirement record discovery is only supported on macOS; use a live IDE for cleanup.", 3)
    if pids := offline_ide_processes():
        raise InspectError("An IDE is running; retain the worktree until an authorized session can use live cleanup or close the IDE before offline retirement.", 3,
                           {"offline_retirement_ide_pids": pids})
    checked: list[str] = []

    def present(path: Path) -> bool:
        try:
            path.lstat()
            return True
        except FileNotFoundError:
            return False

    def children(path: Path) -> list[Path]:
        try:
            return list(path.iterdir())
        except FileNotFoundError:
            return []

    def refers_to_worktree(value: str) -> bool:
        value = urllib.parse.unquote(value.removeprefix("file://"))
        value = value.replace("$USER_HOME$", str(Path.home()))
        path = Path(os.path.expanduser(value))
        return path.is_absolute() and (Path(os.path.normpath(path)).is_relative_to(root) or path.resolve().is_relative_to(root))

    def require_unrelated(path: Path, values: list[str]) -> None:
        checked.append(str(path))
        if any(refers_to_worktree(value) for value in values):
            raise InspectError("IDE records reference this worktree; use a live IDE to prove cleanup.", 3,
                               {"offline_retirement_record": str(path)})

    try:
        # Shared inspection profiles/vcs.xml are copied by Git and do not prove
        # this worktree was prepared. A virtualenv alone is not a registration either.
        project_state = [path for path in children(root / ".idea")
                         if path.name in {"misc.xml", "modules.xml", "workspace.xml", ".name"} or path.suffix == ".iml"]
        for path in [*project_state, repository_preparation_receipt_path({"worktree_root": str(root)})]:
            if present(path):
                raise InspectError("IDE preparation records exist; use a live IDE to prove cleanup.", 3,
                                   {"offline_retirement_record": str(path)})
        configs = children(Path.home() / "Library" / "Application Support" / "JetBrains")
        override = os.environ.get("JETBRAINS_INSPECTION_IDE_CONFIG_DIR")
        if override:
            # Add the override, never let it hide other installed IDE records.
            configs.append(Path(override).expanduser())
        for config in configs:
            if not stat.S_ISDIR(config.stat().st_mode):
                continue
            for name in ("jdk.table.xml", HELPER_SDK_RECORD_FILENAME, "recentProjects.xml", "recentProjectDirectories.xml"):
                path = config / "options" / name
                if not present(path):
                    continue
                document = ElementTree.parse(path)
                if document.getroot().tag != "application":
                    raise ValueError("Invalid IDE application records")
                require_unrelated(path, [value for element in document.iter() for value in element.attrib.values()])
            # Custom system paths cannot be proved from the standard cache root.
            properties = config / "idea.properties"
            if present(properties) and any(re.match(r"\s*idea\.(system|config)\.path\s*=", line)
                                           for line in properties.read_text().splitlines()):
                raise InspectError("Custom IDE storage paths require live IDE cleanup proof.", 3,
                                   {"offline_retirement_record": str(properties)})
        for system in children(Path.home() / "Library" / "Caches" / "JetBrains"):
            if not stat.S_ISDIR(system.stat().st_mode):
                continue
            for project in children(system / "projects"):
                if (not project.name.startswith((HELPER_FINALIZING_CACHE_PREFIX, HELPER_SEALED_CACHE_PREFIX))
                        and not stat.S_ISDIR(project.stat().st_mode)):
                    continue
                marker = project if project.name.startswith(HELPER_FINALIZING_CACHE_PREFIX) else project / HELPER_PROJECT_CACHE_MARKER
                if not present(marker):
                    if project.name.startswith(HELPER_SEALED_CACHE_PREFIX):
                        raise InspectError("Unresolved IDE cache ownership requires live IDE cleanup proof.", 3,
                                           {"offline_retirement_record": str(marker)})
                    continue
                record = json.loads(marker.read_text())
                if (not isinstance(record, dict) or not isinstance(record.get("worktree_path"), str)
                        or not Path(record["worktree_path"]).is_absolute()):
                    raise InspectError("Invalid IDE cache ownership requires live IDE cleanup proof.", 3,
                                       {"offline_retirement_record": str(marker)})
                require_unrelated(marker, [record["worktree_path"]])
        return checked
    except (OSError, ValueError, ElementTree.ParseError) as error:
        raise InspectError("Cannot read offline IDE records; retain the worktree and restore record access or use a live IDE.", 3,
                           {"offline_retirement_error": type(error).__name__,
                            "offline_retirement_record": getattr(error, "filename", None)}) from error


def unregister_helper_sdks(identities: list[dict[str, Any]], root: Path | None, dry_run: bool,
                           offline_records: list[str] | None = None) -> list[dict[str, Any]]:
    if not identities:
        if root is not None:
            records = offline_retirement_records(root)
            if offline_records is not None:
                offline_records.extend(records)
            return []
        raise InspectError("No live IDE is available to prove SDK cleanup; start the prepared worktree's IDE first.", 3)
    # Validate every session before sending any mutating request.
    for identity in identities:
        if identity.get("helper_sdk_lifecycle_version") != 1 or not identity.get("session_id") or not identity.get("port"):
            raise InspectError("Install the plugin with helper-owned SDK lifecycle support in every discovered IDE before cleanup.", 3)
    if root is not None:
        for identity in identities:
            for project in identity.get("open_projects") or []:
                base = project.get("base_path")
                if not base or Path(base).resolve().is_relative_to(root.resolve()):
                    raise InspectError("Close every IDE project at or inside the worktree before removal.", 3)
    results = []
    seen: set[tuple[Any, Any]] = set()
    for identity in identities:
        key = (identity["port"], identity["session_id"])
        if key in seen:
            continue
        seen.add(key)
        params = {"session_id": identity["session_id"], "dry_run": str(dry_run).lower()}
        params.update({"worktree_path": str(root)} if root is not None else {"orphans": "true"})
        try:
            body = http_post(identity["port"], "lifecycle/unregister-python-sdk", params, timeout=15.0).body
        except InspectError as error:
            # A refused orphan preview still contains the candidates and held records.
            if error.payload.get("http_status") == 409 and error.payload.get("status") == "refused" and not error.payload.get("reason") and (root is not None or dry_run):
                body = error.payload
            else:
                error.payload["completed_sdk_cleanup"] = results
                raise
        entries = body.get("sdks")
        orphan_preview = root is None and dry_run
        valid_statuses = {"would_remove", "absent"} if dry_run else {"removed", "absent"}
        valid_reasons = {"helper_owned", "already_absent"}
        if root is not None:
            valid_statuses.add("refused")
            valid_reasons.add("not_helper_owned")
        if orphan_preview:
            valid_statuses.add("refused")
            valid_reasons.update({"sdk_in_use", "ownership_mismatch", "ambiguous_sdk"})
        if (body.get("session_id") != identity["session_id"] or body.get("reason") or body.get("status") not in {"ok", "refused"}
            or body.get("sdk_lifecycle_version") != 1 or body.get("dry_run") is not dry_run
            or not isinstance(entries, list) or any(
                not isinstance(entry, dict) or entry.get("status") not in valid_statuses
                or entry.get("reason") not in valid_reasons
                or (entry.get("status") == "refused" and not orphan_preview and entry.get("reason") != "not_helper_owned")
                or not isinstance(entry.get("sdk_name"), str) or not entry.get("sdk_name")
                or not isinstance(entry.get("worktree_path"), str)
                or not Path(entry.get("worktree_path") or "").is_absolute()
                or (root is not None and not paths_same(entry.get("worktree_path"), root))
                for entry in entries)):
            raise InspectError("SDK cleanup did not prove a successful helper-owned result; retain the worktree.", 3,
                               {"sdk_cleanup": public_payload(body), "completed_sdk_cleanup": results, "http_status": body.get("http_status")})
        results.append({"ide": public_identity_summary(identity), "result": public_payload(body)})
    return results


def retire_helper_project_caches(identities: list[dict[str, Any]], root: Path, dry_run: bool) -> list[dict[str, Any]]:
    results = []
    for identity in identities:
        if identity.get("helper_project_cache_lifecycle_version") != 1:
            results.append({"ide": public_identity_summary(identity), "result": {
                "status": "unsupported", "reason": "plugin_capability_unavailable", "caches": [],
            }})
            continue
        params = {"worktree_path": str(root), "session_id": identity["session_id"],
                  "dry_run": "true" if dry_run else "false"}
        try:
            body = http_post(identity["port"], "lifecycle/retire-project-caches", params, timeout=120.0).body
        except InspectError as error:
            error.payload.setdefault("completed_project_cache_preview" if dry_run else "completed_project_cache_cleanup", results)
            raise
        except (OSError, http.client.HTTPException) as error:
            raise InspectError("Project-cache transport failed; retain the worktree.", 3,
                               {"error_reason": "inspection_api_transport_error",
                                "completed_project_cache_preview" if dry_run else "completed_project_cache_cleanup": results}) from error
        entries = body.get("caches")
        allowed_statuses = {"would_remove", "absent"} if dry_run else {"removed", "absent"}
        if (body.get("session_id") != identity["session_id"] or body.get("status") != "ok"
            or body.get("project_cache_lifecycle_version") != 1 or body.get("dry_run") is not dry_run
            or not paths_same(body.get("worktree_path"), root) or not isinstance(entries, list)
            or any(not isinstance(entry, dict) or entry.get("status") not in allowed_statuses
                   or entry.get("reason") != "helper_owned_closed_project"
                   or not paths_same(entry.get("worktree_path"), root)
                   for entry in entries)):
            raise InspectError("Project-cache retirement was not proved; retain the worktree.", 3,
                               {"project_cache_retirement": public_payload(body), "completed_project_cache_preview" if dry_run else "completed_project_cache_cleanup": results})
        results.append({"ide": public_identity_summary(identity), "result": public_payload(body)})
    return results


def command_retire_sdks(args: argparse.Namespace) -> dict[str, Any]:
    preview: list[dict[str, Any]] = []
    applied: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    cache_preview: list[dict[str, Any]] = []
    cache_cleanup: list[dict[str, Any]] = []
    offline_records: list[str] = []
    with lifecycle_lock(args.lifecycle_lock_timeout_ms):
        try:
            offline_only = getattr(args, "offline_only", False)
            root = retirement_worktree(Path(args.repo), args.dry_run, offline_only) if args.command == "remove-worktree" else None
            identities = [] if offline_only else discover_identities(None)
            proof_identities = identities
            preview = unregister_helper_sdks(identities, root, True, offline_records)
            if root is not None:
                cache_preview = retire_helper_project_caches(identities, root, True)
            if not args.dry_run:
                if root is not None:
                    # Only IDEs with a helper-owned SDK need the mutating call; the final preview rechecks every IDE.
                    for item in preview:
                        if any(entry["status"] == "would_remove" for entry in item["result"]["sdks"]):
                            identity = next(identity for identity in identities if
                                            identity["session_id"] == item["ide"]["session_id"] and identity["port"] == item["ide"]["port"])
                            applied.extend(unregister_helper_sdks([identity], root, False))
                        else:
                            skipped.append(item)
                else:
                    # Explicit paths bind apply to the earlier reviewed dry-run.
                    selected = {str(Path(path).expanduser().resolve()) for path in (getattr(args, "worktree_path", None) or [])}
                    refused_roots = {str(Path(entry["worktree_path"]).resolve()) for item in preview for entry in item["result"]["sdks"] if entry["status"] == "refused"}
                    candidates = {str(Path(entry["worktree_path"]).resolve()) for item in preview for entry in item["result"]["sdks"]} - refused_roots
                    if candidates and not selected:
                        raise InspectError("Repeat each reviewed orphan path with --worktree-path before applying cleanup.", 3)
                    if not selected.issubset(candidates):
                        raise InspectError("Selected orphan paths are absent from the current helper-owned preview.", 3)
                    for candidate in selected:
                        path = Path(candidate)
                        if path.exists() or not path.parent.is_dir() or path.is_symlink():
                            raise InspectError("Orphan removal requires a missing worktree and accessible parent.", 3)
                    for item in preview:
                        identity = next(identity for identity in identities if
                                        identity["session_id"] == item["ide"]["session_id"] and identity["port"] == item["ide"]["port"])
                        roots = {str(Path(entry["worktree_path"]).resolve()) for entry in item["result"]["sdks"]} & selected
                        for candidate in sorted(roots):
                            path = Path(candidate)
                            if path.exists() or not path.parent.is_dir() or path.is_symlink():
                                raise InspectError("Orphan path changed before SDK removal.", 3)
                            applied.extend(unregister_helper_sdks([identity], path, False))
                    selected_sdk_names = {entry["sdk_name"] for item in preview for entry in item["result"]["sdks"]
                                          if str(Path(entry["worktree_path"]).resolve()) in selected}
                    remaining = unregister_helper_sdks(identities, None, True)
                    if any(entry.get("sdk_name") in selected_sdk_names and entry["status"] != "absent"
                           for item in remaining for entry in item["result"]["sdks"]):
                        raise InspectError("Reviewed orphan SDKs remain registered after apply.", 3, {"sdk_remaining": remaining})
                if root is not None:
                    # Recheck local state and live projects after SDK retirement.
                    retirement_worktree(root, False, offline_only)
                    current = [] if offline_only else discover_identities(None)
                    proof_identities = current
                    final_preview = unregister_helper_sdks(current, root, True, offline_records)
                    if any(entry["status"] != "absent" and entry["reason"] != "not_helper_owned" for item in final_preview for entry in item["result"]["sdks"]):
                        raise InspectError("An SDK still needs retirement; retain the worktree and repeat the preview.", 3,
                                           {"sdk_remaining": final_preview})
                    common = git_common_worktree(root)
                    if common is None:
                        raise InspectError("Cannot resolve the primary checkout for worktree removal.", 3)
                    cache_cleanup = retire_helper_project_caches(current, root, False)
                    remaining_caches = retire_helper_project_caches(current, root, True)
                    if any(entry["status"] != "absent" for item in remaining_caches for entry in item["result"]["caches"]):
                        raise InspectError("Project caches still need retirement; retain the worktree.", 3,
                                           {"project_cache_remaining": remaining_caches})
                    before = worktree_entries(root)
                    completed = subprocess.run(["git", "-C", str(common), "worktree", "remove", str(root)],
                                               capture_output=True, text=True)
                    if completed.returncode or root.exists():
                        finish_raced_worktree_removal(common, root, before, completed.stderr)
            return {"status": "ok", "dry_run": args.dry_run, "worktree_path": str(root) if root else None,
                    "offline_cleanup_proved": root is not None and not proof_identities,
                    "offline_records_checked": sorted(set(offline_records)),
                    "project_cache_preview": cache_preview, "project_cache_cleanup": cache_cleanup,
                    "sdk_preview": preview, "sdk_cleanup": applied, "sdk_apply_skipped": [item["ide"] for item in skipped],
                    "worktree_removed": root is not None and not args.dry_run,
                    "refused_preview_sdk_count": sum(entry["status"] == "refused" for item in preview for entry in item["result"]["sdks"]),
                    "preserved_unowned_sdk_count": sum(entry["reason"] == "not_helper_owned" for item in (preview if args.dry_run else applied + skipped) for entry in item["result"]["sdks"])}
        except (subprocess.CalledProcessError, OSError) as error:
            raise InspectError("Worktree retirement failed; retain the worktree and inspect the Git/filesystem error.", 3,
                               {"sdk_preview": preview, "sdk_cleanup": applied, "project_cache_preview": cache_preview,
                                "completed_project_cache_cleanup": cache_cleanup, "error": str(error)}) from error
        except InspectError as error:
            error.payload.setdefault("project_cache_preview", cache_preview)
            error.payload["completed_project_cache_cleanup"] = cache_cleanup + error.payload.get("completed_project_cache_cleanup", [])
            error.payload.update({"sdk_preview": preview, "completed_sdk_cleanup": applied + error.payload.get("completed_sdk_cleanup", [])})
            raise


def command_cleanup_leases(args: argparse.Namespace) -> dict[str, Any]:
    with lifecycle_lock(getattr(args, "lifecycle_lock_timeout_ms", DEFAULT_LIFECYCLE_LOCK_TIMEOUT_MS)):
        return cleanup_stale_helper_leases(args)


def cleanup_stale_helper_leases(args: argparse.Namespace) -> dict[str, Any]:
    removed: list[str] = []
    stale: list[str] = []
    closed: list[dict[str, Any]] = []
    failed: list[dict[str, Any]] = []
    unresolved: list[dict[str, Any]] = []
    local_leases = read_local_leases()
    if getattr(args, "worktree_root", None) is not None:
        local_leases = [(path, lease) for path, lease in local_leases
                        if paths_same(lease.get("worktree_root"), args.worktree_root)]
        ids = [lease.get("lease_id") for _, lease in local_leases]
        try:
            valid = len(set(ids)) == len(ids) and all(
                str(uuid.UUID(lease["lease_id"])) == path.stem for path, lease in local_leases
            )
        except (ValueError, TypeError, KeyError, AttributeError):
            valid = False
        if not valid:
            return {"status": "incomplete", "dry_run": args.dry_run, "stale": [], "removed": [],
                    "closed": [], "unresolved": [], "failed": [{"reason": "lease_identity_mismatch"}]}
    selectors = getattr(args, "lease_id", None)
    selected_id = None
    if selectors is not None:
        reason = None
        try:
            if len(selectors) != 1:
                raise ValueError("Exactly one lease selector is required.")
            selected_id = str(uuid.UUID(selectors[0]))
        except (ValueError, TypeError, AttributeError):
            reason = "invalid_lease_selector"
        if reason is None:
            local_leases = [(path, lease) for path, lease in local_leases if lease.get("lease_id") == selected_id]
            if not local_leases:
                reason = "lease_not_found"
            elif len(local_leases) != 1:
                reason = "lease_identity_ambiguous"
            elif local_leases[0][0].stem != selected_id:
                reason = "lease_identity_mismatch"
        if reason is not None:
            return {
                "status": "incomplete", "dry_run": args.dry_run,
                "stale": [], "removed": [], "closed": [], "unresolved": [],
                "failed": [{"status": "failed", "reason": reason, "lease_id": selected_id}],
            }
    if not local_leases:
        return {"status": "ok", "dry_run": args.dry_run, "would_remove": [],
                "removed": [], "stale": [], "closed": [], "failed": [], "unresolved": []}
    cutoff = now_ms() - int(args.max_age_ms)
    stale_leases: list[tuple[Path, dict[str, Any]]] = []
    for path, lease in local_leases:
        updated = int(lease.get("updated_at_ms") or lease.get("created_at_ms") or 0)
        pid = lease.get("pid")
        # Age is useful for legacy records without a PID; it never overrides a live helper.
        is_stale = not pid_alive(int(pid)) if pid else updated < cutoff
        if not is_stale:
            continue
        stale.append(str(path))
        stale_leases.append((path, lease))
    if selected_id is not None and not stale_leases:
        return {
            "status": "incomplete", "dry_run": args.dry_run,
            "stale": [], "removed": [], "closed": [], "failed": [],
            "unresolved": [{"status": "skipped", "reason": "lease_not_stale", "lease_id": selected_id}],
        }
    if not stale_leases:
        return {
            "status": "ok",
            "dry_run": args.dry_run,
            "stale": stale,
            "removed": [],
            "closed": [],
            "failed": [],
            "unresolved": [],
        }
    routes: list[dict[str, Any]] = []
    observed_sessions: set[str] = set()
    if any(lease_may_own_open_project(lease) for _, lease in stale_leases):
        try:
            routes, observed_sessions = discover_routes_for_cleanup(args)
        except (InspectError, OSError) as error:
            return {
                "status": "incomplete",
                "dry_run": args.dry_run,
                "stale": stale,
                "removed": [],
                "closed": [],
                "failed": [
                    {
                        "status": "failed",
                        "reason": "route_discovery_failed",
                        "message": str(error),
                    }
                ],
                "unresolved": [],
            }
    for path, lease in stale_leases:
        cleanup_result = (cleanup_stale_helper_lease(lease, routes, observed_sessions, dry_run=True)
                          if args.dry_run else cleanup_stale_helper_lease(lease, routes, observed_sessions))
        if cleanup_result.get("status") == "closed":
            closed.append(cleanup_result)
            if not args.dry_run:
                path.unlink(missing_ok=True)
            removed.append(str(path))
        elif cleanup_result.get("status") == "failed":
            failed.append(cleanup_result)
        elif cleanup_result.get("status") == "skipped":
            unresolved.append(cleanup_result)
        elif cleanup_result.get("reason") in {
            "open_not_attempted",
            "project_not_open",
            "project_preexisted",
            "ownership_not_proven",
            "original_ide_process_dead_no_route",
            "original_ide_reports_project_not_open",
        }:
            if not args.dry_run:
                path.unlink(missing_ok=True)
            removed.append(str(path))
    return {
        "status": "incomplete" if failed or unresolved else "ok",
        "dry_run": args.dry_run,
        "stale": stale,
        "removed": [] if args.dry_run else removed,
        "would_remove": removed if args.dry_run else [],
        "closed": closed,
        "failed": failed,
        "unresolved": unresolved,
    }


def discover_routes_for_cleanup(args: argparse.Namespace) -> tuple[list[dict[str, Any]], set[str]]:
    routes: list[dict[str, Any]] = []
    observed_sessions: set[str] = set()
    identities = discover_identities(getattr(args, "port", None))
    for identity in identities:
        port = identity.get("port")
        session_id = identity.get("session_id")
        if session_id:
            observed_sessions.add(str(session_id))
        if not port:
            continue
        for project in identity.get("open_projects") or []:
            if isinstance(project, dict):
                route = project.copy()
                route.setdefault("port", port)
                route.setdefault("session_id", session_id)
                route.setdefault("ide", {
                    "name": identity.get("ide_name") or identity.get("name"),
                    "product_code": identity.get("ide_product_code") or identity.get("product_code"),
                    "version": identity.get("ide_version") or identity.get("version"),
                    "plugin_version": identity.get("plugin_version"),
                    "plugin_build_fingerprint": identity.get("plugin_build_fingerprint"),
                    "lifecycle_ownership_protocol": identity.get("lifecycle_ownership_protocol"),
                })
                routes.append(route)
    return routes, observed_sessions


def cleanup_stale_helper_lease(
    lease: dict[str, Any],
    routes: list[dict[str, Any]],
    observed_sessions: set[str] | None = None,
    *, dry_run: bool = False,
) -> dict[str, Any]:
    if lease_proves_open_not_attempted(lease):
        return {"status": "not_needed", "reason": "open_not_attempted", "lease_id": lease.get("lease_id")}
    if not lease_may_own_open_project(lease):
        if lease.get("opened_by_helper") is not False:
            return {"status": "skipped", "reason": "ownership_evidence_missing", "lease_id": lease.get("lease_id")}
        return {"status": "not_needed", "reason": "project_preexisted", "lease_id": lease.get("lease_id")}
    sessions = observed_sessions if observed_sessions is not None else {
        str(route.get("session_id")) for route in routes if route.get("session_id")
    }
    route = matching_route_for_lease(lease, routes) if lease_has_exact_route_identity(lease) else matching_route_for_ownership_probe(lease, routes)
    if route is None:
        if lease_has_exact_route_identity(lease) and lease.get("session_id") in sessions:
            return {"status": "not_needed", "reason": "project_not_open", "lease_id": lease.get("lease_id")}
        if (can_release_dead_original_ide_lease(lease, routes, observed_sessions)
                or timed_out_open_died_with_original_ide(lease, routes, observed_sessions)):
            return {
                "status": "not_needed",
                "reason": "original_ide_process_dead_no_route",
                "lease_id": lease.get("lease_id"),
                "released_without_close": True,
            }
        probe = original_session_open_probe(lease, sessions)
        if probe_reports_project_not_open(lease, probe):
            return {
                "status": "not_needed",
                "reason": "original_ide_reports_project_not_open",
                "lease_id": lease.get("lease_id"),
                "released_without_close": True,
            }
        unresolved = {
            "status": "skipped",
            "reason": "ownership_route_unavailable",
            "lease_id": lease.get("lease_id"),
            "next_action": "Retry cleanup after the original IDE session and exact route are discoverable.",
        }
        if probe is not None:
            unresolved["open_probe"] = {"status": probe.get("status"), "reason": probe.get("reason")}
            if not lease_open_acknowledged_by_session(lease):
                unresolved["next_action"] = (
                    "The original IDE session did not acknowledge every open request for this lease, so a late "
                    "open cannot be ruled out; retry cleanup after the exact route is discoverable."
                )
            elif probe.get("status") != "not_open":
                unresolved["next_action"] = (
                    f"The original IDE session reports this worktree {probe.get('status')} "
                    f"({probe.get('reason')}); retry cleanup after that open settles."
                )
        return unresolved
    if dry_run:
        return {"status": "skipped", "reason": "ownership_preview_requires_apply",
                "lease_id": lease.get("lease_id"),
                "next_action": f"Run cleanup-helper-leases --lease-id {lease.get('lease_id')} --no-dry-run to prove ownership and settled activity."}
    try:
        ownership_proven, close_proof, claim_metadata, claimed_route = reclaim_lifecycle_claim(lease, route)
    except InspectError as error:
        return {
            "status": "failed",
            "reason": public_cleanup_reason(error),
            "lease_id": lease.get("lease_id"),
        }
    if ownership_proven is False:
        lease["opened_by_helper"] = False
        return {"status": "not_needed", "reason": "ownership_not_proven", "lease_id": lease.get("lease_id")}
    if ownership_proven is not True or not close_proof:
        return {
            "status": "skipped",
            "reason": "ownership_unresolved",
            "lease_id": lease.get("lease_id"),
            "claim": claim_metadata,
        }
    persist_claimed_cleanup_ownership(lease, claimed_route, claim_metadata)
    result = cleanup_lifecycle(lease, claimed_route, str(close_proof))
    result.setdefault("lease_id", lease.get("lease_id"))
    return result


def original_session_open_probe(lease: dict[str, Any], observed_sessions: set[str]) -> dict[str, Any] | None:
    """Ask the lease's still-running IDE session whether it has the worktree open, opening, or unresolved."""
    session_id = lease.get("session_id")
    port = lease.get("ide_port")
    target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
    if not session_id or str(session_id) not in observed_sessions or not isinstance(port, int) or not target:
        return None
    try:
        identity = identity_for_port(port)
    except (InspectError, OSError):
        return None
    # Plugins without lifecycle open diagnostics would treat the probe as a real open request.
    if identity.get("session_id") != session_id or int(identity.get("lifecycle_open_diagnostic_version") or 0) < 1:
        return None
    try:
        body = http_get(port, "lifecycle/open", {
            "worktree_path": target,
            "session_id": session_id,
            "lease_id": lease.get("lease_id"),
            "probe": "true",
        }).body
    except InspectError as error:
        return {"status": "error", "reason": infer_error_reason(error, error.payload)}
    except OSError as error:
        return {"status": "error", "reason": type(error).__name__.lower()}
    return public_payload(body)


def lease_open_acknowledged_by_session(lease: dict[str, Any]) -> bool:
    """Whether the lease's IDE session registered every open request, so none can still arrive unseen."""
    if lease.get("state") not in SETTLED_LEASE_STATES:
        return False
    if lease.get("open_request_may_have_been_accepted") is not False:
        return False
    attempts = lease.get("open_attempts")
    if not isinstance(attempts, list):
        return False
    # App startup and "no matching IDE" entries sent no project-open request.
    requests = [attempt for attempt in attempts if not project_open_was_not_requested([attempt])]
    if not requests:
        return False
    for attempt in requests:
        if not isinstance(attempt, dict) or attempt.get("request_may_have_been_accepted") is True:
            return False
        identity = dict_field(attempt, "identity")
        if not (
            attempt.get("accepted") is True
            and attempt.get("ownership_registered") is True
            and attempt.get("lease_id") == lease.get("lease_id")
            and (identity.get("session_id") or attempt.get("session_id")) == lease.get("session_id")
        ):
            return False
    return True


def probe_reports_project_not_open(lease: dict[str, Any], probe: dict[str, Any] | None) -> bool:
    return (
        lease_open_acknowledged_by_session(lease)
        and isinstance(probe, dict)
        and probe.get("probe") is True
        and probe.get("status") == "not_open"
        and probe.get("reason") == "project_not_open"
        and probe.get("opened") is False
        and probe.get("opening_scheduled") is False
        and bool(lease.get("session_id"))
        and probe.get("session_id") == lease.get("session_id")
    )


def original_session_gone_and_worktree_closed(
    lease: dict[str, Any],
    routes: list[dict[str, Any]],
    observed_sessions: set[str] | None,
) -> bool:
    if observed_sessions is None or lease.get("state") not in SETTLED_LEASE_STATES:
        return False
    session_id = lease.get("session_id")
    target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
    if not lease.get("lease_id") or not session_id or not target or str(session_id) in observed_sessions:
        return False
    for route in routes:
        route_path = route.get("base_path") or route.get("project_file_path")
        if not route_path or paths_same(target, route_path):
            return False
    return True


def can_release_dead_original_ide_lease(
    lease: dict[str, Any],
    routes: list[dict[str, Any]],
    observed_sessions: set[str] | None,
) -> bool:
    if lease.get("opened_by_helper") is not True or lease.get("open_request_may_have_been_accepted") is True:
        return False
    if not original_session_gone_and_worktree_closed(lease, routes, observed_sessions):
        return False
    lease_id = lease.get("lease_id")
    session_id = lease.get("session_id")
    attempts = lease.get("open_attempts")
    if not isinstance(attempts, list) or any(
        isinstance(attempt, dict) and attempt.get("request_may_have_been_accepted") is True
        for attempt in attempts
    ):
        return False
    accepted_identities: list[tuple[str, int]] = []
    for attempt in attempts:
        if not isinstance(attempt, dict):
            continue
        # App startup carries no project-open or lease ownership evidence.
        if attempt.get("method") == "bootstrap_ide" and not any(
            key in attempt for key in ("identity", "lease_id", "ownership_registered", "lifecycle_ownership_protocol")
        ):
            continue
        accepted = attempt.get("accepted") is True
        ownership_registered = attempt.get("ownership_registered") is True
        if not accepted and not ownership_registered:
            continue
        if not accepted or not ownership_registered:
            return False
        if attempt.get("lease_id") != lease_id or attempt.get("lifecycle_ownership_protocol") != LIFECYCLE_OWNERSHIP_PROTOCOL:
            return False
        identity = dict_field(attempt, "identity")
        attempt_session = identity.get("session_id") or attempt.get("session_id")
        ide_pid = identity.get("pid")
        if attempt_session != session_id or not isinstance(ide_pid, int) or ide_pid <= 0:
            return False
        lease_port = lease.get("ide_port")
        identity_port = identity.get("port")
        if lease_port is not None and identity_port != lease_port:
            return False
        accepted_identities.append((str(attempt_session), ide_pid))
    if len(accepted_identities) != 1:
        return False
    return pid_definitively_dead(accepted_identities[0][1])


def timed_out_open_died_with_original_ide(
    lease: dict[str, Any],
    routes: list[dict[str, Any]],
    observed_sessions: set[str] | None,
) -> bool:
    if lease.get("opened_by_helper") is True or not original_session_gone_and_worktree_closed(lease, routes, observed_sessions):
        return False
    attempts = lease.get("open_attempts")
    if not isinstance(attempts, list):
        return False
    sent = [attempt for attempt in attempts if isinstance(attempt, dict) and attempt.get("identity")]
    if not sent or not project_open_was_not_requested([attempt for attempt in attempts if attempt not in sent]):
        return False
    session_id = lease.get("session_id")
    ide_pids: set[int] = set()
    for attempt in sent:
        identity = dict_field(attempt, "identity")
        ide_pid = identity.get("pid")
        if (attempt.get("accepted") is not False or attempt.get("request_may_have_been_accepted") is not True
                or identity.get("session_id") != session_id or identity.get("port") != lease.get("ide_port")
                or not isinstance(ide_pid, int)):
            return False
        ide_pids.add(ide_pid)
    return len(ide_pids) == 1 and pid_definitively_dead(ide_pids.pop())


def reclaim_lifecycle_claim(
    lease: dict[str, Any],
    route: dict[str, Any],
) -> tuple[bool | None, str | None, dict[str, Any], dict[str, Any]]:
    project_instance_id = lease.get("project_instance_id") or route.get("project_instance_id")
    if not project_instance_id:
        return None, None, {"status": "missing_project_instance"}, route
    claim = private_http_get_body(route_port(route), "lifecycle/claim", {
        "project_key": lease.get("project_key") or route.get("project_key"),
        "project_path": route.get("base_path"),
        "worktree_path": route.get("base_path"),
        "session_id": lease.get("session_id") or route.get("session_id"),
        "project_instance_id": project_instance_id,
        "lease_id": lease.get("lease_id"),
    })
    claimed_route = claim.get("route")
    if not isinstance(claimed_route, dict):
        claimed_route = route
    ensure_claim_route_matches(route, claimed_route)
    lease_target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
    claimed_path = claimed_route.get("base_path") or claimed_route.get("project_file_path")
    if lease_target and claimed_path and not paths_same(lease_target, claimed_path):
        raise InspectError(
            "Lifecycle ownership claim resolved a different path.",
            3,
            {"error_reason": "route_mismatch", "expected_path": lease_target, "actual_path": claimed_path},
        )
    ownership_proven, close_proof = lifecycle_claim_ownership(claim, lease)
    claim_metadata = {
        "status": claim.get("status") or "unknown",
        "ownership_proven": ownership_proven is True,
        "reason": claim.get("reason"),
        "lifecycle_ownership_protocol": claim.get("lifecycle_ownership_protocol"),
        "lease_id": lease.get("lease_id"),
    }
    return ownership_proven, close_proof, claim_metadata, claimed_route


def matching_route_for_ownership_probe(lease: dict[str, Any], routes: list[dict[str, Any]]) -> dict[str, Any] | None:
    lease_target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
    for route in routes:
        route_base = route.get("base_path") or route.get("project_file_path")
        if not paths_same(lease_target, route_base):
            continue
        if lease.get("session_id") and route.get("session_id") != lease.get("session_id"):
            continue
        return route
    return None


def matching_route_for_lease(lease: dict[str, Any], routes: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not lease_has_exact_route_identity(lease):
        return None
    for route in routes:
        if route.get("project_instance_id") != lease.get("project_instance_id"):
            continue
        if route.get("session_id") != lease.get("session_id"):
            continue
        if lease.get("project_key") and route.get("project_key") and route.get("project_key") != lease.get("project_key"):
            continue
        route_base = route.get("base_path") or route.get("project_file_path")
        lease_target = lease.get("lifecycle_target_path") or lease.get("worktree_root") or lease.get("repo_path")
        if route_base and lease_target and not paths_same(route_base, lease_target):
            continue
        return route
    return None


def configured_ports() -> list[int]:
    raw = os.environ.get("JETBRAINS_INSPECTION_PORTS")
    if not raw:
        return list(DEFAULT_PORT_RANGE)
    ports: list[int] = []
    for part in raw.split(","):
        item = part.strip()
        if not item:
            continue
        if "-" in item:
            start, end = item.split("-", 1)
            ports.extend(range(int(start), int(end) + 1))
        else:
            ports.append(int(item))
    return ports


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except OSError:
        return True


def pid_definitively_dead(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return False
    except ProcessLookupError:
        return True
    except OSError:
        return False


def flatten_project(identity: dict[str, Any], project: dict[str, Any]) -> dict[str, Any]:
    return {
        "session_id": identity.get("session_id"),
        "port": identity.get("port"),
        "ide_name": identity.get("ide_name"),
        "ide_product_code": identity.get("ide_product_code"),
        "plugin_version": identity.get("plugin_version"),
        "plugin_build_fingerprint": identity.get("plugin_build_fingerprint"),
        "project_key": project.get("project_key"),
        "project_instance_id": project.get("project_instance_id"),
        "name": project.get("name"),
        "base_path": project.get("base_path"),
        "project_file_path": project.get("project_file_path"),
        "focused": bool(project.get("focused")),
    }


def ensure_worktree_safe(route: dict[str, Any], context: dict[str, Any], args: argparse.Namespace) -> None:
    if args.no_worktree_check:
        return
    strategy = str(context.get("worktree_strategy") or "prefer-current")
    if strategy in {"allow-main", "allow-any"}:
        return
    route_base = route.get("base_path") or route.get("project_file_path")
    worktree_root = context.get("worktree_root")
    if not route_base or not worktree_root:
        raise InspectError(
            "Cannot verify the resolved JetBrains route against the current worktree.",
            3,
            {
                "error_reason": "route_path_missing",
                "route": route,
                "context": public_context(context),
            },
        )
    try:
        route_path = Path(route_base).resolve()
        worktree_path = Path(worktree_root).resolve()
    except OSError as error:
        raise InspectError(
            f"Cannot verify the resolved JetBrains route against the current worktree: {error}",
            3,
            {
                "error_reason": "route_path_invalid",
                "route": route,
                "context": public_context(context),
            },
        ) from error
    if route_path == worktree_path:
        return
    if worktree_path.is_relative_to(route_path) or route_path.is_relative_to(worktree_path):
        return
    raise InspectError(
        "Resolved JetBrains project is not the current worktree; refusing to inspect the wrong tree.",
        3,
        {"route_base_path": str(route_path), "worktree_root": str(worktree_path), "hint": "Open the current worktree in the preferred IDE or rerun with --no-worktree-check after approval."},
    )


def route_sort_key(route: dict[str, Any], context: dict[str, Any]) -> tuple[int, int, int]:
    route_base = route.get("base_path")
    exact_route_path = context.get("exact_route_path") or context.get("worktree_root")
    try:
        route_path = Path(str(route_base)).resolve() if route_base else None
        worktree_path = Path(str(exact_route_path)).resolve() if exact_route_path else None
    except OSError:
        route_path = None
        worktree_path = None

    exact = int(route_path is not None and worktree_path is not None and route_path == worktree_path)
    depth = len(route_path.parts) if route_path is not None else 0
    return int(route.get("score") or 0), exact, depth


def ensure_exact_worktree(route: dict[str, Any], context: dict[str, Any], args: argparse.Namespace) -> None:
    if args.no_worktree_check:
        return
    route_base = route.get("base_path")
    exact_route_path = context.get("exact_route_path") or context.get("worktree_root")
    if not route_base or not exact_route_path:
        raise InspectError("Cannot verify exact worktree route; route or worktree path is missing.", 3, {"route": route, "context": public_context(context)})
    try:
        route_path = Path(route_base).resolve()
        worktree_path = Path(exact_route_path).resolve()
    except OSError as error:
        raise InspectError(f"Cannot verify exact worktree route: {error}", 3, {"route": route, "context": public_context(context)}) from error
    if route_path != worktree_path:
        raise InspectError(
            "Lifecycle inspection requires the exact current worktree to be open in the IDE.",
            3,
            {"route_base_path": str(route_path), "worktree_root": str(worktree_path)},
        )


def bootstrap_ide_app(context: dict[str, Any], background: bool = True) -> dict[str, Any]:
    ide_app = resolved_ide_app_name(context)
    ide_app_path = resolved_ide_app_path(context)
    if not (ide_app or ide_app_path) or sys.platform != "darwin":
        raise InspectError("Cannot auto-open IDE without a configured macOS IDE name.", 3)
    refuse_launch_while_app_runs(context)
    command = ["open"]
    if background:
        command.extend(["-g", "-j"])
    command.extend(["-a", str(ide_app_path or ide_app)])
    # JetBrains restores its cwd from PWD after LaunchServices starts it in /.
    environment = {**os.environ, "PWD": "/"}
    completed = subprocess.run(command, env=environment, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        raise InspectError(
            "Failed to launch the JetBrains IDE for lifecycle open.",
            3,
            {
                "command": command,
                "returncode": completed.returncode,
                "stdout": completed.stdout.strip(),
                "stderr": completed.stderr.strip(),
                "ide": context.get("ide"),
                "ide_app": ide_app,
                "ide_app_path": str(ide_app_path) if ide_app_path else None,
                "worktree_root": context.get("worktree_root"),
                "background_open": background,
                "hint": "Check the configured JetBrains app name/path; product metadata uses the latest stable app, while exact EAP/version selection may require jetbrains.ideApp.",
            },
        )
    return {
        "method": "bootstrap_ide",
        "accepted": True,
        "target_worktree": lifecycle_target_path(context),
        "requested_ide": context.get("ide"),
        "ide_app": ide_app,
        "ide_app_path": str(ide_app_path) if ide_app_path else None,
        "background_open": background,
        "command": command,
    }


def refuse_launch_while_app_runs(context: dict[str, Any]) -> None:
    product = launch_guard_product(context)
    if product is None:
        raise InspectError(
            "Cannot identify the selected JetBrains product's launcher; refusing to launch it.",
            3,
            {"error_reason": "ide_launch_refused", "ide": context.get("ide"), "ide_app": resolved_ide_app_name(context)},
        )
    try:
        pids = ide_launcher_pids(product.launcher_names)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise InspectError(
            "Cannot prove the selected JetBrains app is not running; refusing to launch it.",
            3,
            {"error_reason": "ide_launch_refused", "process_census_error": str(error)},
        ) from error
    if pids:
        raise InspectError(
            "The selected JetBrains app is already running without a usable Inspection API identity; refusing to launch or activate it.",
            3,
            {"error_reason": "ide_launch_refused", "running_app_pids": pids},
        )


def launch_guard_product(context: dict[str, Any]) -> IdeProduct | None:
    selection = dict_field(context, "ide_selection")
    return (
        IDE_PRODUCTS.get(str(selection.get("product_key")))
        or product_for_selector(resolved_ide_app_name(context))
        or product_for_selector(clean_optional(context.get("ide")))
    )


def resolved_ide_app_name(context: dict[str, Any]) -> str | None:
    selection = dict_field(context, "ide_selection")
    return clean_optional(selection.get("app_name")) or clean_optional(context.get("ide_app")) or clean_optional(context.get("ide"))


def resolved_ide_app_path(context: dict[str, Any]) -> Path | None:
    selection = dict_field(context, "ide_selection")
    value = selection.get("app_path") or context.get("ide_app_path")
    return Path(str(value)).expanduser() if value else None


def parse_json(raw: bytes) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as error:
        raise InspectError(f"Inspection API returned invalid JSON: {error}", 3)
    if not isinstance(value, dict):
        raise InspectError("Inspection API returned non-object JSON.", 3)
    return value


if __name__ == "__main__":
    sys.exit(main())
