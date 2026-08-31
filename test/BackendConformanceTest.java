package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.js.HostModuleDeclarations;
import deal.codegen.js.JsBackend;
import deal.codegen.lua.LuaBackend;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.module.ModuleIdentityResolver;
import deal.module.ModuleShapeValidator;
import deal.module.DealConfig;
import deal.module.StdlibModuleResolver;
import deal.ir.IrDumper;
import deal.semantic.CompilerInvocation;
import deal.semantic.ir.SemanticProfile;
import deal.lexer.*;
import deal.parser.*;
import deal.types.Type;
import deal.types.Types;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Loads backend-neutral JSON fixture tests from
 * {@code test/conformance/fixtures/} and executes them against the
 * LuaJIT backend, (ISSUE-0091, ISSUE-0092, ISSUE-0093, ISSUE-0094,
 * ISSUE-0096, ISSUE-0097) the JVM backend, and (ISSUE-0193,
 * js-backend-conformance-e2e D1) the JS backend — the {@code js}
 * single-source slice fixtures live in
 * {@code test/conformance/fixtures/js-skeleton.json} and run through the
 * real {@link deal.codegen.js.JsBackend} plus a deployed
 * {@code deal/runtime.js}/{@code std/*.js} artifact set under the probed
 * {@code node} binary (see {@link #runJsAssertions}). The
 * ISSUE-0092 semantic-slice fixtures live in
 * {@code test/conformance/fixtures/jvm-semantic-slice.json} (while loops,
 * template literals, and the surrounding primitive surface — JVM-only,
 * each runtime fixture compiled with the javac frontend (in-process for
 * the single-module adapter, ISSUE-0109 gate-time work — see
 * {@link #compileWithJavac}) and executed with {@code java} against the
 * emitted artifact), the ISSUE-0093
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
 * Unicode scalar-value length/substring/split (ISSUE-0106, spec-v1.2)
 * plus the plain-text search/replace/trim helpers, std/math
 * floor/ceil/sqrt/absInt/
 * absNumber/minInt/maxInt with the sqrt-negative E8001 runtime error,
 * std/time's second-truncated nowMillis, stdlib results composing
 * across modules, a frontend E5001 compile-error gate, and a
 * multi-module fixture consuming std/string through the orchestrator
 * pipeline), the ISSUE-0109 imported-class / cross-module nominal
 * identity fixtures live in
 * {@code test/conformance/fixtures/jvm-xmod-classes-slice.json}
 * (exported/imported classes, imported construction with literal
 * defaults and provided-field literal-order evaluation, class values
 * passed/returned across modules, same-name classes from different
 * modules, and module-qualified runtime nominal-check success/failure
 * — E8001 naming both {@code @module/Name} identities — plus a
 * frontend E3001 gate, all through the orchestrator multi-module
 * pipeline), the ISSUE-0100 host ABI fixtures live in
 * {@code test/conformance/fixtures/jvm-host-abi-slice.json}
 * (externals-gated host modules: declared export exposure, missing
 * declared exports as load-time E8011 errors, extra host exports
 * ignored, sync return boundary checks — E8010 wrong kinds and Java
 * null crossing non-nullable returns, E8004 out-of-safe-range ints,
 * the ISSUE-0106 boundary string validation rejecting unpaired UTF-16
 * surrogate strings on sync returns (E8010, plain and nullable string
 * descriptors) and async completion values (E8001) — nullable returns
 * with Java null as the DEAL null sentinel, null return boundaries,
 * host function parameter adaptation, async host operation shape
 * checks (E8010) and completion checks (E8001 at the await site with
 * the blocking JVM await lowering), and an E2009
 * frontend gate — each fixture's 'hosts' map supplying a declaration
 * path and a real host implementation class compiled with the emitted
 * artifacts), the ISSUE-0099 async/await fixtures live in
 * {@code test/conformance/fixtures/jvm-async-slice.json} (async
 * declarations, await in value/discard/condition/nested-argument
 * positions, completion success and failure — E8005 propagating
 * through one and two await levels —, direct calls with
 * primitive/string/boolean/null signatures, async function values in
 * typed locals, callback parameters, and reassigned local bindings,
 * imported async direct calls through the orchestrator pipeline, and
 * frontend E3012/E3013/E3014 gates), and the ISSUE-0108
 * nullable-slice fixtures live in

 * {@code test/conformance/fixtures/jvm-nullable-slice.json}
 * ({@code T | null} for the four primitives and local classes in
 * locals, module fields, class fields, parameters, and returns;
 * null-check branch narrowing both directions plus narrowing
 * invalidation and module-field narrowing; {@code T[] | null} and
 * {@code (T | null)[]} for the primitive and local-class element types;
 * class arrays {@code C[]}; the table-read nullable boundary checks —
 * runtime success for null/missing/int/class/array values and E8001
 * failures for wrong inner values, wrong array wrappers, and
 * wrong-class arrays; the {@code int()}/{@code number()} nullable
 * conversion overloads including the runtime null failure; and frontend
 * E3001/E3006 compile-error gates rejected before any backend), and
 * the ISSUE-0098 function-values/wrappers fixtures live in
 * {@code test/conformance/fixtures/jvm-function-values-slice.json}
 * (typed/inferred function-value variables, indirect calls through
 * variables/parameters and call-result callees, callbacks, returned
 * function values, the int/number intrinsics as function values,
 * arity-extension adapters at variable/assignment positions, E8010
 * runtime signature checks at callback/return boundaries with the
 * checked value expression evaluated first (evaluate-then-check
 * side-effect order pinned cross-backend), wrapper reference equality,
 * wrapper-name collisions (a function named {@code invoke} or
 * {@code descriptor} — the wrapper classes' dispatch-method and
 * descriptor-field names — delegating through the module-class-qualified
 * static member, review 0009), eight frontend signature/indirect-call
 * compile-error gates (including the E1049 gates of the v1.1
 * module-field load-time shapes the v1.2 module shape removed), four
 * multi-module backend-rejection fixtures pinning the E6000 rejection
 * of cross-module function-value flow (callback-in, arity-extension
 * argument/E8010 boundary, return-out, and call-result callee — the
 * per-module wrapper classes cannot cross a module boundary, so the
 * pre-fix emissions were artifacts javac rejected after the CLI
 * reported success; the rejected entry module writes no artifact, and
 * every entry exports the v1.2 selected-entry non-async
 * {@code main(): null}), ten cross-backend parity fixtures (plus two
 * cross-backend E1049 grammar gates of the v1.1 live
 * field-adapter shapes), and six LuaJIT-only fixtures: two runtime
 * reference pins for the in-function shapes the JVM slice
 * conservatively rejects with E6000 until ISSUE-0110 (the
 * reassigned-local adapter and the side-effecting call-result adapter,
 * re-evaluated per invoke — pinned with console markers since the v1.2
 * grammar removed the module-field counter), and four E1049 grammar
 * gates of the v1.1 load-time shapes (a load-time-called function body
 * assigning the field before the indirect call, a load-time-called
 * function body containing the indirect call, and the two module-level
 * call-result callee shapes — LuaJIT's v1.1 E8001 'expected function'
 * and raw upvalue load failures) that the v1.2 module shape removes
 * before any backend.
 *
 * the @jsonable fixtures live in
 * {@code test/conformance/fixtures/jvm-jsonable-slice.json}
 * (the generated {@code C$fromJson}/{@code C$toJson} helpers: simple
 * toJson→fromJson roundtrips, fromJson defaults for omitted keys,
 * required no-default fields (the reference defaults table: the
 * primitive zeroes, a FRESH empty table for a {@code table} field and
 * a FRESH empty array for an array field — never Java null — and a
 * required class-typed field's absent key as a fromJson validation
 * failure returning the DEAL null), nested object fields, primitive
 * and class array fields, nullable
 * fields including {@code T[] | null}, the optional-nullable three
 * states (missing omits the key, explicit null emits
 * {@code "field": null}, a value emits the value) with {@code has()}
 * presence — also for optional-with-default, optional-nullable nested
 * class, and cross-module optional fields, where the Missing sentinel
 * is always the declaring module's — table fields mapping JSON objects
 * to string-keyed {@code $DealRt.Table} data (nested JSON arrays become array-mode
 * tables and roundtrip byte-identically) with the objects-only fromJson
 * and finite-acyclic toJson E8001 validations, extra-key/malformed-
 * JSON/type-mismatch validation returning the DEAL null, the toJson
 * E8001 NaN rejection, unpaired UTF-16 surrogate rejection in decoded
 * JSON strings (lone high/low surrogates in string fields and
 * table-field leaves are parse failures → the DEAL null, LuaJIT
 * `std/json.lua` parity), invalid JSON number spellings (leading
 * zeros, a decimal point without a fraction digit, an exponent
 * without fraction digits, and a missing integer part are parse
 * failures → the DEAL null for typed fields and table-field leaves,
 * strict RFC 8259 grammar before `Double.parseDouble`, LuaJIT
 * `std/json.lua` parity), cross-module helper calls with a nested
 * imported-class field whose deserialized instance carries its
 * declaring module's nominal identity, the same-name sibling identity
 * E8001 failure naming both module-qualified identities, and an E4007
 * frontend compile-error gate rejected before any backend).
 *
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
 * pipeline: a FRONTEND expectation asserts the named frontend error, no
 * E6 backend code, and no {@code .java} artifact at all (the gate stops
 * before codegen), while a BACKEND expectation (an E6 code — the
 * ISSUE-0098 E6000 cross-module function-value rejections) asserts the
 * named E6000 and that the rejected entry module wrote no artifact (clean
 * imported modules may still be written by the orchestrator's two-pass
 * design — never an artifact javac rejects after the CLI reported
 * success). {@code irContains}/{@code irNotContains} are
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
 *       the javac frontend in-process ({@link #compileWithJavac}; a
 *       missing {@code .class} fails the fixture);</li>
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

    private static final AtomicInteger passed = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    private static final AtomicInteger skipped = new AtomicInteger();
    private static final AtomicInteger knownFailures = new AtomicInteger();

    /** Profile-authority accounting (A4/A5): legacy-authority results
     * keep their own denominator and earn zero v1.2 credit. */
    private static final AtomicInteger legacyAuthorityResults =
        new AtomicInteger();
    private static final AtomicInteger v12CreditResults =
        new AtomicInteger();
    private static final AtomicInteger luajitPassed = new AtomicInteger();
    private static final AtomicInteger luajitFailed = new AtomicInteger();
    private static final AtomicInteger jvmPassed = new AtomicInteger();
    private static final AtomicInteger jvmFailed = new AtomicInteger();
    private static final AtomicInteger jsPassed = new AtomicInteger();
    private static final AtomicInteger jsFailed = new AtomicInteger();
    /**
     * Known-fail cases are drained on the main thread after every worker
     * fixture file has completed: their outcome bookkeeping snapshots the
     * global counters, which is only race-free when no worker is
     * mutating them.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]>
        pendingKnownFail = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static boolean luajitAvailable;
    private static boolean jvmAvailable;
    private static boolean nodeAvailable;

    // Sentinel for JSON null (distinct from Java null)
    private static final Object JSON_NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    /** Holds the result of executing a Lua program. */
    private record ExecutionResult(String output, int exitCode) {}

    /** Result of the backend-neutral frontend: real parser/checker output. */
    private record FrontendCompile(ProgramNode program, CheckResult checkResult,
                                   SymbolTable symbolTable,
                                   List<CompilerDiagnostic> errors) {
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
        nodeAvailable = probeNode();

        System.out.println("=== Backend Conformance Test — DEAL v1.2 "
            + "(LuaJIT + JVM + JS backend-runtime gates) ===");
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        System.out.println("JVM (javac + java): " + (jvmAvailable ? "available" :
            "NOT available (JVM runtime tests will be skipped)"));
        System.out.println("Node.js: " + (nodeAvailable ? "available" :
            "NOT available (JS runtime tests will be skipped)"));
        System.out.println();

        Path fixturesDir = Path.of("test/conformance/fixtures/");
        if (!Files.isDirectory(fixturesDir)) {
            System.out.println("No fixtures directory found at " + fixturesDir);
            System.exit(failed.get() > 0 ? 1 : 0);
        }

        List<Path> fixtureFiles;
        try (var stream = Files.list(fixturesDir)) {
            fixtureFiles = stream.filter(p -> p.toString().endsWith(".json"))
                .sorted().toList();
        }

        // ISSUE-0109 gate-time work, extended by ISSUE-0274: every
        // fixture case is an independent temp-root project, so cases run
        // as individual parallel worker tasks — the per-FILE granularity
        // still serialized each file's cases inside one worker, and the
        // largest files (67 sequential JVM subprocess cases) kept the
        // suite over the gate's wall-clock budget. File-level validation
        // (version, the tests array) stays deterministic on the main
        // thread below; each case buffers its output and flushes it as
        // one contiguous block (FILE_OUTPUT / CONSOLE_LOCK), so parallel
        // cases never interleave lines and the per-test evidence stays
        // readable. The pool is sized at 2x the core count: case work is
        // dominated by subprocess startup and child-CPU latency (javac /
        // java / node per case), so oversubscription overlaps process
        // spawns and keeps the suite under the gate's wall-clock budget
        // even when the host runs other work concurrently.
        List<Object[]> caseTasks = new ArrayList<>();
        Map<String, Set<String>> sliceCases = new LinkedHashMap<>();
        for (Path file : fixtureFiles) {
            log("--- Fixture: " + file.getFileName() + " ---");
            try {
                String raw = Files.readString(file);
                Map<String, Object> root = (Map<String, Object>) parseJson(raw);

                Object version = root.get("version");
                if (!"1.0".equals(String.valueOf(version))) {
                    log("  FAIL: unsupported version: " + version);
                    failed.incrementAndGet();
                    continue;
                }

                List<Map<String, Object>> tests =
                    (List<Map<String, Object>>) root.get("tests");
                if (tests == null) {
                    log("  FAIL: no 'tests' array in fixture");
                    failed.incrementAndGet();
                    continue;
                }

                String fixtureFileName = file.getFileName().toString();
                for (Map<String, Object> test : tests) {
                    String caseName = jsonString(test, "name", "<unnamed>");
                    sliceCases.computeIfAbsent(fixtureFileName,
                        k -> new TreeSet<>()).add(caseName);
                    // A4 completeness scan over every case source and
                    // every multi-module module source.
                    String caseSource = jsonString(test, "source", null);
                    if (caseSource != null) {
                        LegacyProfileRegressionCatalog.scanCaseSource(
                            fixtureFileName, caseName, caseSource,
                            jsonString(test, "expectedCompileError", null));
                    }
                    Object modulesObj = test.get("modules");
                    if (modulesObj != null && modulesObj != JSON_NULL) {
                        for (Map.Entry<String, Object> me
                                : ((Map<String, Object>) modulesObj).entrySet()) {
                            LegacyProfileRegressionCatalog.scanCaseSource(
                                fixtureFileName, caseName,
                                String.valueOf(me.getValue()),
                                jsonString(test, "expectedCompileError", null));
                        }
                    }
                    caseTasks.add(new Object[] {
                        fixtureFileName, test });
                }
            } catch (Exception e) {
                log("  FAIL: error processing fixture: " + e.getMessage());
                e.printStackTrace(System.out);
                failed.incrementAndGet();
            }
        }
        // LegacyProfileRegressionCatalog validation (A4): rows resolve,
        // the closed completeness scan found no uncatalogued
        // legacy-dependent assertion, and the mechanism self-probes pass
        // before any fixture executes.
        LegacyProfileRegressionCatalog.validateRows();
        LegacyProfileRegressionCatalog.validateReplacementRows();
        LegacyProfileRegressionCatalog.runSelfProbes();
        LegacyProfileRegressionCatalog.validateSliceRows(sliceCases);
        LegacyProfileRegressionCatalog.validateReplacementSliceRows(
            sliceCases);
        List<String> catalogViolations =
            LegacyProfileRegressionCatalog.drainViolations();
        if (!catalogViolations.isEmpty()) {
            log("CATALOG FAILURE: LegacyProfileRegressionCatalog "
                + "validation failed:");
            for (String violation : catalogViolations) {
                log("  " + violation);
            }
            System.exit(1);
        }

        int workers = Math.max(1,
            Math.min(2 * Runtime.getRuntime().availableProcessors(),
                caseTasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Object[] task : caseTasks) {
                futures.add(pool.submit(() -> runFixtureCase(
                    (String) task[0], (Map<String, Object>) task[1])));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Known-fail cases run last, on the main thread: no worker is
        // mutating the outcome counters while a known-fail case snapshots
        // them, so the pass/fail bookkeeping is exact.
        List<Object[]> knownCases = new ArrayList<>();
        Object[] pending;
        while ((pending = pendingKnownFail.poll()) != null) {
            knownCases.add(pending);
        }
        if (!knownCases.isEmpty()) {
            System.out.println();
            System.out.println("--- Known-fail cases (intentionally unsupported "
                + "DEAL v1.2 requirements, tracked per case) ---");
            for (Object[] kf : knownCases) {
                runKnownFailCase((String) kf[0], (Map<String, Object>) kf[1]);
            }
        }

        System.out.println();
        System.out.println("=== Backend Conformance Summary (DEAL v1.2) ===");
        int total = passed.get() + failed.get() + skipped.get();
        System.out.println("Total: " + total + ", Passed: " + passed.get() +
            ", Failed: " + failed.get() + ", Skipped: " + skipped.get() +
            ", KnownFailures (tracked): " + knownFailures.get());
        System.out.println();
        System.out.println("Profile-authority accounting: "
            + legacyAuthorityResults.get()
            + " legacy-authority case(s) (LEGACY_REGRESSION + "
            + "LEGACY_SAFE_INT — zero v1.2/promotion credit; every "
            + "legacy result is labelled distinctly on its case line), "
            + v12CreditResults.get()
            + " v1.2-credit case(s) (COMMON_SHADOW + DEAL_V1_2_INT32)");
        System.out.println();
        System.out.println("=== DEAL v1.2 Backend-Runtime Gates ===");
        int luajitTotal = luajitPassed.get() + luajitFailed.get();
        System.out.println("  LuaJIT backend-runtime: " + luajitPassed.get()
            + "/" + luajitTotal + " passed, " + luajitFailed.get() + " failed");
        int jvmTotal = jvmPassed.get() + jvmFailed.get();
        System.out.println("  JVM backend-runtime: " + jvmPassed.get()
            + "/" + jvmTotal + " passed, " + jvmFailed.get() + " failed");
        int jsTotal = jsPassed.get() + jsFailed.get();
        System.out.println("  JS backend-runtime: " + jsPassed.get()
            + "/" + jsTotal + " passed, " + jsFailed.get() + " failed");
        if (skipped.get() > 0) {
            System.out.println("  Skips: " + skipped.get()
                + " (toolchain-availability only — there are no unclassified skips)");
        } else {
            System.out.println("  Skips: 0 (zero unclassified skips)");
        }
        if (knownFailures.get() > 0) {
            System.out.println("  KnownFailures: " + knownFailures.get()
                + " (intentionally unsupported v1.2 cases; each fixture names "
                + "its tracked follow-up issue)");
        }

        if (failed.get() > 0) {
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

    /**
     * Probes that the {@code node} binary is invocable and functional
     * (the {@link #probeJvm} pattern): a broken-but-present node must
     * make the JS runtime fixtures skip, not fail.
     */
    private static boolean probeNode() {
        try {
            Process node = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            return node.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // =========================================================================
    // Fixture file runner
    // =========================================================================

    // =========================================================================
    // Parallel fixture-file execution (ISSUE-0109 gate-time work)
    // =========================================================================

    /**
     * Per-worker console buffer: fixture files run in parallel worker
     * threads, and every line a file's run produces is appended to a
     * thread-local buffer that flushes atomically when the file finishes.
     * The main thread has no buffer and prints directly.
     */
    private static final ThreadLocal<StringBuilder> FILE_OUTPUT =
        new ThreadLocal<>();

    /**
     * Serializes console flushes and the orchestrator capture swap:
     * {@link #runOrchestrator} redirects the global streams while it
     * compiles, so an unprotected concurrent flush could land inside
     * another worker's capture.
     */
    private static final Object CONSOLE_LOCK = new Object();

    /** Appends a fixture-run line to the worker buffer, or prints it
     * directly when the calling thread is not a fixture worker. */
    private static void log(String line) {
        StringBuilder buffer = FILE_OUTPUT.get();
        if (buffer == null) {
            System.out.println(line);
        } else {
            buffer.append(line).append('\n');
        }
    }

    /**
     * Compiles the named {@code .java} files in {@code dir} with the javac
     * frontend in-process (javax.tools — the identical parse/enter/
     * analyze/generate passes the {@code javac} binary runs) instead of
     * spawning a {@code javac} process per fixture. The JVM suites compile
     * several hundred small artifact sets, and every spawned {@code javac}
     * process pays a full JVM boot plus compiler initialization before
     * parsing a single file; the in-process frontend applies the same
     * {@code -encoding UTF-8} option and the compile directory as the
     * classpath (the subprocess form ran with the same working directory
     * and default classpath {@code "."}), plus {@code -proc:none
     * -implicit:none -g:none} (the emitted artifacts carry no
     * annotations, every artifact file is listed explicitly, and no
     * fixture or pin consumes javac debug info, so the skipped passes
     * never resolve or observe anything the named set does not already
     * compile — a pure per-task startup-cost reduction, no compile
     * surface is lost). Artifact execution stays a real
     * {@code java} subprocess. Every multi-module conformance fixture
     * (including all twelve ISSUE-0109 fixtures) and JvmBackendTest's
     * artifact-acceptance pins keep the real {@code javac} binary.
     * Returns true when javac accepted every file; appends diagnostics to
     * {@code err} otherwise.
     */
    static boolean compileWithJavac(Path dir, List<String> javaFiles,
                                    StringBuilder err) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            err.append("no system Java compiler available (javax.tools)");
            return false;
        }
        DiagnosticCollector<JavaFileObject> diagnostics =
            new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler
                .getStandardFileManager(diagnostics, null,
                    StandardCharsets.UTF_8)) {
            List<File> files = new ArrayList<>();
            for (String name : javaFiles) {
                files.add(dir.resolve(name).toFile());
            }
            Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjectsFromFiles(files);
            StringWriter messages = new StringWriter();
            // -proc:none: the emitted artifacts never carry annotations,
            // so javac's default annotation-processor discovery pass is
            // pure overhead (roughly a 2-3x per-task cost on JDK 25).
            // -implicit:none: every emitted artifact file is listed
            // explicitly in {@code javaFiles}, so implicit source lookup
            // never resolves anything the named set does not already
            // compile.
            List<String> options = List.of(
                "-encoding", "UTF-8",
                "-classpath", dir.toString(),
                "-d", dir.toString(),
                "-proc:none",
                "-implicit:none",
                // No JVM fixture or artifact pin consumes javac debug
                // info (line-number tables are never asserted), so skip
                // that generation pass as well — pure per-task cost.
                "-g:none");
            Boolean ok = compiler.getTask(messages, fileManager, diagnostics,
                options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                err.append(messages);
                for (javax.tools.Diagnostic<? extends JavaFileObject> d
                        : diagnostics.getDiagnostics()) {
                    err.append(d.toString()).append('\n');
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            err.append(e.toString());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Runs one fixture case on a worker thread with its own output
     * buffer, flushed as one contiguous block under {@link #CONSOLE_LOCK}
     * when the case finishes (the ISSUE-0109 worker pattern at per-case
     * granularity).
     */
    private static void runFixtureCase(String fixtureName,
                                       Map<String, Object> test) {
        StringBuilder buffer = new StringBuilder();
        FILE_OUTPUT.set(buffer);
        try {
            runTestCase(fixtureName, test);
        } catch (Exception e) {
            log("  FAIL: error processing fixture case: " + e.getMessage());
            e.printStackTrace(System.out);
            failed.incrementAndGet();
        } finally {
            FILE_OUTPUT.remove();
            synchronized (CONSOLE_LOCK) {
                System.out.print(buffer);
            }
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
        // ISSUE-0100: 'hosts' (host modules with deal.json externals) is
        // a multi-module concept for the JVM adapter — the single-module
        // adapter has no project root, no deal.json, and no orchestrator.
        // ISSUE-0328: the JS adapter supports single-module host
        // fixtures (a bare import path per entry with an embedded
        // declaration and a JS host implementation) — any other
        // single-module hosts shape is a violation, never a silent drop.
        Object hosts = test.get("hosts");
        if (hosts != null && hosts != JSON_NULL) {
            Object source = test.get("source");
            if (source != null && source != JSON_NULL) {
                return jsSingleModuleHostsViolation(test);
            }
        }
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
     * The single-module {@code 'hosts'} shape accepted by the JS
     * adapter (ISSUE-0328): a non-empty object mapping bare externals
     * import paths to {@code { declaration, js }} entries — the
     * embedded declaration string (resolved by {@link JsHostResolver})
     * and the non-empty CommonJS host implementation the adapter
     * deploys at {@code <tmp>/<raw path>.js}. Only fixtures whose
     * {@code backends} include {@code "js"} may declare the
     * single-module hosts form; a malformed entry is a violation,
     * never a silent drop.
     */
    private static String jsSingleModuleHostsViolation(
            Map<String, Object> test) {
        Object hosts = test.get("hosts");
        if (!(hosts instanceof Map<?, ?> hostMap) || hostMap.isEmpty()) {
            return "'hosts' must be a non-empty object mapping raw "
                + "import paths to { declaration, js } host entries";
        }
        List<?> backends = (List<?>) test.getOrDefault(
            "backends", List.of());
        if (!backends.contains("js")) {
            return "'hosts' on a single-module ('source') fixture is "
                + "JS-only — 'backends' must include \"js\"";
        }
        for (Map.Entry<?, ?> he : hostMap.entrySet()) {
            String importPath = String.valueOf(he.getKey());
            if (importPath.startsWith("./")
                    || importPath.startsWith("../")) {
                return "'hosts' keys are bare externals import paths "
                    + "(the spec manifest form); got relative path '"
                    + importPath + "'";
            }
            if (!(he.getValue() instanceof Map<?, ?> hostEntry)) {
                return "'hosts' entry '" + importPath + "' must be an "
                    + "object with 'declaration' and 'js' strings";
            }
            Object declaration = hostEntry.get("declaration");
            Object js = hostEntry.get("js");
            if (!(declaration instanceof String decl)
                    || !(js instanceof String jsSrc)
                    || decl.isEmpty() || jsSrc.isEmpty()) {
                return "'hosts' entry '" + importPath + "' must have "
                    + "non-empty string 'declaration' and 'js' fields";
            }
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
        // ISSUE-0100: an optional 'hosts' object declares the fixture's
        // host modules (raw import path → { declaration, java }). The
        // declaration must be one of the 'modules' keys (a .d.deal path
        // relative to the project root, wired through a generated
        // deal.json externals entry), and the java source must be a
        // non-empty host implementation the javac stage compiles together
        // with the emitted artifacts. Bare import paths only (the spec
        // externals map form); malformed entries fail loudly, never
        // silently.
        Object hosts = test.get("hosts");
        if (hosts != null && hosts != JSON_NULL) {
            if (!(hosts instanceof Map<?, ?> hostMap) || hostMap.isEmpty()) {
                return "'hosts' must be a non-empty object mapping raw "
                    + "import paths to { declaration, java } host entries";
            }
            for (Map.Entry<?, ?> he : hostMap.entrySet()) {
                String importPath = String.valueOf(he.getKey());
                if (importPath.startsWith("./") || importPath.startsWith("../")) {
                    return "'hosts' keys are bare externals import paths "
                        + "(the spec manifest form); got relative path '"
                        + importPath + "'";
                }
                if (!(he.getValue() instanceof Map<?, ?> hostEntry)) {
                    return "'hosts' entry '" + importPath
                        + "' must be an object with 'declaration' and "
                        + "'java' strings";
                }
                Object declaration = hostEntry.get("declaration");
                Object java = hostEntry.get("java");
                if (!(declaration instanceof String decl)
                        || !(java instanceof String javaSrc)
                        || decl.isEmpty() || javaSrc.isEmpty()) {
                    return "'hosts' entry '" + importPath
                        + "' must have non-empty string 'declaration' and "
                        + "'java' fields";
                }
                if (!mods.containsKey(decl)) {
                    return "'hosts' entry '" + importPath
                        + "' declaration '" + decl
                        + "' is not a key of 'modules'";
                }
            }
        }
        return null;
    }

    /**
     * Executes one JSON test case. A case with a {@code knownFail} field
     * documents an intentionally unsupported v1.2 requirement (the value
     * is the tracked follow-up issue id): the normal assertions run, and
     * <ul>
     *   <li>when they FAIL, the requirement is still unsatisfied — the
     *       case is recorded as a KNOWN-FAIL (non-fatal, tracked);</li>
     *   <li>when they PASS, the tracked issue has landed — the gate FAILS
     *       with a promotion instruction (remove the marker), so
     *       promotion is forced.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void runTestCase(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        Object knownFail = test.get("knownFail");
        if (knownFail == null || knownFail == JSON_NULL) {
            runTestCaseInner(fixtureName, test);
            return;
        }
        String issue = String.valueOf(knownFail).trim();
        if (issue.isEmpty()) {
            log("  [" + name + "] FAIL: invalid fixture configuration: "
                + "'knownFail' must be a non-empty tracked follow-up issue id");
            failed.incrementAndGet();
            return;
        }
        if (!test.containsKey("backends")
                || ((List<?>) test.get("backends")).isEmpty()) {
            log("  [" + name + "] FAIL: invalid fixture configuration: "
                + "a 'knownFail' case must declare non-empty 'backends'");
            failed.incrementAndGet();
            return;
        }
        // Queue for evaluation on the main thread after every worker
        // fixture file finishes (see pendingKnownFail).
        pendingKnownFail.add(new Object[] { fixtureName, test });
        log("  [" + name + "] QUEUED (known-fail case; evaluated after the "
            + "main fixture files)");
    }

    /**
     * Executes one queued known-fail case on the main thread. The global
     * outcome counters are only mutated by this thread at this point, so
     * the snapshot bookkeeping is race-free: the case runs its normal
     * assertions, and
     * <ul>
     *   <li>when they FAIL, the requirement is still unsatisfied — the
     *       case is recorded as a KNOWN-FAIL (non-fatal, tracked by the
     *       {@code knownFail} issue id);</li>
     *   <li>when they PASS, the tracked issue has landed — the gate FAILS
     *       with a promotion instruction (drop the marker), so promotion
     *       is forced.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void runKnownFailCase(String fixtureName,
            Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        String issue = String.valueOf(test.get("knownFail")).trim();

        StringBuilder capture = new StringBuilder();
        StringBuilder saved = FILE_OUTPUT.get();
        FILE_OUTPUT.set(capture);
        int p0 = passed.get(), f0 = failed.get(), s0 = skipped.get();
        int lp0 = luajitPassed.get(), lf0 = luajitFailed.get();
        int jp0 = jvmPassed.get(), jf0 = jvmFailed.get();
        int sp0 = jsPassed.get(), sf0 = jsFailed.get();
        boolean stale = false;
        try {
            runTestCaseInner(fixtureName, test);
            int deltaP = passed.get() - p0;
            int deltaF = failed.get() - f0;
            int deltaS = skipped.get() - s0;
            passed.set(p0);
            failed.set(f0);
            skipped.set(s0);
            luajitPassed.set(lp0);
            luajitFailed.set(lf0);
            jvmPassed.set(jp0);
            jvmFailed.set(jf0);
            jsPassed.set(sp0);
            jsFailed.set(sf0);
            stale = (deltaF == 0 && deltaS == 0 && deltaP > 0);
        } finally {
            FILE_OUTPUT.set(saved);
        }
        if (stale) {
            log("  [" + name + "] FAIL (STALE knownFail marker: the "
                + "v1.2 requirement tracked by " + issue + " now "
                + "passes — promote the fixture: drop 'knownFail')");
            failed.incrementAndGet();
        } else {
            log("  [" + name + "] KNOWN-FAIL (v1.2 not yet "
                + "implemented; tracked by " + issue + ")");
            for (String line : capture.toString().split("\n")) {
                if (!line.isEmpty()) {
                    log("      " + line);
                }
            }
            knownFailures.incrementAndGet();
        }
    }

    @SuppressWarnings("unchecked")
    private static void runTestCaseInner(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        boolean legacyAuthority = LegacyProfileRegressionCatalog
            .isCatalogued(fixtureName + "#" + name);
        if (legacyAuthority) {
            legacyAuthorityResults.incrementAndGet();
            log("  [" + name + "] LEGACY-AUTHORITY (legacy-regression; "
                + "zero v1.2 credit)");
        } else {
            v12CreditResults.incrementAndGet();
        }
        // Parallel workers mutate the global counters concurrently, so a
        // delta snapshot here can absorb other cases' increments; the
        // legacy denominator therefore counts results only, and any
        // legacy failure still fails the global gate exactly like a
        // v1.2-credit failure.
        runTestCaseInnerImpl(fixtureName, test);
    }

    @SuppressWarnings("unchecked")
    private static void runTestCaseInnerImpl(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        String description = jsonString(test, "description", "");
        String source = jsonString(test, "source", null);
        // A5: exactly one per-case invocation from the catalog decision —
        // catalogued case -> LEGACY_REGRESSION + LEGACY_SAFE_INT; every
        // other case -> COMMON_SHADOW + DEAL_V1_2_INT32 (zero shadow
        // requests). The selected profile reaches the parser and the
        // retained Lua/JVM emitters; the JS retained route ignores it.
        SemanticProfile caseProfile = LegacyProfileRegressionCatalog
            .profileFor(fixtureName + "#" + name);
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
            log("  [" + name + "] FAIL: missing 'source' field");
            failed.incrementAndGet();
            return;
        }

        String configViolation = fixtureConfigViolation(test);
        if (configViolation == null && multiModule) {
            configViolation = multiModuleConfigViolation(test);
        }
        if (configViolation != null) {
            log("  [" + name + "] FAIL: invalid fixture "
                + "configuration: " + configViolation);
            failed.incrementAndGet();
            return;
        }

        if (multiModule) {
            // Multi-module fixtures are JVM-only (validated by
            // multiModuleConfigViolation): attribute their outcome to the
            // JVM backend-runtime gate from the case's own returned
            // outcome (never from shared-counter deltas, which parallel
            // workers mutate concurrently).
            Boolean outcome = runMultiModuleTestCase(fixtureName, test);
            if (outcome != null) {
                if (outcome) jvmPassed.incrementAndGet();
                else jvmFailed.incrementAndGet();
            }
            return;
        }

        try {
            // The single-module hosts map (ISSUE-0328): null for every
            // host-free fixture; a non-null map drives the JS adapter's
            // JsHostResolver frontend resolution and host deployment.
            Map<String, Object> hosts = jsHostsOf(test);

            // Compile with the real frontend (lexer → parser → name resolver
            // → type checker). Diagnostics are collected per phase, exactly
            // like ConformanceTest.compileAndGetDiagnostics.
            FrontendCompile fc = compileFrontend(source, "fixture-" + name + ".deal",
                "fixture-" + name + ".deal", hosts, caseProfile);

            // ---- Frontend compile-error gate (ISSUE-0091) ----
            // Rejected before any backend: this path returns before codegen,
            // javac, or java is touched.
            if (expectedCompileError != null) {
                boolean matched = fc.errors().stream()
                    .anyMatch(d -> expectedCompileError.equals(d.code()));
                boolean onlyFrontend = fc.errors().stream()
                    .allMatch(d -> !d.code().startsWith("E6"));
                if (!matched) {
                    log("  [" + name + "] FAIL: expected frontend "
                        + "compile-error " + expectedCompileError + " but got: "
                        + (fc.errors().isEmpty() ? "<no errors>"
                            : fc.errors().stream().map(CompilerDiagnostic::toString)
                                .toList()));
                    failed.incrementAndGet();
                    return;
                }
                if (!onlyFrontend) {
                    log("  [" + name + "] FAIL: error codes came "
                        + "from backend lowering, not the frontend: " + fc.errors());
                    failed.incrementAndGet();
                    return;
                }
                log("  [" + name + "] OK — compile-error "
                    + expectedCompileError + " rejected before backend"
                    + " (parser/checker only; no codegen invoked)");
                passed.incrementAndGet();
                return;
            }

            if (fc.hasErrors()) {
                log("  [" + name + "] FAIL: frontend errors: "
                    + fc.errors());
                failed.incrementAndGet();
                return;
            }

            // Check IR assertions — always done for IR assertions
            String ir = IrDumper.dump(fc.program(), fc.checkResult(), "fixture-" + name);

            List<String> irContains = (List<String>) test.getOrDefault("irContains", List.of());
            List<String> irNotContains = (List<String>) test.getOrDefault("irNotContains", List.of());

            boolean irOk = true;
            for (String needle : irContains) {
                if (!ir.contains(needle)) {
                    log("  [" + name + "] FAIL: IR should contain '" + needle + "'");
                    log("    IR:\n" + ir);
                    irOk = false;
                }
            }
            for (String needle : irNotContains) {
                if (ir.contains(needle)) {
                    log("  [" + name + "] FAIL: IR should NOT contain '" + needle + "'");
                    log("    IR:\n" + ir);
                    irOk = false;
                }
            }

            if (!irOk) {
                failed.incrementAndGet();
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
                log("  [" + name + "] OK" +
                    (description.isEmpty() ? "" : " — " + description));
                passed.incrementAndGet();
                return;
            }

            boolean appliesToLuajit = backends.contains("luajit");
            boolean appliesToJvm = backends.contains("jvm");
            boolean appliesToJs = backends.contains("js");
            boolean ranAny = false;

            if (appliesToLuajit) {
                if (luajitAvailable) {
                    if (!runBackendTracked("luajit",
                            () -> runLuaAssertions(name, source, caseProfile,
                                expectedOutput, expectedNotOutput,
                                expectedError, expectedExitCode))) {
                        return; // failure already reported
                    }
                    ranAny = true;
                }
            }

            if (appliesToJvm) {
                if (jvmAvailable) {
                    if (!runBackendTracked("jvm",
                            () -> runJvmAssertions(name, fc, caseProfile,
                                expectedOutput, expectedNotOutput,
                                expectedError, expectedExitCode))) {
                        return; // failure already reported
                    }
                    ranAny = true;
                }
            }

            if (appliesToJs) {
                if (nodeAvailable) {
                    if (!runBackendTracked("js",
                            () -> runJsAssertions(name, source, caseProfile,
                                expectedOutput, expectedNotOutput,
                                expectedError, expectedExitCode,
                                hosts))) {
                        return; // failure already reported
                    }
                    ranAny = true;
                }
            }

            if (!ranAny) {
                log("  [" + name + "] SKIP (runtime test, "
                    + "no applicable backend available)");
                skipped.incrementAndGet();
                return;
            }

            log("  [" + name + "] OK" +
                (description.isEmpty() ? "" : " — " + description));
            passed.incrementAndGet();

        } catch (Exception e) {
            log("  [" + name + "] FAIL: " + e.getMessage());
            e.printStackTrace(System.out);
            failed.incrementAndGet();
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
     *
     * <p>v1.2 entry-model note: this path checks the fixture module as a
     * STANDALONE compiled unit (the module is not the selected entry of
     * the compilation), so the spec-v1.2 entry gate (the selected entry
     * module must export non-async {@code main(): null} — the
     * orchestrator's E2010/E2011 frontend gate, with the E6004 backend
     * backstop) does not fire here and the fixtures export their scenario
     * functions directly. The selected-entry path — the entry gate, the
     * emitted {@code public static void main} invocation — is exercised by
     * the multi-module fixtures through {@link CompilationOrchestrator}
     * ({@code entry} naming the entry module) and by
     * {@code JvmBackendTest.testEntryModuleEmitsJvmEntryPoint}, which
     * compiles and runs the emitted entry point directly.
     */
    private static FrontendCompile compileFrontend(String source, String filename) {
        return compileFrontend(source, filename, filename);
    }

    /**
     * Frontend compile with an explicit checker module path (the JS
     * adapter): the NameResolver/TypeChecker module path seeds every
     * local {@code Type.Class} module path, which the JS runtime class
     * identity descriptor must match byte-for-byte (the backend seeds
     * its instance tags from its own modulePath — {@code Main} in the
     * single-source adapter), while the lexer filename keeps seeding the
     * span files. Identical pipeline to {@link #compileFrontend(String, String)};
     * no bypass.
     */
    // E9999 is the project's test-only pseudo code for a NameResolver
    // exception (the ConformanceTest precedent); it uses the deprecated
    // synthetic factory with an anchor note naming the fixture source
    // (D5/verification 6), and this suppression keeps the build
    // warning-free.
    @SuppressWarnings("deprecation")
    private static FrontendCompile compileFrontend(String source, String filename,
                                                   String modulePath) {
        return compileFrontend(source, filename, modulePath, null);
    }

    /**
     * Frontend compile with an optional host-fixture map (ISSUE-0328,
     * js-v12-host-abi-completion D1/D3): a non-null map replaces the
     * stub resolver with {@link JsHostResolver}, which resolves the
     * bare {@code host/&lt;name&gt;} imports against the fixture's
     * embedded declarations and synthesizes the declared host-class
     * symbols — the same frontend surface the production orchestrator's
     * externals resolution provides (the declared export map plus
     * class-symbol synthesis from the declaration AST). A null map
     * keeps the stub resolver (no host imports).
     */
    @SuppressWarnings("deprecation")
    private static FrontendCompile compileFrontend(String source, String filename,
                                                   String modulePath,
                                                   Map<String, Object> hosts) {
        return compileFrontend(source, filename, modulePath, hosts,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Profile-aware frontend compile (A5 seam): the per-case invocation's
     * project-wide profile reaches the parser and the host-declaration
     * parse; the profile-less overload keeps the legacy default.
     */
    @SuppressWarnings("deprecation")
    private static FrontendCompile compileFrontend(String source, String filename,
                                                   String modulePath,
                                                   Map<String, Object> hosts,
                                                   SemanticProfile profile) {
        List<CompilerDiagnostic> errors = new ArrayList<>();

        LexResult lex = new Lexer(source, filename).tokenize();
        for (CompilerDiagnostic d : lex.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (lex.hasErrors()) {
            return new FrontendCompile(null, null, null, errors);
        }

        Parser parser = new Parser(lex.tokens(), filename, profile);
        ParseResult parseResult = parser.parse();
        for (CompilerDiagnostic d : parseResult.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (parseResult.hasErrors()) {
            return new FrontendCompile(null, null, null, errors);
        }

        // Post-parse module shape validation (v1.2 module top level:
        // E1048/E1049/E1050/E1051), mirroring the orchestrator pipeline —
        // the parser alone does not reject v1.1 module shapes.
        for (CompilerDiagnostic d : ModuleShapeValidator.validate(parseResult.program(),
                filename, filename.endsWith(".d.deal"))) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }
        if (!errors.isEmpty()) {
            return new FrontendCompile(null, null, null, errors);
        }

        ModuleResolver resolver = hosts == null || hosts.isEmpty()
            ? (ModuleResolver) new StubModuleResolver()
            : new JsHostResolver(hosts, profile);
        NameResolver nr = new NameResolver(modulePath, resolver);
        SymbolTable symTable;
        try {
            symTable = nr.resolve(parseResult.program());
        } catch (Exception e) {
            errors.add(CompilerDiagnostic.synthetic("E9999", "error",
                e.getMessage(), filename,
                "missing anchor: fixture source '" + filename + "'"));
            return new FrontendCompile(null, null, null, errors);
        }
        for (CompilerDiagnostic d : nr.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }

        CheckResult result = TypeChecker.check(modulePath, symTable, nr, parseResult.program());
        for (CompilerDiagnostic d : result.diagnostics()) {
            if ("error".equals(d.severity())) {
                errors.add(d);
            }
        }

        return new FrontendCompile(parseResult.program(), result, symTable, errors);
    }

    /**
     * The JS adapter's host-fixture resolver (ISSUE-0328,
     * js-v12-host-abi-completion D1/D3): one embedded host declaration
     * per bare {@code host/&lt;name&gt;} import path, mirroring the
     * production externals surface — {@code resolveModule} returns the
     * declaration's extracted export map,
     * {@code resolveClassSymbol} synthesizes the declared host-class
     * symbols from the declaration AST (the frontend's shared
     * host-class symbol synthesis), and
     * {@code resolveTypeNodeInModule} resolves host-class field type
     * annotations against the declaring module's own context through
     * {@link ExportExtractor}.
     */
    private static final class JsHostResolver implements ModuleResolver {
        private static final class HostFixture {
            final String rawPath;
            final String dottedPath;
            final Map<String, Type> exports;
            final Map<String, Symbol.ClassSymbol> classes;
            final ExportExtractor extractor;

            HostFixture(String rawPath, String dottedPath,
                        Map<String, Type> exports,
                        Map<String, Symbol.ClassSymbol> classes,
                        ExportExtractor extractor) {
                this.rawPath = rawPath;
                this.dottedPath = dottedPath;
                this.exports = exports;
                this.classes = classes;
                this.extractor = extractor;
            }
        }

        private final Map<String, HostFixture> byRaw =
            new LinkedHashMap<>();
        private final Map<String, HostFixture> byDotted =
            new LinkedHashMap<>();

        @SuppressWarnings("deprecation")
        JsHostResolver(Map<String, Object> hosts, SemanticProfile profile) {
            for (Map.Entry<String, Object> e : hosts.entrySet()) {
                String raw = e.getKey();
                Map<?, ?> entry = (Map<?, ?>) e.getValue();
                String declaration = String.valueOf(
                    entry.get("declaration"));
                String dotted = raw.replace('/', '.');
                LexResult lex = new Lexer(declaration,
                    raw + ".d.deal").tokenize();
                ParseResult parse = new Parser(lex.tokens(),
                    raw + ".d.deal", profile).parse();
                ExportExtractor extractor = new ExportExtractor(dotted,
                    true);
                Map<String, Type> exports = extractor.extract(
                    parse.program());
                Map<String, Symbol.ClassSymbol> classes =
                    new LinkedHashMap<>();
                for (StatementNode stmt
                        : parse.program().statements()) {
                    ClassDeclaration cd = null;
                    if (stmt instanceof ClassDeclaration c) {
                        cd = c;
                    } else if (stmt instanceof ExportDeclaration ed
                            && ed.declaration()
                                instanceof ClassDeclaration c) {
                        cd = c;
                    }
                    if (cd != null) {
                        classes.put(cd.name(),
                            new Symbol.ClassSymbol(cd.name(),
                                cd.fields(), dotted));
                    }
                }
                HostFixture f = new HostFixture(raw, dotted, exports,
                    classes, extractor);
                byRaw.put(raw, f);
                byDotted.put(dotted, f);
            }
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            HostFixture f = byRaw.get(modulePath);
            if (f != null) {
                return f.exports;
            }
            throw new ModuleNotFoundException("Module not found: "
                + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            HostFixture f = byDotted.get(modulePath);
            if (f != null) {
                return f.classes.get(className);
            }
            return null;
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            HostFixture f = byDotted.get(modulePath);
            if (f != null) {
                Type resolved = f.extractor.resolveFieldType(typeNode);
                return resolved == Type.Error.INSTANCE ? null : resolved;
            }
            return null;
        }
    }

    // =========================================================================
    // Multi-module adapter (ISSUE-0096)
    // =========================================================================

    /** Result of running the real whole-project pipeline. */
    private record OrchestratorRun(boolean success,
                                   List<CompilerDiagnostic> diagnostics,
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
        return runOrchestrator(projectRoot, entryFile, outputRoot, null);
    }

    /**
     * Runs the multi-module pipeline with an explicit {@link
     * CompilationOrchestrator} configuration (ISSUE-0100): fixtures that
     * declare {@code hosts} write a {@code deal.json} whose
     * {@code externals} map wires every host import path to its
     * declaration file, so the orchestrator resolves, discovers, and
     * types host modules through the production externals path (host
     * resolution, E2009 gating, and the JVM host-module codegen map).
     * Fixtures without hosts keep the config-less pipeline (null
     * configuration, unchanged behavior).
     */
    private static OrchestratorRun runOrchestrator(Path projectRoot, Path entryFile,
                                                    Path outputRoot,
                                                    DealConfig config) {
        return runOrchestrator(projectRoot, entryFile, outputRoot, config,
            null);
    }

    /**
     * Runs the multi-module pipeline through the orchestrator's FULL
     * constructor with the per-case selected invocation (A5 seam): the
     * catalogued cases pass the LEGACY_REGRESSION + LEGACY_SAFE_INT
     * invocation, every other case COMMON_SHADOW + DEAL_V1_2_INT32 with
     * zero shadow-module requests (the A1-updated planner guard admits
     * the invocation at phase 3.7 and F4 rule 5 leaves every module on
     * the retained LEGACY route). A null invocation delegates to the
     * config-only orchestrator constructor, which keeps the release
     * default invocation (the existing production-path callers).
     */
    private static OrchestratorRun runOrchestrator(Path projectRoot, Path entryFile,
                                                    Path outputRoot,
                                                    DealConfig config,
                                                    CompilerInvocation invocation) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // The global stream swap is serialized against worker flushes
        // (CONSOLE_LOCK): a parallel fixture file's output block must
        // never land inside another file's orchestrator capture. The
        // original streams are captured INSIDE the lock: reading them
        // outside could observe another worker's swapped capture and
        // then "restore" that dead buffer as the console, permanently
        // redirecting every later flush into a buffer nobody prints
        // (observed as silently missing fixture output blocks).
        synchronized (CONSOLE_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try {
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator = invocation == null
                    ? new CompilationOrchestrator(
                        entryFile.toAbsolutePath().normalize(),
                        outputRoot.toAbsolutePath().normalize(),
                        false, true, false, Backend.JVM,
                        config, List.of(projectRoot.toAbsolutePath().normalize()),
                        null)
                    : new CompilationOrchestrator(
                        entryFile.toAbsolutePath().normalize(),
                        outputRoot.toAbsolutePath().normalize(),
                        false, true, false, false, Backend.JVM,
                        config, List.of(projectRoot.toAbsolutePath().normalize()),
                        null, null, invocation);
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
        return parseEntryProgram(entryFile, SemanticProfile.LEGACY_SAFE_INT);
    }

    /** Profile-aware entry parse (A5 seam): the per-case profile the
     * orchestrator invocation selected drives the runner's export list. */
    private static ProgramNode parseEntryProgram(Path entryFile,
            SemanticProfile profile) throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(), entryFile.toString(),
            profile).parse();
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
    private static boolean assertRuntimeContract(String name, String output, int actualExitCode,
                                        Object expectedOutput, List<String> expectedNotOutput,
                                        Object expectedError, Object expectedExitCode) {
        if (expectedOutput != null && expectedOutput != JSON_NULL) {
            String expStr = String.valueOf(expectedOutput);
            if (!output.contains(expStr)) {
                log("  [" + name + "] FAIL: expected output '"
                    + expStr + "', got: " + output);
                failed.incrementAndGet();
                return false;
            }
        }

        if (expectedError != null && expectedError != JSON_NULL) {
            String expErr = String.valueOf(expectedError);
            if (!output.contains("DEAL_ERROR_CODE: " + expErr)) {
                log("  [" + name + "] FAIL: expected error '"
                    + expErr + "', got: " + output);
                failed.incrementAndGet();
                return false;
            }
        }

        for (String forbidden : expectedNotOutput) {
            if (output.contains(forbidden)) {
                log("  [" + name + "] FAIL: output must NOT "
                    + "contain '" + forbidden + "', got: " + output);
                failed.incrementAndGet();
                return false;
            }
        }

        if (expectedExitCode != null && expectedExitCode != JSON_NULL) {
            int expCode = ((Number) expectedExitCode).intValue();
            if (actualExitCode != expCode) {
                log("  [" + name + "] FAIL: expected exit code "
                    + expCode + ", got " + actualExitCode);
                log("    Output: " + output);
                failed.incrementAndGet();
                return false;
            }
        }
        return true;
    }

    /**
     * The real multi-module JVM path (ISSUE-0096): orchestrator compile of
     * every module → IR dump assertions → javac over every emitted
     * {@code .java} artifact plus a runner → {@code java} execution of the
     * emitted artifacts → stdout/error/exit-code assertions. Every stage
     * must genuinely run: a bypassed parser/checker/module-discovery
     * yields a failed compile, a bypassed codegen leaves no {@code .java}
     * artifact (asserted before javac), and a bypassed JVM execution
     * produces no output.
     *
     * <p>Two {@code expectedCompileError} gates run here. A FRONTEND
     * expectation asserts the named frontend error, asserts no E6 backend
     * code appears, and asserts no {@code .java} artifact at all (the
     * gate stops before codegen). A BACKEND expectation (an E6 code —
     * ISSUE-0098's E6000 cross-module function-value rejections) runs the
     * same orchestrator pipeline, asserts the named E6000 (any other E6
     * code fails the gate), and asserts the rejected ENTRY module wrote
     * no artifact (the orchestrator's two-pass design may still write
     * clean imported modules' artifacts, so the artifact pin targets the
     * entry) — never an artifact javac rejects after the CLI reported
     * success.
     *
     * <p>Every multi-module fixture compiles its artifact set with the
     * real {@code javac} binary unconditionally (a
     * {@code ProcessBuilder("javac", ...)} — there is no per-fixture
     * opt-in; single-module fixtures use the javac frontend in-process
     * via {@link #compileWithJavac}). Both forms apply the same
     * {@code -encoding UTF-8} and {@code -proc:none} options, compile
     * the identical artifact-set shape, and verify the produced
     * {@code .class} artifacts.
     */
    /**
     * Runs one backend assertion group and attributes the pass/fail
     * outcome to the named backend gate ({@code luajit} / {@code jvm}).
     */
    private static boolean runBackendTracked(String backend,
            java.util.function.BooleanSupplier task) {
        boolean ok = task.getAsBoolean();
        switch (backend) {
            case "luajit" -> {
                if (ok) luajitPassed.incrementAndGet();
                else luajitFailed.incrementAndGet();
            }
            case "jvm" -> {
                if (ok) jvmPassed.incrementAndGet();
                else jvmFailed.incrementAndGet();
            }
            default -> {
                if (ok) jsPassed.incrementAndGet();
                else jsFailed.incrementAndGet();
            }
        }
        return ok;
    }

    @SuppressWarnings("unchecked")
    /**
     * Runs one multi-module (JVM-only) fixture. Returns {@code TRUE}
     * when the case passed, {@code FALSE} when it failed, and
     * {@code null} when it was skipped (no JVM gate attribution).
     * The caller attributes the outcome to the JVM backend-runtime
     * gate from this return value, never from shared-counter deltas:
     * parallel workers increment the shared counters concurrently,
     * so a delta snapshot can absorb other cases' increments and
     * make the per-backend totals nondeterministic.
     */
    private static Boolean runMultiModuleTestCase(String fixtureName, Map<String, Object> test) {
        String name = jsonString(test, "name", "<unnamed>");
        String description = jsonString(test, "description", "");
        String entry = jsonString(test, "entry", null);
        // A5: the same per-case catalog decision drives the orchestrator's
        // full constructor and the entry-program parse.
        CompilerInvocation invocation = LegacyProfileRegressionCatalog
            .invocationFor(fixtureName + "#" + name);
        SemanticProfile caseProfile = invocation.semanticProfile();
        Map<String, Object> modulesObj = (Map<String, Object>) test.get("modules");
        String expectedCompileError = jsonString(test, "expectedCompileError", null);
        Object hostsObj = test.get("hosts");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> hosts =
            (hostsObj != null && hostsObj != JSON_NULL)
                ? (Map<String, Map<String, String>>) (Map<?, ?>) hostsObj
                : Map.<String, Map<String, String>>of();

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_backend_conf_mm_");
            Map<String, Path> written = writeModuleFiles(projectRoot, modulesObj);
            Path entryFile = written.get(entry);
            Path outputRoot = projectRoot.resolve("out");

            // ISSUE-0100: host fixtures write a real deal.json whose
            // externals map (spec map form: import path → manifest-relative
            // declaration) drives production host resolution, discovery,
            // typing, and the JVM host-module codegen map. The declaration
            // paths are relative to the project root (the manifest
            // directory), exactly like the modules map keys.
            DealConfig config = null;
            if (!hosts.isEmpty()) {
                StringBuilder dealJson = new StringBuilder();
                dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
                dealJson.append("  \"externals\": {\n");
                boolean first = true;
                for (Map.Entry<String, Map<String, String>> he
                        : hosts.entrySet()) {
                    if (!first) dealJson.append(",\n");
                    first = false;
                    dealJson.append("    \"").append(he.getKey())
                        .append("\": { \"declaration\": \"")
                        .append(he.getValue().get("declaration"))
                        .append("\" }");
                }
                dealJson.append("\n  }\n}\n");
                Files.writeString(projectRoot.resolve("deal.json"), dealJson);
                DealConfig.DealConfigParseResult configResult =
                    DealConfig.load(projectRoot);
                config = configResult.config();
                if (config == null || !configResult.diagnostics().isEmpty()) {
                    log("  [" + name + "] FAIL: generated deal.json did "
                        + "not load");
                    failed.incrementAndGet();
                    return false;
                }
            }

            // ---- Frontend compile-error gate (orchestrator-based) ----
            // The whole pipeline runs; the named frontend error must appear,
            // no E6xxx backend-lowering code may appear, and no .java
            // artifact may exist (codegen never ran). A bypassed
            // parser/checker/module-discovery produces no such diagnostic
            // and the fixture fails.
            if (expectedCompileError != null) {
                OrchestratorRun run = runOrchestrator(projectRoot, entryFile,
                    outputRoot, config, invocation);
                boolean matched = run.diagnostics().stream()
                    .anyMatch(d -> expectedCompileError.equals(d.code()));
                // An E6 expectation is a backend-rejection fixture
                // (ISSUE-0098 cross-module function values): the named
                // E6000 IS the expected diagnostic, so it is admitted —
                // any OTHER E6 code still fails the gate. Frontend-error
                // fixtures keep the strict no-E6 rule.
                boolean backendCode = expectedCompileError.startsWith("E6");
                boolean onlyExpectedCodes = run.diagnostics().stream()
                    .allMatch(d -> !d.code().startsWith("E6")
                        || expectedCompileError.equals(d.code()));
                if (run.success()) {
                    log("  [" + name + "] FAIL: expected "
                        + (backendCode ? "backend rejection "
                            : "frontend compile-error ")
                        + expectedCompileError
                        + " but the project compiled successfully");
                    failed.incrementAndGet();
                    return false;
                }
                if (!matched) {
                    log("  [" + name + "] FAIL: expected "
                        + (backendCode ? "backend rejection "
                            : "frontend compile-error ")
                        + expectedCompileError + " but got: "
                        + (run.diagnostics().isEmpty() ? "<no errors>"
                            : run.diagnostics().stream().map(CompilerDiagnostic::toString)
                                .toList()));
                    failed.incrementAndGet();
                    return false;
                }
                if (!onlyExpectedCodes) {
                    log("  [" + name + "] FAIL: error codes came "
                        + "from an unexpected phase (only '"
                        + expectedCompileError + "' may appear): "
                        + run.diagnostics());
                    failed.incrementAndGet();
                    return false;
                }
                if (backendCode) {
                    // The REJECTED entry module must write no artifact
                    // (the orchestrator's two-pass design may still write
                    // CLEAN imported modules' artifacts, so the artifact
                    // pin targets the entry — the module whose codegen
                    // recorded the rejection).
                    Path entryJava = outputRoot.resolve(
                        entryClassName(entry) + ".java");
                    if (Files.exists(entryJava)) {
                        log("  [" + name + "] FAIL: the "
                            + "backend-rejection gate produced the entry "
                            + "artifact '" + entryJava.getFileName()
                            + "' (codegen ran for the rejected module): "
                            + run.capturedOutput());
                        failed.incrementAndGet();
                        return false;
                    }
                } else {
                    boolean artifactWritten = Files.isDirectory(outputRoot);
                    if (artifactWritten) {
                        try (var stream = Files.walk(outputRoot)) {
                            artifactWritten = stream.anyMatch(
                                p -> p.toString().endsWith(".java"));
                        }
                    }
                    if (artifactWritten) {
                        log("  [" + name + "] FAIL: the compile-error "
                            + "gate produced .java artifacts (codegen ran): "
                            + run.capturedOutput());
                        failed.incrementAndGet();
                        return false;
                    }
                }
                log("  [" + name + "] OK — "
                    + (backendCode ? "backend rejection "
                        : "compile-error ")
                    + expectedCompileError
                    + (backendCode
                        ? " (orchestrator pipeline; no entry artifact written)"
                        : " rejected before backend"
                            + " (orchestrator pipeline; no codegen invoked)"));
                passed.incrementAndGet();
                return true;
            }

            // ---- Runtime fixture: the whole project must compile. ----
            OrchestratorRun run = runOrchestrator(projectRoot, entryFile,
                outputRoot, config, invocation);
            if (!run.success()) {
                log("  [" + name + "] FAIL: orchestrator compile "
                    + "failed: " + run.diagnostics() + "\n"
                    + run.capturedOutput());
                failed.incrementAndGet();
                return false;
            }

            // IR assertions over every module's IR dump.
            String ir = collectIrDumps(outputRoot);
            List<String> irContains = (List<String>) test.getOrDefault("irContains", List.of());
            List<String> irNotContains = (List<String>) test.getOrDefault("irNotContains", List.of());
            boolean irOk = true;
            for (String needle : irContains) {
                if (!ir.contains(needle)) {
                    log("  [" + name + "] FAIL: IR should contain '"
                        + needle + "'");
                    log("    IR:\n" + ir);
                    irOk = false;
                }
            }
            for (String needle : irNotContains) {
                if (ir.contains(needle)) {
                    log("  [" + name + "] FAIL: IR should NOT "
                        + "contain '" + needle + "'");
                    log("    IR:\n" + ir);
                    irOk = false;
                }
            }
            if (!irOk) {
                failed.incrementAndGet();
                return false;
            }

            Object expectedOutput = test.get("expectedOutput");
            Object expectedError = test.get("expectedError");
            Object expectedExitCode = test.get("expectedExitCode");
            boolean hasRuntimeAssertions =
                (expectedOutput != null && expectedOutput != JSON_NULL)
                || (expectedError != null && expectedError != JSON_NULL)
                || (expectedExitCode != null && expectedExitCode != JSON_NULL);

            if (!hasRuntimeAssertions) {
                log("  [" + name + "] OK" +
                    (description.isEmpty() ? "" : " — " + description));
                passed.incrementAndGet();
                return true;
            }

            if (!jvmAvailable) {
                log("  [" + name + "] SKIP (multi-module runtime "
                    + "test, javac/java unavailable)");
                skipped.incrementAndGet();
                return null;
            }

            // Codegen was real: the entry artifact must exist before javac.
            String entryClass = entryClassName(entry);
            Path entryJava = outputRoot.resolve(entryClass + ".java");
            if (!Files.exists(entryJava)) {
                log("  [" + name + "] FAIL: JVM codegen produced "
                    + "no '" + entryJava.getFileName() + "' artifact");
                failed.incrementAndGet();
                return false;
            }

            // ISSUE-0100: host implementation classes. The backend derives
            // each host module's Java class name from its module path
            // (host/http → HostHttp, the same classNameFor rule every
            // emitted module uses), and the runtime load check
            // Class.forName's that name — the fixture's 'java' source
            // therefore normally declares exactly that class. The javac
            // stage compiles the host classes together with the emitted
            // artifacts and the execution classpath exposes them — the
            // harness analog of the user placing the host implementation
            // on the compile/runtime classpath. The .java file name is
            // derived from the class the source actually declares, so a
            // fixture that deliberately declares a DIFFERENT class (the
            // jvm-host-missing-host-class fixture) still compiles and
            // exercises the runtime E8011 for an unloadable host class.
            for (Map.Entry<String, Map<String, String>> he
                    : hosts.entrySet()) {
                String javaSrc = he.getValue().get("java");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?:^|\\s)class\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                    .matcher(javaSrc);
                if (!m.find()) {
                    log("  [" + name + "] FAIL: host implementation for '"
                        + he.getKey() + "' declares no top-level class");
                    failed.incrementAndGet();
                    return false;
                }
                Files.writeString(outputRoot.resolve(m.group(1) + ".java"),
                    javaSrc);
            }

            // Runner: auto-invokes the entry module's zero-arity exports,
            // driven by the real parser's export list.
            ProgramNode entryProgram = parseEntryProgram(entryFile, caseProfile);
            Path runnerFile = outputRoot.resolve("JvmConformanceRunner.java");
            Files.writeString(runnerFile, buildJvmRunner(entryProgram, entryClass));

            // javac over every emitted .java artifact plus the runner.
            List<String> javacArgs = new ArrayList<>();
            javacArgs.add("javac");
            javacArgs.add("-encoding");
            javacArgs.add("UTF-8");
            // Same fidelity-pin binary javac; -proc:none skips the
            // annotation-processor discovery pass (no emitted artifact
            // carries an annotation — the identical argument as the
            // in-process compileWithJavac form).
            javacArgs.add("-proc:none");
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
                log("  [" + name + "] FAIL: javac failed (exit "
                    + javacExit + "):\n" + javacOutput);
                failed.incrementAndGet();
                return false;
            }
            if (!Files.exists(outputRoot.resolve(entryClass + ".class"))
                    || !Files.exists(outputRoot.resolve("JvmConformanceRunner.class"))) {
                log("  [" + name + "] FAIL: javac exited 0 but no "
                    + ".class artifacts were produced (JVM compilation bypassed)");
                failed.incrementAndGet();
                return false;
            }

            // Execute the emitted artifacts with java.
            ProcessBuilder pb2 = new ProcessBuilder("java", "-cp",
                outputRoot.toString(), "JvmConformanceRunner");
            pb2.directory(outputRoot.toFile());
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            String output = new String(p2.getInputStream().readAllBytes()).trim();
            int actualExitCode = p2.waitFor();

            if (!assertRuntimeContract(name, output, actualExitCode, expectedOutput,
                    List.of(), expectedError, expectedExitCode)) {
                return false; // failure already reported
            }

            log("  [" + name + "] OK" +
                (description.isEmpty() ? "" : " — " + description));
            passed.incrementAndGet();
            return true;

        } catch (Exception e) {
            log("  [" + name + "] FAIL: multi-module JVM "
                + "execution exception: " + e.getMessage());
            e.printStackTrace(System.out);
            failed.incrementAndGet();
            return false;
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
                                            SemanticProfile profile,
                                            Object expectedOutput,
                                            List<String> expectedNotOutput,
                                            Object expectedError,
                                            Object expectedExitCode) {
        String lua = generateLua(source, "fixture-" + name + ".deal", profile);
        if (lua == null) {
            log("  [" + name + "] FAIL: codegen failed");
            failed.incrementAndGet();
            return false;
        }

        boolean isErrorTest = (expectedError != null && expectedError != JSON_NULL);
        ExecutionResult execResult = executeLua(lua, isErrorTest);
        if (execResult == null) {
            log("  [" + name + "] FAIL: Lua execution returned null");
            failed.incrementAndGet();
            return false;
        }
        String output = execResult.output();
        int actualExitCode = execResult.exitCode();

        if (expectedOutput != null && expectedOutput != JSON_NULL) {
            String expStr = String.valueOf(expectedOutput);
            if (!output.contains(expStr)) {
                log("  [" + name + "] FAIL: expected output '" +
                    expStr + "', got: " + output);
                failed.incrementAndGet();
                return false;
            }
        }

        if (expectedError != null && expectedError != JSON_NULL) {
            String expErr = String.valueOf(expectedError);
            if (!output.contains("DEAL_ERROR_CODE: " + expErr)) {
                log("  [" + name + "] FAIL: expected error '" +
                    expErr + "', got: " + output);
                failed.incrementAndGet();
                return false;
            }
        }

        for (String forbidden : expectedNotOutput) {
            if (output.contains(forbidden)) {
                log("  [" + name + "] FAIL: output must NOT "
                    + "contain '" + forbidden + "', got: " + output);
                failed.incrementAndGet();
                return false;
            }
        }

        // Assert expected exit code
        if (expectedExitCode != null && expectedExitCode != JSON_NULL) {
            int expCode = ((Number) expectedExitCode).intValue();
            if (actualExitCode != expCode) {
                log("  [" + name + "] FAIL: expected exit code " +
                    expCode + ", got " + actualExitCode);
                log("    Output: " + output);
                failed.incrementAndGet();
                return false;
            }
        }
        return true;
    }

    private static String generateLua(String source, String filename,
            SemanticProfile profile) {
        try {
            LexResult lex = new Lexer(source, filename).tokenize();
            if (lex.hasErrors()) return null;

            Parser parser = new Parser(lex.tokens(), filename, profile);
            ParseResult parseResult = parser.parse();
            if (parseResult.hasErrors()) return null;

            StubModuleResolver resolver = new StubModuleResolver();
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable = nr.resolve(parseResult.program());
            CheckResult result = TypeChecker.check(filename, symTable, nr, parseResult.program());
            if (result.hasErrors()) return null;

            return LuaBackend.generate(parseResult.program(), result, filename,
                profile);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // JVM adapter (ISSUE-0091)
    // =========================================================================

    /**
     * The real JVM path: DEAL frontend (already done) → {@link JvmBackend}
     * codegen → javac (in-process frontend) → {@code java} subprocess →
     * assert. Every stage must genuinely run: a bypassed codegen leaves no
     * artifact for javac, and a bypassed JVM execution produces no output.
     */
    private static boolean runJvmAssertions(String name, FrontendCompile fc,
                                            SemanticProfile profile,
                                            Object expectedOutput,
                                            List<String> expectedNotOutput,
                                            Object expectedError,
                                            Object expectedExitCode) {
        // 1. Codegen with the real JVM backend. Errors (E6000 for
        //    out-of-skeleton constructs) fail the fixture.
        JvmBackend.JvmCodegenResult res = JvmBackend.generate(
            fc.program(), fc.checkResult(), "fixture-" + name + ".deal",
            "Main", profile);
        if (res.hasErrors()) {
            log("  [" + name + "] FAIL: JVM codegen diagnostics: "
                + res.diagnostics());
            failed.incrementAndGet();
            return false;
        }
        if (!res.source().contains("class " + res.className())) {
            log("  [" + name + "] FAIL: JVM codegen produced no '"
                + res.className() + "' class declaration");
            failed.incrementAndGet();
            return false;
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("deal_backend_conf_jvm_");
            Path moduleFile = tmpDir.resolve(res.className() + ".java");
            Files.writeString(moduleFile, res.source());

            Path runnerFile = tmpDir.resolve("JvmConformanceRunner.java");
            Files.writeString(runnerFile, buildJvmRunner(fc.program(), res.className()));

            // 2. Compile the artifact with the javac frontend in-process
            // (compileWithJavac — the identical passes the `javac` binary
            // runs; ISSUE-0109 gate-time work, the single-module JVM
            // fixtures number in the hundreds and each spawned process
            // paid a JVM boot). Every multi-module conformance fixture
            // (including all twelve ISSUE-0109 fixtures) still compiles
            // with the real `javac` binary below.
            StringBuilder javacErr = new StringBuilder();
            boolean javacOk = compileWithJavac(tmpDir,
                List.of(res.className() + ".java", "JvmConformanceRunner.java"),
                javacErr);
            if (!javacOk) {
                log("  [" + name + "] FAIL: javac failed:\n" + javacErr);
                failed.incrementAndGet();
                return false;
            }
            if (!Files.exists(tmpDir.resolve(res.className() + ".class"))
                    || !Files.exists(tmpDir.resolve("JvmConformanceRunner.class"))) {
                log("  [" + name + "] FAIL: javac accepted the artifact but no "
                    + ".class files were produced (JVM compilation bypassed)");
                failed.incrementAndGet();
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
            return assertRuntimeContract(name, output, actualExitCode, expectedOutput,
                expectedNotOutput, expectedError, expectedExitCode);
        } catch (Exception e) {
            log("  [" + name + "] FAIL: JVM execution exception: "
                + e.getMessage());
            e.printStackTrace(System.out);
            failed.incrementAndGet();
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
                if (fd.params().isEmpty()) {
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
        sb.append("            reportError(e);\n");
        sb.append("        } catch (ExceptionInInitializerError e) {\n");
        sb.append("            // Class initialization (module-level statements) threw a\n");
        sb.append("            // DEAL error wrapped in a LinkageError — whether the class\n");
        sb.append("            // was initialized by an auto-invocation or by the\n");
        sb.append("            // Class.forName path. reportError unwraps it so the\n");
        sb.append("            // DEAL_ERROR_CODE contract holds for module-load errors in\n");
        sb.append("            // every fixture.\n");
        sb.append("            reportError(e);\n");
        sb.append("        } catch (Throwable e) {\n");
        sb.append("            reportError(e);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    // A DEAL error raised inside an IMPORTED module is that module's\n");
        sb.append("    // own nested DealError class (each emitted module declares its\n");
        sb.append("    // own), so it is not an instance of the entry module's DealError\n");
        sb.append("    // and no catch above names its type. Every emitted DealError is a\n");
        sb.append("    // RuntimeException subclass whose simple name is \"DealError\" with\n");
        sb.append("    // a package-private String `code` field; the reflection unwrap\n");
        sb.append("    // reports the DEAL code and message uniformly, exactly like the\n");
        sb.append("    // LuaJIT runtime reports errors from any module (ISSUE-0109\n");
        sb.append("    // cross-module nominal-check fixtures pin the E8001 shape).\n");
        sb.append("    private static void reportError(Throwable e) {\n");
        sb.append("        Throwable t = e;\n");
        sb.append("        while (t instanceof ExceptionInInitializerError && t.getCause() != null) {\n");
        sb.append("            t = t.getCause();\n");
        sb.append("        }\n");
        sb.append("        String code = null;\n");
        sb.append("        if (\"DealError\".equals(t.getClass().getSimpleName())) {\n");
        sb.append("            try {\n");
        sb.append("                java.lang.reflect.Field f = t.getClass().getDeclaredField(\"code\");\n");
        sb.append("                f.setAccessible(true);\n");
        sb.append("                code = String.valueOf(f.get(t));\n");
        sb.append("            } catch (ReflectiveOperationException ignored) { }\n");
        sb.append("        }\n");
        sb.append("        if (code != null) {\n");
        sb.append("            System.out.println(\"DEAL_ERROR_CODE: \" + code + \" \" + t.getMessage());\n");
        sb.append("        } else {\n");
        sb.append("            System.out.println(\"DEAL_ERROR_CODE: \"\n");
        sb.append("                + (t.getMessage() == null ? t.toString() : t.getMessage()));\n");
        sb.append("        }\n");
        sb.append("        System.exit(1);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isNullReturnType(TypeNode t) {
        return t instanceof NamedType nt && "null".equals(nt.name());
    }

    // =========================================================================
    // JS adapter (js-backend-conformance-e2e D1)
    // =========================================================================

    /**
     * The real JS path: DEAL frontend (re-run with the adapter's checker
     * module path {@code Main}, so every local class identity descriptor
     * matches the backend's instance tags byte-for-byte — the backend
     * seeds {@code $classname} from its modulePath, and the checker seeds
     * {@code Type.Class.modulePath()} from its own; the two must agree) →
     * {@link JsBackend} codegen → temp-dir deployment of the emitted
     * {@code Main.js} plus {@code deal/runtime.js} and the six
     * {@code std/*.js} (the same files the orchestrator deploys,
     * js-backend-architecture D8) → a generated {@code JsConformanceRunner.js}
     * requiring the module, skipping {@code $}-named exports (compiler-
     * generated helper exports such as {@code C$new}), auto-invoking the
     * zero-arity exported wrappers in declaration order, printing non-null
     * results, and awaiting thenable results (async exports resolve before
     * printing/rejection — the JS analog of the JVM runner's blocking
     * invocation) → {@code node} subprocess execution → the shared
     * assertion contract ({@code DEAL_ERROR_CODE: <code>} stderr + exit 1).
     * Every stage must genuinely run: a bypassed codegen leaves no
     * {@code Main.js} artifact (asserted), and a bypassed node execution
     * produces no output.
     */
    /**
     * The host declared map for one fixture's single-module hosts
     * (ISSUE-0328): per raw import path, the declaration's extracted
     * export map plus, per class export name, the declared field
     * records with their {@link ExportExtractor}-resolved types — the
     * same gather shape the orchestrator's
     * {@code codegenAllJs} builds for externals declarations.
     */
    @SuppressWarnings("deprecation")
    private static Map<String, HostModuleDeclarations> jsHostModulesOf(
            Map<String, Object> hosts) {
        Map<String, HostModuleDeclarations> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : hosts.entrySet()) {
            String raw = e.getKey();
            Map<?, ?> entry = (Map<?, ?>) e.getValue();
            String declaration = String.valueOf(entry.get("declaration"));
            String dotted = raw.replace('/', '.');
            LexResult lex = new Lexer(declaration,
                raw + ".d.deal").tokenize();
            ParseResult parse = new Parser(lex.tokens(),
                raw + ".d.deal").parse();
            ExportExtractor extractor = new ExportExtractor(dotted, true);
            Map<String, Type> exports = extractor.extract(parse.program());
            Map<String, List<HostModuleDeclarations.HostField>>
                classFields = new LinkedHashMap<>();
            for (StatementNode stmt : parse.program().statements()) {
                ClassDeclaration cd = null;
                if (stmt instanceof ClassDeclaration c) {
                    cd = c;
                } else if (stmt instanceof ExportDeclaration ed
                        && ed.declaration()
                            instanceof ClassDeclaration c) {
                    cd = c;
                }
                if (cd == null) continue;
                List<HostModuleDeclarations.HostField> fields =
                    new ArrayList<>();
                for (ClassField cf : cd.fields()) {
                    fields.add(new HostModuleDeclarations.HostField(cf,
                        extractor.resolveFieldType(cf.type())));
                }
                classFields.putIfAbsent(cd.name(), fields);
            }
            result.put(raw,
                new HostModuleDeclarations(exports, classFields));
        }
        return result;
    }

    /** The single-module hosts map of one fixture, or null: the
     * {@code hosts} object cast once (ISSUE-0328 — the
     * fixtureConfigViolation gate already validated the shape). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsHostsOf(Map<String, Object> test) {
        Object hosts = test.get("hosts");
        if (!(hosts instanceof Map<?, ?> m)) {
            return null;
        }
        return (Map<String, Object>) m;
    }

    private static boolean runJsAssertions(String name, String source,
                                           SemanticProfile profile,
                                           Object expectedOutput,
                                           List<String> expectedNotOutput,
                                           Object expectedError,
                                           Object expectedExitCode,
                                           Map<String, Object> hosts) {
        // The checker-module-path alignment re-runs the real frontend
        // pipeline (the same lexer → parser → resolver → checker stages)
        // with modulePath "Main"; it never re-checks backend decisions and
        // cannot act as a checker bypass (the shared fc already ran the
        // compile-error gate and the IR assertions).
        FrontendCompile jsFc = compileFrontend(source, "fixture-" + name + ".deal",
            "Main", hosts, profile);
        if (jsFc.hasErrors()) {
            log("  [" + name + "] FAIL: JS frontend errors: " + jsFc.errors());
            failed.incrementAndGet();
            return false;
        }

        // 1. Codegen with the real JS backend (modulePath "Main", the
        //    single-source adapter convention). Errors (E6003 for
        //    out-of-skeleton constructs) fail the fixture. A host
        //    fixture goes through the production seam with the
        //    per-compilation identity surface (the host modules
        //    classified as externals), so the declared map renders the
        //    canonical @$external identities; the plain adapter path
        //    stays on the standalone seam.
        Map<String, HostModuleDeclarations> hostModules = new LinkedHashMap<>();
        JsBackend.JsCodegenResult res;
        if (hosts == null || hosts.isEmpty()) {
            res = JsBackend.generate(
                jsFc.program(), jsFc.checkResult(), "fixture-" + name + ".deal",
                "Main", Map.of(), Map.of(), false);
        } else {
            hostModules = jsHostModulesOf(hosts);
            Map<String, CanonicalModuleIdentity> byPath =
                new LinkedHashMap<>();
            byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
            byPath.put("Main", new CanonicalModuleIdentity.ProjectModule(
                new ProjectModuleIdentity("Main", "Main", List.of())));
            for (String raw : hosts.keySet()) {
                byPath.put(raw.replace('/', '.'),
                    new CanonicalModuleIdentity.ExternalModule(raw));
            }
            ModuleIdentityResolver.IdentityIndex index =
                ModuleIdentityResolver.buildIndex(byPath);
            res = JsBackend.generate(
                jsFc.program(), jsFc.checkResult(),
                "fixture-" + name + ".deal", "Main", Map.of(),
                hostModules, false, index, index.moduleIdentityLookup(),
                null, SemanticProfile.LEGACY_SAFE_INT);
        }
        if (res.hasErrors()) {
            log("  [" + name + "] FAIL: JS codegen diagnostics: "
                + res.diagnostics());
            failed.incrementAndGet();
            return false;
        }
        // Artifact-presence assertion: the canonical CommonJS capture
        // lines must exist in the generated source (the JVM
        // .class-presence analog).
        if (!res.source().contains("\"use strict\"")
                || !res.source().contains("const $require = require; "
                    + "const $module = module; const $exports = exports;")) {
            log("  [" + name + "] FAIL: JS codegen produced no CommonJS "
                + "module capture (use strict / $require/$module/$exports)");
            failed.incrementAndGet();
            return false;
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("deal_backend_conf_js_");
            Path moduleFile = tmpDir.resolve("Main.js");
            Files.writeString(moduleFile, res.source());
            if (!Files.exists(moduleFile)) {
                log("  [" + name + "] FAIL: JS codegen produced no "
                    + "'Main.js' artifact");
                failed.incrementAndGet();
                return false;
            }

            // 2. Deploy the runtime and the six stdlib .js modules (the
            //    same files the orchestrator's copyJsRuntimeLibrary/
            //    copyStdlibJsModules deploy).
            deployJsSupport(tmpDir);

            // 2b. Host deployment (ISSUE-0328,
            //     js-v12-host-abi-completion D6 analog): every host
            //     implementation lands at <tmpDir>/<raw path>.js — the
            //     file the emitted relative require resolves.
            if (hosts != null) {
                for (Map.Entry<String, Object> e : hosts.entrySet()) {
                    Map<?, ?> entry = (Map<?, ?>) e.getValue();
                    String jsSource = String.valueOf(entry.get("js"));
                    Path hostFile = tmpDir.resolve(e.getKey() + ".js");
                    Files.createDirectories(hostFile.getParent());
                    Files.writeString(hostFile, jsSource);
                }
            }

            // 3. The runner: require the module, skip $-named exports,
            //    auto-invoke zero-arity exported wrappers in declaration
            //    order, print non-null results, await thenables, and map
            //    uncaught errors to the shared DEAL_ERROR_CODE contract.
            Files.writeString(tmpDir.resolve("JsConformanceRunner.js"),
                buildJsRunner(jsFc.program()));

            // 4. Execute the deployed artifact set with node.
            ProcessBuilder pb = new ProcessBuilder("node",
                "JsConformanceRunner.js");
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int actualExitCode = p.waitFor();

            // 5. Assertions — same observable contract as the LuaJIT/JVM
            //    runners.
            return assertRuntimeContract(name, output, actualExitCode,
                expectedOutput, expectedNotOutput, expectedError,
                expectedExitCode);
        } catch (Exception e) {
            log("  [" + name + "] FAIL: JS execution exception: "
                + e.getMessage());
            e.printStackTrace(System.out);
            failed.incrementAndGet();
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
     * Deploys the JS runtime support files a single-module fixture
     * needs: {@code deal/runtime.js} and the six spec stdlib
     * {@code std/*.js} modules — the same file set the orchestrator's
     * JS deployment copies ship ({@code copyJsRuntimeLibrary}/
     * {@code copyStdlibJsModules}).
     */
    static void deployJsSupport(Path dir) throws IOException {
        Path runtimeDir = dir.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.js"), runtimeDir.resolve("runtime.js"));

        Path stdDir = dir.resolve("std");
        Files.createDirectories(stdDir);
        for (String stdlibModule : StdlibModuleResolver.SPEC_STDLIB_MODULES) {
            Path src = Path.of(stdlibModule + ".js");
            if (Files.exists(src)) {
                Files.copy(src, stdDir.resolve(
                    stdlibModule.substring(4) + ".js"));
            }
        }
    }

    /**
     * Builds the JS conformance runner source for a fixture: requires the
     * emitted module and the runtime, iterates the module's own export
     * keys in declaration order (the {@code $rt.setProp} insertion
     * order), SKIPS {@code $}-named exports (compiler-generated helper
     * exports such as the hidden {@code C$new} closures — the Lua
     * harness's {@code $}-skip rule of the runner builders,
     * test/ConformanceTest.java), auto-invokes the remaining zero-arity
     * exported wrappers (the zero-arity set derived from the real
     * parser's export list) in declaration order, prints each non-null
     * result with Node's default formatting, and AWAITS thenable results
     * before printing/rejection (async exports resolve before
     * assertions — the JS analog of the JVM runner's blocking
     * invocation). A thrown error or a rejected Promise routes through
     * {@code $rt.reportUncaught} into the shared
     * {@code DEAL_ERROR_CODE: <code> <message>} stderr + exit-1 contract;
     * a module-load error (an initialization-time throw inside the
     * required module) takes the same contract because the require sits
     * inside the guarded async body.
     *
     * <p>Cross-backend invocation contract — identical asymmetry rules to
     * the JVM runner ({@link #buildJvmRunner}): result formatting is
     * Node-default and backend-dependent, fixtures with multiple
     * zero-arity exports must not depend on cross-backend invocation
     * order, and cross-backend fixtures must route every observable
     * output through {@code std/console}.
     */
    static String buildJsRunner(ProgramNode program) {
        List<String> zeroAry = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd
                    && fd.params().isEmpty()
                    && !fd.name().contains("$")) {
                zeroAry.add(fd.name());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by deal.test.BackendConformanceTest — JS conformance runner.\n");
        sb.append("// Requires the emitted module, skips $-named exports, auto-invokes\n");
        sb.append("// zero-arity exported wrappers in declaration order, prints non-null\n");
        sb.append("// results, and awaits thenable results before asserting.\n");
        sb.append("\"use strict\";\n");
        sb.append("const $rt = require(\"./deal/runtime\");\n");
        sb.append("const $zeroAry = [");
        for (int i = 0; i < zeroAry.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(zeroAry.get(i)).append("\"");
        }
        sb.append("];\n");
        sb.append("(async () => {\n");
        sb.append("  const $mod = require(\"./Main\");\n");
        sb.append("  for (const $k of Object.keys($mod)) {\n");
        sb.append("    if ($k.indexOf(\"$\") !== -1) { continue; }\n");
        sb.append("    const $v = $mod[$k];\n");
        sb.append("    if ($v && $v.$kind === \"function\" "
            + "&& $zeroAry.indexOf($k) !== -1) {\n");
        sb.append("      const $r = await $v.$f();\n");
        sb.append("      if ($r !== null && $r !== $rt.undefined) {\n");
        sb.append("        console.log($r);\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n");
        // The catch mirrors the entry shim's location-embedding body
        // (js-backend-architecture D7): a located error composes its
        // file/line/column into the message before reportUncaught, so
        // fixtures observe the same DEAL_ERROR_CODE line the production
        // entry shim prints.
        sb.append("})().catch((e) => {\n");
        sb.append("  const $err = $rt.reifyError(e);\n");
        sb.append("  $rt.reportUncaught($err.file !== $rt.undefined\n");
        sb.append("    ? $rt.errorValue($err.code, $err.message + \" at \" + $err.file\n");
        sb.append("        + \":\" + $err.line + \":\" + $err.column, $err.file,\n");
        sb.append("        $err.line, $err.column)\n");
        sb.append("    : $err);\n");
        sb.append("});\n");
        return sb.toString();
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
