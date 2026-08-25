/*
 * DEALPG4 fault-injection seams (ISSUE-0205): hook table and production
 * defaults. See fi.h for the module contract.
 *
 * The production default delay is the real monotonic-bounded sleep: one
 * timerfd context armed with the absolute CLOCK_MONOTONIC deadline
 * now + ms (the same canonical machinery as every other deadline,
 * dealpg4-time-stream-utilities), a poll block on the timerfd, EINTR
 * recompute against the identical absolute deadline, and a drain at
 * expiry. The sleep never drifts and never extends; it touches no
 * deadline context, so the enclosing component's own deadline remains
 * in force (an injected delay consumes it, never extends it).
 *
 * The process-wide table dealpg4_fi_hooks is initialized here to these
 * defaults — every mode links them, so production modes behave
 * identically to a build without seams. Only the selftest-child
 * installer (selftest.c/h) replaces the entries, in-process, before
 * scenario execution; there is no CLI/env activation surface.
 */
#define _POSIX_C_SOURCE 200809L

#include "fi.h"

#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>

#include "monotonic.h"

int dealpg4_fi_default_delay_ms(unsigned ms, const char *site)
{
    dealpg4_deadline_ctx ctx;

    (void)site; /* production sleeps identically at every site */
    if (dealpg4_deadline_open(&ctx) != 0)
        return -1;
    if (dealpg4_deadline_arm_relative(&ctx, (uint64_t)ms) != 0) {
        dealpg4_deadline_close(&ctx);
        return -1;
    }
    for (;;) {
        struct pollfd pfd;
        uint64_t remaining = dealpg4_deadline_remaining_ms(&ctx);
        int rc;

        if (remaining == 0) {
            /* Deadline reached: drain the expiry (none when the armed
             * relative deadline was already past at arm time) and
             * return. */
            uint64_t expirations = 0;

            if (dealpg4_deadline_drain(&ctx, &expirations) < 0) {
                dealpg4_deadline_close(&ctx);
                return -1;
            }
            break;
        }
        pfd.fd = ctx.fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1,
                  remaining > (uint64_t)INT_MAX ? INT_MAX
                                                : (int)remaining);
        if (rc > 0) {
            uint64_t expirations = 0;

            if ((pfd.revents & POLLIN) == 0) {
                dealpg4_deadline_close(&ctx);
                return -1;
            }
            if (dealpg4_deadline_drain(&ctx, &expirations) < 0) {
                dealpg4_deadline_close(&ctx);
                return -1;
            }
            break;
        }
        if (rc < 0 && errno != EINTR) {
            dealpg4_deadline_close(&ctx);
            return -1;
        }
        /* Timeout or EINTR: recompute against the same absolute deadline
         * — the sleep never drifts and never extends. */
    }
    dealpg4_deadline_close(&ctx);
    return 0;
}

int dealpg4_fi_default_fail(int site)
{
    (void)site;
    return 0; /* no failure, for any site tag */
}

int dealpg4_fi_default_congest(int target, int mode, void *arg)
{
    (void)target;
    (void)mode;
    (void)arg;
    return 0; /* no congestion, for any target/mode tag */
}

/* The process-wide hook table, at the production defaults. */
struct dealpg4_fi dealpg4_fi_hooks = {
    dealpg4_fi_default_delay_ms,
    dealpg4_fi_default_fail,
    dealpg4_fi_default_congest
};
