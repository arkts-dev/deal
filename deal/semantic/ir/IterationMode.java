package deal.semantic.ir;

/**
 * The closed iteration-mode set of {@code deal.semantic-ir/2} (parent
 * closed operation table; schema S3).
 *
 * <p>Closed set — exactly {@link #ARRAY_VALUES} and
 * {@link #STRING_SCALARS}; no open or unknown fallback member and no
 * external extension point exist. {@code FOR_EACH(ARRAY_VALUES)} evaluates
 * the iterable once, retains its reference, and traverses increasing live
 * positions: each step re-reads the carrier's live element count, so a
 * contiguous append is visited; the first position that reads absent (a
 * deleted slot or a position past the live end) stops the iteration, and a
 * present element (including a wrong one) is still checked — never skipped.
 * {@code FOR_EACH(STRING_SCALARS)} combines valid UTF-16 surrogate pairs
 * and yields isolated units unchanged. Malformed transport encodings use
 * {@code TYPE_DESCRIPTOR} with actual kind {@code invalid-unicode}; lone
 * surrogate units are legal string data.</p>
 */
public enum IterationMode {

    /** Iterate array values in increasing index order. */
    ARRAY_VALUES,

    /** Iterate strings pair-aware, preserving isolated UTF-16 units. */
    STRING_SCALARS
}
