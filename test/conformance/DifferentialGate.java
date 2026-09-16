package deal.test.conformance;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.SemanticProfile;
import deal.test.ConformanceHarnessMetadata;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The v1.2 differential gate core entry point (ISSUE-0353): discovery,
 * authoritative classification, sidecar loading and schema validation,
 * backend-neutral frontend execution, compile-diagnostic comparison,
 * lane dispatch with harness-owned deadlines, and ordinary mismatch
 * failures. The {@link GateRun} carries every per-case
 * {@link GateDispatcher.CaseVerdict} for integration tests.
 */
public final class DifferentialGate {

    private DifferentialGate() {
        // Static entry point; no instances.
    }

    /** The harness-owned per-lane deadline (G7; the gate budget term). */
    public static final Duration DEFAULT_LANE_DEADLINE = Duration.ofSeconds(120);

    /** One gate failure: kind, subject, and the bounded detail. */
    public record GateFailure(String kind, String subject, String detail) {

        public GateFailure {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }

        /** The gate-readable one-line failure. */
        public String message() {
            return subject + " [" + kind + "] " + detail;
        }
    }

    /** One fixture with its loaded sidecar (runtime or compile pin). */
    public record LoadedFixture(CorpusDiscovery.Fixture fixture,
                                SidecarGateLoader.LoadResult load) {

        public LoadedFixture {
            Objects.requireNonNull(fixture, "fixture must not be null");
            Objects.requireNonNull(load, "load must not be null");
        }
    }

    /**
     * The complete gate run: fixtures, phase results, verdicts, counters,
     * and the overall ok flag.
     */
    public record GateRun(
        Path conformanceRoot,
        List<CorpusDiscovery.Fixture> fixtures,
        List<LoadedFixture> loadedFixtures,
        List<GateFailure> failures,
        int frontendCompiled,
        int compileDiagnosticComparisons,
        int runtimeCasesDispatched,
        int runtimeCasesWithoutLanes,
        List<GateDispatcher.CaseVerdict> verdicts,
        Map<String, int[]> perBackend,
        boolean ok
    ) {

        public GateRun {
            fixtures = List.copyOf(fixtures);
            loadedFixtures = List.copyOf(loadedFixtures);
            failures = List.copyOf(failures);
            verdicts = List.copyOf(verdicts);
            perBackend = Map.copyOf(perBackend);
        }

        /** The classification failures of the run (a failures subset). */
        public List<GateFailure> classificationFailures() {
            return failures.stream()
                .filter(f -> "classification".equals(f.kind()))
                .toList();
        }
    }

    /**
     * Runs the gate over one conformance root and returns the complete
     * run result. Read-only over the corpus; lane executions happen only
     * for registered lanes.
     *
     * @param conformanceRoot the conformance root (any path form)
     * @param lanes           the registered lane implementations by
     *                        backend name (empty defers the dispatch)
     * @param parallelism     the worker-pool bound (the gate passes
     *                        available processors)
     * @param laneDeadline    the harness-owned per-lane deadline
     * @param out             the gate report stream
     */
    public static GateRun run(Path conformanceRoot, Map<String, Lane> lanes,
            int parallelism, Duration laneDeadline, PrintStream out)
            throws IOException {
        Objects.requireNonNull(conformanceRoot, "conformanceRoot must not be null");
        Objects.requireNonNull(lanes, "lanes must not be null");
        Objects.requireNonNull(laneDeadline, "laneDeadline must not be null");
        Objects.requireNonNull(out, "out must not be null");
        Path root = conformanceRoot.toAbsolutePath().normalize();
        List<GateFailure> failures = new ArrayList<>();
        Map<String, int[]> perBackend = new LinkedHashMap<>();
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            perBackend.put(backend, new int[] {0, 0});
        }

        out.println("=== DEAL v1.2 Differential Gate (core, ISSUE-0353) ===");
        out.println("Root: " + root);
        out.println();

        // ---------------------------------------------------------------------
        // Phase 1: discovery + classification.
        // ---------------------------------------------------------------------
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(root);
        Map<String, CorpusDiscovery.Fixture> corpusByPath =
            discovery.corpusByPath();
        Set<String> corpusModuleIndex = discovery.corpusModuleIndex();
        for (SidecarSchemaValidator.ClassificationFailure failure
                : discovery.failures()) {
            failures.add(new GateFailure("classification", failure.fixturePath(),
                failure.field() + ": " + failure.reason()));
            out.println("  CLASSIFICATION FAILURE: " + failure.message());
        }
        Map<CorpusDiscovery.Kind, Integer> kindCounts = new LinkedHashMap<>();
        for (CorpusDiscovery.Kind kind : CorpusDiscovery.Kind.values()) {
            kindCounts.put(kind, 0);
        }
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.classification() != null) {
                kindCounts.merge(fixture.classification().kind(), 1,
                    Integer::sum);
            }
        }
        out.println("Discovered " + discovery.fixtures().size()
            + " conformance fixture(s) (" + kindCounts + ")");
        out.println();

        // ---------------------------------------------------------------------
        // Phase 2: sidecar loading + schema validation (T1 wiring).
        // ---------------------------------------------------------------------
        List<LoadedFixture> loaded = new ArrayList<>();
        List<LoadedFixture> runtimeLoaded = new ArrayList<>();
        List<LoadedFixture> pinLoaded = new ArrayList<>();
        int straySidecars = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path sidecar : stream
                    .filter(p -> p.toString().endsWith(".expect.json"))
                    .sorted()
                    .toList()) {
                Path sibling = sidecarSibling(sidecar);
                if (!Files.exists(sibling) || !corpusByPath.containsKey(
                        CorpusDiscovery.slash(root.relativize(sibling)))) {
                    failures.add(new GateFailure("classification",
                        CorpusDiscovery.slash(root.relativize(sidecar)),
                        "<sidecar>: stray sidecar file — a sidecar may exist "
                            + "only next to a discovered fixture"));
                    straySidecars++;
                }
            }
        }
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            if (fixture.classification() == null) {
                continue; // classification failure already recorded
            }
            SidecarGateLoader.LoadResult load = SidecarGateLoader.load(fixture,
                corpusByPath, corpusModuleIndex, root);
            LoadedFixture loadedFixture = new LoadedFixture(fixture, load);
            loaded.add(loadedFixture);
            if (load.failed()) {
                failures.add(new GateFailure("classification",
                    load.failure().get().fixturePath(),
                    load.failure().get().field() + ": "
                        + load.failure().get().reason()));
                out.println("  CLASSIFICATION FAILURE: "
                    + load.failure().get().message());
            } else if (load.runtime().isPresent()) {
                runtimeLoaded.add(loadedFixture);
            } else if (load.compilePin().isPresent()) {
                pinLoaded.add(loadedFixture);
            }
        }
        int runtimeOk = (int) runtimeLoaded.stream()
            .filter(f -> "runtime-ok".equals(
                f.fixture().classification().runtimeSidecarMode()))
            .count();
        int runtimeError = runtimeLoaded.size() - runtimeOk;
        out.println("Sidecars: " + runtimeLoaded.size() + " runtime sidecar(s) "
            + "validated (" + runtimeOk + " runtime-ok, " + runtimeError
            + " runtime-error), " + pinLoaded.size() + " compile pin(s), "
            + straySidecars + " stray");
        out.println();

        // ---------------------------------------------------------------------
        // Phase 2b: frontend corpus execution (G1, ISSUE-0357): every
        // compile-ok fixture must compile clean and every compile-error
        // fixture without a Compile Expectation Sidecar must reject with
        // its exact pinned code, through the real backend-neutral frontend
        // with the per-case A5 profile and the corpus module resolver
        // (stdlib exports, relative corpus imports). No backend executes.
        // ---------------------------------------------------------------------
        int frontendCompiled = 0;
        Set<String> pinFixturePaths = new java.util.HashSet<>();
        for (LoadedFixture loadedFixture : pinLoaded) {
            pinFixturePaths.add(loadedFixture.fixture().corpusPath());
        }
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            CorpusDiscovery.Classification classification =
                fixture.classification();
            if (classification == null
                    || classification.kind()
                        == CorpusDiscovery.Kind.COMPANION) {
                continue;
            }
            CorpusDiscovery.Kind kind = classification.kind();
            if (kind != CorpusDiscovery.Kind.COMPILE_OK
                    && kind != CorpusDiscovery.Kind.COMPILE_ERROR) {
                continue;
            }
            if (kind == CorpusDiscovery.Kind.COMPILE_ERROR
                    && pinFixturePaths.contains(fixture.corpusPath())) {
                continue; // the Compile Diagnostic comparison owns the pin
            }
            frontendCompiled++;
            SemanticProfile profile = ConformanceHarnessMetadata
                .profileFromMetadata(fixture.rawSource(), fixture.corpusPath());
            List<CompilerDiagnostic> errors = FrontendCompiler.errorDiagnostics(
                fixture.source(), fixture.corpusPath(), profile,
                new CorpusFrontendResolver(fixture.corpusPath(), root,
                    corpusByPath, profile));
            List<String> codes = errorCodes(errors);
            if (kind == CorpusDiscovery.Kind.COMPILE_OK) {
                if (errors.isEmpty()) {
                    out.println("  [" + fixture.corpusPath()
                        + "] frontend OK (compile-ok)");
                } else {
                    failures.add(new GateFailure("frontend-compile",
                        fixture.corpusPath(),
                        "the compile-ok fixture must compile clean, got: "
                            + codes));
                    out.println("  FRONTEND-COMPILE FAILURE: "
                        + fixture.corpusPath() + " — unexpected compile "
                        + "errors: " + codes);
                }
            } else {
                String code = classification.code();
                if (codes.contains(code)) {
                    out.println("  [" + fixture.corpusPath()
                        + "] frontend OK (found " + code + ")");
                } else {
                    failures.add(new GateFailure("frontend-compile",
                        fixture.corpusPath(),
                        "the compile-error fixture must produce " + code
                            + ", got: " + codes));
                    out.println("  FRONTEND-COMPILE FAILURE: "
                        + fixture.corpusPath() + " — expected " + code
                        + ", got: " + codes);
                }
            }
        }
        out.println("Frontend compiled: " + frontendCompiled + " fixture(s) ("
            + failures.stream().filter(
                f -> "frontend-compile".equals(f.kind())).count()
            + " failure(s))");
        out.println();

        // ---------------------------------------------------------------------
        // Phase 3: Compile Diagnostic comparison (real frontend, no backend).
        // ---------------------------------------------------------------------
        int compileComparisons = 0;
        for (LoadedFixture loadedFixture : pinLoaded) {
            CorpusDiscovery.Fixture fixture = loadedFixture.fixture();
            compileComparisons++;
            List<CompilerDiagnostic> errors = FrontendCompiler.errorDiagnostics(
                fixture.source(), fixture.corpusPath());
            Optional<GateMismatch> mismatch = CompileDiagnosticComparator.compare(
                fixture.corpusPath(), loadedFixture.load().compilePin().get(),
                errors, fixture.headerLinesStripped());
            if (mismatch.isPresent()) {
                failures.add(new GateFailure("compile-diagnostic",
                    mismatch.get().subject(), mismatch.get().detail()));
                out.println("  COMPILE_DIAGNOSTIC_MISMATCH: "
                    + mismatch.get().message());
            } else {
                out.println("  [" + fixture.corpusPath() + "] compile pin OK ("
                    + errors.get(0).code() + ")");
            }
        }
        out.println();

        // ---------------------------------------------------------------------
        // Phase 5: runtime dispatch (G7 worker pool + per-lane deadlines).
        // ---------------------------------------------------------------------
        int dispatched = 0;
        int withoutLanes = 0;
        List<GateDispatcher.CaseVerdict> verdicts = List.of();
        if (lanes.isEmpty()) {
            withoutLanes = runtimeLoaded.size();
            out.println("Runtime dispatch: no lanes registered; "
                + withoutLanes + " runtime case(s) were not executed");
        } else {
            List<GateDispatcher.CaseInput> cases = new ArrayList<>();
            for (LoadedFixture loadedFixture : runtimeLoaded) {
                CorpusDiscovery.Fixture fixture = loadedFixture.fixture();
                List<LaneCase> laneCases = new ArrayList<>();
                for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
                    laneCases.add(new LaneCase(fixture.corpusPath(), backend,
                        fixture.file(),
                        CorpusDiscovery.compilationSet(fixture, corpusByPath,
                            root),
                        loadedFixture.load().runtime().get()
                            .expectationFor(backend)));
                }
                cases.add(new GateDispatcher.CaseInput(fixture.corpusPath(),
                    laneCases));
            }
            verdicts = GateDispatcher.run(cases, lanes, parallelism,
                laneDeadline);
            dispatched = verdicts.size();
            for (GateDispatcher.CaseVerdict verdict : verdicts) {
                for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                    int[] counts = perBackend.get(outcome.backend());
                    if (outcome.passed()) {
                        counts[0]++;
                    } else {
                        counts[1]++;
                    }
                }

                for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                    if (outcome.mismatch().isPresent()) {
                        GateMismatch mismatch = outcome.mismatch().get();
                        String kind = mismatch.infrastructure()
                            ? "infrastructure"
                            : "lane";
                        failures.add(new GateFailure(kind,
                            outcome.backend() + " " + verdict.fixturePath(),
                            mismatch.detail()));
                        out.println("  [" + verdict.fixturePath() + "] "
                            + outcome.backend() + " "
                            + mismatch.clazz() + ": " + mismatch.detail());
                    }
                }
                if (verdict.passed()) {
                    out.println("  [" + verdict.fixturePath()
                        + "] PASS (all three lanes)");
                }
            }
        }
        out.println();

        // ---------------------------------------------------------------------
        // Summary / verdict.
        // ---------------------------------------------------------------------
        printSummary(out, discovery, failures, frontendCompiled,
            compileComparisons, dispatched, withoutLanes, perBackend);
        return new GateRun(root, discovery.fixtures(), loaded, failures,
            frontendCompiled, compileComparisons, dispatched, withoutLanes,
            verdicts, perBackend, failures.isEmpty());
    }

    private static List<String> errorCodes(List<CompilerDiagnostic> diagnostics) {
        List<String> codes = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            codes.add(diagnostic.code());
        }
        return codes;
    }

    private static Path sidecarSibling(Path sidecar) {
        String name = sidecar.getFileName().toString();
        String dealName = name.substring(0,
            name.length() - ".expect.json".length()) + ".deal";
        return sidecar.resolveSibling(dealName);
    }

    private static void printSummary(PrintStream out,
            CorpusDiscovery.DiscoveryResult discovery,
            List<GateFailure> failures, int frontendCompiled,
            int compileComparisons, int dispatched, int withoutLanes,
            Map<String, int[]> perBackend) {
        out.println("=== Differential Gate Summary ===");
        out.println("Fixtures: " + discovery.fixtures().size());
        out.println("Frontend compiled: " + frontendCompiled
            + " (failures: " + failures.stream().filter(
                f -> "frontend-compile".equals(f.kind())).count() + ")");
        out.println("Classification failures: "
            + failures.stream().filter(f -> "classification".equals(f.kind()))
                .count());
        out.println("Compile diagnostics compared: " + compileComparisons
            + " (failures: "
            + failures.stream().filter(
                f -> "compile-diagnostic".equals(f.kind())).count() + ")");
        out.println("Runtime cases: " + dispatched + " dispatched, "
            + withoutLanes + " without registered lanes");
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            int[] counts = perBackend.get(backend);
            out.println("  " + backend + ": " + counts[0] + " passed, "
                + counts[1] + " failed");
        }
        out.println("Differential failures: "
            + failures.stream().filter(f -> "lane".equals(f.kind())).count());
        out.println("Infrastructure failures: "
            + failures.stream().filter(f -> "infrastructure".equals(f.kind()))
                .count());
        out.println("RESULT: " + (failures.isEmpty() ? "PASS" : "FAIL"));
    }

    // =========================================================================
    // Main
    // =========================================================================

    /**
     * The gate's own deal.test entry point. Exercises discovery,
     * classification, sidecar validation, the frontend corpus execution,
     * and compile-diagnostic comparison over the real on-disk corpus.
     * Runtime cases are not executed when this frontend-only entry point
     * has no registered lanes.
     */
    public static void main(String[] args) throws Exception {
        Path root = args.length > 0
            ? Path.of(args[0])
            : Path.of("test", "conformance");
        GateRun run = run(root, Map.of(),
            Runtime.getRuntime().availableProcessors(),
            DEFAULT_LANE_DEADLINE, System.out);
        if (!run.ok()) {
            System.exit(1);
        }
    }
}
