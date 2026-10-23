package deal.semantic.ir;

/**
 * The closed unary selector set of {@code deal.semantic-ir/2} (parent
 * "Closed selectors"; schema S3).
 *
 * <p>Closed set — exactly {@link #BOOL_NOT} and {@link #NUMBER_NEG};
 * no open or unknown fallback member and no external extension point exist.
 * Unknown or reserved selectors fail validation (R-ENUM).
 * Both selectors use {@code NO_DEAL_FAILURE}.</p>
 */
public enum UnarySelector implements ClosedSelector {

    /** Logical not over booleans. */
    BOOL_NOT,

    /** IEEE-754 number negation (policy {@code NO_DEAL_FAILURE}). */
    NUMBER_NEG
}
