package deal.semantic.ir;

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
    REFERENCE_NE,

    BYTES_EQ,
    BYTES_NE
}
