package deal.lexer;

import deal.ast.TokenType;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.source.ScalarSourceCursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import deal.diagnostics.DiagnosticCode;

/**
 * Tokenizer that converts DEAL source text into a list of {@link Token}s.
 *
 * <p>Handles all DEAL lexical elements: keywords, identifiers, literals
 * (null, boolean, integer, number, string), operators, punctuation,
 * comments, and whitespace. Emits E1xxx diagnostics for lexical errors
 * and recovers to continue tokenizing.</p>
 *
 * <p>Recognizes compiler directive comments such as {@code // @jsonable}
 * and attaches them to the next non-comment token via
 * {@link Token#directives()}.</p>
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
        Map.entry("has",      TokenType.HAS),
        Map.entry("try",      TokenType.TRY),
        Map.entry("catch",    TokenType.CATCH),
        Map.entry("throw",    TokenType.THROW),
        Map.entry("as",       TokenType.AS)
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

    /** Pending compiler directives accumulated from comment lines (D1). */
    private final List<String> pendingDirectives = new ArrayList<>();

    /** True once at least one non-comment token has been produced. */
    private boolean anyNonCommentToken = false;

    /** True when a {@code @deal-version} directive has already been seen. */
    private boolean dealVersionDirectiveSeen = false;

    /**
     * Creates a new lexer for the given source text.
     *
     * @param source the DEAL source text
     * @param file   the file path (for diagnostics)
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

        // Emit EOF token at the current position: the final cursor position
        // with zero scalar length (D3).  Directives that were never
        // consumed by a following token (a trailing comment line or a file
        // containing only comments) attach to the EOF token so the parser
        // still validates their values.  Placement is enforced at directive
        // inspection time, not here: a directive with no preceding
        // non-comment token satisfies "before the first non-comment token"
        // vacuously.  Attachment routes through the offset-preserving
        // withDirectives copy, so the EOF token keeps its final cursor
        // offsets (D3).
        Token eof = new Token(TokenType.EOF, "", cursor.line(), cursor.column(), 0,
            cursor.scalarOffset(), 0, List.of());
        if (!pendingDirectives.isEmpty()) {
            eof = eof.withDirectives(List.copyOf(pendingDirectives));
            pendingDirectives.clear();
        }
        tokens.add(eof);

        return new LexResult(List.copyOf(tokens), List.copyOf(diagnostics));
    }

    // =========================================================================
    // Token dispatch
    // =========================================================================

    /**
     * Reads the next token from the source, or null if at end of input.
     * Attaches any pending compiler directives to the returned token.
     */
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

        // Attach pending compiler directives to this token (D1).  The
        // attachment routes through the offset-preserving withDirectives
        // copy, so makeToken's computed scalar offsets survive on
        // directive-bearing tokens (D3).
        if (token != null && !pendingDirectives.isEmpty()) {
            token = token.withDirectives(List.copyOf(pendingDirectives));
            pendingDirectives.clear();
        }

        if (token != null) {
            anyNonCommentToken = true;
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

    /**
     * Skips a single-line comment: // ... until end of line.
     *
     * <p>Inspects the comment body for recognized compiler directives
     * (currently {@code @jsonable}) and buffers them in
     * {@link #pendingDirectives} for attachment to the next non-comment
     * token (D1).</p>
     *
     * <p>All non-newline scalars are consumed via {@link #bump()} so column
     * tracking remains accurate when a line comment ends at EOF.</p>
     */
    private void skipLineComment() {
        ScalarSourceCursor.Mark commentStart = cursor.mark(); // first '/'
        bump(); // skip first /
        bump(); // skip second /

        // Inspect comment body for compiler directives (D1)
        inspectDirective(commentStart);

        // Consume the rest of the line (existing behavior)
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
     * Inspects the comment body starting at the current position for
     * recognized compiler directives.  Recognized directives are added
     * to {@link #pendingDirectives}.
     *
     * <p>DEAL v1.2 recognizes exactly five directive names:
     * {@code deal-version}, {@code jsonable}, {@code extern-c},
     * {@code c-struct}, and {@code c-pointer}.  {@code @deal-version} is
     * a file directive: it must occur before the first non-comment token
     * (E1052), may occur at most once (E1053), and accepts exactly one
     * non-empty version argument (E1054).  The other recognized names
     * keep the v1.1 attachment behavior (bare {@code @name} directive on
     * the following token).  Names outside the recognized set remain
     * ordinary comments; rejecting them is tracked separately
     * (ISSUE-0111).</p>
     *
     * <p>The inspection is non-consuming: all lookahead over the name and
     * argument positions runs on cursor mark/reset snapshots, and the
     * comment body itself is consumed by {@link #skipLineComment()}
     * afterwards.</p>
     */
    private void inspectDirective(ScalarSourceCursor.Mark commentStart) {
        ScalarSourceCursor.Mark bodyStart = cursor.mark();

        // Skip optional leading horizontal whitespace between // and '@'.
        int scalar;
        while ((scalar = cursor.peekScalar()) == ' ' || scalar == '\t') {
            cursor.advance();
        }

        // Must start with @ to be a directive
        if (cursor.peekScalar() != '@') {
            cursor.reset(bodyStart);
            return;
        }

        // Complete directive comment range (parent D6): from the first '/'
        // of '//' through the last comment scalar — half-open, with the end
        // at the line terminator's first scalar (or EOF).
        DiagnosticRange commentRange = completeCommentRange(commentStart);

        // Re-walk for the name and argument (non-consuming).
        cursor.reset(bodyStart);
        while ((scalar = cursor.peekScalar()) == ' ' || scalar == '\t') {
            cursor.advance();
        }
        cursor.advance(); // '@'

        // Read the directive name: [a-zA-Z0-9-]+
        StringBuilder name = new StringBuilder();
        while (true) {
            int s = cursor.peekScalar();
            if (s < 0 || !isDirectiveNameChar((char) s)) {
                break;
            }
            name.append((char) s);
            cursor.advance();
        }
        if (name.isEmpty()) {
            // '@' with no name does not match CompilerDirectiveComment.
            cursor.reset(bodyStart);
            return;
        }

        // Read the argument: everything up to the line terminator, with
        // surrounding horizontal whitespace trimmed.
        StringBuilder arg = new StringBuilder();
        while (true) {
            int s = cursor.peekScalar();
            if (s < 0 || s == '\n' || s == '\r') {
                break;
            }
            arg.appendCodePoint(s);
            cursor.advance();
        }
        String argument = arg.toString().trim();

        cursor.reset(bodyStart);

        switch (name.toString()) {
            case "deal-version" -> {
                if (dealVersionDirectiveSeen) {
                    error(DiagnosticCode.E1053,
                        "Duplicate @deal-version directive (each file directive may occur at most once)",
                        commentRange);
                }
                dealVersionDirectiveSeen = true;
                if (anyNonCommentToken) {
                    error(DiagnosticCode.E1052,
                        "@deal-version must occur before the first non-comment token",
                        commentRange);
                }
                if (argument.isEmpty()) {
                    error(DiagnosticCode.E1054,
                        "@deal-version requires exactly one non-empty version argument",
                        commentRange);
                } else if (hasInternalWhitespace(argument)) {
                    error(DiagnosticCode.E1054,
                        "@deal-version requires exactly one non-empty version argument, got: '"
                            + argument + "'",
                        commentRange);
                } else {
                    pendingDirectives.add("@deal-version " + argument);
                }
            }
            case "jsonable", "extern-c", "c-struct", "c-pointer" -> {
                // v1.1-compatible attachment: the bare directive name
                // attaches to the following token; any trailing text is
                // not part of the directive.
                pendingDirectives.add("@" + name);
            }
            default -> {
                // v1.1-compatible: an unrecognized directive name stays
                // an ordinary comment.  Rejecting it with E1056 is the
                // tracked follow-up ISSUE-0111.
            }
        }
    }

    /**
     * Computes the complete directive comment range without consuming: from
     * the first {@code /} of {@code //} (the cursor position recorded in
     * {@code commentStart}) through the last comment scalar, half-open with
     * the end at the line terminator's first scalar (or EOF). The cursor is
     * restored to {@code commentStart} before returning.
     */
    private DiagnosticRange completeCommentRange(ScalarSourceCursor.Mark commentStart) {
        cursor.reset(commentStart);
        int startLine = cursor.line();
        int startCol = cursor.column();
        int startOffset = cursor.scalarOffset();

        int s;
        while ((s = cursor.peekScalar()) >= 0 && s != '\n' && s != '\r') {
            cursor.advance();
        }
        int endOffset = cursor.scalarOffset();
        DiagnosticRange range = new DiagnosticRange(file, startLine, startCol,
            cursor.line(), cursor.column(), startOffset, endOffset,
            endOffset - startOffset, RangeOrigin.SOURCE);

        cursor.reset(commentStart);
        return range;
    }

    private static boolean isDirectiveNameChar(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '-';
    }

    private static boolean hasInternalWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return true;
        }
        return false;
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
     * the complete comment range (comment start through EOF, D5).</p>
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
     * Reads an integer or number literal.
     *
     * <p>Integer: {@code [0-9]+}
     * <br>Number:  {@code ([0-9]* '.' [0-9]+ ([eE] [+-]? [0-9]+)?)
     *                    | ([0-9]+ [eE] [+-]? [0-9]+)}</p>
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
     * Reads a backtick-delimited template literal.
     *
     * <p>The lexeme is the raw content between the backticks (backticks not
     * included).  Line terminators inside the literal produce E1003 per the
     * normative grammar (template literals are single-line).</p>
     *
     * <p>Newline handling order (D18, D19):
     * <ol>
     *   <li>Check for bare newline BEFORE checking for {@code \\}.</li>
     *   <li>Within the {@code \\} handler, peek at the next character:
     *       if it is a line terminator, treat it as the terminating newline
     *       rather than as an escaped character.</li>
     *   <li>After calling {@code newline()} for {@code \r}, conditionally
     *       consume a following {@code \n} with {@link #skipCrlfLf()}
     *       (no second line break), matching the existing
     *       {@code readString()} and {@code skipWhitespaceAndComments()}
     *       pattern.</li>
     * </ol>
     */
    private Token readTemplateLiteral() {
        bump(); // opening backtick

        // Record start of raw content (after the opening backtick)
        int contentStart = pos;

        while (pos < source.length()) {
            char c = source.charAt(pos);

            // Step 1: Newline check first (bare newline — E1003)
            if (c == '\n') {
                error(DiagnosticCode.E1003,
                    "Unterminated template literal: missing closing backtick",
                    tokenStartRange());
                String lexeme = source.substring(contentStart, pos);
                newline();
                return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
            }
            if (c == '\r') {
                error(DiagnosticCode.E1003,
                    "Unterminated template literal: missing closing backtick",
                    tokenStartRange());
                String lexeme = source.substring(contentStart, pos);
                newline();
                // D19: conditionally consume LF after CR
                if (pos < source.length() && source.charAt(pos) == '\n') {
                    skipCrlfLf();
                }
                return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
            }

            // Step 2: Backslash escape
            if (c == '\\') {
                bump(); // consume backslash
                if (pos >= source.length()) {
                    // EOF after backslash
                    error(DiagnosticCode.E1003,
                        "Unterminated template literal: escape at end of file",
                        tokenStartRange());
                    String lexeme = source.substring(contentStart, pos);
                    return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
                }
                char next = source.charAt(pos);
                // D18: backslash + newline → not an escape; terminate with E1003
                if (next == '\n') {
                    error(DiagnosticCode.E1003,
                        "Unterminated template literal: missing closing backtick",
                        tokenStartRange());
                    String lexeme = source.substring(contentStart, pos - 1);
                    newline();
                    return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
                }
                if (next == '\r') {
                    error(DiagnosticCode.E1003,
                        "Unterminated template literal: missing closing backtick",
                        tokenStartRange());
                    String lexeme = source.substring(contentStart, pos - 1);
                    newline();
                    // D19: conditionally consume LF after CR
                    if (pos < source.length() && source.charAt(pos) == '\n') {
                        skipCrlfLf();
                    }
                    return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
                }
                // Valid escape: consume the escaped character (backtick after
                // backslash does not close the template)
                bump();
                continue;
            }

            // Step 3: Closing backtick
            if (c == '`') {
                bump(); // consume closing backtick
                String lexeme = source.substring(contentStart, pos - 1);
                return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
            }

            // Step 4: Other characters
            bump();
        }

        // Reached EOF without closing backtick
        error(DiagnosticCode.E1003,
            "Unterminated template literal: missing closing backtick",
            tokenStartRange());
        String lexeme = source.substring(contentStart, pos);
        return makeToken(TokenType.TEMPLATE_LITERAL, lexeme);
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
     * {@link #nextToken()} and computes {@code scalarLength} as the cursor
     * distance consumed since (D3).
     */
    private Token makeToken(TokenType type, String lexeme) {
        int scalarLen = cursor.scalarOffset() - tokenStartScalarOffset;
        return new Token(type, lexeme, tokenStartLine, tokenStartCol,
            lexeme.length(), tokenStartScalarOffset, scalarLen, List.of());
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
