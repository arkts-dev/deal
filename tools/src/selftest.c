/*
 * DEALPG4 probe mode and the five micro-batteries.
 *
 * Owns (dealpg4-probe-selftest-foundation D1-D3, probe mode contract;
 * dealpg4-time-stream-utilities battery mechanics):
 *  - the byte-stable three-part probe report: exactly one identity line
 *    "DEALPG4 4 linux-x86_64 CAPS 31" (every advertised capability bit
 *    is backed by a passing battery in this artifact), exactly one
 *    12-field LIMITS line printed from the embedded limits records (only
 *    after the mode-entry limits validation passed), and one
 *    "OK <battery>" line per passing battery in the canonical order
 *    monotonic-timer, subreaper, parent-death, negative-pgid,
 *    bounded-drain; exit 0 iff every battery passes — no skip, no retry;
 *  - the five kernel-mechanism batteries, each running in fresh forked
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
 *    subreaper) and is reaped (waitid loop to ECHILD); no live
 *    descendants, no zombie.
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
#include <string.h>
#include <sys/prctl.h>
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

/* Stage CAPS: exactly the five battery-backed bits (1|2|4|8|16). */
_Static_assert(DEALPG4_PROBE_CAPS == 31,
               "stage CAPS 31: bits 1|2|4|8|16, every bit battery-backed");

/* === Battery mechanics constants ======================================= */

/* monotonic-timer probe delta: t0 + 250 ms absolute deadline
 * (architect-decided). */
#define DEALPG4_BATTERY_TIMER_DELTA_MS 250u

/* Battery-local deadlines: every blocking point is bounded by the earlier
 * of this local deadline and the overall probe bound; battery mechanics
 * use their own short absolute deadlines. */
#define DEALPG4_BATTERY_LOCAL_DEADLINE_MS 5000u

/* Cleanup grace: on a failure path, reap_all waits at most this long for
 * stragglers (each battery's children are self-bounded). */
#define DEALPG4_BATTERY_CLEANUP_GRACE_MS 10000u

/* Grandchild reparent-wait: 1 ms sleeps, bounded so a stuck reparent can
 * never hang the probe. */
#define DEALPG4_BATTERY_REPARENT_WAIT_ITERS 2000

/* Pure-sleep slice: fd < 0 polls sleep in short slices so child-state
 * changes are re-checked promptly (a SIGCHLD can be delivered between the
 * WNOHANG reap check and the sleep; a full-deadline sleep would miss it
 * and fail the battery on a race). */
#define DEALPG4_BATTERY_SLEEP_SLICE_MS 10

/* negative-pgid child self-limit: if the group SIGTERM never arrives, the
 * child dies by SIGALRM so the battery observes the failure instead of
 * hanging. */
#define DEALPG4_BATTERY_PGID_ALARM_S 2u

/* bounded-drain overshoot past the cap (architect-decided; any overshoot
 * exercises the cut point): 1048576 + 4096 bytes. */
#define DEALPG4_DRAIN_BATTERY_OVERSHOOT_BYTES 4096

/* === Battery result and probe bound ==================================== */

enum dealpg4_battery_result {
    DEALPG4_BATTERY_PASS = 0,  /* full success criterion holds */
    DEALPG4_BATTERY_FAIL = -1, /* capability failure -> CAPABILITY_MISSING */
    DEALPG4_BATTERY_BOUND = -2 /* overall probe bound expired -> PROBE_TIMEOUT */
};

/* The overall probe bound: one monotonic context armed from probe entry,
 * plus the sticky expired flag (a drained timerfd is idle, so the expiry
 * observation must persist for every later check). */
typedef struct dealpg4_probe_bound {
    dealpg4_deadline_ctx ctx;
    int expired;
} dealpg4_probe_bound;

/* 1 once the overall bound has fired (expiry drained from the timerfd;
 * the observation is sticky). A timerfd read error fails closed as
 * expired. */
static int dealpg4_probe_bound_expired(dealpg4_probe_bound *bound)
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

/* Sleep one short slice, bounded by the overall probe bound. Returns 0
 * after the slice, DEALPG4_BATTERY_BOUND when the overall bound expired,
 * or -1 on poll error. The caller owns the local deadline and re-checks
 * child state after every slice (a SIGCHLD can already have been
 * delivered before the sleep began; a full-deadline sleep would miss the
 * state change and fail the battery on a race). */
static int dealpg4_sleep_slice(dealpg4_probe_bound *bound)
{
    struct pollfd pfd;

    if (dealpg4_probe_bound_expired(bound))
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
                             dealpg4_probe_bound *bound, short *revents)
{
    for (;;) {
        struct pollfd pfd;
        uint64_t now, remain, bound_remain;
        int timeout, rc;

        if (dealpg4_probe_bound_expired(bound))
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
                                dealpg4_probe_bound *bound)
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
                                   dealpg4_probe_bound *bound)
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
static int dealpg4_reap_all_bounded(dealpg4_probe_bound *bound,
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
static void dealpg4_battery_cleanup_until(dealpg4_probe_bound *bound,
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
 * probe bound cannot skip the reaping of children we just killed; the
 * grace deadline still bounds the whole cleanup. */
static void dealpg4_battery_kill_reap(dealpg4_probe_bound *bound,
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
static void dealpg4_battery_kill_reap_one(dealpg4_probe_bound *bound,
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

/* === The five micro-batteries ========================================== */

static int dealpg4_battery_monotonic_timer(dealpg4_probe_bound *bound)
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

        if (dealpg4_probe_bound_expired(bound)) {
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

static int dealpg4_battery_subreaper(dealpg4_probe_bound *bound)
{
    int pipefd[2];
    pid_t child, gpid, new_ppid;
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
        /* child: spawn the grandchild and exit at once; the grandchild
         * then reparents to the prober (the nearest living subreaper). */
        pid_t g = fork();

        if (g < 0)
            _exit(1);
        if (g == 0) {
            /* grandchild: report its own pid (readiness), observe the
             * reparenting (bounded), report the new parent, exit. */
            pid_t me = getpid();
            pid_t before = getppid();
            int tries = 0;

            if (write(pipefd[1], &me, sizeof(me)) != (ssize_t)sizeof(me))
                _exit(1);
            while (getppid() == before
                   && tries < DEALPG4_BATTERY_REPARENT_WAIT_ITERS) {
                const struct timespec ts = { 0, 1000000 };

                nanosleep(&ts, NULL);
                tries++;
            }
            me = getppid();
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
        /* The grandchild's pid is not yet known; killing the child kills
         * its parent — the grandchild then dies on its own (bounded
         * reparent loop, or SIGPIPE on its pipe write after this close). */
        dealpg4_battery_kill_reap_one(bound, child);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }

    /* Reap the child; its exit is what reparented the grandchild. */
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

    local = dealpg4_now_ms() + DEALPG4_BATTERY_LOCAL_DEADLINE_MS;
    rc = dealpg4_read_bounded(pipefd[0], &new_ppid, sizeof(new_ppid), local,
                              bound);
    if (rc != 0) {
        close(pipefd[0]);
        dealpg4_battery_kill_reap_one(bound, gpid);
        return rc == DEALPG4_BATTERY_BOUND ? DEALPG4_BATTERY_BOUND
                                           : DEALPG4_BATTERY_FAIL;
    }
    close(pipefd[0]);

    /* The grandchild's parent must have become the prober. */
    if (new_ppid != getpid()) {
        dealpg4_battery_kill_reap_one(bound, gpid);
        return DEALPG4_BATTERY_FAIL;
    }

    /* Reap the adopted grandchild: waitid(P_PID) succeeds only for the
     * prober's own children, so this proves the adoption. */
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

static int dealpg4_battery_parent_death(dealpg4_probe_bound *bound)
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

static int dealpg4_battery_negative_pgid(dealpg4_probe_bound *bound)
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

static int dealpg4_battery_bounded_drain(dealpg4_probe_bound *bound)
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
         * the overall probe bound. */
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

/* === Probe entry ======================================================= */

static const struct dealpg4_probe_battery {
    const char *name;
    int (*run)(dealpg4_probe_bound *bound);
} dealpg4_probe_batteries[] = {
    { DEALPG4_PROBE_BATTERY_MONOTONIC_TIMER,
      dealpg4_battery_monotonic_timer },
    { DEALPG4_PROBE_BATTERY_SUBREAPER, dealpg4_battery_subreaper },
    { DEALPG4_PROBE_BATTERY_PARENT_DEATH, dealpg4_battery_parent_death },
    { DEALPG4_PROBE_BATTERY_NEGATIVE_PGID, dealpg4_battery_negative_pgid },
    { DEALPG4_PROBE_BATTERY_BOUNDED_DRAIN, dealpg4_battery_bounded_drain }
};

_Static_assert(sizeof(dealpg4_probe_batteries)
                   / sizeof(dealpg4_probe_batteries[0]) == 5,
               "probe runs exactly the five canonical batteries");

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
    dealpg4_probe_bound bound;
    size_t i;

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

    for (i = 0; i < sizeof(dealpg4_probe_batteries)
                        / sizeof(dealpg4_probe_batteries[0]); i++) {
        int rc;

        if (dealpg4_probe_bound_expired(&bound)) {
            dealpg4_deadline_close(&bound.ctx);
            fprintf(stderr, "PROBE_TIMEOUT\n");
            return DEALPG4_EXIT_PROBE_TIMEOUT;
        }
        rc = dealpg4_probe_batteries[i].run(&bound);
        if (rc == DEALPG4_BATTERY_PASS) {
            printf("OK %s\n", dealpg4_probe_batteries[i].name);
            fflush(stdout);
            continue;
        }
        dealpg4_deadline_close(&bound.ctx);
        if (rc == DEALPG4_BATTERY_BOUND) {
            fprintf(stderr, "PROBE_TIMEOUT\n");
            return DEALPG4_EXIT_PROBE_TIMEOUT;
        }
        fprintf(stderr, "CAPABILITY_MISSING %s\n",
                dealpg4_probe_batteries[i].name);
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }

    /* The bound must not have fired during the run. */
    if (dealpg4_probe_bound_expired(&bound)) {
        dealpg4_deadline_close(&bound.ctx);
        fprintf(stderr, "PROBE_TIMEOUT\n");
        return DEALPG4_EXIT_PROBE_TIMEOUT;
    }
    dealpg4_deadline_close(&bound.ctx);
    return 0;
}

/* === Selftest entry (stage placeholder) ================================ */

int dealpg4_selftest_entry(int64_t limit_ms)
{
    /* Stage placeholder: the selftest bound machinery (embedded
     * selftestLimits.selftestTimeoutMs default, --limit-ms override) and
     * the fault-injection battery slot land in the selftest child
     * (ISSUE-0184). No fork, no exec, no channel. MODE_NOT_IMPLEMENTED is
     * a stage placeholder, not an integrity token. */
    (void)limit_ms;
    fprintf(stderr, "MODE_NOT_IMPLEMENTED\n");
    return 1;
}
