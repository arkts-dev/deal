package deal.lexer;

import deal.ast.TokenType;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.source.ScalarSourceCursor;

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
 * <p>Comments are whitespace: their contents never produce compiler
 * metadata or diagnostics. Unterminated block comments remain lexical errors.</p>
 *
 * <p>Position tracking is owned by a {@link ScalarSourceCursor}: every
 * position and scalar offset is measured in decoded Unicode scalars (a
 * supplementary character counts one column; CRLF counts two scalars and
 * one line break; a tab counts one). The lexer retains a UTF-16
 * {@code pos} index, kept in lockstep with the cursor, for lexeme
 * substring extraction. Every emitted token carries its computed scalar
 * offsets; the EOF token carries the final cursor position and zero
 * scalar length.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Lexer lexer = new Lexer(source, "file.deal");
 * LexResult result = lexer.tokenize();
 * for (Token t : result.tokens()) { ... }
 * for (CompilerDiagnostic d : result.diagnostics()) { ... }
 * }</pre>
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("let",      TokenType.LET),
        Map.entry("const",    TokenType.CONST),
        Map.entry("class",    TokenType.CLASS),
        Map.entry("function", TokenType.FUNCTION),
        Map.entry("async",    TokenType.ASYNC),
        Map.entry("await",    TokenType.AWAIT),
        Map.entry("return",   TokenType.RETURN),
        Map.entry("if",       TokenType.IF),
        Map.entry("else",     TokenType.ELSE),
        Map.entry("while",    TokenType.WHILE),
        Map.entry("for",      TokenType.FOR),
        Map.entry("of",       TokenType.OF),
        Map.entry("break",    TokenType.BREAK),
        Map.entry("continue", TokenType.CONTINUE),
        Map.entry("null",     TokenType.NULL),
        Map.entry("true",     TokenType.TRUE),
        Map.entry("false",    TokenType.FALSE),
        Map.entry("import",   TokenType.IMPORT),
        Map.entry("export",   TokenType.EXPORT),
        Map.entry("from",     TokenType.FROM),
        Map.entry("delete",   TokenType.DELETE),
        Map.entry("try",      TokenType.TRY),
        Map.entry("catch",    TokenType.CATCH),
        Map.entry("throw",    TokenType.THROW),
        Map.entry("as",       TokenType.AS),
        Map.entry("typeof",   TokenType.TYPEOF),
        Map.entry("instanceof", TokenType.INSTANCEOF)
    );

    private final String source;
    private final String file;

    /**
     * The single owner of decoded-scalar position arithmetic (D2/D3):
     * line/column/offset tracking. The UTF-16 {@link #pos} below is kept
     * in lockstep with the cursor and serves lexeme substring extraction
     * only.
     */
    private final ScalarSourceCursor cursor;

    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();

    private int pos;      // current UTF-16 index in source (0-based), in lockstep with cursor

    // Token start position (set before reading each token)
    private int tokenStartLine;
    private int tokenStartCol;
    private int tokenStartScalarOffset;

    /**
     * Creates a new lexer for the given source text.
     *
     */
    public Lexer(String source, String file) {
        this.source = source;
        this.file = file;
        this.cursor = new ScalarSourceCursor(source);
        this.pos = 0;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Tokenizes the entire source and returns the result.
     *
     */
    public LexResult tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (pos < source.length()) {
            Token token = nextToken();
            if (token != null) {
                tokens.add(token);
            }
        }

        // EOF retains the final cursor position and zero scalar length,
        // including after a trailing comment.
        Token eof = new Token(TokenType.EOF, "", cursor.line(), cursor.column(), 0,
            cursor.scalarOffset(), 0);
        tokens.add(eof);

        return new LexResult(List.copyOf(tokens), List.copyOf(diagnostics));
    }

    // =========================================================================
    // Token dispatch
    // =========================================================================

    /** Reads the next token from the source, or null if at end of input. */
    private Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= source.length()) {
            return null;
        }

        tokenStartLine = cursor.line();
        tokenStartCol = cursor.column();
        tokenStartScalarOffset = cursor.scalarOffset();

        char c = source.charAt(pos);

        Token token;

        // Number: starts with digit, or '.' followed by digit
        if (isDigit(c) || (c == '.' && pos + 1 < source.length() && isDigit(source.charAt(pos + 1)))) {
            token = readNumber();
        } else if (c == '"' || c == '\'') {
            token = readString();
        } else if (isIdentifierStart(c)) {
            token = readIdentifierOrKeyword();
        } else {
            token = readOperatorOrPunctuation();
        }

        return token;
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
                    skipCrlfLf(); // consume LF (one scalar, no line change)
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

    /** Skips a line comment and its terminator, preserving scalar positions. */
    private void skipLineComment() {
        bump(); // skip first /
        bump(); // skip second /
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\n') {
                newline();
                return;
            }
            if (c == '\r') {
                newline();
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    skipCrlfLf();
                }
                return;
            }
            bump();
        }
    }

    /**
     * The half-open SOURCE range from the position recorded in
     * {@code start} through the position recorded in {@code end}.
     */
    private DiagnosticRange rangeBetween(ScalarSourceCursor.Mark start,
                                         ScalarSourceCursor.Mark end) {
        int startOffset = start.scalarOffset();
        int endOffset = end.scalarOffset();
        return new DiagnosticRange(file, start.line(), start.column(),
            end.line(), end.column(), startOffset, endOffset,
            endOffset - startOffset, RangeOrigin.SOURCE);
    }

    /**
     * Skips a block comment: /* ... *​/.
     *
     * <p>Per spec.md line 56, block comments do NOT nest.
     * {@code /* /* *​/} ends at the first {@code *​/}.</p>
     *
     * <p>All non-newline characters are consumed via {@link #bump()} so
     * that subsequent tokens on the same line report correct column
     * positions.  Unterminated block comments produce E1004 anchored at
     * the complete comment range (comment start through EOF, D5).
     * </p>
     */
    private void skipBlockComment() {
        int startLine = cursor.line();
        int startCol = cursor.column();
        int startOffset = cursor.scalarOffset();
        bump(); // skip first /
        bump(); // skip *

        while (pos < source.length()) {
            char c = source.charAt(pos);

            // Check for closing */
            if (c == '*') {
                bump(); // skip *
                if (pos < source.length() && source.charAt(pos) == '/') {
                    bump(); // skip /
                    return;
                }
                // Just a lone * inside the comment; continue scanning.
                continue;
            }

            if (c == '\n') {
                newline();
            } else if (c == '\r') {
                newline();
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    skipCrlfLf(); // consume LF (part of CRLF, column already reset)
                }
            } else {
                bump();
            }
        }

        // Unterminated block comment: comment start through EOF (D5).
        error(DiagnosticCode.E1004, "Unterminated multi-line comment",
            rangeFromStart(startLine, startCol, startOffset));
    }

    // =========================================================================
    // Number literals
    // =========================================================================

    /**
     * Reads a number literal: decimal digits with an optional fractional
     * or exponent part, or a leading decimal point followed by digits.
     *
     * <p>Multiple decimal points produce E1002.</p>
     */
    private Token readNumber() {
        int startPos = pos;
        boolean hasDot = false;
        boolean hasExponent = false;

        // Integer part (optional if starts with dot)
        while (pos < source.length() && isDigit(source.charAt(pos))) {
            bump();
        }

        if (pos - startPos > 1 && source.charAt(startPos) == '0') {
            error(DiagnosticCode.E1002,
                "Leading-zero decimal literals are not supported",
                tokenStartRange());
        }

        // Fractional part: '.' followed by digits
        if (pos < source.length() && source.charAt(pos) == '.') {
            if (pos + 1 < source.length() && isDigit(source.charAt(pos + 1))) {
                // Guard: if we already saw a dot, this is a malformed number.
                // (This fires for patterns like .5.3 where leading dot was consumed
                //  as integer part, but also catches internal double-dot in theory.)
                if (hasDot) {
                    error(DiagnosticCode.E1002,
                        "Malformed number literal: multiple decimal points",
                        tokenStartRange());
                    String lexeme = source.substring(startPos, pos);
                    return makeToken(TokenType.NUMBER_LITERAL, lexeme);
                }
                hasDot = true;
                bump(); // '.'
                while (pos < source.length() && isDigit(source.charAt(pos))) {
                    bump();
                }
            }
        }

        // After consuming the fractional part, check whether the next
        // character is another dot followed by digits (e.g. "1.2.3").
        if (hasDot && pos < source.length() && source.charAt(pos) == '.'
                && pos + 1 < source.length() && isDigit(source.charAt(pos + 1))) {
            error(DiagnosticCode.E1002,
                "Malformed number literal: multiple decimal points",
                tokenStartRange());
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
                    error(DiagnosticCode.E1002,
                        "Malformed number literal: exponent without digits",
                        tokenStartRange());
                }
            } else if (hasDot) {
                hasExponent = true;
                bump();
                error(DiagnosticCode.E1002,
                    "Malformed number literal: exponent without digits",
                    tokenStartRange());
            }
        }

        String lexeme = source.substring(startPos, pos);
        return makeToken(TokenType.NUMBER_LITERAL, lexeme);
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
                error(DiagnosticCode.E1003, "Unterminated string literal: missing closing " + quote,
                    tokenStartRange());
                String lexeme = source.substring(startPos, pos);
                newline();
                return makeToken(TokenType.STRING_LITERAL, lexeme);
            }
            if (c == '\r') {
                error(DiagnosticCode.E1003, "Unterminated string literal: missing closing " + quote,
                    tokenStartRange());
                String lexeme = source.substring(startPos, pos);
                newline();
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    skipCrlfLf();
                }
                return makeToken(TokenType.STRING_LITERAL, lexeme);
            }

            if (c == '\\') {
                bump(); // backslash
                if (pos >= source.length()) {
                    error(DiagnosticCode.E1003, "Unterminated string literal: escape at end of file",
                        tokenStartRange());
                    String lexeme = source.substring(startPos, pos);
                    return makeToken(TokenType.STRING_LITERAL, lexeme);
                }
                char escaped = source.charAt(pos);
                if (!TemplateScanner.validStringEscape(source, pos)) {
                    error(DiagnosticCode.E1052,
                        "Unsupported string escape: \\" + escaped,
                        tokenStartRange());
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
        error(DiagnosticCode.E1003, "Unterminated string literal: missing closing " + quote,
            tokenStartRange());
        String lexeme = source.substring(startPos, pos);
        return makeToken(TokenType.STRING_LITERAL, lexeme);
    }

    // =========================================================================
    // Template literals
    // =========================================================================

    /**
     * Reads one backtick-delimited template token, retaining physical newlines.
     *
     * <p>The lexeme is the raw content between the backticks (backticks not
     * included). {@link TemplateScanner} owns the structure: TemplateText
     * runs end at a line terminator, a backtick, or the {@code ${} opener;
     * a backslash is ordinary TemplateText (no escape processing); inside
     * an interpolation the normal expression rules apply, so whitespace
     * line terminators, comments, quoted strings, balanced braces and
     * nested template literals are all part of the one template token.</p>
     *
     * <p>{@link TemplateScanner} also owns the TemplateText
     * trailing-backslash rule ({@code docs/spec-v1.6.md}: "trailing
     * backslashes form pairs"): a text run that would end with an odd
     * backslash run at the closing backtick or at a {@code ${} opener is
     * not TemplateText. Each such run is rejected here with E1042 over the
     * SOURCE range of the run together with its delimiter. The rejection
     * adds no escape semantics: the token boundary is unchanged, the
     * lexeme keeps the raw scalars, and recovery continues after the
     * delimiter the template closed at. A nested template literal inside
     * an interpolation is scanned by its own expression-level lexical
     * pass, which reports its own violations.</p>
     *
     * <p>EOF inside an unterminated template or interpolation produces E1003
     * at the token start and a recovery token retaining all scanned raw text.
     * Physical CRLF counts as one line break while retaining both units.</p>
     */
    private Token readTemplateLiteral() {
        bump(); // opening backtick
        int contentStart = pos;

        TemplateScanner.Scan scan = TemplateScanner.scan(source, contentStart);
        reportTemplateTrailingBackslashes(scan);

        switch (scan.status()) {
            case TERMINATED -> {
                int endIndex = scan.endIndex();
                String lexeme = source.substring(contentStart, endIndex - 1);
                advanceTo(endIndex);
                return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
            }
            case EOF, UNTERMINATED_INTERPOLATION -> {
                int stop = scan.endIndex();
                error(DiagnosticCode.E1003,
                    "Unterminated template literal: missing closing backtick",
                    tokenStartRange());
                String lexeme = source.substring(contentStart, stop);
                advanceTo(stop);
                return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
            }
            default -> throw new IllegalStateException(
                "Unhandled template scan status: " + scan.status());
        }
    }

    /**
     * Rejects every TemplateText trailing-backslash violation of one scanned
     * template token with E1042 over the run-plus-delimiter SOURCE range.
     *
     * <p>The walk is non-consuming: the cursor mark taken on entry is
     * restored, so the caller's recovery ranges and token spans stay exactly
     * as the scan left them. Violation ranges are recorded in source order,
     * and each range lies before the scan's stop index.</p>
     */
    private void reportTemplateTrailingBackslashes(TemplateScanner.Scan scan) {
        if (scan.violations().isEmpty()) {
            return;
        }
        ScalarSourceCursor.Mark entry = cursor.mark();
        for (TemplateScanner.Violation violation : scan.violations()) {
            ScalarSourceCursor.Mark start = markAt(violation.start());
            ScalarSourceCursor.Mark end = markAt(violation.end());
            error(DiagnosticCode.E1042,
                templateTrailingBackslashMessage(violation.delimiter()),
                rangeBetween(start, end));
        }
        resetCursor(entry);
    }

    /**
     * The E1042 text for one broken trailing-backslash rule: the delimiter
     * names the exact position where the odd run prevents TemplateText.
     */
    private static String templateTrailingBackslashMessage(
            TemplateScanner.Delimiter delimiter) {
        return switch (delimiter) {
            case ESCAPE_PREFIX -> "Invalid template literal: malformed numeric, hexadecimal or Unicode escape spelling";
        };
    }

    /**
     * Advances the cursor to the given UTF-16 index, one decoded scalar at
     * a time, keeping {@code pos} in lockstep with the cursor, and returns
     * the resulting mark.
     */
    private ScalarSourceCursor.Mark markAt(int utf16Index) {
        advanceTo(utf16Index);
        return cursor.mark();
    }

    /**
     * Advances the cursor to the given UTF-16 index, one decoded scalar at
     * a time, keeping {@code pos} in lockstep with the cursor.
     */
    private void advanceTo(int utf16Index) {
        while (pos < utf16Index && pos < source.length()) {
            bump();
        }
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
            case '*' -> { bump(); yield makeToken(TokenType.STAR, "*"); }
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
            case '-' -> {
                bump();
                if (pos < source.length() && source.charAt(pos) == '-') {
                    error(DiagnosticCode.E1042, "Adjacent '-' tokens require whitespace or a comment",
                        tokenStartRange());
                }
                yield makeToken(TokenType.MINUS, "-");
            }
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
            case '`' -> { yield readTemplateLiteral(); }

            default -> {
                String ch = String.valueOf(c);
                // E1001 = the offending single scalar (D5): the range covers
                // exactly the scalar at the current cursor position, before
                // it is consumed.
                error(DiagnosticCode.E1001, "Unrecognized character: '" + ch + "'",
                    singleScalarRange(cursor.line(), cursor.column(), cursor.scalarOffset()));
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
     * Advances past the current decoded scalar (never a line terminator at
     * the call sites), incrementing the column by one via the cursor and
     * keeping the UTF-16 {@code pos} in lockstep with the cursor index.
     */
    private void bump() {
        cursor.advance();
        pos = cursor.index();
    }

    /**
     * Restores a cursor mark while keeping {@code pos} in lockstep.
     */
    private void resetCursor(ScalarSourceCursor.Mark mark) {
        cursor.reset(mark);
        pos = cursor.index();
    }

    /**
     * Handles a newline (\\n or \\r) through the cursor: one scalar offset;
     * the line increments and the column resets to 1 (the cursor's CRLF
     * rule suppresses the line change for an LF following a consumed CR).
     * Keeps {@code pos} in lockstep.
     */
    private void newline() {
        cursor.advance();
        pos = cursor.index();
    }

    /**
     * Consumes the LF scalar of a CRLF pair after the CR was already
     * consumed: one scalar offset with no line/column change (the cursor's
     * CRLF pairing rule), keeping {@code pos} in lockstep. CRLF counts two
     * scalars and one line break.
     */
    private void skipCrlfLf() {
        cursor.advance();
        pos = cursor.index();
    }

    /**
     * Creates a token at the current token start position with the given
     * type and lexeme. Records the token-start scalar offset captured in
     * {@link #nextToken()} and computes {@code scalarLength} as the
     * cursor distance consumed since (D3).
     */
    private Token makeToken(TokenType type, String lexeme) {
        int scalarLen = cursor.scalarOffset() - tokenStartScalarOffset;
        return new Token(type, lexeme, tokenStartLine, tokenStartCol,
            lexeme.length(), tokenStartScalarOffset, scalarLen);
    }

    // =========================================================================
    // Error reporting
    // =========================================================================

    /**
     * Emits a ranged {@link CompilerDiagnostic} carrying the given complete
     * SOURCE range built from cursor positions (D5). Codes, messages, and
     * severities are unchanged.
     */
    private void error(DiagnosticCode code, String message, DiagnosticRange range) {
        diagnostics.add(CompilerDiagnostic.error(code, message, range));
    }

    /**
     * The SOURCE range from the current token start through the current
     * cursor position (half-open): end = the first unconsumed scalar — the
     * terminator's first scalar for E1002/E1003, EOF for E1004 (D5).
     */
    private DiagnosticRange tokenStartRange() {
        return rangeFromStart(tokenStartLine, tokenStartCol, tokenStartScalarOffset);
    }

    /**
     * The SOURCE half-open range from the given start position through the
     * current cursor position (the first unconsumed scalar).
     */
    private DiagnosticRange rangeFromStart(int startLine, int startCol, int startOffset) {
        int endOffset = cursor.scalarOffset();
        return new DiagnosticRange(file, startLine, startCol,
            cursor.line(), cursor.column(), startOffset, endOffset,
            endOffset - startOffset, RangeOrigin.SOURCE);
    }

    /**
     * The SOURCE range covering exactly the single scalar at the given
     * position (E1001 = the offending single scalar, D5).
     */
    private DiagnosticRange singleScalarRange(int line, int col, int offset) {
        return new DiagnosticRange(file, line, col, line, col + 1,
            offset, offset + 1, 1, RangeOrigin.SOURCE);
    }
}
