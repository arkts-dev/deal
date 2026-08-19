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

    /**
     * Host fixture root (host-module-abi D6): declarations
     * ({@code <name>.d.deal}) plus raw Lua implementations
     * ({@code <name>.lua}) for bare {@code host/<name>} imports.
     * Derived from the conformance root argument in {@code main}.
     */
    private static Path hostFixturesRoot =
        Path.of("test/conformance/host-fixtures");

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

    /**
     * A companion module that has been compiled to Lua and is ready to be
     * written to the temp directory for runtime resolution.
     */
    private record CompanionModule(String luaSource, String moduleName) {}

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        String conformanceRoot = "test/conformance/";
        if (args.length > 0) {
            conformanceRoot = args[0];
            hostFixturesRoot = Path.of(conformanceRoot).resolve("host-fixtures");
        }

        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        System.out.println("=== DEAL v1.1 Conformance Test Suite ===");
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
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  // Host fixture declarations (host-fixtures/*.d.deal)
                  // carry no @expected header and are resolved on demand
                  // by the host-fixture registry (host-module-abi D6) —
                  // the discovery walk skips the subtree entirely.
                  .filter(p -> {
                      Path rel = root.relativize(p);
                      return rel.getNameCount() == 0
                          || !"host-fixtures".equals(
                              rel.getName(0).toString());
                  })
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
        List<Diagnostic> diags = compileAndGetDiagnostics(test, null);
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

        CompanionCatalog catalog = new CompanionCatalog();
        List<Diagnostic> diags = compileAndGetDiagnostics(test, catalog);
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

        // Generate Lua for the main test file and any companion modules it imports
        var generated = generateLuaWithCompanions(test.path(), catalog);
        if (generated == null) {
            System.out.println("FAIL (codegen failed)");
            failed++;
            addResult(test, false, "codegen failed");
            return;
        }

        String output = executeLua(generated.mainLua(), generated.companionModules(), false);
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
        List<Diagnostic> diags = compileAndGetDiagnostics(test, null);
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

        CompanionCatalog catalog = new CompanionCatalog();
        List<Diagnostic> diags = compileAndGetDiagnostics(test, catalog);
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

        // Generate Lua for the main test file and any companion modules it imports
        var generated = generateLuaWithCompanions(test.path(), catalog);
        if (generated == null) {
            System.out.println("FAIL (codegen failed)");
            failed++;
            addResult(test, false, "codegen failed");
            return;
        }

        String output = executeLua(generated.mainLua(), generated.companionModules(), true);
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

    @SuppressWarnings("deprecation")
    private static List<Diagnostic> compileAndGetDiagnostics(TestFile test,
            CompanionCatalog catalog) throws Exception {
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

        ConformanceModuleResolver resolver =
            new ConformanceModuleResolver(test.path(), catalog);
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

    // =========================================================================
    // GeneratedLua and companion module support
    // =========================================================================

    /**
     * Result of generating Lua for a test file and its companion modules.
     */
    private record GeneratedLua(String mainLua, Map<String, CompanionModule> companionModules) {}

    /**
     * Generate Lua for a test file and any companion modules it imports.
     * <p>
     * Companion modules are .deal files reachable from the test file through
     * relative ({@code ./} / {@code ../}) imports. The {@link CompanionCatalog}
     * compiles each companion transitively (depth-first, with a cycle guard)
     * and this method collects the full transitive closure so the Lua
     * {@code require} system can resolve every module at runtime.
     * <p>
     * A companion that fails to compile yields no entry (as before), which
     * surfaces as a runtime require failure in the test — the unchanged
     * failure mode.
     */
    private static GeneratedLua generateLuaWithCompanions(Path file,
            CompanionCatalog catalog) throws Exception {
        // The main test file is the v1.2 entry module of its own invocation:
        // it must export a non-async main(): null and the backend invokes
        // main() from the generated chunk.
        CompanionCatalog.Artifact mainArtifact =
            catalog.entryArtifactFor(file.toAbsolutePath().normalize());
        if (mainArtifact == null || mainArtifact.luaSource() == null) {
            return null;
        }
        boolean backendErrors = mainArtifact.backendDiagnostics().stream()
            .anyMatch(d -> "error".equals(d.severity()));
        if (backendErrors) {
            for (Diagnostic d : mainArtifact.backendDiagnostics()) {
                if ("error".equals(d.severity())) {
                    System.out.println("    " + d);
                }
            }
            return null;
        }

        Map<String, CompanionModule> companionModules = new LinkedHashMap<>();
        Set<Path> seen = new HashSet<>();
        collectCompanionModules(mainArtifact, catalog, companionModules, seen);
        return new GeneratedLua(mainArtifact.luaSource(), companionModules);
    }

    /**
     * Collects every transitively reachable companion module (depth-first)
     * from an artifact's direct companion dependencies. Each companion is
     * written once, keyed by its stem module name in the flat temp-dir
     * namespace.
     */
    private static void collectCompanionModules(CompanionCatalog.Artifact artifact,
            CompanionCatalog catalog,
            Map<String, CompanionModule> companionModules,
            Set<Path> seen) {
        for (Path dep : artifact.companionDependencies()) {
            if (!seen.add(dep.toAbsolutePath().normalize())) continue;
            CompanionCatalog.Artifact depArtifact =
                catalog.artifactFor(dep.toAbsolutePath().normalize());
            if (depArtifact == null || depArtifact.luaSource() == null) {
                continue; // compile failure → runtime require failure (unchanged)
            }
            String moduleName = moduleNameFor(dep);
            companionModules.put(moduleName,
                new CompanionModule(depArtifact.luaSource(), moduleName));
            collectCompanionModules(depArtifact, catalog, companionModules, seen);
        }
    }

    /**
     * Resolve a relative import path to a .deal file on disk.
     *
     * @param importPath the raw import path from the ImportDeclaration
     * @param baseDir    the directory containing the importing file
     * @return the resolved path, or {@code null} if not found
     */
    private static Path resolveCompanionPath(String importPath, Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) return resolved;
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) return withExt;
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) return withDeclExt;
        return null;
    }

    /**
     * Derive a Lua module name from a .deal file path.
     * Uses the filename stem (without extension) as the module name.
     */
    private static String moduleNameFor(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        } else if (name.endsWith(".deal")) {
            return name.substring(0, name.length() - ".deal".length());
        }
        return name;
    }



    // =========================================================================
    // Lua execution
    // =========================================================================

    /**
     * Execute Lua source with optional companion modules.
     *
     * @param luaSource        the generated Lua for the main test module
     * @param companionModules companion module name → Lua source pairs to write
     *                         as .lua files in the temp directory
     * @param isXpcallWrapped  true for runtime-error tests, false for runtime-ok
     * @return stdout output, or {@code null} on failure
     */
    private static String executeLua(String luaSource,
            Map<String, CompanionModule> companionModules,
            boolean isXpcallWrapped) {
        // ABI invariant (lua-abi-emission-layer): generated Lua contains '$'
        // only inside quoted string keys — the frozen export-key surface
        // (exports["C$fromJson"]) and META keys under the bare class name.
        if (!dollarOnlyInQuotedKeys(luaSource)) {
            System.err.println("    Generated Lua contains $ outside a quoted string key");
            return null;
        }
        for (var entry : companionModules.entrySet()) {
            if (!dollarOnlyInQuotedKeys(entry.getValue().luaSource())) {
                System.err.println("    Companion " + entry.getKey()
                    + " contains $ outside a quoted string key");
                return null;
            }
        }
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

            // Write companion .lua files so that `require` can find them
            for (var entry : companionModules.entrySet()) {
                Path companionFile = tmpDir.resolve(entry.getKey() + ".lua");
                Files.writeString(companionFile, entry.getValue().luaSource());
            }

            // Copy host fixture implementations (<name>.lua →
            // <tmp>/host/<name>.lua) so bare host imports resolve through
            // the raw slash-form require under the runner's package.path
            // ("./?.lua" → "./host/<name>.lua"), host-module-abi D6. Host
            // .lua files are never inspected by the $-gate — it applies
            // to generated Lua only.
            if (Files.isDirectory(hostFixturesRoot)) {
                Path targetHostDir = tmpDir.resolve("host");
                Files.createDirectories(targetHostDir);
                try (var stream = Files.list(hostFixturesRoot)) {
                    stream.filter(p -> p.toString().endsWith(".lua"))
                          .forEach(p -> {
                              try {
                                  Files.copy(p,
                                      targetHostDir.resolve(p.getFileName()));
                              } catch (IOException ignored) {}
                          });
                }
            }

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
     * Returns true iff every '$' in the generated Lua sits inside a quoted
     * string key (between the enclosing double quotes).
     */
    private static boolean dollarOnlyInQuotedKeys(String lua) {
        for (int i = lua.indexOf('$'); i >= 0; i = lua.indexOf('$', i + 1)) {
            int open = lua.lastIndexOf('"', i);
            int close = lua.indexOf('"', i + 1);
            if (open < 0 || close < 0 || open >= i || i >= close) {
                return false;
            }
        }
        return true;
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
            "        -- The backend already invoked main() at chunk end\n" +
            "        -- (v1.2 entry contract): never invoke it twice.\n" +
            "        if __k ~= \"main\" then\n" +
            "          __v.f()\n" +
            "        end\n" +
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
            "          -- The backend already invoked main() at chunk end\n" +
            "          -- (v1.2 entry contract): never invoke it twice.\n" +
            "          if __k ~= \"main\" then\n" +
            "            __v.f()\n" +
            "          end\n" +
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
     * Compile-and-cache service for companion {@code .deal} files, shared by
     * code generation ({@code generateLuaWithCompanions}) and type resolution
     * ({@code ConformanceModuleResolver.resolveClassSymbol} /
     * {@code resolveTypeNodeInModule}).
     *
     * <p>Per-file artifacts hold the generated Lua source, the import
     * resolutions (import path → stem module name), the compiled symbol
     * table and name resolver, and the set of direct companion
     * dependencies. Companions are compiled transitively, depth-first,
     * with a per-file cache keyed by absolute path and an in-progress set
     * that guards cycles (an in-progress file is skipped — its module is
     * already being emitted once).</p>
     *
     * <p>Module names are file stems in one flat temp-dir namespace; a
     * companion closure must not contain two same-stem files (the gap-02
     * fixture set satisfies this).</p>
     */
    private static final class CompanionCatalog {

        /** Compilation artifact for a single .deal file. */
        record Artifact(
            String luaSource,
            Map<String, String> importResolutions,
            SymbolTable symbolTable,
            NameResolver nameResolver,
            Set<Path> companionDependencies,
            List<Diagnostic> backendDiagnostics
        ) {}

        private final Map<Path, Artifact> cache = new LinkedHashMap<>();
        private final Map<Path, Artifact> entryCache = new LinkedHashMap<>();
        private final Set<Path> inProgress = new HashSet<>();
        /** Shared host-fixture registry (host-module-abi D6). */
        private final HostRegistry hostRegistry = new HostRegistry();

        /**
         * Returns the compiled artifact for a file, compiling it (and all
         * of its transitive companion dependencies) on first use.
         * Returns {@code null} when the file cannot be read, fails any
         * compilation stage, or is currently in progress (cycle) — the
         * same tolerant contract production resolution has.
         */
        Artifact artifactFor(Path file) {
            return artifactFor(file, false);
        }

        /**
         * Compiles the main test file as the v1.2 entry module (the
         * backend validates the exported non-async main(): null and emits
         * the main() invocation). The entry artifact is cached separately
         * from companion artifacts — the same file never mixes both roles
         * in one test run.
         */
        Artifact entryArtifactFor(Path file) {
            return artifactFor(file, true);
        }

        private Artifact artifactFor(Path file, boolean isEntry) {
            Path key = file.toAbsolutePath().normalize();
            Map<Path, Artifact> targetCache = isEntry ? entryCache : cache;
            Artifact cached = targetCache.get(key);
            if (cached != null) return cached;
            if (inProgress.contains(key)) return null;

            inProgress.add(key);
            try {
                Artifact artifact = compile(key, isEntry);
                if (artifact != null) {
                    targetCache.put(key, artifact);
                }
                return artifact;
            } finally {
                inProgress.remove(key);
            }
        }

        private Artifact compile(Path file, boolean isEntry) {
            try {
                String source = Files.readString(file);
                String filename = file.toString();

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors()) return null;

                Parser parser = new Parser(lex.tokens(), filename);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return null;

                // Discover this file's own companion imports. Stdlib paths
                // (non-./ and non-../) are not companions: resolveCompanionPath
                // returns null and they pass through raw as slash-separated
                // require names.
                Path fileDir = file.toAbsolutePath().normalize().getParent();
                Map<String, String> importResolutions = new LinkedHashMap<>();
                Set<Path> companionDependencies = new LinkedHashSet<>();
                Map<String, Map<String, Type>> hostModules = new LinkedHashMap<>();

                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ImportDeclaration imp) {
                        String importPath = imp.modulePath();
                        if (hostRegistry.isHostModule(importPath)) {
                            // Host fixture import (host/<name>): the
                            // declared exports drive the emitted
                            // __rt.load_host loader with the raw
                            // slash-form specifier verbatim; the import
                            // is never a companion and never enters
                            // importResolutions (host-module-abi D6).
                            try {
                                hostModules.put(importPath, hostRegistry
                                    .forModule(importPath).exports());
                            } catch (ModuleResolver.ModuleNotFoundException e) {
                                return null;
                            }
                            continue;
                        }
                        Path resolvedPath = resolveCompanionPath(importPath, fileDir);
                        if (resolvedPath != null) {
                            importResolutions.put(importPath,
                                moduleNameFor(resolvedPath));
                            companionDependencies.add(
                                resolvedPath.toAbsolutePath().normalize());
                        }
                    }
                }

                // Compile each companion dependency transitively (depth-first).
                for (Path dep : new ArrayList<>(companionDependencies)) {
                    artifactFor(dep);
                }

                // Name resolution rooted at this file.
                ConformanceModuleResolver resolver =
                    new ConformanceModuleResolver(file, this);
                NameResolver nr = new NameResolver(filename, resolver);
                SymbolTable symTable = nr.resolve(parseResult.program());
                if (nr.diagnostics().stream().anyMatch(
                        d -> "error".equals(d.severity()))) {
                    return null;
                }

                CheckResult result = TypeChecker.check(
                    filename, symTable, nr, parseResult.program());
                if (result.hasErrors()) return null;

                LuaBackend backend = new LuaBackend(
                    result.typeMap(), result.symbolTable(), filename);
                String luaSource = backend.generateFromInstance(
                    parseResult.program(), isEntry, importResolutions,
                    hostModules);
                return new Artifact(luaSource,
                    Collections.unmodifiableMap(new LinkedHashMap<>(importResolutions)),
                    symTable, nr,
                    Collections.unmodifiableSet(new LinkedHashSet<>(companionDependencies)),
                    backend.diagnostics());
            } catch (Exception e) {
                return null; // mirrors today's null degradation paths
            }
        }
    }

    /**
     * Shared registry of host fixture declarations (host-module-abi D6).
     *
     * <p>A bare import {@code host/<name>} resolves from
     * {@code host-fixtures/<name>.d.deal}; the typing/class-identity module
     * path is the dotted form {@code host.<name>} (class descriptors
     * {@code @host.presence/Config}), while the require path stays the raw
     * slash-form specifier verbatim. Every class declaration in the
     * fixture program (including {@code ExportDeclaration}-wrapped ones)
     * becomes a synthesized {@link Symbol.ClassSymbol} carrying name,
     * fields, and the dotted module path.</p>
     */
    private static final class HostRegistry {

        /** Declared exports plus the synthesized class symbols of one fixture. */
        record HostDeclaration(
            Map<String, Type> exports,
            Map<String, Symbol.ClassSymbol> classSymbols
        ) {}

        private final Map<String, HostDeclaration> cache = new LinkedHashMap<>();

        /**
         * True when {@code modulePath} names a registered host fixture —
         * the raw slash-form specifier ({@code host/<name>}) or its dotted
         * typing form ({@code host.<name>}) — and the fixture declaration
         * file exists on disk.
         */
        boolean isHostModule(String modulePath) {
            if (modulePath == null || modulePath.isEmpty()) return false;
            Path decl = declarationFileFor(toRawPath(modulePath));
            return decl != null && Files.exists(decl);
        }

        /**
         * Returns the declaration for a host fixture, parsing and caching
         * it on first use.
         *
         * @throws ModuleResolver.ModuleNotFoundException when the fixture
         *         declaration is missing or fails to lex/parse
         */
        HostDeclaration forModule(String modulePath)
                throws ModuleResolver.ModuleNotFoundException {
            String raw = toRawPath(modulePath);
            HostDeclaration cached = cache.get(raw);
            if (cached != null) return cached;

            Path decl = declarationFileFor(raw);
            if (decl == null || !Files.exists(decl)) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Module not found: " + modulePath);
            }
            try {
                String source = Files.readString(decl);
                String filename = decl.toString();

                LexResult lex = new Lexer(source, filename).tokenize();
                if (lex.hasErrors())
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Lex errors in " + filename);

                Parser parser = new Parser(lex.tokens(), filename);
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors())
                    throw new ModuleResolver.ModuleNotFoundException(
                        "Parse errors in " + filename);

                // The typing/class-identity module path is the dotted form
                // (host-module-abi D6): class types and descriptors carry
                // it; the require path stays the raw slash-form specifier.
                String dotted = raw.replace('/', '.');
                ExportExtractor extractor = new ExportExtractor(dotted, true);
                Map<String, Type> exports =
                    extractor.extract(parseResult.program());

                // Class-symbol synthesis: every ClassDeclaration in the
                // fixture program (incl. ExportDeclaration-wrapped ones)
                // becomes a ClassSymbol carrying the dotted module path.
                Map<String, Symbol.ClassSymbol> classSymbols = new LinkedHashMap<>();
                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        classSymbols.put(cd.name(),
                            new Symbol.ClassSymbol(cd.name(), cd.fields(), dotted));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration() instanceof ClassDeclaration cd) {
                        classSymbols.put(cd.name(),
                            new Symbol.ClassSymbol(cd.name(), cd.fields(), dotted));
                    }
                }

                HostDeclaration result = new HostDeclaration(
                    Collections.unmodifiableMap(new LinkedHashMap<>(exports)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(classSymbols)));
                cache.put(raw, result);
                return result;
            } catch (IOException e) {
                throw new ModuleResolver.ModuleNotFoundException(
                    "Cannot read: " + decl);
            }
        }

        /**
         * Normalizes a host module path to the raw slash-form specifier:
         * {@code host.presence} → {@code host/presence}; an already raw
         * {@code host/presence} is unchanged.
         */
        private String toRawPath(String modulePath) {
            return modulePath.replace('.', '/');
        }

        /**
         * Maps a raw {@code host/<name>} specifier to its fixture
         * declaration file, or {@code null} when the specifier is not a
         * single-segment host path.
         */
        private Path declarationFileFor(String rawPath) {
            String prefix = "host/";
            if (!rawPath.startsWith(prefix) || rawPath.length() == prefix.length()) {
                return null;
            }
            String name = rawPath.substring(prefix.length());
            if (name.contains("/") || name.contains("\\")) {
                return null;
            }
            return hostFixturesRoot.resolve(name + ".d.deal");
        }
    }

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
        private final CompanionCatalog catalog;
        private final Map<String, Map<String, Type>> stdlibExports;
        private final HostRegistry hostRegistry;

        ConformanceModuleResolver(Path testFile, CompanionCatalog catalog) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.catalog = catalog;
            this.stdlibExports = StdlibModuleResolver.stdlibExports();
            this.hostRegistry = catalog != null
                ? catalog.hostRegistry
                : new HostRegistry();
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

            // Host-fixture registry (host-module-abi D6): a bare
            // host/<name> import resolves from
            // host-fixtures/<name>.d.deal; the typing/class-identity
            // module path is the dotted host.<name> form, while the
            // require path stays the raw slash-form specifier. The
            // dotted form also reaches here (isFunctionExportedFromModule
            // passes a class's module path).
            if (hostRegistry.isHostModule(modulePath)) {
                return hostRegistry.forModule(modulePath).exports();
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
            // Host-fixture branch (host-module-abi D6): classes of a
            // host.<name> module resolve to the ClassSymbols synthesized
            // from the fixture declaration; an absent class returns null,
            // producing the usual E3004/E4002 diagnostics.
            if (hostRegistry.isHostModule(modulePath)) {
                HostRegistry.HostDeclaration decl =
                    hostRegistry.forModule(modulePath);
                return decl.classSymbols().get(className);
            }
            // Local classes (absent modulePath) resolve through the local
            // scope in NameResolver; only foreign module paths reach here.
            if (catalog == null || modulePath == null || modulePath.isEmpty()) {
                return null;
            }
            CompanionCatalog.Artifact artifact = catalog.artifactFor(
                Path.of(modulePath).toAbsolutePath().normalize());
            if (artifact == null || artifact.symbolTable() == null) {
                return null; // file missing / compile failure / in-progress cycle
            }
            Symbol sym = artifact.symbolTable().resolve(className);
            if (sym instanceof Symbol.ClassSymbol cs) return cs;
            return null;
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            // Host-fixture branch (host-module-abi D6): field annotations
            // of a host.<name> class resolve against the fixture's own
            // class registry (NamedType/ArrayType/NullableType shapes);
            // any other shape degrades through the local fallback below.
            if (hostRegistry.isHostModule(modulePath)) {
                HostRegistry.HostDeclaration decl =
                    hostRegistry.forModule(modulePath);
                return resolveHostTypeNode(typeNode, modulePath, decl);
            }
            if (catalog == null || modulePath == null || modulePath.isEmpty()) {
                return null;
            }
            CompanionCatalog.Artifact artifact = catalog.artifactFor(
                Path.of(modulePath).toAbsolutePath().normalize());
            if (artifact == null || artifact.nameResolver() == null) {
                return null;
            }
            return artifact.nameResolver().resolveTypeNode(typeNode);
        }

        /**
         * Resolves a field type annotation of a host-fixture class against
         * the fixture's own class registry (host-module-abi D6): NamedType
         * nodes naming registered host classes resolve to
         * {@code Types.classType(name, modulePath)}, and ArrayType/
         * NullableType wrappers are rebuilt around resolved inners. Any
         * other shape (primitives, qualified types, function types)
         * returns null so the caller falls back to its local resolution.
         */
        private Type resolveHostTypeNode(TypeNode typeNode, String modulePath,
                HostRegistry.HostDeclaration decl) {
            if (typeNode instanceof NamedType nt) {
                if (decl.classSymbols().containsKey(nt.name())) {
                    return Types.classType(nt.name(), modulePath);
                }
                return null;
            }
            if (typeNode instanceof deal.ast.ArrayType at) {
                Type elem = resolveHostTypeNode(at.elementType(), modulePath, decl);
                return elem == null ? null : Types.array(elem);
            }
            if (typeNode instanceof deal.ast.NullableType nullable) {
                Type inner = resolveHostTypeNode(
                    nullable.innerType(), modulePath, decl);
                if (inner == null) return null;
                try {
                    return Types.nullable(inner);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            return null;
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
