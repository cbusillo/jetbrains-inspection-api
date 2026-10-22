#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
import argparse
import re
import sys
from pathlib import Path

BROAD_CATCH = re.compile(r"\bcatch\s*\(\s*(\w+)\s*:\s*(?:Exception|RuntimeException|Throwable)\s*\)\s*\{")
CANCELLATION_NOTE = re.compile(r"^[ \t]*//[ \t]*Cancellation:[ \t]*\S", re.MULTILINE)
CANCELLATION_CATCH = re.compile(r"\bcatch\s*\(\s*\w+\s*:\s*ProcessCanceledException\s*\)\s*$")
SIBLING_CATCH = re.compile(r"\bcatch\s*\([^()]*\)\s*$")
CANCELLATION_HANDLERS = (
    "rethrowIfCanceled",
    "rethrowCancellation",
    "classifyExecutionError",
    "requestExternalCancellation",
    "sendInternalServerError",
)


def matching_close(text: str, open_index: int) -> int:
    depth = 0
    for index in range(open_index, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    return len(text)


def matching_open(text: str, close_index: int) -> int:
    depth = 0
    for index in range(close_index, -1, -1):
        if text[index] == "}":
            depth += 1
        elif text[index] == "{":
            depth -= 1
            if depth == 0:
                return index
    return 0


def has_earlier_cancellation_catch(text: str, catch_start: int) -> bool:
    position = catch_start
    while True:
        previous_close = text[:position].rstrip()
        if not previous_close.endswith("}"):
            return False
        block_open = matching_open(text, len(previous_close) - 1)
        header = text[:block_open].rstrip()
        if CANCELLATION_CATCH.search(header):
            return True
        sibling = SIBLING_CATCH.search(header)
        if not sibling:
            return False
        position = sibling.start()


def outside_nested_blocks(body: str) -> str:
    depth = 0
    kept: list[str] = []
    for character in body:
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
        elif depth == 0:
            kept.append(character)
    return "".join(kept)


def handles_cancellation(name: str, body: str) -> bool:
    statements = body.strip()
    if CANCELLATION_NOTE.search(outside_nested_blocks(body)):
        return True
    if re.search(rf"\bthrow\s+{name}\s*$", statements):
        return True
    if re.search(rf"^\s*\w+\s*=\s*{name}\s*$", statements, re.MULTILINE):
        return True
    return any(re.search(rf"\b{handler}\((?:[^()]*,)?\s*{name}\s*\)", statements) for handler in CANCELLATION_HANDLERS)


def swallowing_catches(source: Path) -> list[int]:
    text = source.read_text(encoding="utf-8")
    lines: list[int] = []
    for match in BROAD_CATCH.finditer(text):
        body_open = match.end() - 1
        body = text[body_open + 1 : matching_close(text, body_open)]
        if handles_cancellation(match.group(1), body) or has_earlier_cancellation_catch(text, match.start()):
            continue
        lines.append(text.count("\n", 0, match.start()) + 1)
    return lines


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()
    if not args.sources.is_dir():
        print(f"{args.sources}: source directory does not exist", file=sys.stderr)
        return 1
    failures = [
        f"{source}:{line}: broad catch must pass its exception to rethrowIfCanceled, end with throw, "
        "follow a ProcessCanceledException catch, or explain in a '// Cancellation:' comment why it is safe"
        for source in sorted(args.sources.rglob("*.kt"))
        for line in swallowing_catches(source)
    ]
    for failure in failures:
        print(failure, file=sys.stderr)
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
