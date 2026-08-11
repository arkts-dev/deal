package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Spec-centric conformance test runner for DEAL v1.0.
 *
 * <p>Discovers all .deal files under test/conformance/frontend/ and
 * test/conformance/backend-runtime/, parses metadata header comments,
 * compiles and/or executes each test according to its @expected tag,
 * and produces a pass/fail report with spec coverage summary.</p>
 *
 * <p>Phase validation: tests in frontend/ may only use compile-ok or
 * compile-error @expected modes. Tests in backend-runtime/ may use
 * runtime-ok or runtime-error modes. Violations are configuration errors.</p>
 */
public class ConformanceTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static final Map<String, List<TestResult>> specGroups = new LinkedHashMap<>();
    private static boolean luajitAvailable;

    // =========================================================================
    // Data types
    // =========================================================================

    private record TestFile(
        Path path,
        String relativePath,
        String phase,          // "frontend" or "backend-runtime"
        String spec,
        String description,
        String expected,
        String features,
        List<String> outputs,  // @output tags
        String errorCode,      // @error tag (single)
        List<String> values    // @value tags: "expr => literal"
    ) {}

    private record TestResult(
        TestFile test,
        boolean pass,
        String message
    ) {}

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        String conformanceRoot = "test/conformance/";
        if (args.length > 0) {
            conformanceRoot = args[0];
        }

        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        System.out.println("=== DEAL v1.0 Conformance Test Suite ===");
        System.out.println("Root: " + conformanceRoot);
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        System.out.println();

        // Discover test files
        List<TestFile> tests = discoverTests(Path.of(conformanceRoot));
        System.out.println("Discovered " + tests.size() + " conformance test(s)");
        System.out.println();

        // Run each test
        for (TestFile test : tests) {
            runTest(test);
        }

        // Print summary
        printSummary();

        // Print coverage report
        printCoverageReport();

        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Discovery
    // =========================================================================

    private static List<TestFile> discoverTests(Path root) throws IOException {
        List<TestFile> result = new ArrayList<>();

        // Walk frontend/
        Path frontendRoot = root.resolve("frontend");
        if (Files.isDirectory(frontendRoot)) {
            try (var stream = Files.walk(frontendRoot)) {
                stream.filter(p -> p.toString().endsWith(".deal"))
                      .sorted()
                      .forEach(p -> {
                          TestFile tf = parseMetadata(p, root, "frontend");
                          if (tf != null) {
                              result.add(tf);
                          }
                      });
            }
        }

        // Walk backend-runtime/
        Path backendRoot = root.resolve("backend-runtime");
        if (Files.isDirectory(backendRoot)) {
            try (var stream = Files.walk(backendRoot)) {
                stream.filter(p -> p.toString().endsWith(".deal"))
                      .sorted()
                      .forEach(p -> {
                          TestFile tf = parseMetadata(p, root, "backend-runtime");
                          if (tf != null) {
                              result.add(tf);
                          }
                      });
            }
        }

        return result;
    }

    private static TestFile parseMetadata(Path file, Path root, String phase) {
        try {
            List<String> lines = Files.readAllLines(file);
            String spec = "";
            String description = "";
            String expected = "";
            String features = "";
            List<String> outputs = new ArrayList<>();
            String errorCode = "";
            List<String> values = new ArrayList<>();

            int linesToScan = Math.min(lines.size(), 30);
            for (int i = 0; i < linesToScan; i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("// @spec:")) {
                    spec = line.substring("// @spec:".length()).trim();
                } else if (line.startsWith("// @description:")) {
                    description = line.substring("// @description:".length()).trim();
                } else if (line.startsWith("// @expected:")) {
                    expected = line.substring("// @expected:".length()).trim();
                } else if (line.startsWith("// @features:")) {
                    features = line.substring("// @features:".length()).trim();
                } else if (line.startsWith("// @output:")) {
                    outputs.add(line.substring("// @output:".length()).trim());
                } else if (line.startsWith("// @error:")) {
                    errorCode = line.substring("// @error:".length()).trim();
                } else if (line.startsWith("// @value:")) {
                    values.add(line.substring("// @value:".length()).trim());
                }
            }

            if (expected.isEmpty()) {
                System.err.println("WARNING: " + file + " has no @expected tag, skipping");
                return null;
            }

            String relPath = root.relativize(file).toString();
            return new TestFile(file, relPath, phase,
                spec, description, expected, features,
                outputs, errorCode, values);
        } catch (IOException e) {
            System.err.println("WARNING: cannot read " + file + ": " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Test execution dispatch
    // =========================================================================

    private static void runTest(TestFile test) {
        System.out.print("  [" + test.phase() + ": " + test.relativePath() + "] ");
        String expected = test.expected();

        // Phase validation (D4)
        String phase = test.phase();
        boolean isRuntimeMode = expected.startsWith("runtime-ok") ||
                                expected.startsWith("runtime-error");

        if (phase.equals("frontend") && isRuntimeMode) {
            System.out.println("CONFIGURATION ERROR (frontend test with @expected: " + expected + ")");
            failed++;
            addResult(test, false, "configuration error: frontend test with runtime @expected: " + expected);
            return;
        }

        try {
            if (expected.startsWith("compile-ok")) {
                runCompileOk(test);
            } else if (expected.startsWith("runtime-ok")) {
                runRuntimeOk(test);
            } else if (expected.startsWith("compile-error ")) {
                String code = expected.substring("compile-error ".length()).trim();
                runCompileError(test, code);
            } else if (expected.startsWith("runtime-error ")) {
                String code = expected.substring("runtime-error ".length()).trim();
                runRuntimeError(test, code);
            } else {
                System.out.println("SKIP (unknown @expected: " + expected + ")");
                skipped++;
                addResult(test, false, "unknown @expected: " + expected);
            }
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            failed++;
            addResult(test, false, "exception: " + e.getMessage());
        }
    }

    // =========================================================================
    // compile-ok
    // =========================================================================

    private static void runCompileOk(TestFile test) throws Exception {
        List<Diagnostic> diags = compileAndGetDiagnostics(test);
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            System.out.println("FAIL (unexpected compile errors)");
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            failed++;
            addResult(test, false, "unexpected compile errors");
        } else {
            System.out.println("OK");
            passed++;
            addResult(test, true, "compile ok");
        }
    }

    // =========================================================================
    // runtime-ok
    // =========================================================================

    private static void runRuntimeOk(TestFile test) throws Exception {
        if (!luajitAvailable) {
            System.out.println("SKIP (LuaJIT not available)");
            skipped++;
            addResult(test, false, "skipped: LuaJIT not available");
            return;
        }

        List<Diagnostic> diags = compileAndGetDiagnostics(test);
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            System.out.println("FAIL (unexpected compile errors)");
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            failed++;
            addResult(test, false, "unexpected compile errors");
            return;
        }

        String lua = generateLua(test.path());
        if (lua == null) {
            System.out.println("FAIL (codegen failed)");
            failed++;
            addResult(test, false, "codegen failed");
            return;
        }

        // Check for @value tags — these require a special wrapper
        if (!test.values().isEmpty()) {
            runRuntimeValues(test, lua);
            return;
        }

        String output = executeLua(lua, false);
        if (output == null) {
            System.out.println("FAIL (Lua execution failed)");
            failed++;
            addResult(test, false, "Lua execution returned null");
            return;
        }

        // Check @output assertions
        if (!test.outputs().isEmpty()) {
            boolean allMatch = true;
            for (String expectedOutput : test.outputs()) {
                if (!output.contains(expectedOutput)) {
                    System.out.println("FAIL (@output not found: \"" + expectedOutput + "\")");
                    System.out.println("    actual output: " + output.replace("\n", "\\n"));
                    allMatch = false;
                }
            }
            if (allMatch) {
                System.out.println("OK");
                passed++;
                addResult(test, true, "runtime ok with @output match");
            } else {
                failed++;
                addResult(test, false, "@output mismatch");
            }
        } else {
            System.out.println("OK");
            passed++;
            addResult(test, true, "runtime ok");
        }
    }

    /**
     * Run runtime-ok tests that have @value tags.
     * Compiles a wrapper that prints each expression value and asserts
     * stdout matches the expected literal.
     */
    private static void runRuntimeValues(TestFile test, String generatedLua) throws Exception {
        // Build a Lua runner that evaluates each expression and prints results
        StringBuilder sb = new StringBuilder();
        sb.append("package.path = './?.lua;./std/?.lua;' .. package.path\n");
        sb.append("local __mod = (function()\n");
        sb.append(generatedLua).append("\n");
        sb.append("end)()\n");

        // For each @value tag, evaluate the expression within the module scope
        // We need to expose module exports as globals for the expressions
        sb.append("if type(__mod) == 'table' then\n");
        sb.append("  for __k, __v in pairs(__mod) do\n");
        sb.append("    if type(__v) == 'table' and __v.__kind == 'function' then\n");
        sb.append("      _G[__k] = function(...) return __v.f(...) end\n");
        sb.append("    else\n");
        sb.append("      _G[__k] = __v\n");
        sb.append("    end\n");
        sb.append("  end\n");
        sb.append("end\n");

        boolean allPassed = true;
        for (String valueTag : test.values()) {
            String[] parts = valueTag.split("=>", 2);
            if (parts.length != 2) {
                System.out.println("FAIL (malformed @value: " + valueTag + ")");
                allPassed = false;
                continue;
            }
            String expr = parts[0].trim();
            String expectedValue = parts[1].trim();

            // Build a Lua snippet to evaluate expr and compare
            String runner = sb.toString() +
                "local __val = " + expr + "\n" +
                "local __expected = " + expectedValue + "\n" +
                "if type(__val) == type(__expected) then\n" +
                "  if __val == __expected then\n" +
                "    print('DEAL_VALUE_MATCH:' .. tostring(__val))\n" +
                "  else\n" +
                "    print('DEAL_VALUE_MISMATCH: expected ' .. tostring(__expected) .. ' got ' .. tostring(__val))\n" +
                "  end\n" +
                "else\n" +
                "  print('DEAL_VALUE_MISMATCH: type difference, expected ' .. tostring(__expected) .. ' got ' .. tostring(__val))\n" +
                "end\n";

            String output = executeLuaRaw(runner);
            if (output == null) {
                System.out.println("FAIL (@value execution failed for: " + expr + ")");
                allPassed = false;
                continue;
            }

            if (output.contains("DEAL_VALUE_MATCH:")) {
                // this one passed
            } else if (output.contains("DEAL_VALUE_MISMATCH:")) {
                System.out.println("FAIL (@value mismatch: " + valueTag + ")");
                System.out.println("    " + output.replace("\n", "\\n"));
                allPassed = false;
            } else {
                System.out.println("FAIL (@value unexpected output for: " + expr + ")");
                System.out.println("    " + output.replace("\n", "\\n"));
                allPassed = false;
            }
        }

        if (allPassed) {
            System.out.println("OK (" + test.values().size() + " @value assertion(s))");
            passed++;
            addResult(test, true, "runtime ok with @value match");
        } else {
            failed++;
            addResult(test, false, "@value mismatch");
        }
    }

    // =========================================================================
    // compile-error
    // =========================================================================

    private static void runCompileError(TestFile test, String expectedCode) throws Exception {
        List<Diagnostic> diags = compileAndGetDiagnostics(test);
        boolean found = diags.stream().anyMatch(
            d -> "error".equals(d.severity()) && expectedCode.equals(d.code()));
        if (found) {
            System.out.println("OK (found " + expectedCode + ")");
            passed++;
            addResult(test, true, "found " + expectedCode);
        } else {
            List<String> gotCodes = diags.stream()
                .filter(d -> "error".equals(d.severity()))
                .map(Diagnostic::code)
                .toList();
            System.out.println("FAIL (expected " + expectedCode +
                ", got: " + gotCodes + ")");
            failed++;
            addResult(test, false, "expected " + expectedCode + ", got: " + gotCodes);
        }
    }

    // =========================================================================
    // runtime-error
    // =========================================================================

    private static void runRuntimeError(TestFile test, String expectedCode) throws Exception {
        if (!luajitAvailable) {
            System.out.println("SKIP (LuaJIT not available)");
            skipped++;
            addResult(test, false, "skipped: LuaJIT not available");
            return;
        }

        // @error tag overrides the expected code in @expected (D2)
        String effectiveCode = test.errorCode().isEmpty() ? expectedCode : test.errorCode();

        List<Diagnostic> diags = compileAndGetDiagnostics(test);
        boolean hasErrors = diags.stream().anyMatch(d -> "error".equals(d.severity()));
        if (hasErrors) {
            System.out.println("FAIL (unexpected compile errors for runtime-error test)");
            for (Diagnostic d : diags) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            failed++;
            addResult(test, false, "unexpected compile errors");
            return;
        }

        String lua = generateLua(test.path());
        if (lua == null) {
            System.out.println("FAIL (codegen failed)");
            failed++;
            addResult(test, false, "codegen failed");
            return;
        }

        String output = executeLua(lua, true);
        if (output == null) {
            System.out.println("FAIL (Lua execution returned null)");
            failed++;
            addResult(test, false, "Lua execution returned null");
            return;
        }

        String needle = "DEAL_ERROR_CODE: " + effectiveCode;
        if (output.contains(needle)) {
            System.out.println("OK (found " + needle + ")");
            passed++;
            addResult(test, true, "found " + effectiveCode);

            // Check @output if present for runtime-error tests too
            if (!test.outputs().isEmpty()) {
                for (String expectedOutput : test.outputs()) {
                    if (!output.contains(expectedOutput)) {
                        System.out.println("    WARNING: @output \"" + expectedOutput + "\" not found in error output");
                    }
                }
            }
        } else {
            System.out.println("FAIL (expected " + needle + ", got: " +
                output.replace("\n", "\\n") + ")");
            failed++;
            addResult(test, false, "expected " + needle + ", got: " + output);
        }
    }

    // =========================================================================
    // Compilation helpers
    // =========================================================================

    private static List<Diagnostic> compileAndGetDiagnostics(TestFile test) throws Exception {
        String source = Files.readString(test.path());
        String filename = test.path().toString();

        LexResult lex = new Lexer(source, filename).tokenize();
        List<Diagnostic> allDiags = new ArrayList<>(lex.diagnostics());
        if (lex.hasErrors()) {
            return allDiags;
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        allDiags.addAll(parseResult.diagnostics());
        if (parseResult.hasErrors()) {
            return allDiags;
        }

        ConformanceModuleResolver resolver = new ConformanceModuleResolver(test.path());
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            allDiags.add(Diagnostic.error("E9999", e.getMessage(), filename, 1, 1));
            return allDiags;
        }
        allDiags.addAll(nr.diagnostics());

        CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
        allDiags.addAll(result.diagnostics());

        return allDiags;
    }

    private static String generateLua(Path file) throws Exception {
        String source = Files.readString(file);
        String filename = file.toString();

        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) return null;

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) return null;

        ConformanceModuleResolver resolver = new ConformanceModuleResolver(file);
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parseResult.program());
        if (nr.diagnostics().stream().anyMatch(d -> "error".equals(d.severity())))
            return null;

        CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
        if (result.hasErrors()) return null;

        return LuaBackend.generate(parseResult.program(), result, filename);
    }

    // =========================================================================
    // Lua execution
    // =========================================================================

    private static String executeLua(String luaSource, boolean isXpcallWrapped) {
        String runner;
        if (isXpcallWrapped) {
            runner = buildXpcallRunner(luaSource);
        } else {
            runner = buildRuntimeOkRunner(luaSource);
        }
        return executeLuaRaw(runner);
    }

    /**
     * Execute a raw Lua script string via luajit and return stdout.
     * Returns null on failure (non-zero exit).
     */
    private static String executeLuaRaw(String luaCode) {
        try {
            Path tmpDir = Files.createTempDirectory("deal_conf_");
            Path luaFile = tmpDir.resolve("test_main.lua");
            Files.writeString(luaFile, luaCode);

            // Copy runtime
            Path runtimeDir = tmpDir.resolve("deal");
            Files.createDirectories(runtimeDir);
            Files.copy(Path.of("deal/runtime.lua"), runtimeDir.resolve("runtime.lua"));

            // Copy stdlib .lua files
            Path stdDir = Path.of("std");
            if (Files.isDirectory(stdDir)) {
                Path targetStdDir = tmpDir.resolve("std");
                Files.createDirectories(targetStdDir);
                try (var stream = Files.list(stdDir)) {
                    stream.filter(p -> p.toString().endsWith(".lua"))
                          .forEach(p -> {
                              try {
                                  Files.copy(p, targetStdDir.resolve(p.getFileName()));
                              } catch (IOException ignored) {}
                          });
                }
            }

            ProcessBuilder pb = new ProcessBuilder("luajit", luaFile.toString());
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();

            // Cleanup
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}

            if (exit != 0) {
                System.err.println("    LuaJIT exit " + exit + ": " + output);
                return null;
            }
            return output;
        } catch (Exception e) {
            System.err.println("    Lua execution exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a runner for runtime-ok tests: load the module and call all
     * exported zero-argument functions.
     */
    private static String buildRuntimeOkRunner(String generatedLua) {
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __mod = (function()\n" +
            generatedLua + "\n" +
            "end)()\n" +
            "if type(__mod) == 'table' then\n" +
            "  for __k, __v in pairs(__mod) do\n" +
            "    if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "      __v.f()\n" +
            "    end\n" +
            "  end\n" +
            "end\n";
    }

    /**
     * Build an xpcall-wrapped runner for runtime-error tests.
     * The runner always exits 0 even on caught errors to allow output capture.
     */
    private static String buildXpcallRunner(String generatedLua) {
        return
            "package.path = './?.lua;./std/?.lua;' .. package.path\n" +
            "local __ok, __err = xpcall(function()\n" +
            "  local __mod = (function()\n" +
            generatedLua + "\n" +
            "  end)()\n" +
            "  if type(__mod) == 'table' then\n" +
            "    for __k, __v in pairs(__mod) do\n" +
            "      if type(__v) == 'table' and __v.__kind == 'function' then\n" +
            "        __v.f()\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end, function(err)\n" +
            "  if type(err) == 'table' and err.code ~= nil then\n" +
            "    print('DEAL_ERROR_CODE: ' .. err.code)\n" +
            "  else\n" +
            "    print('DEAL_ERROR_CODE: ' .. tostring(err))\n" +
            "  end\n" +
            "end)\n";
    }

    // =========================================================================
    // Results & coverage
    // =========================================================================

    private static void addResult(TestFile test, boolean pass, String message) {
        String specKey = test.spec().isEmpty() ? "(no @spec)" : test.spec();
        specGroups.computeIfAbsent(specKey, k -> new ArrayList<>())
                  .add(new TestResult(test, pass, message));
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("=== Conformance Summary ===");
        int total = passed + failed + skipped;
        System.out.println("Total: " + total + ", Passed: " + passed +
            ", Failed: " + failed + ", Skipped: " + skipped);
    }

    private static void printCoverageReport() {
        System.out.println();
        System.out.println("=== Spec Coverage Report ===");
        System.out.println();

        List<String> specSections = List.of(
            "Lexical elements",
            "Syntactic grammar",
            "Type system",
            "Classes",
            "Functions",
            "Variables",
            "Tables",
            "Arrays",
            "Control flow",
            "Error handling",
            "Coroutines",
            "Modules, declarations, standard library, and host ABI",
            "Diagnostics",
            "Runtime execution model",
            "Standard library declarations"
        );

        for (String section : specSections) {
            List<TestResult> sectionResults = new ArrayList<>();
            for (var entry : specGroups.entrySet()) {
                if (entry.getKey().startsWith(section)) {
                    sectionResults.addAll(entry.getValue());
                }
            }

            if (section.equals("Coroutines")) {
                System.out.printf("  %-55s %s%n", "\u00a7Coroutines", "excluded");
                continue;
            }

            if (sectionResults.isEmpty()) {
                System.out.printf("  %-55s %s%n",
                    "\u00a7" + section, "UNCOVERED (0 tests)");
            } else {
                long sectionPassed = sectionResults.stream().filter(r -> r.pass()).count();
                long sectionTotal = sectionResults.size();
                System.out.printf("  %-55s %d/%d passed%n",
                    "\u00a7" + section, sectionPassed, sectionTotal);
            }
        }

        System.out.println();
        System.out.println(
            "Note: \u00a7Coroutines is excluded from v1.0 per epic objective.");
    }

    // =========================================================================
    // Module resolver for conformance tests
    // =========================================================================

    /**
     * A module resolver that resolves stdlib modules using hardcoded
     * export signatures matching the v1.0 spec, and resolves relative
     * file imports using ExportExtractor.
     */
    private static class ConformanceModuleResolver implements ModuleResolver {

        private final Path testFileDir;
        private final Map<String, Map<String, Type>> stdlibExports;

        ConformanceModuleResolver(Path testFile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.stdlibExports = buildStdlibExports();
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            // Check stdlib
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }

            // Try relative file import
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                return resolveFileModule(resolved, modulesInProgress);
            }

            throw new ModuleNotFoundException("Module not found: " + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            return null; // Not needed for conformance tests currently
        }

        // ---- stdlib exports (hardcoded per spec v1.0) ----
        // (Retained for backward compatibility; will be replaced
        //  by dynamic .d.deal parsing in T7.)

        private static Map<String, Map<String, Type>> buildStdlibExports() {
            Map<String, Map<String, Type>> map = new LinkedHashMap<>();

            map.put("std/console", module(
                fn("log", list(strType()), nullType()),
                fn("error", list(strType()), nullType())
            ));

            map.put("std/string", module(
                fn("length", list(strType()), intType()),
                fn("substring", list(strType(), intType(), intType()), strType()),
                fn("contains", list(strType(), strType()), boolType()),
                fn("startsWith", list(strType(), strType()), boolType()),
                fn("endsWith", list(strType(), strType()), boolType()),
                fn("replace", list(strType(), strType(), strType()), strType()),
                fn("split", list(strType(), strType()), arrayType(strType())),
                fn("trim", list(strType()), strType())
            ));

            map.put("std/table", module(
                fn("keys", list(tableType()), arrayType(strType()))
            ));

            map.put("std/json", module(
                fn("parse", list(strType()), tableType()),
                fn("stringify", list(tableType()), strType())
            ));

            map.put("std/math", module(
                fn("floor", list(numType()), numType()),
                fn("ceil", list(numType()), numType()),
                fn("sqrt", list(numType()), numType()),
                fn("absInt", list(intType()), intType()),
                fn("absNumber", list(numType()), numType()),
                fn("minInt", list(intType(), intType()), intType()),
                fn("maxInt", list(intType(), intType()), intType())
            ));

            map.put("std/time", module(
                fn("nowMillis", list(), intType())
            ));

            map.put("std/io", module(
                fn("readText", list(strType()), strType()),
                fn("writeText", list(strType(), strType()), nullType())
            ));

            return map;
        }

        // ---- type helpers ----

        private static Type intType()    { return Type.Int.INSTANCE; }
        private static Type numType()    { return Type.Number.INSTANCE; }
        private static Type strType()    { return Type.String.INSTANCE; }
        private static Type boolType()   { return Type.Boolean.INSTANCE; }
        private static Type nullType()   { return Type.Null.INSTANCE; }
        private static Type tableType()  { return Type.Table.INSTANCE; }

        private static Type arrayType(Type elem) {
            return Types.array(elem);
        }

        private static Type fnType(List<Type> params, Type ret) {
            return Types.func(params, ret);
        }

        @SafeVarargs
        private static Map<String, Type> module(
                Map.Entry<String, Type>... entries) {
            Map<String, Type> m = new LinkedHashMap<>();
            for (var e : entries) m.put(e.getKey(), e.getValue());
            return m;
        }

        private static Map.Entry<String, Type> fn(String name,
                List<Type> params, Type ret) {
            return Map.entry(name, fnType(params, ret));
        }

        private static List<Type> list(Type... types) {
            return List.of(types);
        }

        // ---- relative file imports ----

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = testFileDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) return resolved;
            Path withExt = testFileDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) return withExt;
            Path withDeclExt = testFileDir.resolve(importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) return withDeclExt;
            return null;
        }

        private Map<String, Type> resolveFileModule(Path file,
                Set<String> modulesInProgress) throws ModuleNotFoundException {
            try {
                String source = Files.readString(file);
                String filename = file.toString();
                boolean isDecl = filename.endsWith(".d.deal");

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors())
                    throw new ModuleNotFoundException("Lex errors in " + filename);

                Parser parser = new Parser(lex.tokens(), filename);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors())
                    throw new ModuleNotFoundException("Parse errors in " + filename);

                ExportExtractor extractor = new ExportExtractor(filename, isDecl);
                return extractor.extract(parseResult.program());
            } catch (IOException e) {
                throw new ModuleNotFoundException("Cannot read: " + file);
            }
        }
    }
}
