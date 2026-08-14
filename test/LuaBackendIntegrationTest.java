package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.parser.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Integration tests for the Lua backend (ISSUE-0007).
 * Compiles complete DEAL programs to Lua, executes them with LuaJIT,
 * and verifies correct runtime behavior.
 */
public class LuaBackendIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Compile DEAL source, generate Lua, run with LuaJIT, and return stdout.
     * Returns null if LuaJIT is not available.
     */
    private static String compileAndRun(String dealSource, String filename) throws Exception {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
        } catch (IOException e) {
            return null;
        }

        LexResult lex = new Lexer(dealSource, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<Diagnostic> diags = new ArrayList<>(nr.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity()))
                    System.err.println("  Compile error: " + d);
            }
            throw new RuntimeException("Compilation failed");
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        diags.addAll(result.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity()))
                    System.err.println("  Type error: " + d);
            }
            throw new RuntimeException("Type checking failed");
        }

        String lua = LuaBackend.generate(parse.program(), result, filename);

        Path tmpDir = Files.createTempDirectory("deal_integration_");
        Path luaFile = tmpDir.resolve("test_main.lua");
        Files.writeString(luaFile, lua);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", luaFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();

        try {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        if (exit != 0) {
            System.err.println("LuaJIT exit " + exit + ": " + output);
        }
        return output.trim();
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Lua Backend Integration Tests ===");

        boolean luajitAvailable;
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        if (!luajitAvailable) {
            System.out.println("SKIP: luajit not available. Integration tests require LuaJIT.");
            System.out.println("Passed: 0, Failed: 0 (all skipped)");
            return;
        }

        testBasicFunctionCall();
        testRecursiveFunctionCall();
        testTryCatchPreserveErrorCode();
        testTryCatchRuntimeTypeError();
        testClassOptionalField();
        testClassOptionalFieldAfterDelete();
        testForLoopSimple();
        testForLoopClosureBinding();
        testForLoopClosureNested();
        testClassExport();
        testCodeAfterTryCatch();
        testNestedTryCatchReturn();
        testThrowDefaultFields();
        testNullableComparison();
        testNullReturn();
        testThrowValidError();
        // ISSUE-0011: break/continue inside try within loop
        testBreakInsideTryInLoop();
        testContinueInsideTryInLoop();
        testBreakInsideNestedTryInLoop();
        testContinueInsideNestedTryInLoop();
        testContinueInsideTryInForLoop();

        testIntConvertValid();
        testIntConvertNonInteger();
        testIntConvertNull();
        testIntConvertOutOfRange();
        testNumberConvertValid();
        testNumberConvertNull();
        testIntConvertIntLiteral();
        testIntrinsicFunctionValues();

        // ISSUE-0018: Template literal and for-of integration tests
        testTemplateLiteralRuntime();
        testForOfArrayRuntime();
        testForOfStringRuntime();
        testForOfClosureBinding();
        testForOfBreakRuntime();
        testForOfContinueRuntime();
        testForOfNested();
        testForOfStringSingleEval();

        // ISSUE-0037: Additional template literal and for-of integration tests
        testTemplateComplexExpression();
        testTemplateStringWithRBrace();
        testTemplateMalformedExpr();
        testTemplateNestedLiteral();
        testForOfTryCatchInside();
        testForOfStringMemberAccess();

        // ISSUE-0055: Async/await integration tests
        testAsyncSimpleAwait();
        testAsyncSyncComplete();
        testAsyncNested();
        testAsyncThrowCatch();
        testAsyncErrorPropagation();

        // ISSUE-0050: @jsonable integration tests
        testJsonableBasicRoundtrip();
        testJsonableMalformedJson();
        testJsonableExtraKeys();
        testJsonableOptionalNullableRoundtrip();
        testJsonableNestedClass();
        testJsonableArrayField();
        testJsonableForwardDeclaration();
        testJsonableEmptyClass();
        testJsonableWrappedTypeDependency();
        testJsonableDefaultApplication();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Test: Basic function call
    // =========================================================================

    static void testBasicFunctionCall() throws Exception {
        System.out.println("-- Basic Function Call --");
        String dealSrc =
            "export function add(a: int, b: int): int { return a + b; }\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.add.f(2, 3)\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "basic function call: luajit exit 0");
        check(output.equals("5"), "basic function call: add(2,3) = 5, got: " + output);
    }

    static void testRecursiveFunctionCall() throws Exception {
        System.out.println("-- Recursive Function Call --");
        String dealSrc =
            "export function down(n: int): int {\n" +
            "  if (n === 0) { return 0; }\n" +
            "  return down(n - 1);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "print(mod.down.f(5))\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "recursive function call: luajit exit 0 (got: " + output + ")");
        check(output.equals("0"), "recursive function call: down(5) = 0, got: " + output);
    }

    // =========================================================================
    // Test: try/catch with throw preserving error code
    // =========================================================================

    static void testTryCatchPreserveErrorCode() throws Exception {
        System.out.println("-- Try/Catch Preserve Error Code --");
        String dealSrc =
            "export function test_throw(): string {\n" +
            "  try {\n" +
            "    throw { code: \"E_LIMIT\", message: \"limit exceeded\" };\n" +
            "  } catch (e) {\n" +
            "    return e.code;\n" +
            "  }\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_throw.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "try/catch preserve: luajit exit 0");
        check(output.contains("E_LIMIT"), "try/catch preserve: e.code is E_LIMIT, got: " + output);
    }

    // =========================================================================
    // Test: try/catch with runtime type error
    // =========================================================================

    static void testTryCatchRuntimeTypeError() throws Exception {
        System.out.println("-- Try/Catch Runtime Type Error --");
        String dealSrc =
            "export function test_catch_unexpected(): string {\n" +
            "  try {\n" +
            "    let x: int = 1;\n" +
            "    return \"no_error\";\n" +
            "  } catch (e) {\n" +
            "    return e.code;\n" +
            "  }\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());
        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_catch_unexpected.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "try/catch unexpected: luajit exit 0");
        check(output.equals("no_error"), "try/catch unexpected: returned 'no_error', got: " + output);
    }

    // =========================================================================
    // Test: Class optional field
    // =========================================================================

    static void testClassOptionalField() throws Exception {
        System.out.println("-- Class Optional Field --");
        String dealSrc =
            "class User { name: string = \"\"; nick?: string; }\n" +
            "export function test_optional(): string {\n" +
            "  let u: User = { name: \"Ada\" };\n" +
            "  if (u.nick === null) {\n" +
            "    return \"null\";\n" +
            "  }\n" +
            "  return \"not_null\";\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "class optional: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_optional.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "class optional: luajit exit 0 (got: " + output + ")");
        check(output.equals("null"), "class optional: returns null, got: " + output);
    }

    // =========================================================================
    // Test: Class optional field after delete
    // =========================================================================

    static void testClassOptionalFieldAfterDelete() throws Exception {
        System.out.println("-- Class Optional Field After Delete --");
        String dealSrc =
            "class User { name: string = \"\"; nick?: string = \"\"; }\n" +
            "export function test_delete_optional(): string {\n" +
            "  let u: User = { name: \"Ada\", nick: \"ads\" };\n" +
            "  delete u.nick;\n" +
            "  if (u.nick === null) {\n" +
            "    return \"null_after_delete\";\n" +
            "  }\n" +
            "  return \"not_null\";\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "class optional delete: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_delete_optional.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "class optional delete: luajit exit 0 (got: " + output + ")");
        check(output.equals("null_after_delete"), "class optional delete: returns null_after_delete, got: " + output);
    }

    // =========================================================================
    // Test: For-loop simple
    // =========================================================================

    static void testForLoopSimple() throws Exception {
        System.out.println("-- For Loop Simple --");
        String dealSrc =
            "export function test_for_loop(): int {\n" +
            "  let sum: int = 0;\n" +
            "  for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "    sum = sum + i;\n" +
            "  }\n" +
            "  return sum;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-loop: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_for_loop.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-loop: luajit exit 0 (got: " + output + ")");
        check(output.equals("45"), "for-loop: sum is 45, got: " + output);
    }

    // =========================================================================
    // Test: For-loop closure per-iteration binding (ISSUE-0009)
    // =========================================================================

    static void testForLoopClosureBinding() throws Exception {
        System.out.println("-- For Loop Closure Binding (ISSUE-0009) --");
        // Each closure should capture the per-iteration value of i.
        // We use separate variables to avoid array indexing of table type.
        // f0() should return 0, f1() returns 1, f2() returns 2.
        String dealSrc =
            "export function test_closure_binding(): int {\n" +
            "  let f0: (() => int) = function(): int { return -1; };\n" +
            "  let f1: (() => int) = function(): int { return -1; };\n" +
            "  let f2: (() => int) = function(): int { return -1; };\n" +
            "  for (let i: int = 0; i < 3; i = i + 1) {\n" +
            "    if (i === 0) { f0 = function(): int { return i; }; }\n" +
            "    if (i === 1) { f1 = function(): int { return i; }; }\n" +
            "    if (i === 2) { f2 = function(): int { return i; }; }\n" +
            "  }\n" +
            "  return f0() * 100 + f1() * 10 + f2();\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "closure binding: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_closure_binding.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "closure binding: luajit exit 0 (got: " + output + ")");
        // f0()=0, f1()=1, f2()=2 → 0*100 + 1*10 + 2 = 12
        check(output.equals("12"), "closure binding: expected 12 (f0=0,f1=1,f2=2), got: " + output);
    }

    // =========================================================================
    // Test: Nested for-loops with closure binding (ISSUE-0009)
    // =========================================================================

    static void testForLoopClosureNested() throws Exception {
        System.out.println("-- Nested For Loop Closure Binding (ISSUE-0009) --");
        // Outer closures capture outer i, inner closures capture inner j.
        // Each has its own shadow variable.
        String dealSrc =
            "export function test_nested_binding(): int {\n" +
            "  let outer_f0: (() => int) = function(): int { return -1; };\n" +
            "  let outer_f1: (() => int) = function(): int { return -1; };\n" +
            "  let inner_f0: (() => int) = function(): int { return -1; };\n" +
            "  for (let i: int = 0; i < 2; i = i + 1) {\n" +
            "    if (i === 0) { outer_f0 = function(): int { return i; }; }\n" +
            "    if (i === 1) { outer_f1 = function(): int { return i; }; }\n" +
            "    for (let j: int = 0; j < 2; j = j + 1) {\n" +
            "      if (j === 1) { inner_f0 = function(): int { return j; }; }\n" +
            "    }\n" +
            "  }\n" +
            "  return outer_f0() * 100 + outer_f1() * 10 + inner_f0();\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "nested closure: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        // Verify the generated Lua has distinct shadow variables
        if (lua != null) {
            check(lua.contains("local _i = "), "outer shadow variable _i");
            check(lua.contains("local _j = "), "inner shadow variable _j");
            check(lua.contains("local i = _i"), "outer per-iteration binding");
            check(lua.contains("local j = _j"), "inner per-iteration binding");
        }

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_nested_binding.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "nested closure: luajit exit 0 (got: " + output + ")");
        // outer_f0()=0, outer_f1()=1, inner_f0()=1 → 0*100 + 1*10 + 1 = 11
        check(output.equals("11"), "nested closure: expected 11 (outer0=0,outer1=1,inner=1), got: " + output);
    }

    // =========================================================================
    // Test: Class export
    // =========================================================================

    static void testClassExport() throws Exception {
        System.out.println("-- Class Export --");
        String dealSrc =
            "export class User { name: string = \"\"; nick?: string; }\n" +
            "export function make_user(): string {\n" +
            "  let u: User = { name: \"Bob\" };\n" +
            "  return u.name;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "class export: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.make_user.f()\n" +
            "print(r)\n" +
            "if mod.User ~= nil then print('class_exported') end\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "class export: luajit exit 0 (got: " + output + ")");
        check(output.contains("Bob"), "class export: returns Bob, got: " + output);
        check(output.contains("class_exported"), "class export: User is in exports table, got: " + output);
    }

    // =========================================================================
    // Test: Code after try/catch executes (F1 round 2)
    // =========================================================================

    static void testCodeAfterTryCatch() throws Exception {
        System.out.println("-- Code After Try/Catch --");
        String dealSrc =
            "export function test_after_try(): string {\n" +
            "  let result: string = \"initial\";\n" +
            "  try {\n" +
            "    result = \"tried\";\n" +
            "  } catch (e) {\n" +
            "    result = \"caught\";\n" +
            "  }\n" +
            "  return result;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "code after try/catch: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_after_try.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "code after try/catch: luajit exit 0 (got: " + output + ")");
        check(output.equals("tried"), "code after try/catch: result is 'tried', got: " + output);
    }

    // =========================================================================
    // F1 round 5: Nested try/catch return propagation
    // =========================================================================

    static void testNestedTryCatchReturn() throws Exception {
        System.out.println("-- Nested Try/Catch Return (F1 round 5) --");
        String dealSrc =
            "export function outer(): string {\n" +
            "  try {\n" +
            "    try {\n" +
            "      return \"inner\";\n" +
            "    } catch (e2) {\n" +
            "      return \"inner_caught\";\n" +
            "    }\n" +
            "  } catch (e1) {\n" +
            "    return \"outer_caught\";\n" +
            "  }\n" +
            "  return \"none\";\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "nested try/catch: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.outer.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "nested try/catch: luajit exit 0 (got: " + output + ")");
        check(output.equals("inner"), "nested try/catch: inner return propagates, got: " + output);
    }

    // =========================================================================
    // F3 round 5: throw with partial Error includes default fields
    // =========================================================================

    static void testThrowDefaultFields() throws Exception {
        System.out.println("-- Throw Default Fields (F3 round 5) --");
        String dealSrc =
            "export function test_throw_msg_only(): string {\n" +
            "  try {\n" +
            "    throw { message: \"fail\" };\n" +
            "  } catch (e) {\n" +
            "    return e.code;\n" +
            "  }\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "throw default fields: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_throw_msg_only.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "throw default fields: luajit exit 0 (got: " + output + ")");
        check(output.equals(""), "throw default fields: e.code is empty string, got: " + output);
    }

    // =========================================================================
    // F4 round 5: Nullable-vs-nullable comparison at runtime
    // =========================================================================

    static void testNullableComparison() throws Exception {
        System.out.println("-- Nullable Comparison (F4 round 5) --");
        String dealSrc =
            "class User { name: string = \"\"; nick?: string; }\n" +
            "export function test_nullable_eq(): boolean {\n" +
            "  let u: User = { name: \"Ada\" };\n" +
            "  let a: string | null = u.nick;\n" +
            "  let b: string | null = null;\n" +
            "  return a === b;\n" +
            "}\n" +
            "export function test_nullable_neq(): boolean {\n" +
            "  let u: User = { name: \"Ada\", nick: \"ads\" };\n" +
            "  let a: string | null = u.nick;\n" +
            "  let b: string | null = null;\n" +
            "  return a !== b;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "nullable comparison: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "-- nil (missing field) === null (__NULL) should be true\n" +
            "local r1 = mod.test_nullable_eq.f()\n" +
            "print(r1)\n" +
            "-- 'ads' !== null should be true\n" +
            "local r2 = mod.test_nullable_neq.f()\n" +
            "print(r2)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "nullable comparison: luajit exit 0 (got: " + output + ")");
        String[] lines = output.split("\n");
        check(lines.length >= 2, "nullable comparison: two output lines, got: " + output);
        if (lines.length >= 2) {
            check(lines[0].equals("true"), "nullable eq: nil === __NULL should be true, got: " + lines[0]);
            check(lines[1].equals("true"), "nullable neq: 'ads' !== null should be true, got: " + lines[1]);
        }
    }

    // =========================================================================
    // F7 round 6: Bare return from null-typed function returns __NULL at runtime
    // =========================================================================

    static void testNullReturn() throws Exception {
        System.out.println("-- Null Return (F7 round 6) --");
        String dealSrc =
            "export function f(): null {\n" +
            "  return;\n" +
            "}\n" +
            "export function test_eq(): boolean {\n" +
            "  let x: null = f();\n" +
            "  return x === null;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "null return: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_eq.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "null return: luajit exit 0 (got: " + output + ")");
        check(output.equals("true"), "null return: x === null is true, got: " + output);
    }

    // =========================================================================
    // Test: int() valid conversion
    // =========================================================================

    static void testIntConvertValid() throws Exception {
        System.out.println("-- int() Valid Conversion --");
        String dealSrc =
            "export function test_int(): int {\n" +
            "  return int(3.0);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "int valid: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_int.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "int valid: luajit exit 0 (got: " + output + ")");
        check(output.equals("3"), "int(3.0) = 3, got: " + output);
    }

    // =========================================================================
    // Test: int() on non-integer raises error
    // =========================================================================

    static void testIntConvertNonInteger() throws Exception {
        System.out.println("-- int() Non-Integer Error --");
        String dealSrc =
            "export function test_int_err(): int {\n" +
            "  return int(3.7);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "int non-integer: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local ok, err = pcall(mod.test_int_err.f)\n" +
            "if ok then print('NO_ERROR') else print(err.code) end\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "int non-integer: luajit exit 0 (got: " + output + ")");
        check(output.contains("E8001"), "int(3.7) raises E8001, got: " + output);
    }

    // =========================================================================
    // Test: int(null) raises E8001
    // =========================================================================

    static void testIntConvertNull() throws Exception {
        System.out.println("-- int(null) Error --");
        String dealSrc =
            "export function test_int_null(): int {\n" +
            "  let x: int | null = null;\n" +
            "  return int(x);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "int null: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local ok, err = pcall(mod.test_int_null.f)\n" +
            "if ok then print('NO_ERROR') else print(err.code) end\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "int null: luajit exit 0 (got: " + output + ")");
        check(output.contains("E8001"), "int(null) raises E8001, got: " + output);
    }

    // =========================================================================
    // Test: int(1e308) raises E8004 (out of safe range)
    // =========================================================================

    static void testIntConvertOutOfRange() throws Exception {
        System.out.println("-- int(1e308) Out of Range Error --");
        String dealSrc =
            "export function test_int_range(): int {\n" +
            "  return int(1e308);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "int out of range: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local ok, err = pcall(mod.test_int_range.f)\n" +
            "if ok then print('NO_ERROR') else print(err.code) end\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "int out of range: luajit exit 0 (got: " + output + ")");
        check(output.contains("E8004"), "int(1e308) raises E8004, got: " + output);
    }

    // =========================================================================
    // Test: number() valid conversion
    // =========================================================================

    static void testNumberConvertValid() throws Exception {
        System.out.println("-- number() Valid Conversion --");
        String dealSrc =
            "export function test_number(): number {\n" +
            "  return number(3);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "number valid: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        // Lua prints 3.0 as 3, so verify the value is 3.0 numerically
        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_number.f()\n" +
            "print(r == 3.0 and 'OK' or r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "number valid: luajit exit 0 (got: " + output + ")");
        check(output.equals("OK"), "number(3) = 3.0, got: " + output);
    }

    // =========================================================================
    // Test: number(null) raises E8001
    // =========================================================================

    static void testNumberConvertNull() throws Exception {
        System.out.println("-- number(null) Error --");
        String dealSrc =
            "export function test_number_null(): number {\n" +
            "  let x: number | null = null;\n" +
            "  return number(x);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "number null: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local ok, err = pcall(mod.test_number_null.f)\n" +
            "if ok then print('NO_ERROR') else print(err.code) end\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "number null: luajit exit 0 (got: " + output + ")");
        check(output.contains("E8001"), "number(null) raises E8001, got: " + output);
    }

    // =========================================================================
    // Test: int(3) (int literal) is rejected at compile time
    // =========================================================================

    static void testIntConvertIntLiteral() throws Exception {
        System.out.println("-- int(3) Int Literal Compile Error --");
        String dealSrc =
            "export function test_int_lit(): int {\n" +
            "  return int(3);\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        // int(3) should be rejected at compile time — int literal is not number or int|null
        boolean hasError = result.diagnostics().stream()
            .anyMatch(d -> "error".equals(d.severity()) && d.code().equals("E5001"));
        check(hasError, "int(3) should be rejected at compile time with E5001");
    }

    // =========================================================================
    // Test: int/number intrinsics as first-class function values
    // (direct calls route through the wrapper .f entry with span forwarding;
    // indirect calls go through the wrapper unchanged)
    // =========================================================================

    static void testIntrinsicFunctionValues() throws Exception {
        System.out.println("-- Intrinsics as Function Values --");
        String dealSrc =
            "let fn: ((x: number) => int) = int;\n" +
            "let fn2: ((x: int) => number) = number;\n" +
            "export function test_direct_int(): int { return int(3.0); }\n" +
            "export function test_indirect_int(): int { return fn(4.0); }\n" +
            "export function test_direct_number(): number { return number(42); }\n" +
            "export function test_indirect_number(): number { return fn2(7); }\n" +
            "export function test_direct_int_err(): int { return int(3.7); }\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "intrinsic function values: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "print('direct_int:' .. mod.test_direct_int.f())\n" +
            "print('indirect_int:' .. mod.test_indirect_int.f())\n" +
            "print('direct_number:' .. mod.test_direct_number.f())\n" +
            "print('indirect_number:' .. mod.test_indirect_number.f())\n" +
            "local ok, err = pcall(mod.test_direct_int_err.f)\n" +
            "if ok then print('err:NO_ERROR') else print('err:' .. err.code .. ':' .. tostring(err.file) .. ':' .. tostring(err.line) .. ':' .. tostring(err.column)) end\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "intrinsic function values: luajit exit 0 (got: " + output + ")");
        check(output.contains("direct_int:3"), "direct int(3.0) converts, got: " + output);
        check(output.contains("indirect_int:4"), "indirect int via wrapper converts, got: " + output);
        check(output.contains("direct_number:42"), "direct number(42) converts, got: " + output);
        check(output.contains("indirect_number:7"), "indirect number via wrapper converts, got: " + output);
        check(output.contains("err:E8001:test.deal:7:"),
            "direct conversion error keeps call-site span, got: " + output);
    }


    static void testThrowValidError() throws Exception {
        System.out.println("-- Throw Valid Error (ISSUE-0010) --");
        String dealSrc =
            "export function test_throw_valid(): string {\n" +
            "  try {\n" +
            "    throw { code: \"E_TEST\", message: \"test error\" };\n" +
            "  } catch (e) {\n" +
            "    return e.code;\n" +
            "  }\n" +
            "  return \"not_caught\";\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "throw valid error: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_throw_valid.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "throw valid error: luajit exit 0 (got: " + output + ")");
        check(output.equals("E_TEST"), "throw valid error: caught code is E_TEST, got: " + output);
    }


    // =========================================================================
    // ISSUE-0011: Break inside try within for-loop exits loop correctly
    // =========================================================================

    static void testBreakInsideTryInLoop() throws Exception {
        System.out.println("-- Break Inside Try In Loop (ISSUE-0011) --");
        String dealSrc =
            "export function test_break_in_try(): int {\n" +
            "  let found: int = 0;\n" +
            "  for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "    try {\n" +
            "      if (i === 5) { break; }\n" +
            "    } catch (e) {}\n" +
            "    found = i;\n" +
            "  }\n" +
            "  return found;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "break in try: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_break_in_try.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // When i=5, break exits before found=i, so found should be 4 (last iteration before break)
        check(exit == 0, "break in try: luajit exit 0 (got: " + output + ")");
        check(output.equals("4"), "break in try: found should be 4 (break at i=5 before assignment), got: " + output);
    }

    // =========================================================================
    // ISSUE-0011: Continue inside try within for-loop skips iteration
    // =========================================================================

    static void testContinueInsideTryInLoop() throws Exception {
        System.out.println("-- Continue Inside Try In Loop (ISSUE-0011) --");
        String dealSrc =
            "export function test_continue_in_try(): int {\n" +
            "  let sum: int = 0;\n" +
            "  for (let i: int = 0; i < 5; i = i + 1) {\n" +
            "    try {\n" +
            "      if (i === 2) { continue; }\n" +
            "    } catch (e) {}\n" +
            "    sum = sum + i;\n" +
            "  }\n" +
            "  return sum;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "continue in try: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_continue_in_try.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // sum = 0 + 1 + 3 + 4 = 8 (skips i=2)
        check(exit == 0, "continue in try: luajit exit 0 (got: " + output + ")");
        check(output.equals("8"), "continue in try: sum should be 8 (skip i=2), got: " + output);
    }

    // =========================================================================
    // ISSUE-0011: Break inside nested try propagates and exits loop
    // =========================================================================

    static void testBreakInsideNestedTryInLoop() throws Exception {
        System.out.println("-- Break Inside Nested Try In Loop (ISSUE-0011) --");
        String dealSrc =
            "export function test_nested_break(): int {\n" +
            "  let found: int = 0;\n" +
            "  for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "    try {\n" +
            "      try {\n" +
            "        if (i === 5) { break; }\n" +
            "      } catch (e2) {}\n" +
            "      found = i;\n" +
            "    } catch (e1) {}\n" +
            "  }\n" +
            "  return found;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "nested break: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_nested_break.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // Break at i=5 before found=i, so found should be 4
        check(exit == 0, "nested break: luajit exit 0 (got: " + output + ")");
        check(output.equals("4"), "nested break: found should be 4, got: " + output);
    }

    // =========================================================================
    // ISSUE-0011: Continue inside nested try propagates and skips iteration
    // =========================================================================

    static void testContinueInsideNestedTryInLoop() throws Exception {
        System.out.println("-- Continue Inside Nested Try In Loop (ISSUE-0011) --");
        String dealSrc =
            "export function test_nested_continue(): int {\n" +
            "  let sum: int = 0;\n" +
            "  for (let i: int = 0; i < 5; i = i + 1) {\n" +
            "    try {\n" +
            "      try {\n" +
            "        if (i === 2) { continue; }\n" +
            "      } catch (e2) {}\n" +
            "      sum = sum + i;\n" +
            "    } catch (e1) {}\n" +
            "  }\n" +
            "  return sum;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "nested continue: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_nested_continue.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // sum = 0 + 1 + 3 + 4 = 8 (skips i=2)
        check(exit == 0, "nested continue: luajit exit 0 (got: " + output + ")");
        check(output.equals("8"), "nested continue: sum should be 8 (skip i=2), got: " + output);
    }

    // =========================================================================
    // ISSUE-0011: Continue inside try in for-loop jumps to update step
    // =========================================================================

    static void testContinueInsideTryInForLoop() throws Exception {
        System.out.println("-- Continue Inside Try In For-Loop Update (ISSUE-0011) --");
        // Verify that continue in try jumps to the update step (i = i + 1 executes)
        String dealSrc =
            "export function test_continue_update(): int {\n" +
            "  let count: int = 0;\n" +
            "  for (let i: int = 0; i < 3; i = i + 1) {\n" +
            "    try {\n" +
            "      continue;\n" +
            "    } catch (e) {}\n" +
            "    count = count + 1;\n" +
            "  }\n" +
            "  return count;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "continue update: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_continue_update.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // Every iteration continues before count++, so count should be 0
        // The loop should still terminate (update i = i + 1 runs each time)
        check(exit == 0, "continue update: luajit exit 0 (got: " + output + ")");
        check(output.equals("0"), "continue update: count should be 0 (all iterations skipped by continue), got: " + output);
    }


        // ISSUE-0018: Template literal and for-of integration tests

    static void testTemplateLiteralRuntime() throws Exception {
        System.out.println("-- Template Literal Runtime --");
        String dealSrc =
            "export function greet(name: string): string {\n" +
            "  return `Hello, ${name}!`;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "template literal: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.greet.f(\"World\")\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "template literal: luajit exit 0 (got: " + output + ")");
        check(output.equals("Hello, World!"),
            "template literal: expected 'Hello, World!', got: " + output);
    }

    // =========================================================================
    // ISSUE-0018: For-of over array runtime test
    // =========================================================================

    static void testForOfArrayRuntime() throws Exception {
        System.out.println("-- For-Of Array Runtime --");
        String dealSrc =
            "export function sum(xs: int[]): int {\n" +
            "  let total: int = 0;\n" +
            "  for (let x: int of xs) {\n" +
            "    total = total + x;\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of array: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.sum.f({1, 2, 3, 4, 5})\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of array: luajit exit 0 (got: " + output + ")");
        check(output.equals("15"),
            "for-of array sum: expected 15, got: " + output);
    }

    // =========================================================================
    // ISSUE-0018: For-of over string runtime test
    // =========================================================================

    static void testForOfStringRuntime() throws Exception {
        System.out.println("-- For-Of String Runtime --");
        String dealSrc =
            "export function countChars(s: string): int {\n" +
            "  let n: int = 0;\n" +
            "  for (let c: string of s) {\n" +
            "    n = n + 1;\n" +
            "  }\n" +
            "  return n;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of string: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.countChars.f(\"hello\")\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of string: luajit exit 0 (got: " + output + ")");
        check(output.equals("5"),
            "for-of string count: expected 5, got: " + output);
    }

    // =========================================================================
    // ISSUE-0018: For-of closure binding (fresh per iteration)
    // =========================================================================

    static void testForOfClosureBinding() throws Exception {
        System.out.println("-- For-Of Closure Binding (Fresh Per Iteration) --");
        String dealSrc =
            "export function testBinding(): int {\n" +
            "  let result: int = 0;\n" +
            "  for (let x: int of [1, 2, 3]) {\n" +
            "    let getX = function(): int { return x; };\n" +
            "    result = result + getX();\n" +
            "  }\n" +
            "  return result;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of closure: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.testBinding.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of closure: luajit exit 0 (got: " + output + ")");
        check(output.equals("6"),
            "for-of closure binding: expected 6, got: " + output);
    }

    static void testForOfBreakRuntime() throws Exception {
        System.out.println("-- For-Of Break Runtime --");
        String dealSrc =
            "export function sumUntil3(xs: int[]): int {\n" +
            "  let total: int = 0;\n" +
            "  for (let x: int of xs) {\n" +
            "    if (x === 3) { break; }\n" +
            "    total = total + x;\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of break: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.sumUntil3.f({1, 2, 3, 4, 5})\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of break: luajit exit 0 (got: " + output + ")");
        check(output.equals("3"),
            "for-of break: expected 3, got: " + output);
    }

    // =========================================================================
    // ISSUE-0018: For-of continue runtime test
    // =========================================================================

    static void testForOfContinueRuntime() throws Exception {
        System.out.println("-- For-Of Continue Runtime --");
        String dealSrc =
            "export function sumSkip3(xs: int[]): int {\n" +
            "  let total: int = 0;\n" +
            "  for (let x: int of xs) {\n" +
            "    if (x === 3) { continue; }\n" +
            "    total = total + x;\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of continue: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.sumSkip3.f({1, 2, 3, 4, 5})\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of continue: luajit exit 0 (got: " + output + ")");
        check(output.equals("12"),
            "for-of continue: expected 12, got: " + output);
    }

    // =========================================================================
    // ISSUE-0018: Nested for-of loops
    // =========================================================================

    static void testForOfNested() throws Exception {
        System.out.println("-- For-Of Nested --");
        String dealSrc =
            "export function nestedSum(xs: int[], ys: int[]): int {\n" +
            "  let total: int = 0;\n" +
            "  for (let x: int of xs) {\n" +
            "    for (let y: int of ys) {\n" +
            "      total = total + x * y;\n" +
            "    }\n" +
            "  }\n" +
            "  return total;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of nested: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.nestedSum.f({1, 2}, {10, 20})\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of nested: luajit exit 0 (got: " + output + ")");
        check(output.equals("90"),
            "for-of nested: expected 90, got: " + output);
    }

    // =========================================================================
    // ISSUE-0018: For-of string single evaluation (D17)
    // =========================================================================

    static void testForOfStringSingleEval() throws Exception {
        System.out.println("-- For-Of String Single Evaluation (D17) --");
        String dealSrc =
            "let counter: int = 0;\n" +
            "export function getStr(): string {\n" +
            "  counter = counter + 1;\n" +
            "  return \"abc\";\n" +
            "}\n" +
            "export function countAndGet(): int {\n" +
            "  let n: int = 0;\n" +
            "  for (let c: string of getStr()) {\n" +
            "    n = n + 1;\n" +
            "  }\n" +
            "  return counter;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of string single eval: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.countAndGet.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of string single eval: luajit exit 0 (got: " + output + ")");
        check(output.equals("1"),
            "for-of string single eval: expected 1, got: " + output);
    }

    // =========================================================================
    // ISSUE-0037: Template literal with complex expression (function call + member access)
    // =========================================================================

    static void testTemplateComplexExpression() throws Exception {
        System.out.println("-- Template Literal: Complex Expressions --");
        // Test template literal with function call and identifier interpolation.
        // The expression inside ${} is a function call returning string.
        String dealSrc =
            "export function label(): string { return \"Name\"; }\n" +
            "export function greet(name: string): string {\n" +
            "  return `${label()}: ${name}`;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "template complex expr: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.greet.f(\"Alice\")\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "template complex expr: luajit exit 0 (got: " + output + ")");
        check(output.equals("Name: Alice"),
            "template complex expr: expected 'Name: Alice', got: " + output);
    }

    static void testTemplateStringWithRBrace() throws Exception {
        System.out.println("-- Template Literal: String with '}' in interpolation --");
        String dealSrc =
            "export function testRbrace(): string {\n" +
            "  return `${ \"}\" }`;\n" +
            "  // the } inside the string literal must not close the interpolation\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "template string with }: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.testRbrace.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "template string with }: luajit exit 0 (got: " + output + ")");
        check(output.equals("}"),
            "template string with }: expected '}', got: " + output);
    }

    // =========================================================================
    // ISSUE-0037: Malformed expression inside ${} does not crash compiler (D16)
    // =========================================================================

    static void testTemplateMalformedExpr() throws Exception {
        System.out.println("-- Template Literal: Malformed Expression (D16) --");
        // ${@} is syntactically invalid. The compiler must emit diagnostics and
        // produce valid Lua output for the rest of the template without crashing.
        String dealSrc =
            "export function testMalformed(): string {\n" +
            "  return `hello ${@} world`;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();

        // We expect parser diagnostics for the malformed expression
        boolean hasParserDiags = !parse.diagnostics().isEmpty();
        check(hasParserDiags, "malformed expr: parser emits diagnostics");

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        // Compilation may have errors but should not crash
        // Verify that Lua can still be generated (compiler continues past error)
        try {
            String lua = LuaBackend.generate(parse.program(), result, "test.deal");
            check(lua != null && !lua.isEmpty(), "malformed expr: Lua output generated despite malformed expression");
        } catch (Exception e) {
            check(false, "malformed expr: codegen should not throw: " + e.getMessage());
        }
    }

    // =========================================================================
    // ISSUE-0037: Nested template literal integration test
    // =========================================================================

    static void testTemplateNestedLiteral() throws Exception {
        System.out.println("-- Template Literal: Nested Template (D14) --");
        // A nested template literal inside ${} — the inner template uses escaped
        // backticks so the lexer does not close the outer template prematurely.
        // The parser's unescapeTemplateExpression converts the escape sequences
        // back to literal backticks for the sub-lexer, which then parses the
        // inner template correctly.
        //
        // Expected: the inner template has type string (no E3016), codegen
        // produces correct Lua output with .. concatenation, and runtime
        // result is "[inner]" (the inner template surrounded by brackets).
        String dealSrc =
            "export function testNested(): string {\n" +
            "  return \"[\" + `inner` + \"]\";\n" +
            "}\n" +
            "export function testOuter(): string {\n" +
            "  return `[${ \\`inner\\` }]`;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();

        // Should have no parser diagnostics — the unescape fix ensures
        // the sub-lexer receives clean source
        if (!parse.diagnostics().isEmpty()) {
            for (Diagnostic d : parse.diagnostics()) {
                System.err.println("  Parser diagnostic: " + d);
            }
            check(false, "nested template: unexpected parser diagnostics");
            return;
        }

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Type error: " + d);
            }
            check(false, "nested template: compilation failed");
            return;
        }

        // Verify no E3016 — the inner template must be accepted as string type
        boolean hasE3016 = result.diagnostics().stream()
            .anyMatch(d -> "E3016".equals(d.code()));
        check(!hasE3016, "nested template: no E3016 (inner template type is string)");

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        // Verify the Lua output contains .. concatenation
        check(lua.contains(".."), "nested template: Lua output uses .. concatenation");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r1 = mod.testNested.f()\n" +
            "local r2 = mod.testOuter.f()\n" +
            "print(r1)\n" +
            "print(r2)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "nested template: luajit exit 0 (got: " + output + ")");

        // Both testNested and testOuter should produce "[inner]"
        String[] lines = output.split("\\n");
        check(lines.length == 2, "nested template: expected 2 output lines, got: " + output);
        check(lines[0].equals("[inner]"), "nested template: testNested expected '[inner]', got: '" + lines[0] + "'");
        check(lines[1].equals("[inner]"), "nested template: testOuter expected '[inner]', got: '" + lines[1] + "'");
    }

    // =========================================================================
    // ISSUE-0037: For-of with try/catch inside body (break/continue flag pattern)
    // =========================================================================

    static void testForOfTryCatchInside() throws Exception {
        System.out.println("-- For-Of with Try/Catch Inside Body --");
        // break inside try within for-of should exit the loop correctly
        String dealSrc =
            "export function testForOfBreakInTry(xs: int[]): int {\n" +
            "  let found: int = 0;\n" +
            "  for (let x: int of xs) {\n" +
            "    try {\n" +
            "      if (x === 3) { break; }\n" +
            "    } catch (e) {}\n" +
            "    found = x;\n" +
            "  }\n" +
            "  return found;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of try/catch: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.testForOfBreakInTry.f({1, 2, 3, 4, 5})\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        // Break at x=3 before found=x, so found should be 2 (last value before break)
        check(exit == 0, "for-of try/catch break: luajit exit 0 (got: " + output + ")");
        check(output.equals("2"),
            "for-of try/catch break: found should be 2, got: " + output);
    }

    // =========================================================================
    // ISSUE-0037: String for-of with member access iterable (D17)
    // =========================================================================

    static void testForOfStringMemberAccess() throws Exception {
        System.out.println("-- For-Of String: Member Access Iterable (D17) --");
        // Verify that for-of over a member access expression (w.str) compiles and
        // runs correctly. The iterable expression is a field access on a class instance.
        String dealSrc =
            "class Wrapper { str: string = \"\"; }\n" +
            "export function countWrapper(): int {\n" +
            "  let w: Wrapper = { str: \"hello\" };\n" +
            "  let n: int = 0;\n" +
            "  for (let c: string of w.str) {\n" +
            "    n = n + 1;\n" +
            "  }\n" +
            "  return n;\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "for-of string member access: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        // Verify that the Lua output hoists the member access to a local variable
        check(lua.contains("__iterable"), "string member access: __iterable hoisting present in Lua output");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.countWrapper.f()\n" +
            "print(r)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "for-of string member access: luajit exit 0 (got: " + output + ")");
        check(output.equals("5"),
            "for-of string member access: expected 5, got: " + output);
    }

    // =========================================================================
    // ISSUE-0055: Async/await integration tests
    // =========================================================================

    static void testAsyncSimpleAwait() throws Exception {
        System.out.println("-- Async Simple Await --");
        String dealSrc =
            "export async function g(): int { return 42; }\n" +
            "export async function f(): int { return await g(); }\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "async simple await: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local handle = mod.f.f()\n" +
            "if handle.__kind ~= 'async' then print('NOT ASYNC: ' .. tostring(handle.__kind)); return end\n" +
            "if not handle.__done then print('PENDING'); return end\n" +
            "print(handle.__result)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "async simple await: luajit exit 0 (got: " + output + ")");
        check(output.equals("42"), "async simple await: expected 42, got: " + output);
    }

    static void testAsyncSyncComplete() throws Exception {
        System.out.println("-- Async Sync Complete --");
        String dealSrc =
            "export async function f(): int { return 5; }\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "async sync complete: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local handle = mod.f.f()\n" +
            "if handle.__kind ~= 'async' then print('NOT ASYNC'); return end\n" +
            "if not handle.__done then print('PENDING'); return end\n" +
            "print(handle.__result)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "async sync complete: luajit exit 0 (got: " + output + ")");
        check(output.equals("5"), "async sync complete: expected 5, got: " + output);
    }

    static void testAsyncNested() throws Exception {
        System.out.println("-- Async Nested --");
        String dealSrc =
            "export async function c(): int { return 10; }\n" +
            "export async function b(): int { return await c(); }\n" +
            "export async function a(): int { return await b(); }\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "async nested: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local handle = mod.a.f()\n" +
            "if handle.__kind ~= 'async' then print('NOT ASYNC'); return end\n" +
            "if not handle.__done then print('PENDING'); return end\n" +
            "print(handle.__result)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "async nested: luajit exit 0 (got: " + output + ")");
        check(output.equals("10"), "async nested: expected 10, got: " + output);
    }

    static void testAsyncThrowCatch() throws Exception {
        System.out.println("-- Async Throw Catch --");
        String dealSrc =
            "export async function f(): string {\n" +
            "  try {\n" +
            "    throw { code: \"E_TEST\", message: \"test error\" };\n" +
            "  } catch (e) {\n" +
            "    return e.code;\n" +
            "  }\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "async throw catch: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local handle = mod.f.f()\n" +
            "if handle.__kind ~= 'async' then print('NOT ASYNC'); return end\n" +
            "if not handle.__done then print('PENDING'); return end\n" +
            "print(handle.__result)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "async throw catch: luajit exit 0 (got: " + output + ")");
        check(output.equals("E_TEST"), "async throw catch: expected E_TEST, got: " + output);
    }

    static void testAsyncErrorPropagation() throws Exception {
        System.out.println("-- Async Error Propagation --");
        // inner throws, outer catches via try/catch around await
        // Use int() conversion instead of string() since there is no string() intrinsic.
        String dealSrc =
            "export async function inner(): int {\n" +
            "  throw { code: \"E_INNER\", message: \"inner error\" };\n" +
            "  return 0;\n" +
            "}\n" +
            "export async function outer(): string {\n" +
            "  try {\n" +
            "    let _: int = await inner();\n" +
            "    return \"no error\";\n" +
            "  } catch (e) {\n" +
            "    return e.code;\n" +
            "  }\n" +
            "}\n";

        LexResult lex = new Lexer(dealSrc, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal").parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : result.diagnostics()) {
                if ("error".equals(d.severity())) System.err.println("  Error: " + d);
            }
            check(false, "async error propagation: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local handle = mod.outer.f()\n" +
            "if handle.__kind ~= 'async' then print('NOT ASYNC'); return end\n" +
            "if not handle.__done then print('PENDING'); return end\n" +
            "print(handle.__result)\n";

        Path tmpDir = Files.createTempDirectory("deal_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        check(exit == 0, "async error propagation: luajit exit 0 (got: " + output + ")");
        check(output.equals("E_INNER"), "async error propagation: expected E_INNER, got: " + output);
    }

    // ISSUE-0050: @jsonable integration tests
    // =========================================================================

    /**
     * Compile a DEAL source with @jsonable classes, generate Lua, and
     * run it with a Lua runner that exercises fromJson / toJson.
     * Returns the Lua source and the runtime output.
     */
    private record JsonableRunResult(String lua, String runtimeOutput) {}

    private static JsonableRunResult compileAndRunJsonable(
            String dealSource, String filename, String runnerBody) throws Exception {
        LexResult lex = new Lexer(dealSource, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        List<Diagnostic> diags = new ArrayList<>(nr.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity()))
                    System.err.println("  Compile error: " + d);
            }
            throw new RuntimeException("Compilation failed for " + filename);
        }
        CheckResult result = TypeChecker.check(filename, symTable, nr, parse.program());
        diags.addAll(result.diagnostics());
        if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity()))
                    System.err.println("  Type error: " + d);
            }
            throw new RuntimeException("Type checking failed for " + filename);
        }
        String lua = LuaBackend.generate(parse.program(), result, filename);

        String runtimeOutput;
        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local __rt = require('deal.runtime')\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            runnerBody + "\n";

        Path tmpDir = Files.createTempDirectory("deal_jsonable_int_");
        Path runnerFile = tmpDir.resolve("runner.lua");
        Files.writeString(runnerFile, runner);
        Path runtimeDir = tmpDir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));
        Path stdDir = tmpDir.resolve("std");
        Files.createDirectories(stdDir);
        Files.copy(Path.of("std/json.lua"), stdDir.resolve("json.lua"));

        ProcessBuilder pb = new ProcessBuilder("luajit", runnerFile.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();

        try { Files.walk(tmpDir).sorted(Comparator.reverseOrder())
            .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        if (exit != 0) {
            System.err.println("LuaJIT exit " + exit + ": " + output);
        }
        runtimeOutput = (exit == 0) ? output : ("EXIT:" + exit + " " + output);
        return new JsonableRunResult(lua, runtimeOutput);
    }

    // --- @jsonable integration tests ---

    static void testJsonableBasicRoundtrip() throws Exception {
        System.out.println("-- @jsonable Basic Roundtrip --");
        String dealSrc =
            "// @jsonable\n" +
            "export class User {\n" +
            "  name: string = \"\";\n" +
            "  age: int = 0;\n" +
            "}\n";

        String runnerBody =
            "local u = mod[\"User$fromJson\"].f('{\"name\":\"Alice\",\"age\":30}')\n" +
            "if u == __rt.__NULL then print('NULL') else\n" +
            "  print(u.name)\n" +
            "  print(u.age)\n" +
            "  local s = mod[\"User$toJson\"].f(u)\n" +
            "  print(s)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        // Structural checks
        check(r.lua.contains("__deal[\"User_fields\"] = {"), "User_fields emitted");
        check(r.lua.contains("__deal[\"User$fromJson\"] = __rt.function_("),
            "User$fromJson emitted");
        check(r.lua.contains("__deal[\"User$toJson\"] = __rt.function_("),
            "User$toJson emitted");
        check(r.lua.contains("__json_parse"), "__json_parse used");
        check(r.lua.contains("__json_stringify"), "__json_stringify used");
        check(r.lua.contains("pcall(__json_parse, s)"), "pcall wrapping");

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("Alice"), "fromJson returns Alice, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("30"), "fromJson returns age 30, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("\"name\""), "toJson produces JSON with name, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableMalformedJson() throws Exception {
        System.out.println("-- @jsonable Malformed JSON --");
        String dealSrc =
            "// @jsonable\n" +
            "export class User {\n" +
            "  name: string = \"\";\n" +
            "}\n";

        String runnerBody =
            "local u = mod[\"User$fromJson\"].f('not json')\n" +
            "if u == __rt.__NULL then print('NULL_OK') else print('NOT_NULL') end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        check(r.lua.contains("pcall(__json_parse, s)"), "pcall present");
        check(r.lua.contains("if not ok then return __NULL end"), "returns NULL on parse error");

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("NULL_OK"),
                "malformed JSON returns NULL, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableExtraKeys() throws Exception {
        System.out.println("-- @jsonable Extra Keys --");
        String dealSrc =
            "// @jsonable\n" +
            "export class User {\n" +
            "  name: string = \"\";\n" +
            "}\n";

        String runnerBody =
            "local u = mod[\"User$fromJson\"].f('{\"name\":\"Bob\",\"extra\":42}')\n" +
            "if u == __rt.__NULL then print('NULL_OK') else print('NOT_NULL') end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        check(r.lua.contains("json_from_json"), "json_from_json called");

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("NULL_OK"),
                "extra keys returns NULL, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableOptionalNullableRoundtrip() throws Exception {
        System.out.println("-- @jsonable Optional-Nullable Roundtrip --");
        String dealSrc =
            "// @jsonable\n" +
            "export class Profile {\n" +
            "  nick?: string | null;\n" +
            "  bio: string | null = null;\n" +
            "  score: int = 0;\n" +
            "}\n";

        String runnerBody =
            "local p1 = mod[\"Profile$fromJson\"].f('{\"score\":100}')\n" +
            "local s1 = mod[\"Profile$toJson\"].f(p1)\n" +
            "print('roundtrip1:' .. s1)\n" +
            "local p2 = mod[\"Profile$fromJson\"].f('{\"nick\":null,\"bio\":\"hi\",\"score\":50}')\n" +
            "local s2 = mod[\"Profile$toJson\"].f(p2)\n" +
            "print('roundtrip2:' .. s2)\n" +
            "local p3 = mod[\"Profile$fromJson\"].f('{\"nick\":\"Joe\",\"bio\":null,\"score\":75}')\n" +
            "local s3 = mod[\"Profile$toJson\"].f(p3)\n" +
            "print('roundtrip3:' .. s3)\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        check(r.lua.contains("\"nick\""), "nick field descriptor");
        check(r.lua.contains("optional = true"), "optional field flag");
        check(r.lua.contains("nullable = true"), "nullable field flag");

        if (r.runtimeOutput != null) {
            // Extract roundtrip1 line: the missing optional field should not appear
            String rt1 = r.runtimeOutput.lines()
                .filter(l -> l.startsWith("roundtrip1:"))
                .findFirst().orElse("");
            check(!rt1.contains("nick"), "missing optional not in roundtrip1 output, got: " + rt1);
            check(r.runtimeOutput.contains("roundtrip1"), "roundtrip1 executed");
            check(r.runtimeOutput.contains("roundtrip2"), "roundtrip2 executed");
            check(r.runtimeOutput.contains("roundtrip3"), "roundtrip3 executed");
        }
    }

    static void testJsonableNestedClass() throws Exception {
        System.out.println("-- @jsonable Nested Class --");
        String dealSrc =
            "// @jsonable\n" +
            "export class Child {\n" +
            "  name: string = \"\";\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class Parent {\n" +
            "  child: Child;\n" +
            "  label: string = \"\";\n" +
            "}\n";

        String runnerBody =
            "local p = mod[\"Parent$fromJson\"].f('{\"child\":{\"name\":\"Kid\"},\"label\":\"parent\"}')\n" +
            "if p == __rt.__NULL then print('NULL') else\n" +
            "  print(p.label)\n" +
            "  print(p.child.name)\n" +
            "  local s = mod[\"Parent$toJson\"].f(p)\n" +
            "  print(s)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        // Check that Child_fields is emitted before Parent_fields (topological sort:
        // Parent depends on Child, so Child must come first — here Child is declared first
        // so it's already in order, but we verify both exist)
        check(r.lua.contains("__deal[\"Child_fields\"] = {"), "Child_fields emitted");
        check(r.lua.contains("__deal[\"Parent_fields\"] = {"), "Parent_fields emitted");
        // Parent field descriptor should reference Child_defaults and Child_fields
        check(r.lua.contains("Child_defaults"), "nested class defaults reference");
        check(r.lua.contains("Child_fields"), "nested class fields reference");
        check(r.lua.contains("jtype = \"class\""), "class jtype for nested field");

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("parent"), "parent label, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("Kid"), "child name, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableArrayField() throws Exception {
        System.out.println("-- @jsonable Array Field --");
        String dealSrc =
            "// @jsonable\n" +
            "export class ListHolder {\n" +
            "  tags: string[];\n" +
            "  name: string = \"\";\n" +
            "}\n";

        String runnerBody =
            "local lh = mod[\"ListHolder$fromJson\"].f('{\"tags\":[\"a\",\"b\"],\"name\":\"test\"}')\n" +
            "if lh == __rt.__NULL then print('NULL') else\n" +
            "  print(lh.name)\n" +
            "  print(lh.tags[1])\n" +
            "  print(lh.tags[2])\n" +
            "  local s = mod[\"ListHolder$toJson\"].f(lh)\n" +
            "  print(s)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        check(r.lua.contains("jtype = \"array\""), "array jtype");
        check(r.lua.contains("element = {"), "array element descriptor");

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("test"), "name field, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("a"), "first tag, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("b"), "second tag, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableForwardDeclaration() throws Exception {
        System.out.println("-- @jsonable Forward Declaration --");
        // Parent class A declared BEFORE child class B.
        // Topological sort must ensure B_fields is emitted before A_fields.
        String dealSrc =
            "// @jsonable\n" +
            "export class A {\n" +
            "  b: B;\n" +
            "  label: string = \"\";\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  name: string = \"\";\n" +
            "}\n";

        String runnerBody =
            "local a = mod[\"A$fromJson\"].f('{\"b\":{\"name\":\"child\"},\"label\":\"parent\"}')\n" +
            "if a == __rt.__NULL then print('NULL') else\n" +
            "  print(a.label)\n" +
            "  print(a.b.name)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        String lua = r.lua;
        int bFieldsPos = lua.indexOf("__deal[\"B_fields\"] = {");
        int aFieldsPos = lua.indexOf("__deal[\"A_fields\"] = {");
        check(bFieldsPos >= 0, "B_fields exists");
        check(aFieldsPos >= 0, "A_fields exists");
        check(bFieldsPos < aFieldsPos,
            "B_fields emitted before A_fields (topological sort for forward decl), " +
            "B at " + bFieldsPos + ", A at " + aFieldsPos);

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("parent"), "parent label, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("child"), "child name, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableEmptyClass() throws Exception {
        System.out.println("-- @jsonable Empty Class --");
        String dealSrc =
            "// @jsonable\n" +
            "export class Empty {\n" +
            "}\n";

        String runnerBody =
            "local e = mod[\"Empty$fromJson\"].f('{}')\n" +
            "if e == __rt.__NULL then print('NULL') else\n" +
            "  print('OK')\n" +
            "  local s = mod[\"Empty$toJson\"].f(e)\n" +
            "  print(s)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        check(r.lua.contains("__deal[\"Empty_fields\"] = {"), "Empty_fields emitted");
        // Empty fields should be an empty table
        check(r.lua.contains("__deal[\"Empty_fields\"] = {\n"),
            "Empty_fields is empty table");

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("OK"), "empty class fromJson works, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableWrappedTypeDependency() throws Exception {
        System.out.println("-- @jsonable Wrapped Type Dependency --");
        // A depends on B through array wrapper (bs: B[]).
        // Even though A is declared first, B_fields must be emitted before A_fields.
        String dealSrc =
            "// @jsonable\n" +
            "export class A {\n" +
            "  bs: B[];\n" +
            "  name: string = \"\";\n" +
            "}\n" +
            "// @jsonable\n" +
            "export class B {\n" +
            "  val: int = 0;\n" +
            "}\n";

        String runnerBody =
            "local a = mod[\"A$fromJson\"].f('{\"bs\":[{\"val\":1},{\"val\":2}],\"name\":\"arr\"}')\n" +
            "if a == __rt.__NULL then print('NULL') else\n" +
            "  print(a.name)\n" +
            "  print(a.bs[1].val)\n" +
            "  print(a.bs[2].val)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);
        String lua = r.lua;
        int bFieldsPos = lua.indexOf("__deal[\"B_fields\"] = {");
        int aFieldsPos = lua.indexOf("__deal[\"A_fields\"] = {");
        check(bFieldsPos >= 0, "B_fields exists");
        check(aFieldsPos >= 0, "A_fields exists");
        check(bFieldsPos < aFieldsPos,
            "B_fields emitted before A_fields (wrapped type dep), " +
            "B at " + bFieldsPos + ", A at " + aFieldsPos);

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("arr"), "name field, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("1"), "first element val, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("2"), "second element val, got: " + r.runtimeOutput);
        }
    }

    static void testJsonableDefaultApplication() throws Exception {
        System.out.println("-- @jsonable Default Application --");
        String dealSrc =
            "// @jsonable\n" +
            "export class Config {\n" +
            "  host: string = \"localhost\";\n" +
            "  port: int = 8080;\n" +
            "}\n";

        String runnerBody =
            "local c = mod[\"Config$fromJson\"].f('{}')\n" +
            "if c == __rt.__NULL then print('NULL') else\n" +
            "  print(c.host)\n" +
            "  print(c.port)\n" +
            "end\n";

        JsonableRunResult r = compileAndRunJsonable(dealSrc, "test.deal", runnerBody);

        if (r.runtimeOutput != null) {
            check(r.runtimeOutput.contains("localhost"), "default host applied, got: " + r.runtimeOutput);
            check(r.runtimeOutput.contains("8080"), "default port applied, got: " + r.runtimeOutput);
        }
    }

}
