#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
from pathlib import Path
import argparse
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    implementation = "GlobalInspectionContextImpl"
    boundary = args.sources / "com/shiny/inspectionmcp/GlobalInspectionContextBoundary.kt"
    failures = []
    for source in sorted(args.sources.rglob("*.kt")):
        if source == boundary:
            continue
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            if implementation in line:
                failures.append(f"{source}:{number}: {implementation} must remain in {boundary}")
    for number, line in enumerate(args.manifest.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if implementation in line and not any(
            owner in line
            for owner in (
                "com.shiny.inspectionmcp.GlobalInspectionContextBoundary",
                "com.shiny.inspectionmcp.NativeAttestedGlobalInspectionContext",
            )
        ):
            failures.append(f"{args.manifest}:{number}: {implementation} finding escaped the named boundary")
    for failure in failures:
        print(failure, file=sys.stderr)
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
