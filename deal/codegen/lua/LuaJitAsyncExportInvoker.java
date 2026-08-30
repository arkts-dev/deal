package deal.codegen.lua;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The production LuaJIT host half of {@code BackendAsyncExportInvoker}
 * (luajit-async-export-invoker D1–D10, Architecture item 1): executes a
 * compiled entry artifact under real {@code luajit}, runs module
 * initialization and {@code main()} exactly once in one fresh runtime
 * instance, then calls
 * {@code __rt.invoke_async_export(exports, exportName, returnDescriptor)}
 * and maps the outcome.
 *
 * <h2>Invocation shape (D3)</h2>
 * Every {@link #invoke(AsyncExportInvocationRequest)} call:
 * <ol>
 *   <li>validates the request and derives the deployment root by walking
 *       up from the entry artifact's parent directory to the nearest
 *       ancestor containing {@code deal/runtime.lua} (D5); a missing
 *       entry artifact or marker is a hard failure before any spawn;</li>
 *   <li>locates the committed driver resource
 *       {@code deal/lua_async_export_driver.lua} classpath-first with a
 *       working-tree fallback — the same mechanism the orchestrator uses
 *       for {@code deal/runtime.lua}
 *       ({@code deal/module/CompilationOrchestrator.java:2031-2067}) —
 *       and materializes it into a per-invocation temp file (D1);</li>
 *   <li>spawns exactly one non-detached
 *       {@code ProcessBuilder("luajit", driverPath, entryArtifact,
 *       artifactRoot, exportName, returnDescriptor)} — argv only, no
 *       shell, working directory = artifact root — with stdout/stderr
 *       redirected to temp capture files (no pipe deadlock), waits for
 *       exit, imposes no deadline, and builds no containment (D3, the
 *       delegated containment boundary);</li>
 *   <li>parses the last stdout line with the exact marker prefix
 *       {@value #RESULT_PREFIX} through the strict conventional-JSON
 *       envelope codec (D6, Architecture item 3) and maps the outcome;</li>
 *   <li>deletes the per-invocation temp directory (capture files and the
 *       materialized driver) in a {@code finally} block.</li>
 * </ol>
 *
 * <h2>Outcome mapping (D2/D6/D8)</h2>
 * <ul>
 *   <li>{@code value} envelope → {@link Result.Value} — the
 *       matcher-validated completion as its exact JSON text plus the
 *       descriptor it was matched against;</li>
 *   <li>{@code deal-error} envelope → {@link Result.DealError} with
 *       code/message/file/line/column propagated byte-for-byte and
 *       absent location fields {@code null};</li>
 *   <li>{@code host-failure} envelope → {@link Result.HostFailure} with
 *       the runtime's pinned reason verbatim — missing, sync,
 *       parameterized, duplicate, descriptor-mismatched, non-wrapper,
 *       non-operation exports, and malformed protocol input;</li>
 *   <li>{@code representation-failure} and {@code infrastructure-failure}
 *       envelopes, a missing/malformed envelope, an unknown status or
 *       field, and a nonzero exit without one of the three invocation
 *       envelopes → {@link LuaJitAsyncExportInvocationException} with
 *       the captured process output — never an expected DEAL code.</li>
 * </ul>
 *
 * <p>The component is stateless: one process per invoke, no retry, no
 * state shared between invokes, and concurrent invokes are isolated
 * processes (D3).</p>
 */
public class LuaJitAsyncExportInvoker {

    /** The committed driver distribution resource (D1). */
    public static final String DRIVER_RESOURCE =
        "deal/lua_async_export_driver.lua";

    /** The exact stdout marker prefix of the envelope protocol (D6). */
    public static final String RESULT_PREFIX = "DEAL_ASYNC_EXPORT_RESULT:";

    /** Temp-directory prefix for the per-invocation materialization. */
    static final String TEMP_DIR_PREFIX = "deal-async-export-invoker-";

    private static final String STATUS_VALUE = "value";
    private static final String STATUS_DEAL_ERROR = "deal-error";
    private static final String STATUS_HOST_FAILURE = "host-failure";
    private static final String STATUS_REPRESENTATION_FAILURE =
        "representation-failure";
    private static final String STATUS_INFRASTRUCTURE_FAILURE =
        "infrastructure-failure";

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * The sealed invocation result (D2): exactly three variants and no
     * others, so mistaking a host or infrastructure failure for a DEAL
     * code is impossible at the type level.
     */
    public sealed interface Result
            permits Result.Value, Result.DealError, Result.HostFailure {

        /**
         * The matcher-validated completion (D2/D6/D7).
         *
         * @param returnDescriptor the byte-exact canonical descriptor the
         *                         completion was matched against
         * @param valueJson        the completion value as its exact JSON
         *                         text over the JSON-encodable surface
         *                         (null, booleans, {@code %.17g} numbers,
         *                         UTF-8 strings, arrays, class/plain
         *                         objects)
         */
        record Value(String returnDescriptor, String valueJson)
                implements Result {
            public Value {
                Objects.requireNonNull(returnDescriptor,
                    "returnDescriptor must not be null");
                Objects.requireNonNull(valueJson, "valueJson must not be null");
            }
        }

        /**
         * A DEAL Error raised by module initialization, {@code main()},
         * the operation, or the completion matcher — propagated with
         * code and location unchanged (D8). Absent location fields are
         * {@code null}.
         */
        record DealError(String code, String message, String file,
                         Integer line, Integer column) implements Result {
            public DealError {
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(message, "message must not be null");
            }
        }

        /**
         * The parent's {@code HostInvocationFailure}: a missing, sync,
         * parameterized, duplicate, descriptor-mismatched, non-wrapper,
         * or non-operation export, or malformed protocol input — with
         * the runtime's pinned reason string verbatim (D9). Never a DEAL
         * code.
         */
        record HostFailure(String reason) implements Result {
            public HostFailure {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    /**
     * Invokes one exact async export under one fresh real-LuaJIT
     * process. See the class contract for the full outcome mapping.
     *
     * @param request the three-field request; non-null
     * @return {@link Result.Value}, {@link Result.DealError}, or
     *         {@link Result.HostFailure} — never {@code null}
     * @throws LuaJitAsyncExportInvocationException for infrastructure,
     *         containment, and value-representation failures
     */
    public Result invoke(AsyncExportInvocationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Path entryArtifact = request.entryArtifact().toAbsolutePath().normalize();
        // D5: a missing entry artifact is a hard failure before any spawn.
        if (!Files.isRegularFile(entryArtifact)) {
            throw new LuaJitAsyncExportInvocationException(
                "entry artifact not found: " + entryArtifact, null, null);
        }

        // D5: deployment-root derivation by the runtime marker. A missing
        // marker is a hard failure before any spawn.
        Path artifactRoot = deriveDeploymentRoot(entryArtifact);
        if (artifactRoot == null) {
            throw new LuaJitAsyncExportInvocationException(
                "no deployment root found: no deal/runtime.lua marker in"
                    + " any ancestor directory of " + entryArtifact,
                null, null);
        }

        // D1: driver resource, classpath-first with the working-tree
        // fallback. A missing driver is a hard failure before any spawn.
        final InputStream driverStream;
        try {
            driverStream = locateDriverResource();
        } catch (IOException e) {
            throw new LuaJitAsyncExportInvocationException(
                "cannot read the async-export driver resource "
                    + DRIVER_RESOURCE + ": " + e.getMessage(), null, null, e);
        }
        if (driverStream == null) {
            throw new LuaJitAsyncExportInvocationException(
                "async-export driver resource not found: " + DRIVER_RESOURCE
                    + " (classpath resource and working-tree fallback both"
                    + " absent)", null, null);
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
        } catch (IOException e) {
            throw new LuaJitAsyncExportInvocationException(
                "cannot create the per-invocation temp directory: "
                    + e.getMessage(), null, null, e);
        }

        String stdout = null;
        String stderr = null;
        try (InputStream stream = driverStream) {
            Path driverFile = tempDir.resolve("lua_async_export_driver.lua");
            Files.copy(stream, driverFile);

            Path stdoutFile = tempDir.resolve("stdout.txt");
            Path stderrFile = tempDir.resolve("stderr.txt");

            // D3: exactly one non-detached luajit child per invoke, argv
            // only (no shell), working directory = artifact root, capture
            // files instead of pipes (no pipe deadlock), no deadline, no
            // setsid, no kill escalation.
            ProcessBuilder pb = new ProcessBuilder("luajit",
                driverFile.toString(),
                entryArtifact.toString(),
                artifactRoot.toString(),
                request.exportName(),
                request.returnDescriptor());
            pb.directory(artifactRoot.toFile());
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());

            final Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new LuaJitAsyncExportInvocationException(
                    "cannot spawn luajit: " + e.getMessage(), null, null, e);
            }

            final int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LuaJitAsyncExportInvocationException(
                    "interrupted while waiting for the luajit child",
                    null, null, e);
            }

            try {
                stdout = readCapture(stdoutFile);
                stderr = readCapture(stderrFile);
            } catch (IOException e) {
                throw new LuaJitAsyncExportInvocationException(
                    "cannot read the luajit capture files: " + e.getMessage(),
                    null, null, e);
            }

            return mapOutcome(exit, stdout, stderr);
        } catch (IOException e) {
            throw new LuaJitAsyncExportInvocationException(
                "async-export invoker I/O failure: " + e.getMessage(),
                stdout, stderr, e);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // =========================================================================
    // Driver resource location (D1)
    // =========================================================================

    /**
     * Locates the committed driver resource: classloader resource first,
     * working-tree file fallback — the same mechanism the orchestrator
     * uses for {@code deal/runtime.lua}
     * ({@code deal/module/CompilationOrchestrator.java:2031-2067}).
     *
     * <p>Overridable in tests to hide both lookup sources and exercise
     * the missing-driver hard-failure path.</p>
     *
     * @return an open stream over the driver text, or {@code null} when
     *         neither lookup source carries it
     * @throws IOException when the working-tree file exists but cannot
     *         be opened
     */
    protected InputStream locateDriverResource() throws IOException {
        InputStream stream = getClass().getClassLoader()
            .getResourceAsStream(DRIVER_RESOURCE);
        if (stream != null) {
            return stream;
        }
        Path workingTree = Path.of(DRIVER_RESOURCE);
        if (Files.isRegularFile(workingTree)) {
            return Files.newInputStream(workingTree);
        }
        return null;
    }

    // =========================================================================
    // Deployment-root derivation (D5)
    // =========================================================================

    /**
     * Walks up from the entry artifact's parent directory to the nearest
     * ancestor containing {@code deal/runtime.lua} — the orchestrator's
     * deployment marker ({@code deal/module/CompilationOrchestrator.java:1582}).
     *
     * @return the nearest ancestor deployment root, or {@code null} when
     *         no ancestor carries the marker
     */
    static Path deriveDeploymentRoot(Path entryArtifact) {
        Path dir = entryArtifact.getParent();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("deal/runtime.lua"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // =========================================================================
    // Envelope mapping (D6)
    // =========================================================================

    /** The validated closed-schema envelope of one status. */
    private static final class Envelope {
        final String status;
        final String descriptor;
        final String value;
        final String code;
        final String message;
        final String file;
        final Integer line;
        final Integer column;
        final String reason;

        Envelope(String status, String descriptor, String value, String code,
                 String message, String file, Integer line, Integer column,
                 String reason) {
            this.status = status;
            this.descriptor = descriptor;
            this.value = value;
            this.code = code;
            this.message = message;
            this.file = file;
            this.line = line;
            this.column = column;
            this.reason = reason;
        }
    }

    /**
     * Maps process exit + captured streams to an invocation result or a
     * hard failure (D6). The driver's envelope line is always the last
     * stdout write of the process, so user {@code console.log} output
     * interleaving on both streams never corrupts the frame.
     */
    private Result mapOutcome(int exit, String stdout, String stderr) {
        String line = lastMarkerLine(stdout);
        if (line == null) {
            throw new LuaJitAsyncExportInvocationException(
                "no " + RESULT_PREFIX + " envelope line on stdout (luajit"
                    + " exited " + exit + ")", stdout, stderr);
        }

        final Envelope envelope;
        try {
            envelope = decodeEnvelope(line.substring(RESULT_PREFIX.length()));
        } catch (EnvelopeJson.ParseException e) {
            throw new LuaJitAsyncExportInvocationException(
                "malformed async-export envelope on stdout (luajit exited "
                    + exit + "): " + e.getMessage(), stdout, stderr, e);
        }

        // Status dispatch owns the hard-failure mapping: a nonzero exit
        // without one of the three invocation envelopes (a
        // representation- or infrastructure-failure envelope) throws
        // below regardless of the exit code, the three invocation
        // envelopes map (the driver pins exit 0 for them), and every
        // other shape was already rejected fail-closed by the codec.
        return switch (envelope.status) {
            case STATUS_VALUE -> new Result.Value(
                envelope.descriptor, envelope.value);
            case STATUS_DEAL_ERROR -> new Result.DealError(envelope.code,
                envelope.message, envelope.file, envelope.line,
                envelope.column);
            case STATUS_HOST_FAILURE -> new Result.HostFailure(
                envelope.reason);
            case STATUS_REPRESENTATION_FAILURE ->
                throw new LuaJitAsyncExportInvocationException(
                    "value-representation failure: " + envelope.reason,
                    stdout, stderr);
            case STATUS_INFRASTRUCTURE_FAILURE ->
                throw new LuaJitAsyncExportInvocationException(
                    "infrastructure failure: " + envelope.reason,
                    stdout, stderr);
            default ->
                throw new LuaJitAsyncExportInvocationException(
                    "unknown envelope status: " + envelope.status,
                    stdout, stderr);
        };
    }

    /**
     * The last stdout line carrying the exact marker prefix, or
     * {@code null} when no line carries it.
     */
    static String lastMarkerLine(String stdout) {
        if (stdout == null) {
            return null;
        }
        String[] lines = stdout.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].startsWith(RESULT_PREFIX)) {
                return lines[i];
            }
        }
        return null;
    }

    /**
     * Decodes the envelope JSON text against the closed per-status schema
     * (D6): unknown fields, unknown statuses, wrong field types, and
     * malformed text all fail closed with {@link EnvelopeJson.ParseException}.
     */
    static Envelope decodeEnvelope(String jsonText) {
        EnvelopeJson.ObjectValue obj = EnvelopeJson.parseObject(jsonText);
        String status = requireString(obj, "status", "async-export envelope");
        return switch (status) {
            case STATUS_VALUE -> {
                requireClosed(obj, Set.of("status", "descriptor", "value"),
                    "value");
                String descriptor = requireString(obj, "descriptor",
                    "value envelope");
                String value = requireString(obj, "value",
                    "value envelope");
                yield new Envelope(status, descriptor, value, null, null,
                    null, null, null, null);
            }
            case STATUS_DEAL_ERROR -> {
                requireSubset(obj, Set.of("status", "code", "message",
                    "file", "line", "column"),
                    Set.of("status", "code", "message"), "deal-error");
                String code = requireString(obj, "code",
                    "deal-error envelope");
                String message = requireString(obj, "message",
                    "deal-error envelope");
                String file = optionalString(obj, "file");
                Integer line = optionalInteger(obj, "line");
                Integer column = optionalInteger(obj, "column");
                yield new Envelope(status, null, null, code, message, file,
                    line, column, null);
            }
            case STATUS_HOST_FAILURE -> {
                requireClosed(obj, Set.of("status", "reason"),
                    "host-failure");
                String reason = requireString(obj, "reason",
                    "host-failure envelope");
                yield new Envelope(status, null, null, null, null, null,
                    null, null, reason);
            }
            case STATUS_REPRESENTATION_FAILURE, STATUS_INFRASTRUCTURE_FAILURE -> {
                requireClosed(obj, Set.of("status", "reason"), status);
                String reason = requireString(obj, "reason",
                    status + " envelope");
                yield new Envelope(status, null, null, null, null, null,
                    null, null, reason);
            }
            default -> throw new EnvelopeJson.ParseException(
                "unknown envelope status: " + status);
        };
    }

    private static void requireSubset(EnvelopeJson.ObjectValue obj,
                                     Set<String> allowed,
                                     Set<String> required, String kind) {
        Set<String> keys = obj.fields().keySet();
        if (!allowed.containsAll(keys) || !keys.containsAll(required)) {
            throw new EnvelopeJson.ParseException(
                "malformed " + kind + " envelope: expected the fields "
                    + sortedText(allowed) + " with the required fields "
                    + sortedText(required) + ", got " + sortedText(keys));
        }
    }

    private static void requireClosed(EnvelopeJson.ObjectValue obj,
                                      Set<String> allowed, String kind) {
        Set<String> keys = obj.fields().keySet();
        if (!keys.equals(allowed)) {
            throw new EnvelopeJson.ParseException(
                "malformed " + kind + " envelope: expected exactly the"
                    + " fields " + sortedText(allowed) + ", got "
                    + sortedText(keys));
        }
    }

    private static String requireString(EnvelopeJson.ObjectValue obj,
                                        String key, String kind) {
        EnvelopeJson.Value v = obj.fields().get(key);
        if (!(v instanceof EnvelopeJson.StringValue s)) {
            throw new EnvelopeJson.ParseException(
                "malformed " + kind + ": field '" + key
                    + "' must be a JSON string, got " + kindOf(v));
        }
        return s.text();
    }

    private static String optionalString(EnvelopeJson.ObjectValue obj,
                                         String key) {
        EnvelopeJson.Value v = obj.fields().get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof EnvelopeJson.StringValue s)) {
            throw new EnvelopeJson.ParseException(
                "malformed envelope: optional field '" + key
                    + "' must be a JSON string when present, got "
                    + kindOf(v));
        }
        return s.text();
    }

    private static Integer optionalInteger(EnvelopeJson.ObjectValue obj,
                                           String key) {
        EnvelopeJson.Value v = obj.fields().get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof EnvelopeJson.NumberValue num)
                || !num.isIntegralText()) {
            throw new EnvelopeJson.ParseException(
                "malformed envelope: optional field '" + key
                    + "' must be a JSON integer when present, got "
                    + kindOf(v));
        }
        try {
            long longValue = num.longValue();
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new NumberFormatException("out of int range");
            }
            return (int) longValue;
        } catch (NumberFormatException e) {
            throw new EnvelopeJson.ParseException(
                "malformed envelope: field '" + key
                    + "' is outside the signed32 range: " + num.token());
        }
    }

    private static String kindOf(EnvelopeJson.Value v) {
        if (v == null) {
            return "absent";
        }
        if (v instanceof EnvelopeJson.StringValue) {
            return "string";
        }
        if (v instanceof EnvelopeJson.NumberValue) {
            return "number";
        }
        if (v instanceof EnvelopeJson.BooleanValue) {
            return "boolean";
        }
        if (v instanceof EnvelopeJson.NullValue) {
            return "null";
        }
        return "object";
    }

    private static String sortedText(Set<String> keys) {
        return keys.stream().sorted().toList().toString();
    }

    // =========================================================================
    // Capture-file and temp-directory helpers
    // =========================================================================

    private static String readCapture(Path file) throws IOException {
        try {
            // Fail-closed UTF-8: malformed bytes in a capture file are an
            // invoker hard failure, never silently mis-decoded text.
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("capture file is not valid UTF-8: "
                + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup: the temp dir is system-scoped.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    // =========================================================================
    // Envelope JSON codec (D6, Architecture item 3)
    // =========================================================================

    /**
     * The strict conventional-JSON reader owned by the invoker. The
     * production {@code CanonicalJson}
     * ({@code deal/semantic/ir/CanonicalJson.java}) is canonical-only and
     * rejects conventional decimal numbers ({@code %.17g} text), so this
     * small fail-closed reader is required; the
     * no-second-JSON-library convention is preserved (no org.json, Gson,
     * Jackson, or ObjectMapper anywhere in the invoker).
     *
     * <p>Supported: objects, strings with the RFC 8259 escape set
     * ({@code \" \\ \/ \b \f \n \r \t \\uXXXX} with surrogate-pair
     * combination), conventional decimal numbers
     * ({@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}),
     * {@code true}/{@code false}/{@code null}. Rejected: arrays (not part
     * of the closed envelope schema), duplicate keys, raw control
     * characters in strings, unpaired surrogates, malformed numbers and
     * escapes, and trailing content.</p>
     */
    public static final class EnvelopeJson {

        /** A codec failure: malformed conventional JSON text. */
        public static final class ParseException extends RuntimeException {
            ParseException(String message) {
                super(message);
            }
        }

        /** The closed value model of the reader. */
        public sealed interface Value
                permits EnvelopeJson.ObjectValue, EnvelopeJson.StringValue,
                        EnvelopeJson.NumberValue, EnvelopeJson.BooleanValue,
                        EnvelopeJson.NullValue {
        }

        /**
         * A JSON object with insertion-order-independent field access.
         * Duplicate keys were already rejected by the reader.
         */
        public record ObjectValue(Map<String, Value> fields) implements Value {
            public ObjectValue {
                fields = Map.copyOf(fields);
            }
        }

        /** A JSON string with its decoded text. */
        public record StringValue(String text) implements Value {}

        /**
         * A conventional JSON number, preserving the exact token text
         * ({@code %.17g} output like {@code 0.10000000000000001} is kept
         * byte-for-byte).
         */
        public record NumberValue(String token) implements Value {

            /** True when the token is integral text ({@code -?[0-9]+}). */
            public boolean isIntegralText() {
                return token.matches("-?[0-9]+");
            }

            /** The token parsed as a {@code long}. */
            public long longValue() {
                return Long.parseLong(token);
            }
        }

        /** A JSON boolean. */
        public record BooleanValue(boolean value) implements Value {}

        /** JSON {@code null}. */
        public record NullValue() implements Value {}

        /**
         * Parses conventional JSON text into the value model.
         *
         * @param text the JSON text; non-null
         * @return the parsed value, never {@code null}
         * @throws ParseException on malformed text, duplicate keys,
         *         unpaired surrogates, or trailing content
         */
        public static Value parseValue(String text) {
            Objects.requireNonNull(text, "text must not be null");
            Parser parser = new Parser(text);
            Value value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                throw parser.err("trailing content after the top-level value");
            }
            return value;
        }

        /**
         * Parses conventional JSON text as an object (the envelope
         * protocol's only top-level shape).
         *
         * @param text the JSON object text; non-null
         * @return the parsed object, never {@code null}
         * @throws ParseException on malformed text or a non-object
         *         top-level value
         */
        public static ObjectValue parseObject(String text) {
            Value value = parseValue(text);
            if (!(value instanceof ObjectValue obj)) {
                throw new ParseException(
                    "expected a JSON object, got " + value.getClass()
                        .getSimpleName());
            }
            return obj;
        }

        private EnvelopeJson() {
        }

        /** Recursive-descent conventional-JSON parser. */
        private static final class Parser {

            private final String text;
            private int pos;

            Parser(String text) {
                this.text = text;
            }

            boolean atEnd() {
                return pos >= text.length();
            }

            char peek() {
                return text.charAt(pos);
            }

            ParseException err(String reason) {
                return new ParseException(reason + " at offset " + pos);
            }

            void skipWhitespace() {
                while (!atEnd()) {
                    char c = peek();
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        return;
                    }
                }
            }

            Value parseValue() {
                skipWhitespace();
                if (atEnd()) {
                    throw err("unexpected end of input (expected a value)");
                }
                char c = peek();
                return switch (c) {
                    case '{' -> parseObject();
                    case '"' -> parseString();
                    case 't' -> parseLiteral("true", new BooleanValue(true));
                    case 'f' -> parseLiteral("false", new BooleanValue(false));
                    case 'n' -> parseLiteral("null", new NullValue());
                    default -> {
                        if (c == '-' || (c >= '0' && c <= '9')) {
                            yield parseNumber();
                        }
                        throw err("unexpected character '" + printable(c)
                            + "' (expected a value)");
                    }
                };
            }

            Value parseLiteral(String word, Value value) {
                if (!text.startsWith(word, pos)) {
                    throw err("malformed literal (expected \"" + word + "\")");
                }
                pos += word.length();
                if (!atEnd() && !isDelimiter(peek())) {
                    throw err("malformed literal \"" + word + "\"");
                }
                return value;
            }

            boolean isDelimiter(char c) {
                return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == ',' || c == ']' || c == '}';
            }

            ObjectValue parseObject() {
                pos++; // '{'
                Map<String, Value> fields = new LinkedHashMap<>();
                skipWhitespace();
                if (!atEnd() && peek() == '}') {
                    pos++;
                    return new ObjectValue(fields);
                }
                while (true) {
                    skipWhitespace();
                    if (atEnd() || peek() != '"') {
                        throw err("expected a string object key");
                    }
                    String key = parseString().text();
                    if (fields.containsKey(key)) {
                        throw err("duplicate object key \"" + key + "\"");
                    }
                    skipWhitespace();
                    if (atEnd() || peek() != ':') {
                        throw err("expected ':' after object key");
                    }
                    pos++;
                    fields.put(key, parseValue());
                    skipWhitespace();
                    if (atEnd()) {
                        throw err("unterminated object (expected ',' or '}')");
                    }
                    char c = peek();
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == '}') {
                        pos++;
                        return new ObjectValue(fields);
                    }
                    throw err("expected ',' or '}' in object");
                }
            }

            StringValue parseString() {
                pos++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (atEnd()) {
                        throw err("unterminated string");
                    }
                    char c = text.charAt(pos++);
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\') {
                        if (atEnd()) {
                            throw err("unterminated string escape");
                        }
                        char e = text.charAt(pos++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> sb.append(parseUnicodeEscape());
                            default -> throw err(
                                "invalid string escape '\\" + e + "'");
                        }
                    } else if (c < 0x20) {
                        throw err("raw control character in string");
                    } else {
                        sb.append(c);
                    }
                }
                return new StringValue(sb.toString());
            }

            /** Decodes one {@code \\uXXXX} escape, combining surrogate pairs. */
            private String parseUnicodeEscape() {
                if (pos + 4 > text.length()) {
                    throw err("truncated \\u escape");
                }
                int value = readHex4();
                if (value >= 0xD800 && value <= 0xDBFF) {
                    // High surrogate: must be followed by a low one.
                    if (pos + 6 > text.length() || text.charAt(pos) != '\\'
                            || text.charAt(pos + 1) != 'u') {
                        throw err("unpaired high surrogate in \\u escape");
                    }
                    pos += 2;
                    int low = readHex4();
                    if (low < 0xDC00 || low > 0xDFFF) {
                        throw err("unpaired high surrogate in \\u escape");
                    }
                    int codePoint = 0x10000 + ((value - 0xD800) << 10)
                        + (low - 0xDC00);
                    return new String(Character.toChars(codePoint));
                }
                if (value >= 0xDC00 && value <= 0xDFFF) {
                    throw err("unpaired low surrogate in \\u escape");
                }
                return String.valueOf((char) value);
            }

            private int readHex4() {
                int value = 0;
                for (int i = 0; i < 4; i++) {
                    if (pos >= text.length()) {
                        throw err("truncated \\u escape");
                    }
                    int digit = Character.digit(text.charAt(pos++), 16);
                    if (digit < 0) {
                        throw err("invalid \\u escape (non-hex digit)");
                    }
                    value = (value << 4) | digit;
                }
                return value;
            }

            NumberValue parseNumber() {
                int start = pos;
                if (!atEnd() && peek() == '-') {
                    pos++;
                }
                if (atEnd()) {
                    throw err("malformed number");
                }
                char c = peek();
                if (c == '0') {
                    pos++;
                } else if (c >= '1' && c <= '9') {
                    do {
                        pos++;
                    } while (!atEnd() && isDigit(peek()));
                } else {
                    throw err("malformed number (expected a digit)");
                }
                if (!atEnd() && peek() == '.') {
                    pos++;
                    if (atEnd() || !isDigit(peek())) {
                        throw err("malformed number (fraction needs a digit)");
                    }
                    do {
                        pos++;
                    } while (!atEnd() && isDigit(peek()));
                }
                if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                    pos++;
                    if (!atEnd() && (peek() == '+' || peek() == '-')) {
                        pos++;
                    }
                    if (atEnd() || !isDigit(peek())) {
                        throw err("malformed number (exponent needs a digit)");
                    }
                    do {
                        pos++;
                    } while (!atEnd() && isDigit(peek()));
                }
                return new NumberValue(text.substring(start, pos));
            }

            boolean isDigit(char c) {
                return c >= '0' && c <= '9';
            }

            String printable(char c) {
                return c < 0x20 || c > 0x7E
                    ? String.format("\\u%04x", (int) c) : String.valueOf(c);
            }
        }
    }
}
