package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ContainerClaimingSeam;
import deal.semantic.ContainerClaimingSeam.OutcomeKind;
import deal.semantic.ContainerClaimingSeam.RecordedOutcome;
import deal.semantic.ContainerClaimingSeam.SeamResult;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.ValueId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the ISSUE-0516 class claiming arms
 * (class-construction-jsonable-operations K-D7/K-D11; the epic's
 * decomposition tail): the op-side home rows of the class epic recorded
 * under the shared claiming mechanism ({@link ContainerClaimingSeam}) —
 * the eight class ops home to {@code CLASSES}, {@code HAS_FIELD} homes
 * to {@code CONTAINERS_AND_STRINGS} (never {@code CLASSES}, K-D7), and
 * the class boundary children home to {@code DESCRIPTORS}/
 * {@code BOUNDARIES} — the pinned {@code E9_GATE_ACTIVATION} hand-off
 * (the class epic's closing gate activates {@code CLASSES}), the staged/
 * claimed/deferred outcome discipline, and the plan-time manifest arm
 * (a module whose checked source contains a class construct claims
 * {@code CLASSES} before lowering; a class-free module never claims
 * it).
 *
 * <p>Pinned cases (the task verification):
 * <ol>
 *   <li>home rows: every class op homes to exactly {@code [CLASSES]};
 *       {@code HAS_FIELD} homes to exactly
 *       {@code [CONTAINERS_AND_STRINGS]}; every class boundary kind
 *       homes to exactly {@code [DESCRIPTORS, BOUNDARIES]} in the
 *       pinned order; no non-class op homes to {@code CLASSES}.</li>
 *   <li>{@code E9_GATE_ACTIVATION} is exactly the E6-gate activation
 *       plus {@code CLASSES} (and never {@code STDLIB_SEMANTICS} or
 *       {@code CALLS}/{@code MODULES}).</li>
 *   <li>The staged discipline: under {@code E6_GATE_ACTIVATION} the
 *       {@code CLASSES} row is inactive — every class-op position
 *       records {@code STAGED_HAND_OFF} and the derivation excludes the
 *       row; under an augmented activation the derivation claims
 *       {@code CLASSES} for the full eight-family unit and records
 *       {@code CLAIMED}; a claim of the inactive row is the
 *       producer-defect guard.</li>
 *   <li>The production seam: the class-core unit producer derives the
 *       claim set under {@code E9_GATE_ACTIVATION} — a unit producing
 *       every {@code CLASSES} family carries the claim; a partial unit
 *       records the per-unit deferral and never claims.</li>
 *   <li>The plan-time manifest arm: a class-declaration module, a
 *       class-literal module, a class member-access module, a class
 *       write/delete module, and a {@code has()} module each claim
 *       {@code CLASSES} at plan time; a class-free module and a
 *       type-only class reference never claim it.</li>
 * </ol>
 */
public class ClassClaimingSeamTest {

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

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private record PipelineSlice(CheckedSlice slice, String interfaceHash,
                                 ExternalModuleInterface ownInterface,
                                 Map<ConstructKind, List<SemanticOpKind>> coverage,
                                 List<SemanticRequirementManifest> manifests) {
    }

    private static CheckedSlice checkSlice(String source) {
        Lexer lexer = new Lexer(source, SOURCE_ID);
        deal.lexer.LexResult lex = lexer.tokenize();
        Parser parser = new Parser(lex.tokens(), SOURCE_ID, lex.directiveEvents());
        deal.parser.ParseResult parse = parser.parse();
        check(parse.diagnostics().isEmpty(), "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver resolver = new NameResolver(MODULE.path(), (deal.checker.ModuleResolver) null);
        SymbolTable symbols = resolver.resolve(parse.program());
        check(resolver.diagnostics().isEmpty(),
            "the slice resolves cleanly: " + resolver.diagnostics());
        if (!resolver.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult checks = TypeChecker.check(MODULE.path(), symbols, resolver, parse.program());
        check(checks.diagnostics().isEmpty(), "the slice checks cleanly: " + checks.diagnostics());
        if (checks.hasErrors()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symbols, checks);
    }

    private static PipelineSlice pipeline(CheckedSlice slice) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false, slice.program(),
            Map.of(), slice.symbols(), slice.checks(), List.of());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation,
            MODULE, List.of(fact));
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            "the checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty()
                && manifests.manifests() != null && manifests.manifests().size() == 1,
            "the foundation detector produces exactly one requirement manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests() == null || manifests.manifests().size() != 1) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            new LinkedHashMap<>(manifests.manifests().get(0).constructCoverage());
        coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
        ExternalModuleInterface own = built.index().modules().get(MODULE);
        check(own != null, "the index carries the module's own interface entry");
        return new PipelineSlice(slice, built.index().interfaceIndexDigest(), own, coverage,
            manifests.manifests());
    }

    private static CheckedModuleInput moduleOf(CheckedSlice slice) {
        return new CheckedModuleInput(MODULE, SOURCE_ID, Path.of(SOURCE_ID), slice.program(),
            slice.checks(), List.of(), List.of(), CheckedModuleKind.IMPLEMENTATION);
    }

    private static SemanticLowerer.ClassDeclarationCoreResult lowerModule(String source) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        PipelineSlice pipeline = pipeline(slice);
        if (pipeline == null) {
            return null;
        }
        return SemanticLowerer.lowerModuleClassCore(moduleOf(slice),
            SemanticProfile.DEAL_V1_2_INT32, pipeline.coverage(), pipeline.interfaceHash(),
            REGISTRY_HASH, pipeline.ownInterface(),
            SemanticIdAllocator.over(List.of(MODULE)));
    }

    private static List<SemanticOp> ofKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        List<SemanticOp> matches = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                matches.add(op);
            }
        }
        return matches;
    }

    private static RecordedOutcome outcomeOf(List<RecordedOutcome> outcomes, OpId opId,
                                             SemanticCapability home) {
        for (RecordedOutcome outcome : outcomes) {
            if (outcome.opId().equals(opId) && outcome.home() == home) {
                return outcome;
            }
        }
        return null;
    }

    // =========================================================================
    // 1. The pinned home rows
    // =========================================================================

    private static void testHomeRows() {
        System.out.println("-- the pinned class home rows (K-D7/K-D11) --");

        // The eight class ops home to exactly [CLASSES].
        List<SemanticOp> classOps = List.of(
            handOp(SemanticOpKind.CLASS_NEW,
                new KindPayload.ClassNewPayload(new ClassId("m", "C"),
                    new ClassLayout(new ClassId("m", "C"), List.of()), List.of(),
                    DefaultOwner.LOCAL, List.of(), null, List.of()),
                null, null, FailurePolicyId.CLASS_CONSTRUCTION),
            handOp(SemanticOpKind.CLASS_FACTORY,
                new KindPayload.ClassFactoryPayload(new ClassId("m", "C"), List.of(),
                    new OpId(MODULE, 99_991)),
                null, null, FailurePolicyId.CLASS_CONSTRUCTION),
            handOp(SemanticOpKind.CLASS_DEFAULT,
                new KindPayload.ClassDefaultPayload(new ClassId("m", "C"), "f",
                    new deal.semantic.ir.BlockId(1)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE),
            handOp(SemanticOpKind.FIELD_READ,
                new KindPayload.FieldReadPayload(new ValueId(1), new ClassId("m", "C"),
                    "f"),
                null, null, FailurePolicyId.NO_DEAL_FAILURE),
            handOp(SemanticOpKind.FIELD_WRITE,
                new KindPayload.FieldWritePayload(new ValueId(1), new ClassId("m", "C"),
                    "f", new ValueId(2)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE),
            handOp(SemanticOpKind.FIELD_DELETE,
                new KindPayload.FieldDeletePayload(new ValueId(1), new ClassId("m", "C"),
                    "f"),
                null, null, FailurePolicyId.NO_DEAL_FAILURE),
            handOp(SemanticOpKind.JSON_FROM_CLASS,
                new KindPayload.JsonFromClassPayload(
                    new ClassLayout(new ClassId("m", "C"), List.of()), new ValueId(1)),
                null, null, FailurePolicyId.JSON_FROM_NULL),
            handOp(SemanticOpKind.JSON_TO_CLASS,
                new KindPayload.JsonToClassPayload(new ValueId(1),
                    new ClassLayout(new ClassId("m", "C"), List.of())),
                null, null, FailurePolicyId.JSON_TO_ERROR));
        for (SemanticOp op : classOps) {
            check(ContainerClaimingSeam.homeRows(op)
                    .equals(List.of(SemanticCapability.CLASSES)),
                op.kind() + " homes to exactly [CLASSES]; got "
                    + ContainerClaimingSeam.homeRows(op));
        }

        // HAS_FIELD homes to CONTAINERS_AND_STRINGS, never CLASSES (K-D7).
        SemanticOp has = handOp(SemanticOpKind.HAS_FIELD,
            new KindPayload.HasFieldPayload(new ValueId(1), "f"),
            null, null, FailurePolicyId.NO_DEAL_FAILURE);
        check(ContainerClaimingSeam.homeRows(has)
                .equals(List.of(SemanticCapability.CONTAINERS_AND_STRINGS)),
            "HAS_FIELD homes to exactly [CONTAINERS_AND_STRINGS]; got "
                + ContainerClaimingSeam.homeRows(has));
        check(!ContainerClaimingSeam.homeRows(has).contains(SemanticCapability.CLASSES),
            "HAS_FIELD never homes to CLASSES (the catalog row is the container "
                + "family's)");

        // The class boundary children home to DESCRIPTORS then BOUNDARIES.
        List<BoundaryKind> classBoundaryKinds = List.of(
            BoundaryKind.UNTYPED_CLASS_INPUT, BoundaryKind.OPTIONAL_FIELD_READ,
            BoundaryKind.CLASS_FIELD_ASSIGNMENT, BoundaryKind.CLASS_LITERAL_FIELD,
            BoundaryKind.CLASS_DEFAULT_FIELD);
        for (BoundaryKind kind : classBoundaryKinds) {
            SemanticOp boundary = handOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, RuntimeDescriptor.Int.INSTANCE,
                    new ValueId(1),
                    new deal.semantic.ir.BoundaryRealization.RuntimeValidation("rt")),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR);
            check(ContainerClaimingSeam.homeRows(boundary).equals(List.of(
                    SemanticCapability.DESCRIPTORS, SemanticCapability.BOUNDARIES)),
                "BOUNDARY(" + kind + ") homes to DESCRIPTORS then BOUNDARIES (pinned "
                    + "order); got " + ContainerClaimingSeam.homeRows(boundary));
        }

        // No non-class op homes to CLASSES: every kind outside the eight
        // class kinds and the class boundary kinds maps nowhere to
        // CLASSES.
        for (SemanticOpKind kind : SemanticOpKind.values()) {
            if (kind == SemanticOpKind.CLASS_NEW || kind == SemanticOpKind.CLASS_FACTORY
                    || kind == SemanticOpKind.CLASS_DEFAULT
                    || kind == SemanticOpKind.FIELD_READ
                    || kind == SemanticOpKind.FIELD_WRITE
                    || kind == SemanticOpKind.FIELD_DELETE
                    || kind == SemanticOpKind.JSON_FROM_CLASS
                    || kind == SemanticOpKind.JSON_TO_CLASS) {
                continue;
            }
            check(ContainerClaimingSeam.homeRows(handOp(kind, payloadFor(kind), null, null,
                    FailurePolicyId.NO_DEAL_FAILURE))
                    .stream().noneMatch(home -> home == SemanticCapability.CLASSES),
                kind + " never homes to CLASSES");
        }
    }

    private static KindPayload payloadFor(SemanticOpKind kind) {
        return switch (kind) {
            case CONST -> new KindPayload.ConstPayload(new ScalarValue.String("x"));
            case UNARY -> new KindPayload.UnaryPayload(
                deal.semantic.ir.UnarySelector.BOOL_NOT);
            case BINARY -> new KindPayload.BinaryPayload(
                deal.semantic.ir.BinarySelector.NUMBER_ADD, null, null);
            case STRING_CONCAT -> new KindPayload.StringConcatPayload(List.of());
            case ARRAY_NEW -> new KindPayload.ArrayNewPayload(
                RuntimeDescriptor.Int.INSTANCE, List.of(), List.of());
            case TABLE_NEW -> new KindPayload.TableNewPayload(List.of());
            case ARRAY_LENGTH -> new KindPayload.ArrayLengthPayload(new ValueId(1));
            case MEMBER_READ -> new KindPayload.MemberReadPayload(new ValueId(1), "k");
            case MEMBER_WRITE -> new KindPayload.MemberWritePayload(new ValueId(1), "k",
                new ValueId(2));
            case MEMBER_DELETE -> new KindPayload.MemberDeletePayload(new ValueId(1), "k");
            case INDEX_NORMALIZE -> new KindPayload.IndexNormalizePayload(
                deal.semantic.ir.IndexMode.TABLE_READ, new ValueId(1), new ValueId(2));
            case INDEX_READ -> new KindPayload.IndexReadPayload(new ValueId(1),
                new ValueId(2), new OpId(MODULE, 1));
            case INDEX_WRITE -> new KindPayload.IndexWritePayload(new ValueId(1),
                new ValueId(2), new ValueId(3));
            case INDEX_DELETE -> new KindPayload.IndexDeletePayload(new ValueId(1),
                new ValueId(2));
            case OPTIONAL_READ -> new KindPayload.OptionalReadPayload(new ValueId(1), true,
                RuntimeDescriptor.Int.INSTANCE);
            case HAS_FIELD -> new KindPayload.HasFieldPayload(new ValueId(1), "k");
            case BOUNDARY -> new KindPayload.BoundaryPayload(
                BoundaryKind.VARIABLE_DECLARATION, RuntimeDescriptor.Int.INSTANCE,
                new ValueId(1),
                new deal.semantic.ir.BoundaryRealization.RuntimeValidation("rt"));
            case BINDING_ALLOC -> new KindPayload.BindingAllocPayload(
                new deal.semantic.ir.BindingId(1), new deal.semantic.ir.BlockId(1), true,
                deal.semantic.ir.BindingCellKind.DIRECT, 0);
            case BINDING_INIT -> new KindPayload.BindingInitPayload(
                new deal.semantic.ir.BindingId(1), 0, new ValueId(1));
            case BINDING_LOAD -> new KindPayload.BindingLoadPayload(
                new deal.semantic.ir.BindingId(1), 0);
            case BINDING_STORE -> new KindPayload.BindingStorePayload(
                new deal.semantic.ir.BindingId(1), 0, new ValueId(1));
            case RECURSIVE_GROUP_INIT -> new KindPayload.RecursiveGroupInitPayload(
                List.of(), List.of());
            case CLOSURE_NEW -> new KindPayload.ClosureNewPayload(
                new deal.semantic.ir.FunctionId(1),
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE,
                    false),
                List.of(),
                new deal.semantic.ir.FunctionExecutionBinding.LoweredBody(
                    new deal.semantic.ir.FunctionId(1), new deal.semantic.ir.BlockId(1)));
            case FUNCTION_ADAPT -> new KindPayload.FunctionAdaptPayload(
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE,
                    false),
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE,
                    false),
                deal.semantic.ir.CaptureMode.VALUE,
                new deal.semantic.ir.AdaptSourceRef.Value(new ValueId(1)), null);
            case ASSIGN -> new KindPayload.AssignPayload(
                deal.semantic.ir.AssignTargetKind.VARIABLE, List.of());
            case DELETE -> new KindPayload.DeletePayload(
                deal.semantic.ir.DeleteTargetKind.TABLE_SLOT, List.of());
            case CALL -> new KindPayload.CallPayload(deal.semantic.ir.CallMode.HOST,
                new KindPayload.CallCallee.Static(
                    new deal.semantic.ir.FunctionExecutionBinding.HostFunction(
                        new ModuleId("host"), "f",
                        new RuntimeDescriptor.Func(List.of(),
                            RuntimeDescriptor.Null.INSTANCE, false))),
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE,
                    false),
                List.of(), null, null, null, null);
            case EXTERNAL_ENTRY -> new KindPayload.ExternalEntryPayload("f",
                new deal.semantic.ir.FunctionId(1),
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE,
                    false),
                false, new OpId(MODULE, 1), null);
            case CALLBACK_INVOKE -> new KindPayload.CallbackInvokePayload(new ValueId(1),
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Null.INSTANCE,
                    false),
                List.of(), new OpId(MODULE, 1));
            case INTRINSIC_CALL -> new KindPayload.IntrinsicCallPayload(
                deal.semantic.ir.IntrinsicKind.NUMBER_CONVERT, new ValueId(1));
            case STDLIB_CALL -> new KindPayload.StdlibCallPayload(
                deal.semantic.ir.StdlibFunctionId.STRING_TRIM, List.of(new ValueId(1)),
                SemanticCapability.STDLIB_SEMANTICS);
            case ASYNC_START -> new KindPayload.AsyncStartPayload(
                new KindPayload.CallCallee.Static(
                    new deal.semantic.ir.FunctionExecutionBinding.HostFunction(
                        new ModuleId("host"), "f",
                        new RuntimeDescriptor.Func(List.of(),
                            RuntimeDescriptor.Null.INSTANCE, true))),
                deal.semantic.ir.AsyncStartSource.HOST,
                deal.semantic.ir.ParameterBoundaryMode.RUN, List.of(),
                RuntimeDescriptor.Null.INSTANCE, null, "op-label", null);
            case AWAIT -> new KindPayload.AwaitPayload(
                new deal.semantic.ir.AsyncTokenId.Canonical(1,
                    deal.semantic.ir.AsyncTokenOwner.HOST_OPERATION),
                RuntimeDescriptor.Null.INSTANCE, new OpId(MODULE, 1));
            case BRANCH -> new KindPayload.BranchPayload(
                deal.semantic.ir.ControlSelector.IF, new ValueId(1),
                new deal.semantic.ir.BlockId(1), null);
            case LOOP -> new KindPayload.LoopPayload(
                deal.semantic.ir.ControlSelector.WHILE, null, new ValueId(1),
                new deal.semantic.ir.BlockId(1), null);
            case FOR_EACH -> new KindPayload.ForEachPayload(
                deal.semantic.ir.IterationMode.ARRAY_VALUES, new ValueId(1),
                new deal.semantic.ir.BindingId(1), 0, new deal.semantic.ir.BlockId(1));
            case TRY_CATCH -> new KindPayload.TryCatchPayload(
                new deal.semantic.ir.BlockId(1), new deal.semantic.ir.BindingId(1),
                new deal.semantic.ir.BlockId(2));
            case THROW -> new KindPayload.ThrowPayload(new ValueId(1));
            case RETURN -> new KindPayload.ReturnPayload(new ValueId(1),
                new deal.semantic.ir.FunctionId(1), new OpId(MODULE, 1),
                new OpId(MODULE, 2));
            case BREAK -> new KindPayload.BreakPayload(new OpId(MODULE, 1));
            case CONTINUE -> new KindPayload.ContinuePayload(new OpId(MODULE, 1));
            case DISCARD -> new KindPayload.DiscardPayload(new ValueId(1));
            case CLASS_DEFAULT -> new KindPayload.ClassDefaultPayload(
                new ClassId("m", "C"), "f", new deal.semantic.ir.BlockId(1));
            case CLASS_NEW -> new KindPayload.ClassNewPayload(new ClassId("m", "C"),
                new ClassLayout(new ClassId("m", "C"), List.of()), List.of(),
                DefaultOwner.LOCAL, List.of(), null, List.of());
            case CLASS_FACTORY -> new KindPayload.ClassFactoryPayload(
                new ClassId("m", "C"), List.of(), new OpId(MODULE, 1));
            case FIELD_READ -> new KindPayload.FieldReadPayload(new ValueId(1),
                new ClassId("m", "C"), "f");
            case FIELD_WRITE -> new KindPayload.FieldWritePayload(new ValueId(1),
                new ClassId("m", "C"), "f", new ValueId(2));
            case FIELD_DELETE -> new KindPayload.FieldDeletePayload(new ValueId(1),
                new ClassId("m", "C"), "f");
            case JSON_FROM_CLASS -> new KindPayload.JsonFromClassPayload(
                new ClassLayout(new ClassId("m", "C"), List.of()), new ValueId(1));
            case JSON_TO_CLASS -> new KindPayload.JsonToClassPayload(new ValueId(1),
                new ClassLayout(new ClassId("m", "C"), List.of()));
            case MODULE_INIT -> new KindPayload.ModuleInitPayload(MODULE, List.of(),
                new deal.semantic.ir.BlockId(1));
            case MODULE_IMPORT -> new KindPayload.ModuleImportPayload("x", MODULE,
                deal.semantic.ir.ModuleImportKind.COMPILED);
            case EXPORT_READ -> new KindPayload.ExportReadPayload(MODULE, "x",
                RuntimeDescriptor.Int.INSTANCE, new ValueId(1));
            case EXPORT_PUBLISH -> new KindPayload.ExportPublishPayload(MODULE, "x",
                RuntimeDescriptor.Int.INSTANCE, new ValueId(1));
            case ENTRY_INVOKE -> new KindPayload.EntryInvokePayload(MODULE,
                new deal.semantic.ir.FunctionId(1));
        };
    }

    private static SemanticOp handOp(SemanticOpKind kind, KindPayload payload,
                                     deal.semantic.ir.SemanticValue result,
                                     deal.semantic.ir.OpResultType resultType,
                                     FailurePolicyId policy) {
        deal.semantic.ir.ClosedSelector selector =
            payload instanceof KindPayload.SelectorCarrying carrying
                ? carrying.selector() : null;
        deal.semantic.ir.OperationContractSnapshot contract =
            new deal.semantic.ir.OperationContractSnapshot(
                deal.semantic.ir.OperationContractSnapshot.VERSION, kind, resultType,
                List.of(), selector, payload, policy, List.of(), "placeholder");
        return new SemanticOp(new OpId(MODULE, 99_999), kind,
            new SourceOrigin(SOURCE_ID, SourceSpan.synthetic(SOURCE_ID),
                SourceOriginKind.SYNTHETIC, new AnchorId(0), null),
            result, resultType, List.of(), List.of(), payload, policy, contract);
    }

    // =========================================================================
    // 2. The pinned E9-gate activation hand-off
    // =========================================================================

    private static void testActivationHandoff() {
        System.out.println("-- the pinned E9-gate activation hand-off --");

        Set<SemanticCapability> expected = EnumSet.copyOf(
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        expected.add(SemanticCapability.CLASSES);
        check(ContainerClaimingSeam.E9_GATE_ACTIVATION.equals(expected),
            "E9_GATE_ACTIVATION is exactly the E6-gate activation plus CLASSES; got "
                + ContainerClaimingSeam.E9_GATE_ACTIVATION);
        check(!ContainerClaimingSeam.E9_GATE_ACTIVATION.contains(
                SemanticCapability.STDLIB_SEMANTICS)
                && !ContainerClaimingSeam.E9_GATE_ACTIVATION.contains(
                    SemanticCapability.CALLS)
                && !ContainerClaimingSeam.E9_GATE_ACTIVATION.contains(
                    SemanticCapability.MODULES),
            "E9_GATE_ACTIVATION activates only the class epic's row (STDLIB_SEMANTICS, "
                + "CALLS, and MODULES stay staged)");
    }

    // =========================================================================
    // 3. The staged/claimed/deferred discipline
    // =========================================================================

    private static void testStagedDiscipline() {
        System.out.println("-- the staged/claimed/deferred discipline --");

        // The full-family unit (lowered through the real seam): every
        // CLASSES family op.
        SemanticLowerer.ClassDeclarationCoreResult result = lowerModule("""
            // @jsonable
            export class Point {
              x: int = 40 + 2;
              tag: string = "p";
              note?: string;
            }
            let p: Point = {x: 1}
            p.x = 9
            p.note = "a"
            delete p.note
            let hasNote: boolean = has(p.note)
            let note: string | null = p.note
            """);
        check(result != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null, "the full-family slice lowers");
        if (result == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return;
        }
        LoweredModuleUnit unit = result.lowering().unit();
        List<SemanticOpKind> families = List.of(SemanticOpKind.CLASS_NEW,
            SemanticOpKind.CLASS_FACTORY, SemanticOpKind.CLASS_DEFAULT,
            SemanticOpKind.FIELD_READ, SemanticOpKind.FIELD_WRITE,
            SemanticOpKind.FIELD_DELETE, SemanticOpKind.JSON_FROM_CLASS,
            SemanticOpKind.JSON_TO_CLASS);
        for (SemanticOpKind family : families) {
            check(!ofKind(unit, family).isEmpty(),
                "the full-family unit produces " + family);
        }

        // Staged: under E6_GATE_ACTIVATION the CLASSES row is inactive —
        // the derivation excludes it and every class-op position records
        // the staged hand-off.
        Set<SemanticCapability> stagedDerived = ContainerClaimingSeam.deriveClaims(
            unit.ops(), ContainerClaimingSeam.E6_GATE_ACTIVATION);
        check(!stagedDerived.contains(SemanticCapability.CLASSES),
            "under E6_GATE_ACTIVATION the derivation excludes CLASSES (staged until "
                + "the class epic's gate)");
        SeamResult stagedSeam = ContainerClaimingSeam.check(unit.ops(),
            ContainerClaimingSeam.E6_GATE_ACTIVATION, stagedDerived, unit.moduleId());
        check(stagedSeam.failure() == null, "the staged check records no failure");
        for (RecordedOutcome outcome : stagedSeam.outcomes()) {
            if (outcome.home() == SemanticCapability.CLASSES) {
                check(outcome.outcome() == OutcomeKind.STAGED_HAND_OFF,
                    "a staged class-op position records STAGED_HAND_OFF for the CLASSES "
                        + "home row");
            }
        }

        // The HAS_FIELD row is active since E5's gate: under the same
        // staged check the HAS_FIELD position records DEFERRED (the unit
        // does not fully evidence CONTAINERS_AND_STRINGS).
        for (RecordedOutcome outcome : stagedSeam.outcomes()) {
            if (outcome.opKind() == SemanticOpKind.HAS_FIELD
                    && outcome.home() == SemanticCapability.CONTAINERS_AND_STRINGS) {
                check(outcome.outcome() == OutcomeKind.DEFERRED,
                    "the HAS_FIELD position records the per-unit CONTAINERS_AND_STRINGS "
                        + "deferral (the row is active and the unit does not fully "
                        + "evidence it)");
            }
        }

        // Claimed: under the E9-gate activation the derivation claims
        // CLASSES and the seam records CLAIMED — the full-evidence
        // derivation holds by construction.
        Set<SemanticCapability> derived = ContainerClaimingSeam.deriveClaims(unit.ops(),
            ContainerClaimingSeam.E9_GATE_ACTIVATION);
        check(derived.contains(SemanticCapability.CLASSES),
            "under E9_GATE_ACTIVATION the full-family unit derives the CLASSES claim");
        check(unit.requiredCapabilities().contains(SemanticCapability.CLASSES),
            "the produced unit carries the derived CLASSES claim (the producer "
                + "records the seam's derivation): got " + unit.requiredCapabilities());
        SeamResult seam = ContainerClaimingSeam.check(unit.ops(),
            ContainerClaimingSeam.E9_GATE_ACTIVATION, unit.requiredCapabilities(),
            unit.moduleId());
        check(seam.failure() == null, "the E9-gate check passes with the unit's claims");
        int claimed = 0;
        for (RecordedOutcome outcome : seam.outcomes()) {
            if (outcome.home() == SemanticCapability.CLASSES) {
                check(outcome.outcome() == OutcomeKind.CLAIMED,
                    "an active class-op position records CLAIMED for the CLASSES home "
                        + "row");
                claimed++;
            }
        }
        check(claimed > 0, "class-op positions record CLAIMED outcomes; got " + claimed);

        // The producer-defect guard: claiming the row under the inactive
        // activation is impossible by the derivation.
        boolean guarded = false;
        try {
            ContainerClaimingSeam.check(unit.ops(),
                ContainerClaimingSeam.E6_GATE_ACTIVATION, derived, unit.moduleId());
        } catch (IllegalArgumentException expected) {
            guarded = true;
        }
        check(guarded,
            "a claim of the inactive CLASSES row is the producer-defect guard (never a "
                + "silent claim without evidence)");

        // The partial unit: a field read only — the per-unit deferral,
        // never a claim and never a failure.
        SemanticLowerer.ClassDeclarationCoreResult partial = lowerModule("""
            class Point { x: int; }
            let p: Point = {x: 1}
            let v: int = p.x
            """);
        check(partial != null && !partial.lowering().hasErrors()
                && partial.lowering().unit() != null, "the partial slice lowers");
        if (partial == null || partial.lowering().hasErrors()
                || partial.lowering().unit() == null) {
            return;
        }
        check(!partial.lowering().unit().requiredCapabilities().contains(
                SemanticCapability.CLASSES),
            "the partial unit never claims CLASSES (the derivation requires the full "
                + "eight-family evidence): got "
                + partial.lowering().unit().requiredCapabilities());
        SeamResult partialSeam = ContainerClaimingSeam.check(
            partial.lowering().unit().ops(), ContainerClaimingSeam.E9_GATE_ACTIVATION,
            partial.lowering().unit().requiredCapabilities(),
            partial.lowering().unit().moduleId());
        check(partialSeam.failure() == null, "the partial check records no failure");
        boolean deferred = false;
        for (RecordedOutcome outcome : partialSeam.outcomes()) {
            if (outcome.home() == SemanticCapability.CLASSES) {
                check(outcome.outcome() == OutcomeKind.DEFERRED,
                    "a partial unit's class-op position records the per-unit CLASSES "
                        + "deferral");
                deferred = true;
            }
        }
        check(deferred, "the partial unit records at least one CLASSES deferral");
    }

    // =========================================================================
    // 4. The plan-time manifest arm
    // =========================================================================

    private static void testPlanTimeManifestArm() {
        System.out.println("-- the plan-time CLASSES manifest arm --");

        // (a) A class declaration claims CLASSES at plan time.
        check(planCaps("""
                class Point { x: int; }
                """).contains(SemanticCapability.CLASSES),
            "a class-declaration module claims CLASSES at plan time");

        // (b) A class-typed literal claims CLASSES at plan time.
        check(planCaps("""
                class Point { x: int; }
                let p: Point = {x: 1}
                """).contains(SemanticCapability.CLASSES),
            "a class-literal module claims CLASSES at plan time");

        // (c) A class member access claims CLASSES at plan time.
        check(planCaps("""
                class Point { x: int; }
                let p: Point = {x: 1}
                let v: int = p.x
                """).contains(SemanticCapability.CLASSES),
            "a class member-access module claims CLASSES at plan time");

        // (d) A class-field write and delete claim CLASSES at plan time.
        check(planCaps("""
                class Point { x: int; note?: string; }
                let p: Point = {x: 1}
                p.x = 9
                delete p.note
                """).contains(SemanticCapability.CLASSES),
            "a class write/delete module claims CLASSES at plan time");

        // (e) A has() expression claims CLASSES at plan time.
        check(planCaps("""
                class Point { x: int; note?: string; }
                let p: Point = {x: 1}
                let h: boolean = has(p.note)
                """).contains(SemanticCapability.CLASSES),
            "a has() module claims CLASSES at plan time");

        // (f) A class-free module never claims CLASSES.
        Set<SemanticCapability> plain = planCaps("""
            let n: int = 1
            let m: int = n + 2
            """);
        check(plain != null && !plain.contains(SemanticCapability.CLASSES),
            "a class-free module never claims CLASSES at plan time; got " + plain);

        // (g) The claim names the op-producing constructs only: a
        // class-free function body with a local int never claims
        // CLASSES (the negative control of the claim's precision).
        Set<SemanticCapability> functionOnly = planCaps("""
            function make(): null { let n: int = 1; return null }
            """);
        check(functionOnly != null && !functionOnly.contains(SemanticCapability.CLASSES),
            "a class-free function module never claims CLASSES at plan time; got "
                + functionOnly);
        check(functionOnly != null && functionOnly.contains(
                SemanticCapability.FOUNDATION_VALUES),
            "the class-free module still carries its construction-derived claims; got "
                + functionOnly);
    }

    private static Set<SemanticCapability> planCaps(String source) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        PipelineSlice pipeline = pipeline(slice);
        if (pipeline == null) {
            return null;
        }
        return pipeline.manifests().get(0).capabilities();
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Class Claiming Seam Tests (ISSUE-0516 K-D7/K-D11) ===");
        testHomeRows();
        testActivationHandoff();
        testStagedDiscipline();
        testPlanTimeManifestArm();
        System.out.println();
        if (failed == 0) {
            System.out.println("ClassClaimingSeamTest: " + passed + " passed, "
                + failed + " failed");
        } else {
            System.out.println("ClassClaimingSeamTest: " + passed + " passed, "
                + failed + " failed");
            System.exit(1);
        }
    }
}
