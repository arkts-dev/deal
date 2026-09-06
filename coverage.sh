#!/bin/bash
set -e

# =========================================================================
# JaCoCo coverage gate (ISSUE-0074 / proof gate ISSUE-0080)
#
# Compiles everything with `javac --release 22` (JaCoCo 0.8.12 cannot
# analyze Java 25 bytecode), runs the full suite under the JaCoCo agent
# with the production-only filter (includes=deal.*, excludes=deal.test.*),
# generates the JaCoCo CSV/HTML report, and gates on the CSV totals:
# line >= 75% AND branch >= 55%. The JaCoCo CSV is the only accepted proof.
#
# Improvement statement (ISSUE-0080 acceptance commit): the pre-fix
# baseline was 83.7% line / 70.6% branch (measured over the whole suite);
# post-implementation this gate reports 84.00% line / 71.35% branch with
# deal.codegen.lua.LuaAbi at 100% line and 100% branch (LINE_MISSED=0,
# BRANCH_MISSED=0 in build/coverage.csv) and zero deal.test rows.
# =========================================================================

# Single compile/test-list authority (gate-manifest-authority M1-M3):
# coverage.sh mirrors run_tests.sh's compile list at --release 22 and
# runs the same ordered run phase under the JaCoCo agent.
source tools/gate-manifest.sh
# The run_tests.sh script-local additions (ISSUE-0474/0475, ISSUE-0353)
# join the mirror so both gates compile and run the identical set.
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
# lane suite join the coverage mirror exactly as in run_tests.sh (the
# Shared Lane Contract G4 over the absorbed ConformanceTest compile ->
# LuaBackend -> luajit path; LegacyProfileRegressionCatalog is the A5
# per-case profile-selection authority extracted from ConformanceTest).
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
# suites join the coverage mirror exactly as in run_tests.sh.
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

JACOCO_DIR="/tmp/opencode/jacoco"
JUNIT_CP="/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar"

AGENT="-javaagent:$JACOCO_DIR/jacocoagent.jar=destfile=build/jacoco.exec,append=true,includes=deal.*,excludes=deal.test.*"

# =========================================================================
# DEALPG4 fail-closed toolchain preflight (ISSUE-0183,
# fail-closed-toolchain-preflight D1/D2/D5): the identical ordered phase
# sequence P0-P5 shared verbatim with run_tests.sh via
# tools/preflight-lib.sh, with the coverage-specific assets per D5: P3
# additionally requires the JaCoCo agent/CLI jars and the JUnit/Hamcrest
# jars (absence -> TOOL_MISSING <asset>, never a skip); P4 passes the
# --release 22 compile list; P5 runs the coordinator under the same
# JaCoCo agent flags as the rest of the coverage suites. P0-P3 run
# before any GCC/Javac/Java probe, P4 replaces the raw javac below, and
# P5 runs after the compile. No phase is skipped, downgraded, or
# retried; every failure prints its named token on stderr and exits
# nonzero immediately. No Java process in this script spawns the
# launcher or an outer (D7).
# =========================================================================
source tools/preflight-lib.sh
DEALPG4_PREFLIGHT_JAVAC_ARGS=(
  javac --release 22 -proc:none -d build \
  -cp "$JUNIT_CP" \
  # shellcheck disable=SC2206
  ${PROD_SOURCES[@]} "${TEST_SOURCES[@]}"
)
DEALPG4_PREFLIGHT_COORD_ARGS=(
  java -ea "$AGENT" -cp "build:$JUNIT_CP" deal.test.containment.PreflightCoordinator
)
dealpg4_preflight_extra_tool_check() {
    [ -f "$JACOCO_DIR/jacocoagent.jar" ] \
        || dealpg4_preflight_fail "TOOL_MISSING jacocoagent.jar"
    [ -f "$JACOCO_DIR/jacococli.jar" ] \
        || dealpg4_preflight_fail "TOOL_MISSING jacococli.jar"
    [ -f /usr/share/java/junit4.jar ] \
        || dealpg4_preflight_fail "TOOL_MISSING junit4.jar"
    [ -f /usr/share/java/hamcrest-core.jar ] \
        || dealpg4_preflight_fail "TOOL_MISSING hamcrest-core.jar"
    echo "  JaCoCo agent/CLI and JUnit/Hamcrest jars present"
}

# =========================================================================
# Strict-mode bounded routing (bounded-step-table-and-library D8): every
# tool child dispatches through the shared step library under its pinned
# step name. Dev mode (no DEAL_STRICT) executes the child directly --
# the same child, the same output, the same exit status. Strict mode
# routes through tools/release-step-lib.sh (E5) under the table bound.
# =========================================================================
run_step() {
  local step="$1"; shift
  [ "$#" -ge 1 ] && [ "$1" = "--" ] || { echo "INTERNAL ERROR: malformed run_step invocation" >&2; exit 1; }
  shift
  if [ -n "${DEAL_STRICT:-}" ]; then
    RELEASE_EXPORT_ROOT="$PWD" tools/release-step-lib.sh step_run "$step" -- "$@"
  else
    "$@"
  fi
}

# Step name for a TEST_MAINS java record: the main class token after the
# -cp classpath, or the junit- bundle name for JUnitCore records
# (bounded-step-table-and-library D4).
main_step_name() {
  if [ "$5" = "org.junit.runner.JUnitCore" ]; then
    printf 'junit-%s\n' "$6"
  else
    printf '%s\n' "$5"
  fi
}

# Step name for a guarded luajit/node suite invocation line, mapped by
# the invoked fixture file (bounded-step-table-and-library D4).
tool_line_step() {
  # shellcheck disable=SC2124
  local file="${@: -1}"
  case "$file" in
    test_runtime.lua) printf 'suite-runtime-core\n' ;;
    test_runtime_int32.lua) printf 'suite-runtime-int32\n' ;;
    test/lua_async_export_driver_test.lua) printf 'suite-async-export-driver\n' ;;
    test_runtime_jsonable.lua) printf 'suite-runtime-jsonable\n' ;;
    test_jsonable_js.js) printf 'suite-runtime-js-jsonable\n' ;;
    test_host_js.js) printf 'suite-runtime-js-hostabi\n' ;;
    test_stdlib.lua) printf 'suite-stdlib-lua\n' ;;
    test_stdlib_js.js) printf 'suite-stdlib-js\n' ;;
    test_async_nesting.lua) printf 'suite-async-nesting\n' ;;
    *)
      echo "INTERNAL ERROR: no strict step name for guarded suite file: $file" >&2
      exit 1
      ;;
  esac
}

dealpg4_preflight_run

# =========================================================================
# Reset the build directory before the --release 22 compile below. The
# natural run_tests.sh -> coverage.sh sequence shares one build/ between
# a --release 25 and a --release 22 compile; any stale class file that is
# not in this script's explicit compile list (a leftover from a removed
# source, a file added to only one script's list, or any untracked
# residue) carries Java 25 bytecode and makes the JaCoCo report abort
# with "Error while analyzing ... (Unsupported class file major version)".
# Wiping build/ first guarantees the CSV proof is reproducible from any
# starting state, that only --release 22 class files are analyzed, and
# that no stale build/jacoco.exec is appended to.
# =========================================================================
rm -rf build
mkdir -p build

# =========================================================================
# Strict mode (release-r0-r3-strict-gate-mechanics S2(c)/S2(d)): export
# the strict flag so every test JVM inherits it (before any JVM launch),
# and tee the complete output to the captured gate log
# build/strict-coverage.log. The output assertion
# (tools/strict-output-assert.sh, S4) runs over the captured log after
# the background wait and before the final marker. The capture starts
# after the build/ reset above -- the strict full-reset contract (S2(b)).
# Dev mode exports nothing, captures no log, and runs no assertion.
# =========================================================================
if [ -n "${DEAL_STRICT:-}" ]; then
  export DEAL_STRICT=1
  STRICT_LOG="build/strict-coverage.log"
  rm -f "$STRICT_LOG" "$STRICT_LOG.fifo"
  # fd 3/4 keep the original stdout/stderr for the post-capture restore.
  exec 3>&1 4>&2
  mkfifo "$STRICT_LOG.fifo" \
    || { echo "ERROR: strict log capture fifo creation failed" >&2; exit 1; }
  # fd 5 is a read-write fifo handle: the open never blocks, and closing
  # it (after the restore below) delivers EOF to tee.
  exec 5<> "$STRICT_LOG.fifo"
  ( exec 5>&-; tee "$STRICT_LOG" < "$STRICT_LOG.fifo" >&3 ) &
  STRICT_TEE_PID=$!
  exec >&5 2>&1
fi

# =========================================================================
# Single compilation step at --release 22 (the full mirrored compile
# list, bounded): preflight P4 — launcher run replaces the raw javac
# step (45 s native deadline, 1 MiB drained output).
# =========================================================================
echo "=== Compiling all DEAL sources and tests (--release 22) ==="
# Strict mode routes the compile through the step library under the
# compile-coverage table entry (S2(e)); dev mode keeps the preflight P4
# launcher-bounded javac verbatim.
if [ -n "${DEAL_STRICT:-}" ]; then
  run_step compile-coverage -- "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
else
  dealpg4_preflight_javac "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
fi

# =========================================================================
# Preflight P5: the outer feature supervisor runs the
# deal.test.containment.PreflightCoordinator JVM under the same JaCoCo
# agent flags as the rest of the coverage suites (D5). The agent filter
# excludes deal.test.* from recording, so the preflight adds no coverage
# debt; the coordinator requests all work through the inherited broker.
# Any FAILED record or coordinator failure token exits nonzero. The
# broker socket is unlinked by the outer's final proof.
# =========================================================================
dealpg4_preflight_outer

# Runs a JVM suite under the JaCoCo agent; the first argument is the
# pinned step name for the strict step library (S2(e)) -- the java line
# is the wrapper tail in both modes (bounded-step-table-and-library D8).
run_java() {
  local step="$1"; shift
  run_step "$step" -- java -ea "$AGENT" -cp "build:$JUNIT_CP" "$@"
}

# =========================================================================
# Run the full ordered suite under the agent (the same TEST_MAINS run
# phase as run_tests.sh: background suites in parallel, foreground mains
# in order, guarded luajit/node suites with their verbatim skip
# messages, and the golden-IR check at its position). Each record's
# command shape is "java -ea -cp <cp> <main...>"; the foreground records
# drop the first four tokens and re-run under the agent's own classpath.
# =========================================================================
BACKGROUND_PIDS=""

launch_background() {
  local step
  step="$(main_step_name "$@")"
  run_step "$step" -- "$@" &
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
      run_java "$(main_step_name "${record_args[@]}")" "${record_args[@]:4}"
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
              run_step "$(tool_line_step "${record_args[@]}")" -- "${record_args[@]}"
              ;;
          esac
        done <<< "$record_command"
      else
        # Strict mode (S2(a)): a missing guarded tool is a hard failure --
        # TOOL_MISSING <tool> on stderr, no WARNING skip line, exit
        # nonzero. Dev mode keeps the WARNING skip verbatim.
        if [ -n "${DEAL_STRICT:-}" ]; then
          echo "TOOL_MISSING $record_class" >&2
          exit 1
        fi
        while IFS= read -r record_line; do
          case "$record_line" in
            WARNING:*) echo "$record_line" ;;
          esac
        done <<< "$record_command"
      fi
      ;;
    golden-ir)
      GOLDEN_FILE="test/goldens/stdlib-declarations.ir.txt"
      TEMP_FILE="/tmp/deal-stdlib-ir-cov-$$.txt"
      # Strict mode drops the dev-only stderr redirect (bounded-step-
      # table-and-library D8); dev mode keeps the exact redirect.
      if [ -n "${DEAL_STRICT:-}" ]; then
        run_java deal.test.GenerateStdlibGoldenIr "$TEMP_FILE"
      else
        run_java deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
      fi
      if [ "${DEAL_UPDATE_GOLDENS}" = "true" ]; then
        cp "$TEMP_FILE" "$GOLDEN_FILE"
        echo "  Golden IR file updated"
      else
        if diff -q "$GOLDEN_FILE" "$TEMP_FILE" > /dev/null 2>&1; then
          echo "  Golden IR file is current"
        else
          echo "  ERROR: Golden IR file differs from generated output!"
          echo "  Run 'DEAL_UPDATE_GOLDENS=true ./coverage.sh' to update."
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

# =========================================================================
# JaCoCo report + gate (CSV is the only accepted proof)
# =========================================================================

# The report input is restricted to production class files. The agent
# filter excludes deal.test.* from *recording*; jacococli nevertheless
# lists every class under --classfiles in the CSV, which would emit
# all-missed rows for package deal.test and violate the acceptance
# criterion that no deal.test.* class appears in build/coverage.csv.
# Every compiled production class lives under build/deal except
# build/deal/test (package deal.test), so the find below is exactly the
# production set.
PROD_CLASSFILES=()
while IFS= read -r cf; do
  PROD_CLASSFILES+=("--classfiles" "$cf")
done < <(find build/deal -name '*.class' | grep -v '^build/deal/test/')
if [ "${#PROD_CLASSFILES[@]}" -eq 0 ]; then
  echo "ERROR: no production class files found under build/deal" >&2
  exit 1
fi

echo ""
echo "=== Generating JaCoCo report ==="
run_step coverage-report -- java -jar "$JACOCO_DIR/jacococli.jar" report \
  build/jacoco.exec \
  "${PROD_CLASSFILES[@]}" \
  --sourcefiles deal \
  --csv build/coverage.csv --html build/coverage-html

# Hard acceptance criterion: zero deal.test.* rows in the CSV.
if awk -F, 'NR > 1 && $2 == "deal.test" { found = 1 } END { exit found ? 1 : 0 }' build/coverage.csv; then
  :
else
  echo "ERROR: build/coverage.csv contains deal.test rows (agent filter or classfiles restriction failed)" >&2
  exit 1
fi

COVERAGE_TOTALS="$(LC_ALL=C awk -F, '
NR==1 {
  for (i = 1; i <= NF; i++) {
    if ($i == "LINE_MISSED")      lm = i;
    if ($i == "LINE_COVERED")     lc = i;
    if ($i == "BRANCH_MISSED")    bm = i;
    if ($i == "BRANCH_COVERED")   bc = i;
  }
  next;
}
# Belt-and-braces: deal.test.* is excluded from recording by the agent
# filter and absent from the report input, so its rows must not exist;
# skip them anyway so totals can never be skewed by an all-missed test row.
$2 == "deal.test" { next; }
{
  lmiss += $lm;
  lcov  += $lc;
  bmiss += $bm;
  bcov  += $bc;
}
END {
  if (lmiss + lcov == 0) { lp = 0 } else { lp = (lcov / (lmiss + lcov)) * 100 }
  if (bmiss + bcov == 0) { bp = 0 } else { bp = (bcov / (bmiss + bcov)) * 100 }
  printf "%.2f %.2f\n", lp, bp;
}' build/coverage.csv)"

LINE_PCT="$(echo "$COVERAGE_TOTALS" | awk '{print $1}')"
BRANCH_PCT="$(echo "$COVERAGE_TOTALS" | awk '{print $2}')"

echo ""
echo "=== JaCoCo production coverage (deal.*, excluding deal.test.*) ==="
echo "Line coverage:   ${LINE_PCT}%  (gate: >= 75%)"
echo "Branch coverage: ${BRANCH_PCT}%  (gate: >= 55%)"

# The gate check runs under `set -e`: a failing awk would terminate the
# script before the diagnostic below could run, so the awk exit status is
# consumed by the `if` itself, keeping the error message reachable and the
# failure loud instead of silent.
if LC_ALL=C awk -v line="$LINE_PCT" -v branch="$BRANCH_PCT" \
  'BEGIN { exit (line >= 75.0 && branch >= 55.0) ? 0 : 1 }'; then
  :
else
  echo "ERROR: coverage gate failed (line >= 75% AND branch >= 55%)" >&2
  exit 1
fi

# Strict mode (S2(d)/S4): restore the live streams, close the capture,
# and assert the captured log before the final marker. An assertion
# failure fails the script with STRICT_OUTPUT_VIOLATION on stderr.
if [ -n "${DEAL_STRICT:-}" ]; then
  exec >&3 2>&4
  exec 5>&-
  wait "$STRICT_TEE_PID" || true
  rm -f "$STRICT_LOG.fifo"
  tools/strict-output-assert.sh "$STRICT_LOG"
fi

echo "=== Coverage gate passed ==="
