package deal.semantic.ir;

import java.util.List;

/**
 * The closed standard-library function set of {@code deal.semantic-ir/1}
 * (parent D8, "Standard-library operation table"; schema S3).
 *
 * <p>Closed set — exactly the 20 values below in the pinned order; no open
 * or unknown fallback member and no external extension point exist. Each
 * id names one {@code STDLIB_CALL} whose algorithm, effect, result,
 * ordering, and failure policy are fixed by the parent's stdlib table;
 * consumers may invoke target helpers only after equivalence to that
 * operation is verified. {@code TIME_NOW_MILLIS} is a reserved selector
 * name, invalid in {@code deal.semantic-ir/1} ({@link #RESERVED_NAMES}):
 * the declared API {@code nowMillis(): int} cannot represent contemporary
 * epoch milliseconds under signed32, so the common layer defines no time
 * algorithm and no failure projection for it; a module referencing it
 * requires {@code STDLIB_TIME_CONFLICT} and stays on retained routes
 * (parent D8).</p>
 */
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
    MATH_MAX_INT;

    /**
     * The reserved stdlib selector names, invalid as
     * {@code StdlibFunctionId} values in {@code deal.semantic-ir/1}. They
     * are not enum members.
     */
    public static final List<String> RESERVED_NAMES = List.of(
        "TIME_NOW_MILLIS"
    );

    /** Returns true iff {@code name} is a reserved (invalid) stdlib selector name. */
    public static boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }
}
