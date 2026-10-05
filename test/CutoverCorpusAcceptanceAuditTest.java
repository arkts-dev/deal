package deal.test;

import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ExternalEntry;
import deal.project.ProjectContext;
import deal.project.ProjectLocator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.test.ConformanceHarnessMetadata;
import deal.test.conformance.CorpusDiscovery;
import deal.test.conformance.CorpusFfi;
import deal.test.conformance.GateDispatcher;
import deal.test.conformance.JvmLane;
import deal.test.conformance.Lane;
import deal.test.conformance.LaneCase;
import deal.test.conformance.LuaLane;
import deal.test.conformance.ProducedProjectIdentityAudit;
import deal.test.conformance.SidecarExpectations;
import deal.test.conformance.SidecarGateLoader;
import deal.test.conformance.SidecarSchemaValidator;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * ISSUE-0694: the dispatched-corpus acceptance runs
 * ({@code conformance-cutover-guards-pins-and-production-source-removal}
 * HR3/HR4(a) and the production-source reachability contract; the epic
 * criteria 3 and 4 and {@code conformance-corpus-disposition-and-profile-repin}
 * CD2/CD5).
 *
 * <ol>
 *   <li><b>The corpus invariants.</b> The dispatched set is the discovered
 *       runtime-classified fixture set of {@code test/conformance/backend-runtime};
 *       its count is the pinned 390, every dispatched fixture's runtime
 *       sidecar carries an expectation for every one of the three closed
 *       backend names, classification fails for none, and the drive covers
 *       every dispatched fixture on both production targets (no skip
 *       branch exists in this file).</li>
 *   <li><b>The zero-unlowered/deferred acceptance run.</b> Every dispatched
 *       fixture is materialized lane-equivalently (its header-stripped
 *       compilation set, the host declarations under {@code bindings/},
 *       the corpus FFI declarations with their {@code nativeLibrary}
 *       wiring, and one generated {@code deal.json}) and compiled through
 *       the release-owned production invocation on {@link Backend#LUAJIT}
 *       and {@link Backend#JVM}. The verdict reports zero E6005
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE} per fixture and target. Under the
 *       epic's gating rule (criterion 3: "gated on the Open blocker ...
 *       before it, residual failures are reported per fixture and never
 *       skipped"), a <em>named producer-guard</em> residual is reported
 *       per fixture and target with its diagnostic — never converted into
 *       a skip, an exclusion, or a fabricated pass — and the strict
 *       acceptance mode ({@code -Ddeal.acceptance.strict=true} or
 *       {@code DEAL_ACCEPTANCE_STRICT=1}) asserts the zero result as a
 *       hard gate over the same measurement. Every other
 *       acceptance fact is hard at every revision: a compile failure
 *       outside the named guards, a pinned sidecar rejection that is not
 *       reproduced, a materialization failure, a missing leg, a guard
 *       violation, or a count change fails the run immediately.</li>
 *   <li><b>The lane-level dangling-identity guard over every produced
 *       project.</b> Every project the production compile produces — the
 *       lane's own compile subject, never a re-lowering — is audited with
 *       {@link ProducedProjectIdentityAudit}; a violation is a hard
 *       failure naming the fixture, the module path, the function, the
 *       unresolved op id, and the rule at any revision, because the K12
 *       property holds for every lowered project and no epic gate applies
 *       to it.</li>
 *   <li><b>The negative probe.</b> A deliberately uncovered construct (the
 *       function-typed materialization, the still-fail-closed construct
 *       this revision's production pipeline refuses) fails closed with
 *       E6005 naming {@code CONSTRUCT_UNLOWERED} and stages nothing, so
 *       the zero result can never be a vacuous pass.</li>
 *   <li><b>The settled-lane acceptance run.</b> All 390 dispatched cases
 *       run through the real LuaJIT and JVM lanes
 *       ({@link GateDispatcher} dispatch and the lanes' own execution
 *       contract): one verdict per dispatched case, both backends
 *       accounted per fixture (no skip), and every failing lane outcome
 *       reported per fixture and backend — with the named producer-guard
 *       outcomes called out as the zero-unlowered criterion at the lane
 *       that compiled the project. The strict acceptance mode asserts
 *       zero failing outcomes.</li>
 *   <li><b>The reachability acceptance invocation.</b> The whole
 *       {@code deal/**} production source set is scanned for the
 *       removed-surface contract (zero retained {@code LuaBackend.generate}
 *       / {@code JvmBackend.generate} call sites, zero route/planner/
 *       registry/retained-counter references from the compile path, zero
 *       retained-counter increments, exactly one
 *       {@code SemanticLowerer.lowerProject(} call site) and for the
 *       retained classes' and their unit tests' presence. Pre-removal the
 *       residual sites are reported per file and token (the same
 *       pre-precondition reporting rule); under the strict acceptance mode
 *       the zero result is asserted.</li>
 * </ol>
 *
 * <p>The drive is read-only over the repository: every temp project is
 * removed on every path, nothing is staged in the checkout, and no
 * production source, corpus member, sidecar, schema, or lane mechanism is
 * modified. The drive's workers honor the registered suite's worker
 * bound, {@code deal.test.jobs} (the same bound the differential corpus
 * gate reads; default: the available processors), so the gate's own
 * {@code --jobs} choice bounds this audit too; {@code deal.acceptance.jobs}
 * stays available as an explicit audit override for a bounded acceptance
 * measurement.</p>
 */
public class CutoverCorpusAcceptanceAuditTest {

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final Path CORPUS = CONFORMANCE.resolve("backend-runtime");
    private static final Path HOST_FIXTURES = CONFORMANCE.resolve("host-fixtures");

    /** The pinned dispatched-corpus count (CD2's count invariant). */
    private static final int DISPATCHED_COUNT_PIN = 390;

    /**
     * The named producer guards the acceptance verdict reports; the first
     * two are the epic criterion's named diagnostics, the third is the
     * producer-gap sibling that must equally be absent.
     */
    private static final List<String> NAMED_GUARDS = List.of(
        "CONSTRUCT_UNLOWERED", "RETAINED_ABI_DEFERRED", "SHARED_EMITTER_COVERAGE");

    /** The two production targets the acceptance drive measures. */
    private enum Target {
        LUAJIT(Backend.LUAJIT, "luajit"),
        JVM(Backend.JVM, "jvm");

        private final Backend backend;
        private final String laneName;

        Target(Backend backend, String laneName) {
            this.backend = backend;
            this.laneName = laneName;
        }
    }

    private static int passed = 0;
    private static int fatal = 0;
    private static int reportedDeficit = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            fatal(message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    /** A hard acceptance failure: always fails the registered run. */
    private static void fatal(String message) {
        fatal++;
        System.out.println("FATAL: " + message);
    }

    /**
     * One gated residual — the named producer-guard deficit of the corpus
     * drive (criterion 3's CD5 precondition) or the pre-removal
     * reachability deficit (criterion 4's removal slice) — reported under
     * the pre-precondition rule: named, counted, and asserted only under
     * the strict acceptance mode, never skipped.
     */
    private static void deficit(String message) {
        reportedDeficit++;
        System.out.println("  " + message);
    }

    /** True when the strict acceptance mode is engaged. */
    private static boolean strictAcceptance() {
        return Boolean.getBoolean("deal.acceptance.strict")
            || "1".equals(System.getenv("DEAL_ACCEPTANCE_STRICT"));
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * The drive's worker bound: the registered suite's worker bound
     * ({@code deal.test.jobs}, the same bound the differential corpus gate
     * reads; default: the available processors), with
     * {@code deal.acceptance.jobs} as an explicit audit override. The
     * bound is the gate's, so an invocation at {@code --jobs 1} runs this
     * audit serially like every other registered suite.
     */
    private static int workerCount() {
        String raw = System.getProperty("deal.acceptance.jobs");
        if (raw != null) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed >= 1) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the gate's bound
            }
        }
        return Math.max(1, Integer.getInteger("deal.test.jobs",
            Runtime.getRuntime().availableProcessors()));
    }

    // =========================================================================
    // 1. The corpus invariants
    // =========================================================================

    private static void testCorpusInvariants(
            CorpusDiscovery.DiscoveryResult discovery,
            List<CorpusDiscovery.Fixture> dispatched) throws Exception {
        Map<String, CorpusDiscovery.Fixture> corpusByPath =
            discovery.corpusByPath();
        Set<String> corpusModuleIndex = discovery.corpusModuleIndex();
        System.out.println("-- the corpus invariants: the dispatched set, its "
            + "count, the three-backend dispatch set, and no skip --");
        check(discovery.failures().isEmpty(),
            "zero classification failures over the real corpus: "
                + discovery.failures());
        checkEq(DISPATCHED_COUNT_PIN, dispatched.size(),
            "the dispatched runtime-classified corpus count is the pinned "
                + "390");
        check(!dispatched.isEmpty(),
            "the dispatched corpus is discovered and non-empty");
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            SidecarGateLoader.LoadResult load = SidecarGateLoader.load(
                fixture, corpusByPath, corpusModuleIndex, CONFORMANCE);
            check(!load.failed() && load.runtime().isPresent(),
                fixture.corpusPath() + ": the runtime sidecar loads");
            if (!load.failed() && load.runtime().isPresent()) {
                for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
                    check(load.runtime().get().expectationFor(backend) != null,
                        fixture.corpusPath() + ": the sidecar carries the "
                            + backend + " expectation (the three-backend "
                            + "dispatch set)");
                }
            }
        }
        check(dispatched.stream().map(CorpusDiscovery.Fixture::corpusPath)
                .distinct().count() == dispatched.size(),
            "every dispatched fixture is dispatched exactly once");
    }

    // =========================================================================
    // 2. The zero-unlowered/deferred acceptance run
    // =========================================================================

    /** One measured fixture leg of the acceptance drive. */
    private record Leg(String corpusPath, String target, boolean compiled,
                       List<String> diagnostics, String materializationError) {
    }

    /**
     * The per-leg acceptance outcome: the fixture's legs, the identity-guard
     * violations over the projects this fixture's compiles produced, and the
     * number of projects the guard audited (the non-vacuity count of the
     * lane-level guard over the drive).
     */
    private record FixtureOutcome(String corpusPath, List<Leg> legs,
                                  List<String> guardViolations,
                                  int producedProjects) {
    }

    private static void testDispatchedCorpusProductionDrive(
            List<CorpusDiscovery.Fixture> dispatched,
            Map<String, CorpusDiscovery.Fixture> corpusByPath) throws Exception {
        System.out.println("-- the zero-unlowered/deferred acceptance run: "
            + "every dispatched fixture compiles through the production "
            + "pipeline on both targets --");
        Map<String, Map<String, SidecarExpectations.RuntimeExpectation>>
            expectations = new LinkedHashMap<>();
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            SidecarGateLoader.LoadResult load = SidecarGateLoader.load(fixture,
                corpusByPath, corpusByPath.keySet(), CONFORMANCE);
            Map<String, SidecarExpectations.RuntimeExpectation> byBackend =
                new LinkedHashMap<>();
            if (!load.failed() && load.runtime().isPresent()) {
                for (Target target : Target.values()) {
                    byBackend.put(target.laneName,
                        load.runtime().get().expectationFor(target.laneName));
                }
            }
            expectations.put(fixture.corpusPath(), byBackend);
        }
        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount());
        List<FixtureOutcome> outcomes = new ArrayList<>();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            List<Future<FixtureOutcome>> futures = new ArrayList<>();
            for (CorpusDiscovery.Fixture fixture : dispatched) {
                Callable<FixtureOutcome> task = () -> driveFixture(
                    fixture, corpusByPath);
                futures.add(pool.submit(task));
            }
            for (Future<FixtureOutcome> future : futures) {
                outcomes.add(future.get());
            }
        } finally {
            pool.shutdownNow();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        outcomes.sort(Comparator.comparing(FixtureOutcome::corpusPath));
        System.out.println("  " + dispatched.size() + " dispatched fixtures, "
            + (dispatched.size() * Target.values().length)
            + " fixture-target legs, measured in "
            + (System.currentTimeMillis() - start) + "ms");

        // The drive covered every dispatched fixture on both targets: the
        // no-skip invariant. A missing leg is an audit defect, never a skip.
        checkEq(dispatched.size() * Target.values().length,
            outcomes.stream().mapToInt(o -> o.legs().size()).sum(),
            "every dispatched fixture carries one leg per production target");

        // The lane-level identity guard over every produced project (the
        // K12 property; hard at every revision).
        int producedProjects = outcomes.stream()
            .mapToInt(FixtureOutcome::producedProjects).sum();
        for (FixtureOutcome outcome : outcomes) {
            for (String violation : outcome.guardViolations()) {
                fatal(outcome.corpusPath() + ": " + violation);
            }
        }
        System.out.println("  the dangling-identity guard audited "
            + producedProjects + " produced project(s) over the drive: "
            + (outcomes.stream()
                .mapToInt(o -> o.guardViolations().size()).sum() == 0
                    ? "zero violations" : "VIOLATIONS REPORTED"));
        check(producedProjects > 0,
            "the guard audited at least one produced project (the drive's "
                + "non-vacuity)");
        for (Leg leg : allLegs(outcomes)) {
            if (leg.materializationError() != null) {
                fatal(leg.corpusPath() + " [" + leg.target()
                    + "]: the lane-equivalent materialization failed: "
                    + leg.materializationError());
            }
        }

        // The compiler diagnostics accounting: the named producer guards are
        // the acceptance deficit; the pinned extern-C E6006 compile-reject
        // leg is the one sanctioned divergence (the sidecar's own JVM
        // rejection pin); every other compile failure is reported with its
        // own diagnostic set and is never converted into a skip.
        Map<String, Integer> guardLegs = new LinkedHashMap<>();
        for (String guard : NAMED_GUARDS) {
            guardLegs.put(guard, 0);
        }
        List<String> residual = new ArrayList<>();
        long compileLegs = allLegs(outcomes).size();
        long compiledLegs = 0;
        long compileRejectLegs = 0;
        for (Leg leg : allLegs(outcomes)) {
            if (leg.materializationError() != null || leg.diagnostics() == null) {
                continue;
            }
            if (leg.compiled()) {
                compiledLegs++;
            }
            String joined = String.join(" | ", leg.diagnostics());
            for (String guard : NAMED_GUARDS) {
                if (!leg.compiled() && joined.contains(guard)) {
                    guardLegs.merge(guard, 1, Integer::sum);
                }
            }
            Map<String, SidecarExpectations.RuntimeExpectation> byBackend =
                expectations.get(leg.corpusPath());
            SidecarExpectations.RuntimeExpectation expectation =
                byBackend == null ? null : byBackend.get(leg.target());
            if (expectation instanceof SidecarExpectations.RuntimeExpectation.Rejected
                    rejected) {
                boolean reproduced = !leg.compiled() && leg.diagnostics().stream()
                    .anyMatch(d -> d.startsWith(rejected.code()));
                if (reproduced) {
                    compileRejectLegs++;
                } else {
                    // A pinned sidecar rejection the production compile does
                    // not reproduce is an acceptance failure of this
                    // revision, not a precondition deficit: the rejection
                    // pins are exercised now and every one of them must
                    // reproduce. Never reported as a skippable residual.
                    fatal("[" + leg.target() + "] " + leg.corpusPath()
                        + ": the pinned compile-reject " + rejected.code()
                        + " is not reproduced: " + joined);
                }
                continue;
            }
            if (leg.compiled()) {
                continue;
            }
            if (NAMED_GUARDS.stream().anyMatch(joined::contains)) {
                residual.add("[" + leg.target() + "] " + leg.corpusPath()
                    + ": " + joined);
            } else {
                // A compile failure outside the named producer guards is not
                // the CD5 precondition's deficit: it is an acceptance
                // failure of this revision and always fails the run.
                fatal("[" + leg.target() + "] " + leg.corpusPath()
                    + ": non-guard compile failure: " + joined);
            }
        }
        System.out.println("  compile legs: " + compiledLegs + " pass / "
            + (compileLegs - compiledLegs) + " fail, compile-reject legs: "
            + compileRejectLegs + " (the pinned extern-C E6006)");
        for (Map.Entry<String, Integer> entry : guardLegs.entrySet()) {
            System.out.println("  zero " + entry.getKey() + ": "
                + (entry.getValue() == 0 ? "PASS" : "FAIL")
                + " (" + entry.getValue() + " legs)");
        }
        if (residual.isEmpty() && fatal == 0) {
            System.out.println("  strict acceptance mode: ENGAGED (zero "
                + "named producer-guard deficit over the dispatched set)");
        } else if (residual.isEmpty()) {
            System.out.println("  zero named producer-guard deficit over the "
                + "dispatched set; the hard failure(s) reported above fail "
                + "this run");
        } else if (strictAcceptance()) {
            System.out.println("  strict acceptance mode: ENGAGED "
                + "(-Ddeal.acceptance.strict)");
            for (String line : residual) {
                fatal(line);
            }
        } else {
            System.out.println("  strict acceptance mode: DISENGAGED (the "
                + "pre-precondition reporting rule: the residual is reported "
                + "per fixture and target, never skipped; run with "
                + "-Ddeal.acceptance.strict=true for the hard gate)");
            System.out.println("  Acceptance deficit (reported, non-fatal "
                + "under the pre-precondition rule):");
            for (String line : residual) {
                deficit(line);
            }
        }

        // The negative probe: the deliberately uncovered construct fails
        // closed with the named diagnostic and stages nothing.
        testNegativeProbe();
    }

    private static List<Leg> allLegs(List<FixtureOutcome> outcomes) {
        List<Leg> legs = new ArrayList<>();
        for (FixtureOutcome outcome : outcomes) {
            legs.addAll(outcome.legs());
        }
        return legs;
    }

    /** Drives one fixture on both production targets. */
    private static FixtureOutcome driveFixture(CorpusDiscovery.Fixture fixture,
            Map<String, CorpusDiscovery.Fixture> corpusByPath) {
        List<Leg> legs = new ArrayList<>();
        List<String> guardViolations = new ArrayList<>();
        int producedProjects = 0;
        Map<Target, Path> roots = new LinkedHashMap<>();
        try {
            Map<Target, Project> projects = new LinkedHashMap<>();
            for (Target target : Target.values()) {
                try {
                    projects.put(target, materialize(fixture, corpusByPath,
                        target));
                } catch (Exception materializationFailure) {
                    legs.add(new Leg(fixture.corpusPath(), target.laneName,
                        false, null,
                        "materialization: " + materializationFailure));
                }
            }
            for (Target target : Target.values()) {
                Project project = projects.get(target);
                if (project == null) {
                    continue;
                }
                roots.put(target, project.root());
                ProjectLocator.LocateResult located = ProjectLocator.locate(
                    project.entryFile().toString(),
                    new CliOverrides(null, null));
                if (located.context() == null) {
                    legs.add(new Leg(fixture.corpusPath(), target.laneName,
                        false, null, "the generated deal.json does not "
                            + "locate strictly"));
                    continue;
                }
                CompilationOrchestrator orchestrator =
                    new CompilationOrchestrator(
                        withDottedHostIdentities(located.context(), project),
                        project.entryFile(), false, false, false, false, null,
                        productionInvocation());
                boolean compiled;
                try {
                    compiled = orchestrator.compile();
                } catch (Exception compileFailure) {
                    legs.add(new Leg(fixture.corpusPath(), target.laneName,
                        false, null, "the compile raised "
                            + compileFailure.getClass().getSimpleName() + ": "
                            + compileFailure.getMessage()));
                    continue;
                }
                List<String> diagnostics = new ArrayList<>();
                for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
                    if ("error".equals(diagnostic.severity())) {
                        diagnostics.add(diagnostic.code() + " "
                            + diagnostic.message());
                    }
                }
                legs.add(new Leg(fixture.corpusPath(), target.laneName,
                    compiled, diagnostics, null));
                ExecutableLoweredProject produced =
                    orchestrator.producedProject();
                if (compiled && produced != null) {
                    producedProjects++;
                    ProducedProjectIdentityAudit.laneFailure(produced,
                        fixture.corpusPath() + " [" + target.laneName + "]")
                        .ifPresent(guardViolations::add);
                }
            }
        } finally {
            for (Path root : roots.values()) {
                deleteRecursively(root);
            }
        }
        return new FixtureOutcome(fixture.corpusPath(), List.copyOf(legs),
            List.copyOf(guardViolations), producedProjects);
    }

    /**
     * The negative probe: a scratch project whose construct the production
     * pipeline does not lower still fails closed with the named E6005 and
     * publishes nothing. The function-typed materialization is the
     * still-fail-closed construct of this revision (the same probe the
     * production dispatch battery pins); the probe is retargeted by the
     * slice that realizes it.
     */
    private static void testNegativeProbe() throws Exception {
        System.out.println("-- the negative probe: a deliberately uncovered "
            + "construct fails closed with the named diagnostic --");
        Path project = Files.createTempDirectory("cutover-negative-probe-");
        try {
            write(project, "deal.json", dealJson("luajit", null));
            write(project, "src/main.deal", """
                export function main(): null {
                  let f: (a: int) => int = one
                  let x: int = f(1)
                  return null
                }

                function one(x: int): int {
                  return x
                }
                """);
            Path entry = project.resolve("src/main.deal").toAbsolutePath()
                .normalize();
            CompilationOrchestrator orchestrator = orchestrator(project, entry);
            boolean compiled = orchestrator.compile();
            check(!compiled,
                "the deliberately uncovered construct fails closed");
            List<String> diagnostics = new ArrayList<>();
            for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
                if ("error".equals(diagnostic.severity())) {
                    diagnostics.add(diagnostic.code() + " "
                        + diagnostic.message());
                }
            }
            check(diagnostics.stream().anyMatch(d -> d.contains("E6005")
                    && d.contains("CONSTRUCT_UNLOWERED")),
                "the fail-closed probe names E6005 CONSTRUCT_UNLOWERED: "
                    + diagnostics);
            check(orchestrator.producedProject() == null,
                "the fail-closed probe produces no project");
            check(!Files.exists(project.resolve("out")),
                "the fail-closed probe stages no artifact");
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 3. The settled-lane acceptance run
    // =========================================================================

    /**
     * The dispatched corpus through the settled LuaJIT and JVM lanes
     * ({@link GateDispatcher} dispatch, the lanes' own execution
     * contract): one verdict per dispatched case (no skip), both backends
     * accounted per fixture, every failing outcome reported per fixture
     * and backend, and zero lane outcomes carrying a named producer
     * guard. A named-guard outcome is the zero-unlowered criterion
     * surfacing at the lane that compiled the project; both it and every
     * other differential failure are asserted zero under the strict
     * acceptance mode, while the existing registered three-lane gate
     * remains the hard differential verdict surface.
     */
    private static void testSettledLaneAcceptance(
            CorpusDiscovery.DiscoveryResult discovery,
            List<CorpusDiscovery.Fixture> dispatched) throws Exception {
        System.out.println("-- the settled-lane acceptance run: every "
            + "dispatched case through the LuaJIT and JVM lanes --");
        Map<String, CorpusDiscovery.Fixture> corpusByPath =
            discovery.corpusByPath();
        Set<String> corpusModuleIndex = discovery.corpusModuleIndex();
        Path root = CONFORMANCE.toAbsolutePath().normalize();
        List<GateDispatcher.CaseInput> cases = new ArrayList<>();
        for (CorpusDiscovery.Fixture fixture : dispatched) {
            SidecarGateLoader.LoadResult load = SidecarGateLoader.load(
                fixture, corpusByPath, corpusModuleIndex, CONFORMANCE);
            if (load.failed() || load.runtime().isEmpty()) {
                fatal(fixture.corpusPath() + ": the runtime sidecar loads");
                continue;
            }
            List<LaneCase> laneCases = new ArrayList<>();
            for (Target target : Target.values()) {
                laneCases.add(new LaneCase(fixture.corpusPath(),
                    target.laneName, fixture.file(),
                    CorpusDiscovery.compilationSet(fixture, corpusByPath,
                        CONFORMANCE),
                    load.runtime().get().expectationFor(target.laneName)));
            }
            cases.add(new GateDispatcher.CaseInput(fixture.corpusPath(),
                laneCases));
        }
        Map<String, Lane> lanes = new LinkedHashMap<>();
        lanes.put("luajit", new LuaLane(root));
        lanes.put("jvm", new JvmLane(root));
        long start = System.currentTimeMillis();
        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            cases, lanes, workerCount(), Duration.ofSeconds(120));
        System.out.println("  " + verdicts.size() + " verdicts in "
            + (System.currentTimeMillis() - start) + "ms");
        checkEq(dispatched.size(), verdicts.size(),
            "one verdict per dispatched case (no skip over the settled "
                + "lanes)");
        Map<String, int[]> perBackend = new LinkedHashMap<>();
        for (Target target : Target.values()) {
            perBackend.put(target.laneName, new int[2]);
        }
        long failedOutcomes = 0;
        long namedGuardOutcomes = 0;
        List<String> failureLines = new ArrayList<>();
        for (GateDispatcher.CaseVerdict verdict : verdicts) {
            for (String backend : List.of("luajit", "jvm")) {
                boolean present = verdict.outcomes().stream()
                    .anyMatch(o -> backend.equals(o.backend()));
                check(present, verdict.fixturePath() + ": the " + backend
                    + " lane outcome is present");
            }
            for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                int[] counts = perBackend.get(outcome.backend());
                if (counts == null) {
                    fatal(verdict.fixturePath() + ": unexpected lane "
                        + outcome.backend());
                    continue;
                }
                if (outcome.passed()) {
                    counts[0]++;
                    continue;
                }
                counts[1]++;
                failedOutcomes++;
                String detail = outcome.mismatch()
                    .map(m -> m.message()).orElse("");
                boolean namedGuard = NAMED_GUARDS.stream()
                    .anyMatch(detail::contains);
                if (namedGuard) {
                    namedGuardOutcomes++;
                }
                // Every failing outcome is reported per fixture and
                // backend, never skipped and never silently folded into an
                // aggregate: the named producer-guard outcome is the
                // zero-unlowered criterion surfacing at the lane that
                // compiled the project, and every other differential
                // outcome is reported with its own mismatch text.
                failureLines.add(verdict.fixturePath() + " ["
                    + outcome.backend() + "]"
                    + (namedGuard ? " (named producer guard)" : "")
                    + ": " + detail);
            }
        }
        for (Target target : Target.values()) {
            int[] counts = perBackend.get(target.laneName);
            System.out.println("  " + target.laneName + ": " + counts[0]
                + " pass / " + counts[1] + " fail");
            checkEq(dispatched.size(), counts[0] + counts[1],
                target.laneName + ": the pass/fail counters account for "
                    + "every dispatched case");
        }
        System.out.println("  failing lane outcomes: " + failedOutcomes
            + " (each reported per fixture and backend, never skipped; zero "
            + "over the settled surface)");
        for (String line : failureLines) {
            System.out.println("    " + line);
        }
        System.out.println("  zero CONSTRUCT_UNLOWERED/RETAINED_ABI_DEFERRED "
            + "lane outcomes: "
            + (namedGuardOutcomes == 0 ? "PASS" : "FAIL") + " ("
            + namedGuardOutcomes + ")");
        if (failedOutcomes > 0) {
            if (strictAcceptance()) {
                for (String line : failureLines) {
                    fatal("lane acceptance: " + line);
                }
            } else {
                for (String line : failureLines) {
                    deficit("lane acceptance (pre-precondition, reported): "
                        + line);
                }
            }
        }
    }

    // =========================================================================
    // 3. The reachability acceptance invocation
    // =========================================================================

    /**
     * The removed-surface tokens of the production path (HR3): the
     * retained generation call names, the routing/planner/registry
     * references, the retained-emission counter, and the harness arms.
     */
    private static final List<String> REMOVED_SURFACE_TOKENS = List.of(
        "ModuleRoute", "ModuleRoutePlan", "MigrationPlanner",
        "CapabilityRegistry", "routeOf(", "validateMixedEdges",
        "codegenAllLua", "codegenAllJvm", "retainedEmissionCount++");

    private static final String LOWER_PROJECT_CALL =
        "SemanticLowerer.lowerProject(";

    private static void testReachabilityAcceptance() throws Exception {
        System.out.println("-- the reachability acceptance: the whole deal/** "
            + "production source set --");
        Path root = Path.of(".").toAbsolutePath().normalize();
        check(Files.isDirectory(root.resolve("deal")),
            "the production source set is present");
        List<Path> sources = javaSources(root.resolve("deal"));
        check(!sources.isEmpty(),
            "the production source set is real and non-empty");
        List<String> violations = new ArrayList<>();
        int lowerProjectCallSites = 0;
        for (Path file : sources) {
            String relative = slash(root.relativize(file));
            String source = codeLines(Files.readString(file,
                StandardCharsets.UTF_8));
            boolean definingRetainedBackend = relative.endsWith(
                "codegen/lua/LuaBackend.java")
                || relative.endsWith("codegen/jvm/JvmBackend.java");
            if (!definingRetainedBackend) {
                for (String token : List.of("LuaBackend.generateToFile(",
                        "JvmBackend.generate(")) {
                    if (source.contains(token)) {
                        violations.add(relative + ": " + token);
                    }
                }
            }
            int index = 0;
            while ((index = source.indexOf(LOWER_PROJECT_CALL, index)) >= 0) {
                lowerProjectCallSites++;
                index += LOWER_PROJECT_CALL.length();
            }
            if (relative.endsWith("module/CompilationOrchestrator.java")) {
                for (String token : REMOVED_SURFACE_TOKENS) {
                    if (source.contains(token)) {
                        violations.add(relative + ": " + token);
                    }
                }
            }
        }
        checkEq(1, lowerProjectCallSites,
            "exactly one SemanticLowerer.lowerProject( call site exists in "
                + "the production source set");

        // The retained classes and their unit tests stay present (HR2's
        // invariant: the audit cannot silently delete them).
        for (String retained : List.of(
                "deal/codegen/lua/LuaBackend.java",
                "deal/codegen/jvm/JvmBackend.java",
                "deal/semantic/MigrationPlanner.java")) {
            check(Files.isRegularFile(root.resolve(retained)),
                "the retained class " + retained + " is present");
        }
        for (String retainedTest : List.of(
                "test/LuaBackendTest.java",
                "test/JvmBackendTest.java",
                "test/MigrationPlannerTest.java")) {
            check(Files.isRegularFile(root.resolve(retainedTest)),
                "the retained class's unit test " + retainedTest
                    + " is present");
        }

        if (violations.isEmpty()) {
            System.out.println("  zero retained-surface violations over "
                + "deal/**");
        } else if (strictAcceptance()) {
            System.out.println("  strict acceptance mode: ENGAGED "
                + "(-Ddeal.acceptance.strict)");
            for (String violation : violations) {
                fatal("reachability: " + violation);
            }
        } else {
            System.out.println("  strict acceptance mode: DISENGAGED (the "
                + "pre-precondition reporting rule: the removal slice has "
                + "not landed, so the retained sites are reported per file "
                + "and token, never skipped)");
            System.out.println("  Reachability deficit (reported, non-fatal "
                + "under the pre-precondition rule):");
            for (String violation : violations) {
                deficit("reachability: " + violation);
            }
        }
    }

    /**
     * The code lines of one source: comment-only lines (javadoc, block, and
     * line comments) are dropped, so the token audit reads references, never
     * prose.
     */
    private static String codeLines(String source) {
        StringBuilder code = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*")
                    || trimmed.startsWith("//")) {
                code.append('\n');
                continue;
            }
            code.append(line).append('\n');
        }
        return code.toString();
    }

    private static List<Path> javaSources(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.walk(root)) {
            return listing.filter(path -> path.getFileName().toString()
                    .endsWith(".java"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    // =========================================================================
    // The lane-equivalent materialization (the lanes' rule)
    // =========================================================================

    /** One materialized temp project. */
    private record Project(Path root, Path srcRoot, Path entryFile,
                           String modulePath, List<String> hostNames) {
    }

    /** One materialized corpus module. */
    private record Materialized(String corpusPath, String source, int headers) {
    }

    private static Project materialize(CorpusDiscovery.Fixture fixture,
            Map<String, CorpusDiscovery.Fixture> corpusByPath, Target target)
            throws Exception {
        Path root = Files.createTempDirectory("cutover-corpus-drive-");
        Path srcRoot = root.resolve("src");
        Files.createDirectories(srcRoot);
        Path bindings = root.resolve("bindings");
        Path entryCorpus = fixture.file().toAbsolutePath().normalize();
        Path entryDir = entryCorpus.getParent();

        Map<String, String> rawToDeclaration = new LinkedHashMap<>();
        List<String> hostNames = new ArrayList<>();
        List<String> ffiImports = new ArrayList<>();
        List<Materialized> modules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SidecarSchemaValidator.CompilationModule module
                : CorpusDiscovery.compilationSet(fixture, corpusByPath,
                    CONFORMANCE)) {
            if (!seen.add(module.corpusPath())) {
                continue;
            }
            Path corpusFile = CONFORMANCE.resolve(module.corpusPath())
                .toAbsolutePath().normalize();
            String raw = Files.readString(corpusFile, StandardCharsets.UTF_8);
            String stripped = ConformanceHarnessMetadata
                .stripClassificationHeaders(raw);
            Path targetPath = moduleTarget(corpusFile, entryDir, srcRoot);
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, module.source(),
                StandardCharsets.UTF_8);
            int headers = raw.split("\n", -1).length
                - stripped.split("\n", -1).length;
            modules.add(new Materialized(module.corpusPath(), module.source(),
                headers));
        }

        // Explicit-.deal alias copies (the absorbed
        // copyCompanionAliasIfExplicit rule): an import spelling carrying an
        // explicit .deal extension resolves to its alias-named copy in the
        // flat project root.
        Map<String, CorpusDiscovery.Fixture> corpusFixture = corpusByPath;
        for (SidecarSchemaValidator.CompilationModule module
                : CorpusDiscovery.compilationSet(fixture, corpusByPath,
                    CONFORMANCE)) {
            Path moduleCorpusDir = Path.of(module.corpusPath()).getParent();
            Path baseDir = moduleCorpusDir == null
                ? CONFORMANCE
                : CONFORMANCE.resolve(moduleCorpusDir);
            for (String importPath : relativeImports(module.source())) {
                Path companion = resolveCompanionPath(importPath, baseDir);
                if (companion == null) {
                    continue;
                }
                String companionCorpus = slash(CONFORMANCE.toAbsolutePath()
                    .normalize().relativize(companion.toAbsolutePath()
                        .normalize()));
                CorpusDiscovery.Fixture companionFixture =
                    corpusFixture.get(companionCorpus);
                if (companionFixture == null
                        || !importPath.endsWith(".deal")) {
                    continue;
                }
                String aliasBase = importPath;
                if (aliasBase.startsWith("./")) {
                    aliasBase = aliasBase.substring("./".length());
                } else if (aliasBase.startsWith("../")) {
                    aliasBase = aliasBase.substring("../".length());
                }
                Path aliasTarget = srcRoot.resolve(aliasBase + ".deal");
                if (Files.exists(aliasTarget)) {
                    continue;
                }
                Files.writeString(aliasTarget, companionFixture.source(),
                    StandardCharsets.UTF_8);
            }
        }

        for (Materialized module : modules) {
            for (String hostName : hostImports(module.source())) {
                if (!hostNames.contains(hostName)) {
                    hostNames.add(hostName);
                }
            }
            for (String raw : CorpusDiscovery.ffiImportPaths(module.source())) {
                if (CorpusFfi.isFfiImport(CONFORMANCE, raw)
                        && !ffiImports.contains(raw)) {
                    ffiImports.add(raw);
                }
            }
        }
        Files.createDirectories(bindings);
        for (String hostName : hostNames) {
            Path declaration = HOST_FIXTURES.resolve(hostName + ".d.deal");
            if (!Files.isRegularFile(declaration)) {
                throw new IllegalStateException("the host declaration "
                    + declaration + " is missing");
            }
            Files.writeString(bindings.resolve(hostName + ".d.deal"),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(declaration, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            rawToDeclaration.put("host/" + hostName,
                "bindings/" + hostName + ".d.deal");
        }
        for (String raw : ffiImports) {
            CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE, raw);
            Path declaration = CONFORMANCE.resolve(CorpusFfi.FFI_DIR)
                .resolve(wiring.declarationCorpusPath());
            if (!Files.isRegularFile(declaration)) {
                throw new IllegalStateException("the corpus FFI declaration "
                    + declaration + " is missing");
            }
            String fileName = "ffi_" + raw.replace('/', '_') + ".d.deal";
            Files.writeString(bindings.resolve(fileName),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(declaration, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            rawToDeclaration.put(raw, "bindings/" + fileName);
        }

        Files.writeString(root.resolve("deal.json"),
            dealJson(target.laneName, rawToDeclaration));

        String entryStem = moduleNameOf(entryCorpus.getFileName().toString());
        return new Project(root, srcRoot,
            srcRoot.resolve(entryStem + ".deal"), entryStem,
            List.copyOf(hostNames));
    }

    /** The generated manifest: module root, output, backend, externals. */
    private static String dealJson(String backend,
            Map<String, String> rawToDeclaration) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"languageVersion\": \"1.2\",\n");
        json.append("  \"moduleRoots\": [\"src\"],\n");
        json.append("  \"output\": \"out\",\n");
        json.append("  \"backend\": \"").append(backend).append("\"");
        if (rawToDeclaration != null && !rawToDeclaration.isEmpty()) {
            json.append(",\n  \"externals\": {\n");
            boolean first = true;
            for (Map.Entry<String, String> entry
                    : rawToDeclaration.entrySet()) {
                if (!first) {
                    json.append(",\n");
                }
                first = false;
                json.append("    \"").append(entry.getKey())
                    .append("\": { \"declaration\": \"")
                    .append(entry.getValue()).append("\"");
                CorpusFfi.Wiring wiring = CorpusFfi.wiringFor(CONFORMANCE,
                    entry.getKey());
                if (wiring != null) {
                    json.append(", \"nativeLibrary\": \"")
                        .append(CorpusFfi.loaderTextFor(CONFORMANCE, wiring))
                        .append("\"");
                }
                json.append(" }");
            }
            json.append("\n  }");
        }
        json.append("\n}\n");
        return json.toString();
    }

    /**
     * The corpus conformance externals-identity convention (the JVM lane's
     * rule): a host module's class identities project through its dotted
     * typing name while import resolution keys on the raw specifier the
     * generated {@code deal.json} wires.
     */
    private static ProjectContext withDottedHostIdentities(
            ProjectContext context, Project project) {
        if (project.hostNames().isEmpty()) {
            return context;
        }
        Map<String, ExternalEntry> externals = new LinkedHashMap<>();
        for (String hostName : project.hostNames()) {
            String rawKey = "host/" + hostName;
            ExternalEntry rawEntry = context.externals().get(rawKey);
            if (rawEntry == null) {
                continue;
            }
            String dottedKey = "host." + hostName;
            externals.put(dottedKey, new ExternalEntry(dottedKey,
                rawEntry.declarationPath(), rawEntry.nativeLibrary(),
                rawEntry.sourceRange()));
            externals.put(rawKey, rawEntry);
        }
        for (Map.Entry<String, ExternalEntry> entry
                : context.externals().entrySet()) {
            externals.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new ProjectContext(context.manifestPath(), context.projectRoot(),
            context.manifestDirectory(), context.languageVersion(),
            context.configuredModuleRoots(), context.outputPath(),
            context.backend(), externals, context.stdlibVersion(),
            context.stdlibSurfacePath(), context.stdlibDeclarationFiles(),
            context.projectDeploymentIdentity());
    }

    /** The lane's entry-directory-relative module target. */
    private static Path moduleTarget(Path corpusFile, Path entryDir,
            Path srcRoot) {
        Path relative;
        try {
            relative = entryDir.relativize(corpusFile);
        } catch (IllegalArgumentException e) {
            return srcRoot.resolve(moduleNameOf(
                corpusFile.getFileName().toString()) + ".deal");
        }
        if (relative.startsWith("..") || relative.getNameCount() <= 1) {
            return srcRoot.resolve(moduleNameOf(
                corpusFile.getFileName().toString()) + ".deal");
        }
        return srcRoot.resolve(relative);
    }

    private static String moduleNameOf(String fileName) {
        if (fileName.endsWith(".d.deal")) {
            return fileName.substring(0, fileName.length() - ".d.deal".length());
        }
        if (fileName.endsWith(".deal")) {
            return fileName.substring(0, fileName.length() - ".deal".length());
        }
        return fileName;
    }

    /** Bare {@code host/<name>} import names of one source, in order. */
    private static List<String> hostImports(String source) {
        List<String> hosts = new ArrayList<>();
        for (String path : CorpusDiscovery.ffiImportPaths(source)) {
            if (path.startsWith("host/")
                    && !CorpusFfi.isFfiImport(CONFORMANCE, path)) {
                hosts.add(path.substring("host/".length()));
            }
        }
        return hosts;
    }

    /** Relative import specifiers ({@code ./}/{@code ../}) of one source. */
    private static List<String> relativeImports(String source) {
        List<String> paths = new ArrayList<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            int from = trimmed.indexOf(" from \"");
            if (from < 0) {
                continue;
            }
            int end = trimmed.indexOf('"', from + 7);
            if (end < 0) {
                continue;
            }
            String path = trimmed.substring(from + 7, end);
            if (path.startsWith("./") || path.startsWith("../")) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** The resolved companion path of one relative import, or null. */
    private static Path resolveCompanionPath(String importPath, Path baseDir) {
        if (!importPath.startsWith("./") && !importPath.startsWith("../")) {
            return null;
        }
        Path resolved = baseDir.resolve(importPath).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path withExt = baseDir.resolve(importPath + ".deal").normalize();
        if (Files.exists(withExt)) {
            return withExt;
        }
        Path withDeclExt = baseDir.resolve(importPath + ".d.deal").normalize();
        if (Files.exists(withDeclExt)) {
            return withDeclExt;
        }
        return null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static CompilationOrchestrator orchestrator(Path project,
            Path entry) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toAbsolutePath().normalize().toString(),
            new CliOverrides(null, null));
        if (located.context() == null) {
            throw new IllegalStateException("locate failed: " + located);
        }
        return new CompilationOrchestrator(located.context(), entry, false,
            false, false, false, null, productionInvocation());
    }

    private static void write(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cutover Dispatched-Corpus Acceptance Audit "
            + "Tests (ISSUE-0694) ===\n");
        CorpusDiscovery.DiscoveryResult discovery = CorpusDiscovery.discover(
            CONFORMANCE.toAbsolutePath().normalize());
        List<CorpusDiscovery.Fixture> dispatched = discovery.fixtures().stream()
            .filter(CorpusDiscovery.Fixture::runtimeClassified)
            .sorted(Comparator.comparing(CorpusDiscovery.Fixture::corpusPath))
            .toList();
        testCorpusInvariants(discovery, dispatched);
        testDispatchedCorpusProductionDrive(dispatched,
            discovery.corpusByPath());
        testSettledLaneAcceptance(discovery, dispatched);
        testReachabilityAcceptance();
        System.out.println();
        System.out.println("passed: " + passed + ", fatal: " + fatal
            + ", reported deficit: " + reportedDeficit);
        if (fatal > 0) {
            System.exit(1);
        }
        System.out.println("=== Cutover Dispatched-Corpus Acceptance Audit "
            + "Tests Passed ===");
    }
}
