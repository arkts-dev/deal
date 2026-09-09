#!/bin/bash
# tools/release.sh -- the committed R0-R3 release (release-r0-r3-strict-gate-
# mechanics S1, S6, S7, S8, S9; release-pipeline-strict-mode-and-evidence
# D1/D3's R0-R3 slice). One script; the phases are inseparable because the
# success semantics (exit 0 only after R3 with the complete gate record)
# require the full R0-R3 sequence.
#
# Phase sequence, exactly (S9):
#   R0 preflight (checkout):
#     (1) the static audit invocation (tools/release-static-audit.sh, raw --
#         the audit's textual inspection is an exemption: it creates no tool
#         subprocess) and the library's step-table parse check -- the first
#         step_run below parses tools/release-step-table.json with the pinned
#         /usr/bin/python3 under its named 10 s bound before any step child
#         starts; a parse failure or timeout prints RELEASE_TABLE_PARSE_FAILED;
#     (2) preflight-integrity (checkout-cwd table entry): the sha256sum
#         digest of the committed launcher artifact
#         tools/deal-process-launcher-linux-x86_64 against
#         tools/launcher-manifest.json plus the file-type checks (the
#         launcher integrity tokens: SYMLINK, MISSING, NOT_REGULAR,
#         NOT_EXECUTABLE, MANIFEST_INVALID, DIGEST_MISMATCH);
#     (3) the launcher probe (exempt from the table -- the bounding mechanism
#         itself carries embedded self-bounds; script-side safety net
#         `timeout 60`): identity/LIMITS-shape/micro-battery legs with the
#         tokens CAPABILITY_MISSING, PROTOCOL_MISMATCH, PLATFORM_MISMATCH,
#         PROBE_TIMEOUT, CONFIG_INVALID (tools/verify-launcher.sh leg 3);
#     (4) preflight-limits (checkout-cwd table entry): the pinned
#         /usr/bin/python3 LIMITS cross-check of the probe LIMITS line
#         against the manifest records (LIMITS_MISMATCH);
#     (5) tool presence -- `command -v` for exactly gcc javac java luajit
#         node (TOOL_MISSING <tool>). The JaCoCo/JUnit/Hamcrest assets are
#         deliberately not R0 prerequisites -- their absence surfaces at R3.
#   R1 clean export (S6): the release-export checkout-cwd table entry runs
#     `git archive HEAD | tar -x -C <scratch>` against the checkout
#     repository into the pre-created scratch; any failure to produce or
#     extract the archive prints RELEASE_CLEAN_EXPORT_FAILED and exits
#     nonzero. The export carries no .git and no build/; R2-R3 run
#     exclusively in the export root; no working-tree byte enters any gate
#     decision.
#   R2(a) (S9): the strict-run-tests whole-script Tier-2 table step runs
#     `DEAL_STRICT=1 ./run_tests.sh` in the export root (step-log record
#     cwd = export). Step failure prints RELEASE_GATE_FAILED run-tests on
#     stderr and exits nonzero; the script's own tokens (TOOL_MISSING,
#     STRICT_SKIP_DETECTED, STRICT_OUTPUT_VIOLATION, the suite failures)
#     remain visible beneath it; E5 containment/timeout prints
#     RELEASE_STEP_TIMEOUT strict-run-tests / RELEASE_STEP_CONTAINMENT
#     <token>.
#   R2(b) (S7): after R2(a) succeeds, the pinned runtime-assertion query in
#     a subshell with the export root as cwd; non-empty output prints the
#     weak paths and RUNTIME_ASSERTION_GATE_FAILED to stderr and exits
#     nonzero.
#   R3 (S9): the strict-coverage whole-script Tier-2 table step runs
#     `DEAL_STRICT=1 ./coverage.sh` in the export root. Step failure prints
#     RELEASE_COVERAGE_GATE_FAILED on stderr (the script's own
#     COVERAGE_GATE_FAILED / MANIFEST_SELF_CHECK_FAILED / TOOL_MISSING /
#     asset-missing errors remain visible beneath it) and exits nonzero;
#     then the coverage-csv-digest export-cwd Tier-1 entry computes the
#     pinned /usr/bin/sha256sum digest of build/coverage.csv (its nonzero
#     exit maps to RELEASE_COVERAGE_GATE_FAILED).
#   End: after R3 the release stops with exit 0 and the complete gate record;
#     the scratch is retained (R0-R3 code never removes it -- E8's R7d).
#
# Gate record (S8): one pinned JSON file <export>/release/gate-record.json,
# fixed field order, all fields always present, null for not-yet-produced
# values: {schemaVersion: 1, skipped, weakFixtures, lineCoverage,
# branchCoverage, coverageCsv, coverageCsvSha256}. Updated incrementally:
# after R2(a), skipped = the maximum skip count parsed from the pinned
# summary segments of build/strict-run-tests.log (`Skipped: N`, `N skipped`,
# `N skipped (classified)`) -- 0 on a green run, null when the log is absent
# or carries no pinned segment; after R2(b), weakFixtures = the printed
# weak-path count; after R3, lineCoverage/branchCoverage parsed from the
# pinned print lines of build/strict-coverage.log (recorded on failure too),
# coverageCsv = "build/coverage.csv" when the CSV exists, coverageCsvSha256
# = its SHA-256. The record is written with printf/redirection (no tool
# subprocess); a write failure prints RELEASE_GATE_RECORD_FAILED and exits
# nonzero. The record is E8's R7 input surface.
#
# The export scratch (S6): created before any R0 step as
# ${TMPDIR:-/tmp}/deal-release-<nonce> with a 32-hex nonce read from
# /dev/urandom (16 bytes, od-rendered -- the pinned nonce policy), so the
# run's step log has one fixed home <export>/release/step-log.jsonl for the
# whole run (pre-export records append via the absolute path; export-phase
# records via the export-relative path -- one file, execution order).
#
# Step-name consumption contract (S9): this script requests exactly
# preflight-integrity, preflight-limits, release-export (checkout-cwd),
# strict-run-tests, strict-coverage (Tier-2 whole-script), and
# coverage-csv-digest (Tier-1) from tools/release-step-table.json through
# tools/release-step-lib.sh (E5's committed library -- consumed, not
# implemented here). The checkout root and the export root travel in the
# library's RELEASE_CHECKOUT_ROOT / RELEASE_EXPORT_ROOT environment
# variables for per-entry cwd-class resolution.
#
# Every phase failure prints its named token on stderr and exits nonzero;
# no retry, no fallback skip anywhere. The committed phase functions below
# are the verification surface the battery tools/test/test-release-r0-r3.sh
# drives with synthetic scratch inputs; the script executes main() only when
# invoked directly, so a sourcing battery defines the functions without
# running the release.
set -eu

# --- Pinned surfaces ------------------------------------------------------
RELEASE_STATIC_AUDIT="./tools/release-static-audit.sh"
RELEASE_LAUNCHER="tools/deal-process-launcher-linux-x86_64"
RELEASE_MANIFEST="tools/launcher-manifest.json"
RELEASE_SHA256SUM="/usr/bin/sha256sum"
RELEASE_PYTHON3="/usr/bin/python3"

# Stage capability bitmask and the probe report shape, mirrored from
# tools/verify-launcher.sh (leg 3) and tools/preflight-lib.sh: the probe
# report is exactly 7 lines at this stage -- identity, LIMITS, five OK
# lines in canonical order -- and the identity line must match exactly.
RELEASE_EXPECTED_CAPS=31
RELEASE_PROBE_REPORT_LINES=7

# --- Gate-record state (S8) -----------------------------------------------
# Fixed field order, all fields always present, null for not-yet-produced
# values. The run sets GATE_RECORD_PATH after the scratch creation; the
# battery sources this script and supplies its own path.
RECORD_SKIPPED="null"
RECORD_WEAK="null"
RECORD_LINE="null"
RECORD_BRANCH="null"
RECORD_CSV="null"
RECORD_CSV_SHA="null"
GATE_RECORD_PATH=""

# The run's concrete roots: the checkout root (this script runs with the
# checkout as its own cwd -- it never cd's) and the pre-created export
# scratch (absolute).
CHECKOUT_ROOT=""
EXPORT_ROOT=""

# =========================================================================
# R1 argv: the release-export checkout-cwd entry runs the pinned
# `git archive HEAD | tar -x -C <scratch>` form inside one bounded /bin/sh
# tree (git and tar are the step's own children; the extraction target is
# the pre-created scratch, passed as $1). The committed-tree sentinels make
# the step fail closed when git silently produced nothing (a not-found git
# leaves tar reading empty input, which would otherwise exit 0): the
# archive, the extraction, or the committed content missing is one nonzero
# step exit mapped to RELEASE_CLEAN_EXPORT_FAILED by the caller.
# =========================================================================
RELEASE_EXPORT_SCRIPT='git archive HEAD | tar -x -C "$1" && test -f "$1/run_tests.sh" && test -f "$1/coverage.sh" && test -f "$1/deal/Main.java" && test ! -e "$1/.git" && test ! -e "$1/build"'

# =========================================================================
# R0 (2) argv: the preflight-integrity checkout-cwd entry runs the launcher
# integrity check in one bounded /bin/sh tree -- the canonical token order
# of tools/preflight-lib.sh P0 (a symlink is named before any other check,
# then existence, regular-file, executable), the manifest digest extraction,
# and the /usr/bin/sha256sum digest comparison. One single line: the step
# log's argv record is one JSONL line (bounded-step-table-and-library D5).
# =========================================================================
RELEASE_INTEGRITY_SCRIPT='set -eu; L=tools/deal-process-launcher-linux-x86_64; M=tools/launcher-manifest.json; if [ -L "$L" ]; then echo SYMLINK >&2; exit 1; fi; if [ ! -e "$L" ]; then echo MISSING >&2; exit 1; fi; if [ ! -f "$L" ]; then echo NOT_REGULAR >&2; exit 1; fi; if [ ! -x "$L" ]; then echo NOT_EXECUTABLE >&2; exit 1; fi; EXPECTED=$(sed -n "s/.*\"sha256\"[[:space:]]*:[[:space:]]*\"\([0-9a-f]*\)\".*/\1/p" "$M"); case "$EXPECTED" in ""|*[!0-9a-f]*) echo MANIFEST_INVALID >&2; exit 1 ;; esac; if [ "${#EXPECTED}" -ne 64 ]; then echo MANIFEST_INVALID >&2; exit 1; fi; ACTUAL=$(/usr/bin/sha256sum "$L" | cut -d" " -f1); if [ "$ACTUAL" != "$EXPECTED" ]; then echo DIGEST_MISMATCH >&2; exit 1; fi; echo "preflight-integrity: PASS (regular non-symlink executable; SHA-256 matches tools/launcher-manifest.json)"'

# =========================================================================
# R0 (4) argv: the preflight-limits checkout-cwd entry runs the pinned
# /usr/bin/python3 LIMITS cross-check -- the probe LIMITS line captured at
# R0 (3) against the manifest invocationLimits + outerLimits +
# selftestLimits records in the exact probe LIMITS field order. The script
# body is a per-run scratch file under <export>/release/ (written with
# printf/redirection -- no tool subprocess -- so the step's argv record
# stays one JSONL line); any mismatch (or a missing LIMITS line) prints
# LIMITS_MISMATCH; an unreadable manifest prints MANIFEST_INVALID.
# =========================================================================
RELEASE_LIMITS_SCRIPT_BODY='import json
import sys

try:
    m = json.load(open(sys.argv[1]))
except Exception:
    sys.stderr.write("MANIFEST_INVALID\n")
    sys.exit(1)
try:
    inv = m["invocationLimits"]
    outer = m["outerLimits"]
    selftest = m["selftestLimits"]
    expected = " ".join(str(v) for v in (
        inv["overallTimeoutMs"], inv["startupTimeoutMs"],
        inv["executionCutoffMs"], inv["termGraceMs"],
        inv["killAndProofReserveMs"], inv["finalizationReserveMs"],
        outer["overallTimeoutMs"], outer["readinessTimeoutMs"],
        outer["nestedStopMs"], outer["cleanupReserveMs"],
        outer["brokerStallMs"], selftest["selftestTimeoutMs"]))
except Exception:
    sys.stderr.write("MANIFEST_INVALID\n")
    sys.exit(1)
try:
    lines = open(sys.argv[2]).read().splitlines()
except Exception:
    sys.stderr.write("LIMITS_MISMATCH\n")
    sys.exit(1)
lim_line = next((l for l in lines if l.startswith("LIMITS ")), None)
if lim_line is None or lim_line[len("LIMITS "):] != expected:
    sys.stderr.write("LIMITS_MISMATCH\n")
    sys.exit(1)
print("preflight-limits: PASS (probe LIMITS fields equal the manifest records, canonical order)")
'

# =========================================================================
# Committed dispatch (S9): every tool child this script runs goes through
# the shared step library under its pinned step name, with the caller-
# supplied roots in the library's environment. A name absent from the
# committed table fails RELEASE_UNBOUNDED_PROCESS at invocation (before any
# child starts); the library's parse failure or timeout fails
# RELEASE_TABLE_PARSE_FAILED.
# =========================================================================
release_step_run() {
  tools/release-step-lib.sh step_run "$@"
}

# =========================================================================
# Gate record (S8): printf/redirection only -- no tool subprocess. A write
# failure prints RELEASE_GATE_RECORD_FAILED on stderr and exits nonzero
# (fail closed: the record is E8's R7 input surface, so a release must not
# continue without it).
# =========================================================================
write_gate_record() {
  if ! printf '%s\n' \
    "{\"schemaVersion\": 1, \"skipped\": $RECORD_SKIPPED, \"weakFixtures\": $RECORD_WEAK, \"lineCoverage\": $RECORD_LINE, \"branchCoverage\": $RECORD_BRANCH, \"coverageCsv\": $RECORD_CSV, \"coverageCsvSha256\": $RECORD_CSV_SHA}" \
    > "$GATE_RECORD_PATH"; then
    echo "RELEASE_GATE_RECORD_FAILED" >&2
    exit 1
  fi
}

# =========================================================================
# S8 parsing: the maximum skip count from the pinned summary segments of a
# strict-run-tests capture log -- `Skipped: N`, `N skipped`, and
# `N skipped (classified)`. Prints the maximum (0 for an all-zero green
# log); prints nothing and returns 1 when the log is absent or carries no
# pinned segment (the caller records null).
# =========================================================================
parse_skip_max() {
  local log="$1" counts n max_seen have
  if [ ! -f "$log" ]; then
    return 1
  fi
  # grep -oE emits one token per pinned segment occurrence -- "Skipped: N",
  # "N skipped (classified)", or "N skipped," -- and sed keeps each token's
  # digits, so multi-digit counts (e.g. "45 skipped (classified)") parse
  # whole (a greedy trailing .* inside one sed substitution would steal
  # leading digits). A log carrying no pinned segment leaves counts empty.
  counts=$(grep -oE 'Skipped: [0-9]+|[0-9]+ skipped \(classified\)|[0-9]+ skipped,' "$log" \
    | sed -e 's/[^0-9]//g')
  max_seen=0
  have=0
  # Intentional word splitting: each token is one small integer string.
  for n in $counts; do
    case "$n" in
      ''|*[!0-9]*) continue ;;
    esac
    have=1
    if [ "$n" -gt "$max_seen" ]; then
      max_seen=$n
    fi
  done
  if [ "$have" -ne 1 ]; then
    return 1
  fi
  printf '%s' "$max_seen"
}

# =========================================================================
# S8 parsing: one pinned print line of a strict-coverage capture log --
# "Line coverage: <pct>% (gate: 100)" / "Branch coverage: <pct>% (gate:
# 100)". Prints the percentage; prints nothing and returns 1 when the log
# is absent or carries no pinned print line.
# =========================================================================
parse_coverage_pct() {
  local log="$1" kind="$2" value
  if [ ! -f "$log" ]; then
    return 1
  fi
  value=$(sed -n "s/.*$kind coverage: \([0-9][0-9]*\(\.[0-9][0-9]*\)*\)% (gate: 100).*/\1/p" "$log" | head -n 1)
  if [ -z "$value" ]; then
    return 1
  fi
  case "$value" in
    ''|*[!0-9.]*) return 1 ;;
  esac
  printf '%s' "$value"
}

# =========================================================================
# S8 incremental update after R2(a): skipped = the parsed maximum (0 on a
# green run, null when the log is absent or carries no pinned segment);
# the not-yet-produced fields stay null. Runs on the failure path too --
# the record is E8's evidence-on-failure surface.
# =========================================================================
update_record_after_r2a() {
  local log="$1" skipped
  if skipped=$(parse_skip_max "$log"); then
    RECORD_SKIPPED="$skipped"
  else
    RECORD_SKIPPED="null"
  fi
  write_gate_record
}

# =========================================================================
# S8 incremental update after R3: lineCoverage/branchCoverage parsed from
# the pinned print lines of build/strict-coverage.log (recorded on failure
# too -- a failed attempt records its shortfall), coverageCsv =
# "build/coverage.csv" when the CSV exists. Runs before the digest step, so
# a digest failure retains the parsed shortfall as well.
# =========================================================================
update_record_after_r3() {
  local log="$1" csv="$2" line branch
  if line=$(parse_coverage_pct "$log" Line); then
    RECORD_LINE="$line"
  else
    RECORD_LINE="null"
  fi
  if branch=$(parse_coverage_pct "$log" Branch); then
    RECORD_BRANCH="$branch"
  else
    RECORD_BRANCH="null"
  fi
  if [ -f "$csv" ]; then
    RECORD_CSV='"build/coverage.csv"'
  else
    RECORD_CSV="null"
  fi
  write_gate_record
}

# =========================================================================
# R0 (3): the launcher probe -- exempt from the table (the bounding
# mechanism itself carries embedded self-bounds; the `timeout 60` guard is
# the fail-closed safety net of tools/verify-launcher.sh leg 3). Legs:
# identity line ("DEALPG4 <version> <platform> CAPS 31" field-checked
# against the manifest protocol/version/platform), the exact 12-field
# LIMITS line shape, and the five canonical OK lines in order. The probe
# report is captured at <export>/release/r0-probe.out for R0 (4)'s
# preflight-limits cross-check. Token mapping: PROBE_TIMEOUT (124/5),
# CONFIG_INVALID (3), CAPABILITY_MISSING (4 or any other exit),
# PROTOCOL_MISMATCH, PLATFORM_MISMATCH, MANIFEST_INVALID (a manifest field
# that cannot be read).
# =========================================================================
r0_probe() {
  local out err status nlines id_line lim_line lim_fields
  local p_protocol p_version p_platform p_caps_kw p_caps p_rest
  local m_protocol m_version m_platform expected_id actual_ok expected_ok
  out="$EXPORT_ROOT/release/r0-probe.out"
  err="$EXPORT_ROOT/release/r0-probe.err"

  m_protocol=$(sed -n 's/.*"protocol"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RELEASE_MANIFEST")
  m_version=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$RELEASE_MANIFEST")
  m_platform=$(sed -n 's/.*"platform"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RELEASE_MANIFEST")
  if [ -z "$m_protocol" ] || [ -z "$m_version" ] || [ -z "$m_platform" ]; then
    echo "MANIFEST_INVALID" >&2
    return 1
  fi
  case "$m_protocol$m_version$m_platform" in
    *[!A-Za-z0-9_.-]*)
      echo "MANIFEST_INVALID" >&2
      return 1
      ;;
  esac

  if ! timeout 60 "$RELEASE_LAUNCHER" probe >"$out" 2>"$err"; then
    status=$?
    cat "$err" >&2
    case "$status" in
      124|5) echo "PROBE_TIMEOUT" >&2; return 1 ;;
      3) echo "CONFIG_INVALID" >&2; return 1 ;;
      4) echo "CAPABILITY_MISSING" >&2; return 1 ;;
      *) echo "CAPABILITY_MISSING" >&2; return 1 ;;
    esac
  fi
  cat "$err" >&2

  # The report is exactly 7 lines at this stage: identity, LIMITS, five OK
  # lines in canonical order.
  nlines=$(wc -l < "$out" | tr -d ' ')
  if [ "$nlines" -ne "$RELEASE_PROBE_REPORT_LINES" ]; then
    cat "$out" >&2
    echo "CAPABILITY_MISSING" >&2
    return 1
  fi
  id_line=$(sed -n '1p' "$out")
  lim_line=$(sed -n '2p' "$out")

  [ -n "$id_line" ] || { echo "CAPABILITY_MISSING" >&2; return 1; }
  if ! read -r p_protocol p_version p_platform p_caps_kw p_caps p_rest <<EOF
$id_line
EOF
  then
    echo "CAPABILITY_MISSING" >&2
    return 1
  fi
  [ "$p_protocol" = "$m_protocol" ] || { echo "PROTOCOL_MISMATCH" >&2; return 1; }
  [ "$p_version" = "$m_version" ] || { echo "PROTOCOL_MISMATCH" >&2; return 1; }
  [ "$p_platform" = "$m_platform" ] || { echo "PLATFORM_MISMATCH" >&2; return 1; }
  [ "$p_caps_kw" = "CAPS" ] || { echo "CAPABILITY_MISSING" >&2; return 1; }
  case "$p_caps" in
    ''|*[!0-9]*) echo "CAPABILITY_MISSING" >&2; return 1 ;;
  esac
  [ "$p_caps" -eq "$RELEASE_EXPECTED_CAPS" ] || { echo "CAPABILITY_MISSING" >&2; return 1; }
  [ -z "$p_rest" ] || { echo "CAPABILITY_MISSING" >&2; return 1; }
  expected_id="DEALPG4 $m_version $m_platform CAPS $RELEASE_EXPECTED_CAPS"
  [ "$id_line" = "$expected_id" ] || { echo "CAPABILITY_MISSING" >&2; return 1; }

  # LIMITS line: the exact 12-field numeric shape. The field-by-field
  # manifest cross-check is R0 (4) preflight-limits' pinned python3 leg.
  case "$lim_line" in
    "LIMITS "*) ;;
    *) echo "CAPABILITY_MISSING" >&2; return 1 ;;
  esac
  lim_fields=${lim_line#LIMITS }
  set -- $lim_fields
  if [ "$#" -ne 12 ]; then
    echo "CAPABILITY_MISSING" >&2
    return 1
  fi
  for field in "$@"; do
    case "$field" in
      ''|*[!0-9]*) echo "CAPABILITY_MISSING" >&2; return 1 ;;
    esac
  done

  # The five canonical OK lines, in order, byte-stable.
  expected_ok=$(printf 'OK monotonic-timer\nOK subreaper\nOK parent-death\nOK negative-pgid\nOK bounded-drain')
  actual_ok=$(tail -n 5 "$out")
  if [ "$actual_ok" != "$expected_ok" ]; then
    cat "$out" >&2
    echo "CAPABILITY_MISSING" >&2
    return 1
  fi
  cat "$out"
  echo "  probe green: identity and 12 LIMITS fields in shape, five OK batteries"
}

# =========================================================================
# R2(b) (S7): the pinned runtime-assertion query over the export's
# committed fixtures, executed in a subshell with the export root as cwd.
# weakFixtures is the printed path count; the record is updated before the
# verdict (E8's evidence-on-failure surface). Non-empty output: the weak
# paths print to stderr, RUNTIME_ASSERTION_GATE_FAILED prints to stderr,
# return 1. Empty output: the gate passes. The query creates no tool
# subprocess beyond grep (a trivial POSIX utility -- no table entry) and
# reads only the export's committed bytes.
# =========================================================================
phase_r2b() {
  local export_root="$1" weak p weak_count
  weak=$(cd "$export_root" && grep -RIl '^// @expected: runtime-ok' test/conformance/backend-runtime --include='*.deal' | while read -r f; do grep -q 'code: "TEST_FAIL"' "$f" || printf '%s\n' "$f"; done)
  weak_count=0
  while IFS= read -r p; do
    if [ -n "$p" ]; then
      weak_count=$((weak_count + 1))
    fi
  done <<EOF
$weak
EOF
  RECORD_WEAK="$weak_count"
  write_gate_record
  if [ "$weak_count" -gt 0 ]; then
    printf '%s\n' "$weak" >&2
    echo "RUNTIME_ASSERTION_GATE_FAILED" >&2
    return 1
  fi
  echo "  runtime assertion gate green: weakFixtures 0 (every runtime-ok fixture carries code: \"TEST_FAIL\")"
  return 0
}

# =========================================================================
# R3 (S9): the strict-coverage whole-script Tier-2 step, then the
# coverage-csv-digest export-cwd Tier-1 entry (the pinned
# /usr/bin/sha256sum), then the final record update. Any step failure
# prints RELEASE_COVERAGE_GATE_FAILED on stderr and returns 1; the
# shortfall percentages are recorded on failure too (S8). The digest entry
# runs only when build/coverage.csv exists (a successful strict run always
# produces it -- a missing CSV is itself a failure).
# =========================================================================
phase_r3() {
  local export_root="$1" digest csv_sha
  export DEAL_STRICT=1
  if ! release_step_run strict-coverage -- ./coverage.sh; then
    update_record_after_r3 "$export_root/build/strict-coverage.log" "$export_root/build/coverage.csv"
    echo "RELEASE_COVERAGE_GATE_FAILED" >&2
    return 1
  fi
  update_record_after_r3 "$export_root/build/strict-coverage.log" "$export_root/build/coverage.csv"
  if [ ! -f "$export_root/build/coverage.csv" ]; then
    echo "RELEASE_COVERAGE_GATE_FAILED" >&2
    return 1
  fi
  if ! digest=$(release_step_run coverage-csv-digest -- "$RELEASE_SHA256SUM" build/coverage.csv); then
    echo "RELEASE_COVERAGE_GATE_FAILED" >&2
    return 1
  fi
  csv_sha=$(printf '%s\n' "$digest" | sed -n 's/^\([0-9a-f]*\)[[:space:]].*/\1/p')
  case "$csv_sha" in
    ''|*[!0-9a-f]*)
      echo "RELEASE_COVERAGE_GATE_FAILED" >&2
      return 1
      ;;
  esac
  if [ "${#csv_sha}" -ne 64 ]; then
    echo "RELEASE_COVERAGE_GATE_FAILED" >&2
    return 1
  fi
  RECORD_CSV_SHA="\"$csv_sha\""
  write_gate_record
  echo "  strict coverage gate green: line $RECORD_LINE / branch $RECORD_BRANCH, CSV digest recorded"
  return 0
}

# =========================================================================
# main: the R0-R3 phase sequence (S9). Runs only when the script is
# invoked directly (a sourcing battery defines the committed functions
# without running the release).
# =========================================================================
main() {
  local nonce tool limits_script

  CHECKOUT_ROOT="$PWD"
  case "$CHECKOUT_ROOT" in
    /*) ;;
    *)
      echo "ERROR: release.sh must run from an absolute checkout path" >&2
      exit 1
      ;;
  esac

  # S6: the export scratch is created before any R0 step --
  # ${TMPDIR:-/tmp}/deal-release-<nonce>, 32-hex nonce from /dev/urandom
  # (16 bytes, od-rendered -- the pinned nonce policy) -- so the run's step
  # log has one fixed home <export>/release/step-log.jsonl for the whole
  # run. R0-R3 code never removes the scratch (E8's R7d does).
  nonce=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
  case "$nonce" in
    ''|*[!0-9a-f]*)
      echo "ERROR: export scratch nonce generation failed" >&2
      exit 1
      ;;
  esac
  if [ "${#nonce}" -ne 32 ]; then
    echo "ERROR: export scratch nonce generation failed" >&2
    exit 1
  fi
  EXPORT_ROOT="${TMPDIR:-/tmp}/deal-release-$nonce"
  if ! mkdir -p "$EXPORT_ROOT/release"; then
    echo "ERROR: cannot create the export scratch $EXPORT_ROOT" >&2
    exit 1
  fi
  GATE_RECORD_PATH="$EXPORT_ROOT/release/gate-record.json"

  # The caller-supplied roots for the library's per-entry cwd-class
  # resolution (bounded-step-table-and-library D2): the checkout root and
  # the export root, both absolute.
  export RELEASE_CHECKOUT_ROOT="$CHECKOUT_ROOT"
  export RELEASE_EXPORT_ROOT="$EXPORT_ROOT"

  echo "=== release.sh R0-R3 (checkout: $CHECKOUT_ROOT, export scratch: $EXPORT_ROOT) ==="

  # --- R0 (1): static audit + the step-table parse check ------------------
  echo ""
  echo "=== R0 preflight (1): static audit (whole release-run surface) and the step-table parse check ==="
  # The static audit asserts the whole release-run surface and creates no
  # tool subprocess (release-static-audit D1) -- invoked raw; a violation
  # prints RELEASE_UNBOUNDED_PROCESS and exits nonzero.
  if ! "$RELEASE_STATIC_AUDIT"; then
    exit 1
  fi
  # The library's step-table parse check: the first step_run below parses
  # tools/release-step-table.json with the pinned /usr/bin/python3 under
  # its named 10 s bound before any step child starts -- a parse failure or
  # timeout prints RELEASE_TABLE_PARSE_FAILED (bounded-step-table-and-
  # library D3). The very first tool process of the run is therefore
  # bounded like every other (bounded-subprocesses-release-pipeline D4).

  # --- R0 (2): preflight-integrity (checkout-cwd table entry) -------------
  echo ""
  echo "=== R0 preflight (2): preflight-integrity (checkout-cwd entry: committed launcher artifact) ==="
  if ! release_step_run preflight-integrity -- /bin/sh -c "$RELEASE_INTEGRITY_SCRIPT" sh; then
    exit 1
  fi

  # --- R0 (3): launcher probe (exempt from the table) ----------------------
  echo ""
  echo "=== R0 preflight (3): launcher probe (identity, LIMITS shape, five OK batteries; exempt from the table) ==="
  r0_probe || exit 1

  # --- R0 (4): preflight-limits (checkout-cwd table entry) -----------------
  echo ""
  echo "=== R0 preflight (4): preflight-limits (checkout-cwd entry: the pinned /usr/bin/python3 LIMITS cross-check) ==="
  # The script body is a per-run scratch file (printf/redirection -- no
  # tool subprocess), so the step's argv record stays one JSONL line.
  limits_script="$EXPORT_ROOT/release/r0-preflight-limits.py"
  if ! printf '%s\n' "$RELEASE_LIMITS_SCRIPT_BODY" > "$limits_script"; then
    echo "ERROR: cannot write the preflight-limits script $limits_script" >&2
    exit 1
  fi
  if ! release_step_run preflight-limits -- "$RELEASE_PYTHON3" "$limits_script" "$RELEASE_MANIFEST" "$EXPORT_ROOT/release/r0-probe.out"; then
    exit 1
  fi

  # --- R0 (5): fail-closed tool presence -----------------------------------
  echo ""
  echo "=== R0 preflight (5): fail-closed tool presence (gcc javac java luajit node) ==="
  for tool in gcc javac java luajit node; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "TOOL_MISSING $tool" >&2
      exit 1
    fi
  done
  echo "  gcc, javac, java, luajit, node present"

  # --- R1: clean export ----------------------------------------------------
  echo ""
  echo "=== R1: clean export (release-export checkout-cwd entry: git archive HEAD into the pre-created scratch) ==="
  if ! release_step_run release-export -- /bin/sh -c "$RELEASE_EXPORT_SCRIPT" sh "$EXPORT_ROOT"; then
    echo "RELEASE_CLEAN_EXPORT_FAILED" >&2
    exit 1
  fi
  if [ -e "$EXPORT_ROOT/.git" ]; then
    echo "RELEASE_CLEAN_EXPORT_FAILED" >&2
    exit 1
  fi
  echo "  export ready at $EXPORT_ROOT: committed tree only (no .git, no build/); R2-R3 run exclusively there"

  # --- R2(a): the strict run-tests gate --------------------------------------
  echo ""
  echo "=== R2(a): strict run-tests gate (strict-run-tests whole-script Tier-2 step, export cwd) ==="
  export DEAL_STRICT=1
  if ! release_step_run strict-run-tests -- ./run_tests.sh; then
    update_record_after_r2a "$EXPORT_ROOT/build/strict-run-tests.log"
    echo "RELEASE_GATE_FAILED run-tests" >&2
    echo "  release aborted at R2(a); the export scratch is retained at $EXPORT_ROOT" >&2
    exit 1
  fi
  update_record_after_r2a "$EXPORT_ROOT/build/strict-run-tests.log"
  echo "  strict run-tests gate green (gate record: skipped $RECORD_SKIPPED)"

  # --- R2(b): the runtime assertion gate -------------------------------------
  echo ""
  echo "=== R2(b): runtime assertion gate (the pinned weak-fixture query over the export) ==="
  phase_r2b "$EXPORT_ROOT" || exit 1

  # --- R3: the strict coverage gate -------------------------------------------
  echo ""
  echo "=== R3: strict coverage gate (strict-coverage whole-script Tier-2 step + coverage-csv-digest, export cwd) ==="
  phase_r3 "$EXPORT_ROOT" || exit 1

  # --- End: exit 0 with the complete gate record --------------------------------
  echo ""
  echo "=== R0-R3 complete: exit 0, the complete gate record is at $EXPORT_ROOT/release/gate-record.json (scratch retained for E8) ==="
  sed -n 'p' "$GATE_RECORD_PATH"
  exit 0
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
