#!/bin/sh
# DEALPG4 outer entry component acceptance runner (ISSUE-0293).
#
# Compiles tools/src/outer.c + monotonic.c + protocol.c + fi.c +
# selftest.c + drain.c with tools/test/outer-entry-tests.c under the
# pinned D4 flags and runs the six case groups (mode-surface usage,
# core-entry validation, preamble observation, entry refusals through
# the fault-injection catalog, write-side discipline, exit-status
# mapping). The suite lives outside the tools/src/*.c build glob, so
# the pinned artifact build is unchanged; the test binary is a scratch
# file and never enters the repository. The suite includes the ~4 s
# scaled total-deadline stage run; the script-side bound protects
# against a hung run.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-entry-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/monotonic.c \
    src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-entry-tests.c

timeout 120 "$BIN"
