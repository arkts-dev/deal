package deal.semantic;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnicodeScalars;

import java.util.List;
import java.util.Objects;

/**
 * The production JSON algorithm delegate of the {@code @jsonable} walks
 * (class-construction-jsonable-operations K-D8/K-D10/K-D11; ISSUE-0515):
 * the adapter wiring {@link ClassOpsExecutor}'s JSON algorithm delegate
 * seam (the {@link ClassOpsExecutor.JsonParser} parse arm and the
 * {@link ClassOpsExecutor.JsonStringifier} stringify arm) to E8's
 * {@link SharedStdlibSemantics} — the single production JSON algorithm
 * executor of {@code deal.semantic-ir/1} ({@code JSON_PARSE}/
 * {@code JSON_STRINGIFY}). This epic defines no second production JSON
 * algorithm: the adapter maps the executor's closed {@link
 * ClassOpsExecutor.Value} view onto {@link SharedStdlibSemantics.Value}
 * and back and delegates every parse/stringify computation to E8's
 * algorithm; the walker tests use a fixture delegate implementing
 * exactly the pinned rows, and this adapter's parity with the fixture
 * pins the same contract.
 *
 * <p><b>Parse.</b> The executor hands a scalar-valid input text
 * ({@link UnicodeScalars.Valid}); the adapter calls
 * {@link SharedStdlibSemantics#jsonParse} (the E8 {@code JSON_PARSE}
 * row — signed32 integer lexical mapping, duplicate keys keep the last
 * value and the first position, document order preserved) and maps the
 * parsed {@link SharedStdlibSemantics.Value} back into the executor's
 * view (null/bool/int/number/string/table/array — the parsed model is
 * always JSON-shaped, so no {@code Other} carrier ever appears; one is
 * a producer {@code IllegalStateException}, never a projection). A
 * syntax defect is the seam's {@link
 * ClassOpsExecutor.JsonParse.SyntaxFailure} (the
 * {@code JSON_FROM_CLASS} walk swallows it into language null).</p>
 *
 * <p><b>Stringify.</b> The executor hands one JSON-shaped value plus
 * the pinned-convention fieldPath prefix. Tables delegate straight to
 * {@link SharedStdlibSemantics#jsonStringify} over the mapped
 * first-insertion-order table; arrays and leaves wrap in a synthetic
 * single-entry table under the empty key (an identifier-derived table
 * key can never be empty, so no real key can collide), whose framing
 * is stripped off the emitted text ({@code {"":TEXT}} &#8594;
 * {@code TEXT} — E8's escaping of the wrapped value is exact, so the
 * unwrap adds no re-encoding). E8's internal failure path is the
 * dot-separated pre-order path of its row ({@code "a.1"}; an array
 * element appends the 0-based index, a table key appends the key); the
 * adapter rewrites it into the epic's pinned {@code JSON_TO_CLASS}
 * segment convention (a table key {@code k} appends {@code ".k"}, an
 * array element {@code i} appends {@code "[i]"}) by walking the
 * reported segments against the actual value structure — the current
 * container's kind decides each segment, so the rewrite is
 * unambiguous — and prefixes the caller's field path. The failure
 * {@code actual} token is E8's canonical token, passed through
 * verbatim.</p>
 *
 * <p><b>Origins.</b> E8's algorithms take an origin only for their
 * failure projections; the seam never projects (the walk swallows
 * parse failures and projects stringify failures itself at the call
 * origin), so the adapter passes the supplied origin through and no
 * projection escapes from E8 through this seam.</p>
 *
 * <p><b>Purity.</b> Stateless, deterministic, no host code: exactly
 * the mapped value in, the E8 algorithm, the mapped value out. The
 * adapter is the production delegate seam; the fixture delegate of the
 * walker tests pins the identical rows independently.</p>
 */
public final class JsonClassAlgorithmAdapter {

    private JsonClassAlgorithmAdapter() {
        // Static surface only; pure and stateless.
    }

    /**
     * The production parse arm of the executor's JSON algorithm seam:
     * {@code (UnicodeScalars.Valid) → ClassOpsExecutor.JsonParse} over
     * the E8 {@code JSON_PARSE} algorithm.
     *
     * @return the parser delegate (stateless, reusable)
     */
    public static ClassOpsExecutor.JsonParser parser() {
        return JsonClassAlgorithmAdapter::parse;
    }

    /**
     * The production stringify arm of the executor's JSON algorithm
     * seam: {@code (Value, fieldPathPrefix) → ClassOpsExecutor.JsonStringify}
     * over the E8 {@code JSON_STRINGIFY} algorithm.
     *
     * @return the stringifier delegate (stateless, reusable)
     */
    public static ClassOpsExecutor.JsonStringifier stringifier() {
        return JsonClassAlgorithmAdapter::stringify;
    }

    // =========================================================================
    // Parse
    // =========================================================================

    /** The seam-internal origin passed to E8 (the seam never projects it). */
    private static final SourceOrigin SEAM_ORIGIN = new SourceOrigin(
        "deal.semantic.JsonClassAlgorithmAdapter",
        new SourceSpan("deal.semantic.JsonClassAlgorithmAdapter", 1, 1, 1, 1, 0, 0),
        SourceOriginKind.SYNTHETIC, new deal.semantic.ir.AnchorId(0), null);

    /**
     * Parses one scalar-valid JSON text through the E8 algorithm and
     * maps the parsed value back into the executor's view.
     */
    private static ClassOpsExecutor.JsonParse parse(UnicodeScalars.Valid text) {
        Objects.requireNonNull(text, "text must not be null");
        SharedStdlibSemantics.Outcome<SharedStdlibSemantics.Value> outcome =
            SharedStdlibSemantics.jsonParse(SEAM_ORIGIN, text);
        if (outcome instanceof SharedStdlibSemantics.Outcome.Failure<?>) {
            // A JSON_PARSE_SYNTAX projection: the seam's syntax failure
            // (the walk swallows it into language null).
            return new ClassOpsExecutor.JsonParse.SyntaxFailure();
        }
        return new ClassOpsExecutor.JsonParse.Success(
            fromStdlib(((SharedStdlibSemantics.Outcome.Success<SharedStdlibSemantics.Value>)
                outcome).value()));
    }

    /** Maps one parsed E8 value into the executor's view (always JSON-shaped). */
    private static ClassOpsExecutor.Value fromStdlib(SharedStdlibSemantics.Value value) {
        return switch (value) {
            case SharedStdlibSemantics.Value.Null ignored ->
                ClassOpsExecutor.Value.Null.INSTANCE;
            case SharedStdlibSemantics.Value.Bool bool ->
                new ClassOpsExecutor.Value.Bool(bool.value());
            case SharedStdlibSemantics.Value.Int intValue ->
                new ClassOpsExecutor.Value.Int(intValue.value());
            case SharedStdlibSemantics.Value.Number number ->
                new ClassOpsExecutor.Value.Number(number.value());
            case SharedStdlibSemantics.Value.String string -> {
                if (!(string.scalar() instanceof UnicodeScalars.Valid valid)) {
                    throw new IllegalStateException("a parsed JSON string is always a "
                        + "valid scalar sequence; the E8 parse produced an invalid one "
                        + "— a producer defect, never a projection");
                }
                yield ClassOpsExecutor.Value.string(valid.carrier());
            }
            case SharedStdlibSemantics.Value.Table table -> {
                SemanticTable<ClassOpsExecutor.Value> mapped = new SemanticTable<>();
                for (String key : table.table().keys()) {
                    SemanticTable.Lookup<SharedStdlibSemantics.Value> lookup =
                        table.table().get(key);
                    if (!(lookup instanceof SemanticTable.Lookup.Present<
                            SharedStdlibSemantics.Value> present)) {
                        throw new IllegalStateException("a parsed JSON object key is always "
                            + "present; got Missing for '" + key + "' — a producer defect, "
                            + "never a projection");
                    }
                    mapped.put(key, fromStdlib(present.value()));
                }
                yield new ClassOpsExecutor.Value.Table(mapped);
            }
            case SharedStdlibSemantics.Value.Array array -> {
                java.util.List<ClassOpsExecutor.Value> mapped = new java.util.ArrayList<>(
                    array.elements().size());
                for (SharedStdlibSemantics.Value element : array.elements().elements()) {
                    mapped.add(fromStdlib(element));
                }
                yield new ClassOpsExecutor.Value.Array(SemanticArray.of(mapped));
            }
            case SharedStdlibSemantics.Value.Other other -> throw new IllegalStateException(
                "a parsed JSON value is always JSON-shaped; the E8 parse produced an Other "
                    + "carrier " + other.actualKind() + " — a producer defect, never a "
                    + "projection");
        };
    }

    // =========================================================================
    // Stringify
    // =========================================================================

    /**
     * Stringifies one JSON-shaped executor value through the E8
     * algorithm and rewrites the failure path into the pinned
     * {@code JSON_TO_CLASS} segment convention.
     */
    private static ClassOpsExecutor.JsonStringify stringify(
            ClassOpsExecutor.Value value, String fieldPathPrefix) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(fieldPathPrefix, "fieldPathPrefix must not be null");
        SharedStdlibSemantics.Value mapped = toStdlib(value);
        if (mapped instanceof SharedStdlibSemantics.Value.Table table) {
            return stringifyTable(table, fieldPathPrefix, value);
        }
        // Arrays and leaves: the synthetic empty-key wrapper table
        // (no real identifier-derived key can be empty, so no key can
        // collide); E8's exact text of the wrapped value is unwrapped
        // and E8's failure path is relative to the wrapped value itself
        // (the empty prefix contributes nothing), so the rewrite is the
        // uniform table-case rewrite.
        SemanticTable<SharedStdlibSemantics.Value> wrapper = new SemanticTable<>();
        wrapper.put("", mapped);
        ClassOpsExecutor.JsonStringify rendered = stringifyTable(
            new SharedStdlibSemantics.Value.Table(wrapper), fieldPathPrefix, value);
        if (rendered instanceof ClassOpsExecutor.JsonStringify.Success success) {
            String text = success.text().carrier();
            // {"":TEXT} -> TEXT (the 4-char framing prefix {"": and the
            // 1-char closing brace). A substring of a valid scalar
            // sequence is always a valid scalar sequence; anything else
            // is a producer defect.
            UnicodeScalars.ScalarString unwrapped =
                UnicodeScalars.validate(text.substring(4, text.length() - 1));
            if (!(unwrapped instanceof UnicodeScalars.Valid valid)) {
                throw new IllegalStateException("the unwrapped stringify framing of the "
                    + "empty-key wrapper is always a valid scalar sequence; got an "
                    + "invalid one — a producer defect, never a projection");
            }
            return new ClassOpsExecutor.JsonStringify.Success(valid);
        }
        return rendered;
    }

    /** The shared table-case stringify: E8's algorithm plus the path rewrite. */
    private static ClassOpsExecutor.JsonStringify stringifyTable(
            SharedStdlibSemantics.Value.Table table, String fieldPathPrefix,
            ClassOpsExecutor.Value original) {
        SharedStdlibSemantics.Outcome<SharedStdlibSemantics.Value> outcome =
            SharedStdlibSemantics.jsonStringify(SEAM_ORIGIN, table.table());
        if (outcome instanceof SharedStdlibSemantics.Outcome.Success<
                SharedStdlibSemantics.Value> success
                && success.value() instanceof SharedStdlibSemantics.Value.String text
                && text.scalar() instanceof UnicodeScalars.Valid valid) {
            return new ClassOpsExecutor.JsonStringify.Success(valid);
        }
        if (outcome instanceof SharedStdlibSemantics.Outcome.Failure<?> failure) {
            String relative = failure.failure().failure().metadata().get("fieldPath");
            String actual = failure.failure().failure().metadata().get("actual");
            return new ClassOpsExecutor.JsonStringify.Failure(
                fieldPathPrefix + rewritePath(relative, original),
                actual == null ? "" : actual);
        }
        throw new IllegalStateException("the E8 JSON_STRINGIFY algorithm returned neither "
            + "a valid scalar string nor a JSON_TO_ERROR projection — a producer defect, "
            + "never a projection");
    }

    /** Maps one executor JSON-shaped value into the E8 view, fail closed. */
    private static SharedStdlibSemantics.Value toStdlib(ClassOpsExecutor.Value value) {
        return switch (value) {
            case ClassOpsExecutor.Value.Null ignored ->
                SharedStdlibSemantics.Value.Null.INSTANCE;
            case ClassOpsExecutor.Value.Bool bool ->
                new SharedStdlibSemantics.Value.Bool(bool.value());
            case ClassOpsExecutor.Value.Int intValue ->
                new SharedStdlibSemantics.Value.Int(intValue.value());
            case ClassOpsExecutor.Value.Number number ->
                new SharedStdlibSemantics.Value.Number(number.value());
            case ClassOpsExecutor.Value.String string ->
                SharedStdlibSemantics.Value.string(
                    ((UnicodeScalars.Valid) string.scalar()).carrier());
            case ClassOpsExecutor.Value.Table table -> {
                SemanticTable<SharedStdlibSemantics.Value> mapped = new SemanticTable<>();
                for (String key : table.table().keys()) {
                    SemanticTable.Lookup<ClassOpsExecutor.Value> lookup =
                        table.table().get(key);
                    if (!(lookup instanceof SemanticTable.Lookup.Present<
                            ClassOpsExecutor.Value> present)) {
                        throw new IllegalStateException("a table key is always present; got "
                            + "Missing for '" + key + "' — a producer defect, never a "
                            + "projection");
                    }
                    mapped.put(key, toStdlib(present.value()));
                }
                yield new SharedStdlibSemantics.Value.Table(mapped);
            }
            case ClassOpsExecutor.Value.Array array -> {
                java.util.List<SharedStdlibSemantics.Value> mapped =
                    new java.util.ArrayList<>(array.array().size());
                for (ClassOpsExecutor.Value element : array.array().elements()) {
                    mapped.add(toStdlib(element));
                }
                yield SharedStdlibSemantics.Value.array(mapped);
            }
            default -> throw new IllegalArgumentException("the stringify seam receives "
                + "JSON-shaped values only; got " + value.actualKind() + " — a class, "
                + "function, or missing value reaching the production delegate is a "
                + "producer defect (the executor recurses over classes itself and fails "
                + "non-JSON-shaped field values before the seam)");
        };
    }

    /**
     * Rewrites one E8-relative dot-separated failure path into the
     * pinned {@code JSON_TO_CLASS} segment convention by walking the
     * reported segments against the actual value structure: at each
     * step the current container's kind decides the segment — a table
     * segment is a key (append {@code ".k"}), an array segment is an
     * index (append {@code "[i]"}) — so the rewrite is unambiguous
     * even for numeric table keys.
     */
    private static String rewritePath(String relative, ClassOpsExecutor.Value original) {
        if (relative == null || relative.isEmpty()) {
            return "";
        }
        String[] segments = relative.split("\\.");
        ClassOpsExecutor.Value current = original;
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (current instanceof ClassOpsExecutor.Value.Table table) {
                out.append('.').append(segment);
                SemanticTable.Lookup<ClassOpsExecutor.Value> lookup =
                    table.table().get(segment);
                if (lookup instanceof SemanticTable.Lookup.Present<ClassOpsExecutor.Value>
                        present) {
                    current = present.value();
                    continue;
                }
                current = null;
            } else if (current instanceof ClassOpsExecutor.Value.Array array) {
                out.append('[').append(segment).append(']');
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException notAnIndex) {
                    current = null;
                    continue;
                }
                if (index < 0 || index >= array.array().size()) {
                    current = null;
                    continue;
                }
                current = array.array().elementAt(index);
            } else {
                // A segment past a leaf (an E8 path shape this value
                // cannot produce): keep the reported spelling verbatim.
                out.append('.').append(segment);
                current = null;
            }
        }
        return out.toString();
    }
}
