package deal.lexer;

import deal.ast.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tokenizer that converts DEAL source text into a list of {@link Token}s.
 *
 * <p>Handles all DEAL lexical elements: keywords, identifiers, literals
 * (null, boolean, integer, number, string), operators, punctuation,
 * comments, and whitespace. Emits E1xxx diagnostics for lexical errors
 * and recovers to continue tokenizing.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Lexer lexer = new Lexer(source, "file.deal");
 * LexResult result = lexer.tokenize();
 * for (Token t : result.tokens()) { ... }
 * for (Diagnostic d : result.diagnostics()) { ... }
 * }</pre>
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("let",      TokenType.LET),
        Map.entry("class",    TokenType.CLASS),
        Map.entry("function", TokenType.FUNCTION),
        Map.entry("return",   TokenType.RETURN),
        Map.entry("if",       TokenType.IF),
        Map.entry("else",     TokenType.ELSE),
        Map.entry("while",    TokenType.WHILE),
        Map.entry("for",      TokenType.FOR),
        Map.entry("break",    TokenType.BREAK),
        Map.entry("continue", TokenType.CONTINUE),
        Map.entry("null",     TokenType.NULL),
        Map.entry("true",     TokenType.TRUE),
        Map.entry("false",    TokenType.FALSE),
        Map.entry("import",   TokenType.IMPORT),
        Map.entry("export",   TokenType.EXPORT),
        Map.entry("from",     TokenType.FROM),
        Map.entry("delete",   TokenType.DELETE),
        Map.entry("has",      TokenType.HAS),
        Map.entry("try",      TokenType.TRY),
        Map.entry("catch",    TokenType.CATCH),
        Map.entry("throw",    TokenType.THROW),
        Map.entry("as",       TokenType.AS),
        Map.entry("in",       TokenType.IN)
    );

    private final String source;
    private final String file;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private int pos;      // current index in source (0-based)
    private int line;     // current 1-based line
    private int column;   // current 1-based column

    // Token start position (set before reading each token)
    private int tokenStartLine;
    private int tokenStartCol;

    /**
     * Creates a new lexer for the given source text.
     *
     * @param source the DEAL source text
     * @param file   the file path (for diagnostics)
     */
    public Lexer(String source, String file) {
        this.source = source;
        this.file = file;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Tokenizes the entire source and returns the result.
     *
     * @return a {@link LexResult} containing all tokens and any diagnostics
     */
    public LexResult tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (pos < source.length()) {
            Token token = nextToken();
            if (token != null) {
                tokens.add(token);
            }
        }

        // Emit EOF token at the current position
        tokens.add(new Token(TokenType.EOF, "", line, column, 0));

        return new LexResult(List.copyOf(tokens), List.copyOf(diagnostics));
    }

    // =========================================================================
    // Token dispatch
    // =========================================================================

    /**
     * Reads the next token from the source, or null if at end of input.
     */
    private Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= source.length()) {
            return null;
        }

        tokenStartLine = line;
        tokenStartCol = column;

        char c = source.charAt(pos);

        // Number: starts with digit, or '.' followed by digit
        if (isDigit(c) || (c == '.' && pos + 1 < source.length() && isDigit(source.charAt(pos + 1)))) {
            return readNumber();
        }

        // String literal
        if (c == '"' || c == '\'') {
            return readString();
        }

        // Identifier or keyword
        if (isIdentifierStart(c)) {
            return readIdentifierOrKeyword();
        }

        // Operators and punctuation
        return readOperatorOrPunctuation();
    }

    // =========================================================================
    // Whitespace and comments
    // =========================================================================

    /**
     * Skips whitespace and comments, advancing position, line, and column.
     */
    private void skipWhitespaceAndComments() {
        while (pos < source.length()) {
            char c = source.charAt(pos);

            // Whitespace (non-newline)
            if (c == ' ' || c == '\t') {
                bump();
                continue;
            }

            // Line terminators
            if (c == '\n') {
                newline();
                continue;
            }
            if (c == '\r') {
                newline();
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    pos++; // consume LF (no column update, no new line)
                }
                continue;
            }

            // Line comment //
            if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
                skipLineComment();
                continue;
            }

            // Block comment /* */
            if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
                skipBlockComment();
                continue;
            }

            break;
        }
    }

    /**
     * Skips a single-line comment: // ... until end of line.
     */
    private void skipLineComment() {
        pos += 2; // skip // (no column update — comment chars don't affect token positions)
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n') {
                newline();
                return;
            }
            if (c == '\r') {
                newline();
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    pos++;
                }
                return;
            }
            pos++;
        }
    }

    /**
     * Skips a block comment: /* ... *​/.
     * The spec says they do NOT nest — the first *​/ ends the comment.
     * Unterminated block comments produce E1004.
     */
    private void skipBlockComment() {
        int startLine = line;
        int startCol = column;
        pos += 2; // skip /*
        int depth = 1; // support nesting? spec says no, but be robust

        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '*' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') {
                pos += 2;
                depth--;
                if (depth == 0) return;
                continue;
            }
            if (c == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
                pos += 2;
                depth++;
                continue;
            }
            if (c == '\n') {
                newline();
            } else if (c == '\r') {
                newline();
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '\n') {
                    pos++;
                }
            } else {
                pos++;
            }
        }
        // Unterminated block comment
        error("E1004", "Unterminated multi-line comment", startLine, startCol);
    }

    // =========================================================================
    // Number literals
    // =========================================================================

    /**
     * Reads an integer or number literal.
     *
     * <p>Integer: {@code [0-9]+}
     * <br>Number:  {@code ([0-9]* '.' [0-9]+ ([eE] [+-]? [0-9]+)?)
     *                    | ([0-9]+ [eE] [+-]? [0-9]+)}</p>
     */
    private Token readNumber() {
        int startPos = pos;
        boolean hasDot = false;
        boolean hasExponent = false;

        // Integer part (optional if starts with dot)
        while (pos < source.length() && isDigit(source.charAt(pos))) {
            bump();
        }

        // Fractional part: '.' followed by digits
        if (pos < source.length() && source.charAt(pos) == '.') {
            if (pos + 1 < source.length() && isDigit(source.charAt(pos + 1))) {
                hasDot = true;
                bump(); // '.'
                while (pos < source.length() && isDigit(source.charAt(pos))) {
                    bump();
                }
            }
        }

        // Exponent part
        if (pos < source.length()
                && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {

            int lookahead = pos + 1;
            if (lookahead < source.length()) {
                char nc = source.charAt(lookahead);
                if (nc == '+' || nc == '-') {
                    lookahead++;
                }
                if (lookahead < source.length() && isDigit(source.charAt(lookahead))) {
                    hasExponent = true;
                    bump(); // 'e' or 'E'
                    if (pos < source.length()
                            && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                        bump(); // sign
                    }
                    while (pos < source.length() && isDigit(source.charAt(pos))) {
                        bump();
                    }
                } else if (hasDot) {
                    hasExponent = true;
                    bump(); // 'e' or 'E'
                    if (pos < source.length()
                            && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                        bump(); // sign
                    }
                    error("E1002",
                        "Malformed number literal: exponent without digits",
                        tokenStartLine, tokenStartCol);
                }
            } else if (hasDot) {
                hasExponent = true;
                bump();
                error("E1002",
                    "Malformed number literal: exponent without digits",
                    tokenStartLine, tokenStartCol);
            }
        }

        String lexeme = source.substring(startPos, pos);
        boolean isFloat = hasDot || hasExponent;

        return makeToken(isFloat ? TokenType.NUMBER_LITERAL : TokenType.INT_LITERAL, lexeme);
    }

    // =========================================================================
    // String literals
    // =========================================================================

    /**
     * Reads a double-quoted or single-quoted string literal.
     * The lexeme is the raw source text including quotes and escape sequences.
     * Unterminated strings produce E1003.
     */
    private Token readString() {
        int startPos = pos;
        char quote = source.charAt(pos);
        bump(); // opening quote

        while (pos < source.length()) {
            char c = source.charAt(pos);

            if (c == '\n') {
                error("E1003", "Unterminated string literal: missing closing " + quote,
                    tokenStartLine, tokenStartCol);
                String lexeme = source.substring(startPos, pos);
                newline();
                return makeToken(TokenType.STRING_LITERAL, lexeme);
            }
            if (c == '\r') {
                error("E1003", "Unterminated string literal: missing closing " + quote,
                    tokenStartLine, tokenStartCol);
                String lexeme = source.substring(startPos, pos);
                newline();
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    pos++;
                }
                return makeToken(TokenType.STRING_LITERAL, lexeme);
            }

            if (c == '\\') {
                bump(); // backslash
                if (pos >= source.length()) {
                    error("E1003", "Unterminated string literal: escape at end of file",
                        tokenStartLine, tokenStartCol);
                    String lexeme = source.substring(startPos, pos);
                    return makeToken(TokenType.STRING_LITERAL, lexeme);
                }
                bump(); // the escaped character
            } else if (c == quote) {
                bump(); // closing quote
                String lexeme = source.substring(startPos, pos);
                return makeToken(TokenType.STRING_LITERAL, lexeme);
            } else {
                bump();
            }
        }

        // Reached EOF without closing quote
        error("E1003", "Unterminated string literal: missing closing " + quote,
            tokenStartLine, tokenStartCol);
        String lexeme = source.substring(startPos, pos);
        return makeToken(TokenType.STRING_LITERAL, lexeme);
    }

    // =========================================================================
    // Identifiers and keywords
    // =========================================================================

    /**
     * Reads an identifier or keyword.
     * Identifier: {@code [a-zA-Z_$][a-zA-Z0-9_$]*}
     */
    private Token readIdentifierOrKeyword() {
        int startPos = pos;
        bump(); // first char
        while (pos < source.length() && isIdentifierPart(source.charAt(pos))) {
            bump();
        }
        String lexeme = source.substring(startPos, pos);
        TokenType type = KEYWORDS.getOrDefault(lexeme, TokenType.IDENTIFIER);
        return makeToken(type, lexeme);
    }

    // =========================================================================
    // Operators and punctuation
    // =========================================================================

    /**
     * Reads an operator or punctuation token.
     * Uses manual lookahead to handle multi-character operators.
     * All consumed chars are non-newline, so bump() is safe.
     */
    private Token readOperatorOrPunctuation() {
        char c = source.charAt(pos);

        return switch (c) {
            case '=' -> {
                if (pos + 1 < source.length()) {
                    char n = source.charAt(pos + 1);
                    if (n == '>') {
                        bump(); bump();
                        yield makeToken(TokenType.ARROW, "=>");
                    }
                    if (n == '=') {
                        if (pos + 2 < source.length() && source.charAt(pos + 2) == '=') {
                            bump(); bump(); bump();
                            yield makeToken(TokenType.EQ_STRICT, "===");
                        }
                        bump(); bump();
                        yield makeToken(TokenType.EQ, "==");
                    }
                }
                bump();
                yield makeToken(TokenType.EQ_SIGN, "=");
            }
            case '!' -> {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '=') {
                    if (pos + 2 < source.length() && source.charAt(pos + 2) == '=') {
                        bump(); bump(); bump();
                        yield makeToken(TokenType.NEQ_STRICT, "!==");
                    }
                    bump(); bump();
                    yield makeToken(TokenType.NEQ, "!=");
                }
                bump();
                yield makeToken(TokenType.BANG, "!");
            }
            case '<' -> {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '=') {
                    bump(); bump();
                    yield makeToken(TokenType.LTE, "<=");
                }
                bump();
                yield makeToken(TokenType.LT, "<");
            }
            case '>' -> {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '=') {
                    bump(); bump();
                    yield makeToken(TokenType.GTE, ">=");
                }
                bump();
                yield makeToken(TokenType.GT, ">");
            }
            case '&' -> {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '&') {
                    bump(); bump();
                    yield makeToken(TokenType.AND, "&&");
                }
                bump();
                yield makeToken(TokenType.AMPERSAND, "&");
            }
            case '|' -> {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '|') {
                    bump(); bump();
                    yield makeToken(TokenType.OR, "||");
                }
                bump();
                yield makeToken(TokenType.PIPE, "|");
            }
            case '*' -> {
                if (pos + 1 < source.length() && source.charAt(pos + 1) == '*') {
                    bump(); bump();
                    yield makeToken(TokenType.STAR_STAR, "**");
                }
                bump();
                yield makeToken(TokenType.STAR, "*");
            }
            case '.' -> {
                if (pos + 2 < source.length()
                        && source.charAt(pos + 1) == '.'
                        && source.charAt(pos + 2) == '.') {
                    bump(); bump(); bump();
                    yield makeToken(TokenType.ELLIPSIS, "...");
                }
                bump();
                yield makeToken(TokenType.DOT, ".");
            }
            case '+' -> { bump(); yield makeToken(TokenType.PLUS, "+"); }
            case '-' -> { bump(); yield makeToken(TokenType.MINUS, "-"); }
            case '/' -> { bump(); yield makeToken(TokenType.SLASH, "/"); }
            case '%' -> { bump(); yield makeToken(TokenType.PERCENT, "%"); }
            case '?' -> { bump(); yield makeToken(TokenType.QUESTION, "?"); }
            case ':' -> { bump(); yield makeToken(TokenType.COLON, ":"); }
            case ';' -> { bump(); yield makeToken(TokenType.SEMICOLON, ";"); }
            case ',' -> { bump(); yield makeToken(TokenType.COMMA, ","); }
            case '{' -> { bump(); yield makeToken(TokenType.LBRACE, "{"); }
            case '}' -> { bump(); yield makeToken(TokenType.RBRACE, "}"); }
            case '(' -> { bump(); yield makeToken(TokenType.LPAREN, "("); }
            case ')' -> { bump(); yield makeToken(TokenType.RPAREN, ")"); }
            case '[' -> { bump(); yield makeToken(TokenType.LBRACKET, "["); }
            case ']' -> { bump(); yield makeToken(TokenType.RBRACKET, "]"); }

            default -> {
                String ch = String.valueOf(c);
                error("E1001", "Unrecognized character: '" + ch + "'",
                    line, column);
                bump();
                yield null;
            }
        };
    }

    // =========================================================================
    // Character classification helpers
    // =========================================================================

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || c == '_'
            || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || isDigit(c);
    }

    // =========================================================================
    // Position tracking helpers
    // =========================================================================

    /**
     * Advances past the current non-newline character, incrementing column.
     */
    private void bump() {
        pos++;
        column++;
    }

    /**
     * Handles a newline (\\n or \\r). Advances past the character,
     * increments line, and resets column to 1.
     */
    private void newline() {
        pos++;
        line++;
        column = 1;
    }

    /**
     * Creates a token at the current token start position
     * with the given type and lexeme.
     */
    private Token makeToken(TokenType type, String lexeme) {
        return new Token(type, lexeme, tokenStartLine, tokenStartCol, lexeme.length());
    }

    // =========================================================================
    // Error reporting
    // =========================================================================

    private void error(String code, String message, int errLine, int errCol) {
        diagnostics.add(Diagnostic.error(code, message, file, errLine, errCol));
    }
}
