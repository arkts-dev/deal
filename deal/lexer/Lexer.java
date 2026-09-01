package deal.lexer;

import deal.ast.TokenType;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticNote;
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
 * <p>Recognizes compiler directive comments such as {@code // @jsonable}
 * and emits structured {@link CompilerDirective} events
 * (fixed-name-directive-events D1–D3): fixed-name first-match splitting,
 * unknown-name and punctuation/empty recovery (E1044 — the only
 * directive diagnostic the lexer emits), and the ordered next-token
 * anchoring machine. The events travel in
 * {@link LexResult#directiveEvents()}; tokens carry no directives.</p>
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
 * for (CompilerDirective e : result.directiveEvents()) { ... }
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

    /**
     * The fixed directive alternatives in matching order (D2): the first
     * complete literal match after {@code @} is the name.
     */
    private static final List<DirectiveName> FIXED_NAMES = List.of(
        DirectiveName.DEAL_VERSION,
        DirectiveName.JSONABLE,
        DirectiveName.EXTERN_C,
        DirectiveName.C_STRUCT,
        DirectiveName.C_POINTER
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

    /** Structured directive events, ordered by eventIndex (D1). */
    private final List<CompilerDirective> directiveEvents = new ArrayList<>();

    /**
     * The pending declaration run (D3): event indices into
     * {@link #directiveEvents}. A declaration directive starts or
     * extends it; whitespace preserves it; an ordinary comment, block
     * comment, file directive, or unknown directive clears it without
     * anchoring. When a non-comment token is about to be emitted, every
     * event in the still-pending run is anchored at that token's stream
     * index — anchor before clear before emission.
     */
    private final List<Integer> pendingDeclarationRun = new ArrayList<>();

    /** Non-comment tokens emitted so far (the preceding token count). */
    private int nonCommentTokenCount = 0;

    private int pos;      // current UTF-16 index in source (0-based), in lockstep with cursor

    // Token start position (set before reading each token)
    private int tokenStartLine;
    private int tokenStartCol;
    private int tokenStartScalarOffset;

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
     * @return a {@link LexResult} containing all tokens, any diagnostics,
     *         and the ordered directive events
     */
    public LexResult tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (pos < source.length()) {
            Token token = nextToken(tokens.size());
            if (token != null) {
                tokens.add(token);
            }
        }

        // Emit EOF token at the current position: the final cursor position
        // with zero scalar length (D3). EOF performs no anchoring
        // transition (D3): leftover events stay in directiveEvents as
        // unanchored records for the parser's placement diagnostics — the
        // EOF token never carries events (D1).
        Token eof = new Token(TokenType.EOF, "", cursor.line(), cursor.column(), 0,
            cursor.scalarOffset(), 0);
        tokens.add(eof);

        return new LexResult(List.copyOf(tokens), List.copyOf(diagnostics),
            List.copyOf(directiveEvents));
    }

    // =========================================================================
    // Token dispatch
    // =========================================================================

    /**
     * Reads the next token from the source, or null if at end of input.
     * Before the token is emitted, the ordered transition assigns the
     * token's stream index ({@code nextTokenIndex}) to every event in the
     * still-pending declaration run, then clears the run, then emits the
     * token (anchor before clear before emission, D3).
     */
    private Token nextToken(int nextTokenIndex) {
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

        if (token != null) {
            // D3: anchor before clear before token emission — every event
            // of one run satisfies
            // declarationAnchorTokenIndex == precedingNonCommentTokenCount
            // because no non-comment token can be emitted between event
            // creation and anchoring.
            if (!pendingDeclarationRun.isEmpty()) {
                for (int eventIndex : pendingDeclarationRun) {
                    directiveEvents.set(eventIndex,
                        directiveEvents.get(eventIndex)
                            .withDeclarationAnchor(nextTokenIndex));
                }
                pendingDeclarationRun.clear();
            }
            nonCommentTokenCount++;
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
     * <p>Scans the comment body for a compiler directive event (D1–D2)
     * and drives the declaration-run machine (D3). All non-newline
     * scalars are consumed via {@link #bump()} so column tracking remains
     * accurate when a line comment ends at EOF.</p>
     */
    private void skipLineComment() {
        ScalarSourceCursor.Mark commentStart = cursor.mark(); // first '/'
        bump(); // skip first /
        bump(); // skip second /

        scanDirectiveBody(commentStart);

        // Consume the rest of the line (the directive scan has already
        // advanced through the name and raw argument; this loop consumes
        // any remaining scalars — the horizontal whitespace and punctuation
        // after an unknown/empty form — up to the terminator).
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
     * Scans the comment body starting at the current position for a
     * compiler directive (D2) and updates the declaration-run machine
     * (D3). The comment body starts after the consumed {@code //}; the
     * cursor is left at the first scalar the rest-of-line consumption
     * loop must still consume.
     *
     * <p>Rules:
     * <ul>
     *   <li>Start only when, after {@code //} and optional {@code [ \t]*},
     *       the next scalar is {@code @}.</li>
     *   <li>First-match fixed alternatives in the fixed order; every
     *       remaining pre-terminator scalar is the raw argument (no
     *       delimiter requirement); a recognized prefix is never
     *       reclassified as unknown.</li>
     *   <li>Unknown-name recovery: the maximal
     *       {@code [A-Za-z][A-Za-z0-9-]*} run → E1044 at the recovered
     *       name range with a note carrying the complete comment.</li>
     *   <li>Punctuation/empty forms: E1044 over {@code @} plus the
     *       maximal adjacent run of scalars that are not space, tab,
     *       line terminator, or EOF.</li>
     *   <li>E1044 is the only directive diagnostic the lexer emits.</li>
     * </ul></p>
     *
     * <p>Run machine (D3): a declaration directive starts or extends the
     * run; an ordinary comment, a file directive, or an unknown directive
     * clears the run without anchoring it; the breaking directive keeps
     * its own event/diagnostic.</p>
     */
    private void scanDirectiveBody(ScalarSourceCursor.Mark commentStart) {
        // Optional horizontal whitespace between // and '@'.
        int scalar;
        while ((scalar = cursor.peekScalar()) == ' ' || scalar == '\t') {
            advanceCursor();
        }

        // Must start with '@' to be a directive; an ordinary comment
        // clears the run without anchoring (D3).
        if (cursor.peekScalar() != '@') {
            pendingDeclarationRun.clear();
            return;
        }

        ScalarSourceCursor.Mark atMark = cursor.mark();
        advanceCursor(); // '@'
        ScalarSourceCursor.Mark nameStartMark = cursor.mark();

        // D2 step 2: try the five fixed alternatives in the fixed order.
        // The first complete literal match is the name.
        DirectiveName matched = null;
        for (DirectiveName candidate : FIXED_NAMES) {
            if (tryMatchLiteral(candidate.text())) {
                matched = candidate;
                break;
            }
        }

        if (matched != null) {
            ScalarSourceCursor.Mark nameEndMark = cursor.mark();

            // Raw argument: every remaining pre-terminator scalar.
            StringBuilder raw = new StringBuilder();
            while (true) {
                int s = cursor.peekScalar();
                if (s < 0 || s == '\n' || s == '\r') {
                    break;
                }
                raw.appendCodePoint(s);
                advanceCursor();
            }
            // The cursor now sits at the terminator's first scalar (or
            // EOF); the rest-of-line loop below will consume it.

            DiagnosticRange sourceRange = rangeBetween(commentStart,
                cursor.mark());
            DiagnosticRange nameRange = rangeBetween(nameStartMark, nameEndMark);
            CompilerDirective event = new CompilerDirective(
                directiveEvents.size(), matched, raw.toString(),
                trimHorizontal(raw.toString()), sourceRange, nameRange,
                nonCommentTokenCount, null);
            addDirectiveEvent(event);
            // D3: a file directive clears the run without anchoring it;
            // a declaration directive starts or extends it.
            if (matched.isDeclarationDirective()) {
                pendingDeclarationRun.add(event.eventIndex());
            } else {
                pendingDeclarationRun.clear();
            }
            return;
        }

        // No fixed alternative matches from the first character after '@'.
        int first = cursor.peekScalar();
        if (first >= 0 && isAsciiLetter(first)) {
            // D2 step 3: unknown-name recovery — the maximal
            // [A-Za-z][A-Za-z0-9-]* run for E1044. A recognized prefix is
            // never reclassified as unknown (handled above).
            while (true) {
                int s = cursor.peekScalar();
                if (!(isAsciiLetter(s) || (s >= '0' && s <= '9') || s == '-')) {
                    break;
                }
                advanceCursor();
            }
            ScalarSourceCursor.Mark nameEndMark = cursor.mark();
            DiagnosticRange complete = completeCommentRangeFrom(commentStart);
            DiagnosticRange nameRange = rangeBetween(nameStartMark, nameEndMark);
            errorWithNote(DiagnosticCode.E1044,
                "Unrecognized compiler directive", nameRange, complete);
            CompilerDirective event = new CompilerDirective(
                directiveEvents.size(), null, "", "", complete, nameRange,
                nonCommentTokenCount, null);
            addDirectiveEvent(event);
            // Unknown directives break runs without anchoring (D3).
            pendingDeclarationRun.clear();
            return;
        }

        // D2 step 4: punctuation/empty form — E1044 over '@' plus the
        // maximal adjacent run of scalars that are not space, tab, line
        // terminator, or EOF.
        while (true) {
            int s = cursor.peekScalar();
            if (s < 0 || s == ' ' || s == '\t' || s == '\n' || s == '\r') {
                break;
            }
            advanceCursor();
        }
        ScalarSourceCursor.Mark runEndMark = cursor.mark();
        DiagnosticRange complete = completeCommentRangeFrom(commentStart);
        DiagnosticRange runRange = rangeBetween(atMark, runEndMark);
        errorWithNote(DiagnosticCode.E1044,
            "Unrecognized compiler directive", runRange, complete);
        CompilerDirective event = new CompilerDirective(
            directiveEvents.size(), null, "", "", complete, runRange,
            nonCommentTokenCount, null);
        addDirectiveEvent(event);
        pendingDeclarationRun.clear();
    }

    /** Appends one event to the ordered event list (D1). */
    private void addDirectiveEvent(CompilerDirective event) {
        directiveEvents.add(event);
    }

    /**
     * Tries to match the given ASCII literal at the current cursor
     * position without a net position change on failure: on a complete
     * match the cursor advances past the literal and returns true;
     * otherwise the cursor is restored and false is returned.
     */
    private boolean tryMatchLiteral(String literal) {
        ScalarSourceCursor.Mark mark = cursor.mark();
        for (int i = 0; i < literal.length(); i++) {
            if (cursor.peekScalar() != literal.charAt(i)) {
                resetCursor(mark);
                return false;
            }
            advanceCursor();
        }
        return true;
    }

    /**
     * Computes the complete directive comment range without a net
     * position change: from the first {@code /} of {@code //} (the
     * cursor position recorded in {@code commentStart}) through the last
     * comment scalar, half-open with the end at the line terminator's
     * first scalar (or EOF). The cursor position at entry is restored
     * before returning.
     */
    private DiagnosticRange completeCommentRangeFrom(ScalarSourceCursor.Mark commentStart) {
        ScalarSourceCursor.Mark current = cursor.mark();
        resetCursor(commentStart);
        int s;
        while ((s = cursor.peekScalar()) >= 0 && s != '\n' && s != '\r') {
            advanceCursor();
        }
        DiagnosticRange range = rangeBetween(commentStart, cursor.mark());
        resetCursor(current);
        return range;
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

    /** Strips only U+0020 and U+0009 from both ends (D2). */
    private static String trimHorizontal(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end) {
            char c = raw.charAt(start);
            if (c != ' ' && c != '\t') break;
            start++;
        }
        while (end > start) {
            char c = raw.charAt(end - 1);
            if (c != ' ' && c != '\t') break;
            end--;
        }
        return raw.substring(start, end);
    }

    private static boolean isAsciiLetter(int scalar) {
        return (scalar >= 'a' && scalar <= 'z')
            || (scalar >= 'A' && scalar <= 'Z');
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
     * A block comment breaks a pending declaration run without anchoring
     * it (D3).</p>
     */
    private void skipBlockComment() {
        int startLine = cursor.line();
        int startCol = cursor.column();
        int startOffset = cursor.scalarOffset();
        bump(); // skip first /
        bump(); // skip *

        // D3: an ordinary (block) comment clears the run without anchoring.
        pendingDeclarationRun.clear();

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
     * Advances the cursor during non-consuming directive lookahead while
     * keeping the UTF-16 {@code pos} in lockstep (the scalar cursor is
     * the owner; {@code pos} must always mirror {@code cursor.index()}).
     */
    private void advanceCursor() {
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
     * {@link #nextToken(int)} and computes {@code scalarLength} as the
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
     * Emits a ranged {@link CompilerDiagnostic} with one secondary-range
     * note carrying the complete directive comment (E1044, D6).
     */
    private void errorWithNote(DiagnosticCode code, String message,
                               DiagnosticRange range, DiagnosticRange noteRange) {
        diagnostics.add(new CompilerDiagnostic(code.code(), "error", message,
            range, List.of(new DiagnosticNote(
                "complete directive comment", noteRange)), code));
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
