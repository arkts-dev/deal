#!/bin/sh
# DEALPG4 outer coordinator component acceptance runner (ISSUE-0294).
#
# Compiles tools/src/outer.c + monotonic.c + protocol.c + fi.c +
# selftest.c + drain.c with tools/test/outer-coordinator-tests.c under
# the pinned D4 flags and runs the nine case groups (success
# bootstrap, exec failure, readiness bound + by-pid escalation,
# COORDINATOR_HANG escalation deadline, output flood, shell loss via
# the closed report stdout and via orphaning, FI_COORD_READY_MISMATCH,
# FI_OUTER_PIPE). The suite lives outside the tools/src/*.c build
# glob, so the pinned artifact build is unchanged; the test binary is
# a scratch file and never enters the repository. The suite includes
# the ~7 s scaled escalation-deadline runs; the script-side bound
# protects against a hung run.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-coordinator-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/monotonic.c \
    src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-coordinator-tests.c

timeout 180 "$BIN"
