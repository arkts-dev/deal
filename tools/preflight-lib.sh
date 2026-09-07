#!/bin/bash
# tools/preflight-lib.sh — the shared fail-closed DEALPG4 toolchain
# preflight (fail-closed-toolchain-preflight D1/D2/D5): one ordered,
# fail-closed phase sequence P0-P5, shared verbatim by run_tests.sh and
# coverage.sh. The two gate scripts source this file from the repository
# root; the coverage-specific differences (P3 extra assets, the
# --release 22 compile list, the JaCoCo-flagged coordinator) stay in the
# callers via the contract below.
#
# Phases (order is fixed; any failure prints its named token on stderr
# and exits nonzero immediately — no skip, no downgrade, no retry, and
# no later phase starts):
#   P0 launcher integrity (shell): regular file, not a symlink,
#      executable, SHA-256 equal to tools/launcher-manifest.json.
#   P1 launcher probe (native, <= 15 s): identity line
#      "DEALPG4 4 linux-x86_64 CAPS <bitmask>" and the 12-field LIMITS
#      line, both cross-checked field-by-field against the manifest
#      (drift -> LIMITS_MISMATCH); every probe battery green.
#   P2 native self-test battery (launcher selftest, native bounded by
#      the manifest selftestLimits.selftestTimeoutMs, default 90000 ms,
#      embedded, probe-reported, and P1-cross-checked): the battery is
#      treated as a black box — exit 0 passes, a nonzero exit prints
#      SELFTEST_FAIL or SELFTEST_TIMEOUT (the battery content grows
#      with ISSUE-0184 and the scripts stay green).
#   P3 tool presence (shell, fail-closed): gcc, javac, java, luajit,
#      node present AND functional (ISSUE-0362 extends the set with
#      node, v12-zero-skip-conformance-gate G3: a missing tool or a
#      broken-but-present tool — a version probe that exits nonzero —
#      is TOOL_MISSING <tool> here, never a skip); coverage.sh adds
#      its JaCoCo/JUnit assets through the extra-tool hook. Absence is
#      an error here, never a skip.
#   P4 bounded standalone javac: launcher run <nonce> <repo> --
#      <caller's javac argv> under the embedded LauncherLimits (45 s
#      native deadline, 1 MiB bounded drains). javac failure or any
#      containment failure -> JAVAC_FAIL (the launcher's stderr carries
#      javac's diagnostics and the REPORT line whose failureToken names
#      the containment failure) and a nonzero exit.
#   P5 outer feature supervisor + PreflightCoordinator:
#      launcher outer <nonce> -- <caller's coordinator argv> under the
#      embedded OuterLimits (15 min deadline, FEATURE_READY within the
#      5 s readiness bound). The outer delivers DEALPG4_BROKER_PATH and
#      DEALPG4_NONCE to the coordinator child at its own fork
#      (tools/src/outer.c D1/D5) — the shell passes only the
#      coordinator nonce via argv (16 bytes from /dev/urandom rendered
#      as 32 lowercase hex, artifact page D6), so the coordinator nonce
#      never enters the outer's own environment. Any gate-failure token
#      or FAILED registry record in the outer's final report fails the
#      gate.
#
# Clock ownership (preflight page Architecture): the launcher owns every
# deadline. The script-side `timeout` guards are fail-closed safety nets
# with generous margins over the native bounds; they never fire on a
# conforming artifact and map to the same named timeout tokens.
#
# Caller contract (both scripts, from the repository root):
#   source tools/preflight-lib.sh
#   DEALPG4_PREFLIGHT_JAVAC_ARGS=(javac <explicit args>)
#   DEALPG4_PREFLIGHT_COORD_ARGS=(java <explicit args>)
#   [dealpg4_preflight_extra_tool_check() { ... }]   # P3 additions
#   dealpg4_preflight_run                            # P0-P3 in order
#   dealpg4_preflight_javac "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"  # P4
#   dealpg4_preflight_outer                          # P5 after P4
#
# Staging pin: DEALPG4_PREFLIGHT_EXPECTED_CAPS and
# DEALPG4_PREFLIGHT_PROBE_REPORT_LINES mirror tools/verify-launcher.sh's
# EXPECTED_CAPS and 7-line report count. ISSUE-0184's atomic CAPS flip
# (bit 32 outer registry/broker -> 63, the sixth OK line, 8 report
# lines) updates these constants here and in tools/verify-launcher.sh
# together with the native flip and the digest re-pin.

# --- Pinned artifact surface ---------------------------------------------

DEALPG4_PREFLIGHT_LAUNCHER="tools/deal-process-launcher-linux-x86_64"
DEALPG4_PREFLIGHT_MANIFEST="tools/launcher-manifest.json"
DEALPG4_PREFLIGHT_SHA256SUM="/usr/bin/sha256sum"
DEALPG4_PREFLIGHT_PYTHON3="/usr/bin/python3"

# Stage capability bitmask (dealpg4-probe-selftest-foundation D2/D6,
# tools/src/outer.h battery contract): bits 1|2|4|8|16 = 31, each backed
# by a passing probe battery in the committed artifact; the probe
# identity line must match exactly.
DEALPG4_PREFLIGHT_EXPECTED_CAPS=31
# The probe report is exactly 7 lines at this stage: identity, LIMITS,
# five OK lines in canonical order.
DEALPG4_PREFLIGHT_PROBE_REPORT_LINES=7

# Manifest mirrors, filled by P0 and consumed by P1: protocol, version,
# platform, sha256, and the 12 canonical limits values in the exact
# probe LIMITS field order (one space-separated line).
DEALPG4_M_PROTOCOL=""
DEALPG4_M_VERSION=""
DEALPG4_M_PLATFORM=""
DEALPG4_M_SHA256=""
DEALPG4_M_LIMITS=""

dealpg4_preflight_fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

# One shell-side nonce: 16 bytes from /dev/urandom rendered as 32
# lowercase hex (artifact page D6). Failure is fail-closed.
dealpg4_preflight_make_nonce() {
    local nonce
    nonce=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
    if [ "${#nonce}" -ne 32 ]; then
        return 1
    fi
    case "$nonce" in
        *[!0-9a-f]*) return 1 ;;
    esac
    printf '%s' "$nonce"
}

# =========================================================================
# P0: launcher integrity (shell).
# =========================================================================
dealpg4_preflight_p0() {
    # Not a subshell: the manifest mirrors (DEALPG4_M_*) must land in
    # the caller's scope for P1's cross-check, so the temporary file is
    # removed explicitly on every exit path instead.
    local digest mvals tmp
    tmp=""
    echo ""
    echo "=== Preflight P0: launcher integrity (regular non-symlink executable, manifest digest) ==="
    # Canonical token order: a symlink is named before any other
    # check (a dangling symlink is SYMLINK too — -L needs no
    # resolution), then existence, regular-file, and executable.
    if [ -L "$DEALPG4_PREFLIGHT_LAUNCHER" ]; then
        dealpg4_preflight_fail SYMLINK
    fi
    [ -e "$DEALPG4_PREFLIGHT_LAUNCHER" ] \
        || dealpg4_preflight_fail MISSING
    [ -f "$DEALPG4_PREFLIGHT_LAUNCHER" ] \
        || dealpg4_preflight_fail NOT_REGULAR
    [ -x "$DEALPG4_PREFLIGHT_LAUNCHER" ] \
        || dealpg4_preflight_fail NOT_EXECUTABLE

    tmp=$(mktemp "${TMPDIR:-/tmp}/dealpg4-preflight-manifest.XXXXXX") \
        || dealpg4_preflight_fail MANIFEST_INVALID
        # Manifest parsing uses the pinned python3 3.10.12 (the same
        # parser and field order as tools/verify-launcher.sh): protocol,
        # version, platform, sha256, and the 12 canonical limits values
        # in the exact probe LIMITS field order, one per line. A
        # malformed or missing manifest is fail-closed.
        if ! mvals=$("$DEALPG4_PREFLIGHT_PYTHON3" \
                - "$DEALPG4_PREFLIGHT_MANIFEST" <<'PY' 2>"$tmp"
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
        cat "$tmp" >&2
        rm -f "$tmp"
        dealpg4_preflight_fail MANIFEST_INVALID
    fi
    rm -f "$tmp"
    {
        read -r DEALPG4_M_PROTOCOL
        read -r DEALPG4_M_VERSION
        read -r DEALPG4_M_PLATFORM
        read -r DEALPG4_M_SHA256
        read -r DEALPG4_M_LIMITS
    } <<EOF
$mvals
EOF

    digest=$("$DEALPG4_PREFLIGHT_SHA256SUM" \
            "$DEALPG4_PREFLIGHT_LAUNCHER" | cut -d' ' -f1)
    [ "$digest" = "$DEALPG4_M_SHA256" ] \
        || dealpg4_preflight_fail DIGEST_MISMATCH
    echo "  launcher is a regular non-symlink executable; SHA-256 matches tools/launcher-manifest.json"
}

# =========================================================================
# P1: launcher probe (native, <= 15 s) with the field-by-field manifest
# cross-check. P0 and P1 both run before any GCC/Javac/Java probe.
# =========================================================================
dealpg4_preflight_p1() {
    (
        local out err status nlines id_line lim_line expected_id
        local p_protocol p_version p_platform p_caps_kw p_caps p_rest
        out=$(mktemp "${TMPDIR:-/tmp}/dealpg4-preflight-probe.XXXXXX") \
            || dealpg4_preflight_fail PROBE_FAIL
        err="$out.err"
        trap 'rm -f "$out" "$err"' EXIT
        echo ""
        echo "=== Preflight P1: launcher probe (identity, LIMITS cross-check, batteries) ==="
        # The probe's own monotonic bound is 15000 ms (--limit-ms
        # 15000, dispatch-validated); the script-side guard is a
        # fail-closed safety net with margin that never fires on a
        # conforming artifact.
        if timeout -k 10 60 "$DEALPG4_PREFLIGHT_LAUNCHER" probe \
                --limit-ms 15000 >"$out" 2>"$err"; then
            :
        else
            status=$?
            cat "$err" >&2
            case "$status" in
                124|5) dealpg4_preflight_fail PROBE_TIMEOUT ;;
                3) dealpg4_preflight_fail CONFIG_INVALID ;;
                4) dealpg4_preflight_fail CAPABILITY_MISSING ;;
                *) dealpg4_preflight_fail PROBE_FAIL ;;
            esac
        fi
        cat "$err" >&2

        # The report is exactly 7 lines at this stage: identity, LIMITS,
        # five OK lines (dealpg4-probe-selftest-foundation D1).
        nlines=$(wc -l < "$out" | tr -d ' ')
        if [ "$nlines" -ne "$DEALPG4_PREFLIGHT_PROBE_REPORT_LINES" ]; then
            cat "$out" >&2
            dealpg4_preflight_fail CAPABILITY_MISSING
        fi
        id_line=$(sed -n '1p' "$out")
        lim_line=$(sed -n '2p' "$out")

        # Identity line: exact "DEALPG4 <version> <platform> CAPS <caps>"
        # shape, field-checked against the manifest protocol/version/
        # platform and the stage bitmask. A missing or mis-shaped
        # identity line, a CAPS field that differs from the stage mask,
        # or any extra byte is a capability failure.
        [ -n "$id_line" ] || dealpg4_preflight_fail CAPABILITY_MISSING
        if ! read -r p_protocol p_version p_platform p_caps_kw p_caps p_rest \
                <<EOF
$id_line
EOF
        then
            dealpg4_preflight_fail CAPABILITY_MISSING
        fi
        [ "$p_protocol" = "$DEALPG4_M_PROTOCOL" ] \
            || dealpg4_preflight_fail PROTOCOL_MISMATCH
        [ "$p_version" = "$DEALPG4_M_VERSION" ] \
            || dealpg4_preflight_fail PROTOCOL_MISMATCH
        [ "$p_platform" = "$DEALPG4_M_PLATFORM" ] \
            || dealpg4_preflight_fail PLATFORM_MISMATCH
        [ "$p_caps_kw" = "CAPS" ] \
            || dealpg4_preflight_fail CAPABILITY_MISSING
        case "$p_caps" in
            ''|*[!0-9]*) dealpg4_preflight_fail CAPABILITY_MISSING ;;
        esac
        [ "$p_caps" -eq "$DEALPG4_PREFLIGHT_EXPECTED_CAPS" ] \
            || dealpg4_preflight_fail CAPABILITY_MISSING
        [ -z "$p_rest" ] || dealpg4_preflight_fail CAPABILITY_MISSING
        expected_id="DEALPG4 $DEALPG4_M_VERSION $DEALPG4_M_PLATFORM CAPS $DEALPG4_PREFLIGHT_EXPECTED_CAPS"
        [ "$id_line" = "$expected_id" ] \
            || dealpg4_preflight_fail CAPABILITY_MISSING

        # LIMITS line: the exact 12-field shape, then the field-by-field
        # manifest cross-check (the canonical runtime limits record).
        case "$lim_line" in
            "LIMITS "*) ;;
            *) dealpg4_preflight_fail CAPABILITY_MISSING ;;
        esac
        lim_fields=${lim_line#LIMITS }
        set -- $lim_fields
        if [ "$#" -ne 12 ]; then
            dealpg4_preflight_fail CAPABILITY_MISSING
        fi
        for field in "$@"; do
            case "$field" in
                ''|*[!0-9]*) dealpg4_preflight_fail CAPABILITY_MISSING ;;
            esac
        done
        [ "$*" = "$DEALPG4_M_LIMITS" ] \
            || dealpg4_preflight_fail LIMITS_MISMATCH

        # The five canonical OK lines, in order, byte-stable
        # (dealpg4-probe-selftest-foundation D1).
        {
            printf 'OK monotonic-timer\n'
            printf 'OK subreaper\n'
            printf 'OK parent-death\n'
            printf 'OK negative-pgid\n'
            printf 'OK bounded-drain\n'
        } > "$out.ok.expected"
        tail -n 5 "$out" > "$out.ok.actual"
        if ! cmp -s "$out.ok.expected" "$out.ok.actual"; then
            cat "$out" >&2
            dealpg4_preflight_fail CAPABILITY_MISSING
        fi
        rm -f "$out.ok.expected" "$out.ok.actual"

        echo "  identity line and the 12 LIMITS fields match tools/launcher-manifest.json; all probe batteries green"
        cat "$out"
    ) || exit 1
}

# =========================================================================
# P2: native self-test battery (launcher selftest). The wiring treats
# the battery as a black box: exit 0 passes; the battery content lands
# with ISSUE-0184 and the scripts stay green with the complete battery.
# =========================================================================
dealpg4_preflight_p2() {
    (
        local out err status
        out=$(mktemp "${TMPDIR:-/tmp}/dealpg4-preflight-selftest.XXXXXX") \
            || dealpg4_preflight_fail SELFTEST_FAIL
        err="$out.err"
        trap 'rm -f "$out" "$err"' EXIT
        echo ""
        echo "=== Preflight P2: launcher selftest (native bound: manifest selftestLimits.selftestTimeoutMs = 90000 ms) ==="
        # The mode-level monotonic bound is the launcher's own timerfd
        # deadline (embedded selftestLimits.selftestTimeoutMs, P1-cross-
        # checked); the script-side guard is a fail-closed safety net
        # with margin over the native bound.
        if timeout -k 10 150 "$DEALPG4_PREFLIGHT_LAUNCHER" selftest \
                >"$out" 2>"$err"; then
            :
        else
            status=$?
            cat "$out" >&2
            cat "$err" >&2
            case "$status" in
                124|6) dealpg4_preflight_fail SELFTEST_TIMEOUT ;;
                *) dealpg4_preflight_fail SELFTEST_FAIL ;;
            esac
        fi
        cat "$err" >&2
        cat "$out"
        echo "  selftest battery passed inside the native mode-level bound"
    ) || exit 1
}

# =========================================================================
# P3: fail-closed tool presence (ISSUE-0362: the required set gains
# `node` — v12-zero-skip-conformance-gate G3). Every required tool must
# be present AND functional: a missing tool or a broken-but-present
# tool (a version probe that exits nonzero) is TOOL_MISSING <tool> here,
# never a skip. The optional hook (overridden by coverage.sh) adds the
# coverage-specific assets; a failing hook fails the phase.
# =========================================================================
dealpg4_preflight_extra_tool_check() {
    :
}

dealpg4_preflight_p3() {
    local tool version_flag
    echo ""
    echo "=== Preflight P3: fail-closed tool presence ==="
    for tool in gcc javac java luajit node; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            dealpg4_preflight_fail "TOOL_MISSING $tool"
        fi
        # Functional probe (G3's broken-but-present arm): every required
        # tool answers its version probe with exit 0. luajit carries no
        # --version flag (its -v form is the probe); the other tools
        # answer --version.
        case "$tool" in
            luajit) version_flag="-v" ;;
            *) version_flag="--version" ;;
        esac
        if ! "$tool" "$version_flag" >/dev/null 2>&1; then
            dealpg4_preflight_fail "TOOL_MISSING $tool"
        fi
    done
    dealpg4_preflight_extra_tool_check
    echo "  gcc, javac, java, luajit, node present"
}

# =========================================================================
# The ordered P0-P3 bootstrap, run by both scripts before any GCC/Javac/
# Java probe. P4 (bounded javac) and P5 (outer coordinator) run at the
# caller's compile and post-compile sites to preserve each script's
# build-stamp mechanics.
# =========================================================================
dealpg4_preflight_run() {
    dealpg4_preflight_p0
    dealpg4_preflight_p1
    dealpg4_preflight_p2
    dealpg4_preflight_p3
}

# =========================================================================
# P4: bounded standalone javac under launcher run. The launcher applies
# the embedded LauncherLimits (45 s native deadline, 1 MiB bounded
# drains) and the full stub/session/proof protocol; exit 0 only on clean
# containment plus target exit 0. javac failure or any containment
# failure -> JAVAC_FAIL on stderr and a nonzero exit (the launcher's
# stderr passthrough carries javac's diagnostics and the REPORT line
# with the containment failureToken).
# =========================================================================
dealpg4_preflight_javac() {
    local nonce status
    nonce=$(dealpg4_preflight_make_nonce) \
        || dealpg4_preflight_fail NONCE_FAILED
    echo "=== Preflight P4: bounded standalone javac (launcher run, 45 s native deadline, 1 MiB drains) ==="
    # The 45 s deadline is launcher-owned (embedded LauncherLimits); the
    # script-side guard is a fail-closed safety net with margin that
    # never fires on a conforming artifact.
    if timeout -k 10 120 "$DEALPG4_PREFLIGHT_LAUNCHER" run "$nonce" \
            "$PWD" -- "$@"; then
        :
    else
        status=$?
        printf 'JAVAC_FAIL (launcher exit %s)\n' "$status" >&2
        exit 1
    fi
}

# =========================================================================
# P5: outer feature supervisor + PreflightCoordinator. The shell
# generates the coordinator nonce (16 bytes /dev/urandom -> 32 lowercase
# hex) and passes it as the outer argv; the outer delivers
# DEALPG4_BROKER_PATH/DEALPG4_NONCE to the coordinator child at its own
# fork (tools/src/outer.c D1/D5). FEATURE_READY is native-bounded (5 s
# readiness deadline); the whole run is bounded by the 15-minute
# OuterLimits deadline. Any gate-failure token or FAILED registry record
# in the outer's final report fails the gate (the outer also maps both
# to exit 1).
# =========================================================================
dealpg4_preflight_outer() {
    local nonce out err status
    nonce=$(dealpg4_preflight_make_nonce) \
        || dealpg4_preflight_fail NONCE_FAILED
    out=""
    out=$(mktemp "${TMPDIR:-/tmp}/dealpg4-preflight-outer.XXXXXX") \
        || dealpg4_preflight_fail PREFLIGHT_OUTER_FAILED
    err="$out.err"
    echo ""
    echo "=== Preflight P5: outer feature supervisor + PreflightCoordinator (15 min native deadline, FEATURE_READY 5 s) ==="
    # The 15-minute deadline is launcher-owned (embedded OuterLimits);
    # the script-side guard is a fail-closed safety net with margin.
    if timeout -k 10 960 "$DEALPG4_PREFLIGHT_LAUNCHER" outer "$nonce" \
            -- "${DEALPG4_PREFLIGHT_COORD_ARGS[@]}" \
            >"$out" 2>"$err"; then
        :
    else
        status=$?
        cat "$out" >&2
        cat "$err" >&2
        rm -f "$out" "$err"
        if [ "$status" -eq 124 ]; then
            dealpg4_preflight_fail PREFLIGHT_OUTER_TIMEOUT
        fi
        dealpg4_preflight_fail PREFLIGHT_OUTER_FAILED
    fi
    cat "$err" >&2
    rm -f "$err"

    # Fail closed on any gate-failure token or FAILED registry record in
    # the final report, even though the outer maps both to exit 1 (belt
    # and braces: the report is the per-record proof surface).
    if grep -q '^OUTER token ' "$out" \
            || grep -q '^OUTER record [0-9][0-9]* FAILED ' "$out"; then
        grep -E '^OUTER (token|record)' "$out" >&2 || true
        cat "$out" >&2
        rm -f "$out"
        dealpg4_preflight_fail PREFLIGHT_OUTER_FAILED
    fi
    cat "$out"
    rm -f "$out"
    echo "  coordinator round-trips CLEAN; outer final report clean"
}
