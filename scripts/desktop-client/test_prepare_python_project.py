#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
import base64
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from xml.etree import ElementTree
from pathlib import Path
from unittest.mock import patch
from urllib.parse import unquote
from typing import Any, cast


SCRIPT_PATH = Path(__file__).resolve().parent / "prepare-python-project.py"
SPEC = importlib.util.spec_from_file_location("prepare_python_project", SCRIPT_PATH)
assert SPEC is not None
prepare_python_project = cast(Any, importlib.util.module_from_spec(SPEC))
assert SPEC.loader is not None
SPEC.loader.exec_module(prepare_python_project)


def resolve_model_url(url: str, module_path: Path, repo: Path) -> Path:
    module_dir = module_path.parent
    if module_dir.name == ".idea":
        module_dir = module_dir.parent
    return Path(unquote(url.removeprefix("file://")).replace(
        "$MODULE_DIR$", str(module_dir),
    ).replace("$PROJECT_DIR$", str(repo))).resolve()


class PreparePythonProjectTest(unittest.TestCase):
    def test_generated_index_exclusions_preserve_tracked_and_declared_test_sources(self):
        for candidate in prepare_python_project.GENERATED_INDEX_ROOTS:
            if candidate == ".git":
                continue
            with self.subTest(candidate=candidate), tempfile.TemporaryDirectory() as temporary:
                repo = Path(temporary)
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                (repo / ".gitignore").write_text(candidate + "/\n")
                generated = repo / candidate
                tests = generated / "tests"
                tests.mkdir(parents=True)
                (generated / "sample.py").write_text("value = 1\n")
                roots = prepare_python_project.ignored_generated_roots(repo, [])
                self.assertIn(candidate, roots)
                for test_root in (candidate, candidate + "/tests", "./" + candidate + "/tests",
                                  candidate + "/../" + candidate + "/tests"):
                    self.assertNotIn(candidate, prepare_python_project.ignored_generated_roots(repo, [test_root]))
                files = prepare_python_project.render_project_files(repo, "fixture", [], generated_roots=roots)
                module = ElementTree.fromstring(files[repo / ".idea/fixture.iml"])
                content = module.find("./component/content")
                assert content is not None
                exclusions = {resolve_model_url(entry.get("url", ""), repo / ".idea/fixture.iml", repo)
                              for entry in content.findall("excludeFolder")}
                self.assertTrue(all((repo / root).resolve() in exclusions for root in roots))
                subprocess.run(["git", "-C", str(repo), "add", "--force", candidate + "/sample.py"], check=True)
                self.assertNotIn(candidate, prepare_python_project.ignored_generated_roots(repo, []))

    def test_ignored_symlink_candidates_do_not_abort_preparation_or_hide_declared_tests(self):
        for candidate in prepare_python_project.GENERATED_INDEX_ROOTS:
            if candidate == ".git":
                continue
            with self.subTest(candidate=candidate), tempfile.TemporaryDirectory() as temporary:
                base = Path(temporary)
                repo = base / "repo"
                shared = base / "shared"
                repo.mkdir()
                (shared / "tests").mkdir(parents=True)
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                (repo / ".gitignore").write_text(candidate + "\n")
                (repo / candidate).symlink_to(shared, target_is_directory=True)
                self.assertIn(candidate, prepare_python_project.ignored_generated_roots(repo, []))
                self.assertNotIn(candidate, prepare_python_project.ignored_generated_roots(repo, ["./" + candidate + "/tests"]))
                self.assertTrue((shared / "tests").is_dir())

    @staticmethod
    def write_script(repo, name, dependencies):
        path = repo / name
        path.write_text(
            '# /// script\n# dependencies = ' + json.dumps(dependencies) + '\n# ///\n',
            encoding="utf-8",
        )
        return path

    @staticmethod
    def wheel(directory, version):
        name = "inspection_fixture"
        info = f"{name}-{version}.dist-info"
        files = {
            f"{name}.py": f"VERSION = {version!r}\n",
            f"{info}/METADATA": f"Metadata-Version: 2.1\nName: inspection-fixture\nVersion: {version}\n",
            f"{info}/WHEEL": "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
        }
        records = []
        with zipfile.ZipFile(directory / f"{name}-{version}-py3-none-any.whl", "w") as wheel:
            for path, source in files.items():
                content = source.encode("utf-8")
                digest = base64.urlsafe_b64encode(hashlib.sha256(content).digest()).rstrip(b"=").decode("ascii")
                wheel.writestr(path, content)
                records.append(f"{path},sha256={digest},{len(content)}\n")
            wheel.writestr(f"{info}/RECORD", "".join(records) + f"{info}/RECORD,,\n")

    def test_script_sdk_resolves_imports_updates_and_refuses_conflicting_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve()
            for version in ["1.0", "2.0"]:
                self.wheel(repo, version)
            first = self.write_script(repo, "setup.py", ["inspection-fixture==1.0"])
            self.write_script(repo, "two.py", ["inspection-fixture==1.0"])
            python = f"{sys.version_info.major}.{sys.version_info.minor}"
            interpreter = repo / ".venv/bin/python"

            def installed_version():
                return subprocess.check_output(
                    # Same-sized fixture versions can be replaced within one
                    # filesystem timestamp tick; avoid persisting stale bytecode.
                    [str(interpreter), "-B", "-c", "import inspection_fixture; print(inspection_fixture.VERSION)"],
                    text=True,
                ).strip()

            with patch.dict(os.environ, {"UV_NO_INDEX": "true", "UV_FIND_LINKS": str(repo)}):
                prepare_python_project.prepare_script_environment(repo, python, ["setup.py", "two.py"])
                self.assertEqual(installed_version(), "1.0")
                self.write_script(repo, "two.py", ["inspection-fixture==2.0"])
                before = first.read_bytes()
                with self.assertRaisesRegex(RuntimeError, "could not be resolved"):
                    prepare_python_project.prepare_script_environment(repo, python, ["setup.py", "two.py"])
                self.assertEqual(installed_version(), "1.0")
                self.assertEqual(first.read_bytes(), before)
                prepare_python_project.prepare_script_environment(repo, python, ["two.py"])
                self.assertEqual(installed_version(), "2.0")
                self.write_script(repo, "two.py", [])
                prepare_python_project.prepare_script_environment(repo, python, ["two.py"])
                result = subprocess.run([str(interpreter), "-c", "import inspection_fixture"], capture_output=True)
                self.assertNotEqual(result.returncode, 0)

    def test_script_metadata_and_paths_fail_visibly(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve()
            path = self.write_script(repo, "script.py", [])
            self.assertEqual(prepare_python_project.script_paths(repo, ["script.py"]), [str(path)])
            for source in ["pass\n", "# /// script\n# dependencies = []\n",
                           '# /// script\n# dependencies = "one"\n# ///\n',
                           path.read_text() * 2]:
                path.write_text(source, encoding="utf-8")
                with patch.dict(os.environ, {"UV_NO_INDEX": "true"}), self.assertRaises(RuntimeError):
                    prepare_python_project.prepare_script_environment(repo, "3.12", ["script.py"])
                self.assertFalse((repo / ".venv").exists())
            for name in ["../outside.py", str(repo / "script.py")]:
                with self.assertRaisesRegex(RuntimeError, "inside the worktree"):
                    prepare_python_project.script_paths(repo, [name])

    def test_sdk_name_uses_home_relative_form(self):
        self.assertEqual(
            prepare_python_project.sdk_name_for(Path("/Users/example/work/.venv"), Path("/Users/example")),
            "~/work/.venv",
        )

    def test_rendered_project_model_uses_sdk_and_test_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve()
            test_root = repo / "tests & fixtures"
            test_root.mkdir()
            source = repo / "helper.py"
            source.touch()
            interpreter = repo / ".venv" / "bin" / "python"
            interpreter.parent.mkdir(parents=True)
            interpreter.touch()
            with patch("pathlib.Path.home", return_value=repo.parent):
                files = prepare_python_project.render_project_files(repo, "codex-skills", [test_root.name])

            modules = ElementTree.fromstring(files[repo / ".idea" / "modules.xml"])
            module_node = modules.find("./component/modules/module")
            assert module_node is not None
            filepath = module_node.get("filepath")
            assert filepath is not None
            module_path = Path(filepath.replace("$PROJECT_DIR$", str(repo)))
            model = ElementTree.fromstring(files[module_path])

            content = model.find("./component/content")
            assert content is not None
            content_root = resolve_model_url(content.get("url", ""), module_path, repo)
            self.assertEqual(content_root, repo)
            self.assertTrue(source.is_relative_to(content_root))
            test_folder = content.find("sourceFolder")
            assert test_folder is not None
            self.assertEqual(resolve_model_url(test_folder.get("url", ""), module_path, repo), test_root)
            self.assertEqual(test_folder.get("isTestSource"), "true")
            excluded = [resolve_model_url(folder.get("url", ""), module_path, repo) for folder in content.findall("excludeFolder")]
            self.assertTrue(any(interpreter.is_relative_to(folder) for folder in excluded))
            self.assertTrue(all(folder.parent == repo for folder in excluded))

            sdk = model.find("./component/orderEntry")
            assert sdk is not None
            self.assertEqual(sdk.get("jdkType"), "Python SDK")
            misc = ElementTree.fromstring(files[repo / ".idea" / "misc.xml"])
            misc_component = misc.find("component")
            assert misc_component is not None
            self.assertEqual(misc_component.get("project-jdk-name"), sdk.get("jdkName"))

    @staticmethod
    def sdk_table(path, entries):
        root = ElementTree.Element("application")
        component = ElementTree.SubElement(root, "component", name="ProjectJdkTable")
        for name, home, kind in entries:
            sdk = ElementTree.SubElement(component, "jdk")
            for tag, value in [("name", name), ("homePath", home), ("type", kind)]:
                ElementTree.SubElement(sdk, tag, value=value)
        ElementTree.ElementTree(root).write(path, encoding="unicode")

    def test_registered_sdk_identity_uses_interpreter_home_not_display_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            repo = home / "repo"
            table = home / "jdk.table.xml"
            self.sdk_table(table, [
                ("uv (repo) & Python", "$USER_HOME$/repo/.venv/bin/python", "Python SDK"),
                ("wrong", str(repo / ".venv/bin/python"), "JavaSDK"),
                ("other", str(home / "other/.venv/bin/python"), "Python SDK"),
            ])
            with patch("pathlib.Path.home", return_value=home):
                name = prepare_python_project.registered_sdk_name(repo / ".venv", table)
            self.assertEqual(name, "uv (repo) & Python")
            files = prepare_python_project.render_project_files(repo, "repo", [], name)
            self.assertEqual(ElementTree.fromstring(files[repo / ".idea/misc.xml"]).find("component").get("project-jdk-name"), name)
            self.assertEqual(ElementTree.fromstring(files[repo / ".idea/repo.iml"]).find("./component/orderEntry").get("jdkName"), name)

    def test_sdk_lookup_does_not_follow_interpreter_symlinks_across_worktrees(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = root / "python"
            shared.touch()
            for name in ["one", "two"]:
                interpreter = root / name / ".venv/bin/python"
                interpreter.parent.mkdir(parents=True)
                interpreter.symlink_to(shared)
            table = root / "jdk.table.xml"
            self.sdk_table(table, [("other", str(root / "two/.venv/bin/python"), "Python SDK")])
            self.assertIsNone(prepare_python_project.registered_sdk_name(root / "one/.venv", table))

    def test_cli_binds_both_models_to_registered_sdk(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve()
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            (repo / ".gitignore").write_text(".idea/\n.venv/\n", encoding="utf-8")
            (repo / ".venv").mkdir()
            table = repo / "jdk.table.xml"
            self.sdk_table(table, [("uv (fixture)", str(repo / ".venv/bin/python"), "Python SDK")])
            with (
                patch.object(sys, "argv", [str(SCRIPT_PATH), "--repo", str(repo), "--python", "3.12",
                                           "--module-name", "fixture", "--sdk-table", str(table)]),
                patch.dict(prepare_python_project.__dict__, {"validate_existing_venv": lambda *_: None}),
            ):
                self.assertEqual(prepare_python_project.main(), 0)
            misc = ElementTree.parse(repo / ".idea/misc.xml").getroot()
            module = ElementTree.parse(repo / ".idea/fixture.iml").getroot()
            self.assertEqual(misc.find("component").get("project-jdk-name"), "uv (fixture)")
            self.assertEqual(module.find("./component/orderEntry").get("jdkName"), "uv (fixture)")

    def test_cli_without_project_model_prepares_only_the_environment(self):
        # Headless engines register their own SDK; a generated model would name one they never register.
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary).resolve()
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            (repo / ".gitignore").write_text(".venv/\n", encoding="utf-8")
            (repo / ".venv").mkdir()
            with (
                patch.object(sys, "argv", [str(SCRIPT_PATH), "--repo", str(repo), "--python", "3.12",
                                           "--module-name", "fixture", "--no-project-model"]),
                patch.dict(prepare_python_project.__dict__, {"validate_existing_venv": lambda *_: None}),
            ):
                self.assertEqual(prepare_python_project.main(), 0)
            self.assertFalse((repo / ".idea").exists())

    def test_sdk_lookup_missing_ambiguous_and_malformed_tables(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            table = root / "jdk.table.xml"
            self.assertIsNone(prepare_python_project.registered_sdk_name(root / ".venv", None))
            self.assertIsNone(prepare_python_project.registered_sdk_name(root / ".venv", table))
            self.sdk_table(table, [(n, str(root / ".venv/bin/python"), "Python SDK") for n in ["one", "two"]])
            with self.assertRaisesRegex(RuntimeError, "Multiple Python SDK names"):
                prepare_python_project.registered_sdk_name(root / ".venv", table)
            table.write_text("<application>", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "Cannot read"):
                prepare_python_project.registered_sdk_name(root / ".venv", table)

    def test_atomic_write_skips_unchanged_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "file.xml"
            prepare_python_project.atomic_write(path, "same\n")
            before = path.stat().st_mtime_ns
            real_replace = prepare_python_project.os.replace
            with patch("os.replace", wraps=real_replace) as replace_file:
                prepare_python_project.atomic_write(path, "same\n")
                replace_file.assert_not_called()
                self.assertEqual(path.stat().st_mtime_ns, before)
                prepare_python_project.atomic_write(path, "changed\n")
                replace_file.assert_called_once()
            self.assertEqual(path.read_text(encoding="utf-8"), "changed\n")

    def test_preflight_requires_ignored_untracked_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            (repo / ".gitignore").write_text(".venv/\n.idea/misc.xml\n", encoding="utf-8")
            prepare_python_project.ensure_ignored_outputs(
                repo,
                [repo / ".venv", repo / ".idea" / "misc.xml"],
            )

            (repo / ".idea").mkdir()
            (repo / ".idea" / "misc.xml").write_text("tracked\n", encoding="utf-8")
            subprocess.run(["git", "add", "-f", ".idea/misc.xml"], cwd=repo, check=True)
            with self.assertRaisesRegex(RuntimeError, "must not be tracked"):
                prepare_python_project.ensure_ignored_outputs(repo, [repo / ".idea" / "misc.xml"])

    def test_sync_command_uses_python_and_requested_extras(self):
        self.assertEqual(
            prepare_python_project.build_sync_command("3.13", ["dev", "docs"]),
            [
                "uv",
                "sync",
                "--locked",
                "--python",
                "3.13",
                "--extra",
                "dev",
                "--extra",
                "docs",
            ],
        )


if __name__ == "__main__":
    unittest.main()
