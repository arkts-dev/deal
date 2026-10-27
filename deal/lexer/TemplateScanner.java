package deal.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * DEAL v1.4 template-literal structure scanner (docs/spec-v1.5.md lexical
 * grammar).
 *
 * <pre>
 * TemplateLiteral ::= '`' (TemplateText | '${' Expression '}')* '`'
 * TemplateText    ::= /* one or more characters containing no line
 *                        terminator, '`', or '${' *&#47;
 * </pre>
 *
 * <p>TemplateText has no escape mechanism: a backslash is an ordinary
 * TemplateText character, and only a line terminator, a backtick, or the
 * {@code ${} opener ends the text run. Inside an interpolation the normal
 * expression lexical rules apply: whitespace including line terminators,
 * {@code //} and {@code /* *&#47;} comments, the five-escape quoted strings,
 * nested template literals, and balanced braces.</p>
 *
 * <p>The scan is a pure structural walk: it records text and interpolation
 * segment ranges and never produces diagnostics, directive events, or
 * tokens. The lexer uses it to find the closing backtick of a template
 * token (the returned {@link Status} distinguishes the failure kinds); the
 * parser uses it to split the raw token content into its text and
 * interpolation segments. Because the scan never re-decodes or rewrites the
 * scanned text, a caller holding the raw slice can rebase sub-lexed
 * coordinates one-to-one.</p>
 */
public final class TemplateScanner {

    /** How a scan stopped. */
    public enum Status {
        /** A closing backtick was reached; {@code endIndex} is past it. */
        TERMINATED,
        /** TemplateText hit a line terminator; {@code endIndex} is at it. */
        TEXT_LINE_TERMINATOR,
        /** The scanned text ended inside TemplateText (no closing backtick). */
        EOF,
        /**
         * The scanned text ended inside an interpolation: the last
         * interpolation segment is unterminated and runs to
         * {@code endIndex}.
         */
        UNTERMINATED_INTERPOLATION
    }

    /**
     * One scanned segment, a half-open UTF-16 range. An interpolation
     * segment spans the whole {@code ${...}} region: {@code start} is the
     * {@code '$'} opener, {@code end} is the closing {@code '}'} (or the
     * stop index when unterminated), and the expression content is
     * {@code [start + 2, end)}.
     */
    public record Segment(int start, int end, boolean interpolation,
                          boolean unterminated) {
    }

    /**
     * One scan outcome: the stop status, the stop index, and the segments
     * in source order.
     */
    public record Scan(Status status, int endIndex, List<Segment> segments) {
    }

    private TemplateScanner() {
        // Static utility; no instances.
    }

    /**
     * Scans template content from {@code start}: {@code source} is the
     * complete text available to the scan (the lexer passes the whole
     * source; the parser passes the raw template content), and
     * {@code start} is the index of the first content character (just past
     * the opening backtick).
     *
     * <p>Never throws; an empty or past-end {@code start} reports
     * {@link Status#EOF} with no segments.</p>
     */
    public static Scan scan(String source, int start) {
        return scanContent(source, start);
    }

    private static Scan scanContent(String source, int start) {
        List<Segment> segments = new ArrayList<>();
        int pos = Math.max(0, start);
        int textStart = pos;

        while (pos < source.length()) {
            char c = source.charAt(pos);

            if (c == '\n' || c == '\r') {
                addText(segments, textStart, pos);
                return new Scan(Status.TEXT_LINE_TERMINATOR, pos,
                    List.copyOf(segments));
            }
            if (c == '`') {
                addText(segments, textStart, pos);
                return new Scan(Status.TERMINATED, pos + 1,
                    List.copyOf(segments));
            }
            if (c == '$' && pos + 1 < source.length()
                    && source.charAt(pos + 1) == '{') {
                addText(segments, textStart, pos);
                int opener = pos;
                int exprStart = pos + 2;
                InterpolationScan interp = scanInterpolation(source, exprStart);
                if (interp.terminated()) {
                    segments.add(new Segment(opener, interp.index(), true,
                        false));
                    pos = interp.index() + 1;
                    textStart = pos;
                    continue;
                }
                segments.add(new Segment(opener, interp.index(), true,
                    true));
                if (interp.outerTerminator() >= 0) {
                    // Recovery: the backtick that opened an unterminated
                    // nested template is also the only candidate closing
                    // delimiter of this template, so the outer token ends
                    // there (the interpolation stays unterminated for the
                    // parser's own scan of the returned lexeme).
                    return new Scan(Status.TERMINATED,
                        interp.outerTerminator() + 1, List.copyOf(segments));
                }
                return new Scan(interp.status(), interp.index(),
                    List.copyOf(segments));
            }

            // Every other scalar — backslash, '}', '$' not followed by '{',
            // whitespace, quoted strings are not special at TemplateText
            // level — is ordinary text.
            pos++;
        }

        addText(segments, textStart, pos);
        return new Scan(Status.EOF, pos, List.copyOf(segments));
    }

    /**
     * The interpolation scan result: terminated at {@code index}, or a stop
     * status. {@code outerTerminator} is the backtick index of an
     * unterminated nested template when that backtick is the only candidate
     * closing delimiter of the enclosing template, else {@code -1}.
     */
    private record InterpolationScan(boolean terminated, Status status,
                                     int index, int outerTerminator) {
    }

    /**
     * Scans one interpolation expression starting just after its
     * {@code ${} opener. Returns the index of the closing {@code }} for a
     * terminated interpolation, or the propagation of the nested stop (a
     * line terminator inside a nested template's TemplateText, or the end
     * of the scanned text).
     */
    private static InterpolationScan scanInterpolation(String source, int start) {
        int pos = start;
        int depth = 0;

        while (pos < source.length()) {
            char c = source.charAt(pos);

            if (c == '}') {
                if (depth == 0) {
                    return new InterpolationScan(true, null, pos, -1);
                }
                depth--;
                pos++;
                continue;
            }
            if (c == '{') {
                depth++;
                pos++;
                continue;
            }
            if (c == '`') {
                // A nested template literal: it is itself a TemplateLiteral
                // production, so scan it recursively; its own
                // interpolation braces are balanced inside it.
                int nestedBacktick = pos;
                Scan nested = scanContent(source, pos + 1);
                if (nested.status() == Status.TERMINATED) {
                    pos = nested.endIndex();
                    continue;
                }
                return new InterpolationScan(false, nested.status(),
                    nestedBacktick, nestedBacktick);
            }
            if (c == '"' || c == '\'') {
                pos = skipQuoted(source, pos);
                continue;
            }
            if (c == '/' && pos + 1 < source.length()) {
                char next = source.charAt(pos + 1);
                if (next == '/') {
                    pos = skipLineComment(source, pos);
                    continue;
                }
                if (next == '*') {
                    pos = skipBlockComment(source, pos);
                    continue;
                }
            }
            pos++;
        }

        return new InterpolationScan(false,
            Status.UNTERMINATED_INTERPOLATION, pos, -1);
    }

    /** Adds the text run {@code [start, end)} when it is non-empty. */
    private static void addText(List<Segment> segments, int start, int end) {
        if (end > start) {
            segments.add(new Segment(start, end, false, false));
        }
    }

    /**
     * Skips a quoted string starting at its opening quote: the five-escape
     * table applies ({@code \} escapes the next scalar), and an unterminated
     * string consumes to the end of the scanned text.
     */
    private static int skipQuoted(String source, int start) {
        char quote = source.charAt(start);
        int pos = start + 1;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\\' && pos + 1 < source.length()) {
                pos += 2;
                continue;
            }
            if (c == quote) {
                return pos + 1;
            }
            pos++;
        }
        return pos;
    }

    /** Skips a {@code //} line comment: to the terminator or end of text. */
    private static int skipLineComment(String source, int start) {
        int pos = start + 2;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n' || c == '\r') {
                return pos;
            }
            pos++;
        }
        return pos;
    }

    /** Skips a {@code /* *&#47;} block comment: to past its {@code *&#47;} or end of text. */
    private static int skipBlockComment(String source, int start) {
        int pos = start + 2;
        while (pos + 1 < source.length()) {
            if (source.charAt(pos) == '*' && source.charAt(pos + 1) == '/') {
                return pos + 2;
            }
            pos++;
        }
        return source.length();
    }
}
