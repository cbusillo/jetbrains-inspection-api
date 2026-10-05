#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
from pathlib import Path
import re
import sys


USES = re.compile(r"^(?:-\s+)?uses:")
PINNED = re.compile(r"^(?:-\s+)?uses:\s+[^@#\s]+@[0-9a-f]{40}(?:\s+#.*)?$")


def main(paths: list[str]) -> int:
    if not paths:
        print("Usage: lint-workflow-action-pins.py WORKFLOW_OR_DIRECTORY...", file=sys.stderr)
        return 1
    failures = []
    workflows = []
    for path in map(Path, paths):
        if path.is_dir():
            workflows.extend(sorted((*path.glob("*.yml"), *path.glob("*.yaml"))))
        else:
            workflows.append(path)
    for path in workflows:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = line.strip()
            if USES.match(line) and not PINNED.fullmatch(line):
                failures.append(f"{path}:{number}: action must use a lowercase full commit SHA")
    for failure in failures:
        print(failure, file=sys.stderr)
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
