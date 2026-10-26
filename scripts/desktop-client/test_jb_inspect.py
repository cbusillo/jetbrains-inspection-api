#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
import http.client
import importlib.util
import io
import json
import os
import plistlib
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse
import urllib.request
from argparse import Namespace
from contextlib import chdir, redirect_stderr, redirect_stdout
from pathlib import Path
from typing import Any, cast
from unittest.mock import ANY, MagicMock, patch


def patch_attr(target: Any, name: str, *args: Any, **kwargs: Any) -> Any:
    return patch.object(target, name, *args, **kwargs)


TEST_CACHE = tempfile.TemporaryDirectory(prefix="jetbrains-inspection-tests-")
os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = TEST_CACHE.name
os.environ["JETBRAINS_INSPECTION_REGISTRY_DIR"] = str(Path(TEST_CACHE.name) / "registry")
os.environ["JB_INSPECT_OUTCOME_LOG"] = "0"
os.environ["JB_INSPECT_UNKNOWN_LOG"] = "0"
os.environ["JB_INSPECT_DEFAULT_BACKEND"] = "api"
SCRIPT_PATH = Path(__file__).resolve().parent / "jb-inspect.py"
ATTRIBUTION_FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "attribution" / "cases.json"
SPEC = importlib.util.spec_from_file_location("jb_inspect", SCRIPT_PATH)
assert SPEC is not None
jb_inspect = cast(Any, importlib.util.module_from_spec(SPEC))
assert SPEC.loader is not None
sys.modules["jb_inspect"] = jb_inspect
SPEC.loader.exec_module(jb_inspect)
NO_LIVE_IDE = patch_attr(
    jb_inspect.urllib.request, "urlopen", side_effect=jb_inspect.urllib.error.URLError(ConnectionRefusedError("connection refused"))
)


def setUpModule() -> None:
    NO_LIVE_IDE.start()


def tearDownModule() -> None:
    NO_LIVE_IDE.stop()


def write_json(path: Path, payload: dict) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle)



class IdeLaunchCwdTests(unittest.TestCase):
    def test_live_fake_ide_does_not_hold_task_cwd_after_project_close(self):
        # LaunchServices starts apps in /; JetBrains restores cwd from inherited PWD.
        # Only app launching is faked; the IDE is a real child process.
        child_code = """
import json, os, sys
if os.getcwd() == "/" and os.environ.get("PWD"):
    os.chdir(os.environ["PWD"])
print(json.dumps({"cwd": os.getcwd(), "project": sys.argv[1], "other": os.environ.get("CS1159_LAUNCH_TEST")}), flush=True)
for command in sys.stdin:
    if command.strip() == "close":
        print(json.dumps({"cwd": os.getcwd(), "project": None}), flush=True)
    elif command.strip() == "stop":
        break
"""
        for launch in (jb_inspect.bootstrap_ide_app,):
            with self.subTest(launch=launch.__name__), tempfile.TemporaryDirectory() as temporary:
                task = Path(temporary).resolve() / "disposable-task"
                task.mkdir()
                processes = []
                observations = []

                def fake_open(command, **kwargs):
                    process = subprocess.Popen(
                        [sys.executable, "-u", "-c", child_code, str(task)],
                        cwd="/", env=kwargs.get("env"),
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True,
                    )
                    processes.append(process)
                    assert process.stdout is not None
                    observations.append(json.loads(process.stdout.readline()))
                    return subprocess.CompletedProcess(command, 0, "", "")

                try:
                    with chdir(task), patch.dict(os.environ, {"PWD": str(task), "CS1159_LAUNCH_TEST": "preserved"}), \
                            patch.object(sys, "platform", "darwin"), \
                            patch_attr(jb_inspect, "ide_launcher_pids", return_value=[]), \
                            patch.object(subprocess, "run", side_effect=fake_open):
                        result = launch({"ide": "PyCharm", "worktree_root": str(task)}, background=True)
                        self.assertEqual(Path.cwd(), task)
                        self.assertEqual(os.environ["PWD"], str(task))
                        self.assertEqual(observations[0]["other"], os.environ["CS1159_LAUNCH_TEST"])
                    process = processes[0]
                    assert process.stdin is not None and process.stdout is not None
                    self.assertTrue(result["accepted"])
                    self.assertEqual(observations[0]["project"], str(task))
                    process.stdin.write("close\n")
                    process.stdin.flush()
                    closed = json.loads(process.stdout.readline())
                    self.assertIsNone(closed["project"])
                    self.assertIsNone(process.poll())
                    for observation in (observations[0], closed):
                        self.assertFalse(Path(observation["cwd"]).is_relative_to(task))
                finally:
                    for process in processes:
                        process.communicate("stop\n", timeout=5)
                        self.assertEqual(process.returncode, 0)


class IdeMemoryTests(unittest.TestCase):
    @staticmethod
    def identity():
        return {"port": 63342, "session_id": "current", "ide_memory_diagnostic_version": 1}

    @staticmethod
    def response(status="exhausted", session="current"):
        return jb_inspect.HttpResult(200, {"session_id": session, "ide_memory": {
            "status": status, "out_of_memory_at_ms": 1234,
        }}, "http://fixture")

    def test_trigger_stops_before_inspection_request(self):
        with patch_attr(jb_inspect, "http_get", return_value=self.response()) as get:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.call_endpoint(self.identity(), "trigger", {})
        self.assertEqual(raised.exception.payload["error_reason"], "ide_memory_exhausted")
        self.assertEqual(get.call_args.args[1], "memory")
        get.assert_called_once()

    def test_deferred_memory_failure_guidance_includes_retained_lease_cleanup(self):
        payload = jb_inspect.apply_verdict({
            "status": "error", "error_reason": "ide_memory_exhausted",
            "cleanup": {"status": "deferred"},
        })
        action = payload["agent_result"]["next_action"]
        self.assertIn("restart the IDE", action)
        self.assertIn("cleanup-helper-leases", action)
        self.assertFalse(payload["retry_policy"]["retry"])

    def test_failure_is_classified_using_current_session_memory(self):
        failure = jb_inspect.InspectError("timed out", 3, {"error_reason": "inspection_api_timeout", "endpoint": "wait"})
        with patch_attr(jb_inspect, "execute_inspection_on_route", side_effect=failure), \
                patch_attr(jb_inspect, "http_get", return_value=self.response()):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.run_inspection_on_route(Namespace(), {}, self.identity())
        self.assertEqual(raised.exception.payload["error_reason"], "ide_memory_exhausted")
        self.assertEqual(raised.exception.payload["original_failure"]["error_reason"], "inspection_api_timeout")
        self.assertEqual(raised.exception.payload["original_failure"]["endpoint"], "wait")

    def test_unavailable_or_different_session_memory_preserves_original_failure(self):
        failure = jb_inspect.InspectError("timed out", 3, {"error_reason": "inspection_api_timeout"})
        for diagnostic in (failure, ConnectionResetError("IDE dropped connection"), jb_inspect.http.client.RemoteDisconnected("no response"), self.response(session="restarted"), self.response(status="normal")):
            with self.subTest(diagnostic=diagnostic), \
                    patch_attr(jb_inspect, "execute_inspection_on_route", side_effect=failure), \
                    patch_attr(jb_inspect, "http_get", side_effect=[diagnostic]):
                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.run_inspection_on_route(Namespace(), {}, self.identity())
                self.assertIs(raised.exception, failure)

    def test_trigger_preflight_closes_helper_owned_project_without_transport_uncertainty(self):
        route = self.identity()
        lease = {"lease_id": "owned", "opened_by_helper": True, "state": "prepared"}
        snapshot = {"status": "ok", "entries": {}}
        with patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": route}, lease, "proof")), \
                patch_attr(jb_inspect, "http_get", return_value=self.response()), \
                patch_attr(jb_inspect, "cleanup_lifecycle", return_value={"status": "closed"}) as close, \
                patch_attr(jb_inspect, "git_worktree_status_snapshot", return_value=snapshot), \
                patch_attr(jb_inspect, "post_cleanup_worktree_status_snapshot", return_value=snapshot):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.run_prepared_inspection(helper_args(command="closeout", lifecycle_lock_timeout_ms=1000), {})
        close.assert_called_once_with(lease, route, "proof")
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "closed")
        self.assertTrue(raised.exception.payload["inspection_failure"]["trigger_not_sent"])
        self.assertFalse(raised.exception.payload["inspection_failure"]["transport_state_unknown"])

    def test_problems_server_error_is_classified_after_preserving_response(self):
        failure = jb_inspect.InspectError("HTTP 500", 3, {"http_status": 500, "endpoint": "problems", "error_reason": "inspection_api_http_error"})
        result = jb_inspect.inspection_endpoint_failure_result(failure, self.identity(), {"run_id": 7}, {}, None)
        with patch_attr(jb_inspect, "execute_inspection_on_route", return_value=result), \
                patch_attr(jb_inspect, "http_get", return_value=self.response()):
            observed = jb_inspect.run_inspection_on_route(Namespace(), {}, self.identity())
        self.assertEqual(observed["agent_result"]["bucket"], "ide_memory_exhausted")
        self.assertEqual(observed["http_status"], 500)
        self.assertEqual(observed["endpoint"], "problems")
        self.assertEqual(observed["original_failure_reason"], "inspection_api_http_error")

    def test_readiness_timeout_names_current_oom_and_preserves_failure(self):
        route = self.identity() | {"base_path": "/fixture", "project_key": "path:/fixture", "project_instance_id": "current:1"}
        lease = {"lease_id": "owned", "state": "preparing"}
        failure = jb_inspect.InspectError("not ready", 3, {"error_reason": "ide_not_ready_timeout"})
        with patch_attr(jb_inspect, "run_repository_preparation", return_value={}), \
                patch_attr(jb_inspect, "create_local_lease", return_value=lease), \
                patch_attr(jb_inspect, "find_exact_route", return_value=route), \
                patch_attr(jb_inspect, "matching_deferred_lease", return_value=None), \
                patch_attr(jb_inspect, "ensure_exact_worktree"), \
                patch_attr(jb_inspect, "claim_lifecycle", return_value=unowned_lifecycle_result(route)), \
                patch_attr(jb_inspect, "wait_until_route_ready", side_effect=failure), \
                patch_attr(jb_inspect, "http_get", return_value=self.response()), \
                patch_attr(jb_inspect, "write_lease"), \
                patch_attr(jb_inspect, "remove_lease"):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(helper_args(open=True), {"worktree_root": "/fixture"})
        self.assertEqual(raised.exception.payload["error_reason"], "ide_memory_exhausted")
        self.assertEqual(raised.exception.payload["original_failure_reason"], "ide_not_ready_timeout")
        self.assertEqual(raised.exception.payload["cleanup"]["reason"], "project_preexisted")

    def test_retry_refusal_retains_first_attempt_evidence_and_transport_uncertainty(self):
        first = jb_inspect.apply_verdict({"status": "stale_results", "results_may_be_stale": True, "transport_state_unknown": True})
        refusal = jb_inspect.InspectError("memory exhausted", 3, {"error_reason": "ide_memory_exhausted", "trigger_not_sent": True})
        with patch_attr(jb_inspect, "run_inspection_on_route", side_effect=[first, refusal]), \
                patch_attr(jb_inspect, "wait_for_internal_retry_readiness", return_value={"ready": True}):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.run_inspection_with_internal_retry(Namespace(), {}, self.identity())
        self.assertEqual(raised.exception.payload["internal_retries"][0]["verdict_reason"], "stale_results")
        self.assertEqual(raised.exception.payload["internal_retry_readiness"], {"ready": True})
        self.assertTrue(jb_inspect.inspection_exception_result(raised.exception)["transport_state_unknown"])

    def test_wait_timeout_cancels_owned_run_before_memory_classification(self):
        route = self.identity()
        events = []
        def call(_route, endpoint, _params, **_kwargs):
            events.append(endpoint)
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {"status": "timed_out", "timed_out": True, "inspection_run_id": 7, "inspection_in_progress": True}
            if endpoint == "problems":
                return {"status": "inspection_in_progress", "inspection_run_id": 7, "problems": [], "total_problems": 0}
            self.fail(endpoint)
        def cancel(*_args, **kwargs):
            self.assertTrue(kwargs["owns_run"])
            self.assertEqual(kwargs["expected_run_id"], 7)
            events.append("cancel")
            return {"status": "cancelled", "requested": True}
        def memory(*_args, **_kwargs):
            events.append("memory")
            return self.response()
        with patch_attr(jb_inspect, "call_endpoint", side_effect=call), \
                patch_attr(jb_inspect, "cancel_timed_out_inspection", side_effect=cancel), \
                patch_attr(jb_inspect, "http_get", side_effect=memory):
            result = jb_inspect.run_inspection_on_route(helper_args(timeout_ms=1000, poll_ms=1), {}, route)
        self.assertEqual(events, ["trigger", "wait", "cancel", "problems", "memory"])
        self.assertEqual(result["agent_result"]["bucket"], "ide_memory_exhausted")
        self.assertEqual(result["cancellation"]["status"], "cancelled")
        self.assertEqual(result["original_failure_reason"], "timeout")
        self.assertFalse(result["retry_policy"]["retry"])
        self.assertIn("Java heap space", result["agent_result"]["next_action"])
        self.assertIn("restart the IDE", result["agent_result"]["next_action"])

    def test_session_drift_retains_reason_under_memory_pressure(self):
        failure = jb_inspect.InspectError("session changed", 3, {"error_reason": "session_drift", "http_status": 409})
        with patch_attr(jb_inspect, "execute_inspection_on_route", side_effect=failure), \
                patch_attr(jb_inspect, "http_get", return_value=self.response(status="low_memory")):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.run_inspection_on_route(Namespace(), {}, self.identity())
        self.assertIs(raised.exception, failure)
        self.assertEqual(failure.payload["error_reason"], "session_drift")
        self.assertEqual(failure.payload["ide_memory"]["status"], "low_memory")

    def test_memory_preflight_releases_lease_without_any_project_open(self):
        lease = {"lease_id": "owned", "state": "preparing"}
        with patch_attr(jb_inspect, "run_repository_preparation", return_value={}), \
                patch_attr(jb_inspect, "create_local_lease", return_value=lease), \
                patch_attr(jb_inspect, "find_exact_route", return_value=None), \
                patch_attr(jb_inspect, "ensure_trusted_auto_open_root"), \
                patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"), \
                patch_attr(jb_inspect, "discover_open_identities", return_value=[self.identity()]), \
                patch_attr(jb_inspect, "identity_matches_context", return_value=True), \
                patch_attr(jb_inspect, "http_get", return_value=self.response()), \
                patch_attr(jb_inspect, "write_lease"), \
                patch_attr(jb_inspect, "remove_lease") as remove:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(helper_args(open=True), {"worktree_root": "/fixture"})
        self.assertEqual(raised.exception.payload["cleanup"], {"status": "not_needed", "reason": "open_not_attempted"})
        remove.assert_called_once_with(lease)

    def test_open_probe_ignores_memory_in_another_session(self):
        identity = self.identity() | {"lifecycle_open_diagnostic_version": 1}
        with patch_attr(jb_inspect, "discover_open_identities", return_value=[identity]), \
                patch_attr(jb_inspect, "identity_matches_context", return_value=True), \
                patch_attr(jb_inspect, "http_get", return_value=jb_inspect.HttpResult(200, {"status": "opening"}, "http://fixture")) as get:
            result = jb_inspect.probe_lifecycle_open(Namespace(), {"worktree_root": "/fixture"}, {"session_id": "healthy", "ide_port": 63343})
        self.assertEqual(result["status"], "opening")
        self.assertEqual(get.call_args.args[1], "lifecycle/open")
        get.assert_called_once()

    def test_bootstrap_only_memory_refusal_releases_lease_but_preserves_ambiguous_attempts(self):
        bootstrap = [{"method": "running_ide", "accepted": False, "reason": "no_matching_running_ide"}, {"method": "bootstrap_ide", "accepted": True}]
        ambiguous = [{"method": "bootstrap_ide", "accepted": True, "ownership_registered": True}]
        for attempts in (bootstrap, ambiguous):
            lease = {"lease_id": "owned", "state": "open_requesting", "opened_by_helper": False, "open_attempts": attempts}
            with patch_attr(jb_inspect, "discover_open_identities", return_value=[self.identity()]), \
                    patch_attr(jb_inspect, "identity_matches_context", return_value=True), \
                    patch_attr(jb_inspect, "http_get", return_value=self.response()):
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.open_via_running_ide(Namespace(port=None), {}, attempts, "bootstrapped_ide", lease)
            with patch_attr(jb_inspect, "remove_lease") as remove:
                if attempts is bootstrap:
                    result = jb_inspect.cleanup_failed_preparation(lease, None, None, jb_inspect.InspectError("memory", 3), "project_open")
                    self.assertEqual(result, {"status": "not_needed", "reason": "open_not_attempted"})
                    remove.assert_called_once_with(lease)
                else:
                    self.assertTrue(jb_inspect.lease_may_own_open_project(lease))
                    remove.assert_not_called()

    def test_older_plugin_does_not_probe_or_change_request(self):
        with patch_attr(jb_inspect, "http_get", return_value=jb_inspect.HttpResult(200, {"status": "ok"}, "http://fixture")) as get:
            self.assertEqual(jb_inspect.call_endpoint({"port": 63342}, "trigger", {}), {"status": "ok"})
        self.assertEqual(get.call_args.args[1], "trigger")
        get.assert_called_once()

    def test_pressure_does_not_block_trigger_or_change_unknown_retry_policy(self):
        response = jb_inspect.HttpResult(200, {"status": "triggered"}, "http://fixture")
        with patch_attr(jb_inspect, "http_get", side_effect=[self.response(status="low_memory"), response]) as get:
            self.assertEqual(jb_inspect.call_endpoint(self.identity(), "trigger", {}), response.body)
        self.assertEqual(get.call_count, 2)
        original = jb_inspect.apply_verdict({"status": "timed_out", "timed_out": True})
        expected_retry = dict(original["retry_policy"])
        with patch_attr(jb_inspect, "execute_inspection_on_route", return_value=original), \
                patch_attr(jb_inspect, "http_get", return_value=self.response(status="low_memory")):
            result = jb_inspect.run_inspection_on_route(Namespace(), {}, self.identity())
        self.assertEqual(result["verdict_reason"], "timeout")
        self.assertEqual(result["retry_policy"], expected_retry)
        self.assertEqual(result["ide_memory"]["status"], "low_memory")

    def test_open_probe_names_oom_only_in_the_accepting_session(self):
        identity = self.identity() | {"lifecycle_open_diagnostic_version": 1}
        lease = {"session_id": identity["session_id"], "ide_port": identity["port"]}
        for status in ("exhausted", "low_memory"):
            with self.subTest(status=status), \
                    patch_attr(jb_inspect, "discover_open_identities", return_value=[identity]), \
                    patch_attr(jb_inspect, "identity_matches_context", return_value=True), \
                    patch_attr(jb_inspect, "http_get", side_effect=[self.response(status=status), jb_inspect.HttpResult(200, {"status": "opening"}, "http://fixture")]) as get:
                if status == "exhausted":
                    with self.assertRaises(jb_inspect.InspectError) as raised:
                        jb_inspect.probe_lifecycle_open(Namespace(), {"worktree_root": "/fixture"}, lease)
                    self.assertEqual(raised.exception.payload["error_reason"], "ide_memory_exhausted")
                    self.assertEqual(get.call_count, 1)
                else:
                    self.assertEqual(jb_inspect.probe_lifecycle_open(Namespace(), {"worktree_root": "/fixture"}, lease)["status"], "opening")
                    self.assertEqual(get.call_count, 2)


class ProjectCacheRetirementTests(unittest.TestCase):
    def test_exact_session_cache_receipt_is_required_before_git_removal(self):
        for drift in (False, True):
            with self.subTest(drift=drift), tempfile.TemporaryDirectory() as temporary:
                primary = Path(temporary) / "primary"
                root = Path(temporary) / "task"
                subprocess.run(["git", "init", "-q", str(primary)], check=True)
                subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c",
                                "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "fixture"], check=True)
                subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
                identity = {"port": 12345, "session_id": "current", "helper_project_cache_lifecycle_version": 1}
                cache_retired = []
                def post(_port, endpoint, params, timeout):
                    self.assertGreater(timeout, 0)
                    self.assertEqual(endpoint, "lifecycle/retire-project-caches")
                    self.assertTrue(root.exists())
                    dry_run = params["dry_run"] == "true"
                    status = "would_remove" if dry_run else "removed"
                    if cache_retired:
                        entries = []
                    else:
                        entries = [{"status": status, "reason": "helper_owned_closed_project", "worktree_path": str(root)}]
                    if not dry_run:
                        cache_retired.append(True)
                    return jb_inspect.HttpResult(200, {
                        "status": "ok", "session_id": "foreign" if drift else "current",
                        "project_cache_lifecycle_version": 1, "dry_run": dry_run,
                        "worktree_path": str(root), "caches": entries,
                    }, "http://fixture")
                args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
                with (patch_attr(jb_inspect, jb_inspect.discover_identities.__name__, return_value=[identity]),
                      patch_attr(jb_inspect, jb_inspect.unregister_helper_sdks.__name__, return_value=[]),
                      patch_attr(jb_inspect, jb_inspect.http_post.__name__, side_effect=post)):
                    if drift:
                        with self.assertRaises(jb_inspect.InspectError):
                            jb_inspect.command_retire_sdks(args)
                        self.assertTrue(root.is_dir())
                        self.assertEqual(cache_retired, [])
                    else:
                        result = jb_inspect.command_retire_sdks(args)
                        self.assertTrue(result["worktree_removed"])
                        self.assertFalse(root.exists())
                        self.assertEqual(cache_retired, [True])

    def test_slow_cache_response_completes_and_later_transport_failure_keeps_earlier_receipt(self):
        root = Path("/fixture/worktree")
        first = {"port": 12345, "session_id": "first", "helper_project_cache_lifecycle_version": 1}
        second = {"port": 12346, "session_id": "second", "helper_project_cache_lifecycle_version": 1}
        server_elapsed = 1.0
        def post(port, _endpoint, params, timeout=0.1):
            if timeout < server_elapsed or port == second["port"]:
                raise jb_inspect.InspectError("transport deadline", 3, {"error_reason": "inspection_api_timeout"})
            return jb_inspect.HttpResult(200, {"status": "ok", "session_id": params["session_id"],
                "project_cache_lifecycle_version": 1, "dry_run": False, "worktree_path": str(root),
                "caches": [{"status": "removed", "reason": "helper_owned_closed_project", "worktree_path": str(root)}]}, "http://fixture")
        with patch_attr(jb_inspect, jb_inspect.http_post.__name__, side_effect=post):
            completed = jb_inspect.retire_helper_project_caches([first], root, False)
            self.assertEqual(completed[0]["result"]["caches"][0]["status"], "removed")
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.retire_helper_project_caches([first, second], root, False)
        self.assertEqual(raised.exception.payload["completed_project_cache_cleanup"], completed)

    def test_command_preserves_completed_cache_receipts_from_a_failed_apply(self):
        root = Path("/fixture/worktree")
        completed = [{"ide": {"session_id": "first"}, "result": {"caches": [{"status": "removed"}]}}]
        failure = jb_inspect.InspectError("later IDE disconnected", 3, {"completed_project_cache_cleanup": completed})
        args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
        with (patch_attr(jb_inspect, jb_inspect.retirement_worktree.__name__, return_value=root),
              patch_attr(jb_inspect, jb_inspect.git_common_worktree.__name__, return_value=root),
              patch_attr(jb_inspect, jb_inspect.discover_identities.__name__, return_value=[]),
              patch_attr(jb_inspect, jb_inspect.unregister_helper_sdks.__name__, return_value=[]),
              patch_attr(jb_inspect, jb_inspect.retire_helper_project_caches.__name__, side_effect=[[], failure])):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.command_retire_sdks(args)
        self.assertEqual(raised.exception.payload["completed_project_cache_cleanup"], completed)

    def test_verification_and_filesystem_failures_preserve_successful_cache_applies(self):
        root = Path("/fixture/worktree")
        applied = [{"ide": {"session_id": "first"}, "result": {"caches": [{"status": "removed"}]}}]
        for phase in ("verification", "filesystem"):
            with self.subTest(phase=phase):
                verification = jb_inspect.InspectError("verification disconnected", 3,
                    {"completed_project_cache_preview": []}) if phase == "verification" else []
                args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
                with (patch_attr(jb_inspect, jb_inspect.retirement_worktree.__name__, return_value=root),
                      patch_attr(jb_inspect, jb_inspect.git_common_worktree.__name__, return_value=root),
                      patch_attr(jb_inspect, jb_inspect.discover_identities.__name__, return_value=[]),
                      patch_attr(jb_inspect, jb_inspect.unregister_helper_sdks.__name__, return_value=[]),
                      patch_attr(jb_inspect, jb_inspect.retire_helper_project_caches.__name__, side_effect=[[], applied, verification]),
                      patch_attr(jb_inspect, jb_inspect.worktree_entries.__name__, side_effect=OSError("filesystem unavailable"))):
                    with self.assertRaises(jb_inspect.InspectError) as raised:
                        jb_inspect.command_retire_sdks(args)
                self.assertEqual(raised.exception.payload["completed_project_cache_cleanup"], applied)

    def test_raw_disconnect_and_http_protocol_failure_keep_completed_receipts(self):
        root = Path("/fixture/worktree")
        identities = [{"port": port, "session_id": str(port), "helper_project_cache_lifecycle_version": 1}
                      for port in (12345, 12346)]
        success = jb_inspect.HttpResult(200, {"status": "ok", "session_id": str(identities[0]["port"]),
            "project_cache_lifecycle_version": 1, "dry_run": False, "worktree_path": str(root),
            "caches": [{"status": "removed", "reason": "helper_owned_closed_project", "worktree_path": str(root)}]}, "http://fixture")
        for failure in (http.client.RemoteDisconnected("IDE exited"), http.client.BadStatusLine("invalid response")):
            with self.subTest(failure=type(failure).__name__), patch_attr(jb_inspect, jb_inspect.http_post.__name__, side_effect=[success, failure]):
                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.retire_helper_project_caches(identities, root, False)
                receipts = raised.exception.payload["completed_project_cache_cleanup"]
                self.assertEqual(receipts[0]["result"]["caches"][0]["status"], "removed")
                self.assertEqual(receipts[0]["ide"]["session_id"], identities[0]["session_id"])


class OfflineIdeProcessTests(unittest.TestCase):
    def test_running_ide_is_visible_without_a_plugin_registry(self):
        process_list = "123 /Applications/PyCharm.app/Contents/MacOS/pycharm\n124 /bin/sh\n"
        with patch.object(subprocess, "check_output", return_value=process_list):
            self.assertEqual(jb_inspect.offline_ide_processes(), [123])
        with patch.object(subprocess, "check_output", side_effect=OSError("fixture")):
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.offline_ide_processes()


class SdkRetirementTests(unittest.TestCase):
    def setUp(self):
        processes = patch_attr(jb_inspect, "offline_ide_processes", return_value=[])
        processes.start()
        self.addCleanup(processes.stop)

    def test_running_ide_without_identity_holds_offline_retirement(self):
        with (patch.object(sys, "platform", "darwin"),
              patch_attr(jb_inspect, "offline_ide_processes", return_value=[123])):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.offline_retirement_records(Path("/fixture/task"))
            self.assertEqual(caught.exception.payload["offline_retirement_ide_pids"], [123])

    def test_explicit_offline_retirement_retains_worktree_when_an_ide_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            args = Namespace(command="remove-worktree", repo=str(root), dry_run=False,
                             offline_only=True, lifecycle_lock_timeout_ms=1000)
            with (patch.object(sys, "platform", "darwin"),
                  patch_attr(jb_inspect, "retirement_worktree", return_value=root),
                  patch_attr(jb_inspect, "offline_ide_processes", return_value=[123]),
                  patch_attr(jb_inspect, "discover_identities", return_value=[]) as discover,
                  patch_attr(jb_inspect, "http_post") as post,
                  patch.object(subprocess, "run") as removal):
                with self.assertRaises(jb_inspect.InspectError) as caught:
                    jb_inspect.command_retire_sdks(args)
                self.assertEqual(caught.exception.payload["offline_retirement_ide_pids"], [123])
                discover.assert_not_called()
                post.assert_not_called()
                removal.assert_not_called()
                self.assertTrue(root.is_dir())

    def test_offline_records_hold_registered_sdks_projects_and_caches(self):
        for kind in ("jdk.table.xml", jb_inspect.HELPER_SDK_RECORD_FILENAME, "recentProjects.xml", "cache", "finalizing", "idea", "receipt"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                home = Path(tmp).resolve()
                root = home / "task"
                root.mkdir()
                with (patch_attr(jb_inspect.Path, "home", return_value=home),
                      patch.object(sys, "platform", "darwin"),
                      patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""})):
                    if kind in {"cache", "finalizing"}:
                        projects = home / "Library/Caches/JetBrains/PyCharm2026.2/projects"
                        record = (projects / f"{jb_inspect.HELPER_FINALIZING_CACHE_PREFIX}id.json" if kind == "finalizing" else
                                  projects / f"{jb_inspect.HELPER_SEALED_CACHE_PREFIX}id" / jb_inspect.HELPER_PROJECT_CACHE_MARKER)
                        record.parent.mkdir(parents=True)
                        record.write_text(json.dumps({"worktree_path": str(root)}))
                    elif kind == "idea":
                        (root / ".idea").mkdir()
                        (root / ".idea/workspace.xml").write_text("<project />")
                    elif kind == "receipt":
                        record = jb_inspect.repository_preparation_receipt_path({"worktree_root": str(root)})
                        record.parent.mkdir(parents=True, exist_ok=True)
                        record.write_text("{}")
                        self.addCleanup(record.unlink)
                    else:
                        record = home / "Library/Application Support/JetBrains/PyCharm2026.2/options" / kind
                        record.parent.mkdir(parents=True)
                        record.write_text(f'<application><option value="{root}/.venv/bin/python" /></application>')
                    args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
                    with (patch_attr(jb_inspect, "retirement_worktree", return_value=root),
                          patch_attr(jb_inspect, "discover_identities", return_value=[]),
                          patch.object(subprocess, "run") as removal):
                        with self.assertRaises(jb_inspect.InspectError) as caught:
                            jb_inspect.command_retire_sdks(args)
                        self.assertEqual(caught.exception.payload["offline_retirement_record"],
                                         str(root / ".idea/workspace.xml") if kind == "idea" else str(record))
                        removal.assert_not_called()
                        self.assertTrue(root.is_dir())

    def test_offline_record_scan_preserves_scope_and_fails_closed_on_unreadable_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            root = home / "task"
            options = home / "Library/Application Support/JetBrains/PyCharm2026.2/options"
            options.mkdir(parents=True)
            (home / "another-config").mkdir()
            table = options / "jdk.table.xml"
            with (patch_attr(jb_inspect.Path, "home", return_value=home),
                  patch.object(sys, "platform", "darwin"),
                  patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": str(home / "another-config")})):
                table.write_text('<application><homePath value="$USER_HOME$/task-other/.venv/bin/python" /></application>')
                jb_inspect.offline_retirement_records(root)
                table.write_text('<application><homePath value="$USER_HOME$/task/.venv/bin/python" /></application>')
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.offline_retirement_records(root)
                table.write_text("broken xml")
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.offline_retirement_records(root)
                with patch_attr(jb_inspect.Path, "iterdir", side_effect=PermissionError("fixture")):
                    with self.assertRaises(jb_inspect.InspectError):
                        jb_inspect.offline_retirement_records(root)

    def test_custom_storage_and_unresolved_cache_ownership_require_live_cleanup(self):
        for kind in ("custom-storage", "missing-cache-marker", "bad-cache-marker"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                home = Path(tmp).resolve()
                with (patch_attr(jb_inspect.Path, "home", return_value=home),
                      patch.object(sys, "platform", "darwin"),
                      patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""})):
                    if kind == "custom-storage":
                        config = home / "Library/Application Support/JetBrains/PyCharm2026.2"
                        config.mkdir(parents=True)
                        (config / "idea.properties").write_text("idea.system.path=/custom/cache\n")
                    else:
                        cache = home / "Library/Caches/JetBrains/PyCharm2026.2/projects" / f"{jb_inspect.HELPER_SEALED_CACHE_PREFIX}id"
                        cache.mkdir(parents=True)
                        if kind == "bad-cache-marker":
                            (cache / jb_inspect.HELPER_PROJECT_CACHE_MARKER).write_text("{}")
                    with self.assertRaises(jb_inspect.InspectError) as caught:
                        jb_inspect.offline_retirement_records(home / "task")
                    expected = config / "idea.properties" if kind == "custom-storage" else cache / jb_inspect.HELPER_PROJECT_CACHE_MARKER
                    self.assertEqual(caught.exception.payload["offline_retirement_record"], str(expected))

    def test_offline_cache_inventory_ignores_ordinary_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            projects = home / "Library/Caches/JetBrains/PyCharm2026.2/projects"
            projects.mkdir(parents=True)
            (projects / ".DS_Store").write_bytes(b"Finder metadata")
            (projects / "cache-index").write_text("ordinary cache metadata")
            with (patch_attr(jb_inspect.Path, "home", return_value=home),
                  patch.object(sys, "platform", "darwin"),
                  patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""})):
                jb_inspect.offline_retirement_records(home / "task")

    def test_offline_apply_rechecks_records_after_preview(self):
        for offline_only, new_process in ((False, False), (True, False), (True, True)):
            with self.subTest(offline_only=offline_only, new_process=new_process):
                self.check_offline_apply_rechecks(offline_only, new_process)

    def check_offline_apply_rechecks(self, offline_only, new_process):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            root = home / "task"
            root.mkdir()
            calls = []
            def retire(_root, *_args):
                calls.append(True)
                if len(calls) == 2 and not new_process:
                    (root / ".idea").mkdir()
                    (root / ".idea/workspace.xml").write_text("<project />")
                return root
            args = Namespace(command="remove-worktree", repo=str(root), dry_run=False,
                             offline_only=offline_only, lifecycle_lock_timeout_ms=1000)
            with (patch_attr(jb_inspect.Path, "home", return_value=home),
                  patch.object(sys, "platform", "darwin"),
                  patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}),
                  patch_attr(jb_inspect, "retirement_worktree", side_effect=retire),
                  patch_attr(jb_inspect, "discover_identities", return_value=[]) as discover,
                  patch_attr(jb_inspect, "offline_ide_processes", side_effect=[[], [123]] if new_process else None,
                               return_value=[]),
                  patch.object(subprocess, "run") as removal):
                with self.assertRaises(jb_inspect.InspectError) as caught:
                    jb_inspect.command_retire_sdks(args)
                if new_process:
                    self.assertEqual(caught.exception.payload["offline_retirement_ide_pids"], [123])
                else:
                    self.assertEqual(caught.exception.payload["offline_retirement_record"], str(root / ".idea/workspace.xml"))
                if offline_only:
                    discover.assert_not_called()
                removal.assert_not_called()

    def test_unprepared_worktree_retires_without_live_ide_even_with_a_virtualenv(self):
        for virtualenv, transition, offline_only in ((False, None, False), (True, None, False),
                (False, "starts", False), (False, "stops", False), (False, None, True), (True, None, True)):
            with self.subTest(virtualenv=virtualenv, transition=transition, offline_only=offline_only), tempfile.TemporaryDirectory() as tmp:
                home = Path(tmp).resolve()
                primary, root = home / "primary", home / "task"
                subprocess.run(["git", "init", "-q", str(primary)], check=True)
                subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c",
                                "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "docs"], check=True)
                (primary / ".idea/inspectionProfiles").mkdir(parents=True)
                (primary / ".idea/inspectionProfiles/shared.xml").write_text("<component />")
                (primary / ".idea/vcs.xml").write_text("<project />")
                subprocess.run(["git", "-C", str(primary), "add", ".idea"], check=True)
                subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c",
                                "user.email=fixture@example.invalid", "commit", "-q", "-m", "shared inspection policy"], check=True)
                (primary / ".git" / "info" / "exclude").write_text(".venv/\n")
                subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
                if virtualenv:
                    (root / ".venv").mkdir()
                table = home / "Library/Application Support/JetBrains/PyCharm2026.2/options/jdk.table.xml"
                table.parent.mkdir(parents=True)
                table.write_text('<application />')
                args = jb_inspect.build_parser().parse_args(["remove-worktree", "--repo", str(root)] +
                                                           (["--offline-only"] if offline_only else []))
                discoveries = []
                def discover(_port):
                    discoveries.append(True)
                    live = (transition == "starts" and len(discoveries) == 3) or (transition == "stops" and len(discoveries) < 3)
                    return [self.identity()] if live else []
                response = self.response(root, True)
                response.body["sdks"] = []
                with (patch_attr(jb_inspect.Path, "home", return_value=home),
                      patch.object(sys, "platform", "darwin"),
                      patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}),
                      patch_attr(jb_inspect, "discover_identities", side_effect=discover) as discovery,
                      patch_attr(jb_inspect, "http_post", return_value=response) as post):
                    self.assertFalse(jb_inspect.command_retire_sdks(args)["worktree_removed"])
                    self.assertTrue(root.is_dir())
                    args.dry_run = False
                    result = jb_inspect.command_retire_sdks(args)
                    self.assertTrue(result["worktree_removed"])
                    self.assertEqual(result["offline_cleanup_proved"], transition != "starts")
                    self.assertIn(str(table), result["offline_records_checked"])
                    if transition is None:
                        post.assert_not_called()
                    if offline_only:
                        discovery.assert_not_called()
                self.assertFalse(root.exists())
                subprocess.run(["git", "-C", str(primary), "show-ref", "--verify", "--quiet", "refs/heads/task"], check=True)

    @staticmethod
    def identity():
        return {"port": 63342, "session_id": "session", "helper_sdk_lifecycle_version": 1}

    @staticmethod
    def response(root, dry_run):
        return jb_inspect.HttpResult(200, {"status": "ok", "session_id": "session",
            "sdk_lifecycle_version": 1, "dry_run": dry_run, "sdks": [{
                "status": "would_remove" if dry_run else "removed", "reason": "helper_owned",
                "worktree_path": str(root), "sdk_name": "owned"}]}, "http://fixture")

    def test_remove_unregisters_before_git_removal_and_retains_branch(self):
        with tempfile.TemporaryDirectory() as tmp:
            primary = Path(tmp) / "primary"
            root = Path(tmp) / "task"
            subprocess.run(["git", "init", "-q", str(primary)], check=True)
            subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                            "commit", "-q", "--allow-empty", "-m", "fixture"], check=True)
            subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
            events = []
            def post(_port, endpoint, params, **_kwargs):
                self.assertTrue(root.is_dir())
                self.assertEqual(endpoint, "lifecycle/unregister-python-sdk")
                self.assertEqual(params["worktree_path"], str(root.resolve()))
                events.append(params["dry_run"])
                response = self.response(root, params["dry_run"] == "true")
                if events == ["true", "false", "true"]:
                    response.body["sdks"] = []
                return response
            args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
            with patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post):
                result = jb_inspect.command_retire_sdks(args)
            self.assertEqual(events, ["true", "false", "true"])
            self.assertTrue(result["worktree_removed"])
            self.assertFalse(root.exists())
            subprocess.run(["git", "-C", str(primary), "show-ref", "--verify", "--quiet", "refs/heads/task"], check=True)

    def test_refusal_never_removes_worktree(self):
        args = Namespace(command="remove-worktree", repo="/fixture/task", dry_run=False, lifecycle_lock_timeout_ms=1000)
        refused = jb_inspect.HttpResult(200, {"status": "refused", "sdks": [{"reason": "not_helper_owned"}]}, "http://fixture")
        with patch_attr(jb_inspect, "retirement_worktree", return_value=Path(args.repo)), patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", return_value=refused), patch.object(subprocess, "run") as removal:
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.command_retire_sdks(args)
            removal.assert_not_called()

    def test_dry_run_does_not_apply_or_remove(self):
        args = Namespace(command="remove-worktree", repo="/fixture/task", dry_run=True, lifecycle_lock_timeout_ms=1000)
        with patch_attr(jb_inspect, "retirement_worktree", return_value=Path(args.repo)), patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", return_value=self.response(Path(args.repo), True)) as post, patch.object(subprocess, "run") as removal:
            self.assertFalse(jb_inspect.command_retire_sdks(args)["worktree_removed"])
            self.assertEqual(post.call_args.args[2]["dry_run"], "true")
            post.assert_called_once()
            removal.assert_not_called()

    def test_missing_capability_refuses_before_any_request(self):
        for identities in ([], [self.identity(), {"port": 63343, "session_id": "old"}]):
            with self.subTest(identities=identities), patch_attr(jb_inspect, "http_post") as post:
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.unregister_helper_sdks(identities, None, False)
                post.assert_not_called()

    def test_session_drift_refuses(self):
        response = self.response(Path("/fixture/task"), True)
        response.body["session_id"] = "replacement"
        with patch_attr(jb_inspect, "http_post", return_value=response):
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.unregister_helper_sdks([self.identity()], Path("/fixture/task"), True)

    def test_orphans_use_reviewed_paths_before_apply(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "removed"
            args = Namespace(command="cleanup-helper-sdks", dry_run=False, lifecycle_lock_timeout_ms=1000,
                             worktree_path=[str(root)])
            applied = False
            def post(_port, _endpoint, params, **_kwargs):
                nonlocal applied
                if params["dry_run"] == "true":
                    self.assertEqual(params["orphans"], "true")
                    self.assertNotIn("worktree_path", params)
                else:
                    self.assertEqual(params["worktree_path"], str(root.resolve()))
                    self.assertNotIn("orphans", params)
                response = self.response(root, params["dry_run"] == "true")
                if applied:
                    response.body["sdks"] = []
                if params["dry_run"] == "false":
                    applied = True
                return response
            with patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post) as request:
                result = jb_inspect.command_retire_sdks(args)
                self.assertEqual([call.args[2]["dry_run"] for call in request.call_args_list], ["true", "false", "true"])
                self.assertFalse(result["worktree_removed"])

    def test_open_project_refuses_even_without_sdk_records(self):
        root = Path("/fixture/task")
        identity = self.identity() | {"open_projects": [{"base_path": str(root / "nested")}]}
        with patch_attr(jb_inspect, "http_post") as post:
            with self.assertRaisesRegex(jb_inspect.InspectError, "Close every IDE project"):
                jb_inspect.unregister_helper_sdks([identity], root, False)
            post.assert_not_called()

    def test_apply_refusal_preserves_worktree_and_completed_ide_results(self):
        root = Path("/fixture/task")
        args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
        second = self.identity() | {"port": 63343, "session_id": "second"}
        def urlopen(request, **_kwargs):
            params = urllib.parse.parse_qs(urllib.parse.urlparse(request.full_url).query)
            response = self.response(root, params["dry_run"] == ["true"])
            response.body["session_id"] = params["session_id"][0]
            if ":63343/" in request.full_url and params["dry_run"] == ["false"]:
                response.body["status"] = "refused"
                response.body["sdks"][0].update(status="refused", reason="sdk_in_use")
                raise jb_inspect.urllib.error.HTTPError(request.full_url, 409, "conflict", {}, io.BytesIO(json.dumps(response.body).encode()))
            transport = MagicMock()
            transport.__enter__.return_value = transport
            transport.status = 200
            transport.read.return_value = json.dumps(response.body).encode()
            return transport
        with patch_attr(jb_inspect, "retirement_worktree", return_value=root), patch_attr(jb_inspect, "discover_identities", return_value=[self.identity(), second]), patch_attr(jb_inspect.urllib.request, "urlopen", side_effect=urlopen), patch.object(subprocess, "run") as removal:
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.command_retire_sdks(args)
            self.assertEqual(len(caught.exception.payload["completed_sdk_cleanup"]), 1)
            self.assertEqual(caught.exception.payload["http_status"], 409)
            self.assertEqual(caught.exception.payload["sdk_cleanup"]["sdks"][0]["reason"], "sdk_in_use")
            removal.assert_not_called()

    def test_refused_orphan_is_listed_without_blocking_unrelated_reviewed_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "removed"
            held = Path(tmp).resolve() / "renamed"
            args = Namespace(command="cleanup-helper-sdks", dry_run=False, lifecycle_lock_timeout_ms=1000, worktree_path=[str(root)])
            applied = False
            def post(_port, _endpoint, params, **_kwargs):
                nonlocal applied
                response = self.response(root, params["dry_run"] == "true")
                if params["dry_run"] == "true":
                    response.body["status"] = "refused"
                    if applied:
                        response.body["sdks"] = []
                    response.body["sdks"].append({"worktree_path": str(held), "status": "refused", "reason": "ownership_mismatch", "sdk_name": "held"})
                    raise jb_inspect.InspectError("HTTP 409", 3, response.body | {"http_status": 409})
                self.assertEqual(params["worktree_path"], str(root))
                applied = True
                return response
            with patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post):
                result = jb_inspect.command_retire_sdks(args)
                self.assertEqual(result["sdk_preview"][0]["result"]["sdks"][1]["status"], "refused")
                self.assertEqual(len(result["sdk_cleanup"]), 1)
                args.worktree_path = [str(held)]
                with self.assertRaisesRegex(jb_inspect.InspectError, "absent from the current"):
                    jb_inspect.command_retire_sdks(args)

    def test_empty_orphan_apply_cannot_hide_a_remaining_owned_sdk(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "removed"
            args = Namespace(command="cleanup-helper-sdks", dry_run=False, lifecycle_lock_timeout_ms=1000, worktree_path=[str(root)])
            def post(_port, _endpoint, params, **_kwargs):
                response = self.response(root, params["dry_run"] == "true")
                if params["dry_run"] == "false":
                    response.body["sdks"] = []
                return response
            with patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post):
                with self.assertRaisesRegex(jb_inspect.InspectError, "remain registered"):
                    jb_inspect.command_retire_sdks(args)

    def test_unowned_sdk_refusal_is_preserved_while_eligible_worktree_is_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            primary = Path(tmp) / "primary"
            root = Path(tmp) / "task"
            subprocess.run(["git", "init", "-q", str(primary)], check=True)
            subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "fixture"], check=True)
            subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
            def post(_port, _endpoint, params, **_kwargs):
                self.assertEqual(params["dry_run"], "true")
                response = self.response(root, True)
                response.body["status"] = "refused"
                response.body["sdks"][0].update(status="refused", reason="not_helper_owned")
                raise jb_inspect.InspectError("HTTP 409", 3, response.body | {"http_status": 409})
            args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
            with patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post):
                result = jb_inspect.command_retire_sdks(args)
            self.assertTrue(result["worktree_removed"])
            self.assertEqual(result["preserved_unowned_sdk_count"], 1)
            self.assertEqual(result["sdk_cleanup"], [])
            self.assertFalse(root.exists())

    def remove_racing_finder(self, root: Path, kept: list[str], written: list[str], unregister: bool = True, edited: tuple[str, ...] = ()):
        """Run remove-worktree while Git stops partway, as it does when Finder refills a folder (#844)."""
        primary = root.parent / "primary"
        subprocess.run(["git", "init", "-q", str(primary)], check=True)
        (primary / ".gitignore").write_text(".venv/\n")
        (primary / "pkg").mkdir()
        (primary / "pkg" / "module.py").write_text("VALUE = 1\n")
        subprocess.run(["git", "-C", str(primary), "add", "."], check=True)
        subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                        "commit", "-q", "-m", "fixture"], check=True)
        subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
        (root / ".venv" / "lib").mkdir(parents=True)
        (root / ".venv" / "lib" / "site.py").write_text("generated\n")
        real_run = subprocess.run
        def run(command, *args, **kwargs):
            if command[3:5] != ["worktree", "remove"]:
                return real_run(command, *args, **kwargs)
            if unregister:
                # Git deletes files, then drops the registration even though a folder stayed non-empty.
                git_dir = real_run(["git", "-C", str(root), "rev-parse", "--absolute-git-dir"],
                                   capture_output=True, text=True, check=True).stdout.strip()
                shutil.rmtree(git_dir)
                for path in root.rglob("*"):
                    if path.is_file() and str(path.relative_to(root)) not in kept:
                        path.unlink()
                for relative in written:
                    (root / relative).write_text("written during removal\n")
                for relative in edited:
                    with (root / relative).open("a") as handle:
                        handle.write("edited during removal\n")
            return subprocess.CompletedProcess(command, 128, "", f"error: failed to delete '{root}': Directory not empty\n")
        def post(_port, _endpoint, params, **_kwargs):
            response = self.response(root, True)
            response.body["sdks"] = []
            return response
        args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
        with patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), \
                patch_attr(jb_inspect, "http_post", side_effect=post), patch.object(subprocess, "run", side_effect=run):
            return jb_inspect.command_retire_sdks(args)

    def test_removal_finishes_when_only_finder_metadata_refills_the_checkout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "task"
            result = self.remove_racing_finder(root, [], ["pkg/.DS_Store", ".DS_Store"])
            self.assertTrue(result["worktree_removed"])
            self.assertFalse(root.exists())

    def test_removal_finishes_when_verified_checkout_files_remain(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "task"
            result = self.remove_racing_finder(root, ["pkg/module.py", ".venv/lib/site.py"], ["pkg/.DS_Store"])
            self.assertTrue(result["worktree_removed"])
            self.assertFalse(root.exists())

    def test_removal_keeps_and_reports_a_file_written_during_removal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "task"
            with self.assertRaisesRegex(jb_inspect.InspectError, "not in the verified checkout") as caught:
                self.remove_racing_finder(root, ["pkg/module.py"], ["pkg/.DS_Store", "pkg/notes.txt"])
            self.assertEqual(caught.exception.payload["retained_entries"], ["pkg/notes.txt"])
            self.assertEqual((root / "pkg" / "notes.txt").read_text(), "written during removal\n")
            self.assertTrue((root / "pkg" / "module.py").exists())

    def test_removal_keeps_a_verified_file_edited_in_place_during_removal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "task"
            with self.assertRaisesRegex(jb_inspect.InspectError, "not in the verified checkout") as caught:
                self.remove_racing_finder(root, ["pkg/module.py"], ["pkg/.DS_Store"], edited=("pkg/module.py",))
            self.assertEqual(caught.exception.payload["retained_entries"], ["pkg/module.py"])
            self.assertIn("edited during removal", (root / "pkg" / "module.py").read_text())

    def test_removal_keeps_a_file_written_while_leftovers_are_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "task"
            real_unlink = os.unlink
            def unlink(path, *args, **kwargs):
                real_unlink(path, *args, **kwargs)
                late = root / "pkg" / "late.txt"
                if not late.exists():
                    late.write_text("written after the leftovers were listed\n")
            with patch.object(os, "unlink", side_effect=unlink):
                with self.assertRaisesRegex(jb_inspect.InspectError, "not in the verified checkout") as caught:
                    self.remove_racing_finder(root, ["pkg/module.py"], ["pkg/.DS_Store"])
            self.assertEqual(caught.exception.payload["retained_entries"], ["pkg/late.txt"])
            self.assertTrue((root / "pkg" / "late.txt").exists())

    def test_refused_removal_that_keeps_the_registration_touches_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "task"
            with self.assertRaisesRegex(jb_inspect.InspectError, "Git worktree removal failed"):
                self.remove_racing_finder(root, [], [], unregister=False)
            self.assertTrue((root / ".venv" / "lib" / "site.py").exists())

    def test_apply_skips_ides_whose_preview_has_nothing_to_remove(self):
        # #952: an IDE with an empty preview answered the needless mutating call with sdk_lifecycle_busy or a timeout.
        with tempfile.TemporaryDirectory() as tmp:
            primary = Path(tmp) / "primary"
            root = Path(tmp) / "task"
            subprocess.run(["git", "init", "-q", str(primary)], check=True)
            subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "fixture"], check=True)
            subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
            owner = self.identity()
            empty = self.identity() | {"port": 63343, "session_id": "empty"}
            applied_ports = []
            removed = False
            def post(port, _endpoint, params, **_kwargs):
                nonlocal removed
                session = params["session_id"]
                if params["dry_run"] == "false":
                    applied_ports.append(port)
                    if port == empty["port"]:
                        raise jb_inspect.InspectError("HTTP 409", 3, {"status": "refused", "reason": "sdk_lifecycle_busy", "http_status": 409})
                    removed = True
                response = self.response(root, params["dry_run"] == "true")
                response.body["session_id"] = session
                if port == empty["port"] or removed:
                    response.body["sdks"] = []
                return response
            args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
            with patch_attr(jb_inspect, "discover_identities", return_value=[owner, empty]), patch_attr(jb_inspect, "http_post", side_effect=post):
                result = jb_inspect.command_retire_sdks(args)
            self.assertEqual(applied_ports, [owner["port"]])
            self.assertEqual([item["port"] for item in result["sdk_apply_skipped"]], [empty["port"]])
            self.assertTrue(result["worktree_removed"])
            self.assertFalse(root.exists())

    def test_sdk_registered_after_an_empty_preview_keeps_the_worktree(self):
        root = Path("/fixture/task")
        args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
        previews = 0
        def post(_port, _endpoint, params, **_kwargs):
            nonlocal previews
            self.assertEqual(params["dry_run"], "true")
            previews += 1
            response = self.response(root, True)
            if previews == 1:
                response.body["sdks"] = []
            return response
        with patch_attr(jb_inspect, "retirement_worktree", return_value=root), patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post), patch.object(subprocess, "run") as removal:
            with self.assertRaisesRegex(jb_inspect.InspectError, "still needs retirement"):
                jb_inspect.command_retire_sdks(args)
            removal.assert_not_called()

    def test_busy_empty_response_never_counts_as_cleanup_success(self):
        root = Path("/fixture/task")
        busy = {"status": "refused", "reason": "sdk_lifecycle_busy", "http_status": 409,
                "session_id": "session", "sdk_lifecycle_version": 1, "dry_run": False, "sdks": []}
        with patch_attr(jb_inspect, "http_post", side_effect=jb_inspect.InspectError("HTTP 409", 3, busy)) as post:
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.unregister_helper_sdks([self.identity()], root, False)
            self.assertEqual(caught.exception.payload["reason"], "sdk_lifecycle_busy")
            self.assertGreater(post.call_args.kwargs["timeout"], 10)

    def test_new_sdk_in_final_preview_keeps_the_worktree(self):
        root = Path("/fixture/task")
        args = Namespace(command="remove-worktree", repo=str(root), dry_run=False, lifecycle_lock_timeout_ms=1000)
        def post(_port, _endpoint, params, **_kwargs):
            return self.response(root, params["dry_run"] == "true")
        with patch_attr(jb_inspect, "retirement_worktree", return_value=root), patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", side_effect=post), patch.object(subprocess, "run") as removal:
            with self.assertRaisesRegex(jb_inspect.InspectError, "still needs retirement"):
                jb_inspect.command_retire_sdks(args)
            removal.assert_not_called()

    def test_malformed_orphan_paths_refuse_before_apply(self):
        for value in (None, "", "relative/path", 42):
            response = self.response(Path("/fixture/task"), True)
            response.body["sdks"][0]["worktree_path"] = value
            with self.subTest(value=value), patch_attr(jb_inspect, "http_post", return_value=response):
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.unregister_helper_sdks([self.identity()], None, True)

    def test_orphan_apply_requires_reviewed_paths_and_accessible_parent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "unmounted" / "removed"
            args = Namespace(command="cleanup-helper-sdks", dry_run=False, lifecycle_lock_timeout_ms=1000)
            for selected in ([], [str(root)]):
                args.worktree_path = selected
                with self.subTest(selected=selected), patch_attr(jb_inspect, "discover_identities", return_value=[self.identity()]), patch_attr(jb_inspect, "http_post", return_value=self.response(root, True)) as post:
                    with self.assertRaises(jb_inspect.InspectError):
                        jb_inspect.command_retire_sdks(args)
                    self.assertEqual(post.call_count, 1)

    def test_preflight_preserves_primary_locked_dirty_and_leased_worktrees(self):
        with tempfile.TemporaryDirectory() as tmp:
            primary = Path(tmp) / "primary"
            root = Path(tmp) / "task"
            subprocess.run(["git", "init", "-q", str(primary)], check=True)
            subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "fixture"], check=True)
            subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b", "task", str(root)], check=True)
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.retirement_worktree(primary, True)
            subprocess.run(["git", "-C", str(primary), "worktree", "lock", str(root)], check=True)
            self.assertEqual(jb_inspect.retirement_worktree(root, True), root.resolve())
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.retirement_worktree(root, False)
            subprocess.run(["git", "-C", str(primary), "worktree", "unlock", str(root)], check=True)
            (root / "valuable").write_text("keep")
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.retirement_worktree(root, True)
            (root / "valuable").unlink()
            with patch_attr(jb_inspect, "read_local_leases", return_value=[(Path("lease.json"), {"worktree_root": str(root)})]):
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.retirement_worktree(root, True)
            with patch_attr(jb_inspect, "git_common_worktree", return_value=None):
                with self.assertRaisesRegex(jb_inspect.InspectError, "primary checkout"):
                    jb_inspect.retirement_worktree(root, True)
            self.assertTrue(root.is_dir())


class GlobalConfigTests(unittest.TestCase):
    def test_shared_config_is_independent_of_cli_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            shared = home / ".config/jetbrains-inspection/config.json"
            shared.parent.mkdir(parents=True)
            write_json(shared, {"jetbrains": {"trustedAutoOpenRoots": [str(home / "worktrees")]}})
            for variable in ("CODE_HOME", "CODEX_HOME", "CODEX_LAB_HOME"):
                with self.subTest(variable=variable), patch.dict(os.environ, {variable: str(home / "cli")}, clear=True), patch.object(Path, "home", return_value=home):
                    self.assertEqual(jb_inspect.global_config_path(), shared)
                    self.assertEqual(jb_inspect.trusted_auto_open_roots(), [str((home / "worktrees").resolve())])

    def test_explicit_override_wins_even_when_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            shared = home / ".config/jetbrains-inspection/config.json"
            shared.parent.mkdir(parents=True)
            write_json(shared, {"shared": True})
            override = home / "override.json"
            with patch.dict(os.environ, {"JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(override)}, clear=True), patch.object(Path, "home", return_value=home):
                self.assertEqual(jb_inspect.global_config_path(), override)
                self.assertEqual(jb_inspect.read_global_config(), {})

    def test_legacy_config_remains_available_until_migrated(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            legacy = home / ".code/jetbrains-inspection.json"
            legacy.parent.mkdir()
            write_json(legacy, {"legacy": True})
            for environment in ({}, {"CODE_HOME": str(legacy.parent)}, {"CODEX_HOME": str(legacy.parent)}):
                with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True), patch.object(Path, "home", return_value=home):
                    self.assertEqual(jb_inspect.read_global_config(), {"legacy": True})
                    shared = home / ".config/jetbrains-inspection/config.json"
                    shared.parent.mkdir(parents=True, exist_ok=True)
                    write_json(shared, {"shared": True})
                    self.assertEqual(jb_inspect.read_global_config(), {"shared": True})
                    shared.unlink()

    def test_missing_configs_default_to_shared_location(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True), patch.object(Path, "home", return_value=Path(tmp)):
            self.assertEqual(jb_inspect.global_config_path(), Path(tmp) / ".config/jetbrains-inspection/config.json")
            self.assertEqual(jb_inspect.read_global_config(), {})

    def test_invalid_shared_config_does_not_fall_back_to_legacy_trust(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            legacy = home / ".code/jetbrains-inspection.json"
            legacy.parent.mkdir()
            write_json(legacy, {"legacy": True})
            shared = home / ".config/jetbrains-inspection/config.json"
            shared.parent.mkdir(parents=True)
            shared.write_text("invalid json")
            with patch.dict(os.environ, {}, clear=True), patch.object(Path, "home", return_value=home):
                with self.assertRaises(jb_inspect.InspectError):
                    jb_inspect.read_global_config()


def attribution_cases() -> list[dict]:
    return json.loads(ATTRIBUTION_FIXTURE_PATH.read_text(encoding="utf-8"))


def make_config_dir(home: Path, name: str) -> Path:
    path = home / "Library" / "Application Support" / "JetBrains" / name
    (path / "options").mkdir(parents=True)
    return path


def make_app(base: Path, name: str, bundle_name: str, bundle_id: str, version: str) -> Path:
    path = base / f"{name}.app"
    contents = path / "Contents"
    contents.mkdir(parents=True)
    with (contents / "Info.plist").open("wb") as handle:
        plistlib.dump(
            {
                "CFBundleName": bundle_name,
                "CFBundleIdentifier": bundle_id,
                "CFBundleShortVersionString": version,
            },
            handle,
        )
    return path


def helper_args(**overrides):
    values = {
        "ide": None,
        "project_key": None,
        "project_path": None,
        "worktree_path": None,
        "cwd": None,
        "project": None,
        "session_id": None,
        "port": None,
        "open": False,
        "no_worktree_check": False,
    }
    values.update(overrides)
    return Namespace(**values)


def claimed_lifecycle_result(route: dict, close_proof: str = "proof-1"):
    return (
        {
            "status": "claimed",
            "ownership_proven": True,
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
        },
        close_proof,
        True,
        route,
    )


def unowned_lifecycle_result(route: dict):
    return (
        {
            "status": "not_owned",
            "ownership_proven": False,
            "reason": "project_preexisted",
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
        },
        None,
        False,
        route,
    )


def plugin_claim_body(route: dict, lease_id: str, close_proof: str = "proof-1", owned: bool = True):
    body = {
        "status": "claimed" if owned else "not_owned",
        "ownership_proven": owned,
        "lease_id": lease_id,
        "route": route,
        "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
    }
    if owned:
        body["close_token"] = close_proof
    else:
        body["reason"] = "project_preexisted"
    return body


def scope_capture_diagnostic(*files: dict) -> dict:
    return {
        "scope_kind": "files",
        "scope_resolution_status": "files_resolved",
        "scope_file_requested_count": len(files),
        "scope_file_resolved_count": len(files),
        "scope_file_diagnostics": list(files),
    }


def qualification_payload(
    *,
    since: str = "2026-07-26T00:00:00.000Z",
    after_event_id: str | None = None,
    helper_revision: str = "sha256:" + "1" * 64,
    plugin_build_fingerprint: str = "a" * 40 + "-clean",
    deployment_manifest_sha256: str = "sha256:" + "2" * 64,
) -> dict:
    boundary = {"since": since}
    if after_event_id is not None:
        boundary["after_event_id"] = after_event_id
    return {
        "schema_version": 1,
        "boundary": boundary,
        "helper_revision": helper_revision,
        "plugin_build_fingerprint": plugin_build_fingerprint,
        "deployment_manifest_sha256": deployment_manifest_sha256,
    }


def qualification_event(
    event_id: str,
    *,
    assessment_id: str | None = None,
    timestamp: str = "2026-07-26T00:00:01.000Z",
    verdict: str = "GREEN",
    inspection_run_id: int = 1,
    repo_path_hash: str = "sha256:" + "3" * 64,
    project_key_hash: str = "sha256:" + "4" * 64,
    event_kind: str = "inspection_assessment",
    command: str = "inspect-closeout",
) -> dict:
    assessment_id = assessment_id or f"assessment-{event_id}"
    scope_descriptor = {
        "scope": "changed_files",
        "include_unversioned": True,
        "changed_files_mode": "all",
        "profile": "",
        "severity": "all",
        "problem_type": "all",
        "file_pattern": "all",
        "allow_text_only_coverage": False,
        "include_stale": False,
        "limit": 100,
        "offset": 0,
    }
    decisive = verdict in {"GREEN", "RED"}
    classification = "decisive" if decisive else "legitimate_fail_closed"
    response_code = "no_matching_findings" if verdict == "GREEN" else "actionable_findings" if verdict == "RED" else "timeout"
    cleanup_status = "closed"
    attribution = {
        "schema_version": 1,
        "source": "plugin",
        "observed_by": "helper",
        "classification": classification,
        "code": response_code,
        "phase": "problems",
        "endpoint": "/api/inspection/problems",
        "http_status": 200,
        "request_id": f"request-{event_id}",
        "client_run_id": assessment_id,
        "session_id": "session-1",
        "project_instance_id": "project-instance-1",
        "project_key_hash": project_key_hash,
        "inspection_run_id": inspection_run_id,
        "plugin_version": "1.13.17",
        "plugin_build_fingerprint": "a" * 40 + "-clean",
        "ide_product_code": "PY",
        "ide_version": "2026.2",
        "ide_channel": "eap",
        "ide_channel_source": "plugin_attribution",
        "helper_revision": "sha256:" + "1" * 64,
        "cleanup_status": cleanup_status,
    }
    return {
        "schema_version": 2,
        "event_id": event_id,
        "event_kind": event_kind,
        "assessment_id": assessment_id,
        "timestamp": timestamp,
        "timestamp_ms": jb_inspect.parse_iso_timestamp_ms(timestamp),
        "command": command,
        "verdict": verdict,
        "bucket": "clean" if verdict == "GREEN" else "findings" if verdict == "RED" else "ide_not_ready",
        "verdict_reason": response_code,
        "status": "results_available" if decisive else "running",
        "scope": "changed_files",
        "scope_descriptor": scope_descriptor,
        "scope_descriptor_sha256": jb_inspect.canonical_json_sha256(scope_descriptor),
        "repo_path_hash": repo_path_hash,
        "worktree_root_hash": "sha256:" + "5" * 64,
        "repo_head_sha": "b" * 40,
        "ide": "PyCharm",
        "ide_channel": "eap",
        "ide_channel_source": "plugin_attribution",
        "ide_product_code": "PY",
        "ide_version": "2026.2",
        "project_key_hash": project_key_hash,
        "project_instance_id": "project-instance-1",
        "plugin_build_fingerprint": "a" * 40 + "-clean",
        "plugin_version": "1.13.17",
        "helper_revision": "sha256:" + "1" * 64,
        "deployment_manifest_sha256": "sha256:" + "2" * 64,
        "failure_phase": "problems",
        "attribution_class": classification,
        "response_code": response_code,
        "endpoint": "/api/inspection/problems",
        "http_status": 200,
        "observed_by": "helper",
        "client_run_id": assessment_id,
        "request_id": f"request-{event_id}",
        "session_id": "session-1",
        "inspection_run_id": inspection_run_id,
        "inspection_started": True,
        "inspection_attribution": attribution,
        "cleanup_status": cleanup_status,
        "internal_attempts": [
            {
                "attempt_index": 0,
                "terminal": True,
                "verdict": verdict,
                "verdict_reason": response_code,
                "bucket": "clean" if verdict == "GREEN" else "findings" if verdict == "RED" else "ide_not_ready",
                "retry": False,
                "attribution_class": classification,
                "phase": "problems",
                "inspection_run_id": inspection_run_id,
                "cleanup_status": cleanup_status,
            }
        ],
    }


class ParserCommandAliasTest(unittest.TestCase):
    def test_preferred_commands_parse_and_canonicalize(self):
        parser = jb_inspect.build_parser()
        commands = {
            "list-projects": "list",
            "resolve-route": "route",
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

        for command, canonical in commands.items():
            with self.subTest(command=command):
                args = parser.parse_args([command])
                self.assertEqual(jb_inspect.canonical_command(args.command), canonical)

    def test_compatibility_commands_parse_as_preferred_commands(self):
        parser = jb_inspect.build_parser()
        commands = {
            "list": ("list-projects", "list"),
            "route": ("resolve-route", "route"),
            "prepare": ("open-worktree", "open-worktree"),
            "prepare-worktree": ("open-worktree", "open-worktree"),
            "open-worktree": ("open-worktree", "open-worktree"),
            "run": ("inspect", "run"),
            "closeout": ("inspect-closeout", "closeout"),
            "status": ("get-status", "status"),
            "problems": ("get-problems", "problems"),
            "trigger": ("start-inspection", "trigger"),
            "wait": ("wait-for-inspection", "wait"),
            "claim": ("claim-worktree", "claim"),
            "cleanup-leases": ("cleanup-helper-leases", "cleanup-leases"),
        }

        for command, (preferred, canonical) in commands.items():
            with self.subTest(command=command):
                args = jb_inspect.parse_cli_args(parser, [command])
                self.assertEqual(args.command, preferred)
                self.assertEqual(args.command_input, preferred)
                self.assertEqual(jb_inspect.canonical_command(args.command), canonical)

    def test_help_lists_only_preferred_commands(self):
        parser = jb_inspect.build_parser()

        help_text = parser.format_help()

        for command in ("list-projects", "resolve-route", "open-worktree", "inspect", "inspect-closeout", "get-status", "get-problems"):
            self.assertIn(command, help_text)
        self.assertNotIn("Legacy alias", help_text)
        choices = parser._subparsers._group_actions[0].choices
        for command in ("list", "route", "prepare", "prepare-worktree", "run", "closeout", "status", "problems", "trigger", "wait", "claim", "cleanup-leases"):
            self.assertNotIn(command, choices)

    def test_lifecycle_commands_always_open(self):
        parser = jb_inspect.build_parser()

        args = parser.parse_args(["inspect", "--repo", "/tmp/repo", "--scope", "changed_files"])

        self.assertEqual(jb_inspect.canonical_command(args.command), "run")
        self.assertEqual(args.repo, "/tmp/repo")
        self.assertEqual(args.scope, "changed_files")
        self.assertTrue(args.open)
        for command in ("open-worktree", "inspect", "inspect-closeout"):
            with self.subTest(command=command), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                parser.parse_args([command, "--no-open"])

    def test_assessment_commands_accept_text_only_coverage_override(self):
        parser = jb_inspect.build_parser()

        for command in ("wait-for-inspection", "get-status", "get-problems", "inspect", "inspect-closeout"):
            with self.subTest(command=command):
                args = parser.parse_args([command, "--allow-text-only-coverage"])
                self.assertTrue(args.allow_text_only_coverage)

    def test_cleanup_command_has_lifecycle_lock_timeout(self):
        parser = jb_inspect.build_parser()

        args = parser.parse_args(["cleanup-helper-leases", "--lifecycle-lock-timeout-ms", "1234"])

        self.assertEqual(args.lifecycle_lock_timeout_ms, 1234)

    def test_summarize_outcomes_accepts_strict_qualification_options(self):
        parser = jb_inspect.build_parser()

        args = parser.parse_args([
            "summarize-outcomes",
            "--qualification-file",
            "/tmp/qualification.json",
            "--sample-size",
            "50",
        ])

        self.assertEqual(args.qualification_file, "/tmp/qualification.json")
        self.assertEqual(args.sample_size, 50)


class BuildContextTest(unittest.TestCase):
    def test_repository_preparation_is_validated_and_not_executed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir()
            command = "uv run prepare-project.py --python 3.12"
            write_json(
                root / ".github" / "github.json",
                {"qualityGate": {"inspection": {"prepare": command}}},
            )

            context = jb_inspect.build_context(
                Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
            )

        preparation = context["repository_preparation"]
        self.assertEqual(preparation["configured"], True)
        self.assertEqual(preparation["command"], command)
        self.assertEqual(preparation["source"], "qualityGate.inspection.prepare")
        self.assertEqual(preparation["execution_state"], "not_run")
        self.assertEqual(
            context["_repository_preparation_argv"],
            ["uv", "run", "prepare-project.py", "--python", "3.12"],
        )
        self.assertNotIn("_repository_preparation_command", context)

    def test_repository_preparation_absent_is_not_configured(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir()
            write_json(root / ".github" / "github.json", {"qualityGate": {"inspection": {}}})

            context = jb_inspect.build_context(
                Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
            )

        self.assertEqual(
            context["repository_preparation"],
            {
                "configured": False,
                "command": None,
                "source": "qualityGate.inspection.prepare",
                "execution_state": "not_configured",
                "target_worktree": str(root.resolve()),
            },
        )

    def test_structured_python_preparation_resolves_skill_helper(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir()
            write_json(
                root / ".github" / "github.json",
                {
                    "qualityGate": {
                        "inspection": {
                            "prepare": {
                                "python": {
                                    "version": "3.13",
                                    "moduleName": "launchplane",
                                    "testRoots": ["tests"],
                                    "sync": True,
                                    "extras": ["dev"],
                                    "requiredGeneratedState": [".venv", ".idea"],
                                }
                            }
                        }
                    }
                },
            )

            with patch.object(shutil, "which", side_effect=AssertionError("runtime probed")):
                context = jb_inspect.build_context(
                    Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
                )

        preparation = context["repository_preparation"]
        argv = context["_repository_preparation_argv"]
        self.assertEqual(preparation["kind"], "python")
        self.assertEqual(preparation["required_generated_state"], [
            ".venv", ".idea", ".idea/launchplane.iml", ".idea/modules.xml", ".idea/misc.xml",
        ])
        self.assertEqual(argv[:2], ["uv", "run"])
        self.assertEqual(Path(argv[2]).name, "prepare-python-project.py")
        self.assertEqual(
            argv[3:],
            [
                "--repo",
                ".",
                "--python",
                "3.13",
                "--module-name",
                "launchplane",
                "--test-root",
                "tests",
                "--sync",
                "--extra",
                "dev",
            ],
        )

    def test_structured_python_preparation_invalid_values_fail_closed(self):
        invalid_python_configs = (
            {"version": "3", "moduleName": "launchplane"},
            {"version": "3.13", "moduleName": "launch plane"},
            {"version": "3.13", "moduleName": "launchplane", "testRoots": ["../tests"]},
            {"version": "3.13", "moduleName": "launchplane", "extras": ["dev"]},
            {"version": "3.13", "moduleName": "launchplane", "unknown": True},
            {"version": "3.13", "moduleName": "fixture", "pep723Scripts": ["../script.py"]},
            {"version": "3.13", "moduleName": "fixture", "pep723Scripts": ["script.py"], "sync": True},
        )
        for python_config in invalid_python_configs:
            with self.subTest(python_config=python_config), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / ".github").mkdir()
                write_json(
                    root / ".github" / "github.json",
                    {"qualityGate": {"inspection": {"prepare": {"python": python_config}}}},
                )

                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.build_context(
                        Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
                    )

            self.assertEqual(
                raised.exception.payload["error_reason"],
                "repository_preparation_config_invalid",
            )

    def test_script_preparation_routes_sources_and_hashes_exact_worktree_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = root / "script.py"
            script.write_text("# /// script\n# dependencies = []\n# ///\n", encoding="utf-8")
            preparation = jb_inspect.parse_structured_repository_preparation(
                {"python": {"version": "3.12", "moduleName": "fixture", "pep723Scripts": ["script.py"]}}, {},
            )
            argv = preparation["_argv"]
            self.assertEqual(argv[-2:], ["--pep723-script", "script.py"])
            before = jb_inspect.repository_preparation_command_hash(argv, root)
            script.write_text('# /// script\n# dependencies = ["example==1"]\n# ///\n', encoding="utf-8")
            self.assertNotEqual(before, jb_inspect.repository_preparation_command_hash(argv, root))
            script.unlink()
            self.assertNotEqual(before, jb_inspect.repository_preparation_command_hash(argv, root))

    def test_long_script_set_refuses_instead_of_reporting_a_partial_rerun(self):
        config = {"version": "3.12", "moduleName": "fixture", "pep723Scripts": [
            f"scripts/{'long-path-' * 5}{index}.py" for index in range(20)
        ]}
        with self.assertRaises(jb_inspect.InspectError):
            jb_inspect.parse_structured_repository_preparation({"python": config}, {})
        config["pep723Scripts"] = ["scripts/one.py"]
        preparation = jb_inspect.parse_structured_repository_preparation({"python": config}, {})
        reported = jb_inspect.bounded_repository_preparation(preparation, target_worktree="/tmp/fixture")
        self.assertEqual(shlex.split(reported["command"]), preparation["_argv"])

    def test_structured_preparation_rejects_outer_generated_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir()
            write_json(
                root / ".github" / "github.json",
                {
                    "qualityGate": {
                        "inspection": {
                            "prepare": {
                                "python": {
                                    "version": "3.13",
                                    "moduleName": "launchplane",
                                }
                            },
                            "requiredGeneratedState": [".venv"],
                        }
                    }
                },
            )

            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.build_context(
                    Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
                )

        self.assertIn("inside", str(raised.exception))

    def test_repository_preparation_invalid_values_fail_closed(self):
        invalid_values = [None, "", "   ", [], "'unterminated"]
        for value in invalid_values:
            with self.subTest(value=value), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / ".github").mkdir()
                write_json(
                    root / ".github" / "github.json",
                    {"qualityGate": {"inspection": {"prepare": value}}},
                )

                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.build_context(
                        Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
                    )

            self.assertEqual(raised.exception.payload["error_reason"], "repository_preparation_config_invalid")
            self.assertEqual(
                raised.exception.payload["repository_preparation"]["execution_state"],
                "not_configured",
            )


class RepositoryPreparationPreflightTest(unittest.TestCase):
    def make_git_worktree(self) -> tuple[tempfile.TemporaryDirectory, Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
        (root / "tracked.txt").write_text("original", encoding="utf-8")
        subprocess.run(["git", "add", "tracked.txt"], cwd=root, check=True)
        subprocess.run(["git", "commit", "-qm", "initial"], cwd=root, check=True)
        (root / ".github").mkdir()
        config_path = root / ".github" / "github.json"
        write_json(config_path, {"qualityGate": {"inspection": {"prepare": "placeholder"}}})
        return temporary, root

    def make_context(self, root: Path, argv: list[str], generated: list[str] | None = None) -> dict[str, Any]:
        command = shlex.join(argv)
        config_path = root / ".github" / "github.json"
        write_json(
            config_path,
            {
                "qualityGate": {
                    "inspection": {
                        "prepare": command,
                        "requiredGeneratedState": generated or [],
                    }
                }
            },
        )
        return {
            "repo_path": str(root),
            "worktree_root": str(root),
            "config_path": str(config_path),
            "repository_preparation": {
                "configured": True,
                "command": command,
                "source": jb_inspect.REPOSITORY_PREPARATION_SOURCE,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_NOT_RUN,
                "required_generated_state": generated or [],
                "target_worktree": str(root),
            },
            "_repository_preparation_argv": argv,
        }

    def prep_args(self, **overrides: Any) -> Namespace:
        values = {
            "repository_preparation_timeout_ms": 1_000,
            "skip_preparation": False,
            "force_preparation": False,
        }
        values.update(overrides)
        return Namespace(**values)

    def test_parser_exposes_bounded_opt_out_and_refresh_controls(self):
        parser = jb_inspect.build_parser()
        args = parser.parse_args(
            [
                "inspect-closeout",
                "--repository-preparation-timeout-ms",
                "1234",
                "--no-repository-preparation",
                "--force-refresh-preparation",
            ]
        )

        self.assertEqual(args.repository_preparation_timeout_ms, 1234)
        self.assertTrue(args.skip_preparation)
        self.assertTrue(args.force_preparation)

        for command in ("open-worktree", "prepare-worktree", "prepare"):
            with self.subTest(command=command):
                open_args = jb_inspect.parse_cli_args(
                    parser,
                    [
                        command,
                        "--repository-preparation-timeout-ms",
                        "2345",
                        "--no-repository-preparation",
                        "--force-refresh-preparation",
                    ],
                )
                self.assertEqual(open_args.command, "open-worktree")
                self.assertEqual(open_args.repository_preparation_timeout_ms, 2345)
                self.assertTrue(open_args.skip_preparation)
                self.assertTrue(open_args.force_preparation)

    def test_open_worktree_prepares_once_before_route_discovery(self):
        context = {
            "worktree_root": "/tmp/repo",
            "repository_preparation": {
                "configured": True,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_NOT_RUN,
            },
        }
        prepared_state = {
            "configured": True,
            "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
        }
        route = {
            "base_path": "/tmp/repo",
            "project_instance_id": "session:1",
            "project_key": "path:/tmp/repo",
            "session_id": "session",
        }

        parser = jb_inspect.build_parser()
        for command in ("open-worktree", "prepare-worktree", "prepare"):
            with self.subTest(command=command):
                events = []

                def prepare(_args, _context):
                    events.append("repository_preparation")
                    return prepared_state

                def discover(_args, _context):
                    events.append("route_discovery")
                    return route

                with (
                    patch_attr(jb_inspect, "run_repository_preparation", side_effect=prepare) as run_preparation,
                    patch_attr(jb_inspect, "create_local_lease", return_value={"lease_id": "lease"}),
                    patch_attr(jb_inspect, "find_exact_route", side_effect=discover),
                    patch_attr(jb_inspect, "ensure_exact_worktree"),
                    patch_attr(jb_inspect, "claim_lifecycle", return_value=unowned_lifecycle_result(route)),
                    patch_attr(jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry", return_value={"ready": True}),
                    patch_attr(jb_inspect, "write_lease"),
                    patch_attr(jb_inspect, "remove_lease"),
                ):
                    result = jb_inspect.command_prepare(
                        jb_inspect.parse_cli_args(parser, [command, "--repo", "/tmp/repo"]),
                        context,
                    )

                self.assertEqual(events, ["repository_preparation", "route_discovery"])
                run_preparation.assert_called_once()
                self.assertEqual(
                    result["repository_preparation"]["execution_state"],
                    jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                )

    def test_claim_worktree_does_not_run_repository_preparation(self):
        context = {
            "repository_preparation": {
                "configured": True,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_NOT_RUN,
            },
        }
        with (
            patch_attr(jb_inspect, "run_repository_preparation") as run_preparation,
            patch_attr(jb_inspect, "create_local_lease", return_value={"lease_id": "lease"}),
        ):
            result = jb_inspect.command_claim(Namespace(command="claim"), context)

        run_preparation.assert_not_called()
        self.assertEqual(result["status"], "claimed")

    def test_trusted_preparation_runs_in_exact_worktree_and_writes_receipt(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        argv = [sys.executable, "-c", "import os; print(os.getcwd())"]
        context = self.make_context(root, argv)

        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            result = jb_inspect.run_repository_preparation(self.prep_args(), context)

        self.assertEqual(result["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
        self.assertEqual(result["exit_status"], 0)
        self.assertIn("<path:sha256:", result["stdout"])
        self.assertTrue(Path(result["receipt_path"]).exists())
        receipt = json.loads(Path(result["receipt_path"]).read_text(encoding="utf-8"))
        self.assertEqual(receipt["command_sha256"], result["command_sha256"])
        self.assertEqual(receipt["worktree_identity_hash"], result["worktree_identity_hash"])
        self.assertLessEqual(len(receipt["stdout"]), jb_inspect.MAX_REPOSITORY_PREPARATION_OUTPUT_LENGTH + 32)

    def test_untrusted_root_blocks_without_executing_command(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        context = self.make_context(root, [sys.executable, "-c", "raise SystemExit(9)"])

        with (
            patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent / "other")}),
            patch.object(subprocess, "run", side_effect=AssertionError("command executed")),
            self.assertRaises(jb_inspect.InspectError) as raised,
        ):
            jb_inspect.run_repository_preparation(self.prep_args(), context)

        self.assertEqual(raised.exception.payload["error_reason"], "repository_preparation_untrusted")
        self.assertIn("--skip-preparation", raised.exception.payload["next_action"])

    def test_nonzero_and_timeout_are_terminal_preparation_reasons(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        nonzero = self.make_context(root, [sys.executable, "-c", "raise SystemExit(7)"])
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            with self.assertRaises(jb_inspect.InspectError) as failed:
                jb_inspect.run_repository_preparation(self.prep_args(), nonzero)
        self.assertEqual(failed.exception.payload["error_reason"], "repository_preparation_command_failed")

        timeout = self.make_context(root, [sys.executable, "-c", "import time; time.sleep(1)"])
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            with self.assertRaises(jb_inspect.InspectError) as timed_out:
                jb_inspect.run_repository_preparation(self.prep_args(repository_preparation_timeout_ms=10), timeout)
        self.assertEqual(timed_out.exception.payload["error_reason"], "repository_preparation_timeout")

    def test_tracked_and_hidden_index_mutations_block(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            tracked = self.make_context(
                root,
                [sys.executable, "-c", "from pathlib import Path; Path('tracked.txt').write_text('changed')"],
            )
            with self.assertRaises(jb_inspect.InspectError) as tracked_error:
                jb_inspect.run_repository_preparation(self.prep_args(), tracked)
            self.assertEqual(tracked_error.exception.payload["error_reason"], "repository_preparation_tracked_mutation")

            subprocess.run(["git", "checkout", "--", "tracked.txt"], cwd=root, check=True)
            hidden_index = self.make_context(
                root,
                [sys.executable, "-c", "import subprocess; subprocess.run(['git', 'update-index', '--assume-unchanged', 'tracked.txt'], check=True)"],
            )
            with self.assertRaises(jb_inspect.InspectError) as index_error:
                jb_inspect.run_repository_preparation(self.prep_args(), hidden_index)
            self.assertEqual(index_error.exception.payload["error_reason"], "repository_preparation_index_mutation")

    def test_receipt_reuse_invalidation_and_force_refresh(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        counter_directory = tempfile.TemporaryDirectory()
        self.addCleanup(counter_directory.cleanup)
        counter = Path(counter_directory.name) / "preparation-counter.txt"
        script = (
            f"from pathlib import Path; p=Path({str(counter)!r}); "
            "p.write_text(str(int(p.read_text()) + 1) if p.exists() else '1'); Path('generated').mkdir(exist_ok=True)"
        )
        context = self.make_context(root, [sys.executable, "-c", script], generated=["generated"])
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            first = jb_inspect.run_repository_preparation(self.prep_args(), context)
            skipped = jb_inspect.run_repository_preparation(self.prep_args(skip_preparation=True), context)
            second = jb_inspect.run_repository_preparation(self.prep_args(), context)
            self.assertEqual(first["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
            self.assertEqual(skipped["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SKIPPED)
            self.assertEqual(second["execution_state"], jb_inspect.REPOSITORY_PREPARATION_REUSED)
            self.assertEqual(counter.read_text(encoding="utf-8"), "1")

            write_json(root / ".github" / "github.json", {"qualityGate": {"inspection": {"prepare": context["repository_preparation"]["command"], "requiredGeneratedState": []}, "metadata": "changed"}})
            invalidated = jb_inspect.run_repository_preparation(self.prep_args(), context)
            self.assertEqual(invalidated["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
            self.assertEqual(counter.read_text(encoding="utf-8"), "2")

            refreshed = jb_inspect.run_repository_preparation(self.prep_args(force_preparation=True), context)
            self.assertEqual(refreshed["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
            self.assertEqual(counter.read_text(encoding="utf-8"), "3")

    def test_structured_preparation_skip_reuse_and_missing_runtime(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        runtime_directory = tempfile.TemporaryDirectory()
        self.addCleanup(runtime_directory.cleanup)
        fake_uv = Path(runtime_directory.name) / "uv"
        helper = Path(runtime_directory.name) / "prepare-python-project.py"
        fake_uv.write_text("#!/bin/sh\nshift\nexec python3 \"$@\"\n", encoding="utf-8")
        fake_uv.chmod(0o755)
        helper.write_text(
            "from pathlib import Path\nPath('generated').mkdir(exist_ok=True)\n",
            encoding="utf-8",
        )
        argv = [str(fake_uv), "run", str(helper)]
        context = self.make_context(root, argv, generated=["generated"])
        context["repository_preparation"]["kind"] = "python"

        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            first = jb_inspect.run_repository_preparation(self.prep_args(), context)
        self.assertEqual(first["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)

        fake_uv.unlink()
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            skipped = jb_inspect.run_repository_preparation(
                self.prep_args(skip_preparation=True),
                context,
            )
            reused = jb_inspect.run_repository_preparation(self.prep_args(), context)

        self.assertEqual(skipped["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SKIPPED)
        self.assertEqual(reused["execution_state"], jb_inspect.REPOSITORY_PREPARATION_REUSED)

        Path(reused["receipt_path"]).unlink()
        with (
            patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}),
            self.assertRaises(jb_inspect.InspectError) as unavailable,
        ):
            jb_inspect.run_repository_preparation(self.prep_args(), context)

        self.assertEqual(
            unavailable.exception.payload["error_reason"],
            "repository_preparation_runtime_unavailable",
        )
        self.assertIn("uv", unavailable.exception.payload["next_action"])

    def test_structured_preparation_receipt_hash_changes_with_helper_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            helper = Path(temporary) / "prepare-python-project.py"
            helper.write_text("print('first')\n", encoding="utf-8")
            argv = ["uv", "run", str(helper), "--repo", "."]

            first = jb_inspect.repository_preparation_command_hash(argv)
            helper.write_text("print('second')\n", encoding="utf-8")
            second = jb_inspect.repository_preparation_command_hash(argv)

        self.assertNotEqual(first, second)

    def test_sdk_table_changes_invalidate_preparation_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            helper = Path(temporary) / "prepare-python-project.py"
            helper.write_text("pass\n", encoding="utf-8")
            table = Path(temporary) / "jdk.table.xml"
            argv = ["uv", "run", str(helper), "--sdk-table", str(table)]
            absent = jb_inspect.repository_preparation_command_hash(argv)
            table.write_text("<application />", encoding="utf-8")
            present = jb_inspect.repository_preparation_command_hash(argv)
            self.assertNotEqual(absent, present)
            table.write_text("<application><component /></application>", encoding="utf-8")
            self.assertNotEqual(present, jb_inspect.repository_preparation_command_hash(argv))

    def test_prepared_model_content_changes_invalidate_receipt_snapshot(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        path = root / "model.iml"
        context = self.make_context(root, ["true"], generated=["model.iml"])
        path.write_text("first", encoding="utf-8")
        first = jb_inspect.repository_preparation_generated_state_snapshot(context)
        path.write_text("other", encoding="utf-8")
        self.assertNotEqual(first, jb_inspect.repository_preparation_generated_state_snapshot(context))
        path.unlink()
        self.assertFalse(jb_inspect.repository_preparation_generated_state_snapshot(context)["all_present"])

    def test_structured_preparation_passes_only_selected_ide_sdk_table(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        script = root / "prepare-python-project.py"
        script.write_text("import sys; print(sys.argv[1:])\n", encoding="utf-8")
        context = self.make_context(root, [sys.executable, "-u", str(script)])
        context["repository_preparation"]["kind"] = "python"
        context["ide_config_dir"] = str(root / "selected-ide")
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            result = jb_inspect.run_repository_preparation(self.prep_args(), context)
        self.assertIn("--sdk-table", result["command"])
        self.assertIn(str(root / "selected-ide/options/jdk.table.xml"), result["command"])

    def test_long_selected_sdk_table_refuses_before_running_script_preparation(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        script = root / "prepare-python-project.py"
        script.write_text("pass\n", encoding="utf-8")
        context = self.make_context(root, ["uv", "run", str(script), "--pep723-script", "one.py"])
        context["repository_preparation"]["kind"] = "python"
        context["ide_config_dir"] = str(root.joinpath(*(["deep-ide-config-directory"] * 60)))
        runner = MagicMock(side_effect=AssertionError("preparation executed"))
        subprocess_view = Namespace(**vars(subprocess))
        subprocess_view.Popen = runner
        with (
            patch.dict(jb_inspect.__dict__, {"subprocess": subprocess_view}),
            patch.dict(os.environ, {
                "JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "",
                "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent),
            }),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.run_repository_preparation(self.prep_args(), context)
        self.assertEqual(
            raised.exception.payload["error_reason"],
            jb_inspect.repository_preparation_config_error("fixture").payload["error_reason"],
        )
        runner.assert_not_called()

        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}):
            skipped = jb_inspect.run_repository_preparation(self.prep_args(skip_preparation=True), context)
        self.assertEqual(skipped["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SKIPPED)

    def test_lane_flow_resolves_python_sdk_table_before_shared_preparation(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        script = root / "prepare-python-project.py"
        script.write_text("import sys; print(sys.argv[1:])\n", encoding="utf-8")
        selected = root / "example.py"
        selected.write_text("value = 1\n", encoding="utf-8")
        context = self.make_context(root, [sys.executable, "-u", str(script)])
        context["repository_preparation"]["kind"] = "python"
        context["_inspection_lanes"] = jb_inspect.parse_inspection_lanes({"lanes": [
            {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
            {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
        ]})
        context["scope"] = "files"
        args = self.prep_args(scope="files", files=[str(selected)], max_files=None, profile="")
        seen = []

        def inspect(_args, lane_context):
            seen.append(lane_context["repository_preparation"])
            return {"status": "clean", "total_problems": 0, "problems": []}

        def resolve(selection_context):
            if selection_context.get("ide") == "PyCharm":
                return Namespace(config_dir=root / "pycharm", app_path=None, app_name=None, public=lambda: {})
            return None

        with (
            patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}),
            patch_attr(jb_inspect, "resolve_ide_selection", side_effect=resolve),
            patch_attr(jb_inspect, "run_prepared_inspection", side_effect=inspect),
        ):
            jb_inspect.run_configured_inspection_lanes(args, context)
        self.assertEqual(len(seen), 1)
        self.assertEqual(seen[0]["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
        self.assertIn(str(root / "pycharm/options/jdk.table.xml"), seen[0]["command"])

    def test_structured_preparation_uses_explicit_config_override_and_invalidates_receipt(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        script = root / "prepare-python-project.py"
        script.write_text("import sys; print(sys.argv[1:])\n", encoding="utf-8")
        context = self.make_context(root, [sys.executable, "-u", str(script)])
        context["repository_preparation"]["kind"] = "python"
        context["ide_config_dir"] = str(root / "normal-ide")
        isolated_directory = tempfile.TemporaryDirectory()
        self.addCleanup(isolated_directory.cleanup)
        isolated = Path(isolated_directory.name).resolve()
        table = isolated / "options/jdk.table.xml"
        table.parent.mkdir(parents=True)
        with patch.dict(os.environ, {
            "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent),
            "JETBRAINS_INSPECTION_IDE_CONFIG_DIR": str(isolated),
        }):
            first = jb_inspect.run_repository_preparation(self.prep_args(), context)
            self.assertIn(str(table), first["command"])
            self.assertNotIn(str(root / "normal-ide"), first["command"])
            reused = jb_inspect.run_repository_preparation(self.prep_args(), context)
            self.assertEqual(reused["execution_state"], jb_inspect.REPOSITORY_PREPARATION_REUSED)
            table.write_text("<application />\n", encoding="utf-8")
            refreshed = jb_inspect.run_repository_preparation(self.prep_args(), context)
            self.assertEqual(refreshed["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)

    def test_explicit_config_override_applies_to_one_python_lane_only(self):
        context = {"_inspection_lanes": jb_inspect.parse_inspection_lanes({"lanes": [
            {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
            {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
        ]})}
        with (
            patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "~/isolated-ide"}),
            patch_attr(jb_inspect, "resolve_ide_selection", return_value=Namespace(config_dir=Path("/normal"))),
        ):
            self.assertEqual(
                jb_inspect.repository_preparation_sdk_table(context),
                Path("~/isolated-ide/options/jdk.table.xml").expanduser().resolve(),
            )
            context["_inspection_lanes"] += jb_inspect.parse_inspection_lanes({"lanes": [
                {"id": "eap", "ide": "PyCharm EAP", "include": ["eap/**/*.py"]},
            ]})
            self.assertIsNone(jb_inspect.repository_preparation_sdk_table(context))

    def test_ambiguous_python_lanes_do_not_reuse_top_level_sdk_selection(self):
        context = {"ide_config_dir": "/unrelated", "_inspection_lanes": jb_inspect.parse_inspection_lanes({"lanes": [
            {"id": "one", "ide": "PyCharm", "include": ["one/**/*.py"]},
            {"id": "two", "ide": "PyCharm EAP", "include": ["two/**/*.py"]},
        ]})}
        self.assertIsNone(jb_inspect.repository_preparation_sdk_table(context))

    @unittest.skipUnless(os.name == "posix", "process-group timeout coverage requires POSIX")
    def test_timeout_kills_descendants_that_inherit_output_pipes(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        # Keep the witness unique to this fixture and remove it with the fixture.
        # A shared parent marker can outlive an earlier run and report readiness
        # before this descendant exists, racing SIGKILL against child creation.
        descendant_ready = root / "preparation-descendant-ready"
        preparation_processes = []
        preparation_started = None
        preparation_witness_observed = False
        real_popen = subprocess.Popen
        descendant_command = (
            "from pathlib import Path; import time; "
            f"Path({str(descendant_ready)!r}).write_text('ready', encoding='utf-8'); "
            "time.sleep(10)"
        )
        preparation_command = [
            sys.executable,
            "-c",
            (
                "from pathlib import Path; import subprocess, sys, time; "
                f"child = subprocess.Popen([sys.executable, '-c', {descendant_command!r}]); "
                "time.sleep(10)"
            ),
        ]

        def witnessed_popen(*args, **kwargs):
            nonlocal preparation_started, preparation_witness_observed
            process = real_popen(*args, **kwargs)
            command = args[0] if args else kwargs.get("args")
            if command != preparation_command:
                return process
            preparation_processes.append(process)
            deadline = time.monotonic() + 2
            while not descendant_ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.005)
            if not descendant_ready.exists():
                try:
                    os.killpg(process.pid, jb_inspect.signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate(timeout=2)
                self.fail("preparation descendant did not reach the readiness witness")
            preparation_witness_observed = True
            preparation_started = time.monotonic()
            return process

        context = self.make_context(
            root,
            preparation_command,
        )
        group_absent = False
        try:
            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}),
                patch.object(subprocess, subprocess.Popen.__name__, side_effect=witnessed_popen),
            ):
                with self.assertRaises(jb_inspect.InspectError) as timed_out:
                    jb_inspect.run_repository_preparation(
                        self.prep_args(repository_preparation_timeout_ms=25),
                        context,
                    )
                preparation_finished = time.monotonic()

            self.assertTrue(descendant_ready.exists())
            self.assertTrue(preparation_witness_observed)
            self.assertIsNotNone(preparation_started)
            self.assertEqual(len(preparation_processes), 1)
            self.assertEqual(timed_out.exception.payload["error_reason"], "repository_preparation_timeout")
            preparation_elapsed = preparation_finished - preparation_started
            self.assertLess(preparation_elapsed, 3.0)
            group_deadline = time.monotonic() + 2
            while time.monotonic() < group_deadline:
                try:
                    os.killpg(preparation_processes[0].pid, 0)
                except ProcessLookupError:
                    group_absent = True
                    break
                time.sleep(0.005)
            self.assertTrue(group_absent, "preparation process group remained after timeout cleanup")
        finally:
            for process in preparation_processes:
                if not group_absent:
                    try:
                        os.killpg(process.pid, jb_inspect.signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                try:
                    process.communicate(timeout=2)
                except subprocess.TimeoutExpired:
                    if not group_absent:
                        try:
                            os.killpg(process.pid, jb_inspect.signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    process.communicate(timeout=2)

    def test_opt_out_and_recursion_are_explicit_and_non_executing(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        marker = root.parent / "should-not-exist.txt"
        context = self.make_context(root, [sys.executable, "-c", f"from pathlib import Path; Path({str(marker)!r}).touch()"])
        with patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent)}):
            skipped = jb_inspect.run_repository_preparation(self.prep_args(skip_preparation=True), context)
        self.assertEqual(skipped["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SKIPPED)
        self.assertFalse(marker.exists())

        with patch.dict(
            os.environ,
            {
                "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent),
                jb_inspect.REPOSITORY_PREPARATION_ACTIVE_ENV: "1",
            },
        ):
            with self.assertRaises(jb_inspect.InspectError) as recursive:
                jb_inspect.run_repository_preparation(self.prep_args(skip_preparation=True), context)
        self.assertEqual(recursive.exception.payload["error_reason"], "repository_preparation_recursion")

    def test_preparation_output_redacts_sensitive_values_before_receipt_and_payload(self):
        temporary, root = self.make_git_worktree()
        self.addCleanup(temporary.cleanup)
        secret = "super-secret-value"
        context = self.make_context(
            root,
            [
                sys.executable,
                "-c",
                "import os; print(os.environ['PREP_TOKEN']); print('password=' + 'hunter' + '2'); print('Authorization: Bearer ' + 'abc' + '123')",
            ],
        )
        with patch.dict(
            os.environ,
            {
                "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root.parent),
                "PREP_TOKEN": secret,
            },
        ):
            result = jb_inspect.run_repository_preparation(self.prep_args(), context)

        serialized = json.dumps(result)
        receipt = Path(result["receipt_path"]).read_text(encoding="utf-8")
        for sensitive in (secret, "hunter2", "abc123"):
            self.assertNotIn(sensitive, serialized)
            self.assertNotIn(sensitive, receipt)
        self.assertIn(jb_inspect.REDACTED, result["stdout"])

    def test_single_lane_mutation_window_starts_after_repository_preparation(self):
        events = []
        context = {
            "worktree_root": "/tmp/repo",
            "repository_preparation": {
                "configured": True,
                "command": "uv run prepare.py",
                "source": jb_inspect.REPOSITORY_PREPARATION_SOURCE,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_NOT_RUN,
            },
        }
        prepared_state = {
            **context["repository_preparation"],
            "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
        }

        def prepare(_args, _context):
            events.append("repository_preparation")
            return prepared_state

        def snapshot(_path):
            events.append("mutation_snapshot")
            return {"status": "ok", "entries": {}}

        def lifecycle(_args, lifecycle_context):
            events.append("ide_lifecycle")
            self.assertTrue(lifecycle_context["_repository_preparation_completed"])
            return (
                {"route": {}, "repository_preparation": lifecycle_context["repository_preparation"]},
                {"lease_id": "lease", "opened_by_helper": False},
                None,
            )

        with (
            patch_attr(jb_inspect, "run_repository_preparation", side_effect=prepare),
            patch_attr(jb_inspect, "git_worktree_status_snapshot", side_effect=snapshot),
            patch_attr(jb_inspect, "prepare_lifecycle_details", side_effect=lifecycle),
            patch_attr(
                jb_inspect,
                "run_inspection_with_internal_retry",
                return_value={"status": "clean", "total_problems": 0, "problems": []},
            ),
            patch_attr(
                jb_inspect,
                "cleanup_lifecycle",
                return_value={"status": "not_needed", "reason": "helper_did_not_open_project"},
            ),
        ):
            jb_inspect.run_prepared_inspection(
                Namespace(command="inspect", lifecycle_lock_timeout_ms=1_000, keep_warm=False),
                context,
            )

        self.assertEqual(events[:3], ["repository_preparation", "mutation_snapshot", "ide_lifecycle"])

    def test_preparation_failure_is_qualified_as_explicit_hard_failure(self):
        payload = {
            "status": "error",
            "error_reason": "repository_preparation_command_failed",
            "repository_preparation": {
                "configured": True,
                "execution_state": "failed",
                "failure_reason": "repository_preparation_command_failed",
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "repository_preparation_command_failed")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertIn("repository preparation", payload["agent_result"]["next_action"].lower())

    def test_qualification_names_preparation_failure_instead_of_generic_unknown(self):
        event = qualification_event("preparation-failure", verdict="UNKNOWN")
        event.update(
            {
                "inspection_started": False,
                "attribution_class": "configuration_blocked",
                "response_code": "repository_preparation_command_failed",
                "failure_phase": "repository_preparation",
                "endpoint": None,
                "http_status": None,
                "request_id": None,
                "ide_channel_source": None,
                "repository_preparation": {
                    "configured": True,
                    "execution_state": "failed",
                    "failure_reason": "repository_preparation_command_failed",
                },
            }
        )
        event["inspection_attribution"].update(
            {
                "source": "helper",
                "classification": "configuration_blocked",
                "code": "repository_preparation_command_failed",
                "phase": "repository_preparation",
                "endpoint": None,
                "http_status": None,
                "request_id": None,
                "ide_channel_source": None,
            }
        )
        event["internal_attempts"][0].update(
            {
                "verdict": "UNKNOWN",
                "verdict_reason": "repository_preparation_command_failed",
                "bucket": "environment_blocked",
            }
        )

        candidate, exclusion = jb_inspect.qualification_event_candidate(event, 1, qualification_payload())

        self.assertIsNone(candidate)
        self.assertIsNotNone(exclusion)
        self.assertEqual(exclusion["reason"], "repository_preparation_command_failed")
        self.assertTrue(exclusion["hard_failure"])

    def test_reads_github_config_for_jetbrains_preferences(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir()
            write_json(
                root / ".github" / "github.json",
                {
                    "qualityGate": {
                        "inspection": {
                            "ide": "IntelliJ IDEA",
                            "profile": "Codex Skills Readiness",
                            "scopePreference": ["directory", "whole_project"],
                        }
                    },
                    "jetbrains": {
                        "mainWorktreePath": "~/Developer/example-main",
                        "openProjectPath": "packages/app",
                        "worktreeStrategy": "prefer-current",
                    },
                },
            )
            (root / "packages" / "app").mkdir(parents=True)

            args = Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
            context = jb_inspect.build_context(args)

            self.assertEqual(context["ide"], "IntelliJ IDEA")
            self.assertEqual(context["scope"], "directory")
            self.assertEqual(args.profile, "Codex Skills Readiness")
            self.assertEqual(context["worktree_strategy"], "prefer-current")
            self.assertEqual(context["project_path"], str((root / "packages" / "app").resolve()))
            self.assertTrue(context["main_worktree"].endswith("Developer/example-main"))

    def test_nested_idea_project_remains_requested_project_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            nested = root / "test-fixtures" / "inspection-red-lane-webstorm"
            (nested / ".idea").mkdir(parents=True)

            args = Namespace(repo=str(nested), ide=None, ide_app=None, scope=None)
            context = jb_inspect.build_context(args)

            self.assertEqual(context["repo_path"], str(nested.resolve()))
            self.assertEqual(context["project_path"], str(nested.resolve()))
            self.assertEqual(context["worktree_root"], str(root.resolve()))
            self.assertEqual(context["lifecycle_target_path"], str(nested.resolve()))

    def test_nested_gradle_project_remains_requested_project_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / ".github").mkdir()
            write_json(root / ".github" / "github.json", {"jetbrains": {"openProjectPath": "."}})
            nested = root / "harness" / "workspace" / "project"
            nested.mkdir(parents=True)
            (nested / "settings.gradle").write_text("pluginManagement { repositories { gradlePluginPortal() } }\n", encoding="utf-8")

            args = Namespace(repo=str(nested), ide=None, ide_app=None, scope=None)
            context = jb_inspect.build_context(args)

            self.assertEqual(context["repo_path"], str(nested.resolve()))
            self.assertEqual(context["project_path"], str(nested.resolve()))
            self.assertEqual(context["worktree_root"], str(root.resolve()))
            self.assertEqual(context["lifecycle_target_path"], str(nested.resolve()))

    def test_ide_app_overrides_launch_app_without_changing_identity_selector(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            args = Namespace(repo=str(root), ide="WebStorm", ide_app="WebStorm 2026.2 EAP", scope=None)
            context = jb_inspect.build_context(args)

            self.assertEqual(context["ide"], "WebStorm")
            self.assertEqual(context["ide_app"], "WebStorm 2026.2 EAP")

    def test_product_level_ide_resolves_latest_stable_app_and_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            applications = Path(tmp) / "Applications"
            applications.mkdir()
            stable_config = make_config_dir(home, "WebStorm2026.1")
            make_config_dir(home, "WebStorm2026.2")
            stable_app = make_app(applications, "WebStorm", "WebStorm", "com.jetbrains.WebStorm", "2026.1.3")
            make_app(applications, "WebStorm 2026.2 EAP", "WebStorm", "com.jetbrains.WebStorm-EAP", "EAP WS-262.8377.39")

            with patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=home), \
                patch_attr(jb_inspect, "discover_ide_app_candidates", return_value=[
                    jb_inspect.ide_app_candidate(stable_app),
                    jb_inspect.ide_app_candidate(applications / "WebStorm 2026.2 EAP.app"),
                ]):
                selection = jb_inspect.resolve_ide_selection({"ide": "WebStorm"})

        self.assertIsNotNone(selection)
        self.assertEqual(selection.channel, "stable")
        self.assertFalse(selection.public()["is_eap"])
        self.assertFalse(selection.public()["explicit_eap"])
        self.assertEqual(selection.version, (2026, 1, 3))
        self.assertEqual(selection.app_path, stable_app)
        self.assertEqual(selection.config_dir, stable_config)

    def test_exact_eap_selection_uses_eap_app_and_matching_config_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            applications = Path(tmp) / "Applications"
            applications.mkdir()
            eap_config = make_config_dir(home, "WebStorm2026.2")
            make_config_dir(home, "WebStorm2026.1")
            make_app(applications, "WebStorm", "WebStorm", "com.jetbrains.WebStorm", "2026.1.3")
            eap_app = make_app(applications, "WebStorm 2026.2 EAP", "WebStorm", "com.jetbrains.WebStorm-EAP", "EAP WS-262.8377.39")

            with patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=home), \
                patch_attr(jb_inspect, "discover_ide_app_candidates", return_value=[
                    jb_inspect.ide_app_candidate(applications / "WebStorm.app"),
                    jb_inspect.ide_app_candidate(eap_app),
                ]):
                selection = jb_inspect.resolve_ide_selection({"ide": "WebStorm", "ide_channel": "eap", "ide_version": "2026.2"})

        self.assertIsNotNone(selection)
        self.assertEqual(selection.channel, "eap")
        self.assertTrue(selection.public()["is_eap"])
        self.assertTrue(selection.public()["explicit_eap"])
        self.assertEqual(selection.version[:2], (2026, 2))
        self.assertEqual(selection.app_path, eap_app)
        self.assertEqual(selection.config_dir, eap_config)

    def test_product_level_ide_does_not_implicitly_fall_back_to_eap(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            applications = Path(tmp) / "Applications"
            applications.mkdir()
            make_config_dir(home, "WebStorm2026.2")
            eap_app = make_app(applications, "WebStorm 2026.2 EAP", "WebStorm", "com.jetbrains.WebStorm-EAP", "EAP WS-262.8377.39")

            with patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=home), \
                patch_attr(jb_inspect, "discover_ide_app_candidates", return_value=[jb_inspect.ide_app_candidate(eap_app)]):
                selection = jb_inspect.resolve_ide_selection({"ide": "WebStorm"})

        self.assertIsNotNone(selection)
        self.assertNotEqual(selection.channel, "eap")
        self.assertFalse(selection.public()["is_eap"])
        self.assertFalse(selection.public()["explicit_eap"])
        self.assertIsNone(selection.app_path)

    def test_exact_eap_selection_without_matching_app_does_not_fall_back_to_generic_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            applications = Path(tmp) / "Applications"
            applications.mkdir()
            eap_config = make_config_dir(home, "WebStorm2026.2")
            make_app(applications, "WebStorm", "WebStorm", "com.jetbrains.WebStorm", "2026.1.3")

            with patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=home), \
                patch_attr(jb_inspect, "discover_ide_app_candidates", return_value=[
                    jb_inspect.ide_app_candidate(applications / "WebStorm.app"),
                ]):
                selection = jb_inspect.resolve_ide_selection({"ide": "WebStorm", "ide_channel": "eap", "ide_version": "2026.2"})

        self.assertIsNotNone(selection)
        self.assertEqual(selection.config_dir, eap_config)
        self.assertIsNone(selection.app_name)
        self.assertIsNone(selection.app_path)

    def test_exact_ide_app_without_matching_candidate_does_not_pair_with_stable_app_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "home"
            applications = Path(tmp) / "Applications"
            applications.mkdir()
            make_config_dir(home, "WebStorm2026.2")
            make_app(applications, "WebStorm", "WebStorm", "com.jetbrains.WebStorm", "2026.1.3")

            with patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=home), \
                patch_attr(jb_inspect, "discover_ide_app_candidates", return_value=[
                    jb_inspect.ide_app_candidate(applications / "WebStorm.app"),
                ]):
                selection = jb_inspect.resolve_ide_selection({"ide": "WebStorm", "ide_app": "WebStorm 2026.2 EAP"})

        self.assertIsNotNone(selection)
        self.assertEqual(selection.app_name, "WebStorm 2026.2 EAP")
        self.assertIsNone(selection.app_path)


class InspectionLaneConfigTest(unittest.TestCase):
    def test_parses_ordered_lanes_and_preserves_legacy_without_lanes(self):
        legacy = jb_inspect.parse_inspection_lanes({"ide": "PyCharm"})
        lanes = jb_inspect.parse_inspection_lanes(
            {
                "lanes": [
                    {
                        "id": "jvm",
                        "ide": "IntelliJ IDEA",
                        "required": True,
                        "include": ["**/*.kt", "**/*.gradle.kts"],
                        "exclude": ["test-fixtures/red-lane/**"],
                        "projectPath": "services/jvm",
                    },
                    {
                        "id": "python",
                        "ide": "PyCharm",
                        "required": False,
                        "include": ["**/*.py"],
                    },
                ]
            }
        )

        self.assertEqual(legacy, ())
        self.assertEqual([lane.lane_id for lane in lanes], ["jvm", "python"])
        self.assertTrue(lanes[0].required)
        self.assertFalse(lanes[1].required)
        self.assertEqual(lanes[0].exclude, ("test-fixtures/red-lane/**",))
        self.assertEqual(lanes[0].project_path, "services/jvm")
        self.assertEqual(lanes[0].public()["projectPath"], "services/jvm")
        self.assertNotIn("projectPath", lanes[1].public())

    def test_rejects_duplicate_ids_invalid_patterns_and_unknown_fields(self):
        cases = [
            {
                "lanes": [
                    {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                    {"id": "jvm", "ide": "PyCharm", "include": ["**/*.py"]},
                ]
            },
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["../**/*.py"]}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/[abc.py"]}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"], "profile": "strict"}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"], "projectPath": "../python"}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"], "projectPath": "/tmp/python"}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"], "projectPath": "python/*"}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"], "projectPath": "."}]},
            {"lanes": [{"id": 1, "ide": "PyCharm", "include": ["**/*.py"]}]},
            {"lanes": [{"id": "python", "ide": "PyCharm", "include": [42]}]},
        ]

        for inspection in cases:
            with self.subTest(inspection=inspection), self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.parse_inspection_lanes(inspection)
            self.assertEqual(raised.exception.payload["error_reason"], "inspection_lane_config_invalid")

    def test_build_context_exposes_lanes_without_selecting_one_ide(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / ".github").mkdir()
            write_json(
                root / ".github" / "github.json",
                {
                    "qualityGate": {
                        "inspection": {
                            "scopePreference": "changed_files",
                            "lanes": [
                                {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                                {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
                            ],
                        }
                    }
                },
            )

            context = jb_inspect.build_context(Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile=""))

        self.assertIsNone(context["ide"])
        self.assertEqual([lane["id"] for lane in context["inspection_lanes"]], ["jvm", "python"])
        self.assertEqual([lane.lane_id for lane in context["_inspection_lanes"]], ["jvm", "python"])

    def test_build_context_rejects_lane_project_path_outside_worktree(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / "repo"
            outside = base / "outside"
            root.mkdir()
            outside.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / "linked").symlink_to(outside, target_is_directory=True)
            (root / ".github").mkdir()
            write_json(
                root / ".github" / "github.json",
                {
                    "qualityGate": {
                        "inspection": {
                            "lanes": [
                                {
                                    "id": "frontend",
                                    "ide": "WebStorm",
                                    "include": ["frontend/**/*.ts"],
                                    "projectPath": "linked",
                                }
                            ]
                        }
                    }
                },
            )

            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.build_context(Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile=""))

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_lane_config_invalid")

    def test_lane_preparation_aggregation_preserves_execution_evidence(self):
        context = {
            "worktree_root": "/tmp/repo",
            "repository_preparation": {
                "configured": True,
                "command": "uv run prepare.py",
                "source": jb_inspect.REPOSITORY_PREPARATION_SOURCE,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_NOT_RUN,
            },
        }
        lane_results = [
            {
                "repository_preparation": {
                    **context["repository_preparation"],
                    "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                    "duration_ms": 25,
                }
            },
            {
                "repository_preparation": {
                    **context["repository_preparation"],
                    "execution_state": jb_inspect.REPOSITORY_PREPARATION_REUSED,
                    "receipt_reused": True,
                }
            },
        ]

        preparation = jb_inspect.aggregate_repository_preparation_for_lanes(lane_results, context)

        self.assertEqual(preparation["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
        self.assertEqual(preparation["duration_ms"], 25)


class LaneIdeAdviceTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch_attr(jb_inspect, "discover_ide_app_candidates", return_value=[]))

    def make_repo(self, root: Path) -> dict[str, Any]:
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        (root / ".github").mkdir()
        (root / "backend").mkdir()
        (root / "backend" / "app.py").write_text("pass\n", encoding="utf-8")
        (root / "App.kt").write_text("class App\n", encoding="utf-8")
        (root / ".gitignore").write_text("prepared\n", encoding="utf-8")
        (root / "prepare.py").write_text(
            "from pathlib import Path\nPath('prepared').write_text('ready')\n", encoding="utf-8"
        )
        metadata = {"qualityGate": {"inspection": {
            "prepare": shlex.join([sys.executable, "prepare.py"]),
            "requiredGeneratedState": ["prepared"],
            "lanes": [
                {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                {"id": "python", "ide": "PyCharm", "include": ["backend/**/*.py"], "projectPath": "backend"},
            ],
        }}}
        write_json(root / ".github" / "github.json", metadata)
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        return metadata

    def test_missing_configured_ide_keeps_repair_advice_in_all_outputs(self):
        for installed in (None, "IntelliJIdea2026.1"):
            with self.subTest(installed=installed), tempfile.TemporaryDirectory() as tmp:
                home = Path(tmp).resolve()
                root = home / "repo"
                metadata = self.make_repo(root)
                del metadata["qualityGate"]["inspection"]["lanes"]
                metadata["qualityGate"]["inspection"]["ide"] = "PyCharm"
                write_json(root / ".github" / "github.json", metadata)
                if installed:
                    make_config_dir(home, installed)
                output = io.StringIO()
                with (
                    patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "",
                                           "JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(home / "absent.json"),
                                           "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root)}),
                    patch.object(sys, "platform", "darwin"),
                    patch_attr(jb_inspect.Path, "home", return_value=home),
                    patch_attr(jb_inspect, "find_exact_route", return_value=None),
                    patch_attr(jb_inspect, "open_project_for_lifecycle") as opened,
                    patch.object(sys, "argv", [str(SCRIPT_PATH), "open-worktree", "--repo", str(root), "--json"]),
                    redirect_stdout(output),
                ):
                    code = jb_inspect.main()
                self.assertEqual(code, 3)
                opened.assert_not_called()
                payload = json.loads(output.getvalue())
                self.assertEqual(payload["error_reason"], "ide_config_missing")
                advice = payload["next_action"]
                self.assertTrue(advice)
                for actual in (payload["hint"], payload["verdict_next_action"], payload["agent_result"]["next_action"]):
                    self.assertEqual(actual, advice)
                human = io.StringIO()
                with redirect_stdout(human):
                    jb_inspect.emit(dict(payload), False, code, command="open-worktree")
                self.assertIn(advice, human.getvalue())
                self.assertEqual(json.loads((root / ".github" / "github.json").read_text()), metadata)

    def test_zero_installed_lane_configs_report_install_prerequisite(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            root = home / "repo"
            metadata = self.make_repo(root)
            output = io.StringIO()
            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "",
                                       "JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(home / "absent.json"),
                                       "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root)}),
                patch.object(sys, "platform", "darwin"),
                patch_attr(jb_inspect.Path, "home", return_value=home),
                patch_attr(jb_inspect, "find_exact_route", return_value=None),
                patch_attr(jb_inspect, "open_project_for_lifecycle") as opened,
                patch.object(sys, "argv", [str(SCRIPT_PATH), "open-worktree", "--repo", str(root), "--json"]),
                redirect_stdout(output),
            ):
                self.assertEqual(jb_inspect.main(), 3)
            opened.assert_not_called()
            payload = json.loads(output.getvalue())
            self.assertEqual(payload["error_reason"], "ide_config_missing")
            self.assertEqual(payload["available_config_dirs"], [])
            advice = payload["agent_result"]["next_action"]
            self.assertTrue(advice)
            self.assertEqual(payload["hint"], advice)
            self.assertEqual(payload["next_action"], advice)
            self.assertEqual(json.loads((root / ".github" / "github.json").read_text()), metadata)

    def test_fresh_install_without_selection_and_missing_explicit_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            for context in ({}, {"ide_config_dir": str(home / "missing-config")}):
                with (
                    self.subTest(context=context),
                    patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}),
                    patch.object(sys, "platform", "darwin"),
                    patch_attr(jb_inspect.Path, "home", return_value=home),
                ):
                    with self.assertRaises(jb_inspect.InspectError) as raised:
                        jb_inspect.jetbrains_config_dirs(context)
                payload = jb_inspect.error_payload(raised.exception)
                jb_inspect.apply_verdict(payload)
                self.assertEqual(payload["error_reason"], "ide_config_missing")
                advice = payload["next_action"]
                self.assertTrue(advice)
                self.assertEqual(payload["hint"], advice)
                self.assertEqual(payload["agent_result"]["next_action"], advice)
                self.assertFalse((home / "missing-config").exists())

    def test_supported_assessments_prepare_and_dispatch_each_configured_lane(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve() / "repo"
            metadata = self.make_repo(root)
            for command in ("inspect", "inspect-closeout"):
                seen = []

                def inspect_lane(_args, context):
                    self.assertTrue((root / "prepared").is_file())
                    seen.append((context["ide"], context["project_path"]))
                    return {"status": "clean", "problems": [], "total_problems": 0, "context": context,
                            "cleanup": {"status": "closed"}}

                with (
                    patch.dict(os.environ, {"JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root),
                                           "JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(root / "absent.json")}),
                    patch_attr(jb_inspect.Path, "home", return_value=root.parent),
                    patch_attr(jb_inspect, "run_prepared_inspection", side_effect=inspect_lane),
                    patch.object(sys, "argv", [str(SCRIPT_PATH), command,
                                               "--repo", str(root), "--scope", "files",
                                               "--file", "App.kt", "--file", "backend/app.py", "--json"]),
                    redirect_stdout(io.StringIO()),
                ):
                    code = jb_inspect.main()
                self.assertEqual(code, 0)
                self.assertEqual(seen, [("IntelliJ IDEA", str(root)), ("PyCharm", str(root / "backend"))])
            self.assertEqual(json.loads((root / ".github" / "github.json").read_text()), metadata)

    def test_explicit_open_selects_one_nested_lane_ide_after_preparation(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            root = home / "repo"
            metadata = self.make_repo(root)
            (root / "backend" / "pyproject.toml").write_text("", encoding="utf-8")
            pycharm = make_config_dir(home, "PyCharm2026.1")
            make_config_dir(home, "IntelliJIdea2026.1")
            route = {"base_path": str(root / "backend"), "project_instance_id": "session:1",
                     "project_key": "path:backend", "session_id": "session"}

            def open_project(_args, context, _lease):
                self.assertTrue((root / "prepared").is_file())
                self.assertEqual(context["ide"], "PyCharm")
                self.assertEqual(context["project_path"], str(root / "backend"))
                self.assertEqual(jb_inspect.jetbrains_config_dirs(context), [pycharm])
                return "fixture", [], False

            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "", "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root),
                                       "JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(home / "absent.json")}),
                patch.object(sys, "platform", "darwin"),
                patch_attr(jb_inspect.Path, "home", return_value=home),
                patch_attr(jb_inspect, "find_exact_route", return_value=None),
                patch_attr(jb_inspect, "open_project_for_lifecycle", side_effect=open_project) as opened,
                patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
                patch_attr(jb_inspect, "claim_lifecycle", return_value=unowned_lifecycle_result(route)),
                patch_attr(jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry", return_value={"ready": True}),
                patch.object(sys, "argv", [str(SCRIPT_PATH), "open-worktree", "--repo", str(root / "backend"),
                                           "--ide", "PyCharm", "--json"]),
                redirect_stdout(io.StringIO()),
            ):
                self.assertEqual(jb_inspect.main(), 0)
                opened.assert_called_once()
            self.assertEqual(json.loads((root / ".github" / "github.json").read_text()), metadata)

    def test_low_level_route_error_keeps_lane_context_without_lifecycle_wrapper(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            root = home / "repo"
            self.make_repo(root)
            make_config_dir(home, "PyCharm2026.1")
            make_config_dir(home, "IntelliJIdea2026.1")
            output = io.StringIO()
            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "", "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root),
                                       "JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(home / "absent.json")}),
                patch.object(sys, "platform", "darwin"),
                patch_attr(jb_inspect.Path, "home", return_value=home),
                patch_attr(jb_inspect, "discover_identities", return_value=[]),
                patch_attr(jb_inspect, "open_project_for_lifecycle") as opened,
                patch.object(sys, "argv", [str(SCRIPT_PATH), "get-status", "--repo", str(root), "--open", "--json"]),
                redirect_stdout(output),
            ):
                self.assertEqual(jb_inspect.main(), 3)
                opened.assert_not_called()
            payload = json.loads(output.getvalue())
            self.assertEqual(payload["error_reason"], "ide_selection_required")
            self.assertEqual(payload["next_action"], payload["agent_result"]["next_action"])
            self.assertIn("open-worktree --ide", payload["hint"])
            self.assertNotIn("prepared", [entry.name for entry in root.iterdir()])

    def test_explicit_lane_open_with_missing_ide_recommends_configuration_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            root = home / "repo"
            metadata = self.make_repo(root)
            make_config_dir(home, "IntelliJIdea2026.1")
            output = io.StringIO()
            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": "", "JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS": str(root),
                                       "JETBRAINS_INSPECTION_GLOBAL_CONFIG": str(home / "absent.json")}),
                patch.object(sys, "platform", "darwin"),
                patch_attr(jb_inspect.Path, "home", return_value=home),
                patch_attr(jb_inspect, "find_exact_route", return_value=None),
                patch_attr(jb_inspect, "open_project_for_lifecycle") as opened,
                patch.object(sys, "argv", [str(SCRIPT_PATH), "open-worktree", "--repo", str(root), "--ide", "PyCharm", "--json"]),
                redirect_stdout(output),
            ):
                self.assertEqual(jb_inspect.main(), 3)
                opened.assert_not_called()
            payload = json.loads(output.getvalue())
            self.assertEqual(payload["error_reason"], "ide_config_missing")
            advice = payload["agent_result"]["next_action"]
            self.assertEqual(payload["next_action"], advice)
            self.assertTrue(advice)
            self.assertEqual(json.loads((root / ".github" / "github.json").read_text()), metadata)

    def test_selected_lane_configuration_failure_preserves_lane_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve()
            make_config_dir(home, "IntelliJIdea2026.1")
            context = {"ide": "PyCharm", "inspection_lane": {"id": "python", "ide": "PyCharm"}}
            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}),
                patch.object(sys, "platform", "darwin"),
                patch_attr(jb_inspect.Path, "home", return_value=home),
            ):
                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.jetbrains_config_dirs(context)
            payload = jb_inspect.error_payload(raised.exception)
            jb_inspect.apply_verdict(payload)
            self.assertEqual(payload["error_reason"], "ide_config_missing")
            self.assertEqual(payload["next_action"], payload["agent_result"]["next_action"])
            for reason in jb_inspect.IDE_ROUTE_CONFIGURATION_REASONS:
                advice = jb_inspect.next_action_for_unknown(reason, {"context": context})
                self.assertEqual(advice, jb_inspect.hint_for_error_reason(reason, context))
                self.assertIn("lane", advice)
                self.assertNotIn("jetbrains.ide =", advice)
                self.assertNotIn("--ide", advice)


class InspectionLaneSelectionTest(unittest.TestCase):
    def make_context(self, root: Path, lanes: tuple[Any, ...], scope: str = "files") -> dict[str, Any]:
        return {
            "repo_path": str(root),
            "worktree_root": str(root),
            "project_path": str(root),
            "exact_route_path": str(root),
            "lifecycle_target_path": str(root),
            "scope": scope,
            "_inspection_lanes": lanes,
        }

    def test_first_match_assignment_exclusions_and_unmatched_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            files = [
                root / "build.gradle.kts",
                root / "src" / "Example.kt",
                root / "tools" / "check.py",
                root / "test-fixtures" / "red-lane" / "Broken.py",
                root / "README.md",
            ]
            for path in files:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {
                            "id": "jvm",
                            "ide": "IntelliJ IDEA",
                            "include": ["**/*.kt", "**/*.gradle.kts"],
                        },
                        {
                            "id": "python",
                            "ide": "PyCharm",
                            "include": ["**/*.py", "tools/**"],
                            "exclude": ["test-fixtures/red-lane/**"],
                        },
                    ]
                }
            )
            args = helper_args(
                scope="files",
                files=[str(path) for path in files],
                include_unversioned=True,
                changed_files_mode="all",
                max_files=None,
            )

            selection = jb_inspect.resolve_inspection_lane_selection(args, self.make_context(root, lanes))

        self.assertEqual(
            [item["file"] for item in selection["lane_files"]["jvm"]],
            ["build.gradle.kts", "src/Example.kt"],
        )
        self.assertEqual(
            [item["file"] for item in selection["lane_files"]["python"]],
            ["tools/check.py", "test-fixtures/red-lane/Broken.py"],
        )
        self.assertEqual(selection["excluded_files"], [])
        self.assertEqual(selection["explicit_exclusion_overrides"][0]["file"], "test-fixtures/red-lane/Broken.py")
        self.assertEqual(selection["unmatched_files"], ["README.md"])

    def test_overlap_is_deterministic_first_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            target = root / "tools" / "check.py"
            target.parent.mkdir()
            target.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "first", "ide": "PyCharm", "include": ["tools/**"]},
                        {"id": "second", "ide": "PyCharm", "include": ["**/*.py"]},
                    ]
                }
            )
            args = helper_args(scope="files", files=[str(target)], max_files=None)

            selection = jb_inspect.resolve_inspection_lane_selection(args, self.make_context(root, lanes))

        self.assertEqual(len(selection["lane_files"]["first"]), 1)
        self.assertEqual(selection["lane_files"]["second"], [])

    def test_single_star_does_not_cross_path_segments(self):
        self.assertTrue(jb_inspect.inspection_lane_pattern_matches("src/check.py", "src/*.py"))
        self.assertFalse(jb_inspect.inspection_lane_pattern_matches("src/nested/check.py", "src/*.py"))
        self.assertTrue(jb_inspect.inspection_lane_pattern_matches("src/nested/check.py", "src/**/*.py"))
        self.assertTrue(jb_inspect.inspection_lane_pattern_matches("check.py", "**/*.py"))

    def test_rejects_file_outside_exact_worktree(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.NamedTemporaryFile() as outside:
            root = Path(tmp).resolve()
            lanes = jb_inspect.parse_inspection_lanes(
                {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"]}]}
            )
            args = helper_args(scope="files", files=[outside.name], max_files=None)

            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.resolve_inspection_lane_selection(args, self.make_context(root, lanes))

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_lane_path_outside_worktree")

    def test_changed_files_are_resolved_once_and_partitioned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            kotlin = root / "src" / "Example.kt"
            python = root / "tools" / "check.py"
            kotlin.parent.mkdir()
            python.parent.mkdir()
            kotlin.write_text("class Example\n", encoding="utf-8")
            python.write_text("print('ok')\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "fixture"],
                cwd=root,
                check=True,
            )
            kotlin.write_text("class Updated\n", encoding="utf-8")
            python.write_text("print('updated')\n", encoding="utf-8")
            red_fixture = root / "test-fixtures" / "red-lane" / "Broken.py"
            red_fixture.parent.mkdir(parents=True)
            red_fixture.write_text("broken =\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                        {
                            "id": "python",
                            "ide": "PyCharm",
                            "include": ["**/*.py"],
                            "exclude": ["test-fixtures/red-lane/**"],
                        },
                    ]
                }
            )
            args = helper_args(
                scope="changed_files",
                files=[],
                changed_files_mode="all",
                include_unversioned=True,
                max_files=None,
            )

            selection = jb_inspect.resolve_inspection_lane_selection(
                args,
                self.make_context(root, lanes, scope="changed_files"),
            )

        self.assertEqual(
            selection["selected_files"],
            ["src/Example.kt", "tools/check.py", "test-fixtures/red-lane/Broken.py"],
        )
        self.assertEqual([item["file"] for item in selection["lane_files"]["jvm"]], ["src/Example.kt"])
        self.assertEqual([item["file"] for item in selection["lane_files"]["python"]], ["tools/check.py"])
        self.assertEqual(selection["excluded_files"][0]["file"], "test-fixtures/red-lane/Broken.py")


class InspectionLaneExecutionTest(unittest.TestCase):
    def test_lane_context_uses_lane_project_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            frontend = root / "frontend"
            frontend.mkdir()
            source = frontend / "app.ts"
            source.write_text("fixture\n", encoding="utf-8")
            lane = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {
                            "id": "frontend",
                            "ide": "WebStorm",
                            "include": ["frontend/**/*.ts"],
                            "projectPath": "frontend",
                        }
                    ]
                }
            )[0]
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
            }
            args = helper_args(scope="files", files=[str(source)], max_files=None, profile="")
            lane_args = jb_inspect.inspection_lane_args(
                args,
                lane,
                [{"file": "frontend/app.ts", "absolute_path": str(source)}],
            )

            lane_context = jb_inspect.inspection_lane_context(
                context,
                lane_args,
                lane,
                [{"file": "frontend/app.ts", "absolute_path": str(source)}],
            )

        self.assertEqual(lane_args.project_path, str(frontend))
        self.assertEqual(lane_context["repo_path"], str(frontend))
        self.assertEqual(lane_context["project_path"], str(frontend))
        self.assertEqual(lane_context["exact_route_path"], str(frontend))
        self.assertEqual(lane_context["lifecycle_target_path"], str(frontend))

    def test_lane_context_rejects_selected_files_outside_lane_project_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            frontend = root / "frontend"
            frontend.mkdir()
            source = root / "tools" / "check.ts"
            source.parent.mkdir()
            source.write_text("fixture\n", encoding="utf-8")
            lane = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {
                            "id": "frontend",
                            "ide": "WebStorm",
                            "include": ["**/*.ts"],
                            "projectPath": "frontend",
                        }
                    ]
                }
            )[0]
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
            }
            selected = [{"file": "tools/check.ts", "absolute_path": str(source)}]
            args = helper_args(scope="files", files=[str(source)], max_files=None, profile="")
            lane_args = jb_inspect.inspection_lane_args(args, lane, selected)

            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.inspection_lane_context(context, lane_args, lane, selected)

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_lane_config_invalid")
        self.assertIn("outside projectPath", str(raised.exception))

    def test_lane_project_path_may_be_created_by_repository_preparation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / ".github").mkdir()
            write_json(
                root / ".github" / "github.json",
                {
                    "qualityGate": {
                        "inspection": {
                            "lanes": [
                                {
                                    "id": "frontend",
                                    "ide": "WebStorm",
                                    "include": ["frontend/**/*.ts"],
                                    "projectPath": "frontend",
                                }
                            ]
                        }
                    }
                },
            )

            context = jb_inspect.build_context(
                Namespace(repo=str(root), ide=None, ide_app=None, scope=None, profile="")
            )

        self.assertEqual(context["inspection_lanes"][0]["projectPath"], "frontend")

    def test_lane_context_rechecks_project_path_containment_after_preparation(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp).resolve()
            root = base / "repo"
            outside = base / "outside"
            root.mkdir()
            outside.mkdir()
            source = root / "services" / "api" / "app.py"
            source.parent.mkdir(parents=True)
            source.write_text("fixture\n", encoding="utf-8")
            lane = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {
                            "id": "python",
                            "ide": "PyCharm",
                            "include": ["services/api/**/*.py"],
                            "projectPath": "generated-project",
                        }
                    ]
                }
            )[0]
            jb_inspect.validate_inspection_lane_project_paths((lane,), root)
            (root / "generated-project").symlink_to(outside, target_is_directory=True)
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
            }
            selected = [{"file": "services/api/app.py", "absolute_path": str(source)}]
            args = helper_args(scope="files", files=[str(source)], max_files=None, profile="")
            lane_args = jb_inspect.inspection_lane_args(args, lane, selected)

            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.inspection_lane_context(context, lane_args, lane, selected)

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_lane_config_invalid")
        self.assertIn("after repository preparation", str(raised.exception))

    def test_runs_non_empty_lanes_sequentially_with_exact_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            kotlin = root / "src" / "Example.kt"
            python = root / "tools" / "check.py"
            for path in (kotlin, python):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                        {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
                    ]
                }
            )
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "repo_head_sha": None,
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
                "_inspection_lanes": lanes,
                "inspection_lanes": [lane.public() for lane in lanes],
            }
            args = helper_args(
                scope="files",
                files=[str(kotlin), str(python)],
                max_files=None,
                profile="strict",
                include_unversioned=True,
                changed_files_mode="all",
            )
            calls = []

            def run_lane(lane_args, lane_context):
                calls.append((lane_args.ide, list(lane_args.files), lane_context["scope"]))
                return {
                    "status": "clean",
                    "total_problems": 0,
                    "problems_shown": 0,
                    "problems": [],
                    "context": jb_inspect.public_context(lane_context),
                    "route": {
                        "project_name": lane_args.ide,
                        "project_key": f"key-{len(calls)}",
                        "project_instance_id": f"instance-{len(calls)}",
                        "session_id": f"session-{len(calls)}",
                        "base_path": str(root),
                        "ide": {"name": lane_args.ide, "plugin_version": "1.2.3", "plugin_build_fingerprint": "sha256:" + "a" * 64},
                    },
                    "cleanup": {"status": "closed", "reason": "helper_owned"},
                }

            with patch_attr(jb_inspect, "run_prepared_inspection", side_effect=run_lane):
                result = jb_inspect.run_configured_inspection_lanes(args, context)

        self.assertEqual([call[0] for call in calls], ["IntelliJ IDEA", "PyCharm"])
        self.assertEqual(calls[0][1], [str(kotlin)])
        self.assertEqual(calls[1][1], [str(python)])
        self.assertEqual([call[2] for call in calls], ["files", "files"])
        self.assertEqual(result["verdict"], "GREEN")
        self.assertEqual([lane["execution_order"] for lane in result["lane_results"]], [0, 1])
        self.assertEqual(result["lane_results"][0]["route"]["session_id"], "session-1")
        self.assertEqual(result["lane_results"][1]["ide"]["plugin_version"], "1.2.3")

    def test_empty_lane_does_not_start_ide(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            python = root / "check.py"
            python.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                        {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
                    ]
                }
            )
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
                "_inspection_lanes": lanes,
            }
            args = helper_args(scope="files", files=[str(python)], max_files=None, profile="")
            with patch_attr(
                jb_inspect,
                "run_prepared_inspection",
                return_value={"status": "clean", "total_problems": 0, "problems": [], "cleanup": {"status": "not_needed"}},
            ) as run:
                result = jb_inspect.run_configured_inspection_lanes(args, context)

        self.assertEqual(run.call_count, 1)
        self.assertEqual(result["lane_results"][0]["verdict"], "NOT_RUN")
        self.assertEqual(result["lane_results"][0]["cleanup"]["reason"], "lane_empty")

    def test_clean_committed_branch_has_unknown_empty_changed_files_scope(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / "check.py").write_text("print('committed')\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                 "commit", "-qm", "fixture"], cwd=root, check=True,
            )
            lanes = jb_inspect.parse_inspection_lanes(
                {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"]}]}
            )
            context = {
                "repo_path": str(root), "worktree_root": str(root), "project_path": str(root),
                "scope": "changed_files", "_inspection_lanes": lanes,
            }
            args = helper_args(scope="changed_files", changed_files_mode="all",
                               include_unversioned=True, max_files=None)
            with patch_attr(jb_inspect, "run_prepared_inspection") as inspect:
                result = jb_inspect.run_configured_inspection_lanes(args, context)
                compact = jb_inspect.compact_agent_result_payload(result, 1)
                inspect.assert_not_called()
        self.assertEqual(compact["selected_file_count"], 0)
        self.assertEqual(compact["agent_result"]["verdict"], "UNKNOWN")
        self.assertEqual(compact["lanes"][0]["verdict"], "NOT_RUN")
        self.assertIn("--scope files", compact["agent_result"]["next_action"])

    def test_unmatched_explicit_files_point_to_lane_matching(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            target = root / "README.md"
            target.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {"lanes": [{"id": "python", "ide": "PyCharm", "include": ["**/*.py"]}]}
            )
            context = {
                "repo_path": str(root), "worktree_root": str(root), "project_path": str(root),
                "scope": "files", "_inspection_lanes": lanes,
            }
            args = helper_args(scope="files", files=[str(target)], max_files=None)
            with patch_attr(jb_inspect, "run_prepared_inspection") as inspect:
                result = jb_inspect.run_configured_inspection_lanes(args, context)
                compact = jb_inspect.compact_agent_result_payload(result, 1)
                inspect.assert_not_called()
        self.assertEqual(compact["selected_file_count"], 1)
        self.assertEqual(compact["selection"]["unmatched_files"], [target.name])
        self.assertEqual(compact["agent_result"]["verdict"], "UNKNOWN")
        self.assertIn("lane include/exclude globs", compact["agent_result"]["next_action"])

    def test_repository_preparation_runs_once_before_multiple_active_lanes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            kotlin = root / "Example.kt"
            python = root / "check.py"
            kotlin.write_text("fixture\n", encoding="utf-8")
            python.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                        {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
                    ]
                }
            )
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
                "_inspection_lanes": lanes,
                "repository_preparation": {
                    "configured": True,
                    "command": "uv run prepare.py",
                    "source": jb_inspect.REPOSITORY_PREPARATION_SOURCE,
                    "execution_state": jb_inspect.REPOSITORY_PREPARATION_NOT_RUN,
                },
            }
            args = helper_args(scope="files", files=[str(kotlin), str(python)], max_files=None, profile="")
            prepared_state = {
                **context["repository_preparation"],
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                "duration_ms": 25,
            }
            lane_contexts = []

            def run_lane(_lane_args, lane_context):
                lane_contexts.append(lane_context)
                return {
                    "status": "clean",
                    "total_problems": 0,
                    "problems": [],
                    "prepared": {"repository_preparation": lane_context["repository_preparation"]},
                    "cleanup": {"status": "closed"},
                }

            with (
                patch_attr(jb_inspect, "run_repository_preparation", return_value=prepared_state) as prepare,
                patch_attr(jb_inspect, "run_prepared_inspection", side_effect=run_lane),
            ):
                result = jb_inspect.run_configured_inspection_lanes(args, context)

        self.assertEqual(prepare.call_count, 1)
        self.assertEqual(len(lane_contexts), 2)
        self.assertTrue(all(item["_repository_preparation_completed"] is True for item in lane_contexts))
        self.assertEqual(result["repository_preparation"]["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)
        self.assertEqual(result["agent_result"]["repository_preparation"]["execution_state"], jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED)

    def test_lane_failure_does_not_prevent_later_lane_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            kotlin = root / "Example.kt"
            python = root / "check.py"
            kotlin.write_text("fixture\n", encoding="utf-8")
            python.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                        {"id": "python", "ide": "PyCharm", "include": ["**/*.py"]},
                    ]
                }
            )
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
                "_inspection_lanes": lanes,
            }
            args = helper_args(scope="files", files=[str(kotlin), str(python)], max_files=None, profile="")
            calls = []

            def run_lane(lane_args, _lane_context):
                calls.append(lane_args.ide)
                if lane_args.ide == "IntelliJ IDEA":
                    raise jb_inspect.InspectError(
                        "plugin missing",
                        3,
                        {"error_reason": "inspection_api_unavailable", "cleanup": {"status": "not_needed"}},
                    )
                return {"status": "clean", "total_problems": 0, "problems": [], "cleanup": {"status": "closed"}}

            with patch_attr(jb_inspect, "run_prepared_inspection", side_effect=run_lane):
                result = jb_inspect.run_configured_inspection_lanes(args, context)

        self.assertEqual(calls, ["IntelliJ IDEA", "PyCharm"])
        self.assertEqual(result["lane_results"][0]["bucket"], "environment_blocked")
        self.assertEqual(result["lane_results"][1]["verdict"], "GREEN")
        self.assertEqual(result["verdict"], "UNKNOWN")

    def test_lane_context_failure_preserves_completed_lane_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            kotlin = root / "Example.kt"
            python = root / "check.py"
            nested_python = root / "services" / "python"
            nested_python.mkdir(parents=True)
            kotlin.write_text("fixture\n", encoding="utf-8")
            python.write_text("fixture\n", encoding="utf-8")
            lanes = jb_inspect.parse_inspection_lanes(
                {
                    "lanes": [
                        {"id": "jvm", "ide": "IntelliJ IDEA", "include": ["**/*.kt"]},
                        {
                            "id": "python",
                            "ide": "PyCharm",
                            "include": ["**/*.py"],
                            "projectPath": "services/python",
                        },
                    ]
                }
            )
            context = {
                "repo_path": str(root),
                "worktree_root": str(root),
                "project_path": str(root),
                "exact_route_path": str(root),
                "lifecycle_target_path": str(root),
                "scope": "files",
                "_inspection_lanes": lanes,
            }
            args = helper_args(scope="files", files=[str(kotlin), str(python)], max_files=None, profile="")

            with patch_attr(
                jb_inspect,
                "run_prepared_inspection",
                return_value={
                    "status": "clean",
                    "total_problems": 0,
                    "problems": [],
                    "cleanup": {"status": "closed"},
                },
            ) as run:
                result = jb_inspect.run_configured_inspection_lanes(args, context)

        self.assertEqual(run.call_count, 1)
        self.assertEqual(len(result["lane_results"]), 2)
        self.assertEqual(result["lane_results"][0]["verdict"], "GREEN")
        self.assertEqual(
            result["lane_results"][1]["bucket"],
            "policy_required",
            result["lane_results"][1],
        )
        self.assertEqual(result["lane_results"][1]["verdict"], "UNKNOWN")
        self.assertEqual(result["lane_results"][1]["blocker_stage"], "configuration")
        self.assertEqual(result["verdict"], "UNKNOWN")

    def test_aggregate_precedence_and_optional_failures(self):
        def lane(lane_id, verdict, required=True):
            return {
                "id": lane_id,
                "required": required,
                "verdict": verdict,
                "retry_policy": {"retry": verdict == "UNKNOWN", "max_attempts": 1, "wait_ms": 1000},
            }

        cases = [
            ([lane("green", "GREEN"), lane("red", "RED"), lane("unknown", "UNKNOWN")], "RED"),
            ([lane("green", "GREEN"), lane("unknown", "UNKNOWN")], "UNKNOWN"),
            ([lane("green", "GREEN"), lane("optional-red", "RED", required=False)], "GREEN"),
            ([lane("optional-red", "RED", required=False)], "GREEN"),
            ([lane("empty", "NOT_RUN")], "UNKNOWN"),
            ([lane("invalid", None)], "UNKNOWN"),
        ]
        for lanes, expected in cases:
            with self.subTest(expected=expected):
                payload = {"lane_results": lanes}
                jb_inspect.apply_multi_lane_verdict(payload)
                self.assertEqual(payload["verdict"], expected)

    def test_lane_failure_buckets_remain_distinct(self):
        reasons = {
            "inspection_api_unavailable": "environment_blocked",
            "scope_semantic_coverage_missing": "environment_blocked",
            "language_sdk_missing": "environment_blocked",
            "project_analysis_not_ready": "capture_not_ready",
            "inspection_api_http_error": "tool_bug",
            "ambiguous_route": "route_not_ready",
            "session_drift": "route_not_ready",
            "worktree_mutation_detected": "cleanup_not_clean",
        }
        for reason, expected_bucket in reasons.items():
            with self.subTest(reason=reason):
                payload = {"status": "error", "error_reason": reason}
                jb_inspect.apply_verdict(payload)
                self.assertEqual(payload["bucket"], expected_bucket)

    def test_compact_agent_result_keeps_lane_provenance(self):
        payload = {
            "lane_selection": {"scope": "changed_files", "excluded_files": [], "unmatched_files": []},
            "lane_results": [
                {
                    "id": "jvm",
                    "required": True,
                    "execution_order": 0,
                    "verdict": "GREEN",
                    "bucket": "clean",
                    "retry_policy": {"retry": False, "max_attempts": 0, "wait_ms": 0},
                    "files": ["/tmp/worktree/Example.kt"],
                    "ide": {
                        "product": "IntelliJ IDEA",
                        "version": "2026.1",
                        "plugin_version": "1.2.3",
                        "plugin_build_fingerprint": "sha256:" + "a" * 64,
                    },
                    "route": {"session_id": "session-1"},
                    "evidence_ids": {"inspection_run_id": 42, "request_id": "request-1"},
                    "cleanup": {"status": "closed"},
                    "finding_count": 0,
                    "findings": [],
                }
            ],
        }

        compact = jb_inspect.compact_agent_result_payload(payload, 0)

        self.assertEqual(compact["aggregate"]["verdict"], "GREEN")
        self.assertEqual(compact["lanes"][0]["route"]["session_id"], "session-1")
        self.assertEqual(compact["lanes"][0]["ide"]["plugin_version"], "1.2.3")
        self.assertEqual(compact["lanes"][0]["evidence_ids"]["inspection_run_id"], 42)

    def test_compact_agent_result_bounds_lane_file_lists(self):
        files = [f"/tmp/worktree/file-{index}.py" for index in range(jb_inspect.MAX_LANE_FILE_PATHS + 5)]
        payload = {
            "lane_selection": {
                "scope": "whole_project",
                "selected_files": [f"file-{index}.py" for index in range(jb_inspect.MAX_LANE_FILE_PATHS + 5)],
            },
            "lane_results": [
                {
                    "id": "python",
                    "required": True,
                    "verdict": "GREEN",
                    "retry_policy": {"retry": False, "max_attempts": 0, "wait_ms": 0},
                    "files": files,
                    "relative_files": [Path(path).name for path in files],
                    "cleanup": {"status": "closed"},
                }
            ],
        }

        compact = jb_inspect.compact_agent_result_payload(payload, 0)

        self.assertEqual(len(compact["lanes"][0]["files"]), jb_inspect.MAX_LANE_FILE_PATHS)
        self.assertEqual(compact["lanes"][0]["files_omitted_count"], 5)
        self.assertEqual(len(compact["selection"]["selected_files"]), jb_inspect.MAX_LANE_FILE_PATHS)
        self.assertEqual(compact["selection"]["selected_files_omitted_count"], 5)

    def test_worktree_mutation_is_fail_closed(self):
        result = {
            "status": "clean",
            "total_problems": 0,
            "worktree_mutation_evidence": {
                "new_or_changed_path_count": 1,
                "removed_path_count": 0,
                "new_or_changed_paths": [".idea/workspace.xml"],
            },
        }

        jb_inspect.apply_worktree_mutation_blocker(result)
        jb_inspect.apply_verdict(result)

        self.assertTrue(result["worktree_mutation_detected"])
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertEqual(result["bucket"], "cleanup_not_clean")

    def test_lane_mutation_snapshot_waits_for_delayed_ide_writes(self):
        with (
            patch.object(time, "sleep") as sleep,
            patch_attr(
                jb_inspect,
                "git_worktree_status_snapshot",
                return_value={"status": "ok", "entries": {".idea/modules.xml": " D"}},
            ),
        ):
            snapshot = jb_inspect.post_cleanup_worktree_status_snapshot(
                {"worktree_root": "/tmp/repo", "inspection_lane": {"id": "jvm"}}
            )

        sleep.assert_called_once_with(jb_inspect.LANE_MUTATION_SETTLE_DELAY_MS / 1000.0)
        self.assertEqual(snapshot["settle_delay_ms"], jb_inspect.LANE_MUTATION_SETTLE_DELAY_MS)


class WorktreeSafetyTest(unittest.TestCase):
    def test_rejects_route_outside_current_worktree(self):
        route = {"base_path": "/tmp/main-checkout"}
        context = {"worktree_root": "/tmp/linked-worktree", "worktree_strategy": "prefer-current"}
        args = Namespace(no_worktree_check=False)

        with self.assertRaises(jb_inspect.InspectError) as raised:
            jb_inspect.ensure_worktree_safe(route, context, args)

        self.assertIn("wrong tree", str(raised.exception))
        self.assertEqual(raised.exception.exit_code, 3)

    def test_rejects_route_without_canonical_path(self):
        context = {"worktree_root": "/tmp/linked-worktree", "worktree_strategy": "prefer-current"}
        args = Namespace(no_worktree_check=False)

        with self.assertRaises(jb_inspect.InspectError) as raised:
            jb_inspect.ensure_worktree_safe({"project_key": "opaque"}, context, args)

        self.assertEqual(raised.exception.payload["error_reason"], "route_path_missing")

    def test_allows_current_worktree_inside_open_project(self):
        route = {"base_path": "/tmp/main-checkout"}
        context = {"worktree_root": "/tmp/main-checkout/packages/app", "worktree_strategy": "prefer-current"}
        args = Namespace(no_worktree_check=False)

        jb_inspect.ensure_worktree_safe(route, context, args)

    def test_allows_open_project_inside_current_worktree(self):
        route = {"base_path": "/tmp/current-worktree/packages/app"}
        context = {"worktree_root": "/tmp/current-worktree", "worktree_strategy": "prefer-current"}
        args = Namespace(no_worktree_check=False)

        jb_inspect.ensure_worktree_safe(route, context, args)

    def test_approval_flag_allows_any_worktree(self):
        route = {"base_path": "/tmp/main-checkout"}
        context = {"worktree_root": "/tmp/linked-worktree", "worktree_strategy": "prefer-current"}
        args = Namespace(no_worktree_check=True)

        jb_inspect.ensure_worktree_safe(route, context, args)

    def test_exact_worktree_rejects_containing_project(self):
        route = {"base_path": "/tmp/main-checkout"}
        context = {"worktree_root": "/tmp/main-checkout/packages/app"}
        args = Namespace(no_worktree_check=False)

        with self.assertRaises(jb_inspect.InspectError):
            jb_inspect.ensure_exact_worktree(route, context, args)

    def test_route_sort_key_prefers_exact_worktree_for_equal_scores(self):
        context = {"worktree_root": "/tmp/repo/packages/app"}
        parent = {"score": 930, "base_path": "/tmp/repo"}
        child = {"score": 930, "base_path": "/tmp/repo/packages/app"}

        routes = sorted([parent, child], key=lambda route: jb_inspect.route_sort_key(route, context), reverse=True)

        self.assertEqual(routes[0], child)

    def test_route_sort_key_prefers_exact_nested_project_for_equal_scores(self):
        context = {"worktree_root": "/tmp/repo", "exact_route_path": "/tmp/repo/packages/app"}
        parent = {"score": 930, "base_path": "/tmp/repo"}
        child = {"score": 930, "base_path": "/tmp/repo/packages/app"}

        routes = sorted([parent, child], key=lambda route: jb_inspect.route_sort_key(route, context), reverse=True)

        self.assertEqual(routes[0], child)

    def test_route_sort_key_prefers_deeper_containing_project_for_equal_scores(self):
        context = {"worktree_root": "/tmp/repo/packages/app/src/main"}
        parent = {"score": 930, "base_path": "/tmp/repo"}
        child = {"score": 930, "base_path": "/tmp/repo/packages/app"}

        routes = sorted([parent, child], key=lambda route: jb_inspect.route_sort_key(route, context), reverse=True)

        self.assertEqual(routes[0], child)

    def test_flat_project_matches_exact_nested_project(self):
        project = {"base_path": "/tmp/repo/packages/app"}
        context = {"worktree_root": "/tmp/repo", "project_path": "/tmp/repo/packages/app", "exact_route_path": "/tmp/repo/packages/app"}

        self.assertTrue(jb_inspect.flat_project_matches_context(project, context))


class LifecycleTest(unittest.TestCase):
    def test_emit_redacts_sensitive_keys_from_json(self):
        payload = {
            "status": "prepared",
            "close_token": "value-that-must-not-print",
            "nested": {"password": "another-value-that-must-not-print", "project_key": "path:/tmp/repo"},
        }

        output = io.StringIO()
        with redirect_stdout(output):
            exit_code = jb_inspect.emit(payload, json_only=True, exit_code=0)

        self.assertEqual(exit_code, 0)
        body = json.loads(output.getvalue())
        self.assertEqual(body["close_token"], jb_inspect.REDACTED)
        self.assertEqual(body["nested"]["password"], jb_inspect.REDACTED)
        self.assertEqual(body["nested"]["project_key"], "path:/tmp/repo")
        self.assertNotIn("value-that-must-not-print", output.getvalue())
        self.assertNotIn("another-value-that-must-not-print", output.getvalue())

    def test_emit_strips_private_fields_from_json(self):
        payload = {"status": "prepared", "_control": {"secret": "private-value"}}

        output = io.StringIO()
        with redirect_stdout(output):
            exit_code = jb_inspect.emit(payload, json_only=True, exit_code=0)

        self.assertEqual(exit_code, 0)
        body = json.loads(output.getvalue())
        self.assertNotIn("_control", body)
        self.assertNotIn("private-value", output.getvalue())

    def test_repository_preparation_agent_and_durable_state_is_bounded_and_redacted(self):
        command = "uv run /Users/alice/Project With Space/prepare.py --token secret-value"
        preparation = {
            "configured": True,
            "command": command,
            "source": "qualityGate.inspection.prepare",
            "execution_state": "not_run",
            "target_worktree": "/Users/alice/Project With Space",
        }
        payload = {
            "status": "results_available",
            "context": {"repository_preparation": preparation},
            "agent_result": {
                "next_action": f"Run `{command}` in the target worktree, then rerun inspection.",
                "agent_report": f"Preparation remains pending: `{command}`.",
            },
            "total_problems": 0,
            "problems_shown": 0,
            "problems": [],
        }

        compact = jb_inspect.compact_agent_result_payload(payload, 0)
        durable = jb_inspect.outcome_log_record(payload, 0)

        self.assertEqual(compact["repository_preparation"]["execution_state"], "not_run")
        self.assertLessEqual(
            len(compact["repository_preparation"]["command"]),
            jb_inspect.MAX_REPOSITORY_PREPARATION_COMMAND_LENGTH,
        )
        self.assertEqual(compact["repository_preparation"]["command"], command)
        durable_json = json.dumps(durable)
        self.assertNotIn("/Users/alice/Project With Space", durable_json)
        self.assertNotIn("secret-value", durable_json)
        self.assertIn("then rerun inspection", durable["next_action"])
        self.assertIn(jb_inspect.REDACTED, durable_json)
        self.assertNotIn("target_worktree", durable["repository_preparation"])
        self.assertIn("target_worktree_hash", durable["repository_preparation"])

    def test_prepare_lifecycle_does_not_return_private_close_control(self):
        original_create = jb_inspect.create_local_lease
        original_find = jb_inspect.find_exact_route
        original_ensure = jb_inspect.ensure_exact_worktree
        original_wait_ready = jb_inspect.wait_until_route_ready
        original_claim = jb_inspect.claim_lifecycle
        original_write = jb_inspect.write_lease
        try:
            route = {
                "project_key": "path:/tmp/repo",
                "base_path": "/tmp/repo",
                "project_instance_id": "session:1",
                "session_id": "session",
            }
            jb_inspect.create_local_lease = lambda context, state="preparing": {"lease_id": "lease-1", "state": state}
            jb_inspect.find_exact_route = lambda args, context: route
            jb_inspect.ensure_exact_worktree = lambda route, context, args: None
            jb_inspect.wait_until_route_ready = lambda args, context, route, timeout_ms: None
            jb_inspect.claim_lifecycle = lambda args, context, route, lease: claimed_lifecycle_result(
                route,
                "private-close-proof",
            )
            jb_inspect.write_lease = lambda lease: None

            prepared = jb_inspect.prepare_lifecycle(Namespace(prepare_timeout_ms=1), {"worktree_root": "/tmp/repo"})
        finally:
            jb_inspect.create_local_lease = original_create
            jb_inspect.find_exact_route = original_find
            jb_inspect.ensure_exact_worktree = original_ensure
            jb_inspect.wait_until_route_ready = original_wait_ready
            jb_inspect.claim_lifecycle = original_claim
            jb_inspect.write_lease = original_write

        self.assertEqual(prepared["status"], "prepared")
        self.assertNotIn("_control", prepared)
        self.assertNotIn("private-close-proof", json.dumps(prepared))

    def test_prepare_failure_releases_preexisting_lease_without_close(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        removed = []
        claimed = []

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(
                jb_inspect,
                "wait_until_route_ready",
                side_effect=jb_inspect.InspectError(
                    "not ready",
                    3,
                    {"error_reason": "ide_not_ready_timeout", "last_status": {"indexing": True}},
                ),
            ),
            patch_attr(
                jb_inspect,
                "claim_lifecycle",
                side_effect=lambda *args: claimed.append(args) or unowned_lifecycle_result(route),
            ),
            patch_attr(jb_inspect, "write_lease"),
            patch_attr(jb_inspect, "remove_lease", side_effect=lambda removed_lease: removed.append(removed_lease["lease_id"])),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertEqual(len(claimed), 1)
        self.assertEqual(removed, ["lease-1"])
        self.assertEqual(lease["state"], "released")
        self.assertEqual(raised.exception.payload["error_reason"], "ide_not_ready_timeout")
        self.assertEqual(raised.exception.payload["last_status"], {"indexing": True})
        self.assertEqual(raised.exception.payload["cleanup"], {"status": "not_needed", "reason": "project_preexisted"})

    def test_prepare_failure_after_open_acceptance_records_cleanup_pending(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        written = []
        open_attempts = [
            {
                "method": "running_ide",
                "accepted": True,
                "ownership_registered": True,
                "identity": {"session_id": "session", "port": 63342},
                "endpoint_status": "opening",
            }
        ]

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(jb_inspect, "open_project_for_lifecycle", return_value=("running_ide", open_attempts, True)),
            patch_attr(
                jb_inspect,
                "wait_for_exact_route_after_open",
                side_effect=jb_inspect.InspectError("route missing", 3, {"error_reason": "project_open_blocked"}),
            ),
            patch_attr(jb_inspect, "write_lease", side_effect=lambda written_lease: written.append(written_lease.copy())),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertTrue(lease["opened_by_helper"])
        self.assertEqual(lease["open_method"], "running_ide")
        self.assertEqual(lease["session_id"], "session")
        self.assertEqual(lease["state"], "cleanup_pending")
        self.assertEqual(lease["preparation_failure_stage"], "route_wait")
        self.assertEqual(written[-1]["state"], "cleanup_pending")
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "deferred")
        self.assertEqual(raised.exception.payload["cleanup"]["reason"], "preparation_cleanup_pending")

    def test_prepare_failure_during_route_validation_does_not_close_unverified_route(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        cleanup_calls = []

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                return_value=(
                    "running_ide",
                    [
                        {
                            "method": "running_ide",
                            "accepted": True,
                            "ownership_registered": True,
                            "identity": {"session_id": "session", "port": 1},
                        }
                    ],
                    True,
                ),
            ),
            patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
            patch_attr(jb_inspect, "ensure_exact_worktree", side_effect=jb_inspect.InspectError("wrong route", 3)),
            patch_attr(
                jb_inspect,
                "cleanup_lifecycle",
                side_effect=lambda *args: cleanup_calls.append("close"),
            ),
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertEqual(cleanup_calls, [])
        self.assertEqual(lease["state"], "cleanup_pending")
        self.assertNotIn("project_instance_id", lease)
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "deferred")
        self.assertEqual(raised.exception.payload["preparation_stage"], "route_validation")

    def test_prepare_readiness_failure_reclaims_and_closes(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        cleanup_calls = []

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                return_value=(
                    "running_ide",
                    [
                        {
                            "method": "running_ide",
                            "accepted": True,
                            "ownership_registered": True,
                            "identity": {"session_id": "session", "port": 1},
                        }
                    ],
                    True,
                ),
            ),
            patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(
                jb_inspect,
                "claim_lifecycle",
                return_value=claimed_lifecycle_result(route, "proof-1"),
            ),
            patch_attr(
                jb_inspect,
                "wait_until_route_ready",
                side_effect=jb_inspect.InspectError("not ready", 3, {"error_reason": "ide_not_ready_timeout"}),
            ),
            patch_attr(
                jb_inspect,
                "cleanup_lifecycle",
                side_effect=lambda cleanup_lease, cleanup_route, proof: cleanup_calls.append(
                    (cleanup_lease["lease_id"], cleanup_route["project_instance_id"], proof)
                )
                or {"status": "closed"},
            ),
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertEqual(cleanup_calls, [("lease-1", "session:1", "proof-1")])
        self.assertEqual(raised.exception.payload["error_reason"], "ide_not_ready_timeout")
        self.assertEqual(raised.exception.payload["cleanup"], {"status": "closed"})

    def test_prepare_cleanup_error_does_not_mask_readiness_failure(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                return_value=(
                    "running_ide",
                    [
                        {
                            "method": "running_ide",
                            "accepted": True,
                            "ownership_registered": True,
                            "identity": {"session_id": "session", "port": 1},
                        }
                    ],
                    True,
                ),
            ),
            patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(
                jb_inspect,
                "claim_lifecycle",
                return_value=claimed_lifecycle_result(route, "proof-1"),
            ),
            patch_attr(
                jb_inspect,
                "wait_until_route_ready",
                side_effect=jb_inspect.InspectError("not ready", 3, {"error_reason": "ide_not_ready_timeout"}),
            ),
            patch_attr(jb_inspect, "cleanup_lifecycle", side_effect=RuntimeError("close crashed")),
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertEqual(raised.exception.payload["error_reason"], "ide_not_ready_timeout")
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "deferred")
        self.assertEqual(raised.exception.payload["cleanup"]["cleanup_error"], "RuntimeError")
        self.assertEqual(lease["state"], "cleanup_pending")

    def test_prepare_claim_failure_records_cleanup_pending_when_reclaim_fails(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        written = []

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                return_value=(
                    "running_ide",
                    [
                        {
                            "method": "running_ide",
                            "accepted": True,
                            "ownership_registered": True,
                            "identity": {"session_id": "session", "port": 1},
                        }
                    ],
                    True,
                ),
            ),
            patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(jb_inspect, "wait_until_route_ready"),
            patch_attr(
                jb_inspect,
                "claim_lifecycle",
                side_effect=jb_inspect.InspectError("HTTP 500", 3, {"error_reason": "inspection_api_http_error"}),
            ),
            patch_attr(
                jb_inspect,
                "reclaim_lifecycle_claim",
                return_value=(None, None, {"status": "unknown"}, route),
            ),
            patch_attr(jb_inspect, "write_lease", side_effect=lambda written_lease: written.append(written_lease.copy())),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertEqual(lease["state"], "cleanup_pending")
        self.assertEqual(lease["project_instance_id"], "session:1")
        self.assertEqual(lease["session_id"], "session")
        self.assertEqual(lease["preparation_failure_stage"], "lifecycle_claim")
        self.assertEqual(written[-1]["state"], "cleanup_pending")
        self.assertEqual(raised.exception.payload["error_reason"], "inspection_api_http_error")
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "deferred")

    def test_prepare_unproven_open_never_closes_project(self):
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        for outcome, method in (("already_open", "preexisting"), ("already_opening", "unproven_opening")):
            with self.subTest(outcome=outcome):
                lease = {"lease_id": f"lease-{outcome}", "state": "preparing"}
                close_calls = []
                open_attempts = [
                    {
                        "method": "running_ide",
                        "accepted": True,
                        "ownership_registered": False,
                        "open_outcome": outcome,
                    }
                ]
                with (
                    patch_attr(jb_inspect, "create_local_lease", return_value=lease),
                    patch_attr(jb_inspect, "find_exact_route", return_value=None),
                    patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
                    patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
                    patch_attr(
                        jb_inspect,
                        "open_project_for_lifecycle",
                        return_value=(method, open_attempts, False),
                    ),
                    patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
                    patch_attr(jb_inspect, "ensure_exact_worktree"),
                    patch_attr(
                        jb_inspect,
                        "claim_lifecycle",
                        return_value=unowned_lifecycle_result(route),
                    ),
                    patch_attr(
                        jb_inspect,
                        "wait_until_route_ready",
                        side_effect=jb_inspect.InspectError(
                            "not ready",
                            3,
                            {"error_reason": "ide_not_ready_timeout"},
                        ),
                    ),
                    patch_attr(jb_inspect, "call_lifecycle_close", side_effect=lambda *args: close_calls.append(args)),
                    patch_attr(jb_inspect, "write_lease"),
                    patch_attr(jb_inspect, "remove_lease"),
                ):
                    with self.assertRaises(jb_inspect.InspectError):
                        jb_inspect.prepare_lifecycle_details(
                            helper_args(open=True, prepare_timeout_ms=1),
                            {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                        )

                self.assertEqual(close_calls, [])
                self.assertFalse(lease["opened_by_helper"])
                self.assertEqual(lease["state"], "released")

    def test_prepare_registered_open_downgrades_when_claim_is_not_owned(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        open_attempts = [
            {
                "method": "running_ide",
                "accepted": True,
                "ownership_registered": True,
                "identity": {"session_id": "session", "port": 1},
            }
        ]

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                return_value=("running_ide", open_attempts, True),
            ),
            patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(jb_inspect, "claim_lifecycle", return_value=unowned_lifecycle_result(route)),
            patch_attr(jb_inspect, "wait_until_route_ready"),
            patch_attr(jb_inspect, "write_lease"),
        ):
            prepared, prepared_lease, close_proof = jb_inspect.prepare_lifecycle_details(
                helper_args(open=True, prepare_timeout_ms=1),
                {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
            )

        self.assertFalse(prepared["opened_by_helper"])
        self.assertFalse(prepared_lease["opened_by_helper"])
        self.assertIsNone(close_proof)
        self.assertEqual(prepared["claim"]["status"], "not_owned")

    def test_prepare_rejects_route_from_different_open_session(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 2,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session-b:1",
            "session_id": "session-b",
        }
        cleanup_calls = []
        open_attempts = [
            {
                "method": "running_ide",
                "accepted": True,
                "ownership_registered": True,
                "identity": {"session_id": "session-a", "port": 1},
            }
        ]

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                return_value=("running_ide", open_attempts, True),
            ),
            patch_attr(jb_inspect, "wait_for_exact_route_after_open", return_value=route),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(jb_inspect, "cleanup_lifecycle", side_effect=lambda *args: cleanup_calls.append("close")),
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertTrue(raised.exception.payload["session_drift"])
        self.assertEqual(cleanup_calls, [])
        self.assertEqual(lease["session_id"], "session-a")
        self.assertNotIn("project_instance_id", lease)
        self.assertEqual(lease["state"], "cleanup_pending")

    def test_prepare_cleanup_retains_original_payload_fields(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        original_cleanup = {"status": "original"}
        original_lease = {"lease_id": "original"}

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(
                jb_inspect,
                "claim_lifecycle",
                return_value=unowned_lifecycle_result(route),
            ),
            patch_attr(
                jb_inspect,
                "wait_until_route_ready",
                side_effect=jb_inspect.InspectError(
                    "not ready",
                    3,
                    {
                        "error_reason": "ide_not_ready_timeout",
                        "cleanup": original_cleanup,
                        "lease": original_lease,
                    },
                ),
            ),
            patch_attr(jb_inspect, "write_lease"),
            patch_attr(jb_inspect, "remove_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertEqual(raised.exception.payload["cleanup"], original_cleanup)
        self.assertEqual(raised.exception.payload["lease"], original_lease)
        self.assertEqual(raised.exception.payload["preparation_cleanup"]["reason"], "project_preexisted")
        self.assertEqual(raised.exception.payload["preparation_lease"]["lease_id"], "lease-1")
        self.assertEqual(raised.exception.payload["context"]["worktree_root"], "/tmp/worktree")

    def test_prepare_interrupt_before_open_request_records_cleanup_pending(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        writes = []

        def interrupt_first_write(written_lease):
            writes.append(written_lease.copy())
            if len(writes) == 1:
                raise KeyboardInterrupt()

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(jb_inspect, "open_project_for_lifecycle") as open_project,
            patch_attr(jb_inspect, "write_lease", side_effect=interrupt_first_write),
        ):
            with self.assertRaises(KeyboardInterrupt):
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        open_project.assert_not_called()
        self.assertFalse(lease["opened_by_helper"])
        self.assertEqual(lease["state"], "cleanup_pending")
        self.assertEqual(len(writes), 2)

    def test_prepare_interrupt_after_open_request_attempt_keeps_recoverable_lease(self):
        lease = {"lease_id": "lease-1", "state": "preparing"}
        written = []

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(
                jb_inspect,
                "open_project_for_lifecycle",
                side_effect=KeyboardInterrupt(),
            ),
            patch_attr(jb_inspect, "write_lease", side_effect=lambda value: written.append(value.copy())),
        ):
            with self.assertRaises(KeyboardInterrupt):
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=True, prepare_timeout_ms=1),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertFalse(lease["opened_by_helper"])
        self.assertEqual(lease["state"], "cleanup_pending")
        self.assertEqual(lease["preparation_failure_stage"], "project_open")
        self.assertEqual(written[0]["state"], "open_requesting")
        self.assertEqual(written[-1]["state"], "cleanup_pending")

    def test_failed_preparation_close_results_remain_recoverable(self):
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        error = jb_inspect.InspectError("not ready", 3, {"error_reason": "ide_not_ready_timeout"})
        for close_status in ("failed", "skipped"):
            with self.subTest(close_status=close_status):
                lease = {
                    "lease_id": f"lease-{close_status}",
                    "state": "route_resolved",
                    "opened_by_helper": True,
                    "project_instance_id": "session:1",
                    "project_key": "path:/tmp/worktree",
                    "session_id": "session",
                    "lifecycle_target_path": "/tmp/worktree",
                }
                close_result = {"status": close_status, "reason": f"close_{close_status}"}
                with (
                    patch_attr(
                        jb_inspect,
                        "reclaim_lifecycle_claim",
                        return_value=(
                            True,
                            "proof-1",
                            {"status": "claimed", "ownership_proven": True},
                            route,
                        ),
                    ),
                    patch_attr(jb_inspect, "cleanup_lifecycle", return_value=close_result),
                    patch_attr(jb_inspect, "write_lease"),
                ):
                    result = jb_inspect.cleanup_failed_preparation(
                        lease,
                        route,
                        None,
                        error,
                        "readiness_wait",
                    )

                self.assertEqual(result["status"], "deferred")
                self.assertEqual(result["close_result"], close_result)
                self.assertEqual(lease["state"], "cleanup_pending")

    def test_readiness_timeout_preserves_helper_owned_project_during_active_inspection(self):
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        lease = {
            "lease_id": "lease-active",
            "state": "ownership_claimed",
            "opened_by_helper": True,
            "project_instance_id": "session:1",
            "project_key": "path:/tmp/worktree",
            "session_id": "session",
            "lifecycle_target_path": "/tmp/worktree",
        }
        error = jb_inspect.InspectError(
            "not ready",
            3,
            {
                "error_reason": "ide_not_ready_timeout",
                "last_status": {"inspection_in_progress": True, "is_scanning": True, "indexing": False},
            },
        )

        with (
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
            patch_attr(jb_inspect, "write_lease"),
        ):
            result = jb_inspect.cleanup_failed_preparation(
                lease,
                route,
                "proof-1",
                error,
                "readiness_wait",
            )

        cleanup.assert_not_called()
        self.assertEqual(result["status"], "deferred")
        self.assertEqual(result["reason"], "indexing_or_inspection_still_running")
        self.assertEqual(lease["state"], "kept_warm_after_indexing_timeout")

    def test_write_lease_strips_private_fields_on_disk(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            try:
                lease = jb_inspect.create_local_lease({"worktree_root": "/tmp/repo"}, "prepared")
                lease["_private_data"] = "private-lease-value"
                jb_inspect.write_lease(lease)
                body = json.loads(jb_inspect.lease_path(lease).read_text(encoding="utf-8"))
            finally:
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

            self.assertNotIn("_private_data", body)
            self.assertNotIn("private-lease-value", json.dumps(body))

    def test_claim_creates_local_lease_without_opening_ide(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            try:
                context = {"repo_path": "/tmp/repo", "worktree_root": "/tmp/repo"}
                result = jb_inspect.command_claim(Namespace(), context)
            finally:
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

            self.assertEqual(result["status"], "claimed")
            self.assertEqual(result["lease"]["state"], "claimed")

    def test_cleanup_skips_preexisting_project(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            try:
                lease = jb_inspect.create_local_lease({"worktree_root": "/tmp/repo"}, "prepared")
                lease["opened_by_helper"] = False
                result = jb_inspect.cleanup_lifecycle(lease, {"project_key": "path:/tmp/repo"})
            finally:
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

            self.assertEqual(result["status"], "not_needed")
            self.assertEqual(result["reason"], "project_preexisted")

    def test_prepare_without_open_reports_open_not_attempted(self):
        lease = {"lease_id": "lease-1", "state": "preparing", "opened_by_helper": False}

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "write_lease"),
            patch_attr(jb_inspect, "remove_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(
                    helper_args(open=False),
                    {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                )

        self.assertTrue(lease["open_not_attempted"])
        self.assertEqual(raised.exception.payload["preparation_cleanup"]["status"], "not_needed")
        self.assertEqual(raised.exception.payload["preparation_cleanup"]["reason"], "open_not_attempted")

    def test_closeout_defers_cleanup_when_helper_opened_project_is_still_indexing(self):
        cleanups = []
        states = []
        prepared = {
            "status": "prepared",
            "route": {"port": 1, "project_key": "path:/tmp/worktree", "base_path": "/tmp/worktree"},
            "opened_by_helper": True,
        }
        lease = {
            "opened_by_helper": True,
            "lease_id": "lease-1",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
        }

        def fake_run(args, context, route):
            return {
                "status": "timed_out",
                "verdict": "UNKNOWN",
                "verdict_reason": "timeout",
                "wait": {"timed_out": True, "indexing": True, "inspection_in_progress": True},
            }

        original_prepare = jb_inspect.prepare_lifecycle_details
        original_run = jb_inspect.run_inspection_on_route
        original_cleanup = jb_inspect.cleanup_lifecycle
        original_mark = jb_inspect.mark_lease_state
        jb_inspect.prepare_lifecycle_details = lambda args, context: (prepared, lease, "proof-1")
        jb_inspect.run_inspection_on_route = fake_run
        jb_inspect.cleanup_lifecycle = lambda cleanup_lease, route, close_proof: cleanups.append((cleanup_lease, route, close_proof)) or {"status": "closed"}
        jb_inspect.mark_lease_state = lambda state_lease, state: states.append((state_lease, state))
        try:
            result = jb_inspect.command_closeout(Namespace(keep_warm=False, lifecycle_lock_timeout_ms=0), {})
        finally:
            jb_inspect.prepare_lifecycle_details = original_prepare
            jb_inspect.run_inspection_on_route = original_run
            jb_inspect.cleanup_lifecycle = original_cleanup
            jb_inspect.mark_lease_state = original_mark

        self.assertEqual(cleanups, [])
        self.assertEqual(result["cleanup"]["status"], "deferred")
        self.assertTrue(result["cleanup_deferred"])
        self.assertEqual(states, [(lease, "kept_warm_after_indexing_timeout")])

    def test_prepare_reclaims_deferred_helper_lease_for_exact_route(self):
        removed = []
        written = []
        claimed = []
        created = {
            "lease_id": "new-lease",
            "state": "preparing",
            "lifecycle_target_path": "/tmp/worktree",
        }
        deferred = {
            "lease_id": "old-lease",
            "state": "kept_warm_after_indexing_timeout",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "worktree_root": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
            "updated_at_ms": 20,
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }

        original_create = jb_inspect.create_local_lease
        original_find = jb_inspect.find_exact_route
        original_matching = jb_inspect.matching_deferred_lease
        original_remove = jb_inspect.remove_lease
        original_wait = jb_inspect.wait_until_route_ready
        original_claim = jb_inspect.claim_lifecycle
        original_write = jb_inspect.write_lease
        jb_inspect.create_local_lease = lambda context, state: created.copy()
        jb_inspect.find_exact_route = lambda args, context: route.copy()
        jb_inspect.matching_deferred_lease = lambda context, exact_route: deferred.copy()
        jb_inspect.remove_lease = lambda lease: removed.append(lease["lease_id"])
        jb_inspect.wait_until_route_ready = lambda args, context, exact_route, timeout_ms: None

        def fake_claim(args, context, exact_route, lease):
            claimed.append(lease["lease_id"])
            return claimed_lifecycle_result(exact_route, "proof-1")

        jb_inspect.claim_lifecycle = fake_claim
        jb_inspect.write_lease = lambda lease: written.append(lease.copy())
        try:
            prepared, lease, close_proof = jb_inspect.prepare_lifecycle_details(
                helper_args(open=True, prepare_timeout_ms=1),
                {"worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
            )
        finally:
            jb_inspect.create_local_lease = original_create
            jb_inspect.find_exact_route = original_find
            jb_inspect.matching_deferred_lease = original_matching
            jb_inspect.remove_lease = original_remove
            jb_inspect.wait_until_route_ready = original_wait
            jb_inspect.claim_lifecycle = original_claim
            jb_inspect.write_lease = original_write

        self.assertEqual(removed, ["new-lease"])
        self.assertEqual(claimed, ["old-lease"])
        self.assertTrue(lease["opened_by_helper"])
        self.assertEqual(lease["open_method"], "reclaimed_deferred")
        self.assertEqual(prepared["open_method"], "reclaimed_deferred")
        self.assertTrue(prepared["opened_by_helper"])
        self.assertEqual(close_proof, "proof-1")
        self.assertEqual(written[-1]["lease_id"], "old-lease")

    def test_deferred_lease_matching_requires_exact_project_session_and_path(self):
        lease = {
            "state": "kept_warm_after_indexing_timeout",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        context = {"worktree_root": "/tmp/worktree"}
        route = {
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }

        self.assertTrue(jb_inspect.deferred_lease_matches_route(lease, context, route))
        self.assertTrue(jb_inspect.deferred_lease_matches_route(lease | {"state": "cleanup_pending"}, context, route))
        self.assertFalse(jb_inspect.deferred_lease_matches_route(lease | {"session_id": "other"}, context, route))
        self.assertFalse(jb_inspect.deferred_lease_matches_route(lease | {"project_instance_id": "session:2"}, context, route))
        self.assertFalse(jb_inspect.deferred_lease_matches_route(lease | {"lifecycle_target_path": "/tmp/other"}, context, route))

    def test_cleanup_leases_closes_stale_helper_owned_matching_route(self):
        removed = []
        closed = []
        claims = []
        lease = {
            "lease_id": "old-lease",
            "state": "kept_warm_after_indexing_timeout",
            "opened_by_helper": True,
            "pid": 999999,
            "lifecycle_target_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }

        original_read = jb_inspect.read_local_leases
        original_pid_alive = jb_inspect.pid_alive
        original_routes = jb_inspect.discover_routes_for_cleanup
        original_private = jb_inspect.private_http_get_body
        original_cleanup = jb_inspect.cleanup_lifecycle
        path = Path("/tmp/old-lease.json")
        jb_inspect.read_local_leases = lambda: [(path, lease.copy())]
        jb_inspect.pid_alive = lambda pid: False
        jb_inspect.discover_routes_for_cleanup = lambda args: ([route.copy()], {"session"})
        jb_inspect.private_http_get_body = lambda port, endpoint, params, timeout=jb_inspect.DEFAULT_TIMEOUT_SECONDS: claims.append((endpoint, params["lease_id"])) or plugin_claim_body(route, "old-lease")
        jb_inspect.cleanup_lifecycle = lambda cleanup_lease, cleanup_route, proof: closed.append((cleanup_lease["lease_id"], cleanup_route["project_instance_id"], proof)) or {"status": "closed"}
        try:
            with patch.object(Path, "unlink", lambda self, missing_ok=False: removed.append(str(self))):
                result = jb_inspect.cleanup_stale_helper_leases(Namespace(max_age_ms=86_400_000, dry_run=False))
        finally:
            jb_inspect.read_local_leases = original_read
            jb_inspect.pid_alive = original_pid_alive
            jb_inspect.discover_routes_for_cleanup = original_routes
            jb_inspect.private_http_get_body = original_private
            jb_inspect.cleanup_lifecycle = original_cleanup

        self.assertEqual(claims, [("lifecycle/claim", "old-lease")])
        self.assertEqual(closed, [("old-lease", "session:1", "proof-1")])
        self.assertEqual(removed, [str(path)])
        self.assertEqual(result["closed"][0]["lease_id"], "old-lease")

    def test_cleanup_command_removes_stale_never_opened_lease_without_route_discovery(self):
        removed = []
        path = Path("/tmp/never-opened-lease.json")
        lease = {
            "lease_id": "never-opened-lease",
            "state": "cleanup_pending",
            "preparation_failure_stage": "project_open",
            "preparation_failure_reason": "timeout",
            "opened_by_helper": False,
            "open_request_may_have_been_accepted": False,
            "open_attempts": [],
            "pid": 999_999,
            "updated_at_ms": jb_inspect.now_ms(),
        }

        with (
            patch_attr(jb_inspect, "read_local_leases", return_value=[(path, lease)]),
            patch_attr(jb_inspect, "pid_alive", return_value=False),
            patch_attr(jb_inspect, "discover_routes_for_cleanup") as discover_routes,
            patch.object(Path, "unlink", lambda self, missing_ok=False: removed.append(str(self))),
        ):
            result = jb_inspect.cleanup_stale_helper_leases(
                Namespace(max_age_ms=86_400_000, dry_run=False)
            )

        discover_routes.assert_not_called()
        self.assertEqual(removed, [str(path)])
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["removed"], [str(path)])
        self.assertEqual(result["failed"], [])
        self.assertEqual(result["unresolved"], [])

    def test_cleanup_stale_helper_lease_skips_when_reclaim_fails(self):
        lease = {
            "lease_id": "old-lease",
            "opened_by_helper": True,
            "project_instance_id": "session:1",
            "session_id": "session",
            "lifecycle_target_path": "/tmp/worktree",
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        original_private = jb_inspect.private_http_get_body
        jb_inspect.private_http_get_body = lambda *args, **kwargs: (_ for _ in ()).throw(jb_inspect.InspectError("drift", 4))
        try:
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route])
        finally:
            jb_inspect.private_http_get_body = original_private

        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["reason"], "close_failed")

    def test_cleanup_pending_lease_reclaims_matching_exact_identity(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "session",
            "project_instance_id": "session:1",
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
        }
        cleanup_calls = []

        with (
            patch_attr(
                jb_inspect,
                "private_http_get_body",
                return_value=plugin_claim_body(route, "pending-lease"),
            ),
            patch_attr(
                jb_inspect,
                "cleanup_lifecycle",
                side_effect=lambda cleanup_lease, cleanup_route, proof: cleanup_calls.append(
                    (cleanup_lease["lease_id"], cleanup_route["project_instance_id"], proof)
                )
                or {"status": "closed"},
            ),
        ):
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route])

        self.assertEqual(cleanup_calls, [("pending-lease", "session:1", "proof-1")])
        self.assertEqual(result["status"], "closed")

    def test_cleanup_pending_lease_does_not_close_matching_path_in_new_session(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "old-session",
            "project_instance_id": "old-session:1",
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "new-session:1",
            "session_id": "new-session",
        }

        with patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup:
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route])

        cleanup.assert_not_called()
        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["reason"], "ownership_route_unavailable")

    def test_cleanup_pending_lease_releases_after_original_ide_process_dies_without_route(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "open_request_may_have_been_accepted": False,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "old-session",
            "ide_port": 63343,
            "open_attempts": [
                {
                    "accepted": True,
                    "ownership_registered": True,
                    "lease_id": "pending-lease",
                    "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                    "identity": {
                        "session_id": "old-session",
                        "port": 63343,
                        "pid": 753,
                    },
                }
            ],
        }

        with patch_attr(jb_inspect, "pid_definitively_dead", return_value=True):
            result = jb_inspect.cleanup_stale_helper_lease(lease, [], set())

        self.assertEqual(result["status"], "not_needed")
        self.assertEqual(result["reason"], "original_ide_process_dead_no_route")
        self.assertTrue(result["released_without_close"])

    def test_cleanup_prepared_lease_releases_only_with_dead_original_ide_proof(self):
        lease = {
            "lease_id": "prepared-lease", "state": "prepared",
            "opened_by_helper": True, "open_request_may_have_been_accepted": False,
            "lifecycle_target_path": "/tmp/worktree", "session_id": "old-session",
            "project_instance_id": "old-session:1", "project_key": "path:/tmp/worktree",
            "route": {"base_path": "/tmp/worktree", "session_id": "old-session", "project_instance_id": "old-session:1"},
            "ide_port": 63343, "open_attempts": [{
                "accepted": True, "ownership_registered": True,
                "lease_id": "prepared-lease",
                "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                "identity": {"session_id": "old-session", "port": 63343, "pid": 753},
            }],
        }
        replacement = {"base_path": "/tmp/worktree", "session_id": "new-session"}
        cases = [
            (lease, [], set(), True, True),
            (lease | {"open_attempts": [{"method": "bootstrap_ide", "accepted": True}, *lease["open_attempts"]]}, [], set(), True, True),
            (lease, [], {"old-session"}, True, False),
            (lease, [replacement], {"new-session"}, True, False),
            (lease, [], set(), False, False),
            (lease, [], None, True, False),
            (lease | {"state": "open_requesting"}, [], set(), True, False),
            (lease | {"open_request_may_have_been_accepted": True}, [], set(), True, False),
            (lease | {"open_attempts": []}, [], set(), True, False),
            (lease | {"open_attempts": [lease["open_attempts"][0] | {"ownership_registered": False}]}, [], set(), True, False),
        ]
        for candidate, routes, sessions, dead, released in cases:
            with (
                self.subTest(candidate=candidate, routes=routes, sessions=sessions, dead=dead),
                patch_attr(jb_inspect, "pid_definitively_dead", return_value=dead),
                patch_attr(jb_inspect, "private_http_get_body") as claim,
                patch_attr(jb_inspect, "cleanup_lifecycle") as close,
            ):
                result = jb_inspect.cleanup_stale_helper_lease(candidate, routes, sessions)
            claim.assert_not_called()
            close.assert_not_called()
            self.assertEqual(result.get("released_without_close", False), released)

    def test_connection_reset_after_open_send_preserves_request_identity(self):
        lease = {"lease_id": "reset-lease", "state": "open_requesting"}
        identity = {"session_id": "session", "port": 63343}
        saved = []
        with (
            patch_attr(jb_inspect, "discover_open_identities", return_value=[identity]),
            patch_attr(jb_inspect, "identity_matches_context", return_value=True),
            patch_attr(jb_inspect, "write_lease", side_effect=lambda value: saved.append(value.copy())),
            patch_attr(jb_inspect, "http_get", side_effect=ConnectionResetError()),
        ):
            with self.assertRaises(ConnectionResetError):
                jb_inspect.open_via_running_ide(Namespace(), {"worktree_root": "/tmp/worktree"}, [], lease=lease)
        self.assertEqual(saved[-1]["ide_port"], identity["port"])
        self.assertEqual(saved[-1]["session_id"], identity["session_id"])
        lease.update({
            "state": "cleanup_pending", "preparation_failure_stage": "project_open",
            "preparation_failure_reason": "connectionreseterror",
            "open_request_may_have_been_accepted": False,
        })
        self.assertFalse(jb_inspect.lease_proves_open_not_attempted(lease))

    def test_cleanup_command_removes_unattempted_connection_reset_lease(self):
        lease = {
            "lease_id": "unattempted", "state": "cleanup_pending",
            "preparation_failure_stage": "project_open",
            "preparation_failure_reason": "connectionreseterror",
            "opened_by_helper": False, "open_request_may_have_been_accepted": False,
            "open_attempts": [], "pid": 999999, "updated_at_ms": jb_inspect.now_ms(),
        }
        for changes, removable in [
            ({}, True), ({"session_id": "session"}, False),
            ({"ide_port": 63343}, False),
            ({"open_request_may_have_been_accepted": True}, False),
            ({"open_attempts": [{"accepted": False, "request_may_have_been_accepted": True}]}, False),
            ({"preparation_failure_reason": "interrupted"}, False),
        ]:
            with (
                self.subTest(changes=changes),
                patch_attr(jb_inspect, "read_local_leases", return_value=[(Path("/tmp/unattempted.json"), lease | changes)]),
                patch_attr(jb_inspect, "pid_alive", return_value=False),
                patch_attr(jb_inspect, "discover_routes_for_cleanup", return_value=([], set())) as discover,
                patch_attr(jb_inspect, "private_http_get_body") as claim,
                patch_attr(jb_inspect, "cleanup_lifecycle") as close,
                patch.object(Path, "unlink") as unlink,
            ):
                result = jb_inspect.cleanup_stale_helper_leases(Namespace(max_age_ms=86400000, dry_run=False))
            self.assertEqual(bool(result["removed"]), removable)
            self.assertEqual(unlink.called, removable)
            if removable:
                discover.assert_not_called()
            claim.assert_not_called()
            close.assert_not_called()

    def test_cleanup_command_removes_lease_that_failed_its_identity_check_before_open(self):
        lease = {
            "lease_id": "identity-mismatch", "state": "cleanup_pending",
            "preparation_failure_stage": "project_open",
            "preparation_failure_reason": "identity_port_mismatch",
            "opened_by_helper": False, "open_request_may_have_been_accepted": False,
            "open_attempts": [], "pid": 999999, "updated_at_ms": jb_inspect.now_ms(),
        }
        for changes, removable in [
            ({}, True), ({"preparation_failure_reason": "invalid_identity_port"}, True),
            ({"preparation_failure_reason": "inspection_api_unavailable"}, True),
            ({"session_id": "session"}, False), ({"ide_port": 63343}, False),
            ({"preparation_failure_stage": "route_wait"}, False),
        ]:
            with (
                self.subTest(changes=changes),
                patch_attr(jb_inspect, "read_local_leases", return_value=[(Path("/tmp/identity-mismatch.json"), lease | changes)]),
                patch_attr(jb_inspect, "pid_alive", return_value=False),
                patch_attr(jb_inspect, "discover_routes_for_cleanup", return_value=([], set())),
                patch_attr(jb_inspect, "private_http_get_body") as claim,
                patch_attr(jb_inspect, "cleanup_lifecycle") as close,
                patch.object(Path, "unlink") as unlink,
            ):
                result = jb_inspect.cleanup_stale_helper_leases(Namespace(max_age_ms=86400000, dry_run=False))
            self.assertEqual(bool(result["removed"]), removable)
            self.assertEqual(unlink.called, removable)
            claim.assert_not_called()
            close.assert_not_called()

    def timed_out_open_lease(self, **attempt_identity: Any) -> dict[str, Any]:
        return {
            "lease_id": "timed-out-open",
            "state": "cleanup_pending",
            "opened_by_helper": False,
            "open_request_may_have_been_accepted": True,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "old-session",
            "ide_port": 63343,
            "open_attempts": [
                {
                    "method": "running_ide",
                    "accepted": False,
                    "request_may_have_been_accepted": True,
                    "ownership_registered": False,
                    "error_reason": "inspection_api_timeout",
                    "identity": {"session_id": "old-session", "port": 63343, "pid": 753} | attempt_identity,
                }
            ],
        }

    def test_cleanup_releases_timed_out_open_after_the_original_ide_process_exits(self):
        with patch_attr(jb_inspect, "pid_definitively_dead", return_value=True):
            result = jb_inspect.cleanup_stale_helper_lease(self.timed_out_open_lease(), [], {"new-session"})

        self.assertEqual(result["status"], "not_needed")
        self.assertEqual(result["reason"], "original_ide_process_dead_no_route")
        self.assertTrue(result["released_without_close"])

    def test_cleanup_retains_timed_out_open_unless_the_original_ide_is_provably_gone(self):
        open_elsewhere = {"base_path": "/tmp/worktree", "session_id": "new-session", "project_instance_id": "new-session:1"}
        for name, lease, routes, sessions, dead in [
            ("original process alive", self.timed_out_open_lease(), [], set(), False),
            ("original session still running", self.timed_out_open_lease(), [], {"old-session"}, True),
            ("worktree open in a running IDE", self.timed_out_open_lease(), [open_elsewhere], {"new-session"}, True),
            ("attempt sent to another session", self.timed_out_open_lease(session_id="other-session"), [], set(), True),
            ("attempt sent to another port", self.timed_out_open_lease(port=63344), [], set(), True),
            ("attempt names no process", self.timed_out_open_lease(pid=None), [], set(), True),
            ("running sessions unknown", self.timed_out_open_lease(), [], None, True),
            ("open still being requested", self.timed_out_open_lease() | {"state": "open_requesting"}, [], set(), True),
            ("attempts name different processes", self.timed_out_open_lease() | {"open_attempts": [
                self.timed_out_open_lease()["open_attempts"][0],
                self.timed_out_open_lease(pid=754)["open_attempts"][0],
            ]}, [], set(), True),
            ("an attempt without an identity may have reached an IDE", self.timed_out_open_lease() | {"open_attempts": [
                *self.timed_out_open_lease()["open_attempts"],
                {"method": "running_ide", "accepted": False, "request_may_have_been_accepted": True},
            ]}, [], set(), True),
        ]:
            with (
                self.subTest(name),
                patch_attr(jb_inspect, "pid_definitively_dead", return_value=dead),
                patch_attr(jb_inspect, "identity_for_port", side_effect=OSError("no route")),
            ):
                result = jb_inspect.cleanup_stale_helper_lease(lease, routes, sessions)

            self.assertEqual(result["status"], "skipped")
            self.assertEqual(result["reason"], "ownership_route_unavailable")

    def test_cleanup_pending_lease_retains_dead_original_session_when_target_is_open_elsewhere(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "open_request_may_have_been_accepted": False,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "old-session",
            "ide_port": 63343,
            "open_attempts": [
                {
                    "accepted": True,
                    "ownership_registered": True,
                    "lease_id": "pending-lease",
                    "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                    "identity": {
                        "session_id": "old-session",
                        "port": 63343,
                        "pid": 753,
                    },
                }
            ],
        }
        route = {
            "base_path": "/tmp/worktree",
            "session_id": "new-session",
            "project_instance_id": "new-session:1",
        }

        with patch_attr(jb_inspect, "pid_definitively_dead", return_value=True):
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route], {"new-session"})

        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["reason"], "ownership_route_unavailable")

    def test_cleanup_pending_lease_retains_unknown_or_live_original_ide_process(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "open_request_may_have_been_accepted": False,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "old-session",
            "ide_port": 63343,
            "open_attempts": [
                {
                    "accepted": True,
                    "ownership_registered": True,
                    "lease_id": "pending-lease",
                    "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                    "identity": {
                        "session_id": "old-session",
                        "port": 63343,
                        "pid": 753,
                    },
                }
            ],
        }

        with patch_attr(jb_inspect, "pid_definitively_dead", return_value=False):
            result = jb_inspect.cleanup_stale_helper_lease(lease, [], set())

        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["reason"], "ownership_route_unavailable")

    def test_cleanup_pending_lease_retains_ambiguous_or_conflicting_open_attempts(self):
        base_attempt = {
            "accepted": True,
            "ownership_registered": True,
            "lease_id": "pending-lease",
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
            "identity": {
                "session_id": "old-session",
                "port": 63343,
                "pid": 753,
            },
        }
        attempts = [
            [base_attempt | {"ownership_registered": False}],
            [base_attempt | {"request_may_have_been_accepted": True}],
            [base_attempt | {"identity": base_attempt["identity"] | {"pid": None}}],
            [base_attempt, base_attempt | {"identity": base_attempt["identity"] | {"pid": 754}}],
        ]

        for open_attempts in attempts:
            lease = {
                "lease_id": "pending-lease",
                "state": "cleanup_pending",
                "opened_by_helper": True,
                "open_request_may_have_been_accepted": False,
                "lifecycle_target_path": "/tmp/worktree",
                "session_id": "old-session",
                "ide_port": 63343,
                "open_attempts": open_attempts,
            }
            with self.subTest(open_attempts=open_attempts), patch_attr(
                jb_inspect, "pid_definitively_dead", return_value=True
            ):
                result = jb_inspect.cleanup_stale_helper_lease(lease, [], set())

            self.assertEqual(result["status"], "skipped")
            self.assertEqual(result["reason"], "ownership_route_unavailable")

    def test_cleanup_command_removes_dead_original_ide_lease_without_closing(self):
        removed = []
        path = Path("/tmp/pending-lease.json")
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "open_request_may_have_been_accepted": False,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "old-session",
            "ide_port": 63343,
            "pid": 999_999,
            "updated_at_ms": jb_inspect.now_ms(),
            "open_attempts": [
                {
                    "accepted": True,
                    "ownership_registered": True,
                    "lease_id": "pending-lease",
                    "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                    "identity": {
                        "session_id": "old-session",
                        "port": 63343,
                        "pid": 753,
                    },
                }
            ],
        }

        with (
            patch_attr(jb_inspect, "read_local_leases", return_value=[(path, lease)]),
            patch_attr(jb_inspect, "pid_alive", return_value=False),
            patch_attr(jb_inspect, "pid_definitively_dead", return_value=True),
            patch_attr(jb_inspect, "discover_routes_for_cleanup", return_value=([], set())),
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
            patch_attr(jb_inspect, "private_http_get_body") as claim,
            patch.object(Path, "unlink", lambda self, missing_ok=False: removed.append(str(self))),
        ):
            result = jb_inspect.cleanup_stale_helper_leases(
                Namespace(max_age_ms=86_400_000, dry_run=False)
            )

        cleanup.assert_not_called()
        claim.assert_not_called()
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["removed"], [str(path)])
        self.assertEqual(result["closed"], [])
        self.assertEqual(result["unresolved"], [])
        self.assertEqual(removed, [str(path)])

    def test_pid_definitively_dead_fails_closed_for_unknown_process_state(self):
        with patch.object(os, "kill", side_effect=ProcessLookupError()):
            self.assertTrue(jb_inspect.pid_definitively_dead(753))
        with patch.object(os, "kill", side_effect=PermissionError()):
            self.assertFalse(jb_inspect.pid_definitively_dead(753))
        with patch.object(os, "kill", side_effect=OSError("unknown")):
            self.assertFalse(jb_inspect.pid_definitively_dead(753))
        with patch.object(os, "kill", return_value=None):
            self.assertFalse(jb_inspect.pid_definitively_dead(753))
        self.assertFalse(jb_inspect.pid_definitively_dead(0))

    def test_cleanup_pending_lease_with_legacy_or_request_identity_stays_fail_closed(self):
        leases = [
            {
                "lease_id": "legacy-lease",
                "state": "cleanup_pending",
                "opened_by_helper": False,
                "lifecycle_target_path": "/tmp/worktree",
            },
            {
                "lease_id": "requesting-lease",
                "state": "cleanup_pending",
                "opened_by_helper": False,
                "open_request_may_have_been_accepted": False,
                "open_attempts": [],
                "session_id": "session",
                "ide_port": 63342,
                "lifecycle_target_path": "/tmp/worktree",
            },
            {
                "lease_id": "ambiguous-lease",
                "state": "cleanup_pending",
                "opened_by_helper": False,
                "open_request_may_have_been_accepted": True,
                "open_attempts": [],
                "lifecycle_target_path": "/tmp/worktree",
            },
        ]

        for lease in leases:
            with self.subTest(lease_id=lease["lease_id"]):
                result = jb_inspect.cleanup_stale_helper_lease(lease, [], set())

                self.assertEqual(result["status"], "skipped")
                self.assertEqual(result["reason"], "ownership_route_unavailable")

    def test_cleanup_pending_without_project_identity_never_closes_same_session_route(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "session",
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:manual",
            "session_id": "session",
        }

        with (
            patch_attr(
                jb_inspect,
                "private_http_get_body",
                return_value=plugin_claim_body(route, "pending-lease", owned=False),
            ),
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
        ):
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route])

        cleanup.assert_not_called()
        self.assertEqual(result["status"], "not_needed")
        self.assertEqual(result["reason"], "ownership_not_proven")

    def test_cleanup_route_less_pending_lease_closes_only_after_live_ownership_proof(self):
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": False,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "session",
        }
        route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:owned",
            "session_id": "session",
        }
        cleanup_calls = []

        with (
            patch_attr(
                jb_inspect,
                "private_http_get_body",
                return_value=plugin_claim_body(route, "pending-lease"),
            ),
            patch_attr(jb_inspect, "write_lease"),
            patch_attr(
                jb_inspect,
                "cleanup_lifecycle",
                side_effect=lambda cleanup_lease, cleanup_route, proof: cleanup_calls.append(
                    (cleanup_lease["opened_by_helper"], cleanup_route["project_instance_id"], proof)
                )
                or {"status": "closed"},
            ),
        ):
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route], {"session"})

        self.assertEqual(cleanup_calls, [(True, "session:owned", "proof-1")])
        self.assertEqual(result["status"], "closed")
        self.assertEqual(lease["project_instance_id"], "session:owned")

    def test_cleanup_command_retains_unresolved_route_less_lease(self):
        path = Path("/tmp/pending-lease.json")
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "session_id": "session",
            "pid": 999_999,
            "updated_at_ms": jb_inspect.now_ms(),
        }
        delayed_route = {
            "port": 1,
            "base_path": "/tmp/worktree",
            "project_key": "path:/tmp/worktree",
            "project_instance_id": "session:late",
            "session_id": "session",
        }

        with (
            patch_attr(jb_inspect, "read_local_leases", return_value=[(path, lease)]),
            patch_attr(jb_inspect, "pid_alive", return_value=False),
            patch_attr(jb_inspect, "discover_routes_for_cleanup", return_value=([delayed_route], {"session"})),
            patch_attr(
                jb_inspect,
                "private_http_get_body",
                return_value={"status": "claimed", "lease_id": "pending-lease", "route": delayed_route},
            ),
            patch.object(Path, "unlink") as unlink,
        ):
            result = jb_inspect.cleanup_stale_helper_leases(
                Namespace(max_age_ms=86_400_000, dry_run=False)
            )

        unlink.assert_not_called()
        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["removed"], [])
        self.assertEqual(result["unresolved"][0]["reason"], "ownership_unresolved")
        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)

    def test_cleanup_command_retains_leases_when_route_discovery_fails(self):
        path = Path("/tmp/pending-lease.json")
        lease = {
            "lease_id": "pending-lease",
            "state": "cleanup_pending",
            "opened_by_helper": True,
            "lifecycle_target_path": "/tmp/worktree",
            "project_instance_id": "session:1",
            "session_id": "session",
            "pid": 999_999,
            "updated_at_ms": jb_inspect.now_ms(),
        }

        with (
            patch_attr(jb_inspect, "read_local_leases", return_value=[(path, lease)]),
            patch_attr(jb_inspect, "pid_alive", return_value=False),
            patch_attr(
                jb_inspect,
                "discover_routes_for_cleanup",
                side_effect=jb_inspect.InspectError("registry unavailable", 3),
            ),
            patch.object(Path, "unlink") as unlink,
        ):
            result = jb_inspect.cleanup_stale_helper_leases(
                Namespace(max_age_ms=86_400_000, dry_run=False)
            )

        unlink.assert_not_called()
        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["removed"], [])
        self.assertEqual(result["failed"][0]["reason"], "route_discovery_failed")

    def test_cleanup_discovery_adds_identity_session_to_project_routes(self):
        identity = {
            "port": 63342,
            "session_id": "session",
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
            "open_projects": [
                {
                    "base_path": "/tmp/worktree",
                    "project_instance_id": "session:1",
                }
            ],
        }

        with patch_attr(jb_inspect, "discover_identities", return_value=[identity]):
            routes, sessions = jb_inspect.discover_routes_for_cleanup(Namespace(port=None))

        self.assertEqual(sessions, {"session"})
        self.assertEqual(routes[0]["session_id"], "session")
        self.assertEqual(
            routes[0]["ide"]["lifecycle_ownership_protocol"],
            jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
        )

    def test_cleanup_leases_uses_lifecycle_lock(self):
        events = []

        class FakeLock:
            def __enter__(self):
                events.append("enter")

            def __exit__(self, exc_type, exc, traceback):
                events.append("exit")

        with (
            patch_attr(
                jb_inspect,
                "lifecycle_lock",
                side_effect=lambda timeout_ms: events.append(("timeout", timeout_ms)) or FakeLock(),
            ),
            patch_attr(jb_inspect, "cleanup_stale_helper_leases", return_value={"status": "ok", "removed": []}),
        ):
            result = jb_inspect.command_cleanup_leases(
                Namespace(max_age_ms=1, dry_run=True, lifecycle_lock_timeout_ms=1234)
            )

        self.assertEqual(result, {"status": "ok", "removed": []})
        self.assertEqual(events, [("timeout", 1234), "enter", "exit"])

    def test_lifecycle_close_preserves_failed_close_attempt_diagnostics(self):
        original_private = jb_inspect.private_http_get_body
        jb_inspect.private_http_get_body = lambda port, endpoint, params, timeout=None: {
            "status": "skipped",
            "reason": "close_failed",
            "message": "declined",
            "close_attempts": [{"attempt": 1, "force_close_returned": False}],
        }
        try:
            result = jb_inspect.call_lifecycle_close({"port": 1}, {"project_key": "path:/tmp/worktree"})
        finally:
            jb_inspect.private_http_get_body = original_private

        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["reason"], "close_failed")
        self.assertEqual(result["message"], "declined")
        self.assertEqual(result["close_attempts"], [{"attempt": 1, "force_close_returned": False}])

    def test_lifecycle_close_preserves_success_identifiers(self):
        payload = {
            "status": "closed",
            "project_instance_id": "session:1",
            "project_key": "path:/tmp/worktree",
            "lease_id": "lease-1",
            "session_id": "session",
            "closed_at_ms": 1234,
            "close_attempts": [{"attempt": 1, "closed_verified": True}],
        }
        with patch_attr(jb_inspect, "private_http_get_body", return_value=payload):
            result = jb_inspect.call_lifecycle_close({"port": 1}, {"project_key": "path:/tmp/worktree"})

        self.assertEqual(result["status"], "closed")
        self.assertEqual(result["project_instance_id"], "session:1")
        self.assertEqual(result["project_key"], "path:/tmp/worktree")
        self.assertEqual(result["lease_id"], "lease-1")
        self.assertEqual(result["session_id"], "session")
        self.assertEqual(result["closed_at_ms"], 1234)
        self.assertEqual(result["close_attempts"], [{"attempt": 1, "closed_verified": True}])

    def test_lifecycle_close_preserves_http_conflict_payload(self):
        payload = {
            "status": "skipped",
            "reason": "close_failed",
            "message": "declined",
            "close_attempts": [{"attempt": 1, "force_close_returned": False}],
        }
        original_private = jb_inspect.private_http_get_body
        jb_inspect.private_http_get_body = lambda *args, **kwargs: (_ for _ in ()).throw(jb_inspect.InspectError("HTTP 409", 3, payload))
        try:
            result = jb_inspect.call_lifecycle_close({"port": 1}, {"project_key": "path:/tmp/worktree"})
        finally:
            jb_inspect.private_http_get_body = original_private

        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["reason"], "close_failed")
        self.assertEqual(result["close_attempts"], [{"attempt": 1, "force_close_returned": False}])

    def test_cleanup_leases_preserves_lease_when_close_fails(self):
        removed = []
        lease = {
            "lease_id": "old-lease",
            "state": "kept_warm_after_indexing_timeout",
            "opened_by_helper": True,
            "pid": 999999,
            "lifecycle_target_path": "/tmp/worktree",
            "project_instance_id": "session:1",
        }
        path = Path("/tmp/old-lease.json")

        original_read = jb_inspect.read_local_leases
        original_pid_alive = jb_inspect.pid_alive
        original_routes = jb_inspect.discover_routes_for_cleanup
        original_cleanup = jb_inspect.cleanup_stale_helper_lease
        jb_inspect.read_local_leases = lambda: [(path, lease.copy())]
        jb_inspect.pid_alive = lambda pid: False
        jb_inspect.discover_routes_for_cleanup = lambda args: (
            [{"port": 1, "base_path": "/tmp/worktree", "project_instance_id": "session:1", "session_id": "session"}],
            {"session"},
        )
        jb_inspect.cleanup_stale_helper_lease = lambda cleanup_lease, routes, observed_sessions=None: {"status": "failed", "reason": "close_failed", "lease_id": cleanup_lease["lease_id"]}
        try:
            with patch.object(Path, "unlink", lambda self, missing_ok=False: removed.append(str(self))):
                result = jb_inspect.cleanup_stale_helper_leases(Namespace(max_age_ms=86_400_000, dry_run=False))
        finally:
            jb_inspect.read_local_leases = original_read
            jb_inspect.pid_alive = original_pid_alive
            jb_inspect.discover_routes_for_cleanup = original_routes
            jb_inspect.cleanup_stale_helper_lease = original_cleanup

        self.assertEqual(removed, [])
        self.assertEqual(result["removed"], [])
        self.assertEqual(result["failed"][0]["lease_id"], "old-lease")

    def test_problems_params_preserve_files_scope_selectors(self):
        parser = jb_inspect.build_parser()
        args = parser.parse_args([
            "get-problems",
            "--scope",
            "files",
            "--file",
            "src/App.kt",
            "--file",
            "src/AppTest.kt",
            "--max-files",
            "25",
            "--limit",
            "500",
        ])
        params = jb_inspect.problems_params(args, {"scope": "files"}, {"project_key": "path:/tmp/repo"})

        self.assertEqual(params, {
            "project_key": "path:/tmp/repo",
            "session_id": None,
            "project_path": None,
            "worktree_path": None,
            "cwd": None,
            "project": None,
            "ide": None,
            "scope": "files",
            "include_unversioned": "true",
            "changed_files_mode": "all",
            "profile": "",
            "files": "src/App.kt\nsrc/AppTest.kt",
            "max_files": 25,
            "severity": "all",
            "problem_type": "all",
            "file_pattern": "all",
            "limit": 500,
            "offset": 0,
        })

    def test_get_problems_scope_parser_preserves_existing_defaults(self):
        parser = jb_inspect.build_parser()
        args = parser.parse_args(["get-problems"])

        params = jb_inspect.problems_params(
            args,
            {"scope": "directory"},
            {"project_key": "path:/tmp/repo"},
        )

        self.assertEqual(params, {
            "project_key": "path:/tmp/repo",
            "session_id": None,
            "project_path": None,
            "worktree_path": None,
            "cwd": None,
            "project": None,
            "ide": None,
            "scope": "directory",
            "include_unversioned": "true",
            "changed_files_mode": "all",
            "profile": "",
            "severity": "all",
            "problem_type": "all",
            "file_pattern": "all",
            "limit": 100,
            "offset": 0,
        })

    def test_find_exact_route_returns_none_for_containing_project(self):
        original_resolve_route = jb_inspect.resolve_route
        jb_inspect.resolve_route = lambda args, context: {"base_path": "/tmp/repo"}
        try:
            route = jb_inspect.find_exact_route(
                Namespace(no_worktree_check=False, open=False),
                {"worktree_root": "/tmp/repo/packages/app"},
            )
        finally:
            jb_inspect.resolve_route = original_resolve_route

        self.assertIsNone(route)

    def test_closeout_runs_inspection_on_prepared_route(self):
        calls = []

        def fake_prepare(args, context):
            return {
                "status": "prepared",
                "route": {"port": 1, "project_key": "path:/tmp/worktree", "base_path": "/tmp/worktree"},
                "lease": {"opened_by_helper": False},
                "_lease": {"opened_by_helper": False},
            }

        def fake_run(args, context, route):
            calls.append(route)
            return {"status": "clean", "clean": True, "route": route}

        original_prepare = jb_inspect.prepare_lifecycle_details
        original_run = jb_inspect.run_inspection_on_route
        jb_inspect.prepare_lifecycle_details = lambda args, context: (fake_prepare(args, context), {"opened_by_helper": False}, None)
        jb_inspect.run_inspection_on_route = fake_run
        try:
            result = jb_inspect.command_closeout(Namespace(keep_warm=True), {})
        finally:
            jb_inspect.prepare_lifecycle_details = original_prepare
            jb_inspect.run_inspection_on_route = original_run

        self.assertEqual(result["status"], "clean")
        self.assertEqual(calls, [{"port": 1, "project_key": "path:/tmp/worktree", "base_path": "/tmp/worktree"}])
        self.assertNotIn("_lease", result["prepared"])

    def test_keep_warm_marks_helper_opened_project_as_not_cleanup_clean(self):
        route = {"port": 1, "project_key": "path:/tmp/worktree", "base_path": "/tmp/worktree"}
        lease = {"lease_id": "lease-1", "opened_by_helper": True}
        original_prepare = jb_inspect.prepare_lifecycle_details
        original_run = jb_inspect.run_inspection_on_route
        original_cleanup = jb_inspect.cleanup_lifecycle
        jb_inspect.prepare_lifecycle_details = lambda args, context: ({"status": "prepared", "route": route}, lease, None)
        jb_inspect.run_inspection_on_route = lambda args, context, active_route: {
            "status": "clean",
            "clean": True,
            "route": active_route,
        }
        jb_inspect.cleanup_lifecycle = lambda *args: self.fail("keep-warm must not close the helper-opened project")
        try:
            result = jb_inspect.command_closeout(Namespace(keep_warm=True), {})
        finally:
            jb_inspect.prepare_lifecycle_details = original_prepare
            jb_inspect.run_inspection_on_route = original_run
            jb_inspect.cleanup_lifecycle = original_cleanup

        self.assertEqual(result["verdict"], "GREEN")
        self.assertEqual(
            result["cleanup"],
            {"status": "kept_warm", "reason": "keep_warm_requested", "lease_id": "lease-1"},
        )
        self.assertFalse(result.get("cleanup_failed", False))

    def test_closeout_retries_retryable_unknown_once_before_cleanup(self):
        calls = []
        route = {"port": 1, "project_key": "path:/tmp/worktree", "base_path": "/tmp/worktree"}
        readiness = {"status": "ready", "ready": True, "exit_reason": "ready"}

        def fake_run(args, context, active_route):
            calls.append(active_route)
            if len(calls) == 1:
                return {
                    "status": "stale_results",
                    "verdict": "UNKNOWN",
                    "verdict_reason": "stale_results",
                    "bucket": "stale_results",
                    "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 30000},
                    "cached_total_problems": 2,
                    "wait": {"completion_reason": "stale_results"},
                }
            return {
                "status": "findings",
                "verdict": "RED",
                "verdict_reason": "actionable_findings",
                "bucket": "actionable_findings",
                "total_problems": 1,
                "problems": [{"description": "real problem"}],
                "route": active_route,
            }

        original_prepare = jb_inspect.prepare_lifecycle_details
        original_run = jb_inspect.run_inspection_on_route
        original_readiness = jb_inspect.wait_for_internal_retry_readiness
        jb_inspect.prepare_lifecycle_details = lambda args, context: ({"status": "prepared", "route": route}, {"opened_by_helper": False}, None)
        jb_inspect.run_inspection_on_route = fake_run
        jb_inspect.wait_for_internal_retry_readiness = lambda args, context, active_route, first_result: readiness
        try:
            result = jb_inspect.command_closeout(Namespace(keep_warm=True), {})
        finally:
            jb_inspect.prepare_lifecycle_details = original_prepare
            jb_inspect.run_inspection_on_route = original_run
            jb_inspect.wait_for_internal_retry_readiness = original_readiness

        self.assertEqual(result["verdict"], "RED")
        self.assertEqual(result["total_problems"], 1)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result["internal_retry_count"], 1)
        self.assertEqual(result["internal_retry_readiness"], readiness)
        self.assertTrue(result["recovered_from_unknown"])
        self.assertEqual(result["internal_retries"][0]["verdict_reason"], "stale_results")

    def test_closeout_does_not_internally_retry_full_inspection_timeout(self):
        calls = []
        timed_out = {
            "status": "timed_out",
            "verdict": "UNKNOWN",
            "verdict_reason": "timeout",
            "bucket": "ide_not_ready",
            "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 30000},
            "wait": {"timed_out": True, "inspection_in_progress": True},
        }

        with patch_attr(
            jb_inspect,
            "run_inspection_on_route",
            side_effect=lambda args, context, route: calls.append(route) or timed_out.copy(),
        ):
            result = jb_inspect.run_inspection_with_internal_retry(
                Namespace(),
                {},
                {"port": 63342, "project_key": "path:/tmp/worktree"},
            )

        self.assertEqual(len(calls), 1)
        self.assertEqual(result["verdict_reason"], "timeout")
        self.assertNotIn("internal_retry_count", result)

    def test_exhausted_internal_retry_is_not_advertised_again(self):
        attempts = iter(
            [
                {
                    "status": "stale_results",
                    "verdict": "UNKNOWN",
                    "verdict_reason": "stale_results",
                    "bucket": "stale_results",
                    "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 0},
                },
                {
                    "status": "stale_results",
                    "verdict": "UNKNOWN",
                    "verdict_reason": "stale_results",
                    "bucket": "stale_results",
                    "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 0},
                },
            ]
        )

        with (
            patch_attr(jb_inspect, "run_inspection_on_route", side_effect=lambda *args: next(attempts)),
            patch_attr(
                jb_inspect,
                "wait_for_internal_retry_readiness",
                return_value={"status": "ready", "ready": True, "exit_reason": "ready"},
            ),
        ):
            result = jb_inspect.run_inspection_with_internal_retry(Namespace(), {}, {"port": 63342})
        jb_inspect.apply_verdict(result)

        self.assertTrue(result["retry_exhausted"])
        self.assertFalse(result["retry_policy"]["retry"])
        self.assertEqual(result["retry_policy"]["max_attempts"], 0)
        self.assertIn("Do not attribute it to source edits", result["agent_result"]["next_action"])

    def test_internal_retry_extends_budget_when_later_result_requires_more_attempts(self):
        attempts = iter(
            [
                {
                    "status": "stale_results",
                    "verdict": "UNKNOWN",
                    "verdict_reason": "stale_results",
                    "bucket": "stale_results",
                    "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 0},
                },
                {
                    "status": "capture_incomplete",
                    "verdict": "UNKNOWN",
                    "verdict_reason": "project_analysis_not_ready",
                    "bucket": "capture_not_ready",
                    "retry_policy": {"retry": True, "max_attempts": 3, "wait_ms": 0},
                },
                {
                    "status": "findings",
                    "verdict": "RED",
                    "verdict_reason": "actionable_findings",
                    "bucket": "actionable_findings",
                    "total_problems": 1,
                    "problems": [{"description": "real problem"}],
                },
            ]
        )

        with (
            patch_attr(jb_inspect, "run_inspection_on_route", side_effect=lambda *args: next(attempts)),
            patch_attr(
                jb_inspect,
                "wait_for_internal_retry_readiness",
                return_value={"status": "ready", "ready": True, "exit_reason": "ready"},
            ),
        ):
            result = jb_inspect.run_inspection_with_internal_retry(Namespace(), {}, {"port": 63342})

        self.assertEqual(result["verdict"], "RED")
        self.assertEqual(result["internal_retry_count"], 2)
        self.assertEqual(
            [retry["verdict_reason"] for retry in result["internal_retries"]],
            ["stale_results", "project_analysis_not_ready"],
        )
        self.assertTrue(result["recovered_from_unknown"])
        self.assertFalse(result["retry_exhausted"])

    def test_internal_retry_treats_prior_stale_result_as_idle_but_resets_on_indexing(self):
        statuses = iter(
            [
                {"status": "indexing", "indexing": True},
                {"status": "stale_results", "results_may_be_stale": True},
                {"status": "indexing", "indexing": True},
                {"status": "stale_results", "results_may_be_stale": True},
                {"status": "stale_results", "results_may_be_stale": True},
                {"status": "stale_results", "results_may_be_stale": True},
            ]
        )
        times = iter([0, 0, 0, 1_000, 1_000, 2_000, 2_000, 3_000, 3_000, 4_000, 4_000, 5_000, 5_000])
        first_result = {
            "verdict_reason": "stale_results",
            "bucket": "stale_results",
            "retry_policy": {"wait_ms": 0},
        }

        with (
            patch_attr(jb_inspect, "call_endpoint", side_effect=lambda route, endpoint, params: next(statuses)),
            patch_attr(jb_inspect, "monotonic_ms", side_effect=lambda: next(times)),
            patch.object(time, "sleep"),
        ):
            readiness = jb_inspect.wait_for_internal_retry_readiness(
                helper_args(poll_ms=1_000),
                {"worktree_root": "/tmp/worktree"},
                {"port": 63342, "project_key": "path:/tmp/worktree"},
                first_result,
            )

        self.assertTrue(readiness["ready"])
        self.assertEqual(readiness["status"], "ready")
        self.assertEqual(readiness["sample_count"], 6)
        self.assertEqual(readiness["stable_observations"], 3)
        self.assertEqual(readiness["first_status"]["status"], "indexing")
        self.assertEqual(readiness["last_status"]["status"], "stale_results")
        self.assertEqual(readiness["same_worktree_writer_observation"], "not_available")

    def test_internal_retry_skips_when_readiness_is_not_proven(self):
        calls = []
        stale_result = {
            "status": "stale_results",
            "verdict": "UNKNOWN",
            "verdict_reason": "stale_results",
            "bucket": "stale_results",
            "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 30_000},
            "results_may_be_stale": True,
            "stale_reasons": ["project_changed_since_inspection"],
        }
        readiness = {
            "status": "timeout",
            "ready": False,
            "exit_reason": "indexing",
            "observation_scope": "ide_status_only",
            "same_worktree_writer_observation": "not_available",
        }

        with (
            patch_attr(
                jb_inspect,
                "run_inspection_on_route",
                side_effect=lambda *args: calls.append(args) or stale_result.copy(),
            ),
            patch_attr(jb_inspect, "wait_for_internal_retry_readiness", return_value=readiness),
        ):
            result = jb_inspect.run_inspection_with_internal_retry(Namespace(), {}, {"port": 63342})
        jb_inspect.apply_verdict(result)

        self.assertEqual(len(calls), 1)
        self.assertTrue(result["internal_retry_skipped"])
        self.assertEqual(result["internal_retry_count"], 0)
        self.assertNotIn("internal_retries", result)
        self.assertTrue(result["retry_exhausted"])
        self.assertFalse(result["retry_policy"]["retry"])
        self.assertIn("withheld its internal retry", result["agent_result"]["next_action"])
        self.assertIn("does not identify", result["unknown_diagnosis"]["not_proven"])
        self.assertEqual(result["unknown_diagnosis"]["readiness_barrier_exit_reason"], "indexing")
        self.assertEqual(len(jb_inspect.ordered_internal_attempts(result)), 1)
        record = jb_inspect.unknown_log_record(result)
        self.assertTrue(record["internal_retry_skipped"])
        self.assertEqual(record["internal_retry_readiness"]["exit_reason"], "indexing")
        self.assertEqual(record["unknown_diagnosis"]["classification"], "inspection_state_changed")

    def test_internal_retry_readiness_timeout_is_bounded(self):
        times = iter([0, 0, 0, 1_000, 1_000, 2_000, 2_000, 3_000, 3_000, 4_000, 4_000])
        first_result = {
            "verdict_reason": "stale_results",
            "bucket": "stale_results",
            "retry_policy": {"wait_ms": 0},
        }

        with (
            patch_attr(jb_inspect, "INTERNAL_RETRY_READY_TIMEOUT_MS", 2_000),
            patch_attr(jb_inspect, "call_endpoint", return_value={"status": "indexing", "indexing": True}),
            patch_attr(jb_inspect, "monotonic_ms", side_effect=lambda: next(times)),
            patch.object(time, "sleep"),
        ):
            readiness = jb_inspect.wait_for_internal_retry_readiness(
                helper_args(poll_ms=1_000),
                {"worktree_root": "/tmp/worktree"},
                {"port": 63342, "project_key": "path:/tmp/worktree"},
                first_result,
            )

        self.assertFalse(readiness["ready"])
        self.assertEqual(readiness["status"], "timeout")
        self.assertEqual(readiness["exit_reason"], "indexing")
        self.assertEqual(readiness["sample_count"], 4)
        self.assertEqual(readiness["timeout_ms"], 3_000)

    def test_internal_retry_readiness_probe_error_fails_closed(self):
        first_result = {
            "verdict_reason": "stale_results",
            "bucket": "stale_results",
            "retry_policy": {"wait_ms": 30_000},
        }
        error = jb_inspect.InspectError(
            "status unavailable",
            3,
            {"error_reason": "inspection_api_unavailable"},
        )

        with (
            patch_attr(jb_inspect, "call_endpoint", side_effect=error),
            patch_attr(jb_inspect, "monotonic_ms", side_effect=[0, 0, 0]),
        ):
            readiness = jb_inspect.wait_for_internal_retry_readiness(
                helper_args(poll_ms=1_000),
                {"worktree_root": "/tmp/worktree"},
                {"port": 63342, "project_key": "path:/tmp/worktree"},
                first_result,
            )

        self.assertFalse(readiness["ready"])
        self.assertEqual(readiness["status"], "unavailable")
        self.assertEqual(readiness["exit_reason"], "inspection_api_unavailable")
        self.assertEqual(readiness["sample_count"], 0)

    def test_timed_out_inspection_requests_cancellation_and_waits_for_settlement(self):
        cancel_params = []
        statuses = iter(
            [
                {"inspection_in_progress": True, "inspection_run_id": 7, "is_scanning": True},
                {"inspection_in_progress": False, "inspection_run_id": 7, "is_scanning": False, "indexing": False},
            ]
        )

        def fake_call(route, endpoint, params, timeout=None):
            if endpoint == "cancel":
                cancel_params.append(params)
                return {"status": "cancel_requested", "inspection_cancellation_requested": True}
            if endpoint == "status":
                return next(statuses)
            self.fail(f"unexpected endpoint: {endpoint}")

        with (
            patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call),
            patch_attr(jb_inspect, "now_ms", side_effect=[0, 0, 1000]),
            patch.object(time, "sleep"),
        ):
            result = jb_inspect.cancel_timed_out_inspection(
                Namespace(project_key=None, project_path=None, worktree_path=None, cwd=None, project=None, ide=None, session_id=None),
                {"worktree_root": "/tmp/worktree"},
                {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"},
                {"timed_out": True, "inspection_in_progress": True, "inspection_run_id": 7},
            )

        self.assertEqual(result["status"], "settled")
        self.assertTrue(result["requested"])
        self.assertTrue(result["settled"])
        self.assertFalse(result["last_status"]["inspection_in_progress"])
        self.assertEqual(cancel_params[0]["inspection_run_id"], 7)

    def test_cancellation_refuses_to_cancel_a_changed_run(self):
        calls = []

        def fake_call(route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            self.assertEqual(endpoint, "cancel")
            return {
                "status": "run_changed",
                "inspection_run_id": 8,
                "expected_inspection_run_id": 7,
            }

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.cancel_timed_out_inspection(
                helper_args(),
                {"worktree_root": "/tmp/worktree"},
                {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"},
                {"timed_out": True, "inspection_in_progress": True, "inspection_run_id": 7},
            )

        self.assertEqual(result["status"], "run_changed")
        self.assertFalse(result["requested"])
        self.assertFalse(result["settled"])
        self.assertEqual(calls[0][1]["inspection_run_id"], 7)

    def test_wait_run_change_never_cancels_or_reads_replacement_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {
                    "status": "run_changed",
                    "inspection_run_id": 8,
                    "expected_inspection_run_id": 7,
                    "inspection_in_progress": True,
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual([endpoint for endpoint, _ in calls], ["trigger", "wait"])
        self.assertEqual(calls[1][1]["inspection_run_id"], 7)
        self.assertEqual(result["status"], "run_changed")
        self.assertEqual(result["expected_inspection_run_id"], 7)
        self.assertEqual(result["inspection_run_id"], 8)
        self.assertTrue(result["transport_state_unknown"])
        self.assertEqual(result["verdict_reason"], "run_changed")
        self.assertTrue(jb_inspect.should_defer_lifecycle_cleanup(result, {"opened_by_helper": True}))

    def test_unproven_wait_run_change_continues_with_the_accepted_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {
                    "status": "run_changed",
                    "expected_inspection_run_id": 7,
                    "inspection_run_id": 7,
                    "inspection_triggered": True,
                    "inspection_in_progress": False,
                    "wait_completed": False,
                }
            if endpoint == "problems":
                return {
                    "status": "results_available",
                    "inspection_run_id": 7,
                    "snapshot_run_id": 7,
                    "total_problems": 1,
                    "problems": [{"description": "accepted run finding", "severity": "warning"}],
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual([endpoint for endpoint, _ in calls], ["trigger", "wait", "problems"])
        self.assertEqual(result["status"], "findings")
        self.assertEqual(result["verdict"], "RED")
        self.assertEqual(result["verdict_reason"], "actionable_findings")
        self.assertNotEqual(result.get("error_reason"), "run_changed")

    def test_wait_rejects_snapshot_from_an_older_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {
                    "status": "clean",
                    "wait_completed": True,
                    "inspection_in_progress": False,
                    "inspection_run_id": 7,
                    "snapshot_run_id": 6,
                    "clean_inspection": True,
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual([endpoint for endpoint, _ in calls], ["trigger", "wait"])
        self.assertEqual(result["status"], "run_changed")
        self.assertEqual(result["expected_inspection_run_id"], 7)
        self.assertEqual(result["inspection_run_id"], 7)
        self.assertEqual(result["wait"]["snapshot_run_id"], 6)
        self.assertTrue(result["transport_state_unknown"])

    def test_completed_replacement_run_is_not_reported_as_the_accepted_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {
                    "status": "results_available",
                    "wait_completed": True,
                    "inspection_in_progress": False,
                    "inspection_run_id": 7,
                    "snapshot_run_id": 7,
                }
            if endpoint == "problems":
                return {
                    "status": "results_available",
                    "inspection_in_progress": False,
                    "inspection_run_id": 8,
                    "snapshot_run_id": 8,
                    "total_problems": 0,
                    "problems": [],
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual(result["status"], "run_changed")
        self.assertEqual(result["expected_inspection_run_id"], 7)
        self.assertEqual(result["inspection_run_id"], 8)
        self.assertTrue(result["transport_state_unknown"])
        problems_call = next(params for endpoint, params in calls if endpoint == "problems")
        self.assertEqual(problems_call["inspection_run_id"], 7)

    def test_active_accepted_run_keeps_prior_snapshot_as_stale(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {
                    "status": "timed_out",
                    "timed_out": True,
                    "inspection_in_progress": True,
                    "inspection_run_id": 7,
                }
            if endpoint == "problems":
                return {
                    "status": "inspection_in_progress",
                    "inspection_in_progress": True,
                    "inspection_run_id": 7,
                    "snapshot_run_id": 6,
                    "total_problems": 0,
                    "problems": [],
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with (
            patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call),
            patch_attr(
                jb_inspect,
                "cancel_timed_out_inspection",
                return_value={"status": "settlement_timeout", "requested": True, "settled": False},
            ),
        ):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual([endpoint for endpoint, _ in calls], ["trigger", "wait", "problems"])
        self.assertEqual(result["status"], "timed_out")
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertEqual(result["verdict_reason"], "timeout")
        self.assertNotEqual(result.get("error_reason"), "run_changed")

    def test_cancellation_settlement_rejects_completed_replacement_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}

        def fake_call(active_route, endpoint, params, timeout=None):
            if endpoint == "cancel":
                return {
                    "status": "cancel_requested",
                    "inspection_cancellation_requested": True,
                    "inspection_run_id": 7,
                }
            if endpoint == "status":
                return {
                    "status": "results_available",
                    "inspection_in_progress": False,
                    "inspection_run_id": 8,
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.cancel_timed_out_inspection(
                helper_args(),
                {"worktree_root": "/tmp/worktree"},
                route,
                {"timed_out": True, "inspection_in_progress": True, "inspection_run_id": 7},
                expected_run_id=7,
            )

        self.assertEqual(result["status"], "run_changed")
        self.assertFalse(result["settled"])
        self.assertEqual(result["expected_inspection_run_id"], 7)
        self.assertEqual(result["inspection_run_id"], 8)

    def test_problems_transport_failure_defers_cleanup_for_unsettled_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}

        def fake_call(active_route, endpoint, params, timeout=None):
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {
                    "status": "timed_out",
                    "timed_out": True,
                    "inspection_in_progress": True,
                    "inspection_run_id": 7,
                }
            if endpoint == "problems":
                raise jb_inspect.InspectError(
                    "Inspection API timed out on port 63342: timed out",
                    3,
                    {"error_reason": "inspection_api_timeout", "endpoint": "problems", "port": 63342},
                )
            self.fail(f"unexpected endpoint: {endpoint}")

        with (
            patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call),
            patch_attr(
                jb_inspect,
                "cancel_timed_out_inspection",
                return_value={"status": "settlement_timeout", "requested": True, "settled": False},
            ),
        ):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual(result["error_reason"], "inspection_api_timeout")
        self.assertEqual(result["endpoint"], "problems")
        self.assertTrue(result["transport_state_unknown"])
        self.assertTrue(jb_inspect.should_defer_lifecycle_cleanup(result, {"opened_by_helper": True}))

    def test_wait_transport_timeout_cancels_only_the_accepted_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        calls = []
        statuses = iter(
            [
                {"inspection_in_progress": True, "inspection_run_id": 7, "is_scanning": True},
                {"inspection_in_progress": False, "inspection_run_id": 7, "is_scanning": False, "indexing": False},
            ]
        )

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                raise jb_inspect.InspectError(
                    "Inspection API timed out on port 63342: timed out",
                    3,
                    {"error_reason": "inspection_api_timeout", "endpoint": "wait", "port": 63342},
                )
            if endpoint == "status":
                return next(statuses)
            if endpoint == "cancel":
                return {"status": "cancel_requested", "inspection_cancellation_requested": True, "inspection_run_id": 7}
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertEqual(result["error_reason"], "inspection_api_timeout")
        self.assertEqual(result["timeout_endpoint"], "wait")
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertEqual(result["verdict_reason"], "inspection_api_timeout")
        self.assertEqual(result["cancellation"]["status"], "settled")
        self.assertTrue(result["cancellation"]["settled"])
        cancel_call = next(params for endpoint, params in calls if endpoint == "cancel")
        self.assertEqual(cancel_call["inspection_run_id"], 7)

    def test_trigger_transport_timeout_remains_ambiguous_after_idle_status_probe(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}

        def fake_call(active_route, endpoint, params, timeout=None):
            if endpoint == "trigger":
                raise jb_inspect.InspectError(
                    "Inspection API timed out on port 63342: timed out",
                    3,
                    {"error_reason": "inspection_api_timeout", "endpoint": "trigger", "port": 63342},
                )
            if endpoint == "status":
                return {"inspection_in_progress": False, "is_scanning": False, "indexing": False}
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )

        self.assertTrue(result["transport_state_unknown"])
        self.assertTrue(jb_inspect.should_defer_lifecycle_cleanup(result, {"opened_by_helper": True}))
        self.assertEqual(result["verdict_reason"], "inspection_api_timeout")

    def test_unknown_transport_state_defers_owned_project_cleanup(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        lease = {"lease_id": "lease-1", "opened_by_helper": True, "state": "prepared"}
        timeout_result = {
            "status": "error",
            "error_reason": "inspection_api_timeout",
            "transport_state_unknown": True,
            "wait": {"timed_out": True},
        }

        with (
            patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": route}, lease, "proof-1")),
            patch_attr(jb_inspect, "run_inspection_with_internal_retry", return_value=timeout_result),
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
            patch_attr(
                jb_inspect,
                "defer_lifecycle_cleanup",
                return_value={"status": "deferred", "cleanup_deferred": True},
            ) as defer_cleanup,
        ):
            result = jb_inspect.command_closeout(
                helper_args(keep_warm=False, lifecycle_lock_timeout_ms=0),
                {},
            )

        cleanup.assert_not_called()
        defer_cleanup.assert_called_once_with(lease, timeout_result)
        self.assertEqual(result["cleanup"]["status"], "deferred")
        self.assertTrue(result["cleanup_deferred"])

    def test_inspection_exception_defers_owned_project_cleanup(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        lease = {"lease_id": "lease-1", "opened_by_helper": True, "state": "prepared"}
        error = jb_inspect.InspectError(
            "Inspection API returned invalid JSON",
            3,
            {"error_reason": "invalid_api_response", "endpoint": "problems"},
        )

        with (
            patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": route}, lease, "proof-1")),
            patch_attr(jb_inspect, "run_inspection_with_internal_retry", side_effect=error),
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
            patch_attr(
                jb_inspect,
                "defer_lifecycle_cleanup",
                return_value={"status": "deferred", "cleanup_deferred": True},
            ) as defer_cleanup,
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.command_closeout(
                    helper_args(keep_warm=False, lifecycle_lock_timeout_ms=0),
                    {},
                )

        cleanup.assert_not_called()
        defer_cleanup.assert_called_once()
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "deferred")
        self.assertTrue(raised.exception.payload["inspection_failure"]["transport_state_unknown"])
        self.assertEqual(raised.exception.payload["inspection_failure"]["error_reason"], "invalid_api_response")

    def test_keyboard_interrupt_defers_owned_project_cleanup_before_reraising(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        lease = {"lease_id": "lease-1", "opened_by_helper": True, "state": "prepared"}

        with (
            patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": route}, lease, "proof-1")),
            patch_attr(jb_inspect, "run_inspection_with_internal_retry", side_effect=KeyboardInterrupt()),
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
            patch_attr(
                jb_inspect,
                "defer_lifecycle_cleanup",
                return_value={"status": "deferred", "cleanup_deferred": True},
            ) as defer_cleanup,
        ):
            with self.assertRaises(KeyboardInterrupt):
                jb_inspect.command_closeout(
                    helper_args(keep_warm=False, lifecycle_lock_timeout_ms=0),
                    {},
                )

        cleanup.assert_not_called()
        defer_cleanup.assert_called_once()

    def test_generic_inspection_exception_becomes_structured_inspect_error(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        lease = {"lease_id": "lease-1", "opened_by_helper": True, "state": "prepared"}

        with (
            patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": route}, lease, "proof-1")),
            patch_attr(jb_inspect, "run_inspection_with_internal_retry", side_effect=RuntimeError("boom")),
            patch_attr(
                jb_inspect,
                "defer_lifecycle_cleanup",
                return_value={"status": "deferred", "cleanup_deferred": True},
            ),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.command_closeout(
                    helper_args(keep_warm=False, lifecycle_lock_timeout_ms=0),
                    {},
                )

        self.assertEqual(raised.exception.payload["error_type"], "RuntimeError")
        self.assertEqual(raised.exception.payload["error_message"], "boom")
        self.assertEqual(raised.exception.payload["cleanup"]["status"], "deferred")
        self.assertTrue(raised.exception.payload["inspection_failure"]["transport_state_unknown"])

    def test_settled_cancellation_allows_owned_project_cleanup(self):
        result = {
            "status": "timed_out",
            "verdict": "UNKNOWN",
            "cancellation": {
                "settled": True,
                "last_status": {"inspection_in_progress": False, "is_scanning": False, "indexing": False},
            },
            "wait": {"timed_out": True, "inspection_in_progress": True},
        }

        self.assertFalse(jb_inspect.should_defer_lifecycle_cleanup(result, {"opened_by_helper": True}))

    def test_run_uses_lifecycle_prepare_and_cleanup(self):
        calls = []
        cleanups = []

        prepared = {
            "status": "prepared",
            "route": {"port": 1, "project_key": "path:/tmp/worktree", "base_path": "/tmp/worktree"},
            "lease": {"opened_by_helper": True},
        }
        lease = {"opened_by_helper": True, "lease_id": "lease-1"}

        def fake_run(args, context, route):
            calls.append(route)
            return {"status": "clean", "clean": True, "route": route}

        def fake_cleanup(cleanup_lease, route, close_proof):
            cleanups.append((cleanup_lease, route, close_proof))
            return {"status": "closed"}

        original_prepare = jb_inspect.prepare_lifecycle_details
        original_run = jb_inspect.run_inspection_on_route
        original_cleanup = jb_inspect.cleanup_lifecycle
        jb_inspect.prepare_lifecycle_details = lambda args, context: (prepared, lease, "proof-1")
        jb_inspect.run_inspection_on_route = fake_run
        jb_inspect.cleanup_lifecycle = fake_cleanup
        try:
            args = Namespace(
                keep_warm=False,
                lifecycle_lock_timeout_ms=0,
            )
            result = jb_inspect.command_run(args, {})
        finally:
            jb_inspect.prepare_lifecycle_details = original_prepare
            jb_inspect.run_inspection_on_route = original_run
            jb_inspect.cleanup_lifecycle = original_cleanup

        self.assertEqual(result["status"], "clean")
        self.assertEqual(result["cleanup"], {"status": "closed"})
        self.assertEqual(calls, [prepared["route"]])
        self.assertEqual(cleanups, [(lease, prepared["route"], "proof-1")])

    def test_http_get_redacts_sensitive_query_in_result_url(self):
        captured = {}
        original_urlopen = jb_inspect.urllib.request.urlopen

        class FakeResponse:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return None

            def read(self):
                return b'{"ok": true}'

        def fake_urlopen(request, timeout):
            captured["url"] = request.full_url
            return FakeResponse()

        jb_inspect.urllib.request.urlopen = fake_urlopen
        try:
            result = jb_inspect.http_get(63342, "lifecycle/close", {"close_token": "private-close-proof", "project_key": "path:/tmp/repo"})
        finally:
            jb_inspect.urllib.request.urlopen = original_urlopen

        self.assertIn("private-close-proof", captured["url"])
        self.assertNotIn("private-close-proof", result.url)
        self.assertIn(urllib.parse.quote(jb_inspect.REDACTED), result.url)

    def test_http_get_returns_inspection_in_progress_conflict(self):
        payload = {
            "error": "inspection_in_progress",
            "status": "inspection_in_progress",
            "inspection_in_progress": True,
            "inspection_run_id": 42,
        }
        error = jb_inspect.urllib.error.HTTPError(
            "inspection-trigger",
            409,
            "Conflict",
            {},
            io.BytesIO(json.dumps(payload).encode()),
        )

        with patch_attr(jb_inspect.urllib.request, "urlopen", side_effect=error):
            result = jb_inspect.http_get(63342, "trigger", {})

        self.assertEqual(result.status, 409)
        self.assertEqual(result.body, payload)

    def test_http_get_classifies_socket_timeout_as_busy_api(self):
        with patch_attr(jb_inspect.urllib.request, "urlopen", side_effect=TimeoutError("timed out")):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.http_get(63342, "lifecycle/open", {})

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_api_timeout")
        self.assertEqual(raised.exception.payload["endpoint"], "lifecycle/open")

    def test_http_get_keeps_connection_refusal_as_unavailable_api(self):
        refused = jb_inspect.urllib.error.URLError(ConnectionRefusedError("connection refused"))
        with patch_attr(jb_inspect.urllib.request, "urlopen", side_effect=refused):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.http_get(63342, "identity", {})

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_api_unavailable")

    def test_bootstrap_ide_app_uses_hidden_launch_on_macos(self):
        with patch.object(sys, "platform", "darwin"), patch_attr(jb_inspect, "ide_launcher_pids", return_value=[]), \
                patch.object(subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess(["open"], 0, "", "")
            jb_inspect.bootstrap_ide_app({"ide": "PyCharm", "worktree_root": "/tmp/worktree"}, background=True)

        run.assert_called_once_with(["open", "-g", "-j", "-a", "PyCharm"], env=ANY, check=False, capture_output=True, text=True)

    def test_bootstrap_ide_app_uses_explicit_app_for_hidden_launch(self):
        with patch.object(sys, "platform", "darwin"), patch_attr(jb_inspect, "ide_launcher_pids", return_value=[]), \
                patch.object(subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess(["open"], 0, "", "")
            jb_inspect.bootstrap_ide_app({"ide": "WebStorm", "ide_app": "WebStorm 2026.2 EAP"}, background=True)

        run.assert_called_once_with(["open", "-g", "-j", "-a", "WebStorm 2026.2 EAP"], env=ANY, check=False, capture_output=True, text=True)

    def test_bootstrap_ide_app_uses_resolved_app_path(self):
        app_path = Path("/Applications/WebStorm 2026.2 EAP.app")
        with patch.object(sys, "platform", "darwin"), patch_attr(jb_inspect, "ide_launcher_pids", return_value=[]), \
                patch.object(subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess(["open"], 0, "", "")
            jb_inspect.bootstrap_ide_app({"ide_selection": {"app_path": str(app_path), "app_name": "WebStorm 2026.2 EAP"}}, background=True)

        run.assert_called_once_with(["open", "-g", "-j", "-a", str(app_path)], env=ANY, check=False, capture_output=True, text=True)

    def test_bootstrap_ide_app_reports_failed_hidden_launch(self):
        completed = subprocess.CompletedProcess(["open"], 1, "", "Unable to find application")
        with patch.object(sys, "platform", "darwin"), patch_attr(jb_inspect, "ide_launcher_pids", return_value=[]), \
                patch.object(subprocess, "run", return_value=completed):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.bootstrap_ide_app({"ide": "PyCharm", "ide_app": "Missing PyCharm", "worktree_root": "/tmp/worktree"}, background=True)

        self.assertIn("Failed to launch", str(raised.exception))
        self.assertEqual(raised.exception.payload["command"], ["open", "-g", "-j", "-a", "Missing PyCharm"])
        self.assertIn("Unable to find application", raised.exception.payload["stderr"])

    def test_bootstrap_never_launches_or_activates_a_running_app(self):
        app_path = Path("/Applications/PyCharm.app")
        with patch.object(sys, "platform", "darwin"), patch_attr(jb_inspect, "ide_launcher_pids", return_value=[783]), \
                patch.object(subprocess, "run") as run:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.bootstrap_ide_app({"ide_selection": {"app_path": str(app_path), "app_name": "PyCharm"}}, background=True)

        run.assert_not_called()
        self.assertEqual(raised.exception.payload["error_reason"], "ide_launch_refused")
        self.assertEqual(raised.exception.payload["running_app_pids"], [783])

    def test_launch_guard_finds_the_selected_product_however_the_app_is_named(self):
        census = "\n".join([
            "  783 /Users/someone/Applications/PyCharm.app/Contents/MacOS/pycharm",
            " 1625 /Users/someone/Applications/PyCharm.app/Contents/bin/fsnotifier",
            "  819 /Applications/WebStorm.app/Contents/MacOS/webstorm",
        ])
        selections = (
            {"ide": "PyCharm"},
            {"ide": "PyCharm", "ide_app": "./Current-PyCharm.app"},
            {"ide": "PyCharm", "ide_app": "PyCharm.APP"},
            {"ide_selection": {"product_key": "pycharm", "app_path": "/Volumes/Other/Renamed.app", "app_name": "Renamed"}},
        )
        for context in selections:
            with self.subTest(context=context), patch.object(subprocess, "check_output", return_value=census), \
                    patch.object(subprocess, "run") as run:
                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.refuse_launch_while_app_runs(context)
                run.assert_not_called()
                self.assertEqual(raised.exception.payload["running_app_pids"], [783])

    def test_launch_guard_allows_launch_when_only_another_product_runs(self):
        census = "  819 /Applications/WebStorm.app/Contents/MacOS/webstorm\n 1625 /Applications/PyCharm.app/Contents/bin/fsnotifier"
        with patch.object(subprocess, "check_output", return_value=census):
            jb_inspect.refuse_launch_while_app_runs({"ide": "PyCharm"})

    def test_launch_guard_refuses_a_product_whose_launcher_it_cannot_identify(self):
        with patch.object(subprocess, "check_output", return_value="") as census, \
                patch.object(subprocess, "run") as run:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.refuse_launch_while_app_runs({"ide": "GoLand", "ide_app": "/Applications/GoLand.app"})
        census.assert_not_called()
        run.assert_not_called()
        self.assertEqual(raised.exception.payload["error_reason"], "ide_launch_refused")

    def test_unprovable_process_census_refuses_launch(self):
        with patch.object(subprocess, "check_output", side_effect=OSError("ps unavailable")), \
                patch.object(subprocess, "run") as run:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.refuse_launch_while_app_runs({"ide": "PyCharm"})

        run.assert_not_called()
        self.assertEqual(raised.exception.payload["error_reason"], "ide_launch_refused")

    def test_auto_open_timeout_payload_names_trust_and_modal_causes(self):
        args = Namespace(background_open=True)
        original_trusted = jb_inspect.trusted_auto_open_roots
        original_diagnostic = jb_inspect.discover_diagnostic_identities
        jb_inspect.trusted_auto_open_roots = lambda: ["/tmp"]
        jb_inspect.discover_diagnostic_identities = lambda port: []
        try:
            payload = jb_inspect.auto_open_timeout_payload(
                args,
                {"ide": "PyCharm", "worktree_root": "/tmp/worktree"},
                300_000,
            )
        finally:
            jb_inspect.trusted_auto_open_roots = original_trusted
            jb_inspect.discover_diagnostic_identities = original_diagnostic

        self.assertTrue(payload["background_open"])
        self.assertEqual(payload["blocked_diagnostic"]["reason"], "jetbrains_project_open_blocked")
        self.assertEqual(payload["blocked_diagnostic"]["selected_trusted_root"], str(Path("/tmp").resolve()))
        self.assertIn("JetBrains trust", payload["likely_causes"][0])
        self.assertIn("new window", " ".join(payload["likely_causes"]))

    def test_cleanup_failure_surfaces_close_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            original_private_http = jb_inspect.private_http_get_body
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            try:
                lease = jb_inspect.create_local_lease({"worktree_root": "/tmp/repo"}, "prepared")
                lease.update(
                    {
                        "opened_by_helper": True,
                        "project_instance_id": "session:1",
                        "project_key": "path:/tmp/repo",
                    }
                )

                def fake_private_http(port, endpoint, params, timeout=None):
                    self.assertEqual(timeout, 35.0)
                    raise jb_inspect.InspectError("IDE session changed", 4, {"reason": "session_drift", "session_drift": True})

                jb_inspect.private_http_get_body = fake_private_http
                result = jb_inspect.cleanup_lifecycle(lease, {"port": 63342, "project_key": "path:/tmp/repo"}, "token")
            finally:
                jb_inspect.private_http_get_body = original_private_http
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["reason"], "session_drift")
        self.assertTrue(result["cleanup_failed"])

    def test_cleanup_http_500_is_failed_not_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            original_private_http = jb_inspect.private_http_get_body
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            try:
                lease = jb_inspect.create_local_lease(
                    {
                        "worktree_root": "/tmp/repo",
                        "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    },
                    "prepared",
                )
                lease.update(
                    {
                        "opened_by_helper": True,
                        "project_instance_id": "session:1",
                        "project_key": "path:/tmp/repo",
                    }
                )
                attribution = {
                    "schema_version": 1,
                    "source": "plugin",
                    "classification": "tool_caused",
                    "code": "inspection_api_http_error",
                    "phase": "lifecycle_close",
                    "http_status": 500,
                    "request_id": "11111111-1111-4111-8111-111111111111",
                }

                def fake_private_http(port, endpoint, params, timeout=None):
                    raise jb_inspect.InspectError(
                        "HTTP 500 from inspection API",
                        3,
                        {
                            "status": "error",
                            "error_reason": "inspection_api_http_error",
                            "http_status": 500,
                            "inspection_attribution": attribution,
                        },
                    )

                jb_inspect.private_http_get_body = fake_private_http
                result = jb_inspect.cleanup_lifecycle(
                    lease,
                    {"port": 63342, "project_key": "path:/tmp/repo"},
                    "token",
                )
            finally:
                jb_inspect.private_http_get_body = original_private_http
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

        self.assertEqual(result["status"], "failed")
        self.assertTrue(result["cleanup_failed"])
        self.assertEqual(result["reason"], "inspection_api_http_error")
        self.assertEqual(result["inspection_attribution"]["request_id"], "11111111-1111-4111-8111-111111111111")

    def test_cleanup_skipped_surfaces_successful_close_response(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            original_private_http = jb_inspect.private_http_get_body
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            try:
                lease = jb_inspect.create_local_lease({"worktree_root": "/tmp/repo"}, "prepared")
                lease.update(
                    {
                        "opened_by_helper": True,
                        "project_instance_id": "session:1",
                        "project_key": "path:/tmp/repo",
                    }
                )

                def fake_private_http(port, endpoint, params, timeout=None):
                    self.assertEqual(timeout, 35.0)
                    return {"status": "skipped", "reason": "not_claimed"}

                jb_inspect.private_http_get_body = fake_private_http
                result = jb_inspect.cleanup_lifecycle(lease, {"port": 63342, "project_key": "path:/tmp/repo"}, "token")
            finally:
                jb_inspect.private_http_get_body = original_private_http
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

        self.assertEqual(result["status"], "skipped")
        self.assertEqual(result["reason"], "not_claimed")
        self.assertTrue(result["cleanup_skipped"])
        self.assertFalse(result["cleanup_failed"])

    def test_cleanup_reason_prefers_error_reason_over_status(self):
        error = jb_inspect.InspectError(
            "Timed out waiting for lifecycle close.",
            3,
            {"status": {"status": "indexing"}, "error_reason": "timeout"},
        )

        self.assertEqual(jb_inspect.public_cleanup_reason(error), "timeout")

    def test_lifecycle_lock_times_out_when_already_held(self):
        if jb_inspect.fcntl is None:
            self.skipTest("fcntl locking is unavailable on this platform")
        with tempfile.TemporaryDirectory() as tmp:
            original_cache = os.environ.get("JETBRAINS_INSPECTION_CACHE_DIR")
            os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = tmp
            holder = None
            try:
                path = jb_inspect.lifecycle_lock_path()
                path.parent.mkdir(parents=True, exist_ok=True)
                holder = path.open("a+", encoding="utf-8")
                jb_inspect.fcntl.flock(holder.fileno(), jb_inspect.fcntl.LOCK_EX | jb_inspect.fcntl.LOCK_NB)

                with self.assertRaises(jb_inspect.InspectError) as raised:
                    with jb_inspect.lifecycle_lock(1):
                        pass
            finally:
                if holder is not None:
                    jb_inspect.fcntl.flock(holder.fileno(), jb_inspect.fcntl.LOCK_UN)
                    holder.close()
                if original_cache is None:
                    os.environ.pop("JETBRAINS_INSPECTION_CACHE_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_CACHE_DIR"] = original_cache

        self.assertIn("lifecycle lock", str(raised.exception))
        self.assertEqual(raised.exception.payload["timeout_ms"], 1)

    def test_trusted_auto_open_allows_worktree_under_global_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "trusted"
            worktree = root / "repo"
            worktree.mkdir(parents=True)
            with patch_attr(jb_inspect, "trusted_auto_open_roots", return_value=[str(root)]):
                jb_inspect.ensure_trusted_auto_open_root({"worktree_root": str(worktree)})

    def test_trusted_auto_open_uses_lifecycle_target_for_nested_project(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trusted = root / "trusted-harness-runs"
            nested_project = trusted / "run" / "workspace" / "project"
            parent_repo = root / "code-prealign-new-skills"
            nested_project.mkdir(parents=True)
            parent_repo.mkdir()

            context = {
                "worktree_root": str(parent_repo),
                "project_path": str(nested_project),
                "exact_route_path": str(nested_project),
                "lifecycle_target_path": str(nested_project),
            }

            with patch_attr(jb_inspect, "trusted_auto_open_roots", return_value=[str(trusted)]):
                jb_inspect.ensure_trusted_auto_open_root(context)

    def test_trusted_auto_open_rejects_untrusted_worktree(self):
        with tempfile.TemporaryDirectory() as tmp:
            trusted = Path(tmp) / "trusted"
            worktree = Path(tmp) / "untrusted" / "repo"
            trusted.mkdir()
            worktree.mkdir(parents=True)

            with self.assertRaises(jb_inspect.InspectError) as raised:
                with patch_attr(jb_inspect, "trusted_auto_open_roots", return_value=[str(trusted)]):
                    jb_inspect.ensure_trusted_auto_open_root({"worktree_root": str(worktree)})

        self.assertIn("outside trusted auto-open roots", str(raised.exception))

    def test_ensure_jetbrains_trusted_locations_updates_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_dir = Path(tmp) / "PyCharm2026.1"
            options_dir = config_dir / "options"
            options_dir.mkdir(parents=True)
            trusted_file = options_dir / "trusted-paths.xml"
            trusted_file.write_text(
                '<application><component name="Trusted.Paths.Settings"><option name="TRUSTED_PATHS"><list /></option></component></application>',
                encoding="utf-8",
            )
            worktree = Path(tmp) / "trusted" / "repo"
            worktree.mkdir(parents=True)
            original_config = os.environ.get("JETBRAINS_INSPECTION_IDE_CONFIG_DIR")
            os.environ["JETBRAINS_INSPECTION_IDE_CONFIG_DIR"] = str(config_dir)
            try:
                with (
                    patch.object(sys, "platform", "darwin"),
                    patch_attr(jb_inspect, "trusted_auto_open_roots", return_value=[str(worktree.parent)]),
                ):
                    result = jb_inspect.ensure_jetbrains_trusted_locations({"ide": "PyCharm", "worktree_root": str(worktree)})
                updated = trusted_file.read_text(encoding="utf-8")
            finally:
                if original_config is None:
                    os.environ.pop("JETBRAINS_INSPECTION_IDE_CONFIG_DIR", None)
                else:
                    os.environ["JETBRAINS_INSPECTION_IDE_CONFIG_DIR"] = original_config

        self.assertEqual(result["status"], "trusted")
        self.assertTrue(result["config_updates"][0]["trusted_locations"]["changed"])
        self.assertIn("/trusted", updated)
        self.assertIn("Trusted.Paths.Settings", updated)
        self.assertIn("Trusted.Paths", updated)

    def test_ensure_project_opening_policy_sets_new_window_without_prompt(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_dir = Path(tmp) / "PyCharm2026.1"
            options_dir = config_dir / "options"
            options_dir.mkdir(parents=True)
            general_file = options_dir / "ide.general.xml"
            general_file.write_text(
                '<application><component name="GeneralSettings"><option name="confirmOpenNewProject2" value="0" /></component></application>',
                encoding="utf-8",
            )

            result = jb_inspect.ensure_project_opening_policy(config_dir)
            updated = general_file.read_text(encoding="utf-8")

        self.assertTrue(result["changed"])
        self.assertIn('name="confirmOpenNewProject2" value="-1"', updated)

    def test_open_via_running_ide_calls_matching_lifecycle_open(self):
        calls = []
        original_discover = jb_inspect.discover_identities
        original_http_get = jb_inspect.http_get
        jb_inspect.discover_identities = lambda port: [
            {
                "port": 63341,
                "ide_name": "IntelliJ IDEA",
                "session_id": "s1",
                "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
            }
        ]

        def fake_http_get(port, endpoint, params, timeout=jb_inspect.DEFAULT_TIMEOUT_SECONDS):
            calls.append((port, endpoint, params))
            return jb_inspect.HttpResult(
                200,
                {
                    "status": "opening",
                    "opening_scheduled": True,
                    "opened": False,
                    "session_id": "s1",
                    "ownership_registered": True,
                    "lease_id": "lease-1",
                    "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                },
                "url",
            )

        jb_inspect.http_get = fake_http_get
        attempts = []
        try:
            with patch_attr(jb_inspect, "persist_open_request_identity"):
                result = jb_inspect.open_via_running_ide(
                    Namespace(port=None),
                    {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree", "project_path": "/tmp/worktree"},
                    attempts,
                    lease={"lease_id": "lease-1"},
                )
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.http_get = original_http_get

        self.assertTrue(result["ownership_registered"])
        self.assertEqual(result["open_outcome"], "helper_registered")
        self.assertEqual(calls[0][1], "lifecycle/open")
        self.assertEqual(calls[0][2]["worktree_path"], "/tmp/worktree")
        self.assertEqual(calls[0][2]["session_id"], "s1")
        self.assertEqual(calls[0][2]["lease_id"], "lease-1")
        self.assertEqual(attempts[0]["endpoint_status"], "opening")

    def test_open_via_running_ide_persists_identity_before_http_request(self):
        identity = {
            "port": 63341,
            "ide_name": "IntelliJ IDEA",
            "session_id": "s1",
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
        }
        response = {
            "status": "opening",
            "opening_scheduled": True,
            "session_id": "s1",
            "ownership_registered": True,
            "lease_id": "lease-1",
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
        }
        events = []
        lease = {"lease_id": "lease-1", "state": "open_requesting"}

        with (
            patch_attr(jb_inspect, "discover_open_identities", return_value=[identity]),
            patch_attr(
                jb_inspect,
                "persist_open_request_identity",
                side_effect=lambda *args: events.append("persist"),
            ),
            patch_attr(
                jb_inspect,
                "http_get",
                side_effect=lambda port, endpoint, params, timeout: events.append(("http", params["lease_id"]))
                or jb_inspect.HttpResult(200, response, "url"),
            ),
        ):
            result = jb_inspect.open_via_running_ide(
                Namespace(port=None),
                {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"},
                lease=lease,
            )

        self.assertEqual(events, ["persist", ("http", "lease-1")])
        self.assertTrue(result["ownership_registered"])

    def test_open_via_running_ide_treats_response_timeout_as_ambiguous_acceptance(self):
        identity = {
            "port": 63341,
            "ide_name": "IntelliJ IDEA",
            "session_id": "s1",
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
        }
        lease = {"lease_id": "lease-1", "state": "open_requesting"}
        states = []

        with (
            patch_attr(jb_inspect, "discover_open_identities", return_value=[identity]),
            patch_attr(jb_inspect, "persist_open_request_identity"),
            patch_attr(
                jb_inspect,
                "http_get",
                side_effect=jb_inspect.InspectError(
                    "Inspection API timed out on port 63341: timed out",
                    3,
                    {"error_reason": "inspection_api_timeout"},
                ),
            ),
            patch_attr(jb_inspect, "mark_lease_state", side_effect=lambda active_lease, state: states.append((active_lease, state))),
        ):
            attempts = []
            result = jb_inspect.open_via_running_ide(
                Namespace(port=None),
                {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"},
                attempts=attempts,
                lease=lease,
            )

        self.assertEqual(result["open_outcome"], "response_unknown")
        self.assertTrue(result["request_may_have_been_accepted"])
        self.assertFalse(result["ownership_registered"])
        self.assertEqual(lease["open_attempts"], attempts)
        self.assertTrue(lease["open_request_may_have_been_accepted"])
        self.assertEqual(states[-1][1], "open_requesting")

    def test_ambiguous_open_evidence_survives_route_wait_failure(self):
        lease = {"lease_id": "lease-1", "state": "open_requesting", "opened_by_helper": False}
        attempts = [
            {
                "method": "running_ide",
                "accepted": False,
                "open_outcome": "response_unknown",
                "request_may_have_been_accepted": True,
                "ownership_registered": False,
            }
        ]

        with patch_attr(
            jb_inspect,
            "mark_lease_state",
            side_effect=lambda active_lease, state: active_lease.update({"state": state}),
        ):
            jb_inspect.persist_preparation_lease(
                lease,
                state="open_requesting",
                stage="route_wait",
                opened_by_helper=False,
                open_method="running_ide",
                open_attempts=attempts,
            )

        self.assertTrue(lease["open_request_may_have_been_accepted"])
        with (
            patch_attr(jb_inspect, "cleanup_lifecycle") as cleanup,
            patch_attr(
                jb_inspect,
                "defer_failed_preparation_cleanup",
                return_value={"status": "deferred", "reason": "ownership_unresolved"},
            ) as defer_cleanup,
        ):
            result = jb_inspect.cleanup_failed_preparation(
                lease,
                None,
                None,
                jb_inspect.InspectError("route wait timed out", 3, {"error_reason": "project_open_blocked"}),
                "route_wait",
            )

        cleanup.assert_not_called()
        defer_cleanup.assert_called_once()
        self.assertEqual(result["status"], "deferred")

    def test_definitive_not_owned_claim_clears_ambiguous_open_evidence(self):
        lease = {
            "lease_id": "lease-1",
            "state": "route_resolved",
            "opened_by_helper": False,
            "open_request_may_have_been_accepted": True,
        }
        attempts = [{"request_may_have_been_accepted": True}]

        with patch_attr(
            jb_inspect,
            "mark_lease_state",
            side_effect=lambda active_lease, state: active_lease.update({"state": state}),
        ):
            jb_inspect.persist_preparation_lease(
                lease,
                state="ownership_not_proven",
                stage="readiness_wait",
                opened_by_helper=False,
                open_method="running_ide",
                open_attempts=attempts,
                claim_metadata={"ownership_proven": False, "ownership_determined": True},
            )

        self.assertFalse(lease["open_request_may_have_been_accepted"])
        self.assertFalse(jb_inspect.lease_may_own_open_project(lease))

    def test_lifecycle_claim_ownership_ignores_legacy_close_token(self):
        claim = {"status": "claimed", "lease_id": "lease-1", "close_token": "legacy-proof"}

        ownership_proven, close_proof = jb_inspect.lifecycle_claim_ownership(
            claim,
            {"lease_id": "lease-1"},
        )

        self.assertIsNone(ownership_proven)
        self.assertIsNone(close_proof)
        self.assertNotIn("close_token", claim)

    def test_open_via_running_ide_does_not_claim_already_open_or_opening(self):
        original_discover = jb_inspect.discover_identities
        original_http_get = jb_inspect.http_get
        jb_inspect.discover_identities = lambda port: [
            {"port": 63341, "ide_name": "IntelliJ IDEA", "session_id": "s1"}
        ]
        responses = (
            ({"status": "already_open", "opened": False, "session_id": "s1"}, "already_open"),
            (
                {
                    "status": "opening",
                    "opened": False,
                    "opening_scheduled": False,
                    "reason": "already_opening",
                    "session_id": "s1",
                },
                "already_opening",
            ),
        )
        try:
            for body, outcome in responses:
                with self.subTest(outcome=outcome):
                    jb_inspect.http_get = lambda *args, response_body=body, **kwargs: jb_inspect.HttpResult(
                        200,
                        response_body,
                        "url",
                    )
                    result = jb_inspect.open_via_running_ide(
                        Namespace(port=None),
                        {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"},
                    )

                    self.assertEqual(result["open_outcome"], outcome)
                    self.assertFalse(result["ownership_registered"])
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.http_get = original_http_get

    def test_route_wait_retries_stale_already_opening_request(self):
        attempts = [{"open_outcome": "already_opening"}]
        route = {"project_key": "path:/tmp/worktree", "worktree_path": "/tmp/worktree"}
        retry_results = [
            {"open_outcome": "already_opening"},
            {
                "open_outcome": "helper_registered",
                "ownership_registered": True,
            },
        ]
        routes = [None, None, route]

        with (
            patch_attr(jb_inspect, "find_exact_route", side_effect=lambda *_: routes.pop(0)),
            patch_attr(
                jb_inspect,
                "open_via_running_ide",
                side_effect=lambda *_args, **_kwargs: retry_results.pop(0),
            ) as retry,
            patch.object(time, "sleep"),
        ):
            result = jb_inspect.wait_for_exact_route_after_open(
                Namespace(),
                {"worktree_root": "/tmp/worktree"},
                10_000,
                attempts,
                {"lease_id": "lease-1"},
            )

        self.assertEqual(result, route)
        self.assertEqual(retry.call_count, 2)

    def test_route_wait_fails_quickly_when_already_opening_never_clears(self):
        attempts = [{"open_outcome": "already_opening"}]
        with (
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(
                jb_inspect,
                "open_via_running_ide",
                return_value={"open_outcome": "already_opening"},
            ) as retry,
            patch.object(time, "sleep"),
            self.assertRaises(jb_inspect.InspectError) as raised,
        ):
            jb_inspect.wait_for_exact_route_after_open(
                Namespace(),
                {"worktree_root": "/tmp/worktree"},
                600_000,
                attempts,
                {"lease_id": "lease-1"},
            )

        self.assertEqual(raised.exception.payload["error_reason"], "already_opening_stuck")
        self.assertEqual(retry.call_count, jb_inspect.ALREADY_OPENING_RETRY_LIMIT)
        self.assertIn("restart", jb_inspect.next_action_for_unknown("already_opening_stuck", {}))

    def test_identity_matches_exact_eap_version_from_identity_metadata(self):
        context = {
            "ide": "WebStorm",
            "ide_selection": {
                "product_key": "webstorm",
                "exact": True,
                "channel": "eap",
                "version": "2026.2",
            },
        }
        identity = {"ide_name": "WebStorm", "ide_product_code": "WS", "ide_version": "2026.2 EAP"}

        self.assertTrue(jb_inspect.identity_matches_context(identity, context))

    def test_identity_rejects_exact_eap_when_running_identity_is_stable(self):
        context = {
            "ide": "WebStorm",
            "ide_selection": {
                "product_key": "webstorm",
                "exact": True,
                "channel": "eap",
                "version": "2026.2",
            },
        }
        identity = {"ide_name": "WebStorm", "ide_product_code": "WS", "ide_version": "2026.2"}

        self.assertFalse(jb_inspect.identity_matches_context(identity, context))

    def test_identity_rejects_exact_version_mismatch(self):
        context = {
            "ide": "WebStorm",
            "ide_selection": {
                "product_key": "webstorm",
                "exact": True,
                "channel": "stable",
                "version": "2026.1",
            },
        }
        identity = {"ide_name": "WebStorm", "ide_product_code": "WS", "ide_version": "2026.2"}

        self.assertFalse(jb_inspect.identity_matches_context(identity, context))

    def test_open_via_running_ide_sends_lifecycle_target_path(self):
        calls = []
        original_discover = jb_inspect.discover_identities
        original_http_get = jb_inspect.http_get
        jb_inspect.discover_identities = lambda port: [
            {
                "port": 63341,
                "ide_name": "IntelliJ IDEA",
                "session_id": "s1",
                "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
            }
        ]

        def fake_http_get(port, endpoint, params, timeout=jb_inspect.DEFAULT_TIMEOUT_SECONDS):
            calls.append((port, endpoint, params))
            return jb_inspect.HttpResult(
                200,
                {
                    "status": "opening",
                    "opening_scheduled": True,
                    "opened": False,
                    "session_id": "s1",
                    "ownership_registered": True,
                    "lease_id": "lease-1",
                    "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
                },
                "url",
            )

        jb_inspect.http_get = fake_http_get
        try:
            with patch_attr(jb_inspect, "persist_open_request_identity"):
                result = jb_inspect.open_via_running_ide(
                    Namespace(port=None),
                    {
                        "ide": "IntelliJ IDEA",
                        "worktree_root": "/tmp/harness-parent",
                        "project_path": "/tmp/harness-parent/workspace/project",
                        "lifecycle_target_path": "/tmp/harness-parent/workspace/project",
                    },
                    lease={"lease_id": "lease-1"},
                )
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.http_get = original_http_get

        self.assertTrue(result["ownership_registered"])
        self.assertEqual(calls[0][1], "lifecycle/open")
        self.assertEqual(calls[0][2]["worktree_path"], "/tmp/harness-parent/workspace/project")

    def test_open_via_running_ide_ignores_other_ide_products(self):
        original_discover = jb_inspect.discover_identities
        original_diagnostic = jb_inspect.discover_diagnostic_identities
        original_http_get = jb_inspect.http_get
        jb_inspect.discover_identities = lambda port: [{"port": 63341, "ide_name": "WebStorm", "session_id": "s1"}]
        jb_inspect.discover_diagnostic_identities = lambda port: [{"port": 63341, "ide_name": "WebStorm", "session_id": "s1"}]

        def fake_http_get(port, endpoint, params, timeout=jb_inspect.DEFAULT_TIMEOUT_SECONDS):
            if endpoint == "lifecycle/open":
                raise AssertionError("should not call lifecycle/open for the wrong product")
            return jb_inspect.HttpResult(200, {"status": "ok"}, "url")

        jb_inspect.http_get = fake_http_get
        try:
            result = jb_inspect.open_via_running_ide(Namespace(port=None), {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"})
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.discover_diagnostic_identities = original_diagnostic
            jb_inspect.http_get = original_http_get

        self.assertFalse(result)

    def test_wait_for_matching_ide_identity_returns_target_product(self):
        original_discover = jb_inspect.discover_identities
        original_sleep = jb_inspect.time.sleep
        jb_inspect.discover_identities = lambda port: [
            {"port": 63341, "ide_name": "WebStorm", "session_id": "s1"},
            {"port": 63342, "ide_name": "PyCharm", "session_id": "s2"},
        ]
        jb_inspect.time.sleep = lambda seconds: None
        try:
            with patch_attr(jb_inspect, "now_ms", side_effect=[0, 0, 200]):
                result = jb_inspect.wait_for_matching_ide_identity(Namespace(port=None, background_open=True), {"ide": "PyCharm"}, 100)
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.time.sleep = original_sleep

        self.assertEqual(result["session_id"], "s2")

    def test_wait_for_matching_ide_identity_uses_port_scan_when_registry_misses_target(self):
        original_discover = jb_inspect.discover_identities
        original_diagnostic = jb_inspect.discover_diagnostic_identities
        original_sleep = jb_inspect.time.sleep
        jb_inspect.discover_identities = lambda port: [
            {"port": 63342, "ide_name": "IntelliJ IDEA", "session_id": "idea-session"},
        ]
        jb_inspect.discover_diagnostic_identities = lambda port: [
            {"port": 63342, "ide_name": "IntelliJ IDEA", "session_id": "idea-session"},
            {"port": 63344, "ide_name": "PyCharm", "session_id": "py-session", "open_projects": []},
        ]
        jb_inspect.time.sleep = lambda seconds: None
        try:
            with patch_attr(jb_inspect, "now_ms", side_effect=[0, 0, 200]):
                result = jb_inspect.wait_for_matching_ide_identity(Namespace(port=None, background_open=True), {"ide": "PyCharm"}, 100)
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.discover_diagnostic_identities = original_diagnostic
            jb_inspect.time.sleep = original_sleep

        self.assertEqual(result["session_id"], "py-session")

    def test_wait_for_matching_ide_identity_classifies_missing_plugin(self):
        args = Namespace(port=None, background_open=True)
        with (
            patch_attr(jb_inspect, "discover_open_identities", return_value=[]),
            patch_attr(jb_inspect, "now_ms", side_effect=[0, 200]),
            patch.object(time, "sleep"),
            patch_attr(jb_inspect, "auto_open_timeout_payload", return_value={}),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.wait_for_matching_ide_identity(args, {"ide": "PyCharm"}, 100)

        self.assertEqual(raised.exception.payload["error_reason"], "inspection_api_unavailable")
        self.assertEqual(raised.exception.payload["failure_phase"], "bootstrap_identity")

    def test_list_reports_zero_project_prompt_hint_for_discovered_identity(self):
        original_discover = jb_inspect.discover_identities
        jb_inspect.discover_identities = lambda port: [
            {
                "port": 63344,
                "ide_name": "PyCharm 2026.1.2",
                "ide_product_code": "PY",
                "session_id": "py-session",
                "open_projects": [],
            }
        ]
        try:
            result = jb_inspect.command_list(Namespace(port=None))
        finally:
            jb_inspect.discover_identities = original_discover

        self.assertEqual(result["count"], 0)
        self.assertIn("zero_project_hint", result)
        self.assertIn("Trust Project", result["zero_project_hint"])
        self.assertIn("safe-mode", result["zero_project_hint"])
        self.assertIn("open-project", result["zero_project_hint"])
        self.assertEqual(result["identities"][0]["open_project_count"], 0)

    def test_list_omits_zero_project_prompt_hint_without_identity(self):
        original_discover = jb_inspect.discover_identities
        jb_inspect.discover_identities = lambda port: []
        try:
            result = jb_inspect.command_list(Namespace(port=None))
        finally:
            jb_inspect.discover_identities = original_discover

        self.assertEqual(result["count"], 0)
        self.assertNotIn("zero_project_hint", result)

    def test_route_diagnostic_reports_other_ide_projects(self):
        original_discover = jb_inspect.discover_diagnostic_identities
        jb_inspect.discover_diagnostic_identities = lambda port: [
            {
                "port": 63341,
                "ide_name": "IntelliJ IDEA 2026.1.2",
                "ide_product_code": "IU",
                "plugin_version": "1.12.10",
                "session_id": "idea-session",
                "open_projects": [
                    {
                        "name": "jetbrains-inspection-api",
                        "project_key": "path:/Users/me/Developer/jetbrains-inspection-api",
                        "base_path": "/Users/me/Developer/jetbrains-inspection-api",
                    }
                ],
            }
        ]
        try:
            payload = jb_inspect.route_diagnostic_payload(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/Users/me/Developer/codex-skills", "project_path": "/Users/me/Developer/codex-skills"},
            )
        finally:
            jb_inspect.discover_diagnostic_identities = original_discover

        diagnostic = payload["route_diagnostic"]
        self.assertEqual(diagnostic["requested_ide"], "PyCharm")
        self.assertEqual(diagnostic["discovered_identity_count"], 1)
        self.assertEqual(diagnostic["matching_identity_count"], 0)
        self.assertEqual(diagnostic["discovered_project_count"], 1)
        self.assertEqual(diagnostic["matching_project_count"], 0)
        self.assertEqual(diagnostic["reason"], "different_jetbrains_product_running")
        self.assertEqual(diagnostic["other_projects"][0]["ide_product_code"], "IU")
        self.assertEqual(diagnostic["other_projects"][0]["plugin_version"], "1.12.10")
        self.assertIn("PyCharm", diagnostic["next_action"])
        self.assertIn("plugin installed and up to date", diagnostic["next_action"])

    def test_discover_identities_merges_registry_and_port_scan(self):
        original_registry = jb_inspect.registry_identities
        original_ports = jb_inspect.configured_ports
        original_identity = jb_inspect.identity_for_port
        jb_inspect.registry_identities = lambda: [
            {
                "port": 63342,
                "ide_name": "WebStorm",
                "session_id": "webstorm-session",
                "open_projects": [{"base_path": "/repo/webstorm"}],
            }
        ]
        jb_inspect.configured_ports = lambda: [63342, 63345]

        def fake_identity_for_port(port):
            if port == 63342:
                return {"port": 63342, "ide_name": "WebStorm", "session_id": "webstorm-session"}
            return {"port": 63345, "ide_name": "IntelliJ IDEA", "session_id": "idea-session"}

        jb_inspect.identity_for_port = fake_identity_for_port
        try:
            identities = jb_inspect.discover_identities(None)
        finally:
            jb_inspect.registry_identities = original_registry
            jb_inspect.configured_ports = original_ports
            jb_inspect.identity_for_port = original_identity

        sessions = {identity["session_id"] for identity in identities}
        self.assertEqual(sessions, {"webstorm-session", "idea-session"})
        self.assertEqual(len(identities), 2)
        webstorm = next(identity for identity in identities if identity["session_id"] == "webstorm-session")
        self.assertEqual(webstorm["open_projects"], [{"base_path": "/repo/webstorm"}])

    def test_discover_identities_with_explicit_port_does_not_scan_registry(self):
        original_registry = jb_inspect.registry_identities
        original_ports = jb_inspect.configured_ports
        original_identity = jb_inspect.identity_for_port
        calls = []
        jb_inspect.registry_identities = lambda: (_ for _ in ()).throw(AssertionError("registry should not be read"))
        jb_inspect.configured_ports = lambda: (_ for _ in ()).throw(AssertionError("ports should not be scanned"))

        def fake_identity_for_port(port):
            calls.append(port)
            return {"port": port, "ide_name": "IntelliJ IDEA", "session_id": "idea-session"}

        jb_inspect.identity_for_port = fake_identity_for_port
        try:
            identities = jb_inspect.discover_identities(63345)
        finally:
            jb_inspect.registry_identities = original_registry
            jb_inspect.configured_ports = original_ports
            jb_inspect.identity_for_port = original_identity

        self.assertEqual(calls, [63345])
        self.assertEqual(identities[0]["session_id"], "idea-session")

    def test_identity_for_port_rejects_mismatched_reported_port(self):
        original_http_get = jb_inspect.http_get
        jb_inspect.http_get = lambda port, endpoint, params: jb_inspect.HttpResult(
            200,
            {"port": 63342, "ide_name": "WebStorm", "session_id": "webstorm-session"},
            "url",
        )
        try:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.identity_for_port(63345)
        finally:
            jb_inspect.http_get = original_http_get

        self.assertEqual(raised.exception.payload["error_reason"], "identity_port_mismatch")
        self.assertEqual(raised.exception.payload["requested_port"], 63345)
        self.assertEqual(raised.exception.payload["reported_port"], 63342)

    def test_identity_for_port_rejects_invalid_reported_port(self):
        original_http_get = jb_inspect.http_get
        jb_inspect.http_get = lambda port, endpoint, params: jb_inspect.HttpResult(
            200,
            {"port": "not-a-port", "ide_name": "WebStorm", "session_id": "webstorm-session"},
            "url",
        )
        try:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.identity_for_port(63345)
        finally:
            jb_inspect.http_get = original_http_get

        self.assertEqual(raised.exception.payload["error_reason"], "invalid_identity_port")
        self.assertEqual(raised.exception.payload["requested_port"], 63345)
        self.assertEqual(raised.exception.payload["reported_port"], "not-a-port")

    def test_route_diagnostic_merges_registry_and_port_scan(self):
        original_registry = jb_inspect.registry_identities
        original_ports = jb_inspect.configured_ports
        original_identity = jb_inspect.identity_for_port
        jb_inspect.registry_identities = lambda: [
            {
                "port": 63342,
                "ide_name": "IntelliJ IDEA 2026.1.2",
                "ide_product_code": "IU",
                "session_id": "idea-session",
                "open_projects": [],
            }
        ]
        jb_inspect.configured_ports = lambda: [63342, 63344]

        def fake_identity_for_port(port):
            if port == 63342:
                return {
                    "port": 63342,
                    "ide_name": "IntelliJ IDEA 2026.1.2",
                    "ide_product_code": "IU",
                    "session_id": "idea-session",
                    "open_projects": [],
                }
            return {
                "port": 63344,
                "ide_name": "PyCharm 2026.1.2",
                "ide_product_code": "PY",
                "session_id": "py-session",
                "open_projects": [],
            }

        jb_inspect.identity_for_port = fake_identity_for_port
        try:
            payload = jb_inspect.route_diagnostic_payload(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/Users/me/Developer/mediaforce", "project_path": "/Users/me/Developer/mediaforce"},
            )
        finally:
            jb_inspect.registry_identities = original_registry
            jb_inspect.configured_ports = original_ports
            jb_inspect.identity_for_port = original_identity

        diagnostic = payload["route_diagnostic"]
        self.assertEqual(diagnostic["discovered_identity_count"], 2)
        self.assertEqual(diagnostic["matching_identity_count"], 1)
        self.assertEqual(diagnostic["matching_project_count"], 0)
        self.assertEqual(diagnostic["reason"], "target_ide_running_without_target_project")
        self.assertIn("exact worktree", diagnostic["next_action"])
        self.assertIn("Trust Project", diagnostic["next_action"])
        self.assertIn("safe-mode", diagnostic["next_action"])
        self.assertIn("open-project", diagnostic["next_action"])

    def test_resolve_route_ignores_invalid_route_payloads(self):
        original_discover = jb_inspect.discover_identities
        original_http_get = jb_inspect.http_get
        original_diagnostic = jb_inspect.discover_diagnostic_identities
        target = "/Users/me/Developer/project"
        response_body = {}
        jb_inspect.discover_identities = lambda port: [
            {
                "port": 63342,
                "ide_name": "IntelliJ IDEA 2026.1.3",
                "ide_product_code": "IU",
                "session_id": "idea-session",
                "open_projects": [{"base_path": target, "project_key": f"path:{target}"}],
            }
        ]
        jb_inspect.discover_diagnostic_identities = jb_inspect.discover_identities

        def fake_http_get(port, endpoint, params, timeout=3.0):
            self.assertEqual(endpoint, "route")
            return jb_inspect.HttpResult(200, response_body, "url")

        jb_inspect.http_get = fake_http_get
        try:
            for invalid_route in (None, "not-an-object", {}):
                with self.subTest(route=invalid_route):
                    response_body["route"] = invalid_route
                    with self.assertRaises(jb_inspect.InspectError) as raised:
                        jb_inspect.resolve_route(
                            helper_args(port=None, open=False, no_worktree_check=False),
                            {"ide": "IntelliJ IDEA", "worktree_root": target, "project_path": target},
                        )
                    self.assertEqual(raised.exception.payload["error_reason"], "matching_project_route_unavailable")
                    self.assertEqual(raised.exception.payload["route_diagnostic"]["matching_project_count"], 1)
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.http_get = original_http_get
            jb_inspect.discover_diagnostic_identities = original_diagnostic

    def test_resolve_route_port_scan_finds_target_when_registry_has_other_ide(self):
        original_registry = jb_inspect.registry_identities
        original_ports = jb_inspect.configured_ports
        original_identity = jb_inspect.identity_for_port
        original_http_get = jb_inspect.http_get
        target = "/Users/me/Developer/project/worktrees/feature-odoo"
        calls = []
        jb_inspect.registry_identities = lambda: [
            {
                "port": 63342,
                "ide_name": "WebStorm 2026.1.3",
                "ide_product_code": "WS",
                "session_id": "webstorm-session",
                "open_projects": [
                    {
                        "project_key": "path:/Users/me/Developer/project",
                        "base_path": "/Users/me/Developer/project",
                    }
                ],
            }
        ]
        jb_inspect.configured_ports = lambda: [63342, 63345]

        def fake_identity_for_port(port):
            if port == 63342:
                return {
                    "port": 63342,
                    "ide_name": "WebStorm 2026.1.3",
                    "ide_product_code": "WS",
                    "session_id": "webstorm-session",
                    "open_projects": [],
                }
            return {
                "port": 63345,
                "ide_name": "IntelliJ IDEA 2026.2 EAP",
                "ide_product_code": "IU",
                "session_id": "idea-session",
                "open_projects": [],
            }

        def fake_http_get(port, endpoint, params, timeout=jb_inspect.DEFAULT_TIMEOUT_SECONDS):
            calls.append((port, endpoint, params))
            if endpoint != "route":
                raise AssertionError(f"unexpected endpoint: {endpoint}")
            if port == 63342:
                return jb_inspect.HttpResult(200, {"status": "missing", "route": None}, "webstorm-route")
            return jb_inspect.HttpResult(
                200,
                {
                    "status": "resolved",
                    "route": {
                        "port": 63345,
                        "project_key": f"path:{target}",
                        "base_path": target,
                        "session_id": "idea-session",
                        "ide": {"name": "IntelliJ IDEA 2026.2 EAP"},
                    },
                },
                "idea-route",
            )

        jb_inspect.identity_for_port = fake_identity_for_port
        jb_inspect.http_get = fake_http_get
        try:
            route = jb_inspect.resolve_route(
                Namespace(
                    port=None,
                    open=False,
                    project_key=f"path:{target}",
                    project_path=target,
                    worktree_path=target,
                    cwd=target,
                    project=None,
                    ide="IntelliJ IDEA",
                    session_id="idea-session",
                    no_worktree_check=False,
                ),
                {
                    "ide": "IntelliJ IDEA",
                    "worktree_root": target,
                    "project_path": target,
                    "exact_route_path": target,
                    "worktree_strategy": "prefer-current",
                },
            )
        finally:
            jb_inspect.registry_identities = original_registry
            jb_inspect.configured_ports = original_ports
            jb_inspect.identity_for_port = original_identity
            jb_inspect.http_get = original_http_get

        self.assertEqual(route["port"], 63345)
        self.assertEqual(route["base_path"], target)
        self.assertIn((63345, "route"), [(port, endpoint) for port, endpoint, _ in calls])

    def test_resolve_route_skips_wrong_channel_identity_for_exact_selection(self):
        original_discover = jb_inspect.discover_identities
        original_http_get = jb_inspect.http_get
        target = "/Users/me/Developer/project"
        calls = []
        jb_inspect.discover_identities = lambda port: [
            {
                "port": 63342,
                "ide_name": "WebStorm",
                "ide_product_code": "WS",
                "ide_version": "2026.1",
                "session_id": "stable-session",
            },
            {
                "port": 63344,
                "ide_name": "WebStorm",
                "ide_product_code": "WS",
                "ide_version": "2026.2 EAP",
                "session_id": "eap-session",
            },
        ]

        def fake_http_get(port, endpoint, params, timeout=jb_inspect.DEFAULT_TIMEOUT_SECONDS):
            calls.append((port, endpoint, params))
            if port == 63342:
                raise AssertionError("stable IDE should not be queried for exact EAP selection")
            return jb_inspect.HttpResult(
                200,
                {
                    "status": "resolved",
                    "route": {
                        "port": 63344,
                        "project_key": f"path:{target}",
                        "base_path": target,
                        "session_id": "eap-session",
                        "ide": {"name": "WebStorm 2026.2 EAP"},
                    },
                },
                "eap-route",
            )

        jb_inspect.http_get = fake_http_get
        try:
            route = jb_inspect.resolve_route(
                Namespace(
                    port=None,
                    open=False,
                    project_key=f"path:{target}",
                    project_path=target,
                    worktree_path=target,
                    cwd=target,
                    project=None,
                    ide="WebStorm",
                    session_id="eap-session",
                    no_worktree_check=False,
                ),
                {
                    "ide": "WebStorm",
                    "ide_selection": {
                        "product_key": "webstorm",
                        "exact": True,
                        "channel": "eap",
                        "version": "2026.2",
                    },
                    "worktree_root": target,
                    "project_path": target,
                    "exact_route_path": target,
                    "worktree_strategy": "prefer-current",
                },
            )
        finally:
            jb_inspect.discover_identities = original_discover
            jb_inspect.http_get = original_http_get

        self.assertEqual(route["port"], 63344)
        self.assertEqual([call[0] for call in calls], [63344])

    def test_route_diagnostic_for_no_instances_mentions_hidden_prompt_as_secondary_cause(self):
        original_discover = jb_inspect.discover_diagnostic_identities
        jb_inspect.discover_diagnostic_identities = lambda port: []
        try:
            payload = jb_inspect.route_diagnostic_payload(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/Users/me/Developer/mediaforce", "project_path": "/Users/me/Developer/mediaforce"},
            )
        finally:
            jb_inspect.discover_diagnostic_identities = original_discover

        diagnostic = payload["route_diagnostic"]
        self.assertEqual(diagnostic["reason"], "no_plugin_instances_discovered")
        self.assertTrue(diagnostic["next_action"].startswith("Launch the configured JetBrains IDE with the inspection plugin installed"))
        self.assertIn("Trust Project", diagnostic["next_action"])
        self.assertIn("safe-mode", diagnostic["next_action"])
        self.assertIn("open-project", diagnostic["next_action"])

    def test_open_project_for_lifecycle_uses_running_ide_without_bootstrap(self):
        calls = []
        original_running = jb_inspect.open_via_running_ide
        original_bootstrap = jb_inspect.bootstrap_ide_app
        original_wait = jb_inspect.wait_for_matching_ide_identity
        def fake_running(args, context, attempts=None, method="running_ide", lease=None):
            calls.append(("running", method))
            attempt = {"method": method, "accepted": True, "ownership_registered": True}
            if attempts is not None:
                attempts.append(attempt)
            return attempt

        jb_inspect.open_via_running_ide = fake_running
        jb_inspect.bootstrap_ide_app = lambda *args, **kwargs: calls.append("bootstrap")
        jb_inspect.wait_for_matching_ide_identity = lambda *args, **kwargs: calls.append("wait")
        try:
            result = jb_inspect.open_project_for_lifecycle(Namespace(port=None, background_open=True, no_ide_launch=True), {"ide": "IntelliJ IDEA"})
        finally:
            jb_inspect.open_via_running_ide = original_running
            jb_inspect.bootstrap_ide_app = original_bootstrap
            jb_inspect.wait_for_matching_ide_identity = original_wait

        self.assertEqual(result[0], "running_ide")
        self.assertEqual(result[1], [{"method": "running_ide", "accepted": True, "ownership_registered": True}])
        self.assertTrue(result[2])
        self.assertEqual(calls, [("running", "running_ide")])

    def test_lifecycle_open_never_launches_an_app_unless_asked(self):
        attempt = {"method": "running_ide", "accepted": False, "reason": "unavailable"}

        def running(args, context, attempts=None, method="running_ide", lease=None):
            attempts.append(attempt)
            return None

        for launch_flags in ([], ["--no-ide-launch"]):
            with self.subTest(launch_flags=launch_flags):
                args = jb_inspect.build_parser().parse_args(["inspect-closeout", "--port", "63486", *launch_flags])
                with (
                    patch_attr(jb_inspect, "open_via_running_ide", side_effect=running),
                    patch_attr(jb_inspect, "bootstrap_ide_app") as bootstrap,
                    patch_attr(jb_inspect, "wait_for_matching_ide_identity") as wait,
                    patch_attr(jb_inspect, "wait_for_lifecycle_open", return_value=None),
                ):
                    with self.assertRaises(jb_inspect.InspectError) as raised:
                        jb_inspect.open_project_for_lifecycle(args, {"ide": "PyCharm"})

                self.assertEqual(raised.exception.payload["error_reason"], "ide_launch_disabled")
                self.assertEqual(raised.exception.payload["open_attempts"], [attempt])
                payload = jb_inspect.apply_verdict(jb_inspect.error_payload(raised.exception))
                self.assertEqual(payload["agent_result"]["bucket"], "environment_blocked")
                self.assertFalse(payload["retry_policy"]["retry"])
                bootstrap.assert_not_called()
                wait.assert_not_called()

    def test_requested_launch_refused_for_running_app_sends_no_open(self):
        args = jb_inspect.build_parser().parse_args(["inspect-closeout", "--port", "63486", "--ide-launch"])
        attempt = {"method": "running_ide", "accepted": False, "reason": "no_matching_running_ide"}
        lease = {"lease_id": "owned"}

        def running(args, context, attempts=None, method="running_ide", lease=None):
            attempts.append(attempt)
            return None

        with (
            patch.object(sys, "platform", "darwin"),
            patch_attr(jb_inspect, "open_via_running_ide", side_effect=running),
            patch_attr(jb_inspect, "ide_launcher_pids", return_value=[783]),
            patch.object(subprocess, "run") as run,
            patch_attr(jb_inspect, "wait_for_matching_ide_identity") as wait,
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.open_project_for_lifecycle(args, {"ide": "PyCharm"}, lease)

        run.assert_not_called()
        wait.assert_not_called()
        self.assertEqual(raised.exception.payload["open_attempts"], [attempt])
        self.assertTrue(lease["open_not_attempted"])
        payload = jb_inspect.apply_verdict(jb_inspect.error_payload(raised.exception))
        self.assertEqual(payload["agent_result"]["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])

    def test_no_ide_launch_releases_preparation_lease_when_no_open_was_sent(self):
        lease = {"lease_id": "owned", "state": "preparing"}
        with (
            patch_attr(jb_inspect, "run_repository_preparation", return_value={}),
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "ensure_trusted_auto_open_root"),
            patch_attr(jb_inspect, "ensure_jetbrains_trusted_locations"),
            patch_attr(jb_inspect, "discover_open_identities", return_value=[]),
            patch_attr(jb_inspect, "write_lease"),
            patch_attr(jb_inspect, "remove_lease") as remove,
            patch_attr(jb_inspect, "bootstrap_ide_app") as bootstrap,
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.prepare_lifecycle_details(helper_args(open=True, no_ide_launch=True), {"worktree_root": "/fixture"})
        self.assertEqual(raised.exception.payload["cleanup"], {"status": "not_needed", "reason": "open_not_attempted"})
        remove.assert_called_once_with(lease)
        bootstrap.assert_not_called()

    def test_open_project_for_lifecycle_treats_already_open_as_preexisting(self):
        attempt = {
            "method": "running_ide",
            "accepted": True,
            "ownership_registered": False,
            "open_outcome": "already_open",
        }
        with (
            patch_attr(jb_inspect, "open_via_running_ide", return_value=attempt),
            patch_attr(jb_inspect, "bootstrap_ide_app") as bootstrap,
        ):
            result = jb_inspect.open_project_for_lifecycle(
                Namespace(port=None, background_open=True),
                {"ide": "IntelliJ IDEA"},
            )

        bootstrap.assert_not_called()
        self.assertEqual(result, ("preexisting", [], False))

    def test_open_via_running_ide_returns_false_for_unavailable_explicit_port(self):
        original_discover = jb_inspect.discover_open_identities

        def fake_discover(args, context):
            raise jb_inspect.InspectError("Inspection API unavailable on port 63345: connection refused", 3)

        jb_inspect.discover_open_identities = fake_discover
        try:
            result = jb_inspect.open_via_running_ide(
                Namespace(port=63345),
                {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"},
            )
        finally:
            jb_inspect.discover_open_identities = original_discover

        self.assertIsNone(result)

    def test_open_via_running_ide_reraises_explicit_port_identity_mismatch(self):
        original_discover = jb_inspect.discover_open_identities

        def fake_discover(args, context):
            raise jb_inspect.InspectError(
                "Inspection API identity on port 63345 reported port 63342.",
                3,
                {"error_reason": "identity_port_mismatch", "requested_port": 63345, "reported_port": 63342},
            )

        jb_inspect.discover_open_identities = fake_discover
        try:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.open_via_running_ide(
                    Namespace(port=63345),
                    {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"},
                )
        finally:
            jb_inspect.discover_open_identities = original_discover

        self.assertEqual(raised.exception.payload["error_reason"], "identity_port_mismatch")

    def test_open_project_for_lifecycle_bootstraps_then_lifecycle_opens(self):
        calls = []
        original_running = jb_inspect.open_via_running_ide
        original_bootstrap = jb_inspect.bootstrap_ide_app
        original_wait = jb_inspect.wait_for_matching_ide_identity
        original_now = jb_inspect.monotonic_ms
        original_sleep = jb_inspect.time.sleep

        def fake_running(args, context, attempts=None, method="running_ide", lease=None):
            calls.append(("running", method))
            accepted = len([call for call in calls if call[0] == "running"]) == 2
            attempt = {"method": method, "accepted": accepted, "ownership_registered": accepted}
            if attempts is not None:
                attempts.append(attempt)
            return attempt if accepted else None

        jb_inspect.open_via_running_ide = fake_running
        jb_inspect.bootstrap_ide_app = lambda context, background=True: calls.append(("bootstrap", background)) or {"method": "bootstrap_ide", "accepted": True}
        jb_inspect.wait_for_matching_ide_identity = lambda args, context, timeout_ms: calls.append(("wait", timeout_ms)) or {"port": 63342}
        jb_inspect.monotonic_ms = lambda: 0
        jb_inspect.time.sleep = lambda seconds: calls.append(("sleep", seconds))
        try:
            result = jb_inspect.open_project_for_lifecycle(Namespace(port=None, background_open=True, ide_launch=True, prepare_timeout_ms=1234), {"ide": "IntelliJ IDEA"})
        finally:
            jb_inspect.open_via_running_ide = original_running
            jb_inspect.bootstrap_ide_app = original_bootstrap
            jb_inspect.wait_for_matching_ide_identity = original_wait
            jb_inspect.monotonic_ms = original_now
            jb_inspect.time.sleep = original_sleep

        self.assertEqual(result[0], "bootstrapped_ide")
        self.assertTrue(result[2])
        self.assertEqual([attempt["method"] for attempt in result[1]], ["running_ide", "bootstrap_ide", "bootstrapped_ide"])
        self.assertEqual(calls, [("running", "running_ide"), ("bootstrap", True), ("wait", 1234), ("running", "bootstrapped_ide")])

    def test_open_project_for_lifecycle_retries_open_after_cold_bootstrap(self):
        calls = []
        original_running = jb_inspect.open_via_running_ide
        original_bootstrap = jb_inspect.bootstrap_ide_app
        original_wait = jb_inspect.wait_for_matching_ide_identity
        original_now = jb_inspect.now_ms
        original_sleep = jb_inspect.time.sleep
        ticks = iter([0, 0, 100, 200, 300])

        def fake_running(args, context, attempts=None, method="running_ide", lease=None):
            calls.append(("running", method))
            accepted = len([call for call in calls if call[0] == "running"]) == 4
            attempt = {"method": method, "accepted": accepted, "ownership_registered": accepted}
            if attempts is not None:
                attempts.append(attempt)
            return attempt if accepted else None

        jb_inspect.open_via_running_ide = fake_running
        jb_inspect.bootstrap_ide_app = lambda context, background=True: calls.append(("bootstrap", background)) or {"method": "bootstrap_ide", "accepted": True}
        jb_inspect.wait_for_matching_ide_identity = lambda args, context, timeout_ms: calls.append(("wait", timeout_ms)) or {"port": 63342}
        jb_inspect.now_ms = lambda: next(ticks)
        jb_inspect.time.sleep = lambda seconds: calls.append(("sleep", seconds))
        try:
            result = jb_inspect.open_project_for_lifecycle(Namespace(port=None, background_open=True, ide_launch=True, prepare_timeout_ms=1000), {"ide": "IntelliJ IDEA"})
        finally:
            jb_inspect.open_via_running_ide = original_running
            jb_inspect.bootstrap_ide_app = original_bootstrap
            jb_inspect.wait_for_matching_ide_identity = original_wait
            jb_inspect.now_ms = original_now
            jb_inspect.time.sleep = original_sleep

        self.assertEqual(result[0], "bootstrapped_ide")
        self.assertTrue(result[2])
        self.assertEqual([attempt["accepted"] for attempt in result[1]], [False, True, False, False, True])
        self.assertEqual(
            calls,
            [
                ("running", "running_ide"),
                ("bootstrap", True),
                ("wait", 1000),
                ("running", "bootstrapped_ide"),
                ("sleep", 1),
                ("running", "bootstrapped_ide"),
                ("sleep", 1),
                ("running", "bootstrapped_ide"),
            ],
        )

    def test_open_project_for_lifecycle_errors_when_bootstrapped_ide_rejects_open(self):
        original_running = jb_inspect.open_via_running_ide
        original_bootstrap = jb_inspect.bootstrap_ide_app
        original_wait = jb_inspect.wait_for_matching_ide_identity
        original_now = jb_inspect.now_ms
        original_sleep = jb_inspect.time.sleep
        ticks = iter([0, 0, 500, 1500])
        jb_inspect.open_via_running_ide = lambda args, context, attempts=None, method="running_ide", lease=None: (attempts.append({"method": method, "accepted": False}) if attempts is not None else None)
        jb_inspect.bootstrap_ide_app = lambda context, background=True: {"method": "bootstrap_ide", "accepted": True}
        jb_inspect.wait_for_matching_ide_identity = lambda args, context, timeout_ms: {"port": 63342}
        jb_inspect.now_ms = lambda: next(ticks)
        jb_inspect.time.sleep = lambda seconds: None
        try:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.open_project_for_lifecycle(
                    Namespace(port=None, background_open=True, ide_launch=True, prepare_timeout_ms=1234),
                    {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/worktree"},
                )
        finally:
            jb_inspect.open_via_running_ide = original_running
            jb_inspect.bootstrap_ide_app = original_bootstrap
            jb_inspect.wait_for_matching_ide_identity = original_wait
            jb_inspect.now_ms = original_now
            jb_inspect.time.sleep = original_sleep

        self.assertIn("did not accept", str(raised.exception))
        self.assertEqual(raised.exception.payload["prepare_timeout_ms"], 1234)
        self.assertEqual([attempt["method"] for attempt in raised.exception.payload["open_attempts"]], ["running_ide", "bootstrap_ide", "bootstrapped_ide", "bootstrapped_ide"])

    def test_wait_for_exact_route_after_registered_open_does_not_launch_app_fallback(self):
        original_find = jb_inspect.find_exact_route
        original_sleep = jb_inspect.time.sleep
        original_diagnostic = jb_inspect.discover_diagnostic_identities
        calls = []

        def fake_find(args, context):
            calls.append("find")
            return None

        jb_inspect.find_exact_route = fake_find
        jb_inspect.time.sleep = lambda seconds: None
        jb_inspect.discover_diagnostic_identities = lambda port: []
        open_attempts = [{"method": "running_ide", "accepted": True, "ownership_registered": True}]
        try:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.wait_for_exact_route_after_open(
                    Namespace(port=None, background_open=True),
                    {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                    1,
                    open_attempts,
                )
        finally:
            jb_inspect.find_exact_route = original_find
            jb_inspect.time.sleep = original_sleep
            jb_inspect.discover_diagnostic_identities = original_diagnostic

        self.assertNotIn("fallback", calls)
        self.assertEqual(raised.exception.payload["open_attempts"], open_attempts)

    def test_wait_for_exact_route_after_open_probes_diagnostics_without_rescheduling(self):
        open_attempts = [
            {
                "method": "running_ide",
                "accepted": True,
                "ownership_registered": True,
                "lifecycle_open_diagnostic_version": 1,
            }
        ]
        ticks = iter([0, 0, 2])
        with (
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(
                jb_inspect,
                "probe_lifecycle_open",
                return_value={
                    "status": "opening",
                    "probe": True,
                    "lifecycle_open_diagnostic": {"phase": "edt_started"},
                },
            ) as probe,
            patch_attr(jb_inspect, "open_via_running_ide") as open_again,
            patch_attr(jb_inspect, "now_ms", side_effect=lambda: next(ticks)),
            patch.object(time, "sleep"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.wait_for_exact_route_after_open(
                    Namespace(port=None, background_open=True),
                    {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                    1,
                    open_attempts,
                    {"lease_id": "lease-1"},
                )

        probe.assert_called_once()
        open_again.assert_not_called()
        self.assertEqual(
            raised.exception.payload["lifecycle_open_probe"]["lifecycle_open_diagnostic"]["phase"],
            "edt_started",
        )

    def test_diagnostic_probe_disables_legacy_already_opening_reschedule(self):
        open_attempts = [
            {
                "method": "running_ide",
                "accepted": True,
                "open_outcome": "already_opening",
                "lifecycle_open_diagnostic_version": 1,
            }
        ]
        ticks = iter([0, 0, 2])
        with (
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "probe_lifecycle_open", return_value={"status": "opening"}),
            patch_attr(jb_inspect, "open_via_running_ide") as open_again,
            patch_attr(jb_inspect, "now_ms", side_effect=lambda: next(ticks)),
            patch.object(time, "sleep"),
        ):
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.wait_for_exact_route_after_open(
                    Namespace(port=None, background_open=True),
                    {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                    1,
                    open_attempts,
                    {"lease_id": "lease-1"},
                )

        open_again.assert_not_called()

    def test_probe_lifecycle_open_requires_advertised_version(self):
        with (
            patch_attr(
                jb_inspect,
                "discover_open_identities",
                return_value=[
                    {
                        "port": 63343,
                        "ide_name": "PyCharm 2026.2.1",
                        "ide_product_code": "PY",
                        "session_id": "session-1",
                    }
                ],
            ),
            patch_attr(jb_inspect, "http_get") as http_get,
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
            )

        self.assertIsNone(result)
        http_get.assert_not_called()

    def test_probe_lifecycle_open_preserves_conflict_diagnostic_payload(self):
        error = jb_inspect.InspectError(
            "open state unknown",
            3,
            {
                "status": "failed",
                "reason": "open_state_unknown",
                "lifecycle_open_diagnostic": {
                    "phase": "unresolved",
                    "outcome_phase": "unresolved",
                },
            },
        )
        with (
            patch_attr(
                jb_inspect,
                "discover_open_identities",
                return_value=[
                    {
                        "port": 63343,
                        "ide_name": "PyCharm 2026.2.1",
                        "ide_product_code": "PY",
                        "session_id": "session-1",
                        "lifecycle_open_diagnostic_version": 1,
                    }
                ],
            ),
            patch_attr(jb_inspect, "http_get", side_effect=error),
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
            )

        self.assertEqual(result["reason"], "open_state_unknown")
        self.assertEqual(result["lifecycle_open_diagnostic"]["phase"], "unresolved")
        self.assertEqual(result["status"], "error")

    def test_probe_lifecycle_open_uses_remaining_deadline(self):
        with (
            patch_attr(
                jb_inspect,
                "discover_open_identities",
                return_value=[
                    {
                        "port": 63343,
                        "ide_name": "PyCharm 2026.2.1",
                        "ide_product_code": "PY",
                        "session_id": "session-1",
                        "lifecycle_open_diagnostic_version": 1,
                    }
                ],
            ),
            patch_attr(jb_inspect, "now_ms", return_value=1_000),
            patch_attr(
                jb_inspect,
                "http_get",
                return_value=jb_inspect.HttpResult(200, {"status": "opening"}, "url"),
            ) as http_get,
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
                deadline_ms=2_500,
            )

        self.assertEqual(result["status"], "opening")
        self.assertEqual(http_get.call_args.kwargs["timeout"], 1.5)

    def test_probe_lifecycle_open_skips_requests_after_deadline(self):
        with (
            patch_attr(
                jb_inspect,
                "discover_open_identities",
                return_value=[
                    {
                        "port": 63343,
                        "ide_name": "PyCharm 2026.2.1",
                        "ide_product_code": "PY",
                        "session_id": "session-1",
                        "lifecycle_open_diagnostic_version": 1,
                    }
                ],
            ),
            patch_attr(jb_inspect, "now_ms", return_value=2_500),
            patch_attr(jb_inspect, "http_get") as http_get,
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
                deadline_ms=2_500,
            )

        self.assertIsNone(result)
        http_get.assert_not_called()

    def test_probe_lifecycle_open_skips_request_below_viable_timeout(self):
        with (
            patch_attr(
                jb_inspect,
                "discover_open_identities",
                return_value=[
                    {
                        "port": 63343,
                        "ide_name": "PyCharm 2026.2.1",
                        "ide_product_code": "PY",
                        "session_id": "session-1",
                        "lifecycle_open_diagnostic_version": 1,
                    }
                ],
            ),
            patch_attr(jb_inspect, "now_ms", return_value=2_300),
            patch_attr(jb_inspect, "http_get") as http_get,
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
                deadline_ms=2_500,
            )

        self.assertIsNone(result)
        http_get.assert_not_called()

    def test_probe_lifecycle_open_continues_after_identity_failure(self):
        failure = jb_inspect.InspectError(
            "first IDE unavailable",
            3,
            {"reason": "inspection_api_unavailable"},
        )
        identities = [
            {
                "port": 63343,
                "ide_name": "PyCharm 2026.2.1",
                "ide_product_code": "PY",
                "session_id": "session-1",
                "lifecycle_open_diagnostic_version": 1,
            },
            {
                "port": 63344,
                "ide_name": "PyCharm 2026.2.1",
                "ide_product_code": "PY",
                "session_id": "session-2",
                "lifecycle_open_diagnostic_version": 1,
            },
        ]
        with (
            patch_attr(jb_inspect, "discover_open_identities", return_value=identities),
            patch_attr(
                jb_inspect,
                "http_get",
                side_effect=[failure, jb_inspect.HttpResult(200, {"status": "opening"}, "url")],
            ) as http_get,
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
            )

        self.assertEqual(result["status"], "opening")
        self.assertEqual(http_get.call_count, 2)

    def test_probe_lifecycle_open_prefers_diagnostic_identity_failure(self):
        identities = [
            {
                "port": 63343,
                "ide_name": "PyCharm 2026.2.1",
                "ide_product_code": "PY",
                "session_id": "session-1",
                "lifecycle_open_diagnostic_version": 1,
            },
            {
                "port": 63344,
                "ide_name": "PyCharm 2026.2.1",
                "ide_product_code": "PY",
                "session_id": "session-2",
                "lifecycle_open_diagnostic_version": 1,
            },
        ]
        with (
            patch_attr(jb_inspect, "discover_open_identities", return_value=identities),
            patch_attr(
                jb_inspect,
                "http_get",
                side_effect=[
                    jb_inspect.InspectError(
                        "first",
                        3,
                        {
                            "reason": "first_failure",
                            "lifecycle_open_diagnostic": {"phase": "readiness_wait"},
                        },
                    ),
                    jb_inspect.InspectError("second", 3, {"reason": "second_failure"}),
                ],
            ),
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
            )

        self.assertEqual(result["reason"], "first_failure")
        self.assertEqual(result["lifecycle_open_diagnostic"]["phase"], "readiness_wait")
        self.assertEqual(result["status"], "error")

    def test_probe_lifecycle_open_uses_default_timeout_without_deadline(self):
        with (
            patch_attr(
                jb_inspect,
                "discover_open_identities",
                return_value=[
                    {
                        "port": 63343,
                        "ide_name": "PyCharm 2026.2.1",
                        "ide_product_code": "PY",
                        "session_id": "session-1",
                        "lifecycle_open_diagnostic_version": 1,
                    }
                ],
            ),
            patch_attr(
                jb_inspect,
                "http_get",
                return_value=jb_inspect.HttpResult(200, {"status": "opening"}, "url"),
            ) as http_get,
        ):
            result = jb_inspect.probe_lifecycle_open(
                Namespace(port=None),
                {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"lease_id": "lease-1"},
            )

        self.assertEqual(result["status"], "opening")
        self.assertEqual(http_get.call_args.kwargs["timeout"], 30.0)

    def test_wait_for_exact_route_keeps_last_successful_lifecycle_probe(self):
        open_attempts = [
            {
                "method": "running_ide",
                "accepted": True,
                "ownership_registered": True,
                "lifecycle_open_diagnostic_version": 1,
            }
        ]
        probes = iter([
            {"status": "opening", "lifecycle_open_diagnostic": {"phase": "readiness_wait"}},
            None,
        ])
        ticks = iter([0, 0, 1, 2])
        with (
            patch_attr(jb_inspect, "find_exact_route", return_value=None),
            patch_attr(jb_inspect, "probe_lifecycle_open", side_effect=lambda *args, **kwargs: next(probes)),
            patch_attr(jb_inspect, "now_ms", side_effect=lambda: next(ticks)),
            patch.object(time, "sleep"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.wait_for_exact_route_after_open(
                    Namespace(port=None, background_open=True),
                    {"ide": "PyCharm", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                    1,
                    open_attempts,
                    {"lease_id": "lease-1"},
                )

        self.assertEqual(
            raised.exception.payload["lifecycle_open_probe"]["lifecycle_open_diagnostic"]["phase"],
            "readiness_wait",
        )

    def test_wait_until_route_ready_requires_consecutive_ready_statuses(self):
        statuses = iter([
            {"indexing": True},
            {"indexing": False, "is_scanning": False},
            {"indexing": True},
            {"indexing": False, "is_scanning": False},
            {"indexing": False, "is_scanning": False},
            {"indexing": False, "is_scanning": False},
        ])
        calls = []
        original_call = jb_inspect.call_endpoint
        original_sleep = jb_inspect.time.sleep
        original_now = jb_inspect.now_ms
        jb_inspect.call_endpoint = lambda route, endpoint, params: calls.append(endpoint) or next(statuses)
        jb_inspect.time.sleep = lambda seconds: None
        jb_inspect.now_ms = lambda: 0
        try:
            jb_inspect.wait_until_route_ready(
                helper_args(port=None),
                {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                {"port": 63342},
                1_000,
            )
        finally:
            jb_inspect.call_endpoint = original_call
            jb_inspect.time.sleep = original_sleep
            jb_inspect.now_ms = original_now

        self.assertEqual(calls, ["status", "status", "status", "status", "status", "status"])

    def test_route_status_ready_requires_helper_owned_content_roots(self):
        self.assertFalse(
            jb_inspect.route_status_ready(
                {
                    "indexing": False,
                    "is_scanning": False,
                    "route": {
                        "lifecycle_readiness": {
                            "ready": False,
                            "reason": "no_content_roots",
                            "content_root_count": 0,
                        }
                    },
                }
            )
        )
        self.assertTrue(
            jb_inspect.route_status_ready(
                {
                    "indexing": False,
                    "is_scanning": False,
                    "route": {
                        "lifecycle_readiness": {
                            "ready": True,
                            "reason": "ready",
                            "content_root_count": 1,
                        }
                    },
                }
            )
        )

    def test_wait_until_route_ready_reports_missing_content_roots(self):
        for reason, content_root_count in (("no_content_roots", 0), ("content_roots_outside_target", 2)):
            with self.subTest(reason=reason):
                status = {
                    "indexing": False,
                    "is_scanning": False,
                    "route": {
                        "lifecycle_readiness": {
                            "ready": False,
                            "reason": reason,
                            "content_root_count": content_root_count,
                        }
                    },
                }
                times = iter([0, 0, 1, 1])
                with (
                    patch_attr(jb_inspect, "call_endpoint", return_value=status),
                    patch.object(time, "sleep"),
                    patch_attr(jb_inspect, "monotonic_ms", side_effect=lambda: next(times)),
                ):
                    with self.assertRaises(jb_inspect.InspectError) as raised:
                        jb_inspect.wait_until_route_ready(
                            helper_args(port=None),
                            {"ide": "IntelliJ IDEA", "worktree_root": "/tmp/repo", "project_path": "/tmp/repo"},
                            {"port": 63342},
                            0,
                        )

                self.assertEqual(raised.exception.payload["error_reason"], "project_content_roots_missing")
                self.assertEqual(raised.exception.payload["lifecycle_readiness"]["reason"], reason)

    def test_jetbrains_config_dirs_requires_ide_when_multiple_configs_exist(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "Library" / "Application Support" / "JetBrains"
            for name in ("PyCharm2026.1", "IntelliJIdea2026.1"):
                (base / name / "options").mkdir(parents=True)
            with patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}, clear=False), \
                patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=Path(tmp)):
                os.environ.pop("JETBRAINS_INSPECTION_IDE_CONFIG_DIR", None)
                with self.assertRaises(jb_inspect.InspectError) as raised:
                    jb_inspect.jetbrains_config_dirs({})

        self.assertIn("multiple IDE config directories", str(raised.exception))
        self.assertEqual(raised.exception.payload["error_reason"], "ide_selection_required")
        self.assertIn("Add preferred JetBrains IDE metadata", raised.exception.payload["next_action"])

    def test_jetbrains_config_dirs_matches_requested_ide(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "Library" / "Application Support" / "JetBrains"
            pycharm = base / "PyCharm2026.1"
            idea = base / "IntelliJIdea2026.1"
            (pycharm / "options").mkdir(parents=True)
            (idea / "options").mkdir(parents=True)
            with patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}, clear=False), \
                patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=Path(tmp)):
                os.environ.pop("JETBRAINS_INSPECTION_IDE_CONFIG_DIR", None)
                result = jb_inspect.jetbrains_config_dirs({"ide": "IntelliJ IDEA"})

        self.assertEqual(result, [idea])

    def test_jetbrains_config_dirs_prefers_latest_stable_for_product_selector(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            stable = make_config_dir(home, "PyCharm2026.2")
            make_config_dir(home, "PyCharm2026.1")
            with patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": ""}, clear=False), \
                patch.object(sys, "platform", "darwin"), \
                patch_attr(jb_inspect.Path, "home", return_value=home):
                os.environ.pop("JETBRAINS_INSPECTION_IDE_CONFIG_DIR", None)
                result = jb_inspect.jetbrains_config_dirs({"ide": "PyCharm"})

        self.assertEqual(result, [stable])

    def test_jetbrains_config_dirs_honors_env_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            override = Path(tmp) / "CustomConfig"
            override.mkdir()
            with patch.dict(os.environ, {"JETBRAINS_INSPECTION_IDE_CONFIG_DIR": str(override)}, clear=False):
                result = jb_inspect.jetbrains_config_dirs({"ide": "WebStorm"})

        self.assertEqual(result, [override.resolve()])


class AgentInspectContractTest(unittest.TestCase):
    def test_broad_scope_placeholder_zero_does_not_change_native_verdict(self):
        for scope in ["whole_project", "directory"]:
            with self.subTest(scope=scope):
                payload = {"status": "clean", "context": {"scope": scope}}
                original = jb_inspect.verdict_for_payload(payload)
                payload["capture_diagnostic"] = {
                    "scope_kind": scope, "scope_resolution_status": "project_scope",
                    "scope_file_resolved_count": 0,
                }
                self.assertIsNone(jb_inspect.selected_inspection_file_count(payload))
                self.assertEqual(jb_inspect.verdict_for_payload(payload), original)

    def test_observation_uses_native_scope_instead_of_repository_default(self):
        for native_scope, count, expected in [("whole_project", 0, None), ("directory", 0, None), ("files", 2, 2)]:
            with self.subTest(native_scope=native_scope):
                payload = {
                    "status": "clean", "context": {"scope": "changed_files"},
                    "capture_diagnostic": {"scope_kind": native_scope, "scope_file_resolved_count": count},
                }
                self.assertEqual(jb_inspect.selected_inspection_file_count(payload), expected)
                self.assertNotEqual(jb_inspect.verdict_for_payload(payload)["verdict_reason"], "inspection_scope_empty")

    def emit_agent_payload(self, payload, helper_exit_code=None):
        output = io.StringIO()
        with redirect_stdout(output), patch_attr(jb_inspect, "log_assessment_records"):
            exit_code = jb_inspect.emit_agent_result(
                payload,
                command="agent-inspect",
                helper_exit_code=helper_exit_code,
            )
        return exit_code, json.loads(output.getvalue())

    def test_legacy_agent_result_shape_and_guidance_remain_unchanged(self):
        payload = {
            "command": "inspect-closeout",
            "status": "error",
            "error_reason": "cleanup_failed",
            "cleanup": {"status": "failed"},
        }

        jb_inspect.apply_verdict(payload)

        self.assertNotIn("terminal", payload["agent_result"])
        self.assertIn("inspect-closeout", payload["agent_result"]["next_action"])

    def test_legacy_invented_flag_keeps_argparse_exit_and_stderr(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "inspect-closeout", "--no-wait-stale"],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("unrecognized arguments: --no-wait-stale", result.stderr)

    def test_legacy_exit_contract_is_unchanged(self):
        self.assertEqual(jb_inspect.classify_run_exit({"status": "clean"}), 0)
        self.assertEqual(jb_inspect.classify_run_exit({"status": "findings"}), 1)
        self.assertEqual(jb_inspect.classify_run_exit({"status": "error"}), 1)


class InspectionStageDiagnosticsTest(unittest.TestCase):
    @staticmethod
    def failure(source, outcome, stage, elapsed_ms, run_elapsed_ms, stack=None):
        return {
            "source": source,
            "outcome": outcome,
            "inspection_stage_at_failure": stage,
            "inspection_stage_elapsed_ms": elapsed_ms,
            "inspection_run_elapsed_ms": run_elapsed_ms,
            "dumb_mode": False,
            "inspection_worker_thread": "inspection-worker",
            "inspection_worker_stack": stack or ["inspection-worker-frame"],
        }

    @staticmethod
    def compact(payload):
        jb_inspect.apply_verdict(payload)
        return jb_inspect.compact_agent_result_payload(payload, jb_inspect.classify_run_exit(payload))

    def test_stage_names_are_opaque_progress_labels_bounded_by_shape(self):
        payload = {
            "inspection_run_id": 41,
            "inspection_stage": "stage_added_by_a_newer_plugin",
            "inspection_stage_history": [
                {"stage": "sync", "elapsed_ms": 10},
                {"stage": "stage_added_by_a_newer_plugin", "elapsed_ms": 20},
                {"stage": "Not A Label; rm -rf", "elapsed_ms": 30},
                {"stage": "x" * 65, "elapsed_ms": 40},
                {"stage": 7, "elapsed_ms": 50},
            ],
        }

        diagnostic = jb_inspect.inspection_stage_diagnostics(payload)

        self.assertEqual(diagnostic["inspection_stage"], "stage_added_by_a_newer_plugin")
        self.assertEqual(
            diagnostic["inspection_stage_history"],
            [
                {"stage": "sync", "elapsed_ms": 10},
                {"stage": "stage_added_by_a_newer_plugin", "elapsed_ms": 20},
            ],
        )
        self.assertNotIn(
            "inspection_stage",
            jb_inspect.inspection_stage_diagnostics({"inspection_run_id": 41, "inspection_stage": "Not A Label"}),
        )

    def test_wait_command_carries_stage_and_primary_failure_into_agent_diagnostic(self):
        route = {"project_key": "path:/repo", "session_id": "session", "port": 63342}
        wait_body = {
            "status": "timed_out",
            "completion_reason": "timeout",
            "timed_out": True,
            "inspection_in_progress": True,
            "inspection_run_id": 41,
            "inspection_stage": "smart_wait",
            "inspection_stage_elapsed_ms": 3_000,
            "inspection_run_elapsed_ms": 5_000,
            "inspection_stage_history": [{"stage": "sync", "elapsed_ms": 2_000}],
            "inspection_failure_diagnostic": self.failure(
                "wait_timeout", "timeout", "smart_wait", 3_000, 5_000
            ),
        }

        with (
            patch_attr(jb_inspect, "resolve_route", return_value=route),
            patch_attr(jb_inspect, "call_contextual_endpoint", return_value=wait_body),
        ):
            result = jb_inspect.command_wait(
                helper_args(timeout_ms=5_000, poll_ms=1_000),
                {"scope": "changed_files"},
            )

        compact = jb_inspect.compact_agent_result_payload(result, 1)
        diagnostic = compact["diagnostic"]
        self.assertEqual(diagnostic["inspection_stage_run_id"], 41)
        self.assertEqual(diagnostic["inspection_stage"], "smart_wait")
        self.assertEqual(diagnostic["inspection_stage_history"], [{"stage": "sync", "elapsed_ms": 2_000}])
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["source"], "wait_timeout")
        self.assertNotIn("inspection_stage_index", json.dumps(compact))

    def test_capture_deadline_is_read_from_raw_capture_diagnostic_without_flattening_input(self):
        capture_failure = self.failure(
            "capture_deadline", "timeout", "result_settling", 4_000, 14_000
        )
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "raw": {
                "inspection_run_id": 42,
                "inspection_stage": "result_settling",
                "inspection_stage_elapsed_ms": 4_000,
                "inspection_run_elapsed_ms": 14_000,
                "capture_diagnostic": {"inspection_failure_diagnostic": capture_failure},
            },
        }

        compact = self.compact(payload)

        diagnostic = compact["diagnostic"]
        self.assertEqual(diagnostic["inspection_stage_run_id"], 42)
        self.assertEqual(diagnostic["inspection_failure_diagnostic"], capture_failure)
        self.assertEqual(
            payload["raw"]["capture_diagnostic"]["inspection_failure_diagnostic"],
            capture_failure,
        )
        self.assertNotIn("capture_diagnostic", diagnostic)

    def test_terminal_status_supplies_history_while_wait_timeout_remains_primary(self):
        wait_failure = self.failure("wait_timeout", "timeout", "native_execute", 8_000, 18_000)
        cancel_failure = self.failure("cancellation", "cancelled", "native_execute", 9_000, 19_000)
        payload = {
            "status": "timed_out",
            "trigger": {"inspection_run_id": 43},
            "wait": {
                "inspection_run_id": 43,
                "inspection_stage": "native_execute",
                "inspection_run_elapsed_ms": 18_000,
                "inspection_failure_diagnostic": wait_failure,
            },
            "cancellation": {
                "expected_inspection_run_id": 43,
                "response": {
                    "inspection_run_id": 43,
                    "inspection_failure_diagnostic": cancel_failure,
                },
                "last_status": {
                    "inspection_run_id": 43,
                    "inspection_stage": "native_execute",
                    "inspection_stage_elapsed_ms": 9_500,
                    "inspection_run_elapsed_ms": 19_500,
                    "inspection_stage_history": [
                        {"stage": "sync", "elapsed_ms": 1_000},
                        {"stage": "smart_wait", "elapsed_ms": 2_000},
                    ],
                    "inspection_terminal_outcome": "cancelled",
                    "inspection_failure_diagnostic": wait_failure,
                    "inspection_failure_history": [wait_failure, cancel_failure],
                },
            },
        }

        diagnostic = self.compact(payload)["diagnostic"]

        self.assertEqual(diagnostic["inspection_run_elapsed_ms"], 19_500)
        self.assertEqual(diagnostic["inspection_terminal_outcome"], "cancelled")
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["source"], "wait_timeout")
        self.assertEqual(
            [entry["source"] for entry in diagnostic["inspection_failure_history"]],
            ["wait_timeout", "cancellation"],
        )

    def test_exact_proof_terminal_diagnostics_are_preserved_without_outer_retry(self):
        deadline_failure = self.failure(
            "exact_proof_deadline", "timeout", "exact_proof", 60_000, 75_000
        )
        deadline_failure.update(
            {
                "inspection_tool_short_name": "ProofTool",
                "inspection_file": "/Users/example/project/src/Main.kt",
                "inspection_worker_phase": "execution",
            }
        )
        preempted_failure = self.failure(
            "exact_proof_write_preempted", "preempted", "exact_proof", 61_000, 76_000
        )
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "inspection_verdict": "UNKNOWN",
            "proof_failures": ["execution_not_proven"],
            "trigger": {"inspection_run_id": 52},
            "wait": {
                "inspection_run_id": 52,
                "inspection_failure_diagnostic": deadline_failure,
            },
            "last_status": {
                "inspection_run_id": 52,
                "inspection_stage": "exact_proof",
                "inspection_terminal_outcome": "preempted",
                "inspection_failure_history": [deadline_failure, preempted_failure],
            },
        }

        compact = self.compact(payload)
        diagnostic = compact["diagnostic"]

        self.assertEqual(diagnostic["inspection_terminal_outcome"], "preempted")
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["source"], "exact_proof_deadline")
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["outcome"], "timeout")
        self.assertEqual(
            [entry["source"] for entry in diagnostic["inspection_failure_history"]],
            ["exact_proof_deadline", "exact_proof_write_preempted"],
        )
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["inspection_tool_short_name"], "ProofTool")
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["inspection_worker_phase"], "execution")
        self.assertEqual(compact["agent_result"]["verdict"], "UNKNOWN")
        self.assertFalse(compact["agent_result"]["retry_policy"]["retry"])
        self.assertIn("Stop retrying", compact["agent_result"]["next_action"])

    def test_exact_proof_worker_evidence_is_bounded_and_unknown_values_are_dropped(self):
        value = self.failure("exact_proof_deadline", "timeout", "exact_proof", 1, 2)
        value.update(
            {
                "inspection_tool_short_name": "tool=" + "x" * 300,
                "inspection_file": "/repo/" + "x" * 5_000,
                "inspection_worker_phase": "mapping",
                "unexpected": "discarded",
            }
        )

        bounded = jb_inspect.bounded_inspection_failure_diagnostic(value)

        self.assertEqual(len(bounded["inspection_tool_short_name"]), jb_inspect.MAX_INSPECTION_TOOL_SHORT_NAME_LENGTH)
        self.assertEqual(len(bounded["inspection_file"]), jb_inspect.MAX_INSPECTION_FILE_LENGTH)
        self.assertNotIn("inspection_worker_phase", bounded)
        self.assertNotIn("unexpected", bounded)
        self.assertEqual(
            jb_inspect.bounded_inspection_failure_diagnostic(
                self.failure("unknown_source", "timeout", "exact_proof", 1, 2)
            ),
            {},
        )
        invalid_outcome = self.failure("exact_proof_deadline", "interrupted", "exact_proof", 1, 2)
        self.assertEqual(jb_inspect.bounded_inspection_failure_diagnostic(invalid_outcome), {})

    def test_diagnostics_never_cross_an_explicit_run_change(self):
        replacement_failure = self.failure(
            "capture_deadline", "timeout", "publish", 10_000, 60_000
        )
        payload = jb_inspect.inspection_run_changed_result(
            {"project_key": "path:/repo"},
            {"inspection_run_id": 44},
            {
                "inspection_run_id": 45,
                "inspection_stage": "publish",
                "inspection_run_elapsed_ms": 60_000,
                "inspection_terminal_outcome": "completed",
                "inspection_failure_diagnostic": replacement_failure,
            },
        )

        compact = self.compact(payload)
        diagnostic = compact["diagnostic"]

        self.assertEqual(compact["identity"]["inspection_run_id"], 45)
        self.assertEqual(diagnostic["inspection_stage_run_id"], 45)
        self.assertEqual(diagnostic["inspection_stage"], "publish")
        self.assertEqual(diagnostic["inspection_run_elapsed_ms"], 60_000)
        self.assertEqual(diagnostic["inspection_terminal_outcome"], "completed")
        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["source"], "capture_deadline")

    def test_inspection_failure_wrapper_preserves_the_nested_run_identity(self):
        payload = {
            "status": "error",
            "error_reason": "inspection_api_http_error",
            "inspection_failure": {
                "evidence_ids": {"inspection_run_id": 46},
                "wait": {
                    "inspection_run_id": 46,
                    "inspection_stage": "exact_proof",
                    "inspection_run_elapsed_ms": 12_000,
                },
            },
        }

        compact = self.compact(payload)

        self.assertIsNone(compact["identity"]["inspection_run_id"])
        self.assertEqual(compact["diagnostic"]["inspection_stage_run_id"], 46)
        self.assertEqual(compact["diagnostic"]["inspection_stage"], "exact_proof")

    def test_retry_and_lane_diagnostics_keep_each_run_separate(self):
        first_failure = self.failure("capture_deadline", "timeout", "result_settling", 5_000, 15_000)
        single_payload = {
            "status": "clean",
            "inspection_attribution": {"inspection_run_id": 48},
            "inspection_stage": "publish",
            "inspection_run_elapsed_ms": 8_000,
            "inspection_terminal_outcome": "completed",
            "internal_retries": [
                {
                    "inspection_run_id": 47,
                    "status": "stale_results",
                    "verdict": "UNKNOWN",
                    "bucket": "stale_results",
                    "verdict_reason": "stale_results",
                    "snapshot_change_kind": "project_changed_since_inspection",
                    "stale_reasons": ["project_changed_since_inspection"],
                    "wait_completion_reason": "stale_results",
                    "inspection_stage": "result_settling",
                    "inspection_run_elapsed_ms": 15_000,
                    "inspection_failure_diagnostic": first_failure,
                }
            ],
        }
        single_diagnostic = self.compact(single_payload)["diagnostic"]
        self.assertEqual(
            [attempt["inspection_run_id"] for attempt in single_diagnostic["inspection_attempts"]],
            [47, 48],
        )
        retried_attempt = single_diagnostic["inspection_attempts"][0]
        self.assertEqual(retried_attempt["attempt_index"], 0)
        self.assertEqual(retried_attempt["bucket"], "stale_results")
        self.assertEqual(retried_attempt["verdict_reason"], "stale_results")
        self.assertEqual(retried_attempt["stale_reasons"], ["project_changed_since_inspection"])
        self.assertEqual(retried_attempt["snapshot_change_kind"], "project_changed_since_inspection")
        self.assertIs(retried_attempt["retry"], True)
        self.assertEqual(
            single_diagnostic["inspection_attempts"][0]["inspection_failure_diagnostic"]["source"],
            "capture_deadline",
        )

        lane_payload = {
            "status": "inspection_lanes_unknown",
            "lane_selection": {"scope": "files"},
            "lane_results": [
                {
                    "id": "python",
                    "required": True,
                    "verdict": "UNKNOWN",
                    "bucket": "capture_not_ready",
                    "status": "results_available",
                    "retry_policy": {"retry": True, "max_attempts": 1, "wait_ms": 1_000},
                    "evidence_ids": {"inspection_run_id": 49},
                    "wait": {
                        "inspection_run_id": 49,
                        "inspection_stage": "native_execute",
                        "inspection_run_elapsed_ms": 9_000,
                        "inspection_stage_history": [
                            {"stage": "sync", "elapsed_ms": index}
                            for index in range(12)
                        ],
                        "inspection_failure_diagnostic": self.failure(
                            "wait_timeout",
                            "timeout",
                            "native_execute",
                            8_000,
                            9_000,
                            stack=[f"lane-frame-{index} token=lane-secret" for index in range(70)],
                        ),
                    },
                    "diagnostic": {
                        "error_reason": "capture_not_ready",
                        "inspection_run_id": 999,
                        "inspection_stage": "publish",
                        "inspection_run_elapsed_ms": 99_000,
                    },
                    "capture_diagnostic": {
                        "capture_complete": False,
                        "inspection_failure_diagnostic": self.failure(
                            "capture_deadline", "timeout", "publish", 9_000, 10_000
                        ),
                    },
                },
                {
                    "id": "jvm",
                    "required": True,
                    "verdict": "GREEN",
                    "bucket": "clean",
                    "retry_policy": {"retry": False, "max_attempts": 0, "wait_ms": 0},
                    "evidence_ids": {"inspection_run_id": 50},
                    "diagnostic": {
                        "inspection_run_id": 50,
                        "inspection_stage": "publish",
                        "inspection_run_elapsed_ms": 7_000,
                        "inspection_terminal_outcome": "completed",
                    },
                },
            ],
        }

        lanes = jb_inspect.compact_multi_lane_agent_result_payload(lane_payload, 1)["lanes"]
        self.assertEqual(lanes[0]["diagnostic"]["inspection_stage_run_id"], 49)
        self.assertEqual(lanes[0]["diagnostic"]["inspection_stage"], "native_execute")
        self.assertEqual(lanes[0]["diagnostic"]["error_reason"], "capture_not_ready")
        self.assertEqual(lanes[0]["status"], "results_available")
        self.assertEqual(lanes[0]["capture_diagnostic"], {"capture_complete": False})
        self.assertEqual(len(lanes[0]["diagnostic"]["inspection_stage_history"]), 8)
        self.assertEqual(
            len(lanes[0]["diagnostic"]["inspection_failure_diagnostic"]["inspection_worker_stack"]),
            64,
        )
        self.assertNotIn("lane-secret", json.dumps(lanes[0]))
        self.assertEqual(lanes[1]["diagnostic"]["inspection_stage_run_id"], 50)
        self.assertEqual(lanes[1]["diagnostic"]["inspection_stage"], "publish")

    def test_stage_diagnostics_keep_established_run_id_provenance(self):
        payload = {
            "command": "inspect-closeout",
            "status": "clean",
            "inspection_attribution": {"inspection_run_id": "61"},
            "inspection_stage": "publish",
            "inspection_run_elapsed_ms": 8_000,
        }
        retry = jb_inspect.compact_retry_result(payload, 0)
        logged = jb_inspect.logged_attempt_summary(payload, 0, terminal=True)
        record = jb_inspect.outcome_log_record(payload, 1)
        compact = self.compact(payload)
        lane_record = jb_inspect.outcome_log_record(
            {
                "command": "inspect-closeout",
                "status": "clean",
                "lane_results": [
                    {
                        "id": "python",
                        "evidence_ids": {"inspection_run_id": "62"},
                        "inspection_stage": "publish",
                        "inspection_run_elapsed_ms": 9_000,
                    }
                ],
            },
            1,
        )

        for summary in (retry, logged, record):
            self.assertEqual(summary["inspection_run_id"], "61")
            self.assertEqual(summary["inspection_stage_run_id"], 61)
        self.assertEqual(compact["identity"]["inspection_run_id"], "61")
        self.assertEqual(compact["diagnostic"]["inspection_stage_run_id"], 61)
        self.assertEqual(lane_record["inspection_lanes"][0]["inspection_run_id"], "62")
        self.assertEqual(lane_record["inspection_lanes"][0]["inspection_stage_run_id"], 62)

    def test_wait_and_cancel_failures_form_bounded_history_without_plugin_history(self):
        wait_failure = self.failure("wait_timeout", "timeout", "native_execute", 8_000, 18_000)
        cancel_failure = self.failure("cancellation", "cancelled", "native_execute", 9_000, 19_000)
        payload = {
            "status": "timed_out",
            "trigger": {"inspection_run_id": 51},
            "wait": {
                "inspection_run_id": 51,
                "inspection_failure_diagnostic": wait_failure,
            },
            "cancellation": {
                "expected_inspection_run_id": 51,
                "response": {"inspection_failure_diagnostic": cancel_failure},
            },
        }

        diagnostic = self.compact(payload)["diagnostic"]

        self.assertEqual(diagnostic["inspection_failure_diagnostic"]["source"], "wait_timeout")
        self.assertEqual(
            [entry["source"] for entry in diagnostic["inspection_failure_history"]],
            ["wait_timeout", "cancellation"],
        )

    def test_durable_records_bound_and_redact_stage_diagnostics(self):
        stack = [
            f"frame-{index} token=top-secret at /Users/example/private/File{index}.kt:12"
            for index in range(80)
        ]
        primary = self.failure(
            "wait_timeout", "timeout", "native_execute", 30_000, 40_000, stack=stack
        )
        primary.update(
            {
                "inspection_tool_short_name": "ProofTool",
                "inspection_file": "/Users/example/private/Proof.kt",
                "inspection_worker_phase": "execution",
            }
        )
        cancellation = self.failure(
            "cancellation", "cancelled", "native_execute", 31_000, 41_000
        )
        payload = {
            "command": "inspect-closeout",
            "status": "timed_out",
            "timed_out": True,
            "context": {"client_run_id": "stage-diagnostic-record"},
            "inspection_attribution": {"inspection_run_id": 51},
            "inspection_stage": "native_execute",
            "inspection_stage_elapsed_ms": 31_000,
            "inspection_run_elapsed_ms": 41_000,
            "inspection_stage_history": [
                {"stage": stage, "elapsed_ms": index * 1_000}
                for index, stage in enumerate(
                    [
                        "sync",
                        "smart_wait",
                        "python_sdk_readiness",
                        "native_configure",
                        "native_execute",
                        "exact_proof",
                        "result_settling",
                        "publish",
                        "sync",
                        "smart_wait",
                    ]
                )
            ],
            "inspection_failure_diagnostic": primary,
            "inspection_failure_history": [primary, cancellation, primary, cancellation],
        }
        jb_inspect.apply_verdict(payload)

        record = jb_inspect.outcome_log_record(payload, 1)
        serialized = json.dumps(record)

        self.assertEqual(record["inspection_run_id"], 51)
        self.assertEqual(len(record["inspection_stage_history"]), 8)
        self.assertEqual(len(record["inspection_failure_history"]), 3)
        self.assertEqual(len(record["inspection_failure_diagnostic"]["inspection_worker_stack"]), 64)
        self.assertEqual(record["inspection_failure_diagnostic"]["source"], "wait_timeout")
        self.assertEqual(record["inspection_failure_diagnostic"]["inspection_tool_short_name"], "ProofTool")
        self.assertEqual(record["inspection_failure_diagnostic"]["inspection_worker_phase"], "execution")
        self.assertEqual(
            record["inspection_failure_diagnostic"]["inspection_file_hash"],
            jb_inspect.stable_value_hash("/Users/example/private/Proof.kt"),
        )
        self.assertNotIn("inspection_file", record["inspection_failure_diagnostic"])
        self.assertNotIn("top-secret", serialized)
        self.assertNotIn("/Users/example/private", serialized)
        self.assertIn("<redacted>", serialized)
        self.assertIn("<path:sha256:", serialized)


class ClassificationTest(unittest.TestCase):
    def test_clean_run_exits_zero(self):
        self.assertEqual(jb_inspect.classify_run_exit({"status": "clean"}), 0)

    def test_findings_exit_nonzero(self):
        self.assertEqual(jb_inspect.classify_run_exit({"status": "findings"}), 1)

    def test_stale_problems_exit_nonzero(self):
        result = {"status": "stale_results", "capture_incomplete": False, "results_may_be_stale": True}
        self.assertEqual(jb_inspect.classify_problems_exit(result), 1)

    def test_results_with_findings_exit_nonzero(self):
        result = {"status": "results_available", "problems": [{"description": "x"}]}
        self.assertEqual(jb_inspect.classify_problems_exit(result), 1)

    def test_run_status_uses_total_when_current_page_is_empty(self):
        problems = {"status": "results_available", "total_problems": 5, "problems": []}

        self.assertEqual(jb_inspect.classify_run_status({}, problems), "findings")

    def test_run_status_preserves_clean_wait_when_problems_has_no_results(self):
        wait = {"completion_reason": "clean", "clean_inspection": True, "inspection_verdict": "GREEN"}
        problems = {"status": "no_results", "total_problems": 0, "problems": []}

        self.assertEqual(jb_inspect.classify_run_status(wait, problems), "clean")

    def test_cleanup_leases_failed_cleanup_exits_nonzero(self):
        result = {"status": "ok", "failed": [{"status": "failed", "reason": "close_failed"}]}

        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)

    def test_wait_no_results_exits_nonzero(self):
        result = {"status": "no_results", "wait": {"completion_reason": "no_results"}}

        self.assertEqual(jb_inspect.classify_wait_exit(result), 1)

    def test_status_with_clean_result_exits_zero(self):
        body = {"clean_inspection": True, "is_scanning": False}
        result = {
            "status": jb_inspect.status_label(body),
            "clean": jb_inspect.classify_status_body_clean(body),
        }
        self.assertEqual(jb_inspect.classify_status_exit(result), 0)

    def test_status_with_results_without_verdict_is_unknown(self):
        body = {"has_inspection_results": True, "is_scanning": False}
        result = {
            "status": jb_inspect.status_label(body),
            "clean": jb_inspect.classify_status_body_clean(body),
        }
        self.assertFalse(result["clean"])
        self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_session_drift_exits_nonzero(self):
        body = {"session_drift": True, "clean_inspection": True}
        result = {"clean": jb_inspect.classify_status_body_clean(body)}
        self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_stale_exits_nonzero(self):
        body = {"results_may_be_stale": True, "has_inspection_results": True}
        result = {"clean": jb_inspect.classify_status_body_clean(body)}
        self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_in_progress_exits_nonzero(self):
        body = {"is_scanning": True}
        result = {"clean": jb_inspect.classify_status_body_clean(body)}
        self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_label_prefers_explicit_status(self):
        self.assertEqual(jb_inspect.status_label({"status": "custom"}), "custom")

    def test_status_label_synthesizes_from_boolean_state(self):
        cases = [
            ({"session_drift": True}, "session_drift"),
            ({"ambiguous": True}, "ambiguous"),
            ({"unavailable": True}, "unavailable"),
            ({"results_may_be_stale": True}, "stale_results"),
            ({"capture_incomplete": True}, "capture_incomplete"),
            ({"timed_out": True}, "timed_out"),
            ({"indexing": True}, "indexing"),
            ({"is_scanning": True}, "running"),
            ({"inspection_in_progress": True}, "running"),
            ({"clean_inspection": True}, "clean"),
            ({"has_inspection_results": True}, "results_available"),
        ]
        for body, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(jb_inspect.status_label(body), expected)

    def test_status_clean_exits_zero(self):
        body = {"status": "clean"}
        result = {"status": "clean", "clean": jb_inspect.classify_status_body_clean(body)}

        self.assertEqual(jb_inspect.classify_status_exit(result), 0)

    def test_status_results_available_without_proof_exits_nonzero(self):
        body = {"status": "results_available"}
        result = {"status": "results_available", "clean": jb_inspect.classify_status_body_clean(body)}

        self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_results_available_with_zero_count_exits_zero(self):
        body = {"status": "results_available", "total_problems": 0}
        result = {
            "status": "results_available",
            "clean": jb_inspect.classify_status_body_clean(body),
            "total_problems": 0,
        }

        self.assertEqual(jb_inspect.classify_status_exit(result), 0)

    def test_run_wait_blocker_overrides_plugin_green_verdict(self):
        problems = {
            "status": "results_available",
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
        }
        wait = {"timed_out": True}

        summary = jb_inspect.summarize_problems({}, {}, problems)
        summary["wait"] = wait
        summary["status"] = jb_inspect.classify_run_status(wait, problems)
        jb_inspect.apply_verdict(summary)

        self.assertEqual(summary["status"], "timed_out")
        self.assertEqual(summary["verdict"], "UNKNOWN")
        self.assertEqual(summary["verdict_reason"], "timeout")
        self.assertEqual(jb_inspect.classify_run_exit(summary), 1)

    def test_text_only_override_recovers_settled_semantic_coverage_wait_timeout(self):
        text_only_file = {
            "path": "/tmp/View.swift",
            "valid": True,
            "directory": False,
            "file_type": "TextMate",
            "psi_language": "textmate",
            "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
            "in_content": True,
            "reasons": ["non_semantic_fallback"],
        }
        problems = {
            "status": "results_available",
            "snapshot_outcome": "clean_confirmed",
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
            "capture_diagnostic": {
                "scope_file_resolved_count": 1,
                "scope_file_diagnostics": [text_only_file],
                "scope_file_diagnostics_complete": True,
                "scope_file_semantic_evidence_complete": True,
                "scope_file_semantic_coverage": {
                    "schema_version": 1,
                    "evaluated_file_count": 1,
                    "unproven_file_count": 0,
                    "missing_file_count": 1,
                    "reason_counts": {"non_semantic_fallback": 1},
                    "missing_files": [text_only_file],
                    "metadata_file_count": 0,
                    "metadata_files": [],
                },
            },
        }
        wait = {
            "timed_out": True,
            "wait_completed": False,
            "completion_reason": "timeout",
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
        }

        summary = jb_inspect.summarize_problems({}, {}, problems, allow_text_only_coverage=True)
        summary["wait"] = wait
        summary["status"] = jb_inspect.classify_run_status(wait, problems)
        jb_inspect.apply_verdict(summary)

        self.assertEqual(summary["status"], "clean")
        self.assertEqual(summary["verdict"], "GREEN")
        self.assertEqual(summary["verdict_reason"], "text_only_coverage_allowed")
        self.assertTrue(summary["semantic_coverage_wait_timeout_recovered"])
        self.assertEqual(jb_inspect.classify_run_exit(summary), 0)

    def test_text_only_override_does_not_recover_running_wait_timeout(self):
        payload = {
            "status": "timed_out",
            "clean": True,
            "snapshot_outcome": "clean_confirmed",
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
            "semantic_coverage": {"status": "text_only_allowed"},
            "wait": {
                "status": "running",
                "timed_out": True,
                "wait_completed": False,
                "completion_reason": "timeout",
                "inspection_verdict": "UNKNOWN",
                "inspection_verdict_reason": "inspection_still_running",
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["status"], "timed_out")
        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "timeout")
        self.assertNotIn("semantic_coverage_wait_timeout_recovered", payload)

    def test_text_only_override_does_not_recover_transport_timeout(self):
        payload = {
            "status": "error",
            "error_reason": "inspection_api_timeout",
            "clean": True,
            "snapshot_outcome": "clean_confirmed",
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
            "semantic_coverage": {"status": "text_only_allowed"},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "inspection_api_timeout")
        self.assertNotIn("semantic_coverage_wait_timeout_recovered", payload)

    def test_status_findings_is_usable_but_not_clean(self):
        body = {"status": "findings"}
        result = {"status": "findings", "clean": jb_inspect.classify_status_body_clean(body)}
        self.assertFalse(result["clean"])
        self.assertEqual(jb_inspect.classify_status_exit(result), 0)

    def test_status_command_surfaces_blocker_flags(self):
        def fake_resolve_route(args, context):
            return {"port": 63343, "project_key": "path:/tmp/example"}

        def fake_call_endpoint(route, endpoint, params, timeout=None):
            return {
                "status": "findings",
                "is_scanning": True,
                "indexing": True,
                "inspection_in_progress": True,
                "session_drift": True,
                "ambiguous": True,
                "unavailable": True,
                "capture_incomplete": True,
                "results_may_be_stale": True,
                "timed_out": True,
            }

        original_resolve_route = jb_inspect.resolve_route
        original_call_endpoint = jb_inspect.call_endpoint
        jb_inspect.resolve_route = fake_resolve_route
        jb_inspect.call_endpoint = fake_call_endpoint
        try:
            result = jb_inspect.command_status(
                Namespace(
                    project_key=None,
                    session_id=None,
                    project_path=None,
                    worktree_path=None,
                    cwd=None,
                    project=None,
                    ide=None,
                ),
                {},
            )
        finally:
            jb_inspect.resolve_route = original_resolve_route
            jb_inspect.call_endpoint = original_call_endpoint

        self.assertEqual(result["status"], "findings")
        self.assertFalse(result["clean"])
        for flag in (
            "is_scanning",
            "indexing",
            "inspection_in_progress",
            "session_drift",
            "ambiguous",
            "unavailable",
            "capture_incomplete",
            "results_may_be_stale",
            "timed_out",
        ):
            with self.subTest(flag=flag):
                self.assertIs(result[flag], True)

    def test_status_command_preserves_plugin_verdict(self):
        def fake_resolve_route(args, context):
            return {"port": 63343, "project_key": "path:/tmp/example"}

        def fake_call_endpoint(route, endpoint, params, timeout=None):
            return {
                "has_inspection_results": True,
                "total_problems": 2,
                "inspection_verdict": "RED",
                "inspection_verdict_reason": "actionable_findings",
                "inspection_verdict_message": "Plugin found problems.",
                "inspection_verdict_next_action": "Fix them.",
            }

        original_resolve_route = jb_inspect.resolve_route
        original_call_endpoint = jb_inspect.call_endpoint
        jb_inspect.resolve_route = fake_resolve_route
        jb_inspect.call_endpoint = fake_call_endpoint
        try:
            result = jb_inspect.command_status(
                Namespace(
                    project_key=None,
                    session_id=None,
                    project_path=None,
                    worktree_path=None,
                    cwd=None,
                    project=None,
                    ide=None,
                ),
                {},
            )
        finally:
            jb_inspect.resolve_route = original_resolve_route
            jb_inspect.call_endpoint = original_call_endpoint

        self.assertEqual(result["status"], "results_available")
        self.assertFalse(result["clean"])
        self.assertEqual(result["verdict"], "RED")
        self.assertEqual(result["verdict_reason"], "actionable_findings")

    def test_status_results_available_without_zero_count_is_unknown(self):
        payload = {"status": "results_available", "clean": False, "problems": []}

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")

    def test_status_usable_values_with_blocker_flags_exit_nonzero(self):
        blocker_flags = (
            "is_scanning",
            "indexing",
            "inspection_in_progress",
            "session_drift",
            "ambiguous",
            "unavailable",
            "capture_incomplete",
            "results_may_be_stale",
            "timed_out",
        )
        for flag in blocker_flags:
            with self.subTest(flag=flag):
                body = {"status": "findings", flag: True}
                result = {
                    "status": "findings",
                    "clean": jb_inspect.classify_status_body_clean(body),
                    flag: True,
                }
                self.assertFalse(result["clean"])
                self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_unknown_explicit_values_exit_nonzero(self):
        for status in ("archived", "running", "failed", "cancelled", "pending_results"):
            with self.subTest(status=status):
                body = {"status": status}
                result = {"status": status, "clean": jb_inspect.classify_status_body_clean(body)}
                self.assertEqual(jb_inspect.classify_status_exit(result), 1)

    def test_status_unknown_explicit_value_ignores_cached_clean_flags(self):
        body = {"status": "failed", "clean_inspection": True, "has_inspection_results": True}
        result = {"status": "failed", "clean": jb_inspect.classify_status_body_clean(body)}
        self.assertEqual(jb_inspect.classify_status_exit(result), 1)


class NestedFieldTest(unittest.TestCase):
    MALFORMED_VALUES = (None, "text", 1, True, ("tuple",))

    def test_dict_field_returns_the_nested_dict_so_updates_reach_the_payload(self):
        payload = {"retry_policy": {"retry": True}}

        jb_inspect.dict_field(payload, "retry_policy")["max_attempts"] = 2

        self.assertEqual(payload["retry_policy"], {"retry": True, "max_attempts": 2})

    def test_dict_field_gives_a_fresh_empty_dict_for_missing_or_malformed_values(self):
        for value in (*self.MALFORMED_VALUES, ["retry"]):
            with self.subTest(value=value):
                payload = {"retry_policy": value}
                jb_inspect.dict_field(payload, "retry_policy")["retry"] = True
                self.assertEqual(jb_inspect.dict_field(payload, "retry_policy"), {})
                self.assertEqual(payload, {"retry_policy": value})
        self.assertEqual(jb_inspect.dict_field({}, "retry_policy"), {})

    def test_list_field_returns_the_nested_list_or_a_fresh_empty_list(self):
        problems = [{"file": "a.py"}]
        self.assertIs(jb_inspect.list_field({"problems": problems}, "problems"), problems)
        for value in (*self.MALFORMED_VALUES, {"file": "a.py"}):
            with self.subTest(value=value):
                payload = {"problems": value}
                jb_inspect.list_field(payload, "problems").append({"file": "b.py"})
                self.assertEqual(jb_inspect.list_field(payload, "problems"), [])
        self.assertEqual(jb_inspect.list_field({}, "problems"), [])


class EndpointUtilityTest(unittest.TestCase):
    def test_problems_params_passes_include_stale_when_requested(self):
        args = Namespace(
            project_key=None,
            session_id=None,
            project_path=None,
            worktree_path=None,
            cwd=None,
            project=None,
            ide=None,
            scope=None,
            severity="all",
            problem_type="all",
            file_pattern="all",
            limit=100,
            offset=0,
            include_stale=True,
        )

        params = jb_inspect.problems_params(args, {"scope": "changed_files"}, {})

        self.assertEqual(params["include_stale"], "true")

    def test_summarize_problems_withholds_normal_totals_for_stale_default(self):
        body = {
            "status": "stale_results",
            "results_may_be_stale": True,
            "cached_total_problems": 3,
            "cached_problems_shown": 0,
            "stale_reasons": ["project_changed_since_inspection"],
            "snapshot_change_kind": "snapshot_predates_current_trigger",
            "snapshot_run_id": 41,
        }

        summary = jb_inspect.summarize_problems({}, {}, body)

        self.assertEqual(summary["status"], "stale_results")
        self.assertFalse(summary["clean"])
        self.assertTrue(summary["results_may_be_stale"])
        self.assertEqual(summary["cached_total_problems"], 3)
        self.assertEqual(summary["cached_problems_shown"], 0)
        self.assertEqual(summary["snapshot_change_kind"], "snapshot_predates_current_trigger")
        self.assertEqual(summary["snapshot_run_id"], 41)
        self.assertNotIn("total_problems", summary)
        self.assertNotIn("problems_shown", summary)

    def test_summarize_problems_carries_capture_diagnostic(self):
        diagnostic = {
            "exit_reason": "deadline",
            "view_ready_ok": False,
            "successful_extraction_count": 2,
        }
        body = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_diagnostic": diagnostic,
        }

        summary = jb_inspect.summarize_problems({}, {}, body)

        self.assertEqual(summary["capture_diagnostic"], diagnostic)

    def test_summarize_problems_applies_text_only_coverage_override(self):
        body = {
            "status": "results_available",
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/example.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "textmate",
                    "psi_language": "textmate",
                    "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        summary = jb_inspect.summarize_problems({}, {}, body, allow_text_only_coverage=True)

        self.assertTrue(summary["allow_text_only_coverage"])
        self.assertEqual(summary["verdict"], "GREEN")
        self.assertEqual(summary["verdict_reason"], "text_only_coverage_allowed")
        self.assertEqual(summary["semantic_coverage"]["status"], "text_only_allowed")

    def test_summarize_problems_keeps_cached_stale_findings_separate(self):
        body = {
            "status": "stale_results",
            "results_may_be_stale": True,
            "include_stale": True,
            "cached_total_problems": 1,
            "cached_problems_shown": 1,
            "problems": [{"description": "Cached finding"}],
        }

        summary = jb_inspect.summarize_problems({}, {}, body)

        self.assertEqual(summary["status"], "stale_results")
        self.assertFalse(summary["clean"])
        self.assertTrue(summary["include_stale"])
        self.assertEqual(summary["cached_total_problems"], 1)
        self.assertEqual(summary["cached_problems_shown"], 1)
        self.assertEqual(summary["problems"], [{"description": "Cached finding"}])
        self.assertEqual(jb_inspect.classify_problems_exit(summary), 1)

    def test_summarize_problems_uses_total_for_empty_page_findings(self):
        body = {
            "status": "results_available",
            "total_problems": 5,
            "problems_shown": 0,
            "problems": [],
        }

        summary = jb_inspect.summarize_problems({}, {}, body)

        self.assertFalse(summary["clean"])
        self.assertEqual(summary["total_problems"], 5)
        self.assertEqual(summary["verdict"], "RED")
        self.assertEqual(summary["verdict_reason"], "actionable_findings")
        self.assertEqual(jb_inspect.classify_problems_exit(summary), 1)

    def test_summarize_problems_proof_failure_overrides_plugin_green_verdict(self):
        body = {
            "status": "results_available",
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "clean_confirmed",
            "proof_failures": ["resolved_project_path does not match requested_path"],
        }

        summary = jb_inspect.summarize_problems({}, {}, body)

        self.assertEqual(summary["verdict"], "UNKNOWN")
        self.assertEqual(summary["verdict_reason"], "inspection_proof_failed")
        self.assertEqual(summary["proof_failures"], ["resolved_project_path does not match requested_path"])

    def test_command_problems_preserves_requested_include_stale(self):
        calls = []

        def fake_resolve_route(args, context):
            return {"port": 63343, "project_key": "path:/tmp/example"}

        def fake_call_endpoint(route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            return {
                "status": "stale_results",
                "results_may_be_stale": True,
                "cached_total_problems": 1,
                "cached_problems_shown": 1,
                "problems": [{"description": "Cached finding"}],
            }

        original_resolve_route = jb_inspect.resolve_route
        original_call_endpoint = jb_inspect.call_endpoint
        jb_inspect.resolve_route = fake_resolve_route
        jb_inspect.call_endpoint = fake_call_endpoint
        try:
            result = jb_inspect.command_problems(
                Namespace(
                    project_key=None,
                    session_id=None,
                    project_path=None,
                    worktree_path=None,
                    cwd=None,
                    project=None,
                    ide=None,
                    scope=None,
                    severity="all",
                    problem_type="all",
                    file_pattern="all",
                    limit=100,
                    offset=0,
                    include_stale=True,
                ),
                {},
            )
        finally:
            jb_inspect.resolve_route = original_resolve_route
            jb_inspect.call_endpoint = original_call_endpoint

        self.assertEqual(calls[0][1]["include_stale"], "true")
        self.assertTrue(result["include_stale"])

    def test_command_problems_enriches_endpoint_error_with_context_and_route(self):
        route = {
            "port": 63343,
            "project_key": "path:/tmp/example",
            "base_path": "/tmp/example",
        }
        context = {
            "repo_path": "/tmp/example",
            "worktree_root": "/tmp/example",
            "project_path": "/tmp/example",
            "lifecycle_target_path": "/tmp/example",
            "scope": "changed_files",
        }

        def fake_resolve_route(args, current_context):
            return route

        def fake_call_endpoint(current_route, endpoint, params, timeout=None):
            raise jb_inspect.InspectError(
                "Inspection API returned invalid JSON: boom",
                3,
                {"error_reason": "invalid_api_response"},
            )

        original_resolve_route = jb_inspect.resolve_route
        original_call_endpoint = jb_inspect.call_endpoint
        jb_inspect.resolve_route = fake_resolve_route
        jb_inspect.call_endpoint = fake_call_endpoint
        try:
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.command_problems(
                    Namespace(
                        project_key=None,
                        session_id=None,
                        project_path=None,
                        worktree_path=None,
                        cwd=None,
                        project=None,
                        ide=None,
                        scope=None,
                        severity="all",
                        problem_type="all",
                        file_pattern="all",
                        limit=100,
                        offset=0,
                        include_stale=False,
                    ),
                    context,
                )
        finally:
            jb_inspect.resolve_route = original_resolve_route
            jb_inspect.call_endpoint = original_call_endpoint

        self.assertEqual(raised.exception.payload["context"]["repo_path"], "/tmp/example")
        self.assertEqual(raised.exception.payload["route"], route)
        self.assertEqual(raised.exception.payload["endpoint"], "problems")

    def test_wait_http_timeout_exceeds_plugin_timeout(self):
        self.assertEqual(jb_inspect.wait_http_timeout(60_000), 65.0)

    def test_call_endpoint_can_read_port_from_base_url(self):
        calls = []

        def fake_http_get(port, endpoint, params, timeout):
            calls.append((port, endpoint, params, timeout))
            return jb_inspect.HttpResult(200, {"ok": True}, "http://localhost:63343/api/inspection/status")

        original = jb_inspect.http_get
        jb_inspect.http_get = fake_http_get
        try:
            body = jb_inspect.call_endpoint(
                {"base_url": "http://localhost:63343/api/inspection"},
                "status",
                {"project_key": "path:/tmp/example"},
                timeout=12.5,
            )
        finally:
            jb_inspect.http_get = original

        self.assertEqual(body, {"ok": True})
        self.assertEqual(calls, [(63343, "status", {"project_key": "path:/tmp/example"}, 12.5)])

    def test_http_get_uses_numeric_loopback_to_avoid_localhost_wildcard_collisions(self):
        captured = []

        class FakeResponse:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self):
                return b'{"status":"ok"}'

        def fake_urlopen(request, timeout):
            captured.append((request.full_url, timeout))
            return FakeResponse()

        original_urlopen = jb_inspect.urllib.request.urlopen
        jb_inspect.urllib.request.urlopen = fake_urlopen
        try:
            result = jb_inspect.http_get(63345, "identity", {"project_key": "path:/tmp/example"})
        finally:
            jb_inspect.urllib.request.urlopen = original_urlopen

        expected_prefix = "http://127.0.0.1:63345" + "/api" + "/inspection/identity?"
        parsed_url = urllib.parse.urlparse(captured[0][0])
        self.assertEqual(result.body, {"status": "ok"})
        self.assertTrue(captured[0][0].startswith(expected_prefix))
        self.assertEqual(parsed_url.scheme, "http")
        self.assertEqual(parsed_url.hostname, "127.0.0.1")
        self.assertEqual(parsed_url.port, 63345)

    def test_status_command_passes_route_project_key_and_session_id(self):
        calls = []

        def fake_resolve_route(args, context):
            return {
                "port": 63343,
                "project_key": "path:/tmp/example",
                "session_id": "session-1",
                "base_path": "/tmp/example",
            }

        def fake_call_endpoint(route, endpoint, params, timeout=None):
            calls.append((route, endpoint, params, timeout))
            return {"clean_inspection": True, "is_scanning": False}

        original_resolve_route = jb_inspect.resolve_route
        original_call_endpoint = jb_inspect.call_endpoint
        jb_inspect.resolve_route = fake_resolve_route
        jb_inspect.call_endpoint = fake_call_endpoint
        try:
            result = jb_inspect.command_status(
                Namespace(
                    project_key=None,
                    session_id=None,
                    project_path=None,
                    worktree_path=None,
                    cwd=None,
                    project=None,
                    ide=None,
                ),
                {"ide": "WebStorm"},
            )
        finally:
            jb_inspect.resolve_route = original_resolve_route
            jb_inspect.call_endpoint = original_call_endpoint

        self.assertEqual(result["status"], "clean")
        self.assertEqual(result["clean"], True)
        self.assertEqual(calls[0][1], "status")
        self.assertEqual(
            calls[0][2],
            {
                "project_key": "path:/tmp/example",
                "session_id": "session-1",
                "project_path": None,
                "worktree_path": None,
                "cwd": None,
                "project": None,
                "ide": "WebStorm",
            },
        )


class AttributionContractTest(unittest.TestCase):
    def test_golden_unknown_and_cleanup_cases_have_bounded_attribution(self):
        for case in attribution_cases():
            with self.subTest(case=case["name"]):
                payload = json.loads(json.dumps(case["payload"]))
                jb_inspect.apply_verdict(payload)

                attribution = payload["inspection_attribution"]
                expected = case["expected"]
                self.assertEqual(attribution["schema_version"], 1)
                self.assertEqual(attribution["classification"], expected["classification"])
                self.assertEqual(attribution["code"], expected["code"])
                self.assertEqual(attribution["phase"], expected["phase"])
                self.assertEqual(payload["bucket"], expected["helper_bucket"])
                self.assertEqual(payload["attribution_class"], expected["classification"])
                self.assertEqual(payload["failure_phase"], expected["phase"])
                self.assertIn("helper_revision", attribution)
                self.assertNotIn("close_token", json.dumps(payload))

    def test_endpoint_failure_preserves_http_response_attribution(self):
        case = next(item for item in attribution_cases() if item["name"] == "plugin-http-500")
        error_payload = dict(case["payload"])
        error_payload.update(
            {
                "endpoint": "status",
                "http_status": 500,
                "response_code": "inspection_api_http_error",
            }
        )

        result = jb_inspect.inspection_endpoint_failure_result(
            jb_inspect.InspectError("HTTP 500 from inspection API", 3, error_payload),
            {"project_key": "path:/repo"},
            {"run_id": 42},
            {"inspection_in_progress": False},
            None,
        )

        self.assertEqual(result["endpoint"], "status")
        self.assertEqual(result["http_status"], 500)
        self.assertEqual(result["response_code"], "inspection_api_http_error")
        self.assertEqual(result["inspection_attribution"]["request_id"], "11111111-1111-4111-8111-111111111111")
        self.assertEqual(result["client_run_id"], "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    def test_malformed_response_preserves_request_context(self):
        with self.assertRaises(jb_inspect.InspectError) as raised:
            jb_inspect.parse_http_json(
                b"{",
                "problems",
                200,
                "ffffffff-ffff-4fff-8fff-ffffffffffff",
            )

        payload = raised.exception.payload
        self.assertEqual(payload["endpoint"], "problems")
        self.assertEqual(payload["http_status"], 200)
        self.assertEqual(payload["response_code"], "invalid_api_response")
        self.assertEqual(payload["client_run_id"], "ffffffff-ffff-4fff-8fff-ffffffffffff")
        result = jb_inspect.inspection_endpoint_failure_result(raised.exception, {}, {}, {}, None)
        compact = jb_inspect.compact_agent_result_payload(result, 1)
        self.assertEqual(compact["diagnostic"]["http_status"], payload["http_status"])
        self.assertEqual(compact["diagnostic"]["endpoint"], payload["endpoint"])

    def test_compact_local_timeout_has_no_http_response_code(self):
        payload = {"status": "error", "error_reason": "timeout"}
        jb_inspect.apply_verdict(payload)
        compact = jb_inspect.compact_agent_result_payload(payload, 1)
        self.assertIsNone(compact["diagnostic"]["http_status"])
        self.assertIsNone(compact["diagnostic"]["response_code"])

    def test_broad_non_json_http_failure_survives_compact_lane_report(self):
        files = [f"/repo/src/module_{index}.py" for index in range(160)]
        selected = [{"file": path.removeprefix("/repo/"), "absolute_path": path} for path in files]
        lane = jb_inspect.InspectionLane("python", "PyCharm", True, ("**/*.py",), (), None)
        context = {"worktree_root": "/repo", "scope": "files"}
        for status in (414, 500):
            with self.subTest(http_status=status):
                response = jb_inspect.urllib.error.HTTPError(
                    "http://localhost/api/inspection/trigger", status, "HTTP failure", None,
                    io.BytesIO(b"<html>transport failure</html>"),
                )
                with (
                    patch.object(urllib.request, "urlopen", side_effect=response) as request,
                    self.assertRaises(jb_inspect.InspectError) as raised,
                ):
                    jb_inspect.http_get(63342, "trigger", {"scope": "files", "files": ",".join(files)})
                sent = request.call_args.args[0]
                self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(sent.full_url).query)["files"], [",".join(files)])
                result = jb_inspect.inspection_endpoint_failure_result(
                    raised.exception, {"project_key": "path:/repo"}, {}, {}, None,
                )
                result["context"] = context
                result["cleanup"] = {"status": "closed"}
                compact_lane = jb_inspect.compact_inspection_lane_result(lane, 0, context, selected, result)
                compact = jb_inspect.compact_agent_result_payload({
                    "lane_results": [compact_lane],
                    "lane_selection": {"scope": "whole_project"},
                }, 1)
                diagnostic = compact["lanes"][0]["diagnostic"]
                self.assertEqual(diagnostic["endpoint"], "trigger")
                self.assertEqual(diagnostic["http_status"], status)
                self.assertEqual(diagnostic["response_code"], "invalid_api_response")
                self.assertEqual(compact["agent_result"]["verdict"], "UNKNOWN")
                self.assertFalse(compact["agent_result"]["retry_policy"]["retry"])
                self.assertEqual(compact["lanes"][0]["file_count"], len(files))
                self.assertEqual(compact["lanes"][0]["cleanup"]["status"], "closed")
                self.assertNotIn("<html>", json.dumps(compact))

    def test_unattributed_unknown_is_mechanically_a_tool_failure(self):
        payload = {"status": "mystery", "command": "get-status"}

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertTrue(payload["unattributed_unknown"])
        self.assertEqual(payload["inspection_attribution"]["classification"], "unattributed")
        self.assertEqual(payload["inspection_attribution"]["code"], "unattributed_unknown")
        self.assertEqual(payload["bucket"], "tool_bug")

    def test_prestart_configuration_attribution_records_cleanup_not_needed(self):
        payload = {
            "command": "inspect-closeout",
            "status": "error",
            "error_reason": "ide_selection_required",
            "context": {"client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"},
            "cleanup": {},
        }

        jb_inspect.apply_verdict(payload)

        attribution = payload["inspection_attribution"]
        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(attribution["source"], "helper")
        self.assertEqual(attribution["classification"], "configuration_blocked")
        self.assertEqual(attribution["code"], "ide_selection_required")
        self.assertEqual(attribution["phase"], "selection")
        self.assertEqual(attribution["cleanup_status"], "not_needed")
        self.assertEqual(attribution["cleanup_reason"], "inspection_not_started")

    def test_missing_project_content_roots_is_configuration_blocked(self):
        payload = {
            "command": "inspect-closeout",
            "status": "error",
            "error_reason": jb_inspect.PROJECT_CONTENT_ROOTS_MISSING_REASON,
        }

        jb_inspect.apply_verdict(payload)

        attribution = payload["inspection_attribution"]
        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], jb_inspect.PROJECT_CONTENT_ROOTS_MISSING_REASON)
        self.assertEqual(attribution["classification"], "configuration_blocked")
        self.assertEqual(attribution["phase"], "readiness_wait")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertNotIn("unattributed_unknown", payload)

    def test_outcome_record_keeps_provenance_without_paths_or_tokens(self):
        payload = {
            "status": "error",
            "error_reason": "inspection_api_http_error",
            "context": {
                "repo_path": "/private/repo",
                "worktree_root": "/private/worktree",
                "scope": "changed_files",
                "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            },
            "route": {
                "project_name": "fixture",
                "project_key": "path:/private/worktree",
                "base_path": "/private/worktree",
                "project_instance_id": "project-instance-fixture",
                "session_id": "session-fixture",
                "ide": {
                    "name": "IntelliJ IDEA",
                    "product_code": "IU",
                    "version": "2026.1.4",
                    "plugin_version": "1.13.17",
                    "plugin_build_fingerprint": "fixture-fingerprint",
                },
            },
            "inspection_attribution": {
                "schema_version": 1,
                "source": "plugin",
                "classification": "tool_caused",
                "code": "inspection_api_http_error",
                "phase": "status",
                "request_id": "11111111-1111-4111-8111-111111111111",
                "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            },
            "capture_diagnostic": {
                "scope_directory_requested": "/private/worktree/src",
            },
            "close_token": "must-not-leak",
        }
        jb_inspect.apply_verdict(payload)

        record = jb_inspect.outcome_log_record(payload, 3)
        unknown_record = jb_inspect.unknown_log_record(payload)
        serialized = json.dumps(record, sort_keys=True)
        unknown_serialized = json.dumps(unknown_record, sort_keys=True)

        self.assertEqual(record["helper_revision"], jb_inspect.helper_revision())
        self.assertEqual(record["plugin_build_fingerprint"], "fixture-fingerprint")
        self.assertEqual(record["ide_product_code"], "IU")
        self.assertEqual(record["ide_version"], "2026.1.4")
        self.assertEqual(record["failure_phase"], "status")
        self.assertEqual(record["attribution_class"], "tool_caused")
        self.assertEqual(record["request_id"], "11111111-1111-4111-8111-111111111111")
        self.assertIn("repo_path_hash", record)
        self.assertIn("worktree_root_hash", record)
        self.assertNotIn("/private/", serialized)
        self.assertNotIn("/private/", unknown_serialized)
        self.assertNotIn("must-not-leak", serialized)
        self.assertIn("scope_directory_requested_hash", unknown_record["capture_diagnostic"])

    def test_helper_and_manifest_revisions_use_full_content_sha256(self):
        self.assertRegex(jb_inspect.helper_revision(), r"^sha256:[0-9a-f]{64}$")
        self.assertRegex(jb_inspect.stable_value_hash("repo") or "", r"^sha256:[0-9a-f]{64}$")
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "deployment.json"
            manifest.write_text('{"revision":1}\n', encoding="utf-8")
            first = jb_inspect.file_content_sha256(manifest)
            manifest.write_text('{"revision":2}\n', encoding="utf-8")
            second = jb_inspect.file_content_sha256(manifest)

        self.assertRegex(first or "", r"^sha256:[0-9a-f]{64}$")
        self.assertRegex(second or "", r"^sha256:[0-9a-f]{64}$")
        self.assertNotEqual(first, second)

    def test_green_and_red_preserve_plugin_attribution_and_authoritative_channel(self):
        for verdict, total_problems, reason in (
            ("GREEN", 0, "no_matching_findings"),
            ("RED", 1, "actionable_findings"),
        ):
            with self.subTest(verdict=verdict):
                payload = {
                    "status": "results_available",
                    "total_problems": total_problems,
                    "problems": [] if total_problems == 0 else [{"description": "fixture"}],
                    "context": {
                        "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "ide_selection": {"channel": "stable"},
                    },
                    "route": {
                        "session_id": "session-1",
                        "project_instance_id": "project-1",
                        "ide": {
                            "channel": "eap",
                            "product_code": "PY",
                            "version": "2026.2",
                            "plugin_version": "1.13.17",
                            "plugin_build_fingerprint": "a" * 40 + "-clean",
                        },
                    },
                    "inspection_attribution": {
                        "schema_version": 1,
                        "source": "plugin",
                        "classification": "decisive",
                        "code": reason,
                        "phase": "problems",
                        "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "inspection_run_id": 7,
                        "plugin_version": "1.13.17",
                        "plugin_build_fingerprint": "a" * 40 + "-clean",
                        "ide_product_code": "PY",
                        "ide_version": "2026.2",
                        "ide_channel": "eap",
                    },
                    "cleanup": {"status": "closed"},
                }

                jb_inspect.apply_verdict(payload)

                attribution = payload["inspection_attribution"]
                self.assertEqual(payload["verdict"], verdict)
                self.assertEqual(attribution["source"], "plugin")
                self.assertEqual(attribution["classification"], "decisive")
                self.assertEqual(attribution["ide_channel"], "eap")
                self.assertEqual(attribution["ide_channel_source"], "plugin_attribution")
                self.assertEqual(attribution["helper_revision"], jb_inspect.helper_revision())
                self.assertEqual(attribution["cleanup_status"], "closed")

    def test_local_client_run_id_remains_authoritative_when_plugin_echo_differs(self):
        local_client_run_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        plugin_client_run_id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        payload = {
            "status": "results_available",
            "total_problems": 0,
            "problems": [],
            "context": {
                "repo_path": "/repo",
                "worktree_root": "/repo",
                "client_run_id": local_client_run_id,
            },
            "route": {
                "project_key": "path:/repo",
                "project_instance_id": "project-1",
                "session_id": "session-1",
                "ide": {
                    "product_code": "PY",
                    "version": "2026.2",
                    "channel": "eap",
                    "plugin_version": "1.13.17",
                    "plugin_build_fingerprint": "a" * 40 + "-clean",
                },
            },
            "inspection_attribution": {
                "schema_version": 1,
                "source": "plugin",
                "classification": "decisive",
                "code": "no_matching_findings",
                "phase": "problems",
                "client_run_id": plugin_client_run_id,
                "inspection_run_id": 7,
                "plugin_version": "1.13.17",
                "plugin_build_fingerprint": "a" * 40 + "-clean",
                "ide_product_code": "PY",
                "ide_version": "2026.2",
                "ide_channel": "eap",
            },
            "cleanup": {"status": "closed"},
        }

        jb_inspect.apply_verdict(payload)
        record = jb_inspect.outcome_log_record(payload, 0)

        self.assertEqual(payload["evidence_ids"]["client_run_id"], local_client_run_id)
        self.assertEqual(payload["inspection_attribution"]["client_run_id"], plugin_client_run_id)
        self.assertEqual(record["client_run_id"], local_client_run_id)
        self.assertEqual(record["inspection_attribution"]["client_run_id"], plugin_client_run_id)
        self.assertIn("client_run_id", jb_inspect.plugin_attribution_mismatches(record))

    def test_outcome_record_v2_contains_identity_scope_and_internal_attempts(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "deployment.json"
            manifest.write_text('{"artifact":"fixture"}\n', encoding="utf-8")
            scope_descriptor = {"scope": "changed_files", "include_unversioned": True}
            payload = {
                "command": "inspect-closeout",
                "status": "results_available",
                "total_problems": 0,
                "problems": [],
                "context": {
                    "repo_path": "/repo",
                    "worktree_root": "/repo",
                    "repo_head_sha": "b" * 40,
                    "scope": "changed_files",
                    "scope_descriptor": scope_descriptor,
                    "scope_descriptor_sha256": jb_inspect.canonical_json_sha256(scope_descriptor),
                    "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                },
                "route": {
                    "project_key": "path:/repo",
                    "project_instance_id": "project-1",
                    "session_id": "session-1",
                    "ide": {
                        "product_code": "PY",
                        "version": "2026.2",
                        "channel": "eap",
                        "plugin_version": "1.13.17",
                        "plugin_build_fingerprint": "a" * 40 + "-clean",
                    },
                },
                "inspection_attribution": {
                    "schema_version": 1,
                    "source": "plugin",
                    "classification": "decisive",
                    "code": "no_matching_findings",
                    "phase": "problems",
                    "client_run_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    "inspection_run_id": 7,
                    "plugin_version": "1.13.17",
                    "plugin_build_fingerprint": "a" * 40 + "-clean",
                    "ide_product_code": "PY",
                    "ide_version": "2026.2",
                    "ide_channel": "eap",
                },
                "cleanup": {"status": "closed"},
            }
            jb_inspect.apply_verdict(payload)

            with patch.dict(os.environ, {jb_inspect.DEPLOYMENT_MANIFEST_ENV: str(manifest)}, clear=False):
                record = jb_inspect.outcome_log_record(payload, 0)
            manifest_sha256 = jb_inspect.file_content_sha256(manifest)

        self.assertEqual(record["schema_version"], 2)
        self.assertEqual(record["event_kind"], "inspection_assessment")
        self.assertEqual(record["assessment_id"], "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        self.assertEqual(record["client_run_id"], record["assessment_id"])
        self.assertEqual(record["inspection_run_id"], 7)
        self.assertTrue(record["inspection_started"])
        self.assertEqual(record["repo_head_sha"], "b" * 40)
        self.assertEqual(record["scope_descriptor"], scope_descriptor)
        self.assertEqual(record["scope_descriptor_sha256"], jb_inspect.canonical_json_sha256(scope_descriptor))
        self.assertEqual(record["deployment_manifest_sha256"], manifest_sha256)
        self.assertRegex(record["event_id"], r"^[0-9a-f-]{36}$")
        self.assertRegex(record["timestamp"], r"\.\d{3}Z$")
        self.assertEqual(record["internal_attempts"][-1]["verdict"], "GREEN")
        self.assertTrue(record["internal_attempts"][-1]["terminal"])


class HumanOutputTest(unittest.TestCase):
    def test_verdict_for_clean_payload_is_green(self):
        payload = {"status": "clean", "clean": True, "total_problems": 0, "problems": []}

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "GREEN")
        self.assertEqual(verdict["verdict_reason"], "clean_confirmed")

    def test_verdict_for_current_zero_matching_results_is_green(self):
        payload = {"status": "results_available", "clean": True, "total_problems": 0, "problems": []}

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "GREEN")
        self.assertEqual(verdict["verdict_reason"], "no_matching_findings")

    def test_textmate_scope_overrides_plugin_green_with_semantic_coverage_unknown(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/PlaybackValidation.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "textmate",
                    "psi_language": "textmate",
                    "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "scope_semantic_coverage_missing")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertEqual(payload["attribution_class"], "configuration_blocked")
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 1)
        self.assertEqual(payload["semantic_coverage"]["files"][0]["requested_language_hint"], "swift")
        self.assertIn("language plugins", payload["verdict_next_action"])

        output = io.StringIO()
        with redirect_stdout(output):
            jb_inspect.print_human(payload)
        lines = output.getvalue().splitlines()
        self.assertIn(
            'SEMANTIC_COVERAGE_FILE: path=/tmp/PlaybackValidation.swift language_hint=swift file_type=textmate psi_language=textmate reasons=["non_semantic_fallback"]',
            lines,
        )
        self.assertFalse(any(line.startswith("SEMANTIC_COVERAGE_FILE: classification=") for line in lines))

    def test_mixed_semantic_and_textmate_scope_fails_closed(self):
        payload = {
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/tool.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "Python",
                    "psi_language": "Python",
                    "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
                    "in_content": True,
                    "in_source": False,
                },
                {
                    "path": "/tmp/view.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "TextMate",
                    "psi_language": "textmate",
                    "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content": True,
                    "in_source": False,
                },
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 1)
        self.assertTrue(payload["semantic_coverage"]["files"][0]["path"].endswith("view.swift"))

    def test_semantic_psi_with_in_source_false_stays_green(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/PlaybackValidation.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "NoctuleSwift",
                    "psi_language": "NoctuleSwift",
                    "psi_class": "swift.lang.psi.lt",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertNotIn("semantic_coverage", payload)

    def test_idea_module_plaintext_in_content_is_classified_as_metadata(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/project.iml",
                    "valid": True,
                    "directory": False,
                    "file_type": "IDEA_MODULE",
                    "psi_language": "Plain text",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["verdict_reason"], "project_metadata_coverage_not_required")
        self.assertEqual(payload["semantic_coverage"]["status"], "satisfied")
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 0)
        self.assertEqual(payload["semantic_coverage"]["metadata_file_count"], 1)
        self.assertEqual(payload["semantic_coverage"]["metadata_files"][0]["classification"], "project_metadata")
        self.assertEqual(payload["semantic_coverage"]["files"], [])
        self.assertEqual(
            payload["verdict_next_action"],
            "No inspection action required for classified project metadata.",
        )

        output = io.StringIO()
        with redirect_stdout(output):
            jb_inspect.print_human(payload)
        text = output.getvalue()
        self.assertIn("SEMANTIC_COVERAGE: status=satisfied", text)
        self.assertIn("SEMANTIC_COVERAGE_METADATA_FILE:", text)
        self.assertIn("classification=project_metadata", text)
        self.assertIn("coverage_required=False", text)

    def test_mixed_semantic_scope_with_idea_module_metadata_stays_green(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/tool.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "Python",
                    "psi_language": "Python",
                    "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
                    "in_content": True,
                    "in_source": True,
                },
                {
                    "path": "/tmp/project.iml",
                    "valid": True,
                    "directory": False,
                    "file_type": "IDEA_MODULE",
                    "psi_language": "TEXT",
                    "psi_class": "com.intellij.psi.impl.light.LightPsiFile",
                    "in_content": True,
                    "in_source": False,
                },
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["semantic_coverage"]["status"], "satisfied")
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 0)
        self.assertEqual(payload["semantic_coverage"]["metadata_file_count"], 1)
        metadata_file = payload["semantic_coverage"]["metadata_files"][0]
        self.assertEqual(metadata_file["psi_language"], "TEXT")
        self.assertEqual(metadata_file["psi_class"], "com.intellij.psi.impl.light.LightPsiFile")

    def test_explicitly_excluded_dependency_lockfile_is_classified_as_metadata(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/tool.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "Python",
                    "psi_language": "Python",
                    "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
                    "in_content": True,
                    "in_source": True,
                    "is_excluded": False,
                },
                {
                    "path": "/tmp/uv.lock",
                    "valid": True,
                    "directory": False,
                    "file_type": "PLAIN_TEXT",
                    "psi_language": "TEXT",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": False,
                    "in_source": False,
                    "is_excluded": True,
                    "coverage_role": "excluded_dependency_lockfile",
                },
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["semantic_coverage"]["status"], "satisfied")
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 0)
        self.assertEqual(payload["semantic_coverage"]["metadata_file_count"], 1)
        metadata_file = payload["semantic_coverage"]["metadata_files"][0]
        self.assertEqual(metadata_file["classification"], "excluded_dependency_lockfile")
        self.assertTrue(metadata_file["is_excluded"])

    def test_dependency_lockfile_without_explicit_exclusion_stays_unknown(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/uv.lock",
                    "valid": True,
                    "directory": False,
                    "file_type": "PLAIN_TEXT",
                    "psi_language": "TEXT",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": False,
                    "in_source": False,
                    "is_excluded": False,
                },
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertIn("outside_project_content", payload["semantic_coverage"]["files"][0]["reasons"])

    def test_excluded_lockfile_does_not_hide_source_outside_content(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/uv.lock",
                    "valid": True,
                    "directory": False,
                    "file_type": "PLAIN_TEXT",
                    "psi_language": "TEXT",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": False,
                    "in_source": False,
                    "is_excluded": True,
                    "coverage_role": "excluded_dependency_lockfile",
                },
                {
                    "path": "/tmp/tool.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "Python",
                    "psi_language": "Python",
                    "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
                    "in_content": False,
                    "in_source": False,
                    "is_excluded": False,
                },
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 1)
        self.assertTrue(payload["semantic_coverage"]["files"][0]["path"].endswith("tool.py"))

    def test_malformed_dependency_lockfile_role_fails_closed(self):
        lockfile = {
            "path": "/tmp/uv.lock",
            "valid": True,
            "directory": False,
            "file_type": "PLAIN_TEXT",
            "psi_language": "TEXT",
            "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
            "in_content": False,
            "in_source": False,
            "is_excluded": False,
            "coverage_role": "excluded_dependency_lockfile",
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "capture_diagnostic": scope_capture_diagnostic(lockfile),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertIn("invalid_metadata_role", payload["semantic_coverage"]["files"][0]["reasons"])

    def test_conflicting_dependency_lockfile_roles_fail_closed(self):
        lockfile = {
            "path": "/tmp/uv.lock",
            "valid": True,
            "directory": False,
            "file_type": "PLAIN_TEXT",
            "psi_language": "TEXT",
            "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
            "in_content": False,
            "in_source": False,
            "is_excluded": True,
            "coverage_role": "excluded_dependency_lockfile",
            "classification": "project_metadata",
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "capture_diagnostic": scope_capture_diagnostic(lockfile),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertIn("invalid_metadata_role", payload["semantic_coverage"]["files"][0]["reasons"])

    def test_idea_module_requires_explicit_valid_true(self):
        missing = object()
        for label, valid in (("missing", missing), ("null", None), ("string", "true")):
            with self.subTest(label=label):
                file_diagnostic = {
                    "path": "/tmp/project.iml",
                    "directory": False,
                    "file_type": "IDEA_MODULE",
                    "psi_language": "Plain text",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": True,
                    "in_source": False,
                }
                if valid is not missing:
                    file_diagnostic["valid"] = valid
                payload = {
                    "status": "results_available",
                    "clean": True,
                    "total_problems": 0,
                    "problems": [],
                    "inspection_verdict": "GREEN",
                    "inspection_verdict_reason": "no_matching_findings",
                    "capture_diagnostic": scope_capture_diagnostic(file_diagnostic),
                }

                jb_inspect.apply_verdict(payload)

                self.assertEqual(payload["verdict"], "UNKNOWN")
                self.assertEqual(payload["verdict_reason"], "scope_semantic_coverage_missing")
                self.assertEqual(
                    payload["semantic_coverage"]["files"][0]["reasons"],
                    ["non_semantic_fallback"],
                )

    def test_plaintext_code_scope_still_fails_closed(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/tool.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "PLAIN_TEXT",
                    "psi_language": "Plain text",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "scope_semantic_coverage_missing")
        self.assertEqual(payload["semantic_coverage"]["files"][0]["reasons"], ["non_semantic_fallback"])
        self.assertIn("language plugins", payload["verdict_next_action"])

    def test_plaintext_scope_can_be_explicitly_allowed(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "allow_text_only_coverage": True,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/notes.txt",
                    "valid": True,
                    "directory": False,
                    "file_type": "PLAIN_TEXT",
                    "psi_language": "Plain text",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["verdict_reason"], "text_only_coverage_allowed")
        self.assertEqual(payload["semantic_coverage"]["status"], "text_only_allowed")
        self.assertEqual(payload["attribution_class"], "decisive")
        self.assertEqual(payload["inspection_attribution"]["code"], "text_only_coverage_allowed")
        self.assertEqual(payload["inspection_attribution"]["source"], "helper")
        self.assertIn("explicitly allowed", payload["agent_report"])

    def test_text_only_override_does_not_allow_outside_project_content(self):
        payload = {
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "allow_text_only_coverage": True,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/external.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "Python",
                    "psi_language": "Python",
                    "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
                    "in_content": False,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["semantic_coverage"]["files"][0]["reasons"], ["outside_project_content"])
        self.assertIn("module/content root", payload["verdict_next_action"])
        self.assertNotIn("language plugins", payload["verdict_next_action"])

    def test_idea_module_outside_content_is_not_exempted_by_text_only_override(self):
        payload = {
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "allow_text_only_coverage": True,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/project.iml",
                    "valid": True,
                    "directory": False,
                    "file_type": "IDEA_MODULE",
                    "psi_language": "Plain text",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": False,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(
            payload["semantic_coverage"]["files"][0]["reasons"],
            ["non_semantic_fallback", "outside_project_content"],
        )
        self.assertIn("module/content root", payload["verdict_next_action"])
        self.assertNotIn("language plugins", payload["verdict_next_action"])

    def test_mixed_outside_content_and_textmate_scope_reports_both_actions(self):
        payload = {
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/external.py",
                    "valid": True,
                    "directory": False,
                    "file_type": "Python",
                    "psi_language": "Python",
                    "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
                    "in_content": False,
                    "in_source": False,
                },
                {
                    "path": "/tmp/view.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "TextMate",
                    "psi_language": "textmate",
                    "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content": True,
                    "in_source": False,
                },
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertIn("module/content root", payload["verdict_next_action"])
        self.assertIn("language plugins", payload["verdict_next_action"])
        self.assertIn("--allow-text-only-coverage", payload["verdict_next_action"])

    def test_semantic_coverage_gap_does_not_hide_actionable_red_verdict(self):
        payload = {
            "status": "findings",
            "clean": False,
            "total_problems": 1,
            "problems": [{"description": "Broken"}],
            "inspection_verdict": "RED",
            "inspection_verdict_reason": "actionable_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/view.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "textmate",
                    "psi_language": "textmate",
                    "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "RED")
        self.assertEqual(payload["semantic_coverage"]["status"], "missing")

    def test_project_metadata_classification_does_not_hide_actionable_red_verdict(self):
        payload = {
            "status": "findings",
            "clean": False,
            "total_problems": 1,
            "problems": [{"description": "Broken"}],
            "inspection_verdict": "RED",
            "inspection_verdict_reason": "actionable_findings",
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/project.iml",
                    "valid": True,
                    "directory": False,
                    "file_type": "IDEA_MODULE",
                    "psi_language": "Plain text",
                    "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "RED")
        self.assertEqual(payload["verdict_reason"], "actionable_findings")
        self.assertEqual(payload["semantic_coverage"]["status"], "satisfied")

    def test_verdict_prefers_plugin_provided_contract(self):
        payload = {
            "status": "results_available",
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": "plugin_specific_reason",
            "inspection_verdict_message": "Plugin supplied message.",
            "inspection_verdict_next_action": "Plugin supplied action.",
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "plugin_specific_reason")
        self.assertEqual(verdict["verdict_next_action"], "Plugin supplied action.")

    def test_broad_green_requires_native_proof_capability(self):
        payload = {
            "scope": "whole_project",
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "clean_confirmed",
            "route": {
                "ide": {
                    "plugin_version": "1.13.23",
                    "plugin_build_fingerprint": "a" * 40 + "-dirty",
                }
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "plugin_deployment_mismatch")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertEqual(payload["deployment_mismatch"]["required_version"], 2)
        self.assertIn("Install a plugin", payload["agent_result"]["next_action"])

    def test_broad_green_accepts_native_proof_capability(self):
        payload = {
            "scope": "directory",
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "clean_confirmed",
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "GREEN")
        self.assertEqual(verdict["verdict_reason"], "clean_confirmed")

    def test_verdict_for_findings_payload_is_red(self):
        payload = {"status": "findings", "clean": False, "total_problems": 1, "problems": [{"description": "Broken"}]}

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "RED")
        self.assertEqual(verdict["verdict_reason"], "actionable_findings")

    def test_verdict_for_capture_incomplete_payload_is_unknown_with_guidance(self):
        payload = {
            "status": "capture_incomplete",
            "clean": False,
            "capture_incomplete": True,
            "capture_incomplete_reason": "non_empty_unmapped_tree",
            "total_problems": 0,
            "problems": [],
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "non_empty_unmapped_tree")
        self.assertIn("plugin/helper bug", verdict["verdict_next_action"])

    def test_cleanup_failure_overrides_plugin_green_verdict(self):
        payload = {
            "status": "clean",
            "clean": True,
            "cleanup": {"status": "failed", "reason": "route_missing"},
            "cleanup_failed": True,
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "clean_confirmed",
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "cleanup_failed")

    def test_timeout_overrides_cleanup_failure_reason(self):
        payload = {
            "status": "timed_out",
            "cleanup": {"status": "failed", "reason": "close_failed"},
            "cleanup_failed": True,
            "wait": {"timed_out": True, "inspection_in_progress": True},
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "timeout")
        self.assertIn("larger timeout", verdict["verdict_next_action"])

    def test_proof_failure_overrides_plugin_green_verdict(self):
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "clean_confirmed",
            "proof_failures": [
                "resolved_project_path does not match requested_path",
                "scope_file_count is zero for changed_files",
            ],
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "inspection_proof_failed")

    def test_incomplete_clean_proof_does_not_override_plugin_red_verdict(self):
        for proof_failure in (
            "execution_not_proven",
            jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            jb_inspect.SEMANTIC_COVERAGE_TRUNCATED_REASON,
        ):
            with self.subTest(proof_failure=proof_failure):
                payload = {
                    "status": "results_available",
                    "clean": False,
                    "total_problems": 1,
                    "problems": [{"file": "/tmp/App.kt", "line": 3}],
                    "inspection_verdict": "RED",
                    "inspection_verdict_reason": "actionable_findings",
                    "proof_failures": [proof_failure],
                }

                verdict = jb_inspect.verdict_for_payload(payload)

                self.assertEqual(verdict["verdict"], "RED")
                self.assertEqual(verdict["verdict_reason"], "actionable_findings")

    def test_unexpected_proof_failure_still_overrides_plugin_red_verdict(self):
        payload = {
            "status": "results_available",
            "clean": False,
            "total_problems": 1,
            "problems": [{"file": "/tmp/App.kt", "line": 3}],
            "inspection_verdict": "RED",
            "inspection_verdict_reason": "actionable_findings",
            "proof_failures": ["run_mismatch"],
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "inspection_proof_failed")

    def test_compact_red_result_preserves_incomplete_proof_metadata(self):
        payload = {
            "status": "results_available",
            "clean": False,
            "total_problems": 1,
            "problems": [{"file": "/tmp/App.kt", "line": 3}],
            "inspection_verdict": "RED",
            "inspection_verdict_reason": "actionable_findings",
            "proof_failures": ["execution_not_proven"],
            "inspection_proof": {
                "status": "failed",
                "capture_complete": False,
                "scope": "files",
            },
        }

        jb_inspect.apply_verdict(payload)
        compact = jb_inspect.compact_agent_result_payload(payload, 0)

        self.assertEqual(payload["agent_result"]["proof_failures"], ["execution_not_proven"])
        self.assertEqual(payload["agent_result"]["inspection_proof"]["capture_complete"], False)
        self.assertEqual(compact["proof_failures"], ["execution_not_proven"])
        self.assertEqual(compact["inspection_proof"]["capture_complete"], False)

    def test_cleanup_skipped_overrides_clean_verdict(self):
        payload = {
            "status": "clean",
            "clean": True,
            "cleanup": {"status": "skipped", "reason": "missing_close_token"},
            "cleanup_skipped": True,
        }

        verdict = jb_inspect.verdict_for_payload(payload)

        self.assertEqual(verdict["verdict"], "UNKNOWN")
        self.assertEqual(verdict["verdict_reason"], "cleanup_skipped")

    def test_agent_result_for_retryable_timeout_unknown(self):
        payload = {"status": "timed_out", "timed_out": True}

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "ide_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertEqual(payload["retry_policy"]["max_attempts"], 1)
        self.assertEqual(payload["agent_result"]["bucket"], "ide_not_ready")

    def test_agent_result_for_busy_api_timeout_is_retryable(self):
        payload = {"status": "error", "error_reason": "inspection_api_timeout"}

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "ide_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertIn("busy", payload["agent_result"]["next_action"])

    def test_agent_result_for_tool_bug_unknown_does_not_retry(self):
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "non_empty_unmapped_tree",
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("inconclusive", payload["agent_report"])

    def test_legacy_broad_execution_proof_gap_is_terminal_environment_blocker(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped_reason": "whole_project_execution_not_proven",
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("Update the inspection plugin", payload["agent_result"]["next_action"])

    def test_aborted_native_proof_is_retryable_once_with_consistent_guidance(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped_reason": "native_inspection_not_completed",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "capture_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertIn("retry once", payload["agent_result"]["next_action"])

    def test_native_inspection_block_failure_is_terminal_tool_bug(self):
        payload = {
            "scope": "directory",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped_reason": "native_inspection_failures",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("Stop retrying", payload["agent_result"]["next_action"])

    def test_native_attestation_context_failure_is_environment_blocked(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped_reason": "native_attestation_context_creation_failed",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("reinstall", payload["agent_result"]["next_action"])

    def test_native_unmapped_problem_count_is_terminal_tool_bug(self):
        payload = {
            "scope": "directory",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped_reason": "native_inspection_reported_unmapped_problems",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("Stop retrying", payload["agent_result"]["next_action"])

    def test_native_missing_file_scoped_completion_is_environment_blocked(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped_reason": "native_inspection_no_file_scoped_tools_completed",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("active inspection profile", payload["agent_result"]["next_action"])

    def test_native_incomplete_scope_block_reason_is_environment_blocked(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_skipped": False,
                "execution_proof_block_reason": "native_inspection_scope_incomplete",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("requested scope", payload["agent_result"]["next_action"])

    def test_unproven_c_family_batch_annotators_block_without_retry(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_block_reason": "native_cpp_batch_annotator_unproven",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["agent_result"]["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("C/C++", payload["agent_result"]["next_action"])

    def test_unproven_batch_annotators_and_tool_completions_block_without_retry(self):
        for reason, guidance in (
            ("native_batch_annotator_unproven", "ShellCheck"),
            ("native_tool_completion_unproven", "reported finishing"),
        ):
            with self.subTest(reason=reason):
                payload = {
                    "scope": "whole_project",
                    "status": "capture_incomplete",
                    "capture_incomplete": True,
                    "capture_incomplete_reason": "execution_not_proven",
                    "capture_diagnostic": {"execution_proof_block_reason": reason},
                    "route": {"ide": {"inspection_execution_proof_version": 2}},
                }

                jb_inspect.apply_verdict(payload)

                self.assertEqual(payload["agent_result"]["verdict"], "UNKNOWN")
                self.assertEqual(payload["bucket"], "environment_blocked")
                self.assertFalse(payload["retry_policy"]["retry"])
                self.assertIn(guidance, payload["agent_result"]["next_action"])

    def test_unproven_batch_annotator_unknown_names_blocking_tool_and_file(self):
        for reason in ("native_batch_annotator_unproven", "native_cpp_batch_annotator_unproven"):
            with self.subTest(reason=reason):
                blocking = [
                    {"tool": "ShellCheck", "file": "/repo/scripts/deploy.sh"},
                    {"tool": "RadInspection", "file": "/repo/src/main.cpp"},
                ]
                payload = {
                    "scope": "files",
                    "status": "capture_incomplete",
                    "capture_incomplete": True,
                    "capture_incomplete_reason": "execution_not_proven",
                    "capture_diagnostic": {
                        "execution_proof_block_reason": reason,
                        "execution_proof_unproven_batch_annotators": blocking,
                    },
                    "route": {"ide": {"inspection_execution_proof_version": 2}},
                }

                jb_inspect.apply_verdict(payload)

                self.assertEqual(payload["agent_result"]["verdict"], "UNKNOWN")
                self.assertEqual(payload["agent_result"]["unproven_batch_annotators"], blocking)
                self.assertIn("ShellCheck on /repo/scripts/deploy.sh", payload["agent_result"]["next_action"])
                self.assertIn("RadInspection on /repo/src/main.cpp", payload["agent_result"]["next_action"])

    def test_delegated_batch_inspection_retains_supported_recovery_without_retry(self):
        payload = {
            "scope": "files",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_block_reason": "batch_inspection_delegated_to_editor_service",
                "batch_inspection_delegations": [{"tool": "TypeScriptUnresolvedReference", "file": "/repo/broken.ts"}],
            },
            "inspection_verdict_next_action": "Use the isolated native inspector; preserve the enabled inspection.",
            "route": {"ide": {"inspection_execution_proof_version": jb_inspect.NATIVE_BROAD_SCOPE_PROOF_VERSION}},
        }

        diagnostic = payload["capture_diagnostic"]
        diagnostic["batch_inspection_delegations_omitted_count"] = 1
        diagnostic["batch_inspection_delegation_count"] = len(diagnostic["batch_inspection_delegations"]) + diagnostic["batch_inspection_delegations_omitted_count"]

        jb_inspect.apply_verdict(payload)

        compact = jb_inspect.compact_agent_result_payload(payload, helper_exit_code=1)
        self.assertEqual(compact["agent_result"]["verdict"], "UNKNOWN")
        self.assertEqual(compact["agent_result"]["bucket"], "environment_blocked")
        self.assertFalse(compact["agent_result"]["retry_policy"]["retry"])
        self.assertEqual(compact["agent_result"]["next_action"], payload["inspection_verdict_next_action"])
        self.assertEqual(compact["agent_result"]["batch_inspection_delegations"], payload["capture_diagnostic"]["batch_inspection_delegations"])
        self.assertEqual(compact["diagnostic"]["attribution_class"], "configuration_blocked")
        self.assertFalse(payload.get("unattributed_unknown", False))
        lane = jb_inspect.compact_inspection_lane_result(
            jb_inspect.InspectionLane("frontend", "WebStorm", True, ("**/*.ts",), (), None),
            0, {"worktree_root": "/repo"}, [{"file": "broken.ts", "absolute_path": "/repo/broken.ts"}], payload,
        )
        multi = jb_inspect.compact_multi_lane_agent_result_payload({"lane_results": [lane]}, helper_exit_code=1)
        self.assertEqual(multi["lanes"][0]["batch_inspection_delegations"], compact["agent_result"]["batch_inspection_delegations"])
        self.assertEqual(multi["lanes"][0]["next_action"], compact["agent_result"]["next_action"])
        for field in ("batch_inspection_delegation_count", "batch_inspection_delegations_omitted_count"):
            self.assertEqual(compact["agent_result"][field], diagnostic[field])
            self.assertEqual(multi["lanes"][0][field], diagnostic[field])

    def test_proven_findings_retain_failed_delegation_coverage_and_recovery(self):
        finding = {"file": "/repo/broken.ts", "inspectionType": "Unused", "line": 1, "description": "unused control", "severity": "warning"}
        payload = {
            "scope": "files",
            "status": "results_available",
            "total_problems": 1,
            "problems": [finding],
            "capture_diagnostic": {
                "execution_proof_established": False,
                "execution_proof_block_reason": "batch_inspection_delegated_to_editor_service",
                "batch_inspection_delegations": [{"tool": "TypeScriptUnresolvedReference", "file": finding["file"]}],
                "batch_inspection_delegation_count": 1,
                "batch_inspection_delegations_omitted_count": 0,
            },
            "inspection_proof": {"status": "failed", "capture_complete": False},
            "proof_failures": ["execution_not_proven"],
            "inspection_verdict": "RED",
            "inspection_verdict_reason": "actionable_findings",
            "inspection_verdict_next_action": "Fix the finding, then use the supplied classic batch recovery.",
            "route": {"ide": {"inspection_execution_proof_version": jb_inspect.NATIVE_BROAD_SCOPE_PROOF_VERSION}},
        }

        jb_inspect.apply_verdict(payload)
        compact = jb_inspect.compact_agent_result_payload(payload, helper_exit_code=0)

        self.assertEqual(compact["agent_result"]["verdict"], "RED")
        self.assertEqual(compact["findings"][0]["description"], finding["description"])
        self.assertEqual(compact["agent_result"]["proof_failures"], payload["proof_failures"])
        self.assertEqual(compact["inspection_proof"]["status"], payload["inspection_proof"]["status"])
        self.assertFalse(compact["inspection_proof"]["capture_complete"])
        self.assertEqual(compact["agent_result"]["next_action"], payload["inspection_verdict_next_action"])
        for field in ("batch_inspection_delegations", "batch_inspection_delegation_count", "batch_inspection_delegations_omitted_count"):
            self.assertEqual(compact["agent_result"][field], payload["capture_diagnostic"][field])

    def test_summarized_route_result_preserves_native_inspection_proof(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        native_proof = {
            "status": "failed",
            "capture_complete": False,
            "capture_incomplete_reason": "execution_not_proven",
            "scope": "files",
            "snapshot_outcome": "completed",
        }

        def fake_call(active_route, endpoint, params, timeout=None):
            if endpoint == "trigger":
                return {"status": "triggered", "run_id": 7, "route": route}
            if endpoint == "wait":
                return {"status": "results_available", "wait_completed": True, "inspection_run_id": 7, "snapshot_run_id": 7}
            if endpoint == "problems":
                return {
                    "status": "results_available",
                    "inspection_run_id": 7,
                    "snapshot_run_id": 7,
                    "total_problems": 1,
                    "problems": [{"file": "/tmp/worktree/App.ts", "line": 3, "description": "unused", "severity": "warning"}],
                    "inspection_proof": native_proof,
                    "proof_failures": ["execution_not_proven"],
                    "inspection_verdict": "RED",
                    "inspection_verdict_reason": "actionable_findings",
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(timeout_ms=1000, poll_ms=1),
                {"worktree_root": "/tmp/worktree"},
                route,
            )
        compact = jb_inspect.compact_agent_result_payload(result, helper_exit_code=0)

        self.assertEqual(compact["inspection_proof"], native_proof)
        self.assertEqual(compact["agent_result"]["inspection_proof"], native_proof)
        self.assertEqual(compact["agent_result"]["verdict"], "RED")
        self.assertEqual(compact["agent_result"]["proof_failures"], ["execution_not_proven"])

    def test_native_scope_enumeration_failure_is_terminal_tool_bug(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_block_reason": "native_scope_enumeration_failed",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("could not enumerate", payload["agent_result"]["next_action"])

    def test_native_inspection_failure_is_terminal_tool_bug(self):
        payload = {
            "scope": "whole_project",
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "execution_not_proven",
            "capture_diagnostic": {
                "execution_proof_block_reason": "native_inspection_failures",
            },
            "route": {"ide": {"inspection_execution_proof_version": 2}},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("native inspection run", payload["agent_result"]["next_action"])

    def test_worktree_mutation_evidence_reports_only_new_status_changes(self):
        before = {
            "status": "ok",
            "entries": {"existing.txt": " M"},
        }
        after = {
            "status": "ok",
            "entries": {
                "existing.txt": " M",
                ".idea/misc.xml": " M",
                ".idea/editor.xml": "??",
            },
        }

        evidence = jb_inspect.summarize_worktree_mutations(before, after)

        self.assertTrue(evidence["dirty_before"])
        self.assertTrue(evidence["dirty_after"])
        self.assertEqual(evidence["new_or_changed_path_count"], 2)
        self.assertEqual(evidence["tracked_change_count"], 1)
        self.assertEqual(evidence["untracked_change_count"], 1)
        self.assertEqual(evidence["new_or_changed_paths"], [".idea/editor.xml", ".idea/misc.xml"])
        self.assertEqual(evidence["tracked_content_changed_paths_omitted_count"], 0)

    def test_worktree_mutation_blocker_preserves_native_outcome_and_uses_detection_phase(self):
        payload = {
            "status": "clean",
            "verdict": "GREEN",
            "verdict_reason": "clean_confirmed",
            "inspection_result": {
                "status": "clean",
                "verdict": "GREEN",
                "reason": "clean_confirmed",
                "total_problems": 0,
            },
            "worktree_mutation_evidence": {
                "before_status": "ok",
                "after_status": "ok",
                "new_or_changed_path_count": 1,
                "removed_path_count": 0,
                "new_or_changed_paths": [".idea/misc.xml"],
                "removed_paths": [],
            },
        }

        jb_inspect.apply_worktree_mutation_blocker(payload)
        jb_inspect.apply_verdict(payload)
        compact = jb_inspect.compact_agent_result_payload(payload, 1)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["failure_phase"], "post_run_verification")
        self.assertEqual(payload["detection_phase"], "post_run_verification")
        self.assertEqual(payload["agent_result"]["bucket"], "cleanup_not_clean")
        self.assertFalse(payload["agent_result"]["retry_policy"]["retry"])
        self.assertEqual(compact["inspection_outcome"]["status"], "clean")
        self.assertEqual(compact["lifecycle_outcome"]["status"], "mutated")
        self.assertEqual(compact["lifecycle_outcome"]["attribution"], "unattributed")
        self.assertEqual(compact["worktree_mutation_evidence"]["new_or_changed_paths"], [".idea/misc.xml"])

    def test_full_lifecycle_preserves_native_result_before_mutation_blocker(self):
        route = {"port": 63342, "project_key": "path:/tmp/worktree", "session_id": "session"}
        lease = {"lease_id": "lease-1", "opened_by_helper": True, "state": "prepared"}
        native = {
            "status": "clean",
            "verdict": "GREEN",
            "verdict_reason": "clean_confirmed",
            "total_problems": 0,
        }
        before = {"status": "ok", "entries": {}}
        after = {
            "status": "ok",
            "entries": {".idea/misc.xml": " M"},
            "tracked_content_sha256": {},
        }

        with (
            patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": route}, lease, "proof-1")),
            patch_attr(jb_inspect, "run_inspection_with_internal_retry", return_value=native),
            patch_attr(jb_inspect, "cleanup_lifecycle", return_value={"status": "closed"}),
            patch_attr(jb_inspect, "lease_may_own_open_project", return_value=True),
            patch_attr(jb_inspect, "git_worktree_status_snapshot", return_value=before),
            patch_attr(jb_inspect, "post_cleanup_worktree_status_snapshot", return_value=after),
        ):
            result = jb_inspect.run_prepared_inspection(
                helper_args(command="closeout", lifecycle_lock_timeout_ms=0),
                {"worktree_root": "/tmp/worktree", "scope": "changed_files"},
            )

        compact = jb_inspect.compact_agent_result_payload(result, 1)
        self.assertEqual(result["inspection_result"]["verdict"], "GREEN")
        self.assertEqual(compact["inspection_outcome"]["status"], "clean")
        self.assertEqual(compact["lifecycle_outcome"]["status"], "mutated")
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertFalse(result["agent_result"]["retry_policy"]["retry"])

    def test_compact_lifecycle_outcome_has_bounded_zero_mutation_evidence(self):
        compact = jb_inspect.compact_agent_result_payload({"status": "clean", "verdict": "GREEN"}, 0)

        evidence = compact["lifecycle_outcome"]["worktree_mutation_evidence"]
        self.assertEqual(compact["lifecycle_outcome"]["status"], "unknown")
        self.assertEqual(evidence["status"], "unavailable")
        self.assertNotIn("new_or_changed_path_count", evidence)
        self.assertNotIn("removed_path_count", evidence)
        self.assertNotIn("new_or_changed_paths", compact)

    def test_red_native_outcome_survives_mutation_blocker(self):
        payload = {
            "status": "findings",
            "verdict": "RED",
            "verdict_reason": "actionable_findings",
            "total_problems": 1,
            "inspection_result": {
                "status": "findings",
                "verdict": "RED",
                "reason": "actionable_findings",
                "total_problems": 1,
            },
            "worktree_mutation_evidence": {
                "before_status": "ok",
                "after_status": "ok",
                "new_or_changed_path_count": 1,
                "removed_path_count": 0,
                "new_or_changed_paths": [".idea/editor.xml"],
                "removed_paths": [],
            },
        }

        jb_inspect.apply_worktree_mutation_blocker(payload)
        jb_inspect.apply_verdict(payload)
        compact = jb_inspect.compact_agent_result_payload(payload, 1)

        self.assertEqual(compact["inspection_outcome"]["status"], "red")
        self.assertEqual(compact["inspection_outcome"]["verdict"], "RED")
        self.assertEqual(compact["lifecycle_outcome"]["status"], "mutated")

    def test_stale_unknown_native_outcome_and_missing_snapshots_stay_unknown(self):
        stale = {
            "status": "stale_results",
            "verdict": "UNKNOWN",
            "results_may_be_stale": True,
            "inspection_result": {
                "status": "stale_results",
                "verdict": "UNKNOWN",
                "reason": "stale_results",
                "results_may_be_stale": True,
            },
            "worktree_mutation_evidence": {
                "before_status": "unavailable",
                "after_status": "unavailable",
                "new_or_changed_path_count": 0,
                "removed_path_count": 0,
                "new_or_changed_paths": [],
                "removed_paths": [],
            },
        }

        compact = jb_inspect.compact_agent_result_payload(stale, 1)
        self.assertEqual(compact["inspection_outcome"]["status"], "unknown")
        self.assertEqual(compact["inspection_outcome"]["freshness"], "stale")
        self.assertEqual(compact["lifecycle_outcome"]["status"], "unknown")
        self.assertEqual(compact["lifecycle_outcome"]["snapshot_status"], "unavailable")

        no_run = jb_inspect.compact_agent_result_payload({"status": "error", "error_reason": "inspection_api_unavailable"}, 1)
        self.assertEqual(no_run["inspection_outcome"]["status"], "not_run")
        self.assertEqual(no_run["inspection_outcome"]["freshness"], "unknown")
        self.assertEqual(no_run["lifecycle_outcome"]["status"], "unknown")

    def test_tracked_content_paths_report_omitted_count(self):
        paths = {f"file-{index}.txt": " M" for index in range(jb_inspect.MAX_WORKTREE_MUTATION_PATHS + 3)}
        before = {"status": "ok", "entries": {}, "tracked_content_sha256": {}}
        after = {"status": "ok", "entries": paths, "tracked_content_sha256": {path: f"sha256:{index:064d}" for index, path in enumerate(paths)}}

        evidence = jb_inspect.summarize_worktree_mutations(before, after)
        self.assertEqual(len(evidence["tracked_content_changed_paths"]), jb_inspect.MAX_WORKTREE_MUTATION_PATHS)
        self.assertEqual(evidence["tracked_content_changed_paths_omitted_count"], 3)

    def test_compact_outcomes_are_idempotent_and_survive_durable_conversion(self):
        payload = {
            "status": "error",
            "verdict": "UNKNOWN",
            "verdict_reason": "worktree_mutation_detected",
            "inspection_result": {
                "status": "clean",
                "verdict": "GREEN",
                "reason": "clean_confirmed",
                "total_problems": 0,
                "attribution": "decisive",
                "phase": "publish",
                "results_may_be_stale": False,
                "inspection_run_id": 77,
            },
            "worktree_mutation_evidence": {
                "before_status": "ok",
                "after_status": "ok",
                "new_or_changed_path_count": 1,
                "removed_path_count": 0,
                "new_or_changed_paths": [".idea/misc.xml"],
                "removed_paths": [],
            },
        }

        compact = jb_inspect.compact_agent_result_payload(payload, 1)
        _, record = jb_inspect.outcome_record_base(compact)
        compact_again = jb_inspect.compact_agent_result_payload(compact, 1)

        self.assertEqual(compact["inspection_outcome"], record["inspection_outcome"])
        self.assertEqual(compact["inspection_outcome"], compact_again["inspection_outcome"])
        self.assertEqual(compact_again["inspection_outcome"]["status"], "clean")
        self.assertEqual(compact_again["inspection_outcome"]["phase"], "publish")
        self.assertEqual(compact_again["inspection_outcome"]["inspection_run_id"], 77)

    def test_compact_native_snapshot_preserves_nested_run_identity(self):
        result = {
            "status": "clean",
            "verdict": "GREEN",
            "verdict_reason": "clean_confirmed",
            "wait": {"inspection_run_id": 1, "status": "completed"},
            "inspection_stage": "publish",
        }

        snapshot = jb_inspect.compact_inspection_result(result)
        self.assertEqual(snapshot["inspection_run_id"], 1)
        compact = jb_inspect.compact_agent_result_payload(
            {"status": "clean", "verdict": "GREEN", "inspection_result": snapshot},
            0,
        )
        self.assertEqual(compact["inspection_outcome"]["inspection_run_id"], 1)

    def test_generic_unknown_after_failed_setup_is_not_native_inspection_evidence(self):
        payload = {"status": "error", "error_reason": "inspection_api_unavailable"}
        jb_inspect.apply_verdict(payload)

        outcome = jb_inspect.inspection_outcome_for_payload(payload)
        self.assertEqual(outcome["status"], "not_run")
        self.assertEqual(outcome["freshness"], "unknown")

    def test_git_porcelain_z_preserves_special_paths_and_rename_destination(self):
        entries = jb_inspect.parse_git_porcelain_z(
            b" M path with spaces.txt\0"
            b"?? line\nbreak.txt\0"
            b"R  new \xe2\x86\x92 name.txt\0old name.txt\0"
        )

        self.assertEqual(
            entries,
            {
                "path with spaces.txt": " M",
                "line\nbreak.txt": "??",
                "new → name.txt": "R ",
            },
        )

    def test_agent_result_for_empty_model_capture_unknown_is_retryable(self):
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "verdict_reason": "inspection_trigger_empty_model",
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["bucket"], "capture_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertEqual(payload["agent_result"]["bucket"], "capture_not_ready")

    def test_agent_result_for_no_results_proof_failure_is_retryable(self):
        payload = {
            "status": "no_results",
            "proof_failures": ["no_results"],
            "wait": {"completion_reason": "no_results"},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "no_results")
        self.assertEqual(payload["bucket"], "capture_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertEqual(payload["agent_result"]["bucket"], "capture_not_ready")

    def test_mixed_proof_failures_stay_tool_bug(self):
        payload = {
            "status": "no_results",
            "proof_failures": [
                "no_results",
                "resolved_project_path does not match requested_path",
            ],
            "wait": {"completion_reason": "no_results"},
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "inspection_proof_failed")
        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])

    def test_agent_result_for_unclassified_proof_failure_stays_tool_bug(self):
        payload = {
            "status": "unknown",
            "proof_failures": ["unexpected_contradiction"],
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "inspection_proof_failed")
        self.assertEqual(payload["bucket"], "tool_bug")
        self.assertFalse(payload["retry_policy"]["retry"])

    def test_cleanup_failure_agent_result_preserves_inspection_result(self):
        payload = {
            "status": "clean",
            "clean": True,
            "inspection_result": {"verdict": "GREEN", "reason": "clean_confirmed"},
            "cleanup": {"status": "failed", "reason": "route_missing"},
            "cleanup_failed": True,
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "cleanup_not_clean")
        self.assertEqual(payload["inspection_result"]["verdict"], "GREEN")
        self.assertFalse(payload["retry_policy"]["retry"])

    def test_red_exit_semantics_differ_between_run_and_wait(self):
        payload = {"verdict": "RED", "verdict_reason": "actionable_findings"}

        self.assertEqual(jb_inspect.classify_run_exit(payload), 1)
        self.assertEqual(jb_inspect.classify_wait_exit(payload), 0)

    def test_print_human_is_concise_by_default(self):
        payload = {
            "status": "findings",
            "clean": False,
            "route": {
                "ide": {"name": "WebStorm"},
                "project_name": "example",
                "project_key": "path:/tmp/example",
                "base_path": "/tmp/example",
            },
            "total_problems": 1,
            "problems_shown": 1,
            "raw": {"large": "payload"},
            "problems": [{"severity": "warning", "file": "src/app.ts", "line": 12, "description": "Example finding"}],
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("ROUTE: WebStorm", text)
        self.assertIn("STATUS: findings", text)
        self.assertIn("VERDICT: RED", text)
        self.assertIn("AGENT_RESULT: bucket=actionable_findings retry=False", text)
        self.assertIn("NEXT_ACTION: Fix the reported findings", text)
        self.assertIn("SUMMARY: clean=False total_problems=1 problems_shown=1", text)
        self.assertIn("src/app.ts:12 Example finding", text)
        self.assertNotIn('"raw"', text)

    def test_status_human_output_is_concise(self):
        payload = {
            "status": "unknown",
            "clean": False,
            "route": {
                "ide": {"name": "IntelliJ IDEA"},
                "project_name": "example",
                "project_key": "path:/tmp/example",
                "base_path": "/tmp/example",
            },
            "capture_incomplete": True,
            "raw": {"large": "payload"},
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("ROUTE: IntelliJ IDEA", text)
        self.assertIn("STATUS: unknown", text)
        self.assertIn("VERDICT: UNKNOWN", text)
        self.assertIn("NEXT_ACTION:", text)
        self.assertIn("FLAGS: capture_incomplete", text)
        self.assertNotIn('"raw"', text)

    def test_human_output_summarizes_capture_diagnostic(self):
        payload = {
            "status": "capture_incomplete",
            "clean": False,
            "capture_incomplete": True,
            "capture_diagnostic": {
                "exit_reason": "deadline",
                "view_ready_ok": False,
                "observed_inspection_view": True,
                "inspection_view_updating": True,
                "successful_extraction_count": 3,
                "extraction_failure_count": 1,
                "polling_elapsed_ms": 60012,
            },
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("CAPTURE_DIAGNOSTIC:", text)
        self.assertIn("VERDICT: UNKNOWN", text)
        self.assertIn("exit_reason=deadline", text)
        self.assertIn("view_ready_ok=False", text)
        self.assertIn("successful_extraction_count=3", text)

    def test_human_output_identifies_missing_semantic_coverage(self):
        payload = {
            "status": "clean",
            "clean": True,
            "total_problems": 0,
            "capture_diagnostic": scope_capture_diagnostic(
                {
                    "path": "/tmp/PlaybackValidation.swift",
                    "valid": True,
                    "directory": False,
                    "file_type": "textmate",
                    "psi_language": "textmate",
                    "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
                    "in_content": True,
                    "in_source": False,
                }
            ),
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("VERDICT: UNKNOWN reason=scope_semantic_coverage_missing", text)
        self.assertIn("SEMANTIC_COVERAGE: status=missing", text)
        self.assertIn("PlaybackValidation.swift", text)
        self.assertIn("language_hint=swift", text)
        self.assertIn("non_semantic_fallback", text)

    def test_human_output_explains_errors(self):
        payload = {
            "status": "error",
            "error_reason": "inspection_api_unavailable",
            "error_message": "No JetBrains inspection plugin instances discovered.",
            "command": "closeout",
            "exit_code": 3,
            "context": {
                "repo_path": "/tmp/repo",
                "worktree_root": "/tmp/repo",
                "ide": "PyCharm",
            },
            "hint": "Open the repo in PyCharm.",
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("STATUS: error", text)
        self.assertIn("VERDICT: UNKNOWN", text)
        self.assertIn("ERROR: reason=inspection_api_unavailable", text)
        self.assertIn("message=No JetBrains inspection plugin instances discovered.", text)
        self.assertIn("command=closeout", text)
        self.assertIn("CONTEXT: repo=/tmp/repo worktree=/tmp/repo ide=PyCharm", text)
        self.assertIn("HINT: Open the repo in PyCharm.", text)

    def test_human_output_prints_route_diagnostic(self):
        payload = {
            "status": "error",
            "error_reason": "timeout",
            "error_message": "Timed out waiting for the target JetBrains IDE plugin after hidden bootstrap.",
            "command": "closeout",
            "exit_code": 3,
            "context": {
                "repo_path": "/tmp/repo",
                "worktree_root": "/tmp/repo",
                "ide": "PyCharm",
            },
            "route_diagnostic": {
                "requested_ide": "PyCharm",
                "target_worktree": "/tmp/repo",
                "discovered_identity_count": 1,
                "matching_identity_count": 0,
                "discovered_project_count": 1,
                "matching_project_count": 0,
                "reason": "different_jetbrains_product_running",
                "identities": [
                    {
                        "ide_name": "IntelliJ IDEA 2026.1.2",
                        "ide_product_code": "IU",
                        "port": 63342,
                        "plugin_version": "1.12.10",
                        "plugin_build_fingerprint": "abc123-clean",
                        "open_project_count": 1,
                    }
                ],
                "other_projects": [
                    {
                        "ide_name": "IntelliJ IDEA 2026.1.2",
                        "ide_product_code": "IU",
                        "plugin_version": "1.12.10",
                        "plugin_build_fingerprint": "abc123-clean",
                        "name": "jetbrains-inspection-api",
                        "base_path": "/tmp/jetbrains-inspection-api",
                    }
                ],
                "next_action": "Open the worktree in PyCharm with the inspection plugin installed and up to date.",
            },
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("ROUTE_DIAGNOSTIC: requested_ide=PyCharm", text)
        self.assertIn("matching_identities=0", text)
        self.assertIn("reason=different_jetbrains_product_running", text)
        self.assertIn("ROUTE_IDENTITY: ide=IntelliJ IDEA 2026.1.2 product=IU", text)
        self.assertIn("plugin=1.12.10@abc123-clean", text)
        self.assertIn("ROUTE_OTHER_PROJECT: ide=IntelliJ IDEA 2026.1.2 product=IU plugin=1.12.10@abc123-clean name=jetbrains-inspection-api", text)
        self.assertIn("ROUTE_NEXT_ACTION: Open the worktree in PyCharm with the inspection plugin installed and up to date", text)

    def test_human_output_prints_blocked_project_open_diagnostic(self):
        payload = {
            "status": "error",
            "error_reason": "project_open_blocked",
            "error_message": "Timed out waiting for JetBrains IDE to open the exact worktree.",
            "command": "closeout",
            "exit_code": 3,
            "context": {"repo_path": "/tmp/repo", "worktree_root": "/tmp/repo", "ide": "PyCharm"},
            "blocked_diagnostic": {
                "reason": "jetbrains_project_open_blocked",
                "message": "JetBrains may be waiting on a Trust Project, safe-mode, or open-project prompt.",
                "requested_ide": "PyCharm",
                "target_worktree": "/tmp/repo",
                "background_open": True,
                "prepare_timeout_ms": 1234,
                "selected_trusted_root": "/tmp",
            },
            "lifecycle_open_probe": {
                "status": "opening",
                "reason": "already_opening",
                "lifecycle_open_diagnostic": {
                    "phase": "readiness-wait",
                    "outcome_phase": None,
                    "elapsed_ms": 1234,
                    "open_returned": True,
                    "ownership_registered": True,
                    "readiness_waiting": True,
                    "unresolved": False,
                },
            },
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("PROJECT_OPEN_BLOCKED: reason=jetbrains_project_open_blocked", text)
        self.assertIn("requested_ide=PyCharm", text)
        self.assertIn("background_open=True", text)
        self.assertIn("prepare_timeout_ms=1234", text)
        self.assertIn("PROJECT_OPEN_BLOCKED_HINT: JetBrains may be waiting on a Trust Project, safe-mode, or open-project prompt.", text)
        self.assertIn("LIFECYCLE_OPEN_PROBE: status=opening reason=already_opening phase=readiness-wait", text)
        self.assertIn("elapsed_ms=1234", text)

    def test_human_output_prints_zero_project_hint(self):
        payload = {
            "status": "ok",
            "projects": [],
            "count": 0,
            "zero_project_hint": jb_inspect.zero_project_hint(),
        }
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertIn("PROJECT_OPEN_HINT:", text)
        self.assertIn("Trust Project", text)
        self.assertIn("safe-mode", text)
        self.assertIn("open-project", text)

    def test_human_output_explains_status_bearing_timeout_errors(self):
        payload = jb_inspect.error_payload(
            jb_inspect.InspectError(
                "Timed out waiting for JetBrains indexing/scanning to settle.",
                3,
                {
                    "status": {"status": "indexing", "indexing": True, "is_scanning": False},
                    "route": {"ide": {"name": "PyCharm"}, "project_name": "repo"},
                },
            ),
            Namespace(command="closeout"),
        )
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human(payload)

        text = output.getvalue()
        self.assertEqual(payload["status"], "error")
        self.assertEqual(payload["error_reason"], "timeout")
        self.assertEqual(payload["last_status"]["status"], "indexing")
        self.assertIn("STATUS: error", text)
        self.assertIn("ERROR: reason=timeout", text)
        self.assertIn("HINT: Increase the timeout", text)

    def test_inspect_error_payload_adds_reason_and_command(self):
        error = jb_inspect.InspectError("No JetBrains inspection plugin instances discovered.", 3)
        args = Namespace(command="closeout")

        payload = jb_inspect.error_payload(error, args)

        self.assertEqual(payload["status"], "error")
        self.assertEqual(payload["error_reason"], "inspection_api_unavailable")
        self.assertEqual(payload["error_message"], "No JetBrains inspection plugin instances discovered.")
        self.assertEqual(payload["command"], "inspect-closeout")
        self.assertEqual(payload["exit_code"], 3)
        self.assertIn("Open the repo", payload["hint"])

    def test_inspect_error_payload_classifies_target_project_not_open(self):
        error = jb_inspect.InspectError("No open JetBrains project matched this repo/worktree.", 3)

        payload = jb_inspect.error_payload(error, Namespace(command="resolve-route"))

        self.assertEqual(payload["status"], "error")
        self.assertEqual(payload["error_reason"], "target_project_not_open")
        self.assertEqual(payload["command"], "resolve-route")
        self.assertIn("inspect or open-worktree", payload["hint"])

    def test_inspect_error_payload_reports_input_alias_command(self):
        error = jb_inspect.InspectError("No open JetBrains project matched this repo/worktree.", 3)

        payload = jb_inspect.error_payload(error, Namespace(command="route", command_input="resolve-route"))

        self.assertEqual(payload["command"], "resolve-route")
        self.assertEqual(payload["error_reason"], "target_project_not_open")

    def test_inspect_error_payload_classifies_no_open_lifecycle_miss(self):
        error = jb_inspect.InspectError("Exact worktree is not open in a JetBrains IDE.", 3)

        payload = jb_inspect.error_payload(error, Namespace(command="run", command_input="inspect"))

        self.assertEqual(payload["command"], "inspect")
        self.assertEqual(payload["error_reason"], "target_project_not_open")
        self.assertIn("inspect or open-worktree", payload["hint"])

    def test_structured_route_error_reason_overrides_open_wording(self):
        error = jb_inspect.InspectError(
            "No open JetBrains project matched this repo/worktree.",
            3,
            {"error_reason": "target_project_not_open"},
        )

        payload = jb_inspect.error_payload(error, Namespace(command="resolve-route"))

        self.assertEqual(payload["error_reason"], "target_project_not_open")
        self.assertIn("inspect or open-worktree", payload["hint"])

    def test_inspect_error_payload_moves_structured_status_to_last_status(self):
        error = jb_inspect.InspectError(
            "Timed out waiting for JetBrains indexing/scanning to settle.",
            3,
            {"status": {"status": "indexing", "indexing": True}},
        )

        payload = jb_inspect.error_payload(error, Namespace(command="inspect-closeout"))

        self.assertEqual(payload["status"], "error")
        self.assertEqual(payload["last_status"], {"status": "indexing", "indexing": True})
        self.assertEqual(payload["error_reason"], "timeout")
        self.assertIn("Increase the timeout", payload["hint"])

    def test_inspect_error_payload_turns_scalar_status_into_reason(self):
        error = jb_inspect.InspectError("Lifecycle lock timed out.", 3, {"status": "timeout"})

        payload = jb_inspect.error_payload(error, Namespace(command="inspect-closeout"))

        self.assertEqual(payload["status"], "error")
        self.assertEqual(payload["reason"], "timeout")
        self.assertEqual(payload["error_reason"], "timeout")
        self.assertNotIn("last_status", payload)

    def test_json_error_payload_is_structured(self):
        payload = jb_inspect.error_payload(
            jb_inspect.InspectError("Inspection API returned invalid JSON: boom", 3),
            Namespace(command="get-problems"),
        )
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = jb_inspect.emit(payload, json_only=True, exit_code=3)

        self.assertEqual(exit_code, 3)
        body = json.loads(output.getvalue())
        self.assertEqual(body["status"], "error")
        self.assertEqual(body["error_reason"], "invalid_api_response")
        self.assertEqual(body["command"], "get-problems")
        self.assertEqual(body["exit_code"], 3)


class UnknownVerdictLogTest(unittest.TestCase):
    def test_emit_logs_unknown_verdict_with_rollout_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "unknown.jsonl"
            rollout_path = Path(tmp) / "rollout-123.jsonl"
            rollout_path.write_text('{"deployment":"fixture"}\n', encoding="utf-8")
            payload = {
                "command": "inspect-closeout",
                "status": "capture_incomplete",
                "capture_incomplete_reason": "non_empty_unmapped_tree",
                "verdict": "UNKNOWN",
                "verdict_reason": "non_empty_unmapped_tree",
                "verdict_message": "Inspection did not produce a trustworthy GREEN or RED result.",
                "verdict_next_action": "Treat this as a plugin/helper bug.",
                "context": {
                    "repo_path": "/repo",
                    "worktree_root": "/repo-wt",
                    "scope": "changed_files",
                },
                "route": {
                    "project_name": "repo-wt",
                    "project_key": "path:/repo-wt",
                    "base_path": "/repo-wt",
                    "ide": {"name": "IntelliJ IDEA"},
                },
                "capture_diagnostic": {"exit_reason": "non_empty_unmapped_tree"},
                "authorization": "secret-token",
            }

            output = io.StringIO()
            with patch.dict(os.environ, {
                jb_inspect.UNKNOWN_LOG_ENV: str(log_path),
                "JB_INSPECT_ROLLOUT_FILE": str(rollout_path),
            }, clear=False):
                with redirect_stdout(output):
                    exit_code = jb_inspect.emit(payload, json_only=False, exit_code=1)

            self.assertEqual(exit_code, 1)
            self.assertIn(f"UNKNOWN_LOG: {log_path.resolve()}", output.getvalue())
            records = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(records), 1)
            record = records[0]
            self.assertEqual(record["verdict"], "UNKNOWN")
            self.assertEqual(record["bucket"], "tool_bug")
            self.assertFalse(record["retry"])
            self.assertEqual(record["verdict_reason"], "non_empty_unmapped_tree")
            self.assertEqual(record["schema_version"], 2)
            self.assertEqual(record["rollout_file_hash"], jb_inspect.file_content_sha256(rollout_path))
            self.assertEqual(record["deployment_manifest_sha256"], jb_inspect.file_content_sha256(rollout_path))
            self.assertEqual(record["repo_path_hash"], jb_inspect.stable_value_hash("/repo"))
            self.assertEqual(record["ide"], "IntelliJ IDEA")
            self.assertEqual(record["capture_diagnostic"]["exit_reason"], "non_empty_unmapped_tree")
            self.assertNotIn("secret-token", log_path.read_text(encoding="utf-8"))
            self.assertNotIn("/repo-wt", log_path.read_text(encoding="utf-8"))

    def test_outcome_path_is_resolved_inside_routing_lock(self):
        order = []

        class RoutingLock:
            def __enter__(self):
                order.append("lock_enter")

            def __exit__(self, exc_type, exc, tb):
                order.append("lock_exit")

        payload = qualification_event("routing-lock")

        def resolve_path():
            order.append("resolve_path")
            return Path("/tmp/outcomes.jsonl")

        def append_record(path, record):
            order.append("append")
            self.assertEqual(path, Path("/tmp/outcomes.jsonl"))
            self.assertEqual(record["event_kind"], "inspection_assessment")

        with (
            patch_attr(jb_inspect, "outcome_routing_lock", return_value=RoutingLock()),
            patch_attr(jb_inspect, "outcome_log_path", side_effect=resolve_path),
            patch_attr(jb_inspect, "append_jsonl_record", side_effect=append_record),
        ):
            jb_inspect.log_assessment_records(payload, 0)

        self.assertEqual(order, ["lock_enter", "resolve_path", "append", "lock_exit"])
        self.assertEqual(payload["outcome_log_path"], "/tmp/outcomes.jsonl")

    def test_unknown_and_outcome_records_share_one_routing_lock(self):
        order = []

        class RoutingLock:
            def __enter__(self):
                order.append("lock_enter")

            def __exit__(self, exc_type, exc, tb):
                order.append("lock_exit")

        payload = qualification_event("routing-lock-unknown", verdict="UNKNOWN")

        with (
            patch_attr(jb_inspect, "outcome_routing_lock", return_value=RoutingLock()),
            patch_attr(
                jb_inspect,
                "write_unknown_verdict_record",
                side_effect=lambda candidate: order.append("unknown_record"),
            ),
            patch_attr(
                jb_inspect,
                "write_outcome_record",
                side_effect=lambda candidate, exit_code: order.append("outcome_record"),
            ),
        ):
            jb_inspect.log_assessment_records(payload, 1)

        self.assertEqual(order, ["lock_enter", "unknown_record", "outcome_record", "lock_exit"])

    @unittest.skipIf(jb_inspect.fcntl is None, "requires advisory file locking")
    def test_blocked_emitter_resolves_current_after_routing_lock_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache = root / "cache"
            old_deployment = root / "old"
            new_deployment = root / "new"
            old_deployment.mkdir()
            new_deployment.mkdir()
            current = root / "current"
            current.symlink_to(old_deployment, target_is_directory=True)
            marker = root / "child-started"
            lock_attempt = root / "child-lock-attempt"
            child_code = f"""
import importlib.util
import os
import sys
from pathlib import Path
script = Path({str(SCRIPT_PATH)!r})
spec = importlib.util.spec_from_file_location('jb_inspect_child', script)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
real_outcome_routing_lock = module.outcome_routing_lock
class WitnessedOutcomeRoutingLock:
    def __init__(self, *args, **kwargs):
        self.inner = real_outcome_routing_lock(*args, **kwargs)
    def __enter__(self):
        Path({str(lock_attempt)!r}).write_text('attempting', encoding='utf-8')
        return self.inner.__enter__()
    def __exit__(self, *args):
        return self.inner.__exit__(*args)
module.outcome_routing_lock = WitnessedOutcomeRoutingLock
Path({str(marker)!r}).write_text('started', encoding='utf-8')
module.log_assessment_records({{
    'command': 'inspect-closeout',
    'verdict': 'GREEN',
    'verdict_reason': 'no_matching_findings',
    'status': 'clean',
    'context': {{'client_run_id': 'routing-test', 'scope': 'changed_files'}},
}}, 0)
"""
            environment = os.environ.copy()
            environment.update({
                "JETBRAINS_INSPECTION_CACHE_DIR": str(cache),
                jb_inspect.OUTCOME_LOG_ENV: str(current / "outcomes.jsonl"),
                jb_inspect.UNKNOWN_LOG_ENV: "0",
                "PYTHONDONTWRITEBYTECODE": "1",
            })
            environment.pop(jb_inspect.DEPLOYMENT_MANIFEST_ENV, None)

            child = None

            def drain_child(*, terminate: bool) -> tuple[str, str]:
                if child is None:
                    return "", ""
                if terminate and child.poll() is None:
                    try:
                        os.killpg(child.pid, jb_inspect.signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                try:
                    return child.communicate(timeout=2)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(child.pid, jb_inspect.signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    return child.communicate(timeout=2)

            try:
                with patch.dict(os.environ, {"JETBRAINS_INSPECTION_CACHE_DIR": str(cache)}, clear=False):
                    with jb_inspect.outcome_routing_lock(timeout_ms=5_000):
                        child = subprocess.Popen(
                            [sys.executable, "-c", child_code],
                            env=environment,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE,
                            text=True,
                            start_new_session=True,
                        )
                        deadline = time.monotonic() + 5
                        while not lock_attempt.exists() and child.poll() is None and time.monotonic() < deadline:
                            time.sleep(0.01)
                        if not marker.exists() or not lock_attempt.exists():
                            child_stdout, child_stderr = drain_child(terminate=True)
                            self.fail(
                                "child did not reach the routing-lock attempt witness; "
                                f"stdout={child_stdout}\nstderr={child_stderr}"
                            )
                        replacement = root / ".current-new"
                        replacement.symlink_to(new_deployment, target_is_directory=True)
                        os.replace(replacement, current)

                child_stdout, child_stderr = child.communicate(timeout=10)
                self.assertEqual(child.returncode, 0, f"stdout={child_stdout}\nstderr={child_stderr}")
                self.assertFalse((old_deployment / "outcomes.jsonl").exists())
                records = [
                    json.loads(line)
                    for line in (new_deployment / "outcomes.jsonl").read_text(encoding="utf-8").splitlines()
                ]
                self.assertEqual(len(records), 1)
                self.assertEqual(records[0]["client_run_id"], "routing-test")
            finally:
                if child is not None:
                    drain_child(terminate=False)

    def test_routing_lock_uses_windows_file_lock_fallback(self):
        class FakeMsvcrt:
            LK_NBLCK = 1
            LK_UNLCK = 2

            def __init__(self):
                self.calls = []

            def locking(self, descriptor, mode, size):
                self.calls.append((mode, size))

        fake_msvcrt = FakeMsvcrt()
        with tempfile.TemporaryDirectory() as tmp:
            with (
                patch.dict(os.environ, {"JETBRAINS_INSPECTION_CACHE_DIR": tmp}, clear=False),
                patch_attr(jb_inspect, jb_inspect.fcntl.__name__, None),
                patch_attr(jb_inspect, "msvcrt", fake_msvcrt),
            ):
                with jb_inspect.outcome_routing_lock(timeout_ms=0):
                    pass

        self.assertEqual(fake_msvcrt.calls, [(fake_msvcrt.LK_NBLCK, 1), (fake_msvcrt.LK_UNLCK, 1)])

    @unittest.skipIf(jb_inspect.fcntl is None, "requires POSIX file locking")
    def test_outcome_append_lock_timeout_is_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "outcomes.jsonl"
            with patch_attr(jb_inspect.fcntl, "flock", side_effect=BlockingIOError()):
                with self.assertRaisesRegex(TimeoutError, "outcome log lock"):
                    jb_inspect.append_jsonl_record(log_path, {"event_id": "blocked"}, lock_timeout_ms=0)

    def test_outcome_append_uses_windows_file_lock_fallback(self):
        class FakeMsvcrt:
            LK_NBLCK = 1
            LK_UNLCK = 2

            def __init__(self):
                self.calls = []

            def locking(self, descriptor, mode, size):
                self.calls.append((mode, size))

        fake_msvcrt = FakeMsvcrt()
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "outcomes.jsonl"
            with (
                patch_attr(jb_inspect, jb_inspect.fcntl.__name__, None),
                patch_attr(jb_inspect, "msvcrt", fake_msvcrt),
            ):
                jb_inspect.append_jsonl_record(log_path, {"event_id": "windows"}, lock_timeout_ms=0)

            self.assertEqual(json.loads(log_path.read_text(encoding="utf-8")), {"event_id": "windows"})

        self.assertEqual(fake_msvcrt.calls, [(fake_msvcrt.LK_NBLCK, 1), (fake_msvcrt.LK_UNLCK, 1)])

    def test_outcome_append_windows_lock_timeout_is_bounded(self):
        class BlockingMsvcrt:
            LK_NBLCK = 1
            LK_UNLCK = 2

            def locking(self, descriptor, mode, size):
                raise OSError("busy")

        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "outcomes.jsonl"
            with (
                patch_attr(jb_inspect, jb_inspect.fcntl.__name__, None),
                patch_attr(jb_inspect, "msvcrt", BlockingMsvcrt()),
            ):
                with self.assertRaisesRegex(TimeoutError, "outcome log lock"):
                    jb_inspect.append_jsonl_record(log_path, {"event_id": "blocked"}, lock_timeout_ms=0)

            self.assertEqual(log_path.read_bytes(), b"")

    def test_human_output_surfaces_outcome_log_error(self):
        output = io.StringIO()

        with redirect_stdout(output):
            jb_inspect.print_human({"status": "clean", "outcome_log_error": "routing lock timeout"})

        self.assertIn("OUTCOME_LOG_ERROR: routing lock timeout", output.getvalue())

    def test_rollout_attribution_requires_explicit_session_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            rollout = Path(tmp) / "sessions" / "2026" / "rollout-other-session.jsonl"
            rollout.parent.mkdir(parents=True)
            rollout.write_text("{}\n", encoding="utf-8")
            environment = {"CODE_HOME": tmp} | {name: "" for name in jb_inspect.ROLLOUT_FILE_ENVS}

            with patch.dict(os.environ, environment, clear=False):
                self.assertIsNone(jb_inspect.discover_rollout_file())

    def test_emit_does_not_log_green_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "unknown.jsonl"
            payload = {
                "status": "results_available",
                "total_problems": 0,
                "problems_shown": 0,
                "problems": [],
            }

            output = io.StringIO()
            with patch.dict(os.environ, {jb_inspect.UNKNOWN_LOG_ENV: str(log_path)}, clear=False):
                with redirect_stdout(output):
                    exit_code = jb_inspect.emit(payload, json_only=True, exit_code=0)

            self.assertEqual(exit_code, 0)
            self.assertFalse(log_path.exists())
            body = json.loads(output.getvalue())
            self.assertNotIn("unknown_log_path", body)

    def test_emit_logs_all_outcomes_when_enabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            outcome_path = Path(tmp) / "outcomes.jsonl"
            payload = {
                "status": "results_available",
                "total_problems": 0,
                "problems_shown": 0,
                "problems": [],
                "context": {
                    "scope": "changed_files",
                    "ide_selection": {
                        "product": "IntelliJ IDEA",
                        "channel": "stable",
                        "version": "2026.1",
                        "explicit_eap": False,
                    },
                },
                "agent_result": {
                    "next_action": "No inspection action required for this scope/filter.",
                    "agent_report": "JetBrains inspection passed for the selected scope.",
                    "retry_policy": {"retry": False, "max_attempts": 0, "wait_ms": 0},
                },
            }

            output = io.StringIO()
            with patch.dict(os.environ, {jb_inspect.OUTCOME_LOG_ENV: str(outcome_path)}, clear=False):
                with redirect_stdout(output):
                    exit_code = jb_inspect.emit(payload, json_only=True, exit_code=0, command="inspect-closeout")

            self.assertEqual(exit_code, 0)
            body = json.loads(output.getvalue())
            self.assertEqual(body["bucket"], "clean")
            records = [json.loads(line) for line in outcome_path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["verdict"], "GREEN")
            self.assertEqual(records[0]["bucket"], "clean")
            self.assertEqual(records[0]["command"], "inspect-closeout")
            self.assertEqual(records[0]["ide_channel"], "stable")
            self.assertFalse(records[0]["eap_explicit"])
            self.assertEqual(records[0]["retry_wait_ms"], 0)
            self.assertEqual(records[0]["next_action"], "No inspection action required for this scope/filter.")
            self.assertEqual(records[0]["agent_report"], "JetBrains inspection passed for the selected scope.")

    def test_summarize_outcome_log_counts_agent_buckets(self):
        with tempfile.TemporaryDirectory() as tmp:
            outcome_path = Path(tmp) / "outcomes.jsonl"
            outcome_path.write_text(
                "\n".join(
                    [
                        json.dumps({
                            "timestamp": "2026-07-04T00:00:00Z",
                            "command": "inspect-closeout",
                            "verdict": "UNKNOWN",
                            "bucket": "ide_not_ready",
                            "retry": True,
                            "ide_channel": "stable",
                            "cleanup_status": "deferred",
                            "repo_path": "/secret/repo",
                        }),
                        "not-json",
                        json.dumps({
                            "timestamp": "2026-07-04T00:01:00Z",
                            "command": "inspect-closeout",
                            "verdict": "GREEN",
                            "bucket": "clean",
                            "retry": False,
                            "ide_channel": "stable",
                            "cleanup_status": "closed",
                            "worktree_root": "/secret/worktree",
                        }),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            summary = jb_inspect.summarize_outcome_log(outcome_path, limit=1)

            self.assertEqual(summary["status"], "ok")
            self.assertEqual(summary["events"], 2)
            self.assertEqual(summary["invalid_lines"], 1)
            self.assertEqual(summary["summary"]["by_verdict"], {"GREEN": 1, "UNKNOWN": 1})
            self.assertEqual(summary["summary"]["by_bucket"], {"clean": 1, "ide_not_ready": 1})
            self.assertEqual(summary["summary"]["by_retry"], {"false": 1, "true": 1})
            self.assertEqual(summary["summary"]["by_cleanup_status"], {"closed": 1, "deferred": 1})
            self.assertEqual(summary["summary"]["retryable_unknowns"], 1)
            self.assertEqual(len(summary["recent"]), 1)
            self.assertEqual(summary["recent"][0]["bucket"], "clean")
            self.assertNotIn("repo_path", summary["recent"][0])
            self.assertNotIn("worktree_root", json.dumps(summary))

    def test_summarize_outcome_log_missing_path_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = jb_inspect.summarize_outcome_log(Path(tmp) / "missing.jsonl")

            self.assertEqual(summary["status"], "missing")
            self.assertEqual(summary["events"], 0)
            self.assertEqual(summary["summary"], jb_inspect.empty_outcome_summary())

    def test_command_summarize_outcomes_respects_disabled_env(self):
        with patch.dict(os.environ, {jb_inspect.OUTCOME_LOG_ENV: "0"}, clear=False):
            summary = jb_inspect.command_summarize_outcomes(Namespace(log_path=None, limit=10))

        self.assertEqual(summary["status"], "disabled")
        self.assertEqual(summary["events"], 0)

    def test_command_summarize_outcomes_reads_explicit_log_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            outcome_path = Path(tmp) / "custom-outcomes.jsonl"
            outcome_path.write_text(
                json.dumps({"verdict": "GREEN", "bucket": "clean", "command": "inspect-closeout"}) + "\n",
                encoding="utf-8",
            )

            summary = jb_inspect.command_summarize_outcomes(Namespace(log_path=str(outcome_path), limit=5))

            self.assertEqual(summary["status"], "ok")
            self.assertEqual(summary["events"], 1)
            self.assertEqual(summary["summary"]["by_verdict"], {"GREEN": 1})

    def test_summarize_outcomes_emit_does_not_attach_assessment_verdict_or_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            outcome_path = Path(tmp) / "outcomes.jsonl"
            summary = {"status": "ok", "events": 0, "invalid_lines": 0, "summary": jb_inspect.empty_outcome_summary(), "recent": []}
            output = io.StringIO()

            with patch.dict(os.environ, {jb_inspect.OUTCOME_LOG_ENV: str(outcome_path)}, clear=False):
                with redirect_stdout(output):
                    exit_code = jb_inspect.emit(summary, json_only=True, exit_code=0, command="summarize-outcomes", assess=False)

            self.assertEqual(exit_code, 0)
            body = json.loads(output.getvalue())
            self.assertEqual(body["command"], "summarize-outcomes")
            self.assertNotIn("verdict", body)
            self.assertFalse(outcome_path.exists())

    def test_summarize_outcomes_human_output_renders_counts_without_verdict(self):
        summary = {
            "status": "ok",
            "path": "/tmp/outcomes.jsonl",
            "events": 3,
            "invalid_lines": 1,
            "summary": {
                "by_verdict": {"GREEN": 2, "UNKNOWN": 1},
                "by_bucket": {"clean": 2, "stale_results": 1},
                "by_command": {"inspect-closeout": 3},
                "by_retry": {"false": 2, "true": 1},
                "by_ide_channel": {"stable": 3},
                "by_cleanup_status": {"closed": 3},
                "retryable_unknowns": 1,
            },
            "recent": [],
        }
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = jb_inspect.emit(
                summary,
                json_only=False,
                exit_code=0,
                command="summarize-outcomes",
                assess=False,
            )

        rendered = output.getvalue()
        self.assertEqual(exit_code, 0)
        self.assertIn("STATUS: ok", rendered)
        self.assertIn("OUTCOMES: events=3 invalid_lines=1 path=/tmp/outcomes.jsonl", rendered)
        self.assertIn("BY_VERDICT: GREEN=2 UNKNOWN=1", rendered)
        self.assertIn("RETRYABLE_UNKNOWNS: 1", rendered)
        self.assertFalse(any(line.startswith("VERDICT:") for line in rendered.splitlines()))

    def test_outcome_log_can_be_disabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            outcome_path = Path(tmp) / "outcomes.jsonl"
            payload = {"status": "clean", "clean": True, "total_problems": 0, "problems": []}

            with patch.dict(os.environ, {jb_inspect.OUTCOME_LOG_ENV: "0"}, clear=False):
                jb_inspect.log_assessment_records(payload, 0)

            self.assertFalse(outcome_path.exists())

    def test_emit_does_not_log_informational_outcomes(self):
        with tempfile.TemporaryDirectory() as tmp:
            outcome_path = Path(tmp) / "outcomes.jsonl"
            output = io.StringIO()

            with patch.dict(os.environ, {jb_inspect.OUTCOME_LOG_ENV: str(outcome_path)}, clear=False):
                with redirect_stdout(output):
                    exit_code = jb_inspect.emit({"status": "resolved"}, json_only=True, exit_code=0, command="resolve-route")

            self.assertEqual(exit_code, 0)
            self.assertFalse(outcome_path.exists())

    def test_outcome_log_error_is_nonfatal(self):
        payload = {"command": "inspect-closeout", "status": "clean", "clean": True}
        jb_inspect.apply_verdict(payload)

        with patch.dict(os.environ, {jb_inspect.OUTCOME_LOG_ENV: "/dev/null/outcomes.jsonl"}, clear=False):
            jb_inspect.log_assessment_records(payload, 0)

        self.assertIn("outcome_log_error", payload)

    def test_emit_does_not_log_informational_command_unknowns(self):
        cases = [
            ("list-projects", {"status": "ok", "projects": []}),
            ("resolve-route", {"status": "resolved", "route": {}}),
            ("start-inspection", {"status": "triggered"}),
            ("claim-worktree", {"status": "claimed"}),
            ("open-worktree", {"status": "prepared"}),
            ("cleanup-helper-leases", {"status": "ok", "removed": []}),
        ]
        for command, payload in cases:
            with self.subTest(command=command), tempfile.TemporaryDirectory() as tmp:
                log_path = Path(tmp) / "unknown.jsonl"

                output = io.StringIO()
                with patch.dict(os.environ, {jb_inspect.UNKNOWN_LOG_ENV: str(log_path)}, clear=False):
                    with redirect_stdout(output):
                        exit_code = jb_inspect.emit(payload, json_only=True, exit_code=0, command=command)

                self.assertEqual(exit_code, 0)
                self.assertFalse(log_path.exists())
                body = json.loads(output.getvalue())
                self.assertEqual(body["command"], command)
                self.assertEqual(body["verdict"], "UNKNOWN")
                self.assertNotIn("unknown_log_path", body)

    def test_emit_does_not_log_informational_status_without_command(self):
        cases = ["ok", "prepared", "resolved", "triggered", "claimed"]
        for status in cases:
            with self.subTest(status=status), tempfile.TemporaryDirectory() as tmp:
                log_path = Path(tmp) / "unknown.jsonl"

                output = io.StringIO()
                with patch.dict(os.environ, {jb_inspect.UNKNOWN_LOG_ENV: str(log_path)}, clear=False):
                    with redirect_stdout(output):
                        exit_code = jb_inspect.emit({"status": status}, json_only=True, exit_code=0)

                self.assertEqual(exit_code, 0)
                self.assertFalse(log_path.exists())
                body = json.loads(output.getvalue())
                self.assertEqual(body["status"], status)
                self.assertEqual(body["verdict"], "UNKNOWN")
                self.assertNotIn("unknown_log_path", body)

    def test_emit_logs_error_unknown_even_when_command_is_resolve_route(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "unknown.jsonl"
            payload = {
                "command": "resolve-route",
                "status": "error",
                "error_reason": "ide_open_failed",
                "verdict": "UNKNOWN",
                "verdict_reason": "ide_open_failed",
            }

            with patch.dict(os.environ, {jb_inspect.UNKNOWN_LOG_ENV: str(log_path)}, clear=False):
                with redirect_stdout(io.StringIO()):
                    exit_code = jb_inspect.emit(payload, json_only=True, exit_code=1)

            self.assertEqual(exit_code, 1)
            records = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["verdict_reason"], "ide_open_failed")

    def test_unknown_log_records_preferred_command_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "unknown.jsonl"
            payload = {
                "command": "route",
                "status": "error",
                "error_reason": "ide_open_failed",
                "verdict": "UNKNOWN",
                "verdict_reason": "ide_open_failed",
            }

            with patch.dict(os.environ, {jb_inspect.UNKNOWN_LOG_ENV: str(log_path)}, clear=False):
                jb_inspect.log_assessment_records(payload, 0)

            records = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["command"], "resolve-route")

    def test_unknown_log_can_be_disabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "unknown.jsonl"
            payload = {"status": "no_results", "verdict": "UNKNOWN", "verdict_reason": "no_results"}

            with patch.dict(os.environ, {jb_inspect.UNKNOWN_LOG_ENV: "0"}, clear=False):
                jb_inspect.log_assessment_records(payload, 0)

            self.assertFalse(log_path.exists())
            self.assertNotIn("unknown_log_path", payload)

    def test_jsonl_append_uses_one_locked_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "events.jsonl"
            with patch.object(os, "write", wraps=jb_inspect.os.write) as write_call:
                if jb_inspect.fcntl is None:
                    jb_inspect.append_jsonl_record(log_path, {"event_id": "one"})
                    lock_calls = []
                else:
                    with patch_attr(jb_inspect.fcntl, "flock", wraps=jb_inspect.fcntl.flock) as lock_call:
                        jb_inspect.append_jsonl_record(log_path, {"event_id": "one"})
                    lock_calls = [call.args[1] for call in lock_call.call_args_list]
            written_record = json.loads(log_path.read_text(encoding="utf-8"))

        self.assertEqual(write_call.call_count, 1)
        if jb_inspect.fcntl is not None:
            self.assertEqual(
                lock_calls,
                [jb_inspect.fcntl.LOCK_EX | jb_inspect.fcntl.LOCK_NB, jb_inspect.fcntl.LOCK_UN],
            )
        self.assertEqual(written_record, {"event_id": "one"})

    def test_jsonl_append_rolls_back_partial_write_failures(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "events.jsonl"
            log_path.write_bytes(b'{"event_id":"existing"}\n')
            original = log_path.read_bytes()
            real_write = jb_inspect.os.write
            call_count = 0

            def partial_then_fail(descriptor, payload):
                nonlocal call_count
                call_count += 1
                if call_count == 1:
                    return real_write(descriptor, bytes(payload[:5]))
                raise OSError("simulated append failure")

            with patch.object(os, "write", side_effect=partial_then_fail):
                with self.assertRaisesRegex(OSError, "simulated append failure"):
                    jb_inspect.append_jsonl_record(log_path, {"event_id": "new"})

            self.assertEqual(log_path.read_bytes(), original)


class StrictOutcomeQualificationTest(unittest.TestCase):
    def summarize(self, events: list[dict | str], *, criteria: dict | None = None, sample_size: int = 1) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log_path = root / "outcomes.jsonl"
            qualification_path = root / "qualification.json"
            log_path.write_text(
                "\n".join(event if isinstance(event, str) else json.dumps(event, sort_keys=True) for event in events) + "\n",
                encoding="utf-8",
            )
            write_json(qualification_path, criteria or qualification_payload())
            return jb_inspect.command_summarize_outcomes(
                Namespace(
                    log_path=str(log_path),
                    limit=10,
                    qualification_file=str(qualification_path),
                    sample_size=sample_size,
                )
            )

    def test_mixed_historical_logs_preserve_diagnostic_mode_and_strict_boundary(self):
        historical = {
            "timestamp": "2026-07-25T23:59:59Z",
            "command": "inspect-closeout",
            "verdict": "GREEN",
            "bucket": "clean",
        }
        current = qualification_event("current")
        observation = qualification_event(
            "observation",
            assessment_id="observation-assessment",
            timestamp="2026-07-26T00:00:02.000Z",
            event_kind="inspection_observation",
            command="get-status",
        )

        strict = self.summarize([historical, current, observation])

        self.assertEqual(strict["gate_status"], "pass")
        self.assertEqual(strict["sample_count"], 1)
        self.assertEqual(strict["qualifying_sample"][0]["assessment_id"], "assessment-current")
        self.assertEqual(strict["exclusion_counts"], {"non_assessment_command": 1})
        self.assertNotIn("2026-07-25", json.dumps(strict))

        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "outcomes.jsonl"
            log_path.write_text(json.dumps(historical) + "\n" + json.dumps(current) + "\n", encoding="utf-8")
            diagnostic = jb_inspect.command_summarize_outcomes(Namespace(log_path=str(log_path), limit=10))

        self.assertEqual(diagnostic["status"], "ok")
        self.assertEqual(diagnostic["events"], 2)
        self.assertEqual(diagnostic["summary"]["by_verdict"], {"GREEN": 2})

    def test_boundary_event_and_exact_duplicate_handling_are_deterministic(self):
        before = qualification_event("before", timestamp="2026-07-26T00:00:01.000Z")
        anchor = qualification_event(
            "anchor",
            assessment_id="anchor-assessment",
            timestamp="2026-07-26T00:00:02.000Z",
            event_kind="inspection_observation",
            command="get-status",
        )
        current = qualification_event("current", timestamp="2026-07-26T00:00:03.000Z")
        criteria = qualification_payload(after_event_id="anchor")

        result = self.summarize([before, anchor, before, current, current], criteria=criteria)

        self.assertEqual(result["gate_status"], "pass")
        self.assertEqual(result["sample_count"], 1)
        self.assertEqual(result["qualifying_sample"][0]["assessment_id"], "assessment-current")
        self.assertEqual(result["exclusion_counts"], {"duplicate_event": 2})
        self.assertNotIn("assessment-before", json.dumps(result["groups"]))

    def test_timestamp_only_boundary_rejects_malformed_logs(self):
        result = self.summarize(['{"broken"', qualification_event("current")])

        self.assertEqual(result["gate_status"], "fail")
        self.assertEqual(result["error_reason"], "boundary_event_required_for_invalid_log")

        indeterminate = qualification_event("indeterminate")
        indeterminate.pop("timestamp")
        indeterminate.pop("timestamp_ms")
        indeterminate_result = self.summarize([indeterminate, qualification_event("current")])

        self.assertEqual(indeterminate_result["gate_status"], "fail")
        self.assertEqual(indeterminate_result["error_reason"], "boundary_event_required_for_invalid_log")

        boolean_timestamp = qualification_event("boolean-timestamp")
        boolean_timestamp.pop("timestamp")
        boolean_timestamp["timestamp_ms"] = True
        boolean_result = self.summarize([boolean_timestamp, qualification_event("current")])

        self.assertEqual(boolean_result["gate_status"], "fail")
        self.assertEqual(boolean_result["error_reason"], "boundary_event_required_for_invalid_log")

        mixed_timestamp = qualification_event(
            "mixed-timestamp",
            timestamp="2026-07-25T23:59:59.000Z",
        )
        mixed_timestamp["timestamp_ms"] = True
        mixed_result = self.summarize([mixed_timestamp, qualification_event("current")])

        self.assertEqual(mixed_result["gate_status"], "fail")
        self.assertEqual(mixed_result["error_reason"], "boundary_event_required_for_invalid_log")

    def test_boundary_event_exposes_post_boundary_invalid_json(self):
        anchor = qualification_event(
            "anchor",
            assessment_id="anchor-assessment",
            event_kind="inspection_observation",
            command="get-status",
        )
        current = qualification_event("current", timestamp="2026-07-26T00:00:03.000Z")
        result = self.summarize(
            [anchor, '{"broken"', current],
            criteria=qualification_payload(after_event_id="anchor"),
        )

        self.assertEqual(result["gate_status"], "fail")
        self.assertEqual(result["hard_failure_counts"]["invalid_json"], 1)

    def test_post_boundary_legacy_and_missing_event_identity_are_hard_exclusions(self):
        legacy = {
            "schema_version": 1,
            "timestamp": "2026-07-26T00:00:01.000Z",
            "command": "inspect-closeout",
            "verdict": "GREEN",
        }
        missing_event_id = qualification_event("missing-id", timestamp="2026-07-26T00:00:02.000Z")
        missing_event_id.pop("event_id")

        result = self.summarize([legacy, missing_event_id])

        self.assertEqual(result["gate_status"], "fail")
        self.assertEqual(result["hard_failure_counts"]["legacy_schema"], 1)
        self.assertEqual(result["hard_failure_counts"]["missing_event_id"], 1)

    def test_missing_identity_and_provenance_reasons_fail_closed(self):
        mutations = {
            "missing_client_run_id": ("client_run_id", "assessment_id"),
            "missing_inspection_run_id": ("inspection_run_id",),
            "missing_scope": ("scope_descriptor",),
            "missing_cleanup_status": ("cleanup_status",),
            "missing_ide_product_code": ("ide_product_code",),
            "missing_ide_version": ("ide_version",),
            "missing_ide_channel": ("ide_channel",),
        }
        for expected_reason, removed_keys in mutations.items():
            with self.subTest(reason=expected_reason):
                event = qualification_event(expected_reason)
                for key in removed_keys:
                    event.pop(key, None)
                result = self.summarize([event])

                self.assertEqual(result["gate_status"], "fail")
                self.assertEqual(result["hard_failure_counts"].get(expected_reason), 1)

        mismatched_assessment = qualification_event("assessment-mismatch")
        mismatched_assessment["assessment_id"] = "different-assessment"
        mismatch_result = self.summarize([mismatched_assessment])

        self.assertEqual(mismatch_result["gate_status"], "fail")
        self.assertEqual(mismatch_result["hard_failure_counts"].get("assessment_id_mismatch"), 1)

        for key, expected_reason in (
            ("repo_path_hash", "missing_repo_hash"),
            ("worktree_root_hash", "missing_worktree_hash"),
            ("project_key_hash", "missing_project_hash"),
        ):
            with self.subTest(invalid_hash=key):
                event = qualification_event(f"invalid-{key}")
                event[key] = "sha256:not-a-full-digest"
                if key == "project_key_hash":
                    event["inspection_attribution"][key] = event[key]
                result = self.summarize([event])

                self.assertEqual(result["gate_status"], "fail")
                self.assertEqual(result["hard_failure_counts"].get(expected_reason), 1)

    def test_artifact_mismatches_are_hard_failures(self):
        helper_mismatch = qualification_event("helper")
        helper_mismatch["helper_revision"] = "sha256:" + "3" * 64
        plugin_mismatch = qualification_event("plugin")
        plugin_mismatch["plugin_build_fingerprint"] = "c" * 40 + "-clean"
        deployment_mismatch = qualification_event("deployment")
        deployment_mismatch["deployment_manifest_sha256"] = "sha256:" + "4" * 64

        result = self.summarize([helper_mismatch, plugin_mismatch, deployment_mismatch])

        self.assertEqual(result["gate_status"], "fail")
        self.assertEqual(result["hard_failure_counts"]["helper_revision_mismatch"], 1)
        self.assertEqual(result["hard_failure_counts"]["plugin_fingerprint_mismatch"], 1)
        self.assertEqual(result["hard_failure_counts"]["deployment_manifest_mismatch"], 1)

    def test_rows_fail_when_plugin_attribution_or_authoritative_channel_is_absent(self):
        missing_attribution = qualification_event("missing-attribution")
        missing_attribution["inspection_attribution"] = {
            "source": "helper",
            "classification": "decisive",
        }
        missing_channel_source = qualification_event("missing-channel-source")
        missing_channel_source["ide_channel_source"] = "selector_fallback"

        result = self.summarize([missing_attribution, missing_channel_source], sample_size=2)

        self.assertEqual(result["gate_status"], "fail")
        self.assertEqual(result["hard_failure_counts"]["inspection_attribution_mismatch"], 2)

        unknown = qualification_event("unknown", verdict="UNKNOWN")
        unknown["inspection_attribution"]["classification"] = "unattributed"
        unknown_result = self.summarize([unknown])

        self.assertEqual(unknown_result["gate_status"], "fail")
        self.assertEqual(unknown_result["hard_failure_counts"]["inspection_attribution_mismatch"], 1)

        decisive = qualification_event("decisive")
        decisive["inspection_attribution"]["code"] = "actionable_findings"
        decisive_result = self.summarize([decisive])

        self.assertEqual(decisive_result["gate_status"], "fail")
        self.assertEqual(decisive_result["hard_failure_counts"]["inspection_attribution_mismatch"], 1)

        for field, mismatch in (
            ("endpoint", "/api/inspection/status"),
            ("http_status", 500),
            ("request_id", "different-request"),
            ("observed_by", "different-observer"),
            ("ide_channel_source", "selector_fallback"),
        ):
            with self.subTest(attribution_field=field):
                event = qualification_event(f"mismatch-{field}")
                event["inspection_attribution"][field] = mismatch
                result = self.summarize([event])

                self.assertEqual(result["gate_status"], "fail")
                self.assertEqual(result["hard_failure_counts"]["inspection_attribution_mismatch"], 1)

    def test_explicit_text_only_helper_attribution_qualifies_narrowly(self):
        event = qualification_event("text-only-helper")
        event["verdict_reason"] = "text_only_coverage_allowed"
        event["response_code"] = "text_only_coverage_allowed"
        event["scope_descriptor"]["allow_text_only_coverage"] = True
        event["scope_descriptor_sha256"] = jb_inspect.canonical_json_sha256(event["scope_descriptor"])
        event["inspection_attribution"].update({
            "source": "helper",
            "code": "text_only_coverage_allowed",
        })
        event["internal_attempts"][0].update({
            "verdict_reason": "text_only_coverage_allowed",
            "proof_failures": ["scope_semantic_coverage_missing"],
        })
        event["total_problems"] = 0

        result = self.summarize([event])

        self.assertEqual(result["gate_status"], "pass")
        self.assertEqual(result["hard_failure_count"], 0)

        for label, mutate in (
            ("override-disabled", lambda candidate: candidate["scope_descriptor"].update({"allow_text_only_coverage": False})),
            ("missing-proof", lambda candidate: candidate["internal_attempts"][0].pop("proof_failures")),
            ("malformed-proof", lambda candidate: candidate["internal_attempts"][0].update({"proof_failures": [{}]})),
            ("wrong-verdict-reason", lambda candidate: candidate.update({"verdict_reason": "no_matching_findings"})),
        ):
            with self.subTest(label=label):
                invalid = json.loads(json.dumps(event))
                mutate(invalid)
                invalid["scope_descriptor_sha256"] = jb_inspect.canonical_json_sha256(invalid["scope_descriptor"])
                invalid_result = self.summarize([invalid])

                self.assertEqual(invalid_result["gate_status"], "fail")
                self.assertEqual(invalid_result["hard_failure_counts"]["inspection_attribution_mismatch"], 1)

    def test_configuration_blocked_is_excluded_only_before_start_with_exact_selector_code(self):
        valid = qualification_event("valid-config", verdict="UNKNOWN")
        valid.update({
            "inspection_started": False,
            "attribution_class": "configuration_blocked",
            "response_code": "ide_selection_required",
            "failure_phase": "selection",
            "cleanup_status": "not_needed",
            "observed_by": "helper",
            "status": "error",
            "bucket": "policy_required",
            "verdict_reason": "ide_selection_required",
        })
        for key in (
            "inspection_run_id",
            "plugin_build_fingerprint",
            "plugin_version",
            "ide_product_code",
            "ide_version",
            "ide_channel",
            "ide_channel_source",
            "session_id",
            "project_instance_id",
            "endpoint",
            "http_status",
            "request_id",
        ):
            valid.pop(key, None)
        valid["inspection_attribution"] = {
            "schema_version": 1,
            "source": "helper",
            "observed_by": "helper",
            "classification": "configuration_blocked",
            "code": "ide_selection_required",
            "phase": "selection",
            "client_run_id": valid["client_run_id"],
            "helper_revision": valid["helper_revision"],
            "cleanup_status": "not_needed",
        }
        valid["internal_attempts"][0].update({
            "verdict": "UNKNOWN",
            "verdict_reason": "ide_selection_required",
            "bucket": "policy_required",
            "retry": False,
            "attribution_class": "configuration_blocked",
            "phase": "selection",
            "cleanup_status": "not_needed",
        })
        valid["internal_attempts"][0].pop("inspection_run_id", None)
        decisive = qualification_event("decisive", timestamp="2026-07-26T00:00:02.000Z")

        valid_result = self.summarize([valid, decisive])

        self.assertEqual(valid_result["gate_status"], "pass")
        self.assertEqual(valid_result["exclusion_counts"], {"configuration_blocked_before_start": 1})

        non_clean = json.loads(json.dumps(valid))
        non_clean["cleanup_status"] = "kept_warm"
        non_clean["inspection_attribution"]["cleanup_status"] = "kept_warm"
        non_clean["internal_attempts"][0]["cleanup_status"] = "kept_warm"
        non_clean_result = self.summarize([non_clean])
        self.assertEqual(non_clean_result["hard_failure_counts"]["non_clean_cleanup"], 1)

        malformed_attempts = json.loads(json.dumps(valid))
        malformed_attempts["internal_attempts"] = []
        malformed_result = self.summarize([malformed_attempts])
        self.assertEqual(malformed_result["hard_failure_counts"]["malformed_internal_attempts"], 1)

        attribution_mismatch = json.loads(json.dumps(valid))
        attribution_mismatch["inspection_attribution"]["code"] = "ide_config_missing"
        mismatch_result = self.summarize([attribution_mismatch])
        self.assertEqual(mismatch_result["hard_failure_counts"]["inspection_attribution_mismatch"], 1)

        for field, alias in (
            ("response_code", "ide-selection-required"),
            ("failure_phase", "Selection"),
        ):
            with self.subTest(noncanonical_field=field):
                noncanonical = json.loads(json.dumps(valid))
                noncanonical[field] = alias
                nested_field = "code" if field == "response_code" else "phase"
                noncanonical["inspection_attribution"][nested_field] = alias
                noncanonical_result = self.summarize([noncanonical])

                self.assertEqual(noncanonical_result["gate_status"], "fail")
                self.assertEqual(
                    noncanonical_result["hard_failure_counts"]["configuration_blocked_not_excludable"],
                    1,
                )

        invalid = qualification_event("invalid-config", verdict="UNKNOWN")
        invalid.update({
            "attribution_class": "configuration_blocked",
            "response_code": "timeout",
            "failure_phase": "wait",
            "inspection_started": True,
        })
        invalid["inspection_attribution"]["classification"] = "configuration_blocked"
        invalid_result = self.summarize([invalid])

        self.assertEqual(invalid_result["gate_status"], "fail")
        self.assertEqual(invalid_result["hard_failure_counts"]["configuration_blocked_after_start"], 1)

    def test_distinct_terminal_events_for_one_assessment_fail_closed(self):
        first = qualification_event("event-1", assessment_id="assessment-shared", verdict="UNKNOWN")
        first["internal_attempts"][0]["retry"] = True
        repeated = qualification_event(
            "event-2",
            assessment_id="assessment-shared",
            timestamp="2026-07-26T00:00:02.000Z",
            inspection_run_id=2,
        )

        result = self.summarize([first, first, repeated], sample_size=2)

        self.assertEqual(result["gate_status"], "fail")
        self.assertEqual(result["sample_count"], 1)
        self.assertEqual(result["assessment_groups"], 1)
        self.assertEqual(result["groups"][0]["event_count"], 2)
        self.assertFalse(result["groups"][0]["recovered_from_unknown"])
        self.assertEqual(result["hard_failure_counts"]["multiple_assessment_events"], 1)
        self.assertEqual(result["exclusion_counts"], {"duplicate_event": 1})

    def test_internal_unknown_to_decisive_recovery_stays_visible(self):
        event = qualification_event("recovery")
        event["internal_attempts"] = [
            {
                "attempt_index": 0,
                "terminal": False,
                "verdict": "UNKNOWN",
                "verdict_reason": "stale_results",
                "bucket": "stale_results",
                "retry": True,
                "inspection_run_id": 1,
            },
            {
                "attempt_index": 1,
                "terminal": True,
                "verdict": "GREEN",
                "verdict_reason": "no_matching_findings",
                "bucket": "clean",
                "retry": False,
                "inspection_run_id": 2,
                "cleanup_status": "closed",
            },
        ]

        result = self.summarize([event])

        self.assertEqual(result["gate_status"], "pass")
        self.assertTrue(result["groups"][0]["recovered_from_unknown"])
        self.assertEqual(result["summaries"]["recovered_from_unknown"], 1)
        self.assertEqual([attempt["verdict"] for attempt in result["groups"][0]["internal_attempts"]], ["UNKNOWN", "GREEN"])

    def test_conflicting_decisive_outcomes_and_hidden_terminal_failures_fail(self):
        green = qualification_event("green", assessment_id="assessment-conflict")
        red = qualification_event(
            "red",
            assessment_id="assessment-conflict",
            timestamp="2026-07-26T00:00:02.000Z",
            verdict="RED",
            inspection_run_id=2,
        )
        conflict = self.summarize([green, red], sample_size=2)

        self.assertEqual(conflict["gate_status"], "fail")
        self.assertTrue(conflict["groups"][0]["conflicting_decisive_outcomes"])
        self.assertEqual(conflict["hard_failure_counts"]["conflicting_outcomes"], 1)

        terminal = qualification_event("terminal", assessment_id="assessment-terminal", verdict="UNKNOWN")
        terminal["timestamp"] = "2026-07-26T00:00:02.000Z"
        terminal["timestamp_ms"] = jb_inspect.parse_iso_timestamp_ms(terminal["timestamp"])
        terminal["internal_attempts"][0]["verdict"] = "UNKNOWN"
        terminal["internal_attempts"][0]["retry"] = False
        terminal_result = self.summarize(
            [
                qualification_event("initial", assessment_id="assessment-terminal"),
                terminal,
            ],
            sample_size=2,
        )

        self.assertEqual(terminal_result["gate_status"], "fail")
        self.assertTrue(terminal_result["groups"][0]["hidden_terminal_failure"])
        self.assertEqual(terminal_result["hard_failure_counts"]["hidden_terminal_failure"], 1)

    def test_non_clean_cleanup_and_unattributed_unknown_count_as_gate_failures(self):
        cleanup = qualification_event("cleanup")
        cleanup["cleanup_status"] = "deferred"
        cleanup["internal_attempts"][0]["cleanup_status"] = "deferred"
        cleanup_result = self.summarize([cleanup])

        self.assertEqual(cleanup_result["gate_status"], "fail")
        self.assertEqual(cleanup_result["sample_count"], 1)
        self.assertEqual(cleanup_result["hard_failure_counts"]["non_clean_cleanup"], 1)

        kept_warm = qualification_event("kept-warm")
        kept_warm["cleanup_status"] = "kept_warm"
        kept_warm["inspection_attribution"]["cleanup_status"] = "kept_warm"
        kept_warm["internal_attempts"][0]["cleanup_status"] = "kept_warm"
        kept_warm_result = self.summarize([kept_warm])

        self.assertEqual(kept_warm_result["gate_status"], "fail")
        self.assertEqual(kept_warm_result["hard_failure_counts"]["non_clean_cleanup"], 1)

        unknown = qualification_event("unknown", verdict="UNKNOWN")
        unknown["attribution_class"] = "unattributed"
        unknown["unattributed_unknown"] = True
        unknown["inspection_attribution"]["classification"] = "unattributed"
        unknown_result = self.summarize([unknown])

        self.assertEqual(unknown_result["gate_status"], "fail")
        self.assertEqual(unknown_result["sample_count"], 1)
        self.assertEqual(unknown_result["hard_failure_counts"]["unattributed_unknown"], 1)

    def test_repeated_repository_and_project_concentration_is_reported(self):
        shared_repo = "sha256:" + "6" * 64
        shared_project = "sha256:" + "7" * 64
        events = [
            qualification_event("one", repo_path_hash=shared_repo, project_key_hash=shared_project),
            qualification_event(
                "two",
                timestamp="2026-07-26T00:00:02.000Z",
                repo_path_hash=shared_repo,
                project_key_hash=shared_project,
            ),
        ]

        result = self.summarize(events, sample_size=2)

        self.assertEqual(result["gate_status"], "pass")
        self.assertEqual(result["concentration"]["repeated_repositories"], {shared_repo: 2})
        self.assertEqual(result["concentration"]["repeated_projects"], {shared_project: 2})

    def test_gate_status_requires_sample_and_at_least_ninety_five_percent_decisive(self):
        incomplete = self.summarize([qualification_event("only")], sample_size=2)
        self.assertEqual(incomplete["gate_status"], "incomplete")
        self.assertEqual(incomplete["remaining_to_sample"], 1)

        passing_events = [
            qualification_event(
                f"pass-{index}",
                timestamp=f"2026-07-26T00:00:{index + 1:02d}.000Z",
            )
            for index in range(19)
        ]
        passing_events.append(
            qualification_event(
                "pass-unknown",
                timestamp="2026-07-26T00:00:20.000Z",
                verdict="UNKNOWN",
            )
        )
        passing = self.summarize(passing_events, sample_size=20)
        self.assertEqual(passing["gate_status"], "pass")
        self.assertEqual(passing["decisive_rate"], 0.95)

        failing_events = passing_events[:18] + [
            qualification_event("fail-unknown-1", timestamp="2026-07-26T00:00:19.000Z", verdict="UNKNOWN"),
            qualification_event("fail-unknown-2", timestamp="2026-07-26T00:00:20.000Z", verdict="UNKNOWN"),
        ]
        failing = self.summarize(failing_events, sample_size=20)
        self.assertEqual(failing["gate_status"], "fail")
        self.assertIn("decisive_rate_below_threshold", failing["gate_failures"])

    def test_first_sample_is_stable_when_later_events_fail(self):
        anchor = qualification_event(
            "anchor",
            assessment_id="anchor-assessment",
            event_kind="inspection_observation",
            command="get-status",
        )
        first = qualification_event("first", timestamp="2026-07-26T00:00:02.000Z")
        later_cleanup = qualification_event("later-cleanup", timestamp="2026-07-26T00:00:03.000Z")
        later_cleanup["cleanup_status"] = "deferred"
        later_cleanup["inspection_attribution"]["cleanup_status"] = "deferred"
        later_cleanup["internal_attempts"][0]["cleanup_status"] = "deferred"
        result = self.summarize(
            [anchor, first, later_cleanup, '{"broken"'],
            criteria=qualification_payload(after_event_id="anchor"),
        )

        self.assertEqual(result["gate_status"], "pass")
        self.assertEqual(result["sample_count"], 1)
        self.assertEqual(result["qualifying_sample"][0]["assessment_id"], "assessment-first")
        self.assertEqual(result["hard_failure_counts"], {})
        self.assertEqual(result["post_sample_hard_failure_counts"]["invalid_json"], 1)
        self.assertEqual(result["post_sample_hard_failure_counts"]["non_clean_cleanup"], 1)

    def test_frozen_sample_ignores_later_same_assessment_recovery(self):
        anchor = qualification_event(
            "anchor",
            assessment_id="anchor-assessment",
            event_kind="inspection_observation",
            command="get-status",
        )
        decisive = [
            qualification_event(
                f"decisive-{index}",
                timestamp=f"2026-07-26T00:00:{index + 2:02d}.000Z",
            )
            for index in range(19)
        ]
        first_unknown = qualification_event(
            "first-unknown",
            assessment_id="assessment-retry",
            timestamp="2026-07-26T00:00:21.000Z",
            verdict="UNKNOWN",
        )
        first_unknown["internal_attempts"][0]["retry"] = True
        recovery = qualification_event(
            "recovery",
            assessment_id="assessment-retry",
            timestamp="2026-07-26T00:00:23.000Z",
            inspection_run_id=2,
        )
        result = self.summarize(
            [anchor, *decisive, first_unknown, '{"broken"', recovery],
            criteria=qualification_payload(after_event_id="anchor"),
            sample_size=20,
        )

        self.assertEqual(result["gate_status"], "pass")
        self.assertEqual(result["sample_cutoff_line"], 21)
        self.assertEqual(result["decisive_rate"], 0.95)
        frozen = next(group for group in result["qualifying_sample"] if group["assessment_id"] == "assessment-retry")
        self.assertEqual(frozen["verdict"], "UNKNOWN")
        self.assertFalse(frozen["recovered_from_unknown"])
        full = next(group for group in result["groups"] if group["assessment_id"] == "assessment-retry")
        self.assertFalse(full["recovered_from_unknown"])
        self.assertEqual(result["post_sample_hard_failure_counts"]["invalid_json"], 1)
        self.assertEqual(result["post_sample_hard_failure_counts"]["multiple_assessment_events"], 1)

    def test_strict_command_exit_and_human_output_follow_gate_status(self):
        result = self.summarize([qualification_event("pass")])
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = jb_inspect.emit(
                result,
                json_only=False,
                exit_code=jb_inspect.summarize_outcomes_exit_code(result),
                command="summarize-outcomes",
                assess=False,
            )

        self.assertEqual(exit_code, 0)
        self.assertIn("QUALIFICATION_GATE: status=pass sample=1/1", output.getvalue())
        self.assertFalse(any(line.startswith("VERDICT:") for line in output.getvalue().splitlines()))


class Issue458RegressionTest(unittest.TestCase):
    def test_unproven_conflicts_fail_closed_with_stable_attribution(self):
        cases = [
            (
                "legacy",
                "strict",
                {"status": "inspection_in_progress", "inspection_in_progress": True, "inspection_run_id": 42},
                "inspection_in_progress_scope_profile_proof_missing",
            ),
            (
                "legacy-error-only",
                "strict",
                {"error": "inspection_in_progress", "inspection_run_id": 42},
                "inspection_in_progress_scope_profile_proof_missing",
            ),
            (
                "legacy-flag-only",
                "strict",
                {"inspection_in_progress": True, "inspection_run_id": 42},
                "inspection_in_progress_scope_profile_proof_missing",
            ),
            (
                "missing-default-profile-proof",
                "",
                {"status": "inspection_in_progress", "requested_scope": "changed_files", "inspection_run_id": 42},
                "inspection_in_progress_scope_profile_proof_ambiguous",
            ),
            (
                "scope-mismatch",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "whole_project",
                    "requested_profile": "strict",
                    "inspection_run_id": 42,
                },
                "inspection_in_progress_scope_mismatch",
            ),
            (
                "missing-changed-files-selector-proof",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "inspection_run_id": 42,
                },
                "inspection_in_progress_include_unversioned_proof_missing",
            ),
            (
                "profile-mismatch",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "changed_files",
                    "requested_profile": "default",
                    "inspection_run_id": 42,
                },
                "inspection_in_progress_profile_mismatch",
            ),
            (
                "include-unversioned-mismatch",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "include_unversioned": False,
                    "inspection_run_id": 42,
                },
                "inspection_in_progress_include_unversioned_mismatch",
            ),
            (
                "changed-files-mode-mismatch",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "changed_files_mode": "staged",
                    "inspection_run_id": 42,
                },
                "inspection_in_progress_changed_files_mode_mismatch",
            ),
            (
                "ambiguous-layouts",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "scope": "whole_project",
                    "profile": "strict",
                    "inspection_run_id": 42,
                },
                "inspection_in_progress_scope_profile_proof_ambiguous",
            ),
            (
                "ambiguous-run-id",
                "strict",
                {
                    "status": "inspection_in_progress",
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "requested_include_unversioned": True,
                    "requested_changed_files_mode": "all",
                    "inspection_run_id": 42,
                    "run_id": 43,
                },
                "inspection_in_progress_run_id_ambiguous",
            ),
        ]
        route = {"port": 63342, "project_key": "path:/tmp/repo", "session_id": "session"}

        for name, profile, trigger_response, expected_failure in cases:
            with self.subTest(name=name), patch_attr(
                jb_inspect,
                "call_endpoint",
                return_value=trigger_response,
            ) as call:
                result = jb_inspect.run_inspection_on_route(
                    helper_args(scope="changed_files", profile=profile),
                    {"scope": "changed_files"},
                    route,
                )

            self.assertEqual(call.call_count, 1)
            self.assertEqual(result["status"], "inspection_in_progress_unproven")
            self.assertEqual(result["verdict"], "UNKNOWN")
            self.assertEqual(result["verdict_reason"], "inspection_proof_failed")
            self.assertIn(expected_failure, result["proof_failures"])
            self.assertTrue(result["transport_state_unknown"])
            self.assertEqual(result["inspection_attribution"]["code"], "inspection_proof_failed")
            self.assertEqual(result["inspection_attribution"]["phase"], "trigger")
            self.assertEqual(result["inspection_attribution"]["http_status"], 409)
            self.assertTrue(jb_inspect.should_defer_lifecycle_cleanup(result, {"opened_by_helper": True}))

    def test_matching_conflict_proof_adopts_only_the_proven_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/repo", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append((endpoint, params))
            if endpoint == "trigger":
                return {
                    "status": "inspection_in_progress",
                    "inspection_in_progress": True,
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "requested_include_unversioned": True,
                    "requested_changed_files_mode": "all",
                    "inspection_run_id": 42,
                    "route": route,
                }
            if endpoint == "wait":
                return {"status": "results_available", "inspection_run_id": 42, "snapshot_run_id": 42}
            if endpoint == "problems":
                return {
                    "status": "results_available",
                    "inspection_run_id": 42,
                    "snapshot_run_id": 42,
                    "total_problems": 0,
                    "problems": [],
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(scope="changed_files", profile="strict", timeout_ms=1000, poll_ms=1),
                {"scope": "changed_files"},
                route,
            )

        self.assertEqual(result["verdict"], "GREEN")
        self.assertEqual([endpoint for endpoint, _ in calls], ["trigger", "wait", "problems"])
        self.assertEqual(calls[1][1]["inspection_run_id"], 42)
        self.assertEqual(calls[2][1]["inspection_run_id"], 42)

    def test_matching_conflict_timeout_never_cancels_foreign_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/repo", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append(endpoint)
            if endpoint == "trigger":
                return {
                    "status": "inspection_in_progress",
                    "inspection_in_progress": True,
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "requested_include_unversioned": True,
                    "requested_changed_files_mode": "all",
                    "inspection_run_id": 42,
                    "route": route,
                }
            if endpoint == "wait":
                return {
                    "status": "timed_out",
                    "timed_out": True,
                    "inspection_in_progress": True,
                    "inspection_run_id": 42,
                }
            if endpoint == "problems":
                return {
                    "status": "running",
                    "inspection_in_progress": True,
                    "inspection_run_id": 42,
                    "problems": [],
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(scope="changed_files", profile="strict", timeout_ms=1000, poll_ms=1),
                {"scope": "changed_files"},
                route,
            )

        self.assertEqual(calls, ["trigger", "wait", "problems"])
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertEqual(result["cancellation"]["status"], "not_requested")
        self.assertEqual(result["cancellation"]["reason"], "foreign_run_not_owned")
        self.assertFalse(result["cancellation"]["requested"])

    def test_matching_conflict_transport_timeout_never_cancels_foreign_run(self):
        route = {"port": 63342, "project_key": "path:/tmp/repo", "session_id": "session"}
        calls = []

        def fake_call(active_route, endpoint, params, timeout=None):
            calls.append(endpoint)
            if endpoint == "trigger":
                return {
                    "status": "inspection_in_progress",
                    "inspection_in_progress": True,
                    "requested_scope": "changed_files",
                    "requested_profile": "strict",
                    "requested_include_unversioned": True,
                    "requested_changed_files_mode": "all",
                    "inspection_run_id": 42,
                    "route": route,
                }
            if endpoint == "wait":
                raise jb_inspect.InspectError(
                    "Inspection API timed out on port 63342: timed out",
                    3,
                    {"error_reason": "inspection_api_timeout", "endpoint": "wait", "port": 63342},
                )
            if endpoint == "status":
                return {
                    "status": "running",
                    "inspection_in_progress": True,
                    "inspection_run_id": 42,
                }
            self.fail(f"unexpected endpoint: {endpoint}")

        with patch_attr(jb_inspect, "call_endpoint", side_effect=fake_call):
            result = jb_inspect.run_inspection_on_route(
                helper_args(scope="changed_files", profile="strict", timeout_ms=1000, poll_ms=1),
                {"scope": "changed_files"},
                route,
            )

        self.assertEqual(calls, ["trigger", "wait", "status"])
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertEqual(result["cancellation"]["status"], "not_requested")
        self.assertEqual(result["cancellation"]["reason"], "foreign_run_not_owned")
        self.assertFalse(result["cancellation"]["requested"])

    def test_trigger_and_problems_preserve_scope_parameters(self):
        route = {"port": 63342, "project_key": "path:/tmp/repo", "session_id": "session"}
        for include_unversioned, changed_files_mode in ((False, "staged"), (True, "unstaged"), (True, "all")):
            calls = []

            def fake_call(active_route, endpoint, params, timeout=None):
                calls.append((endpoint, params))
                if endpoint == "trigger":
                    return {"status": "triggered", "run_id": 7, "route": route}
                if endpoint == "wait":
                    return {"status": "results_available", "inspection_run_id": 7, "snapshot_run_id": 7}
                if endpoint == "problems":
                    return {
                        "status": "results_available",
                        "inspection_run_id": 7,
                        "snapshot_run_id": 7,
                        "total_problems": 0,
                        "problems": [],
                    }
                self.fail(f"unexpected endpoint: {endpoint}")

            args = helper_args(
                scope="changed_files",
                include_unversioned=include_unversioned,
                changed_files_mode=changed_files_mode,
                profile="strict",
                max_files=25,
                timeout_ms=1000,
                poll_ms=1,
            )
            with self.subTest(include_unversioned=include_unversioned, changed_files_mode=changed_files_mode), patch_attr(
                jb_inspect,
                "call_endpoint",
                side_effect=fake_call,
            ):
                jb_inspect.run_inspection_on_route(args, {"scope": "changed_files"}, route)

            trigger_request = calls[0][1]
            problems_request = calls[2][1]
            for key in ("scope", "include_unversioned", "changed_files_mode", "profile", "max_files"):
                self.assertEqual(problems_request[key], trigger_request[key])

    def test_truncated_semantic_diagnostics_override_green_with_stable_reason(self):
        semantic_file = {
            "path": "/tmp/a.py",
            "valid": True,
            "directory": False,
            "file_type": "Python",
            "psi_language": "Python",
            "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
            "in_content": True,
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "capture_diagnostic": {
                "scope_file_resolved_count": 3,
                "scope_file_diagnostics": [semantic_file, {**semantic_file, "path": "/tmp/b.py"}],
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], jb_inspect.SEMANTIC_COVERAGE_TRUNCATED_REASON)
        self.assertEqual(payload["semantic_coverage"]["diagnostic_file_count"], 2)
        self.assertEqual(payload["semantic_coverage"]["unproven_file_count"], 1)
        self.assertEqual(payload["inspection_attribution"]["code"], jb_inspect.SEMANTIC_COVERAGE_TRUNCATED_REASON)
        self.assertEqual(payload["attribution_class"], "legitimate_fail_closed")
        self.assertEqual(payload["bucket"], "tool_bug")

    def test_aggregate_semantic_proof_allows_bounded_diagnostic_details(self):
        semantic_file = {
            "path": "/tmp/a.py",
            "valid": True,
            "directory": False,
            "file_type": "Python",
            "psi_language": "Python",
            "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
            "in_content": True,
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": {
                "scope_file_resolved_count": 30,
                "scope_file_diagnostics": [semantic_file, {**semantic_file, "path": "/tmp/b.py"}],
                "scope_file_diagnostics_truncated": True,
                "scope_file_diagnostics_complete": False,
                "scope_file_semantic_evidence_complete": True,
                "scope_file_semantic_coverage": {
                    "schema_version": 1,
                    "evaluated_file_count": 30,
                    "unproven_file_count": 0,
                    "missing_file_count": 0,
                    "reason_counts": {},
                    "missing_files": [],
                    "metadata_file_count": 0,
                    "metadata_files": [],
                },
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["verdict_reason"], "no_matching_findings")
        self.assertNotIn("semantic_coverage", payload)

    def test_aggregate_semantic_proof_preserves_excluded_lockfile_role_beyond_detail_limit(self):
        semantic_file = {
            "path": "/tmp/a.py",
            "valid": True,
            "directory": False,
            "file_type": "Python",
            "psi_language": "Python",
            "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
            "in_content": True,
            "is_excluded": False,
        }
        lockfile = {
            "path": "/tmp/uv.lock",
            "valid": True,
            "directory": False,
            "file_type": "PLAIN_TEXT",
            "psi_language": "TEXT",
            "psi_class": "com.intellij.psi.impl.source.PsiPlainTextFile",
            "in_content": False,
            "in_source": False,
            "is_excluded": True,
            "coverage_role": "excluded_dependency_lockfile",
            "classification": "excluded_dependency_lockfile",
            "coverage_required": False,
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "GREEN",
            "inspection_verdict_reason": "no_matching_findings",
            "capture_diagnostic": {
                "scope_file_resolved_count": 30,
                "scope_file_diagnostics": [semantic_file],
                "scope_file_diagnostics_truncated": True,
                "scope_file_diagnostics_complete": False,
                "scope_file_semantic_evidence_complete": True,
                "scope_file_semantic_coverage": {
                    "schema_version": 1,
                    "evaluated_file_count": 30,
                    "unproven_file_count": 0,
                    "missing_file_count": 0,
                    "reason_counts": {},
                    "missing_files": [],
                    "metadata_file_count": 1,
                    "metadata_files": [lockfile],
                },
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["semantic_coverage"]["status"], "satisfied")
        self.assertEqual(payload["semantic_coverage"]["metadata_file_count"], 1)
        self.assertEqual(
            payload["semantic_coverage"]["metadata_files"][0]["classification"],
            "excluded_dependency_lockfile",
        )

    def test_aggregate_semantic_proof_preserves_missing_file_beyond_detail_limit(self):
        semantic_file = {
            "path": "/tmp/a.py",
            "valid": True,
            "directory": False,
            "file_type": "Python",
            "psi_language": "Python",
            "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
            "in_content": True,
        }
        text_only_file = {
            "path": "/tmp/View.swift",
            "valid": True,
            "directory": False,
            "file_type": "TextMate",
            "psi_language": "textmate",
            "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
            "in_content": True,
            "reasons": ["non_semantic_fallback"],
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
            "capture_diagnostic": {
                "scope_file_resolved_count": 30,
                "scope_file_diagnostics": [semantic_file, {**semantic_file, "path": "/tmp/b.py"}],
                "scope_file_diagnostics_truncated": True,
                "scope_file_diagnostics_complete": False,
                "scope_file_semantic_evidence_complete": True,
                "scope_file_semantic_coverage": {
                    "schema_version": 1,
                    "evaluated_file_count": 30,
                    "unproven_file_count": 0,
                    "missing_file_count": 1,
                    "reason_counts": {"non_semantic_fallback": 1},
                    "missing_files": [text_only_file],
                    "metadata_file_count": 0,
                    "metadata_files": [],
                },
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON)
        self.assertEqual(payload["semantic_coverage"]["evaluated_file_count"], 30)
        self.assertEqual(payload["semantic_coverage"]["diagnostic_file_count"], 2)
        self.assertEqual(payload["semantic_coverage"]["unproven_file_count"], 0)
        self.assertEqual(payload["semantic_coverage"]["missing_file_count"], 1)
        self.assertEqual(payload["semantic_coverage"]["files"][0]["path"], "/tmp/View.swift")

    def test_aggregate_text_only_semantic_proof_can_be_explicitly_allowed(self):
        text_only_file = {
            "path": "/tmp/View.swift",
            "valid": True,
            "directory": False,
            "file_type": "TextMate",
            "psi_language": "textmate",
            "psi_class": "org.jetbrains.plugins.textmate.psi.TextMateFile",
            "in_content": True,
            "reasons": ["non_semantic_fallback"],
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "problems": [],
            "inspection_verdict": "UNKNOWN",
            "inspection_verdict_reason": jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON,
            "proof_failures": [jb_inspect.SEMANTIC_COVERAGE_MISSING_REASON],
            "allow_text_only_coverage": True,
            "capture_diagnostic": {
                "scope_file_resolved_count": 30,
                "scope_file_diagnostics": [],
                "scope_file_diagnostics_truncated": True,
                "scope_file_semantic_evidence_complete": True,
                "scope_file_semantic_coverage": {
                    "schema_version": 1,
                    "evaluated_file_count": 30,
                    "unproven_file_count": 0,
                    "missing_file_count": 1,
                    "reason_counts": {"non_semantic_fallback": 1},
                    "missing_files": [text_only_file],
                    "metadata_file_count": 0,
                    "metadata_files": [],
                },
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "GREEN")
        self.assertEqual(payload["verdict_reason"], "text_only_coverage_allowed")
        self.assertEqual(payload["semantic_coverage"]["status"], "text_only_allowed")

    def test_malformed_aggregate_semantic_proof_still_fails_closed(self):
        semantic_file = {
            "path": "/tmp/a.py",
            "valid": True,
            "directory": False,
            "file_type": "Python",
            "psi_language": "Python",
            "psi_class": "com.jetbrains.python.psi.impl.PyFileImpl",
            "in_content": True,
        }
        payload = {
            "status": "results_available",
            "clean": True,
            "total_problems": 0,
            "inspection_verdict": "GREEN",
            "capture_diagnostic": {
                "scope_file_resolved_count": 3,
                "scope_file_diagnostics": [semantic_file, {**semantic_file, "path": "/tmp/b.py"}],
                "scope_file_semantic_evidence_complete": True,
                "scope_file_semantic_coverage": {
                    "schema_version": 1,
                    "evaluated_file_count": 3,
                    "unproven_file_count": 0,
                    "missing_file_count": 0,
                    "reason_counts": {"non_semantic_fallback": 1},
                    "metadata_file_count": 0,
                },
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], jb_inspect.SEMANTIC_COVERAGE_TRUNCATED_REASON)

    def test_disk_freshness_recovery_survives_bounded_retry(self):
        for exit_reason in ("disk_psi_content_mismatch", "scoped_file_unavailable", "scoped_psi_unavailable", "scoped_disk_read_failed"):
            for exhausted in (False, True):
                with self.subTest(exit_reason=exit_reason, exhausted=exhausted):
                    payload = {
                        "status": "capture_incomplete", "capture_incomplete": True,
                        "capture_incomplete_reason": "inspection_inputs_changed",
                        "capture_diagnostic": {"exit_reason": exit_reason},
                        "retry_exhausted": exhausted, "internal_retry_count": int(exhausted),
                        "total_problems": 0, "problems": [],
                    }
                    jb_inspect.apply_verdict(payload)
                    result = payload["agent_result"]
                    self.assertEqual(result["verdict"], "UNKNOWN")
                    self.assertEqual(payload["verdict_reason"], "inspection_inputs_changed")
                    self.assertEqual(result["retry_policy"]["retry"], not exhausted)
                    self.assertIn("Reload from Disk", result["next_action"])
                    self.assertIn("modification time", result["next_action"])
                    if exhausted:
                        self.assertIn("Stop retrying", result["next_action"])

    def test_inspection_inputs_changed_is_retryable_and_attributed(self):
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "inspection_inputs_changed",
            "total_problems": 0,
            "problems": [],
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["verdict_reason"], "inspection_inputs_changed")
        self.assertEqual(payload["bucket"], "capture_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertEqual(payload["attribution_class"], "legitimate_fail_closed")
        self.assertTrue(jb_inspect.should_retry_unknown_result(payload))

    def test_project_analysis_not_ready_is_retryable_and_attributed(self):
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "project_analysis_not_ready",
            "inspection_attribution": {
                "classification": "unattributed",
                "code": "project_analysis_not_ready",
            },
            "total_problems": 0,
            "problems": [],
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "capture_not_ready")
        self.assertTrue(payload["retry_policy"]["retry"])
        self.assertEqual(payload["retry_policy"]["max_attempts"], 3)
        self.assertEqual(payload["attribution_class"], "legitimate_fail_closed")
        self.assertNotIn("unattributed_unknown", payload)

    def test_language_sdk_missing_is_terminal_environment_blocker(self):
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "language_sdk_missing",
            "total_problems": 0,
            "problems": [],
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertEqual(payload["attribution_class"], "configuration_blocked")
        self.assertIn("documented language/SDK setup", payload["verdict_next_action"])
        self.assertIn("one fresh `agent-inspect` assessment", payload["verdict_next_action"])
        self.assertIn("do not guess a Python version", payload["verdict_next_action"])
        self.assertIn("Do not repeat the failed assessment unchanged", payload["verdict_next_action"])
        self.assertIn("absent, ambiguous, or requires global/system changes", payload["verdict_next_action"])

    def test_language_sdk_missing_remains_terminal_after_internal_retry_exhaustion(self):
        payload = {
            "status": "capture_incomplete",
            "capture_incomplete": True,
            "capture_incomplete_reason": "language_sdk_missing",
            "total_problems": 0,
            "problems": [],
            "repository_preparation": {
                "configured": True,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                "generated_state_snapshot": {
                    "all_present": True,
                    "paths": [{"path": ".venv", "exists": True, "kind": "directory"}],
                },
            },
        }

        jb_inspect.apply_verdict(payload)

        self.assertEqual(payload["verdict"], "UNKNOWN")
        self.assertEqual(payload["bucket"], "environment_blocked")
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertEqual(payload["attribution_class"], "configuration_blocked")
        self.assertIn("documented language/SDK setup", payload["verdict_next_action"])
        self.assertNotIn("Run the configured repository preparation command", payload["verdict_next_action"])

    def test_prepared_sdk_retry_requires_venv_inside_active_lane_project(self):
        payload = {
            "context": {
                "worktree_root": "/tmp/repo",
                "project_path": "/tmp/repo/services/api",
                "repository_preparation": {
                    "configured": True,
                    "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                    "target_worktree": "/tmp/repo",
                    "generated_state_snapshot": {
                        "all_present": True,
                        "paths": [
                            {"path": ".venv", "exists": True, "kind": "directory"},
                            {"path": "services/api/.venv", "exists": True, "kind": "directory"},
                        ],
                    },
                },
            }
        }

        self.assertTrue(jb_inspect.prepared_python_sdk_discovery_pending(payload))
        payload["context"]["repository_preparation"]["generated_state_snapshot"]["paths"] = [
            {"path": ".venv", "exists": True, "kind": "directory"}
        ]
        self.assertFalse(jb_inspect.prepared_python_sdk_discovery_pending(payload))

    def test_prepared_sdk_retry_waits_for_route_readiness_once(self):
        error = jb_inspect.InspectError(
            "SDK missing",
            3,
            {"error_reason": "language_sdk_missing"},
        )
        context = {
            "worktree_root": "/tmp/repo",
            "project_path": "/tmp/repo/services/api",
            "repository_preparation": {
                "configured": True,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                "target_worktree": "/tmp/repo",
                "generated_state_snapshot": {
                    "all_present": True,
                    "paths": [
                        {"path": "services/api/.venv", "exists": True, "kind": "directory"}
                    ],
                },
            },
        }
        retry_readiness = {"status": "ready", "ready": True}

        with (
            patch_attr(
                jb_inspect,
                "wait_until_route_ready",
                side_effect=error,
            ) as wait_route,
            patch_attr(
                jb_inspect,
                "wait_for_internal_retry_readiness",
                return_value=retry_readiness,
            ) as wait_retry,
        ):
            result = jb_inspect.wait_until_route_ready_with_prepared_sdk_retry(
                helper_args(),
                context,
                {"port": 63343},
                1000,
            )

        self.assertEqual(wait_route.call_count, 1)
        wait_retry.assert_called_once()
        self.assertEqual(result["internal_retry_count"], 1)
        self.assertEqual(result["internal_retry_reason"], "language_sdk_missing")

    def test_prepared_sdk_retry_failure_preserves_sdk_configuration_guidance(self):
        error = jb_inspect.InspectError(
            "SDK missing",
            3,
            {"error_reason": "language_sdk_missing"},
        )
        context = {
            "worktree_root": "/tmp/repo",
            "project_path": "/tmp/repo/services/api",
            "repository_preparation": {
                "configured": True,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                "target_worktree": "/tmp/repo",
                "generated_state_snapshot": {
                    "all_present": True,
                    "paths": [
                        {"path": "services/api/.venv", "exists": True, "kind": "directory"}
                    ],
                },
            },
        }

        with (
            patch_attr(jb_inspect, "wait_until_route_ready", side_effect=error),
            patch_attr(
                jb_inspect,
                "wait_for_internal_retry_readiness",
                return_value={"status": "timeout", "ready": False},
            ),
        ):
            with self.assertRaises(jb_inspect.InspectError) as raised:
                jb_inspect.wait_until_route_ready_with_prepared_sdk_retry(
                    helper_args(),
                    context,
                    {"port": 63343},
                    1000,
                )

        payload = jb_inspect.error_payload(raised.exception, helper_args())
        jb_inspect.apply_verdict(payload)
        self.assertFalse(payload["retry_policy"]["retry"])
        self.assertIn("configure the resulting SDK for the selected files", payload["verdict_next_action"])
        self.assertEqual(payload["internal_retry_count"], 0)

    def test_prepared_retry_evidence_is_promoted_to_top_level_result(self):
        result = {}
        retry_readiness = {"status": "ready", "ready": True}
        prepared = {
            "readiness_barrier": {
                "internal_retry_count": 1,
                "internal_retry_reason": "language_sdk_missing",
                "internal_retry_readiness": retry_readiness,
            }
        }

        jb_inspect.apply_prepared_retry_evidence(result, prepared)

        self.assertEqual(result["internal_retry_count"], 1)
        self.assertEqual(result["internal_retry_reason"], "language_sdk_missing")
        self.assertEqual(result["internal_retry_readiness"], retry_readiness)
        self.assertEqual(result["prepared_internal_retry_count"], 1)
        self.assertEqual(result["prepared_internal_retry_reason"], "language_sdk_missing")
        self.assertEqual(result["prepared_internal_retry_readiness"], retry_readiness)

    def test_prepared_retry_evidence_does_not_overwrite_inspection_retry_evidence(self):
        inspection_readiness = {"status": "timeout", "ready": False}
        prepared_readiness = {"status": "ready", "ready": True}
        result = {
            "internal_retry_count": 2,
            "internal_retry_reason": "inspection_unknown",
            "internal_retry_readiness": inspection_readiness,
            "internal_retry_skipped": True,
        }
        prepared = {
            "readiness_barrier": {
                "internal_retry_count": 1,
                "internal_retry_reason": "language_sdk_missing",
                "internal_retry_readiness": prepared_readiness,
            }
        }

        jb_inspect.apply_prepared_retry_evidence(result, prepared)

        self.assertEqual(result["internal_retry_count"], 2)
        self.assertEqual(result["internal_retry_reason"], "inspection_unknown")
        self.assertEqual(result["internal_retry_readiness"], inspection_readiness)
        self.assertTrue(result["internal_retry_skipped"])
        self.assertEqual(result["prepared_internal_retry_count"], 1)
        self.assertEqual(result["prepared_internal_retry_reason"], "language_sdk_missing")
        self.assertEqual(result["prepared_internal_retry_readiness"], prepared_readiness)

    def test_prepared_sdk_retry_does_not_use_root_venv_for_nested_project(self):
        error = jb_inspect.InspectError(
            "SDK missing",
            3,
            {"error_reason": "language_sdk_missing"},
        )
        context = {
            "worktree_root": "/tmp/repo",
            "project_path": "/tmp/repo/services/api",
            "repository_preparation": {
                "configured": True,
                "execution_state": jb_inspect.REPOSITORY_PREPARATION_SUCCEEDED,
                "target_worktree": "/tmp/repo",
                "generated_state_snapshot": {
                    "all_present": True,
                    "paths": [{"path": ".venv", "exists": True, "kind": "directory"}],
                },
            },
        }

        with (
            patch_attr(jb_inspect, "wait_until_route_ready", side_effect=error),
            patch_attr(jb_inspect, "wait_for_internal_retry_readiness") as wait_retry,
        ):
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.wait_until_route_ready_with_prepared_sdk_retry(
                    helper_args(),
                    context,
                    {"port": 63343},
                    1000,
                )

        wait_retry.assert_not_called()

    def test_configuration_blockers_name_repository_preparation_when_configured(self):
        preparation = {
            "configured": True,
            "command": "uv run prepare-project.py --python 3.12",
            "source": "qualityGate.inspection.prepare",
            "execution_state": "not_run",
            "target_worktree": "/tmp/linked-worktree",
        }
        for reason in ("language_sdk_missing", jb_inspect.PROJECT_CONTENT_ROOTS_MISSING_REASON):
            with self.subTest(reason=reason):
                action = jb_inspect.next_action_for_unknown(
                    reason,
                    {"context": {"repository_preparation": preparation}},
                )

                self.assertIn("uv run prepare-project.py --python 3.12", action)
                self.assertIn("/tmp/linked-worktree", action)
                if reason == "language_sdk_missing":
                    self.assertIn("one fresh `agent-inspect` assessment", action)
                    self.assertIn("Do not repeat the failed assessment unchanged", action)
                    self.assertNotIn("then rerun inspection", action)

    def test_missing_ide_route_uses_canonical_repair_advice(self):
        for reason in ("ide_selection_required", "ide_config_ambiguous", "ide_config_missing"):
            with self.subTest(reason=reason):
                action = jb_inspect.next_action_for_unknown(reason, {})

                self.assertEqual(action, jb_inspect.IDE_ROUTE_CONFIGURATION_NEXT_ACTION)

    def test_configuration_blockers_keep_fallback_without_repository_preparation(self):
        language_action = jb_inspect.next_action_for_unknown("language_sdk_missing", {})
        content_root_action = jb_inspect.next_action_for_unknown(
            jb_inspect.PROJECT_CONTENT_ROOTS_MISSING_REASON,
            {},
        )

        self.assertEqual(
            language_action,
            "Use the exact worktree's documented language/SDK setup to restore the missing language SDK and configure the "
            "resulting SDK for the selected files in the current IDE project. For Python, use the repository's documented Python "
            "setup; do not guess a Python version. Do not commit IDE configuration. Then run one fresh `agent-inspect` assessment. "
            "Do not repeat the failed assessment unchanged. "
            "If the documented setup is absent, ambiguous, or requires global/system changes, report that blocker instead.",
        )
        self.assertIn("diagnostic", content_root_action)
        self.assertNotRegex(content_root_action.lower(), r"rerun|retry once|start a new inspection")
        self.assertFalse(
            jb_inspect.retry_policy_for(
                "UNKNOWN",
                "environment_blocked",
                jb_inspect.PROJECT_CONTENT_ROOTS_MISSING_REASON,
            )["retry"]
        )

    def test_truncation_handles_missing_malformed_and_text_only_diagnostics(self):
        cases = [
            [],
            [{"path": "/tmp/a.py", "valid": True, "file_type": "Python", "psi_language": "Python", "in_content": True}, "invalid"],
            [{"path": "/tmp/a.swift", "valid": True, "file_type": "TextMate", "psi_language": "TextMate", "in_content": True}],
        ]
        for diagnostics in cases:
            with self.subTest(diagnostics=diagnostics):
                coverage = jb_inspect.semantic_coverage_for_payload(
                    {
                        "allow_text_only_coverage": True,
                        "capture_diagnostic": {
                            "scope_file_resolved_count": len(diagnostics) + 1,
                            "scope_file_diagnostics": diagnostics,
                        },
                    }
                )

                self.assertIsNotNone(coverage)
                self.assertEqual(coverage["status"], "missing")
                self.assertEqual(coverage["reason"], jb_inspect.SEMANTIC_COVERAGE_TRUNCATED_REASON)

    def test_durable_redaction_hashes_embedded_paths(self):
        posix_path = "/Users/cbusillo/project/src/main.py"
        windows_path = r"C:\Users\cbusillo\project\main.py"
        quoted_path = "/Users/Chris Busillo/My Project/test.py"
        unquoted_path = "/Users/Chris Busillo/My Project/unquoted.py"
        unquoted_windows_path = r"C:\Users\Chris Busillo\My Project\unquoted.py"
        no_extension_path = "/Users/Chris Busillo/My Project"
        no_extension_windows_path = r"C:\Users\Chris Busillo\My Project"
        no_extension_file_uri = "file:///Users/Chris Busillo/My Project"
        file_uri = "file:///private/tmp/inspection.log"
        redacted = jb_inspect.redact_durable_log(
            {
                "capture_diagnostic": {
                    "exception_message": f"Read {posix_path}:12:4, then {windows_path}.",
                    "engine_errors": [
                        f'Failed at "{quoted_path}".',
                        f"Trace: {file_uri}!",
                        f"Read {unquoted_path}:9 before continuing.",
                        f"Read {unquoted_windows_path}:11 before continuing.",
                        no_extension_path,
                        no_extension_windows_path,
                        no_extension_file_uri,
                        f"Read {no_extension_path} before continuing.",
                    ],
                }
            }
        )["capture_diagnostic"]

        self.assertEqual(
            redacted["exception_message"],
            f"Read <path:{jb_inspect.stable_value_hash(posix_path)}>:12:4, then "
            f"<path:{jb_inspect.stable_value_hash(windows_path)}>.",
        )
        self.assertEqual(
            redacted["engine_errors"][0],
            f'Failed at "<path:{jb_inspect.stable_value_hash(quoted_path)}>".',
        )
        self.assertEqual(
            redacted["engine_errors"][1],
            f"Trace: <path:{jb_inspect.stable_value_hash(file_uri)}>!",
        )
        self.assertEqual(
            redacted["engine_errors"][2],
            f"Read <path:{jb_inspect.stable_value_hash(unquoted_path)}>:9 before continuing.",
        )
        self.assertEqual(
            redacted["engine_errors"][3],
            f"Read <path:{jb_inspect.stable_value_hash(unquoted_windows_path)}>:11 before continuing.",
        )
        self.assertEqual(
            redacted["engine_errors"][4],
            f"<path:{jb_inspect.stable_value_hash(no_extension_path)}>",
        )
        self.assertEqual(
            redacted["engine_errors"][5],
            f"<path:{jb_inspect.stable_value_hash(no_extension_windows_path)}>",
        )
        self.assertEqual(
            redacted["engine_errors"][6],
            f"<path:{jb_inspect.stable_value_hash(no_extension_file_uri)}>",
        )
        self.assertTrue(redacted["engine_errors"][7].startswith("Read <path:sha256:"))
        self.assertNotIn("Chris Busillo", redacted["engine_errors"][7])
        self.assertNotIn("My Project", redacted["engine_errors"][7])

    def test_durable_redaction_preserves_urls_routes_and_identifiers(self):
        values = [
            "See " + "ht" + "tps://example.com/docs/ref and " + "ht" + "tp://127.0.0.1:63342/api/inspection/status.",
            "Endpoints /api/v1/problems and /v2/projects/42 remain stable.",
            "Routes /users/42 and /projects/abc/status remain stable.",
            "Identifiers org/example/Foo, owner/repo/issues/458, scope/profile, and/or remain unchanged.",
            "Package-like /com/example/Foo.java identifiers remain unchanged.",
            "MIME application/x-www-form-urlencoded is not a local path.",
        ]

        self.assertEqual(jb_inspect.redact_durable_log(values), values)


class PythonSdkFailureReportingTests(unittest.TestCase):
    def test_sdk_worker_close_refusal_preserves_lease_and_specific_cleanup_action(self):
        lease = {"lease_id": "owned-lease", "opened_by_helper": True}
        close_result = {"status": "close_skipped", "reason": "python_sdk_preparation_in_progress"}
        error = jb_inspect.InspectError("SDK deadline", 3, {"error_reason": "python_sdk_preparation_timeout"})
        with patch_attr(jb_inspect, "write_lease") as write:
            result = jb_inspect.defer_failed_preparation_cleanup(
                lease, None, error, "python_sdk_preparation", cleanup_result=close_result,
            )
        self.assertEqual(lease["state"], "cleanup_pending")
        self.assertEqual(result["reason"], "python_sdk_preparation_in_progress")
        self.assertEqual(result["close_result"], close_result)
        self.assertIn("after the worker exits", result["next_action"])
        self.assertIn("Do not repeat SDK preparation", lease["cleanup_next_action"])
        write.assert_called_once_with(lease)

    def test_sdk_failures_distinguish_configuration_ownership_and_tool_errors_without_retry(self):
        for suffix, bucket in [
            ("sdk_conflict", "environment_blocked"),
            ("existing_sdk_incomplete", "environment_blocked"),
            ("existing_sdk_changed", "environment_blocked"),
            ("persistence_failed", "tool_bug"),
            ("inspection_in_progress", "environment_blocked"),
            ("session_drift", "route_not_ready"),
            ("token_mismatch", "route_not_ready"),
            ("request_failed", "tool_bug"),
            ("invalid_response", "tool_bug"),
            ("registration_failed", "tool_bug"),
            ("timeout", "tool_bug"),
        ]:
            with self.subTest(suffix=suffix):
                actual = jb_inspect.outcome_bucket({}, "python_sdk_preparation_" + suffix)
                self.assertEqual(actual, bucket)
                self.assertNotIn(actual, jb_inspect.UNKNOWN_RETRY_BUCKETS)


    def test_worktree_mutation_guidance_preserves_user_and_agent_edits(self):
        guidance = jb_inspect.next_action_for_unknown("worktree_mutation_detected", {})

        self.assertIn("Inspect the mutation evidence", guidance)
        self.assertIn("preserve user and agent edits", guidance)
        self.assertIn("coordinate same-worktree writers", guidance)
        self.assertNotIn("IDE-created", guidance)


class ScopedLeaseCleanupTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="scoped-lease-cleanup-")
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.selected_id = "190abb77-89c0-458b-84ce-39a2d638dd91"
        self.foreign_id = "04f75cb7-770c-481c-91fc-67509436dafd"
        self.lease = {
            "lease_id": self.selected_id, "state": "cleanup_failed", "pid": 68466,
            "opened_by_helper": True, "updated_at_ms": 1,
            "worktree_root": "/tmp/selected", "project_key": "path:/tmp/selected",
            "project_instance_id": "session:1", "session_id": "session",
        }
        self.selected_path = self.directory / f"{self.selected_id}.json"
        self.foreign_path = self.directory / f"{self.foreign_id}.json"
        write_json(self.selected_path, self.lease)
        write_json(self.foreign_path, self.lease | {"lease_id": self.foreign_id})
        self.foreign_bytes = self.foreign_path.read_bytes()
        self.enterContext(patch_attr(jb_inspect, "lease_dir", return_value=self.directory))
        self.enterContext(patch_attr(jb_inspect, "now_ms", return_value=100_000_000))
        self.enterContext(patch_attr(jb_inspect, "pid_alive", return_value=False))
        self.discovery = self.enterContext(patch_attr(
            jb_inspect, "discover_routes_for_cleanup", return_value=([], {"session"}),
        ))

    def run_cleanup(self, *selectors, dry_run=False):
        args = jb_inspect.build_parser().parse_args([
            "cleanup-helper-leases", "--dry-run" if dry_run else "--no-dry-run",
            *[value for selector in selectors for value in ("--lease-id", selector)],
        ])
        return jb_inspect.command_cleanup_leases(args)

    def test_selected_stale_lease_is_reconciled_without_touching_foreign_lease(self):
        # Discovery proves the original session exists but its exact project is gone.
        result = self.run_cleanup(self.selected_id)
        self.assertEqual(result["removed"], [str(self.selected_path)])
        self.assertFalse(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)
        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 0)

    def test_scoped_dry_run_proves_selected_removal_without_mutation(self):
        result = self.run_cleanup(self.selected_id, dry_run=True)
        self.assertEqual(result["stale"], [str(self.selected_path)])
        self.assertEqual(result["removed"], [])
        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 0)
        self.discovery.assert_called_once()
        self.assertEqual(result["would_remove"], [str(self.selected_path)])
        self.assertTrue(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def test_invalid_missing_and_multiple_selectors_fail_without_discovery(self):
        cases = [
            (("../other",), "invalid_lease_selector"),
            (("",), "invalid_lease_selector"),
            (("00000000-0000-0000-0000-000000000000",), "lease_not_found"),
            ((self.selected_id, self.foreign_id), "invalid_lease_selector"),
            ((self.selected_id, self.selected_id), "invalid_lease_selector"),
        ]
        for selectors, reason in cases:
            with self.subTest(selectors=selectors):
                result = self.run_cleanup(*selectors)
                self.assertEqual(result["failed"][0]["reason"], reason)
                self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)
                self.assertEqual(result["removed"], [])
        self.discovery.assert_not_called()
        self.assertTrue(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def test_duplicate_payload_identity_and_mismatched_filename_fail_closed(self):
        write_json(self.foreign_path, self.lease)
        result = self.run_cleanup(self.selected_id)
        self.assertEqual(result["failed"][0]["reason"], "lease_identity_ambiguous")
        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)
        self.selected_path.unlink()
        result = self.run_cleanup(self.selected_id)
        self.assertEqual(result["failed"][0]["reason"], "lease_identity_mismatch")
        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)
        self.discovery.assert_not_called()
        self.assertTrue(self.foreign_path.exists())

    def test_live_pid_with_old_cleanup_failed_lease_is_not_forced_stale(self):
        for dry_run in (False, True):
            with self.subTest(dry_run=dry_run), patch_attr(jb_inspect, "pid_alive", return_value=True):
                result = self.run_cleanup(self.selected_id, dry_run=dry_run)
                self.assertEqual(result["unresolved"][0]["reason"], "lease_not_stale")
                self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)
        self.discovery.assert_not_called()
        self.assertTrue(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def test_scoped_discovery_failure_preserves_both_leases(self):
        self.discovery.side_effect = jb_inspect.InspectError("unavailable", 3)
        result = self.run_cleanup(self.selected_id)
        self.assertEqual(result["failed"][0]["reason"], "route_discovery_failed")
        self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)
        self.assertTrue(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def test_cleanup_failed_lease_in_foreign_session_remains_unresolved(self):
        self.discovery.return_value = ([{
            "base_path": "/tmp/selected", "project_instance_id": "foreign:1",
            "session_id": "foreign", "project_key": "path:/tmp/selected",
        }], {"foreign"})
        with patch_attr(jb_inspect, "reclaim_lifecycle_claim") as claim:
            result = self.run_cleanup(self.selected_id)
        claim.assert_not_called()
        self.assertEqual(result["unresolved"][0]["reason"], "ownership_route_unavailable")
        self.assertTrue(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def write_never_opened_lease(self, **changes):
        # project_open_blocked: a live IDE accepted the open, but no exact route ever appeared.
        attempt = {"accepted": True, "ownership_registered": True, "lease_id": self.selected_id,
                   "identity": {"session_id": "session", "port": 63342}}
        write_json(self.selected_path, {
            "lease_id": self.selected_id, "state": "cleanup_pending", "pid": 68466,
            "opened_by_helper": True, "open_request_may_have_been_accepted": False,
            "lifecycle_target_path": "/tmp/selected", "worktree_root": "/tmp/selected",
            "session_id": "session", "ide_port": 63342, "updated_at_ms": 1,
            "open_attempts": [attempt],
        } | changes)

    def probe_ide(self, status, reason, session="session", diagnostic_version=1):
        identity = {"session_id": "session", "port": 63342, "lifecycle_open_diagnostic_version": diagnostic_version}
        body = {"status": status, "reason": reason, "opened": False, "opening_scheduled": False,
                "probe": True, "session_id": session}
        return (
            patch_attr(jb_inspect, "identity_for_port", return_value=identity),
            patch_attr(jb_inspect, "http_get", return_value=jb_inspect.HttpResult(200, body, "http://fixture")),
        )

    def test_live_original_session_reporting_project_not_open_releases_lease(self):
        acknowledged = {"method": "bootstrapped_ide", "accepted": True, "ownership_registered": True,
                        "lease_id": self.selected_id, "identity": {"session_id": "session", "port": 63342}}
        histories = {
            "running_ide": None,
            # The IDE was started first; startup and "no matching IDE" entries sent no open request.
            "bootstrapped_ide": [
                {"method": "running_ide", "accepted": False, "reason": "no_matching_running_ide"},
                {"method": "bootstrap_ide", "accepted": True},
                acknowledged,
            ],
        }
        for name, attempts in histories.items():
            with self.subTest(name):
                self.write_never_opened_lease(**({"open_attempts": attempts} if attempts else {}))
                identity, get = self.probe_ide("not_open", "project_not_open")
                with identity, get as http_get, patch_attr(jb_inspect, "cleanup_lifecycle") as close:
                    preview = self.run_cleanup(self.selected_id, dry_run=True)
                    self.assertEqual(preview["would_remove"], [str(self.selected_path)])
                    self.assertTrue(self.selected_path.exists())
                    result = self.run_cleanup(self.selected_id)
                close.assert_not_called()
                endpoint, params = http_get.call_args.args[1:3]
                self.assertEqual((endpoint, params["probe"], params["lease_id"]),
                                 ("lifecycle/open", "true", self.selected_id))
                self.assertEqual(result["removed"], [str(self.selected_path)])
                self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 0)
                self.assertFalse(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def test_live_original_session_still_opening_or_unprovable_retains_lease(self):
        # The probe cannot fence a request the IDE has not registered yet, so only acknowledged opens qualify.
        ambiguous = {"open_request_may_have_been_accepted": True, "state": "open_requesting", "open_attempts": [{
            "accepted": False, "request_may_have_been_accepted": True, "ownership_registered": False,
            "lease_id": self.selected_id, "identity": {"session_id": "session", "port": 63342},
        }]}
        cases = {
            "opening": ("opening", "already_opening", "session", 1, {}),
            "other_session_answered": ("not_open", "project_not_open", "other-session", 1, {}),
            "plugin_without_probe": ("not_open", "project_not_open", "session", 0, {}),
            "ambiguous_open_request": ("not_open", "project_not_open", "session", 1, ambiguous),
        }
        for name, (status, reason, session, version, changes) in cases.items():
            with self.subTest(name):
                self.write_never_opened_lease(**changes)
                identity, get = self.probe_ide(status, reason, session, version)
                with identity, get as http_get:
                    result = self.run_cleanup(self.selected_id)
                unresolved = result["unresolved"][0]
                self.assertEqual(unresolved["reason"], "ownership_route_unavailable")
                self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 1)
                self.assertTrue(self.selected_path.exists())
                if name in {"opening", "ambiguous_open_request"}:
                    self.assertEqual(unresolved["open_probe"], {"status": status, "reason": reason})
                if name == "plugin_without_probe":
                    # An older plugin would treat the probe as a real open request.
                    http_get.assert_not_called()

    def test_plain_output_names_unresolved_lease_reason_without_inspection_verdict(self):
        self.discovery.return_value = ([{
            "base_path": "/tmp/selected", "project_instance_id": "foreign:1",
            "session_id": "foreign", "project_key": "path:/tmp/selected",
        }], {"foreign"})
        output = io.StringIO()
        argv = [str(SCRIPT_PATH), "cleanup-helper-leases", "--lease-id", self.selected_id, "--no-dry-run"]
        with patch.object(sys, "argv", argv), redirect_stdout(output):
            code = jb_inspect.main()
        text = output.getvalue()
        self.assertEqual(code, 1)
        self.assertIn(f"LEASE_UNRESOLVED: lease_id={self.selected_id} reason=ownership_route_unavailable", text)
        self.assertNotIn("VERDICT:", text)
        self.assertTrue(self.selected_path.exists())

    def test_selection_and_cleanup_remain_inside_lifecycle_lock(self):
        held = []
        class Lock:
            def __enter__(self):
                held.append(True)
            def __exit__(self, *_):
                held.pop()
        original_read = jb_inspect.read_local_leases
        def read_locked():
            self.assertEqual(held, [True])
            return original_read()
        with (
            patch_attr(jb_inspect, "lifecycle_lock", return_value=Lock()),
            patch_attr(jb_inspect, "read_local_leases", side_effect=read_locked),
        ):
            result = self.run_cleanup(self.selected_id)
        self.assertEqual(result["removed"], [str(self.selected_path)])

    def test_default_cleanup_still_processes_all_stale_leases(self):
        result = self.run_cleanup()
        self.assertCountEqual(result["removed"], [str(self.selected_path), str(self.foreign_path)])
        self.assertFalse(self.selected_path.exists())
        self.assertFalse(self.foreign_path.exists())

    def test_normalized_selector_identifies_the_same_lease(self):
        for selector in (self.selected_id.upper(), self.selected_id.replace("-", "")):
            with self.subTest(selector=selector):
                result = self.run_cleanup(selector, dry_run=True)
                self.assertEqual(result["stale"], [str(self.selected_path)])
                self.assertEqual(jb_inspect.classify_cleanup_leases_exit(result), 0)
        self.assertEqual(self.discovery.call_count, 2)
        self.assertTrue(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)

    def test_preexisting_project_releases_only_selected_local_lease_without_discovery(self):
        write_json(self.selected_path, self.lease | {"opened_by_helper": False})
        result = self.run_cleanup(self.selected_id)
        self.assertEqual(result["removed"], [str(self.selected_path)])
        self.assertEqual(result["closed"], [])
        self.discovery.assert_not_called()
        self.assertFalse(self.selected_path.exists())
        self.assertEqual(self.foreign_path.read_bytes(), self.foreign_bytes)


class PythonSdkPreparationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / ".venv").mkdir()
        self.args = jb_inspect.build_parser().parse_args(["inspect-closeout", "--repo", str(self.root)])
        self.context = {"worktree_root": str(self.root), "project_path": str(self.root)}
        self.route = {
            "port": 63342, "project_key": "project", "project_instance_id": "instance",
            "session_id": "session", "ide": {"product_code": "IU", "python_sdk_preparation_version": 1},
        }
        self.lease = {"opened_by_helper": True, "lease_id": "lease"}
        self.preparation = {
            "configured": True, "kind": "python", "command": "uv run prepare-python-project.py",
            "execution_state": "succeeded", "target_worktree": str(self.root),
            "generated_state_snapshot": {
                "all_present": True, "paths": [{"path": ".venv", "kind": "directory", "exists": True}],
            },
        }
        self.response = {
            "status": "prepared", "sdk_preparation_version": 1, "operation": "created",
            "project_key": "project", "project_instance_id": "instance", "session_id": "session",
            "registered_local_python_sdk_count": 1, "assigned_local_python_sdk_count": 1,
            "python_module_count": 2, "assigned_python_module_count": 2, "project_sdk_assigned": True,
        }

    def prepare(self, token="private-token"):
        return jb_inspect.prepare_owned_python_sdk(
            self.args, self.context, self.route, self.lease, token, self.preparation,
        )

    def lifecycle_context(self):
        return self.context | {
            "_repository_preparation_completed": True,
            "repository_preparation": self.preparation,
        }

    def test_owned_prepared_environment_uses_one_explicit_post_with_route_pins(self):
        with patch_attr(jb_inspect, "http_post", return_value=jb_inspect.HttpResult(200, self.response, "redacted")) as post:
            result = self.prepare()
        self.assertEqual(result, self.response)
        post.assert_called_once()
        port, endpoint, params = post.call_args.args
        self.assertEqual((port, endpoint), (63342, "lifecycle/prepare-python-sdk"))
        self.assertEqual(params["lease_id"], "lease")
        self.assertEqual(params["close_token"], "private-token")
        self.assertEqual(params["project_instance_id"], "instance")
        self.assertEqual(params["session_id"], "session")
        self.assertNotIn("interpreter", params)

    def test_unowned_project_is_never_provisioned(self):
        self.lease["opened_by_helper"] = False
        with patch_attr(jb_inspect, "http_post") as post:
            self.assertEqual(self.prepare()["reason"], "helper_ownership_not_proven")
        post.assert_not_called()

    def test_missing_proof_is_never_provisioned(self):
        with patch_attr(jb_inspect, "http_post") as post:
            self.assertEqual(self.prepare(None)["reason"], "helper_ownership_not_proven")
        post.assert_not_called()

    def test_reclaimed_worker_active_lease_cannot_repeat_sdk_preparation(self):
        self.lease["cleanup_result"] = {"status": "close_skipped", "reason": "python_sdk_preparation_in_progress"}
        with patch_attr(jb_inspect, "http_post") as post:
            with self.assertRaises(jb_inspect.InspectError) as caught:
                self.prepare()
        post.assert_not_called()
        self.assertEqual(caught.exception.payload["python_sdk_preparation"]["status"], "not_attempted")
        self.assertEqual(caught.exception.payload["error_reason"], "python_sdk_preparation_in_progress")

    def test_old_plugin_and_web_lane_do_not_receive_mutations(self):
        for ide, expected in [
            ({"product_code": "IU"}, "plugin_capability_unavailable"),
            ({"product_code": "IU", "python_sdk_preparation_version": True}, "plugin_capability_unavailable"),
            ({"product_code": "WS", "python_sdk_preparation_version": 1}, "non_python_ide_lane"),
        ]:
            with self.subTest(ide=ide), patch_attr(jb_inspect, "http_post") as post:
                self.route["ide"] = ide
                self.assertEqual(self.prepare()["reason"], expected)
                post.assert_not_called()

    def test_failed_preparation_and_nested_target_do_not_provision_root_environment(self):
        with patch_attr(jb_inspect, "http_post") as post:
            self.preparation["execution_state"] = "failed"
            self.assertEqual(self.prepare()["status"], "skipped")
            self.preparation["execution_state"] = "succeeded"
            self.context["project_path"] = str(self.root / "nested")
            self.assertEqual(self.prepare()["status"], "skipped")
        post.assert_not_called()

    def test_incomplete_route_cannot_start_sdk_mutation(self):
        del self.route["session_id"]
        with patch_attr(jb_inspect, "http_post") as post:
            with self.assertRaises(jb_inspect.InspectError):
                self.prepare()
        post.assert_not_called()

    def test_read_commands_do_not_provision_even_with_prepared_environment(self):
        for command in ("claim-worktree", "get-status", "resolve-route"):
            with self.subTest(command=command), patch_attr(jb_inspect, "http_post") as post:
                self.args.command = command
                self.assertEqual(self.prepare()["reason"], "preparation_not_requested")
                post.assert_not_called()

    def test_shared_python_preparation_does_not_mutate_a_jvm_only_lane(self):
        self.args.files = ["src/main/Main.kt", "README.md"]
        with patch_attr(jb_inspect, "http_post") as post:
            self.assertEqual(self.prepare()["reason"], "python_not_selected")
        post.assert_not_called()

    def test_success_requires_exact_identity_and_positive_readback_counts(self):
        for field, value in [
            ("session_id", "other"), ("project_key", None), ("project_instance_id", "other"),
            ("registered_local_python_sdk_count", 0), ("assigned_local_python_sdk_count", None),
            ("assigned_local_python_sdk_count", True), ("sdk_preparation_version", 2),
            ("sdk_preparation_version", True),
            ("registered_local_python_sdk_count", 2), ("python_module_count", 0),
            ("assigned_python_module_count", 1), ("project_sdk_assigned", False),
        ]:
            with self.subTest(field=field, value=value):
                response = self.response | {field: value}
                with patch_attr(jb_inspect, "http_post", return_value=jb_inspect.HttpResult(200, response, "redacted")):
                    with self.assertRaises(jb_inspect.InspectError):
                        self.prepare()

    def test_transport_failure_is_not_retried_and_preserves_preparation_diagnostic(self):
        error = jb_inspect.InspectError("Timed out", 3, {"error_reason": "timeout"})
        with patch_attr(jb_inspect, "http_post", side_effect=error) as post:
            with self.assertRaises(jb_inspect.InspectError) as caught:
                self.prepare()
        post.assert_called_once()
        self.assertEqual(caught.exception.payload["python_sdk_preparation"]["status"], "failed")
        self.assertEqual(caught.exception.payload["python_sdk_preparation"]["reason"], "timeout")
        self.assertEqual(caught.exception.payload["error_reason"], "python_sdk_preparation_request_failed")

    def test_prepare_lifecycle_claims_then_posts_sdk_preparation_then_waits_for_readiness(self):
        lease = {"lease_id": "lease", "state": "preparing"}
        events = []
        writes = []

        def claim(*_args):
            events.append("claim")
            return claimed_lifecycle_result(self.route, "private-token")

        def post(port, endpoint, params, timeout):
            events.append("sdk_post")
            self.assertEqual(lease["preparation_stage"], "python_sdk_preparation")
            self.assertEqual((port, endpoint, timeout), (63342, "lifecycle/prepare-python-sdk", 75.0))
            self.assertEqual(params["close_token"], "private-token")
            return jb_inspect.HttpResult(200, self.response, "redacted")

        def readiness(*_args):
            events.append("readiness")
            self.assertEqual(lease["preparation_stage"], "readiness_wait")
            return {"status": "ready"}

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=self.route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(jb_inspect, "claim_lifecycle", side_effect=claim),
            patch_attr(jb_inspect, "http_post", side_effect=post),
            patch_attr(jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry", side_effect=readiness),
            patch_attr(jb_inspect, "write_lease", side_effect=lambda value: writes.append(value.copy())),
        ):
            prepared, prepared_lease, close_proof = jb_inspect.prepare_lifecycle_details(
                self.args, self.lifecycle_context(),
            )

        self.assertEqual(events, ["claim", "sdk_post", "readiness"])
        self.assertEqual(close_proof, "private-token")
        self.assertIs(prepared_lease, lease)
        self.assertEqual(prepared["python_sdk_preparation"], self.response)
        self.assertEqual(prepared["readiness_barrier"], {"status": "ready"})
        self.assertEqual(
            [item["preparation_stage"] for item in writes],
            ["lifecycle_claim", "python_sdk_preparation", "readiness_wait", "prepared"],
        )

    def test_prepare_lifecycle_skips_sdk_post_when_claim_does_not_prove_ownership(self):
        lease = {"lease_id": "lease", "state": "preparing"}
        events = []

        def claim(*_args):
            events.append("claim")
            return unowned_lifecycle_result(self.route)

        def readiness(*_args):
            events.append("readiness")
            return {"status": "ready"}

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=self.route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(jb_inspect, "claim_lifecycle", side_effect=claim),
            patch_attr(jb_inspect, "http_post") as post,
            patch_attr(jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry", side_effect=readiness),
            patch_attr(jb_inspect, "write_lease"),
        ):
            prepared, prepared_lease, close_proof = jb_inspect.prepare_lifecycle_details(
                self.args, self.lifecycle_context(),
            )

        self.assertEqual(events, ["claim", "readiness"])
        post.assert_not_called()
        self.assertFalse(prepared["opened_by_helper"])
        self.assertFalse(prepared_lease["opened_by_helper"])
        self.assertIsNone(close_proof)
        self.assertEqual(
            prepared["python_sdk_preparation"],
            {"status": "skipped", "reason": "helper_ownership_not_proven"},
        )

    def test_successful_sdk_evidence_survives_a_later_readiness_inspect_error(self):
        lease = {"lease_id": "lease", "state": "preparing"}
        readiness_error = jb_inspect.InspectError(
            "not ready", 3, {"error_reason": "language_sdk_missing"},
        )
        cleanup = {"status": "closed"}

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=self.route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(
                jb_inspect, "claim_lifecycle",
                return_value=claimed_lifecycle_result(self.route, "private-token"),
            ),
            patch_attr(
                jb_inspect, "http_post",
                return_value=jb_inspect.HttpResult(200, self.response, "redacted"),
            ),
            patch_attr(
                jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry",
                side_effect=readiness_error,
            ),
            patch_attr(jb_inspect, "cleanup_failed_preparation", return_value=cleanup) as cleanup_call,
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.prepare_lifecycle_details(self.args, self.lifecycle_context())

        self.assertIs(caught.exception, readiness_error)
        self.assertEqual(caught.exception.payload["python_sdk_preparation"], self.response)
        self.assertEqual(caught.exception.payload["preparation_stage"], "readiness_wait")
        self.assertEqual(lease["python_sdk_preparation"], self.response)
        self.assertEqual(cleanup_call.call_args.kwargs["stage"], "readiness_wait")

    def test_successful_sdk_evidence_survives_a_later_unexpected_exception(self):
        lease = {"lease_id": "lease", "state": "preparing"}

        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=self.route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(
                jb_inspect, "claim_lifecycle",
                return_value=claimed_lifecycle_result(self.route, "private-token"),
            ),
            patch_attr(
                jb_inspect, "http_post",
                return_value=jb_inspect.HttpResult(200, self.response, "redacted"),
            ),
            patch_attr(
                jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry",
                side_effect=RuntimeError("readiness exploded"),
            ),
            patch_attr(jb_inspect, "cleanup_failed_preparation", return_value={"status": "closed"}),
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.prepare_lifecycle_details(self.args, self.lifecycle_context())

        self.assertIsInstance(caught.exception.__cause__, RuntimeError)
        self.assertEqual(caught.exception.payload["error_reason"], "helper_plugin_error")
        self.assertEqual(caught.exception.payload["error_message"], "readiness exploded")
        self.assertEqual(caught.exception.payload["preparation_stage"], "readiness_wait")
        self.assertEqual(caught.exception.payload["python_sdk_preparation"], self.response)

    def test_invalid_sdk_operations_are_never_accepted_as_prepared(self):
        for operation in (None, "", "registered", "CREATED", True, 1, [], {"created": True}):
            with self.subTest(operation=operation):
                response = self.response | {"operation": operation}
                with patch_attr(
                    jb_inspect, "http_post",
                    return_value=jb_inspect.HttpResult(200, response, "redacted"),
                ):
                    with self.assertRaises(jb_inspect.InspectError) as caught:
                        self.prepare()

                self.assertEqual(
                    caught.exception.payload["error_reason"],
                    "python_sdk_preparation_invalid_response",
                )
                self.assertEqual(caught.exception.payload["python_sdk_preparation"]["operation"], operation)

    def test_skipped_sdk_stage_preserves_unexpected_exception_behavior(self):
        lease = {"lease_id": "lease", "state": "preparing"}
        error = RuntimeError("unrelated readiness failure")
        with (
            patch_attr(jb_inspect, "create_local_lease", return_value=lease),
            patch_attr(jb_inspect, "find_exact_route", return_value=self.route),
            patch_attr(jb_inspect, "matching_deferred_lease", return_value=None),
            patch_attr(jb_inspect, "ensure_exact_worktree"),
            patch_attr(jb_inspect, "claim_lifecycle", return_value=unowned_lifecycle_result(self.route)),
            patch_attr(jb_inspect, "wait_until_route_ready_with_prepared_sdk_retry", side_effect=error),
            patch_attr(jb_inspect, "cleanup_failed_preparation", return_value={"status": "not_needed"}),
            patch_attr(jb_inspect, "write_lease"),
        ):
            with self.assertRaises(RuntimeError) as caught:
                jb_inspect.prepare_lifecycle_details(self.args, self.lifecycle_context())
        self.assertIs(caught.exception, error)

    def test_sdk_post_does_not_swallow_inspection_shaped_http_conflicts(self):
        body = json.dumps({
            "inspection_in_progress": True, "reason": "python_sdk_preparation_in_progress",
        }).encode()
        error = jb_inspect.urllib.error.HTTPError("http://localhost", 409, "Conflict", {}, io.BytesIO(body))
        with patch_attr(jb_inspect.urllib.request, "urlopen", side_effect=error):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                self.prepare()
        self.assertEqual(caught.exception.payload["error_reason"], "python_sdk_preparation_in_progress")
        self.assertEqual(caught.exception.payload["python_sdk_preparation"]["http_status"], 409)

    def test_plugin_failure_reason_and_observed_state_are_preserved(self):
        error = jb_inspect.InspectError("HTTP 409", 3, {
            "reason": "python_sdk_preparation_sdk_conflict", "http_status": 409,
            "detail": "A different valid SDK is assigned", "registered_local_python_sdk_count": 0,
        })
        with patch_attr(jb_inspect, "http_post", side_effect=error):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                self.prepare()
        payload = caught.exception.payload
        self.assertEqual(payload["error_reason"], "python_sdk_preparation_sdk_conflict")
        self.assertEqual(payload["python_sdk_preparation"]["detail"], error.payload["detail"])
        self.assertEqual(payload["python_sdk_preparation"]["registered_local_python_sdk_count"], 0)
        self.assertEqual(jb_inspect.outcome_bucket(payload, payload["error_reason"]), "environment_blocked")

    def test_post_transport_uses_post_and_redacts_the_ownership_token(self):
        with patch_attr(jb_inspect.urllib.request, "urlopen") as urlopen:
            response = urlopen.return_value.__enter__.return_value
            response.status = 200
            response.read.return_value = b'{"status":"prepared"}'
            result = jb_inspect.http_post(63342, "lifecycle/prepare-python-sdk", {"close_token": "private-token"})
        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.data, b"")
        self.assertNotIn("private-token", result.url)

    def test_provisioning_evidence_survives_compact_agent_and_durable_outcome(self):
        payload = {"status": "clean", "prepared": {"python_sdk_preparation": self.response}}
        compact = jb_inspect.compact_agent_result_payload(payload, 0)
        self.assertEqual(compact["diagnostic"]["python_sdk_preparation"], self.response)
        _, record = jb_inspect.outcome_record_base(payload)
        self.assertEqual(record["python_sdk_preparation"], self.response)


class NativeCompletionObservationTests(unittest.TestCase):
    @staticmethod
    def observation(run_id: int | None = 17):
        return {
            "schema_version": 1, "mode": "report_only", "inspection_run_id": run_id,
            "enumeration_complete": True, "candidate_execution_count": 2,
            "matched_candidate_count": 1, "missing_completion_count": 1,
            "candidate_rule_would_block_clean": True,
            "missing_examples": [{"tool": "PythonCheck", "file": "/private/project/app.py"}],
            "exclusions": {"language_not_applicable": 3},
            "limitations": ["empty_visitors_and_other_silent_skips_unobservable"],
        }

    def test_observation_survives_every_verdict_without_changing_classification(self):
        for status, verdict in (("clean", "GREEN"), ("findings", "RED"), ("stale_results", "UNKNOWN")):
            with self.subTest(verdict=verdict):
                payload = {
                    "command": "agent-inspect", "inspection_run_id": 17,
                    "status": status, "verdict": verdict, "total_problems": 1 if verdict == "RED" else 0,
                    "inspection_attribution": {"inspection_run_id": 17, "plugin_build_fingerprint": "candidate-clean"},
                }
                jb_inspect.apply_verdict(payload)
                before = {key: payload.get(key) for key in ("verdict", "retry_policy", "agent_result")}
                payload["capture_diagnostic"] = {"native_tool_completion_observation": self.observation()}
                jb_inspect.apply_verdict(payload)
                self.assertEqual({key: payload.get(key) for key in before}, before)
                compact = jb_inspect.compact_agent_result_payload(payload, 0)
                observed = compact["diagnostic"]["native_tool_completion_observation"]
                self.assertEqual(observed["actual_verdict"], verdict)
                self.assertEqual(observed["hypothetical_candidate_rule_verdict"], "UNKNOWN" if verdict == "GREEN" else verdict)
                record = jb_inspect.outcome_log_record(payload, 0)
                saved = record["native_tool_completion_observation"]
                self.assertEqual(saved["missing_completion_count"], 1)
                self.assertEqual(saved["missing_examples"][0]["file_hash"], jb_inspect.stable_value_hash("/private/project/app.py"))
                self.assertNotIn("/private/project", json.dumps(record))
                self.assertEqual(record["plugin_build_fingerprint"], "candidate-clean")

    def test_stale_and_unpinned_observations_cannot_attach_to_current_run(self):
        for observation_run in (16, None):
            payload = {"inspection_run_id": 17, "capture_diagnostic": {"native_tool_completion_observation": self.observation(observation_run)}}
            self.assertEqual(jb_inspect.native_completion_observation(payload), {})
        payload = {
            "inspection_run_id": 17,
            "wait": {"inspection_run_id": 16, "capture_diagnostic": {"native_tool_completion_observation": self.observation(16)}},
        }
        self.assertEqual(jb_inspect.native_completion_observation(payload), {})

    def test_incomplete_enumeration_has_no_hypothetical_verdict_and_examples_are_bounded(self):
        observation = self.observation()
        observation["enumeration_complete"] = False
        observation["missing_examples"] *= 100
        observation["unexpected_field"] = "private data"
        payload = {"inspection_run_id": 17, "verdict": "GREEN", "capture_diagnostic": {"native_tool_completion_observation": observation}}
        saved = jb_inspect.native_completion_observation(payload)
        self.assertIsNone(saved["candidate_rule_would_block_clean"])
        self.assertIsNone(saved["hypothetical_candidate_rule_verdict"])
        self.assertLess(len(saved["missing_examples"]), len(observation["missing_examples"]))
        self.assertNotIn("unexpected_field", saved)

    def test_silent_skip_classification_survives_with_its_own_hypothetical_verdict(self):
        observation = self.observation() | {
            "missing_classification_complete": True,
            "missing_classification_counts": {"empty_visitor": 1, "non_empty_visitor": 0, "external_annotator_batch": 2, "not_probed": 0},
            "unexplained_missing_completion_count": 0,
            "silent_skip_rule_would_block_clean": False,
            "unexplained_missing_examples": [],
        }
        payload = {"inspection_run_id": 17, "verdict": "GREEN", "capture_diagnostic": {"native_tool_completion_observation": observation}}
        saved = jb_inspect.native_completion_observation(payload)
        self.assertEqual(saved["hypothetical_candidate_rule_verdict"], "UNKNOWN")
        self.assertEqual(saved["hypothetical_silent_skip_rule_verdict"], "GREEN")
        self.assertEqual(saved["missing_classification_counts"]["empty_visitor"], 1)
        self.assertEqual(saved["missing_classification_counts"]["external_annotator_batch"], 2)
        self.assertEqual(saved["unexplained_missing_completion_count"], 0)

        observation["silent_skip_rule_would_block_clean"] = True
        observation["unexplained_missing_examples"] = [{"tool": "PythonCheck", "file": "/private/project/app.py"}]
        saved = jb_inspect.native_completion_observation(payload)
        self.assertEqual(saved["hypothetical_silent_skip_rule_verdict"], "UNKNOWN")
        record = jb_inspect.outcome_log_record(payload | {"command": "agent-inspect", "status": "clean"}, 0)
        durable = record["native_tool_completion_observation"]
        self.assertEqual(durable["unexplained_missing_examples"][0]["file_hash"], jb_inspect.stable_value_hash("/private/project/app.py"))
        self.assertNotIn("/private/project", json.dumps(record))

    def test_unfinished_classification_has_no_silent_skip_verdict(self):
        observation = self.observation() | {
            "missing_classification_complete": False,
            "missing_classification_unavailable_reason": "classification_time_limit",
            "silent_skip_rule_would_block_clean": False,
            "unexplained_missing_examples": [{"tool": "PythonCheck", "file": "/private/project/app.py"}],
        }
        payload = {"inspection_run_id": 17, "verdict": "GREEN", "capture_diagnostic": {"native_tool_completion_observation": observation}}
        saved = jb_inspect.native_completion_observation(payload)
        self.assertIsNone(saved["silent_skip_rule_would_block_clean"])
        self.assertIsNone(saved["hypothetical_silent_skip_rule_verdict"])
        self.assertEqual(saved["missing_classification_unavailable_reason"], "classification_time_limit")
        self.assertNotIn("unexplained_missing_examples", saved)


class FindingsPreservationTests(unittest.TestCase):
    def setUp(self):
        self.route = {"port": 63342, "project_key": "path:/fixture", "session_id": "session", "project_instance_id": "instance"}
        self.context = {"worktree_root": "/fixture", "scope": "files"}
        self.args = helper_args(command="agent", scope="files", files=["app.py"], limit=25, offset=0, lifecycle_lock_timeout_ms=0)
        self.problems = [{"file": "/fixture/app.py", "line": i + 1, "description": f"Finding {i}"} for i in range(53)]

    def page(self, offset):
        problems = self.problems[offset:offset + self.args.limit]
        following = offset + len(problems)
        return {
            "status": "results_available", "inspection_run_id": 7, "snapshot_run_id": 7,
            "route": dict(self.route), "total_problems": len(self.problems), "problems": problems,
            "problems_shown": len(problems),
            "pagination": {"offset": offset, "has_more": following < len(self.problems), "next_offset": following if following < len(self.problems) else None},
        }

    def result(self):
        result = jb_inspect.summarize_problems(self.context, self.route, self.page(self.args.offset))
        result["trigger"] = {"run_id": 7}
        return result

    def test_full_findings_are_readable_after_owned_cleanup_and_compaction(self):
        result = self.result()
        closed = []

        def read_page(route, endpoint, params, **kwargs):
            self.assertFalse(closed)
            self.assertEqual(endpoint, "problems")
            self.assertEqual(params["inspection_run_id"], 7)
            self.assertEqual(params["session_id"], self.route["session_id"])
            self.assertEqual(params["project_instance_id"], self.route["project_instance_id"])
            self.assertEqual(params["files"], "app.py")
            return self.page(params["offset"])

        def close(*_):
            self.assertEqual(result["findings_artifact"]["status"], "complete")
            closed.append(True)
            return {"status": "closed"}

        with patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": self.route}, {"opened_by_helper": True}, "close-proof")), \
                patch_attr(jb_inspect, "run_inspection_with_internal_retry", return_value=result), \
                patch_attr(jb_inspect, "call_endpoint", side_effect=read_page), \
                patch_attr(jb_inspect, "git_worktree_status_snapshot", return_value={"status": "ok", "entries": {}}), \
                patch_attr(jb_inspect, "post_cleanup_worktree_status_snapshot", return_value={"status": "ok", "entries": {}}), \
                patch_attr(jb_inspect, "cleanup_lifecycle", side_effect=close):
            payload = jb_inspect.run_prepared_inspection(self.args, self.context)
        compact = jb_inspect.compact_agent_result_payload(payload, 1)
        receipt = compact["findings_artifact"]
        saved = json.loads(Path(receipt["path"]).read_text())
        self.assertEqual(saved["problems"], self.problems)
        self.assertEqual(saved["request"]["files"], "app.py")
        self.assertEqual(saved["assessment"]["verdict"], "RED")
        self.assertEqual(saved["assessment"]["route"], self.route)
        self.assertEqual(saved["assessment"]["context"]["worktree_root"], self.context["worktree_root"])
        self.assertEqual(Path(receipt["path"]).stat().st_mode & 0o777, 0o600)
        self.assertEqual(receipt["sha256"], jb_inspect.hashlib.sha256(Path(receipt["path"]).read_bytes()).hexdigest())
        self.assertTrue(closed)
        self.assertTrue(compact["findings_truncated"])
        lane = jb_inspect.compact_inspection_lane_result(jb_inspect.InspectionLane("python", "PyCharm", True, ("**/*.py",), (), None), 0, self.context, [{"file": "app.py", "absolute_path": "/fixture/app.py"}], payload)
        self.assertEqual(lane["findings_artifact"], receipt)
        output = io.StringIO()
        with redirect_stdout(output):
            jb_inspect.print_human({"lane_results": [lane], "verdict": "RED"}, assess=False)
        self.assertIn(receipt["path"], output.getvalue())
        for finding in lane["findings"]:
            self.assertIn(f"{finding['file']}:{finding['line']} {finding['description']}\n", output.getvalue())
        self.assertEqual(output.getvalue().count("- ["), len(lane["findings"]))
        omitted = self.problems[len(lane["findings"])]
        self.assertNotIn(f"{omitted['file']}:{omitted['line']} {omitted['description']}\n", output.getvalue())

    def test_nine_lane_findings_remain_readable_after_cleanup(self):
        self.problems = [problem | {"severity": "warning"} for problem in self.problems[:9]]
        for mutated in (False, True):
            with self.subTest(mutated=mutated):
                result = self.result()
                closed = []

                def close(*_):
                    closed.append(True)
                    return {"status": "closed"}

                snapshot = {"status": "ok", "entries": {}}
                after = snapshot | {"entries": {".idea/vcs.xml": " M"}} if mutated else snapshot
                with (
                    patch_attr(jb_inspect, "prepare_lifecycle_details", return_value=({"route": self.route}, {"opened_by_helper": True}, "close-proof")),
                    patch_attr(jb_inspect, "run_inspection_with_internal_retry", return_value=result),
                    patch_attr(jb_inspect, "call_endpoint") as endpoint,
                    patch_attr(jb_inspect, "git_worktree_status_snapshot", return_value=snapshot),
                    patch_attr(jb_inspect, "post_cleanup_worktree_status_snapshot", return_value=after),
                    patch_attr(jb_inspect, "cleanup_lifecycle", side_effect=close),
                ):
                    payload = jb_inspect.run_prepared_inspection(self.args, self.context)
                self.assertTrue(closed)
                endpoint.assert_not_called()
                self.assertNotIn("findings_artifact", payload)
                lane = jb_inspect.compact_inspection_lane_result(
                    jb_inspect.InspectionLane("python", "PyCharm", True, ("**/*.py",), (), None),
                    0, self.context, [{"file": "app.py", "absolute_path": "/fixture/app.py"}], payload,
                )
                self.assertEqual(lane["inspection_outcome"]["verdict"], "RED")
                self.assertEqual(lane["verdict"], "UNKNOWN" if mutated else "RED")
                self.assertEqual(lane["evidence_ids"]["inspection_run_id"], 7)
                self.assertEqual(lane["route"]["project_instance_id"], self.route["project_instance_id"])
                lanes = {"lane_results": [lane]}
                json_output = io.StringIO()
                with redirect_stdout(json_output):
                    jb_inspect.emit(lanes, json_only=True, exit_code=1)
                saved = json.loads(json_output.getvalue())
                self.assertEqual(saved["lane_results"][0]["findings"], lane["findings"])
                output = io.StringIO()
                with redirect_stdout(output):
                    jb_inspect.emit(lanes, json_only=False, exit_code=1)
                text = output.getvalue()
                for problem in self.problems:
                    self.assertIn(f"[warning] {problem['file']}:{problem['line']} {problem['description']}\n", text)
                self.assertEqual(text.count("- [warning]"), len(self.problems))

    def test_human_findings_stay_with_each_lane_beyond_aggregate_limit(self):
        lanes = []
        for lane_id, required in (("python", True), ("optional", False)):
            self.problems = [problem | {"description": f"{lane_id} finding {i}"} for i, problem in enumerate(self.problems[:13])]
            result = self.result() | {"cleanup": {"status": "closed"}}
            lanes.append(jb_inspect.compact_inspection_lane_result(
                jb_inspect.InspectionLane(lane_id, "PyCharm", required, ("**/*.py",), (), None),
                len(lanes), self.context, [{"file": "app.py", "absolute_path": "/fixture/app.py"}], result,
            ))
        output = io.StringIO()
        with redirect_stdout(output):
            jb_inspect.emit({"lane_results": lanes}, json_only=False, exit_code=1)
        text = output.getvalue()
        sections = text.split("LANE: ")[1:]
        self.assertEqual(len(sections), len(lanes))
        for lane, section in zip(lanes, sections):
            self.assertTrue(section.startswith(lane["id"]))
            for finding in lane["findings"]:
                self.assertIn(f"{finding['file']}:{finding['line']} {finding['description']}\n", section)
        self.assertEqual(text.count("- ["), sum(len(lane["findings"]) for lane in lanes))

    def test_unknown_truncated_lane_reports_counts_and_missing_finding_fields(self):
        self.problems[0].pop("description")
        result = jb_inspect.summarize_problems(self.context, self.route, self.page(0) | {"capture_incomplete": True})
        self.assertEqual(result["verdict"], "UNKNOWN")
        self.assertIsNone(jb_inspect.preserve_inspection_findings(self.args, self.context, result))
        lane = jb_inspect.compact_inspection_lane_result(
            jb_inspect.InspectionLane("python", "PyCharm", True, ("**/*.py",), (), None),
            0, self.context, [{"file": "app.py", "absolute_path": "/fixture/app.py"}], result,
        )
        output = io.StringIO()
        with redirect_stdout(output):
            jb_inspect.emit({"lane_results": [lane]}, json_only=False, exit_code=1)
        text = output.getvalue()
        self.assertIn(f"findings={len(lane['findings'])}/{lane['finding_count']} truncated=true", text)
        self.assertIn(f"- [unknown] {self.problems[0]['file']}:{self.problems[0]['line']} \n", text)
        self.assertEqual(text.count("- [unknown]"), len(lane["findings"]))
        self.assertEqual(lane["verdict"], "UNKNOWN")

    def test_nonzero_display_offset_still_preserves_the_complete_run(self):
        self.args.offset = 25
        offsets = []
        def read_page(route, endpoint, params, **kwargs):
            offsets.append(params["offset"])
            return self.page(params["offset"])
        with patch_attr(jb_inspect, "call_endpoint", side_effect=read_page):
            receipt = jb_inspect.preserve_inspection_findings(self.args, self.context, self.result())
        self.assertEqual(json.loads(Path(receipt["path"]).read_text())["problems"], self.problems)
        self.assertEqual(offsets, [0, 25, 50])

    def test_small_display_limit_preserves_omitted_findings_below_compact_limit(self):
        self.problems = self.problems[:15]
        self.args.limit = 10
        with patch_attr(jb_inspect, "call_endpoint", side_effect=lambda route, endpoint, params, **kwargs: self.page(params["offset"])):
            receipt = jb_inspect.preserve_inspection_findings(self.args, self.context, self.result())
        self.assertEqual(receipt["status"], "complete")
        self.assertEqual(json.loads(Path(receipt["path"]).read_text())["problems"], self.problems)

    def test_connection_reset_preserves_accepted_red_and_already_read_findings(self):
        result = self.result()
        with patch_attr(jb_inspect, "call_endpoint", side_effect=ConnectionResetError("peer reset")):
            receipt = jb_inspect.preserve_inspection_findings(self.args, self.context, result)
        self.assertEqual(receipt["status"], "incomplete")
        self.assertEqual(json.loads(Path(receipt["path"]).read_text())["problems"], self.problems[:25])
        self.assertEqual(result["verdict"], "RED")

    def test_invalid_later_pages_preserve_red_and_report_partial_coverage(self):
        for change in ({"inspection_run_id": 8}, {"snapshot_run_id": 8}, {"status": "stale_results"}, {"route": {**self.route, "session_id": "replacement"}}, {"total_problems": 54}):
            with self.subTest(change=change):
                result = self.result()
                page = self.page(25) | change
                with patch_attr(jb_inspect, "call_endpoint", return_value=page):
                    receipt = jb_inspect.preserve_inspection_findings(self.args, self.context, result)
                saved = json.loads(Path(receipt["path"]).read_text())
                self.assertEqual(receipt["status"], "incomplete")
                self.assertEqual(saved["problems"], self.problems[:25])
                self.assertEqual(result["verdict"], "RED")

    def test_unavailable_page_or_artifact_never_claims_full_retrieval(self):
        with patch_attr(jb_inspect, "call_endpoint", side_effect=jb_inspect.InspectError("closed", 3, {"error_reason": "target_project_not_open"})):
            receipt = jb_inspect.preserve_inspection_findings(self.args, self.context, self.result())
        self.assertEqual(receipt["status"], "incomplete")
        with patch_attr(jb_inspect, "call_endpoint", side_effect=lambda route, endpoint, params, **kwargs: self.page(params["offset"])), \
                patch.object(os, "open", side_effect=OSError("disk full")):
            receipt = jb_inspect.preserve_inspection_findings(self.args, self.context, self.result())
        self.assertEqual(receipt["status"], "unavailable")
        self.assertNotIn("path", receipt)


if __name__ == "__main__":
    unittest.main()
