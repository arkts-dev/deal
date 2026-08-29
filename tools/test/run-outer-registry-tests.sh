#!/bin/sh
# DEALPG4 outer registry / live-phase / DONE component acceptance
# runner (ISSUE-0296).
#
# Rebuilds the launcher with the pinned recipe first (deterministic —
# the committed binary is byte-identical; the integration leg runs the
# real outer mode entry of that binary), then compiles
# tools/src/outer.c + monotonic.c + protocol.c + fi.c + selftest.c +
# drain.c with tools/test/outer-registry-tests.c under the pinned D4
# flags and runs the case groups (INVOKE semantic/framing split,
# pre-fork rejection battery, register-before-fork + serve surface,
# ACK/CANCEL record-level validation, BYE in BROKER_LIVE, the
# cutoff-anchored DONE trigger with the post-DONE INVOKE answer, and
# the real-mode-entry integration). The suite lives outside the
# tools/src/*.c build glob, so the pinned artifact build is unchanged;
# the test binary is a scratch file and never enters the repository.
# The suite includes the ~18 s live-record runs that hold to the
# scaled total deadline; the script-side bound protects against a hung
# run.
set -eu

cd "$(dirname "$0")/.."

sh build-launcher.sh

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-registry-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/monotonic.c \
    src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-registry-tests.c

timeout 300 "$BIN"
