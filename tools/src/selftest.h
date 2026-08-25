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
 * The selftest mode surface (bound machinery with the embedded
 * selftestLimits.selftestTimeoutMs default and the fault-battery slot)
 * lands in the selftest child; its entry stays the stage placeholder
 * MODE_NOT_IMPLEMENTED (no fork, no exec, no channel; a stage
 * placeholder, not an integrity token).
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
 * The mode entries own their own statuses. These are the probe-emitted
 * integrity-failure statuses; CONFIG_INVALID is shared with dispatch (the
 * probe entry re-runs the mode-entry limits validation so the LIMITS line
 * can never print past a violation). Usage errors (2) are dispatch-owned.
 */
#define DEALPG4_EXIT_CONFIG_INVALID     3
#define DEALPG4_EXIT_CAPABILITY_MISSING 4
#define DEALPG4_EXIT_PROBE_TIMEOUT      5

/*
 * probe/selftest mode entries.
 *
 * limit_ms is the dispatch-validated --limit-ms override, or -1 when the
 * option is absent and the entry applies its mode default (probe 15000;
 * selftest = embedded selftestLimits.selftestTimeoutMs once its body
 * lands). Dispatch owns the argument validation; the entries own the
 * bound semantics. The bound is a bound on the probe/selftest run only —
 * never an invocation/outer limit.
 */
int dealpg4_probe_entry(int64_t limit_ms);
int dealpg4_selftest_entry(int64_t limit_ms);

#endif
