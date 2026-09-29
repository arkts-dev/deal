package deal.test;

import deal.codegen.lua.LuaSemanticEmitter;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.FailureProjections;

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

    public static void main(String[] args) {
        testClosedArmTable();
        testConsistencyInvariantNegatives();
        testCorrectedTemplates();
        testClosedProjections();
        testRendersAndMarkedArms();
        testEmittedTable();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
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
        String lua = LuaSemanticEmitter.emitProductionProject(project, tables, Map.of(),
            emptySurface());
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
}
