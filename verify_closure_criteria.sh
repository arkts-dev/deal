#!/bin/bash
#
# ISSUE-0479 — Closure-verification criteria, executable form
# (ISSUE-0402 acceptance remediation, CHECK-000028 finding 3).
#
# Asserts the re-pinned post-unit closure criteria against a captured
# ./run_tests.sh log. The numeric totals are read from the executed
# post-unit corpus and recorded as evidence — never pre-pinned. (The
# superseded ISSUE-0421 criteria pinned `Total: 387, Passed: 387` and
# `269/269 passed` — design-time derivations no post-unit state of the
# executed corpus can produce; see CLOSURE_VERIFICATION_CRITERIA.md.)
# The binding assertions are the four zeros on the backend-runtime phase
# line and in the summary, the time-fixture PASS under the landed
# branch through the standard dispatch (expectation(fixture) == landed
# nowMillis behavior), the follow-up zero line, no GATE FAILURE line,
# the pin test on the post-activation header, and ./run_tests.sh exit 0.
#
# Criteria checked (each names its unmet line on failure):
#   1. Summary four zeros: `Total: N, Passed: N, Failed: 0, Skipped: 0,
#      KnownFailures (tracked): 0, StagedFailures (tracked): 0` with
#      Total == Passed (N recorded from the executed run);
#   2. Phase four zeros: `LuaJIT backend-runtime conformance (v1.2):
#      M/M passed, 0 failed, 0 skipped, 0 known-fail (tracked),
#      0 staged-fail (tracked)` (M recorded from the executed run);
#   3. Follow-up line `Tracked v1.2 follow-up issues: none — full v1.2
#      conformance`; no tracked known-fail or staged-failures block;
#   4. Time-fixture PASS under the landed branch, proven by the
#      ConformanceTest lane's own prefix write plus the absence of any
#      failure result for the fixture — inside the `=== DEAL v1.2
#      Conformance Test Suite ===` … `=== Conformance Summary ===`
#      window:
#      (a) the CT prefix write `  [<path>] LEGACY-AUTHORITY
#          (legacy-regression; zero v1.2 credit) ` — the write WITH the
#          trailing space ConformanceTest.java:493-497 prints before
#          the result — appears exactly once. The trailing space is the
#          lane attribution: JvmConformanceTest.java:1357-1364 prints
#          the same path+infix as its own whole line WITHOUT the
#          trailing space, so the JVM lane's identical copy can never
#          match wherever its concurrent line lands; (b) no infix-keyed
#          FAIL/ERROR/STAGED-FAIL/SKIP/KNOWN-FAIL line for the fixture
#          prints, and when the prefix line carries no result (the
#          prefix and result are two separate writes that concurrent
#          background-suite output can splice apart), the line
#          immediately following it is not a failure-result line; (c)
#          the fixture's on-disk @expected is exactly one of the two
#          dispositions (`runtime-error E8004` — branch 1 — or
#          `runtime-ok` — branch 2), and when the result text is
#          visible on the prefix line it must match the on-disk
#          expectation. Combined with the four zeros (criteria 1-2: a
#          FAIL increments failed, a SKIP increments skipped, a staged
#          result prints and increments stagedFailures, a known-fail
#          increments the known-fail counter) and criterion 5, prefix
#          presence plus absence of failure results proves the fixture
#          recorded PASS — expectation(fixture) == landed nowMillis
#          behavior — without depending on where concurrent output
#          spliced the result text. The first-pathless-result
#          attribution of review round 1 is removed entirely: it
#          falsely satisfied the criterion from another fixture's
#          spliced OK and falsely rejected a correct spliced post-unit
#          closure log;
#   5. No `GATE FAILURE` line anywhere in the run;
#   6. Pin-test section `=== std/time.nowMillis Pre-Activation Pin
#      (ISSUE-0369) ===` with a `Passed: <K>, Failed: 0` verdict
#      inside the section;
#   7. `=== All Tests Passed ===` (the run_tests.sh exit-0 marker).
#
# On the pre-unit pending state the script exits 1 by design — the
# closure criteria are never asserted before the disposition-application
# unit and the five known-fail promotions land (Pending-state contract,
# luajit-gate-closure). On a post-unit closure log it exits 0.
#
# Usage: verify_closure_criteria.sh <run_tests.sh-log> [run_exit_code]
#
set -u

LOG="${1:-}"
EXIT_CODE="${2:-0}"
if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
  echo "usage: verify_closure_criteria.sh <run_tests.sh-log> [run_exit_code]" >&2
  exit 2
fi
if [ "$EXIT_CODE" != "0" ]; then
  echo "FAIL: run exit code — recorded exit $EXIT_CODE, closure requires 0" >&2
  exit 1
fi

REPO="$(cd "$(dirname "$0")" && pwd)"
FIXTURE="$REPO/test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal"
FIXTURE_REL="backend-runtime/stdlib-edge/time-now-millis-positive.deal"
N="-"
M="-"

FAILED=0
fail() {
  echo "FAIL: $*" >&2
  FAILED=1
}
pass() {
  echo "PASS: $*"
}

# The deal.test.ConformanceTest window: from its launch header to its
# summary header (inclusive). The fixture and counter lines are
# asserted inside this window only. The JVM lane and the backend
# conformance suites launch in background into the same shared log
# (tools/gate-manifest.sh) and their lines interleave inside this line
# range (verified at this HEAD), so criterion 4 keys its fixture
# evidence to the ConformanceTest-only prefix write — lane attribution
# by line shape, not the window alone.
CT_SECTION="$(awk '/^=== DEAL v1.2 Conformance Test Suite ===$/ {f=1} f{print} f && /^=== Conformance Summary ===$/{exit}' "$LOG")"

# 1. Summary four zeros; N recorded at execution time.
SUMMARY="$(grep -E '^Total: [0-9]+, Passed: [0-9]+, Failed: [0-9]+, Skipped: [0-9]+, KnownFailures \(tracked\): [0-9]+, StagedFailures \(tracked\): [0-9]+$' "$LOG" | head -1)"
if [ -z "$SUMMARY" ]; then
  fail "summary four zeros — no matching summary line found"
else
  if [[ "$SUMMARY" =~ ^Total:\ ([0-9]+),\ Passed:\ ([0-9]+),\ Failed:\ ([0-9]+),\ Skipped:\ ([0-9]+),\ KnownFailures\ \(tracked\):\ ([0-9]+),\ StagedFailures\ \(tracked\):\ ([0-9]+)$ ]]; then
    N="${BASH_REMATCH[1]}"
    if [ "${BASH_REMATCH[1]}" != "${BASH_REMATCH[2]}" ]; then
      fail "summary four zeros — Total (${BASH_REMATCH[1]}) != Passed (${BASH_REMATCH[2]}) in: $SUMMARY"
    elif [ "${BASH_REMATCH[3]}" != "0" ] || [ "${BASH_REMATCH[4]}" != "0" ] \
        || [ "${BASH_REMATCH[5]}" != "0" ] || [ "${BASH_REMATCH[6]}" != "0" ]; then
      fail "summary four zeros — non-zero counter in: $SUMMARY"
    else
      pass "summary four zeros — recorded at execution time: $SUMMARY"
    fi
  else
    fail "summary four zeros — unparsable line: $SUMMARY"
  fi
fi

# 2. Phase four zeros; M recorded at execution time.
PHASE="$(grep -E '^ *LuaJIT backend-runtime conformance \(v1\.2\): [0-9]+/[0-9]+ passed, [0-9]+ failed, [0-9]+ skipped, [0-9]+ known-fail \(tracked\), [0-9]+ staged-fail \(tracked\)$' "$LOG" | head -1)"
if [ -z "$PHASE" ]; then
  fail "phase four zeros — no matching phase line found"
else
  if [[ "$PHASE" =~ ^\ *LuaJIT\ backend-runtime\ conformance\ \(v1\.2\):\ ([0-9]+)/([0-9]+)\ passed,\ ([0-9]+)\ failed,\ ([0-9]+)\ skipped,\ ([0-9]+)\ known-fail\ \(tracked\),\ ([0-9]+)\ staged-fail\ \(tracked\)$ ]]; then
    M="${BASH_REMATCH[2]}"
    if [ "${BASH_REMATCH[1]}" != "${BASH_REMATCH[2]}" ]; then
      fail "phase four zeros — ${BASH_REMATCH[1]}/${BASH_REMATCH[2]} passed in: $PHASE"
    elif [ "${BASH_REMATCH[3]}" != "0" ] || [ "${BASH_REMATCH[4]}" != "0" ] \
        || [ "${BASH_REMATCH[5]}" != "0" ] || [ "${BASH_REMATCH[6]}" != "0" ]; then
      fail "phase four zeros — non-zero counter in: $PHASE"
    else
      pass "phase four zeros — recorded at execution time: $PHASE"
    fi
  else
    fail "phase four zeros — unparsable line: $PHASE"
  fi
fi

# 3. Follow-up zero line; no tracked blocks.
if grep -q 'Tracked v1.2 follow-up issues: none — full v1.2 conformance' "$LOG"; then
  pass "follow-up line — 'Tracked v1.2 follow-up issues: none — full v1.2 conformance'"
else
  fail "follow-up line — 'Tracked v1.2 follow-up issues: none — full v1.2 conformance' not found"
fi
if grep -q 'Tracked v1.2 follow-up issues (intentionally unsupported cases' "$LOG"; then
  fail "follow-up line — tracked known-fail block prints (closure requires the five ISSUE-0111-tagged promotions gone)"
fi
if grep -q 'Tracked v1.2 staged failures (design-sanctioned interim states' "$LOG"; then
  fail "follow-up line — tracked staged-failures block prints (closure requires zero staged entries)"
fi

# 4. Time-fixture PASS under the landed branch — prefix presence plus
#    absence of failure results (the proof does not depend on where
#    concurrent background-suite output spliced the result text).
#
#    Lane attribution by write shape. ConformanceTest prints the
#    prefix `  [<path>] LEGACY-AUTHORITY (legacy-regression; zero
#    v1.2 credit) ` — WITH one trailing space — and the result as two
#    separate writes (ConformanceTest.java:493-497). The JVM lane
#    prints the same path+infix as its own whole line WITHOUT the
#    trailing space (JvmConformanceTest.java:1357-1364; log() at
#    :616-623 printlns the line), and BackendConformanceTest.java:1069
#    prints infix lines only for JSON case names, never for this
#    corpus path. The trailing-space signature therefore matches only
#    ConformanceTest's own prefix write; the JVM lane's identical
#    infix line can never inflate the exact-once count, whether it
#    lands inside or outside the CT window (verified against the
#    executed capture: the CT prefix line carries `credit) ` and the
#    JVM lane's line ends at `credit)`).
AUTH="LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)"
PFX_GREP='^  \[backend-runtime/stdlib-edge/time-now-millis-positive\.deal\] LEGACY-AUTHORITY \(legacy-regression; zero v1\.2 credit\) '
PFX_RAW='  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) '
FAIL_GREP="$PFX_GREP(FAIL \(|ERROR:|STAGED-FAIL \(|SKIP \(|KNOWN-FAIL \()"

PREFIX_COUNT="$(printf '%s\n' "$CT_SECTION" | grep -cE "$PFX_GREP")"
FAIL_LINES="$(printf '%s\n' "$CT_SECTION" | grep -E "$FAIL_GREP" | head -3)"

# Classify the fixture's prefix line: either its result is visible on
# the same line (the CT's next write after the prefix is its result
# print — the CT is single-threaded, and a foreign write between the
# two always ends with its own newline, pushing the CT result to the
# following line), or the prefix carries no result and the result was
# displaced onto the immediately following line. Only that one
# following line is inspected: results displaced for OTHER fixtures
# and other foreign lines beyond it are never attributed to the time
# fixture (review round 2: the first-pathless-result-anywhere
# attribution was unsound in both directions and is removed).
DISPLACED="$(printf '%s\n' "$CT_SECTION" | awk -v pf="$PFX_RAW" '
    !done {
        if (index($0, pf) != 1) next
        done = 1
        rest = substr($0, 1 + length(pf))
        if (rest ~ /^(OK( \(|$)|FAIL \(|ERROR:|STAGED-FAIL \(|SKIP \(|KNOWN-FAIL \()/) {
            print "ONLINE=" rest
        } else {
            want = 1
        }
        next
    }
    want { print "NEXT=" $0; want = 0 }
')"

ONLINE=""
NEXT_LINE=""
while IFS= read -r dl; do
  case "$dl" in
    ONLINE=*) ONLINE="${dl#ONLINE=}" ;;
    NEXT=*) NEXT_LINE="${dl#NEXT=}" ;;
  esac
done <<< "$DISPLACED"

CRIT4_FAILED=0
if [ "$PREFIX_COUNT" != "1" ]; then
  fail "time-fixture PASS — ConformanceTest prefix write: expected the '  [$FIXTURE_REL] $AUTH ' line exactly once inside the CT window (found $PREFIX_COUNT such line(s))"
  CRIT4_FAILED=1
fi
if [ -n "$FAIL_LINES" ]; then
  fail "time-fixture PASS — fixture failure result line found (FAIL/ERROR/STAGED-FAIL/SKIP/KNOWN-FAIL): $(printf '%s\n' "$FAIL_LINES" | head -1)"
  CRIT4_FAILED=1
fi
if [[ "$NEXT_LINE" =~ ^(FAIL\ \(|ERROR:|STAGED-FAIL\ \(|SKIP\ \(|KNOWN-FAIL\ \() ]]; then
  fail "time-fixture PASS — displaced failure result on the line immediately following the fixture's prefix line: $NEXT_LINE"
  CRIT4_FAILED=1
fi

# On-disk @expected cross-check: exactly one of the two disposition
# pairs, and, when the result text is visible on the prefix line, it
# must match the on-disk expectation (mismatched pair = gate failure
# per luajit-gate-closure D3).
EXPECTED=""
if [ -f "$FIXTURE" ]; then
  EXPECTED="$(grep -E '^// @expected: ' "$FIXTURE" | head -1 | sed 's|^// @expected: ||')"
fi
BRANCH=""
case "$EXPECTED" in
  "runtime-error E8004") BRANCH="1" ;;
  "runtime-ok") BRANCH="2" ;;
esac
if [ -z "$BRANCH" ]; then
  fail "time-fixture PASS — on-disk @expected '$EXPECTED' is neither disposition pair ('runtime-error E8004' or 'runtime-ok'); expectation(fixture) == landed nowMillis behavior cannot hold"
  CRIT4_FAILED=1
fi
if [ "$CRIT4_FAILED" = "0" ] && [ -n "$ONLINE" ]; then
  case "$ONLINE" in
    "OK (found DEAL_ERROR_CODE: E8004)")
      if [ "$BRANCH" != "1" ]; then
        fail "time-fixture PASS — the prefix line records 'OK (found DEAL_ERROR_CODE: E8004)' while the fixture is @expected runtime-ok (mismatched pair)"
        CRIT4_FAILED=1
      fi
      ;;
    "OK (found DEAL_ERROR_CODE: "*)
      fail "time-fixture PASS — the prefix line records '$ONLINE' while the fixture is @expected $EXPECTED (mismatched pair)"
      CRIT4_FAILED=1
      ;;
    "OK")
      if [ "$BRANCH" != "2" ]; then
        fail "time-fixture PASS — the prefix line records 'OK' while the fixture is @expected runtime-error E8004 (mismatched pair)"
        CRIT4_FAILED=1
      fi
      ;;
    *)
      fail "time-fixture PASS — unrecognized result on the fixture's prefix line: $ONLINE"
      CRIT4_FAILED=1
      ;;
  esac
fi
if [ "$CRIT4_FAILED" = "0" ]; then
  if [ -n "$ONLINE" ]; then
    pass "time-fixture PASS — branch $BRANCH (direct): the prefix line records '$ONLINE', fixture @expected $EXPECTED"
  else
    pass "time-fixture PASS — branch $BRANCH (indirect): CT-lane prefix line present exactly once, no failure result for the fixture, four zeros on both surfaces; fixture @expected $EXPECTED"
  fi
fi

# 5. No GATE FAILURE line anywhere in the run. (The strict gate prints
#    its residual lines after printSummary, past the `=== Conformance
#    Summary ===` header, so the whole log is scanned.)
if grep -q 'GATE FAILURE' "$LOG"; then
  fail "no GATE FAILURE — a GATE FAILURE line prints in the run"
else
  pass "no GATE FAILURE line in the run"
fi

# 6. Pin-test section on the post-activation header.
PIN_SECTION="$(awk '
  /^=== std\/time\.nowMillis Pre-Activation Pin \(ISSUE-0369\) ===$/ { inpin=1; next }
  /^=== std\/time\.nowMillis Pre-Activation Pin Tests \(ISSUE-0369\) ===$/ && inpin { next }
  inpin && /^=== / { exit }
  inpin { print }
' "$LOG")"
if echo "$PIN_SECTION" | grep -qE '^ *Passed: [0-9]+, Failed: 0$'; then
  pass "pin test — section '=== std/time.nowMillis Pre-Activation Pin (ISSUE-0369) ===' reports Failed: 0"
else
  fail "pin test — no 'Passed: <K>, Failed: 0' verdict inside the Pre-Activation Pin section"
fi

# 7. run_tests.sh exit-0 marker.
if grep -q '=== All Tests Passed ===' "$LOG"; then
  pass "run_tests.sh exit 0 — '=== All Tests Passed ===' marker present"
else
  fail "run_tests.sh exit 0 — '=== All Tests Passed ===' marker absent"
fi

echo
echo "Recorded totals at execution time: summary N=$N; backend-runtime phase denominator M=$M"
if [ "$FAILED" != "0" ]; then
  echo "VERDICT: closure criteria unmet — closure pending" >&2
  exit 1
fi
echo "VERDICT: closure criteria satisfied — four zeros, time-fixture PASS under the landed branch, pin test green, exit 0"
exit 0
