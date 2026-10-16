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
 * {@code FOR_EACH(STRING_SCALARS)} validates the complete scalar sequence
 * before the first binding and yields one single-scalar string per
 * iteration; invalid UTF-8 or an unpaired surrogate uses
 * {@code TYPE_DESCRIPTOR} with actual kind {@code invalid-unicode}.</p>
 */
public enum IterationMode {

    /** Iterate array values in increasing index order. */
    ARRAY_VALUES,

    /** Iterate Unicode scalar values of a string. */
    STRING_SCALARS
}
