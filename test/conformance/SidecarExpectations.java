package deal.test.conformance;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The typed structured-expectation model of the v1.2 sidecar schema
 * (corpus {@code v12-three-backend-conformance-corpus} C2) as consumed by
 * the gate's comparators and lanes.
 *
 * <p>This is the parse surface, not the validation surface: the gate
 * validates every sidecar with T1's {@link SidecarSchemaValidator} before
 * calling these parsers, so a parse failure here is a harness defect
 * (defense in depth, reported as a classification failure naming the
 * sidecar).</p>
 *
 * <p>{@link ErrorExpectation} models the sidecar-authoritative error field
 * set: the mandatory {@code code}/{@code message}, the span group
 * {@code sourceFile}/{@code line}/{@code column}, and the pinned optional
 * fields exactly {@code expected}/{@code actual}/{@code frames}/
 * {@code cause} (C2 — the lane emits exactly the fields the sidecar pins
 * and suppresses every unpinned one). The span group is pinned by every
 * runtime-error sidecar except the one sanctioned span-less shape: the
 * locked time selector's retained {@code nowMillis} wrapper raises E8004
 * with no file/line/column at all
 * ({@code luajit-time-selector-disposition}, Failure and operations —
 * "the E8004 carries the route's existing shape (the retained wrapper
 * passes no span)"), so
 * {@code backend-runtime/stdlib-edge/time-now-millis-positive.deal}'s
 * sidecar omits the whole span group (all three fields null) — a sidecar
 * must never pin span values the runtime cannot produce.</p>
 */
public final class SidecarExpectations {

    private SidecarExpectations() {
        // Static utility; no instances.
    }

    /**
     * One Error Expectation (C2): the mandatory DEALRuntimeError fields
     * {@code code}/{@code message}, the span group
     * {@code sourceFile}/{@code line}/{@code column} (all three non-null
     * when pinned, all three null for the one sanctioned span-less shape
     * — the locked time selector's retained {@code nowMillis} wrapper
     * raises E8004 with no span), and the pinned optional fields. An
     * empty optional means "not pinned" (the lane must suppress the
     * field).
     */
    public record ErrorExpectation(
        String code,
        String message,
        String sourceFile,
        Integer line,
        Integer column,
        Optional<String> expected,
        Optional<String> actual,
        Optional<Integer> frames,
        Optional<String> cause
    ) {

        public ErrorExpectation {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            if ((sourceFile == null) != (line == null)
                    || (sourceFile == null) != (column == null)) {
                throw new IllegalArgumentException(
                    "the span group (sourceFile, line, column) is pinned "
                        + "together: all three non-null or all three null "
                        + "(the sanctioned span-less shape)");
            }
            expected = Objects.requireNonNull(expected, "expected must not be null");
            actual = Objects.requireNonNull(actual, "actual must not be null");
            frames = Objects.requireNonNull(frames, "frames must not be null");
            cause = Objects.requireNonNull(cause, "cause must not be null");
        }

        /** True when the span group (sourceFile, line, column) is pinned. */
        public boolean pinsSpan() {
            return sourceFile != null;
        }

        /** True when the sidecar pins the optional field {@code field}. */
        public boolean pinsOptional(String field) {
            return switch (field) {
                case "expected" -> expected.isPresent();
                case "actual" -> actual.isPresent();
                case "frames" -> frames.isPresent();
                case "cause" -> cause.isPresent();
                default -> throw new IllegalArgumentException(
                    "not an optional error field: " + field);
            };
        }
    }

    /**
     * One runtime expectation of one backend lane. The sealed variants are
     * closed: an executed expectation pins the exact transcript bytes and
     * exit code (plus the Error Expectation for {@code runtime-error});
     * a rejected expectation pins the exact compile-reject diagnostic
     * object (the sanctioned C6 divergence only — schema validation has
     * already closed every other shape).
     */
    public sealed interface RuntimeExpectation
            permits RuntimeExpectation.Executed, RuntimeExpectation.Rejected {

        /** The schema mode string: runtime-ok, runtime-error, compile-reject. */
        String mode();

        /**
         * An executed expectation: exact stdout/stderr bytes (UTF-8), the
         * exact exit code, and the Error Expectation for runtime-error
         * (null for runtime-ok).
         */
        record Executed(String mode, byte[] stdout, byte[] stderr,
                        int exitCode, ErrorExpectation error)
                implements RuntimeExpectation {

            public Executed {
                Objects.requireNonNull(mode, "mode must not be null");
                Objects.requireNonNull(stdout, "stdout must not be null");
                Objects.requireNonNull(stderr, "stderr must not be null");
            }

            /** True for {@code mode: "runtime-error"}. */
            public boolean isRuntimeError() {
                return "runtime-error".equals(mode);
            }
        }

        /**
         * A compile-reject expectation: the exact pinned diagnostic object
         * (mandatory code plus optional-pinned line/column).
         */
        record Rejected(String mode, String code, OptionalInt line,
                        OptionalInt column)
                implements RuntimeExpectation {

            public Rejected {
                Objects.requireNonNull(mode, "mode must not be null");
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(line, "line must not be null");
                Objects.requireNonNull(column, "column must not be null");
            }
        }
    }

    /**
     * One validated runtime sidecar: the expectation of every backend of
     * the three-backend set (uniform form: one shared expectation; the
     * divergent C6 form: one expectation per backend entry).
     */
    public record StructuredExpectationSidecar(
            Map<String, RuntimeExpectation> byBackend) {

        public StructuredExpectationSidecar {
            Objects.requireNonNull(byBackend, "byBackend must not be null");
            for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
                if (!byBackend.containsKey(backend)) {
                    throw new IllegalArgumentException(
                        "a runtime sidecar must carry the expectation of "
                            + "backend " + backend);
                }
            }
            byBackend = Map.copyOf(byBackend);
        }

        /** The expectation of one backend lane. */
        public RuntimeExpectation expectationFor(String backend) {
            return byBackend.get(backend);
        }

        /**
         * Parses a schema-validated runtime sidecar text into the typed
         * model (uniform or divergent form).
         */
        public static StructuredExpectationSidecar parse(String json) {
            CanonicalJson.Obj root = parseRoot(json);
            CanonicalJson.Value backends = field(root, "backends");
            Map<String, RuntimeExpectation> expectations = new LinkedHashMap<>();
            if (backends instanceof CanonicalJson.Arr arr) {
                RuntimeExpectation expected = parseRuntimeExpectation(
                    (CanonicalJson.Obj) field(root, "expected"),
                    "<expected>");
                for (CanonicalJson.Value item : arr.items()) {
                    String name = ((CanonicalJson.Str) item).value();
                    expectations.put(name, expected);
                }
            } else if (backends instanceof CanonicalJson.Obj obj) {
                for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
                    expectations.put(backend, parseRuntimeExpectation(
                        (CanonicalJson.Obj) field(obj, backend),
                        "backends." + backend));
                }
            } else {
                throw new IllegalArgumentException(
                    "the sidecar's backends key is neither the uniform array "
                        + "nor the divergent per-backend object");
            }
            return new StructuredExpectationSidecar(expectations);
        }

        private static RuntimeExpectation parseRuntimeExpectation(
                CanonicalJson.Obj expectation, String prefix) {
            String mode = stringField(expectation, "mode", prefix + ".mode");
            if ("compile-reject".equals(mode)) {
                CanonicalJson.Obj diagnostic =
                    (CanonicalJson.Obj) field(expectation, "diagnostic");
                return new RuntimeExpectation.Rejected(mode,
                    stringField(diagnostic, "code", prefix + ".diagnostic.code"),
                    optionalIntField(diagnostic, "line"),
                    optionalIntField(diagnostic, "column"));
            }
            CanonicalJson.Obj transcript =
                (CanonicalJson.Obj) field(expectation, "transcript");
            byte[] stdout = stringField(transcript, "stdout",
                prefix + ".transcript.stdout").getBytes(StandardCharsets.UTF_8);
            byte[] stderr = stringField(transcript, "stderr",
                prefix + ".transcript.stderr").getBytes(StandardCharsets.UTF_8);
            int exitCode = intField(expectation, "exitCode",
                prefix + ".exitCode");
            ErrorExpectation error = null;
            if ("runtime-error".equals(mode)) {
                error = parseErrorExpectation(
                    (CanonicalJson.Obj) field(expectation, "error"),
                    prefix + ".error");
            }
            return new RuntimeExpectation.Executed(mode, stdout, stderr,
                exitCode, error);
        }

        private static ErrorExpectation parseErrorExpectation(
                CanonicalJson.Obj error, String prefix) {
            return new ErrorExpectation(
                stringField(error, "code", prefix + ".code"),
                stringField(error, "message", prefix + ".message"),
                nullableStringField(error, "sourceFile"),
                nullableIntField(error, "line"),
                nullableIntField(error, "column"),
                optionalStringField(error, "expected"),
                optionalStringField(error, "actual"),
                boxed(optionalIntField(error, "frames")),
                optionalStringField(error, "cause"));
        }
    }

    /**
     * One Compile Expectation Sidecar pin (the Compile Diagnostic
     * comparison): mandatory {@code code} plus optional-pinned
     * {@code line}/{@code column}/{@code message} (message is
     * compile-sidecar-only).
     */
    public record CompileDiagnosticPin(String code, OptionalInt line,
            OptionalInt column, Optional<String> message) {

        public CompileDiagnosticPin {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(line, "line must not be null");
            Objects.requireNonNull(column, "column must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }

        /**
         * Parses a schema-validated Compile Expectation Sidecar text into
         * the typed pin.
         */
        public static CompileDiagnosticPin parse(String json) {
            CanonicalJson.Obj root = parseRoot(json);
            CanonicalJson.Obj diagnostic =
                (CanonicalJson.Obj) field(root, "diagnostic");
            return new CompileDiagnosticPin(
                stringField(diagnostic, "code", "diagnostic.code"),
                optionalIntField(diagnostic, "line"),
                optionalIntField(diagnostic, "column"),
                optionalStringField(diagnostic, "message"));
        }
    }

    // =========================================================================
    // CanonicalJson field helpers
    // =========================================================================

    private static CanonicalJson.Obj parseRoot(String json) {
        CanonicalJson.Value root;
        try {
            root = CanonicalJson.parse(json);
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "malformed sidecar JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof CanonicalJson.Obj obj)) {
            throw new IllegalArgumentException(
                "the sidecar root must be a JSON object");
        }
        return obj;
    }

    private static CanonicalJson.Value field(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    private static String stringField(CanonicalJson.Obj obj, String key,
            String path) {
        CanonicalJson.Value value = field(obj, key);
        if (!(value instanceof CanonicalJson.Str str)) {
            throw new IllegalArgumentException(path + " must be a JSON string");
        }
        return str.value();
    }

    /**
     * The nullable string field (the span group's {@code sourceFile}):
     * absent is null — the sanctioned span-less error shape.
     */
    private static String nullableStringField(CanonicalJson.Obj obj,
            String key) {
        CanonicalJson.Value value = field(obj, key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof CanonicalJson.Str str)) {
            throw new IllegalArgumentException(
                key + " must be a JSON string when pinned");
        }
        return str.value();
    }

    /**
     * The nullable integer field (the span group's {@code line}/
     * {@code column}): absent is null — the sanctioned span-less error
     * shape.
     */
    private static Integer nullableIntField(CanonicalJson.Obj obj,
            String key) {
        CanonicalJson.Value value = field(obj, key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof CanonicalJson.Int integer)) {
            throw new IllegalArgumentException(
                key + " must be a JSON integer when pinned");
        }
        return integer.value();
    }

    private static int intField(CanonicalJson.Obj obj, String key, String path) {
        CanonicalJson.Value value = field(obj, key);
        if (!(value instanceof CanonicalJson.Int integer)) {
            throw new IllegalArgumentException(path + " must be a JSON integer");
        }
        return integer.value();
    }

    private static Optional<String> optionalStringField(CanonicalJson.Obj obj,
            String key) {
        CanonicalJson.Value value = field(obj, key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof CanonicalJson.Str str)) {
            throw new IllegalArgumentException(
                key + " must be a JSON string when pinned");
        }
        return Optional.of(str.value());
    }

    private static Optional<Integer> boxed(OptionalInt value) {
        return value.isPresent()
            ? Optional.of(value.getAsInt())
            : Optional.empty();
    }

    private static OptionalInt optionalIntField(CanonicalJson.Obj obj,
            String key) {
        CanonicalJson.Value value = field(obj, key);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (!(value instanceof CanonicalJson.Int integer)) {
            throw new IllegalArgumentException(
                key + " must be a JSON integer when pinned");
        }
        return OptionalInt.of(integer.value());
    }
}
