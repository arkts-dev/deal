package deal.test;

import deal.ast.*;
import deal.diagnostics.DiagnosticCode;
import deal.lexer.*;
import deal.parser.*;

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
        return new Parser(lex.tokens(), "test.deal").parse();
    }

    private static ParseResult parseFile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        return new Parser(lex.tokens(), filename).parse();
    }

    private static void assertNoParseErrors(ParseResult result, String context) {
        List<Diagnostic> diags = result.diagnostics();
        if (!diags.isEmpty()) {
            for (Diagnostic d : diags) {
                System.err.println("  Diagnostic: " + d);
            }
        }
        check(diags.isEmpty(), context + ": expected no parse errors, got " + diags.size());
    }

    private static void assertParseError(ParseResult result, String code, String context) {
        List<Diagnostic> diags = result.diagnostics();
        boolean found = diags.stream().anyMatch(d -> d.code().equals(code));
        check(found, context + ": expected diagnostic " + code + ", got: " + diags);
    }

    private static void assertStmtCount(ProgramNode prog, int expected, String context) {
        check(prog.statements().size() == expected,
            context + ": expected " + expected + " statements, got " + prog.statements().size());
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

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
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
            check(fd.restParam().isEmpty(), "no rest param");
            check(fd.returnType() instanceof NamedType, "return type");
            check(fd.body().statements().size() == 1, "body has 1 stmt");
        }

        // With rest parameter
        r = parse("function sum(base: int, ...rest: int[]): int { return base; }");
        assertNoParseErrors(r, "func with rest");
        fd = assertInstance(r.program().statements().get(0),
                FunctionDeclaration.class, "func with rest");
        if (fd != null) {
            check(fd.params().size() == 1, "param count before rest");
            check(fd.restParam().isPresent(), "has rest param");
            check(fd.restParam().get().name().equals("rest"), "rest param name");
        }

        // External declaration (semicolon body)
        r = parse("function ext(): int;");
        assertNoParseErrors(r, "external decl");
        fd = assertInstance(r.program().statements().get(0),
                FunctionDeclaration.class, "external decl");
        if (fd != null) {
            check(fd.body().statements().isEmpty(), "external decl empty body");
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

    // =========================================================================
    // Span tests
    // =========================================================================

    static void testParserWarn() {
        System.out.println("-- Parser warn() --");

        // Create parser with minimal tokens
        Parser parser = new Parser(List.of(), "test.deal");

        // Test warn with Token
        Token tok = new Token(TokenType.IDENTIFIER, "test", 5, 3, 4);
        parser.warn(DiagnosticCode.E1001, "warning from token", tok);

        List<Diagnostic> diags = parser.parse().diagnostics();
        check(diags.size() == 1,
            "warn with token should add 1 diagnostic, got " + diags.size());
        if (!diags.isEmpty()) {
            Diagnostic d = diags.get(0);
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
        }

        // Test warn with ExpressionNode (IdentifierExpr)
        Span span = new Span("test.deal", 10, 2, 10, 8);
        ExpressionNode expr = new IdentifierExpr(span, "myVar");
        parser.warn(DiagnosticCode.E1002, "warning from node", expr);

        diags = parser.parse().diagnostics();
        check(diags.size() == 2,
            "warn with node should add another diagnostic, got " + diags.size());
        if (diags.size() >= 2) {
            Diagnostic d = diags.get(1);
            check(d.severity().equals("warning"),
                "node warning severity should be 'warning'");
            check(d.line() == 10,
                "node warning line should be 10, got: " + d.line());
            check(d.column() == 2,
                "node warning column should be 2, got: " + d.column());
            check(d.message().equals("warning from node"),
                "node warning message preserved");
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

        List<Diagnostic> diags = r.diagnostics();
        Diagnostic warnDiag = diags.stream()
                .filter(d -> d.code().equals("E1043"))
                .findFirst().orElse(null);
        check(warnDiag != null, "warning diagnostic present for export function");
        if (warnDiag != null) {
            check(warnDiag.severity().equals("warning"),
                "severity is warning, got: " + warnDiag.severity());
            check(warnDiag.message().contains("export class"),
                "message mentions export class: " + warnDiag.message());
        }
    }

    static void testJsonableStandaloneClass() {
        System.out.println("-- @jsonable standalone class -> warning, isJsonable=false --");

        ParseResult r = parse("// @jsonable\nclass C { x: int; }");
        assertParseError(r, "E1043", "@jsonable standalone class");

        List<Diagnostic> diags = r.diagnostics();
        Diagnostic warnDiag = diags.stream()
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

        List<Diagnostic> diags = r.diagnostics();
        Diagnostic warnDiag = diags.stream()
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

        List<Diagnostic> diags = r.diagnostics();
        Diagnostic warnDiag = diags.stream()
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
        List<Diagnostic> diags = r.diagnostics();

        for (Diagnostic d : diags) {
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

        // Verify that lexing + parsing @jsonable + let produces correct warning
        LexResult lex = new Lexer("// @jsonable\nlet x: int = 1;", "test.deal").tokenize();
        Parser parser = new Parser(lex.tokens(), "test.deal");
        ParseResult r = parser.parse();

        List<Diagnostic> diags = r.diagnostics();
        boolean foundWarn = diags.stream().anyMatch(
                d -> d.code().equals("E1043") && d.severity().equals("warning"));
        check(foundWarn, "end-to-end: warning with code E1043 and severity 'warning'");

        // Verify no error-level diagnostics
        boolean hasErrors = diags.stream().anyMatch(
                d -> d.severity().equals("error"));
        check(!hasErrors, "end-to-end: no errors, only warnings");

        // Verify the LET token had the directive from the lexer
        Token letToken = null;
        for (Token t : lex.tokens()) {
            if (t.type() == TokenType.LET) {
                letToken = t;
                break;
            }
        }
        check(letToken != null, "end-to-end: LET token found");
        check(letToken.directives().contains("@jsonable"),
            "end-to-end: LET token has @jsonable directive");
    }

}
