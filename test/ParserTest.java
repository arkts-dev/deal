package deal.test;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.lexer.*;
import deal.parser.*;
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

        return new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents()).parse();
    }

    private static ParseResult parseFile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        return new Parser(lex.tokens(), filename,
            lex.directiveEvents()).parse();
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

    @SuppressWarnings("unchecked")
    private static <T extends TypeNode> T assertTypeInstance(TypeNode node, Class<T> clazz,
            String context) {
        if (clazz.isInstance(node)) {
            passed++;
            return (T) node;
        }
        fail(context + ": expected " + clazz.getSimpleName()
             + ", got " + (node == null ? "null" : node.getClass().getSimpleName()));
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
        testNumberLiterals();
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

        // v1.2 grammar boundaries (ISSUE-0717 defect snippets 48, 59, 60)
        testSemicolonStrictness();
        testStraySemicolonRejected();
        testTrailingParameterComma();
        testComparisonChaining();

        // v1.1: Template literals and for-of
        testTemplateLiteralPlain();
        testTemplateLiteralWithExpr();
        testTemplateLiteralLeadingExpr();
        testForOfStatement();
        testForOfMissingIterable();
        testTemplateRawBackslashText();
        testTemplateUnterminatedInterpolation();
        testTemplateNestedBraces();
        testTemplateStringLiteralWithRBrace();
        testTemplateNestedTemplateLiteral();
        testTemplateNestedTemplateWithInterpolation();
        testTemplateRawBackslashBeforeBacktick();
        testTemplateMalformedExpression();
        testTemplateInterpolationRangeAnchors();

        // Precedence
        testPrecedenceAddMul();
        testPrecedenceSubAssoc();
        testPrecedenceAndOr();
        testPrecedenceComparison();
        testExponentiationRejected();

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


        // Edge cases
        testOnlyComments();
        testMultipleStatements();
        testSemicolons();

        testJsonableExportClass();
        testJsonableExportFunction();
        testJsonableStandaloneClass();
        testJsonableStandaloneFunction();
        testJsonableNotPresent();
        testJsonableEndToEndLexParse();

        testAsyncFunctionDeclaration();
        testAwaitExpression();
        testAwaitNotCall();
        testAsyncFunctionType();
        testAsyncFunctionExprInLet();
        testAsyncTypeAnnotation();
        testExportAsyncFunction();
        testAsyncExprStatement();

        // 'null' as a BuiltinType array primary (ISSUE-0879)
        testNullArrayElementTypeSuffixes();

        // @deal-version file directive value validation (DEAL v1.4)
        testDealVersionDirectives();

        testDeclarationFileJsonableNoEffect();
        testTemplateEmbeddedDirectives();

        testTrailingDirectiveEofAnchor();
        testParserDiagnosticScalarExactness();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // @deal-version file directive value validation (DEAL v1.4)
    // =========================================================================

    static void testDealVersionDirectives() {
        ParseResult r = parse("export function probe(): null { return null; }");
        assertNoParseErrors(r, "source needs no file-level version metadata");
        check(r.program().fileDirectives().equals(FileDirectives.empty()),
            "empty source directive metadata");
    }

    static void testTrailingDirectiveEofAnchor() {
        System.out.println("-- Trailing directive comment: end-of-input error anchors at the real EOF --");

        // The trailing line is a directive comment, so the directive
        // attaches to the EOF token; an end-of-input parse error must
        // anchor at the real EOF position with SOURCE origin and exact
        // scalar offsets.
        String source = "let x\u0020: number = \n// @jsonable";
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
            // The line before the comment is 17 scalars (the trailing
            // "= " included), the line break is 1, and the comment line
            // is 12 — the EOF scalar offset is 30.
            check(ScalarSourceCursor.scalarCount(source) == 30,
                "fixture scalar count must be 30, got "
                    + ScalarSourceCursor.scalarCount(source));
            check(d.range().startScalarOffset() == 30
                    && d.range().endScalarOffset() == 30
                    && d.range().scalarLength() == 0,
                "end-of-input error offsets must be (30,30) with zero length, got ("
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
        String source = "let s = \"\uD83D\uDE00\t\";\nlet x\u0020: number = ;";
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
            // statement's prefix ("let x: number = ") is another 17, so
            // the ';' sits at line 2, column 18.
            check(d.range().startLine() == 2 && d.range().startColumn() == 18,
                "E1037 start position must be (2,18), got ("
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

        ParseResult r = parse("let x: number = 42;");
        assertNoParseErrors(r, "let with type");
        assertStmtCount(r.program(), 1, "let with type");
        assertNoParseErrors(r, "span test");
        ProgramNode prog = r.program();
        check(prog.span().startLine() == 1, "program start line");
        check(prog.span().startColumn() == 1, "program start col");
        VariableDeclaration spanned = (VariableDeclaration) prog.statements().get(0);
        check(spanned.span().startLine() == 1, "stmt start line");
        check(spanned.span().startColumn() == 1, "stmt start col");
        check(spanned.span().endLine() == 1, "stmt end line");
        check(spanned.span().endColumn() >= 15, "stmt end col >= 15, got " + spanned.span().endColumn());
        VariableDeclaration vd = assertInstance(r.program().statements().get(0),
                VariableDeclaration.class, "let stmt type");
        if (vd != null) {
            check(vd.name().equals("x"), "var name");
            check(vd.typeAnnotation().isPresent(), "has type annotation");
            check(vd.typeAnnotation().get() instanceof NamedType, "type is NamedType");
            check(vd.initializer() instanceof LiteralExpr, "init is literal");
        }

        r = parse("let y = 100;");
        assertNoParseErrors(r, "let without type");
        vd = assertInstance(r.program().statements().get(0),
                VariableDeclaration.class, "let no type");
        if (vd != null) {
            check(vd.name().equals("y"), "var name y");
            check(vd.typeAnnotation().isEmpty(), "no type annotation");
            check(vd.initializer() instanceof LiteralExpr, "init is literal");
        }
    }

    static void testFunctionDeclaration() {
        System.out.println("-- FunctionDeclaration --");

        ParseResult r = parse("function foo(x: number): number { return x; }");
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
        r = parse("function sum(base: number, ...rest: number[]): number { return base; }");
        assertParseError(r, "E1047", "rest parameter rejected with E1047");

        // External declaration (semicolon body) — flagged in the AST so the
        // post-parse validator can reject it outside .d.deal files (E1051).
        r = parse("function ext(): number;");
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

        ParseResult r = parse("class Point { x: number; y: number; }");
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

        // Presence and nullability are independent; fields have no initializer.
        r = parse("class User { name: string; age?: number | null; }");
        assertNoParseErrors(r, "class with optional nullable field");
        cd = assertInstance(r.program().statements().get(0),
                ClassDeclaration.class, "class optional");
        if (cd != null) {
            check(cd.fields().size() == 2, "field count");
            check(cd.fields().get(1).optional(), "field optional");
            check(cd.fields().get(1).nullable(), "optional field nullable");
        }

        for (String source : new String[] {
                "class C { x: number = 0; }",
                "class C { x?: number = 0; }",
                "class C { x: number | null = null; }",
                "class C { x?: number | null = null; }",
                "class C { f: () => number = (): number => { return 1; }; }" }) {
            assertParseError(parse(source), "E1007", "class initializer rejects");
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

        ParseResult r = parse("function f(): number { return 42; }");
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
        assertParseError(parse("for (let i: number = 0; i < 3; i = i + 1) {}"), "E1013", "C-style for rejected");
    }

    static void testForStatementAssign() {
        assertParseError(parse("for (i = 0; i < 3; i = i + 1) {}"), "E1013", "C-style for rejected");
    }

    static void testForStatementEmpty() {
        assertParseError(parse("for (;;) {}"), "E1007", "C-style for rejected");
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

        ParseResult r = parse("export function foo(): number { return 0; }");
        assertNoParseErrors(r, "export function");
        ExportDeclaration exp = assertInstance(r.program().statements().get(0),
                ExportDeclaration.class, "export func");
        if (exp != null) {
            check(exp.declaration() instanceof FunctionDeclaration, "decl is func");
        }

        r = parse("export class Bar { x: number; }");
        assertNoParseErrors(r, "export class");
        exp = assertInstance(r.program().statements().get(0),
                ExportDeclaration.class, "export class");
        if (exp != null) {
            check(exp.declaration() instanceof ClassDeclaration, "decl is class");
        }

        // Export let is invalid
        r = parse("export let x: number = 1;");
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

        ParseResult r = parse("{ let x: number = 1; let y: number = 2; }");
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
            assertNoParseErrors(r, "call no args");
            CallExpr call = assertExprInstance(es.expr(), CallExpr.class, "call no args");
            if (call != null) check(call.args().isEmpty(), "0 args");
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

        r = parse("let a: number = 42;");
        vd = (VariableDeclaration) r.program().statements().get(0);
        lit = (LiteralExpr) vd.initializer();
        check(((LiteralValue.NumberLiteral) lit.value()).value() == 42, "int lit");

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
    // Binary64 literal parsing
    // =========================================================================

    static void testNumberLiterals() {
        System.out.println("-- Binary64 Literals --");
        String[] literals = {"2147483647", "2147483648", "9007199254740993",
            "1e309", "5e-324", "1e-9999"};
        double[] expected = {2147483647d, 2147483648d, 9007199254740992d,
            Double.POSITIVE_INFINITY, Double.MIN_VALUE, 0d};
        for (int i = 0; i < literals.length; i++) {
            ParseResult r = parse("let a: number = " + literals[i] + ";");
            assertNoParseErrors(r, literals[i]);
            VariableDeclaration declaration = (VariableDeclaration) r.program().statements().get(0);
            LiteralExpr literal = assertExprInstance(declaration.initializer(), LiteralExpr.class,
                literals[i]);
            if (literal != null) check(((LiteralValue.NumberLiteral) literal.value()).value()
                == expected[i], "binary64 value " + literals[i]);
        }
        for (String expression : new String[]{"-2147483648", "-(2147483648)",
                "-2147483649", "2147483648 + 0", "1 - 2147483648", "--2147483648"}) {
            assertNoParseErrors(parse("let a: number = " + expression + ";"), expression);
        }
        ParseResult r = parse("let a: number = -2147483648;");
        VariableDeclaration declaration = (VariableDeclaration) r.program().statements().get(0);
        UnaryExpr unary = assertExprInstance(declaration.initializer(), UnaryExpr.class,
            "negative literals retain their unary operator");
        if (unary != null) {
            check(unary.op() == UnaryOp.NEG, "negative literal operator");
            LiteralExpr literal = assertExprInstance(unary.expr(), LiteralExpr.class,
                "negative literal magnitude");
            if (literal != null) {
                check(((LiteralValue.NumberLiteral) literal.value()).value() == 2147483648d,
                    "positive magnitude");
                check(literal.span().startColumn() == 18 && literal.span().endColumn() == 27,
                    "literal span excludes unary minus");
                check(literal.span().hasScalarOffsets()
                    && literal.span().startScalarOffset() == 17
                    && literal.span().endScalarOffset() == 27, "literal scalar offsets");
            }
        }
        r = parse("let a: string = `x ${-2147483648} y`;");
        assertNoParseErrors(r, "template negative magnitude");
        declaration = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr template = assertExprInstance(declaration.initializer(),
            TemplateLiteralExpr.class, "template");
        if (template != null) {
            check(template.parts().size() == 3, "template parts");
            UnaryExpr part = assertExprInstance(template.parts().get(1), UnaryExpr.class,
                "template retains unary expression");
            if (part != null) check(part.expr() instanceof LiteralExpr literal
                && literal.value() instanceof LiteralValue.NumberLiteral number
                && number.value() == 2147483648d, "template magnitude");
        }
    }

    static void testIdentifierExpression() {
        System.out.println("-- Identifier Expression --");

        ParseResult r = parse("let y: number = x;");
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

        r = parse("let a: number = -5;");
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

        // Trailing comma is outside the v1.2 ArgumentList grammar (E1055);
        // the single argument still parses so recovery keeps the call shape.
        r = parse("f(1,);");
        check(r.diagnostics().stream().anyMatch(d -> d.code().equals("E1055")),
            "call trailing comma -> E1055");
        if (!r.program().statements().isEmpty()) {
            es = (ExpressionStatement) r.program().statements().get(0);
            call = assertExprInstance(es.expr(), CallExpr.class, "call trailing comma");
            if (call != null) check(call.args().size() == 1, "1 arg with trailing comma");
        }
    }

    /**
     * v1.4 Semicolon rules (docs/spec-v1.5.md Syntactic grammar): every
     * production carrying {@code ';'} consumes one — imports, class fields,
     * variable/expression/return/break/continue/delete/throw statements and
     * the C-style for separators. Block-ending productions (blocks,
     * if/while/for/try, class and function declarations) end at {@code '}'}
     * and carry no added requirement.
     */
    static void testSemicolonStrictness() {
        System.out.println("-- Semicolon rules (v1.4) --");

        // Let before a following statement.
        ParseResult beforeStatement = parse("export function main(): null {\n"
            + "  let n: number = 1\n"
            + "  one(n);\n"
            + "  return null;\n"
            + "}\n");
        assertParseError(beforeStatement, "E1053",
            "let before an expression statement");

        // Let before return (the old whitelist admitted this).
        ParseResult beforeReturn = parse("export function main(): null {\n"
            + "  let n: number = 1\n"
            + "  return null;\n"
            + "}\n");
        assertParseError(beforeReturn, "E1053", "let before return");

        // Let before a try statement.
        ParseResult beforeTry = parse("export function main(): null {\n"
            + "  let n: number = 1\n"
            + "  try {} catch (e) {}\n"
            + "  return null;\n"
            + "}\n");
        assertParseError(beforeTry, "E1053", "let before try");

        // Let before an async function declaration.
        ParseResult beforeAsync = parse("export function main(): null {\n"
            + "  let n: number = 1\n"
            + "  async function f(): Promise<null> { return null; }\n"
            + "  return null;\n"
            + "}\n");
        assertParseError(beforeAsync, "E1053", "let before async function");

        // Return and break/continue before the closing '}'.
        assertParseError(parse("function f(): number { return 1 }"), "E1053",
            "return before '}'");
        assertParseError(parse("function f(): null { while (true) { break } return null; }"),
            "E1053", "break before '}'");
        assertParseError(parse("function f(): null { while (true) { continue } return null; }"),
            "E1053", "continue before '}'");

        // Missing terminator at end of input.
        assertParseError(parse("let x: number = 1"), "E1053",
            "let at end of input");

        // Class field and import terminators.
        assertParseError(parse("class C { x: number }"), "E1053",
            "class field before '}'");
        assertParseError(parse("import * as m from \"m\"\nexport class C { x: number; }"),
            "E1053", "import before the next declaration");

        // Delete, throw and expression statements.
        assertParseError(parse("function f(t: Record<string, unknown>): null { delete t.x return null; }"),
            "E1053", "delete before return");
        assertParseError(parse("function f(): null { throw { code: \"x\", message: \"y\" } return null; }"),
            "E1053", "throw before return");
        assertParseError(parse("function f(): null { f() return null; }"),
            "E1053", "expression statement before return");

        // Explicit terminators compile clean, and block-ending forms need
        // no added ';'.
        ParseResult ok = parse("export function main(): null {\n  let n: number = 1;\n  if (n === 1) { n = 2; } else { n = 3; }\n  while (n < 10) { n = n + 1; }\n  { let v14Loop1: number = 0; while (v14Loop1 < 1) { let i: number = v14Loop1; { n = n + 1; } v14Loop1 = i; v14Loop1 = v14Loop1 + 1; } }\n  try { n = 4; } catch (e) { n = 5; }\n  return null;\n}\nclass C { x: number; }\nfunction g(): null { return null; }\n");
        assertNoParseErrors(ok, "explicit terminators and block endings");
    }

    /**
     * The v1.4 Syntactic grammar has no empty-statement production: a
     * statement-position {@code ';'} is outside {@code Program},
     * {@code Statement*}, and {@code Block}. Both the module top level and
     * a block report E1059 at the semicolon's own scalar range — including
     * the old optional-semicolon form after a block-ending declaration —
     * while every required terminator stays owned by its production.
     */
    static void testStraySemicolonRejected() {
        System.out.println("-- Stray statement-position ';' (v1.4 grammar) --");

        // Top level: a bare ';' produces no statement.
        ParseResult bare = parse(";");
        assertParseError(bare, "E1059", "bare top-level semicolon");
        assertStmtCount(bare.program(), 0, "bare top-level semicolon");

        // Top level: the old optional terminator after a block-ending
        // function declaration is not a production position.
        ParseResult afterDeclaration = parse(
            "function g(): null { return null; };\n");
        assertParseError(afterDeclaration, "E1059",
            "semicolon after a block-ending declaration");
        assertStmtCount(afterDeclaration.program(), 1,
            "semicolon after a block-ending declaration");

        // Block level: a stray ';' as the first statement and after a
        // block-ending if statement.
        ParseResult blockStart = parse(
            "export function main(): null {\n  ;\n  return null;\n}\n");
        assertParseError(blockStart, "E1059", "block-leading semicolon");
        ParseResult afterBlock = parse(
            "function f(): null { if (true) { } ; return null; }\n");
        assertParseError(afterBlock, "E1059",
            "semicolon after a block-ending statement");

        // The diagnostic is a SOURCE error at the ';' itself.
        ParseResult ranged = parseFile(
            "export function main(): null {\n  ;\n  return null;\n}\n",
            "stray.deal");
        CompilerDiagnostic stray = diagOf(ranged, "E1059");
        if (stray == null) {
            fail("stray semicolon: missing E1059 diagnostic");
        } else {
            check("error".equals(stray.severity()),
                "stray semicolon severity is error, got " + stray.severity());
            check(stray.line() == 2 && stray.column() == 3,
                "stray semicolon at (2,3), got (" + stray.line() + ","
                    + stray.column() + ")");
            check(stray.range().scalarLength() == 1,
                "stray semicolon span length 1, got "
                    + stray.range().scalarLength());
            check(stray.range().origin() == RangeOrigin.SOURCE,
                "stray semicolon range is SOURCE, got "
                    + stray.range().origin());
            check("stray.deal".equals(stray.file()),
                "stray semicolon keeps the file, got " + stray.file());
        }

        // A ';' that a production carries is that production's own
        // terminator — never a stray statement.
        ParseResult owned = parse(
            "export function main(): null {\n"
            + "  let f: () => null = function(): null { return null; };\n"
            + "  return null;\n"
            + "}\n");
        assertNoParseErrors(owned, "terminator owned by a declaration");
    }

    /**
     * ParameterList carries no trailing comma (spec §Syntactic grammar);
     * the array and object literal lists keep the permitted commas
     * (ISSUE-0717 defect snippet 60).
     */
    static void testTrailingParameterComma() {
        System.out.println("-- Trailing parameter comma --");

        ParseResult r = parse("function one(x: number,): number { return x; }\n");
        assertParseError(r, "E1054", "declaration parameter trailing comma");
        if (!r.program().statements().isEmpty()
                && r.program().statements().get(0)
                    instanceof FunctionDeclaration fd) {
            check(fd.params().size() == 1,
                "the single parameter still parses (recovery keeps the shape)");
        }

        ParseResult functionType = parse(
            "let f: (x: number,) => number = function(x: number): number { return x; };\n");
        assertParseError(functionType, "E1054",
            "function-type parameter trailing comma");

        ParseResult literals = parse(
            "let a: number[] = [1, 2,];\nlet o: Record<string, unknown> = { x: 1, };\n");
        assertNoParseErrors(literals, "array and object literal trailing commas");
    }

    /**
     * The relational and equality comparison groups are non-associative
     * (spec §Operator precedence): an unparenthesized chain is E1056
     * (ISSUE-0717 defect snippet 59).
     */
    static void testComparisonChaining() {
        System.out.println("-- Comparison non-associativity --");

        ParseResult equality = parse(
            "let b: boolean = true === true === true;\n");
        assertParseError(equality, "E1056", "equality chaining");

        ParseResult relational = parse("let b: boolean = 1 < 2 < 3;\n");
        assertParseError(relational, "E1056", "relational chaining");

        ParseResult parenthesized = parse(
            "let b: boolean = (true === true) === true;\n"
                + "let c: boolean = (1 < 2) === (3 < 4);\n");
        assertNoParseErrors(parenthesized, "parenthesized comparisons");
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

        ParseResult r = parse("let a: number[] = [1, 2, 3];");
        assertNoParseErrors(r, "array literal");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        ArrayLiteralExpr arr = assertExprInstance(vd.initializer(), ArrayLiteralExpr.class, "array");
        if (arr != null) check(arr.elements().size() == 3, "3 elements");

        r = parse("let a: number[] = [];");
        assertNoParseErrors(r, "empty array");
        vd = (VariableDeclaration) r.program().statements().get(0);
        arr = assertExprInstance(vd.initializer(), ArrayLiteralExpr.class, "empty array");
        if (arr != null) check(arr.elements().isEmpty(), "0 elements");

        // Trailing comma
        r = parse("let a: number[] = [1,];");
        assertNoParseErrors(r, "array trailing comma");
        vd = (VariableDeclaration) r.program().statements().get(0);
        arr = assertExprInstance(vd.initializer(), ArrayLiteralExpr.class,
                "array trailing comma");
        if (arr != null) check(arr.elements().size() == 1, "1 element trailing comma");
    }

    static void testObjectLiteral() {
        System.out.println("-- Object Literal --");

        ParseResult r = parse("let t: Record<string, unknown> = { name: \"A\", age: 30 };");
        assertNoParseErrors(r, "object literal");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        ObjectLiteralExpr obj = assertExprInstance(vd.initializer(), ObjectLiteralExpr.class,
                "object");
        if (obj != null) {
            check(obj.properties().size() == 2, "2 properties");
            check(obj.properties().get(0).name().equals("name"), "prop 0 name");
            check(obj.properties().get(1).name().equals("age"), "prop 1 name");
        }

        r = parse("let t: Record<string, unknown> = {};");
        assertNoParseErrors(r, "empty object");
        vd = (VariableDeclaration) r.program().statements().get(0);
        obj = assertExprInstance(vd.initializer(), ObjectLiteralExpr.class, "empty object");
        if (obj != null) check(obj.properties().isEmpty(), "0 properties");
    }

    static void testFunctionExpression() {
        System.out.println("-- Function Expression --");

        ParseResult r = parse("let f = function(x: number): number { return x; };");
        assertNoParseErrors(r, "func expr");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        FunctionExpr fe = assertExprInstance(vd.initializer(), FunctionExpr.class, "func expr");
        if (fe != null) {
            check(fe.params().size() == 1, "1 param");
            check(fe.returnType() instanceof NamedType, "return type");
            check(fe.body().statements().size() == 1, "1 body stmt");
        }

        // Function expression as expression statement
        r = parse("(function(): number { return 0; });");
        assertNoParseErrors(r, "func expr as stmt");
    }

    static void testHasExpression() {
        System.out.println("-- Own Presence Expression --");
        ParseResult ordinary = parse("has(obj.field);");
        assertNoParseErrors(ordinary, "has is an ordinary identifier call");
        check(((ExpressionStatement) ordinary.program().statements().get(0)).expr() instanceof CallExpr,
            "retired has intrinsic is not a presence AST");

        ParseResult r = parse("let b: boolean = Object.hasOwn(obj, \"field\");");
        assertNoParseErrors(r, "has expr");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        HasExpr has = assertExprInstance(vd.initializer(), HasExpr.class, "has");
        if (has != null) {
            check(has.field().equals("field"), "field name");
            check(has.object() instanceof IdentifierExpr, "object");
        }

        // Negated own-presence test.
        r = parse("let b: boolean = !Object.hasOwn(obj, \"field\");");
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

        ParseResult r = parse("let a: number = 1 + 2 * 3;");
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

        ParseResult r = parse("let a: number = 1 - 2 - 3;");
        assertNoParseErrors(r, "sub assoc");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        BinaryExpr bin = (BinaryExpr) vd.initializer();
        // Left-associative: (1 - 2) - 3
        check(bin.op() == BinaryOp.SUB, "top op is SUB");
        check(bin.right() instanceof LiteralExpr, "right is literal 3");
        check(((LiteralExpr) bin.right()).value() instanceof LiteralValue.NumberLiteral il
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

    static void testExponentiationRejected() {
        System.out.println("-- Exponentiation rejection (v1.4) --");

        // '**' is not in the v1.4 operator set, so it lexes as two '*'
        // tokens and the second one has no operand (E1037). No POW node
        // can enter the AST.
        ParseResult r = parse("let a: number = 2 ** 3;");
        assertParseError(r, "E1037", "exponentiation is not admitted");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        check(!(vd.initializer() instanceof BinaryExpr bin)
                || bin.op() != BinaryOp.POW,
            "no POW node is produced for '**'");

        // The removed operator is rejected regardless of the int32 profile.
        LexResult lex = new Lexer("let a: number = 2 ** 3;", "test.deal").tokenize();
        check(lex.tokens().stream().filter(t -> t.type() == TokenType.STAR).count() == 2,
            "'**' lexes as two STAR tokens");
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

        ParseResult r = parse("while (a) { {  while (true) { { break; }  } } }");
        assertNoParseErrors(r, "nested loops");
    }

    static void testClassInFunction() {
        System.out.println("-- Class in Function --");

        ParseResult r = parse("function f(): null { class Inner { x: number; } return; }");
        assertNoParseErrors(r, "class in function");
    }

    static void testFunctionInClass() {
        System.out.println("-- Function in Class --");

        // Functions aren't class members in DEAL v0.6, but a class body can
        // contain function declarations as statements inside methods.
        // Actually, class fields are only field declarations. Functions in classes
        // aren't a thing yet. Let's just test that parsing doesn't crash.
        // Instead, test a function declaration inside a function body.
        ParseResult r = parse("function outer(): null { function inner(): number { return 0; } return; }");
        assertNoParseErrors(r, "function in function");
    }

    static void testFuncExprInCall() {
        System.out.println("-- Function Expression in Call --");

        ParseResult r = parse("f(function(x: number): number { return x; });");
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

        ParseResult r = parse("function f(): number { return 0; \nlet x: number = 1;");
        // Missing closing brace should produce E1006
        boolean hasE1006 = r.diagnostics().stream().anyMatch(d -> d.code().equals("E1006"));
        check(hasE1006, "error recovery: has E1006");
        // Should still parse the 'let' statement after recovery
        check(r.program().statements().size() >= 1, "at least one statement after error");
    }

    static void testErrorRecoveryMidFile() {
        System.out.println("-- Error Recovery: mid-file --");

        ParseResult r = parse("let a: number = 1;\nlet b: number =\nlet c: number = 3;");
        // let b: number = (missing expression) should produce an error
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
            "let a: number = 1;\n" +
            "let b: number = ;\n" +     // error: missing initializer
            "let c: number = 3;\n" +
            "let d: number = ;\n" +     // error: missing initializer
            "let e: number = 5;");

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
        ParseResult r = parse("x = ;\nlet y: number = 1;");
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
        ParseResult r = parse("let a: number = 1 + ;\nlet b: number = 2;");
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
        ParseResult r = parse("let a: number = + * 5;\nlet b: number = 2;");
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "flaw3: produced E1xxx diagnostic");
        check(r.program().statements().size() >= 1,
            "flaw3: at least 1 statement recovered, got " + r.program().statements().size());
    }

    static void testErrorRecoveryHasExprNull() {
        System.out.println("-- Error Recovery: has() expression null (Flaw 4 fix) --");

        // has(;)  —  has() with a non-expression inside
        ParseResult r = parse("let b: boolean = has(;);\nlet y: number = 1;");
        boolean hasE1xxx = r.diagnostics().stream()
                .anyMatch(d -> d.code().startsWith("E1"));
        check(hasE1xxx, "flaw4: produced E1xxx diagnostic");
        check(r.program().statements().size() >= 1,
            "flaw4: at least 1 statement recovered, got " + r.program().statements().size());
    }

    static void testErrorRecoveryUnaryOperandNull() {
        System.out.println("-- Error Recovery: unary operand null --");

        // !;  —  unary NOT with missing operand
        ParseResult r = parse("let a: boolean = !;\nlet b: number = 2;");
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
        ParseResult r = parse("let x: number = 1; }\nlet y: number = 2;");
        boolean hasE1041 = r.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E1041"));
        check(hasE1041, "f1-fix: produced E1041 diagnostic");
        // Must have parsed both let statements (x and y) despite the stray }
        check(r.program().statements().size() >= 2,
            "f1-fix: at least 2 statements recovered, got " + r.program().statements().size());
    }

    static void testReturnFunctionExpr() {
        System.out.println("-- ReturnStatement with function expression (F2 fix) --");

        // return function(): number { return 0; }
        // Must parse as a single ReturnStatement wrapping a FunctionExpr,
        // not as a bare return followed by an expression statement.
        ParseResult r = parse("function f(): number { return function(): number { return 0; }; }");
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

        ParseResult r = parse("function f(xs: number[]): null { for (let x of xs) { } return null; }");
        assertNoParseErrors(r, "for-of array");
        FunctionDeclaration fd = (FunctionDeclaration) r.program().statements().get(0);
        check(fd.body().statements().size() == 2, "2 body stmts");
        ForOfStatement fos = assertInstance(fd.body().statements().get(0),
                ForOfStatement.class, "ForOfStatement");
        if (fos != null) {
            check(fos.varName().equals("x"), "var name = x");
            check(fos.varType() == null, "for-of type is inferred, not annotated");
            check(fos.iterable() instanceof IdentifierExpr, "iterable is identifier");
            check(((IdentifierExpr) fos.iterable()).name().equals("xs"), "iterable = xs");
        }

        assertParseError(parse("function f(xs: number[]): null { for (let x: number of xs) {} return null; }"),
            "E1013", "explicit for-of annotation is rejected");

        // For-of over string
        r = parse("function f(s: string): null { for (let c of s) { } return null; }");
        assertNoParseErrors(r, "for-of string");
        fd = (FunctionDeclaration) r.program().statements().get(0);
        fos = assertInstance(fd.body().statements().get(0),
                ForOfStatement.class, "for-of string");
        if (fos != null) {
            check(fos.varName().equals("c"), "var name = c");
            check(fos.varType() == null, "string loop binding is inferred");
        }
    }

    static void testForOfMissingIterable() {
        System.out.println("-- ForOfStatement: missing iterable (D20) --");

        // Parse the for-of with missing iterable. Should not NPE.
        ParseResult r = parse("function f(): null { for (let x of ) { } return null; }");
        // We just check it doesn't throw — error diagnostics are expected
        check(r.program() != null, "program not null");
        // The for-of statement should not be present in the AST because the parser
        // should have called synchronize() and returned null.
        System.out.println("    (D20: for-of with missing iterable handled gracefully)");
    }

    static void testTemplateRawBackslashText() {
        System.out.println("-- Template Literal: raw backslash text (v1.4) --");

        // v1.4 TemplateText has no escape mechanism: the backslash and the
        // character after it are ordinary text.
        ParseResult r = parse("let x = `hello \\q world`;");
        assertNoParseErrors(r, "raw backslash is ordinary TemplateText");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = assertExprInstance(vd.initializer(),
            TemplateLiteralExpr.class, "raw backslash template");
        if (tl != null) {
            check(tl.parts().size() == 1, "one text part, got " + tl.parts().size());
            if (tl.parts().get(0) instanceof LiteralExpr part
                    && part.value() instanceof LiteralValue.StringLiteral sl) {
                check(sl.value().equals("hello \\q world"),
                    "verbatim text, got '" + sl.value() + "'");
            }
        }
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

        // A nested template literal inside ${} is written with raw backticks:
        // the lexer's interpolation state keeps them from closing the outer
        // template, and the sub-lexer parses the nested literal itself.
        ParseResult r = parse("let x = `${ `nested` }`;");
        assertNoParseErrors(r, "nested template parses clean");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = (TemplateLiteralExpr) vd.initializer();
        if (tl != null && tl.parts().size() == 3) {
            ExpressionNode expr = tl.parts().get(1);
            check(expr instanceof TemplateLiteralExpr,
                "expr part is the nested TemplateLiteralExpr, got "
                    + (expr == null ? "null" : expr.getClass().getSimpleName()));
        } else {
            fail("nested template: expected a 3-part template literal");
        }
    }

    static void testTemplateNestedTemplateWithInterpolation() {
        System.out.println("-- Template Literal: nested template with its own"
            + " interpolation --");

        ParseResult r = parse("let x = `${ `a${b}c` }`;");
        assertNoParseErrors(r, "nested template with interpolation parses clean");
        VariableDeclaration vd = (VariableDeclaration) r.program().statements().get(0);
        TemplateLiteralExpr tl = (TemplateLiteralExpr) vd.initializer();
        if (tl != null && tl.parts().size() == 3
                && tl.parts().get(1) instanceof TemplateLiteralExpr nested) {
            check(nested.parts().size() == 3,
                "nested template has 3 parts, got " + nested.parts().size());
        } else {
            fail("nested template with interpolation: unexpected shape");
        }
    }

    static void testTemplateRawBackslashBeforeBacktick() {
        System.out.println("-- Template Literal: raw backslash does not shield"
            + " a backtick --");

        // A backslash is ordinary TemplateText, so the backtick after it
        // closes the template and the remaining backtick opens a new
        // (unterminated) literal.
        LexResult lex = new Lexer("let x = `a\\`b`;", "test.deal").tokenize();
        boolean e1003 = lex.diagnostics().stream()
            .anyMatch(d -> d.code().equals("E1003"));
        check(e1003, "the unterminated trailing literal reports E1003");
        ParseResult r = parse("let x = `a\\`b`;");
        check(r.program() != null, "raw backslash before backtick parses");
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

        // 3. A sub-lexer error after a supplementary scalar inside the
        //    interpolation rebases verbatim through the scalar map: the
        //    decoded '@' maps to the raw '@' position, not a
        //    UTF-16-coordinate position.
        String src3 = "let x = `${\"\uD83D\uDE00\" + @}`;";
        ParseResult r3 = parse(src3);
        CompilerDiagnostic e1001 = findDiag(r3.diagnostics(), "E1001");
        check(e1001 != null, "T3: E1001 present for the interpolation error");
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

        // 6. E1042 pseudo-tokens are raw-positioned and scalar-exact after
        //    astral TemplateText.
        String src6 = "let x = `hi\uD83D\uDE00${foo`;";
        ParseResult r6 = parse(src6);
        CompilerDiagnostic e1042b = findDiag(r6.diagnostics(), "E1042");
        check(e1042b != null,
            "T6: E1042 present for the unterminated interpolation after astral");
        if (e1042b != null) {
            checkRange(e1042b.range(), "test.deal", 1, 15, 1, 17, 14, 16, 2,
                RangeOrigin.SOURCE,
                "T6: unterminated-interpolation pseudo-token raw-positioned");
            checkCursorAt(src6, 14, 1, 15, "T6");
        }

        // 7. The one-complete-expression rule: a leftover token is E1042 at
        //    its own rebased token position.
        String src7 = "let x = `a${b c}`;";
        ParseResult r7 = parse(src7);
        CompilerDiagnostic e1042c = findDiag(r7.diagnostics(), "E1042");
        check(e1042c != null, "T7: E1042 present for the trailing token");
        if (e1042c != null) {
            checkRange(e1042c.range(), "test.deal", 1, 15, 1, 16, 14, 15, 1,
                RangeOrigin.SOURCE, "T7: trailing-token anchor");
            checkCursorAt(src7, 14, 1, 15, "T7");
        }

        // 7. Empty expression: E1042 and the D16 placeholder carry zero
        //    scalar length at the expression-start raw position.
        String src8 = "let x = `${}`;";
        ParseResult r8 = parse(src8);
        assertParseError(r8, "E1042", "empty interpolation -> E1042");
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
            "let a: number = 1;\n" +
            "let b: number = 2;\n" +
            "let c: number = 3;");
        assertNoParseErrors(r, "multiple stmts");
        assertStmtCount(r.program(), 3, "3 statements");
    }

    static void testSemicolons() {
        System.out.println("-- Semicolon Rules --");

        // With semicolon
        ParseResult r1 = parse("let x: number = 1;");
        assertNoParseErrors(r1, "with semicolon");
        assertStmtCount(r1.program(), 1, "with semicolon");

        // v1.4 requires the terminator: a statement followed by '}' is
        // E1053 (the statement still parses for recovery).
        ParseResult r2 = parse("{ let x: number = 1 }");
        assertParseError(r2, "E1053", "without semicolon before }");
        Block b = (Block) r2.program().statements().get(0);
        check(b.statements().size() == 1, "1 stmt in block without semicolon");

        // Missing terminator at end of input is E1053 too.
        ParseResult r3 = parse("let x: number = 1");
        assertParseError(r3, "E1053", "without semicolon at EOF");
        assertStmtCount(r3.program(), 1, "1 stmt without semicolon at EOF");
    }

    static void testJsonableExportClass() {
        System.out.println("-- @jsonable export class -> isJsonable=true --");

        ParseResult r = parse("// @jsonable\nexport class C { x: number; }");
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
        System.out.println("-- @jsonable export function -> E1043 error --");

        ParseResult r = parse("// @jsonable\nexport function f(): number { return 0; }");
        assertParseError(r, "E1043", "@jsonable export function");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "E1043 diagnostic present for export function");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("error"),
                "severity is error, got: " + warnDiag.severity());
            check(warnDiag.message().contains("export class"),
                "message mentions export class: " + warnDiag.message());

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
        System.out.println("-- @jsonable standalone class -> E1043 error, isJsonable=false --");

        ParseResult r = parse("// @jsonable\nclass C { x: number; }");
        assertParseError(r, "E1043", "@jsonable standalone class");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "E1043 diagnostic present for standalone class");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("error"),
                "severity is error, got: " + warnDiag.severity());
        }

        // isJsonable should be false: an incompatible attachment applies no
        // metadata.
        ProgramNode prog = r.program();
        assertStmtCount(prog, 1, "standalone class");
        ClassDeclaration cd = assertInstance(prog.statements().get(0),
                ClassDeclaration.class, "standalone class node");
        check(!cd.isJsonable(),
            "ClassDeclaration.isJsonable should be false - directive ignored");
        assertJsonableErrorSeverity(r);
        assertStmtCount(prog, 1, "one statement");
        ClassDeclaration invalid = assertInstance(prog.statements().get(0),
                ClassDeclaration.class, "class node");
        check(!invalid.isJsonable(),
            "isJsonable should be false after invalid placement warning");
    }

    static void testJsonableStandaloneFunction() {
        System.out.println("-- @jsonable standalone function -> E1043 error --");

        ParseResult r = parse("// @jsonable\nfunction f(): number { return 0; }");
        assertParseError(r, "E1043", "@jsonable standalone function");

        List<CompilerDiagnostic> diags = r.diagnostics();
        CompilerDiagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "E1043 diagnostic present for standalone function");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("error"),
                "severity is error, got: " + warnDiag.severity());
        }
    }

    static void testJsonableNotPresent() {
        System.out.println("-- no @jsonable -> isJsonable=false --");

        ParseResult r = parse("export class C { x: number; }");
        assertNoParseErrors(r, "export class without @jsonable");

        ExportDeclaration ed = (ExportDeclaration) r.program().statements().get(0);
        ClassDeclaration cd = (ClassDeclaration) ed.declaration();
        check(!cd.isJsonable(),
            "ClassDeclaration.isJsonable should be false without directive");
    }

    private static void assertJsonableErrorSeverity(ParseResult r) {
        for (CompilerDiagnostic d : r.diagnostics()) {
            if (d.code().equals("E1043")) {
                check(d.severity().equals("error"),
                    "severity should be 'error', got: '" + d.severity() + "'");
            }
        }
        check(r.hasErrors(),
            "an incompatible directive attachment is a compile error");
    }

    // =========================================================================
    // Async/Await parser tests
    // =========================================================================

    static void testAsyncFunctionDeclaration() {
        System.out.println("-- Async Function Declaration --");

        ParseResult r = parse("async function f(): Promise<number> { return await g(); }");
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

        ParseResult r = parse("async function f(): Promise<number> { await g(1, 2); }");
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

        ParseResult r = parse("async function f(): Promise<number> { await 42; }");
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

        // Test: let f: async (number) => string;
        ParseResult r = parse("let f: (p: number) => Promise<string> = g;");
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
        ParseResult r2 = parse("let f: (a: number, b: string) => Promise<boolean> = h;");
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

        ParseResult r = parse("let f = async function(): Promise<number> { return 5; };");
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

        // let f: async (number) => string;
        // But since let requires an initializer, use a valid one
        ParseResult r = parse("let f: (p: number) => Promise<string> = someFunc;");
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

        ParseResult r = parse("export async function f(): Promise<number> { return 5; }");
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
        ParseResult r = parse("async function(): Promise<number> { return 5; };");
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
        // incompatible-attachment E1043 error through the event pipeline.
        LexResult lex = new Lexer("// @jsonable\nlet x: number = 1;", "test.deal").tokenize();
        Parser parser = new Parser(lex.tokens(), "test.deal",
            lex.directiveEvents());
        ParseResult r = parser.parse();
        assertParseError(r, "E1043", "@jsonable let");
        CompilerDiagnostic warnDiag = diagOf(r, "E1043");
        check(warnDiag != null, "E1043 diagnostic present for let");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("error"),
                "severity is error, got: " + warnDiag.severity());
        }

        List<CompilerDiagnostic> diags = r.diagnostics();
        boolean foundError = diags.stream().anyMatch(
                d -> d.code().equals("E1043") && d.severity().equals("error"));
        check(foundError, "end-to-end: E1043 with error severity");

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
    // 'null' as a BuiltinType array primary: the ArrayType '[]' suffixes
    // (ISSUE-0879)
    // =========================================================================

    /** The declared type node of the single `let` statement's annotation. */
    private static TypeNode letAnnotationType(String source) {
        ParseResult r = parse(source);
        assertNoParseErrors(r, source);
        if (r.program().statements().size() != 1) {
            fail(source + ": expected one statement, got "
                + r.program().statements().size());
            return null;
        }
        VariableDeclaration vd = assertInstance(r.program().statements().get(0),
            VariableDeclaration.class, source);
        if (vd == null || vd.typeAnnotation().isEmpty()) {
            fail(source + ": expected a declared type annotation");
            return null;
        }
        return vd.typeAnnotation().get();
    }

    /** Checks that an array node's element chain is rooted at the null primary. */
    private static void checkNullElement(ArrayType array, String context) {
        if (array == null) {
            return;
        }
        NamedType element = assertTypeInstance(array.elementType(), NamedType.class,
            context);
        if (element != null) {
            check(element.name().equals("null"), context
                + ": element type is null, got " + element.name());
        }
    }

    static void testNullArrayElementTypeSuffixes() {
        System.out.println("-- null as a BuiltinType array primary (ISSUE-0879) --");

        // null[] — one suffix over the null builtin primary.
        checkNullElement(assertTypeInstance(letAnnotationType("let xs: null[] = [null];"),
            ArrayType.class, "null[] annotation"), "null[]");

        // null[][] — repeated suffixes nest the array constructor.
        ArrayType nested = assertTypeInstance(
            letAnnotationType("let xs: null[][] = [[null]];"),
            ArrayType.class, "null[][] annotation");
        checkNullElement(assertTypeInstance(nested == null ? null : nested.elementType(),
            ArrayType.class, "null[][] inner array"), "null[][]");

        // The parenthesized (null)[] control keeps the same element type.
        checkNullElement(assertTypeInstance(
            letAnnotationType("let xs: (null)[] = [null];"),
            ArrayType.class, "(null)[] annotation"), "(null)[]");

        // null[] | null and null | null[] both yield Nullable<null[]>.
        for (String source : List.of("let xs: null[] | null = null;",
                "let xs: null | null[] = null;")) {
            NullableType nullable = assertTypeInstance(letAnnotationType(source),
                NullableType.class, source);
            checkNullElement(assertTypeInstance(
                nullable == null ? null : nullable.innerType(),
                ArrayType.class, source + " inner"), source);
        }

        // The 'null |' prefix still wraps a non-null array element type.
        NullableType prefix = assertTypeInstance(
            letAnnotationType("let xs: null | number[] = null;"),
            NullableType.class, "null | number[]");
        ArrayType intArray = assertTypeInstance(prefix == null ? null : prefix.innerType(),
            ArrayType.class, "null | number[] inner");
        NamedType intElement = assertTypeInstance(
            intArray == null ? null : intArray.elementType(), NamedType.class,
            "null | number[] element");
        check(intElement != null && intElement.name().equals("number"),
            "null | number[] keeps the number element, got "
                + (intElement == null ? "null" : intElement.name()));

        // null | null stays the parser's Nullable(null) node, so the
        // checker's nullable invariant (E3005) still reports it instead
        // of the frontend flattening it into an admitted spelling.
        NullableType duplicated = assertTypeInstance(
            letAnnotationType("let xs: null | null = null;"),
            NullableType.class, "null | null");
        NamedType nullInner = assertTypeInstance(
            duplicated == null ? null : duplicated.innerType(), NamedType.class,
            "null | null inner");
        check(nullInner != null && nullInner.name().equals("null"),
            "null | null keeps the null inner for the checker's E3005 arm");

        // Function types reach the same suffixes through their parameter
        // and return positions.
        FunctionType functionType = assertTypeInstance(
            letAnnotationType("let f: (xs: null[]) => null[][] = g;"),
            FunctionType.class, "(xs: null[]) => null[][]");
        if (functionType != null && !functionType.params().isEmpty()) {
            checkNullElement(assertTypeInstance(functionType.params().get(0).type(),
                ArrayType.class, "null[] function-type parameter"),
                "null[] function-type parameter");
        }
        ArrayType functionReturn = assertTypeInstance(
            functionType == null ? null : functionType.returnType(),
            ArrayType.class, "null[][] function-type return");
        checkNullElement(assertTypeInstance(
            functionReturn == null ? null : functionReturn.elementType(),
            ArrayType.class, "null[][] function-type return inner"),
            "null[][] function-type return");

        // Every annotation position reaches the same Array<null> node:
        // class field, parameter, return type, and inferred for-of binding.
        String positions = "class Holder { xs: null[]; }\n"
            + "function f(xs: null[]): null[] { return xs; }\n"
            + "export function main(): null {\n"
            + "  for (let row of [[null]]) { }\n"
            + "  return null;\n"
            + "}";
        ParseResult atPositions = parse(positions);
        assertNoParseErrors(atPositions,
            "null[] annotations and inferred for-of binding");
        ClassDeclaration holder = assertInstance(
            atPositions.program().statements().get(0), ClassDeclaration.class,
            "null[] class");
        if (holder != null && !holder.fields().isEmpty()) {
            checkNullElement(assertTypeInstance(holder.fields().get(0).type(),
                ArrayType.class, "null[] field"), "null[] field");
        }
        FunctionDeclaration f = assertInstance(
            atPositions.program().statements().get(1), FunctionDeclaration.class,
            "null[] function");
        if (f != null && !f.params().isEmpty()) {
            checkNullElement(assertTypeInstance(f.params().get(0).type(),
                ArrayType.class, "null[] parameter"), "null[] parameter");
        }
        if (f != null) {
            checkNullElement(assertTypeInstance(f.returnType(), ArrayType.class,
                "null[] return type"), "null[] return type");
        }
        ExportDeclaration exported = assertInstance(
            atPositions.program().statements().get(2), ExportDeclaration.class,
            "null[] exported main");
        FunctionDeclaration main = exported == null ? null : assertInstance(
            exported.declaration(), FunctionDeclaration.class, "main declaration");
        StatementNode first = main == null || main.body().statements().isEmpty()
            ? null : main.body().statements().get(0);
        ForOfStatement forOf = assertInstance(first, ForOfStatement.class,
            "null[] for-of loop");
        if (forOf != null) {
            check(forOf.varType() == null, "null[] loop binding annotation is absent");
        }

        // Rejected spellings keep their exact parse diagnostics.
        ParseResult union = parse("let xs: null[] | number = null;");
        CompilerDiagnostic e1030 = diagOf(union, "E1030");
        check(e1030 != null, "null[] | number reports E1030, got "
            + union.diagnostics());
        if (e1030 != null) {
            check(e1030.message().equals(
                    "Expected 'null' after '|' in nullable type"),
                "E1030 keeps its exact message, got: " + e1030.message());
        }
        ParseResult bracket = parse("let xs: null[ = null;");
        CompilerDiagnostic e1031 = diagOf(bracket, "E1031");
        check(e1031 != null, "null[ reports E1031, got " + bracket.diagnostics());
        if (e1031 != null) {
            check(e1031.message().equals("Expected ']' in array type"),
                "E1031 keeps its exact message, got: " + e1031.message());
        }
    }

    static void testDeclarationFileJsonableNoEffect() {
        System.out.println("-- declaration-file @jsonable no-effect (D4) --");

        // @jsonable on an export class in a .d.deal file: no metadata,
        // no E1043, and explicitly declared C$fromJson/C$toJson
        // signatures are preserved (no synthetic exports are recorded —
        // ExportExtractor's synthetic loop keys on isJsonable()).
        String decl = "// @jsonable\nexport class C { x: number; }\n"
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

        // Attachment to a non-class declaration is E1043 with no
        // metadata.
        r = parseFile("// @jsonable\nexport function f(): number { return 0; }",
            "lib.d.deal");
        assertParseError(r, "E1043",
            "declaration-file @jsonable on a function errors E1043");
        CompilerDiagnostic w = diagOf(r, "E1043");
        if (w != null) {
            check(w.severity().equals("error"),
                "E1043 is an error, got " + w.severity());
        }

        // The E1045 argument rule still fires in .d.deal files.
        r = parseFile("// @jsonable:foo\nexport class C { x: number; }",
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

        // Removed directives remain unknown inside an interpolation.
        String src3 = "let s = `${ // @deal-version 1.3\n x}`;";
        ParseResult r3 = parse(src3);
        CompilerDiagnostic e1046 = diagOf(r3, "E1044");
        check(e1046 != null,
            "embedded @deal-version produces E1044 placement, got "
                + r3.diagnostics());

        // A C marker inside an interpolation: E1044 (no extern-C context).
        String src4 = "let s = `${ // @c-struct\n x}`;";
        ParseResult r4 = parse(src4);
        CompilerDiagnostic e1046c = diagOf(r4, "E1044");
        check(e1046c != null,
            "embedded C marker produces E1044, got " + r4.diagnostics());
        if (e1046c != null) {
            check(e1046c.range().startScalarOffset() == 16
                    && e1046c.range().endScalarOffset() == 24,
                "embedded C-marker E1044 at the rebased directive range (12,24)");
        }
    }

}
