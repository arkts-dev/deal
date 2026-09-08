// =========================================================================
// PromotionGateRecord — the closed twelve-item promotion gate (R2)
// =========================================================================

package deal.test;

import deal.semantic.Target;
import deal.semantic.ir.SemanticCapability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The closed promotion gate schema and the release-owned E12 promotion
 * gate records (capability-promotion-activation-rollback-retention R2).
 * Release/harness-owned versioned data authored in this gate-run
 * conformance file (the {@code LegacyProfileRegressionCatalog}
 * precedent) — production compiler paths never depend on it; the release
 * action validates the records before composing the
 * {@link deal.semantic.CapabilityRegistry#withState} transitions, and
 * the transitions themselves stay policy-free (R1 invariant 6).
 *
 * <p><b>Gate schema (R2, closed).</b>
 * {@code PromotionGateRecord {capability, target, formatVersion: 1,
 * items: [PromotionGateItem]}} with exactly twelve items, one per
 * {@link Gate} in the pinned order. Each item carries
 * {@code {gate, status: PASS|MISSING, evidenceLocators: [locator],
 * detail}}. The closed gate order is:
 * {@code PROFILE_FOUNDATION, COMPLETE_CLOSURE, OUTCOMES_EFFECTS,
 * OPERATION_SNAPSHOT, THREE_WAY_TRACES, RELATION_SEEDS,
 * HISTORICAL_TESTS, RETAINED_ASSIGNMENTS, ROLLBACK_REJECTION,
 * MIXED_ROUTE_ABI, REAL_ARTIFACTS, FULL_GATE}. A record that deviates
 * from this shape is rejected by the release action with the named gate.
 *
 * <p><b>Promotion ordering and locks (R2).</b> Promotion proceeds in the
 * parent's capability order (the {@link SemanticCapability} declaration
 * order); an out-of-order capability attempt is rejected with
 * {@code PROMOTION_ORDER_VIOLATED <capability>}; attempting to promote
 * {@code STDLIB_TIME_CONFLICT} is rejected with
 * {@code PROMOTION_LOCKED STDLIB_TIME_CONFLICT} (parent D8's lock).
 *
 * <p><b>Item 1's activation condition (R2/R3).</b>
 * {@code PROFILE_FOUNDATION} is evaluated relative to the release unit
 * that performs the promotion: for a pair in the E12 activation unit's
 * own promotion list, the unit's own flip satisfies the activation
 * condition (the first-promotion bootstrap); for any later unit, only
 * the already-performed activation satisfies it. For
 * {@code SIGNED_INT32} the item additionally requires both retained
 * target routes and the common consumers to satisfy v1.2 (the
 * {@code SignedInt32Corpus} four-way agreement and the common int
 * operation cases).
 */
public final class PromotionGateRecord {

    private PromotionGateRecord() {
        // Release-owned data + closed schema only; no instances.
    }

    /** The pinned canonical shape version of a promotion gate record. */
    public static final int FORMAT_VERSION = 1;

    /** The closed per-item status: {@code PASS} or {@code MISSING}. */
    public enum Status {

        /** The gate item is satisfied by its evidence. */
        PASS,

        /** The gate item is missing; a promotion over it is rejected. */
        MISSING
    }

    /**
     * The closed promotion gate identity, in the pinned R2 order — one
     * item per gate, twelve items per (capability &times; target) record.
     */
    public enum Gate {

        /** Profile foundation readiness and the unit-relative activation condition. */
        PROFILE_FOUNDATION,

        /** Complete common closure of the capability's case corpus. */
        COMPLETE_CLOSURE,

        /** Explicit outcome/effect expectations on every case. */
        OUTCOMES_EFFECTS,

        /** Operation-contract snapshot/digest/parent nesting validation. */
        OPERATION_SNAPSHOT,

        /** Oracle + shared LuaJIT + shared JVM event-for-event trace parity. */
        THREE_WAY_TRACES,

        /** Applicable metamorphic relation seeds plus the negative-control battery. */
        RELATION_SEEDS,

        /** Historical and legacy-profile catalog rows active in their authorities. */
        HISTORICAL_TESTS,

        /** Validated unsupported-legacy-slice assignments (attainable evidence floors). */
        RETAINED_ASSIGNMENTS,

        /** Representative rollback/rejection cases (per family and per rejection class). */
        ROLLBACK_REJECTION,

        /** Mixed-route cases with validated ABI edges and artifacts. */
        MIXED_ROUTE_ABI,

        /** Real LuaJIT/JVM artifacts load, compile, and execute. */
        REAL_ARTIFACTS,

        /** {@code ./run_tests.sh} exits 0 with every suite green. */
        FULL_GATE
    }

    /** One closed gate item: {@code {gate, status, evidenceLocators, detail}}. */
    public record Item(Gate gate, Status status, List<String> evidenceLocators,
                       String detail) {

        public Item {
            Objects.requireNonNull(gate, "gate must not be null");
            Objects.requireNonNull(status, "status must not be null");
            evidenceLocators = List.copyOf(evidenceLocators);
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * One closed promotion gate record for one (capability &times; target)
     * pair: exactly twelve items in the pinned gate order. The compact
     * constructor rejects any deviation from the closed shape (R2).
     */
    public record Record(int formatVersion, SemanticCapability capability,
                         Target target, List<Item> items) {

        public Record {
            Objects.requireNonNull(capability, "capability must not be null");
            Objects.requireNonNull(target, "target must not be null");
            items = List.copyOf(items);
            validate(items, capability, target);
        }

        private static void validate(List<Item> items, SemanticCapability capability,
                                     Target target) {
            if (items.size() != Gate.values().length) {
                throw new IllegalArgumentException("the promotion gate record for "
                    + capability + " × " + target + " must carry exactly twelve items, "
                    + "one per gate in the pinned order; got " + items.size());
            }
            Set<Gate> seen = new LinkedHashSet<>();
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                if (item.gate() != Gate.values()[i]) {
                    throw new IllegalArgumentException("promotion gate item " + i
                        + " for " + capability + " × " + target + " must be "
                        + Gate.values()[i] + " in the pinned order; got " + item.gate());
                }
                if (!seen.add(item.gate())) {
                    throw new IllegalArgumentException("duplicate promotion gate "
                        + item.gate() + " for " + capability + " × " + target);
                }
            }
        }

        /** The single item for a gate: {@code null} for a malformed record only. */
        public Item item(Gate gate) {
            for (Item item : items) {
                if (item.gate() == gate) {
                    return item;
                }
            }
            return null;
        }

        /** Whether all twelve items are {@code PASS}. */
        public boolean allPass() {
            return items.stream().allMatch(item -> item.status() == Status.PASS);
        }
    }

    /**
     * The release-owned E12 promotion gate records (the "T5 records"):
     * one record per pair of {@link
     * deal.semantic.ReleaseConfiguration#activationPromotions()}, all
     * twelve items {@code PASS}, with item 1's activation condition
     * satisfied by the E12 unit's own flip (the first-promotion
     * bootstrap). The evidence locators name the in-tree evidence each
     * item consumes; the gate-run release-action validation resolves
     * every locator before composing any transition.
     */
    public static final List<Record> E12_GATE_RECORDS = buildE12Records();

    private static List<Record> buildE12Records() {
        List<Record> records = new ArrayList<>();
        records.add(record(SemanticCapability.FOUNDATION_VALUES, Target.LUAJIT));
        records.add(record(SemanticCapability.FOUNDATION_VALUES, Target.JVM));
        records.add(record(SemanticCapability.SIGNED_INT32, Target.LUAJIT));
        records.add(record(SemanticCapability.SIGNED_INT32, Target.JVM));
        return List.copyOf(records);
    }

    private static Record record(SemanticCapability capability, Target target) {
        boolean int32 = capability == SemanticCapability.SIGNED_INT32;
        List<Item> items = new ArrayList<>(Gate.values().length);
        for (Gate gate : Gate.values()) {
            items.add(new Item(gate, Status.PASS, locators(capability, gate, int32),
                detail(capability, gate, int32)));
        }
        return new Record(FORMAT_VERSION, capability, target, items);
    }

    private static List<String> locators(SemanticCapability capability, Gate gate,
                                         boolean int32) {
        return switch (gate) {
            case PROFILE_FOUNDATION -> List.of(
                "deal/semantic/ReleaseConfiguration.java (E12 activation release action)",
                "deal/semantic/CompilerProfileProvider.java (the A1 profile matrix)",
                "deal/semantic/MigrationPlanner.java (F4 routing rules)",
                int32
                    ? "test/FoundationIntegrationTest.java (SignedInt32Corpus four-way "
                        + "agreement; retained routes + common consumers)"
                    : "test/FoundationIntegrationTest.java (FOUNDATION_VALUES "
                        + "conformance cases)");
            case COMPLETE_CLOSURE -> List.of(
                "deal/test/conformance/DifferentialGate.java (CommonClosurePlan corpus "
                    + "dispatch)",
                "deal/semantic/SemanticLowerer.java (complete module lowering)",
                "test/conformance/ (the on-disk case corpus)");
            case OUTCOMES_EFFECTS -> List.of(
                "deal/test/conformance/SidecarExpectations.java (explicit per-case "
                    + "outcome/effect expectations)",
                "test/conformance/**/*.json (runtime sidecars)");
            case OPERATION_SNAPSHOT -> List.of(
                "deal/semantic/ir/OperationContractSnapshot.java (canonical "
                    + "operation-contract snapshots and digests)",
                "test/SemanticIrValidatorTest.java (validator battery)");
            case THREE_WAY_TRACES -> List.of(
                "deal/test/conformance/DifferentialGateLanesCorpusTest.java (the "
                    + "designated converged subset passes byte-exact on all lanes)");
            case RELATION_SEEDS -> List.of(
                "deal/test/conformance/JsonAbsorptionNegativeControls.java (the "
                    + "oracle-negative control battery)",
                "deal/test/conformance/DifferentialGateLanesCorpusTest.java (the "
                    + "controlled divergence experiments)");
            case HISTORICAL_TESTS -> List.of(
                "test/HistoricalRegressionCatalog.java (pinned historical regressions "
                    + "in their declared authorities)",
                "test/LegacyProfileRegressionCatalog.java (legacy-profile rows with "
                    + "additive v1.2 replacements)",
                "test/HistoricalRegressionCatalogTest.java (the gate-run catalog "
                    + "battery)");
            case RETAINED_ASSIGNMENTS -> List.of(
                "test/LegacyCapabilityCatalog.java (validated unsupported-legacy-slice "
                    + "assignments)");
            case ROLLBACK_REJECTION -> List.of(
                "test/FoundationIntegrationTest.java (the rollback observation: "
                    + "plan-time reroute keeps DEAL_V1_2_INT32)",
                "test/JvmBackendTest.java (retained rejection classes)",
                "test/BackendConformanceTest.java (SameAsShared / BackendReject "
                    + "assignments)");
            case MIXED_ROUTE_ABI -> List.of(
                "test/FoundationIntegrationTest.java (shared/legacy ABI edge "
                    + "validation)",
                "deal/semantic/TargetAbiValidator.java (the ABI validator)",
                "test/StagingPublicationTest.java (atomic publication)");
            case REAL_ARTIFACTS -> List.of(
                "test/JvmBackendTest.java (javac --release 25 -proc:none compile + "
                    + "java run)",
                "test/LuaBackendIntegrationTest.java (pinned LuaJIT load/run)",
                "run_tests.sh (the release gate)");
            case FULL_GATE -> List.of(
                "run_tests.sh (exit 0 with all suites green)");
        };
    }

    private static String detail(SemanticCapability capability, Gate gate,
                                 boolean int32) {
        return switch (gate) {
            case PROFILE_FOUNDATION -> int32
                ? "the E12 activation unit's own flip satisfies the activation "
                    + "condition (first-promotion bootstrap); the retained LuaJIT and "
                    + "retained JVM signed32 routes pass the SignedInt32Corpus "
                    + "four-way agreement and the common consumers agree on the "
                    + "common int operation cases"
                : "the E12 activation unit's own flip satisfies the activation "
                    + "condition (first-promotion bootstrap); the profile-aware "
                    + "frontend and route foundation exist (D2 step 2 readiness)";
            case COMPLETE_CLOSURE ->
                "the corpus closure is common-lowerable under COMMON_SHADOW + "
                    + "DEAL_V1_2_INT32 with the required capability at least SHADOW "
                    + "for all three consumers; no STDLIB_TIME_CONFLICT module";
            case OUTCOMES_EFFECTS ->
                "every case carries explicit outcome/effect expectations (sidecar "
                    + "data, never inferred)";
            case OPERATION_SNAPSHOT ->
                "every trace event validates against its exact IR operation snapshot, "
                    + "digest, and parentOpId nesting";
            case THREE_WAY_TRACES ->
                "oracle, shared LuaJIT, and shared JVM traces match event-for-event";
            case RELATION_SEEDS ->
                "applicable relation seeds and the negative-control battery are "
                    + "green (manifest-derived applicability; not-applicable is not "
                    + "pass)";
            case HISTORICAL_TESTS ->
                "the HistoricalRegressionCatalog pins resolve and stay active in "
                    + "their declared authorities with expectation baselines "
                    + "unchanged; the LegacyProfileRegressionCatalog rows pass "
                    + "unchanged with additive v1.2 replacements";
            case RETAINED_ASSIGNMENTS ->
                "every UnsupportedLegacySlice validates through the "
                    + "LegacyCapabilityCatalog and the attainable-evidence floors hold";
            case ROLLBACK_REJECTION ->
                "at least one SameAsShared case per retained supported operation "
                    + "family and one exact rejection case per retained rejection "
                    + "class";
            case MIXED_ROUTE_ABI ->
                "mixed-route cases with real retained/shared artifacts and "
                    + "validated ABI edges/route/artifact assertions";
            case REAL_ARTIFACTS ->
                "Lua artifacts load and execute under pinned LuaJIT; JVM artifacts "
                    + "compile with javac --release 25 -proc:none and execute; no "
                    + "stale artifacts";
            case FULL_GATE ->
                "./run_tests.sh exits 0 with all historical, direct-Lua, golden, "
                    + "known-fail, and conformance suites green";
        };
    }
}
