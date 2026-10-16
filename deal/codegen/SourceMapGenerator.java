package deal.codegen;

import deal.ast.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records line/column mappings from generated code positions back to
 * original DEAL source {@link Span}s during codegen, and produces a JSON
 * sidecar file after codegen completes.
 *
 * <p>Implements the generated-to-source sidecars required by
 * {@code docs/spec-v1.3.md} §Source maps and debugging. The JSON shape
 * below is this compiler's sidecar format, not a specification-defined schema:
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
     * Records the originating span's start at a generated position.
     */
    public void emitStatement(int generatedLine, int generatedColumn,
                              Span sourceSpan) {
        if (sourceSpan == null) return;
        emitStatement(generatedLine, generatedColumn,
            sourceSpan.startLine(), sourceSpan.startColumn());
    }

    /**
     * Records a mapping from the given generated position to a DEAL source
     * line/column pair. This is the span-neutral overload the shared
     * LuaJIT/JVM project emitters record through (their semantic-IR origins
     * carry {@code deal.semantic.ir.SourceSpan}, never an AST {@link Span}),
     * so one mapping format and one serializer serve every backend.
     */
    public void emitStatement(int generatedLine, int generatedColumn,
                              int sourceLine, int sourceColumn) {
        mappings.add(new Mapping(
            generatedLine,
            generatedColumn,
            sourceLine,
            sourceColumn
        ));
    }

    public List<Mapping> mappings() {
        return Collections.unmodifiableList(mappings);
    }

    public boolean hasMappings() {
        return !mappings.isEmpty();
    }

    /**
     * Serializes all recorded mappings into the JSON sidecar format.
     *
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

    /**
     * Escapes one path string into JSON string content. Strict JSON allows
     * no raw character below U+0020 inside a string: {@code "} and
     * {@code \} take their mandatory escapes, LF/CR/TAB keep the short
     * standard spellings, and every other control character takes the
     * four-hex-digit backslash-u escape spelling. Every ordinary path byte
     * is unchanged and the decoded value is the path text exactly, so an
     * accepted source or output path carrying a control character still
     * yields a sidecar that strict JSON consumers parse.
     */
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
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
