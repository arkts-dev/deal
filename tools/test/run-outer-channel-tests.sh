#!/bin/sh
# DEALPG4 outer nested control-channel state machine component
# acceptance runner (ISSUE-0297).
#
# Rebuilds the launcher with the pinned recipe first (deterministic —
# the committed binary is byte-identical; the integration leg runs the
# real serve core of the same sources), then compiles
# tools/src/outer.c + supervisor.c + monotonic.c + protocol.c + fi.c +
# selftest.c + drain.c with tools/test/outer-channel-tests.c under the
# pinned D4 flags and runs the case groups (per-state expectation sets
# and relay rules, the STUB_READY double-verification battery, the ACK
# relay with RELEASED-at-write-completion, the record-level ACK
# rejection split, the queued-write discard on terminality through the
# FI_CONGEST_NESTED_CTRL seam, the validated-CANCEL application, the
# CANCELLING expectation set with the pre-cancel state, the
# relay-verbatim battery with the OUT 1 MiB cap, the nested-channel
# PROTOCOL_ERROR battery, the death observation with the
# consume-not-relay switch, the outer-synthesized terminal records, and
# the T1-T5 integration through the real serve core). The suite lives
# outside the tools/src/*.c build glob, so the pinned artifact build
# is unchanged; the test binary is a scratch file and never enters the
# repository. The suite includes the ~18 s live-record and
# cutoff-anchored DONE runs; the script-side bound protects against a
# hung run.
set -eu

cd "$(dirname "$0")/.."

sh build-launcher.sh

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-channel-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/supervisor.c \
    src/monotonic.c src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-channel-tests.c

timeout 300 "$BIN"
