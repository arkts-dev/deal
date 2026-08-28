package deal.semantic.ir;

/**
 * The closed binary selector set of {@code deal.semantic-ir/1} (parent
 * "Closed selectors"; schema S3).
 *
 * <p>Closed set — exactly the 40 values below in the pinned order; no open
 * or unknown fallback member and no external extension point exist.
 * Unknown or reserved selectors fail validation (R-ENUM). Selector→policy
 * is fixed by the parent: signed32 add/sub/mul use {@code INT32_RESULT},
 * div/mod use {@code INT32_DIVISOR_THEN_RESULT}, pow uses
 * {@code INT32_EXPONENT_THEN_RESULT}; all number operations and
 * comparisons use {@code NO_DEAL_FAILURE}. Number modulo is
 * {@code a-floor(a/b)*b}; number division and power use IEEE-754.
 * {@code NULLABLE_*} payload carries the inner descriptor and the side
 * mode {@code LEFT|RIGHT|BOTH} ({@link NullableSide}); missing and
 * language null compare as language null only at nullable reads.
 * {@code REFERENCE_*} requires one equal checked descriptor and an
 * {@code ARRAY|TABLE|CLASS|FUNCTION} value; it compares allocation or
 * function identity. Short circuit is {@code BRANCH} with
 * {@code LOGICAL_AND}/{@code LOGICAL_OR}, never {@code BINARY}.</p>
 */
public enum BinarySelector implements ClosedSelector {

    // Signed32 arithmetic.
    INT32_ADD,
    INT32_SUB,
    INT32_MUL,
    INT32_DIV_TRUNC,
    INT32_MOD_TRUNC,
    INT32_POW,

    // Number arithmetic.
    NUMBER_ADD,
    NUMBER_SUB,
    NUMBER_MUL,
    NUMBER_DIV_IEEE,
    NUMBER_MOD_FLOOR,
    NUMBER_POW_IEEE,

    // Signed32 comparison.
    INT32_EQ,
    INT32_NE,
    INT32_LT,
    INT32_LE,
    INT32_GT,
    INT32_GE,

    // Number comparison (IEEE-754, including NaN).
    NUMBER_EQ,
    NUMBER_NE,
    NUMBER_LT,
    NUMBER_LE,
    NUMBER_GT,
    NUMBER_GE,

    // String comparison (Unicode-scalar lexicographic order).
    STRING_EQ,
    STRING_NE,
    STRING_LT,
    STRING_LE,
    STRING_GT,
    STRING_GE,

    // Boolean and null equality.
    BOOLEAN_EQ,
    BOOLEAN_NE,
    NULL_EQ,
    NULL_NE,

    // Nullable equality (inner descriptor + side mode LEFT|RIGHT|BOTH).
    NULLABLE_EQ,
    NULLABLE_NE,
    NULLABLE_NULL_EQ,
    NULLABLE_NULL_NE,

    // Reference identity equality/inequality.
    REFERENCE_EQ,
    REFERENCE_NE
}
