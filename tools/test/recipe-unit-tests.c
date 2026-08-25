/*
 * DEALPG4 limits-ordering and phase-recipe unit cases
 * (tools/test/recipe-unit-tests.c).
 *
 * The T2 recipe unit case (dealpg4-launcher-core embedded-limits
 * contract, dealpg4-time-stream-utilities phase recipes): the
 * invocation recipe's T2 cutoff is T0 + min(executionCutoffMs,
 * T - cleanupTotalMs), so a coordinated manifest +
 * tools/src/monotonic.h limits edit changes the deadline that fires.
 * Every expected value here is derived from the limits records the
 * program is compiled against, so re-running it against edited embedded
 * constants re-verifies the recipe arithmetic with the edited values
 * (the T2 min-form changes with executionCutoffMs). Also covers the
 * other phase anchors (T1/T3/T4/T5), the outer recipe and broker-stall
 * deadline, and the three ordering invariants that gate every mode
 * entry with CONFIG_INVALID.
 */
#define _POSIX_C_SOURCE 200809L

#include <inttypes.h>
#include <stdio.h>

#include "../src/monotonic.h"

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

static int64_t min64(int64_t a, int64_t b)
{
    return a < b ? a : b;
}

/* === Case 1: embedded ordering invariants hold ======================== */

static void case_embedded_ordering(void)
{
    CHECK(dealpg4_embedded_limits_ordering_check() == DEALPG4_LIMITS_OK);
}

/* === Case 2: invocation recipe over the embedded constants ============
 * The named T2 min-form: T2 = T0 + min(executionCutoffMs,
 * T - cleanupTotalMs). With an ordered artifact, executionCutoffMs <=
 * T - cleanupTotalMs, so T2 = T0 + executionCutoffMs: the cutoff is the
 * executionCutoff budget. A coordinated limits edit of
 * executionCutoffMs moves T2 by the same amount.
 */
static void case_invocation_recipe_embedded(void)
{
    const LauncherLimits *l = &dealpg4_embedded_launcher_limits;
    int64_t t0 = 100000;
    int64_t cleanup = l->termGraceMs + l->killAndProofReserveMs
                      + l->finalizationReserveMs;
    dealpg4_invocation_deadlines d;

    dealpg4_invocation_recipe(l, t0, l->overallTimeoutMs, &d);
    CHECK(d.cleanupTotalMs == cleanup);
    CHECK(d.t1 == t0 + min64(l->startupTimeoutMs,
                             l->overallTimeoutMs - cleanup));
    CHECK(d.t2 == t0 + min64(l->executionCutoffMs,
                             l->overallTimeoutMs - cleanup));
    if (l->executionCutoffMs <= l->overallTimeoutMs - cleanup)
        CHECK(d.t2 == t0 + l->executionCutoffMs);
    CHECK(d.t3 == d.t2 + l->termGraceMs);
    CHECK(d.t4 == d.t3 + l->killAndProofReserveMs);
    CHECK(d.t5 == t0 + l->overallTimeoutMs);
    printf("T2 recipe (embedded constants): T0=%" PRId64
           " T2-T0=%" PRId64 " = min(executionCutoffMs=%" PRId64
           ", T-cleanupTotalMs=%" PRId64 ")\n",
           t0, d.t2 - t0, l->executionCutoffMs,
           l->overallTimeoutMs - cleanup);
}

/* === Case 3: reduced overall budget clamps the cutoff =================
 * The serve entry validates 15000 <= T <= embedded overallTimeoutMs;
 * with T = 15000 the cutoff clamps to T - cleanupTotalMs (5000) instead
 * of executionCutoffMs.
 */
static void case_reduced_budget(void)
{
    const LauncherLimits *l = &dealpg4_embedded_launcher_limits;
    int64_t t0 = 500000;
    int64_t t = 15000;
    int64_t cleanup = l->termGraceMs + l->killAndProofReserveMs
                      + l->finalizationReserveMs;
    dealpg4_invocation_deadlines d;

    dealpg4_invocation_recipe(l, t0, t, &d);
    CHECK(d.t1 == t0 + min64(l->startupTimeoutMs, t - cleanup));
    CHECK(d.t2 == t0 + min64(l->executionCutoffMs, t - cleanup));
    if (l->executionCutoffMs > t - cleanup)
        CHECK(d.t2 == t0 + (t - cleanup));
    CHECK(d.t3 == d.t2 + l->termGraceMs);
    CHECK(d.t4 == d.t3 + l->killAndProofReserveMs);
    CHECK(d.t5 == t0 + t);
}

/* === Case 4: outer recipe and broker-stall deadline =================== */

static void case_outer_recipe(void)
{
    const OuterLimits *o = &dealpg4_embedded_outer_limits;
    int64_t t0o = 700000;
    dealpg4_outer_deadlines d;

    dealpg4_outer_recipe(o, t0o, &d);
    CHECK(d.readinessDeadline == t0o + o->readinessTimeoutMs);
    CHECK(d.invokeCutoff == t0o + o->nestedStopMs);
    CHECK(d.totalDeadline == t0o + o->overallTimeoutMs);
    CHECK(dealpg4_broker_stall_deadline(o, t0o)
          == t0o + o->brokerStallMs);
}

/* === Case 5: ordering violations are detected =========================
 * The three canonical invariants (dealpg4-launcher-core embedded-limits
 * contract): each violation maps to its named status, which dispatch
 * surfaces as CONFIG_INVALID at every mode entry.
 */
static void case_ordering_violations(void)
{
    LauncherLimits launcher = dealpg4_embedded_launcher_limits;
    OuterLimits outer = dealpg4_embedded_outer_limits;
    SelftestLimits selftest = dealpg4_embedded_selftest_limits;
    int64_t cleanup;

    /* Invariant 1a: startupTimeoutMs <= executionCutoffMs. */
    launcher.executionCutoffMs = 100;
    launcher.startupTimeoutMs = 200;
    CHECK(dealpg4_limits_ordering_check(&launcher, &outer, &selftest)
          == DEALPG4_LIMITS_INVOCATION_ORDERING_VIOLATED);
    /* Invariant 1b: executionCutoffMs <= overallTimeoutMs -
     * (termGraceMs + killAndProofReserveMs + finalizationReserveMs). */
    launcher = dealpg4_embedded_launcher_limits;
    cleanup = launcher.termGraceMs + launcher.killAndProofReserveMs
              + launcher.finalizationReserveMs;
    launcher.executionCutoffMs = launcher.overallTimeoutMs - cleanup + 1;
    CHECK(dealpg4_limits_ordering_check(&launcher, &outer, &selftest)
          == DEALPG4_LIMITS_INVOCATION_ORDERING_VIOLATED);
    /* Invariant 2: nestedStopMs + cleanupReserveMs <= overallTimeoutMs. */
    launcher = dealpg4_embedded_launcher_limits;
    outer = dealpg4_embedded_outer_limits;
    outer.nestedStopMs = outer.overallTimeoutMs
                         - outer.cleanupReserveMs + 1;
    CHECK(dealpg4_limits_ordering_check(&launcher, &outer, &selftest)
          == DEALPG4_LIMITS_OUTER_ORDERING_VIOLATED);
    /* Invariant 3: selftestTimeoutMs >= 60000. */
    outer = dealpg4_embedded_outer_limits;
    selftest = dealpg4_embedded_selftest_limits;
    selftest.selftestTimeoutMs = DEALPG4_SELFTEST_MINIMUM_TIMEOUT_MS - 1;
    CHECK(dealpg4_limits_ordering_check(&launcher, &outer, &selftest)
          == DEALPG4_LIMITS_SELFTEST_MINIMUM_VIOLATED);
    /* The embedded records themselves remain ordered. */
    CHECK(dealpg4_embedded_limits_ordering_check() == DEALPG4_LIMITS_OK);
}

/* === Runner =========================================================== */

typedef void (*case_fn)(void);

int main(void)
{
    static const struct {
        case_fn fn;
        const char *name;
    } cases[] = {
        { case_embedded_ordering,
          "case 1: embedded limits ordering holds" },
        { case_invocation_recipe_embedded,
          "case 2: invocation recipe (T2 min-form) over the embedded "
          "constants" },
        { case_reduced_budget,
          "case 3: reduced overall budget clamps the cutoff" },
        { case_outer_recipe,
          "case 4: outer recipe and broker-stall deadline" },
        { case_ordering_violations,
          "case 5: ordering violations map to their named statuses" }
    };
    size_t i;

    for (i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        int before = g_failures;

        cases[i].fn();
        printf("%s %s\n", g_failures == before ? "PASS" : "FAIL",
               cases[i].name);
    }
    if (g_failures != 0) {
        fprintf(stderr, "recipe unit cases: %d of %d checks FAILED\n",
                g_failures, g_checks);
        return 1;
    }
    printf("recipe unit cases: all 5 cases passed (%d checks)\n",
           g_checks);
    return 0;
}
