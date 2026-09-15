package deal.test;

import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.ComparisonExecutor;
import deal.semantic.ir.ComparisonOperandView;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.UnicodeScalars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ISSUE-0234 B-D1/B-D2/B-D4 surface: the closed
 * {@link ComparisonOperandView} and {@link ComparisonExecutor} as the
 * single, pure execution form of the 30 comparison selectors of
 * {@code deal.semantic-ir/1}, with the missing≡null rule at both operand
 * positions.
 *
 * <p>Tests (binary-comparison-selectors Verification 1/2/3 and the
 * NO_DEAL_FAILURE sweep):</p>
 * <ol>
 *   <li>View shape: the seven closed variants, fail-closed construction
 *       (null scalars/token), and the validated-scalars invariant.</li>
 *   <li>INT32 EQ/NE and all four signed orderings, incl. the null and
 *       missing operand rows at both positions.</li>
 *   <li>NUMBER IEEE matrix: {@code NaN === NaN} false,
 *       {@code NaN !== NaN} true, {@code -0.0 === 0.0} true,
 *       {@code -0.0 < 0.0} false, {@code -0.0 <= 0.0} true, NaN false
 *       in every ordering.</li>
 *   <li>STRING equality and scalar (code point) order with supplementary
 *       characters — the pinned seed {@code "\uE000"} vs {@code U+1F600}
 *       sorts opposite to UTF-16 code-unit order and yields the
 *       scalar-order result.</li>
 *   <li>BOOLEAN EQ/NE and NULL EQ/NE.</li>
 *   <li>Every {@code NULLABLE_*} side LEFT/RIGHT/BOTH with
 *       null-vs-null, null-vs-value, and value-vs-value pairs per inner
 *       kind (int, number incl. NaN, string incl. supplementary,
 *       boolean, array/table/class/function identity);
 *       {@code NULLABLE_NULL_*} both directions.</li>
 *   <li>{@code REFERENCE_*} for array, table, class, and function
 *       identities: two distinct values of equal shape → NE, the same
 *       allocation/function identity → EQ; null/missing rows.</li>
 *   <li>Missing≡null at both operand positions across every selector
 *       family with no error raised (the
 *       {@code jvm-arr-cmp-past-end-parity} shapes).</li>
 *   <li>NO_DEAL_FAILURE sweep: every comparison selector with
 *       null/missing operands returns a boolean without throwing;
 *       arithmetic selectors are producer defects.</li>
 *   <li>Fail-closed defects: wrong operand families, null-literal
 *       selectors with value operands, missing/broken
 *       {@code NULLABLE_*}/{@code REFERENCE_*} payloads, arithmetic
 *       selectors; documented NPEs.</li>
 *   <li>Component discipline: the executor and view carry no
 *       {@code deal.diagnostics}/{@code deal.types}/{@code deal.ast}/
 *       {@code deal.checker}/{@code deal.codegen} import and the
 *       executor references no boundary-op type (B-D5: no boundary
 *       execution).</li>
 *   <li>Exactly 30 comparison selectors and 12 arithmetic selectors in
 *       the closed set; determinism of repeated execution.</li>
 * </ol>
 */
public class ComparisonExecutorTest {

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

    private static void expectDefect(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected ComparisonExecutor.Defect for " + what
                + ", but no exception was raised");
        } catch (ComparisonExecutor.Defect expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected ComparisonExecutor.Defect for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    private static void expectNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    // =========================================================================
    // Operand factories and payload constants
    // =========================================================================

    private static final ComparisonOperandView NULL = ComparisonOperandView.Null.INSTANCE;
    private static final ComparisonOperandView MISSING = ComparisonOperandView.Missing.INSTANCE;

    private static ComparisonOperandView i(int value) {
        return new ComparisonOperandView.Int(value);
    }

    private static ComparisonOperandView n(double value) {
        return new ComparisonOperandView.Number(value);
    }

    private static ComparisonOperandView s(String value) {
        return new ComparisonOperandView.String(new UnicodeScalars.Valid(value));
    }

    private static ComparisonOperandView b(boolean value) {
        return new ComparisonOperandView.Boolean(value);
    }

    private static ComparisonOperandView.Ref ref(Object token) {
        return new ComparisonOperandView.Ref(token);
    }

    private static boolean cmp(BinarySelector selector, ComparisonOperandView left,
                               ComparisonOperandView right) {
        return ComparisonExecutor.compare(selector, left, right, null, null);
    }

    private static boolean cmp(BinarySelector selector, ComparisonOperandView left,
                               ComparisonOperandView right, RuntimeDescriptor inner,
                               NullableSide side) {
        return ComparisonExecutor.compare(selector, left, right, inner, side);
    }

    private static final RuntimeDescriptor INT = RuntimeDescriptor.Int.INSTANCE;
    private static final RuntimeDescriptor NUMBER = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor STRING = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor BOOLEAN = RuntimeDescriptor.Boolean.INSTANCE;
    private static final RuntimeDescriptor TABLE = RuntimeDescriptor.Table.INSTANCE;
    private static final RuntimeDescriptor ARRAY = new RuntimeDescriptor.Array(INT);
    private static final RuntimeDescriptor CLASS =
        new RuntimeDescriptor.Class(new deal.semantic.ir.ClassId("src/app", "User"));
    private static final RuntimeDescriptor FUNC = new RuntimeDescriptor.Func(
        List.of(INT), NUMBER);
    private static final RuntimeDescriptor BYTES = RuntimeDescriptor.Bytes.INSTANCE;

    // =========================================================================
    // 1. View shape
    // =========================================================================

    private static void testViewShapes() {
        System.out.println("-- ComparisonOperandView: closed shape --");

        check(NULL instanceof ComparisonOperandView.Null, "Null.INSTANCE is the Null variant");
        check(MISSING instanceof ComparisonOperandView.Missing,
            "Missing.INSTANCE is the Missing variant");
        check(b(true).equals(new ComparisonOperandView.Boolean(true)),
            "Boolean views compare by value");
        check(i(7).equals(new ComparisonOperandView.Int(7)), "Int views compare by value");
        check(n(1.5).equals(new ComparisonOperandView.Number(1.5)),
            "Number views compare by value");

        // Strings carry validated scalars: the Valid carrier fails closed
        // for a lone UTF-16 surrogate unit, so an invalid scalar encoding
        // can never construct a String view (the producing string
        // boundary rejects it; a comparison never sees it).
        UnicodeScalars.Valid valid = new UnicodeScalars.Valid("a\uD83D\uDE00b");
        check(s("a\uD83D\uDE00b").equals(new ComparisonOperandView.String(valid)),
            "a String view carries its validated scalar carrier");
        try {
            new UnicodeScalars.Valid("\uD800");
            fail("UnicodeScalars.Valid must reject a lone high surrogate");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        try {
            new UnicodeScalars.Valid("\uDC00");
            fail("UnicodeScalars.Valid must reject a lone low surrogate");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        expectNpe(() -> new ComparisonOperandView.String(null),
            "a String view with a null scalar carrier");
        expectNpe(() -> new ComparisonOperandView.Ref(null),
            "a Ref view with a null allocation token");

        Object token = new Object();
        check(ref(token).allocationIdentity() == token,
            "a Ref view carries its allocation token");
    }

    // =========================================================================
    // 2. INT32 matrix
    // =========================================================================

    private static void testInt32Matrix() {
        System.out.println("-- INT32_EQ/NE/LT/LE/GT/GE --");

        // Value rows.
        check(cmp(BinarySelector.INT32_EQ, i(1), i(1)), "INT32_EQ(1,1)");
        check(!cmp(BinarySelector.INT32_NE, i(1), i(1)), "INT32_NE(1,1) false");
        check(!cmp(BinarySelector.INT32_EQ, i(1), i(2)), "INT32_EQ(1,2) false");
        check(cmp(BinarySelector.INT32_NE, i(1), i(2)), "INT32_NE(1,2)");
        check(cmp(BinarySelector.INT32_LT, i(-5), i(3)), "INT32_LT(-5,3) signed");
        check(cmp(BinarySelector.INT32_LE, i(-5), i(-5)), "INT32_LE(-5,-5)");
        check(cmp(BinarySelector.INT32_LE, i(-5), i(3)), "INT32_LE(-5,3)");
        check(!cmp(BinarySelector.INT32_LT, i(3), i(-5)), "INT32_LT(3,-5) false");
        check(cmp(BinarySelector.INT32_GT, i(3), i(-5)), "INT32_GT(3,-5)");
        check(cmp(BinarySelector.INT32_GE, i(3), i(3)), "INT32_GE(3,3)");
        check(!cmp(BinarySelector.INT32_GE, i(-5), i(3)), "INT32_GE(-5,3) false");
        check(!cmp(BinarySelector.INT32_GT, i(-5), i(3)), "INT32_GT(-5,3) false");

        // Signed32 extremes: no widening, signed order.
        check(cmp(BinarySelector.INT32_LT, i(Integer.MIN_VALUE), i(-1)),
            "INT32_LT(MIN_VALUE,-1)");
        check(cmp(BinarySelector.INT32_GT, i(Integer.MAX_VALUE), i(Integer.MIN_VALUE)),
            "INT32_GT(MAX_VALUE,MIN_VALUE)");
        check(cmp(BinarySelector.INT32_LE, i(Integer.MIN_VALUE), i(Integer.MIN_VALUE)),
            "INT32_LE(MIN_VALUE,MIN_VALUE)");
        check(cmp(BinarySelector.INT32_GE, i(Integer.MAX_VALUE), i(Integer.MAX_VALUE)),
            "INT32_GE(MAX_VALUE,MAX_VALUE)");

        // Null operand rows: null vs int → EQ false, NE true, every
        // ordering false — at both operand positions.
        for (ComparisonOperandView nullish : List.of(NULL, MISSING)) {
            check(!cmp(BinarySelector.INT32_EQ, nullish, i(5)),
                "INT32_EQ(" + nullish.getClass().getSimpleName() + ",5) false");
            check(cmp(BinarySelector.INT32_NE, nullish, i(5)),
                "INT32_NE(" + nullish.getClass().getSimpleName() + ",5)");
            check(!cmp(BinarySelector.INT32_LT, nullish, i(5)),
                "INT32_LT(" + nullish.getClass().getSimpleName() + ",5) false");
            check(!cmp(BinarySelector.INT32_LE, nullish, i(5)),
                "INT32_LE(" + nullish.getClass().getSimpleName() + ",5) false");
            check(!cmp(BinarySelector.INT32_GT, nullish, i(5)),
                "INT32_GT(" + nullish.getClass().getSimpleName() + ",5) false");
            check(!cmp(BinarySelector.INT32_GE, nullish, i(5)),
                "INT32_GE(" + nullish.getClass().getSimpleName() + ",5) false");
            check(!cmp(BinarySelector.INT32_EQ, i(5), nullish),
                "INT32_EQ(5," + nullish.getClass().getSimpleName() + ") false");
            check(cmp(BinarySelector.INT32_NE, i(5), nullish),
                "INT32_NE(5," + nullish.getClass().getSimpleName() + ")");
            check(!cmp(BinarySelector.INT32_LT, i(5), nullish),
                "INT32_LT(5," + nullish.getClass().getSimpleName() + ") false");
            check(!cmp(BinarySelector.INT32_LE, i(5), nullish),
                "INT32_LE(5," + nullish.getClass().getSimpleName() + ") false");
            check(!cmp(BinarySelector.INT32_GT, i(5), nullish),
                "INT32_GT(5," + nullish.getClass().getSimpleName() + ") false");
            check(!cmp(BinarySelector.INT32_GE, i(5), nullish),
                "INT32_GE(5," + nullish.getClass().getSimpleName() + ") false");
        }

        // missing === missing is true (the jvm-arr-cmp-past-end-parity pin).
        check(cmp(BinarySelector.INT32_EQ, MISSING, MISSING), "INT32_EQ(missing,missing)");
        check(!cmp(BinarySelector.INT32_NE, MISSING, MISSING), "INT32_NE(missing,missing) false");
        check(!cmp(BinarySelector.INT32_LT, MISSING, MISSING), "INT32_LT(missing,missing) false");
        check(!cmp(BinarySelector.INT32_LE, MISSING, MISSING), "INT32_LE(missing,missing) false");
        check(!cmp(BinarySelector.INT32_GT, MISSING, MISSING), "INT32_GT(missing,missing) false");
        check(!cmp(BinarySelector.INT32_GE, MISSING, MISSING), "INT32_GE(missing,missing) false");
        check(cmp(BinarySelector.INT32_EQ, NULL, NULL), "INT32_EQ(null,null)");
        check(!cmp(BinarySelector.INT32_NE, NULL, NULL), "INT32_NE(null,null) false");
        check(cmp(BinarySelector.INT32_EQ, NULL, MISSING), "INT32_EQ(null,missing)");
    }

    // =========================================================================
    // 3. NUMBER matrix
    // =========================================================================

    private static void testNumberMatrix() {
        System.out.println("-- NUMBER_EQ/NE/LT/LE/GT/GE (IEEE-754) --");

        // IEEE equality.
        check(!cmp(BinarySelector.NUMBER_EQ, n(Double.NaN), n(Double.NaN)),
            "NUMBER_EQ(NaN,NaN) false");
        check(cmp(BinarySelector.NUMBER_NE, n(Double.NaN), n(Double.NaN)),
            "NUMBER_NE(NaN,NaN)");
        check(cmp(BinarySelector.NUMBER_EQ, n(-0.0), n(0.0)), "NUMBER_EQ(-0.0,0.0)");
        check(!cmp(BinarySelector.NUMBER_NE, n(-0.0), n(0.0)), "NUMBER_NE(-0.0,0.0) false");
        check(cmp(BinarySelector.NUMBER_EQ, n(1.5), n(1.5)), "NUMBER_EQ(1.5,1.5)");
        check(!cmp(BinarySelector.NUMBER_EQ, n(1.5), n(2.5)), "NUMBER_EQ(1.5,2.5) false");
        check(cmp(BinarySelector.NUMBER_NE, n(1.5), n(2.5)), "NUMBER_NE(1.5,2.5)");

        // IEEE order predicates.
        check(!cmp(BinarySelector.NUMBER_LT, n(-0.0), n(0.0)), "NUMBER_LT(-0.0,0.0) false");
        check(cmp(BinarySelector.NUMBER_LE, n(-0.0), n(0.0)), "NUMBER_LE(-0.0,0.0)");
        check(!cmp(BinarySelector.NUMBER_GT, n(-0.0), n(0.0)), "NUMBER_GT(-0.0,0.0) false");
        check(cmp(BinarySelector.NUMBER_GE, n(-0.0), n(0.0)), "NUMBER_GE(-0.0,0.0)");
        check(cmp(BinarySelector.NUMBER_LT, n(1.5), n(2.5)), "NUMBER_LT(1.5,2.5)");
        check(cmp(BinarySelector.NUMBER_LE, n(2.5), n(2.5)), "NUMBER_LE(2.5,2.5)");
        check(cmp(BinarySelector.NUMBER_GT, n(2.5), n(1.5)), "NUMBER_GT(2.5,1.5)");
        check(!cmp(BinarySelector.NUMBER_GE, n(1.5), n(2.5)), "NUMBER_GE(1.5,2.5) false");
        check(cmp(BinarySelector.NUMBER_LT, n(Double.NEGATIVE_INFINITY),
            n(Double.POSITIVE_INFINITY)), "NUMBER_LT(-inf,inf)");
        check(cmp(BinarySelector.NUMBER_EQ, n(Double.POSITIVE_INFINITY),
            n(Double.POSITIVE_INFINITY)), "NUMBER_EQ(inf,inf)");

        // NaN makes every ordering false.
        check(!cmp(BinarySelector.NUMBER_LT, n(Double.NaN), n(1.0)), "NUMBER_LT(NaN,1) false");
        check(!cmp(BinarySelector.NUMBER_LE, n(Double.NaN), n(1.0)), "NUMBER_LE(NaN,1) false");
        check(!cmp(BinarySelector.NUMBER_GT, n(Double.NaN), n(1.0)), "NUMBER_GT(NaN,1) false");
        check(!cmp(BinarySelector.NUMBER_GE, n(Double.NaN), n(1.0)), "NUMBER_GE(NaN,1) false");
        check(!cmp(BinarySelector.NUMBER_LT, n(1.0), n(Double.NaN)), "NUMBER_LT(1,NaN) false");
        check(!cmp(BinarySelector.NUMBER_LE, n(1.0), n(Double.NaN)), "NUMBER_LE(1,NaN) false");
        check(!cmp(BinarySelector.NUMBER_GT, n(1.0), n(Double.NaN)), "NUMBER_GT(1,NaN) false");
        check(!cmp(BinarySelector.NUMBER_GE, n(1.0), n(Double.NaN)), "NUMBER_GE(1,NaN) false");
        check(!cmp(BinarySelector.NUMBER_LT, n(Double.NaN), n(Double.NaN)),
            "NUMBER_LT(NaN,NaN) false");
        check(!cmp(BinarySelector.NUMBER_LE, n(Double.NaN), n(Double.NaN)),
            "NUMBER_LE(NaN,NaN) false");
        check(!cmp(BinarySelector.NUMBER_GT, n(Double.NaN), n(Double.NaN)),
            "NUMBER_GT(NaN,NaN) false");
        check(!cmp(BinarySelector.NUMBER_GE, n(Double.NaN), n(Double.NaN)),
            "NUMBER_GE(NaN,NaN) false");

        // Null/missing rows.
        for (ComparisonOperandView nullish : List.of(NULL, MISSING)) {
            check(!cmp(BinarySelector.NUMBER_EQ, nullish, n(1.5)),
                "NUMBER_EQ(" + nullish.getClass().getSimpleName() + ",1.5) false");
            check(cmp(BinarySelector.NUMBER_NE, nullish, n(1.5)),
                "NUMBER_NE(" + nullish.getClass().getSimpleName() + ",1.5)");
            check(!cmp(BinarySelector.NUMBER_LT, nullish, n(1.5)),
                "NUMBER_LT(" + nullish.getClass().getSimpleName() + ",1.5) false");
            check(!cmp(BinarySelector.NUMBER_LE, nullish, n(1.5)),
                "NUMBER_LE(" + nullish.getClass().getSimpleName() + ",1.5) false");
            check(!cmp(BinarySelector.NUMBER_GT, nullish, n(1.5)),
                "NUMBER_GT(" + nullish.getClass().getSimpleName() + ",1.5) false");
            check(!cmp(BinarySelector.NUMBER_GE, nullish, n(1.5)),
                "NUMBER_GE(" + nullish.getClass().getSimpleName() + ",1.5) false");
            check(!cmp(BinarySelector.NUMBER_EQ, n(1.5), nullish),
                "NUMBER_EQ(1.5," + nullish.getClass().getSimpleName() + ") false");
            check(cmp(BinarySelector.NUMBER_NE, n(1.5), nullish),
                "NUMBER_NE(1.5," + nullish.getClass().getSimpleName() + ")");
        }
        check(cmp(BinarySelector.NUMBER_EQ, MISSING, MISSING), "NUMBER_EQ(missing,missing)");
        check(!cmp(BinarySelector.NUMBER_NE, NULL, NULL), "NUMBER_NE(null,null) false");
    }

    // =========================================================================
    // 4. STRING matrix
    // =========================================================================

    private static void testStringMatrix() {
        System.out.println("-- STRING_EQ/NE/LT/LE/GT/GE (Unicode scalar order) --");

        check(cmp(BinarySelector.STRING_EQ, s("abc"), s("abc")), "STRING_EQ(abc,abc)");
        check(!cmp(BinarySelector.STRING_NE, s("abc"), s("abc")), "STRING_NE(abc,abc) false");
        check(!cmp(BinarySelector.STRING_EQ, s("abc"), s("abd")), "STRING_EQ(abc,abd) false");
        check(cmp(BinarySelector.STRING_NE, s("abc"), s("abd")), "STRING_NE(abc,abd)");
        check(cmp(BinarySelector.STRING_LT, s("abc"), s("abd")), "STRING_LT(abc,abd)");
        check(cmp(BinarySelector.STRING_LE, s("abc"), s("abc")), "STRING_LE(abc,abc)");
        check(!cmp(BinarySelector.STRING_GT, s("abc"), s("abd")), "STRING_GT(abc,abd) false");
        check(cmp(BinarySelector.STRING_GT, s("abd"), s("abc")), "STRING_GT(abd,abc)");
        check(!cmp(BinarySelector.STRING_GE, s("abc"), s("abd")), "STRING_GE(abc,abd) false");
        check(cmp(BinarySelector.STRING_GE, s("abd"), s("abd")), "STRING_GE(abd,abd)");
        check(cmp(BinarySelector.STRING_LT, s("ab"), s("abc")),
            "STRING_LT(ab,abc) shorter prefix first");
        check(cmp(BinarySelector.STRING_GT, s("abc"), s("ab")),
            "STRING_GT(abc,ab) longer tail after");

        // Supplementary characters: code point order, never UTF-16
        // code-unit order. U+E000 (57344) < U+1F600 (128512) by code
        // point, while the UTF-16 carrier of U+1F600 starts with 0xD83D
        // (55357) < 0xE000 — the UTF-16 code-unit ordering sorts the
        // pair the other way around (pinned divergence seed).
        String bmp = "\uE000";
        String supplementary = "\uD83D\uDE00"; // U+1F600
        check(bmp.compareTo(supplementary) > 0,
            "the pinned seed diverges: UTF-16 code-unit order sorts '" + bmp
                + "' after '" + supplementary + "'");
        check(cmp(BinarySelector.STRING_LT, s(bmp), s(supplementary)),
            "STRING_LT(U+E000,U+1F600) scalar order");
        check(!cmp(BinarySelector.STRING_LT, s(supplementary), s(bmp)),
            "STRING_LT(U+1F600,U+E000) false");
        check(cmp(BinarySelector.STRING_LE, s(bmp), s(supplementary)),
            "STRING_LE(U+E000,U+1F600)");
        check(cmp(BinarySelector.STRING_GT, s(supplementary), s(bmp)),
            "STRING_GT(U+1F600,U+E000)");
        check(!cmp(BinarySelector.STRING_GT, s(bmp), s(supplementary)),
            "STRING_GT(U+E000,U+1F600) false");
        check(cmp(BinarySelector.STRING_GE, s(supplementary), s(bmp)),
            "STRING_GE(U+1F600,U+E000)");
        check(!cmp(BinarySelector.STRING_EQ, s(bmp), s(supplementary)),
            "STRING_EQ(U+E000,U+1F600) false");
        check(cmp(BinarySelector.STRING_NE, s(bmp), s(supplementary)),
            "STRING_NE(U+E000,U+1F600)");
        check(cmp(BinarySelector.STRING_EQ, s(supplementary), s(supplementary)),
            "STRING_EQ(U+1F600,U+1F600)");
        check(cmp(BinarySelector.STRING_LT, s("a\uE000"), s("a\uD83D\uDE00")),
            "STRING_LT with a shared BMP prefix still orders by code point");
        check(cmp(BinarySelector.STRING_LT, s("\uFFFF"), s(supplementary)),
            "STRING_LT(U+FFFF,U+1F600): BMP scalars order below supplementary scalars");
        check(cmp(BinarySelector.STRING_GT, s(supplementary), s("a")),
            "STRING_GT(U+1F600,a): supplementary scalar orders above BMP scalars");

        // Null/missing rows.
        for (ComparisonOperandView nullish : List.of(NULL, MISSING)) {
            check(!cmp(BinarySelector.STRING_EQ, nullish, s("a")),
                "STRING_EQ(" + nullish.getClass().getSimpleName() + ",a) false");
            check(cmp(BinarySelector.STRING_NE, nullish, s("a")),
                "STRING_NE(" + nullish.getClass().getSimpleName() + ",a)");
            check(!cmp(BinarySelector.STRING_LT, nullish, s("a")),
                "STRING_LT(" + nullish.getClass().getSimpleName() + ",a) false");
            check(!cmp(BinarySelector.STRING_LE, nullish, s("a")),
                "STRING_LE(" + nullish.getClass().getSimpleName() + ",a) false");
            check(!cmp(BinarySelector.STRING_GT, nullish, s("a")),
                "STRING_GT(" + nullish.getClass().getSimpleName() + ",a) false");
            check(!cmp(BinarySelector.STRING_GE, nullish, s("a")),
                "STRING_GE(" + nullish.getClass().getSimpleName() + ",a) false");
            check(!cmp(BinarySelector.STRING_EQ, s("a"), nullish),
                "STRING_EQ(a," + nullish.getClass().getSimpleName() + ") false");
            check(cmp(BinarySelector.STRING_NE, s("a"), nullish),
                "STRING_NE(a," + nullish.getClass().getSimpleName() + ")");
        }
        check(cmp(BinarySelector.STRING_EQ, MISSING, MISSING), "STRING_EQ(missing,missing)");
        check(cmp(BinarySelector.STRING_EQ, NULL, NULL), "STRING_EQ(null,null)");
    }

    // =========================================================================
    // 5. BOOLEAN and NULL matrices
    // =========================================================================

    private static void testBooleanAndNullMatrices() {
        System.out.println("-- BOOLEAN_EQ/NE, NULL_EQ/NE --");

        check(cmp(BinarySelector.BOOLEAN_EQ, b(true), b(true)), "BOOLEAN_EQ(true,true)");
        check(!cmp(BinarySelector.BOOLEAN_EQ, b(true), b(false)), "BOOLEAN_EQ(true,false) false");
        check(!cmp(BinarySelector.BOOLEAN_NE, b(true), b(true)), "BOOLEAN_NE(true,true) false");
        check(cmp(BinarySelector.BOOLEAN_NE, b(true), b(false)), "BOOLEAN_NE(true,false)");
        check(cmp(BinarySelector.BOOLEAN_EQ, b(false), b(false)), "BOOLEAN_EQ(false,false)");
        check(!cmp(BinarySelector.BOOLEAN_EQ, MISSING, b(true)), "BOOLEAN_EQ(missing,true) false");
        check(cmp(BinarySelector.BOOLEAN_NE, b(true), MISSING), "BOOLEAN_NE(true,missing)");
        check(cmp(BinarySelector.BOOLEAN_EQ, NULL, NULL), "BOOLEAN_EQ(null,null)");
        check(cmp(BinarySelector.BOOLEAN_EQ, MISSING, MISSING),
            "BOOLEAN_EQ(missing,missing)");

        check(cmp(BinarySelector.NULL_EQ, NULL, NULL), "NULL_EQ(null,null)");
        check(!cmp(BinarySelector.NULL_NE, NULL, NULL), "NULL_NE(null,null) false");
        check(cmp(BinarySelector.NULL_EQ, NULL, MISSING), "NULL_EQ(null,missing)");
        check(cmp(BinarySelector.NULL_EQ, MISSING, MISSING), "NULL_EQ(missing,missing)");
        check(!cmp(BinarySelector.NULL_NE, MISSING, NULL), "NULL_NE(missing,null) false");
    }

    // =========================================================================
    // 6. NULLABLE matrices
    // =========================================================================

    private static void testNullableMatrix() {
        System.out.println("-- NULLABLE_EQ/NE (sides LEFT/RIGHT/BOTH) --");

        // BOTH, inner int.
        check(cmp(BinarySelector.NULLABLE_EQ, NULL, NULL, INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,null)");
        check(!cmp(BinarySelector.NULLABLE_NE, NULL, NULL, INT, NullableSide.BOTH),
            "NULLABLE_NE BOTH(null,null) false");
        check(!cmp(BinarySelector.NULLABLE_EQ, NULL, i(5), INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,5) false");
        check(cmp(BinarySelector.NULLABLE_NE, NULL, i(5), INT, NullableSide.BOTH),
            "NULLABLE_NE BOTH(null,5)");
        check(!cmp(BinarySelector.NULLABLE_EQ, i(5), NULL, INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(5,null) false");
        check(cmp(BinarySelector.NULLABLE_NE, i(5), NULL, INT, NullableSide.BOTH),
            "NULLABLE_NE BOTH(5,null)");
        check(cmp(BinarySelector.NULLABLE_EQ, i(5), i(5), INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(5,5)");
        check(!cmp(BinarySelector.NULLABLE_EQ, i(5), i(6), INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(5,6) false");
        check(cmp(BinarySelector.NULLABLE_NE, i(5), i(6), INT, NullableSide.BOTH),
            "NULLABLE_NE BOTH(5,6)");

        // LEFT and RIGHT: null rules apply at the nullable position.
        check(!cmp(BinarySelector.NULLABLE_EQ, NULL, i(5), INT, NullableSide.LEFT),
            "NULLABLE_EQ LEFT(null,5) false");
        check(cmp(BinarySelector.NULLABLE_EQ, i(5), i(5), INT, NullableSide.LEFT),
            "NULLABLE_EQ LEFT(5,5)");
        check(!cmp(BinarySelector.NULLABLE_EQ, i(5), NULL, INT, NullableSide.RIGHT),
            "NULLABLE_EQ RIGHT(5,null) false");
        check(cmp(BinarySelector.NULLABLE_EQ, i(5), i(5), INT, NullableSide.RIGHT),
            "NULLABLE_EQ RIGHT(5,5)");
        check(cmp(BinarySelector.NULLABLE_EQ, NULL, NULL, INT, NullableSide.LEFT),
            "NULLABLE_EQ LEFT(null,null)");
        check(cmp(BinarySelector.NULLABLE_EQ, NULL, NULL, INT, NullableSide.RIGHT),
            "NULLABLE_EQ RIGHT(null,null)");
        check(!cmp(BinarySelector.NULLABLE_EQ, MISSING, i(5), INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(missing,5) false");
        check(!cmp(BinarySelector.NULLABLE_EQ, i(5), MISSING, INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(5,missing) false");
        check(cmp(BinarySelector.NULLABLE_EQ, MISSING, MISSING, INT, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(missing,missing)");

        // Inner number: IEEE equality inside the nullable wrap.
        check(!cmp(BinarySelector.NULLABLE_EQ, n(Double.NaN), n(Double.NaN), NUMBER,
            NullableSide.BOTH), "NULLABLE_EQ BOTH(NaN,NaN) false");
        check(cmp(BinarySelector.NULLABLE_NE, n(Double.NaN), n(Double.NaN), NUMBER,
            NullableSide.BOTH), "NULLABLE_NE BOTH(NaN,NaN)");
        check(cmp(BinarySelector.NULLABLE_EQ, n(-0.0), n(0.0), NUMBER, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(-0.0,0.0)");
        check(!cmp(BinarySelector.NULLABLE_EQ, NULL, n(1.5), NUMBER, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,1.5) false");

        // Inner string: scalar equality incl. supplementary.
        check(cmp(BinarySelector.NULLABLE_EQ, s("a"), s("a"), STRING, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(a,a) string inner");
        check(!cmp(BinarySelector.NULLABLE_EQ, s("a"), s("b"), STRING, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(a,b) false");
        check(cmp(BinarySelector.NULLABLE_EQ, s("\uD83D\uDE00"), s("\uD83D\uDE00"), STRING,
            NullableSide.BOTH), "NULLABLE_EQ BOTH(U+1F600,U+1F600) string inner");
        check(!cmp(BinarySelector.NULLABLE_EQ, NULL, s("a"), STRING, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,a) false");

        // Inner boolean.
        check(cmp(BinarySelector.NULLABLE_EQ, b(true), b(true), BOOLEAN, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(true,true) boolean inner");
        check(!cmp(BinarySelector.NULLABLE_EQ, b(true), b(false), BOOLEAN, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(true,false) false");

        // Inner array/table/class/function: allocation/function identity.
        Object tokenA = new Object();
        Object tokenB = new Object();
        check(cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenA), ARRAY, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(same array identity)");
        check(!cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenB), ARRAY, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(distinct array identities) false");
        check(cmp(BinarySelector.NULLABLE_NE, ref(tokenA), ref(tokenB), ARRAY, NullableSide.BOTH),
            "NULLABLE_NE BOTH(distinct array identities)");
        check(!cmp(BinarySelector.NULLABLE_EQ, NULL, ref(tokenA), ARRAY, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,array) false");
        check(!cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), NULL, ARRAY, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(array,null) false");
        check(cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenA), TABLE, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(same table identity)");
        check(!cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenB), TABLE, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(distinct table identities) false");
        check(cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenA), CLASS, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(same class-instance identity)");
        check(!cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenB), CLASS, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(distinct class-instance identities) false");
        check(cmp(BinarySelector.NULLABLE_EQ,
            ref(new FunctionAllocationIdentity(7)), ref(new FunctionAllocationIdentity(7)),
            FUNC, NullableSide.BOTH), "NULLABLE_EQ BOTH(same function identity)");
        check(!cmp(BinarySelector.NULLABLE_EQ,
            ref(new FunctionAllocationIdentity(7)), ref(new FunctionAllocationIdentity(8)),
            FUNC, NullableSide.BOTH), "NULLABLE_EQ BOTH(distinct function identities) false");
        check(cmp(BinarySelector.NULLABLE_NE,
            ref(new FunctionAllocationIdentity(7)), ref(new FunctionAllocationIdentity(8)),
            FUNC, NullableSide.BOTH), "NULLABLE_NE BOTH(distinct function identities)");

        // Inner bytes: allocation identity inside the nullable wrap.
        check(cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenA), BYTES,
            NullableSide.BOTH), "NULLABLE_EQ BOTH(same bytes identity)");
        check(!cmp(BinarySelector.NULLABLE_EQ, ref(tokenA), ref(tokenB), BYTES,
            NullableSide.BOTH), "NULLABLE_EQ BOTH(distinct bytes identities) false");
        check(cmp(BinarySelector.NULLABLE_NE, ref(tokenA), ref(tokenB), BYTES,
            NullableSide.BOTH), "NULLABLE_NE BOTH(distinct bytes identities)");
        check(!cmp(BinarySelector.NULLABLE_EQ, NULL, ref(tokenA), BYTES, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,bytes) false");
        check(cmp(BinarySelector.NULLABLE_EQ, NULL, NULL, BYTES, NullableSide.BOTH),
            "NULLABLE_EQ BOTH(null,null) bytes inner");
    }

    // =========================================================================
    // 7a. BYTES matrix (ISSUE-0158 row)
    // =========================================================================

    private static void testBytesMatrix() {
        System.out.println("-- BYTES_EQ/NE (bytes allocation identity) --");

        Object bytesA = new Object();
        Object bytesB = new Object();

        check(cmp(BinarySelector.BYTES_EQ, ref(bytesA), ref(bytesA), BYTES, null),
            "BYTES_EQ(same bytes identity)");
        check(!cmp(BinarySelector.BYTES_NE, ref(bytesA), ref(bytesA), BYTES, null),
            "BYTES_NE(same bytes identity) false");
        check(!cmp(BinarySelector.BYTES_EQ, ref(bytesA), ref(bytesB), BYTES, null),
            "BYTES_EQ(distinct buffers) false");
        check(cmp(BinarySelector.BYTES_NE, ref(bytesA), ref(bytesB), BYTES, null),
            "BYTES_NE(distinct buffers)");

        // Null/missing operands follow the null rules.
        check(!cmp(BinarySelector.BYTES_EQ, NULL, ref(bytesA), BYTES, null),
            "BYTES_EQ(null,bytes) false");
        check(!cmp(BinarySelector.BYTES_EQ, ref(bytesA), NULL, BYTES, null),
            "BYTES_EQ(bytes,null) false");
        check(cmp(BinarySelector.BYTES_NE, NULL, ref(bytesA), BYTES, null),
            "BYTES_NE(null,bytes)");
        check(cmp(BinarySelector.BYTES_EQ, NULL, NULL, BYTES, null),
            "BYTES_EQ(null,null)");
        check(!cmp(BinarySelector.BYTES_NE, NULL, NULL, BYTES, null),
            "BYTES_NE(null,null) false");
        check(!cmp(BinarySelector.BYTES_EQ, MISSING, ref(bytesA), BYTES, null),
            "BYTES_EQ(missing,bytes) false");
        check(cmp(BinarySelector.BYTES_NE, ref(bytesA), MISSING, BYTES, null),
            "BYTES_NE(bytes,missing)");
        check(cmp(BinarySelector.BYTES_EQ, MISSING, MISSING, BYTES, null),
            "BYTES_EQ(missing,missing)");
    }

    private static void testNullableNullMatrix() {
        System.out.println("-- NULLABLE_NULL_EQ/NE (named side) --");

        // Side LEFT: the left operand is the nullable; the other operand
        // is the null literal.
        check(cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, INT, NullableSide.LEFT),
            "NULLABLE_NULL_EQ LEFT(null,null)");
        check(!cmp(BinarySelector.NULLABLE_NULL_NE, NULL, NULL, INT, NullableSide.LEFT),
            "NULLABLE_NULL_NE LEFT(null,null) false");
        check(!cmp(BinarySelector.NULLABLE_NULL_EQ, i(5), NULL, INT, NullableSide.LEFT),
            "NULLABLE_NULL_EQ LEFT(5,null) false");
        check(cmp(BinarySelector.NULLABLE_NULL_NE, i(5), NULL, INT, NullableSide.LEFT),
            "NULLABLE_NULL_NE LEFT(5,null)");
        check(cmp(BinarySelector.NULLABLE_NULL_EQ, MISSING, NULL, INT, NullableSide.LEFT),
            "NULLABLE_NULL_EQ LEFT(missing,null)");

        // Side RIGHT.
        check(cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, INT, NullableSide.RIGHT),
            "NULLABLE_NULL_EQ RIGHT(null,null)");
        check(!cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, i(5), INT, NullableSide.RIGHT),
            "NULLABLE_NULL_EQ RIGHT(null,5) false");
        check(cmp(BinarySelector.NULLABLE_NULL_NE, NULL, i(5), INT, NullableSide.RIGHT),
            "NULLABLE_NULL_NE RIGHT(null,5)");
        check(cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, MISSING, INT, NullableSide.RIGHT),
            "NULLABLE_NULL_EQ RIGHT(null,missing)");
        check(!cmp(BinarySelector.NULLABLE_NULL_NE, NULL, MISSING, INT, NullableSide.RIGHT),
            "NULLABLE_NULL_NE RIGHT(null,missing) false");

        // The named side decides only: a non-null named-side operand
        // yields EQ false even for out-of-contract value-vs-value pairs.
        check(!cmp(BinarySelector.NULLABLE_NULL_EQ, i(5), i(6), INT, NullableSide.LEFT),
            "NULLABLE_NULL_EQ LEFT(5,6) false");
        check(cmp(BinarySelector.NULLABLE_NULL_NE, i(5), i(6), INT, NullableSide.LEFT),
            "NULLABLE_NULL_NE LEFT(5,6)");
    }

    // =========================================================================
    // 7. REFERENCE matrix
    // =========================================================================

    private static void testReferenceMatrix() {
        System.out.println("-- REFERENCE_EQ/NE (allocation/function identity) --");

        Object arrayA = new Object();
        Object arrayB = new Object();
        Object tableA = new Object();
        Object tableB = new Object();
        Object classA = new Object();
        Object classB = new Object();

        check(cmp(BinarySelector.REFERENCE_EQ, ref(arrayA), ref(arrayA), ARRAY, null),
            "REFERENCE_EQ(same array identity)");
        check(!cmp(BinarySelector.REFERENCE_NE, ref(arrayA), ref(arrayA), ARRAY, null),
            "REFERENCE_NE(same array identity) false");
        check(!cmp(BinarySelector.REFERENCE_EQ, ref(arrayA), ref(arrayB), ARRAY, null),
            "REFERENCE_EQ(distinct arrays of equal shape) false");
        check(cmp(BinarySelector.REFERENCE_NE, ref(arrayA), ref(arrayB), ARRAY, null),
            "REFERENCE_NE(distinct arrays of equal shape)");

        check(cmp(BinarySelector.REFERENCE_EQ, ref(tableA), ref(tableA), TABLE, null),
            "REFERENCE_EQ(same table identity)");
        check(!cmp(BinarySelector.REFERENCE_EQ, ref(tableA), ref(tableB), TABLE, null),
            "REFERENCE_EQ(distinct tables) false");
        check(cmp(BinarySelector.REFERENCE_NE, ref(tableA), ref(tableB), TABLE, null),
            "REFERENCE_NE(distinct tables)");

        check(cmp(BinarySelector.REFERENCE_EQ, ref(classA), ref(classA), CLASS, null),
            "REFERENCE_EQ(same class-instance identity)");
        check(!cmp(BinarySelector.REFERENCE_EQ, ref(classA), ref(classB), CLASS, null),
            "REFERENCE_EQ(distinct class instances of the same class) false");
        check(cmp(BinarySelector.REFERENCE_NE, ref(classA), ref(classB), CLASS, null),
            "REFERENCE_NE(distinct class instances)");

        check(cmp(BinarySelector.REFERENCE_EQ,
            ref(new FunctionAllocationIdentity(7)), ref(new FunctionAllocationIdentity(7)),
            FUNC, null), "REFERENCE_EQ(same function identity)");
        check(!cmp(BinarySelector.REFERENCE_EQ,
            ref(new FunctionAllocationIdentity(7)), ref(new FunctionAllocationIdentity(8)),
            FUNC, null), "REFERENCE_EQ(distinct function identities) false");
        check(cmp(BinarySelector.REFERENCE_NE,
            ref(new FunctionAllocationIdentity(7)), ref(new FunctionAllocationIdentity(8)),
            FUNC, null), "REFERENCE_NE(distinct function identities)");

        // Null/missing operands follow the null rules.
        check(!cmp(BinarySelector.REFERENCE_EQ, NULL, ref(arrayA), ARRAY, null),
            "REFERENCE_EQ(null,array) false");
        check(!cmp(BinarySelector.REFERENCE_EQ, ref(arrayA), NULL, ARRAY, null),
            "REFERENCE_EQ(array,null) false");
        check(cmp(BinarySelector.REFERENCE_NE, NULL, ref(arrayA), ARRAY, null),
            "REFERENCE_NE(null,array)");
        check(cmp(BinarySelector.REFERENCE_EQ, NULL, NULL, ARRAY, null),
            "REFERENCE_EQ(null,null)");
        check(!cmp(BinarySelector.REFERENCE_NE, NULL, NULL, ARRAY, null),
            "REFERENCE_NE(null,null) false");
        check(!cmp(BinarySelector.REFERENCE_EQ, MISSING, ref(arrayA), ARRAY, null),
            "REFERENCE_EQ(missing,array) false");
        check(cmp(BinarySelector.REFERENCE_NE, ref(arrayA), MISSING, ARRAY, null),
            "REFERENCE_NE(array,missing)");
        check(cmp(BinarySelector.REFERENCE_EQ, MISSING, MISSING, ARRAY, null),
            "REFERENCE_EQ(missing,missing)");
    }

    // =========================================================================
    // 8. Missing≡null across every selector family (no error raised)
    // =========================================================================

    private static void testMissingNullEquivalence() {
        System.out.println("-- B-D1: missing ≡ null at both operand positions --");

        // The jvm-arr-cmp-past-end-parity shapes: past-end reads at
        // comparison operands yield missing and compute the comparison
        // with no error — missing === v false, missing !== v true,
        // missing === missing true, at both operand positions, for every
        // selector family.
        check(cmp(BinarySelector.INT32_EQ, MISSING, MISSING), "INT32 missing===missing");
        check(!cmp(BinarySelector.INT32_EQ, MISSING, i(5)), "INT32 missing===5 false");
        check(!cmp(BinarySelector.INT32_EQ, i(5), MISSING), "INT32 5===missing false");
        check(cmp(BinarySelector.INT32_NE, MISSING, i(5)), "INT32 missing!==5");
        check(cmp(BinarySelector.INT32_NE, i(5), MISSING), "INT32 5!==missing");
        check(!cmp(BinarySelector.INT32_LT, MISSING, i(5)), "INT32 missing<5 false");

        check(cmp(BinarySelector.NUMBER_EQ, MISSING, MISSING), "NUMBER missing===missing");
        check(!cmp(BinarySelector.NUMBER_EQ, MISSING, n(1.5)), "NUMBER missing===1.5 false");
        check(!cmp(BinarySelector.NUMBER_EQ, n(1.5), MISSING), "NUMBER 1.5===missing false");
        check(cmp(BinarySelector.NUMBER_NE, n(1.5), MISSING), "NUMBER 1.5!==missing");
        check(!cmp(BinarySelector.NUMBER_GE, MISSING, n(1.5)), "NUMBER missing>=1.5 false");

        check(cmp(BinarySelector.STRING_EQ, MISSING, MISSING), "STRING missing===missing");
        check(!cmp(BinarySelector.STRING_EQ, MISSING, s("a")), "STRING missing===a false");
        check(!cmp(BinarySelector.STRING_EQ, s("a"), MISSING), "STRING a===missing false");
        check(cmp(BinarySelector.STRING_NE, s("a"), MISSING), "STRING a!==missing");
        check(!cmp(BinarySelector.STRING_LT, s("a"), MISSING), "STRING a<missing false");

        check(cmp(BinarySelector.BOOLEAN_EQ, MISSING, MISSING), "BOOLEAN missing===missing");
        check(!cmp(BinarySelector.BOOLEAN_EQ, MISSING, b(true)),
            "BOOLEAN missing===true false");
        check(cmp(BinarySelector.BOOLEAN_NE, b(false), MISSING), "BOOLEAN false!==missing");

        check(cmp(BinarySelector.NULL_EQ, MISSING, MISSING), "NULL missing===missing");
        check(cmp(BinarySelector.NULL_EQ, NULL, MISSING), "NULL null===missing");
        check(!cmp(BinarySelector.NULL_NE, MISSING, NULL), "NULL missing!==null false");

        check(cmp(BinarySelector.NULLABLE_EQ, MISSING, MISSING, INT, NullableSide.BOTH),
            "NULLABLE missing===missing");
        check(!cmp(BinarySelector.NULLABLE_EQ, MISSING, i(5), INT, NullableSide.BOTH),
            "NULLABLE missing===5 false");
        check(!cmp(BinarySelector.NULLABLE_EQ, i(5), MISSING, INT, NullableSide.BOTH),
            "NULLABLE 5===missing false");
        check(cmp(BinarySelector.NULLABLE_NE, i(5), MISSING, INT, NullableSide.BOTH),
            "NULLABLE 5!==missing");

        check(cmp(BinarySelector.NULLABLE_NULL_EQ, MISSING, NULL, INT, NullableSide.LEFT),
            "NULLABLE_NULL missing===null");
        check(cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, MISSING, INT, NullableSide.RIGHT),
            "NULLABLE_NULL null===missing");

        check(cmp(BinarySelector.REFERENCE_EQ, MISSING, MISSING, ARRAY, null),
            "REFERENCE missing===missing");
        check(!cmp(BinarySelector.REFERENCE_EQ, MISSING, ref(new Object()), ARRAY, null),
            "REFERENCE missing===ref false");
        check(cmp(BinarySelector.REFERENCE_NE, ref(new Object()), MISSING, ARRAY, null),
            "REFERENCE ref!==missing");
    }

    // =========================================================================
    // 9. NO_DEAL_FAILURE sweep over every selector with null/missing operands
    // =========================================================================

    private static void testNoDealFailureSweep() {
        System.out.println("-- NO_DEAL_FAILURE: every selector with null/missing operands --");

        List<BinarySelector> comparisonSelectors = new ArrayList<>();
        for (BinarySelector selector : BinarySelector.values()) {
            if (isComparisonSelector(selector)) {
                comparisonSelectors.add(selector);
            }
        }
        check(comparisonSelectors.size() == 30,
            "the closed set carries exactly 30 comparison selectors; got "
                + comparisonSelectors.size());
        check(BinarySelector.values().length == 42,
            "the closed set carries exactly 42 binary selectors; got "
                + BinarySelector.values().length);

        int swept = 0;
        for (BinarySelector selector : comparisonSelectors) {
            RuntimeDescriptor inner = innerFor(selector);
            NullableSide side = sideFor(selector);
            for (ComparisonOperandView[] pair : List.of(
                    new ComparisonOperandView[] {NULL, NULL},
                    new ComparisonOperandView[] {NULL, MISSING},
                    new ComparisonOperandView[] {MISSING, MISSING},
                    new ComparisonOperandView[] {MISSING, NULL})) {
                boolean result = ComparisonExecutor.compare(selector, pair[0], pair[1],
                    inner, side);
                check(result == true || result == false,
                    selector.name() + "(" + pair[0].getClass().getSimpleName() + ","
                        + pair[1].getClass().getSimpleName() + ") returns a boolean");
                swept++;
            }
        }
        check(swept == 30 * 4, "the null/missing sweep covered every comparison selector; got "
            + swept);
    }

    private static boolean isComparisonSelector(BinarySelector selector) {
        return switch (selector) {
            case INT32_EQ, INT32_NE, INT32_LT, INT32_LE, INT32_GT, INT32_GE,
                 NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT, NUMBER_GE,
                 STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE,
                 BOOLEAN_EQ, BOOLEAN_NE, NULL_EQ, NULL_NE, NULLABLE_EQ, NULLABLE_NE,
                 NULLABLE_NULL_EQ, NULLABLE_NULL_NE, REFERENCE_EQ, REFERENCE_NE,
                 BYTES_EQ, BYTES_NE -> true;
            case INT32_ADD, INT32_SUB, INT32_MUL, INT32_DIV_TRUNC, INT32_MOD_TRUNC,
                 INT32_POW, NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE,
                 NUMBER_MOD_FLOOR, NUMBER_POW_IEEE -> false;
        };
    }

    private static RuntimeDescriptor innerFor(BinarySelector selector) {
        if (selector.name().startsWith("NULLABLE")) {
            return INT;
        }
        if (selector == BinarySelector.REFERENCE_EQ || selector == BinarySelector.REFERENCE_NE) {
            return ARRAY;
        }
        if (selector == BinarySelector.BYTES_EQ || selector == BinarySelector.BYTES_NE) {
            return BYTES;
        }
        return null;
    }

    private static NullableSide sideFor(BinarySelector selector) {
        if (selector == BinarySelector.NULLABLE_EQ || selector == BinarySelector.NULLABLE_NE) {
            return NullableSide.BOTH;
        }
        if (selector == BinarySelector.NULLABLE_NULL_EQ
                || selector == BinarySelector.NULLABLE_NULL_NE) {
            return NullableSide.LEFT;
        }
        return null;
    }

    // =========================================================================
    // 10. Fail-closed defects
    // =========================================================================

    private static void testDefectCases() {
        System.out.println("-- Producer defects (fail closed) --");

        // Arithmetic selectors are the signed-int32/containers epics'
        // domain, never the comparison executor's.
        for (BinarySelector arithmetic : List.of(BinarySelector.INT32_ADD,
                BinarySelector.INT32_SUB, BinarySelector.INT32_MUL, BinarySelector.INT32_DIV_TRUNC,
                BinarySelector.INT32_MOD_TRUNC, BinarySelector.INT32_POW,
                BinarySelector.NUMBER_ADD, BinarySelector.NUMBER_SUB, BinarySelector.NUMBER_MUL,
                BinarySelector.NUMBER_DIV_IEEE, BinarySelector.NUMBER_MOD_FLOOR,
                BinarySelector.NUMBER_POW_IEEE)) {
            expectDefect(() -> cmp(arithmetic, i(1), i(2)),
                "arithmetic selector " + arithmetic.name());
        }

        // Wrong operand families.
        expectDefect(() -> cmp(BinarySelector.INT32_EQ, n(1.0), n(2.0)),
            "INT32_EQ with Number operands");
        expectDefect(() -> cmp(BinarySelector.INT32_EQ, s("a"), s("b")),
            "INT32_EQ with String operands");
        expectDefect(() -> cmp(BinarySelector.NUMBER_LT, i(1), i(2)),
            "NUMBER_LT with Int operands");
        expectDefect(() -> cmp(BinarySelector.STRING_EQ, n(1.0), n(1.0)),
            "STRING_EQ with Number operands");
        expectDefect(() -> cmp(BinarySelector.STRING_GT, i(1), i(2)),
            "STRING_GT with Int operands");
        expectDefect(() -> cmp(BinarySelector.BOOLEAN_EQ, i(1), i(1)),
            "BOOLEAN_EQ with Int operands");
        expectDefect(() -> cmp(BinarySelector.NULL_EQ, i(1), i(1)),
            "NULL_EQ with value operands");
        expectDefect(() -> cmp(BinarySelector.NULL_NE, b(true), b(false)),
            "NULL_NE with value operands");

        // NULLABLE payload shapes.
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, i(1), i(1), null, NullableSide.BOTH),
            "NULLABLE_EQ with a null inner descriptor");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, i(1), i(1), INT, null),
            "NULLABLE_EQ with a null side");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, NULL, NULL,
            RuntimeDescriptor.Null.INSTANCE, NullableSide.BOTH),
            "NULLABLE_EQ with a null-kind inner descriptor");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, i(1), i(1),
            new RuntimeDescriptor.Nullable(INT), NullableSide.BOTH),
            "NULLABLE_EQ with a nullable inner descriptor");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, n(1.0), n(2.0), INT,
            NullableSide.BOTH), "NULLABLE_EQ int inner with Number operands");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, i(1), i(2), STRING,
            NullableSide.BOTH), "NULLABLE_EQ string inner with Int operands");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_EQ, ref(new Object()), i(2), ARRAY,
            NullableSide.BOTH), "NULLABLE_EQ array inner with a mixed operand pair");

        // NULLABLE_NULL payload shapes.
        expectDefect(() -> cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, INT,
            NullableSide.BOTH), "NULLABLE_NULL_EQ with side BOTH");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, INT, null),
            "NULLABLE_NULL_EQ with a null side");
        expectDefect(() -> cmp(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, null,
            NullableSide.LEFT), "NULLABLE_NULL_EQ with a null inner descriptor");

        // REFERENCE payload shapes.
        expectDefect(() -> cmp(BinarySelector.REFERENCE_EQ, ref(new Object()),
            ref(new Object()), null, null), "REFERENCE_EQ with a null descriptor");
        expectDefect(() -> cmp(BinarySelector.REFERENCE_EQ, ref(new Object()),
            ref(new Object()), INT, null), "REFERENCE_EQ with an int descriptor");
        expectDefect(() -> cmp(BinarySelector.REFERENCE_EQ, ref(new Object()),
            ref(new Object()), BOOLEAN, null), "REFERENCE_EQ with a boolean descriptor");
        expectDefect(() -> cmp(BinarySelector.REFERENCE_EQ, ref(new Object()),
            ref(new Object()), new RuntimeDescriptor.Nullable(INT), null),
            "REFERENCE_EQ with a nullable descriptor");
        expectDefect(() -> cmp(BinarySelector.REFERENCE_EQ, i(1), i(1), ARRAY, null),
            "REFERENCE_EQ with Int operands");
        expectDefect(() -> cmp(BinarySelector.REFERENCE_NE, s("a"), s("a"), ARRAY, null),
            "REFERENCE_NE with String operands");

        // BYTES payload shapes.
        expectDefect(() -> cmp(BinarySelector.BYTES_EQ, ref(new Object()),
            ref(new Object()), null, null), "BYTES_EQ with a null descriptor");
        expectDefect(() -> cmp(BinarySelector.BYTES_EQ, ref(new Object()),
            ref(new Object()), INT, null), "BYTES_EQ with an int descriptor");
        expectDefect(() -> cmp(BinarySelector.BYTES_EQ, ref(new Object()),
            ref(new Object()), ARRAY, null), "BYTES_EQ with an array descriptor");
        expectDefect(() -> cmp(BinarySelector.BYTES_EQ, i(1), i(1), BYTES, null),
            "BYTES_EQ with Int operands");
        expectDefect(() -> cmp(BinarySelector.BYTES_NE, s("a"), s("a"), BYTES, null),
            "BYTES_NE with String operands");

        // Null arguments.
        expectNpe(() -> ComparisonExecutor.compare(null, i(1), i(1), null, null),
            "a null selector");
        expectNpe(() -> ComparisonExecutor.compare(BinarySelector.INT32_EQ, null, i(1),
            null, null), "a null left operand");
        expectNpe(() -> ComparisonExecutor.compare(BinarySelector.INT32_EQ, i(1), null,
            null, null), "a null right operand");
    }

    // =========================================================================
    // 11. Component discipline: no boundary execution, closed dependency direction
    // =========================================================================

    private static void testComponentDiscipline() {
        System.out.println("-- Component discipline (B-D5, dependency direction) --");

        for (String file : List.of("deal/semantic/ir/ComparisonExecutor.java",
                "deal/semantic/ir/ComparisonOperandView.java")) {
            String source = readSource(file);
            for (String line : source.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                String imported = trimmed.substring("import ".length())
                    .replace(";", "").trim();
                for (String forbidden : List.of("deal.diagnostics", "deal.types", "deal.ast",
                        "deal.checker", "deal.codegen", "deal.module", "deal.parser",
                        "deal.lexer", "deal.ir.")) {
                    check(!imported.equals(forbidden)
                            && !imported.startsWith(forbidden + "."),
                        file + " keeps the closed dependency direction (no " + forbidden
                            + " import; offending: " + imported + ")");
                }
            }
        }

        String executor = readSource("deal/semantic/ir/ComparisonExecutor.java");
        for (String boundaryType : List.of("BoundaryExecutor", "BoundaryPayload",
                "BoundaryKind", "BoundaryOutcome", "BoundaryFailure", "BoundaryContext",
                "BoundaryValueView")) {
            check(!executor.contains(boundaryType),
                "ComparisonExecutor executes no boundary op (no " + boundaryType + " reference)");
        }
    }

    private static String readSource(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            fail("cannot read " + path + ": " + e);
            return "";
        }
    }

    // =========================================================================
    // 12. Determinism
    // =========================================================================

    private static void testDeterminism() {
        System.out.println("-- Determinism --");

        List<Boolean> first = sampleResults();
        List<Boolean> second = sampleResults();
        check(first.equals(second), "repeated execution returns identical results");
        check(ComparisonExecutor.compare(BinarySelector.STRING_LT, s("a\uE000"),
                s("a\uD83D\uDE00"), null, null)
                == ComparisonExecutor.compare(BinarySelector.STRING_LT, s("a\uE000"),
                    s("a\uD83D\uDE00"), null, null),
            "the scalar-order seed is stable across calls");
    }

    private static List<Boolean> sampleResults() {
        Object token = new Object();
        return List.of(
            cmp(BinarySelector.INT32_LT, i(-1), i(1)),
            cmp(BinarySelector.NUMBER_EQ, n(Double.NaN), n(Double.NaN)),
            cmp(BinarySelector.NUMBER_LE, n(-0.0), n(0.0)),
            cmp(BinarySelector.STRING_LT, s("\uE000"), s("\uD83D\uDE00")),
            cmp(BinarySelector.BOOLEAN_EQ, b(false), b(false)),
            cmp(BinarySelector.NULL_NE, NULL, NULL),
            cmp(BinarySelector.NULLABLE_EQ, NULL, i(5), INT, NullableSide.BOTH),
            cmp(BinarySelector.NULLABLE_EQ, i(5), i(5), INT, NullableSide.LEFT),
            cmp(BinarySelector.NULLABLE_NULL_EQ, MISSING, NULL, INT, NullableSide.LEFT),
            cmp(BinarySelector.REFERENCE_EQ, ref(token), ref(token), ARRAY, null),
            cmp(BinarySelector.REFERENCE_NE, ref(token), ref(new Object()), ARRAY, null),
            cmp(BinarySelector.BYTES_EQ, ref(token), ref(token), BYTES, null),
            cmp(BinarySelector.BYTES_NE, ref(token), ref(new Object()), BYTES, null),
            cmp(BinarySelector.NULLABLE_EQ, ref(token), ref(token), BYTES,
                NullableSide.BOTH),
            cmp(BinarySelector.INT32_EQ, MISSING, MISSING)
        );
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Comparison Operand View and Executor Test "
            + "(ISSUE-0406, ISSUE-0234 B-D1/B-D2/B-D4) ===\n");

        testViewShapes();
        testInt32Matrix();
        testNumberMatrix();
        testStringMatrix();
        testBooleanAndNullMatrices();
        testNullableMatrix();
        testNullableNullMatrix();
        testReferenceMatrix();
        testBytesMatrix();
        testMissingNullEquivalence();
        testNoDealFailureSweep();
        testDefectCases();
        testComponentDiscipline();
        testDeterminism();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
