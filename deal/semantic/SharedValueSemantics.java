package deal.semantic;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.UnarySelector;

import java.util.Objects;

public final class SharedValueSemantics {

    private SharedValueSemantics() { /* pure static primitive: no state, no instances */ }

    /** The signed32 minimum, {@code -2147483648}. */
    public static final int INT32_MIN = -2147483648;

    /** The signed32 maximum, {@code 2147483647}. */
    public static final int INT32_MAX = 2147483647;

    // =========================================================================
    // Int32Result: Value(int) | Fail(code, template, origin)
    // =========================================================================

    /**
     * The closed int32 operation outcome (I2):
     * {@code Value(int)} on success or {@code Fail(code, template,
     * origin)} on failure. Every failure carries the caller-supplied
     * operation {@link SourceOrigin} and the canonical template; the code
     * and template come from the closed {@link FailureContractRegistry}
     * rows (this primitive never selects message text).
     */
    public sealed interface Int32Result
        permits Int32Result.Value, Int32Result.Fail {

        /** Success: the signed32 value. */
        record Value(int value) implements Int32Result {
        }

        /**
         * Failure: the DEAL-visible code, the canonical template
         * (instantiated registry-row text), and the caller-supplied
         * operation origin.
         */
        record Fail(DiagnosticCode code, String template, SourceOrigin origin)
            implements Int32Result {

            public Fail {
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(template, "template must not be null");
                Objects.requireNonNull(origin, "origin must not be null");
            }
        }

        /** Success factory. */
        static Int32Result value(int value) {
            return new Value(value);
        }

        /** Failure factory; non-null components are enforced by {@link Fail}. */
        static Int32Result fail(DiagnosticCode code, String template, SourceOrigin origin) {
            return new Fail(code, template, origin);
        }
    }

    // =========================================================================
    // Int32 arithmetic (INT32_RESULT)
    // =========================================================================

    /** {@code a + b} over the signed32 gate; long intermediate is exact. */
    public static Int32Result int32Add(int a, int b, SourceOrigin origin) {
        return checkInt32Integral((long) a + b, origin);
    }

    /** {@code a - b} over the signed32 gate; long intermediate is exact. */
    public static Int32Result int32Sub(int a, int b, SourceOrigin origin) {
        return checkInt32Integral((long) a - b, origin);
    }

    /** {@code a * b} over the signed32 gate; long intermediate is exact. */
    public static Int32Result int32Mul(int a, int b, SourceOrigin origin) {
        return checkInt32Integral((long) a * b, origin);
    }

    /**
     * {@code -a} over the signed32 gate; long intermediate is exact, so
     * {@code -(-2147483648)} → E8004 and {@code -0} → {@code 0}.
     */
    public static Int32Result int32Neg(int a, SourceOrigin origin) {
        return checkInt32Integral(-(long) a, origin);
    }

    // =========================================================================
    // Int32 division (INT32_DIVISOR_THEN_RESULT)
    // =========================================================================

    /**
     * Truncated division: zero divisor E8005 first, then truncation
     * toward zero ({@code long} {@code /}), then the signed32 gate —
     * {@code -2147483648 / -1} → E8004.
     */
    public static Int32Result int32Div(int a, int b, SourceOrigin origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        if (b == 0) {
            return failOf(FailurePolicyId.INT32_DIVISOR_THEN_RESULT, 0, origin);
        }
        return checkInt32Integral((long) a / b, origin);
    }



    // =========================================================================
    // Conversions (INT_CONVERSION / NUMBER_CONVERSION)
    // =========================================================================

    /**
     * The {@code int(x)} conversion in the normative order: NaN → E8001
     * {@code expected int, got NaN}; ±infinity → E8001
     * {@code expected int, got infinity}; fractional → E8001
     * {@code expected int, got non-integer number}; integral out of
     * range → E8004 {@code int out of safe range}. {@code -0.0} normalizes to
     * {@code 0} (negative zero does not exist in {@code int}).
     */
    public static Int32Result intFromNumber(double value, SourceOrigin origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        if (Double.isNaN(value)) {
            return failOf(FailurePolicyId.INT_CONVERSION, 1, origin);
        }
        if (Double.isInfinite(value)) {
            return failOf(FailurePolicyId.INT_CONVERSION, 2, origin);
        }
        if (value != Math.floor(value)) {
            return failOf(FailurePolicyId.INT_CONVERSION, 3, origin);
        }
        if (value < INT32_MIN || value > INT32_MAX) {
            return failOf(FailurePolicyId.INT32_RESULT, 0, origin);
        }
        return Int32Result.value((int) value);
    }

    /**
     * The int descriptor path tail (after kind/integrality checks):
     * an integral long outside the signed32 range → E8004
     * {@code int out of safe range}; otherwise the signed32 value.
     */
    public static Int32Result checkInt32Integral(long value, SourceOrigin origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        if (value < INT32_MIN || value > INT32_MAX) {
            return failOf(FailurePolicyId.INT32_RESULT, 0, origin);
        }
        return Int32Result.value((int) value);
    }

    /**
     * {@code number(x)}: the exact double of an integral value (every
     * int32 value is exactly representable; never fails).
     */
    public static double numberFromInt(long value) {
        return (double) value;
    }

    // =========================================================================
    // Number operations (NO_DEAL_FAILURE — never fail)
    // =========================================================================

    /** IEEE-754 double addition. */
    public static double numberAdd(double a, double b) {
        return a + b;
    }

    /** IEEE-754 double subtraction. */
    public static double numberSub(double a, double b) {
        return a - b;
    }

    /** IEEE-754 double multiplication. */
    public static double numberMul(double a, double b) {
        return a * b;
    }

    /** IEEE-754 double division: {@code x/0} → ±Infinity, {@code 0/0} → NaN. */
    public static double numberDiv(double a, double b) {
        return a / b;
    }

    /** IEEE-754 double negation: {@code -(-0.0)} → {@code +0.0}. */
    public static double numberNeg(double a) {
        return -a;
    }

    // =========================================================================
    // Comparisons
    // =========================================================================

    /** Logical negation over booleans (pinned {@code BOOL_NOT} row; never fails). */
    public static boolean boolNot(boolean value) {
        return !value;
    }

    /** Int32 equality (exact). */
    public static boolean int32Eq(int a, int b) {
        return a == b;
    }

    /** Int32 inequality (exact). */
    public static boolean int32Ne(int a, int b) {
        return a != b;
    }

    /** Int32 less-than (exact). */
    public static boolean int32Lt(int a, int b) {
        return a < b;
    }

    /** Int32 less-or-equal (exact). */
    public static boolean int32Le(int a, int b) {
        return a <= b;
    }

    /** Int32 greater-than (exact). */
    public static boolean int32Gt(int a, int b) {
        return a > b;
    }

    /** Int32 greater-or-equal (exact). */
    public static boolean int32Ge(int a, int b) {
        return a >= b;
    }

    /** Number equality (IEEE): {@code NaN != NaN}, {@code -0 == +0}. */
    public static boolean numberEq(double a, double b) {
        return a == b;
    }

    /** Number inequality (IEEE): {@code NaN != NaN} is true. */
    public static boolean numberNe(double a, double b) {
        return a != b;
    }

    /** Number less-than (IEEE): false when either operand is NaN. */
    public static boolean numberLt(double a, double b) {
        return a < b;
    }

    /** Number less-or-equal (IEEE): false when either operand is NaN. */
    public static boolean numberLe(double a, double b) {
        return a <= b;
    }

    /** Number greater-than (IEEE): false when either operand is NaN. */
    public static boolean numberGt(double a, double b) {
        return a > b;
    }

    /** Number greater-or-equal (IEEE): false when either operand is NaN. */
    public static boolean numberGe(double a, double b) {
        return a >= b;
    }

    // =========================================================================
    // Selector→policy row projection (cross-checked against the closed table)
    // =========================================================================

    /**
     * This primitive's row projection for the binary selectors it
     * implements: {@code INT32_ADD/SUB/MUL} → {@code INT32_RESULT};
     * {@code INT32_DIV_TRUNC} →
     * {@code INT32_DIVISOR_THEN_RESULT}; every covered
     * {@code NUMBER_*} arithmetic and every covered
     * {@code INT32_*}/{@code NUMBER_*} comparison →
     * {@code NO_DEAL_FAILURE}. The single closed assignment stays the
     * validator's table ({@code SemanticIrValidator.binaryPolicy}); the
     * gate test asserts this projection agrees with that table for every
     * covered selector — a selector stamped with any other policy is a
     * failure. Selectors outside this primitive's rows (string, boolean,
     * null, nullable, reference — the later construct epics' ownership)
     * fail closed.
     *
     */
    public static FailurePolicyId binarySelectorPolicy(BinarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case INT32_ADD, INT32_SUB, INT32_MUL -> FailurePolicyId.INT32_RESULT;
            case INT32_DIV_TRUNC ->
                FailurePolicyId.INT32_DIVISOR_THEN_RESULT;
            case NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE,
                 INT32_EQ, INT32_NE, INT32_LT, INT32_LE, INT32_GT,
                 INT32_GE, NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT,
                 NUMBER_GE -> FailurePolicyId.NO_DEAL_FAILURE;
            case STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE,
                 BOOLEAN_EQ, BOOLEAN_NE, NULL_EQ, NULL_NE, NULLABLE_EQ, NULLABLE_NE,
                 NULLABLE_NULL_EQ, NULLABLE_NULL_NE, REFERENCE_EQ, REFERENCE_NE,
                 BYTES_EQ, BYTES_NE ->
                throw new IllegalArgumentException("selector " + selector.name()
                    + " is outside the SharedValueSemantics rows (owned by the later "
                    + "construct epics)");
        };
    }

    /**
     * This primitive's row projection for the complete unary selector
     * set: {@code INT32_NEG} → {@code INT32_RESULT};
     * {@code NUMBER_NEG} → {@code NO_DEAL_FAILURE}; {@code BOOL_NOT} →
     * {@code NO_DEAL_FAILURE} (logical negation never fails — the
     * pinned {@link #boolNot} row). The single closed assignment stays
     * the validator's rule ({@code SemanticIrValidator.unaryPolicy});
     * the gate test asserts this projection agrees with that rule for
     * every unary selector — a selector stamped with any other policy is
     * a failure. No unary selector is outside this primitive's rows.
     *
     */
    public static FailurePolicyId unarySelectorPolicy(UnarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case INT32_NEG -> FailurePolicyId.INT32_RESULT;
            case NUMBER_NEG, BOOL_NOT -> FailurePolicyId.NO_DEAL_FAILURE;
        };
    }

    // =========================================================================
    // Registry-row projection (codes/templates stay the registry's single source)
    // =========================================================================

    /** Projects one registry row's pinned code + template into a failure at {@code origin}. */
    private static Int32Result failOf(FailurePolicyId policy, int templateIndex,
                                      SourceOrigin origin) {
        FailurePolicyRow row = FailureContractRegistry.row(policy);
        return Int32Result.fail(row.code(), row.templates().get(templateIndex), origin);
    }
}
