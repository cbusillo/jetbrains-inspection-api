#!/usr/bin/env bash

set -euo pipefail

VERSION=""
CHANNEL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --version)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --version requires a plugin version." >&2
        exit 1
      fi
      VERSION="$1"
      ;;
    --channel)
      shift
      if [ -z "${1:-}" ]; then
        echo "ERROR: --channel requires a Marketplace channel." >&2
        exit 1
      fi
      CHANNEL="$1"
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      exit 1
      ;;
  esac
  shift
done

if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  if [ -n "$CHANNEL" ]; then
    echo "ERROR: Stable plugin versions must use the default Marketplace channel." >&2
    exit 1
  fi
  exit 0
fi

if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-canary\.[1-9][0-9]*$ ]]; then
  if [ "$CHANNEL" != "canary" ]; then
    echo "ERROR: Canary plugin versions require -PmarketplaceChannel=canary." >&2
    exit 1
  fi
  exit 0
fi

echo "ERROR: Unsupported Marketplace plugin version: ${VERSION:-<empty>}." >&2
exit 1
