#!/bin/sh
# DEALPG4 outer per-state fallback / wedge rule / total-cancel
# component acceptance runner (ISSUE-0298).
#
# Rebuilds the launcher with the pinned recipe first (deterministic —
# the committed binary is byte-identical; the integration legs run the
# real outer mode entry of that binary), then compiles
# tools/src/outer.c + supervisor.c + monotonic.c + protocol.c + fi.c +
# selftest.c + drain.c with tools/test/outer-fallback-tests.c under
# the pinned D4 flags and runs the eleven case groups (the per-state
# death fallbacks with real setsid'd stub identities, the RELEASED
# retained-identity takeover with the escaped descendant, the
# pre-cancel-state rule, the wedge force-termination at the per-record
# deadline, the CANCEL validation matrix over the live broker peer,
# the nested REJECT hold, and the total-cancel triggers — coordinator
# kill, the INVOKE cutoff, the orphaned outer, and the closed report
# stdout EPIPE). The suite lives outside the tools/src/*.c build glob,
# so the pinned artifact build is unchanged; the test binary is a
# scratch file and never enters the repository. The suite includes
# the ~17-20 s per-record-deadline runs; the script-side bound
# protects against a hung run.
set -eu

cd "$(dirname "$0")/.."

sh build-launcher.sh

BIN=$(mktemp "${TMPDIR:-/tmp}/dealpg4-outer-fallback-tests.XXXXXX")
trap 'rm -f "$BIN"' EXIT
trap 'rm -f "$BIN"; exit 130' HUP INT TERM

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
    -Wl,--build-id=none -o "$BIN" src/outer.c src/supervisor.c \
    src/monotonic.c src/protocol.c src/fi.c src/selftest.c src/drain.c \
    test/outer-fallback-tests.c

timeout 300 "$BIN"
