package deal.test;

import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.ComparisonExecutor;
import deal.semantic.ir.ComparisonOperandView;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.UnicodeScalars;

import java.util.List;

public class ComparisonExecutorTest {
    private static int passed;
    private static int failed;
    private static final ComparisonOperandView NULL = ComparisonOperandView.Null.INSTANCE;
    private static final ComparisonOperandView MISSING = ComparisonOperandView.Missing.INSTANCE;
    private static final RuntimeDescriptor INT = RuntimeDescriptor.Int.INSTANCE;
    private static final RuntimeDescriptor NUMBER = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor STRING = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor BOOLEAN = RuntimeDescriptor.Boolean.INSTANCE;
    private static final RuntimeDescriptor TABLE = RuntimeDescriptor.Table.INSTANCE;
    private static final RuntimeDescriptor ARRAY = new RuntimeDescriptor.Array(INT);
    private static final RuntimeDescriptor CLASS = new RuntimeDescriptor.Class(new deal.semantic.ir.ClassId("src/app", "User"));
    private static final RuntimeDescriptor FUNC = new RuntimeDescriptor.Func(List.of(INT), NUMBER);
    private static final RuntimeDescriptor BYTES = RuntimeDescriptor.Bytes.INSTANCE;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static ComparisonOperandView i(int value) { return new ComparisonOperandView.Int(value); }
    private static ComparisonOperandView n(double value) { return new ComparisonOperandView.Number(value); }
    private static ComparisonOperandView s(String value) { return new ComparisonOperandView.String(new UnicodeScalars.Valid(value)); }
    private static ComparisonOperandView b(boolean value) { return new ComparisonOperandView.Boolean(value); }
    private static ComparisonOperandView.Ref ref(Object token) { return new ComparisonOperandView.Ref(token); }

    private record ComparisonRow(BinarySelector selector, ComparisonOperandView left,
                                 ComparisonOperandView right, RuntimeDescriptor inner,
                                 NullableSide side, boolean expected) {}

    private static void exact(ComparisonRow row) {
        check(ComparisonExecutor.compare(row.selector(), row.left(), row.right(), row.inner(), row.side()) == row.expected(),
            row.selector() + " " + row.left() + "/" + row.right() + " " + row.inner() + " " + row.side()
                + " expected " + row.expected());
    }

    private static void relation(String family, ComparisonOperandView left, ComparisonOperandView right,
                                 boolean equal, boolean less, boolean greater) {
        boolean[] expected = {equal, !equal, less, less || equal, greater, greater || equal};
        String[] names = {"EQ", "NE", "LT", "LE", "GT", "GE"};
        for (int index = 0; index < names.length; index++) {
            exact(new ComparisonRow(BinarySelector.valueOf(family + "_" + names[index]), left, right,
                null, null, expected[index]));
        }
    }

    private static void testScalarAndIdentityMatrices() {
        record Ordered(String family, ComparisonOperandView left, ComparisonOperandView right,
                       boolean equal, boolean less, boolean greater) {}
        List<Ordered> rows = List.of(
            new Ordered("INT32", i(1), i(1), true, false, false),
            new Ordered("INT32", i(1), i(2), false, true, false),
            new Ordered("INT32", i(-5), i(3), false, true, false),
            new Ordered("INT32", i(-1), i(1), false, true, false),
            new Ordered("INT32", i(3), i(-5), false, false, true),
            new Ordered("INT32", i(-5), i(-5), true, false, false),
            new Ordered("INT32", i(3), i(3), true, false, false),
            new Ordered("INT32", i(Integer.MIN_VALUE), i(-1), false, true, false),
            new Ordered("INT32", i(Integer.MAX_VALUE), i(Integer.MIN_VALUE), false, false, true),
            new Ordered("INT32", i(Integer.MIN_VALUE), i(Integer.MIN_VALUE), true, false, false),
            new Ordered("INT32", i(Integer.MAX_VALUE), i(Integer.MAX_VALUE), true, false, false),
            new Ordered("NUMBER", n(-0.0), n(0.0), true, false, false),
            new Ordered("NUMBER", n(1.5), n(1.5), true, false, false),
            new Ordered("NUMBER", n(1.5), n(2.5), false, true, false),
            new Ordered("NUMBER", n(2.5), n(1.5), false, false, true),
            new Ordered("NUMBER", n(2.5), n(2.5), true, false, false),
            new Ordered("NUMBER", n(Double.NEGATIVE_INFINITY), n(Double.POSITIVE_INFINITY), false, true, false),
            new Ordered("NUMBER", n(Double.POSITIVE_INFINITY), n(Double.POSITIVE_INFINITY), true, false, false),
            new Ordered("STRING", s("abc"), s("abc"), true, false, false),
            new Ordered("STRING", s("abc"), s("abd"), false, true, false),
            new Ordered("STRING", s("abd"), s("abc"), false, false, true),
            new Ordered("STRING", s("abd"), s("abd"), true, false, false),
            new Ordered("STRING", s("ab"), s("abc"), false, true, false),
            new Ordered("STRING", s("abc"), s("ab"), false, false, true),
            new Ordered("STRING", s("\uE000"), s("\uD83D\uDE00"), false, true, false),
            new Ordered("STRING", s("\uD83D\uDE00"), s("\uE000"), false, false, true),
            new Ordered("STRING", s("\uD83D\uDE00"), s("\uD83D\uDE00"), true, false, false),
            new Ordered("STRING", s("a\uE000"), s("a\uD83D\uDE00"), false, true, false),
            new Ordered("STRING", s("\uFFFF"), s("\uD83D\uDE00"), false, true, false),
            new Ordered("STRING", s("\uD83D\uDE00"), s("a"), false, false, true));
        rows.forEach(row -> relation(row.family(), row.left(), row.right(), row.equal(), row.less(), row.greater()));
        check("\uE000".compareTo("\uD83D\uDE00") > 0, "Unicode seed diverges from UTF-16 ordering");
        for (ComparisonOperandView[] pair : List.of(new ComparisonOperandView[]{n(Double.NaN), n(Double.NaN)},
                new ComparisonOperandView[]{n(Double.NaN), n(1.0)}, new ComparisonOperandView[]{n(1.0), n(Double.NaN)})) {
            for (String name : List.of("EQ", "NE", "LT", "LE", "GT", "GE")) {
                exact(new ComparisonRow(BinarySelector.valueOf("NUMBER_" + name), pair[0], pair[1], null, null, name.equals("NE")));
            }
        }
        for (boolean left : List.of(false, true)) {
            for (boolean right : List.of(false, true)) {
                exact(new ComparisonRow(BinarySelector.BOOLEAN_EQ, b(left), b(right), null, null, left == right));
                exact(new ComparisonRow(BinarySelector.BOOLEAN_NE, b(left), b(right), null, null, left != right));
            }
        }
        Object a = new Object(), other = new Object();
        for (RuntimeDescriptor descriptor : List.of(ARRAY, TABLE, CLASS, FUNC, BYTES)) {
            ComparisonOperandView sameA = descriptor == FUNC ? ref(new FunctionAllocationIdentity(7)) : ref(a);
            ComparisonOperandView sameB = descriptor == FUNC ? ref(new FunctionAllocationIdentity(7)) : ref(a);
            ComparisonOperandView different = descriptor == FUNC ? ref(new FunctionAllocationIdentity(8)) : ref(other);
            String family = descriptor == BYTES ? "BYTES" : "REFERENCE";
            for (boolean equal : List.of(true, false)) {
                ComparisonOperandView right = equal ? sameB : different;
                exact(new ComparisonRow(BinarySelector.valueOf(family + "_EQ"), sameA, right, descriptor, null, equal));
                exact(new ComparisonRow(BinarySelector.valueOf(family + "_NE"), sameA, right, descriptor, null, !equal));
                for (NullableSide side : NullableSide.values()) {
                    exact(new ComparisonRow(BinarySelector.NULLABLE_EQ, sameA, right, descriptor, side, equal));
                    exact(new ComparisonRow(BinarySelector.NULLABLE_NE, sameA, right, descriptor, side, !equal));
                }
            }
        }
        record NullableRow(RuntimeDescriptor inner, ComparisonOperandView left, ComparisonOperandView right, boolean equal) {}
        for (NullableRow row : List.of(new NullableRow(INT, i(5), i(5), true), new NullableRow(INT, i(5), i(6), false),
                new NullableRow(NUMBER, n(Double.NaN), n(Double.NaN), false), new NullableRow(NUMBER, n(-0.0), n(0.0), true),
                new NullableRow(STRING, s("a"), s("a"), true), new NullableRow(STRING, s("a"), s("b"), false),
                new NullableRow(STRING, s("\uD83D\uDE00"), s("\uD83D\uDE00"), true),
                new NullableRow(BOOLEAN, b(true), b(true), true), new NullableRow(BOOLEAN, b(true), b(false), false))) {
            for (NullableSide side : NullableSide.values()) {
                exact(new ComparisonRow(BinarySelector.NULLABLE_EQ, row.left(), row.right(), row.inner(), side, row.equal()));
                exact(new ComparisonRow(BinarySelector.NULLABLE_NE, row.left(), row.right(), row.inner(), side, !row.equal()));
            }
        }
        exact(new ComparisonRow(BinarySelector.NULLABLE_NULL_EQ, i(5), i(6), INT, NullableSide.LEFT, false));
        exact(new ComparisonRow(BinarySelector.NULLABLE_NULL_NE, i(5), i(6), INT, NullableSide.LEFT, true));
    }

    private static ComparisonOperandView valueFor(RuntimeDescriptor descriptor) {
        if (descriptor == INT) { return i(5); }
        if (descriptor == NUMBER) { return n(1.5); }
        if (descriptor == STRING) { return s("a"); }
        if (descriptor == BOOLEAN) { return b(true); }
        return ref(descriptor == FUNC ? new FunctionAllocationIdentity(7) : new Object());
    }

    private static boolean isComparisonSelector(BinarySelector selector) {
        return switch (selector) {
            case INT32_EQ, INT32_NE, INT32_LT, INT32_LE, INT32_GT, INT32_GE,
                 NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT, NUMBER_GE,
                 STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE,
                 BOOLEAN_EQ, BOOLEAN_NE, NULL_EQ, NULL_NE, NULLABLE_EQ, NULLABLE_NE,
                 NULLABLE_NULL_EQ, NULLABLE_NULL_NE, REFERENCE_EQ, REFERENCE_NE, BYTES_EQ, BYTES_NE -> true;
            case INT32_ADD, INT32_SUB, INT32_MUL, INT32_DIV_TRUNC, INT32_MOD_TRUNC, INT32_POW,
                 NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE, NUMBER_MOD_FLOOR, NUMBER_POW_IEEE -> false;
        };
    }

    private static void testExactNullMissingMatrix() {
        for (BinarySelector selector : BinarySelector.values()) {
            if (!isComparisonSelector(selector)) { continue; }
            String name = selector.name();
            boolean nullable = name.startsWith("NULLABLE"), namedSide = name.startsWith("NULLABLE_NULL");
            List<RuntimeDescriptor> descriptors = nullable ? List.of(INT, NUMBER, STRING, BOOLEAN, ARRAY, TABLE, CLASS, FUNC, BYTES)
                : name.startsWith("REFERENCE") ? List.of(ARRAY, TABLE, CLASS, FUNC)
                : List.of(name.startsWith("INT32") ? INT : name.startsWith("NUMBER") ? NUMBER
                    : name.startsWith("STRING") ? STRING : name.startsWith("BOOLEAN") ? BOOLEAN
                    : name.startsWith("BYTES") ? BYTES : RuntimeDescriptor.Null.INSTANCE);
            for (RuntimeDescriptor descriptor : descriptors) {
                RuntimeDescriptor inner = nullable || name.startsWith("REFERENCE") || name.startsWith("BYTES") ? descriptor : null;
                List<NullableSide> sides = namedSide ? List.of(NullableSide.LEFT, NullableSide.RIGHT)
                    : nullable ? List.of(NullableSide.LEFT, NullableSide.RIGHT, NullableSide.BOTH) : java.util.Collections.singletonList(null);
                for (NullableSide side : sides) {
                    for (ComparisonOperandView left : List.of(NULL, MISSING)) {
                        for (ComparisonOperandView right : List.of(NULL, MISSING)) {
                            exact(new ComparisonRow(selector, left, right, inner, side, name.endsWith("_EQ")));
                        }
                        if (name.startsWith("NULL_")) { continue; }
                        ComparisonOperandView value = valueFor(descriptor);
                        for (boolean swap : List.of(false, true)) {
                            boolean expected = name.endsWith("_NE");
                            if (namedSide) {
                                boolean namedNull = side == NullableSide.LEFT ? !swap : swap;
                                expected = name.endsWith("_EQ") == namedNull;
                            }
                            exact(new ComparisonRow(selector, swap ? value : left, swap ? left : value, inner, side, expected));
                        }
                    }
                }
            }
        }
    }

    private static void expectException(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); check(false, "expected " + type.getName()); }
        catch (Throwable failure) { check(type.isInstance(failure), "expected " + type.getName() + ", got " + failure); }
    }

    private static void testViewShapes() {
        check(NULL instanceof ComparisonOperandView.Null && MISSING instanceof ComparisonOperandView.Missing, "null/missing variants");
        check(b(true).equals(b(true)) && i(7).equals(i(7)) && n(1.5).equals(n(1.5)), "scalar views compare by value");
        check(s("a\uD83D\uDE00b").equals(new ComparisonOperandView.String(new UnicodeScalars.Valid("a\uD83D\uDE00b"))), "validated scalar carrier");
        Object identity = new Object(); check(ref(identity).allocationIdentity() == identity, "reference preserves allocation token");
        expectException(IllegalArgumentException.class, () -> new UnicodeScalars.Valid("\uD800"));
        expectException(IllegalArgumentException.class, () -> new UnicodeScalars.Valid("\uDC00"));
        expectException(NullPointerException.class, () -> new ComparisonOperandView.String(null));
        expectException(NullPointerException.class, () -> new ComparisonOperandView.Ref(null));
    }

    private static void testDefects() {
        for (BinarySelector selector : BinarySelector.values()) {
            if (!isComparisonSelector(selector)) {
                expectException(ComparisonExecutor.Defect.class, () -> ComparisonExecutor.compare(selector, i(1), i(2), null, null));
            }
        }
        List<ComparisonRow> rows = List.of(
            new ComparisonRow(BinarySelector.INT32_EQ, n(1), n(2), null, null, false),
            new ComparisonRow(BinarySelector.INT32_EQ, s("a"), s("b"), null, null, false),
            new ComparisonRow(BinarySelector.NUMBER_LT, i(1), i(2), null, null, false),
            new ComparisonRow(BinarySelector.STRING_EQ, n(1), n(1), null, null, false),
            new ComparisonRow(BinarySelector.STRING_GT, i(1), i(2), null, null, false),
            new ComparisonRow(BinarySelector.BOOLEAN_EQ, i(1), i(1), null, null, false),
            new ComparisonRow(BinarySelector.NULL_EQ, i(1), i(1), null, null, false),
            new ComparisonRow(BinarySelector.NULL_NE, b(true), b(false), null, null, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, i(1), i(1), null, NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, i(1), i(1), INT, null, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, NULL, NULL, RuntimeDescriptor.Null.INSTANCE, NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, i(1), i(1), new RuntimeDescriptor.Nullable(INT), NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, n(1), n(2), INT, NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, i(1), i(2), STRING, NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_EQ, ref(new Object()), i(2), ARRAY, NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, INT, NullableSide.BOTH, false),
            new ComparisonRow(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, INT, null, false),
            new ComparisonRow(BinarySelector.NULLABLE_NULL_EQ, NULL, NULL, null, NullableSide.LEFT, false),
            new ComparisonRow(BinarySelector.REFERENCE_EQ, ref(new Object()), ref(new Object()), null, null, false),
            new ComparisonRow(BinarySelector.REFERENCE_EQ, ref(new Object()), ref(new Object()), INT, null, false),
            new ComparisonRow(BinarySelector.REFERENCE_EQ, ref(new Object()), ref(new Object()), BOOLEAN, null, false),
            new ComparisonRow(BinarySelector.REFERENCE_EQ, ref(new Object()), ref(new Object()), new RuntimeDescriptor.Nullable(INT), null, false),
            new ComparisonRow(BinarySelector.REFERENCE_EQ, i(1), i(1), ARRAY, null, false),
            new ComparisonRow(BinarySelector.REFERENCE_NE, s("a"), s("a"), ARRAY, null, false),
            new ComparisonRow(BinarySelector.BYTES_EQ, ref(new Object()), ref(new Object()), null, null, false),
            new ComparisonRow(BinarySelector.BYTES_EQ, ref(new Object()), ref(new Object()), INT, null, false),
            new ComparisonRow(BinarySelector.BYTES_EQ, ref(new Object()), ref(new Object()), ARRAY, null, false),
            new ComparisonRow(BinarySelector.BYTES_EQ, i(1), i(1), BYTES, null, false),
            new ComparisonRow(BinarySelector.BYTES_NE, s("a"), s("a"), BYTES, null, false));
        rows.forEach(row -> expectException(ComparisonExecutor.Defect.class,
            () -> ComparisonExecutor.compare(row.selector(), row.left(), row.right(), row.inner(), row.side())));
        expectException(NullPointerException.class, () -> ComparisonExecutor.compare(null, i(1), i(1), null, null));
        expectException(NullPointerException.class, () -> ComparisonExecutor.compare(BinarySelector.INT32_EQ, null, i(1), null, null));
        expectException(NullPointerException.class, () -> ComparisonExecutor.compare(BinarySelector.INT32_EQ, i(1), null, null, null));
    }

    public static void main(String[] args) {
        testViewShapes();
        testScalarAndIdentityMatrices();
        testExactNullMissingMatrix();
        testDefects();
        testScalarAndIdentityMatrices();
        testExactNullMissingMatrix();
        System.out.println("ComparisonExecutorTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) { System.exit(1); }
    }
}
