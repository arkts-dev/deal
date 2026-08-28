/*
 * DEALPG4 outer feature supervisor (ISSUE-0293/ISSUE-0294, epic
 * Sequencing steps 1-2): the outer mode entry surface, the in-process
 * outer core entry, the D1 entry preamble, the ppoll loop with the
 * non-blocking write-side discipline, the nested-spawn seam, the
 * exit-status mapping, the coordinator fork with the pre-exec
 * bootstrap, the COORD_READY verification, the continuous 1 MiB
 * coordinator stream drains, the D8 escalation machinery with the
 * COORDINATOR_HANG escalation-deadline trigger, the
 * COORDINATOR_STARTUP_FAILED by-pid termination scope, and the
 * final-proof skeleton.
 *
 * See outer.h for the pinned surfaces (dealpg4-outer-supervisor-engine
 * D1/D2/D5/D6; outer-coordinator-and-broker D1-D9 preserved):
 *  - the mode entry validates the pinned argv shape and binds the
 *    embedded-limits/socket-dir/report-fd/spawn surface;
 *  - the core owns the entry preamble (setsid, ignore SIGHUP/SIGPIPE,
 *    subreaper set + read-back, shellPid, T0o, the timerfd,
 *    signalfd(SIGCHLD), the outerNonce), the coordinator lifecycle
 *    (pre-exec pipe + COORD_READY wait + report-first /proc
 *    cross-check + continuous stream drains + the D8 escalation +
 *    the final proof), the ppoll loop, the write-side discipline, the
 *    final report, and the exit-status mapping;
 *  - the entry-level and coordinator-level fault-injection sites
 *    (FI_OUTER_SUBREAPER / FI_OUTER_TIMERFD / FI_OUTER_SIGNALFD /
 *    FI_OUTER_NONCE / FI_OUTER_PIPE / FI_COORD_READY_MISMATCH plus
 *    the outer-pre-coord-fork / coord-post-fork /
 *    coord-pre-ready-write delay sites) land with this file.
 *
 * Stage invariants: no broker socket, no registry, no nested forks
 * anywhere in this file except the production fork_nested seam (which
 * is invoked only by the registration machinery of a later sequencing
 * step); the broker socket, registry, nested forks, fallbacks, and the
 * remaining final-sequence discrimination land with the next
 * sequencing steps. At this stage the run completes when the
 * coordinator is reaped (clean exit or COORDINATOR_LOST /
 * COORDINATOR_STARTUP_FAILED), at the pinned escalation deadline
 * (COORDINATOR_HANG), on shell loss (immediate bounded escalation), or
 * at the total deadline (OVERALL_TIMEOUT — the hard bound).
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include "outer.h"

#include <dirent.h>
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
#include <sys/wait.h>
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

/* Environment keys of the pinned surfaces (D1/D3/D5): the coordinator
 * env (DEALPG4_BROKER_PATH, DEALPG4_NONCE = coordinator nonce) is set
 * child-side at the coordinator fork, never in the outer's own
 * environment, so nested serve children can never inherit the
 * coordinator nonce under that name (their own DEALPG4_NONCE is the
 * invocation nonce); the nested serve pins (budget/invocation id) are
 * delivered child-side at the nested fork. */
#define DEALPG4_ENV_BROKER_PATH     "DEALPG4_BROKER_PATH"
#define DEALPG4_ENV_BUDGET_MS       "DEALPG4_BUDGET_MS"
#define DEALPG4_ENV_NONCE           "DEALPG4_NONCE"
#define DEALPG4_ENV_INVOCATION_ID   "DEALPG4_INVOCATION_ID"

/* The ppoll fd budget: timerfd + signalfd + the coordinator pre-exec
 * pipe + both coordinator stream drains + every POLLOUT side of the
 * registered write queues (broker + up to 128 nested control channels
 * + report). */
#define DEALPG4_OUTER_POLLFD_MAX 160

/* Write-queue hop registry bound (broker + report + 128 records). */
#define DEALPG4_OUTER_MAX_WRITEQ_HOPS 130

/* The final report arena (bounded: header + coordinator line + proof
 * line + token lines + per-record lines; per-record lines join with
 * the registry child). */
#define DEALPG4_OUTER_REPORT_ARENA_BYTES 131072

/* The periodic shell-loss recheck cadence (parent D1: a periodic
 * getppid() != shellPid check in the event loop). The ppoll wait is
 * additionally capped at this bound so a quiet run still rechecks the
 * shell connection promptly; the armed deadline itself never moves. */
#define DEALPG4_OUTER_SHELL_CHECK_MS 100

/* Bounded survivor identity view of the adopted-descendant/group scan
 * (per-pid TERM then KILL signaling per the D7/D8 policy). */
#define DEALPG4_OUTER_SURVIVOR_VIEW_MAX 32

/* The pre-exec pipe line bound: one catalog-bounded record line
 * (COORD_READY / COORD_EXEC_FAILED, <= DEALPG4_MAX_LINE_OTHER_BYTES). */
#define DEALPG4_OUTER_READY_BUF_BYTES (DEALPG4_MAX_LINE_OTHER_BYTES + 1)

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
                            restored before every exit (and in the
                            coordinator child before the exec) */
    uint64_t sigchld_events;
    int loop_entered;
    int readiness_fired;
    int cutoff_fired;
    int total_fired;
    int done; /* the run reached its terminal result */

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

    /* Coordinator lifecycle (D1/D5/D8). */
    pid_t coordinator_pid;   /* -1 until forked */
    pid_t coordinator_pgid;  /* 0 until verified (pipe-published,
                                /proc cross-checked) */
    pid_t coordinator_sid;   /* 0 until verified */
    int ready_verified;
    int ready_resolved;      /* COORD_READY verified (plus the pipe
                                EOF), or the startup failure decided */
    int ready_line_verified; /* the COORD_READY record arrived and its
                                cross-checks held (the readiness
                                decision additionally requires the
                                pre-exec pipe EOF: the CLOEXEC write
                                end closes exactly at a successful
                                exec, so a trailing COORD_EXEC_FAILED
                                is always observed first) */
    int startup_failed;
    int coord_exec_failed;
    int coordinator_reaped;
    int coordinator_si_code;
    int coordinator_si_status;
    int coordinator_sigchld_pending; /* SIGCHLD observed while the
                                        readiness decision was still
                                        pending (the reap is deferred
                                        so the cross-check never races
                                        the coordinator's exit) */
    int streams_created;

    /* Pre-exec pipe read side. */
    int ready_pipe_rd;
    unsigned char ready_buf[DEALPG4_OUTER_READY_BUF_BYTES];
    size_t ready_buf_len;

    /* Coordinator stream drains (drained continuously from fork at
     * 1 MiB each with the truncation marker; the EOF observations are
     * final-proof items). */
    int coord_out_fd;
    int coord_err_fd;
    dealpg4_drain_ctx drain_out;
    dealpg4_drain_ctx drain_err;

    /* D8 escalation machinery. */
    int64_t escalation_deadline;  /* totalDeadline -
                                     killAndProofReserveMs (the pinned
                                     COORDINATOR_HANG trigger) */
    int escalation_active;
    int escalation_term_issued;   /* the TERM step ran */
    int escalation_term_sent;     /* a TERM was dispatched */
    int escalation_kill_issued;   /* the KILL step ran */
    int escalation_kill_sent;     /* a KILL was dispatched */
    int escalation_group_scope;   /* 1 = -pgid (verified group),
                                     0 = by pid (unverified group —
                                     never the group identity) */
    int group_liveness_checked;   /* kill(-pgid, 0) attempt observed
                                     (never against an unverified
                                     group) */
    int64_t escalation_term_ms;   /* t0o-relative */
    int64_t escalation_kill_ms;   /* absolute CLOCK_MONOTONIC */
    int64_t escalation_term_abs_ms;

    /* Adopted-descendant / group-survivor signaling view. */
    pid_t survivor_pids[DEALPG4_OUTER_SURVIVOR_VIEW_MAX];
    int64_t survivor_seen_ms[DEALPG4_OUTER_SURVIVOR_VIEW_MAX];
    size_t survivor_count;

    /* Final-proof skeleton (D8). */
    int proof_active;
    int proof_done;
    int proof_first_pass;
    int64_t proof_first_pass_ms;
    int64_t proof_next_pass_ms;
    int proof_reap_echild;   /* waitid reached ECHILD */
    int proof_adopted_clean; /* no /proc task with ppid == outerPid */
    int proof_group_clean;   /* coordinator group absent (discharged
                                under the unverified-group rule when
                                the group was never verified) */
    int proof_streams_eof;   /* both coordinator stream fds at EOF */
    int proof_broker_clean;  /* broker connection closed and socket
                                unlinked — vacuous until the broker
                                child; the check slot is wired */
    int proof_registry_clean; /* every registry record terminal —
                                 the registry is empty at this stage;
                                 the check slot is wired */
    int proof_scan_ok;       /* the /proc scan itself completed */
    uint64_t reap_count;
    uint64_t adopt_count;

    /* Shell loss (parent D1). */
    int shell_lost;

    /* Registry facts (empty at this stage; the record machinery lands
     * with the registry child). */
    int records_live;
} dealpg4_outer_state;

/* In-process observability (outer.h contract). */
static dealpg4_outer_result dealpg4_outer_last_result_view;
static dealpg4_drain_ctx dealpg4_outer_last_drain_stdout;
static dealpg4_drain_ctx dealpg4_outer_last_drain_stderr;

void dealpg4_outer_last_result(dealpg4_outer_result *out)
{
    if (out != NULL)
        *out = dealpg4_outer_last_result_view;
}

void dealpg4_outer_drain_state(const dealpg4_drain_ctx **stdout_ctx,
                               const dealpg4_drain_ctx **stderr_ctx)
{
    if (stdout_ctx != NULL)
        *stdout_ctx = &dealpg4_outer_last_drain_stdout;
    if (stderr_ctx != NULL)
        *stderr_ctx = &dealpg4_outer_last_drain_stderr;
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
     * forces the failure path). The loop re-arms the same context at
     * the earliest applicable deadline (readiness / escalation /
     * total) as the run progresses. */
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

/* === Identity cross-check helper ======================================== */

/* /proc/<pid>/stat fields 4/5/6 = ppid/pgrp/session (parent D1's
 * report//proc cross-check evidence — the report is the source, /proc
 * is the cross-check, never the reverse). */
static int dealpg4_proc_stat_identity(pid_t pid, pid_t *ppid,
                                      pid_t *pgrp, pid_t *session)
{
    char path[64];
    char buf[512];
    char *close_paren;
    char state;
    int pp = 0;
    int pg = 0;
    int se = 0;
    int fd;
    ssize_t n;

    snprintf(path, sizeof path, "/proc/%d/stat", (int)pid);
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return -1;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0)
        return -1;
    buf[n] = '\0';
    close_paren = strrchr(buf, ')');
    if (close_paren == NULL)
        return -1;
    if (sscanf(close_paren + 1, " %c %d %d %d", &state, &pp, &pg, &se)
        != 4)
        return -1;
    (void)state;
    *ppid = (pid_t)pp;
    *pgrp = (pid_t)pg;
    *session = (pid_t)se;
    return 0;
}

/* === Coordinator fork and pre-exec bootstrap (D1) ====================== */

/* The coordinator child side (parent D1, exact order): immediately
 * after fork the coord-post-fork delay site, then
 * prctl(PR_SET_PDEATHSIG, SIGKILL), the getppid() == outerPid recheck
 * (mismatch -> _exit(2)), setsid(), the getsid(0) == getpgid(0) ==
 * getpid() self-check, stdout/stderr dup2 onto the stream pipe write
 * ends, the child-side coordinator env (DEALPG4_BROKER_PATH,
 * DEALPG4_NONCE), the one COORD_READY publication (the
 * coord-pre-ready-write delay site and the FI_COORD_READY_MISMATCH
 * fail site precede the write), the captured-entry-mask restore, and
 * exactly one execvp; on exec failure COORD_EXEC_FAILED + _exit(127).
 * Never returns. */
static void dealpg4_outer_coordinator_child(
    char *const coordinator_argv[], const char *socket_dir,
    const char outer_nonce[DEALPG4_NONCE_HEX_CHARS + 1],
    const char coordinator_nonce[DEALPG4_NONCE_HEX_CHARS + 1],
    int ready_wr, int out_wr, int err_wr, pid_t outer_pid,
    const sigset_t *entry_mask) __attribute__((noreturn));

static void dealpg4_outer_coordinator_child(
    char *const coordinator_argv[], const char *socket_dir,
    const char outer_nonce[DEALPG4_NONCE_HEX_CHARS + 1],
    const char coordinator_nonce[DEALPG4_NONCE_HEX_CHARS + 1],
    int ready_wr, int out_wr, int err_wr, pid_t outer_pid,
    const sigset_t *entry_mask)
{
    char broker_path[PATH_MAX];
    char line[96];
    int n;
    pid_t pid;
    int64_t shift;
    ssize_t r;

    /* Delay site: immediately after fork, before the PDEATHSIG step
     * (drives kills inside the pre-exec window). */
    (void)dealpg4_fi_hooks.delay_ms(0, DEALPG4_FI_DELAY_COORD_POST_FORK);

    /* Exact bootstrap order (parent D1). */
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0)
        _exit(2);
    if (getppid() != outer_pid)
        _exit(2);
    if (setsid() == -1)
        _exit(2);
    if (getsid(0) != getpid() || getpgid(0) != getpid())
        _exit(2);

    /* stdout/stderr = the two coordinator stream pipe write ends (the
     * D1 stream drains; they must survive the exec — dup2 clears
     * FD_CLOEXEC on the destination). */
    if (dup2(out_wr, 1) == -1 || dup2(err_wr, 2) == -1)
        _exit(2);
    close(out_wr);
    close(err_wr);

    /* Coordinator env, child-side at the fork (D1/D5): the broker
     * path and the coordinator nonce — never in the outer's own
     * environment, so nested serve children can never inherit the
     * coordinator nonce under that name. */
    n = snprintf(broker_path, sizeof broker_path,
                 "%s/.dealpg4-broker-%s.sock", socket_dir, outer_nonce);
    if (n <= 0 || (size_t)n >= sizeof broker_path)
        _exit(2);
    if (setenv(DEALPG4_ENV_BROKER_PATH, broker_path, 1) != 0
        || setenv(DEALPG4_ENV_NONCE, coordinator_nonce, 1) != 0)
        _exit(2);

    /* The one COORD_READY publication (D1): the report is the source
     * of the pgid/sid, /proc is the cross-check. The
     * FI_COORD_READY_MISMATCH seam shifts the reported pgid/sid by
     * the scripted value — the report lies, the outer's cross-check
     * disagrees -> COORDINATOR_STARTUP_FAILED. */
    (void)dealpg4_fi_hooks.delay_ms(0,
                                    DEALPG4_FI_DELAY_COORD_PRE_READY_WRITE);
    pid = getpid();
    shift = dealpg4_fi_hooks.fail(FI_COORD_READY_MISMATCH);
    n = snprintf(line, sizeof line, "DEALPG4 COORD_READY %ld %ld %ld\n",
                 (long)pid, (long)pid + (long)shift,
                 (long)pid + (long)shift);
    if (n <= 0 || (size_t)n >= sizeof line)
        _exit(2);
    r = write(ready_wr, line, (size_t)n);
    if (r != (ssize_t)n)
        _exit(2);
    /* ready_wr stays open: FD_CLOEXEC closes it at the exec; on exec
     * failure the COORD_EXEC_FAILED publication below reuses it. */

    /* Exec hygiene: the exec'd coordinator must not inherit the
     * outer's SIGCHLD block (the signalfd discipline) — restore the
     * captured entry mask immediately before the single execvp
     * (supervisor-engine D8 discipline). */
    (void)sigprocmask(SIG_SETMASK, entry_mask, NULL);

    execvp(coordinator_argv[0], coordinator_argv);

    /* Exec failure: publish COORD_EXEC_FAILED and _exit(127) (D1). */
    {
        int err = errno;

        n = snprintf(line, sizeof line,
                     "DEALPG4 COORD_EXEC_FAILED %ld %d\n", (long)getpid(),
                     err);
        if (n > 0 && (size_t)n < sizeof line) {
            ssize_t wr = write(ready_wr, line, (size_t)n);

            (void)wr; /* best-effort publication on the way out */
        }
        _exit(127);
    }
}

/* Set O_NONBLOCK|FD_CLOEXEC on a freshly created pipe end (fd hygiene,
 * D1: every outer-owned fd that outlives a fork is FD_CLOEXEC unless
 * deliberately inherited; the drain contract requires O_NONBLOCK read
 * ends). */
static void dealpg4_outer_pipe_end_flags(int fd)
{
    int fl = fcntl(fd, F_GETFL);

    if (fl != -1)
        (void)fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    (void)fcntl(fd, F_SETFD, FD_CLOEXEC);
}

/* Close an fds pair (refusal paths). */
static void dealpg4_outer_close_pipe(int fds[2])
{
    if (fds[0] >= 0)
        close(fds[0]);
    if (fds[1] >= 0)
        close(fds[1]);
    fds[0] = -1;
    fds[1] = -1;
}

/* Start the coordinator (D1): the pre-exec pipe and the two
 * coordinator stream drain pipes are created before the fork; the
 * FI_OUTER_PIPE seam forces the failure path and any pipe failure is
 * fail-closed with no fork. Returns 0 on success (the coordinator was
 * forked), -1 when the start aborted without a fork (the caller runs
 * the unverified-group discharge — trivially, nothing was forked). */
static int dealpg4_outer_start_coordinator(dealpg4_outer_state *st)
{
    int ready_pipe[2] = {-1, -1};
    int out_pipe[2] = {-1, -1};
    int err_pipe[2] = {-1, -1};
    pid_t outer_pid;
    pid_t child;

    if (dealpg4_fi_hooks.fail(FI_OUTER_PIPE) != 0
        || pipe(ready_pipe) != 0)
        goto abort;
    if (dealpg4_fi_hooks.fail(FI_OUTER_PIPE) != 0
        || pipe(out_pipe) != 0)
        goto abort;
    if (dealpg4_fi_hooks.fail(FI_OUTER_PIPE) != 0
        || pipe(err_pipe) != 0)
        goto abort;

    dealpg4_outer_pipe_end_flags(ready_pipe[0]);
    dealpg4_outer_pipe_end_flags(ready_pipe[1]);
    dealpg4_outer_pipe_end_flags(out_pipe[0]);
    dealpg4_outer_pipe_end_flags(out_pipe[1]);
    dealpg4_outer_pipe_end_flags(err_pipe[0]);
    dealpg4_outer_pipe_end_flags(err_pipe[1]);

    /* Delay site: before the coordinator fork (lengthens the
     * shell-loss/coordinator-loss window). */
    (void)dealpg4_fi_hooks.delay_ms(0,
                                    DEALPG4_FI_DELAY_OUTER_PRE_COORD_FORK);

    outer_pid = getpid(); /* recorded before the fork for the child's
                             parent recheck */
    child = fork();
    if (child < 0)
        goto abort;
    if (child == 0)
        dealpg4_outer_coordinator_child(st->coordinator_argv,
                                        st->socket_dir, st->outer_nonce,
                                        st->coordinator_nonce,
                                        ready_pipe[1], out_pipe[1],
                                        err_pipe[1], outer_pid,
                                        &st->entry_mask);

    /* Parent: record the pid immediately at fork return (D1); retain
     * the read ends, close the write ends. */
    st->coordinator_pid = child;
    close(ready_pipe[1]);
    close(out_pipe[1]);
    close(err_pipe[1]);
    st->ready_pipe_rd = ready_pipe[0];
    st->coord_out_fd = out_pipe[0];
    st->coord_err_fd = err_pipe[0];
    st->streams_created = 1;
    dealpg4_drain_init(&st->drain_out);
    dealpg4_drain_init(&st->drain_err);
    return 0;

abort:
    dealpg4_outer_close_pipe(ready_pipe);
    dealpg4_outer_close_pipe(out_pipe);
    dealpg4_outer_close_pipe(err_pipe);
    return -1;
}

/* === COORD_READY wait and verification (D1) ============================= */

/* Startup failure decision (D1): no verified COORD_READY — the
 * COORD_EXEC_FAILED publication, the pre-exec pipe EOF, a malformed
 * pipe record, a self-consistency mismatch, a report//proc
 * disagreement, or the readiness deadline expiry. Gate-fatal; the
 * coordinator is TERM/KILLed and reaped by pid (D8 steps 1-2 skipped
 * — the group was never verified live, and the unverified pgid/sid
 * are never used). */
static void dealpg4_outer_startup_failed(dealpg4_outer_state *st);

/* The D8 bounded escalation dispatch (steps 1-2, by scope). Group
 * scope (the verified pipe-published coordinatorPgid): step 1
 * liveness check kill(-pgid, 0) == 0 with getpgrp() != pgid, then
 * TERM -pgid; grace termGraceMs; then KILL -pgid (re-verified).
 * Pid scope (COORDINATOR_STARTUP_FAILED — the group was never
 * verified): TERM/KILL by pid, steps 1-2 of the group escalation
 * skipped. When the TERM step finds nothing alive both steps run as
 * no-ops (no grace wait — nothing was signaled). */
static void dealpg4_outer_begin_escalation(dealpg4_outer_state *st,
                                           int group_scope);

static void dealpg4_outer_begin_escalation(dealpg4_outer_state *st,
                                           int group_scope)
{
    int64_t now = (int64_t)dealpg4_now_ms();

    if (st->escalation_active)
        return;
    st->escalation_active = 1;
    st->escalation_group_scope = group_scope;
    st->escalation_term_issued = 1;
    st->escalation_term_abs_ms = now;
    st->escalation_term_ms = now - st->t0o;
    st->escalation_kill_ms = now + DEALPG4_LAUNCHER_TERM_GRACE_MS;

    if (group_scope) {
        pid_t pgid = st->coordinator_pgid;

        st->group_liveness_checked = 1;
        if (pgid > 0 && kill(-pgid, 0) == 0 && getpgrp() != pgid) {
            st->escalation_term_sent = 1;
            (void)kill(-pgid, SIGTERM);
        }
    } else {
        pid_t pid = st->coordinator_pid;

        if (pid > 0 && kill(pid, 0) == 0) {
            st->escalation_term_sent = 1;
            (void)kill(pid, SIGTERM);
        }
    }

    if (!st->escalation_term_sent) {
        /* Nothing was signaled: the KILL step runs as a no-op too —
         * no grace wait for a target that is already gone. */
        st->escalation_kill_issued = 1;
        st->escalation_kill_ms = now;
    }
}

/* The grace expiry: KILL (re-verified before sending, D8 step 2). */
static void dealpg4_outer_escalation_kill(dealpg4_outer_state *st)
{
    if (!st->escalation_active || st->escalation_kill_issued)
        return;
    st->escalation_kill_issued = 1;
    st->escalation_kill_ms = (int64_t)dealpg4_now_ms();

    if (st->escalation_group_scope) {
        pid_t pgid = st->coordinator_pgid;

        if (pgid > 0 && kill(-pgid, 0) == 0) {
            st->escalation_kill_sent = 1;
            (void)kill(-pgid, SIGKILL);
        }
    } else {
        pid_t pid = st->coordinator_pid;

        if (pid > 0 && kill(pid, 0) == 0) {
            st->escalation_kill_sent = 1;
            (void)kill(pid, SIGKILL);
        }
    }
}

static void dealpg4_outer_startup_failed(dealpg4_outer_state *st)
{
    if (st->startup_failed)
        return;
    st->startup_failed = 1;
    st->ready_resolved = 1;
    dealpg4_outer_gate_token(st, "COORDINATOR_STARTUP_FAILED");
    /* The group was never verified live (no verified COORD_READY, or
     * the exec failed): the recorded pgid/sid are dropped and never
     * used — the D8 escalation and the final-proof group item run
     * under the unverified-group rule. */
    st->coordinator_pgid = 0;
    st->coordinator_sid = 0;
    if (st->ready_pipe_rd >= 0) {
        close(st->ready_pipe_rd);
        st->ready_pipe_rd = -1;
    }
    /* Immediate by-pid termination (D1/D5): the unverified group
     * identity is never signaled — no liveness check against the
     * unverified pgid is ever attempted. */
    if (st->coordinator_pid > 0 && !st->coordinator_reaped
        && !st->escalation_active)
        dealpg4_outer_begin_escalation(st, 0 /* by pid */);
}

/* Reap the coordinator (deferred until the readiness decision — see
 * the reaping section). */
static void dealpg4_outer_try_reap_coord(dealpg4_outer_state *st);

/* Process one complete pre-exec pipe record line (the segment
 * includes the LF). Exactly two records are ever expected, in order:
 * one COORD_READY (verified against the report-self-consistency, the
 * /proc/<pid>/stat fields, and getpgid/getsid — the report is the
 * source, /proc the cross-check) optionally followed by one
 * COORD_EXEC_FAILED; every other record, a framing defect, or a
 * duplicate is a child defect and fails closed. A verified COORD_READY
 * alone does not decide readiness: the decision additionally requires
 * the pre-exec pipe EOF (the CLOEXEC write end closes exactly at a
 * successful exec), so a trailing COORD_EXEC_FAILED publication is
 * always observed — the exec failure can never race the readiness
 * decision. */
static void dealpg4_outer_process_ready_line(dealpg4_outer_state *st,
                                             const unsigned char *data,
                                             size_t len)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES + 1];
    dealpg4_parsed parsed;
    dealpg4_parse_status ps;
    int64_t pid = 0;
    int64_t pgid = 0;
    int64_t sid = 0;
    const dealpg4_field_slice *f;
    pid_t pgrp = 0;
    pid_t session = 0;
    pid_t dummy_ppid = 0;

    /* Exactly one LF-terminated record line. */
    if (len == 0 || len > sizeof line - 1 || data[len - 1] != '\n'
        || memchr(data, '\n', len - 1) != NULL) {
        dealpg4_outer_startup_failed(st);
        return;
    }
    memcpy(line, data, len);
    line[len] = '\0';

    ps = dealpg4_parse(line, len, &parsed);
    if (ps != DEALPG4_PARSE_OK) {
        dealpg4_outer_startup_failed(st);
        return;
    }
    if (parsed.type == DEALPG4_REC_COORD_EXEC_FAILED) {
        st->coord_exec_failed = 1;
        dealpg4_outer_startup_failed(st);
        return;
    }
    if (parsed.type != DEALPG4_REC_COORD_READY) {
        dealpg4_outer_startup_failed(st);
        return;
    }
    if (st->ready_line_verified) {
        /* A second COORD_READY is a child defect. */
        dealpg4_outer_startup_failed(st);
        return;
    }
    f = dealpg4_parsed_field(&parsed, 0);
    if (f == NULL || dealpg4_field_decimal(f, &pid) == 0) {
        dealpg4_outer_startup_failed(st);
        return;
    }
    f = dealpg4_parsed_field(&parsed, 1);
    if (f == NULL || dealpg4_field_decimal(f, &pgid) == 0) {
        dealpg4_outer_startup_failed(st);
        return;
    }
    f = dealpg4_parsed_field(&parsed, 2);
    if (f == NULL || dealpg4_field_decimal(f, &sid) == 0) {
        dealpg4_outer_startup_failed(st);
        return;
    }

    /* The report must name the coordinator pid and be self-consistent
     * (setsid makes pgid == sid == pid, D1). */
    if (pid <= 0 || pgid <= 0 || sid <= 0
        || pid != (int64_t)st->coordinator_pid || pgid != sid
        || pgid != pid) {
        dealpg4_outer_startup_failed(st);
        return;
    }

    /* Report//proc cross-check: /proc/<pid>/stat fields 5/6 plus
     * getpgid/getsid must agree with the report — the report is the
     * source, /proc the cross-check, never the reverse. (The
     * coordinator reap is deferred until the readiness decision, so
     * the zombie's /proc entry and pgid/session survive even when the
     * coordinator exited immediately after publishing.) */
    if (dealpg4_proc_stat_identity((pid_t)pid, &dummy_ppid, &pgrp,
                                    &session)
            != 0
        || pgrp != (pid_t)pgid || session != (pid_t)sid
        || getpgid((pid_t)pid) != (pid_t)pgid
        || getsid((pid_t)pid) != (pid_t)sid) {
        dealpg4_outer_startup_failed(st);
        return;
    }

    st->coordinator_pgid = (pid_t)pgid;
    st->coordinator_sid = (pid_t)sid;
    st->ready_line_verified = 1;
    /* The readiness decision completes at the pre-exec pipe EOF
     * (dealpg4_outer_ready_eof) — the write end closes exactly at a
     * successful exec, so a trailing COORD_EXEC_FAILED can never
     * race the decision. */
}

/* Pre-exec pipe EOF: the readiness decision. EOF with a verified
 * COORD_READY and no trailing partial line is the verified readiness
 * (the CLOEXEC write end closed at the exec); EOF without COORD_READY
 * — or with a trailing partial line — is a startup failure (the child
 * died before publishing, or the pipe content is defective). */
static void dealpg4_outer_ready_eof(dealpg4_outer_state *st)
{
    if (st->ready_line_verified && st->ready_buf_len == 0) {
        st->ready_verified = 1;
        st->ready_resolved = 1;
        if (st->ready_pipe_rd >= 0) {
            close(st->ready_pipe_rd);
            st->ready_pipe_rd = -1;
        }
        st->ready_buf_len = 0;
        /* The deferred reap: the coordinator may have died during the
         * pre-ready window (SIGCHLD was consumed without a reap so
         * the cross-check had a live /proc entry). */
        if (st->coordinator_sigchld_pending)
            dealpg4_outer_try_reap_coord(st);
    } else {
        dealpg4_outer_startup_failed(st);
    }
}

/* Non-blocking pre-exec pipe reads: accumulate, process complete
 * record lines in arrival order, and apply the EOF decision — EOF
 * without a verified COORD_READY (or with a trailing partial line) is
 * a startup failure. */
static void dealpg4_outer_read_ready(dealpg4_outer_state *st)
{
    unsigned char *buf = st->ready_buf;
    size_t len = st->ready_buf_len;
    int eof = 0;

    if (st->ready_pipe_rd < 0 || st->ready_resolved)
        return;
    for (;;) {
        ssize_t r;

        if (len >= sizeof st->ready_buf - 1) {
            /* Oversize pipe content: fail closed. */
            dealpg4_outer_startup_failed(st);
            return;
        }
        r = read(st->ready_pipe_rd, buf + len,
                 sizeof st->ready_buf - 1 - len);
        if (r > 0) {
            len += (size_t)r;
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        if (r == 0) {
            eof = 1;
            break;
        }
        break; /* EAGAIN: more later */
    }
    /* Complete lines first — in arrival order — so a buffered
     * COORD_EXEC_FAILED behind a COORD_READY is always applied before
     * the EOF decision. */
    for (;;) {
        unsigned char *nl = memchr(buf, '\n', len);
        size_t seg;

        if (nl == NULL)
            break;
        seg = (size_t)(nl - buf) + 1;
        dealpg4_outer_process_ready_line(st, buf, seg);
        if (st->ready_resolved) {
            st->ready_buf_len = 0;
            return;
        }
        memmove(buf, buf + seg, len - seg);
        len -= seg;
    }
    st->ready_buf_len = len;
    if (eof)
        dealpg4_outer_ready_eof(st);
}

/* === Reaping (D8) ======================================================= */

/* Reap the coordinator (deferred until the readiness decision: the
 * COORD_READY cross-check must observe the still-unreaped task). */
static void dealpg4_outer_try_reap_coord(dealpg4_outer_state *st)
{
    siginfo_t si;

    if (st->coordinator_pid <= 0 || st->coordinator_reaped)
        return;
    if (!st->ready_resolved)
        return;
    memset(&si, 0, sizeof si);
    if (waitid(P_PID, st->coordinator_pid, &si, WEXITED | WNOHANG) == 0) {
        if (si.si_pid != 0) {
            st->coordinator_reaped = 1;
            st->coordinator_si_code = si.si_code;
            st->coordinator_si_status = si.si_status;
            st->reap_count++;
        }
    } else if (errno == ECHILD) {
        /* No longer a child (reaped elsewhere): treat as observed. */
        st->coordinator_reaped = 1;
    }
}

/* Reap every waitable child (post-readiness; the coordinator reap is
 * deferred while the readiness decision is pending, and no other
 * child exists at this stage). Returns 1 when waitid reached ECHILD,
 * 0 when children remain, -1 on a non-ECHILD waitid failure. */
static int dealpg4_outer_reap_all(dealpg4_outer_state *st)
{
    for (;;) {
        siginfo_t si;

        memset(&si, 0, sizeof si);
        if (waitid(P_ALL, 0, &si, WEXITED | WNOHANG) != 0)
            return errno == ECHILD ? 1 : -1;
        if (si.si_pid == 0)
            return 0; /* nothing waitable right now */
        st->reap_count++;
        if (si.si_pid == st->coordinator_pid && st->coordinator_pid > 0
            && !st->coordinator_reaped) {
            st->coordinator_reaped = 1;
            st->coordinator_si_code = si.si_code;
            st->coordinator_si_status = si.si_status;
        }
    }
}

/* === /proc scans (D8 step 3 + final proof) ============================== */

/* One /proc scan pass with per-pid survivor signaling: every task
 * with ppid == outerPid (an adopted descendant) and every task in the
 * verified coordinator pgid is recorded and signaled TERM at
 * discovery, KILL once its discovery grace passed (the D7/D8 policy).
 * The bounded fixpoint iteration covers reparent chains: a reaped
 * direct child's children reparent to the subreaper outer and appear
 * as direct ppid == outerPid tasks on the next pass. */
static void dealpg4_outer_scan_proc(dealpg4_outer_state *st, int64_t now)
{
    DIR *dir;
    struct dirent *ent;
    pid_t me = getpid();
    int pass;
    int adopted_found = 0;
    int group_found = 0;
    int ok = 1;

    st->proof_scan_ok = 0;
    dir = opendir("/proc");
    if (dir == NULL) {
        st->proof_adopted_clean = 0;
        st->proof_group_clean = !st->ready_verified;
        return;
    }
    for (pass = 0; pass < 8; pass++) {
        int found_new = 0;

        rewinddir(dir);
        while ((ent = readdir(dir)) != NULL) {
            const char *name = ent->d_name;
            pid_t pid = 0;
            pid_t ppid = 0;
            pid_t pgrp = 0;
            pid_t session = 0;
            size_t i;
            size_t slot;
            int adopted;
            int group;

            if (name[0] < '0' || name[0] > '9')
                continue;
            for (i = 0; name[i] != '\0'; i++) {
                int d = name[i] - '0';

                if (d < 0 || d > 9)
                    break;
                if (pid > (INT_MAX - d) / 10) {
                    pid = INT_MAX;
                    break;
                }
                pid = pid * 10 + d;
            }
            if (name[i] != '\0' || pid <= 0)
                continue;
            if (pid == me || pid == st->coordinator_pid)
                continue;
            if (dealpg4_proc_stat_identity(pid, &ppid, &pgrp, &session)
                != 0)
                continue; /* the task raced away */

            adopted = (ppid == me);
            group = (st->ready_verified && st->coordinator_pgid > 0
                     && pgrp == st->coordinator_pgid);
            if (!adopted && !group)
                continue;
            if (adopted)
                adopted_found = 1;
            if (group)
                group_found = 1;

            slot = st->survivor_count;
            for (i = 0; i < st->survivor_count; i++) {
                if (st->survivor_pids[i] == pid) {
                    slot = i;
                    break;
                }
            }
            if (slot == st->survivor_count) {
                if (st->survivor_count < DEALPG4_OUTER_SURVIVOR_VIEW_MAX) {
                    st->survivor_pids[st->survivor_count] = pid;
                    st->survivor_seen_ms[st->survivor_count] = now;
                    st->survivor_count++;
                    found_new = 1;
                }
                if (adopted)
                    st->adopt_count++;
            }
            /* TERM at discovery, KILL once the discovery grace passed
             * (never signal the outer itself; group members are also
             * covered by the verified negative-PGID escalation). */
            {
                int64_t seen = slot < st->survivor_count
                                   ? st->survivor_seen_ms[slot]
                                   : now;
                int sig = now >= seen + DEALPG4_LAUNCHER_TERM_GRACE_MS
                              ? SIGKILL
                              : SIGTERM;

                (void)kill(pid, sig);
            }
        }
        if (!found_new)
            break;
    }
    closedir(dir);
    ok = 1;
    st->proof_scan_ok = ok;
    st->proof_adopted_clean = ok && !adopted_found;
    /* The coordinator-group-absent item: verified-group scan, or the
     * unverified-group discharge (the group identity is never used —
     * reap-to-ECHILD + adopted-scan + stream-EOF own the proof). */
    st->proof_group_clean = !st->ready_verified
                            || (ok && !group_found);
}

/* === Stream drains (D1) ================================================= */

/* Pump one coordinator stream drain (non-blocking; the 1 MiB cap and
 * the truncation marker live in drain.h). EOF (or an error) closes
 * the read end — the EOF observation is a final-proof item. */
static void dealpg4_outer_pump_drain(dealpg4_outer_state *st, int *fd,
                                     dealpg4_drain_ctx *ctx)
{
    if (*fd < 0)
        return;
    for (;;) {
        dealpg4_drain_status s = dealpg4_drain_pump(ctx, *fd);

        if (s == DEALPG4_DRAIN_AGAIN)
            return;
        close(*fd);
        *fd = -1;
        return; /* EOF or error: the read side is done */
    }
}

/* === Final proof skeleton (D8) ========================================== */

/* One proof pass: (a) the reap loop to waitid ECHILD; (b) the /proc
 * scans with per-pid survivor signaling; (c) both coordinator stream
 * fds observed at EOF (drained continuously from fork); (d) the
 * coordinator group absent (or discharged under the unverified-group
 * rule); (e) every registry record terminal (empty at this stage);
 * (f) the broker connection closed and socket unlinked (vacuous until
 * the broker child — the check slot is wired). Success = every item
 * holds in one pass plus a confirming second pass after a bounded
 * ~10 ms interval (the supervisor-engine D6 two-pass discipline). */
static void dealpg4_outer_proof_pass(dealpg4_outer_state *st)
{
    int64_t now = (int64_t)dealpg4_now_ms();
    int clean;

    if (st->proof_done)
        return;

    st->proof_reap_echild = (dealpg4_outer_reap_all(st) == 1);
    if (st->streams_created) {
        dealpg4_outer_pump_drain(st, &st->coord_out_fd, &st->drain_out);
        dealpg4_outer_pump_drain(st, &st->coord_err_fd, &st->drain_err);
        st->proof_streams_eof = st->drain_out.eof && !st->drain_out.failed
                                && st->drain_err.eof
                                && !st->drain_err.failed;
    } else {
        st->proof_streams_eof = 1; /* vacuous (nothing was forked) */
    }
    dealpg4_outer_scan_proc(st, now);
    st->proof_broker_clean = 1;  /* vacuous until the broker child */
    st->proof_registry_clean = (st->records_live == 0);

    clean = st->proof_reap_echild && st->proof_adopted_clean
            && st->proof_group_clean && st->proof_streams_eof
            && st->proof_broker_clean && st->proof_registry_clean;
    if (clean) {
        if (!st->proof_first_pass) {
            st->proof_first_pass = 1;
            st->proof_first_pass_ms = now;
        } else if (now - st->proof_first_pass_ms >= 10) {
            st->proof_done = 1;
        }
    } else {
        st->proof_first_pass = 0;
    }
    st->proof_next_pass_ms = now + 5;
}

/* === Deadline evaluation and the ppoll loop ============================= */

/* The earliest applicable absolute deadline the single timerfd
 * carries (D9): the readiness deadline (while COORD_READY is
 * pending), the escalation KILL grace expiry, the pinned escalation
 * deadline totalDeadline - killAndProofReserveMs (while the
 * coordinator is alive post-readiness), the proof pass cadence, and
 * the total deadline. */
static int64_t dealpg4_outer_next_deadline(dealpg4_outer_state *st)
{
    int64_t d = st->dl.totalDeadline;

    if (!st->ready_resolved && st->dl.readinessDeadline < d)
        d = st->dl.readinessDeadline;
    if (st->escalation_active && !st->escalation_kill_issued
        && st->escalation_kill_ms < d)
        d = st->escalation_kill_ms;
    if (st->ready_resolved && !st->startup_failed
        && !st->coordinator_reaped && !st->escalation_active
        && st->escalation_deadline < d)
        d = st->escalation_deadline;
    if (st->proof_active && st->proof_next_pass_ms > 0
        && st->proof_next_pass_ms < d)
        d = st->proof_next_pass_ms;
    return d;
}

/* Re-arm the single timerfd at the next applicable deadline. A failed
 * mid-run re-arm leaves the previous state unchanged (monotonic.h:185)
 * and the named failure is recorded with a nonzero gate — the wakeups
 * then come from the previously armed absolute deadline and the
 * escalation still completes by the absolute deadlines
 * (supervisor-engine D8 precedent). */
static void dealpg4_outer_rearm(dealpg4_outer_state *st, int64_t now)
{
    int64_t d = dealpg4_outer_next_deadline(st);

    if (d < now)
        d = now;
    if (dealpg4_deadline_armed(&st->timer)
        && dealpg4_deadline_abs_ms(&st->timer) == (uint64_t)d)
        return;
    if (dealpg4_deadline_arm(&st->timer, (uint64_t)d) != 0)
        dealpg4_outer_gate_token(st, "TIMER_FAILED");
}

/* The per-batch evaluation: recipe-deadline observations, the shell
 * check, readiness resolution, escalation progression, the reap,
 * completion decisions, proof passes, and the timer re-arm. */
static void dealpg4_outer_evaluate(dealpg4_outer_state *st)
{
    int64_t now = (int64_t)dealpg4_now_ms();

    if (st->done)
        return;

    /* Recipe deadline observations (D9). */
    if (now >= st->dl.totalDeadline) {
        st->total_fired = 1;
        st->cutoff_fired = 1;
        st->readiness_fired = 1;
        dealpg4_outer_gate_token(st, "OVERALL_TIMEOUT");
        /* The hard bound (everything completes by T0o +
         * overallTimeoutMs): force the KILL step (skipping any
         * remaining grace — the deadline passed), run one final
         * reap/scan, and terminate with the facts at hand. */
        if (!st->coordinator_reaped) {
            if (!st->escalation_active)
                dealpg4_outer_begin_escalation(st, st->ready_verified);
            dealpg4_outer_escalation_kill(st);
        }
        st->proof_active = 1;
        st->proof_next_pass_ms = now;
        st->done = 1;
        (void)dealpg4_outer_reap_all(st);
        dealpg4_outer_scan_proc(st, now);
        return;
    }
    if (now >= st->dl.invokeCutoff)
        st->cutoff_fired = 1;
    if (now >= st->dl.readinessDeadline)
        st->readiness_fired = 1;

    /* Shell-loss detection (parent D1): a periodic getppid() recheck
     * — the total-cancel trigger. With no records at this stage the
     * cancellation is vacuous; the coordinator termination is the
     * immediate bounded D8 escalation (by the verified group when
     * COORD_READY was cross-checked, else by pid). */
    if (!st->shell_lost && st->shell_pid > 0
        && getppid() != st->shell_pid) {
        st->shell_lost = 1;
        dealpg4_outer_gate_token(st, "SHELL_LOST");
        if (!st->coordinator_reaped && !st->escalation_active)
            dealpg4_outer_begin_escalation(st, st->ready_verified);
    }

    /* Readiness resolution: the readiness deadline bounds the
     * COORD_READY wait (D1/D9). A COORD_READY line already verified
     * by the deadline is never raced by the deadline itself — its
     * decision completes at the pre-exec pipe EOF (or, for a
     * bootstrap hang past the deadline, at the total-deadline hard
     * bound). */
    if (!st->ready_resolved && now >= st->dl.readinessDeadline
        && !st->ready_line_verified)
        dealpg4_outer_startup_failed(st);

    /* Escalation progression: the KILL step at the grace expiry. */
    if (st->escalation_active && !st->escalation_kill_issued
        && now >= st->escalation_kill_ms)
        dealpg4_outer_escalation_kill(st);

    /* The pinned escalation trigger (D5): the D8 escalation runs only
     * at totalDeadline - killAndProofReserveMs and only when the
     * coordinator has not exited by then — a healthy coordinator that
     * exits 0 after readiness is never signaled (the clean-exit reap
     * precedes the deadline). */
    if (!st->coordinator_reaped && !st->escalation_active
        && st->ready_resolved && !st->startup_failed
        && now >= st->escalation_deadline) {
        dealpg4_outer_gate_token(st, "COORDINATOR_HANG");
        dealpg4_outer_begin_escalation(st, 1 /* verified group */);
    }

    /* Reaps. */
    if (st->ready_resolved)
        dealpg4_outer_try_reap_coord(st);

    /* Completion and the final proof. The proof starts once the
     * coordinator is reaped (or was never forked); the clean-exit
     * discrimination runs here: reaped status 0 with no live records
     * is the clean exit, any other reap is COORDINATOR_LOST (a
     * startup-failed or escalated reap keeps its own token). */
    if (!st->proof_active && !st->done) {
        if (st->coordinator_reaped) {
            if (st->ready_verified && !st->escalation_active
                && !(st->coordinator_si_code == CLD_EXITED
                     && st->coordinator_si_status == 0))
                dealpg4_outer_gate_token(st, "COORDINATOR_LOST");
            st->proof_active = 1;
            st->proof_next_pass_ms = now;
        }
    }
    if (st->proof_active)
        dealpg4_outer_proof_pass(st);
    if (st->proof_done)
        st->done = 1;

    dealpg4_outer_rearm(st, now);
}

/* Timerfd expiry: drain and evaluate which deadline fired (the timer
 * context is idle afterwards; evaluate re-arms it at the next
 * applicable deadline). */
static void dealpg4_outer_expiry(dealpg4_outer_state *st)
{
    uint64_t expirations = 0;

    if (dealpg4_deadline_drain(&st->timer, &expirations) < 0) {
        /* Event-loop machinery failure: the deadline-bounded run
         * cannot continue. */
        dealpg4_outer_gate_token(st, "TIMER_FAILED");
        st->done = 1;
        return;
    }
    if (expirations == 0 && dealpg4_deadline_armed(&st->timer))
        return; /* spurious readiness: nothing fired */
    dealpg4_outer_evaluate(st);
}

/* SIGCHLD events (parent D1: consumed through the signalfd). While
 * the readiness decision is pending the coordinator reap is deferred
 * (the cross-check needs the unreaped task); every other reaped
 * child is handled by the post-readiness reap loop. */
static void dealpg4_outer_sigchld(dealpg4_outer_state *st)
{
    for (;;) {
        struct signalfd_siginfo fdsi;
        ssize_t r = read(st->sig_fd, &fdsi, sizeof fdsi);

        if (r == (ssize_t)sizeof fdsi) {
            st->sigchld_events++;
            if (st->coordinator_pid > 0
                && (pid_t)fdsi.ssi_pid == st->coordinator_pid) {
                if (st->ready_resolved)
                    dealpg4_outer_try_reap_coord(st);
                else
                    st->coordinator_sigchld_pending = 1;
            } else if (st->ready_resolved) {
                (void)dealpg4_outer_reap_all(st);
            }
            continue;
        }
        if (r < 0 && errno == EINTR)
            continue;
        break; /* EAGAIN (or EOF/error): drained */
    }
}

/* The single-threaded ppoll loop (parent D1): timerfd + signalfd +
 * the coordinator pre-exec pipe + both coordinator stream drains +
 * POLLOUT sides for every non-empty write queue. ppoll blocks only
 * with the earliest applicable deadline (the armed timerfd, capped at
 * the shell-check cadence for the periodic getppid() recheck), never
 * on a write. */
static void dealpg4_outer_loop(dealpg4_outer_state *st)
{
    st->loop_entered = 1;
    dealpg4_outer_rearm(st, (int64_t)dealpg4_now_ms());
    for (;;) {
        struct pollfd pfds[DEALPG4_OUTER_POLLFD_MAX];
        nfds_t n = 0;
        nfds_t nfixed;
        uint64_t remaining;
        struct timespec ts;
        struct timespec *tsp = NULL;
        int rc;
        size_t i;

        if (st->done)
            break;

        pfds[n].fd = st->timer.fd;
        pfds[n].events = POLLIN;
        pfds[n].revents = 0;
        n++;
        pfds[n].fd = st->sig_fd;
        pfds[n].events = POLLIN;
        pfds[n].revents = 0;
        n++;
        if (!st->ready_resolved && st->ready_pipe_rd >= 0) {
            pfds[n].fd = st->ready_pipe_rd;
            pfds[n].events = POLLIN | POLLHUP;
            pfds[n].revents = 0;
            n++;
        }
        if (st->coord_out_fd >= 0) {
            pfds[n].fd = st->coord_out_fd;
            pfds[n].events = POLLIN | POLLHUP;
            pfds[n].revents = 0;
            n++;
        }
        if (st->coord_err_fd >= 0) {
            pfds[n].fd = st->coord_err_fd;
            pfds[n].events = POLLIN | POLLHUP;
            pfds[n].revents = 0;
            n++;
        }
        /* The first writeq POLLOUT slot index, captured before the
         * slots are appended (the fixed fds precede them). */
        nfixed = n;
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
         * absolute deadline, capped at the shell-check cadence, never
         * a write wait. */
        remaining = dealpg4_deadline_remaining_ms(&st->timer);
        if (remaining > 0) {
            if (remaining > DEALPG4_OUTER_SHELL_CHECK_MS)
                remaining = DEALPG4_OUTER_SHELL_CHECK_MS;
            dealpg4_ms_to_timespec(remaining, &ts);
            tsp = &ts;
        }
        rc = ppoll(pfds, n, tsp, NULL);
        if (rc < 0 && errno == EINTR) {
            /* Re-arm the identical absolute deadline (no drift, no
             * extension); a passed deadline disarms the context and
             * the pending expiry drains on the next pass. */
            (void)dealpg4_deadline_recompute(&st->timer, &remaining);
            dealpg4_outer_evaluate(st);
            continue;
        }
        if (rc < 0) {
            dealpg4_outer_gate_token(st, "TIMER_FAILED");
            break;
        }
        if (rc == 0) {
            /* The shell-check cadence (or the armed deadline) elapsed
             * without an event: run the per-batch evaluation. */
            dealpg4_outer_evaluate(st);
            continue;
        }
        if (pfds[0].revents != 0)
            dealpg4_outer_expiry(st);
        if (pfds[1].revents & POLLIN)
            dealpg4_outer_sigchld(st);
        {
            nfds_t k = 2;

            if (!st->ready_resolved && st->ready_pipe_rd >= 0) {
                if (pfds[k].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_outer_read_ready(st);
                k++;
            }
            if (st->coord_out_fd >= 0) {
                if (pfds[k].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_outer_pump_drain(st, &st->coord_out_fd,
                                             &st->drain_out);
                k++;
            }
            if (st->coord_err_fd >= 0) {
                if (pfds[k].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_outer_pump_drain(st, &st->coord_err_fd,
                                             &st->drain_err);
                k++;
            }
        }
        {
            size_t dropped[DEALPG4_OUTER_MAX_WRITEQ_HOPS];
            size_t ndropped = 0;
            nfds_t qi = nfixed;

            /* Only non-empty queues were polled, in registration
             * order starting at the fixed-fd slot: each non-empty
             * queue consumes exactly one slot (bounded by the polled
             * fd count when the fd budget ran out). */
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
        dealpg4_outer_evaluate(st);
    }
}

/* === Final report (D1) ================================================== */

/* Flush the report queue on POLLOUT, bounded by the remaining total
 * budget: persistent POLLOUT failure on report stdout proceeds to exit
 * (the final report is best-effort under the write-side policy).
 * Returns -1 when the hop was lost with EPIPE/EBADF (the shell-loss
 * trigger), 0 otherwise. */
static int dealpg4_outer_report_flush(dealpg4_outer_state *st,
                                      dealpg4_outer_writeq *q)
{
    while (!dealpg4_outer_writeq_empty(q)) {
        int rc = dealpg4_outer_writeq_flush(q);

        if (rc < 0) {
            int err = errno;

            if (err == EPIPE || err == EBADF)
                return -1; /* shell loss (parent D1) */
            return 0;      /* other hop loss: best-effort */
        }
        if (rc == 0)
            return 0; /* drained */
        {
            uint64_t remaining = dealpg4_deadline_remaining_ms(&st->timer);
            struct pollfd pfd;

            if (remaining == 0)
                return 0; /* persistent POLLOUT failure: proceed */
            pfd.fd = q->fd;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            if (poll(&pfd, 1,
                     remaining > (uint64_t)INT_MAX ? INT_MAX
                                                   : (int)remaining) <= 0)
                return 0; /* timeout or poll error: proceed to exit */
        }
    }
    return 0;
}

/* The final report (report_fd, not a DEALPG4 record): one header line,
 * one coordinator-facts line, one proof-result line, one "OUTER token
 * <NAME>" line per recorded gate token, and one "OUTER record" line
 * per registry record (the registry lines join with the next
 * sequencing steps). Bounded and best-effort; an EPIPE/EBADF on the
 * report write is the shell-loss trigger (parent D1). */
static void dealpg4_outer_report(dealpg4_outer_state *st,
                                 const dealpg4_outer_outcome *outcome,
                                 int status)
{
    static unsigned char arena[DEALPG4_OUTER_REPORT_ARENA_BYTES];
    dealpg4_outer_writeq q;
    char line[256];
    size_t i;
    int n;

    if (st->report_fd < 0)
        return;
    dealpg4_outer_writeq_init(&q, st->report_fd, arena, sizeof arena);
    n = snprintf(line, sizeof line,
                 "OUTER final %d elapsedMs=%llu records=%d\n", status,
                 (unsigned long long)((uint64_t)dealpg4_now_ms()
                                      - (uint64_t)st->t0o),
                 outcome->records_total);
    if (n <= 0 || (size_t)n >= sizeof line)
        return;
    if (dealpg4_outer_writeq_queue(&q, line, (size_t)n, 1, 0) != 0)
        return;
    n = snprintf(line, sizeof line,
                 "OUTER coord pid=%d pgid=%d ready=%d reaped=%d code=%d "
                 "status=%d\n",
                 (int)st->coordinator_pid, (int)st->coordinator_pgid,
                 st->ready_verified, st->coordinator_reaped,
                 st->coordinator_si_code, st->coordinator_si_status);
    if (n <= 0 || (size_t)n >= sizeof line)
        return;
    if (dealpg4_outer_writeq_queue(&q, line, (size_t)n, 1, 0) != 0)
        return;
    n = snprintf(line, sizeof line, "OUTER proof %s\n",
                 st->proof_done ? "ok" : "failed");
    if (n <= 0 || (size_t)n >= sizeof line)
        return;
    if (dealpg4_outer_writeq_queue(&q, line, (size_t)n, 1, 0) != 0)
        return;
    for (i = 0; i < st->ntokens; i++) {
        n = snprintf(line, sizeof line, "OUTER token %s\n",
                     st->tokens[i]);

        if (n <= 0 || (size_t)n >= sizeof line)
            continue;
        if (dealpg4_outer_writeq_queue(&q, line, (size_t)n, 1, 0) != 0)
            return;
    }
    if (dealpg4_outer_report_flush(st, &q) < 0) {
        /* The shell connection died on the report write: the
         * total-cancel trigger fires even though the run already
         * reached its terminal result — the gate flips nonzero. */
        st->shell_lost = 1;
        dealpg4_outer_gate_token(st, "SHELL_LOST");
    }
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

    v.coordinator_pid = st->coordinator_pid;
    v.coordinator_pgid = st->coordinator_pgid;
    v.coordinator_sid = st->coordinator_sid;
    v.ready_verified = st->ready_verified;
    v.coord_exec_failed = st->coord_exec_failed;
    v.coordinator_reaped = st->coordinator_reaped;
    v.coordinator_si_code = st->coordinator_si_code;
    v.coordinator_si_status = st->coordinator_si_status;
    v.coordinator_exited_0 = st->coordinator_reaped
                             && st->coordinator_si_code == CLD_EXITED
                             && st->coordinator_si_status == 0;
    v.escalation_term_issued = st->escalation_term_issued;
    v.escalation_term_sent = st->escalation_term_sent;
    v.escalation_kill_issued = st->escalation_kill_issued;
    v.escalation_kill_sent = st->escalation_kill_sent;
    v.escalation_group_scope = st->escalation_group_scope;
    v.group_liveness_checked = st->group_liveness_checked;
    v.escalation_term_ms = st->escalation_term_ms;
    v.escalation_kill_ms = st->escalation_kill_issued
                               ? st->escalation_kill_ms - st->t0o
                               : 0;
    v.proof_passed = st->proof_done;
    v.proof_reap_echild = st->proof_reap_echild;
    v.proof_adopted_clean = st->proof_adopted_clean;
    v.proof_group_clean = st->proof_group_clean;
    v.proof_streams_eof = st->proof_streams_eof;
    v.proof_broker_clean = st->proof_broker_clean;
    v.proof_registry_clean = st->proof_registry_clean;
    v.shell_lost = st->shell_lost;
    v.coord_stdout_bytes = st->drain_out.total_read;
    v.coord_stderr_bytes = st->drain_err.total_read;
    v.coord_stdout_truncated = st->drain_out.truncated;
    v.coord_stderr_truncated = st->drain_err.truncated;
    v.coord_stdout_eof = st->drain_out.eof;
    v.coord_stderr_eof = st->drain_err.eof;
    v.reap_count = st->reap_count;
    v.adopt_count = st->adopt_count;

    dealpg4_outer_last_result_view = v;
    dealpg4_outer_last_drain_stdout = st->drain_out;
    dealpg4_outer_last_drain_stderr = st->drain_err;
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
    if (st->ready_pipe_rd >= 0) {
        close(st->ready_pipe_rd);
        st->ready_pipe_rd = -1;
    }
    if (st->coord_out_fd >= 0) {
        close(st->coord_out_fd);
        st->coord_out_fd = -1;
    }
    if (st->coord_err_fd >= 0) {
        close(st->coord_err_fd);
        st->coord_err_fd = -1;
    }
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
    dealpg4_drain_init(&dealpg4_outer_last_drain_stdout);
    dealpg4_drain_init(&dealpg4_outer_last_drain_stderr);
    memset(&st, 0, sizeof st);
    st.timer.fd = -1;
    st.sig_fd = -1;
    st.report_fd = report_fd;
    st.shell_pid = -1;
    st.report_flags = -1;
    st.coordinator_pid = -1;
    st.ready_pipe_rd = -1;
    st.coord_out_fd = -1;
    st.coord_err_fd = -1;

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

    /* The pinned escalation deadline (D5 COORDINATOR_HANG trigger):
     * totalDeadline - killAndProofReserveMs (the embedded
     * LauncherLimits constant). Clamped at the readiness deadline so
     * a passed deadline starts the escalation the moment the
     * precondition holds (D5: "if the escalation deadline has passed
     * ... the escalation starts the moment the precondition holds"). */
    st.escalation_deadline = st.dl.totalDeadline
                             - DEALPG4_LAUNCHER_KILL_AND_PROOF_RESERVE_MS;
    if (st.escalation_deadline < st.dl.readinessDeadline)
        st.escalation_deadline = st.dl.readinessDeadline;

    /* Coordinator start (D1): pipes + fork + pre-exec bootstrap. A
     * pipe failure (injected via FI_OUTER_PIPE or real) fails closed
     * with no fork — the unverified-group discharge holds trivially
     * (nothing was ever forked) and the final proof runs. */
    if (dealpg4_outer_start_coordinator(&st) != 0) {
        st.startup_failed = 1;
        st.ready_resolved = 1;
        dealpg4_outer_gate_token(&st, "COORDINATOR_STARTUP_FAILED");
        st.proof_active = 1;
        st.proof_next_pass_ms = st.t0o;
    }

    /* ppoll loop: the readiness wait, the drains, the escalation, the
     * reap, and the final proof. */
    dealpg4_outer_loop(&st);

    /* Outcome facts. */
    memset(&outcome, 0, sizeof outcome);
    outcome.coordinator_reaped = st.coordinator_reaped;
    outcome.coordinator_exited_0 = st.coordinator_reaped
                                   && st.coordinator_si_code == CLD_EXITED
                                   && st.coordinator_si_status == 0;
    outcome.records_total = 0; /* the registry is empty at this stage */
    outcome.records_live = st.records_live;
    outcome.records_failed = 0;
    outcome.records_clean = 0;
    outcome.proof_passed = st.proof_done;
    outcome.gate_failure = st.gate_failure;

    status = dealpg4_outer_map_exit(&outcome);

    /* Final report (D1): named tokens, coordinator facts, the proof
     * result — best-effort under the write-side policy; an EPIPE on
     * the report write flips the gate (shell loss). The report runs
     * before the exit-status mapping is finalized so the trigger is
     * deterministic on every path. */
    if (st.loop_entered) {
        dealpg4_outer_report(&st, &outcome, status);
        if (st.gate_failure != outcome.gate_failure) {
            outcome.gate_failure = st.gate_failure;
            status = dealpg4_outer_map_exit(&outcome);
        }
    }

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
