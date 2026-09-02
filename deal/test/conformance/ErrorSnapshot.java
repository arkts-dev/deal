package deal.test.conformance;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.SemanticIrTextDecodeException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The canonical Error Snapshot serialization (corpus
 * {@code v12-three-backend-conformance-corpus} C2) and the G4.6 lane
 * error framing the StructuredExpectationComparator consumes.
 *
 * <p>Canonical serialization is fully pinned: one single-line JSON object
 * with the fixed key order {@code code}, {@code message},
 * {@code sourceFile}, {@code line}, {@code column}, then pinned optional
 * fields in spec order {@code expected}, {@code actual}, {@code frames},
 * {@code cause} — only pinned fields are emitted. Strings use minimal
 * RFC 8259 section-7 escaping: only the double quote and the backslash
 * are escaped, and control characters U+0000 through U+001F are escaped
 * with the named escapes {@code \b} {@code \t} {@code \n} {@code \f}
 * {@code \r} or, for the remaining control characters, the
 * four-hex-digit escape spelling with uppercase hex digits; non-ASCII
 * characters are emitted raw as UTF-8, never unicode-escaped;
 * {@code /} is never escaped. Integers are canonical decimal with no
 * leading zeros, sign, or exponent. There is no whitespace between
 * tokens.</p>
 *
 * <p>The lane error framing (G4.6) is lane-owned and identical on every
 * backend: on an uncaught DEAL error the lane writes to stdout exactly</p>
 * <pre>{@code
 * DEAL_ERROR_CODE: <code>
 * DEAL_ERROR_SNAPSHOT: <canonical JSON>
 * }</pre>
 * <p>and exits 1. The snapshot framing line ends with one newline
 * character; the framing lines are the final two lines of stdout.</p>
 */
public final class ErrorSnapshot {

    private ErrorSnapshot() {
        // Static utility; no instances.
    }

    /** The G4.6 framing line prefixes. */
    public static final String CODE_LINE_PREFIX = "DEAL_ERROR_CODE: ";
    public static final String SNAPSHOT_LINE_PREFIX = "DEAL_ERROR_SNAPSHOT: ";

    /** The canonical snapshot key order (C2). */
    public static final List<String> CANONICAL_KEY_ORDER =
        List.of("code", "message", "sourceFile", "line", "column",
            "expected", "actual", "frames", "cause");

    // =========================================================================
    // Canonical serialization (C2)
    // =========================================================================

    /**
     * Serializes the error fields to the canonical single-line snapshot
     * JSON (fixed key order, pinned optionals only, minimal escaping,
     * raw UTF-8 non-ASCII, canonical decimal integers, no whitespace
     * between tokens, no trailing newline).
     */
    public static String canonicalJson(SidecarExpectations.ErrorExpectation fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        StringBuilder sb = new StringBuilder("{");
        appendStringField(sb, "code", fields.code(), true);
        appendStringField(sb, "message", fields.message(), false);
        appendStringField(sb, "sourceFile", fields.sourceFile(), false);
        appendIntField(sb, "line", fields.line(), false);
        appendIntField(sb, "column", fields.column(), false);
        fields.expected().ifPresent(v ->
            appendStringField(sb, "expected", v, false));
        fields.actual().ifPresent(v -> appendStringField(sb, "actual", v, false));
        fields.frames().ifPresent(v -> appendIntField(sb, "frames", v, false));
        fields.cause().ifPresent(v -> appendStringField(sb, "cause", v, false));
        return sb.append('}').toString();
    }

    private static void appendStringField(StringBuilder sb, String key,
            String stringValue, boolean first) {
        if (!first) {
            sb.append(',');
        }
        appendString(sb, key);
        sb.append(':');
        appendString(sb, stringValue);
    }

    /** Canonical decimal integer: no leading zeros, sign, or exponent. */
    private static void appendIntField(StringBuilder sb, String key,
            int value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        appendString(sb, key);
        sb.append(':');
        sb.append(Integer.toString(value));
    }

    /** Minimal RFC 8259 §7 escaping with raw UTF-8 non-ASCII (C2). */
    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (cp < 0x20) {
                        sb.append(String.format("\\u%04X", cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Validates one snapshot text against the canonical serialization and
     * returns the first violation description, or empty when the text is
     * canonical. Validation parses the structure (rejecting malformed
     * JSON, duplicate keys, non-canonical numbers), extracts the error
     * fields, re-serializes them with {@link #canonicalJson}, and
     * byte-compares the result with the input — so a wrong key order, an
     * over-escaped or unicode-escaped string, inter-token whitespace,
     * or a leading-zero integer all fail the byte comparison.
     */
    public static Optional<String> canonicalViolation(String snapshotText) {
        Objects.requireNonNull(snapshotText, "snapshotText must not be null");
        CanonicalJson.Value root;
        try {
            root = CanonicalJson.parse(snapshotText);
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            return Optional.of("the snapshot is not valid canonical JSON: "
                + e.getMessage());
        }
        if (!(root instanceof CanonicalJson.Obj obj)) {
            return Optional.of("the snapshot must be a single JSON object");
        }
        SidecarExpectations.ErrorExpectation fields;
        try {
            fields = extractFields(obj);
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        String canonical = canonicalJson(fields);
        if (!canonical.equals(snapshotText)) {
            int diff = firstDifference(canonical, snapshotText);
            return Optional.of("the snapshot violates the canonical "
                + "serialization at byte " + diff + ": expected "
                + boundedContext(canonical, diff) + ", got "
                + boundedContext(snapshotText, diff));
        }
        return Optional.empty();
    }

    /**
     * Extracts the snapshot's error fields from a parsed object, enforcing
     * the closed field set (the five mandatory fields plus exactly the
     * four optional fields) and field types.
     */
    public static SidecarExpectations.ErrorExpectation extractFields(
            CanonicalJson.Obj obj) {
        java.util.Map<String, CanonicalJson.Value> fields =
            new java.util.LinkedHashMap<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (fields.putIfAbsent(entry.key(), entry.value()) != null) {
                throw new IllegalArgumentException(
                    "duplicate snapshot key \"" + entry.key() + "\"");
            }
        }
        for (String key : fields.keySet()) {
            if (!CANONICAL_KEY_ORDER.contains(key)) {
                throw new IllegalArgumentException(
                    "unknown snapshot field \"" + key + "\" (the closed field "
                        + "set is code, message, sourceFile, line, column, "
                        + "expected, actual, frames, cause)");
            }
        }
        for (String mandatory : List.of("code", "message", "sourceFile",
                "line", "column")) {
            if (!fields.containsKey(mandatory)) {
                throw new IllegalArgumentException(
                    "missing mandatory snapshot field \"" + mandatory + "\"");
            }
        }
        for (String key : List.of("code", "message", "sourceFile", "expected",
                "actual", "cause")) {
            CanonicalJson.Value value = fields.get(key);
            if (value != null && !(value instanceof CanonicalJson.Str)) {
                throw new IllegalArgumentException(
                    "snapshot field " + key + " must be a string");
            }
        }
        for (String key : List.of("line", "column", "frames")) {
            CanonicalJson.Value value = fields.get(key);
            if (value != null && !(value instanceof CanonicalJson.Int)) {
                throw new IllegalArgumentException(
                    "snapshot field " + key + " must be an integer");
            }
        }
        return new SidecarExpectations.ErrorExpectation(
            stringOrNull(fields, "code"), stringOrNull(fields, "message"),
            stringOrNull(fields, "sourceFile"),
            intOrNull(fields, "line"), intOrNull(fields, "column"),
            Optional.ofNullable(stringOrNull(fields, "expected")),
            Optional.ofNullable(stringOrNull(fields, "actual")),
            Optional.ofNullable(intOrNull(fields, "frames")),
            Optional.ofNullable(stringOrNull(fields, "cause")));
    }

    private static String stringOrNull(
            java.util.Map<String, CanonicalJson.Value> fields, String key) {
        CanonicalJson.Value value = fields.get(key);
        return value == null ? null : ((CanonicalJson.Str) value).value();
    }

    private static Integer intOrNull(
            java.util.Map<String, CanonicalJson.Value> fields, String key) {
        CanonicalJson.Value value = fields.get(key);
        return value == null ? null : ((CanonicalJson.Int) value).value();
    }

    private static int firstDifference(String expected, String actual) {
        int limit = Math.min(expected.length(), actual.length());
        for (int i = 0; i < limit; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return i;
            }
        }
        return limit;
    }

    private static String boundedContext(String text, int index) {
        int from = Math.max(0, index - 8);
        int to = Math.min(text.length(), index + 8);
        return describe(text.substring(from, to));
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (cp < 0x20) {
                        sb.append(String.format("\\u%04X", cp));
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // =========================================================================
    // G4.6 lane error framing
    // =========================================================================

    /** One parsed framing: the code line, the raw snapshot text, its fields. */
    public record Framed(String code, String snapshotText,
                         SidecarExpectations.ErrorExpectation fields) {

        public Framed {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(snapshotText, "snapshotText must not be null");
            Objects.requireNonNull(fields, "fields must not be null");
        }
    }

    /**
     * Parses the G4.6 lane error framing from captured stdout bytes and
     * validates the snapshot's canonical serialization. Returns the
     * framed error, or {@link Optional#empty()} with the violation
     * description when the framing is missing, malformed, or the snapshot
     * violates the canonical serialization.
     *
     * <p>The framing lines must be exactly the final two lines of stdout:
     * the {@code DEAL_ERROR_SNAPSHOT:} line terminates stdout with one
     * {@code \n}, the {@code DEAL_ERROR_CODE:} line is the line directly
     * before it, and neither prefix occurs anywhere else.</p>
     */
    public static Optional<Framed> parseFraming(byte[] stdout) {
        Objects.requireNonNull(stdout, "stdout must not be null");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(stdout))
                .toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        if (!text.endsWith("\n")) {
            return Optional.empty();
        }
        String[] segments = text.split("\n", -1);
        // segments = [..., codeLine, snapshotLine, ""] — the final split
        // segment is empty because stdout ends with '\n'.
        if (segments.length < 3) {
            return Optional.empty();
        }
        String snapshotLine = segments[segments.length - 2];
        String codeLine = segments[segments.length - 3];
        if (!snapshotLine.startsWith(SNAPSHOT_LINE_PREFIX)
                || !codeLine.startsWith(CODE_LINE_PREFIX)) {
            return Optional.empty();
        }
        // The framing pair must be unique: no earlier line may carry
        // either prefix.
        for (int i = 0; i < segments.length - 3; i++) {
            if (segments[i].startsWith(SNAPSHOT_LINE_PREFIX)
                    || segments[i].startsWith(CODE_LINE_PREFIX)) {
                return Optional.empty(); // the framing appears more than once
            }
        }
        String code = codeLine.substring(CODE_LINE_PREFIX.length());
        String snapshotText = snapshotLine.substring(SNAPSHOT_LINE_PREFIX.length());
        if (code.isEmpty() || snapshotText.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> violation = canonicalViolation(snapshotText);
        if (violation.isPresent()) {
            return Optional.empty();
        }
        SidecarExpectations.ErrorExpectation fields;
        try {
            fields = extractFields((CanonicalJson.Obj) CanonicalJson.parse(
                snapshotText));
        } catch (SemanticIrTextDecodeException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!code.equals(fields.code())) {
            return Optional.empty(); // the framing code must equal the snapshot code
        }
        return Optional.of(new Framed(code, snapshotText, fields));
    }

    /**
     * Returns the violation description for an empty
     * {@link #parseFraming} result, used by the comparator to name the
     * malformed framing.
     */
    public static String framingViolationDetail(byte[] stdout) {
        String text = new String(stdout, StandardCharsets.UTF_8);
        String shown = text.replace("\n", "\\n").replace("\r", "\\r");
        if (shown.length() > 80) {
            shown = shown.substring(0, 77) + "...";
        }
        return "the lane's stdout carries no well-formed "
            + "DEAL_ERROR_CODE/DEAL_ERROR_SNAPSHOT framing as its final two "
            + "lines (a single newline-terminated canonical snapshot) — got: "
            + shown;
    }
}
