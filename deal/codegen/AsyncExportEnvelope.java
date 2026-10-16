package deal.codegen;

import deal.codegen.lua.LuaJitAsyncExportInvoker.EnvelopeJson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

public final class AsyncExportEnvelope {

    /**
     * The shared stdout marker prefix of the async-export envelope
     * protocol: the LuaJIT/JVM invokers and the runtime host surfaces all
     * derive their marker from this one constant.
     */
    public static final String RESULT_PREFIX = "DEAL_ASYNC_EXPORT_RESULT:";

    public static final String STATUS_VALUE = "value";
    public static final String STATUS_DEAL_ERROR = "deal-error";
    public static final String STATUS_HOST_FAILURE = "host-failure";
    public static final String STATUS_REPRESENTATION_FAILURE =
        "representation-failure";
    public static final String STATUS_INFRASTRUCTURE_FAILURE =
        "infrastructure-failure";

    private AsyncExportEnvelope() {
    }

    /** A recursively validated language error; absent optional fields are null. */
    public record ErrorDetail(String code, String message, String file,
                              Integer line, Integer column, String expected,
                              String actual, String frames, ErrorDetail cause) {}

    /** The validated closed-schema envelope of one status. */
    public static final class Envelope {
        public final String status;
        public final String descriptor;
        public final String value;
        public final String code;
        public final String message;
        public final String file;
        public final Integer line;
        public final Integer column;
        /**
         * The deal-error detail's expected/actual pair and ordered frame
         * text (the complete ErrorDetail projection): absent optional
         * metadata stays {@code null}.
         */
        public final String expected;
        public final String actual;
        public final String frames;
        public final ErrorDetail cause;
        public final String reason;

        /**
         * The retained status-shape constructor: callers that construct
         * an envelope without the optional deal-error detail keep working,
         * with expected/actual/frames absent ({@code null}).
         */
        public Envelope(String status, String descriptor, String value, String code,
                        String message, String file, Integer line, Integer column,
                        String reason) {
            this(status, descriptor, value, code, message, file, line, column,
                null, null, null, reason);
        }

        public Envelope(String status, String descriptor, String value, String code,
                        String message, String file, Integer line, Integer column,
                        String expected, String actual, String frames,
                        String reason) {
            this(status, descriptor, value, code, message, file, line, column,
                expected, actual, frames, null, reason);
        }

        public Envelope(String status, String descriptor, String value, String code,
                        String message, String file, Integer line, Integer column,
                        String expected, String actual, String frames,
                        ErrorDetail cause, String reason) {
            this.status = status;
            this.descriptor = descriptor;
            this.value = value;
            this.code = code;
            this.message = message;
            this.file = file;
            this.line = line;
            this.column = column;
            this.expected = expected;
            this.actual = actual;
            this.frames = frames;
            this.cause = cause;
            this.reason = reason;
        }
    }

    /**
     * The last stdout line carrying the exact marker prefix, or
     * {@code null} when no line carries it.
     */
    public static String lastMarkerLine(String stdout, String prefix) {
        if (stdout == null) {
            return null;
        }
        String[] lines = stdout.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].startsWith(prefix)) {
                return lines[i];
            }
        }
        return null;
    }

    /**
     * Decodes the envelope JSON text against the closed per-status schema:
     * unknown fields, unknown statuses, wrong field types, and malformed
     * text all fail closed with {@link EnvelopeJson.ParseException}.
     */
    public static Envelope decodeEnvelope(String jsonText) {
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
                    null, null, null, null, null, null, null);
            }
            case STATUS_DEAL_ERROR -> {
                // The deal-error arm carries the complete ErrorDetail
                // contract: the required code/message plus the optional
                // location, expected/actual and ordered frame text.
                ErrorDetail detail = decodeError(obj, true);
                yield new Envelope(status, null, null, detail.code(),
                    detail.message(), detail.file(), detail.line(), detail.column(),
                    detail.expected(), detail.actual(), detail.frames(),
                    detail.cause(), null);
            }
            case STATUS_HOST_FAILURE -> {
                requireClosed(obj, Set.of("status", "reason"),
                    "host-failure");
                String reason = requireString(obj, "reason",
                    "host-failure envelope");
                yield new Envelope(status, null, null, null, null, null,
                    null, null, null, null, null, reason);
            }
            case STATUS_REPRESENTATION_FAILURE, STATUS_INFRASTRUCTURE_FAILURE -> {
                requireClosed(obj, Set.of("status", "reason"), status);
                String reason = requireString(obj, "reason",
                    status + " envelope");
                yield new Envelope(status, null, null, null, null, null,
                    null, null, null, null, null, reason);
            }
            default -> throw new EnvelopeJson.ParseException(
                "unknown envelope status: " + status);
        };
    }

    private static ErrorDetail decodeError(EnvelopeJson.ObjectValue obj,
                                            boolean envelope) {
        requireSubset(obj, envelope
                ? Set.of("status", "code", "message", "file", "line", "column",
                    "expected", "actual", "frames", "cause")
                : Set.of("code", "message", "file", "line", "column",
                    "expected", "actual", "frames", "cause"),
            envelope ? Set.of("status", "code", "message")
                : Set.of("code", "message"), "deal-error");
        ErrorDetail cause = null;
        if (obj.fields().containsKey("cause")) {
            if (!(obj.fields().get("cause") instanceof EnvelopeJson.ObjectValue nested)) {
                throw new EnvelopeJson.ParseException("cause must be an error object");
            }
            cause = decodeError(nested, false);
        }
        return new ErrorDetail(requireString(obj, "code", "deal-error"),
            requireString(obj, "message", "deal-error"), optionalString(obj, "file"),
            optionalInteger(obj, "line"), optionalInteger(obj, "column"),
            optionalString(obj, "expected"), optionalString(obj, "actual"),
            optionalString(obj, "frames"), cause);
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

    /** The fail-closed UTF-8 text of one capture file. */
    public static String readCapture(Path file) throws IOException {
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

    /** Best-effort recursive deletion of one temp directory. */
    public static void deleteRecursively(Path dir) {
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
}
