#!/usr/bin/env python3
"""Version and describe a clean full-build release for a custom repository."""

import argparse
import io
import re
from pathlib import Path
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def release_version(properties: Path, sequence: int) -> str:
    match = re.search(r"^pluginVersion=(\d+\.\d+\.\d+)\s*$", properties.read_text(), re.M)
    if match is None or sequence < 1:
        raise ValueError("A numeric base version and positive main commit sequence are required")
    return f"{match[1]}.{sequence}"


def plugin_descriptor(archive: Path, version: str, source_sha: str) -> ET.Element:
    if not re.fullmatch(r"\d+\.\d+\.\d+\.\d+", version):
        raise ValueError("Full-build releases require four numeric version components")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("A full source commit is required")
    jar_name = f"jetbrains-inspection-api/lib/jetbrains-inspection-api-{version}.jar"
    with ZipFile(archive) as distribution:
        if distribution.namelist().count(jar_name) != 1:
            raise ValueError("The distribution must contain exactly one versioned plugin jar")
        jar_bytes = distribution.read(jar_name)
    with ZipFile(io.BytesIO(jar_bytes)) as jar:
        metadata_name = "com/shiny/inspectionmcp/inspection-build.properties"
        if any(jar.namelist().count(name) != 1 for name in ("META-INF/plugin.xml", metadata_name)):
            raise ValueError("The plugin jar must contain unique descriptor and provenance files")
        descriptor = ET.fromstring(jar.read("META-INF/plugin.xml"))
        metadata = dict(
            line.split("=", 1) for line in jar.read(metadata_name).decode().splitlines()
            if line and not line.startswith(("#", "!"))
        )
    if descriptor.findtext("id") != "com.shiny.inspection.api" or descriptor.findtext("version") != version:
        raise ValueError("Plugin identity/version does not match the release")
    expected = {
        "plugin.version": version,
        "plugin.build.commit": source_sha,
        "plugin.build.short_commit": source_sha[:12],
        "plugin.build.dirty": "false",
        "plugin.build.fingerprint": f"{source_sha}-clean",
    }
    if any(metadata.get(key) != value for key, value in expected.items()):
        raise ValueError("Plugin provenance does not attest the clean release source")
    compatibility = descriptor.find("idea-version")
    if compatibility is None or not compatibility.get("since-build"):
        raise ValueError("Plugin compatibility metadata is missing")
    return descriptor


def update_feed(archive: Path, version: str, source_sha: str, download_url: str) -> bytes:
    if urlsplit(download_url).scheme != "https" or not urlsplit(download_url).netloc:
        raise ValueError("The plugin download URL must use HTTPS")
    descriptor = plugin_descriptor(archive, version, source_sha)
    root = ET.Element("plugins")
    plugin = ET.SubElement(root, "plugin", {
        "id": descriptor.findtext("id"), "version": version, "url": download_url,
    })
    ET.SubElement(plugin, "idea-version", dict(descriptor.find("idea-version").attrib))
    ET.SubElement(plugin, "name").text = descriptor.findtext("name")
    for dependency in descriptor.findall("depends"):
        if dependency.get("optional") != "true":
            ET.SubElement(plugin, "depends").text = dependency.text
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version_parser = commands.add_parser("version")
    version_parser.add_argument("--properties", type=Path, default=Path("gradle.properties"))
    version_parser.add_argument("--sequence", type=int, required=True)
    feed_parser = commands.add_parser("feed")
    feed_parser.add_argument("--archive", type=Path, required=True)
    feed_parser.add_argument("--version", required=True)
    feed_parser.add_argument("--source-sha", required=True)
    feed_parser.add_argument("--download-url", required=True)
    feed_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "version":
        print(release_version(args.properties, args.sequence))
    else:
        args.output.write_bytes(update_feed(args.archive, args.version, args.source_sha, args.download_url))


if __name__ == "__main__":
    main()
