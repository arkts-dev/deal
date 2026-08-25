package deal.source;

/**
 * A source position measured in decoded Unicode scalars (code points).
 *
 * <p>Lines and columns are 1-based; {@code scalarOffset} is the 0-based count
 * of decoded Unicode scalars from the start of the source. One code point is
 * one scalar: a surrogate pair counts as one column and one offset unit, and
 * an unpaired UTF-16 surrogate counts as one recovery unit (one column, one
 * offset unit). CRLF counts as two scalars; a tab counts as one scalar.
 *
 * @param line         1-based line number
 * @param column       1-based scalar column within the line
 * @param scalarOffset 0-based decoded Unicode scalar offset from source start
 */
public record ScalarPosition(int line, int column, int scalarOffset) {
    public ScalarPosition {
        if (line < 1) throw new IllegalArgumentException("line must be >= 1, got " + line);
        if (column < 1) throw new IllegalArgumentException("column must be >= 1, got " + column);
        if (scalarOffset < 0) {
            throw new IllegalArgumentException("scalarOffset must be >= 0, got " + scalarOffset);
        }
    }
}
