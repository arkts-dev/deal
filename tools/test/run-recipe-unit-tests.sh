#!/bin/sh
# DEALPG4 limits-ordering and phase-recipe unit case runner.
#
# Compiles tools/src/monotonic.c + tools/test/recipe-unit-tests.c with
# the pinned D4 flags and runs the recipe cases, including the named T2
# min-form case: T2 = T0 + min(executionCutoffMs, T - cleanupTotalMs).
# Every expected value is derived from the embedded constants the
# program is compiled against, so running this suite against a
# coordinated limits edit re-verifies the recipe arithmetic with the
# edited constants (the T2 min-form changes with executionCutoffMs).
# The suite lives outside the tools/src/*.c build glob; the test binary
# is a scratch file and never enters the repository.
set -eu

cd "$(dirname "$0")/.."

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-recipe-unit-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/monotonic.c test/recipe-unit-tests.c

"$BIN"
