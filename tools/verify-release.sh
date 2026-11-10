#!/bin/sh
# tools/verify-release.sh — verify a built DEAL distribution outside the checkout.
#
# Invocation: tools/verify-release.sh [dist]
#   dist defaults to <checkout>/dist, the output of tools/build-release.sh.
#
# Every project the script compiles is a private copy under a mktemp
# scratch directory, and every compile runs through the distribution
# launcher with the scratch project as cwd: stdlib declarations, runtime
# libraries and stdlib implementations resolve from the distribution, and
# no checkout path can repair a deployment omission. Fault legs run
# against private distribution copies carrying the fault under test.
#
# Legs:
#   1. resource set        the matched v1.6 resource set is present
#   2. backend execution   compile, inspect the published set, and execute
#                          the smoke project on luajit, jvm and js
#   3. runtime failure     a thrown Error exits nonzero with its code
#   4. version admission   an old manifest rejects; version-shaped declaration
#                          comments are inert; dollar declarations reject
#   5. missing declaration a missing spec-listed declaration is rejected
#   6. missing implementation  a missing spec-listed implementation is rejected
#   7. missing runtime     a missing runtime library is rejected
#   8. mismatched resource a declaration mismatched with its use is rejected
#   9. bad deployment      a broken stdlib implementation fails at execution
#  10. native package      the packaged smoke project compiles with
#                          Backend.LLVM, the published runtime files are
#                          byte-equal to the distribution copies, the product
#                          is built outside the publication root and runs at
#                          -O0 and -O2 with the pinned stdout, and the packaged
#                          debug information names the smoke source file and line
#
# A failing leg prints an "ERROR:" line naming the leg and the observed
# value on stderr and exits 1. All legs green prints "verify-release: PASS".
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIST=${1:-"$ROOT/dist"}
case "$DIST" in
  /*) ;;
  *) DIST="$PWD/$DIST" ;;
esac

# The five spec-listed stdlib modules, in spec declaration order.
STDLIB_MODULES="console string table math time"
# The packaged smoke entry's exact stdout.
SMOKE_STDOUT="smoke deal"

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

leg() {
  printf 'verify-release: %s\n' "$1" >&2
}

if [ ! -d "$DIST" ]; then
  fail "distribution directory not found: $DIST"
fi
DIST=$(cd "$DIST" && pwd)

SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/deal-verify-release.XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT HUP INT TERM

# =========================================================================
# 1. The matched resource set.
# =========================================================================

leg "leg 1/10 distribution resource set"

if [ ! -x "$DIST/bin/deal" ]; then
  fail "distribution launcher not found: $DIST/bin/deal"
fi
for asset in deal/Main.class deal/runtime.lua deal/json.lua deal/runtime.js \
             deal/lua_async_export_driver.lua; do
  if [ ! -f "$DIST/$asset" ]; then
    fail "distribution resource missing: $asset"
  fi
done
for module in $STDLIB_MODULES; do
  for extension in d.deal lua js; do
    if [ ! -f "$DIST/std/$module.$extension" ]; then
      fail "distribution resource missing: std/$module.$extension"
    fi
  done
done

for extension in d.deal lua js; do
  if [ -e "$DIST/std/json.$extension" ]; then
    fail "distribution carries retired resource: std/json.$extension"
  fi
done

# =========================================================================
# Shared helpers.
# =========================================================================

# copy_smoke <destination>: a private copy of the committed smoke project.
copy_smoke() {
  mkdir -p "$1"
  cp -R "$ROOT/test/release/smoke-project/." "$1/"
}

# compile <dist> <project> <entry> <backend> <out> <log>: the production
# compiler from <dist>, run with <project> as cwd. Returns the compiler's
# status; both streams go to <log>.
compile() {
  ( cd "$2" && "$1/bin/deal" compile --backend "$4" --output "$5" "$3" ) \
    >"$6" 2>&1
}

# single_artifact <directory> <extension>: the one entry artifact.
single_artifact() {
  directory=$1
  extension=$2
  set -- "$directory"/*."$extension"
  if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    fail "expected exactly one .$extension artifact in $directory, found $#"
  fi
  printf '%s\n' "$1"
}

# require_copy <published> <reference>: a published deployment copy must
# exist and be byte-equal to the distribution resource it came from.
require_copy() {
  if [ ! -f "$1" ]; then
    fail "bad published artifact: missing $1"
  fi
  if ! cmp -s "$1" "$2"; then
    fail "bad published artifact: $1 differs from $2"
  fi
}

# execute_artifact <backend> <dist> <out> <artifact> <stdout> <stderr>:
# run one published entry artifact under the real toolchain and return the
# child's exit status.
execute_artifact() {
  backend=$1
  dist=$2
  out=$3
  artifact=$4
  stdout=$5
  stderr=$6
  case "$backend" in
    luajit)
      ( cd "$out" && luajit "$artifact" ) >"$stdout" 2>"$stderr"
      ;;
    js)
      ( cd "$out" && node "$artifact" ) >"$stdout" 2>"$stderr"
      ;;
    jvm)
      classes=$(dirname "$out")/classes
      mkdir -p "$classes"
      if ! javac --release 25 -proc:none -cp "$dist" -d "$classes" \
          "$artifact" >"$(dirname "$out")/javac.log" 2>&1; then
        cat "$(dirname "$out")/javac.log" >&2
        return 1
      fi
      ( cd "$out" && java -cp "$classes:$dist" \
          "$(basename "$artifact" .java)" ) >"$stdout" 2>"$stderr"
      ;;
    *)
      fail "unknown backend: $backend"
      ;;
  esac
}

# private_dist <name>: an isolated copy of the distribution.
private_dist() {
  cp -R "$DIST" "$SCRATCH/$1"
  printf '%s\n' "$SCRATCH/$1"
}

# rejected_compile <label> <dist> <code>: the smoke project compiled with
# <dist> must fail with <code> and publish nothing.
rejected_compile() {
  label=$1
  dist=$2
  code=$3
  project="$SCRATCH/$label-smoke"
  out="$SCRATCH/$label-out"
  log="$SCRATCH/$label.log"
  copy_smoke "$project"
  status=0
  compile "$dist" "$project" "$project/src/main.deal" luajit \
    "$out" "$log" || status=$?
  if [ "$status" -eq 0 ]; then
    fail "$label was accepted"
  fi
  if ! grep -q "$code" "$log"; then
    cat "$log" >&2
    fail "$label did not report $code"
  fi
  if [ -d "$out" ] && [ -n "$(ls -A "$out" 2>/dev/null)" ]; then
    fail "$label published artifacts"
  fi
}

# =========================================================================
# 2. Compile and execute the packaged smoke project on every supported
#    backend.
# =========================================================================

verify_backend() {
  backend=$1
  leg "leg 2/10 the packaged smoke project on $backend"

  project="$SCRATCH/$backend/smoke"
  out="$SCRATCH/$backend/out"
  log="$SCRATCH/$backend/compile.log"
  mkdir -p "$SCRATCH/$backend"
  copy_smoke "$project"

  if ! compile "$DIST" "$project" "$project/src/main.deal" "$backend" \
      "$out" "$log"; then
    cat "$log" >&2
    fail "$backend smoke compile failed"
  fi

  case "$backend" in
    luajit)
      artifact=$(single_artifact "$out" lua)
      require_copy "$out/deal/runtime.lua" "$DIST/deal/runtime.lua"
      require_copy "$out/deal/json.lua" "$DIST/deal/json.lua"
      for module in $STDLIB_MODULES; do
        require_copy "$out/std/$module.lua" "$DIST/std/$module.lua"
      done
      # The deployed runtime and every deployed Lua stdlib implementation
      # load through the published set's own module path.
      if ! ( cd "$out" && luajit -e "local j=require('deal.json');
            assert(type(j.encode)=='function' and type(j.decode)=='function');
            assert(j.encode(j.decode('[1,null]', 'unknown'))=='[1,null]');
            for _, m in ipairs({'console',
            'string', 'table', 'math', 'time'}) do
            assert(type(require('std.' .. m)) == 'table', m) end" ) \
          >"$SCRATCH/$backend/deployment-load.log" 2>&1; then
        cat "$SCRATCH/$backend/deployment-load.log" >&2
        fail "the deployed LuaJIT runtime or stdlib implementation does not load"
      fi
      ;;
    js)
      artifact=$(single_artifact "$out" js)
      require_copy "$out/deal/runtime.js" "$DIST/deal/runtime.js"
      for module in $STDLIB_MODULES; do
        require_copy "$out/std/$module.js" "$DIST/std/$module.js"
      done
      # The deployed runtime and every deployed JS stdlib implementation
      # load through the published set's own module path.
      if ! ( cd "$out" && node -e "for (const m of ['console', 'string',
            'table', 'math', 'time']) {
            const mod = require('./std/' + m);
            if (typeof mod !== 'object' || mod === null) throw new Error(m);
            }" ) >"$SCRATCH/$backend/deployment-load.log" 2>&1; then
        cat "$SCRATCH/$backend/deployment-load.log" >&2
        fail "the deployed JavaScript runtime or stdlib implementation does not load"
      fi
      ;;
    jvm)
      artifact=$(single_artifact "$out" java)
      ;;
  esac

  stem=$(basename "$artifact")
  stem=${stem%.*}
  stem=$(printf '%s' "$stem" | tr '[:upper:]' '[:lower:]')
  if [ ! -f "$out/$stem.deal.map.json" ]; then
    fail "$backend entry source-map sidecar missing: $out/$stem.deal.map.json"
  fi

  stdout="$SCRATCH/$backend/run.stdout"
  stderr="$SCRATCH/$backend/run.stderr"
  status=0
  execute_artifact "$backend" "$DIST" "$out" "$artifact" \
    "$stdout" "$stderr" || status=$?
  if [ "$status" -ne 0 ]; then
    cat "$stderr" >&2
    fail "$backend smoke execution exited $status"
  fi
  printf '%s\n' "$SMOKE_STDOUT" >"$SCRATCH/$backend/expected.stdout"
  if ! cmp -s "$stdout" "$SCRATCH/$backend/expected.stdout"; then
    fail "$backend smoke stdout mismatch: $(cat "$stdout")"
  fi
  if [ -s "$stderr" ]; then
    cat "$stderr" >&2
    fail "$backend smoke execution wrote to stderr"
  fi
}

for backend in luajit jvm js; do
  verify_backend "$backend"
done

# =========================================================================
# 3. A thrown Error is a rejected runtime outcome on every backend.
# =========================================================================

leg "leg 3/10 runtime failures are rejected"

FAILURE_PROJECT="$SCRATCH/runtime-failure"
mkdir -p "$FAILURE_PROJECT/src"
printf '%s\n' '{"languageVersion": "1.6", "moduleRoots": ["src"], "backend": "luajit"}' \
  >"$FAILURE_PROJECT/deal.json"
printf '%s\n' \
  'export function main(): null {' \
  '  throw { code: "VERIFY_FAIL", message: "expected distribution failure" };' \
  '  return null;' \
  '}' >"$FAILURE_PROJECT/src/main.deal"

for backend in luajit jvm js; do
  out="$SCRATCH/runtime-failure-out-$backend"
  log="$SCRATCH/runtime-failure-compile-$backend.log"
  if ! compile "$DIST" "$FAILURE_PROJECT" \
      "$FAILURE_PROJECT/src/main.deal" "$backend" "$out" "$log"; then
    cat "$log" >&2
    fail "$backend runtime-failure project failed to compile"
  fi
  case "$backend" in
    luajit) artifact=$(single_artifact "$out" lua) ;;
    js) artifact=$(single_artifact "$out" js) ;;
    jvm) artifact=$(single_artifact "$out" java) ;;
  esac
  stdout="$SCRATCH/runtime-failure-$backend.stdout"
  stderr="$SCRATCH/runtime-failure-$backend.stderr"
  status=0
  execute_artifact "$backend" "$DIST" "$out" "$artifact" \
    "$stdout" "$stderr" || status=$?
  if [ "$status" -eq 0 ]; then
    fail "$backend lifted an uncaught Error (expected a failing exit)"
  fi
  if ! cat "$stdout" "$stderr" | grep -q 'DEAL_ERROR_CODE: VERIFY_FAIL'; then
    cat "$stdout" "$stderr" >&2
    fail "$backend did not report the thrown Error code"
  fi
done

# =========================================================================
# 4. Manifest versions govern admission; declaration comments are whitespace.
# =========================================================================

leg "leg 4/10 manifest versions, inert comments and declaration identifiers"

OLD_PROJECT="$SCRATCH/old-version"
mkdir -p "$OLD_PROJECT"
cp -R "$ROOT/test/release/smoke-project/." "$OLD_PROJECT/"
printf '%s\n' '{"languageVersion": "1.3", "backend": "luajit"}' \
  >"$OLD_PROJECT/deal.json"
OLD_OUT="$SCRATCH/old-version-out"
OLD_LOG="$SCRATCH/old-version.log"
status=0
compile "$DIST" "$OLD_PROJECT" "$OLD_PROJECT/src/main.deal" luajit \
  "$OLD_OUT" "$OLD_LOG" || status=$?
if [ "$status" -eq 0 ]; then
  fail "a languageVersion 1.3 manifest was accepted"
fi
if ! grep -q 'E2010' "$OLD_LOG"; then
  cat "$OLD_LOG" >&2
  fail "the rejected 1.3 manifest did not report E2010"
fi
if [ -d "$OLD_OUT" ] && [ -n "$(ls -A "$OLD_OUT" 2>/dev/null)" ]; then
  fail "the rejected 1.3 manifest published artifacts"
fi

old_declaration=$(private_dist old-declaration-version)
printf '%s\n' '// @deal-version 1.2' \
  | cat - "$old_declaration/std/string.d.deal" \
  >"$old_declaration/std/string.d.deal.tmp"
mv "$old_declaration/std/string.d.deal.tmp" \
  "$old_declaration/std/string.d.deal"
for backend in luajit jvm js; do
  project="$SCRATCH/commented-declaration-$backend"
  out="$SCRATCH/commented-declaration-out-$backend"
  log="$SCRATCH/commented-declaration-$backend.log"
  copy_smoke "$project"
  if ! compile "$old_declaration" "$project" "$project/src/main.deal" "$backend" "$out" "$log"; then
    cat "$log" >&2
    fail "$backend interpreted a version-shaped comment as a declaration directive"
  fi
  case "$backend" in
    luajit) artifact=$(single_artifact "$out" lua) ;;
    jvm) artifact=$(single_artifact "$out" java) ;;
    js) artifact=$(single_artifact "$out" js) ;;
  esac
  stdout="$SCRATCH/commented-declaration-$backend.stdout"
  stderr="$SCRATCH/commented-declaration-$backend.stderr"
  if ! execute_artifact "$backend" "$old_declaration" "$out" "$artifact" "$stdout" "$stderr"; then
    cat "$stderr" >&2
    fail "$backend version-comment positive did not execute"
  fi
  printf '%s\n' "$SMOKE_STDOUT" >"$SCRATCH/commented-declaration.expected"
  if ! cmp -s "$stdout" "$SCRATCH/commented-declaration.expected" || [ -s "$stderr" ]; then
    fail "$backend version-comment positive changed the smoke transcript"
  fi
done

bad_identifier=$(private_dist dollar-declaration)
printf '%s\n' 'export function retired$probe(): null;' \
  | cat - "$bad_identifier/std/string.d.deal" >"$bad_identifier/std/string.d.deal.tmp"
mv "$bad_identifier/std/string.d.deal.tmp" "$bad_identifier/std/string.d.deal"
rejected_compile dollar-declaration "$bad_identifier" E2008

# =========================================================================
# 5-8. Missing and mismatched distribution resources are rejected at the
#      compile boundary; a broken deployed implementation fails at
#      execution.
# =========================================================================

leg "leg 5/10 a missing stdlib declaration is rejected"
missing_declaration=$(private_dist missing-declaration)
rm "$missing_declaration/std/string.d.deal"
rejected_compile missing-declaration "$missing_declaration" E2003

leg "leg 6/10 a missing stdlib implementation is rejected"
missing_implementation=$(private_dist missing-implementation)
rm "$missing_implementation/std/string.lua"
rejected_compile missing-implementation "$missing_implementation" E6000

leg "leg 7/10 a missing runtime library is rejected"
missing_runtime=$(private_dist missing-runtime)
rm "$missing_runtime/deal/runtime.lua"
rejected_compile missing-runtime "$missing_runtime" E6000
missing_json_runtime=$(private_dist missing-json-runtime)
rm "$missing_json_runtime/deal/json.lua"
rejected_compile missing-json-runtime "$missing_json_runtime" E6000
if ! grep -q 'Runtime library not found: deal/json.lua' "$SCRATCH/missing-json-runtime.log"; then
  fail "missing internal JSON runtime did not name its resource"
fi

leg "leg 8/10 a mismatched declaration is rejected"
mismatched=$(private_dist mismatched-declaration)
sed 's/^export function trim(s: string): string;$/export function trim(s: boolean): string;/' \
  "$mismatched/std/string.d.deal" >"$mismatched/std/string.d.deal.tmp"
mv "$mismatched/std/string.d.deal.tmp" "$mismatched/std/string.d.deal"
if ! grep -q 'trim(s: boolean)' "$mismatched/std/string.d.deal"; then
  fail "the mismatched-declaration fault was not injected"
fi
rejected_compile mismatched-declaration "$mismatched" E5001

# =========================================================================
# 9. A broken deployed stdlib implementation is not a passing execution.
# =========================================================================

leg "leg 9/10 a broken deployment copy fails at execution"

bad_deployment=$(private_dist bad-deployment)
printf '%s\n' '"use strict";' 'module.exports = {};' \
  >"$bad_deployment/std/string.js"
bad_project="$SCRATCH/bad-deployment-smoke"
bad_out="$SCRATCH/bad-deployment-out"
bad_log="$SCRATCH/bad-deployment.log"
copy_smoke "$bad_project"
if ! compile "$bad_deployment" "$bad_project" \
    "$bad_project/src/main.deal" js "$bad_out" "$bad_log"; then
  cat "$bad_log" >&2
  fail "bad-deployment smoke compile failed"
fi
artifact=$(single_artifact "$bad_out" js)
status=0
execute_artifact js "$bad_deployment" "$bad_out" "$artifact" \
  "$SCRATCH/bad-deployment.stdout" "$SCRATCH/bad-deployment.stderr" \
  || status=$?
if [ "$status" -eq 0 ]; then
  fail "a broken deployed stdlib implementation executed successfully"
fi

# The internal algorithms are independently executable runtime resources,
# not language stdlib functions. A present but broken scanner cannot be
# repaired from the checkout or silently replaced with the old parser.
bad_json=$(private_dist bad-json-runtime)
printf '%s\n' 'return {scan=function() error("broken internal JSON scanner",0) end}' \
  >"$bad_json/deal/json.lua"
json_project="$SCRATCH/bad-json-runtime-smoke"
json_out="$SCRATCH/bad-json-runtime-out"
copy_smoke "$json_project"
printf '%s\n' 'export function main(): null {' \
  '  let value: number = JSON.parse("1");' '  return null;' '}' \
  >"$json_project/src/main.deal"
if ! compile "$bad_json" "$json_project" "$json_project/src/main.deal" luajit \
    "$json_out" "$SCRATCH/bad-json-runtime.log"; then
  cat "$SCRATCH/bad-json-runtime.log" >&2
  fail "broken internal JSON runtime control did not compile"
fi
require_copy "$json_out/deal/json.lua" "$bad_json/deal/json.lua"
artifact=$(single_artifact "$json_out" lua)
status=0
execute_artifact luajit "$bad_json" "$json_out" "$artifact" \
  "$SCRATCH/bad-json-runtime.stdout" "$SCRATCH/bad-json-runtime.stderr" || status=$?
if [ "$status" -eq 0 ] || ! grep -q 'broken internal JSON scanner' "$SCRATCH/bad-json-runtime.stderr"; then
  cat "$SCRATCH/bad-json-runtime.stderr" >&2
  fail "a broken internal JSON scanner was not observed at execution"
fi

# =========================================================================
# 10. The packaged native distribution (llvm-native-conformance-gate G7).
#
# The packaged smoke project compiles with Backend.LLVM, every published
# runtime file is byte-equal to the distribution copy, the product is built
# through the toolchain seam into this script's scratch area (outside the
# publication root) and executed at -O0 and -O2 with the pinned stdout and a
# zero exit, and the packaged debug information names the smoke source file
# and its line. A missing required tool is the named TOOL_MISSING failure.
# =========================================================================

leg "leg 10/10 the packaged native smoke project"

# The closed C11 runtime deployment set (llvm-native-backend-integration D6),
# the packaging mirror of deal.toolchain.NativeRuntime.FILES.
NATIVE_RUNTIME_FILES="deal/native/deal_rt.h \
deal/native/platform.c deal/native/platform.h \
deal/native/value.c deal/native/value.h \
deal/native/memory.c deal/native/memory.h \
deal/native/text.c deal/native/text.h \
deal/native/container.c deal/native/container.h \
deal/native/descriptor.c deal/native/descriptor.h \
deal/native/error.c deal/native/error.h \
deal/native/json.c deal/native/json.h \
deal/native/std.c deal/native/std.h \
deal/native/host.c deal/native/host.h \
deal/native/async.c deal/native/async.h \
deal/native/entry.c deal/native/entry.h \
deal/native/main.c"

for asset in $NATIVE_RUNTIME_FILES; do
  if [ ! -f "$DIST/$asset" ]; then
    fail "distribution native runtime missing: $asset"
  fi
done

for tool in clang llvm-as llvm-dwarfdump; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    fail "TOOL_MISSING: $tool is required by the native distribution leg"
  fi
done

mkdir -p "$SCRATCH/native"
for level in 0 2; do
  project="$SCRATCH/native/smoke-$level"
  # The documented default publication root of `deal native build`:
  # <manifestDirectory>/build/llvm, so the source display names the emitted
  # module carries stay project-relative ("src/main.deal").
  out="$project/build/llvm"
  products="$SCRATCH/native/products-$level"
  log="$SCRATCH/native/build-$level.log"
  copy_smoke "$project"
  if ! ( cd "$project" && "$DIST/bin/deal" native build \
      src/main.deal --product "$products" \
      --opt-level "$level" ) >"$log" 2>&1; then
    cat "$log" >&2
    fail "native smoke build at -O$level failed"
  fi

  # The publication set: exactly one textual module, and every runtime file
  # byte-equal to the packaged copy it came from.
  ir_count=$(find "$out" -name '*.ll' -type f | wc -l | tr -d ' ')
  if [ "$ir_count" -ne 1 ]; then
    fail "native -O$level published $ir_count textual modules, expected 1"
  fi
  module=$(find "$out" -name '*.ll' -type f)
  if ! llvm-as "$module" -o /dev/null >"$SCRATCH/native/assemble-$level.log" 2>&1; then
    cat "$SCRATCH/native/assemble-$level.log" >&2
    fail "native -O$level published module is not accepted by llvm-as"
  fi
  for asset in $NATIVE_RUNTIME_FILES; do
    require_copy "$out/$asset" "$DIST/$asset"
  done

  product="$products/native/main"
  if [ ! -x "$product" ]; then
    fail "native -O$level product not executable: $product"
  fi
  case "$product" in
    "$out"/*) fail "native -O$level product was written inside the publication root" ;;
  esac

  stdout="$SCRATCH/native/run-$level.stdout"
  stderr="$SCRATCH/native/run-$level.stderr"
  status=0
  ( cd "$SCRATCH/native" && "$product" ) >"$stdout" 2>"$stderr" || status=$?
  if [ "$status" -ne 0 ]; then
    cat "$stderr" >&2
    fail "native -O$level smoke execution exited $status"
  fi
  printf '%s\n' "$SMOKE_STDOUT" >"$SCRATCH/native/expected.stdout"
  if ! cmp -s "$stdout" "$SCRATCH/native/expected.stdout"; then
    fail "native -O$level smoke stdout mismatch: $(cat "$stdout")"
  fi
  if [ -s "$stderr" ]; then
    cat "$stderr" >&2
    fail "native -O$level smoke execution wrote to stderr"
  fi

done

# The packaged debug information names the smoke source file and its line, and
# carries one source-mapped subprogram for the entry function.
DWARF="$SCRATCH/native/debug-info.txt"
if ! llvm-dwarfdump --debug-info "$SCRATCH/native/products-2/native/main" \
    >"$DWARF" 2>&1; then
  cat "$DWARF" >&2
  fail "llvm-dwarfdump could not read the native product's debug information"
fi
if ! grep -q 'DW_AT_name.*("src/main.deal")' "$DWARF"; then
  fail "native debug information does not name the smoke source file src/main.deal"
fi
if ! grep -q 'DW_AT_decl_line.*(11)' "$DWARF"; then
  fail "native debug information does not carry the smoke source line 11"
fi
if ! grep -q 'DW_AT_name.*("main")' "$DWARF"; then
  fail "native debug information does not name the entry function main"
fi

printf 'verify-release: PASS\n'
