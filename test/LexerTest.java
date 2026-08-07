package deal.test;

import deal.ast.TokenType;
import deal.lexer.*;

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

    private static void assertDiagnosticCount(List<Diagnostic> diags,
                                               int expectedCount, String context) {
        check(diags.size() == expectedCount,
            String.format("%s: expected %d diagnostics, got %d: %s",
                context, expectedCount, diags.size(), diags));
    }

    private static void assertNoDiagnostics(List<Diagnostic> diags, String context) {
        assertDiagnosticCount(diags, 0, context);
    }

    private static void assertDiagnosticCode(List<Diagnostic> diags,
                                              String expectedCode, String context) {
        check(diags.stream().anyMatch(d -> d.code().equals(expectedCode)),
            String.format("%s: expected diagnostic %s, got: %s",
                context, expectedCode, diags));
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

        String[] keywords = {
            "let", "class", "function", "return", "if", "else",
            "while", "for", "break", "continue",
            "null", "true", "false",
            "import", "export", "from", "delete", "has",
            "try", "catch", "throw",
            "as", "in"
        };

        TokenType[] types = {
            TokenType.LET, TokenType.CLASS, TokenType.FUNCTION, TokenType.RETURN,
            TokenType.IF, TokenType.ELSE, TokenType.WHILE, TokenType.FOR,
            TokenType.BREAK, TokenType.CONTINUE,
            TokenType.NULL, TokenType.TRUE, TokenType.FALSE,
            TokenType.IMPORT, TokenType.EXPORT, TokenType.FROM, TokenType.DELETE,
            TokenType.HAS, TokenType.TRY, TokenType.CATCH, TokenType.THROW,
            TokenType.AS, TokenType.IN
        };

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
            TokenType.LET, TokenType.CLASS, TokenType.FUNCTION, TokenType.RETURN,
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
}
