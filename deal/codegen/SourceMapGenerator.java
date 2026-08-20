package deal.codegen;

import deal.ast.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records line/column mappings from generated Lua code positions back to
 * original DEAL source {@link Span}s during codegen, and produces a JSON
 * sidecar file after codegen completes.
 *
 * <p>Format per {@code docs/spec-v1.2.md} §Source maps and debugging:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "source": "src/main.deal",
 *   "generated": "build/lua/main.lua",
 *   "mappings": [
 *     { "generatedLine": 10, "generatedColumn": 1,
 *       "sourceLine": 4, "sourceColumn": 1 }
 *   ]
 * }
 * }</pre>
 */
public final class SourceMapGenerator {

    private final List<Mapping> mappings = new ArrayList<>();

    /**
     * A single mapping entry from a generated position back to a DEAL
     * source position.
     */
    public record Mapping(
        int generatedLine,
        int generatedColumn,
        int sourceLine,
        int sourceColumn
    ) {
        public Mapping {
            if (generatedLine < 1) throw new IllegalArgumentException(
                "generatedLine must be >= 1, got " + generatedLine);
            if (generatedColumn < 1) throw new IllegalArgumentException(
                "generatedColumn must be >= 1, got " + generatedColumn);
            if (sourceLine < 1) throw new IllegalArgumentException(
                "sourceLine must be >= 1, got " + sourceLine);
            if (sourceColumn < 1) throw new IllegalArgumentException(
                "sourceColumn must be >= 1, got " + sourceColumn);
        }
    }

    /**
     * Records a mapping from the given generated Lua position to the
     * originating DEAL source span.
     *
     * <p>Called by {@code LuaBackend} before emitting each statement.
     * Only the start line/column of the span are recorded, since that
     * is the primary position used for error reporting.</p>
     */
    public void emitStatement(int generatedLine, int generatedColumn,
                              Span sourceSpan) {
        if (sourceSpan == null) return;
        mappings.add(new Mapping(
            generatedLine,
            generatedColumn,
            sourceSpan.startLine(),
            sourceSpan.startColumn()
        ));
    }

    /**
     * Returns an unmodifiable view of all recorded mappings.
     */
    public List<Mapping> mappings() {
        return Collections.unmodifiableList(mappings);
    }

    /**
     * Returns {@code true} if at least one mapping has been recorded.
     */
    public boolean hasMappings() {
        return !mappings.isEmpty();
    }

    /**
     * Serializes all recorded mappings into the JSON sidecar format.
     *
     * @param sourcePath    the original DEAL source file path
     *                      (e.g. {@code "src/main.deal"})
     * @param generatedPath the generated Lua output path
     *                      (e.g. {@code "build/lua/main.lua"})
     * @return the complete source map JSON string
     */
    public String toJson(String sourcePath, String generatedPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"source\": \"")
            .append(jsonEscape(sourcePath)).append("\",\n");
        sb.append("  \"generated\": \"")
            .append(jsonEscape(generatedPath)).append("\",\n");
        sb.append("  \"mappings\": [\n");

        for (int i = 0; i < mappings.size(); i++) {
            Mapping m = mappings.get(i);
            sb.append("    { \"generatedLine\": ").append(m.generatedLine())
              .append(", \"generatedColumn\": ").append(m.generatedColumn())
              .append(", \"sourceLine\": ").append(m.sourceLine())
              .append(", \"sourceColumn\": ").append(m.sourceColumn());
            sb.append(" }");
            if (i < mappings.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
