package deal.source;

/**
 * Scalar traversal with UTF-16 source coordinates.
 *
 * <p>{@link #advance()} consumes one decoded code point. Supplementary
 * characters advance two columns and offsets; lone surrogates are single
 * recovery units. Lines and columns are 1-based, offsets 0-based. CRLF
 * consumes two offsets but one line break; LF and lone CR also reset the
 * column to 1. Tabs occupy one column.
 *
 * <p>Errors: none. The cursor never throws for any input: unpaired
 * surrogates count as recovery units, {@link #advance()} at end of input is
 * a no-op returning {@code -1}, {@link #peekScalar()} at end of input
 * returns {@code -1}, a {@code null} source is treated as empty, and
 * {@link #reset} with a {@code null} mark is a no-op.
 *
 * <p>The cursor is single-threaded per instance; {@link #mark() mark} /
 * {@link #reset} lookahead is deterministic.
 */
public final class ScalarSourceCursor {

    /**
     * A snapshot of cursor state produced by {@link #mark()} and restored by
     * {@link #reset(ScalarSourceCursor.Mark)}. A mark restores the line,
     * column, scalar offset, and the UTF-16 source index (the next
     * unconsumed unit) exactly.
     *
     */
    public record Mark(int line, int column, int scalarOffset, int index) {
    }

    private final String source;

    /** UTF-16 index of the next unconsumed source unit. */
    private int index;

    /** 1-based line number. */
    private int line = 1;

    /** 1-based UTF-16 column within the line. */
    private int column = 1;

    /** 0-based count of UTF-16 units consumed from source start. */
    private int scalarOffset;

    /**
     * Creates a cursor at the start of {@code source}. A {@code null} source
     * is treated as empty.
     */
    public ScalarSourceCursor(String source) {
        this.source = source == null ? "" : source;
    }

    public ScalarPosition position() {
        return new ScalarPosition(line, column, scalarOffset);
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public int scalarOffset() {
        return scalarOffset;
    }

    /**
     * Returns the UTF-16 index of the next unconsumed source unit. Consumers
     * that retain a UTF-16 index for substring extraction (e.g. the lexer)
     * use this accessor to keep their index in lockstep with the cursor's
     * coordinate arithmetic.
     */
    public int index() {
        return index;
    }

    public boolean atEnd() {
        return index >= source.length();
    }

    /**
     * Returns the next decoded Unicode scalar as a code point without
     * consuming it: a surrogate pair yields its supplementary code point, an
     * unpaired surrogate yields its own code unit value. Returns {@code -1}
     * at end of input. Never consumes and never throws.
     */
    public int peekScalar() {
        if (atEnd()) {
            return -1;
        }
        char c = source.charAt(index);
        if (Character.isHighSurrogate(c) && index + 1 < source.length()
                && Character.isLowSurrogate(source.charAt(index + 1))) {
            return Character.toCodePoint(c, source.charAt(index + 1));
        }
        return c;
    }

    /**
     * Consumes exactly one decoded code point and returns it as a code point:
     * a surrogate pair is one advance with two UTF-16 units, an unpaired
     * surrogate one recovery unit. CRLF increments the line only at CR;
     * LF and lone CR reset the column. Returns {@code -1} at end of input.
     */
    public int advance() {
        if (atEnd()) {
            return -1;
        }
        int scalar = peekScalar();
        if (scalar == '\n') {
            if (index > 0 && source.charAt(index - 1) == '\r') {
                // CRLF continuation: the line break was counted at the '\r';
                // the '\n' consumes no further line/column change but still
                // counts one scalar offset.
            } else {
                line++;
                column = 1;
            }
        } else if (scalar == '\r') {
            line++;
            column = 1;
        } else {
            column += Character.charCount(scalar);
        }
        index += Character.charCount(scalar);
        scalarOffset += Character.charCount(scalar);
        return scalar;
    }

    /**
     * Returns a snapshot of the current cursor state (line, column, scalar
     * offset, and the UTF-16 index of the next unconsumed unit) for
     * lookahead. The CRLF pairing rule is derived from the immediately
     * preceding consumed scalar, so a mark taken between a {@code \r} and its
     * following {@code \n} restores the pairing state exactly.
     */
    public Mark mark() {
        return new Mark(line, column, scalarOffset, index);
    }

    /**
     * Restores the exact state captured by a {@link #mark()} from this cursor
     * over the same source: position, line, column, and scalar offset.
     * A {@code null} mark is a no-op. Never throws.
     */
    public void reset(Mark mark) {
        if (mark == null) {
            return;
        }
        this.line = mark.line();
        this.column = mark.column();
        this.scalarOffset = mark.scalarOffset();
        this.index = mark.index();
    }

    /**
     * Counts UTF-16 source units. Null counts zero; the result agrees with
     * the offset reached by a cursor after consuming the whole string.
     */
    public static int scalarCount(String s) {
        if (s == null) {
            return 0;
        }
        return scalarCount(s, 0, s.length());
    }

    /**
     * Counts UTF-16 units in {@code s.substring(from, to)}.
     * Bounds outside {@code [0, s.length()]} are clamped; if
     * {@code from >= to} the result is zero. Never throws.
     */
    public static int scalarCount(String s, int from, int to) {
        if (s == null) {
            return 0;
        }
        int len = s.length();
        int f = Math.max(0, Math.min(from, len));
        int t = Math.max(0, Math.min(to, len));
        return Math.max(0, t - f);
    }
}
