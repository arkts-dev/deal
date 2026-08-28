/*
 * DEALPG4 nested supervisor: serve/run mode-entry surfaces and the
 * in-process core entry.
 *
 * This child (ISSUE-0244, epic Sequencing steps 4-5) completes the
 * containment engine on top of the steps-2-3 machinery
 * (native-supervisor-containment D1-D10, dealpg4-supervisor-engine
 * D1/D5/D7/D8):
 *  - the nested control-channel state machine (PRE_RELEASE expects
 *    {ACK, CANCEL}, RELEASED and TERMINAL expect {CANCEL}) with the
 *    per-state ACK/CANCEL handling, the canonical ACK rejection split,
 *    the per-state cancel / channel-loss / PROTOCOL_ERROR semantics
 *    (post-ACK-pre-release cancel freezes the release path — the
 *    release byte is never written; RELEASED cancel issues immediate
 *    TERM against the verified negative PGID with KILL at the absolute
 *    T3; PROTOCOL_ERROR closes the channel with no further records and
 *    applies the per-state invocation termination exactly as channel
 *    loss; the terminal-CANCEL REJECT is queued behind the terminal
 *    record and flushed before the channel close), serve exit 2 on
 *    every FAILED path and the no-record PROTOCOL_ERROR path;
 *  - the serve-mode OUT/OUT_END stream relay (hex chunks capped at
 *    DEALPG4_OUT_MAX_HEX_CHARS == 32768 raw bytes, exactly one OUT_END
 *    per stream queued only at that stream's drain EOF, REPORT and the
 *    terminal record queued after both OUT_ENDs only while both drains
 *    reach EOF, and at the terminal classification without the
 *    incomplete stream's OUT_END on DRAIN_FAILED / PROOF_TIMEOUT /
 *    OVERALL_TIMEOUT / survivor-token paths), the bounded 1 MiB
 *    write-side pending queue with the OUT-drop truncation
 *    consequence, and the pinned nested->outer record schedule;
 *  - the canonical 19-field REPORT (D7 semantics: execMs anchored at
 *    the exec-confirmation classification, truncation flags = drain
 *    truncation plus serve-mode queue-overflow drops only) and the
 *    CLEAN final=success|cancelled / FAILED <failureToken> terminal
 *    records with the full failure-token matrix;
 *  - the TERM/KILL/adoption/proof escalation (verified negative PGID,
 *    adopted descendants signaled by pid via the /proc ppid scan, the
 *    repeated proof loop with the confirming second pass, survivor
 *    tokens), the mid-run TIMER_FAILED re-arm path with the
 *    recipe-state ppoll wakeup, and the CLEANUP_FAILED /
 *    STUB_BOOTSTRAP_FAILED record paths for pipe/fork failures;
 *  - the run-mode surface: 1 MiB passthrough to the launcher's
 *    stdout/stderr under the O_NONBLOCK/POLLOUT policy (drops never
 *    set the REPORT truncation flags), --ready-frame, the final
 *    REPORT line on stderr, exit codes 0/1/2;
 *  - SIGPIPE-safe writes (ignored at every entry; EPIPE = channel
 *    loss / passthrough drop, never process death) and the stub exec
 *    hygiene (SIGPIPE SIG_DFL + captured-entry-mask restore
 *    immediately before execvp).
 */
#ifndef DEALPG4_SUPERVISOR_H
#define DEALPG4_SUPERVISOR_H

#include <stdint.h>
#include <sys/types.h>

#include "drain.h"

/*
 * In-process supervisor core entry (dealpg4-supervisor-engine D1).
 *
 * The mode entries own only surface binding (argv/env/fd validation
 * plus limits) and delegate to the core; the selftest battery composes
 * the core in-process with per-scenario values. Core-entry validation
 * is fail-closed before any fork or side effect:
 *   - 15000 <= budget_t <= embedded overallTimeoutMs (45000) else
 *     CONFIG_INVALID (floor = DEALPG4_PROBE_SELFTEST_LIMIT_MS_FLOOR);
 *   - nonce exactly 32 lowercase hex + NUL else CONFIG_INVALID;
 *   - control_fd >= 0 requires invocation_id >= 1 and
 *     control_fd == -1 requires invocation_id == 0, else
 *     CONFIG_INVALID.
 *
 * Parameters: argv is the target argv with argv[0] the program; cwd is
 * the stub step-7 chdir directory. Serve: invocation_id >= 1,
 * control_fd = 0 (the inherited per-invocation control channel),
 * emit_ready_frame = 0. Run: invocation_id = 0, control_fd = -1 (no
 * channel — no id-bearing records exist), emit_ready_frame = the
 * parsed --ready-frame flag.
 *
 * Return status (the process exit status after the mode mapping; the
 * mode entries map the entry refusals below):
 *   - DEALPG4_EXIT_CONFIG_INVALID (3): core-entry validation failure,
 *     no fork, no channel writes, no records;
 *   - DEALPG4_EXIT_CAPABILITY_MISSING (4): subreaper set/read-back,
 *     signalfd(SIGCHLD), or stub-environment delivery refusal (no
 *     fork, no records);
 *   - DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED (internal): the entry
 *     timerfd_create refusal — serve maps it to exit 4, run prints
 *     the token and exits 2 (D8);
 *   - the record-bearing statuses after a classified invocation:
 *     serve 0 iff CLEAN final=success was published, 1 iff CLEAN
 *     final=cancelled, 2 for every FAILED path and the no-record
 *     PROTOCOL_ERROR aborted path (D3/D5); run 0 iff target exit 0
 *     AND containment clean, 1 iff target exited nonzero but
 *     containment clean (real exit code in REPORT.exitCode), 2
 *     containment failure (token in REPORT.failureToken, D4).
 */
#define DEALPG4_SUPERVISOR_ENTRY_TIMER_FAILED 5

int dealpg4_supervise_core(const char **argv, const char *cwd,
                           const char nonce[33], int64_t invocation_id,
                           int64_t budget_t, int control_fd,
                           int emit_ready_frame);

/*
 * Invocation classification (in-process observability).
 *
 * STARTED is the non-terminal exec-confirmation classification; every
 * other non-NONE value is terminal. The classification names the
 * engine's internal outcome; the terminal record kind and the REPORT
 * failure token are the record surface (carried in the result view
 * below).
 */
typedef enum dealpg4_supervise_class {
    DEALPG4_SUP_CLASS_NONE = 0,               /* entry refusal: no fork */
    DEALPG4_SUP_CLASS_STARTED = 1,            /* exec confirmed (D3 rule) */
    DEALPG4_SUP_CLASS_SUCCESS = 2,            /* clean target exit */
    DEALPG4_SUP_CLASS_STUB_BOOTSTRAP_FAILED = 3,
    DEALPG4_SUP_CLASS_EXEC_FAILED = 4,
    DEALPG4_SUP_CLASS_AUTH_FAILED = 5,
    DEALPG4_SUP_CLASS_STARTUP_TIMEOUT = 6,
    DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT = 7,
    DEALPG4_SUP_CLASS_CALLER_LOST = 8,        /* cancel path */
    DEALPG4_SUP_CLASS_EXECUTION_TIMEOUT = 9,
    DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH = 10,
    DEALPG4_SUP_CLASS_PROOF_TIMEOUT = 11,
    DEALPG4_SUP_CLASS_OVERALL_TIMEOUT = 12,
    DEALPG4_SUP_CLASS_TIMER_FAILED = 13,
    DEALPG4_SUP_CLASS_CLEANUP_FAILED = 14,
    DEALPG4_SUP_CLASS_GROUP_SURVIVOR = 15,
    DEALPG4_SUP_CLASS_SESSION_SURVIVOR = 16,
    DEALPG4_SUP_CLASS_ADOPTED_SURVIVOR = 17,
    DEALPG4_SUP_CLASS_ZOMBIE_SURVIVOR = 18,
    DEALPG4_SUP_CLASS_DRAIN_FAILED = 19
} dealpg4_supervise_class;

/* Terminal record kind of the most recent core call. */
typedef enum dealpg4_supervise_terminal {
    DEALPG4_SUP_TERMINAL_NONE = 0,            /* no terminal record (no
                                                 invocation classified) */
    DEALPG4_SUP_TERMINAL_CLEAN_SUCCESS = 1,   /* CLEAN <id> final=success */
    DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED = 2, /* CLEAN <id> final=cancelled */
    DEALPG4_SUP_TERMINAL_FAILED = 3           /* FAILED <id> <failureToken> */
} dealpg4_supervise_terminal;

/* Bounded survivor identity view (in-process observability only; the
 * canonical records carry the named token, never the list). */
#define DEALPG4_SUP_SURVIVOR_VIEW_MAX 16

/* The canonical 19 REPORT field values of the most recent core call
 * (in-process observability; field order per the catalog). */
typedef struct dealpg4_supervise_report_view {
    int64_t exit_code;
    int64_t term_signal;
    int64_t elapsed_ms;
    int64_t startup_ms;
    int64_t exec_ms;
    int64_t term_ms;
    int64_t kill_ms;
    int64_t proof_ms;
    int64_t final_ms;
    int64_t reap_count;
    int64_t adopt_count;
    uint64_t stdout_bytes;
    uint64_t stderr_bytes;
    int stdout_truncated;
    int stderr_truncated;
    int group_proof;
    int session_proof;
    int drain_eof;
} dealpg4_supervise_report_view;

/*
 * Final result view of the most recent core call (in-process
 * observability for the selftest battery and scratch drivers; valid
 * until the next core call).
 */
typedef struct dealpg4_supervise_result {
    pid_t stub_pid;                    /* retained stub/target pid, -1
                                          when no fork happened */
    pid_t stub_pgid;                   /* verified target pgid, 0 when
                                          never verified */
    pid_t stub_sid;                    /* verified target sid, 0 when
                                          never verified */
    dealpg4_supervise_class classification;
    int reaped_si_code;                /* waitid si_code of the stub,
                                          0 when never reaped */
    int reaped_si_status;              /* waitid si_status of the stub */
    dealpg4_supervise_terminal terminal_kind;
    char failure_token[32];            /* the named token, "" when none */
    int protocol_aborted;              /* PROTOCOL_ERROR close: no
                                          records, serve exit 2 */
    int channel_lost;                  /* control-channel EOF/HUP/EPIPE */
    int cancel_applied;                /* a valid CANCEL or channel
                                          loss applied the cancel path */
    int started_published;             /* STARTED (or the run ready
                                          frame) was published */
    dealpg4_supervise_report_view report;
    /* Serve-mode stream relay counters. */
    uint64_t out_chunks_out;
    uint64_t out_chunks_err;
    int out_end_out;
    int out_end_err;
    int queue_drop_out;
    int queue_drop_err;
    /* Bounded survivor identity list (proof-failed paths). */
    pid_t survivor_pids[DEALPG4_SUP_SURVIVOR_VIEW_MAX];
    size_t survivor_count;
} dealpg4_supervise_result;

/* Copy the most recent core call's result (zeroed when no invocation
 * ran or the call was refused before fork). */
void dealpg4_supervise_last_result(dealpg4_supervise_result *out);

/* Read-only view of the most recent core call's two drain contexts
 * (stdout and stderr in that order): the final retained content,
 * truncation flag, total read, and EOF state the battery scenarios
 * assert against. Valid until the next core call. */
void dealpg4_supervise_drain_state(const dealpg4_drain_ctx **stdout_ctx,
                                   const dealpg4_drain_ctx **stderr_ctx);

/*
 * Serve mode entry (dealpg4-supervisor-engine D2/D3/D5).
 *
 * argv shape: "launcher serve <cwd> -- <argv...>" — argv[2] = cwd
 * (one literal element, the decoded INVOKE cwd), argv[3] must be
 * "--", argv[4..] = the target argv (argv[0] = the program). A
 * missing "--", an empty target argv, or an empty cwd is a
 * CONFIG_INVALID-class entry failure: exit 3
 * (DEALPG4_EXIT_CONFIG_INVALID), no fork, no records, nothing written
 * to the channel.
 *
 * env (all validated before anything else): DEALPG4_BUDGET_MS
 * (15000 <= T <= embedded overallTimeoutMs else CONFIG_INVALID),
 * DEALPG4_NONCE (exactly 32 lowercase hex else CONFIG_INVALID),
 * DEALPG4_INVOCATION_ID (non-empty, all decimal digits, fits int64,
 * >= 1 else CONFIG_INVALID — the D2 pin: the outer delivers it at the
 * same fork as the nonce and budget, in the catalog's decimal id field
 * class).
 *
 * fd binding: fd 0 is the inherited per-invocation control channel;
 * the entry sets O_NONBLOCK|FD_CLOEXEC on fd 0 at entry and treats it
 * as the bidirectional control channel. The supervisor writes nothing
 * to its own stdio in serve mode (stdout/stderr are /dev/null).
 *
 * Behavior: the full success path (STUB_FORKED -> STUB_READY -> ACK ->
 * release -> STARTED -> OUT* / OUT_END* -> REPORT -> CLEAN final=success
 * -> channel close -> exit 0) and every pinned failure/cancel/protocol
 * path per dealpg4-supervisor-engine D5/D7/D8.
 *
 * Exit statuses (observable belt-and-braces; the outer consumes
 * records, not the status): 0 iff CLEAN <id> final=success was
 * published; 1 iff CLEAN <id> final=cancelled; 2 for every
 * record-bearing FAILED path and the no-record PROTOCOL_ERROR aborted
 * path (D3/D5); 3 for CONFIG_INVALID-class entry failures; 4 for
 * capability-class entry failures (CAPABILITY_MISSING and the
 * TIMER_FAILED entry refusal, DEALPG4_EXIT_CAPABILITY_MISSING). Entry
 * failures emit no records; the observable effect is control-channel
 * EOF before any record (the outer's FORKING fallback).
 */
int dealpg4_serve_entry(int argc, char **argv);

/*
 * Run mode entry (dealpg4-supervisor-engine D4, D8).
 *
 * argv shape: "launcher run <nonce> <cwd> [--ready-frame] -- <argv...>"
 * — argv[2] = nonce (the CLI invocation nonce, validated exactly 32
 * lowercase hex), argv[3] = cwd (non-empty), optional argv[4] ==
 * "--ready-frame" only in that position, then "--", then a non-empty
 * target argv. Every shape error (including a malformed nonce) is a
 * usage error: usage message on stderr, exit 2 (dispatch's usage
 * status), no fork.
 *
 * budget/env: T = embedded overallTimeoutMs; a DEALPG4_BUDGET_MS value
 * present in the environment is ignored (run mode never reads it).
 * invocation_id = 0, control_fd = -1 (no channel, no id-bearing
 * records). The target's stdin inherits the launcher's stdin
 * unchanged; stdout/stderr are the launcher's own fds.
 *
 * fd flags: F_GETFL is captured for stdout and stderr before O_NONBLOCK
 * is enabled on both; the original flag sets are restored before every
 * exit.
 *
 * Behavior: the stub dance with the verified-identity release, the
 * 1 MiB-per-stream passthrough (cap + marker) under the non-blocking
 * policy, --ready-frame ("DEALPG4 STARTED <nonce>" once exec is
 * confirmed, before any target stdout passthrough), the final
 * canonical REPORT line on stderr, and the exit codes.
 *
 * Exit codes (D4/D8): 0 iff target exit 0 AND containment clean; 1
 * iff target exited nonzero but containment clean (real exit code in
 * REPORT.exitCode); 2 containment failure (token in
 * REPORT.failureToken); capability-class entry failures print the
 * named token (CAPABILITY_MISSING / TIMER_FAILED) on stderr and exit
 * 2; usage errors exit 2 with the usage message, no fork.
 */
int dealpg4_run_entry(int argc, char **argv);

#endif
