#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Install a verified local payload within an explicitly coordinated IDE window."""

import argparse
import hashlib
import importlib.util
import json
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path

PLUGIN = "jetbrains-inspection-api"


@contextmanager
def helper_errors(helper):
    try:
        yield
    except helper.InspectError as error:
        raise ValueError(
            f"Inspection helper could not establish the maintenance window: {error}"
        ) from error


def load_helper(path):
    spec = importlib.util.spec_from_file_location("local_inspection_helper", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def app_config_dir(app, helper):
    candidate = helper.ide_app_candidate(app)
    if candidate is None:
        raise ValueError("The exact app is not a supported JetBrains bundle.")
    metadata = json.loads((app / "Contents/Resources/product-info.json").read_text())
    product = helper.IDE_PRODUCTS[candidate.product_key]
    selector = metadata.get("dataDirectoryName", "")
    if (
        metadata.get("productCode") not in product.product_codes
        or not re.fullmatch(r"[A-Za-z0-9._-]+", selector)
        or not selector.startswith(product.config_prefixes)
    ):
        raise ValueError(
            "Bundle metadata does not prove the product's config selector."
        )
    config = Path.home() / "Library/Application Support/JetBrains" / selector
    if not (config / "options").is_dir():
        raise ValueError(
            "Start the exact app once to create its configuration, then quit normally."
        )
    return config


def require_idle_status(status):
    raw = status.get("raw")
    if (
        not isinstance(raw, dict)
        or any(
            raw.get(field) is not False
            for field in ("indexing", "is_scanning", "inspection_in_progress")
        )
        or any(
            status.get(field)
            for field in ("session_drift", "ambiguous", "unavailable", "timed_out")
        )
    ):
        raise ValueError("An exact target project is busy or its idleness is unproven.")


def require_no_target_leases(leases, identities, app):
    sessions = {
        identity.get("session_id")
        for identity in identities
        if identity.get("session_id")
    }
    pids = {identity.get("pid") for identity in identities if identity.get("pid")}
    projects = {
        project.get("base_path")
        for identity in identities
        for project in identity.get("open_projects") or []
        if project.get("base_path")
    }
    for _, lease in leases:
        route = lease.get("route") or {}
        path = lease.get("lifecycle_target_path") or lease.get("worktree_root")
        leased_app = lease.get("ide_app_path")
        if (
            lease.get("session_id") in sessions
            or route.get("ide", {}).get("pid") in pids
            or path in projects
            or (leased_app and Path(leased_app).resolve() == app.resolve())
        ):
            raise ValueError(
                "An exact target IDE helper lease remains; reconcile it through the maintained helper before installation."
            )


def inventory(root):
    result = {}
    if root.is_symlink() or not root.is_dir():
        raise ValueError(f"Payload is not a plain directory: {root}")
    for path in sorted(root.rglob("*")):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError(f"Unsupported payload entry: {path}")
        result[path.relative_to(root).as_posix()] = (
            hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        )
    return result


def stage_archive(archive, destination, source_sha):
    if not re.fullmatch("[0-9a-f]{40}", source_sha):
        raise ValueError("Require the full source SHA.")
    with zipfile.ZipFile(archive) as zipped:
        names = set()
        for item in zipped.infolist():
            path = Path(item.filename)
            if (
                path.is_absolute()
                or ".." in path.parts
                or not path.parts
                or path.parts[0] != PLUGIN
                or item.filename in names
                or (item.external_attr >> 16) & 0o170000 == 0o120000
            ):
                raise ValueError(f"Unsafe or duplicate archive member: {item.filename}")
            names.add(item.filename)
        zipped.extractall(destination)
    payload = destination / PLUGIN
    proofs = []
    for jar in payload.rglob("*.jar"):
        with zipfile.ZipFile(jar) as zipped:
            name = "com/shiny/inspectionmcp/inspection-build.properties"
            if name in zipped.namelist():
                values = dict(
                    line.split("=", 1)
                    for line in zipped.read(name).decode().splitlines()
                    if "=" in line and not line.startswith("#")
                )
                proofs.append(values)
    if len(proofs) != 1 or any(
        proofs[0].get(key) != expected
        for key, expected in {
            "plugin.build.commit": source_sha,
            "plugin.build.dirty": "false",
            "plugin.build.fingerprint": source_sha + "-clean",
        }.items()
    ):
        raise ValueError("Archive does not prove the exact clean source build.")
    inventory(payload)
    return payload


def process_inventory():
    return subprocess.check_output(
        ["ps", "-axww", "-o", "pid=", "-o", "command="], text=True
    )


def active_helpers(processes):
    for row in processes.splitlines():
        fields = row.strip().split(None, 1)
        if len(fields) != 2:
            continue
        try:
            args = shlex.split(fields[1])
        except ValueError:
            return True
        if not args or not re.fullmatch(r"(?:uv|python[0-9.]*)", Path(args[0]).name):
            continue
        # The first script belongs to the interpreter; later --helper arguments do not.
        scripts = [arg for arg in args[1:] if arg.endswith(".py")]
        if scripts and Path(scripts[0]).name == "jb-inspect.py":
            return True
    return False


def main_processes(processes, executable):
    result = []
    for row in processes.splitlines():
        fields = row.strip().split(None, 1)
        if len(fields) != 2:
            continue
        # Wide command output preserves the complete bundle path, including spaces.
        if fields[1].startswith(str(executable) + " ") or fields[1] == str(executable):
            result.append(int(fields[0]))
    return result


def wait_no_helpers(timeout):
    deadline = time.monotonic() + timeout
    while active_helpers(process_inventory()):
        if time.monotonic() >= deadline:
            raise ValueError("Inspection helper remains active; installation aborted.")
        time.sleep(1)


def running_apps():
    script = """ObjC.import('AppKit');
var apps = $.NSWorkspace.sharedWorkspace.runningApplications, rows = [];
for (var i = 0; i < apps.count; i++) {
    var app = apps.objectAtIndex(i), path = ObjC.unwrap(app.bundleURL.path);
    if (path) rows.push({pid: Number(app.processIdentifier), path: path});
}
JSON.stringify(rows);"""
    rows = json.loads(
        subprocess.check_output(
            ["osascript", "-l", "JavaScript", "-e", script], text=True, timeout=10
        )
    )
    if not isinstance(rows, list) or any(
        not isinstance(row, dict)
        or not isinstance(row.get("pid"), int)
        or not isinstance(row.get("path"), str)
        for row in rows
    ):
        raise ValueError("Native running-app inventory is unproven.")
    return rows


def app_is_running(app):
    return any(Path(row["path"]).resolve() == app.resolve() for row in running_apps())


def require_no_config_peer(app):
    metadata_path = app / "Contents/Resources/product-info.json"
    if not metadata_path.is_file():
        raise ValueError("Target bundle config selector is unproven.")
    selector = json.loads(metadata_path.read_text()).get("dataDirectoryName")
    if not selector:
        raise ValueError("Target bundle config selector is unproven.")
    for row in running_apps():
        peer = Path(row["path"]).resolve()
        if peer == app.resolve():
            continue
        metadata = peer / "Contents/Resources/product-info.json"
        if (
            metadata.is_file()
            and json.loads(metadata.read_text()).get("dataDirectoryName") == selector
        ):
            raise ValueError(
                f"Another running IDE shares the target config; quit it normally first: {peer}"
            )


def require_stopped(executable):
    if main_processes(process_inventory(), executable):
        raise ValueError("Exact target IDE is still running; payload retained.")
    app = executable.parents[2]
    if app_is_running(app):
        raise ValueError("Exact target IDE is still running; payload retained.")
    require_no_config_peer(app)


def quit_normally(app, executable, timeout):
    require_no_config_peer(app)
    pids = main_processes(process_inventory(), executable)
    native = [
        row["pid"]
        for row in running_apps()
        if Path(row["path"]).resolve() == app.resolve()
    ]
    if len(pids) > 1 or pids != native:
        raise ValueError(
            "Exact target process and native bundle identity disagree; quit normally by hand."
        )
    if not pids:
        return
    script = """ObjC.import('AppKit');
function run(argv) {
    var app = $.NSRunningApplication.runningApplicationWithProcessIdentifier(Number(argv[0]));
    if (!app || ObjC.unwrap(app.bundleURL.path) !== argv[1]) throw Error('Target PID or bundle changed');
    if (!app.terminate) throw Error('Normal quit request was refused');
}"""
    subprocess.run(
        ["osascript", "-l", "JavaScript", "-e", script, str(pids[0]), str(app)],
        check=True,
        timeout=timeout,
    )
    deadline = time.monotonic() + timeout
    while main_processes(process_inventory(), executable) or app_is_running(app):
        if time.monotonic() >= deadline:
            raise ValueError(
                "Normal quit unresolved (possibly unsaved documents or a modal); install aborted."
            )
        time.sleep(1)


def replace_payload(candidate, target, evidence, executable):
    if target.is_symlink():
        raise ValueError("Installed payload must not be a symlink.")
    candidate_manifest = inventory(candidate)
    prior_manifest = inventory(target) if target.exists() else None
    (evidence / "candidate-manifest.json").write_text(
        json.dumps(candidate_manifest, indent=2)
    )
    (evidence / "rollback-manifest.json").write_text(
        json.dumps(prior_manifest, indent=2)
    )
    if prior_manifest is not None:
        shutil.copytree(target, evidence / "rollback")
        if inventory(evidence / "rollback") != prior_manifest:
            raise ValueError("Rollback copy verification failed.")
    target.parent.mkdir(parents=True, exist_ok=True)
    sibling = Path(
        tempfile.mkdtemp(prefix=".inspection-candidate-", dir=target.parent.parent)
    )
    (evidence / "staging.json").write_text(
        json.dumps(
            {
                "staging": str(sibling),
                "previous": str(sibling / "previous"),
                "target": str(target),
            },
            indent=2,
        )
        + "\n"
    )
    print(
        f"Preserved staging and rollback paths: {evidence / 'staging.json'}",
        file=sys.stderr,
    )
    if (evidence / ".retain-until").is_file():
        shutil.copy2(evidence / ".retain-until", sibling / ".retain-until")
    staged = sibling / PLUGIN
    previous = sibling / "previous"
    shutil.copytree(candidate, staged)
    if inventory(staged) != candidate_manifest:
        raise ValueError("Candidate copy verification failed.")
    require_stopped(executable)
    if prior_manifest is not None and inventory(target) != prior_manifest:
        raise ValueError("Installed payload changed; replacement aborted.")
    moved_previous = False
    moved_candidate = False
    try:
        if prior_manifest is not None:
            target.rename(previous)
            moved_previous = True
        require_stopped(executable)
        staged.rename(target)
        moved_candidate = True
        require_stopped(executable)
        if inventory(target) != candidate_manifest:
            raise ValueError("Installed candidate verification failed.")
    except BaseException:
        # If an IDE restarted, keep both copies and require a new stopped window.
        require_stopped(executable)
        if moved_candidate:
            target.rename(sibling / "failed-candidate")
        if moved_previous:
            previous.rename(target)
        if prior_manifest is not None and inventory(target) != prior_manifest:
            raise ValueError("Rollback verification failed; retain all evidence.")
        raise
    return {
        "status": "installed",
        "rollback": str(evidence / "rollback"),
        "preserved_previous": str(previous) if moved_previous else None,
        "staging": str(sibling),
    }


def install(args):
    if sys.platform != "darwin":
        raise ValueError(
            "Local installer supports macOS; portable smoke uses the inspection helper."
        )
    app = args.ide_app.resolve(strict=True)
    with (app / "Contents/Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    executable = app / "Contents/MacOS" / info["CFBundleExecutable"]
    executable.resolve(strict=True)
    helper = load_helper(args.helper.resolve(strict=True))
    plugins = app_config_dir(app, helper).resolve() / "plugins"
    if args.plugin_dir and args.plugin_dir.resolve() != plugins:
        raise ValueError(
            "Plugin directory does not match the selected app configuration."
        )
    if plugins.is_symlink():
        raise ValueError("Plugin directory must not be redirected.")
    artifact_root = args.artifact_root.resolve(strict=True)
    if artifact_root == plugins or artifact_root.is_relative_to(plugins):
        raise ValueError("Keep evidence outside installed plugins.")
    # On Chris-Studio an existing validated task worktree supplies the artifact volume identity.
    import platform

    if platform.node().split(".")[0].lower() == "chris-studio":
        root = Path("/Volumes/Developer-Artifacts")
        if not artifact_root.is_relative_to(root) or not (root / "worktrees").is_dir():
            raise ValueError(
                "Use the host-approved, mounted Developer-Artifacts evidence root."
            )
        manager = Path.home() / ".local/bin/dev-worktree"
        subprocess.run(
            [str(manager), "retire", "--dry-run", str(args.repo.resolve())], check=True
        )
    evidence = Path(tempfile.mkdtemp(prefix="local-plugin-", dir=artifact_root))
    (evidence / ".retain-until").write_text(
        str(datetime.now(UTC).date() + timedelta(days=7)) + "\n"
    )
    print(f"Retained installation evidence: {evidence}", flush=True)
    candidate = stage_archive(args.archive, evidence, args.source_sha)
    # Do not depend on any ambient branch/version: check live provenance on the acting path.
    actual = subprocess.check_output(
        ["git", "-C", str(args.repo), "rev-parse", "HEAD"], text=True
    ).strip()
    dirty = subprocess.check_output(
        ["git", "-C", str(args.repo), "status", "--porcelain"], text=True
    ).strip()
    if actual != args.source_sha or dirty:
        raise ValueError("Build source must be the exact clean source checkout.")
    wait_no_helpers(args.timeout)
    with (
        helper_errors(helper),
        helper.outcome_routing_lock(args.timeout * 1000),
        helper.lifecycle_lock(args.timeout * 1000),
    ):
        wait_no_helpers(args.timeout)
        identities = helper.discover_identities(None)
        pids = main_processes(process_inventory(), executable)
        targets = [identity for identity in identities if identity.get("pid") in pids]
        if pids and len(targets) != 1:
            raise ValueError("Cannot prove plugin identity for the exact running IDE.")
        require_no_target_leases(helper.read_local_leases(), targets, app)
        for identity in targets:
            for project in identity.get("open_projects") or []:
                if not project.get("base_path"):
                    raise ValueError("An open project has no exact route identity.")
                status_args = helper.parse_cli_args(
                    helper.build_parser(),
                    [
                        "get-status",
                        "--repo",
                        project["base_path"],
                        "--session-id",
                        identity["session_id"],
                        "--port",
                        str(identity["port"]),
                        "--json",
                    ],
                )
                status_args.client_run_id = "local-plugin-maintenance"
                status = helper.command_status(
                    status_args, helper.build_context(status_args)
                )
                # The outer operation holds outcome routing; avoid a second process's emit/log lock.
                require_idle_status(status)
        quit_normally(app, executable, args.timeout)
        require_stopped(executable)
        receipt = replace_payload(candidate, plugins / PLUGIN, evidence, executable)
        receipt.update(
            {
                "source_sha": args.source_sha,
                "app": str(app),
                "acceptance": "pending_runtime_smokes",
            }
        )
        (evidence / "installation.json").write_text(
            json.dumps(receipt, indent=2) + "\n"
        )
        print(json.dumps(receipt))
    # Preserve a stopped target; relaunch only an app this operation normally quit.
    if pids:
        subprocess.run(["open", "-a", str(app)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument(
        "--ide-app", type=Path, required=True, help="Exact .app bundle path"
    )
    parser.add_argument("--plugin-dir", type=Path)
    parser.add_argument(
        "--artifact-root",
        type=Path,
        required=True,
        help="Existing host-approved evidence directory",
    )
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument(
        "--maintenance-window",
        action="store_true",
        required=True,
        help="All helper, MCP and HTTP callers of this IDE are paused for the entire operation",
    )
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    try:
        install(args)
    except (
        OSError,
        ValueError,
        subprocess.SubprocessError,
        zipfile.BadZipFile,
    ) as error:
        parser.exit(
            1,
            f"Installation stopped; preserve the candidate and rollback evidence: {error}\n",
        )


if __name__ == "__main__":
    main()
