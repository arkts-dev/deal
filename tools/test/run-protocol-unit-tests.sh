#!/bin/sh
# DEALPG4 protocol unit acceptance suite runner.
#
# Compiles tools/src/protocol.c + tools/test/protocol-unit-tests.c with
# the pinned D4 flags and runs the seven case groups
# (dealpg4-protocol-core Verification 1-7). The suite lives outside the
# tools/src/*.c build glob, so the pinned artifact build is unchanged;
# the test binary is a scratch file and never enters the repository.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-protocol-unit-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/protocol.c test/protocol-unit-tests.c

"$BIN"
