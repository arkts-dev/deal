#!/bin/sh
# Supervisor-evaluate startupMs anchoring regression runner
# (ISSUE-0244 review finding cycle 2).
#
# Compiles the white-box regression cases against the supervisor
# source — the test TU includes tools/src/supervisor.c for the static
# dealpg4_supervisor_evaluate — plus the supervisor's leaf
# dependencies (protocol/monotonic/drain/fi) with the pinned D4 flags,
# and runs the !release_write_ok classification cases: every silent
# pre-release stub exit and release-write-race classification anchors
# REPORT.startupMs at CLOCK_MONOTONIC - T0 with the pinned
# phase == STARTUP && startup_ms == 0 guard, so the finalize-built
# REPORT never emits startupMs=0 on those paths. The suite lives
# outside the tools/src/*.c build glob, so the pinned artifact build
# is unchanged; the test binary is a scratch file and never enters the
# repository.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-evaluate-startup-ms-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" \
    test/evaluate-startup-ms-tests.c src/protocol.c src/monotonic.c \
    src/drain.c src/fi.c

"$BIN"
