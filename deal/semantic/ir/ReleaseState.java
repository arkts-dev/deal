package deal.semantic.ir;

/**
 * The release state of a compiler invocation (parent D2; foundation F1).
 *
 * <p>Closed set — exactly {@link #V1_4_ACTIVE}; no open or unknown
 * fallback member and no external extension point exist. The release state
 * is release-owned configuration, never a source or CLI selection, and it
 * selects no semantic arm: the sole {@code DEAL_V1_4_BINARY64} profile is the
 * one executable semantics.</p>
 */
public enum ReleaseState {

    /** The sole active release state: public builds use DEAL_V1_4_BINARY64. */
    V1_4_ACTIVE
}
