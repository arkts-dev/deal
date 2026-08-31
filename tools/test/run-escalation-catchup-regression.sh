#!/bin/sh
# Supervisor deadline-escalation catch-up regression runner
# (ISSUE-0436 remediation — ISSUE-0180 acceptance review finding).
#
# Rebuilds the launcher with the pinned recipe (deterministic — the
# committed binary is byte-identical) and runs the scripted-peer
# regression harness: a SIGSTOPped supervisor resuming past the T4
# proof deadline (or past T5) must catch the T2 TERM / T3 KILL
# escalation up in ascending deadline order, reap the killed target
# tree, and complete the proof before exiting — the REPORT records the
# escalation timestamps and zero survivors remain. The harness drives
# the real serve entry end-to-end and takes about 30 s (two ~15 s
# scenarios with budget 15000).
set -eu
cd "$(dirname "$0")/.."
if ! command -v python3 > /dev/null 2>&1; then
    echo "escalation-catchup-regression: python3 not found" >&2
    exit 1
fi
sh build-launcher.sh
exec python3 test/escalation-catchup-regression.py
