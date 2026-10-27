#!/bin/bash
# tools/build-release.sh — build the DEAL distribution the compiler runs from.
#
# Self-locates, so it runs from any cwd. The distribution is exactly the
# compiled production classes plus the matched v1.5 resource set: the two
# runtime libraries, the LuaJIT async-export driver, the six spec-listed
# stdlib modules' declarations and target implementations, and the
# launcher. Packaging requires every one of those committed assets before
# it starts and requires the same complete set after it copies, so an
# incomplete or mismatched distribution can neither be built nor shipped;
# a missing asset fails with a named path.
set -euo pipefail

cd "$(dirname "$0")/.."

# The six spec-listed stdlib modules, in spec declaration order.
STDLIB_MODULES=(console string table json math time)

# The matched resource set every distribution carries.
REQUIRED_ASSETS=(
  deal/runtime.lua
  deal/runtime.js
  deal/lua_async_export_driver.lua
  tools/deal-launcher.sh
)
for module in "${STDLIB_MODULES[@]}"; do
  REQUIRED_ASSETS+=("std/$module.d.deal" "std/$module.lua" "std/$module.js")
done

for asset in "${REQUIRED_ASSETS[@]}"; do
  if [ ! -f "$asset" ]; then
    echo "ERROR: missing distribution asset: $asset" >&2
    exit 1
  fi
done

mapfile -d '' PROD_SOURCES < <(find deal -name '*.java' -print0 | sort -z)
if [ "${#PROD_SOURCES[@]}" -eq 0 ]; then
  echo "ERROR: no production Java sources found" >&2
  exit 1
fi

rm -rf dist
mkdir -p dist
javac --release 25 -proc:none -d dist "${PROD_SOURCES[@]}"
mkdir -p dist/std dist/bin
cp deal/runtime.lua deal/runtime.js dist/deal/
# The production LuaJIT async-export driver (the LuaJitAsyncExportInvoker
# resource half): located classpath-first, so it ships beside the runtime
# as one deployment unit.
cp deal/lua_async_export_driver.lua dist/deal/
cp std/*.d.deal std/*.lua std/*.js dist/std/
install -m 755 tools/deal-launcher.sh dist/bin/deal

# Completeness: the shipped set carries exactly the matched resource set
# (no asset is left behind, none is duplicated).
for asset in "${REQUIRED_ASSETS[@]}"; do
  case "$asset" in
    tools/*) continue ;;
  esac
  if [ ! -f "dist/$asset" ]; then
    echo "ERROR: distribution is missing required resource: $asset" >&2
    exit 1
  fi
done
if [ ! -f dist/deal/Main.class ]; then
  echo "ERROR: distribution is missing the compiler entry point: deal/Main.class" >&2
  exit 1
fi
if [ ! -x dist/bin/deal ]; then
  echo "ERROR: distribution is missing the executable launcher: bin/deal" >&2
  exit 1
fi
shipped_stdlib=$(find dist/std -type f | wc -l)
expected_stdlib=$((3 * ${#STDLIB_MODULES[@]}))
if [ "$shipped_stdlib" -ne "$expected_stdlib" ]; then
  echo "ERROR: dist/std carries $shipped_stdlib files, expected $expected_stdlib" >&2
  exit 1
fi

echo "Distribution built in $PWD/dist"
