package deal.lexer;

import deal.ast.TokenType;

import java.util.List;

/**
 * A token produced by the lexer.
 *
 * <p>{@code startScalarOffset} and {@code scalarLength} are measured in
 * decoded Unicode scalars (code points). Both components use
 * {@link #UNKNOWN_OFFSET} as the explicit "no offset information" sentinel;
 * the convenience constructors set them to {@link #UNKNOWN_OFFSET}, never
 * {@code 0}. When both are non-negative, the token covers the half-open
 * scalar range {@code [startScalarOffset, startScalarOffset + scalarLength)}
 * in its file.</p>
 *
 * @param type              the token type
 * @param lexeme            the source text of the token
 * @param line              1-based line number
 * @param column            1-based column number
 * @param length            length of the lexeme in UTF-16 characters
 * @param startScalarOffset 0-based scalar offset of the lexeme's first
 *                          decoded scalar, or {@link #UNKNOWN_OFFSET} when
 *                          no offset information is available
 * @param scalarLength      length of the lexeme in decoded Unicode scalars,
 *                          or {@link #UNKNOWN_OFFSET} when no offset
 *                          information is available
 * @param directives        compiler directives (e.g. @jsonable) attached to
 *                          this token
 */
public record Token(
    TokenType type,
    String lexeme,
    int line,
    int column,
    int length,
    int startScalarOffset,
    int scalarLength,
    List<String> directives
) {
    /**
     * Explicit sentinel for "no offset information". Never a valid scalar
     * offset (valid scalar offsets are non-negative).
     */
    public static final int UNKNOWN_OFFSET = -1;

    /**
     * Convenience constructor for backward compatibility: no offset
     * information. Both scalar offset components are set to
     * {@link #UNKNOWN_OFFSET}, never {@code 0}. Directives default to an
     * empty list.
     */
    public Token(TokenType type, String lexeme, int line, int column, int length) {
        this(type, lexeme, line, column, length, UNKNOWN_OFFSET, UNKNOWN_OFFSET, List.of());
    }

    /**
     * Convenience constructor for backward compatibility: no offset
     * information. Both scalar offset components are set to
     * {@link #UNKNOWN_OFFSET}, never {@code 0}.
     */
    public Token(TokenType type, String lexeme, int line, int column, int length,
                 List<String> directives) {
        this(type, lexeme, line, column, length, UNKNOWN_OFFSET, UNKNOWN_OFFSET, directives);
    }

    public Token {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (lexeme == null) throw new IllegalArgumentException("lexeme must not be null");
        if (line < 1) throw new IllegalArgumentException("line must be >= 1, got " + line);
        if (column < 1) throw new IllegalArgumentException("column must be >= 1, got " + column);
        if (length < 0) throw new IllegalArgumentException("length must be >= 0, got " + length);
        if (directives == null) throw new IllegalArgumentException("directives must not be null");
    }

    /**
     * Returns true iff both scalar offset components are non-negative, i.e.
     * the token carries actual scalar offset information.
     */
    public boolean hasScalarOffsets() {
        return startScalarOffset >= 0 && scalarLength >= 0;
    }

    /**
     * Returns the half-open end scalar offset of the token
     * ({@code startScalarOffset + scalarLength}) when both components are
     * known, else {@link #UNKNOWN_OFFSET}.
     */
    public int endScalarOffset() {
        return hasScalarOffsets() ? startScalarOffset + scalarLength : UNKNOWN_OFFSET;
    }

    /**
     * Returns a new Token with the given directives. All other fields —
     * including {@code startScalarOffset} and {@code scalarLength} — are
     * copied verbatim from the receiver (known in, known out; UNKNOWN in,
     * UNKNOWN out). Never routes through the offset-less convenience
     * constructors.
     */
    public Token withDirectives(List<String> d) {
        return new Token(type, lexeme, line, column, length,
            startScalarOffset, scalarLength, List.copyOf(d));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s('%s')@%d:%d",
            type.name(), lexeme, line, column));
        if (!directives.isEmpty()) {
            sb.append(" [");
            sb.append(String.join(", ", directives));
            sb.append("]");
        }
        return sb.toString();
    }
}
