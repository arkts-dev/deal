#!/bin/sh
# DEALPG4 supervisor fault-injection seam catalog acceptance runner
# (ISSUE-0245, epic Sequencing step 6).
#
# Compiles tools/test/fi-seam-tests.c with the supervisor source (the
# test TU includes tools/src/supervisor.c) plus the supervisor's leaf
# dependencies (protocol/monotonic/drain/fi/selftest) with the pinned
# D4 flags, and runs the T1-T4 cases: catalog shape and installer
# determinism, capability refusals, stub failure paths, identity
# malformation, the release-write races, the post-release wedges, the
# post-ACK-pre-release freeze, supervisor death and the parent-death
# cascades, status/channel/stream congestion, and the
# production-inertness regression — every engine scenario driven
# through the real core/serve/run entries with a scripted harness peer
# and the zero-survivor proof. The suite lives outside the
# tools/src/*.c build glob, so the pinned artifact build is unchanged;
# the test binary is a scratch file and never enters the repository.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-fi-seam-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" \
    test/fi-seam-tests.c src/protocol.c src/monotonic.c src/drain.c \
    src/fi.c src/selftest.c

"$BIN"
