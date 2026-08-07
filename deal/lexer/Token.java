package deal.lexer;

import deal.ast.TokenType;

/**
 * A token produced by the lexer.
 *
 * @param type   the token type
 * @param lexeme the source text of the token
 * @param line   1-based line number
 * @param column 1-based column number
 * @param length length of the lexeme in characters
 */
public record Token(
    TokenType type,
    String lexeme,
    int line,
    int column,
    int length
) {
    public Token {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (lexeme == null) throw new IllegalArgumentException("lexeme must not be null");
        if (line < 1) throw new IllegalArgumentException("line must be >= 1, got " + line);
        if (column < 1) throw new IllegalArgumentException("column must be >= 1, got " + column);
        if (length < 0) throw new IllegalArgumentException("length must be >= 0, got " + length);
    }

    @Override
    public String toString() {
        return String.format("%s('%s')@%d:%d",
            type.name(), lexeme, line, column);
    }
}
