package deal.test;

import deal.ast.TokenType;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.*;
import deal.source.ScalarSourceCursor;

import java.util.List;

/**
 * Comprehensive unit tests for the DEAL lexer.
 * Runs via main() using assertions. Enable with -ea JVM flag.
 */
public class LexerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static LexResult tokenize(String source) {
        return new Lexer(source, "test.deal").tokenize();
    }

    private static void assertToken(Token token, TokenType expectedType,
                                     String expectedLexeme, int expectedLine,
                                     int expectedCol) {
        check(token.type() == expectedType,
            String.format("type: expected %s, got %s", expectedType, token.type()));
        check(token.lexeme().equals(expectedLexeme),
            String.format("lexeme: expected '%s', got '%s'", expectedLexeme, token.lexeme()));
        check(token.line() == expectedLine,
            String.format("line: expected %d, got %d", expectedLine, token.line()));
        check(token.column() == expectedCol,
            String.format("column: expected %d, got %d", expectedCol, token.column()));
    }

    private static void assertTokenTypeOnly(Token token, TokenType expectedType,
                                             String context) {
        check(token.type() == expectedType,
            String.format("%s: expected type %s, got %s (%s)",
                context, expectedType, token.type(), token.lexeme()));
    }

    private static void assertDiagnosticCount(List<CompilerDiagnostic> diags,
                                               int expectedCount, String context) {
        check(diags.size() == expectedCount,
            String.format("%s: expected %d diagnostics, got %d: %s",
                context, expectedCount, diags.size(), diags));
    }

    private static void assertNoDiagnostics(List<CompilerDiagnostic> diags, String context) {
        assertDiagnosticCount(diags, 0, context);
    }

    private static void assertDiagnosticCode(List<CompilerDiagnostic> diags,
                                              String expectedCode, String context) {
        check(diags.stream().anyMatch(d -> d.code().equals(expectedCode)),
            String.format("%s: expected diagnostic %s, got: %s",
                context, expectedCode, diags));
    }

    /** Returns the first diagnostic with the given code, or null. */
    private static CompilerDiagnostic findDiag(LexResult r, String code) {
        for (CompilerDiagnostic d : r.diagnostics()) {
            if (d.code().equals(code)) {
                return d;
            }
        }
        return null;
    }

    /** Asserts the complete half-open SOURCE range of a diagnostic. */
    private static void assertRange(CompilerDiagnostic d, String file,
            int startLine, int startCol, int endLine, int endCol,
            int startOffset, int endOffset, String context) {
        DiagnosticRange r = d.range();
        check(r.origin() == RangeOrigin.SOURCE,
            context + ": origin SOURCE, got " + r.origin());
        check(r.file().equals(file),
            context + ": file expected " + file + ", got " + r.file());
        check(r.startLine() == startLine && r.startColumn() == startCol,
            context + ": start expected " + startLine + ":" + startCol
                + ", got " + r.startLine() + ":" + r.startColumn());
        check(r.endLine() == endLine && r.endColumn() == endCol,
            context + ": end expected " + endLine + ":" + endCol
                + ", got " + r.endLine() + ":" + r.endColumn());
        check(r.startScalarOffset() == startOffset
                && r.endScalarOffset() == endOffset,
            context + ": offsets expected [" + startOffset + "," + endOffset
                + "), got [" + r.startScalarOffset() + ","
                + r.endScalarOffset() + ")");
        check(r.scalarLength() == endOffset - startOffset,
            context + ": scalar length expected " + (endOffset - startOffset)
                + ", got " + r.scalarLength());
    }

    /**
     * Recomputes every emitted token's scalar offsets through an
     * independent {@link ScalarSourceCursor} walk: the start offset is
     * {@code scalarCount(source, 0, lexemeUtf16Index)} and the scalar
     * length is {@code scalarCount(lexeme)}; the EOF token must carry the
     * final cursor position and zero scalar length.
     */
    private static void assertTokenOffsetsMatchCursorWalk(String source) {
        LexResult r = tokenize(source);
        int searchFrom = 0;
        for (Token t : r.tokens()) {
            if (t.type() == TokenType.EOF) {
                ScalarSourceCursor c = new ScalarSourceCursor(source);
                while (!c.atEnd()) {
                    c.advance();
                }
                check(t.startScalarOffset() == c.scalarOffset(),
                    "EOF offset equals total scalar count for: " + source
                        + " (got " + t.startScalarOffset() + ", expected "
                        + c.scalarOffset() + ")");
                check(t.scalarLength() == 0,
                    "EOF scalar length is 0 for: " + source);
                check(t.line() == c.line() && t.column() == c.column(),
                    "EOF position matches final cursor position for: "
                        + source + " (got " + t.line() + ":" + t.column()
                        + ", expected " + c.line() + ":" + c.column() + ")");
                continue;
            }
            int idx = source.indexOf(t.lexeme(), searchFrom);
            check(idx >= 0,
                "lexeme '" + t.lexeme() + "' located for recomputation in: "
                    + source);
            searchFrom = idx + Math.max(1, t.length());
            check(t.startScalarOffset()
                    == ScalarSourceCursor.scalarCount(source, 0, idx),
                "start offset recomputed via scalarCount for '" + t.lexeme()
                    + "' in: " + source + " (got " + t.startScalarOffset()
                    + ", expected " + ScalarSourceCursor.scalarCount(source, 0, idx) + ")");
            check(t.scalarLength() == ScalarSourceCursor.scalarCount(t.lexeme()),
                "scalar length recomputed via scalarCount for '" + t.lexeme()
                    + "' in: " + source + " (got " + t.scalarLength()
                    + ", expected " + ScalarSourceCursor.scalarCount(t.lexeme()) + ")");
        }
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Lexer Tests ===");

        testKeywords();
        testOperators();
        testPunctuation();
        testIntegerLiterals();
        testNumberLiterals();
        testStringLiterals();
        testTemplateLiterals();
        testIdentifiers();
        testCompleteLine();
        testLineColumnTracking();
        testComments();
        testUnterminatedString();
        testUnrecognizedCharacter();
        testMalformedNumber();
        testUnterminatedBlockComment();
        testEOFToken();
        testEmptyInput();
        testErrorRecovery();
        testTokenSpan();
        testAllTokenTypesAppear();
        testDotNumber();
        testBlockCommentColumnTracking();
        testMultipleDotsInNumber();
        testBlockCommentNoNesting();
        testLineCommentColumnAtEOF();
        testInTokenizesAsIdentifier();
        testOfTokenizesAsKeyword();
        testAsyncTokenizesAsKeyword();
        testAwaitTokenizesAsKeyword();
        testDirectiveComments();
        testDealVersionDirectives();
        testScalarOffsets();
        testDiagnosticRanges();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Keyword tests
    // =========================================================================

    static void testKeywords() {
        System.out.println("-- Keywords --");

        // v1.1 keyword set: 25 keywords
        String[] keywords = {
            "let", "class", "function", "async", "await",
            "return", "if", "else",
            "while", "for", "of", "break", "continue",
            "null", "true", "false",
            "import", "export", "from", "delete", "has",
            "try", "catch", "throw",
            "as"
        };

        TokenType[] types = {
            TokenType.LET, TokenType.CLASS, TokenType.FUNCTION,
            TokenType.ASYNC, TokenType.AWAIT,
            TokenType.RETURN,
            TokenType.IF, TokenType.ELSE, TokenType.WHILE, TokenType.FOR,
            TokenType.OF,
            TokenType.BREAK, TokenType.CONTINUE,
            TokenType.NULL, TokenType.TRUE, TokenType.FALSE,
            TokenType.IMPORT, TokenType.EXPORT, TokenType.FROM, TokenType.DELETE,
            TokenType.HAS, TokenType.TRY, TokenType.CATCH, TokenType.THROW,
            TokenType.AS
        };

        check(keywords.length == 25, "keyword set has exactly 25 entries, got " + keywords.length);
        check(keywords.length == types.length, "keywords and types arrays have same length");

        for (int i = 0; i < keywords.length; i++) {
            LexResult result = tokenize(keywords[i]);
            assertNoDiagnostics(result.diagnostics(), "keyword " + keywords[i]);
            List<Token> tokens = result.tokens();
            check(tokens.size() == 2, "keyword " + keywords[i] + ": expected 2 tokens (keyword + EOF)");
            assertToken(tokens.get(0), types[i], keywords[i], 1, 1);
            assertToken(tokens.get(1), TokenType.EOF, "", 1, 1 + keywords[i].length());
        }
    }

    // =========================================================================
    // Operator tests
    // =========================================================================

    static void testOperators() {
        System.out.println("-- Operators --");

        record OpTest(String source, TokenType type, String lexeme) {}

        OpTest[] tests = {
            new OpTest("+",   TokenType.PLUS,       "+"),
            new OpTest("-",   TokenType.MINUS,      "-"),
            new OpTest("*",   TokenType.STAR,       "*"),
            new OpTest("/",   TokenType.SLASH,      "/"),
            new OpTest("%",   TokenType.PERCENT,    "%"),
            new OpTest("**",  TokenType.STAR_STAR,  "**"),
            new OpTest("===", TokenType.EQ_STRICT,  "==="),
            new OpTest("!==", TokenType.NEQ_STRICT, "!=="),
            new OpTest("==",  TokenType.EQ,         "=="),
            new OpTest("!=",  TokenType.NEQ,        "!="),
            new OpTest("<",   TokenType.LT,         "<"),
            new OpTest("<=",  TokenType.LTE,        "<="),
            new OpTest(">",   TokenType.GT,         ">"),
            new OpTest(">=",  TokenType.GTE,        ">="),
            new OpTest("&&",  TokenType.AND,        "&&"),
            new OpTest("||",  TokenType.OR,         "||"),
            new OpTest("!",   TokenType.BANG,       "!"),
            new OpTest("=",   TokenType.EQ_SIGN,    "="),
            new OpTest("=>",  TokenType.ARROW,      "=>"),
            new OpTest("|",   TokenType.PIPE,       "|"),
            new OpTest("&",   TokenType.AMPERSAND,  "&"),
            new OpTest(".",   TokenType.DOT,        "."),
            new OpTest("?",   TokenType.QUESTION,   "?"),
            new OpTest("...", TokenType.ELLIPSIS,   "..."),
        };

        for (OpTest t : tests) {
            LexResult result = tokenize(t.source);
            assertNoDiagnostics(result.diagnostics(), "operator " + t.source);
            List<Token> tokens = result.tokens();
            check(tokens.size() == 2,
                "operator " + t.source + ": expected 2 tokens, got " + tokens.size());
            assertToken(tokens.get(0), t.type, t.lexeme, 1, 1);
        }
    }

    // =========================================================================
    // Punctuation tests
    // =========================================================================

    static void testPunctuation() {
        System.out.println("-- Punctuation --");

        record PuncTest(String source, TokenType type) {}
        PuncTest[] tests = {
            new PuncTest(":", TokenType.COLON),
            new PuncTest(";", TokenType.SEMICOLON),
            new PuncTest(",", TokenType.COMMA),
            new PuncTest("(", TokenType.LPAREN),
            new PuncTest(")", TokenType.RPAREN),
            new PuncTest("[", TokenType.LBRACKET),
            new PuncTest("]", TokenType.RBRACKET),
            new PuncTest("{", TokenType.LBRACE),
            new PuncTest("}", TokenType.RBRACE),
        };

        for (PuncTest t : tests) {
            LexResult result = tokenize(t.source);
            assertNoDiagnostics(result.diagnostics(), "punctuation " + t.source);
            List<Token> tokens = result.tokens();
            check(tokens.size() == 2,
                "punctuation " + t.source + ": expected 2 tokens");
            assertToken(tokens.get(0), t.type, t.source, 1, 1);
        }
    }

    // =========================================================================
    // Integer literal tests
    // =========================================================================

    static void testIntegerLiterals() {
        System.out.println("-- Integer Literals --");

        LexResult r = tokenize("0");
        assertNoDiagnostics(r.diagnostics(), "int 0");
        assertToken(r.tokens().get(0), TokenType.INT_LITERAL, "0", 1, 1);

        r = tokenize("42");
        assertNoDiagnostics(r.diagnostics(), "int 42");
        assertToken(r.tokens().get(0), TokenType.INT_LITERAL, "42", 1, 1);

        r = tokenize("-1");
        assertNoDiagnostics(r.diagnostics(), "int -1");
        check(r.tokens().size() == 3, "-1: expected 3 tokens (minus, int, eof)");
        assertToken(r.tokens().get(0), TokenType.MINUS, "-", 1, 1);
        assertToken(r.tokens().get(1), TokenType.INT_LITERAL, "1", 1, 2);

        r = tokenize("9007199254740991");
        assertNoDiagnostics(r.diagnostics(), "int large");
        assertToken(r.tokens().get(0), TokenType.INT_LITERAL, "9007199254740991", 1, 1);
    }

    // =========================================================================
    // Number literal tests
    // =========================================================================

    static void testNumberLiterals() {
        System.out.println("-- Number Literals --");

        LexResult r = tokenize("1.5");
        assertNoDiagnostics(r.diagnostics(), "number 1.5");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, "1.5", 1, 1);

        r = tokenize("0.0");
        assertNoDiagnostics(r.diagnostics(), "number 0.0");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, "0.0", 1, 1);

        r = tokenize("1e10");
        assertNoDiagnostics(r.diagnostics(), "number 1e10");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, "1e10", 1, 1);

        r = tokenize("1.0e-3");
        assertNoDiagnostics(r.diagnostics(), "number 1.0e-3");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, "1.0e-3", 1, 1);

        r = tokenize("1E10");
        assertNoDiagnostics(r.diagnostics(), "number 1E10");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, "1E10", 1, 1);

        r = tokenize(".5");
        assertNoDiagnostics(r.diagnostics(), "number .5");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, ".5", 1, 1);

        r = tokenize("1e+5");
        assertNoDiagnostics(r.diagnostics(), "number 1e+5");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, "1e+5", 1, 1);
    }

    // =========================================================================
    // String literal tests
    // =========================================================================

    static void testStringLiterals() {
        System.out.println("-- String Literals --");

        LexResult r = tokenize("\"hello\"");
        assertNoDiagnostics(r.diagnostics(), "string \"hello\"");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "\"hello\"", 1, 1);

        r = tokenize("'world'");
        assertNoDiagnostics(r.diagnostics(), "string 'world'");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "'world'", 1, 1);

        r = tokenize("\"\"");
        assertNoDiagnostics(r.diagnostics(), "string empty");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "\"\"", 1, 1);

        r = tokenize("\"a\\nb\"");
        assertNoDiagnostics(r.diagnostics(), "string \\n");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "\"a\\nb\"", 1, 1);

        r = tokenize("\"a\\tb\"");
        assertNoDiagnostics(r.diagnostics(), "string \\t");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "\"a\\tb\"", 1, 1);

        r = tokenize("\"a\\\\b\"");
        assertNoDiagnostics(r.diagnostics(), "string \\\\");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "\"a\\\\b\"", 1, 1);

        r = tokenize("\"a\\\"b\"");
        assertNoDiagnostics(r.diagnostics(), "string \\\"");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "\"a\\\"b\"", 1, 1);

        r = tokenize("'a\\'b'");
        assertNoDiagnostics(r.diagnostics(), "string \\'");
        assertToken(r.tokens().get(0), TokenType.STRING_LITERAL, "'a\\'b'", 1, 1);
    }

    // =========================================================================
    // Template literal tests
    // =========================================================================

    static void testTemplateLiterals() {
        System.out.println("-- Template Literals --");

        // Basic template literal
        LexResult r = tokenize("`hello`");
        assertNoDiagnostics(r.diagnostics(), "template 'hello'");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "hello", 1, 1);

        // Empty template literal
        r = tokenize("``");
        assertNoDiagnostics(r.diagnostics(), "empty template");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "", 1, 1);

        // Template with interpolation — lexer sees raw content between backticks
        r = tokenize("`Hello ${name}`");
        assertNoDiagnostics(r.diagnostics(), "template with interpolation");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "Hello ${name}", 1, 1);

        // Template with leading interpolation
        r = tokenize("`${greeting} world`");
        assertNoDiagnostics(r.diagnostics(), "template leading interpolation");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "${greeting} world", 1, 1);

        // Escaped backtick does not close template
        r = tokenize("`a\\`b`");
        assertNoDiagnostics(r.diagnostics(), "escaped backtick");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "a\\`b", 1, 1);

        // Escaped dollar sign
        r = tokenize("`a\\$b`");
        assertNoDiagnostics(r.diagnostics(), "escaped dollar");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "a\\$b", 1, 1);

        // Escaped backslash
        r = tokenize("`a\\\\b`");
        assertNoDiagnostics(r.diagnostics(), "escaped backslash");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "a\\\\b", 1, 1);

        // Multiple escapes
        r = tokenize("`\\`\\\\\\$`");
        assertNoDiagnostics(r.diagnostics(), "multiple escapes");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "\\`\\\\\\$", 1, 1);

        // Template literal with newlines in source (for interpolation with braces)
        r = tokenize("`${ {x:1} }`");
        assertNoDiagnostics(r.diagnostics(), "template with nested braces");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "${ {x:1} }", 1, 1);

        // =====================================================================
        // Unterminated template literal — EOF before closing backtick
        // =====================================================================
        r = tokenize("`unterminated");
        assertDiagnosticCode(r.diagnostics(), "E1003", "unterminated template EOF");
        check(r.tokens().get(0).type() == TokenType.TEMPLATE_LITERAL,
            "unterminated template still produces TEMPLATE_LITERAL token");
        check(r.tokens().get(0).lexeme().equals("unterminated"),
            "unterminated template lexeme is content before EOF");

        // =====================================================================
        // Newline inside template literal → E1003
        // =====================================================================
        r = tokenize("`hello\nworld`");
        assertDiagnosticCode(r.diagnostics(), "E1003", "newline in template");
        check(r.tokens().get(0).type() == TokenType.TEMPLATE_LITERAL,
            "template with newline produces TEMPLATE_LITERAL");
        check(r.tokens().get(0).lexeme().equals("hello"),
            "template lexeme is content before newline");
        // After the newline, remaining source should still tokenize
        boolean hasWorld = r.tokens().stream()
            .anyMatch(t -> t.type() == TokenType.IDENTIFIER && t.lexeme().equals("world"));
        check(hasWorld, "'world' identifier found after unterminated template");

        // =====================================================================
        // Carriage return inside template literal → E1003
        // =====================================================================
        r = tokenize("`hello\rworld`");
        assertDiagnosticCode(r.diagnostics(), "E1003", "CR in template");
        check(r.tokens().get(0).type() == TokenType.TEMPLATE_LITERAL,
            "template with CR produces TEMPLATE_LITERAL");
        check(r.tokens().get(0).lexeme().equals("hello"),
            "template lexeme is content before CR");

        // =====================================================================
        // Windows CRLF inside template literal → E1003, line incremented once
        // =====================================================================
        r = tokenize("`hello\r\nworld`");
        assertDiagnosticCode(r.diagnostics(), "E1003", "CRLF in template");
        check(r.tokens().get(0).lexeme().equals("hello"),
            "template lexeme is content before CRLF");
        // Verify that 'world' is on line 2 (line incremented exactly once)
        Token worldToken = null;
        for (Token t : r.tokens()) {
            if (t.type() == TokenType.IDENTIFIER && t.lexeme().equals("world")) {
                worldToken = t;
                break;
            }
        }
        check(worldToken != null, "world token found after CRLF template");
        check(worldToken.line() == 2,
            "world token on line 2 after CRLF (got " + worldToken.line() + ")");

        // =====================================================================
        // Backslash followed by newline (\n) → E1003 (not consumed as escape)
        // =====================================================================
        r = tokenize("`hello\\\nworld`");
        assertDiagnosticCode(r.diagnostics(), "E1003",
            "backslash+newline in template");
        check(r.tokens().get(0).type() == TokenType.TEMPLATE_LITERAL,
            "template with \\\\n produces TEMPLATE_LITERAL");
        // Lexeme should be content before the backslash
        check(r.tokens().get(0).lexeme().equals("hello"),
            "template lexeme is content before \\\\n (got '" + r.tokens().get(0).lexeme() + "')");
        // Verify 'world' appears after
        boolean hasWorldAfterBackslashN = r.tokens().stream()
            .anyMatch(t -> t.type() == TokenType.IDENTIFIER && t.lexeme().equals("world"));
        check(hasWorldAfterBackslashN,
            "'world' found after template with backslash+newline");

        // =====================================================================
        // Backslash followed by \r → E1003 (not consumed as escape)
        // =====================================================================
        r = tokenize("`hello\\\rworld`");
        assertDiagnosticCode(r.diagnostics(), "E1003",
            "backslash+CR in template");
        check(r.tokens().get(0).lexeme().equals("hello"),
            "template lexeme is content before \\\\r");

        // =====================================================================
        // Backslash followed by \r\n (Windows) → E1003, line correct
        // =====================================================================
        r = tokenize("`hello\\\r\nworld`");
        assertDiagnosticCode(r.diagnostics(), "E1003",
            "backslash+CRLF in template");
        check(r.tokens().get(0).lexeme().equals("hello"),
            "template lexeme is content before \\\\r\\n");
        // Verify 'world' is on line 2
        Token wToken = null;
        for (Token t : r.tokens()) {
            if (t.type() == TokenType.IDENTIFIER && t.lexeme().equals("world")) {
                wToken = t;
                break;
            }
        }
        check(wToken != null,
            "world token found after template with backslash+CRLF");
        check(wToken.line() == 2,
            "world token on line 2 after backslash+CRLF (got " + wToken.line() + ")");

        // =====================================================================
        // After \r\n in template, subsequent tokens report correct line numbers
        // =====================================================================
        r = tokenize("`bad\r\nlet x = 1;");
        assertDiagnosticCode(r.diagnostics(), "E1003", "CRLF template continued");
        // LET should be on line 2
        Token letToken = null;
        for (Token t : r.tokens()) {
            if (t.type() == TokenType.LET) {
                letToken = t;
                break;
            }
        }
        check(letToken != null, "LET found after CRLF template");
        check(letToken.line() == 2,
            "LET on line 2 after CRLF template (got " + letToken.line() + ")");
        check(letToken.column() == 1,
            "LET column is 1 after CRLF template (got " + letToken.column() + ")");

        // =====================================================================
        // Backslash followed by literal newline in source: verify E1003 and
        // that the backslash was NOT consumed as an escape (bump not called
        // for the newline char)
        // =====================================================================
        // This is the key regression for D18 — we must emit E1003 when seeing
        // backslash followed by newline, NOT consume the newline as an escaped char
        r = tokenize("`a\\\nb`");
        assertDiagnosticCode(r.diagnostics(), "E1003",
            "backslash+LF not consumed as escape");
        // The lexeme should be "a" (content before the backslash)
        check(r.tokens().get(0).lexeme().equals("a"),
            "lexeme is 'a' before backslash+LF (got '" + r.tokens().get(0).lexeme() + "')");

        // =====================================================================
        // Template with backslash before non-newline: the backslash is consumed
        // as an escape and the next character is consumed normally
        // =====================================================================
        r = tokenize("`a\\nb`");
        assertNoDiagnostics(r.diagnostics(), "backslash-n escape in template");
        assertToken(r.tokens().get(0), TokenType.TEMPLATE_LITERAL, "a\\nb", 1, 1);

        // =====================================================================
        // Template literal followed by another token on next line
        // =====================================================================
        r = tokenize("`hello`\nlet x = 1;");
        assertNoDiagnostics(r.diagnostics(), "template then newline then code");
        List<Token> tokens = r.tokens();
        assertToken(tokens.get(0), TokenType.TEMPLATE_LITERAL, "hello", 1, 1);
        check(tokens.get(1).type() == TokenType.LET && tokens.get(1).line() == 2,
            "LET on line 2 after template + newline");

        // =====================================================================
        // Backslash at EOF inside template
        // =====================================================================
        r = tokenize("`hello\\");
        assertDiagnosticCode(r.diagnostics(), "E1003",
            "template with backslash at EOF");
        check(r.tokens().get(0).type() == TokenType.TEMPLATE_LITERAL,
            "template with backslash-EOF produces TEMPLATE_LITERAL");
    }

    // =========================================================================
    // Identifier tests
    // =========================================================================

    static void testIdentifiers() {
        System.out.println("-- Identifiers --");

        String[] ids = { "x", "myVar", "_private", "$jq", "camelCase", "PascalCase",
                         "snake_case", "x1", "a0" };

        for (String id : ids) {
            LexResult r = tokenize(id);
            assertNoDiagnostics(r.diagnostics(), "identifier " + id);
            assertToken(r.tokens().get(0), TokenType.IDENTIFIER, id, 1, 1);
        }
    }

    // =========================================================================
    // Complete line test
    // =========================================================================

    static void testCompleteLine() {
        System.out.println("-- Complete Line --");

        LexResult r = tokenize("let x: int = 1 + 2;");
        assertNoDiagnostics(r.diagnostics(), "complete line");
        List<Token> tokens = r.tokens();

        check(tokens.size() == 10, "expected 10 tokens, got " + tokens.size());

        assertToken(tokens.get(0), TokenType.LET,       "let", 1, 1);
        assertToken(tokens.get(1), TokenType.IDENTIFIER, "x",   1, 5);
        assertToken(tokens.get(2), TokenType.COLON,      ":",   1, 6);
        assertToken(tokens.get(3), TokenType.IDENTIFIER, "int", 1, 8);
        assertToken(tokens.get(4), TokenType.EQ_SIGN,    "=",   1, 12);
        assertToken(tokens.get(5), TokenType.INT_LITERAL,"1",   1, 14);
        assertToken(tokens.get(6), TokenType.PLUS,       "+",   1, 16);
        assertToken(tokens.get(7), TokenType.INT_LITERAL,"2",   1, 18);
        assertToken(tokens.get(8), TokenType.SEMICOLON,  ";",   1, 19);
        assertToken(tokens.get(9), TokenType.EOF,        "",    1, 20);

        r = tokenize("print(\"hello\");");
        assertNoDiagnostics(r.diagnostics(), "function call");
        tokens = r.tokens();
        assertToken(tokens.get(0), TokenType.IDENTIFIER, "print", 1, 1);
        assertToken(tokens.get(1), TokenType.LPAREN, "(", 1, 6);
        assertToken(tokens.get(2), TokenType.STRING_LITERAL, "\"hello\"", 1, 7);
        assertToken(tokens.get(3), TokenType.RPAREN, ")", 1, 14);
        assertToken(tokens.get(4), TokenType.SEMICOLON, ";", 1, 15);
    }

    // =========================================================================
    // Line/column tracking tests
    // =========================================================================

    static void testLineColumnTracking() {
        System.out.println("-- Line/Column Tracking --");

        String source = "let a: int = 1\nlet b: int = 2\nlet c: int = 3\n";
        LexResult r = tokenize(source);
        assertNoDiagnostics(r.diagnostics(), "multi-line");

        List<Token> tokens = r.tokens();

        Token let1 = tokens.get(0);
        check(let1.type() == TokenType.LET && let1.line() == 1 && let1.column() == 1,
            "first let at 1:1");

        Token let2 = null;
        for (Token t : tokens) {
            if (t.type() == TokenType.LET && t.line() == 2) {
                let2 = t;
                break;
            }
        }
        check(let2 != null, "found second let");
        check(let2.column() == 1, "second let column 1");

        Token let3 = null;
        for (Token t : tokens) {
            if (t.type() == TokenType.LET && t.line() == 3) {
                let3 = t;
                break;
            }
        }
        check(let3 != null, "found third let");
        check(let3.column() == 1, "third let column 1");

        // Test with CRLF
        source = "x\r\ny\r\nz";
        r = tokenize(source);
        assertNoDiagnostics(r.diagnostics(), "CRLF");
        tokens = r.tokens();
        check(tokens.get(0).line() == 1 && tokens.get(0).lexeme().equals("x"),
            "CRLF x at 1:1");
        check(tokens.get(1).line() == 2 && tokens.get(1).lexeme().equals("y"),
            "CRLF y at 2:1");
        check(tokens.get(2).line() == 3 && tokens.get(2).lexeme().equals("z"),
            "CRLF z at 3:1");

        // Test with string spanning multiple lines (should error)
        source = "\"hello\nworld\"";
        r = tokenize(source);
        check(!r.diagnostics().isEmpty(), "newline in string produces diagnostic");
        assertDiagnosticCode(r.diagnostics(), "E1003", "newline in string");
    }

    // =========================================================================
    // Comment tests
    // =========================================================================

    static void testComments() {
        System.out.println("-- Comments --");

        // Single-line comment
        LexResult r = tokenize("// this is a comment\nlet x = 1;");
        assertNoDiagnostics(r.diagnostics(), "line comment");
        List<Token> tokens = r.tokens();
        boolean hasComment = tokens.stream()
            .anyMatch(t -> t.type().name().contains("COMMENT"));
        check(!hasComment, "no comment tokens in output");

        check(tokens.get(0).type() == TokenType.LET, "first token after comment is LET");
        check(tokens.get(0).line() == 2, "LET is on line 2 after comment");

        // Block comment
        r = tokenize("/* block comment */let x = 1;");
        assertNoDiagnostics(r.diagnostics(), "block comment");
        tokens = r.tokens();
        check(tokens.get(0).type() == TokenType.LET, "first token after block comment is LET");

        // Block comment spanning multiple lines:
        // /* multi\nline\ncomment */let x = 1;
        // Line 1: /* multi
        // Line 2: line
        // Line 3: comment */let x = 1;
        // So LET should be on line 3
        r = tokenize("/* multi\nline\ncomment */let x = 1;");
        assertNoDiagnostics(r.diagnostics(), "multi-line block comment");
        tokens = r.tokens();
        check(tokens.get(0).type() == TokenType.LET,
            "first token after multi-line block comment is LET");
        check(tokens.get(0).line() == 3,
            "LET is on line 3 after multi-line block comment");

        // Comment between tokens
        r = tokenize("1 /* add */ + 2");
        assertNoDiagnostics(r.diagnostics(), "inline block comment");
        tokens = r.tokens();
        assertTokenTypeOnly(tokens.get(0), TokenType.INT_LITERAL, "first token");
        assertTokenTypeOnly(tokens.get(1), TokenType.PLUS, "second token");
        assertTokenTypeOnly(tokens.get(2), TokenType.INT_LITERAL, "third token");
    }

    // =========================================================================
    // Unterminated string tests
    // =========================================================================

    static void testUnterminatedString() {
        System.out.println("-- Unterminated String --");

        LexResult r = tokenize("\"unterminated");
        assertDiagnosticCode(r.diagnostics(), "E1003", "unterminated double-quoted");
        check(r.tokens().get(0).type() == TokenType.STRING_LITERAL,
            "unterminated string still produces STRING_LITERAL token");

        r = tokenize("'unterminated");
        assertDiagnosticCode(r.diagnostics(), "E1003", "unterminated single-quoted");
        check(r.tokens().get(0).type() == TokenType.STRING_LITERAL,
            "unterminated single-quoted string still produces STRING_LITERAL token");

        r = tokenize("\"bad\nlet x = 1;");
        assertDiagnosticCode(r.diagnostics(), "E1003", "unterminated string with continuation");
        boolean hasLet = r.tokens().stream().anyMatch(t -> t.type() == TokenType.LET);
        check(hasLet, "remaining file tokenized after unterminated string");
    }

    // =========================================================================
    // Unrecognized character tests
    // =========================================================================

    static void testUnrecognizedCharacter() {
        System.out.println("-- Unrecognized Character --");

        LexResult r = tokenize("@");
        assertDiagnosticCode(r.diagnostics(), "E1001", "@ unrecognized");
        check(r.tokens().get(0).type() == TokenType.EOF,
            "unrecognized char skipped, only EOF remains");

        r = tokenize("#");
        assertDiagnosticCode(r.diagnostics(), "E1001", "# unrecognized");
        check(r.tokens().get(0).type() == TokenType.EOF,
            "unrecognized char skipped");

        r = tokenize("@let x = 1;");
        assertDiagnosticCode(r.diagnostics(), "E1001", "@ then valid code");
        check(r.tokens().get(0).type() == TokenType.LET,
            "LET token after unrecognized char");
        check(r.tokens().get(1).type() == TokenType.IDENTIFIER,
            "IDENTIFIER after unrecognized char");
    }

    // =========================================================================
    // Malformed number tests
    // =========================================================================

    static void testMalformedNumber() {
        System.out.println("-- Malformed Number --");

        LexResult r = tokenize("1.2e+");
        boolean hasNumDiag = r.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E1002"));
        check(hasNumDiag, "1.2e+ produces E1002 diagnostic");

        r = tokenize("1.5e");
        boolean hasE1002 = r.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E1002"));
        check(hasE1002, "1.5e produces E1002 diagnostic");

        r = tokenize("1.5e\nlet x = 1;");
        boolean hasContinuation = r.tokens().stream()
            .anyMatch(t -> t.type() == TokenType.LET);
        check(hasContinuation, "tokenization continues after malformed number");

        // 1..2 — correct tokenization: INT(1) + DOT + NUMBER(.2)
        r = tokenize("1..2");
        List<Token> tokens = r.tokens();
        check(tokens.get(0).type() == TokenType.INT_LITERAL
                && tokens.get(0).lexeme().equals("1"),
            "1..2: first token is INT 1");
        check(tokens.get(1).type() == TokenType.DOT,
            "1..2: second token is DOT");
        check(tokens.get(2).type() == TokenType.NUMBER_LITERAL
                && tokens.get(2).lexeme().equals(".2"),
            "1..2: third token is NUMBER .2");
    }

    // =========================================================================
    // Unterminated block comment tests
    // =========================================================================

    static void testUnterminatedBlockComment() {
        System.out.println("-- Unterminated Block Comment --");

        LexResult r = tokenize("/* unterminated");
        assertDiagnosticCode(r.diagnostics(), "E1004", "unterminated block comment");
        check(r.tokens().get(0).type() == TokenType.EOF,
            "only EOF after unterminated block comment");
    }

    // =========================================================================
    // EOF token test
    // =========================================================================

    static void testEOFToken() {
        System.out.println("-- EOF Token --");

        LexResult r = tokenize("42");
        List<Token> tokens = r.tokens();
        check(tokens.size() >= 2, "at least 2 tokens (literal + EOF)");
        Token eof = tokens.get(tokens.size() - 1);
        check(eof.type() == TokenType.EOF, "last token is EOF");
        check(eof.lexeme().isEmpty(), "EOF lexeme is empty");
        check(eof.length() == 0, "EOF length is 0");
    }

    // =========================================================================
    // Empty input test
    // =========================================================================

    static void testEmptyInput() {
        System.out.println("-- Empty Input --");

        LexResult r = tokenize("");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 1, "empty input: 1 token (EOF)");
        check(tokens.get(0).type() == TokenType.EOF, "empty input: EOF");
        assertNoDiagnostics(r.diagnostics(), "empty input");
    }

    // =========================================================================
    // Error recovery tests
    // =========================================================================

    static void testErrorRecovery() {
        System.out.println("-- Error Recovery --");

        LexResult r = tokenize("@x = 1;");
        boolean hasIdentifier = r.tokens().stream()
            .anyMatch(t -> t.type() == TokenType.IDENTIFIER && t.lexeme().equals("x"));
        check(hasIdentifier, "identifier 'x' found after unrecognized char");

        r = tokenize("\"bad\nlet y = 2;");
        boolean hasLet = r.tokens().stream()
            .anyMatch(t -> t.type() == TokenType.LET);
        check(hasLet, "LET found after unterminated string + newline");

        r = tokenize("@ # let z = 1;");
        long e1001count = r.diagnostics().stream()
            .filter(d -> d.code().equals("E1001")).count();
        check(e1001count >= 2, "multiple E1001 diagnostics: got " + e1001count);
        boolean hasZ = r.tokens().stream()
            .anyMatch(t -> t.type() == TokenType.IDENTIFIER && t.lexeme().equals("z"));
        check(hasZ, "identifier 'z' found after multiple errors");
    }

    // =========================================================================
    // Token span tests
    // =========================================================================

    static void testTokenSpan() {
        System.out.println("-- Token Span --");

        LexResult r = tokenize("let x = 42;");
        List<Token> tokens = r.tokens();

        assertToken(tokens.get(0), TokenType.LET, "let", 1, 1);
        check(tokens.get(0).length() == 3, "let length is 3");

        assertToken(tokens.get(1), TokenType.IDENTIFIER, "x", 1, 5);
        check(tokens.get(1).length() == 1, "x length is 1");

        assertToken(tokens.get(2), TokenType.EQ_SIGN, "=", 1, 7);
        check(tokens.get(2).length() == 1, "= length is 1");

        assertToken(tokens.get(3), TokenType.INT_LITERAL, "42", 1, 9);
        check(tokens.get(3).length() == 2, "42 length is 2");

        assertToken(tokens.get(4), TokenType.SEMICOLON, ";", 1, 11);
    }

    // =========================================================================
    // All token types appear in comprehensive source
    // =========================================================================

    static void testAllTokenTypesAppear() {
        System.out.println("-- All Token Types Appear --");

        String source = """
            let x: int = 1 + 2;
            let y: number = 3.14;
            let s: string = "hello";
            let b: boolean = true;
            let n: null = null;
            if (x === 1 && y !== 2.0 || !b) {
                return;
            } else {
                let f: boolean = false;
            }
            while (x < 10) {
                x = x + 1;
                if (x >= 5) {
                    break;
                }
                continue;
            }
            for (let i: int = 0; i <= 10; i = i + 1) {
                let z: int = i * 2 / 3 % 5;
                if (z > 0) {
                }
            }
            class Foo {
                name: string;
                age?: int;
            }
            function bar(p: int): int {
                return p ** 2;
            }
            import * as mod from "./module";
            export function baz(): null {
                delete x.field;
                has(x, "field");
                try {
                    throw "error";
                } catch (e) {
                }
            }
            let arr: int[] = [1, 2, 3];
            let opt: int | null = null;
            let obj = { key: "value" };
            let fn: (int) => int = function(): int { return 0; };
            let rest: int = -1;
            """;

        LexResult r = tokenize(source);
        List<Token> tokens = r.tokens();

        java.util.Set<TokenType> seen = new java.util.HashSet<>();
        for (Token t : tokens) {
            seen.add(t.type());
        }

        TokenType[] expected = {
            TokenType.LET, TokenType.CLASS, TokenType.FUNCTION,
            TokenType.RETURN,
            TokenType.IF, TokenType.ELSE, TokenType.WHILE, TokenType.FOR,
            TokenType.BREAK, TokenType.CONTINUE,
            TokenType.NULL, TokenType.TRUE, TokenType.FALSE,
            TokenType.IMPORT, TokenType.EXPORT, TokenType.FROM,
            TokenType.DELETE, TokenType.HAS, TokenType.TRY, TokenType.CATCH,
            TokenType.THROW, TokenType.AS,
            TokenType.INT_LITERAL, TokenType.NUMBER_LITERAL, TokenType.STRING_LITERAL,
            TokenType.IDENTIFIER,
            TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH,
            TokenType.PERCENT, TokenType.STAR_STAR,
            TokenType.EQ_STRICT, TokenType.NEQ_STRICT,
            TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
            TokenType.AND, TokenType.OR, TokenType.PIPE, TokenType.BANG,
            TokenType.EQ_SIGN, TokenType.ARROW, TokenType.DOT,
            TokenType.QUESTION, TokenType.COLON, TokenType.SEMICOLON, TokenType.COMMA,
            TokenType.LBRACE, TokenType.RBRACE, TokenType.LPAREN, TokenType.RPAREN,
            TokenType.LBRACKET, TokenType.RBRACKET,
            TokenType.EOF,
        };

        for (TokenType expectedType : expected) {
            check(seen.contains(expectedType),
                "token type " + expectedType + " appears in comprehensive source");
        }
    }

    // =========================================================================
    // Dot-number edge cases
    // =========================================================================

    static void testDotNumber() {
        System.out.println("-- Dot-Number Edge Cases --");

        LexResult r = tokenize(".5");
        assertNoDiagnostics(r.diagnostics(), ".5");
        assertToken(r.tokens().get(0), TokenType.NUMBER_LITERAL, ".5", 1, 1);

        r = tokenize("1.toString");
        assertNoDiagnostics(r.diagnostics(), "1.toString");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 4, "1.toString: 3 tokens + EOF, got " + tokens.size());
        assertToken(tokens.get(0), TokenType.INT_LITERAL, "1", 1, 1);
        assertToken(tokens.get(1), TokenType.DOT, ".", 1, 2);
        assertToken(tokens.get(2), TokenType.IDENTIFIER, "toString", 1, 3);

        r = tokenize("1.");
        assertNoDiagnostics(r.diagnostics(), "1. at EOF");
        tokens = r.tokens();
        check(tokens.size() == 3, "1. at EOF: 2 tokens + EOF, got " + tokens.size());
        assertToken(tokens.get(0), TokenType.INT_LITERAL, "1", 1, 1);
        assertToken(tokens.get(1), TokenType.DOT, ".", 1, 2);

        r = tokenize(".");
        assertNoDiagnostics(r.diagnostics(), "dot alone");
        assertToken(r.tokens().get(0), TokenType.DOT, ".", 1, 1);
    }

    // =========================================================================
    // Block comment same-line column tracking (regression: Major Flaw 1)
    // =========================================================================

    static void testBlockCommentColumnTracking() {
        System.out.println("-- Block Comment Column Tracking --");

        // /* c */hello — hello starts at column 8 (/*=1-2, ' c '=3-5, */=6-7, h=8)
        LexResult r = tokenize("/* c */hello");
        assertNoDiagnostics(r.diagnostics(), "same-line block comment");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 2, "expected IDENTIFIER + EOF, got " + tokens.size());
        assertToken(tokens.get(0), TokenType.IDENTIFIER, "hello", 1, 8);

        // Test that column is correct after an inline block comment
        r = tokenize("x/*comment*/y");
        assertNoDiagnostics(r.diagnostics(), "inline comment between identifiers");
        tokens = r.tokens();
        check(tokens.size() == 3, "expected x + y + EOF, got " + tokens.size());
        assertToken(tokens.get(0), TokenType.IDENTIFIER, "x", 1, 1);
        assertToken(tokens.get(1), TokenType.IDENTIFIER, "y", 1, 13);
        // x=1, /*comment*/ = positions 2-12, y=14
        // Actually let's compute: x at col 1, then /*comment*/ = columns 2-12 (11 chars), y at col 14
    }

    // =========================================================================
    // Multiple dots in number literal (regression: Major Flaw 2)
    // =========================================================================

    static void testMultipleDotsInNumber() {
        System.out.println("-- Multiple Dots in Number --");

        // 1.2.3 should produce E1002 diagnostic
        LexResult r = tokenize("1.2.3");
        assertDiagnosticCode(r.diagnostics(), "E1002", "1.2.3 multiple dots");
        List<Token> tokens = r.tokens();

        // Should produce NUMBER("1.2") + NUMBER(".3") + EOF (3 tokens total)
        // The first NUMBER token is "1.2" with the diagnostic
        check(tokens.size() >= 3,
            "1.2.3: expected at least 3 tokens, got " + tokens.size());
        check(tokens.get(0).type() == TokenType.NUMBER_LITERAL
                && tokens.get(0).lexeme().equals("1.2"),
            "1.2.3: first token should be NUMBER 1.2");
        check(tokens.get(1).type() == TokenType.NUMBER_LITERAL
                && tokens.get(1).lexeme().equals(".3"),
            "1.2.3: second token should be NUMBER .3");

        // 1..2 should still work without diagnostic
        r = tokenize("1..2");
        assertNoDiagnostics(r.diagnostics(), "1..2 valid");
        tokens = r.tokens();
        check(tokens.get(0).type() == TokenType.INT_LITERAL
                && tokens.get(0).lexeme().equals("1"),
            "1..2: first token INT 1");
        check(tokens.get(1).type() == TokenType.DOT,
            "1..2: second token DOT");
        check(tokens.get(2).type() == TokenType.NUMBER_LITERAL
                && tokens.get(2).lexeme().equals(".2"),
            "1..2: third token NUMBER .2");
    }

    // =========================================================================
    // Block comment non-nesting (regression: Minor Flaw 3)
    // =========================================================================

    static void testBlockCommentNoNesting() {
        System.out.println("-- Block Comment No Nesting --");

        // /* /* */ should terminate at the first */, not nest
        // The source is: /* /* */ trailing — the trailing text should be tokenized
        LexResult r = tokenize("/* /* */ trailing");
        assertNoDiagnostics(r.diagnostics(), "non-nesting block comment");
        List<Token> tokens = r.tokens();
        // After the comment consumes "/* /* */", "trailing" should be an identifier
        check(tokens.size() == 2,
            "expected IDENTIFIER(trailing) + EOF, got " + tokens.size()
            + ": " + tokens);
        assertToken(tokens.get(0), TokenType.IDENTIFIER, "trailing", 1, 10);
    }

    // =========================================================================
    // Line comment column tracking at EOF (regression: Minor Flaw 4)
    // =========================================================================

    static void testLineCommentColumnAtEOF() {
        System.out.println("-- Line Comment Column at EOF --");

        // // comment (no trailing newline) should leave column correct for EOF
        // "// comment" has length 10; after consuming the comment, column should be 11
        LexResult r = tokenize("// comment");
        assertNoDiagnostics(r.diagnostics(), "line comment at EOF");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 1, "expected only EOF, got " + tokens.size());
        Token eof = tokens.get(0);
        check(eof.type() == TokenType.EOF, "last token is EOF");
        // After consuming "// comment" (10 chars), column should be 11 (1-based)
        check(eof.column() == 11,
            "EOF column after line comment: expected 11, got " + eof.column());
        check(eof.line() == 1,
            "EOF line after line comment: expected 1, got " + eof.line());
    }

    // =========================================================================
    // v1.1 keyword changes: new keyword-specific tests
    // =========================================================================

    static void testInTokenizesAsIdentifier() {
        System.out.println("-- 'in' tokenizes as identifier --");

        // "in" is no longer a keyword in v1.1; it must tokenize as IDENTIFIER
        LexResult r = tokenize("in");
        assertNoDiagnostics(r.diagnostics(), "'in' as identifier");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 2, "'in': expected 2 tokens (identifier + EOF)");
        assertToken(tokens.get(0), TokenType.IDENTIFIER, "in", 1, 1);
    }

    static void testOfTokenizesAsKeyword() {
        System.out.println("-- 'of' tokenizes as keyword --");

        LexResult r = tokenize("of");
        assertNoDiagnostics(r.diagnostics(), "'of' as keyword");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 2, "'of': expected 2 tokens (keyword + EOF)");
        assertToken(tokens.get(0), TokenType.OF, "of", 1, 1);
    }

    static void testAsyncTokenizesAsKeyword() {
        System.out.println("-- 'async' tokenizes as keyword --");

        LexResult r = tokenize("async");
        assertNoDiagnostics(r.diagnostics(), "'async' as keyword");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 2, "'async': expected 2 tokens (keyword + EOF)");
        assertToken(tokens.get(0), TokenType.ASYNC, "async", 1, 1);
    }

    static void testAwaitTokenizesAsKeyword() {
        System.out.println("-- 'await' tokenizes as keyword --");

        LexResult r = tokenize("await");
        assertNoDiagnostics(r.diagnostics(), "'await' as keyword");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 2, "'await': expected 2 tokens (keyword + EOF)");
        assertToken(tokens.get(0), TokenType.AWAIT, "await", 1, 1);
    }

    // =========================================================================
    // Directive comment tests (D1)
    // =========================================================================

    static void testDirectiveComments() {
        System.out.println("-- Directive Comments --");

        // --- @jsonable recognized on next token ---
        LexResult r = tokenize("// @jsonable\nexport class C {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable before export class");
        List<Token> tokens = r.tokens();
        Token exportToken = tokens.get(0);
        check(exportToken.type() == TokenType.EXPORT,
            "@jsonable before export: first token is EXPORT, got " + exportToken.type());
        check(exportToken.directives().contains("@jsonable"),
            "EXPORT token has @jsonable directive");
        check(exportToken.directives().size() == 1,
            "EXPORT token has exactly 1 directive, got " + exportToken.directives().size());

        // --- @jsonable followed by blank line ---
        r = tokenize("// @jsonable\n\nexport class D {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable + blank line before export class");
        tokens = r.tokens();
        Token expToken2 = tokens.get(0);
        check(expToken2.type() == TokenType.EXPORT,
            "@jsonable + blank line: first token is EXPORT, got " + expToken2.type());
        check(expToken2.directives().contains("@jsonable"),
            "EXPORT token after blank line has @jsonable directive");

        // --- Ordinary comment produces no directive ---
        r = tokenize("// some comment\nlet x = 1;");
        assertNoDiagnostics(r.diagnostics(), "ordinary comment");
        tokens = r.tokens();
        Token letToken = tokens.get(0);
        check(letToken.type() == TokenType.LET,
            "ordinary comment: first token is LET, got " + letToken.type());
        check(letToken.directives().isEmpty(),
            "LET token has no directives after ordinary comment");

        // --- @jsonable with no space after // ---
        r = tokenize("//@jsonable\nexport class E {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable no space");
        tokens = r.tokens();
        Token expNoSpace = tokens.get(0);
        check(expNoSpace.type() == TokenType.EXPORT,
            "@jsonable no space: first token is EXPORT");
        check(expNoSpace.directives().contains("@jsonable"),
            "EXPORT token has @jsonable directive (no space)");

        // --- Multiple @jsonable comment lines accumulate ---
        r = tokenize("// @jsonable\n// @jsonable\nexport class F {}");
        assertNoDiagnostics(r.diagnostics(), "double @jsonable");
        tokens = r.tokens();
        Token expDouble = tokens.get(0);
        check(expDouble.type() == TokenType.EXPORT,
            "double @jsonable: first token is EXPORT");
        check(expDouble.directives().size() == 2,
            "double @jsonable: 2 directives, got " + expDouble.directives().size());
        check(expDouble.directives().get(0).equals("@jsonable")
                && expDouble.directives().get(1).equals("@jsonable"),
            "both directives are @jsonable");

        // --- @jsonable at end of file with no following token ---
        r = tokenize("// @jsonable");
        assertNoDiagnostics(r.diagnostics(), "@jsonable at EOF");
        tokens = r.tokens();
        // Should only have EOF token, no crash
        check(tokens.size() == 1,
            "@jsonable at EOF: only EOF token, got " + tokens.size());
        check(tokens.get(0).type() == TokenType.EOF,
            "@jsonable at EOF: token is EOF");
        check(tokens.get(0).directives().size() == 1
                && tokens.get(0).directives().get(0).equals("@jsonable"),
            "EOF token carries the trailing @jsonable directive");

        // --- @jsonable with leading whitespace on comment line ---
        r = tokenize("//   @jsonable  \nexport class G {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable with extra whitespace");
        tokens = r.tokens();
        Token expWS = tokens.get(0);
        check(expWS.type() == TokenType.EXPORT,
            "@jsonable extra ws: first token is EXPORT");
        check(expWS.directives().contains("@jsonable"),
            "EXPORT token has @jsonable with extra whitespace");

        // --- @jsonable before non-export keyword gets attached to that keyword ---
        r = tokenize("// @jsonable\nclass C {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable before standalone class");
        tokens = r.tokens();
        Token classToken = tokens.get(0);
        check(classToken.type() == TokenType.CLASS,
            "@jsonable before standalone class: first token is CLASS, got " + classToken.type());
        check(classToken.directives().contains("@jsonable"),
            "CLASS token has @jsonable directive");

        // --- @jsonable does not leak to subsequent tokens ---
        r = tokenize("// @jsonable\nlet x = 1;\nlet y = 2;");
        tokens = r.tokens();
        Token firstLet = tokens.get(0);
        check(firstLet.type() == TokenType.LET && firstLet.lexeme().equals("let"),
            "first non-comment is LET");
        check(firstLet.directives().contains("@jsonable"),
            "first LET gets directive");
        // Find the second LET
        Token secondLet = null;
        for (Token t : tokens) {
            if (t.type() == TokenType.LET && t != firstLet) {
                secondLet = t;
                break;
            }
        }
        check(secondLet != null, "second LET found");
        check(secondLet.directives().isEmpty(),
            "second LET has no directives (directive consumed)");

        // --- @jsonable between other comments still attaches ---
        r = tokenize("// @jsonable\n// another comment\n// @jsonable\nexport class H {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable with other comments between");
        tokens = r.tokens();
        Token expBetween = tokens.get(0);
        check(expBetween.type() == TokenType.EXPORT,
            "@jsonable with intervening comment: first token is EXPORT");
        check(expBetween.directives().size() == 2,
            "@jsonable with intervening comment: 2 directives accumulated, got "
                + expBetween.directives().size());
    }

    // =========================================================================
    // @deal-version directive tests (DEAL v1.2 file directives)
    // =========================================================================

    static void testDealVersionDirectives() {
        System.out.println("-- @deal-version Directive Tests --");

        // --- @deal-version before the first non-comment token attaches ---
        LexResult r = tokenize("// @deal-version 1.2\nexport class C {}");
        assertNoDiagnostics(r.diagnostics(), "@deal-version before first token");
        List<Token> tokens = r.tokens();
        Token exp = tokens.get(0);
        check(exp.type() == TokenType.EXPORT,
            "@deal-version: first token is EXPORT");
        check(exp.directives().size() == 1
                && exp.directives().get(0).equals("@deal-version 1.2"),
            "EXPORT token carries the @deal-version directive");

        // --- @deal-version at end of file with no following token ---
        // spec-v1.2: the directive must occur before the first non-comment
        // token; a file with no non-comment token satisfies that vacuously
        // (Program ::= ImportDeclaration* TopLevelDeclaration* allows an
        // empty module).  The pending directive attaches to the EOF token
        // so the parser still validates its value; no E1052 is emitted.
        r = tokenize("// @deal-version 1.2");
        assertNoDiagnostics(r.diagnostics(), "@deal-version alone at EOF");
        tokens = r.tokens();
        check(tokens.size() == 1,
            "@deal-version alone at EOF: only EOF token, got " + tokens.size());
        check(tokens.get(0).type() == TokenType.EOF,
            "@deal-version alone at EOF: token is EOF");
        check(tokens.get(0).directives().size() == 1
                && tokens.get(0).directives().get(0).equals("@deal-version 1.2"),
            "EOF token carries the trailing @deal-version directive");

        // --- @deal-version after a non-comment token is misplaced ---
        // The placement error is reported exactly once, at the directive
        // site; the pending directive still attaches to the EOF token so
        // the parser can validate its value without a duplicate E1052.
        r = tokenize("class A { x: int = 0; }\n// @deal-version 1.2");
        assertDiagnosticCode(r.diagnostics(), "E1052",
            "@deal-version after a declaration");
        check(r.diagnostics().stream()
                .filter(d -> d.code().equals("E1052")).count() == 1,
            "@deal-version after a declaration: exactly one E1052, got "
                + r.diagnostics().stream()
                    .filter(d -> d.code().equals("E1052")).count());
        tokens = r.tokens();
        Token eof = tokens.get(tokens.size() - 1);
        check(eof.type() == TokenType.EOF,
            "@deal-version after a declaration: last token is EOF");
        check(eof.directives().size() == 1
                && eof.directives().get(0).equals("@deal-version 1.2"),
            "EOF token carries the misplaced @deal-version directive");
    }

    // =========================================================================
    // Scalar offset tracking tests (T3): token/EOF offsets recomputed via an
    // independent ScalarSourceCursor walk over astral, tab, CRLF, and CR
    // fixtures (D2/D3).
    // =========================================================================

    static void testScalarOffsets() {
        System.out.println("-- Scalar Offsets --");

        // Astral character: U+1F600 is two UTF-16 units but one decoded
        // scalar — one column and one scalar offset.
        String src = "let s = \"x\uD83D\uDE00y\";";
        LexResult r = tokenize(src);
        assertNoDiagnostics(r.diagnostics(), "astral string fixture");
        List<Token> tokens = r.tokens();
        check(tokens.size() == 6, "astral fixture: 5 tokens + EOF, got " + tokens.size());
        Token str = tokens.get(3);
        check(str.type() == TokenType.STRING_LITERAL,
            "astral fixture: string token at index 3, got " + str.type());
        // "let s = " = 8 scalars; the lexeme is 5 scalars
        // (quote, x, astral, y, quote).
        check(str.startScalarOffset() == 8,
            "astral: string start offset 8, got " + str.startScalarOffset());
        check(str.scalarLength() == 5,
            "astral: string scalar length 5, got " + str.scalarLength());
        check(str.startScalarOffset()
                == ScalarSourceCursor.scalarCount(src, 0, src.indexOf("\"x")),
            "astral: recomputed string start offset");
        check(str.scalarLength() == ScalarSourceCursor.scalarCount(str.lexeme()),
            "astral: recomputed string scalar length");
        // The astral scalar counts one column: semicolon at column 14.
        check(tokens.get(4).type() == TokenType.SEMICOLON
                && tokens.get(4).column() == 14,
            "astral: semicolon at column 14 (astral = one column), got "
                + tokens.get(4).column());

        // Tab counts one scalar.
        src = "\tx";
        r = tokenize(src);
        Token x = r.tokens().get(0);
        check(x.type() == TokenType.IDENTIFIER && x.startScalarOffset() == 1
                && x.scalarLength() == 1 && x.column() == 2,
            "tab: x at offset 1, column 2");

        // CRLF counts two scalars and one line break.
        src = "a\r\nb";
        r = tokenize(src);
        tokens = r.tokens();
        check(tokens.get(0).startScalarOffset() == 0
                && tokens.get(0).scalarLength() == 1,
            "CRLF: 'a' offsets [0,1)");
        check(tokens.get(1).startScalarOffset() == 3
                && tokens.get(1).scalarLength() == 1,
            "CRLF: 'b' start offset 3 (a + two CRLF scalars), got "
                + tokens.get(1).startScalarOffset());
        check(tokens.get(1).line() == 2 && tokens.get(1).column() == 1,
            "CRLF: 'b' at 2:1");
        Token eof = tokens.get(tokens.size() - 1);
        check(eof.type() == TokenType.EOF
                && eof.startScalarOffset() == 4 && eof.scalarLength() == 0,
            "CRLF: EOF offset 4, zero scalar length, got "
                + eof.startScalarOffset() + "/" + eof.scalarLength());
        check(eof.line() == 2 && eof.column() == 2,
            "CRLF: EOF at 2:2, got " + eof.line() + ":" + eof.column());

        // Lone CR: one scalar, one line break.
        src = "a\rb";
        r = tokenize(src);
        tokens = r.tokens();
        check(tokens.get(1).startScalarOffset() == 2
                && tokens.get(1).line() == 2 && tokens.get(1).column() == 1,
            "CR: 'b' offset 2 at 2:1, got " + tokens.get(1).startScalarOffset());
        eof = tokens.get(tokens.size() - 1);
        check(eof.startScalarOffset() == 3 && eof.scalarLength() == 0
                && eof.line() == 2 && eof.column() == 2,
            "CR: EOF offset 3 at 2:2");

        // Full recomputation via the independent cursor walk over astral,
        // tab, CRLF, CR, multi-line, and comment fixtures.
        assertTokenOffsetsMatchCursorWalk("let s = \"x\uD83D\uDE00y\";");
        assertTokenOffsetsMatchCursorWalk("let\tx = 1;");
        assertTokenOffsetsMatchCursorWalk("let a = 1;\r\nlet b = 2;\rlet c = 3;");
        assertTokenOffsetsMatchCursorWalk("// comment \uD83D\uDE00\nlet x = 1;");
        assertTokenOffsetsMatchCursorWalk("class A {\n    name: string;\n}");
    }

    // =========================================================================
    // Diagnostic range tests (T3): the exact D5 mapping for
    // E1001/E1002/E1003/E1004 and the complete directive comment ranges for
    // E1052/E1053/E1054 (parent D6), including Unicode and CRLF fixtures.
    // =========================================================================

    static void testDiagnosticRanges() {
        System.out.println("-- Diagnostic Ranges --");

        // E1001 = the offending single scalar.
        LexResult r = tokenize("@");
        CompilerDiagnostic d = findDiag(r, "E1001");
        check(d != null, "E1001 present for '@'");
        assertRange(d, "test.deal", 1, 1, 1, 2, 0, 1, "E1001 single scalar");

        // E1001 with an astral scalar: the second diagnostic covers exactly
        // the one supplementary scalar at (1,2) — never two columns.
        r = tokenize("@\uD83D\uDE00");
        List<CompilerDiagnostic> e1001s = r.diagnostics().stream()
            .filter(x -> x.code().equals("E1001")).toList();
        check(e1001s.size() == 2,
            "two E1001 diagnostics for '@<astral>', got " + e1001s.size());
        if (e1001s.size() == 2) {
            assertRange(e1001s.get(0), "test.deal", 1, 1, 1, 2, 0, 1,
                "E1001 '@' of astral fixture");
            assertRange(e1001s.get(1), "test.deal", 1, 2, 1, 3, 1, 2,
                "E1001 astral single scalar");
        }

        // E1002 = token start through the first unconsumed scalar (the
        // second '.' of 1.2.3).
        r = tokenize("1.2.3");
        d = findDiag(r, "E1002");
        check(d != null, "E1002 present for 1.2.3");
        assertRange(d, "test.deal", 1, 1, 1, 4, 0, 3,
            "E1002 token start through first unconsumed scalar");

        // E1002 exponent-without-digits: token start through the first
        // unconsumed scalar after the consumed exponent prefix.
        r = tokenize("1.5e");
        d = findDiag(r, "E1002");
        check(d != null, "E1002 present for 1.5e");
        assertRange(d, "test.deal", 1, 1, 1, 5, 0, 4,
            "E1002 exponent without digits at EOF");

        // E1003 = token start through the first unconsumed scalar (the
        // terminator's first scalar).
        r = tokenize("\"hello\n");
        d = findDiag(r, "E1003");
        check(d != null, "E1003 present for newline");
        assertRange(d, "test.deal", 1, 1, 1, 7, 0, 6,
            "E1003 token start through newline");

        // E1003 with CRLF: the range ends at the '\r' — the terminator's
        // first scalar (CRLF = two scalars, one line break).
        r = tokenize("\"hello\r\n");
        d = findDiag(r, "E1003");
        check(d != null, "E1003 present for CRLF");
        assertRange(d, "test.deal", 1, 1, 1, 7, 0, 6,
            "E1003 CRLF: end at terminator's first scalar");

        // E1003 at EOF.
        r = tokenize("\"hello");
        d = findDiag(r, "E1003");
        check(d != null, "E1003 present at EOF");
        assertRange(d, "test.deal", 1, 1, 1, 7, 0, 6, "E1003 at EOF");

        // E1004 = comment start through EOF.
        r = tokenize("/* unterminated");
        d = findDiag(r, "E1004");
        check(d != null, "E1004 present for unterminated block comment");
        assertRange(d, "test.deal", 1, 1, 1, 16, 0, 15,
            "E1004 comment start through EOF");

        // E1052 = complete directive comment range: first '/' of '//'
        // through the last comment scalar, end = the terminator's first
        // scalar (here EOF).
        r = tokenize("class A {}\n// @deal-version 1.2");
        d = findDiag(r, "E1052");
        check(d != null, "E1052 present after a declaration");
        assertRange(d, "test.deal", 2, 1, 2, 21, 11, 31,
            "E1052 complete comment range");

        // E1052 with CRLF before the comment: the CRLF line break counts
        // two scalars, so the comment starts at offset 12 on line 2.
        r = tokenize("let x = 1;\r\n// @deal-version 1.2");
        d = findDiag(r, "E1052");
        check(d != null, "E1052 present in CRLF fixture");
        assertRange(d, "test.deal", 2, 1, 2, 21, 12, 32,
            "E1052 CRLF comment range");

        // E1052 with a Unicode astral scalar inside the comment: the range
        // is scalar-exact (the astral scalar counts one column/offset).
        r = tokenize("class A {}\n// @deal-version 1.2 \uD83D\uDE00");
        d = findDiag(r, "E1052");
        check(d != null, "E1052 present in Unicode fixture");
        // Comment = 20 ASCII scalars + space + 1 astral scalar = 22.
        assertRange(d, "test.deal", 2, 1, 2, 23, 11, 33,
            "E1052 Unicode comment range");

        // E1053: duplicate @deal-version anchors at the complete range of
        // the second comment.
        r = tokenize("// @deal-version 1.2\n// @deal-version 1.2");
        d = findDiag(r, "E1053");
        check(d != null, "E1053 present for duplicate @deal-version");
        assertRange(d, "test.deal", 2, 1, 2, 21, 21, 41,
            "E1053 second complete comment range");

        // E1054: empty argument anchors at the complete comment range.
        r = tokenize("// @deal-version");
        d = findDiag(r, "E1054");
        check(d != null, "E1054 present for empty argument");
        assertRange(d, "test.deal", 1, 1, 1, 17, 0, 16,
            "E1054 complete comment range");

        // Trailing directive comment: the EOF token keeps its final cursor
        // position offsets through the offset-preserving withDirectives
        // attachment (D3).
        r = tokenize("let x = 1;\n// @jsonable");
        assertNoDiagnostics(r.diagnostics(), "trailing @jsonable fixture");
        List<Token> tokens = r.tokens();
        Token eof = tokens.get(tokens.size() - 1);
        check(eof.type() == TokenType.EOF, "trailing directive: last token EOF");
        check(eof.directives().size() == 1
                && eof.directives().get(0).equals("@jsonable"),
            "EOF carries the trailing @jsonable directive");
        check(eof.line() == 2 && eof.column() == 13,
            "trailing directive: EOF at 2:13, got "
                + eof.line() + ":" + eof.column());
        check(eof.startScalarOffset() == 23 && eof.scalarLength() == 0,
            "trailing directive: EOF offset 23, zero scalar length, got "
                + eof.startScalarOffset() + "/" + eof.scalarLength());

        // Directive-bearing real token keeps makeToken offsets through the
        // offset-preserving withDirectives attachment (D3).
        r = tokenize("// @jsonable\nexport class C {}");
        assertNoDiagnostics(r.diagnostics(), "@jsonable before export fixture");
        tokens = r.tokens();
        Token exportToken = tokens.get(0);
        check(exportToken.type() == TokenType.EXPORT
                && exportToken.directives().contains("@jsonable"),
            "EXPORT carries @jsonable");
        check(exportToken.line() == 2 && exportToken.column() == 1,
            "EXPORT at 2:1, got " + exportToken.line() + ":"
                + exportToken.column());
        check(exportToken.startScalarOffset() == 13
                && exportToken.scalarLength() == 6,
            "EXPORT offsets preserved through withDirectives: start 13, "
                + "length 6, got " + exportToken.startScalarOffset() + "/"
                + exportToken.scalarLength());
    }

}
