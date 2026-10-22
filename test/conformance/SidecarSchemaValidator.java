package deal.test.conformance;

import deal.lexer.CompilerDirective;
import deal.lexer.DirectiveName;
import deal.lexer.Lexer;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SidecarSchemaValidator {

    /** The only schema version this validator accepts. */
    public static final int SIDECAR_SCHEMA_VERSION = 1;
    /** The three backends of the differential gate, in canonical order. */
    public static final List<String> BACKEND_NAMES = List.of("luajit", "jvm", "js");

    private SidecarSchemaValidator() {
        // Static utility; no instances.
    }

    // =========================================================================
    // Validation context and result types
    // =========================================================================

    /**
     * One module of a fixture's compilation set: its canonical
     * corpus-relative path (slash separators, relative to the conformance
     * root — e.g. {@code backend-runtime/arithmetic/int-add-overflow.deal})
     * plus its DEAL source text.
     */
    public record CompilationModule(String corpusPath, String source) {

        public CompilationModule {
            Objects.requireNonNull(corpusPath, "corpusPath must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    /**
     * The validation context for one sidecar: the fixture's canonical
     * corpus-relative path, the fixture's exact {@code @expected} tag
     * (e.g. {@code runtime-error E8001}, {@code compile-error E3001}),
     * the fixture's compilation set (the fixture itself plus its
     * transitively imported corpus modules — gate T6), and the optional
     * corpus module index (all corpus-relative module paths the gate
     * discovered; {@code null} or empty means "no index supplied").
     */
    public record ValidationContext(
        String fixturePath,
        String expectedTag,
        List<CompilationModule> compilationSet,
        Set<String> corpusModuleIndex
    ) {

        public ValidationContext {
            Objects.requireNonNull(fixturePath, "fixturePath must not be null");
            Objects.requireNonNull(expectedTag, "expectedTag must not be null");
            Objects.requireNonNull(compilationSet, "compilationSet must not be null");
            compilationSet = List.copyOf(compilationSet);
            corpusModuleIndex =
                corpusModuleIndex == null ? Set.of() : Set.copyOf(corpusModuleIndex);
        }


    }

    /**
     * One classification failure: the fixture path, the offending sidecar
     * field ({@code "backends"}, {@code "expected.mode"},
     * {@code "expected.error.sourceFile"}, {@code "diagnostic.code"},
     * {@code "@expected"} for the compile-sidecar classification
     * cross-check, …), and the exact reason.
     */
    public record ClassificationFailure(String fixturePath, String field, String reason) {

        public ClassificationFailure {
            Objects.requireNonNull(fixturePath, "fixturePath must not be null");
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        /** The gate-readable failure line: fixture, field, reason. */
        public String message() {
            return fixturePath + ": " + field + ": " + reason;
        }

        @Override
        public String toString() {
            return message();
        }
    }

    // =========================================================================
    // Closed field sets (schema v1)
    // =========================================================================

    private static final Set<String> ROOT_FIELDS =
        Set.of("version", "backends", "expected", "mode", "diagnostic");

    private static final Set<String> RUNTIME_EXPECTATION_FIELDS =
        Set.of("mode", "transcript", "exitCode", "error");

    private static final Set<String> ERROR_FIELDS =
        Set.of("code", "message", "sourceFile", "line", "column",
            "expected", "actual", "frames", "cause");

    private static final List<String> MANDATORY_ERROR_FIELDS =
        List.of("code", "message", "sourceFile", "line", "column");

    private static final List<String> ERROR_STRING_FIELDS =
        List.of("code", "message", "sourceFile", "expected", "actual", "cause");

    private static final List<String> ERROR_INT_FIELDS =
        List.of("line", "column", "frames");

    private static final Set<String> COMPILE_DIAGNOSTIC_FIELDS =
        Set.of("code", "line", "column", "message");

    private static final Set<String> REJECT_DIAGNOSTIC_FIELDS =
        Set.of("code", "line", "column");

    /** The exact {@code @expected} form a Compile Expectation Sidecar requires. */
    private static final Pattern EXACT_COMPILE_ERROR_TAG =
        Pattern.compile("^compile-error E[0-9]{4}$");

    private static final Pattern WINDOWS_ABSOLUTE_PATH =
        Pattern.compile("^[A-Za-z]:[\\\\/]");

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Validates one sidecar document against schema version 1 and returns
     * the first classification failure, or {@link Optional#empty()} when
     * the sidecar validates clean. The check order is fixed, so the
     * result is deterministic for any given input.
     *
     * @param context     the fixture classification and compilation-set
     *                    context (non-null)
     * @param sidecarJson the sidecar document text (non-null)
     * @return the first classification failure, or empty when valid
     */
    public static Optional<ClassificationFailure> validate(
        ValidationContext context, String sidecarJson) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(sidecarJson, "sidecarJson must not be null");

        CanonicalJson.Value root;
        try {
            root = CanonicalJson.parse(sidecarJson);
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            return failure(context, "<sidecar>",
                "malformed sidecar JSON: " + e.getMessage());
        }
        if (!(root instanceof CanonicalJson.Obj obj)) {
            return failure(context, "<sidecar>",
                "the sidecar root must be a JSON object");
        }
        Map<String, CanonicalJson.Value> rootFields = fieldsOf(obj);
        for (String key : rootFields.keySet()) {
            if (!ROOT_FIELDS.contains(key)) {
                return failure(context, key, "unknown field in the sidecar root");
            }
        }
        CanonicalJson.Value version = rootFields.get("version");
        if (!(version instanceof CanonicalJson.Int v)) {
            return failure(context, "version", version == null
                ? "missing mandatory version field"
                : "version must be the integer " + SIDECAR_SCHEMA_VERSION);
        }
        if (v.value() != SIDECAR_SCHEMA_VERSION) {
            return failure(context, "version", "unknown sidecar schema version "
                + v.value() + " (supported: " + SIDECAR_SCHEMA_VERSION + ")");
        }
        if (rootFields.containsKey("mode")) {
            return validateCompileSidecar(context, rootFields);
        }
        return validateRuntimeSidecar(context, rootFields);
    }

    // =========================================================================
    // Compile Expectation Sidecar
    // =========================================================================

    private static Optional<ClassificationFailure> validateCompileSidecar(
        ValidationContext context, Map<String, CanonicalJson.Value> rootFields) {
        CanonicalJson.Value modeValue = rootFields.get("mode");
        if (!(modeValue instanceof CanonicalJson.Str mode)
                || !"compile-error".equals(mode.value())) {
            return failure(context, "mode", "unknown variant "
                + (modeValue == null ? "missing" : describe(modeValue))
                + " — a Compile Expectation Sidecar requires mode \"compile-error\"");
        }
        if (rootFields.containsKey("backends")) {
            return failure(context, "backends",
                "a compile sidecar must not carry a backends key "
                    + "(the backends key exists only on runtime sidecars)");
        }
        if (rootFields.containsKey("expected")) {
            return failure(context, "expected",
                "unknown field in a compile sidecar root");
        }
        CanonicalJson.Value diagnosticValue = rootFields.get("diagnostic");
        if (!(diagnosticValue instanceof CanonicalJson.Obj diagnostic)) {
            return failure(context, "diagnostic", diagnosticValue == null
                ? "missing mandatory diagnostic object"
                : "diagnostic must be a JSON object");
        }
        Map<String, CanonicalJson.Value> diagnosticFields = fieldsOf(diagnostic);
        for (String key : diagnosticFields.keySet()) {
            if (!COMPILE_DIAGNOSTIC_FIELDS.contains(key)) {
                return failure(context, "diagnostic." + key,
                    "unknown field in the compile diagnostic object");
            }
        }
        CanonicalJson.Value codeValue = diagnosticFields.get("code");
        if (!(codeValue instanceof CanonicalJson.Str code)) {
            return failure(context, "diagnostic.code", codeValue == null
                ? "missing mandatory code field"
                : "code must be a string");
        }
        for (String key : List.of("line", "column")) {
            Optional<ClassificationFailure> intFail =
                checkOptionalIntField(context, "diagnostic." + key,
                    diagnosticFields.get(key));
            if (intFail.isPresent()) {
                return intFail;
            }
        }
        Optional<ClassificationFailure> messageFail =
            checkOptionalStringField(context, "diagnostic.message",
                diagnosticFields.get("message"));
        if (messageFail.isPresent()) {
            return messageFail;
        }
        // The pin must equal the fixture's exact @expected code: a compile
        // sidecar on a fixture whose @expected is not an exact
        // "compile-error CODE", or a pin code differing from @expected, is
        // a classification failure (Compile Expectation Sidecar contract).
        String expectedCode = exactCompileErrorCode(context.expectedTag());
        if (expectedCode == null) {
            return failure(context, "@expected",
                "a Compile Expectation Sidecar requires an exact "
                    + "\"compile-error CODE\" @expected tag, got \""
                    + context.expectedTag() + "\"");
        }
        if (!code.value().equals(expectedCode)) {
            return failure(context, "diagnostic.code",
                "the compile sidecar pins code " + code.value()
                    + " but the fixture's @expected pins " + expectedCode);
        }
        return Optional.empty();
    }

    private static String exactCompileErrorCode(String expectedTag) {
        if (!EXACT_COMPILE_ERROR_TAG.matcher(expectedTag).matches()) {
            return null;
        }
        return expectedTag.substring("compile-error ".length());
    }

    // =========================================================================
    // Runtime sidecars (uniform + divergent)
    // =========================================================================

    private static Optional<ClassificationFailure> validateRuntimeSidecar(
        ValidationContext context, Map<String, CanonicalJson.Value> rootFields) {
        if (rootFields.containsKey("diagnostic")) {
            return failure(context, "diagnostic",
                "unknown field in a runtime sidecar root");
        }
        CanonicalJson.Value backendsValue = rootFields.get("backends");
        if (backendsValue == null) {
            return failure(context, "backends",
                "a runtime sidecar must carry a backends key naming exactly "
                    + "the three backends luajit, jvm, js");
        }
        if (backendsValue instanceof CanonicalJson.Arr backends) {
            if (rootFields.containsKey("mode")) {
                return failure(context, "mode",
                    "unknown field in a runtime sidecar root");
            }
            Optional<ClassificationFailure> nameFail =
                checkUniformBackendList(context, backends);
            if (nameFail.isPresent()) {
                return nameFail;
            }
            CanonicalJson.Value expectedValue = rootFields.get("expected");
            if (!(expectedValue instanceof CanonicalJson.Obj expected)) {
                return failure(context, "expected", expectedValue == null
                    ? "missing mandatory expected object (a uniform runtime "
                        + "sidecar names the three backends and one shared "
                        + "expected object)"
                    : "expected must be a JSON object");
            }
            return checkRuntimeExpectation(context, "expected", expected);
        }
        if (backendsValue instanceof CanonicalJson.Obj) {
            if (rootFields.containsKey("expected")) {
                return failure(context, "expected",
                    "unknown field in a divergent sidecar root (a divergent "
                        + "sidecar carries one expectation per backend)");
            }
            if (rootFields.containsKey("mode")) {
                return failure(context, "mode",
                    "unknown field in a runtime sidecar root");
            }
            return validateDivergentSidecar(context, (CanonicalJson.Obj) backendsValue);
        }
        return failure(context, "backends",
            "backends must be an array of the three backend names (uniform "
                + "form) or a per-backend object naming exactly luajit, jvm, "
                + "js (divergent form)");
    }

    /** Uniform form: the backends array must name exactly the three backends. */
    private static Optional<ClassificationFailure> checkUniformBackendList(
        ValidationContext context, CanonicalJson.Arr backends) {
        List<String> names = new ArrayList<>();
        for (CanonicalJson.Value item : backends.items()) {
            if (!(item instanceof CanonicalJson.Str name)) {
                return failure(context, "backends",
                    "the backends array must contain exactly the backend names "
                        + "\"luajit\", \"jvm\", \"js\" as strings");
            }
            if (!BACKEND_NAMES.contains(name.value())) {
                return failure(context, "backends", "unknown backend "
                    + "\"" + name.value() + "\" (a runtime sidecar names "
                    + "exactly luajit, jvm, js)");
            }
            if (names.contains(name.value())) {
                return failure(context, "backends", "duplicate backend "
                    + "\"" + name.value() + "\"");
            }
            names.add(name.value());
        }
        if (names.size() != BACKEND_NAMES.size()) {
            return failure(context, "backends",
                "a runtime sidecar must name exactly the three backends "
                    + "luajit, jvm, js (got "
                    + (names.isEmpty() ? "none" : String.join(", ", names)) + ")");
        }
        return Optional.empty();
    }
    /** Reject backend-specific expectations: all targets share one language contract. */
    private static Optional<ClassificationFailure> validateDivergentSidecar(
            ValidationContext context, CanonicalJson.Obj backends) {
        return failure(context, "backends", "per-backend divergence is not supported");
    }

    // =========================================================================
    // Shared per-mode expectation rules (runtime-ok / runtime-error)
    // =========================================================================

    /**
     * Validates one runtime expectation object (uniform {@code expected}
     * or one divergent backend entry): closed field set, exact mode,
     * transcript with string stdout/stderr, exact exit code, and the
     * mode-closed error rules.
     */
    private static Optional<ClassificationFailure> checkRuntimeExpectation(
        ValidationContext context, String prefix, CanonicalJson.Obj expectation) {
        Map<String, CanonicalJson.Value> fields = fieldsOf(expectation);
        for (String key : fields.keySet()) {
            if (!RUNTIME_EXPECTATION_FIELDS.contains(key)) {
                return failure(context, prefix + "." + key,
                    "unknown field in the " + prefix + " object");
            }
        }
        CanonicalJson.Value modeValue = fields.get("mode");
        if (!(modeValue instanceof CanonicalJson.Str mode)) {
            return failure(context, prefix + ".mode", modeValue == null
                ? "missing mandatory mode field"
                : "mode must be a string");
        }
        switch (mode.value()) {
            case "runtime-ok", "runtime-error" -> { }
            case "compile-reject" -> {
                return failure(context, prefix + ".mode",
                    "unknown variant \"compile-reject\" — a uniform "
                        + "expectation supports exactly runtime-ok and "
                        + "runtime-error (compile-reject exists only in the "
                        + "divergent form)");
            }
            default -> {
                return failure(context, prefix + ".mode", "unknown variant "
                    + describe(modeValue) + " (supported: runtime-ok, "
                    + "runtime-error)");
            }
        }
        CanonicalJson.Value transcriptValue = fields.get("transcript");
        if (!(transcriptValue instanceof CanonicalJson.Obj transcript)) {
            return failure(context, prefix + ".transcript", transcriptValue == null
                ? "missing mandatory transcript object"
                : "transcript must be a JSON object");
        }
        Optional<ClassificationFailure> transcriptFail =
            checkTranscript(context, prefix + ".transcript", transcript);
        if (transcriptFail.isPresent()) {
            return transcriptFail;
        }
        CanonicalJson.Value exitValue = fields.get("exitCode");
        if (!(exitValue instanceof CanonicalJson.Int exitCode)) {
            return failure(context, prefix + ".exitCode", exitValue == null
                ? "missing mandatory exitCode field"
                : "exitCode must be an integer");
        }
        int requiredExit = "runtime-ok".equals(mode.value()) ? 0 : 1;
        if (exitCode.value() != requiredExit) {
            return failure(context, prefix + ".exitCode", mode.value()
                + " requires exitCode " + requiredExit + ", got "
                + exitCode.value());
        }
        CanonicalJson.Value errorValue = fields.get("error");
        if ("runtime-ok".equals(mode.value())) {
            if (errorValue != null) {
                return failure(context, prefix + ".error",
                    "runtime-ok must not carry an error field");
            }
            return Optional.empty();
        }
        if (!(errorValue instanceof CanonicalJson.Obj errorObject)) {
            return failure(context, prefix + ".error", errorValue == null
                ? "runtime-error requires an error object"
                : "error must be a JSON object");
        }
        return checkErrorObject(context, prefix + ".error", errorObject);
    }

    private static Optional<ClassificationFailure> checkTranscript(
        ValidationContext context, String prefix, CanonicalJson.Obj transcript) {
        Map<String, CanonicalJson.Value> fields = fieldsOf(transcript);
        for (String key : fields.keySet()) {
            if (!key.equals("stdout") && !key.equals("stderr")) {
                return failure(context, prefix + "." + key,
                    "unknown field in the transcript object");
            }
        }
        CanonicalJson.Value stdout = fields.get("stdout");
        if (!(stdout instanceof CanonicalJson.Str)) {
            return failure(context, prefix + ".stdout", stdout == null
                ? "missing mandatory stdout string"
                : "stdout must be a string");
        }
        CanonicalJson.Value stderr = fields.get("stderr");
        if (!(stderr instanceof CanonicalJson.Str)) {
            return failure(context, prefix + ".stderr", stderr == null
                ? "missing mandatory stderr string"
                : "stderr must be a string");
        }
        return Optional.empty();
    }

    // =========================================================================
    // Error object rules (sidecar-authoritative field set)
    // =========================================================================

    /**
     * Validates one runtime-error error object: closed field set
     * (mandatory {@code code}, {@code message}, {@code sourceFile},
     * {@code line}, {@code column}; optional exactly {@code expected},
     * {@code actual}, {@code frames}, {@code cause}), field types, and the
     * canonical {@code sourceFile} compilation-graph check.
     */
    private static Optional<ClassificationFailure> checkErrorObject(
        ValidationContext context, String prefix, CanonicalJson.Obj errorObject) {
        Map<String, CanonicalJson.Value> fields = fieldsOf(errorObject);
        for (String key : fields.keySet()) {
            if (!ERROR_FIELDS.contains(key)) {
                return failure(context, prefix + "." + key,
                    "unknown field in the error object (recognized optional "
                        + "fields are exactly expected, actual, frames, cause)");
            }
        }
        for (String mandatory : MANDATORY_ERROR_FIELDS) {
            if (!fields.containsKey(mandatory)) {
                return failure(context, prefix + "." + mandatory,
                    "missing mandatory error field \"" + mandatory + "\"");
            }
        }
        for (String key : ERROR_STRING_FIELDS) {
            CanonicalJson.Value value = fields.get(key);
            if (value != null && !(value instanceof CanonicalJson.Str)) {
                return failure(context, prefix + "." + key,
                    "error field " + key + " must be a string");
            }
        }
        for (String key : ERROR_INT_FIELDS) {
            CanonicalJson.Value value = fields.get(key);
            if (value != null && !(value instanceof CanonicalJson.Int)) {
                return failure(context, prefix + "." + key,
                    "error field " + key + " must be an integer");
            }
        }
        CanonicalJson.Value sourceFileValue = fields.get("sourceFile");
        if (sourceFileValue == null) {
            return Optional.empty();
        }
        String sourceFile = ((CanonicalJson.Str) sourceFileValue).value();
        return checkSourceFile(context, prefix + ".sourceFile", sourceFile);
    }

    // =========================================================================
    // Canonical sourceFile rules
    // =========================================================================

    /**
     * The canonical corpus-relative form (corpus C2): slash separators,
     * relative to the conformance root, naming the module that threw — the
     * fixture's own corpus-relative path or the corpus-relative path of a
     * module in the fixture's compilation set. Absolute paths,
     * temp-workspace paths, backslash separators, paths that do not
     * resolve to an existing corpus module, and paths naming a corpus
     * module outside the fixture's compilation set are classification
     * failures.
     */
    private static Optional<ClassificationFailure> checkSourceFile(
        ValidationContext context, String field, String sourceFile) {
        if (sourceFile.indexOf('\\') >= 0) {
            return failure(context, field, "sourceFile must be a canonical "
                + "corpus-relative path with \"/\" separators, got backslash "
                + "separators in \"" + sourceFile + "\"");
        }
        if (isTempWorkspacePath(sourceFile)) {
            return failure(context, field, "sourceFile must be a canonical "
                + "corpus-relative path, never a temp-workspace path, got \""
                + sourceFile + "\"");
        }
        if (sourceFile.startsWith("/")
                || WINDOWS_ABSOLUTE_PATH.matcher(sourceFile).find()) {
            return failure(context, field, "sourceFile must be a canonical "
                + "corpus-relative path, got the absolute path \""
                + sourceFile + "\"");
        }
        if (isTempWorkspacePath(sourceFile)) {
            return failure(context, field, "sourceFile must be a canonical "
                + "corpus-relative path, never a temp-workspace path, got \""
                + sourceFile + "\"");
        }
        for (String segment : sourceFile.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return failure(context, field, "sourceFile must be a "
                    + "canonical corpus-relative path without \".\" or \"..\" "
                    + "segments, got \"" + sourceFile + "\"");
            }
        }
        if (sourceFile.equals(context.fixturePath())
                || isCompilationModule(context, sourceFile)) {
            return Optional.empty();
        }
        if (context.corpusModuleIndex().contains(sourceFile)) {
            return failure(context, field, "sourceFile \"" + sourceFile
                + "\" names a corpus module outside the fixture's compilation "
                + "set (a different, unrelated corpus module)");
        }
        return failure(context, field, "sourceFile \"" + sourceFile
            + "\" does not resolve to an existing corpus module in the "
            + "fixture's compilation set");
    }

    private static boolean isCompilationModule(
        ValidationContext context, String path) {
        for (CompilationModule module : context.compilationSet()) {
            if (module.corpusPath().equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** Conservative temp-workspace markers; real corpus paths carry none. */
    private static boolean isTempWorkspacePath(String path) {
        for (String segment : path.split("/", -1)) {
            String lower = segment.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("tmp") || lower.equals("temp")
                    || lower.contains("workspace") || lower.contains("workdir")
                    || lower.startsWith("deal_jvm_conf_")
                    || lower.startsWith("deal_conf_")) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private static Optional<ClassificationFailure> checkOptionalIntField(
        ValidationContext context, String field, CanonicalJson.Value value) {
        if (value != null && !(value instanceof CanonicalJson.Int)) {
            return failure(context, field, field.substring(field.lastIndexOf('.') + 1)
                + " must be an integer");
        }
        return Optional.empty();
    }

    private static Optional<ClassificationFailure> checkOptionalStringField(
        ValidationContext context, String field, CanonicalJson.Value value) {
        if (value != null && !(value instanceof CanonicalJson.Str)) {
            return failure(context, field, field.substring(field.lastIndexOf('.') + 1)
                + " must be a string");
        }
        return Optional.empty();
    }

    private static Optional<ClassificationFailure> failure(
        ValidationContext context, String field, String reason) {
        return Optional.of(
            new ClassificationFailure(context.fixturePath(), field, reason));
    }

    /** The document's field map in source order (deterministic iteration). */
    private static LinkedHashMap<String, CanonicalJson.Value> fieldsOf(
        CanonicalJson.Obj obj) {
        LinkedHashMap<String, CanonicalJson.Value> fields = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            fields.put(entry.key(), entry.value());
        }
        return fields;
    }

    private static String describe(CanonicalJson.Value value) {
        return value instanceof CanonicalJson.Str str
            ? "\"" + str.value() + "\""
            : CanonicalJson.serializeText(value);
    }

    private static String describe(String value) {
        return "\"" + value + "\"";
    }
}
