#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# ///
"""Own disposable smoke worktrees and retain evidence on every refusal."""

import argparse
import hashlib
import json
import platform
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

HOST_ROOT = Path("/Volumes/Developer-Artifacts/worktrees")


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def manifest(root):
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if relative == ".git" or relative.startswith(".git/"):
            continue
        if path.name.startswith(".env") and path.name != ".env.example":
            result[relative] = {"protected": True}
        elif path.is_symlink():
            result[relative] = {"link": str(path.readlink())}
        elif path.is_file():
            result[relative] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        elif path.is_dir():
            result[relative] = {"directory": True}
        else:
            raise ValueError(f"Unsupported file: {path}")
    return result


def create(source, slug, parent, manager):
    source = source.resolve(strict=True)
    commit = run("git", "-C", str(source), "rev-parse", "--verify", "HEAD^{commit}")
    if platform.node().split(".")[0].lower() == "chris-studio":
        if parent:
            raise ValueError(
                "Chris-Studio worktrees use dev-worktree routing; omit --worktree-root/--work-root."
            )
        created = run(str(manager), str(source), slug, commit).splitlines()[-1]
        root = Path(created).resolve(strict=True)
        if root.parent.parent != HOST_ROOT or root.name != slug:
            raise ValueError(
                "Worktree manager returned a path outside the approved root; retain it."
            )
        managed = True
    else:
        if not parent:
            raise ValueError(
                "Portable hosts require an explicit --worktree-root/--work-root."
            )
        parent = parent.resolve()
        parent.mkdir(parents=True, exist_ok=True)
        root = parent / slug
        run(
            "git",
            "-C",
            str(source),
            "worktree",
            "add",
            "-b",
            f"work/{slug}",
            str(root),
            commit,
        )
        managed = False
    try:
        return {
            "root": str(root),
            "source": str(source),
            "head": commit,
            "branch": f"work/{slug}",
            "managed": managed,
            "manager": str(manager),
            "root_device": root.stat().st_dev,
            "root_inode": root.stat().st_ino,
            "common_git_dir": run(
                "git",
                "-C",
                str(root),
                "rev-parse",
                "--path-format=absolute",
                "--git-common-dir",
            ),
            "manifest": manifest(root),
        }

    except BaseException:
        print(
            f"Created worktree retained without a complete receipt: {root}; branch work/{slug}; head {commit}",
            file=sys.stderr,
        )
        raise


def generated_path(relative, prepared_roots=()):
    parts = Path(relative).parts
    if any(
        part.startswith(".env")
        or part in {"id_rsa", "id_ed25519"}
        or part.endswith((".key", ".p12", ".pfx"))
        for part in parts
    ):
        return False
    if any(Path(relative).is_relative_to(path) for path in prepared_roots):
        return True
    if ".idea" in parts:
        suffix = parts[parts.index(".idea") + 1 :]
        return (
            not suffix
            or (len(suffix) == 1 and suffix[0].endswith(".iml"))
            or suffix[0]
            in {
                "workspace.xml",
                "editor.xml",
                ".name",
                "kotlinc.xml",
                "misc.xml",
                "modules.xml",
                "vcs.xml",
            }
        )
    return bool(parts) and any(
        part in {".gradle", ".kotlin", "build", "out", ".intellijPlatform"}
        for part in parts
    )


def prepared_generated_roots(payload, root):
    state = (
        payload.get("repository_preparation")
        or payload.get("agent_result", {}).get("repository_preparation")
        or payload.get("prepared", {}).get("repository_preparation")
        or {}
    )
    if (
        not isinstance(state, dict)
        or state.get("configured") is not True
        or state.get("execution_state") not in {"succeeded", "reused"}
    ):
        return []
    target = state.get("target_worktree")
    if not isinstance(target, str) or not Path(target).resolve().is_relative_to(
        root.resolve()
    ):
        return []
    return [
        (Path(target).resolve() / name).relative_to(root.resolve())
        for name in state.get("required_generated_state", [])
        if name == ".venv"
    ]


def preserve_generated_state(root, before, current, evidence, prepared_roots=()):
    changed = [
        name
        for name in set(before) | set(current)
        if before.get(name) != current.get(name)
    ]
    # Unknown ignored data remains in the exact project. Only known generated files can be archived.
    if any(not generated_path(name, prepared_roots) for name in changed):
        return None
    if evidence.resolve().is_relative_to(root.resolve()):
        raise ValueError("Preservation evidence must be outside the project.")
    archive = Path(tempfile.mkdtemp(prefix="generated-state-", dir=evidence))
    for name in changed:
        value = current.get(name)
        if value is None:
            continue
        source = root / name
        target = archive / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if "link" in value:
            target.symlink_to(source.readlink())
        elif "directory" in value:
            target.mkdir(exist_ok=True)
        else:
            shutil.copy2(source, target)
    archived = manifest(archive)
    if any(
        archived.get(name) != value
        for name, value in current.items()
        if name in changed
    ):
        raise ValueError(
            "Generated-state preservation did not verify; retain the project."
        )
    (evidence / (archive.name + "-manifest.json")).write_text(
        json.dumps({name: current.get(name) for name in changed}, indent=2)
    )
    return str(archive)


def sdk_removal_result(helper, root, evidence, apply=False):
    args = ["uv", "run", str(helper), "remove-worktree", "--json"]
    if apply:
        args.append("--no-dry-run")
    output = run(*args, "--repo", str(root))
    record = evidence / ("sdk-apply-stdout.txt" if apply else "sdk-preview-stdout.txt")
    record.write_text(output)
    record.chmod(0o600)
    return json.loads(output)


def retire(receipt, payload, helper, evidence):
    root = Path(receipt["root"])
    if not root.is_dir() or root.is_symlink():
        return {"status": "retained", "reason": "worktree_identity_changed"}
    current_identity = root.stat()
    if (
        current_identity.st_dev != receipt.get("root_device")
        or current_identity.st_ino != receipt.get("root_inode")
        or run(
            "git",
            "-C",
            str(root),
            "rev-parse",
            "--path-format=absolute",
            "--git-common-dir",
        )
        != receipt.get("common_git_dir")
    ):
        return {"status": "retained", "reason": "worktree_identity_changed"}
    if (
        not isinstance(payload, dict)
        or not isinstance(payload.get("cleanup"), dict)
        or payload["cleanup"].get("status") != "closed"
    ):
        return {"status": "retained", "reason": "lifecycle_cleanup_unresolved"}
    if (
        run("git", "-C", str(root), "rev-parse", "--show-toplevel") != str(root)
        or run("git", "-C", str(root), "rev-parse", "HEAD") != receipt["head"]
        or run("git", "-C", str(root), "branch", "--show-current") != receipt["branch"]
    ):
        return {"status": "retained", "reason": "worktree_identity_changed"}
    if run("git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"):
        return {"status": "retained", "reason": "project_changed"}
    current = manifest(root)
    tracked = set(run("git", "-C", str(root), "ls-files", "-z").split("\0"))
    if any(
        value.get("protected") and name not in tracked
        for name, value in current.items()
    ):
        return {"status": "retained", "reason": "private_configuration"}
    archive = None
    if current != receipt["manifest"]:
        archive = preserve_generated_state(
            root,
            receipt["manifest"],
            current,
            evidence,
            prepared_generated_roots(payload, root),
        )
        if archive is None:
            return {"status": "retained", "reason": "project_changed"}
    gitdir = Path(run("git", "-C", str(root), "rev-parse", "--absolute-git-dir"))
    lock = gitdir / "locked"
    lock_reason = lock.read_text() if lock.exists() else None
    if receipt["managed"]:
        run(receipt["manager"], "retire", "--dry-run", str(root))
    elif lock_reason is not None:
        return {"status": "retained", "reason": "foreign_lock"}
    # The maintained helper proves live SDK/lease safety before non-force removal.
    preview = sdk_removal_result(helper, root, evidence)
    if preview.get("status") != "ok" or preview.get("dry_run") is not True:
        return {
            "status": "retained",
            "reason": "sdk_preview_unresolved",
            "preview": preview,
        }
    if (
        manifest(root) != current
        or run("git", "-C", str(root), "rev-parse", "HEAD") != receipt["head"]
    ):
        return {
            "status": "retained",
            "reason": "project_changed_during_preview",
            "preview": preview,
        }
    # Defer ordinary termination until removal settles and the original lock is restored.
    previous_mask = signal.pthread_sigmask(
        signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGINT}
    )
    try:
        if lock_reason is not None:
            run("git", "-C", receipt["source"], "worktree", "unlock", str(root))
    except BaseException:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        raise
    try:
        applied = sdk_removal_result(helper, root, evidence, apply=True)
        if (
            applied.get("status") != "ok"
            or applied.get("worktree_removed") is not True
            or root.exists()
        ):
            return {
                "status": "retained",
                "reason": "sdk_removal_unresolved",
                "preview": preview,
                "apply": applied,
            }
    finally:
        try:
            if (
                lock_reason is not None
                and root.exists()
                and gitdir.exists()
                and not lock.exists()
            ):
                run(
                    "git",
                    "-C",
                    receipt["source"],
                    "worktree",
                    "lock",
                    "--reason",
                    lock_reason,
                    str(root),
                )
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
    # No smoke commit was created; delete only the branch still at its original head.
    branch_cleanup = {"status": "not_needed"}
    try:
        if (
            run("git", "-C", receipt["source"], "rev-parse", receipt["branch"])
            == receipt["head"]
        ):
            run("git", "-C", receipt["source"], "branch", "-d", receipt["branch"])
            branch_cleanup = {"status": "removed"}
    except subprocess.CalledProcessError as error:
        branch_cleanup = {
            "status": "unproven",
            "branch": receipt["branch"],
            "error": str(error),
        }
    return {
        "status": "removed",
        "preview": preview,
        "apply": applied,
        "generated_state_archive": archive,
        "branch_cleanup": branch_cleanup,
    }


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    new = commands.add_parser("create")
    new.add_argument("--source", type=Path, required=True)
    new.add_argument("--slug", required=True)
    new.add_argument("--parent", type=Path)
    new.add_argument(
        "--manager", type=Path, default=Path.home() / ".local/bin/dev-worktree"
    )
    new.add_argument("--receipt", type=Path, required=True)
    delete = commands.add_parser("retire")
    delete.add_argument("--receipt", type=Path, required=True)
    delete.add_argument("--payload", type=Path, required=True)
    delete.add_argument("--helper", type=Path, required=True)
    delete.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "create":
            receipt = create(args.source, args.slug, args.parent, args.manager)
            args.receipt.write_text(json.dumps(receipt, indent=2) + "\n")
            print(receipt["root"])
        else:
            receipt = None
            try:
                receipt = json.loads(args.receipt.read_text())
                result = retire(
                    receipt,
                    json.loads(args.payload.read_text()),
                    args.helper,
                    args.receipt.parent,
                )
            except (OSError, ValueError, subprocess.CalledProcessError) as error:
                result = {
                    "status": "retained",
                    "reason": "retirement_failed",
                    "error": str(error),
                    "worktree_present": (
                        Path(receipt["root"]).exists()
                        if isinstance(receipt, dict)
                        and isinstance(receipt.get("root"), str)
                        else None
                    ),
                }
                if isinstance(error, subprocess.CalledProcessError):
                    records = Path(
                        tempfile.mkdtemp(
                            prefix="retirement-failure-", dir=args.receipt.parent
                        )
                    )
                    for name, content in [
                        ("stdout", error.stdout),
                        ("stderr", error.stderr),
                    ]:
                        record = records / (name + ".txt")
                        record.write_text(content or "")
                        record.chmod(0o600)
                    result["failure_evidence"] = str(records)
            args.out.write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result))
            return 0 if result["status"] == "removed" else 1
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(2, f"{error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
