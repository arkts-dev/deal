package deal.semantic.ir;

/**
 * The closed unary selector set of {@code deal.semantic-ir/1} (parent
 * "Closed selectors"; schema S3).
 *
 * <p>Closed set — exactly {@link #BOOL_NOT}, {@link #INT32_NEG}, and
 * {@link #NUMBER_NEG}; no open or unknown fallback member and no external
 * extension point exist. Unknown or reserved selectors fail validation
 * (R-ENUM). Signed32 negation uses the {@code INT32_RESULT} policy;
 * number negation never fails.</p>
 */
public enum UnarySelector implements ClosedSelector {

    /** Logical not over booleans. */
    BOOL_NOT,

    /** Signed32 arithmetic negation (policy {@code INT32_RESULT}). */
    INT32_NEG,

    /** IEEE-754 number negation (policy {@code NO_DEAL_FAILURE}). */
    NUMBER_NEG
}
