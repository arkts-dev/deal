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
 *   <tr><td>{@code table}</td><td>backend table representation</td>
 *       <td>E8001 {@code expected table, got {actual}}</td></tr>
 *   <tr><td>{@code @...} and the builtin {@code Error} atom</td>
 *       <td>tagged class identity byte-equal to the parsed
 *       {@link DescriptorAst.ClassAtom} full text (the builtin
 *       {@code Error} carrier carries exactly {@code Error})</td>
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
 * location exactly as supplied. Values are
 * inspected only through the
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

    /** The single value-model seam — the matcher's only value inspection point. */
    private final RuntimeValueModel model;

    /**
     * Constructs a matcher over the given value-model seam.
     *
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
            if (nullable.admitsNull() && model.isNull(value)
                    || nullable.admitsUndefined() && model.isUndefined(value)) return value;
            return checkAtom(nullable.inner(), value, range);
        }
        DescriptorAst.FunctionAtom fn = (DescriptorAst.FunctionAtom) atom;
        return checkFunction(fn, value, range);
    }

    // =========================================================================
    // Rows
    // =========================================================================

    /** The primitive rows: {@code null}, {@code boolean}, {@code string},
     * {@code number}, {@code table}. */
    private Object checkPrimitive(String name, Object value, RuntimeSourceLocation range) {
        switch (name) {
            case "null" -> {
                if (model.isNull(value)) {
                    return value;
                }
                return failure("E8001", "expected null, got " + actualKind(value, range), range);
            }
            case "boolean" -> {
                if (model.isBoolean(value)) {
                    return value;
                }
                return failure("E8001", "expected boolean, got " + actualKind(value, range), range);
            }
            case "string" -> {
                if (!model.isString(value)) {
                    return failure("E8001", "expected string, got " + actualKind(value, range), range);
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
                return failure("E8001", "expected number, got " + actualKind(value, range), range);
            }
            case "undefined", "void" -> {
                if (model.isUndefined(value)) return value;
                return failure("E8001", "expected undefined, got " + actualKind(value, range), range);
            }
            case "unknown" -> {
                if (model.isString(value)) return checkPrimitive("string", value, range);
                if (model.isNull(value) || model.isUndefined(value) || model.isBoolean(value)
                        || model.isNumber(value) || model.isString(value) || model.isTable(value)
                        || model.isArray(value) || model.classIdentityText(value) != null
                        || model.functionDescriptorText(value) != null) return value;
                return failure("E8001", "expected unknown, got " + actualKind(value, range), range);
            }
            case "record" -> {
                if (model.isTable(value)) {
                    return value;
                }
                return failure("E8001", "expected table, got " + actualKind(value, range), range);
            }
            default -> {
                // Unreachable through the strict parser, which yields only the
                // canonical primitive keywords.  Defensive only.
                return failure("E8001", "unknown primitive type: " + name, range);
            }
        }
    }


    private Object checkClass(String atomText, Object value, RuntimeSourceLocation range) {
        String tag = model.classIdentityText(value);
        if (tag == null) {
            return failure("E8001",
                "expected instance of " + atomText + ", got " + actualKind(value, range), range);
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
            return failure("E8001", "expected array, got " + actualKind(value, range), range);
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
     * The typed-boundary {@code {actual}} projection of one failing check,
     * derived only from the seam's named predicates (never from value
     * structure beyond them): the closed tokens the shared typed-boundary
     * contract publishes, never a target carrier label.  Scalars project
     * their own tokens; a produced carrier projects its own closed token —
     * a class instance (the builtin {@code Error} included) its carried
     * canonical class atom, a function wrapper {@code function}, an array
     * {@code array}, the E6 bytes carrier {@code bytes} — and only a plain
     * backend table republishes {@code table}.
     *
     * <p>Carrier identity is read before the generic table predicate: a Lua
     * value mapping is a table for {@code type(v)}, so a table-first order
     * would republish every carrier as {@code table} and a class instance
     * would never publish its carried atom.  The bytes classification is
     * the E6-pinned {@code check_bytes(value, range)} hook itself (its
     * {@code bytes | E8001} contract makes the admitted outcome the bytes
     * fact); the seam carries no separate bytes predicate.  The bytes row
     * still routes exclusively through that hook and consults no other
     * predicate — this projection only classifies a value the row already
     * rejected.  A value outside every seam predicate is a producer defect,
     * so the defensive terminal is the seam's own {@code unknown} label,
     * never a fabricated closed token.</p>
     */
    private String actualKind(Object value, RuntimeSourceLocation range) {
        if (model.isUndefined(value)) return "undefined";
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
        String classAtom = model.classIdentityText(value);
        if (classAtom != null) {
            return classAtom;
        }
        if (model.functionDescriptorText(value) != null) {
            return "function";
        }
        if (model.isArray(value)) {
            return "array";
        }
        if (model.isTable(value)) {
            return "table";
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
