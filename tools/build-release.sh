#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."

rm -rf dist
mkdir -p dist

mapfile -d '' PROD_SOURCES < <(find deal -name '*.java' -print0 | sort -z)
if [ "${#PROD_SOURCES[@]}" -eq 0 ]; then
  echo "ERROR: no production Java sources found" >&2
  exit 1
fi

javac --release 25 -proc:none -d dist "${PROD_SOURCES[@]}"
mkdir -p dist/std dist/bin
cp deal/runtime.lua deal/runtime.js dist/deal/
cp std/*.d.deal std/*.lua std/*.js dist/std/
install -m 755 tools/deal-launcher.sh dist/bin/deal

echo "Distribution built in $PWD/dist"
