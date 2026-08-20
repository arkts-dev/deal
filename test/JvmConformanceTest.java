package deal.test;

import deal.ast.*;
import deal.checker.*;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.lexer.*;
import deal.module.CompilationOrchestrator;
import deal.module.DealConfig;
import deal.module.ExportExtractor;
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
 * JVM conformance promotion gate (ISSUE-0102).
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
 *       {@code @issue} (ISSUE-0111 signed int32 / bytes). The runner
 *       executes the underlying mode through the real JVM pipeline and
 *       records a non-fatal tracked KNOWN-FAIL while the case still
 *       fails; when it starts passing, the gate FAILS with a promotion
 *       instruction (drop the marker) — promotion is forced.</li>
 *   <li><b>Backend-runtime tests</b> ({@code runtime-ok} /
 *       {@code runtime-error CODE}): JVM-applicable and must pass
 *       through the whole pipeline UNLESS the explicit skip registry
 *       (below) classifies them. Every skip carries a documented reason
 *       and a follow-up issue id; the registry is validated against the
 *       on-disk corpus (a stale entry naming a missing file fails the
 *       run, and there is no fallback skip branch — zero unclassified
 *       skips by construction).</li>
 * </ol>
 *
 * <h2>The skip registry (ISSUE-0102)</h2>
 *
 * <ul>
 *   <li><b>ISSUE-0099</b> — the async-await corpus (async function
 *       values, cross-module async flow, async error propagation): the
 *       JVM async-await slice owns these shapes.</li>
 *   <li><b>ISSUE-0101</b> — the @jsonable corpus plus every test whose
 *       observable behavior requires the {@code std/json} boundary: the
 *       JVM @jsonable / std-json slice owns these shapes.</li>
 *   <li><b>ISSUE-0100</b> — host-ABI corpus fixtures whose host
 *       fixtures declare surfaces the landed JVM host ABI slice does
 *       not support (class exports, array parameters/returns,
 *       function-typed parameters/returns, and the Lua pre-wrapped
 *       export form). The JVM host-boundary conformance for the
 *       supported shapes is pinned by
 *       {@code test/conformance/fixtures/jvm-host-abi-slice.json};
 *       these corpus fixtures keep exercising the LuaJIT host loader,
 *       whose host implementations are Lua modules.</li>
 *   <li><b>ISSUE-0110</b> — cross-module function-value flow (an
 *       imported wrapper crossing a module boundary): the per-module
 *       wrapper classes cannot be named by the importing module.</li>
 * </ul>
 *
 * <h2>Gates</h2>
 * <ul>
 *   <li>frontend-classified files: 100% pass (zero failed);</li>
 *   <li>backend-runtime: at least 65% of the on-disk backend-runtime
 *       tests (the unchanged 253-test denominator) pass through the
 *       frontend → CompilationOrchestrator → JVM codegen → javac → JVM
 *       pipeline;</li>
 *   <li>zero unclassified skips (by construction — the classifier has
 *       no fallback skip branch, and the registry is validated);</li>
 *   <li>the runner exits non-zero when any gate fails.</li>
 * </ul>
 */
public class JvmConformanceTest {

    // =========================================================================
    // Applicability policy: the explicit skip registry
    // =========================================================================

    /** One skip-registry entry: corpus-relative path, documented reason,
     * and follow-up issue id. */
    private record SkipEntry(String path, String reason, String issue) {}

    /** The complete skip registry. Every entry must name an on-disk
     * runtime-classified corpus test; the runner validates the registry
     * against the corpus so a stale entry fails the run. */
    private static final Map<String, SkipEntry> SKIPS = new LinkedHashMap<>();
    static {
        // ---- ISSUE-0099: the async-await corpus ----
        for (String name : List.of(
                "async-await-statement", "async-cross-module",
                "async-error-propagation", "async-fn-decl", "async-fn-expr",
                "async-function-value", "async-if-branching",
                "async-import-await", "async-multiple-await",
                "async-multiple-awaits", "async-multi-sync-complete",
                "async-nested", "async-no-await", "async-nullable-return",
                "async-simple-await", "async-sync-complete",
                "async-throw-catch", "async-type-propagation",
                "async-with-params", "await-completion-check",
                "awaited-error-caught-by-caller", "await-in-call-arg",
                "await-in-if-condition", "await-in-loop-body",
                "await-returning-array-indexed",
                "await-returning-class-member", "immediate-async-await",
                "imported-async-function-value", "multiple-await-state",
                "multiple-await-state-expanded",
                "nested-await-argument-runtime", "nested-await-arguments")) {
            skip("backend-runtime/async-await/" + name + ".deal",
                "requires async/await operations (async function values, "
                    + "cross-module async flow, async error propagation)",
                "ISSUE-0099");
        }
        skip("backend-runtime/runtime-errors/async-error-code-through-module.deal",
            "requires cross-module async error propagation through an "
                + "imported async function", "ISSUE-0099");
        skip("backend-runtime/source-location/async-error-source.deal",
            "requires an async function whose thrown error crosses an await",
            "ISSUE-0099");
        skip("backend-runtime/source-location-precision/imported-async-error-source.deal",
            "requires an imported async function whose thrown error crosses "
                + "a module boundary and an await", "ISSUE-0099");

        // ---- ISSUE-0101: the @jsonable corpus and the std/json boundary ----
        for (String name : List.of(
                "fromjson-extra-key-runtime", "jsonable-complex-roundtrip",
                "jsonable-cross-module",
                "jsonable-cross-module-nested-class-array",
                "jsonable-fromjson", "jsonable-fromjson-extra-keys",
                "jsonable-fromjson-null", "jsonable-helper-exports",
                "jsonable-local-nested-class-array",
                "jsonable-malformed-input",
                "jsonable-minimal-nested-class-array-access",
                "jsonable-nested", "jsonable-nested-array-extra-key",
                "jsonable-nested-array-malformed-element",
                "jsonable-optional-nullable",
                "jsonable-optional-nullable-nested-class",
                "jsonable-roundtrip", "jsonable-table-field-nested-arrays",
                "jsonable-tojson", "jsonable-tojson-omits-missing",
                "nested-array-roundtrip",
                "optional-nullable-three-state-roundtrip",
                "table-field-roundtrip")) {
            skip("backend-runtime/jsonable/" + name + ".deal",
                "requires @jsonable code generation and the std/json "
                    + "boundary", "ISSUE-0101");
        }
        skip("backend-runtime/runtime-errors/json-stringify-function-e8001.deal",
            "requires the std/json boundary (json.stringify of a "
                + "function-holding table)", "ISSUE-0101");
        skip("backend-runtime/source-location/json-error-source.deal",
            "requires the std/json boundary (json.parse of a malformed "
                + "document)", "ISSUE-0101");
        skip("backend-runtime/source-location-precision/class-param-error-source.deal",
            "requires the std/json boundary (a parsed dynamic class value)",
            "ISSUE-0101");
        skip("backend-runtime/type-system/dynamic-array-element-e8003.deal",
            "requires the std/json boundary (json.parse of a mixed array)",
            "ISSUE-0101");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-param-e8001.deal",
            "requires the std/json boundary (json.parse builds the dynamic "
                + "class value)", "ISSUE-0101");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-return-e8001.deal",
            "requires the std/json boundary (json.parse builds the dynamic "
                + "class value)", "ISSUE-0101");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-imported-class-param-e8001.deal",
            "requires the std/json boundary (json.parse builds the dynamic "
                + "imported-class value)", "ISSUE-0101");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal",
            "requires the std/json boundary (json.parse builds the dynamic "
                + "nullable-class value)", "ISSUE-0101");
        skip("backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
            "requires the std/json boundary (json.parse builds the dynamic "
                + "array value)", "ISSUE-0101");
        skip("backend-runtime/lua-abi/jsonable-helper-name-near-collision.deal",
            "requires @jsonable helper code generation", "ISSUE-0101");
        skip("backend-runtime/lua-abi-structural/jsonable-helper-export-keys.deal",
            "requires @jsonable helper code generation", "ISSUE-0101");

        // ---- ISSUE-0100: host-ABI corpus fixtures whose host surfaces the
        // landed JVM host ABI slice does not support ----
        skip("backend-runtime/host-abi/host-array-return-ok.deal",
            "host fixture declares an array-typed return (the JVM host ABI "
                + "slice supports primitive/string/nullable returns; the "
                + "array host surface stays LuaJIT-only — pinned by "
                + "jvm-host-abi-slice.json)", "ISSUE-0100");
        skip("backend-runtime/host-abi/host-boundary-apply-function.deal",
            "host fixture declares a function-typed parameter (the JVM host "
                + "ABI slice does not adapt DEAL wrappers into host "
                + "callbacks; the shape stays LuaJIT-only)",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-class-export.deal",
            "host fixture declares class exports (Endpoints/ServerConfig "
                + "host classes — the JVM host ABI slice does not emit "
                + "host-class construction; the shape stays LuaJIT-only)",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-export-presence.deal",
            "host fixture constructs a declared host class (Config) — host "
                + "class exports stay LuaJIT-only for the JVM slice",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-nullable-function-param.deal",
            "host fixture declares a nullable function-typed parameter — "
                + "function-typed host parameters stay LuaJIT-only",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-nullable-function-param-bad.deal",
            "host fixture declares a nullable function-typed parameter — "
                + "function-typed host parameters stay LuaJIT-only",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-nullable-function-return-ok.deal",
            "host fixture declares a nullable function-typed return — "
                + "function-typed host returns stay LuaJIT-only",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-nullable-function-return-bad.deal",
            "host fixture declares a nullable function-typed return — "
                + "function-typed host returns stay LuaJIT-only",
            "ISSUE-0100");
        skip("backend-runtime/host-abi/host-prewrapped-ok.deal",
            "host fixture supplies pre-wrapped Lua exports (sig-annotated "
                + "tables) — the pre-wrapped form is a LuaJIT host-loader "
                + "mechanism with no JVM analog", "ISSUE-0100");
        skip("backend-runtime/host-abi/host-prewrapped-bad.deal",
            "host fixture supplies pre-wrapped Lua exports (sig-annotated "
                + "tables) — the pre-wrapped form is a LuaJIT host-loader "
                + "mechanism with no JVM analog", "ISSUE-0100");
        skip("backend-runtime/host-abi/host-rest-ok.deal",
            "host fixture declares an array-typed parameter (the v1.2 "
                + "fixed-array host form — array host parameters stay "
                + "LuaJIT-only for the JVM slice)", "ISSUE-0100");
        skip("backend-runtime/host-abi/host-rest-bad.deal",
            "host fixture declares an array-typed parameter (the v1.2 "
                + "fixed-array host form — array host parameters stay "
                + "LuaJIT-only for the JVM slice)", "ISSUE-0100");

        // ---- ISSUE-0110: cross-module function-value flow ----
        skip("backend-runtime/modules/imported-recursive-callback.deal",
            "passes an imported function as a callback (a cross-module "
                + "function value — the per-module wrapper classes cannot "
                + "cross a module boundary)", "ISSUE-0110");
        skip("backend-runtime/closures/closure-returned-from-module.deal",
            "an imported closure factory's returned wrapper crosses the "
                + "module boundary (cross-module function value)",
            "ISSUE-0110");
        skip("backend-runtime/modules/imported-closure-factory.deal",
            "an imported closure factory's returned wrapper crosses the "
                + "module boundary (cross-module function value)",
            "ISSUE-0110");
    }

    private static void skip(String path, String reason, String issue) {
        SKIPS.put(path, new SkipEntry(path, reason, issue));
    }

    /** Follow-up issue id → human-readable lane description, for the
     * summary's skip-group report. */
    private static final Map<String, String> FOLLOW_UP_ISSUES = Map.of(
        "ISSUE-0099", "JVM async-await slice",
        "ISSUE-0101", "JVM @jsonable slice (and the std/json JVM boundary)",
        "ISSUE-0100", "JVM host ABI slice (landed) — LuaJIT-only host corpus shapes",
        "ISSUE-0110", "JVM cross-module function-value flow"
    );

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
        /** runtime-ok / runtime-error — JVM-applicable backend test. */
        APPLICABLE,
        /** runtime test skipped under a named follow-up issue. */
        SKIPPED,
        /** known-fail MODE — tracked follow-up issue; run + stale-checked. */
        KNOWN_FAIL
    }

    private record Classified(TestFile test, Kind kind, String expectedCode,
            String skipReason, String skipIssue) {}

    private record Outcome(TestFile test, Classified classified,
            boolean pass, String message) {}

    private static final AtomicInteger frontendTotal = new AtomicInteger();
    private static final AtomicInteger frontendPassed = new AtomicInteger();
    private static final AtomicInteger frontendFailed = new AtomicInteger();
    private static final AtomicInteger applicableTotal = new AtomicInteger();
    private static final AtomicInteger applicablePassed = new AtomicInteger();
    private static final AtomicInteger applicableFailed = new AtomicInteger();
    private static final AtomicInteger applicableSkipped = new AtomicInteger();
    private static final AtomicInteger knownFailTotal = new AtomicInteger();
    private static final AtomicInteger knownFailTracked = new AtomicInteger();
    private static final AtomicInteger knownFailStale = new AtomicInteger();

    private static final List<Outcome> outcomes =
        Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> skipGroupCounts =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Integer> knownFailByIssue =
        Collections.synchronizedMap(new LinkedHashMap<>());

    private static final ThreadLocal<StringBuilder> WORKER_OUTPUT =
        new ThreadLocal<>();
    private static final Object CONSOLE_LOCK = new Object();

    private static boolean jvmAvailable;
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

        System.out.println("=== DEAL v1.2 JVM Conformance Suite (ISSUE-0102) ===");
        System.out.println("Root: " + conformanceRoot);
        System.out.println("JVM (javac + java): " + (jvmAvailable ? "available"
            : "NOT available (backend-runtime tests will fail — a bypassed "
                + "JVM execution is not a pass)"));
        System.out.println();

        List<Classified> tests = classifyAll(discoverTests());
        int frontend = (int) tests.stream()
            .filter(c -> c.kind() == Kind.FRONTEND).count();
        int applicable = (int) tests.stream()
            .filter(c -> c.kind() == Kind.APPLICABLE).count();
        int skipped = (int) tests.stream()
            .filter(c -> c.kind() == Kind.SKIPPED).count();
        int knownFail = (int) tests.stream()
            .filter(c -> c.kind() == Kind.KNOWN_FAIL).count();
        System.out.println("Discovered " + tests.size() + " conformance "
            + "test(s): " + frontend + " frontend-classified, "
            + applicable + " JVM-applicable backend-runtime, "
            + skipped + " skipped (classified), " + knownFail
            + " known-fail (tracked)");
        System.out.println();

        // Deterministic execution order: sorted by corpus-relative path.
        List<Classified> ordered = new ArrayList<>(tests);
        ordered.sort(Comparator.comparing(c -> c.test().relativePath()));

        int workers = Math.max(1,
            Math.min(Runtime.getRuntime().availableProcessors(),
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
        printReport(sorted, applicable + skipped + knownFail);
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
     * The {@link Kind#SKIPPED} branch is only ever reached through a
     * registry entry with a reason and a follow-up issue id — there is
     * no unclassified fallback skip branch.
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
                result.add(new Classified(test, Kind.KNOWN_FAIL, code, null,
                    test.issue()));
            } else if (expected.startsWith("compile-ok")) {
                result.add(new Classified(test, Kind.FRONTEND, "", null,
                    null));
            } else if (expected.startsWith("compile-error ")) {
                result.add(new Classified(test, Kind.FRONTEND,
                    expected.substring("compile-error ".length()).trim(),
                    null, null));
            } else if (expected.startsWith("runtime-ok")
                    || expected.startsWith("runtime-error ")) {
                String code = expected.startsWith("runtime-error ")
                    ? expected.substring("runtime-error ".length()).trim()
                    : "";
                SkipEntry entry = SKIPS.get(test.relativePath());
                if (entry != null) {
                    result.add(new Classified(test, Kind.SKIPPED, code,
                        entry.reason(), entry.issue()));
                } else {
                    result.add(new Classified(test, Kind.APPLICABLE, code,
                        null, null));
                }
            } else {
                // Unknown @expected is a harness failure, never a skip.
                throw new IllegalStateException("unknown @expected '"
                    + expected + "' in " + test.relativePath());
            }
        }
        validateRegistry();
        return result;
    }

    /**
     * Validates the explicit skip registry against the on-disk corpus:
     * every registry entry must name an existing runtime-classified
     * backend-runtime test (a stale entry fails the run), and the
     * registry must have produced no skip without a reason or issue id.
     */
    private static void validateRegistry() {
        for (SkipEntry entry : SKIPS.values()) {
            Path file = conformanceRoot.resolve(entry.path());
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " does not name an on-disk corpus "
                    + "test — the registry must stay current");
            }
            if (entry.reason() == null || entry.reason().isEmpty()
                    || entry.issue() == null || entry.issue().isEmpty()) {
                throw new IllegalStateException("skip registry entry "
                    + entry.path() + " lacks a reason or follow-up issue id");
            }
        }
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
                default -> {
                    // SKIPPED: recorded once, reported in the summary.
                    applicableSkipped.incrementAndGet();
                    skipGroupCounts.merge(classified.skipIssue(), 1,
                        Integer::sum);
                    log("  [" + classified.test().relativePath()
                        + "] SKIP (" + classified.skipIssue() + "): "
                        + classified.skipReason());
                    yield null;
                }
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
            } else {
                applicableFailed.incrementAndGet();
                outcomes.add(new Outcome(classified.test(), classified,
                    false, "runner exception: " + e.getMessage()));
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
        List<Diagnostic> diags = frontendDiagnostics(test.path());
        boolean hasErrors = diags.stream()
            .anyMatch(d -> "error".equals(d.severity()));
        String expected = test.expected();
        if (expected.startsWith("compile-ok")) {
            if (hasErrors) {
                frontendFailed.incrementAndGet();
                StringBuilder sb = new StringBuilder();
                for (Diagnostic d : diags) {
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
            .map(Diagnostic::code)
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
    private static List<Diagnostic> frontendDiagnostics(Path file) {
        List<Diagnostic> all = new ArrayList<>();
        try {
            String source = Files.readString(file);
            String filename = file.toString();

            LexResult lex = new Lexer(source, filename).tokenize();
            all.addAll(lex.diagnostics());
            if (lex.hasErrors()) return all;

            Parser parser = new Parser(lex.tokens(), filename);
            ParseResult parseResult = parser.parse();
            all.addAll(parseResult.diagnostics());
            if (parseResult.hasErrors()) return all;

            FrontendModuleResolver resolver = new FrontendModuleResolver(file);
            NameResolver nr = new NameResolver(filename, resolver);
            SymbolTable symTable;
            try {
                symTable = nr.resolve(parseResult.program());
            } catch (Exception e) {
                all.add(Diagnostic.error("E9999", e.getMessage(),
                    filename, 1, 1));
                return all;
            }
            all.addAll(nr.diagnostics());

            CheckResult result = TypeChecker.check(filename, symTable, nr,
                parseResult.program());
            all.addAll(result.diagnostics());
            return all;
        } catch (IOException e) {
            all.add(Diagnostic.error("E9999", "cannot read " + file,
                file.toString(), 1, 1));
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

        FrontendModuleResolver(Path testFile) {
            this.testFileDir = testFile.toAbsolutePath().getParent();
            this.stdlibExports = StdlibModuleResolver.stdlibExports();
        }

        @Override
        public Map<String, Type> resolveModule(String modulePath,
                String importingModule, Set<String> modulesInProgress)
                throws ModuleNotFoundException {
            if (stdlibExports.containsKey(modulePath)) {
                return stdlibExports.get(modulePath);
            }
            if (modulePath.startsWith("std/") && !modulePath.startsWith("./")
                    && !modulePath.startsWith("../")) {
                throw new ModuleNotFoundException(
                    "Module not found: '" + modulePath
                    + "' is not a spec-listed stdlib module");
            }
            Path resolved = resolveRelativePath(modulePath);
            if (resolved != null && Files.exists(resolved)) {
                try {
                    String source = Files.readString(resolved);
                    boolean isDecl = resolved.toString().endsWith(".d.deal");
                    LexResult lex = new Lexer(source, resolved.toString())
                        .tokenize();
                    if (lex.hasErrors()) {
                        throw new ModuleNotFoundException("Lex errors in "
                            + resolved);
                    }
                    Parser parser = new Parser(lex.tokens(),
                        resolved.toString());
                    ParseResult parseResult = parser.parse();
                    if (parseResult.hasErrors()) {
                        throw new ModuleNotFoundException("Parse errors in "
                            + resolved);
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
        public Type resolveTypeNodeInModule(TypeNode typeNode,
                String modulePath, String importingModule)
                throws ModuleNotFoundException {
            if (modulePath == null || modulePath.isEmpty()) return null;
            Path resolved = resolveRelativePath(modulePath);
            if (resolved == null || !Files.exists(resolved)) return null;
            try {
                String source = Files.readString(resolved);
                LexResult lex = new Lexer(source, resolved.toString())
                    .tokenize();
                if (lex.hasErrors()) return null;
                Parser parser = new Parser(lex.tokens(),
                    resolved.toString());
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
                String source = Files.readString(file);
                LexResult lex = new Lexer(source, file.toString()).tokenize();
                if (lex.hasErrors()) return symbols;
                Parser parser = new Parser(lex.tokens(), file.toString());
                ParseResult parseResult = parser.parse();
                if (parseResult.hasErrors()) return symbols;
                String dotted = modulePathOf(file);
                for (StatementNode stmt : parseResult.program().statements()) {
                    if (stmt instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted));
                    } else if (stmt instanceof ExportDeclaration exp
                            && exp.declaration() instanceof ClassDeclaration cd) {
                        symbols.put(cd.name(), new Symbol.ClassSymbol(
                            cd.name(), cd.fields(), dotted));
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
        TestFile test = classified.test();
        if (!knownFailProbe) {
            applicableTotal.incrementAndGet();
        }
        if (!jvmAvailable) {
            applicableFailed.incrementAndGet();
            log("  [" + test.relativePath()
                + "] FAIL (javac/java unavailable — the JVM execution "
                + "stage cannot be bypassed)");
            return new Outcome(test, classified, false,
                "javac/java unavailable");
        }

        Path projectRoot = null;
        try {
            projectRoot = Files.createTempDirectory("deal_jvm_conf_");

            // 1. Materialize the test file and its transitive companion
            // closure into the temp project root (flat stem namespace;
            // the corpus has no duplicate stems). Relative imports
            // resolve on disk from the corpus directories.
            Map<String, Path> written = new LinkedHashMap<>();
            writeModuleFiles(projectRoot, test.path(), written);
            String entryRel = corpusStem(test.path()) + ".deal";
            Path entryFile = projectRoot.resolve(entryRel);
            Path outputRoot = projectRoot.resolve("out");

            // 2. Host-ABI tests: a real deal.json externals entry wires
            // the raw host import path to the host declaration, and the
            // Java host implementation compiles with the artifacts.
            DealConfig config = null;
            Set<String> hostNames = hostImports(test.path());
            if (!hostNames.isEmpty()) {
                StringBuilder dealJson = new StringBuilder();
                dealJson.append("{\n  \"languageVersion\": \"1.2\",\n");
                dealJson.append("  \"externals\": {\n");
                boolean first = true;
                for (String hostName : hostNames) {
                    if (!first) dealJson.append(",\n");
                    first = false;
                    Path decl = hostFixturesRoot.resolve(hostName
                        + ".d.deal");
                    if (!Files.isRegularFile(decl)) {
                        throw new IllegalStateException("host declaration "
                            + "missing for " + hostName);
                    }
                    String declRel = "bindings/" + hostName + ".d.deal";
                    Files.createDirectories(
                        projectRoot.resolve("bindings"));
                    Files.copy(decl, projectRoot.resolve(declRel));
                    dealJson.append("    \"host/").append(hostName)
                        .append("\": { \"declaration\": \"")
                        .append(declRel).append("\" }");
                }
                dealJson.append("\n  }\n}\n");
                Files.writeString(projectRoot.resolve("deal.json"),
                    dealJson);
                config = DealConfig.load(projectRoot);
                if (config == null) {
                    throw new IllegalStateException(
                        "generated deal.json did not load");
                }
            }

            // 3. The real whole-project pipeline: module discovery,
            // signature extraction, dependency ordering, name resolution,
            // type checking, per-module JvmBackend codegen.
            OrchestratorRun run = runOrchestrator(projectRoot, entryFile,
                outputRoot, config);
            if (!run.success()) {
                if (knownFailProbe) {
                    return new Outcome(test, classified, false,
                        "orchestrator compile failed: " + run.diagnostics());
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
            ProgramNode entryProgram = parseEntryProgram(entryFile);
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
                                   List<Diagnostic> diagnostics,
                                   String capturedOutput) {}

    /**
     * Runs the real {@link CompilationOrchestrator} with
     * {@link Backend#JVM} over the temp project: module discovery,
     * signature extraction, dependency ordering, name resolution, type
     * checking, and per-module JvmBackend codegen into
     * {@code outputRoot}. Stdout/stderr is captured so per-test output
     * stays clean.
     */
    private static OrchestratorRun runOrchestrator(Path projectRoot,
            Path entryFile, Path outputRoot, DealConfig config) {
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
                        entryFile.toAbsolutePath().normalize(),
                        outputRoot.toAbsolutePath().normalize(),
                        false, false, false, Backend.JVM,
                        config,
                        List.of(projectRoot.toAbsolutePath().normalize()),
                        null);
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
        copyTransitively(entry, projectRoot, written);
    }

    private static void copyTransitively(Path file, Path projectRoot,
            Map<String, Path> written) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (written.containsKey(normalized.toString())) return;
        Path target = projectRoot.resolve(corpusStem(normalized)
            + ".deal");
        Files.copy(normalized, target);
        written.put(normalized.toString(), target);
        for (String importPath : relativeImports(normalized)) {
            Path resolved = resolveCompanionPath(importPath,
                normalized.getParent());
            if (resolved != null) {
                copyTransitively(resolved, projectRoot, written);
            }
        }
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
     */
    private static ProgramNode parseEntryProgram(Path entryFile)
            throws IOException {
        String source = Files.readString(entryFile);
        LexResult lex = new Lexer(source, entryFile.toString()).tokenize();
        if (lex.hasErrors()) {
            throw new IllegalStateException("entry module lex errors: "
                + lex.diagnostics());
        }
        ParseResult parse = new Parser(lex.tokens(),
            entryFile.toString()).parse();
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
        System.out.println("=== JVM Conformance Summary (ISSUE-0102) ===");

        int ft = frontendTotal.get();
        int fp = frontendPassed.get();
        int ff = frontendFailed.get();
        System.out.printf("Frontend (backend-neutral compile-ok/"
            + "compile-error): total %d, passed %d, failed %d%n",
            ft, fp, ff);

        int at = applicableTotal.get();
        int ap = applicablePassed.get();
        int af = applicableFailed.get();
        int as = applicableSkipped.get();
        int kf = knownFailTracked.get();
        int denominator = runtimeDenominator();
        double pct = denominator == 0 ? 0.0
            : (ap * 100.0 / denominator);
        System.out.printf("Backend-runtime on JVM: denominator %d "
            + "(every on-disk runtime test, unchanged), passed %d, "
            + "failed %d, skipped %d (classified), known-fail %d "
            + "(tracked) — pass rate %.1f%%%n",
            denominator, ap, af, as, kf, pct);
        System.out.println();

        System.out.println("Skipped backend-runtime groups (every skip "
            + "carries a reason and a follow-up issue id):");
        List<String> issues = new ArrayList<>(skipGroupCounts.keySet());
        Collections.sort(issues);
        for (String issue : issues) {
            System.out.printf("  %-11s %-60s %d test(s)%n",
                issue, FOLLOW_UP_ISSUES.getOrDefault(issue, ""),
                skipGroupCounts.get(issue));
        }
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
        if (ff > 0) {
            System.out.println("GATE FAILURE: " + ff
                + " frontend test(s) failed — 100% required");
            ok = false;
        }
        if (pct < 65.0) {
            System.out.println("GATE FAILURE: backend-runtime pass rate "
                + pct + "% below the 65% threshold (denominator "
                + denominator + ")");
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
            + ">= 65% over the unchanged " + denominator
            + "-test denominator; zero unclassified skips.");
    }
}
