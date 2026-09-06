#!/bin/bash
# tools/strict-output-assert.sh — the single committed authority for the
# strict-gate output assertion (release-r0-r3-strict-gate-mechanics S4;
# release-pipeline-strict-mode-and-evidence D2(d)).
#
# Each gate script, in DEAL_STRICT mode, tees its complete output to its
# captured log (build/strict-run-tests.log for run_tests.sh,
# build/strict-coverage.log for coverage.sh) and runs this helper over
# it after the background wait and before the final marker. The helper
# fails on any printed skip evidence and passes only when every line of
# the captured log is clean.
#
# Usage: tools/strict-output-assert.sh <captured-log-path>
#
# Exemptions — the mandated green-run zero-count segments, structurally
# exempt because every count pattern below requires a [1-9] leading
# digit:
#   "Skipped: 0" and "KnownFailures (tracked): 0" (the ConformanceTest
#   runner summary), "0 skipped, 0 known-fail (tracked)" (the phase
#   gate), and the JVM runner's "0 skipped (classified)" /
#   "0 known-fail (tracked)" segments.
#
# Violations — any of: a WARNING line; a "SKIP (" marker line; a
# "Classification failures" line; a non-blank line between the
# "Skipped backend-runtime groups" and "Known-fail groups" headings; a
# "GATE FAILURE" line; a non-zero skip-count segment — "Skipped: N",
# "KnownFailures (tracked): N", "N skipped", "N known-fail",
# "N skipped (classified)", "skipped N (classified)",
# "N known-fail (tracked)", "known-fail N (tracked)" for N >= 1 — or an
# "N known-fail fixture(s)" tracked-issue line.
#
# On a violation the helper prints "STRICT_OUTPUT_VIOLATION: <offending
# line>" to stderr and exits 1. The helper uses awk-class trivial text
# utilities only — no table entry, no tool subprocess.

LOG_PATH="${1:-}"
if [ -z "$LOG_PATH" ]; then
  echo "USAGE: tools/strict-output-assert.sh <captured-log-path>" >&2
  exit 2
fi
if [ ! -f "$LOG_PATH" ]; then
  echo "STRICT_OUTPUT_VIOLATION: captured log not found: $LOG_PATH" >&2
  exit 1
fi

awk '
  BEGIN { inSkippedGroups = 0 }
  # The JVM runner prints the "Skipped backend-runtime groups" heading
  # unconditionally (a green run carries it with zero gap lines under
  # it); the heading itself is exempt, and the first heading switches
  # the range on / off around the gap lines.
  /^Skipped backend-runtime groups/ { inSkippedGroups = 1; next }
  /^Known-fail groups/ { inSkippedGroups = 0; next }
  inSkippedGroups {
    if ($0 !~ /^[[:space:]]*$/) {
      print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
      exit 1
    }
    next
  }
  /WARNING/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /SKIP \(/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /Classification failures/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /GATE FAILURE/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /Skipped: [1-9][0-9]*/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /KnownFailures \(tracked\): [1-9][0-9]*/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /[1-9][0-9]* skipped/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /[1-9][0-9]* known-fail/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /[1-9][0-9]* skipped \(classified\)/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /skipped [1-9][0-9]* \(classified\)/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /[1-9][0-9]* known-fail \(tracked\)/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /known-fail [1-9][0-9]* \(tracked\)/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
  /[1-9][0-9]* known-fail fixture\(s\)/ {
    print "STRICT_OUTPUT_VIOLATION: " $0 > "/dev/stderr"
    exit 1
  }
' "$LOG_PATH"
