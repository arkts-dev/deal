package deal.diagnostics;

/**
 * The origin of a {@link DiagnosticRange}.
 *
 * <ul>
 *   <li>{@link #SOURCE} — the range was computed from real source
 *       positions. A SOURCE range always carries exact known scalar
 *       offsets (the SOURCE-implies-known-offsets invariant); no producer
 *       may manufacture a SOURCE range from an anchor without offset
 *       information.</li>
 *   <li>{@link #SYNTHETIC} — the range has no source anchor. The
 *       canonical shape is {@code (file,1,1,1,1,0,0,0,SYNTHETIC)} plus an
 *       anchor note naming the missing anchor.</li>
 * </ul>
 */
public enum RangeOrigin {
    /** Computed from real source positions with exact known scalar offsets. */
    SOURCE,
    /** No source anchor; canonical zero-length shape plus an anchor note. */
    SYNTHETIC
}
