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

}
