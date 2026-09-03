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
#   4. Time-fixture PASS matching the landed branch, keyed to the
#      ConformanceTest lane's own executed line shape — the
#      `LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)` infix
#      ConformanceTest.java:493-495 prints for this catalogued fixture
#      (LegacyProfileRegressionCatalog.java:173; self-probe :527-533)
#      and the JVM lane never prints (JvmConformanceTest.java:1572,
#      1580). Branch 1 `[...] LEGACY-AUTHORITY (...) OK (found
#      DEAL_ERROR_CODE: E8004)`, branch 2 `[...] LEGACY-AUTHORITY (...)
#      OK`, cross-checked against the fixture's on-disk @expected, and
#      no `[...] LEGACY-AUTHORITY (...) FAIL`/`ERROR` line for the
#      fixture — checked inside the `=== DEAL v1.2 Conformance Test Suite ===` …
#      `=== Conformance Summary ===` window. The infix is the lane
#      attribution: the JVM lane's byte-identical `[...] OK` /
#      `[...] OK (found ...)` suffixes without the infix interleave
#      into the shared log inside the window and cannot match.
#      ConformanceTest prints the prefix and the result as two separate
#      writes, and concurrent background-suite output can splice
#      between them (observed in the captured real run at this HEAD),
#      so when the unsplit line is absent the first pathless CT-result
#      line after the fixture's prefix line is attributed as the
#      fixture's own displaced result (the CT is single-threaded — no
#      other CT output can appear between its two prints);
#   5. No `GATE FAILURE` line in the ConformanceTest section;
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
# asserted inside this window only. The JVM lane launches in background
# into the same shared log (tools/gate-manifest.sh:154) and its
# per-fixture lines can interleave inside this line range (verified at
# this HEAD), so criterion 4 keys its fixture evidence to the
# ConformanceTest-only `LEGACY-AUTHORITY …` infix — lane attribution,
# not the window alone.
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

# 4. Time-fixture PASS matching the landed branch, keyed to the
#    ConformanceTest lane's own executed line shape. ConformanceTest
#    prints `  [<path>] LEGACY-AUTHORITY (legacy-regression; zero
#    v1.2 credit) ` before the result for catalogued fixtures
#    (ConformanceTest.java:493-495); this fixture is catalogued
#    (LegacyProfileRegressionCatalog.java:173, self-probe :527-533).
#    The JVM lane prints byte-identical result suffixes without the
#    infix (JvmConformanceTest.java:1572,1580) and its lines interleave
#    into the shared log inside the CT window, so the infix is the lane
#    attribution: only ConformanceTest's own lines match, and the exact
#    count of 1 cannot be inflated by a concurrent lane. The prefix and
#    the result are two separate writes, and concurrent suite output
#    can splice between them, so when the unsplit line is absent the
#    first pathless CT-result line after the fixture's prefix line is
#    attributed as the fixture's own displaced result (the CT is
#    single-threaded — no other CT output can appear between the two
#    prints; foreign suites never print these pathless shapes: the JVM
#    lane always prefixes its lines with the path).
AUTH="LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)"
PFX_RE="\[$FIXTURE_REL\] $AUTH"
PFX_RAW="[$FIXTURE_REL] $AUTH"
LINE_A="$(printf '%s\n' "$CT_SECTION" | grep -c "$PFX_RE OK (found DEAL_ERROR_CODE: E8004)")"
LINE_B="$(printf '%s\n' "$CT_SECTION" | grep -c "$PFX_RE OK$")"
LINE_FAIL="$(printf '%s\n' "$CT_SECTION" | grep -cE "$PFX_RE (FAIL|ERROR)")"
if [ "$LINE_A" = "0" ] && [ "$LINE_B" = "0" ] && [ "$LINE_FAIL" = "0" ]; then
  DISPLACED="$(printf '%s\n' "$CT_SECTION" | awk -v pf="$PFX_RAW" '
    index($0, pf) { seen = 1; next }
    seen && /^(OK \(|OK$|FAIL \(|STAGED-FAIL \(|SKIP \(|ERROR:)/ { print; exit }
  ')"
  case "$DISPLACED" in
    "OK (found DEAL_ERROR_CODE: E8004"*)
      LINE_A=$((LINE_A + 1))
      ;;
    "OK (found DEAL_ERROR_CODE: "*)
      # Another error code — not this fixture's pinned branch result;
      # treated as neither (the branch checks below name the absence).
      ;;
    "OK"*)
      LINE_B=$((LINE_B + 1))
      ;;
    "FAIL ("*|"ERROR:"*)
      LINE_FAIL=$((LINE_FAIL + 1))
      ;;
    *)
      # Empty, STAGED-FAIL, or SKIP — neither a PASS nor a FAIL line;
      # the branch checks below then fail with "found 0" (closure
      # pending or environmentally skipped).
      ;;
  esac
fi
if [ "$LINE_FAIL" != "0" ]; then
  fail "time-fixture PASS — fixture FAIL line found (expectation != landed nowMillis behavior)"
fi
EXPECTED=""
if [ -f "$FIXTURE" ]; then
  EXPECTED="$(grep -E '^// @expected: ' "$FIXTURE" | head -1 | sed 's|^// @expected: ||')"
fi
if [ "$EXPECTED" = "runtime-error E8004" ]; then
  if [ "$LINE_A" = "1" ]; then
    pass "time-fixture PASS — branch 1: [...deals] $AUTH OK (found DEAL_ERROR_CODE: E8004), fixture @expected runtime-error E8004"
  else
    fail "time-fixture PASS — branch 1: expected the '[backend-runtime/stdlib-edge/time-now-millis-positive.deal] $AUTH OK (found DEAL_ERROR_CODE: E8004)' line for the runtime-error E8004 fixture (found $LINE_A such line(s))"
  fi
elif [ "$EXPECTED" = "runtime-ok" ]; then
  if [ "$LINE_B" = "1" ]; then
    pass "time-fixture PASS — branch 2: [...deals] $AUTH OK, fixture @expected runtime-ok"
  else
    fail "time-fixture PASS — branch 2: expected the '[backend-runtime/stdlib-edge/time-now-millis-positive.deal] $AUTH OK' line for the runtime-ok fixture (found $LINE_B such line(s))"
  fi
else
  if [ "$LINE_A" = "1" ] || [ "$LINE_B" = "1" ]; then
    pass "time-fixture PASS — fixture @expected unreadable; landed-branch PASS line found (A=$LINE_A B=$LINE_B)"
  else
    fail "time-fixture PASS — no PASS line for the time fixture (A=$LINE_A B=$LINE_B)"
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
