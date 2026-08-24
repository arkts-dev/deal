/*
 * DEALPG4 monotonic deadline utility.
 *
 * Owns (dealpg4-launcher-core D2, dealpg4-time-stream-utilities):
 *  - the embedded limits constants: canonical runtime copies of the
 *    records in tools/launcher-manifest.json (the manifest is the
 *    canonical document; a limits change edits both together),
 *  - the embedded-limits ordering validation (mode entry, CONFIG_INVALID),
 *  - timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK|TFD_CLOEXEC) absolute
 *    deadline contexts with EINTR recompute, and
 *  - the canonical phase-recipe arithmetic for the supervisor/outer phases.
 *
 * No deadline-extension API exists: no function in this module can advance
 * an armed deadline. Re-arm paths either repeat the identical absolute
 * deadline (EINTR recompute) or install a strictly-later deadline computed
 * from a fresh CLOCK_MONOTONIC read (next phase via the recipe functions).
 */
#ifndef DEALPG4_MONOTONIC_H
#define DEALPG4_MONOTONIC_H

#include <stdint.h>
#include <time.h>

/* === Embedded limits constants =========================================
 * Canonical runtime copies of tools/launcher-manifest.json. A limits
 * change edits the manifest records and these constants together and
 * re-pins the committed binary digest in the same change (atomic rule,
 * dealpg4-launcher-core D5).
 */

/* Invocation (LauncherLimits). */
#define DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS        45000
#define DEALPG4_LAUNCHER_STARTUP_TIMEOUT_MS         5000
#define DEALPG4_LAUNCHER_EXECUTION_CUTOFF_MS       35000
#define DEALPG4_LAUNCHER_TERM_GRACE_MS              2000
#define DEALPG4_LAUNCHER_KILL_AND_PROOF_RESERVE_MS  5000
#define DEALPG4_LAUNCHER_FINALIZATION_RESERVE_MS    3000

/* Outer (OuterLimits). */
#define DEALPG4_OUTER_OVERALL_TIMEOUT_MS   900000
#define DEALPG4_OUTER_READINESS_TIMEOUT_MS   5000
#define DEALPG4_OUTER_NESTED_STOP_MS       880000
#define DEALPG4_OUTER_CLEANUP_RESERVE_MS    20000
#define DEALPG4_OUTER_BROKER_STALL_MS        2000

/* Selftest (SelftestLimits) and the selftest ordering minimum. */
#define DEALPG4_SELFTEST_TIMEOUT_MS          90000
#define DEALPG4_SELFTEST_MINIMUM_TIMEOUT_MS  60000

/* Record shapes mirror the manifest records field-by-field. */
typedef struct dealpg4_launcher_limits {
    int64_t overallTimeoutMs;
    int64_t startupTimeoutMs;
    int64_t executionCutoffMs;
    int64_t termGraceMs;
    int64_t killAndProofReserveMs;
    int64_t finalizationReserveMs;
} LauncherLimits;

typedef struct dealpg4_outer_limits {
    int64_t overallTimeoutMs;
    int64_t readinessTimeoutMs;
    int64_t nestedStopMs;
    int64_t cleanupReserveMs;
    int64_t brokerStallMs;
} OuterLimits;

typedef struct dealpg4_selftest_limits {
    int64_t selftestTimeoutMs;
} SelftestLimits;

/* The embedded records (canonical maxima, probe-reported and LIMITS
 * cross-checked against the manifest). */
extern const LauncherLimits dealpg4_embedded_launcher_limits;
extern const OuterLimits dealpg4_embedded_outer_limits;
extern const SelftestLimits dealpg4_embedded_selftest_limits;

/* === Embedded-limits ordering validation ==============================
 * Checks the three canonical invariants (dealpg4-time-stream-utilities D2):
 *   1. startupTimeoutMs <= executionCutoffMs
 *        <= overallTimeoutMs - (termGraceMs + killAndProofReserveMs
 *                               + finalizationReserveMs)
 *   2. nestedStopMs + cleanupReserveMs <= overallTimeoutMs  (outer)
 *   3. selftestTimeoutMs >= 60000
 * Dispatch runs the check at every mode entry before any mode code; a
 * violation is reported as a nonzero status and surfaces as CONFIG_INVALID.
 * The probe LIMITS line is printed only after the check passes.
 */
typedef enum dealpg4_limits_status {
    DEALPG4_LIMITS_OK = 0,
    DEALPG4_LIMITS_INVOCATION_ORDERING_VIOLATED,
    DEALPG4_LIMITS_OUTER_ORDERING_VIOLATED,
    DEALPG4_LIMITS_SELFTEST_MINIMUM_VIOLATED
} dealpg4_limits_status;

/* First violated invariant, or DEALPG4_LIMITS_OK. Records must be
 * non-NULL. */
dealpg4_limits_status dealpg4_limits_ordering_check(
    const LauncherLimits *launcher, const OuterLimits *outer,
    const SelftestLimits *selftest);

/* Ordering check over the embedded records. */
dealpg4_limits_status dealpg4_embedded_limits_ordering_check(void);

/* Stable diagnostic name for a status (invariant names, not the
 * CONFIG_INVALID token; dispatch owns token printing). */
const char *dealpg4_limits_status_name(dealpg4_limits_status status);

/* === Phase-recipe arithmetic ===========================================
 * Pure functions over the limits records; no side effects, no clock reads.
 * The supervisor/outer children consume exactly these computations.
 */

/* Invocation recipe (native-supervisor-containment D4). For overall budget
 * T (default = embedded overallTimeoutMs = 45000) and entry time T0 read
 * before any fork:
 *   cleanupTotalMs = termGraceMs + killAndProofReserveMs
 *                    + finalizationReserveMs                     (= 10000)
 *   T1 = T0 + min(startupTimeoutMs,   T - cleanupTotalMs)  handshake+ACK
 *   T2 = T0 + min(executionCutoffMs,  T - cleanupTotalMs)  cutoff -> TERM
 *   T3 = T2 + termGraceMs                                  -> KILL
 *   T4 = T3 + killAndProofReserveMs                        proof deadline
 *   T5 = T0 + T                                            finalization
 * The caller validates T before use (serve entry: 15000 <= T <= embedded
 * overallTimeoutMs); the arithmetic itself is exact and unconditional.
 */
typedef struct dealpg4_invocation_deadlines {
    int64_t cleanupTotalMs;
    int64_t t1;
    int64_t t2;
    int64_t t3;
    int64_t t4;
    int64_t t5;
} dealpg4_invocation_deadlines;

void dealpg4_invocation_recipe(const LauncherLimits *limits, int64_t t0,
                               int64_t budget_t,
                               dealpg4_invocation_deadlines *out);

/* Outer recipe (outer-coordinator-and-broker D9): readiness deadline
 * T0o + readinessTimeoutMs; INVOKE cutoff T0o + nestedStopMs; everything
 * completes by T0o + overallTimeoutMs. */
typedef struct dealpg4_outer_deadlines {
    int64_t readinessDeadline;
    int64_t invokeCutoff;
    int64_t totalDeadline;
} dealpg4_outer_deadlines;

void dealpg4_outer_recipe(const OuterLimits *limits, int64_t t0o,
                          dealpg4_outer_deadlines *out);

/* Broker stall deadline = now + brokerStallMs. The consuming event loop
 * arms it only while broker relay data is pending and POLLOUT is not
 * ready. */
int64_t dealpg4_broker_stall_deadline(const OuterLimits *limits, int64_t now);

/* === Absolute-deadline context =========================================
 * One timerfd per context, created once by dealpg4_deadline_open:
 * timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK|TFD_CLOEXEC). Deadlines are
 * absolute CLOCK_MONOTONIC milliseconds, computed from fresh clock reads;
 * the fd joins the owner's ppoll set; expiry is drained with reads until
 * EAGAIN; after expiry the context is idle and the owner arms the next
 * absolute phase deadline.
 */
typedef struct dealpg4_deadline_ctx {
    int fd;              /* timerfd, -1 when closed */
    uint64_t deadline_ms; /* armed absolute CLOCK_MONOTONIC deadline, 0 idle */
    int armed;           /* 1 while a deadline is set */
} dealpg4_deadline_ctx;

/* Current CLOCK_MONOTONIC time in ms (0 if the clock read fails). */
uint64_t dealpg4_now_ms(void);

/* Create the timerfd (once per context). Returns 0, or -1 with errno on
 * timerfd_create failure (no silent fallback, no alternate timing
 * mechanism). */
int dealpg4_deadline_open(dealpg4_deadline_ctx *ctx);

/* Close the timerfd and reset the context to idle. */
void dealpg4_deadline_close(dealpg4_deadline_ctx *ctx);

/* Arm the absolute deadline (CLOCK_MONOTONIC ms). Returns -1 with errno on
 * timerfd_settime failure, leaving the previous state unchanged; on
 * success the new deadline replaces the previous one. */
int dealpg4_deadline_arm(dealpg4_deadline_ctx *ctx, uint64_t deadline_ms);

/* Arm an absolute deadline of now + timeout_ms, computed from a fresh
 * CLOCK_MONOTONIC read. */
int dealpg4_deadline_arm_relative(dealpg4_deadline_ctx *ctx,
                                  uint64_t timeout_ms);

/* EINTR path: recompute the remaining time from a fresh CLOCK_MONOTONIC
 * read and re-arm the identical absolute deadline — the deadline never
 * moves later (no drift, no extension). Returns 1 when still pending
 * (*remaining_ms > 0, timerfd re-armed at the same absolute deadline),
 * 0 when the deadline has passed (*remaining_ms = 0, context idle — the
 * owner drains the timerfd), or -1 with errno (closed/unarmed context or
 * timerfd_settime failure). */
int dealpg4_deadline_recompute(dealpg4_deadline_ctx *ctx,
                               uint64_t *remaining_ms);

/* Drain expiry notifications with reads until EAGAIN. Returns 1 if at
 * least one expiry was observed (*expirations = count, context idle), 0
 * if none, or -1 with errno on a read error other than EAGAIN/EINTR. */
int dealpg4_deadline_drain(dealpg4_deadline_ctx *ctx, uint64_t *expirations);

/* Remaining ms until the armed deadline from a fresh clock read (0 when
 * idle or expired); converts to the owner's ppoll timeout. */
uint64_t dealpg4_deadline_remaining_ms(const dealpg4_deadline_ctx *ctx);

int dealpg4_deadline_armed(const dealpg4_deadline_ctx *ctx);
uint64_t dealpg4_deadline_abs_ms(const dealpg4_deadline_ctx *ctx);

/* Milliseconds to timespec (ppoll/pselect timeout arguments). */
void dealpg4_ms_to_timespec(uint64_t ms, struct timespec *ts);

#endif
