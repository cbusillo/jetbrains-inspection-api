#!/usr/bin/env python3
"""Exercise the packaged metadata used by IDE automatic updates."""

import importlib.util
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from zipfile import ZipFile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("agents_release", Path(__file__).with_name("agents-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class FullReleaseFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = "2.3.4"
        self.properties = self.root / "gradle.properties"
        self.properties.write_text(f"pluginVersion={self.base}\n")
        self.version = release.release_version(self.properties, 17)
        self.sha = "a" * 40
        self.url = "https://example.invalid/releases/plugin.zip?one=1&two=2"

    def archive(self, *, dirty="false", source=None, version=None, since="261", until="262.*"):
        descriptor = ET.Element("idea-plugin")
        ET.SubElement(descriptor, "id").text = "com.shiny.inspection.api"
        ET.SubElement(descriptor, "name").text = "Inspection API"
        ET.SubElement(descriptor, "version").text = version or self.version
        ET.SubElement(descriptor, "idea-version", {"since-build": since, "until-build": until})
        ET.SubElement(descriptor, "depends").text = "com.intellij.modules.platform"
        ET.SubElement(descriptor, "depends", {"optional": "true"}).text = "PythonCore"
        commit = source or self.sha
        metadata = {
            "plugin.version": self.version,
            "plugin.build.commit": commit,
            "plugin.build.short_commit": commit[:12],
            "plugin.build.dirty": dirty,
            "plugin.build.fingerprint": f"{commit}-clean",
        }
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as jar:
            jar.writestr("META-INF/plugin.xml", ET.tostring(descriptor))
            jar.writestr("com/shiny/inspectionmcp/inspection-build.properties", "\n".join(
                f"{key}={value}" for key, value in metadata.items()
            ))
        path = self.root / "plugin.zip"
        with ZipFile(path, "w") as distribution:
            distribution.writestr(
                f"jetbrains-inspection-api/lib/jetbrains-inspection-api-{self.version}.jar", buffer.getvalue()
            )
        return path

class FullReleaseTest(FullReleaseFixture):
    def test_version_orders_consecutive_main_commits(self):
        current = tuple(map(int, self.version.split(".")))
        following = tuple(map(int, release.release_version(self.properties, 18).split(".")))
        self.assertGreater(following, current)
        self.assertEqual(current[:3], tuple(map(int, self.base.split("."))))

    def test_feed_describes_the_actual_archive_and_required_dependencies(self):
        archive = self.archive()
        feed = ET.fromstring(release.update_feed(archive, self.version, self.sha, self.url))
        plugin = feed.find("plugin")
        packaged = release.plugin_descriptor(archive, self.version, self.sha)
        self.assertEqual(plugin.get("version"), packaged.findtext("version"))
        self.assertEqual(plugin.get("id"), packaged.findtext("id"))
        self.assertEqual(plugin.get("url"), self.url)
        self.assertEqual(plugin.find("idea-version").attrib, packaged.find("idea-version").attrib)
        self.assertEqual([d.text for d in plugin.findall("depends")], [
            d.text for d in packaged.findall("depends") if d.get("optional") != "true"
        ])

    def test_refuses_wrong_source_dirty_build_and_wrong_plugin_version(self):
        for changes in ({"source": "b" * 40}, {"dirty": "true"}, {"version": self.base}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                release.update_feed(self.archive(**changes), self.version, self.sha, self.url)

    def test_refuses_missing_compatibility_and_insecure_download(self):
        with self.assertRaises(ValueError):
            release.update_feed(self.archive(since=""), self.version, self.sha, self.url)
        with self.assertRaises(ValueError):
            release.update_feed(self.archive(), self.version, self.sha, self.url.replace("https:", "http:"))


class PublishTest(FullReleaseFixture):
    def setUp(self):
        super().setUp()
        self.repo = "fixture/inspection-api"
        self.directory = self.root / "bundle"
        self.directory.mkdir()
        archive_name, feed_name, checksum_name = release.bundle_files(self.version)
        (self.directory / archive_name).write_bytes(self.archive().read_bytes())
        url = f"https://github.com/{self.repo}/releases/download/agents%2Fv{self.version}/{archive_name}"
        (self.directory / feed_name).write_bytes(release.update_feed(
            self.directory / archive_name, self.version, self.sha, url
        ))
        (self.directory / checksum_name).write_text("".join(
            f"{hashlib.sha256((self.directory / name).read_bytes()).hexdigest()}  {name}\n"
            for name in (archive_name, feed_name)
        ))
        self.assets = {}
        self.record = None
        self.uploads = 0
        self.publication_contents = None
        self.notes = self.root / "notes.md"
        self.notes.write_text("Fixture release")
        self.addCleanup(patch.stopall)
        patch.object(release, "release_record", side_effect=self.remote_record).start()
        patch.object(release, "gh", side_effect=self.remote_command).start()

    def remote_record(self, *_):
        if self.record is None:
            return None
        return {**self.record, "assets": [{"name": name} for name in self.assets]}

    def remote_command(self, *args):
        command = args[1]
        if command == "create":
            self.record = {"draft": True, "target_commitish": self.sha}
        elif command == "upload":
            path = Path(args[3])
            self.assets[path.name] = path.read_bytes()
            self.uploads += 1
        elif command == "download":
            directory = Path(args[args.index("--dir") + 1])
            names = [args[args.index("--pattern") + 1]] if "--pattern" in args else self.assets
            for name in names:
                (directory / name).write_bytes(self.assets[name])
        elif command == "edit":
            self.publication_contents = dict(self.assets)
            self.record["draft"] = False
        else:
            self.fail(f"Unexpected remote operation {command}")
        return ""

    def publish(self):
        release.publish_release(self.directory, self.repo, self.version, self.sha, self.notes)

    def test_uploads_all_assets_before_publishing_and_reuses_without_reupload(self):
        self.publish()
        self.assertFalse(self.record["draft"])
        self.assertEqual(self.assets, {p.name: p.read_bytes() for p in self.directory.iterdir()})
        self.assertEqual(self.publication_contents, self.assets)
        uploads = self.uploads
        self.publish()
        self.assertEqual(self.uploads, uploads)
        self.assertTrue(release.reuse_release(self.root / "downloaded", self.repo, self.version, self.sha))

    def test_resumes_a_draft_with_a_partly_uploaded_bundle(self):
        self.record = {"draft": True, "target_commitish": self.sha}
        archive = release.bundle_files(self.version)[0]
        self.assets[archive] = (self.directory / archive).read_bytes()
        self.publish()
        self.assertFalse(self.record["draft"])
        self.assertEqual(self.assets, {p.name: p.read_bytes() for p in self.directory.iterdir()})

    def test_preserves_existing_different_bytes_and_does_not_publish(self):
        self.record = {"draft": True, "target_commitish": self.sha}
        name = release.bundle_files(self.version)[0]
        self.assets[name] = b"different published bytes"
        with self.assertRaises(ValueError):
            self.publish()
        self.assertTrue(self.record["draft"])
        self.assertEqual(self.assets[name], b"different published bytes")
        self.assertEqual(self.uploads, 0)

    def test_a_corrupt_bundle_cannot_upload(self):
        (self.directory / "updatePlugins.xml").write_text("corrupt")
        with self.assertRaises(ValueError):
            self.publish()
        self.assertIsNone(self.record)
        self.assertEqual(self.uploads, 0)


class ReleaseReadTest(unittest.TestCase):
    def test_only_a_confirmed_404_is_absence(self):
        with patch.object(release, "gh", side_effect=subprocess.CalledProcessError(
            1, "gh", output="HTTP/2.0 404 Not Found\n\n{}"
        )):
            self.assertIsNone(release.release_record("fixture/repo", "agents/v1.2.3.4"))
        with patch.object(release, "gh", side_effect=subprocess.CalledProcessError(
            1, "gh", output="HTTP/2.0 403 Forbidden\n\n{}"
        )), self.assertRaises(subprocess.CalledProcessError):
            release.release_record("fixture/repo", "agents/v1.2.3.4")


class ReleaseNotesTest(unittest.TestCase):
    def test_full_builds_do_not_shorten_the_stable_changelog(self):
        script = Path(__file__).with_name("generate-release-notes.sh").resolve()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            env = {**os.environ, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}

            def git(*args):
                subprocess.run(["git", *args], cwd=root, env=env, check=True, capture_output=True)

            git("-c", "init.defaultBranch=main", "init")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.invalid")
            tracked = root / "source.txt"
            tracked.write_text("base")
            git("add", ".")
            git("commit", "-m", "Initial fixture")
            stable = "v2.3.4"
            git("tag", stable)
            fix_subject = "fix: preserve the first change in the fixture"
            tracked.write_text("fix")
            git("commit", "-am", fix_subject)
            full = "agents/v2.3.5.19"
            git("tag", full)
            tracked.write_text("feature")
            feature_subject = "feat: include the second change in the fixture"
            git("commit", "-am", feature_subject)
            bin_path = root / "bin"
            bin_path.mkdir()
            fixture = root / "releases.json"
            fixture.write_text(json.dumps([
                {"tag_name": full, "tagName": full, "draft": False},
                {"tag_name": stable, "tagName": stable, "draft": False},
            ]))
            fake_gh = bin_path / "gh"
            fake_gh.write_text('#!/bin/bash\nset -eu\njq -r "${@: -1}" "$RELEASE_FIXTURE"\n')
            fake_gh.chmod(0o755)
            env.update(PATH=f"{bin_path}:{env['PATH']}", RELEASE_FIXTURE=str(fixture))
            output = root / "notes.md"
            subprocess.run([str(script), "--version", "2.3.5", "--output", str(output)],
                           cwd=root, env=env, check=True, capture_output=True)
            notes = output.read_text()
            self.assertIn(fix_subject, notes)
            self.assertIn(feature_subject, notes)
            self.assertIn(stable, notes)


if __name__ == "__main__":
    unittest.main()
