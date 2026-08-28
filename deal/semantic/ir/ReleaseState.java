package deal.semantic.ir;

/**
 * The release state of a compiler invocation (parent D2; foundation F1).
 *
 * <p>Closed set — exactly {@link #PRE_ACTIVATION} and
 * {@link #V1_2_ACTIVE}; no open or unknown fallback member and no external
 * extension point exist. The release state is release-owned configuration,
 * never a source or CLI selection: {@code PRE_ACTIVATION} derives the
 * public profile {@code LEGACY_SAFE_INT} and makes production
 * {@code SHARED} routing impossible, while {@code V1_2_ACTIVE} derives
 * {@code DEAL_V1_2_INT32} and makes shared routing eligible.</p>
 */
public enum ReleaseState {

    /** Before the v1.2 activation cutover: public builds use the legacy profile. */
    PRE_ACTIVATION,

    /** After activation: public builds use the v1.2 signed-32-bit profile. */
    V1_2_ACTIVE
}
