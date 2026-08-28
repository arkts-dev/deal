/*
 * DEALPG4 outer feature supervisor (ISSUE-0293, epic Sequencing step 1):
 * the outer mode entry surface, the in-process outer core entry, the D1
 * entry preamble, the ppoll loop skeleton with the non-blocking
 * write-side discipline, the nested-spawn seam, and the exit-status
 * mapping.
 *
 * See dealpg4-outer-supervisor-engine D1/D2 for the pinned surfaces
 * (outer-coordinator-and-broker D1-D9 preserved). This child replaces
 * the stage placeholder:
 *  - the mode entry owns only surface binding: the pinned argv shape
 *    "launcher outer <coordinatorNonce> -- <coordinator-argv...>"
 *    (usage errors on stderr, exit 2, no fork, no socket, no channel),
 *    the embedded OuterLimits pinning, socket_dir = "build",
 *    report_fd = STDOUT_FILENO, spawn = NULL, and the fd-1 flag-set
 *    capture/restore;
 *  - the core owns the D1 entry preamble and T0o (setsid, ignore
 *    SIGHUP/SIGPIPE, subreaper set + read-back, shellPid, T0o, the
 *    total-deadline timerfd, signalfd(SIGCHLD), the outerNonce), the
 *    ppoll loop skeleton (the single timerfd carrying the earliest
 *    applicable deadline; never blocked on a write), the write-side
 *    discipline (bounded per-record queues, POLLOUT flushes, overflow
 *    dropped with the truncation flag), the final report plumbing, and
 *    the exit-status mapping.
 * The coordinator fork/bootstrap, broker socket, registry, nested
 * forks, fallbacks, escalation, and final proof land with the next
 * sequencing steps; this child owns the mapping, the report plumbing,
 * and the entry-level tokens it can produce.
 */
#ifndef DEALPG4_OUTER_H
#define DEALPG4_OUTER_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "drain.h"
#include "monotonic.h"

/* === Exit statuses (engine D1) ========================================= */

/* Gate-failure class: any FAILED record (including the FORK_FAILED /
 * NONCE_FAILED pre-fork terminal records), COORDINATOR_LOST,
 * AUTH_FAILED, READINESS_TIMEOUT, COORDINATOR_STARTUP_FAILED,
 * BROKER_STALLED, PROTOCOL_ERROR, BROKER_BIND_FAILED, post-cutoff
 * BUDGET_EXHAUSTED records, entry NONCE_FAILED, survivor/zombie/drainer
 * findings, or the outer deadline. Exit 0 requires every record
 * terminal CLEAN success or clean cancelled AND the coordinator
 * clean-exit discrimination AND the final proof. Usage (2) is
 * dispatch-owned (launcher-main.c); CONFIG_INVALID (3) and
 * CAPABILITY_MISSING (4) are the shared selftest.h statuses. */
#define DEALPG4_OUTER_EXIT_GATE_FAILURE 1

/* === Spawn seam (engine D2) ============================================ */

typedef struct dealpg4_outer_spawn {
    /* Fork the nested serve supervisor. Production behavior: fork;
     * in the child dup2(child_control_fd, 0), dup2 /dev/null onto
     * fds 1 and 2, set the D3 env pins child-side
     * (DEALPG4_NONCE / DEALPG4_BUDGET_MS / DEALPG4_INVOCATION_ID),
     * and execvp the serve argv; exec failure -> _exit(127).
     * Returns the child pid in the outer, 0 in the child (which must
     * run the serve surface and _exit), -1 on fork failure. The
     * battery substitutes an in-process composition (fork +
     * dealpg4_supervise_core call) so the forked serve child inherits
     * the installed fi overrides. */
    int (*fork_nested)(const struct dealpg4_outer_spawn *self,
                       char *const serve_argv[], const char nonce[33],
                       int64_t budget_t, int64_t invocation_id,
                       int child_control_fd);
    void *opaque; /* battery-owned composition state */
} dealpg4_outer_spawn;

/* Production fork_nested (a NULL spawn means production). The child
 * side begins immediately after fork with prctl(PR_SET_PDEATHSIG,
 * SIGKILL) and a getppid() == outerPid recheck; a recheck mismatch (or
 * a failed PDEATHSIG set) exits with _exit(2) before dup2, environment
 * setup, or exec. */
int dealpg4_outer_fork_nested(const dealpg4_outer_spawn *self,
                              char *const serve_argv[],
                              const char nonce[33], int64_t budget_t,
                              int64_t invocation_id,
                              int child_control_fd);

/* === Mode entry / core entry (engine D1/D2) ============================ */

/*
 * outer mode entry: argv shape
 * "launcher outer <coordinatorNonce> -- <coordinator-argv...>".
 * argv[2] is the coordinator nonce (validated exactly 32 lowercase
 * hex), argv[3] must be "--", argv[4..] is the coordinator argv
 * (argv[0] = the program, non-empty). Every shape error — a missing
 * "--", an empty coordinator argv, a malformed nonce — is a usage
 * error: usage message on stderr, exit 2 (dispatch's usage status),
 * no fork, no socket, no channel. The outer mode reads no limits from
 * CLI/env (limits-free); the mode entry pins the core limits to the
 * embedded OuterLimits constants, socket_dir = "build",
 * report_fd = STDOUT_FILENO, and spawn = NULL (production
 * fork_nested). Report stdout: F_GETFL is captured for fd 1 before
 * O_NONBLOCK is enabled; the original flag set is restored before
 * every exit.
 */
int dealpg4_outer_entry(int argc, char **argv);

/*
 * In-process outer core entry. The core owns the D1 entry preamble and
 * T0o (so the battery's forked scenario process gets the identical
 * entry behavior), the ppoll loop, and the report/exit discipline; the
 * mode entry owns only surface binding.
 *
 * Fail-closed core-entry validation before any fork/socket/channel:
 * limits ordering (nestedStopMs + cleanupReserveMs <= overallTimeoutMs,
 * and readinessTimeoutMs >= 1, nestedStopMs >= 1, cleanupReserveMs >= 1,
 * brokerStallMs >= 1) else CONFIG_INVALID (exit 3); nonce exactly 32
 * lowercase hex; coordinator argv non-empty; socket_dir non-empty. No
 * fork, socket, or channel on violation.
 *
 * Return status: 0 on the clean exit (the full D8 discrimination —
 * records, coordinator, proof — holds); 1
 * (DEALPG4_OUTER_EXIT_GATE_FAILURE) for any gate failure;
 * DEALPG4_EXIT_CONFIG_INVALID (3); DEALPG4_EXIT_CAPABILITY_MISSING (4)
 * for capability-class entry refusals. The final report goes to
 * report_fd (not a DEALPG4 record) with named tokens and per-record
 * states; the report-fd flag set is restored before every exit.
 */
int dealpg4_outer_core(const OuterLimits *limits,
                       const char coordinator_nonce[33],
                       char *const coordinator_argv[],
                       const char *socket_dir, int report_fd,
                       const dealpg4_outer_spawn *spawn);

/* === Fault-injection seam catalog (engine D6, this child's sites) ====== */

/* Named fail-site tags (int tags through dealpg4_fi_hooks.fail): a
 * scripted nonzero return forces the named failure path with the
 * scripted value as errno; 0 runs the real syscall. The four entry
 * sites land with this child; the remaining D6 fail sites
 * (FI_OUTER_PIPE, FI_OUTER_BIND, FI_OUTER_SOCKETPAIR, FI_OUTER_FORK,
 * FI_COORD_READY_MISMATCH, FI_OUTER_DEATH) and the delay/congestion
 * catalog land with the children that own the coordinator pipe, the
 * broker socket, and the nested fork machinery. */
enum dealpg4_outer_fi_fail_site {
    FI_OUTER_SUBREAPER = 1, /* entry prctl set/read-back fails ->
                             * CAPABILITY_MISSING, exit 4 */
    FI_OUTER_TIMERFD = 2,   /* entry timerfd_create fails ->
                             * TIMER_FAILED, exit 4 */
    FI_OUTER_SIGNALFD = 3,  /* entry signalfd(SIGCHLD) fails ->
                             * CAPABILITY_MISSING, exit 4 */
    FI_OUTER_NONCE = 4      /* entry getrandom(2) outerNonce fails ->
                             * NONCE_FAILED, exit 1 */
};

/* === Exit-status mapping (engine D1) =================================== */

/* The run-outcome facts the mapping consumes. This child owns the
 * mapping; the lifecycle machinery that produces records and
 * classifications lands with the next sequencing steps (at this stage
 * the core fills the stage facts: no coordinator reaped, an empty
 * registry, the proof not run). */
typedef struct dealpg4_outer_outcome {
    int coordinator_reaped;   /* waitid observed the coordinator */
    int coordinator_exited_0; /* reaped CLD_EXITED with status 0 */
    int records_total;        /* registry records (terminal and live) */
    int records_live;         /* pre-terminal registry records */
    int records_failed;       /* terminal FAILED records */
    int records_clean;        /* terminal CLEAN success or cancelled */
    int proof_passed;         /* the final proof completed clean */
    int gate_failure;         /* any named gate-failure token recorded */
} dealpg4_outer_outcome;

/* D1 mapping: 0 iff every record is terminal CLEAN success or clean
 * cancelled AND the coordinator clean-exit discrimination holds AND the
 * final proof passed; 1 for any gate failure (any FAILED record,
 * COORDINATOR_LOST, AUTH_FAILED, READINESS_TIMEOUT,
 * COORDINATOR_STARTUP_FAILED, BROKER_STALLED, PROTOCOL_ERROR,
 * BROKER_BIND_FAILED, post-cutoff BUDGET_EXHAUSTED records, entry
 * NONCE_FAILED, survivor/zombie/drainer findings, the outer deadline).
 */
int dealpg4_outer_map_exit(const dealpg4_outer_outcome *outcome);

/* === In-process observability (component tests, ISSUE-0184 battery) ==== */

#define DEALPG4_OUTER_MAX_TOKENS 8
#define DEALPG4_OUTER_TOKEN_BYTES 32

/* Final result view of the most recent core call (in-process
 * observability for component tests and the selftest battery; valid
 * until the next core call). Mirrors the supervisor.h result-view
 * pattern: a core call refused before the preamble leaves a zeroed
 * view. */
typedef struct dealpg4_outer_result {
    int64_t t0o;            /* CLOCK_MONOTONIC ms read at entry */
    pid_t shell_pid;        /* getppid() recorded at entry */
    int64_t total_deadline; /* t0o + overallTimeoutMs */
    char outer_nonce[33];   /* the 32-hex broker path suffix, "" when
                               generation failed */
    int setsid_ok;          /* the preamble established a session */
    int subreaper_ok;       /* prctl set + read-back == 1 */
    int timerfd_ok;         /* the total-deadline timerfd armed */
    int signalfd_ok;        /* signalfd(SIGCHLD) created */
    int readiness_fired;    /* recipe readiness deadline evaluated as
                               fired (T0o + readinessTimeoutMs) */
    int cutoff_fired;       /* INVOKE cutoff evaluated as fired
                               (T0o + nestedStopMs) */
    int total_fired;        /* total deadline evaluated as fired
                               (T0o + overallTimeoutMs) */
    int report_flags_captured; /* F_GETFL on report_fd succeeded */
    int report_flags_restored; /* F_GETFL at exit == captured flags */
    int gate_failure;       /* any named gate-failure token recorded */
    int exit_status;        /* the mapped exit status */
    uint64_t sigchld_events; /* SIGCHLD events drained by the loop */
    size_t ntokens;         /* named gate tokens recorded */
    char tokens[DEALPG4_OUTER_MAX_TOKENS][DEALPG4_OUTER_TOKEN_BYTES];
} dealpg4_outer_result;

/* Copy the most recent core call's result view (zeroed when no core
 * call ran or the call was refused before the preamble). */
void dealpg4_outer_last_result(dealpg4_outer_result *out);

/* === Write-side discipline (engine D1/D5, canonical write-side
 * contract) =============================================================
 * One bounded pending queue per outer write hop (the broker socket,
 * each nested control channel, the report stdout). The destination fd
 * is O_NONBLOCK; a serialized record line that cannot be written
 * immediately is queued and flushed only on POLLOUT in the ppoll loop.
 * Queued payload bytes (kind 0 — stream relay content) never exceed
 * DEALPG4_RELAY_QUEUE_CAP_BYTES (1 MiB, == DEALPG4_DRAIN_CAP_BYTES)
 * per stream; catalog-bounded control records (kind 1 — <= 8192-byte
 * records, never dropped by queue pressure) sit in the control reserve.
 * A payload refused at the cap is dropped with the truncation flag set
 * (overflow). No queue operation blocks: the flush writes non-blocking,
 * returns on EAGAIN, and the owner polls POLLOUT — no write suspends a
 * native deadline. */

/* Control-record reserve: a bounded handful of catalog records per
 * hop (each <= DEALPG4_MAX_LINE_OTHER_BYTES == 8192 from protocol.h)
 * never count toward the payload cap and are never dropped by queue
 * pressure. */
#define DEALPG4_OUTER_WRITEQ_CTRL_RESERVE_BYTES 131072
#define DEALPG4_OUTER_WRITEQ_MAX_ITEMS 96
/* The arena holds the 1 MiB payload budget (mirrored so a payload
 * line's hex/two-bytes-per-byte worst case is covered) plus the
 * control reserve. */
#define DEALPG4_OUTER_WRITEQ_ARENA_BYTES \
    (2 * DEALPG4_DRAIN_CAP_BYTES + DEALPG4_OUTER_WRITEQ_CTRL_RESERVE_BYTES)

/* One queued record line (possibly wrapped across the arena end as two
 * fragments). */
typedef struct dealpg4_outer_writeq_item {
    size_t start;    /* arena offset of the first fragment */
    size_t frag_len; /* first fragment length */
    size_t tail_len; /* wrap-around second fragment length */
    size_t off;      /* bytes already flushed */
    int kind;        /* 0 = payload (cap subject), 1 = control */
    int stream;      /* payload: 0 = stdout, 1 = stderr; control: -1 */
} dealpg4_outer_writeq_item;

typedef struct dealpg4_outer_writeq {
    int fd;               /* destination fd (O_NONBLOCK), >= 0 */
    unsigned char *arena; /* caller-supplied fixed arena */
    size_t arena_bytes;
    dealpg4_outer_writeq_item items[DEALPG4_OUTER_WRITEQ_MAX_ITEMS];
    int head;      /* item slot of the oldest item */
    int tail;      /* item slot of the next free item */
    int n_items;
    size_t n_bytes;      /* total line bytes queued */
    size_t payload[2];   /* queued payload bytes per stream (the
                            per-stream cap subject; each stream <=
                            DEALPG4_RELAY_QUEUE_CAP_BYTES) */
    size_t free_start;   /* arena offset where the next item starts */
    int overflow;        /* truncation flag: a payload was dropped at
                            the cap */
    size_t dropped_bytes; /* payload bytes dropped at the cap */
} dealpg4_outer_writeq;

void dealpg4_outer_writeq_init(dealpg4_outer_writeq *q, int fd,
                               unsigned char *arena, size_t arena_bytes);

/* Drop every undelivered record (channel close/loss/abort). The
 * overflow consequence (flag, dropped count) survives — it is a
 * stream-truncation consequence recorded for the report, not queue
 * state. */
void dealpg4_outer_writeq_clear(dealpg4_outer_writeq *q);

int dealpg4_outer_writeq_empty(const dealpg4_outer_writeq *q);
size_t dealpg4_outer_writeq_queued_bytes(const dealpg4_outer_writeq *q);
/* Total queued payload bytes across both streams. */
size_t dealpg4_outer_writeq_payload_bytes(const dealpg4_outer_writeq *q);
int dealpg4_outer_writeq_overflow(const dealpg4_outer_writeq *q);
size_t dealpg4_outer_writeq_dropped_bytes(const dealpg4_outer_writeq *q);

/* Queue one serialized record line (a line without the trailing LF
 * is refused — caller defect). kind 0: payload subject to the
 * DEALPG4_RELAY_QUEUE_CAP_BYTES per-stream cap (stream 0 = stdout,
 * 1 = stderr) — a payload that would push its stream's cap is dropped
 * immediately with the truncation flag set and -1 returned (the
 * caller's stream truncation consequence); kind 1: catalog-bounded
 * control record, never dropped by queue pressure (fits by
 * construction; a structural overflow is a caller defect and returns
 * -1). Returns 0 on success. */
int dealpg4_outer_writeq_queue(dealpg4_outer_writeq *q, const char *data,
                               size_t len, int kind, int stream);

/* Non-blocking flush to the destination fd: 0 when the queue drained,
 * 1 when a write hit EAGAIN (the owner polls POLLOUT — a write never
 * blocks), -1 on EPIPE or any other write error (hop loss). */
int dealpg4_outer_writeq_flush(dealpg4_outer_writeq *q);

#endif
