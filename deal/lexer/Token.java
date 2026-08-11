package deal.lexer;

import deal.ast.TokenType;

import java.util.List;

/**
 * A token produced by the lexer.
 *
 * @param type       the token type
 * @param lexeme     the source text of the token
 * @param line       1-based line number
 * @param column     1-based column number
 * @param length     length of the lexeme in characters
 * @param directives compiler directives (e.g. @jsonable) attached to this token
 */
public record Token(
    TokenType type,
    String lexeme,
    int line,
    int column,
    int length,
    List<String> directives
) {
    /**
     * Convenience constructor for backward compatibility.
     * Directives default to an empty list.
     */
    public Token(TokenType type, String lexeme, int line, int column, int length) {
        this(type, lexeme, line, column, length, List.of());
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
     * Returns a new Token with the given directives, copying all other fields.
     */
    public Token withDirectives(List<String> d) {
        return new Token(type, lexeme, line, column, length, List.copyOf(d));
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
