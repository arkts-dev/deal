package deal.source;

/**
 * The single owner of decoded-Unicode-scalar position arithmetic.
 *
 * <p>All positions are measured in decoded Unicode scalars (code points):
 * <ul>
 *   <li>one code point = one scalar; {@link #advance()} consumes exactly one
 *       decoded code point per call — a surrogate pair is one advance (one
 *       column, one scalar offset), and an unpaired UTF-16 surrogate is one
 *       recovery unit (one column, one scalar offset);</li>
 *   <li>line terminators are LF, CRLF, and CR; CRLF is two scalars and one
 *       line break: the line increments once at the CR, and an {@code \n}
 *       immediately following a consumed {@code \r} increments no line and
 *       resets no column while still counting one scalar offset; LF and a
 *       lone CR each increment the line and reset the column to 1;</li>
 *   <li>a tab and every other scalar increment the column by 1;</li>
 *   <li>lines and columns are 1-based; scalar offsets are 0-based counts of
 *       decoded scalars from the start of the source.</li>
 * </ul>
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
     * @param line         1-based line number
     * @param column       1-based scalar column within the line
     * @param scalarOffset 0-based decoded Unicode scalar offset from source start
     * @param index        UTF-16 index of the next unconsumed source unit
     */
    public record Mark(int line, int column, int scalarOffset, int index) {
    }

    private final String source;

    /** UTF-16 index of the next unconsumed source unit. */
    private int index;

    /** 1-based line number. */
    private int line = 1;

    /** 1-based scalar column within the line. */
    private int column = 1;

    /** 0-based count of decoded Unicode scalars consumed from source start. */
    private int scalarOffset;

    /**
     * Creates a cursor at the start of {@code source}. A {@code null} source
     * is treated as empty.
     */
    public ScalarSourceCursor(String source) {
        this.source = source == null ? "" : source;
    }

    /**
     * Returns the current position as a {@link ScalarPosition}.
     */
    public ScalarPosition position() {
        return new ScalarPosition(line, column, scalarOffset);
    }

    /** Returns the current 1-based line number. */
    public int line() {
        return line;
    }

    /** Returns the current 1-based scalar column within the line. */
    public int column() {
        return column;
    }

    /** Returns the 0-based count of decoded Unicode scalars consumed so far. */
    public int scalarOffset() {
        return scalarOffset;
    }

    /**
     * Returns the UTF-16 index of the next unconsumed source unit. Consumers
     * that retain a UTF-16 index for substring extraction (e.g. the lexer)
     * use this accessor to keep their index in lockstep with the cursor's
     * scalar arithmetic.
     */
    public int index() {
        return index;
    }

    /** Returns true exactly at end of input (no unconsumed source unit remains). */
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
     * a surrogate pair is one advance (one column, one scalar offset), an
     * unpaired UTF-16 surrogate is one recovery unit. An {@code \n}
     * immediately following a consumed {@code \r} increments no line and
     * resets no column while still counting one scalar offset (CRLF = two
     * scalars and one line break); LF and a lone CR each increment the line
     * and reset the column to 1; a tab and every other scalar increment the
     * column by 1. Returns {@code -1} and does nothing at end of input.
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
            column++;
        }
        index += Character.charCount(scalar);
        scalarOffset++;
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
     * Counts the decoded Unicode scalars in {@code s}: each surrogate pair
     * counts as one scalar, each unpaired surrogate counts as one recovery
     * unit. Never throws; a {@code null} string counts zero scalars. The
     * result agrees with the scalar offset reached by a cursor after
     * consuming the whole string.
     */
    public static int scalarCount(String s) {
        if (s == null) {
            return 0;
        }
        return scalarCount(s, 0, s.length());
    }

    /**
     * Counts the decoded Unicode scalars in the UTF-16 substring
     * {@code s.substring(from, to)}: each surrogate pair within the range
     * counts as one scalar, each unpaired surrogate counts as one recovery
     * unit. Bounds outside {@code [0, s.length()]} are clamped; if
     * {@code from >= to} the result is zero. Never throws.
     */
    public static int scalarCount(String s, int from, int to) {
        if (s == null) {
            return 0;
        }
        int len = s.length();
        int f = Math.max(0, Math.min(from, len));
        int t = Math.max(0, Math.min(to, len));
        int count = 0;
        for (int i = f; i < t; ) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < t
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                i += 2;
            } else {
                i += 1;
            }
            count++;
        }
        return count;
    }
}
