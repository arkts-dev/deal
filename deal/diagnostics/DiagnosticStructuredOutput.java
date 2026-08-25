package deal.diagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Canonical structured JSON diagnostic output (D8): version 1, one
 * deterministic document with a fixed field order, diagnostics in list
 * order, notes in order.
 *
 * <pre>
 * {
 *   "version": 1,
 *   "diagnostics": [
 *     {
 *       "code": "E3001",
 *       "severity": "error",
 *       "message": "...",
 *       "range": {
 *         "file": "src/main.deal",
 *         "startLine": 2,
 *         "startColumn": 5,
 *         "endLine": 2,
 *         "endColumn": 9,
 *         "startScalarOffset": 20,
 *         "endScalarOffset": 24,
 *         "scalarLength": 4,
 *         "origin": "SOURCE"
 *       },
 *       "notes": [
 *         { "message": "...", "range": { ... } | null }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Every range field carries the complete carrier data: start/end
 * positions, both scalar offsets, the scalar length, and the origin
 * ({@code "SOURCE"} / {@code "SYNTHETIC"}). Notes preserve secondary
 * ranges as nested range objects; a message-only note serializes
 * {@code "range": null}. A null diagnostics list yields the empty
 * document.</p>
 *
 * <p>JDK-only: the serializer is hand-rolled with the minimal standard
 * JSON string escaping (quotes and backslashes escaped, control
 * characters below U+0020 escaped as {@code \b}, {@code \f}, {@code \n},
 * {@code \r}, {@code \t}, or a four-digit hex escape). Every string input is
 * non-null after D9 normalization. The document is deterministic: fixed
 * field order, fixed indentation, no trailing newline.</p>
 */
public final class DiagnosticStructuredOutput {

    /** No instances: the structured output is a pure static utility. */
    private DiagnosticStructuredOutput() {
    }

    /**
     * Serializes the diagnostics as the canonical structured JSON document
     * (version 1).
     *
     * @param diagnostics the normalized diagnostics in emission order; a
     *                    null list yields the empty document
     * @return the deterministic JSON document with the exact D8 field
     *         order
     * @throws NullPointerException if a list element is null
     */
    public static String toJson(List<CompilerDiagnostic> diagnostics) {
        List<CompilerDiagnostic> list = diagnostics == null ? List.of() : diagnostics;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
            .append("  \"version\": 1,\n")
            .append("  \"diagnostics\": ");
        if (list.isEmpty()) {
            sb.append("[]\n")
                .append('}');
            return sb.toString();
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            appendDiagnostic(sb, list.get(i), i == list.size() - 1);
        }
        sb.append("  ]\n")
            .append('}');
        return sb.toString();
    }

    /** Appends one {@code { code, severity, message, range, notes }} object. */
    private static void appendDiagnostic(StringBuilder sb, CompilerDiagnostic d, boolean last) {
        Objects.requireNonNull(d, "diagnostic element must not be null");
        indent(sb, 2).append("{\n");
        indent(sb, 3).append("\"code\": ").append(jsonString(d.code())).append(",\n");
        indent(sb, 3).append("\"severity\": ").append(jsonString(d.severity())).append(",\n");
        indent(sb, 3).append("\"message\": ").append(jsonString(d.message())).append(",\n");
        appendRange(sb, d.range(), 3);
        sb.append(",\n");
        appendNotes(sb, d.notes(), 3);
        indent(sb, 2).append('}').append(last ? "\n" : ",\n");
    }

    /**
     * Appends a {@code "range": { ... }} object whose closing brace is
     * followed by no separator (the caller owns the trailing comma or
     * newline).
     */
    private static void appendRange(StringBuilder sb, DiagnosticRange range, int level) {
        indent(sb, level).append("\"range\": {\n");
        indent(sb, level + 1).append("\"file\": ").append(jsonString(range.file())).append(",\n");
        indent(sb, level + 1).append("\"startLine\": ").append(range.startLine()).append(",\n");
        indent(sb, level + 1).append("\"startColumn\": ").append(range.startColumn()).append(",\n");
        indent(sb, level + 1).append("\"endLine\": ").append(range.endLine()).append(",\n");
        indent(sb, level + 1).append("\"endColumn\": ").append(range.endColumn()).append(",\n");
        indent(sb, level + 1).append("\"startScalarOffset\": ")
            .append(range.startScalarOffset()).append(",\n");
        indent(sb, level + 1).append("\"endScalarOffset\": ")
            .append(range.endScalarOffset()).append(",\n");
        indent(sb, level + 1).append("\"scalarLength\": ")
            .append(range.scalarLength()).append(",\n");
        indent(sb, level + 1).append("\"origin\": ")
            .append(jsonString(range.origin().name())).append('\n');
        indent(sb, level).append('}');
    }

    /** Appends the {@code "notes": [ ... ]} list with its trailing newline. */
    private static void appendNotes(StringBuilder sb, List<DiagnosticNote> notes, int level) {
        indent(sb, level).append("\"notes\": ");
        if (notes.isEmpty()) {
            sb.append("[]\n");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < notes.size(); i++) {
            appendNote(sb, notes.get(i), level + 1, i == notes.size() - 1);
        }
        indent(sb, level).append("]\n");
    }

    /** Appends one {@code { message, range }|{ message, range: null }} object. */
    private static void appendNote(StringBuilder sb, DiagnosticNote note, int level,
                                   boolean last) {
        indent(sb, level).append("{\n");
        indent(sb, level + 1).append("\"message\": ")
            .append(jsonString(note.message())).append(",\n");
        DiagnosticRange range = note.range();
        if (range == null) {
            indent(sb, level + 1).append("\"range\": null\n");
        } else {
            appendRange(sb, range, level + 1);
            sb.append('\n');
        }
        indent(sb, level).append('}').append(last ? "\n" : ",\n");
    }

    /** Appends {@code level} two-space indent units and returns {@code sb}. */
    private static StringBuilder indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb;
    }

    /** The minimal standard JSON string escaping (JDK-only). */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
