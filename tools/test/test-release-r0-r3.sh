#!/bin/bash
# tools/test/test-release-r0-r3.sh -- the release.sh R0-R3 committed phase
# battery (release-r0-r3-strict-gate-mechanics Verification: the R2(b)
# mapping control, the R3 mapping control, the record parsing control, and
# the step-name discipline).
#
# The committed phase functions of tools/release.sh run against synthetic
# scratch inputs (the scratch-copy precedent of tools/verify-launcher.sh):
#   - parse_skip_max / update_record_after_r2a over synthetic
#     strict-run-tests captures: `Skipped: 2` + `3 skipped (classified)`
#     -> skipped 3 (the maximum); a green log with `Skipped: 0` and
#     `0 skipped, 0 known-fail (tracked)` -> 0; a log with no pinned
#     segment -> null; an absent log -> null;
#   - parse_coverage_pct / update_record_after_r3 over a synthetic
#     strict-coverage capture with the pinned print lines, plus a prepared
#     build/coverage.csv digested through the committed coverage-csv-digest
#     dispatch (the pinned /usr/bin/sha256sum) -- the recorded
#     coverageCsvSha256 equals the pinned sha256sum's digest;
#   - phase_r2b green over the committed fixtures (219 runtime-ok files,
#     zero weak -- the query output is empty on green) and the weak
#     control: one runtime-ok fixture with its TEST_FAIL assertion removed
#     -> the weak path prints to stderr, RUNTIME_ASSERTION_GATE_FAILED
#     prints to stderr, exit 1, and the gate record carries weakFixtures 1;
#   - phase_r3 mapping control: a prepared export whose strict-coverage
#     step fails (a production file added under a production root but
#     absent from PROD_SOURCES -> the manifest self-check's
#     MANIFEST_SELF_CHECK_FAILED hard failure) -> RELEASE_COVERAGE_GATE_FAILED
#     on stderr, exit 1, the record retains null coverage fields, and the
#     coverage-csv-digest entry does not run (no build/coverage.csv);
#   - the committed dispatch against a synthetic step name absent from the
#     table -> RELEASE_UNBOUNDED_PROCESS at invocation, and the step-name
#     discipline check: release.sh requests exactly the pinned S9 names.
#
# Writes only under its own mktemp scratch directory; creates no
# repository state.

CHECKOUT_ROOT="$PWD"
cd "$(dirname "$0")/../.." || exit 1
CHECKOUT_ROOT="$PWD"

# Sourcing tools/release.sh defines the committed phase functions without
# running the release (the script executes main() only when invoked
# directly); the script's `set -eu` applies to this battery from here.
source tools/release.sh

WORK="$(mktemp -d "${TMPDIR:-/tmp}/release-r0-r3-test.XXXXXX")" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASSES=0
FAILURES=0

fail() {
  echo "FAIL: $1" >&2
  FAILURES=$((FAILURES + 1))
}

# -------------------------------------------------------------------------
# Record parsing control: parse_skip_max over the synthetic captures.
# -------------------------------------------------------------------------
SKIP_MAX_LOG="$WORK/skip-max.log"
cat > "$SKIP_MAX_LOG" <<'EOF'
Total: 30, Passed: 28, Failed: 0, Skipped: 2, KnownFailures (tracked): 0, StagedFailures (tracked): 0
Discovered 307 conformance test(s): 6 frontend-classified, 45 skipped (classified), 0 known-fail (tracked)
EOF
out=""
if out=$(parse_skip_max "$SKIP_MAX_LOG"); then
  if [ "$out" != "45" ]; then
    fail "parse_skip_max max-case: expected 45, got [$out]"
  else
    PASSES=$((PASSES + 1))
  fi
else
  fail "parse_skip_max max-case: unexpected status $?"
fi

SKIP_GREEN_LOG="$WORK/skip-green.log"
cat > "$SKIP_GREEN_LOG" <<'EOF'
Total: 30, Passed: 30, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
  Frontend conformance (v1.2 grammar and semantics): 20/20 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
EOF
out=""
if out=$(parse_skip_max "$SKIP_GREEN_LOG"); then
  if [ "$out" != "0" ]; then
    fail "parse_skip_max green-case: expected 0, got [$out]"
  else
    PASSES=$((PASSES + 1))
  fi
else
  fail "parse_skip_max green-case: unexpected status $?"
fi

SKIP_NOSEG_LOG="$WORK/skip-noseg.log"
cat > "$SKIP_NOSEG_LOG" <<'EOF'
STRICT_SKIP_DETECTED (control/fixture.deal: injected)
EOF
out=""
if out=$(parse_skip_max "$SKIP_NOSEG_LOG"); then
  fail "parse_skip_max no-segment-case: expected status 1, got 0 with output [$out]"
else
  if [ -n "$out" ]; then
    fail "parse_skip_max no-segment-case: expected empty output, got [$out]"
  else
    PASSES=$((PASSES + 1))
  fi
fi

out=""
if out=$(parse_skip_max "$WORK/absent.log"); then
  fail "parse_skip_max absent-log-case: expected status 1, got 0 with output [$out]"
else
  PASSES=$((PASSES + 1))
fi

# update_record_after_r2a: skipped lands in the pinned record.
GATE_RECORD_PATH="$WORK/gate-record.json"
update_record_after_r2a "$SKIP_MAX_LOG"
if ! grep -q '"skipped": 45' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r2a max-case: expected skipped 45 in $(cat "$GATE_RECORD_PATH")"
else
  PASSES=$((PASSES + 1))
fi
update_record_after_r2a "$SKIP_GREEN_LOG"
if ! grep -q '"skipped": 0' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r2a green-case: expected skipped 0 in $(cat "$GATE_RECORD_PATH")"
else
  PASSES=$((PASSES + 1))
fi
update_record_after_r2a "$WORK/absent.log"
if ! grep -q '"skipped": null' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r2a absent-log-case: expected skipped null in $(cat "$GATE_RECORD_PATH")"
else
  PASSES=$((PASSES + 1))
fi
if ! grep -q '"weakFixtures": null' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r2a: expected not-yet-produced weakFixtures null"
else
  PASSES=$((PASSES + 1))
fi

# -------------------------------------------------------------------------
# Record parsing control: parse_coverage_pct / update_record_after_r3 over
# the synthetic strict-coverage capture, and the coverage-csv-digest
# dispatch over a prepared build/coverage.csv (the pinned sha256sum).
# -------------------------------------------------------------------------
COVERAGE_LOG="$WORK/strict-coverage.log"
cat > "$COVERAGE_LOG" <<'EOF'
Line coverage: 99.72% (gate: 100)
Branch coverage: 100.00% (gate: 100)
EOF
out=""
if out=$(parse_coverage_pct "$COVERAGE_LOG" Line); then
  if [ "$out" != "99.72" ]; then
    fail "parse_coverage_pct line-case: expected 99.72, got [$out]"
  else
    PASSES=$((PASSES + 1))
  fi
else
  fail "parse_coverage_pct line-case: unexpected status $?"
fi
out=""
if out=$(parse_coverage_pct "$COVERAGE_LOG" Branch); then
  if [ "$out" != "100.00" ]; then
    fail "parse_coverage_pct branch-case: expected 100.00, got [$out]"
  else
    PASSES=$((PASSES + 1))
  fi
else
  fail "parse_coverage_pct branch-case: unexpected status $?"
fi
out=""
if out=$(parse_coverage_pct "$WORK/absent.log" Line); then
  fail "parse_coverage_pct absent-log-case: expected status 1, got 0 with output [$out]"
else
  PASSES=$((PASSES + 1))
fi

CSV_EXPORT="$WORK/csv-export"
mkdir -p "$CSV_EXPORT/build" "$CSV_EXPORT/release"
printf 'GROUP,PACKAGE,CLASS,LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED\n' > "$CSV_EXPORT/build/coverage.csv"
update_record_after_r3 "$COVERAGE_LOG" "$CSV_EXPORT/build/coverage.csv"
if ! grep -q '"lineCoverage": 99.72' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r3: expected lineCoverage 99.72 in $(cat "$GATE_RECORD_PATH")"
else
  PASSES=$((PASSES + 1))
fi
if ! grep -q '"branchCoverage": 100.00' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r3: expected branchCoverage 100.00 in $(cat "$GATE_RECORD_PATH")"
else
  PASSES=$((PASSES + 1))
fi
if ! grep -q '"coverageCsv": "build/coverage.csv"' "$GATE_RECORD_PATH"; then
  fail "update_record_after_r3: expected coverageCsv build/coverage.csv in $(cat "$GATE_RECORD_PATH")"
else
  PASSES=$((PASSES + 1))
fi

# The committed coverage-csv-digest dispatch: the pinned sha256sum over the
# prepared CSV, export-cwd; the recorded digest must equal sha256sum's.
export RELEASE_CHECKOUT_ROOT="$CHECKOUT_ROOT"
export RELEASE_EXPORT_ROOT="$CSV_EXPORT"
digest=""
if digest=$(release_step_run coverage-csv-digest -- /usr/bin/sha256sum build/coverage.csv); then
  expected=$(/usr/bin/sha256sum "$CSV_EXPORT/build/coverage.csv" | cut -d' ' -f1)
  got=$(printf '%s\n' "$digest" | cut -d' ' -f1)
  if [ "$got" != "$expected" ]; then
    fail "coverage-csv-digest: expected [$expected], got [$got]"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q '"step": "coverage-csv-digest"' "$CSV_EXPORT/release/step-log.jsonl"; then
    fail "coverage-csv-digest: expected a step-log record"
  else
    PASSES=$((PASSES + 1))
  fi
else
  fail "coverage-csv-digest: the committed dispatch failed"
fi

# -------------------------------------------------------------------------
# R2(b) mapping control: the pinned query green over the committed
# fixtures, and the weak control over a prepared export whose runtime-ok
# fixture had its TEST_FAIL assertion removed.
# -------------------------------------------------------------------------
GATE_RECORD_PATH="$WORK/gate-record-r2b.json"
if phase_r2b "$CHECKOUT_ROOT" >"$WORK/r2b-green.out" 2>"$WORK/r2b-green.err"; then
  if [ -s "$WORK/r2b-green.err" ]; then
    fail "phase_r2b green: expected empty stderr, got [$(cat "$WORK/r2b-green.err")]"
  elif ! grep -q '"weakFixtures": 0' "$GATE_RECORD_PATH"; then
    fail "phase_r2b green: expected weakFixtures 0 in $(cat "$GATE_RECORD_PATH")"
  else
    PASSES=$((PASSES + 1))
  fi
else
  fail "phase_r2b green: expected exit 0, got $?"
fi

WEAK_EXPORT="$WORK/weak-export"
mkdir -p "$WEAK_EXPORT/test/conformance/backend-runtime/control"
cp test/conformance/backend-runtime/arrays/index-read.deal \
  "$WEAK_EXPORT/test/conformance/backend-runtime/control/index-read.deal"
sed -i '/code: "TEST_FAIL"/d' \
  "$WEAK_EXPORT/test/conformance/backend-runtime/control/index-read.deal"
if grep -q 'code: "TEST_FAIL"' \
    "$WEAK_EXPORT/test/conformance/backend-runtime/control/index-read.deal"; then
  fail "phase_r2b weak control: the TEST_FAIL removal did not apply"
fi
GATE_RECORD_PATH="$WORK/gate-record-r2b-weak.json"
if phase_r2b "$WEAK_EXPORT" >"$WORK/r2b-weak.out" 2>"$WORK/r2b-weak.err"; then
  fail "phase_r2b weak control: expected exit 1, got 0"
else
  status=$?
  if [ "$status" -ne 1 ]; then
    fail "phase_r2b weak control: expected exit 1, got $status"
  fi
  if ! grep -q 'index-read.deal' "$WORK/r2b-weak.err"; then
    fail "phase_r2b weak control: the weak path is missing from stderr [$(cat "$WORK/r2b-weak.err")]"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q 'RUNTIME_ASSERTION_GATE_FAILED' "$WORK/r2b-weak.err"; then
    fail "phase_r2b weak control: RUNTIME_ASSERTION_GATE_FAILED missing from stderr"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q '"weakFixtures": 1' "$GATE_RECORD_PATH"; then
    fail "phase_r2b weak control: expected weakFixtures 1 in $(cat "$GATE_RECORD_PATH")"
  else
    PASSES=$((PASSES + 1))
  fi
fi

# -------------------------------------------------------------------------
# R3 mapping control: the committed phase against a prepared export whose
# strict-coverage step fails (a production file under a production root
# but absent from PROD_SOURCES -> MANIFEST_SELF_CHECK_FAILED before the
# compile). The phase token RELEASE_COVERAGE_GATE_FAILED prints on stderr
# with the script's own token beneath it, the record retains null coverage
# fields, and the coverage-csv-digest entry does not run.
# -------------------------------------------------------------------------
R3_EXPORT="$WORK/r3-export"
mkdir -p "$R3_EXPORT/release"
git archive HEAD | tar -x -C "$R3_EXPORT"
if [ ! -f "$R3_EXPORT/coverage.sh" ]; then
  fail "R3 mapping control: the prepared export is incomplete"
else
  cp deal/Main.java "$R3_EXPORT/deal/ReleaseR3ControlExtra.java"
fi
export RELEASE_CHECKOUT_ROOT="$CHECKOUT_ROOT"
export RELEASE_EXPORT_ROOT="$R3_EXPORT"
GATE_RECORD_PATH="$WORK/gate-record-r3.json"
if phase_r3 "$R3_EXPORT" >"$WORK/r3.out" 2>"$WORK/r3.err"; then
  fail "R3 mapping control: expected exit 1, got 0"
else
  status=$?
  if [ "$status" -ne 1 ]; then
    fail "R3 mapping control: expected exit 1, got $status"
  fi
  if ! grep -q 'RELEASE_COVERAGE_GATE_FAILED' "$WORK/r3.err"; then
    fail "R3 mapping control: RELEASE_COVERAGE_GATE_FAILED missing from stderr [$(cat "$WORK/r3.err")]"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q 'MANIFEST_SELF_CHECK_FAILED' "$WORK/r3.err"; then
    fail "R3 mapping control: the script's own token must remain visible beneath the phase token [$(cat "$WORK/r3.err")]"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q '"lineCoverage": null' "$GATE_RECORD_PATH"; then
    fail "R3 mapping control: expected null lineCoverage in $(cat "$GATE_RECORD_PATH")"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q '"branchCoverage": null' "$GATE_RECORD_PATH"; then
    fail "R3 mapping control: expected null branchCoverage in $(cat "$GATE_RECORD_PATH")"
  else
    PASSES=$((PASSES + 1))
  fi
  if ! grep -q '"coverageCsv": null' "$GATE_RECORD_PATH"; then
    fail "R3 mapping control: expected null coverageCsv in $(cat "$GATE_RECORD_PATH")"
  else
    PASSES=$((PASSES + 1))
  fi
  if grep -q '"step": "coverage-csv-digest"' "$R3_EXPORT/release/step-log.jsonl"; then
    fail "R3 mapping control: coverage-csv-digest must not run when build/coverage.csv is absent"
  else
    PASSES=$((PASSES + 1))
  fi
  if grep -q '"step": "strict-coverage"' "$R3_EXPORT/release/step-log.jsonl"; then
    PASSES=$((PASSES + 1))
  else
    fail "R3 mapping control: expected a strict-coverage step-log record"
  fi
fi

# -------------------------------------------------------------------------
# Step-name discipline: the committed dispatch refuses a name absent from
# the table with RELEASE_UNBOUNDED_PROCESS, and release.sh requests exactly
# the pinned S9 names.
# -------------------------------------------------------------------------
if release_step_run not-a-pinned-step -- /bin/true >"$WORK/unb.out" 2>"$WORK/unb.err"; then
  fail "step-name discipline: an absent name must fail RELEASE_UNBOUNDED_PROCESS"
else
  if ! grep -q 'RELEASE_UNBOUNDED_PROCESS' "$WORK/unb.err"; then
    fail "step-name discipline: RELEASE_UNBOUNDED_PROCESS missing from stderr [$(cat "$WORK/unb.err")]"
  else
    PASSES=$((PASSES + 1))
  fi
fi

names=$(grep -o 'release_step_run [a-z0-9.-]*' tools/release.sh \
  | sed 's/release_step_run //' | LC_ALL=C sort -u)
expected_names=$(printf 'coverage-csv-digest\npreflight-integrity\npreflight-limits\nrelease-export\nstrict-coverage\nstrict-run-tests')
if [ "$names" != "$expected_names" ]; then
  fail "step-name discipline: expected exactly the pinned S9 names [$expected_names], got [$names]"
else
  PASSES=$((PASSES + 1))
fi

# The pinned R2(b) query text is the committed query, verbatim (S7).
if ! grep -Fq "grep -RIl '^// @expected: runtime-ok' test/conformance/backend-runtime --include='*.deal'" tools/release.sh; then
  fail "pinned query: the S7 query text is absent from tools/release.sh"
else
  PASSES=$((PASSES + 1))
fi

echo ""
echo "release-r0-r3 battery: $PASSES passed, $FAILURES failed"
if [ "$FAILURES" -gt 0 ]; then
  exit 1
fi
