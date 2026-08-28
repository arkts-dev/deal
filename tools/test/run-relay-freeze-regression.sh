#!/bin/sh
# Serve-mode stream-relay terminal-freeze regression runner
# (ISSUE-0244 review finding 1).
#
# Rebuilds the launcher with the pinned recipe (deterministic — the
# committed binary is byte-identical) and runs the scripted-peer
# regression harness: on a T4-classified no-EOF path (DRAIN_FAILED) a
# late stream EOF must never queue OUT chunks or the previously
# withheld OUT_END behind the already-queued REPORT and terminal
# record. The harness drives the real serve entry end-to-end
# (ACL-release, STARTED, the 1 MiB relay, the proof deadline) and takes
# about 14 s (budget 15000).
set -eu
cd "$(dirname "$0")/.."
if ! command -v python3 > /dev/null 2>&1; then
    echo "relay-freeze-regression: python3 not found" >&2
    exit 1
fi
sh build-launcher.sh
exec python3 test/relay-freeze-regression.py
