#!/bin/sh
# Outer mode-surface usage runner (ISSUE-0293 Verification).
#
# Rebuilds the launcher with the pinned recipe (deterministic — the
# committed binary is byte-identical) and runs the process-level usage
# harness (tools/test/outer-mode-surface-tests.py): every argv shape
# error exits 2 with the usage message on stderr, creates no broker
# socket path under build/, and returns immediately.
set -eu
cd "$(dirname "$0")/.."
if ! command -v python3 > /dev/null 2>&1; then
    echo "outer-mode-surface-tests: python3 not found" >&2
    exit 1
fi
sh build-launcher.sh
exec python3 test/outer-mode-surface-tests.py
