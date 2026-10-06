package deal.test;

import deal.codegen.lua.LuaSemanticEmitter;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.RuntimeDescriptor;

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
        testHostInnerReasonDescriptorValidation();
        testEmittedParameterContract();
        testNegativeSingleSourceControl();
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
        check(FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK).policy()
                    == FailurePolicyId.JSON_TO_ERROR
                && FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK).template()
                    .equals("value at {fieldPath} is not JSON serializable: {actual}")
                && FailureContractRegistry.arm(FailureArmId.JSON_TO_WALK).isSiblingOwned(),
            "the JSON_TO_ERROR walk template is bound to its declared sibling-owned arm");
        check(FailureContractRegistry.row(FailurePolicyId.JSON_TO_ERROR).templates()
                .equals(List.of("value at {fieldPath} is not JSON serializable: {actual}",
                    "unsupported type for JSON encoding: {actual}"))
                && FailureContractRegistry.row(FailurePolicyId.INT32_RESULT).templates()
                    .equals(List.of("int out of safe range")),
            "the JSON_TO_ERROR and INT32_RESULT row data stay as landed");
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
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "a", "actual", "table"), null, "table", null),
            "a production render of the SIBLING_OWNED arm fails closed");
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

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static HostDeclarationSurface emptySurface() {
        return new HostDeclarationSurface(Map.of());
    }

    /** The production chunk of the arm-only project (the emitted prelude). */
    private static String emittedArmsChunk() {
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
        Path workspace = Files.createTempDirectory("failure-arm-" + probeName);
        try {
            Path artifact = workspace.resolve("arms.lua");
            Files.writeString(artifact, emittedArmsChunk(), StandardCharsets.UTF_8);
            Path probe = workspace.resolve(probeName + ".lua");
            Files.writeString(probe, """
                local text = io.open("%s"):read("*a")
                text = text:gsub("%%s*$", "")
                local tail = 'return __exportSurfaces["arms"]'
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
                """.formatted(artifact.toAbsolutePath().toString(), body, probeName),
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit", probeName + ".lua");
            builder.directory(workspace.toFile());
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
}
