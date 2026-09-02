package deal.test.conformance;

import deal.diagnostics.CompilerDiagnostic;

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
 * The v1.2 differential gate core entry point (ISSUE-0353; design
 * {@code v12-zero-skip-conformance-gate} G1): discovery + classification
 * with the pre-flip grammar, sidecar loading + schema validation wired
 * to T1's {@link SidecarSchemaValidator} with per-fixture compilation
 * sets, the Compile Diagnostic comparison over the real backend-neutral
 * frontend, the pre-flip known-fail forced-promotion probes, the lane
 * dispatch seam (G7 worker pool + harness-owned per-lane deadlines), and
 * the verdict/summary with the closed G6 mismatch classes.
 *
 * <p>This child lands the gate core only: no lane implementation is
 * registered yet (the LuaJIT/JVM/JS lanes land in the T7-T9 lane
 * children), so the runtime dispatch phase reports the deferral
 * explicitly and the legacy runners keep executing the runtime corpus
 * during the G5 temporary-coexistence window. The Coverage Manifest
 * Validator is not wired either (T14 wires it when the manifest is
 * complete); the pre-flip tolerances — the {@code known-fail} grammar
 * and its forced-promotion rules — stay active until the flip deletes
 * them (G2/G8).</p>
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
        int compileDiagnosticComparisons,
        int knownFailuresTracked,
        int runtimeCasesDispatched,
        int runtimeCasesDeferred,
        Map<String, int[]> perBackend,
        int skipped,
        boolean ok
    ) {

        public GateRun {
            fixtures = List.copyOf(fixtures);
            loadedFixtures = List.copyOf(loadedFixtures);
            failures = List.copyOf(failures);
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
     *                        backend name (empty in this child)
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
        // Phase 1: discovery + classification (pre-flip grammar).
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
        // Phase 4: known-fail forced-promotion probes (pre-flip tolerance).
        // ---------------------------------------------------------------------
        int knownFailures = 0;
        for (CorpusDiscovery.Fixture fixture : discovery.fixtures()) {
            CorpusDiscovery.Classification classification =
                fixture.classification();
            if (classification == null
                    || classification.kind() != CorpusDiscovery.Kind.KNOWN_FAIL) {
                continue;
            }
            String mode = classification.knownFailMode();
            if (mode.startsWith("runtime") && !lanes.isEmpty()) {
                // The runtime-mode probe is the lane dispatch verdict
                // (phase 5) — never double-probed here.
                continue;
            }
            Optional<KnownFailProbe> probeResult =
                probeKnownFailMode(fixture, mode, lanes);
            if (probeResult.isEmpty()) {
                continue; // probed through the lane dispatch verdict
            }
            KnownFailProbe probe = probeResult.get();
            switch (probe) {
                case KnownFailProbe.Stale stale -> {
                    failures.add(new GateFailure("stale known-fail",
                        fixture.corpusPath(),
                        "the v1.2 requirement tracked by " + fixture.issue()
                            + " now passes (" + stale.detail() + ") — "
                            + "promotion instruction: set '@expected: " + mode
                            + "' and drop the @issue tag"));
                    out.println("  STALE KNOWN-FAIL: " + fixture.corpusPath()
                        + " — promote the fixture: set '@expected: " + mode
                        + "' and drop the @issue tag");
                }
                case KnownFailProbe.Tracked tracked -> {
                    knownFailures++;
                    out.println("  KNOWN-FAIL (tracked by " + fixture.issue()
                        + "): " + fixture.corpusPath() + " — "
                        + tracked.detail());
                }
                default -> throw new IllegalStateException(
                    "closed probe result");
            }
        }
        out.println();

        // ---------------------------------------------------------------------
        // Phase 5: runtime dispatch (G7 worker pool + per-lane deadlines).
        // ---------------------------------------------------------------------
        int dispatched = 0;
        int deferred = 0;
        if (lanes.isEmpty()) {
            // This build child registers no lane implementations (they land
            // in T7-T9): the dispatch phase is deferred explicitly — never
            // silently — and the legacy runners keep executing the runtime
            // corpus during the G5 temporary-coexistence window.
            deferred = runtimeLoaded.size();
            out.println("Runtime dispatch: DEFERRED — no lane implementation "
                + "is registered in this build child (the LuaJIT/JVM/JS lanes "
                + "land in T7-T9); " + deferred + " runtime case(s) remain "
                + "with the legacy runners until the lanes land");
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
            List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(cases,
                lanes, parallelism, laneDeadline);
            dispatched = verdicts.size();
            for (GateDispatcher.CaseVerdict verdict : verdicts) {
                CorpusDiscovery.Fixture fixture = fixtureByPath(
                    discovery.fixtures(), verdict.fixturePath());
                boolean passed = verdict.passed();
                boolean infrastructure = false;
                for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                    int[] counts = perBackend.get(outcome.backend());
                    if (outcome.passed()) {
                        counts[0]++;
                    } else {
                        counts[1]++;
                        if (outcome.mismatch().isPresent()
                                && outcome.mismatch().get().infrastructure()) {
                            infrastructure = true;
                        }
                    }
                }
                boolean knownFailRuntime = fixture != null
                    && fixture.classification() != null
                    && fixture.classification().kind()
                        == CorpusDiscovery.Kind.KNOWN_FAIL;
                if (passed && knownFailRuntime) {
                    // Pre-flip forced promotion: a stale known-fail runtime
                    // marker fails the gate with a promotion instruction.
                    failures.add(new GateFailure("stale known-fail",
                        verdict.fixturePath(),
                        "the runtime requirement tracked by " + fixture.issue()
                            + " now passes on every backend — promotion "
                            + "instruction: set '@expected: "
                            + fixture.classification().knownFailMode()
                            + "' and drop the @issue tag"));
                    out.println("  STALE KNOWN-FAIL: " + verdict.fixturePath()
                        + " — promote the fixture");
                } else if (!passed && knownFailRuntime && !infrastructure) {
                    knownFailures++;
                    out.println("  KNOWN-FAIL (tracked by " + fixture.issue()
                        + "): " + verdict.fixturePath()
                        + " — the underlying mode still fails");
                } else {
                    for (GateDispatcher.LaneOutcome outcome
                            : verdict.outcomes()) {
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
        }
        out.println();

        // ---------------------------------------------------------------------
        // Summary / verdict (pre-flip counters, closed classes).
        // ---------------------------------------------------------------------
        printSummary(out, discovery, failures, compileComparisons,
            knownFailures, dispatched, deferred, perBackend);
        return new GateRun(root, discovery.fixtures(), loaded, failures,
            compileComparisons, knownFailures, dispatched, deferred,
            perBackend, 0, failures.isEmpty());
    }

    /** One closed known-fail probe result (pre-flip forced promotion). */
    public sealed interface KnownFailProbe {

        /** The underlying mode now passes: the marker is stale (gate failure). */
        record Stale(String detail) implements KnownFailProbe { }

        /** The underlying mode still fails: tracked non-fatal. */
        record Tracked(String detail) implements KnownFailProbe { }
    }

    /**
     * Probes the underlying mode of a known-fail fixture. Compile modes
     * run the real backend-neutral frontend. Runtime modes need a
     * registered lane implementation: with none registered in this build
     * child the probe records the fixture tracked non-fatal with an
     * explicit unprobeable note (the flip lands after the lanes, when the
     * runtime probe becomes executable).
     */
    public static Optional<KnownFailProbe> probeKnownFailMode(
            CorpusDiscovery.Fixture fixture, String mode, Map<String, Lane> lanes) {
        if (mode.equals("compile-ok")) {
            List<CompilerDiagnostic> errors = FrontendCompiler.errorDiagnostics(
                fixture.source(), fixture.corpusPath());
            if (errors.isEmpty()) {
                return Optional.of(new KnownFailProbe.Stale(
                    "the fixture compiles clean"));
            }
            return Optional.of(new KnownFailProbe.Tracked(
                "unexpected compile errors: " + errorCodes(errors)));
        }
        if (mode.startsWith("compile-error ")) {
            String code = mode.substring("compile-error ".length()).trim();
            List<CompilerDiagnostic> errors = FrontendCompiler.errorDiagnostics(
                fixture.source(), fixture.corpusPath());
            boolean found = "any".equals(code)
                ? !errors.isEmpty()
                : errors.stream().anyMatch(d -> code.equals(d.code()));
            if (found) {
                return Optional.of(new KnownFailProbe.Stale(
                    "the fixture now produces " + code));
            }
            return Optional.of(new KnownFailProbe.Tracked(
                "does not produce " + code + " (got: " + errorCodes(errors)
                    + ")"));
        }
        // Runtime modes: probed through the lane dispatch verdict when the
        // lanes are registered; with none registered in this build child
        // the fixture is recorded tracked non-fatal with an explicit
        // unprobeable note (the lanes land in T7-T9 before the flip).
        if (lanes.isEmpty()) {
            return Optional.of(new KnownFailProbe.Tracked(mode
                + " unprobeable in this build child (no lane implementation "
                + "is registered — the lanes land in T7-T9 before the flip)"));
        }
        return Optional.empty();
    }

    private static List<String> errorCodes(List<CompilerDiagnostic> diagnostics) {
        List<String> codes = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            codes.add(diagnostic.code());
        }
        return codes;
    }

    private static CorpusDiscovery.Fixture fixtureByPath(
            List<CorpusDiscovery.Fixture> fixtures, String corpusPath) {
        for (CorpusDiscovery.Fixture fixture : fixtures) {
            if (fixture.corpusPath().equals(corpusPath)) {
                return fixture;
            }
        }
        return null;
    }

    private static Path sidecarSibling(Path sidecar) {
        String name = sidecar.getFileName().toString();
        String dealName = name.substring(0,
            name.length() - ".expect.json".length()) + ".deal";
        return sidecar.resolveSibling(dealName);
    }

    private static void printSummary(PrintStream out,
            CorpusDiscovery.DiscoveryResult discovery,
            List<GateFailure> failures, int compileComparisons,
            int knownFailures, int dispatched, int deferred,
            Map<String, int[]> perBackend) {
        out.println("=== Differential Gate Summary ===");
        out.println("Fixtures: " + discovery.fixtures().size());
        out.println("Classification failures: "
            + failures.stream().filter(f -> "classification".equals(f.kind()))
                .count());
        out.println("Compile diagnostics compared: " + compileComparisons
            + " (failures: "
            + failures.stream().filter(
                f -> "compile-diagnostic".equals(f.kind())).count() + ")");
        out.println("Runtime cases: " + dispatched + " dispatched, " + deferred
            + " deferred");
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            int[] counts = perBackend.get(backend);
            out.println("  " + backend + ": " + counts[0] + " passed, "
                + counts[1] + " failed");
        }
        out.println("Skipped: 0");
        out.println("KnownFailures: " + knownFailures);
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
     * classification, sidecar validation, compile-diagnostic comparison,
     * and the known-fail probes over the real on-disk corpus; the runtime
     * dispatch phase defers until the lane children register the lanes.
     * Not wired into {@code run_tests.sh} until the flip (G5's
     * temporary-coexistence window).
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
