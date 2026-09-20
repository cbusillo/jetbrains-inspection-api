#!/usr/bin/env python3
"""Compare first-attempt snapshot freshness between quiescence gate arms.

Reads the IDE logs written by the experiment build. For every project the
first logged run is the first attempt on a freshly opened project; its arm
comes from the experiment line and its outcome from the validation line.
"""
import argparse
import glob
import math
import os
import re
from collections import defaultdict

ARM = re.compile(r"^(\S+ \S+) .*Quiescence gate experiment for (\S+): run=(\d+) arm=(on|off)")
VALIDATION = re.compile(r"^(\S+ \S+) .*Inspection snapshot validation for (\S+): run=(\d+), (.*)$")


def parse(log_paths, since):
    arms, validations = {}, {}
    for path in log_paths:
        with open(path, errors="ignore") as handle:
            for line in handle:
                if line[:19] < since:
                    continue
                match = ARM.match(line)
                if match:
                    arms[(path_ide(path), match.group(2), int(match.group(3)))] = match.group(4)
                    continue
                match = VALIDATION.match(line)
                if match:
                    fields = dict(re.findall(r"(\w+)=([^,]+)", match.group(4)))
                    validations[(path_ide(path), match.group(2), int(match.group(3)))] = fields
    return arms, validations


def path_ide(path):
    return path.split("/JetBrains/")[1].split("/")[0]


def wilson(successes, total):
    if total == 0:
        return (0.0, 0.0)
    z = 1.96
    p = successes / total
    centre = (p + z * z / (2 * total)) / (1 + z * z / total)
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / (1 + z * z / total)
    return (centre - half, centre + half)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--since", required=True, help="local log timestamp, e.g. '2026-09-20 22:00:00'")
    parser.add_argument("--project-prefix", default="inspection-red-lane")
    args = parser.parse_args()
    logs = glob.glob(os.path.expanduser("~/Library/Logs/JetBrains/*2026.2/idea.log*"))
    arms, validations = parse(logs, args.since)

    first_run = {}
    for (ide, project, run), arm in arms.items():
        if not project.startswith(args.project_prefix):
            continue
        key = (ide, project)
        if key not in first_run or run < first_run[key][0]:
            first_run[key] = (run, arm)

    table = defaultdict(lambda: defaultdict(int))
    for (ide, project), (run, arm) in first_run.items():
        fields = validations.get((ide, project, run))
        if fields is None:
            outcome = "no_validation_line"
        elif fields.get("unchangedPublished") == "true" or fields.get("promoted") == "true":
            outcome = "fresh"
        else:
            outcome = "stale"
        table[(ide, arm)][outcome] += 1
        table[("ALL", arm)][outcome] += 1

    print(f"projects with a first attempt: {len(first_run)}")
    for (ide, arm) in sorted(table):
        counts = table[(ide, arm)]
        total = sum(counts.values())
        fresh = counts["fresh"]
        low, high = wilson(fresh, total)
        print(
            f"{ide:22s} gate={arm:3s} n={total:4d} fresh={fresh:4d} ({fresh / total:5.1%}, 95% CI {low:5.1%}-{high:5.1%}) "
            f"stale={counts['stale']} no_validation={counts['no_validation_line']}"
        )


if __name__ == "__main__":
    main()
