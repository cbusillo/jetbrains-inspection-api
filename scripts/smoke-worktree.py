#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# ///
"""Own disposable smoke worktrees and retain evidence on every refusal."""

import argparse
import hashlib
import json
import platform
import subprocess
from pathlib import Path

HOST_ROOT = Path("/Volumes/Developer-Artifacts/worktrees")


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def manifest(root):
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if relative == ".git" or relative.startswith(".git/"):
            continue
        if path.is_symlink():
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
    return {
        "root": str(root),
        "source": str(source),
        "head": commit,
        "branch": f"work/{slug}",
        "managed": managed,
        "manager": str(manager),
        "manifest": manifest(root),
    }


def retire(receipt, payload, helper):
    root = Path(receipt["root"])
    if not root.is_dir() or root.is_symlink():
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
    if (
        run("git", "-C", str(root), "status", "--porcelain", "--untracked-files=all")
        or manifest(root) != receipt["manifest"]
    ):
        return {"status": "retained", "reason": "project_changed"}
    gitdir = Path(run("git", "-C", str(root), "rev-parse", "--absolute-git-dir"))
    lock = gitdir / "locked"
    lock_reason = lock.read_text() if lock.exists() else None
    if receipt["managed"]:
        run(receipt["manager"], "retire", "--dry-run", str(root))
    elif lock_reason is not None:
        return {"status": "retained", "reason": "foreign_lock"}
    # The maintained helper proves live SDK/lease safety before non-force removal.
    preview = json.loads(
        run("uv", "run", str(helper), "remove-worktree", "--json", "--repo", str(root))
    )
    if preview.get("status") != "ok" or preview.get("dry_run") is not True:
        return {
            "status": "retained",
            "reason": "sdk_preview_unresolved",
            "preview": preview,
        }
    if (
        manifest(root) != receipt["manifest"]
        or run("git", "-C", str(root), "rev-parse", "HEAD") != receipt["head"]
    ):
        return {
            "status": "retained",
            "reason": "project_changed_during_preview",
            "preview": preview,
        }
    if lock_reason is not None:
        run("git", "-C", receipt["source"], "worktree", "unlock", str(root))
    try:
        applied = json.loads(
            run(
                "uv",
                "run",
                str(helper),
                "remove-worktree",
                "--json",
                "--no-dry-run",
                "--repo",
                str(root),
            )
        )
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
    # No smoke commit was created; delete only the branch still at its original head.
    if (
        run("git", "-C", receipt["source"], "rev-parse", receipt["branch"])
        == receipt["head"]
    ):
        run("git", "-C", receipt["source"], "branch", "-d", receipt["branch"])
    return {"status": "removed", "preview": preview, "apply": applied}


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
            try:
                result = retire(
                    json.loads(args.receipt.read_text()),
                    json.loads(args.payload.read_text()),
                    args.helper,
                )
            except (OSError, ValueError, subprocess.CalledProcessError) as error:
                result = {
                    "status": "retained",
                    "reason": "retirement_failed",
                    "error": str(error),
                }
            args.out.write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result))
            return 0 if result["status"] == "removed" else 1
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(2, f"{error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
