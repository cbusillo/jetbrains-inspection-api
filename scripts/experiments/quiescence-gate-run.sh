#!/usr/bin/env bash
# Drives fresh-project red-lane controls so the installed experiment build can
# compare its quiescence gate arms. The plugin picks the arm per project path.

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT" || exit 1

ROUNDS=${1:?usage: quiescence-gate-run.sh ROUNDS OUTPUT_DIR [product...]}
OUTPUT_DIR=${2:?usage: quiescence-gate-run.sh ROUNDS OUTPUT_DIR [product...]}
shift 2
PRODUCTS=("$@")
if [ ${#PRODUCTS[@]} -eq 0 ]; then
  PRODUCTS=(intellij pycharm webstorm)
fi

ide_name() {
  case "$1" in
    intellij) echo "IntelliJ IDEA" ;;
    pycharm) echo "PyCharm" ;;
    webstorm) echo "WebStorm" ;;
    *) echo "unknown product: $1" >&2; exit 2 ;;
  esac
}

MARKER_DIR="$HOME/Library/Caches/jetbrains-inspection-api/experiments"
mkdir -p "$OUTPUT_DIR" "$MARKER_DIR"
touch "$MARKER_DIR/quiescence-gate"
trap 'rm -f "$MARKER_DIR/quiescence-gate"' EXIT
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUTPUT_DIR/started-at-utc.txt"
printf 'round\tproduct\tstarted_utc\tseconds\texit\n' > "$OUTPUT_DIR/runs.tsv"

for round in $(seq 1 "$ROUNDS"); do
  for product in "${PRODUCTS[@]}"; do
    ide=$(ide_name "$product")
    started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    begin=$(date +%s)
    ./scripts/dogfood-red-lane-smoke.sh \
      --product "$product" --ide "$ide" --ide-app "$ide" \
      --ide-channel stable --ide-version 2026.2 \
      --timeout-ms 300000 --prepare-timeout-ms 300000 \
      --json-out "$OUTPUT_DIR/round-$round-$product.json" \
      > "$OUTPUT_DIR/round-$round-$product.log" 2>&1
    status=$?
    printf '%s\t%s\t%s\t%s\t%s\n' "$round" "$product" "$started" "$(( $(date +%s) - begin ))" "$status" >> "$OUTPUT_DIR/runs.tsv"
    sleep 5
  done
done
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUTPUT_DIR/finished-at-utc.txt"
