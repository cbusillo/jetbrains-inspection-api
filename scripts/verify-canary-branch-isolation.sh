#!/usr/bin/env bash

set -euo pipefail

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "ERROR: Usage: ./scripts/verify-canary-branch-isolation.sh DEFAULT_BRANCH" >&2
  exit 1
fi

DEFAULT_BRANCH="$1"

git fetch origin "$DEFAULT_BRANCH"
git rev-parse --verify "origin/$DEFAULT_BRANCH^{commit}" >/dev/null

set +e
git merge-base --is-ancestor HEAD "origin/$DEFAULT_BRANCH"
ANCESTOR_STATUS=$?
set -e

if [ "$ANCESTOR_STATUS" -ne 1 ]; then
  echo "ERROR: Canary tags must point to branch-only commits that are not on the default branch (merge-base status $ANCESTOR_STATUS)." >&2
  exit 1
fi
