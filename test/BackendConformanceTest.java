package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.lua.LuaBackend;
import deal.module.CompilationOrchestrator;
import deal.module.ModuleShapeValidator;
import deal.module.DealConfig;
import deal.ir.IrDumper;
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
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Loads backend-neutral JSON fixture tests from
 * {@code test/conformance/fixtures/} and executes them against the
 * LuaJIT backend and (ISSUE-0091, ISSUE-0092, ISSUE-0093, ISSUE-0094,
 * ISSUE-0096, ISSUE-0097) the JVM backend. The
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
 * artifacts), and the ISSUE-0108 nullable-slice fixtures live in

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

    private static final AtomicInteger passed = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    private static final AtomicInteger skipped = new AtomicInteger();
    private static final AtomicInteger knownFailures = new AtomicInteger();
    private static final AtomicInteger luajitPassed = new AtomicInteger();
    private static final AtomicInteger luajitFailed = new AtomicInteger();
    private static final AtomicInteger jvmPassed = new AtomicInteger();
    private static final AtomicInteger jvmFailed = new AtomicInteger();
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

        System.out.println("=== Backend Conformance Test — DEAL v1.2 "
            + "(LuaJIT + JVM backend-runtime gates) ===");
        System.out.println("LuaJIT: " + (luajitAvailable ? "available" :
            "NOT available (runtime tests will be skipped)"));
        System.out.println("JVM (javac + java): " + (jvmAvailable ? "available" :
            "NOT available (JVM runtime tests will be skipped)"));
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

        // ISSUE-0109 gate-time work: fixture files are independent
        // temp-root projects, so they run in parallel worker threads —
        // sequential execution kept the suite over the gate's wall-clock
        // budget because every JVM fixture spawns a javac + java pair.
        // Each file buffers its output and flushes it as one contiguous
        // block (FILE_OUTPUT / CONSOLE_LOCK), so parallel files never
        // interleave lines and the per-test evidence stays readable.
        int workers = Math.max(1,
            Math.min(Runtime.getRuntime().availableProcessors(),
                fixtureFiles.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path file : fixtureFiles) {
                futures.add(pool.submit(() -> runFixtureFile(file)));
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
        System.out.println("=== DEAL v1.2 Backend-Runtime Gates ===");
        int luajitTotal = luajitPassed.get() + luajitFailed.get();
        System.out.println("  LuaJIT backend-runtime: " + luajitPassed.get()
            + "/" + luajitTotal + " passed, " + luajitFailed.get() + " failed");
        int jvmTotal = jvmPassed.get() + jvmFailed.get();
        System.out.println("  JVM backend-runtime: " + jvmPassed.get()
            + "/" + jvmTotal + " passed, " + jvmFailed.get() + " failed");
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
     * and default classpath {@code "."}). Artifact execution stays a real
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
            List<String> options = List.of(
                "-encoding", "UTF-8",
                "-classpath", dir.toString(),
                "-d", dir.toString());
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
    private static void runFixtureFile(Path file) {
        StringBuilder buffer = new StringBuilder();
        FILE_OUTPUT.set(buffer);
        try {
            log("--- Fixture: " + file.getFileName() + " ---");

            try {
                String raw = Files.readString(file);
                Map<String, Object> root = (Map<String, Object>) parseJson(raw);

                Object version = root.get("version");
                if (!"1.0".equals(String.valueOf(version))) {
                    log("  FAIL: unsupported version: " + version);
                    failed.incrementAndGet();
                    return;
                }

                List<Map<String, Object>> tests =
                    (List<Map<String, Object>>) root.get("tests");
                if (tests == null) {
                    log("  FAIL: no 'tests' array in fixture");
                    failed.incrementAndGet();
                    return;
                }

                for (Map<String, Object> test : tests) {
                    runTestCase(file.getFileName().toString(), test);
                }
            } catch (Exception e) {
                log("  FAIL: error processing fixture: " + e.getMessage());
                e.printStackTrace(System.out);
                failed.incrementAndGet();
            }
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
        // a multi-module concept — the single-module adapter has no
        // project root, no deal.json, and no orchestrator, so a
        // single-module fixture declaring hosts would silently drop them.
        Object hosts = test.get("hosts");
        if (hosts != null && hosts != JSON_NULL) {
            Object source = test.get("source");
            if (source != null && source != JSON_NULL) {
                return "'hosts' requires the multi-module 'modules' form; "
                    + "a single-module ('source') fixture cannot declare "
                    + "host modules";
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
                    log("  [" + name + "] FAIL: expected frontend "
                        + "compile-error " + expectedCompileError + " but got: "
                        + (fc.errors().isEmpty() ? "<no errors>"
                            : fc.errors().stream().map(Diagnostic::toString)
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
            boolean ranAny = false;

            if (appliesToLuajit) {
                if (luajitAvailable) {
                    if (!runBackendTracked("luajit",
                            () -> runLuaAssertions(name, source, expectedOutput,
                                expectedNotOutput, expectedError,
                                expectedExitCode))) {
                        return; // failure already reported
                    }
                    ranAny = true;
                }
            }

            if (appliesToJvm) {
                if (jvmAvailable) {
                    if (!runBackendTracked("jvm",
                            () -> runJvmAssertions(name, fc, expectedOutput,
                                expectedNotOutput, expectedError,
                                expectedExitCode))) {
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

        // Post-parse module shape validation (v1.2 module top level:
        // E1048/E1049/E1050/E1051), mirroring the orchestrator pipeline —
        // the parser alone does not reject v1.1 module shapes.
        for (Diagnostic d : ModuleShapeValidator.validate(parseResult.program(),
                filename, filename.endsWith(".d.deal"))) {
            if ("error".equals(d.severity())) errors.add(d);
        }
        if (!errors.isEmpty()) {
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
                CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                    entryFile.toAbsolutePath().normalize(),
                    outputRoot.toAbsolutePath().normalize(),
                    false, true, false, Backend.JVM,
                    config, List.of(projectRoot.toAbsolutePath().normalize()), null);
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
     * every module → IR dump assertions → {@code javac} over every emitted
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
     */
    /**
     * Runs one backend assertion group and attributes the pass/fail
     * outcome to the named backend gate ({@code luajit} / {@code jvm}).
     */
    private static boolean runBackendTracked(String backend,
            java.util.function.BooleanSupplier task) {
        boolean ok = task.getAsBoolean();
        if ("luajit".equals(backend)) {
            if (ok) luajitPassed.incrementAndGet();
            else luajitFailed.incrementAndGet();
        } else {
            if (ok) jvmPassed.incrementAndGet();
            else jvmFailed.incrementAndGet();
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
                config = DealConfig.load(projectRoot);
                if (config == null) {
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
                    outputRoot, config);
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
                            : run.diagnostics().stream().map(Diagnostic::toString)
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
                outputRoot, config);
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

            if (!assertJvmRun(name, output, actualExitCode, expectedOutput,
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
                                            Object expectedOutput,
                                            List<String> expectedNotOutput,
                                            Object expectedError,
                                            Object expectedExitCode) {
        String lua = generateLua(source, "fixture-" + name + ".deal");
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
            return assertJvmRun(name, output, actualExitCode, expectedOutput,
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
