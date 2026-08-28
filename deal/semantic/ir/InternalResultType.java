package deal.semantic.ir;

/**
 * The internal result-type sentinels of the mandatory op header (S2):
 * {@code INTERNAL_MISSING} marks an internal-missing value before an
 * optional read, and {@code INTERNAL_ASYNC} marks an in-flight async
 * operation. Both are schema-internal markers, never user-visible runtime
 * kinds, and both are admissible exactly where the closed operation table
 * permits them.
 *
 * <p>Closed set — exactly the two values below; no open or unknown
 * fallback member and no external extension point exist.</p>
 */
public enum InternalResultType implements OpResultType {

    /** Internal missing value (optional-read input). */
    INTERNAL_MISSING,

    /** In-flight async operation. */
    INTERNAL_ASYNC
}
