package deal.codegen.llvm;

import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.UnarySelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one rendering authority of the closed selector families
 * ({@code llvm-native-codegen-cfg-ssa} C6, task "every comparison and unary
 * selector arm").
 *
 * <p>Every member of {@link BinarySelector} and {@link UnarySelector} has
 * exactly one arm here. The arm names the emitted form — the instruction
 * family, and for the string family the one runtime entry of R8 — so the
 * emitter, the closed-set classifier and the emission test all read the same
 * table: no selector can be rendered by a second path, and a selector without
 * an arm cannot compile.</p>
 *
 * <p>Arithmetic ({@code NUMBER_ADD/SUB/MUL/DIV_IEEE}) is the IEEE 754 binary64
 * form of C6; no arm carries a fast-math flag, and no comparison arm invents a
 * boundary or a language failure.</p>
 */
public final class LlvmSelectorArms {

    private LlvmSelectorArms() {
        // Static table only; no instances.
    }

    /**
     * The emitted form of one binary selector.
     *
     * <ul>
     *   <li>{@link Form#ARITHMETIC} — one {@code fadd}/{@code fsub}/
     *       {@code fmul}/{@code fdiv} over binary64;</li>
     *   <li>{@link Form#IEEE_PREDICATE} — one ordered/unordered
     *       {@code fcmp} predicate (C6's exact table);</li>
     *   <li>{@link Form#TEXT_COMPARE} — {@code int deal_text_compare} then one
     *       {@code icmp} on the result (the single string authority, R8);</li>
     *   <li>{@link Form#BOOLEAN_BYTES} — one {@code icmp} over the 0/1
     *       bytes;</li>
     *   <li>{@link Form#SINGLETON_TAGS} — the exact nullish singleton test
     *       (null equals only null, undefined only undefined);</li>
     *   <li>{@link Form#DATA_SAME} — {@code deal_data_same} over two
     *       persistent data values (the closed {@code unknown} relation);</li>
     *   <li>{@link Form#TAG_TEST} — one nullish tag/config test of the tagged
     *       scalar nullish representation;</li>
     *   <li>{@link Form#IDENTITY} — managed identity for checked reference
     *       values, never structural equality or wrapper-slot identity.</li>
     * </ul>
     */
    public enum Form {
        ARITHMETIC,
        IEEE_PREDICATE,
        TEXT_COMPARE,
        BOOLEAN_BYTES,
        SINGLETON_TAGS,
        DATA_SAME,
        TAG_TEST,
        IDENTITY
    }

    /**
     * How a runtime entry's integer result encodes the relation the arm
     * renders. The encoding is the entry's own published contract (C6/R8), not
     * a per-call-site choice: {@link #ORDER_SIGN} is {@code
     * deal_text_compare}'s {@code -1}/{@code 0}/{@code 1} scalar-sequence
     * ordering, which is compared against zero with the selector's own
     * predicate, and {@link #TRUTH} is {@code deal_data_same}'s {@code 1}
     * exactly when {@code ===} holds, which is compared against zero with the
     * inverted predicate.
     */
    public enum HelperResult {
        /** No runtime entry: the predicate applies to the operands directly. */
        NONE,
        /** {@code deal_text_compare}: a {@code -1}/{@code 0}/{@code 1} ordering. */
        ORDER_SIGN,
        /** A {@code 1}/{@code 0} truth result: {@code 1} exactly when the relation holds. */
        TRUTH
    }

    /**
     * One binary arm: the selector, its form, the emitted predicate (for the
     * {@code fcmp}/{@code icmp} forms) and the instruction mnemonic (for the
     * arithmetic form). Both fields are empty for the runtime-entry forms,
     * whose result encoding is the form's own contract
     * ({@link #helperResult(BinaryArm)}).
     */
    public record BinaryArm(BinarySelector selector, Form form, String predicate,
                     String mnemonic) {
        public BinaryArm {
            Objects.requireNonNull(selector, "selector must not be null");
            Objects.requireNonNull(form, "form must not be null");
            predicate = predicate == null ? "" : predicate;
            mnemonic = mnemonic == null ? "" : mnemonic;
        }
    }

    /**
     * The emitted form of one unary selector:
     * {@code xor i1 x, true} for {@code BOOL_NOT}, {@code fneg double x} for
     * {@code NUMBER_NEG} (never {@code fsub +0.0, x}), the six-name type
     * projection for {@code TYPEOF}, and the nominal tag test for
     * {@code INSTANCEOF}.
     */
    public enum UnaryForm {
        XOR_TRUE,
        FNEG,
        TYPE_NAME,
        CLASS_TAG
    }

    /** One unary arm: the selector, its form and the emitted instruction. */
    public record UnaryArm(UnarySelector selector, UnaryForm form, String instruction) {
        public UnaryArm {
            Objects.requireNonNull(selector, "selector must not be null");
            Objects.requireNonNull(form, "form must not be null");
            instruction = instruction == null ? "" : instruction;
        }
    }

    // =========================================================================
    // The closed tables (one arm per merged member)
    // =========================================================================

    /** The one rendering arm of {@code selector} (C6's complete mapping). */
    public static BinaryArm binary(BinarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case NUMBER_ADD -> new BinaryArm(selector, Form.ARITHMETIC, "", "fadd");
            case NUMBER_SUB -> new BinaryArm(selector, Form.ARITHMETIC, "", "fsub");
            case NUMBER_MUL -> new BinaryArm(selector, Form.ARITHMETIC, "", "fmul");
            case NUMBER_DIV_IEEE ->
                new BinaryArm(selector, Form.ARITHMETIC, "", "fdiv");
            case NUMBER_EQ -> new BinaryArm(selector, Form.IEEE_PREDICATE, "oeq", "");
            case NUMBER_NE -> new BinaryArm(selector, Form.IEEE_PREDICATE, "une", "");
            case NUMBER_LT -> new BinaryArm(selector, Form.IEEE_PREDICATE, "olt", "");
            case NUMBER_LE -> new BinaryArm(selector, Form.IEEE_PREDICATE, "ole", "");
            case NUMBER_GT -> new BinaryArm(selector, Form.IEEE_PREDICATE, "ogt", "");
            case NUMBER_GE -> new BinaryArm(selector, Form.IEEE_PREDICATE, "oge", "");
            case STRING_EQ -> new BinaryArm(selector, Form.TEXT_COMPARE, "eq", "");
            case STRING_NE -> new BinaryArm(selector, Form.TEXT_COMPARE, "ne", "");
            case STRING_LT -> new BinaryArm(selector, Form.TEXT_COMPARE, "slt", "");
            case STRING_LE -> new BinaryArm(selector, Form.TEXT_COMPARE, "sle", "");
            case STRING_GT -> new BinaryArm(selector, Form.TEXT_COMPARE, "sgt", "");
            case STRING_GE -> new BinaryArm(selector, Form.TEXT_COMPARE, "sge", "");
            case BOOLEAN_EQ ->
                new BinaryArm(selector, Form.BOOLEAN_BYTES, "eq", "");
            case BOOLEAN_NE ->
                new BinaryArm(selector, Form.BOOLEAN_BYTES, "ne", "");
            case NULL_EQ -> new BinaryArm(selector, Form.SINGLETON_TAGS, "eq", "");
            case NULL_NE -> new BinaryArm(selector, Form.SINGLETON_TAGS, "ne", "");
            case UNDEFINED_EQ ->
                new BinaryArm(selector, Form.SINGLETON_TAGS, "eq", "");
            case UNDEFINED_NE ->
                new BinaryArm(selector, Form.SINGLETON_TAGS, "ne", "");
            case UNKNOWN_EQ -> new BinaryArm(selector, Form.DATA_SAME, "eq", "");
            case UNKNOWN_NE -> new BinaryArm(selector, Form.DATA_SAME, "ne", "");
            case NULLABLE_UNDEFINED_EQ ->
                new BinaryArm(selector, Form.TAG_TEST, "eq", "");
            case NULLABLE_UNDEFINED_NE ->
                new BinaryArm(selector, Form.TAG_TEST, "ne", "");
            case NULLABLE_NULL_EQ ->
                new BinaryArm(selector, Form.TAG_TEST, "eq", "");
            case NULLABLE_NULL_NE ->
                new BinaryArm(selector, Form.TAG_TEST, "ne", "");
            case NULLABLE_EQ -> new BinaryArm(selector, Form.TAG_TEST, "eq", "");
            case NULLABLE_NE -> new BinaryArm(selector, Form.TAG_TEST, "ne", "");
            case REFERENCE_EQ -> new BinaryArm(selector, Form.IDENTITY, "eq", "");
            case REFERENCE_NE -> new BinaryArm(selector, Form.IDENTITY, "ne", "");
        };
    }

    /** The one rendering arm of {@code selector} (C6's unary mapping). */
    public static UnaryArm unary(UnarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case BOOL_NOT -> new UnaryArm(selector, UnaryForm.XOR_TRUE,
                "xor i1 %s, true");
            case NUMBER_NEG -> new UnaryArm(selector, UnaryForm.FNEG,
                "fneg double %s");
            case TYPEOF -> new UnaryArm(selector, UnaryForm.TYPE_NAME,
                "call %struct.deal_string* @deal_descriptor_type_name"
                    + "(%struct.deal_data* %s)");
            case INSTANCEOF -> new UnaryArm(selector, UnaryForm.CLASS_TAG,
                "icmp eq i32 %s, " + LlvmTypeMapper.DATA_ERROR);
        };
    }

    /**
     * The {@code icmp} predicate one runtime-entry arm applies to that
     * entry's integer result, in C6's spelling. The entry's own encoding
     * decides the polarity: an ordering result
     * ({@link HelperResult#ORDER_SIGN}) is compared against zero with the
     * selector's predicate, while a truth result
     * ({@link HelperResult#TRUTH}, {@code 1} exactly when the relation holds)
     * is compared against zero with the inverted predicate — so
     * {@code UNKNOWN_EQ} renders {@code icmp ne i32 %r, 0} and
     * {@code UNKNOWN_NE} renders {@code icmp eq i32 %r, 0}.
     */
    public static String resultPredicate(BinaryArm arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        return switch (helperResult(arm)) {
            case NONE, ORDER_SIGN -> arm.predicate();
            case TRUTH -> invert(arm);
        };
    }

    /** The published result encoding of one arm's runtime entry (C6/R8). */
    public static HelperResult helperResult(BinaryArm arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        return switch (arm.form()) {
            case TEXT_COMPARE -> HelperResult.ORDER_SIGN;
            case DATA_SAME -> HelperResult.TRUTH;
            default -> HelperResult.NONE;
        };
    }

    /**
     * The zero the runtime-entry arm's result is compared against: both entry
     * encodings name the relation by their zero boundary ({@code 0} for the
     * ordering's equality and {@code 0} for the truth result's falsity).
     */
    public static String resultOperand(BinaryArm arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        return "0";
    }

    private static String invert(BinaryArm arm) {
        return switch (arm.predicate()) {
            case "eq" -> "ne";
            case "ne" -> "eq";
            default -> throw new IllegalStateException("the runtime truth result of "
                + arm.selector() + " has no inverted predicate: "
                + arm.predicate());
        };
    }

    /** Every binary arm, in the merged selector order. */
    public static List<BinaryArm> binaryArms() {
        List<BinaryArm> arms = new ArrayList<>();
        for (BinarySelector selector : BinarySelector.values()) {
            arms.add(binary(selector));
        }
        return List.copyOf(arms);
    }

    /** Every unary arm, in the merged selector order. */
    public static List<UnaryArm> unaryArms() {
        List<UnaryArm> arms = new ArrayList<>();
        for (UnarySelector selector : UnarySelector.values()) {
            arms.add(unary(selector));
        }
        return List.copyOf(arms);
    }
}
