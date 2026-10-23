package deal.semantic;

import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.UnarySelector;

import java.util.Objects;

public final class SharedValueSemantics {

    private SharedValueSemantics() { /* pure static primitive: no state, no instances */ }

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


            case NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE, NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT, NUMBER_GE -> FailurePolicyId.NO_DEAL_FAILURE;
            case STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE,
                 BOOLEAN_EQ, BOOLEAN_NE, NULL_EQ, NULL_NE, NULLABLE_EQ, NULLABLE_NE,
                 NULLABLE_NULL_EQ, NULLABLE_NULL_NE, REFERENCE_EQ, REFERENCE_NE ->
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

            case NUMBER_NEG, BOOL_NOT -> FailurePolicyId.NO_DEAL_FAILURE;
        };
    }

}
