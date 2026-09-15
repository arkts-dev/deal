#!/bin/bash
# VERIFICATION-TIME STAND-IN for E5's tools/release-step-lib.sh
# (ISSUE-0460) — contract-faithful over the pinned step_run surface:
#   tools/release-step-lib.sh step_run <step> -- <argv...>
# with RELEASE_EXPORT_ROOT (absolute, required) and
# RELEASE_CHECKOUT_ROOT (absolute, required for checkout-class entries).
# Table parse via the pinned /usr/bin/python3 under `timeout 10`;
# Tier-1 dispatch via `launcher run <nonce> <cwd> -- <argv>` (embedded
# bounds); Tier-2 via `timeout --kill-after=15 <budget> <argv>`; one
# step-log record {step, tier, cwd, argv, result} appended per executed
# step. NOT a production artifact — removed before the final commit; the
# committed library lands with ISSUE-0460.
set -u

LIB_DIR=${0%/*}
TABLE="$LIB_DIR/release-step-table.json"
LAUNCHER="$LIB_DIR/deal-process-launcher-linux-x86_64"

if [ "${1:-}" != "step_run" ]; then
    echo "USAGE: tools/release-step-lib.sh step_run <step> -- <argv...>" >&2
    exit 1
fi
shift
STEP="$1"
shift
if [ "$#" -ge 1 ] && [ "$1" = "--" ]; then
    shift
else
    echo "INTERNAL ERROR: malformed step_run invocation" >&2
    exit 1
fi
if [ -z "${RELEASE_EXPORT_ROOT:-}" ]; then
    echo "RELEASE_UNBOUNDED_PROCESS" >&2
    exit 1
fi
case "$RELEASE_EXPORT_ROOT" in
    /*) ;;
    *) echo "RELEASE_UNBOUNDED_PROCESS" >&2; exit 1 ;;
esac

# Named-bound table parse (bounded-step-table-and-library D3).
CAP="${RELEASE_EXPORT_ROOT}/release/step-parse.$STEP.$$.txt"
if ! timeout 10 /usr/bin/python3 - "$TABLE" "$STEP" >"$CAP" 2>>"$CAP" <<'PY'
import json
import os
import sys

try:
    table = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(2)
if table.get("schemaVersion") != 1:
    sys.exit(2)
entries = {}
for e in table.get("entries", []):
    entries[e["step"]] = e
entry = entries.get(sys.argv[2])
if entry is None:
    sys.exit(0)
print(json.dumps(entry))
print("%032x" % int.from_bytes(os.urandom(16), "big"))
PY
then
    echo "RELEASE_TABLE_PARSE_FAILED" >&2
    exit 1
fi
ENTRY=$(sed -n '1p' "$CAP")
if [ -z "$ENTRY" ]; then
    echo "RELEASE_UNBOUNDED_PROCESS" >&2
    exit 1
fi
TIER=$(printf '%s' "$ENTRY" | sed -n 's/.*"tier"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')
CWD_CLASS=$(printf '%s' "$ENTRY" | sed -n 's/.*"cwd"[[:space:]]*:[[:space:]]*"\([a-z]*\)".*/\1/p')
BUDGET=$(printf '%s' "$ENTRY" | sed -n 's/.*"budget"[[:space:]]*:[[:space:]]*\([^,}]*\).*/\1/p' | tr -d '"')
NONCE=$(sed -n '2p' "$CAP")
case "$CWD_CLASS" in
    checkout) DIR="${RELEASE_CHECKOUT_ROOT:-}" ;;
    export) DIR="$RELEASE_EXPORT_ROOT" ;;
    *) echo "RELEASE_TABLE_PARSE_FAILED" >&2; exit 1 ;;
esac
case "$DIR" in
    /*) ;;
    *) echo "RELEASE_UNBOUNDED_PROCESS" >&2; exit 1 ;;
esac

log_record() {
    local step="$1" tier="$2" dir="$3" result="$4" argv="" first=1 a
    shift 4
    for a in "$@"; do
        a=${a//\\/\\\\}
        a=${a//\"/\\\"}
        if [ "$first" = 1 ]; then
            argv="\"$a\""
            first=0
        else
            argv="$argv, \"$a\""
        fi
    done
    mkdir -p "${RELEASE_EXPORT_ROOT}/release" || true
    printf '{"step": "%s", "tier": %s, "cwd": "%s", "argv": [%s], "result": %s}\n' \
        "$step" "$tier" "$dir" "$argv" "$result" \
        >> "${RELEASE_EXPORT_ROOT}/release/step-log.jsonl"
}

if [ "$TIER" = "1" ]; then
    STCAP="${RELEASE_EXPORT_ROOT}/release/step-capture.$$.txt"
    "$LAUNCHER" run "$NONCE" "$DIR" -- "$@" 2>"$STCAP"
    status=$?
    cat "$STCAP" >&2
    if [ "$status" -eq 0 ]; then
        log_record "$STEP" "$TIER" "$DIR" 0 "$@"
        exit 0
    fi
    REPORT=$(grep '^DEALPG4 REPORT ' "$STCAP" | tail -n 1 || true)
    TOKEN=$(printf '%s' "$REPORT" | sed -n 's/.* \([^ ]*\)$/\1/p')
    CODE=$(printf '%s' "$REPORT" | sed -n 's/^DEALPG4 REPORT \([0-9][0-9]*\).*/\1/p')
    if [ "$status" -eq 2 ]; then
        echo "RELEASE_STEP_CONTAINMENT ${TOKEN:--}" >&2
        log_record "$STEP" "$TIER" "$DIR" "\"RELEASE_STEP_CONTAINMENT ${TOKEN:--}\"" "$@"
        exit 2
    fi
    [ -n "$CODE" ] || CODE=1
    log_record "$STEP" "$TIER" "$DIR" "$CODE" "$@"
    exit 1
fi

(cd "$DIR" && timeout --kill-after=15 "$BUDGET" "$@")
status=$?
if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    echo "RELEASE_STEP_TIMEOUT $STEP" >&2
    log_record "$STEP" "$TIER" "$DIR" "\"RELEASE_STEP_TIMEOUT $STEP\"" "$@"
    exit 124
fi
log_record "$STEP" "$TIER" "$DIR" "$status" "$@"
exit "$status"
