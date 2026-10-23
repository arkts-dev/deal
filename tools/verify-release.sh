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
#   1. resource set        the matched v1.4 resource set is present
#   2. backend execution   compile, inspect the published set, and execute
#                          the smoke project on luajit, jvm and js
#   3. runtime failure     a thrown Error exits nonzero with its code
#   4. old version         a languageVersion "1.2" manifest and a
#                          "@deal-version 1.2" declaration are rejected
#   5. missing declaration a missing spec-listed declaration is rejected
#   6. missing implementation  a missing spec-listed implementation is rejected
#   7. missing runtime     a missing runtime library is rejected
#   8. mismatched resource a declaration mismatched with its use is rejected
#   9. bad deployment      a broken stdlib implementation fails at execution
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

# The six spec-listed stdlib modules, in spec declaration order.
STDLIB_MODULES="console string table json math time"
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

leg "leg 1/9 distribution resource set"

if [ ! -x "$DIST/bin/deal" ]; then
  fail "distribution launcher not found: $DIST/bin/deal"
fi
for asset in deal/Main.class deal/runtime.lua deal/runtime.js \
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
  leg "leg 2/9 the packaged smoke project on $backend"

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
      for module in $STDLIB_MODULES; do
        require_copy "$out/std/$module.lua" "$DIST/std/$module.lua"
      done
      # The deployed runtime and every deployed Lua stdlib implementation
      # load through the published set's own module path.
      if ! ( cd "$out" && luajit -e "for _, m in ipairs({'console',
            'string', 'table', 'json', 'math', 'time'}) do
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
            'table', 'json', 'math', 'time']) {
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

leg "leg 3/9 runtime failures are rejected"

FAILURE_PROJECT="$SCRATCH/runtime-failure"
mkdir -p "$FAILURE_PROJECT/src"
printf '%s\n' '{"languageVersion": "1.4", "moduleRoots": ["src"], "backend": "luajit"}' \
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
# 4. Older declared versions are rejected before any artifact exists.
# =========================================================================

leg "leg 4/9 old versions are rejected"

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
rejected_compile old-declaration-version "$old_declaration" E1044

# =========================================================================
# 5-8. Missing and mismatched distribution resources are rejected at the
#      compile boundary; a broken deployed implementation fails at
#      execution.
# =========================================================================

leg "leg 5/9 a missing stdlib declaration is rejected"
missing_declaration=$(private_dist missing-declaration)
rm "$missing_declaration/std/string.d.deal"
rejected_compile missing-declaration "$missing_declaration" E2003

leg "leg 6/9 a missing stdlib implementation is rejected"
missing_implementation=$(private_dist missing-implementation)
rm "$missing_implementation/std/string.lua"
rejected_compile missing-implementation "$missing_implementation" E6000

leg "leg 7/9 a missing runtime library is rejected"
missing_runtime=$(private_dist missing-runtime)
rm "$missing_runtime/deal/runtime.lua"
rejected_compile missing-runtime "$missing_runtime" E6000

leg "leg 8/9 a mismatched declaration is rejected"
mismatched=$(private_dist mismatched-declaration)
sed 's/^export function trim(s: string): string;$/export function trim(s: int): string;/' \
  "$mismatched/std/string.d.deal" >"$mismatched/std/string.d.deal.tmp"
mv "$mismatched/std/string.d.deal.tmp" "$mismatched/std/string.d.deal"
if ! grep -q 'trim(s: int)' "$mismatched/std/string.d.deal"; then
  fail "the mismatched-declaration fault was not injected"
fi
rejected_compile mismatched-declaration "$mismatched" E5001

# =========================================================================
# 9. A broken deployed stdlib implementation is not a passing execution.
# =========================================================================

leg "leg 9/9 a broken deployment copy fails at execution"

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

printf 'verify-release: PASS\n'
