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
import deal.semantic.ir.RawSourceRef;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
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
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.JsonDefaultChildTable;
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
        new SemanticIrValidator.ComparisonFacts(IFACE, SemanticProfile.DEAL_V1_2_INT32, REGISTRY);
    private static final String LCH =
        LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32, REGISTRY);

    private static final RuntimeDescriptor INT = RuntimeDescriptor.Int.INSTANCE;
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
            SemanticProfile.DEAL_V1_2_INT32, new ModuleId(module), IFACE, LCH, caps, coverage,
            Map.of(), Map.of(), new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(),
            bindings, ops, new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()),
            new JsonDefaultChildTable(Map.of()), ClassBindingFacts.empty());
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
        return op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Int(1)),
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
            // INDIRECT → LoweredBody through functionBindings.
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings = Map.of(
                new FunctionAllocationIdentity(9),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(1), new BlockId(1)));
            OpId callOp = nextOpId();
            OpId paramBoundary = nextOpId();
            OpId returnBoundary = nextOpId();
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
            assertAdmission("registered indirect CALL", ops, bindings);
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
                    nextValue(), new BoundaryRealization.RepresentationProof("jvm-int-proof")),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, null)), Map.of());
    }

    private static void testSelectorPolicies() {
        Map<BinarySelector, FailurePolicyId> binary = Map.of(
            BinarySelector.INT32_ADD, FailurePolicyId.INT32_RESULT,
            BinarySelector.INT32_DIV_TRUNC, FailurePolicyId.INT32_DIVISOR_THEN_RESULT,
            BinarySelector.INT32_POW, FailurePolicyId.INT32_EXPONENT_THEN_RESULT,
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
            StdlibFunctionId.STRING_LENGTH, FailurePolicyId.INT32_RESULT,
            StdlibFunctionId.TIME_NOW_MILLIS, FailurePolicyId.INT32_RESULT,
            StdlibFunctionId.JSON_PARSE, FailurePolicyId.JSON_PARSE_SYNTAX,
            StdlibFunctionId.JSON_STRINGIFY, FailurePolicyId.JSON_TO_ERROR,
            StdlibFunctionId.MATH_SQRT, FailurePolicyId.SQRT_NEGATIVE,
            StdlibFunctionId.MATH_ABS_INT, FailurePolicyId.INT32_RESULT,
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
        assertPass(SemanticIrValidator.validate(unit, FACTS), what);
        assertPass(SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(unit), FACTS),
            what + " through the text surface");
    }

    private static void testConstructCoverage() {
        Map<ConstructKind, List<SemanticOpKind>> scalarCoverage =
            Map.of(ConstructKind.SCALAR_LITERAL, List.of(SemanticOpKind.CONST));
        LoweredModuleUnit covered = unit(Set.of(), scalarCoverage, Map.of(), List.of(constOp()));
        assertPass(SemanticIrValidator.validate(covered, FACTS), "covered scalar literal");
        assertPass(SemanticIrValidator.validateText(SemanticIrValidator.toUnitText(covered), FACTS),
            "covered scalar literal through the text surface");
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
                    new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.JSON_PARSE_SYNTAX, null))), FACTS),
            "R-POLICY-KIND", "JSON_PARSE_SYNTAX", "INT32_ADD");

        // R-BOUNDARY-TRIPLE: an invocation-kind boundary outside any invocation.
        assertE6005(SemanticIrValidator.validate(unit(List.of(
                boundary(BoundaryKind.FUNCTION_PARAMETER, INT, FailurePolicyId.TYPE_DESCRIPTOR,
                    null))), FACTS),
            "R-BOUNDARY-TRIPLE", "FUNCTION_PARAMETER");

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
                op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Int(1)),
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
                SemanticProfile.DEAL_V1_2_INT32, index,
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

        // R-DIGEST: a wrong (stale) contract digest — T3's recomputation disagrees.
        {
            KindPayload payload = new KindPayload.ConstPayload(new ScalarValue.Int(1));
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
                    SemanticProfile.DEAL_V1_2_INT32, MOD, "wrong-interface-digest", LCH, Set.of(),
                    Map.of(), Map.of(), Map.of(),
                    new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
                    List.of(constOp()), new StructuredBodyTable(Map.of(), Map.of()),
                    new ClassFactoryRegistry(Map.of()), new JsonDefaultChildTable(Map.of()),
                    ClassBindingFacts.empty()), FACTS),
            "R-PROFILE", "interfaceHash");

        // R-PROFILE: loweringContextHash computed by T3's helper for a
        // different profile/registry-hash pair.
        {
            String wrongHash = LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                "different-registry-hash");
            check(!wrongHash.equals(LCH), "the foreign-pair hash differs (T3 precondition)");
            assertE6005(SemanticIrValidator.validate(
                    new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
                        SemanticProfile.DEAL_V1_2_INT32, MOD, IFACE, wrongHash, Set.of(),
                        Map.of(), Map.of(), Map.of(),
                        new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(),
                        Map.of(), List.of(constOp()),
                        new StructuredBodyTable(Map.of(), Map.of()),
                        new ClassFactoryRegistry(Map.of()),
                        new JsonDefaultChildTable(Map.of()),
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
                    new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null)));
            assertPass(SemanticIrValidator.validate(base, FACTS), "binary injection base");
            String text = substitutePolicy(SemanticIrValidator.toUnitText(base),
                "INT32_RESULT", "NOT_A_POLICY");
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
                    new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                    nextValue(), INT, FailurePolicyId.INT32_RESULT, null)));
            String text = substitutePolicy(SemanticIrValidator.toUnitText(base),
                "INT32_RESULT", reserved);
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-RESERVED-NAME", reserved);
        }

        // R-PROFILE: the semanticProfile string LEGACY_SAFE_INT in a unit
        // otherwise identical to a valid one.
        {
            LoweredModuleUnit base = unit(List.of(constOp()));
            String text = substituteUnitLeaf(SemanticIrValidator.toUnitText(base),
                "semanticProfile", "DEAL_V1_2_INT32", "LEGACY_SAFE_INT");
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-PROFILE", "LEGACY_SAFE_INT");
        }

        // R-PROFILE: cross-unit profile mismatch through project-level
        // canonical-JSON injection.
        {
            LoweredModuleUnit unitA = unit("mod.a", Set.of(), Map.of(), Map.of(),
                List.of(constOp()));
            LoweredModuleUnit unitB = unit("mod.b", Set.of(), Map.of(), Map.of(),
                List.of(constOp()));
            ProjectInterfaceIndex index = new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION,
                Map.of(MOD, new ExternalModuleInterface(MOD, ExternalModuleKind.IMPLEMENTATION,
                        List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES),
                    MOD_B, new ExternalModuleInterface(MOD_B, ExternalModuleKind.IMPLEMENTATION,
                        List.of(), List.of(), List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES)));
            ExecutableLoweredProject project = new ExecutableLoweredProject(
                SemanticProfile.DEAL_V1_2_INT32, index,
                new LinkedHashMap<>() {{
                    put(MOD, unitA);
                    put(MOD_B, unitB);
                }}, MOD, ClassRegistrationSeeds.builtinErrorOnly());
            assertPass(SemanticIrValidator.validate(project, FACTS), "project injection base");
            String text = substituteProjectUnitLeaf(SemanticIrValidator.toProjectText(project),
                1, "semanticProfile", "DEAL_V1_2_INT32", "LEGACY_SAFE_INT");
            assertE6005(SemanticIrValidator.validateText(text, FACTS),
                "R-PROFILE", "LEGACY_SAFE_INT");
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
        List<CanonicalJson.Entry> eight = new ArrayList<>();
        for (CanonicalJson.Entry entry : contract.entries()) {
            if (!"canonicalDigest".equals(entry.key())) {
                eight.add(entry);
            }
        }
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(CanonicalJson.obj(eight)));
    }

    private static CanonicalJson.Obj contractWith(CanonicalJson.Obj contract, String key,
            CanonicalJson.Value value) {
        CanonicalJson.Obj updated = withEntry(contract, key, value);
        String digest = recomputeContractDigest(updated);
        return withEntry(updated, "canonicalDigest", CanonicalJson.str(digest));
    }

    private static String substitutePolicy(String text, String from, String to) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        CanonicalJson.Obj contract = (CanonicalJson.Obj) at(op, "contract");
        CanonicalJson.Obj contract2 = contractWith(contract, "failurePolicy", CanonicalJson.str(to));
        CanonicalJson.Obj op2 = withEntry(op, "failurePolicy", CanonicalJson.str(to));
        op2 = withEntry(op2, "contract", contract2);
        return serialize(withOps(root, op2));
    }

    private static String substitutePayloadLeaf(String text, String key, String from, String to) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        CanonicalJson.Obj payload = (CanonicalJson.Obj) at(op, "payload");
        CanonicalJson.Obj payload2 = withEntry(payload, key, CanonicalJson.str(to));
        CanonicalJson.Obj contract = (CanonicalJson.Obj) at(op, "contract");
        CanonicalJson.Obj contract2 = contractWith(contract, "payload", payload2);
        CanonicalJson.Obj op2 = withEntry(op, "payload", payload2);
        op2 = withEntry(op2, "contract", contract2);
        return serialize(withOps(root, op2));
    }

    private static String substituteOpKind(String text, String from, String to) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        CanonicalJson.Obj contract = (CanonicalJson.Obj) at(op, "contract");
        CanonicalJson.Obj contract2 = contractWith(contract, "opKind", CanonicalJson.str(to));
        CanonicalJson.Obj op2 = withEntry(op, "kind", CanonicalJson.str(to));
        op2 = withEntry(op2, "contract", contract2);
        return serialize(withOps(root, op2));
    }

    private static String substituteStdlibFunction(String text, String from, String to) {
        CanonicalJson.Obj root = parseObj(text);
        CanonicalJson.Obj op = firstOp(root);
        CanonicalJson.Obj payload = (CanonicalJson.Obj) at(op, "payload");
        CanonicalJson.Obj payload2 = withEntry(payload, "function", CanonicalJson.str(to));
        CanonicalJson.Obj contract = (CanonicalJson.Obj) at(op, "contract");
        CanonicalJson.Obj contract2 = contractWith(contract, "payload", payload2);
        contract2 = contractWith(contract2, "selector", CanonicalJson.str(to));
        CanonicalJson.Obj op2 = withEntry(op, "payload", payload2);
        op2 = withEntry(op2, "contract", contract2);
        return serialize(withOps(root, op2));
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
            KindPayload payload = new KindPayload.ConstPayload(new ScalarValue.Int(1));
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
                "semanticProfile", "DEAL_V1_2_INT32", "LEGACY_SAFE_INT");
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

        // Aggregate failures identify no operation and stay originless.
        LoweredModuleUnit aggregate = unit("mod.a",
            Set.of(SemanticCapability.STDLIB_TIME_CONFLICT), Map.of(), Map.of(),
            List.of(withOrigin(constOp(), userOrigin("test.deal", null))));
        Optional<CompilerDiagnostic> aggregateFailure =
            SemanticIrValidator.validate(aggregate, FACTS);
        assertE6005(aggregateFailure, "R-CAPABILITY", "STDLIB_TIME_CONFLICT");
        check(aggregateFailure.isPresent()
                && aggregateFailure.get().range().isCanonicalSynthetic()
                && "".equals(aggregateFailure.get().range().file()),
            "an aggregate capability failure borrows no operation origin (the "
                + "canonical empty-file synthetic range): "
                + (aggregateFailure.isEmpty() ? "no failure"
                    : aggregateFailure.get().range()));
    }

    private static SemanticOp nextConst() {
        return op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Int(2)),
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
            LoweredModuleUnit forged = unit("mod.a",
                Set.of(SemanticCapability.STDLIB_TIME_CONFLICT),
                Map.of(), Map.of(), List.of());
            Optional<CompilerDiagnostic> typed = SemanticIrValidator.validate(forged, FACTS);
            assertE6005(typed, "R-CAPABILITY", "STDLIB_TIME_CONFLICT");
            System.out.println("    observed STDLIB_TIME_CONFLICT rejection (typed): "
                + typed.get().message());
            Optional<CompilerDiagnostic> text = SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(forged), FACTS);
            assertE6005(text, "R-CAPABILITY", "STDLIB_TIME_CONFLICT");
            check(typed.get().message().equals(text.get().message()),
                "both surfaces report the identical R-CAPABILITY rejection for the forged "
                    + "STDLIB_TIME_CONFLICT claim");
            checkEq(DiagnosticRange.synthetic(""), typed.get().range(),
                "the typed conflict claim has no source attribution");
            checkEq(typed.get().range(), text.get().range(),
                "the text conflict claim keeps the same originless range");
        }

        // Fact 2 — no produced op set can satisfy the claim: a unit that
        // fully satisfies its other claims still fails on the
        // empty-evidence claim, so no lowering path admits a
        // conflict-claiming module.
        {
            LoweredModuleUnit forged = unit("mod.a",
                Set.of(SemanticCapability.FOUNDATION_VALUES,
                    SemanticCapability.STDLIB_TIME_CONFLICT),
                Map.of(), Map.of(), List.of(
                    constOp(),
                    op(SemanticOpKind.UNARY,
                        new KindPayload.UnaryPayload(UnarySelector.BOOL_NOT),
                        nextValue(), BOOL, FailurePolicyId.NO_DEAL_FAILURE, null),
                    op(SemanticOpKind.BINARY,
                        new KindPayload.BinaryPayload(BinarySelector.INT32_ADD, null, null),
                        nextValue(), INT, FailurePolicyId.INT32_RESULT, null),
                    op(SemanticOpKind.STRING_CONCAT,
                        new KindPayload.StringConcatPayload(List.of(nextValue())),
                        nextValue(), STR, FailurePolicyId.NO_DEAL_FAILURE, null)));
            assertE6005(SemanticIrValidator.validate(forged, FACTS),
                "R-CAPABILITY", "STDLIB_TIME_CONFLICT");
            assertE6005(SemanticIrValidator.validateText(
                    SemanticIrValidator.toUnitText(forged), FACTS),
                "R-CAPABILITY", "STDLIB_TIME_CONFLICT");
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
            JsonDefaultChildTable jsonDefaults,
            ClassBindingFacts classBindingFacts) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, MOD, IFACE, LCH, Set.of(), Map.of(),
            classLayouts, functions, moduleInit, exportPlan, bindings, ops, bodyTable,
            classFactories, jsonDefaults, classBindingFacts);
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
            new ClassLayout.FieldLayout("x", INT, true, DefaultOwner.LOCAL),
            new ClassLayout.FieldLayout("label", STR, false, DefaultOwner.HOST_DEFAULTS))));
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
        bindings.put(new FunctionAllocationIdentity(18),
            new FunctionExecutionBinding.IntrinsicFunction(IntrinsicKind.INT_CONVERT,
                IntrinsicKind.INT_CONVERT.declaredSignature()));
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
        JsonDefaultChildTable jsonDefaults = new JsonDefaultChildTable(
            new LinkedHashMap<>() {{
                put(new OpId(MOD, 54), List.of(new OpId(MOD, 55), new OpId(MOD, 56)));
                put(new OpId(MOD, 57), List.of());
            }});
        ClassBindingFacts bindingFacts = new ClassBindingFacts(
            new LinkedHashSet<>(List.of(captureA)), new LinkedHashSet<>(List.of(captureB)));

        return completeUnit(layouts, functions, moduleInit, exportPlan, bindings,
            List.of(constOp()), bodyTable, classFactories, jsonDefaults, bindingFacts);
    }

    private static void testCompleteTransport(LoweredModuleUnit unit) {
        RawUnit raw = RawUnit.fromTyped(unit);
        String text = SemanticIrValidator.toUnitText(unit);
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));

        check(text.contains("\"formatVersion\":\"deal.semantic-ir/2\""),
            "the unit header carries deal.semantic-ir/2");
        checkEq(9, reparsed.bindings().size(),
            "all binding variants plus the three adapter sourceRef shapes round-trip");
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(reparsed)),
            "re-serializing the reparsed unit is byte-stable");
        checkEq(raw, reparsed,
            "the complete raw unit round-trips exactly through the canonical text");
        checkEq(unit.bodyTable(), reparsed.bodyTable(), "typed body membership survives");
        checkEq(unit.classFactories(), reparsed.classFactories(), "typed factories survive");
        checkEq(unit.jsonDefaults(), reparsed.jsonDefaults(), "typed JSON-default rows survive");
        checkEq(unit.classBindingFacts(), reparsed.classBindingFacts(), "typed binding facts survive");
        check(text.contains("\"exportPlan\":[{\"descriptor\":"),
            "the populated export plan keeps its array form");
        check(text.contains("\"classFactories\":[{\"constructionEntry\":") && text.contains("\"factoryOp\":"),
            "the populated factory registry keeps its required member names");
        check(text.contains("\"jsonDefaults\":[{\"classDefaultOps\":[")
                && text.contains("\"classDefaultOps\":[],\"jsonFromClassOp\":{\"id\":57,"
                    + "\"modulePath\":\"mod.a\",\"type\":\"op\"}"),
            "JSON-default framing and the empty-child row survive");
        check(text.contains("\"key\":{\"id\":4,\"type\":\"function\"}")
                && text.contains("\"functionId\":{\"id\":4,\"type\":\"function\"}"),
            "function entries emit separate key and functionId members");

        check(text.contains("\"sourceName\":null"),
            "an explicit null sourceName means an anonymous function");
        assertE6005(SemanticIrValidator.validateText(
                text.replace("\"defaultOwner\":\"LOCAL\"", "\"defaultOwner\":\"BOGUS\""),
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
            new ClassFactoryRegistry(Map.of()), new JsonDefaultChildTable(Map.of()),
            ClassBindingFacts.empty()));
        expectIllegalArgument(() -> ContractSnapshotCanonicalizer.toJson(hugeRaw),
            "an unrepresentable allocationId fails encoding instead of narrowing");

        FunctionId hugeFunction = new FunctionId(1);
        RawUnit hugeGeneration = RawUnit.fromTyped(completeUnit(Map.of(), Map.of(
                hugeFunction, new LoweredFunction(hugeFunction, SIG_II,
                    List.of(new BindingGeneration(new BindingId(1),
                        (long) Integer.MAX_VALUE + 1)), new BlockId(1), "f")),
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            List.of(), new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), new JsonDefaultChildTable(Map.of()),
            ClassBindingFacts.empty()));
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
            new ClassLayout.FieldLayout("x", INT, true, DefaultOwner.LOCAL))));
        Map<FunctionId, LoweredFunction> functions = new LinkedHashMap<>();
        functions.put(keyFunction, new LoweredFunction(containedFunction, SIG_II, List.of(),
            new BlockId(7), "inc"));
        LoweredModuleUnit unit = completeUnit(layouts, functions,
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            List.of(constOp()), new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), new JsonDefaultChildTable(Map.of()),
            ClassBindingFacts.empty());

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
        check(typed.isPresent() && textSurface.isPresent()
                && typed.get().message().equals(textSurface.get().message()),
            "both surfaces report the identical first failure for the disagreement");
        if (typed.isPresent() && textSurface.isPresent()) {
            checkEq(typed.get().range(), textSurface.get().range(),
                "the disagreement failure keeps the same originless range on both surfaces");
        }

        // The function-only disagreement rejects through the same rule.
        LoweredModuleUnit functionOnly = completeUnit(Map.of(), functions,
            new ModuleInitPlan(List.of(), new BlockId(0)), ExportPlan.empty(), Map.of(),
            List.of(constOp()), new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()), new JsonDefaultChildTable(Map.of()),
            ClassBindingFacts.empty());
        Optional<CompilerDiagnostic> functionFailure =
            SemanticIrValidator.validate(functionOnly, FACTS);
        assertE6005(functionFailure, "R-IDENTITY-LINK", "function registry key",
            "contained functionId");
        assertE6005(SemanticIrValidator.validateText(
                SemanticIrValidator.toUnitText(functionOnly), FACTS),
            "R-IDENTITY-LINK", "contained functionId");
    }

    private static void testTransportMandatoryEmptiesAndNegatives(String complete) {

        LoweredModuleUnit empty = unit(List.of(constOp()));
        String text = SemanticIrValidator.toUnitText(empty);
        for (String fact : List.of("\"classLayouts\":[]", "\"functions\":[]",
                "\"exportPlan\":[]", "\"classFactories\":[]", "\"jsonDefaults\":[]",
                "\"moduleInit\":{\"imports\":[],\"initBlock\":{\"id\":0,\"type\":\"block\"}}",
                "\"bodyTable\":{\"blockOps\":[],\"opBlocks\":[]}",
                "\"classBindingFacts\":{\"importAliases\":[],\"parameters\":[]}")) {
            check(text.contains(fact), "the mandatory empty fact " + fact + " is emitted");
        }
        RawUnit reparsed = ContractSnapshotCanonicalizer.parseUnit(parseObj(text));
        check(reparsed.classLayouts().isEmpty() && reparsed.functions().isEmpty()
                && reparsed.exportPlan().isEmpty()
                && reparsed.classFactories().factories().isEmpty()
                && reparsed.jsonDefaults().defaultChildren().isEmpty()
                && reparsed.bodyTable().blockOps().isEmpty()
                && reparsed.bodyTable().opBlocks().isEmpty()
                && reparsed.classBindingFacts().parameters().isEmpty()
                && reparsed.classBindingFacts().importAliases().isEmpty(),
            "the present-empty facts re-decode as real empty facts");
        checkEq(text, CanonicalJson.serializeText(
                ContractSnapshotCanonicalizer.toJson(reparsed)),
            "the present-empty unit re-serializes byte-identically");

        // The /1 envelope is never filled or repaired.
        expectDecodeFailure(text.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/1\""),
            "a /1 envelope rejects with SemanticIrTextDecodeException");
        expectDecodeFailure(text.replace("\"deal.semantic-ir/2\"", "\"deal.semantic-ir/3\""),
            "any other envelope version rejects");
        // An absent mandatory companion is a decode failure, never empty.
        expectDecodeFailure(withoutKey(text, "classLayouts"),
            "an absent classLayouts companion rejects");
        expectDecodeFailure(withoutKey(text, "bodyTable"),
            "an absent bodyTable companion rejects");
        expectDecodeFailure(withoutKey(text, "classBindingFacts"),
            "an absent classBindingFacts companion rejects");
        // Unknown framing fields reject.
        expectDecodeFailure(withNewEntry(text, "futureFact", CanonicalJson.str("x")),
            "an unknown unit field rejects");
        // A mistyped mandatory field rejects.
        expectDecodeFailure(serialize(withEntry(parseObj(text), "classLayouts",
                CanonicalJson.str("not-an-array"))),
            "a mistyped mandatory companion rejects");
        // Duplicate entry identities reject before map construction.
        expectDecodeFailure(duplicateArrayEntry(complete, "classLayouts"),
            "a duplicate classLayouts key rejects");
        expectDecodeFailure(duplicateArrayEntry(complete, "functions"),
            "a duplicate functions key rejects");
        expectDecodeFailure(duplicateArrayEntry(complete, "functionBindings"),
            "a duplicate functionBindings allocationId rejects");
        expectDecodeFailure(duplicateArrayEntry(complete, "exportPlan"),
            "a duplicate exportPlan name rejects");
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
            try {
                SemanticIrValidator.validateText(duplicate, FACTS);
                fail("a duplicate entry identity is a decode failure on the validation surface");
            } catch (SemanticIrTextDecodeException expected) {
                passed++;
            }
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
        for (int i = 0; i < 9; i++) {
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
        try {
            ContractSnapshotCanonicalizer.parseUnit(parseObj(text));
            fail(message + " (no decode failure)");
        } catch (SemanticIrTextDecodeException expected) {
            passed++;
        } catch (RuntimeException other) {
            fail(message + " (expected SemanticIrTextDecodeException, got " + other
                + ")");
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
        testRegistrySourceRefDiscriminator(text);

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
