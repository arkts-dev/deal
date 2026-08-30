package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The single execution form of the closed comparison-semantics table of
 * {@code deal.semantic-ir/1} (ISSUE-0234 design B-D4,
 * binary-comparison-selectors): a static, pure, deterministic, stateless
 * executor over the closed {@link ComparisonOperandView} implementing
 * exactly the B-D2 per-selector semantics table with the B-D1
 * missing≡null rule. The semantic oracle calls it directly; the shared
 * LuaJIT/JVM emitters realize the same table over their target
 * representations (B-D6) and are conformance-tested against it.
 *
 * <p><b>Missing≡null (B-D1, both operand positions).</b> When either
 * operand is {@code Null} or {@code Missing}, every selector compares
 * with the null rules: an equality selector ({@code *_EQ}) yields true
 * exactly when both operands are null/missing, an inequality selector
 * ({@code *_NE}) yields the negation, and every ordering selector
 * ({@code *_LT/LE/GT/GE}) yields false. So {@code missing === missing}
 * is true, {@code missing === v} is false, and {@code missing !== v} is
 * true — the pinned {@code jvm-arr-cmp-past-end-parity} behavior
 * ({@code xs[99] === xs[99]} true) — and no typed boundary runs at a
 * comparison operand, so no E8001 is raised there (B-D1/B-D5). The
 * {@code NULLABLE_NULL_*} row names the nullable operand by side and
 * therefore consults only that operand: EQ is true exactly when the
 * named-side operand is null/missing (NE negated), which coincides with
 * the null rules on every checker-admitted shape (the other operand is
 * the {@code null} literal).</p>
 *
 * <p><b>Value rules (B-D2, exact).</b></p>
 * <ul>
 *   <li>{@code INT32_EQ/NE/LT/LE/GT/GE} — signed32 integral comparison:
 *       EQ/NE by value; orderings signed. No cross-type coercion: the
 *       operands are {@code Int} views.</li>
 *   <li>{@code NUMBER_EQ/NE/LT/LE/GT/GE} — IEEE-754: EQ/NE use IEEE
 *       equality ({@code NaN === NaN} false, {@code NaN !== NaN} true,
 *       {@code -0.0 == 0.0} true); orderings are IEEE predicates — NaN
 *       makes every ordering false, {@code -0.0 < 0.0} false,
 *       {@code -0.0 <= 0.0} true. Never {@code Double.compare}.</li>
 *   <li>{@code STRING_EQ/NE/LT/LE/GT/GE} — Unicode scalar-value sequence
 *       equality; order is scalar (code point) lexicographic — never
 *       byte order, locale, or UTF-16 code-unit order. Operands are
 *       {@code String} views whose {@link UnicodeScalars.Valid} carriers
 *       are validated scalars.</li>
 *   <li>{@code BOOLEAN_EQ/NE} — value equality.</li>
 *   <li>{@code NULL_EQ/NE} — null vs null: EQ true by sentinel identity,
 *       NE false.</li>
 *   <li>{@code NULLABLE_EQ/NE} (side {@code LEFT|RIGHT|BOTH}) — a
 *       null/missing operand follows the null rules above; otherwise the
 *       inner values compare by the inner descriptor's equality rule:
 *       int/number/string/boolean value rules for those inner kinds and
 *       allocation/function identity (both operands {@code Ref} views)
 *       for array/table/class/function inner kinds; NE is the
 *       negation.</li>
 *   <li>{@code NULLABLE_NULL_EQ/NE} (side {@code LEFT|RIGHT} names the
 *       nullable operand) — EQ true exactly when the named-side operand
 *       is null/missing, else EQ false; NE negated.</li>
 *   <li>{@code REFERENCE_EQ/NE} — one equal checked descriptor with kind
 *       {@code ARRAY|TABLE|CLASS|FUNCTION} (the payload's inner
 *       descriptor, required); compares allocation/function identity by
 *       token equality of the two {@code Ref} views; a null/missing
 *       operand follows the null rules.</li>
 * </ul>
 *
 * <p><b>Payload requirements.</b> The {@code NULLABLE_*} family requires
 * the payload's inner descriptor (non-null, never {@code null} and never
 * another nullable — the closed {@link RuntimeDescriptor} invariant) and
 * the side mode ({@code LEFT|RIGHT|BOTH} for {@code NULLABLE_EQ/NE};
 * {@code LEFT|RIGHT} for {@code NULLABLE_NULL_EQ/NE}).
 * {@code REFERENCE_*} requires the one equal checked descriptor with
 * kind {@code ARRAY|TABLE|CLASS|FUNCTION}. The other selectors carry no
 * inner descriptor or side and ignore those arguments.</p>
 *
 * <p><b>Fail-closed discipline.</b> No comparison selector ever raises a
 * DEAL failure (all comparisons are policy {@code NO_DEAL_FAILURE}) and
 * a comparison never consumes an operand twice: the operands are
 * completed views and the result is a plain boolean. A shape outside the
 * closed table — an arithmetic selector (the signed-int32/containers
 * epics own arithmetic execution), a wrong operand family for the
 * selector, a null-literal selector fed value operands, a missing or
 * broken {@code NULLABLE_*}/{@code REFERENCE_*} payload, or a wrong
 * inner-value family for the nullable inner descriptor — fails closed as
 * a producer {@link Defect}, never as a DEAL projection and never as a
 * crash (the same fail-closed discipline as the boundary-table
 * projection engine, whose types a comparison never touches). A
 * null/missing operand is never a defect: the null rules return a
 * boolean for every selector.</p>
 *
 * <p><b>Purity and bounds.</b> No mutation, no randomness, no I/O, no
 * retry, no target knowledge, no AST, no checker state; repeated calls
 * with equal inputs return equal results. Integer/boolean/number/reference
 * comparisons are constant-time; string comparison is linear in the
 * scalar count of the two operands. The component depends only on the
 * closed schema (selectors, descriptors, the side mode, the scalar
 * model, and the operand view) and adds no dependency outside the
 * closed schema — the pinned dependency direction stays intact — and
 * the executor executes no boundary op of any kind (B-D5).</p>
 */
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
    // compare — the closed 28-selector execution form
    // =========================================================================

    /**
     * Executes exactly one comparison selector of the closed table over
     * the completed operand views and returns the boolean result
     * (B-D1/B-D2). Pure, deterministic, stateless: no failure, no
     * boundary execution, no mutation, and each operand is consumed at
     * most once.
     *
     * @param selector       the closed comparison selector; must be one
     *                       of the 28 comparison values of
     *                       {@link BinarySelector}
     * @param left           the completed left operand view; non-null
     * @param right          the completed right operand view; non-null
     * @param innerDescriptor the payload's inner descriptor for the
     *                        {@code NULLABLE_*} selectors and the one
     *                        equal checked descriptor for
     *                        {@code REFERENCE_*}; {@code null} for every
     *                        other selector
     * @param side           the payload's side mode for the
     *                       {@code NULLABLE_*} selectors; {@code null}
     *                       for every other selector
     * @return the boolean result of the B-D2 row with the B-D1
     *         missing≡null rule applied
     * @throws Defect               if the selector is an arithmetic
     *                               selector, if the operand family does
     *                               not match the selector, if a
     *                               null-literal selector receives value
     *                               operands, if a {@code NULLABLE_*} or
     *                               {@code REFERENCE_*} payload field is
     *                               missing or outside the closed shape,
     *                               or if a nullable inner value does not
     *                               match the inner descriptor's kind —
     *                               a producer defect, never a DEAL
     *                               projection
     * @throws NullPointerException if {@code selector}, {@code left}, or
     *                              {@code right} is null
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
                + "selector: comparison execution covers the 28 comparison selectors only "
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
        throw new Defect("expected a Ref operand view for a reference comparison, got "
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
     * @return a negative, zero, or positive integer as the left scalar
     *         sequence orders before, equal to, or after the right one
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
