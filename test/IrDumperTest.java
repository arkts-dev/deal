package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

/**
 * Unit tests for the {@link IrDumper} class.
 */
public class IrDumperTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Running IrDumper Tests ===\n");

        testEmptyModule();
        testModuleWithImports();
        testFunctionDeclaration();
        testVariableDeclaration();
        testReturnStatement();
        testIfStatement();
        testWhileStatement();
        testForStatement();
        testTryCatch();
        testThrow();
        testClassDeclaration();
        testExpressionKinds();
        testBoundaryAnnotations();
        testSpecTypeDescriptors();
        testRestParamSpecTypeDescriptor();
        testDeclFileMode();
        testDeterministicOrdering();
        testNullSpanThrows();
        testNullSpanInNodeThrows();
        testMissingTypeMapEntryThrows();
        testSyntheticSpan();
        testCallExpr();
        testTableReadBoundary();
        testStdlibBoundary();
        testExternalBoundary();
        testAwaitExpressionIr();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record CompileResult(ProgramNode program, CheckResult checkResult, SymbolTable symbolTable) {}

    private static CompileResult compile(String source) {
        return compile(source, "test.deal");
    }

    private static CompileResult compile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            throw new RuntimeException("Lex error: " + lex.diagnostics());
        }
        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) {
            throw new RuntimeException("Parse error: " + parseResult.diagnostics());
        }
        ProgramNode program = parseResult.program();

        ModuleResolverStub resolver = new ModuleResolverStub();
        NameResolver nr = new NameResolver("test", resolver);
        SymbolTable symTable = nr.resolve(program);
        CheckResult result = TypeChecker.check("test", symTable, nr, program);

        return new CompileResult(program, result, symTable);
    }

    private static void assertContains(String ir, String expected, String label) {
        if (ir.contains(expected)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]: IR should contain '" + expected + "'");
            System.out.println("  Actual IR:\n" + ir);
        }
    }

    private static void assertNotContains(String ir, String unexpected, String label) {
        if (!ir.contains(unexpected)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]: IR should NOT contain '" + unexpected + "'");
            System.out.println("  Actual IR:\n" + ir);
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    static void testEmptyModule() {
        System.out.print("  testEmptyModule... ");
        CompileResult cr = compile("");
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");
        assertContains(ir, "module @test.deal:1:1-1:1", "module header");
        assertNotContains(ir, "function", "no functions");
        assertNotContains(ir, "let", "no lets");
        System.out.println("OK");
    }

    static void testModuleWithImports() {
        System.out.print("  testModuleWithImports... ");
        Span span = new Span("test.deal", 1, 1, 1, 1);
        ImportDeclaration imp = new ImportDeclaration(
            new Span("test.deal", 1, 1, 1, 30), "M", "./other");
        VariableDeclaration var = new VariableDeclaration(
            new Span("test.deal", 2, 1, 2, 14), "x",
            java.util.Optional.of(new NamedType(new Span("test.deal", 2, 8, 2, 10), "int")),
            new LiteralExpr(new Span("test.deal", 2, 14, 2, 14),
                new LiteralValue.IntLiteral(42)));

        ProgramNode prog = new ProgramNode(span, java.util.List.of(imp, var));

        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        typeMap.put(var.initializer(), Type.Int.INSTANCE);
        SymbolTable st = new SymbolTable();
        st.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        String ir = IrDumper.dump(prog, result, "test");
        assertContains(ir, "import * as M from \"./other\"", "import line");
        assertContains(ir, "[boundary: import]", "import boundary");
        assertContains(ir, "let x: int", "let with type");
        assertContains(ir, "literal 42 : int", "literal with type");
        System.out.println("OK");
    }

    static void testFunctionDeclaration() {
        System.out.print("  testFunctionDeclaration... ");
        String source = "function add(a: int, b: int): int { return a + b; }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "function add: int", "function header");
        assertContains(ir, "param a: int", "param a");
        assertContains(ir, "param b: int", "param b");
        assertContains(ir, "[boundary: param-entry]", "param-entry boundary");
        assertContains(ir, "body", "function body");
        assertContains(ir, "return", "return statement");
        assertContains(ir, "[boundary: return]", "return boundary");
        assertContains(ir, "binary + : int", "binary expression");
        assertContains(ir, "ident a : int", "ident a");
        assertContains(ir, "ident b : int", "ident b");
        System.out.println("OK");
    }

    static void testVariableDeclaration() {
        System.out.print("  testVariableDeclaration... ");
        String source = "let x: int = 42\nlet y = 3.14";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "let x: int", "typed let");
        assertContains(ir, "[boundary: var-annotation]", "var-annotation boundary");
        assertContains(ir, "literal 42 : int", "int literal");
        assertContains(ir, "let y: number", "inferred let");
        assertContains(ir, "literal 3.14 : number", "number literal");
        System.out.println("OK");
    }

    static void testReturnStatement() {
        System.out.print("  testReturnStatement... ");
        String source = "function f(): null { return; }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "return", "return statement");
        assertNotContains(ir, "[boundary: return]", "no return boundary for null return");
        System.out.println("OK");
    }

    static void testIfStatement() {
        System.out.print("  testIfStatement... ");
        String source = "function f(): null { if (true) { let x: int = 1; } }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "if @test.deal:", "if statement");
        assertContains(ir, "literal true : boolean", "condition");
        assertContains(ir, "block", "then block");
        System.out.println("OK");
    }

    static void testWhileStatement() {
        System.out.print("  testWhileStatement... ");
        String source = "function f(): null { while (true) { break; } }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "while", "while statement");
        assertContains(ir, "break", "break statement");
        System.out.println("OK");
    }

    static void testForStatement() {
        System.out.print("  testForStatement... ");
        String source = "function f(): null { for (let i: int = 0; i < 10; i = i + 1) { continue; } }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "for", "for statement");
        assertContains(ir, "continue", "continue statement");
        System.out.println("OK");
    }

    static void testTryCatch() {
        System.out.print("  testTryCatch... ");
        String source = "function f(): null { try { let x: int = 1; } catch (e) { let y: int = 2; } }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "try", "try statement");
        assertContains(ir, "catch e", "catch clause");
        System.out.println("OK");
    }

    static void testThrow() {
        System.out.print("  testThrow... ");
        Span span = new Span("test.deal", 1, 1, 1, 30);
        IdentifierExpr errorIdent = new IdentifierExpr(
            new Span("test.deal", 2, 9, 2, 13), "Error");
        LiteralExpr oopsLit = new LiteralExpr(
            new Span("test.deal", 2, 15, 2, 22),
            new LiteralValue.StringLiteral("oops"));
        CallExpr errorCall = new CallExpr(
            new Span("test.deal", 2, 9, 2, 23), errorIdent, List.of(oopsLit));
        ThrowStatement throwStmt = new ThrowStatement(
            new Span("test.deal", 2, 3, 2, 23), errorCall);
        FunctionDeclaration fd = new FunctionDeclaration(
            new Span("test.deal", 1, 1, 3, 2), "f",
            List.of(),
            new NamedType(new Span("test.deal", 1, 19, 1, 22), "null"),
            new Block(new Span("test.deal", 1, 24, 3, 2), List.of(throwStmt)),
            false, false);
        ProgramNode prog = new ProgramNode(span, List.of(fd));

        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        Type errorType = Types.classType("Error", "test");
        typeMap.put(errorCall, errorType);
        typeMap.put(errorIdent, errorType);
        typeMap.put(oopsLit, Type.String.INSTANCE);
        SymbolTable st = new SymbolTable();
        st.define("Error", new Symbol.ClassSymbol("Error", List.of(), "test"));
        st.define("f", new Symbol.FunctionSymbol("f",
            Types.func(List.of(), Type.Null.INSTANCE)));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        String ir = IrDumper.dump(prog, result, "test");
        assertContains(ir, "throw", "throw statement");
        assertContains(ir, "call : @test/Error", "throw expr has Error type");
        System.out.println("OK");
    }

    static void testClassDeclaration() {
        System.out.print("  testClassDeclaration... ");
        String source = "class Point { x: int = 0; y?: int; }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "class Point", "class header");
        assertContains(ir, "field x: int", "field x");
        assertContains(ir, "field y?: int", "optional field y");
        assertContains(ir, "[boundary: class-default]", "class-default boundary");
        System.out.println("OK");
    }

    static void testExpressionKinds() {
        System.out.print("  testExpressionKinds... ");
        String source = """
            function f(): null {
                let a: int = 1;
                let b: int = -a;
                let c: boolean = !true;
                let d: int = a + b;
                let e: boolean = a === b;
                let f: boolean = a < b && b > 0;
                let g: int[] = [1, 2, 3];
                let h: table = { key: "val" };
                let i: int = g[0];
                a = 5;
            }
            """;
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "unary -", "unary neg");
        assertContains(ir, "unary !", "unary not");
        assertContains(ir, "binary +", "binary add");
        assertContains(ir, "binary ===", "binary eq");
        assertContains(ir, "binary &&", "binary and");
        assertContains(ir, "array : [int]", "array literal");
        assertContains(ir, "object : table", "object literal");
        assertContains(ir, "index []", "index expr");
        assertContains(ir, "assign =", "assignment");
        System.out.println("OK");
    }

    static void testBoundaryAnnotations() {
        System.out.print("  testBoundaryAnnotations... ");
        String source = """
            function process(a: int): int {
                let x: int = a;
                x = 5;
                return x;
            }
            """;
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "[boundary: param-entry]", "param-entry boundary");
        assertContains(ir, "[boundary: var-annotation]", "var-annotation boundary");
        assertContains(ir, "[boundary: assignment]", "assignment boundary");
        assertContains(ir, "[boundary: return]", "return boundary");
        System.out.println("OK");
    }

    static void testSpecTypeDescriptors() {
        System.out.print("  testSpecTypeDescriptors... ");
        String source = """
            class User { name: string = ""; }
            function f(a: int[], b: int|null): null {
                let u: User = { name: "test" };
                return;
            }
            """;
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "[int]", "array descriptor uses brackets");
        assertNotContains(ir, "int[]", "no legacy array format");
        assertContains(ir, "@test/User", "class descriptor uses @");
        System.out.println("OK");
    }

    static void testDeclFileMode() {
        System.out.print("  testDeclFileMode... ");
        Span span = new Span("test.d.deal", 1, 1, 1, 1);
        Parameter p1 = new Parameter(new Span("test.d.deal", 1, 1, 1, 1),
            "x", new NamedType(new Span("test.d.deal", 1, 1, 1, 1), "string"));
        FunctionDeclaration fd = new FunctionDeclaration(
            new Span("test.d.deal", 1, 1, 1, 30),
            "log", List.of(p1),
            new NamedType(new Span("test.d.deal", 1, 1, 1, 1), "null"),
            null, false, true);
        ExportDeclaration exp = new ExportDeclaration(
            new Span("test.d.deal", 1, 1, 1, 30), fd);
        ProgramNode prog = new ProgramNode(span, List.of(exp));

        String ir = IrDumper.dump(prog, (SymbolTable) null, "std/console");
        assertContains(ir, "module", "decl file has module header");
        assertContains(ir, "export", "decl file has export");
        assertContains(ir, "function log: null", "decl file function");
        assertContains(ir, "param x: string", "decl file param");
        assertNotContains(ir, "body", "no body in decl file");
        System.out.println("OK");
    }

    static void testDeterministicOrdering() {
        System.out.print("  testDeterministicOrdering... ");
        String source = """
            function b(): null { return; }
            function a(): null { return; }
            function c(): null { return; }
            """;
        CompileResult cr = compile(source);
        String ir1 = IrDumper.dump(cr.program, cr.checkResult, "test");
        String ir2 = IrDumper.dump(cr.program, cr.checkResult, "test");

        if (ir1.equals(ir2)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [deterministic output]: values differ");
        }
        System.out.println("OK");
    }

    static void testNullSpanThrows() {
        System.out.print("  testNullSpanThrows... ");
        ProgramNode prog = new ProgramNode(new Span("test.deal", 1, 1, 1, 1), List.of());
        try {
            IrDumper.dump(prog, (CheckResult) null, "test");
            failed++;
            System.out.println("FAIL: should have thrown for null CheckResult");
        } catch (IllegalArgumentException e) {
            passed++;
        }
        System.out.println("OK");
    }

    /**
     * Tests that passing an AST node with a null span results in
     * IllegalStateException with a message identifying the node class.
     */
    static void testNullSpanInNodeThrows() {
        System.out.print("  testNullSpanInNodeThrows... ");
        // Construct a ProgramNode containing a VariableDeclaration with a null span
        LiteralExpr lit = new LiteralExpr(
            new Span("test.deal", 1, 1, 1, 1),
            new LiteralValue.IntLiteral(42));
        VariableDeclaration var = new VariableDeclaration(
            null,  // null span — should trigger IllegalStateException
            "x",
            Optional.empty(),
            lit);

        ProgramNode prog = new ProgramNode(
            new Span("test.deal", 1, 1, 1, 1),
            List.of(var));

        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        typeMap.put(lit, Type.Int.INSTANCE);
        SymbolTable st = new SymbolTable();
        st.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        try {
            IrDumper.dump(prog, result, "test");
            failed++;
            System.out.println("FAIL: should have thrown IllegalStateException for null span");
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg.contains("null span") || msg.contains("VariableDeclaration")) {
                passed++;
                System.out.println("OK (caught: " + msg + ")");
            } else {
                failed++;
                System.out.println("FAIL: message should identify null span or node class, got: " + msg);
            }
        }
    }

    /**
     * Tests that a missing typeMap entry in full-module mode throws
     * IllegalStateException with the node class name and span.
     */
    static void testMissingTypeMapEntryThrows() {
        System.out.print("  testMissingTypeMapEntryThrows... ");
        // Construct an IdentifierExpr that is NOT in the typeMap
        IdentifierExpr idExpr = new IdentifierExpr(
            new Span("test.deal", 1, 10, 1, 11), "x");
        VariableDeclaration var = new VariableDeclaration(
            new Span("test.deal", 1, 1, 1, 11), "y",
            Optional.empty(), idExpr);

        ProgramNode prog = new ProgramNode(
            new Span("test.deal", 1, 1, 1, 11),
            List.of(var));

        // typeMap is empty — idExpr is missing
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        SymbolTable st = new SymbolTable();
        st.define("y", new Symbol.VariableSymbol("y", Type.Int.INSTANCE, false));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        try {
            IrDumper.dump(prog, result, "test");
            failed++;
            System.out.println("FAIL: should have thrown IllegalStateException for missing typeMap entry");
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg.contains("Missing typeMap entry") || msg.contains("IdentifierExpr")) {
                passed++;
                System.out.println("OK (caught: " + msg + ")");
            } else {
                failed++;
                System.out.println("FAIL: message should identify missing typeMap entry, got: " + msg);
            }
        }
    }

    static void testSyntheticSpan() {
        System.out.print("  testSyntheticSpan... ");
        String source = "function f(): null { return; }";
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "@test.deal:", "spans present");
        System.out.println("OK");
    }

    static void testCallExpr() {
        System.out.print("  testCallExpr... ");
        String source = """
            class Calc { }
            function compute(x: int, y: int): int { return x; }
            function main(): null {
                let r: int = compute(1, 2);
            }
            """;
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "call : int", "call expr");
        assertContains(ir, "ident compute : (int,int)->int", "callee ident");
        System.out.println("OK");
    }

    // =========================================================================
    // New boundary tests (reviewer feedback fixes)
    // =========================================================================

    static void testTableReadBoundary() {
        System.out.print("  testTableReadBoundary... ");
        // A table read with contextual type should produce [boundary: table-read]
        String source = """
            function readTable(t: table): string {
                return t.name;
            }
            """;
        CompileResult cr = compile(source);
        String ir = IrDumper.dump(cr.program, cr.checkResult, "test");

        assertContains(ir, "[boundary: table-read]", "table-read boundary on member access");
        System.out.println("OK");
    }

    static void testStdlibBoundary() {
        System.out.print("  testStdlibBoundary... ");
        // Simulate a call to a stdlib function via an import alias
        Span span = new Span("test.deal", 1, 1, 1, 30);
        ImportDeclaration imp = new ImportDeclaration(
            new Span("test.deal", 1, 1, 1, 30), "S", "std/string");

        IdentifierExpr calleeId = new IdentifierExpr(
            new Span("test.deal", 2, 11, 2, 12), "S");
        MemberAccessExpr callee = new MemberAccessExpr(
            new Span("test.deal", 2, 11, 2, 19), calleeId, "length");
        LiteralExpr arg = new LiteralExpr(
            new Span("test.deal", 2, 20, 2, 27),
            new LiteralValue.StringLiteral("hello"));
        CallExpr call = new CallExpr(
            new Span("test.deal", 2, 11, 2, 28), callee, List.of(arg));
        VariableDeclaration var = new VariableDeclaration(
            new Span("test.deal", 2, 1, 2, 28), "x",
            java.util.Optional.of(new NamedType(new Span("test.deal", 2, 5, 2, 10), "int")),
            call);

        ProgramNode prog = new ProgramNode(span, List.of(imp, var));

        // Set up typeMap — all expression nodes need entries
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        typeMap.put(calleeId, Type.Table.INSTANCE);
        typeMap.put(callee, Type.Int.INSTANCE);
        typeMap.put(arg, Type.String.INSTANCE);
        typeMap.put(call, Type.Int.INSTANCE);
        typeMap.put(var.initializer(), Type.Int.INSTANCE);

        SymbolTable st = new SymbolTable();
        st.define("S", new Symbol.ModuleSymbol("S", Map.of("length",
            Types.func(List.of(Type.String.INSTANCE), Type.Int.INSTANCE)), imp.span()));
        st.define("x", new Symbol.VariableSymbol("x", Type.Int.INSTANCE, false));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        String ir = IrDumper.dump(prog, result, "test");
        assertContains(ir, "[boundary: stdlib-boundary]", "stdlib-boundary on call");
        System.out.println("OK");
    }

    static void testExternalBoundary() {
        System.out.print("  testExternalBoundary... ");
        // Simulate a call to an external (host) function via a bare import
        Span span = new Span("test.deal", 1, 1, 1, 30);
        ImportDeclaration imp = new ImportDeclaration(
            new Span("test.deal", 1, 1, 1, 30), "Ext", "external-lib");

        IdentifierExpr calleeId = new IdentifierExpr(
            new Span("test.deal", 2, 11, 2, 14), "Ext");
        MemberAccessExpr callee = new MemberAccessExpr(
            new Span("test.deal", 2, 11, 2, 18), calleeId, "foo");
        CallExpr call = new CallExpr(
            new Span("test.deal", 2, 11, 2, 20), callee, List.of());
        VariableDeclaration var = new VariableDeclaration(
            new Span("test.deal", 2, 1, 2, 20), "y",
            java.util.Optional.of(new NamedType(new Span("test.deal", 2, 5, 2, 10), "int")),
            call);

        ProgramNode prog = new ProgramNode(span, List.of(imp, var));

        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        typeMap.put(calleeId, Type.Table.INSTANCE);
        typeMap.put(callee, Type.Int.INSTANCE);
        typeMap.put(call, Type.Int.INSTANCE);
        typeMap.put(var.initializer(), Type.Int.INSTANCE);

        SymbolTable st = new SymbolTable();
        st.define("Ext", new Symbol.ModuleSymbol("Ext", Map.of("foo",
            Types.func(List.of(), Type.Int.INSTANCE)), imp.span()));
        st.define("y", new Symbol.VariableSymbol("y", Type.Int.INSTANCE, false));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        String ir = IrDumper.dump(prog, result, "test");
        assertContains(ir, "[boundary: external-boundary]", "external-boundary on call");
        assertContains(ir, "[boundary: host-in]", "host-in on import");
        System.out.println("OK");
    }

    // =========================================================================
    // AwaitExpression IR dump test
    // =========================================================================

    /**
     * Tests that the IrDumper properly handles AwaitExpression nodes
     * instead of silently emitting null (the default Visitor return).
     */
    static void testAwaitExpressionIr() {
        System.out.print("  testAwaitExpressionIr... ");

        // Build a synthetic async function with an await expression:
        // async function g(): int { return 42; }
        // async function f(): int { return await g(); }
        Span span = new Span("test.deal", 1, 1, 5, 1);

        // async function g(): int { return 42; }
        LiteralExpr lit42 = new LiteralExpr(
            new Span("test.deal", 2, 18, 2, 20),
            new LiteralValue.IntLiteral(42));
        ReturnStatement gReturn = new ReturnStatement(
            new Span("test.deal", 2, 9, 2, 21),
            Optional.of(lit42));
        FunctionDeclaration gDecl = new FunctionDeclaration(
            new Span("test.deal", 2, 1, 3, 2), "g",
            List.of(),
            new NamedType(new Span("test.deal", 2, 17, 2, 17), "int"),
            new Block(new Span("test.deal", 2, 22, 3, 2), List.of(gReturn)),
            true, false);  // isAsync = true

        // await g() — the await expression wrapping the call
        IdentifierExpr gId = new IdentifierExpr(
            new Span("test.deal", 4, 17, 4, 20), "g");
        CallExpr gCall = new CallExpr(
            new Span("test.deal", 4, 17, 4, 21), gId, List.of());
        AwaitExpression awaitExpr = new AwaitExpression(
            new Span("test.deal", 4, 11, 4, 21), gCall);
        ReturnStatement fReturn = new ReturnStatement(
            new Span("test.deal", 4, 5, 4, 21),
            Optional.of(awaitExpr));
        FunctionDeclaration fDecl = new FunctionDeclaration(
            new Span("test.deal", 4, 1, 5, 1), "f",
            List.of(),
            new NamedType(new Span("test.deal", 4, 16, 4, 16), "int"),
            new Block(new Span("test.deal", 4, 22, 5, 1), List.of(fReturn)),
            true, false);  // isAsync = true

        ProgramNode prog = new ProgramNode(span, List.of(gDecl, fDecl));

        // Build type map
        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        typeMap.put(lit42, Type.Int.INSTANCE);

        // g has type async()->int
        Type.Func gType = Types.func(List.of(), Type.Int.INSTANCE, true);
        typeMap.put(gId, gType);
        // gCall result type is int (return type of g)
        typeMap.put(gCall, Type.Int.INSTANCE);
        // awaitExpr result type is int (return type of async function)
        typeMap.put(awaitExpr, Type.Int.INSTANCE);
        // fReturn
        typeMap.put(fReturn.expr().get(), Type.Int.INSTANCE);

        // gReturn
        typeMap.put(gReturn.expr().get(), Type.Int.INSTANCE);

        SymbolTable st = new SymbolTable();
        st.define("g", new Symbol.FunctionSymbol("g", gType));
        st.define("f", new Symbol.FunctionSymbol("f",
            Types.func(List.of(), Type.Int.INSTANCE, true)));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        String ir = IrDumper.dump(prog, result, "test");

        assertContains(ir, "async function g: int", "async function g header");
        assertContains(ir, "async function f: int", "async function f header");
        assertContains(ir, "await : int", "await expression with type");
        assertContains(ir, "call : int", "call inside await");
        assertContains(ir, "ident g : async()->int", "async function type on ident");

        System.out.println("OK");
    }


    /**
     * Tests that DEAL v1.2 function descriptors carry no rest arm: a fixed
     * array parameter renders as {@code [int]} inside the parameter list
     * and no {@code ...} arm is emitted.
     */
    static void testRestParamSpecTypeDescriptor() {
        System.out.print("  testFixedArrayParamSpecTypeDescriptor... ");
        // Build a function with a fixed array parameter: function g(a: int, b: int[]): null
        Span span = new Span("test.deal", 1, 1, 1, 30);
        Span gSpan = new Span("test.deal", 1, 1, 1, 30);
        FunctionDeclaration fd = new FunctionDeclaration(
            gSpan, "g",
            List.of(
                new Parameter(new Span("test.deal", 1, 15, 1, 20),
                    "a", new NamedType(new Span("test.deal", 1, 18, 1, 20), "int")),
                new Parameter(new Span("test.deal", 1, 22, 1, 31),
                    "b", new ArrayType(new Span("test.deal", 1, 26, 1, 31),
                        new NamedType(new Span("test.deal", 1, 26, 1, 28), "int")))),
            new NamedType(new Span("test.deal", 1, 35, 1, 38), "null"),
            new Block(new Span("test.deal", 1, 39, 1, 41), List.of()), false, false);

        // Create an identifier reference to g
        IdentifierExpr gId = new IdentifierExpr(
            new Span("test.deal", 2, 1, 2, 2), "g");
        VariableDeclaration var = new VariableDeclaration(
            new Span("test.deal", 2, 1, 2, 2), "h",
            Optional.empty(), gId);

        ProgramNode prog = new ProgramNode(span, List.of(fd, var));

        // Fixed params only: (int, [int]) -> null
        Type.Func funcType = Types.func(
            List.of(Type.Int.INSTANCE, new Type.Array(Type.Int.INSTANCE)),
            Type.Null.INSTANCE);

        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        typeMap.put(gId, funcType);
        typeMap.put(var.initializer(), funcType);

        SymbolTable st = new SymbolTable();
        st.define("g", new Symbol.FunctionSymbol("g", funcType));
        st.define("h", new Symbol.VariableSymbol("h", funcType, false));
        CheckResult result = new CheckResult(typeMap, st, List.of());

        String ir = IrDumper.dump(prog, result, "test");

        // The function identifier type is (int,[int])->null — no rest arm.
        assertContains(ir, "(int,[int])->null", "fixed params use (int,[int])->null");
        assertNotContains(ir, "...", "no rest arm in v1.2 descriptors");

        System.out.println("OK");
    }
    // Stub module resolver
    // =========================================================================

    static class ModuleResolverStub implements ModuleResolver {
        @Override
        public Map<String, Type> resolveModule(String modulePath, String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            return Map.of();
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                                                      String importingModule)
                throws ModuleNotFoundException {
            return null;
        }
    }
}
