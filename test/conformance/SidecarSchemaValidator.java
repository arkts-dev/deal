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

/**
 * The reusable Sidecar Schema Validation component of the v1.2
 * three-backend conformance gate (ISSUE-0348; the corpus sidecar design
 * is {@code v12-three-backend-conformance-corpus} C2/C6, the consuming
 * gate is {@code v12-zero-skip-conformance-gate}).
 *
 * <p>Validates one sidecar document against schema version 1 and
 * produces the classification failures the design defines. The canonical
 * schema name is <b>Structured Expectation Sidecar</b>; its backend
 * neutral variant is the <b>Compile Expectation Sidecar</b>. The three
 * runtime variants and one compile variant of the schema are closed:</p>
 *
 * <ul>
 *   <li><b>Uniform runtime sidecar</b> —
 *       {@code version: 1}, {@code backends} naming exactly the three
 *       backends {@code luajit}, {@code jvm}, {@code js} as an array, and
 *       one {@code expected} object shared by all three lanes with
 *       {@code mode} exactly {@code runtime-ok} or {@code runtime-error}
 *       (a {@code compile-reject} mode in a uniform {@code expected}
 *       object is an unknown variant — the rejection exists only in the
 *       divergent form).</li>
 *   <li><b>Divergent runtime sidecar</b> — {@code backends} as a
 *       per-backend object naming exactly the three backends, one
 *       expectation per entry ({@code mode} in {@code runtime-ok},
 *       {@code runtime-error}, {@code compile-reject}). The only
 *       divergent shape that validates is the sanctioned C6 split,
 *       reserved for the spec-sanctioned C FFI rejection: a runtime
 *       expectation on {@code luajit} and {@code compile-reject} with
 *       {@code diagnostic.code} exactly {@code E6006}
 *       ({@code FFI_UNSUPPORTED_BACKEND}) on both {@code jvm} and
 *       {@code js} — and only when the fixture's compilation set contains
 *       the C6 rejection trigger, an {@code @extern-c} import directive
 *       in the fixture or a companion module of the compilation set.
 *       Every other combination is a classification failure naming the
 *       fixture and the offending backend entry.</li>
 *   <li><b>Compile Expectation Sidecar</b> — {@code version: 1},
 *       {@code mode: "compile-error"}, no {@code backends} key, and one
 *       {@code diagnostic} object with mandatory {@code code} and
 *       optional-pinned {@code line}/{@code column}/{@code message}. The
 *       pin's {@code code} must equal the fixture's exact
 *       {@code compile-error CODE} {@code @expected} tag.</li>
 * </ul>
 *
 * <p>Per-mode expectation rules ({@code prefix} is {@code expected} for
 * the uniform form or {@code backends.<backend>} for a divergent
 * entry):</p>
 * <ul>
 *   <li>{@code runtime-ok} — {@code transcript} object with string
 *       {@code stdout}/{@code stderr} fields, {@code exitCode} 0, and no
 *       {@code error} field (an {@code error} field on {@code runtime-ok}
 *       is a classification failure).</li>
 *   <li>{@code runtime-error} — {@code exitCode} 1 and an {@code error}
 *       object with the mandatory fields {@code code} and {@code
 *       message} plus the span group {@code sourceFile}, {@code line},
 *       {@code column}. The span group is mandatory for every fixture
 *       except the one sanctioned span-less shape — the locked time
 *       selector's retained {@code nowMillis} wrapper raises E8004 with
 *       no file/line/column at all
 *       ({@code luajit-time-selector-disposition}, Failure and
 *       operations), so the sidecar of exactly
 *       {@code backend-runtime/stdlib-edge/time-now-millis-positive.deal}
 *       omits the whole group, and pinning any of the three there is a
 *       classification failure (a sidecar must never pin span values
 *       the runtime cannot produce). Recognized optional fields are
 *       exactly {@code expected}, {@code actual}, {@code frames},
 *       {@code cause}; any other field in the error object is an
 *       unknown-field failure. The sidecar is the authoritative field
 *       set (C2).</li>
 *   <li>{@code compile-reject} — exactly a {@code diagnostic} object with
 *       mandatory {@code code} plus optional-pinned {@code line}/
 *       {@code column} ({@code message} is compile-sidecar-only); no
 *       {@code transcript}/{@code exitCode}/{@code error} fields — any
 *       other field in the entry is an unknown-field failure.</li>
 * </ul>
 *
 * <p>{@code sourceFile} of every {@code runtime-error} error object must
 * be the canonical corpus-relative path of the module that threw — the
 * fixture's own corpus-relative path when the fixture itself throws, or
 * the corpus-relative path of any module in the fixture's compilation set
 * (the modules the lane compiles for this fixture: the fixture plus its
 * transitively imported corpus modules). The validator rejects absolute
 * paths, temp-workspace paths, backslash separators, paths that do not
 * resolve to an existing corpus module, and paths naming a corpus module
 * outside the fixture's compilation set — a sidecar naming a different,
 * unrelated fixture is a classification failure (corpus page
 * Verification 1).</p>
 *
 * <p>The validator is deterministic and side-effect-free: it parses the
 * sidecar text it is given, performs no I/O, mutates nothing, and has no
 * default expectations. It enforces no absence: a missing sidecar for a
 * runtime fixture is the gate classification contract's failure, and
 * {@code compile-ok} fixtures and code-pinned {@code compile-error}
 * fixtures without diagnostic pins carry no sidecar — the validator
 * rejects no absence it is not given.</p>
 *
 * <p>Validation context: the gate core derives the compilation set via
 * module-import resolution (gate T6) and passes module paths plus their
 * sources; unit tests supply synthetic compilation sets. The optional
 * corpus module index is the set of all corpus-relative module paths the
 * gate discovered — it lets the validator distinguish a sidecar naming a
 * corpus module outside the fixture's compilation set from one naming a
 * path that resolves to no existing corpus module.</p>
 */
public final class SidecarSchemaValidator {

    /** The only schema version this validator accepts. */
    public static final int SIDECAR_SCHEMA_VERSION = 1;

    /**
     * The only diagnostic code a divergent {@code compile-reject} may
     * pin: {@code E6006} {@code FFI_UNSUPPORTED_BACKEND} (corpus C6 —
     * the sanctioned C FFI rejection; the backend epics register it in
     * {@code deal/diagnostics/DiagnosticCode.java}).
     */
    public static final String FFI_UNSUPPORTED_BACKEND_CODE = "E6006";

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

        /**
         * True iff any module of the compilation set carries the C6
         * rejection trigger: an {@code @extern-c} import directive
         * (detected with the real lexer — the directive attaches to the
         * {@code import} token, never to an ordinary string occurrence).
         */
        public boolean compilationSetHasExternCImport() {
            for (CompilationModule module : compilationSet) {
                if (containsExternCImport(module.source())) {
                    return true;
                }
            }
            return false;
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

    /**
     * The one sanctioned span-less runtime-error shape: the locked time
     * selector's retained {@code nowMillis} wrapper raises E8004 with no
     * file/line/column at all
     * ({@code luajit-time-selector-disposition}, Failure and operations
     * — "the E8004 carries the route's existing shape (the retained
     * wrapper passes no span)"), so this fixture's sidecar omits the
     * whole span group and pinning any of the three is a classification
     * failure (a sidecar must never pin span values the runtime cannot
     * produce).
     */
    static final String SANCTIONED_SPANLESS_FIXTURE =
        "backend-runtime/stdlib-edge/time-now-millis-positive.deal";

    /** The mandatory fields of the sanctioned span-less error object. */
    private static final List<String> SPANLESS_MANDATORY_ERROR_FIELDS =
        List.of("code", "message");

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

    /**
     * True iff the DEAL source text carries an {@code @extern-c} file
     * directive — the C6 rejection trigger (re-keyed onto the structured
     * directive events by fixed-name-directive-events D9: {@code @extern-c}
     * is a file directive that never anchors to a token). The real lexer
     * recognizes the directive only as a directive-shaped comment, so a
     * mere {@code @extern-c} occurrence inside a string literal is never
     * a trigger. A source that fails to lex yields {@code false} (fail
     * closed: a divergent form is then rejected as lacking the trigger).
     *
     * @param source the DEAL module source text (non-null)
     * @return true iff an {@code @extern-c} directive event exists
     */
    public static boolean containsExternCImport(String source) {
        Objects.requireNonNull(source, "source must not be null");
        try {
            Lexer lexer = new Lexer(source, "<sidecar-schema-compilation-module>");
            for (CompilerDirective event : lexer.tokenize().directiveEvents()) {
                if (event.name() == DirectiveName.EXTERN_C) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // Fail closed: an unlexable module cannot prove the trigger.
            return false;
        }
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

    /**
     * Returns the pinned code iff the tag has the exact
     * {@code compile-error CODE} form, else {@code null}.
     */
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

    /** Divergent form: per-backend entries plus the sanctioned C6 split. */
    private static Optional<ClassificationFailure> validateDivergentSidecar(
        ValidationContext context, CanonicalJson.Obj backends) {
        Map<String, CanonicalJson.Value> entries = fieldsOf(backends);
        for (String key : entries.keySet()) {
            if (!BACKEND_NAMES.contains(key)) {
                return failure(context, "backends." + key, "unknown backend "
                    + "\"" + key + "\" (a divergent sidecar names exactly "
                    + "luajit, jvm, js)");
            }
        }
        if (entries.size() != BACKEND_NAMES.size()) {
            List<String> missing = new ArrayList<>();
            for (String backend : BACKEND_NAMES) {
                if (!entries.containsKey(backend)) {
                    missing.add(backend);
                }
            }
            return failure(context, "backends",
                "a divergent sidecar must name exactly the three backends "
                    + "luajit, jvm, js (missing " + String.join(", ", missing) + ")");
        }
        // Per-entry shape validation in canonical backend order.
        for (String backend : BACKEND_NAMES) {
            CanonicalJson.Value entryValue = entries.get(backend);
            if (!(entryValue instanceof CanonicalJson.Obj entry)) {
                return failure(context, "backends." + backend,
                    "the backend expectation must be a JSON object");
            }
            Optional<ClassificationFailure> entryFail =
                checkDivergentEntry(context, "backends." + backend, entry);
            if (entryFail.isPresent()) {
                return entryFail;
            }
        }
        Optional<ClassificationFailure> splitFail =
            checkSanctionedSplit(context, entries);
        if (splitFail.isPresent()) {
            return splitFail;
        }
        if (!context.compilationSetHasExternCImport()) {
            return failure(context, "backends",
                "a divergent form is valid only for the C6 rejection: the "
                    + "fixture's compilation set contains no @extern-c import "
                    + "directive (the C FFI rejection trigger), so no "
                    + "per-backend divergence is sanctioned");
        }
        return Optional.empty();
    }

    /** One divergent entry: mode-closed field set plus per-mode shape rules. */
    private static Optional<ClassificationFailure> checkDivergentEntry(
        ValidationContext context, String prefix, CanonicalJson.Obj entry) {
        Map<String, CanonicalJson.Value> fields = fieldsOf(entry);
        CanonicalJson.Value modeValue = fields.get("mode");
        if (!(modeValue instanceof CanonicalJson.Str mode)) {
            return failure(context, prefix + ".mode", modeValue == null
                ? "missing mandatory mode field"
                : "mode must be a string");
        }
        switch (mode.value()) {
            case "runtime-ok", "runtime-error", "compile-reject" -> { }
            default -> {
                return failure(context, prefix + ".mode", "unknown variant "
                    + describe(modeValue) + " (supported: runtime-ok, "
                    + "runtime-error, compile-reject)");
            }
        }
        if ("compile-reject".equals(mode.value())) {
            for (String key : fields.keySet()) {
                if (!key.equals("mode") && !key.equals("diagnostic")) {
                    return failure(context, prefix + "." + key,
                        "unknown field in a compile-reject backend expectation "
                            + "(a compile-reject entry carries exactly mode and "
                            + "diagnostic — no transcript, exitCode, or error)");
                }
            }
            CanonicalJson.Value diagnosticValue = fields.get("diagnostic");
            if (!(diagnosticValue instanceof CanonicalJson.Obj diagnostic)) {
                return failure(context, prefix + ".diagnostic",
                    diagnosticValue == null
                        ? "missing mandatory diagnostic object"
                        : "diagnostic must be a JSON object");
            }
            return checkRejectDiagnostic(context, prefix + ".diagnostic", diagnostic);
        }
        // Runtime entry: mode + transcript + exitCode + error only; a
        // diagnostic field on a runtime entry is an unknown field.
        for (String key : fields.keySet()) {
            if (!RUNTIME_EXPECTATION_FIELDS.contains(key)) {
                return failure(context, prefix + "." + key,
                    "unknown field in a runtime backend expectation");
            }
        }
        return checkRuntimeExpectation(context, prefix, entry);
    }

    /** The compile-reject diagnostic object: code mandatory, line/column optional. */
    private static Optional<ClassificationFailure> checkRejectDiagnostic(
        ValidationContext context, String prefix, CanonicalJson.Obj diagnostic) {
        Map<String, CanonicalJson.Value> fields = fieldsOf(diagnostic);
        for (String key : fields.keySet()) {
            if (!REJECT_DIAGNOSTIC_FIELDS.contains(key)) {
                return failure(context, prefix + "." + key,
                    "unknown field in the compile-reject diagnostic object "
                        + "(pinnable: code, line, column — message is "
                        + "compile-sidecar-only)");
            }
        }
        CanonicalJson.Value codeValue = fields.get("code");
        if (!(codeValue instanceof CanonicalJson.Str code)) {
            return failure(context, prefix + ".code", codeValue == null
                ? "missing mandatory code field"
                : "code must be a string");
        }
        for (String key : List.of("line", "column")) {
            Optional<ClassificationFailure> intFail =
                checkOptionalIntField(context, prefix + "." + key, fields.get(key));
            if (intFail.isPresent()) {
                return intFail;
            }
        }
        return Optional.empty();
    }

    /**
     * The sanctioned C6 split (corpus Verification 7; gate Verification 6):
     * exactly luajit → runtime expectation, jvm → compile-reject E6006,
     * js → compile-reject E6006. Every other combination fails naming the
     * offending backend entry.
     */
    private static Optional<ClassificationFailure> checkSanctionedSplit(
        ValidationContext context, Map<String, CanonicalJson.Value> entries) {
        String luajitMode = entryMode(entries.get("luajit"));
        if ("compile-reject".equals(luajitMode)) {
            return failure(context, "backends.luajit.mode",
                "compile-reject on the luajit leg is never valid — the "
                    + "sanctioned C6 split requires a runtime expectation on "
                    + "luajit (LuaJIT supports the C FFI contract)");
        }
        String jvmMode = entryMode(entries.get("jvm"));
        if (!"compile-reject".equals(jvmMode)) {
            return failure(context, "backends.jvm.mode",
                "the sanctioned C6 split requires a compile-reject "
                    + "expectation on the jvm leg, got " + describe(jvmMode));
        }
        Optional<ClassificationFailure> jvmCodeFail =
            checkRejectCode(context, "backends.jvm.diagnostic.code",
                entries.get("jvm"));
        if (jvmCodeFail.isPresent()) {
            return jvmCodeFail;
        }
        String jsMode = entryMode(entries.get("js"));
        if (!"compile-reject".equals(jsMode)) {
            return failure(context, "backends.js.mode",
                "the sanctioned C6 split requires a compile-reject "
                    + "expectation on the js leg, got " + describe(jsMode));
        }
        return checkRejectCode(context, "backends.js.diagnostic.code",
            entries.get("js"));
    }

    private static Optional<ClassificationFailure> checkRejectCode(
        ValidationContext context, String field, CanonicalJson.Value entryValue) {
        if (!(entryValue instanceof CanonicalJson.Obj entry)) {
            return Optional.empty(); // per-entry shape validation already failed
        }
        CanonicalJson.Value diagnosticValue = fieldsOf(entry).get("diagnostic");
        if (!(diagnosticValue instanceof CanonicalJson.Obj diagnostic)) {
            return Optional.empty(); // per-entry shape validation already failed
        }
        CanonicalJson.Value codeValue = fieldsOf(diagnostic).get("code");
        if (!(codeValue instanceof CanonicalJson.Str code)) {
            return Optional.empty(); // per-entry shape validation already failed
        }
        if (!FFI_UNSUPPORTED_BACKEND_CODE.equals(code.value())) {
            return failure(context, field,
                "the sanctioned C6 divergence pins the exact diagnostic code "
                    + FFI_UNSUPPORTED_BACKEND_CODE + " (FFI_UNSUPPORTED_BACKEND), "
                    + "got " + code.value());
        }
        return Optional.empty();
    }

    private static String entryMode(CanonicalJson.Value entryValue) {
        if (!(entryValue instanceof CanonicalJson.Obj entry)) {
            return "<invalid entry>";
        }
        CanonicalJson.Value modeValue = fieldsOf(entry).get("mode");
        return modeValue instanceof CanonicalJson.Str mode
            ? mode.value()
            : "<missing mode>";
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
        boolean spanless = SANCTIONED_SPANLESS_FIXTURE
            .equals(context.fixturePath());
        for (String mandatory : spanless
                ? SPANLESS_MANDATORY_ERROR_FIELDS
                : MANDATORY_ERROR_FIELDS) {
            if (!fields.containsKey(mandatory)) {
                return failure(context, prefix + "." + mandatory,
                    "missing mandatory error field \"" + mandatory + "\"");
            }
        }
        // The sanctioned span-less shape closes the other direction too:
        // the retained nowMillis wrapper raises E8004 with no span, so
        // pinning any of the three span fields there is a fabricated
        // value the runtime can never produce — a classification
        // failure, never a tolerated pin.
        if (spanless) {
            for (String key : List.of("sourceFile", "line", "column")) {
                if (fields.containsKey(key)) {
                    return failure(context, prefix + "." + key,
                        "the sanctioned span-less fixture must not pin "
                            + key + " — the retained nowMillis wrapper "
                            + "raises E8004 with no file/line/column "
                            + "(luajit-time-selector-disposition, Failure "
                            + "and operations); a sidecar never pins span "
                            + "values the runtime cannot produce");
                }
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
