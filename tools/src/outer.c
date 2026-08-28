/*
 * DEALPG4 outer feature supervisor (ISSUE-0293, epic Sequencing step 1):
 * the outer mode entry surface, the in-process outer core entry, the D1
 * entry preamble, the ppoll loop skeleton with the non-blocking
 * write-side discipline, the nested-spawn seam, and the exit-status
 * mapping.
 *
 * See outer.h for the pinned surfaces (dealpg4-outer-supervisor-engine
 * D1/D2; outer-coordinator-and-broker D1-D9 preserved). This child
 * replaces the stage placeholder:
 *  - the mode entry validates the pinned argv shape and binds the
 *    embedded-limits/socket-dir/report-fd/spawn surface;
 *  - the core owns the entry preamble (setsid, ignore SIGHUP/SIGPIPE,
 *    subreaper set + read-back, shellPid, T0o, the total-deadline
 *    timerfd, signalfd(SIGCHLD), the outerNonce), the ppoll loop
 *    skeleton (the single timerfd, never blocked on a write), the
 *    write-side discipline (bounded per-record queues flushed on
 *    POLLOUT, overflow dropped with the truncation flag), the final
 *    report plumbing, and the exit-status mapping;
 *  - the four entry-level fault-injection fail sites
 *    (FI_OUTER_SUBREAPER / FI_OUTER_TIMERFD / FI_OUTER_SIGNALFD /
 *    FI_OUTER_NONCE) land with this child.
 *
 * Stage invariants: no fork, no socket, no channel, no record machinery
 * anywhere in this file except the production fork_nested seam (which
 * is invoked only by the registration machinery of a later sequencing
 * step); the coordinator fork/bootstrap, broker socket, registry,
 * nested forks, fallbacks, escalation, and final proof land with the
 * next sequencing steps. At this stage the core runs the preamble, the
 * ppoll loop to the total deadline (the only deadline with an
 * applicable action), and then terminates with the gate-failure token
 * OVERALL_TIMEOUT — the outer deadline is a hard gate failure (D1).
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include "outer.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/uio.h>
#include <unistd.h>

#include "fi.h"
#include "protocol.h"
#include "selftest.h"

/* === Local surface constants ============================================ */

/* Dispatch's usage exit status (launcher-main.c owns the same value as
 * DEALPG4_EXIT_USAGE); the outer entry shares it for its usage errors. */
#define DEALPG4_EXIT_USAGE 2

/* The literal "--" argv separator of the pinned outer shape (D1). */
#define DEALPG4_ARGV_SEPARATOR "--"

/* The mode-entry socket-dir pin (D1/D2): the broker socket path suffix
 * lands with the broker child. */
#define DEALPG4_OUTER_SOCKET_DIR "build"

/* Environment keys of the pinned nested serve surface (D3): nonce,
 * budget, and invocation id all delivered child-side at the nested
 * fork (the same key names the supervisor's serve entry validates). */
#define DEALPG4_ENV_BUDGET_MS      "DEALPG4_BUDGET_MS"
#define DEALPG4_ENV_NONCE          "DEALPG4_NONCE"
#define DEALPG4_ENV_INVOCATION_ID  "DEALPG4_INVOCATION_ID"

/* The ppoll fd budget: timerfd + signalfd + every POLLOUT side of the
 * registered write queues (broker + up to 128 nested control channels
 * + report) plus the future POLLIN fds of the next sequencing steps. */
#define DEALPG4_OUTER_POLLFD_MAX 160

/* Write-queue hop registry bound (broker + report + 128 records). */
#define DEALPG4_OUTER_MAX_WRITEQ_HOPS 130

/* The final report arena (bounded: header + token lines + per-record
 * lines; per-record lines join with the registry child). */
#define DEALPG4_OUTER_REPORT_ARENA_BYTES 131072

/* === Core state ========================================================= */

typedef struct dealpg4_outer_state {
    const OuterLimits *limits;
    char coordinator_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    char *const *coordinator_argv; /* argv[0] = the program */
    const char *socket_dir;
    int report_fd;
    const dealpg4_outer_spawn *spawn;

    /* Preamble facts (D1). */
    pid_t shell_pid;
    int64_t t0o;
    int64_t total_deadline;
    dealpg4_outer_deadlines dl;
    char outer_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int setsid_ok;
    int subreaper_ok;
    int timerfd_ok;
    int signalfd_ok;

    /* Event-loop machinery. */
    dealpg4_deadline_ctx timer;
    int sig_fd;
    sigset_t entry_mask; /* the process mask observed at core entry,
                            restored before every exit */
    uint64_t sigchld_events;
    int loop_entered;
    int readiness_fired;
    int cutoff_fired;
    int total_fired;

    /* Write-side discipline: the POLLOUT hop registry (empty at this
     * stage; the broker/nested-channel/report queues register with the
     * later children — the final report flushes its own queue at the
     * end). */
    dealpg4_outer_writeq *writeqs[DEALPG4_OUTER_MAX_WRITEQ_HOPS];
    size_t nwriteqs;

    /* Report-fd flag discipline (D1). */
    int report_flags;             /* captured F_GETFL, -1 when the
                                     capture failed */
    int report_flags_captured;
    int report_flags_restored;

    /* Gate-failure tokens (D1 exit-status mapping). */
    int gate_failure;
    size_t ntokens;
    char tokens[DEALPG4_OUTER_MAX_TOKENS][DEALPG4_OUTER_TOKEN_BYTES];
} dealpg4_outer_state;

/* In-process observability (outer.h contract). */
static dealpg4_outer_result dealpg4_outer_last_result_view;

void dealpg4_outer_last_result(dealpg4_outer_result *out)
{
    if (out != NULL)
        *out = dealpg4_outer_last_result_view;
}

/* === Entry validation helpers =========================================== */

/* Nonce class: exactly DEALPG4_NONCE_HEX_CHARS (32) lowercase hex
 * characters followed by NUL (outer-coordinator-and-broker D5). */
static int dealpg4_nonce_is_valid(const char *s)
{
    size_t i;

    if (s == NULL)
        return 0;
    for (i = 0; i < DEALPG4_NONCE_HEX_CHARS; i++) {
        char c = s[i];

        if (c == '\0')
            return 0;
        if ((c < '0' || c > '9') && (c < 'a' || c > 'f'))
            return 0;
    }
    return s[DEALPG4_NONCE_HEX_CHARS] == '\0';
}

/* Core-entry limits validation (D2): the outer ordering invariant
 * nestedStopMs + cleanupReserveMs <= overallTimeoutMs plus the four
 * >= 1 minimums (readinessTimeoutMs, nestedStopMs, cleanupReserveMs,
 * brokerStallMs). The subtraction form keeps the sum overflow-free. */
static int dealpg4_outer_limits_valid(const OuterLimits *limits)
{
    if (limits == NULL)
        return 0;
    if (limits->nestedStopMs > limits->overallTimeoutMs
                                 - limits->cleanupReserveMs)
        return 0;
    if (limits->readinessTimeoutMs < 1)
        return 0;
    if (limits->nestedStopMs < 1)
        return 0;
    if (limits->cleanupReserveMs < 1)
        return 0;
    if (limits->brokerStallMs < 1)
        return 0;
    return 1;
}

/* Outer-mode usage message (D1): usage errors print it on stderr and
 * exit 2, no fork, no socket, no channel. The program path matches
 * dispatch's usage text. */
static void dealpg4_outer_print_usage(void)
{
    fprintf(stderr,
            "usage: tools/deal-process-launcher-linux-x86_64 outer "
            "<coordinatorNonce> -- <coordinator-argv...>\n"
            "<coordinatorNonce> = exactly 32 lowercase hex characters\n");
}

/* === Gate-failure tokens (D1 exit-status mapping) ======================= */

static void dealpg4_outer_gate_token(dealpg4_outer_state *st,
                                     const char *token)
{
    size_t i;

    if (token == NULL || token[0] == '\0')
        return;
    st->gate_failure = 1;
    for (i = 0; i < st->ntokens; i++) {
        if (strcmp(st->tokens[i], token) == 0)
            return; /* already recorded */
    }
    if (st->ntokens >= DEALPG4_OUTER_MAX_TOKENS)
        return;
    snprintf(st->tokens[st->ntokens], DEALPG4_OUTER_TOKEN_BYTES, "%s",
             token);
    st->tokens[st->ntokens][DEALPG4_OUTER_TOKEN_BYTES - 1] = '\0';
    st->ntokens++;
}

/* === Exit-status mapping (D1) =========================================== */

int dealpg4_outer_map_exit(const dealpg4_outer_outcome *outcome)
{
    if (outcome == NULL)
        return DEALPG4_OUTER_EXIT_GATE_FAILURE;
    if (outcome->gate_failure)
        return DEALPG4_OUTER_EXIT_GATE_FAILURE;
    /* The clean-exit discrimination (parent D8): a coordinator reaped
     * with status 0 while no live records remain is the clean exit;
     * the gate follows the record outcomes. Every other fact — a
     * coordinator reaped with nonzero status or killed, exit 0 with
     * live records, any FAILED record, a proof that did not pass — is
     * a gate failure. */
    if (!outcome->coordinator_reaped || !outcome->coordinator_exited_0)
        return DEALPG4_OUTER_EXIT_GATE_FAILURE;
    if (outcome->records_live > 0 || outcome->records_failed > 0)
        return DEALPG4_OUTER_EXIT_GATE_FAILURE;
    if (!outcome->proof_passed)
        return DEALPG4_OUTER_EXIT_GATE_FAILURE;
    return 0;
}

/* === Write-side discipline (D1/D5) ====================================== */

void dealpg4_outer_writeq_init(dealpg4_outer_writeq *q, int fd,
                               unsigned char *arena, size_t arena_bytes)
{
    memset(q, 0, sizeof *q);
    q->fd = fd;
    q->arena = arena;
    q->arena_bytes = arena_bytes;
}

void dealpg4_outer_writeq_clear(dealpg4_outer_writeq *q)
{
    q->head = 0;
    q->tail = 0;
    q->n_items = 0;
    q->n_bytes = 0;
    q->payload[0] = 0;
    q->payload[1] = 0;
    q->free_start = 0;
    /* The overflow consequence (flag, dropped count) survives: it is
     * stream-truncation evidence for the report, not queue state. */
}

int dealpg4_outer_writeq_empty(const dealpg4_outer_writeq *q)
{
    return q->n_items == 0;
}

size_t dealpg4_outer_writeq_queued_bytes(const dealpg4_outer_writeq *q)
{
    return q->n_bytes;
}

size_t dealpg4_outer_writeq_payload_bytes(const dealpg4_outer_writeq *q)
{
    return q->payload[0] + q->payload[1];
}

int dealpg4_outer_writeq_overflow(const dealpg4_outer_writeq *q)
{
    return q->overflow;
}

size_t dealpg4_outer_writeq_dropped_bytes(const dealpg4_outer_writeq *q)
{
    return q->dropped_bytes;
}

/* Append one queued record line (possibly wrapped across the arena end
 * as two fragments). Structural-only refusal: item-slot or arena-space
 * exhaustion is a caller defect. */
static int dealpg4_outer_writeq_append(dealpg4_outer_writeq *q,
                                       const char *data, size_t len,
                                       int kind, int stream)
{
    dealpg4_outer_writeq_item *item;
    size_t start0;
    size_t len0;
    size_t len1;

    if (q->n_items >= DEALPG4_OUTER_WRITEQ_MAX_ITEMS)
        return -1;
    if (len > q->arena_bytes - q->n_bytes)
        return -1;
    item = &q->items[q->tail];
    start0 = q->free_start;
    if (start0 + len <= q->arena_bytes) {
        len0 = len;
        len1 = 0;
    } else {
        len0 = q->arena_bytes - start0;
        len1 = len - len0;
    }
    memcpy(q->arena + start0, data, len0);
    if (len1 > 0)
        memcpy(q->arena, data + len0, len1);
    item->start = start0;
    item->frag_len = len0;
    item->tail_len = len1;
    item->off = 0;
    item->kind = kind;
    item->stream = kind == 0 ? stream : -1;
    q->tail = (q->tail + 1) % DEALPG4_OUTER_WRITEQ_MAX_ITEMS;
    q->n_items++;
    q->n_bytes += len;
    if (kind == 0)
        q->payload[stream] += len;
    q->free_start = (start0 + len) % q->arena_bytes;
    return 0;
}

int dealpg4_outer_writeq_queue(dealpg4_outer_writeq *q, const char *data,
                               size_t len, int kind, int stream)
{
    if (q == NULL || data == NULL || q->arena == NULL)
        return -1;
    if (len == 0 || data[len - 1] != '\n')
        return -1; /* a serialized record line always ends in LF */
    if (kind == 0) {
        if (stream != 0 && stream != 1)
            return -1; /* payload streams are 0 (stdout) / 1 (stderr) */
        if (q->payload[stream] + len > DEALPG4_RELAY_QUEUE_CAP_BYTES) {
            /* Per-stream cap reached: the payload is dropped
             * immediately with the truncation consequence (the
             * write-side contract's overflow rule). Control records
             * (kind 1) never take this path. */
            q->overflow = 1;
            q->dropped_bytes += len;
            return -1;
        }
    }
    return dealpg4_outer_writeq_append(q, data, len, kind, stream);
}

int dealpg4_outer_writeq_flush(dealpg4_outer_writeq *q)
{
    while (q->n_items > 0) {
        dealpg4_outer_writeq_item *item = &q->items[q->head];
        size_t total = item->frag_len + item->tail_len;
        size_t off = item->off;
        struct iovec iov[2];
        int niov = 0;
        ssize_t wr;

        if (off >= total) {
            /* Fully written: pop. */
            q->n_bytes -= total;
            if (item->kind == 0)
                q->payload[item->stream] -= total;
            q->head = (q->head + 1) % DEALPG4_OUTER_WRITEQ_MAX_ITEMS;
            q->n_items--;
            continue;
        }
        if (off < item->frag_len) {
            iov[niov].iov_base = q->arena + item->start + off;
            iov[niov].iov_len = item->frag_len - off;
            niov++;
            if (item->tail_len > 0) {
                iov[niov].iov_base = q->arena;
                iov[niov].iov_len = item->tail_len;
                niov++;
            }
        } else {
            iov[niov].iov_base = q->arena;
            iov[niov].iov_len = item->tail_len - (off - item->frag_len);
            niov++;
        }
        wr = writev(q->fd, iov, niov);
        if (wr > 0) {
            item->off += (size_t)wr;
            continue;
        }
        if (wr < 0 && errno == EINTR)
            continue;
        if (wr < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            return 1; /* POLLOUT: the owner polls, a write never blocks */
        return -1;    /* EPIPE or any other error: hop loss */
    }
    return 0;
}

/* === Entry preamble (D1) ================================================ */

/* Run the D1 entry preamble. Returns 0 on success, or the pinned
 * refusal status; the caller closes the timer/signalfd fds and
 * restores the process mask on every refusal path. The preamble runs
 * before any fork; every capability refusal prints the named token on
 * stderr. */
static int dealpg4_outer_preamble(dealpg4_outer_state *st)
{
    sigset_t sigchld_set;

    /* setsid() first: the outer becomes a session leader before any
     * fork (parent D1). */
    if (setsid() == -1) {
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    st->setsid_ok = 1;

    /* SIGHUP and SIGPIPE ignored: EPIPE surfaces as an error return
     * (shell loss), never a signal death (parent D1). */
    (void)signal(SIGHUP, SIG_IGN);
    (void)signal(SIGPIPE, SIG_IGN);

    /* Subreaper set + read-back before any fork, never assumed
     * inherited (parent D1); the FI_OUTER_SUBREAPER seam forces the
     * failure path. */
    if (dealpg4_fi_hooks.fail(FI_OUTER_SUBREAPER) != 0
        || prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) {
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    {
        int is_subreaper = 0;

        if (prctl(PR_GET_CHILD_SUBREAPER, &is_subreaper) != 0
            || is_subreaper != 1) {
            fprintf(stderr, "CAPABILITY_MISSING\n");
            return DEALPG4_EXIT_CAPABILITY_MISSING;
        }
    }
    st->subreaper_ok = 1;

    /* shellPid and T0o (the CLOCK_MONOTONIC entry read), then the
     * outer recipe deadlines (D1/D9). */
    st->shell_pid = getppid();
    st->t0o = (int64_t)dealpg4_now_ms();
    dealpg4_outer_recipe(st->limits, st->t0o, &st->dl);
    st->total_deadline = st->dl.totalDeadline;

    /* Total-deadline timerfd (D1): timerfd_create plus the absolute
     * T0o + overallTimeoutMs arm through the canonical machinery; any
     * failure is the TIMER_FAILED refusal (the FI_OUTER_TIMERFD seam
     * forces the failure path). */
    if (dealpg4_fi_hooks.fail(FI_OUTER_TIMERFD) != 0
        || dealpg4_deadline_open(&st->timer) != 0
        || dealpg4_deadline_arm(&st->timer,
                                (uint64_t)st->dl.totalDeadline) != 0) {
        fprintf(stderr, "TIMER_FAILED\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    st->timerfd_ok = 1;

    /* signalfd(SIGCHLD) (D1): SIGCHLD is blocked in the process mask
     * before signalfd creation (signalfd delivers only blocked
     * signals); a failure is the CAPABILITY_MISSING refusal (the
     * FI_OUTER_SIGNALFD seam forces the failure path). */
    sigemptyset(&sigchld_set);
    sigaddset(&sigchld_set, SIGCHLD);
    if (sigprocmask(SIG_BLOCK, &sigchld_set, NULL) != 0) {
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    st->sig_fd = -1;
    if (dealpg4_fi_hooks.fail(FI_OUTER_SIGNALFD) != 0
        || (st->sig_fd = signalfd(-1, &sigchld_set,
                                  SFD_NONBLOCK | SFD_CLOEXEC)) < 0) {
        if (st->sig_fd >= 0)
            close(st->sig_fd);
        st->sig_fd = -1;
        fprintf(stderr, "CAPABILITY_MISSING\n");
        return DEALPG4_EXIT_CAPABILITY_MISSING;
    }
    st->signalfd_ok = 1;

    /* outerNonce (D1): 16 bytes from getrandom(2) via the protocol
     * nonce helper, used only as the broker socket path suffix. An
     * unrecoverable failure is NONCE_FAILED (gate-fatal, exit 1): no
     * fork, no socket, no records; the nonce is never silently zeroed
     * or derived. The FI_OUTER_NONCE seam forces the failure path. */
    if (dealpg4_fi_hooks.fail(FI_OUTER_NONCE) != 0
        || dealpg4_nonce_hex(st->outer_nonce) != 0) {
        fprintf(stderr, "NONCE_FAILED\n");
        return DEALPG4_OUTER_EXIT_GATE_FAILURE;
    }

    return 0;
}

/* === ppoll loop skeleton (D1/D9) ======================================== */

/* The single timerfd carries the armed absolute deadline; expiry
 * evaluates which recipe deadline has fired (D9). At this stage the
 * preamble arms the total deadline — the only deadline with an
 * applicable action until the coordinator/registry machinery lands —
 * so an expiry observes the readiness deadline and the INVOKE cutoff
 * as passed on the way and the total deadline as the firing deadline.
 * The per-deadline actions (READINESS_TIMEOUT /
 * COORDINATOR_STARTUP_FAILED, cutoff cancellation, the total-deadline
 * escalation) land with the next sequencing steps; this child records
 * the fired deadlines and terminates the stage run at the total
 * deadline with the gate-failure token (the outer deadline is a hard
 * gate failure, D1). Returns 1 when the loop must terminate. */
static int dealpg4_outer_deadline_expiry(dealpg4_outer_state *st)
{
    int64_t now = (int64_t)dealpg4_now_ms();

    if (now >= st->dl.totalDeadline) {
        st->total_fired = 1;
        st->cutoff_fired = 1;   /* every earlier recipe deadline has
                                   passed by the total deadline */
        st->readiness_fired = 1;
        dealpg4_outer_gate_token(st, "OVERALL_TIMEOUT");
        return 1;
    }
    if (now >= st->dl.invokeCutoff) {
        st->cutoff_fired = 1;
        st->readiness_fired = 1;
        /* Later children: mark every live record CANCELLING. */
        return 0;
    }
    if (now >= st->dl.readinessDeadline) {
        st->readiness_fired = 1;
        /* Later children: READINESS_TIMEOUT /
         * COORDINATOR_STARTUP_FAILED. */
        return 0;
    }
    return 0;
}

/* Drain the timerfd expiry and evaluate. Returns 1 when the loop must
 * terminate, 0 to continue. */
static int dealpg4_outer_expiry(dealpg4_outer_state *st)
{
    uint64_t expirations = 0;

    if (dealpg4_deadline_drain(&st->timer, &expirations) < 0) {
        /* Event-loop machinery failure: the deadline-bounded run
         * cannot continue. */
        dealpg4_outer_gate_token(st, "TIMER_FAILED");
        return 1;
    }
    if (expirations == 0 && dealpg4_deadline_armed(&st->timer))
        return 0; /* spurious readiness: nothing fired */
    return dealpg4_outer_deadline_expiry(st);
}

/* Drain the signalfd until EAGAIN (parent D1: SIGCHLD events are
 * consumed through the signalfd; the reap machinery lands with the
 * coordinator/nested-fork children). */
static void dealpg4_outer_sigchld_drain(dealpg4_outer_state *st)
{
    for (;;) {
        struct signalfd_siginfo fdsi;
        ssize_t r = read(st->sig_fd, &fdsi, sizeof fdsi);

        if (r == (ssize_t)sizeof fdsi) {
            st->sigchld_events++;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break; /* EAGAIN (or EOF/error): drained */
    }
}

/* The single-threaded ppoll loop (parent D1): timerfd + signalfd +
 * POLLOUT sides for every non-empty write queue. ppoll blocks only
 * with the earliest applicable deadline (the armed timerfd), never on
 * a write. */
static void dealpg4_outer_loop(dealpg4_outer_state *st)
{
    st->loop_entered = 1;
    for (;;) {
        struct pollfd pfds[DEALPG4_OUTER_POLLFD_MAX];
        nfds_t n = 0;
        uint64_t remaining;
        struct timespec ts;
        struct timespec *tsp = NULL;
        int rc;
        size_t i;

        pfds[n].fd = st->timer.fd;
        pfds[n].events = POLLIN;
        pfds[n].revents = 0;
        n++;
        pfds[n].fd = st->sig_fd;
        pfds[n].events = POLLIN;
        pfds[n].revents = 0;
        n++;
        /* POLLOUT sides while per-record queues are non-empty (D1);
         * the broker/nested-channel queues register with the later
         * children. */
        for (i = 0; i < st->nwriteqs && n < DEALPG4_OUTER_POLLFD_MAX;
             i++) {
            if (dealpg4_outer_writeq_empty(st->writeqs[i]))
                continue;
            pfds[n].fd = st->writeqs[i]->fd;
            pfds[n].events = POLLOUT;
            pfds[n].revents = 0;
            n++;
        }

        /* ppoll with the earliest applicable deadline — the armed
         * absolute deadline, never a write wait. */
        remaining = dealpg4_deadline_remaining_ms(&st->timer);
        if (remaining > 0) {
            dealpg4_ms_to_timespec(remaining, &ts);
            tsp = &ts;
        }
        rc = ppoll(pfds, n, tsp, NULL);
        if (rc < 0 && errno == EINTR) {
            /* Re-arm the identical absolute deadline (no drift, no
             * extension); a passed deadline disarms the context and
             * the pending expiry drains on the next pass. */
            (void)dealpg4_deadline_recompute(&st->timer, &remaining);
            continue;
        }
        if (rc < 0) {
            dealpg4_outer_gate_token(st, "TIMER_FAILED");
            break;
        }
        if (rc == 0) {
            /* ppoll timed out at the armed absolute deadline. */
            if (dealpg4_outer_expiry(st))
                break;
            continue;
        }
        if (pfds[0].revents != 0) {
            if (dealpg4_outer_expiry(st))
                break;
        }
        if (pfds[1].revents & POLLIN)
            dealpg4_outer_sigchld_drain(st);
        {
            size_t dropped[DEALPG4_OUTER_MAX_WRITEQ_HOPS];
            size_t ndropped = 0;
            size_t qi = 2;

            /* Only non-empty queues were polled, in registration
             * order starting at pfds index 2: each non-empty queue
             * consumes exactly one slot (bounded by the polled fd
             * count when the fd budget ran out). */
            for (i = 0; i < st->nwriteqs && qi < n; i++) {
                if (dealpg4_outer_writeq_empty(st->writeqs[i]))
                    continue;
                if (pfds[qi].revents & POLLOUT) {
                    if (dealpg4_outer_writeq_flush(st->writeqs[i]) < 0) {
                        /* Hop loss (EPIPE/error): the queue leaves
                         * the poll set; the record fallback lands
                         * with the channel children. */
                        dealpg4_outer_writeq_clear(st->writeqs[i]);
                        if (ndropped < DEALPG4_OUTER_MAX_WRITEQ_HOPS)
                            dropped[ndropped++] = i;
                    }
                }
                qi++;
            }
            /* Compact the dropped queue entries (reverse order keeps
             * the earlier indices valid). */
            while (ndropped > 0) {
                size_t di = dropped[--ndropped];

                st->writeqs[di] = st->writeqs[st->nwriteqs - 1];
                st->nwriteqs--;
            }
        }
        if (st->total_fired)
            break;
    }
}

/* === Final report (D1) ================================================== */

/* Flush the report queue on POLLOUT, bounded by the remaining total
 * budget: persistent POLLOUT failure on report stdout proceeds to exit
 * (the final report is best-effort under the write-side policy). */
static void dealpg4_outer_report_flush(dealpg4_outer_state *st,
                                       dealpg4_outer_writeq *q)
{
    while (!dealpg4_outer_writeq_empty(q)) {
        int rc = dealpg4_outer_writeq_flush(q);

        if (rc <= 0)
            return; /* drained, or hop loss (EPIPE etc.): best-effort */
        {
            uint64_t remaining = dealpg4_deadline_remaining_ms(&st->timer);
            struct pollfd pfd;

            if (remaining == 0)
                return; /* persistent POLLOUT failure: proceed to exit */
            pfd.fd = q->fd;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            if (poll(&pfd, 1,
                     remaining > (uint64_t)INT_MAX ? INT_MAX
                                                   : (int)remaining) <= 0)
                return; /* timeout or poll error: proceed to exit */
        }
    }
}

/* The final report (report_fd, not a DEALPG4 record): one header line,
 * one "OUTER token <NAME>" line per recorded gate token, and one
 * "OUTER record" line per registry record (the registry lines join
 * with the next sequencing steps). Bounded and best-effort. */
static void dealpg4_outer_report(dealpg4_outer_state *st,
                                 const dealpg4_outer_outcome *outcome,
                                 int status)
{
    static unsigned char arena[DEALPG4_OUTER_REPORT_ARENA_BYTES];
    dealpg4_outer_writeq q;
    char line[256];
    size_t i;

    if (st->report_fd < 0)
        return;
    dealpg4_outer_writeq_init(&q, st->report_fd, arena, sizeof arena);
    snprintf(line, sizeof line,
             "OUTER final %d elapsedMs=%llu records=%d\n", status,
             (unsigned long long)((uint64_t)dealpg4_now_ms()
                                  - (uint64_t)st->t0o),
             outcome->records_total);
    if (dealpg4_outer_writeq_queue(&q, line, strlen(line), 1, 0) != 0)
        return;
    for (i = 0; i < st->ntokens; i++) {
        int n = snprintf(line, sizeof line, "OUTER token %s\n",
                         st->tokens[i]);

        if (n <= 0 || (size_t)n >= sizeof line)
            continue;
        if (dealpg4_outer_writeq_queue(&q, line, (size_t)n, 1, 0) != 0)
            return;
    }
    dealpg4_outer_report_flush(st, &q);
}

/* === Result view and cleanup ============================================ */

static void dealpg4_outer_copy_view(dealpg4_outer_state *st, int status)
{
    dealpg4_outer_result v;

    memset(&v, 0, sizeof v);
    v.t0o = st->t0o;
    v.shell_pid = st->shell_pid;
    v.total_deadline = st->total_deadline;
    memcpy(v.outer_nonce, st->outer_nonce, sizeof v.outer_nonce);
    v.setsid_ok = st->setsid_ok;
    v.subreaper_ok = st->subreaper_ok;
    v.timerfd_ok = st->timerfd_ok;
    v.signalfd_ok = st->signalfd_ok;
    v.readiness_fired = st->readiness_fired;
    v.cutoff_fired = st->cutoff_fired;
    v.total_fired = st->total_fired;
    v.report_flags_captured = st->report_flags_captured;
    v.report_flags_restored = st->report_flags_restored;
    v.gate_failure = st->gate_failure;
    v.exit_status = status;
    v.sigchld_events = st->sigchld_events;
    v.ntokens = st->ntokens;
    memcpy(v.tokens, st->tokens, sizeof v.tokens);
    dealpg4_outer_last_result_view = v;
}

/* Restore the captured report-fd flag set (D1). Returns 1 when the
 * captured set is observed again after the restore attempt, 0 when no
 * flag set was captured (F_GETFL failed at entry) or the restore
 * failed. */
static int dealpg4_outer_restore_report_flags(dealpg4_outer_state *st)
{
    if (st->report_flags < 0)
        return 0;
    if (fcntl(st->report_fd, F_SETFL, st->report_flags) == -1)
        return 0;
    return fcntl(st->report_fd, F_GETFL) == st->report_flags;
}

/* Close the event-loop fds and restore the process signal mask
 * (idempotent; shared by every exit path). */
static void dealpg4_outer_close_fds(dealpg4_outer_state *st)
{
    if (st->sig_fd >= 0) {
        close(st->sig_fd);
        st->sig_fd = -1;
    }
    dealpg4_deadline_close(&st->timer);
    (void)sigprocmask(SIG_SETMASK, &st->entry_mask, NULL);
}

/* === Core entry (D2) ==================================================== */

int dealpg4_outer_core(const OuterLimits *limits,
                       const char coordinator_nonce[33],
                       char *const coordinator_argv[],
                       const char *socket_dir, int report_fd,
                       const dealpg4_outer_spawn *spawn)
{
    dealpg4_outer_state st;
    dealpg4_outer_outcome outcome;
    int status;

    /* Observability reset, before any side effect or refusal path
     * (outer.h result contract): a core call refused before the
     * preamble leaves a zeroed view, never the previous call's. */
    memset(&dealpg4_outer_last_result_view, 0,
           sizeof dealpg4_outer_last_result_view);
    memset(&st, 0, sizeof st);
    st.timer.fd = -1;
    st.sig_fd = -1;
    st.report_fd = report_fd;
    st.shell_pid = -1;
    st.report_flags = -1;

    /* Fail-closed core-entry validation, before any fork/socket/
     * channel (D2). The mode entry validates the same shapes on its
     * surface; the core re-validates so in-process callers (the
     * selftest battery) get the same guarantees. */
    if (dealpg4_outer_limits_valid(limits) == 0)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (dealpg4_nonce_is_valid(coordinator_nonce) == 0)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (coordinator_argv == NULL || coordinator_argv[0] == NULL)
        return DEALPG4_EXIT_CONFIG_INVALID;
    if (socket_dir == NULL || socket_dir[0] == '\0')
        return DEALPG4_EXIT_CONFIG_INVALID;

    st.limits = limits;
    memcpy(st.coordinator_nonce, coordinator_nonce,
           DEALPG4_NONCE_HEX_CHARS);
    st.coordinator_nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    st.coordinator_argv = coordinator_argv;
    st.socket_dir = socket_dir;
    st.spawn = spawn;

    /* Exec-hygiene entry-mask capture (D1/D8), before SIGCHLD is ever
     * blocked. With a NULL set the call only queries the current mask
     * and cannot fail. */
    (void)sigprocmask(SIG_SETMASK, NULL, &st.entry_mask);

    /* Report-fd flag discipline (D1): capture F_GETFL before
     * O_NONBLOCK is enabled; the original flag set is restored before
     * every exit. */
    st.report_flags = fcntl(report_fd, F_GETFL);
    if (st.report_flags >= 0) {
        if (fcntl(report_fd, F_SETFL, st.report_flags | O_NONBLOCK) == 0)
            st.report_flags_captured = 1;
    }

    /* Entry preamble (D1): the capability refusals and the NONCE_FAILED
     * gate failure exit here — no fork, no socket, no records. */
    status = dealpg4_outer_preamble(&st);
    if (status != 0) {
        dealpg4_outer_close_fds(&st);
        st.report_flags_restored = dealpg4_outer_restore_report_flags(&st);
        dealpg4_outer_copy_view(&st, status);
        return status;
    }

    /* ppoll loop skeleton: runs to the total deadline at this stage
     * (the stage termination is the gate-failure token recorded by the
     * deadline evaluation). */
    dealpg4_outer_loop(&st);

    /* Outcome facts of the stage run: the coordinator fork/bootstrap,
     * registry, and final proof land with the next sequencing steps,
     * so the clean-exit discrimination cannot hold yet. */
    memset(&outcome, 0, sizeof outcome);
    outcome.gate_failure = st.gate_failure;

    status = dealpg4_outer_map_exit(&outcome);

    /* Final report (D1): named tokens, elapsed time, per-record states
     * (none at this stage) — best-effort under the write-side policy. */
    if (st.loop_entered)
        dealpg4_outer_report(&st, &outcome, status);

    st.report_flags_restored = dealpg4_outer_restore_report_flags(&st);
    dealpg4_outer_close_fds(&st);
    dealpg4_outer_copy_view(&st, status);
    return status;
}

/* === Mode entry (D1) ==================================================== */

int dealpg4_outer_entry(int argc, char **argv)
{
    int stdout_flags = -1;
    int status;

    /* Write-side signal discipline first: the usage message below is
     * a write-capable fd use, and every later-stage write path
     * inherits the ignored disposition (the core preamble repeats the
     * ignore). */
    (void)signal(SIGPIPE, SIG_IGN);

    /* argv shape (D1): "launcher outer <coordinatorNonce> --
     * <coordinator-argv...>". argv[0] = program path, argv[1] =
     * "outer", argv[2] = the coordinator nonce, argv[3] = "--",
     * argv[4..] = the coordinator argv (argv[0] = the program). Every
     * shape error — a missing "--", an empty coordinator argv, a
     * malformed nonce — is a usage error: usage message on stderr,
     * exit 2 (dispatch's usage status), no fork, no socket, no
     * channel. */
    if (argc < 5) {
        dealpg4_outer_print_usage();
        return DEALPG4_EXIT_USAGE;
    }
    if (dealpg4_nonce_is_valid(argv[2]) == 0) {
        dealpg4_outer_print_usage();
        return DEALPG4_EXIT_USAGE;
    }
    if (strcmp(argv[3], DEALPG4_ARGV_SEPARATOR) != 0) {
        dealpg4_outer_print_usage();
        return DEALPG4_EXIT_USAGE;
    }

    /* Report stdout (D1): F_GETFL is captured for fd 1 before
     * O_NONBLOCK is enabled; the original flag set is restored before
     * every exit. */
    stdout_flags = fcntl(1, F_GETFL);
    if (stdout_flags != -1)
        (void)fcntl(1, F_SETFL, stdout_flags | O_NONBLOCK);

    /* Surface binding (D2): embedded OuterLimits, socket_dir = build,
     * report_fd = STDOUT_FILENO, spawn = NULL (production
     * fork_nested). The outer mode reads no limits from CLI/env. */
    status = dealpg4_outer_core(&dealpg4_embedded_outer_limits, argv[2],
                                &argv[4], DEALPG4_OUTER_SOCKET_DIR,
                                STDOUT_FILENO, NULL);

    if (stdout_flags != -1)
        (void)fcntl(1, F_SETFL, stdout_flags);
    return status;
}

/* === Production nested-spawn seam (D2/D3) =============================== */

/* The nested serve child (production): the D2 child-side sequence —
 * PDEATHSIG + parent recheck immediately after fork, fd 0 = the
 * per-invocation control channel, stdout/stderr -> /dev/null, the D3
 * env pins child-side, then exactly one execvp of the serve argv;
 * exec failure -> _exit(127). Never returns. */
static void dealpg4_outer_nested_child(char *const serve_argv[],
                                       const char nonce[33],
                                       int64_t budget_t,
                                       int64_t invocation_id,
                                       int child_control_fd,
                                       pid_t outer_pid)
    __attribute__((noreturn));

static void dealpg4_outer_nested_child(char *const serve_argv[],
                                       const char nonce[33],
                                       int64_t budget_t,
                                       int64_t invocation_id,
                                       int child_control_fd,
                                       pid_t outer_pid)
{
    char budget_buf[32];
    char id_buf[32];
    int n;
    int devnull = -1;

    /* Immediately after fork, before dup2, environment setup, or
     * exec: the kernel-mediated parent-death kill and the
     * getppid() == outerPid recheck (a recheck mismatch — or a failed
     * PDEATHSIG set — exits with _exit(2), D2). */
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
        _exit(2);
    if (getppid() != outer_pid)
        _exit(2);

    /* fd 0 = the per-invocation control channel (the serve surface
     * sets O_NONBLOCK|FD_CLOEXEC at entry, supervisor-engine D3); the
     * original child end never survives past the dup2. */
    if (child_control_fd != 0) {
        if (dup2(child_control_fd, 0) == -1)
            _exit(3);
        close(child_control_fd);
    }

    /* stdout/stderr -> /dev/null (serve mode writes nothing to
     * stdio). */
    devnull = open("/dev/null", O_WRONLY);
    if (devnull == -1)
        _exit(3);
    if (dup2(devnull, 1) == -1 || dup2(devnull, 2) == -1)
        _exit(3);
    close(devnull);

    /* D3 env pins, child-side at the fork: DEALPG4_NONCE (the
     * record's invocation nonce), DEALPG4_BUDGET_MS (the delivered
     * nested budget T, decimal), DEALPG4_INVOCATION_ID (decimal). A
     * delivery failure is fail-closed before the exec: _exit(3), the
     * CONFIG_INVALID class (the serve surface itself refuses a
     * missing pin the same way). */
    n = snprintf(budget_buf, sizeof budget_buf, "%lld",
                 (long long)budget_t);
    if (n <= 0 || (size_t)n >= sizeof budget_buf)
        _exit(3);
    n = snprintf(id_buf, sizeof id_buf, "%lld",
                 (long long)invocation_id);
    if (n <= 0 || (size_t)n >= sizeof id_buf)
        _exit(3);
    if (setenv(DEALPG4_ENV_NONCE, nonce, 1) != 0
        || setenv(DEALPG4_ENV_BUDGET_MS, budget_buf, 1) != 0
        || setenv(DEALPG4_ENV_INVOCATION_ID, id_buf, 1) != 0)
        _exit(3);

    execvp(serve_argv[0], serve_argv);
    _exit(127);
}

int dealpg4_outer_fork_nested(const dealpg4_outer_spawn *self,
                              char *const serve_argv[],
                              const char nonce[33], int64_t budget_t,
                              int64_t invocation_id,
                              int child_control_fd)
{
    pid_t outer_pid;
    pid_t child;

    (void)self; /* the production fork ignores the composition state */
    if (serve_argv == NULL || serve_argv[0] == NULL || nonce == NULL) {
        errno = EINVAL;
        return -1;
    }
    outer_pid = getpid(); /* recorded before the fork for the child's
                             parent recheck */
    child = fork();
    if (child < 0)
        return -1;
    if (child > 0)
        return child;
    /* Child: the serve surface runs here; the production child never
     * returns (the battery composition returns 0 and runs
     * dealpg4_supervise_core in-process). */
    dealpg4_outer_nested_child(serve_argv, nonce, budget_t,
                               invocation_id, child_control_fd,
                               outer_pid);
}
