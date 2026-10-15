#!/usr/bin/env python3
"""Version and describe a clean full-build release for a custom repository."""

import argparse
import hashlib
import io
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
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


def gh(*args: str) -> str:
    if not os.environ.get("GH_TOKEN"):
        raise ValueError("An explicit GH_TOKEN is required")
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def release_record(repo: str, tag: str) -> dict | None:
    try:
        response = gh("api", "--include", f"repos/{repo}/releases/tags/{tag.replace('/', '%2F')}")
    except subprocess.CalledProcessError as error:
        if re.match(r"HTTP/\S+ 404\b", error.stdout or ""):
            pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
            matches = [record for page in pages for record in page if record["tag_name"] == tag]
            if len(matches) > 1:
                raise ValueError("Multiple releases for this tag require reconciliation")
            return matches[0] if matches else None
        raise
    return json.loads(response.replace("\r\n", "\n").split("\n\n", 1)[1])


def bundle_files(version: str) -> tuple[str, ...]:
    return (f"jetbrains-inspection-api-{version}.zip", "updatePlugins.xml", "SHA256SUMS")


def verify_bundle(directory: Path, repo: str, version: str, source_sha: str) -> None:
    archive, feed, checksums = bundle_files(version)
    expected = dict(line.split(maxsplit=1)[::-1] for line in (directory / checksums).read_text().splitlines())
    if set(expected) != {archive, feed}:
        raise ValueError("Release checksums must name exactly the plugin and its feed")
    for name, digest in expected.items():
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Release asset digest mismatch: {name}")
    url = f"https://github.com/{repo}/releases/download/agents%2Fv{version}/{archive}"
    if (directory / feed).read_bytes() != update_feed(directory / archive, version, source_sha, url):
        raise ValueError("The feed does not describe the exact release artifact")


def reuse_release(directory: Path, repo: str, version: str, source_sha: str) -> bool:
    tag = f"agents/v{version}"
    record = release_record(repo, tag)
    if not record or record["draft"] or not set(bundle_files(version)) <= {a["name"] for a in record["assets"]}:
        return False
    if record["target_commitish"] != source_sha:
        raise ValueError("Existing release points at another source")
    directory.mkdir(parents=True, exist_ok=True)
    gh("release", "download", tag, "--repo", repo, "--dir", str(directory))
    verify_bundle(directory, repo, version, source_sha)
    return True


def publish_release(directory: Path, repo: str, version: str, source_sha: str, notes: Path) -> None:
    verify_bundle(directory, repo, version, source_sha)
    tag = f"agents/v{version}"
    record = release_record(repo, tag)
    if record and record["target_commitish"] != source_sha:
        raise ValueError("Existing release points at another source")
    if record is None:
        gh("release", "create", tag, "--repo", repo, "--target", source_sha,
           "--title", f"Full build {version}", "--notes-file", str(notes), "--draft", "--latest=false")
        record = {"draft": True, "assets": []}
    existing = {asset["name"] for asset in record["assets"]}
    with tempfile.TemporaryDirectory() as scratch:
        for name in bundle_files(version):
            if name in existing:
                gh("release", "download", tag, "--repo", repo, "--pattern", name, "--dir", scratch)
                if (Path(scratch) / name).read_bytes() != (directory / name).read_bytes():
                    raise ValueError(f"Refusing to replace existing release asset: {name}")
            else:
                gh("release", "upload", tag, str(directory / name), "--repo", repo)
    if record["draft"]:
        gh("release", "edit", tag, "--repo", repo, "--draft=false", "--latest=false")


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
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--archive", type=Path, required=True)
    verify_parser.add_argument("--version", required=True)
    verify_parser.add_argument("--source-sha", required=True)
    verify_parser.add_argument("--expected-sha256", required=True)
    for command in ("reuse", "publish"):
        release_parser = commands.add_parser(command)
        release_parser.add_argument("--directory", type=Path, required=True)
        release_parser.add_argument("--repo", required=True)
        release_parser.add_argument("--version", required=True)
        release_parser.add_argument("--source-sha", required=True)
        if command == "publish":
            release_parser.add_argument("--notes", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "version":
        print(release_version(args.properties, args.sequence))
    elif args.command == "feed":
        args.output.write_bytes(update_feed(args.archive, args.version, args.source_sha, args.download_url))
    elif args.command == "reuse":
        print(str(reuse_release(args.directory, args.repo, args.version, args.source_sha)).lower())
    elif args.command == "verify":
        if not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256):
            raise ValueError("A recorded archive SHA-256 is required")
        if hashlib.sha256(args.archive.read_bytes()).hexdigest() != args.expected_sha256:
            raise ValueError("Archive does not match the recorded release digest")
        plugin_descriptor(args.archive, args.version, args.source_sha)
    else:
        publish_release(args.directory, args.repo, args.version, args.source_sha, args.notes)


if __name__ == "__main__":
    main()
