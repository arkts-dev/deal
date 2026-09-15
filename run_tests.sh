#!/bin/bash
set -e

DEFAULT_JOBS=1
JOBS="$DEFAULT_JOBS"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --jobs)
      JOBS="${2:--}"
      shift 2
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$JOBS" in
  ''|-|*[!0-9]*|0) echo "ERROR: --jobs requires a positive integer" >&2; exit 2 ;;
esac
echo "=== DEAL test parallelism: jobs=$JOBS ==="

mkdir -p build

# =========================================================================
# Strict mode (release-r0-r3-strict-gate-mechanics S2(c)/S2(d)): export
# the strict flag so every test JVM inherits it, and tee the complete
# output to the captured gate log build/strict-run-tests.log. The
# output assertion (tools/strict-output-assert.sh, S4) runs over the
# captured log after the background wait and before the final marker.
# Dev mode exports nothing, captures no log, and runs no assertion.
# =========================================================================
if [ -n "${DEAL_STRICT:-}" ]; then
  export DEAL_STRICT=1
  STRICT_LOG="build/strict-run-tests.log"
  STRICT_OUT_FIFO="$STRICT_LOG.stdout.fifo"
  STRICT_ERR_FIFO="$STRICT_LOG.stderr.fifo"
  rm -f "$STRICT_LOG" "$STRICT_OUT_FIFO" "$STRICT_ERR_FIFO"
  # fd 3/4 keep the original stdout/stderr for the post-capture restore.
  exec 3>&1 4>&2
  mkfifo "$STRICT_OUT_FIFO" "$STRICT_ERR_FIFO" \
    || { echo "ERROR: strict log capture fifo creation failed" >&2; exit 1; }
  # fd 5/6 are read-write fifo handles: the open never blocks, and
  # closing them (after the restore below) delivers EOF to the relays.
  exec 5<> "$STRICT_OUT_FIFO"
  exec 6<> "$STRICT_ERR_FIFO"
  # Two fifo/relay pairs keep the two live streams separate through the
  # capture: stdout content is appended to the merged captured log and
  # relayed to the original stdout (fd 3); stderr content is appended to
  # the same merged log and relayed to the original stderr (fd 4), so
  # error tokens such as TOOL_MISSING stay visible on stderr as the
  # strict-mode gate contract pins. Both relays append to the single
  # merged log -- the S4 assertion input -- one printf per line, so
  # concurrent relays never interleave inside a line and the assertion's
  # line surface stays intact.
  ( exec 5>&- 6>&-; while IFS= read -r line || [ -n "$line" ]; do
      printf '%s\n' "$line" >> "$STRICT_LOG"
      printf '%s\n' "$line" >&3
    done < "$STRICT_OUT_FIFO" ) &
  STRICT_RELAY_OUT_PID=$!
  ( exec 5>&- 6>&-; while IFS= read -r line || [ -n "$line" ]; do
      printf '%s\n' "$line" >> "$STRICT_LOG"
      printf '%s\n' "$line" >&4
    done < "$STRICT_ERR_FIFO" ) &
  STRICT_RELAY_ERR_PID=$!
  exec >&5 2>&6
fi

# Single compile/test-list authority: tools/gate-manifest.sh provides
# PROD_SOURCES + TEST_SOURCES (today's javac source list, verbatim) and
# TEST_MAINS (today's run phase, verbatim). See gate-manifest-authority.
source tools/gate-manifest.sh

# Linux distributions commonly install these jars under /usr/share/java, while
# macOS development environments normally resolve them from Maven's local cache.
# Callers may always provide an explicit classpath.
if [ -n "${DEAL_JUNIT_CP:-}" ]; then
  JUNIT_CP="$DEAL_JUNIT_CP"
elif [ -f /usr/share/java/junit4.jar ] && [ -f /usr/share/java/hamcrest-core.jar ]; then
  JUNIT_CP="/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar"
else
  JUNIT_CP="${HOME}/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar:${HOME}/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
fi
if [ ! -f "${JUNIT_CP%%:*}" ] || [ ! -f "${JUNIT_CP##*:}" ]; then
  echo "JUnit 4 and Hamcrest are required; set DEAL_JUNIT_CP to their jar classpath." >&2
  exit 1
fi
for index in "${!TEST_MAINS[@]}"; do
  TEST_MAINS[$index]="${TEST_MAINS[$index]//\/usr\/share\/java\/junit4.jar:\/usr\/share\/java\/hamcrest-core.jar/$JUNIT_CP}"
done

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
  -cp "$JUNIT_CP" \
  # shellcheck disable=SC2206
  ${PROD_SOURCES[@]} "${TEST_SOURCES[@]}"
)
DEALPG4_PREFLIGHT_COORD_ARGS=(
  java -ea -cp build deal.test.containment.PreflightCoordinator
)

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

# Step name for a luajit/node tool suite invocation line, mapped by
# the invoked fixture file (bounded-step-table-and-library D4). The
# suites run unconditionally (ISSUE-0362); the step name is the strict
# step-library table key, not a guard.
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
# Strict mode (S2(b)): the incremental stamp is never read and never
# written -- a full compile runs unconditionally and a pre-existing stamp
# is left untouched. Dev mode keeps the stamp logic verbatim.
if [ -n "${DEAL_STRICT:-}" ]; then
  NEEDS_BUILD=1
else
  NEEDS_BUILD=0
  if [ ! -f "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ run_tests.sh -nt "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ tools/gate-manifest.sh -nt "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ tools/preflight-lib.sh -nt "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ -n "$(find deal test -name '*.java' -newer "$STAMP" -print -quit)" ]; then
    NEEDS_BUILD=1
  elif [ -n "$(find deal test -type d -newer "$STAMP" -print -quit)" ]; then
    NEEDS_BUILD=1
  fi
fi

if [ "$NEEDS_BUILD" = "1" ]; then
  find build -name '*.class' -delete
  echo "=== Compiling all DEAL sources and tests ==="
# -proc:none: no DEAL/test source uses an annotation processor, so javac's
# default processor-discovery pass is pure per-task startup cost (the gate
# budget is shared with the JVM artifact suites; measured ~40% faster
# compile under load).
# PROD_SOURCES is expanded unquoted so the manifest's quoted globs are
# expanded here exactly as they were when the list lived inline; javac
# receives the identical expanded file list it receives today.
  # Strict mode routes the compile through the step library under the
  # compile-dev table entry (S2(e)); dev mode keeps the preflight P4
  # launcher-bounded javac and the stamp update verbatim.
  if [ -n "${DEAL_STRICT:-}" ]; then
    run_step compile-dev -- "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
  else
    dealpg4_preflight_javac "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
    touch "$STAMP"
  fi
else
  echo "=== DEAL sources and tests unchanged since the last build; reusing build/ ==="
fi

# =========================================================================
# Preflight P5: the outer feature supervisor runs the
# deal.test.containment.PreflightCoordinator JVM (the only post-readiness
# JVM this epic owns): authenticated broker HELLO/FEATURE_READY, bounded
# round-trip nested invocations (luajit -v, /bin/true), and the
# in-process live broker session suite (BrokerSessionTestSuite — the
# handshake/capability assertion, ordering negatives, the
# single-connection rule, the scripted wrong-nonce ACK/CANCEL rejections
# through the continuation seam, content/cap/nonzero-exit record
# exchange, the nonce-bound CANCEL flow, and the D7 spawn scans) through
# the inherited broker, a clean session end (the coordinator closes the
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
run_step compile-identity -- javac --release 25 -proc:none \
  -cp build/identity-cp-empty \
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
# Containment spawn gate (dealpg4-java-broker-session-tests D4/D7): a
# pinned static source scan over deal/test/containment — the Java
# containment package may contain no process-spawn surface, so Java
# containment code can never spawn the launcher or an outer (preflight
# D7: post-readiness Java processes use only the inherited broker; the
# P5 coordinator hosts the live suite on that inherited connection
# instead). The package is spawn-free and the scan pins it; any
# violation prints the offending lines and fails the gate — no skip,
# no downgrade.
# =========================================================================
echo ""
echo "=== Containment Spawn Gate: deal/test/containment source scan ==="
CONTAINMENT_SPAWN_SURFACE="$(grep -nE 'ProcessBuilder|Runtime\.getRuntime|\.exec[[:space:]]*\(' deal/test/containment/*.java || true)"
if [ -n "$CONTAINMENT_SPAWN_SURFACE" ]; then
  echo "  ERROR: deal/test/containment contains a process-spawn surface:"
  echo "$CONTAINMENT_SPAWN_SURFACE"
  exit 1
fi
CONTAINMENT_LAUNCHER_SPAWN="$(grep -nE 'deal-process-launcher' deal/test/containment/*.java \
  | grep -E 'ProcessBuilder|Runtime|exec\(|start\(|command' || true)"
if [ -n "$CONTAINMENT_LAUNCHER_SPAWN" ]; then
  echo "  ERROR: deal/test/containment references the launcher in a process-spawn context:"
  echo "$CONTAINMENT_LAUNCHER_SPAWN"
  exit 1
fi
echo "  Containment spawn scan pass (no process-spawn surface and no launcher-spawn reference in deal/test/containment)."

# =========================================================================
# Run all tests.
#
# The heavy suites (backend conformance, JVM backend, the JUnit ABI/typing
# suite, and the three direct conformance runners) are independent:
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
# foreground mains with the luajit/node suites running unconditionally
# (ISSUE-0362, v12-zero-skip-conformance-gate G3: tool absence is a
# preflight P3 failure — TOOL_MISSING <tool> — before any suite starts,
# so no suite is ever skipped) and the golden-IR check between the
# pre-activation pin and the conformance harness metadata tests.
# =========================================================================
BACKGROUND_PIDS=""

launch_background() {
  local step
  step="$(main_step_name "$@")"
  run_step "$step" -- java "-Ddeal.test.jobs=$JOBS" "${@:2}" &
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
      run_step "$(main_step_name "${record_args[@]}")" -- java "-Ddeal.test.jobs=$JOBS" "${record_args[@]:1}"
      ;;
    luajit|node)
      # ISSUE-0362 (v12-zero-skip-conformance-gate G3/G8): the
      # luajit/node suites run unconditionally — the removed
      # `command -v` conditional branch is the issue's retired
      # tool-absence skip. Tool absence now fails preflight P3
      # (TOOL_MISSING <tool>) before any suite starts, and a missing
      # tool reaching this point fails the run via `set -e` (exit 127),
      # never a skip. The record's trailing WARNING: skip line is
      # legacy manifest content and is never printed (the manifest
      # stays untouched).
      while IFS= read -r record_line; do
        case "$record_line" in
          WARNING:*) ;;
          *)
            read -r -a record_args <<< "$record_line"
            run_step "$(tool_line_step "${record_args[@]}")" -- "${record_args[@]}"
            ;;
        esac
      done <<< "$record_command"
      ;;
    golden-ir)
      GOLDEN_FILE="test/goldens/stdlib-declarations.ir.txt"
      TEMP_FILE="/tmp/deal-stdlib-ir-$$.txt"
      # Strict mode drops the dev-only stderr redirect: the library's
      # capped stderr relay carries the tool's stderr (bounded-step-
      # table-and-library D8). Dev mode keeps the exact redirect.
      if [ -n "${DEAL_STRICT:-}" ]; then
        run_step deal.test.GenerateStdlibGoldenIr -- java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE"
      else
        run_step deal.test.GenerateStdlibGoldenIr -- java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
      fi
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

# =========================================================================
# ISSUE-0455 (luajit-ffigen-boundary-integration D3/D7; Architecture item
# 4): the committed FFI integration driver runs after the manifest-driven
# run phase, adjacent to the LuaJIT suites, and is fail-closed — no
# `command -v` guard, no skip path. The driver bootstraps the T2 native
# fixture with GCC, compiles the three committed extern-c fixture projects
# through the production CLI, runs the generated-artifact surface scan
# (gate half 3), and executes the eight-phase scenario matrix under real
# LuaJIT. Any bootstrap failure, scan mismatch, or assertion failure exits
# nonzero; `set -e` (line 2) propagates it and fails the gate, and a
# missing luajit/gcc/java/build/ fails the gate, never a warning.
# =========================================================================
echo ""
echo "=== Running FFI Integration Driver (ISSUE-0455) ==="
luajit test/ffigen_integration.lua

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

# Strict mode (S2(d)/S4): restore the live streams, close the capture,
# and assert the captured log before the final marker. An assertion
# failure fails the script with STRICT_OUTPUT_VIOLATION on stderr.
if [ -n "${DEAL_STRICT:-}" ]; then
  exec >&3 2>&4
  exec 5>&- 6>&-
  wait "$STRICT_RELAY_OUT_PID" || true
  wait "$STRICT_RELAY_ERR_PID" || true
  rm -f "$STRICT_OUT_FIFO" "$STRICT_ERR_FIFO"
  tools/strict-output-assert.sh "$STRICT_LOG"
fi

echo "=== All Tests Passed ==="
