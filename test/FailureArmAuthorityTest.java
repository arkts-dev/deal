package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmNames;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ClassOpsExecutor;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FailurePolicyRow;
import deal.semantic.ir.FailureProjections;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.UnicodeScalars;
import deal.test.conformance.JvmCompilation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FailureArmAuthorityTest {

    private static int passed = 0;
    private static int failed = 0;
    private static String armsChunk;
    private static String walkChunk;
    private static String walkHostChunk;
    private static List<String> arrayBoundaryRows;

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
        arrayBoundaryRows = null;
        testClosedProjections();
        testRendersAndMarkedArms();
        testRawHostShapeArm();
        testCarrierKindStringActual();
        testCompletionFamilyDrive();
        testCompletionSignatureDrive();
        testCompletionNullCarrierDrive();
        testTypedBoundaryClassAtomCarrier();
        testForgedCarrierTableKeys();
        testTypedBoundaryInvalidUnicode();
        testStdJsonArmDrive();
        testStdlibResultsAndFailureArms();
        testIntLadderWrongKindCell();
        testArrayBoundsArms();
        testDeclaredFieldShapeNegatives();
        testTypedBoundaryClassSpellingNegatives();
        testFieldParameterBindingNegatives();
        testForbiddenRenderScopesAndInnerReasons();
        testDescriptorAndClassAtomVocabularyDepth();
        testNullableInnerInvariant();
        testKindOnlySpellingVocabulary();
        testHostInnerReasonTemplateAuthority();
        testHostSignaturePlaceholderParity();
        testHostInnerReasonDescriptorValidation();
        testEmittedParameterContract();
        testWalkArmThreeConsumerDrive();
        testDeclaredArraySharedReferenceControl();
        testAbsentOriginFailsClosed();
        testWalkArmOracleDispatchLeg();
        testWalkArmOracleDispatchCycles();
        testPresentNullAdmission();
        testWalkOracleNumericPositions();
        testWalkOracleKindPositions();
        testMovedElementCell();
        testDecodedArrayPresentNullCell();
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
    // 4. The closed projections
    // =========================================================================

    static void testClosedProjections() {
        System.out.println("-- the two closed projections --");
        check(FailureProjections.typedBoundaryToken(
                deal.semantic.ir.ActualKind.MISSING, null).equals("undefined")
                && FailureProjections.typedBoundaryToken(
                    deal.semantic.ir.ActualKind.CLASS, "@src/app/User")
                    .equals("@src/app/User")
                && FailureProjections.typedBoundaryToken(
                    deal.semantic.ir.ActualKind.NULL, null).equals("null"),
            "the typed-boundary projection renders undefined for the absent marker and the "
                + "carried canonical class atom for a class instance");
        check(FailureProjections.completionToken(deal.semantic.ir.ActualKind.NUMBER, null)
                .equals("number")
                && FailureProjections.completionToken(
                    deal.semantic.ir.ActualKind.STRING, null).equals("string"),
            "the completion variant maps every numeric carrier to number");
        check(FailureProjections.carrierKindToken(
                FailureProjections.CarrierKind.DEAL_FUNCTION).equals("function"),
            "the carrier-kind projection carries the std/json function token");
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
                deal.semantic.ir.RuntimeDescriptor.Number.INSTANCE).equals("expected number")
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
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "number"), "number", "string",
            null);
        check(kind.message().equals("expected number")
                && kind.expected().equals("number") && kind.actual().equals("string")
                && kind.code() == deal.diagnostics.DiagnosticCode.E8001,
            "the kind arm renders its own suffix-less template: " + kind.message());
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_STRING_SURROGATE,
                Map.of(), null, null, null),
            "an INNER_ONLY arm rendered at a failure site fails closed");
        expectDefect(() -> FailureContractRegistry.renderInner(
                FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "number")),
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
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.SUBSTRING_BOUNDS,
                Map.of(), "number", null, null),
            "a supplied expected field on a field-less arm fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
                Map.of("kind", "number", "extra", "x"), "number", "string", null),
            "an extra named parameter fails closed");
        expectDefect(() -> FailureContractRegistry.armForTemplate(
                FailurePolicyId.NO_DEAL_FAILURE, 0),
            "a template with no bound arm fails closed");
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
            deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, entry, "hash", "context",
            java.util.Set.of(), Map.of(), Map.of(), Map.of(),
            new deal.semantic.ir.ModuleInitPlan(List.of(),
                new deal.semantic.ir.BlockId(0)),
            new deal.semantic.ir.ExportPlan(List.of()), Map.of(), List.of(),
            new deal.semantic.ir.StructuredBodyTable(Map.of(), Map.of()),
            new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
            deal.semantic.ir.ClassBindingFacts.empty());
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
                deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, index, modules, entry,
                deal.semantic.ClassRegistrationSeeds.builtinErrorOnly());
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
     * host fixtures are {@code host-abi/host-array-return-mismatch-e8010},
     * {@code host-bad-return}, {@code host-null-return-bad},
     * {@code host-prewrapped-bad}, {@code host-invalid-utf8-e8010}, and
     * {@code host-surrogate-utf8-e8010} (all pinned {@code actual: string});
     * the artifact legs of the named fixture run in
     * {@code HostCallRealizationTest}'s pinned fixture set.
     */
    /**
     * The JS raw host-shape arm (ISSUE-0797): the raw non-object host
     * result projects E8011 {@code host module did not return a module
     * object} with the pinned {@code table} expected, the JS runtime's own
     * {@code $kindOf} diagnostic kind as its actual and no location at all —
     * distinct from the located, module-clause declared arms. The JS null's
     * {@code null} and the absent marker's {@code nil} are the vocabulary's
     * own members (never the Lua-shaped carrier-kind {@code table}), while a
     * foreign expected text and an object-shaped or invented actual token
     * fail the arm's own field contract closed.
     */
    static void testRawHostShapeArm() throws Exception {
        System.out.println("-- the raw host-shape arm: the unlocated JS "
            + "diagnostic-kind tuple --");
        FailureArm arm = FailureContractRegistry.arm(FailureArmId.HOST_LOAD_RAW_SHAPE);
        checkEq(FailurePolicyId.HOST_LOAD, arm.policy(), "the arm renders the HOST_LOAD "
            + "row");
        checkEq(1, arm.templateIndex(), "the arm binds the row's raw-shape template");
        checkEq(FailureArm.OriginConvention.NO_ORIGIN, arm.origin(), "the arm carries "
            + "no location convention");
        checkEq(FailureArm.RenderScope.TOP_LEVEL, arm.scope(), "the arm renders a "
            + "top-level tuple");
        checkEq(FailureArm.ExpectedSource.PINNED_TEXT, arm.expectedSource(),
            "the expected token is the arm's own pinned text");
        checkEq("table", arm.pinnedExpectedText(), "the pinned expected text is table");
        checkEq(FailureArm.ActualProjection.JS_DIAGNOSTIC_KIND, arm.actualProjection(),
            "the actual is the JS diagnostic-kind projection ($kindOf)");
        checkEq("E8011", FailureContractRegistry.codeOf(arm), "the arm's code is E8011");
        BoundaryFailure rendered = FailureContractRegistry.render(
            FailureArmId.HOST_LOAD_RAW_SHAPE, Map.of(), "table", "number", null);
        checkEq("E8011", rendered.code().name(), "the rendered tuple carries E8011");
        checkEq("host module did not return a module object", rendered.message(),
            "the rendered message is the module-less raw-shape text");
        checkEq("table", rendered.expected(), "the rendered expected is table");
        checkEq("number", rendered.actual(), "the rendered actual is the numeric "
            + "JS diagnostic kind");
        checkEq(null, rendered.cause(), "the raw-shape tuple carries no cause");
        // The criterion's null case: the JS null's own $kindOf token is
        // representable by the declared projection — the Lua-shaped
        // carrier-kind "table" is not what the guard publishes.
        BoundaryFailure nullRaw = FailureContractRegistry.render(
            FailureArmId.HOST_LOAD_RAW_SHAPE, Map.of(), "table", "null", null);
        checkEq("host module did not return a module object", nullRaw.message(),
            "the raw null export renders the module-less raw-shape text");
        checkEq("table", nullRaw.expected(), "the raw null export's expected is table");
        checkEq("null", nullRaw.actual(), "the raw null export's actual is the JS "
            + "null, never the Lua-shaped carrier-kind table");
        checkEq(null, nullRaw.cause(), "the raw null tuple carries no cause");
        // Every closed member of the JS diagnostic-kind vocabulary — the
        // absent marker and the typeof names a non-object result reaches —
        // is admissible on the arm's own declaration.
        for (String member : List.of("nil", "boolean", "number", "string",
                "function", "symbol", "bigint")) {
            BoundaryFailure projected = FailureContractRegistry.render(
                FailureArmId.HOST_LOAD_RAW_SHAPE, Map.of(), "table", member, null);
            checkEq(member, projected.actual(), "the JS diagnostic-kind token " + member
                + " is a member of the arm's declared vocabulary");
        }
        // The object-shaped spellings never reach the guard (every non-null
        // object passes it), so publishing one is a fabricated token.
        expectDefect(() -> FailureContractRegistry.render(
                FailureArmId.HOST_LOAD_RAW_SHAPE, Map.of(), "table", "table", null),
            "the object-shaped table token is not a JS diagnostic kind");
        expectDefect(() -> FailureContractRegistry.render(
                FailureArmId.HOST_LOAD_RAW_SHAPE, Map.of(), "table", "object", null),
            "an invented JS diagnostic-kind spelling fails closed");
        expectDefect(() -> FailureContractRegistry.render(
                FailureArmId.HOST_LOAD_RAW_SHAPE, Map.of(), "function", "number", null),
            "a foreign expected text fails the arm's field contract");
        // The emitted Lua prelude's mirror of the closed vocabulary (real
        // luajit): the JS null renders the arm's complete tuple, while the
        // object-shaped Lua carrier kind — a value that never fails the
        // guard — fails closed. The probe supplies an origin operand (the
        // no-origin guard of an unlocated render is covered separately).
        renderProbes("raw-host-shape-probe",
            new RenderProbe("arm", "js-kind-null",
                "\"HOST_LOAD_RAW_SHAPE\", nil, \"-\", \"table\", \"null\"",
                "true|E8011|table|null|host module did not return a module object",
                "value.code .. '|' .. value.e .. '|' .. value.a .. '|' .. value.m"),
            armProbe("js-kind-object",
                "\"HOST_LOAD_RAW_SHAPE\", nil, \"-\", \"table\", \"table\"", "false|"));
    }

    static void testCarrierKindStringActual() {
        System.out.println("-- the carrier-kind string member (the host string actual) --");
        checkEq("string", FailureProjections.carrierKindToken(
            FailureProjections.CarrierKind.STRING),
            "the carrier-kind projection has the closed string token");
        // host-table-return-mismatch-e8010: the declared table return cell
        // with a string value (pinned actual "string").
        BoundaryFailure tableReturn = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.Table.INSTANCE,
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none()),
            "the host table return cell");
        checkEq("E8010", tableReturn.code().name(), "the pinned host return code");
        checkEq("return value 1 type mismatch: expected table", tableReturn.message(),
            "the pinned host return message");
        checkEq("record", tableReturn.expected(), "the runtime-dialect declared cell descriptor");
        checkEq("string", tableReturn.actual(),
            "the oracle projects the carrier-kind string actual");
        // host-bad-return / host-null-return-bad / host-prewrapped-bad: the
        // same string actual at the declared int/null return cells.
        for (RuntimeDescriptor descriptor : List.of(RuntimeDescriptor.Number.INSTANCE,
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
        // The surrogate code point sequence selects the other closed host
        // string-carrier reason (the host-surrogate-utf8-e8010 pin): the
        // selection is the view's closed encoding defect, never a length.
        BoundaryFailure surrogate = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_SYNC_RETURN, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofInvalidUnicode(
                UnicodeScalars.Defect.SURROGATE),
            BoundaryContext.none()),
            "the host surrogate code point return cell");
        checkEq("return value 1 type mismatch: expected string, got UTF-16 surrogate "
            + "code point", surrogate.message(),
            "the surrogate host string-carrier inner reason is the INNER_ONLY "
                + "arm's own text");
        checkEq("string", surrogate.expected(),
            "the surrogate cell keeps the declared string descriptor");
        checkEq("string", surrogate.actual(),
            "a surrogate view is a string carrier on the carrier-kind projection");
        // The malformed class explicitly at the parameter cell (the
        // classification-only factory defaults to it).
        BoundaryFailure malformed = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_PARAMETER, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofInvalidUnicode(
                UnicodeScalars.Defect.MALFORMED),
            BoundaryContext.parameter(1)),
            "the host malformed string parameter cell");
        checkEq("parameter 1 type mismatch: expected string, got invalid UTF-8 "
            + "encoding", malformed.message(),
            "the malformed host string-carrier inner reason is the INNER_ONLY "
                + "arm's own text");
        checkEq("string", malformed.actual(),
            "the malformed carrier cell projects the string actual");
        // The host parameter cell of the same shape (host-boolean-param-mismatch's
        // sibling int cell with a string value).
        BoundaryFailure parameter = oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_PARAMETER, RuntimeDescriptor.Number.INSTANCE,
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.parameter(1)),
            "the host number parameter cell");
        checkEq("E8010", parameter.code().name(), "the pinned host parameter code");
        checkEq("parameter 1 type mismatch: expected number", parameter.message(),
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
            RuntimeDescriptor.parseCanonicalText("[number]"),
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none()),
            "the array completion cell"), span);
        checkEq(new Tuple("E8001", "expected array", span, "array", "string"),
            oracleArray, "the oracle renders the array completion kind arm");
        Tuple oraclePinned = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.ASYNC_COMPLETION, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofNumber(5), BoundaryContext.none()),
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
            () -> deal.codegen.jvm.JvmRuntime.bcheckCompletion("array(number)",
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
     * The completion cell's function-signature family (P4's tuple identity,
     * the ISSUE-0759 remediation): a differing carried signature at the
     * completion position renders the agreed closed
     * {@code FUNCTION_SIGNATURE_MISMATCH} arm with the declared and carried
     * canonical texts, and the matching carried signature stays an admission
     * control on every consumer.
     *
     * <p>The drive is a real three-consumer drive: it materializes its own
     * host-async project (a declared {@code async} host function completing
     * with a function value), compiles it through the release-owned
     * production invocation, and lowers it once, so the supplied await origin
     * is the lowerer's own {@code ASYNC_COMPLETION} {@code SourceOrigin}. The
     * oracle runs the lowered project's async entries through its closed host
     * seam; the LuaJIT and JVM legs run the staged production artifacts under
     * real {@code luajit} and {@code javac --release 25 -proc:none} +
     * {@code java} with the real host module deployed. Every compared field —
     * the span included — is captured from the failure the consumer produced
     * (the oracle's error snapshot, the Lua error table's {@code .o}, the JVM
     * {@code DealError.origin}), never substituted at comparison time, so a
     * consumer that publishes a constant or a derived span is reported by
     * field name.</p>
     */
    static void testCompletionSignatureDrive() throws Exception {
        System.out.println("-- the completion cell's function-signature family: the "
            + "oracle, the LuaJIT artifact, and the JVM artifact publish the lowered "
            + "await origin on the agreed E8010 arm --");
        Path root = Files.createTempDirectory("completion-signature").toRealPath();
        try {
            CompletionDrive drive = compileCompletionDrive(root);
            if (drive == null) {
                return;
            }
            Map<String, Tuple> captured = driveCompletions(drive, COMPLETION_ENTRY_SOURCE,
                COMPLETION_CELLS, FailureArmAuthorityTest::completionResponder, COMPLETION_LUA_HOST,
                COMPLETION_JVM_HOST, (cell, origin) -> new Tuple("E8010",
                    "function signature mismatch: expected (number)->number, got (number)->string",
                    origin, "(number)->number", "(number)->string"));

            // The span negative operates on the origins the consumers
            // actually published: the two failing cells render the identical
            // signature tuple and differ only in the lowerer-supplied await
            // origin, so the comparison must report the field by name, and a
            // consumer publishing the arm's origin-convention name instead of
            // the await position is caught here.
            Tuple direct = captured.get("direct");
            Tuple nullable = captured.get("nullable");
            if (direct != null && nullable != null) {
                check(!"AWAIT_EXPRESSION".equals(direct.span()), "the LuaJIT artifact "
                    + "publishes the lowerer-selected await origin, never the arm's "
                    + "origin-convention name");
                checkEq("span", firstDifferingField(direct, nullable), "two cells whose "
                    + "only published difference is the consumer-attached await origin "
                    + "are reported by field name span");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The completion cell's language-null family (the P2 admission contract):
     * a host operation whose completion value is the language null must be
     * admitted by the declared {@code null} and nullable completion
     * descriptors on all three consumers — the oracle's {@code NullValue},
     * the deployed runtime's own {@code __rt.__NULL} sentinel the LuaJIT host
     * returns, and the JVM host surface's null carrier — while a nonnullable
     * {@code int} completion with a string value renders the closed
     * {@code ASYNC_COMPLETION_KIND} tuple at the supplied await origin. The
     * drive materializes its own host-async project, compiles it through the
     * release-owned production invocation on both targets and lowers it once,
     * so every compared await origin is the lowerer's own
     * {@code ASYNC_COMPLETION} {@code SourceOrigin}; the admitted cells'
     * entries also re-assert the admitted value is the language null with an
     * in-language identity check, so an admission of any other value is
     * reported as the cell's own failure.
     */
    static void testCompletionNullCarrierDrive() throws Exception {
        System.out.println("-- the completion cell's language-null family: the oracle, "
            + "the LuaJIT artifact, and the JVM artifact admit the runtime-null "
            + "completion and publish the wrong-kind tuple at the await origin --");
        Path root = Files.createTempDirectory("completion-null").toRealPath();
        try {
            CompletionDrive drive = compileCompletionDrive(root,
                NULL_COMPLETION_ENTRY_SOURCE, NULL_COMPLETION_HOST_DECLARATION,
                NULL_COMPLETION_CELLS);
            if (drive == null) {
                return;
            }
            driveCompletions(drive, NULL_COMPLETION_ENTRY_SOURCE, NULL_COMPLETION_CELLS,
                FailureArmAuthorityTest::nullCompletionResponder, NULL_COMPLETION_LUA_HOST, NULL_COMPLETION_JVM_HOST,
                (cell, origin) -> new Tuple("E8001", "expected " + cell.declaredSpec(),
                    origin, cell.declaredSpec(), cell.actual()));
        } finally {
            deleteRecursively(root);
        }
    }

    private static Map<String, Tuple> driveCompletions(CompletionDrive drive, String source,
            List<CompletionCell> cells, java.util.function.Supplier<SemanticOracle.HostResponder> responder,
            String luaHost, String jvmHost,
            java.util.function.BiFunction<CompletionCell, String, Tuple> expectedTuple) throws Exception {
        Map<String, String> origins = new LinkedHashMap<>();
        Map<String, Tuple> failures = new LinkedHashMap<>();
        Map<String, String> admissions = new LinkedHashMap<>();
        for (CompletionCell cell : cells) {
            String origin = awaitOriginAt(drive.entryFile(), source, cell);
            origins.put(cell.label(), origin);
            check(drive.completionCells().contains(cell.declaredSpec() + "@" + origin),
                cell.label() + ": lowering selects await origin " + origin + ": " + drive.completionCells());
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
                drive.project(), drive.tables(), responder.get(), drive.project().entryModule(), cell.export(), List.of());
            if (run.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure failure) {
                SemanticRuntimeModel.ErrorSnapshot error = failure.error();
                failures.put(cell.label(), new Tuple(error.code(), error.message(), error.origin(),
                    error.expected(), error.actual()));
            } else {
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                    cell.label() + ": admitted completion succeeds: " + run.terminal());
                admissions.put(cell.label(), String.valueOf(run.terminal()));
            }
        }
        Map<String, String> luaRows = runLuaCompletionDrive(drive, luaHost, cells);
        Map<String, String> jvmRows = runJvmCompletionDrive(drive, jvmHost, cells);
        Map<String, Tuple> captured = new LinkedHashMap<>();
        for (CompletionCell cell : cells) {
            String what = cell.label() + " completion";
            if (!cell.failing()) {
                check(admissions.containsKey(cell.label()), what + " admitted by Oracle: " + admissions.get(cell.label()));
                checkEq(cell.label() + "|OK", luaRows.get(cell.label()), what + " admitted by LuaJIT artifact");
                checkEq(cell.label() + "|OK", jvmRows.get(cell.label()), what + " admitted by JVM artifact");
                continue;
            }
            Tuple expected = expectedTuple.apply(cell, origins.get(cell.label()));
            Tuple oracle = failures.get(cell.label());
            check(oracle != null, what + " fails on Oracle");
            Tuple lua = tupleOfConsumerRow(cell, luaRows.get(cell.label()), "the LuaJIT artifact");
            Tuple jvm = tupleOfConsumerRow(cell, jvmRows.get(cell.label()), "the JVM artifact");
            if (oracle == null || lua == null || jvm == null) continue;
            checkEq(expected, oracle, what + ": Oracle tuple at supplied await origin");
            checkEq(null, firstDifferingField(expected, lua), what + ": LuaJIT tuple identity");
            checkEq(null, firstDifferingField(expected, jvm), what + ": JVM tuple identity");
            checkEq(null, firstDifferingField(lua, jvm), what + ": LuaJIT/JVM tuple identity");
            captured.put(cell.label(), lua);
        }
        return captured;
    }

    /** One cell of the completion-signature drive. */
    private record CompletionCell(String label, String export, String hostExport,
                                  String declaredSpec, boolean failing, String actual) {

        CompletionCell(String label, String export, String hostExport,
                       String declaredSpec, boolean failing) {
            this(label, export, hostExport, declaredSpec, failing, null);
        }

        /** The entry module's await expression of this cell. */
        String awaitMarker() {
            return "await host." + hostExport + "()";
        }
    }

    /** The four driven cells: the two failing signatures and their controls. */
    private static final List<CompletionCell> COMPLETION_CELLS = List.of(
        new CompletionCell("direct", "test_direct", "fetchDirect", "(number)->number", true),
        new CompletionCell("nullable", "test_nullable", "fetchNullable", "?(number)->number",
            true),
        new CompletionCell("direct-match", "test_direct_match", "fetchDirectMatch",
            "(number)->number", false),
        new CompletionCell("nullable-match", "test_nullable_match",
            "fetchNullableMatch", "?(number)->number", false));

    /**
     * The drive's host declaration: two async exports completing with the
     * declared direct/nullable function type and their matching controls.
     */
    private static final String COMPLETION_HOST_DECLARATION = """
        export function fetchDirect(): Promise<(x: number) => number>;
        export function fetchNullable(): Promise<((x: number) => number) | null>;
        export function fetchDirectMatch(): Promise<(x: number) => number>;
        export function fetchNullableMatch(): Promise<((x: number) => number) | null>;
        """;

    /**
     * The drive's entry module: four async exports awaiting the declared host
     * functions. Every await expression sits on its own source line, so the
     * drive derives each cell's supplied await origin from this text and
     * compares it with the origin each consumer attaches.
     */
    private static final String COMPLETION_ENTRY_SOURCE = """
        import * as host from "host/async_fn";

        export async function test_direct(): Promise<(x: number) => number> {
          let f: (x: number) => number = await host.fetchDirect();
          return f;
        }

        export async function test_nullable(): Promise<((x: number) => number) | null> {
          let f: ((x: number) => number) | null = await host.fetchNullable();
          return f;
        }

        export async function test_direct_match(): Promise<(x: number) => number> {
          let f: (x: number) => number = await host.fetchDirectMatch();
          return f;
        }

        export async function test_nullable_match(): Promise<((x: number) => number) | null> {
          let f: ((x: number) => number) | null = await host.fetchNullableMatch();
          return f;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The drive's Lua host implementation: the two failing cells complete
     * with a DEAL function carrier whose carried canonical signature is
     * {@code (int)->string} while the declared completion is
     * {@code (int)->int} / {@code ?(int)->int}; the matching controls complete
     * with the declared signature.
     */
    private static final String COMPLETION_LUA_HOST = """
        local rt = require("deal.runtime")
        local __K = rt.__keys

        local function mismatched()
          return {[__dealCarrierKeys.fn] = function(x) return "s" end, [__dealCarrierKeys.sig] = "function(number;string)",
            [__dealCarrierKeys.csig] = "(number)->string", [__dealCarrierKeys.fid] = nil}
        end

        local function matching()
          return {[__dealCarrierKeys.fn] = function(x) return x end, [__dealCarrierKeys.sig] = "function(number;number)",
            [__dealCarrierKeys.csig] = "(number)->number", [__dealCarrierKeys.fid] = nil}
        end

        local function operation(value)
          return rt.async_start(function() return value() end)
        end

        return {
          fetchDirect = function() return operation(mismatched) end,
          fetchNullable = function() return operation(mismatched) end,
          fetchDirectMatch = function() return operation(matching) end,
          fetchNullableMatch = function() return operation(matching) end,
        }
        """;

    /**
     * The drive's JVM host implementation ({@code %s}: the host module's
     * production class name), the same four completions as the Lua host.
     */
    private static final String COMPLETION_JVM_HOST = """
        import java.util.concurrent.CompletableFuture;

        final class %s {
          public static Object fetchDirect() {
            return CompletableFuture.completedFuture(mismatched());
          }

          public static Object fetchNullable() {
            return CompletableFuture.completedFuture(mismatched());
          }

          public static Object fetchDirectMatch() {
            return CompletableFuture.completedFuture(matching());
          }

          public static Object fetchNullableMatch() {
            return CompletableFuture.completedFuture(matching());
          }

          private static Object mismatched() {
            return new deal.codegen.jvm.JvmRuntime.FunctionValue(args -> "ok",
                "function(number;string)", "(number)->string", null);
          }

          private static Object matching() {
            return new deal.codegen.jvm.JvmRuntime.FunctionValue(args -> 1.0,
                "function(number;number)", "(number)->number", null);
          }
        }
        """;

    /**
     * The language-null completion drive's cells (P2's admission contract):
     * the null and nullable-int completions (the non-failing controls) and
     * the nonnullable wrong-kind control.
     */
    private static final List<CompletionCell> NULL_COMPLETION_CELLS = List.of(
        new CompletionCell("null-completion", "test_null", "fetchNull", "null", false),
        new CompletionCell("nullable-completion", "test_nullable", "fetchNullable",
            "?number", false),
        new CompletionCell("number-wrong-kind", "test_wrong", "fetchWrong", "number", true,
            "string"),
        new CompletionCell("number-null-kind", "test_wrong_null", "fetchWrongNull", "number",
            true, "null"),
        new CompletionCell("nullable-string-completion", "test_nullable_string",
            "fetchNullableString", "?string", false),
        new CompletionCell("string-null-kind", "test_wrong_null_string",
            "fetchWrongNullString", "string", true, "null"));

    /**
     * The language-null drive's host declaration: a null completion, a
     * nullable-int completion, and the nonnullable wrong-kind controls (a
     * string value and the language null itself).
     */
    private static final String NULL_COMPLETION_HOST_DECLARATION = """
        export function fetchNull(): Promise<null>;
        export function fetchNullable(): Promise<number | null>;
        export function fetchWrong(): Promise<number>;
        export function fetchWrongNull(): Promise<number>;
        export function fetchNullableString(): Promise<string | null>;
        export function fetchWrongNullString(): Promise<string>;
        """;

    /**
     * The language-null drive's entry module: the three awaits on their own
     * source lines (the drive derives each cell's supplied await origin from
     * this text); the admitted cells re-assert the admitted value is the
     * language null with an in-language identity check.
     */
    private static final String NULL_COMPLETION_ENTRY_SOURCE = """
        import * as host from "host/async_fn";

        export async function test_null(): Promise<null> {
          let v: null = await host.fetchNull();
          if (v !== null) {
            throw { code: "TEST_FAIL", message: "null completion value mismatch" };
          }
          return null;
        }

        export async function test_nullable(): Promise<number | null> {
          let v: number | null = await host.fetchNullable();
          if (v !== null) {
            throw { code: "TEST_FAIL", message: "nullable completion value mismatch" };
          }
          return null;
        }

        export async function test_wrong(): Promise<number> {
          let v: number = await host.fetchWrong();
          return v;
        }

        export async function test_wrong_null(): Promise<number> {
          let v: number = await host.fetchWrongNull();
          return v;
        }

        export async function test_nullable_string(): Promise<null> {
          let v: string | null = await host.fetchNullableString();
          if (v !== null) {
            throw { code: "TEST_FAIL", message: "nullable string completion value mismatch" };
          }
          return null;
        }

        export async function test_wrong_null_string(): Promise<null> {
          let v: string = await host.fetchWrongNullString();
          return null;
        }

        export function main(): null {
          return null;
        }
        """;

    /**
     * The language-null drive's Lua host implementation: every host operation
     * completes with the deployed runtime's own language-null sentinel
     * ({@code deal/runtime.lua}'s {@code __rt.__NULL} — the carrier a host
     * null completion supplies), except the wrong-kind control's string.
     */
    private static final String NULL_COMPLETION_LUA_HOST = """
        local rt = require("deal.runtime")

        local function operation(value)
          return rt.async_start(function() return value() end)
        end

        return {
          fetchNull = function() return operation(function() return rt.__NULL end) end,
          fetchNullable = function() return operation(function() return rt.__NULL end) end,
          fetchWrong = function() return operation(function() return "s" end) end,
          fetchWrongNull = function()
            return operation(function() return rt.__NULL end)
          end,
          fetchNullableString = function()
            return operation(function() return rt.__NULL end)
          end,
          fetchWrongNullString = function()
            return operation(function() return rt.__NULL end)
          end,
        }
        """;

    /**
     * The language-null drive's JVM host implementation ({@code %s}: the host
     * module's production class name): every host operation completes with
     * the JVM host surface's null carrier, except the wrong-kind control's
     * string.
     */
    private static final String NULL_COMPLETION_JVM_HOST = """
        import java.util.concurrent.CompletableFuture;

        final class %s {
          public static Object fetchNull() {
            return CompletableFuture.completedFuture(null);
          }

          public static Object fetchNullable() {
            return CompletableFuture.completedFuture(null);
          }

          public static Object fetchWrong() {
            return CompletableFuture.completedFuture("s");
          }

          public static Object fetchWrongNull() {
            return CompletableFuture.completedFuture(null);
          }

          public static Object fetchNullableString() {
            return CompletableFuture.completedFuture(null);
          }

          public static Object fetchWrongNullString() {
            return CompletableFuture.completedFuture(null);
          }
        }
        """;

    /** The drive's compiled, lowered, and staged project (one lowering). */
    private record CompletionDrive(Path root, Path entryFile,
                                   ExecutableLoweredProject project,
                                   Map<ModuleId, StructuredBodyTable> tables,
                                   Map<String, Long> entryOpIds, List<String> completionCells,
                                   Path luaOut, Path jvmOut, String modulePath,
                                   String className) {

        Path jvmArtifact() {
            return jvmOut.resolve(className + ".java");
        }
    }

    /**
     * Materializes the drive's host-async project, compiles it through the
     * release-owned production invocation on both targets, and lowers it once:
     * the completion cells' origins are the one lowering's own
     * {@code ASYNC_COMPLETION} {@code SourceOrigin}s.
     */
    private static CompletionDrive compileCompletionDrive(Path root) throws Exception {
        return compileCompletionDrive(root, COMPLETION_ENTRY_SOURCE,
            COMPLETION_HOST_DECLARATION, COMPLETION_CELLS);
    }

    /**
     * The parameterized form of {@link #compileCompletionDrive(Path)}: one
     * drive of the supplied entry module and host declaration, with one
     * expected {@code ASYNC_COMPLETION} cell per driven await.
     */
    private static CompletionDrive compileCompletionDrive(Path root, String entrySource,
            String hostDeclaration, List<CompletionCell> cells) throws Exception {
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Path entry = src.resolve("main.deal");
        Files.writeString(entry, entrySource, StandardCharsets.UTF_8);
        Path declaration = root.resolve("bindings").resolve("async_fn.d.deal");
        Files.createDirectories(declaration.getParent());
        Files.writeString(declaration, hostDeclaration, StandardCharsets.UTF_8);
        Map<String, String> externals = new LinkedHashMap<>();
        externals.put("host.async_fn", declaration.toAbsolutePath().toString());
        externals.put("host/async_fn", declaration.toAbsolutePath().toString());
        CompilerInvocation invocation = CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        Path luaOut = root.resolve("out-luajit");
        CompilationOrchestrator lua = new CompilationOrchestrator(entry, luaOut, false,
            false, false, false, Backend.LUAJIT, externals, List.of(src.toAbsolutePath()),
            Path.of("std").toAbsolutePath().normalize(), null, invocation);
        boolean compiled = lua.compile();
        CheckedProjectBuildResult built = lua.checkedProject();
        var manifests = lua.requirementManifests();
        check(compiled && built != null && built.input() != null && built.index() != null
                && !built.hasErrors() && manifests != null && manifests.manifests() != null
                && lua.hostDeclarationSurface() != null,
            "the completion-signature drive's project compiles through the release-owned "
                + "invocation: " + lua.diagnostics());
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null || manifests.manifests() == null
                || lua.hostDeclarationSurface() == null) {
            return null;
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declared : lua.hostDeclarationSurface().moduleIds()) {
            identities.put(declared, new CanonicalModuleIdentity.ExternalModule(
                declared.path().replace('/', '.')));
        }
        SemanticLowerer.ProjectLoweringResult lowering = SemanticLowerer.lowerProject(
            invocation, built.input(), built.index(), manifests.manifests(),
            lua.hostDeclarationSurface(), identities, BuiltinErrorDeclaration.synthesized(entrySpan(built.input())),
            Set.of());
        check(lowering.project() != null && lowering.project().modules()
                .containsKey(lowering.project().entryModule()),
            "the one lowering produces the drive's implementation closure (the entry "
                + "module; the declared host module is no implementation unit): "
                + lowering.diagnostics());
        if (lowering.project() == null) {
            return null;
        }
        ExecutableLoweredProject project = lowering.project();
        LoweredModuleUnit unit = project.modules().get(project.entryModule());
        Map<String, Long> entryOpIds = new LinkedHashMap<>();
        List<String> completionCells = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY) {
                KindPayload.ExternalEntryPayload payload =
                    (KindPayload.ExternalEntryPayload) op.payload();
                if (payload.async()) {
                    entryOpIds.put(payload.exportName(), op.opId().id());
                }
            } else if (op.kind() == SemanticOpKind.BOUNDARY
                    && ((KindPayload.BoundaryPayload) op.payload()).kind()
                        == BoundaryKind.ASYNC_COMPLETION) {
                KindPayload.BoundaryPayload payload =
                    (KindPayload.BoundaryPayload) op.payload();
                completionCells.add(payload.descriptor().canonicalSpecText() + "@"
                    + originText(op.origin()));
            }
        }
        check(completionCells.size() == cells.size(),
            "the lowering records one ASYNC_COMPLETION cell per driven await: "
                + completionCells);
        Path jvmOut = root.resolve("out-jvm");
        deal.publication.PublicationStager stager = deal.publication.PublicationStager.forRoot(jvmOut);
        deal.module.ProductionProjectEmission.Result jvm;
        try {
            jvm = deal.module.ProductionProjectEmission.run(invocation, built.input(),
                built.index(), manifests.manifests(), lua.hostDeclarationSurface(),
                identities, BuiltinErrorDeclaration.synthesized(entrySpan(built.input())),
                java.util.Set.of(), Backend.JVM, false,
                deal.distribution.DistributionHome.forManifestDirectory(src.toString()),
                stager);
            if (jvm.emitted()) {
                stager.publish();
            }
        } finally {
            stager.discard();
        }
        check(jvm.emitted() && jvm.diagnostics().isEmpty(), "the completion-signature "
            + "drive's project compiles on the JVM target: " + jvm.diagnostics());
        if (!jvm.emitted()) {
            return null;
        }
        String modulePath = project.entryModule().path();
        String className = JvmNames.classNameFor(modulePath);
        return new CompletionDrive(root, entry, project, lowering.tables(), entryOpIds,
            completionCells, luaOut, jvmOut, modulePath, className);
    }

    /** The entry module's AST span (the synthesized builtin Error declaration's). */
    private static deal.ast.Span entrySpan(CheckedProjectInput project) {
        return project.modules().stream()
            .filter(module -> module.moduleId().equals(project.entryModule()))
            .findFirst().orElseThrow().ast().span();
    }

    /**
     * The drive's supplied await origin of one cell: the {@code await}
     * keyword's position in the entry module's own source text (the P3
     * await-expression origin cell), rendered with the consumers' own origin
     * atom.
     */
    private static String awaitOriginAt(Path entryFile, String entrySource,
            CompletionCell cell) {
        String marker = cell.awaitMarker();
        int at = entrySource.indexOf(marker);
        check(at >= 0, "the drive's entry module awaits " + marker);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < at; i++) {
            if (entrySource.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return entryFile + ":" + line + ":" + (at - lineStart + 1);
    }

    /** One op origin's rendered atom (the consumers' own origin text). */
    private static String originText(deal.semantic.ir.SourceOrigin origin) {
        return SemanticRuntimeModel.originAtom(origin.sourceId(),
            origin.span().startLine(), origin.span().startColumn());
    }

    /**
     * The oracle's scripted host seam of the drive: the failing cells'
     * declared async functions complete with a {@code (int)->string} function
     * value, the matching controls with the declared {@code (int)->int}.
     */
    private static SemanticOracle.HostResponder completionResponder() {
        return new SemanticOracle.HostResponder() {
            private String started;

            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                started = export;
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                boolean matching = started != null && started.endsWith("Match");
                return new SyncOutcome.Returned(new SemanticOracle.Value.FuncValue(
                    new FunctionId(9999), (RuntimeDescriptor.Func) RuntimeDescriptor
                        .parseCanonicalText(matching ? "(number)->number" : "(number)->string"),
                    Map.of()));
            }
        };
    }

    /**
     * The language-null drive's scripted host seam: the null and nullable
     * cells complete with the oracle's {@code NullValue}, the wrong-kind
     * control with a string.
     */
    private static SemanticOracle.HostResponder nullCompletionResponder() {
        return new SemanticOracle.HostResponder() {
            private String started;

            @Override
            public AsyncStartOutcome startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor,
                    List<SemanticOracle.Value> args, String operationLabel) {
                started = export;
                return new AsyncStartOutcome.Operation(operationLabel);
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                if ("fetchWrong".equals(started)) {
                    return new SyncOutcome.Returned(
                        new SemanticOracle.Value.StrValue("s"));
                }
                return new SyncOutcome.Returned(SemanticOracle.Value.NullValue.INSTANCE);
            }
        };
    }

    /**
     * Runs the staged LuaJIT artifact's async entries under real
     * {@code luajit} with the drive's host module deployed beside it, and
     * returns one {@code label|...} row per cell.
     */
    private static Map<String, String> runLuaCompletionDrive(CompletionDrive drive,
            String luaHost, List<CompletionCell> cells)
            throws Exception {
        Path host = drive.luaOut().resolve("host").resolve("async_fn.lua");
        Files.createDirectories(host.getParent());
        Files.writeString(host, luaHost, StandardCharsets.UTF_8);
        Path artifact = drive.luaOut().resolve(
            drive.modulePath().replace('.', '/') + ".lua");
        StringBuilder probe = new StringBuilder();
        probe.append("local surface = dofile(")
            .append(quote(artifact.toAbsolutePath().toString())).append(")\n");
        probe.append("local ok, err = __dealMain()\n");
        probe.append("if not ok then print(\"MAIN-FAIL|\" .. __callbacks.__errtext(err)) "
            + "os.exit(0) end\n");
        probe.append("local function row(label, ok2, e)\n");
        probe.append("  if ok2 then print(label .. \"|OK\") return end\n");
        probe.append("  print(label .. \"|\" .. tostring(e.code) .. \"|\" .. tostring(e.m) "
            + ".. \"|\" .. tostring(e.o) .. \"|\" .. tostring(e.e) .. \"|\" "
            + ".. tostring(e.a))\n");
        probe.append("end\n");
        for (CompletionCell cell : cells) {
            probe.append("row(").append(quote(cell.label())).append(", pcall(__asyncEntries[")
                .append(quote(drive.modulePath() + "#" + cell.export()))
                .append("], \"-\", true))\n");
        }
        Path probeFile = drive.luaOut().resolve("completion-signature-probe.lua");
        Files.writeString(probeFile, probe.toString(), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probeFile.getFileName().toString());
        builder.directory(drive.luaOut().toFile());
        // The probe drives the artifact's recorded async entries directly:
        // the deferred-main flag keeps the module-init walk out of the load
        // path (the probe runs it explicitly).
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0, "the staged LuaJIT artifact runs the completion drive "
            + "under luajit: exit=" + exit + " output=" + output);
        return completionRows(output);
    }

    /**
     * Compiles and runs the staged JVM artifact's async entries under
     * real {@code javac --release 25 -proc:none} + {@code java} with the
     * drive's host implementation deployed, and returns one {@code label|...}
     * row per cell.
     */
    private static Map<String, String> runJvmCompletionDrive(CompletionDrive drive,
            String jvmHost, List<CompletionCell> cells)
            throws Exception {
        Path out = drive.jvmOut();
        String hostClass = JvmNames.classNameFor("host/async_fn");
        Path host = out.resolve(hostClass + ".java");
        Files.writeString(host, jvmHost.formatted(hostClass),
            StandardCharsets.UTF_8);
        String driverName = "FailureArmCompletionSignatureDriver_"
            + java.util.UUID.randomUUID().toString().replace("-", "");
        Path driver = out.resolve(driverName + ".java");
        Files.writeString(driver, completionDriverSource(drive, cells, driverName),
            StandardCharsets.UTF_8);
        Path classes = drive.root().resolve("jvm-classes");
        Files.createDirectories(classes);
        String classpath = classes + java.io.File.pathSeparator + absoluteClasspath();
        JvmCompilation.Result compile = JvmCompilation.compile(List.of("javac",
            "--release", "25", "-proc:none", "-cp", classpath, "-d", classes.toString(),
            drive.jvmArtifact().toString(), host.toString(), driver.toString()), out,
            120_000L);
        check(compile.exitCode() == 0, "the staged JVM artifact compiles with the drive's host "
            + "implementation and driver: " + compile.stdout() + compile.stderr());
        if (compile.exitCode() != 0) {
            return Map.of();
        }
        BoundedProcess.BoundedResult run = BoundedProcess.runCaptured(
            List.of("java", "-cp", classpath, driverName), out);
        check(!run.stdoutTruncated() && !run.stderrTruncated(),
            "the staged JVM artifact's completion drive output is complete");
        check(run.exitCode() == 0, "the staged JVM artifact runs the completion drive: "
            + "exit=" + run.exitCode() + " output=" + run.stdout() + run.stderr());
        return completionRows(run.stdout());
    }

    /**
     * The JVM drive's host {@code main}: the artifact's module-init walk, then
     * each async export driven through the artifact's own static dispatch
     * entry; one {@code label|...} row per cell.
     */
    private static String completionDriverSource(CompletionDrive drive,
            List<CompletionCell> cells, String driverName) {
        StringBuilder source = new StringBuilder();
        source.append("public class ").append(driverName).append(" {\n");
        source.append("  public static void main(String[] args) {\n");
        source.append("    try {\n");
        source.append("      ").append(drive.className()).append(".dealMain();\n");
        source.append("    } catch (Throwable t) {\n");
        source.append("      System.out.println(\"MAIN-FAIL|\" + t);\n");
        source.append("      return;\n");
        source.append("    }\n");
        for (CompletionCell cell : cells) {
            source.append("    try {\n");
            source.append("      ").append(drive.className()).append(".ae")
                .append(drive.entryOpIds().get(cell.export()))
                .append("(\"-\", true, new Object[]{});\n");
            source.append("      System.out.println(\"").append(cell.label())
                .append("|OK\");\n");
            source.append("    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {\n");
            source.append("      System.out.println(\"").append(cell.label())
                .append("|\" + e.code + \"|\" + e.msg + \"|\" + e.origin + \"|\" + "
                    + "e.expected + \"|\" + e.actual);\n");
            source.append("    }\n");
        }
        source.append("  }\n");
        source.append("}\n");
        return source.toString();
    }

    /** The {@code label|...} rows of one completion-drive consumer run. */
    private static Map<String, String> completionRows(String output) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            String text = line.strip();
            int bar = text.indexOf('|');
            if (bar > 0) {
                rows.put(text.substring(0, bar), text);
            }
        }
        return rows;
    }

    /**
     * One failing cell's consumer row as its captured tuple: the row's own
     * code, message, origin, expected, and actual fields. A missing row, an
     * admission, or an unframed row is reported as the consumer's defect.
     */
    private static Tuple tupleOfConsumerRow(CompletionCell cell, String row,
                                            String consumer) {
        if (row == null) {
            check(false, consumer + " prints the " + cell.label() + " row");
            return null;
        }
        String[] parts = row.split("\\|", -1);
        if (parts.length == 2 && "OK".equals(parts[1])) {
            check(false, consumer + " admitted the " + cell.label() + " function "
                + "completion that must fail with the signature arm");
            return null;
        }
        check(parts.length == 6, consumer + " frames the " + cell.label() + " row as "
            + "label|code|message|origin|expected|actual: " + row);
        if (parts.length != 6) {
            return null;
        }
        return new Tuple(parts[1], parts[2], parts[3], parts[4], parts[5]);
    }

    /** The absolute entries of the test JVM's own classpath (the javac/-cp). */
    private static String absoluteClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
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
            ok, value = pcall(__bcheck, "array(number)", "table", "abc", nil, true)
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
     * exactly like the emitted artifacts of a chunk that binds the host surface.
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
    // 9a-1b. The typed-boundary class atom and the builtin Error
    // =========================================================================

    /**
     * The typed-boundary actual token of a class carrier (the ISSUE-0835
     * remediation of the ISSUE-0834 requirement): a declared class instance
     * and the builtin Error publish their carried canonical class atom as
     * the closed {@code actual} projection at every non-class boundary — the
     * oracle's {@code typedBoundaryToken}, the JVM runtime's {@code actualOf},
     * the emitted Lua prelude's {@code __typedBoundaryKind}, and the shipped
     * {@code deal/runtime.lua} matcher's own rows agree byte-for-byte. Before
     * the fix the shipped runtime published the raw Lua {@code type()} of the
     * carrier ({@code table}) on its primitive and wrong-kind rows, so
     * the shipped stdlib and completion consumers diverged from the
     * prelude/JVM/JS lanes. The class-identity and signature arms are
     * unchanged and are pinned as controls.
     */
    static void testTypedBoundaryClassAtomCarrier() throws Exception {
        System.out.println("-- the typed-boundary class atom and the builtin Error --");
        String span = FailureContractRegistry.arm(FailureArmId.TYPED_BOUNDARY_KIND)
            .origin().name();
        String atom = "@src/app/User";

        // The oracle: the closed typed-boundary projection of the carrier.
        Tuple oracleString = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofClass(atom), BoundaryContext.none()),
            "the class-at-string cell"), span);
        checkEq(new Tuple("E8001", "expected string", span, "string", atom),
            oracleString, "the oracle projects the carried class atom at a string "
                + "boundary");
        Tuple oracleInt = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, RuntimeDescriptor.Number.INSTANCE,
            BoundaryValueView.ofClass(atom), BoundaryContext.none()),
            "the class-at-number cell"), span);
        Tuple oracleError = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, RuntimeDescriptor.String.INSTANCE,
            BoundaryValueView.ofClass("Error"), BoundaryContext.none()),
            "the builtin-Error-at-string cell"), span);
        checkEq(new Tuple("E8001", "expected string", span, "string", "Error"),
            oracleError, "the oracle projects the canonical bare Error atom");

        // The JVM runtime consumer: the same two carriers, the same tuples.
        deal.codegen.jvm.JvmRuntime.ClassInstance user =
            new WalkInstance(atom, Map.of());
        deal.codegen.jvm.JvmRuntime.DealError jvmString = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("string", "string", user));
        check(jvmString != null, "the JVM string boundary rejects the class instance");
        if (jvmString != null) {
            checkEq(null, firstDifferingField(oracleString,
                new Tuple(jvmString.code, jvmString.msg, span, jvmString.expected,
                    jvmString.actual)),
                "the JVM runtime renders the oracle's identical class-at-string tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmInt = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("number", "number", user));
        check(jvmInt != null, "the JVM number boundary rejects the class instance");
        if (jvmInt != null) {
            checkEq(null, firstDifferingField(oracleInt,
                new Tuple(jvmInt.code, jvmInt.msg, span, jvmInt.expected,
                    jvmInt.actual)),
                "the JVM runtime renders the oracle's identical class-at-number tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmError = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("string", "string",
                new deal.codegen.jvm.JvmRuntime.ErrorValue("E_USER", "boom")));
        check(jvmError != null, "the JVM string boundary rejects the builtin Error");
        if (jvmError != null) {
            checkEq(null, firstDifferingField(oracleError,
                new Tuple(jvmError.code, jvmError.msg, span, jvmError.expected,
                    jvmError.actual)),
                "the JVM runtime renders the oracle's identical Error-at-string tuple");
        }

        // The two Lua consumers under real luajit: the shipped runtime's own
        // matcher rows and the emitted prelude's check, over the same values.
        List<String> rows = runProbe(emittedArmsChunk(), "arms", """
            local rt = require("deal.runtime")
            local user = rt.class_plan_("@src/app/User", {}, {})
            local errv = rt.error_value("E_USER", "boom")
            local function runtimeRow(label, fn)
              local ok, value = pcall(fn)
              if ok then print(label .. "|OK|") return end
              print(label .. "|" .. tostring(value.code) .. "|"
                .. tostring(value.expected) .. "|" .. tostring(value.actual)
                .. "|" .. tostring(value.message))
            end
            runtimeRow("runtime-class-at-string", function() return rt.check_string(user) end)
            runtimeRow("runtime-class-at-number", function() return rt.check_number(user) end)
            runtimeRow("runtime-error-at-string", function() return rt.check_string(errv) end)
            runtimeRow("runtime-class-at-class", function()
              return rt.check_type("@src/app/Admin", user) end)
            runtimeRow("runtime-class-at-function", function()
              return rt.check_type("()->null", user) end)
            local ok, value = pcall(__bcheck, "string", "string", user, nil)
            row("prelude-class-at-string", ok, value)
            ok, value = pcall(__bcheck, "number", "number", user, nil)
            row("prelude-class-at-number", ok, value)
            ok, value = pcall(__bcheck, "string", "string", errv, nil)
            row("prelude-error-at-string", ok, value)
            """, "typed-boundary-class-probe", 8, true);

        checkEq(null, firstDifferingField(oracleString, luaTuple(rows.get(0), span)),
            "the shipped runtime renders the oracle's identical class-at-string tuple: "
                + rows.get(0));
        checkEq(null, firstDifferingField(oracleInt, luaTuple(rows.get(1), span)),
            "the shipped runtime renders the oracle's identical class-at-number tuple: "
                + rows.get(1));
        checkEq(null, firstDifferingField(oracleError, luaTuple(rows.get(2), span)),
            "the shipped runtime publishes the canonical bare Error atom: "
                + rows.get(2));
        // The unchanged arms: the class-identity arm carries both atoms, the
        // function row's kind arm the failing carrier's own token, and the
        // class row's kind arm the carrier it received.
        checkEq("runtime-class-at-class|E8001|@src/app/Admin|" + atom
                + "|expected instance of @src/app/Admin, got " + atom,
            rows.get(3), "the class-identity arm keeps its two carried atoms");
        checkEq("runtime-class-at-function|E8001|function|" + atom
                + "|expected function",
            rows.get(4), "the function row projects the class atom");
        // The emitted prelude's own projection of the same values.
        checkEq(null, firstDifferingField(oracleString, luaTuple(rows.get(5), span)),
            "the emitted prelude renders the oracle's identical class-at-string tuple: "
                + rows.get(5));
        checkEq(null, firstDifferingField(oracleInt, luaTuple(rows.get(6), span)),
            "the emitted prelude renders the oracle's identical class-at-number tuple: "
                + rows.get(6));
        checkEq(null, firstDifferingField(oracleError, luaTuple(rows.get(7), span)),
            "the emitted prelude publishes the canonical bare Error atom: "
                + rows.get(7));
        // The two Lua consumers agree field-for-field (the shipped runtime is
        // not a second authority).
        checkEq(null, firstDifferingField(luaTuple(rows.get(5), span),
                luaTuple(rows.get(0), span)),
            "the shipped runtime and the emitted prelude publish one tuple");
    }

    // =========================================================================
    // 9a-2. Private carrier identity: a table with carrier-like keys
    // =========================================================================

    /**
     * ISSUE-0830: a DEAL table may carry any string key. On the emitted
     * prelude under real luajit a table whose keys spell the internal
     * carrier vocabulary is the table value at the table cell and is
     * rejected at the class, array, function and int cells with the
     * value's own table token — never admitted as a carrier of the arm its
     * keys name. A real carrier (the runtime class representation) keeps its own token, so the two vocabularies stay
     * distinguishable in one process.
     */
    static void testForgedCarrierTableKeys() throws Exception {
        System.out.println("-- private carrier identity: carrier-like table keys "
            + "are data --");
        List<String> rows = runProbe(emittedArmsChunk(), "arms", """
            local forged = {
              __kind = "class", __classname = "Error", __c = true,
              __id = "@m/C", __d = true, __a = true, __n = 3, __nK = 1,
              __da = true, __t = true, __keys = "k", __order = "o",
              __p = true, __f = true, __fn = 1, __csig = "s", __sig = "t",
              __fid = 2, __it = "u", __sid = "v", __val = 3, __mode = 4,
              __jn = true, __jo = true, __ja = true, __jkeys = 5,
              __jnum = 6, __jseen = 7, __ptr = 8, plain = 9,
            }
            local ok, value = pcall(__bcheck, "table", "table", forged, nil)
            row("forged-at-table", ok, value)
            ok, value = pcall(__bcheck, "@m/C", "table", forged, nil)
            row("forged-at-class", ok, value)
            ok, value = pcall(__bcheck, "array(number)", "table", forged, nil)
            row("forged-at-array", ok, value)
            ok, value = pcall(__bcheck, "function(;null)", "table", forged,
              "()->null")
            row("forged-at-function", ok, value)
            ok, value = pcall(__bcheck, "number", "number", forged, nil)
            row("forged-at-number", ok, value)
            ok, value = pcall(__bcheck, "table", "table", {[__dealCarrierKeys.t] = true,
              [__dealCarrierKeys.keys] = {}, [__dealCarrierKeys.order] = {}}, nil)
            row("real-table-carrier", ok, value)
            ok, value = pcall(__bcheck, "array(number)", "array",
              {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 0}, nil,
              "[number]")
            row("real-array-carrier", ok, value)
            print("forged-kind|" .. __typedBoundaryKind("", forged))
            """, "forged-carrier-probe", 8);
        checkEq("forged-at-table|OK|", rows.get(0),
            "a table with carrier-like keys is the table value");
        checkEq("forged-at-class|E8001|class|table|expected class instance",
            rows.get(1), "the forged class carrier is rejected");
        checkEq("forged-at-array|E8001|array|table|expected array",
            rows.get(2), "the forged array carrier is rejected");
        checkEq("forged-at-function|E8001|function|table|expected function",
            rows.get(3), "the forged function carrier is rejected");
        checkEq("forged-at-number|E8001|number|table|expected number", rows.get(4),
            "a table with carrier-like keys is not an number carrier");
        checkEq("real-table-carrier|OK|", rows.get(5),
            "the emitted table carrier is the table value");
        checkEq("real-array-carrier|OK|", rows.get(6),
            "the emitted array carrier is the array value");
        checkEq("forged-kind|table", rows.get(7),
            "the typed-boundary projection of a carrier-like table is table");
    }

    // =========================================================================
    // 9b. The typed-boundary invalid-Unicode cell (the DEAL string boundary)
    // =========================================================================

    /**
     * The typed-boundary invalid-Unicode arm through the real boundary
     * producers (P1/P4, ISSUE-0705): an unpaired-surrogate string — the
     * Lua byte sequence/Java string the classification projects as
     * {@code invalid-unicode} — fails the declared {@code string} boundary
     * on the oracle's {@code BoundaryExecutor.core}, the JVM runtime's
     * {@code bcheck}, and the emitted prelude's {@code __bcheck} alike,
     * with the identical complete tuple. Before the scalar validation both
     * targets admitted every string, so only the oracle rejected the
     * value. The nested element cells project the element arm's own tuple
     * with the closed {@code invalid-unicode} actual, the completion cells
     * keep their pinned kind arm, and the valid strings are the admission
     * controls.
     */
    static void testTypedBoundaryInvalidUnicode() throws Exception {
        System.out.println("-- the typed-boundary invalid-Unicode cell (the DEAL "
            + "string boundary) --");
        String span = FailureContractRegistry.arm(
            FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE).origin().name();
        String message = FailureContractRegistry.arm(
            FailureArmId.TYPED_BOUNDARY_INVALID_UNICODE).template();
        RuntimeDescriptor str = RuntimeDescriptor.String.INSTANCE;
        RuntimeDescriptor arrStr = RuntimeDescriptor.parseCanonicalText("[string]");
        RuntimeDescriptor arrArrStr = RuntimeDescriptor.parseCanonicalText("[[string]]");
        BoundaryValueView bad = BoundaryValueView.of(ActualKind.INVALID_UNICODE);
        BoundaryValueView valid = BoundaryValueView.of(ActualKind.STRING);

        // The oracle leg: the string cell, the element cells, the
        // completion cell, and the admission controls.
        Tuple oracleString = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, str, bad, BoundaryContext.none()),
            "the invalid-unicode string cell"), span);
        checkEq(new Tuple("E8001", message, span, "string", "invalid-unicode"),
            oracleString, "the oracle rejects the invalid string at its boundary");
        check(BoundaryExecutor.check(FailurePolicyId.TYPE_DESCRIPTOR, str, valid,
            BoundaryContext.none()) instanceof BoundaryOutcome.Pass,
            "the oracle admits the valid string at the string boundary");
        Tuple oracleElement = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, arrStr,
            BoundaryValueView.ofArray(valid, bad), BoundaryContext.none()),
            "the invalid-unicode element cell"), span);
        checkEq(new Tuple("E8003", "array element 2 type mismatch", span,
            "string", "invalid-unicode"), oracleElement,
            "the oracle rejects the invalid array element with the element arm");
        Tuple oracleNested = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, arrArrStr,
            BoundaryValueView.ofArray(BoundaryValueView.ofArray(valid),
                BoundaryValueView.ofArray(bad)), BoundaryContext.none()),
            "the nested invalid-unicode element cell"), span);
        checkEq(new Tuple("E8003", "array element 2 type mismatch", span,
            "[string]", "array"), oracleNested,
            "the oracle rejects the nested invalid element with the outer element "
                + "arm");
        check(BoundaryExecutor.check(FailurePolicyId.TYPE_DESCRIPTOR, arrStr,
            BoundaryValueView.ofArray(valid, valid), BoundaryContext.none())
            instanceof BoundaryOutcome.Pass,
            "the oracle admits the valid array elements");
        Tuple oracleCompletion = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.ASYNC_COMPLETION, str, bad, BoundaryContext.none()),
            "the invalid-unicode completion cell"), span);
        checkEq(new Tuple("E8001", "expected string", span, "string",
            "invalid-unicode"), oracleCompletion,
            "the oracle's completion cell keeps its pinned expected-kind text");
        check(BoundaryExecutor.check(FailurePolicyId.ASYNC_COMPLETION, str, valid,
            BoundaryContext.none()) instanceof BoundaryOutcome.Pass,
            "the oracle admits the valid completion string");

        // The JVM runtime leg: the same cells through bcheck/bcheckCompletion.
        deal.codegen.jvm.JvmRuntime.DealError jvmString = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("string", "string", "\uD800"));
        check(jvmString != null, "the JVM string boundary rejects the invalid string");
        if (jvmString != null) {
            checkEq(null, firstDifferingField(oracleString, new Tuple(jvmString.code,
                jvmString.msg, span, jvmString.expected, jvmString.actual)),
                "the JVM runtime renders the oracle's identical string tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmMid = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("string", "string", "a\uDC80b"));
        check(jvmMid != null, "the JVM string boundary rejects the embedded lone "
            + "low surrogate");
        check(jvmFailure(() -> deal.codegen.jvm.JvmRuntime.bcheck("string", "string",
            "ok")) == null, "the JVM string boundary admits the valid string");
        check(jvmFailure(() -> deal.codegen.jvm.JvmRuntime.bcheck(
            "array(string)", "array", jvmArray("ok", "\uD800"))) != null,
            "the JVM array boundary rejects the invalid element");
        deal.codegen.jvm.JvmRuntime.DealError jvmElement = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("array(string)", "array",
                jvmArray("ok", "\uD800")));
        check(jvmElement != null, "the JVM array boundary rejects the invalid "
            + "element");
        if (jvmElement != null) {
            checkEq(null, firstDifferingField(oracleElement, new Tuple(jvmElement.code,
                jvmElement.msg, span, jvmElement.expected, jvmElement.actual)),
                "the JVM runtime renders the oracle's identical element tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmNested = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("array(array(string))",
                "array", jvmArray(jvmArray("ok"), jvmArray("\uD800"))));
        check(jvmNested != null, "the JVM nested array boundary rejects the "
            + "invalid element");
        if (jvmNested != null) {
            checkEq(null, firstDifferingField(oracleNested, new Tuple(jvmNested.code,
                jvmNested.msg, span, jvmNested.expected, jvmNested.actual)),
                "the JVM runtime renders the oracle's identical nested tuple");
        }
        deal.codegen.jvm.JvmRuntime.DealError jvmCompletion = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheckCompletion("string", "string",
                "\uD800"));
        check(jvmCompletion != null, "the JVM completion cell rejects the "
            + "invalid string");
        if (jvmCompletion != null) {
            checkEq(null, firstDifferingField(oracleCompletion,
                new Tuple(jvmCompletion.code, jvmCompletion.msg, span,
                    jvmCompletion.expected, jvmCompletion.actual)),
                "the JVM runtime renders the oracle's identical completion tuple");
        }

        // The emitted prelude leg under real luajit: the same cells through
        // __bcheck, with the invalid bytes spelled as the Lua byte sequence
        // (CESU-8 for the unpaired surrogate).
        List<String> rows = runPreludeProbe("""
            local bad = "\\237\\160\\128"
            local valid = "ok"
            local function arr(...)
              local out = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = select("#", ...)}
              for i = 1, out[__dealCarrierKeys.n] do out[i] = (select(i, ...)) end
              return out
            end
            row("string-bad", pcall(__bcheck, "string", "string", bad, nil))
            row("string-ok", pcall(__bcheck, "string", "string", valid, nil))
            row("element-bad", pcall(__bcheck, "array(string)", "array",
              arr(valid, bad), nil))
            row("element-ok", pcall(__bcheck, "array(string)", "array",
              arr(valid, "two"), nil))
            row("nested-bad", pcall(__bcheck, "array(array(string))", "array",
              arr(arr(valid), arr(bad)), nil))
            row("nested-ok", pcall(__bcheck, "array(array(string))", "array",
              arr(arr(valid), arr("b")), nil))
            row("completion-bad", pcall(__bcheck, "string", "string", bad, nil,
              true))
            row("completion-ok", pcall(__bcheck, "string", "string", valid, nil,
              true))
            """, "invalid-unicode-probe", 8);
        checkEq("string-bad|E8001|string|invalid-unicode|" + message, rows.get(0),
            "the emitted prelude rejects the invalid string with the declared arm");
        checkEq("string-ok|OK|", rows.get(1),
            "the emitted prelude admits the valid string");
        checkEq("element-bad|E8003|string|invalid-unicode|array element 2 type "
            + "mismatch", rows.get(2),
            "the emitted prelude rejects the invalid array element with the element "
                + "arm");
        checkEq("element-ok|OK|", rows.get(3),
            "the emitted prelude admits the valid array elements");
        checkEq("nested-bad|E8003|[string]|array|array element 2 type mismatch",
            rows.get(4), "the emitted prelude rejects the nested invalid element "
                + "with the outer element arm");
        checkEq("nested-ok|OK|", rows.get(5),
            "the emitted prelude admits the valid nested elements");
        checkEq("completion-bad|E8001|string|invalid-unicode|expected string",
            rows.get(6), "the emitted prelude's completion cell keeps its pinned "
                + "expected-kind text");
        checkEq("completion-ok|OK|", rows.get(7),
            "the emitted prelude admits the valid completion string");
        checkEq(null, firstDifferingField(oracleString, luaTuple(rows.get(0), span)),
            "the Lua artifact renders the oracle's identical string tuple");
        checkEq(null, firstDifferingField(oracleElement, luaTuple(rows.get(2), span)),
            "the Lua artifact renders the oracle's identical element tuple");
        checkEq(null, firstDifferingField(oracleNested, luaTuple(rows.get(4), span)),
            "the Lua artifact renders the oracle's identical nested tuple");
        checkEq(null, firstDifferingField(oracleCompletion, luaTuple(rows.get(6), span)),
            "the Lua artifact renders the oracle's identical completion tuple");
    }

    /** One JVM runtime array carrier with the given elements. */
    private static deal.codegen.jvm.JvmRuntime.Array jvmArray(Object... elements) {
        deal.codegen.jvm.JvmRuntime.Array array =
            new deal.codegen.jvm.JvmRuntime.Array(elements.length);
        for (Object element : elements) {
            array.elements.add(element);
        }
        return array;
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
     * DEAL function value ({@code function}), and a nonfinite number ({@code number}); the
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
        Tuple oracleNumber = checkStdJsonOracle(origin,
            new deal.semantic.SharedStdlibSemantics.Value.Number(Double.NaN),
            span, expectedText, "number",
            "the oracle projects a nonfinite number as number");
        // The JVM runtime leg (the same arm through the shared helper).
        Tuple jvmMissing = checkStdJsonJvm(tableWith("v", deal.codegen.jvm.JvmRuntime.MISSING),
            span, expectedText, "nil", "the JVM runtime projects the absent marker as nil");
        Tuple jvmClass = checkStdJsonJvm(tableWith("v",
                new deal.codegen.jvm.JvmRuntime.ErrorValue("E8001", "x")),
            span, expectedText, "table",
            "the JVM runtime projects a class instance as the table-carried value");
        Tuple jvmFunction = checkStdJsonJvm(tableWith("v",
                new deal.codegen.jvm.JvmRuntime.FunctionValue(args -> null, "() -> null")),
            span, expectedText, "function",
            "the JVM runtime projects a DEAL function value as the function member");
        Tuple jvmNumber = checkStdJsonJvm(tableWith("v", Double.valueOf(Double.NaN)),
            span, expectedText, "number",
            "the JVM runtime projects a nonfinite number as number");
        checkEq(null, firstDifferingField(oracleMissing, jvmMissing),
            "the JVM runtime renders the oracle's identical absent-marker tuple");
        checkEq("OK", jvmClass.code(),
            "the JVM runtime traverses the legitimate Error carrier, unlike an unregistered Oracle class shell");
        checkEq(null, firstDifferingField(oracleFunction, jvmFunction),
            "the JVM runtime renders the oracle's identical function tuple");
        checkEq(null, firstDifferingField(oracleNumber, jvmNumber),
            "the JVM runtime renders the oracle's identical number tuple");
        // The emitted prelude leg, under real luajit: the arm's own render
        // through the serialized table (its pinned text included).
        List<String> rows = runPreludeProbe("""
            local function call(root)
              return pcall(__stdlibInvoke, "STDLIB_CALL", "JSON_STRINGIFY", "op",
                "digest", "parent", "origin", root)
            end
            local ok, value = call({[__dealCarrierKeys.t] = true, [__dealCarrierKeys.order] = {"m"}, m = __MISSING})
            row("missing", ok, value)
            ok, value = call({[__dealCarrierKeys.t] = true, [__dealCarrierKeys.order] = {"c"},
              c = {[__dealCarrierKeys.c] = true, [__dealCarrierKeys.id] = "@a/B", [__dealCarrierKeys.f] = {}, [__dealCarrierKeys.p] = {}}})
            row("class", ok, value)
            ok, value = call({[__dealCarrierKeys.t] = true, [__dealCarrierKeys.order] = {"f"}, f = function() end})
            row("function", ok, value)
            ok, value = call({[__dealCarrierKeys.t] = true, [__dealCarrierKeys.order] = {"n"}, n = 0 / 0})
            row("nan", ok, value)
            """, "std-json-probe", 4);
        checkEq("missing|OK|", rows.get(0),
            "the emitted prelude omits an absent object member");
        checkEq("class|E8001|" + expectedText
                + "|table|unsupported type for JSON encoding: table",
            rows.get(1), "the emitted prelude renders the arm's table cell");
        checkEq("function|OK|", rows.get(2),
            "the emitted prelude omits a function-valued object member");
        checkEq("nan|OK|", rows.get(3),
            "the emitted prelude substitutes null for a nonfinite number");
        checkEq("OK", oracleMissing.code(),
            "the Oracle and Lua agree on absent-member acceptance");
        checkEq(null, firstDifferingField(oracleClass, luaTuple(rows.get(1), span)),
            "the Lua artifact renders the oracle's identical class tuple");
        checkEq("OK", oracleFunction.code(),
            "the Oracle and Lua agree on function-member omission");
        checkEq("OK", oracleNumber.code(),
            "the Oracle and Lua agree on nonfinite substitution");
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
        if (!"table".equals(actual)) {
            String encoded = outcome instanceof deal.semantic.SharedStdlibSemantics.Outcome.Success<
                    deal.semantic.SharedStdlibSemantics.Value> success
                    && success.value() instanceof deal.semantic.SharedStdlibSemantics.Value.String text
                    && text.scalar() instanceof deal.semantic.ir.UnicodeScalars.Valid valid
                ? valid.carrier() : "unexpected failure";
            checkEq("number".equals(actual) ? "{\"v\":null}" : "{}", encoded,
                "generic stringify substitutes/omits the formerly rejected " + actual + " member");
            return new Tuple("OK", encoded, span, null, null);
        }
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
        Object value = deal.codegen.jvm.JvmRuntime.stdlib("STDLIB_CALL", "JSON_STRINGIFY",
            "op", "digest", "parent", "origin", new Object[] {table});
        String encoded = String.valueOf(value);
        String expectedJson = "number".equals(actual) ? "{\"v\":null}"
            : "table".equals(actual) ? "{\"v\":{\"code\":\"E8001\",\"message\":\"x\"}}" : "{}";
        checkEq(expectedJson, encoded, "generic JVM stringify: " + actual);
        return new Tuple("OK", encoded, span, null, null);
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
    // 11. JVM stdlib results and closed failure projection
    // =========================================================================

    /** Sqrt publishes NaN; JSON parse failures retain their closed arm tuple. */
    static void testStdlibResultsAndFailureArms() {
        System.out.println("-- JVM stdlib results and closed failure arms --");
        String origin = "arm-battery.deal:1:1";
        Object sqrt = deal.codegen.jvm.JvmRuntime.stdlib("STDLIB_CALL", "MATH_SQRT",
            "op", "digest", "parent", origin, new Object[] {-1.0d});
        check(sqrt instanceof Double result && Double.isNaN(result),
            "the MATH_SQRT cell publishes NaN, never a domain failure");
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
        System.out.println("-- the number-ladder wrong-kind cell (the closed kind arm) --");
        String origin = "-";
        Tuple reference = tupleOf(FailureContractRegistry.render(
            FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "number"), "number", "string",
            null), origin);
        checkEq(new Tuple("E8001", "expected number", origin, "number", "string"),
            reference, "the authority renders the suffix-less kind arm");
        checkJvmArm(FailureArmId.TYPED_BOUNDARY_KIND, Map.of("kind", "number"), "number",
            "string", origin, "the JVM number boundary wrong-kind cell",
            () -> deal.codegen.jvm.JvmRuntime.bcheck("number", "string", "bad"));
        List<String> rows = runPreludeProbe("""
            local ok, value = pcall(__bcheck, "number", "string", "bad")
            row("number-kind", ok, value)
            """, "number-boundary-probe", 1);
        checkEq("number-kind|E8001|number|string|expected number", rows.get(0),
            "the emitted prelude renders the number kind arm");
        checkEq(null, firstDifferingField(reference, luaTuple(rows.get(0), origin)),
            "Lua renders the authority's identical number-boundary tuple");
        // The oracle leg: a lowered unit whose module-init block feeds a
        // string const into the ladder (the cell's own engine render).
        Tuple oracleTuple = oracleIntLadderWrongKindLeg();
        checkEq(null, firstDifferingField(reference,
            new Tuple(oracleTuple.code(), oracleTuple.message(), origin,
                oracleTuple.expected(), oracleTuple.actual())),
            "the oracle renders the authority's identical number-ladder tuple");

        expectDefect(() -> BoundaryFailure.fromRow(
            FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR), 0, "number",
            "string", new LinkedHashMap<>(), null),
            "the superseded row projection fails closed on the corrected kind template");
    }

    private static Tuple oracleIntLadderWrongKindLeg() {
        deal.semantic.ir.ModuleId module = new deal.semantic.ir.ModuleId("number-ladder");
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
            intLadderOp(intrinsicOp, deal.semantic.ir.SemanticOpKind.BOUNDARY,
                new deal.semantic.ir.KindPayload.BoundaryPayload(
                    BoundaryKind.VARIABLE_DECLARATION, RuntimeDescriptor.Number.INSTANCE, input,
                    new deal.semantic.ir.BoundaryRealization.RuntimeValidation("number-cell")),
                new deal.semantic.ir.ValueId(3), RuntimeDescriptor.Number.INSTANCE,
                deal.semantic.ir.FailurePolicyId.TYPE_DESCRIPTOR, origin, List.of(input),
                List.of(RuntimeDescriptor.String.INSTANCE)));
        deal.semantic.ir.StructuredBodyTable table =
            new deal.semantic.ir.StructuredBodyTable(
                Map.of(initBlock, List.of(constOp, intrinsicOp)),
                Map.of(constOp, initBlock, intrinsicOp, initBlock));
        deal.semantic.ir.LoweredModuleUnit unit =
            new deal.semantic.ir.LoweredModuleUnit(
                deal.semantic.ir.LoweredModuleUnit.FORMAT_VERSION,
                deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, module, "hash",
                "context", java.util.Set.of(), Map.of(), Map.of(), Map.of(),
                new deal.semantic.ir.ModuleInitPlan(List.of(), initBlock),
                deal.semantic.ir.ExportPlan.empty(), Map.of(), ops, table,
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                deal.semantic.ir.ClassBindingFacts.empty());
        deal.semantic.SemanticRuntimeModel.ConsumerRun run =
            deal.semantic.SemanticOracle.execute(unit, table);
        check(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                .DealFailure,
            "the oracle terminates the number-ladder cell with a DEAL failure, never an "
                + "internal defect: " + run.terminal());
        if (!(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                .DealFailure failure)) {
            return new Tuple("?", "?", origin.toString(), "number", "string");
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
    // 11c. The reachable array bounds arms render through the arm table
    // =========================================================================

    /** Array read/write/delete bounds render through the shared arm table. */
    static void testArrayBoundsArms() throws Exception {
        System.out.println("-- array bounds arms --");
        String span = "bounds-arms.deal:1:1";
        // The oracle's shared executor renders each reachable bounds arm.
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.ARRAY_READ_BOUNDS,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR,
                RuntimeDescriptor.Number.INSTANCE, BoundaryValueView.ofNumber(0),
                BoundaryContext.arrayRead(-1, 1)), "the out-of-range read"), span)),
            "the oracle renders ARRAY_READ_BOUNDS");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.ARRAY_WRITE_BOUNDS,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
                RuntimeDescriptor.Number.INSTANCE, BoundaryValueView.ofNumber(0),
                BoundaryContext.writeBounds(2, 1)), "the write bounds"), span)),
            "the oracle renders ARRAY_WRITE_BOUNDS");
        checkEq(null, firstDifferingField(
            tupleOf(FailureContractRegistry.render(FailureArmId.ARRAY_DELETE_BOUNDS,
                Map.of(), null, null, null), span),
            tupleOf(oracleFailure(BoundaryExecutor.check(
                FailurePolicyId.ARRAY_DELETE_BOUNDS, RuntimeDescriptor.Number.INSTANCE,
                BoundaryValueView.ofNumber(0), BoundaryContext.writeBounds(-1, 1)),
                "the delete bounds"), span)),
            "the oracle renders ARRAY_DELETE_BOUNDS");
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
    private record RenderProbe(String lane, String label, String arguments, String expected, String value) {
        RenderProbe(String lane, String label, String arguments, String expected) {
            this(lane, label, arguments, expected, lane.equals("template") ? "value" : "value.m");
        }
    }

    private static void renderProbes(String name, RenderProbe... probes) throws Exception {
        StringBuilder source = new StringBuilder();
        for (RenderProbe probe : probes) {
            String function = switch (probe.lane()) {
                case "arm" -> "__arm";
                case "template" -> "__renderTemplate";
                default -> throw new IllegalArgumentException(probe.lane());
            };
            source.append("do local ok, value = pcall(").append(function).append(", ")
                .append(probe.arguments()).append(")\nprint(").append(luaString(probe.label()))
                .append(" .. '|' .. tostring(ok) .. '|' .. (ok and ")
                .append(probe.value())
                .append(" or '')) end\n");
        }
        List<String> rows = runPreludeProbe(source.toString(), name, probes.length);
        for (int i = 0; i < probes.length; i++) {
            RenderProbe probe = probes[i];
            checkEq(probe.label() + "|" + probe.expected(), rows.get(i),
                name + ": Lua " + probe.lane() + " " + probe.label());
        }
    }

    private static RenderProbe armProbe(String label, String arguments, String expected) {
        return new RenderProbe("arm", label, arguments, expected);
    }

    private static RenderProbe innerProbe(String lane, String label, String inner, boolean admitted) {
        String arguments = "\"HOST_PARAMETER_CELL\", {index = \"1\", inner = " + luaString(inner) + "}"
            + (lane.equals("arm") ? ", \"-\", \"number\", \"number\"" : "");
        return new RenderProbe(lane, label, arguments,
            admitted ? "true|parameter 1 type mismatch: " + inner : "false|");
    }

    private static void signatureProbes(String name, String[][] rows, RenderProbe... extra) throws Exception {
        List<RenderProbe> probes = new ArrayList<>();
        for (String[] row : rows) {
            probes.add(innerProbe("arm", row[0], "function signature mismatch: expected "
                + row[1] + ", got " + row[2], Boolean.parseBoolean(row[3])));
        }
        probes.addAll(List.of(extra));
        renderProbes(name, probes.toArray(RenderProbe[]::new));
    }

    static void testDeclaredFieldShapeNegatives() throws Exception {
        System.out.println("-- the declared expected/actual shapes fail closed --");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.JSON_STRINGIFY_UNSUPPORTED, Map.of("actual", "function"),
            "WRONG", "function", null),
            "a fabricated expected text on the pinned-text arm fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "number"), "WRONG", "string", null),
            "an expected field outside the arm's own kind text fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "frobnicate"), "frobnicate", "string", null),
            "an expected field derived from an unknown kind text fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "class"), "class", "string", null),
            "the class-kind arm's token is not its kind text (the closed kind text is "
                + "'class instance') and fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.TYPED_BOUNDARY_KIND,
            Map.of("kind", "number"), "number", "WRONG", null),
            "an actual token outside the closed typed-boundary vocabulary fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "expected boolean"), "WRONG", "number", null),
            "a host cell's expected field outside the descriptor grammar fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.SUBSTRING_BOUNDS,
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
        checkEq("substring bounds must be finite integers",
            FailureContractRegistry.render(FailureArmId.SUBSTRING_BOUNDS,
                Map.of(), null, null, null).message(),
            "substring integral admission has one exact arm");
        // The serialized Lua renderer's equivalent fail-closed validation,
        // under real luajit.
        renderProbes("field-shape-probe",
            armProbe("kind-expected", "\"TYPED_BOUNDARY_KIND\", {kind = \"number\"}, \"-\", \"WRONG\", \"string\"", "false|"),
            armProbe("kind-actual", "\"TYPED_BOUNDARY_KIND\", {kind = \"number\"}, \"-\", \"number\", \"WRONG\"", "false|"),
            armProbe("pinned-expected", "\"JSON_STRINGIFY_UNSUPPORTED\", {actual = \"function\"}, \"-\", \"WRONG\", \"function\"", "false|"),
            armProbe("fieldless-actual", "\"SUBSTRING_BOUNDS\", nil, \"-\", nil, \"WRONG\"", "false|"),
            armProbe("kind-pair", "\"TYPED_BOUNDARY_KIND\", {kind = \"number\"}, \"-\", \"string\", \"string\"", "false|"),
            armProbe("kind-text", "\"TYPED_BOUNDARY_KIND\", {kind = \"frobnicate\"}, \"-\", \"frobnicate\", \"string\"", "false|"),
            new RenderProbe("arm", "control", "\"TYPED_BOUNDARY_KIND\", {kind = \"number\"}, \"-\", \"number\", \"string\"",
                "true|E8001|number|string|expected number", "value.code .. '|' .. value.e .. '|' .. value.a .. '|' .. value.m"));
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
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "x", "actual", "class:arm/walk/Walk"), null,
            "class:arm/walk/Walk", null),
            "the superseded class:<ClassId> spelling fails closed");
        checkEq("Error", deal.codegen.jvm.JvmRuntime.arm(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "", "actual", "Error"), WALK_SPAN, null,
                "Error").actual,
            "the JVM arm render keeps the carried canonical atom (the control)");
        // The serialized Lua renderer, under real luajit.
        renderProbes("class-spelling-probe",
            armProbe("class-spelling", "\"JSON_TO_WALK\", {fieldPath = \"x\", actual = \"class:arm/walk/Walk\"}, \"-\", nil, \"class:arm/walk/Walk\"", "false|"),
            armProbe("class-nonatom", "\"JSON_TO_WALK\", {fieldPath = \"x\", actual = \"class:not-a-canonical-atom\"}, \"-\", nil, \"class:not-a-canonical-atom\"", "false|"),
            armProbe("class-empty", "\"JSON_TO_WALK\", {fieldPath = \"x\", actual = \"class:\"}, \"-\", nil, \"class:\"", "false|"),
            armProbe("class-superseded-alias", "\"JSON_TO_WALK\", {fieldPath = \"x\", actual = \"@$builtin/Error\"}, \"-\", nil, \"@$builtin/Error\"", "false|"),
            new RenderProbe("arm", "atom-control", "\"JSON_TO_WALK\", {fieldPath = \"\", actual = \"Error\"}, \"-\", nil, \"Error\"", "true|Error", "value.a"));
    }

    // =========================================================================
    // 11f. The field/parameter binding fails closed (W1's same-token rule)
    // =========================================================================

    /**
     * W1's same-token rule (the declared parameter-source contract): every
     * message parameter whose declared source carries a tuple field's own
     * value must render the identical token as that field. A
     * closed-but-contradictory pair — two different closed tokens, one in
     * the message parameter and one in the field — is a fail-closed
     * producer defect on every tuple-producing renderer: the registry's
     * arm render, the JVM arm render (routed through the registry), and the
     * serialized Lua arm renderer. The matching pairs stay green as the
     * controls, and the independent parameter data (the walk's literal
     * {@code fieldPath}) stays untouched.
     */
    static void testFieldParameterBindingNegatives() throws Exception {
        System.out.println("-- the declared field/parameter binding fails closed --");
        for (FailureArmId arm : List.of(FailureArmId.CLASS_IDENTITY,
                FailureArmId.FUNCTION_SIGNATURE_MISMATCH)) {
            boolean identity = arm == FailureArmId.CLASS_IDENTITY;
            String expected = identity ? "@corpus/a/A" : "(number)->number";
            String actual = identity ? "@corpus/b/B" : "(number)->string";
            String contradictory = identity ? "@corpus/c/C" : "(string)->boolean";
            for (boolean expectedSide : List.of(true, false)) {
                expectDefect(() -> FailureContractRegistry.render(arm,
                    Map.of("expected", expected, "actual", actual),
                    expectedSide ? contradictory : expected,
                    expectedSide ? actual : contradictory, null),
                    arm + ": the contradictory " + (expectedSide ? "expected" : "actual")
                        + " operand fails closed");
            }
        }
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
            Map.of("fieldPath", "data.k", "actual", "function"), null, "number", null),
            "the walk arm's contradictory actual parameter and field fail closed");
        // The remaining bound families run through the same check: the
        // completion refinement, the async shape, and the no-value host
        // return.
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.ASYNC_COMPLETION_KIND,
            Map.of("kind", "number"), "number", "WRONG", null),
            "the completion refinement's contradictory actual fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.ASYNC_SHAPE,
            Map.of("actual", "number"), "async operation", "string", null),
            "the async-shape arm's contradictory actual fails closed");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.HOST_SYNC_RETURN_NOTHING, Map.of("expected", "number"),
            "string", "nothing", null),
            "the no-value host return's contradictory expected fails closed");
        // The independent parameter data stays untouched: the walk's literal
        // fieldPath (even a spelling that names a placeholder) never binds
        // to the actual field.
        checkEq("value at {actual} is not JSON serializable: function",
            FailureContractRegistry.render(FailureArmId.JSON_TO_WALK,
                Map.of("fieldPath", "{actual}", "actual", "function"), null,
                "function", null).message(),
            "the walk arm's fieldPath parameter stays independent of the bound "
                + "actual (the control)");
        // The serialized Lua arm renderer, under real luajit: the six
        // contradictory pairs fail closed and the matching pairs render.
        renderProbes("field-binding-probe",
            armProbe("walk-actual", "\"JSON_TO_WALK\", {fieldPath = \"data.k\", actual = \"function\"}, \"-\", nil, \"number\"", "false|"),
            armProbe("stdjson-actual", "\"JSON_STRINGIFY_UNSUPPORTED\", {actual = \"function\"}, \"-\", \"string, number, boolean, or table\", \"boolean\"", "false|"),
            armProbe("identity-expected", "\"CLASS_IDENTITY\", {expected = \"@corpus/a/A\", actual = \"@corpus/b/B\"}, \"-\", \"@corpus/c/C\", \"@corpus/b/B\"", "false|"),
            armProbe("identity-actual", "\"CLASS_IDENTITY\", {expected = \"@corpus/a/A\", actual = \"@corpus/b/B\"}, \"-\", \"@corpus/a/A\", \"@corpus/c/C\"", "false|"),
            armProbe("signature-expected", "\"FUNCTION_SIGNATURE_MISMATCH\", {expected = \"(number)->number\", actual = \"(number)->string\"}, \"-\", \"(string)->boolean\", \"(number)->string\"", "false|"),
            armProbe("signature-actual", "\"FUNCTION_SIGNATURE_MISMATCH\", {expected = \"(number)->number\", actual = \"(number)->string\"}, \"-\", \"(number)->number\", \"(number)->number\"", "false|"),
            new RenderProbe("arm", "walk-control", "\"JSON_TO_WALK\", {fieldPath = \"data.k\", actual = \"function\"}, \"-\", nil, \"function\"",
                "true|E8001|function|value at data.k is not JSON serializable: function", "value.code .. '|' .. value.a .. '|' .. value.m"),
            new RenderProbe("arm", "signature-control", "\"FUNCTION_SIGNATURE_MISMATCH\", {expected = \"(number)->number\", actual = \"(number)->string\"}, \"-\", \"(number)->number\", \"(number)->string\"",
                "true|E8010|function signature mismatch: expected (number)->number, got (number)->string", "value.code .. '|' .. value.m"));
    }

    // =========================================================================
    // 12c. The forbidden render scopes and the closed host inner reasons
    // =========================================================================

    /**
     * The row-addressed entry point enforces the arm's render scope (P1/P2
     * and Verification 2: a forbidden scope fails closed at every
     * tuple-producing entry), and every renderer validates a
     * {@code HOST_INNER_REASON} value against the closed inner-reason
     * vocabulary — the registry's two entries and its {@code fromRow}
     * delegate, the JVM runtime (through the registry), and the emitted Lua
     * prelude's {@code __arm}/{@code __renderTemplate}. The declared
     * pass-throughs (kind, refinement, range, string carriers, signature,
     * identity, element) stay green as the controls.
     */
    static void testForbiddenRenderScopesAndInnerReasons() throws Exception {
        System.out.println("-- the forbidden render scopes and the closed host inner "
            + "reasons --");
        FailurePolicyRow typeDescriptor =
            FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR);
        // The row-addressed INNER_ONLY bypass: the retained template position
        // of either host string-carrier arm is rejected exactly like the
        // arm-addressed render.
        expectDefect(() -> BoundaryFailure.fromRow(typeDescriptor, 3, null, null,
            new LinkedHashMap<>(), null),
            "the row-addressed render of the invalid-UTF-8 INNER_ONLY arm fails closed");
        expectDefect(() -> BoundaryFailure.fromRow(typeDescriptor, 4, null, null,
            new LinkedHashMap<>(), null),
            "the row-addressed render of the surrogate INNER_ONLY arm fails closed");
        expectDefect(() -> FailureContractRegistry.renderAtTemplate(
            FailurePolicyId.TYPE_DESCRIPTOR, 3, null, null, Map.of(), null),
            "the direct template-position render of an INNER_ONLY arm fails closed");
        // Controls: the inner render of both arms and a row-addressed
        // TOP_LEVEL render stay green.
        checkEq("expected string, got invalid UTF-8 encoding",
            FailureContractRegistry.renderInner(FailureArmId.HOST_STRING_INVALID_UTF8,
                Map.of()),
            "the inner render of the invalid-UTF-8 arm stays green (the control)");
        checkEq("expected string, got UTF-16 surrogate code point",
            FailureContractRegistry.renderInner(FailureArmId.HOST_STRING_SURROGATE,
                Map.of()),
            "the inner render of the surrogate arm stays green (the control)");
        checkEq("substring bounds must be finite integers", FailureContractRegistry.renderAtTemplate(
                FailurePolicyId.SUBSTRING_BOUNDS, 0, null, null, Map.of(), null).message(),
            "a row-addressed TOP_LEVEL render stays green (the control)");

        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "invented reason"), "number", "number", null),
            "the registry rejects an invented host inner reason");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.HOST_SYNC_RETURN_CELL,
            Map.of("inner", "expected string, got string with unpaired surrogate "
                + "code units"), "string", "string", null),
            "the registry rejects the JVM matcher's unpaired-surrogate sub-message");
        for (String[] values : List.of(
                new String[] {"expected boolean", "boolean", "number", "parameter"},
                new String[] {"expected string, got UTF-16 surrogate code point", "string", "string", "return"},
                new String[] {"function signature mismatch: expected (number)->number, got (string)->number", "?(number)->number", "table", "parameter"},
                new String[] {"expected instance of @corpus/a/A, got @corpus/b/B", "@corpus/a/A", "table", "parameter"},
                new String[] {"array element 2 type mismatch", "[number]", "table", "parameter"})) {
            boolean parameter = values[3].equals("parameter");
            checkEq((parameter ? "parameter 1" : "return value 1") + " type mismatch: " + values[0],
                FailureContractRegistry.render(parameter ? FailureArmId.HOST_PARAMETER_CELL
                    : FailureArmId.HOST_SYNC_RETURN_CELL, parameter
                    ? Map.of("index", "1", "inner", values[0]) : Map.of("inner", values[0]),
                    values[1], values[2], null).message(),
                "the declared inner reason passes through: " + values[0]);
        }
        checkEq("parameter 1 type mismatch: expected boolean",
            BoundaryFailure.fromRow(FailureContractRegistry.row(FailurePolicyId.HOST_PARAMETER),
                0, "boolean", "number", new LinkedHashMap<>(Map.of("index", "1",
                    "inner", "expected boolean")), null).message(),
            "the row-addressed host render stays green");
        checkEq("parameter 1 type mismatch: expected boolean",
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.HOST_PARAMETER_CELL,
                Map.of("index", "1", "inner", "expected boolean"), "-", "boolean", "number").msg,
            "the JVM host render stays green");

        // The emitted Lua prelude, under real luajit: the same negatives on
        // both the arm renderer and the raw template renderer.
        renderProbes("inner-reason-probe",
            innerProbe("arm", "invented", "invented reason", false),
            armProbe("suffixed", "\"HOST_SYNC_RETURN_CELL\", {inner = \"expected number, got string\"}, \"-\", \"string\", \"string\"", "false|"),
            innerProbe("template", "template-invented", "invented reason", false),
            armProbe("kind-control", "\"HOST_PARAMETER_CELL\", {index = \"1\", inner = \"expected boolean\"}, \"-\", \"boolean\", \"number\"",
                "true|parameter 1 type mismatch: expected boolean"),
            armProbe("signature-control", "\"HOST_PARAMETER_CELL\", {index = \"1\", inner = \"function signature mismatch: expected (number)->number, got (string)->number\"}, \"-\", \"?(number)->number\", \"table\"",
                "true|parameter 1 type mismatch: function signature mismatch: expected (number)->number, got (string)->number"),
            armProbe("number-control", "\"HOST_SYNC_RETURN_CELL\", {inner = \"expected number\"}, \"-\", \"number\", \"string\"",
                "true|return value 1 type mismatch: expected number"));
    }

    // =========================================================================
    // 12d. The nested descriptor vocabulary and the canonical class atoms
    // =========================================================================

    /**
     * The descriptor recognizer parses every nested position of both
     * spellings: a malformed parameter or result text — the canonical
     * composite's own parameters included — fails closed on every consumer,
     * while the nested canonical composite, the internal
     * {@code function(params;result)} spelling (the zero-parameter
     * {@code function(;int)} form included), and the canonical class atoms
     * stay green. The identical drives cover the registry's arm render and
     * its row-addressed {@code renderAtTemplate}/{@code fromRow} entries,
     * the JVM runtime arm render (through the registry), and the emitted Lua
     * prelude's {@code __arm}/{@code __renderTemplate}, with both signature
     * sides and the empty-final-segment identity negative covered.
     */
    static void testDescriptorAndClassAtomVocabularyDepth() throws Exception {
        System.out.println("-- the nested descriptor vocabulary and the class atoms --");
        for (String malformed : List.of("(bogus)->number", "function(bogus;number)",
                "function(number;bogus)", "(number,(bogus)->number)->number")) {
            for (boolean expectedSide : List.of(true, false)) {
                String inner = "function signature mismatch: expected "
                    + (expectedSide ? malformed : "(number)->number") + ", got "
                    + (expectedSide ? "(number)->number" : malformed);
                expectDefect(() -> FailureContractRegistry.render(
                    FailureArmId.HOST_PARAMETER_CELL, Map.of("index", "1", "inner", inner),
                    "number", "number", null),
                    "the registry rejects the malformed signature side: " + inner);
            }
        }
        String nested = "function signature mismatch: expected (()->number)->number, got (number)->number";
        checkEq("parameter 1 type mismatch: " + nested,
            FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
                Map.of("index", "1", "inner", nested), "number", "number", null).message(),
            "the nested canonical signature renders");
        checkEq("expected function", deal.codegen.jvm.JvmRuntime.kindReason(
                "function(;number)"),
            "the zero-parameter dialect descriptor projects its kind (the control)");
        checkEq("expected class instance", deal.codegen.jvm.JvmRuntime.kindReason(
                "nullable(@src/A)"),
            "the dialect nullable class atom projects the class kind (the control)");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.kindReason(
                "function(bogus;number)"),
            "the descriptor-kind reason rejects a malformed dialect parameter");

        for (String inner : List.of("expected instance of @src/, got @src/B",
                "expected instance of @src/A, got @src/")) {
            expectDefect(() -> FailureContractRegistry.render(
                FailureArmId.HOST_PARAMETER_CELL, Map.of("index", "1", "inner", inner),
                "@src/A", "table", null),
                "the registry rejects the empty identity segment: " + inner);
        }
        checkEq("parameter 1 type mismatch: " + nested,
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.HOST_PARAMETER_CELL,
                Map.of("index", "1", "inner", nested), "-", "number", "number").msg,
            "the JVM arm render keeps the nested canonical composite");

        // The emitted Lua prelude, under real luajit: the same negatives and
        // controls on the arm renderer and the raw template renderer.
        signatureProbes("descriptor-depth-probe", new String[][] {
            {"sig-malformed-expected", "(bogus)->number", "(number)->number", "false"},
            {"sig-malformed-actual", "(number)->number", "(bogus)->number", "false"},
            {"sig-dialect-malformed-param", "function(bogus;number)", "function(number;number)", "false"},
            {"sig-dialect-malformed-result", "function(number;number)", "function(number;bogus)", "false"},
            {"sig-nested-composite", "(()->number)->number", "(number)->number", "true"},
            {"sig-nested-params", "(number,(string)->number)->number", "(number)->number", "true"},
            {"sig-dialect", "function(number;number)", "function(number;number)", "true"},
            {"sig-dialect-zero", "function(;number)", "function(number;number)", "true"},
            {"sig-dialect-nested", "function(function(number;number);function(number;number))", "function(number;number)", "true"},
            {"sig-nested-actual", "(number)->number", "(()->number)->number", "true"},
            {"sig-dialect-zero-actual", "function(number;number)", "function(;number)", "true"}
        },
            innerProbe("arm", "identity-empty-expected", "expected instance of @src/, got @src/B", false),
            innerProbe("arm", "identity-empty-carried", "expected instance of @src/A, got @src/", false),
            innerProbe("arm", "identity-builtin", "expected instance of Error, got @src/B", true),
            innerProbe("arm", "identity-canonical", "expected instance of @src/A, got @src/B", true),
            innerProbe("template", "template-malformed", "function signature mismatch: expected function(bogus;number), got function(number;number)", false),
            innerProbe("template", "template-zero", "function signature mismatch: expected function(;number), got function(number;number)", true));
    }

    /**
     * The nullable-inner invariant (docs/spec-v1.5.md §Runtime type descriptor format;
     * {@code RuntimeDescriptor.Nullable}) holds in both descriptor
     * spellings: {@code ?null} / {@code ??inner} and the dialect
     * {@code nullable(null)} / {@code nullable(nullable(…))} fail closed at
     * every descriptor position — both signature sides, the nested
     * parameter/result positions, and the arm's own descriptor fields — on
     * the registry render, its row-addressed entries, the JVM arm render,
     * the descriptor-kind projection, and the emitted Lua prelude, while the
     * valid nullable forms ({@code ?int}, {@code [?int]},
     * {@code ?(int)->int}, {@code nullable(int)},
     * {@code nullable(array(nullable(int)))}) stay green.
     */
    static void testNullableInnerInvariant() throws Exception {
        System.out.println("-- the nullable-inner invariant (both descriptor "
            + "spellings) --");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "function signature mismatch: expected ?null, "
                + "got (number)->number"), "number", "number", null),
            "the registry rejects the nullable-inner violation");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "function signature mismatch: expected (number)->number, "
                + "got [?null]"), "number", "number", null),
            "the carried nested nullable-inner violation fails closed");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "function signature mismatch: expected (number)->number, "
                + "got nullable(null)"), "number", "number", null),
            "the carried dialect nullable-inner violation fails closed");
        // The arm's own descriptor fields admit no invalid nullable either.
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.HOST_PARAMETER_CELL, Map.of("index", "1", "inner",
                "expected number"), "?null", "number", null),
            "the descriptor field rejects the canonical ?null spelling");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.HOST_PARAMETER_CELL, Map.of("index", "1", "inner",
                "expected number"), "nullable(null)", "number", null),
            "the descriptor field rejects the dialect nullable(null) spelling");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.FUNCTION_SIGNATURE_MISMATCH, Map.of("expected", "??number",
                "actual", "(number)->number"), "??number", "(number)->number", null),
            "the declared-signature field rejects the ??number spelling");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.FUNCTION_SIGNATURE_MISMATCH, Map.of("expected", "(number)->number",
                "actual", "nullable(nullable(number))"), "(number)->number",
            "nullable(nullable(number))", null),
            "the carried-signature field rejects the dialect nullable(nullable(number)) "
                + "spelling");
        checkEq("parameter 1 type mismatch: function signature mismatch: expected "
                + "nullable(number), got (number)->number",
            BoundaryFailure.fromRow(FailureContractRegistry.row(FailurePolicyId.HOST_PARAMETER),
                0, "number", "number", new LinkedHashMap<>(Map.of("index", "1", "inner",
                    "function signature mismatch: expected nullable(number), got (number)->number")),
                null).message(),
            "the row-addressed nullable control renders");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.kindReason("nullable(null)"),
            "the descriptor-kind projection rejects the nullable(null) spelling");
        expectDefect(() -> deal.codegen.jvm.JvmRuntime.kindReason("??number"),
            "the descriptor-kind projection rejects the ??number spelling");
        checkEq("expected array",
            deal.codegen.jvm.JvmRuntime.kindReason("nullable(array(nullable(number)))"),
            "the nested dialect nullable projects its kind (the control)");
        checkEq("expected number", deal.codegen.jvm.JvmRuntime.kindReason("?number"),
            "the canonical nullable projects its inner kind (the control)");

        // The emitted Lua prelude, under real luajit: the same negatives and
        // controls on the arm renderer.
        signatureProbes("nullable-invariant-probe", new String[][] {
            {"canonical-null", "?null", "(number)->number", "false"},
            {"canonical-null-actual", "(number)->number", "?null", "false"},
            {"canonical-double", "??number", "(number)->number", "false"},
            {"canonical-array-null", "[?null]", "[number]", "false"},
            {"canonical-param-double", "(??number)->number", "(number)->number", "false"},
            {"dialect-null", "nullable(null)", "(number)->number", "false"},
            {"dialect-double", "nullable(nullable(number))", "(number)->number", "false"},
            {"dialect-array-null", "array(nullable(null))", "array(number)", "false"},
            {"dialect-fn-double", "function(nullable(nullable(number));number)", "function(number;number)", "false"},
            {"valid-number", "?number", "(number)->number", "true"},
            {"valid-array", "[?number]", "[number]", "true"},
            {"valid-nullable-fn", "?(number)->number", "(number)->number", "true"},
            {"valid-dialect-number", "nullable(number)", "(number)->number", "true"},
            {"valid-dialect-nested", "nullable(array(nullable(number)))", "array(number)", "true"}
        });
    }

    /**
     * One descriptor vocabulary on every consumer: a kind-only spelling
     * ({@code array} / {@code function}) is a kind <em>text</em>, never a
     * descriptor text, so it fails closed in the signature/descriptor
     * fields and the signature inner reasons of the registry render, the
     * row-addressed entries, the JVM runtime, and the emitted Lua prelude;
     * the approved kind reasons ({@code expected array}, {@code expected
     * function}) and the descriptor-kind projection keep their texts, so the
     * emitted host ABI's kind rows stay green while no consumer admits a
     * vocabulary the others reject.
     */
    static void testKindOnlySpellingVocabulary() throws Exception {
        System.out.println("-- the kind-only spelling boundary --");
        expectDefect(() -> FailureContractRegistry.render(FailureArmId.HOST_PARAMETER_CELL,
            Map.of("index", "1", "inner", "function signature mismatch: expected array, "
                + "got (number)->number"), "number", "number", null),
            "the registry rejects kind-only signature text");
        // The arm's own descriptor fields admit no kind-only spelling either.
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
            Map.of("expected", "array", "actual", "(number)->number"), "array",
            "(number)->number", null),
            "the declared-signature field rejects the bare array kind text");
        expectDefect(() -> FailureContractRegistry.render(
            FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
            Map.of("expected", "(number)->number", "actual", "function"), "(number)->number",
            "function", null),
            "the carried-signature field rejects the bare function kind text");
        checkEq("expected array", deal.codegen.jvm.JvmRuntime.kindReason("array"),
            "the descriptor-kind projection keeps the host ABI's bare array kind "
                + "text (the control)");
        checkEq("expected function",
            deal.codegen.jvm.JvmRuntime.kindReason("function"),
            "the descriptor-kind projection keeps the host ABI's bare function kind "
                + "text (the control)");
        checkEq("parameter 1 type mismatch: expected function",
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.HOST_PARAMETER_CELL,
                Map.of("index", "1", "inner", "expected function"), "-",
                "(number)->number", "number").msg,
            "the JVM arm render keeps the function kind reason (the control)");
        // The emitted Lua prelude, under real luajit.
        signatureProbes("kind-only-vocabulary-probe", new String[][] {
            {"sig-array", "array", "(number)->number", "false"},
            {"sig-function", "function", "(number)->number", "false"},
            {"sig-array-actual", "(number)->number", "array", "false"},
            {"sig-function-actual", "(number)->number", "function", "false"}
        },
            armProbe("kind-array", "\"HOST_PARAMETER_CELL\", {index = \"1\", inner = \"expected array\"}, \"-\", \"[number]\", \"number\"",
                "true|parameter 1 type mismatch: expected array"),
            armProbe("kind-function", "\"HOST_PARAMETER_CELL\", {index = \"1\", inner = \"expected function\"}, \"-\", \"(number)->number\", \"number\"",
                "true|parameter 1 type mismatch: expected function"),
            armProbe("kind-arm", "\"TYPED_BOUNDARY_KIND\", {kind = \"array\"}, \"-\", \"array\", \"string\"", "true|expected array"));
    }

    /**
     * The host inner-reason validator consumes the serialized arm templates
     * (P4): the emitted chunk holds no independent copy of an inner-reason
     * text, so replacing one declared template in the chunk's own
     * {@code __arms} table redirects which reasons the validator admits.
     * The probe mutates one template per family — the kind template with
     * its {@code {kind}} operand, a fixed refinement/range/string-carrier
     * template, and the signature/identity/element templates' literal
     * segments — and checks both directions (the text derived from the
     * replaced template is admitted, the former literal is rejected) plus
     * the renderer-side read of the same table. A validator holding a
     * copied literal would fail the first direction and keep passing the
     * second.
     */
    static void testHostInnerReasonTemplateAuthority() throws Exception {
        System.out.println("-- the host inner-reason template authority --");
        List<String> rows = runPreludeProbe("""
            local function hostInner(label, inner)
              local ok, value = pcall(__arm, "HOST_PARAMETER_CELL",
                {index = "1", inner = inner}, "-", "number", "number")
              print(label .. "|" .. tostring(ok) .. "|" .. (ok and value.m or ""))
            end
            __arms.TYPED_BOUNDARY_KIND.t = "kind-control {kind}"
            hostInner("kind-derived", "kind-control number")
            hostInner("kind-old", "expected number")
            __arms.TYPED_BOUNDARY_KIND.t = "expected {kind}"
            __arms.HOST_STRING_INVALID_UTF8.t = "range-control"
            print("range-render|" .. __renderTemplate("HOST_STRING_INVALID_UTF8", nil))
            hostInner("range-derived", "range-control")
            hostInner("range-old", "expected string, got invalid UTF-8 encoding")
            __arms.HOST_STRING_INVALID_UTF8.t = "expected string, got invalid UTF-8 encoding"
            __arms.HOST_STRING_INVALID_UTF8.t = "utf8-control"
            hostInner("utf8-derived", "utf8-control")
            hostInner("utf8-old", "expected string, got invalid UTF-8 encoding")
            __arms.HOST_STRING_INVALID_UTF8.t = "expected string, got invalid UTF-8 encoding"
            __arms.FUNCTION_SIGNATURE_MISMATCH.t = "sig-control [{expected}] => {actual}"
            hostInner("signature-derived", "sig-control [number] => (number)->number")
            hostInner("signature-old", "function signature mismatch: expected number, got (number)->number")
            __arms.FUNCTION_SIGNATURE_MISMATCH.t = "function signature mismatch: expected {expected}, got {actual}"
            __arms.CLASS_IDENTITY.t = "ident-control [{expected}] => {actual}"
            hostInner("identity-derived", "ident-control [@src/A] => @src/B")
            hostInner("identity-old", "expected instance of @src/A, got @src/B")
            __arms.CLASS_IDENTITY.t = "expected instance of {expected}, got {actual}"
            __arms.ARRAY_ELEMENT_KIND.t = "elem-control {oneBasedIndex}!"
            hostInner("element-derived", "elem-control 2!")
            hostInner("element-old", "array element 2 type mismatch")
            __arms.ARRAY_ELEMENT_KIND.t = "array element {oneBasedIndex} type mismatch"
            hostInner("restored", "expected string, got invalid UTF-8 encoding")
            """, "inner-template-authority-probe", 14);
        checkEq("kind-derived|true|parameter 1 type mismatch: kind-control number",
            rows.get(0),
            "the kind inner reason follows the serialized kind template");
        checkEq("kind-old|false|", rows.get(1),
            "the former kind literal is rejected once its template is replaced");
        checkEq("range-render|range-control", rows.get(2),
            "the renderer reads the replaced range template");
        checkEq("range-derived|true|parameter 1 type mismatch: range-control",
            rows.get(3),
            "the fixed inner reason follows the serialized range template");
        checkEq("range-old|false|", rows.get(4),
            "the former range literal is rejected once its template is replaced");
        checkEq("utf8-derived|true|parameter 1 type mismatch: utf8-control",
            rows.get(5),
            "the string-carrier inner reason follows the serialized template");
        checkEq("utf8-old|false|", rows.get(6),
            "the former invalid-UTF-8 literal is rejected once its template is "
                + "replaced");
        checkEq("signature-derived|true|parameter 1 type mismatch: sig-control "
                + "[number] => (number)->number", rows.get(7),
            "the signature inner reason follows the serialized template segments");
        checkEq("signature-old|false|", rows.get(8),
            "the former signature literal is rejected once its template is replaced");
        checkEq("identity-derived|true|parameter 1 type mismatch: ident-control "
                + "[@src/A] => @src/B", rows.get(9),
            "the identity inner reason follows the serialized template segments");
        checkEq("identity-old|false|", rows.get(10),
            "the former identity literal is rejected once its template is replaced");
        checkEq("element-derived|true|parameter 1 type mismatch: elem-control 2!",
            rows.get(11),
            "the element inner reason follows the serialized template segments");
        checkEq("element-old|false|", rows.get(12),
            "the former element literal is rejected once its template is replaced");
        checkEq("restored|true|parameter 1 type mismatch: expected string, got invalid UTF-8 encoding",
            rows.get(13),
            "the restored declared templates admit their own reasons again (the "
                + "control)");
    }

    /**
     * The host parameter/return cells with a placeholder-shaped descriptor
     * module component (ISSUE-0765): a declared function-typed position
     * whose parameter class lives in a module component carrying the literal
     * spelling of another arm placeholder ({@code {actual}}). The signature
     * inner reason is the signature arm's own single-pass render on every
     * consumer, so the inserted expected descriptor reaches the message
     * byte-for-byte and the closed inner-reason check admits it — a
     * consumer that composes the text with successive replacements rescans
     * the inserted component and fails closed (the oracle defect this drive
     * pins). The oracle boundary cell, the JVM runtime arm renderer, and the
     * emitted Lua prelude under {@code luajit} publish the identical tuple
     * for the same arm and values.
     */
    static void testHostSignaturePlaceholderParity() throws Exception {
        System.out.println("-- the host parameter/return cells: a placeholder-shaped "
            + "descriptor module component stays byte-for-byte in the signature "
            + "inner reason on every consumer --");
        String declared = "(@src/{actual}/C)->number";
        String carried = "(number)->number";
        RuntimeDescriptor descriptor = RuntimeDescriptor.parseCanonicalText(declared);
        RuntimeDescriptor.Func carriedFunction = (RuntimeDescriptor.Func)
            RuntimeDescriptor.parseCanonicalText(carried);
        String span = FailureContractRegistry.arm(FailureArmId.HOST_PARAMETER_CELL)
            .origin().name();
        // The oracle leg: the complete parameter/return tuples, with the
        // inserted expected module component published unchanged.
        Tuple oracleParameter = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_PARAMETER, descriptor,
            BoundaryValueView.ofFunction(carriedFunction), BoundaryContext.parameter(1)),
            "the placeholder-shaped host parameter cell"), span);
        checkEq(new Tuple("E8010", "parameter 1 type mismatch: function signature "
            + "mismatch: expected " + declared + ", got " + carried, span, declared,
            "table"), oracleParameter,
            "the oracle keeps the placeholder-shaped expected module component "
                + "byte-for-byte and publishes the host parameter tuple");
        Tuple oracleReturn = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.HOST_SYNC_RETURN, descriptor,
            BoundaryValueView.ofFunction(carriedFunction), BoundaryContext.none()),
            "the placeholder-shaped host return cell"), span);
        checkEq(new Tuple("E8010", "return value 1 type mismatch: function signature "
            + "mismatch: expected " + declared + ", got " + carried, span, declared,
            "table"), oracleReturn,
            "the oracle keeps the placeholder-shaped expected module component "
                + "byte-for-byte and publishes the host return tuple");
        // The JVM leg: the emitted host ABI renders the inner reason through
        // its FUNCTION_SIGNATURE_MISMATCH arm and the host cell through its own
        // arm (the emitted __hostInnerMessage passes the inner message).
        deal.codegen.jvm.JvmRuntime.DealError jvmParameter =
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.HOST_PARAMETER_CELL,
                Map.of("index", "1", "inner", deal.codegen.jvm.JvmRuntime.arm(
                    FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
                    Map.of("expected", declared, "actual", carried), span, declared,
                    carried).msg),
                span, declared, "table");
        checkEq(null, firstDifferingField(oracleParameter, new Tuple(
            jvmParameter.code, jvmParameter.msg, span, jvmParameter.expected,
            jvmParameter.actual)),
            "the JVM runtime renders the oracle's identical placeholder-shaped "
                + "host parameter tuple");
        deal.codegen.jvm.JvmRuntime.DealError jvmReturn =
            deal.codegen.jvm.JvmRuntime.arm(FailureArmId.HOST_SYNC_RETURN_CELL,
                Map.of("inner", deal.codegen.jvm.JvmRuntime.arm(
                    FailureArmId.FUNCTION_SIGNATURE_MISMATCH,
                    Map.of("expected", declared, "actual", carried), span, declared,
                    carried).msg),
                span, declared, "table");
        checkEq(null, firstDifferingField(oracleReturn, new Tuple(jvmReturn.code,
            jvmReturn.msg, span, jvmReturn.expected, jvmReturn.actual)),
            "the JVM runtime renders the oracle's identical placeholder-shaped "
                + "host return tuple");
        // The emitted Lua prelude leg, under real luajit.
        List<String> rows = runPreludeProbe("""
            local inner = __renderTemplate("FUNCTION_SIGNATURE_MISMATCH",
              {expected = "(@src/{actual}/C)->number", actual = "(number)->number"})
            print("inner-provided|" .. inner)
            local value = __arm("HOST_PARAMETER_CELL",
              {index = "1", inner = inner}, "-", "(@src/{actual}/C)->number", "table")
            print("param|" .. value.code .. "|" .. value.e .. "|" .. value.a
              .. "|" .. value.m)
            value = __arm("HOST_SYNC_RETURN_CELL", {inner = inner}, "-",
              "(@src/{actual}/C)->number", "table")
            print("return|" .. value.code .. "|" .. value.e .. "|" .. value.a
              .. "|" .. value.m)
            """, "host-signature-placeholder-probe", 3);
        checkEq("inner-provided|function signature mismatch: expected " + declared
            + ", got " + carried, rows.get(0),
            "the prelude's single-pass render keeps the placeholder-shaped component "
                + "byte-for-byte");
        String[] parts = rows.get(1).split("\\|", -1);
        checkEq(5, parts.length, "the prelude frames the parameter row as "
            + "label|code|expected|actual|message: " + rows.get(1));
        if (parts.length == 5) {
            checkEq(null, firstDifferingField(oracleParameter, new Tuple(parts[1],
                parts[4], span, parts[2], parts[3])),
                "the Lua prelude renders the oracle's identical placeholder-shaped "
                    + "host parameter tuple");
        }
        parts = rows.get(2).split("\\|", -1);
        checkEq(5, parts.length, "the prelude frames the return row as "
            + "label|code|expected|actual|message: " + rows.get(2));
        if (parts.length == 5) {
            checkEq(null, firstDifferingField(oracleReturn, new Tuple(parts[1],
                parts[4], span, parts[2], parts[3])),
                "the Lua prelude renders the oracle's identical placeholder-shaped "
                    + "host return tuple");
        }
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
        checkEq("expected number", deal.codegen.jvm.JvmRuntime.kindReason("number"),
            "a primitive descriptor projects its own kind text");
        checkEq("expected array", deal.codegen.jvm.JvmRuntime.kindReason("[number]"),
            "the canonical array descriptor projects the array kind text");
        checkEq("expected function",
            deal.codegen.jvm.JvmRuntime.kindReason("(number)->number"),
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
            local ok = pcall(__arm, "SUBSTRING_BOUNDS", {extra = "x"}, "-", nil, nil)
            print("extra-parameter|" .. tostring(ok))
            ok = pcall(__arm, "TYPED_BOUNDARY_KIND", {}, "-", "number", "string")
            print("missing-parameter|" .. tostring(ok))
            ok = pcall(__renderTemplate, "JSON_TO_WALK",
              {fieldPath = "a", actual = "table", extra = "x"})
            print("walk-extra-parameter|" .. tostring(ok))
            ok = pcall(__renderTemplate, "HOST_STRING_INVALID_UTF8", {})
            print("parameterless-control|" .. tostring(ok))
            local value = __arm("SUBSTRING_BOUNDS", nil, "-", nil, nil)
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
        checkEq("fieldless-control|E8001|substring bounds must be finite integers", rows.get(4),
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
                deal.semantic.ir.ConstructionProvider.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("age",
                RuntimeDescriptor.Number.INSTANCE, true,
                deal.semantic.ir.ConstructionProvider.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("ratio",
                RuntimeDescriptor.Number.INSTANCE, true,
                deal.semantic.ir.ConstructionProvider.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("data",
                RuntimeDescriptor.Table.INSTANCE, true,
                deal.semantic.ir.ConstructionProvider.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("tags",
                new RuntimeDescriptor.Array(RuntimeDescriptor.Number.INSTANCE), true,
                deal.semantic.ir.ConstructionProvider.LOCAL),
            // The nullable optional field of the present-null admission
            // (testPresentNullAdmission): its presence pin is the exact
            // trigger of the walk's admission check.
            new deal.semantic.ir.ClassLayout.FieldLayout("note",
                new RuntimeDescriptor.Nullable(RuntimeDescriptor.String.INSTANCE), false,
                deal.semantic.ir.ConstructionProvider.LOCAL),
            // The declared-array container-identity routes (the
            // declared-array self re-entry and the mixed
            // declared-array/table re-entry of walkCases()): both are
            // optional so every drive case that does not place them keeps
            // its landed shape and text.
            new deal.semantic.ir.ClassLayout.FieldLayout("grid",
                new RuntimeDescriptor.Array(new RuntimeDescriptor.Array(
                    RuntimeDescriptor.Table.INSTANCE)), false,
                deal.semantic.ir.ConstructionProvider.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("matrix",
                new RuntimeDescriptor.Array(RuntimeDescriptor.Table.INSTANCE), false,
                deal.semantic.ir.ConstructionProvider.LOCAL)));
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
        cases.add(walkCase("wrong-kind-number-field", "JSON_TO_WALK", "age", "boolean",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Bool(true),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "true", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", true, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A nonfinite declared number-array element encodes as null.
        cases.add(walkCase("array-element-nonfinite", "JSON_TO_WALK", "tags[1]", "number",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(),
                "tags", walkIntThenNonfiniteArray()),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 2, [1] = 1, [2] = math.huge}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                jvmNumberArray(1L, Double.POSITIVE_INFINITY))));
        // A nonfinite declared number field encodes as null.
        cases.add(walkCase("nonfinite-number-field", "JSON_TO_WALK", "ratio", "number",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(Double.NaN),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0/0",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", Double.NaN, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A table value at a string-typed declared field: its closed kind.
        cases.add(walkCase("wrong-kind-at-string-field", "JSON_TO_WALK", "name", "table",
            Map.of("name", walkEmptyTable(), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", LUA_TABLE_CARRIER, "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", new deal.codegen.jvm.JvmRuntime.Table(), "age", 1L,
                "ratio", 0.5, "data", new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                jvmIntArray(1L))));
        // An absent required value: the typed-boundary undefined token.
        cases.add(walkCase("missing-required-field", "JSON_TO_WALK", "age", "undefined",
            Map.of("name", walkString("n"), "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A present null on a non-nullable field: the null token.
        cases.add(walkCase("present-null-non-nullable", "JSON_TO_WALK", "name", "null",
            Map.of("name", ClassOpsExecutor.Value.Null.INSTANCE,
                "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", "__NULL", "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            walkNullField("name")));
        // An invalid Unicode scalar sequence: invalid-unicode.
        cases.add(walkCase("invalid-scalar-string", "JSON_TO_WALK", "name",
            "invalid-unicode",
            Map.of("name", walkString("\uD800"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", "\"\\237\\160\\128\"", "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "\uD800", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // A host-ABI function wrapper ({[__dealCarrierKeys.kind] = "function"}: the emitted
        // export-surface entry and host-crossing carrier shape) at a failing
        // declared field: the closed function token on every consumer, never
        // the table spelling.
        cases.add(walkCase("function-wrapper-at-string-field", "JSON_TO_WALK",
            "name", "function",
            Map.of("name", walkFunction(), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", "{[__dealCarrierKeys.kind] = \"function\", sig = \"()->number\", "
                    + "f = function() end}",
                "age", "1", "ratio", "0.5", "data", LUA_TABLE_CARRIER,
                "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", walkJvmFunction(), "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        // The remaining declared-descriptor kind mismatches (V2's
        // per-descriptor set): each position fails with the failing value's
        // own closed kind on every consumer.
        cases.add(walkCase("boolean-at-number-field", "JSON_TO_WALK", "age", "boolean",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Bool(true),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "true", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", Boolean.TRUE, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        cases.add(walkCase("string-at-number-field", "JSON_TO_WALK", "ratio", "string",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", walkString("x"),
                "data", walkEmptyTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", luaString("x"),
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", "x", "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L))));
        cases.add(walkCase("string-at-table-field", "JSON_TO_WALK", "data", "string",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkString("x"), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", luaString("x"), "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data", "x", "tags",
                jvmIntArray(1L))));
        cases.add(walkCase("table-at-array-field", "JSON_TO_WALK", "tags", "table",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkEmptyTable()),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", LUA_TABLE_CARRIER),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags",
                new deal.codegen.jvm.JvmRuntime.Table())));
        cases.add(walkCase("number-at-nullable-string-field", "JSON_TO_WALK", "note", "number",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1),
                "note", new ClassOpsExecutor.Value.Number(5)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}",
                "note", "5"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L),
                "note", 5L)));
        // A table-content carrier (an unsupported function value inside the
        // table-typed field): the closed function token at its pinned path.
        cases.add(walkCase("invalid-string-in-table-content", "JSON_TO_WALK", "data.k",
            "invalid-unicode",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkFunctionTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "{[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {k = true}, k = \"\\237\\160\\128\"}",
                "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                jvmFunctionTable(), "tags", jvmIntArray(1L))));
        // A table-content class instance: the carried canonical class atom at
        // its pinned path, never the class: IR/trace spelling.
        cases.add(walkCase("invalid-unicode-nested-content", "JSON_TO_WALK", "data.k",
            "invalid-unicode",
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkClassTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "{[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {k = true}, k = \"\\237\\160\\128\"}",
                "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                jvmClassTable(), "tags", jvmIntArray(1L))));
        // The builtin Error carrier at the walk root: the root-identity
        // failure publishes the value's carried canonical class atom
        // (Error — ClassId.ERROR.text()), never the superseded
        // class:@builtin/Error or v1.2 @/Error spellings.
        cases.add(new WalkCase("error-carrier-at-root", "JSON_TO_WALK", "", "Error",
            new ClassOpsExecutor.Value.Class(deal.semantic.ir.ClassId.ERROR,
                List.of(new ClassOpsExecutor.FieldState.Present(walkString("E8001")),
                    new ClassOpsExecutor.FieldState.Present(walkString("x")))),
            "{[__dealCarrierKeys.d] = true, code = \"E8001\", m = \"x\"}",
            new deal.codegen.jvm.JvmRuntime.ErrorValue("E8001", "x")));
        // A path-local table re-entry: the cycle arm, no expected/actual.
        cases.add(walkCase("cyclic-table-field", "JSON_TO_WALK_CYCLE", null, null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkCyclicTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "cyc", "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data", jvmCyclicTable(),
                "tags", jvmIntArray(1L))));
        // The array container needle: the table subtree re-enters the array
        // it already entered (data -> array -> table -> the same array), so
        // the cycle is detected at the array container and the cycle arm is
        // selected — the second cycle needle site, on every consumer.
        cases.add(walkCase("cyclic-array-container", "JSON_TO_WALK_CYCLE", null, null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkCyclicArrayViaTables(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "arrCycTable", "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmCyclicArrayViaTables(), "tags", jvmIntArray(1L))));
        // The declared-array container needle: the grid field's declared
        // array (array(array(table))) holds itself as its own first element,
        // so the re-entry is detected at the declared-array container on
        // every consumer. Without the declared-array needle the oracle
        // descends the element descriptor and publishes a walk failure at
        // the deeper table-descriptor position instead of the cycle arm.
        cases.add(walkCase("declared-array-self-reentry", "JSON_TO_WALK_CYCLE", null,
            null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1),
                "grid", walkSelfReferentialArray()),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}",
                "grid", "gridSelf"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L),
                "grid", jvmSelfReferentialArray())));
        // The mixed declared-array/table route: the matrix field's declared
        // array (array(table)) holds one table and that table holds the same
        // array again, so the declared-array container is re-entered from
        // inside the table-subtree seam — the seam continues the class
        // walk's path-local identity set, so the cycle is selected at that
        // re-entry on every consumer.
        cases.add(walkCase("declared-array-table-mixed-reentry", "JSON_TO_WALK_CYCLE",
            null, null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkEmptyTable(), "tags", walkIntArray(1),
                "matrix", walkTableHoldingItsOwnArray()),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}",
                "matrix", "mixArr"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L),
                "matrix", jvmTableHoldingItsOwnArray())));
        // A selected noncycle failure with a path-local table re-entry
        // behind it: the table-typed field holds the unsupported function
        // under 'bad' before its own re-entry under 'self', so the first
        // failure precedes the cycle. Every consumer must render the walk
        // arm at the earlier position (the oracle's adapter returns the
        // selected failure before the E8 seam is ever consulted, so the
        // cycle behind it is never traversed).
        cases.add(walkCase("omitted-function-before-table-cycle", "JSON_TO_WALK_CYCLE", null,
            null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkFunctionThenCyclicTable(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "fnCycTable", "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmFunctionThenCyclicTable(), "tags", jvmIntArray(1L))));
        // The same shape with the cycle behind the failure closed through
        // a mixed table/array path: 'mix' holds an array whose element is
        // the containing table again, so the re-entry needle is the table
        // entered before the array.
        cases.add(walkCase("omitted-function-before-mixed-cycle", "JSON_TO_WALK_CYCLE", null,
            null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkFunctionThenMixedCycle(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "fnMixTable", "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmFunctionThenMixedCycle(), "tags", jvmIntArray(1L))));
        // The cycle-first control over the same graph shape: the re-entry
        // sits under 'self' before the unsupported value under 'zbad', so
        // the needle's own closed marker still selects the cycle arm (the
        // selection is the walk's first failure, never the presence of an
        // unsupported value anywhere in the subtree).
        cases.add(walkCase("cycle-before-unsupported-value", "JSON_TO_WALK_CYCLE", null,
            null,
            Map.of("name", walkString("n"), "age", new ClassOpsExecutor.Value.Number(1),
                "ratio", new ClassOpsExecutor.Value.Number(0.5),
                "data", walkCyclicTableThenFunction(), "tags", walkIntArray(1)),
            Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", "cycFirstTable", "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
            Map.of("name", "n", "age", 1L, "ratio", 0.5,
                "data", jvmCyclicTableThenFunction(), "tags", jvmIntArray(1L))));
        // A table-content carrier under a key containing braces or the
        // literal spelling of a placeholder: docs/spec-v1.5.md
        // §Writing table fields permits arbitrary string keys, so the walk's fieldPath carries the key
        // verbatim and the arm's parameter substitution must publish it
        // byte-for-byte on every consumer — never a producer defect, never
        // a placeholder reinterpretation.
        for (String key : List.of("{bad}", "{actual}", "{fieldPath}", "a}b", "a{b")) {
            cases.add(walkCase("brace-key-" + key, "JSON_TO_WALK",
                "data." + key, "invalid-unicode",
                Map.of("name", walkString("n"),
                    "age", new ClassOpsExecutor.Value.Number(1),
                    "ratio", new ClassOpsExecutor.Value.Number(0.5),
                    "data", walkFunctionTable(key), "tags", walkIntArray(1)),
                Map.of("name", luaString("n"), "age", "1", "ratio", "0.5",
                    "data", luaFunctionTable(key),
                    "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}"),
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
        String lua = emittedWalkChunk();
        check(lua.contains("__eT = __arm(__jarmT, __jcparT, __callOrigin(\""
                + WALK_SPAN + "\"), nil, __jfactT)"),
            "the production Lua walk call site renders its selected arm at the call origin");
        check(!lua.contains("__failExpr(\"E8001\", __renderTemplate(\"JSON_TO_WALK\""),
            "the production Lua walk call site composes no independent message");
        String jvm = deal.codegen.jvm.JvmSemanticEmitter.emitProductionProject(
            walkProject(), walkTables(), Map.of(new deal.semantic.ir.ModuleId("arm-walk"),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of())),
            "ArmWalk", emptySurface()).source();
        check(jvm.contains("JvmRuntime.arm(deal.semantic.ir.FailureArmId.JSON_TO_WALK, "
                + "java.util.Map.of(\"fieldPath\", projection.fieldPath, \"actual\", "
                + "projection.actual), JvmRuntime.callOrigin(\"" + WALK_SPAN
                + "\"), null, projection.actual)"),
            "the production JVM walk catch site binds the projection and call origin");
        check(jvm.contains("JvmRuntime.arm(deal.semantic.ir.FailureArmId.JSON_TO_WALK_CYCLE, "
                + "java.util.Map.of(), JvmRuntime.callOrigin(\"" + WALK_SPAN
                + "\"), null, null)"),
            "the production JVM cycle catch site binds no parameters or tuple fields");
        check(!jvm.contains("value at \" + projection.fieldPath"),
            "the production JVM walk catch site composes no independent message");

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
            String encoded = switch (drive.label()) {
                case "array-element-nonfinite" ->
                    "{\"name\":\"n\",\"age\":1,\"ratio\":0.5,\"data\":{},\"tags\":[1,null]}";
                case "nonfinite-number-field" ->
                    "{\"name\":\"n\",\"age\":1,\"ratio\":null,\"data\":{},\"tags\":[1]}";
                default -> null;
            };
            if (encoded != null) {
                String oracleText = outcome instanceof ClassOpsExecutor.Outcome.Success<
                        ClassOpsExecutor.Value> success
                        && success.value() instanceof ClassOpsExecutor.Value.String text
                        && text.scalar() instanceof deal.semantic.ir.UnicodeScalars.Valid valid
                    ? valid.carrier() : "unexpected failure";
                checkEq(jsonStructure(encoded), jsonStructure(oracleText),
                    drive.label() + ": Oracle declared-number JSON encoding");
                String prefix = drive.label() + "|OK|";
                check(luaRows.get(i).startsWith(prefix),
                    drive.label() + ": real Lua completed encoding");
                checkEq(jsonStructure(encoded), jsonStructure(luaRows.get(i).substring(prefix.length())),
                    drive.label() + ": real Lua declared-number JSON encoding");
                checkEq(jsonStructure(encoded), jsonStructure(
                    deal.codegen.jvm.JvmJson.toClass(plan, drive.jvmRoot())),
                    drive.label() + ": JVM declared-number JSON encoding");
                continue;
            }
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

    }

    // =========================================================================
    // 14a. The declared-array needle's shared-reference control
    // =========================================================================

    /**
     * The declared-array container-identity discipline's shared-reference
     * control (the remediation's repeated noncyclic array-reference case):
     * one inner array value placed at two sibling elements of one declared
     * array field is admitted on every consumer — the needle's fact is a
     * re-entry on the current path, so the identity is entered and removed
     * around each array's own subtree, never kept globally — and the oracle,
     * the emitted Lua walk under real luajit, and the emitted JVM walk emit
     * the identical text. The cycle routes themselves are the walkCases()
     * drive's declared-array-self-reentry and declared-array-table-mixed-reentry
     * rows (E8001, the exact cycle message, the supplied origin, absent
     * expected/actual, and empty cycle metadata on all three consumers).
     */
    static void testDeclaredArraySharedReferenceControl() throws Exception {
        System.out.println("-- the declared-array needle's shared-reference control --");
        String expectedText = "{\"name\":\"n\",\"age\":1.0,\"ratio\":0.5,"
            + "\"data\":{},\"tags\":[1.0],\"grid\":[[],[]]}";

        // The oracle leg: the production adapter's class walk with the
        // executing op's own origin.
        deal.semantic.ir.SemanticOp op = walkJsonOp();
        Map<String, ClassOpsExecutor.Value> oracleFields = new LinkedHashMap<>();
        oracleFields.put("name", walkString("n"));
        oracleFields.put("age", new ClassOpsExecutor.Value.Number(1));
        oracleFields.put("ratio", new ClassOpsExecutor.Value.Number(0.5));
        oracleFields.put("data", walkEmptyTable());
        oracleFields.put("tags", walkIntArray(1));
        oracleFields.put("grid", walkRepeatedInnerArray());
        ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> outcome =
            ClassOpsExecutor.executeJsonToClass(op,
                Map.of(((KindPayload.JsonToClassPayload) op.payload()).classValue(),
                    walkOracleInstance(oracleFields)),
                Map.of(WALK_ID, walkLayout()),
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), op.origin());
        check(outcome instanceof ClassOpsExecutor.Outcome.Success<
                ClassOpsExecutor.Value> success
                && success.value() instanceof ClassOpsExecutor.Value.String text
                && text.scalar() instanceof deal.semantic.ir.UnicodeScalars.Valid valid
                && valid.carrier().equals(expectedText),
            "the oracle's declared-array walk admits the shared noncyclic inner array "
                + "at both sibling elements and serializes " + expectedText
                + "; got " + outcome);

        // The emitted Lua leg (the real luajit lane).
        String body = "local sharedInner = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 0}\n"
            + "local root = " + walkLuaRoot(Map.of(
                "name", luaString("n"), "age", "1", "ratio", "0.5",
                "data", LUA_TABLE_CARRIER, "tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}",
                "grid", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 2, sharedInner, sharedInner}")) + "\n"
            + "local ok, text = __jsonToClassOp(" + quote(WALK_ID.text()) + ", root)\n"
            + "if ok then print(\"shared-array-ref|OK|\" .. text)\n"
            + "else print(\"shared-array-ref|FAIL|\") end\n";
        List<String> rows = runProbe(emittedWalkChunk(), "arm-walk", body,
            "shared-array-ref", 1);
        checkEq("shared-array-ref|OK|" + expectedText, rows.get(0),
            "the emitted Lua walk admits the shared noncyclic inner array at both "
                + "sibling elements and serializes " + expectedText);

        // The emitted JVM leg (the shared walk machinery the artifact links).
        String jvmText = deal.codegen.jvm.JvmJson.toClass(walkPlan(),
            walkJvmInstance(Map.of(
                "name", "n", "age", 1L, "ratio", 0.5, "data",
                new deal.codegen.jvm.JvmRuntime.Table(), "tags", jvmIntArray(1L),
                "grid", jvmRepeatedInnerArray())));
        checkEq(expectedText, jvmText,
            "the JVM walk admits the shared noncyclic inner array at both sibling "
                + "elements and serializes " + expectedText);
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
     * The present-null admission of the emitted Lua walk (ISSUE-0698's
     * production realization of the @jsonable family): the walk normalizes
     * the carrier's {@code __NULL} sentinel to the language null and the
     * declared descriptor decides — a nullable position admits it and
     * serializes JSON null (the corpus fixtures' pinned three-state
     * roundtrip: {@code jsonable-optional-nullable} and the sibling
     * roundtrip fixtures execute runtime-ok on both real toolchains), while
     * a non-nullable position keeps the closed typed-boundary "null"
     * projection (the walk-cases drive's {@code present-null-non-nullable}
     * row). The oracle and the JVM walk publish the same JSON null at this
     * position, so the three consumers agree on the corrected admission.
     */
    static void testPresentNullAdmission() throws Exception {
        System.out.println("-- the present-null admission of the emitted walk --");
        Map<String, ClassOpsExecutor.Value> oracleFields = new LinkedHashMap<>();
        oracleFields.put("name", walkString("n"));
        oracleFields.put("age", new ClassOpsExecutor.Value.Number(1));
        oracleFields.put("ratio", new ClassOpsExecutor.Value.Number(0.5));
        oracleFields.put("data", walkEmptyTable());
        oracleFields.put("tags", walkIntArray(1));
        oracleFields.put("note", ClassOpsExecutor.Value.Null.INSTANCE);
        Map<String, String> luaFields = new LinkedHashMap<>();
        luaFields.put("name", luaString("n"));
        luaFields.put("age", "1");
        luaFields.put("ratio", "0.5");
        luaFields.put("data", LUA_TABLE_CARRIER);
        luaFields.put("tags", "{[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}");
        luaFields.put("note", "__NULL");
        Map<String, Object> jvmFields = new LinkedHashMap<>();
        jvmFields.put("name", "n");
        jvmFields.put("age", 1L);
        jvmFields.put("ratio", 0.5);
        jvmFields.put("data", new deal.codegen.jvm.JvmRuntime.Table());
        jvmFields.put("tags", jvmIntArray(1L));
        jvmFields.put("note", null);
        String expectedText = "{\"name\":\"n\",\"age\":1.0,\"ratio\":0.5,"
            + "\"data\":{},\"tags\":[1.0],\"note\":null}";

        // The emitted Lua leg under real luajit: the walk admits the
        // present null on the nullable field and serializes JSON null.
        String body = "local root = " + walkLuaRoot(luaFields) + "\n"
            + "local ok, text = __jsonToClassOp(\"" + WALK_ID.text() + "\", root)\n"
            + "if ok then print(\"present-null-nullable|OK|\" .. text)\n"
            + "else print(\"present-null-nullable|FAIL|\") end\n";
        List<String> rows = runProbe(emittedWalkChunk(), "arm-walk", body,
            "present-null-nullable", 1);
        checkEq("present-null-nullable|OK|" + expectedText, rows.get(0),
            "the emitted Lua walk admits the present null on the nullable field and "
                + "serializes JSON null");

        // The oracle and the JVM walk publish the same JSON null at this
        // position (the three-consumer agreement on the corrected admission).
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
     * Declared numeric JSON encoding and a separate wrong-kind element:
     * nonfinite numbers encode as null, while boolean in a number array
     * retains the path-specific failure at the supplied call origin. The
     * three-consumer drive independently covers nonfinite fields/elements.
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
        ClassOpsExecutor.Outcome<ClassOpsExecutor.Value> nonfiniteOutcome =
            ClassOpsExecutor.executeJsonToClass(op,
                Map.of(((deal.semantic.ir.KindPayload.JsonToClassPayload) op.payload())
                    .classValue(), walkOracleInstance(op, nonfiniteIntField)), layouts,
                deal.semantic.JsonClassAlgorithmAdapter.stringifier(), callOrigin);
        check(nonfiniteOutcome instanceof ClassOpsExecutor.Outcome.Success<
                ClassOpsExecutor.Value> success
                && success.value() instanceof ClassOpsExecutor.Value.String text
                && text.scalar() instanceof deal.semantic.ir.UnicodeScalars.Valid valid
                && valid.carrier().equals("{\"name\":\"n\",\"age\":null,\"ratio\":0.5,\"data\":{},\"tags\":[1.0]}"),
            "nonfinite declared number encodes as null; got " + nonfiniteOutcome);

        Map<String, ClassOpsExecutor.Value> fractionalElement = new LinkedHashMap<>();
        fractionalElement.put("name", walkString("n"));
        fractionalElement.put("age", new ClassOpsExecutor.Value.Number(1));
        fractionalElement.put("ratio", new ClassOpsExecutor.Value.Number(0.5));
        fractionalElement.put("data", walkEmptyTable());
        fractionalElement.put("tags", new ClassOpsExecutor.Value.Array(
            deal.semantic.ir.SemanticArray.of(
                List.of(new ClassOpsExecutor.Value.Bool(true)))));
        checkWalkOracleTuple(op, layouts, callOrigin, callOriginText,
            "wrong-kind-array-number-element", fractionalElement, "tags[0]", "boolean");
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
                deal.semantic.ir.ConstructionProvider.LOCAL),
            new deal.semantic.ir.ClassLayout.FieldLayout("flag",
                RuntimeDescriptor.Boolean.INSTANCE, false,
                deal.semantic.ir.ConstructionProvider.LOCAL)));
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
            "number-at-boolean-descriptor", kindFields("flag",
                new ClassOpsExecutor.Value.Number(7)), "flag", "number");
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
        return "{[__dealCarrierKeys.c] = true, [__dealCarrierKeys.id] = " + quote(WALK_ID.text()) + ", [__dealCarrierKeys.p] = {" + present
            + "}, [__dealCarrierKeys.f] = {" + values + "}}";
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
            elements.add(new ClassOpsExecutor.Value.Number(value));
        }
        return new ClassOpsExecutor.Value.Array(
            deal.semantic.ir.SemanticArray.of(elements));
    }

    /** The drive's array(int) value with an integral first element and a nonfinite second. */
    private static ClassOpsExecutor.Value walkIntThenNonfiniteArray() {
        return new ClassOpsExecutor.Value.Array(deal.semantic.ir.SemanticArray.of(
            List.of(new ClassOpsExecutor.Value.Number(1),
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
        table.put(key, walkString("\uD800"));
        return new ClassOpsExecutor.Value.Table(table);
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmFunctionTable() {
        return jvmFunctionTable("k");
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmFunctionTable(String key) {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write(key, "\uD800");
        return table;
    }

    /**
     * The emitted Lua carrier of a table holding one function under
     * {@code key} (the walk's own key-presence shape): the key is a quoted
     * string literal, so a key containing braces or a placeholder spelling
     * reaches the walk verbatim.
     */
    private static String luaFunctionTable(String key) {
        return "{[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {[" + luaString(key) + "] = true}, ["
            + luaString(key) + "] = \"\\237\\160\\128\"}";
    }

    /** The drive's table-typed field holding one class instance under {@code k}. */
    private static ClassOpsExecutor.Value walkClassTable() {
        deal.semantic.ir.SemanticTable<ClassOpsExecutor.Value> table =
            new deal.semantic.ir.SemanticTable<>();
        table.put("k", walkString("\uD800"));
        return new ClassOpsExecutor.Value.Table(table);
    }

    private static deal.codegen.jvm.JvmRuntime.Table jvmClassTable() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        table.write("k", "\uD800");
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

    /**
     * The declared-array self re-entry of the drive's grid field: the
     * declared array holds itself as its own first element, so the re-entry
     * needle is the declared-array container itself. The live view carries
     * the array's current contents (the oracle's mutable array carrier), so
     * the element is exactly the container identity the walk entered.
     */
    private static ClassOpsExecutor.Value walkSelfReferentialArray() {
        List<ClassOpsExecutor.Value> elements = new ArrayList<>();
        SemanticArray<ClassOpsExecutor.Value> live = SemanticArray.live(
            elements::size, elements::get);
        ClassOpsExecutor.Value array = new ClassOpsExecutor.Value.Array(live);
        elements.add(array);
        return array;
    }

    /**
     * The mixed declared-array/table route of the drive's matrix field: the
     * declared array holds one table and that table holds the same array
     * again, so the walk re-enters the declared-array container from inside
     * the table-subtree seam.
     */
    private static ClassOpsExecutor.Value walkTableHoldingItsOwnArray() {
        SemanticTable<ClassOpsExecutor.Value> table = new SemanticTable<>();
        ClassOpsExecutor.Value tableView = new ClassOpsExecutor.Value.Table(table);
        List<ClassOpsExecutor.Value> elements = new ArrayList<>();
        SemanticArray<ClassOpsExecutor.Value> live = SemanticArray.live(
            elements::size, elements::get);
        ClassOpsExecutor.Value array = new ClassOpsExecutor.Value.Array(live);
        elements.add(tableView);
        table.put("a", array);
        return array;
    }

    /**
     * The shared-reference control of the declared-array needle: one inner
     * array value sits at both sibling elements of the grid field's declared
     * array, so the path-local needle must admit the repeated reference (the
     * needle's fact is re-entry on the current path, never repetition).
     */
    private static ClassOpsExecutor.Value walkRepeatedInnerArray() {
        ClassOpsExecutor.Value inner = new ClassOpsExecutor.Value.Array(
            SemanticArray.of(List.of()));
        return new ClassOpsExecutor.Value.Array(SemanticArray.of(List.of(inner, inner)));
    }

    private static deal.codegen.jvm.JvmRuntime.Array jvmSelfReferentialArray() {
        deal.codegen.jvm.JvmRuntime.Array array = new deal.codegen.jvm.JvmRuntime.Array(1);
        array.elements.add(array);
        return array;
    }

    private static deal.codegen.jvm.JvmRuntime.Array jvmTableHoldingItsOwnArray() {
        deal.codegen.jvm.JvmRuntime.Table table = new deal.codegen.jvm.JvmRuntime.Table();
        deal.codegen.jvm.JvmRuntime.Array array = new deal.codegen.jvm.JvmRuntime.Array(1);
        table.write("a", array);
        array.elements.add(table);
        return array;
    }

    private static deal.codegen.jvm.JvmRuntime.Array jvmRepeatedInnerArray() {
        deal.codegen.jvm.JvmRuntime.Array inner = new deal.codegen.jvm.JvmRuntime.Array(0);
        deal.codegen.jvm.JvmRuntime.Array outer = new deal.codegen.jvm.JvmRuntime.Array(2);
        outer.elements.add(inner);
        outer.elements.add(inner);
        return outer;
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

    private static final String LUA_TABLE_CARRIER = "{[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {}}";

    /** The JVM walk plan of the drive's layout. */
    private static deal.codegen.jvm.JvmJson.Plan walkPlan() {
        return new deal.codegen.jvm.JvmJson.Plan(WALK_ID.text(),
            new deal.codegen.jvm.JvmJson.Field[] {
                new deal.codegen.jvm.JvmJson.Field("name", "string", false, "string"),
                new deal.codegen.jvm.JvmJson.Field("age", "number", false, "number"),
                new deal.codegen.jvm.JvmJson.Field("ratio", "number", false, "number"),
                new deal.codegen.jvm.JvmJson.Field("data", "table", false, "table"),
                new deal.codegen.jvm.JvmJson.Field("tags", "array(number)", false, "number"),
                new deal.codegen.jvm.JvmJson.Field("note", "nullable:string", true,
                    "string"),
                new deal.codegen.jvm.JvmJson.Field("grid", "array(array(table))", true,
                    "table"),
                new deal.codegen.jvm.JvmJson.Field("matrix", "array(table)", true,
                    "table"),
            }, null);
    }

    /**
     * The emitted Lua probe's rows (one row per drive case): the walk's own
     * {@code __jsonToClassOp} result rendered through the prelude's arm
     * renderer at the op's origin.
     */
    private static Object jsonStructure(String text) {
        var parsed = deal.semantic.SharedStdlibSemantics.jsonParse(
            new deal.semantic.ir.SourceOrigin("probe.deal",
                deal.semantic.ir.SourceSpan.synthetic("probe.deal"),
                deal.semantic.ir.SourceOriginKind.SYNTHETIC,
                new deal.semantic.ir.AnchorId(1), null),
            new deal.semantic.ir.UnicodeScalars.Valid(text));
        if (!(parsed instanceof deal.semantic.SharedStdlibSemantics.Outcome.Success<
                deal.semantic.SharedStdlibSemantics.Value> success)) {
            throw new AssertionError("Invalid JSON result: " + text);
        }
        return jsonStructure(success.value());
    }

    private static Object jsonStructure(deal.semantic.SharedStdlibSemantics.Value value) {
        return switch (value) {
            case deal.semantic.SharedStdlibSemantics.Value.Number n -> Double.doubleToLongBits(n.value());
            case deal.semantic.SharedStdlibSemantics.Value.Table t -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (String key : t.table().keys()) {
                    var slot = t.table().get(key);
                    if (!(slot instanceof deal.semantic.ir.SemanticTable.Lookup.Present<
                            deal.semantic.SharedStdlibSemantics.Value> present)) {
                        throw new AssertionError("Parsed JSON key is absent");
                    }
                    fields.put(key, jsonStructure(present.value()));
                }
                yield fields;
            }
            case deal.semantic.SharedStdlibSemantics.Value.Array a ->
                a.elements().elements().stream().map(FailureArmAuthorityTest::jsonStructure).toList();
            default -> value;
        };
    }

    private static List<String> runWalkProbe(List<WalkCase> cases) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("local cyc = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {self = true}}\n");
        body.append("cyc[\"self\"] = cyc\n");
        // The array-container cycle: data -> array -> table -> the same array
        // (the needle is detected at the array container's re-entry, never at
        // the table's).
        body.append("local arrCycTable = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {a = true}}\n");
        body.append("local arrCycTblB = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {b = true}}\n");
        body.append("local arrCycArr = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, arrCycTblB}\n");
        body.append("arrCycTblB.b = arrCycArr\n");
        body.append("arrCycTable.a = arrCycArr\n");
        // The declared-array container needle: the grid field's declared
        // array (array(array(table))) holds itself as its own first element,
        // so the declared-array container is re-entered at the first
        // element.
        body.append("local gridSelf = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1}\n");
        body.append("gridSelf[1] = gridSelf\n");
        // The mixed declared-array/table route: the matrix field's declared
        // array (array(table)) holds the table and that table holds the same
        // array again.
        body.append("local mixArrTable = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {a = true}}\n");
        body.append("local mixArr = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, mixArrTable}\n");
        body.append("mixArrTable.a = mixArr\n");
        // A selected noncycle failure with a table cycle behind it: 'bad'
        // sorts before 'self', so both the ascending-key walk and the
        // first-insertion declaration order meet the unsupported value
        // first.
        body.append("local fnCycTable = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {bad = true, "
            + "self = true}}\n");
        body.append("fnCycTable.bad = function() end\n");
        body.append("fnCycTable.self = fnCycTable\n");
        // The same shape with the cycle behind the failure closed through a
        // mixed table/array path.
        body.append("local fnMixTable = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {bad = true, "
            + "mix = true}}\n");
        body.append("fnMixTable.bad = function() end\n");
        body.append("fnMixTable.mix = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, fnMixTable}\n");
        // The cycle-first control over the same graph shape.
        body.append("local cycFirstTable = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {self = true, "
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
        body.append("  if ok then print(c.label .. \"|OK|\" .. text) else\n");
        body.append("  local value = __arm(arm, params, \"")
            .append(WALK_SPAN).append("\", nil, actual)\n");
        body.append("  print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n");
        body.append("  end\nend\n");
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
                deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, entry, "hash",
                "context", java.util.Set.of(), Map.of(), Map.of(WALK_ID, walkLayout()),
                Map.of(), new deal.semantic.ir.ModuleInitPlan(List.of(), initBlock),
                new deal.semantic.ir.ExportPlan(List.of()), Map.of(), ops,
                new deal.semantic.ir.StructuredBodyTable(Map.of(), Map.of()),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                deal.semantic.ir.ClassBindingFacts.empty());
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
            deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, index, modules, entry,
            deal.semantic.ClassRegistrationSeeds.builtinErrorOnly());
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


    // =========================================================================
    // 15. The moved cell: a fractional array(int) element at the boundary
    // =========================================================================

    private static List<String> arrayBoundaryRows() throws Exception {
        if (arrayBoundaryRows == null) {
            List<String> rows = runPreludeProbe("""
                do
                  local ok, value = pcall(__bcheck, "array(number)", "array",
                    {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = true})
                  row("element", ok, value)
                end
                do
                  local ok, value = pcall(__bcheck, "array(number)", "array",
                    {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.da] = true, [__dealCarrierKeys.n] = 1, [1] = __NULL})
                  row("present-null", ok, value)
                  ok, value = pcall(__bcheck, "array(number)", "array",
                    {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.da] = true, [__dealCarrierKeys.n] = 1})
                  row("deleted-slot", ok, value)
                  ok, value = pcall(__bcheck, "array(null)", "array",
                    {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.da] = true, [__dealCarrierKeys.n] = 1, [1] = __NULL})
                  row("null-admission", ok, value)
                end
                """, "array-boundary-probe", 4);
            check(rows.size() == 4, "the array-boundary batch prints exactly four rows: "
                + rows);
            if (rows.size() != 4) {
                throw new IllegalStateException("the array-boundary batch is incomplete");
            }
            arrayBoundaryRows = List.copyOf(rows);
        }
        return arrayBoundaryRows;
    }

    static void testMovedElementCell() throws Exception {
        System.out.println("-- the moved cell: a fractional array(number) element --");
        String origin = "moved-cell.deal:3:12";
        RuntimeDescriptor intArray =
            new RuntimeDescriptor.Array(RuntimeDescriptor.Number.INSTANCE);
        Tuple oracle = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, intArray,
            BoundaryValueView.ofArray(BoundaryValueView.of(ActualKind.BOOLEAN)),
            BoundaryContext.none()), "the array(number) element cell"), origin);
        checkEq(new Tuple("E8003", "array element 1 type mismatch", origin, "number",
                "boolean"), oracle,
            "the oracle classifies the fractional array(number) element by its value");
        // The JVM leg (the shared array element arm the artifact links).
        deal.codegen.jvm.JvmRuntime.Array jvmArray =
            new deal.codegen.jvm.JvmRuntime.Array(1);
        jvmArray.elements.add(true);
        deal.codegen.jvm.JvmRuntime.DealError jvm = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("array(number)", "array", jvmArray));
        check(jvm != null, "the JVM array element arm fails the fractional element");
        if (jvm != null) {
            checkEq(null, firstDifferingField(oracle,
                new Tuple(jvm.code, jvm.msg, origin, jvm.expected, jvm.actual)),
                "the JVM array element arm renders the oracle's identical tuple");
        }
        // The emitted prelude leg (the moved cell), under real luajit.
        List<String> rows = arrayBoundaryRows().subList(0, 1);
        checkEq("element|E8003|number|boolean|array element 1 type mismatch", rows.get(0),
            "the emitted __bcheck array element arm classifies the element by its value");
        checkEq(null, firstDifferingField(oracle, luaTuple(rows.get(0), origin)),
            "the emitted prelude renders the oracle's identical element tuple");
        // The negative: a cell keying the token on the declared int text
        // publishes "int" and is reported by field name actual.
        checkEq("actual", firstDifferingField(oracle, new Tuple(oracle.code(),
                oracle.message(), oracle.span(), oracle.expected(), "number")),
            "a declared-text-keyed element arm is reported by field name actual");
    }

    /**
     * The std/json producer's present null versus the deleted slot
     * (ISSUE-0753): the JSON reader stores the null element unchanged, so
     * the array descriptor check projects the language-null carrier's own
     * {@code null} token on the oracle, the JVM runtime, and the emitted
     * Lua prelude alike, while only an absent/deleted slot projects
     * {@code nil}; the {@code array(null)} descriptor admits the present
     * null. The runtime-level renders keep the arm's own origin operand
     * (the emitting boundary site attaches the op origin), unchanged by
     * the projection.
     */
    static void testDecodedArrayPresentNullCell() throws Exception {
        System.out.println("-- the decoded-array present-null and deleted-slot "
            + "cells --");
        String origin = "decoded-null.deal:5:11";
        RuntimeDescriptor intArray =
            new RuntimeDescriptor.Array(RuntimeDescriptor.Number.INSTANCE);
        RuntimeDescriptor nullArray =
            new RuntimeDescriptor.Array(RuntimeDescriptor.Null.INSTANCE);

        // The oracle legs: the closed boundary views of the same carriers.
        Tuple oraclePresent = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, intArray,
            BoundaryValueView.ofArray(BoundaryValueView.nullView()),
            BoundaryContext.none()), "the oracle present-null cell"), origin);
        checkEq(new Tuple("E8003", "array element 1 type mismatch", origin, "number",
                "null"), oraclePresent,
            "the oracle projects the present null element as the null token");
        Tuple oracleDeleted = tupleOf(oracleFailure(BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, intArray,
            BoundaryValueView.ofArray(BoundaryValueView.of(ActualKind.MISSING)),
            BoundaryContext.none()), "the oracle deleted-slot cell"), origin);
        checkEq(new Tuple("E8003", "array element 1 type mismatch", origin, "number",
                "undefined"), oracleDeleted,
            "the oracle projects the deleted slot as undefined");
        BoundaryOutcome oracleAdmitted = BoundaryExecutor.check(
            FailurePolicyId.TYPE_DESCRIPTOR, nullArray,
            BoundaryValueView.ofArray(BoundaryValueView.nullView()),
            BoundaryContext.none());
        check(oracleAdmitted instanceof BoundaryOutcome.Pass,
            "the oracle admits the present null at the array(null) descriptor: "
                + oracleAdmitted);

        // The JVM leg over the std/json producer's own decoded array
        // ({"values":[null]} checked as int[]).
        deal.codegen.jvm.JvmRuntime.Table decoded =
            (deal.codegen.jvm.JvmRuntime.Table) deal.codegen.jvm.JvmRuntime.stdlib(
                "STDLIB_CALL", "JSON_PARSE", "op", "digest", "parent", origin,
                new Object[] {"{\"values\":[null]}"});
        deal.codegen.jvm.JvmRuntime.Array parsedValues =
            (deal.codegen.jvm.JvmRuntime.Array) decoded.read("values");
        checkEq("unknown", parsedValues.elementDescriptor,
            "JSON arrays retain their unknown element descriptor");
        check(jvmFailure(() -> deal.codegen.jvm.JvmRuntime.bcheck("array(null)", "array", parsedValues)) != null,
            "decoded arrays cannot assert a different descriptor even when elements match");
        deal.codegen.jvm.JvmRuntime.Array values = new deal.codegen.jvm.JvmRuntime.Array(1);
        values.elements.add(null);
        deal.codegen.jvm.JvmRuntime.DealError jvmPresent = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("array(number)", "array", values));
        check(jvmPresent != null,
            "the JVM array check rejects the decoded present null");
        if (jvmPresent != null) {
            checkEq(null, firstDifferingField(oraclePresent, new Tuple(jvmPresent.code,
                jvmPresent.msg, origin, jvmPresent.expected, jvmPresent.actual)),
                "the JVM array check renders the oracle's complete present-null tuple");
            checkEq("-", jvmPresent.origin,
                "the JVM runtime check keeps the arm render's own origin operand "
                    + "(unchanged)");
        }
        checkEq(values, deal.codegen.jvm.JvmRuntime.bcheck("array(null)", "array",
                values),
            "the JVM array(null) check admits the decoded present null unchanged");
        deal.codegen.jvm.JvmRuntime.DealError jvmDeleted = jvmFailure(
            () -> deal.codegen.jvm.JvmRuntime.bcheck("array(number)", "array",
                new deal.codegen.jvm.JvmRuntime.Array(values.length)));
        check(jvmDeleted != null, "the JVM array check rejects the deleted slot");
        if (jvmDeleted != null) {
            checkEq(null, firstDifferingField(oracleDeleted, new Tuple(jvmDeleted.code,
                jvmDeleted.msg, origin, jvmDeleted.expected, jvmDeleted.actual)),
                "the JVM array check renders the oracle's complete deleted-slot tuple");
        }

        // The emitted prelude leg, under real luajit (the decoded array
        // carrier; the mark is inert at the element projection).
        List<String> rows = arrayBoundaryRows().subList(1, 4);
        checkEq("present-null|E8003|number|null|array element 1 type mismatch",
            rows.get(0), "the emitted prelude projects the present null element as "
                + "the null token");
        checkEq("deleted-slot|E8003|number|undefined|array element 1 type mismatch",
            rows.get(1), "the emitted prelude projects the deleted slot as the "
                + "nil token");
        checkEq("null-admission|OK|", rows.get(2),
            "the emitted prelude admits the present null at the array(null) "
                + "descriptor");
        checkEq(null, firstDifferingField(oraclePresent, luaTuple(rows.get(0), origin)),
            "the emitted prelude renders the oracle's complete present-null tuple");
        checkEq(null, firstDifferingField(oracleDeleted, luaTuple(rows.get(1), origin)),
            "the emitted prelude renders the oracle's complete deleted-slot tuple");
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
            + "  {label = \"runtime-async-handle-at-string-field\", root = {[__dealCarrierKeys.c] = true, "
            + "[__dealCarrierKeys.id] = " + quote(WALK_ID.text()) + ", [__dealCarrierKeys.p] = {name = true, age = true, "
            + "ratio = true, data = true, tags = true}, [__dealCarrierKeys.f] = {name = "
            + "rt.async_create(function() return \"x\" end), age = 1, ratio = 0.5, "
            + "data = " + LUA_TABLE_CARRIER + ", tags = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, "
            + "[1] = 1}}}},\n"
            + "  {label = \"runtime-class-root\", root = rt.class_plan_(" + quote(foreign)
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
     * The runtime-produced language-null sentinel at the walk: the chunk's
     * own {@code __NULL} aliases {@code deal/runtime.lua}'s
     * {@code __rt.__NULL} in a host-binding chunk (the single
     * representation ISSUE-0814 lands), and the landed host crossing
     * preserves the object — {@code __hostReturnCell} returns a
     * host-returned table's entries unchanged and {@code __hostDealProject}
     * keeps a runtime class instance's non-class field values. The one
     * language null is the JSON-shaped null leaf in table content (the
     * oracle's own admission: it spells {@code null}), while a null at a
     * declared non-nullable field keeps the failing walk position's null
     * token. The plain-table control at the same content position is the
     * JSON-shaped empty object and serializes as {@code {}} — the sentinel
     * classification is by identity, not by table shape.
     */
    static void testRuntimeNullSentinelAtWalk() throws Exception {
        System.out.println("-- the deployed runtime's language-null sentinel at the walk --");
        String lua = emittedWalkHostChunk();
        check(lua.contains("local __rt = require(\"deal.runtime\")\n")
                && lua.contains("__rtNull = __rt.__NULL\n"),
            "the host-binding production chunk binds the deployed runtime's "
                + "language-null sentinel");
        check(lua.contains("__NULL = __rt.__NULL\n"),
            "the host-binding production chunk aliases its own null sentinel to "
                + "the deployed runtime's (ISSUE-0814)");
        check(lua.contains("__rt.load_host(\"host/runtime\""),
            "the host-binding walk chunk carries its host import");
        String body = "local rt = require(\"deal.runtime\")\n"
            + "local sentinel = rt.check_type(\"?string\", nil)\n"
            + "print(\"runtime-sentinel-binding|\" .. (__rtNull == sentinel and "
            + "\"OK\" or \"MISSING\") .. \"|\")\n"
            + "print(\"chunk-null-aliases-runtime-sentinel|\" .. (__NULL == sentinel "
            + "and \"OK\" or \"MISSING\") .. \"|\")\n"
            + "local returned = __hostReturnCell(\"record\", {k = sentinel}, "
            + quote(WALK_SPAN) + ", false)\n"
            + "local dataRoot = {[__dealCarrierKeys.c] = true, [__dealCarrierKeys.id] = " + quote(WALK_ID.text())
            + ", [__dealCarrierKeys.p] = {name = true, age = true, ratio = true, data = true, "
            + "tags = true}, [__dealCarrierKeys.f] = {name = " + luaString("n") + ", age = 1, "
            + "ratio = 0.5, data = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {k = true}, k = "
            + "returned.k}, tags = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}}}\n"
            + "local cases = {\n"
            + "  {label = \"host-returned-table-null-entry\", root = dataRoot},\n"
            + "  {label = \"plain-table-entry-control\", root = {[__dealCarrierKeys.c] = true, [__dealCarrierKeys.id] = "
            + quote(WALK_ID.text()) + ", [__dealCarrierKeys.p] = {name = true, age = true, ratio = "
            + "true, data = true, tags = true}, [__dealCarrierKeys.f] = {name = " + luaString("n")
            + ", age = 1, ratio = 0.5, data = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {k = true}, "
            + "k = {}}, tags = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}}}},\n"
            + "  {label = \"runtime-class-null-field\", root = __hostDealProject("
            + quote(WALK_ID.text()) + ", {name = returned.k, age = 1, ratio = "
            + "0.5, data = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {}}, tags = {[__dealCarrierKeys.a] = true, "
            + "[__dealCarrierKeys.n] = 0}, [__dealCarrierKeys.kind] = \"class\", [__dealCarrierKeys.classname] = "
            + quote(WALK_ID.text()) + "}, " + quote(WALK_SPAN) + ")},\n"
            + "}\n"
            + "for i = 1, #cases do\n"
            + "  local c = cases[i]\n"
            + "  local ok, text, arm, params, actual = __jsonToClassOp("
            + quote(WALK_ID.text()) + ", c.root)\n"
            + "  if ok then\n"
            + "    print(c.label .. \"|OK|\" .. text .. \"|\")\n"
            + "  else\n"
            + "    local value = __arm(arm, params, " + quote(WALK_SPAN)
            + ", nil, actual)\n"
            + "    print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n"
            + "  end\n"
            + "end\n";
        List<String> rows = runProbe(lua, "arm-walk", body, "walk-runtime-null", 5,
            true);
        checkEq("runtime-sentinel-binding|OK|", rows.get(0),
            "the emitted chunk's own binding is the deployed runtime's sentinel");
        checkEq("chunk-null-aliases-runtime-sentinel|OK|", rows.get(1),
            "the host-binding chunk's null sentinel is the deployed runtime's "
                + "object (ISSUE-0814)");
        checkEq("host-returned-table-null-entry|OK|"
                + "{\"name\":\"n\",\"age\":1.0,\"ratio\":0.5,\"data\":{\"k\":null},"
                + "\"tags\":[1.0]}|", rows.get(2),
            "the one runtime null in a host-returned table entry is the JSON-shaped "
                + "null leaf and spells null");
        checkEq("plain-table-entry-control|OK|"
                + "{\"name\":\"n\",\"age\":1.0,\"ratio\":0.5,\"data\":{\"k\":{}},"
                + "\"tags\":[1.0]}|", rows.get(3),
            "the plain-table control at the same content position is the JSON-shaped "
                + "empty object and serializes as an empty object");
        checkEq(new Tuple("E8001",
                "value at name is not JSON serializable: null", WALK_SPAN, null,
                "null"), walkLuaTuple(rows.get(4)),
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
     * plain-table control at the same content position is the JSON-shaped
     * empty object and completes the walk — the classification is by
     * sentinel identity, not by table shape.
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
            + "local returned = __hostReturnCell(\"record\", {k = sentinel}, "
            + quote(WALK_SPAN) + ", false)\n"
            + "local dataRoot = {[__dealCarrierKeys.c] = true, [__dealCarrierKeys.id] = " + quote(WALK_ID.text())
            + ", [__dealCarrierKeys.p] = {name = true, age = true, ratio = true, data = true, "
            + "tags = true}, [__dealCarrierKeys.f] = {name = " + luaString("n") + ", age = 1, "
            + "ratio = 0.5, data = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {k = true}, k = "
            + "returned.k}, tags = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}}}\n"
            + "local cases = {\n"
            + "  {label = \"host-returned-table-missing-entry\", root = dataRoot},\n"
            + "  {label = \"plain-table-entry-control\", root = {[__dealCarrierKeys.c] = true, [__dealCarrierKeys.id] = "
            + quote(WALK_ID.text()) + ", [__dealCarrierKeys.p] = {name = true, age = true, ratio = "
            + "true, data = true, tags = true}, [__dealCarrierKeys.f] = {name = " + luaString("n")
            + ", age = 1, ratio = 0.5, data = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {k = true}, "
            + "k = {}}, tags = {[__dealCarrierKeys.a] = true, [__dealCarrierKeys.n] = 1, [1] = 1}}}},\n"
            + "  {label = \"runtime-class-missing-field\", root = __hostDealProject("
            + quote(WALK_ID.text()) + ", {name = returned.k, age = 1, ratio = "
            + "0.5, data = {[__dealCarrierKeys.t] = true, [__dealCarrierKeys.keys] = {}}, tags = {[__dealCarrierKeys.a] = true, "
            + "[__dealCarrierKeys.n] = 0}, [__dealCarrierKeys.kind] = \"class\", [__dealCarrierKeys.classname] = "
            + quote(WALK_ID.text()) + "}, " + quote(WALK_SPAN) + ")},\n"
            + "}\n"
            + "for i = 1, #cases do\n"
            + "  local c = cases[i]\n"
            + "  local ok, text, arm, params, actual = __jsonToClassOp("
            + quote(WALK_ID.text()) + ", c.root)\n"
            + "  if ok then\n"
            + "    print(c.label .. \"|OK|\")\n"
            + "  else\n"
            + "    local value = __arm(arm, params, " + quote(WALK_SPAN)
            + ", nil, actual)\n"
            + "    print(c.label .. \"|\" .. value.code .. \"|\" .. "
            + "(value.e == nil and \"\" or value.e) .. \"|\" .. "
            + "(value.a == nil and \"\" or value.a) .. \"|\" .. value.m .. \"|\" .. "
            + "tostring(value.o))\n"
            + "  end\n"
            + "end\n";
        List<String> rows = runProbe(lua, "arm-walk", body, "walk-runtime-missing", 4,
            true);
        checkEq("runtime-missing-binding|OK|", rows.get(0),
            "the emitted chunk's own binding is the deployed runtime's absent-marker "
                + "sentinel");
        checkEq(new Tuple("E8001",
                "value at data.k is not JSON serializable: undefined", WALK_SPAN, null,
                "undefined"), walkLuaTuple(rows.get(1)),
            "the runtime absent marker in a host-returned table entry renders undefined in "
                + "the message and the actual field");
        checkEq("plain-table-entry-control|OK|", rows.get(2),
            "the plain-table control at the same content position is the JSON-shaped "
                + "empty object and completes the walk");
        checkEq(new Tuple("E8001",
                "value at name is not JSON serializable: undefined", WALK_SPAN, null,
                "undefined"), walkLuaTuple(rows.get(3)),
            "the runtime absent marker at a runtime class instance's declared field "
                + "renders undefined in the message and the actual field");
    }

    // =========================================================================
    // 17. The walk arm's oracle-dispatch leg (the executable IR drive)
    // =========================================================================

    private record DispatchCase(String label, String fieldPath, String actual,
                                DispatchUnit unit) {

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
    private enum DispatchValue { STRING, INT, NUMBER, NUMBER_FINITE, TABLE, ARRAY }

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

    static void testWalkArmOracleDispatchLeg() throws Exception {
        System.out.println("-- the walk arm's oracle-dispatch leg --");
        List<DispatchCase> cases = new ArrayList<>();

        // (1) Construction admits a nonfinite declared number and the
        // executing JSON walk encodes it as null.
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
                Map.of(WALK_ID, walkLayout())));
        }

        // (2) A malformed construction cannot publish an omitted required
        // field to the walk. Direct walk controls above independently cover
        // missing fields on forged carriers.
        {
            DispatchIds ids = new DispatchIds();
            Map<String, DispatchValue> present = new LinkedHashMap<>();
            present.put("name", DispatchValue.STRING);
            present.put("ratio", DispatchValue.NUMBER);
            present.put("data", DispatchValue.TABLE);
            present.put("tags", DispatchValue.ARRAY);
            DispatchCase omitted = dispatchCase("dispatch-missing-required-field", "age", "undefined",
                ids, WALK_ID, walkLayout(), present, Map.of(WALK_ID, walkLayout()));
            try {
                deal.semantic.SemanticOracle.executeProjectInits(
                    omitted.unit().project(), omitted.unit().tables(), Map.of(), null);
                check(false, "omitted required field must fail before class publication");
            } catch (ClassOpsExecutor.Defect defect) {
                check(defect.getMessage().contains("omits required-present field 'age'"),
                    "the dispatch retains the missing field defect: " + defect.getMessage());
            }
        }

        // (3) A produced instance of another class: the walk's own root
        // identity check selects the walk arm with the carried atom.
        {
            DispatchIds ids = new DispatchIds();
            Map<String, DispatchValue> present = new LinkedHashMap<>();
            present.put("x", DispatchValue.INT);
            cases.add(dispatchCase("dispatch-foreign-class-root", "",
                FOREIGN_ID.text(), ids, FOREIGN_ID, foreignLayout(), present,
                Map.of(WALK_ID, walkLayout(), FOREIGN_ID, foreignLayout())));
        }

        for (DispatchCase drive : cases) {
            deal.semantic.SemanticRuntimeModel.ConsumerRun run =
                deal.semantic.SemanticOracle.executeProjectInits(
                    drive.unit().project(), drive.unit().tables(), Map.of(), null);
            if (drive.label().equals("dispatch-nonfinite-number-field")) {
                check(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
                    "the Oracle dispatch admits nonfinite declared-number JSON encoding: "
                        + run.terminal());
                continue;
            }
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

        }
    }

    // =========================================================================
    // 17b. The walk arm's oracle-dispatch cycle leg (the runtime-to-executor
    //      container boundary)
    // =========================================================================

    /**
     * The oracle-dispatch leg's cycle remediation (the runtime-to-executor
     * container boundary): the oracle heap's containers cross into the
     * closed executor view identity-preserving and current, so a lowered
     * {@code JSON_TO_CLASS} op over a cyclic heap renders the bound walk
     * family through the authority — the cycle arm for a path-local
     * re-entry, and the walk arm at the first failing position when an
     * unsupported value precedes the cycle. Every case builds the graph
     * with producer/commit ops ({@code TABLE_NEW}/{@code ARRAY_NEW}/
     * {@code MEMBER_WRITE}) and the class construction, then runs the
     * executing module-init block: a pre-construction container cycle must
     * not exhaust the stack at the conversion boundary, a cycle introduced
     * after the class construction must not serialize the construction-time
     * snapshot, and the recorded first failure stays the selected arm.
     */
    static void testWalkArmOracleDispatchCycles() throws Exception {
        System.out.println("-- the walk arm's oracle-dispatch cycle leg --");

        // (1) A table re-entry created before the class construction: the
        // walk operand is an uncached self-referential table, so the
        // conversion boundary must preserve the container identity instead
        // of recursing before the cycle needle selects the cycle arm.
        {
            DispatchIds ids = new DispatchIds();
            deal.semantic.ir.ValueId table = ids.value();
            List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
            ops.add(dispatchEmptyTable(ids, table));
            ops.add(dispatchMemberWrite(ids, table, "self", table));
            DispatchConstruction construction = dispatchCycleConstruction(ids, table);
            ops.addAll(construction.ops());
            checkDispatchCycleTuple("dispatch-table-cycle-before-construction",
                dispatchUnit(ids, ops, construction.instance(),
                    Map.of(WALK_ID, walkLayout())),
                "JSON_TO_WALK_CYCLE", null, null);
        }

        // (2) A table re-entry introduced after the class construction (the
        // construction cache already holds the table's view): the live view
        // reads the commit, so the walk selects the cycle arm instead of
        // serializing the construction-time snapshot.
        {
            DispatchIds ids = new DispatchIds();
            deal.semantic.ir.ValueId table = ids.value();
            List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
            ops.add(dispatchEmptyTable(ids, table));
            DispatchConstruction construction = dispatchCycleConstruction(ids, table);
            ops.addAll(construction.ops());
            ops.add(dispatchMemberWrite(ids, table, "self", table));
            checkDispatchCycleTuple("dispatch-table-cycle-after-construction",
                dispatchUnit(ids, ops, construction.instance(),
                    Map.of(WALK_ID, walkLayout())),
                "JSON_TO_WALK_CYCLE", null, null);
        }

        // (3) A mixed table/array re-entry introduced after the class
        // construction through an array-element write: the construction
        // snapshots the table with its array's element still a second table,
        // so the later INDEX_WRITE must be observed by the array view and the
        // re-entry needle is the table entered before the array.
        {
            DispatchIds ids = new DispatchIds();
            deal.semantic.ir.ValueId table = ids.value();
            deal.semantic.ir.ValueId element = ids.value();
            List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
            ops.add(dispatchEmptyTable(ids, table));
            ops.add(dispatchEmptyTable(ids, element));
            deal.semantic.ir.ValueId array = ids.value();
            ops.addAll(dispatchArrayOf(ids, element, array));
            ops.add(dispatchMemberWrite(ids, table, "arr", array));
            DispatchConstruction construction = dispatchCycleConstruction(ids, table);
            ops.addAll(construction.ops());
            ops.addAll(dispatchArrayElementWrite(ids, array, table));
            checkDispatchCycleTuple("dispatch-mixed-cycle-after-construction",
                dispatchUnit(ids, ops, construction.instance(),
                    Map.of(WALK_ID, walkLayout())),
                "JSON_TO_WALK_CYCLE", null, null);
        }

        // (4) A supported class before a table re-entry must serialize its
        // fields, then reach and reject the cycle; class content is not a failure.
        {
            DispatchIds ids = new DispatchIds();
            deal.semantic.ir.ValueId table = ids.value();
            List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
            DispatchConstruction foreign = buildDispatchConstruction(ids, FOREIGN_ID,
                foreignLayout(), Map.of("x", DispatchValue.INT));
            ops.addAll(foreign.ops());
            ops.add(dispatchEmptyTable(ids, table));
            DispatchConstruction construction = dispatchCycleConstruction(ids, table);
            ops.addAll(construction.ops());
            ops.add(dispatchMemberWrite(ids, table, "bad", foreign.instance()));
            ops.add(dispatchMemberWrite(ids, table, "self", table));
            checkDispatchCycleTuple("dispatch-class-before-table-cycle",
                dispatchUnit(ids, ops, construction.instance(),
                    Map.of(WALK_ID, walkLayout(), FOREIGN_ID, foreignLayout())),
                "JSON_TO_WALK_CYCLE", null, null);
        }

        // (5) The cycle-first control over the same graph shape: the re-entry
        // under 'self' precedes the unsupported carrier under 'zbad', so the
        // needle's own closed marker still selects the cycle arm (the
        // selection is the walk's first failure, never the presence of an
        // unsupported value anywhere in the subtree).
        {
            DispatchIds ids = new DispatchIds();
            deal.semantic.ir.ValueId table = ids.value();
            List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
            DispatchConstruction foreign = buildDispatchConstruction(ids, FOREIGN_ID,
                foreignLayout(), Map.of("x", DispatchValue.INT));
            ops.addAll(foreign.ops());
            ops.add(dispatchEmptyTable(ids, table));
            DispatchConstruction construction = dispatchCycleConstruction(ids, table);
            ops.addAll(construction.ops());
            ops.add(dispatchMemberWrite(ids, table, "self", table));
            ops.add(dispatchMemberWrite(ids, table, "zbad", foreign.instance()));
            checkDispatchCycleTuple("dispatch-cycle-before-unsupported-value",
                dispatchUnit(ids, ops, construction.instance(),
                    Map.of(WALK_ID, walkLayout(), FOREIGN_ID, foreignLayout())),
                "JSON_TO_WALK_CYCLE", null, null);
        }
    }

    /** One empty {@code TABLE_NEW} op of the dispatch cycle leg. */
    private static deal.semantic.ir.SemanticOp dispatchEmptyTable(DispatchIds ids,
            deal.semantic.ir.ValueId table) {
        return dispatchOp(ids.op(), deal.semantic.ir.SemanticOpKind.TABLE_NEW,
            new KindPayload.TableNewPayload(List.of()), table,
            RuntimeDescriptor.Table.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            dispatchOrigin(ids, null));
    }

    /** One {@code MEMBER_WRITE} commit op of the dispatch cycle leg. */
    private static deal.semantic.ir.SemanticOp dispatchMemberWrite(DispatchIds ids,
            deal.semantic.ir.ValueId table, String key, deal.semantic.ir.ValueId value) {
        return dispatchOp(ids.op(), deal.semantic.ir.SemanticOpKind.MEMBER_WRITE,
            new KindPayload.MemberWritePayload(table, key, value), null, null,
            FailurePolicyId.NO_DEAL_FAILURE, dispatchOrigin(ids, null));
    }

    /**
     * The dispatch cycle leg's mixed path: one single-element
     * {@code ARRAY_NEW} over the given element table and its pinned
     * {@code ARRAY_LITERAL_ELEMENT} boundary child.
     */
    private static List<deal.semantic.ir.SemanticOp> dispatchArrayOf(DispatchIds ids,
            deal.semantic.ir.ValueId element, deal.semantic.ir.ValueId array) {
        deal.semantic.ir.OpId arrayOp = ids.op();
        deal.semantic.ir.OpId elementBoundary = ids.op();
        deal.semantic.ir.SemanticOp arrayNew = dispatchOp(arrayOp,
            deal.semantic.ir.SemanticOpKind.ARRAY_NEW,
            new KindPayload.ArrayNewPayload(RuntimeDescriptor.Table.INSTANCE,
                List.of(element), List.of(elementBoundary)),
            array, new RuntimeDescriptor.Array(RuntimeDescriptor.Table.INSTANCE),
            FailurePolicyId.NO_DEAL_FAILURE, dispatchOrigin(ids, null));
        deal.semantic.ir.SemanticOp boundary = dispatchOp(elementBoundary,
            deal.semantic.ir.SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_LITERAL_ELEMENT,
                RuntimeDescriptor.Table.INSTANCE, element,
                new deal.semantic.ir.BoundaryRealization.RuntimeValidation(
                    deal.semantic.SemanticLowerer.CANONICAL_RUNTIME_VALIDATION_ID)),
            null, null, FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR,
            dispatchOrigin(ids, arrayOp));
        return List.of(arrayNew, boundary);
    }

    /**
     * One array-element write of the dispatch cycle leg, in the landed
     * lowering order: the index constant, the {@code ARRAY_LENGTH} read, the
     * {@code ARRAY_WRITE} slot normalization, and the {@code INDEX_WRITE}
     * commit that mutates the container's element in place.
     */
    private static List<deal.semantic.ir.SemanticOp> dispatchArrayElementWrite(
            DispatchIds ids, deal.semantic.ir.ValueId array,
            deal.semantic.ir.ValueId value) {
        deal.semantic.ir.ValueId key = ids.value();
        deal.semantic.ir.SemanticOp keyOp = dispatchOp(ids.op(),
            deal.semantic.ir.SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Number(0)), key,
            RuntimeDescriptor.Number.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            dispatchOrigin(ids, null));
        deal.semantic.ir.ValueId length = ids.value();
        deal.semantic.ir.SemanticOp lengthOp = dispatchOp(ids.op(),
            deal.semantic.ir.SemanticOpKind.ARRAY_LENGTH,
            new KindPayload.ArrayLengthPayload(array), length,
            RuntimeDescriptor.Number.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            dispatchOrigin(ids, null));
        deal.semantic.ir.ValueId slot = ids.value();
        deal.semantic.ir.SemanticOp normalizeOp = dispatchOp(ids.op(),
            deal.semantic.ir.SemanticOpKind.INDEX_NORMALIZE,
            new KindPayload.IndexNormalizePayload(
                deal.semantic.ir.IndexMode.ARRAY_WRITE, key, length),
            slot, RuntimeDescriptor.Number.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            dispatchOrigin(ids, null));
        deal.semantic.ir.SemanticOp writeOp = dispatchOp(ids.op(),
            deal.semantic.ir.SemanticOpKind.INDEX_WRITE,
            new KindPayload.IndexWritePayload(array, slot, value), null, null,
            FailurePolicyId.NO_DEAL_FAILURE, dispatchOrigin(ids, null));
        return List.of(keyOp, lengthOp, normalizeOp, writeOp);
    }

    /**
     * One dispatch cycle construction: the drive's walk layout with the
     * prebuilt table in the declared {@code data} field and the other
     * fields' pinned producers (a finite ratio, so the cycle position is
     * the first failure).
     */
    private static DispatchConstruction dispatchCycleConstruction(DispatchIds ids,
            deal.semantic.ir.ValueId table) {
        Map<String, DispatchValue> present = new LinkedHashMap<>();
        present.put("name", DispatchValue.STRING);
        present.put("age", DispatchValue.INT);
        present.put("ratio", DispatchValue.NUMBER_FINITE);
        present.put("tags", DispatchValue.ARRAY);
        return buildDispatchConstruction(ids, WALK_ID, walkLayout(), present,
            Map.of("data", table));
    }

    /**
     * Runs one dispatch cycle unit and asserts its rendered walk tuple: the
     * closed arm at the executing op's own {@code SourceOrigin}, with the
     * arms' own expected/actual presence (the cycle arm carries neither; the
     * walk arm carries the failing value's token and the pinned message).
     */
    private static void checkDispatchCycleTuple(String label, DispatchUnit unit,
            String arm, String fieldPath, String actual) {
        deal.semantic.SemanticRuntimeModel.ConsumerRun run =
            deal.semantic.SemanticOracle.executeProjectInits(
                unit.project(), unit.tables(), Map.of(), null);
        check(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                .DealFailure,
            label + ": the oracle dispatch terminates with the walk's DEAL failure; "
                + "got " + run.terminal());
        if (!(run.terminal() instanceof deal.semantic.SemanticRuntimeModel.Terminal
                .DealFailure failure)) {
            return;
        }
        deal.semantic.SemanticRuntimeModel.ErrorSnapshot error = failure.error();
        String expectedMessage = "JSON_TO_WALK_CYCLE".equals(arm)
            ? "cyclic value cannot be encoded as JSON"
            : "value at " + fieldPath + " is not JSON serializable: " + actual;
        checkEq(new Tuple("E8001", expectedMessage, WALK_SPAN,
                null, "JSON_TO_WALK_CYCLE".equals(arm) ? null : actual),
            new Tuple(error.code(), error.message(), error.origin(),
                error.expected(), error.actual()),
            label + ": the oracle dispatch renders the bound walk arm's tuple at the "
                + "executing op's own SourceOrigin");
    }

    /** One dispatch construction with its producer ops and the walk op. */
    private static DispatchCase dispatchCase(String label, String fieldPath,
            String actual, DispatchIds ids, deal.semantic.ir.ClassId classId,
            deal.semantic.ir.ClassLayout layout,
            Map<String, DispatchValue> present,
            Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts) {
        DispatchConstruction construction = buildDispatchConstruction(ids, classId,
            layout, present);
        return new DispatchCase(label, fieldPath, actual,
            dispatchUnit(ids, construction, layouts));
    }


    /** The dispatch drive's foreign class layout (one provided int field). */
    private static deal.semantic.ir.ClassLayout foreignLayout() {
        return new deal.semantic.ir.ClassLayout(FOREIGN_ID, List.of(
            new deal.semantic.ir.ClassLayout.FieldLayout("x",
                RuntimeDescriptor.Number.INSTANCE, true,
                deal.semantic.ir.ConstructionProvider.LOCAL)));
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
        return buildDispatchConstruction(ids, classId, layout, present, Map.of());
    }

    /**
     * One executable construction with prebuilt field operands: every field
     * named in {@code prebuilt} uses the caller's already-produced value
     * (the cycle leg's mutated table), and every field named in
     * {@code present} gets the pinned producer op; the declaration-order
     * boundary children and the {@code CLASS_NEW} op stay the pinned shape.
     */
    private static DispatchConstruction buildDispatchConstruction(DispatchIds ids,
            deal.semantic.ir.ClassId classId, deal.semantic.ir.ClassLayout layout,
            Map<String, DispatchValue> present,
            Map<String, deal.semantic.ir.ValueId> prebuilt) {
        deal.semantic.ir.OpId classNewOp = ids.op();
        List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>();
        List<KindPayload.ProvidedField> provided = new ArrayList<>();
        Map<String, deal.semantic.ir.ValueId> values = new LinkedHashMap<>();
        for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
            deal.semantic.ir.ValueId built = prebuilt.get(field.name());
            if (built != null) {
                provided.add(new KindPayload.ProvidedField(field.name(), built));
                values.put(field.name(), built);
                continue;
            }
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
                deal.semantic.ir.ConstructionProvider.LOCAL, null, boundaries),
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
                payload = new KindPayload.ConstPayload(new ScalarValue.Number(1));
                opKind = deal.semantic.ir.SemanticOpKind.CONST;
                resultType = RuntimeDescriptor.Number.INSTANCE;
            }
            case NUMBER -> {
                payload = new KindPayload.ConstPayload(
                    new ScalarValue.Number(Double.NaN));
                opKind = deal.semantic.ir.SemanticOpKind.CONST;
                resultType = RuntimeDescriptor.Number.INSTANCE;
            }
            case NUMBER_FINITE -> {
                payload = new KindPayload.ConstPayload(new ScalarValue.Number(0.5));
                opKind = deal.semantic.ir.SemanticOpKind.CONST;
                resultType = RuntimeDescriptor.Number.INSTANCE;
            }
            case TABLE -> {
                payload = new KindPayload.TableNewPayload(List.of());
                opKind = deal.semantic.ir.SemanticOpKind.TABLE_NEW;
                resultType = RuntimeDescriptor.Table.INSTANCE;
            }
            case ARRAY -> {
                payload = new KindPayload.ArrayNewPayload(RuntimeDescriptor.Number.INSTANCE,
                    List.of(), List.of());
                opKind = deal.semantic.ir.SemanticOpKind.ARRAY_NEW;
                resultType = new RuntimeDescriptor.Array(RuntimeDescriptor.Number.INSTANCE);
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
        return dispatchUnit(ids, construction.ops(), construction.instance(), layouts);
    }

    /**
     * The dispatch project over one explicit op list: the produced ops in
     * order, the walk op over the given class-value operand, and the
     * module-init table.
     */
    private static DispatchUnit dispatchUnit(DispatchIds ids,
            List<deal.semantic.ir.SemanticOp> producedOps,
            deal.semantic.ir.ValueId instance,
            Map<deal.semantic.ir.ClassId, deal.semantic.ir.ClassLayout> layouts) {
        deal.semantic.ir.ModuleId entry = ids.module();
        deal.semantic.ir.SemanticOp json = walkJsonOp(ids.op(), instance, ids.value());
        List<deal.semantic.ir.SemanticOp> ops = new ArrayList<>(producedOps);
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
                deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, entry, "hash",
                "context", java.util.Set.of(), Map.of(), layouts, Map.of(),
                new deal.semantic.ir.ModuleInitPlan(List.of(), initBlock),
                deal.semantic.ir.ExportPlan.empty(), Map.of(), ops, table,
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                deal.semantic.ir.ClassBindingFacts.empty());
        deal.semantic.ir.ProjectInterfaceIndex index =
            new deal.semantic.ir.ProjectInterfaceIndex(
                deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION,
                Map.of(entry, new deal.semantic.ir.ExternalModuleInterface(entry,
                    deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(),
                    List.of(), List.of(),
                    deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)));
        deal.semantic.ir.ExecutableLoweredProject project =
            new deal.semantic.ir.ExecutableLoweredProject(
                deal.semantic.ir.SemanticProfile.DEAL_V1_5_BINARY64, index,
                Map.of(entry, unit), entry,
                deal.semantic.ClassRegistrationSeeds.builtinErrorOnly());
        return new DispatchUnit(project, Map.of(entry, table), json.origin());
    }
}
