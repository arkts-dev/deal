package deal.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared template structure and escape processing. Scans retain raw UTF-16
 * ranges; the parser decodes text once before creating semantic values.
 * Nested templates own their escape diagnostics through expression lexing.
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

    public enum Delimiter {
        /** A backslash starts an invalid numeric/hex/Unicode escape. */
        ESCAPE_PREFIX
    }

    /**
     * An invalid template escape's half-open UTF-16 source range.
     * Scanning preserves raw text; the parser decodes accepted text.
     */
    public record Violation(int start, int end, Delimiter delimiter) {
    }

    /**
     * One scan outcome: the stop status, the stop index, the segments and
     * the invalid escapes of the scanned level, in source
     * order.
     */
    public record Scan(Status status, int endIndex, List<Segment> segments,
                       List<Violation> violations) {
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
        List<Violation> violations = new ArrayList<>();
        int pos = Math.max(0, start);
        int textStart = pos;

        while (pos < source.length()) {
            char c = source.charAt(pos);

            if (c == '\n' || c == '\r') {
                addText(segments, textStart, pos);
                return new Scan(Status.TEXT_LINE_TERMINATOR, pos,
                    List.copyOf(segments), List.copyOf(violations));
            }
            if (c == '`') {
                addText(segments, textStart, pos);
                return new Scan(Status.TERMINATED, pos + 1,
                    List.copyOf(segments), List.copyOf(violations));
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
                        interp.outerTerminator() + 1, List.copyOf(segments),
                        List.copyOf(violations));
                }
                return new Scan(interp.status(), interp.index(),
                    List.copyOf(segments), List.copyOf(violations));
            }

            if (c == '\\') {
                int next = pos + 1;
                if (next < source.length() && invalidEscape(source, next, source.charAt(next))) {
                    violations.add(new Violation(pos, next + 1, Delimiter.ESCAPE_PREFIX));
                }
                if (next < source.length() && source.charAt(next) == '\r'
                        && next + 1 < source.length() && source.charAt(next + 1) == '\n') {
                    pos = next + 2;
                } else {
                    pos = Math.min(next + 1, source.length());
                }
                continue;
            }

            // Every other scalar — '}', '$' not followed by '{',
            // whitespace, quoted strings are not special at TemplateText
            // level — is ordinary text.
            pos++;
        }

        addText(segments, textStart, pos);
        return new Scan(Status.EOF, pos, List.copyOf(segments),
            List.copyOf(violations));
    }

    /** Decodes validated untagged-template text; ranges always retain the raw source. */
    public static String decodeText(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 == raw.length()) {
                out.append(c);
                continue;
            }
            c = raw.charAt(++i);
            switch (c) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'v' -> out.append('\u000b');
                case '0' -> out.append('\0');
                case '\n', '\u2028', '\u2029' -> { }
                case '\r' -> { if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++; }
                case 'x', 'u' -> {
                    boolean braces = c == 'u' && i + 1 < raw.length() && raw.charAt(i + 1) == '{';
                    int start = i + (braces ? 2 : 1);
                    int end = braces ? raw.indexOf('}', start) : start + (c == 'x' ? 2 : 4);
                    if (end < start || end > raw.length()) { out.append(c); break; }
                    try {
                        int point = 0;
                        for (int digit = start; digit < end; digit++) {
                            int value = hex(raw.charAt(digit));
                            if (value < 0 || point > 0x10ffff / 16) throw new IllegalArgumentException();
                            point = point * 16 + value;
                        }
                        out.appendCodePoint(point);
                        i = braces ? end : end - 1;
                    } catch (IllegalArgumentException invalid) {
                        // Lexer owns malformed spellings; recovery must not throw.
                        out.append(c);
                    }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Validates the ordinary-string escape table at its character after the backslash. */
    public static boolean validStringEscape(String source, int pos) {
        char next = source.charAt(pos);
        return switch (next) {
            case 'n', 'r', 't', '\\', '\"', '\'' -> true;
            case 'x', 'u' -> !invalidEscape(source, pos, next);
            default -> false;
        };
    }

    private static boolean invalidEscape(String source, int pos, char next) {
        if (next >= '1' && next <= '9') return true;
        if (next == '0') return pos + 1 < source.length()
            && source.charAt(pos + 1) >= '0' && source.charAt(pos + 1) <= '9';
        if (next == 'x') return !hexDigits(source, pos + 1, 2);
        if (next != 'u') return false;
        if (pos + 1 >= source.length() || source.charAt(pos + 1) != '{')
            return !hexDigits(source, pos + 1, 4);
        int i = pos + 2;
        long value = 0;
        int digits = 0;
        while (i < source.length() && hex(source.charAt(i)) >= 0) {
            // Saturate rather than overflow on an arbitrarily long spelling.
            value = Math.min(0x110000L, value * 16 + hex(source.charAt(i++)));
            digits++;
        }
        return digits == 0 || value > 0x10ffff || i >= source.length() || source.charAt(i) != '}';
    }

    private static boolean hexDigits(String source, int start, int count) {
        if (start + count > source.length()) return false;
        for (int i = start; i < start + count; i++) if (hex(source.charAt(i)) < 0) return false;
        return true;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
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
                // interpolation braces are balanced inside it. The nested
                // level owns its escape violations: the
                // enclosing caller re-lexes this nested literal under the
                // normal expression rules and reports them there.
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
