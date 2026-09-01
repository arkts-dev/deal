/*
 * Cancel-path terminal-derivation regression (ISSUE-0439 remediation,
 * ISSUE-0180 acceptance review finding): a cancel-requested invocation
 * whose stub/target is still live — un-reaped — at the terminal
 * classification must publish FAILED with the owning token, never the
 * false-clean CLEAN <id> final=cancelled with failureToken '-'.
 *
 * Defect (pre-fix): dealpg4_supervisor_finalize's cancel branch
 * required a reaped stub for every named case and fell through to
 * CLEAN final=cancelled for an un-reaped stub. The post-T5 proof-bound
 * expiry (dealpg4_supervisor_t5_proof_expiry, reached from the T5
 * branch of dealpg4_supervisor_advance_deadlines when the
 * DEALPG4_SUP_T5_PROOF_BOUND_MS window expires) forces the terminal
 * classification OVERALL_TIMEOUT and finalizes regardless of proof
 * completion — exactly the landing of a released target that the
 * escalation could not clean (an unkillable D-state survivor: the
 * SIGKILL pends, the stub never reaps, the drains never reach EOF).
 * On that path the cancel derivation published REPORT (failureToken
 * '-') + CLEAN final=cancelled and serve exit 1 while the target tree
 * still lived — a false-clean terminal record, the direction the
 * design pins as forbidden (never a false CLEAN; D5(c) clean cancels
 * require the stub/target to have exited on its own; ISSUE-0436 pins
 * the post-T5 proof-bound expiry to exactly one REPORT and exactly one
 * terminal FAILED record with the owning token).
 *
 * Post-fix rule (the pinned derivation): the cancel-path CLEAN
 * final=cancelled case requires a reaped CLD_EXITED stub (or the
 * never-forked stub_pid < 0 paths); an un-reaped, still-live stub at
 * any terminal classification — OVERALL_TIMEOUT at the T5-bound
 * expiry or a survivor-family token — publishes FAILED with the
 * owning token. Every misclassification direction stays a false
 * failure, never a false clean.
 *
 * A deterministic D-state target is not constructible in this
 * environment (uninterruptible device/FUSE/NFS I/O only; SIGTERM and
 * SIGKILL remove every killable userspace state), so the regression
 * drives the real terminal-classification functions in-process —
 * dealpg4_supervisor_t5_proof_expiry (the exact defect landing:
 * reap_all, the final /proc scan, the OVERALL_TIMEOUT force, and
 * finalize) and dealpg4_supervisor_proof_deadline / finalize for the
 * survivor-family and preserved cases — over a constructed terminal
 * state whose stub_pid > 0 is un-reaped and whose stub_pgid/stub_sid
 * are unverified (no real process is signaled or scanned). The
 * derivation matrix is pinned case by case:
 *
 *   T5-bound expiry, un-reaped live stub (the defect) ->
 *       FAILED OVERALL_TIMEOUT, REPORT drainEof 0, serve exit 2;
 *   T4 proof-deadline classification, un-reaped live stub ->
 *       FAILED with the survivor-family token, REPORT drainEof 0;
 *   reaped CLD_EXITED stub on the cancel path (preserved) ->
 *       CLEAN final=cancelled, failureToken '-', exit 1;
 *   never-forked stub (stub_pid < 0, preserved) ->
 *       CLEAN final=cancelled, failureToken '-', exit 1;
 *   reaped CLD_KILLED + cancel-signals-issued (preserved) ->
 *       FAILED CALLER_LOST (exitCode 128+signal, termSignal);
 *   reaped CLD_KILLED + deadline-owned signal (ISSUE-0438,
 *   preserved) -> FAILED EXECUTION_TIMEOUT;
 *   reaped CLD_KILLED, no supervisor signal (preserved) ->
 *       FAILED UNVERIFIED_TARGET_DEATH.
 *
 * Compile: gcc -std=c11 -O2 -Wall -Werror -fno-ident \
 *     -ffile-prefix-map=$PWD=. -Wl,--build-id=none -o <bin> \
 *     test/cancel-terminal-derivation-regression.c src/protocol.c \
 *     src/monotonic.c src/drain.c src/fi.c src/selftest.c
 * Run via tools/test/run-cancel-terminal-derivation-regression.sh from
 * the repository root (takes well under a second; no fork, no signal,
 * no /proc target exists).
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "../src/supervisor.c"

/* === Harness ========================================================== */

static int g_checks;
static int g_failures;
static const char *g_ctx = "";

#define CHECK(cond)                                                     \
    do {                                                                \
        g_checks++;                                                     \
        if (!(cond)) {                                                  \
            g_failures++;                                               \
            fprintf(stderr, "FAIL [%s] %s:%d: %s\n", g_ctx, __FILE__,   \
                    __LINE__, #cond);                                   \
        }                                                               \
    } while (0)

static uint64_t now_ms(void)
{
    struct timespec ts;

    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000u
           + (uint64_t)(ts.tv_nsec / 1000000);
}

static int pfi(const dealpg4_parsed *p, size_t i, int64_t *v)
{
    const dealpg4_field_slice *f = dealpg4_parsed_field(p, i);

    return f != NULL && dealpg4_field_decimal(f, v) != 0;
}

static int ptok(const dealpg4_parsed *p, size_t i, const char *s)
{
    const dealpg4_field_slice *f = dealpg4_parsed_field(p, i);
    size_t n = strlen(s);

    return f != NULL && f->len == n && memcmp(f->p, s, n) == 0;
}

/* Construct the common terminal state: a serve-mode invocation whose
 * channel is already gone (control_fd = -1: no further publication
 * surface — the terminal derivation is the observable surface via
 * final_kind, failure_token, report_line, and the serve exit status),
 * with the real drain contexts and the queue initialized. */
static void state_init(dealpg4_supervisor_state *st,
                       dealpg4_drain_ctx *dout, dealpg4_drain_ctx *derr)
{
    memset(st, 0, sizeof *st);
    memset(dout, 0, sizeof *dout);
    memset(derr, 0, sizeof *derr);
    st->drain_out = dout;
    st->drain_err = derr;
    st->relay_out.drain = dout;
    st->relay_err.drain = derr;
    st->control_fd = -1;
    st->serve_mode = 1;
    st->t0 = (int64_t)now_ms();
    st->stub_pid = 4242;
    dealpg4_supervisor_queue_init(&st->queue);
}

/* After the terminal classification: assert the terminal kind, the
 * failure token, the serialized REPORT's failureToken/drainEof/
 * exitCode/termSignal fields, and the serve exit status. */
static void check_terminal(const dealpg4_supervisor_state *st,
                           dealpg4_supervise_terminal kind,
                           const char *token, int drain_eof,
                           int exit_code, int term_signal, int exit_status)
{
    dealpg4_parsed p;
    int64_t v;

    CHECK(st->terminal_queued == 1);
    CHECK(st->final_kind == kind);
    CHECK(strcmp(st->failure_token, token) == 0);
    CHECK(dealpg4_parse(st->report_line, st->report_len, &p)
          == DEALPG4_PARSE_OK);
    CHECK(p.type == DEALPG4_REC_REPORT);
    CHECK(ptok(&p, 18, token));
    CHECK(pfi(&p, 17, &v) && v == drain_eof);
    CHECK(pfi(&p, 0, &v) && v == exit_code);
    CHECK(pfi(&p, 1, &v) && v == term_signal);
    CHECK(dealpg4_supervisor_exit_status(st) == exit_status);
}

/* === The cases ======================================================== */

/* The defect: a cancel-requested invocation whose released target is
 * still alive (un-reaped) when the post-T5 proof window expires — the
 * D-state survivor landing. The state mirrors what the T5 branch set
 * (classification OVERALL_TIMEOUT) and drives the real
 * dealpg4_supervisor_t5_proof_expiry: reap_all, the final /proc scan,
 * the OVERALL_TIMEOUT force, and finalize. Post-fix: FAILED
 * OVERALL_TIMEOUT, REPORT drainEof 0, serve exit 2 — pre-fix this
 * published the false-clean CLEAN final=cancelled with failureToken
 * '-' and exit 1. */
static void case_t5_bound_dstate_survivor(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "T5-bound expiry with a live un-reaped stub (cancel)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.cancel_signals_issued = 1; /* the cancel TERM/KILL were delivered
                                     but the D-state stub never reaped */
    st.classification = DEALPG4_SUP_CLASS_OVERALL_TIMEOUT;
    dealpg4_supervisor_t5_proof_expiry(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_FAILED, "OVERALL_TIMEOUT",
                   0, 0, 0, DEALPG4_EXIT_USAGE);
    CHECK(st.proof_failed_class == 1); /* the pinned drainEof = 0
                                          consequence */
}

/* The survivor-family variant: the same un-reaped live stub at the T4
 * proof-deadline landing. The real dealpg4_supervisor_proof_deadline
 * classifies (PROOF_TIMEOUT here: no survivor identity is verified in
 * the constructed state) and finalizes — FAILED with the owning
 * survivor-family token, never a false clean. */
static void case_proof_deadline_token(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "T4 proof-deadline token with a live un-reaped stub (cancel)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.classification = DEALPG4_SUP_CLASS_OVERALL_TIMEOUT;
    dealpg4_supervisor_proof_deadline(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_FAILED, "PROOF_TIMEOUT",
                   0, 0, 0, DEALPG4_EXIT_USAGE);

    /* The explicit survivor token: the derivation must carry the
     * classification's own token for every survivor-family value. */
    g_ctx = "GROUP_SURVIVOR token with a live un-reaped stub (cancel)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.classification = DEALPG4_SUP_CLASS_GROUP_SURVIVOR;
    st.proof_failed_class = 1;
    dealpg4_supervisor_finalize(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_FAILED, "GROUP_SURVIVOR",
                   0, 0, 0, DEALPG4_EXIT_USAGE);
}

/* Preserved: a reaped CLD_EXITED stub on the cancel path is the
 * pinned clean cancel (D5(c): the stub/target exited on its own) —
 * CLEAN final=cancelled, failureToken '-', serve exit 1. */
static void case_reaped_exited_clean_cancel(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "Reaped CLD_EXITED stub (clean cancel, preserved)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.stub_reaped = 1;
    st.stub_si_code = CLD_EXITED;
    st.stub_si_status = 5; /* the pre-release release-EOF exit 5 */
    st.classification = DEALPG4_SUP_CLASS_CALLER_LOST;
    dealpg4_supervisor_finalize(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED, "-",
                   1, 5, 0, 1);
}

/* Preserved: a never-forked stub (stub_pid < 0 — the CLEANUP_FAILED /
 * STUB_BOOTSTRAP_FAILED record paths with a cancel in play) stays the
 * clean cancel: no stub/target ever existed. */
static void case_never_forked_clean_cancel(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "Never-forked stub (clean cancel, preserved)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.stub_pid = -1;
    st.classification = DEALPG4_SUP_CLASS_CLEANUP_FAILED;
    dealpg4_supervisor_finalize(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_CLEAN_CANCELLED, "-",
                   1, 0, 0, 1);
}

/* Preserved: a cancel-path signal death (the cancel itself delivered
 * the TERM/KILL) stays FAILED CALLER_LOST with the reaped-status
 * convention (D5(c)). */
static void case_cancel_signal_death(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "Cancel-path signal death (CALLER_LOST, preserved)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.cancel_signals_issued = 1;
    st.stub_reaped = 1;
    st.stub_si_code = CLD_KILLED;
    st.stub_si_status = SIGTERM;
    st.classification = DEALPG4_SUP_CLASS_CALLER_LOST;
    dealpg4_supervisor_finalize(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_FAILED, "CALLER_LOST",
                   1, 128 + SIGTERM, SIGTERM, DEALPG4_EXIT_USAGE);
}

/* Preserved (ISSUE-0438): a death owned by the deadline escalation —
 * the cancel that arrived mid-escalation issued no signal of its own
 * — stays FAILED with the owning phase token (EXECUTION_TIMEOUT). */
static void case_deadline_owned_death(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "Deadline-owned signal death (EXECUTION_TIMEOUT, preserved)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.signals_issued_to_target = 1;
    st.stub_reaped = 1;
    st.stub_si_code = CLD_KILLED;
    st.stub_si_status = SIGKILL;
    st.classification = DEALPG4_SUP_CLASS_EXECUTION_TIMEOUT;
    dealpg4_supervisor_finalize(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_FAILED, "EXECUTION_TIMEOUT",
                   1, 128 + SIGKILL, SIGKILL, DEALPG4_EXIT_USAGE);
}

/* Preserved: a signal death with no supervisor-issued signal on the
 * record stays FAILED UNVERIFIED_TARGET_DEATH (parent D3). */
static void case_unverified_target_death(void)
{
    dealpg4_supervisor_state st;
    dealpg4_drain_ctx dout;
    dealpg4_drain_ctx derr;

    g_ctx = "Unverified target death (preserved)";
    state_init(&st, &dout, &derr);
    st.cancel_requested = 1;
    st.stub_reaped = 1;
    st.stub_si_code = CLD_KILLED;
    st.stub_si_status = SIGSEGV;
    st.classification = DEALPG4_SUP_CLASS_UNVERIFIED_TARGET_DEATH;
    dealpg4_supervisor_finalize(&st);
    check_terminal(&st, DEALPG4_SUP_TERMINAL_FAILED,
                   "UNVERIFIED_TARGET_DEATH", 1, 128 + SIGSEGV, SIGSEGV,
                   DEALPG4_EXIT_USAGE);
}

int main(void)
{
    case_t5_bound_dstate_survivor();
    case_proof_deadline_token();
    case_reaped_exited_clean_cancel();
    case_never_forked_clean_cancel();
    case_cancel_signal_death();
    case_deadline_owned_death();
    case_unverified_target_death();

    if (g_failures != 0) {
        fprintf(stderr, "REGRESSION FAIL: %d of %d checks failed\n",
                g_failures, g_checks);
        return 1;
    }
    printf("REGRESSION PASS: cancel-path terminal derivation — an "
           "un-reaped live stub at the T5-bound expiry or a "
           "survivor-token classification publishes FAILED with the "
           "owning token (never the false-clean CLEAN final=cancelled "
           "with failureToken '-'); the pinned clean-cancel, "
           "never-forked, CALLER_LOST, EXECUTION_TIMEOUT, and "
           "UNVERIFIED_TARGET_DEATH derivations are preserved "
           "(%d checks)\n", g_checks);
    return 0;
}
