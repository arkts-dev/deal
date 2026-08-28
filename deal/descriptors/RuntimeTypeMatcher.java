package deal.descriptors;

import java.util.Objects;

/**
 * The canonical runtime check contract (design source
 * {@code canonical-type-system-and-runtime-descriptors} D4):
 * {@link #check(String, Object, RuntimeSourceLocation)} realizes the
 * pinned check table over one value-model seam and produces failures as
 * {@link RuntimeCheckFailure} values — never as raw exceptions.
 *
 * <p>The normative table (realized identically by the Lua runtime and
 * the JVM emitted {@code $check} in their own epics):</p>
 *
 * <table>
 *   <caption>RuntimeTypeMatcher check table</caption>
 *   <tr><th>Descriptor</th><th>Predicate</th><th>Failure</th></tr>
 *   <tr><td>{@code null}</td><td>backend null representation</td>
 *       <td>E8001 {@code expected null, got {actual}}</td></tr>
 *   <tr><td>{@code boolean}</td><td>backend boolean scalar predicate</td>
 *       <td>E8001 {@code expected boolean, got {actual}}</td></tr>
 *   <tr><td>{@code string}</td><td>backend string scalar predicate; the
 *       JVM additionally rejects unpaired surrogate code units</td>
 *       <td>E8001 {@code expected string, got {actual}}; sub-message
 *       {@code expected string, got string with unpaired surrogate code
 *       units}</td></tr>
 *   <tr><td>{@code number}</td><td>backend number scalar predicate</td>
 *       <td>E8001 {@code expected number, got {actual}}</td></tr>
 *   <tr><td>{@code int}</td><td>number carrier that is finite and
 *       integral, {@code -0} normalized to {@code 0}, then the
 *       safe-range gate ±(2^53-1)</td>
 *       <td>E8001 {@code expected int, got {actual}} (non-number, NaN,
 *       infinity, non-integer); E8004 {@code int out of safe
 *       range}</td></tr>
 *   <tr><td>{@code table}</td><td>backend table representation</td>
 *       <td>E8001 {@code expected table, got {actual}}</td></tr>
 *   <tr><td>{@code bytes}</td><td>the E6-pinned {@code check_bytes(value,
 *       range)} hook ({@code bytes | E8001}); no other predicate is
 *       consulted</td><td>the hook's own E8001 outcome, propagated
 *       unchanged</td></tr>
 *   <tr><td>{@code @...}</td><td>tagged class identity byte-equal to the
 *       parsed {@link DescriptorAst.ClassAtom} full text</td>
 *       <td>E8001 {@code expected instance of {D}, got {actual}}</td></tr>
 *   <tr><td>{@code [D]}</td><td>backend array representation; elements
 *       checked recursively in index order</td>
 *       <td>E8003 {@code array element {i} type mismatch} at the first
 *       failing index (non-arrays: E8001 {@code expected array, got
 *       {actual}})</td></tr>
 *   <tr><td>{@code ?D}</td><td>null representation passes; otherwise the
 *       inner check</td><td>the inner failure, propagated
 *       unchanged</td></tr>
 *   <tr><td>function</td><td>function wrapper whose carried canonical
 *       descriptor equals the parsed {@link DescriptorAst.FunctionAtom}
 *       text byte-for-byte</td>
 *       <td>E8010 {@code function signature mismatch: expected {D}, got
 *       {actual}}; E8001 {@code expected function} for a
 *       non-wrapper</td></tr>
 *   <tr><td>unparsable text</td><td>—</td>
 *       <td>E8001 {@code internal: cannot parse type descriptor: {text}}
 *       (defensive)</td></tr>
 * </table>
 *
 * <p>{@link #check(String, Object, RuntimeSourceLocation)} never throws
 * and never mutates the descriptor, the value, or the location: it
 * returns the unchanged checked value or a {@link RuntimeCheckFailure}
 * carrying the pinned code, the byte-exact pinned message, and the
 * location exactly as supplied.  Values are inspected only through the
 * {@link RuntimeValueModel} seam passed to the constructor — the matcher
 * performs no {@code instanceof}, cast, reflection, or any other direct
 * value inspection.  Recursion is bounded by the descriptor length:
 * every atom step consumes at least one scalar of the parsed descriptor,
 * so hostile input cannot grow the stack beyond the input size.</p>
 *
 * <p>An instance is immutable and thread-safe; one matcher serves any
 * number of checks against its model.</p>
 */
public final class RuntimeTypeMatcher {

    /**
     * The pinned int safe-range bound {@code 2^53 - 1} = 9007199254740991:
     * a finite integral number inside {@code [-BOUND, BOUND]} passes the
     * {@code int} row and anything outside raises E8004
     * {@code int out of safe range} — byte-identical to
     * {@code deal/runtime.lua}'s {@code check_int} and the JVM emitted
     * {@code checkInt}.
     */
    public static final double INT_SAFE_RANGE_BOUND = 9007199254740991.0d;

    /** The single value-model seam — the matcher's only value inspection point. */
    private final RuntimeValueModel model;

    /**
     * Constructs a matcher over the given value-model seam.
     *
     * @param model the single inspection point for backend-represented
     *              values (never {@code null})
     */
    public RuntimeTypeMatcher(RuntimeValueModel model) {
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    /**
     * Checks a backend-represented value against a canonical runtime type
     * descriptor per the pinned table.
     *
     * <p>The descriptor is parsed with T3's strict canonical grammar
     * ({@link CanonicalRuntimeTypeDescriptor#parse(String)}); unparsable
     * text — including every legacy spelling — fails defensively with
     * E8001 {@code internal: cannot parse type descriptor: {text}}.</p>
     *
     * @param descriptor canonical descriptor text (the strict grammar is
     *                   the only accepted dialect)
     * @param value      the backend-represented value, read only through
     *                   the seam
     * @param range      the optional {@code (file, line, column)}
     *                   location, carried on every failure; {@code null}
     *                   when no location is supplied
     * @return the unchanged checked value when it satisfies the table, or
     *         a {@link RuntimeCheckFailure} with the pinned code, the
     *         byte-exact pinned message, and {@code range} — never
     *         {@code null} for a non-null failure and never an exception
     */
    public Object check(String descriptor, Object value, RuntimeSourceLocation range) {
        DescriptorParseResult parsed = CanonicalRuntimeTypeDescriptor.parse(descriptor);
        if (parsed instanceof DescriptorSyntaxError) {
            // Defensive pinned row: unparsable text (including legacy
            // spellings and a null descriptor) never reaches a value check.
            return new RuntimeCheckFailure(
                "E8001",
                "internal: cannot parse type descriptor: " + String.valueOf(descriptor),
                range);
        }
        return checkAtom((DescriptorAst) parsed, value, range);
    }

    // =========================================================================
    // Atom dispatch (one step per atom; each step consumes >= 1 scalar)
    // =========================================================================

    private Object checkAtom(DescriptorAst atom, Object value, RuntimeSourceLocation range) {
        if (atom instanceof DescriptorAst.PrimitiveAtom primitive) {
            return checkPrimitive(primitive.name(), value, range);
        }
        if (atom instanceof DescriptorAst.ClassAtom cls) {
            return checkClass(cls.fullDescriptorText(), value, range);
        }
        if (atom instanceof DescriptorAst.ArrayAtom array) {
            return checkArray(array.element(), value, range);
        }
        if (atom instanceof DescriptorAst.NullableAtom nullable) {
            return checkNullable(nullable.inner(), value, range);
        }
        DescriptorAst.FunctionAtom fn = (DescriptorAst.FunctionAtom) atom;
        return checkFunction(fn, value, range);
    }

    // =========================================================================
    // Rows
    // =========================================================================

    /** The primitive rows: {@code null}, {@code boolean}, {@code string},
     * {@code number}, {@code int}, {@code bytes}, {@code table}. */
    private Object checkPrimitive(String name, Object value, RuntimeSourceLocation range) {
        switch (name) {
            case "null" -> {
                if (model.isNull(value)) {
                    return value;
                }
                return failure("E8001", "expected null, got " + actualKind(value), range);
            }
            case "boolean" -> {
                if (model.isBoolean(value)) {
                    return value;
                }
                return failure("E8001", "expected boolean, got " + actualKind(value), range);
            }
            case "string" -> {
                if (!model.isString(value)) {
                    return failure("E8001", "expected string, got " + actualKind(value), range);
                }
                if (hasUnpairedSurrogate(model.stringPayload(value))) {
                    // JVM-pinned sub-message (the JVM string representation
                    // contract: java.lang.String with no unpaired surrogate
                    // code units).
                    return failure("E8001",
                        "expected string, got string with unpaired surrogate code units", range);
                }
                return value;
            }
            case "number" -> {
                if (model.isNumber(value)) {
                    return value;
                }
                return failure("E8001", "expected number, got " + actualKind(value), range);
            }
            case "int" -> {
                return checkInt(value, range);
            }
            case "bytes" -> {
                // The E6-pinned seam: this row routes ONLY through the pinned
                // check_bytes(value, range) hook (bytes | E8001).  No other
                // predicate is consulted, and the hook's outcome — the checked
                // value or its own E8001 failure — drives the row.
                Object hookOutcome = model.checkBytes(value, range);
                if (hookOutcome instanceof RuntimeCheckFailure hookFailure) {
                    return hookFailure;
                }
                return value;
            }
            case "table" -> {
                if (model.isTable(value)) {
                    return value;
                }
                return failure("E8001", "expected table, got " + actualKind(value), range);
            }
            default -> {
                // Unreachable through the strict parser, which yields only the
                // seven canonical primitive keywords.  Defensive only.
                return failure("E8001", "unknown primitive type: " + name, range);
            }
        }
    }

    /**
     * The int row: number carrier that is finite and integral, {@code -0}
     * normalized to {@code 0}, then the safe-range gate.
     *
     * <p>Pinned E8001 sub-cases byte-identical to {@code deal/runtime.lua}'s
     * {@code check_int}: non-number carrier → {@code expected int, got
     * {actual}}; NaN → {@code expected int, got NaN}; infinity →
     * {@code expected int, got infinity}; non-integer →
     * {@code expected int, got non-integer number}.  A finite integral
     * number outside ±(2^53-1) raises E8004 {@code int out of safe
     * range}.</p>
     */
    private Object checkInt(Object value, RuntimeSourceLocation range) {
        if (!model.isNumber(value)) {
            return failure("E8001", "expected int, got " + actualKind(value), range);
        }
        double payload = model.numberPayload(value);
        if (payload != payload) {
            return failure("E8001", "expected int, got NaN", range);
        }
        payload = payload + 0.0d; // normalize -0 to 0 (LuaJIT check_int contract)
        if (Double.isInfinite(payload)) {
            return failure("E8001", "expected int, got infinity", range);
        }
        if (payload % 1.0d != 0.0d) {
            return failure("E8001", "expected int, got non-integer number", range);
        }
        if (payload > INT_SAFE_RANGE_BOUND || payload < -INT_SAFE_RANGE_BOUND) {
            return failure("E8004", "int out of safe range", range);
        }
        return value;
    }

    /** The class row: tagged class identity byte-equal to the atom's full text. */
    private Object checkClass(String atomText, Object value, RuntimeSourceLocation range) {
        String tag = model.classIdentityText(value);
        if (tag == null) {
            return failure("E8001",
                "expected instance of " + atomText + ", got " + actualKind(value), range);
        }
        if (!tag.equals(atomText)) {
            return failure("E8001",
                "expected instance of " + atomText + ", got " + tag, range);
        }
        return value;
    }

    /** The array row: elements checked recursively in index order; E8003 at
     * the first failing index (1-based, as pinned). */
    private Object checkArray(DescriptorAst element, Object value, RuntimeSourceLocation range) {
        if (!model.isArray(value)) {
            return failure("E8001", "expected array, got " + actualKind(value), range);
        }
        int length = model.arrayLength(value);
        for (int i = 0; i < length; i++) {
            Object result = checkAtom(element, model.arrayElementAt(value, i), range);
            if (result instanceof RuntimeCheckFailure) {
                // Pinned E8003 message: the 1-based index of the first
                // failing element, nothing else appended.
                return failure("E8003",
                    "array element " + (i + 1) + " type mismatch", range);
            }
        }
        return value;
    }

    /** The nullable row: the null representation passes; otherwise the inner
     * check, whose failure propagates unchanged. */
    private Object checkNullable(DescriptorAst inner, Object value, RuntimeSourceLocation range) {
        if (model.isNull(value)) {
            return value;
        }
        return checkAtom(inner, value, range);
    }

    /** The function row: the wrapper's carried canonical descriptor must
     * equal the parsed function atom text byte-for-byte. */
    private Object checkFunction(DescriptorAst.FunctionAtom fn, Object value,
                                 RuntimeSourceLocation range) {
        String carried = model.functionDescriptorText(value);
        if (carried == null) {
            return failure("E8001", "expected function", range);
        }
        // For parsed input, render reconstructs the function atom's own
        // canonical text byte-identically (render(parse(text)) == text).
        String expected = CanonicalRuntimeTypeDescriptor.render(fn);
        if (!carried.equals(expected)) {
            return failure("E8010",
                "function signature mismatch: expected " + expected + ", got " + carried,
                range);
        }
        return value;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static RuntimeCheckFailure failure(String code, String message,
                                               RuntimeSourceLocation range) {
        return new RuntimeCheckFailure(code, message, range);
    }

    /**
     * A deterministic backend-kind description for the {@code {actual}}
     * slots of the pinned messages, derived only from the seam's named
     * predicates (never from value structure beyond them).  The order
     * mirrors the Lua runtime's {@code type(v)} precedence for the
     * overlapping table-family shapes (table before array/class/function).
     */
    private String actualKind(Object value) {
        if (model.isNull(value)) {
            return "null";
        }
        if (model.isBoolean(value)) {
            return "boolean";
        }
        if (model.isNumber(value)) {
            return "number";
        }
        if (model.isString(value)) {
            return "string";
        }
        if (model.isTable(value)) {
            return "table";
        }
        if (model.isArray(value)) {
            return "array";
        }
        if (model.classIdentityText(value) != null) {
            return "class";
        }
        if (model.functionDescriptorText(value) != null) {
            return "function";
        }
        return "unknown";
    }

    /** The JVM-pinned unpaired-surrogate scan (mirrors the emitted
     * {@code __hasUnpairedSurrogate} helper byte-for-byte in behavior). */
    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
