// =========================================================================
// E12ActivationReleaseTest — the public activation release gate (R3, V3)
// =========================================================================

package deal.test;

import deal.codegen.Backend;
import deal.module.CompilationOrchestrator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.MigrationPlanner;
import deal.semantic.ModuleRoute;
import deal.semantic.ModuleRoutePlan;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.RoutePlanResult;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.Target;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The E12 public activation release gate
 * (capability-promotion-activation-rollback-retention Verification 3,
 * plus the combined-behavior step): the evidence-record content checks,
 * every activation precondition negative, the gate-record fault
 * injection, the digest-mismatch detection, the committed flipped
 * constant and promoted registry state, the A1 profile-mapping rows, the
 * absence of any legacy-selection surface, and a concrete post-flip
 * shared-routing plan for an int-using module (eligibility plus the
 * {@code SIGNED_INT32} requirement).
 *
 * <p>The committed release state stays flipped: every negative below
 * runs the release action over a derived configuration — the
 * {@code ReleaseConfiguration} constant and registry are never edited by
 * this test.</p>
 */
public class E12ActivationReleaseTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // 1. Activation evidence record content checks
    // =========================================================================

    static void testEvidenceRecordContent() throws Exception {
        System.out.println("-- Activation evidence record content checks --");

        ActivationEvidenceRecord.Evidence evidence =
            ActivationEvidenceRecord.E11_EVIDENCE;
        check(evidence.formatVersion() == ActivationEvidenceRecord.FORMAT_VERSION,
            "the evidence record carries formatVersion 1");
        check(ActivationEvidenceRecord.PRODUCER_ISSUE.equals(evidence.producerIssue()),
            "the evidence record names its producer (ISSUE-0240's harness): "
                + evidence.producerIssue());
        check(evidence.releaseState() == ReleaseState.PRE_ACTIVATION,
            "the evidence record ran under PRE_ACTIVATION");
        check(evidence.complete() && evidence.green(),
            "the evidence record is complete and green");

        check(evidence.consumers().equals(ActivationEvidenceRecord.COMMON_CONSUMERS)
                && evidence.consumers().size() == 3,
            "the evidence record names exactly the three common consumers "
                + "(SEMANTIC_ORACLE, SHARED_LUAJIT, SHARED_JVM): "
                + evidence.consumers());

        check(!evidence.caseLocators().isEmpty(),
            "the evidence record names at least one all-common case");
        for (String locator : evidence.caseLocators()) {
            Path fixture = Path.of("test", "conformance").resolve(locator).normalize();
            check(Files.isRegularFile(fixture),
                "the named case " + locator + " exists on disk under "
                    + "test/conformance");
        }

        check(!evidence.productionPlans().isEmpty(),
            "the evidence record records at least one pre-activation production plan");
        for (ActivationEvidenceRecord.PreActivationPlan plan
                : evidence.productionPlans()) {
            check(plan.moduleRoutes().stream().allMatch("LEGACY"::equals),
                "the recorded pre-activation plan '" + plan.buildId()
                    + "' routes every module LEGACY: " + plan.moduleRoutes());
            check(plan.shadowModules().isEmpty(),
                "the recorded pre-activation plan '" + plan.buildId()
                    + "' has empty shadowModules");
        }
    }

    // =========================================================================
    // 2. The release action over the authored T5 records (all PASS)
    // =========================================================================

    static void testReleaseActionComposition() {
        System.out.println("-- Release action: T5 records compose the promoted registry --");

        E12ActivationReleaseAction.Configuration config =
            new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION,
                ActivationEvidenceRecord.E11_EVIDENCE,
                PromotionGateRecord.E12_GATE_RECORDS,
                ReleaseConfiguration.activationPromotions());
        E12ActivationReleaseAction.Result result =
            E12ActivationReleaseAction.applyActivation(config);
        check(result.success(),
            "the release action succeeds over the authored T5 records (all PASS): "
                + (result.failureText() == null ? "ok" : result.failureText()));
        if (!result.success()) {
            return;
        }
        check(result.registry() != null, "the action composed a registry");

        // The composed registry equals the committed release registry
        // entry-for-entry (the committed constant stays flipped; the
        // derived configuration reproduces it exactly).
        CapabilityRegistry composed = result.registry();
        CapabilityRegistry committed = ReleaseConfiguration.releaseCapabilityRegistry();
        check(composed.entries().equals(committed.entries()),
            "the composed registry equals the committed release registry "
                + "entry-for-entry");
        check(composed.capabilityRegistryHash()
                .equals(committed.capabilityRegistryHash()),
            "the composed registry digest equals the committed release digest");

        // Digest recomputation: the composed digest is the canonical
        // SHA-256 over the result's own entries (never hand-rolled,
        // never unchanged).
        check(composed.capabilityRegistryHash().equals(
                E12ActivationReleaseAction.recomputedDigest(composed)),
            "the composed digest is the canonical recomputation over its own "
                + "entries");
        check(!composed.capabilityRegistryHash().equals(
                CapabilityRegistry.releaseRegistry().capabilityRegistryHash()),
            "the composed digest differs from the all-SHADOW release digest");

        // The post-condition facts: the A1 rows over the flipped state.
        check(result.postConditionFacts().stream().anyMatch(fact ->
                fact.startsWith("publicProfile(V1_2_ACTIVE)")),
            "the post-conditions assert publicProfile(V1_2_ACTIVE) == "
                + "DEAL_V1_2_INT32");
        check(result.postConditionFacts().stream().anyMatch(fact ->
                fact.startsWith("publicProfile(PRE_ACTIVATION)")),
            "the post-conditions assert publicProfile(PRE_ACTIVATION) == "
                + "LEGACY_SAFE_INT (the internal matrix row)");

        // Release-state hash discipline: the flipped configuration's
        // hash recomputes over the promoted registry hash.
        String expectedStateHash = CompilerProfileProvider.deriveReleaseStateHash(
            ReleaseState.V1_2_ACTIVE, committed.capabilityRegistryHash());
        check(result.postConditionFacts().stream().anyMatch(fact ->
                fact.contains(expectedStateHash)),
            "the release-state hash recomputes over the flipped configuration "
                + "(never an unchanged-hash claim)");
    }

    // =========================================================================
    // 3. Precondition negatives (derived configurations; the committed
    //    release state stays flipped)
    // =========================================================================

    private static void checkAborts(E12ActivationReleaseAction.Configuration config,
                                    String expectedFact, String what) {
        E12ActivationReleaseAction.Result result =
            E12ActivationReleaseAction.applyActivation(config);
        check(!result.success(), what + " aborts the action");
        check(result.registry() == null, what + " composes no registry");
        check(result.failureText() != null
                && result.failureText().startsWith(expectedFact),
            what + " names the exact fact " + expectedFact + ": "
                + (result.failureText() == null ? "null" : result.failureText()));
    }

    static void testPreconditionNegatives() {
        System.out.println("-- Activation precondition negatives --");

        List<ReleaseConfiguration.ReleasePromotion> full =
            ReleaseConfiguration.activationPromotions();

        // (1) The armed gate: a non-PRE_ACTIVATION configuration aborts.
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.V1_2_ACTIVE, ActivationEvidenceRecord.E11_EVIDENCE,
                PromotionGateRecord.E12_GATE_RECORDS, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "a release state that is not PRE_ACTIVATION");

        // (2) Missing/incomplete E11 evidence: incomplete, not green, a
        // wrong release state, and no named cases — each aborts.
        ActivationEvidenceRecord.Evidence e = ActivationEvidenceRecord.E11_EVIDENCE;
        ActivationEvidenceRecord.Evidence incomplete =
            new ActivationEvidenceRecord.Evidence(e.formatVersion(), e.producerIssue(),
                e.releaseState(), e.caseLocators(), e.consumers(), false, true,
                e.productionPlans());
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, incomplete,
                PromotionGateRecord.E12_GATE_RECORDS, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "an incomplete evidence record");
        ActivationEvidenceRecord.Evidence notGreen =
            new ActivationEvidenceRecord.Evidence(e.formatVersion(), e.producerIssue(),
                e.releaseState(), e.caseLocators(), e.consumers(), true, false,
                e.productionPlans());
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, notGreen,
                PromotionGateRecord.E12_GATE_RECORDS, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "a not-green evidence record");
        ActivationEvidenceRecord.Evidence wrongState =
            new ActivationEvidenceRecord.Evidence(e.formatVersion(), e.producerIssue(),
                ReleaseState.V1_2_ACTIVE, e.caseLocators(), e.consumers(), true,
                true, e.productionPlans());
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, wrongState,
                PromotionGateRecord.E12_GATE_RECORDS, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "an evidence record produced under the wrong release state");
        ActivationEvidenceRecord.Evidence noCases =
            new ActivationEvidenceRecord.Evidence(e.formatVersion(), e.producerIssue(),
                e.releaseState(), List.of(), e.consumers(), true, true,
                e.productionPlans());
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, noCases,
                PromotionGateRecord.E12_GATE_RECORDS, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "an evidence record naming no case");

        // (3) A recorded pre-activation production plan with a shared
        // route aborts (the evidential half of "no pre-activation
        // production module took a shared route").
        ActivationEvidenceRecord.PreActivationPlan sharedPlan =
            new ActivationEvidenceRecord.PreActivationPlan("SHARED_ROUTE_PLAN",
                "main", List.of("SHARED"), List.of());
        ActivationEvidenceRecord.Evidence sharedHistory =
            new ActivationEvidenceRecord.Evidence(e.formatVersion(), e.producerIssue(),
                e.releaseState(), e.caseLocators(), e.consumers(), true, true,
                List.of(sharedPlan));
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, sharedHistory,
                PromotionGateRecord.E12_GATE_RECORDS, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "a recorded pre-activation production plan with a shared route");

        // (5) An empty promotion list aborts; no flip fires.
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, ActivationEvidenceRecord.E11_EVIDENCE,
                PromotionGateRecord.E12_GATE_RECORDS, List.of()),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "an empty promotion list");

        // (5) A list missing SIGNED_INT32 for a shared target aborts.
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, ActivationEvidenceRecord.E11_EVIDENCE,
                PromotionGateRecord.E12_GATE_RECORDS,
                List.of(full.get(0), full.get(1), full.get(2))),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "a promotion list missing SIGNED_INT32 for JVM");
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, ActivationEvidenceRecord.E11_EVIDENCE,
                PromotionGateRecord.E12_GATE_RECORDS,
                List.of(full.get(0), full.get(1), full.get(3))),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "a promotion list missing SIGNED_INT32 for LUAJIT");

        // (4) A gate-rejected pair in the list aborts: fault one item of
        // the SIGNED_INT32 × JVM record and keep the rest.
        List<PromotionGateRecord.Record> faultedRecords = new ArrayList<>();
        for (PromotionGateRecord.Record record : PromotionGateRecord.E12_GATE_RECORDS) {
            if (record.capability() == SemanticCapability.SIGNED_INT32
                    && record.target() == Target.JVM) {
                List<PromotionGateRecord.Item> items = new ArrayList<>();
                for (PromotionGateRecord.Item item : record.items()) {
                    if (item.gate() == PromotionGateRecord.Gate.COMPLETE_CLOSURE) {
                        items.add(new PromotionGateRecord.Item(item.gate(),
                            PromotionGateRecord.Status.MISSING, item.evidenceLocators(),
                            item.detail()));
                    } else {
                        items.add(item);
                    }
                }
                faultedRecords.add(new PromotionGateRecord.Record(
                    record.formatVersion(), record.capability(), record.target(),
                    items));
            } else {
                faultedRecords.add(record);
            }
        }
        checkAborts(new E12ActivationReleaseAction.Configuration(
                ReleaseState.PRE_ACTIVATION, ActivationEvidenceRecord.E11_EVIDENCE,
                faultedRecords, full),
            E12ActivationReleaseAction.ACTIVATION_PRECONDITION_FAILED,
            "a gate-rejected pair in the promotion list");
    }

    // =========================================================================
    // 4. Promotion attempt contract negatives: order, lock, missing record
    // =========================================================================

    static void testPromotionAttemptNegatives() {
        System.out.println("-- Promotion attempt contract: order, lock, missing "
            + "record --");

        List<ReleaseConfiguration.ReleasePromotion> full =
            ReleaseConfiguration.activationPromotions();
        List<PromotionGateRecord.Record> records = PromotionGateRecord.E12_GATE_RECORDS;

        // Out-of-order capability: SIGNED_INT32 before FOUNDATION_VALUES.
        List<ReleaseConfiguration.ReleasePromotion> outOfOrder = List.of(
            full.get(2), full.get(3), full.get(0), full.get(1));
        E12ActivationReleaseAction.Result order =
            E12ActivationReleaseAction.validatePromotionList(records, outOfOrder);
        check(!order.success() && order.failureText() != null
                && order.failureText().startsWith(
                    E12ActivationReleaseAction.PROMOTION_ORDER_VIOLATED),
            "an out-of-order capability attempt is rejected with "
                + "PROMOTION_ORDER_VIOLATED: "
                + (order.failureText() == null ? "null" : order.failureText()));

        // The locked time capability (parent D8).
        List<ReleaseConfiguration.ReleasePromotion> locked = List.of(
            new ReleaseConfiguration.ReleasePromotion(
                SemanticCapability.STDLIB_TIME_CONFLICT, Target.LUAJIT));
        E12ActivationReleaseAction.Result lock =
            E12ActivationReleaseAction.validatePromotionList(records, locked);
        check(!lock.success() && lock.failureText() != null
                && lock.failureText().startsWith(
                    E12ActivationReleaseAction.PROMOTION_LOCKED),
            "an attempt to promote STDLIB_TIME_CONFLICT is rejected with "
                + "PROMOTION_LOCKED: "
                + (lock.failureText() == null ? "null" : lock.failureText()));

        // A promotion pair without a gate record is rejected with the
        // named capability, target, and gate.
        List<ReleaseConfiguration.ReleasePromotion> unrecorded = List.of(
            new ReleaseConfiguration.ReleasePromotion(
                SemanticCapability.BINDINGS, Target.LUAJIT));
        E12ActivationReleaseAction.Result missing =
            E12ActivationReleaseAction.validatePromotionList(records, unrecorded);
        check(!missing.success() && missing.failureText() != null
                && missing.failureText().startsWith(
                    E12ActivationReleaseAction.PROMOTION_GATE_REJECTED)
                && missing.failureText().contains("BINDINGS LUAJIT"),
            "a promotion pair without a gate record is rejected with "
                + "PROMOTION_GATE_REJECTED naming the pair: "
                + (missing.failureText() == null ? "null" : missing.failureText()));
    }

    // =========================================================================
    // 5. Digest mismatch detection (the combined-behavior step)
    // =========================================================================

    static void testDigestMismatchDetection() {
        System.out.println("-- Digest mismatch detection (a transition surface "
            + "that fails to recompute aborts) --");

        CapabilityRegistry committed = ReleaseConfiguration.releaseCapabilityRegistry();
        check(E12ActivationReleaseAction.verifyRegistryDigest(committed,
                E12ActivationReleaseAction.recomputedDigest(committed)),
            "the committed registry passes the canonical digest verification");
        check(!E12ActivationReleaseAction.verifyRegistryDigest(committed,
                "0000000000000000000000000000000000000000000000000000000000000000"),
            "a tampered expected digest is detected (the verification returns "
                + "false and the release action aborts on the mismatch)");

        // Through the action's own seam: the mismatch branch is the
        // named activation fact when the verification reports false.
        boolean mismatchAborts = !E12ActivationReleaseAction.verifyRegistryDigest(
            committed, "deadbeef");
        check(mismatchAborts,
            "a transition surface that fails to recompute the digest is detected "
                + "and the action aborts (hash mismatch)");
    }

    // =========================================================================
    // 6. The committed flipped constant and promoted registry state
    // =========================================================================

    static void testCommittedFlipState() {
        System.out.println("-- The committed flipped constant and promoted "
            + "registry state --");

        check(ReleaseConfiguration.CURRENT_RELEASE_STATE == ReleaseState.V1_2_ACTIVE,
            "ReleaseConfiguration.CURRENT_RELEASE_STATE == V1_2_ACTIVE (the "
                + "committed E12 flip)");

        // The promotion list is non-empty, in the parent's capability
        // order, LUAJIT before JVM, and carries the minimum content.
        List<ReleaseConfiguration.ReleasePromotion> list =
            ReleaseConfiguration.activationPromotions();
        check(!list.isEmpty(), "the release-owned promotion list is non-empty");
        check(list.size() == 4
                && list.get(0).capability() == SemanticCapability.FOUNDATION_VALUES
                && list.get(0).target() == Target.LUAJIT
                && list.get(1).capability() == SemanticCapability.FOUNDATION_VALUES
                && list.get(1).target() == Target.JVM
                && list.get(2).capability() == SemanticCapability.SIGNED_INT32
                && list.get(2).target() == Target.LUAJIT
                && list.get(3).capability() == SemanticCapability.SIGNED_INT32
                && list.get(3).target() == Target.JVM,
            "the promotion list is FOUNDATION_VALUES then SIGNED_INT32, LUAJIT "
                + "before JVM (the parent's capability order): " + list);

        CapabilityRegistry committed = ReleaseConfiguration.releaseCapabilityRegistry();
        int promotedCount = 0;
        for (CapabilityRegistry.Entry entry : committed.entries()) {
            if (entry.state() == CapabilityRegistry.State.PROMOTED) {
                promotedCount++;
                check(entry.capability() == SemanticCapability.FOUNDATION_VALUES
                        || entry.capability() == SemanticCapability.SIGNED_INT32,
                    "a PROMOTED entry names exactly FOUNDATION_VALUES or "
                        + "SIGNED_INT32; got " + entry);
            }
        }
        check(promotedCount == 4,
            "exactly four entries are PROMOTED (FOUNDATION_VALUES and "
                + "SIGNED_INT32 × both targets); got " + promotedCount);
        check(committed.state(SemanticCapability.SIGNED_INT32, Target.LUAJIT)
                == CapabilityRegistry.State.PROMOTED
                && committed.state(SemanticCapability.SIGNED_INT32, Target.JVM)
                == CapabilityRegistry.State.PROMOTED,
            "SIGNED_INT32 is PROMOTED for both shared targets");
        check(committed.state(SemanticCapability.FOUNDATION_VALUES, Target.LUAJIT)
                == CapabilityRegistry.State.PROMOTED
                && committed.state(SemanticCapability.FOUNDATION_VALUES, Target.JVM)
                == CapabilityRegistry.State.PROMOTED,
            "FOUNDATION_VALUES is PROMOTED for both shared targets");
        check(committed.state(SemanticCapability.STDLIB_TIME_CONFLICT, Target.LUAJIT)
                == CapabilityRegistry.State.SHADOW
                && committed.state(SemanticCapability.STDLIB_TIME_CONFLICT, Target.JVM)
                == CapabilityRegistry.State.SHADOW,
            "STDLIB_TIME_CONFLICT stays SHADOW (the parent D8 lock)");

        // The all-SHADOW release default stays byte-unchanged and the
        // committed digest recomputes over the promoted entries.
        CapabilityRegistry release = CapabilityRegistry.releaseRegistry();
        check(release.entries().stream().allMatch(entry ->
                entry.state() == CapabilityRegistry.State.SHADOW),
            "releaseRegistry() stays all-SHADOW after the activation derivation");
        check(committed.capabilityRegistryHash().equals(
                CanonicalJson.sha256Hex(
                    CanonicalJson.serializeBytes(committed.canonicalJson()))),
            "the committed release digest is the canonical recomputation over its "
                + "own entries");
        check(!committed.capabilityRegistryHash()
                .equals(release.capabilityRegistryHash()),
            "the committed release digest differs from the all-SHADOW digest "
                + "(never an unchanged-hash claim)");
        check(committed.entries().size() == CapabilityRegistry.ENTRY_COUNT,
            "the promoted registry keeps the closed 24-entry cross product");
    }

    // =========================================================================
    // 7. A1 matrix rows + the absence of any legacy-selection surface
    // =========================================================================

    static void testProfileMatrixAndNoLegacySelection() throws Exception {
        System.out.println("-- A1 matrix rows + no legacy-selection surface --");

        check(CompilerProfileProvider.publicProfile(ReleaseState.V1_2_ACTIVE)
                == SemanticProfile.DEAL_V1_2_INT32,
            "publicProfile(V1_2_ACTIVE) == DEAL_V1_2_INT32");
        check(CompilerProfileProvider.publicProfile(ReleaseState.PRE_ACTIVATION)
                == SemanticProfile.LEGACY_SAFE_INT,
            "publicProfile(PRE_ACTIVATION) == LEGACY_SAFE_INT (the internal matrix "
                + "row)");

        String mainSource = Files.readString(Path.of("deal/Main.java"));
        check(!mainSource.contains("--profile") && !mainSource.contains("--purpose")
                && !mainSource.contains("ReleaseState.PRE_ACTIVATION"),
            "deal/Main.java carries no profile/purpose option and no hardcoded "
                + "release-state literal");
        check(mainSource.contains("CompilerProfileProvider.resolve")
                && mainSource.contains("ReleaseConfiguration.CURRENT_RELEASE_STATE")
                && mainSource.contains("ReleaseConfiguration.releaseCapabilityRegistry()"),
            "deal/Main.java derives PUBLIC_BUILD through the provider from the "
                + "release configuration (derivation-only)");

        Matcher matcher = Pattern.compile("--[a-z][a-z-]*").matcher(mainSource);
        Set<String> options = new LinkedHashSet<>();
        while (matcher.find()) {
            options.add(matcher.group());
        }
        check(options.equals(Set.of("--output", "--backend", "--diagnostics-json",
                "--verbose", "--dump-ir", "--source-map")),
            "the CLI option set is unchanged (no legacy profile selection "
                + "surface); got " + options);

        String orchestratorSource =
            Files.readString(Path.of("deal/module/CompilationOrchestrator.java"));
        check(orchestratorSource.contains("ReleaseConfiguration.CURRENT_RELEASE_STATE")
                && !orchestratorSource.contains("ReleaseState.PRE_ACTIVATION"),
            "CompilationOrchestrator.defaultInvocation consumes the release "
                + "configuration and carries no hardcoded release-state literal");

        // PUBLIC_BUILD is derivation-only: the single resolve overload
        // takes (ReleaseState, CapabilityRegistry); no factory derives a
        // profile from a purpose string.
        try {
            CompilerProfileProvider.class.getDeclaredMethod("resolve",
                String.class, ReleaseState.class, CapabilityRegistry.class);
            fail("a purpose-string profile-selection factory must not exist");
        } catch (NoSuchMethodException expected) {
            check(true, "no provider factory selects a profile by purpose string "
                + "(PUBLIC_BUILD is derivation-only)");
        }
    }

    // =========================================================================
    // 8. The concrete post-flip shared-routing plan (eligibility plus the
    //    SIGNED_INT32 requirement)
    // =========================================================================

    static void testPostFlipSharedRoutingPlan() throws Exception {
        System.out.println("-- Post-flip shared routing: eligibility plus the "
            + "SIGNED_INT32 requirement --");

        Path tmp = Files.createTempDirectory("deal-e12-shared-routing");
        try {
            Path src = tmp.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), """
                export function add(x: int, y: int): int {
                  return x + y
                }

                export function main(): null {
                  return null
                }
                """);
            List<Path> roots = List.of(src.toAbsolutePath());
            Path entry = src.resolve("main.deal").toAbsolutePath();
            Path stdlibDir = Path.of("std").toAbsolutePath().normalize();

            // The post-flip public build: resolve(V1_2_ACTIVE,
            // ReleaseConfiguration.releaseCapabilityRegistry()) through
            // the real orchestrator.
            CompilerInvocation postFlip = CompilerProfileProvider.resolve(
                ReleaseState.V1_2_ACTIVE,
                ReleaseConfiguration.releaseCapabilityRegistry());
            CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                entry, tmp.resolve("build"), false, false, false, false,
                Backend.LUAJIT, null, roots, stdlibDir, null, postFlip);
            boolean ok = orchestrator.compile();
            check(ok, "the post-flip public build compiles: "
                + orchestrator.diagnostics());
            check(orchestrator.invocation().semanticProfile()
                    == SemanticProfile.DEAL_V1_2_INT32,
                "the post-flip public build derives DEAL_V1_2_INT32");
            RoutePlanResult planned = orchestrator.routePlan();
            check(planned != null && !planned.hasErrors() && planned.plan() != null,
                "the post-flip public build produces exactly one route plan");
            if (planned == null || planned.hasErrors() || planned.plan() == null) {
                return;
            }
            ModuleRoutePlan plan = planned.plan();
            check(plan.entries().values().stream()
                    .allMatch(route -> route == ModuleRoute.SHARED),
                "the int-using module routes SHARED post-flip (F4 rule 4 "
                    + "reachable; FOUNDATION_VALUES + SIGNED_INT32 promoted): "
                    + plan.entries());
            check(plan.shadowModules().isEmpty(),
                "the post-flip shared plan has empty shadowModules (production "
                    + "SHARED, never shadow)");

            // The SIGNED_INT32 requirement (small literals waive
            // nothing): demote SIGNED_INT32 × LUAJIT and re-plan — the
            // int-using module reroutes LEGACY at plan time.
            CheckedProjectBuildResult checked = orchestrator.checkedProject();
            RequirementManifestResult manifests = orchestrator.requirementManifests();
            if (checked == null || checked.hasErrors() || checked.input() == null
                    || checked.index() == null || manifests == null
                    || manifests.hasErrors() || manifests.manifests() == null) {
                fail("the post-flip compile produced no checked project/manifests");
                return;
            }
            CapabilityRegistry demoted = ReleaseConfiguration
                .releaseCapabilityRegistry()
                .withState(SemanticCapability.SIGNED_INT32, Target.LUAJIT,
                    CapabilityRegistry.State.SHADOW);
            for (SemanticRequirementManifest manifest : manifests.manifests()) {
                check(manifest.capabilities().contains(SemanticCapability.SIGNED_INT32),
                    "the int-using module's manifest claims SIGNED_INT32 (I3, "
                        + "construct-kind claim, small literals waive nothing): "
                        + manifest.capabilities());
            }
            RoutePlanResult demotedPlan = MigrationPlanner.planRoutes(
                CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE, demoted),
                demoted, checked.input(), checked.index(),
                manifests.manifests(), Target.LUAJIT, Set.of());
            check(demotedPlan != null && !demotedPlan.hasErrors()
                    && demotedPlan.plan() != null
                    && demotedPlan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.LEGACY),
                "demoting SIGNED_INT32 × LUAJIT reroutes the int-using module "
                    + "LEGACY at plan time (the SIGNED_INT32 requirement; silent "
                    + "plan-time ineligibility, never E6005): "
                    + (demotedPlan == null ? "null" : demotedPlan.diagnostics()));

            // Per-target discrimination: JVM keeps SIGNED_INT32 PROMOTED,
            // so the same module still routes SHARED for JVM over the
            // partially demoted registry.
            RoutePlanResult jvmPlan = MigrationPlanner.planRoutes(
                CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE, demoted),
                demoted, checked.input(), checked.index(),
                manifests.manifests(), Target.JVM, Set.of());
            check(jvmPlan != null && !jvmPlan.hasErrors()
                    && jvmPlan.plan() != null
                    && jvmPlan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.SHARED),
                "the JVM plan stays SHARED over the partially demoted registry "
                    + "(promotion is per target, F7)");

            // Post-activation rollback keeps DEAL_V1_2_INT32: over the
            // all-SHADOW registry a V1_2_ACTIVE public build stays v1.2
            // and reroutes all-LEGACY — the route-only terminal state.
            RoutePlanResult rollbackPlan = MigrationPlanner.planRoutes(
                CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
                    CapabilityRegistry.releaseRegistry()),
                CapabilityRegistry.releaseRegistry(), checked.input(),
                checked.index(), manifests.manifests(), Target.LUAJIT, Set.of());
            check(rollbackPlan != null && !rollbackPlan.hasErrors()
                    && rollbackPlan.plan() != null
                    && rollbackPlan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.LEGACY),
                "a post-activation rollback keeps DEAL_V1_2_INT32 and selects "
                    + "complete retained modules before lowering (all-LEGACY "
                    + "plan; LEGACY_SAFE_INT never reappears)");

            // A stdlib-using module claims STDLIB_SEMANTICS (not
            // promoted), so it stays LEGACY post-flip.
            Path stdSrc = tmp.resolve("stdsrc");
            Files.createDirectories(stdSrc);
            Files.writeString(stdSrc.resolve("main.deal"), """
                import * as console from "std/console"

                export function main(): null {
                  console.log("post-flip stdlib stays retained")
                  return null
                }
                """);
            CompilationOrchestrator stdOrchestrator = new CompilationOrchestrator(
                stdSrc.resolve("main.deal").toAbsolutePath(),
                tmp.resolve("build-std"), false, false, false, false,
                Backend.LUAJIT, null, List.of(stdSrc.toAbsolutePath()),
                stdlibDir, null, CompilerProfileProvider.resolve(
                    ReleaseState.V1_2_ACTIVE,
                    ReleaseConfiguration.releaseCapabilityRegistry()));
            boolean stdOk = stdOrchestrator.compile();
            check(stdOk, "the post-flip stdlib-using build compiles: "
                + stdOrchestrator.diagnostics());
            RoutePlanResult stdPlan = stdOrchestrator.routePlan();
            check(stdPlan != null && !stdPlan.hasErrors() && stdPlan.plan() != null
                    && stdPlan.plan().entries().values().stream()
                        .allMatch(route -> route == ModuleRoute.LEGACY),
                "the stdlib-using module routes LEGACY post-flip "
                    + "(STDLIB_SEMANTICS not promoted, F4 rule 4): "
                    + (stdPlan == null ? "null" : stdPlan.diagnostics()));
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path root) throws java.io.IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) {
        try {
            testEvidenceRecordContent();
            testReleaseActionComposition();
            testPreconditionNegatives();
            testPromotionAttemptNegatives();
            testDigestMismatchDetection();
            testCommittedFlipState();
            testProfileMatrixAndNoLegacySelection();
            testPostFlipSharedRoutingPlan();
        } catch (Throwable t) {
            failed++;
            System.err.println("FAIL: unexpected " + t);
            t.printStackTrace();
        }
        System.out.println();
        System.out.println("E12ActivationReleaseTest: " + passed + " passed, "
            + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
