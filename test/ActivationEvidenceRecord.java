// =========================================================================
// ActivationEvidenceRecord — the versioned E11 pre-activation evidence (R3)
// =========================================================================

package deal.test;

import deal.semantic.ir.ReleaseState;

import java.util.List;
import java.util.Objects;

/**
 * The versioned activation evidence record
 * (capability-promotion-activation-rollback-retention R3): release/harness-
 * owned data naming the E11 all-common end-to-end evidence — the cases,
 * their consumers, and the release state they ran under
 * ({@code PRE_ACTIVATION}) — plus the recorded pre-activation production
 * plans (every module {@code LEGACY}, every {@code shadowModules} list
 * empty). Authored in this gate-run conformance file (the
 * {@code LegacyProfileRegressionCatalog} precedent); production compiler
 * paths never depend on it.
 *
 * <p>The release action asserts the record before the flip: the record
 * must be complete and green, every named case must name the three
 * common consumers, the recorded release state must be
 * {@code PRE_ACTIVATION}, and no recorded pre-activation production plan
 * may contain a shared route or a shadow module. Any deviation aborts
 * with {@code ACTIVATION_PRECONDITION_FAILED <fact>} and the flip is not
 * performed.</p>
 */
public final class ActivationEvidenceRecord {

    private ActivationEvidenceRecord() {
        // Release-owned data + closed schema only; no instances.
    }

    /** The pinned canonical shape version of the activation evidence record. */
    public static final int FORMAT_VERSION = 1;

    /** The producer of the evidence: the E11 complete common-closure harness. */
    public static final String PRODUCER_ISSUE = "ISSUE-0240";

    /** The three common consumers every all-common case names. */
    public static final String CONSUMER_ORACLE = "SEMANTIC_ORACLE";
    public static final String CONSUMER_SHARED_LUAJIT = "SHARED_LUAJIT";
    public static final String CONSUMER_SHARED_JVM = "SHARED_JVM";
    public static final List<String> COMMON_CONSUMERS = List.of(
        CONSUMER_ORACLE, CONSUMER_SHARED_LUAJIT, CONSUMER_SHARED_JVM);

    /**
     * One recorded pre-activation production plan: the build identity,
     * the entry module, the per-module routes, and the shadow-module
     * set. Shape validation (non-null, at least one route) lives here;
     * the activation policy — a pre-activation plan must be all
     * {@code LEGACY} with empty {@code shadowModules} — is enforced by
     * the release action (R3 precondition 3), so a faulted plan is
     * exercisable as a release-action negative.
     */
    public record PreActivationPlan(String buildId, String entryModule,
                                    List<String> moduleRoutes,
                                    List<String> shadowModules) {

        public PreActivationPlan {
            Objects.requireNonNull(buildId, "buildId must not be null");
            Objects.requireNonNull(entryModule, "entryModule must not be null");
            moduleRoutes = List.copyOf(moduleRoutes);
            shadowModules = List.copyOf(shadowModules);
            if (moduleRoutes.isEmpty()) {
                throw new IllegalArgumentException("a recorded production plan names "
                    + "at least one module route");
            }
        }
    }

    /**
     * The versioned evidence record: the named cases, consumers, and
     * release state plus the recorded pre-activation production plans.
     * Shape validation (non-null components) lives here; the activation
     * policy — at least one named case, the exact three common
     * consumers, and at least one recorded plan — is enforced by the
     * release action (R3 precondition 2/3), so a faulted record is
     * exercisable as a release-action negative.
     */
    public record Evidence(int formatVersion, String producerIssue,
                           ReleaseState releaseState, List<String> caseLocators,
                           List<String> consumers, boolean complete,
                           boolean green, List<PreActivationPlan> productionPlans) {

        public Evidence {
            Objects.requireNonNull(producerIssue, "producerIssue must not be null");
            Objects.requireNonNull(releaseState, "releaseState must not be null");
            caseLocators = List.copyOf(caseLocators);
            consumers = List.copyOf(consumers);
            productionPlans = List.copyOf(productionPlans);
            if (productionPlans.isEmpty()) {
                throw new IllegalArgumentException("the evidence record records at "
                    + "least one pre-activation production plan");
            }
        }
    }
    /**
     * The E11 all-common case locators (repository-relative fixture
     * paths under {@code test/conformance/}): the designated converged
     * subset of the differential gate corpus — every case passes
     * byte-exact on all three lanes, pinned by
     * {@code deal.test.conformance.DifferentialGateLanesCorpusTest}.
     */
    private static final List<String> E11_CASE_LOCATORS = List.of(
        "backend-runtime/control-flow/if-else.deal",
        "backend-runtime/functions/direct-recursion.deal",
        "backend-runtime/stdlib/string/length-unicode.deal",
        "backend-runtime/error-handling/try-catch.deal",
        "backend-runtime/error-handling/throw-error.deal",
        "backend-runtime/control-flow/continue.deal");

    /**
     * The recorded pre-activation production plans (R3 precondition 3):
     * the armed-state public-build plans recorded while the release state
     * was {@code PRE_ACTIVATION} — the int-using public build and the
     * trivial CLI build, both all-{@code LEGACY} with empty
     * {@code shadowModules} (production {@code SHARED} was unreachable
     * under F4 rule 3).
     */
    private static final List<PreActivationPlan> PRE_ACTIVATION_PLANS = List.of(
        new PreActivationPlan("PRE_ACTIVATION_PUBLIC_INT_ADD", "main",
            List.of("LEGACY"), List.of()),
        new PreActivationPlan("PRE_ACTIVATION_CLI_TRIVIAL", "main",
            List.of("LEGACY"), List.of()));

    /**
     * The authored activation evidence record (R3): complete and green,
     * release state {@code PRE_ACTIVATION}, the three common consumers,
     * and the recorded pre-activation production plans.
     */
    public static final Evidence E11_EVIDENCE = new Evidence(
        FORMAT_VERSION, PRODUCER_ISSUE, ReleaseState.PRE_ACTIVATION,
        E11_CASE_LOCATORS, COMMON_CONSUMERS, true, true, PRE_ACTIVATION_PLANS);
}
