package deal.test;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.RuntimeCheckFailure;
import deal.descriptors.RuntimeSourceLocation;
import deal.descriptors.RuntimeTypeMatcher;
import deal.descriptors.RuntimeValueModel;

import java.util.Arrays;

public class RuntimeTypeMatcherTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // Conformant value doubles
    // =========================================================================

    /** The backend null-representation double for this corpus. */
    static final Object NULL_VALUE = new Object();

    /** A class-tagged double value carrying real tag text. */
    static final class ClassValue {
        final String tag;
        ClassValue(String tag) { this.tag = tag; }
    }

    /** A function-wrapper double carrying a real descriptor string. */
    static final class FnValue {
        final String descriptor;
        FnValue(String descriptor) { this.descriptor = descriptor; }
    }

    /** An array double exposing ordered elements (honest element access). */
    static final class ArrayValue {
        final Object[] elements;
        ArrayValue(Object... elements) { this.elements = elements; }
    }

    /** A table double. */
    static final class TableValue { }

    /**
     * The conformant full-corpus seam double: every accessor is exactly
     * one of the table's named backend-representation predicates,
     * honestly backed by the value shapes above.
     */
    static class TestModel implements RuntimeValueModel {
        @Override public boolean isNull(Object value) {
            return value == null || value == NULL_VALUE;
        }
        @Override public boolean isBoolean(Object value) { return value instanceof Boolean; }
        @Override public boolean isString(Object value) { return value instanceof String; }
        @Override public String stringPayload(Object value) { return (String) value; }
        @Override public boolean isNumber(Object value) { return value instanceof Double; }
        @Override public double numberPayload(Object value) { return (Double) value; }
        @Override public boolean isTable(Object value) { return value instanceof TableValue; }
        @Override public boolean isArray(Object value) { return value instanceof ArrayValue; }
        @Override public int arrayLength(Object value) { return ((ArrayValue) value).elements.length; }
        @Override public Object arrayElementAt(Object value, int index) {
            return ((ArrayValue) value).elements[index];
        }
        @Override public String classIdentityText(Object value) {
            return value instanceof ClassValue c ? c.tag : null;
        }
        @Override public String functionDescriptorText(Object value) {
            return value instanceof FnValue f ? f.descriptor : null;
        }
        private String kindOf(Object value) {
            if (value == null || value == NULL_VALUE) return "null";
            if (value instanceof Boolean) return "boolean";
            if (value instanceof Double) return "number";
            if (value instanceof String) return "string";
            if (value instanceof ClassValue c) return c.tag;
            if (value instanceof FnValue) return "function";
            if (value instanceof ArrayValue) return "array";
            if (value instanceof BytesValue) return "bytes";
            if (value instanceof TableValue) return "table";
            return "unknown";
        }
    }

    /**
     * The Lua value mapping: every produced carrier is physically a table,
     * so {@code type(v)} is {@code table} for all of them.  The canonical
     * typed-boundary projection must still publish each carrier's own
     * closed token (the carried class atom for a class instance), never the
     * generic table label.
     */
    static final class LuaShapedModel extends TestModel {
        @Override public boolean isTable(Object value) {
            return value instanceof TableValue || value instanceof ClassValue
                || value instanceof ArrayValue || value instanceof FnValue;
        }
    }

    static final RuntimeTypeMatcher MATCHER = new RuntimeTypeMatcher(new TestModel());

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void check(boolean condition, String label) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]");
        }
    }

    private static void fail(String label, String detail) {
        failed++;
        System.out.println("FAIL [" + label + "]: " + detail);
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.append('\'').toString();
    }

    private static RuntimeCheckFailure failure(Object result) {
        return result instanceof RuntimeCheckFailure f ? f : null;
    }

    /** Asserts a passing check that returns the unchanged value instance
     * (every row except the int row's negative-zero normalization). */
    private static void assertPass(String descriptor, Object value, String label) {
        Object result = MATCHER.check(descriptor, value, null);
        if (result != value) {
            fail(label, "expected the unchanged value for " + quote(descriptor)
                + " but got " + result);
        } else {
            check(true, label + ": passes and returns the unchanged value");
        }
    }

    /** Asserts a passing int check that returns the normalized int {@code 0}
     * (positive zero) for a negative-zero number payload. */
    private static void assertPassNormalizedZero(String descriptor, double value, String label) {
        Object result = MATCHER.check(descriptor, value, null);
        if (!(result instanceof Double normalized)
                || Double.doubleToRawLongBits(normalized) != Double.doubleToRawLongBits(0.0d)) {
            fail(label, "expected the normalized int 0 for " + quote(descriptor)
                + " but got " + result);
        } else {
            check(true, label + ": passes and returns the normalized int 0");
        }
    }

    /** Asserts a failing check with the byte-exact pinned code and message. */
    private static void assertFailure(String descriptor, Object value, String code,
                                      String message, String label) {
        Object result = MATCHER.check(descriptor, value, null);
        RuntimeCheckFailure f = failure(result);
        if (f == null) {
            fail(label, "expected a RuntimeCheckFailure for " + quote(descriptor)
                + " but got " + result);
            return;
        }
        if (!code.equals(f.code()) || !message.equals(f.message())) {
            fail(label, "for " + quote(descriptor) + " expected " + code + " "
                + quote(message) + " but got " + f.code() + " " + quote(f.message()));
        } else {
            check(true, label);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Runtime Type Matcher Tests (ISSUE-0312) ===");

        testNullRow();
        testBooleanRow();
        testStringRow();
        testNumberRow();
        testNumberExtremes();
        testTableRow();
        testClassRow();
        testArrayRow();
        testNullableRow();
        testFunctionRow();
        testActualProjectionPrecedence();
        testUnparsableRow();
        testRangePropagation();
        testNeverThrowsAndNoMutation();
        testCarrierRecords();
        testParserTieIn();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // null row
    // =========================================================================

    static void testNullRow() {
        System.out.println("-- null row --");

        assertPass("null", NULL_VALUE, "null passes");
        assertFailure("null", Boolean.TRUE, "E8001", "expected null, got boolean", "null vs boolean");
        assertFailure("null", 1.0, "E8001", "expected null, got number", "null vs number");
        assertFailure("null", "x", "E8001", "expected null, got string", "null vs string");
        assertFailure("null", new TableValue(), "E8001", "expected null, got table", "null vs table");
        assertFailure("null", new ArrayValue(), "E8001", "expected null, got array", "null vs array");
        assertFailure("null", new ClassValue("Error"), "E8001",
            "expected null, got Error", "null vs builtin Error carrier publishes its atom");
        assertFailure("null", new ClassValue("@x/y"), "E8001",
            "expected null, got @x/y", "null vs class carrier publishes its atom");
        assertFailure("null", new BytesValue(), "E8001",
            "expected null, got bytes", "null vs bytes carrier publishes the closed bytes token");
        assertFailure("null", new FnValue("()->null"), "E8001",
            "expected null, got function", "null vs function");
    }

    // =========================================================================
    // boolean row
    // =========================================================================

    static void testBooleanRow() {
        System.out.println("-- boolean row --");

        assertPass("boolean", Boolean.TRUE, "boolean true passes");
        assertPass("boolean", Boolean.FALSE, "boolean false passes");
        assertFailure("boolean", NULL_VALUE, "E8001", "expected boolean, got null",
            "boolean vs null");
        assertFailure("boolean", 1.0, "E8001", "expected boolean, got number",
            "boolean vs number");
        assertFailure("boolean", "true", "E8001", "expected boolean, got string",
            "boolean vs string");
    }

    // =========================================================================
    // string row (with the JVM unpaired-surrogate sub-message)
    // =========================================================================

    static void testStringRow() {
        System.out.println("-- string row --");

        assertPass("string", "hello", "string passes");
        assertPass("string", "", "empty string passes");
        assertPass("string", "\uD83D\uDE00", "well-formed surrogate pair passes");
        assertFailure("string", 1.0, "E8001", "expected string, got number",
            "string vs number");
        assertFailure("string", "\uD800", "E8001",
            "expected string, got string with unpaired surrogate code units",
            "lone high surrogate");
        assertFailure("string", "\uDC00", "E8001",
            "expected string, got string with unpaired surrogate code units",
            "lone low surrogate");
        assertFailure("string", "a\uD800b", "E8001",
            "expected string, got string with unpaired surrogate code units",
            "high surrogate not followed by a low surrogate");
    }

    // =========================================================================
    // number row
    // =========================================================================

    static void testNumberRow() {
        System.out.println("-- number row --");

        assertPass("number", 3.5, "fractional number passes");
        assertPass("number", Double.NaN, "NaN passes the predicate-only number row");
        assertPass("number", Double.POSITIVE_INFINITY, "infinity passes the predicate-only number row");
        assertFailure("number", "x", "E8001", "expected number, got string",
            "number vs string");
        assertFailure("number", Boolean.TRUE, "E8001", "expected number, got boolean",
            "number vs boolean");
        assertFailure("number", new TableValue(), "E8001", "expected number, got table",
            "number vs table");
    }

    // =========================================================================
    // Binary64 admission preserves every numeric payload.
    // =========================================================================

    static void testNumberExtremes() {
        for (double value : new double[]{0, -0.0, 42, -42, 2147483648d,
                -2147483649d, 9007199254740992d, 1e308, -1e308,
                Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 3000000000.5}) {
            assertPass("number", value, "binary64 value passes " + value);
            assertPass("?number", value, "nullable binary64 value passes " + value);
        }
    }

    // =========================================================================
    // table row
    // =========================================================================

    static void testTableRow() {
        System.out.println("-- table row --");

        assertPass("table", new TableValue(), "table passes");
        assertFailure("table", 1.0, "E8001", "expected table, got number", "table vs number");
        assertFailure("table", new ClassValue("@x/y"), "E8001", "expected table, got @x/y",
            "table vs class carrier publishes its atom");
        assertFailure("table", new ClassValue("Error"), "E8001", "expected table, got Error",
            "table vs builtin Error carrier publishes its atom");
        assertFailure("table", new BytesValue(), "E8001", "expected table, got bytes",
            "table vs bytes carrier publishes the closed bytes token");
        assertFailure("table", new ArrayValue(), "E8001", "expected table, got array",
            "table vs array");
        assertFailure("table", new FnValue("()->null"), "E8001", "expected table, got function",
            "table vs function");
    }

    // =========================================================================
    // class row: byte-for-byte tag compare, dotted-tag negative pins
    // =========================================================================

    static void testClassRow() {
        System.out.println("-- class row --");

        assertPass("Error", new ClassValue("Error"),
            "builtin Error tag byte-matches");
        // The superseded v1.2 builtin spellings are refused at the decoding
        // boundary and never match the canonical atom.
        assertFailure("@/Error", new ClassValue("Error"), "E8001",
            "internal: cannot parse type descriptor: @/Error",
            "superseded @/Error alias refused at the decoding boundary");
        assertFailure("@$builtin/Error", new ClassValue("Error"), "E8001",
            "internal: cannot parse type descriptor: @$builtin/Error",
            "superseded @$builtin/Error alias refused at the decoding boundary");
        assertFailure("Error", new ClassValue("@$builtin/Error"), "E8001",
            "expected instance of Error, got @$builtin/Error",
            "a value carrying the superseded alias never matches the canonical atom");
        assertPass("@lib/utils/User", new ClassValue("@lib/utils/User"),
            "multi-component root tag byte-matches");
        assertPass("@lib.utils/User", new ClassValue("@lib.utils/User"),
            "dotted non-final component tag byte-matches");

        assertFailure("Error", new ClassValue("@$external/pkg/Error"), "E8001",
            "expected instance of Error, got @$external/pkg/Error",
            "class tag byte delta");

        // Dedicated dotted-tag negative pins: the v1.1 dotted spellings
        // never byte-match the canonical projection descriptors.
        assertFailure("@src/models/User", new ClassValue("@src.models/User"), "E8001",
            "expected instance of @src/models/User, got @src.models/User",
            "v1.1 dotted tag vs canonical project projection");
        assertFailure("@$external/host.cfg/ServerConfig", new ClassValue("@host.cfg/ServerConfig"),
            "E8001",
            "expected instance of @$external/host.cfg/ServerConfig, got @host.cfg/ServerConfig",
            "v1.1 dotted host tag vs canonical externals projection");

        // Text-opaque atoms: '/' vs '.' spellings are distinct identities.
        assertFailure("@lib/utils/User", new ClassValue("@lib.utils/User"), "E8001",
            "expected instance of @lib/utils/User, got @lib.utils/User",
            "slash/dot spellings never cross");

        assertFailure("Error", new TableValue(), "E8001",
            "expected instance of Error, got table", "untagged table value");
        assertFailure("Error", NULL_VALUE, "E8001",
            "expected instance of Error, got null", "untagged null value");
    }

    // =========================================================================
    // array row: recursive index-order checking, E8003 at the first failing index
    // =========================================================================

    static void testArrayRow() {
        System.out.println("-- array row --");

        assertPass("[number]", new ArrayValue(1.0, 2.0), "int array passes");
        assertPass("[number]", new ArrayValue(), "empty array passes");
        assertPass("[[number]]", new ArrayValue(new ArrayValue(1.0), new ArrayValue(2.0, 3.0)),
            "nested arrays pass");
        assertPass("[?number]", new ArrayValue(NULL_VALUE, 5.0), "nullable elements pass");
        assertPass("[Error]", new ArrayValue(new ClassValue("Error")),
            "class elements pass");
        assertPass("[(number)->null]", new ArrayValue(new FnValue("(number)->null")),
            "function elements pass");

        assertFailure("[number]", new TableValue(), "E8001", "expected array, got table",
            "non-array table");
        assertFailure("[number]", NULL_VALUE, "E8001", "expected array, got null",
            "non-array null");
        assertFailure("[string]", new ArrayValue(42.0, "x"), "E8003",
            "array element 1 type mismatch", "first failing index is 1");
        assertFailure("[number]", new ArrayValue(1.0, "x"), "E8003",
            "array element 2 type mismatch", "first failing index is 2");
        assertFailure("[[number]]", new ArrayValue(new ArrayValue(1.0, "x"), new ArrayValue(2.0)),
            "E8003", "array element 1 type mismatch",
            "outer first failing index reported for nested arrays");
        assertFailure("[Error]",
            new ArrayValue(new ClassValue("Error"), new ClassValue("@host.cfg/ServerConfig")),
            "E8003", "array element 2 type mismatch", "class tag delta inside an array");

        // No mutation: the array double's storage is untouched and the
        // unchanged value instance is returned.
        ArrayValue array = new ArrayValue(1.0, 2.0);
        Object[] before = array.elements.clone();
        Object result = MATCHER.check("[number]", array, null);
        check(result == array, "checked array returned as the unchanged value");
        check(Arrays.equals(before, array.elements), "array storage not mutated by the check");

        // A negative-zero element is admitted by the int row's normalization,
        // and the array's own storage keeps the element the caller supplied.
        ArrayValue zeros = new ArrayValue(-0.0);
        check(MATCHER.check("[number]", zeros, null) == zeros,
            "an array holding a negative-zero element passes the int row");
        check(Double.doubleToRawLongBits((Double) zeros.elements[0])
                == Double.doubleToRawLongBits(-0.0d),
            "the int row's negative-zero normalization never rewrites array storage");
    }

    // =========================================================================
    // nullable row
    // =========================================================================

    static void testNullableRow() {
        System.out.println("-- nullable row --");

        assertPass("?number", NULL_VALUE, "nullable null passes");
        assertPass("?number", 7.0, "nullable non-null passes");
        assertPass("?number", null, "Java null passes through the seam's null-ness");
        assertFailure("?number", "x", "E8001", "expected number, got string",
            "inner failure propagates unchanged");
        assertPass("?[?string]", new ArrayValue(NULL_VALUE, "a"), "nested nullable array passes");
        assertFailure("?[?string]", new ArrayValue(NULL_VALUE, 42.0), "E8003",
            "array element 2 type mismatch", "inner array failure propagates");
        assertFailure("?[number]", new TableValue(), "E8001", "expected array, got table",
            "inner non-array failure propagates");
        assertPass("?(number)->number", NULL_VALUE, "nullable function null passes");
    }

    // =========================================================================
    // function row: exact sync/async byte-compare, E8010 on any delta
    // =========================================================================

    static void testFunctionRow() {
        System.out.println("-- function row --");

        assertPass("(number)->string", new FnValue("(number)->string"), "sync wrapper byte-matches");
        assertPass("async(number)->string", new FnValue("async(number)->string"),
            "async wrapper byte-matches");
        assertPass("()->null", new FnValue("()->null"), "zero-arg wrapper byte-matches");
        assertPass("(Error)->null", new FnValue("(Error)->null"),
            "class-typed function byte-matches");

        assertFailure("async(number)->string", new FnValue("(number)->string"), "E8010",
            "function signature mismatch: expected async(number)->string, got (number)->string",
            "sync/async marker delta");
        assertFailure("(number)->string", new FnValue("(number)->number"), "E8010",
            "function signature mismatch: expected (number)->string, got (number)->number",
            "return-type delta");
        assertFailure("(number)->string", new FnValue("(string)->string"), "E8010",
            "function signature mismatch: expected (number)->string, got (string)->string",
            "parameter delta");

        assertFailure("(number)->string", new TableValue(), "E8001", "expected function",
            "non-wrapper table");
        assertFailure("(number)->string", NULL_VALUE, "E8001", "expected function",
            "non-wrapper null");

        assertPass("[(number)->number]", new ArrayValue(new FnValue("(number)->number")),
            "function inside array passes");
        assertFailure("[(number)->number]", new ArrayValue(new FnValue("(number)->string")), "E8003",
            "array element 1 type mismatch", "signature delta inside an array");
    }

    // =========================================================================
    // =========================================================================
    // typed-boundary actual projection: carrier identity precedes the generic
    // table fallback (the Lua value mapping is table-shaped)
    // =========================================================================

    static void testActualProjectionPrecedence() {
        System.out.println("-- typed-boundary actual projection precedence --");

        LuaShapedModel model = new LuaShapedModel();
        RuntimeTypeMatcher lua = new RuntimeTypeMatcher(model);
        check(model.isTable(new ClassValue("@x/y"))
                && model.isTable(new ArrayValue()) && model.isTable(new FnValue("()->null")),
            "the Lua-shaped seam maps every carrier to a table (type(v) == table)");

        Object[] values = {
            new ClassValue("Error"), new ClassValue("@x/y"),
            new ArrayValue(), new FnValue("()->null"), new TableValue()
        };
        String[] tokens = { "Error", "@x/y", "array", "function", "table" };
        for (int i = 0; i < values.length; i++) {
            Object result = lua.check("null", values[i], null);
            RuntimeCheckFailure f = failure(result);
            String expected = "expected null, got " + tokens[i];
            check(f != null && "E8001".equals(f.code()) && expected.equals(f.message()),
                "table-shaped seam: " + tokens[i] + " publishes its own closed actual token");
        }

    }

    // =========================================================================
    // Defensive unparsable-text row (legacy spellings and malformed text)
    // =========================================================================

    static void testUnparsableRow() {
        System.out.println("-- unparsable descriptor row --");

        assertFailure("int[]", 1.0, "E8001",
            "internal: cannot parse type descriptor: int[]", "legacy T[] spelling");
        assertFailure("string|null", "x", "E8001",
            "internal: cannot parse type descriptor: string|null", "legacy T|null spelling");
        assertFailure("(string,...string[])->string", new FnValue("(string,...string[])->string"),
            "E8001", "internal: cannot parse type descriptor: (string,...string[])->string",
            "rest-parameter sig");
        assertFailure("...T[]", 1.0, "E8001",
            "internal: cannot parse type descriptor: ...T[]", "rest-array spelling");
        assertFailure("User", new ClassValue("User"), "E8001",
            "internal: cannot parse type descriptor: User", "bare class name");
        assertFailure("@src.models.User", new ClassValue("@src.models.User"), "E8001",
            "internal: cannot parse type descriptor: @src.models.User",
            "dotted class-name position");
        assertFailure("??number", 1.0, "E8001",
            "internal: cannot parse type descriptor: ??number", "nested nullable");
        assertFailure("?null", NULL_VALUE, "E8001",
            "internal: cannot parse type descriptor: ?null", "nullable of null");
        assertFailure("[]", new ArrayValue(), "E8001",
            "internal: cannot parse type descriptor: []", "empty array");
        assertFailure("async[number]", 1.0, "E8001",
            "internal: cannot parse type descriptor: async[number]", "async not followed by (");
        assertFailure("", 1.0, "E8001",
            "internal: cannot parse type descriptor: ", "empty descriptor text");
        assertFailure(null, NULL_VALUE, "E8001",
            "internal: cannot parse type descriptor: null", "null descriptor text");
    }

    // =========================================================================
    // Range propagation
    // =========================================================================

    static void testRangePropagation() {
        System.out.println("-- range propagation --");

        RuntimeSourceLocation loc = new RuntimeSourceLocation("main.deal", 12, 4);
        Object r = MATCHER.check("number", "x", loc);
        RuntimeCheckFailure f = failure(r);
        check(f != null && "E8001".equals(f.code())
                && "expected number, got string".equals(f.message())
                && f.range() == loc,
            "failure carries the identical supplied range instance");
        check("main.deal".equals(loc.file()) && loc.line() == 12 && loc.column() == 4,
            "the supplied location itself is untouched");

        Object r2 = MATCHER.check("number", "x", null);
        RuntimeCheckFailure f2 = failure(r2);
        check(f2 != null && f2.range() == null, "absent range yields a failure with null range");

        Double seven = 7.0;
        Object r3 = MATCHER.check("number", seven, loc);
        check(r3 == seven, "passing check returns the value without a failure");
    }

    // =========================================================================
    // Never-throw, no-mutation, deep-nesting bounds
    // =========================================================================

    static void testNeverThrowsAndNoMutation() {
        System.out.println("-- never throws / no mutation / deep nesting --");

        String[] hostile = {
            "int[]", "@", "async", "[[", "?", "(x)->", "\u0000", "[number]]",
            "@a//b", "@Foo", "Async(number)->int", "T|null"
        };
        for (String descriptor : hostile) {
            try {
                Object r = MATCHER.check(descriptor, NULL_VALUE, null);
                boolean matches = failure(r) != null;
                check(matches, matches ? "" : "hostile descriptor " + quote(descriptor)
                    + " returns a failure, never throws");
            } catch (Throwable t) {
                fail("hostile descriptor " + quote(descriptor) + " threw", t.toString());
            }
        }

        // Recursion is bounded by the descriptor length: a depth-300 valid
        // descriptor checks a depth-300 nested array without failure.
        int depth = 300;
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            deep.append('[');
        }
        deep.append("number");
        for (int i = 0; i < depth; i++) {
            deep.append(']');
        }
        Object inner = 7.0;
        for (int i = 0; i < depth; i++) {
            inner = new ArrayValue(inner);
        }
        try {
            Object r = MATCHER.check(deep.toString(), inner, null);
            check(r == inner, "depth-" + depth + " nested array checks without failure");
        } catch (Throwable t) {
            fail("depth-" + depth + " nested check threw", t.toString());
        }
        Object wrong = "x";
        for (int i = 0; i < depth; i++) {
            wrong = new ArrayValue(wrong);
        }
        try {
            Object r = MATCHER.check(deep.toString(), wrong, null);
            RuntimeCheckFailure f = failure(r);
            check(f != null && "E8003".equals(f.code())
                    && "array element 1 type mismatch".equals(f.message()),
                "depth-" + depth + " failing nested check yields E8003 at the first failing index");
        } catch (Throwable t) {
            fail("depth-" + depth + " failing nested check threw", t.toString());
        }
    }

    // =========================================================================
    // Carrier record contracts
    // =========================================================================

    static void testCarrierRecords() {
        System.out.println("-- carrier records --");

        RuntimeSourceLocation loc = new RuntimeSourceLocation("main.deal", 3, 7);
        check("main.deal".equals(loc.file()) && loc.line() == 3 && loc.column() == 7,
            "RuntimeSourceLocation carries (file, line, column)");

        boolean threw = false;
        try {
            new RuntimeSourceLocation(null, 1, 1);
        } catch (RuntimeException e) {
            threw = true;
        }
        check(threw, "RuntimeSourceLocation rejects a null file");
        threw = false;
        try {
            new RuntimeSourceLocation("f.deal", 0, 1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "RuntimeSourceLocation rejects line < 1");
        threw = false;
        try {
            new RuntimeSourceLocation("f.deal", 1, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "RuntimeSourceLocation rejects column < 1");

        RuntimeCheckFailure ok = new RuntimeCheckFailure("E8001", "expected number", loc);
        check("E8001".equals(ok.code()) && "expected number".equals(ok.message())
                && ok.range() == loc,
            "RuntimeCheckFailure carries (code, message, range)");
        threw = false;
        try {
            new RuntimeCheckFailure("E8002", "x", null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "RuntimeCheckFailure rejects a non-pinned code");
        threw = false;
        try {
            new RuntimeCheckFailure("E8001", "", null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "RuntimeCheckFailure rejects an empty message");
        threw = false;
        try {
            new RuntimeCheckFailure("E8001", null, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "RuntimeCheckFailure rejects a null message");
    }

    // =========================================================================
    // Combined check with T3's strict parser
    // =========================================================================

    static void testParserTieIn() {
        System.out.println("-- combined check with T3 strict parse --");

        // The matcher parses every descriptor through T3's strict canonical
        // parser: the class row compares against the parsed ClassAtom full
        // text, so a parser regression fails this suite.
        DescriptorParseResult parsed = CanonicalRuntimeTypeDescriptor.parse("@lib/utils/User");
        check(parsed instanceof DescriptorAst.ClassAtom c
                && "@lib/utils/User".equals(c.fullDescriptorText()),
            "T3 parse yields the ClassAtom whose full text the class row compares");
        assertPass("@lib/utils/User", new ClassValue("@lib/utils/User"),
            "class row byte-matches the parsed ClassAtom full text");

        // Dotted text in the class-name position is parse-invalid, so the
        // matcher never reaches the class row for it — the defensive branch
        // fires instead.
        assertFailure("@host.cfg.ServerConfig", new ClassValue("@host.cfg.ServerConfig"), "E8001",
            "internal: cannot parse type descriptor: @host.cfg.ServerConfig",
            "dotted class-name position rejected by strict parse");
        assertFailure("@a/b.C", new ClassValue("@a/b.C"), "E8001",
            "internal: cannot parse type descriptor: @a/b.C",
            "dotted final component rejected by strict parse");
    }
}
