#!/bin/bash
set -e

mkdir -p build

# Single compile/test-list authority: tools/gate-manifest.sh provides
# PROD_SOURCES + TEST_SOURCES (today's javac source list, verbatim) and
# TEST_MAINS (today's run phase, verbatim). See gate-manifest-authority.
source tools/gate-manifest.sh
# =========================================================================
# ISSUE-0474 + ISSUE-0475 (Coverage Manifest Validator and Corpus
# Check): the reusable C7 validation component, its synthetic 26/0 unit
# matrix, and the real-manifest 82/0 mechanical check join the compile
# list and the unconditional run phase here, at the gate-script level
# (the authoring-time gate authority). The change boundary is
# deal/test/conformance/ plus run_tests.sh, so the single compile/
# test-list authority file (tools/gate-manifest.sh) stays untouched.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/CoverageManifestValidator.java'
  'deal/test/conformance/CoverageManifestValidatorTest.java'
  'deal/test/conformance/CoverageManifestCorpusTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Coverage Manifest Validator Tests (ISSUE-0474) ===|java -ea -cp build deal.test.conformance.CoverageManifestValidatorTest'
  'fg|=== Running Coverage Manifest Corpus Tests (ISSUE-0475) ===|java -ea -cp build deal.test.conformance.CoverageManifestCorpusTest'
)

# =========================================================================
# ISSUE-0354 (LuaJIT lane): the Lua lane of the differential gate plus its
# lane suite join the compile list and the run phase here. The lane
# implements the Shared Lane Contract (G4) over the absorbed
# ConformanceTest compile -> LuaBackend -> luajit path and reuses the
# shared canonical ErrorSnapshot serializer verbatim. The
# LegacyProfileRegressionCatalog authority (the A5 per-case profile
# selection) was extracted from test/ConformanceTest.java into its own
# gate-compiled file so the lane can consume it; ConformanceTest keeps
# running unchanged in run_tests.sh until the flip retires it.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/LuaLane.java'
  'deal/test/conformance/LuaLaneTest.java'
  'test/LegacyProfileRegressionCatalog.java'
)
TEST_MAINS+=(
  'fg|=== Running Lua Lane Tests (ISSUE-0354) ===|java -ea -cp build deal.test.conformance.LuaLaneTest'
)

# =========================================================================
# ISSUE-0353 (differential gate core): the gate components and their unit
# suites join the compile list and the run phase here. The gate's own
# deal.test entry point (deal.test.conformance.DifferentialGate) is NOT
# added to TEST_MAINS — it is exercised directly and is not wired into
# run_tests.sh until the flip (G5's temporary-coexistence window: the
# legacy runners keep executing the runtime corpus until the lanes land).
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/MismatchClass.java'
  'deal/test/conformance/GateMismatch.java'
  'deal/test/conformance/CorpusDiscovery.java'
  'deal/test/conformance/SidecarExpectations.java'
  'deal/test/conformance/ErrorSnapshot.java'
  'deal/test/conformance/StructuredExpectationComparator.java'
  'deal/test/conformance/FrontendCompiler.java'
  'deal/test/conformance/CompileDiagnosticComparator.java'
  'deal/test/conformance/Lane.java'
  'deal/test/conformance/LaneCase.java'
  'deal/test/conformance/LaneExecution.java'
  'deal/test/conformance/GateDispatcher.java'
  'deal/test/conformance/SidecarGateLoader.java'
  'deal/test/conformance/DifferentialGate.java'
  'deal/test/conformance/StructuredExpectationComparatorTest.java'
  'deal/test/conformance/CompileDiagnosticComparatorTest.java'
  'deal/test/conformance/GateDispatcherTest.java'
  'deal/test/conformance/GateClassificationTest.java'
  'deal/test/conformance/DifferentialGateCorpusTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Differential Gate Comparator Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.StructuredExpectationComparatorTest'
  'fg|=== Running Compile Diagnostic Comparator Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.CompileDiagnosticComparatorTest'
  'fg|=== Running Gate Dispatcher Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.GateDispatcherTest'
  'fg|=== Running Gate Classification Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.GateClassificationTest'
  'fg|=== Running Differential Gate Corpus Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.DifferentialGateCorpusTest'
)

# =========================================================================
# DEALPG4 fail-closed toolchain preflight (ISSUE-0183,
# fail-closed-toolchain-preflight D1/D2/D5): one ordered, fail-closed
# phase sequence P0-P5 shared verbatim with coverage.sh via
# tools/preflight-lib.sh. P0 (launcher integrity), P1 (probe identity +
# LIMITS cross-check), P2 (native selftest), and P3 (fail-closed tool
# presence) run here, before any GCC/Javac/Java probe; P4 (bounded
# standalone javac under launcher run) replaces the raw compile below;
# P5 (outer feature supervisor + PreflightCoordinator) runs after the
# compile and before the legacy phases (P6, unchanged). No phase is
# skipped, downgraded, or retried; every failure prints its named token
# on stderr and exits nonzero immediately. No Java process in this
# script spawns the launcher or an outer (D7).
# =========================================================================
source tools/preflight-lib.sh
# PROD_SOURCES is expanded unquoted so the manifest's quoted globs are
# expanded here exactly as they were when the list lived inline (the
# identical expanded file list javac has always received).
DEALPG4_PREFLIGHT_JAVAC_ARGS=(
  javac --release 25 -proc:none -d build \
  -cp /usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  # shellcheck disable=SC2206
  ${PROD_SOURCES[@]} "${TEST_SOURCES[@]}"
)
DEALPG4_PREFLIGHT_COORD_ARGS=(
  java -ea -cp build deal.test.containment.PreflightCoordinator
)
dealpg4_preflight_run

# =========================================================================
# Single compilation step: compile all source and test files at once.
# Incremental: when every .java source under deal/ and test/ is older
# than the recorded build stamp (and this script itself has not changed
# since the stamp), reuse the build/ classes. The stamp is updated after
# every successful full compile, so repeated gate runs on an unchanged
# tree skip the recompilation while a fresh checkout or any touched
# source still compiles everything.
#
# The compile is preflight P4: it runs under `launcher run` (bounded
# standalone javac — 45 s native deadline, 1 MiB drained output) instead
# of the raw javac; the stamp logic is unchanged.
# =========================================================================
STAMP="build/.deal-build-stamp"
NEEDS_BUILD=0
if [ ! -f "$STAMP" ]; then
  NEEDS_BUILD=1
elif [ run_tests.sh -nt "$STAMP" ]; then
  NEEDS_BUILD=1
elif [ tools/preflight-lib.sh -nt "$STAMP" ]; then
  NEEDS_BUILD=1
elif [ -n "$(find deal test -name '*.java' -newer "$STAMP" -print -quit)" ]; then
  NEEDS_BUILD=1
fi

if [ "$NEEDS_BUILD" = "1" ]; then
echo "=== Compiling all DEAL sources and tests ==="
# -proc:none: no DEAL/test source uses an annotation processor, so javac's
# default processor-discovery pass is pure per-task startup cost (the gate
# budget is shared with the JVM artifact suites; measured ~40% faster
# compile under load).
# PROD_SOURCES is expanded unquoted so the manifest's quoted globs are
# expanded here exactly as they were when the list lived inline; javac
# receives the identical expanded file list it receives today.
dealpg4_preflight_javac "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
  touch "$STAMP"
else
  echo "=== DEAL sources and tests unchanged since the last build; reusing build/ ==="
fi

# =========================================================================
# Preflight P5: the outer feature supervisor runs the
# deal.test.containment.PreflightCoordinator JVM (the only post-readiness
# JVM this epic owns): authenticated broker HELLO/FEATURE_READY, bounded
# round-trip nested invocations (luajit -v, /bin/true) through the
# inherited broker, a clean session end (the coordinator closes the
# broker and exits 0; the outer's clean-exit discrimination takes the
# BYE-less short run — the frame pins DONE -> BYE, and DONE lands only
# at the 14:40 cutoff), and a clean outer final proof. Any FAILED
# record or coordinator failure token exits nonzero. The broker socket
# is unlinked by the outer's final proof.
# =========================================================================
dealpg4_preflight_outer

# =========================================================================
# Identity package gate (ISSUE-0309): deal.identity is the neutral
# JDK-only carrier package. A standalone compile against an empty
# classpath fails on any symbol outside java.* and deal.identity, and an
# import scan pins the allowed import surface.
# =========================================================================
echo ""
echo "=== Identity Package Gate: JDK-only closure ==="
mkdir -p build/identity-cp-empty build/identity-standalone
javac --release 25 -proc:none -cp build/identity-cp-empty \
  -d build/identity-standalone deal/identity/*.java
IDENTITY_IMPORTS="$(grep -hE '^import ' deal/identity/*.java || true)"
IDENTITY_BAD_IMPORTS="$(echo "$IDENTITY_IMPORTS" \
  | grep -vE '^import java\.' \
  | grep -vE '^import deal\.identity\.' || true)"
if [ -n "$IDENTITY_BAD_IMPORTS" ]; then
  echo "  ERROR: deal.identity imports outside java.* and deal.identity:"
  echo "$IDENTITY_BAD_IMPORTS"
  exit 1
fi
echo "  deal.identity is JDK-only (standalone compile and import scan pass)."

# =========================================================================
# Migration gate (verification 7): the legacy start-only record is gone
# (compile-time enforced: deal/lexer/Diagnostic.java is deleted, so any
# leftover start-only call site fails compilation), and two source scans
# assert that (1) no reference to deal.lexer.Diagnostic remains anywhere
# in deal/ or test/ and (2) no production source creates an
# internal-defect note outside the D9 normalization carrier in
# deal/diagnostics/CompilerDiagnostic.java. Either scan tripping fails
# the gate.
# =========================================================================
echo ""
echo "=== Migration Gate: legacy diagnostic surface scans ==="
if [ -e deal/lexer/Diagnostic.java ]; then
  echo "  ERROR: deal/lexer/Diagnostic.java still exists; the legacy start-only record must be deleted."
  exit 1
fi
LEGACY_REFS="$(grep -rn 'deal\.lexer\.Diagnostic' deal test --include='*.java' 2>/dev/null || true)"
if [ -n "$LEGACY_REFS" ]; then
  echo "  ERROR: references to the legacy deal.lexer.Diagnostic record remain:"
  echo "$LEGACY_REFS"
  exit 1
fi
DEFECT_NOTES="$(grep -rn '"internal range defect' deal --include='*.java' 2>/dev/null | grep -v '^deal/diagnostics/CompilerDiagnostic.java:' || true)"
if [ -n "$DEFECT_NOTES" ]; then
  echo "  ERROR: production sources create internal-defect notes outside the D9 normalization carrier:"
  echo "$DEFECT_NOTES"
  exit 1
fi
echo "  Migration gate scans pass (no legacy record, no legacy references, no production defect-note creation)."

# =========================================================================
# Run all tests.
#
# The five heavy suites (backend conformance, JVM backend, the JUnit
# ABI/typing suite, and the two conformance runners) are independent:
# each confines its generated artifacts and subprocess work to its own
# PID-unique temp directories and reads the shared fixture/stdlib trees
# read-only. They run concurrently in the background while the remaining
# phases run sequentially in the foreground, so the whole gate fits its
# wall-clock budget even on a loaded machine. Each background suite is
# waited on before the final verdict; any background failure fails the
# gate exactly like a foreground failure.
#
# The run phase is driven by TEST_MAINS from tools/gate-manifest.sh: each
# record is "<class>|<banner>|<command>"; the dispatcher below reproduces
# today's run order verbatim — background launches first, then the
# foreground mains with the guarded luajit/node suites at their positions
# and the golden-IR check between the pre-activation pin and the
# conformance harness metadata tests.
# =========================================================================
BACKGROUND_PIDS=""

launch_background() {
  "$@" &
  BACKGROUND_PIDS="$BACKGROUND_PIDS $!"
}

cleanup_background() {
  # shellcheck disable=SC2086
  if [ -n "$BACKGROUND_PIDS" ]; then
    kill $BACKGROUND_PIDS 2>/dev/null || true
  fi
}
trap cleanup_background EXIT

for record in "${TEST_MAINS[@]}"; do
  record_class="${record%%|*}"
  record_rest="${record#*|}"
  record_banner="${record_rest%%|*}"
  record_command="${record_rest#*|}"
  if [ -n "$record_banner" ]; then
    echo ""
    echo "$record_banner"
  fi
  case "$record_class" in
    bg)
      read -r -a record_args <<< "$record_command"
      launch_background "${record_args[@]}"
      ;;
    fg)
      read -r -a record_args <<< "$record_command"
      "${record_args[@]}"
      ;;
    luajit|node)
      if command -v "$record_class" &> /dev/null; then
        # Today's command lines run under the guard; the trailing
        # WARNING: line is today's skip message, printed only when the
        # tool is absent.
        while IFS= read -r record_line; do
          case "$record_line" in
            WARNING:*) ;;
            *)
              read -r -a record_args <<< "$record_line"
              "${record_args[@]}"
              ;;
          esac
        done <<< "$record_command"
      else
        while IFS= read -r record_line; do
          case "$record_line" in
            WARNING:*) echo "$record_line" ;;
          esac
        done <<< "$record_command"
      fi
      ;;
    golden-ir)
      GOLDEN_FILE="test/goldens/stdlib-declarations.ir.txt"
      TEMP_FILE="/tmp/deal-stdlib-ir-$$.txt"
      java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
      if [ "${DEAL_UPDATE_GOLDENS}" = "true" ]; then
        cp "$TEMP_FILE" "$GOLDEN_FILE"
        echo "  Golden IR file updated"
      else
        if diff -q "$GOLDEN_FILE" "$TEMP_FILE" > /dev/null 2>&1; then
          echo "  Golden IR file is current"
        else
          echo "  ERROR: Golden IR file differs from generated output!"
          echo "  Run 'DEAL_UPDATE_GOLDENS=true ./run_tests.sh' to update."
          diff "$GOLDEN_FILE" "$TEMP_FILE" || true
          rm -f "$TEMP_FILE"
          exit 1
        fi
      fi
      rm -f "$TEMP_FILE"
      ;;
    *)
      echo "INTERNAL ERROR: unknown TEST_MAINS record class '${record_class}'" >&2
      exit 1
      ;;
  esac
done

echo ""
echo "=== Waiting for background suites ==="
BACKGROUND_FAILED=0
# shellcheck disable=SC2086
for pid in $BACKGROUND_PIDS; do
  if ! wait "$pid"; then
    BACKGROUND_FAILED=1
  fi
done
if [ "$BACKGROUND_FAILED" -eq 1 ]; then
  echo "=== A background test suite failed ===" >&2
  exit 1
fi
trap - EXIT

echo "=== All Tests Passed ==="
