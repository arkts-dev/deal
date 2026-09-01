#!/bin/sh
# Cancel-path terminal-derivation regression runner (ISSUE-0439
# remediation, ISSUE-0180 acceptance review finding): a
# cancel-requested invocation whose stub/target is still live
# (un-reaped) at the terminal classification must publish FAILED with
# the owning token — OVERALL_TIMEOUT at the post-T5 proof-bound
# expiry, the survivor-family token at a T4 proof-deadline landing —
# never the false-clean CLEAN <id> final=cancelled with failureToken
# '-'.
#
# A deterministic D-state (unkillable) target is not constructible in
# this environment, so the regression drives the real terminal
# classification functions in-process —
# dealpg4_supervisor_t5_proof_expiry and
# dealpg4_supervisor_proof_deadline / dealpg4_supervisor_finalize —
# over a constructed terminal state (un-reaped stub_pid, no verified
# pgid/session: no real process is signaled or scanned) and pins the
# full cancel-path derivation matrix, including the preserved clean
# cancel (reaped CLD_EXITED), never-forked (stub_pid < 0), CALLER_LOST,
# deadline-owned EXECUTION_TIMEOUT (ISSUE-0438), and
# UNVERIFIED_TARGET_DEATH cases.
#
# Compiles tools/test/cancel-terminal-derivation-regression.c with the
# supervisor source (the test TU includes tools/src/supervisor.c) plus
# the supervisor's leaf dependencies (protocol/monotonic/drain/fi/
# selftest) with the pinned D4 flags, and runs the regression. The
# suite lives outside the tools/src/*.c build glob, so the pinned
# artifact build is unchanged; the test binary is a scratch file and
# never enters the repository.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-cancel-terminal-derivation.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" \
    test/cancel-terminal-derivation-regression.c src/protocol.c \
    src/monotonic.c src/drain.c src/fi.c src/selftest.c

"$BIN"
