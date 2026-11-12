#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# ///
"""Install a plugin archive into an isolated IDE, ask the plugin for verdicts, and compare them."""

import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path

FIXTURE = Path(__file__).resolve().parent.parent / "test-fixtures" / "release-smoke"
PROFILE = "ReleaseSmoke"
STALE_RERUNS = 2


@dataclass(frozen=True)
class Case:
    name: str
    scope: dict[str, str]
    verdict: str
    # None: the build decides whether an unproven run still reports what it found.
    findings: bool | None
    reason: str | None = None


CASES = (
    Case("clean", {"scope": "files", "files": "src/clean.json"}, "GREEN", findings=False),
    Case("finding", {"scope": "files", "files": "src/finding.json"}, "RED", findings=True),
    Case("unproven", {"scope": "directory", "dir": "src"}, "UNKNOWN", findings=None, reason="execution_not_proven"),
)


class SmokeError(Exception):
    pass


class PluginApi:
    def __init__(self, port: int, log: Path) -> None:
        self.base = f"http://127.0.0.1:{port}/api/inspection"
        self.log = log

    def get(self, endpoint: str, params: dict[str, str] | None = None, timeout: float = 30) -> dict:
        url = f"{self.base}/{endpoint}?{urllib.parse.urlencode(params or {})}"
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                body = json.load(response)
        except urllib.error.HTTPError as error:
            body = error.read().decode(errors="replace")
            raise SmokeError(f"{endpoint} returned HTTP {error.code}: {body[:2000]}") from error
        with self.log.open("a") as log:
            log.write(json.dumps({"endpoint": endpoint, "params": params, "response": body}) + "\n")
        return body


def poll(description: str, deadline: float, check):
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            result = check()
        except (OSError, SmokeError) as error:
            last_error = error
            result = None
        if result is not None:
            return result
        time.sleep(1)
    raise SmokeError(f"Timed out waiting for {description}: {last_error}")


def open_project(api: PluginApi, project: Path, session_id: str, deadline: float) -> dict:
    lease_id = str(uuid.uuid4())
    selector = {"project_path": str(project), "session_id": session_id, "lease_id": lease_id}
    api.get("lifecycle/open", selector)

    def claimed() -> dict | None:
        claim = api.get("lifecycle/claim", selector)
        if claim.get("ownership_proven") and claim.get("lifecycle_readiness", {}).get("ready"):
            return claim
        return None

    return poll("the project to open and become ready", deadline, claimed)


def inspect(api: PluginApi, route: dict[str, str], case: Case, timeout_s: int) -> dict:
    trigger = api.get("trigger", route | case.scope | {"profile": PROFILE})
    run = route | {"inspection_run_id": trigger["run_id"]}
    api.get("wait", run | {"timeout_ms": str(timeout_s * 1000), "poll_ms": "1000"}, timeout=timeout_s + 30)
    return api.get("problems", run | case.scope)


def run_case(api: PluginApi, claim: dict, case: Case, timeout_s: int) -> dict:
    route = {"project_key": claim["project_key"], "session_id": claim["session_id"]}
    # A newly opened project keeps changing while the IDE finishes its startup work. The plugin
    # reports that as stale_results and asks for a rerun; a stale result is never an answer.
    for attempt in range(1, STALE_RERUNS + 2):
        problems = inspect(api, route, case, timeout_s)
        if problems.get("inspection_verdict_reason") != "stale_results":
            break
    verdict = problems.get("inspection_verdict")
    findings = int(problems.get("total_problems") or 0)
    reason = problems.get("inspection_verdict_reason")
    passed = (
        verdict == case.verdict
        and case.findings in (None, findings > 0)
        and case.reason in (None, reason)
    )
    return {
        "case": case.name,
        "passed": passed,
        "expected": case.verdict,
        "verdict": verdict,
        "reason": reason,
        "findings": findings,
        "attempts": attempt,
    }


def close_project(api: PluginApi, claim: dict) -> str:
    keys = ("project_key", "project_instance_id", "session_id", "close_token", "lease_id")
    try:
        response = api.get("lifecycle/close", {key: claim[key] for key in keys})
    except SmokeError as error:
        return f"failed: {error}"
    return str(response.get("status"))


def ask_plugin(api: PluginApi, project: Path, timeout_s: int) -> dict:
    deadline = time.monotonic() + timeout_s
    identity = poll("the plugin to answer", deadline, lambda: api.get("identity", timeout=5))
    claim = open_project(api, project, identity["session_id"], deadline)
    try:
        results = [run_case(api, claim, case, timeout_s) for case in CASES]
    finally:
        cleanup = close_project(api, claim)
    return {
        "passed": cleanup == "closed" and all(result["passed"] for result in results),
        "plugin": {key: identity.get(key) for key in (
            "pid", "plugin_version", "plugin_build_edition", "plugin_build_fingerprint", "plugin_build_dirty",
            "ide_name", "ide_version",
        )},
        "cases": results,
        "cleanup": cleanup,
    }


def launch_ide(ide_app: Path, work: Path, config: Path, port: int) -> subprocess.Popen:
    product_info = ide_app / "Contents/Resources/product-info.json"
    product = json.loads(product_info.read_text())
    launcher = (product_info.parent / product["launch"][0]["launcherPath"]).resolve()
    properties = work / "idea.properties"
    properties.write_text("\n".join([
        f"idea.config.path={config}",
        f"idea.plugins.path={work / 'plugins'}",
        f"idea.system.path={work / 'system'}",
        f"idea.log.path={work / 'logs'}",
        "idea.initially.ask.config=never",
        f"rpc.port={port}",
    ]) + "\n")
    # A private user.home keeps first-start projects, daemons and caches out of the real home.
    home = work / "home"
    home.mkdir()
    vm_options = work / "ide.vmoptions"
    default_options = (product_info.parent / product["launch"][0]["vmOptionsFilePath"]).read_text()
    vm_options.write_text(default_options + f"\n-Duser.home={home}\n")
    variable = product["envVarBaseName"]
    environment = os.environ | {
        "HOME": str(home),
        f"{variable}_PROPERTIES": str(properties),
        f"{variable}_VM_OPTIONS": str(vm_options),
    }
    log = (work / "launcher.log").open("w")
    return subprocess.Popen(
        [str(launcher), "dontReopenProjects"],
        cwd=work, env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
    )


def stop_ide(process: subprocess.Popen) -> None:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def require_free_port(port: int) -> None:
    with socket.socket() as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError as error:
            raise SmokeError(f"Port {port} is in use; stop the IDE using it or pass --port.") from error


def new_config(config: Path) -> Path:
    # An existing options directory tells the IDE this is not a first start, so it skips the
    # import-settings wizard that would otherwise block startup and offer the user's own profile.
    (config / "options").mkdir(parents=True)
    (config / "options" / "ide.general.xml").write_text("<application />\n")
    return config


def smoke(args: argparse.Namespace) -> dict:
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    require_free_port(args.port)
    with zipfile.ZipFile(args.archive) as archive:
        archive.extractall(work / "plugins")
    project = work / "release-smoke"
    shutil.copytree(FIXTURE, project)
    process = launch_ide(args.ide_app.resolve(), work, new_config(work / "config"), args.port)
    try:
        report = ask_plugin(PluginApi(args.port, work / "responses.jsonl"), project, args.timeout)
        if os.getpgid(report["plugin"]["pid"]) != process.pid:
            raise SmokeError(f"Port {args.port} was answered by an IDE this smoke did not launch.")
        return report
    finally:
        stop_ide(process)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True, help="Plugin ZIP to install.")
    parser.add_argument("--ide-app", type=Path, required=True, help="IDE .app bundle to run.")
    parser.add_argument("--work-dir", type=Path, required=True, help="New directory for the IDE's private state.")
    parser.add_argument("--port", type=int, default=65349)
    parser.add_argument("--timeout", type=int, default=600, help="Seconds allowed for startup and each case.")
    args = parser.parse_args()
    # Termination unwinds through smoke()'s cleanup so the isolated IDE never outlives the driver.
    for termination in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(termination, lambda number, _frame: sys.exit(128 + number))
    try:
        report = smoke(args)
    except SmokeError as error:
        report = {"passed": False, "error": str(error)}
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
