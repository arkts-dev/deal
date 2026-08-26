/*
 * DEALPG4 nested supervisor: serve/run mode-entry surfaces and the
 * in-process core entry.
 *
 * This child (ISSUE-0242, epic Sequencing step 1) replaces the
 * MODE_NOT_IMPLEMENTED placeholders with the pinned entry surfaces
 * (dealpg4-supervisor-engine D1-D4, D8): argv/env/fd bindings, entry
 * validation, the DEALPG4_INVOCATION_ID environment pin, fd flags,
 * SIGPIPE suppression, the core-entry signal-mask capture, and the
 * entry exit-status map. Stage invariants: no fork, no exec, no
 * channel records, no process creation anywhere in this child's code;
 * validation happens before any side effect; entry failures always
 * exit with the pinned status. The engine (subreaper set + read-back,
 * stub fork, ppoll loop, record surface) lands with the next
 * sequencing step.
 *
 * Dispatch delegates run/serve to these entries after the
 * dispatch-owned embedded-limits ordering validation and passes
 * argc/argv through unchanged (argv[0] = program path, argv[1] = mode
 * name); dispatch performs no further argument interpretation for
 * run/serve — the argument shapes below are owned by this child.
 */
#ifndef DEALPG4_SUPERVISOR_H
#define DEALPG4_SUPERVISOR_H

#include <stdint.h>

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
 * channel — the channel state machine is inert and no id-bearing
 * records exist), emit_ready_frame = the parsed --ready-frame flag.
 *
 * At this stage the core body is a stub: after validation (and the
 * D8 SIGPIPE ignore plus the entry signal-mask capture) it returns a
 * temporary nonzero no-op status with no fork, no channel writes, and
 * no records. The temporary refusal is not part of the final
 * exit-status map. The returned status is the process exit status
 * (mode mapping per D3/D4 below).
 */
int dealpg4_supervise_core(const char **argv, const char *cwd,
                           const char nonce[33], int64_t invocation_id,
                           int64_t budget_t, int control_fd,
                           int emit_ready_frame);

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
 * Exit statuses (observable belt-and-braces; the outer consumes
 * records, not the status): 0 iff CLEAN final=success was published;
 * 1 iff CLEAN final=cancelled; 2 for any record-bearing FAILED path
 * and for the no-record PROTOCOL_ERROR aborted path; 3 for
 * CONFIG_INVALID-class entry failures; 4 for capability-class entry
 * failures (DEALPG4_EXIT_CAPABILITY_MISSING — the capability checks
 * themselves land with the engine; the status mapping is complete
 * here). Entry failures emit no records; the observable effect is
 * control-channel EOF before any record.
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
 * records). The target's stdin will inherit the launcher's stdin
 * unchanged; stdout/stderr are the launcher's own fds.
 *
 * fd flags: F_GETFL is captured for stdout and stderr before O_NONBLOCK
 * is enabled on both; the original flag sets are restored before every
 * exit on this child's exit paths (the discipline extends to all
 * later-stage exits).
 *
 * Exit codes: 0 iff target exit 0 AND containment clean; 1 iff target
 * exited nonzero but containment clean; 2 containment failure
 * (capability-class entry failures: the named token on stderr and exit
 * 2); usage errors exit 2 with the usage message, no fork.
 */
int dealpg4_run_entry(int argc, char **argv);

#endif
