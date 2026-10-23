#!/bin/bash
# Fail-closed required-tool preflight shared by the DEAL verification gates.
#
# Every tool named here is required by the suite selection in
# tools/gate-manifest.sh: run_tests.sh executes the LuaJIT and Node runtime
# records and the production corpus with these real toolchains. A missing
# required tool is the named TOOL_MISSING infrastructure outcome, so the gate
# fails before any suite runs and before any build output is touched. There is
# no warning, skip or downgrade path: an absent required tool never removes a
# supported case from execution.
#
# Usage: tools/tool-preflight.sh <tool>...
# Exit:  0 when every named tool resolves on PATH.
#        1 when at least one named tool is absent; each absent tool is one
#          "TOOL_MISSING: <tool> ..." line on stderr.

set -u

missing=0
for tool in "$@"; do
  [ -n "$tool" ] || continue
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "TOOL_MISSING: $tool is not available; required by the DEAL gate, so" \
      "the run fails instead of skipping a supported case" >&2
    missing=1
  fi
done
exit "$missing"
