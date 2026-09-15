package deal.test;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.*;
import deal.parser.*;
import deal.semantic.ir.SemanticProfile;
import deal.source.ScalarSourceCursor;

import java.util.List;
import java.util.Optional;

/**
 * Comprehensive unit tests for the DEAL parser.
 */
public class ParserTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ParseResult parse(String source) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        // ISSUE-0273: the events-carrying constructor — file-directive
        // evaluation and binding run exactly as in production.
        return new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents()).parse();
    }

    private static ParseResult parseFile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        return new Parser(lex.tokens(), filename,
            lex.directiveEvents()).parse();
    }

    /** Parses under the v1.2 profile-aware constructor (I1). */
    private static ParseResult parseV12(String source) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        return new Parser(lex.tokens(), "test.deal",
            SemanticProfile.DEAL_V1_2_INT32, lex.directiveEvents()).parse();
    }

    /** Parses under the profile-aware constructor with the legacy profile. */
    private static ParseResult parseLegacyProfile(String source) {
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        return new Parser(lex.tokens(), "test.deal",
            SemanticProfile.LEGACY_SAFE_INT, lex.directiveEvents()).parse();
    }

    private static void assertNoParseErrors(ParseResult result, String context) {
        List<CompilerDiagnostic> diags = result.diagnostics();
        if (!diags.isEmpty()) {
            for (CompilerDiagnostic d : diags) {
                System.err.println("  Diagnostic: " + d);
            }
        }
        check(diags.isEmpty(), context + ": expected no parse errors, got " + diags.size());
    }

    private static void assertParseError(ParseResult result, String code, String context) {
        List<CompilerDiagnostic> diags = result.diagnostics();
        boolean found = diags.stream().anyMatch(d -> d.code().equals(code));
        check(found, context + ": expected diagnostic " + code + ", got: " + diags);
    }

    private static void assertStmtCount(ProgramNode prog, int expected, String context) {
        check(prog.statements().size() == expected,
            context + ": expected " + expected + " statements, got " + prog.statements().size());
    }

    private static CompilerDiagnostic diagOf(ParseResult r, String code) {
        return r.diagnostics().stream()
                .filter(d -> d.code().equals(code))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T assertInstance(StatementNode stmt, Class<T> clazz, String context) {
        if (clazz.isInstance(stmt)) {
            passed++;
            return (T) stmt;
        }
        fail(context + ": expected " + clazz.getSimpleName()
             + ", got " + stmt.getClass().getSimpleName());
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T assertExprInstance(ExpressionNode expr, Class<T> clazz, String context) {
        if (clazz.isInstance(expr)) {
            passed++;
            return (T) expr;
        }
        fail(context + ": expected " + clazz.getSimpleName()
             + ", got " + expr.getClass().getSimpleName());
        return null;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Running Parser Tests ===");

        testEmptyInput();
        testVariableDeclaration();
        testFunctionDeclaration();
        testClassDeclaration();
        testReturnStatement();
        testIfStatement();
        testIfElseIf();
        testIfElse();
        testWhileStatement();
        testForStatementVarDecl();
        testForStatementAssign();
        testForStatementEmpty();
        testBreakContinue();
        testImportDeclaration();
        testExportDeclaration();
        testDeleteStatement();
        testTryStatement();
        testThrowStatement();
        testBlock();
        testExpressionStatement();

        // Expressions
        testLiteralExpressions();
        testProfileAwareInt32Literals();
        testIdentifierExpression();
        testBinaryExpressions();
        testUnaryExpressions();
        testCallExpression();
        testMemberAccessExpression();
        testIndexExpression();
        testArrayLiteral();
        testObjectLiteral();
        testFunctionExpression();
        testHasExpression();
        testAssignmentExpression();

        // v1.1: Template literals and for-of
        testTemplateLiteralPlain();
        testTemplateLiteralWithExpr();
        testTemplateLiteralLeadingExpr();
        testForOfStatement();
        testForOfMissingIterable();
        testTemplateInvalidEscape();
        testTemplateEmptyInterpolation();
        testTemplateUnterminatedInterpolation();
        testTemplateNestedBraces();
        testTemplateStringLiteralWithRBrace();
        testTemplateNestedTemplateLiteral();
        testTemplateEscapedBacktick();
        testTemplateMalformedExpression();
        testTemplateInterpolationRangeAnchors();

        // Precedence
        testPrecedenceAddMul();
        testPrecedenceSubAssoc();
        testPrecedenceAndOr();
        testPrecedenceComparison();
        testPrecedenceExponentiation();

        // Complex
        testNestedIf();
        testNestedLoops();
        testClassInFunction();
        testFunctionInClass();
        testFuncExprInCall();

        // Error recovery
        testErrorRecoveryMissingBrace();
        testErrorRecoveryMidFile();
        testErrorRecoveryMultipleErrors();
        testErrorRecoveryAssignmentRhsNull();
        testErrorRecoveryBinaryRhsNull();
        testErrorRecoveryBinaryLhsNull();
        testErrorRecoveryHasExprNull();
        testErrorRecoveryUnaryOperandNull();
        testErrorRecoveryStrayRBrace();
        testReturnFunctionExpr();

        // Span tests
        // Parser warn helpers
        testParserWarn();

        testSpanPositions();

        // Edge cases
        testOnlyComments();
        testMultipleStatements();
        testSemicolons();

        // @jsonable directive tests (ISSUE-0046)
        testJsonableExportClass();
        testJsonableExportFunction();
        testJsonableStandaloneClass();
        testJsonableStandaloneFunction();
        testJsonableLet();
        testJsonableNotPresent();
        testJsonableWarningSeverity();
        testJsonableInvalidPlacementIsJsonableFalse();
        testJsonableEndToEndLexParse();

        testAsyncFunctionDeclaration();
        testAwaitExpression();
        testAwaitNotCall();
        testAsyncFunctionType();
        testAsyncFunctionExprInLet();
        testAsyncTypeAnnotation();
        testExportAsyncFunction();
        testAsyncExprStatement();

        // @deal-version file directive value validation (DEAL v1.2)
        testDealVersionDirectives();

        // ISSUE-0273 directive binding pins (D4/D7/D10)
        testExternCWrongFileKind();
        testExternCDuplicateAndPlacement();
        testCMarkerOutsideExternC();
        testCMarkerCardinality();
        testDeclarationFileJsonableNoEffect();
        testTemplateEmbeddedDirectives();

        // Ranged diagnostic anchors (ISSUE-0223 verification 3)
        testTrailingDirectiveEofAnchor();
        testParserDiagnosticScalarExactness();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // @deal-version file directive value validation (DEAL v1.2)
    // =========================================================================

    static void testDealVersionDirectives() {
        System.out.println("-- @deal-version Directive Value Tests --");

        // A valid 1.2 file directive compiles without diagnostics.
        ParseResult r = parse("// @deal-version 1.2\nexport function probe(): null { return null; }");
        assertNoParseErrors(r, "@deal-version 1.2 accepted");
        check(r.program().fileDirectives().declaredDealVersion() != null
                && r.program().fileDirectives().declaredDealVersion()
                    .equals(new DealVersion(1, 2)),
            "declared version (1,2) recorded");
        check(r.program().fileDirectives().effectiveDealVersion()
                .equals(new DealVersion(1, 2)),
            "effective version (1,2)");
        check(r.program().fileDirectives().dealVersionRange() != null
                && r.program().fileDirectives().dealVersionRange()
                    .startScalarOffset() == 0
                && r.program().fileDirectives().dealVersionRange()
                    .endScalarOffset() == 20,
            "dealVersionRange = the first event's complete comment range (0,20)");

        // Omission: declared absent, effective (1,2).
        r = parse("export function probe(): null { return null; }");
        assertNoParseErrors(r, "omission accepted");
        check(r.program().fileDirectives().declaredDealVersion() == null,
            "declared version absent on omission");
        check(r.program().fileDirectives().effectiveDealVersion()
                .equals(new DealVersion(1, 2)),
            "effective version defaults to (1,2)");

        // Numeric equivalence: 01.02 and 1.02 succeed (D7).
        r = parse("// @deal-version 01.02\nclass A { x: int = 0; }");
        assertNoParseErrors(r, "@deal-version 01.02 accepted");
        check(r.program().fileDirectives().declaredDealVersion() != null
                && r.program().fileDirectives().declaredDealVersion()
                    .equals(new DealVersion(1, 2)),
            "01.02 parses to (1,2)");
        r = parse("// @deal-version 1.02\nclass A { x: int = 0; }");
        assertNoParseErrors(r, "@deal-version 1.02 accepted");

        // Delimiter-free @deal-version1.2 (D2/D7).
        r = parse("// @deal-version1.2\nclass A { x: int = 0; }");
        assertNoParseErrors(r, "delimiter-free @deal-version1.2 accepted");

        // Empty argument is E1045 at the complete comment range.
        r = parse("// @deal-version\nclass A { x: int = 0; }");
        assertParseError(r, "E1045", "@deal-version empty argument rejected");
        CompilerDiagnostic e1045 = diagOf(r, "E1045");
        if (e1045 != null) {
            check(e1045.range().origin() == RangeOrigin.SOURCE
                    && e1045.range().startScalarOffset() == 0
                    && e1045.range().endScalarOffset() == 16
                    && e1045.range().scalarLength() == 16,
                "E1045 empty argument anchors at the complete comment (0,16), got ("
                    + e1045.range().startScalarOffset() + ","
                    + e1045.range().endScalarOffset() + ")");
        }

        // Multi-value argument is E1045.
        r = parse("// @deal-version 1.2 1.3\nclass A { x: int = 0; }");
        assertParseError(r, "E1045", "@deal-version multi-value rejected");

        // Older language versions are rejected: DEAL v1.2 is not
        // source-compatible with earlier versions (E1046).
        r = parse("// @deal-version 1.1\nclass A { x: int = 0; }");
        assertParseError(r, "E1046", "@deal-version 1.1 rejected");

        // E1046 anchors at the complete directive comment range: line 1,
        // column 1, offsets (0,20) — the comment line
        // "// @deal-version 1.1" is 20 scalars.
        CompilerDiagnostic e1046 = diagOf(r, "E1046");
        check(e1046 != null, "E1046 diagnostic present for 1.1");
        if (e1046 != null) {
            check(e1046.range().origin() == RangeOrigin.SOURCE,
                "E1046 range must be SOURCE, got " + e1046.range().origin());
            check(e1046.line() == 1 && e1046.column() == 1,
                "E1046 must anchor at the complete comment (1,1), got ("
                    + e1046.line() + "," + e1046.column() + ")");
            check(e1046.range().startScalarOffset() == 0
                    && e1046.range().endScalarOffset() == 20
                    && e1046.range().scalarLength() == 20,
                "E1046 offsets must be (0,20), got ("
                    + e1046.range().startScalarOffset() + ","
                    + e1046.range().endScalarOffset() + ")");
        }

        // Newer major versions are rejected (E1046).
        r = parse("// @deal-version 2.0\nclass A { x: int = 0; }");
        assertParseError(r, "E1046", "@deal-version 2.0 rejected");

        // Malformed values are E1046.
        for (String bad : new String[]{"1.2.3", "1", "1.", ".2", "abc", "1,2",
                "99999999999999999999.2"}) {
            r = parse("// @deal-version " + bad + "\nclass A { x: int = 0; }");
            assertParseError(r, "E1046",
                "@deal-version '" + bad + "' rejected");
        }

        // A trailing directive with no following declaration still
        // validates its value (E1046 at the complete comment range).
        r = parse("// @deal-version 1.1");
        assertParseError(r, "E1046", "trailing @deal-version 1.1 rejected");
        CompilerDiagnostic trailing = diagOf(r, "E1046");
        if (trailing != null) {
            check(trailing.range().origin() == RangeOrigin.SOURCE
                    && trailing.range().startScalarOffset() == 0
                    && trailing.range().endScalarOffset() == 20,
                "trailing E1046 anchors at the complete comment (0,20), got ("
                    + trailing.range().startScalarOffset() + ","
                    + trailing.range().endScalarOffset() + ")");
        }

        // A trailing valid directive produces no diagnostics.
        r = parse("// @deal-version 1.2");
        assertNoParseErrors(r, "trailing @deal-version 1.2 accepted");

        // Placement after the first non-comment token is E1046.
        r = parse("class A { x: int = 0; }\n// @deal-version 1.2");
        assertParseError(r, "E1046", "post-token @deal-version rejected");

        // Duplicate is E1046 at the duplicate's complete comment range.
        r = parse("// @deal-version 1.2\n// @deal-version 1.2");
        assertParseError(r, "E1046", "duplicate @deal-version rejected");
        CompilerDiagnostic dup = r.diagnostics().stream()
                .filter(d -> d.code().equals("E1046")
                        && d.range().startLine() == 2)
                .findFirst().orElse(null);
        check(dup != null, "duplicate E1046 anchors at the second comment");
        if (dup != null) {
            check(dup.range().startScalarOffset() == 21
                    && dup.range().endScalarOffset() == 41
                    && dup.range().scalarLength() == 20,
                "duplicate E1046 offsets (21,41), got ("
                    + dup.range().startScalarOffset() + ","
                    + dup.range().endScalarOffset() + ")");
        }
    }

    // =========================================================================
    // Ranged diagnostic anchor fixtures (ISSUE-0223 verification 3)
    // =========================================================================

    static void testTrailingDirectiveEofAnchor() {
        System.out.println("-- Trailing directive comment: end-of-input error anchors at the real EOF --");

        // The trailing line is a directive comment, so the directive
        // attaches to the EOF token; an end-of-input parse error must
        // anchor at the real EOF position with SOURCE origin and exact
        // scalar offsets.
        String source = "let x\u0020: int = \n// @jsonable";
        ParseResult r = parse(source);

        CompilerDiagnostic d = r.diagnostics().stream()
                .filter(x -> x.code().equals("E1037"))
                .findFirst().orElse(null);
        check(d != null, "end-of-input E1037 present, got " + r.diagnostics());
        if (d != null) {
            check(d.range().origin() == RangeOrigin.SOURCE,
                "end-of-input error range must be SOURCE, got "
                    + d.range().origin());
            // The EOF token sits at the end of the trailing comment line:
            // line 2, column 13 (1 + the 12 comment scalars).
            check(d.line() == 2 && d.column() == 13,
                "end-of-input error must anchor at the real EOF (2,13), got ("
                    + d.line() + "," + d.column() + ")");
            // The line before the comment is 14 scalars (the trailing
            // "= " included), the line break is 1, and the comment line
            // is 12 — the EOF scalar offset is 27.
            check(ScalarSourceCursor.scalarCount(source) == 27,
                "fixture scalar count must be 27, got "
                    + ScalarSourceCursor.scalarCount(source));
            check(d.range().startScalarOffset() == 27
                    && d.range().endScalarOffset() == 27
                    && d.range().scalarLength() == 0,
                "end-of-input error offsets must be (27,27) with zero length, got ("
                    + d.range().startScalarOffset() + ","
                    + d.range().endScalarOffset() + ")");
        }
    }

    static void testParserDiagnosticScalarExactness() {
        System.out.println("-- Parser diagnostic scalar exactness (independent cursor recomputation) --");

        // The first statement holds an astral scalar and a tab inside a
        // string, so the error in the second statement sits at a
        // non-trivial scalar offset. The trailing statement reaches the
        // E1037 default at the ';' token.
        String source = "let s = \"\uD83D\uDE00\t\";\nlet x\u0020: int = ;";
        ParseResult r = parse(source);

        CompilerDiagnostic d = r.diagnostics().stream()
                .filter(x -> x.code().equals("E1037"))
                .findFirst().orElse(null);
        check(d != null, "E1037 present, got " + r.diagnostics());
        if (d != null) {
            // Independent recomputation: walk a fresh cursor past the
            // first statement's ';' to the second one, then compare
            // every coordinate of the E1037 anchor.
            ScalarSourceCursor c = new ScalarSourceCursor(source);
            while (!c.atEnd() && c.peekScalar() != ';') {
                c.advance();
            }
            check(!c.atEnd(), "cursor must find the first ';' scalar");
            c.advance(); // consume the first statement's ';'
            while (!c.atEnd() && c.peekScalar() != ';') {
                c.advance();
            }
            check(!c.atEnd(), "cursor must find the second ';' scalar");
            check(d.range().origin() == RangeOrigin.SOURCE,
                "E1037 range must be SOURCE, got " + d.range().origin());
            check(d.line() == c.line() && d.column() == c.column(),
                "E1037 start (" + d.line() + "," + d.column()
                    + ") != cursor (" + c.line() + "," + c.column() + ")");
            check(d.range().startScalarOffset() == c.scalarOffset(),
                "E1037 start offset " + d.range().startScalarOffset()
                    + " != cursor offset " + c.scalarOffset());
            check(d.range().scalarLength() == 1,
                "E1037 scalar length must be 1 (';'), got "
                    + d.range().scalarLength());
            check(d.range().endScalarOffset() == c.scalarOffset() + 1,
                "E1037 end offset " + d.range().endScalarOffset()
                    + " != cursor offset + 1");
            check(d.range().endLine() == c.line()
                    && d.range().endColumn() == c.column() + 1,
                "E1037 end (" + d.range().endLine() + "," + d.range().endColumn()
                    + ") != cursor end");
            // The first statement (string with the astral scalar and
            // the tab) is 13 scalars plus the line break; the second
            // statement's prefix ("let x: int = ") is another 14, so
            // the ';' sits at line 2, column 15.
            check(d.range().startLine() == 2 && d.range().startColumn() == 15,
                "E1037 start position must be (2,15), got ("
                    + d.range().startLine() + "," + d.range().startColumn() + ")");
        }
    }


    // =========================================================================
    // Statement form tests
    // =========================================================================

    static void testEmptyInput() {
        System.out.println("-- Empty Input --");

        ParseResult r = parse("");
        assertNoParseErrors(r, "empty input");
        assertStmtCount(r.program(), 0, "empty input");

        r = parse("   ");
        assertNoParseErrors(r, "whitespace only");
        assertStmtCount(r.program(), 0, "whitespace only");

        r = parse("// comment only\n");
        assertNoParseErrors(r, "comment only");
        assertStmtCount(r.program(), 0, "comment only");
    }

    static void testVariableDeclaration() {
        System.out.println("-- VariableDeclaration --");

        ParseResult r = parse("let x: int = 42;");
        assertNoParseErrors(r, "let with type");
        assertStmtCount(r.program(), 1, "let with type");
        VariableDeclaration vd = assertInstance(r.program().statements().get(0),
                VariableDeclaration.class, "let stmt type");
        if (vd != null) {
            check(vd.name().equals("x"), "var name");
            check(vd.typeAnnotation().isPresent(), "has type annotation");
            check(vd.typeAnnotation().get() instanceof NamedType, "type is NamedType");
            check(vd.initializer() instanceof LiteralExpr, "init is literal");
        }

        r = parse("let y = 100");
        assertNoParseErrors(r, "let without type");
        vd = assertInstance(r.program().statements().get(0),
                VariableDeclaration.class, "let no type");
        if (vd != null) {
            check(vd.name().equals("y"), "var name y");
            check(vd.typeAnnotation().isEmpty(), "no type annotation");
            check(vd.initializer() instanceof LiteralExpr, "init is literal");
        }

        r = parse("let from: int = 0;");
        CompilerDiagnostic reserved = diagOf(r, "E1007");
        check(reserved != null, "reserved local name produces E1007");
        if (reserved != null) {
            check(reserved.message().contains("'from' is a reserved keyword"),
                "reserved local diagnostic names the rejected keyword: " + reserved.message());
        }
    }

    static void testFunctionDeclaration() {
        System.out.println("-- FunctionDeclaration --");

        ParseResult r = parse("function foo(x: int): int { return x; }");
        assertNoParseErrors(r, "func decl");
        assertStmtCount(r.program(), 1, "func decl");
        FunctionDeclaration fd = assertInstance(r.program().statements().get(0),
                FunctionDeclaration.class, "func decl");
        if (fd != null) {
            check(fd.name().equals("foo"), "func name");
            check(fd.params().size() == 1, "param count");
            check(fd.params().get(0).name().equals("x"), "param name");
            check(fd.returnType() instanceof NamedType, "return type");
            check(fd.body().statements().size() == 1, "body has 1 stmt");
            check(!fd.isExternal(), "body declaration is not external");
        }

        // DEAL v1.2: rest parameters are rejected with E1047.
        r = parse("function sum(base: int, ...rest: int[]): int { return base; }");
        assertParseError(r, "E1047", "rest parameter rejected with E1047");

        // External declaration (semicolon body) — flagged in the AST so the
        // post-parse validator can reject it outside .d.deal files (E1051).
        r = parse("function ext(): int;");
        assertNoParseErrors(r, "external decl");
        fd = assertInstance(r.program().statements().get(0),
                FunctionDeclaration.class, "external decl");
        if (fd != null) {
            check(fd.body().statements().isEmpty(), "external decl empty body");
            check(fd.isExternal(), "external decl is flagged isExternal");
        }
    }

    static void testClassDeclaration() {
        System.out.println("-- ClassDeclaration --");

        ParseResult r = parse("class Point { x: int; y: int; }");
        assertNoParseErrors(r, "class decl");
        assertStmtCount(r.program(), 1, "class decl");
        ClassDeclaration cd = assertInstance(r.program().statements().get(0),
                ClassDeclaration.class, "class decl");
        if (cd != null) {
            check(cd.name().equals("Point"), "class name");
            check(cd.fields().size() == 2, "field count");
            check(cd.fields().get(0).name().equals("x"), "field 0 name");
            check(!cd.fields().get(0).optional(), "field 0 not optional");
            check(cd.fields().get(1).name().equals("y"), "field 1 name");
        }

        // With optional and default
        r = parse("class User { name: string; age?: int = 0; }");
        assertNoParseErrors(r, "class with optional default");
        cd = assertInstance(r.program().statements().get(0),
                ClassDeclaration.class, "class optional");
        if (cd != null) {
            check(cd.fields().size() == 2, "field count");
            check(cd.fields().get(1).optional(), "field optional");
            check(cd.fields().get(1).defaultExpr().isPresent(), "has default");
        }

        // Nullable field
        r = parse("class Node { parent: Node | null; }");
        assertNoParseErrors(r, "class nullable field");
        cd = assertInstance(r.program().statements().get(0),
                ClassDeclaration.class, "class nullable");
        if (cd != null) {
            check(cd.fields().get(0).nullable(), "field nullable");
        }
    }

    static void testReturnStatement() {
        System.out.println("-- ReturnStatement --");

        ParseResult r = parse("function f(): int { return 42; }");
        assertNoParseErrors(r, "return with expr");
        FunctionDeclaration fd = (FunctionDeclaration) r.program().statements().get(0);
        ReturnStatement ret = assertInstance(fd.body().statements().get(0),
                ReturnStatement.class, "return stmt");
        if (ret != null) {
            check(ret.expr().isPresent(), "return has expr");
            check(ret.expr().get() instanceof LiteralExpr, "return expr is literal");
        }

        r = parse("function f(): null { return; }");
        assertNoParseErrors(r, "return void");
        fd = (FunctionDeclaration) r.program().statements().get(0);
        ret = assertInstance(fd.body().statements().get(0),
                ReturnStatement.class, "return void");
        if (ret != null) {
            check(ret.expr().isEmpty(), "return no expr");
        }
    }

    static void testIfStatement() {
        System.out.println("-- IfStatement --");

        ParseResult r = parse("if (true) { return; }");
        assertNoParseErrors(r, "if simple");
        IfStatement ifStmt = assertInstance(r.program().statements().get(0),
                IfStatement.class, "if stmt");
        if (ifStmt != null) {
            check(ifStmt.condition() instanceof LiteralExpr, "if condition literal");
            check(ifStmt.elseBranch().isEmpty(), "no else branch");
        }
    }

    static void testIfElseIf() {
        System.out.println("-- IfStatement else-if --");

        ParseResult r = parse("if (x) { } else if (y) { }");
        assertNoParseErrors(r, "if else-if");
        IfStatement ifStmt = assertInstance(r.program().statements().get(0),
                IfStatement.class, "if else-if");
        if (ifStmt != null) {
            check(ifStmt.elseBranch().isPresent(), "has else branch");
            Either<IfStatement, Block> eb = ifStmt.elseBranch().get();
            check(eb instanceof Either.Left, "else branch is Left (else-if)");
            if (eb instanceof Either.Left<IfStatement, Block> left) {
                check(left.value() instanceof IfStatement, "Left wraps IfStatement");
            }
        }

        // Full chain: if ... else if ... else if ... else
        r = parse("if (a) { } else if (b) { } else if (c) { } else { }");
        assertNoParseErrors(r, "if else-if chain with else");
    }

    static void testIfElse() {
        System.out.println("-- IfStatement else --");

        ParseResult r = parse("if (x) { } else { }");
        assertNoParseErrors(r, "if else");
        IfStatement ifStmt = assertInstance(r.program().statements().get(0),
                IfStatement.class, "if else");
        if (ifStmt != null) {
            check(ifStmt.elseBranch().isPresent(), "has else branch");
            Either<IfStatement, Block> eb = ifStmt.elseBranch().get();
            check(eb instanceof Either.Right, "else branch is Right (else-block)");
            if (eb instanceof Either.Right<IfStatement, Block> right) {
                check(right.value() instanceof Block, "Right wraps Block");
            }
        }
    }

    static void testWhileStatement() {
        System.out.println("-- WhileStatement --");

        ParseResult r = parse("while (x < 10) { x = x + 1; }");
        assertNoParseErrors(r, "while");
        WhileStatement ws = assertInstance(r.program().statements().get(0),
                WhileStatement.class, "while stmt");
        if (ws != null) {
            check(ws.condition() instanceof BinaryExpr, "while condition binary");
            check(ws.body().statements().size() == 1, "body has 1 stmt");
        }
    }

    static void testForStatementVarDecl() {
        System.out.println("-- ForStatement VarDecl --");

        ParseResult r = parse("for (let i: int = 0; i < 10; i = i + 1) { }");
        assertNoParseErrors(r, "for var decl");
        ForStatement fs = assertInstance(r.program().statements().get(0),
                ForStatement.class, "for stmt");
        if (fs != null) {
            check(fs.init().isPresent(), "has init");
            ForInit init = fs.init().get();
            check(init instanceof ForInit.VarDecl, "init is VarDecl");
            if (init instanceof ForInit.VarDecl vd) {
                check(vd.decl().name().equals("i"), "loop var name");
            }
            check(fs.condition().isPresent(), "has condition");
            check(fs.update().isPresent(), "has update");
        }
    }

    static void testForStatementAssign() {
        System.out.println("-- ForStatement AssignExpr --");

        ParseResult r = parse("for (i = 0; i < 10; i = i + 1) { }");
        assertNoParseErrors(r, "for assign");
        ForStatement fs = assertInstance(r.program().statements().get(0),
                ForStatement.class, "for assign stmt");
        if (fs != null) {
            check(fs.init().isPresent(), "has init");
            ForInit init = fs.init().get();
            check(init instanceof ForInit.AssignExpr, "init is AssignExpr");
        }
    }

    static void testForStatementEmpty() {
        System.out.println("-- ForStatement empty parts --");

        ParseResult r = parse("for (;;) { }");
        assertNoParseErrors(r, "for empty");
        ForStatement fs = assertInstance(r.program().statements().get(0),
                ForStatement.class, "for empty stmt");
        if (fs != null) {
            check(fs.init().isEmpty(), "no init");
            check(fs.condition().isEmpty(), "no condition");
            check(fs.update().isEmpty(), "no update");
        }
    }

    static void testBreakContinue() {
        System.out.println("-- Break / Continue --");

        ParseResult r = parse("while (true) { break; continue; }");
        assertNoParseErrors(r, "break/continue");
        WhileStatement ws = (WhileStatement) r.program().statements().get(0);
        check(ws.body().statements().get(0) instanceof BreakStatement, "break");
        check(ws.body().statements().get(1) instanceof ContinueStatement, "continue");
    }

    static void testImportDeclaration() {
        System.out.println("-- ImportDeclaration --");

        ParseResult r = parse("import * as X from \"./lib\";");
        assertNoParseErrors(r, "import");
        ImportDeclaration imp = assertInstance(r.program().statements().get(0),
                ImportDeclaration.class, "import");
        if (imp != null) {
            check(imp.alias().equals("X"), "alias");
            check(imp.modulePath().equals("./lib"), "path");
        }
    }

    static void testExportDeclaration() {
        System.out.println("-- ExportDeclaration --");

        ParseResult r = parse("export function foo(): int { return 0; }");
        assertNoParseErrors(r, "export function");
        ExportDeclaration exp = assertInstance(r.program().statements().get(0),
                ExportDeclaration.class, "export func");
        if (exp != null) {
            check(exp.declaration() instanceof FunctionDeclaration, "decl is func");
        }

        r = parse("export class Bar { x: int; }");
        assertNoParseErrors(r, "export class");
        exp = assertInstance(r.program().statements().get(0),
                ExportDeclaration.class, "export class");
        if (exp != null) {
            check(exp.declaration() instanceof ClassDeclaration, "decl is class");
        }

        // Export let is invalid
        r = parse("export let x: int = 1;");
        assertParseError(r, "E1024", "export let invalid");
    }

    static void testDeleteStatement() {
        System.out.println("-- DeleteStatement --");

        ParseResult r = parse("delete obj.field;");
        assertNoParseErrors(r, "delete member");
        DeleteStatement ds = assertInstance(r.program().statements().get(0),
                DeleteStatement.class, "delete member");
        if (ds != null) {
            check(ds.target() instanceof MemberAccessExpr, "target is member access");
        }

        r = parse("delete arr[0];");
        assertNoParseErrors(r, "delete index");
        ds = assertInstance(r.program().statements().get(0),
                DeleteStatement.class, "delete index");
        if (ds != null) {
            check(ds.target() instanceof IndexExpr, "target is index");
        }

        // delete on simple identifier should produce error
        r = parse("delete x;");
        assertParseError(r, "E1025", "delete simple id");
    }

    static void testTryStatement() {
        System.out.println("-- TryStatement --");

        ParseResult r = parse("try { f(); } catch (e) { log(e); }");
        assertNoParseErrors(r, "try/catch");
        TryStatement ts = assertInstance(r.program().statements().get(0),
                TryStatement.class, "try stmt");
        if (ts != null) {
            check(ts.catchVar().equals("e"), "catch var name");
            check(ts.tryBlock().statements().size() == 1, "try body");
            check(ts.catchBlock().statements().size() == 1, "catch body");
        }
    }

    static void testThrowStatement() {
        System.out.println("-- ThrowStatement --");

        ParseResult r = parse("throw e;");
        assertNoParseErrors(r, "throw");
        ThrowStatement ts = assertInstance(r.program().statements().get(0),
                ThrowStatement.class, "throw stmt");
        if (ts != null) {
            check(ts.expr() instanceof IdentifierExpr, "throw expr is ident");
        }
    }

    static void testBlock() {
        System.out.println("-- Block --");

        ParseResult r = parse("{ let x: int = 1; let y: int = 2; }");
        assertNoParseErrors(r, "block");
        Block block = assertInstance(r.program().statements().get(0),
                Block.class, "block");
        if (block != null) {
            check(block.statements().size() == 2, "block has 2 stmts");
        }
    }

    static void testExpressionStatement() {
        System.out.println("-- ExpressionStatement --");

        ParseResult r = parse("f();");
        assertNoParseErrors(r, "expression stmt");
        ExpressionStatement es = assertInstance(r.program().statements().get(0),
                ExpressionStatement.class, "expr stmt");
        if (es != null) {
            check(es.expr() instanceof CallExpr, "expr is call");
        }
    }

    // =========================================================================
    // Expression form tests
    // =========================================================================

    static void testLiteralExpressions() {
        System.out.println("-- Literal Expressions --");

        ParseResult r = parse("let a: null = null;");
        assertNoParseErrors(r, "null literal");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        LiteralExpr lit = (LiteralExpr) vd.initializer();
        check(lit.value() instanceof LiteralValue.NullLiteral, "null lit");

        r = parse("let a: boolean = true;");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = (LiteralExpr) vd.initializer();
        check(((LiteralValue.BooleanLiteral) lit.value()).value() == true, "true lit");

        r = parse("let a: boolean = false;");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = (LiteralExpr) vd.initializer();
        check(((LiteralValue.BooleanLiteral) lit.value()).value() == false, "false lit");

        r = parse("let a: int = 42;");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = (LiteralExpr) vd.initializer();
        check(((LiteralValue.IntLiteral) lit.value()).value() == 42, "int lit");

        r = parse("let a: number = 3.14;");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = (LiteralExpr) vd.initializer();
        check(((LiteralValue.NumberLiteral) lit.value()).value() == 3.14, "number lit");

        r = parse("let a: string = \"hello\";");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = (LiteralExpr) vd.initializer();
        check(((LiteralValue.StringLiteral) lit.value()).value().equals("hello"), "string lit");
    }

    // =========================================================================
    // Profile-aware int32 literal parsing (signed-int32 foundation I1)
    // =========================================================================

    static void testProfileAwareInt32Literals() {
        System.out.println("-- Profile-Aware Int32 Literals (I1) --");

        // ---- v1.2 matrix ----

        // 2147483647 (the signed32 maximum) parses unchanged.
        ParseResult r = parseV12("let a: int = 2147483647;");
        assertNoParseErrors(r, "v1.2 max literal");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        LiteralExpr lit = assertExprInstance(vd.initializer(), LiteralExpr.class,
            "v1.2 max literal");
        if (lit != null) {
            check(((LiteralValue.IntLiteral) lit.value()).value() == 2147483647L,
                "v1.2 max literal value");
        }

        // -2147483648: the combined-token in-range literal with the
        // combined span and no diagnostic.
        r = parseV12("let a: int = -2147483648;");
        assertNoParseErrors(r, "v1.2 min literal via immediate minus");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = assertExprInstance(vd.initializer(), LiteralExpr.class,
            "v1.2 -2147483648 is a single literal, not a unary expr");
        if (lit != null) {
            check(((LiteralValue.IntLiteral) lit.value()).value() == -2147483648L,
                "v1.2 -2147483648 value");
            Span sp = lit.span();
            check(sp.startColumn() == 14 && sp.endColumn() == 24,
                "v1.2 -2147483648 combined span covers '-' (col 14) through "
                    + "the literal end (col 24): got " + sp.startColumn() + ".."
                    + sp.endColumn());
            check(sp.hasScalarOffsets() && sp.startScalarOffset() == 13
                    && sp.endScalarOffset() == 24,
                "v1.2 -2147483648 combined span scalar offsets [13,24): got ["
                    + sp.startScalarOffset() + "," + sp.endScalarOffset() + ")");
        }

        // 2147483648 alone -> exactly one E1036 at the token with the
        // existing template; parsing recovers with value 0.
        r = parseV12("let a: int = 2147483648;");
        List<CompilerDiagnostic> diags = r.diagnostics();
        check(diags.size() == 1 && "E1036".equals(diags.get(0).code())
                && "error".equals(diags.get(0).severity())
                && "Integer literal out of range: 2147483648".equals(diags.get(0).message()),
            "v1.2 bare 2147483648 raises exactly one E1036 at the token: " + diags);
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = assertExprInstance(vd.initializer(), LiteralExpr.class,
            "v1.2 bare 2147483648 recovers");
        if (lit != null) {
            check(((LiteralValue.IntLiteral) lit.value()).value() == 0,
                "v1.2 bare 2147483648 recovers with value 0");
        }

        // -(2147483648): the parenthesized literal reaches parsePrimary
        // outside the immediate position -> E1036.
        r = parseV12("let a: int = -(2147483648);");
        assertParseError(r, "E1036", "v1.2 parenthesized 2147483648");

        // -2147483649 -> E1036.
        r = parseV12("let a: int = -2147483649;");
        assertParseError(r, "E1036", "v1.2 -2147483649");

        // 2147483648 + 0 -> E1036.
        r = parseV12("let a: int = 2147483648 + 0;");
        assertParseError(r, "E1036", "v1.2 2147483648 + 0");

        // Binary-position 2147483648 is never the immediate-minus special
        // case: the binary '-' is consumed as the operator and the
        // literal reaches parsePrimary -> E1036.
        r = parseV12("let a: int = 1 - 2147483648;");
        assertParseError(r, "E1036", "v1.2 binary-position 2147483648");

        // --2147483648 parses as UnaryExpr(NEG, IntLiteral(-2147483648)):
        // the inner '-' is the immediate token, and the outer negation's
        // E8004 is a runtime concern, not a parse error.
        r = parseV12("let a: int = --2147483648;");
        assertNoParseErrors(r, "v1.2 --2147483648 parses");
        vd = (VariableDeclaration) r.program().statements().get(0);
        UnaryExpr un = assertExprInstance(vd.initializer(), UnaryExpr.class,
            "v1.2 --2147483648 outer unary");
        if (un != null) {
            check(un.op() == UnaryOp.NEG, "v1.2 --2147483648 outer op is NEG");
            LiteralExpr inner = assertExprInstance(un.expr(), LiteralExpr.class,
                "v1.2 --2147483648 inner literal");
            if (inner != null) {
                check(((LiteralValue.IntLiteral) inner.value()).value() == -2147483648L,
                    "v1.2 --2147483648 inner value");
            }
        }

        // The v1.2 contract also applies inside template interpolations:
        // the template sub-parser carries the same profile (I1).
        r = parseV12("let a: string = `x ${2147483648} y`;");
        assertParseError(r, "E1036", "v1.2 template interpolation 2147483648");
        r = parseV12("let a: string = `x ${-2147483648} y`;");
        assertNoParseErrors(r, "v1.2 template interpolation -2147483648");
        vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = assertExprInstance(vd.initializer(),
            TemplateLiteralExpr.class, "v1.2 template");
        if (tl != null && tl.parts().size() == 3) {
            LiteralExpr part = assertExprInstance(tl.parts().get(1),
                LiteralExpr.class,
                "v1.2 template interpolation part is the combined literal");
            if (part != null) {
                check(((LiteralValue.IntLiteral) part.value()).value()
                        == -2147483648L,
                    "v1.2 template interpolation -2147483648 value");
            }
        }

        // ---- legacy matrix ----

        // Legacy values beyond the int32 range parse under both
        // constructors exactly as today.
        for (String legacyValue : new String[] {"9007199254740991",
                                                 "9223372036854775807"}) {
            String srcText = "let a: int = " + legacyValue + ";";
            ParseResult legacyDefault = parse(srcText);
            ParseResult legacyProfile = parseLegacyProfile(srcText);
            assertNoParseErrors(legacyDefault,
                "legacy constructor accepts " + legacyValue);
            assertNoParseErrors(legacyProfile,
                "profile-aware LEGACY_SAFE_INT accepts " + legacyValue);
            check(legacyDefault.diagnostics().equals(legacyProfile.diagnostics()),
                "legacy diagnostics identical across constructors for " + legacyValue);
        }

        // The legacy-default constructor's diagnostics and recovery are
        // byte-identical under the profile-aware LEGACY_SAFE_INT
        // constructor: values beyond Long.parseLong raise the same single
        // diagnostic.
        String huge = "let a: int = 9999999999999999999999;";
        ParseResult legacyDefault = parse(huge);
        ParseResult legacyProfile = parseLegacyProfile(huge);
        check(legacyDefault.diagnostics().equals(legacyProfile.diagnostics()),
            "out-of-long diagnostics identical across constructors: "
                + legacyDefault.diagnostics() + " vs " + legacyProfile.diagnostics());
        assertParseError(legacyProfile, "E1036", "profile-aware legacy out-of-long");

        // Under LEGACY_SAFE_INT the immediate-minus special case does not
        // apply: -2147483648 keeps the historical UnaryExpr(NEG,
        // IntLiteral(2147483648)) shape.
        r = parseLegacyProfile("let a: int = -2147483648;");
        assertNoParseErrors(r, "legacy -2147483648 parses");
        vd = (VariableDeclaration) r.program().statements().get(0);
        un = assertExprInstance(vd.initializer(), UnaryExpr.class,
            "legacy -2147483648 stays a unary expr");
        if (un != null) {
            check(un.op() == UnaryOp.NEG, "legacy -2147483648 unary op is NEG");
            LiteralExpr inner = assertExprInstance(un.expr(), LiteralExpr.class,
                "legacy -2147483648 operand literal");
            if (inner != null) {
                check(((LiteralValue.IntLiteral) inner.value()).value() == 2147483648L,
                    "legacy -2147483648 operand keeps value 2147483648");
            }
        }

        // 2147483648 parses cleanly under the legacy profile (no gate).
        r = parseLegacyProfile("let a: int = 2147483648;");
        assertNoParseErrors(r, "legacy profile accepts 2147483648");

        // Template interpolations keep the historical legacy contract too.
        r = parseLegacyProfile("let a: string = `x ${2147483648} y`;");
        assertNoParseErrors(r, "legacy template interpolation 2147483648");
        r = parseLegacyProfile("let a: string = `x ${-2147483648} y`;");
        assertNoParseErrors(r, "legacy template interpolation -2147483648");
        vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr legacyTl = assertExprInstance(vd.initializer(),
            TemplateLiteralExpr.class, "legacy template");
        if (legacyTl != null && legacyTl.parts().size() == 3) {
            UnaryExpr legacyUn = assertExprInstance(legacyTl.parts().get(1),
                UnaryExpr.class,
                "legacy template interpolation -2147483648 stays a unary expr");
            if (legacyUn != null) {
                check(legacyUn.op() == UnaryOp.NEG,
                    "legacy template interpolation -2147483648 unary op is NEG");
            }
        }
    }

    static void testIdentifierExpression() {
        System.out.println("-- Identifier Expression --");

        ParseResult r = parse("let y: int = x;");
        assertNoParseErrors(r, "identifier expr");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        IdentifierExpr id = assertExprInstance(vd.initializer(), IdentifierExpr.class, "ident");
        if (id != null) check(id.name().equals("x"), "ident name");
    }

    static void testBinaryExpressions() {
        System.out.println("-- Binary Expressions --");

        String[][] tests = {
            {"1 + 2", "ADD"},
            {"1 - 2", "SUB"},
            {"1 * 2", "MUL"},
            {"1 / 2", "DIV"},
            {"1 % 2", "MOD"},
            {"1 ** 2", "POW"},
            {"1 === 2", "EQ"},
            {"1 !== 2", "NEQ"},
            {"1 < 2", "LT"},
            {"1 <= 2", "LTE"},
            {"1 > 2", "GT"},
            {"1 >= 2", "GTE"},
            {"true && false", "AND"},
            {"true || false", "OR"},
        };

        for (String[] test : tests) {
            ParseResult r = parse("let x: boolean = " + test[0] + ";");
            assertNoParseErrors(r, "binary " + test[1]);
            VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
            BinaryExpr bin = assertExprInstance(vd.initializer(), BinaryExpr.class,
                    "binary " + test[1]);
            if (bin != null) check(bin.op().name().equals(test[1]), "op is " + test[1]);
        }
    }

    static void testUnaryExpressions() {
        System.out.println("-- Unary Expressions --");

        ParseResult r = parse("let a: boolean = !true;");
        assertNoParseErrors(r, "unary not");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        UnaryExpr un = assertExprInstance(vd.initializer(), UnaryExpr.class, "unary NOT");
        if (un != null) check(un.op() == UnaryOp.NOT, "NOT op");

        r = parse("let a: int = -5;");
        assertNoParseErrors(r, "unary neg");
        vd = (VariableDeclaration) r.program().statements().get(0);
        un = assertExprInstance(vd.initializer(), UnaryExpr.class, "unary NEG");
        if (un != null) check(un.op() == UnaryOp.NEG, "NEG op");
    }

    static void testCallExpression() {
        System.out.println("-- Call Expression --");

        ParseResult r = parse("f(1, 2, 3);");
        assertNoParseErrors(r, "call");
        ExpressionStatement es = (ExpressionStatement) r.program().statements().get(0);
        CallExpr call = assertExprInstance(es.expr(), CallExpr.class, "call");
        if (call != null) {
            check(call.args().size() == 3, "3 args");
            check(call.callee() instanceof IdentifierExpr, "callee is ident");
        }

        r = parse("f();");
        assertNoParseErrors(r, "call no args");
        es = (ExpressionStatement) r.program().statements().get(0);
        call = assertExprInstance(es.expr(), CallExpr.class, "call no args");
        if (call != null) check(call.args().isEmpty(), "0 args");

        // Trailing comma
        r = parse("f(1,);");
        assertNoParseErrors(r, "call trailing comma");
        es = (ExpressionStatement) r.program().statements().get(0);
        call = assertExprInstance(es.expr(), CallExpr.class, "call trailing comma");
        if (call != null) check(call.args().size() == 1, "1 arg with trailing comma");
    }

    static void testMemberAccessExpression() {
        System.out.println("-- Member Access --");

        ParseResult r = parse("obj.field;");
        assertNoParseErrors(r, "member access");
        ExpressionStatement es = (ExpressionStatement) r.program().statements().get(0);
        MemberAccessExpr ma = assertExprInstance(es.expr(), MemberAccessExpr.class, "member");
        if (ma != null) {
            check(ma.field().equals("field"), "field name");
            check(ma.object() instanceof IdentifierExpr, "object is ident");
        }
    }

    static void testIndexExpression() {
        System.out.println("-- Index Expression --");

        ParseResult r = parse("arr[0];");
        assertNoParseErrors(r, "index");
        ExpressionStatement es = (ExpressionStatement) r.program().statements().get(0);
        IndexExpr ix = assertExprInstance(es.expr(), IndexExpr.class, "index");
        if (ix != null) {
            check(ix.array() instanceof IdentifierExpr, "array is ident");
            check(ix.index() instanceof LiteralExpr, "index is literal");
        }
    }

    static void testArrayLiteral() {
        System.out.println("-- Array Literal --");

        ParseResult r = parse("let a: int[] = [1, 2, 3];");
        assertNoParseErrors(r, "array literal");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        ArrayLiteralExpr arr = assertExprInstance(vd.initializer(), ArrayLiteralExpr.class, "array");
        if (arr != null) check(arr.elements().size() == 3, "3 elements");

        r = parse("let a: int[] = [];");
        assertNoParseErrors(r, "empty array");
        vd = (VariableDeclaration) r.program().statements().get(0);
        arr = assertExprInstance(vd.initializer(), ArrayLiteralExpr.class, "empty array");
        if (arr != null) check(arr.elements().isEmpty(), "0 elements");

        // Trailing comma
        r = parse("let a: int[] = [1,];");
        assertNoParseErrors(r, "array trailing comma");
        vd = (VariableDeclaration) r.program().statements().get(0);
        arr = assertExprInstance(vd.initializer(), ArrayLiteralExpr.class,
                "array trailing comma");
        if (arr != null) check(arr.elements().size() == 1, "1 element trailing comma");
    }

    static void testObjectLiteral() {
        System.out.println("-- Object Literal --");

        ParseResult r = parse("let t: table = { name: \"A\", age: 30 };");
        assertNoParseErrors(r, "object literal");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        ObjectLiteralExpr obj = assertExprInstance(vd.initializer(), ObjectLiteralExpr.class,
                "object");
        if (obj != null) {
            check(obj.properties().size() == 2, "2 properties");
            check(obj.properties().get(0).name().equals("name"), "prop 0 name");
            check(obj.properties().get(1).name().equals("age"), "prop 1 name");
        }

        r = parse("let t: table = {};");
        assertNoParseErrors(r, "empty object");
        vd = (VariableDeclaration) r.program().statements().get(0);
        obj = assertExprInstance(vd.initializer(), ObjectLiteralExpr.class, "empty object");
        if (obj != null) check(obj.properties().isEmpty(), "0 properties");
    }

    static void testFunctionExpression() {
        System.out.println("-- Function Expression --");

        ParseResult r = parse("let f = function(x: int): int { return x; };");
        assertNoParseErrors(r, "func expr");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        FunctionExpr fe = assertExprInstance(vd.initializer(), FunctionExpr.class, "func expr");
        if (fe != null) {
            check(fe.params().size() == 1, "1 param");
            check(fe.returnType() instanceof NamedType, "return type");
            check(fe.body().statements().size() == 1, "1 body stmt");
        }

        // Function expression as expression statement
        r = parse("(function(): int { return 0; });");
        assertNoParseErrors(r, "func expr as stmt");
    }

    static void testHasExpression() {
        System.out.println("-- Has Expression --");

        ParseResult r = parse("let b: boolean = has(obj.field);");
        assertNoParseErrors(r, "has expr");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        HasExpr has = assertExprInstance(vd.initializer(), HasExpr.class, "has");
        if (has != null) {
            check(has.field().equals("field"), "field name");
            check(has.object() instanceof IdentifierExpr, "object");
        }

        // Nested: !has(obj.field)
        r = parse("let b: boolean = !has(obj.field);");
        assertNoParseErrors(r, "!has");
        vd = (VariableDeclaration) r.program().statements().get(0);
        UnaryExpr un = assertExprInstance(vd.initializer(), UnaryExpr.class, "!has");
        if (un != null) {
            check(un.op() == UnaryOp.NOT, "NOT op");
            check(un.expr() instanceof HasExpr, "inner is has");
        }
    }

    static void testAssignmentExpression() {
        System.out.println("-- Assignment Expression --");

        ParseResult r = parse("x = 5;");
        assertNoParseErrors(r, "assignment");
        ExpressionStatement es = (ExpressionStatement) r.program().statements().get(0);
        AssignmentExpr assign = assertExprInstance(es.expr(), AssignmentExpr.class, "assign");
        if (assign != null) {
            check(assign.target() instanceof IdentifierExpr, "target ident");
            check(assign.value() instanceof LiteralExpr, "value literal");
        }

        // Chained assignment (right-associative)
        r = parse("x = y = 5;");
        assertNoParseErrors(r, "chained assign");
        es = (ExpressionStatement) r.program().statements().get(0);
        assign = assertExprInstance(es.expr(), AssignmentExpr.class, "chained assign");
        if (assign != null) {
            check(assign.target() instanceof IdentifierExpr, "target");
            check(assign.value() instanceof AssignmentExpr, "value is nested assign");
        }
    }

    // =========================================================================
    // Precedence tests
    // =========================================================================

    static void testPrecedenceAddMul() {
        System.out.println("-- Precedence: add/mul --");

        ParseResult r = parse("let a: int = 1 + 2 * 3;");
        assertNoParseErrors(r, "add/mul");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        BinaryExpr bin = (BinaryExpr) vd.initializer();
        check(bin.op() == BinaryOp.ADD, "top op is ADD");
        check(bin.right() instanceof BinaryExpr, "right is nested");
        BinaryExpr right = (BinaryExpr) bin.right();
        check(right.op() == BinaryOp.MUL, "nested op is MUL");
    }

    static void testPrecedenceSubAssoc() {
        System.out.println("-- Precedence: associativity --");

        ParseResult r = parse("let a: int = 1 - 2 - 3;");
        assertNoParseErrors(r, "sub assoc");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        BinaryExpr bin = (BinaryExpr) vd.initializer();
        // Left-associative: (1 - 2) - 3
        check(bin.op() == BinaryOp.SUB, "top op is SUB");
        check(bin.right() instanceof LiteralExpr, "right is literal 3");
        check(((LiteralExpr) bin.right()).value() instanceof LiteralValue.IntLiteral il
                && il.value() == 3, "right value is 3");
        check(bin.left() instanceof BinaryExpr, "left is nested");
        BinaryExpr left = (BinaryExpr) bin.left();
        check(left.op() == BinaryOp.SUB, "nested op is SUB");
    }

    static void testPrecedenceAndOr() {
        System.out.println("-- Precedence: and/or --");

        ParseResult r = parse("let a: boolean = x || y && z;");
        assertNoParseErrors(r, "and/or");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        BinaryExpr bin = (BinaryExpr) vd.initializer();
        check(bin.op() == BinaryOp.OR, "top is OR");
        check(bin.right() instanceof BinaryExpr, "right is nested AND");
        BinaryExpr right = (BinaryExpr) bin.right();
        check(right.op() == BinaryOp.AND, "nested is AND");
    }

    static void testPrecedenceComparison() {
        System.out.println("-- Precedence: comparison --");

        // Relational binds tighter than equality: a === b < c → a === (b < c)
        ParseResult r = parse("let a: boolean = x === y < z;");
        assertNoParseErrors(r, "eq vs rel");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        BinaryExpr bin = (BinaryExpr) vd.initializer();
        check(bin.op() == BinaryOp.EQ, "top is EQ");
        check(bin.right() instanceof BinaryExpr, "right is nested LT");
    }

    static void testPrecedenceExponentiation() {
        System.out.println("-- Precedence: exponentiation --");

        // ** is right-associative: 2 ** 3 ** 2 → 2 ** (3 ** 2)
        ParseResult r = parse("let a: int = 2 ** 3 ** 2;");
        assertNoParseErrors(r, "exp right assoc");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        BinaryExpr bin = (BinaryExpr) vd.initializer();
        check(bin.op() == BinaryOp.POW, "top is POW");
        check(bin.left() instanceof LiteralExpr, "left is literal 2");
        check(bin.right() instanceof BinaryExpr, "right is nested POW");
    }

    // =========================================================================
    // Nested constructs
    // =========================================================================

    static void testNestedIf() {
        System.out.println("-- Nested If --");

        ParseResult r = parse("if (a) { if (b) { return; } }");
        assertNoParseErrors(r, "nested if");
        IfStatement outer = (IfStatement) r.program().statements().get(0);
        check(outer.thenBlock().statements().get(0) instanceof IfStatement, "inner if");
    }

    static void testNestedLoops() {
        System.out.println("-- Nested Loops --");

        ParseResult r = parse("while (a) { for (;;) { break; } }");
        assertNoParseErrors(r, "nested loops");
    }

    static void testClassInFunction() {
        System.out.println("-- Class in Function --");

        ParseResult r = parse("function f(): null { class Inner { x: int; } return; }");
        assertNoParseErrors(r, "class in function");
    }

    static void testFunctionInClass() {
        System.out.println("-- Function in Class --");

        // Functions aren't class members in DEAL v0.6, but a class body can
        // contain function declarations as statements inside methods.
        // Actually, class fields are only field declarations. Functions in classes
        // aren't a thing yet. Let's just test that parsing doesn't crash.
        // Instead, test a function declaration inside a function body.
        ParseResult r = parse("function outer(): null { function inner(): int { return 0; } return; }");
        assertNoParseErrors(r, "function in function");
    }

    static void testFuncExprInCall() {
        System.out.println("-- Function Expression in Call --");

        ParseResult r = parse("f(function(x: int): int { return x; });");
        assertNoParseErrors(r, "func expr in call");
        ExpressionStatement es = (ExpressionStatement) r.program().statements().get(0);
        CallExpr call = (CallExpr) es.expr();
        check(call.args().get(0) instanceof FunctionExpr, "arg is func expr");
    }

    // =========================================================================
    // Error recovery tests
    // =========================================================================

    static void testErrorRecoveryMissingBrace() {
        System.out.println("-- Error Recovery: missing brace --");

        ParseResult r = parse("function f(): int { return 0; \nlet x: int = 1;");
        // Missing closing brace should produce E1006
        boolean hasE1006 = r.diagnostics().stream().anyMatch(d -> d.code().equals("E1006"));
        check(hasE1006, "error recovery: has E1006");
        // Should still parse the 'let' statement after recovery
        check(r.program().statements().size() >= 1, "at least one statement after error");
    }

    static void testErrorRecoveryMidFile() {
        System.out.println("-- Error Recovery: mid-file --");

        ParseResult r = parse("let a: int = 1;\nlet b: int =\nlet c: int = 3;");
        // let b: int = (missing expression) should produce an error
        // but let c should still be parsed
        boolean hasError = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasError, "error recovery mid-file: has E1xxx");
        // Should still parse let a and let c
        check(r.program().statements().size() >= 2,
            "error recovery mid-file: at least 2 statements, got "
            + r.program().statements().size());
    }

    static void testErrorRecoveryMultipleErrors() {
        System.out.println("-- Error Recovery: multiple errors --");

        ParseResult r = parse(
            "let a: int = 1;\n" +
            "let b: int = ;\n" +     // error: missing initializer
            "let c: int = 3;\n" +
            "let d: int = ;\n" +     // error: missing initializer
            "let e: int = 5;");

        long errorCount = r.diagnostics().stream()
                .filter(d -> d.code().startsWith("E1")).count();
        check(errorCount >= 2, "error recovery: at least 2 errors, got " + errorCount);
        check(r.program().statements().size() >= 3,
            "error recovery: at least 3 valid statements, got "
            + r.program().statements().size());
    }

    // =========================================================================
    // Null-safety error recovery tests (regression for Flaws 1-4)
    // =========================================================================

    static void testErrorRecoveryAssignmentRhsNull() {
        System.out.println("-- Error Recovery: assignment RHS null (Flaw 1 fix) --");

        // x = ;  —  assignment with missing RHS
        ParseResult r = parse("x = ;\nlet y: int = 1;");
        // Must not crash; errors must be reported
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "flaw1: produced E1xxx diagnostic");
        // Should still parse 'let y'
        check(r.program().statements().size() >= 1,
            "flaw1: at least 1 statement recovered, got " + r.program().statements().size());
    }

    static void testErrorRecoveryBinaryRhsNull() {
        System.out.println("-- Error Recovery: binary RHS null (Flaw 2 fix) --");

        // 1 + ;  —  binary operator with missing RHS
        ParseResult r = parse("let a: int = 1 + ;\nlet b: int = 2;");
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "flaw2: produced E1xxx diagnostic");
        check(r.program().statements().size() >= 1,
            "flaw2: at least 1 statement recovered, got " + r.program().statements().size());
    }

    static void testErrorRecoveryBinaryLhsNull() {
        System.out.println("-- Error Recovery: binary LHS null (Flaw 3 fix) --");

        // + * 5  — the '+' is not a unary op in DEAL, so parsePrimary rejects it,
        // returning null. Without the fix, parseBinary would enter the loop
        // on '*' and dereference null left.
        ParseResult r = parse("let a: int = + * 5;\nlet b: int = 2;");
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "flaw3: produced E1xxx diagnostic");
        check(r.program().statements().size() >= 1,
            "flaw3: at least 1 statement recovered, got " + r.program().statements().size());
    }

    static void testErrorRecoveryHasExprNull() {
        System.out.println("-- Error Recovery: has() expression null (Flaw 4 fix) --");

        // has(;)  —  has() with a non-expression inside
        ParseResult r = parse("let b: boolean = has(;);\nlet y: int = 1;");
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "flaw4: produced E1xxx diagnostic");
        check(r.program().statements().size() >= 1,
            "flaw4: at least 1 statement recovered, got " + r.program().statements().size());
    }

    static void testErrorRecoveryUnaryOperandNull() {
        System.out.println("-- Error Recovery: unary operand null --");

        // !;  —  unary NOT with missing operand
        ParseResult r = parse("let a: boolean = !;\nlet b: int = 2;");
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "unary-null: produced E1xxx diagnostic");
        check(r.program().statements().size() >= 1,
            "unary-null: at least 1 statement recovered, got " + r.program().statements().size());
    }

    // =========================================================================
    // Regression tests for review F1/F2 fixes
    // =========================================================================

    static void testErrorRecoveryStrayRBrace() {
        System.out.println("-- Error Recovery: stray '}' at top level (F1 fix) --");

        // Stray '}' at top level must not cause infinite loop; must emit E1041
        // and continue parsing remaining statements.
        ParseResult r = parse("let x: int = 1; }\nlet y: int = 2;");
        boolean hasE1041 = r.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E1041"));
        check(hasE1041, "f1-fix: produced E1041 diagnostic");
        // Must have parsed both let statements (x and y) despite the stray }
        check(r.program().statements().size() >= 2,
            "f1-fix: at least 2 statements recovered, got " + r.program().statements().size());
    }

    static void testReturnFunctionExpr() {
        System.out.println("-- ReturnStatement with function expression (F2 fix) --");

        // return function(): int { return 0; }
        // Must parse as a single ReturnStatement wrapping a FunctionExpr,
        // not as a bare return followed by an expression statement.
        ParseResult r = parse("function f(): int { return function(): int { return 0; }; }");
        assertNoParseErrors(r, "return func expr");
        FunctionDeclaration fd = (FunctionDeclaration) r.program().statements().get(0);
        check(fd.body().statements().size() == 1,
            "return-func-expr: 1 body statement, got " + fd.body().statements().size());
        ReturnStatement ret = assertInstance(fd.body().statements().get(0),
                ReturnStatement.class, "return-func-expr");
        if (ret != null) {
            check(ret.expr().isPresent(), "return-func-expr: has expression");
            check(ret.expr().get() instanceof FunctionExpr,
                "return-func-expr: expression is FunctionExpr, got "
                + ret.expr().get().getClass().getSimpleName());
        }
    }

    // =========================================================================
    // v1.1 Template literal and for-of parser tests
    // =========================================================================

    static void testTemplateLiteralPlain() {
        System.out.println("-- Template Literal: plain --");

        ParseResult r = parse("let x = `hello`;");
        assertNoParseErrors(r, "plain template");
        assertStmtCount(r.program(), 1, "plain template");
        VariableDeclaration vd = assertInstance(r.program().statements().get(0),
                VariableDeclaration.class, "vd");
        if (vd != null) {
            TemplateLiteralExpr tl = assertExprInstance(vd.initializer(),
                    TemplateLiteralExpr.class, "template literal");
            if (tl != null) {
                check(tl.parts().size() == 1, "plain template: 1 part, got " + tl.parts().size());
                LiteralExpr part = assertExprInstance(tl.parts().get(0),
                        LiteralExpr.class, "part 0");
                if (part != null && part.value() instanceof LiteralValue.StringLiteral sl) {
                    check(sl.value().equals("hello"), "string part 'hello', got '" + sl.value() + "'");
                }
            }
        }
    }

    static void testTemplateLiteralWithExpr() {
        System.out.println("-- Template Literal: with expression --");

        ParseResult r = parse("let x = `Hello ${name}`;");
        assertNoParseErrors(r, "template with expr");
        assertStmtCount(r.program(), 1, "template with expr");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = assertExprInstance(vd.initializer(),
                TemplateLiteralExpr.class, "template literal");
        if (tl != null) {
            check(tl.parts().size() == 3, "template with expr: 3 parts, got " + tl.parts().size());
            // Part 0: string "Hello "
            check(tl.parts().get(0) instanceof LiteralExpr, "part 0 is LiteralExpr");
            LiteralExpr p0 = (LiteralExpr) tl.parts().get(0);
            check(p0.value() instanceof LiteralValue.StringLiteral
                && ((LiteralValue.StringLiteral) p0.value()).value().equals("Hello "),
                "part 0 = 'Hello '");
            // Part 1: identifier "name"
            check(tl.parts().get(1) instanceof IdentifierExpr, "part 1 is IdentifierExpr");
            IdentifierExpr p1 = (IdentifierExpr) tl.parts().get(1);
            check(p1.name().equals("name"), "part 1 name = 'name'");
            // Part 2: empty string
            check(tl.parts().get(2) instanceof LiteralExpr, "part 2 is LiteralExpr");
            LiteralExpr p2 = (LiteralExpr) tl.parts().get(2);
            check(p2.value() instanceof LiteralValue.StringLiteral
                && ((LiteralValue.StringLiteral) p2.value()).value().equals(""),
                "part 2 = ''");
        }
    }

    static void testTemplateLiteralLeadingExpr() {
        System.out.println("-- Template Literal: leading expression --");

        ParseResult r = parse("let x = `${greeting} world`;");
        assertNoParseErrors(r, "leading expr template");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = assertExprInstance(vd.initializer(),
                TemplateLiteralExpr.class, "template literal");
        if (tl != null) {
            check(tl.parts().size() == 3, "leading expr: 3 parts, got " + tl.parts().size());
            // Part 0: empty string
            check(tl.parts().get(0) instanceof LiteralExpr, "part 0 is LiteralExpr");
            LiteralExpr p0 = (LiteralExpr) tl.parts().get(0);
            check(p0.value() instanceof LiteralValue.StringLiteral
                && ((LiteralValue.StringLiteral) p0.value()).value().equals(""),
                "part 0 = ''");
            // Part 1: identifier "greeting"
            check(tl.parts().get(1) instanceof IdentifierExpr, "part 1 is IdentifierExpr");
            // Part 2: string " world"
            check(tl.parts().get(2) instanceof LiteralExpr, "part 2 is LiteralExpr");
            LiteralExpr p2 = (LiteralExpr) tl.parts().get(2);
            check(p2.value() instanceof LiteralValue.StringLiteral
                && ((LiteralValue.StringLiteral) p2.value()).value().equals(" world"),
                "part 2 = ' world'");
        }
    }

    static void testForOfStatement() {
        System.out.println("-- ForOfStatement --");

        ParseResult r = parse("function f(xs: int[]): null { for (let x: int of xs) { } return; }");
        assertNoParseErrors(r, "for-of array");
        FunctionDeclaration fd = (FunctionDeclaration) r.program().statements().get(0);
        check(fd.body().statements().size() == 2, "2 body stmts");
        ForOfStatement fos = assertInstance(fd.body().statements().get(0),
                ForOfStatement.class, "ForOfStatement");
        if (fos != null) {
            check(fos.varName().equals("x"), "var name = x");
            check(fos.varType() instanceof NamedType, "var type is NamedType");
            check(((NamedType) fos.varType()).name().equals("int"), "var type = int");
            check(fos.iterable() instanceof IdentifierExpr, "iterable is identifier");
            check(((IdentifierExpr) fos.iterable()).name().equals("xs"), "iterable = xs");
        }

        // For-of over string
        r = parse("function f(s: string): null { for (let c: string of s) { } return; }");
        assertNoParseErrors(r, "for-of string");
        fd = (FunctionDeclaration) r.program().statements().get(0);
        fos = assertInstance(fd.body().statements().get(0),
                ForOfStatement.class, "for-of string");
        if (fos != null) {
            check(fos.varName().equals("c"), "var name = c");
            check(((NamedType) fos.varType()).name().equals("string"), "var type = string");
        }
    }

    static void testForOfMissingIterable() {
        System.out.println("-- ForOfStatement: missing iterable (D20) --");

        // Parse the for-of with missing iterable. Should not NPE.
        ParseResult r = parse("function f(): null { for (let x: int of ) { } return; }");
        // We just check it doesn't throw — error diagnostics are expected
        check(r.program() != null, "program not null");
        // The for-of statement should not be present in the AST because the parser
        // should have called synchronize() and returned null.
        System.out.println("    (D20: for-of with missing iterable handled gracefully)");
    }

    static void testTemplateInvalidEscape() {
        System.out.println("-- Template Literal: invalid escape --");

        ParseResult r = parse("let x = `hello \\q world`;");
        assertParseError(r, "E1042", "invalid escape -> E1042");
    }

    static void testTemplateEmptyInterpolation() {
        System.out.println("-- Template Literal: empty interpolation --");

        ParseResult r = parse("let x = `${}`;");
        assertParseError(r, "E1042", "empty interpolation -> E1042");
    }

    static void testTemplateUnterminatedInterpolation() {
        System.out.println("-- Template Literal: unterminated interpolation --");

        ParseResult r = parse("let x = `${name;");
        assertParseError(r, "E1042", "unterminated interpolation -> E1042");
    }

    static void testTemplateNestedBraces() {
        System.out.println("-- Template Literal: nested braces in expr --");

        // ${ {x:1} } — the object literal has braces; the brace scan must not
        // be confused by them.
        ParseResult r = parse("let x = `${ {x:1} }`;");
        // May or may not parse cleanly (object literal inside template may have
        // type-checking issues, but it should parse without crashing).
        check(r.program() != null, "nested braces parses without crash");
    }

    static void testTemplateStringLiteralWithRBrace() {
        System.out.println("-- Template Literal: string with } inside interpolation --");

        // The } inside the string literal must not be mistaken for the closing brace.
        ParseResult r = parse("let x = `${ \"}\" }`;");
        check(r.program() != null, "string with } parses without crash");
        // Verify the expression inside ${} is a string literal containing "}"
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = (TemplateLiteralExpr) vd.initializer();
        if (tl != null && tl.parts().size() >= 2) {
            ExpressionNode expr = tl.parts().get(1);
            check(expr instanceof LiteralExpr, "expr part is LiteralExpr");
            if (expr instanceof LiteralExpr le && le.value() instanceof LiteralValue.StringLiteral sl) {
                check(sl.value().equals("}"), "string literal = '}', got '" + sl.value() + "'");
            }
        }
    }

    static void testTemplateNestedTemplateLiteral() {
        System.out.println("-- Template Literal: nested template in interpolation --");

        // The nested template literal inside ${} must not confuse the parser.
        // Escaped backticks inside the expression — the backslash prevents
        // the lexer from treating them as template delimiters. The parser's
        // splitTemplateLiteral processes \` as an escaped backtick character.
        ParseResult r = parse("let x = `${ \\`nested\\` }`;");
        check(r.program() != null, "nested template parses without crash");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = (TemplateLiteralExpr) vd.initializer();
        if (tl != null && tl.parts().size() >= 2) {
            ExpressionNode expr = tl.parts().get(1);
            // The expression contains escaped backticks, which are string content,
            // so the expression should be a BinaryExpr or similar (depending on
            // what the expression parses as). The key invariant: no crash.
            check(expr != null, "expr part is not null");
        }
    }

    static void testTemplateEscapedBacktick() {
        System.out.println("-- Template Literal: escaped backtick in interpolation --");

        // \` must not be mistaken for a nested template start.
        ParseResult r = parse("let x = `${ \\`escaped\\` }`;");
        check(r.program() != null, "escaped backtick parses without crash");
    }

    static void testTemplateMalformedExpression() {
        System.out.println("-- Template Literal: malformed expression (D16) --");

        // ${@} is syntactically invalid. The parser must emit diagnostics and
        // substitute a placeholder — not crash or NPE.
        ParseResult r = parse("let x = `${@}`;");
        check(r.program() != null, "malformed expr: program not null");
        // Should have diagnostics
        boolean hasDiag = !r.diagnostics().isEmpty();
        check(hasDiag, "malformed expr: has diagnostics");
        // Verify the template literal still has parts (placeholder substituted)
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        if (vd != null) {
            TemplateLiteralExpr tl = (TemplateLiteralExpr) vd.initializer();
            if (tl != null) {
                // ${@} produces 3 parts: empty string, placeholder, empty string
                check(tl.parts().size() == 3,
                    "malformed expr: 3 parts, got " + tl.parts().size());
            }
        }
    }

    /**
     * ISSUE-0224 verification 5: template-interpolation scalar rebasing.
     * Every sub-lexed diagnostic inside ${...} carries a SOURCE range with
     * exact original scalar offsets; the rebased sub-parser EOF token anchors
     * end-of-input errors at the expression-end raw position; E1042
     * pseudo-token and D16 placeholder ranges are raw-positioned. Each
     * expectation is cross-checked against an independent ScalarSourceCursor
     * recomputation of the original source.
     */
    static void testTemplateInterpolationRangeAnchors() {
        System.out.println("-- Template Literal: interpolation range anchors (ISSUE-0224) --");

        // 1. End-of-input E1037 inside a terminated interpolation anchors at
        //    the closing '}' position with SOURCE origin and exact offsets.
        String src1 = "let x = `${foo +}`;";
        ParseResult r1 = parse(src1);
        CompilerDiagnostic e1037 = findDiag(r1.diagnostics(), "E1037");
        check(e1037 != null, "T1: E1037 present for `${foo +}`");
        if (e1037 != null) {
            checkRange(e1037.range(), "test.deal", 1, 17, 1, 17, 16, 16, 0,
                RangeOrigin.SOURCE, "T1: E1037 at the '}' position");
            check(e1037.notes().isEmpty(), "T1: SOURCE anchor carries no notes");
            checkCursorAt(src1, 16, 1, 17, "T1");
        }

        // 2. End-of-input E1037 in an unterminated interpolation anchors at
        //    the end of the raw template content (the closing backtick's
        //    position).
        String src2 = "let x = `a${foo +`;";
        ParseResult r2 = parse(src2);
        CompilerDiagnostic e1042 = findDiag(r2.diagnostics(), "E1042");
        check(e1042 != null, "T2: E1042 present for unterminated interpolation");
        if (e1042 != null) {
            checkRange(e1042.range(), "test.deal", 1, 13, 1, 15, 12, 14, 2,
                RangeOrigin.SOURCE, "T2: E1042 raw-positioned at the expression start");
        }
        CompilerDiagnostic e1037b = findDiag(r2.diagnostics(), "E1037");
        check(e1037b != null, "T2: E1037 present for unterminated interpolation");
        if (e1037b != null) {
            checkRange(e1037b.range(), "test.deal", 1, 18, 1, 18, 17, 17, 0,
                RangeOrigin.SOURCE, "T2: E1037 at the end of the raw content");
            check(e1037b.notes().isEmpty(), "T2: SOURCE anchor carries no notes");
            checkCursorAt(src2, 17, 1, 18, "T2");
        }

        // 3. A sub-lexer error after a recognized escape rebases through the
        //    scalar map: the decoded '@' maps to the raw '@' position, not the
        //    decoded-coordinate position.
        String src3 = "let x = `${\\$x + @}`;";
        ParseResult r3 = parse(src3);
        CompilerDiagnostic e1001 = findDiag(r3.diagnostics(), "E1001");
        check(e1001 != null, "T3: E1001 present for `${\\$x + @}`");
        if (e1001 != null) {
            checkRange(e1001.range(), "test.deal", 1, 18, 1, 19, 17, 18, 1,
                RangeOrigin.SOURCE, "T3: E1001 rebased to the raw '@' position");
            checkCursorAt(src3, 17, 1, 18, "T3");
        }
        CompilerDiagnostic e1037c = findDiag(r3.diagnostics(), "E1037");
        check(e1037c != null, "T3: E1037 present after the rebased '@'");
        if (e1037c != null) {
            checkRange(e1037c.range(), "test.deal", 1, 19, 1, 19, 18, 18, 0,
                RangeOrigin.SOURCE, "T3: E1037 at the '}' position");
        }

        // 4. Astral content before the interpolation: column and offset
        //    arithmetic count the supplementary character as one scalar.
        String src4 = "let x = `\uD83D\uDE00${foo +}`;";
        ParseResult r4 = parse(src4);
        CompilerDiagnostic e1037d = findDiag(r4.diagnostics(), "E1037");
        check(e1037d != null, "T4: E1037 present after the astral prefix");
        if (e1037d != null) {
            checkRange(e1037d.range(), "test.deal", 1, 18, 1, 18, 17, 17, 0,
                RangeOrigin.SOURCE, "T4: astral scalar counts one column");
            checkCursorAt(src4, 17, 1, 18, "T4");
        }

        // 5. Tab content before the interpolation counts one scalar.
        String src5 = "let x = `\t${foo +}`;";
        ParseResult r5 = parse(src5);
        CompilerDiagnostic e1037e = findDiag(r5.diagnostics(), "E1037");
        check(e1037e != null, "T5: E1037 present after the tab prefix");
        if (e1037e != null) {
            checkRange(e1037e.range(), "test.deal", 1, 18, 1, 18, 17, 17, 0,
                RangeOrigin.SOURCE, "T5: tab counts one column");
            checkCursorAt(src5, 17, 1, 18, "T5");
        }

        // 6. E1042 pseudo-tokens are raw-positioned and scalar-exact.
        String src6 = "let x = `hi\uD83D\uDE00\\q`;";
        ParseResult r6 = parse(src6);
        CompilerDiagnostic e1042b = findDiag(r6.diagnostics(), "E1042");
        check(e1042b != null, "T6: E1042 present for invalid escape after astral");
        if (e1042b != null) {
            checkRange(e1042b.range(), "test.deal", 1, 14, 1, 15, 13, 14, 1,
                RangeOrigin.SOURCE, "T6: invalid-escape pseudo-token raw-positioned");
            checkCursorAt(src6, 13, 1, 14, "T6");
        }

        String src7 = "let x = `hi}there`;";
        ParseResult r7 = parse(src7);
        CompilerDiagnostic e1042c = findDiag(r7.diagnostics(), "E1042");
        check(e1042c != null, "T7: E1042 present for unexpected '}'");
        if (e1042c != null) {
            checkRange(e1042c.range(), "test.deal", 1, 12, 1, 13, 11, 12, 1,
                RangeOrigin.SOURCE, "T7: unexpected-'}' pseudo-token raw-positioned");
        }

        // 7. Empty expression: E1042 and the D16 placeholder carry zero
        //    scalar length at the expression-start raw position.
        String src8 = "let x = `${}`;";
        ParseResult r8 = parse(src8);
        CompilerDiagnostic e1042d = findDiag(r8.diagnostics(), "E1042");
        check(e1042d != null, "T8: E1042 present for empty interpolation");
        if (e1042d != null) {
            checkRange(e1042d.range(), "test.deal", 1, 12, 1, 12, 11, 11, 0,
                RangeOrigin.SOURCE, "T8: empty-expression anchor at the expression start");
        }
        VariableDeclaration vd8 = (VariableDeclaration) r8.program().statements().get(0);
        if (vd8 != null && vd8.initializer() instanceof TemplateLiteralExpr tl8
                && tl8.parts().size() == 3) {
            Span placeholderSpan = tl8.parts().get(1).span();
            check(placeholderSpan.startLine() == 1 && placeholderSpan.startColumn() == 12
                    && placeholderSpan.endLine() == 1 && placeholderSpan.endColumn() == 12,
                "T8: D16 placeholder span (1,12)-(1,12), got " + placeholderSpan);
            check(placeholderSpan.startScalarOffset() == 11
                    && placeholderSpan.endScalarOffset() == 11,
                "T8: D16 placeholder offsets (11,11), got ("
                    + placeholderSpan.startScalarOffset() + ","
                    + placeholderSpan.endScalarOffset() + ")");
        }
    }

    /** Finds the first diagnostic with the given code, or null. */
    private static CompilerDiagnostic findDiag(List<CompilerDiagnostic> diags, String code) {
        for (CompilerDiagnostic d : diags) {
            if (d.code().equals(code)) return d;
        }
        return null;
    }

    /** Asserts a diagnostic range field by field. */
    private static void checkRange(DiagnosticRange r, String file,
            int startLine, int startColumn, int endLine, int endColumn,
            int startScalarOffset, int endScalarOffset, int scalarLength,
            RangeOrigin origin, String context) {
        check(r.file().equals(file),
            context + ": file '" + r.file() + "' != '" + file + "'");
        check(r.startLine() == startLine && r.startColumn() == startColumn,
            context + ": start (" + r.startLine() + "," + r.startColumn()
                + ") != (" + startLine + "," + startColumn + ")");
        check(r.endLine() == endLine && r.endColumn() == endColumn,
            context + ": end (" + r.endLine() + "," + r.endColumn()
                + ") != (" + endLine + "," + endColumn + ")");
        check(r.startScalarOffset() == startScalarOffset
                && r.endScalarOffset() == endScalarOffset
                && r.scalarLength() == scalarLength,
            context + ": offsets (" + r.startScalarOffset() + ","
                + r.endScalarOffset() + ",len " + r.scalarLength()
                + ") != (" + startScalarOffset + "," + endScalarOffset
                + ",len " + scalarLength + ")");
        check(r.origin() == origin,
            context + ": origin " + r.origin() + " != " + origin);
    }

    /** Cross-checks the cursor position reached at a scalar offset. */
    private static void checkCursorAt(String source, int scalarOffset,
            int expectedLine, int expectedColumn, String context) {
        ScalarSourceCursor cursor = new ScalarSourceCursor(source);
        for (int i = 0; i < scalarOffset; i++) {
            cursor.advance();
        }
        check(cursor.line() == expectedLine && cursor.column() == expectedColumn,
            context + ": cursor at offset " + scalarOffset + " is ("
                + cursor.line() + "," + cursor.column() + ") != ("
                + expectedLine + "," + expectedColumn + ")");
    }

    // =========================================================================
    // Span tests
    // =========================================================================

    static void testParserWarn() {
        System.out.println("-- Parser warn() --");

        // Create parser with minimal tokens
        Parser parser = new Parser(List.of(), "test.deal");

        // Test warn with Token. The token carries explicit scalar offsets
        // (start 20, length 4): the warning anchor is the token's full
        // SOURCE range with exact offsets (D4/D5).
        Token tok = new Token(TokenType.IDENTIFIER, "test", 5, 3, 4, 20, 4);
        parser.warn(DiagnosticCode.E1001, "warning from token", tok);

        List<CompilerDiagnostic> diags = parser.parse().diagnostics();
        check(diags.size() == 1,
            "warn with token should add 1 diagnostic, got " + diags.size());
        if (!diags.isEmpty()) {
            CompilerDiagnostic d = diags.get(0);
            check(d.severity().equals("warning"),
                "severity should be 'warning', got: " + d.severity());
            check(d.code().equals("E1001"),
                "code should be E1001, got: " + d.code());
            check(d.message().equals("warning from token"),
                "message preserved");
            check(d.file().equals("test.deal"),
                "file preserved");
            check(d.line() == 5,
                "line from token: " + d.line());
            check(d.column() == 3,
                "column from token: " + d.column());
            check(d.range().origin() == RangeOrigin.SOURCE,
                "token warning range must be SOURCE, got " + d.range().origin());
            check(d.range().startScalarOffset() == 20,
                "token warning start offset must be 20, got "
                    + d.range().startScalarOffset());
            check(d.range().endScalarOffset() == 24,
                "token warning end offset must be 24, got "
                    + d.range().endScalarOffset());
            check(d.range().scalarLength() == 4,
                "token warning scalar length must be 4, got "
                    + d.range().scalarLength());
            check(d.range().endLine() == 5 && d.range().endColumn() == 7,
                "token warning range end must be (5,7), got ("
                    + d.range().endLine() + "," + d.range().endColumn() + ")");
        }

        // Test warn with ExpressionNode (IdentifierExpr). The span carries
        // explicit scalar offsets (30 to 36): the warning anchor is the
        // span's SOURCE range (D4/D5).
        Span span = new Span("test.deal", 10, 2, 10, 8, 30, 36);
        ExpressionNode expr = new IdentifierExpr(span, "myVar");
        parser.warn(DiagnosticCode.E1002, "warning from node", expr);

        diags = parser.parse().diagnostics();
        check(diags.size() == 2,
            "warn with node should add another diagnostic, got " + diags.size());
        if (diags.size() >= 2) {
            CompilerDiagnostic d = diags.get(1);
            check(d.severity().equals("warning"),
                "node warning severity should be 'warning'");
            check(d.line() == 10,
                "node warning line should be 10, got: " + d.line());
            check(d.column() == 2,
                "node warning column should be 2, got: " + d.column());
            check(d.message().equals("warning from node"),
                "node warning message preserved");
            check(d.range().origin() == RangeOrigin.SOURCE,
                "node warning range must be SOURCE, got " + d.range().origin());
            check(d.range().startScalarOffset() == 30,
                "node warning start offset must be 30, got "
                    + d.range().startScalarOffset());
            check(d.range().endScalarOffset() == 36,
                "node warning end offset must be 36, got "
                    + d.range().endScalarOffset());
            check(d.range().scalarLength() == 6,
                "node warning scalar length must be 6, got "
                    + d.range().scalarLength());
        }

        // Offset-less anchors (the UNKNOWN sentinel) convert to the
        // canonical SYNTHETIC shape with the D4 anchor note — never a
        // SOURCE range (D4/D9).
        Parser unknownParser = new Parser(List.of(), "test.deal");
        unknownParser.warn(DiagnosticCode.E1003, "unknown-offset warning",
            new Token(TokenType.IDENTIFIER, "x", 7, 4, 1));
        ParseResult unknownResult = unknownParser.parse();
        check(unknownResult.diagnostics().size() == 1,
            "unknown-offset warn should add 1 diagnostic, got "
                + unknownResult.diagnostics().size());
        if (!unknownResult.diagnostics().isEmpty()) {
            CompilerDiagnostic d = unknownResult.diagnostics().get(0);
            check(d.range().origin() == RangeOrigin.SYNTHETIC,
                "offset-less token warning must convert to SYNTHETIC, got "
                    + d.range().origin());
            check(d.range().startLine() == 1 && d.range().startColumn() == 1
                    && d.range().endLine() == 1 && d.range().endColumn() == 1
                    && d.range().scalarLength() == 0
                    && d.range().startScalarOffset() == 0
                    && d.range().endScalarOffset() == 0,
                "offset-less token warning must be the canonical synthetic shape, got "
                    + d.range());
            check(d.notes().size() == 1
                    && d.notes().get(0).message()
                        .equals("missing anchor: test.deal:7:4"),
                "offset-less token warning must carry the D4 anchor note, got "
                    + d.notes());
        }

        // Verify warnings don't cause parse.hasErrors() to return true
        ParseResult result = parser.parse();
        check(!result.hasErrors(),
            "warnings should not cause hasErrors() to return true");

        // Verify separate parser: no warnings by default
        Parser cleanParser = new Parser(List.of(), "clean.deal");
        ParseResult cleanResult = cleanParser.parse();
        check(cleanResult.diagnostics().isEmpty(),
            "clean parser should have no diagnostics");
    }

    static void testSpanPositions() {
        System.out.println("-- Span Positions --");

        ParseResult r = parse("let x: int = 42;");
        assertNoParseErrors(r, "span test");
        ProgramNode prog = r.program();
        check(prog.span().startLine() == 1, "program start line");
        check(prog.span().startColumn() == 1, "program start col");

        VariableDeclaration vd = (VariableDeclaration) prog.statements().get(0);
        check(vd.span().startLine() == 1, "stmt start line");
        check(vd.span().startColumn() == 1, "stmt start col");
        // End position should be reasonable
        check(vd.span().endLine() == 1, "stmt end line");
        check(vd.span().endColumn() >= 15, "stmt end col >= 15, got " + vd.span().endColumn());
    }

    // =========================================================================
    // Edge case tests
    // =========================================================================

    static void testOnlyComments() {
        System.out.println("-- Only Comments --");

        ParseResult r = parse("// just a comment\n/* block comment */");
        assertNoParseErrors(r, "only comments");
        assertStmtCount(r.program(), 0, "only comments");
    }

    static void testMultipleStatements() {
        System.out.println("-- Multiple Statements --");

        ParseResult r = parse(
            "let a: int = 1;\n" +
            "let b: int = 2;\n" +
            "let c: int = 3;");
        assertNoParseErrors(r, "multiple stmts");
        assertStmtCount(r.program(), 3, "3 statements");
    }

    static void testSemicolons() {
        System.out.println("-- Semicolon Rules --");

        // With semicolon
        ParseResult r1 = parse("let x: int = 1;");
        assertNoParseErrors(r1, "with semicolon");
        assertStmtCount(r1.program(), 1, "with semicolon");

        // Without semicolon (statement followed by })
        ParseResult r2 = parse("{ let x: int = 1 }");
        assertNoParseErrors(r2, "without semicolon before }");
        Block b = (Block) r2.program().statements().get(0);
        check(b.statements().size() == 1, "1 stmt in block without semicolon");

        // Without semicolon (statement followed by EOF)
        ParseResult r3 = parse("let x: int = 1");
        assertNoParseErrors(r3, "without semicolon at EOF");
        assertStmtCount(r3.program(), 1, "1 stmt without semicolon at EOF");
    }

    // =========================================================================
    // @jsonable directive tests (ISSUE-0046)
    // =========================================================================

    static void testJsonableExportClass() {
        System.out.println("-- @jsonable export class -> isJsonable=true --");

        ParseResult r = parse("// @jsonable\nexport class C { x: int; }");
        assertNoParseErrors(r, "@jsonable export class");
        ProgramNode prog = r.program();
        assertStmtCount(prog, 1, "@jsonable export class");

        ExportDeclaration ed = assertInstance(prog.statements().get(0),
                ExportDeclaration.class, "@jsonable export class node");
        ClassDeclaration cd = assertInstance(ed.declaration(),
                ClassDeclaration.class, "exported declaration is class");
        check(cd.isJsonable(),
            "ClassDeclaration.isJsonable should be true with @jsonable directive");
    }

    static void testJsonableExportFunction() {
        System.out.println("-- @jsonable export function -> warning --");

        ParseResult r = parse("// @jsonable\nexport function f(): int { return 0; }");
        assertParseError(r, "E1043", "@jsonable export function");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "warning diagnostic present for export function");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("warning"),
                "severity is warning, got: " + warnDiag.severity());
            check(warnDiag.message().contains("export class"),
                "message mentions export class: " + warnDiag.message());

            // ISSUE-0273: the E1043 anchor is the complete directive
            // comment range (parent D6) — line 1, column 1 through column
            // 13, offsets (0,12) — never the EXPORT token and never
            // SYNTHETIC (1,1).
            check(warnDiag.range().origin() == RangeOrigin.SOURCE,
                "E1043 range must be SOURCE, got " + warnDiag.range().origin());
            check(warnDiag.line() == 1 && warnDiag.column() == 1,
                "E1043 must anchor at the complete comment (1,1), got ("
                    + warnDiag.line() + "," + warnDiag.column() + ")");
            check(warnDiag.range().startScalarOffset() == 0,
                "E1043 start offset must be 0, got "
                    + warnDiag.range().startScalarOffset());
            check(warnDiag.range().endScalarOffset() == 12,
                "E1043 end offset must be 12, got "
                    + warnDiag.range().endScalarOffset());
            check(warnDiag.range().scalarLength() == 12,
                "E1043 scalar length must be 12 (complete comment), got "
                    + warnDiag.range().scalarLength());
            check(warnDiag.range().endLine() == 1 && warnDiag.range().endColumn() == 13,
                "E1043 range end must be (1,13), got ("
                    + warnDiag.range().endLine() + ","
                    + warnDiag.range().endColumn() + ")");
        }
    }

    static void testJsonableStandaloneClass() {
        System.out.println("-- @jsonable standalone class -> warning, isJsonable=false --");

        ParseResult r = parse("// @jsonable\nclass C { x: int; }");
        assertParseError(r, "E1043", "@jsonable standalone class");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "warning diagnostic present for standalone class");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("warning"),
                "severity is warning, got: " + warnDiag.severity());
        }

        // isJsonable should be false since directive was ignored
        ProgramNode prog = r.program();
        assertStmtCount(prog, 1, "standalone class");
        ClassDeclaration cd = assertInstance(prog.statements().get(0),
                ClassDeclaration.class, "standalone class node");
        check(!cd.isJsonable(),
            "ClassDeclaration.isJsonable should be false - directive ignored");
    }

    static void testJsonableStandaloneFunction() {
        System.out.println("-- @jsonable standalone function -> warning --");

        ParseResult r = parse("// @jsonable\nfunction f(): int { return 0; }");
        assertParseError(r, "E1043", "@jsonable standalone function");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "warning diagnostic present for standalone function");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("warning"),
                "severity is warning, got: " + warnDiag.severity());
        }
    }

    static void testJsonableLet() {
        System.out.println("-- @jsonable let -> warning --");

        ParseResult r = parse("// @jsonable\nlet x: int = 1;");
        assertParseError(r, "E1043", "@jsonable let");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "warning diagnostic present for let");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("warning"),
                "severity is warning, got: " + warnDiag.severity());
        }
    }

    static void testJsonableNotPresent() {
        System.out.println("-- no @jsonable -> isJsonable=false --");

        ParseResult r = parse("export class C { x: int; }");
        assertNoParseErrors(r, "export class without @jsonable");

        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(!cd.isJsonable(),
            "ClassDeclaration.isJsonable should be false without directive");
    }

    static void testJsonableWarningSeverity() {
        System.out.println("-- @jsonable warning severity is warning --");

        ParseResult r = parse("// @jsonable\nclass C { x: int; }");
        List<CompilerDiagnostic> diags = r.diagnostics();

        for (CompilerDiagnostic d : diags) {
            if (d.code().equals("E1043")) {
                check(d.severity().equals("warning"),
                    "severity should be 'warning', got: '" + d.severity() + "'");
                check(!d.severity().equals("error"),
                    "severity should NOT be 'error'");
            }
        }

        // Warnings should not cause hasErrors() to return true
        check(!r.hasErrors(),
            "warnings should not cause hasErrors() to return true");
    }

    static void testJsonableInvalidPlacementIsJsonableFalse() {
        System.out.println("-- @jsonable invalid placement -> isJsonable remains false --");

        // After warning, the standalone class should have isJsonable=false
        ParseResult r = parse("// @jsonable\nclass C { x: int; }");
        ProgramNode prog = r.program();
        assertStmtCount(prog, 1, "one statement");
        ClassDeclaration cd = assertInstance(prog.statements().get(0),
                ClassDeclaration.class, "class node");
        check(!cd.isJsonable(),
            "isJsonable should be false after invalid placement warning");
    }

    // =========================================================================
    // Async/Await parser tests
    // =========================================================================

    static void testAsyncFunctionDeclaration() {
        System.out.println("-- Async Function Declaration --");

        ParseResult r = parse("async function f(): int { return await g(); }");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "async function decl");
        assertStmtCount(prog, 1, "one statement");

        FunctionDeclaration fd = assertInstance(prog.statements().get(0),
                FunctionDeclaration.class, "function decl node");
        check(fd.isAsync(), "isAsync should be true");
        check(fd.name().equals("f"), "name should be f");

        // Check body: should contain return with await
        check(fd.body().statements().size() == 1, "body has one statement");
        ReturnStatement ret = assertInstance(fd.body().statements().get(0),
                ReturnStatement.class, "return statement");
        check(ret.expr().isPresent(), "return has expression");
        AwaitExpression await = assertExprInstance(ret.expr().get(),
                AwaitExpression.class, "await expression");
        CallExpr call = assertExprInstance(await.callee(),
                CallExpr.class, "call expression");
        IdentifierExpr callee = assertExprInstance(call.callee(),
                IdentifierExpr.class, "callee is identifier");
        check(callee.name().equals("g"), "callee is g");
    }

    static void testAwaitExpression() {
        System.out.println("-- Await Expression --");

        ParseResult r = parse("async function f(): int { await g(1, 2); }");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "await expression");
        assertStmtCount(prog, 1, "one statement");

        FunctionDeclaration fd = assertInstance(prog.statements().get(0),
                FunctionDeclaration.class, "function decl");
        check(fd.isAsync(), "isAsync should be true");

        // Body contains expression statement with await
        ExpressionStatement es = assertInstance(fd.body().statements().get(0),
                ExpressionStatement.class, "expression statement");
        AwaitExpression await = assertExprInstance(es.expr(),
                AwaitExpression.class, "await expression");
        CallExpr call = assertExprInstance(await.callee(),
                CallExpr.class, "call expression");
        IdentifierExpr callee = assertExprInstance(call.callee(),
                IdentifierExpr.class, "callee is identifier");
        check(callee.name().equals("g"), "callee is g");
        check(call.args().size() == 2, "two args");
    }

    static void testAwaitNotCall() {
        System.out.println("-- Await Not Call -> E1042 --");

        ParseResult r = parse("async function f(): int { await 42; }");
        assertParseError(r, "E1042", "await not followed by call");

        // Should still produce an AwaitExpression (error recovery)
        ProgramNode prog = r.program();
        if (!prog.statements().isEmpty()) {
            FunctionDeclaration fd = assertInstance(prog.statements().get(0),
                    FunctionDeclaration.class, "function decl");
            if (!fd.body().statements().isEmpty()) {
                // The expression statement should still exist
                check(fd.body().statements().get(0) instanceof ExpressionStatement,
                        "expression statement exists despite error");
            }
        }
    }

    static void testAsyncFunctionType() {
        System.out.println("-- Async Function Type --");

        // Test: let f: async (int) => string;
        ParseResult r = parse("let f: async (p: int) => string = g;");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "async function type annotation");
        assertStmtCount(prog, 1, "one statement");

        VariableDeclaration vd = assertInstance(prog.statements().get(0),
                VariableDeclaration.class, "variable decl");
        check(vd.typeAnnotation().isPresent(), "has type annotation");

        TypeNode tn = vd.typeAnnotation().get();
        check(tn instanceof FunctionType, "type is FunctionType");
        FunctionType ft = (FunctionType) tn;
        check(ft.isAsync(), "isAsync should be true on function type");
        check(ft.params().size() == 1, "one param");
        check(ft.params().get(0).name().equals("p"), "param name is p");

        // Also test just the type in isolation via let with no init
        ParseResult r2 = parse("let f: async (a: int, b: string) => boolean = h;");
        ProgramNode prog2 = r2.program();
        // Multi-param async function type
        VariableDeclaration vd2 = assertInstance(prog2.statements().get(0),
                VariableDeclaration.class, "variable decl with async type");
        check(vd2.typeAnnotation().isPresent(), "has type annotation");
        TypeNode tn2 = vd2.typeAnnotation().get();
        check(tn2 instanceof FunctionType, "type is FunctionType");
        FunctionType ft2 = (FunctionType) tn2;
        check(ft2.isAsync(), "isAsync should be true with multi-param");
        check(ft2.params().size() == 2, "two params");
    }

    static void testAsyncFunctionExprInLet() {
        System.out.println("-- Async Function Expression in Let --");

        ParseResult r = parse("let f = async function(): int { return 5; };");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "async function expr in let");
        assertStmtCount(prog, 1, "one statement");

        VariableDeclaration vd = assertInstance(prog.statements().get(0),
                VariableDeclaration.class, "variable decl");
        FunctionExpr fe = assertExprInstance(vd.initializer(),
                FunctionExpr.class, "function expr");
        check(fe.isAsync(), "isAsync should be true on function expr");
    }

    static void testAsyncTypeAnnotation() {
        System.out.println("-- Async Type Annotation in Let --");

        // let f: async (int) => string;
        // But since let requires an initializer, use a valid one
        ParseResult r = parse("let f: async (p: int) => string = someFunc;");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "async type annotation in let");
        assertStmtCount(prog, 1, "one statement");

        VariableDeclaration vd = assertInstance(prog.statements().get(0),
                VariableDeclaration.class, "variable decl");
        check(vd.typeAnnotation().isPresent(), "has type annotation");
        TypeNode tn3 = vd.typeAnnotation().get();
        check(tn3 instanceof FunctionType, "type is FunctionType");
        FunctionType ft = (FunctionType) tn3;
        check(ft.isAsync(), "isAsync in type annotation");
    }

    static void testExportAsyncFunction() {
        System.out.println("-- Export Async Function --");

        ParseResult r = parse("export async function f(): int { return 5; }");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "export async function");
        assertStmtCount(prog, 1, "one statement");

        ExportDeclaration ed = assertInstance(prog.statements().get(0),
                ExportDeclaration.class, "export decl");
        FunctionDeclaration fd = assertInstance(ed.declaration(),
                FunctionDeclaration.class, "function inside export");
        check(fd.isAsync(), "isAsync should be true on exported async function");
        check(fd.name().equals("f"), "name is f");
    }

    static void testAsyncExprStatement() {
        System.out.println("-- Async Function as Expression Statement --");

        // Bare async function expression as a statement
        ParseResult r = parse("async function(): int { return 5; };");
        ProgramNode prog = r.program();
        assertNoParseErrors(r, "async expr statement");
        assertStmtCount(prog, 1, "one statement");

        ExpressionStatement es = assertInstance(prog.statements().get(0),
                ExpressionStatement.class, "expression statement");
        FunctionExpr fe = assertExprInstance(es.expr(),
                FunctionExpr.class, "function expr");
        check(fe.isAsync(), "isAsync should be true");
    }

    static void testJsonableEndToEndLexParse() {
        System.out.println("-- @jsonable end-to-end lex + parse --");

        // Verify that lexing + parsing @jsonable + let produces the
        // correct warning through the event pipeline.
        LexResult lex = new Lexer("// @jsonable\nlet x: int = 1;", "test.deal").tokenize();
        Parser parser = new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents());
        ParseResult r = parser.parse();

        List<CompilerDiagnostic> diags = r.diagnostics();
        boolean foundWarn = diags.stream().anyMatch(
                d -> d.code().equals("E1043") && d.severity().equals("warning"));
        check(foundWarn, "end-to-end: warning with code E1043 and severity 'warning'");

        // Verify no error-level diagnostics
        boolean hasErrors = diags.stream().anyMatch(
                d -> d.severity().equals("error"));
        check(!hasErrors, "end-to-end: no errors, only warnings");

        // Verify the event anchored at the LET token (index 0) with no
        // token-carried directives.
        check(lex.directiveEvents().size() == 1,
            "end-to-end: one directive event, got "
                + lex.directiveEvents().size());
        check(lex.directiveEvents().get(0).declarationAnchorTokenIndex() != null
                && lex.directiveEvents().get(0).declarationAnchorTokenIndex() == 0,
            "end-to-end: the jsonable event anchors at the LET token");
        check(lex.tokens().get(0).type() == TokenType.LET,
            "end-to-end: LET is the first token");
    }


    // =========================================================================
    // ISSUE-0273 directive binding pins (D4/D7/D10)
    // =========================================================================

    static void testExternCWrongFileKind() {
        System.out.println("-- @extern-c wrong file kind -> E1046 --");

        // @extern-c in an implementation (.deal) file: E1046 at the
        // directive's complete comment range and no extern-C state.
        ParseResult r = parse("// @extern-c\nexport class A { x: int = 0; }");
        assertParseError(r, "E1046", "@extern-c in a .deal file rejected");
        check(!r.program().fileDirectives().externC(),
            "no extern-C state after wrong-file-kind rejection");
        CompilerDiagnostic d = diagOf(r, "E1046");
        if (d != null) {
            check(d.range().startScalarOffset() == 0
                    && d.range().endScalarOffset() == 12
                    && d.range().scalarLength() == 12,
                "E1046 at the complete comment (0,12), got ("
                    + d.range().startScalarOffset() + ","
                    + d.range().endScalarOffset() + ")");
        }
    }

    static void testExternCDuplicateAndPlacement() {
        System.out.println("-- @extern-c duplicate/placement -> E1046 --");

        // Duplicate @extern-c in a .d.deal file: E1046 at the duplicate.
        ParseResult r = parseFile(
            "// @extern-c\n// @extern-c\nexport class A { x: int = 0; }",
            "mod.d.deal");
        assertParseError(r, "E1046", "duplicate @extern-c rejected");
        check(r.program().fileDirectives().externC(),
            "the first valid event keeps extern-C effective");
        CompilerDiagnostic dup = r.diagnostics().stream()
                .filter(d -> d.code().equals("E1046")
                        && d.range().startLine() == 2)
                .findFirst().orElse(null);
        check(dup != null, "duplicate E1046 at the second comment");

        // @extern-c after a declaration: E1046 and no effective extern-C.
        r = parseFile(
            "export class A { x: int = 0; }\n// @extern-c",
            "mod.d.deal");
        assertParseError(r, "E1046",
            "@extern-c after a declaration rejected");
        check(!r.program().fileDirectives().externC(),
            "placement violation drops effective extern-C");

        // @extern-c before declarations but with a later import: E1046.
        r = parseFile(
            "// @extern-c\nimport * as m from \"./m\"\nexport class A { x: int = 0; }",
            "mod.d.deal");
        assertParseError(r, "E1046",
            "@extern-c followed by an import rejected");
        check(!r.program().fileDirectives().externC(),
            "later import drops effective extern-C");

        // Valid placement: imports first, then @extern-c, then classes.
        r = parseFile(
            "import * as m from \"./m\"\n// @extern-c\n// @c-struct\nexport class A { x: int = 0; }",
            "mod.d.deal");
        assertNoParseErrors(r, "@extern-c after imports accepted");
        check(r.program().fileDirectives().externC(),
            "effective extern-C in the valid placement fixture");
        check(r.program().fileDirectives().externCRange() != null
                && r.program().fileDirectives().externCRange()
                    .startScalarOffset() == 25,
            "externCRange = the first event's complete comment range (25)");
    }

    static void testCMarkerOutsideExternC() {
        System.out.println("-- C markers outside extern-C -> E1046 --");

        ParseResult r = parse("// @c-struct\nexport class A { x: int = 0; }");
        assertParseError(r, "E1046", "@c-struct without @extern-c rejected");
        CompilerDiagnostic d = diagOf(r, "E1046");
        if (d != null) {
            check(d.range().startScalarOffset() == 0
                    && d.range().endScalarOffset() == 12
                    && d.range().scalarLength() == 12,
                "E1046 at the directive range (0,12), got ("
                    + d.range().startScalarOffset() + ","
                    + d.range().endScalarOffset() + ")");
        }

        // The marker was recorded on the class; no JSONABLE metadata.
        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(cd.directives().contains(DeclarationDirective.C_STRUCT),
            "C marker recorded on the export-wrapped class");
        check(!cd.isJsonable(), "no jsonable metadata");
    }

    static void testCMarkerCardinality() {
        System.out.println("-- C-marker cardinality -> E7002 --");

        // Zero markers: E7002 at the class span.
        ParseResult r = parseFile("// @extern-c\nexport class A { x: int = 0; }",
            "mod.d.deal");
        assertParseError(r, "E7002", "zero C markers rejected in extern-C file");
        check(r.program().fileDirectives().externC(), "extern-C effective");
        CompilerDiagnostic zero = diagOf(r, "E7002");
        if (zero != null) {
            check(zero.range().startLine() == 2,
                "zero-marker E7002 anchors at the class span (line 2), got "
                    + zero.range().startLine());
        }

        // Exactly one marker: valid.
        r = parseFile(
            "// @extern-c\n// @c-struct\nexport class A { x: int = 0; }",
            "mod.d.deal");
        assertNoParseErrors(r, "exactly one C marker accepted");
        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(cd.directives().contains(DeclarationDirective.C_STRUCT)
                && !cd.directives().contains(DeclarationDirective.JSONABLE),
            "the class records C_STRUCT only");

        // Two markers: E7002 at the class span.
        r = parseFile(
            "// @extern-c\n// @c-struct\n// @c-pointer\nexport class A { x: int = 0; }",
            "mod.d.deal");
        assertParseError(r, "E7002", "two C markers rejected in extern-C file");
        CompilerDiagnostic two = diagOf(r, "E7002");
        if (two != null) {
            check(two.range().startLine() == 4,
                "two-marker E7002 anchors at the class span (line 4), got "
                    + two.range().startLine());
        }

        // A C marker on a non-class declaration in an extern-C file:
        // E7002 at the declaration node span.
        r = parseFile(
            "// @extern-c\n// @c-struct\nexport function f(): int { return 0; }",
            "mod.d.deal");
        assertParseError(r, "E7002",
            "C marker on an exported function rejected in extern-C file");
        CompilerDirective mark = null;
        // (range pinned: the export declaration span starts on line 3)
        CompilerDiagnostic fn = r.diagnostics().stream()
                .filter(d -> d.code().equals("E7002")
                        && d.range().startLine() == 3)
                .findFirst().orElse(null);
        check(fn != null, "function-marker E7002 anchors at the declaration (line 3)");
    }

    static void testDeclarationFileJsonableNoEffect() {
        System.out.println("-- declaration-file @jsonable no-effect (D4) --");

        // @jsonable on an export class in a .d.deal file: no metadata,
        // no E1043, and explicitly declared C$fromJson/C$toJson
        // signatures are preserved (no synthetic exports are recorded —
        // ExportExtractor's synthetic loop keys on isJsonable()).
        String decl = "// @jsonable\nexport class C { x: int = 0; }\n"
            + "export function C$fromJson(s: string): C | null;\n"
            + "export function C$toJson(c: C): string;";
        ParseResult r = parseFile(decl, "lib.d.deal");
        assertNoParseErrors(r, "declaration-file @jsonable export class");
        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(!cd.isJsonable(),
            "no JSONABLE metadata recorded in a .d.deal file");
        check(cd.directives().isEmpty(),
            "no declaration directives recorded, got " + cd.directives());
        boolean hasE1043 = r.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E1043"));
        check(!hasE1043, "no E1043 for the export-class attachment");

        // The program keeps the explicit C$fromJson/C$toJson declarations.
        check(r.program().statements().size() == 3,
            "three statements (class + two explicit JSON functions), got "
                + r.program().statements().size());

        // Attachment to a non-class declaration warns E1043 with no
        // metadata.
        r = parseFile("// @jsonable\nexport function f(): int { return 0; }",
            "lib.d.deal");
        assertParseError(r, "E1043",
            "declaration-file @jsonable on a function warns E1043");
        CompilerDiagnostic w = diagOf(r, "E1043");
        if (w != null) {
            check(w.severity().equals("warning"),
                "E1043 stays a warning, got " + w.severity());
        }

        // The E1045 argument rule still fires in .d.deal files.
        r = parseFile("// @jsonable:foo\nexport class C { x: int = 0; }",
            "lib.d.deal");
        assertParseError(r, "E1045",
            "@jsonable:foo in a .d.deal file still produces E1045");
    }

    static void testTemplateEmbeddedDirectives() {
        System.out.println("-- template-embedded directive events (D10) --");

        // ${ // @jsonable ... }: exactly one E1043 at the rebased
        // complete-comment range, no metadata.
        String src = "let s = `${ // @jsonable\n x}`;";
        ParseResult r = parse(src);
        long e1043Count = r.diagnostics().stream()
                .filter(d -> d.code().equals("E1043")).count();
        check(e1043Count == 1,
            "exactly one E1043 for the template-embedded @jsonable, got "
                + e1043Count + ": " + r.diagnostics());
        CompilerDiagnostic w = diagOf(r, "E1043");
        if (w != null) {
            // The template token starts at column 9 ("let s = `" is 8
            // scalars + backtick at col 9); content after the backtick:
            // '${' at cols 10-11; the comment starts at col 13.
            check(w.range().origin() == RangeOrigin.SOURCE,
                "rebased E1043 range must be SOURCE");
            check(w.range().startScalarOffset() == 12
                    && w.range().endScalarOffset() == 24
                    && w.range().scalarLength() == 12,
                "E1043 at the rebased complete comment (12,24), got ("
                    + w.range().startScalarOffset() + ","
                    + w.range().endScalarOffset() + ")");
        }
        // No JSONABLE metadata anywhere.
        boolean jsonable = false;
        for (StatementNode stmt : r.program().statements()) {
            if (stmt instanceof ClassDeclaration cd && cd.isJsonable()) {
                jsonable = true;
            }
        }
        check(!jsonable, "no metadata from the template-embedded event");

        // An unknown name inside an interpolation: E1044 at the rebased
        // recovered-name range AND its complete-comment note range in
        // original-source coordinates (D10.8).
        String src2 = "let s = `${ // @nope\n x}`;";
        ParseResult r2 = parse(src2);
        CompilerDiagnostic e1044 = diagOf(r2, "E1044");
        check(e1044 != null, "E1044 for the embedded unknown directive");
        if (e1044 != null) {
            check(e1044.range().origin() == RangeOrigin.SOURCE,
                "E1044 primary range must be SOURCE");
            // The comment starts at original offset 12; '@' at 15; the
            // name 'nope' runs 16..20.
            check(e1044.range().startScalarOffset() == 16
                    && e1044.range().endScalarOffset() == 20,
                "E1044 at the rebased recovered name (16,20), got ("
                    + e1044.range().startScalarOffset() + ","
                    + e1044.range().endScalarOffset() + ")");
            check(e1044.range().startLine() == 1
                    && e1044.range().startColumn() == 17,
                "E1044 at original-source (1,17), got ("
                    + e1044.range().startLine() + ","
                    + e1044.range().startColumn() + ")");
            boolean noteOk = e1044.notes().size() == 1
                    && e1044.notes().get(0).range() != null
                    && e1044.notes().get(0).range().origin()
                        == RangeOrigin.SOURCE
                    && e1044.notes().get(0).range().startScalarOffset() == 12
                    && e1044.notes().get(0).range().endScalarOffset() == 20;
            check(noteOk,
                "E1044 note range in original-source coordinates (12,20): "
                    + e1044.notes());
        }

        // // @deal-version 1.2 inside an interpolation: E1046 (placement —
        // the truthful preceding count is at least 1).
        String src3 = "let s = `${ // @deal-version 1.2\n x}`;";
        ParseResult r3 = parse(src3);
        CompilerDiagnostic e1046 = diagOf(r3, "E1046");
        check(e1046 != null,
            "embedded @deal-version produces E1046 placement, got "
                + r3.diagnostics());

        // A C marker inside an interpolation: E1046 (no extern-C context).
        String src4 = "let s = `${ // @c-struct\n x}`;";
        ParseResult r4 = parse(src4);
        CompilerDiagnostic e1046c = diagOf(r4, "E1046");
        check(e1046c != null,
            "embedded C marker produces E1046, got " + r4.diagnostics());
        if (e1046c != null) {
            check(e1046c.range().startScalarOffset() == 12
                    && e1046c.range().endScalarOffset() == 24,
                "embedded C-marker E1046 at the rebased directive range (12,24)");
        }
    }

}
