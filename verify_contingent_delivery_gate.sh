#!/bin/bash
#
# ISSUE-0401 — Contingent disposition change-set delivery gate
# (pre-delivery verification of the pending-resolution state).
#
# Evaluates the delivery gate condition for the locked std/time.nowMillis
# disposition change sets at the current HEAD (luajit-time-selector-
# disposition D1/D2, Contracts; the task's gate condition):
#
#   1. T1 baseline: the pinned pre-activation state must be intact —
#      fixture `// @expected: runtime-ok` (canonical @spec/@description/
#      @features lines and the unchanged `now > 0` body), std/time.lua
#      :9-10 byte-identical (retained ()->int route), the stagedFailure
#      registration present in test/ConformanceTest.java, the pin test's
#      pre-activation assertions present, the lock halves present
#      (TIME_NOW_MILLIS reserved; STDLIB_TIME_CONFLICT routing marker;
#      planner rule 2; the closed four-arm detector).
#   2. Landing-record scan: every authoritative resolution record
#      (test/ConformanceTest.java:144-161 registry doc,
#      docs/v1.2-conformance-status.md, the fixture header, the two
#      test_stdlib.lua nowMillis E8004 cases) must record the resolution
#      as NOT landed. If any record names a landed disposition pair,
#      this pending-state pin is superseded — the script aborts (the
#      disposition change set is delivered only inside the disposition-
#      application unit after the landing, never by this script).
#   3. History discipline: no disposition edit (fixture flip, registry
#      removal, pin-test update, std/time.lua change) may have landed
#      before the resolution landing — verified from git history over
#      the protected files.
#   4. Combined behavior (T2-pinned mechanism): the committed
#      verify_both_branches_scratch.sh exercise runs to completion and
#      reproduces all six pinned results; the branch-1 matching pair
#      (delivered change set + retained implementation + emptied
#      registry) must pass as `runtime-error E8004` with zero staged
#      failures. This step fails if T1's pinned baseline or T2's pinned
#      mechanism is broken.
#   5. Repository untouched: clean status and zero tracked diff on the
#      protected files after the exercise.
#
# Exit 0 iff the resolution is pending, no disposition edit exists, the
# T1/T2 pins hold, the combined-behavior result is reproduced, and the
# repository is clean — i.e. the gate condition holds and the
# determination is: no delivery, no edit, no landing.
#
# Usage: verify_contingent_delivery_gate.sh [EVIDENCE_DIR]
#
set -u

REPO="$(cd "$(dirname "$0")" && pwd)"
OUTDIR="${1:-}"
if [ -z "$OUTDIR" ]; then
  OUTDIR="$(mktemp -d /tmp/issue0401-evidence.XXXXXX)"
fi
mkdir -p "$OUTDIR"
OUTDIR="$(cd "$OUTDIR" && pwd)"

die() {
  echo "ABORT: $*" >&2
  exit 1
}

for tool in bash sed grep git diff; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool missing: $tool"
done

PIN_FIXTURE="$REPO/test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal"
PIN_TIME_LUA="$REPO/std/time.lua"
PIN_RUNNER="$REPO/test/ConformanceTest.java"
PIN_PIN_TEST="$REPO/test/StdlibTimePreActivationPinTest.java"
PIN_STATUS_DOC="$REPO/docs/v1.2-conformance-status.md"
PIN_STDLIB_SUITE="$REPO/test_stdlib.lua"
RECORD="$REPO/CONTINGENT_DISPOSITION_DELIVERY_RECORD.md"

# =========================================================================
# 1. T1 baseline pin — the pre-activation state every later check consumes.
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
  if grep -Fq 'runtime-error' "$PIN_FIXTURE"; then
    die "T1 pin broken: the fixture contains runtime-error text (a disposition flip landed?)"
  fi
  [ "$(grep -c '@expected' "$PIN_FIXTURE")" = "1" ] \
    || die "T1 pin broken: the fixture has != 1 @expected line"

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

  grep -Fq '"// @expected: runtime-ok".equals(ls.get(2))' "$PIN_PIN_TEST" \
    || die "T1 pin broken: pin-test testFixtureHeader pre-activation @expected assertion missing"
  grep -Fq '"the fixture still declares @expected: runtime-ok"' "$PIN_PIN_TEST" \
    || die "T1 pin broken: pin-test testFixtureHeader runtime-ok message missing"
  grep -Fq '"each ±(2^53-1) bound appears exactly once' "$PIN_PIN_TEST" \
    || die "T1 pin broken: pin-test testRuntimeSeam ±(2^53-1) seam pin missing"
  grep -Fq '"std/time.lua:9 is the retained ()->int wrapper line"' "$PIN_PIN_TEST" \
    || die "T1 pin broken: pin-test testRetainedLuaImplementation :9 pin missing"

  [ "$(sed -n '51p' "$REPO/deal/semantic/ir/StdlibFunctionId.java")" = '        "TIME_NOW_MILLIS"' ] \
    || die "T1 pin broken: TIME_NOW_MILLIS reserved (deal/semantic/ir/StdlibFunctionId.java:51)"
  grep -Fq '// rule 2: never shared in any purpose' "$REPO/deal/semantic/MigrationPlanner.java" \
    || die "T1 pin broken: MigrationPlanner rule 2"
  grep -Fq 'Routing marker: the module references std/time.nowMillis (never lowered)' \
    "$REPO/deal/semantic/ir/SemanticCapability.java" \
    || die "T1 pin broken: STDLIB_TIME_CONFLICT routing marker"
  grep -Fq '<b>Closed capability claims.</b>' "$REPO/deal/semantic/LoweringSupport.java" \
    || die "T1 pin broken: LoweringSupport closed four-arm detector"

  echo "  T1 baseline pin OK: fixture @expected runtime-ok at :3 (body unchanged); std/time.lua :9-10 retained; staged entry present (test/ConformanceTest.java:165-174); pin-test pre-activation assertions present; lock halves present"
}

# =========================================================================
# 2. Landing-record scan — the resolution must be recorded as NOT landed.
# =========================================================================
scan_landing_records() {
  echo "-- resolution landing record scan --"

  # a) the runner's registry doc pins the frozen state until the landing.
  grep -Fq '(ISSUE-0237) lands its disposition pair. This runner records the' "$PIN_RUNNER" \
    || die "landing record changed: test/ConformanceTest.java registry doc no longer records the pending state"
  echo "  [runner registry doc] pending: '... stays frozen until the delegated time-selector child (ISSUE-0237) lands its disposition pair' (test/ConformanceTest.java:144-161)"

  # b) the conformance status doc records the unresolved interim state.
  grep -Fq 'Until the ISSUE-0237 resolution lands its disposition pair, the LuaJIT' "$PIN_STATUS_DOC" \
    || die "landing record changed: docs/v1.2-conformance-status.md no longer records the pending state"
  echo "  [conformance status doc] pending: 'Until the ISSUE-0237 resolution lands its disposition pair, the LuaJIT backend-runtime gate additionally records one tracked staged failure' (docs/v1.2-conformance-status.md:24-28)"

  # c) the fixture keeps its runtime-ok header (checked in pin_t1).
  echo "  [fixture header] pending: '// @expected: runtime-ok' at :3, no runtime-error text, exactly one @expected line"

  # d) the direct suite keeps asserting the locked E8004 artifact.
  grep -Fq 'test("time.nowMillis raises E8004 under the signed-int32 gate", function()' "$PIN_STDLIB_SUITE" \
    || die "landing record changed: test_stdlib.lua nowMillis E8004 case missing"
  grep -Fq 'test("time.nowMillis ratio case raises E8004 under the signed-int32 gate", function()' "$PIN_STDLIB_SUITE" \
    || die "landing record changed: test_stdlib.lua nowMillis ratio E8004 case missing"
  echo "  [direct suite] pending: the two nowMillis cases assert the locked E8004 artifact (test_stdlib.lua:1111-1117)"

  echo "  determination: no authoritative record names a landed disposition pair — the resolution is pending; no delivery, no edit, no landing"
}

# =========================================================================
# 3. History discipline — no disposition edit landed before the landing.
# =========================================================================
check_history_discipline() {
  echo "-- history discipline (git) --"
  local n

  n=$(git -C "$REPO" log -S 'stagedFailure("backend-runtime/stdlib-edge' --oneline -- test/ConformanceTest.java | wc -l)
  [ "$n" = "1" ] \
    || die "history discipline broken: the stagedFailure registration appeared/vanished in $n commit(s) of test/ConformanceTest.java (expected exactly the one ISSUE-0332 addition)"
  echo "  [registry] the stagedFailure registration was added exactly once and never removed (test/ConformanceTest.java history)"

  n=$(git -C "$REPO" log -S 'runtime-error E8004' --oneline -- test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal | wc -l)
  [ "$n" = "0" ] \
    || die "history discipline broken: the fixture's expectation was edited ($n commit(s) mention runtime-error E8004)"
  echo "  [fixture] the shared fixture's @expected line was never edited to runtime-error E8004 (no disposition edit landed)"

  n=$(git -C "$REPO" log --format=%h -1 -- std/time.lua)
  case "$n" in
    4eba239*) : ;;
    *) die "history discipline broken: std/time.lua last changed outside the pre-epic rewrite ($n)" ;;
  esac
  echo "  [std/time.lua] last commit touching std/time.lua is the pre-epic v1.0 rewrite (4eba239) — byte-identical through the epic"

  git -C "$REPO" diff --quiet HEAD -- \
    test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal \
    std/time.lua test/ConformanceTest.java test/StdlibTimePreActivationPinTest.java \
    deal/runtime.lua deal/runtime.js test_stdlib.lua \
    deal/semantic/ir/StdlibFunctionId.java deal/semantic/ir/SemanticCapability.java \
    deal/semantic/MigrationPlanner.java deal/semantic/LoweringSupport.java \
    || die "history discipline broken: tracked diff present on a protected file"
  echo "  [working tree] zero tracked diff on every protected surface (fixture, std/time.lua, runner, pin test, deal/runtime.lua, deal/runtime.js, test_stdlib.lua, lock files)"
}

# =========================================================================
# 4. Combined behavior — the T2-pinned scratch mechanism.
# =========================================================================
check_combined_behavior() {
  echo "-- combined behavior (T2-pinned scratch mechanism) --"
  local t2out="$OUTDIR/t2-exercise" rc
  bash "$REPO/verify_both_branches_scratch.sh" "$t2out"
  rc=$?
  [ "$rc" -eq 0 ] || die "combined-behavior step failed: verify_both_branches_scratch.sh exited $rc (T1 baseline or T2 mechanism broken)"

  local logA="$t2out/runA-branch1-matching.log"
  [ -f "$logA" ] || die "combined-behavior step failed: run A log missing"
  grep -Fq 'OK (found DEAL_ERROR_CODE: E8004)' "$logA" \
    || die "combined-behavior step failed: run A missing the pinned E8004 PASS line"
  grep -Fq 'Total: 1, Passed: 1, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0' "$logA" \
    || die "combined-behavior step failed: run A missing the zero-staged summary"
  grep -Fq 'LuaJIT backend-runtime conformance (v1.2): 1/1 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)' "$logA" \
    || die "combined-behavior step failed: run A missing the zero-fail gate line"
  echo "  [run A] branch-1 matching pair passes: 'OK (found DEAL_ERROR_CODE: E8004)', zero staged failures, exit 0"
  echo "  [runs B-F] all remaining pinned results reproduced (stale-entry failure, branch-2 matching pass, branch-2 mismatched failure, two unmodified-runner interceptions)"
}

# =========================================================================
# 5. Repository untouched.
# =========================================================================
check_repo_clean() {
  echo "-- repository state --"
  local status
  status=$(git -C "$REPO" status --porcelain --untracked-files=all)
  if [ -n "$status" ]; then
    echo "$status" >&2
    die "repository not clean after the exercise"
  fi
  echo "  git status --porcelain --untracked-files=all: empty"
}

# =========================================================================
# Execute.
# =========================================================================
echo "ISSUE-0401 contingent delivery gate — pre-delivery verification (pending-resolution state)"
echo "repository: $REPO"
echo "evidence dir: $OUTDIR"
echo

[ -f "$RECORD" ] || die "the contingent delivery record is missing: $RECORD"

pin_t1
echo
scan_landing_records
echo
check_history_discipline
echo
check_combined_behavior
echo
check_repo_clean

REV=$(git -C "$REPO" rev-parse HEAD)
echo
echo "DELIVERY GATE DETERMINATION (canonical HEAD $REV)"
echo "  resolution landing record: pending — no authoritative record names a landed disposition pair"
echo "  action: no delivery, no edit, no landing (the gate condition; the contingent change sets stay non-applied)"
echo "  T1 pre-activation state: intact at the canonical HEAD"
echo "  contingent change sets: authored in $RECORD, delivered only inside the disposition-application unit after the landing"
echo "  combined behavior: branch-1 matching pair PASS runtime-error E8004 with zero staged failures (T2 mechanism); all six pinned results reproduced"
echo "  repository: clean; zero disposition edits in the working tree and the canonical history"
echo "evidence: $OUTDIR"
