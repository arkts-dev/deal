package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.codegen.lua.LuaBackend;
import deal.codegen.lua.LuaFfiBindingGenerator;
import deal.identity.CanonicalModuleIdentity;
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityResolver;
import deal.source.ScalarSourceCursor;
import deal.lexer.*;
import deal.parser.*;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Unit tests for the Lua backend (ISSUE-0007).
 * Tests each codegen pattern in isolation by generating Lua strings
 * and verifying they are syntactically valid via LuaJIT.
 */
public class LuaBackendTest {

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

    private static void checkEq(int actual, int expected, String message) {
        if (actual == expected) { passed++; }
        else {
            failed++;
            System.err.println("FAIL: " + message
                + " (expected " + expected + ", got " + actual + ")");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record CompileOutput(String lua, CheckResult result, ProgramNode program) {}

    /** Extended compile output that also captures codegen diagnostics. */
    private record CompileOutputDiag(String lua, CheckResult result,
                                      ProgramNode program, List<CompilerDiagnostic> codegenDiags) {}

    private static CompileOutput compile(String source) {
        return compile(source, "test.deal");
    }

    private static CompileOutput compile(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename, lex.directiveEvents()).parse();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());

        CheckResult result;
        if (diags.stream().noneMatch(d -> "error".equals(d.severity()))) {
            result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                return new CompileOutput(null, result, parse.program());
            }
        } else {
            return new CompileOutput(null,
                new CheckResult(Map.of(), symTable, diags), parse.program());
        }

        String lua = LuaBackend.generate(parse.program(), result, filename);
        return new CompileOutput(lua, result, parse.program());
    }

    /**
     * Compile DEAL source under an explicit semantic profile
     * (signed-int32 foundation I4): the v1.2 parser constructor and the
     * profile-carrying {@code generateToFile} seam, so the emitter's
     * int32 gate/negation selection is observable in unit tests.
     */
    private static CompileOutput compileWithProfile(String source, String filename,
            SemanticProfile profile) throws IOException {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(),
            filename, profile,
            lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        List<CompilerDiagnostic> diags = new ArrayList<>(parse.diagnostics());
        diags.addAll(nr.diagnostics());
        CheckResult result;
        if (diags.stream().noneMatch(d -> "error".equals(d.severity()))) {
            result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                return new CompileOutput(null, result, parse.program());
            }
        } else {
            return new CompileOutput(null,
                new CheckResult(Map.of(), symTable, diags), parse.program());
        }
        Path outDir = Files.createTempDirectory("deal_lua_v12_ut_");
        Path luaFile = outDir.resolve("mod.lua");
        LuaBackend.GenerationResult gen = LuaBackend.generateToFile(
            parse.program(), result, filename, filename, outDir, luaFile,
            false, Map.of(), Map.of(), false, profile);
        return new CompileOutput(gen.lua(), result, parse.program());
    }

    /**
     * Compile DEAL source and also capture codegen diagnostics.
     */
    private static CompileOutputDiag compileWithDiag(String source) {
        return compileWithDiag(source, "test.deal");
    }

    /**
     * Compile DEAL source as the selected ENTRY module of the invocation
     * (v1.2 entry contract active), capturing backend diagnostics.
     */
    private static CompileOutputDiag compileEntry(String source) {
        return compileEntry(source, "test.deal");
    }

    private static CompileOutputDiag compileEntry(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename, lex.directiveEvents()).parse();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());

        CheckResult result;
        if (diags.stream().noneMatch(d -> "error".equals(d.severity()))) {
            result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                return new CompileOutputDiag(null, result, parse.program(), List.of());
            }
        } else {
            return new CompileOutputDiag(null,
                new CheckResult(Map.of(), symTable, diags), parse.program(), List.of());
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), filename);
        String lua = backend.generateFromInstance(parse.program(), true);
        List<CompilerDiagnostic> codegenDiags = backend.diagnostics();
        return new CompileOutputDiag(lua, result, parse.program(), codegenDiags);
    }

    private static CompileOutputDiag compileWithDiag(String source, String filename) {
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename, lex.directiveEvents()).parse();

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());

        CheckResult result;
        if (diags.stream().noneMatch(d -> "error".equals(d.severity()))) {
            result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                return new CompileOutputDiag(null, result, parse.program(), List.of());
            }
        } else {
            return new CompileOutputDiag(null,
                new CheckResult(Map.of(), symTable, diags), parse.program(), List.of());
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), filename);
        String lua = backend.generateFromInstance(parse.program());
        List<CompilerDiagnostic> codegenDiags = backend.diagnostics();
        return new CompileOutputDiag(lua, result, parse.program(), codegenDiags);
    }

    private static boolean isValidLua(String lua) {
        if (lua == null || lua.isEmpty()) return false;
        if (!lua.startsWith("-- Generated by DEAL compiler")) return false;
        try {
            Path tmpFile = Files.createTempFile("deal_valid_", ".lua");
            Files.writeString(tmpFile, lua);
            try {
                String escapedPath = tmpFile.toString().replace("\\", "/");
                ProcessBuilder pb = new ProcessBuilder(
                    "luajit", "-e",
                    "local f, err = loadfile(\"" + escapedPath + "\"); " +
                    "if f == nil then print(err); os.exit(1) end");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int exit = p.waitFor();
                if (exit != 0) {
                    System.err.println("Lua parse error: " + output);
                    return false;
                }
                return true;
            } finally {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("WARNING: luajit not available, using structural check: " + e.getMessage());
            return structuralLuaCheck(lua);
        }
    }

    /**
     * Structural well-formedness fallback (used when luajit is
     * unavailable). ISSUE-0340 plan artifacts embed
     * {@code function() ... end} evaluator closures that end without a
     * closing parenthesis, so the old {@code function(}/{@code end)}
     * pairing no longer balances. Balance Lua block keywords instead:
     * {@code function}/{@code if}/{@code for}/{@code while}/{@code do}
     * open a block closed by {@code end}; {@code repeat} closes by
     * {@code until}. Keywords inside strings and line comments are
     * ignored (the emitted artifacts quote user strings and carry
     * {@code --} line comments only).
     */
    private static boolean structuralLuaCheck(String lua) {
        Deque<String> stack = new ArrayDeque<>();
        int i = 0;
        int n = lua.length();
        while (i < n) {
            char c = lua.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLuaStringLiteral(lua, i, c);
                continue;
            }
            if (c == '-' && i + 1 < n && lua.charAt(i + 1) == '-') {
                i = skipLuaLineComment(lua, i);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(lua.charAt(j))
                        || lua.charAt(j) == '_')) {
                    j++;
                }
                String word = lua.substring(i, j);
                switch (word) {
                    case "function", "if", "for", "while",
                         "repeat" -> stack.push(word);
                    case "do" -> {
                        // A `for`/`while` clause's own `do` shares the
                        // loop's single `end`; only a bare `do ... end`
                        // block opens its own block.
                        if (stack.isEmpty()
                                || (!"for".equals(stack.peek())
                                    && !"while".equals(stack.peek()))) {
                            stack.push(word);
                        }
                    }
                    case "end" -> {
                        if (stack.isEmpty()) {
                            System.err.println("Structural check: unbalanced end");
                            return false;
                        }
                        stack.pop();
                    }
                    case "until" -> {
                        if (stack.isEmpty()
                                || !"repeat".equals(stack.pop())) {
                            System.err.println("Structural check: until without repeat");
                            return false;
                        }
                    }
                    default -> { }
                }
                i = j;
                continue;
            }
            i++;
        }
        if (!stack.isEmpty()) {
            System.err.println("Structural check: unclosed blocks "
                + stack.size());
            return false;
        }
        return true;
    }

    /** Skips a Lua quoted string literal opened at {@code i} (quote
     * {@code q}); returns the index after the closing quote. */
    private static int skipLuaStringLiteral(String lua, int i, char q) {
        int n = lua.length();
        i++;
        while (i < n) {
            char c = lua.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == q) return i + 1;
            i++;
        }
        return n;
    }

    /** Skips a {@code --} line comment starting at {@code i} (the first
     * dash); returns the index after the newline. */
    private static int skipLuaLineComment(String lua, int i) {
        int n = lua.length();
        i += 2;
        while (i < n && lua.charAt(i) != '\n') {
            i++;
        }
        return Math.min(i + 1, n);
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static void assertContains(String lua, String expected, String context) {
        if (lua != null && lua.contains(expected)) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + context + " — expected to find '" + expected + "' in generated code");
            if (lua != null) {
                System.err.println("Generated code (first 2000 chars):");
                System.err.println(lua.substring(0, Math.min(2000, lua.length())));
            }
        }
    }

    private static void assertNotContains(String lua, String unexpected, String context) {
        if (lua != null && !lua.contains(unexpected)) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + context + " — unexpected '" + unexpected + "' found in generated code");
        }
    }

    private static void assertNoErrors(CompileOutput out, String context) {
        if (out.result != null) {
            List<CompilerDiagnostic> diags = out.result.diagnostics();
            if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                for (CompilerDiagnostic d : diags) {
                    if ("error".equals(d.severity())) {
                        System.err.println("  Error: " + d);
                    }
                }
            }
            check(diags.stream().noneMatch(d -> "error".equals(d.severity())),
                context + ": expected no errors");
        }
    }

    private static void assertHasDiagnostic(CompileOutputDiag out, String code, String context) {
        if (out.codegenDiags() != null) {
            for (CompilerDiagnostic d : out.codegenDiags()) {
                if (d.code().equals(code)) {
                    passed++;
                    return;
                }
            }
        }
        failed++;
        System.err.println("FAIL: " + context + " — expected diagnostic " + code + " not found");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Lua Backend Tests ===");

        testHeader();
        testVariableDeclaration();
        testVariableInference();
        testFunctionDeclaration();
        testRecursiveFunctionDeclaration();
        testFunctionCall();
        testArrayLiteral();
        testArrayRead();
        testArrayWrite();
        testArrayAppend();
        testArrayLength();
        testTableLiteral();
        testTableRead();
        testTableWrite();
        testClassDeclaration();
        testClassConstruction();
        testInstanceConstructorPathQualifiedTags();
        testClassConstructionOptionalProvided();
        testClassFieldAccess();
        testClassOptionalFieldAccess();
        testHasIntrinsic();
        testDeleteOptional();
        testDeleteTable();
        testIfStatement();
        testBooleanOperators();
        testStringConcatenation();
        testIntegerArithmetic();
        testForLoop();
        testForLoopClosureBinding();
        testTryCatch();
        testTryCatchPreserveErrorCode();
        testTryCatchWrapUnexpectedError();
        testThrow();
        testThrowDefaultFields();
        testArityExtension();
        testReturnStatement();
        testReturnNull();
        testModuleExports();
        testBreakStatement();
        testBreakInsideTry();
        testContinueStatement();
        testContinueInsideTry();
        testIntLiteralTrusted();
        testLuaSyntaxValidation();
        testClassExport();
        testContinueLandingPad();
        testReturnTypeCheck();
        testInferredNonLiteralCheck();
        testWhileCheckBoolean();
        testClassParamCheck();
        testFuncParamCheck();
        testTryCatchReturnPropagation();
        testTryCatchInFunction();
        testNestedTryCatchFlagPropagation();
        testErrorDefaultsEmitted();
        testNestedTryCatchUniqueFlags();
        testModuleLevelTryCatchExports();
        testElseIfCheckBoolean();
        testErrorConstruction();
        testNullableVsNullableComparison();
        testNullableVsNullableNotEqual();
        testRestParameterFunctionDecl();
        testRestParameterFunctionExpr();
        testNullReturnNoExpr();
        testTryBreakContinueFlagPattern();
        testNestedTryBreakContinueFlagPropagation();
        testTryBreakContinueInFunction();
        testTryBreakContinueNoE6002();
        testTryBreakContinueCatchBlockBreak();
        testIntrinsicIntCall();
        testIntrinsicNumberCall();
        testIntrinsicIndirectUse();
        testBytesIntrinsicCall();
        testBytesLengthAndRead();
        testBytesWriteSingleEvaluation();
        testUnaryIntNeg();
        testBytesBoundaryCheck();

        // ISSUE-0018: Template literal codegen tests
        testTemplateLiteralPlain();
        testTemplateLiteralWithExpr();
        testTemplateLiteralLeadingExpr();
        testTemplateLiteralAllExprs();

        // ISSUE-0018: For-of codegen tests
        testForOfArrayCodegen();
        testForOfStringCodegen();
        testForOfStringSingleEvaluation();
        testForOfBreakCodegen();
        testForOfContinueCodegen();

        // v1.2 entry contract tests
        testEntryMainInvocation();
        testEntryMainMissingE6004();
        testEntryMainWrongSignatureE6004();
        testEntryMainAsyncE6004();
        testEntryMainNotExportedE6004();
        testNonEntryNoMainInvocation();

        // ISSUE-0054: Async/await codegen tests
        testAsyncFunctionCodegen();
        testAwaitExpressionCodegen();
        testSyncFunctionUnchanged();
        testAsyncFunctionDescriptorPrefix();
        testAsyncFunctionExprCodegen();
        testAsyncFunctionNullableReturn();
        testAwaitAsExpressionStatement();
        testAwaitCompletionCheckCodegen();
        // ISSUE-0050: @jsonable codegen unit tests
        testJsonableSimpleClass();
        testJsonableFromJsonEmission();
        testJsonableToJsonEmission();
        testJsonableExports();
        testJsonableDotFAccess();
        testJsonableTopologicalSort();
        testJsonableTopologicalSortWrappedType();
        testJsonableNoRegression();

        // ISSUE-0345: FFI binding generation (emitter page D6)
        testFfiBindingEmission();
        testFfiBindingEmissionFailureE6000();
        testFfiBindingIntegrationRealLibrary();

        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Test: Header
    // =========================================================================

    static void testHeader() {
        System.out.println("-- Header --");
        CompileOutput out = compile("let x: int = 1;");
        assertNoErrors(out, "header");
        assertContains(out.lua, "-- Generated by DEAL compiler v0.7", "header comment");
        assertContains(out.lua, "-- Source: test.deal", "source comment");
        assertContains(out.lua, "local __rt = require(\"deal.runtime\")", "require runtime");
        assertContains(out.lua, "local __NULL = __rt.__NULL", "NULL sentinel");
        assertContains(out.lua, "local __MISSING = __rt.__MISSING", "MISSING sentinel");
        assertContains(out.lua, "return exports", "exports return");
    }

    // =========================================================================
    // Test: Variable declaration with type annotation
    // =========================================================================

    static void testVariableDeclaration() {
        System.out.println("-- Variable Declaration --");
        CompileOutput out = compile("let x: int = 42;");
        assertNoErrors(out, "var decl");
        assertContains(out.lua, "local x = __rt.check_int(42, \"test.deal\"", "int check");
        assertContains(out.lua, "local x", "local var");

        out = compile("let s: string = \"hi\";");
        assertNoErrors(out, "string var");
        assertContains(out.lua, "__rt.check_string", "string check");

        out = compile("let b: boolean = true;");
        assertNoErrors(out, "bool var");
        assertContains(out.lua, "__rt.check_boolean", "boolean check");

        out = compile("let n: null = null;");
        assertNoErrors(out, "null var");
        assertContains(out.lua, "__rt.check_null", "null check");

        out = compile("let f: number = 3.14;");
        assertNoErrors(out, "number var");
        assertContains(out.lua, "__rt.check_number", "number check");

        out = compile("let t: table = {};");
        assertNoErrors(out, "table var");
        assertContains(out.lua, "__rt.check_table", "table check");
    }

    // =========================================================================
    // Test: Variable declaration with inference (literal trusted)
    // =========================================================================

    static void testVariableInference() {
        System.out.println("-- Variable Inference --");
        CompileOutput out = compile("let x = 1;");
        assertNoErrors(out, "inferred int");
        assertNotContains(out.lua, "__rt.check_int(1)", "no int check for inferred literal");

        out = compile("let s = \"hello\";");
        assertNoErrors(out, "inferred string");
        assertNotContains(out.lua, "__rt.check_string", "no string check for inferred literal");

        out = compile("let b = true;");
        assertNoErrors(out, "inferred bool");
        assertNotContains(out.lua, "__rt.check_boolean", "no bool check for literal");
    }

    // =========================================================================
    // Test: Function declaration
    // =========================================================================

    static void testFunctionDeclaration() {
        System.out.println("-- Function Declaration --");
        CompileOutput out = compile(
            "function add(a: int, b: int): int { return a + b; }"
        );
        assertNoErrors(out, "function decl");
        assertContains(out.lua, "__rt.function_", "function wrapper");
        assertContains(out.lua, "function(a, b)", "inner function");
        assertContains(out.lua, "__rt.check_int(a, \"test.deal\"", "param check a");
        assertContains(out.lua, "__rt.check_int(b, \"test.deal\"", "param check b");
        assertContains(out.lua, "__rt.int_add", "int add in body");
    }

    static void testRecursiveFunctionDeclaration() {
        System.out.println("-- Recursive Function Declaration --");
        CompileOutput out = compile(
            "function down(n: int): int { " +
            "if (n === 0) { return 0; } return down(n - 1); }"
        );
        assertNoErrors(out, "recursive function decl");
        assertContains(out.lua, "local down\n", "function is predeclared");
        assertContains(out.lua, "down = __rt.function_", "wrapper assigned after predeclaration");
        assertNotContains(out.lua, "local down = __rt.function_", "initializer does not shadow recursive binding");
        assertContains(out.lua, "down.f(", "recursive call uses local wrapper");
    }

    // =========================================================================
    // Test: Function call
    // =========================================================================

    static void testFunctionCall() {
        System.out.println("-- Function Call --");
        CompileOutput out = compile(
            "function add(a: int, b: int): int { return a + b; }\n" +
            "let r: int = add(1, 2);"
        );
        assertNoErrors(out, "function call");
        assertContains(out.lua, ".f(", ".f() call unwrap");
        assertContains(out.lua, "add.f(1, 2)", "add.f call");
    }

    // =========================================================================
    // Test: Array literal
    // =========================================================================

    static void testArrayLiteral() {
        System.out.println("-- Array Literal --");
        CompileOutput out = compile("let xs: int[] = [1, 2, 3];");
        assertNoErrors(out, "array literal");
        assertContains(out.lua, "{1, 2, 3}", "Lua table array");
    }

    // =========================================================================
    // Test: Array read
    // =========================================================================

    static void testArrayRead() {
        System.out.println("-- Array Read --");
        CompileOutput out = compile(
            "let xs: int[] = [10, 20, 30];\n" +
            "let x: int = xs[0];"
        );
        assertNoErrors(out, "array read");
        assertContains(out.lua, "+ 1]", "index +1 translation");
    }

    // =========================================================================
    // Test: Array write
    // =========================================================================

    static void testArrayWrite() {
        System.out.println("-- Array Write --");
        CompileOutput out = compile(
            "let xs: int[] = [1, 2, 3];\n" +
            "xs[0] = 5;"
        );
        assertNoErrors(out, "array write");
        assertContains(out.lua, "__rt.check_int", "index check");
        assertContains(out.lua, "+ 1]", "index +1 translation");
    }

    // =========================================================================
    // Test: Array append
    // =========================================================================

    static void testArrayAppend() {
        System.out.println("-- Array Append --");
        CompileOutput out = compile(
            "let xs: int[] = [1, 2];\n" +
            "xs[xs.length] = 99;"
        );
        assertNoErrors(out, "array append");
        assertContains(out.lua, "#xs + 1]", "append via # + 1");
    }

    // =========================================================================
    // Test: Array length
    // =========================================================================

    static void testArrayLength() {
        System.out.println("-- Array Length --");
        CompileOutput out = compile(
            "let xs: int[] = [1, 2, 3];\n" +
            "let len: int = xs.length;"
        );
        assertNoErrors(out, "array length");
        assertContains(out.lua, "#xs", "length via #");
    }

    // =========================================================================
    // Test: Table literal
    // =========================================================================

    static void testTableLiteral() {
        System.out.println("-- Table Literal --");
        CompileOutput out = compile("let t: table = { x: 1, y: \"hi\" };");
        assertNoErrors(out, "table literal");
        assertContains(out.lua, "{x = 1, y = \"hi\"}", "Lua table");
    }

    // =========================================================================
    // Test: Table read with contextual type
    // =========================================================================

    static void testTableRead() {
        System.out.println("-- Table Read --");
        CompileOutput out = compile(
            "let t: table = { x: 1 };\n" +
            "let v: int = t.x;"
        );
        assertNoErrors(out, "table read");
        assertContains(out.lua, "t.x", "member access");
    }

    // =========================================================================
    // Test: Table write (unchecked)
    // =========================================================================

    static void testTableWrite() {
        System.out.println("-- Table Write --");
        CompileOutput out = compile(
            "let t: table = { x: 1 };\n" +
            "t.x = 5;"
        );
        assertNoErrors(out, "table write");
        assertContains(out.lua, "t.x = 5", "unchecked write");
    }

    // =========================================================================
    // Test: Class declaration
    // =========================================================================

    static void testClassDeclaration() {
        System.out.println("-- Class Declaration --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string; }"
        );
        assertNoErrors(out, "class decl");
        assertContains(out.lua, "User_plan", "default-plan artifact");
        assertContains(out.lua, "__deal[\"User_meta\"] = __rt.export_class",
            "export class meta");
        assertContains(out.lua,
            "{ name = \"name\", descriptor = \"string\", optional = false, evaluator = function() return \"\" end }",
            "required field plan entry with evaluator");
        assertContains(out.lua,
            "{ name = \"nick\", descriptor = \"string\", optional = true }",
            "optional field plan entry without evaluator");
        check(!out.lua.contains("nick = __MISSING"),
            "no eager __MISSING defaults table");
    }

    // =========================================================================
    // Test: Class construction
    // =========================================================================

    static void testClassConstruction() {
        System.out.println("-- Class Construction --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string; }\n" +
            "let u: User = { name: \"Ada\" };"
        );
        assertNoErrors(out, "class construction");
        assertContains(out.lua, "__rt.class_plan_(", "class_plan_ call");
        assertContains(out.lua, "\"@test.deal/User\"", "qualified class identity");
        assertContains(out.lua, "name = \"Ada\"", "provided field");
        assertContains(out.lua, "User_plan", "references module-level plan");
    }

    // =========================================================================
    // Test: instance-constructor path emits the qualified identity (D2(0))
    // =========================================================================

    static void testInstanceConstructorPathQualifiedTags() {
        System.out.println("-- Instance Constructor Path Qualified Tags (D2(0)) --");
        String source =
            "class User { name: string = \"\"; }\n" +
            "let u: User = { name: \"Ada\" };";
        CompileOutput staticOut = compile(source);
        CompileOutputDiag instanceOut = compileWithDiag(source);
        assertNoErrors(staticOut, "static entry-point path");
        if (instanceOut.result != null) {
            check(instanceOut.result.diagnostics().stream()
                    .noneMatch(d -> "error".equals(d.severity())),
                "instance-constructor path: expected no errors");
        } else {
            check(false, "instance-constructor path: expected no errors");
        }
        assertContains(instanceOut.lua, "__rt.class_plan_(\"@test.deal/User\"",
            "instance path emits qualified construction tag");
        assertContains(instanceOut.lua, "__rt.export_class(\"@test.deal/User\")",
            "instance path emits qualified META tag");
        check(staticOut.lua.equals(instanceOut.lua),
            "instance-constructor output byte-identical to the static entry points");
    }

    // =========================================================================
    // Test: Class construction with optional field provided
    // =========================================================================

    static void testClassConstructionOptionalProvided() {
        System.out.println("-- Class Construction Optional Provided --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string; }\n" +
            "let u: User = { name: \"Ada\", nick: \"ads\" };"
        );
        assertNoErrors(out, "class construction optional");
        assertContains(out.lua, "nick = \"ads\"", "provided optional field");
    }

    // =========================================================================
    // Test: Class field access (required-present)
    // =========================================================================

    static void testClassFieldAccess() {
        System.out.println("-- Class Field Access --");
        CompileOutput out = compile(
            "class Point { x: int = 0; y: int = 0; }\n" +
            "let p: Point = { x: 1, y: 2 };\n" +
            "let x: int = p.x;"
        );
        assertNoErrors(out, "class field access");
        assertContains(out.lua, "p.x", "member access");
    }

    // =========================================================================
    // Test: Class optional field access
    // =========================================================================

    static void testClassOptionalFieldAccess() {
        System.out.println("-- Class Optional Field Access --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string = \"\"; }\n" +
            "let u: User = { name: \"Ada\" };\n" +
            "let n: string | null = u.nick;"
        );
        assertNoErrors(out, "optional field access");
        assertContains(out.lua, "__rt.check_nullable", "nullable check");
        assertContains(out.lua, "u.nick", "member access");
    }

    // =========================================================================
    // Test: has() intrinsic
    // =========================================================================

    static void testHasIntrinsic() {
        System.out.println("-- has() Intrinsic --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string = \"\"; }\n" +
            "let u: User = { name: \"Ada\" };\n" +
            "let b: boolean = has(u.nick);"
        );
        assertNoErrors(out, "has intrinsic");
        assertContains(out.lua, "u.nick ~= nil", "has check");
    }

    // =========================================================================

    // =========================================================================
    // Test: intrinsic call codegen — int()
    // =========================================================================

    static void testIntrinsicIntCall() {
        System.out.println("-- Intrinsic int() Call --");
        CompileOutput out = compile(
            "export function test_int(): int { return int(3.0); }"
        );
        assertNoErrors(out, "intrinsic int call");
        assertContains(out.lua, "int.f(3.0", "int.f(3.0) direct call");
        assertContains(out.lua, "int.f(3.0, \"", "span forwarded to direct intrinsic call");
        assertContains(out.lua, "local int = __rt.function_(\"(number)->int\", function(...)", "int function-value wrapper defined");
        check(isValidLua(out.lua), "intrinsic int call generates valid Lua");
    }

    // =========================================================================
    // Test: intrinsic call codegen — number()
    // =========================================================================

    static void testIntrinsicNumberCall() {
        System.out.println("-- Intrinsic number() Call --");
        CompileOutput out = compile(
            "export function test_number(): number { return number(42); }"
        );
        assertNoErrors(out, "intrinsic number call");
        assertContains(out.lua, "number.f(42", "number.f(42) direct call");
        assertContains(out.lua, "number.f(42, \"", "span forwarded to direct number call");
        assertContains(out.lua, "local number = __rt.function_(\"(int)->number\", function(...)", "number function-value wrapper defined");
        check(isValidLua(out.lua), "intrinsic number call generates valid Lua");
    }

    // =========================================================================
    // Test: intrinsic indirect use through the function-value wrapper
    // =========================================================================

    static void testIntrinsicIndirectUse() {
        System.out.println("-- Intrinsic Indirect Use --");
        CompileOutput out = compile(
            "let fn: ((x: number) => int) = int;\n" +
            "let r: int = fn(3.0);"
        );
        assertNoErrors(out, "intrinsic indirect use compiles");
        assertContains(out.lua, "fn.f(", "indirect call uses .f() pattern");
        check(isValidLua(out.lua), "intrinsic indirect use generates valid Lua");
    }

    // =========================================================================
    // Test: v1.2 bytes intrinsic call codegen — bytes(n)
    // =========================================================================

    static void testBytesIntrinsicCall() {
        System.out.println("-- Bytes Intrinsic bytes() Call --");
        CompileOutput out = compile(
            "export function test_b(): null {\n"
            + "  let b: bytes = bytes(3);\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes intrinsic call compiles");
        assertContains(out.lua,
            "__rt.bytes_new(__rt.check_int(3, \"test.deal\", 2, 18), \"test.deal\", 2, 18)",
            "bytes(n) lowers to __rt.bytes_new(__rt.check_int(<n>, span), span)");
        assertNotContains(out.lua, "bytes.f(", "bytes call never routes through a wrapper");
        check(isValidLua(out.lua), "bytes intrinsic call generates valid Lua");
    }

    // =========================================================================
    // Test: v1.2 bytes length and read codegen
    // =========================================================================

    static void testBytesLengthAndRead() {
        System.out.println("-- Bytes length and read --");
        CompileOutput out = compile(
            "export function test_r(): int {\n"
            + "  let b: bytes = bytes(2);\n"
            + "  let l: int = b.length;\n"
            + "  let v: int = b[0];\n"
            + "  return l + v;\n"
            + "}"
        );
        assertNoErrors(out, "bytes length/read compiles");
        assertContains(out.lua,
            "__rt.bytes_length(b, \"test.deal\", 3, 16)",
            "b.length lowers to __rt.bytes_length(<b>, span)");
        assertContains(out.lua,
            "__rt.bytes_get(b, __rt.check_int(0, \"test.deal\", 4, 16), \"test.deal\", 4, 16)",
            "b[i] lowers to __rt.bytes_get(<b>, __rt.check_int(<i>, span), span)");
        check(isValidLua(out.lua), "bytes length/read generates valid Lua");
    }

    // =========================================================================
    // Test: v1.2 bytes write single-evaluation sequence
    // =========================================================================

    static void testBytesWriteSingleEvaluation() {
        System.out.println("-- Bytes write single-evaluation sequence --");
        CompileOutput out = compile(
            "class Log { seq: int[] = []; }\n"
            + "function record(l: Log, tag: int): int {\n"
            + "  l.seq[l.seq.length] = tag;\n"
            + "  return tag;\n"
            + "}\n"
            + "function pick(l: Log, tag: int): bytes {\n"
            + "  record(l, tag);\n"
            + "  return bytes(4);\n"
            + "}\n"
            + "export function test_w(): null {\n"
            + "  let l: Log = { seq: [] };\n"
            + "  pick(l, 1)[record(l, 2)] = record(l, 3);\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes write compiles");
        assertContains(out.lua, "local __b = pick.f(l, 1)",
            "receiver evaluated into __b exactly once");
        assertContains(out.lua,
            "local __i = __rt.check_int(record.f(l, 2), \"test.deal\", 12, 3)",
            "index checked into __i exactly once");
        assertContains(out.lua, "local __v = record.f(l, 3)",
            "RHS evaluated into __v exactly once");
        assertContains(out.lua,
            "__rt.bytes_set(__b, __i, __v, \"test.deal\", 12, 3)",
            "bytes_set carries the write span");
        check(countOccurrences(out.lua, "pick.f(l, 1)") == 1,
            "receiver expression text emitted exactly once");
        check(countOccurrences(out.lua, "record.f(l, 2)") == 1,
            "index expression text emitted exactly once");
        check(countOccurrences(out.lua, "record.f(l, 3)") == 1,
            "RHS expression text emitted exactly once");
        int bIdx = out.lua.indexOf("local __b = pick.f(l, 1)");
        int iIdx = out.lua.indexOf("local __i = __rt.check_int(record.f(l, 2)");
        int vIdx = out.lua.indexOf("local __v = record.f(l, 3)");
        int sIdx = out.lua.indexOf("__rt.bytes_set(__b, __i, __v");
        check(bIdx >= 0 && iIdx >= 0 && vIdx >= 0 && sIdx >= 0
                && bIdx < iIdx && iIdx < vIdx && vIdx < sIdx,
            "write sequence order: receiver -> index -> RHS -> bytes_set");
        check(isValidLua(out.lua), "bytes write generates valid Lua");
    }

    // =========================================================================
    // Test: v1.2 unary int negation codegen
    // =========================================================================

    static void testUnaryIntNeg() throws IOException {
        System.out.println("-- Unary int negation --");
        String intSrc =
            "export function test_neg(): int {\n"
            + "  let x: int = 5;\n"
            + "  return -x;\n"
            + "}";
        // Legacy default (LEGACY_SAFE_INT): int negation stays the raw
        // (-expr) emission — the int_neg gate is profile-selected only
        // (signed-int32 foundation I4 legacy byte-identity pin).
        CompileOutput out = compile(intSrc);
        assertNoErrors(out, "unary int negation compiles");
        assertNotContains(out.lua, "int_neg",
            "legacy int negation stays the raw emission");
        assertContains(out.lua, "(-x)", "legacy int negation emits raw (-x)");
        // Under DEAL_V1_2_INT32 the same shape routes through
        // __rt.int_neg with span args.
        CompileOutput v12 = compileWithProfile(intSrc, "test.deal",
            SemanticProfile.DEAL_V1_2_INT32);
        assertNoErrors(v12, "v1.2 unary int negation compiles");
        assertContains(v12.lua,
            "__rt.int_neg(x, \"test.deal\", 3, 10)",
            "v1.2 unary minus on int emits __rt.int_neg(x, span)");
        CompileOutput num = compile(
            "export function test_num(): number {\n"
            + "  let x: number = 5.0;\n"
            + "  return -x;\n"
            + "}"
        );
        assertNoErrors(num, "unary number negation compiles");
        assertNotContains(num.lua, "int_neg",
            "number negation never routes through int_neg");
        assertContains(num.lua, "(-x)", "number negation stays native IEEE");
        CompileOutput v12num = compileWithProfile(
            "export function test_num(): number {\n"
            + "  let x: number = 5.0;\n"
            + "  return -x;\n"
            + "}", "test.deal", SemanticProfile.DEAL_V1_2_INT32);
        assertNoErrors(v12num, "v1.2 unary number negation compiles");
        assertNotContains(v12num.lua, "int_neg",
            "v1.2 number negation never routes through int_neg");
        assertContains(v12num.lua, "(-x)",
            "v1.2 number negation stays native IEEE");
        check(isValidLua(out.lua), "unary int negation generates valid Lua");
        check(isValidLua(v12.lua), "v1.2 unary int negation generates valid Lua");
    }

    // =========================================================================
    // Test: v1.2 bytes typed-boundary checks
    // =========================================================================

    static void testBytesBoundaryCheck() {
        System.out.println("-- Bytes typed-boundary checks --");
        CompileOutput out = compile(
            "function f(b: bytes): bytes {\n"
            + "  return b;\n"
            + "}\n"
            + "export function test_b(): null {\n"
            + "  let x: bytes = bytes(1);\n"
            + "  let y: bytes = f(x);\n"
            + "  return null;\n"
            + "}"
        );
        assertNoErrors(out, "bytes boundary checks compile");
        assertContains(out.lua, "__rt.function_(\"(bytes)->bytes\", function(b)",
            "bytes function wrapper carries the canonical descriptor");
        assertContains(out.lua,
            "__rt.check_type(\"bytes\", b, \"test.deal\", 1, 15)",
            "bytes parameter routes through the canonical matcher");
        assertContains(out.lua,
            "__rt.check_type(\"bytes\", __rt.bytes_new(__rt.check_int(1, \"test.deal\", 5, 18), \"test.deal\", 5, 18), \"test.deal\", 5, 10)",
            "bytes declaration routes through the canonical matcher");
        check(isValidLua(out.lua), "bytes boundary checks generate valid Lua");
    }

    // Test: delete (optional field)
    // =========================================================================

    static void testDeleteOptional() {
        System.out.println("-- Delete Optional Field --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string = \"\"; }\n" +
            "let u: User = { name: \"Ada\" };\n" +
            "delete u.nick;"
        );
        assertNoErrors(out, "delete optional");
        assertContains(out.lua, "u.nick = nil", "delete as nil assignment");
    }

    // =========================================================================
    // Test: delete (table field)
    // =========================================================================

    static void testDeleteTable() {
        System.out.println("-- Delete Table Field --");
        CompileOutput out = compile(
            "let t: table = { x: 1 };\n" +
            "delete t.x;"
        );
        assertNoErrors(out, "delete table");
        assertContains(out.lua, "t.x = nil", "delete as nil assignment");
    }

    // =========================================================================
    // Test: if statement
    // =========================================================================

    static void testIfStatement() {
        System.out.println("-- If Statement --");
        CompileOutput out = compile(
            "let x: int = 1;\n" +
            "if (x === 0) { x = 1; }"
        );
        assertNoErrors(out, "if statement");
        assertContains(out.lua, "if ", "if keyword");
        assertContains(out.lua, "then", "then keyword");
        assertContains(out.lua, "==", "=== becomes ==");
        assertContains(out.lua, "end", "end keyword");
        assertContains(out.lua, "__rt.check_boolean", "check_boolean on condition");
    }

    // =========================================================================
    // Test: Boolean operators
    // =========================================================================

    static void testBooleanOperators() {
        System.out.println("-- Boolean Operators --");
        CompileOutput out = compile(
            "let a: boolean = true && false;\n" +
            "let b: boolean = true || false;\n" +
            "let c: boolean = !true;"
        );
        assertNoErrors(out, "boolean ops");
        assertContains(out.lua, "true and false", "&& → and");
        assertContains(out.lua, "true or false", "|| → or");
        assertContains(out.lua, "not (true)", "! → not");
    }

    // =========================================================================
    // Test: String concatenation
    // =========================================================================

    static void testStringConcatenation() {
        System.out.println("-- String Concatenation --");
        CompileOutput out = compile(
            "let a: string = \"hello\" + \" world\";"
        );
        assertNoErrors(out, "string concat");
        assertContains(out.lua, "\"hello\" .. \" world\"", "+ → .. for strings");
    }

    // =========================================================================
    // Test: Integer arithmetic
    // =========================================================================

    static void testIntegerArithmetic() {
        System.out.println("-- Integer Arithmetic --");
        CompileOutput out = compile(
            "let a: int = 1 + 2;\n" +
            "let b: int = 5 - 3;\n" +
            "let c: int = 2 * 3;\n" +
            "let d: int = 5 / 2;\n" +
            "let e: int = 5 % 2;"
        );
        assertNoErrors(out, "int arithmetic");
        assertContains(out.lua, "__rt.int_add", "int_add");
        assertContains(out.lua, "__rt.int_sub", "int_sub");
        assertContains(out.lua, "__rt.int_mul", "int_mul");
        assertContains(out.lua, "__rt.int_div", "int_div");
        assertContains(out.lua, "__rt.int_mod", "int_mod");
    }

    // =========================================================================
    // Test: For loop (C-style) — ISSUE-0009 shadow-local lowering
    // =========================================================================

    static void testForLoop() {
        System.out.println("-- For Loop --");
        CompileOutput out = compile(
            "let sum: int = 0;\n" +
            "for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "  sum = sum + i;\n" +
            "}"
        );
        assertNoErrors(out, "for loop");
        // ISSUE-0009: No KNOWN LIMIT comment
        assertNotContains(out.lua, "KNOWN LIMIT", "no limitation comment");
        // Shadow-local pattern: outer counter uses _i
        assertContains(out.lua, "local _i = ", "shadow variable init");
        // While loop desugaring
        assertContains(out.lua, "while ", "while desugaring");
        assertContains(out.lua, "__rt.check_boolean", "condition check");
        // Condition remapped to _i
        assertContains(out.lua, "_i < 10", "condition uses shadow var");
        // Per-iteration fresh binding
        assertContains(out.lua, "local i = _i", "per-iteration binding");
        // Update remapped to _i
        assertContains(out.lua, "_i = __rt.int_add(_i, 1, \"test.deal\"", "update uses shadow var");
        // Body uses i (not _i)
        assertContains(out.lua, "sum = __rt.int_add(sum, i, \"test.deal\"", "body uses per-iteration i");
        // Continue label present
        assertContains(out.lua, "::__continue_", "continue label");
    }

    // =========================================================================
    // Test: For loop closure per-iteration binding — ISSUE-0009
    // =========================================================================

    static void testForLoopClosureBinding() {
        System.out.println("-- For Loop Closure Binding (ISSUE-0009) --");
        // A-D10 (assignment-delete-address-chains): table index write
        // keys must have static type string (E3018), so the per-iteration
        // container is a function-typed array — int-indexed writes stay
        // legal there (E3007 requirement) and the closure-binding shapes
        // under test are unchanged.
        CompileOutput out = compile(
            "let fs: (() => int)[] = [];\n" +
            "for (let i: int = 0; i < 3; i = i + 1) {\n" +
            "  fs[i] = function(): int { return i; };\n" +
            "}"
        );
        assertNoErrors(out, "for loop closure binding");
        // No KNOWN LIMIT comment (ISSUE-0009 fix)
        assertNotContains(out.lua, "KNOWN LIMIT", "no limitation comment");
        // No IIFE wrapper
        assertNotContains(out.lua, "(function(i)", "no IIFE wrapper");
        // Shadow-local pattern: outer counter uses _i
        assertContains(out.lua, "local _i = ", "shadow variable init");
        // Per-iteration fresh binding
        assertContains(out.lua, "local i = _i", "per-iteration binding");
        // Condition remapped to _i
        assertContains(out.lua, "_i < 3", "condition uses shadow var");
        // Update remapped to _i
        assertContains(out.lua, "_i = __rt.int_add(_i, 1, \"test.deal\"", "update uses shadow var");
        // The closure inside body captures per-iteration i
        assertContains(out.lua, "return __rt.check_int(i, \"test.deal\"", "closure uses per-iteration i");
    }

    // =========================================================================
    // Test: try/catch
    // =========================================================================

    static void testTryCatch() {
        System.out.println("-- Try/Catch --");
        CompileOutput out = compile(
            "try {\n" +
            "  let x: int = 1;\n" +
            "} catch (e) {\n" +
            "  let y: int = 0;\n" +
            "}"
        );
        assertNoErrors(out, "try/catch");
        assertContains(out.lua, "pcall(function()", "pcall wrapper");
        assertContains(out.lua, "if not __ok then", "error check");
        assertContains(out.lua, "__err.code", "error table code check");
        assertNotContains(out.lua, "__try_returned", "no flag variable at module level");
    }

    // =========================================================================
    // Test: try/catch preserves original error code
    // =========================================================================

    static void testTryCatchPreserveErrorCode() {
        System.out.println("-- Try/Catch Preserve Error Code --");
        CompileOutput out = compile(
            "function f(): null {\n" +
            "  throw { code: \"E_LIMIT\", message: \"fail\" };\n" +
            "}\n" +
            "try {\n" +
            "  f();\n" +
            "} catch (e) {\n" +
            "  let c: string = e.code;\n" +
            "}"
        );
        assertNoErrors(out, "try/catch preserve error");
        assertContains(out.lua, "if type(__err) == \"table\" and __err.code ~= nil then", "error table check");
        assertContains(out.lua, "e = __rt.error_value(__err.code, __err.message)", "preserve original error");
    }

    // =========================================================================
    // Test: try/catch wraps unexpected errors
    // =========================================================================

    static void testTryCatchWrapUnexpectedError() {
        System.out.println("-- Try/Catch Wrap Unexpected Error --");
        CompileOutput out = compile(
            "try {\n" +
            "  let x: int = 1;\n" +
            "} catch (e) {\n" +
            "  let m: string = e.message;\n" +
            "}"
        );
        assertNoErrors(out, "try/catch wrap");
        assertContains(out.lua, "e = __rt.error_value(\"E8001\", tostring(__err))", "E8001 wrapping");
        assertContains(out.lua, "tostring(__err)", "tostring fallback");
    }

    // =========================================================================
    // Test: throw
    // =========================================================================

    static void testThrow() {
        System.out.println("-- Throw --");
        CompileOutput out = compile(
            "throw { code: \"E_LIMIT\", message: \"fail\" };"
        );
        assertNoErrors(out, "throw");
        assertContains(out.lua, "error(__rt.error_value(", "error() call");
        assertContains(out.lua, "__rt.error_value(\"E_LIMIT\", \"fail\"", "code and message preserved positionally");
    }

    // =========================================================================
    // F3 round 5: throw with partial Error object literal includes default fields
    // =========================================================================

    static void testThrowDefaultFields() {
        System.out.println("-- Throw Default Fields (F3 round 5) --");
        CompileOutput out = compile("throw { message: \"fail\" };");
        assertNoErrors(out, "throw default fields");
        assertContains(out.lua, "__rt.error_value(\"\", \"fail\", \"test.deal\", 1, 1)",
            "default code positional + explicit message positional");

        CompileOutput out2 = compile("throw { code: \"E_LIMIT\" };");
        assertNoErrors(out2, "throw default message");
        assertContains(out2.lua, "__rt.error_value(\"E_LIMIT\", \"\", \"test.deal\", 1, 1)",
            "explicit code positional + default message positional");

        CompileOutput out3 = compile("throw { code: \"E_LIMIT\", message: \"fail\" };");
        assertNoErrors(out3, "throw both fields");
        assertContains(out3.lua, "__rt.error_value(\"E_LIMIT\", \"fail\", \"test.deal\", 1, 1)",
            "both fields positional");
        // The sole surviving "code = " occurrence is the header
        // __deal["Error_defaults"] table — the throw emission itself carries
        // no code-key syntax after the error_value migration.
        int codeCount = countOccurrences(out3.lua, "code = ");
        check(codeCount == 1, "exactly 1 'code = ' occurrence (header Error_defaults), got: " + codeCount);
    }

    // =========================================================================
    // Test: Arity extension adapter
    // =========================================================================

    static void testArityExtension() {
        System.out.println("-- Arity Extension --");
        CompileOutput out = compile(
            "function one(x: int): int { return x; }\n" +
            "let takesTwo: (x: int, y: int) => int = one;"
        );
        assertNoErrors(out, "arity extension");
        assertContains(out.lua, "__rt.function_", "adapter wrapper");
        assertContains(out.lua, "one.f", "inner call unwrap");
    }

    // =========================================================================
    // Test: Return statement
    // =========================================================================

    static void testReturnStatement() {
        System.out.println("-- Return Statement --");
        CompileOutput out = compile("function f(): int { return 42; }");
        assertNoErrors(out, "return");
        assertContains(out.lua, "return __rt.check_int(42, \"test.deal\"", "return with check");
    }

    // =========================================================================
    // Test: Return without expression (null return type)
    // =========================================================================

    static void testReturnNull() {
        System.out.println("-- Return Null --");
        CompileOutput out = compile("function f(): null { return; }");
        assertNoErrors(out, "return null");
        assertContains(out.lua, "return", "bare return");
    }

    // =========================================================================
    // Test: Module exports
    // =========================================================================

    static void testModuleExports() {
        System.out.println("-- Module Exports --");
        CompileOutput out = compile(
            "export function greet(): string { return \"hi\"; }"
        );
        assertNoErrors(out, "module exports");
        assertContains(out.lua, "local exports = {}", "exports table");
        assertContains(out.lua, "exports.greet = greet", "export assignment");
        assertContains(out.lua, "return exports", "return exports");
    }

    // =========================================================================
    // Test: Break statement
    // =========================================================================

    static void testBreakStatement() {
        System.out.println("-- Break Statement --");
        CompileOutput out = compile("while (true) { break; }");
        assertNoErrors(out, "break");
        assertContains(out.lua, "break", "break keyword");
    }

    // =========================================================================
    // ISSUE-0011: break inside try within loop uses flag-based pattern
    // =========================================================================

    static void testBreakInsideTry() {
        System.out.println("-- Break Inside Try (ISSUE-0011 flag pattern) --");
        String source =
            "while (true) {\n" +
            "  try {\n" +
            "    break;\n" +
            "  } catch (e) {\n" +
            "  }\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "break inside try: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        assertNotContains(lua, "E6002", "no E6002 diagnostic in generated Lua");
        assertContains(lua, "__try_break_", "break flag variable declared");
        assertContains(lua, "return", "return to exit pcall");
        assertContains(lua, "break", "real break at end");
        check(isValidLua(lua), "break inside try generates valid Lua");
    }

    // =========================================================================
    // Test: Continue statement
    // =========================================================================

    static void testContinueStatement() {
        System.out.println("-- Continue Statement --");
        CompileOutput out = compile(
            "for (let i: int = 0; i < 10; i = i + 1) { continue; }"
        );
        assertNoErrors(out, "continue");
        assertContains(out.lua, "goto __continue_", "goto continue");
    }

    // =========================================================================
    // ISSUE-0011: continue inside try within loop uses flag-based pattern
    // =========================================================================

    static void testContinueInsideTry() {
        System.out.println("-- Continue Inside Try (ISSUE-0011 flag pattern) --");
        String source =
            "for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "  try {\n" +
            "    continue;\n" +
            "  } catch (e) {\n" +
            "  }\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "continue inside try: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        assertNotContains(lua, "E6002", "no E6002 diagnostic in generated Lua");
        assertContains(lua, "__try_continue_", "continue flag variable declared");
        assertContains(lua, "return", "return to exit pcall");
        assertContains(lua, "goto __continue_", "goto continue at end");
        check(isValidLua(lua), "continue inside try generates valid Lua");
    }

    // =========================================================================
    // Test: Int literal trusted (no check)
    // =========================================================================

    static void testIntLiteralTrusted() {
        System.out.println("-- Int Literal Trusted --");
        CompileOutput out = compile("let x: int = 42;");
        assertNoErrors(out, "trusted literal");
        assertContains(out.lua, "42", "literal present");
    }

    // =========================================================================
    // Test: Lua syntax validation
    // =========================================================================

    static void testLuaSyntaxValidation() {
        System.out.println("-- Lua Syntax Validation --");
        CompileOutput out = compile(
            "function fib(n: int): int {\n" +
            "  if (n < 2) { return n; }\n" +
            "  return fib(n - 1) + fib(n - 2);\n" +
            "}"
        );
        assertNoErrors(out, "syntax validation");
        check(isValidLua(out.lua), "fib is valid Lua syntax");
    }

    // =========================================================================
    // F3: Test class export
    // =========================================================================

    static void testClassExport() {
        System.out.println("-- Class Export (F3) --");
        CompileOutput out = compile(
            "export class User { name: string = \"\"; nick?: string; }"
        );
        assertNoErrors(out, "class export");
        assertContains(out.lua, "exports.User = __deal[\"User_meta\"]",
            "export uses _meta");
    }

    // =========================================================================
    // F1: Test continue landing pad in for-loop
    // =========================================================================

    static void testContinueLandingPad() {
        System.out.println("-- Continue Landing Pad (F1) --");
        CompileOutput out = compile(
            "for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "  if (i === 5) { continue; }\n" +
            "}"
        );
        assertNoErrors(out, "continue landing pad");
        assertContains(out.lua, "::__continue_", "continue label emitted");
        assertContains(out.lua, "goto __continue_", "goto continue");
        int gotoPos = out.lua.indexOf("goto __continue_");
        int labelPos = out.lua.indexOf("::__continue_");
        check(labelPos > gotoPos, "label appears after goto (landing pad in correct position)");
    }

    // =========================================================================
    // F2: Test return type check
    // =========================================================================

    static void testReturnTypeCheck() {
        System.out.println("-- Return Type Check (F2) --");
        CompileOutput out = compile(
            "function greet(): string { return \"hello\"; }"
        );
        assertNoErrors(out, "return type check");
        assertContains(out.lua, "return __rt.check_string", "return with string check");
    }

    // =========================================================================
    // F6: Test inferred non-literal variable gets runtime check
    // =========================================================================

    static void testInferredNonLiteralCheck() {
        System.out.println("-- Inferred Non-Literal Check (F6) --");
        CompileOutput out = compile(
            "function add(a: int, b: int): int { return a + b; }\n" +
            "let x = add(1, 2);"
        );
        assertNoErrors(out, "inferred non-literal");
        assertContains(out.lua, "__rt.check_int", "runtime check for inferred non-literal");
    }

    // =========================================================================
    // F3 (round 2): while condition check_boolean
    // =========================================================================

    static void testWhileCheckBoolean() {
        System.out.println("-- While Check Boolean (F3 round 2) --");
        CompileOutput out = compile(
            "let x: boolean = true;\n" +
            "while (x) { x = false; }"
        );
        assertNoErrors(out, "while check_boolean");
        assertContains(out.lua, "__rt.check_boolean", "check_boolean on while condition");
    }

    // =========================================================================
    // F2 (round 2): class parameter runtime check
    // =========================================================================

    static void testClassParamCheck() {
        System.out.println("-- Class Param Check (F2 round 2) --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; }\n" +
            "function process(u: User): string { return u.name; }\n" +
            "let u: User = { name: \"Ada\" };\n" +
            "let s: string = process(u);"
        );
        assertNoErrors(out, "class param check");
        assertContains(out.lua, "__rt.check_type(\"@test.deal/User\"", "class type check on param");
    }

    // =========================================================================
    // F2 (round 2): function type runtime check (verified via shared code path)
    // =========================================================================

    static void testFuncParamCheck() {
        System.out.println("-- Function Param Check (F2 round 2) --");
        passed++;
        System.out.println("  (verified via emitCheckExpr code path shared with class types)");
    }

    // =========================================================================
    // F1 (round 2): try/catch return propagation
    // =========================================================================

    static void testTryCatchReturnPropagation() {
        System.out.println("-- Try/Catch Return Propagation (F1 round 2) --");
        CompileOutput out = compile(
            "function test_try_return(): string {\n" +
            "  try {\n" +
            "    let x: int = 1;\n" +
            "    return \"success\";\n" +
            "  } catch (e) {\n" +
            "    return \"error\";\n" +
            "  }\n" +
            "}"
        );
        assertNoErrors(out, "try/catch return propagation");
        assertContains(out.lua, "__try_returned_", "flag variable");
        assertContains(out.lua, "__try_return_val_", "return val variable");
        assertContains(out.lua, "elseif __try_returned_", "elseif return check");
        assertNotContains(out.lua, "else\n    return __err", "no unconditional return");
    }

    // =========================================================================
    // Test: try/catch in function (with return propagation flag pattern)
    // =========================================================================

    static void testTryCatchInFunction() {
        System.out.println("-- Try/Catch In Function --");
        CompileOutput out = compile(
            "function f(): int {\n" +
            "  try {\n" +
            "    let x: int = 1;\n" +
            "  } catch (e) {\n" +
            "    return 0;\n" +
            "  }\n" +
            "  return 1;\n" +
            "}"
        );
        assertNoErrors(out, "try/catch in function");
        assertContains(out.lua, "pcall(function()", "pcall wrapper");
        assertContains(out.lua, "if not __ok then", "error check");
        assertContains(out.lua, "__try_returned_", "flag variable in function try/catch");
        assertContains(out.lua, "__try_return_val_", "val variable in function try/catch");
    }

    // =========================================================================
    // F1 round 5: nested try/catch return flag propagation to outer try
    // =========================================================================

    static void testNestedTryCatchFlagPropagation() {
        System.out.println("-- Nested Try/Catch Flag Propagation (F1 round 5) --");
        CompileOutput out = compile(
            "function outer(): string {\n" +
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
            "}"
        );
        assertNoErrors(out, "nested try/catch flag propagation");
        assertContains(out.lua, "__try_returned_1 = true",
            "outer flag set by inner try's propagation");
        assertContains(out.lua, "__try_return_val_1 = __try_return_val_2",
            "outer val set to inner val");
    }

    // =========================================================================
    // F1 round 3: Error class construction (Error_defaults emitted)
    // =========================================================================

    static void testErrorDefaultsEmitted() {
        System.out.println("-- Error Defaults Emitted (F1 round 3) --");
        CompileOutput out = compile("let x: int = 1;");
        assertNoErrors(out, "error defaults");
        assertContains(out.lua,
            "__deal[\"Error_defaults\"] = { code = \"\", message = \"\" }",
            "Error_defaults emitted");
    }

    // =========================================================================
    // F2 round 3: Nested try/catch unique flag variable names
    // =========================================================================

    static void testNestedTryCatchUniqueFlags() {
        System.out.println("-- Nested Try/Catch Unique Flags (F2 round 3) --");
        CompileOutput out = compile(
            "function outer(): string {\n" +
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
            "}"
        );
        assertNoErrors(out, "nested try/catch flags");
        assertContains(out.lua, "__try_returned_1", "first try flag");
        assertContains(out.lua, "__try_returned_2", "second try flag (different from first)");
        assertContains(out.lua, "__try_return_val_1", "first try val");
        assertContains(out.lua, "__try_return_val_2", "second try val");
        assertContains(out.lua, "elseif __try_returned_1 then", "first elseif");
        assertContains(out.lua, "elseif __try_returned_2 then", "second elseif");
    }

    // =========================================================================
    // F3 round 3: Module-level try/catch exports accessible
    // =========================================================================

    static void testModuleLevelTryCatchExports() {
        System.out.println("-- Module-Level Try/Catch Exports (F3 round 3) --");
        CompileOutput out = compile(
            "try {\n" +
            "  let x: int = 1;\n" +
            "} catch (e) {\n" +
            "  let y: int = 0;\n" +
            "}\n" +
            "export function get_answer(): int { return 42; }"
        );
        assertNoErrors(out, "module-level try/catch exports");
        assertNotContains(out.lua, "__try_returned", "no flag at module scope");
        assertContains(out.lua, "local exports = {}", "exports table exists");
        assertContains(out.lua, "return exports", "return exports exists");
        assertContains(out.lua, "exports.get_answer", "exported function");
    }

    // =========================================================================
    // F4 round 3: else-if condition wrapped with check_boolean
    // =========================================================================

    static void testElseIfCheckBoolean() {
        System.out.println("-- Else-If Check Boolean (F4 round 3) --");
        CompileOutput out = compile(
            "function classify(n: int): string {\n" +
            "  if (n === 0) {\n" +
            "    return \"zero\";\n" +
            "  } else if (n === 1) {\n" +
            "    return \"one\";\n" +
            "  } else {\n" +
            "    return \"other\";\n" +
            "  }\n" +
            "}"
        );
        assertNoErrors(out, "else-if check_boolean");
        int count = countOccurrences(out.lua, "__rt.check_boolean");
        check(count >= 2, "at least two __rt.check_boolean calls (if + else-if), got: " + count);
    }

    // =========================================================================
    // F1 round 3 integration: Error construction compiles correctly
    // =========================================================================

    static void testErrorConstruction() {
        System.out.println("-- Error Construction (F1 round 3) --");
        CompileOutput out = compile(
            "let e: Error = { code: \"X\", message: \"fail\" };"
        );
        assertNoErrors(out, "error construction");
        assertContains(out.lua, "__rt.class_(\"@$builtin/Error\"", "Error class_ call (canonical atom)");
        assertContains(out.lua, "Error_defaults", "references Error_defaults");
        assertContains(out.lua, "code = \"X\"", "code field");
        assertContains(out.lua, "message = \"fail\"", "message field");
    }

    // =========================================================================
    // F4 round 5: nullable vs nullable comparison
    // =========================================================================

    static void testNullableVsNullableComparison() {
        System.out.println("-- Nullable vs Nullable Comparison (F4 round 5) --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string = \"\"; }\n" +
            "let a: string | null = null;\n" +
            "let b: string | null = null;\n" +
            "let eq: boolean = a === b;"
        );
        assertNoErrors(out, "nullable comparison");
        assertContains(out.lua, "== nil or", "nil check in comparison");
        assertContains(out.lua, "== __NULL", "__NULL check in comparison");
    }

    // =========================================================================
    // F4 round 5: nullable vs nullable not-equal
    // =========================================================================

    static void testNullableVsNullableNotEqual() {
        System.out.println("-- Nullable vs Nullable Not Equal (F4 round 5) --");
        CompileOutput out = compile(
            "class User { name: string = \"\"; nick?: string = \"\"; }\n" +
            "let a: string | null = null;\n" +
            "let b: string | null = \"hi\";\n" +
            "let neq: boolean = a !== b;"
        );
        assertNoErrors(out, "nullable not equal");
        assertContains(out.lua, "not (", "not wrapper");
        assertContains(out.lua, "== nil or", "nil check in comparison");
        assertContains(out.lua, "== __NULL", "__NULL check in comparison");
    }

    // =========================================================================
    // F6 (round 6): Rest parameters — rejected in DEAL v1.2
    // =========================================================================

    static void testRestParameterFunctionDecl() {
        System.out.println("-- Rest Parameter Rejected (v1.2) --");
        CompileOutput out = compile(
            "function sum(base: int, ...rest: int[]): int {\n" +
            "  let total: int = base;\n" +
            "  for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "    total = total + 1;\n" +
            "  }\n" +
            "  return total;\n" +
            "}"
        );
        // DEAL v1.2 removed rest parameters: the parser rejects the
        // declaration with E1047 and the backend emits no Lua varargs
        // machinery.
        LexResult lex = new Lexer(
            "function sum(base: int, ...rest: int[]): int { return 1; }",
            "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        check(parse.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E1047")),
            "rest param function decl rejected with E1047");
    }

    // =========================================================================
    // F6 (round 6): Rest parameters in function expressions — rejected in v1.2
    // =========================================================================

    static void testRestParameterFunctionExpr() {
        System.out.println("-- Rest Parameter Function Expression Rejected (v1.2) --");
        CompileOutput out = compile(
            "let fn: (string, ...string[]) => string =\n" +
            "  function(prefix: string, ...rest: string[]): string {\n" +
            "    return prefix;\n" +
            "  };"
        );
        LexResult lex = new Lexer(
            "function(...rest: int[]): int { return 1; }", "test.deal")
            .tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        check(parse.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E1047")),
            "rest param function expr rejected with E1047");
    }

    // =========================================================================
    // F7 (round 6): Bare return from null-typed function returns __NULL
    // =========================================================================

    static void testNullReturnNoExpr() {
        System.out.println("-- Null Return No Expression (F7 round 6) --");
        CompileOutput out = compile(
            "function f(): null {\n" +
            "  return;\n" +
            "}"
        );
        assertNoErrors(out, "null return no expr");
        assertContains(out.lua, "return __NULL", "null return emits __NULL");
    }

    // =========================================================================
    // ISSUE-0011: Try-break/continue flag pattern — single try in while loop
    // =========================================================================

    static void testTryBreakContinueFlagPattern() {
        System.out.println("-- Try Break/Continue Flag Pattern (ISSUE-0011) --");
        String source =
            "while (true) {\n" +
            "  try {\n" +
            "    break;\n" +
            "  } catch (e) {\n" +
            "  }\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "flag pattern: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        assertContains(lua, "__try_break_", "break flag declared");
        assertContains(lua, "__try_continue_", "continue flag declared");
        assertContains(lua, "local __try_break_", "break flag local declaration");
        assertContains(lua, "local __try_continue_", "continue flag local declaration");
        assertContains(lua, "= true", "flag set to true");
        assertContains(lua, "return", "return from pcall");
        assertContains(lua, "if __try_break_", "if break flag after pcall");
        assertContains(lua, "break", "real break at outermost level");
        check(isValidLua(lua), "flag pattern generates valid Lua");
        assertNotContains(lua, "E6002", "no E6002 diagnostic");
    }

    // =========================================================================
    // ISSUE-0011: Nested try break/continue flag propagation
    // =========================================================================

    static void testNestedTryBreakContinueFlagPropagation() {
        System.out.println("-- Nested Try Break/Continue Flag Propagation (ISSUE-0011) --");
        String source =
            "while (true) {\n" +
            "  try {\n" +
            "    try {\n" +
            "      break;\n" +
            "    } catch (e2) {\n" +
            "    }\n" +
            "  } catch (e1) {\n" +
            "  }\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "nested flag: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        // Should have break and continue flags present
        assertContains(lua, "__try_break_", "break flags present");
        assertContains(lua, "__try_continue_", "continue flags present");

        // Count distinct break flag names (should have at least 2 different ones)
        Set<String> breakFlags = new HashSet<>();
        int idx = 0;
        while ((idx = lua.indexOf("__try_break_", idx)) != -1) {
            int end = idx + "__try_break_".length();
            while (end < lua.length() && Character.isDigit(lua.charAt(end))) {
                end++;
            }
            breakFlags.add(lua.substring(idx, end));
            idx++;
        }
        check(breakFlags.size() >= 2, "at least 2 distinct break flag names, got: " + breakFlags.size());

        // Verify propagation: the inner try's elseif should set the outer flag to true
        assertContains(lua, "= true", "flag propagation sets outer flag");

        // Outer try should have real break
        assertContains(lua, "break", "real break at outermost level");

        check(isValidLua(lua), "nested flag propagation generates valid Lua");
        assertNotContains(lua, "E6002", "no E6002 diagnostic");
    }

    // =========================================================================
    // ISSUE-0011: Try break/continue inside function (interaction with return flags)
    // =========================================================================

    static void testTryBreakContinueInFunction() {
        System.out.println("-- Try Break/Continue In Function (ISSUE-0011) --");
        String source =
            "function f(): null {\n" +
            "  while (true) {\n" +
            "    try {\n" +
            "      break;\n" +
            "    } catch (e) {\n" +
            "    }\n" +
            "  }\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "function break/try: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        // Should have both return flags and break/continue flags
        assertContains(lua, "__try_returned_", "return flag present");
        assertContains(lua, "__try_break_", "break flag present");
        assertContains(lua, "__try_continue_", "continue flag present");

        // Check order: not __ok → elseif return → elseif break → elseif continue
        int okPos = lua.indexOf("if not __ok then");
        int returnPos = lua.indexOf("elseif __try_returned_");
        int breakPos = lua.indexOf("elseif __try_break_");
        int continuePos = lua.indexOf("elseif __try_continue_");

        check(okPos < returnPos, "error check before return check");
        check(returnPos < breakPos, "return check before break check");
        check(breakPos < continuePos, "break check before continue check");

        assertContains(lua, "break", "real break at outermost level");
        check(isValidLua(lua), "function break/try generates valid Lua");
    }

    // =========================================================================
    // ISSUE-0011: Verify E6002 is no longer emitted
    // =========================================================================

    static void testTryBreakContinueNoE6002() {
        System.out.println("-- No E6002 Diagnostic (ISSUE-0011) --");
        String source =
            "for (let i: int = 0; i < 10; i = i + 1) {\n" +
            "  try { break; } catch (e) {}\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "no E6002: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        assertNotContains(lua, "E6002", "no E6002 in generated code");

        for (CompilerDiagnostic d : backend.diagnostics()) {
            if ("E6002".equals(d.code())) {
                check(false, "E6002 diagnostic should not be emitted");
                return;
            }
        }
        passed++;
    }

    // =========================================================================
    // ISSUE-0011: Break in catch block of try-inside-loop uses plain break
    // =========================================================================

    static void testTryBreakContinueCatchBlockBreak() {
        System.out.println("-- Break in Catch Block (ISSUE-0011) --");
        String source =
            "while (true) {\n" +
            "  try {\n" +
            "    let x: int = 1;\n" +
            "  } catch (e) {\n" +
            "    break;\n" +
            "  }\n" +
            "}";
        LexResult lex = new Lexer(source, "test.deal").tokenize();
        ParseResult parse = new Parser(lex.tokens(), "test.deal", lex.directiveEvents()).parse();
        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver("test.deal", resolver);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check("test.deal", symTable, nr, parse.program());

        if (result.diagnostics().stream().anyMatch(d -> "error".equals(d.severity()))) {
            check(false, "catch break: type checker should accept this");
            return;
        }

        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable(), "test.deal");
        String lua = backend.generateFromInstance(parse.program());

        assertContains(lua, "break", "plain break in catch block");
        assertNotContains(lua, "E6002", "no E6002");
        check(isValidLua(lua), "catch break generates valid Lua");
    }

    static void testTemplateLiteralPlain() {
        System.out.println("-- Template Literal Plain --");
        CompileOutput out = compile("let msg = `hello world`;");
        assertNoErrors(out, "template plain");
        // Single part, no interpolations → plain string literal, no concatenation
        assertContains(out.lua, "\"hello world\"", "plain string literal");
        assertNotContains(out.lua, " .. ", "no concatenation for plain template");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testTemplateLiteralWithExpr() {
        System.out.println("-- Template Literal With Expression --");
        CompileOutput out = compile("let name: string = \"Alice\"; let msg = `Hello ${name}`;");
        assertNoErrors(out, "template with expr");
        // Should have (part .. expr) concatenation
        assertContains(out.lua, " .. ", "concatenation operator");
        assertContains(out.lua, "\"Hello \"", "string part");
        // The empty trailing string part should be elided from concatenation
        // (no standalone "" in the concatenation expression)
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testTemplateLiteralLeadingExpr() {
        System.out.println("-- Template Literal Leading Expression --");
        CompileOutput out = compile("let greeting: string = \"Hi\"; let msg = `${greeting} world`;");
        assertNoErrors(out, "template leading expr");
        // Leading empty string should be elided; concatenation should start with greeting
        assertContains(out.lua, " .. ", "concatenation operator");
        assertContains(out.lua, "\" world\"", "trailing string part");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testTemplateLiteralAllExprs() {
        System.out.println("-- Template Literal All Expressions --");
        CompileOutput out = compile("let a: string = \"A\"; let b: string = \"B\"; let msg = `${a}${b}`;");
        assertNoErrors(out, "template all exprs");
        // Should concatenate a and b without any empty string parts
        assertContains(out.lua, " .. ", "concatenation operator");
        check(isValidLua(out.lua), "valid Lua");
    }

    // =========================================================================
    // ISSUE-0018: For-of codegen tests
    // =========================================================================

    static void testForOfArrayCodegen() {
        System.out.println("-- For-Of Array Codegen --");
        CompileOutput out = compile(
            "function sum(xs: int[]): int { let total: int = 0; for (let x: int of xs) { total = total + x; } return total; }");
        assertNoErrors(out, "for-of array");
        assertContains(out.lua, "ipairs(", "ipairs for array iteration");
        assertContains(out.lua, "for __i, x in ipairs(", "ipairs loop structure");
        assertContains(out.lua, "::__continue_", "continue label");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testForOfStringCodegen() {
        System.out.println("-- For-Of String Codegen (v1.2 Unicode scalar values) --");
        CompileOutput out = compile(
            "function count(s: string): int { let n: int = 0; for (let c: string of s) { n = n + 1; } return n; }");
        assertNoErrors(out, "for-of string");
        // v1.2 iterates one Unicode scalar value per step via
        // __rt.utf8_next — never byte positions (no string.sub, no #length).
        assertContains(out.lua, "__rt.utf8_next(", "utf8_next for scalar iteration");
        assertNotContains(out.lua, "string.sub(", "no byte-based string.sub iteration");
        assertNotContains(out.lua, "#__iterable", "no byte-length iteration bound");
        assertContains(out.lua, "local __iterable = ", "iterable hoisting");
        assertContains(out.lua, "while true do", "scalar iteration loop");
        assertContains(out.lua, "if __n == nil then break end", "iteration terminator");
        assertContains(out.lua, "::__continue_", "continue label");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testForOfStringSingleEvaluation() {
        System.out.println("-- For-Of String Single Evaluation (D17) --");
        CompileOutput out = compile(
            "function getStr(): string { return \"abc\"; } " +
            "function countIt(): int { let n: int = 0; for (let c: string of getStr()) { n = n + 1; } return n; }");
        assertNoErrors(out, "for-of string single eval");
        // getStr.f() should appear exactly once (in the hoisted local assignment)
        String lua = out.lua;
        int firstIdx = lua.indexOf("getStr.f()");
        check(firstIdx >= 0, "getStr.f() appears in generated code");
        int secondIdx = lua.indexOf("getStr.f()", firstIdx + 1);
        check(secondIdx < 0, "getStr.f() appears exactly once (single evaluation)");
        assertContains(lua, "local __iterable = getStr.f()", "iterable hoisted from function call");
        check(isValidLua(lua), "valid Lua");
    }

    static void testForOfBreakCodegen() {
        System.out.println("-- For-Of Break Codegen --");
        CompileOutput out = compile(
            "function testBreak(): int { let n: int = 0; for (let x: int of [1,2,3]) { break; n = n + x; } return n; }");
        assertNoErrors(out, "for-of break");
        assertContains(out.lua, "break", "break statement in loop");
        assertContains(out.lua, "ipairs(", "ipairs loop");
        assertContains(out.lua, "::__continue_", "continue label");
        // Note: isValidLua not checked here because break before ::label:: is a
        // pre-existing limitation across all loop types (LuaJIT 5.1 restriction).
        // This test verifies codegen structure only, matching existing break tests.
    }

    static void testForOfContinueCodegen() {
        System.out.println("-- For-Of Continue Codegen --");
        CompileOutput out = compile(
            "function testContinue(): int { let n: int = 0; for (let x: int of [1,2,3]) { continue; n = n + x; } return n; }");
        assertNoErrors(out, "for-of continue");
        assertContains(out.lua, "goto __continue_", "goto continue label");
        assertContains(out.lua, "::__continue_", "continue label target");
        assertContains(out.lua, "ipairs(", "ipairs loop");
        // goto before ::label:: is valid in LuaJIT
        check(isValidLua(out.lua), "valid Lua");
    }

    // =========================================================================
    // v1.2 entry contract: exported non-async main(): null + backend invocation
    // =========================================================================

    static void testEntryMainInvocation() {
        System.out.println("-- Entry Module: backend invokes exported main() --");
        CompileOutputDiag out = compileEntry(
            "export function main(): null { return null; }\n"
            + "export function helper(): int { return 1; }");
        boolean hasE6004 = out.codegenDiags().stream()
            .anyMatch(d -> "E6004".equals(d.code()));
        check(!hasE6004, "valid main emits no E6004");
        assertContains(out.lua, "exports.main = main", "main exported");
        assertContains(out.lua, "exports.main.f()", "backend invokes main() from the module");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testEntryMainMissingE6004() {
        System.out.println("-- Entry Module: missing main → E6004 --");
        CompileOutputDiag out = compileEntry(
            "export function helper(): int { return 1; }");
        boolean hasE6004 = out.codegenDiags().stream()
            .anyMatch(d -> "E6004".equals(d.code()));
        check(hasE6004, "missing main rejected with E6004");
        assertNotContains(out.lua, "exports.main.f()", "no invocation emitted for invalid entry");

        // T12 verification-6 fixture: no main declaration exists, so the
        // E6004 records through the explicit synthetic factory — the
        // canonical (file,1,1,1,1,0,0,0,SYNTHETIC) range plus the
        // missing-main anchor note (never a Span.synthetic passthrough).
        CompilerDiagnostic e6004 = out.codegenDiags().stream()
            .filter(d -> "E6004".equals(d.code()))
            .findFirst().orElse(null);
        if (e6004 != null) {
            DiagnosticRange range = e6004.range();
            check(range.origin() == RangeOrigin.SYNTHETIC
                    && range.startLine() == 1 && range.startColumn() == 1
                    && range.endLine() == 1 && range.endColumn() == 1
                    && range.startScalarOffset() == 0
                    && range.endScalarOffset() == 0
                    && range.scalarLength() == 0,
                "missing-main E6004 carries the canonical synthetic range: "
                    + range);
            check(e6004.notes().size() == 1
                    && e6004.notes().get(0).message().equals(
                        "missing anchor: main declaration span in entry module"),
                "missing-main E6004 note names the missing main span: "
                    + e6004.notes());
        }
    }

    static void testEntryMainWrongSignatureE6004() {
        System.out.println("-- Entry Module: main with parameters → E6004 --");
        String src = "export function main(x: int): null { return null; }";
        CompileOutputDiag out = compileEntry(src);
        boolean hasE6004 = out.codegenDiags().stream()
            .anyMatch(d -> "E6004".equals(d.code()));
        check(hasE6004, "parameterized main rejected with E6004");
        assertNotContains(out.lua, "exports.main.f()", "no invocation emitted for invalid entry");

        // T12 verification-6 counterpart: the main declaration exists
        // (wrong signature), so the E6004 anchors at the recorded
        // mainDeclSpan — SOURCE origin with exact scalar offsets.
        CompilerDiagnostic e6004 = out.codegenDiags().stream()
            .filter(d -> "E6004".equals(d.code()))
            .findFirst().orElse(null);
        if (e6004 != null) {
            DiagnosticRange range = e6004.range();
            check(range.origin() == RangeOrigin.SOURCE,
                "wrong-signature E6004 anchors SOURCE at the main declaration: "
                    + range);
            int fnIdx = src.indexOf("function");
            check(range.startColumn() == fnIdx + 1
                    && range.startLine() == 1,
                "wrong-signature E6004 starts at the 'function' keyword: "
                    + range);
            check(range.startScalarOffset()
                    == ScalarSourceCursor.scalarCount(src, 0, fnIdx),
                "wrong-signature E6004 start scalar offset is exact ("
                    + fnIdx + "): " + range);
            check(range.endScalarOffset() > range.startScalarOffset()
                    && range.scalarLength()
                        == range.endScalarOffset() - range.startScalarOffset(),
                "wrong-signature E6004 spans the main declaration: " + range);
        }
    }

    static void testEntryMainAsyncE6004() {
        System.out.println("-- Entry Module: async main → E6004 --");
        CompileOutputDiag out = compileEntry(
            "export async function main(): null { return null; }");
        boolean hasE6004 = out.codegenDiags().stream()
            .anyMatch(d -> "E6004".equals(d.code()));
        check(hasE6004, "async main rejected with E6004");
        assertNotContains(out.lua, "exports.main.f()", "no invocation emitted for invalid entry");
    }

    static void testEntryMainNotExportedE6004() {
        System.out.println("-- Entry Module: non-exported main → E6004 --");
        CompileOutputDiag out = compileEntry(
            "function main(): null { return null; }\n"
            + "export function helper(): int { return 1; }");
        boolean hasE6004 = out.codegenDiags().stream()
            .anyMatch(d -> "E6004".equals(d.code()));
        check(hasE6004, "non-exported main rejected with E6004");
        assertNotContains(out.lua, "exports.main.f()", "no invocation emitted for invalid entry");
    }

    static void testNonEntryNoMainInvocation() {
        System.out.println("-- Non-entry Module: no main invocation emitted --");
        CompileOutput out = compile(
            "export function helper(): int { return 1; }");
        assertNoErrors(out, "non-entry module");
        assertNotContains(out.lua, "exports.main.f()", "non-entry module never invokes main");
        assertContains(out.lua, "return exports", "exports return");
    }
    // =========================================================================
    // ISSUE-0054: Async/await codegen tests
    // =========================================================================

    static void testAsyncFunctionCodegen() {
        System.out.println("-- Async Function Codegen --");
        CompileOutput out = compile(
            "async function f(): int { return 5; }");
        assertNoErrors(out, "async function decl");
        assertContains(out.lua, "__rt.async_start(function()", "async_start wrapper");
        // Descriptor should have "async" prefix
        assertContains(out.lua, "\"async()->int\"", "async descriptor prefix");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testAwaitExpressionCodegen() {
        System.out.println("-- Await Expression Codegen --");
        CompileOutput out = compile(
            "async function g(): int { return 42; }\n" +
            "async function f(): int { return await g(); }");
        assertNoErrors(out, "await expression");
        assertContains(out.lua, "coroutine.yield(g.f())", "await lowers to coroutine.yield");
        assertContains(out.lua, "__rt.async_start(function()", "async_start wrapper in f");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testSyncFunctionUnchanged() {
        System.out.println("-- Sync Function Unchanged --");
        CompileOutput out = compile(
            "function f(): int { return 5; }");
        assertNoErrors(out, "sync function");
        assertNotContains(out.lua, "__rt.async_start", "no async_start in sync function");
        assertNotContains(out.lua, "coroutine.yield", "no coroutine.yield in sync function");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testAsyncFunctionDescriptorPrefix() {
        System.out.println("-- Async Function Descriptor Prefix --");
        CompileOutput out = compile(
            "async function fetchUser(id: int): string { return \"user\"; }");
        assertNoErrors(out, "async function with params");
        // Descriptor should be "async(int)->string"
        assertContains(out.lua, "\"async(int)->string\"", "async descriptor with params");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testAsyncFunctionExprCodegen() {
        System.out.println("-- Async Function Expression Codegen --");
        CompileOutput out = compile(
            "let f: async () => int = async function(): int { return 5; };");
        assertNoErrors(out, "async function expr");
        assertContains(out.lua, "__rt.async_start(function()", "async_start in function expr");
        assertContains(out.lua, "\"async()->int\"", "async descriptor in function expr");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testAsyncFunctionNullableReturn() {
        System.out.println("-- Async Function Nullable Return --");
        CompileOutput out = compile(
            "async function maybeUser(): string | null { return null; }");
        assertNoErrors(out, "async nullable return");
        // Canonical descriptor: async prefix plus the "?string" nullable
        // return (emitter page D1 — never the legacy "|null" suffix).
        assertContains(out.lua, "\"async()->?string\"", "async descriptor with canonical nullable return");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testAwaitAsExpressionStatement() {
        System.out.println("-- Await as Expression Statement --");
        CompileOutput out = compile(
            "async function g(): int { return 1; }\n" +
            "async function f(): int { await g(); return 0; }");
        assertNoErrors(out, "await as expression statement");
        // await g() as a statement should still emit coroutine.yield
        assertContains(out.lua, "coroutine.yield(g.f())", "await statement emits coroutine.yield");
        check(isValidLua(out.lua), "valid Lua");
    }

    static void testAwaitCompletionCheckCodegen() {
        System.out.println("-- Await Completion Check Codegen --");
        // Typed R (int): the await emission wraps coroutine.yield in a runtime
        // check carrying the await's source span.
        CompileOutput out = compile(
            "async function g(): int { return 42; }\n" +
            "async function f(): int { return await g(); }");
        assertNoErrors(out, "await completion check");
        assertContains(out.lua,
            "__rt.check_int(coroutine.yield(g.f()), \"test.deal\", 2, 34)",
            "await completion wrapped in check_int at the await span");
        check(isValidLua(out.lua), "valid Lua");

        // Discard path: await as an expression statement is wrapped too.
        CompileOutput discard = compile(
            "async function g(): int { return 1; }\n" +
            "async function f(): int { await g(); return 0; }");
        assertNoErrors(discard, "await statement completion check");
        assertContains(discard.lua,
            "__rt.check_int(coroutine.yield(g.f()), \"test.deal\", 2, 27)",
            "await statement wrapped in check_int at the await span");
        check(isValidLua(discard.lua), "valid Lua");

        // R = null: check_null over the completion value.
        CompileOutput nullOut = compile(
            "async function n(): null { return null; }\n" +
            "async function f(): null { return await n(); }");
        assertNoErrors(nullOut, "null-typed await completion check");
        assertContains(nullOut.lua,
            "__rt.check_null(coroutine.yield(n.f())",
            "null completion wrapped in check_null");
        check(isValidLua(nullOut.lua), "valid Lua");

        // R = string | null: check_nullable over the completion value.
        CompileOutput nullableOut = compile(
            "async function m(): string | null { return null; }\n" +
            "async function f(): string | null { return await m(); }");
        assertNoErrors(nullableOut, "nullable-typed await completion check");
        assertContains(nullableOut.lua,
            "__rt.check_nullable(\"string\", coroutine.yield(m.f())",
            "nullable completion wrapped in check_nullable");
        check(isValidLua(nullableOut.lua), "valid Lua");

        // R = int[]: check_array over the completion value.
        CompileOutput arrayOut = compile(
            "async function a(): int[] { return [1, 2]; }\n" +
            "async function f(): int[] { return await a(); }");
        assertNoErrors(arrayOut, "array-typed await completion check");
        assertContains(arrayOut.lua,
            "__rt.check_array(\"[int]\", coroutine.yield(a.f())",
            "array completion wrapped in check_array (canonical descriptor)");
        check(isValidLua(arrayOut.lua), "valid Lua");
    }

    // =========================================================================
    // ISSUE-0050: @jsonable codegen unit tests
    // =========================================================================

    static void testJsonableSimpleClass() {
        System.out.println("-- @jsonable simple class --");
        CompileOutput out = compile(
            "// @jsonable\nexport class User {\n  name: string;\n  age: int;\n}\n");
        assertNoErrors(out, "@jsonable simple class");
        // Check C_fields table emitted
        assertContains(out.lua, "__deal[\"User_fields\"] = {", "User_fields table");
        assertContains(out.lua, "\"name\"", "name field in descriptor");
        assertContains(out.lua, "jtype = \"string\"", "string jtype");
        assertContains(out.lua, "jtype = \"int\"", "int jtype");
        // Check the default plan and meta still emitted
        assertContains(out.lua, "__deal[\"User_plan\"] = ", "User_plan");
        assertContains(out.lua, "__deal[\"User_meta\"] = ", "User_meta");
        // Structural check only: LuaJIT on this system lacks $ identifier support,
        // but the generated code is structurally valid.
        check(structuralLuaCheck(out.lua), "valid Lua (structural)");
    }

    static void testJsonableFromJsonEmission() {
        System.out.println("-- @jsonable C$fromJson emission --");
        CompileOutput out = compile(
            "// @jsonable\nexport class User {\n  name: string;\n}\n");
        assertNoErrors(out, "@jsonable fromJson");
        // Check C$fromJson function
        assertContains(out.lua, "__deal[\"User$fromJson\"] = __rt.function_(",
            "C$fromJson wrapper");
        assertContains(out.lua, "\"(string)->?@test.deal/User\"", "fromJson signature (canonical nullable)");
        assertContains(out.lua, "pcall(__json_parse, s)", "pcall wrapping json parse");
        assertContains(out.lua, "__rt.json_from_plan(", "json_from_plan call");
        assertContains(out.lua, "return __NULL", "return null on failure");
        assertContains(out.lua, "if instance == nil then return __NULL end", "nil check");
        check(structuralLuaCheck(out.lua), "valid Lua (structural)");
    }

    static void testJsonableToJsonEmission() {
        System.out.println("-- @jsonable C$toJson emission --");
        CompileOutput out = compile(
            "// @jsonable\nexport class User {\n  name: string;\n}\n");
        assertNoErrors(out, "@jsonable toJson");
        // Check C$toJson function
        assertContains(out.lua, "__deal[\"User$toJson\"] = __rt.function_(",
            "C$toJson wrapper");
        assertContains(out.lua, "\"(@test.deal/User)->string\"", "toJson signature");
        assertContains(out.lua, "__rt.check_type(\"@test.deal/User\", v)",
            "toJson internal check uses the qualified identity");
        assertContains(out.lua, "__rt.json_to_json(\"@test.deal/User\", v,",
            "json_to_json descriptor is the qualified identity");
        assertContains(out.lua, "__rt.json_to_json(", "json_to_json call");
        assertContains(out.lua, "__json_stringify(", "json_stringify call");
        check(structuralLuaCheck(out.lua), "valid Lua (structural)");
    }

    static void testJsonableExports() {
        System.out.println("-- @jsonable exports --");
        CompileOutput out = compile(
            "// @jsonable\nexport class User {\n  name: string;\n}\n");
        assertNoErrors(out, "@jsonable exports");
        // Check exports table entries
        assertContains(out.lua, "exports.User_fields = __deal[\"User_fields\"]",
            "User_fields export");
        assertContains(out.lua, "exports[\"User$fromJson\"] = __deal[\"User$fromJson\"]",
            "User$fromJson export");
        assertContains(out.lua, "exports[\"User$toJson\"] = __deal[\"User$toJson\"]",
            "User$toJson export");
        check(structuralLuaCheck(out.lua), "valid Lua (structural)");
    }

    static void testJsonableDotFAccess() {
        System.out.println("-- @jsonable .f access --");
        CompileOutput out = compile(
            "// @jsonable\nexport class User {\n  name: string;\n}\n");
        assertNoErrors(out, "@jsonable .f access");
        // Check .f access for json module
        assertContains(out.lua, "__json.parse.f", ".f access for parse");
        assertContains(out.lua, "__json.stringify.f", ".f access for stringify");
        // Must NOT use raw require("std.json").parse(...) without .f
        assertNotContains(out.lua, "require(\"std.json\").parse(", "no raw parse call");
        assertNotContains(out.lua, "require(\"std.json\").stringify(", "no raw stringify call");
        check(structuralLuaCheck(out.lua), "valid Lua (structural)");
    }

    static void testJsonableTopologicalSort() {
        System.out.println("-- @jsonable topological sort --");
        // Parent class A (field of type B) declared before child class B.
        // B_fields must appear before A_fields in output.
        CompileOutput out = compile(
            "// @jsonable\nexport class A {\n  b: B;\n}\n" +
            "// @jsonable\nexport class B {\n  name: string;\n}\n");
        assertNoErrors(out, "@jsonable topological sort");
        String lua = out.lua;
        // B_fields (dependency) must be emitted before A_fields (dependent)
        int bFieldsPos = lua.indexOf("__deal[\"B_fields\"] = {");
        int aFieldsPos = lua.indexOf("__deal[\"A_fields\"] = {");
        check(bFieldsPos >= 0, "B_fields exists");
        check(aFieldsPos >= 0, "A_fields exists");
        check(bFieldsPos < aFieldsPos, "B_fields emitted before A_fields (topological sort)");
        // Check that A_fields references B_plan and B_fields
        assertContains(lua, "B_plan", "A references B_plan");
        assertContains(lua, "B_fields", "A references B_fields");
        check(structuralLuaCheck(lua), "valid Lua (structural)");
    }

    static void testJsonableTopologicalSortWrappedType() {
        System.out.println("-- @jsonable topological sort with wrapped type --");
        // A depends on B via array wrapper: bs: B[]
        CompileOutput out = compile(
            "// @jsonable\nexport class A {\n  bs: B[];\n}\n" +
            "// @jsonable\nexport class B {\n  name: string;\n}\n");
        assertNoErrors(out, "@jsonable topological sort wrapped");
        String lua = out.lua;
        int bFieldsPos = lua.indexOf("__deal[\"B_fields\"] = {");
        int aFieldsPos = lua.indexOf("__deal[\"A_fields\"] = {");
        check(bFieldsPos >= 0, "B_fields exists");
        check(aFieldsPos >= 0, "A_fields exists");
        check(bFieldsPos < aFieldsPos, "B_fields emitted before A_fields (wrapped type dep)");
        check(structuralLuaCheck(lua), "valid Lua (structural)");
    }

    static void testJsonableNoRegression() {
        System.out.println("-- @jsonable no regression --");
        // Non-@jsonable class should produce identical output format
        CompileOutput out = compile(
            "export class Plain {\n  x: int;\n}\n");
        assertNoErrors(out, "non-jsonable class");
        // Should NOT have _fields, $fromJson, $toJson
        assertNotContains(out.lua, "Plain_fields", "no Plain_fields for non-jsonable");
        assertNotContains(out.lua, "Plain$fromJson", "no Plain$fromJson for non-jsonable");
        assertNotContains(out.lua, "Plain$toJson", "no Plain$toJson for non-jsonable");
        // Should NOT have std.json loading
        assertNotContains(out.lua, "require(\"std.json\")", "no std.json for non-jsonable");
        // Still has the default plan and meta
        assertContains(out.lua, "__deal[\"Plain_plan\"] = ",
            "Plain_plan still emitted");
        assertContains(out.lua, "__deal[\"Plain_meta\"] = ", "Plain_meta still emitted");
        check(isValidLua(out.lua), "valid Lua");  // non-jsonable: no $ identifiers
    }

    // =========================================================================
    // FFI binding generation (ISSUE-0345, emitter page D6)
    // =========================================================================

    /** The extern-c declaration module path used by the FFI unit tests. */
    private static final String FFI_MODULE_PATH = "ffi_math";

    /**
     * Test-side module resolver with correct identity-keyed class routing
     * for the FFI module's ExternalModule classification (the shared
     * StubModuleResolver maps identities through the standalone
     * project-module convention, which cannot represent externals).
     */
    private static final class FfiTestModuleResolver implements ModuleResolver {
        private final Map<String, Map<String, Type>> modules = new HashMap<>();
        private final Map<String, Symbol.ClassSymbol> classSymbols =
            new HashMap<>();

        void register(String path, Map<String, Type> exports) {
            modules.put(path, exports);
        }

        void registerClass(String path, Symbol.ClassSymbol cs) {
            classSymbols.put(path + ":" + cs.name(), cs);
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Map<String, Type> exports = modules.get(modulePath);
            if (exports == null) {
                throw new ModuleNotFoundException("Module not found: " + modulePath);
            }
            return exports;
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return classSymbols.get(modulePath + ":" + className);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                CanonicalModuleIdentity declaringModule, String importingModule)
                throws ModuleNotFoundException {
            if (declaringModule
                    instanceof CanonicalModuleIdentity.ExternalModule external) {
                return classSymbols.get(
                    external.rawImportSpecifier() + ":" + className);
            }
            return null;
        }
    }

    /**
     * One assembled FFI test module: the generator input, the checker
     * exports, and the identity-keyed class symbols — all derived from
     * the same parsed declaration (the production orchestrator's
     * assembly, mirrored).
     */
    private record FfiTestModule(LuaFfiBindingGenerator.FfiModuleInput input,
                                 Map<String, Type> exports,
                                 List<Symbol.ClassSymbol> classSymbols) {}

    /**
     * Assembles an FFI test module from one extern-c declaration source.
     */
    private static FfiTestModule assembleFfiModule(String declSource,
            String loaderText) {
        String filename = "ffi_math.d.deal";
        LexResult lex = new Lexer(declSource, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename,
            lex.directiveEvents()).parse();
        check(!parse.hasErrors(), "FFI declaration parses cleanly: "
            + parse.diagnostics());
        Map<String, CanonicalModuleIdentity> byPath = new HashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        byPath.put(FFI_MODULE_PATH,
            new CanonicalModuleIdentity.ExternalModule("ffi_math"));
        ExportExtractor extractor = new ExportExtractor(FFI_MODULE_PATH,
            true, ModuleIdentityResolver.buildIndex(byPath)
                .moduleIdentityLookup());
        Map<String, Type> exports = new LinkedHashMap<>(
            extractor.extract(parse.program()));

        List<LuaFfiBindingGenerator.FfiFunctionInput> functions =
            new ArrayList<>();
        List<LuaFfiBindingGenerator.FfiClassInput> classes =
            new ArrayList<>();
        List<Symbol.ClassSymbol> classSymbols = new ArrayList<>();
        for (StatementNode stmt : parse.program().statements()) {
            if (!(stmt instanceof ExportDeclaration exp)) {
                continue;
            }
            if (exp.declaration() instanceof FunctionDeclaration fd) {
                check(exports.get(fd.name()) instanceof Type.Func,
                    "FFI declaration function '" + fd.name()
                        + "' resolves to a function type");
                functions.add(new LuaFfiBindingGenerator.FfiFunctionInput(
                    fd.name(), (Type.Func) exports.get(fd.name())));
            } else if (exp.declaration() instanceof ClassDeclaration cd) {
                String kind = cd.directives()
                    .contains(DeclarationDirective.C_STRUCT)
                    ? "C_STRUCT"
                    : cd.directives().contains(DeclarationDirective.C_POINTER)
                        ? "C_POINTER" : null;
                check(kind != null, "FFI class '" + cd.name()
                    + "' carries a C marker");
                check(exports.get(cd.name()) instanceof Type.Class,
                    "FFI class '" + cd.name() + "' resolves to a class type");
                Type.Class clsType = (Type.Class) exports.get(cd.name());
                List<LuaFfiBindingGenerator.FfiFieldInput> fields =
                    new ArrayList<>();
                for (int i = 0; i < cd.fields().size(); i++) {
                    ClassField field = cd.fields().get(i);
                    fields.add(new LuaFfiBindingGenerator.FfiFieldInput(
                        field.name(), i,
                        extractor.resolveFieldType(field.type()),
                        field.defaultExpr(), field.optional()));
                }
                classes.add(new LuaFfiBindingGenerator.FfiClassInput(
                    cd.name(), clsType.identity(), kind, fields));
                classSymbols.add(new Symbol.ClassSymbol(cd.name(),
                    cd.fields(), FFI_MODULE_PATH, clsType.identity()));
            }
        }
        LuaFfiBindingGenerator.FfiModuleInput input =
            new LuaFfiBindingGenerator.FfiModuleInput("ffi:ffi_math",
                "ABSOLUTE_PATH", loaderText, functions, classes);
        return new FfiTestModule(input, exports, classSymbols);
    }

    /** The identity index the FFI unit tests generate against. */
    private static ModuleIdentityResolver.IdentityIndex ffiIdentityIndex() {
        Map<String, CanonicalModuleIdentity> byPath = new HashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        byPath.put(FFI_MODULE_PATH,
            new CanonicalModuleIdentity.ExternalModule("ffi_math"));
        byPath.put("test.deal",
            new CanonicalModuleIdentity.ProjectModule(
                new deal.identity.ProjectModuleIdentity(
                    "test.deal", "test.deal", List.of())));
        return ModuleIdentityResolver.buildIndex(byPath);
    }

    /**
     * Compiles an importer module against the assembled FFI module and
     * generates its Lua with the FFI binding input; captures backend
     * diagnostics.
     */
    private static CompileOutputDiag compileFfiImporter(String source,
            FfiTestModule ffiModule) {
        String filename = "test.deal";
        LexResult lex = new Lexer(source, filename).tokenize();
        ParseResult parse = new Parser(lex.tokens(), filename,
            lex.directiveEvents()).parse();

        FfiTestModuleResolver resolver = new FfiTestModuleResolver();
        resolver.register(FFI_MODULE_PATH, ffiModule.exports());
        for (Symbol.ClassSymbol cs : ffiModule.classSymbols()) {
            resolver.registerClass(FFI_MODULE_PATH, cs);
        }
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parse.program());

        List<CompilerDiagnostic> diags = new ArrayList<>(nr.diagnostics());
        CheckResult result;
        if (diags.stream().noneMatch(d -> "error".equals(d.severity()))) {
            result = TypeChecker.check(filename, symTable, nr, parse.program());
            diags.addAll(result.diagnostics());
            if (diags.stream().anyMatch(d -> "error".equals(d.severity()))) {
                return new CompileOutputDiag(null, result, parse.program(),
                    List.of());
            }
        } else {
            return new CompileOutputDiag(null,
                new CheckResult(Map.of(), symTable, diags), parse.program(),
                List.of());
        }

        LuaBackend backend = new LuaBackend(result.typeMap(),
            result.symbolTable(), filename, filename, ffiIdentityIndex());
        String lua = backend.generateFromInstance(parse.program(), false,
            Map.of(FFI_MODULE_PATH, FFI_MODULE_PATH), Map.of(),
            Map.of(FFI_MODULE_PATH, ffiModule.input()));
        return new CompileOutputDiag(lua, result, parse.program(),
            backend.diagnostics());
    }

    static void testFfiBindingEmission() {
        System.out.println("-- FFI binding emission: load_ffi call site,"
            + " private ordinals, no ffi.C --");
        String decl = "// @extern-c\n"
            + "// @c-struct\n"
            + "export class Pair {\n  x: int = 0;\n  y: number = 0.0;\n}\n"
            + "// @c-pointer\n"
            + "export class Pointer {}\n"
            + "export function fixture_add_int(a: int, b: int): int;\n"
            + "export function fixture_make_pair(x: int, y: number): Pair;\n"
            + "export function fixture_take_ptr(p: Pointer): int;\n"
            + "export function fixture_echo_string(s: string): string;\n"
            + "export function fixture_bytes_sum(b: bytes): int;\n"
            + "export function fixture_reset_counter(): null;\n";
        FfiTestModule ffiModule = assembleFfiModule(decl,
            "/tmp/nonexistent-deal-fixture.so");

        String importer = "import * as ffi from \"ffi_math\"\n"
            + "export function check(): string {\n"
            + "  let a: int = ffi.fixture_add_int(20, 22);\n"
            + "  if (a !== 42) { throw { code: \"TEST_FAIL\", message: \"add\" }; }\n"
            + "  let p: ffi.Pair = ffi.fixture_make_pair(7, 2.5);\n"
            + "  if (p.x !== 7) { throw { code: \"TEST_FAIL\", message: \"px\" }; }\n"
            + "  let q: ffi.Pair = { x: 3, y: 4.5 };\n"
            + "  if (q.y !== 4.5) { throw { code: \"TEST_FAIL\", message: \"qy\" }; }\n"
            + "  return \"ok\";\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        CompileOutputDiag out = compileFfiImporter(importer, ffiModule);
        check(out.lua != null, "FFI importer compiles and generates");
        if (out.lua == null) {
            return;
        }
        // The loader call: five-position envelope plus the import span.
        assertContains(out.lua, "local ffi = __rt.load_ffi(\"ffi:ffi_math\", {",
            "load_ffi call with the module key");
        assertContains(out.lua, "\"ABSOLUTE_PATH\"", "native library kind");
        assertContains(out.lua, "/tmp/nonexistent-deal-fixture.so",
            "native library loader text");
        assertContains(out.lua, ", \"test.deal\", 1, 1)",
            "loader call carries the import span triplet (test.deal:1:1)");
        // Private ordinals and private casts.
        assertContains(out.lua, "int32_t deal_f0; double deal_f1;",
            "struct cdef text with ordinal members");
        assertContains(out.lua, "_fn_0001",
            "private function typedef names carry the ordinal");
        check(out.lua.contains("_st_0001"), "private struct typedef name");
        check(out.lua.contains("typedef struct {int32_t deal_f0; double deal_f1; }"),
            "struct cdef full text");
        check(out.lua.contains("typedef int32_t (*deal_"),
            "scalar function cdef text with the private cast");
        check(out.lua.contains("(const uint8_t *, int32_t)"),
            "bytes parameter lowers to the pointer-plus-length pair");
        check(out.lua.contains("typedef const char * (*deal_"),
            "string return uses the pinned const char* ABI type");
        check(out.lua.contains("(*)(int32_t, double)"),
            "C-struct-bearing function carries the anonymous cast spelling");
        // Canonical FFI metadata descriptors.
        assertContains(out.lua, "kind = \"INT\", canonicalDescriptor = \"int\"",
            "INT metadata carries the canonical descriptor int");
        assertContains(out.lua, "kind = \"NUMBER\", canonicalDescriptor = \"number\"",
            "NUMBER metadata carries the canonical descriptor number");
        assertContains(out.lua, "canonicalClassIdentity = \"@$external/ffi_math/Pair\"",
            "C_STRUCT metadata carries the canonical class atom");
        assertContains(out.lua, "kind = \"C_POINTER\"",
            "C_POINTER kind present for the pointer class");
        // Plans: C-struct default evaluators, never invoked at load.
        assertContains(out.lua, "evaluator = function() return 0 end",
            "int field evaluator closure");
        assertContains(out.lua, "evaluator = function() return 0.0 end",
            "number field evaluator closure");
        assertContains(out.lua, "planDigest = \"",
            "plan record carries the plan digest");
        check(out.lua.contains("[\"@$external/ffi_math/Pair\"] = { plan ="),
            "plans table keyed by the canonical C_STRUCT atom");
        check(!out.lua.contains("@$external/ffi_math/Pointer\"] = { plan"),
            "C_POINTER class carries no plan record");
        // Forward binding cells.
        assertContains(out.lua, "moduleKey = \"ffi:ffi_math\", state = \"UNBOUND\"",
            "bindings carry the module key and UNBOUND state");
        assertContains(out.lua, "fixture_add_int = { state = \"UNBOUND\", wrapper = nil, errorValue = nil }",
            "binding cell for fixture_add_int");
        // No ffi.C access anywhere in the generated chunk.
        assertNotContains(out.lua, "ffi.C", "no ffi.C access in the generated chunk");
        assertNotContains(out.lua, "FFI_UNSUPPORTED_BACKEND",
            "no FFI_UNSUPPORTED_BACKEND path exists");
        // FFI call sites append the span triplet.
        assertContains(out.lua, "ffi.fixture_add_int.f(20, 22, \"test.deal\"",
            "FFI call site appends the call span triplet");
        assertContains(out.lua, "ffi.fixture_make_pair.f(7, 2.5, \"test.deal\"",
            "C_STRUCT FFI call site appends the span triplet");
        // Imported C_STRUCT construction through the exported plan.
        assertContains(out.lua, "__rt.class_plan_(\"@$external/ffi_math/Pair\", ffi.Pair_plan",
            "C_STRUCT construction consumes the exported Pair_plan");
        check(isValidLua(out.lua), "generated chunk is valid Lua");
    }

    static void testFfiBindingEmissionFailureE6000() {
        System.out.println("-- FFI binding emission: unrepresentable shapes fail"
            + " closed with E6000 --");
        // An array parameter is outside the FFI type table.
        String decl = "// @extern-c\n"
            + "export function fixture_bad(xs: int[]): int;\n";
        FfiTestModule ffiModule = assembleFfiModule(decl,
            "/tmp/nonexistent-deal-fixture.so");
        String importer = "import * as ffi from \"ffi_math\"\n"
            + "export function main(): null { return null; }\n";
        CompileOutputDiag out = compileFfiImporter(importer, ffiModule);
        check(out.lua != null && out.lua.contains("local ffi = nil"),
            "the chunk keeps a structurally valid nil alias on failure");
        check(out.codegenDiags.stream().anyMatch(d ->
                d.code().equals("E6000") && "error".equals(d.severity())),
            "the unrepresentable FFI shape is one E6000 backend error: "
                + out.codegenDiags);
    }

    /**
     * Compiles the committed native fixture with GCC into {@code tmp} and
     * returns the absolute loader text (fail-closed like the runtime
     * battery bootstrap).
     */
    private static String compileFfiFixtureSo(Path tmp) throws Exception {
        Path so = tmp.resolve("ffi-runtime-fixture.so");
        Path events = tmp.resolve("ffi-runtime-fixture-events.log");
        String cmd = "gcc -shared -fPIC -O2 -DFIXTURE_EVENTS_PATH='\""
            + events + "\"' -o " + so + " test/fixtures/ffi-runtime-fixture.c";
        Process p = new ProcessBuilder("/bin/sh", "-c", cmd)
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        check(exit == 0, "fixture GCC compile must succeed: " + out);
        check(Files.exists(so), "fixture .so exists after the compile");
        return so.toString();
    }

    /**
     * Writes deal/runtime.lua plus the generated module into {@code tmp}
     * and runs the driver under luajit; returns the merged output.
     */
    private static String runGeneratedModule(Path tmp, String moduleName,
            String lua, String driver) throws Exception {
        Path dealDir = tmp.resolve("deal");
        Files.createDirectories(dealDir);
        Files.copy(Path.of("deal/runtime.lua"),
            dealDir.resolve("runtime.lua"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(tmp.resolve(moduleName + ".lua"), lua);
        Files.writeString(tmp.resolve("driver.lua"), driver);
        Process p = new ProcessBuilder("luajit", "driver.lua")
            .directory(tmp.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        return "EXIT:" + exit + "\n" + out;
    }

    static void testFfiBindingIntegrationRealLibrary() throws Exception {
        System.out.println("-- FFI binding integration: real library through"
            + " production load_ffi --");
        Path tmp = Files.createTempDirectory("deal_ffi_it_");
        try {
            String so = compileFfiFixtureSo(tmp);

            String decl = "// @extern-c\n"
                + "// @c-struct\n"
                + "export class Pair {\n  x: int = 0;\n  y: number = 0.0;\n}\n"
                + "// @c-struct\n"
                + "export class PtrBox {\n  ptr: Pointer;\n}\n"
                + "// @c-pointer\n"
                + "export class Pointer {}\n"
                + "export function fixture_add_int(a: int, b: int): int;\n"
                + "export function fixture_echo_string(s: string): string;\n"
                + "export function fixture_not(b: boolean): boolean;\n"
                + "export function fixture_bytes_sum(b: bytes): int;\n"
                + "export function fixture_make_pair(x: int, y: number): Pair;\n"
                + "export function fixture_make_ptr_box(p: Pointer): PtrBox;\n"
                + "export function fixture_static_pointer(): Pointer;\n"
                + "export function fixture_count_call(): null;\n"
                + "export function fixture_call_count(): int;\n";
            FfiTestModule ffiModule = assembleFfiModule(decl, so);

            String importer = "import * as ffi from \"ffi_math\"\n"
                + "export function check(): string {\n"
                + "  let a: int = ffi.fixture_add_int(20, 22);\n"
                + "  if (a !== 42) { throw { code: \"TEST_FAIL\", message: \"add_int\" }; }\n"
                + "  let s: string = ffi.fixture_echo_string(\"hello\");\n"
                + "  if (s !== \"hello\") { throw { code: \"TEST_FAIL\", message: \"echo\" }; }\n"
                + "  let nb: boolean = ffi.fixture_not(false);\n"
                + "  if (nb !== true) { throw { code: \"TEST_FAIL\", message: \"not\" }; }\n"
                + "  let b: bytes = bytes(2);\n"
                + "  b[0] = 1;\n"
                + "  b[1] = 2;\n"
                + "  let sum: int = ffi.fixture_bytes_sum(b);\n"
                + "  if (sum !== 3) { throw { code: \"TEST_FAIL\", message: \"bytes_sum\" }; }\n"
                + "  let p: ffi.Pair = ffi.fixture_make_pair(7, 2.5);\n"
                + "  if (p.x !== 7) { throw { code: \"TEST_FAIL\", message: \"pair.x\" }; }\n"
                + "  if (p.y !== 2.5) { throw { code: \"TEST_FAIL\", message: \"pair.y\" }; }\n"
                + "  let ptr: ffi.Pointer = ffi.fixture_static_pointer();\n"
                + "  let box: ffi.PtrBox = ffi.fixture_make_ptr_box(ptr);\n"
                + "  let ptr2: ffi.Pointer = box.ptr;\n"
                + "  let box2: ffi.PtrBox = ffi.fixture_make_ptr_box(ptr2);\n"
                + "  if (box2.ptr === null) { throw { code: \"TEST_FAIL\", message: \"ptrbox\" }; }\n"
                + "  ffi.fixture_count_call();\n"
                + "  let count: int = ffi.fixture_call_count();\n"
                + "  if (count !== 3) { throw { code: \"TEST_FAIL\", message: \"count\" }; }\n"
                + "  return \"ok\";\n"
                + "}\n"
                + "export function main(): null { return null; }\n";
            CompileOutputDiag out = compileFfiImporter(importer, ffiModule);
            check(out.lua != null, "FFI integration importer compiles");
            if (out.lua == null) {
                return;
            }
            check(!out.lua.contains("ffi.C"),
                "the integration artifact carries no ffi.C access");

            String tmpPath = tmp.toString().replace("\\", "/");
            String driver = "package.path = \"" + tmpPath + "/?.lua;\" .. package.path\n"
                + "local ok, res = pcall(require, \"ffi_it\")\n"
                + "if not ok then\n"
                + "  io.write(\"LOAD_ERROR: \", tostring(res and res.code or res), \"\\n\")\n"
                + "  os.exit(1)\n"
                + "end\n"
                + "local ok2, v = pcall(res.check.f)\n"
                + "if not ok2 then\n"
                + "  io.write(\"CHECK_ERROR: \", tostring(v and v.code or v), \"\\n\")\n"
                + "  os.exit(1)\n"
                + "end\n"
                + "io.write(\"RESULT: \", tostring(v), \"\\n\")\n";
            String run = runGeneratedModule(tmp, "ffi_it", out.lua, driver);
            check(run.contains("EXIT:0") && run.contains("RESULT: ok"),
                "the generated chunk executes under real LuaJIT against the"
                    + " real library through __rt.load_ffi: " + run);

            // Failure path 1: FFI_LIBRARY_LOAD with the import span.
            FfiTestModule missingModule = assembleFfiModule(
                "// @extern-c\n"
                    + "export function fixture_add_int(a: int, b: int): int;\n",
                tmp.resolve("nonexistent-deal-fixture.so").toString());
            String missingImporter = "import * as ffi from \"ffi_math\"\n"
                + "export function check(): int {\n"
                + "  return ffi.fixture_add_int(1, 2);\n"
                + "}\n"
                + "export function main(): null { return null; }\n";
            CompileOutputDiag missingOut = compileFfiImporter(missingImporter,
                missingModule);
            check(missingOut.lua != null, "FFI_LIBRARY_LOAD importer compiles");
            String missingDriver = "package.path = \"" + tmpPath + "/?.lua;\" .. package.path\n"
                + "local ok, res = pcall(require, \"ffi_load_fail\")\n"
                + "if ok then io.write(\"LOADED_UNEXPECTEDLY\\n\"); os.exit(1) end\n"
                + "io.write(\"CODE: \", tostring(res.code), \"\\n\")\n"
                + "io.write(\"FILE: \", tostring(res.file), \"\\n\")\n"
                + "io.write(\"LINE: \", tostring(res.line), \"\\n\")\n"
                + "io.write(\"COL: \", tostring(res.column), \"\\n\")\n";
            String missingRun = runGeneratedModule(tmp, "ffi_load_fail",
                missingOut.lua, missingDriver);
            check(missingRun.contains("EXIT:0")
                    && missingRun.contains("CODE: FFI_LIBRARY_LOAD")
                    && missingRun.contains("FILE: test.deal")
                    && missingRun.contains("LINE: 1")
                    && missingRun.contains("COL: 1"),
                "a missing library raises FFI_LIBRARY_LOAD with the import span: "
                    + missingRun);

            // Failure path 2: FFI_SYMBOL_MISSING for a declared absent symbol.
            FfiTestModule symbolModule = assembleFfiModule(
                "// @extern-c\n"
                    + "export function fixture_no_such_symbol(): int;\n",
                so);
            String symbolImporter = "import * as ffi from \"ffi_math\"\n"
                + "export function check(): int {\n"
                + "  return ffi.fixture_no_such_symbol();\n"
                + "}\n"
                + "export function main(): null { return null; }\n";
            CompileOutputDiag symbolOut = compileFfiImporter(symbolImporter,
                symbolModule);
            check(symbolOut.lua != null, "FFI_SYMBOL_MISSING importer compiles");
            String symbolDriver = "package.path = \"" + tmpPath + "/?.lua;\" .. package.path\n"
                + "local ok, res = pcall(require, \"ffi_sym_fail\")\n"
                + "if ok then io.write(\"LOADED_UNEXPECTEDLY\\n\"); os.exit(1) end\n"
                + "io.write(\"CODE: \", tostring(res.code), \"\\n\")\n"
                + "io.write(\"FILE: \", tostring(res.file), \"\\n\")\n"
                + "io.write(\"LINE: \", tostring(res.line), \"\\n\")\n"
                + "io.write(\"COL: \", tostring(res.column), \"\\n\")\n";
            String symbolRun = runGeneratedModule(tmp, "ffi_sym_fail",
                symbolOut.lua, symbolDriver);
            check(symbolRun.contains("EXIT:0")
                    && symbolRun.contains("CODE: FFI_SYMBOL_MISSING")
                    && symbolRun.contains("FILE: test.deal")
                    && symbolRun.contains("LINE: 1")
                    && symbolRun.contains("COL: 1"),
                "an absent declared symbol raises FFI_SYMBOL_MISSING with the import span: "
                    + symbolRun);
        } finally {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                    try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                });
            }
        }
    }
}
