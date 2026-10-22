#!/usr/bin/env python3

import argparse
from collections import Counter
from pathlib import Path
import sys


REPORT_SUFFIX_MARKER = ". This "
INTERNAL_API_REPORT_NAME = "internal-api-usages.txt"


def read_manifest(path: Path) -> list[str]:
    entries = [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    unrecognized = [entry for entry in entries if not entry.startswith("Internal ")]
    if unrecognized:
        raise ValueError(
            "Internal API allowlist contains unrecognized entries:\n"
            + "\n".join(f"  {entry}" for entry in unrecognized)
        )
    if entries != sorted(entries):
        raise ValueError(f"Internal API allowlist must remain sorted: {path}")
    duplicates = [entry for entry, count in Counter(entries).items() if count > 1]
    if duplicates:
        raise ValueError(
            "Internal API allowlist contains duplicate entries:\n"
            + "\n".join(f"  {entry}" for entry in sorted(duplicates))
        )
    return entries


def read_report(path: Path) -> list[str]:
    if path.is_dir():
        # Plugin Verifier writes no internal API file for a plugin without internal API use.
        path = path / INTERNAL_API_REPORT_NAME
        if not path.exists():
            return []
    entries = []
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line:
            continue
        canonical, marker, _ = line.partition(REPORT_SUFFIX_MARKER)
        if not marker or not canonical.startswith("Internal "):
            raise ValueError(
                f"Unrecognized Plugin Verifier finding at {path}:{line_number}: {line}"
            )
        entries.append(canonical)
    return entries


def format_difference(label: str, difference: Counter[str]) -> list[str]:
    return [f"  {label}: {entry}" for entry in sorted(difference.elements())]


def verify_report(expected: list[str], report_path: Path) -> None:
    actual = read_report(report_path)
    expected_counts = Counter(expected)
    actual_counts = Counter(actual)
    missing = expected_counts - actual_counts
    unexpected = actual_counts - expected_counts
    if missing or unexpected:
        details = format_difference("missing", missing) + format_difference(
            "unexpected", unexpected
        )
        raise ValueError(
            f"Plugin Verifier internal API findings do not match the selected allowlist: {report_path}\n"
            + "\n".join(details)
        )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compare Plugin Verifier internal API reports with an exact allowlist."
    )
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--report", action="append", type=Path, default=[])
    parser.add_argument(
        "--report-dir",
        action="append",
        type=Path,
        default=[],
        help="Plugin Verifier report directory for this plugin version",
    )
    args = parser.parse_args()

    try:
        expected = read_manifest(args.manifest)
        for report_dir in args.report_dir:
            if not report_dir.is_dir():
                raise ValueError(f"Plugin Verifier report directory does not exist: {report_dir}")
        reports = [*args.report, *args.report_dir]
        for report_path in reports:
            verify_report(expected, report_path)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if reports:
        print(
            f"Verified {len(reports)} Plugin Verifier report(s) against "
            f"{len(expected)} exact internal API finding(s)."
        )
    else:
        print(f"Validated {len(expected)} exact internal API allowlist entries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
