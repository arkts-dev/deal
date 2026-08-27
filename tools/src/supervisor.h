/*
 * DEALPG4 nested supervisor: serve/run mode-entry surfaces and the
 * in-process core entry.
 *
 * This child (ISSUE-0243, epic Sequencing steps 2-3) replaces the
 * stage-1 core placeholder with the containment engine
 * (native-supervisor-containment D1-D8, dealpg4-supervisor-engine
 * D1/D8): subreaper set + read-back before any fork, entry capability
 * refusal (timerfd_create -> TIMER_FAILED, signalfd(SIGCHLD) ->
 * CAPABILITY_MISSING), the blocked stub state machine (steps 1-8 with
 * the reserved exits {2,3,4,5,6}), the status/release/stream pipes,
 * STUB_FORKED through a minimal non-blocking pending slot, the
 * single-threaded ppoll event loop (timerfd, signalfd(SIGCHLD), both
 * stream pipes, the status-pipe read fd, the control-channel read and
 * write sides), the phase recipe T1-T5 with TERM/KILL against the
 * verified negative PGID, the bounded 1 MiB drains, the release write,
 * and the STARTED three-condition exec-evidence classification with
 * the full non-STARTED classification matrix.
 *
 * Stage invariants: exactly one fork (the stub) and exactly one
 * execvp (the stub's step 8, only after the observed successful
 * release write); no REPORT/CLEAN/FAILED/OUT records exist yet — the
 * record surface and the real serve/run exit-status map land with the
 * channel machine (epic Sequencing step 4); after the stage's
 * classification the invocation ends with the temporary nonzero
 * stage-terminal status with the stub reaped, the drains at EOF, and
 * every supervisor fd closed.
 *
 * Release authorization at this stage (epic Sequencing steps 2-3):
 * run mode releases on the verified STUB_IDENTITY nonce ownership (the
 * CLI nonce); serve mode has no ACK handling yet, so serve never
 * releases and ends at T1 with the STARTUP_TIMEOUT classification —
 * both consistent with the sequencing (channel machine = T3).
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
 * parsed --ready-frame flag (consumed by a later stage: the ready
 * frame prints only once exec is confirmed).
 *
 * Engine stage return statuses (the returned status is the process
 * exit status after the mode mapping below):
 *   - DEALPG4_EXIT_CONFIG_INVALID (3): core-entry validation failure,
 *     no fork, no channel writes, no records;
 *   - DEALPG4_EXIT_CAPABILITY_MISSING (4): subreaper set/read-back or
 *     signalfd(SIGCHLD) entry refusal, plus the stage-fail-closed
 *     environment/pipe/fork refusals (no fork, no records);
 *   - the internal TIMER_FAILED entry refusal (timerfd_create
 *     failure; no fork, no records) — serve maps it to exit 4, run
 *     prints the token and exits 2 (D8);
 *   - the temporary stage-terminal status (nonzero) once the stage's
 *     classification completed: the stub reaped, the target signaled
 *     per the T1-T5 recipe, the drains at EOF, every supervisor fd
 *     closed, no records (the record surface lands with the channel
 *     machine).
 */
int dealpg4_supervise_core(const char **argv, const char *cwd,
                           const char nonce[33], int64_t invocation_id,
                           int64_t budget_t, int control_fd,
                           int emit_ready_frame);

/*
 * Stage classification of an invocation (in-process observability).
 *
 * The selftest battery and scratch drivers compose the core
 * in-process; this enum names the internal classification the engine
 * reached at termination (the record-token mapping lands with the
 * channel machine). STARTED is the non-terminal exec-confirmation
 * classification; every other non-NONE value is terminal at this
 * stage.
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
    DEALPG4_SUP_CLASS_CALLER_LOST = 8,        /* cancel path: T3 */
    DEALPG4_SUP_CLASS_EXECUTION_TIMEOUT = 9,
    DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH = 10,
    DEALPG4_SUP_CLASS_PROOF_TIMEOUT = 11,
    DEALPG4_SUP_CLASS_OVERALL_TIMEOUT = 12
} dealpg4_supervise_class;

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
 * Serve mode entry (dealpg4-supervisor-engine D2/D3).
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
 * Engine stage behavior: STUB_FORKED <invocationId> <stubPid> is
 * written to the channel immediately after fork; the raw
 * STUB_IDENTITY is cross-verified (nonce echo, getpgid/getsid//proc);
 * serve has no ACK handling at this stage, so the supervisor never
 * releases — the stub blocks on the release poll until the supervisor
 * classifies STARTUP_TIMEOUT at T1, kills the retained stub
 * pre-release, reaps it, drains both streams to EOF, and exits with
 * the temporary stage-terminal status (nonzero, no records).
 *
 * Exit statuses (observable belt-and-braces; the outer consumes
 * records, not the status — the record-bearing statuses land with the
 * channel machine): 0 iff CLEAN final=success; 1 iff CLEAN
 * final=cancelled; 2 for record-bearing FAILED paths and the
 * no-record PROTOCOL_ERROR aborted path (T3); 3 for CONFIG_INVALID-
 * class entry failures; 4 for capability-class entry failures
 * (CAPABILITY_MISSING and the TIMER_FAILED entry refusal,
 * DEALPG4_EXIT_CAPABILITY_MISSING). Entry failures emit no records;
 * the observable effect is control-channel EOF before any record.
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
 * exit on this child's exit paths (the discipline extends to all
 * later-stage exits).
 *
 * Engine stage behavior: the stub dance runs end-to-end — subreaper,
 * fork, steps 1-8, identity cross-verification, the single release
 * write on the verified identity nonce (the CLI nonce), the single
 * execvp, the drains (retained in the drain contexts; the passthrough
 * write path lands with the channel machine), the STARTED
 * classification, the T1-T5 escalation, and the stage-terminal status.
 * Capability-class entry failures print the named token
 * (CAPABILITY_MISSING / TIMER_FAILED) on stderr and exit 2
 * (containment failure, D4/D8); usage errors exit 2 with the usage
 * message, no fork.
 *
 * Exit codes (the final record-bearing map lands with the channel
 * machine): 0 iff target exit 0 AND containment clean; 1 iff target
 * exited nonzero but containment clean (real exit code in
 * REPORT.exitCode); 2 containment failure; the temporary
 * stage-terminal status is nonzero and not part of the final map.
 */
int dealpg4_run_entry(int argc, char **argv);

#endif
