package deal.semantic;

import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;

import java.util.List;
import java.util.Objects;

/**
 * The single release-owned configuration selection point (A2): the
 * release state that drives the public-profile derivation plus the
 * release capability registry (foundation F7).
 *
 * <p>E12's public activation release action is performed here and is now
 * complete: {@link #CURRENT_RELEASE_STATE} is pinned to
 * {@link ReleaseState#V1_2_ACTIVE} and
 * {@link #releaseCapabilityRegistry()} carries the release-owned E12
 * promotion list (R3's one atomic release change — the constant edit
 * plus the registry promotion transitions ISSUE-0241 owns). Public
 * builds derive {@code DEAL_V1_2_INT32} and production {@code SHARED}
 * routing is eligible (F4 rule 4) for exactly the promoted
 * capability &times; target pairs; a module using {@code int} requires
 * target capability {@code SIGNED_INT32} at plan time (small literals
 * waive nothing, foundation I3). No source pragma or CLI flag selects
 * the release state or a profile: {@code deal/Main.java} and
 * {@code CompilationOrchestrator.defaultInvocation()} both consume this
 * class, and no other code path derives the public profile.</p>
 *
 * <p>Post-activation rollback (A3) never touches this class: rollback
 * keeps {@code DEAL_V1_2_INT32} and changes only the module emitter
 * route — by construction there is no derivation path back to a legacy
 * public profile now that {@code V1_2_ACTIVE} is set. The pre-activation
 * matrix row ({@code PRE_ACTIVATION &rarr; LEGACY_SAFE_INT}) remains an
 * internal derivation fact of {@link CompilerProfileProvider} forever —
 * it is a route/regression inspection surface, never a production
 * rollback target.</p>
 */
public final class ReleaseConfiguration {

    /**
     * The release state driving
     * {@link CompilerProfileProvider#publicProfile(ReleaseState)} (A2):
     * pinned to {@code V1_2_ACTIVE} by E12's public activation release
     * action (the single release-owned constant edit of the one atomic
     * release change).
     */
    public static final ReleaseState CURRENT_RELEASE_STATE =
        ReleaseState.V1_2_ACTIVE;

    /**
     * One release-owned promotion transition of the E12 promotion list:
     * the closed (capability &times; target) pair to promote, ordered by
     * the parent's capability order (the {@link SemanticCapability}
     * declaration order) with {@code LUAJIT} before {@code JVM} within
     * each capability. The list is release-owned data only — no gate
     * policy lives here (R1 invariant 6); gate validation lives in the
     * release action and the gate-run suites.
     */
    public record ReleasePromotion(SemanticCapability capability, Target target) {

        public ReleasePromotion {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(target, "target must not be null");
        }
    }

    /**
     * The release-owned E12 promotion list (R3): non-empty, in the
     * parent's capability order, carrying the post-flip minimum content
     * — {@code SIGNED_INT32} promoted for both shared targets
     * ({@code LUAJIT} and {@code JVM}) — plus {@code FOUNDATION_VALUES}
     * for both targets (every module manifest claims
     * {@code FOUNDATION_VALUES}, foundation F3, so the post-flip
     * shared-routing eligibility check of an int-using module requires
     * both capabilities PROMOTED for the target). The list is the
     * registry half of E12's one atomic release change.
     */
    private static final List<ReleasePromotion> ACTIVATION_PROMOTIONS = List.of(
        new ReleasePromotion(SemanticCapability.FOUNDATION_VALUES, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.FOUNDATION_VALUES, Target.JVM),
        new ReleasePromotion(SemanticCapability.SIGNED_INT32, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.SIGNED_INT32, Target.JVM));

    /**
     * The release registry after E12's activation release action: the
     * all-{@code SHADOW} {@link CapabilityRegistry#releaseRegistry()}
     * composed with {@link #ACTIVATION_PROMOTIONS} through the single
     * release-owned transition surface
     * {@link CapabilityRegistry#withState(SemanticCapability, Target,
     * CapabilityRegistry.State)} in capability order. The derivation is
     * pure and policy-free (R1 invariant 6); the composed registry is
     * immutable and its digest recomputes over its own entries.
     */
    private static final CapabilityRegistry RELEASE_REGISTRY = deriveReleaseRegistry();

    private ReleaseConfiguration() {
        // Release-owned static configuration only; no instances.
    }

    /**
     * Composes the release registry from the all-SHADOW release default
     * and the E12 promotion list, in list order (the parent's capability
     * order). Deterministic; the source {@code releaseRegistry()} stays
     * byte-unchanged.
     *
     * @return the immutable promoted release registry
     */
    private static CapabilityRegistry deriveReleaseRegistry() {
        CapabilityRegistry registry = CapabilityRegistry.releaseRegistry();
        for (ReleasePromotion promotion : ACTIVATION_PROMOTIONS) {
            registry = registry.withState(promotion.capability(), promotion.target(),
                CapabilityRegistry.State.PROMOTED);
        }
        return registry;
    }

    /**
     * The release-owned E12 promotion list (R3): the ordered
     * (capability &times; target) pairs the activation release unit
     * promotes, in the parent's capability order with {@code LUAJIT}
     * before {@code JVM}. Read-only release data consumed by the release
     * action's gate validation and the gate-run suites.
     *
     * @return the immutable promotion list (never empty)
     */
    public static List<ReleasePromotion> activationPromotions() {
        return ACTIVATION_PROMOTIONS;
    }

    /**
     * The release capability registry (foundation F7) after E12's
     * activation: {@link CapabilityRegistry#releaseRegistry()} composed
     * with the E12 promotion list through {@code withState} —
     * {@code FOUNDATION_VALUES} and {@code SIGNED_INT32} promoted for
     * {@code LUAJIT} and {@code JVM}, every other entry {@code SHADOW}.
     * {@code deal/Main.java}, {@code CompilationOrchestrator}, and the
     * internal harnesses consume exactly this instance for invocation
     * resolution and route planning.
     *
     * @return the immutable promoted release registry
     */
    public static CapabilityRegistry releaseCapabilityRegistry() {
        return RELEASE_REGISTRY;
    }
}
