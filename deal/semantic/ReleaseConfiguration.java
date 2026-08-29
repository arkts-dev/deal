package deal.semantic;

import deal.semantic.ir.ReleaseState;

/**
 * The single release-owned configuration selection point (A2): the
 * release state that drives the public-profile derivation plus the
 * release capability registry (foundation F7).
 *
 * <p>{@link #CURRENT_RELEASE_STATE} is pinned to
 * {@link ReleaseState#PRE_ACTIVATION} at this stage's gate: public
 * builds derive {@code LEGACY_SAFE_INT} and production {@code SHARED}
 * routing stays ineligible (F4 rule 3). The public activation flip is
 * performed only in E12 and only after E11's pre-activation
 * common-closure evidence is green; it is exactly one atomic release
 * change — editing this constant to {@link ReleaseState#V1_2_ACTIVE}
 * (plus, in E12's release unit, the capability promotion transitions
 * ISSUE-0241 owns). No source pragma or CLI flag selects the release
 * state or a profile: {@code deal/Main.java} and
 * {@code CompilationOrchestrator.defaultInvocation()} both consume this
 * class, and no other code path derives the public profile.</p>
 *
 * <p>Post-activation rollback (A3) never touches this class: rollback
 * keeps {@code DEAL_V1_2_INT32} and changes only the module emitter
 * route — by construction there is no derivation path back to a legacy
 * public profile once {@code V1_2_ACTIVE} is set.</p>
 */
public final class ReleaseConfiguration {

    /**
     * The release state driving
     * {@link CompilerProfileProvider#publicProfile(ReleaseState)} (A2):
     * pinned to {@code PRE_ACTIVATION} at this stage's gate; the public
     * flip (E12's release action) is exactly editing this constant.
     */
    public static final ReleaseState CURRENT_RELEASE_STATE =
        ReleaseState.PRE_ACTIVATION;

    private ReleaseConfiguration() {
        // Release-owned static configuration only; no instances.
    }

    /**
     * The release capability registry (foundation F7): the single closed,
     * immutable, all-{@code SHADOW} capability × target registry.
     * {@code deal/Main.java}, {@code CompilationOrchestrator}, and the
     * internal harnesses consume exactly this instance for invocation
     * resolution and route planning.
     *
     * @return the immutable release registry
     */
    public static CapabilityRegistry releaseCapabilityRegistry() {
        return CapabilityRegistry.releaseRegistry();
    }
}
