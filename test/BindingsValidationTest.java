package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.RangeOrigin;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.BindingsProductionValidator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.SemanticLowerer;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BindingImmutabilityProof;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.ClassBindingFacts;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.JsonDefaultChildTable;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BindingsValidationTest {

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

    // =========================================================================
    // Fixed invocation facts
    // =========================================================================

    private static final ModuleId MODULE = new ModuleId("main");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
    private static final String INTERFACE_HASH = new ProjectInterfaceIndex(
        ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new deal.semantic.ir.ExternalModuleInterface(MODULE,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION,
                List.of(), List.of(), List.of(),
                deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();

    private record CheckedSlice(ProgramNode program, CheckResult checks) {
    }

    // =========================================================================
    // Source-level driver (the validation entry point)
    // =========================================================================

    private static CheckedSlice checkSlice(String source) {
        LexResult lex = new Lexer(source, SOURCE_ID).tokenize();
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID, lex.directiveEvents()).parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver(SOURCE_ID, null);
        SymbolTable symTable = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), "the slice resolves cleanly: " + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult result = TypeChecker.check(SOURCE_ID, symTable, nr, parse.program());
        check(result.diagnostics().isEmpty(),
            "the slice checks cleanly: " + result.diagnostics());
        if (result.hasErrors()) {
            return null;
        }
        return new CheckedSlice(parse.program(), result);
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice) {
        return new CheckedModuleInput(MODULE, SOURCE_ID, Path.of(SOURCE_ID), slice.program(),
            slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
    }

    private static SemanticLowerer.ValidationCoreResult lowerSlice(String source) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        return SemanticLowerer.lowerModuleValidationCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_3_INT32, Map.of(), INTERFACE_HASH, REGISTRY_HASH,
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    /** A lowered, schema/chain/control-flow/B9-validated unit for the named test. */
    private static SemanticLowerer.ValidationCoreResult validatedResult(String source,
                                                                        String what) {
        return validatedResult(lowerSlice(source), what);
    }

    private static SemanticLowerer.ValidationCoreResult validatedResult(
            SemanticLowerer.ValidationCoreResult result, String what) {
        if (result == null) {
            return null;
        }
        check(result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + " lowers to a B9-validated unit: "
                + (result.lowering() == null ? "null" : result.lowering().diagnostics()));
        if (result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return null;
        }
        return result;
    }

    private static void assertE6005(SemanticLowerer.ValidationCoreResult result, String rule,
                                    String what) {
        check(result != null, what + " lowers (the production-time check is the rejecting "
            + "authority)");
        if (result == null) {
            return;
        }
        check(result.lowering() != null && result.lowering().hasErrors(),
            what + " is rejected with E6005");
        if (result.lowering() == null || !result.lowering().hasErrors()) {
            return;
        }
        List<CompilerDiagnostic> diagnostics = result.lowering().diagnostics();
        check(diagnostics.size() == 1, what + " produces exactly one diagnostic; got "
            + diagnostics.size());
        for (CompilerDiagnostic diagnostic : diagnostics) {
            check("E6005".equals(diagnostic.code()), what + " diagnostic code is E6005");
            check(diagnostic.diagnosticCode() == DiagnosticCode.E6005,
                what + " diagnosticCode is E6005");
            check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
                what + " phase is BACKEND_LOWERING");
            String message = diagnostic.message();
            check(message.startsWith("Common semantic lowering failed"),
                what + " message instantiates the registry-owned E6005 template");
            String[] byRule = message.split("validatorRule ");
            check(byRule.length == 2 && byRule[1].startsWith(rule + ","),
                what + " is named as the validator rule " + rule + " (exactly one rule "
                    + "per fixture); got \"" + message + "\"");
            check(message.contains("capability BINDINGS"),
                what + " message carries capability BINDINGS");
            check(message.contains("deal.semantic-ir/2"),
                what + " message carries the IR version");
            check(message.contains("BindingsProductionValidator " + rule),
                what + " message carries the BindingsProductionValidator origin");
        }
    }

    // =========================================================================
    // IR-level synthetic fixtures (the SemanticIrValidatorTest discipline)
    // =========================================================================

    private static final ModuleId MOD = new ModuleId("mod.binding");
    private static final String IFACE = "interface-digest-1";
    private static final String REGISTRY = "capability-registry-hash-1";
    private static final String LCH =
        LoweringContextHash.of(SemanticProfile.DEAL_V1_3_INT32, REGISTRY);

    private static final RuntimeDescriptor INT = RuntimeDescriptor.Int.INSTANCE;
    private static final RuntimeDescriptor.Func SIG0 =
        new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE);
    private static final RuntimeDescriptor.Func SIG1 = new RuntimeDescriptor.Func(
        List.of(RuntimeDescriptor.Int.INSTANCE), RuntimeDescriptor.Null.INSTANCE);
    private static final RuntimeDescriptor.Func SIG2 = new RuntimeDescriptor.Func(
        List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
        RuntimeDescriptor.Null.INSTANCE);

    private static final BlockId INIT_BLOCK = new BlockId(0);

    private static int nextOp = 1;
    private static int nextVal = 1;
    private static int nextBinding = 1;
    private static int nextFunction = 1;
    private static int nextBlock = 100;
    private static int nextIdentity = 1000;

    private static OpId nextOpId() {
        return new OpId(MOD, nextOp++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextVal++);
    }

    private static BindingId nextBindingId() {
        return new BindingId(nextBinding++);
    }

    private static FunctionId nextFunctionId() {
        return new FunctionId(nextFunction++);
    }

    private static BlockId nextBlock() {
        return new BlockId(nextBlock++);
    }

    private static FunctionAllocationIdentity nextIdentity() {
        return new FunctionAllocationIdentity(nextIdentity++);
    }

    private static SourceOrigin origin(OpId parent) {
        return new SourceOrigin("test.deal", SourceSpan.synthetic("test.deal"),
            SourceOriginKind.SYNTHETIC, new AnchorId(0), parent);
    }

    /** The offending operation's own copied USER origin (ISSUE-0750 D3). */
    private static SourceOrigin userOrigin(OpId parent) {
        return new SourceOrigin("test.deal",
            new SourceSpan("test.deal", 5, 3, 5, 8, 40, 43),
            SourceOriginKind.USER, new AnchorId(11), parent);
    }

    /** The same op with a different origin (all other components unchanged). */
    private static SemanticOp withOrigin(SemanticOp op, SourceOrigin origin) {
        return new SemanticOp(op.opId(), op.kind(), origin, op.result(), op.resultType(),
            op.operands(), op.operandTypes(), op.payload(), op.failurePolicy(), op.contract());
    }

    /**
     * The bindings failure origins (ISSUE-0750): a per-op rule carries the
     * offending operation's own SOURCE range (including the duplicate-INIT
     * conflict, whose second init in the retained unit order detects the
     * violation); an offsetless known-file origin keeps its file but stays
     * SYNTHETIC; and a rule conflict with no identified offending operation
     * keeps the canonical empty-file synthetic range.
     */
    private static void testFailureOriginProjection() {
        System.out.println("-- bindings failure origins: the offending op's exact range, "
            + "the known-file offsetless control, the originless conflict --");

        // The offending load's own origin survives the shared E6005.
        BindingId x = nextBindingId();
        ValueId constValue = nextValue();
        SemanticOp alloc = allocOp(x, INIT_BLOCK, 0);
        SemanticOp init = initOp(x, 0, constValue);
        SemanticOp stale = withOrigin(opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(x, 5), nextValue(), INT, List.of(), List.of(),
            FailurePolicyId.NO_DEAL_FAILURE, null), userOrigin(null));
        LoweredModuleUnit unit = unit(Map.of(), Map.of(), List.of(alloc, init, stale));
        StructuredBodyTable table = tableOf(Map.of(INIT_BLOCK, List.of(alloc, init, stale)));
        Optional<CompilerDiagnostic> failure =
            BindingsProductionValidator.validate(unit, table);
        assertE6005(failure, "BINDING_GENERATION_RESOLUTION", "the stale-generation load",
            "stale/unresolvable");
        check(failure.isPresent()
                && userOrigin(null).span().range().equals(failure.get().range()),
            "the BINDING_GENERATION_RESOLUTION failure carries the offending load's own "
                + "SOURCE range: " + (failure.isEmpty() ? "none" : failure.get().range()));
        check(failure.isPresent() && failure.get().range().origin() == RangeOrigin.SOURCE
                && "test.deal".equals(failure.get().range().file())
                && failure.get().range().startLine() == 5
                && failure.get().range().startColumn() == 3
                && failure.get().range().endColumn() == 9
                && failure.get().range().startScalarOffset() == 40
                && failure.get().range().endScalarOffset() == 43
                && failure.get().range().scalarLength() == 3,
            "the failure range is the offending load's own exact half-open span: "
                + (failure.isEmpty() ? "none" : failure.get().range()));
        check(failure.isPresent() && failure.get().notes().isEmpty(),
            "the well-formed origin adds no normalization note");

        // The known-file offsetless control: the same failure on an op whose
        // origin carries no offsets keeps the file but stays SYNTHETIC.
        BindingId y = nextBindingId();
        SemanticOp allocY = allocOp(y, INIT_BLOCK, 0);
        SemanticOp initY = initOp(y, 0, nextValue());
        SemanticOp staleY = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(y, 5), nextValue(), INT, List.of(), List.of(),
            FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unitY = unit(Map.of(), Map.of(), List.of(allocY, initY, staleY));
        StructuredBodyTable tableY = tableOf(Map.of(INIT_BLOCK, List.of(allocY, initY, staleY)));
        Optional<CompilerDiagnostic> offsetless =
            BindingsProductionValidator.validate(unitY, tableY);
        assertE6005(offsetless, "BINDING_GENERATION_RESOLUTION",
            "the offsetless-origin stale-generation load", "stale/unresolvable");
        check(offsetless.isPresent()
                && offsetless.get().range().origin() == RangeOrigin.SYNTHETIC
                && offsetless.get().range().isCanonicalSynthetic()
                && "test.deal".equals(offsetless.get().range().file()),
            "an offsetless span keeps its known file but stays SYNTHETIC, never a "
                + "SOURCE anchor: "
                + (offsetless.isEmpty() ? "none" : offsetless.get().range()));

        // The duplicate-INIT conflict: the rule is detected on the second
        // init in the retained unit order, whose own SOURCE range is the
        // offending span; the earlier init's distinct origin is never
        // substituted.
        BindingId z = nextBindingId();
        SemanticOp allocZ = allocOp(z, INIT_BLOCK, 0);
        SourceOrigin firstInitOrigin = new SourceOrigin("first.deal",
            new SourceSpan("first.deal", 1, 1, 1, 6, 0, 5),
            SourceOriginKind.USER, new AnchorId(21), null);
        SourceOrigin secondInitOrigin = new SourceOrigin("second.deal",
            new SourceSpan("second.deal", 2, 3, 2, 8, 30, 35),
            SourceOriginKind.USER, new AnchorId(22), null);
        SemanticOp initZ1 = withOrigin(initOp(z, 0, nextValue()), firstInitOrigin);
        SemanticOp initZ2 = withOrigin(initOp(z, 0, nextValue()), secondInitOrigin);
        LoweredModuleUnit unitZ = unit(Map.of(), Map.of(), List.of(allocZ, initZ1, initZ2));
        StructuredBodyTable tableZ =
            tableOf(Map.of(INIT_BLOCK, List.of(allocZ, initZ1, initZ2)));
        Optional<CompilerDiagnostic> duplicateInit =
            BindingsProductionValidator.validate(unitZ, tableZ);
        assertE6005(duplicateInit, "BINDING_INIT_ONCE", "the duplicate-INIT conflict",
            "has 2 BINDING_INIT ops");
        check(duplicateInit.isPresent()
                && secondInitOrigin.span().range().equals(duplicateInit.get().range()),
            "the duplicate-INIT failure carries the second init's own SOURCE range: "
                + (duplicateInit.isEmpty() ? "none" : duplicateInit.get().range()));
        check(duplicateInit.isPresent()
                && duplicateInit.get().range().origin() == RangeOrigin.SOURCE
                && "second.deal".equals(duplicateInit.get().range().file())
                && duplicateInit.get().range().startLine() == 2
                && duplicateInit.get().range().startColumn() == 3
                && duplicateInit.get().range().endColumn() == 9
                && duplicateInit.get().range().startScalarOffset() == 30
                && duplicateInit.get().range().endScalarOffset() == 35
                && duplicateInit.get().range().scalarLength() == 5,
            "the duplicate-INIT range is the second init's own exact half-open span: "
                + (duplicateInit.isEmpty() ? "none" : duplicateInit.get().range()));
        check(duplicateInit.isPresent()
                && !firstInitOrigin.span().range().equals(duplicateInit.get().range()),
            "the diagnosing init's range is not the earlier init's origin: "
                + (duplicateInit.isEmpty() ? "none" : duplicateInit.get().range()));

        // The genuinely originless control: an import-alias allocation named
        // by no MODULE_IMPORT completion identifies no offending operation,
        // so the canonical empty-file synthetic range stays.
        BindingId aliasA = nextBindingId();
        BindingId aliasB = nextBindingId();
        SemanticOp aliasAllocA = allocOp(aliasA, INIT_BLOCK, 0);
        SemanticOp aliasAllocB = allocOp(aliasB, INIT_BLOCK, 0);
        SemanticOp completion = moduleImportOp(aliasA);
        LoweredModuleUnit unitAlias = unit(Map.of(), Map.of(),
            List.of(aliasAllocA, aliasAllocB, completion));
        StructuredBodyTable tableAlias = tableOf(Map.of(INIT_BLOCK,
            List.of(aliasAllocA, aliasAllocB, completion)));
        Optional<CompilerDiagnostic> originless = BindingsProductionValidator.validate(
            unitAlias, tableAlias,
            new ClassBindingFacts(Set.of(), Set.of(aliasB)));
        assertE6005(originless, "BINDING_INIT_ONCE",
            "the import alias named by no completion",
            "is named by no MODULE_IMPORT completion");
        check(originless.isPresent() && originless.get().range().isCanonicalSynthetic()
                && "".equals(originless.get().range().file()),
            "a conflict with no identified offending operation keeps the canonical "
                + "empty-file synthetic range: "
                + (originless.isEmpty() ? "none" : originless.get().range()));
    }

    /**
     * The GROUP_SHAPE offender attribution (ISSUE-0750 D3): a separate
     * BINDING_ALLOC or BINDING_INIT for a member binding is named by the
     * group message but is itself the offending operation, so the
     * diagnostic carries the separate op's own range. The group keeps
     * only the message role; an originless group yields to an available
     * offender anchor, and an offsetless offender keeps its own known
     * file and SYNTHETIC anchor instead of borrowing the group's source
     * location.
     */
    private static void testGroupShapeOffenderOrigins() {
        System.out.println("-- group-shape offender origins: the separate ALLOC/INIT op's "
            + "own range, the originless group, the offsetless offender --");

        // The distinct group origin shared by the probes below.
        SourceOrigin groupSource = new SourceOrigin("group.deal",
            new SourceSpan("group.deal", 2, 3, 2, 8, 10, 15),
            SourceOriginKind.USER, new AnchorId(31), null);

        // A separate BINDING_ALLOC for a member binding: the failure
        // carries the separate op's own SOURCE range, never the group's.
        BindingId allocMember = nextBindingId();
        FunctionId allocMemberFn = nextFunctionId();
        BlockId allocMemberBody = nextBlock();
        SourceOrigin rogueAllocSource = new SourceOrigin("rogue.deal",
            new SourceSpan("rogue.deal", 4, 2, 4, 9, 30, 37),
            SourceOriginKind.USER, new AnchorId(32), null);
        SemanticOp groupAlloc = withOrigin(
            groupOp(List.of(allocMember), List.of(allocMemberFn)), groupSource);
        SemanticOp rogueAlloc = withOrigin(
            allocOp(allocMember, INIT_BLOCK, 0), rogueAllocSource);
        LoweredModuleUnit unitAlloc = unit(
            Map.of(allocMemberFn,
                function(allocMemberFn, SIG0, List.of(), allocMemberBody)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(allocMemberFn, allocMemberBody)),
            List.of(groupAlloc, rogueAlloc));
        StructuredBodyTable tableAlloc = tableOf(Map.of(INIT_BLOCK,
            List.of(groupAlloc, rogueAlloc), allocMemberBody, List.of()));
        Optional<CompilerDiagnostic> allocFailure =
            BindingsProductionValidator.validate(unitAlloc, tableAlloc);
        assertE6005(allocFailure, "GROUP_SHAPE",
            "the group member binding with a separate ALLOC",
            "separate BINDING_ALLOC", rogueAlloc.opId().toString());
        check(allocFailure.isPresent()
                && rogueAllocSource.span().range().equals(allocFailure.get().range()),
            "the separate-ALLOC failure carries the offending ALLOC's own SOURCE range: "
                + (allocFailure.isEmpty() ? "none" : allocFailure.get().range()));
        check(allocFailure.isPresent()
                && allocFailure.get().range().origin() == RangeOrigin.SOURCE
                && "rogue.deal".equals(allocFailure.get().range().file())
                && allocFailure.get().range().startLine() == 4
                && allocFailure.get().range().startColumn() == 2
                && allocFailure.get().range().endColumn() == 10
                && allocFailure.get().range().startScalarOffset() == 30
                && allocFailure.get().range().endScalarOffset() == 37
                && allocFailure.get().range().scalarLength() == 7,
            "the separate-ALLOC range is the offending op's own exact half-open span: "
                + (allocFailure.isEmpty() ? "none" : allocFailure.get().range()));
        check(allocFailure.isPresent()
                && !groupSource.span().range().equals(allocFailure.get().range()),
            "the separate-ALLOC range is never the enclosing group's origin: "
                + (allocFailure.isEmpty() ? "none" : allocFailure.get().range()));

        // A separate BINDING_INIT for a member binding: the same
        // attribution through the INIT branch.
        BindingId initMember = nextBindingId();
        FunctionId initMemberFn = nextFunctionId();
        BlockId initMemberBody = nextBlock();
        SourceOrigin rogueInitSource = new SourceOrigin("init-rogue.deal",
            new SourceSpan("init-rogue.deal", 7, 5, 7, 11, 70, 76),
            SourceOriginKind.USER, new AnchorId(33), null);
        SemanticOp groupInit = withOrigin(
            groupOp(List.of(initMember), List.of(initMemberFn)), groupSource);
        SemanticOp rogueInit = withOrigin(
            initOp(initMember, 0, nextValue()), rogueInitSource);
        LoweredModuleUnit unitInit = unit(
            Map.of(initMemberFn,
                function(initMemberFn, SIG0, List.of(), initMemberBody)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(initMemberFn, initMemberBody)),
            List.of(groupInit, rogueInit));
        StructuredBodyTable tableInit = tableOf(Map.of(INIT_BLOCK,
            List.of(groupInit, rogueInit), initMemberBody, List.of()));
        Optional<CompilerDiagnostic> initFailure =
            BindingsProductionValidator.validate(unitInit, tableInit);
        assertE6005(initFailure, "GROUP_SHAPE",
            "the group member binding with a separate INIT",
            "separate BINDING_INIT", rogueInit.opId().toString());
        check(initFailure.isPresent()
                && rogueInitSource.span().range().equals(initFailure.get().range()),
            "the separate-INIT failure carries the offending INIT's own SOURCE range: "
                + (initFailure.isEmpty() ? "none" : initFailure.get().range()));
        check(initFailure.isPresent()
                && initFailure.get().range().origin() == RangeOrigin.SOURCE
                && "init-rogue.deal".equals(initFailure.get().range().file())
                && initFailure.get().range().startLine() == 7
                && initFailure.get().range().startColumn() == 5
                && initFailure.get().range().endColumn() == 12
                && initFailure.get().range().startScalarOffset() == 70
                && initFailure.get().range().endScalarOffset() == 76
                && initFailure.get().range().scalarLength() == 6,
            "the separate-INIT range is the offending op's own exact half-open span: "
                + (initFailure.isEmpty() ? "none" : initFailure.get().range()));
        check(initFailure.isPresent()
                && !groupSource.span().range().equals(initFailure.get().range()),
            "the separate-INIT range is never the enclosing group's origin: "
                + (initFailure.isEmpty() ? "none" : initFailure.get().range()));

        // An originless group with an identifiable offending ALLOC: the
        // offender's own range still wins; the canonical empty-file
        // synthetic anchor is never substituted for an available source
        // location.
        SourceOrigin originlessGroup = new SourceOrigin("", SourceSpan.synthetic(""),
            SourceOriginKind.SYNTHETIC, new AnchorId(0), null);
        BindingId originlessMember = nextBindingId();
        FunctionId originlessMemberFn = nextFunctionId();
        BlockId originlessMemberBody = nextBlock();
        SemanticOp groupSynthetic = withOrigin(
            groupOp(List.of(originlessMember), List.of(originlessMemberFn)),
            originlessGroup);
        SemanticOp rogueSyntheticAlloc = withOrigin(
            allocOp(originlessMember, INIT_BLOCK, 0), rogueAllocSource);
        LoweredModuleUnit unitSynthetic = unit(
            Map.of(originlessMemberFn,
                function(originlessMemberFn, SIG0, List.of(), originlessMemberBody)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(originlessMemberFn,
                    originlessMemberBody)),
            List.of(groupSynthetic, rogueSyntheticAlloc));
        StructuredBodyTable tableSynthetic = tableOf(Map.of(INIT_BLOCK,
            List.of(groupSynthetic, rogueSyntheticAlloc), originlessMemberBody, List.of()));
        Optional<CompilerDiagnostic> syntheticFailure =
            BindingsProductionValidator.validate(unitSynthetic, tableSynthetic);
        assertE6005(syntheticFailure, "GROUP_SHAPE",
            "the originless group with an identifiable separate ALLOC",
            "separate BINDING_ALLOC");
        check(syntheticFailure.isPresent()
                && rogueAllocSource.span().range().equals(syntheticFailure.get().range()),
            "an originless group does not replace the offending ALLOC's own SOURCE "
                + "range: "
                + (syntheticFailure.isEmpty() ? "none" : syntheticFailure.get().range()));
        check(syntheticFailure.isPresent()
                && !syntheticFailure.get().range().isCanonicalSynthetic(),
            "the available offender anchor is never the canonical empty-file synthetic "
                + "range: "
                + (syntheticFailure.isEmpty() ? "none" : syntheticFailure.get().range()));

        // An offsetless offending ALLOC with a source-bearing group: the
        // offending op keeps its own known file and stays SYNTHETIC; the
        // group's SOURCE location is never borrowed.
        BindingId offsetlessMember = nextBindingId();
        FunctionId offsetlessMemberFn = nextFunctionId();
        BlockId offsetlessMemberBody = nextBlock();
        SemanticOp groupSourceOp = withOrigin(
            groupOp(List.of(offsetlessMember), List.of(offsetlessMemberFn)), groupSource);
        SemanticOp rogueOffsetless = withOrigin(
            allocOp(offsetlessMember, INIT_BLOCK, 0), origin(null));
        LoweredModuleUnit unitOffsetless = unit(
            Map.of(offsetlessMemberFn,
                function(offsetlessMemberFn, SIG0, List.of(), offsetlessMemberBody)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(offsetlessMemberFn,
                    offsetlessMemberBody)),
            List.of(groupSourceOp, rogueOffsetless));
        StructuredBodyTable tableOffsetless = tableOf(Map.of(INIT_BLOCK,
            List.of(groupSourceOp, rogueOffsetless), offsetlessMemberBody, List.of()));
        Optional<CompilerDiagnostic> offsetlessFailure =
            BindingsProductionValidator.validate(unitOffsetless, tableOffsetless);
        assertE6005(offsetlessFailure, "GROUP_SHAPE",
            "the offsetless separate ALLOC with a source-bearing group",
            "separate BINDING_ALLOC");
        check(offsetlessFailure.isPresent()
                && origin(null).span().range().equals(offsetlessFailure.get().range()),
            "the offsetless offender keeps its own anchor instead of the group's span: "
                + (offsetlessFailure.isEmpty() ? "none" : offsetlessFailure.get().range()));
        check(offsetlessFailure.isPresent()
                && offsetlessFailure.get().range().origin() == RangeOrigin.SYNTHETIC
                && "test.deal".equals(offsetlessFailure.get().range().file())
                && !groupSource.span().range().equals(offsetlessFailure.get().range()),
            "an offsetless offending op stays SYNTHETIC with its known file, never the "
                + "group's SOURCE location: "
                + (offsetlessFailure.isEmpty() ? "none" : offsetlessFailure.get().range()));
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
            SemanticValue result, OpResultType resultType, List<RuntimeDescriptor> operandTypes,
            List<ValueId> operands, FailurePolicyId policy, OpId parent) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, operandTypes, policy, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, operandTypes, policy, digest);
        return new SemanticOp(id, kind, origin(parent), result, resultType, operands,
            operandTypes, payload, policy, contract);
    }

    private static SemanticOp op(SemanticOpKind kind, KindPayload payload, SemanticValue result,
            OpResultType resultType, FailurePolicyId policy, OpId parent) {
        return opWith(nextOpId(), kind, payload, result, resultType, List.of(), List.of(),
            policy, parent);
    }

    private static SemanticOp opWithOperand(SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, ValueId operand,
            RuntimeDescriptor operandType, FailurePolicyId policy, OpId parent) {
        return opWith(nextOpId(), kind, payload, result, resultType, List.of(operandType),
            List.of(operand), policy, parent);
    }

    private static SemanticOp constInt() {
        return op(SemanticOpKind.CONST, new KindPayload.ConstPayload(new ScalarValue.Int(1)),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp allocOp(BindingId binding, BlockId scope, long generation) {
        return op(SemanticOpKind.BINDING_ALLOC,
            new KindPayload.BindingAllocPayload(binding, scope, true, BindingCellKind.DIRECT,
                generation),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp initOp(BindingId binding, long generation, ValueId value) {
        return op(SemanticOpKind.BINDING_INIT,
            new KindPayload.BindingInitPayload(binding, generation, value),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp loadOp(BindingId binding, long generation) {
        return op(SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(binding, generation),
            nextValue(), INT, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp functionLoadOp(BindingId binding, long generation) {
        return op(SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(binding, generation),
            nextValue(), SIG0, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp storeOp(BindingId binding, long generation, ValueId value) {
        return op(SemanticOpKind.BINDING_STORE,
            new KindPayload.BindingStorePayload(binding, generation, value),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    /** The generation-0-pinned capture entries of one hand-built seed. */
    private static List<BindingGeneration> pins(BindingId... bindings) {
        List<BindingGeneration> entries = new java.util.ArrayList<>();
        for (BindingId binding : bindings) {
            entries.add(new BindingGeneration(binding, 0));
        }
        return entries;
    }

    private static SemanticOp closureNewOp(FunctionId function, RuntimeDescriptor.Func signature,
            List<BindingGeneration> captures, BlockId body, ValueId result) {
        return op(SemanticOpKind.CLOSURE_NEW,
            new KindPayload.ClosureNewPayload(function, signature, captures,
                new FunctionExecutionBinding.LoweredBody(function, body)),
            result, signature, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp groupOp(List<BindingId> bindings, List<FunctionId> functions) {
        return op(SemanticOpKind.RECURSIVE_GROUP_INIT,
            new KindPayload.RecursiveGroupInitPayload(bindings, functions),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp adaptOp(CaptureMode mode, AdaptSourceRef source,
            RuntimeDescriptor.Func sourceSignature, RuntimeDescriptor.Func targetSignature,
            BindingImmutabilityProof proof, List<ValueId> operands,
            List<RuntimeDescriptor> operandTypes, ValueId result) {
        return opWith(nextOpId(), SemanticOpKind.FUNCTION_ADAPT,
            new KindPayload.FunctionAdaptPayload(sourceSignature, targetSignature, mode, source,
                proof),
            result, targetSignature, operandTypes, operands,
            FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp forEachOp(BindingId binding, long generation, BlockId body) {
        return op(SemanticOpKind.FOR_EACH,
            new KindPayload.ForEachPayload(deal.semantic.ir.IterationMode.STRING_SCALARS,
                nextValue(), binding, generation, body),
            null, null, FailurePolicyId.TYPE_DESCRIPTOR, null);
    }

    private static SemanticOp tryCatchOp(BlockId tryBlock, BindingId catchBinding,
            BlockId catchBlock) {
        return op(SemanticOpKind.TRY_CATCH,
            new KindPayload.TryCatchPayload(tryBlock, catchBinding, catchBlock),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp branchOp(BlockId selected, BlockId alternate) {
        return op(SemanticOpKind.BRANCH,
            new KindPayload.BranchPayload(deal.semantic.ir.ControlSelector.IF, nextValue(),
                selected, alternate),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }


    private static SemanticOp boundaryOp(BoundaryKind kind, RuntimeDescriptor descriptor,
            ValueId input) {
        return op(SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, input,
                new BoundaryRealization.RuntimeValidation("runtime-validation")),
            null, null, descriptor instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR, null);
    }

    private static SemanticOp moduleImportOp(BindingId... aliasCells) {
        return op(SemanticOpKind.MODULE_IMPORT,
            new KindPayload.ModuleImportPayload("lib.math", new ModuleId("lib.math"),
                deal.semantic.ir.ModuleImportKind.COMPILED, List.of(aliasCells)),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static SemanticOp classDefaultOp(BlockId defaultBlock) {
        return op(SemanticOpKind.CLASS_DEFAULT,
            new KindPayload.ClassDefaultPayload(new ClassId("mod.binding", "C"), "handler",
                defaultBlock),
            nextValue(), SIG1, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    private static LoweredFunction function(FunctionId id, RuntimeDescriptor.Func signature,
            List<BindingGeneration> captures, BlockId body) {
        return new LoweredFunction(id, signature, captures, body);
    }

    private static LoweredModuleUnit unit(Map<FunctionId, LoweredFunction> functions,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> registry,
            List<SemanticOp> ops) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_3_INT32, MOD, IFACE, LCH, Set.of(), Map.of(), Map.of(),
            functions, new ModuleInitPlan(List.of(), INIT_BLOCK), ExportPlan.empty(), registry,
            ops, new StructuredBodyTable(Map.of(), Map.of()),
            new ClassFactoryRegistry(Map.of()),
            new JsonDefaultChildTable(Map.of()), ClassBindingFacts.empty());
    }

    /** Builds a table from block → ops and derives the exact inverse map. */
    private static StructuredBodyTable tableOf(Map<BlockId, List<SemanticOp>> blocks) {
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        for (Map.Entry<BlockId, List<SemanticOp>> entry : blocks.entrySet()) {
            List<OpId> ids = new ArrayList<>();
            for (SemanticOp op : entry.getValue()) {
                ids.add(op.opId());
                opBlocks.put(op.opId(), entry.getKey());
            }
            blockOps.put(entry.getKey(), ids);
        }
        return new StructuredBodyTable(blockOps, opBlocks);
    }

    private static void assertPass(Optional<CompilerDiagnostic> diagnostic, String what) {
        check(diagnostic.isEmpty(), what + " passes validation"
            + (diagnostic.isPresent() ? ": " + diagnostic.get().message() : ""));
    }

    private static void assertE6005(Optional<CompilerDiagnostic> diagnostic, String rule,
            String what, String... contains) {
        check(diagnostic.isPresent(), what + " is rejected with E6005 " + rule);
        if (diagnostic.isEmpty()) {
            return;
        }
        CompilerDiagnostic d = diagnostic.get();
        check("E6005".equals(d.code()), what + " diagnostic code is E6005");
        check(d.diagnosticCode() == DiagnosticCode.E6005, what + " diagnosticCode is E6005");
        check(DiagnosticCode.E6005.phase() == DiagnosticCode.Phase.BACKEND_LOWERING,
            what + " phase is BACKEND_LOWERING");
        String message = d.message();
        check(message.startsWith("Common semantic lowering failed"),
            what + " message instantiates the registry-owned E6005 template");
        String[] byRule = message.split("validatorRule ");
        check(byRule.length == 2 && byRule[1].startsWith(rule + ","),
            what + " is named as the validator rule " + rule + " (exactly one rule per "
                + "fixture); got \"" + message + "\"");
        check(message.contains("capability BINDINGS"),
            what + " message carries capability BINDINGS");
        check(message.contains("deal.semantic-ir/2"), what + " message carries the IR version");
        check(message.contains("mod.binding"), what + " message carries the module path");
        check(message.contains("BindingsProductionValidator " + rule),
            what + " message carries the BindingsProductionValidator origin");
        for (String c : contains) {
            check(message.contains(c), what + " message contains \"" + c + "\"; got \""
                + message + "\"");
        }
    }

    // =========================================================================
    // 1. Surface and rule-name contract
    // =========================================================================

    private static void testSurfaceAndRules() {
        System.out.println("-- surface and pinned rule names --");

        expectNpe(() -> BindingsProductionValidator.validate(null, tableOf(Map.of())),
            "validate with a null unit");
        expectNpe(() -> BindingsProductionValidator.validate(
                unit(Map.of(), Map.of(), List.of()), null),
            "validate with a null table");

        // Purity and determinism: repeated validation of the same inputs
        // yields the identical outcome.
        SemanticOp x = constInt();
        LoweredModuleUnit simple = unit(Map.of(), Map.of(), List.of(x));
        StructuredBodyTable table = tableOf(Map.of(INIT_BLOCK, List.of(x)));
        Optional<CompilerDiagnostic> first = BindingsProductionValidator.validate(simple, table);
        Optional<CompilerDiagnostic> second = BindingsProductionValidator.validate(simple, table);
        check(first.equals(second), "repeated validation of the same unit is identical");
    }

    private static void expectNpe(Runnable runnable, String what) {
        try {
            runnable.run();
            fail("expected NullPointerException for " + what + ", but no exception was raised");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Throwable other) {
            fail("expected NullPointerException for " + what + ", got "
                + other.getClass().getSimpleName() + ": " + other.getMessage());
        }
    }

    // =========================================================================
    // 2. Source-level positive corpus (each must validate)
    // =========================================================================

    private static void testSourceLevelPositives() {
        System.out.println("-- source-level positive corpus (each must validate) --");

        // (a) ISSUE-0717 (defect snippets 21/22): the store-to-pre-init
        // shape `let x: int = (x = 1);` no longer reaches the validator —
        // the assignment target inside the declaration's own initializer
        // resolves against the enclosing scope (none here) under the
        // non-hoisting rule, so the checker rejects the declaration with
        // E2001. The validator's store exemption (the rule covers loads
        // only) keeps its IR-level coverage through the stale-store
        // negative below.
        checkerRejects("let x: int = (x = 1);", "E2001",
            "(a) the store-to-pre-init shape");

        SemanticLowerer.ValidationCoreResult result = validatedResult("function selfReference(): null { let g: () => null = selfReference; }\nfunction forwardReference(): null { let h: () => null = laterFn; }\nfunction laterFn(): null {}\nfunction even(): null { let oddRef: () => null = odd; }\nfunction odd(): null { let evenRef: () => null = even; }\nfunction parameterCapture(p: int): null {\n  let g: () => null = function(): null { let read: int = p; };\n}\nfunction catchCapture(): null {\n  try { let t = 1; }\n  catch (e) { let g: () => null = function(): null { let copy = e; }; }\n}\nfunction iterationCapture(): null {\n  for (let c: string of \"ab\") {\n    let g: () => null = function(): null { let read: string = c; };\n  }\n}\nfunction nestedSelfReference(): null {\n  function g(): null { let copy: () => null = g; }\n}\nfunction nestedCaptureChain(): null {\n  let x: int = 1;\n  function make(): null {\n    let inner: () => null = function(): null { let read: int = x; };\n  }\n}\nfunction ancestorLoads(): null {\n  let x = 1;\n  try { let t = x; } catch (e) { let y = x; }\n}\nfunction thunkCapture(): null {\n  let x = 1;\n  let s: () => null = function(): null { let read: int = x; };\n  let k: (a: int, b: int) => null = (s = function(): null { let read2: int = x; });\n}\nfunction provedLoad(): null {\n  let f: (x: int) => null = inner;\n  let g: (a: int, b: int) => null = f;\n}\nfunction inner(x: int): null {}\n", "the surviving source-level positive shapes");
        testCellKindInvariant(result);
    }

    // =========================================================================
    // 3. Source-level negatives
    // =========================================================================

    private static void testSourceLevelNegatives() {
        System.out.println("-- source-level negatives (the checker is the rejecting "
            + "authority) --");

        // ISSUE-0717 (defect snippet 22): the self-read is rejected by the
        // checker's non-hoisting rule before the binding validator ever
        // sees it — the reference inside `let x: int = x;` resolves
        // against the enclosing scope (none here), so the declaration is
        // E2001 and the INIT_DOMINATES_LOAD source shape is unreachable
        // through the production entry. The IR-level
        // {@link #testInitDominatesLoadNegative()} keeps the validator
        // arm's direct coverage.
        checkerRejects("let x: int = x;", "E2001",
            "the self-read let x: int = x;");
    }

    /** One source the real checker must reject with the exact code. */
    private static void checkerRejects(String source, String code, String what) {
        LexResult lex = new Lexer(source, SOURCE_ID).tokenize();
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID,
            lex.directiveEvents()).parse();
        NameResolver nr = new NameResolver(SOURCE_ID, null);
        SymbolTable symTable = nr.resolve(parse.program());
        CheckResult result = TypeChecker.check(SOURCE_ID, symTable, nr, parse.program());
        boolean found = result.diagnostics().stream()
            .anyMatch(diagnostic -> code.equals(diagnostic.code()));
        check(found, what + " is rejected by the checker with " + code + ": "
            + result.diagnostics());
    }

    // =========================================================================
    // 4. IR-level negatives
    // =========================================================================

    private static void testIrLevelNegatives() {
        System.out.println("-- IR-level negatives (each fails with E6005 naming the rule) --");

        testInitDominatesLoadNegative();
        testStaleGenerationNegatives();
        testCaptureResolutionNegatives();
        testGroupShapeNegatives();
        testAdapterPairNegatives();
        testAdapterSourceShapeNegatives();
        testRegistryOneToOneNegatives();
        testNoAdapterAtBoundaryNegative();
        testBindingInitOnceNegatives();
    }

    /**
     * The closure-capturing-own-initializer shape of
     * {@code let x: int = g(() => x);}: the closure is created inside
     * x's initializer before INIT(x) commits, so the load would read an
     * uninitialized cell — the chain's closing step fails arm 2.
     */
    private static void testInitDominatesLoadNegative() {
        // The enclosing function F with its name binding.
        BindingId fBinding = nextBindingId();
        FunctionId fFunction = nextFunctionId();
        BlockId fBody = nextBlock();
        ValueId fIdentity = nextValue();
        // x's declaration and the capturing closure inside F's body.
        BindingId xBinding = nextBindingId();
        FunctionId closureFunction = nextFunctionId();
        BlockId closureBody = nextBlock();
        ValueId closureIdentity = nextValue();
        ValueId constValue = nextValue();
        SemanticOp allocF = allocOp(fBinding, INIT_BLOCK, 0);
        SemanticOp closureF = closureNewOp(fFunction, SIG0, List.of(), fBody, fIdentity);
        SemanticOp initF = initOp(fBinding, 0, fIdentity);
        SemanticOp allocX = allocOp(xBinding, fBody, 0);
        SemanticOp captureClosure = closureNewOp(closureFunction, SIG0, pins(xBinding),
            closureBody, closureIdentity);
        SemanticOp filler = op(SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(1)),
            constValue, INT, FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp initX = initOp(xBinding, 0, constValue);
        SemanticOp loadX = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(xBinding, 0),
            nextValue(), INT, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unit = unit(
            Map.of(fFunction, function(fFunction, SIG0, List.of(), fBody),
                closureFunction, function(closureFunction, SIG0, pins(xBinding),
                    closureBody)),
            Map.of(new FunctionAllocationIdentity(fIdentity.id()),
                new FunctionExecutionBinding.LoweredBody(fFunction, fBody),
                new FunctionAllocationIdentity(closureIdentity.id()),
                new FunctionExecutionBinding.LoweredBody(closureFunction, closureBody)),
            List.of(allocF, closureF, initF, allocX, captureClosure, filler, initX, loadX));
        StructuredBodyTable table = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(allocF, closureF, initF)),
            Map.entry(fBody, List.of(allocX, captureClosure, filler, initX)),
            Map.entry(closureBody, List.of(loadX))));
        assertE6005(BindingsProductionValidator.validate(unit, table), "INIT_DOMINATES_LOAD",
            "the closure-capturing-own-initializer shape", "not dominated by");
    }

    private static void testStaleGenerationNegatives() {
        // A stale store generation is the same rule (stores are exempt
        // only from INIT_DOMINATES_LOAD, never from generation
        // resolution).
        BindingId y = nextBindingId();
        SemanticOp allocY = allocOp(y, INIT_BLOCK, 0);
        SemanticOp initY = initOp(y, 0, nextValue());
        SemanticOp staleStore = op(SemanticOpKind.BINDING_STORE,
            new KindPayload.BindingStorePayload(y, 7, nextValue()),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unitY = unit(Map.of(), Map.of(),
            List.of(allocY, initY, staleStore));
        StructuredBodyTable tableY = tableOf(Map.of(INIT_BLOCK,
            List.of(allocY, initY, staleStore)));
        assertE6005(BindingsProductionValidator.validate(unitY, tableY),
            "BINDING_GENERATION_RESOLUTION", "a stale-generation store",
            "stale/unresolvable");


    }

    private static void testCaptureResolutionNegatives() {
        // A capture chain closing with zero producing allocations: the
        // capture names a binding with no producing allocation anywhere.
        BindingId ghost = nextBindingId();
        FunctionId fn = nextFunctionId();
        BlockId body = nextBlock();
        LoweredModuleUnit unit = unit(
            Map.of(fn, function(fn, SIG0, pins(ghost), body)),
            Map.of(), List.of());
        StructuredBodyTable table = tableOf(Map.of(INIT_BLOCK, List.of(),
            body, List.of()));
        assertE6005(BindingsProductionValidator.validate(unit, table), "CAPTURE_RESOLUTION",
            "a capture chain closing with zero producing allocations");

        // A capture chain closing with multiple producing allocations:
        // two ALLOCs of one binding in two sibling BRANCH arms, both
        // visible at the closure's creation site after the branch.
        BindingId ambiguous = nextBindingId();
        FunctionId closureFn = nextFunctionId();
        BlockId closureBody = nextBlock();
        BlockId arm1 = nextBlock();
        BlockId arm2 = nextBlock();
        ValueId identity = nextValue();
        SemanticOp alloc1 = allocOp(ambiguous, arm1, 0);
        SemanticOp alloc2 = allocOp(ambiguous, arm2, 0);
        SemanticOp branch = branchOp(arm1, arm2);
        SemanticOp closure = closureNewOp(closureFn, SIG0, pins(ambiguous), closureBody,
            identity);
        LoweredModuleUnit unitM = unit(
            Map.of(closureFn, function(closureFn, SIG0, pins(ambiguous), closureBody)),
            Map.of(new FunctionAllocationIdentity(identity.id()),
                new FunctionExecutionBinding.LoweredBody(closureFn, closureBody)),
            List.of(branch, closure, alloc1, alloc2));
        Map<BlockId, List<SemanticOp>> blocks = new LinkedHashMap<>();
        blocks.put(arm1, List.of(alloc1));
        blocks.put(INIT_BLOCK, List.of(branch, closure));
        blocks.put(arm2, List.of(alloc2));
        blocks.put(closureBody, List.of());
        StructuredBodyTable tableM = tableOf(blocks);
        assertE6005(BindingsProductionValidator.validate(unitM, tableM), "CAPTURE_RESOLUTION",
            "a capture chain closing with multiple producing allocations",
            "zero or multiple producing allocations");

        // A detached-body free reference outside the captures list.
        BindingId free = nextBindingId();
        FunctionId outer = nextFunctionId();
        BlockId outerBody = nextBlock();
        ValueId outerIdentity = nextValue();
        SemanticOp allocFree = allocOp(free, INIT_BLOCK, 0);
        SemanticOp initFree = initOp(free, 0, nextValue());
        SemanticOp closureOuter = closureNewOp(outer, SIG0, List.of(), outerBody,
            outerIdentity);
        SemanticOp loadFree = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(free, 0),
            nextValue(), INT, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unitF = unit(
            Map.of(outer, function(outer, SIG0, List.of(), outerBody)),
            Map.of(new FunctionAllocationIdentity(outerIdentity.id()),
                new FunctionExecutionBinding.LoweredBody(outer, outerBody)),
            List.of(allocFree, initFree, closureOuter, loadFree));
        StructuredBodyTable tableF = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(allocFree, initFree, closureOuter)),
            Map.entry(outerBody, List.of(loadFree))));
        assertE6005(BindingsProductionValidator.validate(unitF, tableF), "CAPTURE_RESOLUTION",
            "a detached-body free reference outside the captures list",
            "outside the captures");

        // A thunk-block free reference outside the pinned
        // capturedBindings.
        BindingId thunkFree = nextBindingId();
        BlockId thunkBlock = nextBlock();
        ValueId adapterResult = nextValue();
        SemanticOp allocThunkFree = allocOp(thunkFree, INIT_BLOCK, 0);
        SemanticOp initThunkFree = initOp(thunkFree, 0, nextValue());
        SemanticOp thunkLoad = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(thunkFree, 0),
            nextValue(), INT, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp adapt = adaptOp(CaptureMode.REEVALUATE_THUNK,
            new AdaptSourceRef.Thunk(thunkBlock, List.of()),
            SIG0, SIG2, null, List.of(), List.of(), adapterResult);
        LoweredModuleUnit unitT = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(adapterResult.id()),
                new FunctionExecutionBinding.AdapterBinding(adapt.opId(),
                    CaptureMode.REEVALUATE_THUNK, new AdaptSourceRef.Thunk(thunkBlock,
                        List.of()), SIG0, SIG2)),
            List.of(allocThunkFree, initThunkFree, adapt, thunkLoad));
        StructuredBodyTable tableT = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(allocThunkFree, initThunkFree, adapt)),
            Map.entry(thunkBlock, List.of(thunkLoad))));
        assertE6005(BindingsProductionValidator.validate(unitT, tableT), "CAPTURE_RESOLUTION",
            "a thunk-block free reference outside the pinned capturedBindings",
            "outside the pinned capturedBindings");
    }

    private static void testGroupShapeNegatives() {
        // An empty bindings list.
        FunctionId member = nextFunctionId();
        BlockId body = nextBlock();
        LoweredModuleUnit unitEmpty = unit(
            Map.of(member, function(member, SIG0, List.of(), body)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(member, body)),
            List.of(groupOp(List.of(), List.of(member))));
        StructuredBodyTable tableEmpty = tableOf(Map.of(INIT_BLOCK,
            List.of(groupOp(List.of(), List.of(member))), body, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitEmpty, tableEmpty), "GROUP_SHAPE",
            "an empty group bindings list");

        // A bindings/functions length mismatch.
        BindingId b1 = nextBindingId();
        FunctionId f1 = nextFunctionId();
        FunctionId f2 = nextFunctionId();
        BlockId b1Body = nextBlock();
        BlockId b2Body = nextBlock();
        LoweredModuleUnit unitMismatch = unit(
            Map.of(f1, function(f1, SIG0, List.of(), b1Body),
                f2, function(f2, SIG0, List.of(), b2Body)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(f1, b1Body),
                nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(f2, b2Body)),
            List.of(groupOp(List.of(b1), List.of(f1, f2))));
        StructuredBodyTable tableMismatch = tableOf(Map.of(INIT_BLOCK,
            List.of(groupOp(List.of(b1), List.of(f1, f2))), b1Body, List.of(),
            b2Body, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitMismatch, tableMismatch),
            "GROUP_SHAPE", "a bindings/functions length mismatch");

    }

    private static void testAdapterPairNegatives() {
        // An exact pair: exact-signature positions store the value
        // directly — an exact pair is invalid IR for FUNCTION_ADAPT.
        BindingId x = nextBindingId();
        ValueId adapterResult = nextValue();
        SemanticOp alloc = allocOp(x, INIT_BLOCK, 0);
        SemanticOp init = initOp(x, 0, nextValue());
        SemanticOp exact = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(x, 0), SIG1, SIG1, null, List.of(), List.of(),
            adapterResult);
        LoweredModuleUnit unitExact = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(adapterResult.id()),
                new FunctionExecutionBinding.AdapterBinding(exact.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(x, 0), SIG1,
                    SIG1)),
            List.of(alloc, init, exact));
        StructuredBodyTable tableExact = tableOf(Map.of(INIT_BLOCK,
            List.of(alloc, init, exact)));
        assertE6005(BindingsProductionValidator.validate(unitExact, tableExact), "ADAPTER_PAIR",
            "an exact adapter pair (M == N)", "does not re-derive");

        // A non-assignable pair: M > N — the checker already rejects
        // such positions, and the IR-level rule re-derives the pair.
        BindingId y = nextBindingId();
        ValueId adapterResult2 = nextValue();
        SemanticOp allocY = allocOp(y, INIT_BLOCK, 0);
        SemanticOp initY = initOp(y, 0, nextValue());
        SemanticOp nonAssignable = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(y, 0), SIG2, SIG1, null, List.of(), List.of(),
            adapterResult2);
        LoweredModuleUnit unitNon = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(adapterResult2.id()),
                new FunctionExecutionBinding.AdapterBinding(nonAssignable.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(y, 0), SIG2,
                    SIG1)),
            List.of(allocY, initY, nonAssignable));
        StructuredBodyTable tableNon = tableOf(Map.of(INIT_BLOCK,
            List.of(allocY, initY, nonAssignable)));
        assertE6005(BindingsProductionValidator.validate(unitNon, tableNon), "ADAPTER_PAIR",
            "a non-assignable adapter pair (M > N)", "does not re-derive");

    }

    private static void testAdapterSourceShapeNegatives() {
        // A mode/source shape mismatch: SHARED_CELL carrying a Value.
        BindingId x = nextBindingId();
        ValueId operand = nextValue();
        ValueId adapterResult = nextValue();
        SemanticOp alloc = allocOp(x, INIT_BLOCK, 0);
        SemanticOp init = initOp(x, 0, operand);
        SemanticOp mismatch = adaptOp(CaptureMode.SHARED_CELL, new AdaptSourceRef.Value(operand),
            SIG1, SIG2, null, List.of(), List.of(), adapterResult);
        LoweredModuleUnit unitMismatch = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(adapterResult.id()),
                new FunctionExecutionBinding.AdapterBinding(mismatch.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.Value(operand), SIG1,
                    SIG2)),
            List.of(alloc, init, mismatch));
        StructuredBodyTable tableMismatch = tableOf(Map.of(INIT_BLOCK,
            List.of(alloc, init, mismatch)));
        assertE6005(BindingsProductionValidator.validate(unitMismatch, tableMismatch),
            "ADAPTER_SOURCE_SHAPE", "a mode/source shape mismatch",
            "mode must match the source shape");

        // VALUE-over-binding without the proof: the operand is produced
        // by exactly one binding load (no closure re-publishes the
        // identity) and the payload carries no proof.
        BindingId b = nextBindingId();
        ValueId loadResult = nextValue();
        ValueId adapterResult2 = nextValue();
        SemanticOp allocB = allocOp(b, INIT_BLOCK, 0);
        SemanticOp initB = initOp(b, 0, nextValue());
        SemanticOp loadB = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(b, 0),
            loadResult, SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp unproved = adaptOp(CaptureMode.VALUE, new AdaptSourceRef.Value(loadResult),
            SIG1, SIG2, null, List.of(loadResult), List.of(SIG1), adapterResult2);
        LoweredModuleUnit unitUnproved = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(adapterResult2.id()),
                new FunctionExecutionBinding.AdapterBinding(unproved.opId(),
                    CaptureMode.VALUE, new AdaptSourceRef.Value(loadResult), SIG1, SIG2)),
            List.of(allocB, initB, loadB, unproved));
        StructuredBodyTable tableUnproved = tableOf(Map.of(INIT_BLOCK,
            List.of(allocB, initB, loadB, unproved)));
        assertE6005(BindingsProductionValidator.validate(unitUnproved, tableUnproved),
            "ADAPTER_SOURCE_SHAPE", "VALUE-over-binding without its proof",
            "without its proof");

        // A proof naming the wrong binding/generation: the operand is
        // the load of {b, 0} but the proof names {other, 0}.
        BindingId b2 = nextBindingId();
        BindingId other = nextBindingId();
        ValueId loadResult2 = nextValue();
        ValueId adapterResult3 = nextValue();
        SemanticOp allocB2 = allocOp(b2, INIT_BLOCK, 0);
        SemanticOp initB2 = initOp(b2, 0, nextValue());
        SemanticOp allocOther = allocOp(other, INIT_BLOCK, 0);
        SemanticOp initOther = initOp(other, 0, nextValue());
        SemanticOp loadB2 = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(b2, 0),
            loadResult2, SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp wrongProof = adaptOp(CaptureMode.VALUE,
            new AdaptSourceRef.Value(loadResult2), SIG1, SIG2,
            new BindingImmutabilityProof(other, 0), List.of(loadResult2), List.of(SIG1),
            adapterResult3);
        LoweredModuleUnit unitWrong = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(adapterResult3.id()),
                new FunctionExecutionBinding.AdapterBinding(wrongProof.opId(),
                    CaptureMode.VALUE, new AdaptSourceRef.Value(loadResult2), SIG1,
                    SIG2)),
            List.of(allocB2, initB2, allocOther, initOther, loadB2, wrongProof));
        StructuredBodyTable tableWrong = tableOf(Map.of(INIT_BLOCK,
            List.of(allocB2, initB2, allocOther, initOther, loadB2, wrongProof)));
        assertE6005(BindingsProductionValidator.validate(unitWrong, tableWrong),
            "ADAPTER_SOURCE_SHAPE", "a proof naming the wrong binding/generation",
            "naming no producing load");

        // A proof over a non-load operand: the operand is a CLOSURE_NEW
        // result but the payload carries a proof.
        BindingId b3 = nextBindingId();
        FunctionId fn = nextFunctionId();
        BlockId body = nextBlock();
        ValueId closureResult = nextValue();
        ValueId adapterResult4 = nextValue();
        SemanticOp allocB3 = allocOp(b3, INIT_BLOCK, 0);
        SemanticOp initB3 = initOp(b3, 0, nextValue());
        SemanticOp closure = closureNewOp(fn, SIG1, List.of(), body, closureResult);
        SemanticOp nonLoadProof = adaptOp(CaptureMode.VALUE,
            new AdaptSourceRef.Value(closureResult), SIG1, SIG2,
            new BindingImmutabilityProof(b3, 0), List.of(closureResult), List.of(SIG1),
            adapterResult4);
        LoweredModuleUnit unitNonLoad = unit(
            Map.of(fn, function(fn, SIG1, List.of(), body)),
            Map.of(new FunctionAllocationIdentity(closureResult.id()),
                new FunctionExecutionBinding.LoweredBody(fn, body),
                new FunctionAllocationIdentity(adapterResult4.id()),
                new FunctionExecutionBinding.AdapterBinding(nonLoadProof.opId(),
                    CaptureMode.VALUE, new AdaptSourceRef.Value(closureResult), SIG1,
                    SIG2)),
            List.of(allocB3, initB3, closure, nonLoadProof));
        StructuredBodyTable tableNonLoad = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(allocB3, initB3, closure, nonLoadProof)),
            Map.entry(body, List.of())));
        assertE6005(BindingsProductionValidator.validate(unitNonLoad, tableNonLoad),
            "ADAPTER_SOURCE_SHAPE", "a proof over a non-load operand",
            "proof over a non-load operand");
    }

    private static void testRegistryOneToOneNegatives() {
        // An orphan key: a registration no producing op publishes.
        FunctionId orphanFn = nextFunctionId();
        BlockId orphanBody = nextBlock();
        LoweredModuleUnit unitOrphan = unit(
            Map.of(orphanFn, function(orphanFn, SIG0, List.of(), orphanBody)),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.LoweredBody(orphanFn, orphanBody)),
            List.of());
        StructuredBodyTable tableOrphan = tableOf(Map.of(INIT_BLOCK, List.of(),
            orphanBody, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitOrphan, tableOrphan),
            "REGISTRY_ONE_TO_ONE", "an orphan registry key", "produced by 0");

        // A missing registration: a CLOSURE_NEW without a registry entry.
        FunctionId missingFn = nextFunctionId();
        BlockId missingBody = nextBlock();
        ValueId identity = nextValue();
        SemanticOp closure = closureNewOp(missingFn, SIG0, List.of(), missingBody, identity);
        LoweredModuleUnit unitMissing = unit(
            Map.of(missingFn, function(missingFn, SIG0, List.of(), missingBody)),
            Map.of(), List.of(closure));
        StructuredBodyTable tableMissing = tableOf(Map.of(INIT_BLOCK, List.of(closure),
            missingBody, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitMissing, tableMissing),
            "REGISTRY_ONE_TO_ONE", "a missing closure registration",
            "missing registration");

        // A duplicate production: two CLOSURE_NEW ops publish one
        // allocation identity with a single registration.
        FunctionId dupFn = nextFunctionId();
        BlockId dupBody = nextBlock();
        ValueId dupIdentity = nextValue();
        SemanticOp closureA = closureNewOp(dupFn, SIG0, List.of(), dupBody, dupIdentity);
        SemanticOp closureB = closureNewOp(dupFn, SIG0, List.of(), dupBody, dupIdentity);
        LoweredModuleUnit unitDup = unit(
            Map.of(dupFn, function(dupFn, SIG0, List.of(), dupBody)),
            Map.of(new FunctionAllocationIdentity(dupIdentity.id()),
                new FunctionExecutionBinding.LoweredBody(dupFn, dupBody)),
            List.of(closureA, closureB));
        StructuredBodyTable tableDup = tableOf(Map.of(INIT_BLOCK,
            List.of(closureA, closureB), dupBody, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitDup, tableDup),
            "REGISTRY_ONE_TO_ONE", "a duplicate production of one key",
            "produced by 2");

        // A HostFunctionValue obligation missing at a HOST_TO_DEAL
        // function-typed boundary crossing.
        ValueId hostValue = nextValue();
        SemanticOp hostBoundary = boundaryOp(BoundaryKind.HOST_TO_DEAL, SIG1, hostValue);
        LoweredModuleUnit unitHost = unit(Map.of(), Map.of(), List.of(hostBoundary));
        StructuredBodyTable tableHost = tableOf(Map.of(INIT_BLOCK, List.of(hostBoundary)));
        assertE6005(BindingsProductionValidator.validate(unitHost, tableHost),
            "REGISTRY_ONE_TO_ONE", "a host-materialized function value without its "
                + "HostFunctionValue registration", "HostFunctionValue");

        // The host/external seam: an orphan HostFunction registration
        // (a registry key no import member read produces).
        LoweredModuleUnit unitOrphanHost = unit(Map.of(),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.HostFunction(new ModuleId("host"), "g", SIG1)),
            List.of());
        StructuredBodyTable tableOrphanHost = tableOf(Map.of(INIT_BLOCK, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitOrphanHost, tableOrphanHost),
            "REGISTRY_ONE_TO_ONE", "an orphan HostFunction registration",
            "produced by 0");

        // The host/external seam: an orphan ExternalFunction
        // registration (a registry key no export read produces).
        LoweredModuleUnit unitOrphanExternal = unit(Map.of(),
            Map.of(nextIdentity(),
                new FunctionExecutionBinding.ExternalFunction(new ModuleId("lib.math"),
                    "g", SIG1, deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY)),
            List.of());
        StructuredBodyTable tableOrphanExternal = tableOf(Map.of(INIT_BLOCK, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitOrphanExternal,
                tableOrphanExternal),
            "REGISTRY_ONE_TO_ONE", "an orphan ExternalFunction registration",
            "produced by 0");

        // The host/external seam: a duplicate production — two import
        // member reads publishing one HostFunction key.
        ValueId readIdentity = nextValue();
        SemanticOp memberReadA = opWith(nextOpId(), SemanticOpKind.MEMBER_READ,
            new KindPayload.MemberReadPayload(nextValue(), "g"),
            readIdentity, SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE,
            null);
        SemanticOp memberReadB = opWith(nextOpId(), SemanticOpKind.MEMBER_READ,
            new KindPayload.MemberReadPayload(nextValue(), "g"),
            readIdentity, SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE,
            null);
        LoweredModuleUnit unitDupRead = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(readIdentity.id()),
                new FunctionExecutionBinding.HostFunction(new ModuleId("host"), "g", SIG1)),
            List.of(memberReadA, memberReadB));
        StructuredBodyTable tableDupRead = tableOf(Map.of(INIT_BLOCK,
            List.of(memberReadA, memberReadB)));
        assertE6005(BindingsProductionValidator.validate(unitDupRead, tableDupRead),
            "REGISTRY_ONE_TO_ONE", "a duplicate import member-read production of one "
                + "HostFunction key", "produced by 2");

        // The host/external seam: a duplicate production — two export
        // reads publishing one ExternalFunction key.
        ValueId exportIdentity = nextValue();
        SemanticOp exportReadA = opWith(nextOpId(), SemanticOpKind.EXPORT_READ,
            new KindPayload.ExportReadPayload(new ModuleId("lib.math"), "g", SIG1,
                exportIdentity),
            null, null, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp exportReadB = opWith(nextOpId(), SemanticOpKind.EXPORT_READ,
            new KindPayload.ExportReadPayload(new ModuleId("lib.math"), "g", SIG1,
                exportIdentity),
            null, null, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unitDupExport = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(exportIdentity.id()),
                new FunctionExecutionBinding.ExternalFunction(new ModuleId("lib.math"),
                    "g", SIG1, deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY)),
            List.of(exportReadA, exportReadB));
        StructuredBodyTable tableDupExport = tableOf(Map.of(INIT_BLOCK,
            List.of(exportReadA, exportReadB)));
        assertE6005(BindingsProductionValidator.validate(unitDupExport, tableDupExport),
            "REGISTRY_ONE_TO_ONE", "a duplicate export-read production of one "
                + "ExternalFunction key", "produced by 2");
    }

    private static void testNoAdapterAtBoundaryNegative() {
        // The closed creation-wiring rule: an adapter result is the direct
        // input of exactly the adapted position's own cell and of no other
        // typed boundary. A stdlib return crossing alone is rejected
        // outright, a crossing beside an agreeing position cell is rejected
        // too, and a class default's published result admits no consumer
        // beyond its own CLASS_DEFAULT_FIELD construction cells.
        BindingId x = nextBindingId();
        ValueId adapterResult = nextValue();
        FunctionAllocationIdentity identity = new FunctionAllocationIdentity(
            adapterResult.id());
        SemanticOp alloc = allocOp(x, INIT_BLOCK, 0);
        SemanticOp initValue = constInt();
        SemanticOp init = initOp(x, 0, (ValueId) initValue.result());
        SemanticOp adapt = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(x, 0), SIG1, SIG2, null, List.of(), List.of(),
            adapterResult);
        SemanticOp crossing = boundaryOp(BoundaryKind.STDLIB_RETURN, SIG2,
            adapterResult);
        LoweredModuleUnit unit = unit(Map.of(),
            Map.of(identity,
                new FunctionExecutionBinding.AdapterBinding(adapt.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(x, 0), SIG1,
                    SIG2)),
            List.of(alloc, initValue, init, adapt, crossing));
        StructuredBodyTable table = tableOf(Map.of(INIT_BLOCK,
            List.of(alloc, initValue, init, adapt, crossing)));
        assertE6005(BindingsProductionValidator.validate(unit, table),
            "NO_ADAPTER_AT_BOUNDARY",
            "an adapter result wired into a STDLIB_RETURN crossing",
            "STDLIB_RETURN", "not the adapted position's own cell");

        // A second consumer of the same adapter result: the variable
        // position's own chain agrees, and the stdlib return crossing beside
        // it still fails closed.
        BindingId store = nextBindingId();
        ValueId assignedAdapter = nextValue();
        FunctionAllocationIdentity assignedIdentity = new FunctionAllocationIdentity(
            assignedAdapter.id());
        SemanticOp storeAlloc = allocOp(store, INIT_BLOCK, 0);
        SemanticOp storeInitValue = constInt();
        SemanticOp storeInit = initOp(store, 0, (ValueId) storeInitValue.result());
        SemanticOp assignedAdapt = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(store, 0), SIG1, SIG2, null, List.of(),
            List.of(), assignedAdapter);
        SemanticOp assignment = boundaryOp(BoundaryKind.VARIABLE_ASSIGNMENT, SIG2,
            assignedAdapter);
        SemanticOp commit = storeOp(store, 0, assignedAdapter);
        SemanticOp sideCrossing = boundaryOp(BoundaryKind.STDLIB_RETURN, SIG2,
            assignedAdapter);
        LoweredModuleUnit unitCrossing = unit(Map.of(),
            Map.of(assignedIdentity,
                new FunctionExecutionBinding.AdapterBinding(assignedAdapt.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(store, 0),
                    SIG1, SIG2)),
            List.of(storeAlloc, storeInitValue, storeInit, assignedAdapt, assignment,
                commit, sideCrossing));
        StructuredBodyTable tableCrossing = tableOf(Map.of(INIT_BLOCK,
            List.of(storeAlloc, storeInitValue, storeInit, assignedAdapt, assignment,
                commit, sideCrossing)));
        assertE6005(BindingsProductionValidator.validate(unitCrossing, tableCrossing),
            "NO_ADAPTER_AT_BOUNDARY",
            "an adapter result whose agreeing assignment cell is joined by a "
                + "STDLIB_RETURN crossing",
            "STDLIB_RETURN", "not the adapted position's own cell");

        // The class-default publisher: no construction is required in the
        // declaring unit, but a stdlib return crossing of the published
        // result is still not a position cell.
        ValueId defaultAdapter = nextValue();
        FunctionAllocationIdentity defaultIdentity = new FunctionAllocationIdentity(
            defaultAdapter.id());
        BlockId defaultBlock = nextBlock();
        SemanticOp defaultAdapt = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(x, 0), SIG1, SIG2, null, List.of(), List.of(),
            defaultAdapter);
        SemanticOp classDefault = op(SemanticOpKind.CLASS_DEFAULT,
            new KindPayload.ClassDefaultPayload(new ClassId("mod.binding", "C"),
                "handler", defaultBlock),
            defaultAdapter, SIG2, FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp defaultCrossing = boundaryOp(BoundaryKind.STDLIB_RETURN, SIG2,
            defaultAdapter);
        LoweredModuleUnit unitDefault = unit(Map.of(),
            Map.of(defaultIdentity,
                new FunctionExecutionBinding.AdapterBinding(defaultAdapt.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(x, 0), SIG1,
                    SIG2)),
            List.of(alloc, initValue, init, defaultAdapt, classDefault, defaultCrossing));
        StructuredBodyTable tableDefault = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(alloc, initValue, init, defaultAdapt,
                classDefault, defaultCrossing)),
            Map.entry(defaultBlock, List.of())));
        assertE6005(BindingsProductionValidator.validate(unitDefault, tableDefault),
            "NO_ADAPTER_AT_BOUNDARY",
            "a class-default adapter published beside a STDLIB_RETURN crossing",
            "STDLIB_RETURN", "CLASS_DEFAULT_FIELD");

        // The variable position's own chain is closed by adjacency: a
        // VARIABLE_ASSIGNMENT cell that consumes the adapter result but is
        // not the op immediately following the adapter in its own block is
        // not the position's own chain — the commit that follows it does
        // not make it one.
        BindingId displaced = nextBindingId();
        ValueId displacedAdapter = nextValue();
        FunctionAllocationIdentity displacedIdentity = new FunctionAllocationIdentity(
            displacedAdapter.id());
        SemanticOp displacedAlloc = allocOp(displaced, INIT_BLOCK, 0);
        SemanticOp displacedInitValue = constInt();
        SemanticOp displacedInit = initOp(displaced, 0,
            (ValueId) displacedInitValue.result());
        SemanticOp displacedAdapt = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(displaced, 0), SIG1, SIG2, null, List.of(),
            List.of(), displacedAdapter);
        SemanticOp displacedFiller = constInt();
        SemanticOp displacedAssignment = boundaryOp(BoundaryKind.VARIABLE_ASSIGNMENT, SIG2,
            displacedAdapter);
        SemanticOp displacedCommit = storeOp(displaced, 0, displacedAdapter);
        LoweredModuleUnit unitDisplaced = unit(Map.of(),
            Map.of(displacedIdentity,
                new FunctionExecutionBinding.AdapterBinding(displacedAdapt.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(displaced, 0),
                    SIG1, SIG2)),
            List.of(displacedAlloc, displacedInitValue, displacedInit, displacedAdapt,
                displacedFiller, displacedAssignment, displacedCommit));
        StructuredBodyTable tableDisplaced = tableOf(Map.of(INIT_BLOCK,
            List.of(displacedAlloc, displacedInitValue, displacedInit, displacedAdapt,
                displacedFiller, displacedAssignment, displacedCommit)));
        assertE6005(BindingsProductionValidator.validate(unitDisplaced, tableDisplaced),
            "NO_ADAPTER_AT_BOUNDARY",
            "an adapter result whose variable cell is not its immediate successor",
            "immediately following", "VARIABLE_ASSIGNMENT");

        // The adjacent cell alone is not the chain either: the position's
        // single commit must follow the cell immediately, storing the
        // adapter result.
        BindingId uncommitted = nextBindingId();
        ValueId uncommittedAdapter = nextValue();
        FunctionAllocationIdentity uncommittedIdentity =
            new FunctionAllocationIdentity(uncommittedAdapter.id());
        SemanticOp uncommittedAlloc = allocOp(uncommitted, INIT_BLOCK, 0);
        SemanticOp uncommittedInitValue = constInt();
        SemanticOp uncommittedInit = initOp(uncommitted, 0,
            (ValueId) uncommittedInitValue.result());
        SemanticOp uncommittedAdapt = adaptOp(CaptureMode.SHARED_CELL,
            new AdaptSourceRef.SharedCell(uncommitted, 0), SIG1, SIG2, null, List.of(),
            List.of(), uncommittedAdapter);
        SemanticOp uncommittedAssignment = boundaryOp(BoundaryKind.VARIABLE_ASSIGNMENT,
            SIG2, uncommittedAdapter);
        SemanticOp uncommittedFiller = constInt();
        SemanticOp uncommittedStore = storeOp(uncommitted, 0, uncommittedAdapter);
        LoweredModuleUnit unitUncommitted = unit(Map.of(),
            Map.of(uncommittedIdentity,
                new FunctionExecutionBinding.AdapterBinding(uncommittedAdapt.opId(),
                    CaptureMode.SHARED_CELL, new AdaptSourceRef.SharedCell(uncommitted, 0),
                    SIG1, SIG2)),
            List.of(uncommittedAlloc, uncommittedInitValue, uncommittedInit,
                uncommittedAdapt, uncommittedAssignment, uncommittedFiller,
                uncommittedStore));
        StructuredBodyTable tableUncommitted = tableOf(Map.of(INIT_BLOCK,
            List.of(uncommittedAlloc, uncommittedInitValue, uncommittedInit,
                uncommittedAdapt, uncommittedAssignment, uncommittedFiller,
                uncommittedStore)));
        assertE6005(BindingsProductionValidator.validate(unitUncommitted,
                tableUncommitted),
            "NO_ADAPTER_AT_BOUNDARY",
            "an adapter result whose variable cell is not followed by its commit",
            "commit", "adapter result");
    }

    private static void testBindingInitOnceNegatives() {
        // Two INITs for one incarnation.
        BindingId x = nextBindingId();
        ValueId v1 = nextValue();
        ValueId v2 = nextValue();
        SemanticOp alloc = allocOp(x, INIT_BLOCK, 0);
        SemanticOp initA = initOp(x, 0, v1);
        SemanticOp initB = initOp(x, 0, v2);
        LoweredModuleUnit unit = unit(Map.of(), Map.of(),
            List.of(alloc, initA, initB));
        StructuredBodyTable table = tableOf(Map.of(INIT_BLOCK, List.of(alloc, initA, initB)));
        assertE6005(BindingsProductionValidator.validate(unit, table), "BINDING_INIT_ONCE",
            "two INITs for one incarnation", "exactly one per source-declaration "
                + "incarnation");

        // An INIT targeting a FOR_EACH iteration binding's cell.
        BindingId loopBinding = nextBindingId();
        BlockId loopBody = nextBlock();
        SemanticOp forEach = forEachOp(loopBinding, 0, loopBody);
        SemanticOp rogueInit = initOp(loopBinding, 0, nextValue());
        LoweredModuleUnit unitFor = unit(Map.of(), Map.of(),
            List.of(forEach, rogueInit));
        StructuredBodyTable tableFor = tableOf(Map.of(INIT_BLOCK,
            List.of(forEach, rogueInit), loopBody, List.of()));
        assertE6005(BindingsProductionValidator.validate(unitFor, tableFor),
            "BINDING_INIT_ONCE", "an INIT targeting a FOR_EACH iteration binding's cell",
            "never carries a BINDING_INIT");

        // An INIT targeting a parameter cell: the unit payload alone
        // cannot distinguish a parameter ALLOC from a local ALLOC (both
        // mutable=true, generation 0, body-root block), so the walk's
        // checker facts name the parameter binding (the synthetic
        // parameter-transfer entry write is the invoking machinery's
        // pinned initializing write).
        BindingId parameter = nextBindingId();
        FunctionId paramFn = nextFunctionId();
        BlockId paramBody = nextBlock();
        ValueId paramIdentity = nextValue();
        SemanticOp paramAlloc = allocOp(parameter, paramBody, 0);
        SemanticOp rogueParamInit = initOp(parameter, 0, nextValue());
        SemanticOp paramClosure = closureNewOp(paramFn, SIG0, List.of(), paramBody,
            paramIdentity);
        LoweredModuleUnit unitParam = unit(
            Map.of(paramFn, function(paramFn, SIG0, List.of(), paramBody)),
            Map.of(new FunctionAllocationIdentity(paramIdentity.id()),
                new FunctionExecutionBinding.LoweredBody(paramFn, paramBody)),
            List.of(paramClosure, paramAlloc, rogueParamInit));
        StructuredBodyTable tableParam = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(paramClosure)),
            Map.entry(paramBody, List.of(paramAlloc, rogueParamInit))));
        assertE6005(BindingsProductionValidator.validate(unitParam, tableParam,
                new ClassBindingFacts(Set.of(parameter), Set.of())),
            "BINDING_INIT_ONCE", "an INIT targeting a parameter cell", "parameter cell");

        // An INIT targeting an import-alias cell: the unit payload
        // alone cannot distinguish an alias ALLOC from an intrinsic
        // ALLOC (both module-region mutable=false ALLOCs), so the
        // walk's checker facts name the alias binding (the
        // MODULE_IMPORT completion write is the pinned initializing
        // write).
        BindingId alias = nextBindingId();
        SemanticOp aliasAlloc = allocOp(alias, INIT_BLOCK, 0);
        SemanticOp rogueAliasInit = initOp(alias, 0, nextValue());
        SemanticOp moduleImport = moduleImportOp();
        LoweredModuleUnit unitAlias = unit(Map.of(), Map.of(),
            List.of(aliasAlloc, rogueAliasInit, moduleImport));
        StructuredBodyTable tableAlias = tableOf(Map.of(INIT_BLOCK,
            List.of(aliasAlloc, rogueAliasInit, moduleImport)));
        assertE6005(BindingsProductionValidator.validate(unitAlias, tableAlias,
                new ClassBindingFacts(Set.of(), Set.of(alias))),
            "BINDING_INIT_ONCE", "an INIT targeting an import-alias cell",
            "import-alias cell");
    }

    // =========================================================================
    // 5. IR-level positives
    // =========================================================================

    private static void testIrLevelPositives() {
        System.out.println("-- IR-level positives (unit-level; each must validate) --");

        BindingId makeFactory = nextBindingId();
        FunctionId fn = nextFunctionId();
        BlockId fnBody = nextBlock();
        ValueId identity = nextValue();
        BlockId defaultBlock = nextBlock();
        SemanticOp alloc = allocOp(makeFactory, INIT_BLOCK, 0);
        SemanticOp closure = closureNewOp(fn, SIG1, List.of(), fnBody, identity);
        SemanticOp init = initOp(makeFactory, 0, identity);
        SemanticOp classDefault = classDefaultOp(defaultBlock);
        SemanticOp defaultLoad = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(makeFactory, 0),
            nextValue(), SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unit = unit(
            Map.of(fn, function(fn, SIG1, List.of(), fnBody)),
            Map.of(new FunctionAllocationIdentity(identity.id()),
                new FunctionExecutionBinding.LoweredBody(fn, fnBody)),
            List.of(alloc, closure, init, classDefault, defaultLoad));
        StructuredBodyTable table = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(alloc, closure, init, classDefault)),
            Map.entry(fnBody, List.of()),
            Map.entry(defaultBlock, List.of(defaultLoad))));
        assertPass(BindingsProductionValidator.validate(unit, table),
            "the default-block module-level reference (R4's module-level arm)");

        // The import-alias synthetic write: the MODULE_IMPORT
        // completion names the alias cell in its payload and dominates
        // alias loads in the module block.
        BindingId alias = nextBindingId();
        SemanticOp aliasAlloc = allocOp(alias, INIT_BLOCK, 0);
        SemanticOp moduleImport = moduleImportOp(alias);
        SemanticOp aliasLoad = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(alias, 0),
            nextValue(), RuntimeDescriptor.Table.INSTANCE, List.of(), List.of(),
            FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unitAlias = unit(Map.of(), Map.of(),
            List.of(aliasAlloc, moduleImport, aliasLoad));
        StructuredBodyTable tableAlias = tableOf(Map.of(INIT_BLOCK,
            List.of(aliasAlloc, moduleImport, aliasLoad)));
        assertPass(BindingsProductionValidator.validate(unitAlias, tableAlias),
            "the import-alias MODULE_IMPORT completion write");

        // A parameter's synthetic entry write dominates body loads.
        BindingId parameter = nextBindingId();
        FunctionId paramFn = nextFunctionId();
        BlockId paramBody = nextBlock();
        ValueId paramIdentity = nextValue();
        SemanticOp paramAlloc = allocOp(parameter, paramBody, 0);
        SemanticOp paramLoad = opWith(nextOpId(), SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(parameter, 0),
            nextValue(), INT, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        SemanticOp paramClosure = closureNewOp(paramFn, SIG0, List.of(), paramBody,
            paramIdentity);
        LoweredModuleUnit unitParam = unit(
            Map.of(paramFn, function(paramFn, SIG0, List.of(), paramBody)),
            Map.of(new FunctionAllocationIdentity(paramIdentity.id()),
                new FunctionExecutionBinding.LoweredBody(paramFn, paramBody)),
            List.of(paramClosure, paramAlloc, paramLoad));
        StructuredBodyTable tableParam = tableOf(Map.ofEntries(
            Map.entry(INIT_BLOCK, List.of(paramClosure)),
            Map.entry(paramBody, List.of(paramAlloc, paramLoad))));
        assertPass(BindingsProductionValidator.validate(unitParam, tableParam),
            "the parameter-transfer entry write dominates body loads");

        // A HostFunctionValue whose identity later crosses an ordinary
        // VARIABLE_DECLARATION boundary (identity-preserving flow):
        // only the producing HOST_TO_DEAL crossing counts toward the
        // exactly-one requirement.
        ValueId hostValue = nextValue();
        SemanticOp hostCrossing = boundaryOp(BoundaryKind.HOST_TO_DEAL, SIG1, hostValue);
        SemanticOp laterDeclaration = boundaryOp(BoundaryKind.VARIABLE_DECLARATION, SIG1,
            hostValue);
        LoweredModuleUnit unitHostFlow = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(hostValue.id()),
                new FunctionExecutionBinding.HostFunctionValue(MOD,
                    hostCrossing.opId(), SIG1)),
            List.of(hostCrossing, laterDeclaration));
        StructuredBodyTable tableHostFlow = tableOf(Map.of(INIT_BLOCK,
            List.of(hostCrossing, laterDeclaration)));
        assertPass(BindingsProductionValidator.validate(unitHostFlow, tableHostFlow),
            "a HostFunctionValue followed by an identity-preserving VARIABLE_DECLARATION "
                + "crossing");

        // A HostFunction produced by exactly one import member read
        // (the host/external seam's producing site).
        ValueId memberReadResult = nextValue();
        SemanticOp memberRead = opWith(nextOpId(), SemanticOpKind.MEMBER_READ,
            new KindPayload.MemberReadPayload(nextValue(), "g"),
            memberReadResult, SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE,
            null);
        LoweredModuleUnit unitHostRead = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(memberReadResult.id()),
                new FunctionExecutionBinding.HostFunction(new ModuleId("host"), "g", SIG1)),
            List.of(memberRead));
        StructuredBodyTable tableHostRead = tableOf(Map.of(INIT_BLOCK, List.of(memberRead)));
        assertPass(BindingsProductionValidator.validate(unitHostRead, tableHostRead),
            "a HostFunction produced by exactly one import member read");

        // An ExternalFunction produced by exactly one export read.
        ValueId exportReadValue = nextValue();
        SemanticOp exportRead = opWith(nextOpId(), SemanticOpKind.EXPORT_READ,
            new KindPayload.ExportReadPayload(new ModuleId("lib.math"), "g", SIG1,
                exportReadValue),
            null, null, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
        LoweredModuleUnit unitExternalRead = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(exportReadValue.id()),
                new FunctionExecutionBinding.ExternalFunction(new ModuleId("lib.math"),
                    "g", SIG1, deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY)),
            List.of(exportRead));
        StructuredBodyTable tableExternalRead = tableOf(Map.of(INIT_BLOCK,
            List.of(exportRead)));
        assertPass(BindingsProductionValidator.validate(unitExternalRead, tableExternalRead),
            "an ExternalFunction produced by exactly one export read");
    }

    // =========================================================================
    // 6. Cell-kind invariant (the complete B2 iff over the corpus)
    // =========================================================================

    private static void testCellKindInvariant(SemanticLowerer.ValidationCoreResult prepared) {
        System.out.println("-- cell-kind invariant: the closed B2 iff across the corpus --");

        SemanticLowerer.ValidationCoreResult result = validatedResult(prepared,
            "the cell-kind invariant corpus slice");
        if (result == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<BindingsProductionValidator.DerivedCellKind> derived =
            BindingsProductionValidator.deriveCellKinds(unit, result.lowering().table());
        Map<String, BindingCellKind> byKey = new LinkedHashMap<>();
        for (BindingsProductionValidator.DerivedCellKind row : derived) {
            byKey.put(row.key().binding().id() + "|" + row.key().generation() + "|"
                + row.key().scope().id(), row.kind());
        }
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc) {
                String key = alloc.binding().id() + "|" + alloc.generation() + "|"
                    + alloc.scope().id();
                BindingCellKind expected = byKey.get(key);
                check(expected != null, "the derivation covers every emitted ALLOC "
                    + key);
                if (expected != null) {
                    check(expected == alloc.cellKind(), "the emitted cell kind of "
                        + key + " equals the closed derivation ("
                        + alloc.cellKind() + " == " + expected + ")");
                }
            }
            if (op.payload() instanceof KindPayload.ForEachPayload forEach) {
                boolean sharedDerived = derived.stream().anyMatch(row ->
                    row.key().binding().equals(forEach.binding())
                        && row.key().generation() == forEach.generation()
                        && row.kind() == BindingCellKind.SHARED_CELL);
                check(sharedDerived, "the FOR_EACH iteration binding derives "
                    + "SHARED_CELL (the pinned special case)");
            }
            if (op.payload() instanceof KindPayload.RecursiveGroupInitPayload group) {
                for (BindingId member : group.bindings()) {
                    boolean memberShared = derived.stream().anyMatch(row ->
                        row.key().binding().equals(member) && row.key().generation() == 0
                            && row.kind() == BindingCellKind.SHARED_CELL);
                    check(memberShared, "the group member " + member + " derives "
                        + "SHARED_CELL by construction (B2)");
                }
            }
        }
    }

    // =========================================================================
    // IMPORT_OCCURRENCE_CORRELATION
    // =========================================================================

    /** One kind-pinned MODULE_IMPORT completion naming the given alias cells. */
    private static SemanticOp importOp(ModuleId module,
            deal.semantic.ir.ModuleImportKind kind, BindingId... aliasCells) {
        return op(SemanticOpKind.MODULE_IMPORT,
            new KindPayload.ModuleImportPayload(module.path(), module, kind,
                List.of(aliasCells)),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    /** One HOST-kind MODULE_IMPORT completion naming the given alias cells. */
    private static SemanticOp hostImportOp(ModuleId host, BindingId... aliasCells) {
        return importOp(host, deal.semantic.ir.ModuleImportKind.HOST, aliasCells);
    }

    /** One function-typed EXPORT_READ of a host export. */
    private static SemanticOp hostReadOp(ModuleId host, String name, ValueId value) {
        return opWith(nextOpId(), SemanticOpKind.EXPORT_READ,
            new KindPayload.ExportReadPayload(host, name, SIG1, value),
            value, SIG1, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, null);
    }

    /**
     * The host import read's occurrence correlation: a recorded cell that
     * matches the read's own {@code MODULE_IMPORT} completion passes; a
     * missing cell, an uncorrelated cell and a cell whose completion
     * resolves another module each fail closed with the offending read's
     * own origin — never a first-alias fallback.
     */
    private static void testImportOccurrenceCorrelation() {
        System.out.println("-- IMPORT_OCCURRENCE_CORRELATION: the host import read's "
            + "owning occurrence cell --");
        ModuleId host = new ModuleId("host/echo");

        // The positive control: the binding names the read's own
        // completion cell.
        BindingId cell = nextBindingId();
        ValueId value = nextValue();
        SemanticOp alloc = allocOp(cell, INIT_BLOCK, 0);
        SemanticOp completion = hostImportOp(host, cell);
        SemanticOp read = hostReadOp(host, "g", value);
        LoweredModuleUnit correlated = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(value.id()),
                new FunctionExecutionBinding.HostFunction(host, "g", SIG1, cell)),
            List.of(alloc, completion, read));
        StructuredBodyTable correlatedTable = tableOf(Map.of(INIT_BLOCK,
            List.of(alloc, completion, read)));
        assertPass(BindingsProductionValidator.validate(correlated, correlatedTable),
            "the correlated host import read");

        // The occurrence cell is a complete transported fact: the typed ->
        // raw -> canonical text -> typed roundtrip preserves it byte-exactly
        // (the canonical binding mapping, both directions).
        {
            String text = deal.semantic.ir.SemanticIrValidator.toUnitText(correlated);
            deal.semantic.ir.RawUnit reparsed =
                deal.semantic.ir.ContractSnapshotCanonicalizer.parseUnit(
                    (deal.semantic.ir.CanonicalJson.Obj)
                        deal.semantic.ir.CanonicalJson.parse(text));
            check(deal.semantic.ir.RawUnit.fromTyped(correlated).equals(reparsed),
                "the occurrence-correlated unit round-trips through the raw "
                    + "transport");
            check(correlated.equals(
                    deal.semantic.ir.RawUnitMaterializer.materialize(reparsed)),
                "the materialized text carries the occurrence cell");
            check(text.equals(deal.semantic.ir.CanonicalJson.serializeText(
                    deal.semantic.ir.ContractSnapshotCanonicalizer.toJson(reparsed))),
                "the canonical text is byte-stable across a repeat encoding");
        }

        // A missing cell: the binding records no occurrence.
        ValueId missingValue = nextValue();
        BindingId missingCell = nextBindingId();
        SemanticOp missingAlloc = allocOp(missingCell, INIT_BLOCK, 0);
        SemanticOp missingCompletion = hostImportOp(host, missingCell);
        SemanticOp missingRead = hostReadOp(host, "g", missingValue);
        LoweredModuleUnit uncorrelated = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(missingValue.id()),
                new FunctionExecutionBinding.HostFunction(host, "g", SIG1)),
            List.of(missingAlloc, missingCompletion, missingRead));
        StructuredBodyTable uncorrelatedTable = tableOf(Map.of(INIT_BLOCK,
            List.of(missingAlloc, missingCompletion, missingRead)));
        Optional<CompilerDiagnostic> missingFailure = BindingsProductionValidator
            .validate(uncorrelated, uncorrelatedTable);
        assertE6005(missingFailure, "IMPORT_OCCURRENCE_CORRELATION",
            "a host import read without its occurrence cell",
            "records no owning import-occurrence cell");
        check(missingFailure.isPresent() && missingFailure.get().range()
                .equals(missingRead.origin().span().range()),
            "the missing-correlation failure carries the offending read's own range");

        // An uncorrelated cell: the binding names a BindingId no
        // completion of the unit initializes.
        ValueId strayValue = nextValue();
        BindingId strayCell = nextBindingId();
        BindingId strayAlias = nextBindingId();
        SemanticOp strayAlloc = allocOp(strayAlias, INIT_BLOCK, 0);
        SemanticOp strayCompletion = hostImportOp(host, strayAlias);
        SemanticOp strayRead = hostReadOp(host, "g", strayValue);
        LoweredModuleUnit stray = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(strayValue.id()),
                new FunctionExecutionBinding.HostFunction(host, "g", SIG1, strayCell)),
            List.of(strayAlloc, strayCompletion, strayRead));
        StructuredBodyTable strayTable = tableOf(Map.of(INIT_BLOCK,
            List.of(strayAlloc, strayCompletion, strayRead)));
        assertE6005(BindingsProductionValidator.validate(stray, strayTable),
            "IMPORT_OCCURRENCE_CORRELATION",
            "a host import read naming an uninitialized cell",
            "no MODULE_IMPORT completion of the unit initializes");

        // An uncorrelated module: the read and its binding name one host
        // module while the named cell's completion resolves another.
        ValueId mismatchValue = nextValue();
        BindingId mismatchCell = nextBindingId();
        BindingId mismatchAlias = nextBindingId();
        SemanticOp mismatchAlloc = allocOp(mismatchAlias, INIT_BLOCK, 0);
        SemanticOp mismatchOwnCellAlloc = allocOp(mismatchCell, INIT_BLOCK, 0);
        SemanticOp mismatchOwnCompletion = hostImportOp(host, mismatchAlias);
        SemanticOp mismatchCompletion = hostImportOp(new ModuleId("host/other"),
            mismatchCell);
        SemanticOp mismatchRead = hostReadOp(host, "g", mismatchValue);
        LoweredModuleUnit mismatch = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(mismatchValue.id()),
                new FunctionExecutionBinding.HostFunction(host, "g", SIG1,
                    mismatchCell)),
            List.of(mismatchAlloc, mismatchOwnCellAlloc, mismatchOwnCompletion,
                mismatchCompletion, mismatchRead));
        StructuredBodyTable mismatchTable = tableOf(Map.of(INIT_BLOCK,
            List.of(mismatchAlloc, mismatchOwnCellAlloc, mismatchOwnCompletion,
                mismatchCompletion, mismatchRead)));
        assertE6005(BindingsProductionValidator.validate(mismatch, mismatchTable),
            "IMPORT_OCCURRENCE_CORRELATION",
            "a host import read whose occurrence resolves another module",
            "does not resolve that host module");

        // A read of a module with no import occurrence at all: the read
        // names no MODULE_IMPORT of its resolved module.
        ValueId unknownValue = nextValue();
        BindingId unknownCell = nextBindingId();
        BindingId unknownAlias = nextBindingId();
        SemanticOp unknownAlloc = allocOp(unknownAlias, INIT_BLOCK, 0);
        SemanticOp unknownCompletion = hostImportOp(host, unknownAlias);
        SemanticOp unknownRead = hostReadOp(new ModuleId("host/unimported"), "g",
            unknownValue);
        LoweredModuleUnit unknown = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(unknownValue.id()),
                new FunctionExecutionBinding.HostFunction(new ModuleId("host/unimported"),
                    "g", SIG1, unknownCell)),
            List.of(unknownAlloc, unknownCompletion, unknownRead));
        StructuredBodyTable unknownTable = tableOf(Map.of(INIT_BLOCK,
            List.of(unknownAlloc, unknownCompletion, unknownRead)));
        assertE6005(BindingsProductionValidator.validate(unknown, unknownTable),
            "IMPORT_OCCURRENCE_CORRELATION",
            "a host import read of a module with no import occurrence",
            "names no MODULE_IMPORT occurrence");

        // A STDLIB read records no occurrence cell (the once-only
        // module-keyed surface): a recorded cell there is a producer
        // defect.
        ModuleId stdlib = new ModuleId("std.json");
        ValueId stdlibValue = nextValue();
        BindingId stdlibCell = nextBindingId();
        SemanticOp stdlibAlloc = allocOp(stdlibCell, INIT_BLOCK, 0);
        SemanticOp stdlibCompletion = importOp(stdlib,
            deal.semantic.ir.ModuleImportKind.STDLIB, stdlibCell);
        SemanticOp stdlibRead = hostReadOp(stdlib, "parse", stdlibValue);
        LoweredModuleUnit stdlibUnit = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(stdlibValue.id()),
                new FunctionExecutionBinding.HostFunction(stdlib, "parse", SIG1,
                    stdlibCell)),
            List.of(stdlibAlloc, stdlibCompletion, stdlibRead));
        StructuredBodyTable stdlibTable = tableOf(Map.of(INIT_BLOCK,
            List.of(stdlibAlloc, stdlibCompletion, stdlibRead)));
        assertE6005(BindingsProductionValidator.validate(stdlibUnit, stdlibTable),
            "IMPORT_OCCURRENCE_CORRELATION",
            "a STDLIB read carrying an occurrence cell",
            "records no occurrence cell");

        // The STDLIB control: the same read without a cell passes.
        ValueId stdlibOkValue = nextValue();
        BindingId stdlibOkCell = nextBindingId();
        SemanticOp stdlibOkAlloc = allocOp(stdlibOkCell, INIT_BLOCK, 0);
        SemanticOp stdlibOkCompletion = importOp(stdlib,
            deal.semantic.ir.ModuleImportKind.STDLIB, stdlibOkCell);
        SemanticOp stdlibOkRead = hostReadOp(stdlib, "parse", stdlibOkValue);
        LoweredModuleUnit stdlibOk = unit(Map.of(),
            Map.of(new FunctionAllocationIdentity(stdlibOkValue.id()),
                new FunctionExecutionBinding.HostFunction(stdlib, "parse", SIG1)),
            List.of(stdlibOkAlloc, stdlibOkCompletion, stdlibOkRead));
        StructuredBodyTable stdlibOkTable = tableOf(Map.of(INIT_BLOCK,
            List.of(stdlibOkAlloc, stdlibOkCompletion, stdlibOkRead)));
        assertPass(BindingsProductionValidator.validate(stdlibOk, stdlibOkTable),
            "the STDLIB read without an occurrence cell");
    }

    // =========================================================================
    // Runner
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Bindings Production Validation Test (ISSUE-0451 B9 "
            + "child) ===\n");

        testSurfaceAndRules();
        testSourceLevelPositives();
        testSourceLevelNegatives();
        testIrLevelNegatives();
        testIrLevelPositives();
        testFailureOriginProjection();
        testGroupShapeOffenderOrigins();
        testImportOccurrenceCorrelation();

        System.out.println("\nBindings production validation: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
