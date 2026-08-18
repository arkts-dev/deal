package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.lua.LuaBackend;
import deal.module.CompilationOrchestrator;
import deal.ir.IrDumper;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loads backend-neutral JSON fixture tests from
 * {@code test/conformance/fixtures/} and executes them against the
 * LuaJIT backend and (ISSUE-0091, ISSUE-0092, ISSUE-0093, ISSUE-0094,
 * ISSUE-0096, ISSUE-0097) the JVM backend. The
 * ISSUE-0092 semantic-slice fixtures live in
 * {@code test/conformance/fixtures/jvm-semantic-slice.json} (while loops,
 * template literals, and the surrounding primitive surface — JVM-only,
 * each runtime fixture compiled with {@code javac} and executed with
 * {@code java} against the emitted artifact), the ISSUE-0093
 * functions/direct-calls fixtures live in
 * {@code test/conformance/fixtures/jvm-functions-slice.json} (direct
 * calls, multiple parameters, return values, nested calls, direct
 * self-recursion, argument evaluation order with side effects,
 * parameter shadowing — including the triple-deep shadow chains
 * field → parameter → body-top let → inner-block let, with and
 * without the module field, where every binding gets a distinct
 * emitted Java name — and two frontend arity/type compile-error gates
 * rejected before any backend), the ISSUE-0094 primitive-array
 * fixtures live in
 * {@code test/conformance/fixtures/jvm-arrays-slice.json} (int[]/number[]/
 * string[]/boolean[] literals, index reads, element writes — in-place
 * and the {@code i === length} append — {@code .length} reads, runtime
 * bounds checks (negative read → E8002, read past the end → E8001 at
 * typed read sites, boundary-less positions computing Lua's nil
 * semantics instead — discarded standalone reads, {@code ===}/
 * {@code !==} operands, {@code !} operands, and {@code &&}/{@code ||}
 * operands, with the result nil failing a typed boolean boundary with
 * E8001 on both backends — negative/gap writes → E8002,
 * out-of-safe-range int element writes → E8004), spec §Operational
 * semantics evaluation order for reads, writes, and array literals
 * including hoisted side effects and side-effecting receivers/first
 * elements, the both-reads comparison evaluation position (the left
 * read's E8002 raises before any right-operand hoisted side effect,
 * pinned with {@code expectedNotOutput} negative stdout assertions),
 * cross-backend parity against LuaJIT, and frontend E3007/E3017
 * compile-error gates rejected before any backend), and the ISSUE-0096
 * multi-module fixtures live in
 * {@code test/conformance/fixtures/jvm-modules-slice.json}
 * (namespace imports/exports and imported direct calls across compiled
 * project modules — see the multi-module section below), and the
 * ISSUE-0097 stdlib-boundary fixtures live in
 * {@code test/conformance/fixtures/jvm-stdlib-slice.json}
 * (the stdlib modules whose declared functions use only the slice's
 * prerequisite value types — std/console output, std/string
 * UTF-8 byte-wise length/substring/split plus the plain-text
 * search/replace/trim helpers, std/math floor/ceil/sqrt/absInt/
 * absNumber/minInt/maxInt with the sqrt-negative E8001 runtime error,
 * std/time's second-truncated nowMillis, stdlib results composing
 * across modules, a frontend E5001 compile-error gate, and a
 * multi-module fixture consuming std/string through the orchestrator
 * pipeline).
 *
 * <h2>Multi-module fixtures (ISSUE-0096)</h2>
 *
 * <p>A fixture may replace {@code source} with a {@code modules} object
 * mapping relative {@code .deal} source paths to DEAL source strings, plus
 * an {@code entry} field naming the entry module:
 * <pre>
 * {
 *   "name": "jvm-mod-imported-direct-call",
 *   "modules": {
 *     "lib.deal": "export function add(a: int, b: int): int { return a + b; }",
 *     "main.deal": "import * as lib from \"./lib\"\nexport function run(): int { return lib.add(2, 3); }"
 *   },
 *   "entry": "main.deal",
 *   "expectedOutput": "5",
 *   "backends": ["jvm"]
 * }
 * </pre>
 * Multi-module fixtures are JVM-only and run the REAL whole-project
 * pipeline: {@link CompilationOrchestrator} with {@link Backend#JVM} —
 * module discovery (import resolution over the temp project root),
 * signature extraction, dependency ordering, name resolution, type
 * checking, and JvmBackend codegen per module into an output directory —
 * followed by {@code javac} over every emitted {@code .java} artifact plus
 * a runner class and {@code java} execution of the emitted artifacts
 * (the entry module's class derives from its module path; the runner
 * auto-invokes the entry's zero-arity exports exactly like the
 * single-module adapter). A bypassed parser/checker/module-discovery
 * yields no compile and the fixture fails; a bypassed codegen leaves no
 * {@code .java} artifact (asserted); a bypassed JVM execution produces no
 * output. {@code expectedCompileError} fixtures run the same orchestrator
 * pipeline, assert the named frontend error, assert no E6xxx backend code
 * appears, and assert no {@code .java} artifact was written — the gate
 * stops before codegen. {@code irContains}/{@code irNotContains} are
 * checked against the concatenation of every module's IR dump (the
 * orchestrator runs with {@code --dump-ir}).
 *
 * <p>Fixture format (per {@code conformance-test-architecture} D3, extended
 * by ISSUE-0091 with {@code expectedCompileError} and by ISSUE-0094 with
 * {@code expectedNotOutput} — negative stdout assertions that pin, e.g.,
 * "the error raises before any later operand's side effect prints"):
 * <pre>
 * {
 *   "version": "1.0",
 *   "tests": [{
 *     "name": "unique-test-name",
 *     "description": "what this test verifies",
 *     "source": "DEAL source code as string",
 *     "expectedOutput": "string that stdout must contain" | null,
 *     "expectedNotOutput": ["substrings stdout must NOT contain"],
 *     "expectedError": "E8001" | null,
 *     "expectedExitCode": 0 | 1,
 *     "expectedCompileError": "E3001" | null,
 *     "irContains": ["substrings that the IR dump must contain"],
 *     "irNotContains": ["substrings that the IR dump must NOT contain"],
 *     "backends": ["luajit", "jvm"]
 *   }]
 * }
 * </pre>
 *
 * <p>For IR-only tests, the runtime fields are {@code null} and the
 * runner only validates the IR dump assertions ({@code irContains}
 * and {@code irNotContains}).
 *
 * <h2>Frontend compile-error fixtures (ISSUE-0091)</h2>
 *
 * <p>When {@code expectedCompileError} names an error code (e.g.
 * {@code "E3001"}), the fixture is a frontend gate: the runner runs the
 * real lexer → parser → name resolver → type checker, asserts the named
 * error is produced by that pipeline (and that no backend-lowering E6xxx
 * code is), and then stops — the fixture is rejected <em>before</em> any
 * backend (codegen, {@code javac}, {@code java}) is invoked. A bypassed
 * parser or checker produces no such diagnostic and the fixture fails.
 *
 * <p>Schema validation (per {@code conformance-test-architecture} D6 —
 * invalid configurations must fail with a clear message, never pass
 * silently): a fixture that combines {@code expectedCompileError} with
 * any runtime assertion ({@code expectedOutput}/
 * {@code expectedNotOutput}/{@code expectedError}/
 * {@code expectedExitCode}) or IR assertion ({@code irContains}/
 * {@code irNotContains}) is rejected up front, because the compile-error
 * gate returns before runtime/IR dispatch and would silently drop those
 * assertions (see {@link #fixtureConfigViolation}).
 *
 * <h2>JVM adapter (ISSUE-0091)</h2>
 *
 * <p>For fixtures listing {@code "jvm"} in {@code backends}, the runner:
 * <ol>
 *   <li>generates Java source with the real {@link JvmBackend} (a bypassed
 *       codegen produces no artifact);</li>
 *   <li>compiles the module class together with a small runner class with
 *       {@code javac} in a subprocess (a missing {@code .class} fails the
 *       fixture);</li>
 *   <li>executes the artifact with {@code java} in a subprocess; the runner
 *       auto-invokes the zero-arity exported functions in declaration order
 *       and prints non-null results to stdout, so {@code expectedOutput}
 *       observes real return values;</li>
 *   <li>asserts {@code expectedOutput}/{@code expectedError}/
 *       {@code expectedExitCode} against the captured stdout and exit code,
 *       with the same {@code DEAL_ERROR_CODE: <code>} error convention as
 *       the LuaJIT runner.</li>
 * </ol>
 *
 * <p>Auto-invocation asymmetry (fixture-authoring contract): the Lua
 * harness iterates the exported functions with {@code pairs()} (an
 * unspecified hash order) and <em>discards</em> their results, while the
 * JVM runner invokes them in declaration order and <em>prints</em>
 * non-null results with Java's default formatting
 * ({@code System.out.println(double)} prints {@code 1.0}/{@code 1.0E300},
 * not LuaJIT's {@code tostring} {@code 1}/{@code 1e+300}). Therefore:
 * fixtures with multiple zero-arity exports must not depend on
 * invocation order across backends, result-value assertions
 * ({@code expectedOutput} equal to an export's printed return value) are
 * JVM-only, and cross-backend fixtures must route every observable
 * output through {@code std/console}. Every current fixture has at most
 * one zero-arity export, and every cross-backend observable output goes
 * through {@code console.log}/{@code console.error}.
 */
public class BackendConformanceTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static boolean luajitAvailable;
    private static boolean jvmAvailable;

    // Sentinel for JSON null (distinct from Java null)
    private static final Object JSON_NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    /** Holds the result of executing a Lua program. */
    private record ExecutionResult(String output, int exitCode) {}

    /** Result of the backend-neutral frontend: real parser/checker output. */
    private record FrontendCompile(ProgramNode program, CheckResult checkResult,
                                   SymbolTable symbolTable,
                                   List<Diagnostic> errors) {
        boolean hasErrors() { return !errors.isEmpty(); }
    }

    public static void main(String[] args) throws Exception {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            luajitAvailable = true;
        } catch (IOException e) {
            luajitAvailable = false;
        }

        jvmAvailable = probeJvm();

        System.out.println("=== Backend Conformance Test ===");
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        System.out.println("JVM (javac + java): " + (jvmAvailable ? "available" :
            "NOT available (JVM runtime tests will be skipped)"));
        System.out.println();

        Path fixturesDir = Path.of("test/conformance/fixtures/");
        if (!Files.isDirectory(fixturesDir)) {
            System.out.println("No fixtures directory found at " + fixturesDir);
            System.exit(failed > 0 ? 1 : 0);
        }

        try (var stream = Files.list(fixturesDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .sorted()
                  .forEach(BackendConformanceTest::runFixtureFile);
        }

        System.out.println();
        System.out.println("=== Backend Conformance Summary ===");
        int total = passed + failed + skipped;
        System.out.println("Total: " + total + ", Passed: " + passed +
            ", Failed: " + failed + ", Skipped: " + skipped);

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * Probes that both {@code javac} and {@code java} are invocable and
     * functional: a broken-but-present toolchain (e.g. a wrapper script that
     * exits non-zero) must make the JVM runtime fixtures skip, not fail.
     */
    private static boolean probeJvm() {
        try {
            Process javac = new ProcessBuilder("javac", "-version")
                .redirectErrorStream(true).start();
            if (javac.waitFor() != 0) return false;
            Process java = new ProcessBuilder("java", "-version")
                .redirectErrorStream(true).start();
            return java.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // =========================================================================
    // Fixture file runner
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static void runFixtureFile(Path file) {
        System.out.println("--- Fixture: " + file.getFileName() + " ---");

        try {
            String raw = Files.readString(file);
            Map<String, Object> root = (Map<String, Object>) parseJson(raw);

            Object version = root.get("version");
            if (!"1.0".equals(String.valueOf(version))) {
                System.out.println("  FAIL: unsupported version: " + version);
                failed++;
                return;
            }

            List<Map<String, Object>> tests =
                (List<Map<String, Object>>) root.get("tests");
            if (tests == null) {
                System.out.println("  FAIL: no 'tests' array in fixture");
                failed++;
                return;
            }

            for (Map<String, Object> test : tests) {
                runTestCase(file.getFileName().toString(), test);
            }
        } catch (Exception e) {
            System.out.println("  FAIL: error processing fixture: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    /**
     * Schema validation (per {@code conformance-test-architecture} D6):
     * a fixture that sets {@code expectedCompileError} together with
     * runtime assertions ({@code expectedOutput}/
     * {@code expectedNotOutput}/{@code expectedError}/
     * {@code expectedExitCode}) or IR assertions ({@code irContains}/
     * {@code irNotContains}) would silently drop those assertions — the
     * compile-error gate returns before any runtime/IR dispatch, so a
     * misconfigured fixture would pass without ever running its runtime
     * checks. Returns a violation description, or {@code null} when the
     * configuration is valid.
     */
    static String fixtureConfigViolation(Map<String, Object> test) {
        Object expectedCompileError = test.get("expectedCompileError");
        if (expectedCompileError == null || expectedCompileError == JSON_NULL) {
            return null;
        }
        Object expectedOutput = test.get("expectedOutput");
        Object expectedError = test.get("expectedError");
        Object expectedExitCode = test.get("expectedExitCode");
        List<?> expectedNotOutput = (List<?>) test.getOrDefault(
            "expectedNotOutput", List.of());
        List<?> irContains = (List<?>) test.getOrDefault("irContains", List.of());
        List<?> irNotContains = (List<?>) test.getOrDefault("irNotContains", List.of());
        boolean runtimeSet = (expectedOutput != null && expectedOutput != JSON_NULL)
            || (expectedError != null && expectedError != JSON_NULL)
            || (expectedExitCode != null && expectedExitCode != JSON_NULL)
            || !expectedNotOutput.isEmpty();
        if (runtimeSet || !irContains.isEmpty() || !irNotContains.isEmpty()) {
            return "expectedCompileError is set together with runtime/IR "
                + "assertions (expectedOutput/expectedNotOutput/"
                + "expectedError/expectedExitCode/"
                + "irContains/irNotContains); the compile-error gate stops "
                + "before codegen and would silently drop them "
                + "(conformance-test-architecture D6: invalid fixture "
                + "configurations must fail)";
        }
        return null;
    }

    /**
     * Schema validation for multi-module fixtures (ISSUE-0096): {@code
     * modules} must replace {@code source} (exactly one of the two), be a
     * non-empty object of relative {@code .deal} paths to source strings,
     * name a valid {@code entry} among its keys, and list {@code backends}
     * as exactly {@code ["jvm"]} — the LuaJIT harness compiles single
     * sources only, so a multi-module fixture must never claim LuaJIT
     * coverage. Violations must fail with a clear message, never pass
     * silently (conformance-test-architecture D6).
     */
    static String multiModuleConfigViolation(Map<String, Object> test) {
        Object source = test.get("source");
        Object modules = test.get("modules");
        boolean hasSource = source != null && source != JSON_NULL;
        boolean hasModules = modules != null && modules != JSON_NULL;
        if (hasSource == hasModules) {
            return "fixture must set exactly one of 'source' (single-module) "
                + "or 'modules' (multi-module); got source=" + hasSource
                + ", modules=" + hasModules;
        }
        if (!(modules instanceof Map<?, ?> mods) || mods.isEmpty()) {
            return "'modules' must be a non-empty object mapping relative "
                + ".deal source paths to DEAL source strings";
        }
        for (Object v : mods.values()) {
            if (!(v instanceof String)) {
                return "every 'modules' value must be a DEAL source string; "
                    + "got: " + v;
            }
        }
        String entry = jsonString(test, "entry", null);
        if (entry == null) {
            return "a multi-module fixture requires an 'entry' field naming "
                + "the entry module (one of the 'modules' keys)";
        }
        if (!mods.containsKey(entry)) {
            return "'entry' (" + entry + ") is not a key of 'modules': "
                + mods.keySet();
        }
        if (!entry.endsWith(".deal")) {
            return "'entry' must be a .deal source path, got: " + entry;
        }
        List<?> backends = (List<?>) test.getOrDefault("backends", List.of());
        if (backends.size() != 1 || !"jvm".equals(String.valueOf(backends.get(0)))) {
            return "multi-module fixtures are JVM-only (the LuaJIT harness "
                + "compiles single sources); 'backends' must be exactly "
                + "[\"jvm\"], got: " + backends;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void runTestCase(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        String description = jsonString(test, "description", "");
        String source = jsonString(test, "source", null);
        String expectedCompileError = jsonString(test, "expectedCompileError", null);
        List<String> backends = (List<String>) test.getOrDefault("backends", List.of());
        List<String> expectedNotOutput = new ArrayList<>();
        for (Object o : (List<?>) test.getOrDefault("expectedNotOutput", List.of())) {
            expectedNotOutput.add(String.valueOf(o));
        }

        // ISSUE-0096: a fixture with a 'modules' object is a multi-module
        // fixture — it replaces 'source' and is dispatched to the
        // orchestrator-based pipeline instead of the single-source
        // frontend.
        Object modulesObj = test.get("modules");
        boolean multiModule = modulesObj != null && modulesObj != JSON_NULL;

        if (!multiModule && source == null) {
            System.out.println("  [" + name + "] FAIL: missing 'source' field");
            failed++;
            return;
        }

        String configViolation = fixtureConfigViolation(test);
        if (configViolation == null && multiModule) {
            configViolation = multiModuleConfigViolation(test);
        }
        if (configViolation != null) {
            System.out.println("  [" + name + "] FAIL: invalid fixture "
                + "configuration: " + configViolation);
            failed++;
            return;
        }

        if (multiModule) {
            runMultiModuleTestCase(fixtureName, test);
            return;
        }

        try {
            // Compile with the real frontend (lexer → parser → name resolver
            // → type checker). Diagnostics are collected per phase, exactly
            // like ConformanceTest.compileAndGetDiagnostics.
            FrontendCompile fc = compileFrontend(source, "fixture-" + name + ".deal");

            // ---- Frontend compile-error gate (ISSUE-0091) ----
            // Rejected before any backend: this path returns before codegen,
            // javac, or java is touched.
            if (expectedCompileError != null) {
                boolean matched = fc.errors().stream()
                    .anyMatch(d -> expectedCompileError.equals(d.code()));
                boolean onlyFrontend = fc.errors().stream()
                    .allMatch(d -> !d.code().startsWith("E6"));
                if (!matched) {
                    System.out.println("  [" + name + "] FAIL: expected frontend "
                        + "compile-error " + expectedCompileError + " but got: "
                        + (fc.errors().isEmpty() ? "<no errors>"
                            : fc.errors().stream().map(Diagnostic::toString)
                                .toList()));
                    failed++;
                    return;
                }
                if (!onlyFrontend) {
                    System.out.println("  [" + name + "] FAIL: error codes came "
                        + "from backend lowering, not the frontend: " + fc.errors());
                    failed++;
                    return;
                }
                System.out.println("  [" + name + "] OK — compile-error "
                    + expectedCompileError + " rejected before backend"
                    + " (parser/checker only; no codegen invoked)");
                passed++;
                return;
            }

            if (fc.hasErrors()) {
                System.out.println("  [" + name + "] FAIL: frontend errors: "
                    + fc.errors());
                failed++;
                return;
            }

            // Check IR assertions — always done for IR assertions
            String ir = IrDumper.dump(fc.program(), fc.checkResult(), "fixture-" + name);

            List<String> irContains = (List<String>) test.getOrDefault("irContains", List.of());
            List<String> irNotContains = (List<String>) test.getOrDefault("irNotContains", List.of());

            boolean irOk = true;
            for (String needle : irContains) {
                if (!ir.contains(needle)) {
                    System.out.println("  [" + name + "] FAIL: IR should contain '" + needle + "'");
                    System.out.println("    IR:\n" + ir);
                    irOk = false;
                }
            }
            for (String needle : irNotContains) {
                if (ir.contains(needle)) {
                    System.out.println("  [" + name + "] FAIL: IR should NOT contain '" + needle + "'");
                    System.out.println("    IR:\n" + ir);
                    irOk = false;
                }
            }

            if (!irOk) {
                failed++;
                return;
            }

            // If runtime test → dispatch to the backends listed in the fixture
            Object expectedOutput = test.get("expectedOutput");
            Object expectedError = test.get("expectedError");
            Object expectedExitCode = test.get("expectedExitCode");

            boolean hasRuntimeAssertions = (expectedOutput != null && expectedOutput != JSON_NULL)
                || (expectedError != null && expectedError != JSON_NULL)
                || (expectedExitCode != null && expectedExitCode != JSON_NULL)
                || !expectedNotOutput.isEmpty();

            if (!hasRuntimeAssertions) {
                System.out.println("  [" + name + "] OK" +
                    (description.isEmpty() ? "" : " — " + description));
                passed++;
                return;
            }

            boolean appliesToLuajit = backends.contains("luajit");
            boolean appliesToJvm = backends.contains("jvm");
            boolean ranAny = false;

            if (appliesToLuajit) {
                if (luajitAvailable) {
                    if (!runLuaAssertions(name, source, expectedOutput,
                            expectedNotOutput, expectedError, expectedExitCode)) {
                        return; // failure already reported
                    }
                    ranAny = true;
                }
            }

            if (appliesToJvm) {
                if (jvmAvailable) {
                    if (!runJvmAssertions(name, fc, expectedOutput,
                            expectedNotOutput, expectedError, expectedExitCode)) {
                        return; // failure already reported
                    }
                    ranAny = true;
                }
            }

            if (!ranAny) {
                System.out.println("  [" + name + "] SKIP (runtime test, "
                    + "no applicable backend available)");
                skipped++;
                return;
            }

            System.out.println("  [" + name + "] OK" +
                (description.isEmpty() ? "" : " — " + description));
            passed++;

        } catch (Exception e) {
            System.out.println("  [" + name + "] FAIL: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        }
    }

    // =========================================================================
    // Frontend compilation
    // =========================================================================

    /**
     * Runs the real frontend pipeline and collects every error diagnostic
     * (lexer, parser, name resolver, type checker), mirroring
     * {@code ConformanceTest.compileAndGetDiagnostics}. A bypassed parser or
     * checker yields no diagnostics — the compile-error fixtures fail.
     */
    // E9999 is the project's test-only pseudo code for a NameResolver
    // exception (the ConformanceTest precedent); the String-code overload is
    // deprecated, and this suppression keeps the build warning-free.
    @SuppressWarnings("deprecation")
    private static FrontendCompile compileFrontend(String source, String filename) {
        List<Diagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (Diagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }
        if (lex.hasErrors()) {
            return new FrontendCompile(null, null, null, errors);
        }

        Parser parser = new Parser(lex.tokens(), filename);
        ParseResult parseResult = parser.parse();
        for (Diagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }
        if (parseResult.hasErrors()) {
            return new FrontendCompile(null, null, null, errors);
        }

        StubModuleResolver resolver = new StubModuleResolver();
        NameResolver nr = new NameResolver(filename, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(Diagnostic.error("E9999", e.getMessage(), filename, 1, 1));
            return new FrontendCompile(null, null, null, errors);
        }
        for (Diagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }

        CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
        for (Diagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) errors.add(d);
        }

        return new FrontendCompile(parseResult.program(), result, symTable, errors);
    }

    // =========================================================================
    // Multi-module adapter (ISSUE-0096)
    // =========================================================================

    /** Result of running the real whole-project pipeline. */
    private record OrchestratorRun(boolean success, List<Diagnostic> diagnostics,
                                   String capturedOutput) {}

    /**
     * Runs a multi-module fixture through the real {@link
     * CompilationOrchestrator} pipeline with {@link Backend#JVM}: module
     * discovery, signature extraction, dependency ordering, name
     * resolution, type checking, and per-module JvmBackend codegen into
     * {@code outputRoot} (the fixture's module sources are written under
     * {@code projectRoot}, which is the single module root). The
     * orchestrator's stdout/stderr (phase logs, diagnostic reports) is
     * captured so fixture output stays clean and failure messages can
     * quote it.
     */
    private static OrchestratorRun runOrchestrator(Path projectRoot, Path entryFile,
                                                    Path outputRoot) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entryFile.toAbsolutePath().normalize(),
                outputRoot.toAbsolutePath().normalize(),
                false, true, false, Backend.JVM,
                null, List.of(projectRoot.toAbsolutePath().normalize()), null);
            boolean success = orchestrator.compile();
            return new OrchestratorRun(success, orchestrator.diagnostics(),
                captured.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new OrchestratorRun(false, List.of(),
                "orchestrator I/O failure: " + e + "\n"
                    + captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /**
     * Writes every {@code modules} entry under the temp project root and
     * returns relative path → absolute file path. A relative path that
     * escapes the root is rejected — fixtures are trusted test data, but a
     * traversal would silently write outside the temp dir.
     */
    private static Map<String, Path> writeModuleFiles(Path root,
                                                      Map<String, Object> modulesObj)
            throws IOException {
        Map<String, Path> written = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : modulesObj.entrySet()) {
            Path target = root.resolve(e.getKey()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalStateException("module path escapes the "
                    + "project root: " + e.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, String.valueOf(e.getValue()));
            written.put(e.getKey(), target);
        }
        return written;
    }

    /**
     * Class name of the entry module's emitted artifact. The orchestrator
     * computes the entry's module path relative to the single module root
     * ({@code a/b.deal → a.b}), and {@link JvmBackend#classNameFor} derives
     * the class name — mirrored here so the runner and artifact assertions
     * reference the same name the orchestrator used.
     */
    private static String entryClassName(String entryRel) {
        String path = entryRel;
        if (path.endsWith(".deal")) {
            path = path.substring(0, path.length() - ".deal".length());
        }
        return JvmBackend.classNameFor(path.replace('/', '.').replace('\\', '.'));
    }

    /**
     * Parses the entry module with the real lexer + parser for the runner
     * (its export list drives auto-invocation). Type checking is NOT
     * re-run here — the orchestrator already checked every module — so
     * this parse cannot act as a checker bypass for the fixture.
     */
    private static ProgramNode parseEntryProgram(Path entryFile) throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), entryFile.toString()).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry module parse errors: "
                + parse.diagnostics());
        }
        return parse.program();
    }

    /** Concatenates every module IR dump the orchestrator wrote (sorted by
     * file name) for the fixture's {@code irContains}/{@code irNotContains}
     * assertions. */
    private static String collectIrDumps(Path outputRoot) throws IOException {
        List<Path> dumps = new ArrayList<>();
        try (var stream = Files.list(outputRoot)) {
            stream.filter(p -> p.toString().endsWith(".ir.txt"))
                  .sorted()
                  .forEach(dumps::add);
        }
        StringBuilder sb = new StringBuilder();
        for (Path dump : dumps) {
            sb.append("=== IR: ").append(dump.getFileName()).append(" ===\n");
            sb.append(Files.readString(dump)).append('\n');
        }
        return sb.toString();
    }

    /** Assertion block shared by the single-module and multi-module JVM
     * adapters: the same {@code DEAL_ERROR_CODE} contract and exit-code
     * check as the LuaJIT runner. Returns true when every assertion holds. */
    private static boolean assertJvmRun(String name, String output, int actualExitCode,
                                        Object expectedOutput, List<String> expectedNotOutput,
                                        Object expectedError, Object expectedExitCode) {
        if (expectedOutput != null && expectedOutput != JSON_NULL) {
            String expStr = String.valueOf(expectedOutput);
            if (!output.contains(expStr)) {
                System.out.println("  [" + name + "] FAIL: expected output '"
                    + expStr + "', got: " + output);
                failed++;
                return false;
            }
        }

        if (expectedError != null && expectedError != JSON_NULL) {
            String expErr = String.valueOf(expectedError);
            if (!output.contains("DEAL_ERROR_CODE: " + expErr)) {
                System.out.println("  [" + name + "] FAIL: expected error '"
                    + expErr + "', got: " + output);
                failed++;
                return false;
            }
        }

        for (String forbidden : expectedNotOutput) {
            if (output.contains(forbidden)) {
                System.out.println("  [" + name + "] FAIL: output must NOT "
                    + "contain '" + forbidden + "', got: " + output);
                failed++;
                return false;
            }
        }

        if (expectedExitCode != null && expectedExitCode != JSON_NULL) {
            int expCode = ((Number) expectedExitCode).intValue();
            if (actualExitCode != expCode) {
                System.out.println("  [" + name + "] FAIL: expected exit code "
                    + expCode + ", got " + actualExitCode);
                System.out.println("    Output: " + output);
                failed++;
                return false;
            }
        }
        return true;
    }

    /**
     * The real multi-module JVM path (ISSUE-0096): orchestrator compile of
     * every module → IR dump assertions → {@code javac} over every emitted
     * {@code .java} artifact plus a runner → {@code java} execution of the
     * emitted artifacts → stdout/error/exit-code assertions. Every stage
     * must genuinely run: a bypassed parser/checker/module-discovery
     * yields a failed compile, a bypassed codegen leaves no {@code .java}
     * artifact (asserted before javac), and a bypassed JVM execution
     * produces no output.
     */
    @SuppressWarnings("unchecked")
    private static void runMultiModuleTestCase(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        String description = jsonString(test, "description", "");
        String entry = jsonString(test, "entry", null);
        Map<String, Object> modulesObj = (Map<String, Object>) test.get("modules");
        String expectedCompileError = jsonString(test, "expectedCompileError", null);

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_backend_conf_mm_");
            Map<String, Path> written = writeModuleFiles(projectRoot, modulesObj);
            Path entryFile = written.get(entry);
            Path outputRoot = projectRoot.resolve("out");

            // ---- Frontend compile-error gate (orchestrator-based) ----
            // The whole pipeline runs; the named frontend error must appear,
            // no E6xxx backend-lowering code may appear, and no .java
            // artifact may exist (codegen never ran). A bypassed
            // parser/checker/module-discovery produces no such diagnostic
            // and the fixture fails.
            if (expectedCompileError != null) {
                OrchestratorRun run = runOrchestrator(projectRoot, entryFile, outputRoot);
                boolean matched = run.diagnostics().stream()
                    .anyMatch(d -> expectedCompileError.equals(d.code()));
                boolean onlyFrontend = run.diagnostics().stream()
                    .allMatch(d -> !d.code().startsWith("E6"));
                if (run.success()) {
                    System.out.println("  [" + name + "] FAIL: expected frontend "
                        + "compile-error " + expectedCompileError
                        + " but the project compiled successfully");
                    failed++;
                    return;
                }
                if (!matched) {
                    System.out.println("  [" + name + "] FAIL: expected frontend "
                        + "compile-error " + expectedCompileError + " but got: "
                        + (run.diagnostics().isEmpty() ? "<no errors>"
                            : run.diagnostics().stream().map(Diagnostic::toString)
                                .toList()));
                    failed++;
                    return;
                }
                if (!onlyFrontend) {
                    System.out.println("  [" + name + "] FAIL: error codes came "
                        + "from backend lowering, not the frontend: "
                        + run.diagnostics());
                    failed++;
                    return;
                }
                boolean artifactWritten = Files.isDirectory(outputRoot);
                if (artifactWritten) {
                    try (var stream = Files.walk(outputRoot)) {
                        artifactWritten = stream.anyMatch(
                            p -> p.toString().endsWith(".java"));
                    }
                }
                if (artifactWritten) {
                    System.out.println("  [" + name + "] FAIL: the compile-error "
                        + "gate produced .java artifacts (codegen ran): "
                        + run.capturedOutput());
                    failed++;
                    return;
                }
                System.out.println("  [" + name + "] OK — compile-error "
                    + expectedCompileError + " rejected before backend"
                    + " (orchestrator pipeline; no codegen invoked)");
                passed++;
                return;
            }

            // ---- Runtime fixture: the whole project must compile. ----
            OrchestratorRun run = runOrchestrator(projectRoot, entryFile, outputRoot);
            if (!run.success()) {
                System.out.println("  [" + name + "] FAIL: orchestrator compile "
                    + "failed: " + run.diagnostics() + "\n"
                    + run.capturedOutput());
                failed++;
                return;
            }

            // IR assertions over every module's IR dump.
            String ir = collectIrDumps(outputRoot);
            List<String> irContains = (List<String>) test.getOrDefault("irContains", List.of());
            List<String> irNotContains = (List<String>) test.getOrDefault("irNotContains", List.of());
            boolean irOk = true;
            for (String needle : irContains) {
                if (!ir.contains(needle)) {
                    System.out.println("  [" + name + "] FAIL: IR should contain '"
                        + needle + "'");
                    System.out.println("    IR:\n" + ir);
                    irOk = false;
                }
            }
            for (String needle : irNotContains) {
                if (ir.contains(needle)) {
                    System.out.println("  [" + name + "] FAIL: IR should NOT "
                        + "contain '" + needle + "'");
                    System.out.println("    IR:\n" + ir);
                    irOk = false;
                }
            }
            if (!irOk) {
                failed++;
                return;
            }

            Object expectedOutput = test.get("expectedOutput");
            Object expectedError = test.get("expectedError");
            Object expectedExitCode = test.get("expectedExitCode");
            boolean hasRuntimeAssertions =
                (expectedOutput != null && expectedOutput != JSON_NULL)
                || (expectedError != null && expectedError != JSON_NULL)
                || (expectedExitCode != null && expectedExitCode != JSON_NULL);

            if (!hasRuntimeAssertions) {
                System.out.println("  [" + name + "] OK" +
                    (description.isEmpty() ? "" : " — " + description));
                passed++;
                return;
            }

            if (!jvmAvailable) {
                System.out.println("  [" + name + "] SKIP (multi-module runtime "
                    + "test, javac/java unavailable)");
                skipped++;
                return;
            }

            // Codegen was real: the entry artifact must exist before javac.
            String entryClass = entryClassName(entry);
            Path entryJava = outputRoot.resolve(entryClass + ".java");
            if (!Files.exists(entryJava)) {
                System.out.println("  [" + name + "] FAIL: JVM codegen produced "
                    + "no '" + entryJava.getFileName() + "' artifact");
                failed++;
                return;
            }

            // Runner: auto-invokes the entry module's zero-arity exports,
            // driven by the real parser's export list.
            ProgramNode entryProgram = parseEntryProgram(entryFile);
            Path runnerFile = outputRoot.resolve("JvmConformanceRunner.java");
            Files.writeString(runnerFile, buildJvmRunner(entryProgram, entryClass));

            // javac over every emitted .java artifact plus the runner.
            List<String> javacArgs = new ArrayList<>();
            javacArgs.add("javac");
            javacArgs.add("-encoding");
            javacArgs.add("UTF-8");
            try (var stream = Files.list(outputRoot)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .sorted()
                      .forEach(p -> javacArgs.add(p.getFileName().toString()));
            }
            ProcessBuilder pb = new ProcessBuilder(javacArgs);
            pb.directory(outputRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String javacOutput = new String(p.getInputStream().readAllBytes()).trim();
            int javacExit = p.waitFor();
            if (javacExit != 0) {
                System.out.println("  [" + name + "] FAIL: javac failed (exit "
                    + javacExit + "):\n" + javacOutput);
                failed++;
                return;
            }
            if (!Files.exists(outputRoot.resolve(entryClass + ".class"))
                    || !Files.exists(outputRoot.resolve("JvmConformanceRunner.class"))) {
                System.out.println("  [" + name + "] FAIL: javac exited 0 but no "
                    + ".class artifacts were produced (JVM compilation bypassed)");
                failed++;
                return;
            }

            // Execute the emitted artifacts with java.
            ProcessBuilder pb2 = new ProcessBuilder("java", "-cp",
                outputRoot.toString(), "JvmConformanceRunner");
            pb2.directory(outputRoot.toFile());
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            String output = new String(p2.getInputStream().readAllBytes()).trim();
            int actualExitCode = p2.waitFor();

            if (!assertJvmRun(name, output, actualExitCode, expectedOutput,
                    List.of(), expectedError, expectedExitCode)) {
                return; // failure already reported
            }

            System.out.println("  [" + name + "] OK" +
                (description.isEmpty() ? "" : " — " + description));
            passed++;

        } catch (Exception e) {
            System.out.println("  [" + name + "] FAIL: multi-module JVM "
                + "execution exception: " + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
        } finally {
            if (projectRoot != null) {
                try {
                    Files.walk(projectRoot).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
    }

    // =========================================================================
    // LuaJIT adapter (unchanged behavior)
    // =========================================================================

    /** Runs the LuaJIT execution + assertions. Returns true when all pass. */
    private static boolean runLuaAssertions(String name, String source,
                                            Object expectedOutput,
                                            List<String> expectedNotOutput,
                                            Object expectedError,
                                            Object expectedExitCode) {
        String lua = generateLua(source, "fixture-" + name + ".deal");
        if (lua == null) {
            System.out.println("  [" + name + "] FAIL: codegen failed");
            failed++;
            return false;
        }

        boolean isErrorTest = (expectedError != null && expectedError != JSON_NULL);
        ExecutionResult execResult = executeLua(lua, isErrorTest);
        if (execResult == null) {
            System.out.println("  [" + name + "] FAIL: Lua execution returned null");
            failed++;
            return false;
        }
        String output = execResult.output();
        int actualExitCode = execResult.exitCode();

        if (expectedOutput != null && expectedOutput != JSON_NULL) {
            String expStr = String.valueOf(expectedOutput);
            if (!output.contains(expStr)) {
                System.out.println("  [" + name + "] FAIL: expected output '" +
                    expStr + "', got: " + output);
                failed++;
                return false;
            }
        }

        if (expectedError != null && expectedError != JSON_NULL) {
            String expErr = String.valueOf(expectedError);
            if (!output.contains("DEAL_ERROR_CODE: " + expErr)) {
                System.out.println("  [" + name + "] FAIL: expected error '" +
                    expErr + "', got: " + output);
                failed++;
                return false;
            }
        }

        for (String forbidden : expectedNotOutput) {
            if (output.contains(forbidden)) {
                System.out.println("  [" + name + "] FAIL: output must NOT "
                    + "contain '" + forbidden + "', got: " + output);
                failed++;
                return false;
            }
        }

        // Assert expected exit code
        if (expectedExitCode != null && expectedExitCode != JSON_NULL) {
            int expCode = ((Number) expectedExitCode).intValue();
            if (actualExitCode != expCode) {
                System.out.println("  [" + name + "] FAIL: expected exit code " +
                    expCode + ", got " + actualExitCode);
                System.out.println("    Output: " + output);
                failed++;
                return false;
            }
        }
        return true;
    }

    private static String generateLua(String source, String filename) {
        try {
            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) return null;

            Parser parser = new Parser(lex.tokens(), filename);
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) return null;

            StubModuleResolver resolver = new StubModuleResolver();
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable = nr.resolve(parseResult.program());
            CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
            if (result.hasErrors()) return null;

            return LuaBackend.generate(parseResult.program(), result, filename);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // JVM adapter (ISSUE-0091)
    // =========================================================================

    /**
     * The real JVM path: DEAL frontend (already done) → {@link JvmBackend}
     * codegen → {@code javac} subprocess → {@code java} subprocess → assert.
     * Every stage must genuinely run: a bypassed codegen leaves no artifact
     * for {@code javac}, and a bypassed JVM execution produces no output.
     */
    private static boolean runJvmAssertions(String name, FrontendCompile fc,
                                            Object expectedOutput,
                                            List<String> expectedNotOutput,
                                            Object expectedError,
                                            Object expectedExitCode) {
        // 1. Codegen with the real JVM backend. Errors (E6000 for
        //    out-of-skeleton constructs) fail the fixture.
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            fc.program(), fc.checkResult(), "fixture-" + name + ".deal", "Main");
        if (res.hasErrors()) {
            System.out.println("  [" + name + "] FAIL: JVM codegen diagnostics: "
                + res.diagnostics());
            failed++;
            return false;
        }
        if (!res.source().contains("class " + res.className())) {
            System.out.println("  [" + name + "] FAIL: JVM codegen produced no '"
                + res.className() + "' class declaration");
            failed++;
            return false;
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("deal_backend_conf_jvm_");
            Path moduleFile = tmpDir.resolve(res.className() + ".java");
            Files.writeString(moduleFile, res.source());

            Path runnerFile = tmpDir.resolve("JvmConformanceRunner.java");
            Files.writeString(runnerFile, buildJvmRunner(fc.program(), res.className()));

            // 2. Compile the artifact with javac.
            ProcessBuilder pb = new ProcessBuilder("javac", "-encoding", "UTF-8",
                moduleFile.toString(), runnerFile.toString());
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String javacOutput = new String(p.getInputStream().readAllBytes()).trim();
            int javacExit = p.waitFor();
            if (javacExit != 0) {
                System.out.println("  [" + name + "] FAIL: javac failed (exit "
                    + javacExit + "):\n" + javacOutput);
                failed++;
                return false;
            }
            if (!Files.exists(tmpDir.resolve(res.className() + ".class"))
                    || !Files.exists(tmpDir.resolve("JvmConformanceRunner.class"))) {
                System.out.println("  [" + name + "] FAIL: javac exited 0 but no "
                    + ".class artifacts were produced (JVM compilation bypassed)");
                failed++;
                return false;
            }

            // 3. Execute the artifact with java.
            ProcessBuilder pb2 = new ProcessBuilder("java", "-cp",
                tmpDir.toString(), "JvmConformanceRunner");
            pb2.directory(tmpDir.toFile());
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            String output = new String(p2.getInputStream().readAllBytes()).trim();
            int actualExitCode = p2.waitFor();

            // 4. Assertions — same observable contract as the LuaJIT runner.
            return assertJvmRun(name, output, actualExitCode, expectedOutput,
                expectedNotOutput, expectedError, expectedExitCode);
        } catch (Exception e) {
            System.out.println("  [" + name + "] FAIL: JVM execution exception: "
                + e.getMessage());
            e.printStackTrace(System.out);
            failed++;
            return false;
        } finally {
            if (tmpDir != null) {
                try {
                    Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Builds the Java runner source for a fixture: auto-invokes the zero-arity
     * exported functions in declaration order and prints non-null results;
     * DEAL runtime errors print {@code DEAL_ERROR_CODE: <code>} and exit 1,
     * matching the LuaJIT runner's xpcall handler. When no function is
     * auto-invoked, the runner initializes the module class with
     * {@code Class.forName} so a module-load-only fixture's static
     * initializers still run. A module-level error raised while a
     * zero-arity export exists surfaces the same way: the first
     * auto-invocation triggers class initialization, whose
     * {@link ExceptionInInitializerError} (a {@code LinkageError}, not a
     * {@code RuntimeException}) wraps the {@code DealError} — the runner
     * unwraps it into the same {@code DEAL_ERROR_CODE} contract.
     *
     * <p>Cross-backend invocation contract — result handling is
     * deliberately ASYMMETRIC and fixture authors must not rely on export
     * return values across backends:
     * <ul>
     * <li>the JVM runner invokes zero-arity exports in <em>declaration
     * order</em> and <em>prints</em> each non-null result with Java's
     * default formatting ({@code System.out.println(long)} prints
     * {@code 5}; {@code System.out.println(double)} prints {@code 1.0}/
     * {@code 1.0E300} — not LuaJIT's {@code tostring} {@code 1}/
     * {@code 1e+300});</li>
     * <li>the LuaJIT harness ({@link #buildRuntimeOkRunner} /
     * {@link #buildXpcallRunner}) invokes zero-arity exports in
     * {@code pairs()} order (unspecified) and <em>discards</em> their
     * results.</li>
     * </ul>
     * Consequence: a printed return value is asserted only against the JVM
     * runner (result-value assertions are JVM-only), a printed
     * {@code double} is backend-dependent in formatting, fixtures with
     * multiple zero-arity exports must not depend on cross-backend
     * invocation order, and cross-backend fixtures must route every
     * observable output through {@code std/console} — never through an
     * export's return value. The LuaJIT runner keeps {@code pairs()} +
     * discard because the pre-ISSUE-0091 harness contract must not change;
     * the JVM runner prints results so a single-export fixture can assert
     * its computed value end-to-end without an extra {@code console.log}
     * round-trip.
     */
    static String buildJvmRunner(ProgramNode program, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by deal.test.BackendConformanceTest — JVM conformance runner.\n");
        sb.append("// Auto-invokes zero-arity exported functions and prints non-null results.\n");
        sb.append("public final class JvmConformanceRunner {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        try {\n");
        boolean any = false;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd) {
                String fn = JvmBackend.javaName(fd.name());
                if (fd.params().isEmpty() && fd.restParam().isEmpty()) {
                    any = true;
                    if (isNullReturnType(fd.returnType())) {
                        sb.append("            ").append(className).append('.')
                            .append(fn).append("();\n");
                    } else {
                        sb.append("            System.out.println(").append(className)
                            .append('.').append(fn).append("());\n");
                    }
                } else {
                    sb.append("            // ").append(fn)
                        .append(" takes parameters; not auto-invoked\n");
                }
            }
        }
        if (!any) {
            // No zero-arity exported function was auto-invoked, so the module
            // class would never be initialized and module-level statements
            // (its static initializers) would never run — a load-only fixture
            // would produce no output. Initialize the class explicitly so
            // module-level side effects are observable. (A module-level DEAL
            // error thrown here is wrapped in an ExceptionInInitializerError;
            // the catch below unwraps it into the DEAL_ERROR_CODE contract.)
            sb.append("            // no zero-arity exported functions; initialize the module class\n");
            sb.append("            // so module-level statements (static initializers) run.\n");
            sb.append("            try { Class.forName(\"").append(className).append("\"); }\n");
            sb.append("                catch (ClassNotFoundException e) { throw new RuntimeException(e); }\n");
        }
        sb.append("        } catch (").append(className).append(".DealError e) {\n");
        sb.append("            System.out.println(\"DEAL_ERROR_CODE: \" + e.code"
            + " + \" \" + e.getMessage());\n");
        sb.append("            System.exit(1);\n");
        sb.append("        } catch (ExceptionInInitializerError e) {\n");
        sb.append("            // Class initialization (module-level statements) threw a\n");
        sb.append("            // DEAL error wrapped in a LinkageError — whether the class\n");
        sb.append("            // was initialized by an auto-invocation or by the\n");
        sb.append("            // Class.forName path. Unwrap it so the DEAL_ERROR_CODE\n");
        sb.append("            // contract holds for module-load errors in every fixture.\n");
        sb.append("            Throwable cause = e.getCause();\n");
        sb.append("            if (cause instanceof ").append(className).append(".DealError de) {\n");
        sb.append("                System.out.println(\"DEAL_ERROR_CODE: \" + de.code"
            + " + \" \" + de.getMessage());\n");
        sb.append("            } else {\n");
        sb.append("                System.out.println(\"DEAL_ERROR_CODE: \""
            + " + (cause == null ? e.toString() : cause.toString()));\n");
        sb.append("            }\n");
        sb.append("            System.exit(1);\n");
        sb.append("        } catch (Throwable e) {\n");
        sb.append("            System.out.println(\"DEAL_ERROR_CODE: \" + e.getMessage());\n");
        sb.append("            System.exit(1);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isNullReturnType(TypeNode t) {
        return t instanceof NamedType nt && "null".equals(nt.name());
    }

    // =========================================================================
    // Lua execution
    // =========================================================================

    private static ExecutionResult executeLua(String luaSource, boolean isXpcallWrapped) {
        String runner;
        if (isXpcallWrapped) {
            runner = buildXpcallRunner(luaSource);
        } else {
            runner = buildRuntimeOkRunner(luaSource);
        }
        try {
            Path tmpDir = Files.createTempDirectory("deal_backend_conf_");
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

            ProcessBuilder pb = new ProcessBuilder("luajit", luaFile.toString());
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();

            // Cleanup
            try {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}

            return new ExecutionResult(output, exitCode);
        } catch (Exception e) {
            System.err.println("    Lua execution exception: " + e.getMessage());
            return null;
        }
    }

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
            "end)\n" +
            "if not __ok then os.exit(1) end\n";
    }

    // =========================================================================
    // Minimal JSON parser — no external dependencies
    // =========================================================================

    /**
     * Parses a JSON string into a Java object tree (Map, List, String,
     * Number, Boolean, JSON_NULL).
     */
    private static Object parseJson(String s) {
        int[] pos = new int[]{0};
        skipWhitespace(s, pos);
        return parseValue(s, pos);
    }

    private static void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static Object parseValue(String s, int[] pos) {
        skipWhitespace(s, pos);
        if (pos[0] >= s.length()) return JSON_NULL;

        char c = s.charAt(pos[0]);
        return switch (c) {
            case '"' -> parseString(s, pos);
            case '{' -> parseObject(s, pos);
            case '[' -> parseArray(s, pos);
            case 'n' -> { pos[0] += 4; yield JSON_NULL; }
            case 't' -> { pos[0] += 4; yield true; }
            case 'f' -> { pos[0] += 5; yield false; }
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield parseNumber(s, pos);
                }
                throw new RuntimeException("Unexpected char '" + c + "' at " + pos[0]);
            }
        };
    }

    private static String parseString(String s, int[] pos) {
        pos[0]++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '"') {
                pos[0]++;
                return sb.toString();
            }
            if (c == '\\') {
                pos[0]++;
                if (pos[0] < s.length()) {
                    char ec = s.charAt(pos[0]);
                    sb.append(switch (ec) {
                        case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                        default -> ec;
                    });
                }
            } else {
                sb.append(c);
            }
            pos[0]++;
        }
        return sb.toString();
    }

    private static Map<String, Object> parseObject(String s, int[] pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos[0]++; // skip '{'
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return map;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) break;
            if (s.charAt(pos[0]) == '}') { pos[0]++; break; }
            if (s.charAt(pos[0]) == ',') { pos[0]++; continue; }

            String key = parseString(s, pos);
            skipWhitespace(s, pos);
            if (pos[0] < s.length() && s.charAt(pos[0]) == ':') pos[0]++;
            Object value = parseValue(s, pos);
            map.put(key, value);
        }
        return map;
    }

    private static List<Object> parseArray(String s, int[] pos) {
        List<Object> list = new ArrayList<>();
        pos[0]++; // skip '['
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) break;
            if (s.charAt(pos[0]) == ']') { pos[0]++; break; }
            if (s.charAt(pos[0]) == ',') { pos[0]++; continue; }

            Object value = parseValue(s, pos);
            list.add(value);
        }
        return list;
    }

    private static Number parseNumber(String s, int[] pos) {
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e'
                || c == 'E' || c == '+') {
                sb.append(c);
                pos[0]++;
            } else {
                break;
            }
        }
        String numStr = sb.toString();
        if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    private static String jsonString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        if (v == null || v == JSON_NULL) return defaultValue;
        return String.valueOf(v);
    }

    // =========================================================================
    // Stub module resolver
    // =========================================================================

    static class StubModuleResolver implements ModuleResolver {
        private static final Map<String, Map<String, Type>> STDLIB;

        static {
            STDLIB = new HashMap<>();
            STDLIB.put("std/console", module(
                fn("log", list(strType()), nullType()),
                fn("error", list(strType()), nullType())
            ));
            STDLIB.put("std/string", module(
                fn("length", list(strType()), intType()),
                fn("substring", list(strType(), intType(), intType()), strType()),
                fn("contains", list(strType(), strType()), boolType()),
                fn("startsWith", list(strType(), strType()), boolType()),
                fn("endsWith", list(strType(), strType()), boolType()),
                fn("replace", list(strType(), strType(), strType()), strType()),
                fn("split", list(strType(), strType()), arrayType(strType())),
                fn("trim", list(strType()), strType())
            ));
            STDLIB.put("std/table", module(
                fn("keys", list(tableType()), arrayType(strType()))
            ));
            STDLIB.put("std/json", module(
                fn("parse", list(strType()), tableType()),
                fn("stringify", list(tableType()), strType())
            ));
            STDLIB.put("std/math", module(
                fn("floor", list(numType()), numType()),
                fn("ceil", list(numType()), numType()),
                fn("sqrt", list(numType()), numType()),
                fn("absInt", list(intType()), intType()),
                fn("absNumber", list(numType()), numType()),
                fn("minInt", list(intType(), intType()), intType()),
                fn("maxInt", list(intType(), intType()), intType())
            ));
            STDLIB.put("std/time", module(
                fn("nowMillis", list(), intType())
            ));
        }

        private static Type intType()    { return Type.Int.INSTANCE; }
        private static Type numType()    { return Type.Number.INSTANCE; }
        private static Type strType()    { return Type.String.INSTANCE; }
        private static Type boolType()   { return Type.Boolean.INSTANCE; }
        private static Type nullType()   { return Type.Null.INSTANCE; }
        private static Type tableType()  { return Type.Table.INSTANCE; }
        private static Type arrayType(Type elem) { return Types.array(elem); }
        private static Type fnType(List<Type> params, Type ret) { return Types.func(params, ret); }

        @SafeVarargs
        private static Map<String, Type> module(Map.Entry<String, Type>... entries) {
            Map<String, Type> m = new LinkedHashMap<>();
            for (var e : entries) m.put(e.getKey(), e.getValue());
            return m;
        }

        private static Map.Entry<String, Type> fn(String name, List<Type> params, Type ret) {
            return Map.entry(name, fnType(params, ret));
        }

        private static List<Type> list(Type... types) { return List.of(types); }

        @Override
        public Map<String, Type> resolveModule(String modulePath, String importingModule,
                                                Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            Map<String, Type> exports = STDLIB.get(modulePath);
            if (exports != null) return exports;
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
