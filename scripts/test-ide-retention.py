#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import json
import subprocess
import tempfile
import unittest
import sys
from unittest.mock import patch
from argparse import Namespace

sys.dont_write_bytecode = True

spec = spec_from_file_location("reproduction", Path(__file__).with_name("reproduce-ide-retention.py"))
reproduction = module_from_spec(spec)
spec.loader.exec_module(reproduction)


class RetentionReproductionTest(unittest.TestCase):
    def exercise(self, root: Path, properties: str, *, exhausted=False, reported_pid=123):
        helper = root / "helper.py"
        helper.write_text(f'def identity_for_port(port):\n    return {{"pid": {reported_pid}, "session_id": "fixture"}}\ndef ide_memory_failure(identity):\n    return {"object()" if exhausted else "None"}\n')
        args = Namespace(instance_root=root, output=root / "measurements", helper=helper,
                         jcmd=Path("fixture-jcmd"), pid=123, port=12345, cycles=1, ide="Fixture IDE")
        native = {"agent_result": {"verdict": "GREEN"},
                  "cleanup": {"status": "closed", "close_attempts": [{"project_disposed": True}]}}
        histogram = " 1: 2 128 com.shiny.inspectionmcp.InspectionResultsSnapshot\nTotal 2 128\n"

        def command(argv, **kwargs):
            if "VM.system_properties" in argv:
                text = properties
            elif "GC.class_histogram" in argv:
                text = histogram
            else:
                fixture = Path(argv[argv.index("--repo") + 1])
                self.assertEqual(fixture.parent, args.output / "fixtures")
                self.assertTrue((fixture / "sample.xml").is_file())
                self.assertEqual(kwargs["env"]["JETBRAINS_INSPECTION_CACHE_DIR"], str(root / "helper-cache"))
                text = json.dumps(native)
            return subprocess.CompletedProcess(argv, 0, stdout=text, stderr="")

        with patch.object(reproduction.subprocess, "run", side_effect=command):
            return reproduction.run(args)

    def test_foreign_or_partially_isolated_ide_is_refused_before_open(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            properties = "\n".join(f"idea.{key}.path={root / value}" for key, value in
                                   (("config", "config"), ("system", "system"), ("log", "logs")))
            properties += "\nidea.plugins.path=/shared/plugins\n"
            with self.assertRaises(ValueError):
                self.exercise(root, properties)
            self.assertFalse((root / "measurements").exists())

    def test_exhaustion_stops_the_batch_without_opening_another_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            properties = "\n".join(f"idea.{key}.path={root / value}" for key, value in
                                   (("config", "config"), ("system", "system"), ("log", "logs"), ("plugins", "plugins")))
            with self.assertRaisesRegex(ValueError, "memory exhaustion"):
                self.exercise(root, properties, exhausted=True)
            self.assertFalse((root / "measurements/fixtures").exists())

    def test_a_port_owned_by_another_pid_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            properties = "\n".join(f"idea.{key}.path={root / value}" for key, value in
                                   (("config", "config"), ("system", "system"), ("log", "logs"), ("plugins", "plugins")))
            with self.assertRaisesRegex(ValueError, "different IDE process"):
                self.exercise(root, properties, reported_pid=124)
            self.assertFalse((root / "measurements").exists())

    def test_completed_cycle_preserves_native_payload_and_retained_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            properties = "\n".join(f"idea.{key}.path={root / value}" for key, value in
                                   (("config", "config"), ("system", "system"), ("log", "logs"), ("plugins", "plugins")))
            rows = self.exercise(root, properties)
            saved = json.loads((root / "measurements/measurements.json").read_text())
            self.assertEqual(saved["rows"], rows)
            self.assertEqual(rows[-1]["snapshots"], 2)
            self.assertEqual(rows[-1]["heap_object_bytes"], 128)
            payload = json.loads((root / "measurements/cycle-01.json").read_text())
            self.assertEqual(payload["cleanup"]["status"], "closed")


if __name__ == "__main__":
    unittest.main()
