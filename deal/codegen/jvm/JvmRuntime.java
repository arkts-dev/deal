package deal.codegen.jvm;

import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ActualKind;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared JVM runtime helper of the decomposition-tail integration
 * verification (ISSUE-0410): the real runtime the generated shared-JVM
 * artifact executes against — event/effect/terminal protocol encoding
 * (byte-identical to the semantic oracle's report), the closed atom
 * encoding with first-observation allocation ids, the native boundary
 * projections (the descriptor-kind rule with the pinned int ladder, the
 * array element cause chain, the function-signature projection — exactly
 * the closed failure registry's templates, never invented text), the
 * closed B-D2 comparison table realized with native Java operators
 * (primitive {@code ==}/{@code <} on ints; IEEE {@code ==}/{@code <} on
 * doubles — never {@code Double.compare}; code point order on strings —
 * never {@code String.compareTo}; identity {@code ==} on references —
 * never {@code equals()}), and the array read/bounds cells with the
 * shared slot-space context.
 */
public final class JvmRuntime {

    private JvmRuntime() {
    }

    // =========================================================================
    // Values
    // =========================================================================

    /** The internal missing sentinel (past-end array reads). */
    public static final Object MISSING = new Object() {
        @Override public String toString() {
            return "missing";
        }
    };

    /** A string-keyed table with explicit key presence. */
    public static final class Table {
        public final LinkedHashMap<String, Object> entries = new LinkedHashMap<>();
        public final java.util.HashSet<String> keys = new java.util.HashSet<>();

        public Object read(String key) {
            if (!keys.contains(key)) {
                return MISSING;
            }
            return entries.get(key);
        }

        public void write(String key, Object value) {
            entries.put(key, value);
            keys.add(key);
        }

        public void remove(String key) {
            entries.remove(key);
            keys.remove(key);
        }
    }

    /** A dense array with the shared slot-space length. */
    public static final class Array {
        public final List<Object> elements = new ArrayList<>();
        public int length;

        public Array(int length) {
            this.length = length;
        }
    }

    /** A DEAL Error value ({code, message}). */
    public static final class ErrorValue {
        public final String code;
        public final String message;

        public ErrorValue(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /** An intrinsic function value (int()/number() as first-class values). */
    public static final class Intrinsic {
        @Override public String toString() {
            return "intrinsic";
        }
    }

    /** A function value: the invoker plus its carried signature text. */
    public interface Fn {
        Object invoke(Object[] args);
    }

    public static final class FunctionValue {
        public final Fn fn;
        public final String signature;

        public FunctionValue(Fn fn, String signature) {
            this.fn = fn;
            this.signature = signature;
        }
    }

    /** A BREAK/CONTINUE/RETURN transfer signal crossing a try boundary. */
    public static final class Transfer extends RuntimeException {
        public final String kind;
        public final long id;
        public final Object value;

        public Transfer(String kind, long id, Object value) {
            super(null, null, false, false);
            this.kind = kind;
            this.id = id;
            this.value = value;
        }
    }

    /** A DEAL failure carrying its exact projection facts. */
    public static final class DealError extends RuntimeException {
        public final String code;
        public final String msg;
        public final String origin;
        public final String expected;
        public final String actual;
        public final String frames;
        public final DealError cause;

        public DealError(String code, String msg, String origin, String expected,
                         String actual, String frames, DealError cause) {
            super(msg, cause);
            this.code = code;
            this.msg = msg;
            this.origin = origin;
            this.expected = expected;
            this.actual = actual;
            this.frames = frames;
            this.cause = cause;
        }
    }

    // =========================================================================
    // Protocol state
    // =========================================================================

    static final ThreadLocal<List<String>> FRAMES = ThreadLocal.withInitial(ArrayList::new);
    static long seq = 0;
    static final IdentityHashMap<Object, String> ALLOC_IDS = new IdentityHashMap<>();
    static long allocNext = 1;

    /** Pushes/pops the innermost active frame (function id text). */
    public static void pushFrame(String functionId) {
        FRAMES.get().add(0, functionId);
    }

    public static void popFrame() {
        FRAMES.get().remove(0);
    }

    public static String framesText() {
        List<String> frames = FRAMES.get();
        return frames.isEmpty() ? "-" : String.join(",", frames);
    }

    // =========================================================================
    // Encoding
    // =========================================================================

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case ';' -> out.append("\\u003b");
                case '|' -> out.append("\\u007c");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The closed atom encoding (first-observation allocation ids). */
    public static String atom(Object v, String kind) {
        if (v == MISSING) {
            return "missing";
        }
        if (kind == null) {
            return "missing";
        }
        switch (kind) {
            case "null" -> {
                return "null";
            }
            case "missing" -> {
                return "missing";
            }
            case "bool" -> {
                return "bool:" + v;
            }
            case "int" -> {
                return "int:" + v;
            }
            case "number" -> {
                double d = (Double) v;
                if (Double.isNaN(d)) {
                    return "num:nan";
                }
                return "num:" + Double.toHexString(d);
            }
            case "string" -> {
                return "str:" + esc((String) v);
            }
            case "err" -> {
                ErrorValue error = (ErrorValue) v;
                return "err:" + error.code + ":" + esc(error.message);
            }
            default -> {
                if (kind.startsWith("nullable:")) {
                    if (v == null) {
                        return "null";
                    }
                    return atom(v, kind.substring("nullable:".length()));
                }
                if (kind.equals("ref") || kind.equals("table") || kind.equals("array")
                        || kind.equals("function")) {
                    return "ref:" + allocId(v);
                }
                return "missing";
            }
        }
    }

    static String allocId(Object heap) {
        String id = ALLOC_IDS.get(heap);
        if (id == null) {
            id = String.valueOf(allocNext++);
            ALLOC_IDS.put(heap, id);
        }
        return id;
    }

    /** The nested error text form. */
    public static String errtext(Throwable e) {
        if (e instanceof DealError deal) {
            return deal.code + ";" + esc(deal.msg) + ";" + (deal.origin == null ? "-"
                : deal.origin) + ";" + (deal.expected == null ? "-" : deal.expected) + ";"
                + (deal.actual == null ? "-" : deal.actual) + ";"
                + (deal.frames == null || deal.frames.isEmpty() ? "-" : deal.frames) + ";"
                + (deal.cause == null ? "-" : errtext(deal.cause));
        }
        return "E9999;" + esc(String.valueOf(e)) + ";-;-;-;-;-";
    }

    /**
     * The protocol-channel gate (ISSUE-0239 E10): the conformance
     * consumers keep the channel on (the default), while a production
     * artifact disables it at startup so no trace/effect record ever
     * reaches stderr from a production run.
     */
    private static volatile boolean traceEnabled = true;

    /**
     * Sets the protocol-channel gate. Production artifacts call this
     * with {@code false} before executing any operation; the conformance
     * harness never calls it, so its per-process default stays on.
     *
     * @param enabled whether the dedicated trace/effect channel is active
     */
    public static void setTraceEnabled(boolean enabled) {
        traceEnabled = enabled;
    }

    /** Emits one protocol trace line to the dedicated channel (stderr). */
    public static void ev(String module, String op, String phase, String kind,
                          String digest, String parent, List<String> inputs,
                          String output, String errtext) {
        if (!traceEnabled) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append("T|").append(seq++).append('|').append(module).append('|').append(op)
            .append('|').append(phase).append('|').append(kind).append('|').append(digest)
            .append('|').append(parent == null ? "-" : parent);
        if (inputs != null) {
            for (String input : inputs) {
                line.append('|').append(input);
            }
        }
        if (output != null) {
            line.append("|=>").append(output);
        }
        if (errtext != null) {
            line.append("|!").append(errtext);
        }
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println(line);
    }

    /** Records one console effect (real stdout bytes + the protocol record). */
    public static void console(String text) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println(text);
        if (!traceEnabled) {
            return;
        }
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println("F|CONSOLE_WRITE|" + esc(text));
    }

    // =========================================================================
    // Boundary projections (the closed failure registry's exact templates)
    // =========================================================================

    public static DealError fail(String code, String msg, String origin, String expected,
                                 String actual) {
        return new DealError(code, msg, origin, expected, actual, framesText(), null);
    }

    public static String actualOf(String staticKind, Object v) {
        if (v == MISSING) {
            return "missing";
        }
        switch (staticKind) {
            case "null" -> {
                return "null";
            }
            case "bool" -> {
                return "boolean";
            }
            case "int" -> {
                return "int";
            }
            case "number" -> {
                return "number";
            }
            case "string" -> {
                return "string";
            }
            case "table" -> {
                return "table";
            }
            case "array" -> {
                return "array";
            }
            case "function" -> {
                return "function";
            }
            case "err" -> {
                return "class:@builtin/Error";
            }
            default -> {
                if (staticKind.startsWith("nullable:")) {
                    if (v == null) {
                        return "null";
                    }
                    return actualOf(staticKind.substring("nullable:".length()), v);
                }
                return "missing";
            }
        }
    }

    /**
     * The descriptor-kind boundary check over the closed descriptor texts
     * ({@code null|boolean|int|number|string|table|array(INNER)|
     * nullable(INNER)|function(PARAMS;RETURN)}): the pinned int ladder
     * (kind → NaN → infinity → fractional → E8004), the array element
     * cause chain (E8003), and the function-signature projection (E8010).
     */
    public static Object bcheck(String desc, String staticKind, Object v) {
        String actual = actualOf(staticKind, v);
        if ("null".equals(desc)) {
            if (v == null) {
                return v;
            }
            throw fail("E8001", "expected null, got " + actual, "-", "null", actual);
        }
        if ("boolean".equals(desc)) {
            if (v instanceof Boolean) {
                return v;
            }
            throw fail("E8001", "expected boolean, got " + actual, "-", "boolean", actual);
        }
        if ("int".equals(desc)) {
            if (v instanceof Long longValue) {
                return v;
            }
            if (v instanceof Double doubleValue) {
                double d = doubleValue;
                if (Double.isNaN(d)) {
                    throw fail("E8001", "expected int, got NaN", "-", "int", "NaN");
                }
                if (Double.isInfinite(d)) {
                    throw fail("E8001", "expected int, got infinity", "-", "int",
                        "infinity");
                }
                if (d != Math.rint(d)) {
                    throw fail("E8001", "expected int, got non-integer number", "-", "int",
                        "non-integer number");
                }
                if (d < -2147483648d || d > 2147483647d) {
                    throw fail("E8004", "int out of range", "-", "int", "number");
                }
                return d;
            }
            throw fail("E8001", "expected int, got " + actual, "-", "int", actual);
        }
        if ("number".equals(desc)) {
            if (v instanceof Double || v instanceof Long) {
                return v;
            }
            throw fail("E8001", "expected number, got " + actual, "-", "number", actual);
        }
        if ("string".equals(desc)) {
            if (v instanceof String) {
                return v;
            }
            throw fail("E8001", "expected string, got " + actual, "-", "string", actual);
        }
        if ("table".equals(desc)) {
            if (v instanceof Table) {
                return v;
            }
            throw fail("E8001", "expected table, got " + actual, "-", "table", actual);
        }
        if (desc.startsWith("array(")) {
            if (v instanceof Array array) {
                String inner = desc.substring(6, desc.length() - 1);
                for (int i = 0; i < array.length; i++) {
                    Object elem = i < array.elements.size() ? array.elements.get(i)
                        : MISSING;
                    if (elem == null) {
                        elem = MISSING;
                    }
                    try {
                        bcheck(inner, elem == MISSING ? "missing" : inner, elem);
                    } catch (DealError leaf) {
                        throw fail("E8003", "array element " + (i + 1)
                            + " type mismatch", "-", inner,
                            actualOf(elem == MISSING ? "missing" : inner, elem));
                    }
                }
                return v;
            }
            throw fail("E8001", "expected array, got " + actual, "-", "array", actual);
        }
        if (desc.startsWith("nullable(")) {
            if (v == null || v == MISSING) {
                return null;
            }
            return bcheck(desc.substring(9, desc.length() - 1), staticKind, v);
        }
        if (desc.startsWith("function(")) {
            if (v instanceof FunctionValue function) {
                String carried = function.signature == null ? "" : function.signature;
                if (carried.equals(desc)) {
                    return v;
                }
                throw fail("E8010", "function signature mismatch: expected " + desc
                    + ", got " + carried, "-", desc, carried);
            }
            throw fail("E8001", "expected function, got " + actual, "-", "function",
                actual);
        }
        return v;
    }

    // =========================================================================
    // The closed B-D2 comparison table (native operators)
    // =========================================================================

    public static boolean cmp(String selector, String kindL, Object l, String kindR,
                              Object r, String side) {
        boolean leftNullish = l == null || l == MISSING;
        boolean rightNullish = r == null || r == MISSING;
        if (leftNullish || rightNullish) {
            if ("NULLABLE_NULL_EQ".equals(selector)) {
                Object named = "LEFT".equals(side) ? l : r;
                return named == null || named == MISSING;
            }
            if ("NULLABLE_NULL_NE".equals(selector)) {
                Object named = "LEFT".equals(side) ? l : r;
                return !(named == null || named == MISSING);
            }
            boolean both = leftNullish && rightNullish;
            if (selector.endsWith("_EQ")) {
                return both;
            }
            if (selector.endsWith("_NE")) {
                return !both;
            }
            return false;
        }
        switch (selector) {
            case "INT32_EQ" -> {
                return ((Long) l).longValue() == ((Long) r).longValue();
            }
            case "INT32_NE" -> {
                return ((Long) l).longValue() != ((Long) r).longValue();
            }
            case "INT32_LT" -> {
                return ((Long) l).longValue() < ((Long) r).longValue();
            }
            case "INT32_LE" -> {
                return ((Long) l).longValue() <= ((Long) r).longValue();
            }
            case "INT32_GT" -> {
                return ((Long) l).longValue() > ((Long) r).longValue();
            }
            case "INT32_GE" -> {
                return ((Long) l).longValue() >= ((Long) r).longValue();
            }
            case "NUMBER_EQ" -> {
                return ((Number) l).doubleValue() == ((Number) r).doubleValue();
            }
            case "NUMBER_NE" -> {
                return ((Number) l).doubleValue() != ((Number) r).doubleValue();
            }
            case "NUMBER_LT" -> {
                return ((Number) l).doubleValue() < ((Number) r).doubleValue();
            }
            case "NUMBER_LE" -> {
                return ((Number) l).doubleValue() <= ((Number) r).doubleValue();
            }
            case "NUMBER_GT" -> {
                return ((Number) l).doubleValue() > ((Number) r).doubleValue();
            }
            case "NUMBER_GE" -> {
                return ((Number) l).doubleValue() >= ((Number) r).doubleValue();
            }
            case "STRING_EQ" -> {
                return ((String) l).equals((String) r);
            }
            case "STRING_NE" -> {
                return !((String) l).equals((String) r);
            }
            case "STRING_LT" -> {
                return codePointCompare((String) l, (String) r) < 0;
            }
            case "STRING_LE" -> {
                return codePointCompare((String) l, (String) r) <= 0;
            }
            case "STRING_GT" -> {
                return codePointCompare((String) l, (String) r) > 0;
            }
            case "STRING_GE" -> {
                return codePointCompare((String) l, (String) r) >= 0;
            }
            case "BOOLEAN_EQ" -> {
                return ((Boolean) l).equals((Boolean) r);
            }
            case "BOOLEAN_NE" -> {
                return !((Boolean) l).equals((Boolean) r);
            }
            case "NULL_EQ" -> {
                return true;
            }
            case "NULL_NE" -> {
                return false;
            }
            case "NULLABLE_EQ" -> {
                return nullableEquals(kindL, l, kindR, r);
            }
            case "NULLABLE_NE" -> {
                return !nullableEquals(kindL, l, kindR, r);
            }
            case "REFERENCE_EQ" -> {
                return l == r;
            }
            case "REFERENCE_NE" -> {
                return l != r;
            }
            default -> {
                return false;
            }
        }
    }

    /** Unicode-scalar (code point) order — never String.compareTo. */
    static int codePointCompare(String left, String right) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int n = Math.min(leftPoints.length, rightPoints.length);
        for (int i = 0; i < n; i++) {
            if (leftPoints[i] != rightPoints[i]) {
                return Integer.compare(leftPoints[i], rightPoints[i]);
            }
        }
        return Integer.compare(leftPoints.length, rightPoints.length);
    }

    static boolean nullableEquals(String kindL, Object l, String kindR, Object r) {
        // Inner equality by the value rules (both non-null here).
        if (l instanceof Long left && r instanceof Long right) {
            return left.longValue() == right.longValue();
        }
        if (l instanceof Number left && r instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        if (l instanceof String left && r instanceof String right) {
            return left.equals(right);
        }
        if (l instanceof Boolean left && r instanceof Boolean right) {
            return left.equals(right);
        }
        return l == r; // reference identity for heap values
    }

    // =========================================================================
    // The closed int32/number arithmetic rows
    // =========================================================================

    public static Object unary(String selector, Object v, String opKey, String digest,
                               String parent, String origin) {
        if ("BOOL_NOT".equals(selector)) {
            return !((Boolean) v);
        }
        if ("INT32_NEG".equals(selector)) {
            long result = -((Long) v).longValue();
            return int32Result(result, opKey, digest, parent, origin, "UNARY");
        }
        return -((Number) v).doubleValue();
    }

    public static Object arith(String selector, Object l, Object r, String opKey,
                               String digest, String parent, String origin) {
        switch (selector) {
            case "INT32_ADD" -> {
                return int32Result(((Long) l) + ((Long) r), opKey, digest, parent, origin,
                    "BINARY");
            }
            case "INT32_SUB" -> {
                return int32Result(((Long) l) - ((Long) r), opKey, digest, parent, origin,
                    "BINARY");
            }
            case "INT32_MUL" -> {
                return int32Result(((Long) l) * ((Long) r), opKey, digest, parent, origin,
                    "BINARY");
            }
            case "INT32_DIV_TRUNC" -> {
                long divisor = ((Long) r);
                if (divisor == 0) {
                    raise(opKey, digest, parent, origin, "BINARY", "E8005",
                        "integer division by zero", null, null);
                }
                return int32Result(((Long) l) / divisor, opKey, digest, parent, origin,
                    "BINARY");
            }
            case "INT32_MOD_TRUNC" -> {
                long divisor = ((Long) r);
                if (divisor == 0) {
                    raise(opKey, digest, parent, origin, "BINARY", "E8005",
                        "integer division by zero", null, null);
                }
                return int32Result(((Long) l) % divisor, opKey, digest, parent, origin,
                    "BINARY");
            }
            case "INT32_POW" -> {
                long exponent = ((Long) r);
                if (exponent < 0) {
                    raise(opKey, digest, parent, origin, "BINARY", "E8006",
                        "integer exponent must be non-negative", null, null);
                }
                long result = 1;
                try {
                    for (long i = 0; i < exponent; i++) {
                        result = Math.multiplyExact(result, ((Long) l));
                    }
                } catch (ArithmeticException overflow) {
                    raise(opKey, digest, parent, origin, "BINARY", "E8004",
                        "int out of range", null, null);
                }
                return int32Result(result, opKey, digest, parent, origin, "BINARY");
            }
            case "NUMBER_ADD" -> {
                return ((Number) l).doubleValue() + ((Number) r).doubleValue();
            }
            case "NUMBER_SUB" -> {
                return ((Number) l).doubleValue() - ((Number) r).doubleValue();
            }
            case "NUMBER_MUL" -> {
                return ((Number) l).doubleValue() * ((Number) r).doubleValue();
            }
            case "NUMBER_DIV_IEEE" -> {
                return ((Number) l).doubleValue() / ((Number) r).doubleValue();
            }
            case "NUMBER_MOD_FLOOR" -> {
                double a = ((Number) l).doubleValue();
                double b = ((Number) r).doubleValue();
                return a - Math.floor(a / b) * b;
            }
            case "NUMBER_POW_IEEE" -> {
                return Math.pow(((Number) l).doubleValue(), ((Number) r).doubleValue());
            }
            default -> {
                return 0L;
            }
        }
    }

    static Object int32Result(long result, String opKey, String digest, String parent,
                              String origin, String kind) {
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            raise(opKey, digest, parent, origin, kind, "E8004", "int out of range", null,
                null);
        }
        return result;
    }

    static void raise(String opKey, String digest, String parent, String origin,
                      String kind, String code, String msg, String expected, String actual) {
        DealError e = fail(code, msg, origin, expected, actual);
        ev(currentModule(), opKey, "FAILURE", kind, digest, parent, List.of(), null,
            errtext(e));
        throw e;
    }

    // =========================================================================
    // Array read/bounds cells (shared slot-space context)
    // =========================================================================

    /** The ARRAY_ELEMENT_READ cell: negative E8002 first, then the contextual decision. */
    public static Object arrayRead(String opKey, String digest, String parent,
                                   String bKey, String bDigest, String bParent,
                                   String desc, String inner, Array container,
                                   long index, boolean nullable, String origin) {
        Object elem = MISSING;
        if (index < 0) {
            ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
                List.of(atom(MISSING, "missing")), null, null);
            DealError e = fail("E8002", "negative array index", origin, null, null);
            ev(currentModule(), bKey, "FAILURE", "BOUNDARY", bDigest, bParent,
                List.of(), null, errtext(e));
            ev(currentModule(), opKey, "FAILURE", "INDEX_READ", digest, parent,
                List.of(), null, errtext(e));
            throw e;
        }
        if (index < container.length && index < container.elements.size()) {
            elem = container.elements.get((int) index);
        }
        // A present null element stays null (the slot was stored with a
        // language null); only an absent/deleted slot reads as missing.
        String elemKind;
        if (elem == MISSING) {
            elemKind = "missing";
        } else if (elem == null) {
            elemKind = "null";
        } else {
            elemKind = inner;
        }
        ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
            List.of(atom(elem, elemKind)), null, null);
        ev(currentModule(), bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, List.of(),
            atom(elem, elemKind), null);
        if (elem == MISSING) {
            if (nullable) {
                return null;
            }
            return MISSING;
        }
        return elem;
    }

    /** The ARRAY_ELEMENT_ASSIGNMENT/ARRAY_ELEMENT_DELETE cells. */
    public static void arrayBounds(String bKey, String bDigest, String bParent,
                                   Object input, long index, long length, String desc,
                                   String staticKind, boolean isAssign, String origin) {
        ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
            List.of(atom(input, staticKind)), null, null);
        if (index < 0 || index > length) {
            DealError e = fail("E8002", "array index out of bounds", origin, null, null);
            ev(currentModule(), bKey, "FAILURE", "BOUNDARY", bDigest, bParent, List.of(),
                null, errtext(e));
            throw e;
        }
        if (isAssign) {
            bcheck(desc, staticKind, input);
        }
        ev(currentModule(), bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, List.of(),
            atom(input, staticKind), null);
    }

    /** The ARRAY_ELEMENT_DELETE cell whose input operand is the normalized slot. */
    public static void arrayBoundsSlot(String bKey, String bDigest, String bParent,
                                       long[] slot, long length, String origin) {
        long index = slot[0];
        String slotAtom = "slot:" + index + "/" + (index < length) + "/"
            + (slot[2] == 1 && index == length);
        ev(currentModule(), bKey, "START", "BOUNDARY", bDigest, bParent,
            List.of(slotAtom), null, null);
        if (index < 0 || index > length) {
            DealError e = fail("E8002", "array index out of bounds", origin, null, null);
            ev(currentModule(), bKey, "FAILURE", "BOUNDARY", bDigest, bParent, List.of(),
                null, errtext(e));
            throw e;
        }
        ev(currentModule(), bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, List.of(),
            slotAtom, null);
    }

    /** The FOR_EACH op's own TYPE_DESCRIPTOR terminal check. */
    public static void foreachCheck(String opKey, String digest, String parent,
                                    String desc, Object elem, String origin) {
        if (elem == MISSING) {
            DealError e = fail("E8001", "expected " + desc + ", got missing", origin, desc,
                "missing");
            ev(currentModule(), opKey, "FAILURE", "FOR_EACH", digest, parent, List.of(),
                null, errtext(e));
            throw e;
        }
        try {
            bcheck(desc, desc, elem);
        } catch (DealError e) {
            ev(currentModule(), opKey, "FAILURE", "FOR_EACH", digest, parent, List.of(),
                null, errtext(e));
            throw e;
        }
    }

    /** The intrinsic conversion ladders (INT_CONVERSION/NUMBER_CONVERSION). */
    public static Object intConv(Object v, String kind, String opKey, String digest,
                                 String parent, String origin) {
        if (v == null) {
            DealError e = fail("E8001", "cannot convert null to int", origin, "int", "null");
            ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent,
                List.of(), null, errtext(e));
            throw e;
        }
        if (v instanceof Double doubleValue) {
            double d = doubleValue;
            if (Double.isNaN(d)) {
                DealError e = fail("E8001", "expected int, got NaN", origin, "int", "NaN");
                ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent,
                    List.of(), null, errtext(e));
                throw e;
            }
            if (Double.isInfinite(d)) {
                DealError e = fail("E8001", "expected int, got infinity", origin, "int",
                    "infinity");
                ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent,
                    List.of(), null, errtext(e));
                throw e;
            }
            if (d != Math.rint(d)) {
                DealError e = fail("E8001", "expected int, got non-integer number",
                    origin, "int", "non-integer number");
                ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent,
                    List.of(), null, errtext(e));
                throw e;
            }
            if (d < -2147483648d || d > 2147483647d) {
                DealError e = fail("E8004", "int out of range", origin, "int", "number");
                ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent,
                    List.of(), null, errtext(e));
                throw e;
            }
            return (long) d;
        }
        if (v instanceof Long) {
            return v;
        }
        DealError e = fail("E8001", "expected int, got " + actualOf(kind, v), origin, "int",
            actualOf(kind, v));
        ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, List.of(),
            null, errtext(e));
        throw e;
    }

    public static Object numConv(Object v, String kind, String opKey, String digest,
                                 String parent, String origin) {
        if (v == null) {
            DealError e = fail("E8001", "cannot convert null to number", origin, "number",
                "null");
            ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent,
                List.of(), null, errtext(e));
            throw e;
        }
        if (v instanceof Long longValue) {
            return (double) longValue;
        }
        if (v instanceof Double) {
            return v;
        }
        DealError e = fail("E8001", "expected number, got " + actualOf(kind, v), origin,
            "number", actualOf(kind, v));
        ev(currentModule(), opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, List.of(),
            null, errtext(e));
        throw e;
    }

    // =========================================================================
    // Module context
    // =========================================================================

    private static String module = "";

    public static void setModule(String moduleName) {
        module = moduleName;
    }

    public static String currentModule() {
        return module;
    }
}
