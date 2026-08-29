package deal.test;

import deal.codegen.Backend;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.module.CompilationOrchestrator;
import deal.semantic.ArtifactOwner;
import deal.semantic.BoundaryRealizationReport;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.MigrationPlanner;
import deal.semantic.ModuleEmissionResult;
import deal.semantic.ModuleRoute;
import deal.semantic.ModuleRoutePlan;
import deal.semantic.OperationContractManifest;
import deal.semantic.ProjectArtifactStager;
import deal.semantic.RequirementManifestResult;
import deal.semantic.RoutePlanResult;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.StagedArtifact;
import deal.semantic.Target;
import deal.semantic.TargetModuleAbi;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FunctionSignatureAbi;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SyncInvocationEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Verifies the ISSUE-0292 integration wiring (foundation F8; Sequencing
 * item 11): one combined flow drives a full compiler invocation through
 * profile selection (T4) → the orchestrator's checked project + interface
 * index (T8) → requirement manifests (T9) → route plan (T10) → a
 * synthetic validated unit (T2 records + T3 digests, validated through
 * T6) → staged/validated atomic publication (T11 with synthetically
 * completed ABI records) — and fails when any constituent is broken.
 *
 * <p>Tests:
 * <ol>
 *   <li>Happy path: {@code CompilationOrchestrator.compile()} resolves the
 *       internal {@code COMMON_SHADOW + DEAL_V1_2_INT32} invocation, the
 *       foundation phase runs after phase 3 and before phase 4 (exactly
 *       one checked project input, one interface index, one manifest set,
 *       one route plan), phase 4 public dispatch is unchanged (the
 *       retained artifacts are emitted), and the internal shadow harness
 *       completes the flow through a synthetic validated unit into an
 *       atomically published artifact set.</li>
 *   <li>Fault-injection matrix: each variant faults exactly one
 *       constituent and asserts the combined flow fails on that
 *       constituent with the named defect — wrong profile resolution
 *       (E6005 {@code STAGE_LEGACY_PROFILE_REJECTED} at the lowering
 *       boundary), corrupted checked project/index (planner E6005
 *       {@code ROUTE_INTERNAL_ERROR_SENTINEL}), wrong manifest claim
 *       (stager E6005 {@code STAGE_UNIT_ROUTE_MISMATCH}), wrong route
 *       plan (validator E6005 {@code ABI_EDGE_MISSING}), an invalid
 *       synthetic unit (validator E6005 {@code R-PROFILE}), and an
 *       incomplete ABI record (validator E6005
 *       {@code ABI_MISSING_LOAD_KEY}).</li>
 *   <li>Legacy-profile lowering rejection (F1 verification 1): a lowering
 *       request carrying {@code LEGACY_SAFE_INT} is rejected at the
 *       pipeline boundary with E6005 through T5/T1 and the prescribed
 *       {@code LoweringFailureDetail} payload.</li>
 *   <li>Verbose-report wiring: a verbose invocation prints purpose,
 *       profile, release state, and the release-state hash equal to the
 *       invocation record field; a non-verbose invocation still records
 *       the hash on the invocation.</li>
 * </ol>
 */
public class FoundationIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    public static void main(String[] args) {
        try {
            testFullPipelineIntegration();
            testFaultWrongProfileResolution();
            testFaultCorruptedCheckedProjectIndex();
            testFaultWrongManifestClaim();
            testFaultWrongRoutePlan();
            testFaultInvalidSyntheticUnit();
            testFaultIncompleteAbiRecord();
            testLegacyProfileLoweringRejection();
            testVerboseReportWiring();
        } catch (Throwable t) {
            failed++;
            System.err.println("FAIL: unexpected " + t);
            t.printStackTrace();
        }
        System.out.println();
        System.out.println("FoundationIntegrationTest: " + passed + " passed, "
            + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    private static final ModuleId MOD_A = new ModuleId("a");
    private static final ModuleId MOD_MAIN = new ModuleId("main");

    private static final RuntimeDescriptor DESC_ADD = new RuntimeDescriptor.Func(
        List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
        RuntimeDescriptor.Int.INSTANCE);
    private static final RuntimeDescriptor DESC_MAIN = new RuntimeDescriptor.Func(
        List.of(), RuntimeDescriptor.Null.INSTANCE);

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Map<String, byte[]> readTree(Path tree) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        if (!Files.exists(tree)) {
            return contents;
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = tree.relativize(file).toString()
                    .replace(java.io.File.separatorChar, '/');
                contents.put(relative, Files.readAllBytes(file));
            }
        }
        return contents;
    }

    private static List<Path> siblingTrees(Path root, String marker) throws IOException {
        List<Path> trees = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root.getParent())) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(root.getFileName() + marker)
                        && Files.isDirectory(entry)) {
                    trees.add(entry);
                }
            }
        }
        return trees;
    }

    private static void checkNoSiblings(Path root, String what) throws IOException {
        List<Path> stage = siblingTrees(root, ProjectArtifactStager.STAGE_TREE_MARKER);
        List<Path> retired = siblingTrees(root, ProjectArtifactStager.RETIRED_TREE_MARKER);
        check(stage.isEmpty() && retired.isEmpty(),
            what + ": no staging/retired siblings remain (stage=" + stage
                + ", retired=" + retired + ")");
    }

    /** Asserts the prescribed E6005 {@code LoweringFailureDetail} payload. */
    private static void assertE6005Rule(CompilerDiagnostic diagnostic, String rule,
                                        String module, SemanticCapability capability,
                                        SemanticProfile profile, String origin) {
        check(diagnostic != null, rule + " produces a diagnostic");
        if (diagnostic == null) {
            return;
        }
        check("E6005".equals(diagnostic.code()), rule + " code is E6005");
        check(diagnostic.diagnosticCode() == DiagnosticCode.E6005,
            rule + " diagnosticCode is E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            rule + " phase is BACKEND_LOWERING");
        check("error".equals(diagnostic.severity()), rule + " severity is error");
        String message = diagnostic.message();
        check(message.startsWith("Common semantic lowering failed"),
            rule + " instantiates the registry-owned E6005 template (T5)");
        check(message.contains("module '" + module + "'"),
            rule + " names module '" + module + "'; got \"" + message + "\"");
        check(message.contains("capability " + capability),
            rule + " carries capability " + capability + "; got \"" + message + "\"");
        check(message.contains("validatorRule " + rule + ","),
            rule + " is named as the validator rule; got \"" + message + "\"");
        check(message.contains("semanticProfile " + profile),
            rule + " carries the semantic profile " + profile);
        check(message.contains("irVersion deal.semantic-ir/1"),
            rule + " carries the pinned irVersion");
        check(message.contains("origin " + origin),
            rule + " names the producing component '" + origin + "'");
    }

    /** The T2-record synthetic unit passing the closed T6 rules. */
    private static LoweredModuleUnit validUnit(ModuleId module, ProjectInterfaceIndex index,
                                               CompilerInvocation invocation) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, module, index.interfaceIndexDigest(),
            LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                invocation.capabilityRegistryHash()),
            Set.of(), Map.of(), Map.of(), Map.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            List.of());
    }

    /** The invalid synthetic unit: an interfaceHash contradicting the index (T6 R-PROFILE). */
    private static LoweredModuleUnit invalidUnit(ProjectInterfaceIndex index,
                                                 CompilerInvocation invocation) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, MOD_MAIN, "corrupted-interface-hash",
            LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                invocation.capabilityRegistryHash()),
            Set.of(), Map.of(), Map.of(), Map.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            List.of());
    }

    /** The synthetically completed stage-time record for legacy dependency a (F5/F6). */
    private static TargetModuleAbi completeAbiA(TargetModuleAbi planTime) {
        return new TargetModuleAbi(planTime.moduleId(), planTime.target(),
            planTime.artifactOwner(), planTime.semanticProfile(),
            planTime.classFactoryAbi(), planTime.classLayoutAbi(),
            Map.of("add", DESC_ADD), "a/artifact.lua", "a_init",
            Map.of("add", new FunctionSignatureAbi("add_wrapper", "(int, int) => int")),
            Map.of("add", new SyncInvocationEntry.AbiWrapper("add_wrapper")),
            List.of());
    }

    /** The emitted SHARED-owner ABI manifest of the shared entry module. */
    private static TargetModuleAbi emittedMainManifest() {
        return new TargetModuleAbi(MOD_MAIN, Target.LUAJIT, ArtifactOwner.SHARED,
            SemanticProfile.DEAL_V1_2_INT32,
            Map.of(), Map.of(), Map.of("main", DESC_MAIN),
            "main/artifact.lua", "main_init",
            Map.of("main", new FunctionSignatureAbi("main_wrapper", "() => null")),
            Map.of("main", new SyncInvocationEntry.AbiWrapper("main_wrapper")),
            List.of());
    }

    /** The forged wrong manifest claim: main claims STDLIB_TIME_CONFLICT (a T9 defect). */
    private static List<SemanticRequirementManifest> forgedConflictManifest(
            List<SemanticRequirementManifest> manifests) {
        List<SemanticRequirementManifest> forged = new ArrayList<>();
        for (SemanticRequirementManifest manifest : manifests) {
            if (manifest.moduleId().equals(MOD_MAIN)) {
                forged.add(new SemanticRequirementManifest(MOD_MAIN,
                    EnumSet.of(SemanticCapability.FOUNDATION_VALUES,
                        SemanticCapability.STDLIB_TIME_CONFLICT),
                    manifest.constructCoverage()));
            } else {
                forged.add(manifest);
            }
        }
        return forged;
    }

    /** The corrupted interface index: module a dropped (a T8 defect). */
    private static ProjectInterfaceIndex corruptedIndex(ProjectInterfaceIndex index) {
        Map<ModuleId, ExternalModuleInterface> corrupted =
            new LinkedHashMap<>(index.modules());
        corrupted.remove(MOD_A);
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, corrupted);
    }

    // =========================================================================
    // The combined integration pipeline (Sequencing item 11)
    // =========================================================================

    /** The named single-constituent faults; exactly one per variant. */
    private enum Fault { NONE, PROFILE, INDEX, MANIFEST, PLAN, UNIT, ABI }

    /** The outcome of one combined-flow run: the result plus the pinned products. */
    private record PipelineRun(
        boolean ok,
        String failureStep,
        List<CompilerDiagnostic> diagnostics,
        CompilerInvocation invocation,
        CheckedProjectBuildResult checked,
        List<SemanticRequirementManifest> manifests,
        ProjectInterfaceIndex index,
        ModuleRoutePlan orchestratorPlan,
        ModuleRoutePlan harnessPlan,
        LoweredModuleUnit unit,
        String harnessPlanTextBeforeStaging,
        Path publicationRoot
    ) {

        static PipelineRun success(CompilerInvocation invocation,
                CheckedProjectBuildResult checked,
                List<SemanticRequirementManifest> manifests, ProjectInterfaceIndex index,
                ModuleRoutePlan orchestratorPlan, ModuleRoutePlan harnessPlan,
                LoweredModuleUnit unit, String harnessPlanTextBeforeStaging,
                Path publicationRoot) {
            return new PipelineRun(true, "complete", List.of(), invocation, checked,
                manifests, index, orchestratorPlan, harnessPlan, unit,
                harnessPlanTextBeforeStaging, publicationRoot);
        }

        static PipelineRun failure(String step, List<CompilerDiagnostic> diagnostics,
                CompilerInvocation invocation, CheckedProjectBuildResult checked,
                List<SemanticRequirementManifest> manifests, ProjectInterfaceIndex index,
                ModuleRoutePlan orchestratorPlan, ModuleRoutePlan harnessPlan,
                LoweredModuleUnit unit, String harnessPlanTextBeforeStaging,
                Path publicationRoot) {
            return new PipelineRun(false, step, diagnostics, invocation, checked, manifests,
                index, orchestratorPlan, harnessPlan, unit, harnessPlanTextBeforeStaging,
                publicationRoot);
        }
    }

    /**
     * The two-module fixture written into {@code tmp}: {@code a.deal}
     * exports {@code add(x, y)}, {@code main.deal} imports it and exports
     * {@code main(): null} — one real mixed edge (a legacy dependency of
     * the shared entry module).
     */
    private static Path writeProjectFixture(Path tmp) throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("a.deal"), """
            export function add(x: int, y: int): int {
              return x + y
            }
            """);
        Files.writeString(src.resolve("main.deal"), """
            import * as a from "./a"

            export function main(): null {
              a.add(1, 2)
              return null
            }
            """);
        return src;
    }

    /**
     * Runs the complete combined flow once with at most one faulted
     * constituent:
     *
     * <ol>
     *   <li><b>T4 profile selection</b> — the internal
     *       {@code COMMON_SHADOW + DEAL_V1_2_INT32} invocation.</li>
     *   <li><b>Orchestrator (F8 wiring)</b> — phases 0-4 plus the
     *       foundation phase: exactly one checked project input + one
     *       interface index (T8), one manifest set (T9), one route plan
     *       (T10) per compile.</li>
     *   <li><b>Internal shadow harness (T10)</b> — shadow-request the
     *       entry module; the plan routes {@code a} LEGACY (the retained
     *       dependency) and {@code main} SHARED with one plan-time ABI
     *       record for {@code a}.</li>
     *   <li><b>T2/T3 + T6</b> — the synthetic validated unit built from
     *       the real index digest and the invocation's lowering-context
     *       facts, validated against the comparison facts.</li>
     *   <li><b>T11</b> — synthetically completed ABI records plus the
     *       retained carrier and the shared emission result, staged,
     *       validated, and atomically published.</li>
     * </ol>
     *
     * @param tmp   the scratch directory (source, build, and publication
     *              root live inside it)
     * @param fault the single faulted constituent, or {@code NONE}
     * @return the run outcome; never null
     */
    private static PipelineRun runIntegratedPipeline(Path tmp, Fault fault)
            throws IOException {
        Path src = writeProjectFixture(tmp);
        Path entry = src.resolve("main.deal").toAbsolutePath();
        List<Path> roots = List.of(src.toAbsolutePath());
        Path stdlib = Path.of("std").toAbsolutePath().normalize();

        // T4: profile selection — the internal shadow invocation.
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry());

        // F8 wiring: the orchestrator carries the resolved invocation,
        // runs phases 0-4, and runs the foundation phase after phase 3
        // (checked project -> index -> manifests -> route plan) before
        // phase 4.
        Path outDir = tmp.resolve("build");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, outDir, false, false, false, false, Backend.LUAJIT, null,
            roots, null, null, invocation);
        if (!orchestrator.compile()) {
            return PipelineRun.failure("orchestrator", orchestrator.diagnostics(),
                invocation, null, List.of(), null, null, null, null, null, null);
        }
        CheckedProjectBuildResult checked = orchestrator.checkedProject();
        if (checked == null || checked.hasErrors()) {
            return PipelineRun.failure("checked-project",
                checked == null ? List.of() : checked.diagnostics(), invocation, checked,
                List.of(), null, null, null, null, null, null);
        }
        RequirementManifestResult manifestResult = orchestrator.requirementManifests();
        if (manifestResult == null || manifestResult.hasErrors()) {
            return PipelineRun.failure("manifests",
                manifestResult == null ? List.of() : manifestResult.diagnostics(),
                invocation, checked, List.of(), null, null, null, null, null, null);
        }
        List<SemanticRequirementManifest> manifests =
            new ArrayList<>(manifestResult.manifests());

        // Fault INDEX: the corrupted interface index handed to the planner.
        ProjectInterfaceIndex index = checked.index();
        if (fault == Fault.INDEX) {
            index = corruptedIndex(checked.index());
        }
        // Fault MANIFEST: the forged wrong claim handed to the planner.
        if (fault == Fault.MANIFEST) {
            manifests = forgedConflictManifest(manifests);
        }

        // T10 (internal COMMON_SHADOW harness): shadow-request the entry
        // module over the real checked project, index, and manifests.
        RoutePlanResult planned = MigrationPlanner.planRoutes(invocation,
            CapabilityRegistry.releaseRegistry(), checked.input(), index, manifests,
            Target.LUAJIT, Set.of(MOD_MAIN));
        if (planned.hasErrors()) {
            return PipelineRun.failure("route-plan", planned.diagnostics(), invocation,
                checked, manifests, index, orchestrator.routePlan() == null ? null
                    : orchestrator.routePlan().plan(), null, null, null, null);
        }
        ModuleRoutePlan harnessPlan = planned.plan();
        // Fault PLAN: the forged wrong plan handed to the stager — the
        // legacy dependency's ABI edge is dropped while main stays SHARED.
        if (fault == Fault.PLAN) {
            harnessPlan = new ModuleRoutePlan(harnessPlan.target(), harnessPlan.entries(),
                harnessPlan.shadowModules(), List.of(), harnessPlan.invocationHash(),
                harnessPlan.planId());
        }

        // T2/T3: the synthetic unit built from the real index digest and
        // the invocation's lowering-context facts.
        LoweredModuleUnit unit = validUnit(MOD_MAIN, index, invocation);
        if (fault == Fault.UNIT) {
            unit = invalidUnit(index, invocation);
        }

        // T6: validation against the comparison facts.
        SemanticIrValidator.ComparisonFacts facts = new SemanticIrValidator.ComparisonFacts(
            index.interfaceIndexDigest(), invocation.semanticProfile(),
            invocation.capabilityRegistryHash());
        Optional<CompilerDiagnostic> unitFailure = SemanticIrValidator.validate(unit, facts);
        if (unitFailure.isPresent()) {
            return PipelineRun.failure("unit-validation", List.of(unitFailure.get()),
                invocation, checked, manifests, index,
                orchestrator.routePlan() == null ? null : orchestrator.routePlan().plan(),
                harnessPlan, unit, null, null);
        }

        // T11: the synthetically completed ABI records.
        List<TargetModuleAbi> abiEdges = new ArrayList<>();
        for (TargetModuleAbi planTime : harnessPlan.abiEdges()) {
            abiEdges.add(fault == Fault.ABI ? planTime : completeAbiA(planTime));
        }
        ModuleEmissionResult retainedA = new ModuleEmissionResult(
            List.of(new StagedArtifact("a/artifact.lua", bytes("a-artifact"))), List.of(),
            null, BoundaryRealizationReport.empty(), null);
        ModuleEmissionResult resultMain = new ModuleEmissionResult(
            List.of(new StagedArtifact("main/artifact.lua", bytes("main-artifact"))),
            List.of(), emittedMainManifest(), BoundaryRealizationReport.empty(),
            new OperationContractManifest(unit));

        // Fault PROFILE: the lowering request carries the wrongly resolved
        // LEGACY_SAFE_INT invocation (a valid T4 resolution, wrong for the
        // lowering flow) — rejected at the pipeline boundary.
        CompilerInvocation loweringInvocation = invocation;
        if (fault == Fault.PROFILE) {
            loweringInvocation = CompilerProfileProvider.resolve(
                ReleaseState.PRE_ACTIVATION, CapabilityRegistry.releaseRegistry());
        }

        String planTextBefore = harnessPlan.canonicalText();
        Path root = tmp.resolve("live");
        ProjectArtifactStager.PublicationOutcome outcome =
            ProjectArtifactStager.stageValidatePublish(root, loweringInvocation, harnessPlan,
                List.of(retainedA, resultMain), abiEdges, index);
        if (!outcome.published()) {
            return PipelineRun.failure("staging-publication", outcome.diagnostics(),
                invocation, checked, manifests, index,
                orchestrator.routePlan() == null ? null : orchestrator.routePlan().plan(),
                harnessPlan, unit, planTextBefore, root);
        }
        return PipelineRun.success(invocation, checked, manifests, index,
            orchestrator.routePlan() == null ? null : orchestrator.routePlan().plan(),
            harnessPlan, unit, planTextBefore, root);
    }

    // =========================================================================
    // 1. Happy path: the full combined flow
    // =========================================================================

    static void testFullPipelineIntegration() throws Exception {
        System.out.println("-- Full pipeline: profile -> checked project -> index -> "
            + "manifests -> route plan -> synthetic validated unit -> atomic publication --");

        Path tmp = Files.createTempDirectory("deal-foundation-integration");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.NONE);
            check(run.ok() && "complete".equals(run.failureStep()),
                "the combined flow succeeds end to end; step='" + run.failureStep()
                    + "' diagnostics=" + run.diagnostics());

            // T4: exactly one profile/purpose/release state per invocation.
            CompilerInvocation invocation = run.invocation();
            check(invocation.purpose() == InvocationPurpose.COMMON_SHADOW
                    && invocation.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32
                    && invocation.releaseState() == ReleaseState.V1_2_ACTIVE,
                "the invocation carries exactly one purpose/profile/release state");
            check(invocation.releaseStateHash().equals(
                    CompilerProfileProvider.deriveReleaseStateHash(
                        ReleaseState.V1_2_ACTIVE, invocation.capabilityRegistryHash())),
                "the invocation records the derived release-state hash");

            // T8: exactly one checked project input + one interface index.
            CheckedProjectInput input = run.checked().input();
            check(input.releaseStateHash().equals(invocation.releaseStateHash()),
                "the checked project records the invocation's releaseStateHash verbatim");
            check(input.modules().size() == 2,
                "the input covers exactly the two implementation modules; got "
                    + input.modules().size());
            check(new ArrayList<>(run.index().modules().keySet()).equals(
                    List.of(MOD_A, MOD_MAIN)),
                "the index covers the closure in dependency order; got "
                    + run.index().modules().keySet());

            // T9: exactly one manifest per implementation module.
            check(run.manifests().size() == 2,
                "the foundation phase computed exactly one manifest per implementation "
                    + "module; got " + run.manifests().size());
            check(run.manifests().get(0).moduleId().equals(MOD_A)
                    && run.manifests().get(1).moduleId().equals(MOD_MAIN),
                "the manifests are in dependency order");
            for (SemanticRequirementManifest manifest : run.manifests()) {
                check(manifest.capabilities().contains(SemanticCapability.FOUNDATION_VALUES)
                        && !manifest.capabilities().contains(
                            SemanticCapability.STDLIB_TIME_CONFLICT),
                    "manifest " + manifest.moduleId() + " claims FOUNDATION_VALUES and "
                        + "no STDLIB_TIME_CONFLICT (no time access in the fixture)");
            }

            // T10: the orchestrator's own plan (no shadow request) is
            // all-LEGACY; the internal shadow harness plan routes the
            // entry module SHARED with one plan-time ABI edge for a.
            ModuleRoutePlan orchestratorPlan = run.orchestratorPlan();
            check(orchestratorPlan != null
                    && orchestratorPlan.entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.LEGACY)
                    && orchestratorPlan.shadowModules().isEmpty(),
                "the orchestrator's COMMON_SHADOW plan without shadow requests is "
                    + "all-LEGACY with no shadow entries");
            ModuleRoutePlan harnessPlan = run.harnessPlan();
            check(harnessPlan.entries().get(MOD_A) == ModuleRoute.LEGACY
                    && harnessPlan.entries().get(MOD_MAIN) == ModuleRoute.SHARED,
                "the shadow harness plan routes a LEGACY (retained) and main SHARED");
            check(harnessPlan.shadowModules().equals(Set.of(MOD_MAIN)),
                "the shadow harness plan records main as a shadow SHARED entry");
            check(harnessPlan.abiEdges().size() == 1
                    && harnessPlan.abiEdges().get(0).moduleId().equals(MOD_A)
                    && harnessPlan.abiEdges().get(0).artifactOwner()
                        == ArtifactOwner.RETAINED_LUAJIT
                    && harnessPlan.abiEdges().get(0).loadKey() == null,
                "the plan carries exactly one plan-time ABI record for a "
                    + "(RETAINED_LUAJIT, planner-owned fields only)");

            // T11: the atomically published live set equals the staged
            // content; no stale siblings; the nonce never enters the plan.
            Map<String, byte[]> live = readTree(run.publicationRoot());
            check(live.size() == 2
                    && Arrays.equals(live.get("a/artifact.lua"), bytes("a-artifact"))
                    && Arrays.equals(live.get("main/artifact.lua"), bytes("main-artifact")),
                "the published live set carries the staged artifacts byte-identically; "
                    + "got " + live.keySet());
            checkNoSiblings(run.publicationRoot(), "full pipeline");
            check(run.harnessPlan().canonicalText().equals(
                    run.harnessPlanTextBeforeStaging()),
                "the staging nonce never enters the plan record (byte-identical plan "
                    + "text)");

            // Phase 4 public dispatch is unchanged: the retained emitters
            // wrote their artifacts directly into the build root.
            check(Files.exists(tmp.resolve("build/main.lua"))
                    && Files.exists(tmp.resolve("build/a.lua")),
                "phase 4 public dispatch is unchanged (retained Lua artifacts exist)");

            // The combined flow is deterministic: a second identical run
            // publishes the identical set.
            PipelineRun second = runIntegratedPipeline(
                Files.createTempDirectory("deal-foundation-integration-2"), Fault.NONE);
            check(second.ok(), "the repeated combined flow succeeds: " + second.diagnostics());
            if (second.ok()) {
                check(second.harnessPlan().canonicalText().equals(
                        run.harnessPlan().canonicalText()),
                    "repeated builds produce byte-identical route plans");
                check(second.index().interfaceIndexDigest().equals(
                        run.index().interfaceIndexDigest()),
                    "repeated builds produce byte-identical interface indexes");
            }
            deleteRecursively(Path.of(second.publicationRoot().getParent().toString()));
        } finally {
            deleteRecursively(tmp);
        }
    }

    // =========================================================================
    // 2. Fault-injection matrix (each variant faults exactly one constituent)
    // =========================================================================

    static void testFaultWrongProfileResolution() throws Exception {
        System.out.println("-- Fault: wrong profile resolution (T4) --");
        Path tmp = Files.createTempDirectory("deal-foundation-fault-profile");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.PROFILE);
            check(!run.ok() && "staging-publication".equals(run.failureStep()),
                "a wrongly resolved profile fails the combined flow at the lowering "
                    + "boundary; step='" + run.failureStep() + "'");
            check(!run.diagnostics().isEmpty()
                    && "STAGE_LEGACY_PROFILE_REJECTED".equals(ruleOf(run.diagnostics().get(0))),
                "the named defect 'wrong profile resolution' is detected "
                    + "(STAGE_LEGACY_PROFILE_REJECTED); got "
                    + run.diagnostics().stream().map(CompilerDiagnostic::message).toList());
            if (!run.diagnostics().isEmpty()) {
                assertE6005Rule(run.diagnostics().get(0),
                    ProjectArtifactStager.STAGE_LEGACY_PROFILE_REJECTED, "",
                    SemanticCapability.FOUNDATION_VALUES, SemanticProfile.LEGACY_SAFE_INT,
                    "ProjectArtifactStager");
            }
            check(!Files.exists(run.publicationRoot()),
                "the rejected lowering request publishes nothing (no live set)");
            checkNoSiblings(run.publicationRoot(), "wrong profile resolution");
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testFaultCorruptedCheckedProjectIndex() throws Exception {
        System.out.println("-- Fault: corrupted checked project/index (T8) --");
        Path tmp = Files.createTempDirectory("deal-foundation-fault-index");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.INDEX);
            check(!run.ok() && "route-plan".equals(run.failureStep()),
                "a corrupted interface index fails the combined flow at route planning; "
                    + "step='" + run.failureStep() + "'");
            check(!run.diagnostics().isEmpty()
                    && "ROUTE_INTERNAL_ERROR_SENTINEL".equals(ruleOf(run.diagnostics().get(0))),
                "the named defect 'corrupted checked project/index' is detected "
                    + "(ROUTE_INTERNAL_ERROR_SENTINEL); got "
                    + run.diagnostics().stream().map(CompilerDiagnostic::message).toList());
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testFaultWrongManifestClaim() throws Exception {
        System.out.println("-- Fault: wrong manifest claim (T9) --");
        Path tmp = Files.createTempDirectory("deal-foundation-fault-manifest");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.MANIFEST);
            check(!run.ok() && "staging-publication".equals(run.failureStep()),
                "a wrong manifest claim fails the combined flow at staging (the "
                    + "conflict-claiming module reroutes LEGACY and never stages); step='"
                    + run.failureStep() + "'");
            check(!run.diagnostics().isEmpty()
                    && "STAGE_UNIT_ROUTE_MISMATCH".equals(ruleOf(run.diagnostics().get(0))),
                "the named defect 'wrong manifest claim' is detected "
                    + "(STAGE_UNIT_ROUTE_MISMATCH); got "
                    + run.diagnostics().stream().map(CompilerDiagnostic::message).toList());
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testFaultWrongRoutePlan() throws Exception {
        System.out.println("-- Fault: wrong route plan (T10) --");
        Path tmp = Files.createTempDirectory("deal-foundation-fault-plan");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.PLAN);
            check(!run.ok() && "staging-publication".equals(run.failureStep()),
                "a wrong route plan fails the combined flow at staging (the legacy "
                    + "dependency's ABI edge is missing); step='" + run.failureStep() + "'");
            check(!run.diagnostics().isEmpty()
                    && "ABI_EDGE_MISSING".equals(ruleOf(run.diagnostics().get(0))),
                "the named defect 'wrong route plan' is detected (ABI_EDGE_MISSING); got "
                    + run.diagnostics().stream().map(CompilerDiagnostic::message).toList());
            check(!Files.exists(run.publicationRoot()),
                "the wrong plan publishes nothing (no live set)");
            checkNoSiblings(run.publicationRoot(), "wrong route plan");
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testFaultInvalidSyntheticUnit() throws Exception {
        System.out.println("-- Fault: invalid synthetic unit (T2/T3/T6) --");
        Path tmp = Files.createTempDirectory("deal-foundation-fault-unit");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.UNIT);
            check(!run.ok() && "unit-validation".equals(run.failureStep()),
                "an invalid synthetic unit fails the combined flow at T6 validation; "
                    + "step='" + run.failureStep() + "'");
            check(!run.diagnostics().isEmpty()
                    && SemanticIrValidator.R_PROFILE.equals(ruleOf(run.diagnostics().get(0))),
                "the named defect 'invalid synthetic unit' is detected (R-PROFILE); got "
                    + run.diagnostics().stream().map(CompilerDiagnostic::message).toList());
            if (!run.diagnostics().isEmpty()) {
                assertE6005Rule(run.diagnostics().get(0), SemanticIrValidator.R_PROFILE,
                    MOD_MAIN.toString(), SemanticCapability.FOUNDATION_VALUES,
                    SemanticProfile.DEAL_V1_2_INT32, "SemanticIrValidator");
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testFaultIncompleteAbiRecord() throws Exception {
        System.out.println("-- Fault: incomplete ABI record (T11) --");
        Path tmp = Files.createTempDirectory("deal-foundation-fault-abi");
        try {
            PipelineRun run = runIntegratedPipeline(tmp, Fault.ABI);
            check(!run.ok() && "staging-publication".equals(run.failureStep()),
                "an incomplete ABI record fails the combined flow at stage-time "
                    + "validation; step='" + run.failureStep() + "'");
            check(!run.diagnostics().isEmpty()
                    && "ABI_MISSING_LOAD_KEY".equals(ruleOf(run.diagnostics().get(0))),
                "the named defect 'incomplete ABI record' is detected "
                    + "(ABI_MISSING_LOAD_KEY); got "
                    + run.diagnostics().stream().map(CompilerDiagnostic::message).toList());
            if (!run.diagnostics().isEmpty()) {
                assertE6005Rule(run.diagnostics().get(0),
                    "ABI_MISSING_LOAD_KEY", MOD_A.toString(), SemanticCapability.MODULES,
                    SemanticProfile.DEAL_V1_2_INT32, "TargetAbiValidator");
            }
            check(!Files.exists(run.publicationRoot()),
                "the incomplete record publishes nothing (no live set)");
            checkNoSiblings(run.publicationRoot(), "incomplete ABI record");
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** Extracts the {@code validatorRule} value from an E6005 message. */
    private static String ruleOf(CompilerDiagnostic diagnostic) {
        String message = diagnostic.message();
        int index = message.indexOf("validatorRule ");
        if (index < 0) {
            return null;
        }
        String rest = message.substring(index + "validatorRule ".length());
        int comma = rest.indexOf(',');
        return comma < 0 ? rest : rest.substring(0, comma);
    }

    // =========================================================================
    // 3. Legacy-profile lowering rejection (F1 verification 1)
    // =========================================================================

    static void testLegacyProfileLoweringRejection() throws Exception {
        System.out.println("-- Legacy-profile lowering rejection: LEGACY_SAFE_INT -> E6005 --");

        Path tmp = Files.createTempDirectory("deal-foundation-legacy-reject");
        try {
            // A valid T4 resolution whose profile LEGACY_SAFE_INT makes it
            // a rejected lowering request: lowering admits only
            // DEAL_V1_2_INT32 (F1).
            CompilerInvocation legacyInvocation = CompilerProfileProvider.resolve(
                ReleaseState.PRE_ACTIVATION, CapabilityRegistry.releaseRegistry());
            check(legacyInvocation.purpose() == InvocationPurpose.PUBLIC_BUILD
                    && legacyInvocation.semanticProfile() == SemanticProfile.LEGACY_SAFE_INT,
                "T4 resolves the public PRE_ACTIVATION invocation with "
                    + "LEGACY_SAFE_INT (inspectable for routing/regression)");

            // A minimal synthetic lowering request: one SHARED shadow plan
            // and one synthetic unit — all consistent, only the profile is
            // legacy.
            Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>();
            modules.put(MOD_MAIN, new ExternalModuleInterface(MOD_MAIN,
                ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
                InitializationMode.ONCE_AFTER_DEPENDENCIES));
            ProjectInterfaceIndex index =
                new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
            String hash = MigrationPlanner.deriveInvocationHash(legacyInvocation,
                index.interfaceIndexDigest(), Target.LUAJIT);
            ModuleRoutePlan plan = new ModuleRoutePlan(Target.LUAJIT,
                Map.of(MOD_MAIN, ModuleRoute.SHARED), Set.of(MOD_MAIN), List.of(), hash,
                MigrationPlanner.planIdFor(hash));
            LoweredModuleUnit unit = validUnit(MOD_MAIN, index,
                CompilerProfileProvider.resolveCommonShadow(ReleaseState.V1_2_ACTIVE,
                    CapabilityRegistry.releaseRegistry()));
            ModuleEmissionResult result = new ModuleEmissionResult(
                List.of(new StagedArtifact("main/artifact.lua", bytes("main-artifact"))),
                List.of(), emittedMainManifest(), BoundaryRealizationReport.empty(),
                new OperationContractManifest(unit));

            Path root = tmp.resolve("live");
            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(root, legacyInvocation, plan,
                    List.of(result), List.of(), index);

            check(!outcome.published(),
                "a lowering request carrying LEGACY_SAFE_INT is rejected (never lowered)");
            check(outcome.diagnostics().size() == 1,
                "the rejection is exactly one E6005; got " + outcome.diagnostics());
            assertE6005Rule(outcome.diagnostics().get(0),
                ProjectArtifactStager.STAGE_LEGACY_PROFILE_REJECTED, "",
                SemanticCapability.FOUNDATION_VALUES, SemanticProfile.LEGACY_SAFE_INT,
                "ProjectArtifactStager");
            check(!Files.exists(root),
                "the rejected lowering request creates no live set and no staging tree");
            checkNoSiblings(root, "legacy-profile rejection");
        } finally {
            deleteRecursively(tmp);
        }
    }

    // =========================================================================
    // 4. Verbose-report wiring (F1/F8)
    // =========================================================================

    static void testVerboseReportWiring() throws Exception {
        System.out.println("-- Verbose report wiring: purpose/profile/release state/hash --");

        Path tmp = Files.createTempDirectory("deal-foundation-verbose");
        try {
            Path src = tmp.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("trivial.deal"),
                "export function main(): null { return null; }\n");
            Path entry = src.resolve("trivial.deal").toAbsolutePath();
            List<Path> roots = List.of(src.toAbsolutePath());
            CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
                ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry());

            // Non-verbose path: the derived hash is recorded on the
            // invocation regardless of the report.
            CompilationOrchestrator quiet = new CompilationOrchestrator(
                entry, tmp.resolve("build-quiet"), false, false, false, false,
                Backend.LUAJIT, null, roots, null, null, invocation);
            ByteArrayOutputStream quietOut = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(quietOut, true, StandardCharsets.UTF_8));
            boolean quietOk;
            try {
                quietOk = quiet.compile();
            } finally {
                System.setOut(originalOut);
            }
            check(quietOk, "the non-verbose compile succeeds");
            check(quiet.invocation().equals(invocation)
                    && invocation.releaseStateHash().equals(
                        quiet.invocation().releaseStateHash()),
                "the non-verbose path still records the derived hash on the "
                    + "invocation record");
            check(!quietOut.toString(StandardCharsets.UTF_8).contains("Purpose:"),
                "the non-verbose report prints no invocation facts (display-only)");

            // Verbose path: the report prints the recorded facts and the
            // printed hash equals the invocation record field — the
            // recording location is the record, never the report.
            CompilationOrchestrator verbose = new CompilationOrchestrator(
                entry, tmp.resolve("build-verbose"), true, false, false, false,
                Backend.LUAJIT, null, roots, null, null, invocation);
            ByteArrayOutputStream verboseOut = new ByteArrayOutputStream();
            System.setOut(new PrintStream(verboseOut, true, StandardCharsets.UTF_8));
            boolean verboseOk;
            try {
                verboseOk = verbose.compile();
            } finally {
                System.setOut(originalOut);
            }
            check(verboseOk, "the verbose compile succeeds");
            String text = verboseOut.toString(StandardCharsets.UTF_8);
            check(text.contains("Purpose: " + invocation.purpose()),
                "the verbose report prints the purpose");
            check(text.contains("Semantic profile: " + invocation.semanticProfile()),
                "the verbose report prints the profile");
            check(text.contains("Release state: " + invocation.releaseState()),
                "the verbose report prints the release state");
            check(text.contains("Release-state hash: " + invocation.releaseStateHash()),
                "the verbose report prints the release-state hash equal to the "
                    + "invocation record field");
            check(verbose.invocation().releaseStateHash().equals(
                    invocation.releaseStateHash()),
                "the verbose path records the same derived hash on the invocation "
                    + "record");

            // The foundation phase runs in both paths: exactly one checked
            // project, one manifest set, and one route plan per compile.
            check(verbose.checkedProject() != null
                    && !verbose.checkedProject().hasErrors()
                    && quiet.checkedProject() != null
                    && !quiet.checkedProject().hasErrors(),
                "verbose and non-verbose compiles each build exactly one checked "
                    + "project + interface index");
            check(verbose.requirementManifests() != null
                    && verbose.requirementManifests().manifests().size() == 1
                    && verbose.routePlan() != null && !verbose.routePlan().hasErrors(),
                "the verbose compile computes exactly one manifest set and one route "
                    + "plan (foundation phase before phase 4)");
        } finally {
            deleteRecursively(tmp);
        }
    }
}
