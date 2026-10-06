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
    MATH_ABS_INT,
    MATH_ABS_NUMBER,
    MATH_MIN_INT,
    MATH_MAX_INT,
    TIME_NOW_MILLIS;

    /**
     * The reserved stdlib selector names, invalid as
     * {@code StdlibFunctionId} values in {@code deal.semantic-ir/1}. They
     * are not enum members. The list is empty since
     * {@code TIME_NOW_MILLIS} became the 21st member (K7); the guard
     * (deny-by-default: no reserved name is ever a valid selector) stays
     * as the closed-set machinery, exercised by the reserved
     * {@link FailurePolicyId} names.
     */
    public static final List<String> RESERVED_NAMES = List.of();

    public static boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }
}
