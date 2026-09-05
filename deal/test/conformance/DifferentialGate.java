package deal.test.conformance;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.SemanticProfile;
import deal.test.LegacyProfileRegressionCatalog;

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
 * sets, the backend-neutral frontend corpus execution (ISSUE-0357 —
 * every {@code compile-ok} fixture compiles clean and every
 * {@code compile-error} fixture without a pin rejects with its exact
 * pinned code through the real frontend with the per-case A5 profile
 * and the corpus module resolver), the Compile Diagnostic comparison
 * over the real backend-neutral frontend, the pre-flip known-fail
 * forced-promotion probes, the lane dispatch seam (G7 worker pool +
 * harness-owned per-lane deadlines), and the verdict/summary with the
 * closed G6 mismatch classes. The {@link GateRun} carries the per-case
 * {@link GateDispatcher.CaseVerdict} list so the lane integration suite
 * (ISSUE-0357) asserts the exact pre-flip failure enumeration.
 *
 * <p>The lane children register the LuaJIT/JVM/JS lanes (ISSUE-0354/
 * 0355/0356). Pre-flip, the JVM lane additionally registers its
 * capability skip registry ({@code JvmLane.preFlipSkipRegistry()},
 * absorbed from {@code JvmConformanceTest.SKIPS}) through the
 * {@link #run(Path, Map, int, Duration, PrintStream, Map)} overload: a
 * registry-tracked fixture whose lane outcome still fails is reported
 * tracked non-fatal with its gap id (G8 — the tracked non-fatal paths
 * remain before the flip), a registry-tracked fixture that starts
 * passing is a stale skip-registry entry failing the gate with a
 * promotion instruction, and a registry entry naming a fixture that is
 * missing from the corpus (or carries no runtime classification) fails
 * the gate the same way (G2's forced-promotion rules stay active until
 * the flip deletes the registry with its mechanisms).</p>
 *
 * <p>The legacy runners keep executing the runtime corpus during the G5
 * temporary-coexistence window; {@code run_tests.sh} wires this gate's
 * own entry point only at the flip (T14).</p>
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

    /**
     * One pre-flip skip-registry entry (G8 tolerance): the corpus path,
     * the gap id, and the documented reason. The registry is the JVM
     * lane's capability-skip baseline absorbed from
     * {@code JvmConformanceTest.SKIPS}; the flip (T14) deletes the
     * registry and this surface with it.
     */
    public record PreFlipSkipEntry(String corpusPath, String gapId,
                                   String reason) {

        public PreFlipSkipEntry {
            Objects.requireNonNull(corpusPath, "corpusPath must not be null");
            Objects.requireNonNull(gapId, "gapId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * A pre-flip skip-registry view a lane registers with the gate: the
     * gate applies the G8 tolerance to the registry's backend (a tracked
     * failing outcome is non-fatal; a passing outcome or a missing
     * fixture is a stale entry with a promotion instruction).
     */
    public interface PreFlipSkipRegistry {

        /** The entry of {@code corpusPath}, or empty when not registered. */
        Optional<PreFlipSkipEntry> entryFor(String corpusPath);

        /** Every registry entry, in registry order. */
        List<PreFlipSkipEntry> entries();
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
        int knownFailuresTracked,
        int runtimeCasesDispatched,
        int runtimeCasesDeferred,
        List<GateDispatcher.CaseVerdict> verdicts,
        Map<String, int[]> perBackend,
        int skipped,
        int skipRegistryTracked,
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
     * for registered lanes. No skip registry is registered (the
     * pre-flip JVM skip tolerance needs the
     * {@link #run(Path, Map, int, Duration, PrintStream, Map)} overload).
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
        return run(conformanceRoot, lanes, parallelism, laneDeadline, out,
            Map.of());
    }

    /**
     * Runs the gate over one conformance root with the registered
     * pre-flip skip registries (keyed by backend name) and returns the
     * complete run result. The skip-registry tolerance (G8 pre-flip):
     * a registry entry naming a fixture missing from the corpus or
     * without a runtime classification is a stale entry failing the gate
     * with a promotion instruction; a registry-tracked fixture whose
     * registry backend outcome passes is a stale entry failing the gate
     * the same way; a registry-tracked fixture whose registry backend
     * outcome fails with a DEAL-outcome or artifact/process class is
     * reported tracked non-fatal with its gap id (never a gate failure);
     * infrastructure outcomes of {@code HARNESS_DEFECT},
     * {@code TOOL_MISSING}, and {@code LANE_TIMEOUT} are never tracked —
     * they stay gate-fatal (G6: infrastructure outcomes never satisfy a
     * case).
     */
    public static GateRun run(Path conformanceRoot, Map<String, Lane> lanes,
            int parallelism, Duration laneDeadline, PrintStream out,
            Map<String, PreFlipSkipRegistry> skipRegistries)
            throws IOException {
        Objects.requireNonNull(conformanceRoot, "conformanceRoot must not be null");
        Objects.requireNonNull(lanes, "lanes must not be null");
        Objects.requireNonNull(laneDeadline, "laneDeadline must not be null");
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(skipRegistries,
            "skipRegistries must not be null");
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
        // Phase 1b: pre-flip skip-registry validation (G2/G8): every entry
        // must name an on-disk runtime-classified fixture; a stale entry
        // fails the gate with a promotion instruction (the forced-promotion
        // rules stay active until the flip deletes the registry).
        // ---------------------------------------------------------------------
        for (Map.Entry<String, PreFlipSkipRegistry> registration
                : skipRegistries.entrySet()) {
            for (PreFlipSkipEntry entry : registration.getValue().entries()) {
                CorpusDiscovery.Fixture fixture =
                    corpusByPath.get(entry.corpusPath());
                if (fixture == null || !fixture.runtimeClassified()) {
                    failures.add(new GateFailure("stale skip-registry",
                        registration.getKey() + " " + entry.corpusPath(),
                        "the skip-registry entry names a fixture that is "
                            + (fixture == null
                                ? "missing from the corpus"
                                : "not runtime-classified")
                            + " — promotion instruction: remove the "
                            + "skip-registry entry (gap id " + entry.gapId()
                            + ")"));
                    out.println("  STALE SKIP-REGISTRY: " + entry.corpusPath()
                        + " — remove the skip-registry entry (gap id "
                        + entry.gapId() + ")");
                }
            }
        }
        if (!skipRegistries.isEmpty()) {
            out.println();
        }

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
                    || classification.kind() == CorpusDiscovery.Kind.KNOWN_FAIL
                    || classification.kind()
                        == CorpusDiscovery.Kind.COMPANION) {
                continue; // classification failure / tracked / support module
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
            SemanticProfile profile = LegacyProfileRegressionCatalog
                .profileFor(fixture.corpusPath());
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
        int skipRegistryTracked = 0;
        List<GateDispatcher.CaseVerdict> verdicts = List.of();
        if (lanes.isEmpty()) {
            // No lane implementation registered: the dispatch phase is
            // deferred explicitly — never silently — and the legacy runners
            // keep executing the runtime corpus during the G5
            // temporary-coexistence window.
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
            verdicts = GateDispatcher.run(cases, lanes, parallelism,
                laneDeadline);
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

                // Pre-flip skip-registry tolerance (G2/G8): a
                // registry-tracked fixture's registry-backend outcome is
                // tracked non-fatal when it still fails with a
                // DEAL-outcome/artifact/process class; a passing outcome
                // or a gate-fatal infrastructure outcome is never tracked.
                GateDispatcher.LaneOutcome registryOutcome = null;
                PreFlipSkipEntry registryEntry = null;
                for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                    PreFlipSkipRegistry registry =
                        skipRegistries.get(outcome.backend());
                    if (registry == null) {
                        continue;
                    }
                    Optional<PreFlipSkipEntry> entry =
                        registry.entryFor(verdict.fixturePath());
                    if (entry.isPresent()) {
                        registryOutcome = outcome;
                        registryEntry = entry.get();
                        break;
                    }
                }
                String toleratedBackend = null;
                if (registryEntry != null && registryOutcome.passed()) {
                    failures.add(new GateFailure("stale skip-registry",
                        verdict.fixturePath(),
                        "the fixture now passes on the "
                            + registryOutcome.backend() + " lane — promotion "
                            + "instruction: remove the skip-registry entry "
                            + "(gap id " + registryEntry.gapId() + ")"));
                    out.println("  STALE SKIP: " + verdict.fixturePath()
                        + " — remove the skip-registry entry (gap id "
                        + registryEntry.gapId() + ")");
                } else if (registryEntry != null
                        && registryOutcome.mismatch().isPresent()
                        && tolerablePreFlipSkip(
                            registryOutcome.mismatch().get())) {
                    skipRegistryTracked++;
                    toleratedBackend = registryOutcome.backend();
                    out.println("  SKIP-REGISTRY (tracked non-fatal, "
                        + registryEntry.gapId() + "): "
                        + verdict.fixturePath() + " — "
                        + registryOutcome.mismatch().get().detail());
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
                        if (toleratedBackend != null
                                && toleratedBackend.equals(
                                    outcome.backend())) {
                            continue; // tracked non-fatal, never re-recorded
                        }
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
        printSummary(out, discovery, failures, frontendCompiled,
            compileComparisons, knownFailures, dispatched, deferred,
            skipRegistryTracked, perBackend);
        return new GateRun(root, discovery.fixtures(), loaded, failures,
            frontendCompiled, compileComparisons, knownFailures, dispatched,
            deferred, verdicts, perBackend, 0, skipRegistryTracked,
            failures.isEmpty());
    }

    /**
     * The pre-flip skip-registry tolerance classes (G6/G8): a
     * registry-tracked fixture whose outcome is a DEAL-outcome mismatch
     * or an artifact/process infrastructure outcome is tracked non-fatal
     * (the fixture's underlying mode still fails); a harness defect, a
     * missing tool, or a deadline kill is never tracked — infrastructure
     * outcomes never satisfy a case and stay gate-fatal.
     */
    private static boolean tolerablePreFlipSkip(GateMismatch mismatch) {
        return switch (mismatch.clazz()) {
            case TRANSCRIPT_MISMATCH, ERROR_SNAPSHOT_MISMATCH,
                 COMPILE_DIAGNOSTIC_MISMATCH, EXIT_CODE_MISMATCH,
                 COMPILE_REJECT_MISMATCH, ARTIFACT_MISSING,
                 PROCESS_FAILURE -> true;
            case TOOL_MISSING, LANE_TIMEOUT, HARNESS_DEFECT -> false;
        };
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
            List<GateFailure> failures, int frontendCompiled,
            int compileComparisons, int knownFailures, int dispatched,
            int deferred, int skipRegistryTracked, Map<String, int[]> perBackend) {
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
        out.println("Runtime cases: " + dispatched + " dispatched, " + deferred
            + " deferred");
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            int[] counts = perBackend.get(backend);
            out.println("  " + backend + ": " + counts[0] + " passed, "
                + counts[1] + " failed");
        }
        out.println("Skipped: 0");
        out.println("KnownFailures: " + knownFailures);
        out.println("Skip registry tracked (pre-flip): " + skipRegistryTracked
            + " non-fatal (stale entries: "
            + failures.stream().filter(
                f -> "stale skip-registry".equals(f.kind())).count() + ")");
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
     * compile-diagnostic comparison, and the known-fail probes over the
     * real on-disk corpus; the runtime dispatch phase defers (no lane
     * registration in this entry point — the three-lane corpus execution
     * lives in the ISSUE-0357 lane integration suite until the flip).
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
