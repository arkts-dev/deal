package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.lua.LuaBackend;
import deal.lexer.*;
import deal.module.ExportExtractor;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Spec-centric conformance test runner for DEAL v1.1.
 *
 * <p>Discovers all .deal files under test/conformance/, parses metadata
 * header comments, compiles and/or executes each test according to its
 * @expected tag, and produces a pass/fail report with spec coverage
 * summary.</p>
 */
public class ConformanceTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static final Map<String, List<TestResult>> specGroups = new LinkedHashMap<>();
    private static boolean luajitAvailable;
    private static boolean luajitSupportsDollar;

    // =========================================================================
    // Data types
    // =========================================================================

    private record TestFile(
        Path path,
        String relativePath,
        String spec,
        String description,
        String expected,
        String features
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

        if (luajitAvailable) {
            luajitSupportsDollar = checkLuajitSupportsDollar();
        }

        System.out.println("=== DEAL v1.1 Conformance Test Suite ===");
        System.out.println("Root: " + conformanceRoot);
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        if (luajitAvailable) {
            System.out.println("LuaJIT $ support: " +
                (luajitSupportsDollar ? "yes" : "no (@jsonable runtime tests will be skipped)"));
        }
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

    /**
     * Check whether LuaJIT supports {@code $} in identifiers.
     * Required for @jsonable tests which generate identifiers like
     * {@code C$fromJson}.  Older LuaJIT builds (pre-2020) reject {@code $}.
     */
    private static boolean checkLuajitSupportsDollar() {
        try {
            Path tmp = Files.createTempFile("deal_dollar_test_", ".lua");
            Files.writeString(tmp, "local a$b = 1; return a$b\n");
            ProcessBuilder pb = new ProcessBuilder("luajit", tmp.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Discovery
    // =========================================================================

    private static List<TestFile> discoverTests(Path root) throws IOException {
        List<TestFile> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  .sorted()
                  .forEach(p -> {
                      TestFile tf = parseMetadata(p, root);
                      if (tf != null) {
                          result.add(tf);
                      }
                  });
        }
        return result;
    }

    private static TestFile parseMetadata(Path file, Path root) {
        try {
            List<String> lines = Files.readAllLines(file);
            String spec = "";
            String description = "";
            String expected = "";
            String features = "";

            int linesToScan = Math.min(lines.size(), 20);
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
                }
            }

            if (expected.isEmpty()) {
                System.err.println("WARNING: " + file + " has no @expected tag, skipping");
                return null;
            }

            String relPath = root.relativize(file).toString();
            return new TestFile(file, relPath, spec, description, expected, features);
        } catch (IOException e) {
            System.err.println("WARNING: cannot read " + file + ": " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Test execution dispatch
    // =========================================================================

    private static void runTest(TestFile test) {
        System.out.print("  [" + test.relativePath() + "] ");
        String expected = test.expected();

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

        // Check if generated Lua uses $ identifiers (from @jsonable codegen)
        // and skip runtime test if LuaJIT doesn't support $ in identifiers.
        if (luaContainsDollarIdentifiers(lua) && !luajitSupportsDollar) {
            System.out.println("SKIP (LuaJIT does not support $ in identifiers; @jsonable runtime test skipped)");
            skipped++;
            addResult(test, false, "skipped: LuaJIT $ support required for @jsonable");
            return;
        }

        // Compile companion modules referenced in the generated Lua
        Map<String, String> companionLuas = compileCompanionModules(test.path());

        String output = executeLua(lua, false, companionLuas);
        if (output == null) {
            System.out.println("FAIL (Lua execution failed)");
            failed++;
            addResult(test, false, "Lua execution returned null");
        } else {
            System.out.println("OK");
            passed++;
            addResult(test, true, "runtime ok");
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

        // Check if generated Lua uses $ identifiers
        if (luaContainsDollarIdentifiers(lua) && !luajitSupportsDollar) {
            System.out.println("SKIP (LuaJIT does not support $ in identifiers; @jsonable runtime test skipped)");
            skipped++;
            addResult(test, false, "skipped: LuaJIT $ support required for @jsonable");
            return;
        }

        // Compile companion modules referenced in the generated Lua
        Map<String, String> companionLuas = compileCompanionModules(test.path());

        String output = executeLua(lua, true, companionLuas);
        if (output == null) {
            System.out.println("FAIL (Lua execution returned null)");
            failed++;
            addResult(test, false, "Lua execution returned null");
            return;
        }

        String needle = "DEAL_ERROR_CODE: " + expectedCode;
        if (output.contains(needle)) {
            System.out.println("OK (found " + needle + ")");
            passed++;
            addResult(test, true, "found " + expectedCode);
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

    /**
     * Compile a DEAL source file to Lua.  Used for companion modules.
     * Returns the Lua source string, or null on failure.
     */
    private static String generateLuaForFile(Path file) throws Exception {
        String source = Files.readString(file);
        String filename = file.toString();

        LexResult lex = new Lexer(source, filename).tokenize();
        if (lex.hasErrors()) return null;

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        if (parseResult.hasErrors()) return null;

        // Use a resolver rooted at the companion file's directory
        ConformanceModuleResolver resolver = new ConformanceModuleResolver(file);
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable = nr.resolve(parseResult.program());
        if (nr.diagnostics().stream().anyMatch(d -> "error".equals(d.severity())))
            return null;

        CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
        if (result.hasErrors()) return null;

        return LuaBackend.generate(parseResult.program(), result, filename);
    }

    /**
     * Check whether a Lua source string contains {@code $} used as an
     * identifier character (as opposed to inside a string literal).
     *
     * <p>Heuristic: {@code $} preceded by a letter, digit, or underscore
     * and followed by a letter or digit is treated as an identifier
     * character.  This matches the pattern used by @jsonable codegen
     * for names like {@code C$fromJson}.</p>
     */
    private static boolean luaContainsDollarIdentifiers(String lua) {
        // Match patterns like: local Foo$bar, Foo$fromJson, exports.Foo$bar
        // $ preceded by letter/digit and followed by letter
        return Pattern.compile("[a-zA-Z0-9_]\\$[a-zA-Z]").matcher(lua).find();
    }

    /**
     * Find and compile companion modules referenced by relative
     * {@code require} calls in the generated Lua.
     */
    private static Map<String, String> compileCompanionModules(Path testFile)
            throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Path testDir = testFile.toAbsolutePath().getParent();

        // Find require("./...") patterns in the generated Lua
        String mainLua = generateLua(testFile);
        if (mainLua == null) return result;

        // Match require("./xxx") or require("../xxx")
        Pattern requirePattern = Pattern.compile(
            "require\\(\"(\\.\\.?/[^\"]+)\"\\)");
        Matcher m = requirePattern.matcher(mainLua);

        while (m.find()) {
            String requirePath = m.group(1);
            // Try to find the corresponding .deal file
            Path resolved = testDir.resolve(requirePath + ".deal").normalize();
            if (!Files.exists(resolved)) {
                resolved = testDir.resolve(requirePath + ".d.deal").normalize();
            }
            if (Files.exists(resolved) && !resolved.equals(testFile.toAbsolutePath().normalize())) {
                // Skip if already compiled
                if (!result.containsKey(requirePath)) {
                    try {
                        String companionLua = generateLuaForFile(resolved);
                        if (companionLua != null &&
                            luaContainsDollarIdentifiers(companionLua) &&
                            !luajitSupportsDollar) {
                            // Companion requires $ support — skip but don't fail
                            continue;
                        }
                        if (companionLua != null) {
                            result.put(requirePath, companionLua);
                        }
                    } catch (Exception e) {
                        System.err.println("    Warning: failed to compile companion " +
                            resolved + ": " + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Lua execution
    // =========================================================================

    private static String executeLua(String luaSource, boolean isXpcallWrapped,
            Map<String, String> companionLuas) {
        // Build a runner that loads the module and invokes exported functions
        String runner;
        if (isXpcallWrapped) {
            runner = buildXpcallRunner(luaSource);
        } else {
            runner = buildRuntimeOkRunner(luaSource);
        }
        try {
            Path tmpDir = Files.createTempDirectory("deal_conf_");
            Path luaFile = tmpDir.resolve("test_main.lua");
            Files.writeString(luaFile, runner);

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

            // Write companion module Lua files
            for (var entry : companionLuas.entrySet()) {
                String modulePath = entry.getKey();  // e.g., "./jsonable_lib"
                String lua = entry.getValue();
                // require("./jsonable_lib") looks for ./jsonable_lib.lua
                Path companionFile = tmpDir.resolve(modulePath + ".lua");
                Files.createDirectories(companionFile.getParent());
                Files.writeString(companionFile, lua);
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

            if (isXpcallWrapped) {
                return output;
            } else {
                if (exit != 0) {
                    System.err.println("    LuaJIT exit " + exit + ": " + output);
                    return null;
                }
                return output;
            }
        } catch (Exception e) {
            System.err.println("    Lua execution exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a runner for runtime-ok tests: load the module and call all
     * exported zero-argument functions.  Compiler-generated functions
     * (those with {@code $} in their name) are skipped because they
     * typically require arguments (e.g., {@code C$fromJson(s: string)}).
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
            "      -- Skip compiler-generated functions ($ in name)\n" +
            "      -- as they typically require arguments\n" +
            "      if not string.find(__k, \"$\", 1, true) then\n" +
            "        __v.f()\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end\n";
    }

    /**
     * Build an xpcall-wrapped runner for runtime-error tests (D7).
     * Compiler-generated functions (those with {@code $} in name) are
     * skipped because they typically require arguments.
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
            "        -- Skip compiler-generated functions ($ in name)\n" +
            "        if not string.find(__k, \"$\", 1, true) then\n" +
            "          __v.f()\n" +
            "        end\n" +
            "      end\n" +
            "    end\n" +
            "  end\n" +
            "end, function(err)\n" +
            "  if type(err) == 'table' and err.code ~= nil then\n" +
            "    print('DEAL_ERROR_CODE: ' .. err.code)\n" +
            "  else\n" +
            "    print('DEAL_ERROR_CODE: ' .. tostring(err))\n" +
            "  end\n" +
            "end)\n" +
            "if not __ok then os.exit(1) end\n";
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
            "Async/Await",
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
    }

    // =========================================================================
    // Module resolver for conformance tests
    // =========================================================================

    /**
     * A module resolver that resolves stdlib modules by parsing the actual
     * .d.deal files via {@link StdlibModuleResolver}, and resolves relative
     * file imports using {@link ExportExtractor}.
     *
     * <p>Stdlib exports are derived from the 6 spec-listed .d.deal files
     * — there is no hardcoded export map. Non-spec modules (std/io,
     * std/coroutine) are not resolved.
     */
    private static class ConformanceModuleResolver implements ModuleResolver {

        private final Path testFileDir;
        private final Map<String, Map<String, Type>> stdlibExports;

        ConformanceModuleResolver(Path testFile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.stdlibExports = StdlibModuleResolver.stdlibExports();
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            // Check spec-listed stdlib modules
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }

            // Reject non-spec stdlib paths (std/io, std/coroutine)
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleNotFoundException(
                    "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
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
