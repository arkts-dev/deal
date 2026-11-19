package deal.semantic.ir;

import java.util.List;

public enum StdlibFunctionId implements ClosedSelector {

    CONSOLE_LOG,
    CONSOLE_ERROR,
    STRING_LENGTH,
    STRING_SUBSTRING,
    STRING_CONTAINS,
    STRING_STARTS_WITH,
    STRING_ENDS_WITH,
    STRING_REPLACE,
    STRING_SPLIT,
    STRING_TRIM,
    TABLE_KEYS,
    JSON_PARSE,
    JSON_STRINGIFY,
    MATH_FLOOR,
    MATH_CEIL,
    MATH_SQRT,
    MATH_ABS_NUMBER,
    TIME_NOW_MILLIS,
    MATH_MIN,
    MATH_MAX,
    MATH_ROUND,
    NUMBER_IS_FINITE,
    NUMBER_IS_NAN,
    STRING_SLICE,
    STRING_INCLUDES_POSITION,
    STRING_STARTS_WITH_POSITION,
    STRING_ENDS_WITH_POSITION,
    STRING_INDEX_OF,
    STRING_LAST_INDEX_OF,
    STRING_TO_LOWER_CASE,
    STRING_TO_UPPER_CASE,
    STRING_TO_NUMBER,
    MATH_POW;

    /** Direct builtin algorithms with no callable stdlib module export. */
    public boolean isBuiltinOnly() {
        return switch (this) {
            case MATH_POW, STRING_TO_NUMBER, MATH_MIN, MATH_MAX, MATH_ROUND, NUMBER_IS_FINITE, NUMBER_IS_NAN, STRING_SLICE,
                 STRING_INCLUDES_POSITION, STRING_STARTS_WITH_POSITION, STRING_ENDS_WITH_POSITION,
                 STRING_INDEX_OF, STRING_LAST_INDEX_OF, STRING_TO_LOWER_CASE, STRING_TO_UPPER_CASE -> true;
            default -> false;
        };
    }

    /**
     * The reserved stdlib selector names, invalid as
     * {@code StdlibFunctionId} values in {@code deal.semantic-ir/2}. They
     * are not enum members. The list is empty; the guard
     * (deny-by-default: no reserved name is ever a valid selector) stays
     * as the closed-set machinery, exercised by the reserved
     * {@link FailurePolicyId} names.
     */
    public static final List<String> RESERVED_NAMES = List.of();

    public static boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }
}
