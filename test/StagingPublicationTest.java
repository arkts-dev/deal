package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.FunctionSignatureAbi;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SyncInvocationEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Verifies the ISSUE-0291 foundation surface: {@link ProjectArtifactStager}
 * staging, {@link TargetAbiValidator} complete-record mixed-edge
 * validation, and the atomic stage → validate → publish sequence with
 * per-root serialization and under-lock crash recovery (foundation F5/F6;
 * Verification 5).
 *
 * <p>Tests:
 * <ol>
 *   <li>Validator: a complete record passes; one case per incomplete-record
 *       class (missing load key, initialization entry, exported
 *       descriptors, wrapper ABI, sync-invocation entry, async-linkage
 *       record) and per mismatch class (descriptors contradicting the
 *       index, constructionEntry != the T8-derived id, artifactOwner !=
 *       the route entry, semanticProfile != the project profile) — each
 *       E6005 through T5/T1 with capability MODULES and the prescribed
 *       payload; the mixed-edge coverage gate fires when a SHARED module's
 *       legacy dependency has no record.</li>
 *   <li>Forced-failure stage test: an injected mid-stage emission failure
 *       leaves the prior live set byte-untouched, publishes nothing,
 *       writes no partial module artifact, and removes the staging tree.</li>
 *   <li>Fresh-root branch: live absent + success → the live set equals the
 *       staged content and the retire step was a no-op; live absent +
 *       failing single move → the root is left without a live set and the
 *       staging tree is removed.</li>
 *   <li>Restore-on-failure: a failing second move restores retired → live
 *       and leaves the prior set byte-identical.</li>
 *   <li>Torn-publish recovery: live absent + retired present + a stale
 *       .deal-stage-* sibling → recovery under the per-root lock restores
 *       retired → live and removes the dead stage tree at the same publish
 *       start; stale-stage-only recovery removes the stale trees; recovery
 *       refuses to run without the lock and never removes the current
 *       invocation's live staging tree.</li>
 *   <li>Concurrent isolation: two concurrent identical invocations
 *       (identical planId) serialize on the per-root lock, never share a
 *       staging/retired tree, and leave the live set equal to the staged
 *       content; a loser-side failure publishes none and leaves the
 *       winner's set untouched; a blocked invocation creates no staging
 *       tree until it holds the lock.</li>
 *   <li>Unit validator gate (T6): a synthetic unit failing the closed
 *       rules is rejected before staging (the staging tree is never
 *       created).</li>
 *   <li>Direct-write rejection: the live set changing during staging is
 *       E6005 and publishes none.</li>
 *   <li>Combined flow (deps T1/T2/T5/T6/T10): invocation → plan (planId,
 *       artifactOwner, constructionEntry) → synthetic units → completed
 *       ABI records → staged/validated publication succeeds, and faulting
 *       any dependency in turn fails the suite.</li>
 *   <li>Structural guards and invariants: {@code ModuleEmissionResult}
 *       failure shape, artifact path grammar, duplicate staged names, the
 *       planId-only stage tree naming (the nonce never enters the plan
 *       record).</li>
 * </ol>
 */
public class StagingPublicationTest {

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

    // =========================================================================
    // Shared fixtures
    // =========================================================================

    private static final ModuleId MOD_A = new ModuleId("a");
    private static final ModuleId MOD_B = new ModuleId("b");
    private static final ModuleId MOD_C = new ModuleId("c");
    private static final ClassId POINT = new ClassId("a", "Point");
    private static final ClassFactoryId POINT_FACTORY = new ClassFactoryId(3);
    private static final FieldInterface FIELD_X =
        new FieldInterface("x", "int", false, false, false);

    private static final RuntimeDescriptor DESC_ADD = new RuntimeDescriptor.Func(
        List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
        RuntimeDescriptor.Int.INSTANCE);
    private static final RuntimeDescriptor DESC_TICK = new RuntimeDescriptor.Func(
        List.of(), RuntimeDescriptor.Int.INSTANCE, true);
    private static final RuntimeDescriptor DESC_POINT = new RuntimeDescriptor.Class(POINT);

    private static CompilerInvocation commonShadowInvocation() {
        return CompilerProfileProvider.resolveCommonShadow(ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    /** The A/B index: a legacy dependency a (exports add/tick/Point) imported by shared b. */
    private static ProjectInterfaceIndex abIndex() {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>();
        modules.put(MOD_A, new ExternalModuleInterface(MOD_A, ExternalModuleKind.IMPLEMENTATION,
            List.of(),
            List.of(new ExportInterface("add", "(int, int) => int"),
                new ExportInterface("tick", "async () => int"),
                new ExportInterface("Point", "@a/Point")),
            List.of(new ClassInterface(POINT, List.of(FIELD_X), POINT_FACTORY)),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        modules.put(MOD_B, new ExternalModuleInterface(MOD_B, ExternalModuleKind.IMPLEMENTATION,
            List.of(new ResolvedImport("a", "./a", MOD_A, ExternalModuleKind.IMPLEMENTATION)),
            List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    /** The standalone B/C index: two SHARED-routed modules with no imports (no mixed edges). */
    private static ProjectInterfaceIndex bcIndex() {
        Map<ModuleId, ExternalModuleInterface> modules = new LinkedHashMap<>();
        modules.put(MOD_B, new ExternalModuleInterface(MOD_B, ExternalModuleKind.IMPLEMENTATION,
            List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES));
        modules.put(MOD_C, new ExternalModuleInterface(MOD_C, ExternalModuleKind.IMPLEMENTATION,
            List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, modules);
    }

    private static ModuleRoutePlan plan(CompilerInvocation invocation,
                                        ProjectInterfaceIndex index,
                                        Map<ModuleId, ModuleRoute> entries,
                                        Set<ModuleId> shadowModules,
                                        List<TargetModuleAbi> abiEdges) {
        String hash = MigrationPlanner.deriveInvocationHash(
            invocation, index.interfaceIndexDigest(), Target.LUAJIT);
        return new ModuleRoutePlan(Target.LUAJIT, entries, shadowModules, abiEdges, hash,
            MigrationPlanner.planIdFor(hash));
    }

    /** The A/B plan: a LEGACY, b SHARED, one completed plan-time record for a. */
    private static ModuleRoutePlan abPlan(CompilerInvocation invocation,
                                          ProjectInterfaceIndex index,
                                          TargetModuleAbi abiA) {
        Map<ModuleId, ModuleRoute> entries = new LinkedHashMap<>();
        entries.put(MOD_A, ModuleRoute.LEGACY);
        entries.put(MOD_B, ModuleRoute.SHARED);
        return plan(invocation, index, entries, Set.of(MOD_B), List.of(abiA));
    }

    /** The B/C plan: both modules SHARED (shadow), no mixed edges. */
    private static ModuleRoutePlan bcPlan(CompilerInvocation invocation,
                                          ProjectInterfaceIndex index) {
        Map<ModuleId, ModuleRoute> entries = new LinkedHashMap<>();
        entries.put(MOD_B, ModuleRoute.SHARED);
        entries.put(MOD_C, ModuleRoute.SHARED);
        return plan(invocation, index, entries, Set.of(MOD_B, MOD_C), List.of());
    }

    /** The complete stage-time record for legacy dependency a (foundation F5/F6). */
    private static TargetModuleAbi completeAbiA(String loadKey) {
        return abiRecord(MOD_A, Target.LUAJIT, ArtifactOwner.RETAINED_LUAJIT,
            SemanticProfile.DEAL_V1_2_INT32,
            Map.of(POINT, POINT_FACTORY),
            Map.of(POINT, List.of(FIELD_X)),
            Map.of("add", DESC_ADD, "tick", DESC_TICK, "Point", DESC_POINT),
            loadKey, "a_init",
            Map.of("add", new FunctionSignatureAbi("add_wrapper", "(int, int) => int")),
            Map.of("add", new SyncInvocationEntry.AbiWrapper("add_wrapper"),
                "tick", new SyncInvocationEntry.AbiWrapper("tick_wrapper"),
                "Point", new SyncInvocationEntry.AbiWrapper("point_wrapper")),
            List.of(new ExternalAsyncLink(MOD_A, "tick",
                new AsyncTokenId.Canonical(7, AsyncTokenOwner.DEAL_BODY_TASK))));
    }

    /** The emitted SHARED-owner manifest of a shared module with no exports/classes. */
    private static TargetModuleAbi emittedManifest(ModuleId module, String loadKey) {
        return abiRecord(module, Target.LUAJIT, ArtifactOwner.SHARED,
            SemanticProfile.DEAL_V1_2_INT32,
            Map.of(), Map.of(), Map.of(),
            loadKey, module.path() + "_init", Map.of(), Map.of(), List.of());
    }

    private static TargetModuleAbi abiRecord(
            ModuleId module, Target target, ArtifactOwner owner, SemanticProfile profile,
            Map<ClassId, ClassFactoryId> factories, Map<ClassId, List<FieldInterface>> layouts,
            Map<String, RuntimeDescriptor> descriptors, String loadKey, String initEntry,
            Map<String, FunctionSignatureAbi> wrappers,
            Map<String, SyncInvocationEntry> syncEntries,
            List<ExternalAsyncLink> links) {
        return new TargetModuleAbi(module, target, owner, profile, factories, layouts,
            descriptors, loadKey, initEntry, wrappers, syncEntries, links);
    }

    /** A synthetic unit passing the closed validator rules (T2/T6 surface). */
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

    private static ModuleEmissionResult successfulResult(LoweredModuleUnit unit,
                                                         List<StagedArtifact> artifacts,
                                                         TargetModuleAbi manifest) {
        return new ModuleEmissionResult(artifacts, List.of(), manifest,
            BoundaryRealizationReport.empty(), new OperationContractManifest(unit));
    }

    private static ModuleEmissionResult failedResult(LoweredModuleUnit unit, String message) {
        return new ModuleEmissionResult(List.of(),
            List.of(CompilerDiagnostic.error(DiagnosticCode.E6000, message,
                DiagnosticRange.synthetic("test"))),
            null, BoundaryRealizationReport.empty(),
            unit == null ? null : new OperationContractManifest(unit));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Path tempRoot() throws IOException {
        return Files.createTempDirectory("deal-stage-test").resolve("live");
    }

    private static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
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
            what + ": no staging/retired siblings remain (stage=" + stage + ", retired="
                + retired + ")");
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

    private static void assertTreeEquals(Path tree, List<StagedArtifact> expected, String what)
            throws IOException {
        Map<String, byte[]> actual = readTree(tree);
        check(actual.size() == expected.size(),
            what + ": the published set has exactly " + expected.size() + " artifact(s), got "
                + actual.size());
        for (StagedArtifact artifact : expected) {
            byte[] got = actual.get(artifact.name());
            check(got != null && java.util.Arrays.equals(got, artifact.bytes()),
                what + ": artifact '" + artifact.name() + "' is byte-identical to the staged "
                    + "content");
        }
    }

    /** The T5 payload assertion: E6005, BACKEND_LOWERING, capability MODULES, the full detail. */
    private static void assertE6005(CompilerDiagnostic diagnostic, String rule, String module,
                                    SemanticProfile profile, String origin) {
        check(diagnostic != null, rule + " produces a diagnostic");
        if (diagnostic == null) {
            return;
        }
        check("E6005".equals(diagnostic.code()), rule + " code is E6005");
        check(diagnostic.diagnosticCode() == DiagnosticCode.E6005, rule + " diagnosticCode is E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            rule + " phase is BACKEND_LOWERING");
        check("error".equals(diagnostic.severity()), rule + " severity is error");
        String message = diagnostic.message();
        check(message.startsWith("Common semantic lowering failed"),
            rule + " instantiates the registry-owned E6005 template (T5)");
        check(message.contains("module '" + module + "'"),
            rule + " names the module; got \"" + message + "\"");
        check(message.contains("capability MODULES"),
            rule + " carries capability MODULES (foundation F6)");
        String[] byRule = message.split("validatorRule ");
        check(byRule.length == 2 && byRule[1].startsWith(rule + ","),
            rule + " is named as the validator rule (exactly one rule per fixture); got \""
                + message + "\"");
        check(message.contains("semanticProfile " + profile),
            rule + " carries the project semantic profile");
        check(message.contains("irVersion deal.semantic-ir/1"),
            rule + " carries the pinned irVersion");
        check(message.contains("origin " + origin),
            rule + " names the producing component '" + origin + "'");
    }

    // =========================================================================
    // 1. TargetAbiValidator — complete-record and mismatch classes
    // =========================================================================

    private static void testValidatorCompleteRecordPasses() {
        System.out.println("-- TargetAbiValidator: complete record passes --");
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = abIndex();
        TargetModuleAbi abi = completeAbiA("a/artifact.lua");
        ModuleRoutePlan plan = abPlan(invocation, index, abi);
        StagedArtifactSet stagedSet = new StagedArtifactSet(List.of(
            new StagedArtifact("a/artifact.lua", bytes("a"))));
        check(TargetAbiValidator.validate(stagedSet, plan, List.of(abi), index,
                invocation.semanticProfile()).isEmpty(),
            "the complete ABI record validates (all planner-owned, DescriptorService-owned, "
                + "and emission-owned fields present and consistent)");
    }

    private static void testValidatorIncompleteRecordClasses() {
        System.out.println("-- TargetAbiValidator: one case per incomplete-record class --");
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = abIndex();
        StagedArtifactSet stagedSet = new StagedArtifactSet(List.of(
            new StagedArtifact("a/artifact.lua", bytes("a"))));

        // Missing load key.
        TargetModuleAbi missingLoadKey = completeAbiA("a/artifact.lua");
        missingLoadKey = abiRecord(missingLoadKey.moduleId(), missingLoadKey.target(),
            missingLoadKey.artifactOwner(), missingLoadKey.semanticProfile(),
            missingLoadKey.classFactoryAbi(), missingLoadKey.classLayoutAbi(),
            missingLoadKey.exportedDescriptors(), null, missingLoadKey.initializationEntry(),
            missingLoadKey.functionWrapperAbi(), missingLoadKey.syncInvocationEntries(),
            missingLoadKey.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, missingLoadKey),
            List.of(missingLoadKey), index, invocation,
            TargetAbiValidator.ABI_MISSING_LOAD_KEY, "a");

        // Load key naming no staged artifact.
        TargetModuleAbi unresolved = completeAbiA("a/not-staged.lua");
        assertRejected(stagedSet, abPlan(invocation, index, unresolved),
            List.of(unresolved), index, invocation,
            TargetAbiValidator.ABI_LOAD_KEY_UNRESOLVED, "a");

        // Missing initialization entry.
        TargetModuleAbi missingInit = completeAbiA("a/artifact.lua");
        missingInit = abiRecord(missingInit.moduleId(), missingInit.target(),
            missingInit.artifactOwner(), missingInit.semanticProfile(),
            missingInit.classFactoryAbi(), missingInit.classLayoutAbi(),
            missingInit.exportedDescriptors(), missingInit.loadKey(), null,
            missingInit.functionWrapperAbi(), missingInit.syncInvocationEntries(),
            missingInit.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, missingInit),
            List.of(missingInit), index, invocation,
            TargetAbiValidator.ABI_MISSING_INITIALIZATION_ENTRY, "a");

        // Missing exported descriptors (empty at stage time = incomplete).
        TargetModuleAbi missingDescriptors = completeAbiA("a/artifact.lua");
        missingDescriptors = abiRecord(missingDescriptors.moduleId(),
            missingDescriptors.target(), missingDescriptors.artifactOwner(),
            missingDescriptors.semanticProfile(), missingDescriptors.classFactoryAbi(),
            missingDescriptors.classLayoutAbi(), Map.of(), missingDescriptors.loadKey(),
            missingDescriptors.initializationEntry(), missingDescriptors.functionWrapperAbi(),
            missingDescriptors.syncInvocationEntries(), missingDescriptors.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, missingDescriptors),
            List.of(missingDescriptors), index, invocation,
            TargetAbiValidator.ABI_DESCRIPTOR_MISMATCH, "a");

        // Missing wrapper ABI for the imported function export.
        TargetModuleAbi missingWrapper = completeAbiA("a/artifact.lua");
        missingWrapper = abiRecord(missingWrapper.moduleId(), missingWrapper.target(),
            missingWrapper.artifactOwner(), missingWrapper.semanticProfile(),
            missingWrapper.classFactoryAbi(), missingWrapper.classLayoutAbi(),
            missingWrapper.exportedDescriptors(), missingWrapper.loadKey(),
            missingWrapper.initializationEntry(), Map.of(),
            missingWrapper.syncInvocationEntries(), missingWrapper.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, missingWrapper),
            List.of(missingWrapper), index, invocation,
            TargetAbiValidator.ABI_MISSING_FUNCTION_WRAPPER, "a");

        // Missing sync-invocation entry (drop "add").
        TargetModuleAbi missingSync = completeAbiA("a/artifact.lua");
        missingSync = abiRecord(missingSync.moduleId(), missingSync.target(),
            missingSync.artifactOwner(), missingSync.semanticProfile(),
            missingSync.classFactoryAbi(), missingSync.classLayoutAbi(),
            missingSync.exportedDescriptors(), missingSync.loadKey(),
            missingSync.initializationEntry(), missingSync.functionWrapperAbi(),
            Map.of("tick", new SyncInvocationEntry.AbiWrapper("tick_wrapper"),
                "Point", new SyncInvocationEntry.AbiWrapper("point_wrapper")),
            missingSync.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, missingSync),
            List.of(missingSync), index, invocation,
            TargetAbiValidator.ABI_MISSING_SYNC_INVOCATION_ENTRY, "a");

        // Missing async-linkage record.
        TargetModuleAbi missingLink = completeAbiA("a/artifact.lua");
        missingLink = abiRecord(missingLink.moduleId(), missingLink.target(),
            missingLink.artifactOwner(), missingLink.semanticProfile(),
            missingLink.classFactoryAbi(), missingLink.classLayoutAbi(),
            missingLink.exportedDescriptors(), missingLink.loadKey(),
            missingLink.initializationEntry(), missingLink.functionWrapperAbi(),
            missingLink.syncInvocationEntries(), List.of());
        assertRejected(stagedSet, abPlan(invocation, index, missingLink),
            List.of(missingLink), index, invocation,
            TargetAbiValidator.ABI_MISSING_ASYNC_LINKAGE, "a");
    }

    private static void testValidatorMismatchClasses() {
        System.out.println("-- TargetAbiValidator: one case per mismatch class --");
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = abIndex();
        StagedArtifactSet stagedSet = new StagedArtifactSet(List.of(
            new StagedArtifact("a/artifact.lua", bytes("a"))));

        // Descriptors contradicting the index (wrong key set).
        TargetModuleAbi descriptorMismatch = completeAbiA("a/artifact.lua");
        descriptorMismatch = abiRecord(descriptorMismatch.moduleId(),
            descriptorMismatch.target(), descriptorMismatch.artifactOwner(),
            descriptorMismatch.semanticProfile(), descriptorMismatch.classFactoryAbi(),
            descriptorMismatch.classLayoutAbi(),
            Map.of("add", DESC_ADD, "tick", DESC_TICK), descriptorMismatch.loadKey(),
            descriptorMismatch.initializationEntry(), descriptorMismatch.functionWrapperAbi(),
            descriptorMismatch.syncInvocationEntries(), descriptorMismatch.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, descriptorMismatch),
            List.of(descriptorMismatch), index, invocation,
            TargetAbiValidator.ABI_DESCRIPTOR_MISMATCH, "a");

        // constructionEntry != the T8-derived id.
        TargetModuleAbi factoryMismatch = completeAbiA("a/artifact.lua");
        factoryMismatch = abiRecord(factoryMismatch.moduleId(), factoryMismatch.target(),
            factoryMismatch.artifactOwner(), factoryMismatch.semanticProfile(),
            Map.of(POINT, new ClassFactoryId(99)), factoryMismatch.classLayoutAbi(),
            factoryMismatch.exportedDescriptors(), factoryMismatch.loadKey(),
            factoryMismatch.initializationEntry(), factoryMismatch.functionWrapperAbi(),
            factoryMismatch.syncInvocationEntries(), factoryMismatch.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, factoryMismatch),
            List.of(factoryMismatch), index, invocation,
            TargetAbiValidator.ABI_CLASS_FACTORY_MISMATCH, "a");

        // Class layout contradicting the index (field list differs).
        TargetModuleAbi layoutMismatch = completeAbiA("a/artifact.lua");
        layoutMismatch = abiRecord(layoutMismatch.moduleId(), layoutMismatch.target(),
            layoutMismatch.artifactOwner(), layoutMismatch.semanticProfile(),
            layoutMismatch.classFactoryAbi(),
            Map.of(POINT, List.of(new FieldInterface("y", "string", false, false, false))),
            layoutMismatch.exportedDescriptors(), layoutMismatch.loadKey(),
            layoutMismatch.initializationEntry(), layoutMismatch.functionWrapperAbi(),
            layoutMismatch.syncInvocationEntries(), layoutMismatch.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, layoutMismatch),
            List.of(layoutMismatch), index, invocation,
            TargetAbiValidator.ABI_CLASS_LAYOUT_MISMATCH, "a");

        // artifactOwner != the route entry (SHARED owner for a LEGACY-routed module).
        TargetModuleAbi ownerMismatch = completeAbiA("a/artifact.lua");
        ownerMismatch = abiRecord(ownerMismatch.moduleId(), ownerMismatch.target(),
            ArtifactOwner.SHARED, ownerMismatch.semanticProfile(),
            ownerMismatch.classFactoryAbi(), ownerMismatch.classLayoutAbi(),
            ownerMismatch.exportedDescriptors(), ownerMismatch.loadKey(),
            ownerMismatch.initializationEntry(), ownerMismatch.functionWrapperAbi(),
            ownerMismatch.syncInvocationEntries(), ownerMismatch.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, ownerMismatch),
            List.of(ownerMismatch), index, invocation,
            TargetAbiValidator.ABI_OWNER_ROUTE_MISMATCH, "a");

        // semanticProfile != the project profile.
        TargetModuleAbi profileMismatch = completeAbiA("a/artifact.lua");
        profileMismatch = abiRecord(profileMismatch.moduleId(), profileMismatch.target(),
            profileMismatch.artifactOwner(), SemanticProfile.LEGACY_SAFE_INT,
            profileMismatch.classFactoryAbi(), profileMismatch.classLayoutAbi(),
            profileMismatch.exportedDescriptors(), profileMismatch.loadKey(),
            profileMismatch.initializationEntry(), profileMismatch.functionWrapperAbi(),
            profileMismatch.syncInvocationEntries(), profileMismatch.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, profileMismatch),
            List.of(profileMismatch), index, invocation,
            TargetAbiValidator.ABI_PROFILE_MISMATCH, "a");

        // Async-linkage inconsistency (exportName is not an async export).
        TargetModuleAbi linkMismatch = completeAbiA("a/artifact.lua");
        linkMismatch = abiRecord(linkMismatch.moduleId(), linkMismatch.target(),
            linkMismatch.artifactOwner(), linkMismatch.semanticProfile(),
            linkMismatch.classFactoryAbi(), linkMismatch.classLayoutAbi(),
            linkMismatch.exportedDescriptors(), linkMismatch.loadKey(),
            linkMismatch.initializationEntry(), linkMismatch.functionWrapperAbi(),
            linkMismatch.syncInvocationEntries(),
            List.of(new ExternalAsyncLink(MOD_A, "add",
                new AsyncTokenId.Canonical(7, AsyncTokenOwner.DEAL_BODY_TASK))));
        assertRejected(stagedSet, abPlan(invocation, index, linkMismatch),
            List.of(linkMismatch), index, invocation,
            TargetAbiValidator.ABI_ASYNC_LINKAGE_MISMATCH, "a");

        // Wrapper entry naming a non-export.
        TargetModuleAbi wrapperExtra = completeAbiA("a/artifact.lua");
        wrapperExtra = abiRecord(wrapperExtra.moduleId(), wrapperExtra.target(),
            wrapperExtra.artifactOwner(), wrapperExtra.semanticProfile(),
            wrapperExtra.classFactoryAbi(), wrapperExtra.classLayoutAbi(),
            wrapperExtra.exportedDescriptors(), wrapperExtra.loadKey(),
            wrapperExtra.initializationEntry(),
            Map.of("add", new FunctionSignatureAbi("add_wrapper", "(int, int) => int"),
                "ghost", new FunctionSignatureAbi("ghost_wrapper", "() => null")),
            wrapperExtra.syncInvocationEntries(), wrapperExtra.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, wrapperExtra),
            List.of(wrapperExtra), index, invocation,
            TargetAbiValidator.ABI_WRAPPER_CONTRADICTS_INDEX, "a");

        // Sync-invocation entry naming a non-export.
        TargetModuleAbi syncExtra = completeAbiA("a/artifact.lua");
        syncExtra = abiRecord(syncExtra.moduleId(), syncExtra.target(),
            syncExtra.artifactOwner(), syncExtra.semanticProfile(),
            syncExtra.classFactoryAbi(), syncExtra.classLayoutAbi(),
            syncExtra.exportedDescriptors(), syncExtra.loadKey(),
            syncExtra.initializationEntry(), syncExtra.functionWrapperAbi(),
            Map.of("add", new SyncInvocationEntry.AbiWrapper("add_wrapper"),
                "tick", new SyncInvocationEntry.AbiWrapper("tick_wrapper"),
                "Point", new SyncInvocationEntry.AbiWrapper("point_wrapper"),
                "ghost", new SyncInvocationEntry.AbiWrapper("ghost_wrapper")),
            syncExtra.asyncLinkageRecords());
        assertRejected(stagedSet, abPlan(invocation, index, syncExtra),
            List.of(syncExtra), index, invocation,
            TargetAbiValidator.ABI_SYNC_ENTRY_CONTRADICTS_INDEX, "a");

        // The mixed-edge coverage gate: b is SHARED and imports LEGACY a, but no record.
        TargetModuleAbi abi = completeAbiA("a/artifact.lua");
        Map<ModuleId, ModuleRoute> entries = new LinkedHashMap<>();
        entries.put(MOD_A, ModuleRoute.LEGACY);
        entries.put(MOD_B, ModuleRoute.SHARED);
        ModuleRoutePlan planWithoutEdge = plan(invocation, index, entries, Set.of(MOD_B),
            List.of());
        assertRejected(stagedSet, planWithoutEdge, List.of(), index, invocation,
            TargetAbiValidator.ABI_EDGE_MISSING, "b");

        // A record whose module is not in the plan / not in the index.
        TargetModuleAbi ghostRecord = abiRecord(new ModuleId("ghost"), Target.LUAJIT,
            ArtifactOwner.RETAINED_LUAJIT, SemanticProfile.DEAL_V1_2_INT32,
            Map.of(), Map.of(), Map.of(), "ghost.lua", "ghost_init", Map.of(), Map.of(),
            List.of());
        assertRejected(stagedSet, abPlan(invocation, index, abi),
            List.of(abi, ghostRecord),
            index, invocation, TargetAbiValidator.ABI_MODULE_NOT_IN_INDEX, "ghost");
    }

    private static void assertRejected(StagedArtifactSet stagedSet, ModuleRoutePlan plan,
                                       List<TargetModuleAbi> abiEdges,
                                       ProjectInterfaceIndex index,
                                       CompilerInvocation invocation,
                                       String rule, String module) {
        var diagnostic = TargetAbiValidator.validate(stagedSet, plan, abiEdges, index,
            invocation.semanticProfile());
        check(diagnostic.isPresent(), rule + " is rejected");
        diagnostic.ifPresent(d -> assertE6005(d, rule, module,
            invocation.semanticProfile(), "TargetAbiValidator"));
    }

    // =========================================================================
    // 2. Forced-failure stage test
    // =========================================================================

    private static void testForcedFailureLeavesPriorUntouched() throws IOException {
        System.out.println("-- Forced-failure stage test --");
        Path root = tempRoot();
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        try {
            Files.createDirectories(root);
            Files.write(root.resolve("prior.lua"), bytes("PRIOR"));

            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            List<StagedArtifact> artifactsB = List.of(new StagedArtifact("b/x.lua", bytes("B")));
            List<StagedArtifact> artifactsC = List.of(new StagedArtifact("c/y.lua", bytes("C")));

            List<ModuleEmissionResult> results = List.of(
                successfulResult(unitB, artifactsB, emittedManifest(MOD_B, "b/x.lua")),
                failedResult(unitC, "injected mid-stage emission failure"));

            // Record the pinned seam steps to prove the failure was mid-stage.
            List<String> steps = new ArrayList<>();
            ProjectArtifactStager.installPublishFault(step -> steps.add(step));
            ProjectArtifactStager.PublicationOutcome outcome;
            try {
                outcome = ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, results, List.of(), index);
            } finally {
                ProjectArtifactStager.clearPublishFault();
            }

            check(!outcome.published(), "the injected failure publishes nothing");
            check(steps.contains(ProjectArtifactStager.FAULT_STEP_STAGING_BEGAN)
                    && !steps.contains(ProjectArtifactStager.FAULT_STEP_POST_STAGING),
                "the failure was discovered mid-stage (staging began, publish never started); "
                    + "steps=" + steps);
            check(outcome.diagnostics().size() == 1
                    && outcome.diagnostics().get(0).message()
                        .contains("injected mid-stage emission failure"),
                "the injected emission failure is reported verbatim (emission diagnostics, "
                    + "never a fallback)");
            check(java.util.Arrays.equals(
                    Files.readAllBytes(root.resolve("prior.lua")), bytes("PRIOR")),
                "the prior live set is byte-untouched");
            check(readTree(root).size() == 1,
                "nothing was published into the live set (no partial module artifact)");
            checkNoSiblings(root, "forced-failure test");
            check(!Files.exists(root.resolveSibling("c")),
                "the failed module has no partial stage directory");
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 3. Fresh-root branch
    // =========================================================================

    private static void testFreshRootSuccess() throws IOException {
        System.out.println("-- Fresh-root publication --");
        Path root = tempRoot();
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        try {
            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            List<StagedArtifact> artifacts = List.of(
                new StagedArtifact("b/x.lua", bytes("B")),
                new StagedArtifact("c/y.lua", bytes("C")));
            ModuleEmissionResult resultB = successfulResult(unitB,
                List.of(new StagedArtifact("b/x.lua", bytes("B"))),
                emittedManifest(MOD_B, "b/x.lua"));
            ModuleEmissionResult resultC = successfulResult(unitC,
                List.of(new StagedArtifact("c/y.lua", bytes("C"))),
                emittedManifest(MOD_C, "c/y.lua"));

            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, List.of(resultB, resultC), List.of(), index);

            check(outcome.published(), "the fresh-root publication succeeds");
            check(outcome.stageTreeName() != null
                    && outcome.stageTreeName().contains(plan.planId())
                    && outcome.stageTreeName().contains(
                        ProjectArtifactStager.STAGE_TREE_MARKER),
                "the staging tree name carries the planId from T10's plan record");
            assertTreeEquals(root, artifacts, "fresh-root");
            check(siblingTrees(root, ProjectArtifactStager.RETIRED_TREE_MARKER).isEmpty(),
                "the retire step was a no-op (no retired sibling ever existed)");
            checkNoSiblings(root, "fresh-root success");
            check(outcome.liveSet().equals(artifacts),
                "the outcome records the published set");
        } finally {
            deleteTree(root.getParent());
        }
    }

    private static void testFreshRootFailingSingleMove() throws IOException {
        System.out.println("-- Fresh-root publication with a failing single move --");
        Path root = tempRoot();
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        try {
            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            ModuleEmissionResult resultB = successfulResult(unitB,
                List.of(new StagedArtifact("b/x.lua", bytes("B"))),
                emittedManifest(MOD_B, "b/x.lua"));
            ModuleEmissionResult resultC = successfulResult(unitC,
                List.of(new StagedArtifact("c/y.lua", bytes("C"))),
                emittedManifest(MOD_C, "c/y.lua"));

            ProjectArtifactStager.installPublishFault(step -> {
                if (ProjectArtifactStager.FAULT_STEP_STAGE_TO_LIVE.equals(step)) {
                    throw new IOException("injected fresh-root move failure");
                }
            });
            ProjectArtifactStager.PublicationOutcome outcome;
            try {
                outcome = ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, List.of(resultB, resultC), List.of(), index);
            } finally {
                ProjectArtifactStager.clearPublishFault();
            }

            check(!outcome.published(), "the failing single move publishes nothing");
            check(!Files.exists(root), "the root is left without a live set");
            checkNoSiblings(root, "failed fresh-root");
            check(outcome.diagnostics().size() == 1
                    && outcome.diagnostics().get(0).message().contains(
                        "validatorRule " + ProjectArtifactStager.STAGE_PUBLISH_MOVE_FAILED),
                "the move failure is E6005 STAGE_PUBLISH_MOVE_FAILED");
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 4. Restore-on-failure (prior set)
    // =========================================================================

    private static void testPriorSetRestoreOnFailedSecondMove() throws IOException {
        System.out.println("-- Prior-set publish with a failing second move restores --");
        Path root = tempRoot();
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        try {
            Files.createDirectories(root);
            Files.write(root.resolve("prior.lua"), bytes("PRIOR"));

            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            ModuleEmissionResult resultB = successfulResult(unitB,
                List.of(new StagedArtifact("b/x.lua", bytes("B"))),
                emittedManifest(MOD_B, "b/x.lua"));
            ModuleEmissionResult resultC = successfulResult(unitC,
                List.of(new StagedArtifact("c/y.lua", bytes("C"))),
                emittedManifest(MOD_C, "c/y.lua"));

            ProjectArtifactStager.installPublishFault(step -> {
                if (ProjectArtifactStager.FAULT_STEP_STAGE_TO_LIVE.equals(step)) {
                    throw new IOException("injected second-move failure");
                }
            });
            ProjectArtifactStager.PublicationOutcome outcome;
            try {
                outcome = ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, List.of(resultB, resultC), List.of(), index);
            } finally {
                ProjectArtifactStager.clearPublishFault();
            }

            check(!outcome.published(), "the failing second move publishes nothing");
            check(java.util.Arrays.equals(
                    Files.readAllBytes(root.resolve("prior.lua")), bytes("PRIOR")),
                "retired -> live restore leaves the prior set byte-identical");
            check(readTree(root).size() == 1,
                "the prior set is restored untouched (no blend, no new artifact)");
            checkNoSiblings(root, "restore-on-failure");
            check(outcome.diagnostics().get(0).message().contains(
                    "validatorRule " + ProjectArtifactStager.STAGE_PUBLISH_MOVE_FAILED),
                "the second-move failure is reported");
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 5. Direct-write rejection
    // =========================================================================

    private static void testDirectWriteConflict() throws IOException {
        System.out.println("-- Direct-write rejection --");
        Path root = tempRoot();
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        try {
            Files.createDirectories(root);
            Files.write(root.resolve("prior.lua"), bytes("PRIOR"));

            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            ModuleEmissionResult resultB = successfulResult(unitB,
                List.of(new StagedArtifact("b/x.lua", bytes("B"))),
                emittedManifest(MOD_B, "b/x.lua"));
            ModuleEmissionResult resultC = successfulResult(unitC,
                List.of(new StagedArtifact("c/y.lua", bytes("C"))),
                emittedManifest(MOD_C, "c/y.lua"));

            ProjectArtifactStager.installPublishFault(step -> {
                if (ProjectArtifactStager.FAULT_STEP_POST_STAGING.equals(step)) {
                    // A direct write into the live root while the stager stages.
                    Files.write(root.resolve("intruder.lua"), bytes("DIRECT"));
                }
            });
            ProjectArtifactStager.PublicationOutcome outcome;
            try {
                outcome = ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, List.of(resultB, resultC), List.of(), index);
            } finally {
                ProjectArtifactStager.clearPublishFault();
            }

            check(!outcome.published(),
                "an invocation that both stages and direct-writes the same root is rejected");
            check(outcome.diagnostics().get(0).message().contains(
                    "validatorRule " + ProjectArtifactStager.STAGE_DIRECT_WRITE_CONFLICT),
                "the drift is E6005 STAGE_DIRECT_WRITE_CONFLICT");
            check(java.util.Arrays.equals(
                    Files.readAllBytes(root.resolve("prior.lua")), bytes("PRIOR"))
                    && java.util.Arrays.equals(
                        Files.readAllBytes(root.resolve("intruder.lua")), bytes("DIRECT")),
                "the stager touched neither the prior set nor the direct write");
            checkNoSiblings(root, "direct-write rejection");
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 6. Torn-publish and stale-tree recovery
    // =========================================================================

    private static void testTornPublishRecovery() throws IOException {
        System.out.println("-- Torn-publish recovery --");
        Path root = tempRoot();
        try {
            Path parent = root.getParent();
            Path retired = parent.resolve(root.getFileName()
                + ProjectArtifactStager.RETIRED_TREE_MARKER + "plan-dead-1-nonce1");
            Path deadStage = parent.resolve(root.getFileName()
                + ProjectArtifactStager.STAGE_TREE_MARKER + "plan-dead-1-nonce2");
            Files.createDirectories(retired);
            Files.write(retired.resolve("old.lua"), bytes("OLD"));
            Files.createDirectories(deadStage);
            Files.write(deadStage.resolve("junk.lua"), bytes("JUNK"));

            // Recovery runs only while holding the per-root lock.
            boolean lockGuard = false;
            try {
                ProjectArtifactStager.recoverStaleSiblings(root, null);
            } catch (IllegalStateException expected) {
                lockGuard = true;
            }
            check(lockGuard,
                "crash recovery refuses to run without the per-root lock (fail closed)");

            ReentrantLock lock = ProjectArtifactStager.rootLock(root);
            lock.lock();
            try {
                ProjectArtifactStager.recoverStaleSiblings(root, null);
            } finally {
                lock.unlock();
            }

            check(Files.exists(root.resolve("old.lua"))
                    && java.util.Arrays.equals(
                        Files.readAllBytes(root.resolve("old.lua")), bytes("OLD")),
                "retired -> live restore (torn publish after the first move)");
            check(!Files.exists(retired), "the retired tree was moved, not copied");
            check(!Files.exists(deadStage),
                "the dead invocation's stage tree is removed at the same publish start");
            check(siblingTrees(root, ProjectArtifactStager.STAGE_TREE_MARKER).isEmpty()
                    && siblingTrees(root, ProjectArtifactStager.RETIRED_TREE_MARKER).isEmpty(),
                "no stale sibling survives the recovery");
        } finally {
            deleteTree(root.getParent());
        }
    }

    private static void testStaleStageOnlyRecovery() throws IOException {
        System.out.println("-- Stale-stage-only recovery --");
        Path root = tempRoot();
        try {
            Path parent = root.getParent();
            Path stale = parent.resolve(root.getFileName()
                + ProjectArtifactStager.STAGE_TREE_MARKER + "plan-dead-2-nonce3");
            Files.createDirectories(stale);
            Files.write(stale.resolve("junk.lua"), bytes("JUNK"));

            ReentrantLock lock = ProjectArtifactStager.rootLock(root);
            lock.lock();
            try {
                ProjectArtifactStager.recoverStaleSiblings(root, null);
            } finally {
                lock.unlock();
            }

            check(!Files.exists(root), "live stays absent (no retired set exists)");
            check(!Files.exists(stale), "the stale stage tree is removed");
        } finally {
            deleteTree(root.getParent());
        }
    }

    private static void testRecoveryKeepsCurrentTree() throws IOException {
        System.out.println("-- Recovery never removes the current invocation's live tree --");
        Path root = tempRoot();
        try {
            Path parent = root.getParent();
            Path current = parent.resolve(root.getFileName()
                + ProjectArtifactStager.STAGE_TREE_MARKER + "plan-live-3-nonce4");
            Path dead = parent.resolve(root.getFileName()
                + ProjectArtifactStager.STAGE_TREE_MARKER + "plan-dead-3-nonce5");
            Files.createDirectories(current);
            Files.write(current.resolve("ours.lua"), bytes("OURS"));
            Files.createDirectories(dead);
            Files.write(dead.resolve("dead.lua"), bytes("DEAD"));

            ReentrantLock lock = ProjectArtifactStager.rootLock(root);
            lock.lock();
            try {
                ProjectArtifactStager.recoverStaleSiblings(root, current);
            } finally {
                lock.unlock();
            }

            check(Files.exists(current.resolve("ours.lua")),
                "the current invocation's own staging tree is never removed");
            check(!Files.exists(dead), "only dead-invocation trees are removed");
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 7. Concurrent identical-invocation isolation
    // =========================================================================

    private static void testConcurrentIsolation() throws Exception {
        System.out.println("-- Concurrent identical invocations serialize per root --");
        Path root = tempRoot();
        try {
            CompilerInvocation invocation = commonShadowInvocation();
            ProjectInterfaceIndex index = bcIndex();
            ModuleRoutePlan plan = bcPlan(invocation, index);
            List<StagedArtifact> content = List.of(
                new StagedArtifact("b/x.lua", bytes("W")),
                new StagedArtifact("c/y.lua", bytes("W2")));
            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            ModuleEmissionResult resultB = successfulResult(unitB,
                List.of(new StagedArtifact("b/x.lua", bytes("W"))),
                emittedManifest(MOD_B, "b/x.lua"));
            ModuleEmissionResult resultC = successfulResult(unitC,
                List.of(new StagedArtifact("c/y.lua", bytes("W2"))),
                emittedManifest(MOD_C, "c/y.lua"));
            List<ModuleEmissionResult> results = List.of(resultB, resultC);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ProjectArtifactStager.PublicationOutcome> first =
                    pool.submit(() -> ProjectArtifactStager.stageValidatePublish(
                        root, invocation, plan, results, List.of(), index));
                Future<ProjectArtifactStager.PublicationOutcome> second =
                    pool.submit(() -> ProjectArtifactStager.stageValidatePublish(
                        root, invocation, plan, results, List.of(), index));
                ProjectArtifactStager.PublicationOutcome outcome1 = first.get(30, TimeUnit.SECONDS);
                ProjectArtifactStager.PublicationOutcome outcome2 = second.get(30, TimeUnit.SECONDS);

                check(outcome1.published() && outcome2.published(),
                    "both concurrent identical invocations complete");
                check(outcome1.stageTreeName() != null && outcome2.stageTreeName() != null
                        && !outcome1.stageTreeName().equals(outcome2.stageTreeName()),
                    "no shared staging tree at any time: per-invocation nonce names ("
                        + outcome1.stageTreeName() + " vs " + outcome2.stageTreeName() + ")");
                assertTreeEquals(root, content,
                    "after both complete, the live set equals the staged content");
                checkNoSiblings(root, "concurrent isolation");

                // Loser-side failure: one invocation fails, the winner's set stays.
                Path root2 = tempRoot();
                try {
                    ModuleEmissionResult failing = failedResult(unitC,
                        "injected loser-side emission failure");
                    Future<ProjectArtifactStager.PublicationOutcome> winner =
                        pool.submit(() -> ProjectArtifactStager.stageValidatePublish(
                            root2, invocation, plan, results, List.of(), index));
                    Future<ProjectArtifactStager.PublicationOutcome> loser =
                        pool.submit(() -> ProjectArtifactStager.stageValidatePublish(
                            root2, invocation, plan,
                            List.of(resultB, failing), List.of(), index));
                    ProjectArtifactStager.PublicationOutcome winnerOutcome =
                        winner.get(30, TimeUnit.SECONDS);
                    ProjectArtifactStager.PublicationOutcome loserOutcome =
                        loser.get(30, TimeUnit.SECONDS);

                    check(!loserOutcome.published(),
                        "a loser-side failure publishes none");
                    check(loserOutcome.diagnostics().size() == 1
                            && loserOutcome.diagnostics().get(0).message()
                                .contains("injected loser-side emission failure"),
                        "the loser reports its emission failure verbatim");
                    assertTreeEquals(root2, content,
                        "the winner's published set is untouched by the loser-side failure");
                    checkNoSiblings(root2, "loser-side failure");
                } finally {
                    deleteTree(root2.getParent());
                }

                // The loser blocks on the per-root lock until the winner completes.
                Path root3 = tempRoot();
                try {
                    ReentrantLock lock = ProjectArtifactStager.rootLock(root3);
                    lock.lock();
                    try {
                        Future<ProjectArtifactStager.PublicationOutcome> blocked =
                            pool.submit(() -> ProjectArtifactStager.stageValidatePublish(
                                root3, invocation, plan, results, List.of(), index));
                        Thread.sleep(300);
                        check(!blocked.isDone(),
                            "the blocked invocation waits on the per-root lock");
                        check(siblingTrees(root3,
                                ProjectArtifactStager.STAGE_TREE_MARKER).isEmpty(),
                            "no staging tree is created while the invocation is blocked");
                        lock.unlock();
                        ProjectArtifactStager.PublicationOutcome outcome =
                            blocked.get(30, TimeUnit.SECONDS);
                        check(outcome.published(),
                            "the invocation completes once the lock is released");
                        assertTreeEquals(root3, content,
                            "the serialized invocation publishes its own identical set");
                        checkNoSiblings(root3, "serialized invocation");
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } finally {
                    deleteTree(root3.getParent());
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 8. Unit validator gate (T6) before staging
    // =========================================================================

    private static void testInvalidUnitRejectedBeforeStaging() throws IOException {
        System.out.println("-- T6 validator gate rejects invalid IR before staging --");
        Path root = tempRoot();
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        try {
            Files.createDirectories(root);
            Files.write(root.resolve("prior.lua"), bytes("PRIOR"));

            // A unit failing the closed rules: interfaceHash != the index digest (R-PROFILE).
            LoweredModuleUnit invalidUnit = new LoweredModuleUnit(
                LoweredModuleUnit.FORMAT_VERSION, SemanticProfile.DEAL_V1_2_INT32, MOD_B,
                "wrong-interface-digest",
                LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                    invocation.capabilityRegistryHash()),
                Set.of(), Map.of(), Map.of(), Map.of(),
                new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
                List.of());
            ModuleEmissionResult invalid = successfulResult(invalidUnit,
                List.of(new StagedArtifact("b/x.lua", bytes("B"))),
                emittedManifest(MOD_B, "b/x.lua"));
            LoweredModuleUnit unitC = validUnit(MOD_C, index, invocation);
            ModuleEmissionResult validC = successfulResult(unitC,
                List.of(new StagedArtifact("c/y.lua", bytes("C"))),
                emittedManifest(MOD_C, "c/y.lua"));

            List<String> steps = new ArrayList<>();
            ProjectArtifactStager.installPublishFault(step -> steps.add(step));
            ProjectArtifactStager.PublicationOutcome outcome;
            try {
                outcome = ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, List.of(invalid, validC), List.of(), index);
            } finally {
                ProjectArtifactStager.clearPublishFault();
            }

            check(!outcome.published(), "the invalid unit is rejected");
            check(outcome.stageTreeName() == null
                    && !steps.contains(ProjectArtifactStager.FAULT_STEP_STAGING_BEGAN),
                "rejected before staging: the staging tree was never created; steps=" + steps);
            check(outcome.diagnostics().size() == 1,
                "exactly one rejection diagnostic");
            CompilerDiagnostic rejected = outcome.diagnostics().get(0);
            check("E6005".equals(rejected.code())
                    && rejected.message().contains("validatorRule "
                        + SemanticIrValidator.R_PROFILE)
                    && rejected.message().contains("module 'b'")
                    && rejected.message().contains("capability FOUNDATION_VALUES")
                    && rejected.message().contains("semanticProfile DEAL_V1_2_INT32")
                    && rejected.message().contains("irVersion deal.semantic-ir/1")
                    && rejected.message().contains("origin SemanticIrValidator R-PROFILE"),
                "the unit-gate rejection is the closed validator's E6005 (R-PROFILE, "
                    + "capability FOUNDATION_VALUES, origin SemanticIrValidator); got \""
                    + rejected.message() + "\"");
            check(java.util.Arrays.equals(
                    Files.readAllBytes(root.resolve("prior.lua")), bytes("PRIOR")),
                "the prior set is byte-untouched");
            checkNoSiblings(root, "invalid-unit rejection");
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 9. Emission-coverage defects against the plan
    // =========================================================================

    private static void testEmissionCoverageDefects() throws IOException {
        System.out.println("-- Emission-coverage defects fail closed --");
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = bcIndex();
        ModuleRoutePlan plan = bcPlan(invocation, index);
        LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
        ModuleEmissionResult resultB = successfulResult(unitB,
            List.of(new StagedArtifact("b/x.lua", bytes("B"))),
            emittedManifest(MOD_B, "b/x.lua"));

        // A result whose unit is not routed SHARED.
        Path root1 = tempRoot();
        try {
            LoweredModuleUnit ghostUnit = new LoweredModuleUnit(
                LoweredModuleUnit.FORMAT_VERSION, SemanticProfile.DEAL_V1_2_INT32,
                new ModuleId("zzz"), index.interfaceIndexDigest(),
                LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                    invocation.capabilityRegistryHash()),
                Set.of(), Map.of(), Map.of(), Map.of(),
                new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
                List.of());
            ModuleEmissionResult ghost = successfulResult(ghostUnit,
                List.of(new StagedArtifact("z/x.lua", bytes("Z"))),
                emittedManifest(new ModuleId("zzz"), "z/x.lua"));
            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(
                    root1, invocation, plan, List.of(ghost), List.of(), index);
            check(!outcome.published()
                    && outcome.diagnostics().get(0).message().contains(
                        "validatorRule " + ProjectArtifactStager.STAGE_UNIT_ROUTE_MISMATCH),
                "a unit not routed SHARED is rejected (STAGE_UNIT_ROUTE_MISMATCH)");
            check(outcome.stageTreeName() == null,
                "the coverage defect is rejected before staging");
            checkNoSiblings(root1, "route mismatch");
        } finally {
            deleteTree(root1.getParent());
        }

        // A SHARED-routed module without any emission result.
        Path root2 = tempRoot();
        try {
            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(
                    root2, invocation, plan, List.of(), List.of(), index);
            check(!outcome.published()
                    && outcome.diagnostics().get(0).message().contains(
                        "validatorRule " + ProjectArtifactStager.STAGE_MISSING_UNIT),
                "a missing unit for a SHARED module is rejected (STAGE_MISSING_UNIT)");
            checkNoSiblings(root2, "missing unit");
        } finally {
            deleteTree(root2.getParent());
        }

        // A successful result without its emitted ABI manifest.
        Path root3 = tempRoot();
        try {
            ModuleEmissionResult manifestless = new ModuleEmissionResult(
                List.of(new StagedArtifact("b/x.lua", bytes("B"))), List.of(), null,
                BoundaryRealizationReport.empty(), new OperationContractManifest(unitB));
            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(
                    root3, invocation, plan, List.of(manifestless), List.of(), index);
            check(!outcome.published()
                    && outcome.diagnostics().get(0).message().contains(
                        "validatorRule " + ProjectArtifactStager.STAGE_MISSING_EMITTED_ABI),
                "a successful emission without its emitted ABI manifest is rejected "
                    + "(STAGE_MISSING_EMITTED_ABI)");
            checkNoSiblings(root3, "missing manifest");
        } finally {
            deleteTree(root3.getParent());
        }

        // A duplicate emission result for one module.
        Path root4 = tempRoot();
        try {
            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(
                    root4, invocation, plan, List.of(resultB, resultB), List.of(), index);
            check(!outcome.published()
                    && outcome.diagnostics().get(0).message().contains(
                        "validatorRule " + ProjectArtifactStager.STAGE_UNIT_ROUTE_MISMATCH),
                "duplicate emission results for one module are rejected");
            checkNoSiblings(root4, "duplicate result");
        } finally {
            deleteTree(root4.getParent());
        }
    }

    // =========================================================================
    // 10. Combined flow (deps T1/T2/T5/T6/T10)
    // =========================================================================

    private static void testCombinedFlowAndDependencyFaults() throws IOException {
        System.out.println("-- Combined flow: invocation -> plan -> units -> ABI -> publication --");
        Path root = tempRoot();
        try {
            // T1: invocation resolution; T10: route plan + ABI records.
            CompilerInvocation invocation = commonShadowInvocation();
            check(invocation.purpose() == InvocationPurpose.COMMON_SHADOW
                    && invocation.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32,
                "T1 supplies the internal COMMON_SHADOW + DEAL_V1_2_INT32 invocation");

            ProjectInterfaceIndex index = abIndex();
            TargetModuleAbi abiA = completeAbiA("a/artifact.lua");
            ModuleRoutePlan plan = abPlan(invocation, index, abiA);
            check(plan.planId().equals("plan-" + plan.invocationHash().substring(0, 16)),
                "T10's plan record carries the derived planId");

            // T2: synthetic validated units; T8: constructionEntry facts flow
            // through the completed record (classFactoryAbi). The retained
            // side's artifact stages through a synthetic retained carrier
            // (foundation F6: retained sides stage first; ISSUE-0239 supplies
            // production retained results).
            LoweredModuleUnit unitB = validUnit(MOD_B, index, invocation);
            List<StagedArtifact> artifacts = List.of(
                new StagedArtifact("a/artifact.lua", bytes("a")),
                new StagedArtifact("b/x.lua", bytes("b")));
            ModuleEmissionResult retainedA = new ModuleEmissionResult(
                List.of(new StagedArtifact("a/artifact.lua", bytes("a"))), List.of(), null,
                BoundaryRealizationReport.empty(), null);
            ModuleEmissionResult resultB = successfulResult(unitB,
                List.of(new StagedArtifact("b/x.lua", bytes("b"))),
                emittedManifest(MOD_B, "b/x.lua"));

            String planTextBefore = plan.canonicalText();
            ProjectArtifactStager.PublicationOutcome outcome =
                ProjectArtifactStager.stageValidatePublish(
                    root, invocation, plan, List.of(retainedA, resultB), List.of(abiA),
                    index);

            check(outcome.published(),
                "the full flow (profile selection -> plan -> units -> validated publication) "
                    + "succeeds");
            assertTreeEquals(root, artifacts, "combined flow");
            checkNoSiblings(root, "combined flow");
            check(plan.canonicalText().equals(planTextBefore),
                "the staging nonce never enters the plan record (byte-identical plan text)");
            check(outcome.stageTreeName().contains(plan.planId())
                    && outcome.stageTreeName().contains("pid"),
                "the on-disk tree name carries the planId plus the per-invocation nonce");

            // Fault each dependency in turn: the suite fails for any constituent.
            // (a) T6: a unit failing the closed rules is rejected before staging.
            LoweredModuleUnit invalidUnitB = new LoweredModuleUnit(
                LoweredModuleUnit.FORMAT_VERSION, SemanticProfile.DEAL_V1_2_INT32, MOD_B,
                "wrong-digest",
                LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                    invocation.capabilityRegistryHash()),
                Set.of(), Map.of(), Map.of(), Map.of(),
                new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
                List.of());
            ModuleEmissionResult invalidResult = successfulResult(invalidUnitB,
                List.of(new StagedArtifact("b/x.lua", bytes("b"))),
                emittedManifest(MOD_B, "b/x.lua"));
            Path faultRoot1 = tempRoot();
            try {
                ProjectArtifactStager.PublicationOutcome faulted =
                    ProjectArtifactStager.stageValidatePublish(
                        faultRoot1, invocation, plan, List.of(invalidResult), List.of(abiA),
                        index);
                check(!faulted.published()
                        && faulted.diagnostics().get(0).message().contains(
                            "validatorRule " + SemanticIrValidator.R_PROFILE),
                    "a T6 fault fails the suite (invalid unit rejected)");
                checkNoSiblings(faultRoot1, "T6 fault");
            } finally {
                deleteTree(faultRoot1.getParent());
            }

            // (b) T10/T8: a broken ABI record (missing load key) fails the suite.
            TargetModuleAbi brokenAbi = abiRecord(abiA.moduleId(), abiA.target(),
                abiA.artifactOwner(), abiA.semanticProfile(), abiA.classFactoryAbi(),
                abiA.classLayoutAbi(), abiA.exportedDescriptors(), null,
                abiA.initializationEntry(), abiA.functionWrapperAbi(),
                abiA.syncInvocationEntries(), abiA.asyncLinkageRecords());
            Path faultRoot2 = tempRoot();
            try {
                ProjectArtifactStager.PublicationOutcome faulted =
                    ProjectArtifactStager.stageValidatePublish(
                        faultRoot2, invocation, plan, List.of(resultB), List.of(brokenAbi),
                        index);
                check(!faulted.published()
                        && faulted.diagnostics().get(0).message().contains(
                            "validatorRule " + TargetAbiValidator.ABI_MISSING_LOAD_KEY),
                    "an ABI-record fault fails the suite (E6005 through T5's registry)");
                assertE6005(faulted.diagnostics().get(0), TargetAbiValidator.ABI_MISSING_LOAD_KEY,
                    "a", SemanticProfile.DEAL_V1_2_INT32, "TargetAbiValidator");
                checkNoSiblings(faultRoot2, "ABI fault");
            } finally {
                deleteTree(faultRoot2.getParent());
            }

            // (c) T8: descriptors contradicting the index fail the suite.
            TargetModuleAbi descriptorFault = abiRecord(abiA.moduleId(), abiA.target(),
                abiA.artifactOwner(), abiA.semanticProfile(), abiA.classFactoryAbi(),
                abiA.classLayoutAbi(), Map.of("add", DESC_ADD), abiA.loadKey(),
                abiA.initializationEntry(), abiA.functionWrapperAbi(),
                abiA.syncInvocationEntries(), abiA.asyncLinkageRecords());
            Path faultRoot3 = tempRoot();
            try {
                ProjectArtifactStager.PublicationOutcome faulted =
                    ProjectArtifactStager.stageValidatePublish(
                        faultRoot3, invocation, plan, List.of(retainedA, resultB),
                        List.of(descriptorFault), index);
                check(!faulted.published()
                        && faulted.diagnostics().get(0).message().contains(
                            "validatorRule " + TargetAbiValidator.ABI_DESCRIPTOR_MISMATCH),
                    "an index/descriptor fault fails the suite");
                checkNoSiblings(faultRoot3, "descriptor fault");
            } finally {
                deleteTree(faultRoot3.getParent());
            }
        } finally {
            deleteTree(root.getParent());
        }
    }

    // =========================================================================
    // 11. Structural guards and invariants
    // =========================================================================

    private static void testStructuralGuardsAndInvariants() {
        System.out.println("-- Structural guards and pinned invariants --");

        // ModuleEmissionResult: a failed module has no staged artifact (structural).
        boolean failedWithArtifacts = false;
        try {
            new ModuleEmissionResult(List.of(new StagedArtifact("x.lua", bytes("x"))),
                List.of(CompilerDiagnostic.error(DiagnosticCode.E6000, "failed",
                    DiagnosticRange.synthetic("test"))),
                null, BoundaryRealizationReport.empty(), null);
        } catch (IllegalArgumentException expected) {
            failedWithArtifacts = true;
        }
        check(failedWithArtifacts,
            "a failed ModuleEmissionResult with artifacts is rejected structurally "
                + "(no partial module artifact)");

        // A successful retained-side artifact carrier (no unit, no manifest) is
        // admitted structurally — the stager's completeness gates own the rest.
        ModuleEmissionResult retainedCarrier = new ModuleEmissionResult(
            List.of(new StagedArtifact("x.lua", bytes("x"))), List.of(), null,
            BoundaryRealizationReport.empty(), null);
        check(!retainedCarrier.failed() && retainedCarrier.unit() == null
                && retainedCarrier.emittedAbiManifest() == null
                && retainedCarrier.stagedArtifacts().size() == 1,
            "a retained-side artifact carrier is admitted (synthetic retained side, "
                + "foundation F6; ISSUE-0239 supplies production retained results)");

        // StagedArtifact: out-of-grammar names fail closed.
        for (String bad : new String[]{"", "/abs.lua", "a/../b.lua", "a\\b.lua", "..",
                "C:drive.lua"}) {
            boolean rejected = false;
            try {
                new StagedArtifact(bad, bytes("x"));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "artifact name \"" + bad + "\" is rejected (staging-tree escape "
                + "is impossible)");
        }

        // StagedArtifact: the byte payload is defensively copied.
        byte[] payload = bytes("x");
        StagedArtifact artifact = new StagedArtifact("x.lua", payload);
        payload[0] = 'y';
        artifact.bytes()[0] = 'z';
        check(artifact.bytes()[0] == 'x',
            "the staged artifact payload is immutable (defensive copies both ways)");

        // StagedArtifactSet: duplicate names fail closed.
        boolean duplicate = false;
        try {
            new StagedArtifactSet(List.of(new StagedArtifact("x.lua", bytes("a")),
                new StagedArtifact("x.lua", bytes("b"))));
        } catch (IllegalArgumentException expected) {
            duplicate = true;
        }
        check(duplicate, "duplicate staged artifact names fail closed");

        // PublicationOutcome: success with diagnostics is rejected structurally.
        boolean outcomeGuard = false;
        try {
            ProjectArtifactStager.PublicationOutcome.success("tree",
                List.of(new StagedArtifact("x.lua", bytes("x"))));
            new ProjectArtifactStager.PublicationOutcome(true, "tree",
                List.of(new StagedArtifact("x.lua", bytes("x"))),
                List.of(CompilerDiagnostic.error(DiagnosticCode.E6000, "bad",
                    DiagnosticRange.synthetic("test"))));
        } catch (IllegalArgumentException expected) {
            outcomeGuard = true;
        }
        check(outcomeGuard, "a publication outcome cannot be both published and failed");

        // The registry names TargetAbiValidator as the single producer of the
        // ABI-mismatch coverage item (T5's D11 coverage list).
        check(FailureContractRegistry.e6005CoverageItems().get(
                "ABI mismatch after compatibility was claimed").contains("TargetAbiValidator"),
            "T5's coverage list names TargetAbiValidator for ABI mismatch after "
                + "compatibility was claimed");

        // The route-plan record stays free of any staging-tree naming facts.
        CompilerInvocation invocation = commonShadowInvocation();
        ProjectInterfaceIndex index = abIndex();
        ModuleRoutePlan plan = abPlan(invocation, index, completeAbiA("a/artifact.lua"));
        check(!plan.canonicalText().contains("deal-stage")
                && !plan.canonicalText().contains("deal-retired"),
            "the plan record carries no staging-tree naming facts (the nonce lives only in "
                + "on-disk tree names)");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Staging / ABI Validation / Atomic Publication Test (ISSUE-0291) ===\n");

        testValidatorCompleteRecordPasses();
        testValidatorIncompleteRecordClasses();
        testValidatorMismatchClasses();
        testForcedFailureLeavesPriorUntouched();
        testFreshRootSuccess();
        testFreshRootFailingSingleMove();
        testPriorSetRestoreOnFailedSecondMove();
        testDirectWriteConflict();
        testTornPublishRecovery();
        testStaleStageOnlyRecovery();
        testRecoveryKeepsCurrentTree();
        testConcurrentIsolation();
        testInvalidUnitRejectedBeforeStaging();
        testEmissionCoverageDefects();
        testCombinedFlowAndDependencyFaults();
        testStructuralGuardsAndInvariants();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
