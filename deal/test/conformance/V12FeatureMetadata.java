package deal.test.conformance;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.descriptors.DescriptorAst;
import deal.descriptors.DescriptorParseResult;
import deal.descriptors.DescriptorSyntaxError;
import deal.diagnostics.DiagnosticCode;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The strict v1.2 feature-record metadata parser/validator (ISSUE-0157;
 * design source {@code deal-v1.2-directives-and-c-ffi-declarations}
 * D12's {@code V12FeatureMetadata} schema version 1).
 *
 * <p>One feature sidecar document carries the exact fields
 * {@code version}, {@code feature}, {@code spec}, {@code description},
 * {@code expected}, {@code backends}, {@code invocation}, optional
 * {@code oracle}, {@code support}, and optional {@code linkedRecord}.
 * The parser is strict and closed: unknown fields, wrong types, and
 * malformed values are {@link ParseFailure}s naming the offending
 * field; there is no silent default and no permissive fallback.</p>
 *
 * <p>Closed field shapes (schema version 1):</p>
 * <ul>
 *   <li>{@code version} — exactly the JSON integer 1.</li>
 *   <li>{@code feature} — a string naming one {@link FeatureId}.</li>
 *   <li>{@code spec} — a non-empty string (the spec section the record
 *       verifies, e.g. {@code spec-v1.2.md §Bytes}).</li>
 *   <li>{@code description} — a non-empty string.</li>
 *   <li>{@code expected} — the string {@code "compile-ok"} or
 *       {@code "runtime-ok"}, or a closed one-field object
 *       {@code {"compile-error": CODE}} / {@code {"runtime-error": CODE}}.
 *       A compile-error {@code CODE} must be a registered non-runtime
 *       {@link DiagnosticCode}; a runtime-error {@code CODE} must be a
 *       registered {@link DiagnosticCode.Phase#RUNTIME} code.</li>
 *   <li>{@code backends} — a non-empty ordered subset of the canonical
 *       order {@code [luajit, jvm]}: known names only, no duplicates,
 *       canonical order preserved.</li>
 *   <li>{@code invocation} — exactly {@code compile-only},
 *       {@code direct-main}, {@code synthetic-main}, or
 *       {@code async-export}.</li>
 *   <li>{@code oracle} — optional; a closed two-field object with
 *       {@code exportName} (DEAL identifier {@code [A-Za-z_][A-Za-z0-9_]*})
 *       and {@code functionDescriptor} (a canonical function descriptor
 *       accepted by E4's strict parser,
 *       {@link CanonicalRuntimeTypeDescriptor#parse(String)}, with
 *       complete input consumption — the signature-validation surface of
 *       acceptance criterion 3).</li>
 *   <li>{@code support} — an array of canonical catalog-relative paths
 *       (slash-separated, no leading slash, no {@code .}/{@code ..}
 *       segments, no backslash or NUL), without duplicates.</li>
 *   <li>{@code linkedRecord} — optional; a canonical feature-record id
 *       under the same path rules.</li>
 * </ul>
 *
 * <p>Exact conditional rules (D12):</p>
 * <ul>
 *   <li>Compile outcomes ({@code compile-ok}/{@code compile-error})
 *       require {@code compile-only} and forbid {@code oracle}.</li>
 *   <li>Runtime outcomes ({@code runtime-ok}/{@code runtime-error})
 *       forbid {@code compile-only}.</li>
 *   <li>{@code direct-main} forbids {@code oracle}.</li>
 *   <li>{@code synthetic-main} requires one oracle whose descriptor
 *       parses to the canonical sync form {@code ()->R} (zero
 *       parameters, non-async).</li>
 *   <li>{@code async-export} requires one oracle whose descriptor
 *       parses to the canonical async form {@code async()->R} (zero
 *       parameters, async).</li>
 * </ul>
 *
 * <p>The parser is deterministic and side-effect-free: it consumes the
 * sidecar text it is given, performs no I/O, and mutates nothing. It
 * does not decide backends or linked-record requirements — those are
 * the architecture-owned {@link FeatureBackendMatrix}'s domain (D12:
 * "the matrix—not each sidecar—selects mandatory backends and linked
 * records").</p>
 */
public final class V12FeatureMetadata {

    /** The only feature-metadata schema version this parser accepts. */
    public static final int METADATA_SCHEMA_VERSION = 1;

    /** The canonical backend order (D12: ordered subset of luajit, jvm). */
    public static final List<String> BACKEND_NAMES = List.of("luajit", "jvm");

    /** The exact invocation vocabulary (D12). */
    public static final List<String> INVOCATION_NAMES =
        List.of("compile-only", "direct-main", "synthetic-main", "async-export");

    private V12FeatureMetadata() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Closed vocabulary types
    // =========================================================================

    /**
     * The closed feature vocabulary of the architecture-owned matrix
     * (D12's {@code FeatureBackendMatrix} table).
     */
    public enum FeatureId {
        SIGNED_INT32("SIGNED_INT32"),
        BYTES_CORE("BYTES_CORE"),
        BYTES_DEFAULTS("BYTES_DEFAULTS"),
        BYTES_DESCRIPTORS("BYTES_DESCRIPTORS"),
        BYTES_SYNC_FUNCTION("BYTES_SYNC_FUNCTION"),
        BYTES_ASYNC_FUNCTION("BYTES_ASYNC_FUNCTION"),
        DIRECTIVES("DIRECTIVES"),
        DIAGNOSTIC_RANGE("DIAGNOSTIC_RANGE"),
        PROJECT_CONFIG("PROJECT_CONFIG"),
        C_FFI("C_FFI"),
        C_FFI_DECLARATION_ERROR("C_FFI_DECLARATION_ERROR");

        private final String jsonText;

        FeatureId(String jsonText) {
            this.jsonText = jsonText;
        }

        /** The exact JSON string spelling of the feature. */
        public String jsonText() {
            return jsonText;
        }

        /**
         * Returns the feature for an exact JSON spelling, or
         * {@link Optional#empty()} for an unknown string.
         */
        public static Optional<FeatureId> fromJson(String text) {
            for (FeatureId id : values()) {
                if (id.jsonText.equals(text)) {
                    return Optional.of(id);
                }
            }
            return Optional.empty();
        }
    }

    /** The closed invocation vocabulary (D12). */
    public enum Invocation {
        COMPILE_ONLY("compile-only"),
        DIRECT_MAIN("direct-main"),
        SYNTHETIC_MAIN("synthetic-main"),
        ASYNC_EXPORT("async-export");

        private final String jsonText;

        Invocation(String jsonText) {
            this.jsonText = jsonText;
        }

        /** The exact JSON string spelling of the invocation. */
        public String jsonText() {
            return jsonText;
        }

        /** True for the runtime-execution invocation modes. */
        public boolean isRuntimeInvocation() {
            return this != COMPILE_ONLY;
        }

        /**
         * Returns the invocation for an exact JSON spelling, or
         * {@link Optional#empty()} for an unknown string.
         */
        public static Optional<Invocation> fromJson(String text) {
            for (Invocation i : values()) {
                if (i.jsonText.equals(text)) {
                    return Optional.of(i);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The closed expectation outcome (D12): {@code compile-ok},
     * {@code runtime-ok}, {@code compile-error CODE}, or
     * {@code runtime-error CODE}.
     */
    public sealed interface ExpectedOutcome
            permits ExpectedOutcome.CompileOk,
                   ExpectedOutcome.RuntimeOk,
                   ExpectedOutcome.CompileError,
                   ExpectedOutcome.RuntimeError {

        /** True for a compile-side outcome ({@code compile-ok}/{@code compile-error}). */
        boolean isCompileOutcome();

        /** True for a runtime-side outcome ({@code runtime-ok}/{@code runtime-error}). */
        default boolean isRuntimeOutcome() {
            return !isCompileOutcome();
        }

        /** The JSON mode string: compile-ok, runtime-ok, compile-error, runtime-error. */
        String mode();

        /** A compile-ok expectation. */
        record CompileOk() implements ExpectedOutcome {
            @Override public boolean isCompileOutcome() { return true; }
            @Override public String mode() { return "compile-ok"; }
        }

        /** A runtime-ok expectation. */
        record RuntimeOk() implements ExpectedOutcome {
            @Override public boolean isCompileOutcome() { return false; }
            @Override public String mode() { return "runtime-ok"; }
        }

        /** A compile-error expectation pinning the exact diagnostic code. */
        record CompileError(String code) implements ExpectedOutcome {
            public CompileError {
                Objects.requireNonNull(code, "code must not be null");
            }
            @Override public boolean isCompileOutcome() { return true; }
            @Override public String mode() { return "compile-error"; }
        }

        /** A runtime-error expectation pinning the exact DEAL error code. */
        record RuntimeError(String code) implements ExpectedOutcome {
            public RuntimeError {
                Objects.requireNonNull(code, "code must not be null");
            }
            @Override public boolean isCompileOutcome() { return false; }
            @Override public String mode() { return "runtime-error"; }
        }
    }

    /**
     * One validated oracle pin (D12): the export name, the exact
     * canonical function-descriptor text, and the parsed function atom
     * (E4's strict parser — the signature-validation surface of
     * acceptance criterion 3).
     */
    public record Oracle(String exportName, String functionDescriptor,
                         DescriptorAst.FunctionAtom functionAtom) {

        public Oracle {
            Objects.requireNonNull(exportName, "exportName must not be null");
            Objects.requireNonNull(functionDescriptor,
                "functionDescriptor must not be null");
            Objects.requireNonNull(functionAtom, "functionAtom must not be null");
        }

        /**
         * The canonical return descriptor text of the oracle
         * ({@code R} of {@code ()->R} / {@code async()->R}) — the
         * completion check the execution surface validates against.
         */
        public String returnDescriptor() {
            return CanonicalRuntimeTypeDescriptor.render(functionAtom.returnType());
        }
    }

    /**
     * One fully parsed and shape-validated feature record (schema
     * version 1). The conditional invocation/oracle rules have been
     * applied; backend-mandate and linked-record requirements remain
     * the {@link FeatureBackendMatrix}'s domain.
     */
    public record Metadata(
        FeatureId feature,
        String spec,
        String description,
        ExpectedOutcome expected,
        List<String> backends,
        Invocation invocation,
        Optional<Oracle> oracle,
        List<String> support,
        Optional<String> linkedRecord
    ) {

        public Metadata {
            Objects.requireNonNull(feature, "feature must not be null");
            Objects.requireNonNull(spec, "spec must not be null");
            Objects.requireNonNull(description, "description must not be null");
            Objects.requireNonNull(expected, "expected must not be null");
            Objects.requireNonNull(backends, "backends must not be null");
            backends = List.copyOf(backends);
            Objects.requireNonNull(invocation, "invocation must not be null");
            oracle = Objects.requireNonNull(oracle, "oracle must not be null");
            support = Objects.requireNonNull(support, "support must not be null");
            support = List.copyOf(support);
            linkedRecord = Objects.requireNonNull(linkedRecord,
                "linkedRecord must not be null");
        }
    }

    /**
     * One metadata parse failure: the offending field (or
     * {@code "<metadata>"} for document-level failures) plus the exact
     * reason. Deterministic: the first failure in the fixed check order
     * is returned.
     */
    public record ParseFailure(String field, String reason) {

        public ParseFailure {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        /** The human-readable failure line: field, reason. */
        public String message() {
            return field + ": " + reason;
        }

        @Override
        public String toString() {
            return message();
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Strictly parses and validates one feature sidecar document.
     *
     * @param sidecarJson the sidecar document text (non-null)
     * @return the validated {@link Metadata}, or the first
     *         {@link ParseFailure} in the fixed check order — never
     *         {@code null}, never an exception
     */
    public static ParseResult parse(String sidecarJson) {
        Objects.requireNonNull(sidecarJson, "sidecarJson must not be null");
        CanonicalJson.Value root;
        try {
            root = CanonicalJson.parse(sidecarJson);
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            return new ParseResult(null, new ParseFailure("<metadata>",
                "malformed feature metadata JSON: " + e.getMessage()));
        }
        if (!(root instanceof CanonicalJson.Obj obj)) {
            return new ParseResult(null, new ParseFailure("<metadata>",
                "the feature metadata root must be a JSON object"));
        }
        Map<String, CanonicalJson.Value> fields = fieldsOf(obj);
        for (String key : fields.keySet()) {
            if (!ROOT_FIELDS.contains(key)) {
                return new ParseResult(null, new ParseFailure(key,
                    "unknown field in the feature metadata root"));
            }
        }
        CanonicalJson.Value version = fields.get("version");
        if (!(version instanceof CanonicalJson.Int v)) {
            return new ParseResult(null, new ParseFailure("version",
                version == null
                    ? "missing mandatory version field"
                    : "version must be the integer " + METADATA_SCHEMA_VERSION));
        }
        if (v.value() != METADATA_SCHEMA_VERSION) {
            return new ParseResult(null, new ParseFailure("version",
                "unknown feature metadata schema version " + v.value()
                    + " (supported: " + METADATA_SCHEMA_VERSION + ")"));
        }

        // feature
        CanonicalJson.Value featureValue = fields.get("feature");
        if (!(featureValue instanceof CanonicalJson.Str featureText)) {
            return new ParseResult(null, new ParseFailure("feature",
                featureValue == null
                    ? "missing mandatory feature field"
                    : "feature must be a string"));
        }
        Optional<FeatureId> feature = FeatureId.fromJson(featureText.value());
        if (feature.isEmpty()) {
            return new ParseResult(null, new ParseFailure("feature",
                "unknown feature \"" + featureText.value() + "\""));
        }

        // spec / description
        Optional<ParseFailure> textFail = checkNonEmptyStringField(
            fields, "spec", "spec must be a non-empty string");
        if (textFail.isPresent()) {
            return new ParseResult(null, textFail.get());
        }
        textFail = checkNonEmptyStringField(fields, "description",
            "description must be a non-empty string");
        if (textFail.isPresent()) {
            return new ParseResult(null, textFail.get());
        }

        // expected
        Optional<ParseFailure> expectedFail =
            checkExpectedField(fields.get("expected"));
        if (expectedFail.isPresent()) {
            return new ParseResult(null, expectedFail.get());
        }
        ExpectedOutcome expected = parseExpected(fields.get("expected"));

        // backends — ordered subset of the canonical backend order
        Optional<ParseFailure> backendsFail =
            checkBackendsField(fields.get("backends"));
        if (backendsFail.isPresent()) {
            return new ParseResult(null, backendsFail.get());
        }
        List<String> backends = parseBackends((CanonicalJson.Arr) fields.get("backends"));

        // invocation
        CanonicalJson.Value invocationValue = fields.get("invocation");
        if (!(invocationValue instanceof CanonicalJson.Str invocationText)) {
            return new ParseResult(null, new ParseFailure("invocation",
                invocationValue == null
                    ? "missing mandatory invocation field"
                    : "invocation must be a string"));
        }
        Optional<Invocation> invocation =
            Invocation.fromJson(invocationText.value());
        if (invocation.isEmpty()) {
            return new ParseResult(null, new ParseFailure("invocation",
                "unknown invocation \"" + invocationText.value()
                    + "\" (supported: " + String.join(", ", INVOCATION_NAMES)
                    + ")"));
        }

        // oracle — the E4 strict-descriptor signature validation
        Optional<ParseFailure> oracleFail = checkOracleField(fields.get("oracle"));
        if (oracleFail.isPresent()) {
            return new ParseResult(null, oracleFail.get());
        }
        Optional<Oracle> oracle = Optional.empty();
        if (fields.get("oracle") instanceof CanonicalJson.Obj oracleObj) {
            oracle = Optional.of(parseOracle(oracleObj));
        }

        // support — canonical relative paths
        Optional<ParseFailure> supportFail =
            checkCanonicalPathArrayField(fields.get("support"), "support",
                "support");
        if (supportFail.isPresent()) {
            return new ParseResult(null, supportFail.get());
        }
        List<String> support =
            parsePathArray((CanonicalJson.Arr) fields.get("support"));

        // linkedRecord
        Optional<ParseFailure> linkedFail = checkOptionalCanonicalPathField(
            fields.get("linkedRecord"), "linkedRecord",
            "linkedRecord");
        if (linkedFail.isPresent()) {
            return new ParseResult(null, linkedFail.get());
        }
        Optional<String> linkedRecord = Optional.empty();
        if (fields.get("linkedRecord") instanceof CanonicalJson.Str linked) {
            linkedRecord = Optional.of(linked.value());
        }

        Metadata metadata = new Metadata(feature.get(),
            ((CanonicalJson.Str) fields.get("spec")).value(),
            ((CanonicalJson.Str) fields.get("description")).value(),
            expected, backends, invocation.get(), oracle, support, linkedRecord);

        // Exact conditional rules (D12).
        Optional<ParseFailure> conditional = checkConditionalRules(metadata);
        if (conditional.isPresent()) {
            return new ParseResult(null, conditional.get());
        }
        return new ParseResult(metadata, null);
    }

    /**
     * The parse result: the validated metadata, or the first failure.
     * Exactly one side is present.
     */
    public record ParseResult(Optional<Metadata> metadata,
                              Optional<ParseFailure> failure) {

        public ParseResult {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(failure, "failure must not be null");
        }

        /** Convenience: validated metadata or the failure. */
        public ParseResult(Metadata metadata, ParseFailure failure) {
            this(Optional.ofNullable(metadata), Optional.ofNullable(failure));
        }

        /** True iff exactly one side is present (defensive invariant). */
        public boolean wellFormed() {
            return metadata.isPresent() != failure.isPresent();
        }
    }

    // =========================================================================
    // Closed root field set (schema v1)
    // =========================================================================

    private static final Set<String> ROOT_FIELDS = Set.of(
        "version", "feature", "spec", "description", "expected",
        "backends", "invocation", "oracle", "support", "linkedRecord");

    private static final Set<String> EXPECTED_OBJECT_FIELDS =
        Set.of("compile-error", "runtime-error");

    private static final Set<String> ORACLE_FIELDS =
        Set.of("exportName", "functionDescriptor");

    /** The DEAL identifier shape of an oracle export name. */
    private static boolean isDealIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (!(first >= 'A' && first <= 'Z')
                && !(first >= 'a' && first <= 'z')
                && first != '_') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates one canonical catalog-relative path spelling: non-empty,
     * slash-separated, no leading/trailing slash, no empty, {@code .},
     * or {@code ..} segments, no backslash, no NUL. Absolute paths and
     * alternate separator spellings are rejected — paths are canonical
     * by construction, so two spellings can never alias one file.
     */
    public static Optional<ParseFailure> checkCanonicalRelativePath(
            String text, String field) {
        if (text == null) {
            return Optional.of(new ParseFailure(field,
                "missing mandatory " + field + " value"));
        }
        if (text.isEmpty()) {
            return Optional.of(new ParseFailure(field,
                field + " must be a non-empty canonical relative path"));
        }
        if (text.startsWith("/") || text.endsWith("/")) {
            return Optional.of(new ParseFailure(field,
                field + " \"" + text + "\" must not start or end with '/'"));
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                return Optional.of(new ParseFailure(field,
                    field + " \"" + text + "\" contains a backslash — "
                        + "canonical relative paths use '/' separators"));
            }
            if (c == '\0') {
                return Optional.of(new ParseFailure(field,
                    field + " \"" + text + "\" contains a NUL scalar"));
            }
            if (c < 0x20 || c == 0x7F) {
                return Optional.of(new ParseFailure(field,
                    field + " \"" + text + "\" contains a control scalar"));
            }
        }
        for (String segment : text.split("/", -1)) {
            if (segment.isEmpty()) {
                return Optional.of(new ParseFailure(field,
                    field + " \"" + text + "\" contains an empty segment"));
            }
            if (segment.equals(".") || segment.equals("..")) {
                return Optional.of(new ParseFailure(field,
                    field + " \"" + text + "\" contains a '.'/'..' segment — "
                        + "canonical relative paths carry no dot segments"));
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Field checks in fixed order
    // =========================================================================

    private static Optional<ParseFailure> checkNonEmptyStringField(
            Map<String, CanonicalJson.Value> fields, String key, String reason) {
        CanonicalJson.Value value = fields.get(key);
        if (value == null) {
            return Optional.of(new ParseFailure(key,
                "missing mandatory " + key + " field"));
        }
        if (!(value instanceof CanonicalJson.Str str) || str.value().isEmpty()) {
            return Optional.of(new ParseFailure(key, reason));
        }
        return Optional.empty();
    }

    private static Optional<ParseFailure> checkExpectedField(
            CanonicalJson.Value value) {
        if (value == null) {
            return Optional.of(new ParseFailure("expected",
                "missing mandatory expected field"));
        }
        if (value instanceof CanonicalJson.Str str) {
            if (!"compile-ok".equals(str.value())
                    && !"runtime-ok".equals(str.value())) {
                return Optional.of(new ParseFailure("expected",
                    "unknown expectation \"" + str.value() + "\" (supported "
                        + "strings: compile-ok, runtime-ok; objects: "
                        + "{\"compile-error\": CODE}, {\"runtime-error\": CODE})"));
            }
            return Optional.empty();
        }
        if (!(value instanceof CanonicalJson.Obj obj)) {
            return Optional.of(new ParseFailure("expected",
                "expected must be the string \"compile-ok\" or "
                    + "\"runtime-ok\", or an object with exactly one of "
                    + "compile-error/runtime-error"));
        }
        Map<String, CanonicalJson.Value> fields = fieldsOf(obj);
        if (fields.size() != 1) {
            return Optional.of(new ParseFailure("expected",
                "the expected object must carry exactly one of "
                    + "compile-error/runtime-error"));
        }
        String key = fields.keySet().iterator().next();
        if (!EXPECTED_OBJECT_FIELDS.contains(key)) {
            return Optional.of(new ParseFailure("expected." + key,
                "unknown field in the expected object (supported: "
                    + "compile-error, runtime-error)"));
        }
        CanonicalJson.Value codeValue = fields.get(key);
        if (!(codeValue instanceof CanonicalJson.Str code)
                || code.value().isEmpty()) {
            return Optional.of(new ParseFailure("expected." + key,
                key + " must be a non-empty string code"));
        }
        if ("compile-error".equals(key)) {
            DiagnosticCode dCode = DiagnosticCode.fromCode(code.value());
            if (dCode == null || dCode.phase() == DiagnosticCode.Phase.RUNTIME) {
                return Optional.of(new ParseFailure("expected.compile-error",
                    "code " + code.value() + " is not a registered "
                        + "compile-time diagnostic code"));
            }
        } else {
            DiagnosticCode dCode = DiagnosticCode.fromCode(code.value());
            if (dCode == null || dCode.phase() != DiagnosticCode.Phase.RUNTIME) {
                return Optional.of(new ParseFailure("expected.runtime-error",
                    "code " + code.value() + " is not a registered DEAL "
                        + "runtime error code"));
            }
        }
        return Optional.empty();
    }

    private static ExpectedOutcome parseExpected(CanonicalJson.Value value) {
        if (value instanceof CanonicalJson.Str str) {
            return "compile-ok".equals(str.value())
                ? new ExpectedOutcome.CompileOk()
                : new ExpectedOutcome.RuntimeOk();
        }
        CanonicalJson.Obj obj = (CanonicalJson.Obj) value;
        Map<String, CanonicalJson.Value> fields = fieldsOf(obj);
        String key = fields.keySet().iterator().next();
        String code = ((CanonicalJson.Str) fields.get(key)).value();
        return "compile-error".equals(key)
            ? new ExpectedOutcome.CompileError(code)
            : new ExpectedOutcome.RuntimeError(code);
    }

    private static Optional<ParseFailure> checkBackendsField(
            CanonicalJson.Value value) {
        if (value == null) {
            return Optional.of(new ParseFailure("backends",
                "missing mandatory backends field"));
        }
        if (!(value instanceof CanonicalJson.Arr arr) || arr.items().isEmpty()) {
            return Optional.of(new ParseFailure("backends",
                "backends must be a non-empty ordered subset of "
                    + String.join(", ", BACKEND_NAMES)));
        }
        int previousIndex = -1;
        for (CanonicalJson.Value item : arr.items()) {
            if (!(item instanceof CanonicalJson.Str name)) {
                return Optional.of(new ParseFailure("backends",
                    "backends must contain only backend names as strings"));
            }
            int index = BACKEND_NAMES.indexOf(name.value());
            if (index < 0) {
                return Optional.of(new ParseFailure("backends",
                    "unknown backend \"" + name.value() + "\" (supported: "
                        + String.join(", ", BACKEND_NAMES) + ")"));
            }
            if (index <= previousIndex) {
                return Optional.of(new ParseFailure("backends",
                    "backends must be an ordered subset of the canonical "
                        + "order " + BACKEND_NAMES + " without duplicates, "
                        + "got \"" + name.value() + "\" out of order"));
            }
            previousIndex = index;
        }
        return Optional.empty();
    }

    private static List<String> parseBackends(CanonicalJson.Arr arr) {
        List<String> backends = new ArrayList<>();
        for (CanonicalJson.Value item : arr.items()) {
            backends.add(((CanonicalJson.Str) item).value());
        }
        return List.copyOf(backends);
    }

    /**
     * The oracle field check: closed object, identifier-shaped export
     * name, and — the acceptance-criterion-3 surface — the
     * {@code functionDescriptor} must be accepted by E4's strict
     * canonical descriptor parser with complete input consumption and
     * must parse to a function atom.
     */
    private static Optional<ParseFailure> checkOracleField(
            CanonicalJson.Value value) {
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof CanonicalJson.Obj obj)) {
            return Optional.of(new ParseFailure("oracle",
                "oracle must be a JSON object with exportName and "
                    + "functionDescriptor"));
        }
        Map<String, CanonicalJson.Value> fields = fieldsOf(obj);
        for (String key : fields.keySet()) {
            if (!ORACLE_FIELDS.contains(key)) {
                return Optional.of(new ParseFailure("oracle." + key,
                    "unknown field in the oracle object (supported: "
                        + "exportName, functionDescriptor)"));
            }
        }
        CanonicalJson.Value exportValue = fields.get("exportName");
        if (!(exportValue instanceof CanonicalJson.Str exportName)
                || !isDealIdentifier(exportName.value())) {
            return Optional.of(new ParseFailure("oracle.exportName",
                "oracle.exportName must be a DEAL identifier "
                    + "[A-Za-z_][A-Za-z0-9_]*"));
        }
        CanonicalJson.Value descriptorValue = fields.get("functionDescriptor");
        if (!(descriptorValue instanceof CanonicalJson.Str descriptor)
                || descriptor.value().isEmpty()) {
            return Optional.of(new ParseFailure("oracle.functionDescriptor",
                "oracle.functionDescriptor must be a non-empty string"));
        }
        DescriptorParseResult parsed =
            CanonicalRuntimeTypeDescriptor.parse(descriptor.value());
        if (parsed instanceof DescriptorSyntaxError error) {
            return Optional.of(new ParseFailure("oracle.functionDescriptor",
                "the oracle descriptor is not a canonical function "
                    + "descriptor: " + error.kind() + " at scalar offset "
                    + error.scalarOffset()));
        }
        if (!(parsed instanceof DescriptorAst.FunctionAtom)) {
            return Optional.of(new ParseFailure("oracle.functionDescriptor",
                "the oracle descriptor \"" + descriptor.value()
                    + "\" is not a function descriptor"));
        }
        return Optional.empty();
    }

    private static Oracle parseOracle(CanonicalJson.Obj obj) {
        Map<String, CanonicalJson.Value> fields = fieldsOf(obj);
        String exportName = ((CanonicalJson.Str) fields.get("exportName")).value();
        String descriptor =
            ((CanonicalJson.Str) fields.get("functionDescriptor")).value();
        DescriptorParseResult parsed =
            CanonicalRuntimeTypeDescriptor.parse(descriptor);
        // Field validation already pinned the parse; this cast is total.
        return new Oracle(exportName, descriptor,
            (DescriptorAst.FunctionAtom) parsed);
    }

    private static Optional<ParseFailure> checkCanonicalPathArrayField(
            CanonicalJson.Value value, String key, String name) {
        if (value == null) {
            return Optional.of(new ParseFailure(key,
                "missing mandatory " + key + " field (may be an empty array)"));
        }
        if (!(value instanceof CanonicalJson.Arr arr)) {
            return Optional.of(new ParseFailure(key,
                key + " must be an array of canonical relative paths"));
        }
        List<String> seen = new ArrayList<>();
        for (CanonicalJson.Value item : arr.items()) {
            if (!(item instanceof CanonicalJson.Str path)) {
                return Optional.of(new ParseFailure(key,
                    key + " must contain only path strings"));
            }
            Optional<ParseFailure> pathFail =
                checkCanonicalRelativePath(path.value(), key);
            if (pathFail.isPresent()) {
                return pathFail;
            }
            if (seen.contains(path.value())) {
                return Optional.of(new ParseFailure(key,
                    key + " lists \"" + path.value() + "\" more than once"));
            }
            seen.add(path.value());
        }
        return Optional.empty();
    }

    private static List<String> parsePathArray(CanonicalJson.Arr arr) {
        List<String> paths = new ArrayList<>();
        for (CanonicalJson.Value item : arr.items()) {
            paths.add(((CanonicalJson.Str) item).value());
        }
        return List.copyOf(paths);
    }

    private static Optional<ParseFailure> checkOptionalCanonicalPathField(
            CanonicalJson.Value value, String key, String name) {
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof CanonicalJson.Str path)) {
            return Optional.of(new ParseFailure(key,
                key + " must be a canonical feature-record id string"));
        }
        return checkCanonicalRelativePath(path.value(), key);
    }

    // =========================================================================
    // Exact conditional rules (D12)
    // =========================================================================

    private static Optional<ParseFailure> checkConditionalRules(Metadata m) {
        // Compile outcomes require compile-only and forbid oracle.
        if (m.expected().isCompileOutcome()) {
            if (m.invocation() != Invocation.COMPILE_ONLY) {
                return Optional.of(new ParseFailure("invocation",
                    "a " + m.expected().mode() + " expectation requires "
                        + "compile-only, got " + m.invocation().jsonText()));
            }
            if (m.oracle().isPresent()) {
                return Optional.of(new ParseFailure("oracle",
                    "a compile outcome forbids an oracle (compile-only "
                        + "records carry no oracle)"));
            }
            return Optional.empty();
        }
        // Runtime outcomes forbid compile-only.
        if (m.invocation() == Invocation.COMPILE_ONLY) {
            return Optional.of(new ParseFailure("invocation",
                "a runtime outcome forbids compile-only (runtime records "
                    + "require direct-main, synthetic-main, or async-export)"));
        }
        return switch (m.invocation()) {
            case DIRECT_MAIN -> {
                if (m.oracle().isPresent()) {
                    yield Optional.of(new ParseFailure("oracle",
                        "direct-main forbids an oracle (the entry main is "
                            + "invoked directly)"));
                }
                yield Optional.empty();
            }
            case SYNTHETIC_MAIN -> checkOracleSignature(m, false);
            case ASYNC_EXPORT -> checkOracleSignature(m, true);
            case COMPILE_ONLY -> Optional.empty(); // unreachable (handled above)
        };
    }

    /**
     * The exact sync/async oracle-signature rule: the descriptor must
     * parse (already guaranteed by field validation) to a function atom
     * with zero parameters and the exact async marker of the invocation
     * mode — {@code ()->R} for synthetic-main, {@code async()->R} for
     * async-export. Any other shape (parameterized, mismatched marker)
     * is a signature-mismatch failure.
     */
    private static Optional<ParseFailure> checkOracleSignature(
            Metadata m, boolean requireAsync) {
        if (m.oracle().isEmpty()) {
            return Optional.of(new ParseFailure("oracle",
                m.invocation().jsonText() + " requires exactly one oracle"));
        }
        Oracle oracle = m.oracle().get();
        if (!oracle.functionAtom().params().isEmpty()) {
            return Optional.of(new ParseFailure("oracle.functionDescriptor",
                m.invocation().jsonText() + " requires the zero-parameter "
                    + (requireAsync ? "async()->R" : "()->R")
                    + " form, got a parameterized descriptor"));
        }
        if (oracle.functionAtom().isAsync() != requireAsync) {
            return Optional.of(new ParseFailure("oracle.functionDescriptor",
                m.invocation().jsonText() + " requires the exact "
                    + (requireAsync ? "async()->R" : "()->R")
                    + " form, got " + oracle.functionDescriptor()));
        }
        return Optional.empty();
    }

    // =========================================================================
    // CanonicalJson helpers
    // =========================================================================

    /** The sidecar's object fields in document order (canonical JSON). */
    private static Map<String, CanonicalJson.Value> fieldsOf(CanonicalJson.Obj obj) {
        Map<String, CanonicalJson.Value> fields = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            fields.put(entry.key(), entry.value());
        }
        return fields;
    }
}
