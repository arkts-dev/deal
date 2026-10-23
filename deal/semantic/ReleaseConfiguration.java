package deal.semantic;

import deal.semantic.ir.ReleaseState;

/**
 * The release-owned configuration of the compiler (foundation F1/F7): the
 * single release state and the release capability registry every compile
 * resolves its invocation over. Both are release data only — they select
 * no semantic arm, no promotion and no compatibility mode; the sole
 * {@code DEAL_V1_4_BINARY64} profile executes on every backend.
 */
public final class ReleaseConfiguration {

    /**
     * The single release state of the v1.4 product. Source and the public
     * CLI cannot select it; it is recorded on every invocation.
     */
    public static final ReleaseState CURRENT_RELEASE_STATE =
        ReleaseState.V1_4_ACTIVE;

    private ReleaseConfiguration() {
        // Release-owned static configuration only; no instances.
    }

    /**
     * The release capability registry (foundation F7): the closed
     * capability × target cross product and its deterministic digest.
     * {@code deal/Main.java}, {@code CompilationOrchestrator}, and the
     * harnesses consume exactly this instance for invocation resolution.
     *
     */
    public static CapabilityRegistry releaseCapabilityRegistry() {
        return CapabilityRegistry.releaseRegistry();
    }
}
