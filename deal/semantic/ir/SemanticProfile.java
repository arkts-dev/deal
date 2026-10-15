package deal.semantic.ir;

/**
 * The project-wide semantic profile of a compiler invocation (parent D2;
 * foundation F1).
 *
 * <p>Closed set — exactly {@link #DEAL_V1_3_INT32}; no open or unknown
 * fallback member and no external extension point exist. Source and the
 * public CLI cannot select the profile: every compiler entrypoint uses the
 * one v1.3 signed-32-bit profile, and persisted executable data carrying
 * any other profile name is rejected and regenerated, never interpreted
 * with an alias profile.</p>
 */
public enum SemanticProfile {

    /** The sole v1.3 signed-32-bit semantics. */
    DEAL_V1_3_INT32
}
