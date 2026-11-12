#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# ///
import json
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

sys.dont_write_bytecode = True
spec = spec_from_file_location("release_smoke", Path(__file__).with_name("release-smoke.py"))
assert spec is not None and spec.loader is not None
release_smoke = module_from_spec(spec)
spec.loader.exec_module(release_smoke)

CORRECT = {
    "src/clean.json": [("GREEN", "no_matching_findings", 0)],
    "src/finding.json": [("RED", "actionable_findings", 2)],
    "src": [("UNKNOWN", "execution_not_proven", 2)],
}


def fake_plugin(answers: dict[str, list[tuple[str, str, int]]], close_status: str) -> type[BaseHTTPRequestHandler]:
    remaining = {target: list(results) for target, results in answers.items()}

    class Plugin(BaseHTTPRequestHandler):
        # noinspection PyShadowingBuiltins
        def log_message(self, format: str, *args: object) -> None:
            pass

        # noinspection PyPep8Naming
        def do_GET(self) -> None:
            path, _, query = str(self.path).partition("?")
            endpoint = path.removeprefix("/api/inspection/")
            params: dict[str, str] = dict(urllib.parse.parse_qsl(query))
            claim = {
                "ownership_proven": True, "lifecycle_readiness": {"ready": True}, "project_key": "key",
                "session_id": "session", "project_instance_id": "instance", "close_token": "token",
                "lease_id": params.get("lease_id"),
            }
            body = {
                "identity": {"session_id": "session"},
                "lifecycle/open": {"status": "opening"},
                "lifecycle/claim": claim,
                "lifecycle/close": {"status": close_status},
                "trigger": {"run_id": "run"},
                "wait": {},
            }.get(endpoint)
            if endpoint == "problems":
                results = remaining[params.get("files") or params["dir"]]
                verdict, reason, findings = results.pop(0) if len(results) > 1 else results[0]
                body = {"inspection_verdict": verdict, "inspection_verdict_reason": reason, "total_problems": findings}
            payload = json.dumps(body).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Plugin


def ask(answers: dict[str, list[tuple[str, str, int]]], close_status: str = "closed") -> dict:
    # noinspection PyTypeChecker
    server = ThreadingHTTPServer(("127.0.0.1", 0), fake_plugin(answers, close_status))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory() as work:
            api = release_smoke.PluginApi(server.server_address[1], Path(work) / "responses.jsonl")
            return release_smoke.ask_plugin(api, Path(work), timeout_s=10)
    finally:
        server.shutdown()


class ReleaseSmokeTest(unittest.TestCase):

    def test_passes_only_when_every_verdict_matches(self) -> None:
        self.assertTrue(ask(CORRECT)["passed"])

    def test_fails_when_an_unproven_run_is_reported_clean(self) -> None:
        report = ask(CORRECT | {"src": [("GREEN", "no_matching_findings", 0)]})
        self.assertFalse(report["passed"])
        self.assertEqual("closed", report["cleanup"])

    def test_fails_when_the_project_does_not_close(self) -> None:
        self.assertFalse(ask(CORRECT, close_status="skipped")["passed"])

    def test_fails_when_unknown_has_another_cause(self) -> None:
        self.assertFalse(ask(CORRECT | {"src": [("UNKNOWN", "capture_incomplete", 0)]})["passed"])

    def test_reruns_stale_results_but_never_accepts_them(self) -> None:
        stale = ("UNKNOWN", "stale_results", 0)
        settled = ask(CORRECT | {"src/clean.json": [stale, ("GREEN", "no_matching_findings", 0)]})
        self.assertTrue(settled["passed"])
        self.assertEqual(2, settled["cases"][0]["attempts"])
        self.assertFalse(ask(CORRECT | {"src": [stale]})["passed"])


if __name__ == "__main__":
    unittest.main()
