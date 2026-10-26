#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
import argparse
import html
import os
import subprocess
import sys
import tempfile
from xml.etree import ElementTree
from pathlib import Path


def sdk_name_for(venv: Path, home: Path) -> str:
    try:
        return f"~/{venv.relative_to(home).as_posix()}"
    except ValueError:
        return str(venv)


def registered_sdk_name(venv: Path, sdk_table: Path | None) -> str | None:
    if sdk_table is None or not sdk_table.exists():
        return None
    try:
        root = ElementTree.parse(sdk_table).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise RuntimeError("Cannot read the selected IDE's Python SDK table") from error
    interpreter = os.path.normpath(str(venv / "bin" / "python"))
    matches = set()
    for sdk in root.findall("./component[@name='ProjectJdkTable']/jdk"):
        sdk_type, sdk_home, sdk_name = sdk.find("type"), sdk.find("homePath"), sdk.find("name")
        if sdk_type is None or sdk_home is None or sdk_name is None:
            continue
        if sdk_type.get("value") != "Python SDK":
            continue
        home = sdk_home.get("value", "").replace("$USER_HOME$", str(Path.home()))
        home = os.path.normpath(os.path.expanduser(home))
        if home == interpreter and sdk_name.get("value"):
            matches.add(sdk_name.get("value"))
    if len(matches) > 1:
        raise RuntimeError("Multiple Python SDK names match this worktree interpreter in the selected IDE")
    return next(iter(matches), None)


def render_project_files(
    repo: Path, module_name: str, test_roots: list[str], sdk_name: str | None = None,
    generated_roots: list[str] | None = None,
) -> dict[Path, str]:
    idea = repo / ".idea"
    sdk_name = html.escape(sdk_name or sdk_name_for(repo / ".venv", Path.home()))
    safe_module_name = "".join(character if character.isalnum() or character in "-_" else "-" for character in module_name)
    module_file = idea / f"{safe_module_name}.iml"
    source_folders = "\n".join(
        f'      <sourceFolder url="file://$PROJECT_DIR$/{html.escape(root)}" isTestSource="true" />'
        for root in test_roots
    )
    extra_exclusions = "\n".join(
        f'      <excludeFolder url="file://$PROJECT_DIR$/{html.escape(root)}" />'
        for root in (generated_roots or [])
    )
    module_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<module type="PYTHON_MODULE" version="4">
  <component name="NewModuleRootManager">
    <content url="file://$PROJECT_DIR$">
      <excludeFolder url="file://$PROJECT_DIR$/.mypy_cache" />
      <excludeFolder url="file://$PROJECT_DIR$/.system" />
      <excludeFolder url="file://$PROJECT_DIR$/.local" />
      <excludeFolder url="file://$PROJECT_DIR$/.code" />
      <excludeFolder url="file://$PROJECT_DIR$/.venv" />
{extra_exclusions}
{source_folders}
    </content>
    <orderEntry type="jdk" jdkName="{sdk_name}" jdkType="Python SDK" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
'''
    modules_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://$PROJECT_DIR$/.idea/{safe_module_name}.iml" filepath="$PROJECT_DIR$/.idea/{safe_module_name}.iml" />
    </modules>
  </component>
</project>
'''
    misc_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectRootManager" version="2" project-jdk-name="{sdk_name}" project-jdk-type="Python SDK" />
  <component name="PythonCompatibilityInspectionAdvertiser">
    <option name="version" value="3" />
  </component>
</project>
'''
    return {module_file: module_xml, idea / "modules.xml": modules_xml, idea / "misc.xml": misc_xml}


GENERATED_INDEX_ROOTS = (".git", ".gradle", "node_modules", "build", "dist", "target",
                         ".pytest_cache", ".ruff_cache", "__pycache__")


def ignored_generated_roots(repo: Path, test_roots: list[str]) -> list[str]:
    declared = [(repo / root).resolve() for root in test_roots]
    excluded = []
    for name in GENERATED_INDEX_ROOTS:
        if any(root.is_relative_to((repo / name).resolve()) for root in declared):
            continue
        tracked = subprocess.run(["git", "-C", str(repo), "ls-files", "--", name],
                                 capture_output=True, text=True, check=True).stdout
        if tracked:
            continue
        probe = name if (repo / name).is_symlink() else name + "/"
        ignored = subprocess.run(["git", "-C", str(repo), "check-ignore", "--quiet", probe],
                                 capture_output=True, text=True)
        if ignored.returncode not in (0, 1):
            raise RuntimeError("Cannot establish ignored generated indexing roots")
        if name == ".git" or ignored.returncode == 0:
            excluded.append(name)
    return excluded


def atomic_write(path: Path, content: str) -> None:
    if path.exists() and path.read_text(encoding="utf-8") == content:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            handle.write(content)
            temporary = Path(handle.name)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def ensure_ignored_outputs(repo: Path, paths: list[Path]) -> None:
    relative_paths = [str(path.relative_to(repo)) for path in paths]
    for path, relative_path in zip(paths, relative_paths, strict=True):
        tracked = subprocess.run(
            ["git", "-C", str(repo), "ls-files", "--error-unmatch", "--", relative_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if tracked.returncode == 0:
            raise RuntimeError(f"preparation output must not be tracked: {relative_path}")
        ignore_probe = f"{relative_path}/.prepare-check" if path.name == ".venv" else relative_path
        ignored = subprocess.run(
            ["git", "-C", str(repo), "check-ignore", "--no-index", "-q", "--", ignore_probe],
        )
        if ignored.returncode != 0:
            raise RuntimeError(f"preparation output is not ignored: {relative_path}")


def validate_existing_venv(venv: Path, requested_python: str) -> None:
    if not venv.exists():
        return
    interpreter = venv / "bin" / "python"
    if not interpreter.is_file():
        raise RuntimeError(f"existing virtual environment is incomplete: {venv}")
    result = subprocess.run(
        [str(interpreter), "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"],
        check=True,
        capture_output=True,
        text=True,
    )
    if result.stdout.strip() != requested_python:
        raise RuntimeError(
            f"existing virtual environment uses Python {result.stdout.strip()}, expected {requested_python}"
        )


def build_sync_command(python: str, extras: list[str]) -> list[str]:
    command = ["uv", "sync", "--locked", "--python", python]
    for extra in extras:
        command.extend(("--extra", extra))
    return command


def script_paths(repo: Path, scripts: list[str]) -> list[str]:
    paths = []
    for script in scripts:
        path = (repo / script).resolve()
        if Path(script).is_absolute() or ".." in Path(script).parts or not path.is_relative_to(repo):
            raise RuntimeError(f"PEP 723 script must stay inside the worktree: {script}")
        if path.suffix != ".py" or not path.is_file():
            raise RuntimeError(f"PEP 723 script must be an existing Python file: {script}")
        paths.append(str(path))
    return paths


def prepare_script_environment(repo: Path, python: str, scripts: list[str]) -> None:
    paths = script_paths(repo, scripts)
    with tempfile.TemporaryDirectory(prefix="inspection-pep723-") as temporary:
        for index, path in enumerate(paths):
            if Path(path).name == "setup.py":
                script_input = Path(temporary) / f"pep723-{index}.py"
                script_input.write_bytes(Path(path).read_bytes())
                paths[index] = str(script_input)
        resolved = Path(temporary) / "requirements.txt"
        result = subprocess.run(
            ["uv", "pip", "compile", "--python-version", python, "--no-header",
             "--output-file", str(resolved), *paths],
            cwd=repo, capture_output=True, text=True,
        )
        if result.returncode:
            raise RuntimeError(
                f"PEP 723 inspection dependencies could not be resolved ({', '.join(scripts)}). "
                "The inspection SDK was not changed. uv diagnostic:\n" + result.stderr
            )
        validate_existing_venv(repo / ".venv", python)
        if not (repo / ".venv").exists():
            subprocess.run(["uv", "venv", "--python", python, ".venv"], cwd=repo, check=True)
        subprocess.run(
            ["uv", "pip", "sync", "--python", str(repo / ".venv/bin/python"),
             "--allow-empty-requirements", str(resolved)], cwd=repo, check=True,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare a worktree-local PyCharm project model.")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--python", default="3.12")
    parser.add_argument("--module-name", required=True)
    parser.add_argument("--test-root", action="append", default=[])
    parser.add_argument("--sync", action="store_true")
    parser.add_argument("--extra", action="append", default=[])
    parser.add_argument("--pep723-script", action="append", default=[],
                        help="Read inspection dependencies from this worktree-relative script (repeatable).")
    parser.add_argument("--sdk-table", type=Path, help="Read SDK names from the selected IDE's options/jdk.table.xml.")
    parser.add_argument("--no-project-model", action="store_true",
                        help="Prepare only .venv; a headless engine builds its own model and SDK from it.")
    args = parser.parse_args()

    repo = Path(args.repo).expanduser().resolve()
    git_check = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "--git-dir"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if git_check.returncode != 0:
        parser.error(f"not a Git worktree: {repo}")
    for test_root in args.test_root:
        if not (repo / test_root).is_dir():
            parser.error(f"test root does not exist: {test_root}")

    if args.pep723_script and args.sync:
        parser.error("--pep723-script and --sync are separate dependency sources")
    if args.extra and not args.sync:
        parser.error("--extra requires --sync")
    if args.sync and not (repo / "pyproject.toml").is_file():
        parser.error("--sync requires pyproject.toml in the repository root")
    sdk_name = registered_sdk_name(repo / ".venv", args.sdk_table)
    project_files: dict[Path, str] = {}
    if not args.no_project_model:
        generated_roots = ignored_generated_roots(repo, args.test_root)
        project_files = render_project_files(repo, args.module_name, args.test_root, sdk_name, generated_roots)
    ensure_ignored_outputs(repo, [repo / ".venv", *project_files])
    validate_existing_venv(repo / ".venv", args.python)
    if args.pep723_script:
        prepare_script_environment(repo, args.python, args.pep723_script)
    elif not (repo / ".venv").exists():
        subprocess.run(["uv", "venv", "--python", args.python, ".venv"], cwd=repo, check=True)
    if args.sync:
        subprocess.run(build_sync_command(args.python, args.extra), cwd=repo, check=True)
    for path, content in project_files.items():
        atomic_write(path, content)
    if sdk_name is None and project_files:
        print(
            "Python environment prepared; IDE SDK registration is not confirmed. "
            "The generated SDK name is provisional. Inspection must verify registration and assignment. "
            "The lifecycle helper can request explicit SDK preparation for helper-owned projects when the plugin supports it. "
            "If inspection reports language_sdk_missing, register this worktree's .venv/bin/python "
            "in the selected IDE and rerun preparation.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
