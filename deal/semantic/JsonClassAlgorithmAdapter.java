package deal.semantic;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnicodeScalars;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JsonClassAlgorithmAdapter {

    private JsonClassAlgorithmAdapter() {
        // Static surface only; pure and stateless.
    }

    /**
     * The production parse arm of the executor's JSON algorithm seam:
     * {@code (UnicodeScalars.Valid) → ClassOpsExecutor.JsonParse} over
     * the E8 {@code JSON_PARSE} algorithm.
     *
     */
    public static ClassOpsExecutor.JsonParser parser() {
        return JsonClassAlgorithmAdapter::parse;
    }

    /**
     * The production stringify arm of the executor's JSON algorithm
     * seam: {@code (Value, fieldPathPrefix) → ClassOpsExecutor.JsonStringify}
     * over the E8 {@code JSON_STRINGIFY} algorithm.
     *
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
     * algorithm: the segment-aware first-failure pre-walk decides the
     * pinned failure position (and short-circuits cyclic containers,
     * which the mapping cannot represent), and E8 emits the exact text
     * of every clean value.
     */
    private static ClassOpsExecutor.JsonStringify stringify(
            ClassOpsExecutor.Value value, String fieldPathPrefix) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(fieldPathPrefix, "fieldPathPrefix must not be null");
        FirstFailure first = firstFailure(value);
        if (first != null && first.cycle()) {
            // A cyclic container: the needle's own fact selects the walk
            // family's cycle arm, and the mapping cannot represent the
            // cycle — the pre-walk is the sole authority (the fixture
            // delegate's identical walk pins the same arm).
            return new ClassOpsExecutor.JsonStringify.Failure(
                fieldPathPrefix + first.pinnedPath(), first.actual(), true);
        }
        SharedStdlibSemantics.Value mapped = toStdlib(value);
        if (mapped instanceof SharedStdlibSemantics.Value.Table table) {
            return stringifyE8(table, fieldPathPrefix, false, first);
        }
        // Arrays and leaves: the synthetic empty-key wrapper table
        // (no real identifier-derived key can be empty, so no key can
        // collide); E8's exact text of the wrapped value is unwrapped
        // and E8's failure path is relative to the wrapped value itself
        // (the empty prefix contributes nothing), so the pre-walk over
        // the direct value cross-checks against E8 uniformly.
        SemanticTable<SharedStdlibSemantics.Value> wrapper = new SemanticTable<>();
        wrapper.put("", mapped);
        return stringifyE8(new SharedStdlibSemantics.Value.Table(wrapper),
            fieldPathPrefix, true, first);
    }

    /**
     * The E8 stringify over one clean mapped value: {@code e8Root} is
     * the table E8 stringifies (the mapped table itself, or the
     * synthetic empty-key wrapper for arrays/leaves). On success the
     * emitted text is returned (the wrapper's {@code {"":…}} framing
     * stripped); on failure the pre-walk's pinned path and actual token
     * are projected after a fail-closed cross-check of the walk position
     * against the E8 failure's internal {@code fieldPath} metadata (the
     * arm's declared actual token follows its own carrier-kind
     * convention, so the two arms' tokens are not compared).
     */
    private static ClassOpsExecutor.JsonStringify stringifyE8(
            SharedStdlibSemantics.Value.Table e8Root, String fieldPathPrefix,
            boolean wrapped, FirstFailure first) {
        SharedStdlibSemantics.Outcome<SharedStdlibSemantics.Value> outcome =
            SharedStdlibSemantics.jsonStringify(SEAM_ORIGIN, e8Root.table());
        if (outcome instanceof SharedStdlibSemantics.Outcome.Success<
                SharedStdlibSemantics.Value> success
                && success.value() instanceof SharedStdlibSemantics.Value.String text
                && text.scalar() instanceof UnicodeScalars.Valid valid) {
            if (first != null) {
                throw new IllegalStateException("the adapter's first-failure pre-walk "
                    + "found a failure at " + first.e8Path() + " but the E8 "
                    + "JSON_STRINGIFY algorithm emitted text for the identical structure "
                    + "— a walk divergence is a producer defect, never a projection");
            }
            if (!wrapped) {
                return new ClassOpsExecutor.JsonStringify.Success(valid);
            }
            // {"":TEXT} -> TEXT (the 4-char framing prefix {"": and the
            // 1-char closing brace). A substring of a valid scalar
            // sequence is always a valid scalar sequence; anything else
            // is a producer defect.
            String carrier = valid.carrier();
            UnicodeScalars.ScalarString unwrapped =
                UnicodeScalars.validate(carrier.substring(4, carrier.length() - 1));
            if (!(unwrapped instanceof UnicodeScalars.Valid unwrappedValid)) {
                throw new IllegalStateException("the unwrapped stringify framing of the "
                    + "empty-key wrapper is always a valid scalar sequence; got an "
                    + "invalid one — a producer defect, never a projection");
            }
            return new ClassOpsExecutor.JsonStringify.Success(unwrappedValid);
        }
        if (outcome instanceof SharedStdlibSemantics.Outcome.Failure<?> failure) {
            // The walk's internal position metadata (the JSON_TO_ERROR row's
            // fieldPath key): the E8 arm publishes the carrier-kind actual
            // and no walk position, and the two arms' actual conventions
            // differ by declaration (the walk arm's canonical tokens vs the
            // std/json arm's carrier-kind tokens), so the divergence guard
            // compares the projection-independent position. The pre-walk
            // owns both the pinned path and the actual token it returns.
            String reportedPath = failure.failure().failure().metadata().get("fieldPath");
            if (first == null) {
                throw new IllegalStateException("the E8 JSON_STRINGIFY algorithm "
                    + "projected a JSON_TO_ERROR failure at " + reportedPath + " but the "
                    + "adapter's first-failure pre-walk over the identical structure found "
                    + "none — a walk divergence is a producer defect, never a projection");
            }
            if (reportedPath == null || !first.e8Path().equals(reportedPath)) {
                throw new IllegalStateException("the adapter's first-failure pre-walk and "
                    + "the E8 JSON_STRINGIFY algorithm disagree on the failing position "
                    + "(pre-walk " + first.e8Path() + " vs E8 " + reportedPath + ") — a "
                    + "walk divergence is a producer defect, never a projection");
            }
            return new ClassOpsExecutor.JsonStringify.Failure(
                fieldPathPrefix + first.pinnedPath(), first.actual());
        }
        throw new IllegalStateException("the E8 JSON_STRINGIFY algorithm returned neither "
            + "a valid scalar string nor a JSON_TO_ERROR projection — a producer defect, "
            + "never a projection");
    }

    /**
     * Maps one executor value into the E8 view. JSON-shaped values map
     * to the same carriers; the unsupported carriers a table-typed (or
     * array-nested) runtime value may contain map to E8's projections
     * instead of throwing: a function, a class instance, and the
     * internal missing view become E8's {@code Value.Other} carriers
     * with their canonical actual kinds (the closed typed-boundary
     * projection's own classification), and an invalid-scalar string
     * maps with its classification preserved — E8's stringify walk then
     * projects each as the pinned {@code JSON_TO_ERROR} failure via the
     * seam's {@code Failure(fieldPath, actual)} terminal. Only clean
     * values reach this mapping (the pre-walk short-circuits every
     * failing value, cyclic containers included, first).
     */
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
                // The closed scalar classification is preserved (never
                // cast to Valid): E8's stringify walk projects an
                // Invalid carrier with the pinned invalid-unicode token.
                SharedStdlibSemantics.Value.string(string.scalar());
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
            case ClassOpsExecutor.Value.Function ignored ->
                new SharedStdlibSemantics.Value.Other(ActualKind.FUNCTION, null);
            case ClassOpsExecutor.Value.Bytes ignored ->
                // Bytes are not JSON serializable: the pinned rejection
                // projection carries the canonical bytes actual token
                // (K6/K8).
                new SharedStdlibSemantics.Value.Other(ActualKind.BYTES, null);
            case ClassOpsExecutor.Value.Class instance ->
                new SharedStdlibSemantics.Value.Other(ActualKind.CLASS,
                    instance.classId().text());
            case ClassOpsExecutor.Value.Missing ignored ->
                new SharedStdlibSemantics.Value.Other(ActualKind.MISSING, null);
        };
    }

    // =========================================================================
    // The segment-aware first-failure pre-walk (the pinned failure position)
    // =========================================================================

    /**
     * One first-failure position of the adapter's pre-walk of the
     * stringify rows: {@code pinnedPath} in the pinned
     * {@code JSON_TO_CLASS} segment convention relative to the walked
     * root, {@code e8Path} in E8's own dot-joined spelling,
     * {@code actual} the closed typed-boundary token of the offending
     * value, and {@code cycle} true exactly when the failure is an
     * identity-based container re-entry (the mapping cannot represent
     * that value, so the pre-walk is the sole authority and its own
     * closed marker selects the walk family's cycle arm).
     */
    private static final class FirstFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final String pinnedPath;
        final String e8Path;
        final String actual;
        final boolean cycle;

        FirstFailure(String pinnedPath, String e8Path, String actual, boolean cycle) {
            super("value at " + pinnedPath + " is not JSON serializable: " + actual);
            this.pinnedPath = pinnedPath;
            this.e8Path = e8Path;
            this.actual = actual;
            this.cycle = cycle;
        }

        String pinnedPath() {
            return pinnedPath;
        }

        String e8Path() {
            return e8Path;
        }

        String actual() {
            return actual;
        }

        boolean cycle() {
            return cycle;
        }
    }

    /**
     * Walks one executor value in E8's {@code JSON_STRINGIFY} walk
     * order to find the first failing position without parsing E8's
     * ambiguous dot-joined failure path back (a dotted table key like
     * {@code a.0} cannot be told apart from the array element 0 under
     * key {@code a} from the string alone). The pre-walk uses the same
     * pre-order (table keys in first-insertion order, array elements
     * in index order), the same failure conditions (nonfinite numbers,
     * invalid-scalar strings, the unsupported carriers, and
     * identity-based path-local container re-entry), and the same
     * depth-first traversal as E8's walk, and records both spellings
     * of the failing position: the pinned {@code JSON_TO_CLASS}
     * segments (a table key {@code k} appends {@code ".k"}, an array
     * element {@code i} appends {@code "[i]"}) and E8's own dot-joined
     * spelling, so the caller can cross-check the pre-walk against
     * E8's reported failing position (the row's internal
     * {@code fieldPath} metadata).
     *
     */
    private static FirstFailure firstFailure(ClassOpsExecutor.Value root) {
        Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            walkForFirstFailure(root, "", "", path);
            return null;
        } catch (FirstFailure first) {
            return first;
        }
    }

    /** One depth-first pre-order step of the first-failure pre-walk (the E8 traversal). */
    private static void walkForFirstFailure(ClassOpsExecutor.Value value,
                                            String pinnedPath, String e8Path,
                                            Set<Object> path) {
        switch (value) {
            case ClassOpsExecutor.Value.Null ignored -> {
                // A JSON-shaped leaf: no failure.
            }
            case ClassOpsExecutor.Value.Bool ignored -> {
                // A JSON-shaped leaf: no failure.
            }
            case ClassOpsExecutor.Value.Int ignored -> {
                // A JSON-shaped leaf: no failure.
            }
            case ClassOpsExecutor.Value.Number number -> {
                if (!Double.isFinite(number.value())) {
                    throw new FirstFailure(pinnedPath, e8Path,
                        FailureProjections.typedBoundaryToken(ActualKind.NUMBER, null),
                        false);
                }
            }
            case ClassOpsExecutor.Value.String string -> {
                if (!(string.scalar() instanceof UnicodeScalars.Valid)) {
                    throw new FirstFailure(pinnedPath, e8Path,
                        FailureProjections.typedBoundaryToken(
                            ActualKind.INVALID_UNICODE, null), false);
                }
            }
            case ClassOpsExecutor.Value.Table table -> {
                if (!path.add(table.table())) {
                    throw new FirstFailure(pinnedPath, e8Path,
                        FailureProjections.typedBoundaryToken(ActualKind.TABLE, null),
                        true);
                }
                try {
                    for (String key : table.table().keys()) {
                        SemanticTable.Lookup<ClassOpsExecutor.Value> lookup =
                            table.table().get(key);
                        if (!(lookup instanceof SemanticTable.Lookup.Present<
                                ClassOpsExecutor.Value> present)) {
                            throw new IllegalStateException("a table key returned by "
                                + "keys() is always present; got Missing for '" + key
                                + "' — a producer defect, never a projection");
                        }
                        walkForFirstFailure(present.value(), pinnedPath + "." + key,
                            e8Path.isEmpty() ? key : e8Path + "." + key, path);
                    }
                } finally {
                    path.remove(table.table());
                }
            }
            case ClassOpsExecutor.Value.Array array -> {
                if (!path.add(array.array())) {
                    throw new FirstFailure(pinnedPath, e8Path,
                        FailureProjections.typedBoundaryToken(ActualKind.ARRAY, null),
                        true);
                }
                try {
                    for (int i = 0; i < array.array().size(); i++) {
                        walkForFirstFailure(array.array().elementAt(i),
                            pinnedPath + "[" + i + "]",
                            e8Path.isEmpty() ? Integer.toString(i) : e8Path + "." + i,
                            path);
                    }
                } finally {
                    path.remove(array.array());
                }
            }
            case ClassOpsExecutor.Value.Function ignored -> throw new FirstFailure(
                pinnedPath, e8Path,
                FailureProjections.typedBoundaryToken(ActualKind.FUNCTION, null), false);
            case ClassOpsExecutor.Value.Bytes ignored -> throw new FirstFailure(
                pinnedPath, e8Path,
                FailureProjections.typedBoundaryToken(ActualKind.BYTES, null), false);
            case ClassOpsExecutor.Value.Class instance -> throw new FirstFailure(
                pinnedPath, e8Path,
                FailureProjections.typedBoundaryToken(ActualKind.CLASS,
                    instance.classId().text()),
                false);
            case ClassOpsExecutor.Value.Missing ignored -> throw new FirstFailure(
                pinnedPath, e8Path,
                FailureProjections.typedBoundaryToken(ActualKind.MISSING, null), false);
        }
    }
}
