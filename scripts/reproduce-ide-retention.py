#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Measure retained objects across helper cycles in an isolated, already running IDE."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import zipfile


COUNTED_CLASSES = {
    "com.intellij.openapi.project.impl.ProjectImpl": "projects",
    "com.shiny.inspectionmcp.NativeAttestedGlobalInspectionContext": "native_contexts",
    "com.shiny.inspectionmcp.InspectionResultsSnapshot": "snapshots",
    "com.shiny.inspectionmcp.InspectionAnalysisQualification": "analysis_qualifications",
    "com.intellij.openapi.progress.util.ProgressWindow": "progress_windows",
}


def verify_installed_classes(root: Path, fingerprint: str | None) -> None:
    entries: set[str] = set()
    fingerprints: set[str] = set()
    for path in (root / "plugins").rglob("*.jar"):
        with zipfile.ZipFile(path) as archive:
            entries.update(archive.namelist())
            resource = "com/shiny/inspectionmcp/inspection-build.properties"
            if resource in archive.namelist():
                properties = dict(line.split("=", 1) for line in archive.read(resource).decode().splitlines()
                                  if "=" in line and not line.startswith("#"))
                fingerprints.add(properties.get("plugin.build.fingerprint", ""))
    expected = {name.replace(".", "/") + ".class" for name in COUNTED_CLASSES
                if name.startswith("com.shiny.")}
    if not fingerprint or fingerprints != {fingerprint} or not expected.issubset(entries):
        raise ValueError("Installed plugin provenance or counted classes cannot be verified")


def isolated_properties(properties: str, root: Path) -> bool:
    values = dict(line.split("=", 1) for line in properties.splitlines() if "=" in line)
    return all(
        values.get(f"idea.{key}.path") == str(root / directory)
        for key, directory in (
            ("config", "config"), ("system", "system"), ("plugins", "plugins"), ("log", "logs")
        )
    )


def histogram_counts(histogram: str) -> dict[str, int]:
    counts = dict.fromkeys(COUNTED_CLASSES.values(), 0)
    for line in histogram.splitlines():
        match = re.match(r"\s*\d+:\s+(\d+)\s+\d+\s+(\S+)", line)
        if match and match[2] in COUNTED_CLASSES:
            counts[COUNTED_CLASSES[match[2]]] += int(match[1])
    total = re.search(r"^Total\s+\d+\s+(\d+)", histogram, re.MULTILINE)
    if total:
        counts["heap_object_bytes"] = int(total[1])
    return counts


def run_helper(command: list[str], *, env: dict[str, str], stdout, stderr) -> None:
    with subprocess.Popen(command, env=env, stdout=stdout, stderr=stderr, text=True,
                          start_new_session=True) as process:
        try:
            process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=10)
            raise


def run(args: argparse.Namespace) -> list[dict[str, object]]:
    if not hasattr(os, "killpg"):
        raise ValueError("Use a POSIX host for isolated helper process-group control")
    root = args.instance_root.resolve(strict=True)
    output = args.output.resolve()
    if output == root or not output.is_relative_to(root):
        raise ValueError("Output must be a new directory inside the disposable instance root")
    properties = subprocess.run(
        [str(args.jcmd), str(args.pid), "VM.system_properties"],
        capture_output=True, text=True, check=True, timeout=30,
    ).stdout
    if not isolated_properties(properties, root):
        raise ValueError("IDE config, system, plugins and logs must all belong to this disposable root")
    spec = importlib.util.spec_from_file_location("retention_inspection_helper", args.helper)
    if spec is None or spec.loader is None:
        raise ValueError("Cannot load the maintained inspection helper")
    helper = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = helper
    spec.loader.exec_module(helper)
    if args.port in helper.DEFAULT_PORT_RANGE:
        raise ValueError("Disposable IDE port must be outside normal helper discovery")
    try:
        helper.build_parser().parse_args(["inspect-closeout", "--no-ide-launch"])
    except SystemExit as error:
        raise ValueError("The maintained helper must support --no-ide-launch before measurement") from error

    def live_identity() -> dict[str, object]:
        try:
            live = helper.identity_for_port(args.port)
        except helper.InspectError as error:
            raise ValueError("Disposable IDE is unavailable; stop and preserve its evidence") from error
        if live.get("pid") != args.pid:
            raise ValueError("The inspection port belongs to a different IDE process")
        projects = live.get("open_projects") or []
        if not projects or any(not project.get("base_path") or
                               not Path(project["base_path"]).resolve().is_relative_to(root)
                               for project in projects):
            raise ValueError("Open a disposable startup project inside the instance root; foreign projects are refused")
        try:
            route = helper.http_get(args.port, "route", {
                "session_id": live["session_id"], "project_key": projects[0]["project_key"],
            }).body
        except helper.InspectError as error:
            raise ValueError("Cannot verify disposable registry isolation") from error
        registry = (route.get("registry") or {}).get("instances_dir")
        if not registry or not Path(registry).resolve().is_relative_to(root / "registry"):
            raise ValueError("IDE process must use this instance's private registry")
        return live

    identity = live_identity()
    if identity.get("pid") != args.pid:
        raise ValueError("The inspection port belongs to a different IDE process")
    session = identity["session_id"]
    verify_installed_classes(root, identity.get("plugin_build_fingerprint"))
    output.mkdir(parents=True, exist_ok=False)
    environment = dict(os.environ)
    environment.update(
        JETBRAINS_INSPECTION_CACHE_DIR=str(root / "helper-cache"),
        JETBRAINS_INSPECTION_REGISTRY_DIR=str(root / "registry"),
        JETBRAINS_INSPECTION_IDE_CONFIG_DIR=str(root / "config"),
        JETBRAINS_INSPECTION_TRUSTED_AUTO_OPEN_ROOTS=str(output / "fixtures"),
        JB_INSPECT_OUTCOME_LOG=str(output / "outcomes.jsonl"),
    )
    rows: list[dict[str, object]] = []

    def observe(cycle: int) -> None:
        live = live_identity()
        if live.get("pid") != args.pid or live.get("session_id") != session:
            raise ValueError("IDE identity changed; measurements cannot be compared")
        histogram = subprocess.run(
            [str(args.jcmd), str(args.pid), "GC.class_histogram"],
            capture_output=True, text=True, check=True, timeout=60,
        ).stdout
        (output / f"cycle-{cycle:02d}.histo").write_text(histogram)
        row: dict[str, object] = {"cycle": cycle, **histogram_counts(histogram)}
        if cycle == 0 and row["projects"] < 1:
            raise ValueError("The open startup project is missing from the histogram; project counts are unqualified")
        rows.append(row)
        (output / "measurements.json").write_text(json.dumps({
            "session_id": session,
            "plugin_build_fingerprint": identity.get("plugin_build_fingerprint"),
            "rows": rows,
        }, indent=2) + "\n")
        print(json.dumps(row), flush=True)

    observe(0)
    for cycle in range(1, args.cycles + 1):
        live = live_identity()
        if live.get("session_id") != session:
            raise ValueError("IDE session changed; stop before opening another project")
        failure = helper.ide_memory_failure(live)
        if failure is not None and failure.payload.get("error_reason") == "ide_memory_exhausted":
            raise ValueError("IDE memory exhaustion detected; stop the batch and use supervised recovery")
        fixture = output / "fixtures" / f"cycle-{cycle:02d}"
        fixture.mkdir(parents=True)
        (fixture / "sample.xml").write_text('<root><entry name="retention"/></root>\n')
        command = [
            "uv", "run", str(args.helper), "inspect-closeout", "--repo", str(fixture),
            "--port", str(args.port), "--session-id", str(session), "--no-ide-launch",
            "--ide", args.ide, "--scope", "files", "--file", "sample.xml",
            "--timeout-ms", "120000", "--prepare-timeout-ms", "90000", "--json",
        ]
        stdout_path = output / f"cycle-{cycle:02d}.json"
        with stdout_path.open("w") as stdout, (output / f"cycle-{cycle:02d}.stderr").open("w") as stderr:
            run_helper(command, env=environment, stdout=stdout, stderr=stderr)
        payload = json.loads(stdout_path.read_text())
        cleanup = payload.get("cleanup") or {}
        attempts = cleanup.get("close_attempts") or []
        if cleanup.get("status") != "closed" or not any(item.get("project_disposed") for item in attempts):
            raise ValueError(f"Cycle {cycle} did not dispose its helper-owned project; evidence retained")
        if payload.get("agent_result", {}).get("verdict") not in ("GREEN", "RED"):
            raise ValueError(f"Cycle {cycle} did not complete native inspection; evidence retained")
        observe(cycle)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-root", type=Path, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--jcmd", type=Path, required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--ide", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cycles", type=int, default=20)
    args = parser.parse_args()
    if args.cycles < 1 or args.cycles > 100:
        parser.error("cycles must be between 1 and 100")
    try:
        run(args)
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
