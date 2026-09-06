#!/bin/bash
# tools/test/test-strict-output-assert.sh — the S4 assertion matrix
# battery (release-r0-r3-strict-gate-mechanics Verification 2).
#
# The synthetic green log carrying exactly the mandated zero-count
# segments passes with exit 0 and no output; one synthetic log per
# violation class fails with "STRICT_OUTPUT_VIOLATION: <offending line>"
# on stderr and exit 1; the zero-count segments never violate
# (anti-false-positive proof).
#
# Runs the committed helper over scratch logs in a temporary directory;
# creates no repository state.

cd "$(dirname "$0")/../.." || exit 1
ASSERT="tools/strict-output-assert.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/strict-output-assert-test.XXXXXX")" \
  || exit 1
trap 'rm -rf "$WORK"' EXIT

PASSES=0
FAILURES=0

fail() {
  echo "FAIL: $1" >&2
  FAILURES=$((FAILURES + 1))
}

check_green() {
  local desc="$1" content="$2" out err status
  printf '%s\n' "$content" > "$WORK/case.log"
  out="$WORK/case.out"; err="$WORK/case.err"
  "$ASSERT" "$WORK/case.log" >"$out" 2>"$err"
  status=$?
  if [ "$status" -ne 0 ]; then
    fail "$desc: expected exit 0, got $status (stderr: $(cat "$err"))"
    return
  fi
  if [ -s "$out" ] || [ -s "$err" ]; then
    fail "$desc: expected no output (stdout: $(cat "$out"); stderr: $(cat "$err"))"
    return
  fi
  PASSES=$((PASSES + 1))
}

check_violation() {
  # args: desc, content, expected-offending-line (default: first line)
  local desc="$1" content="$2" expected="$3" out err status
  printf '%s\n' "$content" > "$WORK/case.log"
  out="$WORK/case.out"; err="$WORK/case.err"
  "$ASSERT" "$WORK/case.log" >"$out" 2>"$err"
  status=$?
  if [ "$status" -ne 1 ]; then
    fail "$desc: expected exit 1, got $status"
    return
  fi
  if [ -s "$out" ]; then
    fail "$desc: expected no stdout, got: $(cat "$out")"
    return
  fi
  if [ -z "$expected" ]; then
    expected="${content%%$'\n'*}"
  fi
  expected="STRICT_OUTPUT_VIOLATION: $expected"
  if [ "$(cat "$err")" != "$expected" ]; then
    fail "$desc: expected stderr [$expected], got [$(cat "$err")]"
    return
  fi
  PASSES=$((PASSES + 1))
}

# --- Green log: the mandated zero-count segments only ---------------------
GREEN_LOG='
Total: 30, Passed: 30, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
Companions (classified support modules): 2
  Frontend conformance (v1.2 grammar and semantics): 20/20 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
  LuaJIT backend-runtime conformance (v1.2): 10/10 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
Discovered 30 conformance test(s): 20 frontend-classified, 10 JVM-applicable backend-runtime, 0 skipped (classified), 0 known-fail (tracked)
Discovered 30 conformance test(s): 20 frontend-classified, 10 JVM-applicable backend-runtime, skipped 0 (classified), known-fail 0 (tracked)
Skipped backend-runtime groups (every skip carries a reason and a gap id):

Known-fail groups (tracked follow-up issues):

=== All Tests Passed ==='
check_green "green log with the mandated zero-count segments" "$GREEN_LOG"

# --- Violation classes, one synthetic log each ----------------------------
check_violation "WARNING line" \
  "WARNING: luajit not found, skipping runtime library tests"
check_violation "SKIP ( marker line" \
  "SKIP (LuaJIT not available)"
check_violation "Classification failures line" \
  "Classification failures (unclassified skips are removed by the v1.2 gate):"
check_violation "non-blank gap line between the group headings" '
Skipped backend-runtime groups (every skip carries a reason and a gap id):
  GAP-02    backend-runtime gap (tracked)                               3 test(s)
Known-fail groups (tracked follow-up issues):' \
  '  GAP-02    backend-runtime gap (tracked)                               3 test(s)'
check_violation "GATE FAILURE line" \
  "GATE FAILURE: backend-runtime pass rate 12/15 below the required 1/1"
check_violation "Skipped: 1" \
  "Total: 30, Passed: 29, Failed: 0, Skipped: 1, KnownFailures (tracked): 0, StagedFailures (tracked): 0"
check_violation "KnownFailures (tracked): 1" \
  "Total: 30, Passed: 29, Failed: 0, Skipped: 0, KnownFailures (tracked): 1, StagedFailures (tracked): 0"
check_violation "1 skipped" \
  "  Frontend conformance (v1.2 grammar and semantics): 19/20 passed, 0 failed, 1 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)"
check_violation "1 known-fail" \
  "  Frontend conformance (v1.2 grammar and semantics): 19/20 passed, 0 failed, 0 skipped, 1 known-fail (tracked), 0 staged-fail (tracked)"
check_violation "1 skipped (classified)" \
  "Discovered 30 conformance test(s): 20 frontend-classified, 9 JVM-applicable backend-runtime, 1 skipped (classified), 0 known-fail (tracked)"
check_violation "skipped 1 (classified)" \
  "Discovered 30 conformance test(s): 20 frontend-classified, 9 JVM-applicable backend-runtime, skipped 1 (classified), 0 known-fail (tracked)"
check_violation "1 known-fail (tracked)" \
  "Discovered 30 conformance test(s): 20 frontend-classified, 9 JVM-applicable backend-runtime, 0 skipped (classified), 1 known-fail (tracked)"
check_violation "known-fail 1 (tracked)" \
  "Discovered 30 conformance test(s): 20 frontend-classified, 9 JVM-applicable backend-runtime, 0 skipped (classified), known-fail 1 (tracked)"
check_violation "1 known-fail fixture(s)" \
  "    ISSUE-0111: 1 known-fail fixture(s)"

echo ""
echo "strict-output-assert matrix: $PASSES passed, $FAILURES failed"
if [ "$FAILURES" -gt 0 ]; then
  exit 1
fi
