#!/usr/bin/env python3
"""Exercise the packaged metadata used by IDE automatic updates."""

import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("agents_release", Path(__file__).with_name("agents-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class FullReleaseTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
