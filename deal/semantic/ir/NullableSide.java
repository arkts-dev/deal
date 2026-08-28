package deal.semantic.ir;

/**
 * The closed side mode of the {@code NULLABLE_*} binary selectors (parent
 * "Closed selectors"): which operand position carries the inner nullable
 * descriptor — {@code LEFT}, {@code RIGHT}, or {@code BOTH}. Missing and
 * language null compare as language null only at nullable reads.
 *
 * <p>Closed set — exactly the three values below; no open or unknown
 * fallback member and no external extension point exist. The side is a
 * {@code BINARY} payload field carried with the inner descriptor; other
 * selectors carry no side.</p>
 */
public enum NullableSide {

    /** The left operand is the nullable position. */
    LEFT,

    /** The right operand is the nullable position. */
    RIGHT,

    /** Both operands are nullable positions. */
    BOTH
}
