package deal.test;

import deal.codegen.lua.LuaSemanticEmitter;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticTable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical failure-projection authority's arm-table battery
 * (canonical-failure-projection-authority P1/P2 and Verification 2/4): the
 * closed arm table beside its rows, the fail-closed row/arm consistency
 * invariant with its negative controls, the corrected template
 * enumeration, the two closed projections, and the serialized arm table
 * the emitted Lua prelude carries.
 */
public class FailureArmAuthorityTest {

    private static int passed = 0;
    private static int failed = 0;
    private static String armsChunk;
    private static String walkChunk;
    private static String walkHostChunk;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void expectDefect(Runnable action, String message) {
        try {
            action.run();
            failed++;
            System.err.println("FAIL: " + message + " (no defect thrown)");
        } catch (BoundaryExecutor.Defect defect) {
            passed++;
        } catch (RuntimeException other) {
            failed++;
            System.err.println("FAIL: " + message + " (threw "
                + other.getClass().getSimpleName() + ": " + other.getMessage() + ")");
        }
    }

    private static void expectIllegalState(Runnable action, String message) {
        try {
            action.run();
            failed++;
            System.err.println("FAIL: " + message + " (no failure thrown)");
        } catch (IllegalStateException expected) {
            passed++;
        } catch (RuntimeException other) {
            failed++;
            System.err.println("FAIL: " + message + " (threw "
                + other.getClass().getSimpleName() + ": " + other.getMessage() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        armsChunk = null;
        walkChunk = null;
        walkHostChunk = null;
        testClosedArmTable();
        testConsistencyInvariantNegatives();
        testCorrectedTemplates();
        testClosedProjections();
        testRendersAndMarkedArms();
        testEmittedTable();
        testCarrierKindStringActual();
        testCompletionFamilyDrive();
        testTypedBoundaryBytesCarrier();
        testStdJsonArmDrive();
        testInt32AndSqrtArmFamily();
        testIntLadderWrongKindCell();
        testBoundsAndBytesArms();
        testDeclaredFieldShapeNegatives();
        testTypedBoundaryClassSpellingNegatives();
        testHostInnerReasonDescriptorValidation();
        testEmittedParameterContract();
        testNegativeSingleSourceControl();
        testWalkArmThreeConsumerDrive();
        testAbsentOriginFailsClosed();
        testWalkArmOracleDispatchLeg();
        testLandedPresentNullRead();
        testWalkOracleNumericPositions();
        testWalkOracleKindPositions();
        testMovedElementCell();
        testRuntimeCarrierProjection();
        testRuntimeNullSentinelAtWalk();
        testRuntimeMissingSentinelAtWalk();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + " (expected "
            + expected + ", got " + actual + ")");
    }

    /** One rendered failure tuple (the arm rendering contract's projection). */
    private record Tuple(String code, String message, String span, String expected,
                         String actual) {
    }

    /**
     * The three-consumer comparison helper: the first differing field's name
     * ({@code code}, {@code message}, {@code span}, {@code expected},
     * {@code actual}), or {@code null} when the tuples agree. A consumer that
     * composes its own text or picks its own span is reported by the name of
     * the field it composed (Verification 3).
     */
    private static String firstDifferingField(Tuple reference, Tuple candidate) {
        if (!java.util.Objects.equals(reference.code(), candidate.code())) {
            return "code";
        }
        if (!java.util.Objects.equals(reference.message(), candidate.message())) {
            return "message";
        }
        if (!java.util.Objects.equals(reference.span(), candidate.span())) {
            return "span";
        }
        if (!java.util.Objects.equals(reference.expected(), candidate.expected())) {
            return "expected";
        }
        if (!java.util.Objects.equals(reference.actual(), candidate.actual())) {
            return "actual";
        }
        return null;
    }

    private static Tuple tupleOf(BoundaryFailure failure, String span) {
        return new Tuple(failure.code().name(), failure.message(), span,
            failure.expected(), failure.actual());
    }

    /** The oracle's rendered failure of one boundary check, or a defect. */
    private static BoundaryFailure oracleFailure(deal.semantic.ir.BoundaryOutcome outcome,
                                                 String what) {
        if (outcome instanceof deal.semantic.ir.BoundaryOutcome.Fail fail) {
            return fail.failure();
        }
        throw new IllegalStateException(what + " did not fail: " + outcome);
    }

    /**
     * The declared arm list with one hand-built arm substituted for the
     * declared arm of the same id — the data-driven completeness subject of
     * the retargeted marker negative (W5).
     */
    private static List<FailureArm> markedArms(FailureArm replacement) {
        List<FailureArm> arms = new ArrayList<>();
        for (FailureArm declared : FailureContractRegistry.arms().values()) {
            arms.add(declared.id() == replacement.id() ? replacement : declared);
        }
        return arms;
    }

    /** The JVM runtime's failure of one check, or {@code null} when it passed. */
    private static deal.codegen.jvm.JvmRuntime.DealError jvmFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (deal.codegen.jvm.JvmRuntime.DealError error) {
            return error;
        }
    }

    // =========================================================================
    // 1. The closed arm table and the row binding
    // =========================================================================

    static void testClosedArmTable() {
        System.out.println("-- the closed arm table and the row/arm binding --");
        Map<FailureArmId, FailureArm> arms = FailureContractRegistry.arms();
        check(arms.size() == FailureArmId.values().length,
            "one immutable arm per closed arm id; got " + arms.size());
        check(arms.keySet().equals(EnumSet.allOf(FailureArmId.class)),
            "every closed arm id is declared exactly once");
        for (FailureArmId id : FailureArmId.values()) {
            FailureArm arm = FailureContractRegistry.arm(id);
            FailurePolicyRow row = FailureContractRegistry.row(arm.policy());
            check(arm == arms.get(id), "arm(" + id + ") and arms() agree");
            check(row.templates().get(arm.templateIndex()).equals(arm.template()),
                "arm " + id + " carries its row's template at its index");
            check(FailureContractRegistry.armForTemplate(arm.policy(), arm.templateIndex())
                    == arm,
                "armForTemplate(" + arm.policy() + ", " + arm.templateIndex()
                    + ") resolves " + id);
            for (String parameter : arm.parameters()) {
                check(arm.parameterSources().containsKey(parameter),
                    "arm " + id + " declares a source for {" + parameter + "}");
            }
        }
        FailureContractRegistry.checkArmConsistency(FailureContractRegistry.rows(),
            new ArrayList<>(arms.values()));
        passed++;
    }

    // =========================================================================
    // 2. The fail-closed consistency invariant and its negative controls
    // =========================================================================

    static void testConsistencyInvariantNegatives() {
        System.out.println("-- the fail-closed row/arm consistency invariant --");
        Map<FailurePolicyId, FailurePolicyRow> rows = FailureContractRegistry.rows();
        List<FailureArm> arms = new ArrayList<>(FailureContractRegistry.arms().values());

        // An unbound retained template: the TYPE_DESCRIPTOR row loses its
        // kind arm, leaving the retained template bound to nothing.
        List<FailureArm> unbound = new ArrayList<>();
        for (FailureArm arm : arms) {
            if (arm.id() != FailureArmId.TYPED_BOUNDARY_KIND) {
                unbound.add(arm);
            }
        }
        expectIllegalState(() -> FailureContractRegistry.checkArmConsistency(rows, unbound),
            "an unbound retained template fails closed");

        // A foreign template: the kind arm carries a text its row does not.
        FailureArm kind = FailureContractRegistry.arm(FailureArmId.TYPED_BOUNDARY_KIND);
        List<FailureArm> foreign = new ArrayList<>(arms);
        foreign.set(foreign.indexOf(kind), new FailureArm(kind.id(), kind.policy(),
            kind.templateIndex(), "expected {kind}, got {actual}", kind.parameters(),
            kind.parameterSources(), kind.expectedSource(), null, kind.actualProjection(),
            kind.origin(), kind.scope(), kind.code()));
        expectIllegalState(() -> FailureContractRegistry.checkArmConsistency(rows, foreign),
            "a foreign template fails closed");

        // A duplicate binding and a missing arm for a retained template.
        List<FailureArm> duplicate = new ArrayList<>(arms);
        duplicate.add(kind);
        expectIllegalState(
            () -> FailureContractRegistry.checkArmConsistency(rows, duplicate),
            "a duplicate arm binding fails closed");
        List<FailureArm> missing = new ArrayList<>(arms);
        missing.remove(FailureContractRegistry.arm(FailureArmId.CLASS_EXTRA_FIELD));
        expectIllegalState(() -> FailureContractRegistry.checkArmConsistency(rows, missing),
            "a missing arm for a retained template fails closed");
    }

    // =========================================================================
    // 3. The corrected template enumeration
    // =========================================================================

    static void testCorrectedTemplates() {
        System.out.println("-- the corrected templates (P1) --");
        FailurePolicyRow type = FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR);
        check(type.templates().equals(List.of("expected {kind}",
            "expected string, got invalid Unicode scalar encoding",
            "expected instance of {expected}, got {actual}",
            "expected string, got invalid UTF-8 encoding",
            "expected string, got UTF-16 surrogate code point")),
            "TYPE_DESCRIPTOR pins the kind arm, the invalid-Unicode arm, the identity "
                + "arm, and the two inner-only host string arms: " + type.templates());
        check(FailureContractRegistry.arm(FailureArmId.HOST_STRING_INVALID_UTF8).isInnerOnly()
                && FailureContractRegistry.arm(FailureArmId.HOST_STRING_SURROGATE).isInnerOnly()
                && FailureContractRegistry.arm(FailureArmId.HOST_STRING_INVALID_UTF8)
                    .policy() == FailurePolicyId.TYPE_DESCRIPTOR,
            "the two host string-carrier arms are INNER_ONLY TYPE_DESCRIPTOR arms");
        check(FailureContractRegistry.row(FailurePolicyId.HOST_PARAMETER).templates()
                .equals(List.of("parameter {index} type mismatch: {inner}")),
            "HOST_PARAMETER renders the composite parameter arm");
        check(FailureContractRegistry.row(FailurePolicyId.HOST_SYNC_RETURN).templates()
                .equals(List.of(
                    "return value 1 type mismatch: expected {expected}, got nothing",
                    "return value 1 type mismatch: {inner}")),
            "HOST_SYNC_RETURN keeps its landed order and the composite return arm");
        check(FailureContractRegistry.row(FailurePolicyId.ASYNC_OPERATION_HANDLE).templates()
                .equals(List.of("host async function must return an async operation, "
                    + "got {actual}")),
            "ASYNC_OPERATION_HANDLE renders the async-shape arm");
        // The walk arm's binding (jsonable-tojson-walk-arm-binding W1/W2):
        // the closed typed-boundary projection, the closed call-expression
        // origin convention, and the typed-boundary actual source; the
        // marker is gone from the declared table.
        FailureArm walk = FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK);
        check(walk.policy() == FailurePolicyId.JSON_TO_ERROR
                && walk.templateIndex() == 0
                && walk.template()
                    .equals("value at {fieldPath} is not JSON serializable: {actual}")
                && walk.parameters().equals(List.of("fieldPath", "actual"))
                && walk.parameterSources().equals(Map.of(
                    "fieldPath", FailureArm.ParameterSource.FIELD_PATH,
                    "actual", FailureArm.ParameterSource.TYPED_BOUNDARY_ACTUAL))
                && walk.expectedSource() == FailureArm.ExpectedSource.NONE
                && walk.actualProjection() == FailureArm.ActualProjection.TYPED_BOUNDARY
                && walk.origin() == FailureArm.OriginConvention.CALL_EXPRESSION
                && walk.scope() == FailureArm.RenderScope.TOP_LEVEL
                && !walk.isSiblingOwned(),
            "the JSON_TO_WALK arm is bound: TYPED_BOUNDARY actual, the "
                + "TYPED_BOUNDARY_ACTUAL source for {actual}, CALL_EXPRESSION, TOP_LEVEL, "
                + "not sibling-owned");
        check(FailureContractRegistry.arms().values().stream()
                .noneMatch(FailureArm::hasSiblingOwnedBindingSlot),
            "no declared arm carries a sibling-owned marker in its projection binding, "
                + "its origin convention, or its parameter sources");
        // The cycle arm's declaration (W4): the appended row template, no
        // parameters, no expected/actual, the call-expression origin.
        FailureArm cycle = FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK_CYCLE);
        check(cycle.policy() == FailurePolicyId.JSON_TO_ERROR
                && cycle.templateIndex() == 2
                && cycle.template().equals("cyclic value cannot be encoded as JSON")
                && cycle.parameters().isEmpty()
                && cycle.parameterSources().isEmpty()
                && cycle.expectedSource() == FailureArm.ExpectedSource.NONE
                && cycle.pinnedExpectedText() == null
                && cycle.actualProjection() == FailureArm.ActualProjection.NONE
                && cycle.origin() == FailureArm.OriginConvention.CALL_EXPRESSION
                && cycle.scope() == FailureArm.RenderScope.TOP_LEVEL,
            "the JSON_TO_WALK_CYCLE arm declares the pinned cycle text with no "
                + "parameters, no expected/actual, CALL_EXPRESSION, TOP_LEVEL");
        check(FailureContractRegistry.row(FailurePolicyId.JSON_TO_ERROR).templates()
                .equals(List.of("value at {fieldPath} is not JSON serializable: {actual}",
                    "unsupported type for JSON encoding: {actual}",
                    "cyclic value cannot be encoded as JSON"))
                && FailureContractRegistry.armForTemplate(
                    FailurePolicyId.JSON_TO_ERROR, 0).id() == FailureArmId.JSON_TO_WALK
                && FailureContractRegistry.armForTemplate(
                    FailurePolicyId.JSON_TO_ERROR, 1).id()
                    == FailureArmId.JSON_STRINGIFY_UNSUPPORTED
                && FailureContractRegistry.armForTemplate(
                    FailurePolicyId.JSON_TO_ERROR, 2).id()
                    == FailureArmId.JSON_TO_WALK_CYCLE
                && FailureContractRegistry.row(FailurePolicyId.INT32_RESULT).templates()
                    .equals(List.of("int out of safe range")),
            "the JSON_TO_ERROR row carries the three templates with the walk template "
                + "first, the std/json template second, and the cycle template third; "
                + "the INT32_RESULT row data stays as landed");
    }

    // =========================================================================
    // 4. The closed projections
    // =========================================================================

    static void testClosedProjections() {
        System.out.println("-- the two closed projections --");
        check(FailureProjections.typedBoundaryToken(
                deal.semantic.ir.ActualKind.MISSING, null).equals("nil")
                && FailureProjections.typedBoundaryToken(
                    deal.semantic.ir.ActualKind.CLASS, "@src/app/User")
                    .equals("@src/app/User")
                && FailureProjections.typedBoundaryToken(
                    deal.semantic.ir.ActualKind.NULL, null).equals("null"),
            "the typed-boundary projection renders nil for the absent marker and the "
                + "carried canonical class atom for a class instance");
        check(FailureProjections.completionToken(deal.semantic.ir.ActualKind.INT, null)
                .equals("number")
                && FailureProjections.completionToken(
                    deal.semantic.ir.ActualKind.STRING, null).equals("string"),
            "the completion variant maps every numeric carrier to number");
        check(FailureProjections.carrierKindToken(
                FailureProjections.CarrierKind.BYTES).equals("bytes")
                && FailureProjections.carrierKindToken(
                    FailureProjections.CarrierKind.DEAL_FUNCTION).equals("function"),
            "the carrier-kind projection carries the std/json arm's two pinned members "
                + "(bytes, function)");
        check(FailureProjections.typedBoundaryToken(
                deal.semantic.ir.ActualKind.BYTES, null).equals("bytes"),
            "the typed-boundary projection projects the bytes carrier");
        check(FailureProjections.carrierKindToken(
                FailureProjections.CarrierKind.ABSENT).equals("nil")
                && FailureProjections.carrierKindToken(
                    FailureProjections.CarrierKind.LANGUAGE_NULL).equals("table")
                && FailureProjections.carrierKindToken(
                    FailureProjections.CarrierKind.HOST_FUNCTION).equals("function"),
            "the carrier-kind projection renders nil for the absent marker and table "
                + "for the DEAL-null sentinel");
        deal.semantic.ir.RuntimeDescriptor classDescriptor =
            deal.semantic.ir.RuntimeDescriptor.parseCanonicalText("@a/B");
        check(FailureProjections.kindText(classDescriptor).equals("class instance")
                && FailureProjections.kindToken(classDescriptor).equals("class"),
            "the class kind text is class instance while its expected token is class");
        check(FailureProjections.kindReason(
                deal.semantic.ir.RuntimeDescriptor.Int.INSTANCE).equals("expected int")
                && FailureProjections.refinementReason("int", "NaN")
                    .equals("expected int, got NaN")
                && FailureProjections.stringCarrierReason(false)
                    .equals("expected string, got invalid UTF-8 encoding")
                && FailureProjections.stringCarrierReason(true)
                    .equals("expected string, got UTF-16 surrogate code point"),
            "the host inner-reason render carries the descriptor, refinement, and "
                + "string-carrier entries");
    }

    // =========================================================================
    // 5. Arm renders and the marked arms fail closed
    // =========================================================================

    static void testRendersAndMarkedArms() {
        System.out.println("-- arm renders and the marked arms --");
        BoundaryFailure kind = FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int", "string",
            null);
        check(kind.message().equals("expected int")
                && kind.expected().equals("int") && kind.actual().equals("string")
                && kind.code() == deal.diagnostics.DiagnosticCode.E8001,
            "the kind arm renders its own suffix-less template: " + kind.message());
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_STRING_SURROGATE,
                Map.of(), null, null, null),
            "an INNER_ONLY arm rendered at a failure site fails closed");
        expectDefect(() -> FailureContractRegistry.renderInner(
                FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int")),
            "a top-level arm rendered as an inner reason fails closed");
        // The retargeted marker negative (W5): the only remaining entry
        // that can receive a hand-built marked arm is the data-driven
        // completeness check, and the constructed marked arms fail closed
        // in each marked binding slot.
        FailureArm markedProjection = new FailureArm(FailureArmId.TYPED_BOUNDARY_KIND,
            FailurePolicyId.TYPE_DESCRIPTOR, 0, "expected {kind}",
            List.of("kind"), Map.of("kind", FailureArm.ParameterSource.KIND_TEXT),
            FailureArm.ExpectedSource.KIND_TOKEN, null,
            FailureArm.ActualProjection.SIBLING_OWNED,
            FailureArm.OriginConvention.BOUNDARY_CELL, FailureArm.RenderScope.TOP_LEVEL,
            null);
        expectIllegalState(() -> FailureContractRegistry.checkArmConsistency(
                FailureContractRegistry.rows(),
                markedArms(markedProjection)),
            "a hand-built arm whose projection binding is SIBLING_OWNED fails the "
                + "completeness check closed");
        FailureArm markedOrigin = new FailureArm(FailureArmId.JSON_TO_WALK,
            FailurePolicyId.JSON_TO_ERROR, 0, "value at {fieldPath} is not JSON "
                + "serializable: {actual}", List.of("fieldPath", "actual"),
            Map.of("fieldPath", FailureArm.ParameterSource.FIELD_PATH, "actual",
                FailureArm.ParameterSource.TYPED_BOUNDARY_ACTUAL),
            FailureArm.ExpectedSource.NONE, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.SIBLING_OWNED, FailureArm.RenderScope.TOP_LEVEL,
            null);
        expectIllegalState(() -> FailureContractRegistry.checkArmConsistency(
                FailureContractRegistry.rows(), markedArms(markedOrigin)),
            "a hand-built arm whose origin convention is SIBLING_OWNED fails the "
                + "completeness check closed");
        FailureArm markedSource = new FailureArm(FailureArmId.JSON_TO_WALK,
            FailurePolicyId.JSON_TO_ERROR, 0, "value at {fieldPath} is not JSON "
                + "serializable: {actual}", List.of("fieldPath", "actual"),
            Map.of("fieldPath", FailureArm.ParameterSource.FIELD_PATH, "actual",
                FailureArm.ParameterSource.SIBLING_OWNED_ACTUAL),
            FailureArm.ExpectedSource.NONE, null,
            FailureArm.ActualProjection.TYPED_BOUNDARY,
            FailureArm.OriginConvention.CALL_EXPRESSION, FailureArm.RenderScope.TOP_LEVEL,
            null);
        expectIllegalState(() -> FailureContractRegistry.checkArmConsistency(
                FailureContractRegistry.rows(), markedArms(markedSource)),
            "a hand-built arm whose parameter source is SIBLING_OWNED_ACTUAL fails the "
                + "completeness check closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.INT32_RANGE,
                Map.of(), "int", null, null),
            "a supplied expected field on a field-less arm fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
                Map.of("kind", "int", "extra", "x"), "int", "string", null),
            "an extra named parameter fails closed");
        expectDefect(() -> FailureContractRegistry.armForTemplate(
                FailurePolicyId.NO_DEAL_FAILURE, 0),
            "a template with no bound arm fails closed");
    }

    // =========================================================================
    // 6. The emitted arm table equals the canonical serialization
    // =========================================================================

    static void testEmittedTable() {
        System.out.println("-- the emitted Lua arm table --");
        String lua = emittedArmsChunk();
        List<String> canonical = FailureContractRegistry.canonicalArmSerialization();
        check(canonical.size() == FailureArmId.values().length,
            "the canonical serialization carries every declared arm");
        for (FailureArm arm : FailureContractRegistry.arms().values()) {
            String line = arm.id() + "|" + FailureContractRegistry.codeOf(arm) + "|"
                + arm.template() + "|" + arm.expectedSource() + "|"
                + arm.actualProjection() + "|" + arm.scope() + "|" + arm.origin() + "|"
                + FailureContractRegistry.parameterSourcesText(arm);
            String pinned = arm.pinnedExpectedText();
            if (pinned != null) {
                line = line + "|" + pinned;
            }
            check(canonical.contains(line),
                "the canonical serialization carries arm " + arm.id()
                    + " with its scope, projection binding, parameter sources, and "
                    + "pinned expected text");
            String expectedEntry = "[\"" + arm.id() + "\"] = {c="
                + quote(FailureContractRegistry.codeOf(arm)) + ", t="
                + quote(arm.template()) + ", e=" + quote(arm.expectedSource().name())
                + ", a=" + quote(arm.actualProjection().name()) + ", s="
                + quote(arm.scope().name()) + ", o=" + quote(arm.origin().name())
                + ", k=" + quote(FailureContractRegistry.parameterSourcesText(arm));
            if (pinned != null) {
                expectedEntry = expectedEntry + ", p=" + quote(pinned);
            }
            expectedEntry = expectedEntry + "}";
            check(lua.contains(expectedEntry),
                "the emitted prelude serializes arm " + arm.id()
                    + " (scope, projection binding, parameter sources, and pinned "
                    + "expected text included); expected " + expectedEntry);
        }
        check(lua.contains("local function __arm(id, values, origin, expected, actual)"),
            "the prelude renders every failure site through the one arm renderer");

        // The JVM consumer resolves the same closed arm table through the
        // registry (a focused assertion over the arms, never the artifact
        // text): one render per family carries the identical tuple.
        deal.codegen.jvm.JvmRuntime.DealError javaKind = deal.codegen.jvm.JvmRuntime.arm(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "origin",
            "int", "string");
        deal.semantic.ir.BoundaryFailure registryKind =
            FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
                Map.of("kind", "int"), "int", "string", null);
        check(javaKind.code.equals(registryKind.code().name())
                && javaKind.msg.equals(registryKind.message())
                && javaKind.expected.equals(registryKind.expected())
                && javaKind.actual.equals(registryKind.actual()),
            "the JVM arm render resolves the registry's own arm tuple");
        check(FailureProjections.refinementReason("int", "number")
                .equals(deal.codegen.jvm.JvmRuntime.refinementReason("number"))
                && FailureProjections.stringCarrierReason(true)
                    .equals(deal.codegen.jvm.JvmRuntime.stringCarrierReason(true))
                && FailureContractRegistry.arm(FailureArmId.INT32_RANGE).template()
                    .equals(deal.codegen.jvm.JvmRuntime.rangeReason()),
            "the JVM host inner-reason helpers resolve the registry's own texts "
                + "(the int refinement, the surrogate string-carrier arm, the "
                + "signed32-range pass-through)");
    }

    /** One Lua double-quoted string literal (the same escaping as the artifacts). */
    private static String luaString(String text) {
        return quote(text);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static HostDeclarationSurface emptySurface() {
        return new HostDeclarationSurface(Map.of());
    }

    /** The production chunk of the arm-only project (the emitted prelude). */
    private static String emittedArmsChunk() {
        if (armsChunk == null) {
            armsChunk = emitArmsChunk();
        }
        return armsChunk;
    }

    private static String emitArmsChunk() {
        deal.semantic.ir.ModuleId entry = new deal.semantic.ir.ModuleId("arms");
        deal.semantic.ir.LoweredModuleUnit unit = new deal.semantic.ir.LoweredModuleUnit(
            deal.semantic.ir.LoweredModuleUnit.FORMAT_VERSION,
            deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, entry, "hash", "context",
            java.util.Set.of(), Map.of(), Map.of(), Map.of(),
            new deal.semantic.ir.ModuleInitPlan(List.of(),
                new deal.semantic.ir.BlockId(0)),
            new deal.semantic.ir.ExportPlan(List.of()), Map.of());
        Map<deal.semantic.ir.ModuleId, deal.semantic.ir.LoweredModuleUnit> modules =
            new LinkedHashMap<>();
        modules.put(entry, unit);
        deal.semantic.ir.ProjectInterfaceIndex index =
            new deal.semantic.ir.ProjectInterfaceIndex(
                deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION,
                Map.of(entry, new deal.semantic.ir.ExternalModuleInterface(entry,
                    deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(),
                    List.of(), List.of(),
                    deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        deal.semantic.ir.ExecutableLoweredProject project =
            new deal.semantic.ir.ExecutableLoweredProject(
                deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, index, modules, entry);
        Map<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable> tables =
            new LinkedHashMap<>();
        tables.put(entry, new deal.semantic.ir.StructuredBodyTable(
            Map.of(new deal.semantic.ir.BlockId(0), List.of()),
            Map.of()));
        return LuaSemanticEmitter.emitProductionProject(project, tables, Map.of(),
            emptySurface());
    }

    // =========================================================================
    // 7. The carrier-kind string member (the host string actual)
    // =========================================================================

    /**
     * The carrier-kind projection's string member (P2 item 2): a string
     * carrier — a valid Unicode scalar sequence and an invalid one alike —
     * projects the closed {@code string} token at the host cells, exactly as
     * the unchanged runtimes and both production artifacts do. The pinned
     * host fixtures are {@code host-abi/host-bytes-return-mismatch-e8010},
     * {@code host-bad-return}, {@code host-null-return-bad},
     * {@code host-prewrapped-bad}, {@code host-invalid-utf8-e8010}, and
     * {@code host-surrogate-utf8-e8010} (all pinned {@code actual: string});
     * the artifact legs of the named fixture run in
     * {@code HostCallRealizationTest}'s pinned fixture set.
     */
    static void testCarrierKindStringActual() {
        System.out.println("-- the carrier-kind string member (the host string actual) --");
        checkEq("string", FailureProjections.carrierKindToken(
            FailureProjections.CarrierKind.STRING),
            "the carrier-kind projection has the closed string token");
        // host-bytes-return-mismatch-e8010: the declared bytes return cell
        // with a string value (pinned actual "string").
        BoundaryFailure bytesReturn = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.Bytes.INSTANCE,
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none()),
            "the host bytes return cell");
        checkEq("E8010", bytesReturn.code().name(), "the pinned host return code");
        checkEq("return value 1 type mismatch: expected bytes", bytesReturn.message(),
            "the pinned host return message");
        checkEq("bytes", bytesReturn.expected(), "the declared cell descriptor");
        checkEq("string", bytesReturn.actual(),
            "the oracle projects the carrier-kind string actual");
        // host-bad-return / host-null-return-bad / host-prewrapped-bad: the
        // same string actual at the declared int/null return cells.
        for (RuntimeDescriptor descriptor : List.of(RuntimeDescriptor.Int.INSTANCE,
                RuntimeDescriptor.Null.INSTANCE)) {
            BoundaryFailure wrong = oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.HOST_SYNC_RETURN, descriptor,
                BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none()),
                "the host return cell of " + descriptor);
            checkEq("string", wrong.actual(), "a string value at the " + descriptor
                + " return cell projects the carrier-kind string actual");
            checkEq("return value 1 type mismatch: expected "
                + FailureProjections.kindText(descriptor), wrong.message(),
                "the host return composite carries the descriptor's kind reason");
        }
        // host-invalid-utf8-e8010 / host-surrogate-utf8-e8010: the invalid
        // Unicode classification is a string carrier too.
        BoundaryFailure invalid = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.of(ActualKind.INVALID_UNICODE), BoundaryContext.none()),
            "the host invalid-Unicode return cell");
        checkEq("return value 1 type mismatch: expected string, got invalid UTF-8 "
            + "encoding", invalid.message(),
            "the host string-carrier inner reason is the INNER_ONLY arm's own text");
        checkEq("string", invalid.actual(),
            "an invalid-Unicode view is a string carrier on the carrier-kind projection");
        // The host parameter cell of the same shape (host-bytes-param-mismatch's
        // sibling int cell with a string value).
        BoundaryFailure parameter = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_PARAMETER, RuntimeDescriptor.Int.INSTANCE,
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.parameter(1)),
            "the host int parameter cell");
        checkEq("E8010", parameter.code().name(), "the pinned host parameter code");
        checkEq("parameter 1 type mismatch: expected int", parameter.message(),
            "the host parameter composite carries the descriptor's kind reason");
        checkEq("string", parameter.actual(),
            "the oracle projects the carrier-kind string actual at the parameter cell");
    }

    // =========================================================================
    // 8. The completion family drive: one tuple from the three consumers
    // =========================================================================

    /**
     * The completion family's class/array cells (the {@code ASYNC_COMPLETION}
     * kind arm): one identical {@code (code, message, expected, actual)} tuple
     * from the oracle, the JVM runtime, and the emitted Lua prelude under
     * {@code luajit}. The arm renders the descriptor's closed kind text in
     * its message and the kind token as its expected field — the unchanged
     * runtime matcher's own form {@code expected class instance}/{@code class}
     * and {@code expected array}/{@code array} — with the pinned corpus
     * completion form ({@code expected string}/{@code string}, the numeric
     * carrier projecting {@code number}) as the control.
     */
    static void testCompletionFamilyDrive() throws Exception {
        System.out.println("-- the completion family: class/array cells from the three "
            + "consumers --");
        String span = FailureContractRegistry.arm(FailureArmId.ASYNC_COMPLETION_KIND)
            .origin().name();
        // The oracle leg: the two named canonical descriptors and the pinned
        // string control.
        Tuple oracleClass = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.ASYNC_COMPLETION,
            RuntimeDescriptor.parseCanonicalText("@src/app/User"),
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none()),
            "the class completion cell"), span);
        checkEq(new Tuple("E8001", "expected class instance", span, "class", "string"),
            oracleClass, "the oracle renders the class completion kind arm");
        Tuple oracleArray = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.ASYNC_COMPLETION,
            RuntimeDescriptor.parseCanonicalText("[int]"),
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none()),
            "the array completion cell"), span);
        checkEq(new Tuple("E8001", "expected array", span, "array", "string"),
            oracleArray, "the oracle renders the array completion kind arm");
        Tuple oraclePinned = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.ASYNC_COMPLETION, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofInt(5), BoundaryContext.none()),
            "the pinned string completion cell"), span);
        checkEq(new Tuple("E8001", "expected string", span, "string", "number"),
            oraclePinned, "the oracle keeps the pinned host-async-bad completion form");
        // The JVM runtime leg (the same closed arm and helper).
        deal.codegen.jvm.JvmRuntime.DealError jvmClass = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheckCompletion("@src/app/User",
                "table", "abc"));
        check(jvmClass != null, "the JVM completion check fails: " + jvmClass);
        if (jvmClass != null) {
            checkEq(null, firstDifferingField(oracleClass,
                new Tuple(jvmClass.code, jvmClass.msg, span, jvmClass.expected,
                    jvmClass.actual)),
                "the JVM runtime renders the oracle's identical class completion tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmArray = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheckCompletion("array(int)",
                "table", "abc"));
        check(jvmArray != null, "the JVM array completion check fails: " + jvmArray);
        if (jvmArray != null) {
            checkEq(null, firstDifferingField(oracleArray,
                new Tuple(jvmArray.code, jvmArray.msg, span, jvmArray.expected,
                    jvmArray.actual)),
                "the JVM runtime renders the oracle's identical array completion tuple");
        }
        // The emitted Lua prelude leg, under real luajit.
        List<String> luaRows = luaCompletionRows();
        checkEq("class|E8001|class|string|expected class instance", luaRows.get(0),
            "the emitted prelude renders the class completion arm");
        checkEq("array|E8001|array|string|expected array", luaRows.get(1),
            "the emitted prelude renders the array completion arm");
        checkEq("string|E8001|string|number|expected string", luaRows.get(2),
            "the emitted prelude keeps the pinned completion form");
        checkEq("carrier-string|string", luaRows.get(3),
            "the emitted prelude's carrier-kind projection renders the string member");
        // The three consumers' tuples: one comparison, reported by field name.
        String[] parts = luaRows.get(0).split("\\|", -1);
        checkEq(null, firstDifferingField(oracleClass,
            new Tuple(parts[1], parts[4], span, parts[2], parts[3])),
            "the Lua artifact renders the oracle's identical class completion tuple");
        parts = luaRows.get(1).split("\\|", -1);
        checkEq(null, firstDifferingField(oracleArray,
            new Tuple(parts[1], parts[4], span, parts[2], parts[3])),
            "the Lua artifact renders the oracle's identical array completion tuple");
        parts = luaRows.get(2).split("\\|", -1);
        checkEq(null, firstDifferingField(oraclePinned,
            new Tuple(parts[1], parts[4], span, parts[2], parts[3])),
            "the Lua artifact renders the pinned identical completion tuple");
    }

    /**
     * Runs the emitted prelude's completion and carrier-kind renders under
     * {@code luajit}: one {@code label|code|expected|actual|message} row per
     * cell.
     */
    private static List<String> luaCompletionRows() throws Exception {
        return runPreludeProbe("""
            local ok, value = pcall(__bcheck, "@src/app/User", "table", "abc", nil, true)
            row("class", ok, value)
            ok, value = pcall(__bcheck, "array(int)", "table", "abc", nil, true)
            row("array", ok, value)
            ok, value = pcall(__bcheck, "string", "string", 5, nil, true)
            row("string", ok, value)
            print("carrier-string|" .. __carrierKind("abc"))
            """, "completion-probe", 4);
    }

    /**
     * Runs one probe body against the emitted arm-only prelude under
     * {@code luajit}: the prelude (up to its surface return) plus the
     * standard {@code row(label, ok, value)} helper and the caller's body.
     * Returns the printed rows.
     */
    private static List<String> runPreludeProbe(String body, String probeName, int minRows)
            throws Exception {
        return runProbe(emittedArmsChunk(), "arms", body, probeName, minRows);
    }

    /**
     * Runs one probe body against one emitted production chunk under
     * {@code luajit}: the chunk (up to its surface return) plus the standard
     * {@code row(label, ok, value)} helper and the caller's body. Returns the
     * printed rows.
     */
    private static List<String> runProbe(String chunk, String modulePath, String body,
                                         String probeName, int minRows)
            throws Exception {
        return runProbe(chunk, modulePath, body, probeName, minRows, false);
    }

    /**
     * The probe runner with an option to stage the deployed runtime beside
     * the artifact: a probe that drives a runtime-produced carrier (the
     * async handle, the runtime class representation) loads
     * {@code deal/runtime.lua} through {@code require("deal.runtime")},
     * exactly like the emitted artifacts of a chunk that binds the host or
     * bytes surface.
     */
    private static List<String> runProbe(String chunk, String modulePath, String body,
                                         String probeName, int minRows, boolean bindRuntime)
            throws Exception {
        Path workspace = Files.createTempDirectory("failure-arm-" + probeName);
        try {
            if (bindRuntime) {
                Path runtimeTarget = workspace.resolve("deal").resolve("runtime.lua");
                Files.createDirectories(runtimeTarget.getParent());
                Files.copy(Path.of("deal", "runtime.lua"), runtimeTarget);
            }
            Path artifact = workspace.resolve(modulePath + ".lua");
            Files.writeString(artifact, chunk, StandardCharsets.UTF_8);
            Path probe = workspace.resolve(probeName + ".lua");
            Files.writeString(probe, """
                local text = io.open("%s"):read("*a")
                text = text:gsub("%%s*$", "")
                local tail = 'return __exportSurfaces["%s"]'
                assert(text:sub(-#tail) == tail, "the artifact tail is the surface return")
                local row = [==[
                local function row(label, ok, value)
                  if ok then print(label .. "|OK|") return end
                  print(label .. "|" .. tostring(value.code) .. "|" .. tostring(value.e)
                    .. "|" .. tostring(value.a) .. "|" .. tostring(value.m))
                end
                %s
                ]==]
                local chunk = assert(load(text:sub(1, #text - #tail) .. row, "%s"))
                chunk()
                """.formatted(artifact.toAbsolutePath().toString(), modulePath, body,
                    probeName),
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit", probeName + ".lua");
            builder.directory(workspace.toFile());
            // The probe drives the emitted helpers directly: the deferred-main
            // flag keeps the chunk's module-init walk (which the drive does
            // not need) out of the load path.
            builder.environment().put("DEAL_DEFER_MAIN", "1");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int exit = process.waitFor();
            check(exit == 0, "the emitted prelude runs under luajit: exit=" + exit
                + " output=" + output);
            List<String> rows = new ArrayList<>();
            for (String line : output.lines().toList()) {
                if (line.contains("|") && !line.startsWith("luajit")) {
                    rows.add(line.strip());
                }
            }
            check(rows.size() >= minRows, "the prelude probe prints its rows: " + output);
            return rows;
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // =========================================================================
    // 9. The typed-boundary bytes carrier (the non-bytes cell admission)
    // =========================================================================

    /**
     * The typed-boundary projection's bytes member at the kind arm: a
     * bytes carrier projects {@code bytes}, so a non-bytes typed boundary
     * rejects it on every consumer. The cell is the reachable bytes value
     * crossing a {@code table} (and an {@code int}) typed boundary; before
     * the closed bytes case the emitted Lua prelude projected the table
     * spelling and admitted the value while the oracle and the JVM
     * artifact rejected it. One identical tuple from the oracle, the JVM
     * runtime, and the emitted prelude under {@code luajit}, with the
     * table-at-table and bytes-at-bytes admissions as the controls.
     */
    static void testTypedBoundaryBytesCarrier() throws Exception {
        System.out.println("-- the typed-boundary bytes carrier (the table/int cells) --");
        String span = FailureContractRegistry.arm(FailureArmId.TYPED_BOUNDARY_KIND)
            .origin().name();
        Tuple oracleTable = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, RuntimeDescriptor.Table.INSTANCE,
            BoundaryValueView.of(ActualKind.BYTES), BoundaryContext.none()),
            "the bytes-at-table cell"), span);
        checkEq(new Tuple("E8001", "expected table", span, "table", "bytes"),
            oracleTable, "the oracle rejects a bytes carrier at a table boundary");
        Tuple oracleInt = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, RuntimeDescriptor.Int.INSTANCE,
            BoundaryValueView.of(ActualKind.BYTES), BoundaryContext.none()),
            "the bytes-at-int cell"), span);
        checkEq(new Tuple("E8001", "expected int", span, "int", "bytes"),
            oracleInt, "the oracle rejects a bytes carrier at an int boundary");
        // The JVM runtime leg (a real bytes carrier).
        deal.codegen.jvm.JvmRuntime.BytesValue bytesValue =
            new deal.codegen.jvm.JvmRuntime.BytesValue(2);
        deal.codegen.jvm.JvmRuntime.DealError jvmTable = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("table", "table", bytesValue));
        check(jvmTable != null, "the JVM table boundary rejects the bytes carrier");
        if (jvmTable != null) {
            checkEq(null, firstDifferingField(oracleTable,
                new Tuple(jvmTable.code, jvmTable.msg, span, jvmTable.expected,
                    jvmTable.actual)),
                "the JVM runtime renders the oracle's identical bytes-at-table tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmInt = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("int", "int", bytesValue));
        check(jvmInt != null, "the JVM int boundary rejects the bytes carrier");
        if (jvmInt != null) {
            checkEq(null, firstDifferingField(oracleInt,
                new Tuple(jvmInt.code, jvmInt.msg, span, jvmInt.expected, jvmInt.actual)),
                "the JVM runtime renders the oracle's identical bytes-at-int tuple");
        }
        // The emitted prelude leg, under real luajit: the failures and the
        // two admissions.
        List<String> rows = runPreludeProbe("""
            local bytes = {__kind = "bytes", __len = 0}
            local ok, value = pcall(__bcheck, "table", "table", bytes, nil)
            row("bytes-at-table", ok, value)
            ok, value = pcall(__bcheck, "int", "int", bytes, nil)
            row("bytes-at-int", ok, value)
            ok, value = pcall(__bcheck, "table", "table", {__t = true, __order = {}}, nil)
            row("table-at-table", ok, value)
            ok, value = pcall(__bcheck, "bytes", "bytes", bytes, nil)
            row("bytes-at-bytes", ok, value)
            """, "bytes-boundary-probe", 4);
        checkEq("bytes-at-table|E8001|table|bytes|expected table", rows.get(0),
            "the emitted prelude rejects the bytes carrier at a table boundary with the "
                + "closed kind tuple");
        checkEq("bytes-at-int|E8001|int|bytes|expected int", rows.get(1),
            "the emitted prelude rejects the bytes carrier at an int boundary");
        checkEq("table-at-table|OK|", rows.get(2),
            "the emitted prelude admits a table carrier at a table boundary");
        checkEq("bytes-at-bytes|OK|", rows.get(3),
            "the emitted prelude admits the bytes carrier at a bytes boundary");
        checkEq(null, firstDifferingField(oracleTable, luaTuple(rows.get(0), span)),
            "the Lua artifact renders the oracle's identical bytes-at-table tuple");
        checkEq(null, firstDifferingField(oracleInt, luaTuple(rows.get(1), span)),
            "the Lua artifact renders the oracle's identical bytes-at-int tuple");
    }

    // =========================================================================
    // 10. The std/json rejection arm: the carrier-kind cells
    // =========================================================================

    /**
     * The {@code JSON_STRINGIFY_UNSUPPORTED} arm's render: one identical
     * {@code (code, message, expected, actual)} tuple from the oracle
     * (through {@link FailureProjections#stringifyUnsupported}), the JVM
     * runtime's stdlib realization, and the emitted prelude under
     * {@code luajit}. The cells are the reachable carrier kinds — the
     * absent marker ({@code nil}), a class instance ({@code table}), a
     * DEAL function value ({@code function}), the bytes carrier
     * ({@code bytes}), and a nonfinite number ({@code number}); the
     * expected field is the arm's own pinned text on every consumer.
     */
    static void testStdJsonArmDrive() throws Exception {
        System.out.println("-- the std/json rejection arm: the carrier-kind cells --");
        String span = FailureContractRegistry.arm(
            FailureArmId.JSON_STRINGIFY_UNSUPPORTED).origin().name();
        String expectedText = FailureContractRegistry.arm(
            FailureArmId.JSON_STRINGIFY_UNSUPPORTED).pinnedExpectedText();
        checkEq(FailureContractRegistry.arm(FailureArmId.JSON_STRINGIFY_UNSUPPORTED)
                .pinnedExpectedText(),
            deal.semantic.SharedStdlibSemantics.JSON_STRINGIFY_EXPECTED,
            "the shared expected constant derives from the arm's own pinned text");
        deal.semantic.ir.SourceOrigin origin = new deal.semantic.ir.SourceOrigin(
            "arm-battery", deal.semantic.ir.SourceSpan.synthetic("arm-battery"),
            deal.semantic.ir.SourceOriginKind.SYNTHETIC,
            new deal.semantic.ir.AnchorId(1), null);
        // The oracle leg: the shared primitive's render per carrier kind.
        Tuple oracleMissing = checkStdJsonOracle(origin,
            new deal.semantic.SharedStdlibSemantics.Value.Other(ActualKind.MISSING, null),
            span, expectedText, "nil", "the oracle projects the absent marker as nil");
        Tuple oracleClass = checkStdJsonOracle(origin,
            new deal.semantic.SharedStdlibSemantics.Value.Other(ActualKind.CLASS,
                "@src/app/Admin"), span, expectedText, "table",
            "the oracle projects a class instance as the table-carried value");
        Tuple oracleFunction = checkStdJsonOracle(origin,
            new deal.semantic.SharedStdlibSemantics.Value.Other(ActualKind.FUNCTION, null),
            span, expectedText, "function",
            "the oracle projects a DEAL function value as the function member");
        Tuple oracleBytes = checkStdJsonOracle(origin,
            new deal.semantic.SharedStdlibSemantics.Value.Other(ActualKind.BYTES, null),
            span, expectedText, "bytes",
            "the oracle projects the bytes carrier as the bytes member");
        Tuple oracleNumber = checkStdJsonOracle(origin,
            new deal.semantic.SharedStdlibSemantics.Value.Number(Double.NaN),
            span, expectedText, "number",
            "the oracle projects a nonfinite number as number");
        // The JVM runtime leg (the same arm through the shared helper).
        Tuple jvmMissing = checkStdJsonJvm(tableWith("m", deal.codegen.jvm.JvmRuntime.MISSING),
            span, expectedText, "nil", "the JVM runtime projects the absent marker as nil");
        Tuple jvmClass = checkStdJsonJvm(tableWith("c",
                new deal.codegen.jvm.JvmRuntime.ErrorValue("E8001", "x")),
            span, expectedText, "table",
            "the JVM runtime projects a class instance as the table-carried value");
        Tuple jvmFunction = checkStdJsonJvm(tableWith("f",
                new deal.codegen.jvm.JvmRuntime.FunctionValue(args -> null, "() -> null")),
            span, expectedText, "function",
            "the JVM runtime projects a DEAL function value as the function member");
        Tuple jvmBytes = checkStdJsonJvm(tableWith("b",
                new deal.codegen.jvm.JvmRuntime.BytesValue(2)),
            span, expectedText, "bytes",
            "the JVM runtime projects the bytes carrier as the bytes member");
        Tuple jvmNumber = checkStdJsonJvm(tableWith("n", Double.valueOf(Double.NaN)),
            span, expectedText, "number",
            "the JVM runtime projects a nonfinite number as number");
        checkEq(null, firstDifferingField(oracleMissing, jvmMissing),
            "the JVM runtime renders the oracle's identical absent-marker tuple");
        checkEq(null, firstDifferingField(oracleClass, jvmClass),
            "the JVM runtime renders the oracle's identical class tuple");
        checkEq(null, firstDifferingField(oracleFunction, jvmFunction),
            "the JVM runtime renders the oracle's identical function tuple");
        checkEq(null, firstDifferingField(oracleBytes, jvmBytes),
            "the JVM runtime renders the oracle's identical bytes tuple");
        checkEq(null, firstDifferingField(oracleNumber, jvmNumber),
            "the JVM runtime renders the oracle's identical number tuple");
        // The emitted prelude leg, under real luajit: the arm's own render
        // through the serialized table (its pinned text included).
        List<String> rows = runPreludeProbe("""
            local function call(root)
              return pcall(__stdlibInvoke, "STDLIB_CALL", "JSON_STRINGIFY", "op",
                "digest", "parent", "origin", root)
            end
            local ok, value = call({__t = true, __order = {"m"}, m = __MISSING})
            row("missing", ok, value)
            ok, value = call({__t = true, __order = {"c"},
              c = {__c = true, __id = "@a/B", __f = {}, __p = {}}})
            row("class", ok, value)
            ok, value = call({__t = true, __order = {"f"}, f = function() end})
            row("function", ok, value)
            ok, value = call({__t = true, __order = {"b"},
              b = {__kind = "bytes", __len = 0}})
            row("bytes", ok, value)
            ok, value = call({__t = true, __order = {"n"}, n = 0 / 0})
            row("nan", ok, value)
            """, "std-json-probe", 5);
        checkEq("missing|E8001|" + expectedText
                + "|nil|unsupported type for JSON encoding: nil",
            rows.get(0), "the emitted prelude renders the arm's nil cell");
        checkEq("class|E8001|" + expectedText
                + "|table|unsupported type for JSON encoding: table",
            rows.get(1), "the emitted prelude renders the arm's table cell");
        checkEq("function|E8001|" + expectedText
                + "|function|unsupported type for JSON encoding: function",
            rows.get(2), "the emitted prelude renders the arm's function member");
        checkEq("bytes|E8001|" + expectedText
                + "|bytes|unsupported type for JSON encoding: bytes",
            rows.get(3), "the emitted prelude renders the arm's bytes member");
        checkEq("nan|E8001|" + expectedText
                + "|number|unsupported type for JSON encoding: number",
            rows.get(4), "the emitted prelude renders the arm's number cell");
        checkEq(null, firstDifferingField(oracleMissing,
            luaTuple(rows.get(0), span)),
            "the Lua artifact renders the oracle's identical absent-marker tuple");
        checkEq(null, firstDifferingField(oracleClass, luaTuple(rows.get(1), span)),
            "the Lua artifact renders the oracle's identical class tuple");
        checkEq(null, firstDifferingField(oracleFunction, luaTuple(rows.get(2), span)),
            "the Lua artifact renders the oracle's identical function tuple");
        checkEq(null, firstDifferingField(oracleBytes, luaTuple(rows.get(3), span)),
            "the Lua artifact renders the oracle's identical bytes tuple");
        checkEq(null, firstDifferingField(oracleNumber, luaTuple(rows.get(4), span)),
            "the Lua artifact renders the oracle's identical number tuple");
    }

    /** One oracle std/json rejection assertion (the arm's tuple per carrier). */
    private static Tuple checkStdJsonOracle(deal.semantic.ir.SourceOrigin origin,
                                            deal.semantic.SharedStdlibSemantics.Value value,
                                            String span, String expected, String actual,
                                            String note) {
        deal.semantic.SharedStdlibSemantics.Value.Table table =
            (deal.semantic.SharedStdlibSemantics.Value.Table)
                deal.semantic.SharedStdlibSemantics.Value.table();
        table.table().put("v", value);
        deal.semantic.SharedStdlibSemantics.Outcome<
                deal.semantic.SharedStdlibSemantics.Value> outcome =
            deal.semantic.SharedStdlibSemantics.jsonStringify(origin, table.table());
        if (!(outcome instanceof deal.semantic.SharedStdlibSemantics.Outcome.Failure<
                deal.semantic.SharedStdlibSemantics.Value> failure)) {
            check(false, note + " (no failure: " + outcome + ")");
            return new Tuple("?", "?", span, expected, actual);
        }
        BoundaryFailure projection = failure.failure().failure();
        Tuple tuple = new Tuple(projection.code().name(), projection.message(), span,
            projection.expected(), projection.actual());
        checkEq(new Tuple("E8001", "unsupported type for JSON encoding: " + actual, span,
            expected, actual), tuple, note);
        return tuple;
    }

    /** One JVM std/json rejection assertion through the shared stdlib dispatch. */
    private static Tuple checkStdJsonJvm(deal.codegen.jvm.JvmRuntime.Table table,
                                         String span, String expected, String actual,
                                         String note) {
        deal.codegen.jvm.JvmRuntime.DealError error = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.stdlib("STDLIB_CALL", "JSON_STRINGIFY",
                "op", "digest", "parent", "origin", new Object[] {table}));
        check(error != null, note + " (no failure thrown)");
        if (error == null) {
            return new Tuple("?", "?", span, expected, actual);
        }
        Tuple tuple = new Tuple(error.code, error.msg, span, error.expected, error.actual);
        checkEq(new Tuple("E8001", "unsupported type for JSON encoding: " + actual, span,
            expected, actual), tuple, note);
        return tuple;
    }

    /** One single-entry JVM table. */
    private static deal.codegen.jvm.JvmRuntime.Table tableWith(String key, Object value) {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write(key, value);
        return table;
    }

    /** One prelude probe row ({@code label|code|expected|actual|message}) as a tuple. */
    private static Tuple luaTuple(String row, String span) {
        String[] parts = row.split("\\|", -1);
        return new Tuple(parts[1], parts[4], span, parts[2], parts[3]);
    }

    /**
     * One walk-probe row's tuple ({@code label|code|expected|actual|message|
     * origin}): an empty field is an absent (null) field, and the span is the
     * row's own rendered origin — never a test constant, so an absent or
     * substituted consumer origin fails the drive's comparison by field name.
     */
    private static Tuple walkLuaTuple(String row) {
        String[] parts = row.split("\\|", -1);
        return new Tuple(parts[1], parts[4], parts[5],
            parts[2].isEmpty() ? null : parts[2],
            parts[3].isEmpty() ? null : parts[3]);
    }

    /** One walk-probe row with its rendered origin replaced (the origin negative). */
    private static String withOrigin(String row, String origin) {
        int cut = row.lastIndexOf('|');
        return row.substring(0, cut + 1) + origin;
    }

    // =========================================================================
    // 11. The int32/sqrt family: the JVM runtime renders the arms
    // =========================================================================

    /**
     * The int32/sqrt family's JVM render sites (P4 item 3): the arithmetic
     * range and division arms, the negative-exponent arm, the
     * {@code SQRT_NEGATIVE} arm, the {@code MATH_ABS_INT} range cell, and
     * the JSON parse arm each publish the closed arm's own tuple — never a
     * hard-coded copy of its text. {@code JvmRuntime} composes no failure
     * text for a table arm; the arm render is the only source of each
     * message and field.
     */
    static void testInt32AndSqrtArmFamily() {
        System.out.println("-- the int32/sqrt family: the JVM runtime renders the arms --");
        String origin = "arm-battery.deal:1:1";
        checkJvmArm(FailureArmId.INT32_RANGE, Map.of(), null, null, origin,
            "the arithmetic range cell",
            () -> deal.codegen.jvm.JvmRuntime.arith("INT32_ADD", 2147483647L, 1L,
                "op", "digest", "parent", origin));
        checkJvmArm(FailureArmId.INT32_RANGE, Map.of(), null, null, origin,
            "the exponent-overflow cell",
            () -> deal.codegen.jvm.JvmRuntime.arith("INT32_POW", 2L, 100L,
                "op", "digest", "parent", origin));
        checkJvmArm(FailureArmId.INT32_DIVISION_BY_ZERO, Map.of(), null, null, origin,
            "the division-by-zero cell",
            () -> deal.codegen.jvm.JvmRuntime.arith("INT32_DIV_TRUNC", 1L, 0L,
                "op", "digest", "parent", origin));
        checkJvmArm(FailureArmId.INT32_DIVISION_BY_ZERO, Map.of(), null, null, origin,
            "the modulo-by-zero cell",
            () -> deal.codegen.jvm.JvmRuntime.arith("INT32_MOD_TRUNC", 1L, 0L,
                "op", "digest", "parent", origin));
        checkJvmArm(FailureArmId.INT32_NEGATIVE_EXPONENT, Map.of(), null, null, origin,
            "the negative-exponent cell",
            () -> deal.codegen.jvm.JvmRuntime.arith("INT32_POW", 2L, -1L,
                "op", "digest", "parent", origin));
        checkJvmArm(FailureArmId.INT32_RANGE, Map.of(), null, null, origin,
            "the MATH_ABS_INT range cell",
            () -> deal.codegen.jvm.JvmRuntime.stdlib("STDLIB_CALL", "MATH_ABS_INT",
                "op", "digest", "parent", origin,
                new Object[] {Long.valueOf(-2147483648L)}));
        checkJvmArm(FailureArmId.SQRT_NEGATIVE, Map.of(), null,
            Double.toHexString(-1.0d), origin, "the MATH_SQRT cell",
            () -> deal.codegen.jvm.JvmRuntime.stdlib("STDLIB_CALL", "MATH_SQRT",
                "op", "digest", "parent", origin,
                new Object[] {Double.valueOf(-1.0d)}));
        checkJvmArm(FailureArmId.JSON_PARSE_ERROR,
            Map.of("oneBasedByteOffset", "2", "reason", "unexpected end of input"),
            null, null, origin, "the JSON parse cell",
            () -> deal.codegen.jvm.JvmRuntime.stdlib("STDLIB_CALL", "JSON_PARSE",
                "op", "digest", "parent", origin, new Object[] {"{"}));
    }

    /**
     * One JVM runtime failure of one arm-rendered site: the published tuple
     * must equal the closed arm's own render (the single-source property at
     * the JVM consumer), reported by the first differing field's name.
     */
    private static void checkJvmArm(FailureArmId armId, Map<String, String> parameters,
                                    String expected, String actual, String origin,
                                    String note, Runnable action) {
        Tuple reference = tupleOf(FailureContractRegistry.render(armId, parameters,
            expected, actual, null), origin);
        deal.codegen.jvm.JvmRuntime.DealError error = jvmFailure(action);
        check(error != null, note + " fails: " + error);
        if (error == null) {
            return;
        }
        Tuple published = new Tuple(error.code, error.msg, error.origin, error.expected,
            error.actual);
        checkEq(null, firstDifferingField(reference, published),
            note + " publishes the closed " + armId + " arm's own tuple");
    }

    // =========================================================================
    // 11b. The int-ladder wrong-kind cell (the closed kind arm)
    // =========================================================================

    static void testIntLadderWrongKindCell() throws Exception {
        System.out.println("-- the int-ladder wrong-kind cell (the closed kind arm) --");
        String origin = "int-ladder.deal:3:16";
        Tuple reference = tupleOf(FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int", "string",
            null), origin);
        checkEq(new Tuple("E8001", "expected int", origin, "int", "string"),
            reference, "the authority renders the suffix-less kind arm");
        // The JVM leg: the ladder's wrong-kind tail.
        checkJvmArm(FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int",
            "string", origin, "the JVM int-ladder wrong-kind cell",
            () -> deal.codegen.jvm.JvmRuntime.intConv("bad", "INTRINSIC_CALL",
                "number", "op", "digest", "parent", origin));
        // The emitted prelude leg, under real luajit.
        List<String> rows = runPreludeProbe("""
            local ok, value = pcall(__intConv, "bad", "INTRINSIC_CALL", "number",
              "op", "digest", "parent", "origin")
            row("int-kind", ok, value)
            ok, value = pcall(__numConv, true, "INTRINSIC_CALL", "int",
              "op", "digest", "parent", "origin")
            row("number-kind", ok, value)
            """, "int-ladder-probe", 2);
        checkEq("int-kind|E8001|int|string|expected int", rows.get(0),
            "the emitted prelude's int ladder renders the closed kind arm");
        checkEq("number-kind|E8001|number|boolean|expected number", rows.get(1),
            "the emitted prelude's number ladder renders the closed kind arm");
        checkEq(null, firstDifferingField(reference, luaTuple(rows.get(0), origin)),
            "the Lua artifact renders the oracle's identical int-ladder tuple");
        // The oracle leg: a lowered unit whose module-init block feeds a
        // string const into the ladder (the cell's own engine render).
        Tuple oracleTuple = oracleIntLadderWrongKindLeg();
        checkEq(null, firstDifferingField(reference,
            new Tuple(oracleTuple.code(), oracleTuple.message(), origin,
                oracleTuple.expected(), oracleTuple.actual())),
            "the oracle renders the authority's identical int-ladder tuple");

        expectDefect(() -> BoundaryFailure.fromRow(
            FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR), 0, "int",
            "string", new LinkedHashMap<>(), null),
            "the superseded row projection fails closed on the corrected kind template");
    }

    private static Tuple oracleIntLadderWrongKindLeg() {
        deal.semantic.ir.ModuleId module = new deal.semantic.ir.ModuleId("int-ladder");
        deal.semantic.ir.OpId constOp = new deal.semantic.ir.OpId(module, 0);
        deal.semantic.ir.OpId intrinsicOp = new deal.semantic.ir.OpId(module, 1);
        deal.semantic.ir.BlockId initBlock = new deal.semantic.ir.BlockId(0);
        deal.semantic.ir.ValueId input = new deal.semantic.ir.ValueId(2);
        deal.semantic.ir.SourceOrigin origin = new deal.semantic.ir.SourceOrigin(
            module.path(), deal.semantic.ir.SourceSpan.synthetic(module.path()),
            deal.semantic.ir.SourceOriginKind.SYNTHETIC, new deal.semantic.ir.AnchorId(0),
            null);
        List<deal.semantic.ir.SemanticOp> ops = List.of(
            intLadderOp(constOp, deal.semantic.ir.SemanticOpKind.CONST,
                new deal.semantic.ir.KindPayload.ConstPayload(
                    new deal.semantic.ir.ScalarValue.String("bad")), input,
                RuntimeDescriptor.String.INSTANCE,
                deal.semantic.ir.FailurePolicyId.NO_DEAL_FAILURE, origin, List.of(),
                List.of()),
            intLadderOp(intrinsicOp, deal.semantic.ir.SemanticOpKind.INTRINSIC_CALL,
                new deal.semantic.ir.KindPayload.IntrinsicCallPayload(
                    deal.semantic.ir.IntrinsicKind.INT_CONVERT, input),
                new deal.semantic.ir.ValueId(3), RuntimeDescriptor.Int.INSTANCE,
                deal.semantic.ir.FailurePolicyId.INT_CONVERSION, origin, List.of(input),
                List.of(RuntimeDescriptor.Number.INSTANCE)));
        deal.semantic.ir.LoweredModuleUnit unit =
            new deal.semantic.ir.LoweredModuleUnit(
                deal.semantic.ir.LoweredModuleUnit.FORMAT_VERSION,
                deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, module, "hash",
                "context", java.util.Set.of(), Map.of(), Map.of(), Map.of(),
                new deal.semantic.ir.ModuleInitPlan(List.of(), initBlock),
                deal.semantic.ir.ExportPlan.empty(), Map.of(), ops);
        deal.semantic.ir.StructuredBodyTable table =
            new deal.semantic.ir.StructuredBodyTable(
                Map.of(initBlock, List.of(constOp, intrinsicOp)),
                Map.of(constOp, initBlock, intrinsicOp, initBlock));
        deal.semantic.SemanticRuntimeModel.ConsumerRun run =
            deal.semantic.SemanticOracle.execute(unit, table);
        check(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                .DealFailure,
            "the oracle terminates the int-ladder cell with a DEAL failure, never an "
                + "internal defect: " + run.terminal());
        if (!(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                .DealFailure failure)) {
            return new Tuple("?", "?", origin.toString(), "int", "string");
        }
        return new Tuple(failure.error().code(), failure.error().message(),
            failure.error().origin(), failure.error().expected(),
            failure.error().actual());
    }

    /** One contract-complete op of the int-ladder oracle unit. */
    private static deal.semantic.ir.SemanticOp intLadderOp(
            deal.semantic.ir.OpId id, deal.semantic.ir.SemanticOpKind kind,
            deal.semantic.ir.KindPayload payload, deal.semantic.ir.SemanticValue result,
            deal.semantic.ir.OpResultType resultType,
            deal.semantic.ir.FailurePolicyId policy,
            deal.semantic.ir.SourceOrigin origin,
            List<deal.semantic.ir.ValueId> operands,
            List<RuntimeDescriptor> operandTypes) {
        deal.semantic.ir.ClosedSelector selector =
            payload instanceof deal.semantic.ir.KindPayload.SelectorCarrying carrying
                ? carrying.selector() : null;
        deal.semantic.ir.OperationContractSnapshot contract =
            new deal.semantic.ir.OperationContractSnapshot(
                deal.semantic.ir.OperationContractSnapshot.VERSION, kind, resultType,
                operandTypes, selector, payload, policy, List.of(), "placeholder");
        contract = new deal.semantic.ir.OperationContractSnapshot(
            deal.semantic.ir.OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(),
            deal.semantic.ir.ContractSnapshotCanonicalizer.digest(contract));
        return new deal.semantic.ir.SemanticOp(id, kind, origin, result, resultType,
            operands, operandTypes, payload, policy, contract);
    }

    // =========================================================================
    // 11c. The reachable bounds/bytes arms render through the arm table
    // =========================================================================

    /**
     * Every reachable array/bytes bounds arm and the multi-code
     * {@code BYTES_WRITE} value-range arm renders through the closed arm
     * table — the oracle's shared executor, the row-position entry point
     * ({@code fromRow}, which resolves the bound arm and can never
     * instantiate a retained template behind its arm's back), and the JVM
     * runtime's bytes sites. The emitted Lua prelude's bytes gates render the
     * same arms and hold no failure text of their own.
     */
    static void testBoundsAndBytesArms() throws Exception {
        System.out.println("-- the array/bytes bounds arms and the multi-code range arm --");
        String span = "bounds-arms.deal:1:1";
        checkEq("E8012", FailureContractRegistry.codeOf(
                FailureContractRegistry.arm(FailureArmId.BYTES_WRITE_BOUNDS)),
            "the bytes bounds arm keeps its row's E8012");
        checkEq("E8013", FailureContractRegistry.codeOf(
                FailureContractRegistry.arm(FailureArmId.BYTES_WRITE_RANGE)),
            "the multi-code row's value-range arm pins its own E8013");
        // The oracle's shared executor renders each reachable bounds arm.
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.ARRAY_READ_NEGATIVE_INDEX,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR,
                RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
                BoundaryContext.arrayIndex(-1)), "the negative-index read"), span)),
            "the oracle renders ARRAY_READ_NEGATIVE_INDEX");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.ARRAY_WRITE_BOUNDS,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
                RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
                BoundaryContext.writeBounds(2, 1)), "the write bounds"), span)),
            "the oracle renders ARRAY_WRITE_BOUNDS");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.ARRAY_DELETE_BOUNDS,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.ARRAY_DELETE_BOUNDS, RuntimeDescriptor.Int.INSTANCE,
                BoundaryValueView.ofInt(0), BoundaryContext.writeBounds(-1, 1)),
                "the delete bounds"), span)),
            "the oracle renders ARRAY_DELETE_BOUNDS");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.BYTES_READ,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(FailurePolicyId.BYTES_READ,
                RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
                BoundaryContext.bytesBounds(2, 2)), "the bytes read bounds"), span)),
            "the oracle renders BYTES_READ");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.BYTES_WRITE_BOUNDS,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(FailurePolicyId.BYTES_WRITE,
                RuntimeDescriptor.Int.INSTANCE, BoundaryValueView.ofInt(0),
                BoundaryContext.bytesBounds(-1, 2)), "the bytes write bounds"), span)),
            "the oracle renders BYTES_WRITE_BOUNDS");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.BYTES_WRITE_RANGE,
                Map.of(), null, null, null), span),
            tupleOf(BoundaryExecutor.bytesWriteRangeFailure(), span)),
            "the oracle's value-range projection renders the multi-code arm");
        // The row-position entry point resolves the bound arm: the same
        // template and the arm's own code, never a caller-selected code.
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.BYTES_WRITE_RANGE,
                Map.of(), null, null, null), span),
            tupleOf(BoundaryFailure.fromRow(FailureContractRegistry.row(
                FailurePolicyId.BYTES_WRITE), 1, null, null, new LinkedHashMap<>(), null),
                span)),
            "the row-position entry point renders the arm's own code and template");
        // The JVM runtime's bytes sites render the same arms.
        checkJvmArm(FailureArmId.BYTES_ALLOCATE, Map.of(), null, null, span,
            "the bytes allocation cell",
            () -> deal.codegen.jvm.JvmRuntime.bytesNew(-1L, "INTRINSIC_CALL", "op",
                "digest", "parent", span));
        checkJvmArm(FailureArmId.BYTES_READ, Map.of(), null, null, span,
            "the bytes read bounds cell",
            () -> deal.codegen.jvm.JvmRuntime.bytesRead("op", "digest", "parent",
                "bkey", "bdigest", "bparent",
                new deal.codegen.jvm.JvmRuntime.BytesValue(2), 2L, 2L, span));
        checkJvmArm(FailureArmId.BYTES_WRITE_BOUNDS, Map.of(), null, null, span,
            "the bytes write bounds cell",
            () -> deal.codegen.jvm.JvmRuntime.bytesBounds("bkey", "bdigest", "bparent",
                Long.valueOf(0), -1L, 2L, "int", "int", span));
        checkJvmArm(FailureArmId.BYTES_WRITE_RANGE, Map.of(), null, null, span,
            "the bytes value-range cell",
            () -> deal.codegen.jvm.JvmRuntime.bytesCommit("op", "digest", "parent",
                new deal.codegen.jvm.JvmRuntime.BytesValue(1), 0L, Long.valueOf(256),
                span));
        checkJvmArm(FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "bytes"), "bytes",
            "string", span, "the bytes-descriptor kind cell",
            () -> { throw deal.codegen.jvm.JvmRuntime.bytesKindFailure("not-bytes", span); });
        // The emitted Lua bytes gates render the same arms (a source control
        // over the emitter: no composed bytes failure text remains).
        String luaSource = Files.readString(Path.of("deal", "codegen", "lua",
            "LuaSemanticEmitter.java"), StandardCharsets.UTF_8);
        check(!luaSource.contains("__failExpr(\"E8012\""),
            "the Lua emitter holds no composed E8012 failure text at a failure site");
        check(luaSource.contains("__arm(\"BYTES_ALLOCATE\"")
                && luaSource.contains("__arm(\"BYTES_READ\"")
                && luaSource.contains("__arm(\"BYTES_WRITE_BOUNDS\""),
            "the emitted bytes gates render the closed bytes arms");
    }

    // =========================================================================
    // 11d. The declared expected/actual field shapes fail closed
    // =========================================================================

    /**
     * A caller-supplied expected/actual field outside its arm's declared
     * shape is a producer defect on the registry renderer and on the
     * serialized Lua renderer — never a fabricated DEAL-visible token. The
     * matching renders stay green (the control).
     */
    static void testDeclaredFieldShapeNegatives() throws Exception {
        System.out.println("-- the declared expected/actual shapes fail closed --");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.JSON_STRINGIFY_UNSUPPORTED, Map.of("actual", "function"),
            "WRONG", "function", null),
            "a fabricated expected text on the pinned-text arm fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "int"), "WRONG", "string", null),
            "an expected field outside the arm's own kind text fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "frobnicate"), "frobnicate", "string", null),
            "an expected field derived from an unknown kind text fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "class"), "class", "string", null),
            "the class-kind arm's token is not its kind text (the closed kind text is "
                + "'class instance') and fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "int"), "int", "WRONG", null),
            "an actual token outside the closed typed-boundary vocabulary fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "expected bytes"), "WRONG", "number", null),
            "a host cell's expected field outside the descriptor grammar fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.INT32_RANGE,
            Map.of(), null, "WRONG", null),
            "an actual field on a field-less arm fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.SQRT_NEGATIVE,
            Map.of(), null, "WRONG", null),
            "a non-number canonical-value actual fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.CLASS_IDENTITY,
            Map.of("expected", "not-an-atom", "actual", "@a/C"), "not-an-atom", "@a/C",
            null),
            "a class-identity expected field outside the atom grammar fails closed");
        expectDefect(() -> BoundaryFailure.fromRow(
            FailureContractRegistry.row(FailurePolicyId.ASYNC_COMPLETION), 0,
            "string", "number", new LinkedHashMap<>(), null),
            "the row-position entry point never guesses a parameter its fields do not "
                + "declare (the completion kind arm's kind text)");
        // The controls: the arm's own declared fields render.
        checkEq("unsupported type for JSON encoding: function",
            FailureContractRegistry.render(FailureArmId.JSON_STRINGIFY_UNSUPPORTED,
                Map.of("actual", "function"), "string, number, boolean, or table",
                "function", null).message(),
            "the matching pinned expected text renders");
        checkEq("expected int, got non-integer number",
            FailureContractRegistry.render(FailureArmId.INT_CONVERSION_FRACTIONAL,
                Map.of(), "int", "number", null).message(),
            "the fractional ladder arm keeps its pinned actual token");
        // The serialized Lua renderer's equivalent fail-closed validation,
        // under real luajit.
        List<String> rows = runPreludeProbe("""
            local ok = pcall(__arm, "TYPED_BOUNDARY_KIND", {kind = "int"}, "-", "WRONG", "string")
            print("kind-expected|" .. tostring(ok))
            ok = pcall(__arm, "TYPED_BOUNDARY_KIND", {kind = "int"}, "-", "int", "WRONG")
            print("kind-actual|" .. tostring(ok))
            ok = pcall(__arm, "JSON_STRINGIFY_UNSUPPORTED", {actual = "function"}, "-",
              "WRONG", "function")
            print("pinned-expected|" .. tostring(ok))
            ok = pcall(__arm, "INT32_RANGE", nil, "-", nil, "WRONG")
            print("fieldless-actual|" .. tostring(ok))
            ok = pcall(__arm, "TYPED_BOUNDARY_KIND", {kind = "int"}, "-", "string", "string")
            print("kind-pair|" .. tostring(ok))
            ok = pcall(__arm, "TYPED_BOUNDARY_KIND", {kind = "frobnicate"}, "-",
              "frobnicate", "string")
            print("kind-text|" .. tostring(ok))
            local value = __arm("TYPED_BOUNDARY_KIND", {kind = "int"}, "-", "int", "string")
            print("control|" .. value.code .. "|" .. value.e .. "|" .. value.a .. "|"
              .. value.m)
            """, "field-shape-probe", 7);
        checkEq("kind-expected|false", rows.get(0),
            "the Lua renderer fails closed on a fabricated kind expected token");
        checkEq("kind-actual|false", rows.get(1),
            "the Lua renderer fails closed on a fabricated typed-boundary actual");
        checkEq("pinned-expected|false", rows.get(2),
            "the Lua renderer fails closed on a fabricated pinned expected text");
        checkEq("fieldless-actual|false", rows.get(3),
            "the Lua renderer fails closed on an actual field a field-less arm declares none");
        checkEq("kind-pair|false", rows.get(4),
            "the Lua renderer derives the kind token from the arm's serialized "
                + "parameter sources (a closed-but-foreign pair fails closed)");
        checkEq("kind-text|false", rows.get(5),
            "the Lua renderer fails closed on an unknown kind text");
        checkEq("control|E8001|int|string|expected int", rows.get(6),
            "the Lua renderer keeps the arm's own declared fields green");
    }

    // =========================================================================
    // 11e. The superseded class:<ClassId> spelling fails closed
    // =========================================================================

    /**
     * The closed typed-boundary vocabulary admits the carried canonical
     * class atom only: the superseded composed {@code class:<ClassId>}
     * spelling (jsonable-tojson-walk-arm-binding W1) is rejected by every
     * renderer that can receive a caller-supplied actual token — the
     * registry's arm render, the JVM arm render (routed through the
     * registry), and the serialized Lua arm renderer — while a valid
     * carried atom stays green.
     */
    static void testTypedBoundaryClassSpellingNegatives() throws Exception {
        System.out.println("-- the superseded class: spelling fails closed --");
        // The registry renderer.
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "x", "actual", "class:arm/walk/Walk"), null,
            "class:arm/walk/Walk", null),
            "the superseded class:<ClassId> spelling on the walk arm's actual field "
                + "fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "x", "actual", "class:not-a-canonical-atom"), null,
            "class:not-a-canonical-atom", null),
            "a class:-prefixed token that is not a canonical atom fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "x", "actual", "class:"), null, "class:", null),
            "the empty class: prefix fails closed");
        checkEq("@/Error", FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "", "actual", "@/Error"), null, "@/Error", null)
                .actual(),
            "the carried canonical class atom stays admissible (the control)");
        // The JVM renderer (JvmRuntime.arm routes through the registry).
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "x", "actual", "class:arm/walk/Walk"), WALK_SPAN,
            null, "class:arm/walk/Walk"),
            "the JVM arm render fails closed on the superseded class: spelling");
        checkEq("@/Error", deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "", "actual", "@/Error"), WALK_SPAN, null,
                "@/Error").actual,
            "the JVM arm render keeps the carried canonical atom (the control)");
        // The serialized Lua renderer, under real luajit.
        List<String> rows = runPreludeProbe("""
            local ok = pcall(__arm, "JSON_TO_WALK",
              {fieldPath = "x", actual = "class:arm/walk/Walk"}, "-", nil,
              "class:arm/walk/Walk")
            print("class-spelling|" .. tostring(ok))
            ok = pcall(__arm, "JSON_TO_WALK",
              {fieldPath = "x", actual = "class:not-a-canonical-atom"}, "-", nil,
              "class:not-a-canonical-atom")
            print("class-nonatom|" .. tostring(ok))
            ok = pcall(__arm, "JSON_TO_WALK", {fieldPath = "x", actual = "class:"},
              "-", nil, "class:")
            print("class-empty|" .. tostring(ok))
            ok, value = pcall(__arm, "JSON_TO_WALK",
              {fieldPath = "", actual = "@/Error"}, "-", nil, "@/Error")
            print("atom-control|" .. tostring(ok) .. "|" .. (ok and value.a or ""))
            """, "class-spelling-probe", 4);
        checkEq("class-spelling|false", rows.get(0),
            "the Lua arm renderer fails closed on the superseded class: spelling");
        checkEq("class-nonatom|false", rows.get(1),
            "the Lua arm renderer fails closed on a class:-prefixed non-atom");
        checkEq("class-empty|false", rows.get(2),
            "the Lua arm renderer fails closed on the empty class: prefix");
        checkEq("atom-control|true|@/Error", rows.get(3),
            "the Lua arm renderer keeps the carried canonical atom (the control)");
    }

    // =========================================================================
    // 13. The host inner-reason descriptor validation and the emitted
    //     parameter contract
    // =========================================================================

    /**
     * The host inner reason is never derived from an unvalidated descriptor
     * string: the descriptor-kind render decodes the text through the closed
     * descriptor grammar and projects its closed kind, so an unknown
     * descriptor is a fail-closed producer defect and every canonical
     * spelling projects its kind ({@code [int]} to {@code array},
     * {@code (int)->int} to {@code function}, {@code ?string} to
     * {@code string}, {@code @…} to {@code class instance}).
     */
    static void testHostInnerReasonDescriptorValidation() {
        System.out.println("-- the host inner-reason descriptor validation --");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.kindReason("bogus"),
            "an unknown host descriptor fails closed in the descriptor-kind reason");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.kindReason(""),
            "an empty host descriptor fails closed in the descriptor-kind reason");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.kindReason("[bogus]"),
            "a descriptor whose element kind is unknown fails closed");
        checkEq("expected int", deal.codegen.jvm.JvmRuntime.kindReason("int"),
            "a primitive descriptor projects its own kind text");
        checkEq("expected array", deal.codegen.jvm.JvmRuntime.kindReason("[int]"),
            "the canonical array descriptor projects the array kind text");
        checkEq("expected function",
            deal.codegen.jvm.JvmRuntime.kindReason("(int)->int"),
            "the canonical function descriptor projects the function kind text");
        checkEq("expected string", deal.codegen.jvm.JvmRuntime.kindReason("?string"),
            "a nullable descriptor projects its inner descriptor's kind text");
        checkEq("expected class instance",
            deal.codegen.jvm.JvmRuntime.kindReason("@$external/host/cfg/ServerConfig"),
            "a class descriptor projects the class kind text");
    }

    /**
     * The emitted prelude's arm render supplies exactly the arm's declared
     * parameters (its serialized parameter sources): an extra parameter, a
     * missing parameter, and a parameter on a parameterless render are
     * producer defects, while the arm's own declaration stays green.
     */
    static void testEmittedParameterContract() throws Exception {
        System.out.println("-- the emitted arm parameter contract --");
        List<String> rows = runPreludeProbe("""
            local ok = pcall(__arm, "INT32_RANGE", {extra = "x"}, "-", nil, nil)
            print("extra-parameter|" .. tostring(ok))
            ok = pcall(__arm, "TYPED_BOUNDARY_KIND", {}, "-", "int", "string")
            print("missing-parameter|" .. tostring(ok))
            ok = pcall(__renderTemplate, "JSON_TO_WALK",
              {fieldPath = "a", actual = "table", extra = "x"})
            print("walk-extra-parameter|" .. tostring(ok))
            ok = pcall(__renderTemplate, "HOST_STRING_INVALID_UTF8", {})
            print("parameterless-control|" .. tostring(ok))
            local value = __arm("INT32_RANGE", nil, "-", nil, nil)
            print("fieldless-control|" .. value.code .. "|" .. value.m)
            """, "parameter-contract-probe", 5);
        checkEq("extra-parameter|false", rows.get(0),
            "an extra parameter on a parameterless arm fails closed");
        checkEq("missing-parameter|false", rows.get(1),
            "a missing declared parameter fails closed");
        checkEq("walk-extra-parameter|false", rows.get(2),
            "an extra parameter on the walk arm fails closed");
        checkEq("parameterless-control|true", rows.get(3),
            "a parameterless inner arm renders with no parameters (the control)");
        checkEq("fieldless-control|E8004|int out of safe range", rows.get(4),
            "the parameterless arm's own render stays green (the control)");
    }


    // =========================================================================
    // 14. The walk arm's three-consumer drive
    //     (jsonable-tojson-walk-arm-binding V2-V5)
    // =========================================================================

    /** The walk drive's class identity. */
    private static final deal.semantic.ir.ClassId WALK_ID =
        new deal.semantic.ir.ClassId("arm/walk", "Walk");

    /** The executing op's own source span (the emitted and driven origin). */
    private static final String WALK_SPAN = "arm-walk.deal:2:8";

    /** The walk drive's layout (the same declaration order on every consumer). */
    private static deal.semantic.ir.ClassLayout walkLayout() {
        return new deal.semantic.ir.ClassLayout(WALK_ID, List.of(
            new deal.semantic.ir.ClassLayout.FieldLayout("name",
                RuntimeDescriptor.String.INSTANCE, true,
                deal.semantic.ir.DefaultOwner.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("age",
                RuntimeDescriptor.Int.INSTANCE, true,
                deal.semantic.ir.DefaultOwner.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("ratio",
                RuntimeDescriptor.Number.INSTANCE, true,
                deal.semantic.ir.DefaultOwner.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("data",
                RuntimeDescriptor.Table.INSTANCE, true,
                deal.semantic.ir.DefaultOwner.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("tags",
                new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE), true,
                deal.semantic.ir.DefaultOwner.LOCAL),
            // The nullable optional field of the landed present-null read
            // (testLandedPresentNullRead): its presence pin is the exact
            // trigger of the walk's landed admission check.
            new deal.semantic.ir.ClassLayout.FieldLayout("note",
                new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE), false,
                deal.semantic.ir.DefaultOwner.LOCAL)));
    }

    /** One drive case: the same failing value on the three consumers. */
    private record WalkCase(String label, String arm, String fieldPath, String actual,
                            deal.semantic.ir.ClassOpsExecutor.Value oracleValue,
                            String luaRoot,
                            deal.codegen.jvm.JvmRuntime.ClassInstance jvmRoot) {

        /** The arm's own instantiated message for this case. */
        String message() {
            return FailureArmId.JSON_TO_WALK_CYCLE.name().equals(arm)
                ? "cyclic value cannot be encoded as JSON"
                : "value at " + fieldPath + " is not JSON serializable: " + actual;
        }
    }

    /** The walk drive's cases: the admission positions every named consumer fails. */
    private static List<WalkCase> walkCases() {
        List<WalkCase> cases = new ArrayList<>();
        // A fractional value at an int-typed declared field: number by the
        // value's own variant, never the declared int text.
        cases.add(walkCase("fractional-int-field", "JSON_TO_WALK", "age", "number",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(3.5),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "3.5", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 3.5, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A nonfinite value at an array(int) element: number.
        cases.add(walkCase("array-element-nonfinite", "JSON_TO_WALK", "tags[1]", "number",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(),
                "tags", walkIntThenNonfiniteArray()),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 2, [1] = 1, [2] = math.huge}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                jvmNumberArray(1L, Double.POSITIVE_INFINITY))));
        // A nonfinite value at a number-typed declared field: number.
        cases.add(walkCase("nonfinite-number-field", "JSON_TO_WALK", "ratio", "number",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(Double.NaN),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0/0",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", Double.NaN, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A table value at a string-typed declared field: its closed kind.
        cases.add(walkCase("wrong-kind-at-string-field", "JSON_TO_WALK", "name", "table",
            Map.of("name", walkEmptyTable(), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", LUA_TABLE_CARRIER, "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", new deal.codegen.jvm.JvmRuntime.Table(), "age", 1L,
                "ratio", 0.5, "data", new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                jvmIntArray(1L))));
        // An absent required value: the typed-boundary nil token.
        cases.add(walkCase("missing-required-field", "JSON_TO_WALK", "age", "nil",
            Map.of("name", walkString("n"), "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A present null on a non-nullable field: the null token.
        cases.add(walkCase("present-null-non-nullable", "JSON_TO_WALK", "name", "null",
            Map.of("name", ClassOpsExecutor.Value.Null.INSTANCE,
                "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", "__NULL", "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            walkNullField("name")));
        // An invalid Unicode scalar sequence: invalid-unicode.
        cases.add(walkCase("invalid-scalar-string", "JSON_TO_WALK", "name",
            "invalid-unicode",
            Map.of("name", walkString("\uD800"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", "\"\\237\\160\\128\"", "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "\uD800", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A host-ABI function wrapper ({__kind = "function"}: the emitted
        // export-surface entry and host-crossing carrier shape) at a failing
        // declared field: the closed function token on every consumer, never
        // the table spelling.
        cases.add(walkCase("function-wrapper-at-string-field", "JSON_TO_WALK",
            "name", "function",
            Map.of("name", walkFunction(), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", "{__kind = \"function\", sig = \"()->number\", "
                    + "f = function() end}",
                "age", "1", "ratio", "0.5", "data", LUA_TABLE_CARRIER,
                "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", walkJvmFunction(), "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // The remaining declared-descriptor kind mismatches (V2's
        // per-descriptor set): each position fails with the failing value's
        // own closed kind on every consumer.
        cases.add(walkCase("boolean-at-int-field", "JSON_TO_WALK", "age", "boolean",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Bool(true),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "true", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", Boolean.TRUE, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        cases.add(walkCase("string-at-number-field", "JSON_TO_WALK", "ratio", "string",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", walkString("x"),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", luaString("x"),
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", "x", "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        cases.add(walkCase("string-at-table-field", "JSON_TO_WALK", "data", "string",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkString("x"), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", luaString("x"), "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data", "x", "tags",
                jvmIntArray(1L))));
        cases.add(walkCase("table-at-array-field", "JSON_TO_WALK", "tags", "table",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkEmptyTable()),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", LUA_TABLE_CARRIER),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                new deal.codegen.jvm.JvmRuntime.Table())));
        cases.add(walkCase("int-at-nullable-string-field", "JSON_TO_WALK", "note", "int",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1),
                "note", new ClassOpsExecutor.Value.Int(5)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{__a = true, __n = 1, [1] = 1}",
                "note", "5"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L),
                "note", 5L)));
        // A table-content carrier (an unsupported function value inside the
        // table-typed field): the closed function token at its pinned path.
        cases.add(walkCase("function-in-table-content", "JSON_TO_WALK", "data.k",
            "function",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkFunctionTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "{__t = true, __keys = {k = true}, k = function() end}",
                "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                jvmFunctionTable(), "tags", jvmIntArray(1L))));
        // A table-content class instance: the carried canonical class atom at
        // its pinned path, never the class: IR/trace spelling.
        cases.add(walkCase("class-in-table-content", "JSON_TO_WALK", "data.k",
            FOREIGN_ID.text(),
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkClassTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "{__t = true, __keys = {k = true}, k = {__c = true, "
                    + "__id = " + quote(FOREIGN_ID.text())
                    + ", __p = {}, __f = {}}}",
                "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                jvmClassTable(), "tags", jvmIntArray(1L))));
        // The builtin Error carrier at the walk root: the root-identity
        // failure publishes the value's carried canonical class atom
        // (@/Error — ClassId.ERROR.text()), never the superseded
        // class:@builtin/Error spelling (the review correction).
        cases.add(new WalkCase("error-carrier-at-root", "JSON_TO_WALK", "", "@/Error",
            new ClassOpsExecutor.Value.Class(deal.semantic.ir.ClassId.ERROR,
                List.of(new ClassOpsExecutor.FieldState.Present(walkString("E8001")),
                    new ClassOpsExecutor.FieldState.Present(walkString("x")))),
            "{__d = true, code = \"E8001\", m = \"x\"}",
            new deal.codegen.jvm.JvmRuntime.ErrorValue("E8001", "x")));
        // A path-local table re-entry: the cycle arm, no expected/actual.
        cases.add(walkCase("cyclic-table-field", "JSON_TO_WALK_CYCLE", null, null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkCyclicTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "cyc", "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data", jvmCyclicTable(),
                "tags", jvmIntArray(1L))));
        // The array container needle: the table subtree re-enters the array
        // it already entered (data -> array -> table -> the same array), so
        // the cycle is detected at the array container and the cycle arm is
        // selected — the second cycle needle site, on every consumer.
        cases.add(walkCase("cyclic-array-container", "JSON_TO_WALK_CYCLE", null, null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkCyclicArrayViaTables(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "arrCycTable", "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmCyclicArrayViaTables(), "tags", jvmIntArray(1L))));
        // A selected noncycle failure with a path-local table re-entry
        // behind it: the table-typed field holds the unsupported function
        // under 'bad' before its own re-entry under 'self', so the first
        // failure precedes the cycle. Every consumer must render the walk
        // arm at the earlier position (the oracle's adapter returns the
        // selected failure before the E8 seam is ever consulted, so the
        // cycle behind it is never traversed).
        cases.add(walkCase("unsupported-before-table-cycle", "JSON_TO_WALK", "data.bad",
            "function",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkFunctionThenCyclicTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "fnCycTable", "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmFunctionThenCyclicTable(), "tags", jvmIntArray(1L))));
        // The same shape with the cycle behind the failure closed through
        // a mixed table/array path: 'mix' holds an array whose element is
        // the containing table again, so the re-entry needle is the table
        // entered before the array.
        cases.add(walkCase("unsupported-before-mixed-cycle", "JSON_TO_WALK", "data.bad",
            "function",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkFunctionThenMixedCycle(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "fnMixTable", "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmFunctionThenMixedCycle(), "tags", jvmIntArray(1L))));
        // The cycle-first control over the same graph shape: the re-entry
        // sits under 'self' before the unsupported value under 'zbad', so
        // the needle's own closed marker still selects the cycle arm (the
        // selection is the walk's first failure, never the presence of an
        // unsupported value anywhere in the subtree).
        cases.add(walkCase("cycle-before-unsupported-value", "JSON_TO_WALK_CYCLE", null,
            null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Int(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkCyclicTableThenFunction(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "cycFirstTable", "tags", "{__a = true, __n = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmCyclicTableThenFunction(), "tags", jvmIntArray(1L))));
        // A table-content carrier under a key containing braces or the
        // literal spelling of a placeholder: docs/spec-v1.2.md permits
        // arbitrary string keys, so the walk's fieldPath carries the key
        // verbatim and the arm's parameter substitution must publish it
        // byte-for-byte on every consumer — never a producer defect, never
        // a placeholder reinterpretation.
        for (String key : List.of("{bad}", "{actual}", "{fieldPath}", "a}b", "a{b")) {
            cases.add(walkCase("brace-key-" + key, "JSON_TO_WALK",
                "data." + key, "function",
                Map.of("name", walkString("n"),
                    "age", new ClassOpsExecutor.Value.Int(1),
                    "ratio", new ClassOpsExecutor.Value.Number(0.5),
                    "data", walkFunctionTable(key), "tags", walkIntArray(1)),
                Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                    "data", luaFunctionTable(key),
                    "tags", "{__a = true, __n = 1, [1] = 1}"),
                Map.of("name", "n", "age", 1L, "ratio", 0.5,
                    "data", jvmFunctionTable(key), "tags", jvmIntArray(1L))));
        }
        return cases;
    }

    /**
     * The walk arm's three-consumer drive: for each case the oracle's class
     * walk (the production stringify adapter), the emitted Lua walk machinery
     * under real luajit, and the emitted JVM walk machinery select the same
     * closed arm and publish the identical
     * {@code (code, message, origin, expected, actual)} tuple. The drive
     * fails if the authority slice is broken (a changed template or field
     * shape) or if the binding is absent (a marked arm fails closed at the
     * render).
     */
    static void testWalkArmThreeConsumerDrive() throws Exception {
        System.out.println("-- the walk arm's three-consumer drive --");
        List<WalkCase> cases = walkCases();

        // The emitted artifacts: the walk's call sites render the arm the walk
        // selects, through the one arm renderer, at the executing op's own
        // SourceOrigin.
        String lua = emittedWalkChunk();
        check(lua.contains("__eT = __arm(__jarmT, __jcparT, \"" + WALK_SPAN
                + "\", nil, __jfactT)"),
            "the emitted Lua walk site renders the selected arm through the one arm "
                + "renderer at the op's own SourceOrigin");
        check(!lua.contains("__failExpr(\"E8001\", __renderTemplate(\"JSON_TO_WALK\""),
            "the emitted Lua walk site composes no message of its own");
        String jvm = emittedWalkJvm();
        check(jvm.contains("JvmRuntime.arm(deal.semantic.ir.FailureArmId.JSON_TO_WALK, "
                + "java.util.Map.of(\"fieldPath\", projection.fieldPath, \"actual\", "
                + "projection.actual), \"" + WALK_SPAN + "\", null, projection.actual)"),
            "the emitted JVM catch site renders the walk arm at the op's own SourceOrigin");
        check(jvm.contains("JvmRuntime.arm(deal.semantic.ir.FailureArmId.JSON_TO_WALK_CYCLE, "
                + "java.util.Map.of(), \"" + WALK_SPAN + "\", null, null)"),
            "the emitted JVM catch site renders the cycle arm with no parameters");
        check(!jvm.contains("value at \" + projection.fieldPath"),
            "the emitted JVM catch site composes no message of its own");

        // The oracle leg (the class walk with a supplied call origin = the
        // executing op's own SourceOrigin) and the emitted Lua leg.
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts =
            Map.of(WALK_ID, walkLayout());
        deal.semantic.ir.SourceOrigin opOrigin = op.origin();
        String opOriginText = opOrigin.sourceId() + ":" + opOrigin.span().startLine()
            + ":" + opOrigin.span().startColumn();
        checkEq(WALK_SPAN, opOriginText,
            "the drive's op carries the pinned origin span");
        List<String> luaRows = runWalkProbe(cases);
        deal.codegen.jvm.JvmJson.Plan plan = walkPlan();
        Tuple firstOracle = null;
        for (int i = 0; i < cases.size(); i++) {
            WalkCase drive = cases.get(i);
            // The oracle leg: the class walk driven with the executing op's
            // own origin.
            Map<deal.semantic.ir.ValueId, ClassOpsExecutor.Value> values = new LinkedHashMap<>();
            values.put(((deal.semantic.ir.KindPayload.JsonToClassPayload) op.payload())
                .classValue(), drive.oracleValue());
            ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> outcome =
                ClassOpsExecutor.executeJsonToClass(op, values, layouts,
                    deal.semantic.JsonClassAlgorithmAdapter.stringifier(), opOrigin);
            check(outcome instanceof ClassOpsExecutor.Outcome.Failure<
                    ClassOpsExecutor.Value> failure,
                drive.label() + ": the oracle's class walk fails the value");
            if (!(outcome instanceof ClassOpsExecutor.Outcome.Failure<
                    ClassOpsExecutor.Value> failure)) {
                continue;
            }
            BoundaryFailure rendered = failure.failure().failure();
            Tuple oracle = tupleOf(rendered, failure.failure().origin().sourceId() + ":"
                + failure.failure().origin().span().startLine() + ":"
                + failure.failure().origin().span().startColumn());
            checkEq(new Tuple("E8001", drive.message(), WALK_SPAN,
                    null, drive.actual()), oracle,
                drive.label() + ": the oracle renders the bound walk arm's tuple");
            checkEq(failure.failure().origin(), opOrigin,
                drive.label() + ": the class walk renders the operand it was given");
            // The fieldPath metadata is the walk's literal position: a table
            // key reaches it byte-for-byte (the cycle arm carries no position).
            if (drive.fieldPath() == null) {
                checkEq(Map.of(), rendered.metadata(), drive.label()
                    + ": the cycle arm carries no position metadata");
            } else {
                checkEq(drive.fieldPath(), rendered.metadata().get("fieldPath"),
                    drive.label() + ": the walk's fieldPath metadata is the literal "
                        + "position (" + drive.fieldPath() + ")");
            }

            // The emitted Lua leg, under real luajit. The tuple's origin is
            // the row's own rendered origin (never a test constant), so an
            // absent or substituted span fails the comparison by field.
            Tuple luaTuple = walkLuaTuple(luaRows.get(i));
            checkEq(null, firstDifferingField(oracle, luaTuple),
                drive.label() + ": the emitted Lua walk machinery renders the "
                    + "oracle's identical tuple");
            checkEq(WALK_SPAN, luaTuple.span(),
                drive.label() + ": the emitted __arm renders the origin operand the "
                    + "call site carries");

            // The emitted JVM leg (the shared walk machinery the artifact
            // links), rendered exactly like the emitted catch site.
            deal.codegen.jvm.JvmJson.Projection projection = null;
            try {
                deal.codegen.jvm.JvmJson.toClass(plan, drive.jvmRoot());
            } catch (deal.codegen.jvm.JvmJson.Projection caught) {
                projection = caught;
            }
            check(projection != null,
                drive.label() + ": the JVM walk machinery fails the value");
            if (projection == null) {
                continue;
            }
            deal.codegen.jvm.JvmRuntime.DealError jvmError = projection.cycle
                ? deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK_CYCLE,
                    Map.of(), WALK_SPAN, null, null)
                : deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                    Map.of("fieldPath", projection.fieldPath, "actual",
                        projection.actual), WALK_SPAN, null, projection.actual);
            // The JVM leg's origin is the rendered DealError's own origin
            // (never a test constant).
            checkEq(null, firstDifferingField(oracle, new Tuple(jvmError.code,
                    jvmError.msg, jvmError.origin, jvmError.expected, jvmError.actual)),
                drive.label() + ": the JVM walk machinery renders the oracle's "
                    + "identical tuple");
            checkEq(WALK_SPAN, jvmError.origin,
                drive.label() + ": the JVM arm render carries the origin operand the "
                    + "catch site carries");
            checkEq(drive.arm().equals("JSON_TO_WALK_CYCLE"), projection.cycle,
                drive.label() + ": the JVM walk selects the same closed arm");
            if (drive.fieldPath() != null) {
                checkEq(drive.fieldPath(), projection.fieldPath, drive.label()
                    + ": the JVM walk's projection path is the literal position ("
                    + drive.fieldPath() + ")");
            }
            if (i == 0) {
                firstOracle = oracle;
            }
        }

        // The origin negatives (executable on the drive's own comparisons):
        // the emitted row's origin and the JVM render's origin are
        // load-bearing, so a consumer that renders a span other than the
        // operand it received is reported by field name span. The negative
        // mutates the rendered origin itself, not a hand-built tuple.
        check(firstOracle != null, "the drive produced its reference tuple");
        checkEq("span", firstDifferingField(firstOracle, walkLuaTuple(
                withOrigin(luaRows.get(0), "consumer-anchor.deal:1:1"))),
            "a substituted emitted Lua origin is reported by field name span");
        WalkCase firstCase = cases.get(0);
        deal.codegen.jvm.JvmRuntime.DealError substitutedOrigin =
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", firstCase.fieldPath(), "actual",
                    firstCase.actual()), "consumer-anchor.deal:1:1", null,
                firstCase.actual());
        checkEq("span", firstDifferingField(firstOracle, new Tuple(
                substitutedOrigin.code, substitutedOrigin.msg, substitutedOrigin.origin,
                substitutedOrigin.expected, substitutedOrigin.actual)),
            "a consumer-derived JVM origin is reported by field name span");

        // The emitted __arm guard (W5's artifact-surface subject): a marked
        // entry injected into the chunk-level arm table is rejected in every
        // marked binding slot — the projection binding, the origin
        // convention, and a parameter source — before it publishes a tuple.
        // The valid walk and cycle arms stay the controls: the guard is not
        // a blanket refusal of hand-supplied entries.
        List<String> markedRows = runPreludeProbe("""
            __arms["MARKED_PROBE"] = {c = "E8001", t = "marked", e = "NONE",
              a = "SIBLING_OWNED", s = "TOP_LEVEL", o = "CALL_EXPRESSION", k = ""}
            local ok = pcall(__arm, "MARKED_PROBE", nil, "-", nil, nil)
            print("marked-entry|" .. tostring(ok))
            __arms["MARKED_ORIGIN"] = {c = "E8001",
              t = "cyclic value cannot be encoded as JSON", e = "NONE", a = "NONE",
              s = "TOP_LEVEL", o = "SIBLING_OWNED", k = ""}
            ok = pcall(__arm, "MARKED_ORIGIN", nil, "-", nil, nil)
            print("marked-origin|" .. tostring(ok))
            __arms["MARKED_SOURCE"] = {c = "E8001",
              t = "value at {fieldPath} is not JSON serializable: {actual}",
              e = "NONE", a = "TYPED_BOUNDARY", s = "TOP_LEVEL",
              o = "CALL_EXPRESSION",
              k = "fieldPath=FIELD_PATH,actual=SIBLING_OWNED_ACTUAL"}
            ok = pcall(__arm, "MARKED_SOURCE",
              {fieldPath = "data.k", actual = "function"}, "-", nil, "function")
            print("marked-source|" .. tostring(ok))
            ok, value = pcall(__arm, "JSON_TO_WALK",
              {fieldPath = "data.k", actual = "function"}, "-", nil, "function")
            print("walk-control|" .. tostring(ok) .. "|"
              .. (ok and value.m or tostring(value)))
            ok, value = pcall(__arm, "JSON_TO_WALK_CYCLE", nil, "-", nil, nil)
            print("cycle-control|" .. tostring(ok) .. "|"
              .. (ok and value.m or tostring(value)))
            """, "marked-entry-probe", 5);
        checkEq("marked-entry|false", markedRows.get(0),
            "the emitted __arm rejects a marked projection binding injected into "
                + "the chunk-level arm table");
        checkEq("marked-origin|false", markedRows.get(1),
            "the emitted __arm rejects a marked origin convention injected into "
                + "the chunk-level arm table, even with a supplied origin operand");
        checkEq("marked-source|false", markedRows.get(2),
            "the emitted __arm rejects a marked parameter source injected into the "
                + "chunk-level arm table, even with its declared parameters and "
                + "fields supplied");
        checkEq("walk-control|true|value at data.k is not JSON serializable: function",
            markedRows.get(3),
            "the emitted __arm keeps rendering the valid walk arm after the marked-slot "
                + "guards");
        checkEq("cycle-control|true|cyclic value cannot be encoded as JSON",
            markedRows.get(4),
            "the emitted __arm keeps rendering the valid cycle arm after the "
                + "marked-slot guards");

        // The origin operand negative: a walk failure renders the operand its
        // consumer received, and a consumer-derived span is reported by field.
        Tuple reference = tupleOf(FailureContractRegistry.render(
            FailureArmId.JSON_TO_WALK, Map.of("fieldPath", "age", "actual", "number"),
            null, "number", null), WALK_SPAN);
        checkEq("span", firstDifferingField(reference, new Tuple(reference.code(),
                reference.message(), "consumer-anchor.deal:1:1", reference.expected(),
                reference.actual())),
            "a consumer-derived span is reported by field name span");
        checkEq("actual", firstDifferingField(reference, new Tuple(reference.code(),
                reference.message(), reference.span(), reference.expected(),
                "class:arm/walk/Walk")),
            "the superseded class: IR spelling is reported by field name actual");
        // The superseded absent-value spelling ("missing"): the walk arm's
        // closed projection publishes "nil" at an absent required position.
        Tuple absent = tupleOf(FailureContractRegistry.render(
            FailureArmId.JSON_TO_WALK, Map.of("fieldPath", "age", "actual", "nil"),
            null, "nil", null), WALK_SPAN);
        checkEq("nil", absent.actual(),
            "the walk arm's absent-position projection publishes the nil token");
        checkEq("actual", firstDifferingField(absent, new Tuple(absent.code(),
                absent.message(), absent.span(), absent.expected(), "missing")),
            "the superseded absent-value 'missing' spelling is reported by field "
                + "name actual");
        // The superseded root-identity spelling ("shape"): the walk arm's
        // root/identity projection publishes the carried canonical class atom.
        Tuple identity = tupleOf(FailureContractRegistry.render(
            FailureArmId.JSON_TO_WALK, Map.of("fieldPath", "", "actual", "@/Error"),
            null, "@/Error", null), WALK_SPAN);
        checkEq("@/Error", identity.actual(),
            "the walk arm's root-identity projection publishes the carried canonical "
                + "class atom");
        checkEq("actual", firstDifferingField(identity, new Tuple(identity.code(),
                identity.message(), identity.span(), identity.expected(), "shape")),
            "the superseded root 'shape' spelling is reported by field name actual");
        checkEq("message", firstDifferingField(reference, new Tuple(reference.code(),
                "walk: value at age is not JSON serializable: number", reference.span(),
                reference.expected(), reference.actual())),
            "a consumer-composed walk text is reported by field name message");
        // A cycle rendered under the walk arm with a table token instead of
        // the cycle arm's own text is reported by field name message.
        Tuple cycle = tupleOf(FailureContractRegistry.render(
            FailureArmId.JSON_TO_WALK_CYCLE, Map.of(), null, null, null), WALK_SPAN);
        checkEq("cyclic value cannot be encoded as JSON", cycle.message(),
            "the cycle arm renders the pinned cycle text with no fields");
        checkEq("message", firstDifferingField(cycle, new Tuple(cycle.code(),
                "value at data.self is not JSON serializable: table", cycle.span(), null,
                "table")),
            "a cycle rendered under the walk arm with a table token is reported by "
                + "field name message");
        // A projection caller that keys the numeric token on the declared int
        // text publishes "int" where the value-derived rule publishes
        // "number"; the comparison reports the field it changed.
        Tuple declaredKeyed = tupleOf(FailureContractRegistry.render(
            FailureArmId.JSON_TO_WALK, Map.of("fieldPath", "age", "actual", "int"),
            null, "int", null), WALK_SPAN);
        checkEq("int", declaredKeyed.actual(),
            "the declared-text-keyed projection publishes the wrong numeric token");
        checkEq("actual", firstDifferingField(reference, new Tuple(reference.code(),
                reference.message(), reference.span(), reference.expected(),
                declaredKeyed.actual())),
            "a projection caller keying the numeric token on the declared int text is "
                + "reported by field name actual");
    }

    // =========================================================================
    // 14b. The absent origin operand fails closed on both target renderers
    // =========================================================================

    /**
     * The origin contract's fail-closed subject (jsonable-tojson-walk-arm-binding
     * W2/W6): the render's origin operand is the executing op's
     * {@code SourceOrigin}, and an absent operand is a producer defect at
     * both target rendering boundaries — the emitted Lua prelude's
     * {@code __arm} and the JVM runtime's {@code arm} — for the walk arm and
     * the cycle arm alike. No render derives or substitutes a span, so the
     * guard rejects the render instead of publishing an origin-less
     * DEAL-visible tuple. A supplied operand stays green on both renderers
     * and reaches the tuple's origin field, and the oracle's class walk
     * keeps its landed rejection of an absent call origin, so the three
     * consumers agree on the origin contract.
     */
    static void testAbsentOriginFailsClosed() throws Exception {
        System.out.println("-- the absent origin operand fails closed on both "
            + "target renderers --");
        // The JVM target's rendering boundary (the emitted catch site's own
        // call), on both walk arms.
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "data.k", "actual", "function"), null, null,
            "function"),
            "the JVM walk-arm render rejects an absent origin operand");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.arm(
            FailureArmId.JSON_TO_WALK_CYCLE, Map.of(), null, null, null),
            "the JVM cycle-arm render rejects an absent origin operand");
        // The supplied operand stays green on both arms (the controls), and
        // the rendered tuple carries it.
        deal.codegen.jvm.JvmRuntime.DealError jvmWalk =
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "data.k", "actual", "function"), WALK_SPAN, null,
                "function");
        checkEq(WALK_SPAN, jvmWalk.origin,
            "the JVM walk-arm render carries the supplied origin operand");
        checkEq("value at data.k is not JSON serializable: function", jvmWalk.msg,
            "the JVM walk-arm render keeps the arm's own message with its origin");
        deal.codegen.jvm.JvmRuntime.DealError jvmCycle =
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK_CYCLE, Map.of(),
                WALK_SPAN, null, null);
        checkEq(WALK_SPAN, jvmCycle.origin,
            "the JVM cycle-arm render carries the supplied origin operand");
        checkEq("cyclic value cannot be encoded as JSON", jvmCycle.msg,
            "the JVM cycle-arm render keeps the arm's own message with its origin");

        // The emitted Lua target's rendering boundary, under real luajit.
        List<String> rows = runPreludeProbe("""
            local ok = pcall(__arm, "JSON_TO_WALK",
              {fieldPath = "data.k", actual = "function"}, nil, nil, "function")
            print("walk-absent-origin|" .. tostring(ok))
            ok = pcall(__arm, "JSON_TO_WALK_CYCLE", nil, nil, nil, nil)
            print("cycle-absent-origin|" .. tostring(ok))
            ok, value = pcall(__arm, "JSON_TO_WALK",
              {fieldPath = "data.k", actual = "function"}, %s, nil, "function")
            print("walk-origin-control|" .. tostring(ok) .. "|"
              .. (ok and (value.o .. "|" .. value.m) or tostring(value)))
            ok, value = pcall(__arm, "JSON_TO_WALK_CYCLE", nil, %s, nil, nil)
            print("cycle-origin-control|" .. tostring(ok) .. "|"
              .. (ok and (value.o .. "|" .. value.m) or tostring(value)))
            """.formatted(quote(WALK_SPAN), quote(WALK_SPAN)),
            "absent-origin-probe", 4);
        checkEq("walk-absent-origin|false", rows.get(0),
            "the emitted prelude's walk-arm render fails closed when the render "
                + "carries no origin operand");
        checkEq("cycle-absent-origin|false", rows.get(1),
            "the emitted prelude's cycle-arm render fails closed when the render "
                + "carries no origin operand");
        checkEq("walk-origin-control|true|" + WALK_SPAN
                + "|value at data.k is not JSON serializable: function", rows.get(2),
            "the emitted prelude's walk-arm render carries the supplied origin "
                + "operand");
        checkEq("cycle-origin-control|true|" + WALK_SPAN
                + "|cyclic value cannot be encoded as JSON", rows.get(3),
            "the emitted prelude's cycle-arm render carries the supplied origin "
                + "operand");

        // The oracle's class walk keeps its landed rejection of an absent
        // call origin (the third consumer of the same contract).
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        Map<deal.semantic.ir.ValueId, ClassOpsExecutor.Value> values =
            new LinkedHashMap<>();
        values.put(((KindPayload.JsonToClassPayload) op.payload()).classValue(),
            new ClassOpsExecutor.Value.Class(WALK_ID, List.of()));
        boolean oracleRejected = false;
        try {
            ClassOpsExecutor.executeJsonToClass(op, values, Map.of(WALK_ID, walkLayout()),
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), null);
        } catch (NullPointerException expected) {
            oracleRejected = true;
        }
        check(oracleRejected,
            "the oracle's class walk rejects an absent call origin (the landed "
                + "supplied-callOrigin contract)");
    }

    /**
     * The landed present-null read of the emitted Lua walk (the review
     * correction of the binding): the walk's admission checks are landed and
     * this binding does not rebuild them, so the emitted walk keeps its
     * landed {@code (raw == __NULL) and nil or raw} operand — the
     * {@code __NULL} sentinel reaches the declared-descriptor check and the
     * bound typed-boundary projection classifies it as "null". The oracle
     * and the JVM walk admit the sentinel as the language null and serialize
     * JSON null at this position (their landed check sets, pinned by
     * {@code ClassConstructionIntegrationTailTest}'s present-null row); the
     * nullable-admission correction and its success regression belong to the
     * follow-up slice that owns the walk's check sets, so this drive pins the
     * landed Lua failure — the exact trigger of the recorded
     * rejection-to-success finding — without claiming the correction.
     */
    static void testLandedPresentNullRead() throws Exception {
        System.out.println("-- the landed present-null read on a nullable field --");
        Map<String, ClassOpsExecutor.Value> oracleFields = new LinkedHashMap<>();
        oracleFields.put("name", walkString("n"));
        oracleFields.put("age", new ClassOpsExecutor.Value.Int(1));
        oracleFields.put("ratio", new ClassOpsExecutor.Value.Number(0.5));
        oracleFields.put("data", walkEmptyTable());
        oracleFields.put("tags", walkIntArray(1));
        oracleFields.put("note", ClassOpsExecutor.Value.Null.INSTANCE);
        Map<String, String> luaFields = new LinkedHashMap<>();
        luaFields.put("name", luaString("n"));
        luaFields.put("age", "1");
        luaFields.put("ratio", "0.5");
        luaFields.put("data", LUA_TABLE_CARRIER);
        luaFields.put("tags", "{__a = true, __n = 1, [1] = 1}");
        luaFields.put("note", "__NULL");
        Map<String, Object> jvmFields = new LinkedHashMap<>();
        jvmFields.put("name", "n");
        jvmFields.put("age", 1L);
        jvmFields.put("ratio", 0.5);
        jvmFields.put("data", new deal.codegen.jvm.JvmRuntime.Table());
        jvmFields.put("tags", jvmIntArray(1L));
        jvmFields.put("note", null);
        String expectedText = "{\"name\":\"n\",\"age\":1,\"ratio\":0.5,"
            + "\"data\":{},\"tags\":[1],\"note\":null}";

        // The emitted Lua leg under real luajit: the landed read leaves the
        // sentinel in place, so the walk fails the position and the bound
        // projection renders its closed null token.
        List<String> rows = runWalkProbe(List.of(walkCase("present-null-nullable",
            "JSON_TO_WALK", "note", "null", oracleFields, luaFields, jvmFields)));
        checkEq(new Tuple("E8001",
                "value at note is not JSON serializable: null", WALK_SPAN, null, "null"),
            walkLuaTuple(rows.get(0)),
            "the emitted Lua walk keeps the landed present-null read: the __NULL "
                + "sentinel reaches the declared-descriptor check and the bound "
                + "projection classifies it as null");

        // The oracle's and the JVM's own landed check sets admit the sentinel
        // and serialize JSON null (the per-target difference the follow-up
        // slice's nullable-admission correction owns).
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> oracleOutcome =
            ClassOpsExecutor.executeJsonToClass(op,
                Map.of(((KindPayload.JsonToClassPayload) op.payload()).classValue(),
                    walkOracleInstance(oracleFields)),
                Map.of(WALK_ID, walkLayout()),
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), op.origin());
        check(oracleOutcome instanceof ClassOpsExecutor.Outcome.Success<
                ClassOpsExecutor.Value> success
                && success.value() instanceof ClassOpsExecutor.Value.String text
                && text.scalar() instanceof deal.semantic.ir.UnicodeScalars.Valid valid
                && valid.carrier().equals(expectedText),
            "the oracle's landed check set admits the present null on the nullable "
                + "field and serializes JSON null; got " + oracleOutcome);
        String jvmText = deal.codegen.jvm.JvmJson.toClass(walkPlan(),
            walkJvmInstance(jvmFields));
        checkEq(expectedText, jvmText,
            "the JVM walk's landed check set admits the present null on the nullable "
                + "field and serializes JSON null");
    }

    /**
     * The walk arm's oracle-only numeric decomposition (V2): the two numeric
     * positions the oracle's landed check set fails while the emitted Lua
     * walk's landed check set admits them (the landed per-target check sets)
     * are asserted on the oracle alone — a nonfinite value at an int-typed
     * declared field and a fractional value at an {@code array(int)} element,
     * each projecting the value's own variant ({@code number}), never the
     * declared {@code int} text beside the position, at the call origin the
     * drive supplied.
     */
    static void testWalkOracleNumericPositions() {
        System.out.println("-- the walk arm's oracle-only numeric positions --");
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts =
            Map.of(WALK_ID, walkLayout());
        deal.semantic.ir.SourceOrigin callOrigin = new deal.semantic.ir.SourceOrigin(
            "arm-walk.deal",
            new deal.semantic.ir.SourceSpan("arm-walk.deal", 7, 4, 7, 12),
            deal.semantic.ir.SourceOriginKind.USER, new deal.semantic.ir.AnchorId(0),
            null);
        String callOriginText = callOrigin.sourceId() + ":"
            + callOrigin.span().startLine() + ":" + callOrigin.span().startColumn();

        Map<String, ClassOpsExecutor.Value> nonfiniteIntField = new LinkedHashMap<>();
        nonfiniteIntField.put("name", walkString("n"));
        nonfiniteIntField.put("age",
            new ClassOpsExecutor.Value.Number(Double.POSITIVE_INFINITY));
        nonfiniteIntField.put("ratio", new ClassOpsExecutor.Value.Number(0.5));
        nonfiniteIntField.put("data", walkEmptyTable());
        nonfiniteIntField.put("tags", walkIntArray(1));
        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "nonfinite-int-field", nonfiniteIntField, "age", "number");

        Map<String, ClassOpsExecutor.Value> fractionalElement = new LinkedHashMap<>();
        fractionalElement.put("name", walkString("n"));
        fractionalElement.put("age", new ClassOpsExecutor.Value.Int(1));
        fractionalElement.put("ratio", new ClassOpsExecutor.Value.Number(0.5));
        fractionalElement.put("data", walkEmptyTable());
        fractionalElement.put("tags", new ClassOpsExecutor.Value.Array(
            deal.semantic.ir.SemanticArray.of(
                List.of(new ClassOpsExecutor.Value.Number(0.25)))));
        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "fractional-array-int-element", fractionalElement, "tags[0]", "number");
    }

    /**
     * The walk drive's oracle-only kind-mismatch layout: a null descriptor
     * and a boolean descriptor, the two declared positions the three-consumer
     * drive's layout does not carry. Both fields are optional so the
     * dedicated drives place exactly the failing position and the walk skips
     * every absent one; the value-derived kind of the present wrong-kind
     * value is the arm's actual token.
     */
    private static deal.semantic.ir.ClassLayout walkKindLayout() {
        return new deal.semantic.ir.ClassLayout(WALK_ID, List.of(
            new deal.semantic.ir.ClassLayout.FieldLayout("nul",
                RuntimeDescriptor.Null.INSTANCE, false,
                deal.semantic.ir.DefaultOwner.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("flag",
                RuntimeDescriptor.Boolean.INSTANCE, false,
                deal.semantic.ir.DefaultOwner.LOCAL)));
    }

    /**
     * The walk arm's oracle-only null/boolean descriptor decomposition (V2):
     * a wrong-kind value at a {@code null}-typed declared field and at a
     * {@code boolean}-typed declared field each publish the walk arm's tuple
     * — the failing value's own closed kind as both the {@code {actual}}
     * message parameter and the {@code actual} field, the supplied call
     * origin, and no expected field — never the declared descriptor text.
     * The descriptor's own kind at each position is the success control (the
     * position admits its value and the walk serializes it).
     */
    static void testWalkOracleKindPositions() {
        System.out.println("-- the walk arm's oracle-only null/boolean descriptor "
            + "positions --");
        deal.semantic.ir.SemanticOp op = walkJsonOp(walkKindLayout());
        Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts =
            Map.of(WALK_ID, walkKindLayout());
        deal.semantic.ir.SourceOrigin callOrigin = new deal.semantic.ir.SourceOrigin(
            "arm-walk.deal",
            new deal.semantic.ir.SourceSpan("arm-walk.deal", 9, 6, 9, 14),
            deal.semantic.ir.SourceOriginKind.USER, new deal.semantic.ir.AnchorId(0),
            null);
        String callOriginText = callOrigin.sourceId() + ":"
            + callOrigin.span().startLine() + ":" + callOrigin.span().startColumn();

        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "string-at-null-descriptor", kindFields("nul", walkString("x")), "nul",
            "string");
        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "table-at-null-descriptor", kindFields("nul", walkEmptyTable()), "nul",
            "table");
        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "int-at-boolean-descriptor", kindFields("flag",
                new ClassOpsExecutor.Value.Int(7)), "flag", "int");
        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "string-at-boolean-descriptor", kindFields("flag", walkString("x")),
            "flag", "string");

        // The success controls: each descriptor's own kind at its position is
        // admitted and serialized (the null descriptor's null, the boolean
        // descriptor's boolean).
        checkWalkOracleSuccess(op, layouts, callOrigin, "null-descriptor-control",
            kindFields("nul", ClassOpsExecutor.Value.Null.INSTANCE),
            "{\"nul\":null}");
        checkWalkOracleSuccess(op, layouts, callOrigin, "boolean-descriptor-control",
            kindFields("flag", new ClassOpsExecutor.Value.Bool(true)),
            "{\"flag\":true}");
    }

    /** The kind-mismatch drive's one-field instance (declaration order). */
    private static Map<String, ClassOpsExecutor.Value> kindFields(
            String name, ClassOpsExecutor.Value value) {
        Map<String, ClassOpsExecutor.Value> fields = new LinkedHashMap<>();
        fields.put(name, value);
        return fields;
    }

    /** One oracle-only walk position's full tuple at the supplied call origin. */
    private static void checkWalkOracleTuple(
            deal.semantic.ir.SemanticOp op,
            Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts,
            deal.semantic.ir.SourceOrigin callOrigin, String callOriginText,
            String label, Map<String, ClassOpsExecutor.Value> fields, String fieldPath,
            String token) {
        Map<deal.semantic.ir.ValueId, ClassOpsExecutor.Value> values =
            new LinkedHashMap<>();
        values.put(((deal.semantic.ir.KindPayload.JsonToClassPayload) op.payload())
            .classValue(), walkOracleInstance(op, fields));
        ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> outcome =
            ClassOpsExecutor.executeJsonToClass(op, values, layouts,
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(outcome instanceof ClassOpsExecutor.Outcome.Failure<
                ClassOpsExecutor.Value> failure,
            label + ": the oracle's class walk fails the value");
        if (!(outcome instanceof ClassOpsExecutor.Outcome.Failure<
                ClassOpsExecutor.Value> failure)) {
            return;
        }
        checkEq(new Tuple("E8001",
                "value at " + fieldPath + " is not JSON serializable: " + token,
                callOriginText, null, token),
            tupleOf(failure.failure().failure(), callOriginText),
            label + ": the oracle renders the walk arm's tuple with the failing "
                + "value's own " + token + " token, never the declared descriptor "
                + "text beside the position");
        checkEq(callOrigin, failure.failure().origin(),
            label + ": the walk renders the supplied call origin");
        check(!failure.failure().origin().equals(op.origin()),
            label + ": the walk never substitutes the op's own synthetic anchor");
    }

    /** One oracle-only walk position's admitted value and serialized JSON text. */
    private static void checkWalkOracleSuccess(
            deal.semantic.ir.SemanticOp op,
            Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts,
            deal.semantic.ir.SourceOrigin callOrigin, String label,
            Map<String, ClassOpsExecutor.Value> fields, String expectedJson) {
        Map<deal.semantic.ir.ValueId, ClassOpsExecutor.Value> values =
            new LinkedHashMap<>();
        values.put(((deal.semantic.ir.KindPayload.JsonToClassPayload) op.payload())
            .classValue(), walkOracleInstance(op, fields));
        ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> outcome =
            ClassOpsExecutor.executeJsonToClass(op, values, layouts,
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(outcome instanceof ClassOpsExecutor.Outcome.Success<
                ClassOpsExecutor.Value> success
                && success.value() instanceof ClassOpsExecutor.Value.String text
                && text.scalar() instanceof deal.semantic.ir.UnicodeScalars.Valid valid
                && valid.carrier().equals(expectedJson),
            label + ": the descriptor's own kind at its position is admitted and "
                + "serializes " + expectedJson + "; got " + outcome);
    }

    /** A one-field drive case of the walk's three-consumer comparison. */
    private static WalkCase walkCase(String label, String arm, String fieldPath,
                                     String actual,
                                     Map<String, ClassOpsExecutor.Value> oracleFields,
                                     Map<String, String> luaFields,
                                     Map<String, Object> jvmFields) {
        return new WalkCase(label, arm, fieldPath, actual,
            walkOracleInstance(oracleFields), walkLuaRoot(luaFields),
            walkJvmInstance(jvmFields));
    }

    /** One oracle walk instance of the drive's layout (declaration order). */
    private static ClassOpsExecutor.Value walkOracleInstance(
            Map<String, ClassOpsExecutor.Value> fields) {
        return walkOracleInstance(walkLayout(), fields);
    }

    /** One oracle walk instance of one layout (declaration order). */
    private static ClassOpsExecutor.Value walkOracleInstance(
            deal.semantic.ir.ClassLayout layout,
            Map<String, ClassOpsExecutor.Value> fields) {
        List<ClassOpsExecutor.FieldState> states = new ArrayList<>();
        for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
            ClassOpsExecutor.Value value = fields.get(field.name());
            states.add(value == null ? ClassOpsExecutor.FieldState.Missing.INSTANCE
                : new ClassOpsExecutor.FieldState.Present(value));
        }
        return new ClassOpsExecutor.Value.Class(WALK_ID, List.copyOf(states));
    }

    /** One oracle walk instance of the op's payload layout (declaration order). */
    private static ClassOpsExecutor.Value walkOracleInstance(
            deal.semantic.ir.SemanticOp op,
            Map<String, ClassOpsExecutor.Value> fields) {
        return walkOracleInstance(
            ((deal.semantic.ir.KindPayload.JsonToClassPayload) op.payload()).layout(),
            fields);
    }

    /** One emitted-Lua walk instance of the drive's layout (the carrier shape). */
    private static String walkLuaRoot(Map<String, String> fields) {
        StringBuilder present = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (present.length() > 0) {
                present.append(", ");
                values.append(", ");
            }
            present.append(entry.getKey()).append(" = true");
            values.append(entry.getKey()).append(" = ").append(entry.getValue());
        }
        return "{__c = true, __id = " + quote(WALK_ID.text()) + ", __p = {" + present
            + "}, __f = {" + values + "}}";
    }

    /** One JVM walk instance of the drive's layout (the generated carrier shape). */
    private static deal.codegen.jvm.JvmRuntime.ClassInstance walkJvmInstance(
            Map<String, Object> fields) {
        return new WalkInstance(WALK_ID.text(), fields);
    }

    /** A null-valued field map (present null, distinct from the absent field). */
    private static Map<String, Object> walkNullField(String name) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", null);
        fields.put("age", 1L);
        fields.put("ratio", 0.5);
        fields.put("data", new deal.codegen.jvm.JvmRuntime.Table());
        fields.put("tags", jvmIntArray(1L));
        return fields;
    }

    private static ClassOpsExecutor.Value walkString(String carrier) {
        return ClassOpsExecutor.Value.string(carrier);
    }

    /** The oracle's function carrier (the wrapper's value on the oracle leg). */
    private static ClassOpsExecutor.Value walkFunction() {
        return new ClassOpsExecutor.Value.Function(new RuntimeDescriptor.Func(
            List.of(), RuntimeDescriptor.Null.INSTANCE, false));
    }

    /** The JVM's function carrier (the wrapper's value on the JVM leg). */
    private static deal.codegen.jvm.JvmRuntime.FunctionValue walkJvmFunction() {
        return new deal.codegen.jvm.JvmRuntime.FunctionValue(args -> null, "()->number");
    }

    private static ClassOpsExecutor.Value walkEmptyTable() {
        return new ClassOpsExecutor.Value.Table(new deal.semantic.ir.SemanticTable<>());
    }

    private static ClassOpsExecutor.Value walkIntArray(int... values) {
        List<ClassOpsExecutor.Value> elements = new ArrayList<>();
        for (int value : values) {
            elements.add(new ClassOpsExecutor.Value.Int(value));
        }
        return new ClassOpsExecutor.Value.Array(
            deal.semantic.ir.SemanticArray.of(elements));
    }

    /** The drive's array(int) value with an integral first element and a nonfinite second. */
    private static ClassOpsExecutor.Value walkIntThenNonfiniteArray() {
        return new ClassOpsExecutor.Value.Array(deal.semantic.ir.SemanticArray.of(
            List.of(new ClassOpsExecutor.Value.Int(1),
                new ClassOpsExecutor.Value.Number(Double.POSITIVE_INFINITY))));
    }

    private static ClassOpsExecutor.Value walkCyclicTable() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        ClassOpsExecutor.Value carrier = new ClassOpsExecutor.Value.Table(table);
        table.put("self", carrier);
        return carrier;
    }

    /**
     * The drive's table-typed field with an unsupported function value under
     * {@code bad} and the table's own path-local re-entry under {@code self}
     * behind it: the walk's first failure precedes the cycle, so every
     * consumer selects the walk arm at {@code data.bad}. The keys are declared
     * in ascending order, so the oracle's first-insertion walk and the emitted
     * Lua/JVM ascending-key walks agree on the position.
     */
    private static ClassOpsExecutor.Value walkFunctionThenCyclicTable() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        ClassOpsExecutor.Value carrier = new ClassOpsExecutor.Value.Table(table);
        table.put("bad", walkFunction());
        table.put("self", carrier);
        return carrier;
    }

    /**
     * The same shape with the cycle closed through a mixed table/array path:
     * {@code mix} holds an array whose element is the containing table again,
     * so the re-entry needle is the table the walk entered before the array.
     */
    private static ClassOpsExecutor.Value walkFunctionThenMixedCycle() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        ClassOpsExecutor.Value carrier = new ClassOpsExecutor.Value.Table(table);
        ClassOpsExecutor.Value array = new ClassOpsExecutor.Value.Array(
            deal.semantic.ir.SemanticArray.of(List.of(carrier)));
        table.put("bad", walkFunction());
        table.put("mix", array);
        return carrier;
    }

    /**
     * The cycle-first control: the path-local re-entry under {@code self}
     * precedes the unsupported value under {@code zbad}, so the needle's own
     * marker selects the cycle arm on every consumer.
     */
    private static ClassOpsExecutor.Value walkCyclicTableThenFunction() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        ClassOpsExecutor.Value carrier = new ClassOpsExecutor.Value.Table(table);
        table.put("self", carrier);
        table.put("zbad", walkFunction());
        return carrier;
    }

    /** The drive's table-typed field holding one function value under {@code k}. */
    private static ClassOpsExecutor.Value walkFunctionTable() {
        return walkFunctionTable("k");
    }

    /** The drive's table-typed field holding one function value under {@code key}. */
    private static ClassOpsExecutor.Value walkFunctionTable(String key) {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        table.put(key, walkFunction());
        return new ClassOpsExecutor.Value.Table(table);
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmFunctionTable() {
        return jvmFunctionTable("k");
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmFunctionTable(String key) {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write(key, walkJvmFunction());
        return table;
    }

    /**
     * The emitted Lua carrier of a table holding one function under
     * {@code key} (the walk's own key-presence shape): the key is a quoted
     * string literal, so a key containing braces or a placeholder spelling
     * reaches the walk verbatim.
     */
    private static String luaFunctionTable(String key) {
        return "{__t = true, __keys = {[" + luaString(key) + "] = true}, ["
            + luaString(key) + "] = function() end}";
    }

    /** The drive's table-typed field holding one class instance under {@code k}. */
    private static ClassOpsExecutor.Value walkClassTable() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        table.put("k", new ClassOpsExecutor.Value.Class(FOREIGN_ID, List.of()));
        return new ClassOpsExecutor.Value.Table(table);
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmClassTable() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write("k", new WalkInstance(FOREIGN_ID.text(), Map.of()));
        return table;
    }

    /**
     * The drive's array-container cycle: the table subtree {@code data} holds
     * the array {@code a}; the array holds the second table {@code b}; that
     * table holds the same array again, so the re-entry is detected at the
     * array container.
     */
    private static ClassOpsExecutor.Value walkCyclicArrayViaTables() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> second =
            new deal.semantic.ir.SemanticTable<>();
        ClassOpsExecutor.Value secondValue =
            new ClassOpsExecutor.Value.Table(second);
        ClassOpsExecutor.Value array = new ClassOpsExecutor.Value.Array(
            deal.semantic.ir.SemanticArray.of(List.of(secondValue)));
        second.put("b", array);
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> first =
            new deal.semantic.ir.SemanticTable<>();
        first.put("a", array);
        return new ClassOpsExecutor.Value.Table(first);
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmCyclicArrayViaTables() {
        deal.codegen.jvm.JvmRuntime.Table second = new deal.codegen.jvm.JvmRuntime.Table();
        deal.codegen.jvm.JvmRuntime.Array array = new deal.codegen.jvm.JvmRuntime.Array(1);
        array.elements.add(second);
        second.write("b", array);
        deal.codegen.jvm.JvmRuntime.Table first = new deal.codegen.jvm.JvmRuntime.Table();
        first.write("a", array);
        return first;
    }

    private static deal.codegen.jvm.JvmRuntime.Array jvmIntArray(long... values) {
        deal.codegen.jvm.JvmRuntime.Array array =
            new deal.codegen.jvm.JvmRuntime.Array(values.length);
        for (long value : values) {
            array.elements.add(value);
        }
        return array;
    }

    private static deal.codegen.jvm.JvmRuntime.Array jvmNumberArray(long first,
                                                                     double second) {
        deal.codegen.jvm.JvmRuntime.Array array =
            new deal.codegen.jvm.JvmRuntime.Array(2);
        array.elements.add(first);
        array.elements.add(second);
        return array;
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmCyclicTable() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write("self", table);
        return table;
    }

    /** The JVM leg of the unsupported-value-before-table-cycle graph. */
    private static deal.codegen.jvm.JvmRuntime.Table jvmFunctionThenCyclicTable() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write("bad", walkJvmFunction());
        table.write("self", table);
        return table;
    }

    /** The JVM leg of the unsupported-value-before-mixed-cycle graph. */
    private static deal.codegen.jvm.JvmRuntime.Table jvmFunctionThenMixedCycle() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        deal.codegen.jvm.JvmRuntime.Array array = new deal.codegen.jvm.JvmRuntime.Array(1);
        array.elements.add(table);
        table.write("bad", walkJvmFunction());
        table.write("mix", array);
        return table;
    }

    /** The JVM leg of the cycle-first control. */
    private static deal.codegen.jvm.JvmRuntime.Table jvmCyclicTableThenFunction() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write("self", table);
        table.write("zbad", walkJvmFunction());
        return table;
    }

    private static final String LUA_TABLE_CARRIER = "{__t = true, __keys = {}}";

    /** The JVM walk plan of the drive's layout. */
    private static deal.codegen.jvm.JvmJson.Plan walkPlan() {
        return new deal.codegen.jvm.JvmJson.Plan(WALK_ID.text(),
            new deal.codegen.jvm.JvmJson.Field[] {
                new deal.codegen.jvm.JvmJson.Field("name", "string", false, "string"),
                new deal.codegen.jvm.JvmJson.Field("age", "int", false, "int"),
                new deal.codegen.jvm.JvmJson.Field("ratio", "number", false, "number"),
                new deal.codegen.jvm.JvmJson.Field("data", "table", false, "table"),
                new deal.codegen.jvm.JvmJson.Field("tags", "array(int)", false, "int"),
                new deal.codegen.jvm.JvmJson.Field("note", "nullable:string", true,
                    "string"),
            }, null);
    }

    /**
     * The emitted Lua probe's rows (one row per drive case): the walk's own
     * {@code __jsonToClassOp} result rendered through the prelude's arm
     * renderer at the op's origin.
     */
    private static List<String> runWalkProbe(List<WalkCase> cases) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("local cyc = {__t = true, __keys = {self = true}}\n");
        body.append("cyc[\"self\"] = cyc\n");
        // The array-container cycle: data -> array -> table -> the same array
        // (the needle is detected at the array container's re-entry, never at
        // the table's).
        body.append("local arrCycTable = {__t = true, __keys = {a = true}}\n");
        body.append("local arrCycTblB = {__t = true, __keys = {b = true}}\n");
        body.append("local arrCycArr = {__a = true, __n = 1, arrCycTblB}\n");
        body.append("arrCycTblB.b = arrCycArr\n");
        body.append("arrCycTable.a = arrCycArr\n");
        // A selected noncycle failure with a table cycle behind it: 'bad'
        // sorts before 'self', so both the ascending-key walk and the
        // first-insertion declaration order meet the unsupported value
        // first.
        body.append("local fnCycTable = {__t = true, __keys = {bad = true, "
            + "self = true}}\n");
        body.append("fnCycTable.bad = function() end\n");
        body.append("fnCycTable.self = fnCycTable\n");
        // The same shape with the cycle behind the failure closed through a
        // mixed table/array path.
        body.append("local fnMixTable = {__t = true, __keys = {bad = true, "
            + "mix = true}}\n");
        body.append("fnMixTable.bad = function() end\n");
        body.append("fnMixTable.mix = {__a = true, __n = 1, fnMixTable}\n");
        // The cycle-first control over the same graph shape.
        body.append("local cycFirstTable = {__t = true, __keys = {self = true, "
            + "zbad = true}}\n");
        body.append("cycFirstTable.self = cycFirstTable\n");
        body.append("cycFirstTable.zbad = function() end\n");
        body.append("local cases = {\n");
        for (WalkCase drive : cases) {
            body.append("  {label = ").append(quote(drive.label())).append(", root = ")
                .append(drive.luaRoot()).append("},\n");
        }
        body.append("}\n");
        body.append("for i = 1, #cases do\n");
        body.append("  local c = cases[i]\n");
        body.append("  local ok, text, arm, params, actual = "
            + "__jsonToClassOp(\"").append(WALK_ID.text()).append("\", c.root)\n");
        body.append("  if ok then print(c.label .. \"|OK|\") return end\n");
        body.append("  local value = __arm(arm, params, \"")
            .append(WALK_SPAN).append("\", nil, actual)\n");
        body.append("  print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n");
        body.append("end\n");
        return runProbe(emittedWalkChunk(), "arm-walk", body.toString(), "walk-probe",
            cases.size());
    }

    /** A minimal generated-class carrier for the JVM walk drive. */
    private static final class WalkInstance
            implements deal.codegen.jvm.JvmRuntime.ClassInstance {

        private final String classId;
        private final Map<String, Object> values;
        private final java.util.Set<String> present = new java.util.LinkedHashSet<>();

        WalkInstance(String classId, Map<String, Object> values) {
            this.classId = classId;
            this.values = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                this.values.put(entry.getKey(), entry.getValue());
                this.present.add(entry.getKey());
            }
        }

        @Override
        public String classIdText() {
            return classId;
        }

        @Override
        public boolean isPresent(String field) {
            return present.contains(field);
        }

        @Override
        public Object read(String field) {
            return present.contains(field) ? values.get(field)
                : deal.codegen.jvm.JvmRuntime.MISSING;
        }

        @Override
        public void write(String field, Object value) {
            throw new UnsupportedOperationException("the walk drive never writes");
        }

        @Override
        public void delete(String field) {
            throw new UnsupportedOperationException("the walk drive never deletes");
        }
    }

    /** The JSON_TO_CLASS op of the walk drive (the generated body's op shape). */
    private static deal.semantic.ir.SemanticOp walkJsonOp() {
        return walkJsonOp(new deal.semantic.ir.OpId(
                new deal.semantic.ir.ModuleId("arm-walk"), 3),
            new deal.semantic.ir.ValueId(1), new deal.semantic.ir.ValueId(4),
            walkLayout());
    }

    /** The same op shape over one class layout (the oracle-only drives). */
    private static deal.semantic.ir.SemanticOp walkJsonOp(
            deal.semantic.ir.ClassLayout layout) {
        return walkJsonOp(new deal.semantic.ir.OpId(
                new deal.semantic.ir.ModuleId("arm-walk"), 3),
            new deal.semantic.ir.ValueId(1), new deal.semantic.ir.ValueId(4), layout);
    }

    /** The same op shape over one produced class-value operand and its own ids. */
    private static deal.semantic.ir.SemanticOp walkJsonOp(
            deal.semantic.ir.OpId opId, deal.semantic.ir.ValueId classValue,
            deal.semantic.ir.ValueId result) {
        return walkJsonOp(opId, classValue, result, walkLayout());
    }

    /** The same op shape over one produced class-value operand and one layout. */
    private static deal.semantic.ir.SemanticOp walkJsonOp(
            deal.semantic.ir.OpId opId, deal.semantic.ir.ValueId classValue,
            deal.semantic.ir.ValueId result, deal.semantic.ir.ClassLayout layout) {
        deal.semantic.ir.ModuleId module = opId.module();
        deal.semantic.ir.SourceSpan span = new deal.semantic.ir.SourceSpan(
            "arm-walk.deal", 2, 8, 2, 18);
        deal.semantic.ir.SourceOrigin origin = new deal.semantic.ir.SourceOrigin(
            "arm-walk.deal", span, deal.semantic.ir.SourceOriginKind.SYNTHETIC,
            new deal.semantic.ir.AnchorId(0), null);
        KindPayload.JsonToClassPayload payload =
            new KindPayload.JsonToClassPayload(classValue, layout);
        deal.semantic.ir.OpResultType resultType = RuntimeDescriptor.String.INSTANCE;
        deal.semantic.ir.OperationContractSnapshot contract =
            new deal.semantic.ir.OperationContractSnapshot(
                deal.semantic.ir.OperationContractSnapshot.VERSION,
                deal.semantic.ir.SemanticOpKind.JSON_TO_CLASS, resultType,
                List.of(new RuntimeDescriptor.Class(WALK_ID)), null, payload,
                FailurePolicyId.JSON_TO_ERROR, List.of(), "placeholder");
        contract = new deal.semantic.ir.OperationContractSnapshot(
            deal.semantic.ir.OperationContractSnapshot.VERSION,
            deal.semantic.ir.SemanticOpKind.JSON_TO_CLASS, resultType,
            List.of(new RuntimeDescriptor.Class(WALK_ID)), null, payload,
            FailurePolicyId.JSON_TO_ERROR, List.of(),
            deal.semantic.ir.ContractSnapshotCanonicalizer.digest(contract));
        return new deal.semantic.ir.SemanticOp(opId,
            deal.semantic.ir.SemanticOpKind.JSON_TO_CLASS, origin,
            result, resultType, List.of(classValue),
            List.of(new RuntimeDescriptor.Class(WALK_ID)), payload,
            FailurePolicyId.JSON_TO_ERROR, contract);
    }

    /** The walk drive's module unit (its own class layout and JSON walk call site). */
    private static deal.semantic.ir.ExecutableLoweredProject walkProject() {
        return walkProject(List.of());
    }

    /**
     * The walk drive's module unit with the given extra module-init ops
     * prepended (the runtime-sentinel drive carries its
     * {@code MODULE_IMPORT(HOST)} op, so its production chunk binds the
     * deployed runtime and emits the host-boundary cells).
     */
    private static deal.semantic.ir.ExecutableLoweredProject walkProject(
            List<deal.semantic.ir.SemanticOp> extraOps) {
        deal.semantic.ir.ModuleId entry = new deal.semantic.ir.ModuleId("arm-walk");
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>(extraOps);
        ops.add(op);
        deal.semantic.ir.BlockId initBlock = new deal.semantic.ir.BlockId(0);
        deal.semantic.ir.LoweredModuleUnit unit =
            new deal.semantic.ir.LoweredModuleUnit(
                deal.semantic.ir.LoweredModuleUnit.FORMAT_VERSION,
                deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, entry, "hash",
                "context", java.util.Set.of(), Map.of(), Map.of(WALK_ID, walkLayout()),
                Map.of(), new deal.semantic.ir.ModuleInitPlan(List.of(), initBlock),
                new deal.semantic.ir.ExportPlan(List.of()), Map.of(), ops);
        Map<deal.semantic.ir.ModuleId, deal.semantic.ir.LoweredModuleUnit> modules =
            new LinkedHashMap<>();
        modules.put(entry, unit);
        deal.semantic.ir.ProjectInterfaceIndex index =
            new deal.semantic.ir.ProjectInterfaceIndex(
                deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION,
                Map.of(entry, new deal.semantic.ir.ExternalModuleInterface(entry,
                    deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(),
                    List.of(), List.of(),
                    deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        return new deal.semantic.ir.ExecutableLoweredProject(
            deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, index, modules, entry);
    }

    /** The walk chunk's table (the JSON walk op in the module-init block). */
    private static Map<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>
            walkTables() {
        return walkTables(List.of());
    }

    /** The walk chunk's table with the given extra module-init ops. */
    private static Map<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable>
            walkTables(List<deal.semantic.ir.SemanticOp> extraOps) {
        deal.semantic.ir.ModuleId entry = new deal.semantic.ir.ModuleId("arm-walk");
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        deal.semantic.ir.BlockId initBlock = new deal.semantic.ir.BlockId(0);
        List<deal.semantic.ir.OpId> members = new ArrayList<>();
        Map<deal.semantic.ir.OpId, deal.semantic.ir.BlockId> membership =
            new LinkedHashMap<>();
        for (deal.semantic.ir.SemanticOp extra : extraOps) {
            members.add(extra.opId());
            membership.put(extra.opId(), initBlock);
        }
        members.add(op.opId());
        membership.put(op.opId(), initBlock);
        Map<deal.semantic.ir.ModuleId, deal.semantic.ir.StructuredBodyTable> tables =
            new LinkedHashMap<>();
        tables.put(entry, new deal.semantic.ir.StructuredBodyTable(
            Map.of(initBlock, members), membership));
        return tables;
    }

    /** The production Lua chunk of the walk drive project. */
    private static String emittedWalkChunk() {
        if (walkChunk == null) {
            walkChunk = LuaSemanticEmitter.emitProductionProject(walkProject(), walkTables(),
                Map.of(), emptySurface());
        }
        return walkChunk;
    }

    /** The host-import module identity of the runtime-sentinel walk drive. */
    private static final deal.semantic.ir.ModuleId WALK_HOST_MODULE =
        new deal.semantic.ir.ModuleId("host/runtime");

    /** The declaration surface of the runtime-sentinel drive's host import. */
    private static HostDeclarationSurface walkHostSurface() {
        return new HostDeclarationSurface(Map.of(WALK_HOST_MODULE,
            new HostDeclarationSurface.DeclarationFacts(WALK_HOST_MODULE,
                HostDeclarationSurface.DeclarationKind.HOST, Map.of(), Map.of())));
    }

    /**
     * The module-init {@code MODULE_IMPORT(HOST)} op of the runtime-sentinel
     * drive: with it the production chunk is a host-binding chunk (the
     * emitted {@code __rtNull} sentinel binding and the host-boundary
     * cells), exactly like a production project with a host import.
     */
    private static deal.semantic.ir.SemanticOp walkHostImportOp() {
        deal.semantic.ir.ModuleId entry = new deal.semantic.ir.ModuleId("arm-walk");
        deal.semantic.ir.SourceOrigin origin = new deal.semantic.ir.SourceOrigin(
            "arm-walk.deal",
            new deal.semantic.ir.SourceSpan("arm-walk.deal", 1, 1, 1, 20),
            deal.semantic.ir.SourceOriginKind.USER, new deal.semantic.ir.AnchorId(1),
            null);
        return dispatchOp(new deal.semantic.ir.OpId(entry, 7),
            deal.semantic.ir.SemanticOpKind.MODULE_IMPORT,
            new KindPayload.ModuleImportPayload("host/runtime", WALK_HOST_MODULE,
                deal.semantic.ir.ModuleImportKind.HOST, List.of()),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, origin);
    }

    /** The production Lua chunk of the host-binding walk drive project. */
    private static String emittedWalkHostChunk() {
        if (walkHostChunk == null) {
            List<deal.semantic.ir.SemanticOp> extraOps = List.of(walkHostImportOp());
            walkHostChunk = LuaSemanticEmitter.emitProductionProject(walkProject(extraOps),
                walkTables(extraOps), Map.of(), walkHostSurface());
        }
        return walkHostChunk;
    }

    /** The production JVM source of the walk drive project. */
    private static String emittedWalkJvm() {
        deal.codegen.jvm.JvmSemanticEmitter.EmissionResult emission =
            deal.codegen.jvm.JvmSemanticEmitter.emitProductionProject(walkProject(),
                walkTables(), Map.of(new deal.semantic.ir.ModuleId("arm-walk"),
                    new deal.semantic.ir.ClassFactoryRegistry(Map.of())),
                "ArmWalk", emptySurface());
        return emission.source();
    }


    // =========================================================================
    // 15. The moved cell: a fractional array(int) element at the boundary
    // =========================================================================

    /**
     * The moved cell (jsonable-tojson-walk-arm-binding W6): the numeric
     * classification's only ripple. A fractional {@code array(int)} element —
     * a position the emitted Lua walk never fails — is classified at the
     * boundary-check site on all three consumers: the emitted {@code __bcheck}
     * array element arm, the oracle's per-element int rule, and the JVM's
     * array element arm each publish the value-derived {@code number} token,
     * never the declared {@code int} text beside the position.
     */
    static void testMovedElementCell() throws Exception {
        System.out.println("-- the moved cell: a fractional array(int) element --");
        String origin = "moved-cell.deal:3:12";
        RuntimeDescriptor intArray =
            new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE);
        Tuple oracle = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, intArray,
            BoundaryValueView.ofArray(BoundaryValueView.ofNumber(3.5)),
            BoundaryContext.none()), "the array(int) element cell"), origin);
        checkEq(new Tuple("E8003", "array element 1 type mismatch", origin, "int",
                "number"), oracle,
            "the oracle classifies the fractional array(int) element by its value");
        // The JVM leg (the shared array element arm the artifact links).
        deal.codegen.jvm.JvmRuntime.Array jvmArray =
            new deal.codegen.jvm.JvmRuntime.Array(1);
        jvmArray.elements.add(3.5);
        deal.codegen.jvm.JvmRuntime.DealError jvm = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("array(int)", "array", jvmArray));
        check(jvm != null, "the JVM array element arm fails the fractional element");
        if (jvm != null) {
            checkEq(null, firstDifferingField(oracle,
                new Tuple(jvm.code, jvm.msg, origin, jvm.expected, jvm.actual)),
                "the JVM array element arm renders the oracle's identical tuple");
        }
        // The emitted prelude leg (the moved cell), under real luajit.
        List<String> rows = runPreludeProbe("""
            local ok, value = pcall(__bcheck, "array(int)", "array",
              {__a = true, __n = 1, [1] = 3.5})
            row("element", ok, value)
            """, "moved-cell-probe", 1);
        checkEq("element|E8003|int|number|array element 1 type mismatch", rows.get(0),
            "the emitted __bcheck array element arm classifies the element by its value");
        checkEq(null, firstDifferingField(oracle, luaTuple(rows.get(0), origin)),
            "the emitted prelude renders the oracle's identical element tuple");
        // The negative: a cell keying the token on the declared int text
        // publishes "int" and is reported by field name actual.
        checkEq("actual", firstDifferingField(oracle, new Tuple(oracle.code(),
                oracle.message(), oracle.span(), oracle.expected(), "int")),
            "a declared-text-keyed element arm is reported by field name actual");
    }

    // =========================================================================
    // The negative single-source control
    // =========================================================================

    static void testNegativeSingleSourceControl() {
        System.out.println("-- the negative single-source control --");
        // The reference: the authority's own kind arm (runtime-errors/
        // type-mismatch-e8001's corrected tuple at the declaration span).
        Tuple kind = tupleOf(FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int", "string",
            null), "type-mismatch-e8001.deal:6:21");
        checkEq(null, firstDifferingField(kind, kind),
            "the identical tuple compares equal (the control is not vacuous)");

        checkEq("message", firstDifferingField(kind, new Tuple(kind.code(),
            "expected int, got string", kind.span(), kind.expected(), kind.actual())),
            "the superseded suffixed spelling is caught by field name message");

        Tuple foreach = tupleOf(FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int", "nil",
            null), "for-of:8:3");
        checkEq("message", firstDifferingField(foreach, new Tuple(foreach.code(),
            "expected int, got missing", foreach.span(), foreach.expected(),
            "missing")),
            "the superseded absent-element text is caught by field name message");

        Tuple host = tupleOf(FailureContractRegistry.render(
            FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner",
                FailureProjections.kindReason(RuntimeDescriptor.Bytes.INSTANCE)),
            "bytes", "number", null), "host-bytes-param-mismatch-e8010.deal:10:10");
        checkEq("parameter 1 type mismatch: expected bytes", host.message(),
            "the authority renders the host composite with the inner reason");
        checkEq("message", firstDifferingField(host, new Tuple(host.code(),
            "parameter 1 type mismatch: expected bytes, got number", host.span(),
            host.expected(), host.actual())),
            "the superseded host composite is caught by field name message");
        // (4) A call-site span for a declaration-owned arm.
        checkEq("span", firstDifferingField(kind, new Tuple(kind.code(), kind.message(),
            "type-mismatch-e8001.deal:9:10", kind.expected(), kind.actual())),
            "a call-site span for a declaration-owned arm is caught by field name span");
    }

    // =========================================================================
    // 16. The landed runtime carriers on the emitted walk
    // =========================================================================

    /** The foreign class identity of the runtime-carrier and dispatch drives. */
    private static final deal.semantic.ir.ClassId FOREIGN_ID =
        new deal.semantic.ir.ClassId("host/runtime", "Foreign");

    /**
     * The landed runtime/host ABI carrier projections (the review
     * correction of the binding): the deployed runtime's async handle
     * ({@code deal/runtime.lua}'s {@code async_create}) projects the closed
     * {@code async-operation} token and its class representation
     * ({@code class_}) projects the carried canonical class atom at a
     * failing walk position — never the table spelling. The async handle
     * has no oracle or JVM carrier shape, so its regression is the emitted
     * walk's own; the class carrier's tuple is additionally asserted
     * identical on the oracle's class walk and the JVM walk machinery.
     */
    static void testRuntimeCarrierProjection() throws Exception {
        System.out.println("-- the landed runtime carriers on the emitted walk --");
        String foreign = FOREIGN_ID.text();
        String body = "local rt = require(\"deal.runtime\")\n"
            + "local carriers = {\n"
            + "  {label = \"runtime-async-handle-at-string-field\", root = {__c = true, "
            + "__id = " + quote(WALK_ID.text()) + ", __p = {name = true, age = true, "
            + "ratio = true, data = true, tags = true}, __f = {name = "
            + "rt.async_create(function() return \"x\" end), age = 1, ratio = 0.5, "
            + "data = " + LUA_TABLE_CARRIER + ", tags = {__a = true, __n = 1, "
            + "[1] = 1}}}},\n"
            + "  {label = \"runtime-class-root\", root = rt.class_(" + quote(foreign)
            + ", {}, {})},\n"
            + "}\n"
            + "for i = 1, #carriers do\n"
            + "  local c = carriers[i]\n"
            + "  local ok, text, arm, params, actual = __jsonToClassOp("
            + quote(WALK_ID.text()) + ", c.root)\n"
            + "  if ok then print(c.label .. \"|OK|\") return end\n"
            + "  local value = __arm(arm, params, " + quote(WALK_SPAN)
            + ", nil, actual)\n"
            + "  print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n"
            + "end\n";
        List<String> rows = runProbe(emittedWalkChunk(), "arm-walk", body,
            "walk-runtime-carriers", 2, true);

        Tuple asyncTuple = walkLuaTuple(rows.get(0));
        checkEq(new Tuple("E8001",
                "value at name is not JSON serializable: async-operation", WALK_SPAN,
                null, "async-operation"), asyncTuple,
            "the emitted walk projects the runtime async handle as async-operation at "
                + "the failing declared field");

        Tuple classTuple = walkLuaTuple(rows.get(1));
        checkEq(new Tuple("E8001",
                "value at  is not JSON serializable: " + foreign, WALK_SPAN, null,
                foreign), classTuple,
            "the emitted walk projects the runtime class representation as its carried "
                + "canonical class atom");

        // The oracle's typed-boundary projection publishes the same async
        // token for the same walk position (the JSON_TO_FIELD cell's check).
        Tuple oracleAsync = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.JSON_TO_ERROR, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.of(ActualKind.ASYNC_OPERATION),
            BoundaryContext.jsonField("name")), "the JSON_TO_FIELD async carrier"),
            WALK_SPAN);
        checkEq(null, firstDifferingField(asyncTuple, oracleAsync),
            "the oracle's typed-boundary projection publishes the emitted walk's "
                + "identical async tuple");

        // The class carrier on the two other consumers: the oracle's class
        // walk over a foreign carried atom and the JVM walk machinery over
        // the same identity.
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        Map<deal.semantic.ir.ValueId, ClassOpsExecutor.Value> values =
            new LinkedHashMap<>();
        values.put(((KindPayload.JsonToClassPayload) op.payload()).classValue(),
            new ClassOpsExecutor.Value.Class(FOREIGN_ID, List.of()));
        ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> outcome =
            ClassOpsExecutor.executeJsonToClass(op, values,
                Map.of(WALK_ID, walkLayout()),
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), op.origin());
        check(outcome instanceof ClassOpsExecutor.Outcome.Failure<
                ClassOpsExecutor.Value> failure,
            "the oracle's class walk fails the foreign class carrier");
        if (outcome instanceof ClassOpsExecutor.Outcome.Failure<
                ClassOpsExecutor.Value> failure) {
            Tuple oracleClass = tupleOf(failure.failure().failure(), WALK_SPAN);
            checkEq(null, firstDifferingField(classTuple, oracleClass),
                "the oracle's class walk publishes the emitted walk's identical class "
                    + "tuple");
            checkEq(op.origin(), failure.failure().origin(),
                "the oracle's class walk renders the operand it was given");
        }
        deal.codegen.jvm.JvmJson.Projection projection = null;
        try {
            deal.codegen.jvm.JvmJson.toClass(walkPlan(),
                new WalkInstance(foreign, Map.of()));
        } catch (deal.codegen.jvm.JvmJson.Projection caught) {
            projection = caught;
        }
        check(projection != null,
            "the JVM walk machinery fails the foreign class carrier");
        if (projection != null) {
            deal.codegen.jvm.JvmRuntime.DealError jvmError = projection.cycle
                ? deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK_CYCLE,
                    Map.of(), WALK_SPAN, null, null)
                : deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                    Map.of("fieldPath", projection.fieldPath, "actual",
                        projection.actual), WALK_SPAN, null, projection.actual);
            checkEq(null, firstDifferingField(classTuple, new Tuple(jvmError.code,
                    jvmError.msg, jvmError.origin, jvmError.expected, jvmError.actual)),
                "the JVM walk machinery publishes the emitted walk's identical class "
                    + "tuple");
        }
    }

    // =========================================================================
    // 16b. The deployed runtime's language-null sentinel at the walk
    // =========================================================================

    /**
     * The runtime-produced language-null sentinel at a failing walk position
     * (jsonable-tojson-walk-arm-binding W1's language-null classification):
     * {@code deal/runtime.lua}'s {@code __rt.__NULL} is a distinct value from
     * the chunk's own {@code __NULL}, and the landed host crossing preserves
     * it — {@code __hostReturnCell} returns a host-returned table's entries
     * unchanged and {@code __hostDealProject} keeps a runtime class
     * instance's non-class field values. The host-binding production chunk
     * binds the deployed runtime's sentinel beside the runtime it requires
     * (a project with a host import), and the typed-boundary projection
     * classifies that sentinel as the language null at the walk's failing
     * positions: the message and the actual field both carry {@code null}.
     * The flat-table control at the same position keeps the table spelling,
     * so the classification is by sentinel identity, not by table shape.
     */
    static void testRuntimeNullSentinelAtWalk() throws Exception {
        System.out.println("-- the deployed runtime's language-null sentinel at the walk --");
        String lua = emittedWalkHostChunk();
        check(lua.contains("local __rt = require(\"deal.runtime\")\n")
                && lua.contains("__rtNull = __rt.__NULL\n"),
            "the host-binding production chunk binds the deployed runtime's "
                + "language-null sentinel");
        check(lua.contains("__rt.load_host(\"host/runtime\""),
            "the host-binding walk chunk carries its host import");
        String body = "local rt = require(\"deal.runtime\")\n"
            + "local sentinel = rt.check_type(\"?string\", nil)\n"
            + "print(\"runtime-sentinel-binding|\" .. (__rtNull == sentinel and "
            + "\"OK\" or \"MISSING\") .. \"|\")\n"
            + "local returned = __hostReturnCell(\"table\", {k = sentinel}, "
            + quote(WALK_SPAN) + ", false)\n"
            + "local dataRoot = {__c = true, __id = " + quote(WALK_ID.text())
            + ", __p = {name = true, age = true, ratio = true, data = true, "
            + "tags = true}, __f = {name = " + luaString("n") + ", age = 1, "
            + "ratio = 0.5, data = {__t = true, __keys = {k = true}, k = "
            + "returned.k}, tags = {__a = true, __n = 1, [1] = 1}}}\n"
            + "local cases = {\n"
            + "  {label = \"host-returned-table-null-entry\", root = dataRoot},\n"
            + "  {label = \"plain-table-entry-control\", root = {__c = true, __id = "
            + quote(WALK_ID.text()) + ", __p = {name = true, age = true, ratio = "
            + "true, data = true, tags = true}, __f = {name = " + luaString("n")
            + ", age = 1, ratio = 0.5, data = {__t = true, __keys = {k = true}, "
            + "k = {}}, tags = {__a = true, __n = 1, [1] = 1}}}},\n"
            + "  {label = \"runtime-class-null-field\", root = __hostDealProject("
            + quote(WALK_ID.text()) + ", {name = returned.k, age = 1, ratio = "
            + "0.5, data = {__t = true, __keys = {}}, tags = {__a = true, "
            + "__n = 0}, __kind = \"class\", __classname = "
            + quote(WALK_ID.text()) + "}, " + quote(WALK_SPAN) + ")},\n"
            + "}\n"
            + "for i = 1, #cases do\n"
            + "  local c = cases[i]\n"
            + "  local ok, text, arm, params, actual = __jsonToClassOp("
            + quote(WALK_ID.text()) + ", c.root)\n"
            + "  if ok then print(c.label .. \"|OK|\") return end\n"
            + "  local value = __arm(arm, params, " + quote(WALK_SPAN)
            + ", nil, actual)\n"
            + "  print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n"
            + "end\n";
        List<String> rows = runProbe(lua, "arm-walk", body, "walk-runtime-null", 4,
            true);
        checkEq("runtime-sentinel-binding|OK|", rows.get(0),
            "the emitted chunk's own binding is the deployed runtime's sentinel");
        checkEq(new Tuple("E8001",
                "value at data.k is not JSON serializable: null", WALK_SPAN, null,
                "null"), walkLuaTuple(rows.get(1)),
            "the runtime null in a host-returned table entry renders null in the "
                + "message and the actual field");
        checkEq(new Tuple("E8001",
                "value at data.k is not JSON serializable: table", WALK_SPAN, null,
                "table"), walkLuaTuple(rows.get(2)),
            "the flat-table control at the same position keeps the table spelling");
        checkEq(new Tuple("E8001",
                "value at name is not JSON serializable: null", WALK_SPAN, null,
                "null"), walkLuaTuple(rows.get(3)),
            "the runtime null at a runtime class instance's declared field renders "
                + "null in the message and the actual field");
    }

    // =========================================================================
    // 16c. The deployed runtime's absent-marker sentinel at the walk
    // =========================================================================

    /**
     * The runtime-produced absent-marker sentinel at a failing walk position
     * (jsonable-tojson-walk-arm-binding W1's absent-marker classification):
     * {@code deal/runtime.lua}'s {@code __rt.__MISSING} is a distinct value
     * from the chunk's own {@code __MISSING}, and the landed host crossing
     * preserves it — {@code __hostReturnCell} returns a host-returned
     * table's entries unchanged and {@code __hostDealProject} keeps a
     * runtime class instance's non-class field values. The host-binding
     * production chunk binds the deployed runtime's sentinel beside the
     * runtime it requires, and the typed-boundary projection classifies
     * that sentinel as the absent marker at the walk's failing positions:
     * the message and the actual field both carry {@code nil}. The
     * flat-table control at the same position keeps the table spelling, so
     * the classification is by sentinel identity, not by table shape.
     */
    static void testRuntimeMissingSentinelAtWalk() throws Exception {
        System.out.println("-- the deployed runtime's absent-marker sentinel at the walk --");
        String lua = emittedWalkHostChunk();
        check(lua.contains("local __rt = require(\"deal.runtime\")\n")
                && lua.contains("__rtMissing = __rt.__MISSING\n"),
            "the host-binding production chunk binds the deployed runtime's "
                + "absent-marker sentinel");
        String body = "local rt = require(\"deal.runtime\")\n"
            + "local sentinel = rt.__MISSING\n"
            + "print(\"runtime-missing-binding|\" .. (__rtMissing == sentinel and "
            + "\"OK\" or \"MISSING\") .. \"|\")\n"
            + "local returned = __hostReturnCell(\"table\", {k = sentinel}, "
            + quote(WALK_SPAN) + ", false)\n"
            + "local dataRoot = {__c = true, __id = " + quote(WALK_ID.text())
            + ", __p = {name = true, age = true, ratio = true, data = true, "
            + "tags = true}, __f = {name = " + luaString("n") + ", age = 1, "
            + "ratio = 0.5, data = {__t = true, __keys = {k = true}, k = "
            + "returned.k}, tags = {__a = true, __n = 1, [1] = 1}}}\n"
            + "local cases = {\n"
            + "  {label = \"host-returned-table-missing-entry\", root = dataRoot},\n"
            + "  {label = \"plain-table-entry-control\", root = {__c = true, __id = "
            + quote(WALK_ID.text()) + ", __p = {name = true, age = true, ratio = "
            + "true, data = true, tags = true}, __f = {name = " + luaString("n")
            + ", age = 1, ratio = 0.5, data = {__t = true, __keys = {k = true}, "
            + "k = {}}, tags = {__a = true, __n = 1, [1] = 1}}}},\n"
            + "  {label = \"runtime-class-missing-field\", root = __hostDealProject("
            + quote(WALK_ID.text()) + ", {name = returned.k, age = 1, ratio = "
            + "0.5, data = {__t = true, __keys = {}}, tags = {__a = true, "
            + "__n = 0}, __kind = \"class\", __classname = "
            + quote(WALK_ID.text()) + "}, " + quote(WALK_SPAN) + ")},\n"
            + "}\n"
            + "for i = 1, #cases do\n"
            + "  local c = cases[i]\n"
            + "  local ok, text, arm, params, actual = __jsonToClassOp("
            + quote(WALK_ID.text()) + ", c.root)\n"
            + "  if ok then print(c.label .. \"|OK|\") return end\n"
            + "  local value = __arm(arm, params, " + quote(WALK_SPAN)
            + ", nil, actual)\n"
            + "  print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n"
            + "end\n";
        List<String> rows = runProbe(lua, "arm-walk", body, "walk-runtime-missing", 4,
            true);
        checkEq("runtime-missing-binding|OK|", rows.get(0),
            "the emitted chunk's own binding is the deployed runtime's absent-marker "
                + "sentinel");
        checkEq(new Tuple("E8001",
                "value at data.k is not JSON serializable: nil", WALK_SPAN, null,
                "nil"), walkLuaTuple(rows.get(1)),
            "the runtime absent marker in a host-returned table entry renders nil in "
                + "the message and the actual field");
        checkEq(new Tuple("E8001",
                "value at data.k is not JSON serializable: table", WALK_SPAN, null,
                "table"), walkLuaTuple(rows.get(2)),
            "the flat-table control at the same position keeps the table spelling");
        checkEq(new Tuple("E8001",
                "value at name is not JSON serializable: nil", WALK_SPAN, null,
                "nil"), walkLuaTuple(rows.get(3)),
            "the runtime absent marker at a runtime class instance's declared field "
                + "renders nil in the message and the actual field");
    }

    // =========================================================================
    // 17. The walk arm's oracle-dispatch leg (the executable IR drive)
    // =========================================================================

    /** One dispatch case: the executable unit and the two artifact legs. */
    private record DispatchCase(String label, String fieldPath, String actual,
                                DispatchUnit unit, String luaRoot,
                                deal.codegen.jvm.JvmRuntime.ClassInstance jvmRoot) {

        String message() {
            return "value at " + fieldPath + " is not JSON serializable: " + actual;
        }
    }

    /** One built dispatch unit: the project, its table, and the walk op's origin. */
    private record DispatchUnit(deal.semantic.ir.ExecutableLoweredProject project,
                                Map<deal.semantic.ir.ModuleId,
                                    deal.semantic.ir.StructuredBodyTable> tables,
                                deal.semantic.ir.SourceOrigin walkOrigin) {
    }

    /** The producer kinds of one dispatch construction. */
    private enum DispatchValue { STRING, INT, NUMBER, TABLE, ARRAY }

    /** The produced ops and the class-value result of one construction. */
    private record DispatchConstruction(List<deal.semantic.ir.SemanticOp> ops,
                                        deal.semantic.ir.ValueId instance) {
    }

    /** The monotonically increasing IR ids of one dispatch unit. */
    private static final class DispatchIds {

        private final deal.semantic.ir.ModuleId module =
            new deal.semantic.ir.ModuleId("arm-walk");
        private int opOrdinal = 0;
        private int valueOrdinal = 100;
        private int anchorOrdinal = 0;

        deal.semantic.ir.ModuleId module() {
            return module;
        }

        deal.semantic.ir.OpId op() {
            return new deal.semantic.ir.OpId(module, opOrdinal++);
        }

        deal.semantic.ir.ValueId value() {
            return new deal.semantic.ir.ValueId(valueOrdinal++);
        }

        deal.semantic.ir.AnchorId anchor() {
            return new deal.semantic.ir.AnchorId(anchorOrdinal++);
        }
    }

    /**
     * The oracle-dispatch leg of the walk arm's comparison (Verification
     * 3/4): an executable lowered project whose module-init block produces
     * the class value with producer ops (CONST/TABLE_NEW/ARRAY_NEW, the
     * pinned {@code CLASS_LITERAL_FIELD} boundary children, and the
     * {@code CLASS_NEW} construction) and feeds it to the JSON_TO_CLASS op.
     * The oracle's dispatch supplies the executing op's own
     * {@code SourceOrigin} to the walk; the snapshot's
     * {@code (code, message, origin, expected, actual)} tuple equals the
     * emitted Lua and JVM consumers' renders for the same failing value.
     * The separate direct-executor drives keep the positions a construction
     * cannot produce (a fractional value at an int-typed field, a nonfinite
     * array element) with a supplied origin distinct from the op's
     * synthetic anchor; this leg makes the dispatch's operand selection and
     * walk-failure handling load-bearing.
     */
    static void testWalkArmOracleDispatchLeg() throws Exception {
        System.out.println("-- the walk arm's oracle-dispatch leg --");
        List<DispatchCase> cases = new ArrayList<>();

        // (1) A nonfinite value at the number-typed declared field: the
        // construction's number boundary admits it, the walk fails it.
        {
            DispatchIds ids = new DispatchIds();
            Map<String, DispatchValue> present = new LinkedHashMap<>();
            present.put("name", DispatchValue.STRING);
            present.put("age", DispatchValue.INT);
            present.put("ratio", DispatchValue.NUMBER);
            present.put("data", DispatchValue.TABLE);
            present.put("tags", DispatchValue.ARRAY);
            cases.add(dispatchCase("dispatch-nonfinite-number-field", "ratio",
                "number", ids, WALK_ID, walkLayout(), present,
                Map.of(WALK_ID, walkLayout()),
                "{__c = true, __id = " + quote(WALK_ID.text()) + ", __p = {name = true, "
                    + "age = true, ratio = true, data = true, tags = true}, __f = "
                    + "{name = " + luaString("n") + ", age = 1, ratio = 0/0, data = "
                    + LUA_TABLE_CARRIER + ", tags = {__a = true, __n = 1, [1] = 1}}}",
                walkJvmInstance(Map.of("name", "n", "age", 1L, "ratio", Double.NaN,
                    "data", new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                    jvmIntArray(1L)))));
        }

        // (2) An omitted required field with no declared default: the
        // construction publishes the instance with the missing field state,
        // the walk fails the first missing required field declaration-order.
        {
            DispatchIds ids = new DispatchIds();
            Map<String, DispatchValue> present = new LinkedHashMap<>();
            present.put("name", DispatchValue.STRING);
            present.put("ratio", DispatchValue.NUMBER);
            present.put("data", DispatchValue.TABLE);
            present.put("tags", DispatchValue.ARRAY);
            cases.add(dispatchCase("dispatch-missing-required-field", "age", "nil",
                ids, WALK_ID, walkLayout(), present, Map.of(WALK_ID, walkLayout()),
                "{__c = true, __id = " + quote(WALK_ID.text()) + ", __p = {name = true, "
                    + "ratio = true, data = true, tags = true}, __f = {name = "
                    + luaString("n") + ", ratio = 0.5, data = " + LUA_TABLE_CARRIER
                    + ", tags = {__a = true, __n = 1, [1] = 1}}}",
                walkJvmInstance(Map.of("name", "n", "ratio", 0.5, "data",
                    new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L)))));
        }

        // (3) A produced instance of another class: the walk's own root
        // identity check selects the walk arm with the carried atom.
        {
            DispatchIds ids = new DispatchIds();
            Map<String, DispatchValue> present = new LinkedHashMap<>();
            present.put("x", DispatchValue.INT);
            cases.add(dispatchCase("dispatch-foreign-class-root", "",
                FOREIGN_ID.text(), ids, FOREIGN_ID, foreignLayout(), present,
                Map.of(WALK_ID, walkLayout(), FOREIGN_ID, foreignLayout()),
                "{__c = true, __id = " + quote(FOREIGN_ID.text())
                    + ", __p = {x = true}, __f = {x = 1}}",
                new WalkInstance(FOREIGN_ID.text(), Map.of("x", 1L))));
        }

        // The emitted Lua leg for the same failing values (the production
        // chunk's own walk machinery under real luajit).
        List<WalkCase> luaCases = new ArrayList<>();
        for (DispatchCase drive : cases) {
            luaCases.add(new WalkCase(drive.label(), "JSON_TO_WALK",
                drive.fieldPath(), drive.actual(), null, drive.luaRoot(), null));
        }
        List<String> luaRows = runWalkProbe(luaCases);

        for (int i = 0; i < cases.size(); i++) {
            DispatchCase drive = cases.get(i);
            deal.semantic.SemanticRuntimeModel.ConsumerRun run =
                deal.semantic.SemanticOracle.executeProjectInits(
                    drive.unit().project(), drive.unit().tables(), Map.of(), null);
            check(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure,
                drive.label() + ": the oracle dispatch terminates with the walk's DEAL "
                    + "failure; got " + run.terminal());
            if (!(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure failure)) {
                continue;
            }
            deal.semantic.SemanticRuntimeModel.ErrorSnapshot error = failure.error();
            Tuple dispatch = new Tuple(error.code(), error.message(), error.origin(),
                error.expected(), error.actual());
            Tuple reference = new Tuple("E8001", drive.message(), WALK_SPAN, null,
                drive.actual());
            checkEq(null, firstDifferingField(reference, dispatch),
                drive.label() + ": the oracle dispatch renders the bound walk arm's "
                    + "tuple at the executing op's own SourceOrigin");
            String walkOriginText = drive.unit().walkOrigin().sourceId() + ":"
                + drive.unit().walkOrigin().span().startLine() + ":"
                + drive.unit().walkOrigin().span().startColumn();
            checkEq(WALK_SPAN, walkOriginText,
                drive.label() + ": the executing JSON_TO_CLASS op carries the pinned "
                    + "span");
            checkEq(walkOriginText, dispatch.span(),
                drive.label() + ": the dispatch supplies the executing op's own "
                    + "SourceOrigin to the walk");

            // The emitted Lua leg: the rendered origin field is the row's own
            // (never a test constant), so a substituted dispatch span fails
            // the comparison by field.
            Tuple luaTuple = walkLuaTuple(luaRows.get(i));
            checkEq(null, firstDifferingField(dispatch, luaTuple),
                drive.label() + ": the emitted Lua walk machinery renders the "
                    + "dispatch's identical tuple");

            // The emitted JVM leg (the shared walk machinery the artifact
            // links), rendered exactly like the emitted catch site.
            deal.codegen.jvm.JvmJson.Projection projection = null;
            try {
                deal.codegen.jvm.JvmJson.toClass(walkPlan(), drive.jvmRoot());
            } catch (deal.codegen.jvm.JvmJson.Projection caught) {
                projection = caught;
            }
            check(projection != null,
                drive.label() + ": the JVM walk machinery fails the value");
            if (projection == null) {
                continue;
            }
            deal.codegen.jvm.JvmRuntime.DealError jvmError = projection.cycle
                ? deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK_CYCLE,
                    Map.of(), WALK_SPAN, null, null)
                : deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                    Map.of("fieldPath", projection.fieldPath, "actual",
                        projection.actual), WALK_SPAN, null, projection.actual);
            checkEq(null, firstDifferingField(dispatch, new Tuple(jvmError.code,
                    jvmError.msg, jvmError.origin, jvmError.expected, jvmError.actual)),
                drive.label() + ": the JVM walk machinery renders the dispatch's "
                    + "identical tuple");
        }
    }

    /** One dispatch construction with its producer ops and the walk op. */
    private static DispatchCase dispatchCase(String label, String fieldPath,
            String actual, DispatchIds ids, deal.semantic.ir.ClassId classId,
            deal.semantic.ir.ClassLayout layout,
            Map<String, DispatchValue> present,
            Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts,
            String luaRoot, deal.codegen.jvm.JvmRuntime.ClassInstance jvmRoot) {
        DispatchConstruction construction = buildDispatchConstruction(ids, classId,
            layout, present);
        return new DispatchCase(label, fieldPath, actual,
            dispatchUnit(ids, construction, layouts), luaRoot, jvmRoot);
    }


    /** The dispatch drive's foreign class layout (one provided int field). */
    private static deal.semantic.ir.ClassLayout foreignLayout() {
        return new deal.semantic.ir.ClassLayout(FOREIGN_ID, List.of(
            new deal.semantic.ir.ClassLayout.FieldLayout("x",
                RuntimeDescriptor.Int.INSTANCE, true,
                deal.semantic.ir.DefaultOwner.LOCAL)));
    }

    /**
     * One executable construction: the producer ops of the provided fields
     * (in declaration order), the pinned {@code CLASS_LITERAL_FIELD}
     * boundary children (in declaration order), and the {@code CLASS_NEW}
     * op (LOCAL owner, no defaults, no factory).
     */
    private static DispatchConstruction buildDispatchConstruction(DispatchIds ids,
            deal.semantic.ir.ClassId classId, deal.semantic.ir.ClassLayout layout,
            Map<String, DispatchValue> present) {
        deal.semantic.ir.OpId classNewOp = ids.op();
        List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
        List<KindPayload.ProvidedField> provided = new ArrayList<>();
        Map<String, deal.semantic.ir.ValueId> values = new LinkedHashMap<>();
        for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
            DispatchValue kind = present.get(field.name());
            if (kind == null) {
                continue;
            }
            deal.semantic.ir.ValueId value = ids.value();
            ops.add(dispatchProducer(ids, value, kind));
            provided.add(new KindPayload.ProvidedField(field.name(), value));
            values.put(field.name(), value);
        }
        List<KindPayload.FieldBoundary> boundaries = new ArrayList<>();
        for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
            deal.semantic.ir.ValueId value = values.get(field.name());
            if (value == null) {
                continue;
            }
            deal.semantic.ir.OpId boundaryOp = ids.op();
            ops.add(dispatchBoundary(ids, boundaryOp, field.descriptor(), value,
                classNewOp));
            boundaries.add(new KindPayload.FieldBoundary(field.name(),
                BoundaryKind.CLASS_LITERAL_FIELD, boundaryOp));
        }
        deal.semantic.ir.ValueId instance = ids.value();
        ops.add(dispatchOp(classNewOp, deal.semantic.ir.SemanticOpKind.CLASS_NEW,
            new KindPayload.ClassNewPayload(classId, layout, provided,
                deal.semantic.ir.DefaultOwner.LOCAL, List.of(), null, boundaries),
            instance, new RuntimeDescriptor.Class(classId),
            FailurePolicyId.CLASS_CONSTRUCTION, dispatchOrigin(ids, null)));
        return new DispatchConstruction(ops, instance);
    }

    /** One provided-field producer of the dispatch construction. */
    private static deal.semantic.ir.SemanticOp dispatchProducer(DispatchIds ids,
            deal.semantic.ir.ValueId result, DispatchValue kind) {
        KindPayload payload;
        deal.semantic.ir.SemanticOpKind opKind;
        deal.semantic.ir.OpResultType resultType;
        switch (kind) {
            case STRING -> {
                payload = new KindPayload.ConstPayload(new ScalarValue.String("n"));
                opKind = deal.semantic.ir.SemanticOpKind.CONST;
                resultType = RuntimeDescriptor.String.INSTANCE;
            }
            case INT -> {
                payload = new KindPayload.ConstPayload(new ScalarValue.Int(1));
                opKind = deal.semantic.ir.SemanticOpKind.CONST;
                resultType = RuntimeDescriptor.Int.INSTANCE;
            }
            case NUMBER -> {
                payload = new KindPayload.ConstPayload(
                    new ScalarValue.Number(Double.NaN));
                opKind = deal.semantic.ir.SemanticOpKind.CONST;
                resultType = RuntimeDescriptor.Number.INSTANCE;
            }
            case TABLE -> {
                payload = new KindPayload.TableNewPayload(List.of());
                opKind = deal.semantic.ir.SemanticOpKind.TABLE_NEW;
                resultType = RuntimeDescriptor.Table.INSTANCE;
            }
            case ARRAY -> {
                payload = new KindPayload.ArrayNewPayload(RuntimeDescriptor.Int.INSTANCE,
                    List.of(), List.of());
                opKind = deal.semantic.ir.SemanticOpKind.ARRAY_NEW;
                resultType = new RuntimeDescriptor.Array(RuntimeDescriptor.Int.INSTANCE);
            }
            default -> throw new IllegalStateException("unknown dispatch producer "
                + kind);
        }
        return dispatchOp(ids.op(), opKind, payload, result, resultType,
            FailurePolicyId.NO_DEAL_FAILURE, dispatchOrigin(ids, null));
    }

    /** One pinned {@code CLASS_LITERAL_FIELD} boundary child of the construction. */
    private static deal.semantic.ir.SemanticOp dispatchBoundary(DispatchIds ids,
            deal.semantic.ir.OpId opId, RuntimeDescriptor descriptor,
            deal.semantic.ir.ValueId input, deal.semantic.ir.OpId parent) {
        return dispatchOp(opId, deal.semantic.ir.SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(BoundaryKind.CLASS_LITERAL_FIELD,
                descriptor, input, new deal.semantic.ir.BoundaryRealization
                    .RuntimeValidation(
                        deal.semantic.SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID)),
            null, null,
            descriptor instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE
                : FailurePolicyId.TYPE_DESCRIPTOR,
            dispatchOrigin(ids, parent));
    }

    /** One contract-complete op of the dispatch unit. */
    private static deal.semantic.ir.SemanticOp dispatchOp(deal.semantic.ir.OpId id,
            deal.semantic.ir.SemanticOpKind kind, KindPayload payload,
            deal.semantic.ir.SemanticValue result,
            deal.semantic.ir.OpResultType resultType, FailurePolicyId policy,
            deal.semantic.ir.SourceOrigin origin) {
        deal.semantic.ir.ClosedSelector selector =
            payload instanceof KindPayload.SelectorCarrying carrying
                ? carrying.selector() : null;
        deal.semantic.ir.OperationContractSnapshot contract =
            new deal.semantic.ir.OperationContractSnapshot(
                deal.semantic.ir.OperationContractSnapshot.VERSION, kind, resultType,
                List.of(), selector, payload, policy, List.of(), "placeholder");
        contract = new deal.semantic.ir.OperationContractSnapshot(
            deal.semantic.ir.OperationContractSnapshot.VERSION, kind, resultType,
            List.of(), selector, payload, policy, List.of(),
            deal.semantic.ir.ContractSnapshotCanonicalizer.digest(contract));
        return new deal.semantic.ir.SemanticOp(id, kind, origin, result, resultType,
            List.of(), List.of(), payload, policy, contract);
    }

    /** One synthetic origin of the dispatch unit (the walk op's own span). */
    private static deal.semantic.ir.SourceOrigin dispatchOrigin(DispatchIds ids,
            deal.semantic.ir.OpId parent) {
        return new deal.semantic.ir.SourceOrigin("arm-walk.deal",
            new deal.semantic.ir.SourceSpan("arm-walk.deal", 2, 8, 2, 18),
            deal.semantic.ir.SourceOriginKind.SYNTHETIC, ids.anchor(), parent);
    }

    /**
     * The dispatch project of one construction: the produced ops, the
     * JSON_TO_CLASS op over the construction's result, and the module-init
     * table that runs them in order.
     */
    private static DispatchUnit dispatchUnit(DispatchIds ids,
            DispatchConstruction construction,
            Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts) {
        deal.semantic.ir.ModuleId entry = ids.module();
        deal.semantic.ir.SemanticOp json = walkJsonOp(ids.op(),
            construction.instance(), ids.value());
        List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>(construction.ops());
        ops.add(json);
        deal.semantic.ir.BlockId initBlock = new deal.semantic.ir.BlockId(0);
        List<deal.semantic.ir.OpId> members = new ArrayList<>();
        Map<deal.semantic.ir.OpId, deal.semantic.ir.BlockId> membership =
            new LinkedHashMap<>();
        for (deal.semantic.ir.SemanticOp op : ops) {
            members.add(op.opId());
            membership.put(op.opId(), initBlock);
        }
        deal.semantic.ir.StructuredBodyTable table =
            new deal.semantic.ir.StructuredBodyTable(Map.of(initBlock, members),
                membership);
        deal.semantic.ir.LoweredModuleUnit unit =
            new deal.semantic.ir.LoweredModuleUnit(
                deal.semantic.ir.LoweredModuleUnit.FORMAT_VERSION,
                deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, entry, "hash",
                "context", java.util.Set.of(), Map.of(), layouts, Map.of(),
                new deal.semantic.ir.ModuleInitPlan(List.of(), initBlock),
                deal.semantic.ir.ExportPlan.empty(), Map.of(), ops);
        deal.semantic.ir.ProjectInterfaceIndex index =
            new deal.semantic.ir.ProjectInterfaceIndex(
                deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION,
                Map.of(entry, new deal.semantic.ir.ExternalModuleInterface(entry,
                    deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(),
                    List.of(), List.of(),
                    deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        deal.semantic.ir.ExecutableLoweredProject project =
            new deal.semantic.ir.ExecutableLoweredProject(
                deal.semantic.ir.SemanticProfile.DEAL_V1_2_INT32, index,
                Map.of(entry, unit), entry);
        return new DispatchUnit(project, Map.of(entry, table), json.origin());
    }
}
