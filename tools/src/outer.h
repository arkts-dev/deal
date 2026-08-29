/*
 * DEALPG4 outer feature supervisor (ISSUE-0293/ISSUE-0294, epic
 * Sequencing steps 1-2): the outer mode entry surface, the in-process
 * outer core entry, the D1 entry preamble, the ppoll loop with the
 * non-blocking write-side discipline, the nested-spawn seam, the
 * exit-status mapping, the coordinator fork with the pre-exec
 * bootstrap (COORD_READY publication + report-first /proc cross-check),
 * the continuous 1 MiB coordinator stream drains, the D8 escalation
 * machinery with the COORDINATOR_HANG escalation-deadline trigger, the
 * COORDINATOR_STARTUP_FAILED by-pid termination scope, the
 * final-proof skeleton, and (ISSUE-0295) the authenticated AF_UNIX
 * broker socket (0700 dir, stale-path unlink, 0600 bind, one
 * SO_PEERCRED-verified connection, FD_CLOEXEC hygiene), the
 * HELLO -> HELLO_OK 4 <caps> -> FEATURE_READY -> READY_ACK handshake
 * channel state machine on the protocol expectation-set mechanism,
 * the uniform framing-level PROTOCOL_ERROR close rule, the broker
 * stall rule (stall deadline = now + brokerStallMs while relay data
 * is pending and POLLOUT is not ready; BROKER_STALLED on expiry),
 * and the PROTOCOL_ERROR/AUTH_FAILED aftermath (deadline-bounded D8
 * escalation through the T2 machinery).
 *
 * This child (ISSUE-0296, epic Sequencing steps 4-5) adds: the
 * SupervisorRecord registry with register-before-fork (parent D2/D3
 * record shape, invocation-id/nonce/deadline assignment, ordered
 * state history, the first-ACK tracking field for the D6 validation,
 * <= 128 concurrent live records, records retained until the final
 * report); the serve-surface nested fork through the
 * dealpg4_outer_spawn seam (per-invocation AF_UNIX socketpair control
 * channel with the outer end O_NONBLOCK|FD_CLOEXEC and the child end
 * dup2'd onto fd 0, serve argv [self, serve, <decodedCwd>, --,
 * <target-argv...>] with self = /proc/self/exe, the DEALPG4_NONCE /
 * DEALPG4_BUDGET_MS / DEALPG4_INVOCATION_ID env pins child-side at
 * the fork, T = min(invocationLimits.overallTimeoutMs, nestedStopMs -
 * nowMs), the 15000 ms budget floor); the pre-fork terminal-record
 * rejections (MALFORMED_INVOKE / REGISTRY_FULL / BUDGET_EXHAUSTED /
 * FORK_FAILED / NONCE_FAILED -- immediate terminal FAILED records
 * with no pid, no channels, no fork, answered REJECT over the open
 * broker); the BROKER_LIVE and BROKER_POST_DONE channel rows (INVOKE
 * semantic/framing split, the full parent-D6 ACK condition with the
 * outer-side state == TARGET_PUBLISHED check, the CANCEL record-level
 * validation, BYE in BROKER_LIVE = PROTOCOL_ERROR, the EOF
 * caller-loss mark); the INVOKE acceptance cutoff (T0o +
 * nestedStopMs, live records marked CANCELLING at expiry) with the
 * cutoff-anchored DONE emission trigger (exactly once, only after
 * the cutoff with a non-empty fully-terminal registry, every
 * terminal answer queued before DONE, the verdict computed at the
 * queueing moment) and the post-DONE INVOKE answer (REJECT <id> <tag>
 * BUDGET_EXHAUSTED + immediate terminal FAILED record over the open
 * broker -- never PROTOCOL_ERROR, never a close); and the
 * coordinator-side dispatch contract pinned for the Java-client
 * boundary in coordinator-observable terms (see the contract block
 * below).
 *
 * See dealpg4-outer-supervisor-engine D1/D2/D5/D6 for the pinned
 * surfaces (outer-coordinator-and-broker D1-D9 preserved):
 *  - the mode entry owns only surface binding: the pinned argv shape
 *    "launcher outer <coordinatorNonce> -- <coordinator-argv...>"
 *    (usage errors on stderr, exit 2, no fork, no socket, no channel),
 *    the embedded OuterLimits pinning, socket_dir = "build",
 *    report_fd = STDOUT_FILENO, spawn = NULL, and the fd-1 flag-set
 *    capture/restore;
 *  - the core owns the D1 entry preamble and T0o (setsid, ignore
 *    SIGHUP/SIGPIPE, subreaper set + read-back, shellPid, T0o, the
 *    timerfd, signalfd(SIGCHLD), the outerNonce), the coordinator
 *    lifecycle (pre-exec pipe, COORD_READY wait + cross-check, the
 *    stream drains, the D8 escalation, the final proof), the registry
 *    and the nested forks, the broker channel state machine (the
 *    live-phase and post-DONE rows), the write-side discipline, the
 *    final report, and the exit-status mapping.
 * The nested control-channel state machine (STUB_FORKED/STUB_READY
 * handling, the ACK relay with RELEASED-at-write-completion, the
 * CANCEL fan-out application), the D7 fallbacks/wedge rule, the
 * cancellation execution, and the remaining final-sequence
 * discrimination land with the next sequencing steps.
 */
#ifndef DEALPG4_OUTER_H
#define DEALPG4_OUTER_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "drain.h"
#include "monotonic.h"
#include "protocol.h"

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

/* === Broker channel states (engine D5) ================================= */

enum dealpg4_outer_broker_state {
    DEALPG4_OUTER_BROKER_AWAIT_HELLO = 0, /* expects exactly HELLO (any
                                             non-HELLO record ->
                                             PROTOCOL_ERROR; a HELLO
                                             nonce mismatch ->
                                             AUTH_FAILED) */
    DEALPG4_OUTER_BROKER_AWAIT_READY = 1, /* entered when HELLO_OK was
                                             queued; expects exactly
                                             FEATURE_READY (any other
                                             record or a nonce
                                             mismatch ->
                                             PROTOCOL_ERROR) */
    DEALPG4_OUTER_BROKER_LIVE = 2,        /* entered exactly after the
                                             READY_ACK write was
                                             queued; expects INVOKE /
                                             ACK / CANCEL — BYE (and
                                             any other record) is
                                             state-unexpected and
                                             closes per the D5
                                             PROTOCOL_ERROR rule (the
                                             frame pins DONE -> BYE);
                                             EOF is the accepted
                                             channel event feeding the
                                             D8 discrimination slot
                                             with the caller-loss
                                             mark on live records */
    DEALPG4_OUTER_BROKER_POST_DONE = 3,   /* entered when the outer has
                                             queued DONE (the
                                             cutoff-anchored trigger
                                             below); expects BYE /
                                             INVOKE / ACK / CANCEL —
                                             BYE is accepted and the
                                             outer then closes its
                                             end; a post-DONE INVOKE
                                             keeps the unconditional
                                             post-cutoff answer
                                             (REJECT <id> <tag>
                                             BUDGET_EXHAUSTED +
                                             immediate terminal FAILED
                                             record over the open
                                             broker — never
                                             PROTOCOL_ERROR, never a
                                             close); ACK/CANCEL keep
                                             the record-level
                                             validation; HELLO /
                                             FEATURE_READY and any
                                             other record are
                                             state-unexpected ->
                                             PROTOCOL_ERROR */
    DEALPG4_OUTER_BROKER_CLOSED = 4       /* terminal: the connection
                                             is closed, no further
                                             records are read */
};

/* === Broker socket mechanics and authentication (engine D1/D5) ======== */

/* The real socket-setup surface (one code path shared by the core and
 * the component tests): mkdir(socket_dir, 0700) tolerating EEXIST for
 * an existing directory, chmod the dir 0700, unlink a stale same-name
 * path, socket(AF_UNIX, SOCK_STREAM) with O_NONBLOCK|FD_CLOEXEC,
 * bind, chmod(path, 0600) immediately after bind and before listen,
 * listen. The FI_OUTER_BIND seam forces the bind failure with the
 * scripted value as errno. Returns 0 with *listen_fd set and the
 * NUL-terminated socket path copied to path_out (path_cap bytes), or
 * -1 with errno — no fd leaked and the path unlinked on every
 * post-create failure path. */
int dealpg4_outer_broker_bind_path(const char *socket_dir,
                                   const char outer_nonce[33],
                                   char *path_out, size_t path_cap,
                                   int *listen_fd);

/* Accept one broker connection with O_NONBLOCK|FD_CLOEXEC and verify
 * SO_PEERCRED before any record (parent D5): pid == coordinator_pid
 * AND uid == expected_uid. Returns 0 with *conn_fd set on success, or
 * -1 with errno — EAGAIN when no connection is pending (the normal
 * non-blocking outcome), EACCES when the peer credentials failed (the
 * caller's AUTH_FAILED path), the accept(2)/fcntl errno otherwise. */
int dealpg4_outer_broker_accept_peer(int listen_fd, pid_t coordinator_pid,
                                     uid_t expected_uid, int *conn_fd);

/* === Registry (engine D2/D3/D4; outer-coordinator-and-broker D2-D4)
 * ========================================================================
 * SupervisorRecord(invocationId, clientTag, nonce, supervisorPid,
 * controlFd, stubPid?, targetPgid?, targetSessionId?, state, deadlineMs,
 * history, cleanupAcknowledged) — parent D2. invocationId is
 * outer-assigned, monotonic, unique (a counter from 1); the nonce is
 * generated at registration via dealpg4_nonce_hex (getrandom(2)); the
 * deadline (the delivered nested budget T) is computed at registration;
 * the ordered (state, monotonicMs) history retains every transition;
 * the first-ACK tracking field backs the D6 ACK validation (set when
 * the outer validates and accepts the ACK for relay — at queueing,
 * before any write completes; cleared only when a validated CANCEL or
 * the terminal queued-write discard drops the queued-but-unwritten ACK —
 * the channel-machine child's application). Register-before-fork: the
 * record is inserted (state FORKING, nonce and deadline computed)
 * before the fork syscall; supervisorPid is attached immediately after
 * fork returns; INVOKED is answered only after insertion. At most 128
 * concurrent live records (REGISTRY_FULL); records are never deleted
 * before the final report. Pre-fork rejection records are inserted
 * directly terminal FAILED with no pid, no channels, no fork, and no
 * fallback work.
 *
 * The live (pre-terminal) states are FORKING / STUB_BLOCKED /
 * TARGET_PUBLISHED / RELEASED / CANCELLING; terminal states are CLEAN /
 * FAILED. The caller-loss mark (broker EOF/close with live records)
 * and the INVOKE-cutoff expiry mark every live record CANCELLING —
 * the cancellation execution (the CANCEL fan-out, fallback
 * completions) lands with the fallback child.
 */

/* <= 128 concurrent live records (parent D2: REGISTRY_FULL at 128). */
#define DEALPG4_OUTER_MAX_LIVE_RECORDS 128

/* Bounded ordered state history per record (the full transition path
 * FORKING -> STUB_BLOCKED -> TARGET_PUBLISHED -> RELEASED ->
 * CANCELLING -> CLEAN|FAILED is at most 6 entries). */
#define DEALPG4_OUTER_HISTORY_MAX 16

/* In-process observability buffers (component tests / ISSUE-0184
 * battery). */
#define DEALPG4_OUTER_TAG_VIEW_BYTES 128
#define DEALPG4_OUTER_TOKEN_VIEW_BYTES 32

/* Registry record states (parent D2). */
typedef enum dealpg4_outer_record_state {
    DEALPG4_OUTER_REC_FORKING = 0,         /* inserted pre-fork */
    DEALPG4_OUTER_REC_STUB_BLOCKED = 1,    /* STUB_FORKED retained */
    DEALPG4_OUTER_REC_TARGET_PUBLISHED = 2,/* verified STUB_READY
                                              forwarded */
    DEALPG4_OUTER_REC_RELEASED = 3,        /* entered exactly when the
                                              outer's ACK write into the
                                              nested control channel
                                              completes */
    DEALPG4_OUTER_REC_CANCELLING = 4,      /* coordinator loss / outer
                                              deadline / shell loss /
                                              valid CANCEL */
    DEALPG4_OUTER_REC_CLEAN = 5,           /* terminal: success or
                                              cancelled (clean_final) */
    DEALPG4_OUTER_REC_FAILED = 6           /* terminal: failure_token */
} dealpg4_outer_record_state;

typedef struct dealpg4_outer_history_entry {
    int state;        /* dealpg4_outer_record_state */
    int64_t at_ms;    /* absolute CLOCK_MONOTONIC ms of the transition */
} dealpg4_outer_history_entry;

/* Read-only record view (in-process observability; valid until the
 * next core call). */
typedef struct dealpg4_outer_record_view {
    int64_t invocation_id;
    char client_tag[DEALPG4_OUTER_TAG_VIEW_BYTES];
    char nonce[DEALPG4_NONCE_HEX_CHARS + 1]; /* "" for pre-fork
                                                rejection records */
    pid_t supervisor_pid;  /* -1 until attached / for pre-fork
                              rejection records */
    int control_fd;        /* -1 until attached / for pre-fork
                              rejection records */
    pid_t stub_pid;        /* -1 until retained */
    pid_t target_pgid;     /* 0 until verified */
    pid_t target_session_id; /* 0 until verified */
    int state;             /* dealpg4_outer_record_state */
    int64_t deadline_ms;   /* the delivered nested budget T (0 for
                              pre-fork rejection records) */
    int64_t deadline_abs_ms; /* registration time + T (0 for pre-fork
                                rejection records) */
    int ack_applied;       /* first-ACK tracking (D6): set when the
                              outer validates and accepts the ACK for
                              relay (at queueing, before any write
                              completes) */
    int cleanup_acknowledged; /* outer completed the fallback proof */
    int clean_final;       /* CLEAN records: 1 = success, 0 =
                              cancelled */
    char failure_token[DEALPG4_OUTER_TOKEN_VIEW_BYTES]; /* FAILED
                                                           records */
    size_t history_count;
    dealpg4_outer_history_entry history[DEALPG4_OUTER_HISTORY_MAX];
} dealpg4_outer_record_view;

/* Read-only registry access (component tests and the ISSUE-0184
 * battery; also valid DURING a core call — the spawn-seam
 * compositions use it to prove register-before-fork): count records,
 * then copy each view. The live registry is visible while a core call
 * runs; afterwards the most recent call's snapshot is retained until
 * the next core call (a call refused before the preamble leaves an
 * empty snapshot). */
size_t dealpg4_outer_registry_count(void);
int dealpg4_outer_registry_record(size_t idx, dealpg4_outer_record_view *out);

/* === Coordinator-side dispatch contract (engine D5, pinned here for
 * the Java-client boundary — coordinator-observable terms only; the
 * coordinator's own sent records and received answers, never
 * outer-registry knowledge) ==============================================
 * (1) The coordinator may send INVOKEs at any time before the cutoff;
 *     each pre-cutoff INVOKE is answered INVOKED or a record-level
 *     REJECT over the open broker — the coordinator needs no
 *     knowledge of any other record's registry state.
 * (2) Once any of its INVOKEs is answered REJECT <id> <tag>
 *     BUDGET_EXHAUSTED, the dispatch window is closed and the
 *     coordinator sends no further INVOKEs.
 * (3) DONE arrives only after the cutoff, once every registry record
 *     is terminal, with every terminal answer preceding it; after
 *     receiving DONE the coordinator sends BYE and then closes — or
 *     simply closes: exit 0 with no live records is the clean exit
 *     per parent D8 either way.
 * (4) A coordinator that dispatched no records must not wait for DONE
 *     (the registry is empty — DONE is never queued) and closes +
 *     exits 0, the clean exit.
 * (5) At any point — including before the cutoff — the coordinator
 *     may close the broker and exit 0 once every record it dispatched
 *     has a terminal answer and it will dispatch no more: the clean
 *     exit per parent D8 (exit 0 without BYE after all records
 *     terminal).
 * (6) While records are live it may interleave CANCEL for a live
 *     record (validated per parent D7), and it must not send a CANCEL
 *     for a record whose terminal answer it has already received.
 * The outer side implements this contract through the BROKER_LIVE /
 * BROKER_POST_DONE rows and the cutoff-anchored DONE trigger below:
 * pre-cutoff INVOKEs are processed before DONE exists; post-cutoff
 * INVOKEs receive the objective's deterministic REJECT <id> <tag>
 * BUDGET_EXHAUSTED answer before or after DONE alike; a coordinator
 * that sends BYE before DONE is BROKER_LIVE-state unexpected ->
 * PROTOCOL_ERROR (the frame pins DONE -> BYE).
 */

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
 * entry behavior), the coordinator lifecycle (pre-exec pipe +
 * COORD_READY wait + report-first cross-check + continuous stream
 * drains + D8 escalation + final proof), the ppoll loop, and the
 * report/exit discipline; the mode entry owns only surface binding.
 *
 * Fail-closed core-entry validation before any fork/socket/channel:
 * limits ordering (nestedStopMs + cleanupReserveMs <= overallTimeoutMs,
 * and readinessTimeoutMs >= 1, nestedStopMs >= 1, cleanupReserveMs >= 1,
 * brokerStallMs >= 1) else CONFIG_INVALID (exit 3); nonce exactly 32
 * lowercase hex; coordinator argv non-empty; socket_dir non-empty. No
 * fork, socket, or channel on violation.
 *
 * Return status: 0 on the clean exit (the D8 discrimination — the
 * coordinator reaped with status 0, no live records, the final proof
 * passed — holds); 1 (DEALPG4_OUTER_EXIT_GATE_FAILURE) for any gate
 * failure; DEALPG4_EXIT_CONFIG_INVALID (3);
 * DEALPG4_EXIT_CAPABILITY_MISSING (4) for capability-class entry
 * refusals. The final report goes to report_fd (not a DEALPG4 record)
 * with named tokens, coordinator facts, and the proof result; the
 * report-fd flag set is restored before every exit.
 */
int dealpg4_outer_core(const OuterLimits *limits,
                       const char coordinator_nonce[33],
                       char *const coordinator_argv[],
                       const char *socket_dir, int report_fd,
                       const dealpg4_outer_spawn *spawn);

/* === Fault-injection seam catalog (engine D6, this child's sites) ====== */

/* Named fail-site tags (int tags through dealpg4_fi_hooks.fail): a
 * scripted nonzero return forces the named failure path with the
 * scripted value as errno; 0 runs the real syscall. The entry sites
 * (FI_OUTER_SUBREAPER / FI_OUTER_TIMERFD / FI_OUTER_SIGNALFD /
 * FI_OUTER_ENTRY_NONCE) land with the entry child; FI_OUTER_PIPE and
 * FI_COORD_READY_MISMATCH land with this child (the coordinator pipe
 * owner). The pinned D6 catalog binds FI_OUTER_NONCE to the
 * registration-time nonce site (the nested-fork machinery, D3). The
 * remaining D6 fail sites (FI_OUTER_BIND,
 * FI_OUTER_SOCKETPAIR, FI_OUTER_FORK, FI_OUTER_DEATH) and the
 * congestion catalog land with the children that own the broker
 * socket and the nested fork machinery. */
enum dealpg4_outer_fi_fail_site {
    FI_OUTER_SUBREAPER = 1, /* entry prctl set/read-back fails ->
                             * CAPABILITY_MISSING, exit 4 */
    FI_OUTER_TIMERFD = 2,   /* entry timerfd_create fails ->
                             * TIMER_FAILED, exit 4 */
    FI_OUTER_SIGNALFD = 3,  /* entry signalfd(SIGCHLD) fails ->
                             * CAPABILITY_MISSING, exit 4 */
    FI_OUTER_NONCE = 4,     /* registration-time nonce generation
                             * fails (unrecoverable getrandom(2)) ->
                             * REJECT <id> <tag> NONCE_FAILED +
                             * terminal FAILED <id> NONCE_FAILED
                             * record, no fork, no channels (D3); the
                             * production nonce path
                             * (dealpg4_nonce_hex) is unchanged when
                             * the site is unscripted */
    FI_OUTER_PIPE = 5,      /* the coordinator pre-exec pipe or a
                             * coordinator stream drain pipe fails
                             * before the coordinator fork -> no fork,
                             * gate-fatal, final report token
                             * COORDINATOR_STARTUP_FAILED (the
                             * unverified-group discharge trivially
                             * holds — nothing was ever forked) */
    FI_COORD_READY_MISMATCH = 6, /* the coordinator child writes a
                                  * scripted COORD_READY whose pgid/sid
                                  * differ from its actual values (the
                                  * report lies; the outer's
                                  * report//proc cross-check disagrees)
                                  * -> COORDINATOR_STARTUP_FAILED */
    FI_OUTER_BIND = 7,             /* the broker socket bind fails with
                                    * the scripted value as errno ->
                                    * BROKER_BIND_FAILED, no
                                    * coordinator fork */
    FI_OUTER_SOCKETPAIR = 8,      /* the per-invocation control
                                    * socketpair fails with the
                                    * scripted value as errno -> the
                                    * already-inserted record goes
                                    * terminal FAILED FORK_FAILED and
                                    * the answer is REJECT <id> <tag>
                                    * FORK_FAILED — no pid, no
                                    * channels, no fork (D3) */
    FI_OUTER_FORK = 9,            /* the nested-supervisor fork(2)
                                    * fails with the scripted value as
                                    * errno -> the same FORK_FAILED
                                    * path (the record was already
                                    * inserted — register-before-fork;
                                    * D3) */
    FI_OUTER_ENTRY_NONCE = 10    /* entry getrandom(2) outerNonce
                                    * fails -> NONCE_FAILED on stderr,
                                    * exit 1, no fork, no socket, no
                                    * records (D1). Distinct from
                                    * FI_OUTER_NONCE: the pinned D6
                                    * catalog binds FI_OUTER_NONCE to
                                    * the registration-time site */
};

/* Named congest target/mode tags (int tags through
 * dealpg4_fi_hooks.congest; a scripted nonzero mode forces the named
 * congestion, 0 = the real path; fi.h). The broker-owned congestion
 * seam (engine D6) lands with this child. */
#define FI_CONGEST_BROKER        1 /* the broker socket hop */
#define BROKER_WRITE_STALL       1 /* broker POLLOUT never ready while
                                    * relay data is pending (the write
                                    * flush reports EAGAIN without
                                    * attempting the syscall) — arms
                                    * and fires the parent-D5 stall
                                    * rule; a native deadline is never
                                    * suspended */

/* Named delay-site tags (string tags through dealpg4_fi_hooks.
 * delay_ms; an injected delay sleeps exactly the scripted ms before
 * the named step and consumes the component's own deadline — an
 * injection never extends a deadline, fi.h). Production: the
 * requested 0 ms at every site. */
#define DEALPG4_FI_DELAY_OUTER_PRE_COORD_FORK    "outer-pre-coord-fork"
#define DEALPG4_FI_DELAY_COORD_POST_FORK         "coord-post-fork"
#define DEALPG4_FI_DELAY_COORD_PRE_READY_WRITE   "coord-pre-ready-write"

/* === Exit-status mapping (engine D1) =================================== */

/* The run-outcome facts the mapping consumes. */
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
    int timerfd_ok;         /* the deadline timerfd armed */
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

    /* Coordinator lifecycle (engine D1/D5/D8). */
    pid_t coordinator_pid;       /* -1 when never forked */
    pid_t coordinator_pgid;      /* 0 when never verified (the
                                    pipe-published cross-checked
                                    group — never a /proc race) */
    pid_t coordinator_sid;       /* 0 when never verified */
    int ready_verified;          /* COORD_READY received and every
                                    cross-check held */
    int coord_exec_failed;       /* COORD_EXEC_FAILED observed */
    int coordinator_reaped;      /* waitid observed the coordinator */
    int coordinator_si_code;     /* reaped si_code (CLD_*) */
    int coordinator_si_status;   /* reaped si_status */
    int coordinator_exited_0;    /* CLD_EXITED with status 0 */
    /* D8 escalation facts. */
    int escalation_term_issued;  /* the TERM step ran */
    int escalation_term_sent;    /* a TERM was dispatched */
    int escalation_kill_issued;  /* the KILL step ran */
    int escalation_kill_sent;    /* a KILL was dispatched */
    int escalation_group_scope;  /* 1 = -pgid (verified group),
                                    0 = by pid (unverified group) */
    int group_liveness_checked;  /* kill(-pgid, 0) attempt observed
                                    (never against an unverified
                                    group) */
    int64_t escalation_term_ms;  /* t0o-relative ms of the TERM step */
    int64_t escalation_kill_ms;  /* t0o-relative ms of the KILL step */
    /* Final-proof facts. */
    int proof_passed;          /* the proof completed clean */
    int proof_reap_echild;     /* waitid reached ECHILD */
    int proof_adopted_clean;   /* no /proc task with ppid == outerPid */
    int proof_group_clean;     /* coordinator group absent (vacuous
                                  under the unverified group) */
    int proof_streams_eof;     /* both coordinator stream fds at EOF */
    int proof_broker_clean;    /* broker connection closed and socket
                                  unlinked (vacuous until the broker
                                  child — the check slot is wired) */
    int proof_registry_clean;  /* every registry record terminal
                                  (registry empty at this stage) */
    /* Shell loss (parent D1). */
    int shell_lost;            /* EPIPE/EBADF on the report stdout or
                                  getppid() != shellPid */
    /* Broker channel (engine D5). */
    int broker_created;        /* bind/listen completed */
    int broker_conn_accepted;  /* the one connection was accepted at
                                  the socket level */
    int broker_peer_verified;  /* SO_PEERCRED pid/uid held */
    int broker_hello_ok_sent;  /* HELLO_OK queued (AWAIT_READY
                                  entered) */
    int broker_ready_acked;    /* READY_ACK queued (BROKER_LIVE
                                  entered exactly after the queue) */
    int broker_state;          /* final channel state
                                  (dealpg4_outer_broker_state) */
    int broker_closed;         /* the connection is closed */
    int broker_eof;            /* read-side EOF observed (the D8
                                  discrimination slot) */
    int broker_socket_unlinked; /* the socket path was unlinked */
    int broker_conns_rejected; /* second+ connections accepted and
                                  closed without a read */
    int broker_stall_armed;    /* the stall deadline was armed
                                  (observed at least once) */
    int broker_stall_fired;    /* BROKER_STALLED decided (relay data
                                  still pending at the stall
                                  deadline) */
    int64_t broker_stall_arm_ms; /* t0o-relative stall arm time (0
                                    when never armed) */
    int64_t broker_stall_deadline_ms; /* t0o-relative stall deadline
                                         (0 when never armed) */
    /* Coordinator stream drains. */
    uint64_t coord_stdout_bytes; /* drain total_read */
    uint64_t coord_stderr_bytes;
    int coord_stdout_truncated;
    int coord_stderr_truncated;
    int coord_stdout_eof;
    int coord_stderr_eof;
    uint64_t reap_count;        /* children reaped by the outer */
    uint64_t adopt_count;       /* adopted descendants found/scanned */

    /* Registry + DONE trigger (engine D2-D5). */
    int records_total;          /* registry records (terminal and
                                   live) */
    int records_live;           /* pre-terminal registry records */
    int records_failed;         /* terminal FAILED records */
    int records_clean;          /* terminal CLEAN records (success or
                                   cancelled) */
    int64_t next_invocation_id; /* the next outer-assigned id (the
                                   monotonic counter) */
    int cutoff_cancelled;       /* the cutoff expiry marked every
                                   live record CANCELLING */
    int64_t cutoff_mark_ms;     /* t0o-relative cutoff-mark time (0
                                   when never marked) */
    int caller_loss_marked;     /* a broker close/EOF marked live
                                   records CANCELLING (the caller-loss
                                   mark) */
    int done_queued;            /* the cutoff-anchored DONE trigger
                                   fired exactly once */
    int done_clean;             /* the DONE verdict computed at the
                                   queueing moment (1 = clean, every
                                   record CLEAN success or clean
                                   cancelled) */
    int64_t done_ms;            /* t0o-relative DONE queueing time */
} dealpg4_outer_result;

/* Copy the most recent core call's result view (zeroed when no core
 * call ran or the call was refused before the preamble). */
void dealpg4_outer_last_result(dealpg4_outer_result *out);

/* Read-only view of the most recent core call's two coordinator
 * stream drain contexts (stdout and stderr in that order): the final
 * retained content (1 MiB cap + the truncation marker), the
 * truncation flag, the total read, and the EOF state the component
 * tests and the ISSUE-0184 battery assert against. Valid until the
 * next core call; a call refused before the coordinator start leaves
 * empty contexts. */
void dealpg4_outer_drain_state(const dealpg4_drain_ctx **stdout_ctx,
                               const dealpg4_drain_ctx **stderr_ctx);

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
