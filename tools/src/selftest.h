/*
 * DEALPG4 probe/selftest surface.
 *
 * Probe mode is implemented here (ISSUE-0203): the byte-stable three-part
 * report — exactly one identity line ("DEALPG4 4 linux-x86_64 CAPS 31",
 * every advertised capability bit backed by a passing battery in this
 * artifact), exactly one 12-field LIMITS line printed from the embedded
 * limits records only after the mode-entry limits validation passed, and
 * one "OK <battery>" line per passing battery in the canonical order
 * monotonic-timer, subreaper, parent-death, negative-pgid, bounded-drain
 * — plus the five kernel-mechanism capability batteries, the probe bound
 * (one monotonic context armed from probe entry), and the named failure
 * tokens (CAPABILITY_MISSING <battery>, PROBE_TIMEOUT). Exit 0 iff every
 * battery passes; no skip, no retry.
 *
 * Selftest mode surface (ISSUE-0204): the mode-level monotonic bound
 * armed from selftest entry with the same timerfd machinery as every
 * other deadline (dealpg4-time-stream-utilities monotonic context). The
 * effective bound is the dispatch-validated --limit-ms N override when
 * present (N >= DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR) and the embedded
 * selftestLimits.selftestTimeoutMs otherwise — read from the same
 * embedded constants the probe reports, never a hardcoded default. The
 * bound bounds the selftest run only — never an invocation/outer limit —
 * and changes no embedded constant, probe report, or manifest. The
 * selftest entry forks one selftest child which inherits the bound
 * context and runs an explicit additive battery list: at this stage the
 * five probe batteries, printing the same five "OK <battery>" lines as
 * probe in the same order (identity/LIMITS lines are probe-only); the
 * fault-injection battery (excluded, ISSUE-0184) appends to the same
 * list, child, and bound machinery, with every future scenario
 * deadline-owned at the earlier of its scenario budget and the remaining
 * mode bound — the bound machinery needs no change. Success: all
 * batteries passed and the bound did not fire (exit 0). Failure: the
 * first failing battery's token (CAPABILITY_MISSING <battery>) or
 * SELFTEST_TIMEOUT on stderr, nonzero exit; no skip, no retry.
 * Post-state: selftest child reaped, no survivors, bound context closed.
 *
 * Dispatch delegates probe/selftest to these entries after the
 * dispatch-owned --limit-ms shape validation and the mode-entry
 * embedded-limits ordering validation (dealpg4-launcher-core mode
 * dispatch contract).
 */
#ifndef DEALPG4_SELFTEST_H
#define DEALPG4_SELFTEST_H

#include <stdint.h>

/* === probe/selftest --limit-ms floor ===================================
 * Dispatch-owned argument shape: at most one optional "--limit-ms N" with
 * N a decimal integer >= this floor; a smaller, non-integer, repeated,
 * or missing value is a usage error. The floor is symmetric across probe
 * (whose default bound is the floor) and selftest (whose default bound is
 * the embedded selftestLimits.selftestTimeoutMs).
 */
#define DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR 15000

/* probe default bound when --limit-ms is absent. */
#define DEALPG4_PROBE_DEFAULT_LIMIT_MS \
    DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR

/* === Capability bitmask ================================================
 * Bit semantics (dealpg4-probe-selftest-foundation D2): 1 subreaper,
 * 2 monotonic timer, 4 negative-PGID signaling, 8 parent-death signal,
 * 16 bounded drains, 32 outer registry/broker. Invariant: every
 * advertised bit has a passing battery in the same artifact. This stage
 * verifies 1|2|4|8|16 and advertises exactly that (CAPS 31); bit 32 is
 * advertised by the outer child with its selftest coverage (-> 63, digest
 * re-pinned under the atomic rule).
 */
#define DEALPG4_CAP_SUBREAPER        1
#define DEALPG4_CAP_MONOTONIC_TIMER  2
#define DEALPG4_CAP_NEGATIVE_PGID    4
#define DEALPG4_CAP_PARENT_DEATH     8
#define DEALPG4_CAP_BOUNDED_DRAIN   16

#define DEALPG4_PROBE_CAPS \
    (DEALPG4_CAP_SUBREAPER | DEALPG4_CAP_MONOTONIC_TIMER \
     | DEALPG4_CAP_NEGATIVE_PGID | DEALPG4_CAP_PARENT_DEATH \
     | DEALPG4_CAP_BOUNDED_DRAIN) /* 31 at this stage */

/* === Canonical battery names ===========================================
 * Fixed order, byte-stable report (dealpg4-probe-selftest-foundation D1):
 * monotonic-timer, subreaper, parent-death, negative-pgid, bounded-drain.
 */
#define DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER "monotonic-timer"
#define DEALPG4_PROBE_BATTERY_SUBREAPER       "subreaper"
#define DEALPG4_PROBE_BATTERY_PARENT_DEATH    "parent-death"
#define DEALPG4_PROBE_BATTERY_NEGATIVE_PGID   "negative-pgid"
#define DEALPG4_PROBE_BATTERY_BOUNDED_DRAIN   "bounded-drain"

/* === Exit statuses =====================================================
 * The mode entries own their own statuses. These are the
 * probe/selftest-emitted integrity-failure statuses; CONFIG_INVALID is
 * shared with dispatch (the mode entries re-run the mode-entry limits
 * validation so no mode output can print past a violation). Usage errors
 * (2) are dispatch-owned.
 */
#define DEALPG4_EXIT_CONFIG_INVALID     3
#define DEALPG4_EXIT_CAPABILITY_MISSING 4
#define DEALPG4_EXIT_PROBE_TIMEOUT      5
#define DEALPG4_EXIT_SELFTEST_TIMEOUT   6

/*
 * probe/selftest mode entries.
 *
 * limit_ms is the dispatch-validated --limit-ms override, or -1 when the
 * option is absent and the entry applies its mode default (probe 15000;
 * selftest = embedded selftestLimits.selftestTimeoutMs). Dispatch owns
 * the argument validation; the entries own the bound semantics. The
 * bound is a bound on the probe/selftest run only — never an
 * invocation/outer limit.
 */
int dealpg4_probe_entry(int64_t limit_ms);
int dealpg4_selftest_entry(int64_t limit_ms);

#endif
