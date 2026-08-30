package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.semantic.BoundaryRealizationReport;
import deal.semantic.DescriptorService;
import deal.semantic.ModuleEmissionResult;
import deal.semantic.ir.ActualKind;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies the ISSUE-0233 D4 surface: the closed realization/report
 * completion predicate on {@link BoundaryRealizationReport} — exactly one
 * recorded realization per {@code BOUNDARY} op of a validated lowered
 * unit (key-set equality; each entry equals the op payload's
 * {@code realization}), the closed proof-eligibility cell
 * ({@code RepresentationProof} admissible only on {@code DEAL_TO_HOST} +
 * {@code HOST_PARAMETER}; every other cell requires
 * {@code RuntimeValidation}), non-empty {@code checkId}/{@code proofKind},
 * and the E6005 paths through {@code FailureContractRegistry} naming the
 * module and the offending op/cell (wiki Verification 3).
 *
 * <p>Tests:
 * <ol>
 *   <li>Pinned negatives, each asserting the E6005 code, phase
 *       {@code BACKEND_LOWERING}, and the detail naming the module and
 *       the offending op/cell: a unit with one {@code BOUNDARY} op and an
 *       empty report (missing entry); a report with an extra op-id key;
 *       an entry whose realization differs from the op payload's; a
 *       {@code RepresentationProof} on non-admissible cell families
 *       ({@code FUNCTION_PARAMETER}, {@code ARRAY_ELEMENT_READ},
 *       {@code STDLIB_RETURN}, and {@code DEAL_TO_HOST} with the wrong
 *       policy); an empty {@code checkId}/{@code proofKind}.</li>
 *   <li>The deterministic first-failure order (unit op order, then
 *       missing → mismatch → proof cell → empty identifier within one op,
 *       then report key order for extras).</li>
 *   <li>Pinned positives: {@code RuntimeValidation} on every other cell
 *       family passes (including a physical check on the proof-eligible
 *       cell); {@code RepresentationProof} on {@code DEAL_TO_HOST} +
 *       {@code HOST_PARAMETER} passes; an empty unit with
 *       {@code BoundaryRealizationReport.empty()} is complete; the
 *       predicate mutates nothing.</li>
 *   <li>Preservation: {@code SemanticIrValidator.RULES} is still the
 *       exact pinned 14-condition list in S6 order (no rule added by this
 *       task).</li>
 *   <li>The {@code ModuleEmissionResult.failed()} convention: a report
 *       that fails completion carries its E6005 diagnostic and the
 *       module publishes no staged artifact.</li>
 *   <li>Combined behavior with T1/T2: one synthetic unit whose
 *       descriptors come from {@code DescriptorService.describe},
 *       validator-accepted through {@code SemanticIrValidator}, executed
 *       cell-by-cell through {@code BoundaryExecutor} (one
 *       {@code RuntimeValidation} cell with a passing and one with a
 *       failing view; one proved cell that skips check logic), then
 *       completed through the predicate — and a variant with one entry
 *       dropped asserts the E6005. The chain fails if T1 or T2 is
 *       broken.</li>
 * </ol>
 */
public class BoundaryRealizationReportTest {

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
            testEmptyUnitAndEmptyReport();
            testMissingEntry();
            testExtraEntry();
            testMismatchedEntry();
            testProofNotAdmissible();
            testEmptyIdentifiers();
            testFirstFailureOrder();
            testPositives();
            testValidatorRuleSetPreservation();
            testFailedEmissionConvention();
            testCombinedBehaviorWithT1T2();
        } catch (Throwable t) {
            failed++;
            System.err.println("FAIL: unexpected " + t);
            t.printStackTrace();
        }
        System.out.println();
        System.out.println("BoundaryRealizationReportTest: " + passed + " passed, "
            + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    // =========================================================================
    // Shared synthetic fixtures
    // =========================================================================

    private static final ModuleId MOD = new ModuleId("mod.a");
    private static final ModuleId HOST_MOD = new ModuleId("host.a");
    private static final String IFACE = "interface-digest-1";
    private static final String REGISTRY = "capability-registry-hash-1";
    private static final SemanticIrValidator.ComparisonFacts FACTS =
        new SemanticIrValidator.ComparisonFacts(IFACE, SemanticProfile.DEAL_V1_2_INT32, REGISTRY);
    private static final String LCH =
        LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32, REGISTRY);

    // Descriptors built through DescriptorService (combined behavior with T1).
    private static final RuntimeDescriptor STRING =
        DescriptorService.describe(Type.String.INSTANCE);
    private static final RuntimeDescriptor INT =
        DescriptorService.describe(Type.Int.INSTANCE);
    private static final RuntimeDescriptor.Func SIG_II = (RuntimeDescriptor.Func)
        DescriptorService.describe(new Type.Func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE));
    private static final RuntimeDescriptor.Func SYNC_STR_TO_STR = (RuntimeDescriptor.Func)
        DescriptorService.describe(
            new Type.Func(List.of(Type.String.INSTANCE), Type.String.INSTANCE));

    private static int nextOp = 1;
    private static int nextVal = 1;

    private static OpId nextOpId() {
        return new OpId(MOD, nextOp++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextVal++);
    }

    private static SourceOrigin origin(OpId parent) {
        return new SourceOrigin("test.deal", SourceSpan.synthetic("test.deal"),
            SourceOriginKind.SYNTHETIC, new AnchorId(0), parent);
    }

    private static OperationContractSnapshot contractFor(SemanticOpKind kind, KindPayload payload,
            OpResultType resultType, List<RuntimeDescriptor> operandTypes,
            FailurePolicyId policy, String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    private static SemanticOp opWith(OpId id, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy, OpId parent) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, List.of(), policy, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, List.of(), policy, digest);
        return new SemanticOp(id, kind, origin(parent), result, resultType, List.of(), List.of(),
            payload, policy, contract);
    }

    private static SemanticOp boundaryWith(OpId id, BoundaryKind kind, RuntimeDescriptor descriptor,
            FailurePolicyId policy, BoundaryRealization realization, OpId parent) {
        return opWith(id, SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, nextValue(), realization),
            null, null, policy, parent);
    }

    private static LoweredModuleUnit unit(List<SemanticOp> ops) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, MOD, IFACE, LCH, Set.of(), Map.of(), Map.of(),
            Map.of(), new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(),
            Map.of(), ops);
    }

    /** The report the emitter fills from the op payloads (complete by construction). */
    private static BoundaryRealizationReport reportOf(List<SemanticOp> ops) {
        Map<OpId, BoundaryRealization> map = new LinkedHashMap<>();
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BOUNDARY) {
                KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
                map.put(op.opId(), payload.realization());
            }
        }
        return new BoundaryRealizationReport(map);
    }

    private static void assertComplete(Optional<CompilerDiagnostic> diagnostic, String what) {
        check(diagnostic.isEmpty(), what + " is complete"
            + (diagnostic.isPresent() ? ": " + diagnostic.get().message() : ""));
    }

    /** Pins the E6005 shape: code, phase, severity, registry-instantiated
     *  message naming the module, the capability, the rule, and every
     *  required op/cell fragment. */
    private static void assertE6005(Optional<CompilerDiagnostic> diagnostic, String rule,
            String... contains) {
        check(diagnostic.isPresent(), rule + " is rejected with E6005");
        if (diagnostic.isEmpty()) {
            return;
        }
        CompilerDiagnostic d = diagnostic.get();
        check("E6005".equals(d.code()), rule + " diagnostic code is E6005");
        check(d.diagnosticCode() == DiagnosticCode.E6005, rule + " diagnosticCode is E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            rule + " phase is BACKEND_LOWERING");
        check("error".equals(d.severity()), rule + " severity is error");
        String message = d.message();
        check(message.startsWith("Common semantic lowering failed"),
            rule + " message instantiates the registry-owned E6005 template");
        String[] byRule = message.split("validatorRule ");
        check(byRule.length == 2 && byRule[1].startsWith(rule + ","),
            rule + " is named as the validator rule (exactly one rule per defect); got \""
                + message + "\"");
        check(message.contains("module 'mod.a'"),
            rule + " message names the module; got \"" + message + "\"");
        check(message.contains("capability BOUNDARIES"),
            rule + " message names the BOUNDARIES capability");
        check(message.contains("semanticProfile DEAL_V1_2_INT32")
                && message.contains("irVersion deal.semantic-ir/1"),
            rule + " message carries the profile and IR version");
        for (String c : contains) {
            check(message.contains(c), rule + " message contains \"" + c + "\"; got \""
                + message + "\"");
        }
    }

    private static void assertPass(SemanticIrValidator.ComparisonFacts facts,
            LoweredModuleUnit unit, String what) {
        Optional<CompilerDiagnostic> diagnostic = SemanticIrValidator.validate(unit, facts);
        check(diagnostic.isEmpty(), what + " passes validation"
            + (diagnostic.isPresent() ? ": " + diagnostic.get().message() : ""));
    }

    // =========================================================================
    // 1. Pinned positives: empty unit, proof-eligible cell, runtime cells
    // =========================================================================

    static void testEmptyUnitAndEmptyReport() {
        System.out.println("-- Empty unit + empty report --");

        LoweredModuleUnit unit = unit(List.of());
        BoundaryRealizationReport report = BoundaryRealizationReport.empty();
        assertComplete(BoundaryRealizationReport.complete(unit, report),
            "an empty unit with BoundaryRealizationReport.empty()");
        check(report.realizations().isEmpty(), "the predicate mutates nothing (report unchanged)");
        check(unit.ops().isEmpty(), "the predicate mutates nothing (unit unchanged)");
    }

    static void testPositives() {
        System.out.println("-- Positives: runtime validation on every cell family + the admissible proof --");

        // RuntimeValidation on every one of the 25 closed BoundaryKind
        // values (the descriptor-kind-rule cells, the array cells, the
        // completion cell, the construction/optional/contextual/imported
        // read cells, the host-direction cells, the stdlib/external
        // cells, the JSON cells, and a physical check on the
        // proof-eligible DEAL_TO_HOST + HOST_PARAMETER cell).
        record Cell(String what, BoundaryKind kind, RuntimeDescriptor descriptor,
                    FailurePolicyId policy) { }
        List<Cell> cells = List.of(
            new Cell("VARIABLE_DECLARATION", BoundaryKind.VARIABLE_DECLARATION, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("VARIABLE_ASSIGNMENT", BoundaryKind.VARIABLE_ASSIGNMENT, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("CLASS_FIELD_ASSIGNMENT", BoundaryKind.CLASS_FIELD_ASSIGNMENT, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("ARRAY_ELEMENT_READ", BoundaryKind.ARRAY_ELEMENT_READ, STRING,
                FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR),
            new Cell("ARRAY_LITERAL_ELEMENT", BoundaryKind.ARRAY_LITERAL_ELEMENT, STRING,
                FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR),
            new Cell("ARRAY_ELEMENT_ASSIGNMENT", BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT, STRING,
                FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT),
            new Cell("ARRAY_ELEMENT_DELETE", BoundaryKind.ARRAY_ELEMENT_DELETE, STRING,
                FailurePolicyId.ARRAY_DELETE_BOUNDS),
            new Cell("ASYNC_COMPLETION", BoundaryKind.ASYNC_COMPLETION, STRING,
                FailurePolicyId.ASYNC_COMPLETION),
            new Cell("CLASS_LITERAL_FIELD", BoundaryKind.CLASS_LITERAL_FIELD, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("CLASS_DEFAULT_FIELD", BoundaryKind.CLASS_DEFAULT_FIELD, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("UNTYPED_CLASS_INPUT", BoundaryKind.UNTYPED_CLASS_INPUT, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("OPTIONAL_FIELD_READ", BoundaryKind.OPTIONAL_FIELD_READ, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("CONTEXTUAL_TABLE_READ", BoundaryKind.CONTEXTUAL_TABLE_READ, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("IMPORTED_MEMBER_READ", BoundaryKind.IMPORTED_MEMBER_READ, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("FUNCTION_PARAMETER scalar", BoundaryKind.FUNCTION_PARAMETER, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("FUNCTION_PARAMETER function", BoundaryKind.FUNCTION_PARAMETER,
                SYNC_STR_TO_STR, FailurePolicyId.FUNCTION_SIGNATURE),
            new Cell("FUNCTION_RETURN", BoundaryKind.FUNCTION_RETURN, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("HOST_TO_DEAL", BoundaryKind.HOST_TO_DEAL, STRING,
                FailurePolicyId.HOST_SYNC_RETURN),
            new Cell("STDLIB_RETURN", BoundaryKind.STDLIB_RETURN, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("EXTERNAL_RETURN", BoundaryKind.EXTERNAL_RETURN, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("JSON_FROM_FIELD", BoundaryKind.JSON_FROM_FIELD, STRING,
                FailurePolicyId.JSON_FROM_NULL),
            new Cell("JSON_TO_FIELD", BoundaryKind.JSON_TO_FIELD, STRING,
                FailurePolicyId.JSON_TO_ERROR),
            new Cell("MODULE_EXPORT", BoundaryKind.MODULE_EXPORT, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("DEAL_TO_HOST physical check", BoundaryKind.DEAL_TO_HOST, STRING,
                FailurePolicyId.HOST_PARAMETER),
            new Cell("STDLIB_PARAMETER", BoundaryKind.STDLIB_PARAMETER, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new Cell("EXTERNAL_PARAMETER", BoundaryKind.EXTERNAL_PARAMETER, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR));

        // Closed-set pin: every one of the 25 closed BoundaryKind values
        // appears with RuntimeValidation (the 24 non-proof cells plus a
        // physical check on the proof-eligible DEAL_TO_HOST cell), so a
        // kind added to the closed enum without a pinned cell — or a cell
        // removed here — fails the suite instead of passing silently.
        Set<BoundaryKind> pinnedKinds = new LinkedHashSet<>();
        for (Cell cell : cells) {
            pinnedKinds.add(cell.kind());
        }
        Set<BoundaryKind> allKinds = new LinkedHashSet<>(List.of(BoundaryKind.values()));
        check(pinnedKinds.equals(allKinds),
            "RuntimeValidation is pinned on all " + BoundaryKind.values().length
                + " closed BoundaryKind values (24 non-proof cells + the DEAL_TO_HOST physical "
                + "check); missing: "
                + allKinds.stream().filter(k -> !pinnedKinds.contains(k)).toList());

        List<SemanticOp> ops = new ArrayList<>();
        int cellIndex = 1;
        for (Cell cell : cells) {
            ops.add(boundaryWith(nextOpId(), cell.kind(), cell.descriptor(), cell.policy(),
                new BoundaryRealization.RuntimeValidation("check-" + cellIndex), null));
            cellIndex++;
        }
        LoweredModuleUnit unit = unit(ops);
        BoundaryRealizationReport report = reportOf(ops);
        check(report.realizations().size() == cells.size(),
            "the report records one realization per cell");
        assertComplete(BoundaryRealizationReport.complete(unit, report),
            "RuntimeValidation on every cell family");
        assertComplete(BoundaryRealizationReport.complete(unit, report),
            "repeated completion is deterministic");

        // RepresentationProof on the only admissible cell.
        SemanticOp proved = boundaryWith(nextOpId(), BoundaryKind.DEAL_TO_HOST, STRING,
            FailurePolicyId.HOST_PARAMETER,
            new BoundaryRealization.RepresentationProof("jvm-method-signature"), null);
        LoweredModuleUnit provedUnit = unit(List.of(proved));
        assertComplete(BoundaryRealizationReport.complete(provedUnit, reportOf(List.of(proved))),
            "RepresentationProof on DEAL_TO_HOST + HOST_PARAMETER");
    }

    // =========================================================================
    // 2. Pinned negatives: missing, extra, mismatch, proof cell, empty id
    // =========================================================================

    static void testMissingEntry() {
        System.out.println("-- Missing entry --");

        SemanticOp boundary = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-1"), null);
        Optional<CompilerDiagnostic> diagnostic =
            BoundaryRealizationReport.complete(unit(List.of(boundary)),
                BoundaryRealizationReport.empty());
        assertE6005(diagnostic, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISSING,
            "OpId(mod.a#" + boundary.opId().id() + ")", "VARIABLE_DECLARATION",
            "no recorded realization");
    }

    static void testExtraEntry() {
        System.out.println("-- Extra entry --");

        SemanticOp boundary = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-1"), null);
        OpId extra = new OpId(MOD, 91);
        Map<OpId, BoundaryRealization> map = new LinkedHashMap<>();
        map.put(boundary.opId(), new BoundaryRealization.RuntimeValidation("check-1"));
        map.put(extra, new BoundaryRealization.RuntimeValidation("check-91"));
        Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(
            unit(List.of(boundary)), new BoundaryRealizationReport(map));
        assertE6005(diagnostic, BoundaryRealizationReport.BOUNDARY_REALIZATION_EXTRA,
            "OpId(mod.a#91)", "no matching BOUNDARY op");
    }

    static void testMismatchedEntry() {
        System.out.println("-- Mismatched entry --");

        // checkId differs.
        SemanticOp boundary = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-a"), null);
        Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(
            unit(List.of(boundary)), new BoundaryRealizationReport(Map.of(boundary.opId(),
                new BoundaryRealization.RuntimeValidation("check-b"))));
        assertE6005(diagnostic, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISMATCH,
            "OpId(mod.a#" + boundary.opId().id() + ")", "RuntimeValidation", "check-a",
            "check-b");

        // Realization form differs (RuntimeValidation payload vs proof entry).
        SemanticOp boundary2 = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-a"), null);
        Optional<CompilerDiagnostic> diagnostic2 = BoundaryRealizationReport.complete(
            unit(List.of(boundary2)), new BoundaryRealizationReport(Map.of(boundary2.opId(),
                new BoundaryRealization.RepresentationProof("proof-a"))));
        assertE6005(diagnostic2, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISMATCH,
            "OpId(mod.a#" + boundary2.opId().id() + ")", "RepresentationProof", "check-a");

        // proofKind differs on the admissible cell (mismatch wins over
        // cell eligibility — entry equality is checked first).
        SemanticOp boundary3 = boundaryWith(nextOpId(), BoundaryKind.DEAL_TO_HOST, STRING,
            FailurePolicyId.HOST_PARAMETER,
            new BoundaryRealization.RepresentationProof("proof-a"), null);
        Optional<CompilerDiagnostic> diagnostic3 = BoundaryRealizationReport.complete(
            unit(List.of(boundary3)), new BoundaryRealizationReport(Map.of(boundary3.opId(),
                new BoundaryRealization.RepresentationProof("proof-b"))));
        assertE6005(diagnostic3, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISMATCH,
            "OpId(mod.a#" + boundary3.opId().id() + ")", "proof-a", "proof-b");
    }

    static void testProofNotAdmissible() {
        System.out.println("-- RepresentationProof on non-admissible cells --");

        record ProofCell(String what, BoundaryKind kind, RuntimeDescriptor descriptor,
                         FailurePolicyId policy) { }
        List<ProofCell> cells = List.of(
            new ProofCell("FUNCTION_PARAMETER", BoundaryKind.FUNCTION_PARAMETER, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new ProofCell("ARRAY_ELEMENT_READ", BoundaryKind.ARRAY_ELEMENT_READ, STRING,
                FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR),
            new ProofCell("STDLIB_RETURN", BoundaryKind.STDLIB_RETURN, STRING,
                FailurePolicyId.TYPE_DESCRIPTOR),
            new ProofCell("DEAL_TO_HOST with the wrong policy", BoundaryKind.DEAL_TO_HOST,
                STRING, FailurePolicyId.TYPE_DESCRIPTOR));
        for (ProofCell cell : cells) {
            SemanticOp boundary = boundaryWith(nextOpId(), cell.kind(), cell.descriptor(),
                cell.policy(), new BoundaryRealization.RepresentationProof("proof-1"), null);
            Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(
                unit(List.of(boundary)), reportOf(List.of(boundary)));
            assertE6005(diagnostic,
                BoundaryRealizationReport.BOUNDARY_REALIZATION_PROOF_NOT_ADMISSIBLE,
                "OpId(mod.a#" + boundary.opId().id() + ")", cell.kind().name(),
                cell.policy().name(), "does not admit RepresentationProof");
        }
    }

    static void testEmptyIdentifiers() {
        System.out.println("-- Empty checkId/proofKind --");

        SemanticOp validation = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation(""), null);
        Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(
            unit(List.of(validation)), reportOf(List.of(validation)));
        assertE6005(diagnostic, BoundaryRealizationReport.BOUNDARY_REALIZATION_EMPTY_IDENTIFIER,
            "OpId(mod.a#" + validation.opId().id() + ")", "empty checkId");

        SemanticOp proof = boundaryWith(nextOpId(), BoundaryKind.DEAL_TO_HOST, STRING,
            FailurePolicyId.HOST_PARAMETER, new BoundaryRealization.RepresentationProof(""),
            null);
        Optional<CompilerDiagnostic> diagnostic2 = BoundaryRealizationReport.complete(
            unit(List.of(proof)), reportOf(List.of(proof)));
        assertE6005(diagnostic2, BoundaryRealizationReport.BOUNDARY_REALIZATION_EMPTY_IDENTIFIER,
            "OpId(mod.a#" + proof.opId().id() + ")", "empty proofKind");
    }

    static void testFirstFailureOrder() {
        System.out.println("-- Deterministic first-failure order --");

        // Unit op order first: op 1 is complete, op 2's entry is missing.
        SemanticOp first = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-1"), null);
        SemanticOp second = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_ASSIGNMENT,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-2"), null);
        Map<OpId, BoundaryRealization> partial = new LinkedHashMap<>();
        partial.put(first.opId(), new BoundaryRealization.RuntimeValidation("check-1"));
        Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(
            unit(List.of(first, second)), new BoundaryRealizationReport(partial));
        assertE6005(diagnostic, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISSING,
            "OpId(mod.a#" + second.opId().id() + ")");

        // Within one op: mismatch wins over proof-cell eligibility.
        SemanticOp mismatchedProof = boundaryWith(nextOpId(), BoundaryKind.FUNCTION_PARAMETER,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-3"), null);
        Optional<CompilerDiagnostic> diagnostic2 = BoundaryRealizationReport.complete(
            unit(List.of(mismatchedProof)), new BoundaryRealizationReport(Map.of(
                mismatchedProof.opId(), new BoundaryRealization.RepresentationProof("proof-3"))));
        assertE6005(diagnostic2, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISMATCH,
            "OpId(mod.a#" + mismatchedProof.opId().id() + ")");

        // Proof-cell eligibility wins over a later op's missing entry
        // (unit op order).
        SemanticOp badProof = boundaryWith(nextOpId(), BoundaryKind.FUNCTION_PARAMETER,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RepresentationProof("proof-4"), null);
        SemanticOp later = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-5"), null);
        Map<OpId, BoundaryRealization> twoOfThree = new LinkedHashMap<>();
        twoOfThree.put(badProof.opId(), new BoundaryRealization.RepresentationProof("proof-4"));
        twoOfThree.put(later.opId(), new BoundaryRealization.RuntimeValidation("check-5"));
        Optional<CompilerDiagnostic> diagnostic3 = BoundaryRealizationReport.complete(
            unit(List.of(badProof, later)), new BoundaryRealizationReport(twoOfThree));
        assertE6005(diagnostic3,
            BoundaryRealizationReport.BOUNDARY_REALIZATION_PROOF_NOT_ADMISSIBLE,
            "OpId(mod.a#" + badProof.opId().id() + ")");
    }

    // =========================================================================
    // 3. Preservation + failed-emission convention
    // =========================================================================

    static void testValidatorRuleSetPreservation() {
        System.out.println("-- Validator rule-set preservation (no new rule) --");

        check(SemanticIrValidator.RULES.equals(List.of(
                "R-COVERAGE", "R-ENUM", "R-CAPABILITY", "R-POLICY-KIND", "R-BOUNDARY-TRIPLE",
                "R-ELIDED-PLACEMENT", "R-FUNCTION-BINDING", "R-EXTERNAL-ENTRY", "R-ALIAS-CYCLE",
                "R-TOKEN-REUSE", "R-PRIVATE-STEP", "R-RESERVED-NAME", "R-DIGEST", "R-PROFILE")),
            "SemanticIrValidator.RULES is still the exact closed 14-condition list in the S6 "
                + "enumeration order; got " + SemanticIrValidator.RULES);
        check(SemanticIrValidator.RULES.size() == 14,
            "exactly 14 rules — the completion predicate added no validator rule");
    }

    static void testFailedEmissionConvention() {
        System.out.println("-- ModuleEmissionResult.failed() convention --");

        SemanticOp boundary = boundaryWith(nextOpId(), BoundaryKind.VARIABLE_DECLARATION,
            STRING, FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-1"), null);
        Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(
            unit(List.of(boundary)), BoundaryRealizationReport.empty());
        check(diagnostic.isPresent(), "the failing report produces its diagnostic");
        ModuleEmissionResult result = new ModuleEmissionResult(List.of(), List.of(diagnostic.get()),
            null, BoundaryRealizationReport.empty(), null);
        check(result.failed(), "a module whose report fails completion reports failure");
        check(result.stagedArtifacts().isEmpty(),
            "a module whose report fails completion publishes no staged artifact set");
    }

    // =========================================================================
    // 4. Combined behavior with T1 (DescriptorService) and T2 (BoundaryExecutor)
    // =========================================================================

    static void testCombinedBehaviorWithT1T2() {
        System.out.println("-- Combined behavior: descriptor -> boundary -> realization report --");

        OpId callOp = nextOpId();
        OpId paramBoundary = nextOpId();
        OpId returnBoundary = nextOpId();
        OpId declBoundary = nextOpId();
        OpId assignBoundary = nextOpId();

        List<SemanticOp> ops = new ArrayList<>();
        // The proved host-parameter cell (DEAL_TO_HOST + HOST_PARAMETER).
        ops.add(boundaryWith(paramBoundary, BoundaryKind.DEAL_TO_HOST, INT,
            FailurePolicyId.HOST_PARAMETER,
            new BoundaryRealization.RepresentationProof("jvm-method-signature"), callOp));
        // The host sync-return cell.
        ops.add(boundaryWith(returnBoundary, BoundaryKind.HOST_TO_DEAL, INT,
            FailurePolicyId.HOST_SYNC_RETURN,
            new BoundaryRealization.RuntimeValidation("check-return"), callOp));
        // Two descriptor-kind-rule runtime cells.
        ops.add(boundaryWith(declBoundary, BoundaryKind.VARIABLE_DECLARATION, STRING,
            FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-decl"), null));
        ops.add(boundaryWith(assignBoundary, BoundaryKind.VARIABLE_ASSIGNMENT, STRING,
            FailurePolicyId.TYPE_DESCRIPTOR,
            new BoundaryRealization.RuntimeValidation("check-assign"), null));
        // The enclosing CALL(HOST).
        ops.add(opWith(callOp, SemanticOpKind.CALL,
            new KindPayload.CallPayload(CallMode.HOST,
                new KindPayload.CallCallee.Static(new FunctionExecutionBinding.HostFunction(
                    HOST_MOD, "f", SIG_II)),
                SIG_II, List.of(paramBoundary), returnBoundary, null, null),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));

        LoweredModuleUnit unit = unit(ops);

        // The synthetic unit is validator-accepted IR (the closed-table
        // triples; R-BOUNDARY-TRIPLE among the 14 rules).
        assertPass(FACTS, unit, "the combined synthetic unit");

        // Execute the boundary cells through BoundaryExecutor (T2).
        // The proved cell skips check logic: a view that would fail the
        // check still passes.
        BoundaryOutcome proved = BoundaryExecutor.execute(FailurePolicyId.HOST_PARAMETER, INT,
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.parameter(1),
            new BoundaryRealization.RepresentationProof("jvm-method-signature"));
        check(proved instanceof BoundaryOutcome.Pass,
            "the proved cell passes without any check logic (wrong view included)");

        BoundaryOutcome decl = BoundaryExecutor.check(FailurePolicyId.TYPE_DESCRIPTOR, STRING,
            BoundaryValueView.of(ActualKind.STRING), BoundaryContext.none());
        check(decl instanceof BoundaryOutcome.Pass,
            "the passing RuntimeValidation cell publishes the same semantic value");
        check(((BoundaryOutcome.Pass) decl).value() instanceof BoundaryValueView,
            "the passing cell publishes the closed value view");

        BoundaryOutcome assign = BoundaryExecutor.check(FailurePolicyId.TYPE_DESCRIPTOR, STRING,
            BoundaryValueView.of(ActualKind.INT), BoundaryContext.none());
        check(assign instanceof BoundaryOutcome.Fail,
            "the failing RuntimeValidation cell projects the pinned failure");
        if (assign instanceof BoundaryOutcome.Fail failOutcome) {
            check(failOutcome.failure().code() == DiagnosticCode.E8001,
                "the failing cell projects E8001 (got " + failOutcome.failure().code() + ")");
            check(failOutcome.failure().policy() == FailurePolicyId.TYPE_DESCRIPTOR,
                "the failing cell names TYPE_DESCRIPTOR");
        }

        BoundaryOutcome ret = BoundaryExecutor.check(FailurePolicyId.HOST_SYNC_RETURN, INT,
            BoundaryValueView.ofInt(7), BoundaryContext.none());
        check(ret instanceof BoundaryOutcome.Pass, "the sync-return cell passes");

        // The emitter-filled report (one entry per boundary, equal to each
        // op payload's realization) completes.
        BoundaryRealizationReport report = reportOf(ops);
        check(report.realizations().size() == 4,
            "the report records one realization per boundary op");
        assertComplete(BoundaryRealizationReport.complete(unit, report),
            "the completed report of the combined unit");

        // A variant with one entry dropped asserts the E6005 naming that
        // op — the chain fails when any constituent is broken.
        Map<OpId, BoundaryRealization> dropped = new LinkedHashMap<>(report.realizations());
        dropped.remove(assignBoundary);
        Optional<CompilerDiagnostic> diagnostic = BoundaryRealizationReport.complete(unit,
            new BoundaryRealizationReport(dropped));
        assertE6005(diagnostic, BoundaryRealizationReport.BOUNDARY_REALIZATION_MISSING,
            "OpId(mod.a#" + assignBoundary.id() + ")");
    }
}
