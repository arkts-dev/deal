#!/bin/sh
# DEALPG4 outer final-sequence / coordinator-death-classification
# component acceptance runner (ISSUE-0299).
#
# Rebuilds the launcher with the pinned recipe first (deterministic —
# the committed binary is byte-identical; the integration leg runs the
# real outer mode entry of that binary), then compiles
# tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
# drain.c with tools/test/outer-final-tests.c under the pinned D4
# flags and runs the eleven case groups (full-success multi-record
# DONE/BYE, the clean-exit variants incl. the pre-cutoff close and
# the post-DONE exit-0-without-BYE, COORDINATOR_LOST exit-0-with-live-
# records and signal-killed, READINESS_TIMEOUT with the full group
# escalation incl. the TERM-ignoring KILL path, the
# COORDINATOR_STARTUP_FAILED exec-fail discharge, the COORDINATOR_HANG
# post-DONE escalation at the pinned deadline, the D8 precondition
# deferral past the escalation deadline, the exit-status matrix
# 0/1/2/3/4, and the real-mode T1-T7 integration with a production
# serve-surface INVOKE). The suite lives outside the tools/src/*.c
# build glob, so the pinned artifact build is unchanged; the test
# binary is a scratch file and never enters the repository. The suite
# includes the ~17 s cutoff runs and the ~27 s post-DONE hang; the
# script-side bound protects against a hung run.
set -eu

cd "$(dirname "$0")/.."

sh build-launcher.sh

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-final-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/monotonic.c \
    src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-final-tests.c

timeout 240 "$BIN"
