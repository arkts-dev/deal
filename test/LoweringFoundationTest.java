package deal.test;

import deal.diagnostics.DiagnosticCode;
import deal.semantic.SharedValueSemantics;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnarySelector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Verifies the ISSUE-0281 foundation surface: the E6005 registration in
 * {@link DiagnosticCode}, the {@link LoweringFailureDetail} payload carrier,
 * and the four closed foundation enums
 * ({@link SemanticProfile}, {@link InvocationPurpose}, {@link ReleaseState},
 * {@link SemanticCapability}).
 *
 * <p>Verifies the ISSUE-0390 value-semantics primitive (design I2):
 * {@link SharedValueSemantics} — pure, static, deterministic, no I/O, no
 * state — with its closed {@code Int32Result = Value(int) | Fail(code,
 * template, origin)} shape, every int32/IEEE-number row (both signs of
 * add/sub/mul/neg overflow, {@code MIN/-1} → E8004 and {@code MIN%-1} →
 * 0, zero-divisor E8005 first, negative-exponent E8006 first, the
 * int32Pow finite band {@code 2 ** 62} → E8004 and infinity band
 * {@code 2 ** 1024} → E8001, intFromNumber in the normative order,
 * checkInt32Integral, numberFromInt exactness, -0 normalization, IEEE
 * div-by-zero, NaN equality/relational, floor-mod signs, numberPow's
 * every pinned special case, failure precedence, and the supplied origin
 * on every failure), and a cross-check asserting the primitive's row
 * projection agrees with the closed {@code binaryPolicy} table data in
 * {@link SemanticIrValidator} for every covered selector, and the
 * complete unary set ({@code INT32_NEG}, {@code NUMBER_NEG},
 * {@code BOOL_NOT}) agrees with the closed {@code unaryPolicy} rule —
 * a selector stamped with any other policy is a failure.</p>
 *
 * <p>Tests:
 * <ol>
 *   <li>E6000..E6005 are the complete E6 code set, all
 *       {@code BACKEND_LOWERING}, in declaration order, with E6005 directly
 *       beside E6000-E6004 and its message template exactly
 *       "Common semantic lowering failed"; E6000 remains the intentional
 *       retained-target rejection code and is never E6005; the E6000-E6004
 *       message templates are unchanged.</li>
 *   <li>Reflection-based exhaustive enumeration of the four closed enums:
 *       exactly the pinned values in the pinned order; enum type, implicit
 *       finality, and constructor surface checked; no open/unknown fallback
 *       member; no static factory or other extension point beyond the
 *       implicit values()/valueOf(String).</li>
 *   <li>{@link LoweringFailureDetail} is an immutable record with exactly
 *       the six pinned components in the pinned order and the pinned
 *       component types, and its accessors return the constructed values.</li>
 *   <li>{@link SharedValueSemantics} surface: final, no instances, no
 *       state, static entry points, the pinned {@code INT32_MIN}/{@code
 *       INT32_MAX} constants, the sealed two-permit {@code Int32Result}
 *       shape, and fail-closed null handling.</li>
 *   <li>Every int32 row: arithmetic both signs, div/mod truncation and
 *       precedence, pow bands and precedence, conversion order,
 *       checkInt32Integral, numberFromInt, and the caller-supplied origin
 *       on every failure.</li>
 *   <li>Every number row: IEEE add/sub/mul/div/neg, floor-mod signs,
 *       numberPow's pinned IEEE-754 special-case table, and the int32 /
 *       number comparison rules incl. NaN.</li>
 *   <li>The selector→policy cross-check against the closed
 *       {@code SemanticIrValidator.binaryPolicy} table and the
 *       {@code SemanticIrValidator.unaryPolicy} rule.</li>
 * </ol>
 */
public class LoweringFoundationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    /** A throwing action for {@link #expectThrows}. */
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static void expectThrows(Class<? extends Throwable> type, ThrowingRunnable action,
                                     String message) {
        try {
            action.run();
            fail(message);
        } catch (Throwable t) {
            check(type.isInstance(t), message + " (got " + t.getClass().getSimpleName() + ")");
        }
    }

    // =========================================================================
    // E6005 registration
    // =========================================================================

    static void testE6005Registration() {
        System.out.println("-- E6005 registration --");

        List<String> e6 = new ArrayList<>();
        for (DiagnosticCode dc : DiagnosticCode.values()) {
            if (dc.code().matches("E6\\d{3}")) {
                e6.add(dc.code());
            }
        }
        check(e6.equals(List.of("E6000", "E6001", "E6002", "E6003",
                "E6004", "E6005")),
            "E6 codes are exactly E6000..E6005 in declaration order; got " + e6);

        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6005 is BACKEND_LOWERING");
        check("Common semantic lowering failed"
                .equals(DiagnosticCode.E6005.messageTemplate()),
            "E6005 message template is exactly \"Common semantic lowering failed\"");
        check(DiagnosticCode.fromCode("E6005") == DiagnosticCode.E6005,
            "E6005 resolves through fromCode");
        check(DiagnosticCode.isRegistered("E6005"), "E6005 is registered");

        check(DiagnosticCode.E6005.ordinal() == DiagnosticCode.E6004.ordinal() + 1
                && DiagnosticCode.E6004.ordinal() == DiagnosticCode.E6003.ordinal() + 1,
            "E6005 sits directly beside E6000-E6004 (ordinal adjacency)");

        // E6000 remains the intentional retained-target rejection code and is
        // never E6005 (parent D11).
        check(DiagnosticCode.E6000 != DiagnosticCode.E6005,
            "E6000 and E6005 are distinct codes");
        check(DiagnosticCode.E6000.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            "E6000 remains BACKEND_LOWERING (retained-target rejection)");

        // No existing E6000-E6004 code was renamed or renumbered: pin the
        // unchanged message templates.
        Map<String, String> pinnedMessages = Map.of(
            "E6000", "Unsupported statement type",
            "E6001", "Continue outside loop",
            "E6002", "Cannot break/continue across try boundary",
            "E6003", "Rest parameters are not part of DEAL v1.2",
            "E6004", "Entry module must export non-async main(): null");
        for (var entry : pinnedMessages.entrySet()) {
            DiagnosticCode dc = DiagnosticCode.fromCode(entry.getKey());
            check(dc != null && entry.getValue().equals(dc.messageTemplate()),
                entry.getKey() + " message template unchanged: "
                    + (dc == null ? "null" : dc.messageTemplate()));
        }
    }

    // =========================================================================
    // Closed enum verification (reflection-based)
    // =========================================================================

    static void testClosedEnum(Class<? extends Enum<?>> type, List<String> pinned,
                               String name) {
        System.out.println("-- Closed enum " + name + " --");

        check(type.isEnum(), name + " is an enum type");
        check(Enum.class.isAssignableFrom(type), name + " extends java.lang.Enum");
        check(Modifier.isFinal(type.getModifiers()),
            name + " is implicitly final (no subclass extension point)");

        Enum<?>[] constants = type.getEnumConstants();
        List<String> actual = new ArrayList<>();
        for (Enum<?> c : constants) {
            actual.add(c.name());
        }
        check(actual.equals(pinned),
            name + " contains exactly " + pinned + " in the pinned order; got " + actual);
        check(constants.length == pinned.size(),
            name + " has exactly " + pinned.size() + " constants (no extra members)");

        // No open/unknown fallback member.
        for (Enum<?> c : constants) {
            String n = c.name();
            check(!n.equals("UNKNOWN") && !n.equals("OPEN") && !n.equals("OTHER")
                    && !n.equals("UNSPECIFIED") && !n.equals("CUSTOM"),
                name + " has no open/unknown fallback member (offending: " + n + ")");
            check(c.getClass() == type,
                name + " constant " + n + " has no constant-specific class body");
        }

        // No external extension point: no public constructor, and the only
        // public static methods returning the enum type (or its array) are
        // the implicit values()/valueOf(String) — the javac-generated
        // private synthetic $values() helper is not a public surface.
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        check(ctors.length == 1 && !Modifier.isPublic(ctors[0].getModifiers()),
            name + " has exactly one private constructor (no public construction)");
        for (Method m : type.getDeclaredMethods()) {
            boolean returnsEnumType = m.getReturnType() == type
                || (m.getReturnType().isArray()
                    && m.getReturnType().getComponentType() == type);
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && returnsEnumType) {
                check(m.getName().equals("values") || m.getName().equals("valueOf"),
                    name + " public static enum-typed surface is exactly "
                        + "values()/valueOf(String); found " + m);
            }
        }

        // Reflection round-trip for every pinned value.
        for (int i = 0; i < pinned.size(); i++) {
            check(valueOfRaw(type, pinned.get(i)) == constants[i],
                name + " valueOf round-trip for " + pinned.get(i)
                    + " hits declaration position " + i);
        }
        try {
            valueOfRaw(type, "UNKNOWN");
            fail(name + " valueOf(\"UNKNOWN\") should have failed (closed set)");
        } catch (IllegalArgumentException expected) {
            check(true, name + " rejects an unknown value by name");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> valueOfRaw(Class<? extends Enum<?>> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    // =========================================================================
    // LoweringFailureDetail
    // =========================================================================

    static void testLoweringFailureDetail() {
        System.out.println("-- LoweringFailureDetail payload carrier --");

        Class<LoweringFailureDetail> type = LoweringFailureDetail.class;
        check(type.isRecord(), "LoweringFailureDetail is an immutable record");

        RecordComponent[] components = type.getRecordComponents();
        String[] pinnedNames = {"module", "capability", "validatorRule",
            "semanticProfile", "irVersion", "origin"};
        Class<?>[] pinnedTypes = {String.class, SemanticCapability.class,
            String.class, SemanticProfile.class, String.class, String.class};
        check(components.length == 6,
            "LoweringFailureDetail has exactly 6 components; got "
                + components.length + " " + Arrays.toString(components));
        for (int i = 0; i < Math.min(components.length, pinnedNames.length); i++) {
            check(pinnedNames[i].equals(components[i].getName()),
                "component " + i + " is named " + pinnedNames[i]
                    + "; got " + components[i].getName());
            check(components[i].getType() == pinnedTypes[i],
                "component " + i + " has type " + pinnedTypes[i].getSimpleName()
                    + "; got " + components[i].getType().getSimpleName());
        }

        Field[] fields = type.getDeclaredFields();
        check(fields.length == 6,
            "LoweringFailureDetail declares exactly 6 fields (no extras); got "
                + fields.length);
        for (Field f : fields) {
            check(Modifier.isPrivate(f.getModifiers()) && Modifier.isFinal(f.getModifiers()),
                "field " + f.getName() + " is private final (immutable carrier)");
        }

        LoweringFailureDetail detail = new LoweringFailureDetail(
            "a.b", SemanticCapability.MODULES, "INDEX_INTERNAL_ERROR_SENTINEL",
            SemanticProfile.DEAL_V1_2_INT32, "deal.semantic-ir/1", "CheckedProjectBuilder");
        check("a.b".equals(detail.module()), "module accessor");
        check(detail.capability() == SemanticCapability.MODULES, "capability accessor");
        check("INDEX_INTERNAL_ERROR_SENTINEL".equals(detail.validatorRule()),
            "validatorRule accessor");
        check(detail.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32,
            "semanticProfile accessor");
        check("deal.semantic-ir/1".equals(detail.irVersion()), "irVersion accessor");
        check("CheckedProjectBuilder".equals(detail.origin()), "origin accessor");
    }

    // =========================================================================
    // SharedValueSemantics (ISSUE-0390, design I2)
    // =========================================================================

    /** A distinct caller-supplied operation origin for one row under test. */
    private static SourceOrigin origin(String label) {
        return new SourceOrigin(label, SourceSpan.synthetic(label),
            SourceOriginKind.SYNTHETIC, new AnchorId(1), null);
    }

    private static void expectValue(SharedValueSemantics.Int32Result result, int value,
                                    String what) {
        if (result instanceof SharedValueSemantics.Int32Result.Value v) {
            check(v.value() == value,
                what + ": expected Value(" + value + "), got Value(" + v.value() + ")");
        } else {
            SharedValueSemantics.Int32Result.Fail f =
                (SharedValueSemantics.Int32Result.Fail) result;
            fail(what + ": expected Value(" + value + ") but got Fail(" + f.code().code()
                + ", \"" + f.template() + "\")");
        }
    }

    private static void expectFail(SharedValueSemantics.Int32Result result, String code,
                                   String template, String what) {
        if (result instanceof SharedValueSemantics.Int32Result.Fail f) {
            check(code.equals(f.code().code()),
                what + ": fail code " + code + " (got " + f.code().code() + ")");
            check(f.code() == DiagnosticCode.fromCode(code),
                what + ": fail code resolves through fromCode");
            check(template.equals(f.template()),
                what + ": fail template exactly \"" + template + "\" (got \""
                    + f.template() + "\")");
        } else {
            SharedValueSemantics.Int32Result.Value v =
                (SharedValueSemantics.Int32Result.Value) result;
            fail(what + ": expected Fail(" + code + ", \"" + template
                + "\") but got Value(" + v.value() + ")");
        }
    }

    /** Failure with the caller-supplied origin carried by identity. */
    private static void expectFailAt(SharedValueSemantics.Int32Result result, String code,
                                     String template, SourceOrigin origin, String what) {
        expectFail(result, code, template, what);
        if (result instanceof SharedValueSemantics.Int32Result.Fail f) {
            check(f.origin() == origin,
                what + ": the caller-supplied origin is carried by identity");
        }
    }

    static void testSharedValueSemanticsSurface() {
        System.out.println("-- SharedValueSemantics surface (pure, static, closed) --");

        Class<SharedValueSemantics> type = SharedValueSemantics.class;
        check(Modifier.isFinal(type.getModifiers()),
            "SharedValueSemantics is final (no subclass extension point)");
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        check(ctors.length == 1 && !Modifier.isPublic(ctors[0].getModifiers()),
            "SharedValueSemantics has exactly one non-public constructor (no instances)");
        boolean hasInstanceFields = false;
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                hasInstanceFields = true;
            }
        }
        check(!hasInstanceFields, "SharedValueSemantics declares no instance state");
        for (Method m : type.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                fail("SharedValueSemantics public entry point is static: " + m);
            }
        }

        check(SharedValueSemantics.INT32_MIN == -2147483648,
            "INT32_MIN pins -2147483648");
        check(SharedValueSemantics.INT32_MAX == 2147483647,
            "INT32_MAX pins 2147483647");

        // Int32Result = Value(int) | Fail(code, template, origin).
        Class<SharedValueSemantics.Int32Result> ir = SharedValueSemantics.Int32Result.class;
        check(ir.isInterface(), "Int32Result is an interface");
        check(ir.isSealed(), "Int32Result is sealed");
        Class<?>[] permitted = ir.getPermittedSubclasses();
        check(permitted != null && permitted.length == 2,
            "Int32Result permits exactly Value and Fail; got "
                + Arrays.toString(permitted));
        RecordComponent[] valueComponents =
            SharedValueSemantics.Int32Result.Value.class.getRecordComponents();
        check(valueComponents.length == 1 && valueComponents[0].getType() == int.class,
            "Value is a record with exactly one int component");
        RecordComponent[] failComponents =
            SharedValueSemantics.Int32Result.Fail.class.getRecordComponents();
        check(failComponents.length == 3
                && failComponents[0].getType() == DiagnosticCode.class
                && failComponents[1].getType() == String.class
                && failComponents[2].getType() == SourceOrigin.class,
            "Fail is a record with exactly (DiagnosticCode, String, SourceOrigin) components");

        // Fail fails closed on null components.
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.Int32Result.fail(null, "int out of range",
                origin("null-code")),
            "Fail(null code) fails closed");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.Int32Result.fail(DiagnosticCode.E8004, null,
                origin("null-template")),
            "Fail(null template) fails closed");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.Int32Result.fail(DiagnosticCode.E8004,
                "int out of range", null),
            "Fail(null origin) fails closed");

        // A null origin fails closed on every failing-capable operation.
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Add(1, 1, null), "int32Add(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Sub(1, 1, null), "int32Sub(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Mul(1, 1, null), "int32Mul(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Neg(1, null), "int32Neg(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Div(1, 1, null), "int32Div(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Mod(1, 1, null), "int32Mod(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.int32Pow(1, 1, null), "int32Pow(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.intFromNumber(1.0, null),
            "intFromNumber(null origin)");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.checkInt32Integral(1L, null),
            "checkInt32Integral(null origin)");
    }

    static void testSharedValueSemanticsInt32Arithmetic() {
        System.out.println("-- Int32 arithmetic: INT32_RESULT, both signs --");

        SourceOrigin ok = origin("add-ok");
        SourceOrigin over = origin("add-over");
        SourceOrigin under = origin("add-under");
        SourceOrigin mulOk = origin("mul-ok");
        SourceOrigin mulOver = origin("mul-over");
        SourceOrigin negOk = origin("neg-ok");
        SourceOrigin negOver = origin("neg-over");

        // add
        expectValue(SharedValueSemantics.int32Add(2147483647, 0, ok),
            2147483647, "add max + 0");
        expectValue(SharedValueSemantics.int32Add(-2147483648, 0, ok),
            -2147483648, "add min + 0");
        expectValue(SharedValueSemantics.int32Add(0, 0, ok), 0, "add 0 + 0");
        expectValue(SharedValueSemantics.int32Add(1073741824, 1073741823, ok),
            2147483647, "add boundary sum");
        expectFailAt(SharedValueSemantics.int32Add(2147483647, 1, over),
            "E8004", "int out of range", over, "add max + 1");
        expectFailAt(SharedValueSemantics.int32Add(-2147483648, -1, under),
            "E8004", "int out of range", under, "add min + -1");
        expectFailAt(SharedValueSemantics.int32Add(1073741824, 1073741824, over),
            "E8004", "int out of range", over, "add 2^30 + 2^30");

        // sub
        expectValue(SharedValueSemantics.int32Sub(-2147483648, 0, ok),
            -2147483648, "sub min - 0");
        expectValue(SharedValueSemantics.int32Sub(2147483647, 2147483647, ok),
            0, "sub x - x");
        expectValue(SharedValueSemantics.int32Sub(0, 2147483647, ok),
            -2147483647, "sub 0 - max");
        expectFailAt(SharedValueSemantics.int32Sub(-2147483648, 1, under),
            "E8004", "int out of range", under, "sub min - 1");
        expectFailAt(SharedValueSemantics.int32Sub(2147483647, -1, over),
            "E8004", "int out of range", over, "sub max - -1");

        // mul
        expectValue(SharedValueSemantics.int32Mul(0, -2147483648, mulOk),
            0, "mul 0 * min");
        expectValue(SharedValueSemantics.int32Mul(1, -2147483648, mulOk),
            -2147483648, "mul 1 * min");
        expectValue(SharedValueSemantics.int32Mul(-1, 2147483647, mulOk),
            -2147483647, "mul -1 * max");
        expectValue(SharedValueSemantics.int32Mul(46340, 46340, mulOk),
            2147395600, "mul boundary product");
        expectFailAt(SharedValueSemantics.int32Mul(2147483647, 2, mulOver),
            "E8004", "int out of range", mulOver, "mul max * 2");
        expectFailAt(SharedValueSemantics.int32Mul(-2147483648, 2, mulOver),
            "E8004", "int out of range", mulOver, "mul min * 2");
        expectFailAt(SharedValueSemantics.int32Mul(46341, 46341, mulOver),
            "E8004", "int out of range", mulOver, "mul 46341 * 46341");
        expectFailAt(SharedValueSemantics.int32Mul(-46341, -46341, mulOver),
            "E8004", "int out of range", mulOver, "mul -46341 * -46341");

        // neg
        expectValue(SharedValueSemantics.int32Neg(0, negOk), 0, "neg 0 -> 0");
        expectValue(SharedValueSemantics.int32Neg(5, negOk), -5, "neg 5");
        expectValue(SharedValueSemantics.int32Neg(-5, negOk), 5, "neg -5");
        expectValue(SharedValueSemantics.int32Neg(2147483647, negOk),
            -2147483647, "neg max");
        expectValue(SharedValueSemantics.int32Neg(-2147483647, negOk),
            2147483647, "neg -max");
        expectFailAt(SharedValueSemantics.int32Neg(-2147483648, negOver),
            "E8004", "int out of range", negOver, "neg min -> E8004");
    }

    static void testSharedValueSemanticsInt32DivMod() {
        System.out.println("-- Int32 div/mod: INT32_DIVISOR_THEN_RESULT --");

        SourceOrigin divZero = origin("div-zero");
        SourceOrigin modZero = origin("mod-zero");
        SourceOrigin trunc = origin("div-trunc");
        SourceOrigin ok = origin("div-ok");
        SourceOrigin minNegOne = origin("div-min-neg-one");
        SourceOrigin modMinNegOne = origin("mod-min-neg-one");

        // Zero divisor is E8005 first — before any range gate.
        expectFailAt(SharedValueSemantics.int32Div(1, 0, divZero),
            "E8005", "integer division by zero", divZero, "div 1 / 0");
        expectFailAt(SharedValueSemantics.int32Div(-2147483648, 0, divZero),
            "E8005", "integer division by zero", divZero,
            "div min / 0 (E8005 wins)");
        expectFailAt(SharedValueSemantics.int32Div(0, 0, divZero),
            "E8005", "integer division by zero", divZero, "div 0 / 0");
        expectFailAt(SharedValueSemantics.int32Mod(0, 0, modZero),
            "E8005", "integer division by zero", modZero, "mod 0 % 0");
        expectFailAt(SharedValueSemantics.int32Mod(-2147483648, 0, modZero),
            "E8005", "integer division by zero", modZero,
            "mod min % 0 (E8005 wins)");

        // Truncation toward zero, both signs.
        expectValue(SharedValueSemantics.int32Div(5, 2, trunc), 2,
            "div 5 / 2 = 2");
        expectValue(SharedValueSemantics.int32Div(-5, 2, trunc), -2,
            "div -5 / 2 = -2");
        expectValue(SharedValueSemantics.int32Div(5, -2, trunc), -2,
            "div 5 / -2 = -2");
        expectValue(SharedValueSemantics.int32Div(-5, -2, trunc), 2,
            "div -5 / -2 = 2");
        expectValue(SharedValueSemantics.int32Mod(5, 2, trunc), 1,
            "mod 5 % 2 = 1");
        expectValue(SharedValueSemantics.int32Mod(-5, 2, trunc), -1,
            "mod -5 % 2 = -1");
        expectValue(SharedValueSemantics.int32Mod(5, -2, trunc), 1,
            "mod 5 % -2 = 1");
        expectValue(SharedValueSemantics.int32Mod(-5, -2, trunc), -1,
            "mod -5 % -2 = -1");

        // Boundaries.
        expectValue(SharedValueSemantics.int32Div(2147483647, 1, ok),
            2147483647, "div max / 1");
        expectValue(SharedValueSemantics.int32Div(2147483647, -1, ok),
            -2147483647, "div max / -1");
        expectValue(SharedValueSemantics.int32Div(-2147483648, 1, ok),
            -2147483648, "div min / 1");
        expectValue(SharedValueSemantics.int32Div(-2147483648, 2, ok),
            -1073741824, "div min / 2");
        expectValue(SharedValueSemantics.int32Mod(-2147483648, 2147483647, ok),
            -1, "mod min % max = -1");
        expectFailAt(SharedValueSemantics.int32Div(-2147483648, -1, minNegOne),
            "E8004", "int out of range", minNegOne,
            "div min / -1 -> E8004");
        expectValue(SharedValueSemantics.int32Mod(-2147483648, -1, modMinNegOne),
            0, "mod min % -1 = 0 (truncated remainder)");
    }

    static void testSharedValueSemanticsInt32Pow() {
        System.out.println("-- Int32 pow: INT32_EXPONENT_THEN_RESULT --");

        SourceOrigin negExp = origin("pow-neg-exp");
        SourceOrigin ok = origin("pow-ok");
        SourceOrigin over = origin("pow-over");
        SourceOrigin pow62 = origin("pow-62");
        SourceOrigin pow63 = origin("pow-63");
        SourceOrigin pow1024 = origin("pow-1024");
        SourceOrigin pow1024Neg = origin("pow-1024-neg");
        SourceOrigin powInf = origin("pow-inf");

        // Negative exponent is E8006 first — even for a zero base.
        expectFailAt(SharedValueSemantics.int32Pow(2, -1, negExp),
            "E8006", "integer exponent must be non-negative", negExp,
            "pow 2 ** -1 -> E8006");
        expectFailAt(SharedValueSemantics.int32Pow(0, -1, negExp),
            "E8006", "integer exponent must be non-negative", negExp,
            "pow 0 ** -1 -> E8006 first");
        expectFailAt(SharedValueSemantics.int32Pow(-2147483648, -2147483648, negExp),
            "E8006", "integer exponent must be non-negative", negExp,
            "pow min ** min -> E8006 first");

        // Exact in-range and boundary values.
        expectValue(SharedValueSemantics.int32Pow(0, 0, ok), 1,
            "pow 0 ** 0 = 1");
        expectValue(SharedValueSemantics.int32Pow(2, 0, ok), 1,
            "pow 2 ** 0 = 1");
        expectValue(SharedValueSemantics.int32Pow(-2147483648, 0, ok), 1,
            "pow min ** 0 = 1");
        expectValue(SharedValueSemantics.int32Pow(0, 5, ok), 0,
            "pow 0 ** 5 = 0");
        expectValue(SharedValueSemantics.int32Pow(1, 2147483647, ok), 1,
            "pow 1 ** max = 1");
        expectValue(SharedValueSemantics.int32Pow(-1, 2147483647, ok), -1,
            "pow -1 ** odd = -1");
        expectValue(SharedValueSemantics.int32Pow(-1, 2147483646, ok), 1,
            "pow -1 ** even = 1");
        expectValue(SharedValueSemantics.int32Pow(2, 30, ok), 1073741824,
            "pow 2 ** 30");
        expectValue(SharedValueSemantics.int32Pow(-2, 31, ok), -2147483648,
            "pow -2 ** 31 = min");
        expectValue(SharedValueSemantics.int32Pow(2147483647, 1, ok),
            2147483647, "pow max ** 1");
        expectValue(SharedValueSemantics.int32Pow(-2147483648, 1, ok),
            -2147483648, "pow min ** 1");

        // Finite out-of-range band -> E8004 (exact long, then the ±2^31 gate).
        expectFailAt(SharedValueSemantics.int32Pow(2, 31, over),
            "E8004", "int out of range", over, "pow 2 ** 31");
        expectFailAt(SharedValueSemantics.int32Pow(-2, 32, over),
            "E8004", "int out of range", over, "pow -2 ** 32");
        expectFailAt(SharedValueSemantics.int32Pow(-2147483648, 2, over),
            "E8004", "int out of range", over, "pow min ** 2");
        expectFailAt(SharedValueSemantics.int32Pow(2, 62, pow62),
            "E8004", "int out of range", pow62,
            "pow 2 ** 62 -> E8004 (finite band)");
        expectFailAt(SharedValueSemantics.int32Pow(-2, 63, pow63),
            "E8004", "int out of range", pow63,
            "pow -2 ** 63 -> E8004 (exactly in-long, out of range)");
        expectFailAt(SharedValueSemantics.int32Pow(2, 63, over),
            "E8004", "int out of range", over,
            "pow 2 ** 63 -> E8004 (long overflow, finite classification)");
        expectFailAt(SharedValueSemantics.int32Pow(-2, 64, over),
            "E8004", "int out of range", over,
            "pow -2 ** 64 -> E8004 (long overflow via squaring, finite)");
        expectFailAt(SharedValueSemantics.int32Pow(3, 64, over),
            "E8004", "int out of range", over,
            "pow 3 ** 64 -> E8004 (long overflow, finite)");

        // Infinity band -> E8001 expected int, got infinity (sign-insensitive).
        expectFailAt(SharedValueSemantics.int32Pow(2, 1024, pow1024),
            "E8001", "expected int, got infinity", pow1024,
            "pow 2 ** 1024 -> E8001 infinity band");
        expectFailAt(SharedValueSemantics.int32Pow(-2, 1024, pow1024Neg),
            "E8001", "expected int, got infinity", pow1024Neg,
            "pow -2 ** 1024 -> E8001 (sign-insensitive classification)");
        expectFailAt(SharedValueSemantics.int32Pow(2, 1025, powInf),
            "E8001", "expected int, got infinity", powInf,
            "pow 2 ** 1025 -> E8001");
    }

    static void testSharedValueSemanticsConversion() {
        System.out.println("-- Conversions: INT_CONVERSION order, NUMBER_CONVERSION, -0 --");

        SourceOrigin nan = origin("cvt-nan");
        SourceOrigin inf = origin("cvt-inf");
        SourceOrigin frac = origin("cvt-frac");
        SourceOrigin order = origin("cvt-order");
        SourceOrigin range = origin("cvt-range");
        SourceOrigin ok = origin("cvt-ok");
        SourceOrigin negZero = origin("cvt-negzero");
        SourceOrigin checkOk = origin("check-ok");
        SourceOrigin checkOver = origin("check-over");

        // intFromNumber in the normative order: NaN, then infinity, then
        // fractional, then integral out of range.
        expectFailAt(SharedValueSemantics.intFromNumber(Double.NaN, nan),
            "E8001", "expected int, got NaN", nan, "int(NaN)");
        expectFailAt(SharedValueSemantics.intFromNumber(Double.POSITIVE_INFINITY, inf),
            "E8001", "expected int, got infinity", inf, "int(+inf)");
        expectFailAt(SharedValueSemantics.intFromNumber(Double.NEGATIVE_INFINITY, inf),
            "E8001", "expected int, got infinity", inf, "int(-inf)");
        expectFailAt(SharedValueSemantics.intFromNumber(0.5, frac),
            "E8001", "expected int, got non-integer number", frac,
            "int(0.5)");
        expectFailAt(SharedValueSemantics.intFromNumber(-0.5, frac),
            "E8001", "expected int, got non-integer number", frac,
            "int(-0.5)");
        expectFailAt(SharedValueSemantics.intFromNumber(2147483647.999, frac),
            "E8001", "expected int, got non-integer number", frac,
            "int(max - epsilon) fractional");
        expectFailAt(SharedValueSemantics.intFromNumber(-2147483648.5, frac),
            "E8001", "expected int, got non-integer number", frac,
            "int(min - 0.5) fractional");
        expectFailAt(SharedValueSemantics.intFromNumber(2147483648.5, order),
            "E8001", "expected int, got non-integer number", order,
            "int(2^31 + 0.5): fractional wins over range (normative order)");
        expectFailAt(SharedValueSemantics.intFromNumber(2147483648.0, range),
            "E8004", "int out of range", range, "int(2^31)");
        expectFailAt(SharedValueSemantics.intFromNumber(-2147483649.0, range),
            "E8004", "int out of range", range, "int(-2^31 - 1)");
        expectFailAt(SharedValueSemantics.intFromNumber(9007199254740992.0, range),
            "E8004", "int out of range", range, "int(2^53)");
        expectFailAt(SharedValueSemantics.intFromNumber(1e300, range),
            "E8004", "int out of range", range, "int(1e300)");
        expectValue(SharedValueSemantics.intFromNumber(2147483647.0, ok),
            2147483647, "int(max)");
        expectValue(SharedValueSemantics.intFromNumber(-2147483648.0, ok),
            -2147483648, "int(min)");
        expectValue(SharedValueSemantics.intFromNumber(0.0, ok), 0,
            "int(0.0)");
        expectValue(SharedValueSemantics.intFromNumber(-0.0, negZero), 0,
            "int(-0.0) normalizes to 0");
        int normalized = ((SharedValueSemantics.Int32Result.Value)
            SharedValueSemantics.intFromNumber(-0.0, negZero)).value();
        check(1.0 / (double) normalized == Double.POSITIVE_INFINITY,
            "1 / int(-0.0) is +Infinity (the normalized value is +0, not -0)");

        // checkInt32Integral: the descriptor-path tail after kind checks.
        expectValue(SharedValueSemantics.checkInt32Integral(-2147483648L, checkOk),
            -2147483648, "checkInt32Integral(min)");
        expectValue(SharedValueSemantics.checkInt32Integral(2147483647L, checkOk),
            2147483647, "checkInt32Integral(max)");
        expectValue(SharedValueSemantics.checkInt32Integral(0L, checkOk), 0,
            "checkInt32Integral(0)");
        expectFailAt(SharedValueSemantics.checkInt32Integral(-2147483649L, checkOver),
            "E8004", "int out of range", checkOver,
            "checkInt32Integral(min - 1)");
        expectFailAt(SharedValueSemantics.checkInt32Integral(2147483648L, checkOver),
            "E8004", "int out of range", checkOver,
            "checkInt32Integral(max + 1)");
        expectFailAt(SharedValueSemantics.checkInt32Integral(Long.MIN_VALUE, checkOver),
            "E8004", "int out of range", checkOver,
            "checkInt32Integral(Long.MIN_VALUE)");
        expectFailAt(SharedValueSemantics.checkInt32Integral(Long.MAX_VALUE, checkOver),
            "E8004", "int out of range", checkOver,
            "checkInt32Integral(Long.MAX_VALUE)");

        // numberFromInt: exact double for every int32.
        check(SharedValueSemantics.numberFromInt(2147483647L) == 2147483647.0,
            "numberFromInt(max) == 2147483647.0 exact");
        check(SharedValueSemantics.numberFromInt(-2147483648L) == -2147483648.0,
            "numberFromInt(min) == -2147483648.0 exact");
        check(SharedValueSemantics.numberFromInt(0L) == 0.0
                && Double.doubleToRawLongBits(SharedValueSemantics.numberFromInt(0L)) == 0L,
            "numberFromInt(0) == +0.0");
        check(SharedValueSemantics.numberFromInt(16777217L) == 16777217.0,
            "numberFromInt(16777217) == 16777217.0 exact");

        // The normalized -0 proof: dividing by the normalized int gives
        // +Infinity, never -Infinity.
        check(SharedValueSemantics.numberDiv(1.0, (double) normalized)
                == Double.POSITIVE_INFINITY,
            "numberDiv(1.0, int(-0.0)) = +Infinity (normalized to +0)");
        check(Double.isNaN(SharedValueSemantics.numberDiv(0.0, (double) normalized)),
            "numberDiv(0.0, int(-0.0)) = NaN");
    }

    static void testSharedValueSemanticsNumberOps() {
        System.out.println("-- Number ops: IEEE add/sub/mul/div/neg, floor-mod --");

        check(SharedValueSemantics.numberAdd(2.5, 4.0) == 6.5, "numberAdd 2.5 + 4.0");
        check(SharedValueSemantics.numberSub(2.5, 4.0) == -1.5, "numberSub 2.5 - 4.0");
        check(SharedValueSemantics.numberMul(2.5, 4.0) == 10.0, "numberMul 2.5 * 4.0");
        check(SharedValueSemantics.numberAdd(0.0, -0.0) == 0.0,
            "numberAdd +0 + -0 = +0 (IEEE)");
        check(Double.isNaN(SharedValueSemantics.numberMul(
                SharedValueSemantics.numberDiv(1.0, 0.0), 0.0)),
            "numberMul(inf, 0) = NaN (IEEE)");

        // IEEE div-by-zero.
        check(SharedValueSemantics.numberDiv(1.0, 0.0) == Double.POSITIVE_INFINITY,
            "numberDiv 1.0 / 0.0 = +Infinity");
        check(SharedValueSemantics.numberDiv(-1.0, 0.0) == Double.NEGATIVE_INFINITY,
            "numberDiv -1.0 / 0.0 = -Infinity");
        check(SharedValueSemantics.numberDiv(1.0, -0.0) == Double.NEGATIVE_INFINITY,
            "numberDiv 1.0 / -0.0 = -Infinity (IEEE zero sign)");
        check(Double.isNaN(SharedValueSemantics.numberDiv(0.0, 0.0)),
            "numberDiv 0.0 / 0.0 = NaN");
        check(Double.isNaN(SharedValueSemantics.numberDiv(0.0, -0.0)),
            "numberDiv 0.0 / -0.0 = NaN");

        // IEEE negation, incl. the -0 -> +0 rule.
        check(Double.doubleToRawLongBits(SharedValueSemantics.numberNeg(-0.0)) == 0L,
            "numberNeg -(-0.0) = +0.0 (raw bits)");
        check(Double.doubleToRawLongBits(SharedValueSemantics.numberNeg(0.0))
                == Double.doubleToRawLongBits(-0.0),
            "numberNeg -(0.0) = -0.0 (raw bits)");
        check(SharedValueSemantics.numberNeg(5.5) == -5.5, "numberNeg 5.5");

        // Floor-mod signs: a - floor(a/b)*b.
        check(SharedValueSemantics.numberModFloor(7.0, 3.0) == 1.0,
            "numberModFloor 7 mod 3 = 1");
        check(SharedValueSemantics.numberModFloor(-7.0, 3.0) == 2.0,
            "numberModFloor -7 mod 3 = 2");
        check(SharedValueSemantics.numberModFloor(7.0, -3.0) == -2.0,
            "numberModFloor 7 mod -3 = -2");
        check(SharedValueSemantics.numberModFloor(-7.0, -3.0) == -1.0,
            "numberModFloor -7 mod -3 = -1");
        check(SharedValueSemantics.numberModFloor(5.5, 2.0) == 1.5,
            "numberModFloor 5.5 mod 2 = 1.5");
        check(SharedValueSemantics.numberModFloor(-5.5, 2.0) == 0.5,
            "numberModFloor -5.5 mod 2 = 0.5");
        check(Double.isNaN(SharedValueSemantics.numberModFloor(Double.NaN, 3.0)),
            "numberModFloor NaN propagates NaN");
        check(Double.isNaN(SharedValueSemantics.numberModFloor(1.0, 0.0)),
            "numberModFloor x mod 0 = NaN");
    }

    static void testSharedValueSemanticsNumberPow() {
        System.out.println("-- numberPow: the pinned IEEE-754 special-case table --");

        // Ordinary exact cases.
        check(SharedValueSemantics.numberPow(2.0, 10.0) == 1024.0,
            "2.0 ** 10.0 = 1024.0");
        check(SharedValueSemantics.numberPow(-2.0, 3.0) == -8.0,
            "(-2.0) ** 3.0 = -8.0");
        check(SharedValueSemantics.numberPow(-2.0, 4.0) == 16.0,
            "(-2.0) ** 4.0 = 16.0 (parity)");
        check(SharedValueSemantics.numberPow(-2.0, -3.0) == -0.125,
            "(-2.0) ** -3.0 = -0.125 (parity)");
        check(SharedValueSemantics.numberPow(-2.0, -4.0) == 0.0625,
            "(-2.0) ** -4.0 = 0.0625");

        // pow(x, ±0) = 1 for every x incl. NaN.
        check(SharedValueSemantics.numberPow(0.0, 0.0) == 1.0, "0.0 ** 0.0 = 1.0");
        check(SharedValueSemantics.numberPow(-0.0, 0.0) == 1.0, "(-0.0) ** 0.0 = 1.0");
        check(SharedValueSemantics.numberPow(Double.NaN, 0.0) == 1.0, "NaN ** 0.0 = 1.0");
        check(SharedValueSemantics.numberPow(2.0, -0.0) == 1.0, "2.0 ** -0.0 = 1.0");

        // pow(1, y) = 1 for every y incl. NaN (the first pinned correction).
        check(SharedValueSemantics.numberPow(1.0, Double.NaN) == 1.0,
            "1.0 ** NaN = 1.0 (Java-vs-IEEE correction)");
        check(SharedValueSemantics.numberPow(1.0, 5.0) == 1.0, "1.0 ** 5.0 = 1.0");

        // pow(±1, ±Infinity) = 1 (the second pinned correction).
        check(SharedValueSemantics.numberPow(1.0, Double.POSITIVE_INFINITY) == 1.0,
            "1.0 ** +Infinity = 1.0 (Java-vs-IEEE correction)");
        check(SharedValueSemantics.numberPow(1.0, Double.NEGATIVE_INFINITY) == 1.0,
            "1.0 ** -Infinity = 1.0 (Java-vs-IEEE correction)");
        check(SharedValueSemantics.numberPow(-1.0, Double.POSITIVE_INFINITY) == 1.0,
            "(-1.0) ** +Infinity = 1.0 (Java-vs-IEEE correction)");
        check(SharedValueSemantics.numberPow(-1.0, Double.NEGATIVE_INFINITY) == 1.0,
            "(-1.0) ** -Infinity = 1.0 (Java-vs-IEEE correction)");

        // pow(x, NaN) = NaN for x != 1.
        check(Double.isNaN(SharedValueSemantics.numberPow(Double.NaN, Double.NaN)),
            "NaN ** NaN = NaN");
        check(Double.isNaN(SharedValueSemantics.numberPow(2.0, Double.NaN)),
            "2.0 ** NaN = NaN");
        check(Double.isNaN(SharedValueSemantics.numberPow(0.0, Double.NaN)),
            "0.0 ** NaN = NaN");

        // ±0 bases: sign rules for negative odd-integer y.
        check(SharedValueSemantics.numberPow(0.0, -1.0) == Double.POSITIVE_INFINITY,
            "0.0 ** -1.0 = +Infinity");
        check(SharedValueSemantics.numberPow(-0.0, -1.0) == Double.NEGATIVE_INFINITY,
            "(-0.0) ** -1.0 = -Infinity (odd y preserves zero sign)");
        check(SharedValueSemantics.numberPow(-0.0, -3.0) == Double.NEGATIVE_INFINITY,
            "(-0.0) ** -3.0 = -Infinity (odd y)");
        check(SharedValueSemantics.numberPow(-0.0, -2.0) == Double.POSITIVE_INFINITY,
            "(-0.0) ** -2.0 = +Infinity (even y)");
        check(Double.doubleToRawLongBits(SharedValueSemantics.numberPow(0.0, 3.0)) == 0L,
            "0.0 ** 3.0 = +0.0 (raw bits)");
        check(Double.doubleToRawLongBits(SharedValueSemantics.numberPow(-0.0, 3.0))
                == Double.doubleToRawLongBits(-0.0),
            "(-0.0) ** 3.0 = -0.0 (odd y preserves zero sign)");
        check(Double.doubleToRawLongBits(SharedValueSemantics.numberPow(-0.0, 2.0)) == 0L,
            "(-0.0) ** 2.0 = +0.0 (even y)");

        // ±Infinity bases: IEEE sign/parity rules.
        check(SharedValueSemantics.numberPow(Double.POSITIVE_INFINITY, 3.0)
                == Double.POSITIVE_INFINITY,
            "(+inf) ** 3.0 = +Infinity");
        check(SharedValueSemantics.numberPow(Double.POSITIVE_INFINITY, -3.0) == 0.0,
            "(+inf) ** -3.0 = +0.0");
        check(SharedValueSemantics.numberPow(Double.NEGATIVE_INFINITY, 3.0)
                == Double.NEGATIVE_INFINITY,
            "(-inf) ** 3.0 = -Infinity (odd y)");
        check(SharedValueSemantics.numberPow(Double.NEGATIVE_INFINITY, 2.0)
                == Double.POSITIVE_INFINITY,
            "(-inf) ** 2.0 = +Infinity (even y)");
        check(Double.doubleToRawLongBits(
                SharedValueSemantics.numberPow(Double.NEGATIVE_INFINITY, -3.0))
                == Double.doubleToRawLongBits(-0.0),
            "(-inf) ** -3.0 = -0.0 (odd y)");
        check(Double.doubleToRawLongBits(
                SharedValueSemantics.numberPow(Double.NEGATIVE_INFINITY, -2.0)) == 0L,
            "(-inf) ** -2.0 = +0.0 (even y)");

        // Finite bases with infinite exponents.
        check(SharedValueSemantics.numberPow(2.0, Double.POSITIVE_INFINITY)
                == Double.POSITIVE_INFINITY,
            "2.0 ** +Infinity = +Infinity");
        check(SharedValueSemantics.numberPow(2.0, Double.NEGATIVE_INFINITY) == 0.0,
            "2.0 ** -Infinity = +0.0");
        check(SharedValueSemantics.numberPow(-2.0, Double.POSITIVE_INFINITY)
                == Double.POSITIVE_INFINITY,
            "(-2.0) ** +Infinity = +Infinity");
        check(SharedValueSemantics.numberPow(-2.0, Double.NEGATIVE_INFINITY) == 0.0,
            "(-2.0) ** -Infinity = +0.0");
        check(SharedValueSemantics.numberPow(0.5, Double.POSITIVE_INFINITY) == 0.0,
            "0.5 ** +Infinity = +0.0");
        check(SharedValueSemantics.numberPow(0.5, Double.NEGATIVE_INFINITY)
                == Double.POSITIVE_INFINITY,
            "0.5 ** -Infinity = +Infinity");
        check(Double.doubleToRawLongBits(
                SharedValueSemantics.numberPow(-0.5, Double.POSITIVE_INFINITY)) == 0L,
            "(-0.5) ** +Infinity = +0.0");
        check(SharedValueSemantics.numberPow(-0.5, Double.NEGATIVE_INFINITY)
                == Double.POSITIVE_INFINITY,
            "(-0.5) ** -Infinity = +Infinity");

        // Negative finite base with a non-integer exponent -> NaN.
        check(Double.isNaN(SharedValueSemantics.numberPow(-2.0, 0.5)),
            "(-2.0) ** 0.5 = NaN (fractional exponent)");
        check(Double.isNaN(SharedValueSemantics.numberPow(-2.0, 2.5)),
            "(-2.0) ** 2.5 = NaN (fractional exponent)");

        // Overflow to ±Infinity and underflow to ±0 per IEEE.
        check(SharedValueSemantics.numberPow(2.0, 1024.0) == Double.POSITIVE_INFINITY,
            "2.0 ** 1024.0 = +Infinity (overflow)");
        check(SharedValueSemantics.numberPow(-2.0, 1024.0) == Double.POSITIVE_INFINITY,
            "(-2.0) ** 1024.0 = +Infinity (overflow, even y)");
        check(SharedValueSemantics.numberPow(-2.0, 1025.0) == Double.NEGATIVE_INFINITY,
            "(-2.0) ** 1025.0 = -Infinity (overflow, odd y)");
        check(Double.doubleToRawLongBits(SharedValueSemantics.numberPow(2.0, -1075.0)) == 0L,
            "2.0 ** -1075.0 = +0.0 (underflow)");
        check(Double.doubleToRawLongBits(
                SharedValueSemantics.numberPow(-2.0, -1075.0))
                == Double.doubleToRawLongBits(-0.0),
            "(-2.0) ** -1075.0 = -0.0 (underflow, odd y)");
    }

    static void testSharedValueSemanticsComparisons() {
        System.out.println("-- Comparisons: exact int32, IEEE number incl. NaN --");

        check(SharedValueSemantics.boolNot(true) == false, "boolNot true = false");
        check(SharedValueSemantics.boolNot(false) == true, "boolNot false = true");

        check(SharedValueSemantics.int32Eq(5, 5), "int32Eq 5 == 5");
        check(!SharedValueSemantics.int32Eq(5, 6), "int32Eq 5 != 6");
        check(SharedValueSemantics.int32Ne(5, 6), "int32Ne 5 != 6");
        check(!SharedValueSemantics.int32Ne(5, 5), "int32Ne 5 == 5 false");
        check(SharedValueSemantics.int32Lt(-2147483648, 2147483647),
            "int32Lt min < max");
        check(!SharedValueSemantics.int32Lt(2147483647, 2147483647),
            "int32Lt max < max false");
        check(SharedValueSemantics.int32Le(-2147483648, -2147483648),
            "int32Le min <= min");
        check(!SharedValueSemantics.int32Le(2147483647, -2147483648),
            "int32Le max <= min false");
        check(SharedValueSemantics.int32Gt(2147483647, -2147483648),
            "int32Gt max > min");
        check(!SharedValueSemantics.int32Gt(-2147483648, -2147483648),
            "int32Gt min > min false");
        check(SharedValueSemantics.int32Ge(2147483647, 2147483647),
            "int32Ge max >= max");
        check(!SharedValueSemantics.int32Ge(-2147483648, 2147483647),
            "int32Ge min >= max false");

        check(!SharedValueSemantics.numberEq(Double.NaN, Double.NaN),
            "numberEq NaN != NaN");
        check(SharedValueSemantics.numberEq(-0.0, 0.0), "numberEq -0 == +0");
        check(SharedValueSemantics.numberEq(1.5, 1.5), "numberEq 1.5 == 1.5");
        check(SharedValueSemantics.numberNe(Double.NaN, Double.NaN),
            "numberNe NaN != NaN is true");
        check(!SharedValueSemantics.numberNe(-0.0, 0.0), "numberNe -0 != +0 false");
        check(SharedValueSemantics.numberNe(1.5, 2.5), "numberNe 1.5 != 2.5");
        check(!SharedValueSemantics.numberLt(Double.NaN, 1.0),
            "numberLt NaN < 1 false");
        check(!SharedValueSemantics.numberLt(1.0, Double.NaN),
            "numberLt 1 < NaN false");
        check(!SharedValueSemantics.numberLe(Double.NaN, Double.NaN),
            "numberLe NaN <= NaN false");
        check(!SharedValueSemantics.numberGt(Double.NaN, 1.0),
            "numberGt NaN > 1 false");
        check(!SharedValueSemantics.numberGe(1.0, Double.NaN),
            "numberGe 1 >= NaN false");
        check(!SharedValueSemantics.numberLt(-0.0, 0.0), "numberLt -0 < +0 false");
        check(SharedValueSemantics.numberLe(-0.0, 0.0), "numberLe -0 <= +0");
        check(SharedValueSemantics.numberLt(1.5, 2.5), "numberLt 1.5 < 2.5");
        check(SharedValueSemantics.numberGe(2.5, 1.5), "numberGe 2.5 >= 1.5");
        check(!SharedValueSemantics.numberLt(2.5, 1.5), "numberLt 2.5 < 1.5 false");
        check(!SharedValueSemantics.numberGe(1.5, 2.5), "numberGe 1.5 >= 2.5 false");
        check(SharedValueSemantics.numberLt(Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY),
            "numberLt -inf < +inf");
        check(SharedValueSemantics.numberGt(Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY),
            "numberGt +inf > -inf");
        check(SharedValueSemantics.numberLe(Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY),
            "numberLe -inf <= -inf");
        check(!SharedValueSemantics.numberLt(Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY),
            "numberLt +inf < +inf false");
        check(!SharedValueSemantics.numberLt(Double.POSITIVE_INFINITY, Double.NaN),
            "numberLt +inf < NaN false");
    }

    static void testSharedValueSemanticsOrigins() {
        System.out.println("-- Failure origins: caller-supplied operation origin per row --");

        SourceOrigin add = origin("origin-add");
        SourceOrigin div = origin("origin-div");
        SourceOrigin pow = origin("origin-pow");
        SourceOrigin cvt = origin("origin-cvt");
        expectFailAt(SharedValueSemantics.int32Add(2147483647, 1, add),
            "E8004", "int out of range", add, "add overflow origin");
        expectFailAt(SharedValueSemantics.int32Div(1, 0, div),
            "E8005", "integer division by zero", div, "div zero origin");
        expectFailAt(SharedValueSemantics.int32Pow(2, -1, pow),
            "E8006", "integer exponent must be non-negative", pow,
            "pow negative-exponent origin");
        expectFailAt(SharedValueSemantics.int32Pow(2, 1024, pow),
            "E8001", "expected int, got infinity", pow, "pow infinity-band origin");
        expectFailAt(SharedValueSemantics.intFromNumber(Double.NaN, cvt),
            "E8001", "expected int, got NaN", cvt, "conversion NaN origin");
        expectFailAt(SharedValueSemantics.checkInt32Integral(2147483648L, add),
            "E8004", "int out of range", add, "checkInt32Integral origin");
    }

    static void testSelectorPolicyCrossCheck() {
        System.out.println("-- Selector->policy cross-check against the closed table --");

        int covered = 0;
        for (BinarySelector selector : BinarySelector.values()) {
            FailurePolicyId primitivePolicy;
            boolean inRows;
            try {
                primitivePolicy = SharedValueSemantics.binarySelectorPolicy(selector);
                inRows = true;
            } catch (IllegalArgumentException outsideRows) {
                primitivePolicy = null;
                inRows = false;
            }
            if (inRows) {
                FailurePolicyId tablePolicy = SemanticIrValidator.binaryPolicy(selector);
                check(primitivePolicy == tablePolicy,
                    "binary selector " + selector.name() + ": primitive row policy "
                        + primitivePolicy + " agrees with the closed binaryPolicy table ("
                        + tablePolicy + ")");
                covered++;
            }
        }
        check(covered == 24,
            "the primitive covers exactly the 6 INT32_* + 6 NUMBER_* + 12 comparison "
                + "binary selectors (got " + covered + ")");

        // The selectors outside this primitive's rows fail closed.
        for (BinarySelector selector : List.of(BinarySelector.STRING_EQ,
                BinarySelector.STRING_NE, BinarySelector.STRING_LT,
                BinarySelector.STRING_LE, BinarySelector.STRING_GT,
                BinarySelector.STRING_GE, BinarySelector.BOOLEAN_EQ,
                BinarySelector.BOOLEAN_NE, BinarySelector.NULL_EQ,
                BinarySelector.NULL_NE, BinarySelector.NULLABLE_EQ,
                BinarySelector.NULLABLE_NE, BinarySelector.NULLABLE_NULL_EQ,
                BinarySelector.NULLABLE_NULL_NE, BinarySelector.REFERENCE_EQ,
                BinarySelector.REFERENCE_NE)) {
            expectThrows(IllegalArgumentException.class,
                () -> SharedValueSemantics.binarySelectorPolicy(selector),
                selector.name() + " is outside the primitive's rows and fails closed");
        }
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.binarySelectorPolicy(null),
            "binarySelectorPolicy(null) fails closed");

        // Unary rows: the primitive covers the complete unary selector set
        // and each projected policy agrees with the validator's closed
        // UNARY rule (INT32_NEG -> INT32_RESULT; NUMBER_NEG, BOOL_NOT ->
        // NO_DEAL_FAILURE).
        int unaryCovered = 0;
        for (UnarySelector selector : UnarySelector.values()) {
            FailurePolicyId primitivePolicy =
                SharedValueSemantics.unarySelectorPolicy(selector);
            FailurePolicyId tablePolicy = SemanticIrValidator.unaryPolicy(selector);
            check(primitivePolicy == tablePolicy,
                "unary selector " + selector.name() + ": primitive row policy "
                    + primitivePolicy + " agrees with the closed unaryPolicy rule ("
                    + tablePolicy + ")");
            unaryCovered++;
        }
        check(unaryCovered == 3,
            "the primitive covers exactly the three closed unary selectors (got "
                + unaryCovered + ")");
        expectThrows(NullPointerException.class,
            () -> SharedValueSemantics.unarySelectorPolicy(null),
            "unarySelectorPolicy(null) fails closed");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Lowering Foundation Test (ISSUE-0281 + ISSUE-0390) ===\n");

        testE6005Registration();

        testClosedEnum(SemanticProfile.class,
            List.of("LEGACY_SAFE_INT", "DEAL_V1_2_INT32"), "SemanticProfile");
        testClosedEnum(InvocationPurpose.class,
            List.of("PUBLIC_BUILD", "COMMON_SHADOW", "LEGACY_REGRESSION"),
            "InvocationPurpose");
        testClosedEnum(ReleaseState.class,
            List.of("PRE_ACTIVATION", "V1_2_ACTIVE"), "ReleaseState");
        testClosedEnum(SemanticCapability.class,
            List.of("FOUNDATION_VALUES", "SIGNED_INT32", "CONTAINERS_AND_STRINGS",
                "DESCRIPTORS", "BOUNDARIES", "EVALUATION_ORDER", "BINDINGS",
                "CALLS", "STDLIB_SEMANTICS", "STDLIB_TIME_CONFLICT", "CLASSES",
                "MODULES"),
            "SemanticCapability");

        testLoweringFailureDetail();

        testSharedValueSemanticsSurface();
        testSharedValueSemanticsInt32Arithmetic();
        testSharedValueSemanticsInt32DivMod();
        testSharedValueSemanticsInt32Pow();
        testSharedValueSemanticsConversion();
        testSharedValueSemanticsNumberOps();
        testSharedValueSemanticsNumberPow();
        testSharedValueSemanticsComparisons();
        testSharedValueSemanticsOrigins();
        testSelectorPolicyCrossCheck();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
