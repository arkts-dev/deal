package deal.source;

/**
 * A UTF-16 source position, with 1-based lines/columns and a 0-based
 * {@code scalarOffset}. Supplementary characters occupy two units, lone
 * surrogates one recovery unit. CRLF consumes two offsets and one line;
 * a tab occupies one column.
 *
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
