#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Preview or remove one empty dedicated smoke root from a stopped IDE's trust settings."""

import argparse
import importlib.util
import json
import plistlib
import tempfile
from pathlib import Path
from xml.etree import ElementTree


def trust_entries(tree, token):
    selected = []
    for component in tree.getroot().findall("component"):
        if component.get("name") == "Trusted.Paths.Settings":
            for option in component.findall(
                "option[@name='TRUSTED_PATHS']/list/option"
            ):
                if option.get("value") == token:
                    selected.append(
                        (component.find("option[@name='TRUSTED_PATHS']/list"), option)
                    )
        elif component.get("name") == "Trusted.Paths":
            for entry in component.findall(
                "option[@name='TRUSTED_PROJECT_PATHS']/map/entry"
            ):
                if entry.get("key") == token or entry.get("key", "").startswith(
                    token + "/"
                ):
                    selected.append(
                        (
                            component.find("option[@name='TRUSTED_PROJECT_PATHS']/map"),
                            entry,
                        )
                    )
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--smoke-root", type=Path, required=True)
    parser.add_argument("--ide-app", type=Path, required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--artifact-root", type=Path)
    parser.add_argument("--maintenance-window", action="store_true")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location(
        "local_installer", Path(__file__).with_name("install-local-plugin.py")
    )
    installer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(installer)
    helper = installer.load_helper(args.helper)
    root = args.smoke_root.expanduser().resolve()
    if (
        root == Path.home().resolve()
        or len(root.parts) < 4
        or (root.exists() and any(root.iterdir()))
    ):
        parser.error(
            "Select the empty dedicated smoke parent, after retiring every project; never select a shared worktree root."
        )
    if args.apply and (not args.maintenance_window or not args.artifact_root):
        parser.error(
            "--apply requires --maintenance-window and an existing --artifact-root for backup."
        )
    app = args.ide_app.resolve(strict=True)
    selection = helper.resolve_ide_selection({"ide_app": str(app)})
    if (
        not selection
        or not selection.config_dir
        or not selection.app_path
        or selection.app_path.resolve() != app
    ):
        parser.error("Exact app/config identity unavailable.")
    with (app / "Contents/Info.plist").open("rb") as stream:
        executable = (
            app / "Contents/MacOS" / plistlib.load(stream)["CFBundleExecutable"]
        )
    with helper.outcome_routing_lock(), helper.lifecycle_lock():
        for _, lease in helper.read_local_leases():
            path = lease.get("lifecycle_target_path") or lease.get("worktree_root")
            if path and Path(path).resolve().is_relative_to(root):
                parser.error(
                    "A smoke lease remains; reconcile it through the maintained helper first."
                )
        path = selection.config_dir / "options/trusted-paths.xml"
        if path.is_symlink():
            parser.error("Trust settings must not be a symlink.")
        if not path.exists():
            print(json.dumps({"status": "absent", "root": str(root)}))
            return
        before = path.read_bytes()
        tree = ElementTree.ElementTree(ElementTree.fromstring(before))
        entries = trust_entries(tree, helper.trust_path_token(root))
        report = {
            "status": "preview",
            "root": str(root),
            "config": str(path),
            "entry_count": len(entries),
        }
        if args.apply and entries:
            installer.wait_no_helpers(120)
            installer.require_stopped(executable)
            backup_dir = Path(
                tempfile.mkdtemp(
                    prefix="smoke-trust-", dir=args.artifact_root.resolve(strict=True)
                )
            )
            backup = backup_dir / path.name
            backup.write_bytes(before)
            for parent, entry in entries:
                parent.remove(entry)
            installer.require_stopped(executable)
            if path.read_bytes() != before or (root.exists() and any(root.iterdir())):
                parser.error(
                    "Trust settings or smoke root changed; preserve the backup and retry after settling."
                )
            staged = path.with_name(path.name + ".smoke-retirement")
            with staged.open("xb") as stream:
                tree.write(stream, encoding="utf-8")
            staged.replace(path)
            report.update({"status": "removed", "backup": str(backup)})
        print(json.dumps(report))


if __name__ == "__main__":
    main()
