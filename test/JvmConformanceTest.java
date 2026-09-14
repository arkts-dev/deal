package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.codegen.jvm.JvmBackend;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.module.ExportExtractor;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.module.StdlibModuleResolver;
import deal.parser.*;
import deal.types.Type;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM conformance promotion gate (ISSUE-0102), running the activated
 * DEAL v1.2 signed-int32 route (ISSUE-0378 D1).
 *
 * <p>Runs every existing backend-runtime conformance test
 * ({@code test/conformance/backend-runtime/}) against the JVM backend
 * with a deterministic applicability policy and a real whole-project
 * pipeline: module discovery → parsing → signature extraction →
 * dependency ordering → name resolution → type checking → per-module
 * {@link JvmBackend} codegen (via {@link CompilationOrchestrator} with
 * {@link Backend#JVM}) → {@code javac} over every emitted artifact →
 * {@code java} execution of the emitted classes. A bypassed
 * parser/checker/module-discovery yields no orchestrator success; a
 * bypassed codegen leaves no {@code .java} artifact (asserted before
 * javac); a bypassed JVM execution produces no output and no exit code
 * (asserted against the captured subprocess output).
 *
 * <h2>The lane-wide activated invocation (ISSUE-0378 D1)</h2>
 *
 * <p>Every on-disk backend-runtime fixture compiles through the single
 * lane-wide activated invocation {@link #LANE_INVOCATION} — the
 * explicit
 * {@code CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
 * CapabilityRegistry.releaseRegistry())} invocation (PUBLIC_BUILD +
 * {@code DEAL_V1_2_INT32}, release state {@code V1_2_ACTIVE}) —
 * passed through the full {@link CompilationOrchestrator} constructor.
 * The lane carries zero per-fixture catalog-seam call sites and
 * zero legacy-authority labeling: the profile-authority accounting is
 * pinned at 0 legacy-authority fixtures, and the per-fixture catalog
 * seam stays where it belongs — the untouched legacy harness
 * {@code deal.test.BackendConformanceTest} (this lane only validates
 * the catalog at startup; it never routes a fixture through it).
 *
 * <h2>Closed gate state (ISSUE-0307: zero skips, 100% denominator)</h2>
 *
 * <p>The completion gate is closed: every applicable backend-runtime
 * fixture passes the real pipeline and the skip registry is RETIRED —
 * removed, never retained as empty machinery
 * (jvm-v12-completion-architecture D3). The last four gap families
 * closed with their dispositions: the Error literal default filling
 * (the builtin {@code code}/{@code message} defaults to {@code ""},
 * spec-v1.2 §Error type — rtc-015-error-default-code passes), the
 * Error-typed nullable catch-probe local and catch-block assignment
 * (plan-phase-order-provided-before-defaults passes), the host export
 * used as a first-class function value (the per-export shared wrapper
 * carrier keeps the identical E8010/E8001 boundary checks —
 * host-async-shape-value passes as its pinned runtime-error E8010),
 * and the eight ISSUE-0504 host-boundary fixtures (the boxed int/
 * Integer carrier class literals match the declared host shapes under
 * DEAL_V1_2_INT32 — every host-boundary fixture passes). ISSUE-0548
 * (the sync bytes-function shape closure) then lands the six sync
 * bytes-function fixtures plus their companion (bytes-sync-fn-shapes,
 * bytes-fn-adapters, bytes-fn-adapter-e8010, bytes-identity-equality,
 * bytes-nested-fn-shapes, bytes-fn-xmod): the wrapper shapes, the
 * prefix adapters, the E8010 check position, the reference-identity
 * equality, and the bytes-bearing class-field function slots all pass
 * the real pipeline on the closed gate — zero skips, zero entries. The
 * adapter live-rebinding criterion splits by surface: the module-level
 * binding reassignment stays a LuaJIT-only reference pin
 * ({@code jvm-bytes-lua-ref-reassigned-adapter} in
 * {@code jvm-bytes-slice.json} — the adapter re-reads the chunk local
 * live, so {@code id = stamp} retargets it) because the JVM backend
 * retains the reason-bearing E6000 for that reassignment, never a
 * bytes reason (pinned in {@code JvmBackendTest
 * .testRecursiveBytesClosureLane}), while the reachable class-field
 * re-read surface ({@code box.cb = picker(); box.cb(...)}) passes the
 * real pipeline in bytes-nested-fn-shapes.deal. The
 * lane runs directly from {@code run_tests.sh} on every gate run.</p>
 *
 * <h2>Classification policy (deterministic, documented)</h2>
 *
 * <ol>
 *   <li><b>Frontend-classified files</b> ({@code compile-ok} /
 *       {@code compile-error CODE} anywhere under the corpus): run the
 *       shared frontend pipeline (lexer → parser → name resolver → type
 *       checker) and must pass 100% — they are backend-neutral and never
 *       counted in the JVM backend-runtime denominator (the same
 *       classification the LuaJIT harness applies).</li>
 *   <li><b>Companions</b> ({@code @expected: companion}): classified
 *       support modules. They are compiled as part of the transitive
 *       module graph of every test that imports them (never counted
 *       separately); their standalone compilation stays enforced by the
 *       shared {@code deal.test.ConformanceTest} gate.</li>
 *   <li><b>Known-fail</b> ({@code @expected: known-fail MODE}): the
 *       intentionally unsupported v1.2 cases tracked by their
 *       {@code @issue}. The runner executes the underlying mode
 *       through the real JVM pipeline and records a non-fatal tracked
 *       KNOWN-FAIL while the case still fails; when it starts passing,
 *       the gate FAILS with a promotion instruction (drop the marker)
 *       — promotion is forced. The corpus carries zero known-fail
 *       markers on the JVM lane today (the int32/bytes promotions
 *       landed with their lanes).</li>
 *   <li><b>Backend-runtime tests</b> ({@code runtime-ok} /
 *       {@code runtime-error CODE}): every on-disk runtime-classified
 *       fixture is JVM-applicable and must pass through the whole
 *       pipeline. There is NO skip classification: the classifier has
 *       no registry and no fallback skip branch, so a SKIPPED outcome
 *       is impossible by construction — the gate asserts the zero count
 *       and the gate's evidence owner pins the registry-less source.
 *       Zero skips by construction, never empty skip machinery.</li>
 * </ol>
 *
 * <h2>Gates</h2>
 * <ul>
 *   <li>frontend-classified files: 100% pass (zero failed);</li>
 *   <li>backend-runtime: zero applicable failures AND 100% of the
 *       on-disk backend-runtime denominator (the per-run
 *       {@code runtimeDenominator()} count — 389: the closed 354
 *       plus the six ISSUE-0548 sync bytes-function fixtures
 *       (bytes-sync-fn-shapes, bytes-fn-adapters,
 *       bytes-fn-adapter-e8010, bytes-identity-equality,
 *       bytes-nested-fn-shapes, bytes-fn-xmod) plus the six
 *       ISSUE-0550 dynamic bytes-boundary fixtures:
 *       bytes-dynamic-boundary-ok, bytes-dynamic-wrong-kind-e8001,
 *       bytes-dynamic-nested-first-element-e8003,
 *       bytes-dynamic-function-mismatch-e8010,
 *       bytes-dynamic-nullable-function-ok, and
 *       bytes-dynamic-async-function-mismatch-e8010 — plus the
 *       fifteen runtime-classified FFI fixtures ISSUE-0507 landed,
 *       every one passing as the sanctioned compile-reject E6006
 *       FFI_UNSUPPORTED_BACKEND divergence — plus the seven
 *       ISSUE-0551 host/module/JSON bytes fixtures:
 *       host-bytes-roundtrip, host-bytes-nullable-roundtrip,
 *       host-bytes-no-call-on-failure,
 *       host-bytes-param-mismatch-e8010,
 *       host-bytes-return-mismatch-e8010, bytes-module-identity, and
 *       stdlib/json/json-stringify-nested-bytes-error — plus the
 *       ISSUE-0552 integrated-verification fixture
 *       (bytes-class-default-integration: nested bytes[] and
 *       bytes[][] defaults, sync/async first-class function defaults,
 *       once-per-attempt evaluation with zero load-time runs, fresh
 *       isolated buffers, retained host-returned identity,
 *       validation failure, and JSON rejection)) passing through
 *       the frontend → CompilationOrchestrator → JVM codegen → javac →
 *       JVM pipeline — zero skipped, zero stale known-fail markers;</li>
 *   <li>the classified runtime total equals the on-disk denominator
 *       (a missing or deferred runtime fixture fails the gate);</li>
 *   <li>zero probe runner exceptions (a probe crash is never silent
 *       evidence — retained while the known-fail mechanism exists);</li>
 *   <li>the runner exits non-zero when any gate fails.</li>
 * </ul>
 */
public class JvmConformanceTest {

    private static final int DEFAULT_JOBS = 1;

    // =========================================================================
    // The lane-wide activated invocation (ISSUE-0378 D1)
    // =========================================================================

    /**
     * The single lane-wide activated invocation: every on-disk
     * backend-runtime fixture compiles through this exact invocation
     * via the full {@link CompilationOrchestrator} constructor. The
     * public release-state derivation (PUBLIC_BUILD +
     * {@code DEAL_V1_2_INT32}, release state {@code V1_2_ACTIVE}) is
     * the activated backend's sanctioned harness surface until the
     * release-owned public cutover; no per-fixture catalog seam and no
     * legacy-authority routing exist in this lane (the untouched
     * {@code deal.test.BackendConformanceTest} owns that seam).
     */
    private static final CompilerInvocation LANE_INVOCATION =
        CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());

    // =========================================================================
    // Applicability policy (ISSUE-0307 gate closure): no skip registry
    // =========================================================================
    // The skip registry is RETIRED — removed, never retained as empty
    // machinery (jvm-v12-completion-architecture D3). The classifier has
    // no registry and no fallback skip branch, so a SKIPPED outcome is
    // impossible by construction: every runtime-classified on-disk
    // fixture is APPLICABLE and must pass the real pipeline. The gate
    // report asserts the zero skip count.

    // =========================================================================
    // Host modules for the JVM-applicable host-ABI corpus tests
    // =========================================================================

    /**
     * Java host implementations for the host-ABI corpus tests whose
     * declared surfaces the landed JVM host ABI slice supports. Each
     * class name derives from the raw module path with the same
     * {@code classNameFor} rule every emitted module uses
     * (host/bad_return → HostBad_return); methods take the JVM-mapped
     * parameter types and return Object (or a CompletableFuture for
     * async exports), exactly like the jvm-host-abi-slice.json hosts.
     */
    private static final Map<String, String> HOST_JAVA = Map.ofEntries(
        Map.entry("async_bad",
            "import java.util.concurrent.CompletableFuture;\n"
                + "public final class HostAsync_bad {\n"
                + "  public static Object fetchValue() {\n"
                + "    return CompletableFuture.completedFuture(Long.valueOf(42L));\n"
                + "  }\n"
                + "}\n"),
        Map.entry("async_ok",
            "import java.util.concurrent.CompletableFuture;\n"
                + "public final class HostAsync_ok {\n"
                + "  public static Object fetchValue() {\n"
                + "    return CompletableFuture.completedFuture(\"fetched\");\n"
                + "  }\n"
                + "}\n"),
        Map.entry("async_shape_bad",
            "public final class HostAsync_shape_bad {\n"
                + "  public static Object fetchValue() {\n"
                + "    return Long.valueOf(42L);\n"
                + "  }\n"
                + "}\n"),
        Map.entry("bad_return",
            "public final class HostBad_return {\n"
                + "  public static Object getNumber() {\n"
                + "    return \"not a number\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("empty_return",
            "public final class HostEmpty_return {\n"
                + "  public static Object ping() {\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("extra_export",
            "public final class HostExtra_export {\n"
                + "  public static Object ping() {\n"
                + "    return \"pong\";\n"
                + "  }\n"
                + "  public static Object extra() {\n"
                + "    return \"undeclared\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("missing_export",
            "public final class HostMissing_export {\n"
                + "  public static Object ping() {\n"
                + "    return \"pong\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("bad_string",
            "public final class HostBad_string {\n"
                + "  public static Object badString() {\n"
                + "    return \"a\\uD800b\";\n"
                + "  }\n"
                + "  public static Object surrogateString() {\n"
                + "    return \"\\uD800\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("boundary",
            "public final class HostBoundary {\n"
                + "  private static int nextValue;\n"
                + "\n"
                + "  public static Object intValue() { return Integer.valueOf(17); }\n"
                + "  public static Object stringValue() { return \"host\"; }\n"
                + "  public static Object nullableString(boolean flag) { return flag ? \"host\" : null; }\n"
                + "  public static Object nullValue() { return null; }\n"
                + "  public static Object nextValue() { nextValue += 1; return Integer.valueOf(nextValue); }\n"
                + "  public static Object echoInt(int value) { return Integer.valueOf(value); }\n"
                + "  public static Object echoNumber(double value) { return Double.valueOf(value); }\n"
                + "  public static Object echoBoolean(boolean value) { return Boolean.valueOf(value); }\n"
                + "  public static Object echoString(String value) { return value; }\n"
                + "  public static Object nullableInt(Integer value) { return value; }\n"
                + "  public static Object extraExport() { return \"ignored\"; }\n"
                + "}\n"),
        Map.entry("nullreturn_bad",
            "public final class HostNullreturn_bad {\n"
                + "  public static Object ping() {\n"
                + "    return \"junk\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullreturn_ok",
            "public final class HostNullreturn_ok {\n"
                + "  public static Object ping() {\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullable_return",
            "public final class HostNullable_return {\n"
                + "  public static Object find(String s) {\n"
                + "    if (\"__BAD__\".equals(s)) return Long.valueOf(42L);\n"
                + "    if (\"__NULL__\".equals(s)) return null;\n"
                + "    return s;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("array_return",
            "public final class HostArray_return {\n"
                + "  public static Object split(String s) {\n"
                + "    return new $DealRt.__StringArray(new String[] {\"a\", \"b\", \"c\"});\n"
                + "  }\n"
                + "}\n"),
        Map.entry("rest_join",
            "public final class HostRest_join {\n"
                + "  public static Object join(String sep, $DealRt.__StringArray parts) {\n"
                + "    return String.join(sep, parts.data);\n"
                + "  }\n"
                + "}\n"),
        Map.entry("boundary_apply",
            "public final class HostBoundary_apply {\n"
                + "  public static Object apply($DealRt.Fn1_I_R_I f, int v) {\n"
                + "    return Integer.valueOf(f.invoke(v) + 100);\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullable_fn",
            "public final class HostNullable_fn {\n"
                + "  public static Object register($DealRt.Fn1_I_R_I cb) {\n"
                + "    if (cb == null) return Integer.valueOf(0);\n"
                + "    return Integer.valueOf(cb.invoke(41));\n"
                + "  }\n"
                + "}\n"),
        Map.entry("nullable_fn_return",
            "public final class HostNullable_fn_return {\n"
                + "  public static Object getCallback(String mode) {\n"
                + "    if (\"bad\".equals(mode)) {\n"
                + "      java.util.function.IntUnaryOperator raw = (x) -> x;\n"
                + "      return raw;\n"
                + "    }\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("cfg",
            "public final class HostCfg {\n"
                + "  public static final java.util.Map<String, Object> Endpoint_defaults =\n"
                + "      java.util.Map.of(\"path\", \"/\");\n"
                + "  public static final java.util.Map<String, Object> ServerConfig_defaults =\n"
                + "      java.util.Map.of(\"port\", Integer.valueOf(8080),\n"
                + "          \"endpoint\", new Object(), \"tags\", new Object(),\n"
                + "          \"note\", new Object());\n"
                + "  public static Object describe($DealRt.$Host$host$scfg$ServerConfig s) {\n"
                + "    return s.endpoint.path + \":\" + s.port;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("presence",
            "public final class HostPresence {\n"
                + "  public static final java.util.Map<String, Object> Config_defaults =\n"
                + "      java.util.Map.of(\"port\", Integer.valueOf(8080));\n"
                + "  public static Object ping() {\n"
                + "    return \"pong\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("prewrapped_ok",
            "public final class HostPrewrapped_ok {\n"
                + "  public static Object greet(String name) {\n"
                + "    return \"hello \" + name;\n"
                + "  }\n"
                + "  public static Object ping() {\n"
                + "    return null;\n"
                + "  }\n"
                + "}\n"),
        Map.entry("prewrapped_bad",
            "public final class HostPrewrapped_bad {\n"
                + "  public static Object ping() {\n"
                + "    return \"junk\";\n"
                + "  }\n"
                + "}\n"),
        Map.entry("planprobe",
            "final class HostPlanprobe {\n"
                + "  private static long n = 0L;\n"
                + "  public static Object nextValue() {\n"
                + "    n = n + 1;\n"
                + "    return Long.valueOf(n * 10L);\n"
                + "  }\n"
                + "  public static Object valueCount() {\n"
                + "    return Long.valueOf(n);\n"
                + "  }\n"
                + "}\n"),
        Map.entry("bytes_roundtrip",
            "public final class HostBytes_roundtrip {\n"
                + "  private static int calls;\n"
                + "  private static $DealRt.Bytes shared = new $DealRt.Bytes(new byte[2]);\n"
                + "  public static Object echoBytes($DealRt.Bytes b) { calls += 1; return b; }\n"
                + "  public static Object nullableBytes($DealRt.Bytes b) { calls += 1; return b; }\n"
                + "  public static Object makeBytes(int n) { calls += 1; return new $DealRt.Bytes(new byte[n]); }\n"
                + "  public static Object sharedBytes() { return shared; }\n"
                + "  public static int readByte($DealRt.Bytes b) { calls += 1; return b.data[0] & 0xFF; }\n"
                + "  public static int callCount() { return calls; }\n"
                + "  public static Object badBytesReturn() { calls += 1; return \"not-bytes\"; }\n"
                + "}\n")
    );


    // =========================================================================
    // Data types and counters
    // =========================================================================

    private record TestFile(Path path, String relativePath, String spec,
            String description, String expected, String features,
            String issue) {}

    private enum Kind {
        /** compile-ok / compile-error — backend-neutral frontend gate. */
        FRONTEND,
        /** runtime-ok / runtime-error — JVM-applicable backend test.
         *  ISSUE-0307 gate closure: the skip classification no longer
         *  exists — the registry was retired and the classifier has no
         *  fallback skip branch, so every runtime-classified on-disk
         *  fixture is APPLICABLE (zero skips by construction). */
        APPLICABLE,
        /** known-fail MODE — tracked follow-up issue; run + stale-checked. */
        KNOWN_FAIL
    }

    private record Classified(TestFile test, Kind kind, String expectedCode,
            String knownFailIssue) {}

    private record Outcome(TestFile test, Classified classified,
            boolean pass, String message) {}

    private static final AtomicInteger frontendTotal = new AtomicInteger();
    private static final AtomicInteger frontendPassed = new AtomicInteger();
    private static final AtomicInteger frontendFailed = new AtomicInteger();
    private static final AtomicInteger applicableTotal = new AtomicInteger();
    private static final AtomicInteger applicablePassed = new AtomicInteger();

    private static final AtomicInteger applicableFailed = new AtomicInteger();
    private static final AtomicInteger knownFailTotal = new AtomicInteger();
    private static final AtomicInteger knownFailTracked = new AtomicInteger();
    private static final AtomicInteger knownFailStale = new AtomicInteger();
    private static final AtomicInteger probeHarnessFailed =
        new AtomicInteger();

    private static final List<Outcome> outcomes =
        Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> knownFailByIssue =
        Collections.synchronizedMap(new LinkedHashMap<>());

    private static final ThreadLocal<StringBuilder> WORKER_OUTPUT =
        new ThreadLocal<>();
    private static final Object CONSOLE_LOCK = new Object();

    private static boolean jvmAvailable;

    /**
     * Strict no-skip mode (release-r0-r3-strict-gate-mechanics S3;
     * release-pipeline-strict-mode-and-evidence D2(c)): when the gate
     * scripts export DEAL_STRICT=1, the runner JVM inherits it and the
     * known-fail recording statement below is a hard gate failure
     * instead of tracked evidence. Dev mode leaves the flag unset and
     * records exactly as before. (The skip-recording seam was retired
     * with the registry — ISSUE-0307: the classifier has no skip
     * branch at all.)
     */
    private static final boolean STRICT_MODE =
        System.getenv("DEAL_STRICT") != null;
    private static Path conformanceRoot = Path.of("test/conformance/")
        .toAbsolutePath().normalize();
    private static Path hostFixturesRoot =
        conformanceRoot.resolve("host-fixtures");

    private static void log(String line) {
        StringBuilder buffer = WORKER_OUTPUT.get();
        if (buffer == null) {
            System.out.println(line);
        } else {
            buffer.append(line).append('\n');
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            conformanceRoot = Path.of(args[0]).toAbsolutePath().normalize();
            hostFixturesRoot = conformanceRoot.resolve("host-fixtures");
        }
        if (!Files.isDirectory(conformanceRoot)) {
            System.out.println("No conformance root found at " + conformanceRoot);
            System.exit(2);
        }

        jvmAvailable = probeJvm();

        System.out.println("=== DEAL v1.2 JVM Conformance Suite (ISSUE-0102 origin — ISSUE-0168 capability accounting) ===");
        System.out.println("Root: " + conformanceRoot);
        System.out.println("JVM (javac + java): " + (jvmAvailable ? "available"
            : "NOT available (backend-runtime tests will fail — a bypassed "
                + "JVM execution is not a pass)"));
        System.out.println();

        List<TestFile> discovered = discoverTests();
        for (TestFile test : discovered) {
            ConformanceHarnessMetadata.profileFromFile(test.path(),
                test.relativePath());
        }
        List<Classified> tests = classifyAll(discovered);
        int frontend = (int) tests.stream()
            .filter(c -> c.kind() == Kind.FRONTEND).count();
        int applicable = (int) tests.stream()
            .filter(c -> c.kind() == Kind.APPLICABLE).count();
        int knownFail = (int) tests.stream()
            .filter(c -> c.kind() == Kind.KNOWN_FAIL).count();
        System.out.println("Discovered " + tests.size() + " conformance "
            + "test(s): " + frontend + " frontend-classified, "
            + applicable + " JVM-applicable backend-runtime, "
            + knownFail + " known-fail (tracked)");
        System.out.println();

        // Deterministic execution order: sorted by corpus-relative path.
        List<Classified> ordered = new ArrayList<>(tests);
        ordered.sort(Comparator.comparing(c -> c.test().relativePath()));

        int workers = Math.max(1, Math.min(
            Integer.getInteger("deal.test.jobs", DEFAULT_JOBS),
            ordered.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Classified classified : ordered) {
                futures.add(pool.submit(() -> runOne(classified)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<Outcome> sorted = new ArrayList<>(outcomes);
        sorted.sort(Comparator.comparing(o -> o.test().relativePath()));
        printReport(sorted, applicable + knownFail);
    }

    /** Probes that both {@code javac} and {@code java} are invocable and
     * functional — the same gate BackendConformanceTest uses. */
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
    // Discovery and classification
    // =========================================================================

    private static List<TestFile> discoverTests() throws IOException {
        Path backendRoot = conformanceRoot.resolve("backend-runtime");
        List<TestFile> result = new ArrayList<>();
        try (var stream = Files.walk(backendRoot)) {
            stream.filter(p -> p.toString().endsWith(".deal"))
                  .sorted()
                  .forEach(p -> {
                      TestFile tf = parseMetadata(p);
                      if (tf != null) {
                          result.add(tf);
                      }
                  });
        }
        return result;
    }

    private static TestFile parseMetadata(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            String spec = "";
            String description = "";
            String expected = "";
            String features = "";
            String issue = "";

            int linesToScan = Math.min(lines.size(), 40);
            for (int i = 0; i < linesToScan; i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("// @spec:")) {
                    spec = line.substring("// @spec:".length()).trim();
                } else if (line.startsWith("// @description:")) {
                    description = line.substring(
                        "// @description:".length()).trim();
                } else if (line.startsWith("// @expected:")) {
                    expected = line.substring("// @expected:".length()).trim();
                } else if (line.startsWith("// @features:")) {
                    features = line.substring(
                        "// @features:".length()).trim();
                } else if (line.startsWith("// @issue:")) {
                    issue = line.substring("// @issue:".length()).trim();
                }
            }

            if (expected.isEmpty()) {
                throw new IllegalStateException("no @expected tag in "
                    + file + " — the v1.2 gate has no unclassified files");
            }

            Path rel = conformanceRoot.relativize(file);
            String relPath = rel.toString().replace('\\', '/');
            return new TestFile(file, relPath, spec, description, expected,
                features, issue);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * The deterministic applicability policy. See the class javadoc.
     * ISSUE-0307 gate closure: there is no skip classification — the
     * registry was retired and no fallback skip branch exists, so every
     * runtime-classified on-disk fixture is APPLICABLE (zero skips by
     * construction, never empty skip machinery).
     */
    private static List<Classified> classifyAll(List<TestFile> tests) {
        List<Classified> result = new ArrayList<>();
        for (TestFile test : tests) {
            String expected = test.expected();
            if (expected.equals("companion")) {
                continue; // classified support module; compiled transitively
            }
            if (expected.startsWith("known-fail ")) {
                String mode = expected.substring("known-fail ".length())
                    .trim();
                String code = mode.startsWith("runtime-error ")
                    ? mode.substring("runtime-error ".length()).trim() : "";
                if (test.issue().isEmpty()) {
                    throw new IllegalStateException("known-fail without "
                        + "@issue in " + test.relativePath());
                }
                result.add(new Classified(test, Kind.KNOWN_FAIL, code,
                    test.issue()));
            } else if (expected.startsWith("compile-ok")) {
                result.add(new Classified(test, Kind.FRONTEND, "", null));
            } else if (expected.startsWith("compile-error ")) {
                result.add(new Classified(test, Kind.FRONTEND,
                    expected.substring("compile-error ".length()).trim(),
                    null));
            } else if (expected.startsWith("runtime-ok")
                    || expected.startsWith("runtime-error ")) {
                String code = expected.startsWith("runtime-error ")
                    ? expected.substring("runtime-error ".length()).trim()
                    : "";
                result.add(new Classified(test, Kind.APPLICABLE, code,
                    null));
            } else {
                // Unknown @expected is a harness failure, never a skip.
                throw new IllegalStateException("unknown @expected '"
                    + expected + "' in " + test.relativePath());
            }
        }
        return result;
    }

    // =========================================================================
    // Execution dispatch
    // =========================================================================

    private static void runOne(Classified classified) {
        StringBuilder buffer = new StringBuilder();
        WORKER_OUTPUT.set(buffer);
        try {
            Outcome outcome = switch (classified.kind()) {
                case FRONTEND -> runFrontend(classified);
                case APPLICABLE -> runApplicable(classified);
                case KNOWN_FAIL -> runKnownFail(classified);
            };
            if (outcome != null) {
                outcomes.add(outcome);
            }
        } catch (Throwable e) {
            log("  [" + classified.test().relativePath()
                + "] FAIL: runner exception: " + e.getMessage());
            if (classified.kind() == Kind.FRONTEND) {
                frontendFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else if (classified.kind() == Kind.APPLICABLE) {
                applicableFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
            } else {
                // A runner exception (an Error, or anything thrown
                // before runApplicable's internal Exception catch)
                // escaping a KNOWN_FAIL probe is an explicit
                // harness failure — never an applicable failure, never
                // a tracked result.
                probeHarnessFailed.incrementAndGet();
                log("  [" + classified.test().relativePath()
                    + "] FAIL (runner exception during known-fail "
                    + "probe — harness failure): " + e.getMessage());
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception during known-fail "
                    + "probe — harness failure: " + e.getMessage()));
            }
        } finally {
            WORKER_OUTPUT.remove();
            synchronized (CONSOLE_LOCK) {
                System.out.print(buffer);
            }
        }
    }

    // =========================================================================
    // Frontend gate (backend-neutral compile-ok / compile-error)
    // =========================================================================

    private static Outcome runFrontend(Classified classified) {
        TestFile test = classified.test();
        frontendTotal.incrementAndGet();
        List<CompilerDiagnostic> diags = frontendDiagnostics(test.path(),
            SemanticProfile.DEAL_V1_2_INT32);
        boolean hasErrors = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        String expected = test.expected();
        if (expected.startsWith("compile-ok")) {
            if (hasErrors) {
                frontendFailed.incrementAndGet();
                StringBuilder sb = new StringBuilder();
                for (CompilerDiagnostic d : diags) {
                    if ("error".equals(d.severity())) {
                        sb.append("    ").append(d).append('\n');
                    }
                }
                log("  [" + test.relativePath()
                    + "] FAIL (unexpected compile errors)\n" + sb);
                return new Outcome(test, classified, false,
                    "unexpected compile errors");
            }
            frontendPassed.incrementAndGet();
            log("  [" + test.relativePath() + "] OK (frontend)");
            return new Outcome(test, classified, true, "compile ok");
        }
        String code = expected.substring("compile-error ".length()).trim();
        boolean found = diags.stream().anyMatch(
            d -> "error".equals(d.severity()) && code.equals(d.code()));
        if (found) {
            frontendPassed.incrementAndGet();
            log("  [" + test.relativePath() + "] OK (found " + code + ")");
            return new Outcome(test, classified, true, "found " + code);
        }
        List<String> gotCodes = diags.stream()
            .filter(d -> "error".equals(d.severity()))
            .map(CompilerDiagnostic::code)
            .toList();
        frontendFailed.incrementAndGet();
        log("  [" + test.relativePath() + "] FAIL (expected " + code
            + ", got: " + gotCodes + ")");
        return new Outcome(test, classified, false,
            "expected " + code + ", got: " + gotCodes);
    }

    /**
     * The shared frontend pipeline — lexer → parser → name resolver →
     * type checker — with the same stdlib/relative-import resolution the
     * LuaJIT conformance harness uses (stdlib exports from the
     * spec-listed .d.deal declarations, relative imports via
     * {@link ExportExtractor}). A bypassed parser or checker yields no
     * diagnostics and the compile-error tests fail.
     */
    @SuppressWarnings("deprecation")
    private static List<CompilerDiagnostic> frontendDiagnostics(Path file,
            SemanticProfile profile) {
        List<CompilerDiagnostic> all = new ArrayList<>();
        try {
            // ISSUE-0272 D8 item 2a: in-memory seam site — classification
            // headers are stripped before the lexer; parseMetadata keeps
            // reading the raw fixture bytes.
            String source = ConformanceHarnessMetadata
                .stripClassificationHeaders(Files.readString(file));
            String filename = file.toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            all.addAll(lex.diagnostics());
            if (lex.hasErrors()) return all;

            Parser parser = new Parser(lex.tokens(),
                    filename, profile,
                    lex.directiveEvents());
            ParseResult parseResult = parser.parse();
            all.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) return all;

            FrontendModuleResolver resolver =
                new FrontendModuleResolver(file, profile);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                // E9999 is the test-only pseudo code for an unexpected
                // NameResolver exception (D5/verification 6): the
                // deprecated synthetic factory carries the canonical
                // synthetic range plus an anchor note naming the fixture
                // file.
                all.add(CompilerDiagnostic.synthetic("E9999", "error",
                    e.getMessage(), filename,
                    "missing anchor: fixture source '" + filename + "'"));
                return all;
            }
            all.addAll(nr.diagnostics());

            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            all.addAll(result.diagnostics());

            // Corpus C FFI externals (ISSUE-0507): the production
            // FfiDeclarationValidator diagnostics of every candidate/*
            // import surface on the frontend compile paths (the E7002 C
            // FFI declaration policy) exactly as the orchestrator's FFI
            // phase surfaces them.
            for (StatementNode stmt
                    : parseResult.program().statements()) {
                if (stmt instanceof ImportDeclaration imp
                        && deal.test.conformance.CorpusFfi.isFfiImport(
                            conformanceRoot, imp.modulePath())) {
                    all.addAll(deal.test.conformance.CorpusFfi.module(
                        conformanceRoot, imp.modulePath(), profile)
                        .validationDiagnostics());
                }
            }
            return all;
        } catch (IOException e) {
            String filename = file.toString();
            all.add(CompilerDiagnostic.synthetic("E9999", "error",
                "cannot read " + file, filename,
                "missing anchor: fixture source '" + filename + "'"));
            return all;
        }
    }

    /** Module resolver for the frontend gate: spec-listed stdlib exports
     * and relative {@code ./} / {@code ../} imports resolved through
     * {@link ExportExtractor} with synthesized class symbols — the same
     * resolution surface the LuaJIT conformance harness uses for
     * compile-stage tests. */
    private static final class FrontendModuleResolver
            implements ModuleResolver {
        private final Path testFileDir;
        private final Map<String, Map<String, Type>> stdlibExports;
        private final SemanticProfile profile;

        FrontendModuleResolver(Path testFile, SemanticProfile profile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.profile = java.util.Objects.requireNonNull(profile,
                "profile must not be null");
            // ISSUE-0269: the resolved distribution surface (the
            // CWD-relative no-arg read is retired).
            this.stdlibExports = StdlibModuleResolver.stdlibExports(
                Path.of("std").toAbsolutePath().normalize().toString());
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException,
                    CffiImportWithoutNativeLibraryException {
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleNotFoundException(
                    "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
            }
            // Corpus C FFI externals (ISSUE-0507): candidate/* imports
            // resolve through the corpus-owned FFI wiring into the real
            // FFI declaration surface.
            if (deal.test.conformance.CorpusFfi.isFfiImport(
                    conformanceRoot, modulePath)) {
                return deal.test.conformance.CorpusFfi.module(
                    conformanceRoot, modulePath, profile).exports();
            }
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                try {
                    // ISSUE-0272 D8 item 2a: in-memory seam site —
                    // classification headers are stripped before the lexer.
                    String source = ConformanceHarnessMetadata
                        .stripClassificationHeaders(Files.readString(resolved));
                    boolean isDecl = resolved.toString().endsWith(".d.deal");
                    LexResult lex = new Lexer(source, resolved.toString())
                        .tokenize();
                    if (lex.hasErrors()) {
                        throw new ModuleNotFoundException("Lex errors in "
                            + resolved);
                    }
                    Parser parser = new Parser(lex.tokens(),
                    resolved.toString(), profile,
                    lex.directiveEvents());
                    ParseResult parseResult = parser.parse();
                    if (parseResult.hasErrors()) {
                        throw new ModuleNotFoundException("Parse errors in "
                            + resolved);
                    }
                    // The v1.2 C FFI manifest policy
                    // (docs/spec-v1.2.md:1891): a C FFI declaration file
                    // (a .d.deal file carrying // @extern-c) may only be
                    // imported through a deal.json externals entry
                    // specifying nativeLibrary. The conformance frontend
                    // pipeline has no manifest, so such an import is an
                    // invalid project configuration — E2010 at the
                    // import span via the checker's manifest-policy
                    // rejection.
                    if (parseResult.program().fileDirectives().externC()) {
                        throw new ModuleResolver
                            .CffiImportWithoutNativeLibraryException(
                                modulePath);
                    }
                    ExportExtractor extractor = new ExportExtractor(
                        resolved.toString(), isDecl);
                    return extractor.extract(parseResult.program());
                } catch (IOException e) {
                    throw new ModuleNotFoundException("Cannot read: "
                        + resolved);
                }
            }
            throw new ModuleNotFoundException("Module not found: "
                + modulePath);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            return classSymbolsOf(resolved).get(className);
        }

        @Override
        public Symbol.ClassSymbol resolveClassSymbol(String className,
                deal.identity.CanonicalModuleIdentity declaringModule,
                String importingModule)
                throws ModuleNotFoundException {
            // v1.2 identity carriage: route the carried companion
            // identity (the standalone ProjectModule(dotted) convention
            // this frontend resolver synthesizes) back to the
            // synthesized symbols.
            for (java.nio.file.Path candidate
                    : companionPaths(declaringModule)) {
                Symbol.ClassSymbol sym = classSymbolsOf(candidate)
                    .get(className);
                if (sym != null) {
                    return sym;
                }
            }
            return null;
        }

        /** The companion files whose synthesized identity equals the
         * carried module identity (frontend-gate routing only). */
        private java.util.List<java.nio.file.Path> companionPaths(
                deal.identity.CanonicalModuleIdentity declaringModule) {
            java.util.List<java.nio.file.Path> result =
                new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> stream =
                    java.nio.file.Files.list(testFileDir)) {
                for (java.nio.file.Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".deal") && !name.endsWith(".d.deal")) {
                        continue;
                    }
                    String stem = name.endsWith(".d.deal")
                        ? name.substring(0, name.length() - ".d.deal".length())
                        : name.substring(0, name.length() - ".deal".length());
                    if (declaringModule.equals(
                            IdentityTestFixtures.moduleIdentityOf(stem))) {
                        result.add(file);
                    }
                }
            } catch (IOException ignored) {
                // frontend-gate best effort
            }
            return result;
        }

        @Override
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            try {
                // ISSUE-0272 D8 item 2a: in-memory seam site —
                // classification headers are stripped before the lexer.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(resolved));
                LexResult lex = new Lexer(source, resolved.toString())
                    .tokenize();
                if (lex.hasErrors()) return null;
                Parser parser = new Parser(lex.tokens(),
                    resolved.toString(), profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return null;
                NameResolver nr = new NameResolver(resolved.toString(), this);
                nr.resolve(parseResult.program());
                return nr.resolveTypeNode(typeNode);
            } catch (Exception e) {
                return null;
            }
        }

        /** Parses a relative companion and synthesizes its class symbols
         * (the dotted module path view the checker consumes). */
        private Map<String, Symbol.ClassSymbol> classSymbolsOf(Path file) {
            Map<String, Symbol.ClassSymbol> symbols = new LinkedHashMap<>();
            try {
                // ISSUE-0272 D8 item 2a: in-memory seam site —
                // classification headers are stripped before the lexer.
                String source = ConformanceHarnessMetadata
                    .stripClassificationHeaders(Files.readString(file));
                LexResult lex = new Lexer(source, file.toString()).tokenize();
                if (lex.hasErrors()) return symbols;
                Parser parser = new Parser(lex.tokens(),
                    file.toString(), profile,
                    lex.directiveEvents());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return symbols;
                String dotted = modulePathOf(file);
                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            IdentityTestFixtures.identityOf(dotted,
                                cd.name())));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration() instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted,
                            IdentityTestFixtures.identityOf(dotted,
                                cd.name())));
                    }
                }
            } catch (IOException ignored) { }
            return symbols;
        }

        private String modulePathOf(Path file) {
            String name = file.getFileName().toString();
            if (name.endsWith(".d.deal")) {
                return name.substring(0, name.length() - ".d.deal".length());
            }
            return name.substring(0, name.length() - ".deal".length());
        }

        private Path resolveRelativePath(String importPath) {
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
                return null;
            }
            Path resolved = testFileDir.resolve(importPath).normalize();
            if (Files.exists(resolved)) return resolved;
            Path withExt = testFileDir.resolve(importPath + ".deal").normalize();
            if (Files.exists(withExt)) return withExt;
            Path withDeclExt = testFileDir.resolve(
                importPath + ".d.deal").normalize();
            if (Files.exists(withDeclExt)) return withDeclExt;
            return null;
        }
    }

    // =========================================================================
    // Known-fail cases (tracked follow-up issues)
    // =========================================================================

    /**
     * Executes the underlying runtime mode of a known-fail case through
     * the real JVM pipeline. While the case still fails, it is recorded
     * as a non-fatal tracked KNOWN-FAIL; when it starts passing, the
     * gate FAILS with a promotion instruction (drop the marker).
     */
    private static Outcome runKnownFail(Classified classified) {
        TestFile test = classified.test();
        knownFailTotal.incrementAndGet();
        String mode = test.expected().substring(
            "known-fail ".length()).trim();
        Outcome probe = runApplicable(classified, true);
        if (probe.pass()) {
            knownFailStale.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (STALE known-fail: the v1.2 requirement tracked "
                + "by " + test.issue() + " now passes on JVM — promote the "
                + "fixture: set '@expected: " + mode + "' and drop the "
                + "@issue tag)");
            return new Outcome(test, classified, false,
                "stale known-fail; promote fixture");
        }
        // The strict recording seam (S3): the first known-fail recording
        // attempt terminates the runner before knownFailTracked or
        // knownFailByIssue is touched.
        if (STRICT_MODE) {
            System.err.println("STRICT_SKIP_DETECTED ("
                + test.relativePath() + ": known-fail tracked by "
                + test.issue() + ")");
            System.exit(1);
        }
        knownFailTracked.incrementAndGet();
        knownFailByIssue.merge(test.issue(), 1, Integer::sum);
        log("  [" + test.relativePath() + "] KNOWN-FAIL (" + mode
            + " not yet satisfied on JVM; tracked by " + test.issue()
            + ") — " + probe.message());
        return null;
    }

    // =========================================================================
    // Backend-runtime execution: orchestrator → javac → java
    // =========================================================================

    private static Outcome runApplicable(Classified classified) {
        return runApplicable(classified, false);
    }

    private static Outcome runApplicable(Classified classified,
            boolean knownFailProbe) {
        // ISSUE-0378 D1: every fixture — applicable, known-fail probe,
        // or skip probe — compiles through the single lane-wide
        // activated invocation; no legacy-authority labeling exists in
        // this lane, so the profile-authority accounting stays at zero.
        return runApplicableImpl(classified, knownFailProbe);
    }

    private static Outcome runApplicableImpl(Classified classified,
            boolean knownFailProbe) {
        TestFile test = classified.test();
        if (!knownFailProbe) {
            applicableTotal.incrementAndGet();
        }
        if (!jvmAvailable) {
            if (!knownFailProbe) {
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (javac/java unavailable — the JVM "
                    + "execution stage cannot be bypassed)");
            }
            return new Outcome(test, classified, false,
                "javac/java unavailable");
        }

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_jvm_conf_");

            // 1. Materialize the test file and its transitive companion
            // closure under the configured root "src" of the temp
            // project (flat stem namespace; the corpus has no duplicate
            // stems). Relative imports resolve on disk from the corpus
            // directories.
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot.resolve("src"), test.path(), written);
            String entryRel = "src/" + corpusStem(test.path()) + ".deal";
            Path entryFile = projectRoot.resolve(entryRel);
            Path outputRoot = projectRoot.resolve("out");

            // 2. Every harness project receives an injected exact-v1.2
            // deal.json and routes through production ProjectLocator
            // (ISSUE-0269, parent D12): moduleRoots ["src"] (the
            // representable configured root), output "out", backend
            // "jvm" — host-ABI fixtures additionally carry the
            // externals map wiring every raw host import path to its
            // declaration under the project root (bindings/).
            Set<String> hostNames = hostImports(test.path());
            // Corpus C FFI externals (ISSUE-0507): every candidate/*
            // import of the compilation set wires through the real
            // whole-project externals machinery — the orchestrator's FFI
            // phase validates the declaration and the JVM backend
            // rejects with E6006 FFI_UNSUPPORTED_BACKEND before any
            // artifact.
            Set<String> ffiImports = ffiImports(test.path());
            StringBuilder dealJson = new StringBuilder();
            dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
            dealJson.append("  \"moduleRoots\": [\"src\"],\n");
            dealJson.append("  \"output\": \"out\",\n");
            dealJson.append("  \"backend\": \"jvm\"");
            if (!hostNames.isEmpty() || !ffiImports.isEmpty()) {
                if (!hostNames.isEmpty()) {
                    // ISSUE-0272 D8 item 2b: producer-side seam — the host
                    // declaration materialization strips classification
                    // headers before the bytes reach the orchestrator, and
                    // each raw host import path is wired to its declaration
                    // under the project root (bindings/).
                    copyHostBindings(projectRoot, hostFixturesRoot,
                        hostNames);
                }
                copyFfiBindings(projectRoot, ffiImports);
                dealJson.append(",\n  \"externals\": {\n");
                boolean first = true;
                for (String hostName : hostNames) {
                    if (!first) dealJson.append(",\n");
                    first = false;
                    String declRel = "bindings/" + hostName + ".d.deal";
                    dealJson.append("    \"host/").append(hostName)
                        .append("\": { \"declaration\": \"")
                        .append(declRel).append("\" }");
                }
                for (String raw : ffiImports) {
                    if (!first) dealJson.append(",\n");
                    first = false;
                    String declRel = "bindings/ffi/" + raw
                        .replace('/', '_') + ".d.deal";
                    dealJson.append("    \"").append(raw)
                        .append("\": { \"declaration\": \"")
                        .append(declRel)
                        .append("\", \"nativeLibrary\": \"")
                        .append(deal.test.conformance.CorpusFfi
                            .loaderTextFor(conformanceRoot,
                                deal.test.conformance.CorpusFfi
                                    .wiringFor(conformanceRoot, raw)))
                        .append("\" }");
                }
                dealJson.append("\n  }");
            }
            dealJson.append("\n}\n");
            Files.writeString(projectRoot.resolve("deal.json"), dealJson);
            ProjectLocator.LocateResult located =
                ProjectLocator.locate(entryFile.toString(), null);
            if (located.context() == null) {
                throw new IllegalStateException(
                    "generated deal.json did not locate strictly: "
                        + located.e2010());
            }

            // 3. The real whole-project pipeline: module discovery,
            // signature extraction, dependency ordering, name resolution,
            // type checking, per-module JvmBackend codegen — driven by
            // the published immutable ProjectContext. Every on-disk
            // backend-runtime fixture compiles through the single
            // lane-wide activated invocation (ISSUE-0378 D1): the
            // explicit CompilerProfileProvider.resolve(V1_2_ACTIVE,
            // releaseRegistry()) PUBLIC_BUILD + DEAL_V1_2_INT32 record;
            // no per-fixture catalog seam and no legacy-authority
            // routing exist in this lane.
            CompilerInvocation invocation = LANE_INVOCATION;
            OrchestratorRun run = runOrchestrator(entryFile,
                located.context(), invocation);
            if (!run.success()) {
                if (knownFailProbe) {
                    return new Outcome(test, classified, false,
                        "orchestrator compile failed: " + run.diagnostics());
                }
                // Corpus C6 (ISSUE-0507): the sanctioned FFI
                // divergence — when the fixture's sidecar pins the jvm
                // leg as compile-reject E6006 FFI_UNSUPPORTED_BACKEND
                // and the real pipeline rejected with exactly that code
                // before any artifact, the lane records the pinned
                // rejection as the verdict (matching the sidecar),
                // never as an applicable failure.
                deal.test.conformance.SidecarExpectations
                        .StructuredExpectationSidecar sidecar =
                    sidecarOf(test.path());
                if (sidecar != null
                        && sidecar.expectationFor("jvm")
                            instanceof deal.test.conformance
                                .SidecarExpectations.RuntimeExpectation
                                .Rejected rejected
                        && "E6006".equals(rejected.code())
                        && run.diagnostics().stream().anyMatch(
                            d -> "error".equals(d.severity())
                                && "E6006".equals(d.code()))
                        && !Files.exists(outputRoot.resolve(
                            entryClassName(entryRel) + ".java"))) {
                    applicablePassed.incrementAndGet();
                    log("  [" + test.relativePath()
                        + "] OK (compile-reject E6006 "
                        + "FFI_UNSUPPORTED_BACKEND)");
                    return new Outcome(test, classified, true,
                        "compile-reject E6006 FFI_UNSUPPORTED_BACKEND");
                }
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (orchestrator compile failed): "
                    + run.diagnostics() + "\n" + run.capturedOutput());
                return new Outcome(test, classified, false,
                    "orchestrator compile failed: " + run.diagnostics());
            }

            // 4. Codegen was real: the entry artifact must exist before
            // javac runs.
            String entryClass = entryClassName(entryRel);
            Path entryJava = outputRoot.resolve(entryClass + ".java");
            if (!Files.exists(entryJava)) {
                if (!knownFailProbe) applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (JVM codegen produced no '"
                    + entryJava.getFileName() + "' artifact)");
                return new Outcome(test, classified, false,
                    "no .java artifact produced");
            }

            // 5. Host implementation classes compile together with the
            // emitted artifacts (the harness analog of the user placing
            // the host implementation on the compile/runtime classpath).
            for (String hostName : hostNames) {
                String javaSrc = HOST_JAVA.get(hostName);
                if (javaSrc == null) {
                    throw new IllegalStateException("no JVM host "
                        + "implementation for " + hostName);
                }
                String hostClass = JvmBackend.classNameFor(
                    "host/" + hostName);
                Files.writeString(outputRoot.resolve(hostClass + ".java"),
                    javaSrc);
            }

            // 6. Runner: auto-invokes the entry module's zero-arity
            // exports, driven by the real parser's export list.
            ProgramNode entryProgram = parseEntryProgram(entryFile,
                invocation.semanticProfile());
            Path runnerFile = outputRoot.resolve("JvmConformanceRunner.java");
            Files.writeString(runnerFile,
                BackendConformanceTest.buildJvmRunner(entryProgram,
                    entryClass));

            // 7. javac over every emitted .java artifact plus the runner
            // and the host classes (in-process javax.tools — the
            // identical parse/enter/analyze/generate passes the javac
            // binary runs, the same documented frontend the canonical
            // BackendConformanceTest uses for its JVM fixtures).
            List<String> javaFiles = new ArrayList<>();
            try (var stream = Files.list(outputRoot)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .sorted()
                      .forEach(p -> javaFiles.add(p.getFileName().toString()));
            }
            StringBuilder javacErr = new StringBuilder();
            boolean javacOk = BackendConformanceTest.compileWithJavac(
                outputRoot, javaFiles, javacErr);
            if (!javacOk) {
                if (knownFailProbe) {
                    return new Outcome(test, classified, false,
                        "javac failed: " + javacErr);
                }
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath() + "] FAIL (javac failed):\n"
                    + javacErr);
                return new Outcome(test, classified, false,
                    "javac failed: " + javacErr);
            }
            if (!Files.exists(outputRoot.resolve(entryClass + ".class"))
                    || !Files.exists(outputRoot.resolve(
                        "JvmConformanceRunner.class"))) {
                if (!knownFailProbe) applicableFailed.incrementAndGet();
                log("  [" + test.relativePath()
                    + "] FAIL (javac exited 0 but no .class artifacts "
                    + "were produced — JVM compilation bypassed)");
                return new Outcome(test, classified, false,
                    "no .class artifacts produced");
            }

            // 8. Execute the emitted artifacts with a real java subprocess.
            ProcessBuilder pb = new ProcessBuilder("java", "-cp",
                outputRoot.toString(), "JvmConformanceRunner");
            pb.directory(outputRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(
                p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();

            if (test.expected().startsWith("runtime-ok")
                    || test.expected().startsWith("known-fail runtime-ok")) {
                if (exitCode != 0) {
                    if (!knownFailProbe) {
                        applicableFailed.incrementAndGet();
                        log("  [" + test.relativePath()
                            + "] FAIL (runtime-ok test exited " + exitCode
                            + "): " + output);
                    }
                    return new Outcome(test, classified, false,
                        "exited " + exitCode + ": " + output);
                }
                if (!knownFailProbe) applicablePassed.incrementAndGet();
                log("  [" + test.relativePath() + "] OK");
                return new Outcome(test, classified, true, "runtime ok");
            }

            String needle = "DEAL_ERROR_CODE: "
                + classified.expectedCode();
            if (output.contains(needle)) {
                if (!knownFailProbe) applicablePassed.incrementAndGet();
                log("  [" + test.relativePath() + "] OK (found " + needle
                    + ")");
                return new Outcome(test, classified, true,
                    "found " + needle);
            }
            if (!knownFailProbe) {
                applicableFailed.incrementAndGet();
                log("  [" + test.relativePath() + "] FAIL (expected "
                    + needle + ", got: "
                    + output.replace("\n", "\\n") + ")");
            }
            return new Outcome(test, classified, false,
                "expected " + needle + ", got: " + output);
        } catch (Exception e) {
            if (!knownFailProbe) applicableFailed.incrementAndGet();
            log("  [" + test.relativePath() + "] FAIL (execution exception): "
                + e.getMessage());
            return new Outcome(test, classified, false,
                "execution exception: " + e.getMessage());
        } finally {
            if (projectRoot != null) {
                try {
                    Files.walk(projectRoot).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); }
                            catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
    }

    private record OrchestratorRun(boolean success,
                                   List<CompilerDiagnostic> diagnostics,
                                   String capturedOutput) {}

    /**
     * Runs the real {@link CompilationOrchestrator} with the
     * context-driven production constructor over the temp project
     * (ISSUE-0269): the published immutable {@link ProjectContext}
     * supplies the backend, the output root, the module roots, the
     * externals declarations, and the stdlib surface. Module discovery,
     * signature extraction, dependency ordering, name resolution, type
     * checking, and per-module JvmBackend codegen run unchanged.
     * Stdout/stderr is captured so per-test output stays clean.
     */
    private static OrchestratorRun runOrchestrator(Path entryFile,
            ProjectContext context, CompilerInvocation invocation) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        synchronized (CONSOLE_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            try {
                System.setOut(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                System.setErr(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(
                        context,
                        entryFile.toAbsolutePath().normalize(),
                        false, false, false, false, null, invocation);
                boolean success = orchestrator.compile();
                return new OrchestratorRun(success,
                    orchestrator.diagnostics(),
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

    /** The corpus-relative file stem (name without {@code .deal}). */
    private static String corpusStem(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".d.deal")) {
            return name.substring(0, name.length() - ".d.deal".length());
        }
        return name.substring(0, name.length() - ".deal".length());
    }

    /**
     * Copies the entry file and its transitive companion closure into
     * the flat temp project root. Relative imports resolve against each
     * file's own corpus directory (the same resolution the LuaJIT
     * conformance harness uses); stdlib and bare host imports stop the
     * walk (stdlib resolves through the production resolver, host
     * modules through deal.json externals).
     */
    private static void writeModuleFiles(Path projectRoot, Path entry,
            Map<String, Path> written) throws IOException {
        Files.createDirectories(projectRoot);
        copyTransitively(entry,
            entry.toAbsolutePath().normalize().getParent(),
            projectRoot, written);
    }

    /**
     * Copies the host-ABI binding declarations to
     * {@code bindings/<hostName>.d.deal} under the temp project root.
     * ISSUE-0272 D8 item 2b: producer-side seam — every binding is
     * written classification-header free (18 of 20
     * {@code test/conformance/host-fixtures/*.d.deal} carry
     * {@code // @expected: host-fixture} / {@code // @description:}
     * headers), so the production orchestrator never lexes a header
     * line. A missing host declaration fails loudly, exactly as the
     * inline copy this replaces did.
     */
    private static void copyHostBindings(Path projectRoot,
            Path hostFixturesRoot, Set<String> hostNames) throws IOException {
        for (String hostName : hostNames) {
            Path decl = hostFixturesRoot.resolve(hostName + ".d.deal");
            if (!Files.isRegularFile(decl)) {
                throw new IllegalStateException("host declaration "
                    + "missing for " + hostName);
            }
            String declRel = "bindings/" + hostName + ".d.deal";
            Files.createDirectories(projectRoot.resolve("bindings"));
            Files.writeString(projectRoot.resolve(declRel),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(decl)));
        }
    }

    /**
     * Test-only materialization pin support
     * ({@code ConformanceHarnessMetadataTest}): performs the exact
     * temp-project materialization {@link #runApplicable} performs for a
     * backend-runtime fixture — {@link #writeModuleFiles} (entry plus
     * transitive companions, including explicit-{@code .deal} alias
     * copies) and {@link #copyHostBindings} — and returns the project
     * root so the pin can assert that the three producer-side copy sites
     * left the orchestrator header-free sources. The caller owns the
     * returned tree.
     */
    static Path materializeProject(Path conformanceRoot, Path entry)
            throws IOException {
        Path projectRoot = Files.createTempDirectory("deal_jvm_seam_");
        boolean ok = false;
        try {
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot, entry, written);
            Set<String> hostNames = hostImports(entry);
            if (!hostNames.isEmpty()) {
                copyHostBindings(projectRoot,
                    conformanceRoot.resolve("host-fixtures"), hostNames);
            }
            ok = true;
            return projectRoot;
        } finally {
            if (!ok) {
                try {
                    Files.walk(projectRoot).sorted(Comparator.reverseOrder())
                        .forEach(f -> { try { Files.deleteIfExists(f); }
                            catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
    }

    private static void copyTransitively(Path file, Path entryDir,
            Path projectRoot, Map<String, Path> written)
            throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (written.containsKey(normalized.toString())) return;
        // Companions inside the entry's own corpus directory keep their
        // subdirectory layout (the v1.2 identity carriage: two files in
        // one directory share the module identity, so nested
        // companions — like the modid isolation pair — need their
        // directories preserved to stay nominally distinct); every
        // other companion keeps the flat stem layout.
        Path target;
        try {
            Path rel = entryDir.relativize(normalized);
            if (rel.startsWith("..") || rel.getNameCount() <= 1) {
                target = projectRoot.resolve(corpusStem(normalized)
                    + ".deal");
            } else {
                target = projectRoot.resolve(rel);
            }
        } catch (IllegalArgumentException e) {
            target = projectRoot.resolve(corpusStem(normalized) + ".deal");
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        // ISSUE-0272 D8 item 2b: producer-side seam — the entry fixture
        // and every transitive companion are written classification-header
        // free, so the production orchestrator never lexes a header line.
        Files.writeString(target, ConformanceHarnessMetadata
            .stripClassificationHeaders(Files.readString(normalized)));
        written.put(normalized.toString(), target);
        for (String importPath : relativeImports(normalized)) {
            Path resolved = resolveCompanionPath(importPath,
                normalized.getParent());
            if (resolved == null) continue;
            // D4 (ISSUE-0176): an import spelling carrying an explicit
            // .deal extension resolves to its alias-named copy in the
            // flat project root, so materialize the companion under
            // that name before the recursive walk (the written-map
            // early return below must not suppress it).
            copyCompanionAliasIfExplicit(resolved, importPath, projectRoot);
            copyTransitively(resolved, entryDir, projectRoot, written);
        }
    }

    /**
     * Materializes a resolved companion under the production-resolved
     * alias name when the import spelling carries an explicit
     * {@code .deal} extension ({@code ./async_lib.deal} →
     * {@code async_lib.deal.deal}): {@link CompilationOrchestrator}
     * searches {@code basePath + ".deal"} first, and its
     * {@code isMatch} rule resolves the spelling to exactly that alias
     * file. The stem-named copy is still written by the recursive
     * walk; stem-only spellings need no alias and are untouched. The
     * copy is idempotent across repeated explicit spellings of the
     * same companion (the alias target already materialized is
     * skipped).
     */
    private static void copyCompanionAliasIfExplicit(Path resolved,
            String importPath, Path projectRoot) throws IOException {
        if (!importPath.endsWith(".deal")) return;
        String aliasBase = importPath;
        if (aliasBase.startsWith("./")) {
            aliasBase = aliasBase.substring("./".length());
        } else if (aliasBase.startsWith("../")) {
            aliasBase = aliasBase.substring("../".length());
        }
        Path aliasTarget = projectRoot.resolve(aliasBase + ".deal");
        if (Files.exists(aliasTarget)) return;
        // ISSUE-0272 D8 item 2b: producer-side seam — the explicit-.deal
        // alias copy is written classification-header free; the
        // written-map dedup/alias semantics are unchanged.
        Files.writeString(aliasTarget, ConformanceHarnessMetadata
            .stripClassificationHeaders(
                Files.readString(resolved.toAbsolutePath().normalize())));
    }

    /** Relative import paths ({@code ./} / {@code ../}) appearing in the
     * file's source, in order. */
    private static List<String> relativeImports(Path file)
            throws IOException {
        List<String> paths = new ArrayList<>();
        String source = Files.readString(file);
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            int from = trimmed.indexOf(" from \"");
            if (from < 0) continue;
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) continue;
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("./") || path.startsWith("../")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Bare host import names ({@code host/<name>}) appearing in the
     * file's source, in order (drives deal.json externals generation). */
    private static Set<String> hostImports(Path file) throws IOException {
        Set<String> hosts = new LinkedHashSet<>();
        String source = Files.readString(file);
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            int from = trimmed.indexOf(" from \"");
            if (from < 0) continue;
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) continue;
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("host/")) {
                hosts.add(path.substring("host/".length()));
            }
        }
        return hosts;
    }

    /**
     * The parsed Structured Expectation Sidecar of one fixture, or
     * null when the fixture carries no sidecar (the compile-reject
     * verdict check reads the sanctioned jvm-leg pin).
     */
    private static deal.test.conformance.SidecarExpectations
            .StructuredExpectationSidecar sidecarOf(Path file) {
        String name = file.getFileName().toString();
        Path sidecar;
        if (name.endsWith(".deal")) {
            sidecar = file.resolveSibling(
                name.substring(0, name.length() - ".deal".length())
                    + ".expect.json");
        } else {
            sidecar = file.resolveSibling(name + ".expect.json");
        }
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            return deal.test.conformance.SidecarExpectations
                .StructuredExpectationSidecar.parse(
                    Files.readString(sidecar));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                "cannot parse the sidecar " + sidecar + ": "
                    + e.getMessage());
        }
    }

    /** Corpus FFI externals import specifiers ({@code candidate/*})
     * appearing in the fixture's source (drives deal.json externals
     * generation through the corpus-owned FFI wiring). */
    private static Set<String> ffiImports(Path file) throws IOException {
        Set<String> ffi = new LinkedHashSet<>();
        String source = Files.readString(file);
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) continue;
            int from = trimmed.indexOf(" from \"");
            if (from < 0) continue;
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) continue;
            String path = trimmed.substring(from + 7, end);
            if (deal.test.conformance.CorpusFfi.isFfiImport(
                    conformanceRoot, path)) {
                ffi.add(path);
            }
        }
        return ffi;
    }

    /**
     * Materializes the corpus FFI declarations under the project root
     * ({@code bindings/ffi/<raw with / as _>.d.deal},
     * classification-header free — the ISSUE-0272 D8 producer-side
     * seam) so the real whole-project externals machinery resolves
     * every candidate/* import through the declaration on disk.
     */
    private static void copyFfiBindings(Path projectRoot,
            Set<String> ffiImports) throws IOException {
        for (String raw : ffiImports) {
            deal.test.conformance.CorpusFfi.Wiring wiring =
                deal.test.conformance.CorpusFfi.wiringFor(
                    conformanceRoot, raw);
            if (wiring == null) {
                throw new IllegalStateException(
                    "no corpus FFI wiring for " + raw);
            }
            Path declaration = conformanceRoot.resolve(
                    deal.test.conformance.CorpusFfi.FFI_DIR)
                .resolve(wiring.declarationCorpusPath());
            if (!Files.isRegularFile(declaration)) {
                throw new IllegalStateException(
                    "the corpus FFI declaration is missing: "
                        + declaration);
            }
            String declRel = "bindings/ffi/" + raw.replace('/', '_')
                + ".d.deal";
            Path target = projectRoot.resolve(declRel);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, ConformanceHarnessMetadata
                .stripClassificationHeaders(
                    Files.readString(declaration)));
        }
    }

    /** Resolve a relative import path to a .deal/.d.deal file on disk
     * (the same resolution the LuaJIT conformance harness uses). */
    private static Path resolveCompanionPath(String importPath,
            Path baseDir) {
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
     * Class name of the entry module's emitted artifact: the temp
     * project root is the single module root, so the entry's module
     * path is its file stem and {@link JvmBackend#classNameFor} derives
     * the class name the orchestrator used.
     */
    private static String entryClassName(String entryRel) {
        String path = entryRel;
        // ISSUE-0269: the entry key carries the configured-root prefix
        // (src/...); the orchestrator's module name is the root-relative
        // path, so the prefix is stripped before deriving the class name.
        if (path.startsWith("src/")) {
            path = path.substring("src/".length());
        }
        if (path.endsWith(".deal")) {
            path = path.substring(0, path.length() - ".deal".length());
        }
        return JvmBackend.classNameFor(
            path.replace('/', '.').replace('\\', '.'));
    }

    /**
     * Parses the entry module with the real lexer + parser for the runner
     * (its export list drives auto-invocation). Type checking is NOT
     * re-run here — the orchestrator already checked every module — so
     * this parse cannot act as a checker bypass.
     *
     * <p>ISSUE-0273 D8 item 2c/3: this runner parse is NOT an in-memory
     * seam site — its lexer reads the already-stripped temp copy produced
     * by the materialization seam, and its single parser construction
     * keeps the no-events parser form: the orchestrator already validated
     * the same bytes with full directive evaluation, and re-running
     * file-directive evaluation and binding here would duplicate that
     * work.</p>
     */
    private static ProgramNode parseEntryProgram(Path entryFile,
            SemanticProfile profile) throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(),
            entryFile.toString(), profile).parse();
        if (parse.hasErrors()) {
            throw new IllegalStateException("entry module parse errors: "
                + parse.diagnostics());
        }
        return parse.program();
    }

    // =========================================================================
    // Report
    // =========================================================================

    /** The on-disk backend-runtime denominator: every runtime-ok /
     * runtime-error (including known-fail) corpus test. The gate
     * measures the pass rate against this UNCHANGED denominator — skips
     * never shrink it. */
    private static int runtimeDenominator() throws IOException {
        int n = 0;
        try (var stream = Files.walk(
                conformanceRoot.resolve("backend-runtime"))) {
            for (Path p : (Iterable<Path>) stream
                    .filter(p -> p.toString().endsWith(".deal"))::iterator) {
                TestFile tf = parseMetadata(p);
                if (tf == null) continue;
                if (tf.expected().startsWith("runtime-ok")
                        || tf.expected().startsWith("runtime-error ")
                        || tf.expected().startsWith("known-fail runtime")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void printReport(List<Outcome> sorted, int runtimeTotal)
            throws IOException {
        System.out.println();
        System.out.println("=== JVM Conformance Summary (ISSUE-0102 origin — ISSUE-0168 capability accounting) ===");

        int ft = frontendTotal.get();
        int fp = frontendPassed.get();
        int ff = frontendFailed.get();
        System.out.printf("Frontend (backend-neutral compile-ok/"
            + "compile-error): total %d, passed %d, failed %d%n",
            ft, fp, ff);

        int ap = applicablePassed.get();
        int af = applicableFailed.get();
        int kf = knownFailTracked.get();
        int denominator = runtimeDenominator();
        double pct = denominator == 0 ? 0.0
            : (ap * 100.0 / denominator);
        System.out.printf("Backend-runtime on JVM: denominator %d "
            + "(every on-disk runtime test, unchanged), passed %d, "
            + "failed %d, skipped 0 (no registry — zero skips by "
            + "construction), known-fail %d "
            + "(tracked) — pass rate %.1f%%%n",
            denominator, ap, af, kf, pct);
        System.out.println();

        System.out.println("Known-fail groups (tracked follow-up issues):");
        List<String> kfIssues = new ArrayList<>(knownFailByIssue.keySet());
        Collections.sort(kfIssues);
        for (String issue : kfIssues) {
            System.out.printf("  %-11s %d test(s)%n", issue,
                knownFailByIssue.get(issue));
        }
        System.out.println();

        // Largest failing feature groups (review evidence).
        Map<String, Integer> failingGroups = new LinkedHashMap<>();
        for (Outcome o : sorted) {
            if (o.classified().kind() != Kind.APPLICABLE || o.pass()) {
                continue;
            }
            String[] segs = o.test().relativePath().split("/");
            String key = segs.length > 1
                && "backend-runtime".equals(segs[0]) ? segs[1] : segs[0];
            failingGroups.merge(key, 1, Integer::sum);
        }
        if (!failingGroups.isEmpty()) {
            System.out.println("Largest remaining failing feature groups:");
            List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(failingGroups.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(),
                a.getValue()));
            for (Map.Entry<String, Integer> e : entries) {
                System.out.printf("  %-35s %d failing test(s)%n",
                    e.getKey(), e.getValue());
            }
            System.out.println();
        }

        // Gates.
        boolean ok = true;
        if (knownFailStale.get() > 0) {
            System.out.println("GATE FAILURE: " + knownFailStale.get()
                + " stale known-fail marker(s) — promote the fixture(s)");
            ok = false;
        }
        if (probeHarnessFailed.get() > 0) {
            System.out.println("GATE FAILURE: probeHarnessFailed = "
                + probeHarnessFailed.get() + " — a runner exception "
                + "escaped a known-fail probe (harness failure; a "
                + "probe crash is never silent evidence and never a "
                + "tracked result)");
            ok = false;
        }
        if (ff > 0) {
            System.out.println("GATE FAILURE: " + ff
                + " frontend test(s) failed — 100% required");
            ok = false;
        }
        if (af > 0) {
            System.out.println("GATE FAILURE: " + af
                + " applicable backend-runtime test(s) failed — zero "
                + "applicable failures required");
            ok = false;
        }
        if (pct < 100.0) {
            System.out.println("GATE FAILURE: backend-runtime pass rate "
                + pct + "% below the 100% threshold (denominator "
                + denominator + " — every on-disk applicable runtime "
                + "test must pass through codegen, javac, and java)");
            ok = false;
        }
        if (runtimeTotal != denominator) {
            System.out.println("GATE FAILURE: classified runtime total "
                + runtimeTotal + " differs from the on-disk denominator "
                + denominator);
            ok = false;
        }
        if (!ok) {
            System.exit(1);
        }
        System.out.println("Gates PASSED: frontend 100%; backend-runtime "
            + "zero applicable failures AND 100% of the unchanged "
            + denominator
            + "-test denominator through codegen, javac, and java; "
            + "zero skips (no registry — zero by construction); zero "
            + "stale known-fail markers; zero probe runner "
            + "exceptions.");
    }
}
