package deal.diagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Canonical structured JSON diagnostic output (D8): version 2, one
 * deterministic document with a fixed field order, diagnostics in list
 * order, notes in order.
 *
 * <pre>
 * {
 *   "version": 2,
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
 *       ],
 *       "facts": { "reason": "...", "operation": "...", "expected": "...",
 *                  "actual": "...", "guidance": ["..."], "path": [] },
 *       "missingSymbols": [{ "kind": "VALUE", "name": "...", "namespace": "...",
 *                            "constraints": [] }]
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
     * (version 2).
     *
     */
    public static String toJson(List<CompilerDiagnostic> diagnostics) {
        List<CompilerDiagnostic> list = diagnostics == null ? List.of() : diagnostics;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
            .append("  \"version\": 2,\n")
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

    /** Emits producer facts and missing names without interpreting message text. */
    private static void appendDiagnostic(StringBuilder sb, CompilerDiagnostic d, boolean last) {
        Objects.requireNonNull(d, "diagnostic element must not be null");
        indent(sb, 2).append("{\n");
        indent(sb, 3).append("\"code\": ").append(jsonString(d.code())).append(",\n");
        indent(sb, 3).append("\"severity\": ").append(jsonString(d.severity())).append(",\n");
        indent(sb, 3).append("\"message\": ").append(jsonString(d.message())).append(",\n");
        appendRange(sb, d.range(), 3);
        sb.append(",\n");
        appendNotes(sb, d.notes(), 3);
        sb.append(",\n");
        appendFacts(sb, d.facts(), 3);
        sb.append(",\n");
        appendMissingSymbols(sb, d.missingSymbols(), 3);
        sb.append('\n');
        indent(sb, 2).append('}').append(last ? "\n" : ",\n");
    }

    private static void appendFacts(StringBuilder sb, DiagnosticFacts facts, int level) {
        indent(sb, level).append("\"facts\": {\n");
        indent(sb, level + 1).append("\"reason\": ").append(jsonString(facts.reason())).append(",\n");
        indent(sb, level + 1).append("\"operation\": ").append(jsonString(facts.operation())).append(",\n");
        indent(sb, level + 1).append("\"expected\": ").append(jsonString(facts.expected())).append(",\n");
        indent(sb, level + 1).append("\"actual\": ").append(jsonString(facts.actual())).append(",\n");
        indent(sb, level + 1).append("\"guidance\": ");
        appendStrings(sb, facts.guidance());
        sb.append(",\n");
        indent(sb, level + 1).append("\"path\": [");
        for (int i = 0; i < facts.path().size(); i++) {
            if (i > 0) sb.append(", ");
            var segment = facts.path().get(i);
            sb.append("{\"kind\": ").append(jsonString(segment.kind().name()))
                    .append(", \"name\": ").append(jsonString(segment.name())).append('}');
        }
        sb.append("],\n");
        indent(sb, level + 1).append("\"call\": ");
        var call = facts.call();
        if (call == null) sb.append("null");
        else {
            sb.append("{\n");
            indent(sb, level + 2).append("\"callee\": ").append(jsonString(call.callee())).append(",\n");
            indent(sb, level + 2).append("\"signature\": ").append(jsonString(call.signature())).append(",\n");
            if (call.declarationRange() == null) indent(sb, level + 2).append("\"declarationRange\": null");
            else appendRange(sb, call.declarationRange(), level + 2, "declarationRange");
            sb.append(",\n");
            indent(sb, level + 2).append("\"argumentIndex\": ").append(call.argumentIndex()).append(",\n");
            indent(sb, level + 2).append("\"parameterName\": ").append(jsonString(call.parameterName())).append(",\n");
            indent(sb, level + 2).append("\"expectedCount\": ").append(call.expectedCount()).append(",\n");
            indent(sb, level + 2).append("\"actualCount\": ").append(call.actualCount()).append(",\n");
            if (call.parameterRange() == null) indent(sb, level + 2).append("\"parameterRange\": null");
            else appendRange(sb, call.parameterRange(), level + 2, "parameterRange");
            sb.append('\n');
            indent(sb, level + 1).append('}');
        }
        sb.append(",\n");
        indent(sb, level + 1).append("\"operator\": ");
        var operator = facts.operator();
        if (operator == null) sb.append("null");
        else {
            sb.append("{\n");
            indent(sb, level + 2).append("\"token\": ").append(jsonString(operator.token())).append(",\n");
            indent(sb, level + 2).append("\"operands\": [");
            for (int i = 0; i < operator.operands().size(); i++) {
                if (i > 0) sb.append(',');
                var operand = operator.operands().get(i);
                sb.append('\n');
                indent(sb, level + 3).append("{\n");
                indent(sb, level + 4).append("\"role\": ").append(jsonString(operand.role())).append(",\n");
                indent(sb, level + 4).append("\"type\": ").append(jsonString(operand.type())).append(",\n");
                appendRange(sb, operand.range(), level + 4);
                sb.append('\n');
                indent(sb, level + 3).append('}');
            }
            if (!operator.operands().isEmpty()) { sb.append('\n'); indent(sb, level + 2); }
            sb.append("],\n");
            indent(sb, level + 2).append("\"acceptedCombinations\": ");
            appendStrings(sb, operator.acceptedCombinations());
            sb.append('\n');
            indent(sb, level + 1).append('}');
        }
        sb.append(",\n");
        indent(sb, level + 1).append("\"mismatch\": ");
        var mismatch = facts.mismatch();
        if (mismatch == null) sb.append("null");
        else {
            sb.append("{\n");
            indent(sb, level + 2).append("\"rule\": ").append(jsonString(mismatch.rule())).append(",\n");
            indent(sb, level + 2).append("\"path\": [");
            for (int i = 0; i < mismatch.path().size(); i++) {
                if (i > 0) sb.append(", ");
                var component = mismatch.path().get(i);
                sb.append("{\"kind\": ").append(jsonString(component.kind().name()))
                        .append(", \"index\": ").append(component.index()).append('}');
            }
            sb.append("],\n");
            indent(sb, level + 2).append("\"expected\": ").append(jsonString(mismatch.expected())).append(",\n");
            indent(sb, level + 2).append("\"actual\": ").append(jsonString(mismatch.actual())).append(",\n");
            indent(sb, level + 2).append("\"truncated\": ").append(mismatch.truncated()).append('\n');
            indent(sb, level + 1).append('}');
        }
        sb.append(",\n");
        indent(sb, level + 1).append("\"syntax\": ");
        var syntax = facts.syntax();
        if (syntax == null) sb.append("null\n");
        else {
            sb.append("{\n");
            indent(sb, level + 2).append("\"context\": ").append(jsonString(syntax.context())).append(",\n");
            indent(sb, level + 2).append("\"expectedKinds\": ");
            appendStrings(sb, syntax.expectedKinds());
            sb.append(",\n");
            indent(sb, level + 2).append("\"foundKind\": ").append(jsonString(syntax.foundKind())).append(",\n");
            indent(sb, level + 2).append("\"lexeme\": ").append(jsonString(syntax.lexeme())).append(",\n");
            indent(sb, level + 2).append("\"lexemeLength\": ").append(syntax.lexemeLength()).append(",\n");
            indent(sb, level + 2).append("\"truncated\": ").append(syntax.truncated()).append('\n');
            indent(sb, level + 1).append("}\n");
        }
        indent(sb, level).append('}');
    }

    private static void appendMissingSymbols(StringBuilder sb, List<CompilerDiagnostic.MissingSymbol> facts, int level) {
        indent(sb, level).append("\"missingSymbols\": [");
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) sb.append(',');
            var fact = facts.get(i);
            sb.append("\n");
            indent(sb, level + 1).append("{\"kind\": ").append(jsonString(fact.kind()))
                    .append(", \"name\": ").append(jsonString(fact.name()))
                    .append(", \"namespace\": ").append(jsonString(fact.namespace()))
                    .append(", \"constraints\": ");
            appendStrings(sb, fact.constraints());
            sb.append('}');
        }
        if (!facts.isEmpty()) { sb.append('\n'); indent(sb, level); }
        sb.append(']');
    }

    private static void appendStrings(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jsonString(values.get(i)));
        }
        sb.append(']');
    }

    /**
     * Appends a {@code "range": { ... }} object whose closing brace is
     * followed by no separator (the caller owns the trailing comma or
     * newline).
     */
    private static void appendRange(StringBuilder sb, DiagnosticRange range, int level) {
        appendRange(sb, range, level, "range");
    }

    private static void appendRange(StringBuilder sb, DiagnosticRange range, int level, String name) {
        indent(sb, level).append(jsonString(name)).append(": {\n");
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

    /** Appends notes without a trailing separator. */
    private static void appendNotes(StringBuilder sb, List<DiagnosticNote> notes, int level) {
        indent(sb, level).append("\"notes\": ");
        if (notes.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < notes.size(); i++) {
            appendNote(sb, notes.get(i), level + 1, i == notes.size() - 1);
        }
        indent(sb, level).append(']');
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
                    if (c < 0x20 || (Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && i + 1 < s.length()
                                && Character.isLowSurrogate(s.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0
                                && Character.isHighSurrogate(s.charAt(i - 1))))) {
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
