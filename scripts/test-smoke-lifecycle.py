#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
import importlib.util
import io
import json
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent.parent


def load(name):
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), ROOT / "scripts" / (name + ".py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


smoke = load("smoke-worktree")
installer = load("install-local-plugin")
trust = load("retire-smoke-trust")


class SmokeLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        subprocess.run(
            ["git", "init", "-q", "-b", "main", str(self.source)], check=True
        )
        (self.source / "source.py").write_text('print("fixture")\n')
        subprocess.run(["git", "-C", str(self.source), "add", "."], check=True)
        subprocess.run(
            [
                "git",
                "-C",
                str(self.source),
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "commit",
                "-qm",
                "fixture",
            ],
            check=True,
        )
        with patch.object(smoke.platform, "node", return_value="portable"):
            self.receipt = smoke.create(
                self.source, "smoke", self.root / "worktrees", self.root / "manager"
            )
        self.worktree = Path(self.receipt["root"])
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.calls = []

    def helper_run(self, *args):
        if args[0] != "uv":
            return smoke.run_original(*args)
        self.calls.append(args)
        self.assertEqual(args[-1], str(self.worktree))
        if "--no-dry-run" in args:
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(self.source),
                    "worktree",
                    "remove",
                    str(self.worktree),
                ],
                check=True,
            )
            return json.dumps({"status": "ok", "worktree_removed": True})
        return json.dumps({"status": "ok", "dry_run": True})

    def retire(self, payload):
        smoke.run_original = smoke.run
        with patch.object(smoke, "run", side_effect=self.helper_run):
            return smoke.retire(
                self.receipt, payload, Path("/maintained/jb-inspect.py"), self.evidence
            )

    def test_failed_lifecycle_preserves_exact_worktree_and_branch(self):
        for status in ["failed", "deferred", "not_needed", "skipped", None]:
            with self.subTest(status=status):
                result = self.retire({"cleanup": {"status": status}})
                self.assertEqual(result["reason"], "lifecycle_cleanup_unresolved")
                self.assertEqual(
                    smoke.manifest(self.worktree), self.receipt["manifest"]
                )
                self.assertEqual(self.calls, [])
                self.assertEqual(
                    smoke.run(
                        "git", "-C", str(self.worktree), "branch", "--show-current"
                    ),
                    "work/smoke",
                )

    def test_closed_project_retires_sdk_before_nonforce_removal(self):
        unrelated = self.root / "unrelated"
        unrelated.mkdir()
        (unrelated / "evidence").write_text("keep")
        result = self.retire({"cleanup": {"status": "closed"}})
        self.assertEqual(result["status"], "removed")
        self.assertEqual(len(self.calls), 2)
        self.assertNotIn("--no-dry-run", self.calls[0])
        self.assertIn("--no-dry-run", self.calls[1])
        self.assertFalse(self.worktree.exists())
        self.assertEqual((unrelated / "evidence").read_text(), "keep")

    def test_ignored_mutation_is_retained(self):
        exclude = self.source / ".git/info/exclude"
        exclude.write_text(".idea/\n")
        (self.worktree / ".idea").mkdir()
        (self.worktree / ".idea/private-notes.xml").write_text("IDE mutation")
        result = self.retire({"cleanup": {"status": "closed"}})
        self.assertEqual(result["reason"], "project_changed")
        self.assertEqual(self.calls, [])
        self.assertTrue((self.worktree / ".idea/private-notes.xml").exists())

    def test_generated_workspace_is_preserved_before_successful_removal(self):
        (self.source / ".git/info/exclude").write_text(".idea/\n")
        (self.worktree / ".idea").mkdir()
        workspace = self.worktree / ".idea/workspace.xml"
        workspace.write_bytes(b"per-run workspace bytes")
        result = self.retire({"cleanup": {"status": "closed"}})
        self.assertEqual(result["status"], "removed")
        self.assertEqual(
            (
                Path(result["generated_state_archive"]) / ".idea/workspace.xml"
            ).read_bytes(),
            b"per-run workspace bytes",
        )
        self.assertFalse(self.worktree.exists())

    def test_fixture_workspace_is_ignored_while_source_changes_remain_dirty(self):
        fixtures = self.source / "test-fixtures"
        fixtures.mkdir()
        for name in [
            "inspection-red-lane",
            "inspection-red-lane-pycharm",
            "inspection-red-lane-webstorm",
        ]:
            shutil.copytree(ROOT / "test-fixtures" / name, fixtures / name)
        subprocess.run(["git", "-C", str(self.source), "add", "."], check=True)
        subprocess.run(
            [
                "git",
                "-C",
                str(self.source),
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "commit",
                "-qm",
                "fixtures",
            ],
            check=True,
        )
        for directory in fixtures.iterdir():
            (directory / ".idea/workspace.xml").write_text("generated IDE workspace")
        self.assertEqual(
            smoke.run("git", "-C", str(self.source), "status", "--porcelain"), ""
        )
        source = next((fixtures / "inspection-red-lane-pycharm/src").glob("*.py"))
        source.write_text(source.read_text() + "\nprint('source changed')\n")
        self.assertIn(
            source.relative_to(self.source).as_posix(),
            smoke.run("git", "-C", str(self.source), "status", "--porcelain"),
        )

    def test_preview_failure_keeps_worktree(self):
        original = smoke.run

        def refused(*args):
            if args[0] == "uv":
                raise subprocess.CalledProcessError(3, args)
            return original(*args)

        with (
            patch.object(smoke, "run", side_effect=refused),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            smoke.retire(
                self.receipt,
                {"cleanup": {"status": "closed"}},
                Path("/helper"),
                self.evidence,
            )
        self.assertTrue(self.worktree.is_dir())

    def test_mutation_during_sdk_preview_preserves_worktree(self):
        original = smoke.run

        def changed(*args):
            if args[0] == "uv":
                (self.worktree / "unexpected").write_text("retain")
                return json.dumps({"status": "ok", "dry_run": True})
            return original(*args)

        with patch.object(smoke, "run", side_effect=changed):
            result = smoke.retire(
                self.receipt,
                {"cleanup": {"status": "closed"}},
                Path("/helper"),
                self.evidence,
            )
        self.assertEqual(result["reason"], "project_changed_during_preview")
        self.assertEqual((self.worktree / "unexpected").read_text(), "retain")

    def test_host_creation_uses_manager_and_refuses_root_override(self):
        with (
            patch.object(smoke.platform, "node", return_value="Chris-Studio"),
            patch.object(smoke, "run") as command,
        ):
            with self.assertRaises(ValueError):
                smoke.create(self.source, "other", self.root, Path("/manager"))
            command.assert_called_once()
            command.reset_mock()
            command.side_effect = [
                self.receipt["head"],
                "/Volumes/Developer-Artifacts/worktrees/source/other",
            ]
            with (
                patch.object(
                    Path, "resolve", autospec=True, side_effect=lambda path, **_: path
                ),
                patch.object(smoke, "manifest", return_value={}),
            ):
                result = smoke.create(self.source, "other", None, Path("/manager"))
            self.assertTrue(result["managed"])
            self.assertEqual(
                command.call_args.args,
                ("/manager", str(self.source), "other", self.receipt["head"]),
            )


class LocalInstaller(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.candidate = self.root / "candidate"
        self.target = self.root / "plugins/jetbrains-inspection-api"
        self.evidence = self.root / "evidence"
        for directory in [self.candidate, self.target, self.evidence]:
            directory.mkdir(parents=True)
        (self.candidate / "new-version.jar").write_bytes(b"new")
        (self.target / "old-version.jar").write_bytes(b"prior")
        (self.target / "nested").mkdir()
        (self.target / "nested/prior-only").write_bytes(b"prior only")
        self.old = installer.inventory(self.target)

    def test_success_matches_complete_candidate_and_retains_exact_prior(self):
        with patch.object(installer, "require_stopped"):
            result = installer.replace_payload(
                self.candidate, self.target, self.evidence, Path("/IDE/launcher")
            )
        self.assertEqual(
            installer.inventory(self.target), installer.inventory(self.candidate)
        )
        self.assertEqual(installer.inventory(Path(result["rollback"])), self.old)
        self.assertEqual(
            installer.inventory(Path(result["preserved_previous"])), self.old
        )

    def test_failed_candidate_restores_prior_files_and_bytes_across_versions(self):
        original = installer.inventory
        checked = 0

        def broken(root):
            nonlocal checked
            if root == self.target and (root / "new-version.jar").exists():
                checked += 1
                return {"corrupted": "bad"}
            return original(root)

        with (
            patch.object(installer, "require_stopped"),
            patch.object(installer, "inventory", side_effect=broken),
            self.assertRaises(ValueError),
        ):
            installer.replace_payload(
                self.candidate, self.target, self.evidence, Path("/IDE/launcher")
            )
        self.assertEqual(checked, 1)
        self.assertEqual(installer.inventory(self.target), self.old)
        self.assertFalse((self.target / "new-version.jar").exists())

    def test_running_ide_aborts_before_payload_replacement(self):
        with (
            patch.object(
                installer,
                "process_inventory",
                return_value="42 /IDE/launcher /IDE/launcher\n",
            ),
            self.assertRaises(ValueError),
        ):
            installer.replace_payload(
                self.candidate, self.target, self.evidence, Path("/IDE/launcher")
            )
        self.assertEqual(installer.inventory(self.target), self.old)

    def test_alias_running_app_cannot_be_treated_as_stopped(self):
        executable = Path("/IDE.app/Contents/MacOS/idea")
        with (
            patch.object(
                installer,
                "process_inventory",
                return_value="42 /alias.app/Contents/MacOS/idea\n",
            ),
            patch.object(installer, "app_is_running", return_value=True),
            self.assertRaises(ValueError),
        ):
            installer.require_stopped(executable)
        with (
            patch.object(installer, "process_inventory", return_value=""),
            patch.object(installer, "app_is_running", return_value=True),
            self.assertRaises(ValueError),
        ):
            installer.quit_normally(Path("/IDE.app"), executable, 1)

    def test_bundle_config_selector_keeps_editions_separate(self):
        selectors = [
            "IntelliJIdea2026.2",
            "IdeaIC2026.2",
            "PyCharm2026.2",
            "PyCharmCE2026.2",
        ]
        for selector in selectors:
            with self.subTest(selector=selector):
                app = self.root / (selector + ".app")
                resources = app / "Contents/Resources"
                resources.mkdir(parents=True)
                metadata = {
                    "dataDirectoryName": selector,
                    "productCode": "fixture-product",
                }
                (resources / "product-info.json").write_text(json.dumps(metadata))
                expected = (
                    self.root
                    / "Library/Application Support/JetBrains"
                    / metadata["dataDirectoryName"]
                )
                (expected / "options").mkdir(parents=True)
                product = SimpleNamespace(
                    product_codes=(metadata["productCode"],),
                    config_prefixes=(selector,),
                )
                helper = SimpleNamespace(
                    ide_app_candidate=lambda _: SimpleNamespace(product_key="fixture"),
                    IDE_PRODUCTS={"fixture": product},
                )
                with patch.object(Path, "home", return_value=self.root):
                    actual = installer.app_config_dir(app, helper)
                self.assertEqual(actual, expected)

    def test_idle_project_needs_no_previous_inspection_verdict(self):
        raw = {"indexing": False, "is_scanning": False, "inspection_in_progress": False}
        installer.require_idle_status({"raw": raw, "verdict": "UNKNOWN"})
        for field in raw:
            with self.subTest(field=field), self.assertRaises(ValueError):
                installer.require_idle_status({"raw": raw | {field: True}})
        with self.assertRaises(ValueError):
            installer.require_idle_status({"raw": {}})

    def test_helper_error_reports_maintenance_failure(self):
        class InspectError(Exception):
            pass

        helper = SimpleNamespace(InspectError=InspectError)
        with (
            self.assertRaisesRegex(ValueError, "maintenance window"),
            installer.helper_errors(helper),
        ):
            raise InspectError("lock unavailable")

    def test_exact_executable_and_helper_process_guards(self):
        text = "1 /IDE/launcher /IDE/launcher\n2 /foreign/launcher /foreign/launcher\n3 /bin/codex prompt python jb-inspect.py\n"
        self.assertEqual(installer.main_processes(text, Path("/IDE/launcher")), [1])
        self.assertFalse(installer.active_helpers(text))
        self.assertTrue(
            installer.active_helpers("4 /bin/uv uv run /skills/jb-inspect.py inspect\n")
        )

    def test_helper_wait_expires_without_quitting(self):
        with (
            patch.object(
                installer,
                "process_inventory",
                return_value="4 /bin/uv uv run jb-inspect.py\n",
            ),
            patch.object(installer.time, "monotonic", side_effect=[0, 2]),
            self.assertRaisesRegex(ValueError, "helper remains active"),
        ):
            installer.wait_no_helpers(1)

    def test_normal_quit_timeout_keeps_unsaved_ide(self):
        with (
            patch.object(
                installer,
                "process_inventory",
                return_value="42 /IDE/launcher /IDE/launcher\n",
            ),
            patch.object(installer.subprocess, "run") as command,
            patch.object(installer.time, "monotonic", side_effect=[0, 2]),
            self.assertRaisesRegex(ValueError, "Normal quit unresolved"),
        ):
            installer.quit_normally(Path("/IDE.app"), Path("/IDE/launcher"), 1)
        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[0][0], "osascript")

    def test_trust_retirement_preserves_other_roots_and_preferences(self):
        from xml.etree import ElementTree

        tree = ElementTree.ElementTree(
            ElementTree.fromstring(
                '<application><component name="Trusted.Paths.Settings"><option name="TRUSTED_PATHS"><list><option value="/dedicated/smoke"/><option value="/other/work"/></list></option></component><component name="Trusted.Paths"><option name="TRUSTED_PROJECT_PATHS"><map><entry key="/dedicated/smoke/project" value="true"/><entry key="/other/work/project" value="true"/></map></option></component><component name="GeneralSettings"><option name="confirmOpenNewProject2" value="-1"/></component></application>'
            )
        )
        for parent, entry in trust.trust_entries(tree, "/dedicated/smoke"):
            parent.remove(entry)
        self.assertEqual(trust.trust_entries(tree, "/dedicated/smoke"), [])
        self.assertEqual(len(trust.trust_entries(tree, "/other/work")), 2)
        self.assertEqual(
            tree.getroot()
            .find("component[@name='GeneralSettings']/option")
            .get("value"),
            "-1",
        )

    def test_trust_apply_refuses_running_ide_and_preserves_settings(self):
        app = self.root / "IDE.app"
        (app / "Contents").mkdir(parents=True)
        (app / "Contents/Info.plist").write_bytes(
            plistlib.dumps({"CFBundleExecutable": "idea"})
        )
        config = self.root / "config"
        (config / "options").mkdir(parents=True)
        settings = config / "options/trusted-paths.xml"
        before = b'<application><component name="Trusted.Paths.Settings"><option name="TRUSTED_PATHS"><list><option value="/dedicated/smoke"/></list></option></component></application>'
        settings.write_bytes(before)
        helper = SimpleNamespace(
            resolve_ide_selection=lambda _: SimpleNamespace(
                config_dir=config, app_path=app
            ),
            outcome_routing_lock=nullcontext,
            lifecycle_lock=nullcontext,
            read_local_leases=list,
            trust_path_token=lambda _: "/dedicated/smoke",
        )
        fake_installer = SimpleNamespace(
            load_helper=lambda _: helper,
            app_config_dir=lambda *_: config,
            wait_no_helpers=lambda _: None,
            require_stopped=lambda _: (_ for _ in ()).throw(ValueError("IDE running")),
        )
        fake_spec = SimpleNamespace(loader=SimpleNamespace(exec_module=lambda _: None))
        args = [
            "retire-smoke-trust.py",
            "--smoke-root",
            str(self.root / "empty/smoke"),
            "--ide-app",
            str(app),
            "--helper",
            "/helper.py",
            "--apply",
            "--maintenance-window",
            "--artifact-root",
            str(self.evidence),
        ]
        with (
            patch.object(sys, "argv", args),
            patch.object(
                trust.importlib.util, "spec_from_file_location", return_value=fake_spec
            ),
            patch.object(
                trust.importlib.util, "module_from_spec", return_value=fake_installer
            ),
            self.assertRaisesRegex(ValueError, "IDE running"),
        ):
            trust.main()
        self.assertEqual(settings.read_bytes(), before)

    def test_symlinked_installed_payload_is_preserved(self):
        link = self.root / "plugins/linked-plugin"
        link.symlink_to(self.target, target_is_directory=True)
        with patch.object(installer, "require_stopped"), self.assertRaises(ValueError):
            installer.replace_payload(
                self.candidate, link, self.evidence, Path("/IDE/launcher")
            )
        self.assertTrue(link.is_symlink())
        self.assertEqual(installer.inventory(self.target), self.old)

    def test_archive_requires_exact_clean_build_and_rejects_traversal(self):
        sha = "a" * 40
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as zipped:
            zipped.writestr(
                "com/shiny/inspectionmcp/inspection-build.properties",
                f"plugin.build.commit={sha}\nplugin.build.dirty=false\nplugin.build.fingerprint={sha}-clean\n",
            )
        archive = self.root / "candidate.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("jetbrains-inspection-api/lib/plugin.jar", jar.getvalue())
        good = installer.stage_archive(archive, self.root / "good", sha)
        self.assertTrue((good / "lib/plugin.jar").exists())
        with self.assertRaises(ValueError):
            installer.stage_archive(archive, self.root / "wrong", "b" * 40)
        with zipfile.ZipFile(archive, "a") as zipped:
            zipped.writestr("jetbrains-inspection-api/../../escape", "bad")
        with self.assertRaises(ValueError):
            installer.stage_archive(archive, self.root / "unsafe", sha)
        self.assertFalse((self.root / "escape").exists())


if __name__ == "__main__":
    unittest.main()
