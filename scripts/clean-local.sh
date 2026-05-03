#!/usr/bin/env bash

set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

find . \
	\( \
	-name .git \
	-o -name .code \
	-o -name .gradle \
	-o -name .intellijPlatform \
	-o -name .qodana \
	-o -name build \
	\) -prune \
	-o -name .DS_Store -type f -delete

rm -f tmp-test-all.log

if [ -d tmp ]; then
	find tmp -maxdepth 1 -type f -name '*.log' -delete
	rmdir tmp 2>/dev/null || true
fi
