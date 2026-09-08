// =========================================================================
// E12ActivationReleaseAction — the public activation release action (R3)
// =========================================================================

package deal.test;

import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.Target;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The E12 public activation release action
 * (capability-promotion-activation-rollback-retention R3): the
 * precondition checks, the gate validation, the promotion composition
 * over the ISSUE-0485 {@code withState} transition surface, and the
 * post-condition assertions. Release/harness-owned validation data
 * authored in this gate-run conformance file (R6): production owns only
 * the transition surface and the release configuration — the committed
 * {@code ReleaseConfiguration} already carries the flip plus the
 * promotion list, and this action validates candidate configurations and
 * re-derives the promoted registry for the gate-run suites. No gate
 * policy lives inside {@code withState} (R1 invariant 6); all rejection
 * vocabulary below is release-action policy.
 *
 * <p><b>Preconditions (R3), each asserted before the flip and each
 * aborting with {@code ACTIVATION_PRECONDITION_FAILED <fact>}:</b>
 * (1) the armed gate — the configuration's release state is
 * {@code PRE_ACTIVATION}; (2) the E11 evidence is complete and green
 * under {@code PRE_ACTIVATION}; (3) no pre-activation production module
 * took a shared route — structurally (F4 rule 3) and evidentially (the
 * recorded plans, all {@code LEGACY} with empty {@code shadowModules});
 * (4) every pair in the release-owned promotion list is gate-approved
 * (all twelve items {@code PASS}, item 1 evaluated against this unit's
 * own flip); (5) the list is non-empty and promotes {@code SIGNED_INT32}
 * for both {@code LUAJIT} and {@code JVM}.</p>
 *
 * <p><b>The action (R3):</b> one atomic release change — the flip to
 * {@code V1_2_ACTIVE} plus the registry derivation composing
 * {@code withState(capability, target, PROMOTED)} for the list pairs in
 * the parent's capability order. The committed release state stays
 * flipped; negatives are exercised on derived configurations.</p>
 */
public final class E12ActivationReleaseAction {

    /** The named failure fact prefix of the activation preconditions. */
    public static final String ACTIVATION_PRECONDITION_FAILED =
        "ACTIVATION_PRECONDITION_FAILED";

    /** The promotion-attempt rejection of a gate-rejected pair (R2). */
    public static final String PROMOTION_GATE_REJECTED =
        "PROMOTION_GATE_REJECTED";

    /** The promotion-attempt rejection of an out-of-order capability (R2). */
    public static final String PROMOTION_ORDER_VIOLATED =
        "PROMOTION_ORDER_VIOLATED";

    /** The promotion-attempt rejection of the locked time capability (R2/D8). */
    public static final String PROMOTION_LOCKED = "PROMOTION_LOCKED";

    private E12ActivationReleaseAction() {
        // Static release-action surface only; no instances.
    }

    /**
     * One candidate activation configuration: the release state observed
     * before the flip, the evidence record, the promotion gate records,
     * and the promotion list. The release action validates and composes
     * over this configuration without editing the committed release
     * constant.
     */
    public record Configuration(ReleaseState releaseState,
                                ActivationEvidenceRecord.Evidence evidence,
                                List<PromotionGateRecord.Record> gateRecords,
                                List<ReleaseConfiguration.ReleasePromotion> promotionList) {

        public Configuration {
            Objects.requireNonNull(releaseState, "releaseState must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
            gateRecords = List.copyOf(gateRecords);
            promotionList = List.copyOf(promotionList);
        }
    }

    /** The failure of the release action: the named fact and its message. */
    public record Failure(String fact, String message) {

        public Failure {
            Objects.requireNonNull(fact, "fact must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    /**
     * The action result: the composed promoted registry (whose digest
     * recomputes over its own entries) plus the asserted post-condition
     * facts, or the named failure with no composition.
     */
    public record Result(CapabilityRegistry registry, List<String> postConditionFacts,
                         Failure failure) {

        public Result {
            Objects.requireNonNull(postConditionFacts, "postConditionFacts must not be null");
        }

        /** Whether the action succeeded (registry composed, facts asserted). */
        public boolean success() {
            return failure == null;
        }

        /** The failure text: {@code ACTIVATION_PRECONDITION_FAILED <fact> …}. */
        public String failureText() {
            return failure == null ? null
                : failure.fact() + " " + failure.message();
        }
    }

    /**
     * Runs the release action over the candidate configuration: asserts
     * the five preconditions in order, validates every promotion pair's
     * gate record, composes the {@code withState} transitions in
     * capability order, verifies the recomputed digest, and asserts the
     * post-conditions. On any failure the registry is not composed and
     * the named fact is returned.
     *
     * @param config the candidate activation configuration; non-null
     * @return the composed promoted registry or the named failure
     */
    public static Result applyActivation(Configuration config) {
        Objects.requireNonNull(config, "config must not be null");

        // Precondition 1: the armed gate (R3.1).
        if (config.releaseState() != ReleaseState.PRE_ACTIVATION) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "release state is not PRE_ACTIVATION (the armed gate; got "
                    + config.releaseState() + ")");
        }

        // Precondition 2: the E11 evidence is complete and green under
        // PRE_ACTIVATION (R3.2).
        ActivationEvidenceRecord.Evidence evidence = config.evidence();
        if (evidence.releaseState() != ReleaseState.PRE_ACTIVATION) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the activation evidence record was not produced under "
                    + "PRE_ACTIVATION (got " + evidence.releaseState() + ")");
        }
        if (!evidence.complete()) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the activation evidence record is incomplete");
        }
        if (!evidence.green()) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the activation evidence record is not green");
        }
        if (evidence.caseLocators().isEmpty()) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the activation evidence record names no all-common case");
        }
        if (!evidence.consumers().equals(ActivationEvidenceRecord.COMMON_CONSUMERS)) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the activation evidence consumers are not exactly the three common "
                    + "consumers; got " + evidence.consumers());
        }

        // Precondition 3: no pre-activation production module took a
        // shared route — evidential (the recorded plans) and structural
        // (F4 rule 3 makes production SHARED unreachable while
        // PRE_ACTIVATION).
        if (evidence.productionPlans().isEmpty()) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the activation evidence records no pre-activation production plan");
        }
        for (ActivationEvidenceRecord.PreActivationPlan plan : evidence.productionPlans()) {
            for (String route : plan.moduleRoutes()) {
                if (!"LEGACY".equals(route)) {
                    return failure(ACTIVATION_PRECONDITION_FAILED,
                        "a recorded pre-activation production plan of build '"
                            + plan.buildId() + "' took a shared route: " + route);
                }
            }
            if (!plan.shadowModules().isEmpty()) {
                return failure(ACTIVATION_PRECONDITION_FAILED,
                    "a recorded pre-activation production plan of build '"
                        + plan.buildId() + "' carries shadow modules: "
                        + plan.shadowModules());
            }
        }

        // Precondition 5: the promotion list is non-empty and promotes
        // SIGNED_INT32 for both shared targets (R3.5).
        if (config.promotionList().isEmpty()) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the promotion list is empty (a flip over an all-SHADOW registry "
                    + "leaves all-LEGACY plans, not an activation)");
        }
        for (Target target : Target.values()) {
            if (config.promotionList().stream().noneMatch(p ->
                    p.capability() == SemanticCapability.SIGNED_INT32
                        && p.target() == target)) {
                return failure(ACTIVATION_PRECONDITION_FAILED,
                    "the promotion list is missing SIGNED_INT32 for the shared "
                        + "target " + target);
            }
        }

        // Precondition 4 + promotion attempt contract (R2/R3.4): every
        // pair is gate-approved — capability order, the time lock, and
        // all twelve gate items PASS (item 1 evaluated against this
        // unit's own flip).
        Result gate = validatePromotionList(config.gateRecords(),
            config.promotionList());
        if (gate.failure() != null) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                gate.failure().message());
        }

        // The action: compose the transitions over the release default in
        // capability order (R3; the single release-owned surface, never
        // hand-rolled entries).
        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        for (ReleaseConfiguration.ReleasePromotion promotion : config.promotionList()) {
            registry = registry.withState(promotion.capability(), promotion.target(),
                CapabilityRegistry.State.PROMOTED);
        }

        // Digest discipline: the composed registry's digest is the
        // canonical recomputation over its own entries (never an
        // unchanged-hash claim; a transition surface that fails to
        // recompute the digest aborts here).
        if (!verifyRegistryDigest(registry, recomputedDigest(registry))) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the composed registry digest does not equal the canonical "
                    + "recomputation over its own entries (hash mismatch)");
        }
        if (registry.capabilityRegistryHash()
                .equals(CapabilityRegistry.releaseRegistry().capabilityRegistryHash())) {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "the composed registry digest equals the all-SHADOW release digest "
                    + "(an unchanged-hash claim)");
        }

        // Post-conditions (R3): the A1 matrix rows, the public profile
        // derivation, and the release-state hash over the flipped
        // configuration.
        List<String> postConditionFacts = new ArrayList<>();
        if (CompilerProfileProvider.publicProfile(ReleaseState.V1_2_ACTIVE)
                == SemanticProfile.DEAL_V1_2_INT32) {
            postConditionFacts.add(
                "publicProfile(V1_2_ACTIVE) == DEAL_V1_2_INT32");
        } else {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "publicProfile(V1_2_ACTIVE) != DEAL_V1_2_INT32");
        }
        if (CompilerProfileProvider.publicProfile(ReleaseState.PRE_ACTIVATION)
                == SemanticProfile.LEGACY_SAFE_INT) {
            postConditionFacts.add(
                "publicProfile(PRE_ACTIVATION) == LEGACY_SAFE_INT (the internal "
                    + "matrix row; never a post-activation rollback target)");
        } else {
            return failure(ACTIVATION_PRECONDITION_FAILED,
                "publicProfile(PRE_ACTIVATION) != LEGACY_SAFE_INT");
        }
        String releaseStateHash = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.V1_2_ACTIVE, registry.capabilityRegistryHash());
        postConditionFacts.add("releaseStateHash(V1_2_ACTIVE, promoted registry) == "
            + releaseStateHash + " (recomputed over the flipped configuration)");
        return new Result(registry, List.copyOf(postConditionFacts), null);
    }

    /**
     * The promotion-attempt contract (R2) over the candidate promotion
     * list: pairs must appear in the parent's capability order, the
     * {@code STDLIB_TIME_CONFLICT} lock holds, and every pair must carry
     * its twelve-item gate record with every item {@code PASS}. The
     * first violation is named — never a silent subset.
     *
     * @param gateRecords   the candidate gate records; non-null
     * @param promotionList the candidate promotion list; non-null
     * @return a success result (empty facts) or the named failure
     */
    static Result validatePromotionList(List<PromotionGateRecord.Record> gateRecords,
                                        List<ReleaseConfiguration.ReleasePromotion> promotionList) {
        SemanticCapability[] order = SemanticCapability.values();
        List<ReleaseConfiguration.ReleasePromotion> seen = new ArrayList<>();
        for (ReleaseConfiguration.ReleasePromotion promotion : promotionList) {
            // Capability order: no later pair may name a capability
            // declared earlier in the closed capability order.
            for (ReleaseConfiguration.ReleasePromotion earlier : seen) {
                if (indexOf(order, promotion.capability())
                        < indexOf(order, earlier.capability())) {
                    return failure(PROMOTION_ORDER_VIOLATED,
                        promotion.capability() + " (the list is out of the parent's "
                            + "capability order: " + promotion.capability() + " after "
                            + earlier.capability() + ")");
                }
            }
            seen.add(promotion);

            // The locked time capability (parent D8): never promotable.
            if (promotion.capability() == SemanticCapability.STDLIB_TIME_CONFLICT) {
                return failure(PROMOTION_LOCKED,
                    "STDLIB_TIME_CONFLICT (the parent D8 time conflict lock holds "
                        + "until an authoritative API/spec resolution)");
            }

            // The twelve-item gate record: every item PASS.
            PromotionGateRecord.Record record = null;
            for (PromotionGateRecord.Record candidate : gateRecords) {
                if (candidate.capability() == promotion.capability()
                        && candidate.target() == promotion.target()) {
                    record = candidate;
                    break;
                }
            }
            if (record == null) {
                return failure(PROMOTION_GATE_REJECTED,
                    promotion.capability() + " " + promotion.target()
                        + " (no promotion gate record)");
            }
            for (PromotionGateRecord.Item item : record.items()) {
                if (item.status() != PromotionGateRecord.Status.PASS) {
                    return failure(PROMOTION_GATE_REJECTED,
                        promotion.capability() + " " + promotion.target() + " "
                            + item.gate());
                }
            }
        }
        return new Result(null, List.of(), null);
    }

    private static int indexOf(SemanticCapability[] order, SemanticCapability capability) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == capability) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown capability " + capability);
    }

    /**
     * The canonical digest recomputation over the registry's own entries
     * (the existing {@link CanonicalJson} derivation, never hand-rolled).
     */
    static String recomputedDigest(CapabilityRegistry registry) {
        return CanonicalJson.sha256Hex(
            CanonicalJson.serializeBytes(registry.canonicalJson()));
    }

    /**
     * The digest verification (R3 hash discipline): the registry's
     * recorded digest must equal the expected canonical recomputation.
     * A transition surface that fails to recompute the digest is
     * detected here and the action aborts.
     */
    static boolean verifyRegistryDigest(CapabilityRegistry registry,
                                        String expectedDigest) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(expectedDigest, "expectedDigest must not be null");
        return registry.capabilityRegistryHash().equals(expectedDigest);
    }

    private static Result failure(String fact, String message) {
        return new Result(null, List.of(), new Failure(fact, message));
    }
}
