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
            kind.origin(), kind.scope()));
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
                + arm.actualProjection() + "|" + arm.scope() + "|" + arm.origin();
            check(canonical.contains(line),
                "the canonical serialization carries arm " + arm.id()
                    + " with its scope and projection binding");
            String expectedEntry = "[\"" + arm.id() + "\"] = {c="
                + quote(FailureContractRegistry.codeOf(arm)) + ", t="
                + quote(arm.template()) + ", e=" + quote(arm.expectedSource().name())
                + ", a=" + quote(arm.actualProjection().name()) + ", s="
                + quote(arm.scope().name()) + ", o=" + quote(arm.origin().name()) + "}";
            check(lua.contains(expectedEntry),
                "the emitted prelude serializes arm " + arm.id()
                    + " (scope and projection binding included); expected "
                    + expectedEntry);
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
        Path workspace = Files.createTempDirectory("failure-arm-completion");
        try {
            Path artifact = workspace.resolve("arms.lua");
            Files.writeString(artifact, emittedArmsChunk(), StandardCharsets.UTF_8);
            Path probe = workspace.resolve("completion-probe.lua");
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
                local ok, value = pcall(__bcheck, "@src/app/User", "table", "abc", nil, true)
                row("class", ok, value)
                ok, value = pcall(__bcheck, "array(int)", "table", "abc", nil, true)
                row("array", ok, value)
                ok, value = pcall(__bcheck, "string", "string", 5, nil, true)
                row("string", ok, value)
                print("carrier-string|" .. __carrierKind("abc"))
                ]==]
                local chunk = assert(load(text:sub(1, #text - #tail) .. row, "completion-probe"))
                chunk()
                """.formatted(artifact.toAbsolutePath().toString()),
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit", "completion-probe.lua");
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
            check(rows.size() >= 4, "the prelude probe prints its rows: " + output);
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
    // 9. The negative single-source control
    // =========================================================================

    /**
     * The negative single-source proof (Verification 3): the comparison
     * helper reports the first differing field by name, so a consumer that
     * composes its own text or picks its own span is caught by the field it
     * composed. The reference tuples are the authority's own renders; each
     * negative drives a deliberately composing snapshot: the superseded
     * suffixed spelling, the superseded {@code FOR_EACH} absent-element text,
     * the superseded host composite, and a call-site span for a
     * declaration-owned arm.
     */
    static void testNegativeSingleSourceControl() {
        System.out.println("-- the negative single-source control --");
        // The reference: the authority's own kind arm (runtime-errors/
        // type-mismatch-e8001's corrected tuple at the declaration span).
        Tuple kind = tupleOf(FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int", "string",
            null), "type-mismatch-e8001.deal:6:21");
        checkEq(null, firstDifferingField(kind, kind),
            "the identical tuple compares equal (the control is not vacuous)");
        // (1) A consumer composing the superseded suffix.
        checkEq("message", firstDifferingField(kind, new Tuple(kind.code(),
            "expected int, got string", kind.span(), kind.expected(), kind.actual())),
            "the superseded suffixed spelling is caught by field name message");
        // (2) The superseded FOR_EACH absent-element text (the cell now
        // renders the kind arm with the typed-boundary nil actual).
        Tuple foreach = tupleOf(FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "int"), "int", "nil",
            null), "for-of:8:3");
        checkEq("message", firstDifferingField(foreach, new Tuple(foreach.code(),
            "expected int, got missing", foreach.span(), foreach.expected(),
            "missing")),
            "the superseded absent-element text is caught by field name message");
        // (3) The superseded host composite (expected/actual inlined instead
        // of the host inner reason).
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
