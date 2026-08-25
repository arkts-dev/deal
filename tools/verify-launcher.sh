#!/bin/sh
# DEALPG4 launcher release gate (tools/verify-launcher.sh).
#
# Release gate only -- not part of the per-run bootstrap: run_tests.sh /
# coverage.sh wiring is the preflight child's scope. Runs from the
# repository root; the script re-locates itself via its own path, so it
# also works on a scratch copy of tools/ (the acceptance negative gates
# run in scratch copies and the committed set stays untouched).
#
# Legs, in order (dealpg4-launcher-core verify-launcher contract,
# dealpg4-launcher-artifact-and-integrity verify-launcher contract); a
# failing leg prints its named token on stderr and exits nonzero -- no
# skip, no retry:
#   1. rebuild from tools/src/ with exactly the pinned D4 recipe into a
#      scratch file; the committed binary is never overwritten;
#   2. SHA-256 byte equality of the scratch file against
#      LauncherManifest.sha256, else DIGEST_MISMATCH;
#   3. run "<scratch> probe" and require the exact identity line, the
#      exact 12-field LIMITS line, all five OK lines, and exit 0; probe
#      failures map to PLATFORM_MISMATCH / PROTOCOL_MISMATCH /
#      CAPABILITY_MISSING / PROBE_TIMEOUT / CONFIG_INVALID per the probe
#      contract;
#   4. cross-check the probe LIMITS line field-by-field against the
#      manifest records, LIMITS_MISMATCH on drift.
# Exit 0 iff every leg passes. Manifest parsing uses the pinned python3
# 3.10.12 (/usr/bin/python3); the digest comparison uses
# /usr/bin/sha256sum.
#
# Selftest leg: defined below in dormant form (SELFTEST_LEG_ACTIVE=0).
# The activation condition is the fault-injection battery: when the
# selftest child lands it (ISSUE-0184), that change flips the constant
# to 1 (and re-pins the digest under the atomic rule); from that point
# the full script -- including the selftest leg -- must pass.
set -eu

cd "$(dirname "$0")/.."

MANIFEST="tools/launcher-manifest.json"
SHA256SUM=/usr/bin/sha256sum
PYTHON3=/usr/bin/python3

# Stage capability bitmask (dealpg4-probe-selftest-foundation D2/D6):
# bits 1|2|4|8|16 = 31, each backed by a passing probe battery in this
# artifact. The outer child flips this to 63 (bit 32 registry/broker) in
# the same change that lands its selftest coverage, together with the
# digest re-pin; the identity line must match exactly.
EXPECTED_CAPS=31

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

SCRATCH_DIR=$(mktemp -d "${TMPDIR:-/tmp}/dealpg4-verify-launcher.XXXXXX")
trap 'rm -rf "$SCRATCH_DIR"' EXIT
trap 'rm -rf "$SCRATCH_DIR"; exit 130' HUP INT TERM
SCRATCH_BIN="$SCRATCH_DIR/launcher"

echo "verify-launcher: leg 1/4 rebuild (pinned recipe into scratch)" >&2

SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH
# The pinned D4 recipe, identical to tools/build-launcher.sh's
# compilation (including the .comment removal the pinned toolchain's
# prebuilt crt objects require) except that -o points at the scratch
# file: the committed binary is never overwritten. Any drift between the
# two invocations is a gate defect.
if ! gcc -std=c11 -O2 -Wall -Werror -fno-ident -ffile-prefix-map=$PWD=. \
        -Wl,--build-id=none -o "$SCRATCH_BIN" tools/src/*.c \
        >"$SCRATCH_DIR/rebuild.log" 2>&1; then
    cat "$SCRATCH_DIR/rebuild.log" >&2
    printf 'REBUILD_FAILED\n' >&2
    exit 1
fi
objcopy --remove-section=.comment "$SCRATCH_BIN"

echo "verify-launcher: leg 2/4 digest equality" >&2

# Manifest parsing uses the pinned python3 3.10.12. Emits protocol,
# version, platform, sha256, and the 12 canonical limits values (in the
# exact probe LIMITS field order), one per line.
if ! mvals=$("$PYTHON3" - "$MANIFEST" <<'PY' 2>"$SCRATCH_DIR/manifest.log"
import json
import sys

with open(sys.argv[1], "r") as f:
    m = json.load(f)
print(m["protocol"])
print(m["version"])
print(m["platform"])
print(m["sha256"])
inv = m["invocationLimits"]
outer = m["outerLimits"]
selftest = m["selftestLimits"]
print(" ".join(str(v) for v in (
    inv["overallTimeoutMs"], inv["startupTimeoutMs"],
    inv["executionCutoffMs"], inv["termGraceMs"],
    inv["killAndProofReserveMs"], inv["finalizationReserveMs"],
    outer["overallTimeoutMs"], outer["readinessTimeoutMs"],
    outer["nestedStopMs"], outer["cleanupReserveMs"],
    outer["brokerStallMs"], selftest["selftestTimeoutMs"])))
PY
); then
    cat "$SCRATCH_DIR/manifest.log" >&2
    printf 'MANIFEST_INVALID\n' >&2
    exit 1
fi

{
    read -r m_protocol
    read -r m_version
    read -r m_platform
    read -r m_sha256
    read -r m_limits
} <<EOF
$mvals
EOF

scratch_sha=$("$SHA256SUM" "$SCRATCH_BIN" | cut -d' ' -f1)
if [ "$scratch_sha" != "$m_sha256" ]; then
    fail DIGEST_MISMATCH
fi

echo "verify-launcher: leg 3/4 probe" >&2

# The script-side bound maps a hung probe to PROBE_TIMEOUT (the probe's
# own bound expiry exits 5; the launcher's CONFIG_INVALID exit is 3).
if ! timeout 60 "$SCRATCH_BIN" probe \
        >"$SCRATCH_DIR/probe.out" 2>"$SCRATCH_DIR/probe.err"; then
    status=$?
    cat "$SCRATCH_DIR/probe.err" >&2
    case "$status" in
    124|5) fail PROBE_TIMEOUT ;;
    3) fail CONFIG_INVALID ;;
    4) fail CAPABILITY_MISSING ;;
    *) fail CAPABILITY_MISSING ;;
    esac
fi

# The report is exactly 7 lines: identity, LIMITS, five OK lines.
nlines=$(wc -l < "$SCRATCH_DIR/probe.out" | tr -d ' ')
if [ "$nlines" -ne 7 ]; then
    fail CAPABILITY_MISSING
fi

id_line=$(sed -n '1p' "$SCRATCH_DIR/probe.out")
lim_line=$(sed -n '2p' "$SCRATCH_DIR/probe.out")

# Identity line: exact "DEALPG4 <version> <platform> CAPS <caps>" shape,
# field-checked against the manifest protocol/version/platform and the
# stage bitmask. A missing or mis-shaped identity line, a CAPS field
# that lacks an expected bit, or any extra byte is a capability failure.
[ -n "$id_line" ] || fail CAPABILITY_MISSING
p_protocol=
p_version=
p_platform=
p_caps_kw=
p_caps=
p_rest=
if ! read -r p_protocol p_version p_platform p_caps_kw p_caps p_rest \
        <<EOF
$id_line
EOF
then
    fail CAPABILITY_MISSING
fi
[ "$p_protocol" = "$m_protocol" ] || fail PROTOCOL_MISMATCH
[ "$p_version" = "$m_version" ] || fail PROTOCOL_MISMATCH
[ "$p_platform" = "$m_platform" ] || fail PLATFORM_MISMATCH
[ "$p_caps_kw" = "CAPS" ] || fail CAPABILITY_MISSING
case "$p_caps" in
''|*[!0-9]*) fail CAPABILITY_MISSING ;;
esac
[ "$p_caps" -eq "$EXPECTED_CAPS" ] || fail CAPABILITY_MISSING
[ -z "$p_rest" ] || fail CAPABILITY_MISSING
expected_id="DEALPG4 $m_version $m_platform CAPS $EXPECTED_CAPS"
[ "$id_line" = "$expected_id" ] || fail CAPABILITY_MISSING

# LIMITS line: the exact 12-field shape (the field-by-field manifest
# cross-check is leg 4).
case "$lim_line" in
"LIMITS "*) ;;
*) fail CAPABILITY_MISSING ;;
esac
lim_fields=${lim_line#LIMITS }
set -- $lim_fields
[ "$#" -eq 12 ] || fail CAPABILITY_MISSING
for field in "$@"; do
    case "$field" in
    ''|*[!0-9]*) fail CAPABILITY_MISSING ;;
    esac
done

# The five canonical OK lines, in order, byte-stable
# (dealpg4-probe-selftest-foundation D1).
{
    printf 'OK monotonic-timer\n'
    printf 'OK subreaper\n'
    printf 'OK parent-death\n'
    printf 'OK negative-pgid\n'
    printf 'OK bounded-drain\n'
} > "$SCRATCH_DIR/ok.expected"
tail -n 5 "$SCRATCH_DIR/probe.out" > "$SCRATCH_DIR/ok.actual"
if ! cmp -s "$SCRATCH_DIR/ok.expected" "$SCRATCH_DIR/ok.actual"; then
    fail CAPABILITY_MISSING
fi

echo "verify-launcher: leg 4/4 LIMITS cross-check" >&2

# Field-by-field: the 12 probe LIMITS fields against the manifest
# invocationLimits + outerLimits + selftestLimits records, in canonical
# order.
[ "$*" = "$m_limits" ] || fail LIMITS_MISMATCH

# ----------------------------------------------------------------------
# Selftest leg (DORMANT at this stage).
# The activation condition is the fault-injection battery: when the
# selftest child lands it (ISSUE-0184), that change flips
# SELFTEST_LEG_ACTIVE to 1 below. From that point the full script --
# including this leg -- must pass (dealpg4-launcher-core verify-launcher
# contract). The leg runs the rebuilt scratch launcher in selftest mode
# with a script-side bound, requires exit 0 with the five probe-battery
# OK lines present, and maps failures: the launcher's own bound expiry
# (exit 6) or the script-side timeout (124) -> SELFTEST_TIMEOUT, any
# other failure -> CAPABILITY_MISSING with the launcher's stderr token
# relayed. No skip, no retry.
# ----------------------------------------------------------------------
SELFTEST_LEG_ACTIVE=0
if [ "$SELFTEST_LEG_ACTIVE" = "1" ]; then
    echo "verify-launcher: selftest leg" >&2
    if ! timeout 120 "$SCRATCH_BIN" selftest \
            >"$SCRATCH_DIR/selftest.out" 2>"$SCRATCH_DIR/selftest.err"; then
        status=$?
        cat "$SCRATCH_DIR/selftest.err" >&2
        case "$status" in
        124|6) fail SELFTEST_TIMEOUT ;;
        *) fail CAPABILITY_MISSING ;;
        esac
    fi
    for battery in monotonic-timer subreaper parent-death negative-pgid \
                   bounded-drain; do
        grep -qx "OK $battery" "$SCRATCH_DIR/selftest.out" \
            || fail CAPABILITY_MISSING
    done
fi

printf 'verify-launcher: PASS (rebuild byte-identical, probe green, LIMITS cross-check clean)\n'
exit 0
