#!/bin/sh
# Post-T5 cleanup-bound regression runner (ISSUE-0436 remediation,
# MR-0322 review finding): the past-T5 escalation catch-up must
# terminate at the bounded post-T5 proof window even when the proof
# can never complete (a never-EOF drain via the committed
# FI_CONGEST_STREAM STREAM_NO_EOF seam).
#
# Compiles tools/test/t5-proof-bound-regression.c with the supervisor
# source (the test TU includes tools/src/supervisor.c) plus the
# supervisor's leaf dependencies (protocol/monotonic/drain/fi/selftest)
# with the pinned D4 flags, and runs the regression: budget 15000,
# STREAM_NO_EOF installed in-process, SIGSTOP before T2, SIGCONT past
# T5 — the fixed supervisor catches the TERM/KILL escalation up,
# reaps the killed tree, and terminates at the post-T5 proof bound
# with exactly one REPORT (OVERALL_TIMEOUT, drainEof 0), exactly one
# terminal FAILED record, serve exit 2, and zero survivors. The suite
# lives outside the tools/src/*.c build glob, so the pinned artifact
# build is unchanged; the test binary is a scratch file and never
# enters the repository.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-t5-proof-bound-regression.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" \
    test/t5-proof-bound-regression.c src/protocol.c src/monotonic.c \
    src/drain.c src/fi.c src/selftest.c

"$BIN"
