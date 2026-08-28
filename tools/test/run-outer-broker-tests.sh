#!/bin/sh
# DEALPG4 outer broker component acceptance runner (ISSUE-0295).
#
# Rebuilds the launcher with the pinned recipe first (deterministic —
# the committed binary is byte-identical; the integration leg runs the
# real outer mode entry of that binary), then compiles
# tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
# drain.c with tools/test/outer-broker-tests.c under the pinned D4
# flags and runs the eleven case groups (socket mechanics incl. the
# stale-path unlink and the CLOEXEC fork probes, the full handshake
# through the core, SO_PEERCRED pid mismatch, wrong HELLO nonce,
# AUTH_FAILED aftermath, the channel-state PROTOCOL_ERROR rejections,
# the uniform framing-level split, the second-connection rejection,
# the stall rule firing and disarm legs, the PROTOCOL_ERROR aftermath
# reap and escalation legs, FI_OUTER_BIND, EOF before any record, and
# the T1+T2+T3 integration through the real mode entry). The suite
# lives outside the tools/src/*.c build glob, so the pinned artifact
# build is unchanged; the test binary is a scratch file and never
# enters the repository. The suite includes the ~5 s scaled
# escalation-deadline runs; the script-side bound protects against a
# hung run.
set -eu

cd "$(dirname "$0")/.."

sh build-launcher.sh

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-broker-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/monotonic.c \
    src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-broker-tests.c

timeout 180 "$BIN"
