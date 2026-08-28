package deal.test;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.RuntimeCheckFailure;
import deal.descriptors.RuntimeSourceLocation;
import deal.descriptors.RuntimeTypeMatcher;
import deal.descriptors.RuntimeValueModel;

import java.util.Arrays;

/**
 * Tests for the canonical runtime check table (ISSUE-0312):
 * {@link RuntimeTypeMatcher#check(String, Object, RuntimeSourceLocation)}
 * with the single value-model seam {@link RuntimeValueModel}, the
 * {@link RuntimeCheckFailure} carrier, and the
 * {@link RuntimeSourceLocation} record.
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>Every pinned table row and every pinned message/code, asserted
 *       byte-for-byte: null, boolean, string (with the JVM unpaired
 *       surrogate sub-message), number, int (E8001 non-number/NaN/
 *       infinity/non-integer sub-cases and E8004 at ±(2^53-1) on both
 *       sides), table, class (byte-equal tag compare), array (E8003 at
 *       the first failing index), nullable (inner failure propagates),
 *       function (E8010 on any text delta, E8001 for a non-wrapper),
 *       bytes (hook delegation only), and the defensive unparsable-text
 *       E8001.</li>
 *   <li>Conformant anti-hollow doubles: a class double carries real tag
 *       text, a wrapper double carries a real descriptor string, and an
 *       array double exposes ordered elements.</li>
 *   <li>The dedicated dotted-tag negative pins: a value tagged with the
 *       v1.1 dotted spellings {@code @src.models/User} /
 *       {@code @host.cfg/ServerConfig} checked against the canonical
 *       projection descriptors fails E8001.</li>
 *   <li>The bytes-routing code-level pin: on the {@code bytes} row the
 *       matcher consults only {@code checkBytes(value, range)}, the hook
 *       receives {@code (value, range)}, and its outcome drives the
 *       row.</li>
 *   <li>Combined check with T3: every descriptor is parsed through the
 *       strict canonical parser, so legacy spellings (including dotted
 *       class-name-position text) hit the defensive branch and a parser
 *       regression fails this suite.</li>
 *   <li>Never-throw, no-mutation, unchanged-value, and deep-nesting
 *       bounds; the carrier records' pinned validation contracts.</li>
 * </ul>
 */
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

    /** A bytes double standing in for the E6-owned representation. */
    static final class BytesValue { }

    /**
     * The conformant full-corpus seam double: every accessor is exactly
     * one of the table's named backend-representation predicates,
     * honestly backed by the value shapes above.  Its bytes hook obeys
     * the pinned {@code check_bytes(value, range) -> bytes | E8001}
     * contract with its own failure text; the matcher must propagate the
     * hook's outcome unchanged.
     */
    static final class TestModel implements RuntimeValueModel {
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
        @Override public Object checkBytes(Object value, RuntimeSourceLocation range) {
            if (value instanceof BytesValue) {
                return value;
            }
            return new RuntimeCheckFailure("E8001",
                "expected bytes, got " + kindOf(value), range);
        }
        private String kindOf(Object value) {
            if (value == null || value == NULL_VALUE) return "null";
            if (value instanceof Boolean) return "boolean";
            if (value instanceof Double) return "number";
            if (value instanceof String) return "string";
            if (value instanceof TableValue) return "table";
            if (value instanceof ArrayValue) return "array";
            if (value instanceof ClassValue) return "class";
            if (value instanceof FnValue) return "function";
            return "unknown";
        }
    }

    /**
     * The bytes-routing-proof double: every accessor other than the
     * pinned {@code check_bytes(value, range)} hook throws, so any
     * consultation of another predicate on the bytes row fails the test
     * loudly.
     */
    static final class BytesRoutingModel implements RuntimeValueModel {
        final Object hookOutcome;
        boolean hookCalled;
        Object receivedValue;
        RuntimeSourceLocation receivedRange;

        BytesRoutingModel(Object hookOutcome) { this.hookOutcome = hookOutcome; }

        private AssertionError forbidden(String accessor) {
            return new AssertionError("non-bytes accessor consulted on the bytes row: " + accessor);
        }
        @Override public boolean isNull(Object value) { throw forbidden("isNull"); }
        @Override public boolean isBoolean(Object value) { throw forbidden("isBoolean"); }
        @Override public boolean isString(Object value) { throw forbidden("isString"); }
        @Override public String stringPayload(Object value) { throw forbidden("stringPayload"); }
        @Override public boolean isNumber(Object value) { throw forbidden("isNumber"); }
        @Override public double numberPayload(Object value) { throw forbidden("numberPayload"); }
        @Override public boolean isTable(Object value) { throw forbidden("isTable"); }
        @Override public boolean isArray(Object value) { throw forbidden("isArray"); }
        @Override public int arrayLength(Object value) { throw forbidden("arrayLength"); }
        @Override public Object arrayElementAt(Object value, int index) { throw forbidden("arrayElementAt"); }
        @Override public String classIdentityText(Object value) { throw forbidden("classIdentityText"); }
        @Override public String functionDescriptorText(Object value) { throw forbidden("functionDescriptorText"); }
        @Override public Object checkBytes(Object value, RuntimeSourceLocation range) {
            hookCalled = true;
            receivedValue = value;
            receivedRange = range;
            return hookOutcome;
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

    /** Asserts a passing check that returns the unchanged value instance. */
    private static void assertPass(String descriptor, Object value, String label) {
        Object result = MATCHER.check(descriptor, value, null);
        if (result != value) {
            fail(label, "expected the unchanged value for " + quote(descriptor)
                + " but got " + result);
        } else {
            check(true, label + ": passes and returns the unchanged value");
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
            check(true, label + ": " + code + " " + quote(message));
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
        testIntRow();
        testTableRow();
        testClassRow();
        testArrayRow();
        testNullableRow();
        testFunctionRow();
        testBytesRow();
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
        assertFailure("null", new ClassValue("@$builtin/Error"), "E8001",
            "expected null, got class", "null vs class");
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
    // int row: E8001 sub-cases and E8004 at ±(2^53-1) on both sides
    // =========================================================================

    static void testIntRow() {
        System.out.println("-- int row --");

        double bound = RuntimeTypeMatcher.INT_SAFE_RANGE_BOUND;
        check(bound == 9007199254740991.0d,
            "pinned INT_SAFE_RANGE_BOUND equals 2^53 - 1");

        assertPass("int", 0.0, "integral zero passes");
        assertPass("int", -0.0, "negative zero passes (normalized internally)");
        assertPass("int", 42.0, "integral number passes");
        assertPass("int", -42.0, "negative integral number passes");
        assertPass("int", bound, "upper boundary 2^53 - 1 passes");
        assertPass("int", -bound, "lower boundary -(2^53 - 1) passes");
        assertPass("int", bound - 1.0, "just inside the upper boundary passes");
        assertPass("int", -bound + 1.0, "just inside the lower boundary passes");

        assertFailure("int", Boolean.TRUE, "E8001", "expected int, got boolean",
            "int vs non-number boolean");
        assertFailure("int", NULL_VALUE, "E8001", "expected int, got null",
            "int vs non-number null");
        assertFailure("int", "x", "E8001", "expected int, got string",
            "int vs non-number string");
        assertFailure("int", new TableValue(), "E8001", "expected int, got table",
            "int vs non-number table");
        assertFailure("int", Double.NaN, "E8001", "expected int, got NaN", "int vs NaN");
        assertFailure("int", Double.POSITIVE_INFINITY, "E8001",
            "expected int, got infinity", "int vs positive infinity");
        assertFailure("int", Double.NEGATIVE_INFINITY, "E8001",
            "expected int, got infinity", "int vs negative infinity");
        assertFailure("int", 3.5, "E8001", "expected int, got non-integer number",
            "int vs non-integer");
        assertFailure("int", -3.5, "E8001", "expected int, got non-integer number",
            "int vs negative non-integer");

        assertFailure("int", bound + 1.0, "E8004", "int out of safe range",
            "2^53 fails E8004");
        assertFailure("int", -bound - 1.0, "E8004", "int out of safe range",
            "-(2^53) fails E8004");
        assertFailure("int", 1e308, "E8004", "int out of safe range",
            "finite integral far above the range fails E8004");
        assertFailure("int", -1e308, "E8004", "int out of safe range",
            "finite integral far below the range fails E8004");
    }

    // =========================================================================
    // table row
    // =========================================================================

    static void testTableRow() {
        System.out.println("-- table row --");

        assertPass("table", new TableValue(), "table passes");
        assertFailure("table", 1.0, "E8001", "expected table, got number", "table vs number");
        assertFailure("table", new ClassValue("@x/y"), "E8001", "expected table, got class",
            "table vs class");
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

        assertPass("@$builtin/Error", new ClassValue("@$builtin/Error"),
            "builtin Error tag byte-matches");
        assertPass("@lib/utils/User", new ClassValue("@lib/utils/User"),
            "multi-component root tag byte-matches");
        assertPass("@lib.utils/User", new ClassValue("@lib.utils/User"),
            "dotted non-final component tag byte-matches");

        assertFailure("@$builtin/Error", new ClassValue("@$external/pkg/Error"), "E8001",
            "expected instance of @$builtin/Error, got @$external/pkg/Error",
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

        assertFailure("@$builtin/Error", new TableValue(), "E8001",
            "expected instance of @$builtin/Error, got table", "untagged table value");
        assertFailure("@$builtin/Error", NULL_VALUE, "E8001",
            "expected instance of @$builtin/Error, got null", "untagged null value");
    }

    // =========================================================================
    // array row: recursive index-order checking, E8003 at the first failing index
    // =========================================================================

    static void testArrayRow() {
        System.out.println("-- array row --");

        assertPass("[int]", new ArrayValue(1.0, 2.0), "int array passes");
        assertPass("[int]", new ArrayValue(), "empty array passes");
        assertPass("[[int]]", new ArrayValue(new ArrayValue(1.0), new ArrayValue(2.0, 3.0)),
            "nested arrays pass");
        assertPass("[?int]", new ArrayValue(NULL_VALUE, 5.0), "nullable elements pass");
        assertPass("[@$builtin/Error]", new ArrayValue(new ClassValue("@$builtin/Error")),
            "class elements pass");
        assertPass("[(int)->null]", new ArrayValue(new FnValue("(int)->null")),
            "function elements pass");

        assertFailure("[int]", new TableValue(), "E8001", "expected array, got table",
            "non-array table");
        assertFailure("[int]", NULL_VALUE, "E8001", "expected array, got null",
            "non-array null");
        assertFailure("[string]", new ArrayValue(42.0, "x"), "E8003",
            "array element 1 type mismatch", "first failing index is 1");
        assertFailure("[int]", new ArrayValue(1.0, "x"), "E8003",
            "array element 2 type mismatch", "first failing index is 2");
        assertFailure("[[int]]", new ArrayValue(new ArrayValue(1.0, "x"), new ArrayValue(2.0)),
            "E8003", "array element 1 type mismatch",
            "outer first failing index reported for nested arrays");
        assertFailure("[@$builtin/Error]",
            new ArrayValue(new ClassValue("@$builtin/Error"), new ClassValue("@host.cfg/ServerConfig")),
            "E8003", "array element 2 type mismatch", "class tag delta inside an array");

        // No mutation: the array double's storage is untouched and the
        // unchanged value instance is returned.
        ArrayValue array = new ArrayValue(1.0, 2.0);
        Object[] before = array.elements.clone();
        Object result = MATCHER.check("[int]", array, null);
        check(result == array, "checked array returned as the unchanged value");
        check(Arrays.equals(before, array.elements), "array storage not mutated by the check");
    }

    // =========================================================================
    // nullable row
    // =========================================================================

    static void testNullableRow() {
        System.out.println("-- nullable row --");

        assertPass("?int", NULL_VALUE, "nullable null passes");
        assertPass("?int", 7.0, "nullable non-null passes");
        assertPass("?int", null, "Java null passes through the seam's null-ness");
        assertFailure("?int", "x", "E8001", "expected int, got string",
            "inner failure propagates unchanged");
        assertPass("?[?string]", new ArrayValue(NULL_VALUE, "a"), "nested nullable array passes");
        assertFailure("?[?string]", new ArrayValue(NULL_VALUE, 42.0), "E8003",
            "array element 2 type mismatch", "inner array failure propagates");
        assertFailure("?[int]", new TableValue(), "E8001", "expected array, got table",
            "inner non-array failure propagates");
        assertPass("?(int)->int", NULL_VALUE, "nullable function null passes");
    }

    // =========================================================================
    // function row: exact sync/async byte-compare, E8010 on any delta
    // =========================================================================

    static void testFunctionRow() {
        System.out.println("-- function row --");

        assertPass("(int)->string", new FnValue("(int)->string"), "sync wrapper byte-matches");
        assertPass("async(int)->string", new FnValue("async(int)->string"),
            "async wrapper byte-matches");
        assertPass("()->null", new FnValue("()->null"), "zero-arg wrapper byte-matches");
        assertPass("(@$builtin/Error)->null", new FnValue("(@$builtin/Error)->null"),
            "class-typed function byte-matches");

        assertFailure("async(int)->string", new FnValue("(int)->string"), "E8010",
            "function signature mismatch: expected async(int)->string, got (int)->string",
            "sync/async marker delta");
        assertFailure("(int)->string", new FnValue("(int)->number"), "E8010",
            "function signature mismatch: expected (int)->string, got (int)->number",
            "return-type delta");
        assertFailure("(int)->string", new FnValue("(string)->string"), "E8010",
            "function signature mismatch: expected (int)->string, got (string)->string",
            "parameter delta");

        assertFailure("(int)->string", new TableValue(), "E8001", "expected function",
            "non-wrapper table");
        assertFailure("(int)->string", NULL_VALUE, "E8001", "expected function",
            "non-wrapper null");

        assertPass("[(int)->int]", new ArrayValue(new FnValue("(int)->int")),
            "function inside array passes");
        assertFailure("[(int)->int]", new ArrayValue(new FnValue("(int)->string")), "E8003",
            "array element 1 type mismatch", "signature delta inside an array");
    }

    // =========================================================================
    // bytes row: hook delegation only; the hook receives (value, range)
    // =========================================================================

    static void testBytesRow() {
        System.out.println("-- bytes row --");

        BytesValue bytes = new BytesValue();
        assertPass("bytes", bytes, "bytes row delegates to the hook and passes");

        // Hook failure propagates byte-for-byte with the supplied range.
        RuntimeSourceLocation loc = new RuntimeSourceLocation("main.deal", 1, 1);
        Object r = MATCHER.check("bytes", new TableValue(), loc);
        RuntimeCheckFailure f = failure(r);
        check(f != null && "E8001".equals(f.code())
                && "expected bytes, got table".equals(f.message())
                && f.range() == loc,
            "bytes hook failure (E8001 'expected bytes, got table') propagated with the range");

        // Code-level routing pin: only checkBytes is consulted; the hook
        // receives (value, range); its outcome drives the row.
        BytesRoutingModel routing = new BytesRoutingModel(bytes);
        RuntimeTypeMatcher routingMatcher = new RuntimeTypeMatcher(routing);
        Object r2 = routingMatcher.check("bytes", bytes, loc);
        check(r2 == bytes, "bytes-routing: success outcome returns the unchanged value");
        check(routing.hookCalled, "bytes-routing: the hook was called");
        check(routing.receivedValue == bytes, "bytes-routing: hook received the value");
        check(routing.receivedRange == loc, "bytes-routing: hook received the range");

        RuntimeCheckFailure hookFailure =
            new RuntimeCheckFailure("E8001", "expected bytes, got unknown", loc);
        BytesRoutingModel routing2 = new BytesRoutingModel(hookFailure);
        Object r3 = new RuntimeTypeMatcher(routing2).check("bytes", bytes, loc);
        check(r3 == hookFailure, "bytes-routing: hook failure outcome propagated unchanged");
        check(routing2.hookCalled && routing2.receivedValue == bytes && routing2.receivedRange == loc,
            "bytes-routing: the failing hook still received (value, range)");

        // Nested delegation: the hook drives the row inside arrays too.
        assertPass("[bytes]", new ArrayValue(new BytesValue(), new BytesValue()),
            "bytes elements pass via the hook");
        assertFailure("[bytes]", new ArrayValue(new BytesValue(), new TableValue()), "E8003",
            "array element 2 type mismatch", "bytes hook failure becomes the element failure");
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
        assertFailure("??int", 1.0, "E8001",
            "internal: cannot parse type descriptor: ??int", "nested nullable");
        assertFailure("?null", NULL_VALUE, "E8001",
            "internal: cannot parse type descriptor: ?null", "nullable of null");
        assertFailure("[]", new ArrayValue(), "E8001",
            "internal: cannot parse type descriptor: []", "empty array");
        assertFailure("async[int]", 1.0, "E8001",
            "internal: cannot parse type descriptor: async[int]", "async not followed by (");
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
        Object r = MATCHER.check("int", "x", loc);
        RuntimeCheckFailure f = failure(r);
        check(f != null && "E8001".equals(f.code())
                && "expected int, got string".equals(f.message())
                && f.range() == loc,
            "failure carries the identical supplied range instance");
        check("main.deal".equals(loc.file()) && loc.line() == 12 && loc.column() == 4,
            "the supplied location itself is untouched");

        Object r2 = MATCHER.check("int", "x", null);
        RuntimeCheckFailure f2 = failure(r2);
        check(f2 != null && f2.range() == null, "absent range yields a failure with null range");

        Double seven = 7.0;
        Object r3 = MATCHER.check("int", seven, loc);
        check(r3 == seven, "passing check returns the value without a failure");
    }

    // =========================================================================
    // Never-throw, no-mutation, deep-nesting bounds
    // =========================================================================

    static void testNeverThrowsAndNoMutation() {
        System.out.println("-- never throws / no mutation / deep nesting --");

        String[] hostile = {
            "int[]", "@", "async", "[[", "?", "(x)->", "\u0000", "[int]]",
            "@a//b", "@Foo", "Async(int)->int", "T|null"
        };
        for (String descriptor : hostile) {
            try {
                Object r = MATCHER.check(descriptor, NULL_VALUE, null);
                check(failure(r) != null, "hostile descriptor " + quote(descriptor)
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
        deep.append("int");
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

        RuntimeCheckFailure ok = new RuntimeCheckFailure("E8004", "int out of safe range", loc);
        check("E8004".equals(ok.code()) && "int out of safe range".equals(ok.message())
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
