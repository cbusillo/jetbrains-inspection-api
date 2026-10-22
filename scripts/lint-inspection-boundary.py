#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
import argparse
import sys
from pathlib import Path

IMPLEMENTATION = "GlobalInspectionContextImpl"
MANIFEST_OWNERS = (
    "com.shiny.inspectionmcp.InspectionBuildEdition",
    "com.shiny.inspectionmcp.NativeAttestedGlobalInspectionContext",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=f"Keep {IMPLEMENTATION} references inside the one full-build boundary file.")
    parser.add_argument("--sources", type=Path, action="append", required=True)
    parser.add_argument("--boundary", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    missing = [sources for sources in args.sources if not sources.is_dir()]
    for sources in missing:
        print(f"{sources}: source directory does not exist", file=sys.stderr)
    if missing:
        return 1
    if not args.boundary.is_file():
        print(f"{args.boundary}: boundary source is missing", file=sys.stderr)
        return 1
    boundary = args.boundary.resolve()
    failures: list[str] = []
    for sources in args.sources:
        for source in sorted(sources.rglob("*.kt")):
            if source.resolve() == boundary:
                continue
            for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
                if IMPLEMENTATION in line:
                    failures.append(f"{source}:{number}: {IMPLEMENTATION} must remain in {args.boundary}")
    for number, line in enumerate(args.manifest.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if IMPLEMENTATION in line and not any(owner in line for owner in MANIFEST_OWNERS):
            failures.append(f"{args.manifest}:{number}: {IMPLEMENTATION} finding escaped the named boundary")
    for failure in failures:
        print(failure, file=sys.stderr)
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
