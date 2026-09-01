/*
 * DEALPG4 outer feature supervisor (ISSUE-0293/ISSUE-0294, epic
 * Sequencing steps 1-2): the outer mode entry surface, the in-process
 * outer core entry, the D1 entry preamble, the ppoll loop with the
 * non-blocking write-side discipline, the nested-spawn seam, the
 * exit-status mapping, the coordinator fork with the pre-exec
 * bootstrap, the COORD_READY verification, the continuous 1 MiB
 * coordinator stream drains, the D8 escalation machinery with the
 * COORDINATOR_HANG escalation-deadline trigger, the
 * COORDINATOR_STARTUP_FAILED by-pid termination scope, the
 * final-proof skeleton, and (ISSUE-0295) the authenticated AF_UNIX
 * broker socket + the HELLO/READY handshake channel state machine +
 * the framing-level PROTOCOL_ERROR close rule + the stall rule.
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
 *
 * This child (ISSUE-0297, epic Sequencing step 5) adds the
 * per-record nested control-channel state machine (engine D4):
 * one invocation per channel with expectation sets configured
 * per record state, the parent-D6 STUB_READY double
 * verification before forwarding, the ACK relay with
 * RELEASED-at-write-completion, the validated-CANCEL
 * application, the relay rules (verbatim nested-origin relays,
 * the 1 MiB payload cap with truncation), the
 * single-terminal-answer rule with the drain-only switch and
 * the outer-synthesized CLEAN/FAILED terminal records, the
 * post-terminal drop rule, the queued-write discard on
 * terminality, the nested-channel PROTOCOL_ERROR rule, and the
 * outer-side fallback-termination surface the fallback child
 * drives.
 *  - the entry-level and coordinator-level fault-injection sites
 *    (FI_OUTER_SUBREAPER / FI_OUTER_TIMERFD / FI_OUTER_SIGNALFD /
 *    FI_OUTER_ENTRY_NONCE / FI_OUTER_PIPE / FI_COORD_READY_MISMATCH plus
 *    the outer-pre-coord-fork / coord-post-fork /
 *    coord-pre-ready-write delay sites) land with this file.
 *
 * Stage invariants: the broker socket and the handshake channel
 * machine are real (D1/D5: 0700 dir, stale-path unlink, 0600 bind,
 * one SO_PEERCRED-verified connection, HELLO_OK 4 <caps> /
 * READY_ACK <nonce> through the POLLOUT relay queue, the stall rule);
 * a well-formed record in BROKER_LIVE is state-unexpected at this
 * stage (the live-phase rows land with the registry child) and closes
 * per the D5 PROTOCOL_ERROR rule. No registry and no nested forks
 * anywhere in this file except the production fork_nested seam (which
 * is invoked only by the registration machinery of a later sequencing
 * step); the registry, nested forks, fallbacks, and the remaining
 * final-sequence discrimination land with the next sequencing steps.
 * The run completes when the coordinator is reaped (clean exit or
 * COORDINATOR_LOST / COORDINATOR_STARTUP_FAILED), at the pinned
 * escalation deadline (COORDINATOR_HANG / the PROTOCOL_ERROR and
 * AUTH_FAILED aftermath), on shell loss (immediate bounded
 * escalation), on BROKER_STALLED (immediate escalation after the
 * total-cancel marking — the TERM dispatch defers while live records
 * remain), or at the total deadline (OVERALL_TIMEOUT — the hard
 * bound).
 *
 * This child (ISSUE-0298, epic Sequencing step 6) adds the D7
 * fallback execution: the per-state death fallbacks (FORKING /
 * STUB_BLOCKED / TARGET_PUBLISHED / RELEASED — a death while
 * CANCELLING applies the pre-cancel state's fallback, recovered from
 * the ordered history), the wedge rule (a live record at its
 * per-record deadline without a terminal answer: kill(supervisorPid,
 * 0) re-verified — an already-dead supervisor applies that state's
 * death fallback directly — TERM by pid, grace termGraceMs, KILL by
 * pid re-verified, reap to waitid, then the state's death fallback),
 * the total-cancel CANCEL fan-out (caller loss / the INVOKE cutoff /
 * shell loss mark every live record CANCELLING in parallel and relay
 * the CANCEL with the exact registry nonce through the POLLOUT
 * discipline), the per-record /proc proof (group/session absence
 * against the retained identities, the adopted-descendant scan with
 * per-pid TERM-then-KILL, the supervisor/stub reap observation, the
 * confirming second pass) with the survivor-token synthesis
 * (CLEAN <id> cancelled when the proof is clean, otherwise
 * FAILED <id> GROUP_SURVIVOR | SESSION_SURVIVOR | ADOPTED_SURVIVOR |
 * ZOMBIE_SURVIVOR), the COORDINATOR_LOST discrimination slot on
 * broker EOF with live records, and the D8 escalation TERM deferred
 * until every registry record is terminal.
 *
 * This child (ISSUE-0299, epic Sequencing step 7) adds the
 * coordinator death classification with the split immediate
 * escalation scopes and the final report: the READINESS_TIMEOUT
 * scope (no FEATURE_READY by T0o + readinessTimeoutMs after a
 * verified COORD_READY — the immediate D8 bounded escalation with
 * the full group scope against the pipe-published cross-checked
 * coordinatorPgid, the readiness obligation discharged only by a
 * broker close before the live phase), the clean-exit /
 * COORDINATOR_LOST discrimination over the real reaped waitid status
 * and the real registry (D8 steps 1-2 skipped for the clean exit and
 * for COORDINATOR_LOST; the total-cancel completion and the full
 * final proof still run), the DONE/BYE completion (the ppoll wait for
 * broker EOF plus the coordinator reap after DONE — never an
 * escalation on DONE itself), and the final report's reap/adoption
 * counts line.
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
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
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

/* The bounded per-record survivor view of the fallback proof scan
 * (per-pid TERM at discovery, KILL once the discovery grace passed —
 * the D7/D8 policy). */
#define DEALPG4_OUTER_REC_SURVIVOR_VIEW_MAX 8

/* The pre-exec pipe line bound: one catalog-bounded record line
 * (COORD_READY / COORD_EXEC_FAILED, <= DEALPG4_MAX_LINE_OTHER_BYTES). */
#define DEALPG4_OUTER_READY_BUF_BYTES (DEALPG4_MAX_LINE_OTHER_BYTES + 1)

/* The internal registry record (parent D2 shape + the D6 first-ACK
 * tracking field). The public view (outer.h) is the observability
 * copy. */
struct dealpg4_outer_record {
    int64_t invocation_id;
    char *client_tag;   /* heap, exact INVOKE tag bytes */
    char nonce[DEALPG4_NONCE_HEX_CHARS + 1]; /* "" for pre-fork
                                                rejection records */
    pid_t supervisor_pid; /* -1 until attached */
    int control_fd;       /* -1 until attached */
    pid_t stub_pid;       /* -1 until retained */
    pid_t target_pgid;    /* 0 until verified */
    pid_t target_session_id; /* 0 until verified */
    int state;            /* dealpg4_outer_record_state */
    int64_t deadline_ms;  /* the delivered nested budget T (0 for
                             pre-fork rejection records) */
    int64_t deadline_abs_ms; /* registration time + T */
    int ack_applied;      /* first-ACK tracking (D6): set when the
                             outer validates and accepts the ACK for
                             relay (at queueing, before any write
                             completes); cleared only when a
                             validated CANCEL or terminality
                             discards that queued-but-unwritten
                             ACK */
    int cleanup_acknowledged;
    int clean_final;      /* CLEAN records: 1 = success, 0 = cancelled */
    char failure_token[DEALPG4_OUTER_TOKEN_VIEW_BYTES];
    size_t history_count;
    dealpg4_outer_history_entry history[DEALPG4_OUTER_HISTORY_MAX];
    /* Nested control-channel machine (engine D4 — this child). */
    dealpg4_expectation_set ctrl_expect; /* configured per record
                                            state */
    int channel_drain_only; /* an outer-side termination began, or
                              the record is terminal: nested-origin
                              records are consumed-not-relayed /
                              dropped until EOF, then the channel
                              closes */
    int fallback_initiated; /* this state's death fallback was
                              initiated (the fallback execution is
                              the fallback child's) */
    int fallback_state;   /* the state whose death fallback applies
                             (the pre-cancel state when the record
                             was CANCELLING) */
    int pre_cancel_state; /* the state the record was in when the
                             cancel landed (recovered from the
                             ordered history) */
    /* Fallback execution (engine D4/D7 — this child). */
    int fallback_step;    /* dealpg4_outer_fallback_step */
    int fallback_wedge;   /* the fallback began as the wedge
                             force-termination (the supervisor
                             TERM/grace/KILL precedes the state's
                             death fallback) */
    int64_t fallback_grace_deadline_ms; /* absolute CLOCK_MONOTONIC ms
                                           of the current grace
                                           expiry (0 when idle) */
    int64_t fallback_advance_ms; /* absolute CLOCK_MONOTONIC ms of the
                                    next fallback proof advance (the
                                    FB_PROOF retry cadence on the
                                    loop's own clock; 0 when idle) */
    int fallback_proof_first_pass;
    int64_t fallback_proof_first_pass_ms;
    pid_t fb_survivor_pids[DEALPG4_OUTER_REC_SURVIVOR_VIEW_MAX];
    int64_t fb_survivor_seen_ms[DEALPG4_OUTER_REC_SURVIVOR_VIEW_MAX];
    size_t fb_survivor_count;
    int supervisor_dead; /* the nested supervisor's death was
                            observed (the reap match): the
                            death-fallback initiation defers
                            until the channel's buffered
                            nested-origin records are consumed,
                            so a supervisor's own last
                            publications are never discarded */
    int queued_ack;       /* an ACK relay write is queued-but-
                             unwritten into the nested channel */
    int queued_cancel;    /* a CANCEL relay write is queued-but-
                             unwritten into the nested channel */
    dealpg4_outer_writeq ctrl_q; /* the ACK/CANCEL relay queue */
    unsigned char *ctrl_q_arena; /* heap arena (bounded: at most one
                                    catalog control line) */
    unsigned char *ctrl_rbuf; /* heap read buffer (the largest
                                nested-origin line + slack) */
    size_t ctrl_rbuf_len;
};

typedef struct dealpg4_outer_record dealpg4_outer_record;

/* Registry total bound (an implementation bound, distinct from the
 * pinned 128-live cap): live and forked records are bounded at
 * DEALPG4_OUTER_REGISTRY_TOTAL_CAP so the run's memory and the
 * bounded final report stay deterministic. Terminal-at-insertion
 * pre-fork rejection records are exempt from the bound: the
 * unconditional post-cutoff REJECT <id> <tag> BUDGET_EXHAUSTED answer
 * and every pre-cutoff semantic rejection keep their immediate
 * terminal FAILED record at every registry size (the array grows
 * separately for them — D4/D5 review rule). At the bound a
 * further live/forked insert is answered REJECT <id> <tag>
 * REGISTRY_FULL with the immediate terminal FAILED record (the
 * rejection record itself is exempt). The component suite scales the
 * bound through -DDEALPG4_OUTER_REGISTRY_TOTAL_CAP to exercise the
 * exact boundary; production keeps 4096. */
#ifndef DEALPG4_OUTER_REGISTRY_TOTAL_CAP
#define DEALPG4_OUTER_REGISTRY_TOTAL_CAP 4096
#endif

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
    uint64_t timer_expiry_count; /* timerfd expirations drained by the
                                    event loop (the deadline-driven
                                    wakeup counter — the observable of
                                    the ppoll blocking discipline) */
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
    int readiness_timeout_fired; /* READINESS_TIMEOUT decided (D5/D9):
                                    the FEATURE_READY bound expiry
                                    with a verified COORD_READY */
    int readiness_discharged;   /* a broker close before the live
                                   phase (AUTH_FAILED /
                                   PROTOCOL_ERROR / EOF) discharged
                                   the FEATURE_READY obligation —
                                   the D5 aftermath escalation at the
                                   pinned escalation deadline owns the
                                   coordinator, never
                                   READINESS_TIMEOUT */
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

    /* Broker channel (engine D5). */
    int broker_listen_fd;   /* -1 until bound */
    int broker_conn_fd;     /* -1 until the one connection is
                               accepted */
    int broker_accepted_any; /* the one connection was accepted at
                                the socket level (further connections
                                are rejected) */
    int broker_closed;      /* no connection open (the channel is
                               closed) */
    int broker_eof;         /* read-side EOF observed (the D8
                               discrimination slot) */
    int broker_created;     /* bind/listen completed */
    int broker_path_computed;
    char broker_path[PATH_MAX];
    int broker_state;       /* dealpg4_outer_broker_state */
    dealpg4_expectation_set broker_expected;
    dealpg4_outer_writeq broker_q;
    int broker_q_eagain;    /* the most recent flush attempt hit
                               EAGAIN (POLLOUT not ready) with data
                               pending */
    int broker_peer_verified;
    int broker_hello_ok_sent;
    int broker_ready_acked;
    int broker_conns_rejected;
    int broker_socket_unlinked;
    size_t broker_rbuf_len; /* bytes buffered without a complete
                               record line */
    /* Broker stall rule (D5). */
    int stall_armed;         /* the stall deadline is currently armed */
    int stall_ever_armed;    /* it was armed at least once */
    int stall_fired;         /* BROKER_STALLED decided */
    int64_t stall_deadline_ms; /* absolute CLOCK_MONOTONIC */
    int64_t stall_arm_ms;      /* absolute CLOCK_MONOTONIC arm time */

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

    /* Registry (engine D2/D3/D4 — the registry child). */
    dealpg4_outer_record *records; /* dynamically grown; retained until
                                      the final report */
    size_t nrecords;
    size_t records_cap;
    int64_t next_invocation_id; /* outer-assigned, monotonic, unique
                                   (counter from 1) */
    int records_live;           /* pre-terminal (live) records */
    int records_failed;         /* terminal FAILED records */
    int records_clean;          /* terminal CLEAN records */
    char self_argv0[PATH_MAX + 1]; /* the outer's own argv[0] (the
                                      exact string the shell used) —
                                      the serve argv[0] of the D3 fork
                                      surface, captured at core entry
                                      from the process argv[0] the
                                      dispatch noted at startup */
    int self_argv0_ok;

    /* DONE trigger + cutoff (engine D5/D9). */
    int done_queued;    /* the trigger fired exactly once */
    int done_clean;     /* the verdict computed at the queueing moment */
    int64_t done_ms;    /* t0o-relative queueing time */
    int cutoff_cancelled; /* the cutoff expiry marked live records
                             CANCELLING */
    int64_t cutoff_mark_ms; /* t0o-relative */
    int caller_loss_marked; /* a broker close/EOF marked live records
                               CANCELLING (the caller-loss mark) */

    /* Nested control-channel machine (engine D4 — this child). */
    int nested_protocol_errors;
    int nested_terminal_relays;
    int synthesized_terminals;
    int ack_write_completions;
    int queued_write_discards;
    int nested_deaths_observed;
    int stub_ready_forwarded;
    int stub_verify_failures;
    int nested_rejects;
    int wedge_terminations;   /* wedge-rule TERM-by-pid dispatches */
    int fallback_completions; /* fallback executions completed (the
                                 synthesized terminal record) */
    int cancel_fanout_writes; /* total-cancel CANCEL fan-out writes
                                 queued */
} dealpg4_outer_state;

/* In-process observability (outer.h contract). */
static dealpg4_outer_result dealpg4_outer_last_result_view;
static dealpg4_drain_ctx dealpg4_outer_last_drain_stdout;
static dealpg4_drain_ctx dealpg4_outer_last_drain_stderr;

/* Registry observability: the live core state during a core call (the
 * spawn-seam compositions read it to prove register-before-fork), and
 * the most recent call's heap snapshot afterwards (valid until the
 * next core call). The core is single-threaded: the composition's
 * fork_nested runs on the same thread, so the live access is safe. */
static dealpg4_outer_state *dealpg4_outer_live_state;
static dealpg4_outer_record_view *dealpg4_outer_registry_snapshot;
static size_t dealpg4_outer_registry_snapshot_count;

/* The process argv[0] the dispatch noted at startup (the exact string
 * the shell used to start the launcher) — the serve argv[0] of
 * the D3 fork surface. Noted by launcher-main (the single process
 * entry, so every mode — including the selftest battery's
 * forked scenario processes — delivers it) and by the outer
 * mode entry; the core captures it at core entry. Never a /proc
 * resolution: argv[0] is the pinned structural surface. */
static const char *dealpg4_outer_process_argv0;

void dealpg4_outer_note_process_argv0(const char *argv0)
{
    /* Idempotent: the first non-NULL non-empty note wins (argv[0] is
     * immutable for the process lifetime). */
    if (dealpg4_outer_process_argv0 == NULL && argv0 != NULL
        && argv0[0] != '\0')
        dealpg4_outer_process_argv0 = argv0;
}

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

/* === Registry helpers (defined after the broker machine) =============== */

static void dealpg4_outer_mark_live_cancelling(dealpg4_outer_state *st,
                                               int caller_loss);
static void dealpg4_outer_eval_done_trigger(dealpg4_outer_state *st);
/* Channel-machine forward declarations (defined in the nested
 * control-channel section below): the record-transition machinery
 * reconfigures the per-state expectation set, and the STUB_READY
 * re-verification reads the /proc identity helper defined later. */
static void dealpg4_outer_channel_configure(dealpg4_outer_record *r);
static void dealpg4_outer_channel_cancel_write(dealpg4_outer_state *st,
                                               dealpg4_outer_record *r);
static int dealpg4_proc_stat_identity(pid_t pid, pid_t *ppid,
                                      pid_t *pgrp, pid_t *session);
static void dealpg4_outer_broker_invoke(dealpg4_outer_state *st,
                                        const dealpg4_parsed *parsed);
static void dealpg4_outer_broker_ack(dealpg4_outer_state *st,
                                     const dealpg4_parsed *parsed);
static void dealpg4_outer_broker_cancel(dealpg4_outer_state *st,
                                        const dealpg4_parsed *parsed);

/* === Broker socket mechanics + authentication (engine D1/D5) =========== */

/* The real socket-setup surface (outer.h contract): one code path
 * shared by the core and the component tests. */
int dealpg4_outer_broker_bind_path(const char *socket_dir,
                                   const char outer_nonce[33],
                                   char *path_out, size_t path_cap,
                                   int *listen_fd)
{
    struct sockaddr_un sun;
    struct stat sb;
    char path[PATH_MAX];
    int n;
    int fd = -1;

    if (socket_dir == NULL || socket_dir[0] == '\0' || outer_nonce == NULL
        || !dealpg4_nonce_is_valid(outer_nonce) || listen_fd == NULL
        || (path_out != NULL && path_cap == 0)) {
        errno = EINVAL;
        return -1;
    }
    *listen_fd = -1;

    /* The socket dir: mkdir 0700 tolerating EEXIST for an existing
     * directory, then chmod 0700 so the mode is exact regardless of
     * the creating umask. */
    if (mkdir(socket_dir, 0700) != 0 && errno != EEXIST)
        return -1;
    if (stat(socket_dir, &sb) != 0 || !S_ISDIR(sb.st_mode)) {
        errno = ENOTDIR;
        return -1;
    }
    if (chmod(socket_dir, 0700) != 0)
        return -1;

    /* The per-run path: build/.dealpg4-broker-<outerNonce>.sock (the
     * same shape the coordinator child exports via DEALPG4_BROKER_PATH
     * — D1/D5). */
    n = snprintf(path, sizeof path, "%s/.dealpg4-broker-%s.sock",
                 socket_dir, outer_nonce);
    if (n <= 0 || (size_t)n >= sizeof path
        || (size_t)n >= sizeof sun.sun_path) {
        errno = ENAMETOOLONG;
        return -1;
    }

    /* Stale same-name path unlinked before bind (D1/D5). */
    if (unlink(path) != 0 && errno != ENOENT)
        return -1;

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    /* Fd hygiene: O_NONBLOCK (accept never blocks) and FD_CLOEXEC
     * (never inherited across an exec). */
    {
        int fl = fcntl(fd, F_GETFL);

        if (fl == -1 || fcntl(fd, F_SETFL, fl | O_NONBLOCK) == -1)
            goto fail;
        if (fcntl(fd, F_SETFD, FD_CLOEXEC) == -1)
            goto fail;
    }

    memset(&sun, 0, sizeof sun);
    sun.sun_family = AF_UNIX;
    memcpy(sun.sun_path, path, (size_t)n + 1);

    /* FI_OUTER_BIND: a scripted nonzero value forces the bind failure
     * with the scripted value as errno (0 = the real bind). */
    {
        int injected = dealpg4_fi_hooks.fail(FI_OUTER_BIND);

        if (injected != 0) {
            errno = injected;
            goto fail;
        }
    }
    if (bind(fd, (struct sockaddr *)&sun, sizeof sun) != 0)
        goto fail;

    /* chmod 0600 immediately after bind, before listen (D1/D5). */
    if (chmod(path, 0600) != 0)
        goto fail;
    if (listen(fd, 8) != 0)
        goto fail;

    if (path_out != NULL) {
        if (path_cap < (size_t)n + 1) {
            errno = ENOBUFS;
            goto fail;
        }
        memcpy(path_out, path, (size_t)n + 1);
    }
    *listen_fd = fd;
    return 0;

fail:
    {
        int err = errno;

        close(fd);
        (void)unlink(path); /* the stale path was already unlinked;
                               this removes the half-created socket */
        errno = err;
        return -1;
    }
}

/* Accept one broker connection and verify SO_PEERCRED before any
 * record (outer.h contract; the production accept path). */
int dealpg4_outer_broker_accept_peer(int listen_fd, pid_t coordinator_pid,
                                     uid_t expected_uid, int *conn_fd)
{
    struct ucred cred;
    socklen_t cred_len = sizeof cred;
    int fd;

    if (conn_fd == NULL || listen_fd < 0) {
        errno = EINVAL;
        return -1;
    }
    *conn_fd = -1;
    fd = accept(listen_fd, NULL, NULL);
    if (fd < 0)
        return -1; /* EAGAIN = nothing pending (the normal outcome
                      on the non-blocking listen socket) */
    /* Fd hygiene on the accepted connection: O_NONBLOCK (record reads
     * and relay writes never block) and FD_CLOEXEC (never inherited
     * across an exec). */
    {
        int fl = fcntl(fd, F_GETFL);

        if (fl == -1 || fcntl(fd, F_SETFL, fl | O_NONBLOCK) == -1)
            goto fail;
        if (fcntl(fd, F_SETFD, FD_CLOEXEC) == -1)
            goto fail;
    }
    /* SO_PEERCRED before any record (parent D5): pid ==
     * coordinatorPid AND uid == getuid(). */
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &cred_len) != 0
        || cred_len != sizeof cred || cred.pid != coordinator_pid
        || cred.uid != expected_uid) {
        close(fd);
        errno = EACCES;
        return -1;
    }
    *conn_fd = fd;
    return 0;

fail:
    {
        int err = errno;

        close(fd);
        errno = err;
        return -1;
    }
}

/* === Broker channel state machine (engine D5) ========================== */

static void dealpg4_outer_broker_close(dealpg4_outer_state *st);

/* Close the broker channel (idempotent): the connection is closed, the
 * channel state goes terminal, undelivered relay writes are dropped
 * (writeq_clear keeps the overflow consequence — the queued-write
 * discard), and the stall deadline disarms. The listen socket stays
 * open until the final cleanup so any further connection attempt is
 * deterministically rejected. */
static void dealpg4_outer_broker_close(dealpg4_outer_state *st)
{
    /* A broker close before the live phase discharges the
     * FEATURE_READY obligation (D5 aftermath: the coordinator reaps
     * on its own or is terminated at the pinned escalation deadline —
     * never READINESS_TIMEOUT). The discharge keys on the accepted
     * connection (broker_accepted_any — the AUTH_FAILED peer-cred
     * path closes the connection without ever opening conn_fd). */
    if (st->broker_accepted_any && !st->broker_ready_acked)
        st->readiness_discharged = 1;
    if (st->broker_conn_fd >= 0) {
        close(st->broker_conn_fd);
        st->broker_conn_fd = -1;
    }
    st->broker_closed = 1;
    st->broker_state = DEALPG4_OUTER_BROKER_CLOSED;
    st->stall_armed = 0;
    st->broker_q_eagain = 0;
    dealpg4_outer_writeq_clear(&st->broker_q);
    /* The caller-loss mark (D3/D7): every broker close — EOF, HUP,
     * PROTOCOL_ERROR, AUTH_FAILED, BROKER_STALLED — marks every live
     * record CANCELLING (the cancellation execution lands with the
     * fallback child). Vacuous before the first INVOKE. */
    dealpg4_outer_mark_live_cancelling(st, 1 /* caller loss */);
}

/* PROTOCOL_ERROR aftermath (the parent's recorded pin): close the
 * broker, gate nonzero; the coordinator reaps on its own or is
 * terminated at the pinned escalation deadline by the T2 machinery
 * (evaluate's COORDINATOR_HANG trigger — the D8 escalation runs only
 * at the pinned escalation deadline when the coordinator has not
 * exited). */
static void dealpg4_outer_broker_protocol_error(dealpg4_outer_state *st)
{
    dealpg4_outer_gate_token(st, "PROTOCOL_ERROR");
    dealpg4_outer_broker_close(st);
}

/* AUTH_FAILED (peer credentials or the HELLO nonce): connection
 * closed, the readiness obligation discharged, gate nonzero; the
 * ppoll loop keeps running until the coordinator reaps on its own or
 * is terminated at the pinned escalation deadline (D5). */
static void dealpg4_outer_broker_auth_failed(dealpg4_outer_state *st)
{
    dealpg4_outer_gate_token(st, "AUTH_FAILED");
    dealpg4_outer_broker_close(st);
}

/* One broker flush attempt: the FI_CONGEST_BROKER seam (mode
 * BROKER_WRITE_STALL) reports EAGAIN without attempting the syscall
 * (POLLOUT never ready while relay data is pending — arms and fires
 * the stall rule; a native deadline is never suspended), otherwise
 * the real non-blocking flush. The most recent EAGAIN fact feeds the
 * stall arming. */
static int dealpg4_outer_broker_flush(dealpg4_outer_state *st)
{
    if (dealpg4_fi_hooks.congest(FI_CONGEST_BROKER, BROKER_WRITE_STALL,
                                 NULL) != 0) {
        st->broker_q_eagain = 1;
        return 1; /* EAGAIN-equivalent */
    }
    {
        int rc = dealpg4_outer_writeq_flush(&st->broker_q);

        st->broker_q_eagain = (rc == 1);
        return rc;
    }
}

/* Queue one serialized broker record through the non-blocking POLLOUT
 * path (parent D5 write-side contract): catalog-bounded control
 * records never count toward the payload cap; the flush attempt runs
 * immediately and a write-hop loss (EPIPE/error) closes the broker —
 * the D8 discrimination then owns the coordinator reap. */
static void dealpg4_outer_broker_queue_line(dealpg4_outer_state *st,
                                            dealpg4_record_type type,
                                            const dealpg4_field_value *fields,
                                            size_t nfields)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES + 1];
    size_t written = 0;

    if (st->broker_closed)
        return; /* a write to a closed broker is never attempted (the
                   DONE suppression and every late answer path) */
    if (dealpg4_serialize(type, fields, nfields, line, sizeof line,
                          &written) != 0)
        return; /* caller defect: a malformed line is never emitted */
    if (dealpg4_outer_writeq_queue(&st->broker_q, line, written, 1, 0)
        != 0)
        return;
    if (dealpg4_outer_broker_flush(st) < 0)
        dealpg4_outer_broker_close(st); /* write-hop loss */
}

/* Queue one raw serialized record line (a verbatim nested-origin
 * relay) through the non-blocking POLLOUT path: kind 0 payload
 * (an OUT chunk — the 1 MiB per-stream relay cap with the
 * truncation consequence) or kind 1 catalog-bounded control
 * (never dropped by queue pressure). A write to a closed broker
 * is never attempted; the flush attempt runs immediately and a
 * write-hop loss (EPIPE/error) closes the broker. */
static void dealpg4_outer_broker_queue_raw(dealpg4_outer_state *st,
                                           const char *line, size_t len,
                                           int kind, int stream)
{
    if (st->broker_closed)
        return;
    if (dealpg4_outer_writeq_queue(&st->broker_q, line, len, kind,
                                   stream) != 0)
        return; /* a payload overflow carries the truncation
                   consequence on the queue */
    if (dealpg4_outer_broker_flush(st) < 0)
        dealpg4_outer_broker_close(st);
}

/* HELLO_OK 4 <caps> (caps = the stage bitmask 31, selftest.h). */
static void dealpg4_outer_broker_queue_hello_ok(dealpg4_outer_state *st)
{
    char caps[16];
    dealpg4_field_value fields[2];

    snprintf(caps, sizeof caps, "%d", DEALPG4_PROBE_CAPS);
    fields[0].data = "4";
    fields[0].len = 1;
    fields[1].data = caps;
    fields[1].len = strlen(caps);
    dealpg4_outer_broker_queue_line(st, DEALPG4_REC_HELLO_OK, fields, 2);
}

/* READY_ACK <coordinatorNonce>. */
static void dealpg4_outer_broker_queue_ready_ack(dealpg4_outer_state *st)
{
    dealpg4_field_value fields[1];

    fields[0].data = st->coordinator_nonce;
    fields[0].len = DEALPG4_NONCE_HEX_CHARS;
    dealpg4_outer_broker_queue_line(st, DEALPG4_REC_READY_ACK, fields, 1);
}

/* One complete broker record line (the segment includes the LF). The
 * uniform framing-level split: every parse defect (unparseable fields,
 * non-hex/odd-length hex, field-count violations, CR anywhere,
 * unknown record types, oversize records — INVOKE <= 131072 bytes
 * with <= 65536 raw argv bytes, OUT <= 65536 hex chars, every other
 * record <= 8192 bytes) classifies PROTOCOL_ERROR and closes the
 * broker; a parse-OK record outside the current channel-state
 * expectation set likewise. The two handshake rows are fully
 * implemented here; the live-phase rows land with the registry child
 * (at this stage the BROKER_LIVE expectation set is empty). */
static void dealpg4_outer_broker_record(dealpg4_outer_state *st,
                                        const char *line, size_t len)
{
    static char linebuf[DEALPG4_MAX_LINE_INVOKE_BYTES + 1];
    dealpg4_parsed parsed;
    dealpg4_parse_status ps;
    dealpg4_classification cls;
    const dealpg4_field_slice *f;

    if (len == 0 || len > sizeof linebuf - 1) {
        dealpg4_outer_broker_protocol_error(st);
        return;
    }
    memcpy(linebuf, line, len);
    linebuf[len] = '\0';

    ps = dealpg4_parse(linebuf, len, &parsed);
    if (ps != DEALPG4_PARSE_OK) {
        /* Framing defect: PROTOCOL_ERROR close (the canonical split
         * — the record-level semantic handlers are the registry
         * child's). */
        dealpg4_outer_broker_protocol_error(st);
        return;
    }
    cls = dealpg4_expectation_check(&st->broker_expected, &parsed);
    if (cls != DEALPG4_CLASS_OK) {
        /* A record unexpected in the current channel state. */
        dealpg4_outer_broker_protocol_error(st);
        return;
    }
    switch (st->broker_state) {
    case DEALPG4_OUTER_BROKER_AWAIT_HELLO:
        /* HELLO only (the expectation set enforced it). The nonce
         * must equal the env-delivered coordinator nonce; a mismatch
         * is AUTH_FAILED (D5). */
        f = dealpg4_parsed_field(&parsed, 0);
        if (f == NULL || f->len != DEALPG4_NONCE_HEX_CHARS
            || memcmp(f->p, st->coordinator_nonce, f->len) != 0) {
            dealpg4_outer_broker_auth_failed(st);
            return;
        }
        dealpg4_outer_broker_queue_hello_ok(st);
        if (!st->broker_closed) {
            st->broker_hello_ok_sent = 1;
            st->broker_state = DEALPG4_OUTER_BROKER_AWAIT_READY;
            dealpg4_expectation_set_init(&st->broker_expected);
            dealpg4_expectation_set_add(&st->broker_expected,
                                        DEALPG4_REC_FEATURE_READY);
        }
        break;
    case DEALPG4_OUTER_BROKER_AWAIT_READY:
        /* FEATURE_READY only, and only after HELLO_OK. The nonce
         * must equal the coordinator nonce — a mismatch or any other
         * record is PROTOCOL_ERROR (D5). */
        f = dealpg4_parsed_field(&parsed, 0);
        if (f == NULL || f->len != DEALPG4_NONCE_HEX_CHARS
            || memcmp(f->p, st->coordinator_nonce, f->len) != 0) {
            dealpg4_outer_broker_protocol_error(st);
            return;
        }
        dealpg4_outer_broker_queue_ready_ack(st);
        /* The transition to BROKER_LIVE occurs exactly after the
         * READY_ACK write is queued (D5); the live phase expects
         * INVOKE / ACK / CANCEL — BYE and every other record are
         * state-unexpected -> PROTOCOL_ERROR (the frame pins
         * DONE -> BYE). */
        st->broker_ready_acked = 1;
        if (!st->broker_closed) {
            st->broker_state = DEALPG4_OUTER_BROKER_LIVE;
            dealpg4_expectation_set_init(&st->broker_expected);
            dealpg4_expectation_set_add(&st->broker_expected,
                                        DEALPG4_REC_INVOKE);
            dealpg4_expectation_set_add(&st->broker_expected,
                                        DEALPG4_REC_ACK);
            dealpg4_expectation_set_add(&st->broker_expected,
                                        DEALPG4_REC_CANCEL);
        }
        break;
    case DEALPG4_OUTER_BROKER_LIVE:
    case DEALPG4_OUTER_BROKER_POST_DONE:
        /* The live-phase and post-DONE rows (D5): the expectation set
         * already rejected every state-unexpected record (BYE in
         * BROKER_LIVE included). */
        switch (parsed.type) {
        case DEALPG4_REC_INVOKE:
            dealpg4_outer_broker_invoke(st, &parsed);
            break;
        case DEALPG4_REC_ACK:
            dealpg4_outer_broker_ack(st, &parsed);
            break;
        case DEALPG4_REC_CANCEL:
            dealpg4_outer_broker_cancel(st, &parsed);
            break;
        case DEALPG4_REC_BYE:
            /* BROKER_POST_DONE only (the expectation set rejected it
             * in BROKER_LIVE): BYE is accepted and the outer then
             * closes its end of the broker connection (D5). */
            dealpg4_outer_broker_close(st);
            break;
        default:
            /* Unreachable: the expectation set rejected it above. */
            dealpg4_outer_broker_protocol_error(st);
            break;
        }
        break;
    default:
        dealpg4_outer_broker_protocol_error(st);
        break;
    }
}

/* Non-blocking record reads: accumulate complete LF-terminated record
 * lines, enforce the global record bound while buffering (a line that
 * exceeds the largest record cap without an LF is PROTOCOL_ERROR),
 * process each line through the state machine, and apply the EOF
 * decision (EOF in BROKER_LIVE is the accepted channel event feeding
 * the D8 discrimination slot — at this stage, with zero records, EOF
 * plus a reaped status-0 coordinator proceeds through the final
 * proof). A read error other than EAGAIN/EINTR is the hop loss: the
 * broker closes and the D8 discrimination owns the coordinator reap. */
static void dealpg4_outer_broker_read(dealpg4_outer_state *st)
{
    /* One slack byte keeps the read size >= 1 while len stays under
     * the record bound, so a full buffer never turns a zero-size read
     * into a false EOF. */
    static unsigned char rbuf[DEALPG4_MAX_LINE_INVOKE_BYTES + 2];
    size_t len = st->broker_rbuf_len;

    if (st->broker_conn_fd < 0)
        return;
    for (;;) {
        /* Complete lines first (arrival order). */
        for (;;) {
            unsigned char *nl = memchr(rbuf, '\n', len);
            size_t seg;

            if (nl == NULL)
                break;
            seg = (size_t)(nl - rbuf) + 1;
            dealpg4_outer_broker_record(st, (const char *)rbuf, seg);
            if (st->broker_closed) {
                st->broker_rbuf_len = 0;
                return;
            }
            memmove(rbuf, rbuf + seg, len - seg);
            len -= seg;
        }
        /* Oversize: the buffered bytes hold no LF, and no valid
         * record line can reach DEALPG4_MAX_LINE_INVOKE_BYTES without
         * one (the per-type caps are re-enforced by dealpg4_parse
         * once a line completes). */
        if (len >= DEALPG4_MAX_LINE_INVOKE_BYTES) {
            dealpg4_outer_broker_protocol_error(st);
            st->broker_rbuf_len = 0;
            return;
        }
        {
            ssize_t r = read(st->broker_conn_fd, rbuf + len,
                             sizeof rbuf - 1 - len);

            if (r > 0) {
                len += (size_t)r;
                continue;
            }
            if (r < 0 && errno == EINTR)
                continue;
            if (r == 0) {
                st->broker_eof = 1;
                /* The D8 discrimination slot: broker EOF/HUP with
                 * live records is COORDINATOR_LOST (the total-cancel
                 * trigger) — including a coordinator that exits 0
                 * without BYE while records are live. */
                if (st->records_live > 0)
                    dealpg4_outer_gate_token(st, "COORDINATOR_LOST");
                st->broker_rbuf_len = 0;
                dealpg4_outer_broker_close(st);
                return;
            }
            if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
                break;
            /* Read error: the hop is lost — close and let the D8
             * discrimination own the reap. */
            st->broker_rbuf_len = 0;
            dealpg4_outer_broker_close(st);
            return;
        }
    }
    st->broker_rbuf_len = len;
}

/* Accept one connection: the first accepted connection becomes the
 * broker channel (SO_PEERCRED verified before any record; a peer
 * mismatch is AUTH_FAILED with the connection closed); every further
 * connection is rejected — accepted and closed without a read
 * (exactly one connection, D5). */
static void dealpg4_outer_broker_accept(dealpg4_outer_state *st)
{
    if (st->broker_listen_fd < 0)
        return;
    for (;;) {
        int fd;
        int rc;

        if (st->broker_accepted_any) {
            /* Every further connection is rejected without a read
             * (exactly one connection, D5) — before any credential
             * check: the rejection does not depend on who connected,
             * so a credential-failing second connection can never
             * produce an AUTH_FAILED decision. */
            fd = accept(st->broker_listen_fd, NULL, NULL);
            if (fd < 0) {
                if (errno == EINTR)
                    continue;
                return; /* EAGAIN / ECONNABORTED / genuine failure:
                           nothing more to accept right now */
            }
            close(fd);
            st->broker_conns_rejected++;
            continue;
        }
        rc = dealpg4_outer_broker_accept_peer(st->broker_listen_fd,
                                              st->coordinator_pid,
                                              getuid(), &fd);
        if (rc != 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK
                || errno == EINTR || errno == ECONNABORTED)
                return; /* nothing pending / a raced-away queued
                           connection */
            if (errno == EACCES) {
                /* Peer verification failed (pid or uid mismatch):
                 * AUTH_FAILED, connection closed, gate nonzero. The
                 * connection was accepted at the socket level (the
                 * accept syscall succeeded) — exactly one connection
                 * is ever accepted. */
                st->broker_accepted_any = 1;
                dealpg4_outer_broker_auth_failed(st);
                return;
            }
            /* Genuine accept-surface failure: fail closed with the
             * broker bind token (the broker surface is unusable). */
            dealpg4_outer_gate_token(st, "BROKER_BIND_FAILED");
            dealpg4_outer_broker_close(st);
            return;
        }
        st->broker_accepted_any = 1;
        st->broker_conn_fd = fd;
        st->broker_closed = 0;
        st->broker_peer_verified = 1;
        st->broker_q.fd = fd;
        /* The channel state stays BROKER_AWAIT_HELLO (set at start);
         * the expectation set expects exactly HELLO. */
    }
}

/* Broker setup before the coordinator fork (D5): the real socket
 * mechanics (0700 dir, stale unlink, 0600 bind, listen) plus the
 * channel-state and write-queue initialization. Returns 0 on success,
 * -1 on failure (the caller's BROKER_BIND_FAILED path — no fork, no
 * socket left behind). */
static int dealpg4_outer_broker_start(dealpg4_outer_state *st)
{
    static unsigned char q_arena[DEALPG4_OUTER_WRITEQ_ARENA_BYTES];

    st->broker_listen_fd = -1;
    if (dealpg4_outer_broker_bind_path(st->socket_dir, st->outer_nonce,
                                       st->broker_path,
                                       sizeof st->broker_path,
                                       &st->broker_listen_fd) != 0)
        return -1;
    st->broker_path_computed = 1;
    st->broker_created = 1;
    st->broker_closed = 1; /* no connection open yet */
    st->broker_state = DEALPG4_OUTER_BROKER_AWAIT_HELLO;
    dealpg4_expectation_set_init(&st->broker_expected);
    dealpg4_expectation_set_add(&st->broker_expected, DEALPG4_REC_HELLO);
    dealpg4_outer_writeq_init(&st->broker_q, -1, q_arena, sizeof q_arena);
    if (st->nwriteqs < DEALPG4_OUTER_MAX_WRITEQ_HOPS) {
        st->writeqs[st->nwriteqs] = &st->broker_q;
        st->nwriteqs++;
    }
    st->broker_rbuf_len = 0;
    return 0;
}

/* === Registry (engine D2/D3/D4; outer-coordinator-and-broker D2-D4) ==== */

/* Stable state names for the record surface. */
static const char *dealpg4_outer_record_state_name(int state)
{
    static const char *const names[] = {
        "FORKING", "STUB_BLOCKED", "TARGET_PUBLISHED", "RELEASED",
        "CANCELLING", "CLEAN", "FAILED"
    };

    if (state < DEALPG4_OUTER_REC_FORKING
        || state > DEALPG4_OUTER_REC_FAILED)
        return "?";
    return names[state];
}

/* The live (pre-terminal) states (parent D2): FORKING / STUB_BLOCKED /
 * TARGET_PUBLISHED / RELEASED / CANCELLING. */
static int dealpg4_outer_record_live_state(int state)
{
    return state == DEALPG4_OUTER_REC_FORKING
           || state == DEALPG4_OUTER_REC_STUB_BLOCKED
           || state == DEALPG4_OUTER_REC_TARGET_PUBLISHED
           || state == DEALPG4_OUTER_REC_RELEASED
           || state == DEALPG4_OUTER_REC_CANCELLING;
}

/* Registry lookup by invocation id. */
static dealpg4_outer_record *dealpg4_outer_find_record(
    dealpg4_outer_state *st, int64_t invocation_id)
{
    size_t i;

    for (i = 0; i < st->nrecords; i++) {
        if (st->records[i].invocation_id == invocation_id)
            return &st->records[i];
    }
    return NULL;
}

/* One state transition: the ordered history appends the new state with
 * the monotonic timestamp, the live/terminal counters adjust exactly
 * on live -> terminal edges, and the state changes. */
static void dealpg4_outer_record_transition(dealpg4_outer_state *st,
                                            dealpg4_outer_record *r,
                                            int new_state, int64_t now)
{
    int was_live = dealpg4_outer_record_live_state(r->state);
    int is_live = dealpg4_outer_record_live_state(new_state);

    /* The pre-cancel state (engine D4): the state the record was
     * in when the cancel landed, recovered from the ordered
     * history — the CANCELLING-state expectation set and the
     * pre-cancel-state death fallback consume it. */
    if (new_state == DEALPG4_OUTER_REC_CANCELLING
        && r->state != DEALPG4_OUTER_REC_CANCELLING)
        r->pre_cancel_state = r->state;
    if (r->history_count < DEALPG4_OUTER_HISTORY_MAX) {
        r->history[r->history_count].state = new_state;
        r->history[r->history_count].at_ms = now;
        r->history_count++;
    }
    r->state = new_state;
    /* The per-state expectation set reconfigures on every
     * transition (the channel machine consumes it on the next
     * nested-origin record). */
    dealpg4_outer_channel_configure(r);
    if (was_live && !is_live) {
        st->records_live--;
        if (new_state == DEALPG4_OUTER_REC_FAILED)
            st->records_failed++;
        if (new_state == DEALPG4_OUTER_REC_CLEAN)
            st->records_clean++;
    }
}

/* Insert one registry record (never deleted before the final report).
 * The tag bytes are copied to the heap (exact — INVOKED/REJECT echo
 * them verbatim); the array grows up to the total bound. The bound
 * covers live and forked records only: terminal-at-insertion pre-fork
 * rejection records (initial_state FAILED) are exempt and grow the
 * array past the bound, so the unconditional post-cutoff answer and
 * every pre-fork semantic rejection keep their immediate terminal
 * FAILED record at every registry size. Returns NULL at the
 * live/forked total bound or on an allocation failure. */
static dealpg4_outer_record *dealpg4_outer_insert_record(
    dealpg4_outer_state *st, int64_t invocation_id,
    const char *tag_bytes, size_t tag_len, const char nonce[33],
    int initial_state, int64_t deadline_ms, int64_t deadline_abs_ms,
    const char *failure_token, int64_t now)
{
    dealpg4_outer_record *r;
    char *tag;

    if (st->nrecords >= DEALPG4_OUTER_REGISTRY_TOTAL_CAP
        && initial_state != DEALPG4_OUTER_REC_FAILED)
        return NULL;
    if (st->nrecords == st->records_cap) {
        size_t new_cap = st->records_cap == 0 ? 16 : st->records_cap * 2;
        dealpg4_outer_record *grown;

        if (initial_state != DEALPG4_OUTER_REC_FAILED
            && new_cap > DEALPG4_OUTER_REGISTRY_TOTAL_CAP)
            new_cap = DEALPG4_OUTER_REGISTRY_TOTAL_CAP;
        grown = realloc(st->records, new_cap * sizeof *grown);
        if (grown == NULL)
            return NULL;
        st->records = grown;
        st->records_cap = new_cap;
    }
    tag = malloc(tag_len + 1);
    if (tag == NULL)
        return NULL;
    memcpy(tag, tag_bytes, tag_len);
    tag[tag_len] = '\0';

    r = &st->records[st->nrecords];
    memset(r, 0, sizeof *r);
    r->invocation_id = invocation_id;
    r->client_tag = tag;
    memcpy(r->nonce, nonce, DEALPG4_NONCE_HEX_CHARS);
    r->nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    r->supervisor_pid = -1;
    r->control_fd = -1;
    r->stub_pid = -1;
    r->state = initial_state;
    r->deadline_ms = deadline_ms;
    r->deadline_abs_ms = deadline_abs_ms;
    if (failure_token != NULL)
        snprintf(r->failure_token, sizeof r->failure_token, "%s",
                 failure_token);
    r->history[r->history_count].state = initial_state;
    r->history[r->history_count].at_ms = now;
    r->history_count = 1;
    st->nrecords++;
    if (dealpg4_outer_record_live_state(initial_state))
        st->records_live++;
    if (initial_state == DEALPG4_OUTER_REC_FAILED)
        st->records_failed++;
    if (initial_state == DEALPG4_OUTER_REC_CLEAN)
        st->records_clean++;
    return r;
}

/* The caller-loss / cutoff mark (one-shot per cause): every live
 * record becomes CANCELLING and the total-cancel CANCEL fan-out
 * relays the CANCEL with the exact registry nonce into every open
 * control channel (parent D7 — the parallel cancellation; the
 * non-blocking POLLOUT write queue, never a blocking write). A
 * closed channel needs no write: the record's fallback owns the
 * termination. The per-record deadline is the earlier of the record
 * deadline and the outer reserve — every record's deadline lands
 * inside the cutoff by construction (D9). */
static void dealpg4_outer_mark_live_cancelling(dealpg4_outer_state *st,
                                               int caller_loss)
{
    size_t i;
    int64_t now = (int64_t)dealpg4_now_ms();

    if (caller_loss) {
        if (st->caller_loss_marked)
            return;
        st->caller_loss_marked = 1;
    }
    for (i = 0; i < st->nrecords; i++) {
        dealpg4_outer_record *r = &st->records[i];

        if (!dealpg4_outer_record_live_state(r->state)
            || r->state == DEALPG4_OUTER_REC_CANCELLING)
            continue;
        dealpg4_outer_record_transition(st, r,
                                        DEALPG4_OUTER_REC_CANCELLING,
                                        now);
        if (r->control_fd >= 0) {
            /* The fan-out relay (the queued-but-unwritten ACK drops
             * with it — the D4 relay rules). */
            dealpg4_outer_channel_cancel_write(st, r);
            st->cancel_fanout_writes++;
        }
    }
}

/* The cutoff-anchored DONE emission trigger (engine D5): evaluated on
 * the cutoff expiry and on every record's terminal transition. DONE is
 * queued exactly once, and only when (a) the INVOKE acceptance cutoff
 * T0o + nestedStopMs has passed and (b) the registry is non-empty and
 * every record is terminal — never before the cutoff (not on a
 * fully-terminal registry, not at READY_ACK, not between sequential
 * terminal states), never on an empty registry. Every registry
 * record's terminal answer precedes DONE in the broker write order:
 * the record whose terminality completes the registry has its answer
 * (the pre-fork REJECT, the nested-origin CLEAN/FAILED relay, or the
 * outer-synthesized terminal record) queued before the trigger is
 * evaluated, so DONE lands behind it in the queue. The verdict is
 * computed over the registry at the queueing moment: clean iff every
 * record is CLEAN success or clean cancelled, failed otherwise — a
 * post-DONE INVOKE's FAILED record does not re-emit DONE and the
 * verdict stands as queued. DONE goes through the non-blocking
 * POLLOUT path and is suppressed when the broker is already closed
 * (COORDINATOR_LOST / PROTOCOL_ERROR paths); the emission decision is
 * still recorded once. */
static void dealpg4_outer_eval_done_trigger(dealpg4_outer_state *st)
{
    int64_t now = (int64_t)dealpg4_now_ms();
    size_t i;
    int clean = 1;
    dealpg4_field_value fields[1];
    const char *verdict;

    if (st->done_queued)
        return;
    if (now < st->dl.invokeCutoff)
        return;
    if (st->nrecords == 0)
        return;
    for (i = 0; i < st->nrecords; i++) {
        const dealpg4_outer_record *r = &st->records[i];

        if (r->state != DEALPG4_OUTER_REC_CLEAN
            && r->state != DEALPG4_OUTER_REC_FAILED)
            return; /* a live record: DONE waits for its terminality */
        if (r->state == DEALPG4_OUTER_REC_FAILED)
            clean = 0;
    }
    st->done_queued = 1;
    st->done_clean = clean;
    st->done_ms = now - st->t0o;
    if (st->broker_closed)
        return; /* suppressed: the broker is already closed */
    verdict = clean ? "clean" : "failed";
    fields[0].data = verdict;
    fields[0].len = strlen(verdict);
    dealpg4_outer_broker_queue_line(st, DEALPG4_REC_DONE, fields, 1);
    if (!st->broker_closed) {
        st->broker_state = DEALPG4_OUTER_BROKER_POST_DONE;
        dealpg4_expectation_set_init(&st->broker_expected);
        dealpg4_expectation_set_add(&st->broker_expected,
                                    DEALPG4_REC_BYE);
        dealpg4_expectation_set_add(&st->broker_expected,
                                    DEALPG4_REC_INVOKE);
        dealpg4_expectation_set_add(&st->broker_expected,
                                    DEALPG4_REC_ACK);
        dealpg4_expectation_set_add(&st->broker_expected,
                                    DEALPG4_REC_CANCEL);
    }
}

/* One REJECT answer over the broker (record-level; the broker stays
 * open). */
static void dealpg4_outer_reject_answer(dealpg4_outer_state *st,
                                        int64_t id, const char *tag,
                                        const char *token)
{
    char idbuf[24];
    dealpg4_field_value fields[3];
    int n;

    n = snprintf(idbuf, sizeof idbuf, "%lld", (long long)id);
    if (n <= 0 || (size_t)n >= sizeof idbuf)
        return;
    fields[0].data = idbuf;
    fields[0].len = (size_t)n;
    fields[1].data = tag;
    fields[1].len = strlen(tag);
    fields[2].data = token;
    fields[2].len = strlen(token);
    dealpg4_outer_broker_queue_line(st, DEALPG4_REC_REJECT, fields, 3);
}

/* Duplicate the INVOKE tag slice (heap, exact bytes); NULL on
 * allocation failure. */
static char *dealpg4_outer_dup_tag(const dealpg4_field_slice *tag)
{
    char *buf;

    if (tag == NULL)
        return NULL;
    buf = malloc(tag->len + 1);
    if (buf == NULL)
        return NULL;
    memcpy(buf, tag->p, tag->len);
    buf[tag->len] = '\0';
    return buf;
}

/* One pre-fork rejection (engine D3/D4): an immediate terminal FAILED
 * record with the failureToken — no supervisorPid, no control channel,
 * no stub, no fork, no fallback work — answered REJECT <id> <tag>
 * <token> over the open broker. The DONE trigger is then evaluated
 * (the terminal answer is queued first, so it precedes DONE). */
static void dealpg4_outer_reject_pre_fork(dealpg4_outer_state *st,
                                          int64_t id,
                                          const dealpg4_field_slice *tag,
                                          const char *token)
{
    static const char empty_nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    dealpg4_outer_record *r;
    int64_t now = (int64_t)dealpg4_now_ms();

    r = dealpg4_outer_insert_record(st, id, tag->p, tag->len,
                                    empty_nonce,
                                    DEALPG4_OUTER_REC_FAILED, 0, 0,
                                    token, now);
    if (r == NULL) {
        /* Only an allocation failure can refuse a terminal rejection
         * record (they are exempt from the registry total bound): the
         * answer still carries the pinned token — never a
         * substitute — and the pinned token is recorded on the
         * gate. */
        char *tagbuf = dealpg4_outer_dup_tag(tag);

        dealpg4_outer_gate_token(st, token);
        dealpg4_outer_reject_answer(st, id,
                                    tagbuf != NULL ? tagbuf : "-",
                                    token);
        free(tagbuf);
        return;
    }
    dealpg4_outer_reject_answer(st, id, r->client_tag, token);
    dealpg4_outer_eval_done_trigger(st);
}


/* The serve argv of the D3 fork surface: [self, "serve", <decodedCwd>,
 * "--", <target-argv...>] with self = the outer's own argv[0] (the
 * exact string the shell used to start the launcher — the same
 * committed binary; captured at core entry, never a /proc
 * resolution), argv[2] the INVOKE cwd field decoded, and the tail
 * the decoded INVOKE argv.
 * The returned argv array and data block are heap-owned; the caller
 * frees them after fork_nested returns (the child side uses them
 * before its exec / _exit — copy-on-write safe because the child
 * never returns into the parent's free). Returns NULL on any decode
 * or allocation failure (the caller's FORK_FAILED path — no fork). */
static char **dealpg4_outer_build_serve_argv(const dealpg4_outer_state *st,
                                             const dealpg4_parsed *parsed,
                                             const unsigned char *cwd_bytes,
                                             size_t cwd_len,
                                             unsigned char **data_out)
{
    size_t argc = parsed->argv_count;
    size_t raw_total = 0;
    size_t self_len;
    char **av;
    unsigned char *data;
    size_t data_cap;
    size_t off;
    size_t i;

    if (!st->self_argv0_ok)
        return NULL;
    if (dealpg4_parsed_argv_raw_total(parsed, &raw_total) != 0)
        return NULL;
    self_len = strlen(st->self_argv0);
    /* argv slots: self, "serve", cwd, "--", argc elements, NULL. */
    av = malloc((argc + 5) * sizeof(char *));
    if (av == NULL)
        return NULL;
    /* The data block holds self (heap copy — the serve argv strings
     * are never mutated by the child side), cwd, and the decoded
     * target argv. */
    data_cap = self_len + 1 + cwd_len + 1 + raw_total + argc;
    data = malloc(data_cap);
    if (data == NULL) {
        free(av);
        return NULL;
    }
    off = 0;
    memcpy(data + off, st->self_argv0, self_len);
    data[off + self_len] = '\0';
    av[0] = (char *)data + off;
    off += self_len + 1;
    av[1] = (char *)"serve";
    memcpy(data + off, cwd_bytes, cwd_len);
    data[off + cwd_len] = '\0';
    av[2] = (char *)data + off;
    off += cwd_len + 1;
    av[3] = (char *)"--";
    for (i = 0; i < argc; i++) {
        dealpg4_field_slice elem;
        size_t written = 0;

        if (dealpg4_parsed_argv_element(parsed, i, &elem) != 0
            || dealpg4_hex_decode(&elem, data + off, data_cap - off,
                                  &written) != 0) {
            free(av);
            free(data);
            return NULL;
        }
        data[off + written] = '\0';
        av[4 + i] = (char *)data + off;
        off += written + 1;
    }
    av[4 + argc] = NULL;
    *data_out = data;
    return av;
}

/* === Nested control-channel state machine (engine D4 — this child) ===== */

/* The per-record read buffer bound: the largest nested-origin record
 * line is an OUT chunk (DEALPG4_MAX_LINE_OUT_BYTES); a buffered stream
 * that reaches the bound without LF is an oversize framing defect
 * (PROTOCOL_ERROR while the record is live and no outer-side
 * termination is in progress; consumed-and-discarded in drain-only
 * mode). */
#define DEALPG4_OUTER_CTRL_RBUF_BYTES (DEALPG4_MAX_LINE_OUT_BYTES + 2)

/* The per-record relay-queue arena: one catalog-bounded control record
 * (ACK/CANCEL <= DEALPG4_MAX_LINE_OTHER_BYTES) is the only content — a
 * validated CANCEL drops the queued ACK first, so at most one item is
 * ever queued. */
#define DEALPG4_OUTER_CTRLQ_ARENA_BYTES \
    (2 * DEALPG4_MAX_LINE_OTHER_BYTES + 64)

/* Configure the expectation set for the record's current state (the
 * D4 channel-machine table). Terminal states carry an empty set — the
 * drain-only switch owns the channel for them. */
static void dealpg4_outer_channel_configure(dealpg4_outer_record *r)
{
    dealpg4_expectation_set_init(&r->ctrl_expect);
    switch (r->state) {
    case DEALPG4_OUTER_REC_FORKING:
        /* Expects exactly STUB_FORKED (the fork precedes the
         * supervisor's first loop iteration). */
        dealpg4_expectation_set_add(&r->ctrl_expect,
                                    DEALPG4_REC_STUB_FORKED);
        break;
    case DEALPG4_OUTER_REC_STUB_BLOCKED:
        /* The no-STARTED schedule: STUB_FORKED -> [STUB_READY] ->
         * REPORT -> FAILED/CLEAN (supervisor-engine D5(d)). */
        dealpg4_expectation_set_add(&r->ctrl_expect,
                                    DEALPG4_REC_STUB_READY);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REPORT);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_CLEAN);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_FAILED);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REJECT);
        break;
    case DEALPG4_OUTER_REC_TARGET_PUBLISHED:
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REPORT);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_CLEAN);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_FAILED);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REJECT);
        break;
    case DEALPG4_OUTER_REC_RELEASED:
        /* The supervisor-engine D5(d) post-release schedule. */
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_STARTED);
        dealpg4_expectation_set_add(&r->ctrl_expect,
                                    DEALPG4_REC_EXEC_FAILED);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_OUT);
        dealpg4_expectation_set_add(&r->ctrl_expect,
                                    DEALPG4_REC_OUT_END);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REPORT);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_CLEAN);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_FAILED);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REJECT);
        break;
    case DEALPG4_OUTER_REC_CANCELLING:
        /* The supervisor-engine D5(c)/(d) cancel-path schedule —
         * REPORT/CLEAN/FAILED/REJECT — plus, for CANCELLING-from-
         * RELEASED, the post-release relays; STUB_FORKED /
         * STUB_READY may still arrive and are accepted
         * outer-internal only. */
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REPORT);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_CLEAN);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_FAILED);
        dealpg4_expectation_set_add(&r->ctrl_expect, DEALPG4_REC_REJECT);
        dealpg4_expectation_set_add(&r->ctrl_expect,
                                    DEALPG4_REC_STUB_FORKED);
        dealpg4_expectation_set_add(&r->ctrl_expect,
                                    DEALPG4_REC_STUB_READY);
        if (r->pre_cancel_state == DEALPG4_OUTER_REC_RELEASED) {
            dealpg4_expectation_set_add(&r->ctrl_expect,
                                        DEALPG4_REC_STARTED);
            dealpg4_expectation_set_add(&r->ctrl_expect,
                                        DEALPG4_REC_EXEC_FAILED);
            dealpg4_expectation_set_add(&r->ctrl_expect,
                                        DEALPG4_REC_OUT);
            dealpg4_expectation_set_add(&r->ctrl_expect,
                                        DEALPG4_REC_OUT_END);
        }
        break;
    default:
        /* CLEAN / FAILED: the post-terminal drop rule owns the
         * channel (drain-only — no expectation application). */
        break;
    }
}

/* Close the record's control channel: the fd closes, the relay queue
 * is cleared, the pending-write flags reset, and the heap buffers
 * free. The overflow consequence of the queue (irrelevant for the
 * control-only ACK/CANCEL content) survives in the queue context. */
static void dealpg4_outer_channel_close(dealpg4_outer_state *st,
                                        dealpg4_outer_record *r)
{
    (void)st;
    if (r->control_fd >= 0) {
        close(r->control_fd);
        r->control_fd = -1;
    }
    dealpg4_outer_writeq_clear(&r->ctrl_q);
    r->ctrl_q.fd = -1;
    r->queued_ack = 0;
    r->queued_cancel = 0;
    free(r->ctrl_rbuf);
    r->ctrl_rbuf = NULL;
    r->ctrl_rbuf_len = 0;
    free(r->ctrl_q_arena);
    r->ctrl_q_arena = NULL;
    r->ctrl_q.arena = NULL;
    r->ctrl_q.arena_bytes = 0;
}

/* The queued-write discard on terminality (engine D4): every outer
 * write queued-but-unwritten for the record — the ACK relay and the
 * CANCEL fan-out — is discarded without completing it. RELEASED is
 * never entered on a terminal record, no fallback work runs for a
 * terminal record, and the wedge rule never applies to a terminal
 * record. The first-ACK flag is cleared only by this discard of a
 * queued-but-unwritten ACK (a validated CANCEL application) or the
 * terminality discard — never by an ACK whose write completed. */
static void dealpg4_outer_channel_discard_writes(dealpg4_outer_state *st,
                                                 dealpg4_outer_record *r)
{
    if (!dealpg4_outer_writeq_empty(&r->ctrl_q))
        st->queued_write_discards++;
    dealpg4_outer_writeq_clear(&r->ctrl_q);
    if (r->queued_ack) {
        r->queued_ack = 0;
        r->ack_applied = 0;
    }
    if (r->queued_cancel)
        r->queued_cancel = 0;
}

/* The single-terminal-answer switch: once an outer-side termination of
 * the record begins, nested-origin records for that record are
 * consumed but never relayed, never classified, never applied — the
 * channel is drain-only until EOF, then it closes. Idempotent. */
static void dealpg4_outer_channel_begin_termination(
    dealpg4_outer_state *st, dealpg4_outer_record *r)
{
    if (r->channel_drain_only)
        return;
    r->channel_drain_only = 1;
    r->ctrl_rbuf_len = 0; /* buffered lines are consumed-not-relayed
                             from now on */
    dealpg4_outer_channel_discard_writes(st, r);
}

/* Initiate that state's death fallback (the initiation and the
 * drain-only switch are this child's; the fallback execution is this
 * child's executor below). A nested-supervisor death while CANCELLING
 * applies the pre-cancel state's death fallback, recovered from the
 * record's ordered history (the pre_cancel_state retained at the
 * CANCELLING entry). wedge = 1 marks the wedge force-termination (the
 * executor begins with the supervisor TERM/grace/KILL by pid); a
 * death fallback begins directly with the retained-identity signals
 * (the supervisor is already dead). Idempotent. */
static void dealpg4_outer_channel_initiate_fallback(
    dealpg4_outer_state *st, dealpg4_outer_record *r, int wedge)
{
    (void)st;
    if (r->fallback_initiated)
        return;
    r->fallback_initiated = 1;
    r->fallback_wedge = wedge;
    r->fallback_state = r->state == DEALPG4_OUTER_REC_CANCELLING
                            ? r->pre_cancel_state
                            : r->state;
    r->fallback_step = wedge ? DEALPG4_OUTER_FB_TERM_SUPV
                             : DEALPG4_OUTER_FB_TERM_TARGET;
}

/* The deferred death-fallback switch (engine D4): the nested
 * supervisor's death was observed (the reap match set
 * supervisor_dead); the outer-side termination begins the moment the
 * channel's buffered nested-origin records have been consumed — a
 * supervisor that publishes its own terminal record and then dies
 * keeps that record as its single terminal answer (the relay happens
 * while the record is still live), while every record arriving after
 * the channel drained once is consumed but never relayed (the
 * single-terminal-answer rule holds for the whole fallback window).
 * The death fallback's state is the state the record holds when the
 * switch applies (the pre-cancel state for a CANCELLING record). */
static void dealpg4_outer_channel_death_apply(dealpg4_outer_state *st,
                                              dealpg4_outer_record *r)
{
    if (!r->supervisor_dead || !dealpg4_outer_record_live_state(r->state)
        || r->channel_drain_only)
        return;
    dealpg4_outer_channel_begin_termination(st, r);
    dealpg4_outer_channel_initiate_fallback(st, r, 0 /* death */);
}

/* The nested-channel PROTOCOL_ERROR aftermath (engine D4): framing
 * defects, unknown types, oversize records, and records unexpected in
 * the record state — while the record is live and no outer-side
 * termination of it is in progress — close the record's control
 * channel and initiate that state's death fallback (parent D7; the
 * fallback execution lands with the fallback child). The named token
 * is recorded on the gate (the D1 exit-status mapping lists
 * PROTOCOL_ERROR). */
static void dealpg4_outer_channel_protocol_error(
    dealpg4_outer_state *st, dealpg4_outer_record *r)
{
    st->nested_protocol_errors++;
    dealpg4_outer_gate_token(st, "PROTOCOL_ERROR");
    dealpg4_outer_channel_begin_termination(st, r);
    dealpg4_outer_channel_initiate_fallback(st, r, 0 /* death */);
    dealpg4_outer_channel_close(st, r);
}

/* The nested channel's hop loss (EOF/HUP/read error/write EPIPE). On a
 * live record this is the nested supervisor's death observation: the
 * outer-side termination begins (the single-terminal-answer switch)
 * and that state's death fallback is initiated; the channel closes.
 * On a terminal/drain-only record the channel just closes (the
 * post-terminal drop rule — drain to EOF, close). */
static void dealpg4_outer_channel_loss(dealpg4_outer_state *st,
                                       dealpg4_outer_record *r)
{
    if (dealpg4_outer_record_live_state(r->state)
        && !r->channel_drain_only) {
        if (!r->supervisor_dead)
            st->nested_deaths_observed++; /* one observation per
                                             record: the channel-loss
                                             observation and the reap
                                             observation share the
                                             supervisor_dead flag */
        r->supervisor_dead = 1;
        dealpg4_outer_channel_begin_termination(st, r);
        dealpg4_outer_channel_initiate_fallback(st, r, 0 /* death */);
    }
    dealpg4_outer_channel_close(st, r);
}

/* === Per-state fallback execution + wedge rule (engine D4/D7 —
 * this child) ============================================================
 * The D7 exact fallback table, executed as a per-record step machine
 * driven by the per-batch evaluation (the TERM/grace/KILL escalation
 * and the proof loop advance on the event loop's own clock — a grace
 * window is an absolute deadline, never a blocking wait):
 *   FB_TERM_SUPV  — the wedge: TERM the supervisor by pid (re-verified;
 *                   a race that finds it already dead falls through to
 *                   the death-fallback signals without a grace wait);
 *   FB_GRACE_SUPV — the termGraceMs grace window;
 *   FB_KILL_SUPV  — KILL by pid (re-verified), reap it to waitid;
 *   FB_TERM_TARGET / FB_GRACE_TARGET / FB_KILL_TARGET — the state's
 *                   death fallback signals: FORKING nothing (any stub
 *                   dies via PDEATHSIG + release EOF; adopted
 *                   descendants are the proof scan's per-pid
 *                   TERM-then-KILL policy); STUB_BLOCKED the retained
 *                   stubPid directly; TARGET_PUBLISHED the verified
 *                   -pgid (the group holds only the pre-exec stub) and
 *                   the retained stubPid; RELEASED the verified -pgid
 *                   (the retained-identity takeover);
 *   FB_PROOF      — the per-record proof: the reap loop, the
 *                   group/session absence scan against the retained
 *                   identities, the adopted-descendant scan with
 *                   per-pid TERM-then-KILL, and the clean decision
 *                   with the confirming second pass (~10 ms);
 *   FB_DONE       — the record's single terminal answer was
 *                   synthesized exactly once (CLEAN <id> cancelled
 *                   when the proof is clean, otherwise
 *                   FAILED <id> <token>) with cleanupAcknowledged set.
 * No fallback retries: the initiation is idempotent and the steps
 * advance monotonically (the proof loop's repeated passes are the D6
 * fixpoint discipline, not retries).
 */

/* Forward declarations of the later-defined machinery the executor
 * consumes (the global reap loop and the terminal-record synthesis —
 * both defined in the sections below). */
static int dealpg4_outer_reap_all(dealpg4_outer_state *st);
static void dealpg4_outer_channel_synthesize(dealpg4_outer_state *st,
                                             dealpg4_outer_record *r,
                                             int clean,
                                             const char *token);

/* Reap one specific child by pid (the D7 wedge "reap it to waitid"
 * step; belt-and-braces on the global reap loop). */
static void dealpg4_outer_reap_pid(dealpg4_outer_state *st, pid_t pid)
{
    siginfo_t si;

    if (pid <= 0)
        return;
    memset(&si, 0, sizeof si);
    if (waitid(P_PID, pid, &si, WEXITED | WNOHANG) == 0
        && si.si_pid != 0)
        st->reap_count++;
}

/* Signal the death fallback's identities per the fallback state
 * (parent D7). Returns 1 when at least one signal was dispatched (the
 * caller's grace wait), 0 when nothing was alive (no grace wait —
 * the escalation discipline of D8 step 1). */
static int dealpg4_outer_fallback_signal(dealpg4_outer_state *st,
                                         dealpg4_outer_record *r,
                                         int sig, int64_t now)
{
    int sent = 0;

    (void)st;
    (void)now;
    switch (r->fallback_state) {
    case DEALPG4_OUTER_REC_FORKING:
        return 0; /* nothing to signal: the supervisor is dead, and
                     any stub dies via PDEATHSIG + release EOF */
    case DEALPG4_OUTER_REC_STUB_BLOCKED:
        if (r->stub_pid > 0 && kill(r->stub_pid, 0) == 0) {
            (void)kill(r->stub_pid, sig);
            sent = 1;
        }
        return sent;
    case DEALPG4_OUTER_REC_TARGET_PUBLISHED: {
        pid_t pgid = r->target_pgid;

        if (pgid > 0 && kill(-pgid, 0) == 0 && getpgrp() != pgid) {
            (void)kill(-pgid, sig);
            sent = 1;
        }
        if (r->stub_pid > 0 && kill(r->stub_pid, 0) == 0) {
            (void)kill(r->stub_pid, sig);
            sent = 1;
        }
        return sent;
    }
    case DEALPG4_OUTER_REC_RELEASED: {
        pid_t pgid = r->target_pgid;

        if (pgid > 0 && kill(-pgid, 0) == 0 && getpgrp() != pgid) {
            (void)kill(-pgid, sig);
            sent = 1;
        }
        return sent;
    }
    default:
        return 0;
    }
}

/* One /proc pass for a record's fallback proof: every task in the
 * record's retained target group/session and every adopted descendant
 * (ppid == the outer) is recorded and signaled — TERM at discovery,
 * KILL once the discovery grace passed. Known-live identities (the
 * coordinator, every record's supervisor/stub, and the coordinator's
 * own group) are excluded from the adopted attribution: they are not
 * this record's survivors (the coordinator tree is the D8 escalation's
 * and the final proof's). */
static void dealpg4_outer_record_scan(dealpg4_outer_state *st,
                                      dealpg4_outer_record *r,
                                      int64_t now,
                                      int *group_found,
                                      int *session_found,
                                      int *adopted_found)
{
    DIR *dir;
    struct dirent *ent;
    pid_t me = getpid();

    dir = opendir("/proc");
    if (dir == NULL) {
        /* The scan failed: report the conservative findings (the
         * caller's bounded failure synthesis keeps the named token). */
        if (r->target_pgid > 0 || r->target_session_id > 0)
            *group_found = 1;
        *adopted_found = 1;
        return;
    }
    while ((ent = readdir(dir)) != NULL) {
        const char *name = ent->d_name;
        pid_t pid = 0;
        pid_t ppid = 0;
        pid_t pgrp = 0;
        pid_t session = 0;
        size_t i;
        size_t slot;
        int member = 0;
        int adopted = 0;
        int known = 0;

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
        for (i = 0; i < st->nrecords; i++) {
            const dealpg4_outer_record *o = &st->records[i];

            if ((o->supervisor_pid > 0
                 && pid == o->supervisor_pid)
                || (o->stub_pid > 0 && pid == o->stub_pid)) {
                known = 1;
                break;
            }
        }
        if (known)
            continue;
        if (dealpg4_proc_stat_identity(pid, &ppid, &pgrp, &session)
            != 0)
            continue; /* the task raced away */
        if (r->target_pgid > 0 && pgrp == r->target_pgid) {
            member = 1;
            *group_found = 1;
        }
        if (r->target_session_id > 0 && session == r->target_session_id) {
            member = 1;
            *session_found = 1;
        }
        adopted = (ppid == me);
        if (adopted && st->ready_verified && st->coordinator_pgid > 0
            && pgrp == st->coordinator_pgid)
            adopted = 0; /* the coordinator tree — the D8 escalation's
                            scope, not this record's survivor */
        if (!member && !adopted)
            continue;
        if (adopted)
            *adopted_found = 1;
        /* TERM at discovery, KILL once the discovery grace passed
         * (the bounded per-record survivor view). */
        slot = r->fb_survivor_count;
        for (i = 0; i < r->fb_survivor_count; i++) {
            if (r->fb_survivor_pids[i] == pid) {
                slot = i;
                break;
            }
        }
        if (slot == r->fb_survivor_count) {
            if (r->fb_survivor_count < DEALPG4_OUTER_REC_SURVIVOR_VIEW_MAX) {
                r->fb_survivor_pids[slot] = pid;
                r->fb_survivor_seen_ms[slot] = now;
                r->fb_survivor_count++;
            }
        }
        {
            int64_t seen = slot < r->fb_survivor_count
                               ? r->fb_survivor_seen_ms[slot]
                               : now;
            int sig = now >= seen + DEALPG4_LAUNCHER_TERM_GRACE_MS
                          ? SIGKILL
                          : SIGTERM;

            (void)kill(pid, sig);
        }
    }
    closedir(dir);
}

/* One fallback proof pass: the reap loop, the record's
 * group/session/adopted scan, and the clean decision with the
 * confirming second pass after ~10 ms (the supervisor-engine D6
 * two-pass discipline). Returns 1 when the proof is clean — the
 * caller synthesizes CLEAN <id> cancelled; returns 0 while work
 * remains, filling token with the named finding of the current pass
 * (GROUP_SURVIVOR | SESSION_SURVIVOR | ADOPTED_SURVIVOR |
 * ZOMBIE_SURVIVOR) for the caller's bounded failure synthesis. */
static int dealpg4_outer_fallback_proof_pass(
    dealpg4_outer_state *st, dealpg4_outer_record *r, int64_t now,
    char token[DEALPG4_OUTER_TOKEN_VIEW_BYTES])
{
    int group_found = 0;
    int session_found = 0;
    int adopted_found = 0;
    int supv_alive = 0;
    int stub_alive = 0;
    int clean;

    (void)dealpg4_outer_reap_all(st);
    dealpg4_outer_reap_pid(st, r->supervisor_pid);
    dealpg4_outer_reap_pid(st, r->stub_pid);
    dealpg4_outer_record_scan(st, r, now, &group_found, &session_found,
                              &adopted_found);

    if (r->supervisor_pid > 0 && kill(r->supervisor_pid, 0) == 0)
        supv_alive = 1;
    if (r->stub_pid > 0 && kill(r->stub_pid, 0) == 0)
        stub_alive = 1;

    /* The named survivor finding of the current pass. */
    if (group_found)
        snprintf(token, DEALPG4_OUTER_TOKEN_VIEW_BYTES, "GROUP_SURVIVOR");
    else if (session_found)
        snprintf(token, DEALPG4_OUTER_TOKEN_VIEW_BYTES, "SESSION_SURVIVOR");
    else if (adopted_found)
        snprintf(token, DEALPG4_OUTER_TOKEN_VIEW_BYTES, "ADOPTED_SURVIVOR");
    else if (supv_alive || stub_alive)
        snprintf(token, DEALPG4_OUTER_TOKEN_VIEW_BYTES, "ZOMBIE_SURVIVOR");
    else
        token[0] = '\0';

    clean = !group_found && !session_found && !adopted_found
            && !supv_alive && !stub_alive;
    if (!clean) {
        r->fallback_proof_first_pass = 0;
        return 0;
    }
    if (!r->fallback_proof_first_pass) {
        r->fallback_proof_first_pass = 1;
        r->fallback_proof_first_pass_ms = now;
        return 0;
    }
    if (now - r->fallback_proof_first_pass_ms >= 10)
        return 1;
    return 0;
}

/* One fallback-executor advancement (the per-batch step). */
static void dealpg4_outer_fallback_run(dealpg4_outer_state *st,
                                       dealpg4_outer_record *r,
                                       int64_t now)
{
    for (;;) {
        if (!r->fallback_initiated || r->fallback_step == DEALPG4_OUTER_FB_DONE
            || !dealpg4_outer_record_live_state(r->state))
            return;
        switch (r->fallback_step) {
        case DEALPG4_OUTER_FB_TERM_SUPV: {
            pid_t pid = r->supervisor_pid;

            if (pid > 0 && kill(pid, 0) == 0) {
                (void)kill(pid, SIGTERM);
                r->fallback_grace_deadline_ms =
                    now + DEALPG4_LAUNCHER_TERM_GRACE_MS;
                r->fallback_step = DEALPG4_OUTER_FB_GRACE_SUPV;
                return;
            }
            /* A race between the deadline check and the TERM: nothing
             * was signaled — no grace wait. */
            r->fallback_step = DEALPG4_OUTER_FB_KILL_SUPV;
            continue;
        }
        case DEALPG4_OUTER_FB_GRACE_SUPV:
            if (now < r->fallback_grace_deadline_ms)
                return;
            r->fallback_step = DEALPG4_OUTER_FB_KILL_SUPV;
            continue;
        case DEALPG4_OUTER_FB_KILL_SUPV: {
            pid_t pid = r->supervisor_pid;

            if (pid > 0 && kill(pid, 0) == 0)
                (void)kill(pid, SIGKILL);
            dealpg4_outer_reap_pid(st, pid);
            r->fallback_step = DEALPG4_OUTER_FB_TERM_TARGET;
            continue;
        }
        case DEALPG4_OUTER_FB_TERM_TARGET:
            if (dealpg4_outer_fallback_signal(st, r, SIGTERM, now)) {
                r->fallback_grace_deadline_ms =
                    now + DEALPG4_LAUNCHER_TERM_GRACE_MS;
                r->fallback_step = DEALPG4_OUTER_FB_GRACE_TARGET;
                return;
            }
            r->fallback_step = DEALPG4_OUTER_FB_KILL_TARGET;
            continue;
        case DEALPG4_OUTER_FB_GRACE_TARGET:
            if (now < r->fallback_grace_deadline_ms)
                return;
            r->fallback_step = DEALPG4_OUTER_FB_KILL_TARGET;
            continue;
        case DEALPG4_OUTER_FB_KILL_TARGET:
            (void)dealpg4_outer_fallback_signal(st, r, SIGKILL, now);
            r->fallback_proof_first_pass = 0;
            r->fallback_step = DEALPG4_OUTER_FB_PROOF;
            continue;
        case DEALPG4_OUTER_FB_PROOF: {
            char token[DEALPG4_OUTER_TOKEN_VIEW_BYTES];

            if (dealpg4_outer_fallback_proof_pass(st, r, now, token)) {
                /* The proof is clean: the record's single terminal
                 * answer is the synthesized CLEAN <id> cancelled. */
                r->cleanup_acknowledged = 1;
                r->fallback_advance_ms = 0;
                r->fallback_step = DEALPG4_OUTER_FB_DONE;
                st->fallback_completions++;
                dealpg4_outer_channel_synthesize(st, r, 1, NULL);
            } else {
                /* Work remains: the retry cadence deadline drives the
                 * next pass (the two-pass confirm and the per-pid
                 * TERM->KILL escalation advance on the loop's own
                 * clock — a bounded wakeup, never a spin). */
                r->fallback_advance_ms = now + 5;
            }
            return;
        }
        default:
            r->fallback_step = DEALPG4_OUTER_FB_TERM_TARGET;
            continue;
        }
    }
}

/* The total-deadline hard-bound completion: skip the remaining grace
 * windows (the D8 escalation discipline), force the KILL steps, reap,
 * run one final proof pass, and synthesize the record's terminal
 * answer with the facts at hand — CLEAN <id> cancelled when that pass
 * is clean (the zero-survivor proof holds), otherwise
 * FAILED <id> <token> (the named survivor finding). The gate is
 * already nonzero (OVERALL_TIMEOUT); the single pass replaces the
 * two-pass confirm because the run ends now. */
static void dealpg4_outer_fallback_force_complete(
    dealpg4_outer_state *st, dealpg4_outer_record *r, int64_t now)
{
    char token[DEALPG4_OUTER_TOKEN_VIEW_BYTES];

    if (r->fallback_step == DEALPG4_OUTER_FB_TERM_SUPV
        || r->fallback_step == DEALPG4_OUTER_FB_GRACE_SUPV
        || r->fallback_step == DEALPG4_OUTER_FB_KILL_SUPV) {
        pid_t pid = r->supervisor_pid;

        if (pid > 0 && kill(pid, 0) == 0)
            (void)kill(pid, SIGKILL);
        dealpg4_outer_reap_pid(st, pid);
        r->fallback_step = DEALPG4_OUTER_FB_TERM_TARGET;
    }
    (void)dealpg4_outer_fallback_signal(st, r, SIGKILL, now);
    r->fallback_proof_first_pass = 0;
    r->fallback_step = DEALPG4_OUTER_FB_DONE;
    st->fallback_completions++;
    if (dealpg4_outer_fallback_proof_pass(st, r, now, token)) {
        r->cleanup_acknowledged = 1;
        dealpg4_outer_channel_synthesize(st, r, 1, NULL);
    } else {
        dealpg4_outer_channel_synthesize(st, r, 0, token);
    }
}

/* The parent-D6 STUB_READY double verification: the outer re-verifies
 * the stub identity before the coordinator sees it — getpgid(stubPid)
 * == pgid, getsid(stubPid) == sid, kill(stubPid, 0) == 0, the
 * /proc/<stubPid>/stat ppid/pgrp/session fields, nonce equality with
 * the record's invocation nonce and uniqueness across the registry,
 * and stubPid not colliding with any known supervisor/coordinator PID
 * (or the outer itself). The report is the source, the kernel facts
 * are the cross-check. Returns 1 when every check holds. */
static int dealpg4_outer_stub_ready_verify(dealpg4_outer_state *st,
                                           dealpg4_outer_record *r,
                                           const dealpg4_parsed *parsed)
{
    const dealpg4_field_slice *id_f = dealpg4_parsed_field(parsed, 0);
    const dealpg4_field_slice *pid_f = dealpg4_parsed_field(parsed, 1);
    const dealpg4_field_slice *pgid_f = dealpg4_parsed_field(parsed, 2);
    const dealpg4_field_slice *sid_f = dealpg4_parsed_field(parsed, 3);
    const dealpg4_field_slice *nonce_f = dealpg4_parsed_field(parsed, 4);
    int64_t id = 0;
    int64_t pid = 0;
    int64_t pgid = 0;
    int64_t sid = 0;
    pid_t ppid = 0;
    pid_t pgrp = 0;
    pid_t session = 0;
    size_t i;

    if (id_f == NULL || pid_f == NULL || pgid_f == NULL || sid_f == NULL
        || nonce_f == NULL)
        return 0;
    if (dealpg4_field_decimal(id_f, &id) == 0
        || dealpg4_field_decimal(pid_f, &pid) == 0
        || dealpg4_field_decimal(pgid_f, &pgid) == 0
        || dealpg4_field_decimal(sid_f, &sid) == 0)
        return 0;
    if (id != r->invocation_id || pid <= 0 || pgid <= 0 || sid <= 0)
        return 0;
    if ((pid_t)pid == r->supervisor_pid
        || (pid_t)pid == st->coordinator_pid || (pid_t)pid == getpid())
        return 0;
    for (i = 0; i < st->nrecords; i++) {
        const dealpg4_outer_record *o = &st->records[i];

        if ((pid_t)pid == o->supervisor_pid)
            return 0; /* collides with a known supervisor pid */
        if (o != r && o->stub_pid > 0 && (pid_t)pid == o->stub_pid)
            return 0; /* collides with a retained stub pid */
        if (o != r && o->nonce[0] != '\0'
            && memcmp(o->nonce, nonce_f->p, DEALPG4_NONCE_HEX_CHARS) == 0)
            return 0; /* the nonce is not unique across the registry */
    }
    if (nonce_f->len != DEALPG4_NONCE_HEX_CHARS
        || memcmp(nonce_f->p, r->nonce, DEALPG4_NONCE_HEX_CHARS) != 0)
        return 0;
    if (kill((pid_t)pid, 0) != 0)
        return 0;
    if (getpgid((pid_t)pid) != (pid_t)pgid
        || getsid((pid_t)pid) != (pid_t)sid)
        return 0;
    if (dealpg4_proc_stat_identity((pid_t)pid, &ppid, &pgrp, &session)
        != 0)
        return 0;
    if (ppid != r->supervisor_pid || pgrp != (pid_t)pgid
        || session != (pid_t)sid)
        return 0;
    return 1;
}

/* Relay one nested-origin record verbatim to the broker (the relay
 * rules' single canonical mapping): OUT chunks are kind-0 payload on
 * the chunk's stream (the 1 MiB per-stream relay cap with the
 * truncation consequence); every other record is catalog-bounded
 * control (never dropped by queue pressure). */
static void dealpg4_outer_channel_relay(dealpg4_outer_state *st,
                                        const dealpg4_parsed *parsed,
                                        const char *line, size_t len)
{
    int kind = 1;
    int stream = 0;

    if (parsed->type == DEALPG4_REC_OUT) {
        const dealpg4_field_slice *f = dealpg4_parsed_field(parsed, 1);

        kind = 0;
        stream = (f != NULL && f->len == 3 && memcmp(f->p, "out", 3) == 0)
                     ? 0
                     : 1;
    }
    dealpg4_outer_broker_queue_raw(st, line, len, kind, stream);
}

/* A nested-origin terminal record (CLEAN/FAILED) was relayed verbatim
 * as the record's single terminal answer: the queued-but-unwritten
 * ACK/CANCEL writes are discarded without completing them (no RELEASED
 * on a terminal record, no never-started fallback work, no wedge), the
 * record transitions terminal, the channel switches to drain-only
 * (post-terminal drop rule), and the cutoff-anchored DONE trigger is
 * evaluated (the terminal answer was queued first, so it precedes
 * DONE). */
static void dealpg4_outer_channel_terminal(dealpg4_outer_state *st,
                                           dealpg4_outer_record *r,
                                           int clean, int clean_final,
                                           const char *token, int64_t now)
{
    st->nested_terminal_relays++;
    dealpg4_outer_channel_discard_writes(st, r);
    if (clean) {
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_CLEAN,
                                        now);
        r->clean_final = clean_final;
    } else {
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_FAILED,
                                        now);
        snprintf(r->failure_token, sizeof r->failure_token, "%s",
                 token != NULL ? token : "-");
    }
    r->channel_drain_only = 1;
    dealpg4_outer_eval_done_trigger(st);
}

/* The outer-synthesized terminal record (engine D4): the record's
 * single terminal answer when the outer itself terminated it (a
 * state's death fallback or the wedge force-termination). CLEAN
 * <id> cancelled when the fallback's proof is clean, otherwise
 * FAILED <id> <token> (the named survivor finding of that proof).
 * The synthesis is unconditional once the termination began; it is
 * queued through the non-blocking POLLOUT path ahead of DONE,
 * catalog-bounded and never dropped by relay-queue pressure while
 * the broker is open, suppressed only when the broker is already
 * closed. The outer never synthesizes a REPORT. */
static void dealpg4_outer_channel_synthesize(dealpg4_outer_state *st,
                                             dealpg4_outer_record *r,
                                             int clean, const char *token)
{
    int64_t now = (int64_t)dealpg4_now_ms();
    char idbuf[24];
    dealpg4_field_value fields[2];
    int n;

    if (!dealpg4_outer_record_live_state(r->state))
        return; /* the record already terminated exactly once */
    if (!r->channel_drain_only)
        dealpg4_outer_channel_begin_termination(st, r);
    st->synthesized_terminals++;
    n = snprintf(idbuf, sizeof idbuf, "%lld",
                 (long long)r->invocation_id);
    if (n <= 0 || (size_t)n >= sizeof idbuf)
        return;
    fields[0].data = idbuf;
    fields[0].len = (size_t)n;
    if (clean) {
        static const char cancelled_text[] = "cancelled";

        fields[1].data = cancelled_text;
        fields[1].len = sizeof cancelled_text - 1;
        dealpg4_outer_broker_queue_line(st, DEALPG4_REC_CLEAN, fields, 2);
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_CLEAN,
                                        now);
        r->clean_final = 0; /* a synthesized CLEAN is always cancelled */
    } else {
        fields[1].data = token != NULL ? token : "-";
        fields[1].len = strlen(fields[1].data);
        dealpg4_outer_broker_queue_line(st, DEALPG4_REC_FAILED, fields, 2);
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_FAILED,
                                        now);
        snprintf(r->failure_token, sizeof r->failure_token, "%s",
                 fields[1].data);
    }
    r->channel_drain_only = 1;
    dealpg4_outer_eval_done_trigger(st);
}

/* One nested-channel flush attempt: the FI_CONGEST_NESTED_CTRL seam
 * (mode NESTED_WRITE_STALL) reports POLLOUT never ready without
 * attempting the syscall — the queued ACK/CANCEL write stays
 * queued-but-unwritten (the record stays TARGET_PUBLISHED with the
 * never-started fallback invariant); otherwise the real non-blocking
 * flush. When the queue drains the write completed: a queued ACK's
 * completion enters RELEASED exactly at that moment (parent D2/D6);
 * a queued CANCEL's completion needs nothing further (the
 * application happened at queueing). A write-hop loss (EPIPE/error)
 * is the channel loss. */
static void dealpg4_outer_channel_flush(dealpg4_outer_state *st,
                                        dealpg4_outer_record *r)
{
    int rc;

    if (r->control_fd < 0)
        return;
    if (dealpg4_fi_hooks.congest(FI_CONGEST_NESTED_CTRL,
                                 NESTED_WRITE_STALL, NULL) != 0)
        return; /* POLLOUT never ready: the write stays queued */
    rc = dealpg4_outer_writeq_flush(&r->ctrl_q);
    if (rc == 0) {
        if (r->queued_ack) {
            r->queued_ack = 0;
            if (r->state == DEALPG4_OUTER_REC_TARGET_PUBLISHED
                && !r->channel_drain_only) {
                st->ack_write_completions++;
                dealpg4_outer_record_transition(
                    st, r, DEALPG4_OUTER_REC_RELEASED,
                    (int64_t)dealpg4_now_ms());
            }
        }
        if (r->queued_cancel)
            r->queued_cancel = 0;
        return;
    }
    if (rc < 0)
        dealpg4_outer_channel_loss(st, r);
}

/* Queue the ACK relay into the nested control channel (the exact
 * registry nonce) and attempt the flush. The ACK was validated (the
 * full parent-D6 condition with the outer-side state check) and the
 * first-ACK flag set at queueing before this write; RELEASED is
 * entered exactly when this write completes. A closed channel (a
 * fallback already in progress) never queues the write — the record's
 * fallback owns its termination. */
static void dealpg4_outer_channel_ack_write(dealpg4_outer_state *st,
                                            dealpg4_outer_record *r)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES + 1];
    char idbuf[24];
    dealpg4_field_value fields[2];
    size_t written = 0;
    int n;

    if (r->control_fd < 0)
        return;
    n = snprintf(idbuf, sizeof idbuf, "%lld",
                 (long long)r->invocation_id);
    if (n <= 0 || (size_t)n >= sizeof idbuf)
        return;
    fields[0].data = idbuf;
    fields[0].len = (size_t)n;
    fields[1].data = r->nonce;
    fields[1].len = DEALPG4_NONCE_HEX_CHARS;
    if (dealpg4_serialize(DEALPG4_REC_ACK, fields, 2, line, sizeof line,
                          &written) != 0)
        return; /* caller defect: a malformed line is never emitted */
    if (dealpg4_outer_writeq_queue(&r->ctrl_q, line, written, 1, 0) != 0)
        return;
    r->queued_ack = 1;
    dealpg4_outer_channel_flush(st, r);
}

/* Apply a validated coordinator CANCEL (parent D7 + the D4 relay
 * rules): any queued-but-unwritten ACK for the record is dropped (the
 * nested side never receives it; the first-ACK flag is cleared — it
 * survives only an actually-applied ACK), the record is marked
 * CANCELLING (the pre-cancel state is recovered from the ordered
 * history), and the CANCEL is relayed into the nested control channel
 * with the exact registry nonce. The nested side then applies its
 * pre-release/post-release cancel semantics (supervisor-engine D5).
 * The outer never releases directly. */
static void dealpg4_outer_channel_cancel_write(dealpg4_outer_state *st,
                                               dealpg4_outer_record *r)
{
    char line[DEALPG4_MAX_LINE_OTHER_BYTES + 1];
    char idbuf[24];
    dealpg4_field_value fields[2];
    size_t written = 0;
    int n;

    dealpg4_outer_channel_discard_writes(st, r);
    if (r->state != DEALPG4_OUTER_REC_CANCELLING)
        dealpg4_outer_record_transition(st, r,
                                        DEALPG4_OUTER_REC_CANCELLING,
                                        (int64_t)dealpg4_now_ms());
    if (r->control_fd < 0)
        return; /* the channel is already closed: the relay has no
                   destination — the record's fallback owns its
                   termination */
    n = snprintf(idbuf, sizeof idbuf, "%lld",
                 (long long)r->invocation_id);
    if (n <= 0 || (size_t)n >= sizeof idbuf)
        return;
    fields[0].data = idbuf;
    fields[0].len = (size_t)n;
    fields[1].data = r->nonce;
    fields[1].len = DEALPG4_NONCE_HEX_CHARS;
    if (dealpg4_serialize(DEALPG4_REC_CANCEL, fields, 2, line,
                          sizeof line, &written) != 0)
        return;
    if (dealpg4_outer_writeq_queue(&r->ctrl_q, line, written, 1, 0) != 0)
        return;
    r->queued_cancel = 1;
    dealpg4_outer_channel_flush(st, r);
}

/* One complete nested-origin record line (the segment includes the
 * LF). While the record is live and no outer-side termination of it
 * is in progress, every framing defect, unknown type, oversize
 * record, or record unexpected in the record state is
 * PROTOCOL_ERROR: the channel closes and that state's death fallback
 * is initiated. After an outer-side termination began, or after the
 * record is terminal, the single-terminal-answer and post-terminal
 * drop rules own the channel instead (drain-only consumption, no
 * classification). */
static void dealpg4_outer_channel_record(dealpg4_outer_state *st,
                                         dealpg4_outer_record *r,
                                         const char *line, size_t len)
{
    static char linebuf[DEALPG4_MAX_LINE_OUT_BYTES + 1];
    dealpg4_parsed parsed;
    dealpg4_parse_status ps;
    dealpg4_classification cls;

    if (len == 0 || len > sizeof linebuf - 1) {
        dealpg4_outer_channel_protocol_error(st, r);
        return;
    }
    memcpy(linebuf, line, len);
    linebuf[len] = '\0';
    ps = dealpg4_parse(linebuf, len, &parsed);
    if (ps != DEALPG4_PARSE_OK) {
        dealpg4_outer_channel_protocol_error(st, r);
        return;
    }
    cls = dealpg4_expectation_check(&r->ctrl_expect, &parsed);
    if (cls != DEALPG4_CLASS_OK) {
        dealpg4_outer_channel_protocol_error(st, r);
        return;
    }
    switch (parsed.type) {
    case DEALPG4_REC_STUB_FORKED: {
        /* STUB_FORKED <invocationId> <stubPid>: outer-internal —
         * never forwarded (the relay rules' single canonical
         * mapping). FORKING: retains stubPid and enters STUB_BLOCKED
         * (parent D2); CANCELLING: accepted outer-internal —
         * stubPid retained for the fallback, no state change. */
        const dealpg4_field_slice *id_f = dealpg4_parsed_field(&parsed, 0);
        const dealpg4_field_slice *pid_f = dealpg4_parsed_field(&parsed, 1);
        int64_t id = 0;
        int64_t stub = 0;

        if (id_f == NULL || pid_f == NULL
            || dealpg4_field_decimal(id_f, &id) == 0
            || dealpg4_field_decimal(pid_f, &stub) == 0
            || id != r->invocation_id || stub <= 0) {
            dealpg4_outer_channel_protocol_error(st, r);
            return;
        }
        if (r->stub_pid < 0)
            r->stub_pid = (pid_t)stub;
        if (r->state == DEALPG4_OUTER_REC_FORKING)
            dealpg4_outer_record_transition(
                st, r, DEALPG4_OUTER_REC_STUB_BLOCKED,
                (int64_t)dealpg4_now_ms());
        break;
    }
    case DEALPG4_REC_STUB_READY: {
        if (r->state == DEALPG4_OUTER_REC_CANCELLING) {
            /* Accepted outer-internal only — never forwarded, never
             * re-verified, no state change (a CANCELLING record
             * never enters TARGET_PUBLISHED). */
            break;
        }
        /* STUB_BLOCKED (the expectation set admits nothing else):
         * the parent-D6 double verification gates the forward. */
        if (dealpg4_outer_stub_ready_verify(st, r, &parsed)) {
            const dealpg4_field_slice *pgid_f =
                dealpg4_parsed_field(&parsed, 2);
            const dealpg4_field_slice *sid_f =
                dealpg4_parsed_field(&parsed, 3);
            int64_t pgid = 0;
            int64_t sid = 0;

            (void)dealpg4_field_decimal(pgid_f, &pgid);
            (void)dealpg4_field_decimal(sid_f, &sid);
            r->target_pgid = (pid_t)pgid;
            r->target_session_id = (pid_t)sid;
            /* Only then is STUB_READY forwarded (verbatim) and the
             * record enters TARGET_PUBLISHED. */
            st->stub_ready_forwarded++;
            dealpg4_outer_channel_relay(st, &parsed, line, len);
            dealpg4_outer_record_transition(
                st, r, DEALPG4_OUTER_REC_TARGET_PUBLISHED,
                (int64_t)dealpg4_now_ms());
        } else {
            /* A failed re-verification forwards nothing and answers
             * nothing over the broker; the record stays STUB_BLOCKED
             * and the nested supervisor fails the record under its
             * own T1 (FAILED STARTUP_TIMEOUT — relayed verbatim when
             * it arrives); the outer holds the record to its
             * per-record deadline as belt-and-braces (the fallback
             * child's wedge rule). */
            st->stub_verify_failures++;
        }
        break;
    }
    case DEALPG4_REC_REJECT:
        /* A nested REJECT (the CANCEL_AUTH_FAILED answer) indicates
         * supervisor defect — the record is held to its per-record
         * deadline (parent D7). Never forwarded, no state change. */
        st->nested_rejects++;
        break;
    case DEALPG4_REC_STARTED:
        /* STARTED is a relayed observation and does not change state
         * (parent D2). */
        dealpg4_outer_channel_relay(st, &parsed, line, len);
        break;
    case DEALPG4_REC_EXEC_FAILED:
    case DEALPG4_REC_OUT_END:
    case DEALPG4_REC_REPORT:
        dealpg4_outer_channel_relay(st, &parsed, line, len);
        break;
    case DEALPG4_REC_OUT:
        /* OUT chunk caps are enforced by the protocol parse on read;
         * the relay queue caps the payload at the 1 MiB drain budget
         * with the truncation flag (the queue path's overflow
         * consequence). */
        dealpg4_outer_channel_relay(st, &parsed, line, len);
        break;
    case DEALPG4_REC_CLEAN: {
        /* CLEAN <id> success|cancelled: relayed verbatim as the
         * record's single terminal answer (queued ahead of DONE),
         * then the queued-write discard, the terminal transition,
         * and the drain-only switch. */
        const dealpg4_field_slice *f = dealpg4_parsed_field(&parsed, 1);
        int clean_final = (f != NULL && f->len == 7
                           && memcmp(f->p, "success", 7) == 0);

        dealpg4_outer_channel_relay(st, &parsed, line, len);
        dealpg4_outer_channel_terminal(st, r, 1, clean_final, NULL,
                                       (int64_t)dealpg4_now_ms());
        break;
    }
    case DEALPG4_REC_FAILED: {
        /* FAILED <id> <failureToken>: relayed verbatim as the
         * record's single terminal answer, then the queued-write
         * discard, the terminal transition, and the drain-only
         * switch. */
        const dealpg4_field_slice *f = dealpg4_parsed_field(&parsed, 1);
        char token[DEALPG4_OUTER_TOKEN_VIEW_BYTES];

        if (f != NULL) {
            size_t tl = f->len;

            if (tl >= sizeof token)
                tl = sizeof token - 1;
            memcpy(token, f->p, tl);
            token[tl] = '\0';
        } else {
            snprintf(token, sizeof token, "-");
        }
        dealpg4_outer_channel_relay(st, &parsed, line, len);
        dealpg4_outer_channel_terminal(st, r, 0, 0, token,
                                       (int64_t)dealpg4_now_ms());
        break;
    }
    default:
        /* Unreachable: the expectation set admitted only the handled
         * types. */
        dealpg4_outer_channel_protocol_error(st, r);
        break;
    }
}

/* Non-blocking nested-channel reads (one invocation per channel).
 * Complete record lines are consumed as they arrive; the expectation
 * set configured for the record state classifies each line. In
 * drain-only mode (an outer-side termination began, or the record is
 * terminal) every byte is consumed but never classified, applied, or
 * relayed — until EOF, then the channel closes. EOF/HUP/error on a
 * live record is the nested supervisor's loss: the outer-side
 * termination begins (the drain-only switch and the queued-write
 * discard) and that state's death fallback is initiated. */
static void dealpg4_outer_channel_read(dealpg4_outer_state *st,
                                       dealpg4_outer_record *r)
{
    static unsigned char discard[4096];

    if (r->control_fd < 0)
        return;
    for (;;) {
        ssize_t rr;

        if (r->channel_drain_only) {
            /* Consume-not-relay / post-terminal drop: drain and
             * discard until EOF, then close. */
            rr = read(r->control_fd, discard, sizeof discard);
            if (rr > 0)
                continue;
            if (rr < 0 && errno == EINTR)
                continue;
            if (rr < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
                return;
            dealpg4_outer_channel_close(st, r);
            return;
        }
        if (r->ctrl_rbuf_len >= DEALPG4_OUTER_CTRL_RBUF_BYTES - 1) {
            /* A buffered stream reached the largest nested-origin
             * record cap without LF: an oversize framing defect. */
            dealpg4_outer_channel_protocol_error(st, r);
            return;
        }
        rr = read(r->control_fd, r->ctrl_rbuf + r->ctrl_rbuf_len,
                  DEALPG4_OUTER_CTRL_RBUF_BYTES - 1 - r->ctrl_rbuf_len);
        if (rr > 0) {
            unsigned char *nl;

            r->ctrl_rbuf_len += (size_t)rr;
            while ((nl = memchr(r->ctrl_rbuf, '\n', r->ctrl_rbuf_len))
                   != NULL) {
                size_t line_len = (size_t)(nl - r->ctrl_rbuf) + 1;
                size_t rest = r->ctrl_rbuf_len - line_len;

                if (r->channel_drain_only) {
                    /* The record reached terminality (or an
                     * outer-side termination began) while this
                     * buffer was being filled: every buffered byte
                     * from now on is consumed-not-relayed. */
                    r->ctrl_rbuf_len = 0;
                    break;
                }
                dealpg4_outer_channel_record(st, r,
                                             (const char *)r->ctrl_rbuf,
                                             line_len);
                if (r->control_fd < 0)
                    return; /* the channel closed mid-buffer */
                memmove(r->ctrl_rbuf, r->ctrl_rbuf + line_len, rest);
                r->ctrl_rbuf_len = rest;
            }
            continue;
        }
        if (rr < 0 && errno == EINTR)
            continue;
        if (rr < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            /* The channel drained: a reaped supervisor's death
             * fallback begins now — every nested-origin record
             * published before this drain was processed normally
             * (a supervisor's own terminal record is its single
             * terminal answer); every later record is consumed but
             * never relayed. */
            dealpg4_outer_channel_death_apply(st, r);
            return;
        }
        dealpg4_outer_channel_loss(st, r);
        return;
    }
}

/* One INVOKE from the coordinator (BROKER_LIVE / BROKER_POST_DONE).
 * The framing level was enforced by dealpg4_parse (a framing defect is
 * PROTOCOL_ERROR — the canonical split). Well-formed, pre-cutoff:
 * semantic defects -> REJECT <id> <tag> MALFORMED_INVOKE + immediate
 * terminal FAILED record; REGISTRY_FULL at 128 live records -> the
 * record-level REJECT + terminal record; a budget floor T < 15000 ->
 * REJECT <id> <tag> BUDGET_EXHAUSTED + terminal record; valid ->
 * register, fork the nested serve supervisor through the spawn seam,
 * INVOKED <id> <tag>. Well-formed, post-cutoff — before or after
 * DONE — the objective's unconditional answer: REJECT <id> <tag>
 * BUDGET_EXHAUSTED + immediate terminal FAILED record (no channels,
 * no pid, no fork) over the open broker — never PROTOCOL_ERROR, never
 * a broker close. Registration-time failures (nonce source, the
 * control socketpair, the nested fork) terminate the already-inserted
 * record with the FORK_FAILED / NONCE_FAILED pre-fork terminal
 * records (D3 — register-before-fork held: the insertion preceded the
 * failing syscall). */
static void dealpg4_outer_broker_invoke(dealpg4_outer_state *st,
                                        const dealpg4_parsed *parsed)
{
    static unsigned char cwd_buf[DEALPG4_INVOKE_MAX_ARGV_RAW_BYTES + 1];
    const dealpg4_field_slice *tag_f = dealpg4_parsed_field(parsed, 0);
    const dealpg4_field_slice *cwd_f = dealpg4_parsed_field(parsed, 1);
    const dealpg4_field_slice *argc_f = dealpg4_parsed_field(parsed, 2);
    int64_t argc_v = 0;
    int64_t now;
    int64_t budget_t;
    size_t cwd_len = 0;
    int cwd_ok;
    int64_t id;
    dealpg4_outer_record *r;
    char nonce[DEALPG4_NONCE_HEX_CHARS + 1];
    int sv[2];
    char **serve_argv = NULL;
    unsigned char *serve_data = NULL;
    int child_control_fd = -1;
    pid_t nested_pid = -1;

    now = (int64_t)dealpg4_now_ms();
    if (dealpg4_field_decimal(argc_f, &argc_v) == 0)
        return; /* unreachable: parse enforced the decimal class */
    id = st->next_invocation_id++;

    /* The unconditional post-cutoff rule (D5/D9): every well-formed
     * INVOKE arriving at/after T0o + nestedStopMs — before or after
     * DONE — is answered REJECT <id> <tag> BUDGET_EXHAUSTED with an
     * immediate terminal FAILED record over the open broker. The
     * semantic split applies only to the pre-cutoff live phase. */
    if (now >= st->dl.invokeCutoff) {
        dealpg4_outer_reject_pre_fork(st, id, tag_f, "BUDGET_EXHAUSTED");
        return;
    }

    /* Semantic validation (the record-level split): argc 0, argc !=
     * the argv field count, an empty argv, or a cwd whose hex bytes
     * fail UTF-8 decoding or decode to an empty/NUL-containing path. */
    cwd_ok = dealpg4_hex_decode(cwd_f, cwd_buf, sizeof cwd_buf,
                                &cwd_len) == 0;
    if (dealpg4_invoke_semantic_check(
            argc_v, parsed->argv_count,
            cwd_ok ? cwd_buf : (const unsigned char *)"", cwd_ok ? cwd_len : 0)
        != DEALPG4_CLASS_OK) {
        dealpg4_outer_reject_pre_fork(st, id, tag_f,
                                      "MALFORMED_INVOKE");
        return;
    }

    /* REGISTRY_FULL at 128 concurrent live records (parent D2). */
    if (st->records_live >= DEALPG4_OUTER_MAX_LIVE_RECORDS) {
        dealpg4_outer_reject_pre_fork(st, id, tag_f, "REGISTRY_FULL");
        return;
    }

    /* The delivered nested budget (parent D4):
     * T = min(invocationLimits.overallTimeoutMs, nestedStopMs - nowMs)
     * with nowMs = the t0o-relative registration time (now - T0o) —
     * the time remaining until the INVOKE cutoff. T < 15000 ->
     * REJECT <id> <tag> BUDGET_EXHAUSTED, no fork, no retry. */
    budget_t = dealpg4_embedded_launcher_limits.overallTimeoutMs;
    if (st->limits->nestedStopMs - (now - st->t0o) < budget_t)
        budget_t = st->limits->nestedStopMs - (now - st->t0o);
    if (budget_t < DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR) {
        dealpg4_outer_reject_pre_fork(st, id, tag_f, "BUDGET_EXHAUSTED");
        return;
    }

    /* Registration-time nonce generation (artifact D6): the record's
     * invocation nonce, generated at registration via getrandom(2)
     * through dealpg4_nonce_hex. An unrecoverable failure (scripted
     * via the pinned FI_OUTER_NONCE tag or real) inserts the record
     * directly terminal FAILED NONCE_FAILED and answers REJECT <id>
     * <tag> NONCE_FAILED — no pid, no channels, no fork (D3). The
     * production nonce path is unchanged when the site is unscripted. */
    if (dealpg4_fi_hooks.fail(FI_OUTER_NONCE) != 0
        || dealpg4_nonce_hex(nonce) != 0) {
        dealpg4_outer_reject_pre_fork(st, id, tag_f, "NONCE_FAILED");
        return;
    }

    /* Register-before-fork (D3): the record is inserted (state
     * FORKING, nonce and deadline computed) before any failing
     * syscall that could follow — the control socketpair, the nested
     * fork — so the FORK_FAILED terminal record always exists for a
     * socketpair/fork failure. INVOKED is answered only after
     * insertion; a broker EOF arriving mid-INVOKE still registers +
     * forks, then the caller-loss mark applies — never the reverse. */
    r = dealpg4_outer_insert_record(st, id, tag_f->p, tag_f->len, nonce,
                                    DEALPG4_OUTER_REC_FORKING, budget_t,
                                    now + budget_t, NULL, now);
    if (r == NULL) {
        /* The registry total bound for live/forked records: the
         * rejection keeps the pinned pre-fork shape — REJECT <id>
         * <tag> REGISTRY_FULL + the immediate terminal FAILED record
         * (the rejection record itself is exempt from the bound) +
         * the DONE trigger evaluation. */
        dealpg4_outer_reject_pre_fork(st, id, tag_f, "REGISTRY_FULL");
        return;
    }

    /* The per-invocation control channel (D3): one socketpair(AF_UNIX,
     * SOCK_STREAM); the outer end is the registry controlFd with
     * O_NONBLOCK|FD_CLOEXEC; the child end is dup2'd onto fd 0 in the
     * serve child and survives the exec (the serve entry sets
     * O_NONBLOCK|FD_CLOEXEC at entry itself). A failure (scripted via
     * FI_OUTER_SOCKETPAIR or real) terminates the already-inserted
     * record as FAILED <id> FORK_FAILED. */
    {
        int injected = dealpg4_fi_hooks.fail(FI_OUTER_SOCKETPAIR);

        if (injected != 0) {
            errno = injected;
            sv[0] = sv[1] = -1;
        } else if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
            sv[0] = sv[1] = -1;
        }
    }
    if (sv[0] >= 0) {
        int fl = fcntl(sv[0], F_GETFL);

        if (fl == -1 || fcntl(sv[0], F_SETFL, fl | O_NONBLOCK) == -1
            || fcntl(sv[0], F_SETFD, FD_CLOEXEC) == -1) {
            close(sv[0]);
            close(sv[1]);
            sv[0] = sv[1] = -1;
        }
    }
    if (sv[0] < 0) {
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_FAILED,
                                        now);
        snprintf(r->failure_token, sizeof r->failure_token,
                 "FORK_FAILED");
        dealpg4_outer_reject_answer(st, id, r->client_tag, "FORK_FAILED");
        dealpg4_outer_eval_done_trigger(st);
        return;
    }
    child_control_fd = sv[1];

    /* The D3 serve surface: serve argv [self, serve, <decodedCwd>, --,
     * <target-argv...>] with self = the outer's own argv[0] (the same
     * committed binary). */
    serve_argv = dealpg4_outer_build_serve_argv(st, parsed, cwd_buf,
                                                cwd_len, &serve_data);
    if (serve_argv == NULL) {
        close(sv[0]);
        close(sv[1]);
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_FAILED,
                                        now);
        snprintf(r->failure_token, sizeof r->failure_token,
                 "FORK_FAILED");
        dealpg4_outer_reject_answer(st, id, r->client_tag, "FORK_FAILED");
        dealpg4_outer_eval_done_trigger(st);
        return;
    }

    /* The nested fork through the spawn seam (D2): the production
     * fork_nested forks, dup2s the child end onto fd 0, dup2s
     * /dev/null onto fds 1/2, sets the DEALPG4_NONCE /
     * DEALPG4_BUDGET_MS / DEALPG4_INVOCATION_ID env pins child-side,
     * and execvps the serve argv; the battery substitutes the
     * in-process composition. A fork failure (scripted via
     * FI_OUTER_FORK in the production fork_nested, or a composition
     * returning -1) terminates the already-inserted record as
     * FAILED <id> FORK_FAILED — register-before-fork held. */
    {
        const dealpg4_outer_spawn *sp = st->spawn;

        if (sp != NULL && sp->fork_nested != NULL)
            nested_pid = sp->fork_nested(sp, serve_argv, nonce,
                                         budget_t, id, child_control_fd);
        else
            nested_pid = dealpg4_outer_fork_nested(NULL, serve_argv,
                                                   nonce, budget_t, id,
                                                   child_control_fd);
    }
    if (nested_pid < 0) {
        close(sv[0]);
        close(sv[1]);
        free(serve_argv);
        free(serve_data);
        dealpg4_outer_record_transition(st, r, DEALPG4_OUTER_REC_FAILED,
                                        now);
        snprintf(r->failure_token, sizeof r->failure_token,
                 "FORK_FAILED");
        dealpg4_outer_reject_answer(st, id, r->client_tag, "FORK_FAILED");
        dealpg4_outer_eval_done_trigger(st);
        return;
    }

    /* The fork succeeded: supervisorPid attached immediately after
     * fork returns; the parent closes the child end; the outer end is
     * the registry controlFd. */
    r->supervisor_pid = nested_pid;
    r->control_fd = sv[0];
    close(sv[1]);
    free(serve_argv);
    free(serve_data);

    /* The nested control-channel machinery attaches with the
     * channel (engine D4): the heap read buffer (the largest
     * nested-origin line + slack), the ACK/CANCEL relay queue,
     * and the FORKING expectation set. An allocation failure
     * fail-closes the already-inserted record (FORK_FAILED —
     * no usable channel; the spawned child reaps through the
     * event loop). */
    {
        size_t arena_bytes = DEALPG4_OUTER_CTRLQ_ARENA_BYTES;
        unsigned char *arena = malloc(arena_bytes);
        unsigned char *rbuf = malloc(DEALPG4_OUTER_CTRL_RBUF_BYTES);

        if (arena == NULL || rbuf == NULL) {
            free(arena);
            free(rbuf);
            close(sv[0]);
            r->control_fd = -1;
            dealpg4_outer_record_transition(st, r,
                                            DEALPG4_OUTER_REC_FAILED,
                                            now);
            snprintf(r->failure_token, sizeof r->failure_token,
                     "FORK_FAILED");
            dealpg4_outer_reject_answer(st, id, r->client_tag,
                                         "FORK_FAILED");
            dealpg4_outer_eval_done_trigger(st);
            return;
        }
        r->ctrl_q_arena = arena;
        r->ctrl_rbuf = rbuf;
        dealpg4_outer_writeq_init(&r->ctrl_q, sv[0], arena,
                                  arena_bytes);
        dealpg4_outer_channel_configure(r);
    }

    /* INVOKED <id> <tag> — answered only after insertion (D3). */
    {
        char idbuf[24];
        dealpg4_field_value fields[2];
        int n;

        n = snprintf(idbuf, sizeof idbuf, "%lld", (long long)id);
        if (n > 0 && (size_t)n < sizeof idbuf) {
            fields[0].data = idbuf;
            fields[0].len = (size_t)n;
            fields[1].data = r->client_tag;
            fields[1].len = strlen(r->client_tag);
            dealpg4_outer_broker_queue_line(st, DEALPG4_REC_INVOKED,
                                            fields, 2);
        }
    }
}

/* One ACK from the coordinator (BROKER_LIVE / BROKER_POST_DONE).
 * Framing defects were classified PROTOCOL_ERROR by the parse. The
 * record-level rule is the full parent-D6 condition of D5: the ACK
 * applies only when the invocationId names a live record in
 * TARGET_PUBLISHED, the nonce equals that record's invocation nonce,
 * and no ACK was previously applied to that record. Everything else
 * — an unknown invocationId, a terminal record, a nonce mismatch, a
 * second ACK, and a live record in any state other than
 * TARGET_PUBLISHED (FORKING / STUB_BLOCKED / CANCELLING / RELEASED) —
 * is answered REJECT <invocationId> <clientTag> AUTH_FAILED with the
 * broker open, the record untouched, and no relay. The outer enforces
 * the state == TARGET_PUBLISHED check itself:
 * dealpg4_ack_classify's outer facts cover only id-known, record live,
 * nonce match, and first-ACK, so the classifier alone would return OK
 * for the CANCELLING race (a validated CANCEL dropped the
 * queued-but-unwritten ACK, then a matching-nonce ACK arrives with no
 * applied ACK on the record). On a validated ACK the first-ACK field
 * is set at acceptance — the queueing point of the relay, before any
 * write completes (the relay into the nested control channel and
 * RELEASED-at-write-completion are the channel-machine child's; the
 * field is cleared only by a validated CANCEL application or the
 * terminal queued-write discard, likewise the channel-machine
 * child's). */
static void dealpg4_outer_broker_ack(dealpg4_outer_state *st,
                                     const dealpg4_parsed *parsed)
{
    const dealpg4_field_slice *id_f = dealpg4_parsed_field(parsed, 0);
    const dealpg4_field_slice *nonce_f = dealpg4_parsed_field(parsed, 1);
    int64_t id = 0;
    dealpg4_outer_record *r;
    dealpg4_ack_facts facts;
    dealpg4_classification cls;

    if (dealpg4_field_decimal(id_f, &id) == 0)
        return; /* unreachable: parse enforced the decimal class */
    r = dealpg4_outer_find_record(st, id);
    if (r == NULL) {
        /* Unknown invocationId: no record, so no tag — the REJECT
         * carries "-" (the catalog's DASH_OR_TOKEN class). */
        dealpg4_outer_reject_answer(st, id, "-", "AUTH_FAILED");
        return;
    }
    memset(&facts, 0, sizeof facts);
    facts.invocation_id_known = 1;
    facts.record_in_apply_phase = dealpg4_outer_record_live_state(
        r->state);
    facts.nonce_matches = (nonce_f->len == DEALPG4_NONCE_HEX_CHARS
                           && memcmp(nonce_f->p, r->nonce,
                                     DEALPG4_NONCE_HEX_CHARS) == 0);
    facts.first_ack = !r->ack_applied;
    cls = dealpg4_ack_classify(&facts);
    if (cls == DEALPG4_CLASS_OK
        && r->state == DEALPG4_OUTER_REC_TARGET_PUBLISHED) {
        /* Validated and accepted for relay (the outer-side state
         * check held): the first-ACK field is set at queueing, before
         * any write completes, and the ACK relay is queued into the
         * nested control channel (the channel machine's). No broker
         * answer, no state change here (RELEASED is entered exactly
         * when the ACK write completes into the nested control
         * channel). */
        r->ack_applied = 1;
        dealpg4_outer_channel_ack_write(st, r);
        return;
    }
    /* Record-level rejection: the broker stays open, the record is
     * untouched, and no ACK is forwarded — a TARGET_PUBLISHED record
     * then fails under its own nested T1 (FAILED STARTUP_TIMEOUT via
     * the control-channel machine); a CANCELLING record continues its
     * cancel path unchanged. */
    dealpg4_outer_reject_answer(st, id, r->client_tag, "AUTH_FAILED");
}

/* One CANCEL from the coordinator (BROKER_LIVE / BROKER_POST_DONE).
 * Framing defects were classified PROTOCOL_ERROR by the parse. The
 * record-level rule (parent D7): a CANCEL applies only to a live
 * record with the exact invocation nonce. An unknown invocationId, a
 * terminal record, or a nonce mismatch is answered REJECT
 * <invocationId> <clientTag> CANCEL_AUTH_FAILED — the broker stays
 * open, the record is untouched, and every other live record is
 * unaffected. A validated CANCEL is applied by the channel-machine
 * child (marking the record CANCELLING, dropping any
 * queued-but-unwritten ACK for it, and relaying the CANCEL with the
 * exact registry nonce into the nested control channel); the
 * cancellation execution lands with the fallback child. */
static void dealpg4_outer_broker_cancel(dealpg4_outer_state *st,
                                        const dealpg4_parsed *parsed)
{
    const dealpg4_field_slice *id_f = dealpg4_parsed_field(parsed, 0);
    const dealpg4_field_slice *nonce_f = dealpg4_parsed_field(parsed, 1);
    int64_t id = 0;
    dealpg4_outer_record *r;
    dealpg4_cancel_facts facts;
    dealpg4_classification cls;

    if (dealpg4_field_decimal(id_f, &id) == 0)
        return; /* unreachable: parse enforced the decimal class */
    r = dealpg4_outer_find_record(st, id);
    if (r == NULL) {
        dealpg4_outer_reject_answer(st, id, "-", "CANCEL_AUTH_FAILED");
        return;
    }
    memset(&facts, 0, sizeof facts);
    facts.invocation_id_known = 1;
    facts.record_live = dealpg4_outer_record_live_state(r->state);
    facts.nonce_matches = (nonce_f->len == DEALPG4_NONCE_HEX_CHARS
                           && memcmp(nonce_f->p, r->nonce,
                                     DEALPG4_NONCE_HEX_CHARS) == 0);
    cls = dealpg4_cancel_classify(&facts);
    if (cls == DEALPG4_CLASS_OK) {
        /* Validated (parent D7): the channel machine applies it
         * (D4 relay rules) — any queued-but-unwritten ACK for the
         * record is dropped, the record is marked CANCELLING (the
         * pre-cancel state recovered from the ordered history), and
         * the CANCEL is relayed with the exact registry nonce. */
        dealpg4_outer_channel_cancel_write(st, r);
        return;
    }
    dealpg4_outer_reject_answer(st, id, r->client_tag,
                                "CANCEL_AUTH_FAILED");
}

/* === Registry observability (outer.h contract) ========================== */

/* Copy one internal record into the public view. */
static void dealpg4_outer_fill_record_view(const dealpg4_outer_record *r,
                                           dealpg4_outer_record_view *out)
{
    memset(out, 0, sizeof *out);
    out->invocation_id = r->invocation_id;
    snprintf(out->client_tag, sizeof out->client_tag, "%s",
             r->client_tag != NULL ? r->client_tag : "");
    memcpy(out->nonce, r->nonce, sizeof out->nonce);
    out->supervisor_pid = r->supervisor_pid;
    out->control_fd = r->control_fd;
    out->stub_pid = r->stub_pid;
    out->target_pgid = r->target_pgid;
    out->target_session_id = r->target_session_id;
    out->state = r->state;
    out->deadline_ms = r->deadline_ms;
    out->deadline_abs_ms = r->deadline_abs_ms;
    out->ack_applied = r->ack_applied;
    out->cleanup_acknowledged = r->cleanup_acknowledged;
    out->clean_final = r->clean_final;
    out->channel_open = r->control_fd >= 0;
    out->channel_drain_only = r->channel_drain_only;
    out->fallback_initiated = r->fallback_initiated;
    out->fallback_wedge = r->fallback_wedge;
    out->fallback_step = r->fallback_step;
    out->fallback_grace_deadline_ms = r->fallback_grace_deadline_ms;
    out->fallback_state = r->fallback_state;
    out->pre_cancel_state = r->pre_cancel_state;
    out->queued_ack = r->queued_ack;
    out->queued_cancel = r->queued_cancel;
    memcpy(out->failure_token, r->failure_token,
           sizeof out->failure_token);
    out->history_count = r->history_count;
    memcpy(out->history, r->history, sizeof out->history);
}

/* Fill the heap snapshot of the most recent core call (freed at the
 * next core call). */
static void dealpg4_outer_fill_registry_snapshot(dealpg4_outer_state *st)
{
    dealpg4_outer_record_view *snap;
    size_t i;

    free(dealpg4_outer_registry_snapshot);
    dealpg4_outer_registry_snapshot = NULL;
    dealpg4_outer_registry_snapshot_count = 0;
    if (st->nrecords == 0)
        return;
    snap = malloc(st->nrecords * sizeof *snap);
    if (snap == NULL)
        return;
    for (i = 0; i < st->nrecords; i++)
        dealpg4_outer_fill_record_view(&st->records[i], &snap[i]);
    dealpg4_outer_registry_snapshot = snap;
    dealpg4_outer_registry_snapshot_count = st->nrecords;
}

size_t dealpg4_outer_registry_count(void)
{
    if (dealpg4_outer_live_state != NULL)
        return dealpg4_outer_live_state->nrecords;
    return dealpg4_outer_registry_snapshot_count;
}

int dealpg4_outer_registry_record(size_t idx,
                                  dealpg4_outer_record_view *out)
{
    if (out == NULL)
        return -1;
    if (dealpg4_outer_live_state != NULL) {
        if (idx >= dealpg4_outer_live_state->nrecords)
            return -1;
        dealpg4_outer_fill_record_view(
            &dealpg4_outer_live_state->records[idx], out);
        return 0;
    }
    if (idx >= dealpg4_outer_registry_snapshot_count)
        return -1;
    *out = dealpg4_outer_registry_snapshot[idx];
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
     * or derived. The FI_OUTER_ENTRY_NONCE seam forces the failure
     * path (distinct from the pinned FI_OUTER_NONCE registration-time
     * site, D6). */
    if (dealpg4_fi_hooks.fail(FI_OUTER_ENTRY_NONCE) != 0
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
 * deliberately inherited). Used for the pre-exec ready pipe, which is
 * CLOEXEC and never enters the coordinator's fds. */
static void dealpg4_outer_pipe_end_flags(int fd)
{
    int fl = fcntl(fd, F_GETFL);

    if (fl != -1)
        (void)fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    (void)fcntl(fd, F_SETFD, FD_CLOEXEC);
}

/* Set O_NONBLOCK on a coordinator stream drain read end (the drain
 * contract requires O_NONBLOCK read sides; the write ends keep the
 * target's ordinary blocking semantics — the supervisor.c
 * stream-topology pin). */
static void dealpg4_outer_drain_read_nonblock(int fd)
{
    int fl = fcntl(fd, F_GETFL);

    if (fl != -1)
        (void)fcntl(fd, F_SETFL, fl | O_NONBLOCK);
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
    /* The two coordinator stream drain pipes carry the coordinator's
     * stdout/stderr (D1): the pinned stream-topology contract
     * (supervisor.c) keeps the write ends at the target's ordinary
     * blocking semantics and requires O_NONBLOCK only on the read
     * sides — pipe2 sets FD_CLOEXEC on every end (dup2 onto fds 1/2
     * clears it on the inherited coordinator surfaces, and the outer
     * closes the write ends at fork return). */
    if (dealpg4_fi_hooks.fail(FI_OUTER_PIPE) != 0
        || pipe2(out_pipe, O_CLOEXEC) != 0)
        goto abort;
    if (dealpg4_fi_hooks.fail(FI_OUTER_PIPE) != 0
        || pipe2(err_pipe, O_CLOEXEC) != 0)
        goto abort;

    /* The ready pipe keeps O_NONBLOCK on both ends (it is CLOEXEC and
     * never enters the coordinator's fds); the drain read ends become
     * O_NONBLOCK for the continuous pump — the write ends stay
     * blocking, exactly the supervisor.c topology. */
    dealpg4_outer_pipe_end_flags(ready_pipe[0]);
    dealpg4_outer_pipe_end_flags(ready_pipe[1]);
    dealpg4_outer_drain_read_nonblock(out_pipe[0]);
    dealpg4_outer_drain_read_nonblock(err_pipe[0]);

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
 * no-ops (no grace wait — nothing was signaled). One exception: a
 * coordinator that published COORD_EXEC_FAILED announced its own
 * _exit(127) immediately after the publication — the by-pid TERM is
 * deferred by the grace window (escalation_term_abs_ms = now +
 * termGraceMs, KILL at now + 2*termGraceMs) so the child's own exit
 * is deterministically observed (CLD_EXITED 127) and never raced by
 * the signal; a defective child that hangs after the publication is
 * still TERMed (re-verified) and KILLed, bounded. */
static void dealpg4_outer_begin_escalation(dealpg4_outer_state *st,
                                           int group_scope);

static void dealpg4_outer_escalation_term_dispatch(
    dealpg4_outer_state *st, int64_t now);

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

    if (st->coord_exec_failed) {
        /* The child published COORD_EXEC_FAILED immediately
         * before its own _exit(127): the by-pid TERM is deferred
         * by the grace window so the child's own exit is
         * deterministically observed (CLD_EXITED 127) and never
         * raced by the signal; a defective child that hangs
         * after the publication is TERMed at the deferred moment
         * (re-verified) and KILLed after the standard grace. */
        st->escalation_term_abs_ms =
            now + DEALPG4_LAUNCHER_TERM_GRACE_MS;
        st->escalation_kill_ms =
            st->escalation_term_abs_ms
            + DEALPG4_LAUNCHER_TERM_GRACE_MS;
        return;
    }
    /* The D8 precondition (parent D8): the coordinator PGID is TERMed
     * only after every registry record is terminal. The TERM dispatch
     * defers while live records remain (the escalation is active; the
     * KILL grace clock starts at the dispatch moment — evaluate
     * dispatches the TERM the moment the precondition holds). */
    if (st->records_live > 0)
        return;
    dealpg4_outer_escalation_term_dispatch(st, now);
}

/* The D8 step-1 TERM dispatch (by scope), re-verified: group scope —
 * the liveness check kill(-pgid, 0) == 0 with getpgrp() != pgid, then
 * TERM -pgid; pid scope — TERM by pid. The KILL grace clock starts at
 * the dispatch moment; nothing alive at the dispatch runs both steps
 * as no-ops (no grace wait — nothing was signaled). */
static void dealpg4_outer_escalation_term_dispatch(
    dealpg4_outer_state *st, int64_t now)
{
    if (st->escalation_group_scope) {
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
    } else {
        st->escalation_kill_ms = now + DEALPG4_LAUNCHER_TERM_GRACE_MS;
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

/* READINESS_TIMEOUT (engine D5/D9, the split immediate escalation
 * scopes): no FEATURE_READY by T0o + readinessTimeoutMs after a
 * verified COORD_READY. The COORD_READY report was received and
 * cross-checked, so the coordinator group IS verified — the
 * immediate D8 bounded escalation runs with the full group scope
 * against the pipe-published cross-checked coordinatorPgid (step-1
 * liveness check kill(-coordinatorPgid, 0) == 0 with
 * getpgrp() != coordinatorPgid, TERM -pgid, grace termGraceMs,
 * KILL -pgid re-verified, reap to waitid ECHILD, adopted-descendant
 * scan). The pid-only steps-1-2-skipped form never applies here.
 * Gate-fatal. Covers a coordinator that execs and never connects,
 * one that connects and never completes the handshake, and the
 * bootstrap hang between the COORD_READY write and the exec (the
 * pre-exec pipe EOF never arrives — no FEATURE_READY is possible by
 * the deadline either way). The readiness obligation is discharged
 * only by a broker close before the live phase (AUTH_FAILED /
 * PROTOCOL_ERROR / EOF — the D5 aftermath: the coordinator reaps on
 * its own or is terminated at the pinned escalation deadline), so a
 * discharged run never gets this token. */
static void dealpg4_outer_readiness_timeout(dealpg4_outer_state *st)
{
    if (st->readiness_timeout_fired)
        return;
    st->readiness_timeout_fired = 1;
    st->ready_resolved = 1;
    st->ready_verified = 1; /* the COORD_READY cross-check held — the
                               pipe-published pgid is the verified
                               group identity (never dropped: the
                               escalation and the final-proof group
                               item use it) */
    if (st->ready_pipe_rd >= 0) {
        close(st->ready_pipe_rd);
        st->ready_pipe_rd = -1;
    }
    st->ready_buf_len = 0;
    dealpg4_outer_gate_token(st, "READINESS_TIMEOUT");
    if (st->coordinator_pid > 0 && !st->coordinator_reaped
        && !st->escalation_active)
        dealpg4_outer_begin_escalation(st, 1 /* the verified group */);
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
        /* Nested-supervisor death observation (engine D4): a
         * reaped child matching a live record's supervisorPid
         * begins that record's outer-side termination (the
         * single-terminal-answer switch — nested-origin records
         * are consumed but never relayed from this moment) and
         * initiates that state's death fallback (the fallback
         * execution is the fallback child's). The channel itself
         * stays open until EOF (a surviving descendant may hold
         * the write end); the drain-only consumption owns its
         * records. */
        {
            size_t ri;

            for (ri = 0; ri < st->nrecords; ri++) {
                dealpg4_outer_record *r = &st->records[ri];

                if (r->supervisor_pid <= 0
                    || r->supervisor_pid != si.si_pid)
                    continue;
                if (dealpg4_outer_record_live_state(r->state)
                    && !r->supervisor_dead) {
                    /* The death observation (engine D4): every
                     * nested-origin record the supervisor published
                     * before dying is consumed first — the channel
                     * drains here, processing each record against
                     * the live state machine; the drain-only switch
                     * and the death-fallback initiation then apply
                     * the moment the channel is empty (the
                     * death-apply switch in the read path), so a
                     * supervisor's own terminal record is always its
                     * single terminal answer while every record
                     * arriving afterwards is consumed but never
                     * relayed. */
                    r->supervisor_dead = 1;
                    st->nested_deaths_observed++;
                    dealpg4_outer_channel_read(st, r);
                }
                break;
            }
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
            {
                int known = 0;
                size_t ri;

                /* Known registry identities (every record's
                 * supervisor/stub) are never adopted descendants:
                 * they are live registered records owned by the
                 * record machinery (records_live is the separate
                 * final-proof item). Only tasks outside the known
                 * set with ppid == outerPid are reparented
                 * stragglers. */
                for (ri = 0; ri < st->nrecords; ri++) {
                    const dealpg4_outer_record *o = &st->records[ri];

                    if ((o->supervisor_pid > 0
                         && pid == o->supervisor_pid)
                        || (o->stub_pid > 0
                            && pid == o->stub_pid)) {
                        known = 1;
                        break;
                    }
                }
                if (known)
                    continue;
            }
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
    /* (f) the broker connection closed and the socket path unlinked
     * (parent D8): the unlink runs during final cleanup, once the
     * channel is closed — a second connection attempt then finds no
     * rendezvous path. */
    if (st->broker_path_computed && st->broker_closed
        && !st->broker_socket_unlinked) {
        if (unlink(st->broker_path) == 0 || errno == ENOENT)
            st->broker_socket_unlinked = 1;
    }
    st->proof_broker_clean = st->broker_closed
                             && st->broker_socket_unlinked;
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
    /* A failing pass retries on a coarser cadence (the success
     * two-pass confirm interval stays ~5 ms): a live-record run whose
     * cancellation execution has not landed yet (the fallback child's
     * scope) holds the proof failing until the total deadline — the
     * coarser retry keeps the /proc scans bounded. */
    st->proof_next_pass_ms = now + (clean ? 5 : 50);
}

/* === Deadline evaluation and the ppoll loop ============================= */

/* The earliest applicable absolute deadline the single timerfd
 * carries (D9): the readiness deadline (while COORD_READY is
 * pending), the escalation TERM dispatch moment (only while the
 * dispatch precondition holds — every registry record terminal),
 * the escalation KILL grace expiry (only once the TERM was
 * dispatched — the grace clock starts at the dispatch moment), the
 * pinned escalation deadline totalDeadline - killAndProofReserveMs
 * (while the coordinator is alive post-readiness and the D8
 * precondition holds), the proof pass cadence, and the total
 * deadline. Every entry mirrors the per-batch evaluate dispatch
 * precondition exactly, so a deferred step never leaves a stale
 * elapsed deadline in the set: the loop blocks on the earliest
 * applicable future deadline instead of re-arming an elapsed one at
 * 'now' (a hot re-arm at 'now' is not a recipe deadline — the timer
 * would expire immediately every iteration and the loop would
 * busy-spin until the deferral resolves). */
static int64_t dealpg4_outer_next_deadline(dealpg4_outer_state *st)
{
    int64_t d = st->dl.totalDeadline;

    if ((!st->ready_resolved
         || (!st->startup_failed && !st->readiness_timeout_fired
             && !st->readiness_discharged
             && st->ready_line_verified && !st->broker_ready_acked
             && !st->coordinator_reaped && !st->escalation_active))
        && st->dl.readinessDeadline < d)
        d = st->dl.readinessDeadline;
    if (st->escalation_active && !st->escalation_term_sent
        && !st->escalation_kill_issued && st->records_live == 0
        && st->escalation_term_abs_ms < d)
        d = st->escalation_term_abs_ms;
    if (st->escalation_active && st->escalation_term_sent
        && !st->escalation_kill_issued
        && st->escalation_kill_ms < d)
        d = st->escalation_kill_ms;
    if (st->ready_resolved && !st->startup_failed
        && !st->coordinator_reaped && !st->escalation_active
        && st->records_live == 0
        && st->escalation_deadline < d)
        d = st->escalation_deadline;
    if (st->proof_active && st->proof_next_pass_ms > 0
        && st->proof_next_pass_ms < d)
        d = st->proof_next_pass_ms;
    if (st->stall_armed && st->stall_deadline_ms < d)
        d = st->stall_deadline_ms;
    /* Per-record deadlines (the D7 wedge trigger) and the fallback
     * step deadlines (the D7 TERM->grace->KILL escalation and the
     * FB_PROOF retry cadence). The per-record deadline leaves the set
     * the moment the fallback initiates — the wedge dispatches at the
     * deadline and a death fallback before the deadline is owned by
     * the fallback machine from then on — so an elapsed per-record
     * deadline never re-arms the timerfd at 'now' for the whole
     * fallback window. */
    {
        size_t i;

        for (i = 0; i < st->nrecords; i++) {
            const dealpg4_outer_record *r = &st->records[i];

            if (!dealpg4_outer_record_live_state(r->state))
                continue;
            if (!r->fallback_initiated) {
                if (r->deadline_abs_ms > 0
                    && r->deadline_abs_ms < d)
                    d = r->deadline_abs_ms;
                continue;
            }
            if ((r->fallback_step == DEALPG4_OUTER_FB_GRACE_SUPV
                 || r->fallback_step
                        == DEALPG4_OUTER_FB_GRACE_TARGET)
                && r->fallback_grace_deadline_ms > 0
                && r->fallback_grace_deadline_ms < d)
                d = r->fallback_grace_deadline_ms;
            if (r->fallback_advance_ms > 0
                && r->fallback_advance_ms < d)
                d = r->fallback_advance_ms;
        }
    }
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

    /* Deferred pid-scope TERM dispatch (the exec-failed
     * coordinator's own _exit(127) publication is never raced):
     * dispatch at the deferred moment, re-verified, only when the
     * coordinator is still alive — a well-behaved exec-failed child
     * is reaped CLD_EXITED 127 before this moment and is never
     * signaled; a defective child that hangs after COORD_EXEC_FAILED
     * is TERMed here and KILLed at the grace expiry. */
    if (st->escalation_active && !st->escalation_term_sent
        && !st->escalation_kill_issued
        && now >= st->escalation_term_abs_ms
        && st->records_live == 0) {
        /* The deferred TERM dispatch (the exec-failed deferral, or
         * the D8 precondition deferral — the TERM runs only once
         * every registry record is terminal). */
        st->escalation_term_ms = now - st->t0o;
        dealpg4_outer_escalation_term_dispatch(st, now);
    }

    /* Recipe deadline observations (D9). */
    if (now >= st->dl.totalDeadline) {
        st->total_fired = 1;
        st->cutoff_fired = 1;
        st->readiness_fired = 1;
        dealpg4_outer_gate_token(st, "OVERALL_TIMEOUT");
        /* The hard bound (everything completes by T0o +
         * overallTimeoutMs): force the KILL step (skipping any
         * remaining grace — the deadline passed), complete every
         * still-live record with the facts at hand, run one final
         * reap/scan, and terminate. */
        if (!st->coordinator_reaped) {
            if (!st->escalation_active)
                dealpg4_outer_begin_escalation(st, st->ready_verified);
            dealpg4_outer_escalation_kill(st);
        }
        {
            size_t i;

            for (i = 0; i < st->nrecords; i++) {
                dealpg4_outer_record *r = &st->records[i];

                if (!dealpg4_outer_record_live_state(r->state))
                    continue;
                if (!r->fallback_initiated) {
                    dealpg4_outer_channel_begin_termination(st, r);
                    if (r->supervisor_pid > 0
                        && kill(r->supervisor_pid, 0) == 0) {
                        st->wedge_terminations++;
                        dealpg4_outer_channel_initiate_fallback(st, r, 1);
                    } else {
                        dealpg4_outer_channel_initiate_fallback(st, r, 0);
                    }
                }
                dealpg4_outer_fallback_force_complete(st, r, now);
            }
        }
        st->proof_active = 1;
        st->proof_next_pass_ms = now;
        st->done = 1;
        (void)dealpg4_outer_reap_all(st);
        dealpg4_outer_scan_proc(st, now);
        return;
    }
    if (now >= st->dl.invokeCutoff) {
        if (!st->cutoff_fired) {
            /* The INVOKE acceptance cutoff (D9): INVOKEs are accepted
             * only until T0o + nestedStopMs. At the expiry every live
             * record is marked CANCELLING (the cancellation execution
             * lands with the fallback child) and the cutoff-anchored
             * DONE trigger is evaluated — DONE fires here exactly
             * when the registry is non-empty and already fully
             * terminal (the rejection-record case). */
            st->cutoff_fired = 1;
            st->cutoff_cancelled = 1;
            st->cutoff_mark_ms = now - st->t0o;
            dealpg4_outer_mark_live_cancelling(st, 0 /* cutoff */);
            dealpg4_outer_eval_done_trigger(st);
        }
    }
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
        /* The total-cancel trigger (parent D1/D7): every live record
         * CANCELLING with the CANCEL fan-out; the coordinator
         * termination is the bounded D8 escalation, its TERM deferred
         * until every record is terminal (the precondition). */
        dealpg4_outer_mark_live_cancelling(st, 0 /* shell loss */);
        if (!st->coordinator_reaped && !st->escalation_active)
            dealpg4_outer_begin_escalation(st, st->ready_verified);
    }

    /* Readiness resolution (D1/D9 + the D5 split immediate
     * escalation scopes): the readiness deadline T0o +
     * readinessTimeoutMs bounds both the COORD_READY pipe wait and
     * the FEATURE_READY broker handshake. No verified COORD_READY by
     * the deadline is COORDINATOR_STARTUP_FAILED — the group was
     * never verified, so the immediate escalation is by pid with
     * steps 1-2 skipped. A verified COORD_READY without a completed
     * FEATURE_READY handshake by the deadline is READINESS_TIMEOUT —
     * the group IS verified (COORD_READY was cross-checked), so the
     * immediate D8 escalation runs with the full group scope against
     * the pipe-published cross-checked coordinatorPgid. */
    if (now >= st->dl.readinessDeadline
        && !st->startup_failed && !st->readiness_timeout_fired
        && !st->readiness_discharged && !st->coordinator_reaped
        && !st->escalation_active && !st->broker_ready_acked) {
        if (!st->ready_line_verified)
            dealpg4_outer_startup_failed(st);
        else
            dealpg4_outer_readiness_timeout(st);
    }

    /* Escalation progression: the KILL step at the grace expiry
     * (the grace clock starts at the TERM dispatch — a deferred TERM
     * never lets the KILL fire first). */
    if (st->escalation_active && !st->escalation_kill_issued
        && st->escalation_term_sent && now >= st->escalation_kill_ms)
        dealpg4_outer_escalation_kill(st);

    /* The per-record wedge rule + the fallback execution (D7): a live
     * record at its per-record deadline without a terminal
     * CLEAN/FAILED is unresponsive — kill(supervisorPid, 0) is
     * re-verified (an already-dead supervisor applies that state's
     * death fallback directly) and the wedge force-termination begins
     * (TERM by pid -> grace -> KILL -> reap -> the state's death
     * fallback). Every initiated fallback advances one step per
     * batch on the loop's own clock. */
    {
        size_t i;

        for (i = 0; i < st->nrecords; i++) {
            dealpg4_outer_record *r = &st->records[i];

            if (!dealpg4_outer_record_live_state(r->state))
                continue;
            if (!r->fallback_initiated && r->deadline_abs_ms > 0
                && now >= r->deadline_abs_ms) {
                dealpg4_outer_channel_begin_termination(st, r);
                if (r->supervisor_pid > 0
                    && kill(r->supervisor_pid, 0) == 0) {
                    st->wedge_terminations++;
                    dealpg4_outer_channel_initiate_fallback(st, r, 1);
                } else {
                    dealpg4_outer_channel_initiate_fallback(st, r, 0);
                }
            }
            if (r->fallback_initiated)
                dealpg4_outer_fallback_run(st, r, now);
        }
    }

    /* Broker stall rule (D5): the stall deadline arms at
     * now + brokerStallMs while relay data is pending and POLLOUT is
     * not ready (the most recent flush hit EAGAIN), and disarms
     * whenever the relay queue drains. Firing with data still pending
     * is BROKER_STALLED: the D7 total-cancel marking is vacuous at
     * this stage (no live records), the undelivered queue is dropped,
     * the broker closes, and the D8 escalation terminates the
     * coordinator (the escalation begins immediately — the
     * coordinator stopped reading the broker). */
    if (st->stall_armed) {
        if (dealpg4_outer_writeq_empty(&st->broker_q)) {
            st->stall_armed = 0; /* the queue drained */
            st->broker_q_eagain = 0;
        }
    } else if (!st->stall_fired && !dealpg4_outer_writeq_empty(&st->broker_q)
               && st->broker_q_eagain) {
        st->stall_armed = 1;
        st->stall_ever_armed = 1;
        st->stall_arm_ms = now;
        st->stall_deadline_ms = now + st->limits->brokerStallMs;
    }
    if (st->stall_armed && now >= st->stall_deadline_ms) {
        st->stall_armed = 0;
        if (!dealpg4_outer_writeq_empty(&st->broker_q)) {
            st->stall_fired = 1;
            dealpg4_outer_gate_token(st, "BROKER_STALLED");
            dealpg4_outer_writeq_clear(&st->broker_q);
            st->broker_q_eagain = 0;
            dealpg4_outer_broker_close(st);
            if (!st->coordinator_reaped && !st->escalation_active)
                dealpg4_outer_begin_escalation(st, st->ready_verified);
        }
    }

    /* The pinned escalation trigger (D5): the D8 escalation runs only
     * at totalDeadline - killAndProofReserveMs and only when the
     * coordinator has not exited by then — a healthy coordinator that
     * exits 0 after readiness is never signaled (the clean-exit reap
     * precedes the deadline). The escalation never runs before the D8
     * precondition holds (every registry record terminal, parent D8);
     * if the escalation deadline has passed while the total-cancel
     * path is still completing records, the escalation starts the
     * moment the precondition holds, bounded by the total deadline
     * (the records_live == 0 gate — the registry child's completion
     * of the T2 machinery). */
    if (!st->coordinator_reaped && !st->escalation_active
        && st->ready_resolved && !st->startup_failed
        && st->records_live == 0 && now >= st->escalation_deadline) {
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
                     && st->coordinator_si_status == 0
                     && st->records_live == 0))
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
    st->timer_expiry_count += expirations;
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
        nfds_t ctrl_base;
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
        if (st->broker_listen_fd >= 0) {
            pfds[n].fd = st->broker_listen_fd;
            pfds[n].events = POLLIN | POLLERR;
            pfds[n].revents = 0;
            n++;
        }
        if (st->broker_conn_fd >= 0) {
            pfds[n].fd = st->broker_conn_fd;
            pfds[n].events = POLLIN | POLLHUP;
            pfds[n].revents = 0;
            n++;
        }
        /* Nested control channels: one slot per open channel
         * (POLLIN | POLLHUP | POLLERR always; POLLOUT combined
         * when the ACK/CANCEL relay queue is non-empty).
         * Drain-only channels keep their POLLIN slot (consume to
         * EOF, then close). */
        ctrl_base = n;
        for (i = 0; i < st->nrecords && n < DEALPG4_OUTER_POLLFD_MAX;
             i++) {
            dealpg4_outer_record *r = &st->records[i];

            if (r->control_fd < 0)
                continue;
            pfds[n].fd = r->control_fd;
            pfds[n].events = POLLIN | POLLHUP | POLLERR;
            if (!dealpg4_outer_writeq_empty(&r->ctrl_q))
                pfds[n].events |= POLLOUT;
            pfds[n].revents = 0;
            n++;
        }
        /* The first writeq POLLOUT slot index, captured before the
         * slots are appended (the fixed fds and the control
         * channels precede them). */
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
            if (st->broker_listen_fd >= 0) {
                if (pfds[k].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_outer_broker_accept(st);
                k++;
            }
            if (st->broker_conn_fd >= 0) {
                if (pfds[k].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_outer_broker_read(st);
                k++;
            }
        }
        /* Nested control channels (the same record order as the
         * polled slots): reads first (a terminal transition or a
         * channel close may empty the relay queue), then the
         * POLLOUT flush when the queue is still non-empty. */
        {
            nfds_t k = ctrl_base;
            size_t ri;

            for (ri = 0; ri < st->nrecords && k < n; ri++) {
                dealpg4_outer_record *r = &st->records[ri];

                if (r->control_fd < 0)
                    continue;
                if (pfds[k].revents & (POLLIN | POLLHUP | POLLERR))
                    dealpg4_outer_channel_read(st, r);
                if (r->control_fd >= 0 && pfds[k].revents & POLLOUT)
                    dealpg4_outer_channel_flush(st, r);
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
                    int is_broker = (st->writeqs[i] == &st->broker_q);
                    int rc = is_broker
                                 ? dealpg4_outer_broker_flush(st)
                                 : dealpg4_outer_writeq_flush(
                                       st->writeqs[i]);

                    if (rc < 0) {
                        /* Hop loss (EPIPE/error): the queue leaves
                         * the poll set; the record fallback lands
                         * with the channel children. A lost broker
                         * write hop closes the broker (the D8
                         * discrimination then owns the coordinator
                         * reap). */
                        dealpg4_outer_writeq_clear(st->writeqs[i]);
                        if (is_broker)
                            dealpg4_outer_broker_close(st);
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
    /* Reap/adoption counts (parent D8 observability: the report
     * carries the proof's reap/adoption totals next to the proof
     * result). */
    n = snprintf(line, sizeof line,
                 "OUTER counts reaped=%llu adopted=%llu\n",
                 (unsigned long long)st->reap_count,
                 (unsigned long long)st->adopt_count);
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
    /* One line per registry record (retained until the final report,
     * parent D2): id, terminal/live state, tag, and the terminal
     * classification (success/cancelled/failure token/-). The lines
     * flush in batches: the writeq item bound is per flush window, so
     * batching keeps every record listed even past the per-queue item
     * bound (the arena caps the report — best-effort). */
    for (i = 0; i < st->nrecords; i++) {
        const dealpg4_outer_record *r = &st->records[i];
        const char *token = "-";

        if (r->state == DEALPG4_OUTER_REC_FAILED)
            token = r->failure_token;
        else if (r->state == DEALPG4_OUTER_REC_CLEAN)
            token = r->clean_final ? "success" : "cancelled";
        n = snprintf(line, sizeof line, "OUTER record %lld %s %s %s\n",
                     (long long)r->invocation_id,
                     dealpg4_outer_record_state_name(r->state),
                     r->client_tag != NULL ? r->client_tag : "-",
                     token);
        if (n <= 0 || (size_t)n >= sizeof line)
            continue;
        if (dealpg4_outer_writeq_queue(&q, line, (size_t)n, 1, 0) != 0)
            return; /* the bounded arena caps the report (best-effort) */
        if ((i % 64) == 63 || i + 1 == st->nrecords) {
            if (dealpg4_outer_report_flush(st, &q) < 0) {
                st->shell_lost = 1;
                dealpg4_outer_gate_token(st, "SHELL_LOST");
                return;
            }
            dealpg4_outer_writeq_clear(&q);
        }
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
    v.readiness_timeout_fired = st->readiness_timeout_fired;
    v.cutoff_fired = st->cutoff_fired;
    v.total_fired = st->total_fired;
    v.report_flags_captured = st->report_flags_captured;
    v.report_flags_restored = st->report_flags_restored;
    v.gate_failure = st->gate_failure;
    v.exit_status = status;
    v.sigchld_events = st->sigchld_events;
    v.timer_expiry_count = st->timer_expiry_count;
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

    v.broker_created = st->broker_created;
    v.broker_conn_accepted = st->broker_accepted_any;
    v.broker_peer_verified = st->broker_peer_verified;
    v.broker_hello_ok_sent = st->broker_hello_ok_sent;
    v.broker_ready_acked = st->broker_ready_acked;
    v.broker_state = st->broker_state;
    v.broker_closed = st->broker_closed;
    v.broker_eof = st->broker_eof;
    v.broker_socket_unlinked = st->broker_socket_unlinked;
    v.broker_conns_rejected = st->broker_conns_rejected;
    v.broker_stall_armed = st->stall_ever_armed;
    v.broker_stall_fired = st->stall_fired;
    v.broker_stall_arm_ms = st->stall_ever_armed
                                ? st->stall_arm_ms - st->t0o
                                : 0;
    v.broker_stall_deadline_ms = st->stall_ever_armed
                                     ? st->stall_deadline_ms - st->t0o
                                     : 0;
    v.coord_stdout_bytes = st->drain_out.total_read;
    v.coord_stderr_bytes = st->drain_err.total_read;
    v.coord_stdout_truncated = st->drain_out.truncated;
    v.coord_stderr_truncated = st->drain_err.truncated;
    v.coord_stdout_eof = st->drain_out.eof;
    v.coord_stderr_eof = st->drain_err.eof;
    v.reap_count = st->reap_count;
    v.adopt_count = st->adopt_count;

    v.records_total = (int)st->nrecords;
    v.records_live = st->records_live;
    v.records_failed = st->records_failed;
    v.records_clean = st->records_clean;
    v.next_invocation_id = st->next_invocation_id;
    v.cutoff_cancelled = st->cutoff_cancelled;
    v.cutoff_mark_ms = st->cutoff_mark_ms;
    v.caller_loss_marked = st->caller_loss_marked;
    v.done_queued = st->done_queued;
    v.done_clean = st->done_clean;
    v.done_ms = st->done_ms;
    v.nested_protocol_errors = st->nested_protocol_errors;
    v.nested_terminal_relays = st->nested_terminal_relays;
    v.synthesized_terminals = st->synthesized_terminals;
    v.wedge_terminations = st->wedge_terminations;
    v.fallback_completions = st->fallback_completions;
    v.cancel_fanout_writes = st->cancel_fanout_writes;
    v.ack_write_completions = st->ack_write_completions;
    v.queued_write_discards = st->queued_write_discards;
    v.nested_deaths_observed = st->nested_deaths_observed;
    v.stub_ready_forwarded = st->stub_ready_forwarded;
    v.stub_verify_failures = st->stub_verify_failures;
    v.nested_rejects = st->nested_rejects;
    v.broker_relay_overflow = st->broker_q.overflow;
    v.broker_relay_dropped = st->broker_q.dropped_bytes;

    dealpg4_outer_last_result_view = v;
    dealpg4_outer_last_drain_stdout = st->drain_out;
    dealpg4_outer_last_drain_stderr = st->drain_err;
    /* The registry observability hands over from the live state to
     * the retained snapshot of this call. */
    dealpg4_outer_fill_registry_snapshot(st);
    dealpg4_outer_live_state = NULL;
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
    size_t i;

    for (i = 0; i < st->nrecords; i++) {
        if (st->records[i].control_fd >= 0) {
            close(st->records[i].control_fd);
            st->records[i].control_fd = -1;
        }
    }
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
    if (st->broker_conn_fd >= 0) {
        close(st->broker_conn_fd);
        st->broker_conn_fd = -1;
    }
    if (st->broker_listen_fd >= 0) {
        close(st->broker_listen_fd);
        st->broker_listen_fd = -1;
    }
    if (st->broker_path_computed && !st->broker_socket_unlinked)
        st->broker_socket_unlinked = (unlink(st->broker_path) == 0
                                      || errno == ENOENT);
    if (st->sig_fd >= 0) {
        close(st->sig_fd);
        st->sig_fd = -1;
    }
    dealpg4_deadline_close(&st->timer);
    (void)sigprocmask(SIG_SETMASK, &st->entry_mask, NULL);
}

/* Free the registry (records + tags); the retained observability
 * snapshot stays until the next core call. */
static void dealpg4_outer_registry_release(dealpg4_outer_state *st)
{
    size_t i;

    for (i = 0; i < st->nrecords; i++) {
        free(st->records[i].client_tag);
        free(st->records[i].ctrl_rbuf);
        free(st->records[i].ctrl_q_arena);
    }
    free(st->records);
    st->records = NULL;
    st->nrecords = 0;
    st->records_cap = 0;
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
     * preamble leaves a zeroed view and an empty registry snapshot,
     * never the previous call's. */
    memset(&dealpg4_outer_last_result_view, 0,
           sizeof dealpg4_outer_last_result_view);
    dealpg4_drain_init(&dealpg4_outer_last_drain_stdout);
    dealpg4_drain_init(&dealpg4_outer_last_drain_stderr);
    dealpg4_outer_live_state = NULL;
    free(dealpg4_outer_registry_snapshot);
    dealpg4_outer_registry_snapshot = NULL;
    dealpg4_outer_registry_snapshot_count = 0;
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
    st.broker_listen_fd = -1;
    st.broker_conn_fd = -1;

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

    st.next_invocation_id = 1; /* outer-assigned, monotonic, unique —
                                   the counter starts at 1 (the serve
                                   surface requires ids >= 1) */
    st.limits = limits;
    memcpy(st.coordinator_nonce, coordinator_nonce,
           DEALPG4_NONCE_HEX_CHARS);
    st.coordinator_nonce[DEALPG4_NONCE_HEX_CHARS] = '\0';
    st.coordinator_argv = coordinator_argv;
    st.socket_dir = socket_dir;
    st.spawn = spawn;

    /* The serve argv[0] of the D3 fork surface: the outer's own
     * argv[0] (the exact string the shell used to start the
     * launcher), captured at core entry from the process argv[0] the
     * dispatch noted at startup — never a /proc resolution. A
     * missing capture fail-closes later INVOKEs that would fork (the
     * FORK_FAILED path — no fork happens without a serve
     * surface). */
    {
        const char *argv0 = dealpg4_outer_process_argv0;

        if (argv0 != NULL && argv0[0] != '\0') {
            size_t alen = strlen(argv0);

            if (alen < sizeof st.self_argv0) {
                memcpy(st.self_argv0, argv0, alen + 1);
                st.self_argv0_ok = 1;
            }
        }
    }

    /* Registry observability: the live state is visible to the spawn
     * seam compositions for the register-before-fork proof. */
    dealpg4_outer_live_state = &st;

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

    /* Broker socket (D5): the real socket mechanics (0700 dir, stale
     * unlink, 0600 bind, listen) before the coordinator fork — the
     * coordinator child receives the derived path via
     * DEALPG4_BROKER_PATH. A setup failure (injected via
     * FI_OUTER_BIND or real) is fail-closed with no fork: the
     * BROKER_BIND_FAILED token, the unverified-group discharge holds
     * trivially (nothing was ever forked), and the final proof runs. */
    if (dealpg4_outer_broker_start(&st) != 0) {
        st.startup_failed = 1;
        st.ready_resolved = 1;
        st.broker_closed = 1;
        st.broker_socket_unlinked = 1; /* nothing was created, or the
                                          failed setup unlinked its
                                          half-created path */
        dealpg4_outer_gate_token(&st, "BROKER_BIND_FAILED");
        st.proof_active = 1;
        st.proof_next_pass_ms = st.t0o;
    } else if (dealpg4_outer_start_coordinator(&st) != 0) {
        /* Coordinator start (D1): pipes + fork + pre-exec bootstrap.
         * A pipe failure (injected via FI_OUTER_PIPE or real) fails
         * closed with no fork — the unverified-group discharge holds
         * trivially (nothing was ever forked) and the final proof
         * runs. */
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
    outcome.records_total = (int)st.nrecords;
    outcome.records_live = st.records_live;
    outcome.records_failed = st.records_failed;
    outcome.records_clean = st.records_clean;
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
    dealpg4_outer_registry_release(&st);
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

    /* Capture the process argv[0] for the D3 serve surface: the
     * serve argv[0] is the outer's own argv[0] (the exact string the
     * shell used). The dispatch notes it at startup too; the entry
     * note makes the entry self-sufficient for any caller. */
    dealpg4_outer_note_process_argv0(argv[0]);

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
    /* FI_OUTER_FORK: a scripted nonzero value forces the fork failure
     * with the scripted value as errno (0 = the real fork) — the
     * caller's FORK_FAILED pre-fork terminal-record path (D3/D6). */
    {
        int injected = dealpg4_fi_hooks.fail(FI_OUTER_FORK);

        if (injected != 0) {
            errno = injected;
            return -1;
        }
    }
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

/* === Outer-side fallback-termination surface (engine D4) ============= */

int dealpg4_outer_fallback_begin(int64_t invocation_id)
{
    dealpg4_outer_state *st = dealpg4_outer_live_state;
    dealpg4_outer_record *r;

    if (st == NULL) {
        errno = EINVAL;
        return -1;
    }
    r = dealpg4_outer_find_record(st, invocation_id);
    if (r == NULL || !dealpg4_outer_record_live_state(r->state)) {
        errno = ENOENT;
        return -1;
    }
    dealpg4_outer_channel_begin_termination(st, r);
    return 0;
}

int dealpg4_outer_fallback_synthesize(int64_t invocation_id,
                                      int clean, const char *token)
{
    dealpg4_outer_state *st = dealpg4_outer_live_state;
    dealpg4_outer_record *r;

    if (st == NULL) {
        errno = EINVAL;
        return -1;
    }
    r = dealpg4_outer_find_record(st, invocation_id);
    if (r == NULL || !dealpg4_outer_record_live_state(r->state)) {
        errno = ENOENT;
        return -1;
    }
    dealpg4_outer_channel_synthesize(st, r, clean ? 1 : 0, token);
    return 0;
}
