/*
 * DEALPG4 monotonic deadline utility.
 *
 * Embedded limits constants (canonical runtime copies of
 * tools/launcher-manifest.json), the embedded-limits ordering validation,
 * timerfd absolute-deadline contexts with EINTR recompute, and the
 * canonical phase-recipe arithmetic. See monotonic.h for contracts.
 */
#define _POSIX_C_SOURCE 200809L

#include "monotonic.h"

#include <errno.h>
#include <sys/timerfd.h>
#include <unistd.h>

/* === Embedded limits constants =========================================
 * Values must match tools/launcher-manifest.json field-by-field; a limits
 * change edits the manifest and this file together and re-pins the digest.
 */
const LauncherLimits dealpg4_embedded_launcher_limits = {
    .overallTimeoutMs = DEALPG4_LAUNCHER_OVERALL_TIMEOUT_MS,
    .startupTimeoutMs = DEALPG4_LAUNCHER_STARTUP_TIMEOUT_MS,
    .executionCutoffMs = DEALPG4_LAUNCHER_EXECUTION_CUTOFF_MS,
    .termGraceMs = DEALPG4_LAUNCHER_TERM_GRACE_MS,
    .killAndProofReserveMs = DEALPG4_LAUNCHER_KILL_AND_PROOF_RESERVE_MS,
    .finalizationReserveMs = DEALPG4_LAUNCHER_FINALIZATION_RESERVE_MS
};

const OuterLimits dealpg4_embedded_outer_limits = {
    .overallTimeoutMs = DEALPG4_OUTER_OVERALL_TIMEOUT_MS,
    .readinessTimeoutMs = DEALPG4_OUTER_READINESS_TIMEOUT_MS,
    .nestedStopMs = DEALPG4_OUTER_NESTED_STOP_MS,
    .cleanupReserveMs = DEALPG4_OUTER_CLEANUP_RESERVE_MS,
    .brokerStallMs = DEALPG4_OUTER_BROKER_STALL_MS
};

const SelftestLimits dealpg4_embedded_selftest_limits = {
    .selftestTimeoutMs = DEALPG4_SELFTEST_TIMEOUT_MS
};

/* === Embedded-limits ordering validation ============================== */

dealpg4_limits_status dealpg4_limits_ordering_check(
    const LauncherLimits *launcher, const OuterLimits *outer,
    const SelftestLimits *selftest)
{
    int64_t cleanup_total = launcher->termGraceMs
                          + launcher->killAndProofReserveMs
                          + launcher->finalizationReserveMs;

    if (launcher->startupTimeoutMs > launcher->executionCutoffMs ||
        launcher->executionCutoffMs >
            launcher->overallTimeoutMs - cleanup_total)
        return DEALPG4_LIMITS_INVOCATION_ORDERING_VIOLATED;
    if (outer->nestedStopMs + outer->cleanupReserveMs >
        outer->overallTimeoutMs)
        return DEALPG4_LIMITS_OUTER_ORDERING_VIOLATED;
    if (selftest->selftestTimeoutMs < DEALPG4_SELFTEST_MINIMUM_TIMEOUT_MS)
        return DEALPG4_LIMITS_SELFTEST_MINIMUM_VIOLATED;
    return DEALPG4_LIMITS_OK;
}

dealpg4_limits_status dealpg4_embedded_limits_ordering_check(void)
{
    return dealpg4_limits_ordering_check(&dealpg4_embedded_launcher_limits,
                                         &dealpg4_embedded_outer_limits,
                                         &dealpg4_embedded_selftest_limits);
}

const char *dealpg4_limits_status_name(dealpg4_limits_status status)
{
    switch (status) {
    case DEALPG4_LIMITS_OK:
        return "limits ok";
    case DEALPG4_LIMITS_INVOCATION_ORDERING_VIOLATED:
        return "invocation limits ordering violated";
    case DEALPG4_LIMITS_OUTER_ORDERING_VIOLATED:
        return "outer limits ordering violated";
    case DEALPG4_LIMITS_SELFTEST_MINIMUM_VIOLATED:
        return "selftest limits minimum violated";
    }
    return "unknown limits status";
}

/* === Phase-recipe arithmetic =========================================== */

static int64_t dealpg4_min_i64(int64_t a, int64_t b)
{
    return a < b ? a : b;
}

void dealpg4_invocation_recipe(const LauncherLimits *limits, int64_t t0,
                               int64_t budget_t,
                               dealpg4_invocation_deadlines *out)
{
    int64_t cleanup_total = limits->termGraceMs
                          + limits->killAndProofReserveMs
                          + limits->finalizationReserveMs;

    out->cleanupTotalMs = cleanup_total;
    out->t1 = t0 + dealpg4_min_i64(limits->startupTimeoutMs,
                                   budget_t - cleanup_total);
    out->t2 = t0 + dealpg4_min_i64(limits->executionCutoffMs,
                                   budget_t - cleanup_total);
    out->t3 = out->t2 + limits->termGraceMs;
    out->t4 = out->t3 + limits->killAndProofReserveMs;
    out->t5 = t0 + budget_t;
}

void dealpg4_outer_recipe(const OuterLimits *limits, int64_t t0o,
                          dealpg4_outer_deadlines *out)
{
    out->readinessDeadline = t0o + limits->readinessTimeoutMs;
    out->invokeCutoff = t0o + limits->nestedStopMs;
    out->totalDeadline = t0o + limits->overallTimeoutMs;
}

int64_t dealpg4_broker_stall_deadline(const OuterLimits *limits, int64_t now)
{
    return now + limits->brokerStallMs;
}

/* === Absolute-deadline context ========================================= */

uint64_t dealpg4_now_ms(void)
{
    struct timespec ts;

    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)(ts.tv_nsec / 1000000);
}

int dealpg4_deadline_open(dealpg4_deadline_ctx *ctx)
{
    int fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);

    if (fd < 0)
        return -1;
    ctx->fd = fd;
    ctx->deadline_ms = 0;
    ctx->armed = 0;
    return 0;
}

void dealpg4_deadline_close(dealpg4_deadline_ctx *ctx)
{
    if (ctx->fd >= 0)
        close(ctx->fd);
    ctx->fd = -1;
    ctx->deadline_ms = 0;
    ctx->armed = 0;
}

/* Arm the timerfd for an absolute deadline; used by every arm path. Only
 * this helper ever calls timerfd_settime, and it only ever installs the
 * caller-supplied absolute deadline value (never a computed extension). */
static int dealpg4_timerfd_set_absolute(int fd, uint64_t deadline_ms)
{
    struct itimerspec spec;

    spec.it_value.tv_sec = (time_t)(deadline_ms / 1000u);
    spec.it_value.tv_nsec = (long)((deadline_ms % 1000u) * 1000000u);
    spec.it_interval.tv_sec = 0;
    spec.it_interval.tv_nsec = 0;
    return timerfd_settime(fd, TFD_TIMER_ABSTIME, &spec, NULL);
}

int dealpg4_deadline_arm(dealpg4_deadline_ctx *ctx, uint64_t deadline_ms)
{
    if (ctx->fd < 0) {
        errno = EBADF;
        return -1;
    }
    if (dealpg4_timerfd_set_absolute(ctx->fd, deadline_ms) != 0)
        return -1;
    ctx->deadline_ms = deadline_ms;
    ctx->armed = 1;
    return 0;
}

int dealpg4_deadline_arm_relative(dealpg4_deadline_ctx *ctx,
                                  uint64_t timeout_ms)
{
    return dealpg4_deadline_arm(ctx, dealpg4_now_ms() + timeout_ms);
}

int dealpg4_deadline_recompute(dealpg4_deadline_ctx *ctx,
                               uint64_t *remaining_ms)
{
    uint64_t now;
    uint64_t remaining;

    if (ctx->fd < 0) {
        errno = EBADF;
        return -1;
    }
    if (!ctx->armed) {
        errno = EINVAL;
        return -1;
    }

    now = dealpg4_now_ms();
    if (ctx->deadline_ms > now)
        remaining = ctx->deadline_ms - now;
    else
        remaining = 0;

    if (remaining > 0) {
        /* Re-arm the identical absolute deadline: no drift, no extension. */
        if (dealpg4_timerfd_set_absolute(ctx->fd, ctx->deadline_ms) != 0)
            return -1;
    } else {
        /* Deadline passed; nothing remains armed, the owner drains the
         * timerfd and observes the expiry. */
        ctx->armed = 0;
        ctx->deadline_ms = 0;
    }
    *remaining_ms = remaining;
    return remaining > 0 ? 1 : 0;
}

int dealpg4_deadline_drain(dealpg4_deadline_ctx *ctx, uint64_t *expirations)
{
    uint64_t count = 0;

    if (ctx->fd < 0) {
        errno = EBADF;
        return -1;
    }
    for (;;) {
        uint64_t n = 0;
        ssize_t r = read(ctx->fd, &n, sizeof(n));

        if (r == (ssize_t)sizeof(n)) {
            count += n;
            continue;
        }
        if (r < 0) {
            if (errno == EINTR)
                continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK)
                break;
            return -1;
        }
        /* A short (or zero) read cannot happen on an armed timerfd. */
        errno = EIO;
        return -1;
    }
    if (count > 0) {
        ctx->armed = 0;
        ctx->deadline_ms = 0;
        *expirations = count;
        return 1;
    }
    *expirations = 0;
    return 0;
}

uint64_t dealpg4_deadline_remaining_ms(const dealpg4_deadline_ctx *ctx)
{
    uint64_t now;

    if (!ctx->armed)
        return 0;
    now = dealpg4_now_ms();
    if (ctx->deadline_ms > now)
        return ctx->deadline_ms - now;
    return 0;
}

int dealpg4_deadline_armed(const dealpg4_deadline_ctx *ctx)
{
    return ctx->armed;
}

uint64_t dealpg4_deadline_abs_ms(const dealpg4_deadline_ctx *ctx)
{
    return ctx->deadline_ms;
}

void dealpg4_ms_to_timespec(uint64_t ms, struct timespec *ts)
{
    ts->tv_sec = (time_t)(ms / 1000u);
    ts->tv_nsec = (long)((ms % 1000u) * 1000000u);
}
