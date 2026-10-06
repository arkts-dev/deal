package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

public final class ComparisonExecutor {

    private ComparisonExecutor() {
        // Static surface only; pure and stateless.
    }

    /**
     * An executor producer defect: a selector or operand/payload shape
     * outside the closed comparison table reached the executor. Internal
     * control flow — fail closed, never a DEAL projection and never a
     * crash.
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }
    }

    // =========================================================================
    // compare — the closed 30-selector execution form
    // =========================================================================

    /**
     * Executes exactly one comparison selector of the closed table over
     * the completed operand views and returns the boolean result
     * (B-D1/B-D2). Pure, deterministic, stateless: no failure, no
     * boundary execution, no mutation, and each operand is consumed at
     * most once.
     *
     */
    public static boolean compare(BinarySelector selector, ComparisonOperandView left,
                                  ComparisonOperandView right, RuntimeDescriptor innerDescriptor,
                                  NullableSide side) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
        return switch (selector) {
            case NULLABLE_NULL_EQ, NULLABLE_NULL_NE -> {
                requireNullableInner(innerDescriptor);
                NullableSide namedSide = requireNamedSide(side, selector);
                ComparisonOperandView named = namedSide == NullableSide.LEFT ? left : right;
                boolean eq = isNullish(named);
                yield selector == BinarySelector.NULLABLE_NULL_EQ ? eq : !eq;
            }
            case NULLABLE_EQ, NULLABLE_NE -> {
                requireNullableInner(innerDescriptor);
                if (side == null) {
                    throw new Defect("the NULLABLE_* side mode must not be null "
                        + "(LEFT|RIGHT|BOTH)");
                }
                if (isNullish(left) || isNullish(right)) {
                    boolean bothNullish = isNullish(left) && isNullish(right);
                    yield selector == BinarySelector.NULLABLE_EQ ? bothNullish : !bothNullish;
                }
                boolean eq = nullableInnerEquals(innerDescriptor, left, right);
                yield selector == BinarySelector.NULLABLE_EQ ? eq : !eq;
            }
            case REFERENCE_EQ, REFERENCE_NE -> {
                requireReferenceDescriptor(innerDescriptor);
                if (isNullish(left) || isNullish(right)) {
                    boolean bothNullish = isNullish(left) && isNullish(right);
                    yield selector == BinarySelector.REFERENCE_EQ ? bothNullish : !bothNullish;
                }
                boolean eq = referenceEquals(left, right);
                yield selector == BinarySelector.REFERENCE_EQ ? eq : !eq;
            }
            case BYTES_EQ, BYTES_NE -> {
                requireBytesDescriptor(innerDescriptor);
                if (isNullish(left) || isNullish(right)) {
                    boolean bothNullish = isNullish(left) && isNullish(right);
                    yield selector == BinarySelector.BYTES_EQ ? bothNullish : !bothNullish;
                }
                boolean eq = referenceEquals(left, right);
                yield selector == BinarySelector.BYTES_EQ ? eq : !eq;
            }
            case INT32_EQ, INT32_NE, INT32_LT, INT32_LE, INT32_GT, INT32_GE,
                 NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT, NUMBER_GE,
                 STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE,
                 BOOLEAN_EQ, BOOLEAN_NE, NULL_EQ, NULL_NE -> {
                if (isNullish(left) || isNullish(right)) {
                    boolean bothNullish = isNullish(left) && isNullish(right);
                    yield switch (selector) {
                        case INT32_EQ, NUMBER_EQ, STRING_EQ, BOOLEAN_EQ, NULL_EQ -> bothNullish;
                        case INT32_NE, NUMBER_NE, STRING_NE, BOOLEAN_NE, NULL_NE -> !bothNullish;
                        case INT32_LT, INT32_LE, INT32_GT, INT32_GE, NUMBER_LT, NUMBER_LE,
                             NUMBER_GT, NUMBER_GE, STRING_LT, STRING_LE, STRING_GT, STRING_GE ->
                            false;
                        default -> throw new Defect(
                            "unreachable null-rule dispatch for " + selector.name());
                    };
                }
                yield scalarCompare(selector, left, right);
            }
            default -> throw new Defect("selector " + selector.name() + " is an arithmetic "
                + "selector: comparison execution covers the 30 comparison selectors only "
                + "(the signed-int32/containers epics own arithmetic execution)");
        };
    }

    // =========================================================================
    // The B-D1 null rules
    // =========================================================================

    /**
     * A null/missing operand: language null or the internal missing of a
     * past-end comparison-operand read — the B-D1 equivalence, applied
     * at both operand positions.
     */
    private static boolean isNullish(ComparisonOperandView view) {
        return view instanceof ComparisonOperandView.Null
            || view instanceof ComparisonOperandView.Missing;
    }

    // =========================================================================
    // Payload shape guards (fail closed)
    // =========================================================================

    /** The {@code NULLABLE_*} inner descriptor: non-null, never null, never another nullable. */
    private static void requireNullableInner(RuntimeDescriptor innerDescriptor) {
        if (innerDescriptor == null) {
            throw new Defect("the NULLABLE_* selectors carry the inner descriptor "
                + "(BinaryPayload.innerDescriptor); null is a producer defect");
        }
        if (innerDescriptor instanceof RuntimeDescriptor.Null
                || innerDescriptor instanceof RuntimeDescriptor.Nullable) {
            throw new Defect("the NULLABLE_* inner descriptor must not be null or nullable "
                + "(the closed RuntimeDescriptor invariant); got "
                + innerDescriptor.canonicalSpecText());
        }
    }

    /** {@code NULLABLE_NULL_*}: the side names the nullable operand (LEFT|RIGHT, never BOTH). */
    private static NullableSide requireNamedSide(NullableSide side, BinarySelector selector) {
        if (side != NullableSide.LEFT && side != NullableSide.RIGHT) {
            throw new Defect(selector.name() + " names the nullable operand by its side mode "
                + "(LEFT|RIGHT); got " + side);
        }
        return side;
    }

    /** {@code REFERENCE_*}: one equal checked descriptor of kind ARRAY|TABLE|CLASS|FUNCTION. */
    private static void requireReferenceDescriptor(RuntimeDescriptor descriptor) {
        if (!(descriptor instanceof RuntimeDescriptor.Array)
                && !(descriptor instanceof RuntimeDescriptor.Table)
                && !(descriptor instanceof RuntimeDescriptor.Class)
                && !(descriptor instanceof RuntimeDescriptor.Func)) {
            throw new Defect("REFERENCE_* requires one equal checked descriptor with kind "
                + "ARRAY|TABLE|CLASS|FUNCTION; got "
                + (descriptor == null ? "null" : descriptor.canonicalSpecText()));
        }
    }

    /** {@code BYTES_*}: the one equal checked descriptor is the bytes descriptor. */
    private static void requireBytesDescriptor(RuntimeDescriptor descriptor) {
        if (!(descriptor instanceof RuntimeDescriptor.Bytes)) {
            throw new Defect("BYTES_* requires the one equal checked bytes descriptor "
                + "(RuntimeDescriptor.Bytes); got "
                + (descriptor == null ? "null" : descriptor.canonicalSpecText()));
        }
    }

    // =========================================================================
    // The B-D2 value rules
    // =========================================================================

    /** The plain scalar families' value dispatch (both operands non-null/missing). */
    private static boolean scalarCompare(BinarySelector selector, ComparisonOperandView left,
                                         ComparisonOperandView right) {
        return switch (selector) {
            case INT32_EQ -> intView(left).value() == intView(right).value();
            case INT32_NE -> intView(left).value() != intView(right).value();
            case INT32_LT -> intView(left).value() < intView(right).value();
            case INT32_LE -> intView(left).value() <= intView(right).value();
            case INT32_GT -> intView(left).value() > intView(right).value();
            case INT32_GE -> intView(left).value() >= intView(right).value();
            case NUMBER_EQ -> numberView(left).value() == numberView(right).value();
            case NUMBER_NE -> numberView(left).value() != numberView(right).value();
            case NUMBER_LT -> numberView(left).value() < numberView(right).value();
            case NUMBER_LE -> numberView(left).value() <= numberView(right).value();
            case NUMBER_GT -> numberView(left).value() > numberView(right).value();
            case NUMBER_GE -> numberView(left).value() >= numberView(right).value();
            case STRING_EQ -> stringCompare(left, right) == 0;
            case STRING_NE -> stringCompare(left, right) != 0;
            case STRING_LT -> stringCompare(left, right) < 0;
            case STRING_LE -> stringCompare(left, right) <= 0;
            case STRING_GT -> stringCompare(left, right) > 0;
            case STRING_GE -> stringCompare(left, right) >= 0;
            case BOOLEAN_EQ -> boolView(left).value() == boolView(right).value();
            case BOOLEAN_NE -> boolView(left).value() != boolView(right).value();
            case NULL_EQ, NULL_NE -> throw new Defect(selector.name() + " consumes null "
                + "literals: value operand views reaching it are a producer defect");
            default -> throw new Defect("unreachable value dispatch for " + selector.name());
        };
    }

    /** {@code NULLABLE_EQ/NE} inner values: the inner descriptor's equality rule (B-D2). */
    private static boolean nullableInnerEquals(RuntimeDescriptor inner, ComparisonOperandView left,
                                               ComparisonOperandView right) {
        return switch (inner) {
            case RuntimeDescriptor.Int ignored -> intEq(left, right);
            case RuntimeDescriptor.Number ignored -> numberEq(left, right);
            case RuntimeDescriptor.String ignored -> stringEq(left, right);
            case RuntimeDescriptor.Boolean ignored -> booleanEq(left, right);
            case RuntimeDescriptor.Array ignored -> referenceEquals(left, right);
            case RuntimeDescriptor.Table ignored -> referenceEquals(left, right);
            case RuntimeDescriptor.Class ignored -> referenceEquals(left, right);
            case RuntimeDescriptor.Func ignored -> referenceEquals(left, right);
            case RuntimeDescriptor.Bytes ignored -> referenceEquals(left, right);
            case RuntimeDescriptor.Null ignored -> throw new Defect(
                "unreachable nullable-inner dispatch: the inner descriptor must not be null; "
                    + "got " + inner.canonicalSpecText());
            case RuntimeDescriptor.Nullable ignored -> throw new Defect(
                "unreachable nullable-inner dispatch: the inner descriptor must not be "
                    + "nullable; got " + inner.canonicalSpecText());
        };
    }

    // =========================================================================
    // Operand-family helpers
    // =========================================================================

    private static ComparisonOperandView.Int intView(ComparisonOperandView view) {
        if (view instanceof ComparisonOperandView.Int value) {
            return value;
        }
        throw new Defect("expected an Int operand view for a signed32 comparison, got "
            + variant(view));
    }

    private static ComparisonOperandView.Number numberView(ComparisonOperandView view) {
        if (view instanceof ComparisonOperandView.Number value) {
            return value;
        }
        throw new Defect("expected a Number operand view for an IEEE-754 comparison, got "
            + variant(view));
    }

    private static ComparisonOperandView.String stringView(ComparisonOperandView view) {
        if (view instanceof ComparisonOperandView.String value) {
            return value;
        }
        throw new Defect("expected a String operand view for a string comparison, got "
            + variant(view));
    }

    private static ComparisonOperandView.Boolean boolView(ComparisonOperandView view) {
        if (view instanceof ComparisonOperandView.Boolean value) {
            return value;
        }
        throw new Defect("expected a Boolean operand view for a boolean comparison, got "
            + variant(view));
    }

    private static ComparisonOperandView.Ref refView(ComparisonOperandView view) {
        if (view instanceof ComparisonOperandView.Ref value) {
            return value;
        }
        throw new Defect("expected a Ref operand view for a reference/bytes comparison, got "
            + variant(view));
    }

    /** The closed variant name for defect reporting. */
    private static String variant(ComparisonOperandView view) {
        return view.getClass().getSimpleName();
    }

    /** Signed32 integral equality. */
    private static boolean intEq(ComparisonOperandView left, ComparisonOperandView right) {
        return intView(left).value() == intView(right).value();
    }

    /** IEEE-754 equality (NaN unequal to itself; -0.0 equal to 0.0). */
    private static boolean numberEq(ComparisonOperandView left, ComparisonOperandView right) {
        return numberView(left).value() == numberView(right).value();
    }

    /** Unicode scalar-sequence equality. */
    private static boolean stringEq(ComparisonOperandView left, ComparisonOperandView right) {
        return stringCompare(left, right) == 0;
    }

    /** Boolean value equality. */
    private static boolean booleanEq(ComparisonOperandView left, ComparisonOperandView right) {
        return boolView(left).value() == boolView(right).value();
    }

    /**
     * Allocation/function identity: the two {@code Ref} tokens compare by
     * {@code Objects.equals} — instance tokens by object identity,
     * identity records by their value equality, which is the allocation
     * identity.
     */
    private static boolean referenceEquals(ComparisonOperandView left, ComparisonOperandView right) {
        return Objects.equals(refView(left).allocationIdentity(),
            refView(right).allocationIdentity());
    }

    /**
     * Unicode scalar (code point) lexicographic order of two validated
     * scalar sequences — never byte order, locale, or UTF-16 code-unit
     * order: a surrogate pair is one scalar, and every supplementary
     * scalar code point orders above every BMP scalar code point.
     *
     */
    private static int stringCompare(ComparisonOperandView left, ComparisonOperandView right) {
        List<Integer> leftScalars = UnicodeScalars.scalars(stringView(left).scalars());
        List<Integer> rightScalars = UnicodeScalars.scalars(stringView(right).scalars());
        int common = Math.min(leftScalars.size(), rightScalars.size());
        for (int i = 0; i < common; i++) {
            int compared = Integer.compare(leftScalars.get(i), rightScalars.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftScalars.size(), rightScalars.size());
    }
}
