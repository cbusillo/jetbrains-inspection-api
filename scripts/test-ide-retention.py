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
import zipfile
from unittest.mock import patch
from argparse import Namespace

sys.dont_write_bytecode = True

spec = spec_from_file_location("reproduction", Path(__file__).with_name("reproduce-ide-retention.py"))
reproduction = module_from_spec(spec)
spec.loader.exec_module(reproduction)


class RetentionReproductionTest(unittest.TestCase):
    def exercise(self, root: Path, properties: str, *, memory_reason=None, reported_pid=123,
                 registry=None, port=12345, timeout=False, verdict="GREEN", disposed=True,
                 missing_class=False):
        helper = root / "helper.py"
        helper.write_text(f'''
from argparse import Namespace
class InspectError(Exception):
    def __init__(self, reason):
        self.payload = {{"error_reason": reason}}
DEFAULT_PORT_RANGE = range(63340, 63350)
def build_parser():
    return Namespace(parse_args=lambda args: Namespace())
def identity_for_port(port):
    return {{"pid": {reported_pid}, "session_id": "fixture", "plugin_build_fingerprint": "fixture-build",
             "open_projects": [{{"base_path": {str(root / "startup")!r}, "project_key": "startup"}}]}}
def http_get(port, endpoint, params):
    return Namespace(body={{"registry": {{"instances_dir": {str(registry or root / "registry")!r}}}}})
def ide_memory_failure(identity):
    return {f"InspectError({memory_reason!r})" if memory_reason else "None"}
''')
        (root / "plugins").mkdir(exist_ok=True)
        with zipfile.ZipFile(root / "plugins" / "plugin.jar", "w") as archive:
            archive.writestr("com/shiny/inspectionmcp/inspection-build.properties", "plugin.build.fingerprint=fixture-build\n")
            if not missing_class:
                for name in reproduction.COUNTED_CLASSES:
                    if name.startswith("com.shiny."):
                        archive.writestr(name.replace(".", "/") + ".class", b"")
        args = Namespace(instance_root=root, output=root / "measurements", helper=helper,
                         jcmd=Path("fixture-jcmd"), pid=123, port=port, cycles=1, ide="Fixture IDE")
        native = {"agent_result": {"verdict": verdict},
                  "cleanup": {"status": "closed", "close_attempts": [{"project_disposed": disposed}]}}
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
                self.assertEqual(kwargs["env"]["JETBRAINS_INSPECTION_IDE_CONFIG_DIR"], str(root / "config"))
                self.assertIn("--no-ide-launch", argv)
                text = json.dumps(native)
                kwargs["stdout"].write(text)
                kwargs["stdout"].flush()
                kwargs["stderr"].write("cycle diagnostic")
                kwargs["stderr"].flush()
                if timeout:
                    raise subprocess.TimeoutExpired(argv, 300)
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
            with self.assertRaises(ValueError):
                self.exercise(root, properties, memory_reason="ide_memory_exhausted")
            self.assertFalse((root / "measurements/fixtures").exists())

    def test_a_port_owned_by_another_pid_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            properties = "\n".join(f"idea.{key}.path={root / value}" for key, value in
                                   (("config", "config"), ("system", "system"), ("log", "logs"), ("plugins", "plugins")))
            with self.assertRaises(ValueError):
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

    def test_low_memory_context_does_not_abort_native_cycle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            rows = self.exercise(root, self.properties(root), memory_reason="ide_memory_pressure")
            self.assertEqual(rows[-1]["cycle"], 1)

    def properties(self, root):
        return "\n".join(f"idea.{key}.path={root / value}" for key, value in
                         (("config", "config"), ("system", "system"), ("log", "logs"), ("plugins", "plugins")))

    def test_shared_registry_discovery_port_or_missing_classes_prevents_open(self):
        for kwargs in ({"registry": Path("/shared/registry")}, {"port": 63345}, {"missing_class": True}):
            with self.subTest(kwargs=kwargs), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                with self.assertRaises(ValueError):
                    self.exercise(root, self.properties(root), **kwargs)
                self.assertFalse((root / "measurements").exists())

    def test_timeout_preserves_partial_output_without_measurement_for_incomplete_cycle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with self.assertRaises(subprocess.TimeoutExpired):
                self.exercise(root, self.properties(root), timeout=True)
            self.assertTrue((root / "measurements/cycle-01.json").read_text())
            self.assertEqual((root / "measurements/cycle-01.stderr").read_text(), "cycle diagnostic")
            saved = json.loads((root / "measurements/measurements.json").read_text())
            self.assertEqual(len(saved["rows"]), 1)

    def test_unproven_native_result_or_disposal_stops_before_next_observation(self):
        for kwargs in ({"verdict": "UNKNOWN"}, {"disposed": False}):
            with self.subTest(kwargs=kwargs), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                with self.assertRaises(ValueError):
                    self.exercise(root, self.properties(root), **kwargs)
                self.assertTrue((root / "measurements/cycle-01.json").is_file())
                self.assertFalse((root / "measurements/cycle-01.histo").exists())

    def test_same_class_in_multiple_classloaders_is_summed(self):
        name, key = next(iter(reproduction.COUNTED_CLASSES.items()))
        counts = reproduction.histogram_counts(f"1: 2 32 {name}\n2: 3 48 {name}\nTotal 5 80\n")
        self.assertEqual(counts[key], 5)


if __name__ == "__main__":
    unittest.main()
