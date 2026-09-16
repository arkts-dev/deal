#!/bin/sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIST=${1:-"$ROOT/dist"}
case "$DIST" in
  /*) ;;
  *) DIST="$PWD/$DIST" ;;
esac

if [ ! -x "$DIST/bin/deal" ]; then
  printf 'ERROR: distribution launcher not found: %s/bin/deal\n' "$DIST" >&2
  exit 1
fi

SCRATCH_DIR=$(mktemp -d "${TMPDIR:-/tmp}/deal-dist-smoke.XXXXXX")
trap 'rm -rf "$SCRATCH_DIR"' EXIT HUP INT TERM
cp -R "$ROOT/test/release/smoke-project" "$SCRATCH_DIR/smoke"
(
  cd "$SCRATCH_DIR/smoke"
  "$DIST/bin/deal" compile \
    --output "$SCRATCH_DIR/smoke-out" \
    "$SCRATCH_DIR/smoke/src/main.deal"
)
if [ -z "$(find "$SCRATCH_DIR/smoke-out" -type f -print -quit)" ]; then
  echo "ERROR: distribution smoke produced no artifacts" >&2
  exit 1
fi

echo "Distribution smoke passed"
