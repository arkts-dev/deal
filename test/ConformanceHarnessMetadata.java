package deal.test;

import java.util.List;

/**
 * Harness-only classification-header stripping seam (ISSUE-0272,
 * design fixed-name-directive-events D8 items 1–2).
 *
 * <p>The conformance corpus classifies fixtures through five
 * directive-shaped header comment lines ({@code // @spec:},
 * {@code // @description:}, {@code // @expected:}, {@code // @features:},
 * {@code // @issue:}), which the harnesses read from the raw fixture
 * bytes in {@code parseMetadata} ({@code test/ConformanceTest.java},
 * {@code test/JvmConformanceTest.java}). Classification keeps reading the
 * raw bytes, but every harness site that hands corpus {@code .deal}
 * bytes to a compiler — the in-memory lexer sites and the three JVM
 * temp-project materialization copy sites — strips those headers before
 * any lexer sees them: once the fixed-name directive scanner activates
 * E1044 for unknown directive names, each header line would otherwise
 * become a compile-time error in harness compiles and in the production
 * {@code CompilationOrchestrator} run over the JVM temp project.</p>
 *
 * <p>This helper is referenced only from {@code test/}; production
 * sources never strip or whitelist directive-shaped metadata — a
 * production compile of a header-shaped line is exactly E1044
 * (parent design deal-v1.2-directives-and-c-ffi-declarations D12).</p>
 */
public final class ConformanceHarnessMetadata {

    /**
     * The five classification header prefixes in catalog order — the
     * exact trimmed-prefix strings both harnesses match in
     * {@code parseMetadata}.
     */
    private static final List<String> HEADER_PREFIXES = List.of(
        "// @spec:", "// @description:", "// @expected:",
        "// @features:", "// @issue:");

    private ConformanceHarnessMetadata() { }

    /**
     * True when {@code line}'s trimmed form starts with one of the five
     * classification header prefixes (the same trimmed-prefix check
     * {@code parseMetadata} applies to raw fixture lines).
     */
    static boolean isClassificationHeaderLine(String line) {
        String trimmed = line.trim();
        for (String prefix : HEADER_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code source} with every classification-header line
     * removed.
     *
     * <p>A full line whose trimmed form starts with one of the five
     * header prefixes is dropped (line content plus its terminator).
     * LF, CRLF, and CR line endings are all handled: terminators are
     * matched per character ({@code \n}, {@code \r\n}, {@code \r}), so
     * a CR-only file is not mangled into LF lines. Every non-matching
     * line — including its original terminator characters — is appended
     * verbatim from the input, so its bytes are unchanged. A final line
     * without a terminator is still classified, and is dropped when it
     * is a header. A source without any header line is returned
     * unchanged (the same String instance).</p>
     */
    public static String stripClassificationHeaders(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean droppedAny = false;
        int lineStart = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                if (!isClassificationHeaderLine(
                        source.substring(lineStart, i))) {
                    out.append(source, lineStart, i + 1);
                } else {
                    droppedAny = true;
                }
                lineStart = i + 1;
                i++;
            } else if (c == '\r') {
                int contentEnd = i;
                i++;
                if (i < source.length() && source.charAt(i) == '\n') {
                    i++;
                }
                if (!isClassificationHeaderLine(
                        source.substring(lineStart, contentEnd))) {
                    out.append(source, lineStart, i);
                } else {
                    droppedAny = true;
                }
                lineStart = i;
            } else {
                i++;
            }
        }
        if (lineStart < source.length()) {
            if (!isClassificationHeaderLine(
                    source.substring(lineStart))) {
                out.append(source, lineStart, source.length());
            } else {
                droppedAny = true;
            }
        }
        return droppedAny ? out.toString() : source;
    }
}
