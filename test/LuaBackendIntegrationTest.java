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
        testForLoopClosureLimitation();
        testClassExport();
        testCodeAfterTryCatch();
        testNestedTryCatchReturn();
        testThrowDefaultFields();
        testNullableComparison();
        testNullReturn();

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
    // Test: For-loop closure limitation
    // =========================================================================

    static void testForLoopClosureLimitation() throws Exception {
        System.out.println("-- For Loop Closure Limitation --");
        String dealSrc =
            "export function test_closure_loop(): int {\n" +
            "  let a: int = 0;\n" +
            "  let b: int = 0;\n" +
            "  let c: int = 0;\n" +
            "  for (let i: int = 0; i < 3; i = i + 1) {\n" +
            "    if (i === 1) { a = i; }\n" +
            "    if (i === 2) { b = i; }\n" +
            "  }\n" +
            "  c = a + b;\n" +
            "  return c;\n" +
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
            check(false, "for-loop closure: compilation failed");
            return;
        }

        String lua = LuaBackend.generate(parse.program(), result, "test.deal");

        String runner =
            "package.path = './?.lua;' .. package.path\n" +
            "local mod = loadstring([[" + lua + "]])()\n" +
            "local r = mod.test_closure_loop.f()\n" +
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

        check(exit == 0, "for-loop closure: luajit exit 0 (got: " + output + ")");
        check(output.equals("3"), "for-loop closure: c is 3, got: " + output);
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
        // throw with only message — code should default to ""
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
        // Output should be "true\ntrue"
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

}

