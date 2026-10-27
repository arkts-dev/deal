package deal.semantic.ir;

public enum BinarySelector implements ClosedSelector {


// Number arithmetic.
    NUMBER_ADD,
    NUMBER_SUB,
    NUMBER_MUL,
    NUMBER_DIV_IEEE,


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
    UNDEFINED_EQ,
    UNDEFINED_NE,
    NULLABLE_UNDEFINED_EQ,
    NULLABLE_UNDEFINED_NE,

    // Nullable equality (inner descriptor + side mode LEFT|RIGHT|BOTH).
    NULLABLE_EQ,
    NULLABLE_NE,
    NULLABLE_NULL_EQ,
    NULLABLE_NULL_NE,

    // Reference identity equality/inequality.
    REFERENCE_EQ,
    REFERENCE_NE
}
