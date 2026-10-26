#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Exercise retirement and cleanup with real lease files and linked Git fixtures."""
import io
import json
import shlex
import subprocess
import tempfile
import unittest
import uuid
from argparse import Namespace
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from test_jb_inspect import jb_inspect


class LeaseRetirementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.leases = self.base / "leases"
        self.leases.mkdir()
        self.patch = patch.object(jb_inspect, "lease_dir", return_value=self.leases)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def lease(self, root, **changes):
        lease_id = str(uuid.uuid4())
        lease = {
            "lease_id": lease_id, "worktree_root": str(root),
            "state": "cleanup_pending", "pid": 999999,
            "updated_at_ms": jb_inspect.now_ms(), "opened_by_helper": False,
            "open_not_attempted": True, "open_request_may_have_been_accepted": False,
            "open_attempts": [],
        } | changes
        path = self.leases / f"{lease_id}.json"
        path.write_text(json.dumps(lease))
        return path, lease

    def worktree(self):
        primary = self.base / "primary"
        root = self.base / "task"
        subprocess.run(["git", "init", "-q", str(primary)], check=True)
        subprocess.run(["git", "-C", str(primary), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "-q",
                        "--allow-empty", "-m", "fixture"], check=True)
        subprocess.run(["git", "-C", str(primary), "worktree", "add", "-q", "-b",
                        "task", str(root)], check=True)
        return root.resolve()

    def test_retirement_previews_then_reconciles_only_target_dead_owner(self):
        root = self.worktree()
        owned, _ = self.lease(root)
        foreign, _ = self.lease(self.base / "foreign")
        with patch.object(jb_inspect, "pid_alive", return_value=False):
            self.assertEqual(jb_inspect.retirement_worktree(root, True), root)
            self.assertTrue(owned.exists())
            self.assertEqual(jb_inspect.retirement_worktree(root), root)
        self.assertFalse(owned.exists())
        self.assertTrue(foreign.exists())
        self.assertTrue(root.is_dir())

    def test_retirement_preserves_live_owner_and_names_exact_recovery(self):
        root = self.worktree()
        path, lease = self.lease(root, updated_at_ms=1)
        with patch.object(jb_inspect, "pid_alive", return_value=True):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.retirement_worktree(root)
        self.assertTrue(path.exists())
        self.assertEqual(caught.exception.payload["blocking_lease_ids"], [lease["lease_id"]])
        command = caught.exception.payload["cleanup_commands"][0]
        args = jb_inspect.build_parser().parse_args(shlex.split(command)[3:])
        self.assertEqual(args.lease_id, [lease["lease_id"]])
        self.assertFalse(args.dry_run)
        self.assertIn(command, str(caught.exception))

    def test_offline_retirement_keeps_leases_without_discovery_or_cleanup(self):
        root = self.worktree()
        path, lease = self.lease(root, updated_at_ms=1, opened_by_helper=True,
                                 open_not_attempted=False, open_request_may_have_been_accepted=True)
        for dry_run in (True, False):
            args = Namespace(command="remove-worktree", repo=str(root), dry_run=dry_run,
                             offline_only=True, lifecycle_lock_timeout_ms=1000)
            with (self.subTest(dry_run=dry_run),
                  patch.object(jb_inspect, "pid_alive", return_value=False),
                  patch.object(jb_inspect, "discover_identities", return_value=[]) as discover,
                  patch.object(jb_inspect, "http_post") as post):
                with self.assertRaises(jb_inspect.InspectError) as caught:
                    jb_inspect.command_retire_sdks(args)
                discover.assert_not_called()
                post.assert_not_called()
                self.assertEqual(caught.exception.payload["blocking_lease_ids"], [lease["lease_id"]])
                self.assertFalse(caught.exception.payload["cleanup_commands"])
                self.assertTrue(path.exists())
                self.assertTrue(root.is_dir())

    def test_offline_apply_keeps_a_lease_that_appears_after_preview(self):
        root = self.worktree()
        leases = []
        def preview(*_args):
            leases.append(self.lease(root, updated_at_ms=1, opened_by_helper=True,
                                     open_not_attempted=False, open_request_may_have_been_accepted=True))
            return []
        args = Namespace(command="remove-worktree", repo=str(root), dry_run=False,
                         offline_only=True, lifecycle_lock_timeout_ms=1000)
        with (patch.object(jb_inspect, "pid_alive", return_value=False),
              patch.object(jb_inspect, "unregister_helper_sdks", side_effect=preview),
              patch.object(jb_inspect, "discover_identities", return_value=[]) as discover,
              patch.object(jb_inspect, "http_post") as post):
            with self.assertRaises(jb_inspect.InspectError) as caught:
                jb_inspect.command_retire_sdks(args)
            discover.assert_not_called()
            post.assert_not_called()
        path, lease = leases[0]
        self.assertEqual(caught.exception.payload["blocking_lease_ids"], [lease["lease_id"]])
        self.assertTrue(path.exists())
        self.assertTrue(root.is_dir())

    def test_cleanup_preview_reports_proved_removal_and_changes_nothing(self):
        path, _ = self.lease(self.base / "task")
        with patch.object(jb_inspect, "pid_alive", return_value=False):
            result = jb_inspect.cleanup_stale_helper_leases(Namespace(max_age_ms=1, dry_run=True))
        self.assertEqual(result["would_remove"], [str(path)])
        self.assertEqual(result["removed"], [])
        self.assertTrue(path.exists())
        output = io.StringIO()
        with redirect_stdout(output):
            jb_inspect.print_human(result, assess=False)
        self.assertIn("PREVIEW", output.getvalue())
        self.assertIn(f"would_remove={len(result['would_remove'])}", output.getvalue())
        self.assertIn("--no-dry-run", output.getvalue())

    def test_warm_dead_ide_release_keeps_uncertain_and_replacement_routes(self):
        path, lease = self.lease(self.base / "task", state="kept_warm_after_indexing_timeout",
                                opened_by_helper=True, open_not_attempted=False,
                                session_id="original", ide_port=63343)
        lease["open_attempts"] = [{
            "accepted": True, "ownership_registered": True, "lease_id": lease["lease_id"],
            "lifecycle_ownership_protocol": jb_inspect.LIFECYCLE_OWNERSHIP_PROTOCOL,
            "identity": {"session_id": "original", "port": 63343, "pid": 753},
        }]
        path.write_text(json.dumps(lease))
        replacement = {"base_path": lease["worktree_root"], "session_id": "replacement"}
        for changes, routes, dead, removable in [
            ({}, [], True, True), ({}, [], False, False),
            ({}, [replacement], True, False),
            ({"open_request_may_have_been_accepted": True}, [], True, False),
        ]:
            with self.subTest(changes=changes, routes=routes, dead=dead), \
                    patch.object(jb_inspect, "pid_definitively_dead", return_value=dead), \
                    patch.object(jb_inspect, "cleanup_lifecycle") as close:
                result = jb_inspect.cleanup_stale_helper_lease(lease | changes, routes, set())
            self.assertEqual(result.get("released_without_close", False), removable)
            close.assert_not_called()

    def test_preview_with_live_route_never_claims_or_closes(self):
        _, lease = self.lease(self.base / "task", opened_by_helper=True,
                              open_not_attempted=False, session_id="original")
        route = {"base_path": lease["worktree_root"], "session_id": "original", "port": 63343}
        with patch.object(jb_inspect, "reclaim_lifecycle_claim") as claim:
            result = jb_inspect.cleanup_stale_helper_lease(lease, [route], {"original"}, dry_run=True)
        claim.assert_not_called()
        self.assertEqual(result["status"], "skipped")

    def test_retirement_keeps_malformed_identity_without_cleanup(self):
        root = self.worktree()
        path, lease = self.lease(root)
        lease["lease_id"] = str(uuid.uuid4())
        path.write_text(json.dumps(lease))
        with patch.object(jb_inspect, "discover_routes_for_cleanup") as discovery:
            with self.assertRaises(jb_inspect.InspectError):
                jb_inspect.retirement_worktree(root)
        discovery.assert_not_called()
        self.assertTrue(path.exists())

    def test_discovery_transport_reset_retains_lease_with_structured_failure(self):
        path, _ = self.lease(self.base / "task", opened_by_helper=True, open_not_attempted=False)
        with patch.object(jb_inspect, "pid_alive", return_value=False), \
                patch.object(jb_inspect, "discover_routes_for_cleanup", side_effect=ConnectionResetError()):
            result = jb_inspect.cleanup_stale_helper_leases(Namespace(max_age_ms=1, dry_run=False))
        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["removed"], [])
        self.assertTrue(result["failed"])
        self.assertTrue(path.exists())

    def test_inaccessible_helper_pid_is_retained(self):
        with patch.object(jb_inspect.os, "kill", side_effect=PermissionError()):
            self.assertTrue(jb_inspect.pid_alive(753))


if __name__ == "__main__":
    unittest.main()
