#!/bin/bash
#
# ISSUE-0400 — Both-branch verification via isolated scratch exercise.
#
# Exercises the four disposition-pair cases of the locked
# std/time.nowMillis selector (luajit-time-selector-disposition D4) plus
# the two unmodified-runner interceptions. Every run builds a fresh
# scratch copy of the repository tree from the pinned pre-unit state T1,
# applies only scratch-local edits, executes the compiled scratch runner
# over a scratch conformance root, asserts the exact pinned result the
# specified mechanism produces, verifies the repository is untouched, and
# deletes the scratch tree. Re-running this script rebuilds everything
# from repository state and must reproduce all six pinned results.
#
#   run A  branch-1 matching (isolated): the fixture clone is flipped to
#          the canonical D3 disposition header (body unchanged), the
#          scratch test/ConformanceTest.java is compiled with the
#          stagedFailure(...) registration absent, and the retained
#          std/time.lua is untouched
#          -> PASS on runtime-error E8004, zero staged failures, exit 0
#   run B  branch-1 stale-entry (isolated): the fixture header is flipped
#          only and the staged entry is still present
#          -> gate failure with the promotion instruction naming the
#             registry-entry removal, exit 1
#   run C  branch-2 matching (scratch): empty staged-failure registry +
#          scratch passing std/time stand-in at the scratch tree's std/ +
#          a runtime-ok clone of the fixture at the corpus-relative path
#          -> PASS, exit 0
#   run D  branch-2 mismatched (scratch): the same scratch configuration
#          with the clone's expectation runtime-error E8004 (same body)
#          -> FAIL (no E8004 is raised), exit 1
#   run E  unmodified runner + stand-in + runtime-ok clone
#          -> stale passing promotion (test/ConformanceTest.java:845-847),
#             exit 1
#   run F  unmodified runner + stand-in + runtime-error E8004 clone
#          -> stale expectation-changed promotion
#             (test/ConformanceTest.java:665-670), exit 1
#
# Usage: verify_both_branches_scratch.sh [EVIDENCE_DIR]
#
# Evidence: per-run runner logs and the post-run repository status
# capture are written to EVIDENCE_DIR (default: a fresh temp directory).
# The script exits 0 only when all six pinned results were reproduced by
# their specified mechanisms and the repository files
# test/ConformanceTest.java, std/time.lua and the shared fixture were
# never modified.
#
set -u

REPO="$(cd "$(dirname "$0")" && pwd)"
OUTDIR="${1:-}"
if [ -z "$OUTDIR" ]; then
  OUTDIR="$(mktemp -d /tmp/issue0400-evidence.XXXXXX)"
fi
mkdir -p "$OUTDIR"
OUTDIR="$(cd "$OUTDIR" && pwd)"

LEFT_OVERS=""
cleanup() {
  for d in $LEFT_OVERS; do
    rm -rf "$d" 2>/dev/null || true
  done
}
trap cleanup EXIT

die() {
  echo "ABORT: $*" >&2
  exit 1
}

for tool in javac java luajit python3 diff grep sed tail git; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool missing: $tool"
done

PIN_FIXTURE="$REPO/test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal"
PIN_TIME_LUA="$REPO/std/time.lua"
PIN_RUNNER="$REPO/test/ConformanceTest.java"
FIXTURE_REL="backend-runtime/stdlib-edge/time-now-millis-positive.deal"

STALE_EXPECTATION_CHANGED="FAIL (STALE staged entry: 'backend-runtime/stdlib-edge/time-now-millis-positive.deal' is now classified 'runtime-error E8004' instead of the pinned 'runtime-ok' — the ISSUE-0237 child applied its disposition: remove the registry entry)"
STALE_PASSING="FAIL (STALE staged entry: the fixture now passes 'runtime-ok' — the ISSUE-0237 child landed a passing disposition: remove the registry entry)"

# =========================================================================
# T1 baseline pin: the pinned pre-unit state every scratch run consumes.
# =========================================================================
pin_t1() {
  local line1 line2 line3 line4
  line1=$(sed -n '1p' "$PIN_FIXTURE")
  line2=$(sed -n '2p' "$PIN_FIXTURE")
  line3=$(sed -n '3p' "$PIN_FIXTURE")
  line4=$(sed -n '4p' "$PIN_FIXTURE")
  [ "$line1" = "// @spec: Standard library declarations — std/time" ] \
    || die "T1 pin broken: fixture :1 is not the canonical @spec line"
  [ "$line2" = "// @description: std/time.nowMillis returns a positive int-like timestamp" ] \
    || die "T1 pin broken: fixture :2"
  [ "$line3" = "// @expected: runtime-ok" ] \
    || die "T1 pin broken: fixture :3 is not '// @expected: runtime-ok'"
  [ "$line4" = "// @features: stdlib, time" ] \
    || die "T1 pin broken: fixture :4"
  grep -Fq 'if (now <= 0) { throw { code: "TEST_FAIL", message: "time.nowMillis not positive" }; }' "$PIN_FIXTURE" \
    || die "T1 pin broken: fixture now > 0 body"
  [ "$(sed -n '9p' "$PIN_TIME_LUA")" = 'time.nowMillis = __rt.function_("()->int", function()' ] \
    || die "T1 pin broken: std/time.lua :9"
  [ "$(sed -n '10p' "$PIN_TIME_LUA")" = '  return __rt.check_int(os.time() * 1000)' ] \
    || die "T1 pin broken: std/time.lua :10"
  [ "$(sed -n '165p' "$PIN_RUNNER")" = '        stagedFailure("backend-runtime/stdlib-edge/time-now-millis-positive.deal",' ] \
    || die "T1 pin broken: staged registration call site (test/ConformanceTest.java:165)"
  [ "$(sed -n '174p' "$PIN_RUNNER")" = '                + "lands its disposition pair");' ] \
    || die "T1 pin broken: staged registration tail (test/ConformanceTest.java:174)"
  for s in '"runtime-ok",' '"E8004",' '"ISSUE-0237",'; do
    grep -Fq "$s" "$PIN_RUNNER" || die "T1 pin broken: staged registration missing $s"
  done
  echo "  T1 baseline pin OK: fixture @expected runtime-ok at :3; std/time.lua :9-10 retained; staged entry present (test/ConformanceTest.java:164-176, call :165-174)"
}

# =========================================================================
# Scratch-tree construction (copy-only; every edit is scratch-local).
# =========================================================================
base_copy() {
  local scratch="$1"
  mkdir -p "$scratch/test"
  cp -a "$REPO/deal" "$scratch/deal"
  cp -a "$REPO/std" "$scratch/std"
  cp "$PIN_RUNNER" "$scratch/test/ConformanceTest.java"
  # Diff the scratch base against T1's pinned repository state; a
  # mismatch aborts the exercise (the exercise must consume T1).
  diff -q "$scratch/test/ConformanceTest.java" "$PIN_RUNNER" >/dev/null \
    || die "scratch base drift vs T1: test/ConformanceTest.java"
  diff -q "$scratch/std/time.lua" "$PIN_TIME_LUA" >/dev/null \
    || die "scratch base drift vs T1: std/time.lua"
  diff -q "$scratch/deal/runtime.lua" "$REPO/deal/runtime.lua" >/dev/null \
    || die "scratch base drift vs T1: deal/runtime.lua"
}

flip_fixture() {
  # Canonical D3 disposition header; body byte-identical to the shared
  # fixture (everything from the shared fixture's line 5 onward).
  local scratch="$1"
  mkdir -p "$scratch/coroot/backend-runtime/stdlib-edge"
  {
    printf '%s\n' \
      '// @spec: Standard library declarations — std/time' \
      '// @description: std/time.nowMillis under the v1.2 signed-int32 gate — the retained ()->int route raises E8004 for contemporary epoch milliseconds (locked TIME_NOW_MILLIS artifact)' \
      '// @expected: runtime-error E8004' \
      '// @features: stdlib, time, runtime-errors'
    tail -n +5 "$PIN_FIXTURE"
  } > "$scratch/coroot/$FIXTURE_REL"
  tail -n +5 "$scratch/coroot/$FIXTURE_REL" | diff - <(tail -n +5 "$PIN_FIXTURE") >/dev/null \
    || die "flipped fixture body differs from the shared fixture body"
  grep -Fxq '// @expected: runtime-error E8004' "$scratch/coroot/$FIXTURE_REL" \
    || die "flipped fixture header missing"
}

ok_clone() {
  # Verbatim copy of the shared fixture (runtime-ok) at the corpus-
  # relative path.
  local scratch="$1"
  mkdir -p "$scratch/coroot/backend-runtime/stdlib-edge"
  cp "$PIN_FIXTURE" "$scratch/coroot/$FIXTURE_REL"
  diff -q "$scratch/coroot/$FIXTURE_REL" "$PIN_FIXTURE" >/dev/null \
    || die "runtime-ok clone is not a verbatim copy of the shared fixture"
}

err_clone() {
  # Same body as the shared fixture; only the expectation line changes.
  local scratch="$1"
  ok_clone "$scratch"
  sed -i 's|^// @expected: runtime-ok$|// @expected: runtime-error E8004|' \
    "$scratch/coroot/$FIXTURE_REL"
  grep -Fxq '// @expected: runtime-error E8004' "$scratch/coroot/$FIXTURE_REL" \
    || die "runtime-error clone expectation line missing"
  sed 's|^// @expected: runtime-error E8004$|// @expected: runtime-ok|' \
    "$scratch/coroot/$FIXTURE_REL" | diff - "$PIN_FIXTURE" >/dev/null \
    || die "runtime-error clone body differs from the shared fixture body"
}

empty_registry() {
  # Remove the stagedFailure(...) registration from the scratch copy of
  # the runner so the compiled registry is empty.
  local scratch="$1"
  python3 - "$scratch/test/ConformanceTest.java" <<'PYEOF'
import sys
path = sys.argv[1]
lines = open(path).read().split('\n')
start = end = None
for i, l in enumerate(lines):
    if 'stagedFailure("backend-runtime/stdlib-edge/time-now-millis-positive.deal"' in l:
        start = i
        for j in range(i, min(i + 12, len(lines))):
            if lines[j].rstrip().endswith('lands its disposition pair");'):
                end = j
                break
        break
assert start is not None and end is not None, (start, end)
del lines[start:end + 1]
open(path, 'w').write('\n'.join(lines))
PYEOF
  if grep -q 'stagedFailure("backend-runtime/stdlib-edge' "$scratch/test/ConformanceTest.java"; then
    die "scratch registry not emptied (registration still present)"
  fi
  grep -Fq 'static {' "$scratch/test/ConformanceTest.java" \
    || die "scratch registry edit damaged the static block"
}

standin() {
  # Scratch passing std/time stand-in at the scratch tree's std/: an
  # int32-representable positive value through the current ()->int
  # wrapper shape (the runner copies std/*.lua from its own working
  # directory, so the stand-in must sit at the scratch tree's std/).
  local scratch="$1"
  cat > "$scratch/std/time.lua" <<'STDEOF'
-- Scratch stand-in for the ISSUE-0400 both-branch verification
-- (discarded with the scratch tree; never repository state).
local __rt = require("deal.runtime")

local time = {}

--- Returns a fixed int32-representable positive value through the
-- retained ()->int wrapper shape.
time.nowMillis = __rt.function_("()->int", function()
  return __rt.check_int(42)
end)

return time
STDEOF
  grep -Fq 'return __rt.check_int(42)' "$scratch/std/time.lua" \
    || die "scratch stand-in not in place at the scratch tree's std/"
}

compile_scratch() {
  local scratch="$1"
  ( cd "$scratch" && javac --release 25 -proc:none -d build \
      -cp /usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
      deal/source/*.java \
      deal/ast/*.java \
      deal/types/*.java \
      deal/descriptors/*.java \
      deal/diagnostics/*.java \
      deal/lexer/*.java \
      deal/parser/*.java \
      deal/checker/*.java \
      deal/codegen/*.java \
      deal/codegen/lua/*.java \
      deal/codegen/jvm/*.java \
      deal/codegen/js/*.java \
      deal/ir/*.java \
      deal/semantic/*.java \
      deal/semantic/ir/*.java \
      deal/module/*.java \
      deal/project/*.java \
      deal/identity/*.java \
      deal/Main.java \
      deal/test/containment/ContainedProcessBroker.java \
      deal/test/containment/PreflightCoordinator.java \
      deal/test/conformance/SidecarSchemaValidator.java \
      deal/project/ProjectLocatorTest.java \
      deal/module/ModuleIdentityResolverTest.java \
      test/ConformanceTest.java ) || die "scratch runner compile failed"
}

run_scratch() {
  local scratch="$1" log="$2"
  ( cd "$scratch" && java -ea -cp build deal.test.ConformanceTest "$scratch/coroot" ) > "$log" 2>&1
  return $?
}

assert_grep() {
  local log="$1" tag="$2" pattern="$3"
  if grep -Fq -- "$pattern" "$log"; then
    echo "  [$tag] pinned line present: $(grep -F -- "$pattern" "$log" | head -1)"
  else
    echo "  [$tag] MISSING pinned line: $pattern" >&2
    echo "  --- runner output ($log) ---" >&2
    cat "$log" >&2
    die "pinned result mismatch ($tag)"
  fi
}

repo_clean_after() {
  local tag="$1" scratch="$2"
  local status_file="$OUTDIR/repo-status-after-$tag.txt"
  git -C "$REPO" status --porcelain --untracked-files=all > "$status_file"
  if grep -qE 'test/ConformanceTest\.java|std/time\.lua|backend-runtime/stdlib-edge/time-now-millis-positive\.deal' "$status_file"; then
    echo "repository status after $tag:" >&2
    cat "$status_file" >&2
    die "repository state changed (protected file) after $tag"
  fi
  git -C "$REPO" diff --quiet HEAD -- test/ConformanceTest.java std/time.lua \
    test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal \
    || die "tracked diff in protected files after $tag"
  rm -rf "$scratch"
  if [ -e "$scratch" ]; then die "scratch tree not deleted after $tag"; fi
  echo "  [$tag] repository untouched (test/ConformanceTest.java, std/time.lua, fixture unmodified); scratch tree deleted"
}

# =========================================================================
# The six pinned configurations.
# =========================================================================

run_A() {
  local tag=A scratch rc
  echo "=== run A: branch-1 matching pair (isolated mechanism) ==="
  pin_t1
  scratch="$(mktemp -d /tmp/issue0400-runA.XXXXXX)"; LEFT_OVERS="$LEFT_OVERS $scratch"
  base_copy "$scratch"
  flip_fixture "$scratch"
  empty_registry "$scratch"
  diff -q "$scratch/std/time.lua" "$PIN_TIME_LUA" >/dev/null \
    || die "run A: std/time.lua was not left untouched"
  compile_scratch "$scratch"
  run_scratch "$scratch" "$OUTDIR/runA-branch1-matching.log"; rc=$?
  [ "$rc" -eq 0 ] || { cat "$OUTDIR/runA-branch1-matching.log"; die "run A: expected exit 0, got $rc"; }
  assert_grep "$OUTDIR/runA-branch1-matching.log" A-pass \
    'OK (found DEAL_ERROR_CODE: E8004)'
  assert_grep "$OUTDIR/runA-branch1-matching.log" A-zero-staged \
    'Total: 1, Passed: 1, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0'
  assert_grep "$OUTDIR/runA-branch1-matching.log" A-gate \
    'LuaJIT backend-runtime conformance (v1.2): 1/1 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)'
  repo_clean_after "$tag" "$scratch"
  echo "  run A pinned result: PASS on runtime-error E8004, zero staged failures, exit 0"
}

run_B() {
  local tag=B scratch rc
  echo "=== run B: branch-1 stale-entry (isolated; registry entry still present) ==="
  pin_t1
  scratch="$(mktemp -d /tmp/issue0400-runB.XXXXXX)"; LEFT_OVERS="$LEFT_OVERS $scratch"
  base_copy "$scratch"
  flip_fixture "$scratch"
  diff -q "$scratch/test/ConformanceTest.java" "$PIN_RUNNER" >/dev/null \
    || die "run B: runner copy drifted before compile (registry must stay present)"
  compile_scratch "$scratch"
  run_scratch "$scratch" "$OUTDIR/runB-branch1-stale-entry.log"; rc=$?
  [ "$rc" -eq 1 ] || { cat "$OUTDIR/runB-branch1-stale-entry.log"; die "run B: expected exit 1, got $rc"; }
  assert_grep "$OUTDIR/runB-branch1-stale-entry.log" B-promotion "$STALE_EXPECTATION_CHANGED"
  assert_grep "$OUTDIR/runB-branch1-stale-entry.log" B-failed \
    'Total: 1, Passed: 0, Failed: 1, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0'
  repo_clean_after "$tag" "$scratch"
  echo "  run B pinned result: gate failure naming the registry-entry removal, exit 1"
}

run_C() {
  local tag=C scratch rc
  echo "=== run C: branch-2 matching pair (empty-registry scratch runner configuration) ==="
  pin_t1
  scratch="$(mktemp -d /tmp/issue0400-runC.XXXXXX)"; LEFT_OVERS="$LEFT_OVERS $scratch"
  base_copy "$scratch"
  empty_registry "$scratch"
  standin "$scratch"
  ok_clone "$scratch"
  compile_scratch "$scratch"
  run_scratch "$scratch" "$OUTDIR/runC-branch2-matching.log"; rc=$?
  [ "$rc" -eq 0 ] || { cat "$OUTDIR/runC-branch2-matching.log"; die "run C: expected exit 0, got $rc"; }
  assert_grep "$OUTDIR/runC-branch2-matching.log" C-pass \
    "[backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK"
  assert_grep "$OUTDIR/runC-branch2-matching.log" C-summary \
    'Total: 1, Passed: 1, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0'
  repo_clean_after "$tag" "$scratch"
  echo "  run C pinned result: matching clone passes, exit 0"
}

run_D() {
  local tag=D scratch rc
  echo "=== run D: branch-2 mismatched pair (empty-registry scratch runner configuration) ==="
  pin_t1
  scratch="$(mktemp -d /tmp/issue0400-runD.XXXXXX)"; LEFT_OVERS="$LEFT_OVERS $scratch"
  base_copy "$scratch"
  empty_registry "$scratch"
  standin "$scratch"
  err_clone "$scratch"
  compile_scratch "$scratch"
  run_scratch "$scratch" "$OUTDIR/runD-branch2-mismatched.log"; rc=$?
  [ "$rc" -eq 1 ] || { cat "$OUTDIR/runD-branch2-mismatched.log"; die "run D: expected exit 1, got $rc"; }
  assert_grep "$OUTDIR/runD-branch2-mismatched.log" D-no-E8004 \
    'FAIL (expected DEAL_ERROR_CODE: E8004, got: )'
  assert_grep "$OUTDIR/runD-branch2-mismatched.log" D-summary \
    'Total: 1, Passed: 0, Failed: 1, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0'
  repo_clean_after "$tag" "$scratch"
  echo "  run D pinned result: mismatched clone fails — no E8004 is raised, exit 1"
}

run_E() {
  local tag=E scratch rc
  echo "=== run E: unmodified runner interception — passing clone records the stale passing promotion ==="
  pin_t1
  scratch="$(mktemp -d /tmp/issue0400-runE.XXXXXX)"; LEFT_OVERS="$LEFT_OVERS $scratch"
  base_copy "$scratch"
  diff -q "$scratch/test/ConformanceTest.java" "$PIN_RUNNER" >/dev/null \
    || die "run E: unmodified runner required"
  standin "$scratch"
  ok_clone "$scratch"
  compile_scratch "$scratch"
  run_scratch "$scratch" "$OUTDIR/runE-unmodified-matching-interception.log"; rc=$?
  [ "$rc" -eq 1 ] || { cat "$OUTDIR/runE-unmodified-matching-interception.log"; die "run E: expected exit 1, got $rc"; }
  assert_grep "$OUTDIR/runE-unmodified-matching-interception.log" E-stale-passing "$STALE_PASSING"
  repo_clean_after "$tag" "$scratch"
  echo "  run E pinned result: interception before expectation evaluation — stale passing promotion, exit 1"
}

run_F() {
  local tag=F scratch rc
  echo "=== run F: unmodified runner interception — mismatched clone records the stale expectation-changed promotion ==="
  pin_t1
  scratch="$(mktemp -d /tmp/issue0400-runF.XXXXXX)"; LEFT_OVERS="$LEFT_OVERS $scratch"
  base_copy "$scratch"
  diff -q "$scratch/test/ConformanceTest.java" "$PIN_RUNNER" >/dev/null \
    || die "run F: unmodified runner required"
  standin "$scratch"
  err_clone "$scratch"
  compile_scratch "$scratch"
  run_scratch "$scratch" "$OUTDIR/runF-unmodified-mismatched-interception.log"; rc=$?
  [ "$rc" -eq 1 ] || { cat "$OUTDIR/runF-unmodified-mismatched-interception.log"; die "run F: expected exit 1, got $rc"; }
  assert_grep "$OUTDIR/runF-unmodified-mismatched-interception.log" F-stale-changed "$STALE_EXPECTATION_CHANGED"
  repo_clean_after "$tag" "$scratch"
  echo "  run F pinned result: interception before expectation evaluation — stale expectation-changed promotion, exit 1"
}

# =========================================================================
# Execute the six pinned configurations.
# =========================================================================
echo "ISSUE-0400 both-branch verification — isolated scratch exercise"
echo "repository: $REPO"
echo "evidence dir: $OUTDIR"
echo

pin_t1

run_A
run_B
run_C
run_D
run_E
run_F

echo
echo "ALL SIX PINNED RESULTS REPRODUCED BY THEIR SPECIFIED MECHANISMS"
echo "  (branch-1 matching pass / branch-1 stale-entry failure / branch-2 matching pass / branch-2 mismatched failure / two unmodified-runner interceptions)"
echo "repository files never modified; every scratch tree deleted"
echo "evidence: $OUTDIR"
