package deal.semantic.ir;

/**
 * The purpose of a compiler invocation (parent D2; foundation F1).
 *
 * <p>Closed set — exactly {@link #PUBLIC_BUILD}, {@link #COMMON_SHADOW},
 * and {@link #LEGACY_REGRESSION}; no open or unknown fallback member and no
 * external extension point exist. Source and the public CLI cannot select
 * the purpose: public builds are {@link #PUBLIC_BUILD}, and the two
 * remaining purposes exist only as internal harness factories, never as
 * CLI flags. {@code COMMON_SHADOW} requires {@code DEAL_V1_2_INT32} and
 * {@code LEGACY_REGRESSION} requires {@code LEGACY_SAFE_INT}; a mismatch is
 * rejected at invocation resolution before checking or lowering.</p>
 */
public enum InvocationPurpose {

    /** Release-facing builds selected by compiler release configuration. */
    PUBLIC_BUILD,

    /** Internal harness shadow builds driving the common lowering layer. */
    COMMON_SHADOW,

    /** Internal harness-only legacy-profile regression builds. */
    LEGACY_REGRESSION
}
