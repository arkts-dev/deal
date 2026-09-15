/*
 * DEALPG4 probe mode, selftest mode, and the six micro-batteries.
 *
 * Probe mode (ISSUE-0203) and selftest mode surface (ISSUE-0204) live
 * here. Owns (dealpg4-probe-selftest-foundation D1-D4, probe/selftest
 * mode contracts; dealpg4-time-stream-utilities battery mechanics):
 *  - the byte-stable three-part probe report: exactly one identity line
 *    "DEALPG4 4 linux-x86_64 CAPS 63" (every advertised capability bit
 *    is backed by a passing battery in this artifact), exactly one
 *    12-field LIMITS line printed from the embedded limits records (only
 *    after the mode-entry limits validation passed), and one
 *    "OK <battery>" line per passing battery in the canonical order
 *    monotonic-timer, subreaper, parent-death, negative-pgid,
 *    bounded-drain, outer-registry-broker; exit 0 iff every battery
 *    passes — no skip, no retry;
 *  - the six kernel-mechanism batteries, each running in fresh forked
 *    children; the prober is the subreaper and reaps everything it forks;
 *    post-state: no survivors, sessions, or open battery fds — battery
 *    failure and bound-expiry paths SIGKILL the battery's known live
 *    children before reaping (kill-then-reap), so a failed battery never
 *    leaks a child;
 *  - one overall monotonic bound armed from probe entry with the
 *    monotonic context (default 15000 ms; the dispatch-validated
 *    --limit-ms N override, N >= 15000): expiry prints PROBE_TIMEOUT on
 *    stderr and exits nonzero;
 *  - the named failure tokens: a failing battery prints
 *    "CAPABILITY_MISSING <battery>" on stderr and exits nonzero.
 *
 * Selftest mode:
 *  - the mode-level monotonic bound armed from selftest entry with the
 *    same monotonic context (timerfd_create(CLOCK_MONOTONIC,
 *    TFD_NONBLOCK|TFD_CLOEXEC)) as every other deadline: the effective
 *    bound is the dispatch-validated --limit-ms N override when present
 *    (N >= 15000) and the embedded selftestLimits.selftestTimeoutMs
 *    otherwise, read from the same embedded constants the probe reports
 *    (never a hardcoded default — bound provenance). The bound bounds
 *    the selftest run only — never an invocation/outer limit — and
 *    changes no embedded constant, probe report, or manifest;
 *  - one selftest child forked from the entry inherits the bound
 *    context and runs an explicit additive battery list: at this stage
 *    the six probe batteries, printing the same six "OK <battery>"
 *    lines as probe in the same order (identity/LIMITS lines are
 *    probe-only); the fault-injection battery (excluded, ISSUE-0184)
 *    appends to the same list, child, and bound machinery, with every
 *    future scenario deadline-owned at the earlier of its scenario
 *    budget and the remaining mode bound — the bound machinery needs no
 *    change;
 *  - success: every battery passed and the bound did not fire (exit 0).
 *    Failure: the first failing battery's "CAPABILITY_MISSING
 *    <battery>" or SELFTEST_TIMEOUT on stderr (the entry owns the single
 *    token print; the child reports through its exit status), nonzero
 *    exit; no skip, no retry. Post-state: the selftest child reaped, no
 *    survivors (the child runs in its own process group so the
 *    bound-expiry kill takes the whole battery subtree down at once),
 *    bound context closed.
 *
 * Battery contracts (each passes only when its full success criterion
 * holds; any deviation is a battery failure — no skip, no retry):
 *  - monotonic-timer: a timerfd via the monotonic context; clock read t0;
 *    an absolute deadline t0 + 250 ms armed; block on the timerfd; clock
 *    read t1 at expiry; success iff t1 >= the absolute deadline and every
 *    clock sample taken during the run is non-decreasing; failure on
 *    early fire or a backwards sample; timerfd closed after.
 *  - subreaper: the prober sets prctl(PR_SET_CHILD_SUBREAPER, 1) and
 *    verifies PR_GET_CHILD_SUBREAPER == 1; forks a child which forks a
 *    grandchild (readiness reported via a pipe); the child exits; success
 *    iff the grandchild reparents to the prober (nearest living
 *    subreaper) and is reaped by the prober - the prober-side
 *    waitid(P_PID, gpid) adoption proof (deadline-bounded, loop to
 *    ECHILD) is the only reparent verification; no live descendants, no
 *    zombie.
 *  - parent-death: the prober forks intermediate A; A forks probe child
 *    B; B sets PR_SET_PDEATHSIG=SIGKILL, rechecks getppid() equals A's
 *    pid, and reports ready via a pipe; the prober kills A with SIGKILL;
 *    success iff B dies by SIGKILL via the parent-death signal (reaped
 *    CLD_KILLED, si_status SIGKILL) and both A and B are reaped (B via
 *    subreaper adoption); no survivors.
 *  - negative-pgid: the prober forks a throwaway child that places itself
 *    in its own process group (setpgid(0,0)) and reports its pgid via a
 *    pipe; success iff kill(-pgid, 0) == 0 resolves the group, a SIGTERM
 *    to -pgid is delivered (the child dies by SIGTERM), and the child is
 *    reaped; group gone afterwards.
 *  - bounded-drain: a pipe is created; a forked child writes
 *    1048576 + 4096 bytes to the write end and exits; the prober drains
 *    the read end with the drain utility; success iff the retained buffer
 *    is exactly the first 1048576 bytes plus the marker
 *    "\n[STREAM TRUNCATED at 1 MiB]\n" at the cut point, read-side EOF is
 *    observed, and the child is reaped; pipe closed after.
 *  - outer-registry-broker (the atomic CAPS flip, ISSUE-0524 — the
 *    tools/src/outer.h engine D7 battery contract, appended after
 *    bounded-drain in the canonical order): a 0700 scratch directory
 *    (mkdtemp under TMPDIR, removed afterward); socket(AF_UNIX,
 *    SOCK_STREAM); bind at a scratch path with the stale-same-name
 *    unlink first and chmod(path, 0600) after bind; listen; a forked
 *    child connects to the path and performs one bounded line
 *    round-trip; accept returns exactly the one connection — a
 *    follow-up non-blocking accept must report EAGAIN (no second
 *    connection); SO_PEERCRED on the accepted socket reports pid ==
 *    the connecting child's pid and uid == getuid(); close and
 *    unlink the path; the child is reaped; the scratch dir is
 *    removed. Success iff every step holds, the socket path is
 *    unlinked, and no survivor remains; every wait is bounded by the
 *    earlier of the battery-local deadline and the overall mode
 *    bound, and a failing path kill-reaps its child (no leaked
 *    process).
 *
 * Fault-injection override installer (ISSUE-0205,
 * dealpg4-probe-selftest-foundation D5 seam contract): the
 * selftest-child-only, in-process installer for scripted overrides on
 * the fi hook table — explicit single-shot or always-on entries with
 * exact durations, values, and modes; every entry validated against
 * the named site/target/mode catalog before anything is installed
 * (unknown tags are installer errors, nothing injects silently); no
 * CLI/env activation surface and no randomness. See selftest.h for the
 * installer contract.
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include "selftest.h"

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "drain.h"
#include "monotonic.h"

/* The pinned platform: the artifact builds and runs only on linux-x86_64;
 * the identity line reports the build platform, so a foreign build is
 * refused at compile time (fail-closed, no silent misreport). */
#if !defined(__linux__) || !defined(__x86_64__)
#error "DEALPG4 launcher artifact builds only on linux-x86_64 (pinned platform)"
#endif

/* CAPS 63: exactly the six battery-backed bits (1|2|4|8|16|32). */
_Static_assert(DEALPG4_PROBE_CAPS == 63,
               "CAPS 63: bits 1|2|4|8|16|32, every bit battery-backed");

/* === Battery mechanics constants ======================================= */

/* monotonic-timer probe delta: t0 + 250 ms absolute deadline
 * (architect-decided). */
#define DEALPG4_BATTERY_TIMER_DELTA_MS 250u

/* Battery-local deadlines: every blocking point is bounded by the earlier
 * of this local deadline and the overall mode bound; battery mechanics
 * use their own short absolute deadlines. */
#define DEALPG4_BATTERY_LOCAL_DEADLINE_MS 5000u

/* Cleanup grace: on a failure path, reap_all waits at most this long for
 * stragglers (each battery's children are self-bounded). */
#define DEALPG4_BATTERY_CLEANUP_GRACE_MS 10000u

/* Pure-sleep slice: fd < 0 polls sleep in short slices so child-state
 * changes are re-checked promptly (a SIGCHLD can be delivered between the
 * WNOHANG reap check and the sleep; a full-deadline sleep would miss it
 * and fail the battery on a race). */
#define DEALPG4_BATTERY_SLEEP_SLICE_MS 10

/* negative-pgid child self-limit: if the group SIGTERM never arrives, the
 * child dies by SIGALRM so the battery observes the failure instead of
 * hanging. */
#define DEALPG4_BATTERY_PGID_ALARM_S 2u

/* outer-registry-broker child self-limit: if the round-trip never
 * completes, the child dies by SIGALRM so the battery observes the
 * failure instead of hanging. */
#define DEALPG4_BATTERY_ORB_ALARM_S 5u

/* bounded-drain overshoot past the cap (architect-decided; any overshoot
 * exercises the cut point): 1048576 + 4096 bytes. */
#define DEALPG4_DRAIN_BATTERY_OVERSHOOT_BYTES 4096

/* === Battery result and mode bound ==================================== */

enum dealpg4_battery_result {
    DEALPG4_BATTERY_PASS = 0,  /* full success criterion holds */
    DEALPG4_BATTERY_FAIL = -1, /* capability failure -> CAPABILITY_MISSING */
    DEALPG4_BATTERY_BOUND = -2 /* overall mode bound expired ->
                                  PROBE_TIMEOUT/SELFTEST_TIMEOUT */
};

/* The overall mode bound (probe or selftest): one monotonic context
 * armed from mode entry, plus the sticky expired flag (a drained
 * timerfd is idle, so the expiry observation must persist for every
 * later check). */
typedef struct dealpg4_battery_bound {
    dealpg4_deadline_ctx ctx;
    int expired;
} dealpg4_battery_bound;

/* 1 once the overall bound has fired (expiry drained from the timerfd;
 * the observation is sticky). A timerfd read error fails closed as
 * expired. */
static int dealpg4_battery_bound_expired(dealpg4_battery_bound *bound)
{
    uint64_t expirations = 0;

    if (bound->expired)
        return 1;
    if (dealpg4_deadline_drain(&bound->ctx, &expirations) != 0) {
        bound->expired = 1;
        return 1;
    }
    if (dealpg4_deadline_armed(&bound->ctx)
        && dealpg4_deadline_remaining_ms(&bound->ctx) == 0) {
        bound->expired = 1;
        return 1;
    }
    return 0;
}

/* === Bounded blocking helpers ==========================================
 * Every battery blocking point runs through these: nothing can suspend
 * past the earlier of the battery-local deadline and the overall bound,
 * and the overall bound expiry propagates as DEALPG4_BATTERY_BOUND.
 * Child-reap loops sleep one short slice at a time and re-check the
 * WNOHANG reap every iteration; fd waits block in poll until the fd is
 * ready (data or EOF both wake POLLIN) or a deadline passes.
 */

/* Sleep one short slice, bounded by the overall mode bound. Returns 0
 * after the slice, DEALPG4_BATTERY_BOUND when the overall bound expired,
 * or -1 on poll error. The caller owns the local deadline and re-checks
 * child state after every slice (a SIGCHLD can already have been
 * delivered before the sleep began; a full-deadline sleep would miss the
 * state change and fail the battery on a race). */
static int dealpg4_sleep_slice(dealpg4_battery_bound *bound)
{
    struct pollfd pfd;

    if (dealpg4_battery_bound_expired(bound))
        return DEALPG4_BATTERY_BOUND;
    pfd.fd = -1;
    pfd.events = 0;
    pfd.revents = 0;
    for (;;) {
        int rc = poll(&pfd, 1, DEALPG4_BATTERY_SLEEP_SLICE_MS);

        if (rc == 0)
            return 0;
        if (rc < 0 && errno != EINTR)
            return -1;
    }
}

/* Poll fd for POLLIN until ready, the local deadline passes, or the
 * overall bound expires. Returns 1 on readiness (*revents), 0 on local
 * timeout, DEALPG4_BATTERY_BOUND on bound expiry, or -1 on poll error. */
static int dealpg4_poll_wait(int fd, uint64_t local_deadline_ms,
                             dealpg4_battery_bound *bound, short *revents)
{
    for (;;) {
        struct pollfd pfd;
        uint64_t now, remain, bound_remain;
        int timeout, rc;

        if (dealpg4_battery_bound_expired(bound))
            return DEALPG4_BATTERY_BOUND;
        now = dealpg4_now_ms();
        if (now >= local_deadline_ms)
            return 0;
        remain = local_deadline_ms - now;
        if (dealpg4_deadline_armed(&bound->ctx)) {
            bound_remain = dealpg4_deadline_remaining_ms(&bound->ctx);
            if (bound_remain < remain)
                remain = bound_remain;
        }
        timeout = remain > (uint64_t)INT_MAX ? INT_MAX : (int)remain;

        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1, timeout);
        if (rc > 0) {
            if (revents != NULL)
                *revents = pfd.revents;
            return 1;
        }
        if (rc < 0 && errno != EINTR)
            return -1;
        /* timeout or EINTR: recompute against the same deadlines */
    }
}

/* Read exactly len bytes from a non-blocking fd, poll-bounded. Returns 0
 * on success, DEALPG4_BATTERY_BOUND on bound expiry, or -1 on EOF before
 * len bytes / read error / local timeout. */
static int dealpg4_read_bounded(int fd, void *buf, size_t len,
                                uint64_t local_deadline_ms,
                                dealpg4_battery_bound *bound)
{
    unsigned char *p = buf;
    size_t got = 0;

    while (got < len) {
        ssize_t r = read(fd, p + got, len - got);

        if (r > 0) {
            got += (size_t)r;
            continue;
        }
        if (r < 0) {
            if (errno == EINTR)
                continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                short revents = 0;
                int rc = dealpg4_poll_wait(fd, local_deadline_ms, bound,
                                           &revents);

                if (rc != 1)
                    return rc == DEALPG4_BATTERY_BOUND
                               ? DEALPG4_BATTERY_BOUND
                               : -1;
                continue;
            }
            return -1;
        }
        return -1; /* EOF before len bytes */
    }
    return 0;
}

/* Reap a specific child with a local deadline. Returns 0 with *status on
 * reaping, DEALPG4_BATTERY_BOUND on bound expiry, or -1 (wait error or
 * local timeout — the child is still running). */
static int dealpg4_waitpid_bounded(pid_t pid, int *status,
                                   uint64_t local_deadline_ms,
                                   dealpg4_battery_bound *bound)
{
    for (;;) {
        pid_t r = waitpid(pid, status, WNOHANG);

        if (r == pid)
            return 0;
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (dealpg4_now_ms() >= local_deadline_ms)
            return -1;
        {
            int rc = dealpg4_sleep_slice(bound);

            if (rc == DEALPG4_BATTERY_BOUND)
                return DEALPG4_BATTERY_BOUND;
            if (rc != 0)
                return -1;
        }
    }
}

/* Reap every exited descendant (waitid loop) until no children remain
 * (ECHILD). Live-but-not-exited children make this wait up to the local
 * deadline (battery children are self-bounded and exit/die shortly);
 * returns 0 when no children remain, DEALPG4_BATTERY_BOUND on bound
 * expiry, or -1 when a live descendant remains at the deadline or a wait
 * error occurs. */
static int dealpg4_reap_all_bounded(dealpg4_battery_bound *bound,
                                    uint64_t local_deadline_ms)
{
    for (;;) {
        siginfo_t si;

        memset(&si, 0, sizeof(si));
        if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) < 0) {
            if (errno == ECHILD)
                return 0; /* no children: clean */
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (si.si_pid != 0)
            continue; /* reaped one; loop to ECHILD */
        /* live children remain: give them a bounded moment to exit */
        if (dealpg4_now_ms() >= local_deadline_ms)
            return -1; /* local deadline with live children */
        {
            int rc = dealpg4_sleep_slice(bound);

            if (rc == DEALPG4_BATTERY_BOUND)
                return DEALPG4_BATTERY_BOUND;
            if (rc != 0)
                return -1;
        }
    }
}

/* Best-effort failure-path cleanup: reap every exited descendant; live
 * stragglers are expected to exit/die within the grace window (each
 * battery's children are self-bounded). */
static void dealpg4_battery_cleanup_until(dealpg4_battery_bound *bound,
                                          uint64_t local_deadline_ms)
{
    (void)dealpg4_reap_all_bounded(bound, local_deadline_ms);
}

/* Kill-then-reap failure-path cleanup: SIGKILL the battery's known live
 * children first — the no-survivors post-state applies to failure runs
 * too, and a battery child that outlives its battery is a leaked
 * process — then wait for each to be reaped within the cleanup grace
 * window and reap every remaining exited descendant. kill() on an
 * already-exited child is a harmless ESRCH; on a zombie it succeeds and
 * the waitid below collects it. The per-pid wait uses plain nanosleep
 * slices (not the probe-bound-gated sleep slice) so an already-expired
 * mode bound cannot skip the reaping of children we just killed; the
 * grace deadline still bounds the whole cleanup. */
static void dealpg4_battery_kill_reap(dealpg4_battery_bound *bound,
                                      const pid_t *pids, size_t npids)
{
    uint64_t grace = dealpg4_now_ms() + DEALPG4_BATTERY_CLEANUP_GRACE_MS;
    size_t i;

    for (i = 0; i < npids; i++) {
        if (pids[i] > 0)
            (void)kill(pids[i], SIGKILL);
    }
    for (i = 0; i < npids; i++) {
        if (pids[i] <= 0)
            continue;
        for (;;) {
            siginfo_t si;

            memset(&si, 0, sizeof(si));
            if (waitid(P_PID, pids[i], &si, WEXITED | WNOHANG) < 0) {
                if (errno == ECHILD)
                    break; /* already reaped / not (yet) a child */
                if (errno == EINTR)
                    continue;
                break;
            }
            if (si.si_pid != 0)
                break; /* reaped this pid */
            if (dealpg4_now_ms() >= grace)
                break; /* grace window exhausted */
            {
                const struct timespec ts = { 0, 1000000 };

                nanosleep(&ts, NULL);
            }
        }
    }
    dealpg4_battery_cleanup_until(bound, grace);
}

/* Kill-then-reap a single known battery child. */
static void dealpg4_battery_kill_reap_one(dealpg4_battery_bound *bound,
                                          pid_t pid)
{
    dealpg4_battery_kill_reap(bound, &pid, 1);
}

/* Child-side full write: loop over partial writes and EINTR. Returns 1 on
 * success; the writer child exits nonzero on any failure. */
static int dealpg4_battery_write_all(int fd, const void *buf, size_t len)
{
    const unsigned char *p = buf;

    while (len > 0) {
        ssize_t w = write(fd, p, len);

        if (w > 0) {
            p += w;
            len -= (size_t)w;
            continue;
        }
        if (w < 0 && errno == EINTR)
            continue;
        return 0;
    }
    return 1;
}

/* === The six micro-batteries ========================================== */

static int dealpg4_battery_monotonic_timer(dealpg4_battery_bound *bound)
{
    dealpg4_deadline_ctx t;
    uint64_t t0, t1, prev, deadline, expirations;

    t0 = dealpg4_now_ms();
    if (t0 == 0)
        return DEALPG4_BATTERY_FAIL; /* no valid clock sample */

    if (dealpg4_deadline_open(&t) != 0)
        return DEALPG4_BATTERY_FAIL;
    deadline = t0 + DEALPG4_BATTERY_TIMER_DELTA_MS;
    if (dealpg4_deadline_arm(&t, deadline) != 0) {
        dealpg4_deadline_close(&t);
        return DEALPG4_BATTERY_FAIL;
    }

    /* Block on the timerfd until the absolute deadline fires; every clock
     * sample taken during the run must be non-decreasing, checked pairwise
     * against the previous sample (not only against the first sample). */
    expirations = 0;
    prev = t0;
    for (;;) {
        struct pollfd pfd;
        uint64_t now, remain, bound_remain;
        int rc, timeout;

        if (dealpg4_battery_bound_expired(bound)) {
            dealpg4_deadline_close(&t);
            return DEALPG4_BATTERY_BOUND;
        }
        now = dealpg4_now_ms();
        if (now < t0 || now < prev) { /* backwards sample */
            dealpg4_deadline_close(&t);
            return DEALPG4_BATTERY_FAIL;
        }
        prev = now;
        remain = deadline > now ? deadline - now : 0;
        if (dealpg4_deadline_armed(&bound->ctx)) {
            bound_remain = dealpg4_deadline_remaining_ms(&bound->ctx);
            if (bound_remain < remain)
                remain = bound_remain;
        }
        timeout = remain > (uint64_t)INT_MAX ? INT_MAX : (int)remain;

        pfd.fd = t.fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        rc = poll(&pfd, 1, timeout);
        if (rc > 0) {
            if ((pfd.revents & POLLIN) == 0) {
                dealpg4_deadline_close(&t);
                return DEALPG4_BATTERY_FAIL;
            }
            if (dealpg4_deadline_drain(&t, &expirations) != 1
                || expirations == 0) {
                dealpg4_deadline_close(&t);
                return DEALPG4_BATTERY_FAIL;
            }
            break;
        }
        if (rc < 0 && errno != EINTR) {
            dealpg4_deadline_close(&t);
            return DEALPG4_BATTERY_FAIL;
        }
        /* timeout or EINTR: recompute against the same absolute deadline
         * (the deadline never moves later) */
    }

    t1 = dealpg4_now_ms();
    if (t1 == 0 || t1 < t0 || t1 < prev) {
        /* failed read or backwards sample */
        dealpg4_deadline_close(&t);
        return DEALPG4_BATTERY_FAIL;
    }
    if (t1 < deadline) { /* early fire */
        dealpg4_deadline_close(&t);
        return DEALPG4_BATTERY_FAIL;
    }
    dealpg4_deadline_close(&t);
    return DEALPG4_BATTERY_PASS;
}

static int dealpg4_battery_subreaper(dealpg4_battery_bound *bound)
{
    int pipefd[2];
    pid_t child, gpid;
    uint64_t local;
    int status;
    int rc;

    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0)
        return DEALPG4_BATTERY_FAIL;
    {
        int is_subreaper = 0;

        if (prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) != 0)
            return DEALPG4_BATTERY_FAIL;
        if (is_subreaper != 1)
            return DEALPG4_BATTERY_FAIL;
    }

    if (pipe2(pipefd, O_NONBLOCK | O_CLOEXEC) != 0)
        return DEALPG4_BATTERY_FAIL;

    child = fork();
    if (child < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return DEALPG4_BATTERY_FAIL;
    }
    if (child == 0) {
        /* child: spawn the grandchild and exit at once; when the child
         * exits, the grandchild (still running or already exited - the
         * zombie reparents too) reparents to the prober (the nearest
         * living subreaper). */
        pid_t g = fork();

        if (g < 0)
            _exit(1);
        if (g == 0) {
            /* grandchild: report its own pid (readiness), then exit.
             * No child-side reparent observation and no fixed
             * reparent-wait budget: the prober-side waitid(P_PID, gpid)
             * adoption proof verifies the reparent to the prober and is
             * deadline-bounded, so a scheduler-delayed child exit can
             * never exhaust a child-side budget and spuriously fail the
             * battery. */
            pid_t me = getpid();

            if (write(pipefd[1], &me, sizeof(me)) != (ssize_t)sizeof(me))
                _exit(1);
            _exit(0);
        }
        _exit(0); /* the child exits once the grandchild is forked */
    }
    close(pipefd[1]);

    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    rc = dealpg4_read_bounded(pipefd[0], &gpid, sizeof(gpid), local, bound);
    if (rc != 0) {
        close(pipefd[0]);
        /* The grandchild's pid is not yet known; killing the child makes
         * the kernel reparent the grandchild (running or already exited)
         * to the prober, and the kill-reap cleanup collects it. */
        dealpg4_battery_kill_reap_one(bound, child);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }

    /* Reap the child; its exit is what reparented the grandchild (the
     * zombie reparents even when the grandchild already exited). */
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    status = 0;
    rc = dealpg4_waitpid_bounded(child, &status, local, bound);
    if (rc == DEALPG4_BATTERY_BOUND) {
        pid_t victims[2];

        victims[0] = child;
        victims[1] = gpid;
        close(pipefd[0]);
        dealpg4_battery_kill_reap(bound, victims, 2);
        return DEALPG4_BATTERY_BOUND;
    }
    if (rc != 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        pid_t victims[2];

        victims[0] = child;
        victims[1] = gpid;
        close(pipefd[0]);
        dealpg4_battery_kill_reap(bound, victims, 2);
        return DEALPG4_BATTERY_FAIL;
    }

    close(pipefd[0]);

    /* Reap the adopted grandchild: waitid(P_PID) succeeds only for the
     * prober's own children, so this proves the reparent to the prober;
     * ECHILD here means the grandchild reparented elsewhere and the
     * battery fails closed. */
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    {
        siginfo_t si;

        for (;;) {
            memset(&si, 0, sizeof(si));
            if (waitid(P_PID, gpid, &si, WEXITED | WNOHANG) < 0) {
                if (errno == EINTR)
                    continue;
                dealpg4_battery_kill_reap_one(bound, gpid);
                return DEALPG4_BATTERY_FAIL;
            }
            if (si.si_pid == gpid) {
                if (si.si_code != CLD_EXITED || si.si_status != 0) {
                    dealpg4_battery_kill_reap_one(bound, gpid);
                    return DEALPG4_BATTERY_FAIL;
                }
                break;
            }
            if (dealpg4_now_ms() >= local) {
                dealpg4_battery_kill_reap_one(bound, gpid);
                return DEALPG4_BATTERY_FAIL;
            }
            {
                int rc = dealpg4_sleep_slice(bound);

                if (rc != 0) {
                    dealpg4_battery_kill_reap_one(bound, gpid);
                    return rc == DEALPG4_BATTERY_BOUND
                               ? DEALPG4_BATTERY_BOUND
                               : DEALPG4_BATTERY_FAIL;
                }
            }
        }
    }

    /* No live descendants, no zombie: waitid loop to ECHILD. */
    rc = dealpg4_reap_all_bounded(bound,
                                  dealpg4_now_ms()
                                      + DEALPG4_BATTERY_LOCAL_DEADLINE_MS);
    if (rc != 0) {
        dealpg4_battery_kill_reap_one(bound, gpid);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    return DEALPG4_BATTERY_PASS;
}

static int dealpg4_battery_parent_death(dealpg4_battery_bound *bound)
{
    int pipefd[2];
    pid_t a, b;
    int status;
    int rc;
    uint64_t local;

    /* B's reaping relies on subreaper adoption. The subreaper battery
     * (canonical order, runs first) established the prober as subreaper;
     * verify explicitly so a regression fails here with a clear name. */
    {
        int is_subreaper = 0;

        if (prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) != 0)
            return DEALPG4_BATTERY_FAIL;
        if (is_subreaper != 1)
            return DEALPG4_BATTERY_FAIL;
    }

    if (pipe2(pipefd, O_NONBLOCK | O_CLOEXEC) != 0)
        return DEALPG4_BATTERY_FAIL;

    a = fork();
    if (a < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return DEALPG4_BATTERY_FAIL;
    }
    if (a == 0) {
        /* A: intermediate parent. */
        pid_t b_pid = fork();

        if (b_pid < 0)
            _exit(1);
        if (b_pid == 0) {
            /* B: probe child. */
            pid_t a_pid = getppid();
            pid_t me;

            if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
                _exit(1);
            if (getppid() != a_pid)
                _exit(2); /* A died during setup: the PDEATHSIG race */
            me = getpid();
            if (write(pipefd[1], &me, sizeof(me)) != (ssize_t)sizeof(me))
                _exit(1);
            for (;;)
                pause();
        }
        /* A: stay alive until the prober kills it. */
        for (;;)
            pause();
    }
    close(pipefd[1]);

    /* B reports ready with its pid. */
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    rc = dealpg4_read_bounded(pipefd[0], &b, sizeof(b), local, bound);
    if (rc != 0) {
        close(pipefd[0]);
        /* B's pid is not yet known if the read failed; A's death
         * delivers B's parent-death signal (or B self-exits on the
         * PDEATHSIG race check). */
        dealpg4_battery_kill_reap_one(bound, a);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    close(pipefd[0]);

    /* Kill the intermediate parent; the kernel delivers SIGKILL to B
     * through the parent-death signal. */
    if (kill(a, SIGKILL) != 0) {
        pid_t victims[2];

        victims[0] = a;
        victims[1] = b;
        dealpg4_battery_kill_reap(bound, victims, 2);
        return DEALPG4_BATTERY_FAIL;
    }

    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    status = 0;
    rc = dealpg4_waitpid_bounded(a, &status, local, bound);
    if (rc == DEALPG4_BATTERY_BOUND) {
        pid_t victims[2];

        victims[0] = a;
        victims[1] = b;
        dealpg4_battery_kill_reap(bound, victims, 2);
        return DEALPG4_BATTERY_BOUND;
    }
    if (rc != 0 || !WIFSIGNALED(status) || WTERMSIG(status) != SIGKILL) {
        pid_t victims[2];

        victims[0] = a;
        victims[1] = b;
        dealpg4_battery_kill_reap(bound, victims, 2);
        return DEALPG4_BATTERY_FAIL;
    }

    /* B dies by SIGKILL via the parent-death signal and is reaped via
     * subreaper adoption (waitid P_PID proves the adoption). */
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    {
        siginfo_t si;

        for (;;) {
            memset(&si, 0, sizeof(si));
            if (waitid(P_PID, b, &si, WEXITED | WNOHANG) < 0) {
                if (errno == EINTR)
                    continue;
                dealpg4_battery_kill_reap_one(bound, b);
                return DEALPG4_BATTERY_FAIL;
            }
            if (si.si_pid == b) {
                if (si.si_code != CLD_KILLED || si.si_status != SIGKILL) {
                    dealpg4_battery_kill_reap_one(bound, b);
                    return DEALPG4_BATTERY_FAIL;
                }
                break;
            }
            if (dealpg4_now_ms() >= local) {
                dealpg4_battery_kill_reap_one(bound, b);
                return DEALPG4_BATTERY_FAIL;
            }
            {
                int rc = dealpg4_sleep_slice(bound);

                if (rc != 0) {
                    dealpg4_battery_kill_reap_one(bound, b);
                    return rc == DEALPG4_BATTERY_BOUND
                               ? DEALPG4_BATTERY_BOUND
                               : DEALPG4_BATTERY_FAIL;
                }
            }
        }
    }

    /* Both A and B reaped; no survivors. */
    rc = dealpg4_reap_all_bounded(bound,
                                  dealpg4_now_ms()
                                      + DEALPG4_BATTERY_LOCAL_DEADLINE_MS);
    if (rc != 0) {
        dealpg4_battery_kill_reap_one(bound, b);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    return DEALPG4_BATTERY_PASS;
}

static int dealpg4_battery_negative_pgid(dealpg4_battery_bound *bound)
{
    int pipefd[2];
    pid_t child, pgid;
    int status;
    int rc;
    uint64_t local;

    if (pipe2(pipefd, O_NONBLOCK | O_CLOEXEC) != 0)
        return DEALPG4_BATTERY_FAIL;

    child = fork();
    if (child < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return DEALPG4_BATTERY_FAIL;
    }
    if (child == 0) {
        /* Throwaway child: its own process group (pgid == its pid),
         * self-limited by SIGALRM so a failed group delivery cannot hang
         * the probe. */
        pid_t me;

        close(pipefd[0]);
        if (setpgid(0, 0) != 0)
            _exit(1);
        me = getpid();
        if (write(pipefd[1], &me, sizeof(me)) != (ssize_t)sizeof(me))
            _exit(1);
        close(pipefd[1]);
        alarm(DEALPG4_BATTERY_PGID_ALARM_S);
        for (;;)
            pause();
    }
    close(pipefd[1]);

    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    rc = dealpg4_read_bounded(pipefd[0], &pgid, sizeof(pgid), local, bound);
    if (rc != 0) {
        close(pipefd[0]);
        dealpg4_battery_kill_reap_one(bound, child);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    close(pipefd[0]);

    /* The group must resolve via the negative pgid. */
    if (kill(-pgid, 0) != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    /* Deliver SIGTERM to the whole group. */
    if (kill(-pgid, SIGTERM) != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    status = 0;
    rc = dealpg4_waitpid_bounded(child, &status, local, bound);
    if (rc == DEALPG4_BATTERY_BOUND) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_BOUND;
    }
    if (rc != 0 || !WIFSIGNALED(status) || WTERMSIG(status) != SIGTERM) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    /* The group must be gone afterwards. */
    if (kill(-pgid, 0) == 0 || errno != ESRCH) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    rc = dealpg4_reap_all_bounded(bound,
                                  dealpg4_now_ms()
                                      + DEALPG4_BATTERY_LOCAL_DEADLINE_MS);
    if (rc != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    return DEALPG4_BATTERY_PASS;
}

static int dealpg4_battery_bounded_drain(dealpg4_battery_bound *bound)
{
    int pipefd[2];
    pid_t child;
    int status;
    int rc;
    dealpg4_drain_ctx dctx;
    uint64_t local;
    size_t i;

    if (pipe2(pipefd, 0) != 0)
        return DEALPG4_BATTERY_FAIL;

    child = fork();
    if (child < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return DEALPG4_BATTERY_FAIL;
    }
    if (child == 0) {
        /* Writer: cap + overshoot bytes of a deterministic per-offset
         * pattern; blocking writes are fine — the prober drains
         * concurrently. */
        unsigned char buf[DEALPG4_DRAIN_READ_CHUNK_BYTES];
        uint64_t written = 0;

        close(pipefd[0]);
        while (written < DEALPG4_DRAIN_CAP_BYTES
                             + DEALPG4_DRAIN_BATTERY_OVERSHOOT_BYTES) {
            size_t j;

            for (j = 0; j < sizeof(buf); j++)
                buf[j] = (unsigned char)((written + j) & 0xFF);
            if (!dealpg4_battery_write_all(pipefd[1], buf, sizeof(buf)))
                _exit(1);
            written += sizeof(buf);
        }
        close(pipefd[1]);
        _exit(0);
    }
    close(pipefd[1]);

    /* The drain utility requires a non-blocking read fd. */
    if (fcntl(pipefd[0], F_SETFL, O_NONBLOCK) != 0) {
        close(pipefd[0]);
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    dealpg4_drain_init(&dctx);
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    for (;;) {
        dealpg4_drain_status ds = dealpg4_drain_pump(&dctx, pipefd[0]);

        if (ds == DEALPG4_DRAIN_EOF)
            break;
        if (ds == DEALPG4_DRAIN_ERROR) {
            close(pipefd[0]);
            dealpg4_battery_kill_reap_one(bound, child);
            return DEALPG4_BATTERY_FAIL;
        }
        /* EAGAIN: wait for POLLIN, bounded by the battery deadline and
         * the overall mode bound. */
        {
            short revents = 0;
            int rc = dealpg4_poll_wait(pipefd[0], local, bound, &revents);

            if (rc != 1) {
                close(pipefd[0]);
                dealpg4_battery_kill_reap_one(bound, child);
                return rc == DEALPG4_BATTERY_BOUND
                           ? DEALPG4_BATTERY_BOUND
                           : DEALPG4_BATTERY_FAIL;
            }
        }
    }
    close(pipefd[0]);

    /* Exact drain outcome: the first 1048576 bytes retained, the marker
     * exactly at the cut point, EOF observed, the whole stream read. */
    if (dctx.total_read != DEALPG4_DRAIN_CAP_BYTES
                               + DEALPG4_DRAIN_BATTERY_OVERSHOOT_BYTES
        || dctx.retained_len != DEALPG4_DRAIN_CAP_BYTES
                                    + DEALPG4_DRAIN_TRUNCATION_MARKER_LEN
        || !dctx.truncated || !dctx.eof) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }
    for (i = 0; i < DEALPG4_DRAIN_CAP_BYTES; i++) {
        if (dctx.retained[i] != (unsigned char)(i & 0xFF)) {
            dealpg4_battery_kill_reap_one(bound, child);
            return DEALPG4_BATTERY_FAIL;
        }
    }
    if (memcmp(dctx.retained + DEALPG4_DRAIN_CAP_BYTES,
               DEALPG4_DRAIN_TRUNCATION_MARKER,
               DEALPG4_DRAIN_TRUNCATION_MARKER_LEN) != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    /* The writer exited cleanly and is reaped. */
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    status = 0;
    rc = dealpg4_waitpid_bounded(child, &status, local, bound);
    if (rc == DEALPG4_BATTERY_BOUND) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_BOUND;
    }
    if (rc != 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        return DEALPG4_BATTERY_FAIL;
    }

    rc = dealpg4_reap_all_bounded(bound,
                                  dealpg4_now_ms()
                                      + DEALPG4_BATTERY_LOCAL_DEADLINE_MS);
    if (rc != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    return DEALPG4_BATTERY_PASS;
}


/* outer-registry-broker micro-battery (the atomic CAPS flip, ISSUE-0524 —
 * the tools/src/outer.h engine D7 battery contract, appended after
 * bounded-drain in the canonical order). Proves the kernel mechanisms
 * the outer broker depends on, in the contract's exact order: the 0700
 * mkdtemp scratch directory (removed afterward), the AF_UNIX socket
 * with the stale-same-name unlink before bind and the 0600 chmod after
 * bind, listen, one forked child that connects and performs one bounded
 * line round-trip, accept returning exactly the one connection with the
 * follow-up non-blocking accept reporting EAGAIN, the SO_PEERCRED
 * pid/uid equality on the accepted socket, close/unlink, the child
 * reaped, the scratch dir removed. Success iff every step holds, the
 * socket path is unlinked, and no survivor remains. Every wait is
 * bounded by the earlier of the battery-local deadline and the overall
 * mode bound; a failing path kill-reaps its child (no leaked process). */

/* The round-trip lines: the prober writes the server line to the
 * accepted connection; the child answers with the client line. */
#define DEALPG4_BATTERY_ORB_SERVER_LINE "DEALPG4 BROKER_PROBE_ROUNDTRIP\n"
#define DEALPG4_BATTERY_ORB_CLIENT_LINE "DEALPG4 BROKER_PROBE_ROUNDTRIP_ACK\n"

/* Bounded non-blocking socket line write (MSG_NOSIGNAL: a dead peer is
 * an error return, never process death). EAGAIN waits one bounded
 * sleep slice at a time — the wait is owned by the earlier of the
 * local deadline and the overall mode bound. */
static int dealpg4_battery_sock_write_bounded(int fd, const char *line,
                                              size_t len,
                                              uint64_t local_deadline_ms,
                                              dealpg4_battery_bound *bound)
{
    size_t off = 0;

    while (off < len) {
        ssize_t w = send(fd, line + off, len - off, MSG_NOSIGNAL);

        if (w > 0) {
            off += (size_t)w;
            continue;
        }
        if (w < 0 && errno == EINTR)
            continue;
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            int rc;

            if (dealpg4_now_ms() >= local_deadline_ms)
                return -1;
            rc = dealpg4_sleep_slice(bound);
            if (rc != 0)
                return rc == DEALPG4_BATTERY_BOUND
                           ? DEALPG4_BATTERY_BOUND
                           : -1;
            continue;
        }
        return -1;
    }
    return 0;
}

/* Failure-path cleanup: kill-reap the connecting child (when forked),
 * close every fd, unlink the socket path, and remove the scratch
 * directory — best-effort, so a failed battery never leaks a child, an
 * fd, a socket path, or the scratch dir. */
static void dealpg4_battery_orb_cleanup(dealpg4_battery_bound *bound,
                                        pid_t child, int listen_fd,
                                        int conn_fd, const char *sock_path,
                                        const char *dir_path)
{
    if (child > 0)
        dealpg4_battery_kill_reap_one(bound, child);
    if (conn_fd >= 0)
        close(conn_fd);
    if (listen_fd >= 0)
        close(listen_fd);
    if (sock_path != NULL && sock_path[0] != '\0')
        (void)unlink(sock_path);
    if (dir_path != NULL && dir_path[0] != '\0')
        (void)rmdir(dir_path);
}

static int dealpg4_battery_outer_registry_broker(dealpg4_battery_bound *bound)
{
    const char *tmpdir;
    char dir_path[PATH_MAX];
    char sock_path[PATH_MAX];
    struct sockaddr_un sun;
    struct stat sb;
    int listen_fd = -1;
    int conn_fd = -1;
    pid_t child = -1;
    uint64_t local;
    int status;
    int rc;
    int n;

    /* (1) A 0700 scratch directory under TMPDIR (mkdtemp creates mode
     * 0700; the chmod makes the mode exact regardless of umask). */
    tmpdir = getenv("TMPDIR");
    if (tmpdir == NULL || tmpdir[0] == '\0')
        tmpdir = "/tmp";
    n = snprintf(dir_path, sizeof dir_path, "%s/dealpg4-orb.XXXXXX",
                 tmpdir);
    if (n <= 0 || (size_t)n >= sizeof dir_path)
        return DEALPG4_BATTERY_FAIL;
    if (mkdtemp(dir_path) == NULL)
        return DEALPG4_BATTERY_FAIL;
    if (chmod(dir_path, 0700) != 0) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    if (stat(dir_path, &sb) != 0 || !S_ISDIR(sb.st_mode)
        || (sb.st_mode & 0777) != 0700) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }

    /* The scratch socket path (fits the sun_path cap by construction). */
    n = snprintf(sock_path, sizeof sock_path, "%s/broker.sock", dir_path);
    if (n <= 0 || (size_t)n >= sizeof sock_path
        || (size_t)n >= sizeof sun.sun_path) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }

    /* (3) Stale same-name path unlinked first (ENOENT is the normal
     * outcome of a fresh scratch dir). */
    if (unlink(sock_path) != 0 && errno != ENOENT) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }

    /* (2) socket(AF_UNIX, SOCK_STREAM), non-blocking so the follow-up
     * accept reports EAGAIN (never blocks). */
    listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    {
        int fl = fcntl(listen_fd, F_GETFL);

        if (fl == -1 || fcntl(listen_fd, F_SETFL, fl | O_NONBLOCK) == -1) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return DEALPG4_BATTERY_FAIL;
        }
    }

    memset(&sun, 0, sizeof sun);
    sun.sun_family = AF_UNIX;
    memcpy(sun.sun_path, sock_path, (size_t)n + 1);

    /* (3) bind; chmod(path, 0600) after bind, verified by stat. */
    if (bind(listen_fd, (struct sockaddr *)&sun, sizeof sun) != 0) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    if (chmod(sock_path, 0600) != 0) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    if (stat(sock_path, &sb) != 0 || !S_ISSOCK(sb.st_mode)
        || (sb.st_mode & 0777) != 0600) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return DEALPG4_BATTERY_FAIL;
    }

    /* (4) listen. */
    if (listen(listen_fd, 1) != 0) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return DEALPG4_BATTERY_FAIL;
    }

    /* (5) A forked child connects to the path and performs one bounded
     * line round-trip: it answers the prober's server line with the
     * client line (self-limited by SIGALRM). */
    child = fork();
    if (child < 0) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    if (child == 0) {
        char buf[64];
        int cfd;
        size_t got = 0;

        close(listen_fd);
        cfd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (cfd < 0)
            _exit(1);
        alarm(DEALPG4_BATTERY_ORB_ALARM_S);
        if (connect(cfd, (struct sockaddr *)&sun, sizeof sun) != 0)
            _exit(1);
        /* Read exactly the server line, then answer with the client
         * line; any deviation (EOF, error, content mismatch, or the
         * alarm) is a child failure the prober observes. */
        for (;;) {
            ssize_t r = read(cfd, buf + got,
                             sizeof(DEALPG4_BATTERY_ORB_SERVER_LINE) - 1
                                 - got);

            if (r > 0) {
                got += (size_t)r;
                if (got == sizeof(DEALPG4_BATTERY_ORB_SERVER_LINE) - 1)
                    break;
                continue;
            }
            if (r < 0 && errno == EINTR)
                continue;
            _exit(1);
        }
        if (memcmp(buf, DEALPG4_BATTERY_ORB_SERVER_LINE,
                   sizeof(DEALPG4_BATTERY_ORB_SERVER_LINE) - 1) != 0)
            _exit(1);
        if (!dealpg4_battery_write_all(
                cfd, DEALPG4_BATTERY_ORB_CLIENT_LINE,
                sizeof(DEALPG4_BATTERY_ORB_CLIENT_LINE) - 1))
            _exit(1);
        close(cfd);
        _exit(0);
    }

    /* (6) Wait for the one connection, then accept it. */
    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    {
        short revents = 0;

        rc = dealpg4_poll_wait(listen_fd, local, bound, &revents);
        if (rc != 1) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                               : DEALPG4_BATTERY_FAIL;
        }
    }
    conn_fd = accept(listen_fd, NULL, NULL);
    if (conn_fd < 0) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    {
        int fl = fcntl(conn_fd, F_GETFL);

        if (fl == -1 || fcntl(conn_fd, F_SETFL, fl | O_NONBLOCK) == -1) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return DEALPG4_BATTERY_FAIL;
        }
    }

    /* (5 continued) The prober's half of the round-trip: write the
     * server line, then read the child's client line back. */
    rc = dealpg4_battery_sock_write_bounded(
        conn_fd, DEALPG4_BATTERY_ORB_SERVER_LINE,
        sizeof(DEALPG4_BATTERY_ORB_SERVER_LINE) - 1, local, bound);
    if (rc != 0) {
        dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                    sock_path, dir_path);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    {
        char buf[64];

        memset(buf, 0, sizeof buf);
        rc = dealpg4_read_bounded(
            conn_fd, buf, sizeof(DEALPG4_BATTERY_ORB_CLIENT_LINE) - 1,
            local, bound);
        if (rc != 0) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                               : DEALPG4_BATTERY_FAIL;
        }
        if (memcmp(buf, DEALPG4_BATTERY_ORB_CLIENT_LINE,
                   sizeof(DEALPG4_BATTERY_ORB_CLIENT_LINE) - 1) != 0) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return DEALPG4_BATTERY_FAIL;
        }
    }

    /* (6 continued) No second connection: the follow-up non-blocking
     * accept must report EAGAIN. */
    {
        int extra = accept(listen_fd, NULL, NULL);

        if (extra >= 0) {
            close(extra);
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return DEALPG4_BATTERY_FAIL;
        }
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return DEALPG4_BATTERY_FAIL;
        }
    }

    /* (7) SO_PEERCRED: pid == the connecting child's pid and uid ==
     * getuid(). */
    {
        struct ucred cred;
        socklen_t cred_len = sizeof cred;

        if (getsockopt(conn_fd, SOL_SOCKET, SO_PEERCRED, &cred,
                       &cred_len) != 0
            || cred_len != sizeof cred || cred.pid != child
            || cred.uid != getuid()) {
            dealpg4_battery_orb_cleanup(bound, child, listen_fd, conn_fd,
                                        sock_path, dir_path);
            return DEALPG4_BATTERY_FAIL;
        }
    }

    /* (8) close and unlink the path; the child is reaped. */
    close(conn_fd);
    conn_fd = -1;
    close(listen_fd);
    listen_fd = -1;

    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    status = 0;
    rc = dealpg4_waitpid_bounded(child, &status, local, bound);
    if (rc == DEALPG4_BATTERY_BOUND) {
        dealpg4_battery_kill_reap_one(bound, child);
        (void)unlink(sock_path);
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_BOUND;
    }
    if (rc != 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        dealpg4_battery_kill_reap_one(bound, child);
        (void)unlink(sock_path);
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    child = -1; /* reaped */

    /* The socket path is unlinked (the stat re-check proves the
     * post-state). */
    if (unlink(sock_path) != 0) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }
    if (stat(sock_path, &sb) == 0 || errno != ENOENT) {
        (void)rmdir(dir_path);
        return DEALPG4_BATTERY_FAIL;
    }

    /* The scratch dir is removed (rmdir only succeeds on the now-empty
     * directory; the stat re-check proves the post-state). */
    if (rmdir(dir_path) != 0)
        return DEALPG4_BATTERY_FAIL;
    if (stat(dir_path, &sb) == 0 || errno != ENOENT)
        return DEALPG4_BATTERY_FAIL;

    /* No survivor remains: waitid loop to ECHILD. */
    rc = dealpg4_reap_all_bounded(bound,
                                  dealpg4_now_ms()
                                      + DEALPG4_BATTERY_LOCAL_DEADLINE_MS);
    if (rc != 0)
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    return DEALPG4_BATTERY_PASS;
}

/* === Probe entry ======================================================= */

struct dealpg4_battery_spec {
    const char *name;
    int (*run)(dealpg4_battery_bound *bound);
};

/* The probe battery table: exactly the six canonical batteries in the
 * canonical order (byte-stable report; the outer-registry-broker
 * battery appended after bounded-drain by the atomic CAPS flip,
 * ISSUE-0524). */
static const struct dealpg4_battery_spec dealpg4_probe_batteries[] = {
    { DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER,
      dealpg4_battery_monotonic_timer },
    { DEALPG4_PROBE_BATTERY_SUBREAPER, dealpg4_battery_subreaper },
    { DEALPG4_PROBE_BATTERY_PARENT_DEATH, dealpg4_battery_parent_death },
    { DEALPG4_PROBE_BATTERY_NEGATIVE_PGID, dealpg4_battery_negative_pgid },
    { DEALPG4_PROBE_BATTERY_BOUNDED_DRAIN, dealpg4_battery_bounded_drain },
    { DEALPG4_PROBE_BATTERY_OUTER_REGISTRY_BROKER,
      dealpg4_battery_outer_registry_broker }
};

_Static_assert(sizeof(dealpg4_probe_batteries)
                   / sizeof(dealpg4_probe_batteries[0]) == 6,
               "probe runs exactly the six canonical batteries");

/* The selftest battery table: an explicit additive structure. At this
 * stage it carries the six probe batteries — the same spec entries in
 * the same canonical order, so the selftest OK lines are byte-equal to
 * probe's (the outer-registry-broker battery joined with the atomic
 * CAPS flip, ISSUE-0524); the fault-injection battery (excluded,
 * ISSUE-0184) appends here and runs in the same child under the same
 * bound machinery (every future scenario is deadline-owned at the
 * earlier of its scenario budget and the remaining mode bound — the
 * run signature already carries the bound, so the machinery needs no
 * change). */
static const struct dealpg4_battery_spec dealpg4_selftest_batteries[] = {
    { DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER,
      dealpg4_battery_monotonic_timer },
    { DEALPG4_PROBE_BATTERY_SUBREAPER, dealpg4_battery_subreaper },
    { DEALPG4_PROBE_BATTERY_PARENT_DEATH, dealpg4_battery_parent_death },
    { DEALPG4_PROBE_BATTERY_NEGATIVE_PGID, dealpg4_battery_negative_pgid },
    { DEALPG4_PROBE_BATTERY_BOUNDED_DRAIN, dealpg4_battery_bounded_drain },
    { DEALPG4_PROBE_BATTERY_OUTER_REGISTRY_BROKER,
      dealpg4_battery_outer_registry_broker }
    /* ISSUE-0184 fault-injection battery appends here. */
};

_Static_assert(sizeof(dealpg4_selftest_batteries)
                   / sizeof(dealpg4_selftest_batteries[0]) >= 6,
               "selftest runs the six probe batteries plus the fault "
               "battery");

/* Outcome of a battery-list run. */
enum dealpg4_battery_list_outcome {
    DEALPG4_BATTERY_LIST_ALL_PASS = 0,
    DEALPG4_BATTERY_LIST_FAILED, /* *failed_index set */
    DEALPG4_BATTERY_LIST_BOUND_EXPIRED
};

/* Run a battery list under the mode bound, printing one "OK <name>" line
 * per passing battery (stdout, byte-stable order — the shared runner is
 * what keeps the probe and selftest OK lines byte-equal). The bound is
 * checked before every battery and after the last one; a battery whose
 * mechanics hit the bound maps to DEALPG4_BATTERY_LIST_BOUND_EXPIRED.
 * On a battery failure *failed_index holds the failing entry's index.
 * The caller maps the outcome to its mode token (PROBE_TIMEOUT or
 * SELFTEST_TIMEOUT). */
static enum dealpg4_battery_list_outcome
dealpg4_run_battery_list(dealpg4_battery_bound *bound,
                         const struct dealpg4_battery_spec *specs,
                         size_t nspecs, size_t *failed_index)
{
    size_t i;

    for (i = 0; i < nspecs; i++) {
        int rc;

        if (dealpg4_battery_bound_expired(bound))
            return DEALPG4_BATTERY_LIST_BOUND_EXPIRED;
        rc = specs[i].run(bound);
        if (rc == DEALPG4_BATTERY_PASS) {
            printf("OK %s\n", specs[i].name);
            fflush(stdout);
            continue;
        }
        if (rc == DEALPG4_BATTERY_BOUND)
            return DEALPG4_BATTERY_LIST_BOUND_EXPIRED;
        *failed_index = i;
        return DEALPG4_BATTERY_LIST_FAILED;
    }
    /* The bound must not have fired during the run. */
    if (dealpg4_battery_bound_expired(bound))
        return DEALPG4_BATTERY_LIST_BOUND_EXPIRED;
    return DEALPG4_BATTERY_LIST_ALL_PASS;
}

/* The 12-field LIMITS line from the embedded limits records, in manifest
 * field order. Printed only after the mode-entry limits validation
 * passed. */
static void dealpg4_probe_print_limits(void)
{
    const LauncherLimits *l = &dealpg4_embedded_launcher_limits;
    const OuterLimits *o = &dealpg4_embedded_outer_limits;
    const SelftestLimits *s = &dealpg4_embedded_selftest_limits;

    printf("LIMITS %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64
           " %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64 " %" PRId64
           " %" PRId64 " %" PRId64 "\n",
           l->overallTimeoutMs, l->startupTimeoutMs, l->executionCutoffMs,
           l->termGraceMs, l->killAndProofReserveMs,
           l->finalizationReserveMs, o->overallTimeoutMs,
           o->readinessTimeoutMs, o->nestedStopMs, o->cleanupReserveMs,
           o->brokerStallMs, s->selftestTimeoutMs);
}

int dealpg4_probe_entry(int64_t limit_ms)
{
    uint64_t bound_ms;
    dealpg4_battery_bound bound;
    size_t nspecs = sizeof(dealpg4_probe_batteries)
                        / sizeof(dealpg4_probe_batteries[0]);
    size_t failed_index = 0;
    enum dealpg4_battery_list_outcome outcome;

    /* Effective bound: default 15000; the dispatch-validated override
     * otherwise (the floor is enforced defensively here too). The bound
     * is a bound on the probe run only — never an invocation/outer
     * limit. */
    bound_ms = (limit_ms < DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR)
                   ? DEALPG4_PROBE_DEFAULT_LIMIT_MS
                   : (uint64_t)limit_ms;

    /* The LIMITS line prints only after the mode-entry limits validation
     * passed: dispatch ran it before delegating; the re-check makes the
     * guarantee local to the entry. */
    if (dealpg4_embedded_limits_ordering_check() != DEALPG4_LIMITS_OK) {
        fprintf(stderr, "CONFIG_INVALID\n");
        return DEALPG4_EXIT_CONFIG_INVALID;
    }

    /* One overall monotonic bound armed from probe entry with the
     * monotonic context; a broken timer capability fails before any
     * report is emitted. */
    if (dealpg4_deadline_open(&bound.ctx) != 0) {
        fprintf(stderr, "CAPABILITY_MISSING %s\n",
                DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    bound.expired = 0;
    if (dealpg4_deadline_arm_relative(&bound.ctx, bound_ms) != 0) {
        dealpg4_deadline_close(&bound.ctx);
        fprintf(stderr, "CAPABILITY_MISSING %s\n",
                DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }

    /* Three-part report, byte-stable order: identity line, LIMITS line,
     * then one OK line per passing battery. */
    printf("DEALPG4 4 linux-x86_64 CAPS %d\n", DEALPG4_PROBE_CAPS);
    dealpg4_probe_print_limits();
    fflush(stdout);

    outcome = dealpg4_run_battery_list(&bound, dealpg4_probe_batteries,
                                       nspecs, &failed_index);
    dealpg4_deadline_close(&bound.ctx);
    if (outcome == DEALPG4_BATTERY_LIST_ALL_PASS)
        return 0;
    if (outcome == DEALPG4_BATTERY_LIST_BOUND_EXPIRED) {
        fprintf(stderr, "PROBE_TIMEOUT\n");
        return DEALPG4_EXIT_PROBE_TIMEOUT;
    }
    fprintf(stderr, "CAPABILITY_MISSING %s\n",
            dealpg4_probe_batteries[failed_index].name);
    return DEALPG4_EXIT_CAPABILITY_MISSING;
}

/* === Selftest entry ==================================================== */

/* Selftest child -> entry exit-status encoding (internal to the mode;
 * the child never prints a failure token — the entry owns the single
 * token print, so stderr carries exactly one line on every failure
 * path): 0 all pass; TIMEOUT the mode bound fired; INTERNAL an internal
 * child malfunction; BATTERY_BASE + i the first failing battery's index
 * in the selftest battery table. */
#define DEALPG4_SELFTEST_CHILD_EXIT_OK 0
#define DEALPG4_SELFTEST_CHILD_EXIT_TIMEOUT \
    DEALPG4_EXIT_SELFTEST_TIMEOUT
#define DEALPG4_SELFTEST_CHILD_EXIT_INTERNAL 7
#define DEALPG4_SELFTEST_CHILD_EXIT_BATTERY_BASE 16

/* The selftest child body: own process group, then the additive battery
 * list under the inherited bound. Never returns. */
static void dealpg4_selftest_child_main(dealpg4_battery_bound *bound)
{
    size_t nspecs = sizeof(dealpg4_selftest_batteries)
                        / sizeof(dealpg4_selftest_batteries[0]);
    size_t failed_index = 0;
    enum dealpg4_battery_list_outcome outcome;

    /* Own process group: on the entry's bound-expiry kill the whole
     * battery subtree dies at once (no-survivors post-state). */
    if (setpgid(0, 0) != 0)
        _exit(DEALPG4_SELFTEST_CHILD_EXIT_INTERNAL);

    outcome = dealpg4_run_battery_list(bound, dealpg4_selftest_batteries,
                                       nspecs, &failed_index);
    fflush(stdout);
    if (outcome == DEALPG4_BATTERY_LIST_FAILED) {
        if (failed_index < nspecs)
            _exit(DEALPG4_SELFTEST_CHILD_EXIT_BATTERY_BASE
                  + (int)failed_index);
        _exit(DEALPG4_SELFTEST_CHILD_EXIT_INTERNAL);
    }
    if (outcome == DEALPG4_BATTERY_LIST_BOUND_EXPIRED)
        _exit(DEALPG4_SELFTEST_CHILD_EXIT_TIMEOUT);
    _exit(DEALPG4_SELFTEST_CHILD_EXIT_OK);
}

/* Wait for the selftest child, bounded by the mode bound. Returns 1 with
 * *status on reaping, DEALPG4_BATTERY_BOUND on bound expiry, or -1 on a
 * wait error. */
static int dealpg4_selftest_wait_child(dealpg4_battery_bound *bound,
                                       pid_t child, int *status)
{
    for (;;) {
        pid_t r = waitpid(child, status, WNOHANG);

        if (r == child)
            return 1;
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (dealpg4_battery_bound_expired(bound))
            return DEALPG4_BATTERY_BOUND;
        {
            int rc = dealpg4_sleep_slice(bound);

            if (rc == DEALPG4_BATTERY_BOUND)
                return DEALPG4_BATTERY_BOUND;
            if (rc != 0)
                return -1;
        }
    }
}

/* Bound-expiry cleanup: the child placed itself in its own process
 * group, so one group kill takes the whole battery subtree down at
 * once; the pgid guard keeps a child whose setpgid failed (still in
 * this group) from being group-killed. Then reap the direct child
 * within the cleanup grace. */
static void dealpg4_selftest_kill_reap(dealpg4_battery_bound *bound,
                                       pid_t child)
{
    pid_t pg = getpgid(child);

    if (pg > 0 && pg == child)
        (void)kill(-child, SIGKILL);
    dealpg4_battery_kill_reap(bound, &child, 1);
}

int dealpg4_selftest_entry(int64_t limit_ms)
{
    uint64_t bound_ms;
    dealpg4_battery_bound bound;
    pid_t child;
    int status;
    size_t nspecs = sizeof(dealpg4_selftest_batteries)
                        / sizeof(dealpg4_selftest_batteries[0]);

    /* Effective mode bound: the dispatch-validated --limit-ms override
     * when present, else the embedded selftestLimits.selftestTimeoutMs —
     * the same embedded constants the probe reports (bound provenance;
     * never a hardcoded default). The bound bounds the selftest run
     * only; it is never an invocation/outer limit and changes no
     * embedded constant, probe report, or manifest. */
    bound_ms = (limit_ms >= DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR)
                   ? (uint64_t)limit_ms
                   : (uint64_t)dealpg4_embedded_selftest_limits
                         .selftestTimeoutMs;

    /* Mode-entry limits re-check (dispatch ran it before delegating; the
     * local guarantee mirrors the probe entry — the bound is armed only
     * past a valid embedded configuration). */
    if (dealpg4_embedded_limits_ordering_check() != DEALPG4_LIMITS_OK) {
        fprintf(stderr, "CONFIG_INVALID\n");
        return DEALPG4_EXIT_CONFIG_INVALID;
    }

    /* One mode-level monotonic bound armed from selftest entry with the
     * monotonic context — the same timerfd machinery as every other
     * deadline. A broken timer capability fails before any battery
     * runs. */
    if (dealpg4_deadline_open(&bound.ctx) != 0) {
        fprintf(stderr, "CAPABILITY_MISSING %s\n",
                DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    bound.expired = 0;
    if (dealpg4_deadline_arm_relative(&bound.ctx, bound_ms) != 0) {
        dealpg4_deadline_close(&bound.ctx);
        fprintf(stderr, "CAPABILITY_MISSING %s\n",
                DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }

    /* One selftest child inherits the bound context (the timerfd is
     * shared across the fork, so parent and child observe the same
     * absolute deadline) and runs the additive battery list. */
    child = fork();
    if (child < 0) {
        dealpg4_deadline_close(&bound.ctx);
        fprintf(stderr, "CAPABILITY_MISSING selftest-child\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    if (child == 0) {
        dealpg4_selftest_child_main(&bound);
        _exit(DEALPG4_SELFTEST_CHILD_EXIT_INTERNAL); /* unreachable */
    }

    /* The entry waits for the child under the same bound and owns the
     * single failure-token print. */
    {
        int rc = dealpg4_selftest_wait_child(&bound, child, &status);

        if (rc == DEALPG4_BATTERY_BOUND) {
            dealpg4_selftest_kill_reap(&bound, child);
            dealpg4_deadline_close(&bound.ctx);
            fprintf(stderr, "SELFTEST_TIMEOUT\n");
            return DEALPG4_EXIT_SELFTEST_TIMEOUT;
        }
        if (rc != 1) {
            dealpg4_selftest_kill_reap(&bound, child);
            dealpg4_deadline_close(&bound.ctx);
            fprintf(stderr, "CAPABILITY_MISSING selftest-child\n");
            return DEALPG4_EXIT_CAPABILITY_MISSING;
        }
    }

    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);

        if (code == DEALPG4_SELFTEST_CHILD_EXIT_OK) {
            /* Success requires the bound not to have fired: the child
             * passed its own final check; the entry re-checks so a bound
             * firing in the reap window cannot pass the mode. */
            if (dealpg4_battery_bound_expired(&bound)) {
                dealpg4_deadline_close(&bound.ctx);
                fprintf(stderr, "SELFTEST_TIMEOUT\n");
                return DEALPG4_EXIT_SELFTEST_TIMEOUT;
            }
            dealpg4_deadline_close(&bound.ctx);
            return 0;
        }
        dealpg4_deadline_close(&bound.ctx);
        if (code == DEALPG4_SELFTEST_CHILD_EXIT_TIMEOUT) {
            fprintf(stderr, "SELFTEST_TIMEOUT\n");
            return DEALPG4_EXIT_SELFTEST_TIMEOUT;
        }
        if (code >= DEALPG4_SELFTEST_CHILD_EXIT_BATTERY_BASE
            && (size_t)(code - DEALPG4_SELFTEST_CHILD_EXIT_BATTERY_BASE)
                   < nspecs) {
            fprintf(stderr, "CAPABILITY_MISSING %s\n",
                    dealpg4_selftest_batteries[code
                        - DEALPG4_SELFTEST_CHILD_EXIT_BATTERY_BASE].name);
            return DEALPG4_EXIT_CAPABILITY_MISSING;
        }
        /* Defensive: an unexpected child report (internal malfunction)
         * is still a named-token failure with no retry. */
        fprintf(stderr, "CAPABILITY_MISSING selftest-child\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }

    /* Defensive: the child died by an external signal (the entry's own
     * bound-expiry kill returns above with the timeout token). */
    dealpg4_deadline_close(&bound.ctx);
    fprintf(stderr, "CAPABILITY_MISSING selftest-child\n");
    return DEALPG4_EXIT_CAPABILITY_MISSING;
}

/* === Fault-injection override installer (ISSUE-0205) ===================
 * The selftest-child-only, in-process installer for scripted overrides
 * on the fi hook table (fi.h). Contract in selftest.h; the fault battery
 * (ISSUE-0184) calls it from the selftest child before scenario
 * execution. The committed binary's mode paths never reach it: dispatch
 * and the probe/selftest entries above do not call the installer, so the
 * shipped artifact has no injection activation surface.
 *
 * State is the bounded per-hook-kind script storage (entries plus fired
 * flags) and the swapped hook-table entries. An install is all-or-
 * nothing: the whole script validates against the catalog first, and
 * only then is the state populated and the table swapped — a failed
 * install injects nothing and leaves any previously installed script
 * untouched.
 */

/* Script storage: bounded copies of the validated entries plus the
 * single-shot fired flags. Static (zero-initialized); gated by the
 * entry counts, so the hooks behave as production defaults whenever no
 * script is installed. */
static dealpg4_fi_script_delay
    dealpg4_fi_script_delays[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static unsigned char
    dealpg4_fi_script_delay_fired[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static size_t dealpg4_fi_script_ndelays;

static dealpg4_fi_script_fail
    dealpg4_fi_script_fails[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static unsigned char
    dealpg4_fi_script_fail_fired[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static size_t dealpg4_fi_script_nfails;

static dealpg4_fi_script_congest
    dealpg4_fi_script_congests[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static unsigned char
    dealpg4_fi_script_congest_fired[DEALPG4_FI_SCRIPT_MAX_ENTRIES];
static size_t dealpg4_fi_script_ncongests;

/* Script hooks. Entries are scanned in script order; the first entry
 * whose tags match and whose single shot has not yet fired decides the
 * call (deterministic, explicit — no randomness). Anything unmatched
 * falls through to the production behavior. */

static int dealpg4_fi_script_delay_hook(unsigned ms, const char *site)
{
    size_t i;

    if (site != NULL) {
        for (i = 0; i < dealpg4_fi_script_ndelays; i++) {
            const dealpg4_fi_script_delay *e =
                &dealpg4_fi_script_delays[i];

            if (strcmp(e->site, site) != 0)
                continue;
            if (e->oneshot && dealpg4_fi_script_delay_fired[i])
                continue; /* single shot spent: next entry / production */
            dealpg4_fi_script_delay_fired[i] = 1;
            /* The exact scripted duration replaces the requested ms and
             * is slept with the real monotonic-bounded default sleep —
             * the enclosing component's own deadline is untouched, so
             * the injection consumes it and never extends it. */
            return dealpg4_fi_default_delay_ms(e->duration_ms, site);
        }
    }
    return dealpg4_fi_default_delay_ms(ms, site);
}

static int dealpg4_fi_script_fail_hook(int site)
{
    size_t i;

    for (i = 0; i < dealpg4_fi_script_nfails; i++) {
        const dealpg4_fi_script_fail *e = &dealpg4_fi_script_fails[i];

        if (e->site != site)
            continue;
        if (e->oneshot && dealpg4_fi_script_fail_fired[i])
            continue;
        dealpg4_fi_script_fail_fired[i] = 1;
        return e->value; /* the exact scripted failure value */
    }
    return 0; /* production default: no failure */
}

static int dealpg4_fi_script_congest_hook(int target, int mode, void *arg)
{
    size_t i;

    (void)arg; /* call-site-specific data lands with the call-site
                * children; the scripted report is the mode tag itself */
    for (i = 0; i < dealpg4_fi_script_ncongests; i++) {
        const dealpg4_fi_script_congest *e =
            &dealpg4_fi_script_congests[i];

        if (e->target != target || e->mode != mode)
            continue;
        if (e->oneshot && dealpg4_fi_script_congest_fired[i])
            continue;
        dealpg4_fi_script_congest_fired[i] = 1;
        return e->mode; /* reports exactly the scripted mode */
    }
    return 0; /* production default: no congestion */
}

/* Catalog lookups (named string/int tags; the catalogs are extended by
 * the children that place call sites). */
static int dealpg4_fi_catalog_has_str(const char *const *tags, size_t ntags,
                                      const char *tag)
{
    size_t i;

    if (tags == NULL || tag == NULL)
        return 0;
    for (i = 0; i < ntags; i++) {
        if (tags[i] != NULL && strcmp(tags[i], tag) == 0)
            return 1;
    }
    return 0;
}

static int dealpg4_fi_catalog_has_int(const int *tags, size_t ntags,
                                      int tag)
{
    size_t i;

    if (tags == NULL)
        return 0;
    for (i = 0; i < ntags; i++) {
        if (tags[i] == tag)
            return 1;
    }
    return 0;
}

int dealpg4_fi_install_overrides(const dealpg4_fi_script *script,
                                 const dealpg4_fi_catalog *catalog)
{
    size_t i;

    if (script == NULL || catalog == NULL)
        return -1;
    if (script->ndelays > DEALPG4_FI_SCRIPT_MAX_ENTRIES ||
        script->nfails > DEALPG4_FI_SCRIPT_MAX_ENTRIES ||
        script->ncongests > DEALPG4_FI_SCRIPT_MAX_ENTRIES)
        return -1;
    if (script->ndelays > 0 && script->delays == NULL)
        return -1;
    if (script->nfails > 0 && script->fails == NULL)
        return -1;
    if (script->ncongests > 0 && script->congests == NULL)
        return -1;

    /* Validate the whole script against the named catalogs before
     * touching any state: hook misuse (an unknown site/target/mode tag,
     * a NULL site string, a reserved congestion mode 0, or a oneshot
     * flag outside {0,1}) is an installer error and injects nothing. */
    for (i = 0; i < script->ndelays; i++) {
        const dealpg4_fi_script_delay *e = &script->delays[i];

        if (e->site == NULL)
            return -1;
        if (!dealpg4_fi_catalog_has_str(catalog->delay_sites,
                                        catalog->ndelay_sites, e->site))
            return -1; /* unknown site: never inject silently */
        if (e->oneshot != 0 && e->oneshot != 1)
            return -1;
    }
    for (i = 0; i < script->nfails; i++) {
        const dealpg4_fi_script_fail *e = &script->fails[i];

        if (!dealpg4_fi_catalog_has_int(catalog->fail_sites,
                                        catalog->nfail_sites, e->site))
            return -1;
        if (e->oneshot != 0 && e->oneshot != 1)
            return -1;
    }
    for (i = 0; i < script->ncongests; i++) {
        const dealpg4_fi_script_congest *e = &script->congests[i];

        if (e->mode == 0)
            return -1; /* 0 is the production no-congestion report */
        if (!dealpg4_fi_catalog_has_int(catalog->targets,
                                        catalog->ntargets, e->target))
            return -1;
        if (!dealpg4_fi_catalog_has_int(catalog->modes, catalog->nmodes,
                                        e->mode))
            return -1;
        if (e->oneshot != 0 && e->oneshot != 1)
            return -1;
    }

    /* Validated: populate the bounded script state, reset the fired
     * flags, and swap the hook table entries — all three hooks together,
     * deterministically. */
    for (i = 0; i < script->ndelays; i++) {
        dealpg4_fi_script_delays[i] = script->delays[i];
        dealpg4_fi_script_delay_fired[i] = 0;
    }
    dealpg4_fi_script_ndelays = script->ndelays;
    for (i = 0; i < script->nfails; i++) {
        dealpg4_fi_script_fails[i] = script->fails[i];
        dealpg4_fi_script_fail_fired[i] = 0;
    }
    dealpg4_fi_script_nfails = script->nfails;
    for (i = 0; i < script->ncongests; i++) {
        dealpg4_fi_script_congests[i] = script->congests[i];
        dealpg4_fi_script_congest_fired[i] = 0;
    }
    dealpg4_fi_script_ncongests = script->ncongests;

    dealpg4_fi_hooks.delay_ms = dealpg4_fi_script_delay_hook;
    dealpg4_fi_hooks.fail = dealpg4_fi_script_fail_hook;
    dealpg4_fi_hooks.congest = dealpg4_fi_script_congest_hook;
    return 0;
}

void dealpg4_fi_restore_defaults(void)
{
    dealpg4_fi_hooks.delay_ms = dealpg4_fi_default_delay_ms;
    dealpg4_fi_hooks.fail = dealpg4_fi_default_fail;
    dealpg4_fi_hooks.congest = dealpg4_fi_default_congest;
    dealpg4_fi_script_ndelays = 0;
    dealpg4_fi_script_nfails = 0;
    dealpg4_fi_script_ncongests = 0;
}
