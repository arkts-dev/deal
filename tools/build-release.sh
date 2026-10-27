#!/bin/bash
# tools/build-release.sh — build the DEAL distribution the compiler runs from.
#
# Self-locates, so it runs from any cwd. The distribution is exactly the
# compiled production classes plus the matched v1.5 resource set: the two
# runtime libraries, the LuaJIT async-export driver, the complete C11 runtime
# deployment set, the six spec-listed stdlib modules' declarations and target
# implementations, and the launcher. Packaging requires every one of those
# committed assets before it starts and requires the same complete set after it
# copies, so an incomplete or mismatched distribution can neither be built nor
# shipped; a missing asset fails with a named path.
set -euo pipefail

cd "$(dirname "$0")/.."

# The six spec-listed stdlib modules, in spec declaration order.
STDLIB_MODULES=(console string table json math time)

# The closed C11 runtime deployment set (llvm-native-backend-integration D6):
# every file the LLVM arm stages, at the same relative path under dist. This
# list is the packaging mirror of deal.toolchain.NativeRuntime.FILES and carries
# the product build's process-entry translation unit (deal/native/main.c).
NATIVE_RUNTIME_FILES=(
  deal/native/deal_rt.h
  deal/native/platform.c deal/native/platform.h
  deal/native/value.c deal/native/value.h
  deal/native/memory.c deal/native/memory.h
  deal/native/text.c deal/native/text.h
  deal/native/container.c deal/native/container.h
  deal/native/descriptor.c deal/native/descriptor.h
  deal/native/error.c deal/native/error.h
  deal/native/json.c deal/native/json.h
  deal/native/std.c deal/native/std.h
  deal/native/host.c deal/native/host.h
  deal/native/async.c deal/native/async.h
  deal/native/entry.c deal/native/entry.h
  deal/native/main.c
)

# The matched resource set every distribution carries.
REQUIRED_ASSETS=(
  deal/runtime.lua
  deal/runtime.js
  deal/lua_async_export_driver.lua
  tools/deal-launcher.sh
  "${NATIVE_RUNTIME_FILES[@]}"
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
# The complete C11 runtime, one deployment copy per pinned file.
mkdir -p dist/deal/native
for file in "${NATIVE_RUNTIME_FILES[@]}"; do
  cp "$file" "dist/$file"
done
install -m 755 tools/deal-launcher.sh dist/bin/deal

# Byte equality: every shipped runtime copy is the checkout file, so a
# deployment copy can never drift from its source.
for file in "${NATIVE_RUNTIME_FILES[@]}"; do
  if ! cmp -s "$file" "dist/$file"; then
    echo "ERROR: runtime deployment copy differs from the checkout file: $file" >&2
    exit 1
  fi
done

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
shipped_native=$(find dist/deal/native -type f | wc -l)
expected_native=${#NATIVE_RUNTIME_FILES[@]}
if [ "$shipped_native" -ne "$expected_native" ]; then
  echo "ERROR: dist/deal/native carries $shipped_native files, expected" \
    "$expected_native" >&2
  exit 1
fi
shipped_stdlib=$(find dist/std -type f | wc -l)
expected_stdlib=$((3 * ${#STDLIB_MODULES[@]}))
if [ "$shipped_stdlib" -ne "$expected_stdlib" ]; then
  echo "ERROR: dist/std carries $shipped_stdlib files, expected $expected_stdlib" >&2
  exit 1
fi

echo "Distribution built in $PWD/dist"
