#!/bin/bash
#
# ISSUE-0379 — JVM Int32 Gate Activation Tree acceptance verification
# (diff discipline, staged-state confirmation, merge-input report).
#
# Verifies the tree's acceptance surface at the current HEAD
# (jvm-int32-gate-activation-tree D6/D7, Verification 4/5/6; the epic's
# tree criteria):
#
#   1. Four-file diff discipline: the diff against the pre-tree base
#      scoped to the four JVM activation files lists exactly those four
#      files — no activation file missing, no extra activation file.
#   2. Whole-tree combined file set: the union of the pinned tree
#      commits' changed files equals the pinned expected set exactly —
#      a missing or extra dependency file fails the assertion.
#   3. Forbidden surfaces: the tree change set changes no std/*.js,
#      no std/*.lua, no deal/runtime.js, no deal/runtime.lua, no
#      std/time.* file; its only test/conformance/** members are the
#      sanctioned known-fail lane fixtures (int-add-overflow.deal +
#      expect.json restored with their known-fail/@issue markers, and
#      the jvm-v1.2-known-fail.json mirror re-pin).
#   4. Fixture discipline: the shared time fixture keeps its
#      runtime-ok header and is byte-identical to the pre-tree base;
#      std/time.lua and std/time.js are unchanged; the delegated
#      promotions are NOT applied (int-add-overflow.deal still carries
#      '@expected: known-fail runtime-error E8004' + '@issue').
#   5. Byte identity: emitStdlibTimeMemberCall's emitted body is
#      byte-identical to the pre-tree base.
#   6. Profile immutability: the release state stays PRE_ACTIVATION
#      (ReleaseConfiguration.CURRENT_RELEASE_STATE), Main and the
#      orchestrator default derive the public profile from it, the
#      SemanticProfile set stays closed, no system-property/environment
#      profile surface exists, the JVM lane carries the explicit
#      V1_2_ACTIVE invocation, and BackendConformanceTest keeps the
#      legacy invocation.
#   7. No merge into the main line by this tree: the delivered change
#      set (canonical import .. HEAD) contains no merge commit; the
#      tree head SHA is recorded.
#   8. Staged lane re-run (requires the compiled build/): the real JVM
#      lane exits 1 with exactly the pinned failure set — the unflipped
#      time fixture raising E8004 against runtime-ok, and the
#      stale-known-fail gate naming arithmetic/int-add-overflow.deal
#      with its promotion instruction — never papered over.
#
# Exit 0 iff every check holds. Evidence is written to
# verify_jvm_tree_acceptance.sh [EVIDENCE_DIR] (default: mktemp dir).
#
# Usage: run after the gate compile (./run_tests.sh --jobs 1) so that
# build/ exists for section 8.
#
set -u

REPO="$(cd "$(dirname "$0")" && pwd)"
OUTDIR="${1:-}"
if [ -z "$OUTDIR" ]; then
  OUTDIR="$(mktemp -d /tmp/issue0379-evidence.XXXXXX)"
fi
mkdir -p "$OUTDIR"
OUTDIR="$(cd "$OUTDIR" && pwd)"

die() {
  echo "ABORT: $*" >&2
  exit 1
}

for tool in bash sed grep git diff sort java; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool missing: $tool"
done

# The pre-tree base: the main-line revision immediately before the first
# JVM-tree commit (85a9e1f, the profile plumb — its parent). At this
# revision the four activation files are in their pre-tree state
# (legacy long-carrier helpers, no plumb, legacy lane, no int32 pins).
BASE="a5f683ab829796cd5b2d42eade7c47ecdee86321"
# The engine-imported canonical source revision this tree is built on.
CANONICAL="8212f23417e0df03e0b90d78db973af51c39cf6d"

FOUR_FILES="deal/codegen/jvm/JvmBackend.java deal/module/CompilationOrchestrator.java test/JvmConformanceTest.java test/JvmBackendTest.java"
TIME_FIXTURE="test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal"
INT_OVERFLOW_FIXTURE="test/conformance/backend-runtime/arithmetic/int-add-overflow.deal"
KNOWN_FAIL_JSON="test/conformance/fixtures/jvm-v1.2-known-fail.json"

# The pinned tree commits (the JVM activation series T1-T5: ISSUE-0374
# plumb, ISSUE-0375 switch, ISSUE-0376 boundary seam, ISSUE-0377 time
# boundary pin, ISSUE-0476 activated lane + ISSUE-0378 anti-hollow pin
# test). The union of their changed files IS the tree change set.
TREE_COMMITS="85a9e1f 6302649 cc81dc1 46a9828 05f3974 a9aa359 4f0e57d 11c35e9 3aa3727 3afa871"

# The pinned whole-tree file set: the four activation files plus the
# lane's supporting gate wiring and the restored known-fail fixture
# trio (the design's D7 pinned staged state presupposes the on-disk
# known-fail fixture and the mirror json at the tree head).
EXPECTED_UNION="deal/codegen/jvm/JvmBackend.java deal/module/CompilationOrchestrator.java test/JvmConformanceTest.java test/JvmBackendTest.java test/JvmLaneStatePinTest.java test/LegacyProfileRegressionCatalog.java run_tests.sh deal/test/conformance/SidecarCorpusValidationTest.java deal/test/conformance/DifferentialGateCorpusTest.java test/conformance/backend-runtime/arithmetic/int-add-overflow.deal test/conformance/backend-runtime/arithmetic/int-add-overflow.expect.json test/conformance/fixtures/jvm-v1.2-known-fail.json"
EXPECTED_CONFORMANCE_MEMBERS="test/conformance/backend-runtime/arithmetic/int-add-overflow.deal test/conformance/backend-runtime/arithmetic/int-add-overflow.expect.json test/conformance/fixtures/jvm-v1.2-known-fail.json"

cd "$REPO"

pass() { echo "  OK: $1"; }
note() { echo "NOTE: $1"; }

# =========================================================================
# 1. Four-file diff discipline (scoped to the activation files).
# =========================================================================
echo "== 1. Four-file diff discipline =="
ACTUAL_FOUR="$(git diff "$BASE"..HEAD --name-only -- $FOUR_FILES | sort)"
EXPECTED_FOUR="$(printf '%s\n' $FOUR_FILES | sort)"
if [ "$ACTUAL_FOUR" = "$EXPECTED_FOUR" ]; then
  pass "the scoped diff against the pre-tree base lists exactly the four activation files"
else
  die "four-file diff diverges. expected: [$EXPECTED_FOUR] actual: [$ACTUAL_FOUR]"
fi
git diff "$BASE"..HEAD --stat -- $FOUR_FILES > "$OUTDIR/four-file-stat.txt" || true

# =========================================================================
# 2. Whole-tree combined file set (union of the pinned tree commits).
# =========================================================================
echo "== 2. Whole-tree combined file set =="
UNION_TMP="$(mktemp /tmp/issue0379-union.XXXXXX)"
: > "$UNION_TMP"
for c in $TREE_COMMITS; do
  git diff --name-only "$c~1..$c" >> "$UNION_TMP"
done
ACTUAL_UNION="$(sort -u "$UNION_TMP")"
rm -f "$UNION_TMP"
EXPECTED_UNION_SORTED="$(printf '%s\n' $EXPECTED_UNION | sort)"
if [ "$ACTUAL_UNION" = "$EXPECTED_UNION_SORTED" ]; then
  pass "the tree change set equals the pinned whole-tree file set exactly"
else
  die "tree change set diverges from the pinned set (missing or extra dependency files). expected: [$EXPECTED_UNION_SORTED] actual: [$ACTUAL_UNION]"
fi

# =========================================================================
# 3. Forbidden surfaces over the tree change set.
# =========================================================================
echo "== 3. Forbidden surfaces =="
FORBIDDEN="$(printf '%s\n' $ACTUAL_UNION | grep -E '^(std/.*\.(js|lua)|deal/runtime\.(js|lua)|std/time\..*)$' || true)"
if [ -z "$FORBIDDEN" ]; then
  pass "no std/*.js, std/*.lua, deal/runtime.js, deal/runtime.lua, or std/time.* file appears in the tree change set"
else
  die "forbidden files in the tree change set: $FORBIDDEN"
fi
CONF_MEMBERS="$(printf '%s\n' $ACTUAL_UNION | grep '^test/conformance/' || true)"
CONF_SORTED="$(printf '%s\n' $EXPECTED_CONFORMANCE_MEMBERS | sort)"
if [ "$(printf '%s\n' "$CONF_MEMBERS" | sort)" = "$CONF_SORTED" ]; then
  pass "the only test/conformance/** members are the sanctioned known-fail lane fixtures (restored int-add-overflow.deal + expect.json, mirror re-pin)"
else
  die "unexpected test/conformance/** members: [$CONF_MEMBERS]"
fi

# =========================================================================
# 4. Fixture discipline.
# =========================================================================
echo "== 4. Fixture discipline =="
LINE3="$(sed -n '3p' "$TIME_FIXTURE")"
if [ "$LINE3" = "// @expected: runtime-ok" ]; then
  pass "the shared time fixture keeps its runtime-ok expectation (the flip is the unit's edit)"
else
  die "time fixture header diverged: [$LINE3]"
fi
EXPECTED_LINES="$(grep -c '@expected' "$TIME_FIXTURE")"
if [ "$EXPECTED_LINES" = "1" ]; then
  pass "the time fixture has exactly one @expected line"
else
  die "time fixture @expected line count: $EXPECTED_LINES"
fi
CHANGED_PROTECTED="$(git diff "$BASE"..HEAD --name-only -- \
  "$TIME_FIXTURE" 'std/time.lua' 'std/time.js')"
if [ -z "$CHANGED_PROTECTED" ]; then
  pass "the time fixture, std/time.lua, and std/time.js are byte-identical to the pre-tree base"
else
  die "protected time surfaces changed since the pre-tree base: $CHANGED_PROTECTED"
fi
if grep -q '@expected: known-fail runtime-error E8004' "$INT_OVERFLOW_FIXTURE" \
    && grep -q '@issue: ISSUE-0111' "$INT_OVERFLOW_FIXTURE"; then
  pass "the delegated promotion is not applied: int-add-overflow.deal still carries '@expected: known-fail runtime-error E8004' and '@issue'"
else
  die "int-add-overflow.deal markers diverged (the promotion must not land on this tree)"
fi
if grep -q '"name": "jvm-int32-add-overflow"' "$KNOWN_FAIL_JSON" \
    && grep -q '"knownFail": "ISSUE-0111"' "$KNOWN_FAIL_JSON"; then
  pass "the mirror jvm-int32-add-overflow stays a tracked known-fail in jvm-v1.2-known-fail.json"
else
  die "the mirror known-fail json diverged"
fi

# =========================================================================
# 5. Byte identity of emitStdlibTimeMemberCall.
# =========================================================================
echo "== 5. Byte identity: emitStdlibTimeMemberCall =="
BASE_BODY="$(mktemp /tmp/issue0379-base-time.XXXXXX)"
HEAD_BODY="$(mktemp /tmp/issue0379-head-time.XXXXXX)"
git show "$BASE:deal/codegen/jvm/JvmBackend.java" | \
  awk '/private String emitStdlibTimeMemberCall\(MemberAccessExpr mae, CallExpr call\) \{/,/^\    \}/' > "$BASE_BODY"
awk '/private String emitStdlibTimeMemberCall\(MemberAccessExpr mae, CallExpr call\) \{/,/^\    \}/' \
  deal/codegen/jvm/JvmBackend.java > "$HEAD_BODY"
if diff -u "$BASE_BODY" "$HEAD_BODY" > /dev/null; then
  pass "emitStdlibTimeMemberCall is byte-identical to the pre-tree base; no time algorithm added or changed"
else
  diff -u "$BASE_BODY" "$HEAD_BODY" >&2
  die "emitStdlibTimeMemberCall diverged from the pre-tree base"
fi
cp "$BASE_BODY" "$OUTDIR/emitStdlibTimeMemberCall.pre-tree.txt"
cp "$HEAD_BODY" "$OUTDIR/emitStdlibTimeMemberCall.head.txt"
rm -f "$BASE_BODY" "$HEAD_BODY"

# =========================================================================
# 6. Profile immutability.
# =========================================================================
echo "== 6. Profile immutability =="
if grep -q 'ReleaseState.PRE_ACTIVATION;' deal/semantic/ReleaseConfiguration.java; then
  pass "ReleaseConfiguration.CURRENT_RELEASE_STATE stays pinned to PRE_ACTIVATION"
else
  die "the release state pin diverged"
fi
if grep -q 'ReleaseConfiguration.CURRENT_RELEASE_STATE' deal/Main.java \
    && grep -q 'CompilerProfileProvider.resolve' deal/Main.java; then
  pass "the public CLI derives the public profile from CURRENT_RELEASE_STATE (PRE_ACTIVATION → LEGACY_SAFE_INT)"
else
  die "the Main profile derivation diverged"
fi
if grep -q 'CompilerProfileProvider.resolve' deal/module/CompilationOrchestrator.java \
    && grep -q 'ReleaseConfiguration.CURRENT_RELEASE_STATE' deal/module/CompilationOrchestrator.java; then
  pass "the orchestrator default invocation derives from CURRENT_RELEASE_STATE via the provider (never constructed directly)"
else
  die "the orchestrator default invocation diverged"
fi
if grep -q 'LEGACY_SAFE_INT,' deal/semantic/ir/SemanticProfile.java \
    && grep -q 'DEAL_V1_2_INT32' deal/semantic/ir/SemanticProfile.java; then
  pass "SemanticProfile stays the closed {LEGACY_SAFE_INT, DEAL_V1_2_INT32} set"
else
  die "the SemanticProfile closed set diverged"
fi
SURFACE="$(grep -n 'System.getProperty\|System.getenv' \
  deal/codegen/jvm/JvmBackend.java deal/module/CompilationOrchestrator.java \
  deal/Main.java || true)"
if [ -z "$SURFACE" ]; then
  pass "no system-property/environment profile surface exists in the backend, orchestrator, or CLI"
else
  die "profile surface found: $SURFACE"
fi
if grep -q 'this.int32Mode = semanticProfile == SemanticProfile.DEAL_V1_2_INT32;' \
    deal/codegen/jvm/JvmBackend.java; then
  pass "the backend derives the int32 mode from the invocation's SemanticProfile"
else
  die "the backend mode derivation diverged"
fi
if grep -q 'CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,' \
    test/JvmConformanceTest.java \
    && grep -q 'CapabilityRegistry.releaseRegistry()' test/JvmConformanceTest.java; then
  pass "the JVM corpus lane carries the explicit V1_2_ACTIVE invocation"
else
  die "the lane invocation diverged"
fi
if grep -q 'SemanticProfile.LEGACY_SAFE_INT' test/BackendConformanceTest.java; then
  pass "BackendConformanceTest keeps the untouched legacy invocation (the legacy regression authority)"
else
  die "BackendConformanceTest lost its legacy invocation"
fi

# =========================================================================
# 7. History discipline: no merge into the main line; head SHA recorded.
# =========================================================================
echo "== 7. History discipline =="
if ! git merge-base --is-ancestor "$CANONICAL" HEAD; then
  die "the canonical import $CANONICAL is not an ancestor of HEAD"
fi
MERGES="$(git log --merges --format=%h "$CANONICAL"..HEAD)"
if [ -z "$MERGES" ]; then
  pass "the delivered change set contains no merge commit (no merge into the repository main line)"
else
  die "merge commits in the delivered change set: $MERGES"
fi
HEAD_SHA="$(git rev-parse HEAD)"
echo "$HEAD_SHA" > "$OUTDIR/tree-head-sha.txt"
pass "tree head SHA recorded: $HEAD_SHA"

# =========================================================================
# 8. Staged lane re-run (the real lane; requires the compiled build/).
# =========================================================================
echo "== 8. Staged lane re-run =="
if [ ! -d build ]; then
  die "build/ missing — run './run_tests.sh --jobs 1' first (the gate compile) and re-run"
fi
LANE_LOG="$OUTDIR/jvm-lane-run.log"
set +e
java -ea -cp build deal.test.JvmConformanceTest test/conformance/ > "$LANE_LOG" 2>&1
LANE_EXIT=$?
set -e
if [ "$LANE_EXIT" = "1" ]; then
  pass "the real JVM lane exits 1 (the sanctioned pinned staged state)"
else
  die "the JVM lane exit code diverged (expected 1): $LANE_EXIT"
fi
check_line() { grep -Fq "$2" "$LANE_LOG" && pass "$1" || die "$1 (pinned line missing in the lane run)"; }
check_line "the unflipped time fixture raises E8004 against runtime-ok" \
  "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] FAIL (runtime-ok test exited 1): DEAL_ERROR_CODE: E8004 int out of safe range"
check_line "the stale-known-fail gate names arithmetic/int-add-overflow.deal with the promotion instruction" \
  "  [backend-runtime/arithmetic/int-add-overflow.deal] FAIL (STALE known-fail: the v1.2 requirement tracked by ISSUE-0111 now passes on JVM — promote the fixture: set '@expected: runtime-error E8004' and drop the @issue tag)"
check_line "the stale-known-fail GATE FAILURE line" \
  "GATE FAILURE: 1 stale known-fail marker(s) — promote the fixture(s)"
check_line "the applicable-failures GATE FAILURE line" \
  "GATE FAILURE: 1 applicable backend-runtime test(s) failed — zero applicable failures required"
check_line "the pinned lane summary" \
  "Backend-runtime on JVM: denominator 301 (every on-disk runtime test, unchanged), passed 252, failed 1, skipped 47 (classified), known-fail 0 (tracked) — pass rate 83.7%"
if grep -q 'STAGED-FAIL' "$LANE_LOG"; then
  die "unexpected STAGED-FAIL line on the JVM lane (the pinned set has none)"
fi
pass "no STAGED-FAIL line on the JVM lane (the pinned failure set is exact)"

echo ""
echo "=== ISSUE-0379 tree acceptance verification passed ==="
echo "Evidence directory: $OUTDIR"
echo "Tree head SHA: $HEAD_SHA"
