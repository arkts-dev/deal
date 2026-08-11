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
        testDeclFileMode();
        testDeterministicOrdering();
        testNullSpanThrows();
        testSyntheticSpan();
        testCallExpr();

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
        FunctionDeclaration fd = new FunctionDeclaration(
            new Span("test.deal", 1, 1, 3, 2), "f",
            List.of(), Optional.empty(),
            new NamedType(new Span("test.deal", 1, 19, 1, 22), "null"),
            new Block(new Span("test.deal", 1, 24, 3, 2), List.of(
                new ThrowStatement(
                    new Span("test.deal", 2, 3, 2, 23),
                    new CallExpr(
                        new Span("test.deal", 2, 9, 2, 23),
                        new IdentifierExpr(new Span("test.deal", 2, 9, 2, 13), "Error"),
                        List.of(
                            new LiteralExpr(new Span("test.deal", 2, 15, 2, 22),
                                new LiteralValue.StringLiteral("oops"))
                        )
                    )
                )
            ))
        );
        ProgramNode prog = new ProgramNode(span, List.of(fd));

        Map<ExpressionNode, Type> typeMap = new HashMap<>();
        Type errorType = Types.classType("Error", "test");
        typeMap.put(
            ((ThrowStatement)((Block)fd.body()).statements().get(0)).expr(),
            errorType);
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
            "log", List.of(p1), Optional.empty(),
            new NamedType(new Span("test.d.deal", 1, 1, 1, 1), "null"),
            null);
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
