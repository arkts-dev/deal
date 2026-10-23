package deal.test;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.diagnostics.DiagnosticCode;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.RawCoverage;
import deal.semantic.ir.RawOp;
import deal.semantic.ir.RawSourceRef;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.InternalResultType;
import deal.semantic.ir.KindPayload;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.ir.ClassBindingFacts;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIrTextDecodeException;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ControlFlowValidator;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.ValueId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SemanticIrValidatorTest {

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // Shared synthetic fixtures
    // =========================================================================

    private static final ModuleId MOD = new ModuleId("mod.a");
    private static final ModuleId MOD_B = new ModuleId("mod.b");
    private static final String IFACE = "interface-digest-1";
    private static final String REGISTRY = "capability-registry-hash-1";
    private static final SemanticIrValidator.ComparisonFacts FACTS =
        new SemanticIrValidator.ComparisonFacts(IFACE, SemanticProfile.DEAL_V1_3_INT32, REGISTRY);
    private static final String LCH =
        LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32, REGISTRY);

    private static final RuntimeDescriptor INT = RuntimeDescriptor.Number.INSTANCE;
    private static final RuntimeDescriptor BOOL = RuntimeDescriptor.Boolean.INSTANCE;
    private static final RuntimeDescriptor STR = RuntimeDescriptor.String.INSTANCE;
    private static final RuntimeDescriptor.Func SIG_II = new RuntimeDescriptor.Func(List.of(INT), INT);
    private static final RuntimeDescriptor.Func SIG_II_ASYNC =
        new RuntimeDescriptor.Func(List.of(INT), INT, true);

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

    private static SemanticOp op(SemanticOpKind kind, KindPayload payload, SemanticValue result,
            OpResultType resultType, FailurePolicyId policy, OpId parent) {
        return opWith(nextOpId(), kind, payload, result, resultType, policy, parent);
    }

    private static SemanticOp boundary(BoundaryKind kind, RuntimeDescriptor descriptor,
            FailurePolicyId policy, OpId parent) {
        return boundaryWith(nextOpId(), kind, descriptor, policy, parent);
    }

    private static SemanticOp boundaryWith(OpId id, BoundaryKind kind, RuntimeDescriptor descriptor,
            FailurePolicyId policy, OpId parent) {
        return opWith(id, SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, nextValue(),
                new BoundaryRealization.RuntimeValidation("check")),
            null, null, policy, parent);
    }

    private static LoweredModuleUnit unit(String module, Set<SemanticCapability> caps,
            Map<ConstructKind, List<SemanticOpKind>> coverage,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings,
            List<SemanticOp> ops) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_3_INT32, new ModuleId(module), IFACE, LCH, caps, coverage,
            Map.of(), Map.of(), new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(),
            bindings, ops, membership(ops, bindings),
            new ClassFactoryRegistry(Map.of()),
            ClassBindingFacts.empty());
    }

    /**
     * The consistent membership of one synthetic unit: every produced op is
     * a member of the module-init block, which is the plan's init block and
     * therefore a root of the block tree, and every lowered-body binding's
     * block is a real (empty) block the binding references. Synthetic
     * negatives that own a membership mutation build their own explicit
     * table.
     */
    private static StructuredBodyTable membership(List<SemanticOp> ops,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings) {
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        List<OpId> ids = new ArrayList<>();
        for (SemanticOp op : ops) {
            ids.add(op.opId());
            opBlocks.put(op.opId(), new BlockId(0));
        }
        blockOps.put(new BlockId(0), ids);
        for (FunctionExecutionBinding binding : bindings.values()) {
            if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                blockOps.putIfAbsent(body.blockId(), List.of());
            }
        }
        return new StructuredBodyTable(blockOps, opBlocks);
    }

    private static LoweredModuleUnit unit(Set<SemanticCapability> caps,
            Map<ConstructKind, List<SemanticOpKind>> coverage,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings,
            List<SemanticOp> ops) {
        return unit("mod.a", caps, coverage, bindings, ops);
    }

    private static LoweredModuleUnit unit(List<SemanticOp> ops) {
        return unit(Set.of(), Map.of(), Map.of(), ops);
    }

    /**
     * The unit rebuilt with another carried interface hash: a synthetic
     * project's units carry the digest of their own carried interface
     * index (the project surface ties the two).
     */
    private static LoweredModuleUnit withInterfaceHash(LoweredModuleUnit unit,
            String interfaceHash) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), interfaceHash, unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(), unit.classLayouts(),
            unit.functions(), unit.moduleInit(), unit.exportPlan(), unit.functionBindings(),
            unit.ops(), unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
    }

    /** The comparison facts of one carried project index (its own digest). */
    private static SemanticIrValidator.ComparisonFacts projectFacts(
            ProjectInterfaceIndex index) {
        return new SemanticIrValidator.ComparisonFacts(index.interfaceIndexDigest(),
            SemanticProfile.DEAL_V1_3_INT32, REGISTRY);
    }

    private static ExecutableLoweredProject profileProject() {
        Map<ModuleId, ExternalModuleInterface> interfaces = new LinkedHashMap<>();
        for (ModuleId module : List.of(MOD, MOD_B)) {
            interfaces.put(module, new ExternalModuleInterface(module, ExternalModuleKind.IMPLEMENTATION,
                List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES));
        }
        ProjectInterfaceIndex index = new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, interfaces);
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        for (ModuleId module : interfaces.keySet()) {
            modules.put(module, withInterfaceHash(unit(module.path(), Set.of(), Map.of(), Map.of(),
                List.of(constOp())), index.interfaceIndexDigest()));
        }
        return new ExecutableLoweredProject(SemanticProfile.DEAL_V1_3_INT32, index, modules,
            MOD, ClassRegistrationSeeds.builtinErrorOnly());
    }

    private static void assertUnitPass(LoweredModuleUnit unit,
            SemanticIrValidator.ComparisonFacts facts, String what) {
        assertPass(SemanticIrValidator.validate(unit, facts), what + " (typed)");
        assertPass(SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(unit), facts),
            what + " (unit text)");
    }

    private static void assertProjectPass(ExecutableLoweredProject project, String what) {
        SemanticIrValidator.ComparisonFacts facts = projectFacts(project.interfaceIndex());
        assertPass(SemanticIrValidator.validate(project, facts), what + " (typed)");
        assertPass(SemanticIrValidator.validateText(SemanticIrValidator.toProjectText(project), facts),
            what + " (project text)");
    }

    private static void assertSameFailure(Optional<CompilerDiagnostic> expected,
            Optional<CompilerDiagnostic> actual, String what) {
        check(expected.isPresent() && actual.isPresent()
                && expected.get().message().equals(actual.get().message()),
            what + ": identical first failure");
        if (expected.isPresent() && actual.isPresent()) {
            checkEq(expected.get().range(), actual.get().range(), what + ": identical range");
        }
    }

    private static void assertPass(Optional<CompilerDiagnostic> diagnostic, String what) {
        check(diagnostic.isEmpty(), what + " passes validation"
            + (diagnostic.isPresent() ? ": " + diagnostic.get().message() : ""));
    }

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
            rule + " is named as the validator rule (exactly one rule per fixture); got \""
                + message + "\"");
        check(message.contains("deal.semantic-ir/2") && message.contains("mod"),
            rule + " message carries the detail fields");
        for (String c : contains) {
            check(message.contains(c), rule + " message contains \"" + c + "\"; got \"" + message + "\"");
        }
    }

    private static SemanticOp constOp() {
        return op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Number(1)),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    /**
     * The real copied USER origin of the retained-origin battery: the
     * offending.deal-style coordinates the ISSUE-0750 contract names.
     */
    private static final SourceSpan REAL_SPAN =
        new SourceSpan("test.deal", 4, 5, 4, 10, 30, 35);

    private static SourceOrigin userOrigin(String file, OpId parent) {
        return new SourceOrigin(file,
            new SourceSpan(file, REAL_SPAN.startLine(), REAL_SPAN.startColumn(),
                REAL_SPAN.endLine(), REAL_SPAN.endColumn(), REAL_SPAN.startScalarOffset(),
                REAL_SPAN.endScalarOffset()),
            SourceOriginKind.USER, new AnchorId(7), parent);
    }

    /** The same op with a different origin (all other components unchanged). */
    private static SemanticOp withOrigin(SemanticOp op, SourceOrigin origin) {
        return new SemanticOp(op.opId(), op.kind(), origin, op.result(), op.resultType(),
            op.operands(), op.operandTypes(), op.payload(), op.failurePolicy(), op.contract());
    }

    /** Replaces the first op's origin object with the supplied one. */
    private static String substituteOrigin(String text, CanonicalJson.Obj origin) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        return serialize(withOps(root, withEntry(op, "origin", origin)));
    }

    /** Replaces the first op's origin span object with the supplied one. */
    private static String substituteOriginSpan(String text, CanonicalJson.Obj span) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        CanonicalJson.Obj origin = (CanonicalJson.Obj) at(op, "origin");
        return serialize(withOps(root,
            withEntry(op, "origin", withEntry(origin, "span", span))));
    }

    /** The prior neutral synthetic origin (the pre-ISSUE-0750 serialized shape). */
    private static CanonicalJson.Obj neutralOrigin() {
        return CanonicalJson.obj(
            CanonicalJson.e("sourceId", CanonicalJson.str("semantic-ir")),
            CanonicalJson.e("span", CanonicalJson.obj(
                CanonicalJson.e("file", CanonicalJson.str("semantic-ir")),
                CanonicalJson.e("startLine", CanonicalJson.intValue(1)),
                CanonicalJson.e("startColumn", CanonicalJson.intValue(1)),
                CanonicalJson.e("endLine", CanonicalJson.intValue(1)),
                CanonicalJson.e("endColumn", CanonicalJson.intValue(1)),
                CanonicalJson.e("startScalarOffset", CanonicalJson.intValue(-1)),
                CanonicalJson.e("endScalarOffset", CanonicalJson.intValue(-1)))),
            CanonicalJson.e("kind", CanonicalJson.str("SYNTHETIC")),
            CanonicalJson.e("anchorId", CanonicalJson.obj(
                CanonicalJson.e("id", CanonicalJson.intValue(0)),
                CanonicalJson.e("type", CanonicalJson.str("anchor")))),
            CanonicalJson.e("parentOpId", CanonicalJson.nullValue()));
    }

    private static void testExceptionalAdmissions() {
        System.out.println("-- Exceptional positive admissions --");

        {
            // INDIRECT → LoweredBody through functionBindings: the
            // registration is produced by the closure that detaches the
            // body, so the complete binding graph admits.
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings = Map.of(
                new FunctionAllocationIdentity(9),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(1), new BlockId(1)));
            Map<FunctionId, LoweredFunction> functions = Map.of(new FunctionId(1),
                new LoweredFunction(new FunctionId(1), SIG_II, List.of(), new BlockId(1),
                    "f"));
            OpId callOp = nextOpId();
            OpId paramBoundary = nextOpId();
            OpId returnBoundary = nextOpId();
            OpId closureOp = nextOpId();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWith(paramBoundary, BoundaryKind.FUNCTION_PARAMETER, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, callOp));
            ops.add(boundaryWith(returnBoundary, BoundaryKind.FUNCTION_RETURN, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, callOp));
            ops.add(opWith(callOp, SemanticOpKind.CALL,
                new KindPayload.CallPayload(CallMode.INDIRECT,
                    new KindPayload.CallCallee.Indirect(new ValueId(9)),
                    SIG_II, List.of(paramBoundary), returnBoundary, null, null, null),
                nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(closureOp, SemanticOpKind.CLOSURE_NEW,
                new KindPayload.ClosureNewPayload(new FunctionId(1), SIG_II, List.of(),
                    new FunctionExecutionBinding.LoweredBody(new FunctionId(1),
                        new BlockId(1))),
                new ValueId(9), SIG_II, FailurePolicyId.NO_DEAL_FAILURE, null));
            assertAdmissionWithFunctions("registered indirect CALL", ops, bindings, functions);
        }
        {
            // EXTERNAL RETAINED_ABI: caller-side return boundary.
            OpId callOp = nextOpId();
            OpId paramBoundary = nextOpId();
            OpId returnBoundary = nextOpId();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWith(paramBoundary, BoundaryKind.EXTERNAL_PARAMETER, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, callOp));
            ops.add(boundaryWith(returnBoundary, BoundaryKind.EXTERNAL_RETURN, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, callOp));
            ops.add(opWith(callOp, SemanticOpKind.CALL,
                new KindPayload.CallPayload(CallMode.EXTERNAL,
                    new KindPayload.CallCallee.Static(
                        new FunctionExecutionBinding.ExternalFunction(MOD, "f", SIG_II,
                            ExternalExecutionOwner.RETAINED_ABI)),
                    SIG_II, List.of(paramBoundary), returnBoundary, null, null, null),
                nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));
            assertAdmission("static EXTERNAL RETAINED_ABI", ops, Map.of());
        }

        // Parameter boundary modes: ELIDED_BY_ADAPTER on the nested source
        // op of an adapter-over-async task.
        {
            OpId outerOp = nextOpId();
            OpId outerParam = nextOpId();
            OpId nestedOp = nextOpId();
            OpId nestedReturn = nextOpId();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWith(outerParam, BoundaryKind.FUNCTION_PARAMETER, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, outerOp));
            ops.add(boundaryWith(nestedReturn, BoundaryKind.FUNCTION_RETURN, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, nestedOp));
            ops.add(opWith(outerOp, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(new FunctionExecutionBinding.AdapterBinding(
                        nextOpId(), CaptureMode.SHARED_CELL,
                        new AdaptSourceRef.SharedCell(new BindingId(1), 0),
                        SIG_II_ASYNC, SIG_II)),
                    AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.RUN,
                    List.of(outerParam), INT, null, null, null),
                new AsyncTokenId.Canonical(4, AsyncTokenOwner.DEAL_BODY_TASK),
                InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(nestedOp, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(new FunctionExecutionBinding.LoweredBody(
                        new FunctionId(1), new BlockId(1))),
                    AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.ELIDED_BY_ADAPTER,
                    List.of(), INT, nestedReturn, null, null),
                new AsyncTokenId.Canonical(5, AsyncTokenOwner.DEAL_BODY_TASK),
                InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, outerOp));
            assertAdmission("nested async ELIDED_BY_ADAPTER", ops, Map.of());
        }

        assertAdmission("realization RepresentationProof", List.of(
            op(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_ASSIGNMENT, INT,
                    nextValue(), new BoundaryRealization.RepresentationProof("jvm-number-proof")),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, null)), Map.of());
    }

    private static void testSelectorPolicies() {
        Map<BinarySelector, FailurePolicyId> binary = Map.of(
            BinarySelector.NUMBER_DIV_IEEE, FailurePolicyId.NO_DEAL_FAILURE,
            BinarySelector.NUMBER_ADD, FailurePolicyId.NO_DEAL_FAILURE,
            BinarySelector.NULLABLE_EQ, FailurePolicyId.NO_DEAL_FAILURE);
        for (var entry : binary.entrySet()) {
            BinarySelector selector = entry.getKey();
            KindPayload payload = new KindPayload.BinaryPayload(selector,
                selector == BinarySelector.NULLABLE_EQ ? INT : null,
                selector == BinarySelector.NULLABLE_EQ ? NullableSide.BOTH : null);
            assertAdmission("binary policy " + selector, List.of(
                op(SemanticOpKind.BINARY, payload, nextValue(), INT, entry.getValue(), null)),
                Map.of());
        }
        Map<StdlibFunctionId, FailurePolicyId> stdlib = Map.of(
            StdlibFunctionId.CONSOLE_LOG, FailurePolicyId.INFRASTRUCTURE_ONLY,
            StdlibFunctionId.STRING_LENGTH, FailurePolicyId.NO_DEAL_FAILURE,
            StdlibFunctionId.TIME_NOW_MILLIS, FailurePolicyId.NO_DEAL_FAILURE,
            StdlibFunctionId.JSON_PARSE, FailurePolicyId.JSON_PARSE_SYNTAX,
            StdlibFunctionId.JSON_STRINGIFY, FailurePolicyId.JSON_TO_ERROR,
            StdlibFunctionId.MATH_SQRT, FailurePolicyId.SQRT_NEGATIVE,
            StdlibFunctionId.STRING_SUBSTRING, FailurePolicyId.SUBSTRING_BOUNDS,
            StdlibFunctionId.MATH_FLOOR, FailurePolicyId.NO_DEAL_FAILURE);
        for (var entry : stdlib.entrySet()) {
            assertAdmission("stdlib policy " + entry.getKey(), List.of(
                op(SemanticOpKind.STDLIB_CALL,
                    new KindPayload.StdlibCallPayload(entry.getKey(), List.of(),
                        SemanticCapability.STDLIB_SEMANTICS),
                    nextValue(), INT, entry.getValue(), null)), Map.of());
        }
    }

    private static void assertAdmission(String what, List<SemanticOp> ops,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings) {
        LoweredModuleUnit unit = unit(Set.of(), Map.of(), bindings, ops);
        assertUnitPass(unit, FACTS, what);
    }

    /** The same positive admission with the unit's function records supplied. */
    private static void assertAdmissionWithFunctions(String what, List<SemanticOp> ops,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings,
            Map<FunctionId, LoweredFunction> functions) {
        LoweredModuleUnit unit = completeUnit(Map.of(), functions,
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), bindings, ops,
            membership(ops, bindings), new ClassFactoryRegistry(Map.of()),
            ClassBindingFacts.empty());
        assertUnitPass(unit, FACTS, what);
    }

    private static void testConstructCoverage() {
        Map<ConstructKind, List<SemanticOpKind>> scalarCoverage =
            Map.of(ConstructKind.SCALAR_LITERAL, List.of(SemanticOpKind.CONST));
        LoweredModuleUnit covered = unit(Set.of(), scalarCoverage, Map.of(), List.of(constOp()));
        assertUnitPass(covered, FACTS, "covered scalar literal");
        LoweredModuleUnit uncovered = unit(Set.of(), scalarCoverage, Map.of(), List.of());
        assertE6005(SemanticIrValidator.validate(uncovered, FACTS),
            "R-COVERAGE", "SCALAR_LITERAL");
        assertE6005(SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(uncovered), FACTS),
            "R-COVERAGE", "SCALAR_LITERAL");
        LoweredModuleUnit timeAbsent = unit(Set.of(),
            Map.of(ConstructKind.STDLIB_TIME_NOW_MILLIS, List.of(SemanticOpKind.STDLIB_CALL)),
            Map.of(), List.of(constOp()));
        assertE6005(SemanticIrValidator.validate(timeAbsent, FACTS),
            "R-COVERAGE", "STDLIB_TIME_NOW_MILLIS");
        assertE6005(SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(timeAbsent), FACTS),
            "R-COVERAGE", "STDLIB_TIME_NOW_MILLIS");
    }

    // =========================================================================
    // 3. Negative corpus: each of the 14 rules asserted exactly once per class
    // =========================================================================

    private static void testNegatives() {
        System.out.println("-- Negative corpus (typed construction) --");

        // R-COVERAGE: the call row recorded, no produced op of any mapped kind.
        Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(ConstructKind.CALL,
            ConstructKind.CALL.mappedOpKinds());
        assertE6005(SemanticIrValidator.validate(
                unit(Set.of(), coverage, Map.of(), List.of(constOp())), FACTS),
            "R-COVERAGE", "construct CALL");

        // R-CAPABILITY: a claimed capability without a required operation.
        assertE6005(SemanticIrValidator.validate(
                unit(Set.of(SemanticCapability.CALLS), Map.of(), Map.of(), List.of(constOp())),
                FACTS),
            "R-CAPABILITY", "CALLS");

        // R-POLICY-KIND: a policy not allowed for its selector.
        assertE6005(SemanticIrValidator.validate(unit(List.of(
                op(SemanticOpKind.BINARY,
                    new KindPayload.BinaryPayload(BinarySelector.NUMBER_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.JSON_PARSE_SYNTAX, null))), FACTS),
            "R-POLICY-KIND", "JSON_PARSE_SYNTAX", "NUMBER_ADD");

        // R-BOUNDARY-TRIPLE: an invocation-kind boundary outside any invocation.
        assertE6005(SemanticIrValidator.validate(unit(List.of(
                boundary(BoundaryKind.FUNCTION_PARAMETER, INT, FailurePolicyId.TYPE_DESCRIPTOR,
                    null))), FACTS),
            "R-BOUNDARY-TRIPLE", "FUNCTION_PARAMETER");

        // R-BOUNDARY-TRIPLE: the refined-operand cell carries the
        // descriptor-kind policy of its own descriptor (a foreign policy
        // fails closed).
        assertE6005(SemanticIrValidator.validate(unit(List.of(
                boundary(BoundaryKind.NARROWED_READ, INT,
                    FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR, null))), FACTS),
            "R-BOUNDARY-TRIPLE", "NARROWED_READ", "descriptor-kind rule");

        // R-BOUNDARY-TRIPLE: the refined-operand cell's input is one
        // identifier read — an input no BINDING_LOAD produces fails closed
        // (the refinement claim is about a binding, never a value). The
        // typed surface and the canonical text surface reject identically.
        {
            LoweredModuleUnit readless = unit(List.of(
                boundary(BoundaryKind.NARROWED_READ, INT,
                    FailurePolicyId.TYPE_DESCRIPTOR, null)));
            assertE6005(SemanticIrValidator.validate(readless, FACTS),
                "R-BOUNDARY-TRIPLE", "NARROWED_READ", "not a binding read");
            assertE6005(SemanticIrValidator.validateText(
                    SemanticIrValidator.toUnitText(readless), FACTS),
                "R-BOUNDARY-TRIPLE", "NARROWED_READ", "not a binding read");
        }

        // R-ELIDED-PLACEMENT: ELIDED_BY_ADAPTER with no parent op.
        {
            OpId startOp = nextOpId();
            OpId returnBoundary = nextOpId();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWith(returnBoundary, BoundaryKind.FUNCTION_RETURN, INT,
                FailurePolicyId.TYPE_DESCRIPTOR, startOp));
            ops.add(opWith(startOp, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(new FunctionExecutionBinding.LoweredBody(
                        new FunctionId(1), new BlockId(1))),
                    AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.ELIDED_BY_ADAPTER,
                    List.of(), INT, returnBoundary, null, null),
                new AsyncTokenId.Canonical(8, AsyncTokenOwner.DEAL_BODY_TASK),
                InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
            assertE6005(SemanticIrValidator.validate(unit(ops), FACTS),
                "R-ELIDED-PLACEMENT", "ELIDED_BY_ADAPTER");
        }

        // R-FUNCTION-BINDING: a function-typed result without a binding.
        assertE6005(SemanticIrValidator.validate(unit(List.of(
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Number(1)),
                    new ValueId(7), SIG_II, FailurePolicyId.NO_DEAL_FAILURE, null))), FACTS),
            "R-FUNCTION-BINDING", "ValueId 7");

        // R-EXTERNAL-ENTRY: a SHARED_BODY ExternalFunction without a recorded
        // EXTERNAL_ENTRY in the callee unit (project-level cross-unit check).
        {
            LoweredModuleUnit caller = unit("mod.a", Set.of(), Map.of(),
                Map.of(new FunctionAllocationIdentity(1),
                    new FunctionExecutionBinding.ExternalFunction(MOD_B, "f", SIG_II,
                        ExternalExecutionOwner.SHARED_BODY)),
                List.of(constOp()));
            LoweredModuleUnit callee = unit("mod.b", Set.of(), Map.of(), Map.of(), List.of());
            ProjectInterfaceIndex index = new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION,
                Map.of(MOD, new ExternalModuleInterface(MOD, ExternalModuleKind.IMPLEMENTATION,
                        List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES),
                    MOD_B, new ExternalModuleInterface(MOD_B, ExternalModuleKind.IMPLEMENTATION,
                        List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES)));
            ExecutableLoweredProject project = new ExecutableLoweredProject(
                SemanticProfile.DEAL_V1_3_INT32, index,
                new LinkedHashMap<>() {{
                    put(MOD, caller);
                    put(MOD_B, callee);
                }}, MOD, ClassRegistrationSeeds.builtinErrorOnly());
            assertE6005(SemanticIrValidator.validate(project, FACTS),
                "R-EXTERNAL-ENTRY", "SHARED_BODY", "\"f\"");
        }

        // R-ALIAS-CYCLE: an alias referent chain revisiting a token identity.
        {
            AsyncTokenId canonical = new AsyncTokenId.Canonical(9, AsyncTokenOwner.DEAL_BODY_TASK);
            AsyncTokenId aliasB = new AsyncTokenId.Alias(1, canonical, deal.semantic.ir.AsyncLinkKind.EXTERNAL_LINK);
            AsyncTokenId aliasA = new AsyncTokenId.Alias(1, aliasB, deal.semantic.ir.AsyncLinkKind.EXTERNAL_LINK);
            OpId awaitOp = nextOpId();
            OpId completionBoundary = nextOpId();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWith(completionBoundary, BoundaryKind.ASYNC_COMPLETION, INT,
                FailurePolicyId.ASYNC_COMPLETION, awaitOp));
            ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(aliasA, INT, completionBoundary),
                nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));
            assertE6005(SemanticIrValidator.validate(unit(ops), FACTS),
                "R-ALIAS-CYCLE", "alias token cycle");
        }

        // R-TOKEN-REUSE: the same token consumed by two AWAITs.
        {
            AsyncTokenId token = new AsyncTokenId.Alias(2,
                new AsyncTokenId.Canonical(10, AsyncTokenOwner.DEAL_BODY_TASK),
                deal.semantic.ir.AsyncLinkKind.EXTERNAL_LINK);
            OpId awaitOne = nextOpId();
            OpId completionOne = nextOpId();
            OpId awaitTwo = nextOpId();
            OpId completionTwo = nextOpId();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWith(completionOne, BoundaryKind.ASYNC_COMPLETION, INT,
                FailurePolicyId.ASYNC_COMPLETION, awaitOne));
            ops.add(opWith(awaitOne, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(token, INT, completionOne),
                nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWith(completionTwo, BoundaryKind.ASYNC_COMPLETION, INT,
                FailurePolicyId.ASYNC_COMPLETION, awaitTwo));
            ops.add(opWith(awaitTwo, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(token, INT, completionTwo),
                nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));
            assertE6005(SemanticIrValidator.validate(unit(ops), FACTS),
                "R-TOKEN-REUSE", "consumed twice");
        }

        // Numeric payloads use the binary64 wire atom, independently of
        // internal integer IDs. Even an integral value cannot use an ID atom.
        {
            String numeric = SemanticIrValidator.toUnitText(unit(List.of(constOp())));
            CanonicalJson.Obj payload = (CanonicalJson.Obj) at(firstOp(parseObj(numeric)), "payload");
            String integerAtom = substituteMirrored(numeric, "payload", "payload",
                withEntry(payload, "value", CanonicalJson.intValue(1)), false);
            Optional<CompilerDiagnostic> rejected = SemanticIrValidator.validateText(integerAtom, FACTS);
            check(rejected.isPresent(), "an integer transport atom cannot stand for a binary64 scalar");
            for (double value : new double[] { -0.0, Double.MIN_VALUE, 9007199254740992.0,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN }) {
                SemanticOp scalar = op(SemanticOpKind.CONST,
                    new KindPayload.ConstPayload(new ScalarValue.Number(value)),
                    nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
                String encoded = SemanticIrValidator.toUnitText(unit(List.of(scalar)));
                CanonicalJson.Num atom = (CanonicalJson.Num) at(
                    (CanonicalJson.Obj) at(firstOp(parseObj(encoded)), "payload"), "value");
                check(Double.doubleToLongBits(value) == Double.doubleToLongBits(atom.value()),
                    "binary64 scalar bits survive canonical transport: " + Double.toHexString(value));
            }
        }

        // R-DIGEST: a wrong (stale) contract digest — T3's recomputation disagrees.
        {
            KindPayload payload = new KindPayload.ConstPayload(new ScalarValue.Number(1));
            OperationContractSnapshot stale = contractFor(SemanticOpKind.CONST, payload, INT,
                List.of(), FailurePolicyId.NO_DEAL_FAILURE,
                "0000000000000000000000000000000000000000000000000000000000000000");
            String recomputed = ContractSnapshotCanonicalizer.digest(stale);
            check(!recomputed.equals(stale.canonicalDigest()),
                "the stale digest differs from T3's recomputation (R-DIGEST precondition)");
            SemanticOp staleOp = new SemanticOp(nextOpId(), SemanticOpKind.CONST, origin(null),
                nextValue(), INT, List.of(), List.of(), payload, FailurePolicyId.NO_DEAL_FAILURE,
                stale);
            Optional<CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(unit(List.of(staleOp)), FACTS);
            assertE6005(diagnostic, "R-DIGEST", recomputed);
        }

        // R-PROFILE: interfaceHash mismatch.
        assertE6005(SemanticIrValidator.validate(
                new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
                    SemanticProfile.DEAL_V1_3_INT32, MOD, "wrong-interface-digest", LCH, Set.of(),
                    Map.of(), Map.of(), Map.of(),
                    new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
                    List.of(constOp()), new StructuredBodyTable(Map.of(), Map.of()),
                    new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty()), FACTS),
            "R-PROFILE", "interfaceHash");

        // R-PROFILE: loweringContextHash computed by T3's helper for a
        // different profile/registry-hash pair.
        {
            String wrongHash = LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32,
                "different-registry-hash");
            check(!wrongHash.equals(LCH), "the foreign-pair hash differs (T3 precondition)");
            assertE6005(SemanticIrValidator.validate(
                    new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
                        SemanticProfile.DEAL_V1_3_INT32, MOD, IFACE, wrongHash, Set.of(),
                        Map.of(), Map.of(), Map.of(),
                        new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(),
                        Map.of(), List.of(constOp()),
                        new StructuredBodyTable(Map.of(), Map.of()),
                        new ClassFactoryRegistry(Map.of()),
                        ClassBindingFacts.empty()), FACTS),
                "R-PROFILE", "loweringContextHash");
        }
    }

    // =========================================================================
    // 4. Text-surface negatives: the pinned invalid-IR injection route
    // =========================================================================

    private static void testTextNegatives() {
        System.out.println("-- Negative corpus (canonical-JSON text surface) --");

        // R-ENUM: an open, non-reserved FailurePolicyId value in both mirror
        // positions (op + contract), digest recomputed through T3.
        {
            LoweredModuleUnit base = unit(List.of(
                op(SemanticOpKind.BINARY,
                    new KindPayload.BinaryPayload(BinarySelector.NUMBER_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null)));
            assertPass(SemanticIrValidator.validate(base, FACTS), "binary injection base");
            String text = substitutePolicy(SemanticIrValidator.toUnitText(base),
                "NO_DEAL_FAILURE", "NOT_A_POLICY");
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-ENUM", "NOT_A_POLICY");
        }

        // R-ENUM: a reserved boundary name in BoundaryKind position (the
        // three reserved names are R-ENUM values per S6's negative list).
        {
            LoweredModuleUnit base = unit(List.of(
                boundary(BoundaryKind.VARIABLE_DECLARATION, INT, FailurePolicyId.TYPE_DESCRIPTOR,
                    null)));
            assertPass(SemanticIrValidator.validate(base, FACTS), "boundary injection base");
            String text = substitutePayloadLeaf(SemanticIrValidator.toUnitText(base),
                "kind", "VARIABLE_DECLARATION", "DEAL_TO_C_FFI");
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-ENUM", "DEAL_TO_C_FFI");
        }

        // R-PRIVATE-STEP: an out-of-set op-kind string.
        {
            LoweredModuleUnit base = unit(List.of(constOp()));
            assertPass(SemanticIrValidator.validate(base, FACTS), "const injection base");
            String text = substituteOpKind(SemanticIrValidator.toUnitText(base),
                "CONST", "PRIVATE_STEP_X");
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-PRIVATE-STEP", "PRIVATE_STEP_X");
        }

        // R-ENUM: an open stdlib selector name in the StdlibFunctionId
        // position (the closed-set rule; TIME_NOW_MILLIS is a member
        // since K7 and its typed form is validated by the construct-row
        // battery).
        {
            LoweredModuleUnit base = unit(List.of(
                op(SemanticOpKind.STDLIB_CALL,
                    new KindPayload.StdlibCallPayload(StdlibFunctionId.CONSOLE_LOG, List.of(),
                        SemanticCapability.STDLIB_SEMANTICS),
                    nextValue(), RuntimeDescriptor.Null.INSTANCE,
                    FailurePolicyId.INFRASTRUCTURE_ONLY, null)));
            assertPass(SemanticIrValidator.validate(base, FACTS), "stdlib injection base");
            String text = substituteStdlibFunction(SemanticIrValidator.toUnitText(base),
                "CONSOLE_LOG", "NOT_A_STDLIB_ID");
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-ENUM", "NOT_A_STDLIB_ID");
        }

        // R-RESERVED-NAME: each of the four reserved policy names in
        // FailurePolicyId position.
        for (String reserved : List.of("EXTERNAL_PARAMETER", "EXTERNAL_RETURN",
                "STDLIB_PARAMETER", "STDLIB_RETURN")) {
            LoweredModuleUnit base = unit(List.of(
                op(SemanticOpKind.BINARY,
                    new KindPayload.BinaryPayload(BinarySelector.NUMBER_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null)));
            String text = substitutePolicy(SemanticIrValidator.toUnitText(base),
                "NO_DEAL_FAILURE", reserved);
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-RESERVED-NAME", reserved);
        }

        String profileText = SemanticIrValidator.toUnitText(unit(List.of(constOp())));
        for (String profile : List.of("LEGACY_SAFE_INT", "DEAL_V1_2_INT32")) {
            assertE6005(SemanticIrValidator.validateText(substituteUnitLeaf(profileText,
                    "semanticProfile", "DEAL_V1_3_INT32", profile), FACTS),
                "R-PROFILE", profile);
        }

        // R-PROFILE: cross-unit profile mismatch through project-level
        // canonical-JSON injection.
        {
            ExecutableLoweredProject project = profileProject();
            ProjectInterfaceIndex index = project.interfaceIndex();
            assertPass(SemanticIrValidator.validate(project, projectFacts(index)),
                "project injection base");
            String text = SemanticIrValidator.toProjectText(project);
            for (String profile : List.of("LEGACY_SAFE_INT", "DEAL_V1_2_INT32")) {
                assertE6005(SemanticIrValidator.validateText(substituteProjectUnitLeaf(text,
                        1, "semanticProfile", "DEAL_V1_3_INT32", profile), projectFacts(index)),
                    "R-PROFILE", profile);
            }
        }

        // Transport-level decode failures are never E6005 and never a rule.
        try {
            SemanticIrValidator.validateText("{not json", FACTS);
            fail("malformed JSON must raise SemanticIrTextDecodeException");
        } catch (deal.semantic.ir.SemanticIrTextDecodeException expected) {
            check(true, "malformed JSON is a transport-level decode failure (never E6005)");
        }
    }

    // ---- text-surface substitution helpers (one leaf + digest recompute) ----

    private static CanonicalJson.Obj parseObj(String text) {
        return (CanonicalJson.Obj) CanonicalJson.parse(text);
    }

    private static CanonicalJson.Value at(CanonicalJson.Obj obj, String key) {
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    private static CanonicalJson.Obj withEntry(CanonicalJson.Obj obj, String key,
            CanonicalJson.Value value) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            entries.add(entry.key().equals(key)
                ? CanonicalJson.e(key, value) : entry);
        }
        return CanonicalJson.obj(entries);
    }

    private static String recomputeContractDigest(CanonicalJson.Obj contract) {
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(withoutKey(contract, "canonicalDigest")));
    }

    private static CanonicalJson.Obj contractWith(CanonicalJson.Obj contract, String key,
            CanonicalJson.Value value) {
        CanonicalJson.Obj updated = withEntry(contract, key, value);
        String digest = recomputeContractDigest(updated);
        return withEntry(updated, "canonicalDigest", CanonicalJson.str(digest));
    }

    private static String substituteMirrored(String text, String opKey, String contractKey,
            CanonicalJson.Value value, boolean selector) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        CanonicalJson.Obj contract = (CanonicalJson.Obj) at(op, "contract");
        if (selector) {
            contract = withEntry(contract, "selector", at((CanonicalJson.Obj) value, "function"));
        }
        return serialize(withOps(root, withEntry(withEntry(op, opKey, value), "contract",
            contractWith(contract, contractKey, value))));
    }

    private static String substitutePolicy(String text, String from, String to) {
        return substituteMirrored(text, "failurePolicy", "failurePolicy", CanonicalJson.str(to), false);
    }

    private static String substitutePayloadLeaf(String text, String key, String from, String to) {
        CanonicalJson.Obj payload = (CanonicalJson.Obj) at(firstOp(parseObj(text)), "payload");
        return substituteMirrored(text, "payload", "payload",
            withEntry(payload, key, CanonicalJson.str(to)), false);
    }

    private static String substituteOpKind(String text, String from, String to) {
        return substituteMirrored(text, "kind", "opKind", CanonicalJson.str(to), false);
    }

    private static String substituteStdlibFunction(String text, String from, String to) {
        CanonicalJson.Obj payload = (CanonicalJson.Obj) at(firstOp(parseObj(text)), "payload");
        return substituteMirrored(text, "payload", "payload",
            withEntry(payload, "function", CanonicalJson.str(to)), true);
    }

    private static String substituteUnitLeaf(String text, String key, String from, String to) {
        CanonicalJson.Obj root = parseObj(text);
        return serialize(withEntry(root, key, CanonicalJson.str(to)));
    }

    private static String substituteProjectUnitLeaf(String text, int unitIndex, String key,
            String from, String to) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Arr modules = (CanonicalJson.Arr) at(root, "modules");
        List<CanonicalJson.Value> items = new ArrayList<>(modules.items());
        CanonicalJson.Obj unit = (CanonicalJson.Obj) items.get(unitIndex);
        items.set(unitIndex, withEntry(unit, key, CanonicalJson.str(to)));
        return serialize(withEntry(root, "modules", CanonicalJson.arr(items)));
    }

    private static CanonicalJson.Obj firstOp(CanonicalJson.Obj root) {
        CanonicalJson.Arr ops = (CanonicalJson.Arr) at(root, "ops");
        return (CanonicalJson.Obj) ops.items().get(0);
    }

    private static CanonicalJson.Obj withOps(CanonicalJson.Obj root, CanonicalJson.Obj op) {
        CanonicalJson.Arr ops = (CanonicalJson.Arr) at(root, "ops");
        List<CanonicalJson.Value> items = new ArrayList<>(ops.items());
        items.set(0, op);
        return withEntry(root, "ops", CanonicalJson.arr(items));
    }

    private static String serialize(CanonicalJson.Value value) {
        return CanonicalJson.serializeText(value);
    }

    // =========================================================================
    // 5. Determinism and multi-defect first-failure order
    // =========================================================================

    private static void testDeterminism() {
        System.out.println("-- Deterministic first-failure order --");

        // Two independent defects in one unit: the S6 enumeration order
        // decides — R-COVERAGE (rule 1) before R-DIGEST (rule 13).
        {
            KindPayload payload = new KindPayload.ConstPayload(new ScalarValue.Number(1));
            OperationContractSnapshot stale = contractFor(SemanticOpKind.CONST, payload, INT,
                List.of(), FailurePolicyId.NO_DEAL_FAILURE,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
            SemanticOp staleOp = new SemanticOp(nextOpId(), SemanticOpKind.CONST, origin(null),
                nextValue(), INT, List.of(), List.of(), payload, FailurePolicyId.NO_DEAL_FAILURE,
                stale);
            Map<ConstructKind, List<SemanticOpKind>> coverage = Map.of(ConstructKind.CALL,
                ConstructKind.CALL.mappedOpKinds());
            LoweredModuleUnit unit = unit(Set.of(), coverage, Map.of(), List.of(staleOp));
            Optional<CompilerDiagnostic> first = SemanticIrValidator.validate(unit, FACTS);
            assertE6005(first, "R-COVERAGE", "construct CALL");
            // Repeated runs are byte-identical diagnostics.
            check(first.get().message().equals(
                    SemanticIrValidator.validate(unit, FACTS).get().message()),
                "repeated validation produces the identical first failure");
        }

        // Repeated text-surface runs are deterministic too.
        {
            LoweredModuleUnit base = unit(List.of(constOp()));
            String text = substituteUnitLeaf(SemanticIrValidator.toUnitText(base),
                "semanticProfile", "DEAL_V1_3_INT32", "LEGACY_SAFE_INT");
            Optional<CompilerDiagnostic> first = SemanticIrValidator.validateText(text, FACTS);
            Optional<CompilerDiagnostic> second = SemanticIrValidator.validateText(text, FACTS);
            check(first.isPresent() && first.get().message().equals(second.get().message()),
                "repeated text-surface validation produces the identical first failure");
        }

        // Typed/text equivalence for a typed negative: the same rule fires on
        // both surfaces.
        {
            LoweredModuleUnit negative = unit(Set.of(SemanticCapability.CALLS), Map.of(),
                Map.of(), List.of(constOp()));
            Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(negative, FACTS);
            Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(negative), FACTS);
            assertE6005(typed, "R-CAPABILITY", "CALLS");
            assertE6005(text, "R-CAPABILITY", "CALLS");
            check(typed.get().message().equals(text.get().message()),
                "both surfaces report the identical E6005 for the same defect");
        }
    }

    // =========================================================================
    // 9. Retained origin metadata and source-attributed validation (D3)
    // =========================================================================

    private static void testOriginTransport() {
        System.out.println("-- Retained origin metadata + source-attributed validation --");

        LoweredModuleUnit base = unit(List.of(
            withOrigin(constOp(), userOrigin("test.deal", null))));
        assertPass(SemanticIrValidator.validate(base, FACTS),
            "the real-origin base unit validates");

        String text = SemanticIrValidator.toUnitText(base);
        check(text.contains("\"sourceId\":\"test.deal\"")
                && text.contains("\"file\":\"test.deal\"")
                && text.contains("\"startLine\":4")
                && text.contains("\"startColumn\":5")
                && text.contains("\"endColumn\":10")
                && text.contains("\"startScalarOffset\":30")
                && text.contains("\"endScalarOffset\":35")
                && text.contains("\"kind\":\"USER\""),
            "typed-to-text conversion retains the complete origin metadata: " + text);

        // Text-to-raw: the supplied origin object survives parsing and
        // re-serialization byte-identically.
        RawUnit parsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));
        checkEq(REAL_SPAN.range(), parsed.ops().get(0).origin().diagnosticRange(),
            "text parsing retains the supplied origin coordinates");
        String reText = CanonicalJson.serializeText(
            ContractSnapshotCanonicalizer.toJson(parsed));
        checkEq(text, reText,
            "text parsing and re-serialization preserve the supplied origin values");

        // Source-attributed validation: the offending op's supplied origin
        // reaches the projected E6005 on the text surface.
        Optional<CompilerDiagnostic> typed = SemanticIrValidator.validateText(text, FACTS);
        assertPass(typed, "the retained-origin text validates unchanged");
        String bad = substitutePolicy(text, "NO_DEAL_FAILURE", "BAD_POLICY");
        Optional<CompilerDiagnostic> failure = SemanticIrValidator.validateText(bad, FACTS);
        assertE6005(failure, "R-ENUM", "BAD_POLICY");
        check(failure.isPresent() && REAL_SPAN.range().equals(failure.get().range()),
            "the R-ENUM failure carries the offending operation's own SOURCE range: "
                + (failure.isEmpty() ? "no failure" : failure.get().range()));
        check(failure.isPresent() && failure.get().notes().isEmpty(),
            "the well-formed origin adds no normalization note");

        // Coordinates supplied only in the text drive the projection.
        String supplied = substitutePolicy(
            text.replace("\"file\":\"test.deal\"", "\"file\":\"offending.deal\""),
            "NO_DEAL_FAILURE", "BAD_POLICY");
        Optional<CompilerDiagnostic> suppliedFailure =
            SemanticIrValidator.validateText(supplied, FACTS);
        assertE6005(suppliedFailure, "R-ENUM", "BAD_POLICY");
        check(suppliedFailure.isPresent()
                && "offending.deal".equals(suppliedFailure.get().range().file())
                && suppliedFailure.get().range().origin() == RangeOrigin.SOURCE
                && suppliedFailure.get().range().startScalarOffset() == 30
                && suppliedFailure.get().range().endScalarOffset() == 35
                && suppliedFailure.get().range().scalarLength() == 5
                && suppliedFailure.get().range().startLine() == 4
                && suppliedFailure.get().range().startColumn() == 5
                && suppliedFailure.get().range().endColumn() == 11,
            "the supplied offending origin is the projected SOURCE range: "
                + (suppliedFailure.isEmpty() ? "no failure"
                    : suppliedFailure.get().range()));

        // The prior neutral synthetic origin stays synthetic (the known
        // file is retained, never turned into a SOURCE anchor).
        String neutral = substitutePolicy(substituteOrigin(text, neutralOrigin()),
            "NO_DEAL_FAILURE", "BAD_POLICY");
        Optional<CompilerDiagnostic> neutralFailure =
            SemanticIrValidator.validateText(neutral, FACTS);
        assertE6005(neutralFailure, "R-ENUM", "BAD_POLICY");
        check(neutralFailure.isPresent()
                && neutralFailure.get().range().origin() == RangeOrigin.SYNTHETIC
                && neutralFailure.get().range().isCanonicalSynthetic()
                && "semantic-ir".equals(neutralFailure.get().range().file()),
            "a neutral synthetic supplied origin stays synthetic with its known file: "
                + (neutralFailure.isEmpty() ? "no failure"
                    : neutralFailure.get().range()));

        // Malformed diagnostic-only coordinates normalize rather than
        // changing the semantic failure selection: the rule and message
        // stay identical, only the range is normalized.
        String badPositions = substitutePolicy(
            text.replace("\"startLine\":4", "\"startLine\":0"),
            "NO_DEAL_FAILURE", "BAD_POLICY");
        Optional<CompilerDiagnostic> positionsFailure =
            SemanticIrValidator.validateText(badPositions, FACTS);
        check(positionsFailure.isPresent() && failure.isPresent()
                && positionsFailure.get().message().equals(failure.get().message()),
            "a malformed supplied position keeps the same first failure and message");
        check(positionsFailure.isPresent() && !positionsFailure.get().notes().isEmpty()
                && !positionsFailure.get().range().equals(failure.get().range()),
            "a malformed supplied position normalizes the range with the existing "
                + "note: " + (positionsFailure.isEmpty() ? "no failure"
                    : positionsFailure.get().range() + " " + positionsFailure.get().notes()));

        CanonicalJson.Obj invertedSpan = CanonicalJson.obj(
            CanonicalJson.e("file", CanonicalJson.str("test.deal")),
            CanonicalJson.e("startLine", CanonicalJson.intValue(4)),
            CanonicalJson.e("startColumn", CanonicalJson.intValue(5)),
            CanonicalJson.e("endLine", CanonicalJson.intValue(4)),
            CanonicalJson.e("endColumn", CanonicalJson.intValue(10)),
            CanonicalJson.e("startScalarOffset", CanonicalJson.intValue(35)),
            CanonicalJson.e("endScalarOffset", CanonicalJson.intValue(30)));
        String invertedOffsets = substitutePolicy(
            substituteOriginSpan(text, invertedSpan), "NO_DEAL_FAILURE", "BAD_POLICY");
        Optional<CompilerDiagnostic> invertedFailure =
            SemanticIrValidator.validateText(invertedOffsets, FACTS);
        check(invertedFailure.isPresent() && failure.isPresent()
                && invertedFailure.get().message().equals(failure.get().message()),
            "inverted supplied offsets keep the same first failure and message");
        check(invertedFailure.isPresent()
                && invertedFailure.get().range().origin() == RangeOrigin.SYNTHETIC
                && !invertedFailure.get().notes().isEmpty(),
            "inverted supplied offsets normalize to the synthetic range, never an "
                + "invented SOURCE anchor");

        // Coordinate-only mutations leave the snapshot digest, validity, and
        // message selection unchanged.
        LoweredModuleUnit shifted = unit(List.of(
            withOrigin(constOp(), userOrigin("other.deal", null))));
        checkEq(ContractSnapshotCanonicalizer.digest(base.ops().get(0).contract()),
            ContractSnapshotCanonicalizer.digest(shifted.ops().get(0).contract()),
            "a coordinate-only origin change leaves the snapshot digest unchanged");
        assertPass(SemanticIrValidator.validate(shifted, FACTS),
            "a coordinate-only origin change leaves validity unchanged");
        check(!SemanticIrValidator.toUnitText(base)
                .equals(SemanticIrValidator.toUnitText(shifted)),
            "a coordinate-only origin change is visible in the transported text alone");

        // The parent-op link keeps its own decoding, independent of the
        // retained diagnostic metadata.
        SemanticOp parent = withOrigin(constOp(), userOrigin("test.deal", null));
        SemanticOp child = withOrigin(nextConst(), userOrigin("test.deal", parent.opId()));
        RawUnit twoOps = ContractSnapshotCanonicalizer.parseUnit(parseObj(
            SemanticIrValidator.toUnitText(unit(List.of(parent, child)))));
        checkEq(parent.opId(), twoOps.ops().get(1).parentOpId(),
            "the parent-op link decodes unchanged beside the retained origin");
        checkEq(REAL_SPAN.range(), twoOps.ops().get(1).origin().diagnosticRange(),
            "the retained origin metadata stays independent of the parent link");

        // Aggregate failures identify no operation and stay originless: a
        // removed capability name in the closed position is an R-ENUM
        // aggregate failure with the canonical synthetic range.
        LoweredModuleUnit aggregateBase = unit("mod.a",
            Set.of(SemanticCapability.FOUNDATION_VALUES), Map.of(), Map.of(),
            List.of(withOrigin(constOp(), userOrigin("test.deal", null))));
        String aggregateText = SemanticIrValidator.toUnitText(aggregateBase)
            .replace("\"FOUNDATION_VALUES\"", "\"STDLIB_TIME_CONFLICT\"");
        Optional<CompilerDiagnostic> aggregateFailure =
            SemanticIrValidator.validateText(aggregateText, FACTS);
        assertE6005(aggregateFailure, "R-ENUM", "STDLIB_TIME_CONFLICT");
        check(aggregateFailure.isPresent()
                && aggregateFailure.get().range().isCanonicalSynthetic()
                && "".equals(aggregateFailure.get().range().file()),
            "an aggregate capability failure borrows no operation origin (the "
                + "canonical empty-file synthetic range): "
                + (aggregateFailure.isEmpty() ? "no failure"
                    : aggregateFailure.get().range()));
    }

    private static SemanticOp nextConst() {
        return op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Number(2)),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static void testTimeAdmission() {
        LoweredModuleUnit base = unit(List.of(
            op(SemanticOpKind.STDLIB_CALL,
                new KindPayload.StdlibCallPayload(StdlibFunctionId.CONSOLE_LOG, List.of(),
                    SemanticCapability.STDLIB_SEMANTICS),
                nextValue(), RuntimeDescriptor.Null.INSTANCE,
                FailurePolicyId.INFRASTRUCTURE_ONLY, null)));
        String timeName = substituteStdlibFunction(SemanticIrValidator.toUnitText(base),
            "CONSOLE_LOG", "TIME_NOW_MILLIS");
        assertE6005(SemanticIrValidator.validateText(timeName, FACTS),
            "R-POLICY-KIND", "TIME_NOW_MILLIS", "INFRASTRUCTURE_ONLY");

        {
            // A claim without evidence: the typed and text surfaces report
            // the identical R-CAPABILITY rejection, originless.
            LoweredModuleUnit forged = unit("mod.a",
                Set.of(SemanticCapability.DESCRIPTORS),
                Map.of(), Map.of(), List.of());
            Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(forged, FACTS);
            assertE6005(typed, "R-CAPABILITY", "DESCRIPTORS");
            Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(forged), FACTS);
            assertE6005(text, "R-CAPABILITY", "DESCRIPTORS");
            check(typed.get().message().equals(text.get().message()),
                "both surfaces report the identical R-CAPABILITY rejection for the "
                    + "evidence-less SIGNED_INT32 claim");
            checkEq(DiagnosticRange.synthetic(""), typed.get().range(),
                "the typed capability claim has no source attribution");
            checkEq(typed.get().range(), text.get().range(),
                "the text capability claim keeps the same originless range");

            // The removed routing-marker capability name is rejected at the
            // closed position (R-ENUM), never admitted.
            String removedName = SemanticIrValidator.toUnitText(forged)
                .replace("\"DESCRIPTORS\"", "\"STDLIB_TIME_CONFLICT\"");
            assertE6005(SemanticIrValidator.validateText(removedName, FACTS),
                "R-ENUM", "STDLIB_TIME_CONFLICT");
        }

        // Fact 2 — no produced op set can satisfy the removed
        // routing-marker claim: the position is closed, so a unit that
        // fully satisfies its other claims still fails at the claim name
        // and no lowering path admits a conflict-claiming module.
        {
            LoweredModuleUnit completed = unit("mod.a",
                Set.of(SemanticCapability.FOUNDATION_VALUES),
                Map.of(), Map.of(), List.of(
                    constOp(),
                    op(SemanticOpKind.UNARY,
                        new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),
                        nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
                    op(SemanticOpKind.BINARY,
                        new KindPayload.BinaryPayload(BinarySelector.NUMBER_ADD, null, null),
                        nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null),
                    op(SemanticOpKind.STRING_CONCAT,
                        new KindPayload.StringConcatPayload(List.of(nextValue())),
                        nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null)));
            check(SemanticIrValidator.validate(completed, FACTS).isEmpty(),
                "the fully-evidenced base unit passes R-CAPABILITY: "
                    + SemanticIrValidator.validate(completed, FACTS).map(
                        CompilerDiagnostic::message).orElse("no failure"));
            String forgedText = SemanticIrValidator.toUnitText(completed)
                .replace("\"FOUNDATION_VALUES\"",
                    "\"FOUNDATION_VALUES\",\"STDLIB_TIME_CONFLICT\"");
            assertE6005(SemanticIrValidator.validateText(forgedText, FACTS),
                "R-ENUM", "STDLIB_TIME_CONFLICT");
        }
    }

    // =========================================================================
    // 8. The complete deal.semantic-ir/2 unit transport (ISSUE-0817)
    // =========================================================================

    private static final ClassId LAYOUT_CLASS = new ClassId("src", "Point");
    private static final ClassId FOREIGN_CLASS = new ClassId("src", "Other");

    /** A complete-unit builder carrying the full lowerer-owned companion set. */
    private static LoweredModuleUnit completeUnit(
            Map<ClassId, ClassLayout> classLayouts,
            Map<FunctionId, LoweredFunction> functions,
            ModuleInitPlan moduleInit,
            ExportPlan exportPlan,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings,
            List<SemanticOp> ops,
            StructuredBodyTable bodyTable,
            ClassFactoryRegistry classFactories,
            ClassBindingFacts classBindingFacts) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_3_INT32, MOD, IFACE, LCH, Set.of(), Map.of(),
            classLayouts, functions, moduleInit, exportPlan, bindings, ops, bodyTable,
            classFactories, classBindingFacts);
    }

    private static LoweredModuleUnit transportUnit(Map<ClassId, ClassLayout> layouts,
            Map<FunctionId, LoweredFunction> functions,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings) {
        return completeUnit(layouts, functions, new ModuleInitPlan(List.of(), new BlockId(0)),
            ExportPlan.empty(), bindings, List.of(constOp()), new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty());
    }

    private static CanonicalJson.Value blockIdJson(long id) {
        return CanonicalJson.obj(
            CanonicalJson.e("id", CanonicalJson.intValue((int) id)),
            CanonicalJson.e("type", CanonicalJson.str("block")));
    }

    /** The complete transport fixture: layouts, functions, plans and every binding variant. */
    private static LoweredModuleUnit completeTransportUnit() {
        FunctionId named = new FunctionId(3);
        FunctionId anonymous = new FunctionId(4);
        BindingId captureA = new BindingId(1);
        BindingId captureB = new BindingId(2);
        BlockId body = new BlockId(7);
        BlockId anonymousBody = new BlockId(8);

        Map<ClassId, ClassLayout> layouts = new LinkedHashMap<>();
        layouts.put(LAYOUT_CLASS, new ClassLayout(LAYOUT_CLASS, List.of(
            new ClassLayout.FieldLayout("x", INT, true, ConstructionProvider.LOCAL),
            new ClassLayout.FieldLayout("label", STR, false, ConstructionProvider.HOST_DECLARATION))));
        Map<FunctionId, LoweredFunction> functions = new LinkedHashMap<>();
        functions.put(named, new LoweredFunction(named, SIG_II,
            List.of(new BindingGeneration(captureA, 0),
                new BindingGeneration(captureB, 4)), body, "inc"));
        functions.put(anonymous, new LoweredFunction(anonymous, SIG_II, List.of(),
            anonymousBody, null));

        ModuleInitPlan moduleInit = new ModuleInitPlan(List.of(MOD_B, MOD_B, MOD),
            new BlockId(9));
        ExportPlan exportPlan = new ExportPlan(List.of(
            new ExportPlan.ExportPlanEntry("main", SIG_II, new OpId(MOD, 41)),
            new ExportPlan.ExportPlanEntry("other", INT, new OpId(MOD, 42))));

        OpId adaptOp = new OpId(MOD, 43);
        OpId boundaryOp = new OpId(MOD, 44);
        Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
            new LinkedHashMap<>();
        bindings.put(new FunctionAllocationIdentity(11),
            new FunctionExecutionBinding.LoweredBody(named, body));
        bindings.put(new FunctionAllocationIdentity(12),
            new FunctionExecutionBinding.AdapterBinding(adaptOp, CaptureMode.SHARED_CELL,
                new AdaptSourceRef.SharedCell(captureA, 2), SIG_II, SIG_II_ASYNC));
        bindings.put(new FunctionAllocationIdentity(13),
            new FunctionExecutionBinding.AdapterBinding(adaptOp, CaptureMode.REEVALUATE_THUNK,
                new AdaptSourceRef.Thunk(new BlockId(14),
                    List.of(new BindingGeneration(captureB, 5))), SIG_II, SIG_II_ASYNC));
        bindings.put(new FunctionAllocationIdentity(14),
            new FunctionExecutionBinding.AdapterBinding(adaptOp, CaptureMode.VALUE,
                new AdaptSourceRef.Value(new ValueId(9)), SIG_II, SIG_II_ASYNC));
        bindings.put(new FunctionAllocationIdentity(15),
            new FunctionExecutionBinding.HostFunction(MOD_B, "log", SIG_II));
        bindings.put(new FunctionAllocationIdentity(16),
            new FunctionExecutionBinding.HostFunctionValue(MOD_B, boundaryOp, SIG_II_ASYNC));
        bindings.put(new FunctionAllocationIdentity(17),
            new FunctionExecutionBinding.ExternalFunction(MOD_B, "helper", SIG_II,
                ExternalExecutionOwner.RETAINED_ABI));
        bindings.put(new FunctionAllocationIdentity(19),
            new FunctionExecutionBinding.DynamicFunctionValue(boundaryOp, SIG_II_ASYNC));

        // Both membership directions are transported independently: the
        // opBlocks side is deliberately not the inverse of the blockOps
        // side, and the transport must not rebuild either from the other.
        StructuredBodyTable bodyTable = new StructuredBodyTable(
            Map.of(new BlockId(15), List.of(new OpId(MOD, 51), new OpId(MOD, 52))),
            Map.of(new OpId(MOD, 52), new BlockId(16)));
        ClassFactoryRegistry classFactories =
            new ClassFactoryRegistry(Map.of(new ClassFactoryId(41), new OpId(MOD, 53)));
        ClassBindingFacts bindingFacts = new ClassBindingFacts(
            new LinkedHashSet<>(List.of(captureA)), new LinkedHashSet<>(List.of(captureB)));

        return completeUnit(layouts, functions, moduleInit, exportPlan, bindings,
            List.of(constOp()), bodyTable, classFactories, bindingFacts);
    }

    private static void testCompleteTransport(LoweredModuleUnit unit) {
        RawUnit raw = RawUnit.fromTyped(unit);
        String text = SemanticIrValidator.toUnitText(unit);
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));

        check(text.contains("\"formatVersion\":\"deal.semantic-ir/2\""),
            "the unit header carries deal.semantic-ir/2");
        checkEq(8, reparsed.bindings().size(),
            "all binding variants plus the three adapter sourceRef shapes round-trip");
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(reparsed)),
            "re-serializing the reparsed unit is byte-stable");
        checkEq(raw, reparsed,
            "the complete raw unit round-trips exactly through the canonical text");
        checkEq(unit.bodyTable(), reparsed.bodyTable(), "typed body membership survives");
        checkEq(unit.classFactories(), reparsed.classFactories(), "typed factories survive");
        checkEq(unit.classBindingFacts(), reparsed.classBindingFacts(), "typed binding facts survive");
        check(text.contains("\"exportPlan\":[{\"descriptor\":"),
            "the populated export plan keeps its array form");
        check(text.contains("\"classFactories\":[{\"constructionEntry\":") && text.contains("\"factoryOp\":"),
            "the populated factory registry keeps its required member names");
        check(!text.contains("\"jsonDefaults\""),
            "the retired JSON initializer companion is absent");
        check(text.contains("\"key\":{\"id\":4,\"type\":\"function\"}")
                && text.contains("\"functionId\":{\"id\":4,\"type\":\"function\"}"),
            "function entries emit separate key and functionId members");

        check(text.contains("\"sourceName\":null"),
            "an explicit null sourceName means an anonymous function");
        assertE6005(SemanticIrValidator.validateText(
                text.replace("\"constructionProvider\":\"LOCAL\"", "\"constructionProvider\":\"BOGUS\""),
                FACTS),
            "R-ENUM", "BOGUS");

        check(text.contains("\"materializingBoundaryOpId\":{\"id\":44,\"modulePath\":\"mod.a\","
                + "\"type\":\"op\"},\"type\":\"dynamicFunctionValue\"}"),
            "the dynamic variant preserves the materializingBoundaryOpId wire name");

        // Checked numeric conversion: an id outside the signed32 transport
        // range fails encoding instead of aliasing another identity.
        Map<FunctionAllocationIdentity, FunctionExecutionBinding> hugeBinding = Map.of(
            new FunctionAllocationIdentity((long) Integer.MAX_VALUE + 1),
            new FunctionExecutionBinding.HostFunction(MOD_B, "log", SIG_II));
        RawUnit hugeRaw = RawUnit.fromTyped(completeUnit(Map.of(), Map.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), hugeBinding,
            List.of(), new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty()));
        expectIllegalArgument(() -> ContractSnapshotCanonicalizer.toJson(hugeRaw),
            "an unrepresentable allocationId fails encoding instead of narrowing");

        FunctionId hugeFunction = new FunctionId(1);
        RawUnit hugeGeneration = RawUnit.fromTyped(completeUnit(Map.of(), Map.of(
                hugeFunction, new LoweredFunction(hugeFunction, SIG_II,
                    List.of(new BindingGeneration(new BindingId(1),
                        (long) Integer.MAX_VALUE + 1)), new BlockId(1), "f")),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            List.of(), new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty()));
        expectIllegalArgument(() -> ContractSnapshotCanonicalizer.toJson(hugeGeneration),
            "an unrepresentable capture generation fails encoding instead of narrowing", "generation");
    }

    private static void testTransportIdentityLink() {
        System.out.println("-- Identity-link regression: key/record disagreements survive "
            + "transport and reject on both surfaces --");

        FunctionId keyFunction = new FunctionId(3);
        FunctionId containedFunction = new FunctionId(4);
        Map<ClassId, ClassLayout> layouts = new LinkedHashMap<>();
        layouts.put(FOREIGN_CLASS, new ClassLayout(LAYOUT_CLASS, List.of(
            new ClassLayout.FieldLayout("x", INT, true, ConstructionProvider.LOCAL))));
        Map<FunctionId, LoweredFunction> functions = new LinkedHashMap<>();
        functions.put(keyFunction, new LoweredFunction(containedFunction, SIG_II, List.of(),
            new BlockId(7), "inc"));
        LoweredModuleUnit unit = transportUnit(layouts, functions, Map.of());

        RawUnit raw = RawUnit.fromTyped(unit);
        checkEq(FOREIGN_CLASS, raw.classLayouts().get(0).key(),
            "the typed layout registry key is retained as the raw key");
        checkEq(LAYOUT_CLASS, raw.classLayouts().get(0).layout().classId(),
            "the contained layout classId is retained separately");
        checkEq(keyFunction, raw.functions().get(0).key(),
            "the typed function registry key is retained as the raw key");
        checkEq(containedFunction, raw.functions().get(0).functionId(),
            "the contained functionId is retained separately");

        String text = SemanticIrValidator.toUnitText(unit);
        check(text.contains("\"key\":{\"modulePath\":\"src\",\"name\":\"Other\",\"type\":\"class\"}"
                + ",\"layout\":{\"classId\":{\"modulePath\":\"src\",\"name\":\"Point\","
                + "\"type\":\"class\"}"),
            "both class-layout spellings are present in the transported entry pair");
        check(text.contains("\"key\":{\"id\":3,\"type\":\"function\"},\"sourceName\":\"inc\"}"),
            "no function entry was repaired, replaced or dropped");

        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));
        checkEq(raw.classLayouts(), reparsed.classLayouts(),
            "the layout disagreement survives typed->raw->text->raw unchanged");
        checkEq(raw.functions(), reparsed.functions(),
            "the function disagreement survives typed->raw->text->raw unchanged");
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(reparsed)),
            "re-serializing the disagreeing unit is byte-stable");

        Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(unit, FACTS);
        assertE6005(typed, "R-IDENTITY-LINK", "Other", "Point");
        Optional<CompilerDiagnostic> textSurface = SemanticIrValidator.validateText(text, FACTS);
        assertE6005(textSurface, "R-IDENTITY-LINK");
        assertSameFailure(typed, textSurface, "the transported key/record disagreement");

        // The function-only disagreement rejects through the same rule.
        LoweredModuleUnit functionOnly = transportUnit(Map.of(), functions, Map.of());
        Optional<CompilerDiagnostic> functionFailure =
            SemanticIrValidator.validate(functionOnly, FACTS);
        assertE6005(functionFailure, "R-IDENTITY-LINK", "function registry key",
            "contained functionId");
        assertE6005(SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(functionOnly), FACTS),
            "R-IDENTITY-LINK", "contained functionId");
    }

    private static void testTransportMandatoryEmptiesAndNegatives(String complete) {

        LoweredModuleUnit empty = transportUnit(Map.of(), Map.of(), Map.of());
        String text = SemanticIrValidator.toUnitText(empty);
        for (String fact : List.of("\"classLayouts\":[]", "\"functions\":[]",
                "\"exportPlan\":[]", "\"classFactories\":[]",
                "\"moduleInit\":{\"imports\":[],\"initBlock\":{\"id\":0,\"type\":\"block\"}}",
                "\"bodyTable\":{\"blockOps\":[],\"opBlocks\":[]}",
                "\"classBindingFacts\":{\"importAliases\":[],\"parameters\":[]}")) {
            check(text.contains(fact), "the mandatory empty fact " + fact + " is emitted");
        }
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));
        check(reparsed.classLayouts().isEmpty() && reparsed.functions().isEmpty()
                && reparsed.exportPlan().isEmpty()
                && reparsed.classFactories().factories().isEmpty()
                && reparsed.bodyTable().blockOps().isEmpty()
                && reparsed.bodyTable().opBlocks().isEmpty()
                && reparsed.classBindingFacts().parameters().isEmpty()
                && reparsed.classBindingFacts().importAliases().isEmpty(),
            "the present-empty facts re-decode as real empty facts");
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(reparsed)),
            "the present-empty unit re-serializes byte-identically");

        assertDecodeFailures(text, false,
            new TextCorruption("a /1 envelope rejects with SemanticIrTextDecodeException",
                input -> input.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/1\"")),
            new TextCorruption("any other envelope version rejects",
                input -> input.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/3\"")),
            new TextCorruption("an unknown unit field rejects",
                input -> withNewEntry(input, "futureFact", CanonicalJson.str("x"))),
            new TextCorruption("a mistyped mandatory companion rejects",
                input -> withEntryAt(input, "classLayouts", CanonicalJson.str("not-an-array"))));
        for (String field : List.of("classLayouts", "bodyTable", "classBindingFacts")) {
            expectDecodeFailure(withoutKey(text, field), "an absent " + field + " companion rejects");
        }
        for (String field : List.of("classLayouts", "functions", "functionBindings", "exportPlan")) {
            expectDecodeFailure(duplicateArrayEntry(complete, field), "a duplicate " + field + " identity rejects");
        }
        expectDecodeFailure(withEntryAt(duplicateArrayEntry(complete, "exportPlan"),
                "descriptor", CanonicalJson.str("i32"), "exportPlan", 2),
            "a same-name export row with a different descriptor rejects");
        expectDecodeFailure(withEntryAt(duplicateArrayEntry(complete, "exportPlan"),
                "publishOpId", CanonicalJson.obj(
                    CanonicalJson.e("id", CanonicalJson.intValue(99)),
                    CanonicalJson.e("modulePath", CanonicalJson.str("mod.a")),
                    CanonicalJson.e("type", CanonicalJson.str("op"))),
                "exportPlan", 2),
            "a same-name export row with a different publishing op rejects");
        // Distinct export rows keep their names and producer order, and an
        // empty plan stays an explicit empty plan.
        List<String> exportNames = new ArrayList<>();
        for (RawUnit.RawExportEntry entry : ContractSnapshotCanonicalizer.parseUnit(
                parseObj(complete)).exportPlan()) {
            exportNames.add(entry.name());
        }
        checkEq(List.of("main", "other"), exportNames,
            "distinct export rows decode in producer order");
        check(ContractSnapshotCanonicalizer.parseUnit(parseObj(text)).exportPlan().isEmpty(),
            "the empty export plan stays an explicit empty plan");
        expectDecodeFailure(serialize(withEntry(parseObj(complete), "bodyTable",
                CanonicalJson.obj(
                    CanonicalJson.e("blockOps", CanonicalJson.arr(
                        CanonicalJson.obj(CanonicalJson.e("block", blockIdJson(1)),
                            CanonicalJson.e("ops", CanonicalJson.arr())),
                        CanonicalJson.obj(CanonicalJson.e("block", blockIdJson(1)),
                            CanonicalJson.e("ops", CanonicalJson.arr())))),
                    CanonicalJson.e("opBlocks", CanonicalJson.arr())))),
            "a duplicate bodyTable block rejects");
        expectDecodeFailure(duplicateArrayEntry(complete, "ops"),
            "a duplicate ops opId rejects");
        String differentOp = withEntryAt(complete, "kind", CanonicalJson.str("BOUNDARY"),
            "ops", 0);
        checkEq("BOUNDARY", ContractSnapshotCanonicalizer.parseUnit(parseObj(differentOp))
                .ops().get(0).kind(),
            "a same-opId ops row with different contents decodes alone");
        expectDecodeFailure(withEntryAt(duplicateArrayEntry(complete, "ops"),
                "kind", CanonicalJson.str("BOUNDARY"), "ops", 1),
            "a same-opId ops row with different contents rejects");
        checkEq(1, ContractSnapshotCanonicalizer.parseUnit(parseObj(complete)).ops().size(),
            "the single-row ops control decodes");
        LoweredModuleUnit covered = unit(Set.of(),
            Map.of(ConstructKind.SCALAR_LITERAL, List.of(SemanticOpKind.CONST)),
            Map.of(), List.of(constOp()));
        String coveredText = SemanticIrValidator.toUnitText(covered);
        checkEq("SCALAR_LITERAL", ContractSnapshotCanonicalizer.parseUnit(
                parseObj(coveredText)).coverage().get(0).construct(),
            "the single-row constructCoverage control decodes");
        expectDecodeFailure(duplicateArrayEntry(coveredText, "constructCoverage"),
            "a duplicate constructCoverage construct rejects");
        String differentCoverage = withEntryAt(coveredText, "opKinds",
            CanonicalJson.arr(), "constructCoverage", 0);
        checkEq(0, ContractSnapshotCanonicalizer.parseUnit(parseObj(differentCoverage))
                .coverage().get(0).opKinds().size(),
            "a same-construct coverage row with different opKinds decodes alone");
        expectDecodeFailure(withEntryAt(duplicateArrayEntry(coveredText, "constructCoverage"),
                "opKinds", CanonicalJson.arr(), "constructCoverage", 1),
            "a same-construct coverage row with different opKinds rejects");
        Map<ConstructKind, List<SemanticOpKind>> twoCoverageRows = new LinkedHashMap<>();
        twoCoverageRows.put(ConstructKind.SCALAR_LITERAL, List.of(SemanticOpKind.CONST));
        twoCoverageRows.put(ConstructKind.CALL, ConstructKind.CALL.mappedOpKinds());
        List<String> coverageConstructs = new ArrayList<>();
        for (RawCoverage row : ContractSnapshotCanonicalizer.parseUnit(parseObj(
                SemanticIrValidator.toUnitText(unit(Set.of(), twoCoverageRows, Map.of(),
                    List.of(constOp()))))).coverage()) {
            coverageConstructs.add(row.construct());
        }
        checkEq(List.of("SCALAR_LITERAL", "CALL"), coverageConstructs,
            "distinct coverage rows decode in producer order");
        // The decode failure precedes admission on the validation surface:
        // a duplicated identity yields no decoded result there either.
        for (String duplicate : List.of(duplicateArrayEntry(complete, "ops"),
                duplicateArrayEntry(coveredText, "constructCoverage"))) {
            expectDecodeFailure(() -> SemanticIrValidator.validateText(duplicate, FACTS),
                "a duplicate entry identity is a decode failure on the validation surface");
        }
        // A recognized binding shape keeps its exact member set.
        expectDecodeFailure(withoutKeyAt(complete, "sourceSignature", "functionBindings", 1, "binding"),
            "an adapter binding without sourceSignature rejects");
        expectDecodeFailure(withoutKeyAt(complete, "functionId", "functionBindings", 0, "binding"),
            "a loweredBody binding without functionId rejects");

        for (Object[] path : List.of(
                new Object[] {"classLayouts", 0, "key"},
                new Object[] {"classLayouts", 0, "layout", "classId"},
                new Object[] {"classFactories", 0, "constructionEntry"},
                new Object[] {"functions", 0, "key"},
                new Object[] {"functions", 0, "body"},
                new Object[] {"moduleInit", "imports", 0},
                new Object[] {"ops", 0, "opId"},
                new Object[] {"ops", 0, "result"},
                new Object[] {"functionBindings", 1, "binding", "sourceRef"},
                new Object[] {"functionBindings", 2, "binding", "sourceRef"},
                new Object[] {"functionBindings", 3, "binding", "sourceRef"},
                new Object[] {"functionBindings", 2, "binding", "sourceRef",
                    "capturedBindings", 0, "binding"})) {
            expectDecodeFailure(extraMemberAt(complete, "extra", path),
                "unknown semantic-id/sourceRef member at " + List.of(path));
        }
        for (int i = 0; i < 8; i++) {
            expectDecodeFailure(extraMemberAt(complete, "extra", "functionBindings", i, "binding"),
                "unknown binding member at variant " + i);
        }
        expectDecodeFailure(withoutKeyAt(complete, "name", "classLayouts", 0, "key"),
            "a class semantic-id object without its name rejects");
        expectDecodeFailure(withoutKeyAt(complete, "id", "functionBindings", 0,
                "binding", "functionId"),
            "a function semantic-id object without its id rejects");

        // An imported module identity that fails the typed ModuleId
        // precondition is the documented transport failure, not an
        // unchecked producer exception from the constructor.
        checkEq("mod.b", ContractSnapshotCanonicalizer.parseUnit(parseObj(complete))
                .moduleInit().imports().get(0).path(),
            "the valid nonempty imported module path decodes");
        expectDecodeFailure(withEntryAt(complete, "path", CanonicalJson.str(""),
                "moduleInit", "imports", 0),
            "an empty imported module path rejects as a decode failure");

        // The recognized token semantic-id shapes are part of the ops
        // mapping: their exact member set and checked numeric transport
        // apply on the unit decode path (no producer exception leaks).
        CanonicalJson.Obj tokenCanonical = CanonicalJson.obj(
            CanonicalJson.e("owner", CanonicalJson.str("DEAL_BODY_TASK")),
            CanonicalJson.e("tokenId", CanonicalJson.intValue(0)),
            CanonicalJson.e("type", CanonicalJson.str("tokenCanonical")));
        check(ContractSnapshotCanonicalizer.parseToken(tokenCanonical)
                instanceof AsyncTokenId.Canonical canonical && canonical.tokenId() == 0,
            "the recognized tokenCanonical shape decodes");
        expectDecodeFailure(withEntryAt(text, "result",
                CanonicalJson.obj(
                    CanonicalJson.e("extra", CanonicalJson.bool(true)),
                    CanonicalJson.e("owner", CanonicalJson.str("DEAL_BODY_TASK")),
                    CanonicalJson.e("tokenId", CanonicalJson.intValue(0)),
                    CanonicalJson.e("type", CanonicalJson.str("tokenCanonical"))),
                "ops", 0),
            "an unknown member on a token semantic-id object rejects");
        CanonicalJson.Obj negativeAlias = CanonicalJson.obj(
            CanonicalJson.e("linkKind", CanonicalJson.str("EXTERNAL_LINK")),
            CanonicalJson.e("referent", tokenCanonical),
            CanonicalJson.e("tokenId", CanonicalJson.intValue(-1)),
            CanonicalJson.e("type", CanonicalJson.str("tokenAlias")));
        expectDecodeFailure(withEntryAt(text, "result", negativeAlias, "ops", 0),
            "a negative token id rejects as a decode failure");

        // Malformed syntax is a transport failure, not an admission result.
        try {
            ContractSnapshotCanonicalizer.parseValidationText("{");
            fail("malformed syntax rejects");
        } catch (SemanticIrTextDecodeException expected) {
            passed++;
        }
    }

    private static void expectDecodeFailure(String text, String message) {
        expectDecodeFailure(() -> ContractSnapshotCanonicalizer.parseUnit(parseObj(text)), message);
    }

    private static void expectDecodeFailure(Runnable action, String message) {
        try {
            action.run();
            fail(message + " (no decode failure)");
        } catch (SemanticIrTextDecodeException expected) {
            passed++;
        } catch (RuntimeException other) {
            fail(message + " (expected SemanticIrTextDecodeException, got " + other + ")");
        }
    }

    private record TextCorruption(String message, java.util.function.UnaryOperator<String> edit) {}

    private static void assertDecodeFailures(String text, boolean project, TextCorruption... cases) {
        for (TextCorruption scenario : cases) {
            String corrupted = scenario.edit().apply(text);
            check(!text.equals(corrupted), scenario.message() + " changes the input");
            if (project) {
                expectProjectDecodeFailure(corrupted, scenario.message());
            } else {
                expectDecodeFailure(corrupted, scenario.message());
            }
        }
    }

    private static void expectIllegalArgument(Runnable action, String message, String... contains) {
        try {
            action.run();
            fail(message + " (no encoding failure)");
        } catch (IllegalArgumentException expected) {
            passed++;
            for (String part : contains) {
                check(expected.getMessage().contains(part), message + ": " + expected.getMessage());
            }
        } catch (RuntimeException other) {
            fail(message + " (expected IllegalArgumentException, got " + other + ")");
        }
    }

    private static CanonicalJson.Obj withoutKey(CanonicalJson.Obj obj, String key) {
        List<CanonicalJson.Entry> entries = new ArrayList<>();
        for (CanonicalJson.Entry entry : obj.entries()) {
            if (!entry.key().equals(key)) {
                entries.add(entry);
            }
        }
        return CanonicalJson.obj(entries);
    }

    private static String withoutKey(String text, String key) {
        return serialize(withoutKey(parseObj(text), key));
    }

    private static String withNewEntry(String text, String key, CanonicalJson.Value value) {
        CanonicalJson.Obj root = parseObj(text);
        List<CanonicalJson.Entry> entries = new ArrayList<>(root.entries());
        entries.add(CanonicalJson.e(key, value));
        return serialize(CanonicalJson.obj(entries));
    }

    private static String duplicateArrayEntry(String text, String arrayKey) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Arr arr = (CanonicalJson.Arr) at(root, arrayKey);
        List<CanonicalJson.Value> items = new ArrayList<>(arr.items());
        items.add(items.get(0));
        return serialize(withEntry(root, arrayKey, CanonicalJson.arr(items)));
    }

    /** Adds one unknown framing member to the object reached by a key/index path. */
    private static String extraMemberAt(String text, String member, Object... steps) {
        return serialize(editNested(CanonicalJson.parse(text), 0, steps, obj -> {
            List<CanonicalJson.Entry> entries = new ArrayList<>(obj.entries());
            entries.add(CanonicalJson.e(member, CanonicalJson.bool(true)));
            return CanonicalJson.obj(entries);
        }));
    }

    /** Replaces one field of the object reached by a key/index path. */
    private static String withEntryAt(String text, String key, CanonicalJson.Value value,
            Object... steps) {
        return serialize(editNested(CanonicalJson.parse(text), 0, steps,
            obj -> withEntry(obj, key, value)));
    }

    /** Removes one field from the object reached by a key/index path. */
    private static String withoutKeyAt(String text, String key, Object... steps) {
        return serialize(editNested(CanonicalJson.parse(text), 0, steps,
            obj -> withoutKey(obj, key)));
    }

    private static CanonicalJson.Value editNested(CanonicalJson.Value value, int at,
            Object[] steps, java.util.function.UnaryOperator<CanonicalJson.Obj> edit) {
        if (at == steps.length) {
            return edit.apply((CanonicalJson.Obj) value);
        }
        Object step = steps[at];
        if (step instanceof String key) {
            CanonicalJson.Obj obj = (CanonicalJson.Obj) value;
            List<CanonicalJson.Entry> entries = new ArrayList<>();
            for (CanonicalJson.Entry entry : obj.entries()) {
                entries.add(entry.key().equals(key)
                    ? CanonicalJson.e(key, editNested(entry.value(), at + 1, steps, edit))
                    : entry);
            }
            return CanonicalJson.obj(entries);
        }
        CanonicalJson.Arr arr = (CanonicalJson.Arr) value;
        int index = (Integer) step;
        List<CanonicalJson.Value> items = new ArrayList<>(arr.items());
        items.set(index, editNested(items.get(index), at + 1, steps, edit));
        return CanonicalJson.arr(items);
    }

    private static void testRegistrySourceRefDiscriminator(String complete) {
        String futureCell = complete.replace("\"type\":\"sharedCell\"",
            "\"type\":\"futureCell\"");
        check(!futureCell.equals(complete),
            "the complete fixture carries the sharedCell registry sourceRef");

        RawUnit decoded = ContractSnapshotCanonicalizer.parseUnit(parseObj(futureCell));
        RawSourceRef carried = decoded.bindings().get(1).sourceRef();
        check(carried instanceof RawSourceRef.Unknown,
            "decode preserves the unknown sourceRef discriminator instead of rejecting it");
        if (carried instanceof RawSourceRef.Unknown unknown) {
            checkEq("futureCell", unknown.type(),
                "the unknown discriminator is the carried type");
            checkEq(3, unknown.json().entries().size(),
                "decode preserves the unknown sourceRef members");
        }
        String reserialized = CanonicalJson.serializeText(
            ContractSnapshotCanonicalizer.toJson(decoded));
        check(reserialized.contains("\"type\":\"futureCell\"")
                && reserialized.contains("\"generation\":2")
                && reserialized.contains("\"binding\":{\"id\":1,\"type\":\"binding\"}"),
            "the retained unknown sourceRef re-serializes its discriminator and members");
        checkEq(futureCell, reserialized,
            "the doctored unit re-serializes byte-identically (the unknown discriminator "
                + "is never normalized or dropped)");

        assertE6005(SemanticIrValidator.validateText(futureCell, FACTS),
            "R-ENUM", "futureCell", "AdaptSourceRef");
    }

    /**
     * The transport project's entry unit: the declared class of the carried
     * interface index with its registered structural provider, so the carried
     * index, the unit's layouts and the unit's class
     * registry are one coherent class graph.
     */
    private static LoweredModuleUnit classTransportUnit(String interfaceHash) {
        ClassId point = new ClassId("mod.a", "Point");
        ClassFactoryId constructionEntry = new ClassFactoryId(7);
        ClassLayout layout = new ClassLayout(point, List.of(
            new ClassLayout.FieldLayout("x", INT, true, ConstructionProvider.LOCAL),
            new ClassLayout.FieldLayout("label", STR, false, ConstructionProvider.LOCAL)));
        OpId caller = new OpId(MOD, 60);
        OpId factoryOp = new OpId(MOD, 62);
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(constOp());
        ops.add(opWith(factoryOp, SemanticOpKind.CLASS_FACTORY,
            new KindPayload.ClassFactoryPayload(point, caller),
            new ValueId(63), new RuntimeDescriptor.Class(point),
            FailurePolicyId.CLASS_CONSTRUCTION, null));
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_3_INT32, MOD, interfaceHash, LCH, Set.of(), Map.of(),
            Map.of(point, layout), Map.of(),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            ops, membership(ops, Map.of()),
            new ClassFactoryRegistry(Map.of(constructionEntry, factoryOp)),
            ClassBindingFacts.empty());
    }

    /**
     * The complete {@code deal.semantic-ir/2} project transport
     * (ISSUE-0818): the project text carries the {@code
     * deal.semantic-interface/1} index, the class registration seeds and
     * the dependency-ordered units, and its inverse retains every field —
     * the index digest stays stable, present-empty facts stay present and
     * empty, and every framing violation rejects before any project
     * record exists.
     */
    private static void testProjectTransportAndMandatoryEmpties() {
        System.out.println("-- Complete project transport: the /2 project framing, the "
            + "interface index inverse and the mandatory present-empty facts --");

        ProjectInterfaceIndex index = new ProjectInterfaceIndex(
            ProjectInterfaceIndex.FORMAT_VERSION, new LinkedHashMap<>() {{
                put(MOD, new ExternalModuleInterface(MOD, ExternalModuleKind.IMPLEMENTATION,
                    List.of(new ResolvedImport("b", "mod/b", MOD_B,
                            ExternalModuleKind.IMPLEMENTATION),
                        new ResolvedImport("cfg", "host/cfg", new ModuleId("host.cfg"),
                            ExternalModuleKind.HOST)),
                    List.of(new ExportInterface("main", "() => null")),
                    List.of(new ClassInterface(new ClassId("mod.a", "Point"),
                        List.of(new FieldInterface("x", "number", false, false),
                            new FieldInterface("label", "string", true, true)),
                        new ClassFactoryId(7))),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES));
                put(MOD_B, new ExternalModuleInterface(MOD_B,
                    ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES));
                put(new ModuleId("host.cfg"), new ExternalModuleInterface(
                    new ModuleId("host.cfg"), ExternalModuleKind.HOST,
                    List.of(), List.of(), List.of(),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES));
            }});
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        modules.put(MOD_B, withInterfaceHash(
            unit("mod.b", Set.of(), Map.of(), Map.of(), List.of(constOp())),
            index.interfaceIndexDigest()));
        modules.put(MOD, classTransportUnit(index.interfaceIndexDigest()));
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_3_INT32, index, modules, MOD,
            ClassRegistrationSeeds.builtinErrorOnly());
        assertPass(SemanticIrValidator.validate(project, projectFacts(index)),
            "the project transport fixture");

        String text = SemanticIrValidator.toProjectText(project);
        check(text.contains("\"formatVersion\":\"deal.semantic-ir/2\""),
            "the project text carries the /2 envelope");
        check(text.contains("\"entryModule\":\"mod.a\""),
            "the project entry module is a plain string");
        check(text.contains("\"interfaceIndex\":{\"formatVersion\":"
                + "\"deal.semantic-interface/1\""),
            "the project text carries the interface index under its unchanged format");
        check(text.contains("\"classSeeds\":[{\"key\":{\"modulePath\":\"\",\"name\":\"Error\""),
            "the project text carries the class registration seeds with their keys");
        ContractSnapshotCanonicalizer.ParsedProject parsed =
            ContractSnapshotCanonicalizer.parseProject(parseObj(text));
        checkEq("deal.semantic-ir/2", parsed.formatVersion(),
            "the parsed project keeps the /2 envelope");
        checkEq("DEAL_V1_3_INT32", parsed.semanticProfile(),
            "the parsed project keeps the profile as supplied");
        checkEq("mod.a", parsed.entryModule(), "the parsed project keeps the entry module");
        checkEq(index, parsed.interfaceIndex(),
            "the interface index inverse retains every module/interface/import/class/field "
                + "fact");
        checkEq(index.interfaceIndexDigest(), parsed.interfaceIndex().interfaceIndexDigest(),
            "the reparsed index keeps its digest");
        checkEq(ClassRegistrationSeeds.builtinErrorOnly(), parsed.classSeeds(),
            "the parsed project keeps the class registration seeds");
        // The carried project keeps its dependency order: mod.b precedes its
        // consumer mod.a, and the host declaration provider carries an index
        // entry without a unit.
        checkEq(List.of("mod.b", "mod.a"), parsed.modulePaths(),
            "the parsed project keeps the dependency-ordered module paths");
        checkEq(RawUnit.fromTyped(modules.get(MOD_B)), parsed.modules().get(0),
            "the first module round-trips every complete unit fact");
        checkEq(RawUnit.fromTyped(modules.get(MOD)), parsed.modules().get(1),
            "the second module round-trips every complete unit fact");
        checkEq(text, serialize(ContractSnapshotCanonicalizer.toJson(parsed)),
            "the reparsed project re-serializes byte-identically");
        assertPass(SemanticIrValidator.validateText(text, projectFacts(index)),
            "the project text surface");

        // Every retained index field is present and non-default in the fixture.
        ExternalModuleInterface parsedA = parsed.interfaceIndex().modules().get(MOD);
        checkEq(List.of("b", "cfg"),
            parsedA.imports().stream().map(ResolvedImport::alias).toList(),
            "the resolved import aliases survive in order");
        checkEq(ExternalModuleKind.HOST, parsedA.imports().get(1).kind(),
            "the resolved import kind survives");
        checkEq(new ModuleId("host.cfg"), parsedA.imports().get(1).resolvedModuleId(),
            "the resolved import target identity survives");
        checkEq("host/cfg", parsedA.imports().get(1).modulePath(),
            "the raw import specifier survives");
        checkEq(List.of("main"),
            parsedA.exports().stream().map(ExportInterface::name).toList(),
            "the export entries survive");
        ClassInterface point = parsedA.classes().get(0);
        checkEq(new ClassId("mod.a", "Point"), point.classId(),
            "the class identity survives");
        checkEq(new ClassFactoryId(7), point.constructionEntry(),
            "the class construction entry survives");
        checkEq(List.of("x", "label"),
            point.fields().stream().map(FieldInterface::name).toList(),
            "the class field order survives");
        check(!point.fields().get(0).optional() && !point.fields().get(0).nullable()
                && point.fields().get(1).optional() && point.fields().get(1).nullable(),
            "the independent optional/nullable structural facts survive");
        checkEq(InitializationMode.ONCE_AFTER_DEPENDENCIES,
            parsed.interfaceIndex().modules().get(MOD_B).initialization(),
            "the initialization mode survives");

        // Mandatory empties: the empty seed set and the empty index entry
        // lists stay present and empty, distinct from absent.
        ExecutableLoweredProject emptySeeds = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_3_INT32, index, modules, MOD,
            new ClassRegistrationSeeds(Map.of()));
        String emptyText = SemanticIrValidator.toProjectText(emptySeeds);
        check(emptyText.contains("\"classSeeds\":[]"),
            "a project with no class registration seeds emits a present-empty list");
        check(emptyText.contains("\"classes\":[],\"exports\":[],\"imports\":[]"),
            "an index entry with no imports/exports/classes emits present-empty lists");
        ContractSnapshotCanonicalizer.ParsedProject emptyParsed =
            ContractSnapshotCanonicalizer.parseProject(parseObj(emptyText));
        check(emptyParsed.classSeeds().registrations().isEmpty(),
            "the present-empty seeds decode as a real empty seed set");
        checkEq(emptyText, serialize(ContractSnapshotCanonicalizer.toJson(emptyParsed)),
            "the present-empty project re-serializes byte-identically");
        assertPass(SemanticIrValidator.validateText(emptyText, projectFacts(index)),
            "the present-empty project text surface");

        // The registration key and the contained layout classId stay separate
        // facts: a disagreement is transported unchanged and never merged into
        // a unit's own classLayouts.
        ClassRegistrationSeeds detachedSeeds = new ClassRegistrationSeeds(Map.of(
            new ClassId("mod.a", "Other"),
            new ClassRegistrationSeeds.ClassRegistration(
                new ClassLayout(new ClassId("mod.a", "Point"), List.of()),
                ConstructionProvider.LOCAL)));
        ExecutableLoweredProject detachedProject = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_3_INT32, index, modules, MOD, detachedSeeds);
        String detachedText = SemanticIrValidator.toProjectText(detachedProject);
        check(detachedText.contains("\"key\":{\"modulePath\":\"mod.a\",\"name\":\"Other\"")
                && detachedText.contains("\"layout\":{\"classId\":{\"modulePath\":\"mod.a\","
                    + "\"name\":\"Point\""),
            "a seed's registration key and contained layout classId stay separate spellings");
        ContractSnapshotCanonicalizer.ParsedProject detachedParsed =
            ContractSnapshotCanonicalizer.parseProject(parseObj(detachedText));
        checkEq(detachedSeeds, detachedParsed.classSeeds(),
            "the seed key/layout disagreement survives typed->text->typed unchanged");
        for (RawUnit raw : detachedParsed.modules()) {
            check(raw.classLayouts().stream()
                    .noneMatch(entry -> entry.layout().fields().isEmpty()),
                "the seeds are never merged into a unit's own classLayouts");
        }
        RawUnit detachedModA = detachedParsed.modules().stream()
            .filter(raw -> raw.modulePath().equals("mod.a")).findFirst().orElse(null);
        check(detachedModA != null, "the detached project keeps the mod.a unit");
        checkEq(RawUnit.fromTyped(modules.get(MOD)).classLayouts(),
            detachedModA == null ? null : detachedModA.classLayouts(),
            "the unit keeps exactly its own declared classLayouts beside the seeds");

        for (String field : List.of("formatVersion", "interfaceIndex", "classSeeds")) {
            expectProjectDecodeFailure(withoutKey(text, field), "an absent project " + field + " rejects");
        }
        assertDecodeFailures(text, true,
            new TextCorruption("a /1 project envelope rejects",
                input -> input.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/1\"")),
            new TextCorruption("any other project envelope version rejects",
                input -> input.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/3\"")),
            new TextCorruption("an unknown project field rejects",
                input -> withNewEntry(input, "futureProjectFact", CanonicalJson.str("x"))),
            new TextCorruption("a duplicate project module identity rejects",
                input -> duplicateArrayEntry(input, "modules")),
            new TextCorruption("an entry module outside the carried modules rejects",
                input -> withEntryAt(input, "entryModule", CanonicalJson.str("mod.absent"))),
            new TextCorruption("a mistyped project modules field rejects",
                input -> withEntryAt(input, "modules", CanonicalJson.str("not-an-array"))),
            new TextCorruption("an interface format bump rejects", input -> withEntryAt(input,
                "formatVersion", CanonicalJson.str("deal.semantic-interface/2"), "interfaceIndex")),
            new TextCorruption("an unknown interface kind rejects", input -> withEntryAt(input,
                "kind", CanonicalJson.str("NOT_A_KIND"), "interfaceIndex", "modules", 0)),
            new TextCorruption("an interface export without its declared type rejects", input ->
                withoutKeyAt(input, "declaredType", "interfaceIndex", "modules", 0, "exports", 0)),
            new TextCorruption("an interface field without its optional member rejects", input ->
                withoutKeyAt(input, "optional", "interfaceIndex", "modules", 0, "classes", 0, "fields", 0)),
            new TextCorruption("an unknown seed owner rejects", input ->
                withEntryAt(input, "owner", CanonicalJson.str("NOT_AN_OWNER"), "classSeeds", 0)),
            new TextCorruption("an unknown seed field owner rejects", input -> withEntryAt(input,
                "constructionProvider", CanonicalJson.str("NOT_AN_OWNER"), "classSeeds", 0, "layout", "fields", 0)),
            new TextCorruption("a seed without its owner member rejects", input ->
                withoutKeyAt(input, "owner", "classSeeds", 0)),
            new TextCorruption("a duplicate class-seed registration key rejects", input ->
                duplicateArrayEntry(input, "classSeeds")));
        // A malformed seed descriptor text is a transport decode failure, not
        // an unchecked parser failure: truncated parameter-list forms
        // normalize to SemanticIrTextDecodeException at the descriptor owner.
        for (String truncated : List.of("(", "(number", "(number,", "(number)->", "async(")) {
            expectProjectDecodeFailure(withEntryAt(text, "descriptor",
                    CanonicalJson.str(truncated), "classSeeds", 0, "layout", "fields", 0),
                "a truncated seed descriptor \"" + truncated + "\" rejects as a "
                    + "transport decode failure");
        }
        // The decode failure precedes admission on the validation surface.
        for (String rejected : List.of(withoutKey(text, "interfaceIndex"),
                text.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/1\""),
                duplicateArrayEntry(text, "modules"),
                withEntryAt(text, "descriptor", CanonicalJson.str("("),
                    "classSeeds", 0, "layout", "fields", 0))) {
            expectDecodeFailure(() -> SemanticIrValidator.validateText(rejected, FACTS),
                "malformed project framing is a decode failure on the validation surface, never an admission result");
        }
    }

    private static void expectProjectDecodeFailure(String text, String message) {
        expectDecodeFailure(() -> ContractSnapshotCanonicalizer.parseProject(parseObj(text)), message);
    }

    // =========================================================================
    // 10. Common admission: membership, registry identity and plan links
    //     (ISSUE-0819)
    // =========================================================================

    private static final ClassId BATTERY_CLASS = new ClassId("src", "Point");
    private static final FunctionId BATTERY_FUNCTION = new FunctionId(3);
    private static final BlockId BATTERY_BODY_BLOCK = new BlockId(7);
    private static final BlockId BATTERY_INIT_BLOCK = new BlockId(9);
    private static final OpId BATTERY_PUBLISH_MAIN = new OpId(MOD, 41);
    private static final OpId BATTERY_PUBLISH_OTHER = new OpId(MOD, 42);
    private static final OpId BATTERY_MODULE_INIT = new OpId(MOD, 50);
    private static final OpId BATTERY_BODY_CONST = new OpId(MOD, 51);
    private static final OpId BATTERY_UNPRODUCED = new OpId(MOD, 99);

    /** The carried project index of the common-admission battery. */
    private static ProjectInterfaceIndex batteryIndex() {
        Map<ModuleId, ExternalModuleInterface> entries = new LinkedHashMap<>();
        entries.put(MOD, new ExternalModuleInterface(MOD, ExternalModuleKind.IMPLEMENTATION,
            List.of(new ResolvedImport("b", "mod/b", MOD_B, ExternalModuleKind.IMPLEMENTATION)),
            List.of(new ExportInterface("main", "() => null"),
                new ExportInterface("other", "i32")),
            List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES));
        entries.put(MOD_B, new ExternalModuleInterface(MOD_B,
            ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
            InitializationMode.ONCE_AFTER_DEPENDENCIES));
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, entries);
    }
    private static Map<ClassId, ClassLayout> batteryLayouts() {
        Map<ClassId, ClassLayout> layouts = new LinkedHashMap<>();
        layouts.put(BATTERY_CLASS, new ClassLayout(BATTERY_CLASS, List.of(
            new ClassLayout.FieldLayout("x", INT, true, ConstructionProvider.LOCAL))));
        return layouts;
    }

    private static Map<FunctionId, LoweredFunction> batteryFunctions() {
        Map<FunctionId, LoweredFunction> functions = new LinkedHashMap<>();
        functions.put(BATTERY_FUNCTION, new LoweredFunction(BATTERY_FUNCTION, SIG_II,
            List.of(), BATTERY_BODY_BLOCK, "f"));
        return functions;
    }

    private static List<SemanticOp> batteryOps() {
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(userOp(opWith(BATTERY_PUBLISH_MAIN, SemanticOpKind.EXPORT_PUBLISH,
            new KindPayload.ExportPublishPayload(MOD, "main", SIG_II, new ValueId(41)),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null)));
        ops.add(userOp(opWith(BATTERY_PUBLISH_OTHER, SemanticOpKind.EXPORT_PUBLISH,
            new KindPayload.ExportPublishPayload(MOD, "other", INT, new ValueId(42)),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null)));
        ops.add(userOp(opWith(BATTERY_MODULE_INIT, SemanticOpKind.MODULE_INIT,
            new KindPayload.ModuleInitPayload(MOD, List.of(MOD_B, MOD_B), BATTERY_INIT_BLOCK),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null)));
        ops.add(userOp(opWith(BATTERY_BODY_CONST, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Number(1)), new ValueId(51), INT,
            FailurePolicyId.NO_DEAL_FAILURE, null)));
        return ops;
    }

    /** The same op with a real USER origin (the range parity probe). */
    private static SemanticOp userOp(SemanticOp op) {
        return withOrigin(op, userOrigin("test.deal", null));
    }

    private static ExportPlan batteryExportPlan() {
        return retargetedExportPlan("main", BATTERY_PUBLISH_MAIN);
    }

    private static ExportPlan retargetedExportPlan(String name, OpId publishOp) {
        return new ExportPlan(List.of(
            new ExportPlan.ExportPlanEntry(name, SIG_II, publishOp),
            new ExportPlan.ExportPlanEntry("other", INT, BATTERY_PUBLISH_OTHER)));
    }

    private static List<SemanticOp> retargetedPublication(ModuleId module, String name) {
        List<SemanticOp> ops = batteryOps();
        ops.set(0, userOp(opWith(BATTERY_PUBLISH_MAIN, SemanticOpKind.EXPORT_PUBLISH,
            new KindPayload.ExportPublishPayload(module, name, SIG_II, new ValueId(41)),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null)));
        return ops;
    }

    private static StructuredBodyTable batteryTable(List<OpId> initOps, List<OpId> bodyOps) {
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        blockOps.put(BATTERY_INIT_BLOCK, initOps);
        blockOps.put(BATTERY_BODY_BLOCK, bodyOps);
        for (OpId op : initOps) {
            opBlocks.put(op, BATTERY_INIT_BLOCK);
        }
        for (OpId op : bodyOps) {
            opBlocks.put(op, BATTERY_BODY_BLOCK);
        }
        return new StructuredBodyTable(blockOps, opBlocks);
    }

    private static StructuredBodyTable batteryBaseTable() {
        return batteryTable(List.of(BATTERY_PUBLISH_MAIN, BATTERY_PUBLISH_OTHER),
            List.of(BATTERY_BODY_CONST));
    }

    /**
     * One complete battery unit: the complete /2 facts with the supplied
     * records, so a mutation rebuilds exactly one record and the transport
     * carries the rest unchanged.
     */
    private static LoweredModuleUnit batteryUnit(
            String interfaceHash,
            Map<ConstructKind, List<SemanticOpKind>> coverage,
            Map<ClassId, ClassLayout> layouts,
            Map<FunctionId, LoweredFunction> functions,
            ModuleInitPlan moduleInit,
            ExportPlan exportPlan,
            List<SemanticOp> ops,
            StructuredBodyTable table) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_3_INT32, MOD, interfaceHash, LCH, Set.of(), coverage,
            layouts, functions, moduleInit, exportPlan, Map.of(), ops, table,
            new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty());
    }

    private static final class BatteryMutation {
        Map<ClassId, ClassLayout> layouts = batteryLayouts();
        Map<FunctionId, LoweredFunction> functions = batteryFunctions();
        ModuleInitPlan moduleInit = new ModuleInitPlan(List.of(MOD_B, MOD_B), BATTERY_INIT_BLOCK);
        ExportPlan exportPlan = batteryExportPlan();
        List<SemanticOp> ops = batteryOps();
        StructuredBodyTable table = batteryBaseTable();
    }

    private static LoweredModuleUnit batteryUnit(String interfaceHash,
            java.util.function.Consumer<BatteryMutation> mutation) {
        BatteryMutation fixture = new BatteryMutation();
        mutation.accept(fixture);
        return batteryUnit(interfaceHash, Map.of(), fixture.layouts, fixture.functions,
            fixture.moduleInit, fixture.exportPlan, fixture.ops, fixture.table);
    }

    private static LoweredModuleUnit batteryUnit(String interfaceHash) {
        return batteryUnit(interfaceHash, fixture -> {});
    }

    private record AdmissionCase(String what, java.util.function.Consumer<BatteryMutation> mutation,
            String rule, String detail) {}

    private static LoweredModuleUnit batteryDependencyUnit(String interfaceHash) {
        OpId constOp = new OpId(MOD_B, 91);
        StructuredBodyTable table = new StructuredBodyTable(
            Map.of(new BlockId(0), List.of(constOp)), Map.of(constOp, new BlockId(0)));
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_3_INT32, MOD_B, interfaceHash, LCH, Set.of(), Map.of(),
            Map.of(), Map.of(), new ModuleInitPlan(List.of(), new BlockId(0)),
            ExportPlan.empty(), Map.of(),
            List.of(opWith(constOp, SemanticOpKind.CONST,
                new KindPayload.ConstPayload(new ScalarValue.Number(2)), new ValueId(91), INT,
                FailurePolicyId.NO_DEAL_FAILURE, null)),
            table, new ClassFactoryRegistry(Map.of()), ClassBindingFacts.empty());
    }

    private static ExecutableLoweredProject batteryProject(LoweredModuleUnit unit,
            ProjectInterfaceIndex index) {
        Map<ModuleId, LoweredModuleUnit> modules = new LinkedHashMap<>();
        modules.put(MOD_B, batteryDependencyUnit(index.interfaceIndexDigest()));
        modules.put(MOD, unit);
        return new ExecutableLoweredProject(SemanticProfile.DEAL_V1_3_INT32, index, modules,
            MOD, ClassRegistrationSeeds.builtinErrorOnly());
    }

    /**
     * The mutation battery: the mutation travels typed -> raw -> text ->
     * raw unchanged, and the typed unit admission, the unit text admission
     * and both project admissions return the same first E6005 (rule,
     * message and range) with no executable or partial result exposed.
     */
    private static void assertCommonAdmissionRejects(String what, LoweredModuleUnit mutated,
            ProjectInterfaceIndex index, String rule, String... contains) {
        SemanticIrValidator.ComparisonFacts facts = projectFacts(index);
        RawUnit raw = RawUnit.fromTyped(mutated);
        String text = SemanticIrValidator.toUnitText(mutated);
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));
        checkEq(raw, reparsed,
            what + ": the mutation survives typed->raw->text->raw unchanged");
        for (RawOp op : raw.ops()) {
            checkEq(op.canonicalDigest(),
                ContractSnapshotCanonicalizer.recomputeSnapshotDigest(op.snapshot()),
                what + ": op " + op.opId() + " keeps a valid contract digest "
                    + "(the rejection is not a digest artifact)");
        }
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(reparsed)),
            what + ": re-serializing the transported mutation is byte-stable");
        Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(mutated, facts);
        Optional<CompilerDiagnostic> unitText = SemanticIrValidator.validateText(text, facts);
        assertE6005(typed, rule, contains);
        assertE6005(unitText, rule, contains);
        assertSameFailure(typed, unitText, what + ": unit text admission");
        ExecutableLoweredProject project = batteryProject(mutated, index);
        Optional<CompilerDiagnostic> typedProject = SemanticIrValidator.validate(project, facts);
        Optional<CompilerDiagnostic> projectText = SemanticIrValidator.validateText(
            SemanticIrValidator.toProjectText(project), facts);
        assertE6005(typedProject, rule, contains);
        assertE6005(projectText, rule, contains);
        assertSameFailure(typed, typedProject, what + ": typed project admission");
        assertSameFailure(typed, projectText, what + ": project text admission");
        // The composed typed production admission (the lowering entry's own
        // chain) rejects the same content with the same first failure, so no
        // production path can expose a project or a companion view for it.
        Optional<CompilerDiagnostic> composed = SemanticLowerer.validateProjectUnit(mutated,
            mutated.bodyTable(), facts, mutated.classBindingFacts(), mutated.classFactories(),
            index.modules().get(MOD), Map.of(), Map.of());
        check(composed.isPresent() && typed.isPresent()
                && composed.get().message().equals(typed.get().message()),
            what + ": the composed typed production admission rejects with the same "
                + "first failure: " + composed.map(CompilerDiagnostic::message)
                    .orElse("admitted"));
    }

    private static void testCommonAdmissionMembershipAndPlans() {
        System.out.println("-- Common admission: membership, registry identity and plan "
            + "links over the complete facts (ISSUE-0819) --");
        ProjectInterfaceIndex index = batteryIndex();
        String hash = index.interfaceIndexDigest();
        SemanticIrValidator.ComparisonFacts facts = projectFacts(index);
        LoweredModuleUnit base = batteryUnit(hash);
        assertUnitPass(base, facts, "the common-admission base unit");
        assertProjectPass(batteryProject(base, index), "the common-admission base project");

        for (AdmissionCase scenario : List.of(
                new AdmissionCase("a deleted opBlocks inverse row", fixture -> {
                    Map<OpId, BlockId> inverse = new LinkedHashMap<>(fixture.table.opBlocks());
                    inverse.remove(BATTERY_PUBLISH_OTHER);
                    fixture.table = new StructuredBodyTable(fixture.table.blockOps(), inverse);
                }, "CONTROL_BLOCK_TREE", "has no inverse membership entry"),
                new AdmissionCase("a deleted blockOps row", fixture -> {
                    Map<BlockId, List<OpId>> blocks = new LinkedHashMap<>(fixture.table.blockOps());
                    blocks.put(BATTERY_INIT_BLOCK, List.of(BATTERY_PUBLISH_MAIN));
                    fixture.table = new StructuredBodyTable(blocks, fixture.table.opBlocks());
                }, "CONTROL_BLOCK_TREE", "listed in no block"),
                new AdmissionCase("an op listed twice in one block", fixture ->
                    fixture.table = batteryTable(List.of(BATTERY_PUBLISH_MAIN,
                        BATTERY_PUBLISH_MAIN, BATTERY_PUBLISH_OTHER), List.of(BATTERY_BODY_CONST)),
                    "CONTROL_BLOCK_TREE", "listed twice in one block"),
                new AdmissionCase("an op the unit does not produce", fixture ->
                    fixture.table = batteryTable(List.of(BATTERY_PUBLISH_MAIN,
                        BATTERY_PUBLISH_OTHER, BATTERY_UNPRODUCED), List.of(BATTERY_BODY_CONST)),
                    "CONTROL_BLOCK_TREE", "not a produced op"),
                new AdmissionCase("a missing function root", fixture ->
                    fixture.table = new StructuredBodyTable(
                        Map.of(BATTERY_INIT_BLOCK, List.of(BATTERY_PUBLISH_MAIN, BATTERY_PUBLISH_OTHER)),
                        Map.of(BATTERY_PUBLISH_MAIN, BATTERY_INIT_BLOCK,
                            BATTERY_PUBLISH_OTHER, BATTERY_INIT_BLOCK)),
                    "CONTROL_BLOCK_TREE", "is not a block of the table"),
                new AdmissionCase("a class-layout key disagreement", fixture ->
                    fixture.layouts.put(FOREIGN_CLASS, fixture.layouts.remove(BATTERY_CLASS)),
                    "R-IDENTITY-LINK", "class-layout registry key"),
                new AdmissionCase("a function key disagreement", fixture ->
                    fixture.functions = Map.of(new FunctionId(4), fixture.functions.get(BATTERY_FUNCTION)),
                    "R-IDENTITY-LINK", "function registry key"),
                new AdmissionCase("a retargeted moduleInit.initBlock", fixture ->
                    fixture.moduleInit = new ModuleInitPlan(List.of(MOD_B, MOD_B), BATTERY_BODY_BLOCK),
                    "R-IDENTITY-LINK", "module-init plan names init block"),
                new AdmissionCase("retargeted moduleInit.imports", fixture ->
                    fixture.moduleInit = new ModuleInitPlan(List.of(MOD_B), BATTERY_INIT_BLOCK),
                    "R-IDENTITY-LINK", "ordered resolved imports"),
                new AdmissionCase("a retargeted export publish op", fixture ->
                    fixture.exportPlan = retargetedExportPlan("main", BATTERY_PUBLISH_OTHER),
                    "R-IDENTITY-LINK", "whose export name is"),
                new AdmissionCase("a retargeted export name", fixture ->
                    fixture.exportPlan = retargetedExportPlan("mainElsewhere", BATTERY_PUBLISH_MAIN),
                    "R-IDENTITY-LINK", "whose export name is"),
                new AdmissionCase("a foreign publication", fixture ->
                    fixture.ops = retargetedPublication(MOD_B, "main"),
                    "R-IDENTITY-LINK", "foreign publication"))) {
            assertCommonAdmissionRejects(scenario.what(), batteryUnit(hash, scenario.mutation()),
                index, scenario.rule(), scenario.detail());
        }
        {
            // A duplicate export name is the entry identity: the typed
            // admission rejects it; the text surface rejects it earlier, at
            // transport decode (the framing precedes admission).
            ExportPlan plan = new ExportPlan(List.of(
                new ExportPlan.ExportPlanEntry("main", SIG_II, BATTERY_PUBLISH_MAIN),
                new ExportPlan.ExportPlanEntry("main", SIG_II, BATTERY_PUBLISH_MAIN)));
            LoweredModuleUnit mutated = batteryUnit(hash, fixture -> fixture.exportPlan = plan);
            assertE6005(SemanticIrValidator.validate(mutated, facts),
                "R-IDENTITY-LINK", "more than once");
            expectDecodeFailure(SemanticIrValidator.toUnitText(mutated),
                "a duplicate export-plan name rejects at transport decode");
        }

        // The own-interface link needs the project context: a standalone unit
        // admission cannot approve it, the complete project admission rejects
        // it on both surfaces.
        {
            LoweredModuleUnit mutated = batteryUnit(hash, fixture -> {
                fixture.exportPlan = retargetedExportPlan("notDeclared", BATTERY_PUBLISH_MAIN);
                fixture.ops = retargetedPublication(MOD, "notDeclared");
            });
            assertPass(SemanticIrValidator.validate(mutated, facts),
                "the standalone unit admission carries no own-interface context");
            ExecutableLoweredProject project = batteryProject(mutated, index);
            Optional<CompilerDiagnostic> typedProject =
                SemanticIrValidator.validate(project, facts);
            Optional<CompilerDiagnostic> projectText = SemanticIrValidator.validateText(
                SemanticIrValidator.toProjectText(project), facts);
            assertE6005(typedProject, "R-IDENTITY-LINK", "not a declared export");
            assertE6005(projectText, "R-IDENTITY-LINK", "not a declared export");
            check(typedProject.isPresent() && projectText.isPresent()
                    && typedProject.get().message().equals(projectText.get().message()),
                "both project surfaces report the identical own-interface disagreement");
        }

        // The two exemptions stay live: the invocation-owned RETURN record
        // and the module-level kinds are admissible absent from the table.
        {
            LoweredModuleUnit dynamicPositive = dynamicCellUnit(hash, Map.of());
            assertPass(SemanticIrValidator.validate(dynamicPositive, facts),
                "the call-owned DEAL-body cell and its unattached RETURN are admissible");
            assertPass(SemanticIrValidator.validateText(
                    SemanticIrValidator.toUnitText(dynamicPositive), facts),
                "the call-owned DEAL-body cell through the text surface");
            check(dynamicPositive.bodyTable().blockOps().containsKey(BATTERY_INIT_BLOCK)
                    && !dynamicPositive.bodyTable().opBlocks().containsKey(
                        DYNAMIC_RETURN_OP),
                "the fixture keeps the invocation-owned RETURN out of the table");
        }
    }

    private static final OpId BATTERY_RETURN = new OpId(MOD, 52);
    /** A RETURN's recorded invocation/boundary positions (unused by this fixture). */
    private static final OpId BATTERY_RETURN_INVOCATION = new OpId(MOD, 90);
    private static final OpId BATTERY_RETURN_BOUNDARY = new OpId(MOD, 91);

    /** The battery unit with one function-attached RETURN naming its function. */
    private static LoweredModuleUnit returnTargetUnit(String interfaceHash) {
        List<SemanticOp> ops = new ArrayList<>(batteryOps());
        ops.add(userOp(opWith(BATTERY_RETURN, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(null, BATTERY_FUNCTION,
                BATTERY_RETURN_INVOCATION, BATTERY_RETURN_BOUNDARY),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null)));
        return batteryUnit(interfaceHash, Map.of(), batteryLayouts(), batteryFunctions(),
            new ModuleInitPlan(List.of(MOD_B, MOD_B), BATTERY_INIT_BLOCK), batteryExportPlan(),
            ops, batteryTable(List.of(BATTERY_PUBLISH_MAIN, BATTERY_PUBLISH_OTHER),
                List.of(BATTERY_BODY_CONST, BATTERY_RETURN)));
    }

    private static int opIndexOf(LoweredModuleUnit unit, OpId opId) {
        for (int i = 0; i < unit.ops().size(); i++) {
            if (unit.ops().get(i).opId().equals(opId)) {
                return i;
            }
        }
        return -1;
    }

    /** The index of one module document in the project text's modules array. */
    private static int moduleIndexOf(String projectText, ModuleId module) {
        CanonicalJson.Arr modules = (CanonicalJson.Arr) at(parseObj(projectText), "modules");
        for (int i = 0; i < modules.items().size(); i++) {
            CanonicalJson.Obj unit = (CanonicalJson.Obj) modules.items().get(i);
            if (module.path().equals(((CanonicalJson.Str) at(unit, "moduleId")).value())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The text admission of decodable malformed content must fail closed
     * with a defined admission result: an unhandled runtime exception is
     * not a failure contract.
     */
    private static Optional<CompilerDiagnostic> textAdmission(String text,
            SemanticIrValidator.ComparisonFacts facts) {
        try {
            return SemanticIrValidator.validateText(text, facts);
        } catch (RuntimeException e) {
            fail("the text admission of decodable malformed content must fail closed, "
                + "never throw: " + e);
            return Optional.empty();
        }
    }

    /**
     * The RETURN function position on the decodable text surface: the typed
     * {@code ReturnPayload} enforces the identity, so only the raw/text
     * surface can carry an absent or null function. Raw admission owns the
     * rule there: the text admission must reject with the control exit rule
     * instead of dereferencing the missing identity, and the raw facts and
     * the project text must report the identical first failure.
     */
    private static void testCommonAdmissionReturnPosition() {
        System.out.println("-- Common admission: a RETURN naming no function fails closed on "
            + "the text surface (ISSUE-0819) --");
        ProjectInterfaceIndex index = batteryIndex();
        SemanticIrValidator.ComparisonFacts facts = projectFacts(index);
        LoweredModuleUnit base = returnTargetUnit(index.interfaceIndexDigest());
        assertUnitPass(base, facts, "the function-attached RETURN unit");
        String text = SemanticIrValidator.toUnitText(base);
        ExecutableLoweredProject project = batteryProject(base, index);
        assertProjectPass(project, "the function-attached RETURN project");
        String projectText = SemanticIrValidator.toProjectText(project);
        int returnIndex = opIndexOf(base, BATTERY_RETURN);
        int projectModule = moduleIndexOf(projectText, MOD);
        check(returnIndex >= 0 && projectModule >= 0,
            "the fixture carries the attached RETURN (unit op " + returnIndex
                + ", project module " + projectModule + ")");
        if (returnIndex < 0 || projectModule < 0) {
            return;
        }

        String nullFunction = withEntryAt(text, "function", CanonicalJson.nullValue(),
            "ops", returnIndex, "payload");
        String absentFunction = withoutKeyAt(text, "function", "ops", returnIndex, "payload");
        check(!nullFunction.equals(text) && !absentFunction.equals(text)
                && !nullFunction.equals(absentFunction),
            "the null and absent function positions are real text mutations");
        Optional<CompilerDiagnostic> nullFailure = textAdmission(nullFunction, facts);
        Optional<CompilerDiagnostic> absentFailure = textAdmission(absentFunction, facts);
        assertE6005(nullFailure, "CONTROL_EXIT", "names no function");
        assertE6005(absentFailure, "CONTROL_EXIT", "names no function");
        check(nullFailure.map(CompilerDiagnostic::message)
                .equals(absentFailure.map(CompilerDiagnostic::message)),
            "the null and absent function positions report the identical first failure");

        // The raw facts behind the mutated text run the same rule with the
        // same message and range: the control owner, not the transport, is
        // the responsible rule for the malformed position.
        Optional<CompilerDiagnostic> rawFailure = ControlFlowValidator.validate(
            ContractSnapshotCanonicalizer.parseUnit(parseObj(nullFunction)));
        assertSameFailure(nullFailure, rawFailure, "the mutated raw RETURN facts");

        // The project text path carries the same fact to the same rule.
        String malformedProject = withEntryAt(projectText, "function",
            CanonicalJson.nullValue(), "modules", projectModule, "ops", returnIndex, "payload");
        Optional<CompilerDiagnostic> projectFailure = textAdmission(malformedProject, facts);
        assertE6005(projectFailure, "CONTROL_EXIT", "names no function");
        assertSameFailure(nullFailure, projectFailure, "the project text RETURN admission");
    }

    private static final OpId DYNAMIC_CALL_OP = new OpId(MOD, 60);
    private static final OpId DYNAMIC_PARAM_OP = new OpId(MOD, 61);
    private static final OpId DYNAMIC_DEAL_CELL = new OpId(MOD, 62);
    private static final OpId DYNAMIC_HOST_CELL = new OpId(MOD, 63);
    private static final OpId DYNAMIC_EXTERNAL_CELL = new OpId(MOD, 64);
    private static final OpId DYNAMIC_RETURN_OP = new OpId(MOD, 65);

    /**
     * The dynamic DEAL-body cell fixture: one Dynamic CALL recording the
     * three cells, the DEAL-body cell parented to the unattached
     * invocation-owned RETURN. Declaring the RETURN's function makes the
     * record the callee-owned form; the callee value then needs the
     * same-walk loweredBody registration the dynamic-cell correlation
     * requires.
     */
    private static LoweredModuleUnit dynamicCellUnit(String interfaceHash,
            Map<FunctionId, LoweredFunction> functions) {
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(boundaryWith(DYNAMIC_PARAM_OP, BoundaryKind.FUNCTION_PARAMETER, INT,
            FailurePolicyId.TYPE_DESCRIPTOR, DYNAMIC_CALL_OP));
        ops.add(boundaryWith(DYNAMIC_DEAL_CELL, BoundaryKind.FUNCTION_RETURN, INT,
            FailurePolicyId.TYPE_DESCRIPTOR, DYNAMIC_RETURN_OP));
        ops.add(boundaryWith(DYNAMIC_HOST_CELL, BoundaryKind.HOST_TO_DEAL, INT,
            FailurePolicyId.HOST_SYNC_RETURN, DYNAMIC_CALL_OP));
        ops.add(boundaryWith(DYNAMIC_EXTERNAL_CELL, BoundaryKind.EXTERNAL_RETURN, INT,
            FailurePolicyId.TYPE_DESCRIPTOR, DYNAMIC_CALL_OP));
        ops.add(opWith(DYNAMIC_RETURN_OP, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(nextValue(), new FunctionId(1), DYNAMIC_CALL_OP,
                DYNAMIC_DEAL_CELL),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, DYNAMIC_CALL_OP));
        ops.add(opWith(DYNAMIC_CALL_OP, SemanticOpKind.CALL,
            new KindPayload.CallPayload(CallMode.INDIRECT,
                new KindPayload.CallCallee.Dynamic(new ValueId(77)), SIG_II,
                List.of(DYNAMIC_PARAM_OP), null,
                new KindPayload.DynamicReturnBoundary(DYNAMIC_DEAL_CELL, DYNAMIC_HOST_CELL,
                    DYNAMIC_EXTERNAL_CELL),
                null, null),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null));
        List<OpId> attached = new ArrayList<>();
        Map<OpId, BlockId> inverse = new LinkedHashMap<>();
        for (SemanticOp op : ops) {
            if (op.opId().equals(DYNAMIC_RETURN_OP)) {
                continue;
            }
            attached.add(op.opId());
            inverse.put(op.opId(), BATTERY_INIT_BLOCK);
        }
        StructuredBodyTable table = new StructuredBodyTable(
            Map.of(BATTERY_INIT_BLOCK, attached), inverse);
        return batteryUnit(interfaceHash, Map.of(), Map.of(), functions,
            new ModuleInitPlan(List.of(), BATTERY_INIT_BLOCK), ExportPlan.empty(), ops, table);
    }

    /**
     * The previously typed-only correlations of the complete facts run
     * identically on both surfaces: the layout-only CLASS_DECLARATION
     * coverage and the dynamic DEAL-body cell form.
     */
    private static LoweredModuleUnit layoutCoverageUnit(String hash, boolean present) {
        return batteryUnit(hash, Map.of(ConstructKind.CLASS_DECLARATION,
                ConstructKind.CLASS_DECLARATION.mappedOpKinds()),
            present ? batteryLayouts() : Map.of(), Map.of(),
            new ModuleInitPlan(List.of(), BATTERY_INIT_BLOCK), ExportPlan.empty(), List.of(),
            new StructuredBodyTable(Map.of(BATTERY_INIT_BLOCK, List.of()), Map.of()));
    }

    private record CorrelationCase(String what,
            java.util.function.Function<String, LoweredModuleUnit> positive,
            java.util.function.Function<String, LoweredModuleUnit> negative,
            String rule, List<String> details) {}

    private static void testCommonAdmissionCorrelations() {
        System.out.println("-- Common admission: the typed-only correlations run on both surfaces (ISSUE-0819) --");
        Map<FunctionId, LoweredFunction> declared = Map.of(new FunctionId(1),
            new LoweredFunction(new FunctionId(1), SIG_II, List.of(), BATTERY_INIT_BLOCK, "f"));
        ProjectInterfaceIndex index = batteryIndex();
        for (CorrelationCase scenario : List.of(
                new CorrelationCase("layout-only class", hash -> layoutCoverageUnit(hash, true),
                    hash -> layoutCoverageUnit(hash, false), "R-COVERAGE", List.of("no class layout record")),
                new CorrelationCase("call-owned DEAL-body cell", hash -> dynamicCellUnit(hash, Map.of()),
                    hash -> dynamicCellUnit(hash, declared), "R-BOUNDARY-TRIPLE",
                    List.of("callee-owned DEAL-body cell", "no same-walk loweredBody registration")))) {
            assertUnitPass(scenario.positive().apply(IFACE), FACTS, scenario.what());
            assertProjectPass(batteryProject(scenario.positive().apply(index.interfaceIndexDigest()), index),
                scenario.what());
            LoweredModuleUnit negative = scenario.negative().apply(IFACE);
            Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(negative, FACTS);
            Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(negative), FACTS);
            ExecutableLoweredProject project = batteryProject(
                scenario.negative().apply(index.interfaceIndexDigest()), index);
            Optional<CompilerDiagnostic> typedProject = SemanticIrValidator.validate(project, projectFacts(index));
            Optional<CompilerDiagnostic> projectText = SemanticIrValidator.validateText(
                SemanticIrValidator.toProjectText(project), projectFacts(index));
            for (Optional<CompilerDiagnostic> diagnostic : List.of(typed, text, typedProject, projectText)) {
                assertE6005(diagnostic, scenario.rule(), scenario.details().toArray(String[]::new));
            }
            assertSameFailure(typed, text, scenario.what() + " unit paths");
            assertSameFailure(typedProject, projectText, scenario.what() + " project paths");
        }
    }

    // =========================================================================
    // 12. Explicit profile transport and the v1.3 activation boundary
    // =========================================================================

    /**
     * ISSUE-0822: the semanticProfile flows explicitly from the
     * single-profile owner through the unit/project transport and the dump
     * framing, and no path substitutes a removed v1.2 value or a second
     * supported mode. The owner carries exactly one member, so the typed
     * record surfaces can only carry that value; the text surface is where
     * a foreign carried value is exercised, and it rejects with the same
     * R-PROFILE rule. Removing the carried profile is a decode failure,
     * never a silent substitution.
     */
    private static void assertProfileText(String text, SemanticIrValidator.ComparisonFacts facts,
            String surface) {
        check(text.contains("\"semanticProfile\":\"DEAL_V1_3_INT32\""),
            surface + " text carries the owner profile");
        check(!text.contains("DEAL_V1_2_INT32") && !text.contains("LEGACY_SAFE_INT"),
            surface + " transport contains no removed profile");
        assertPass(SemanticIrValidator.validateText(text, facts), surface + " owner profile admits");
        expectDecodeFailure(() -> SemanticIrValidator.validateText(withoutKey(text, "semanticProfile"), facts),
            surface + " without semanticProfile is a decode failure, never a substitution");
    }

    private static void testExplicitProfileTransport() {
        System.out.println("-- Explicit semanticProfile transport and the v1.3 owner --");

        // The admitted name is the owner's value, not an independent literal,
        // and the owner is a closed single-member set with unchanged peer
        // format versions (no speculative bump).
        checkEq(1, SemanticProfile.values().length,
            "the single-profile owner carries exactly one member");
        checkEq(SemanticProfile.DEAL_V1_3_INT32.name(), SemanticIrValidator.ADMITTED_PROFILE,
            "the admitted profile name is the owner's value");
        checkEq("deal.semantic-interface/1", ProjectInterfaceIndex.FORMAT_VERSION,
            "the interface format is unchanged (no speculative bump)");
        checkEq(1, OperationContractSnapshot.VERSION,
            "the operation snapshot format is unchanged (no speculative bump)");

        LoweredModuleUnit base = unit(List.of(constOp()));

        // The typed unit carries the owner's profile and the raw transport
        // preserves it explicitly at the unit surface.
        checkEq(SemanticProfile.DEAL_V1_3_INT32, base.semanticProfile(),
            "the typed unit carries the owner profile");
        checkEq(SemanticProfile.DEAL_V1_3_INT32.name(),
            RawUnit.fromTyped(base).semanticProfile(),
            "the raw transport carries the profile name explicitly");

        // The unit text carries the owner value, and nothing in the
        // admission/transport output mentions a removed profile.
        String text = SemanticIrValidator.toUnitText(base);

        checkEq(LCH, LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32, REGISTRY),
            "the carried loweringContextHash is the owner/capability derivation");

        // A carried removed v1.2 value whose loweringContextHash equals the
        // owner derivation still rejects by profile name: the carried value is
        // rejected, not silently interpreted under the owner profile.
        String removed = serialize(withEntry(parseObj(text),
            "semanticProfile", CanonicalJson.str("DEAL_V1_2_INT32")));
        check(removed.contains("\"loweringContextHash\":\"" + LCH + "\""),
            "the removed-profile mutation keeps the owner-derived hash (precondition)");
        assertE6005(SemanticIrValidator.validateText(removed, FACTS),
            "R-PROFILE", "DEAL_V1_2_INT32");

        // A carried profile that disagrees with the supplied facts rejects at
        // the same R-PROFILE relation; the derivation consumes the supplied
        // capability-registry hash, so a foreign pair rejects at the hash.
        String wrongRegistryHash = LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32,
            REGISTRY + "-other");
        check(!wrongRegistryHash.equals(LCH),
            "the foreign registry hash changes the derivation (precondition)");
        String hashMismatch = serialize(withEntry(parseObj(text), "loweringContextHash",
            CanonicalJson.str(wrongRegistryHash)));
        assertE6005(SemanticIrValidator.validateText(hashMismatch, FACTS),
            "R-PROFILE", "loweringContextHash");

        // The project surface: the typed project and each of its units carry
        // the owner profile, the text carries it explicitly, and the output
        // mentions no removed value.
        ExecutableLoweredProject project = profileProject();
        ProjectInterfaceIndex index = project.interfaceIndex();
        assertPass(SemanticIrValidator.validate(project, projectFacts(index)),
            "the owner-profile project admits");
        checkEq(SemanticProfile.DEAL_V1_3_INT32, project.semanticProfile(),
            "the typed project carries the owner profile");
        for (LoweredModuleUnit carried : project.modules().values()) {
            checkEq(SemanticProfile.DEAL_V1_3_INT32, carried.semanticProfile(),
                "unit " + carried.moduleId() + " carries the owner profile");
        }
        String projectText = SemanticIrValidator.toProjectText(project);
        assertProfileText(text, FACTS, "unit");
        assertProfileText(projectText, projectFacts(index), "project");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Semantic IR Validator Test (ISSUE-0286) ===\n");

        testExceptionalAdmissions();
        testSelectorPolicies();
        testConstructCoverage();
        testNegatives();
        testTextNegatives();
        testOriginTransport();
        testDeterminism();
        testTimeAdmission();
        LoweredModuleUnit transport = completeTransportUnit();
        String text = SemanticIrValidator.toUnitText(transport);
        testCompleteTransport(transport);
        testTransportIdentityLink();
        testTransportMandatoryEmptiesAndNegatives(text);
        testProjectTransportAndMandatoryEmpties();
        testRegistrySourceRefDiscriminator(text);
        testCommonAdmissionMembershipAndPlans();
        testCommonAdmissionReturnPosition();
        testCommonAdmissionCorrelations();
        testExplicitProfileTransport();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
