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
     * pinned failure position (the pinned walk depth bound, the unsupported
     * carriers, and the walk family's cycle arm for a path-local container
     * re-entry, all in walk order), and E8 emits the exact text of every
     * clean value. The pre-walk continues the enclosing class/declared-array
     * walk's path-local container-identity set: it enters its own
     * table/array containers on that same set and leaves every entry it did
     * not add, so a table-subtree position that re-enters an already-entered
     * declared-array container selects the cycle arm at the re-entry exactly
     * like the emitted Lua/JVM walks. A value with a selected failure is
     * never mapped through E8: the mapping would traverse contents behind
     * the selected failure, and those contents may include a container
     * re-entry (or an arbitrarily long chain) the mapping cannot represent.
     */
    private static ClassOpsExecutor.JsonStringify stringify(
            ClassOpsExecutor.Value value, String fieldPathPrefix, int depth,
            Set<Object> entered) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(fieldPathPrefix, "fieldPathPrefix must not be null");
        Objects.requireNonNull(entered, "entered must not be null");
        FirstFailure first = firstFailure(value, depth, entered);
        if (first != null) {
            // A selected failure: the pre-walk is its sole authority. The
            // cycle needle's own closed marker selects the walk family's
            // cycle arm; every other first failure selects the walk arm at
            // its pinned position. Contents behind the selected failure
            // (including any later cycle) stay untraversed.
            return new ClassOpsExecutor.JsonStringify.Failure(
                fieldPathPrefix + first.pinnedPath(), first.actual(), first.cycle());
        }
        // No selected failure: the pre-walk's own closed conditions leave
        // only acyclic JSON-shaped data (every container is entered once on
        // its path and every leaf is a JSON-shaped carrier), so the mapping
        // terminates and E8 emits the exact text of the whole value.
        SharedStdlibSemantics.Value mapped = toStdlib(value);
        if (mapped instanceof SharedStdlibSemantics.Value.Table table) {
            return stringifyE8(table, fieldPathPrefix, false);
        }
        // Arrays and leaves: the synthetic empty-key wrapper table
        // (no real identifier-derived key can be empty, so no key can
        // collide); E8's exact text of the wrapped value is unwrapped
        // and E8's failure path is relative to the wrapped value itself
        // (the empty prefix contributes nothing).
        SemanticTable<SharedStdlibSemantics.Value> wrapper = new SemanticTable<>();
        wrapper.put("", mapped);
        return stringifyE8(new SharedStdlibSemantics.Value.Table(wrapper),
            fieldPathPrefix, true);
    }

    /**
     * The E8 stringify over one clean mapped value: {@code e8Root} is
     * the table E8 stringifies (the mapped table itself, or the
     * synthetic empty-key wrapper for arrays/leaves). On success the
     * emitted text is returned (the wrapper's {@code {"":…}} framing
     * stripped). A failure here is a fail-closed divergence: the
     * pre-walk selected no failure over the identical structure, so E8
     * may not project one.
     */
    private static ClassOpsExecutor.JsonStringify stringifyE8(
            SharedStdlibSemantics.Value.Table e8Root, String fieldPathPrefix,
            boolean wrapped) {
        SharedStdlibSemantics.Outcome<SharedStdlibSemantics.Value> outcome =
            SharedStdlibSemantics.jsonStringify(SEAM_ORIGIN, e8Root.table());
        if (outcome instanceof SharedStdlibSemantics.Outcome.Success<
                SharedStdlibSemantics.Value> success
                && success.value() instanceof SharedStdlibSemantics.Value.String text
                && text.scalar() instanceof UnicodeScalars.Valid valid) {
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
            // The pre-walk selected no failure over the identical structure,
            // so an E8 projection here is a walk divergence: a producer
            // defect, never a product failure. Only the pre-walk selects a
            // failure (the E8 failure's internal {fieldPath} metadata stays
            // an internal position aid and is never projected).
            throw new IllegalStateException("the E8 JSON_STRINGIFY algorithm projected "
                + "a JSON_TO_ERROR failure at "
                + failure.failure().failure().metadata().get("fieldPath") + " but the "
                + "adapter's first-failure pre-walk over the identical structure found "
                + "none — a walk divergence is a producer defect, never a projection");
        }
        throw new IllegalStateException("the E8 JSON_STRINGIFY algorithm returned neither "
            + "a valid scalar string nor a JSON_TO_ERROR projection — a producer defect, "
            + "never a projection");
    }

    /**
     * Maps one clean executor value into the E8 view: JSON-shaped values map
     * to the same carriers, and the closed scalar classification is preserved
     * (never cast to Valid). Called only after the pre-walk selected no
     * failure, so every container is entered at most once on its path: the
     * mapping terminates and the unsupported carriers (functions, bytes,
     * classes, the internal missing view, invalid scalars, nonfinite numbers)
     * cannot occur.
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
                SharedStdlibSemantics.Value.string(string.scalar());
            case ClassOpsExecutor.Value.Table table -> {
                SemanticTable<SharedStdlibSemantics.Value> mapped = new SemanticTable<>();
                for (String key : table.table().keys()) {
                    SemanticTable.Lookup<ClassOpsExecutor.Value> lookup =
                        table.table().get(key);
                    if (!(lookup instanceof SemanticTable.Lookup.Present<
                            ClassOpsExecutor.Value> present)) {
                        throw new IllegalStateException("a table key is always "
                            + "present; got Missing for '" + key + "' — a "
                            + "producer defect, never a projection");
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
     * root, {@code actual} the closed typed-boundary token of the
     * offending value, and {@code cycle} true exactly when the failure
     * is an identity-based container re-entry (the needle's own closed
     * marker selects the walk family's cycle arm). A selected failure
     * is final: the pre-walk stops and the value behind it is never
     * traversed or mapped.
     */
    private static final class FirstFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final String pinnedPath;
        final String actual;
        final boolean cycle;

        FirstFailure(String pinnedPath, String actual, boolean cycle) {
            super("value at " + pinnedPath + " is not JSON serializable: " + actual);
            this.pinnedPath = pinnedPath;
            this.actual = actual;
            this.cycle = cycle;
        }

        String pinnedPath() {
            return pinnedPath;
        }

        String actual() {
            return actual;
        }

        boolean cycle() {
            return cycle;
        }
    }

    /**
     * Walks one executor value in the {@code JSON_STRINGIFY} walk order
     * to find the first failing position without parsing E8's ambiguous
     * dot-joined failure path back (a dotted table key like {@code a.0}
     * cannot be told apart from the array element 0 under key {@code a}
     * from the string alone). The pre-walk uses the same pre-order
     * (table keys in first-insertion order, array elements in index
     * order), the same failure conditions (nonfinite numbers,
     * invalid-scalar strings, the unsupported carriers, and
     * identity-based path-local container re-entry), and the same
     * depth-first traversal as E8's walk, and records the failing
     * position in the pinned {@code JSON_TO_CLASS} segment convention
     * (a table key {@code k} appends {@code ".k"}, an array element
     * {@code i} appends {@code "[i]"}). The {@code depth} of the entry value
     * is the enclosing class walk's depth, and each nested container
     * consumes one level, exactly like the walk's own table-subtree bound:
     * a container past {@link ClassOpsExecutor#JSON_MAX_DEPTH} fails with its
     * own carrier token at its pinned path. The {@code path} set is the
     * enclosing class/declared-array walk's own path-local identity set
     * (never a fresh subtree-local set): the pre-walk enters its containers
     * on that set and removes exactly the entries it added, so a table
     * position that re-enters an already-entered declared-array container
     * selects the cycle arm there, exactly like the emitted Lua/JVM walks.
     * A selected failure ends the walk
     * (the value behind it stays untraversed), so a cycle later in the
     * walk order never reaches the mapping.
     *
     */
    private static FirstFailure firstFailure(ClassOpsExecutor.Value root, int depth,
                                             Set<Object> path) {
        try {
            walkForFirstFailure(root, "", path, depth);
            return null;
        } catch (FirstFailure first) {
            return first;
        }
    }

    /** One depth-first pre-order step of the first-failure pre-walk (the E8 traversal). */
    private static void walkForFirstFailure(ClassOpsExecutor.Value value,
                                            String pinnedPath, Set<Object> path,
                                            int depth) {
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
                    throw new FirstFailure(pinnedPath,
                        FailureProjections.typedBoundaryToken(ActualKind.NUMBER, null),
                        false);
                }
            }
            case ClassOpsExecutor.Value.String string -> {
                if (!(string.scalar() instanceof UnicodeScalars.Valid)) {
                    throw new FirstFailure(pinnedPath,
                        FailureProjections.typedBoundaryToken(
                            ActualKind.INVALID_UNICODE, null), false);
                }
            }
            case ClassOpsExecutor.Value.Table table -> {
                // The depth bound precedes the cycle needle at this container
                // (the exceeding container is its own failure, exactly like
                // the walk's table-subtree bound before it moved here).
                if (depth > ClassOpsExecutor.JSON_MAX_DEPTH) {
                    throw new FirstFailure(pinnedPath,
                        FailureProjections.typedBoundaryToken(ActualKind.TABLE, null),
                        false);
                }
                if (!path.add(table.table())) {
                    throw new FirstFailure(pinnedPath,
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
                            path, depth + 1);
                    }
                } finally {
                    path.remove(table.table());
                }
            }
            case ClassOpsExecutor.Value.Array array -> {
                if (depth > ClassOpsExecutor.JSON_MAX_DEPTH) {
                    throw new FirstFailure(pinnedPath,
                        FailureProjections.typedBoundaryToken(ActualKind.ARRAY, null),
                        false);
                }
                if (!path.add(array.array())) {
                    throw new FirstFailure(pinnedPath,
                        FailureProjections.typedBoundaryToken(ActualKind.ARRAY, null),
                        true);
                }
                try {
                    for (int i = 0; i < array.array().size(); i++) {
                        walkForFirstFailure(array.array().elementAt(i),
                            pinnedPath + "[" + i + "]", path, depth + 1);
                    }
                } finally {
                    path.remove(array.array());
                }
            }
            case ClassOpsExecutor.Value.Function ignored -> throw new FirstFailure(
                pinnedPath,
                FailureProjections.typedBoundaryToken(ActualKind.FUNCTION, null), false);
            case ClassOpsExecutor.Value.Bytes ignored -> throw new FirstFailure(
                pinnedPath,
                FailureProjections.typedBoundaryToken(ActualKind.BYTES, null), false);
            case ClassOpsExecutor.Value.Class instance -> throw new FirstFailure(
                pinnedPath,
                FailureProjections.typedBoundaryToken(ActualKind.CLASS,
                    instance.classId().text()),
                false);
            case ClassOpsExecutor.Value.Missing ignored -> throw new FirstFailure(
                pinnedPath,
                FailureProjections.typedBoundaryToken(ActualKind.MISSING, null), false);
        }
    }
}
