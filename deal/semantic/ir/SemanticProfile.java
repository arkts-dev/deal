package deal.semantic.ir;

/**
 * The project-wide semantic profile of a compiler invocation (parent D2;
 * foundation F1).
 *
 * <p>Closed set — exactly {@link #LEGACY_SAFE_INT} and
 * {@link #DEAL_V1_2_INT32}; no open or unknown fallback member and no
 * external extension point exist. Source and the public CLI cannot select
 * the profile: release state derives the public profile
 * ({@code PRE_ACTIVATION → LEGACY_SAFE_INT}, {@code V1_2_ACTIVE →
 * DEAL_V1_2_INT32}), and only internal harness factories may construct the
 * remaining purpose/profile combinations. {@code LEGACY_SAFE_INT} may be
 * inspected for routing and regression reporting but is never lowered;
 * lowering admits only {@link #DEAL_V1_2_INT32}.</p>
 */
public enum SemanticProfile {

    /** Pre-activation public profile: ±(2^53−1) safe integer semantics. */
    LEGACY_SAFE_INT,

    /** The v1.2 signed-32-bit semantics; the only profile ever lowered. */
    DEAL_V1_2_INT32
}
