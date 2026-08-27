/*
 * DEALPG4 supervisor-evaluate startupMs anchoring regression cases
 * (tools/test/evaluate-startup-ms-tests.c).
 *
 * ISSUE-0244 review-cycle-2 regression: REPORT.startupMs is pinned to
 * CLOCK_MONOTONIC - T0 at the end of the startup phase — the observed
 * successful release write, or the startup-phase terminal
 * classification (dealpg4-supervisor-engine D7). The
 * !release_write_ok branch of dealpg4_supervisor_evaluate is that
 * terminal classification for the silent pre-release stub exits
 * (STUB_PRE_RELEASE_EXIT for the reserved exits 2/5/6 and an
 * unsignaled signal death, STARTUP_TIMEOUT for a reaped exit 4 — the
 * stub's own step-6 deadline, including the release-write race — and
 * for the supervisor's own T1 TERM) and for the release-write races;
 * it must anchor startup_ms at the classification with the pinned
 * guard (phase == DEALPG4_PHASE_STARTUP && startup_ms == 0), exactly
 * as the cancel/AUTH_FAILED/TIMER_FAILED paths do, so the
 * finalize-built REPORT never emits startupMs=0 on those paths.
 *
 * The silent exits are seam/race-driven end-to-end (the ISSUE-0184
 * battery scripts them against the D6 catalog), so the cases here
 * drive the classification directly with fabricated states — a
 * white-box regression for the exact trigger. The suite lives outside
 * the tools/src/ build glob (the pinned artifact build is
 * unchanged); the test binary is a scratch file and never enters the
 * repository.
 */
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>

#include "../src/supervisor.c"

static int g_checks;
static int g_failures;

#define CHECK(cond)                                                     \
    do {                                                                \
        g_checks++;                                                     \
        if (!(cond)) {                                                  \
            g_failures++;                                               \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,     \
                    #cond);                                             \
        }                                                               \
    } while (0)

/* Fabricate a pre-release state: the status pipe reached EOF and the
 * stub is reaped, nothing classified yet, the startup phase is live,
 * T0 anchored 1000 ms in the past, the release write never succeeded
 * (the branch's premise). */
static void seed_pre_release(dealpg4_supervisor_state *s)
{
    memset(s, 0, sizeof *s);
    s->t0 = (int64_t)dealpg4_now_ms() - 1000;
    s->status_eof = 1;
    s->stub_reaped = 1;
}

/* startup_ms anchored between the pre-call and post-call
 * CLOCK_MONOTONIC - T0 readings (the D7 classification instant). */
static int anchored_in_window(const dealpg4_supervisor_state *s,
                              uint64_t lo)
{
    uint64_t hi = (uint64_t)((int64_t)dealpg4_now_ms() - s->t0);

    return s->startup_ms > 0 && (uint64_t)s->startup_ms >= lo
        && (uint64_t)s->startup_ms <= hi;
}

static void case_silent_exit(int status, dealpg4_supervise_class want)
{
    dealpg4_supervisor_state s;
    uint64_t lo;

    seed_pre_release(&s);
    s.stub_si_code = CLD_EXITED;
    s.stub_si_status = status;
    lo = (uint64_t)((int64_t)dealpg4_now_ms() - s.t0);
    dealpg4_supervisor_evaluate(&s);
    CHECK(s.classification == want);
    CHECK(anchored_in_window(&s, lo));
}

static void case_signal_death(int status, int signals_issued,
                              dealpg4_supervise_class want)
{
    dealpg4_supervisor_state s;
    uint64_t lo;

    seed_pre_release(&s);
    s.stub_si_code = CLD_KILLED;
    s.stub_si_status = status;
    s.signals_issued_to_target = signals_issued;
    lo = (uint64_t)((int64_t)dealpg4_now_ms() - s.t0);
    dealpg4_supervisor_evaluate(&s);
    CHECK(s.classification == want);
    CHECK(anchored_in_window(&s, lo));
}

/* The pinned guard: an anchor already taken by the cancel path is
 * never overwritten by the later classification. */
static void case_guard_preserves_existing_anchor(void)
{
    dealpg4_supervisor_state s;

    seed_pre_release(&s);
    s.stub_si_code = CLD_EXITED;
    s.stub_si_status = 5;
    s.cancel_requested = 1; /* apply_cancel anchored startup_ms */
    s.startup_ms = 777;
    dealpg4_supervisor_evaluate(&s);
    CHECK(s.classification == DEALPG4_SUP_CLASS_CALLER_LOST);
    CHECK(s.startup_ms == 777);
}

/* The pinned guard: the anchor is the startup-phase terminal
 * classification — a classification after the startup phase ended
 * (fabricated: the T1 handler already left STARTUP) takes no anchor. */
static void case_guard_phase(void)
{
    dealpg4_supervisor_state s;

    seed_pre_release(&s);
    s.stub_si_code = CLD_EXITED;
    s.stub_si_status = 5;
    s.phase = DEALPG4_PHASE_TERM;
    dealpg4_supervisor_evaluate(&s);
    CHECK(s.classification == DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT);
    CHECK(s.startup_ms == 0);
}

/* No classification without the EOF + reaped-status evidence pair —
 * and no anchor either (nothing was classified yet). */
static void case_no_evidence_yet(void)
{
    dealpg4_supervisor_state s;

    seed_pre_release(&s);
    s.status_eof = 0;
    s.stub_si_code = CLD_EXITED;
    s.stub_si_status = 5;
    dealpg4_supervisor_evaluate(&s);
    CHECK(s.classification == DEALPG4_SUP_CLASS_NONE);
    CHECK(s.startup_ms == 0);
}

int main(void)
{
    /* Silent pre-release stub exits (parent D3 reserved set): 4 ->
     * STARTUP_TIMEOUT (the stub's own step-6 deadline and the
     * release-write race), 2/5/6 -> STUB_PRE_RELEASE_EXIT. */
    case_silent_exit(4, DEALPG4_SUP_CLASS_STARTUP_TIMEOUT);
    case_silent_exit(5, DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT);
    case_silent_exit(2, DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT);
    case_silent_exit(6, DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT);
    /* Pre-release signal deaths: unsignaled -> STUB_PRE_RELEASE_EXIT,
     * the supervisor's own T1 TERM -> STARTUP_TIMEOUT. */
    case_signal_death(SIGTERM, 0, DEALPG4_SUP_CLASS_STUB_PRE_RELEASE_EXIT);
    case_signal_death(SIGTERM, 1, DEALPG4_SUP_CLASS_STARTUP_TIMEOUT);
    /* The pinned anchor guard and the no-evidence early return. */
    case_guard_preserves_existing_anchor();
    case_guard_phase();
    case_no_evidence_yet();

    if (g_failures != 0) {
        fprintf(stderr,
                "evaluate-startup-ms-tests: %d of %d checks failed\n",
                g_failures, g_checks);
        return 1;
    }
    printf("evaluate-startup-ms-tests: all %d checks passed\n", g_checks);
    return 0;
}
