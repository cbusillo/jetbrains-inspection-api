#!/usr/bin/env python3
"""Exercise the packaged metadata used by IDE automatic updates."""

from datetime import datetime, timedelta, timezone
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


class PublishFixture(FullReleaseFixture):
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


class PublishTest(PublishFixture):

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

    def test_concurrent_rebuild_reuses_the_verified_winning_release(self):
        self.publish()
        published = dict(self.assets)
        uploads = self.uploads
        archive, feed, checksums = release.bundle_files(self.version)
        with ZipFile(self.directory / archive, "a") as distribution:
            distribution.writestr("rebuilt-timestamp.txt", "later build")
        (self.directory / checksums).write_text("".join(
            f"{hashlib.sha256((self.directory / name).read_bytes()).hexdigest()}  {name}\n"
            for name in (archive, feed)
        ))
        self.publish()
        self.assertEqual(self.assets, published)
        self.assertEqual(self.uploads, uploads)


class MarketplaceDeliveryTest(PublishFixture):
    def setUp(self):
        super().setUp()
        self.publish()
        self.now = datetime(2026, 10, 18, tzinfo=timezone.utc)
        self.stable = [self.marketplace_update(1, "1.0.0", "", self.now - timedelta(days=10))]
        self.channel_updates = []
        self.marketplace_uploads = []
        self.approve = True
        self.upload_failure = None
        patch.object(release, "marketplace", side_effect=self.remote_marketplace).start()

    @staticmethod
    def marketplace_update(identifier, version, channel, created, approve=True):
        return {"id": identifier, "version": version, "channel": channel, "approve": approve,
                "listed": approve, "hidden": False, "cdate": str(int(created.timestamp() * 1000))}

    def remote_marketplace(self, path, *form):
        if path.startswith("/api/updates/upload"):
            if self.upload_failure:
                raise release.MarketplaceError(self.upload_failure)
            fields = dict(field.split("=", 1) for field in form)
            archive = Path(fields["file"].removeprefix("@"))
            self.marketplace_uploads.append({**fields, "bytes": archive.read_bytes()})
            update = self.marketplace_update(100 + len(self.marketplace_uploads), self.version,
                                             fields["channel"], self.now, self.approve)
            self.channel_updates.insert(0, update)
            return update
        channel = path.split("channel=", 1)[1].split("&", 1)[0]
        if channel == "" and self.stable is None:
            raise release.MarketplaceError("HTTP 500")
        updates = self.stable if channel == "" else self.channel_updates
        return [update for update in updates if update["channel"] == channel]

    def deliver(self, name="delivery"):
        return release.deliver_to_marketplace(self.root / name, self.repo, self.version, self.sha, self.now)

    def github_archive(self):
        return self.assets[release.bundle_files(self.version)[0]]

    def test_uploads_the_published_github_archive_once_outside_stable(self):
        self.deliver()
        self.deliver("rerun")
        self.assertEqual(len(self.marketplace_uploads), 1)
        upload = self.marketplace_uploads[0]
        self.assertEqual(upload["bytes"], self.github_archive())
        self.assertTrue(upload["channel"])
        self.assertNotEqual(upload["channel"].casefold(), "stable")
        self.assertEqual([update["version"] for update in self.stable], ["1.0.0"])

    def test_refuses_before_any_marketplace_request_without_a_verified_github_release(self):
        self.assets["updatePlugins.xml"] = b"tampered"
        with self.assertRaises(ValueError):
            self.deliver()
        self.record["draft"] = True
        self.assets["updatePlugins.xml"] = (self.directory / "updatePlugins.xml").read_bytes()
        with self.assertRaises(ValueError):
            self.deliver("draft")
        self.assertEqual(release.marketplace.call_count, 0)

    def test_marketplace_failure_leaves_the_github_release_published(self):
        published = dict(self.assets)
        self.upload_failure = "HTTP 500"
        with self.assertRaises(release.MarketplaceError):
            self.deliver()
        self.assertFalse(self.record["draft"])
        self.assertEqual(self.assets, published)

    def test_does_not_upload_an_older_build_after_a_newer_one(self):
        newer = ".".join([*self.version.split(".")[:3], str(int(self.version.split(".")[3]) + 1)])
        self.channel_updates = [self.marketplace_update(90, newer, release.MARKETPLACE_CHANNEL, self.now)]
        self.deliver()
        self.assertEqual(self.marketplace_uploads, [])

    def test_reports_moderation_and_the_auto_approval_window(self):
        self.approve = False
        report = self.deliver()
        self.assertTrue(any(line.startswith("::warning::") and "moderation" in line for line in report))
        self.stable = [self.marketplace_update(1, "1.0.0", "", self.now - timedelta(days=200))]
        self.assertEqual(len(release.auto_approval_report(self.now)), 1)
        self.stable = [self.marketplace_update(1, "1.0.0", "", self.now - timedelta(days=10))]
        self.assertEqual(release.auto_approval_report(self.now), [])
        self.approve = True
        self.channel_updates.clear()
        self.assertFalse(any(line.startswith("::warning::") for line in self.deliver("approved")))

    def test_a_stable_lookup_failure_is_reported_without_raising(self):
        self.stable = None
        report = release.auto_approval_report(self.now)
        self.assertEqual(len(report), 1)
        self.assertTrue(report[0].startswith("::warning::"))


class MarketplaceRequestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        bin_path = root / "bin"
        bin_path.mkdir()
        self.receipt = root / "request.json"
        fake_curl = bin_path / "curl"
        fake_curl.write_text("#!" + sys.executable + '''
import json, os, pathlib, sys
pathlib.Path(os.environ["CURL_RECEIPT"]).write_text(json.dumps({"argv": sys.argv[1:], "stdin": sys.stdin.read()}))
print(os.environ["CURL_RESPONSE"])
sys.exit(int(os.environ["CURL_EXIT"]))
''')
        fake_curl.chmod(0o755)
        self.token = "fixture-secret-token"
        self.env = {"PATH": f"{bin_path}:{os.environ['PATH']}", "PUBLISH_TOKEN": self.token,
                    "CURL_RECEIPT": str(self.receipt), "CURL_RESPONSE": json.dumps({"id": 7}), "CURL_EXIT": "0"}

    def request(self, **env):
        with patch.dict(os.environ, {**self.env, **env}):
            return release.marketplace("/api/updates/upload", "channel=agents", "file=@/tmp/plugin.zip")

    def test_sends_the_token_on_stdin_and_the_form_to_marketplace(self):
        self.assertEqual(self.request(), {"id": 7})
        sent = json.loads(self.receipt.read_text())
        self.assertNotIn(self.token, " ".join(sent["argv"]))
        self.assertEqual(sent["stdin"].strip(), f"Authorization: Bearer {self.token}")
        self.assertEqual(sent["argv"][-1], f"{release.MARKETPLACE}/api/updates/upload")
        forms = [sent["argv"][i + 1] for i, value in enumerate(sent["argv"]) if value == "--form"]
        self.assertEqual(forms, ["channel=agents", "file=@/tmp/plugin.zip"])

    def test_an_error_response_raises_without_the_token(self):
        with self.assertRaises(release.MarketplaceError) as raised:
            self.request(CURL_RESPONSE="Plugin version already exists", CURL_EXIT="22")
        self.assertIn("already exists", str(raised.exception))
        self.assertNotIn(self.token, str(raised.exception))

    def test_refuses_without_a_token(self):
        with self.assertRaises(ValueError):
            self.request(PUBLISH_TOKEN="")
        self.assertFalse(self.receipt.exists())


class ReleaseReadTest(unittest.TestCase):
    def test_only_a_confirmed_404_is_absence(self):
        with patch.object(release, "gh", side_effect=[subprocess.CalledProcessError(
            1, "gh", output="HTTP/2.0 404 Not Found\n\n{}"
        ), "[]"]):
            self.assertIsNone(release.release_record("fixture/repo", "agents/v1.2.3.4"))
        with patch.object(release, "gh", side_effect=subprocess.CalledProcessError(
            1, "gh", output="HTTP/2.0 403 Forbidden\n\n{}"
        )), self.assertRaises(subprocess.CalledProcessError):
            release.release_record("fixture/repo", "agents/v1.2.3.4")

    def test_recovers_a_draft_omitted_by_the_published_tag_endpoint(self):
        draft = {"id": 19, "draft": True, "tag_name": "agents/v1.2.3.4", "assets": []}
        with patch.object(release, "gh", side_effect=[subprocess.CalledProcessError(
            1, "gh", output="HTTP/2.0 404 Not Found\n\n{}"
        ), json.dumps([[draft]])]):
            self.assertEqual(release.release_record("fixture/repo", draft["tag_name"]), draft)


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
