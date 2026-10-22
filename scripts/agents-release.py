#!/usr/bin/env python3

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from zipfile import ZipFile

MARKETPLACE = "https://plugins.jetbrains.com"
MARKETPLACE_PLUGIN = 29580
MARKETPLACE_XML_ID = "com.shiny.inspection.api"
MARKETPLACE_CHANNEL = "agents"
AUTO_APPROVAL_WINDOW = timedelta(days=120)
AUTO_APPROVAL_NOTICE = timedelta(days=30)


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
            line.split("=", 1) for line in jar.read(metadata_name).decode().splitlines() if line and not line.startswith(("#", "!"))
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
    plugin = ET.SubElement(
        root,
        "plugin",
        {
            "id": descriptor.findtext("id"),
            "version": version,
            "url": download_url,
        },
    )
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


def release_record(repo: str, tag: str) -> dict[str, Any] | None:
    try:
        response = gh("api", "--include", f"repos/{repo}/releases/tags/{tag.replace('/', '%2F')}")
    except subprocess.CalledProcessError as error:
        if re.match(r"HTTP/\S+ 404\b", error.stdout or ""):
            return find_release_in_pages(repo, tag)
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
    if record and not record["draft"]:
        with tempfile.TemporaryDirectory() as scratch:
            if reuse_release(Path(scratch), repo, version, source_sha):
                return
    if record is None:
        gh(
            "release",
            "create",
            tag,
            "--repo",
            repo,
            "--target",
            source_sha,
            "--title",
            f"Full build {version}",
            "--notes-file",
            str(notes),
            "--draft",
            "--latest=false",
        )
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


class MarketplaceError(RuntimeError):
    pass


def marketplace(path: str, *form: str) -> Any:
    token = os.environ.get("PUBLISH_TOKEN")
    if not token:
        raise ValueError("PUBLISH_TOKEN is required for Marketplace requests")
    command = ["curl", "--fail-with-body", "--silent", "--show-error", "--header", "@-"]
    for field in form:
        command += ["--form", field]
    result = subprocess.run(
        [*command, f"{MARKETPLACE}{path}"], input=f"Authorization: Bearer {token}\n", capture_output=True, text=True
    )
    if result.returncode:
        detail = (result.stdout.strip() or result.stderr.strip())[:2000]
        raise MarketplaceError(f"Marketplace request {path.split('?')[0]} failed: {detail}")
    return json.loads(result.stdout)


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def is_listed(update: dict[str, Any]) -> bool:
    return bool(update.get("approve") and update.get("listed") and not update.get("hidden"))


def auto_approval_ends(stable_updates: list[dict[str, Any]]) -> datetime | None:
    approved = [int(update["cdate"]) for update in stable_updates if is_listed(update)]
    if not approved:
        return None
    return datetime.fromtimestamp(max(approved) / 1000, UTC) + AUTO_APPROVAL_WINDOW


def deliver_to_marketplace(directory: Path, repo: str, version: str, source_sha: str, now: datetime) -> list[str]:
    if not reuse_release(directory, repo, version, source_sha):
        raise ValueError("The GitHub release must be published and verified before the Marketplace upload")
    archive = directory / bundle_files(version)[0]
    updates = f"/api/plugins/{MARKETPLACE_PLUGIN}/updates"
    channel = marketplace(f"{updates}?channel={MARKETPLACE_CHANNEL}&size=100")
    report: list[str] = []
    existing = [update for update in channel if update["version"] == version]
    newest = max((update["version"] for update in channel), key=version_key, default=None)
    if existing:
        update = existing[0]
        report.append(f"{version} was already uploaded to {MARKETPLACE_CHANNEL}; not uploading again.")
    elif newest and version_key(newest) > version_key(version):
        return [f"Skipped {version}: {MARKETPLACE_CHANNEL} already has newer build {newest}."]
    else:
        update = marketplace(
            "/api/updates/upload", f"xmlId={MARKETPLACE_XML_ID}", f"file=@{archive}", f"channel={MARKETPLACE_CHANNEL}"
        )
        if update.get("version") != version or update.get("channel") != MARKETPLACE_CHANNEL:
            raise MarketplaceError(f"Marketplace recorded an unexpected update: {update.get('id')}")
        report.append(f"Uploaded {version} to {MARKETPLACE_CHANNEL} as update {update['id']}.")
    if is_listed(update):
        report.append(f"Update {update['id']} is approved and listed on {MARKETPLACE_CHANNEL}.")
    else:
        report.append(
            f"::warning::Update {update['id']} ({version}) is waiting for JetBrains moderation "
            f"and is not yet listed on {MARKETPLACE_CHANNEL}. The GitHub release is unaffected."
        )
    return report


def auto_approval_report(now: datetime) -> list[str]:
    try:
        stable = marketplace(f"/api/plugins/{MARKETPLACE_PLUGIN}/updates?channel=&size=100")
    except MarketplaceError as error:
        return [f"::warning::Could not read the Stable approval state for agents auto-approval: {error}"]
    ends = auto_approval_ends(stable)
    if ends is None or ends <= now:
        return [
            f"::warning::Agents auto-approval no longer applies: no approved Stable update in the "
            f"last {AUTO_APPROVAL_WINDOW.days} days. New agents uploads go to normal moderation."
        ]
    if ends - now <= AUTO_APPROVAL_NOTICE:
        return [
            f"::warning::Agents auto-approval ends {ends:%B} {ends.day}, {ends.year}, "
            f"{AUTO_APPROVAL_WINDOW.days} days after the latest approved Stable update. "
            "Later uploads go to normal moderation."
        ]
    return []


def find_release_in_pages(repo: str, tag: str) -> dict[str, Any] | None:
    pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    matches = [record for page in pages for record in page if record["tag_name"] == tag]
    if len(matches) > 1:
        raise ValueError("Multiple releases for this tag require reconciliation")
    return matches[0] if matches else None


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Version, publish and deliver a clean full-build release to GitHub and the Marketplace agents channel."
    )
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
    for command in ("reuse", "publish", "marketplace"):
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
    elif args.command == "marketplace":
        now = datetime.now(UTC)
        print("\n".join(deliver_to_marketplace(args.directory, args.repo, args.version, args.source_sha, now)), flush=True)
        print("\n".join(auto_approval_report(now)))
    else:
        publish_release(args.directory, args.repo, args.version, args.source_sha, args.notes)


if __name__ == "__main__":
    main()
