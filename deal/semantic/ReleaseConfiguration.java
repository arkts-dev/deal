package deal.semantic;

import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;

import java.util.List;
import java.util.Objects;

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

    private static final List<ReleasePromotion> ACTIVATION_PROMOTIONS = List.of(
        new ReleasePromotion(SemanticCapability.FOUNDATION_VALUES, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.FOUNDATION_VALUES, Target.JVM),
        new ReleasePromotion(SemanticCapability.SIGNED_INT32, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.SIGNED_INT32, Target.JVM),
        new ReleasePromotion(SemanticCapability.CONTAINERS_AND_STRINGS, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.CONTAINERS_AND_STRINGS, Target.JVM),
        new ReleasePromotion(SemanticCapability.DESCRIPTORS, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.DESCRIPTORS, Target.JVM),
        new ReleasePromotion(SemanticCapability.BOUNDARIES, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.BOUNDARIES, Target.JVM),
        new ReleasePromotion(SemanticCapability.EVALUATION_ORDER, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.EVALUATION_ORDER, Target.JVM),
        new ReleasePromotion(SemanticCapability.BINDINGS, Target.LUAJIT),
        new ReleasePromotion(SemanticCapability.BINDINGS, Target.JVM));

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
     */
    public static List<ReleasePromotion> activationPromotions() {
        return ACTIVATION_PROMOTIONS;
    }

    /**
     * The release capability registry (foundation F7) after E12's
     * activation plus the step-1 through step-5 cutover promotions:
     * {@link CapabilityRegistry#releaseRegistry()} composed with the
     * E12 promotion list through {@code withState} —
     * {@code FOUNDATION_VALUES}, {@code SIGNED_INT32},
     * {@code CONTAINERS_AND_STRINGS}, {@code DESCRIPTORS},
     * {@code BOUNDARIES}, {@code EVALUATION_ORDER}, and
     * {@code BINDINGS} promoted for {@code LUAJIT} and {@code JVM},
     * every other entry {@code SHADOW}.
     * {@code deal/Main.java}, {@code CompilationOrchestrator}, and the
     * internal harnesses consume exactly this instance for invocation
     * resolution and route planning.
     *
     */
    public static CapabilityRegistry releaseCapabilityRegistry() {
        return RELEASE_REGISTRY;
    }
}
