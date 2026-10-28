package deal.semantic;

import deal.ast.AssertionExpr;
import deal.ast.NamedType;
import deal.ast.QualifiedType;
import deal.checker.NameResolver;
import deal.ast.InstanceofExpr;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AssignmentExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.Block;
import deal.ast.BreakStatement;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ContinueStatement;
import deal.ast.DeleteStatement;
import deal.ast.Either;
import deal.ast.ExportDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ForOfStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.ImportDeclaration;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.ReturnStatement;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
import deal.ast.Property;
import deal.ast.ReturnStatement;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TryStatement;
import deal.ast.UnaryExpr;
import deal.ast.UnaryOp;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.checker.BuiltinErrorDeclaration;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.identity.CanonicalModuleIdentity;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.DiagnosticOriginCarrier;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.InternalResultType;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.NamespaceRegistrations;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.AddressChainProtocol;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.AssignTargetKind;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BindingImmutabilityProof;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ClassBindingFacts;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ConstructionProvider;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DeleteTargetKind;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SharedFactoryFacts;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.ValueId;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SemanticLowerer {

    /** The producer fact-defect identifier of the E6005 unlowered-construct arm (D7). */
    public static final String CONSTRUCT_UNLOWERED = "CONSTRUCT_UNLOWERED";


/**
     * The pinned canonical realization id of every lowerer-created
     * boundary child (D3): {@code RuntimeValidation("runtime-validation")}
     * — a pinned constant inside the contract digest, so units and dumps
     * are byte-identical, and E4's report-completion predicate carries it
     * unchanged.
     */
    public static final String CANONICAL_RUNTIME_VALIDATION_ID = "runtime-validation";

    public static final long INITIAL_LOOP_GENERATION = 0L;



    public static final String RETAINED_ABI_DEFERRED = "RETAINED_ABI_DEFERRED";

    public enum BindingProducer {

        /** The incarnation's producing allocation is its {@code BINDING_ALLOC} op. */
        BINDING_ALLOC,

        /** The incarnation's producing allocation is the {@code FOR_EACH} iteration op. */
        FOR_EACH,

        /**
         * The incarnation's producing allocation is the
         * {@code RECURSIVE_GROUP_INIT} group op (the member's binding
         * cell is published by the group's atomic publication phase).
         */
        RECURSIVE_GROUP_INIT
    }


    public record BindingCoreIncarnation(long generation, BlockId scope,
                                         BindingCellKind cellKind, boolean mutable,
                                         BindingProducer producer,
                                         boolean pinnedSharedCell) {

        public BindingCoreIncarnation {
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(cellKind, "cellKind must not be null");
            Objects.requireNonNull(producer, "producer must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException(
                    "generation must be >= 0, got " + generation);
            }
        }
    }

    /**
     * One declared name's binding-core facts: the single globally unique
     * {@link BindingId} of the declared name and its incarnations in
     * allocation (source) order.
     */
    public record BindingCoreBinding(String name, BindingId binding,
                                     List<BindingCoreIncarnation> incarnations) {

        public BindingCoreBinding {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            Objects.requireNonNull(incarnations, "incarnations must not be null");
            incarnations = List.copyOf(incarnations);
        }
    }

    /**
     * The binding-core walk's complete fact surface: one entry per
     * declared name in the pinned registration order (intrinsic bindings
     * first, then module-level hoisted names in declaration order, then
     * every remaining declaration in source order).
     */
    public record BindingCoreFacts(List<BindingCoreBinding> bindings) {

        /** The empty fact set (the failure-path value). */
        public static BindingCoreFacts empty() {
            return new BindingCoreFacts(List.of());
        }

        public BindingCoreFacts {
            Objects.requireNonNull(bindings, "bindings must not be null");
            bindings = List.copyOf(bindings);
        }
    }

    /**
     * The result of the binding-core entry point: the validated lowering
     * result plus the walk's binding facts (partial on failure, complete
     * on success).
     */
    public record BindingCoreResult(LoweringResult lowering, BindingCoreFacts facts) {

        public BindingCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(facts, "facts must not be null");
        }
    }

    public static final class CellKindDerivation {

        /** The registered capture references (identity semantics). */
        private final Set<BindingCoreIncarnation> captureReferences =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        /**
         * Registers one capture reference (B2's closure-capture arm for
         * this child; the shape-map child registers the thunk/adapter
         * arms through the same surface): the incarnation the capture
         * resolves to at the capture's creation site.
         *
         */
        public void registerCaptureReference(BindingCoreIncarnation incarnation) {
            captureReferences.add(Objects.requireNonNull(incarnation,
                "incarnation must not be null"));
        }

        /** True iff any capture reference resolves to the incarnation. */
        public boolean isCaptured(BindingCoreIncarnation incarnation) {
            return captureReferences.contains(incarnation);
        }

        /** The number of registered capture references. */
        public int captureReferenceCount() {
            return captureReferences.size();
        }

        /**
         * The derived cell kind of one incarnation: {@code SHARED_CELL}
         * iff the incarnation is a pinned special case or any registered
         * capture resolves to it; {@code DIRECT} otherwise (reassignability
         * alone never forces {@code SHARED_CELL} — the {@code mutable}
         * flag records it independently).
         *
         */
        public BindingCellKind cellKindOf(BindingCoreIncarnation incarnation) {
            Objects.requireNonNull(incarnation, "incarnation must not be null");
            if (incarnation.pinnedSharedCell() || isCaptured(incarnation)) {
                return BindingCellKind.SHARED_CELL;
            }
            return BindingCellKind.DIRECT;
        }
    }

    /**
     * One resolved closure capture of the closure child's fact surface:
     * the captured binding plus the producing allocation the capture
     * resolves to at the detaching op's creation site — the dominant
     * incarnation's generation, the block its producing allocation sits
     * in, and the producing-allocation kind (B9 R1/R2: the resolution
     * evaluated at the {@code CLOSURE_NEW} creation site, recursively
     * along the detaching chain).
     */
    public record ClosureCapture(String name, BindingId binding, long generation,
                                 BlockId scope, BindingProducer producer) {

        public ClosureCapture {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(producer, "producer must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException(
                    "generation must be >= 0, got " + generation);
            }
        }
    }

    public record ClosureFacts(FunctionId functionId, RuntimeDescriptor.Func signature,
                               BlockId bodyBlock, List<ClosureCapture> captures) {

        public ClosureFacts {
            Objects.requireNonNull(functionId, "functionId must not be null");
            Objects.requireNonNull(signature, "signature must not be null");
            Objects.requireNonNull(bodyBlock, "bodyBlock must not be null");
            Objects.requireNonNull(captures, "captures must not be null");
            captures = List.copyOf(captures);
        }
    }

    /**
     * The result of the closure-core entry point: the validated lowering
     * result, the binding walk's binding facts (with final derived cell
     * kinds), and the produced closures' capture facts in creation order
     * (partial on failure, complete on success).
     */
    public record ClosureCoreResult(LoweringResult lowering, BindingCoreFacts bindingFacts,
                                    List<ClosureFacts> closures) {

        public ClosureCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
            Objects.requireNonNull(closures, "closures must not be null");
            closures = List.copyOf(closures);
        }
    }

    public record GroupMemberFacts(String name, BindingId binding, FunctionId functionId,
                                   ValueId identity, RuntimeDescriptor.Func signature,
                                   BlockId bodyBlock, List<ClosureCapture> captures) {

        public GroupMemberFacts {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            Objects.requireNonNull(functionId, "functionId must not be null");
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(signature, "signature must not be null");
            Objects.requireNonNull(bodyBlock, "bodyBlock must not be null");
            Objects.requireNonNull(captures, "captures must not be null");
            captures = List.copyOf(captures);
        }
    }

    public record GroupFacts(OpId opId, List<GroupMemberFacts> members, BlockId block) {

        public GroupFacts {
            Objects.requireNonNull(opId, "opId must not be null");
            Objects.requireNonNull(members, "members must not be null");
            Objects.requireNonNull(block, "block must not be null");
            members = List.copyOf(members);
        }
    }

    /**
     * The result of the group-core entry point: the validated lowering
     * result, the binding walk's binding facts (with final derived cell
     * kinds), the produced closures' capture facts in creation order,
     * and the produced recursive groups' member facts in creation order
     * (partial on failure, complete on success).
     */
    public record GroupCoreResult(LoweringResult lowering, BindingCoreFacts bindingFacts,
                                  List<ClosureFacts> closures, List<GroupFacts> groups) {

        public GroupCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
            Objects.requireNonNull(closures, "closures must not be null");
            Objects.requireNonNull(groups, "groups must not be null");
            closures = List.copyOf(closures);
            groups = List.copyOf(groups);
        }
    }

    public record ImmutabilityCoreResult(
            LoweringResult lowering, BindingCoreFacts bindingFacts,
            List<ClosureFacts> closures, List<GroupFacts> groups,
            BindingImmutabilityAnalysis.BindingImmutabilityFacts proofFacts) {

        public ImmutabilityCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
            Objects.requireNonNull(closures, "closures must not be null");
            Objects.requireNonNull(groups, "groups must not be null");
            Objects.requireNonNull(proofFacts, "proofFacts must not be null");
            closures = List.copyOf(closures);
            groups = List.copyOf(groups);
        }
    }

    public record CreationRuleCoreResult(
            LoweringResult lowering, BindingCoreFacts bindingFacts,
            List<ClosureFacts> closures, List<GroupFacts> groups,
            BindingImmutabilityAnalysis.BindingImmutabilityFacts proofFacts,
            AdapterCreationRule.CreationRuleFacts creationFacts) {

        public CreationRuleCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
            Objects.requireNonNull(closures, "closures must not be null");
            Objects.requireNonNull(groups, "groups must not be null");
            Objects.requireNonNull(proofFacts, "proofFacts must not be null");
            Objects.requireNonNull(creationFacts, "creationFacts must not be null");
            closures = List.copyOf(closures);
            groups = List.copyOf(groups);
        }
    }

    public record AdapterEmission(
            OpId adaptOpId, ValueId adapterIdentity, CaptureMode mode,
            AdaptSourceRef sourceRef, RuntimeDescriptor.Func sourceSignature,
            RuntimeDescriptor.Func targetSignature, BindingImmutabilityProof proof,
            OpId wiringTargetOpId, BoundaryKind wiringBoundaryKind) {

        public AdapterEmission {
            Objects.requireNonNull(adaptOpId, "adaptOpId must not be null");
            Objects.requireNonNull(adapterIdentity, "adapterIdentity must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            Objects.requireNonNull(sourceRef, "sourceRef must not be null");
            Objects.requireNonNull(sourceSignature, "sourceSignature must not be null");
            Objects.requireNonNull(targetSignature, "targetSignature must not be null");
            Objects.requireNonNull(wiringTargetOpId, "wiringTargetOpId must not be null");
            Objects.requireNonNull(wiringBoundaryKind, "wiringBoundaryKind must not be null");
        }
    }

    /**
     * The shape-map child's complete emission fact surface of one module
     * lowering: one {@link AdapterEmission} per emitted
     * {@code FUNCTION_ADAPT} op in emission order (partial on a failed
     * walk, complete on success).
     */
    public record ShapeMapFacts(List<AdapterEmission> emissions) {

        /** The empty fact set (the failure-path value). */
        public static ShapeMapFacts empty() {
            return new ShapeMapFacts(List.of());
        }

        public ShapeMapFacts {
            Objects.requireNonNull(emissions, "emissions must not be null");
            emissions = List.copyOf(emissions);
        }
    }

    public record ShapeMapCoreResult(
            LoweringResult lowering, BindingCoreFacts bindingFacts,
            List<ClosureFacts> closures, List<GroupFacts> groups,
            BindingImmutabilityAnalysis.BindingImmutabilityFacts proofFacts,
            AdapterCreationRule.CreationRuleFacts creationFacts,
            ShapeMapFacts shapeMapFacts) {

        public ShapeMapCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
            Objects.requireNonNull(closures, "closures must not be null");
            Objects.requireNonNull(groups, "groups must not be null");
            Objects.requireNonNull(proofFacts, "proofFacts must not be null");
            Objects.requireNonNull(creationFacts, "creationFacts must not be null");
            Objects.requireNonNull(shapeMapFacts, "shapeMapFacts must not be null");
            closures = List.copyOf(closures);
            groups = List.copyOf(groups);
        }
    }

    public record ValidationCoreResult(
            LoweringResult lowering, BindingCoreFacts bindingFacts,
            List<ClosureFacts> closures, List<GroupFacts> groups,
            BindingImmutabilityAnalysis.BindingImmutabilityFacts proofFacts,
            AdapterCreationRule.CreationRuleFacts creationFacts,
            ShapeMapFacts shapeMapFacts) {

        public ValidationCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
            Objects.requireNonNull(closures, "closures must not be null");
            Objects.requireNonNull(groups, "groups must not be null");
            Objects.requireNonNull(proofFacts, "proofFacts must not be null");
            Objects.requireNonNull(creationFacts, "creationFacts must not be null");
            Objects.requireNonNull(shapeMapFacts, "shapeMapFacts must not be null");
            closures = List.copyOf(closures);
            groups = List.copyOf(groups);
        }
    }

    @FunctionalInterface
    public interface BindingSiteResolver {

        /**
         * The dominant incarnation of the named declared binding at the
         * current site, or {@code null} when the name is not a declared
         * binding of the installed environment.
         *
         */
        BindingSite resolve(String name);
    }

    /**
     * One generation-pinned binding reference of the installed resolver:
     * the binding identity plus the dominant generation at the site.
     */
    public record BindingSite(BindingId binding, long generation) {

        public BindingSite {
            Objects.requireNonNull(binding, "binding must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException(
                    "generation must be >= 0, got " + generation);
            }
        }
    }

    private SemanticLowerer() {
        // Static entry points plus the per-module lowering session; no instances.
    }

    public static final class ConstructUnlowered extends RuntimeException
            implements DiagnosticOriginCarrier {

        private static final long serialVersionUID = 1L;

        /** The human-readable construct description (pinned in the diagnostic's origin). */
        private final String construct;

        /**
         * The offending construct's own source span, or {@code null} when
         * the defect names a producer fact rather than one source construct
         * (a genuinely originless failure keeps the canonical synthetic
         * E6005 range).
         */
        private final Span span;

        public ConstructUnlowered(String construct) {
            this(construct, null);
        }

        public ConstructUnlowered(String construct, Span span) {
            super("a construct without a lowering arm reached this stage: " + construct);
            this.construct = Objects.requireNonNull(construct, "construct must not be null");
            this.span = span;
        }

        /** The construct description carried into the E6005 origin. */
        public String construct() {
            return construct;
        }

        /** The offending construct's own span, or {@code null}. */
        public Span span() {
            return span;
        }

        @Override
        public DiagnosticRange diagnosticRange() {
            return span == null ? null : span.range();
        }
    }

    public static final class RetainedAbiDeferred extends RuntimeException
            implements DiagnosticOriginCarrier {

        private static final long serialVersionUID = 1L;

        /** The imported class identity carried into the E6005 origin. */
        private final String classId;

        /**
         * The offending literal's own source span, or {@code null} when the
         * defect names a producer fact rather than one source construct.
         */
        private final Span span;

        public RetainedAbiDeferred(String classId) {
            this(classId, null);
        }

        public RetainedAbiDeferred(String classId, Span span) {
            super("class-typed literal of imported class " + classId
                + " whose owner carries no shared factory facts (the "
                + "RETAINED_ABI derivation is E10's, ISSUE-0239)");
            this.classId = Objects.requireNonNull(classId, "classId must not be null");
            this.span = span;
        }

        /** The imported class identity carried into the E6005 origin. */
        public String classId() {
            return classId;
        }

        /** The offending literal's own span, or {@code null}. */
        public Span span() {
            return span;
        }

        @Override
        public DiagnosticRange diagnosticRange() {
            return span == null ? null : span.range();
        }
    }


/**
     * The offending source range of one lowering defect, or {@code null}
     * when the defect names a producer fact rather than one source construct
     * (a guessed anchor is never manufactured). Every source-bearing
     * lowering signal implements the common
     * {@link DiagnosticOriginCarrier} contract (design D2): the signals
     * carry their own span, while the descriptor and comparison producers
     * attach their offending construct's own range at the
     * expression/literal boundary (D2); an unattached defect stays
     * genuinely originless.
     */
    private static DiagnosticRange offendingRange(RuntimeException defect) {
        return defect instanceof DiagnosticOriginCarrier carrier
            ? carrier.diagnosticRange()
            : null;
    }

    /**
     * The E6005 diagnostic of one lowering defect: the registry-owned message
     * plus the defect's own offending source range when the defect identifies
     * one (never an invented anchor), else the canonical synthetic range. The
     * detail payload is exactly {@link #loweringFailureDetail}'s.
     */
    public static CompilerDiagnostic loweringFailure(ModuleId module,
                                                     RuntimeException defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        LoweringFailureDetail detail = loweringFailureDetail(module, defect);
        DiagnosticRange range = offendingRange(defect);
        return range == null
            ? FailureContractRegistry.e6005(detail)
            : FailureContractRegistry.e6005(detail, range);
    }

    public static LoweringFailureDetail loweringFailureDetail(ModuleId module,
                                                              RuntimeException defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        if (defect instanceof ConstructUnlowered unlowered) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.CONTAINERS_AND_STRINGS, CONSTRUCT_UNLOWERED,
                SemanticProfile.DEAL_V1_5_BINARY64, LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer " + CONSTRUCT_UNLOWERED + " (" + unlowered.construct() + ")");
        }

        if (defect instanceof RetainedAbiDeferred deferred) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.CLASSES, RETAINED_ABI_DEFERRED,
                SemanticProfile.DEAL_V1_5_BINARY64, LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer " + RETAINED_ABI_DEFERRED + " (class "
                    + deferred.classId() + " — the RETAINED_ABI derivation is E10's)");
        }
        if (defect instanceof ContainerPayloadDescriptors.Defect descriptorDefect) {
            return ContainerPayloadDescriptors.loweringFailureDetail(module, descriptorDefect);
        }
        throw new IllegalArgumentException(
            "a defect kind without an E6005 projection in this stage reached the seam: "
                + defect.getClass().getSimpleName());
    }

    /**
     * The shared non-null preamble of the public lowering entries: the
     * six required inputs of every per-module lowering request.
     */
    private static void requireLoweringInputs(CheckedModuleInput module,
                                              SemanticProfile profile,
                                              Map<ConstructKind, List<SemanticOpKind>>
                                                  constructCoverage,
                                              String interfaceHash,
                                              String capabilityRegistryHash,
                                              SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
    }

    /**
     * The first two closed gates of a produced unit (the common-admission
     * head — the 14-rule unit validator with the dynamic/layout
     * correlations and the plan links — and the address-chain protocol, in
     * order): empty on pass, otherwise the first E6005 as a failed
     * {@link LoweringResult}. The head deliberately excludes the
     * control-flow owner: the composed per-module order runs it after the
     * address-chain protocol (see {@link #finishLowering}), never twice.
     */
    private static Optional<LoweringResult> validateUnitGates(
            LoweredModuleUnit unit, SemanticProfile profile, String interfaceHash,
            String capabilityRegistryHash) {
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return Optional.of(new LoweringResult(null, null, List.of(validation.get())));
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return Optional.of(new LoweringResult(null, null, List.of(chainShape.get())));
        }
        return Optional.empty();
    }

    /**
     * The complete validator tail of a produced unit: the two shared
     * gates, then the control-flow validator over the unit's block table
     * (produced only after the gates pass); returns the validated unit
     * plus its table, or the first failing gate's E6005.
     */
    private static LoweringResult finishLowering(ModuleLowerer lowerer,
                                                 LoweredModuleUnit unit,
                                                 SemanticProfile profile,
                                                 String interfaceHash,
                                                 String capabilityRegistryHash) {
        Optional<LoweringResult> gates = validateUnitGates(unit, profile, interfaceHash,
            capabilityRegistryHash);
        if (gates.isPresent()) {
            return gates.get();
        }
        StructuredBodyTable table = lowerer.bodyTable();
        Optional<CompilerDiagnostic> controlFlow = ControlFlowValidator.validate(unit, table);
        if (controlFlow.isPresent()) {
            return new LoweringResult(null, null, List.of(controlFlow.get()));
        }
        return new LoweringResult(unit, table, List.of());
    }

    /**
     * The result of the module-level unit production: the validated unit
     * plus its produced {@link StructuredBodyTable} (C-D1), or
     * {@code null}/{@code null} with exactly the first E6005 diagnostic
     * on failure (a lowered-away construct, an unrepresentable
     * descriptor, a validator rejection of the produced unit, an
     * address-chain protocol violation, or a control-flow validation
     * rejection).
     *
     * <p>The retained body table is exactly the produced unit's owned
     * {@link LoweredModuleUnit#bodyTable()} — the same instance — so the
     * result can never expose a second table authority describing a
     * different graph. A supplied table that disagrees with the unit
     * rejects before exposure; a content-equal detached table is replaced
     * by the owned record.</p>
     *
     */
    public record LoweringResult(LoweredModuleUnit unit, StructuredBodyTable table,
                                 List<CompilerDiagnostic> diagnostics) {

        public LoweringResult {
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
            if (unit != null) {
                if (table == null || !table.equals(unit.bodyTable())) {
                    throw new IllegalArgumentException(
                        "the retained result table must be exactly the produced"
                            + " unit's owned bodyTable, never an independent table");
                }
                table = unit.bodyTable();
            }
        }

        /** True iff the lowering failed (no unit was produced). */
        public boolean hasErrors() {
            return !diagnostics.isEmpty();
        }
    }

    public record FullProgramE7Result(LoweringResult lowering,
                                      Map<String, OpId> externalEntries,
                                      Map<String, OpId> callbackInvokes) {

        public FullProgramE7Result {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(externalEntries, "externalEntries must not be null");
            Objects.requireNonNull(callbackInvokes, "callbackInvokes must not be null");
            externalEntries = Map.copyOf(externalEntries);
            callbackInvokes = Map.copyOf(callbackInvokes);
        }
    }

    public record ClassDeclarationCoreResult(LoweringResult lowering,
                                             deal.semantic.ir.ClassFactoryRegistry registry) {

        public ClassDeclarationCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(registry, "registry must not be null");
        }
    }

    /**
     * One frame of the lowering environment (D1's loop-binding
     * load-resolution rule): the enclosing for-of's loop-binding name,
     * its producer-allocated {@link BindingId}, and the payload's initial
     * generation. A {@code BINDING_LOAD} of the frame's name carries the
     * frame's generation; the for-of arm pushes exactly one frame while
     * lowering the loop body.
     */
    public record ForEachFrame(String name, BindingId binding, long generation) {

        public ForEachFrame {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must be >= 0, got " + generation);
            }
        }
    }

    /**
     * One open catch scope: the catch variable's name and binding, plus
     * the number of enclosing binding frames at the moment the catch
     * block opened. The count distinguishes a declaration nested inside
     * the catch body (a frame pushed after the catch opened — a genuine
     * shadowing declaration) from an enclosing declaration the catch
     * variable itself shadows (a frame that was already open).
     */
    public record CatchFrame(String name, BindingId binding,
                             int outerFrameCount) {

        public CatchFrame {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
        }
    }

    public static LoweringResult lowerModule(CheckedModuleInput module,
                                             SemanticProfile profile,
                                             Map<ConstructKind, List<SemanticOpKind>>
                                                 constructCoverage,
                                             String interfaceHash,
                                             String capabilityRegistryHash,
                                             SemanticIdAllocator allocator) {
        requireLoweringInputs(module, profile, constructCoverage, interfaceHash,
            capabilityRegistryHash, allocator);
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator);
        try {
            lowerer.lowerStatements(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered)));
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect)));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash);
        // E5 production-time checks (A-D1/C-D2): the common-admission
        // head, the address-chain protocol, and the block-membership
        // table's control-flow validator, in order; the first violation is
        // the returned E6005.
        return finishLowering(lowerer, unit, profile, interfaceHash, capabilityRegistryHash);
    }

    public static BindingCoreResult lowerModuleBindingCore(CheckedModuleInput module,
                                                           SemanticProfile profile,
                                                           Map<ConstructKind,
                                                               List<SemanticOpKind>>
                                                               constructCoverage,
                                                           String interfaceHash,
                                                           String capabilityRegistryHash,
                                                           SemanticIdAllocator allocator) {
        requireLoweringInputs(module, profile, constructCoverage, interfaceHash,
            capabilityRegistryHash, allocator);
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, module.ast().span());
        try {
            lowerer.lowerBindingModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<LoweringResult> gates = validateUnitGates(unit, profile, interfaceHash,
            capabilityRegistryHash);
        if (gates.isPresent()) {
            return new BindingCoreResult(gates.get(), lowerer.bindingFacts());
        }
        return new BindingCoreResult(new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts());
    }

    public static LoweringResult lowerModuleFullProgram(CheckedModuleInput module,
                                                        SemanticProfile profile,
                                                        Map<ConstructKind,
                                                            List<SemanticOpKind>>
                                                            constructCoverage,
                                                        String interfaceHash,
                                                        String capabilityRegistryHash,
                                                        SemanticIdAllocator allocator) {
        requireLoweringInputs(module, profile, constructCoverage, interfaceHash,
            capabilityRegistryHash, allocator);
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, module.ast().span(), true);
        lowerer.setModuleImports(module.imports());
        try {
            lowerer.hoistModuleLevelAllocs(module.ast().statements());
            lowerer.statementWalk.walk(module.ast().statements(), true);
            lowerer.emitEntryMainCall();

            lowerer.finalizeInvocationIdentities();
            lowerer.finalizeCellKinds();
        } catch (ConstructUnlowered unlowered) {
            return new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered)));
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect)));
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect)));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        return finishLowering(lowerer, unit, profile, interfaceHash, capabilityRegistryHash);
    }

    public static FullProgramE7Result lowerModuleFullProgramE7(CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash, String capabilityRegistryHash,
            SemanticIdAllocator allocator,
            Map<ModuleId, Map<String, OpId>> calleeExternalEntries,
            Set<String> callbackExports) {
        requireLoweringInputs(module, profile, constructCoverage, interfaceHash,
            capabilityRegistryHash, allocator);
        Objects.requireNonNull(calleeExternalEntries, "calleeExternalEntries must not be null");
        Objects.requireNonNull(callbackExports, "callbackExports must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, module.ast().span(), true);
        lowerer.setModuleImports(module.imports());
        lowerer.setE7Facts(module.exports(), calleeExternalEntries,
            callbackExports);
        try {
            lowerer.hoistModuleLevelAllocs(module.ast().statements());
            lowerer.statementWalk.walk(module.ast().statements(), true);
            lowerer.emitE7Terminals();

            lowerer.finalizeInvocationIdentities();
            lowerer.finalizeCellKinds();
        } catch (ConstructUnlowered unlowered) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                Map.of(), Map.of());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                Map.of(), Map.of());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                Map.of(), Map.of());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        return new FullProgramE7Result(
            finishLowering(lowerer, unit, profile, interfaceHash, capabilityRegistryHash),
            lowerer.recordedEntries(), lowerer.recordedCallbacks());
    }

    public static final String DECLARATION_SURFACE_INCOMPLETE =
        "DECLARATION_SURFACE_INCOMPLETE";

    public static final String DECLARATION_KIND_MISMATCH =
        "DECLARATION_KIND_MISMATCH";

    public static final String PROJECT_INPUT_INCOMPLETE =
        "PROJECT_INPUT_INCOMPLETE";

    /**
     * The composed production admission's supplied-view disagreement: a
     * companion view handed to
     * {@link #validateProjectUnit(LoweredModuleUnit, StructuredBodyTable,
     * SemanticIrValidator.ComparisonFacts, ClassBindingFacts,
     * deal.semantic.ir.ClassFactoryRegistry,
     * deal.semantic.ir.ExternalModuleInterface, Map, Map)} differs from the
     * unit's admitted record. The E6005 is fail-closed: the owner chain
     * never consumes a competing authority beside the admitted content.
     */
    public static final String PROJECT_VIEW_MISMATCH = "PROJECT_VIEW_MISMATCH";

    public static ProjectLoweringResult lowerProject(
            CompilerInvocation invocation,
            CheckedProjectInput checkedProject,
            deal.semantic.ir.ProjectInterfaceIndex interfaceIndex,
            List<SemanticRequirementManifest> requirementManifests,
            HostDeclarationSurface declarationSurface,
            Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities,
            BuiltinErrorDeclaration builtinError,
            Set<String> callbackExports) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(checkedProject, "checkedProject must not be null");
        Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
        Objects.requireNonNull(requirementManifests,
            "requirementManifests must not be null");
        Objects.requireNonNull(declarationSurface, "declarationSurface must not be null");
        Objects.requireNonNull(declarationModuleIdentities,
            "declarationModuleIdentities must not be null");
        Objects.requireNonNull(builtinError, "builtinError must not be null");
        Objects.requireNonNull(callbackExports, "callbackExports must not be null");
        String interfaceHash = interfaceIndex.interfaceIndexDigest();
        String capabilityRegistryHash = invocation.capabilityRegistryHash();
        SemanticIrValidator.ComparisonFacts comparisonFacts =
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                invocation.semanticProfile(), capabilityRegistryHash);

        // (2) The declaration-fact agreement: every declaration import of
        // the closure is covered by the surface (a declaration module's
        // class literal would otherwise defer silently).
        for (CheckedModuleInput module : checkedProject.modules()) {
            for (ResolvedImport importFact : module.imports()) {
                deal.semantic.ir.ExternalModuleInterface target =
                    interfaceIndex.modules().get(importFact.resolvedModuleId());
                if (target != null
                        && target.kind() == ExternalModuleKind.HOST
                        && !declarationSurface.modules()
                            .containsKey(importFact.resolvedModuleId())) {
                    return projectFailure(importFact.resolvedModuleId(),
                        SemanticCapability.FOUNDATION_VALUES,
                        DECLARATION_SURFACE_INCOMPLETE,
                        invocation.semanticProfile(),
                        "declaration import '" + importFact.modulePath()
                            + "' of module '" + module.moduleId().path()
                            + "' has no declaration-surface entry (the surface"
                            + " covers every declaration import of the closure)");
                }
            }
        }


        // (2) The class registration seeds, produced before the first unit
        // walk: one layout per declared class of every declaration module
        // plus the compiler-owned builtin Error entry.
        ClassRegistrationSeeds.Production seeds = ClassRegistrationSeeds.produce(
            declarationSurface, declarationModuleIdentities,
            builtinError);
        if (seeds.hasErrors()) {
            return projectFailureResult(seeds.diagnostics());
        }

        // (3) The namespace registrations: created before the first unit
        // walk in dependency order (then first-import declaration order) and
        // completed by each unit's import arm.
        Map<ModuleId, ModuleImportKind> preRegistered = new LinkedHashMap<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            for (ResolvedImport importFact : module.imports()) {
                preRegistered.putIfAbsent(importFact.resolvedModuleId(),
                    ModuleLowerer.moduleImportKindOf(importFact));
            }
        }
        NamespaceRegistrations.Recorder namespaceRecorder =
            new NamespaceRegistrations.Recorder(preRegistered);

        // (4) The one closure walk in dependency order: one allocator, one
        // session per module with every arm active, and the composed
        // per-unit closed chain.
        List<ModuleId> closureOrder = new ArrayList<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            closureOrder.add(module.moduleId());
        }
        if (closureOrder.isEmpty()) {
            return projectFailure(checkedProject.entryModule(),
                SemanticCapability.FOUNDATION_VALUES, PROJECT_INPUT_INCOMPLETE,
                invocation.semanticProfile(),
                "the checked project carries no implementation module (the closure"
                    + " is the complete implementation closure of the compile)");
        }
        if (!closureOrder.contains(checkedProject.entryModule())) {
            return projectFailure(checkedProject.entryModule(),
                SemanticCapability.FOUNDATION_VALUES, PROJECT_INPUT_INCOMPLETE,
                invocation.semanticProfile(),
                "the entry module '" + checkedProject.entryModule().path()
                    + "' is not part of the dependency-ordered closure "
                    + closureOrder + " (the project admits only the complete"
                    + " closure with its entry module)");
        }
        SemanticIdAllocator allocator = SemanticIdAllocator.over(closureOrder);

        Map<ModuleId, LoweredModuleUnit> units = new LinkedHashMap<>();
        Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
        Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry> registries =
            new LinkedHashMap<>();
        Map<ModuleId, Map<String, OpId>> calleeExternalEntries = new LinkedHashMap<>();

        Map<ClassId, SharedFactoryFacts> inProjectFactoryFacts =
            new LinkedHashMap<>();
        // The provider-keyed view of the same accumulated facts (the
        // declared class occurrence's construction entry to its facts): a
        // canonical ClassId can carry two declarations only in a
        // harness-synthesized carrier, so the recorded construction entry
        // — never the identity-keyed first match — selects the exact
        // declaring provider of a construction.
        Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
            inProjectProviderFacts = new LinkedHashMap<>();
        Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>>
            providerConstructionEntries = constructionEntriesOf(interfaceIndex);

        // (4b) The declared-parameter-annotation index of the one lowering
        // (canonical failure-projection authority P3): built once from the
        // checked project's declarations before the module walk, so every
        // declaration-owned parameter boundary carries the callee's declared
        // annotation span rather than the call site.
        Map<ModuleId, DeclaredParameterAnnotations> declaredParameterAnnotations =
            declaredParameterAnnotationIndex(checkedProject);
        // The project-wide same-identity sibling declarations: a field or
        // JSON link of such an identity needs its recorded exact declaring
        // provider and fails closed without one (never a first alias).
        java.util.Set<ClassId> projectAmbiguousClassIds =
            ambiguousProjectClassIds(checkedProject);
        // The plan-bearing (jsonable) declarations of the same identity:
        // only a genuine two-plan collision makes the generated JSON plan
        // key ambiguous; a single plan-bearing declaration keeps every
        // nested link exact. The plan owner resolves the declaration the
        // generated plan table carries under the canonical key (the
        // unique plan-bearing declaration when one exists, the emission
        // order's last declaration otherwise).
        JsonPlanFacts jsonPlanFacts = jsonPlanFacts(checkedProject, interfaceIndex);

        for (CheckedModuleInput module : checkedProject.modules()) {
            SemanticRequirementManifest manifest =
                manifestOf(requirementManifests, module.moduleId());
            if (manifest == null) {
                return projectFailure(module.moduleId(),
                    SemanticCapability.FOUNDATION_VALUES, PROJECT_INPUT_INCOMPLETE,
                    invocation.semanticProfile(),
                    "module '" + module.moduleId().path() + "' has no requirement"
                        + " manifest (every closure module carries exactly one"
                        + " manifest's construct-coverage rows)");
            }
            deal.semantic.ir.ExternalModuleInterface ownInterface =
                interfaceIndex.modules().get(module.moduleId());
            if (ownInterface == null) {
                return projectFailure(module.moduleId(),
                    SemanticCapability.FOUNDATION_VALUES, PROJECT_INPUT_INCOMPLETE,
                    invocation.semanticProfile(),
                    "module '" + module.moduleId().path() + "' has no interface-index"
                        + " entry (the index covers every module of the closure)");
            }
            LoweredProjectModule lowered = lowerProjectModule(module, manifest,
                invocation.semanticProfile(), interfaceHash, capabilityRegistryHash, allocator,
                calleeExternalEntries, callbackExports, seeds.seeds(),
                ownInterface, comparisonFacts,
                inProjectFactoryFacts, inProjectProviderFacts,
                providerConstructionEntries, declaredParameterAnnotations,
                projectAmbiguousClassIds, jsonPlanFacts);
            if (lowered.hasErrors()) {
                return projectFailureResult(lowered.diagnostics());
            }
            Optional<CompilerDiagnostic> namespaceFailure =
                namespaceRecorder.record(lowered.unit());
            if (namespaceFailure.isPresent()) {
                return projectFailureResult(List.of(namespaceFailure.get()));
            }
            units.put(module.moduleId(), lowered.unit());
            tables.put(module.moduleId(), lowered.unit().bodyTable());
            registries.put(module.moduleId(), lowered.unit().classFactories());
            if (!lowered.externalEntries().isEmpty()) {
                calleeExternalEntries.put(module.moduleId(), lowered.externalEntries());
            }
            accumulateInProjectFactoryFacts(ownInterface, lowered,
                inProjectFactoryFacts, inProjectProviderFacts);
        }

        // (5) The project-form gate over the complete closure: the
        // common-admission head of every module plus the project class
        // facts, without re-running the per-module owners whose declared
        // slots (address chain, control flow, bindings, class
        // construction) the module walk already composed in order. The
        // project owns the produced class registration seeds; the lowering
        // result's seed view is exactly that record.
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            invocation.semanticProfile(), interfaceIndex, units,
            checkedProject.entryModule(), seeds.seeds());
        Optional<CompilerDiagnostic> projectGate = SemanticIrValidator.validateProjectHead(
            project, comparisonFacts);
        if (projectGate.isPresent()) {
            return projectFailureResult(List.of(projectGate.get()));
        }
        NamespaceRegistrations.Assembly namespaceAssembly = namespaceRecorder.build();
        return new ProjectLoweringResult(project, tables, registries,
            project.classSeeds(), namespaceAssembly.registrations(), List.of());
    }

    /**
     * The canonical class identities declared by more than one module of
     * the checked project (a harness-synthesized pair; a resolved source
     * file is its own identity): a field operation or generated JSON link
     * of such an identity must select the exact declaring declaration
     * through its recorded provider, never a first alias, a unit-local mask
     * or an identity-keyed first match — an unrecorded such link fails
     * closed.
     */
    private static java.util.Set<ClassId> ambiguousProjectClassIds(
            CheckedProjectInput checkedProject) {
        Map<ClassId, Integer> counts = new LinkedHashMap<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            SymbolTable table = module.checks() == null
                ? null : module.checks().symbolTable();
            if (table == null) {
                continue;
            }
            for (Symbol symbol : table.symbols().values()) {
                if (!(symbol instanceof Symbol.ClassSymbol classSymbol)
                        || classSymbol.identity() == null) {
                    continue;
                }
                ClassId classId = new ClassId(
                    DescriptorService.semanticModulePath(classSymbol.identity()),
                    classSymbol.name());
                if (classId.equals(ClassId.ERROR)) {
                    // The compiler-owned builtin class: one declaration.
                    continue;
                }
                counts.merge(classId, 1, Integer::sum);
            }
        }
        java.util.Set<ClassId> ambiguous = new java.util.LinkedHashSet<>();
        for (Map.Entry<ClassId, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                ambiguous.add(entry.getKey());
            }
        }
        return java.util.Collections.unmodifiableSet(ambiguous);
    }

    /**
     * The generated-JSON-plan facts of the project's class identities
     * (the canonical plan key is the class identity):
     *
     * <ul>
     *   <li>{@code ambiguous} — the identities carrying more than one
     *   plan-bearing ({@code @jsonable}) declaration: the generated plan
     *   table can carry only one declaration's plan under the key, so such
     *   a project fails closed (the generated helpers themselves are
     *   ambiguous, not only the nested links);</li>
     *   <li>{@code owners} — for every identity carrying more than one
     *   declaration, the declaration whose layout and defaults the
     *   generated plan table carries: the unique plan-bearing declaration
     *   when exactly one exists, the emission order's last declaration
     *   otherwise;</li>
     *   <li>{@code ownerEntries} — the owner's construction entry when the
     *   owner is exported, so the nested-link admission and the per-module
     *   validation resolve the owner's exact shared-factory facts.</li>
     * </ul>
     *
     * <p>A declaration file's {@code @jsonable} directive has no effect
     * (its classes generate no plan), so only implementation modules
     * contribute; the checked project's module list covers exactly those,
     * in dependency order — the order the emitters merge the project's
     * class layouts in.</p>
     */
    private record JsonPlanFacts(java.util.Set<ClassId> ambiguous,
                                 Map<ClassId, ModuleId> owners,
                                 Map<ClassId, deal.semantic.ir.ClassFactoryId>
                                     ownerEntries) {}

    private static JsonPlanFacts jsonPlanFacts(CheckedProjectInput checkedProject,
            deal.semantic.ir.ProjectInterfaceIndex interfaceIndex) {
        Map<ClassId, java.util.List<ModuleId>> declarations = new LinkedHashMap<>();
        Map<ClassId, java.util.List<ModuleId>> planBearers = new LinkedHashMap<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            SymbolTable table = module.checks() == null
                ? null : module.checks().symbolTable();
            if (table == null) {
                continue;
            }
            for (StatementNode statement : module.ast().statements()) {
                StatementNode declaration = statement instanceof ExportDeclaration export
                    ? export.declaration() : statement;
                if (!(declaration instanceof ClassDeclaration classDeclaration)) {
                    continue;
                }
                Symbol symbol = table.resolve(classDeclaration.name());
                if (!(symbol instanceof Symbol.ClassSymbol classSymbol)
                        || classSymbol.identity() == null) {
                    continue;
                }
                ClassId classId = new ClassId(
                    DescriptorService.semanticModulePath(classSymbol.identity()),
                    classSymbol.name());
                if (classId.equals(ClassId.ERROR)) {
                    continue;
                }
                declarations.computeIfAbsent(classId,
                    key -> new java.util.ArrayList<>()).add(module.moduleId());
                if (classDeclaration.isJsonable()) {
                    planBearers.computeIfAbsent(classId,
                        key -> new java.util.ArrayList<>()).add(module.moduleId());
                }
            }
        }
        java.util.Set<ClassId> ambiguous = new java.util.LinkedHashSet<>();
        Map<ClassId, ModuleId> owners = new LinkedHashMap<>();
        for (Map.Entry<ClassId, java.util.List<ModuleId>> entry
                : declarations.entrySet()) {
            java.util.List<ModuleId> bearers = planBearers.get(entry.getKey());
            int bearerCount = bearers == null ? 0 : bearers.size();
            if (bearerCount > 1) {
                ambiguous.add(entry.getKey());
            }
            if (entry.getValue().size() > 1) {
                owners.put(entry.getKey(), bearerCount == 1
                    ? bearers.get(0)
                    : entry.getValue().get(entry.getValue().size() - 1));
            }
        }
        Map<ClassId, deal.semantic.ir.ClassFactoryId> ownerEntries =
            new LinkedHashMap<>();
        Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>> moduleEntries =
            constructionEntriesOf(interfaceIndex);
        for (Map.Entry<ClassId, ModuleId> owner : owners.entrySet()) {
            Map<ClassId, deal.semantic.ir.ClassFactoryId> byClass =
                moduleEntries.get(owner.getValue());
            if (byClass == null) {
                continue;
            }
            deal.semantic.ir.ClassFactoryId entry =
                byClass.get(owner.getKey());
            if (entry != null) {
                ownerEntries.put(owner.getKey(), entry);
            }
        }
        return new JsonPlanFacts(
            java.util.Collections.unmodifiableSet(ambiguous),
            java.util.Collections.unmodifiableMap(owners),
            java.util.Collections.unmodifiableMap(ownerEntries));
    }

    /**
     * The validator-facing plan-owner facts of the project's
     * multiple-declaration class identities (the owner declaration and its
     * construction entry when exported).
     */
    private static Map<ClassId, ClassConstructionValidator.ClassPlanOwner> jsonPlanOwners(
            JsonPlanFacts facts) {
        Map<ClassId, ClassConstructionValidator.ClassPlanOwner> owners =
            new LinkedHashMap<>();
        for (Map.Entry<ClassId, ModuleId> entry : facts.owners().entrySet()) {
            owners.put(entry.getKey(), new ClassConstructionValidator.ClassPlanOwner(
                entry.getValue(), facts.ownerEntries().get(entry.getKey())));
        }
        return java.util.Collections.unmodifiableMap(owners);
    }

    /**
     * The declared-parameter-annotation index of the one lowering
     * (canonical failure-projection authority P3): {@code module → declared
     * function name → declared parameter type-annotation spans}, built from
     * the checked project's declarations — never from a call site. The
     * declaration-owned parameter boundaries read it so every consumer
     * renders the callee's declared annotation span, in the callee's file.
     */
    private static Map<ModuleId, DeclaredParameterAnnotations> declaredParameterAnnotationIndex(
            CheckedProjectInput checkedProject) {
        Map<ModuleId, DeclaredParameterAnnotations> index = new LinkedHashMap<>();
        for (CheckedModuleInput module : checkedProject.modules()) {
            Map<String, List<List<Span>>> byName = new LinkedHashMap<>();
            for (StatementNode statement : module.ast().statements()) {
                StatementNode declaration = statement instanceof ExportDeclaration export
                    ? export.declaration() : statement;
                if (declaration instanceof FunctionDeclaration function) {
                    // The declared parameter type-annotation spans in
                    // declaration order; a parameter without a type
                    // annotation records a null entry and fails the
                    // declaration-owned lookup closed (never a call-site
                    // fallback).
                    byName.computeIfAbsent(function.name(), ignored -> new ArrayList<>())
                        .add(ModuleLowerer.parameterTypeSpans(function.params()));
                }
                if (declaration instanceof ClassDeclaration classDeclaration
                        && classDeclaration.isJsonable()) {
                    // The generated @jsonable helper declarations (R3):
                    // a cross-module helper call's declared-parameter
                    // cell is declaration-owned like any declared
                    // callee's. The helper has exactly one generated
                    // parameter, and its declaration site is the class
                    // declaration's own span, so the index records that
                    // span — never the invoking call site.
                    for (String helper : List.of(classDeclaration.name() + "$fromJson",
                            classDeclaration.name() + "$toJson")) {
                        byName.computeIfAbsent(helper, ignored -> new ArrayList<>())
                            .add(List.of(classDeclaration.span()));
                    }
                }
            }
            index.put(module.moduleId(),
                new DeclaredParameterAnnotations(module.sourceId(), byName));
        }
        return index;
    }

    /**
     * One module's declared-parameter index (P3): the module's stable
     * source id (the origin file of the declaration-owned parameter cells)
     * and its declared functions' parameter type-annotation spans by
     * declared function name.
     */
    private record DeclaredParameterAnnotations(String sourceId,
            Map<String, List<List<Span>>> byFunctionName) {
    }

    /** One declaration-owned parameter cell origin (P3): file and span. */
    private record DeclaredParameterOrigin(String sourceId, Span span) {
    }

    private static LoweredProjectModule lowerProjectModule(
            CheckedModuleInput module,
            SemanticRequirementManifest manifest,
            SemanticProfile profile,
            String interfaceHash,
            String capabilityRegistryHash,
            SemanticIdAllocator allocator,
            Map<ModuleId, Map<String, OpId>> calleeExternalEntries,
            Set<String> callbackExports,
            ClassRegistrationSeeds seeds,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            SemanticIrValidator.ComparisonFacts comparisonFacts,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
                providerFactoryFacts,
            Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>>
                providerConstructionEntries,
            Map<ModuleId, DeclaredParameterAnnotations> declaredParameterAnnotations,
            java.util.Set<ClassId> projectAmbiguousClassIds,
            JsonPlanFacts jsonPlanFacts) {
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, false, false, false,
            module.ast().span(), true, true, ownInterface,
            Map.copyOf(sharedFactories));
        lowerer.setDeclaredParameterAnnotations(declaredParameterAnnotations);
        lowerer.setModuleImports(module.imports());
        lowerer.setRegistrationSeeds(seeds);
        lowerer.setProviderFactoryFacts(Map.copyOf(providerFactoryFacts),
            providerConstructionEntries, module.classLiteralProviders());
        lowerer.setProjectAmbiguousClassIds(projectAmbiguousClassIds);
        lowerer.setAmbiguousJsonPlanClassIds(jsonPlanFacts.ambiguous());
        lowerer.setJsonPlanOwners(jsonPlanFacts.owners(),
            jsonPlanFacts.ownerEntries());
        lowerer.setE7Facts(module.exports(), calleeExternalEntries,
            callbackExports);
        try {
            lowerer.lowerProjectModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return LoweredProjectModule.failure(List.of(
                loweringFailure(module.moduleId(), unlowered)));
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return LoweredProjectModule.failure(List.of(
                loweringFailure(module.moduleId(), defect)));
        } catch (ComparisonSelectorLowering.Defect defect) {
            return LoweredProjectModule.failure(List.of(
                ComparisonSelectorLowering.e6005(module.moduleId(), defect)));
        } catch (RetainedAbiDeferred deferred) {
            return LoweredProjectModule.failure(List.of(
                loweringFailure(module.moduleId(), deferred)));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(manifest.constructCoverage(),
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E9_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> unitChain = validateProjectUnit(unit,
            unit.bodyTable(), comparisonFacts, unit.classBindingFacts(),
            unit.classFactories(), ownInterface,
            Map.copyOf(sharedFactories), seeds.registrations(),
            Map.copyOf(providerFactoryFacts),
            jsonPlanFacts.ambiguous(), jsonPlanOwners(jsonPlanFacts));
        if (unitChain.isPresent()) {
            return LoweredProjectModule.failure(List.of(unitChain.get()));
        }
        return new LoweredProjectModule(unit, lowerer.recordedEntries(), List.of());
    }

    private static void accumulateInProjectFactoryFacts(
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            LoweredProjectModule lowered,
            Map<ClassId, SharedFactoryFacts> accumulated,
            Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
                providerAccumulated) {
        for (deal.semantic.ir.ClassInterface classEntry : ownInterface.classes()) {
            deal.semantic.ir.ClassLayout layout =
                lowered.unit().classLayouts().get(classEntry.classId());
            if (layout == null) {
                continue;
            }
            OpId factoryOpId = lowered.unit().classFactories()
                .factoryFor(classEntry.constructionEntry());
            if (factoryOpId == null) {
                continue;
            }
            SemanticOp factoryOp = opOf(lowered.unit(), factoryOpId);
            if (factoryOp == null || !(factoryOp.result() instanceof ValueId result)) {
                continue;
            }
            SharedFactoryFacts facts = new SharedFactoryFacts(classEntry.classId(),
                classEntry, layout, factoryOpId, result);
            accumulated.put(classEntry.classId(), facts);
            // The exact declaring occurrence's facts: the construction
            // entry is unique per declared class occurrence, so two
            // declarations of one identity never overwrite each other here.
            providerAccumulated.put(classEntry.constructionEntry(), facts);
        }
    }

    /**
     * The declared-class construction entries of the project interface
     * index, keyed by declaring module then canonical class identity: the
     * exact occurrence a recorded provider alias selects. Two declarations
     * of one canonical class identity (a harness-synthesized pair) hold
     * distinct entries.
     */
    private static Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>>
            constructionEntriesOf(deal.semantic.ir.ProjectInterfaceIndex index) {
        Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>> entries =
            new LinkedHashMap<>();
        for (Map.Entry<ModuleId, deal.semantic.ir.ExternalModuleInterface> module
                : index.modules().entrySet()) {
            Map<ClassId, deal.semantic.ir.ClassFactoryId> byClass =
                new LinkedHashMap<>();
            for (deal.semantic.ir.ClassInterface classEntry
                    : module.getValue().classes()) {
                byClass.put(classEntry.classId(), classEntry.constructionEntry());
            }
            entries.put(module.getKey(), byClass);
        }
        return entries;
    }

    /** The unit's op named by the id, or {@code null}. */
    private static SemanticOp opOf(LoweredModuleUnit unit, OpId opId) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(opId)) {
                return op;
            }
        }
        return null;
    }

    public static Optional<CompilerDiagnostic> validateProjectUnit(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            SemanticIrValidator.ComparisonFacts facts,
            ClassBindingFacts bindingFacts,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories) {
        return validateProjectUnit(unit, table, facts, bindingFacts, factories,
            ownInterface, sharedFactories, Map.of());
    }

    /**
     * The composed per-module production admission, in the pinned order:
     * the common-admission head ({@link SemanticIrValidator#validateUnitHead}),
     * {@link AddressChainProtocol}, the control-flow owner over the produced
     * block table, the bindings-production owner and the class-construction
     * owner. Each owner runs exactly once; the returned diagnostic is the
     * first failure of that order.
     *
     * <p>The supplied companion views are compared with the unit's own
     * records before the owners run: a supplied {@code table},
     * {@code bindingFacts} or {@code factories} that
     * differs from the admitted content is a fail-closed E6005 (a
     * content-equal detached view is accepted and the unit's owned record
     * is consumed instead, so no second authority ever describes a
     * different graph).</p>
     */
    public static Optional<CompilerDiagnostic> validateProjectUnit(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            SemanticIrValidator.ComparisonFacts facts,
            ClassBindingFacts bindingFacts,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses) {
        return validateProjectUnit(unit, table, facts, bindingFacts, factories,
            ownInterface, sharedFactories, declarationClasses,
            Map.of());
    }

    /**
     * The composed per-module production admission over the supplied
     * provider-keyed shared-factory facts: the recorded construction
     * entry of a {@code CLASS_NEW(SHARED_FACTORY)} selects the exact
     * declaring provider when one canonical class identity carries more
     * than one declaration (the harness-synthesized pair).
     */
    public static Optional<CompilerDiagnostic> validateProjectUnit(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            SemanticIrValidator.ComparisonFacts facts,
            ClassBindingFacts bindingFacts,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
            Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
                providerFactoryFacts) {
        return validateProjectUnit(unit, table, facts, bindingFacts, factories,
            ownInterface, sharedFactories, declarationClasses,
            providerFactoryFacts, java.util.Set.of());
    }

    /**
     * The composed per-module production admission over the supplied
     * provider-keyed shared-factory facts and the project-wide set of
     * class identities carrying more than one plan-bearing
     * ({@code @jsonable}) declaration: only such an identity's generated
     * JSON plan key is ambiguous, so only its nested JSON links fail
     * closed.
     */
    public static Optional<CompilerDiagnostic> validateProjectUnit(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            SemanticIrValidator.ComparisonFacts facts,
            ClassBindingFacts bindingFacts,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
            Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
                providerFactoryFacts,
            java.util.Set<ClassId> ambiguousJsonPlanClassIds) {
        return validateProjectUnit(unit, table, facts, bindingFacts, factories,
            ownInterface, sharedFactories, declarationClasses,
            providerFactoryFacts, ambiguousJsonPlanClassIds, Map.of());
    }

    /**
     * The composed per-module production admission over the supplied
     * provider-keyed shared-factory facts, the project-wide ambiguous
     * plan-key identities and the recorded plan owners of the
     * multiple-declaration class identities: every nested JSON link of an
     * identity the generated plan table carries under the canonical key
     * validates the owner declaration's layout — never the unit-local
     * sibling an identity-keyed lookup would mask it with.
     */
    public static Optional<CompilerDiagnostic> validateProjectUnit(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            SemanticIrValidator.ComparisonFacts facts,
            ClassBindingFacts bindingFacts,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses,
            Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
                providerFactoryFacts,
            java.util.Set<ClassId> ambiguousJsonPlanClassIds,
            Map<ClassId, ClassConstructionValidator.ClassPlanOwner> jsonPlanOwners) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(facts, "facts must not be null");
        Objects.requireNonNull(bindingFacts, "bindingFacts must not be null");
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(ownInterface, "ownInterface must not be null");
        Objects.requireNonNull(sharedFactories, "sharedFactories must not be null");
        Objects.requireNonNull(declarationClasses,
            "declarationClasses must not be null");
        Objects.requireNonNull(providerFactoryFacts,
            "providerFactoryFacts must not be null");
        Objects.requireNonNull(ambiguousJsonPlanClassIds,
            "ambiguousJsonPlanClassIds must not be null");
        Objects.requireNonNull(jsonPlanOwners, "jsonPlanOwners must not be null");
        Optional<CompilerDiagnostic> viewMismatch = checkSuppliedViews(unit, table,
            bindingFacts, factories, facts);
        if (viewMismatch.isPresent()) {
            return viewMismatch;
        }
        StructuredBodyTable ownedTable = unit.bodyTable();
        ClassBindingFacts ownedBindingFacts = unit.classBindingFacts();
        deal.semantic.ir.ClassFactoryRegistry ownedFactories = unit.classFactories();
        Optional<CompilerDiagnostic> validation =
            SemanticIrValidator.validateUnitHead(unit, facts);
        if (validation.isPresent()) {
            return validation;
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return chainShape;
        }
        Optional<CompilerDiagnostic> controlFlow =
            ControlFlowValidator.validate(unit, ownedTable);
        if (controlFlow.isPresent()) {
            return controlFlow;
        }
        Optional<CompilerDiagnostic> bindings = BindingsProductionValidator.validate(
            unit, ownedTable, ownedBindingFacts);
        if (bindings.isPresent()) {
            return bindings;
        }
        return ClassConstructionValidator.validate(unit, ownedTable, ownedFactories,
            ownInterface, sharedFactories, declarationClasses,
            providerFactoryFacts, ambiguousJsonPlanClassIds, jsonPlanOwners);
    }

    /**
     * The supplied-view agreement of the composed production admission:
     * each supplied companion must equal the unit's own admitted record.
     * A differing view is the fail-closed {@code PROJECT_VIEW_MISMATCH}
     * E6005 of the lowering entry — never a silently consumed competing
     * authority — and a content-equal detached view is replaced by the
     * owned record.
     */
    private static Optional<CompilerDiagnostic> checkSuppliedViews(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            ClassBindingFacts bindingFacts,
            deal.semantic.ir.ClassFactoryRegistry factories,
            SemanticIrValidator.ComparisonFacts facts) {
        String view = !unit.bodyTable().equals(table) ? "body table"
            : !unit.classBindingFacts().equals(bindingFacts) ? "binding facts"
            : !unit.classFactories().equals(factories) ? "class-factory registry"
            : null;
        if (view == null) {
            return Optional.empty();
        }
        return Optional.of(FailureContractRegistry.e6005(new LoweringFailureDetail(
            unit.moduleId().path(), SemanticCapability.FOUNDATION_VALUES,
            PROJECT_VIEW_MISMATCH, facts.semanticProfile(),
            LoweredModuleUnit.FORMAT_VERSION,
            "SemanticLowerer.validateProjectUnit " + PROJECT_VIEW_MISMATCH
                + " (the supplied " + view + " view of module '"
                + unit.moduleId().path() + "' differs from the unit's admitted"
                + " record: the complete admission consumes exactly the unit's"
                + " owned facts, never a competing authority)")));
    }

    /** The requirement manifest of one module, or {@code null} when absent. */
    private static SemanticRequirementManifest manifestOf(
            List<SemanticRequirementManifest> manifests, ModuleId moduleId) {
        for (SemanticRequirementManifest manifest : manifests) {
            if (manifest.moduleId().equals(moduleId)) {
                return manifest;
            }
        }
        return null;
    }

    /** The first E6005 of the project entry, with no project and no records. */
    private static ProjectLoweringResult projectFailure(ModuleId module,
            SemanticCapability capability, String rule, SemanticProfile profile,
            String origin) {
        return projectFailureResult(List.of(FailureContractRegistry.e6005(
            new LoweringFailureDetail(module.path(), capability, rule, profile,
                LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer.lowerProject " + rule + " (" + origin + ")"))));
    }

    /** The failure result carrying exactly the first E6005 and no records. */
    private static ProjectLoweringResult projectFailureResult(
            List<CompilerDiagnostic> diagnostics) {
        return new ProjectLoweringResult(null, Map.of(), Map.of(), null, null,
            List.copyOf(diagnostics));
    }

    /**
     * The result of the one project lowering: the executable project (the
     * owner of the class registration seeds), the session's namespace
     * registrations, and exactly the first E6005 on failure.
     *
     * <p>No second authority describes a different graph: {@link #tables()}
     * and {@link #registries()} are exactly the project modules' owned
     * {@code bodyTable}/{@code classFactories}, and {@link #seeds()} is
     * exactly the project's owned {@code classSeeds}. A supplied companion
     * view that disagrees with the project rejects before any successful
     * exposure; a content-equal detached view is replaced by the owned
     * record. A failed lowering carries no project, no tables, no
     * registries, no seeds and no namespace registrations.</p>
     */
    public record ProjectLoweringResult(
            ExecutableLoweredProject project,
            Map<ModuleId, StructuredBodyTable> tables,
            Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry> registries,
            ClassRegistrationSeeds seeds,
            NamespaceRegistrations namespaces,
            List<CompilerDiagnostic> diagnostics) {

        public ProjectLoweringResult {
            Objects.requireNonNull(tables, "tables must not be null");
            Objects.requireNonNull(registries, "registries must not be null");
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
            if (project != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "a successful project lowering carries no diagnostics");
            }
            if (project == null
                    && (diagnostics.isEmpty() || !tables.isEmpty()
                        || !registries.isEmpty() || seeds != null
                        || namespaces != null)) {
                throw new IllegalArgumentException(
                    "a failed project lowering carries exactly the first E6005 and"
                        + " no project, no tables, no registries, no seeds, and no"
                        + " namespace registrations");
            }
            if (project == null) {
                tables = Map.of();
                registries = Map.of();
            } else {
                // The successful companion views are derived from the
                // project's own records. A supplied view that disagrees with
                // the project rejects before successful exposure, and the
                // canonical owned instance is retained (never an independent
                // authority).
                Map<ModuleId, StructuredBodyTable> ownedTables =
                    ownedTables(project);
                Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry>
                    ownedRegistries = ownedRegistries(project);
                if (!ownedTables.equals(tables)) {
                    throw new IllegalArgumentException(
                        "the result tables are not the project modules' owned"
                            + " body tables: supplied modules " + tables.keySet()
                            + ", the project owns " + ownedTables.keySet());
                }
                if (!ownedRegistries.equals(registries)) {
                    throw new IllegalArgumentException(
                        "the result registries are not the project modules' owned"
                            + " class-factory registries: supplied modules "
                            + registries.keySet() + ", the project owns "
                            + ownedRegistries.keySet());
                }
                if (!Objects.equals(seeds, project.classSeeds())) {
                    throw new IllegalArgumentException(
                        "the result seeds are not the project's owned class"
                            + " registration seeds");
                }
                tables = ownedTables;
                registries = ownedRegistries;
                seeds = project.classSeeds();
            }
        }

        /** The project modules' owned body tables, keyed as the modules are. */
        private static Map<ModuleId, StructuredBodyTable> ownedTables(
                ExecutableLoweredProject project) {
            Map<ModuleId, StructuredBodyTable> owned = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                    : project.modules().entrySet()) {
                owned.put(entry.getKey(), entry.getValue().bodyTable());
            }
            return Collections.unmodifiableMap(owned);
        }

        /** The project modules' owned class-factory registries. */
        private static Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry>
                ownedRegistries(ExecutableLoweredProject project) {
            Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry> owned =
                new LinkedHashMap<>();
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                    : project.modules().entrySet()) {
                owned.put(entry.getKey(), entry.getValue().classFactories());
            }
            return Collections.unmodifiableMap(owned);
        }

        /** Whether the lowering failed (no project was produced). */
        public boolean hasErrors() {
            return !diagnostics.isEmpty();
        }

        /** The block-membership table of one module, or {@code null}. */
        public StructuredBodyTable tableOf(ModuleId moduleId) {
            return tables.get(Objects.requireNonNull(moduleId,
                "moduleId must not be null"));
        }

        /** The class-factory registry of one module, or {@code null}. */
        public deal.semantic.ir.ClassFactoryRegistry registryOf(ModuleId moduleId) {
            return registries.get(Objects.requireNonNull(moduleId,
                "moduleId must not be null"));
        }
    }

    /**
     * One lowered module of the project walk: its produced unit (the owner
     * of the structural companions) and the session's recorded external
     * entries, or exactly the first E6005 and no unit.
     */
    private record LoweredProjectModule(
            LoweredModuleUnit unit,
            Map<String, OpId> externalEntries,
            List<CompilerDiagnostic> diagnostics) {

        LoweredProjectModule {
            diagnostics = List.copyOf(diagnostics);
        }

        boolean hasErrors() {
            return !diagnostics.isEmpty();
        }

        static LoweredProjectModule failure(List<CompilerDiagnostic> diagnostics) {
            return new LoweredProjectModule(null, Map.of(), diagnostics);
        }
    }

    public static ClosureCoreResult lowerModuleClosureCore(CheckedModuleInput module,
                                                           SemanticProfile profile,
                                                           Map<ConstructKind,
                                                               List<SemanticOpKind>>
                                                               constructCoverage,
                                                           String interfaceHash,
                                                           String capabilityRegistryHash,
                                                           SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, module.ast().span());
        try {
            lowerer.lowerBindingModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                lowerer.bindingFacts(), lowerer.closureFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                lowerer.bindingFacts(), lowerer.closureFacts());
        }
        return new ClosureCoreResult(new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts(), lowerer.closureFacts());
    }

    public static GroupCoreResult lowerModuleGroupCore(CheckedModuleInput module,
                                                       SemanticProfile profile,
                                                       Map<ConstructKind,
                                                           List<SemanticOpKind>>
                                                           constructCoverage,
                                                       String interfaceHash,
                                                       String capabilityRegistryHash,
                                                       SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        }
        return new GroupCoreResult(new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
    }

    public static ImmutabilityCoreResult lowerModuleImmutabilityCore(
            CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash,
            String capabilityRegistryHash,
            SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        }
        return new ImmutabilityCoreResult(
            new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
            lowerer.proofFacts());
    }

    public static CreationRuleCoreResult lowerModuleCreationRuleCore(
            CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash,
            String capabilityRegistryHash,
            SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, true, module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        }
        return new CreationRuleCoreResult(
            new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
            lowerer.proofFacts(), lowerer.creationRuleFacts());
    }

    public static ShapeMapCoreResult lowerModuleShapeMapCore(
            CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash,
            String capabilityRegistryHash,
            SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, true, true,
            module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        return new ShapeMapCoreResult(
            new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
            lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
    }

    public static ValidationCoreResult lowerModuleValidationCore(
            CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash,
            String capabilityRegistryHash,
            SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, true, true,
            module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        Optional<CompilerDiagnostic> controlFlow =
            ControlFlowValidator.validate(unit, lowerer.bodyTable());
        if (controlFlow.isPresent()) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(controlFlow.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        Optional<CompilerDiagnostic> bindings =
            BindingsProductionValidator.validate(unit, lowerer.bodyTable(),
                lowerer.pinnedWriteFacts());
        if (bindings.isPresent()) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(bindings.get())),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        }
        return new ValidationCoreResult(
            new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
            lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
    }

    public static ClassDeclarationCoreResult lowerModuleClassCore(
            CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash,
            String capabilityRegistryHash,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            SemanticIdAllocator allocator) {
        return lowerModuleClassCore(module, profile, constructCoverage, interfaceHash,
            capabilityRegistryHash, ownInterface, Map.of(), allocator);
    }

    public static ClassDeclarationCoreResult lowerModuleClassCore(
            CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash,
            String capabilityRegistryHash,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(ownInterface, "ownInterface must not be null");
        Objects.requireNonNull(sharedFactories, "sharedFactories must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, false, false, false,
            module.ast().span(), false, true, ownInterface, sharedFactories);
        lowerer.setModuleImports(module.imports());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), unlowered))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }  catch (ContainerPayloadDescriptors.Defect defect) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), defect))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        } catch (RetainedAbiDeferred deferred) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(loweringFailure(module.moduleId(), deferred))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            profile, interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E9_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validateUnitHead(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                profile, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }
        Optional<CompilerDiagnostic> controlFlow =
            ControlFlowValidator.validate(unit, lowerer.bodyTable());
        if (controlFlow.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(controlFlow.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }
        Optional<CompilerDiagnostic> bindings =
            BindingsProductionValidator.validate(unit, lowerer.bodyTable(),
                lowerer.pinnedWriteFacts());
        if (bindings.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(bindings.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }

        Optional<CompilerDiagnostic> construction =
            ClassConstructionValidator.validate(unit, lowerer.bodyTable(),
                lowerer.factoryRegistry(), ownInterface, sharedFactories);
        if (construction.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(construction.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()));
        }
        return new ClassDeclarationCoreResult(
            new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.factoryRegistry());
    }

    // =========================================================================
    // The per-module lowering session (the arms)
    // =========================================================================

    /**
     * The per-module lowering session holding the D1 arms: one session
     * per module lowering, deterministic and stateless apart from its
     * pinned inputs. It consults the {@link CheckResult} (checked types
     * and the root symbol table) only as copied facts while lowering —
     * a produced unit carries no AST node, {@code SymbolTable},
     * {@code CheckResult}, or identity-keyed map (D4).
     *
     * <p>The session emits operations in the pinned order — element prior
     * steps in source order, then the consuming op, then its boundary
     * children in source order; the iterable's prior steps, then the
     * {@code FOR_EACH} op, then the body ops; a structure op, then its
     * child blocks in payload order ({@code LOOP}: init, body, update;
     * {@code BRANCH}: selected, alternate) — so {@link #ops()} is the
     * unit's produced-operation list in source order and the id
     * allocation sequence follows the same order. Every op is recorded
     * as a member of exactly the current emission block (C-D1); the
     * produced {@link StructuredBodyTable} is available through
     * {@link #bodyTable()}.</p>
     */
    public static final class ModuleLowerer {

        private final ModuleId module;
        private final String sourceId;
        private final CheckResult checks;
        private final SemanticIdAllocator ids;
        private final BlockId moduleInitBlock;
        private long nextOrdinal = 0;
        private final List<SemanticOp> ops = new ArrayList<>();
        private final List<ForEachFrame> frames = new ArrayList<>();
        /**
         * The catch-binding frames (C-D6): the innermost enclosing
         * {@code TRY_CATCH} catch variables. The try/catch arm pushes
         * exactly one frame while lowering the catch block (binding init
         * mechanics E6). The frame's binding is also the catch variable's
         * {@code BINDING_ALLOC} incarnation of the walk's binding
         * environment (its own scope frame), so a closure walk resolves —
         * and captures — the catch variable exactly like every other
         * declared binding; the E5 residual window (no binding
         * environment) reads this frame directly.
         */
        private final List<CatchFrame> catchFrames = new ArrayList<>();
        /**
         * The block-membership production state (C-D1): the ordered ops
         * per block and the inverse membership, plus the emission block
         * stack. Every emitted op becomes a member of exactly the top
         * block; child blocks are pushed while their contents are
         * lowered. The produced {@link StructuredBodyTable} is built at
         * {@link #bodyTable()}.
         */
        private final java.util.LinkedHashMap<BlockId, List<OpId>> blockOps =
            new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<OpId, BlockId> opBlocks =
            new java.util.LinkedHashMap<>();
        /**
         * The payload block → owning structure op inverse (C-D1..C-D6):
         * every block a structure op's payload references belongs to
         * exactly that op, recorded at the op's emission. The
         * address-chain production reads it to keep a value produced in a
         * selected block under its structure op (A-D2).
         */
        private final java.util.LinkedHashMap<BlockId, OpId> blockOwners =
            new java.util.LinkedHashMap<>();
        /**
         * The enclosing structure-op parents (C-D3..C-D6): the innermost
         * structure op whose block is currently being lowered. Every op
         * emitted inside a structure's child block records that
         * structure as its {@code parentOpId} — block ops record the
         * structure op, and nested structure ops record their enclosing
         * structure op.
         */
        private final ArrayDeque<OpId> blockParents = new ArrayDeque<>();
        /**
         * The innermost enclosing loop ops (C-D7): {@code BREAK}/
         * {@code CONTINUE} record the top loop as their target — the
         * checker's E2000 pins source-level loop placement, so a missing
         * target is a producer defect. Pushed while lowering a loop or
         * for-of body block.
         */
        private final ArrayDeque<OpId> loopTargets = new ArrayDeque<>();
        /**
         * The per-block termination flags (C-D2): a
         * {@code RETURN}/{@code THROW}/{@code BREAK}/{@code CONTINUE}
         * terminates its block; a following statement in the same block
         * is represented behind the terminator and never executes, and
         * the flag is monotone (a later statement neither clears nor
         * changes it).
         */
        private final java.util.LinkedHashMap<BlockId, Boolean> blockTerminated =
            new java.util.LinkedHashMap<>();
        /**
         * The closed per-block exit summaries of the terminator analysis
         * ({@code residual-carrier-shapes-production-realization} D3): the
         * two-facet summary the three implicit-return sites consume and the
         * composite arms compose from their sub-blocks. A block can
         * complete normally, and/or a reachable break/continue path may
         * escape into it; the closed three-member classification
         * ({@code OPEN}/{@code RETURN_OR_THROW}/{@code TRANSFER}) derives
         * from both facets. Absent = {@code OPEN}. One body's blocks are
         * disjoint from a nested declared/closure body's blocks, so a
         * nested transfer never contributes to an enclosing loop's state.
         */
        private final java.util.LinkedHashMap<BlockId, BlockExit> blockExits =
            new java.util.LinkedHashMap<>();
        /**
         * The enclosing address-chain parents (A-D2): the innermost chain
         * op currently being built. Every op emitted while a chain is
         * active records that chain as its {@code parentOpId} — chain
         * children in payload order and nested chain ops alike.
         */
        private final ArrayDeque<OpId> chainParents = new ArrayDeque<>();
        /**
         * The cached store identities of assigned variables (keyed by the
         * resolved {@link Symbol.VariableSymbol} identity, never by name
         * or structural equality — two same-named bindings in nested
         * scopes stay distinct). E6's declaration-driven
         * {@code BINDING_ALLOC} replaces this on-demand allocation.
         */
        private final IdentityHashMap<Symbol.VariableSymbol, BindingId> variableBindings =
            new IdentityHashMap<>();

        private final boolean bindingCore;

        private final boolean closureCore;

        private final boolean groupCore;

        private final boolean proofAnalysis;
        /**
         * The conservative binding-immutability analysis of the session
         * (the proof child's production class, B7 — active only in
         * proof-analysis mode): the resolved assignment facts and the
         * intrinsic markers feeding the proof derivation over the
         * binding-core incarnation map.
         */
        private final BindingImmutabilityAnalysis assignmentAnalysis =
            new BindingImmutabilityAnalysis();

        private final boolean creationRuleAnalysis;
        /**
         * The recorded creation-rule position classifications in walk
         * order (creation-rule-analysis mode): the fact surface backing
         * {@link #creationRuleFacts()}.
         */
        private final List<AdapterCreationRule.PositionClassification>
            creationClassifications = new ArrayList<>();

        private final boolean shapeMapAnalysis;
        /**
         * The recorded adapter emissions in emission order (shape-map
         * mode): the fact surface backing {@link #shapeMapFacts()}.
         */
        private final List<AdapterEmission> shapeMapEmissions = new ArrayList<>();

        /**
         * The seeded intrinsic bindings by intrinsic name (B1): the
         * module-init-top cells {@link #seedIntrinsicBindings} allocates
         * for the module's unshadowed root
         * {@code Symbol.IntrinsicSymbol} bindings. The map is the
         * position-resolved classifier's identity fact
         * ({@link #conversionIntrinsicKindAt}): a name is one of the
         * conversion/allocation intrinsics at a position exactly when the
         * walk's own binding environment resolves that name to the seeded
         * cell — a lexically shadowing local, parameter, enclosing-block
         * or nested declaration is a different cell, so the intrinsic is
         * never selected by spelling or by the root binding. Empty in the
         * modes that do not seed their intrinsic bindings; their
         * classifier falls back to the checker's per-scope table.
         */
        /**
         * The produced recursive groups' member facts in creation order
         * (group-core mode): the fact surface backing
         * {@link #groupFacts()}.
         */
        private final List<GroupFacts> groupFactsList = new ArrayList<>();
        /**
         * The pinned parameter bindings of the walk (B1/B9): every
         * parameter ALLOC emission records its {@link BindingId} — the
         * checker facts {@code BINDING_INIT_ONCE}'s parameter arm
         * consumes ({@link #pinnedWriteFacts()}). The unit payload alone
         * cannot distinguish a parameter ALLOC from a local ALLOC (both
         * {@code mutable=true}, generation 0, body-root block).
         */
        private final LinkedHashSet<BindingId> parameterBindings = new LinkedHashSet<>();
        /**
         * The pinned import-alias bindings of the walk (B1/B9): every
         * hoisted import-alias ALLOC emission records its
         * {@link BindingId} — the checker facts
         * {@code BINDING_INIT_ONCE}'s import-alias arm consumes
         * ({@link #pinnedWriteFacts()}). The unit payload alone cannot
         * distinguish an alias ALLOC from an intrinsic ALLOC (both
         * module-region {@code mutable=false} ALLOCs).
         */
        private final LinkedHashSet<BindingId> importAliasBindings = new LinkedHashSet<>();
        /**
         * The hoisted import-alias cell per alias name (declaration
         * order): the alias&#8596;cell join the {@code MODULE_IMPORT}
         * arm records in the op's payload ({@code aliasCells}) — the
         * explicit list the import completion writes and the bindings
         * production validator resolves the alias's pinned initializing
         * write through.
         */
        private final Map<String, BindingId> importAliasCells = new LinkedHashMap<>();

        private final boolean fullProgram;

        private final boolean classCore;

        private final deal.semantic.ir.ExternalModuleInterface ownInterface;

        private final Map<ClassId, SharedFactoryFacts> sharedFactories;
        /**
         * The provider-keyed shared-factory facts of the accumulated
         * project closure (declared class occurrence's construction entry
         * to its facts) and the index's declared-class construction
         * entries keyed by declaring module then canonical class identity:
         * a canonical class identity can carry two declarations only in a
         * harness-synthesized carrier, so the recorded provider alias —
         * never the identity-keyed first match — selects the exact
         * declaring provider of a construction.
         */
        private Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
            providerFactoryFacts = Map.of();
        private Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>>
            providerConstructionEntries = Map.of();
        private Map<DiagnosticRange, String> recordedClassLiteralProviders = Map.of();
        /**
         * The canonical class identities declared by more than one module
         * of the whole project (a harness-synthesized pair; a resolved
         * source file is its own identity): a field operation or generated
         * JSON link of such an identity without a recorded exact declaring
         * provider fails closed — never a first alias, a unit-local mask or
         * an identity-keyed first match.
         */
        private java.util.Set<ClassId> projectAmbiguousClassIds =
            java.util.Set.of();
        /**
         * The canonical class identities carrying more than one
         * plan-bearing ({@code @jsonable}) declaration in the project: the
         * generated JSON plan key is the canonical identity, so only such
         * an identity has no single nested plan and its JSON links fail
         * closed. A same-identity declaration without a generated plan
         * registers none and never makes the key ambiguous.
         */
        private java.util.Set<ClassId> ambiguousJsonPlanClassIds =
            java.util.Set.of();
        /**
         * The recorded plan owner of every canonical identity carrying
         * more than one declaration (the unique plan-bearing declaration
         * when one exists, the emission order's last declaration
         * otherwise) and the owner's construction entry when exported.
         * The generated JSON plan table carries exactly this declaration's
         * layout under the canonical key, so the nested-link admission and
         * the per-module validation resolve through it.
         */
        private Map<ClassId, ModuleId> jsonPlanOwners = Map.of();
        private Map<ClassId, deal.semantic.ir.ClassFactoryId> jsonPlanOwnerEntries =
            Map.of();

        private ClassRegistrationSeeds registrationSeeds =
            ClassRegistrationSeeds.builtinErrorOnly();


        private final java.util.LinkedHashMap<ClassId, deal.semantic.ir.ClassLayout>
            classLayouts = new java.util.LinkedHashMap<>();

        /**
         * The complete module-level class layout surface, computed
         * before any body walk (docs/spec-v1.5.md §Name resolution,
         * Declaration order: a function body and a class field may
         * reference a class declared later in the same module). One entry
         * per module-level class declaration, in declaration order, with
         * the class's own declaration recorded so the declaration walk
         * reuses exactly this layout instead of recomputing it at its
         * position.
         */
        private final java.util.LinkedHashMap<ClassId, ClassLayoutPlan>
            classLayoutPlans = new java.util.LinkedHashMap<>();

        /**
         * This unit's class declarations, keyed by canonical identity.
         * Imported and host providers carry their own declaration facts.
         */
        private final java.util.LinkedHashMap<ClassId, ClassDeclaration>
            unitClassDeclarations = new java.util.LinkedHashMap<>();

        /**
         * The module-level class declarations whose full lowering already ran
         * at the module hoist (identity keyed): the declaration-position walk
         * emits nothing for them.
         */
        private final Set<ClassDeclaration> hoistedClassDeclarations =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        private final java.util.LinkedHashMap<deal.semantic.ir.ClassFactoryId, OpId>
            factoryRegistry = new java.util.LinkedHashMap<>();

        /**
         * The produced structural companions of this session's unit (S1
         * ownership): the exact records the unit owns, captured by
         * {@link #buildUnit} so {@link #bodyTable()},
         * {@link #factoryRegistry()} and
         * {@link #pinnedWriteFacts()} return the same instances the unit
         * carries — the retained result views are these records, never
         * detached copies. A session that has not built its unit yet
         * derives fresh (equal) records from the live walk state.
         */
        private StructuredBodyTable producedBodyTable;

        private deal.semantic.ir.ClassFactoryRegistry producedClassFactories;

        private ClassBindingFacts producedClassBindingFacts;





        /**
         * The statement-walk strategy of the session: the nested-statement
         * recursion every arm consults. The E5 window routes to
         * {@link #lowerStatements}, the binding/closure windows to
         * {@link #lowerBindingStatements}, and the full-program window to
         * the unified walk ({@link #lowerFullStatements}) — so every arm's
         * nested blocks follow the session's construct coverage without
         * re-implementing the dispatch.
         */
        private final StatementWalk statementWalk;
        /**
         * One closed statement-walk strategy (the session's nested-block
         * recursion).
         */
        @FunctionalInterface
        private interface StatementWalk {
            void walk(List<StatementNode> statements, boolean moduleLevel);
        }
        /**
         * The single cell-kind derivation of the session (B2): every
         * {@code BINDING_ALLOC} payload cell kind flows through
         * {@link CellKindDerivation#cellKindOf} — at emission and again
         * at the walk-finalization re-derivation over the complete
         * capture-reference union.
         */
        private final CellKindDerivation cellKinds = new CellKindDerivation();
        /**
         * The produced lowered functions keyed by {@link FunctionId} (the
         * unit's {@code functions} map; closure-core mode).
         */
        private final Map<FunctionId, LoweredFunction> functions = new LinkedHashMap<>();
        /**
         * The declared bodies whose {@code LoweredBody} execution binding
         * is registered before their own body walk (the declared-body
         * preallocation guarantee) but whose capture list is not yet
         * known: the {@code LoweredFunction} record is finalized only
         * after the body walk completes, so an invocation of the body
         * through an ordinary function value resolved while that walk is
         * still open is value-carried — the closure carrier holds the
         * creation-site captures, never a call-site re-resolution of an
         * unresolved incarnation. The set holds the recursive-group
         * members pre-registered by {@link #lowerGroup} and every
         * declared function pre-registered by
         * {@link #lowerBindingFunctionDecl} (the local and module-level
         * self-reference shapes: a checker-valid reference of a
         * declaration to its own pre-allocated body identity, an
         * ordinary function-value call included).
         */
        private final Set<FunctionId> bodiesPendingCaptures =
            new LinkedHashSet<>();
        /**
         * The member binding cells of every nested (non-module-level)
         * recursive group of this lowering. A member binding's cell is
         * published by the group's {@code RECURSIVE_GROUP_INIT} and holds
         * the member's closure carrier; an identifier call of such a
         * binding must invoke that carrier instead of reconstructing the
         * member's factory at the call site, because a call-site
         * reconstruction resolves the target's captures from the calling
         * function's own frame and cannot name the group instance's cells
         * for a target that is not one of the caller's own captures (an
         * enclosing-function-returned group observed through a second
         * creation would re-resolve the first creation's sibling cells to
         * the latest creation's global cells). The module-level member
         * bindings are not in this set: their captures are module-level
         * cells with a single incarnation, so the landed static
         * direct-call shape is exact there.
         */
        private final Set<BindingId> nestedGroupMemberBindings =
            new LinkedHashSet<>();
        /**
         * The produced function-execution-bindings registry (the unit's
         * {@code functionBindings} map; the registry child's production
         * class, B5 — closure-core mode): exactly one registration per
         * producing allocation keyed by
         * {@link FunctionAllocationIdentity}, duplicate-key rejection at
         * registration time, and the insertion-ordered immutable
         * snapshot consumed by the unit producer.
         */
        private final FunctionBindingRegistry registry = new FunctionBindingRegistry();

        private final IdentityHashMap<BindingCoreIncarnation, FunctionContext>
            functionContexts = new IdentityHashMap<>();
        /**
         * The module-level function contexts keyed by declared name (the
         * entry-delegation lookup surface).
         */
        private final Map<String, FunctionContext> moduleFunctionContexts =
            new LinkedHashMap<>();
        /**
         * The binding-id → function-context index (the call-site
         * resolution surface of the carrier slice).
         */
        private final Map<BindingId, FunctionContext> contextsByBindingId =
            new LinkedHashMap<>();
        /**
         * The module's resolved import facts (the console-stdlib
         * detection surface), installed by the full-program entry.
         */
        private List<ResolvedImport> moduleImports = List.of();

        private boolean e7Calls;
        /** The module's checked export facts (the E7 entry/callback surface). */
        private List<ExportInterface> moduleExports = List.of();
        /** The callee-module route facts (execution-owner resolution). */
        /** The callee modules' recorded {@code EXTERNAL_ENTRY} op ids by export name. */
        private Map<ModuleId, Map<String, OpId>> calleeExternalEntries = Map.of();
        /** The exported function names the scenario invokes as callbacks. */
        private Set<String> callbackExports = Set.of();
        /**
         * The one lowering's declared-parameter-annotation index (P3), or
         * {@code null} on a non-project session (the entry sets it before
         * the walk).
         */
        private Map<ModuleId, DeclaredParameterAnnotations> declaredParameterAnnotations;
        /**
         * The member-read nodes that are the direct argument expression of a
         * declared callee's call (P3): the callee's parameter cell performs
         * their contextual kind check at the declaration-owned origin (the
         * unchanged reference defers the check to the declared parameter),
         * so a scalar contextual argument read lowers to its raw read and
         * composes no boundary of its own. Identity-keyed: only the exact
         * argument node is affected, never a nested read.
         */
        private final java.util.Set<MemberAccessExpr> callArgumentReads =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /** The module's recorded {@code EXTERNAL_ENTRY} op ids by export name. */
        private final LinkedHashMap<String, OpId> recordedEntries = new LinkedHashMap<>();
        /** The module's recorded {@code CALLBACK_INVOKE} op ids by export name. */
        private final LinkedHashMap<String, OpId> recordedCallbacks = new LinkedHashMap<>();
        /** The function-context index by {@link FunctionId} (E7 call-site resolution). */
        private final Map<FunctionId, FunctionContext> contextsByFunctionId =
            new LinkedHashMap<>();
        /** The hoisted module-level functions' allocation identities by name. */
        private final Map<String, ValueId> moduleFunctionIdentities = new LinkedHashMap<>();
        /**
         * The module's exported function names whose reserved invocation
         * shape is the declaration's {@code EXTERNAL_ENTRY}, by function id
         * (the same-module entry realization's export key): populated at
         * the hoist beside the shape reservation, and never for
         * {@code main} (the {@code ENTRY_INVOKE} delegation owns its single
         * invocation shape), for a callback export (host-driven
         * {@code CALLBACK_INVOKE}), or for the generated helper (its
         * same-module references keep the landed {@code LoweredBody} call
         * shape — R3(b)).
         */
        private final Map<FunctionId, String> moduleEntryExportNames =
            new LinkedHashMap<>();
        /**
         * The innermost enclosing function context of the current body
         * walk (RETURN resolution).
         */
        private final ArrayDeque<FunctionContext> functionStack = new ArrayDeque<>();

        /** The closed per-function invocation shapes of the E7 call machine. */
        private enum InvocationShape {
            /** Called from source: {@code CALL(DIRECT/INDIRECT)} (sync). */
            SOURCE_CALL,
            /** Awaited from source: an {@code ASYNC_START(DEAL_BODY)} body task. */
            SOURCE_ASYNC,
            /** Cross-module invocation: the function's {@code EXTERNAL_ENTRY}. */
            EXTERNAL_ENTRY_SHAPE,
            /** Host-driven invocation: the export's {@code CALLBACK_INVOKE}. */
            CALLBACK_SHAPE
        }

        private static final class FunctionContext {
            final FunctionId functionId;
            final BlockId bodyBlock;
            final RuntimeDescriptor.Func signature;
            final OpId returnBoundaryOpId;
            final OpId callSiteOpId;
            /**
             * The declared parameter type-annotation spans in declaration
             * order. A {@code CALL}'s {@code FUNCTION_PARAMETER} cell
             * reports the failing parameter at the callee's own declaration
             * site — the pinned corpus span of a parameter-boundary failure
             * ({@code runtime-errors/type-mismatch-e8001} at the callee
             * parameter's annotation; the unchanged JS and retained Lua
             * wrappers emit exactly that span) — so every call of a lowered
             * body carries them. A declared callee without a recorded
             * annotation fails closed (P3: a producer defect, never a
             * fallback span).
             */
            final List<Span> parameterTypeSpans;
            boolean returnBoundaryEmitted;
            /**
             * Whether the body's single return cell was emitted by a
             * {@code RETURN} of the body (the callee-owned form's recorded
             * cell). A never-returning body's cell is instead materialized
             * by its invocation op at the walk's finalization, so the body
             * carries no RETURN-owned cell to record.
             */
            boolean returnCellReturnParented;
            boolean callSiteUsed;
            InvocationShape shape;
            OpId shapeOpId;
            /**
             * True for a compiler-generated {@code @jsonable} helper
             * context (R3(b)): the declaring module's own reference to
             * the helper calls the class's generated closure identity
             * through the landed {@code LoweredBody} call shape, never
             * the module surface's external entry — the reserved
             * {@code EXTERNAL_ENTRY} record (and the body's
             * {@code EXTERNAL_RETURN} cell it names) stays the
             * cross-module callers' invocation.
             */
            boolean generatedHelper;

            FunctionContext(FunctionId functionId, BlockId bodyBlock,
                            RuntimeDescriptor.Func signature, OpId returnBoundaryOpId,
                            OpId callSiteOpId) {
                this(functionId, bodyBlock, signature, returnBoundaryOpId, callSiteOpId,
                    List.of());
            }

            FunctionContext(FunctionId functionId, BlockId bodyBlock,
                            RuntimeDescriptor.Func signature, OpId returnBoundaryOpId,
                            OpId callSiteOpId, List<Span> parameterTypeSpans) {
                this.functionId = functionId;
                this.bodyBlock = bodyBlock;
                this.signature = signature;
                this.returnBoundaryOpId = returnBoundaryOpId;
                this.callSiteOpId = callSiteOpId;
                // Null-tolerant: a declared parameter without a type
                // annotation records a null entry, and the declared-context
                // lookup fails closed on it (never a call-site fallback).
                this.parameterTypeSpans = java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<>(parameterTypeSpans));
            }

            /**
             * The parameter cell's origin span: the declared type-annotation
             * span at the parameter index (P3). A declared callee without a
             * recorded annotation at the index — an empty list, a short
             * list, or a null entry — is a fail-closed producer defect,
             * never a fallback to the invoking call site; only the cells
             * without a declared callee keep the call expression.
             */
            Span parameterSpan(int index) {
                Span span = index < parameterTypeSpans.size()
                    ? parameterTypeSpans.get(index) : null;
                if (span == null) {
                    throw new ConstructUnlowered("the declared parameter "
                        + (index + 1) + " of function id " + functionId.id()
                        + " has no recorded type annotation in the one lowering's"
                        + " declaration index (a declared callee without a recorded"
                        + " annotation is a fail-closed producer defect, never a"
                        + " fallback span)");
                }
                return span;
            }

            void assignShape(InvocationShape candidate, OpId candidateOpId) {
                if (shape == null) {
                    shape = candidate;
                    shapeOpId = candidateOpId;
                    return;
                }
                if (shape != candidate) {
                    throw new ConstructUnlowered("function id " + functionId.id()
                        + " is invoked under two invocation shapes (" + shape + " and "
                        + candidate + ") — the statically-resolved slice admits exactly "
                        + "one shape per function (runtime shape selection is "
                        + "ISSUE-0531's)");
                }
            }

            /** The enclosing invocation op the function's RETURN ops name. */
            OpId invocationOpId() {
                return shapeOpId != null ? shapeOpId : callSiteOpId;
            }
        }
        /**
         * The produced closures' capture facts in creation order
         * (closure-core mode): the fact surface backing
         * {@link #closureFacts()}.
         */
        private final List<ClosureFacts> closureFactsList = new ArrayList<>();
        /**
         * The statically tracked function-value identities of binding
         * incarnations (closure-core mode, identity-keyed): the
         * allocation identity the incarnation's cell currently holds.
         * {@code BINDING_LOAD}s of function-typed bindings publish the
         * tracked identity (identity preservation — the schema-level
         * {@code R-FUNCTION-BINDING} rule holds); a load whose
         * incarnation has no tracked identity fails closed as the
         * registry child's resolution (B5).
         */
        private final IdentityHashMap<BindingCoreIncarnation, ValueId> functionIdentity =
            new IdentityHashMap<>();
        /**
         * The assigned-name surface of the open function-body walks,
         * innermost first: the identifier names assigned anywhere in one
         * body's subtree, nested bodies included. The module-init walk is
         * the bottom scope (pushed before the module's statements walk), so
         * a module-level name is assigned exactly when some function of the
         * module assigns it.
         */
        private final ArrayDeque<Set<String>> assignedNameScopes = new ArrayDeque<>();
        /**
         * The assigned-name scope each registered binding was declared in
         * (keyed by binding identity, first incarnation wins). A
         * function-typed binding whose declaring scope names it is mutable:
         * a read observes the current binding, never the allocation the
         * declaration's initializer produced (docs/spec-v1.5.md §Closures
         * and captured variables — captured variables are captured by
         * binding, not by value).
         */
        private final Map<BindingId, Set<String>> assignedNamesOfBinding =
            new LinkedHashMap<>();
        /**
         * The capture borders of the currently open detached-body walks
         * (closure-core mode), innermost first: the number of binding
         * frames outside the function recorded before its own frame was
         * pushed. A reference inside the body resolving to a frame at or
         * beyond {@code bindingScopes.size() - border} is a capture
         * (B9 R2/R3 as the resolution model).
         */
        private final ArrayDeque<Integer> captureBorders = new ArrayDeque<>();
        /**
         * The capture collectors of the currently open detached-body
         * walks (closure-core mode), innermost first: each collector
         * records the captured cells in first-reference order for the
         * {@code CLOSURE_NEW} being built.
         */
        private final ArrayDeque<List<CapturedCell>> captureCollectors = new ArrayDeque<>();
        /**
         * The emission targets of the session, innermost first: the
         * session's op list at the bottom, one buffer per open
         * detached-body walk. Every emission appends to
         * {@link #emitTarget()}, so a function body's ops can be
         * collected while the walk runs and flushed after the
         * {@code CLOSURE_NEW} op that needs the collected captures.
         */
        private final ArrayDeque<List<SemanticOp>> emitTargets = new ArrayDeque<>();

        private BindingSiteResolver bindingSiteResolver;

        private final List<Map<String, FrameEntry>> bindingScopes = new ArrayList<>();
        /**
         * The checker's per-scoped-statement symbol tables, innermost
         * first: the current stack of scope-map key nodes (blocks,
         * function declarations, for statements, try statements) so the
         * walk resolves declared types of nested bindings without
         * retaining any {@code NameResolver} instance (D4).
         */
        private final ArrayDeque<StatementNode> checkerScopeNodes = new ArrayDeque<>();
        /**
         * The registered binding cells in registration order (binding-core
         * mode): the fact surface backing {@link #bindingFacts()}.
         */
        private final List<BindingCell> bindingCells = new ArrayList<>();

        private final Map<BindingId, BindingCell> cellsById = new LinkedHashMap<>();
        /**
         * The current structured-region block identities of the binding
         * walk, innermost first (module-init block at the bottom): the
         * scope every {@code BINDING_ALLOC} emitted at the current site
         * carries (B9 R1's same-block/structured-ancestor resolution
         * blocks).
         */
        private final ArrayDeque<BlockId> blockStack = new ArrayDeque<>();
        /**
         * The checked program's span: the pinned origin span of the
         * module-init-top intrinsic ALLOC/INIT ops (synthetic module
         * entry ops with no statement span of their own).
         */
        private final Span programSpan;

        /**
         * One binding cell of the binding environment (binding-core mode):
         * the declared name, its single globally unique {@link BindingId},
         * and its incarnations in allocation order.
         */
        private static final class BindingCell {

            final String name;
            final BindingId id;
            final List<BindingCoreIncarnation> incarnations = new ArrayList<>();

            BindingCell(String name, BindingId id) {
                this.name = Objects.requireNonNull(name, "name must not be null");
                this.id = Objects.requireNonNull(id, "id must not be null");
            }
        }

        /**
         * One scope-frame entry: the name's cell plus the incarnation
         * dominant within the frame (the frame-level visibility fact that
         * resolves loads/stores at the site).
         */
        private record FrameEntry(BindingCell cell, BindingCoreIncarnation incarnation) {
        }

        /**
         * One name resolution of the binding environment (closure-core
         * mode): the innermost frame entry plus the frame's index
         * (innermost first) — the frame index decides whether a body
         * reference resolves inside the function's own scope chain or is
         * a capture (B9 R2/R3).
         */
        private record FrameResolution(FrameEntry entry, int frameIndex) {

            private FrameResolution {
                Objects.requireNonNull(entry, "entry must not be null");
                if (frameIndex < 0) {
                    throw new IllegalArgumentException(
                        "frameIndex must be >= 0, got " + frameIndex);
                }
            }
        }

        /**
         * One captured cell of an open detached-body walk: the cell plus
         * the incarnation the capture resolves to at the creation site
         * (the dominant incarnation of the resolved frame entry).
         */
        private record CapturedCell(BindingCell cell, BindingCoreIncarnation incarnation) {
        }

        /**
         * Creates one lowering session. The module-init block is the
         * session's first allocation (role {@code BLOCK}).
         *
         */
        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids) {
            this(module, sourceId, checks, ids, false,
                new Span(sourceId, 1, 1, 1, 1, Span.UNKNOWN_OFFSET, Span.UNKNOWN_OFFSET));
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore, Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, false, programSpan);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, false, programSpan);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore, Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, groupCore,
                false, programSpan);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore,
                             boolean proofAnalysis, Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, groupCore,
                proofAnalysis, false, programSpan);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore,
                             boolean proofAnalysis, boolean creationRuleAnalysis,
                             Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, groupCore,
                proofAnalysis, creationRuleAnalysis, false, programSpan);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore,
                             boolean proofAnalysis, boolean creationRuleAnalysis,
                             boolean shapeMapAnalysis, Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, groupCore,
                proofAnalysis, creationRuleAnalysis, shapeMapAnalysis, programSpan,
                false);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, Span programSpan, boolean fullProgram) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, false, false,
                false, false, programSpan, fullProgram);
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore,
                             boolean proofAnalysis, boolean creationRuleAnalysis,
                             boolean shapeMapAnalysis, Span programSpan,
                             boolean fullProgram) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, groupCore,
                proofAnalysis, creationRuleAnalysis, shapeMapAnalysis, programSpan,
                fullProgram, false, null, Map.of());
        }

        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore,
                             boolean proofAnalysis, boolean creationRuleAnalysis,
                             boolean shapeMapAnalysis, Span programSpan,
                             boolean fullProgram, boolean classCore,
                             deal.semantic.ir.ExternalModuleInterface ownInterface,
                             Map<ClassId, SharedFactoryFacts> sharedFactories) {

            this.module = Objects.requireNonNull(module, "module must not be null");
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            this.checks = Objects.requireNonNull(checks, "checks must not be null");
            this.ids = Objects.requireNonNull(ids, "ids must not be null");
            this.bindingCore = bindingCore;
            this.closureCore = closureCore && bindingCore;
            this.groupCore = groupCore && this.closureCore;
            this.proofAnalysis = proofAnalysis;
            this.creationRuleAnalysis = creationRuleAnalysis;
            this.shapeMapAnalysis = shapeMapAnalysis && this.creationRuleAnalysis;
            this.fullProgram = fullProgram && bindingCore;
            this.classCore = classCore;
            this.ownInterface = ownInterface;
            this.sharedFactories = Map.copyOf(sharedFactories);
            this.statementWalk = fullProgram
                ? this::lowerFullStatements
                : (bindingCore ? this::lowerBindingStatements
                    : (statements, moduleLevel) -> lowerStatements(statements));
            this.programSpan = Objects.requireNonNull(programSpan,
                "programSpan must not be null");
            this.moduleInitBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            this.blockOps.put(moduleInitBlock, new ArrayList<>());
            this.blockTerminated.put(moduleInitBlock, false);
            this.blockStack.push(moduleInitBlock);
            emitTargets.push(ops);
            if (bindingCore) {
                bindingScopes.add(new LinkedHashMap<>());
                installBindingSiteResolver(name -> {
                    FrameEntry entry = frameEntryOf(name);
                    if (entry == null) {
                        return null;
                    }
                    return new BindingSite(entry.cell().id,
                        entry.incarnation().generation());
                });
            }
        }

        public void installBindingSiteResolver(BindingSiteResolver resolver) {
            this.bindingSiteResolver = Objects.requireNonNull(resolver,
                "resolver must not be null");
        }

        /** The binding-core mode flag of this session. */
        public boolean bindingCore() {
            return bindingCore;
        }

        /** The closure-core mode flag of this session. */
        public boolean closureCore() {
            return closureCore;
        }

        /** The group-core mode flag of this session. */
        public boolean groupCore() {
            return groupCore;
        }

        /** The module identity of this session. */
        public ModuleId module() {
            return module;
        }

        /** The produced operations in source order (unmodifiable). */
        public List<SemanticOp> ops() {
            return List.copyOf(ops);
        }

        /** The module-init block identity (the session's first allocation). */
        public BlockId moduleInitBlockId() {
            return moduleInitBlock;
        }

        // ---------------------------------------------------------------------
        // Block-membership machinery (C-D1) and the structure-op parents
        // ---------------------------------------------------------------------

        /**
         * Allocates one child block of the session: a fresh
         * {@link BlockId} registered in the block-membership state with
         * an empty op list (C-D1 — every payload-referenced block exists
         * in the produced table, empty blocks included).
         *
         */
        private BlockId allocateBlock() {
            BlockId block = ids.nextBlockId(module, nextOrdinal++, 0);
            blockOps.put(block, new ArrayList<>());
            blockTerminated.put(block, false);

            return block;
        }

        /** Pushes one allocated block as the current emission block. */
        private void pushBlock(BlockId block) {
            if (!blockOps.containsKey(block)) {
                throw new IllegalStateException(
                    "pushing an unregistered block (producer defect)");
            }
            blockStack.push(block);
        }

        /** Pops the current emission block (the outer block resumes). */
        private void popBlock() {
            if (blockStack.size() <= 1) {
                throw new IllegalStateException(
                    "popping the root module-init block (producer defect)");
            }
            blockStack.pop();
        }

        /**
         * Emits one op into the current emission target (the unit op
         * list or an open detached-body buffer, the closure child's
         * buffering) and the current emission block.
         */
        private void emit(SemanticOp op) {
            emitAt(emitTarget().size(), op);
        }

        /**
         * Emits one op at the pinned target-list position (the
         * structure-op position of a {@code LOOP}/{@code TRY_CATCH}/
         * {@code BRANCH(LOGICAL_*)} whose child blocks were lowered
         * before the op's emission) and records its membership in the
         * current emission block — the pinned unit order is structure op
         * first, then its child block ops in payload order. The mark is
         * relative to the current emission target, so a structure op
         * lowered inside a buffered detached-body walk keeps its pinned
         * position within that buffer.
         */
        private void emitAt(int mark, SemanticOp op) {
            List<SemanticOp> target = emitTarget();
            target.add(mark, op);
            BlockId block = blockStack.peek();
            if (blockOps.containsKey(block)) {
                blockOps.get(block).add(op.opId());
                opBlocks.put(op.opId(), block);
            }
            for (BlockId owned : ownedBlocksOf(op)) {
                if (owned != null) {
                    blockOwners.put(owned, op.opId());
                }
            }
        }

        /**
         * The child blocks one structure op's payload owns — the closed
         * structure set ({@code BRANCH}, {@code LOOP}, {@code FOR_EACH},
         * {@code TRY_CATCH}); every other op owns none.
         */
        private static List<BlockId> ownedBlocksOf(SemanticOp op) {
            return switch (op.payload()) {
                case KindPayload.BranchPayload branch -> branch.alternateBlock() == null
                    ? List.of(branch.selectedBlock())
                    : List.of(branch.selectedBlock(), branch.alternateBlock());
                case KindPayload.LoopPayload loop -> {
                    List<BlockId> blocks = new ArrayList<>();
                    blocks.add(loop.initBlock());
                    blocks.add(loop.bodyBlock());
                    if (loop.updateBlock() != null) {
                        blocks.add(loop.updateBlock());
                    }
                    yield List.copyOf(blocks);
                }
                case KindPayload.ForEachPayload forEach -> List.of(forEach.body());
                case KindPayload.TryCatchPayload tryCatch ->
                    List.of(tryCatch.tryBlock(), tryCatch.catchBlock());
                default -> List.of();
            };
        }

        /**
         * The parentage of the next USER op: the innermost address-chain
         * parent wins, then the innermost structure-op parent (block ops
         * record the enclosing structure op), else {@code null} at
         * module top level.
         */
        private OpId currentParent() {
            OpId chain = chainParents.peek();
            if (chain != null) {
                return chain;
            }
            return blockParents.peek();
        }

        /** Pushes one structure op as the current block-op parent. */
        private void pushBlockParent(OpId structureOpId) {
            blockParents.push(structureOpId);
        }

        /** Pops the current structure-op parent. */
        private void popBlockParent() {
            if (blockParents.isEmpty()) {
                throw new IllegalStateException(
                    "popping an empty block-parent stack (producer defect)");
            }
            blockParents.pop();
        }

        /** Pushes one loop op as the innermost break/continue target. */
        private void pushLoopTarget(OpId loopOpId) {
            loopTargets.push(loopOpId);
        }

        /** Pops the innermost break/continue target. */
        private void popLoopTarget() {
            if (loopTargets.isEmpty()) {
                throw new IllegalStateException(
                    "popping an empty loop-target stack (producer defect)");
            }
            loopTargets.pop();
        }

        /**
         * The closed three-member exit classification of one block
         * ({@code residual-carrier-shapes-production-realization} D3):
         * {@code OPEN} (the block can complete normally),
         * {@code RETURN_OR_THROW} (it cannot; every non-completing path
         * exits by {@code return}/{@code throw}), and {@code TRANSFER} (it
         * cannot; at least one path exits by {@code break}/{@code
         * continue}). The classification is derived from the two-facet
         * {@link BlockExit} summary; the two facets are tracked separately
         * so a partial transfer survives a later terminator in the same
         * block (see {@link #markStatementExit}).
         */
        private enum BlockExitState {
            OPEN,
            RETURN_OR_THROW,
            TRANSFER
        }

        /**
         * One block's composed exit summary (D3): whether the block can
         * complete normally, and whether a reachable {@code break}/{@code
         * continue} path escapes into it. The two facets are composed
         * separately because a composite may carry a transfer path while
         * it still has a normal exit (an {@code if} without an
         * {@code else}, an {@code if}/{@code else} or a {@code try}/{@code
         * catch} with one open branch): dropping that transfer path when a
         * later statement terminates the block would misclassify a
         * {@code break}/{@code continue} exit as {@code
         * RETURN_OR_THROW} and make a literal-true loop look
         * non-completing.
         */
        private record BlockExit(boolean canCompleteNormally, boolean transfers) {

            /** An unmarked (or absent) block: open, no transfer path. */
            static final BlockExit OPEN = new BlockExit(true, false);

            /** The block's closed three-member classification (D3). */
            BlockExitState state() {
                if (canCompleteNormally) {
                    return BlockExitState.OPEN;
                }
                return transfers ? BlockExitState.TRANSFER
                    : BlockExitState.RETURN_OR_THROW;
            }
        }

        /**
         * Marks the current emission block terminated after a
         * {@code RETURN}/{@code THROW} emission and records the leaf
         * transfer's exit facet. The state is monotone: a source statement
         * after the terminator in the same block lowers normally into ops
         * that are members of the same block behind the terminator, is
         * never executed, and neither clears nor changes this state. A
         * reachable partial transfer recorded earlier in the same block
         * stays recorded, so the block classifies {@code TRANSFER}.
         */
        private void terminateBlock() {
            blockTerminated.put(blockStack.peek(), true);
            markExit(blockStack.peek(), BlockExitState.RETURN_OR_THROW);
        }

        /**
         * Marks the current emission block terminated after a
         * {@code BREAK}/{@code CONTINUE} emission and records the leaf
         * transfer's closed exit state ({@link BlockExitState#TRANSFER}) —
         * the loop transfer leaves the block without completing normally,
         * and the block can still leave its enclosing loop normally.
         */
        private void transferBlock() {
            blockTerminated.put(blockStack.peek(), true);
            markExit(blockStack.peek(), BlockExitState.TRANSFER);
        }

        /**
         * The composed exit summary of one block; an unmarked (or absent)
         * block is {@link BlockExit#OPEN}.
         */
        private BlockExit exitOf(BlockId block) {
            return blockExits.getOrDefault(block, BlockExit.OPEN);
        }

        /**
         * The closed exit classification of one block; an unmarked (or
         * absent) block is {@code OPEN}.
         */
        private BlockExitState exitStateOf(BlockId block) {
            return exitOf(block).state();
        }

        /**
         * Applies one leaf transfer's closed exit state to a block: a
         * {@code RETURN}/{@code THROW} leaves the block unable to complete
         * normally with no transfer facet of its own, a {@code BREAK}/
         * {@code CONTINUE} leaves it unable to complete normally and
         * carries the transfer facet.
         */
        private void markExit(BlockId block, BlockExitState state) {
            switch (state) {
                case OPEN -> {
                }
                case RETURN_OR_THROW -> markStatementExit(block, false, false);
                case TRANSFER -> markStatementExit(block, false, true);
            }
        }

        /**
         * Composes one statement's two-facet exit summary into a block:
         * the block can complete normally exactly when it could before and
         * the statement can, and a break/continue path of the statement
         * stays recorded until (and past) the point a later statement
         * terminates the block. The composition is monotone: once the
         * block cannot complete normally every later marking is a no-op,
         * so a represented unreachable tail neither clears nor changes the
         * state, and a nested body's transfers never reach an enclosing
         * block (its blocks are disjoint).
         */
        private void markStatementExit(BlockId block, boolean canCompleteNormally,
                                       boolean transfers) {
            BlockExit current = exitOf(block);
            if (!current.canCompleteNormally()) {
                return;
            }
            blockExits.put(block, new BlockExit(canCompleteNormally,
                current.transfers() || transfers));
        }

        /**
         * Composes one two-branch composite's (an {@code if}/{@code else},
         * an {@code else if} chain's enclosing {@code if}, or a {@code
         * try}/{@code catch}) exit summary from its sub-blocks and applies
         * it to the enclosing block: the composite can complete normally
         * exactly when one of its sub-blocks can, and a break/continue
         * path of either sub-block escapes into the enclosing block (the
         * composite consumes no transfer). A partially transferring
         * composite therefore records its transfer path even while it can
         * still complete normally, and a later terminator in the same
         * block classifies the block {@code TRANSFER} instead of {@code
         * RETURN_OR_THROW}.
         */
        private void markCompositeExit(BlockId block, BlockId first, BlockId second) {
            BlockExit left = exitOf(first);
            BlockExit right = exitOf(second);
            markStatementExit(block,
                left.canCompleteNormally() || right.canCompleteNormally(),
                left.transfers() || right.transfers());
        }


        private static boolean isLiteralTrue(ExpressionNode expression) {
            return expression instanceof LiteralExpr literal
                && literal.value() instanceof LiteralValue.BooleanLiteral bool
                && bool.value();
        }

        // ---------------------------------------------------------------------
        // Environment frames (the checker-scope analog; the for-of arm drives
        // these, and per-construct slices may arrange them explicitly)
        // ---------------------------------------------------------------------

        /**
         * Opens one for-of loop-binding frame: allocates the binding
         * (role {@code BINDING}, pinned initial generation
         * {@link #INITIAL_LOOP_GENERATION}) and pushes the frame as the
         * new innermost scope. The for-of arm drives exactly one
         * open/close pair while lowering the loop body; per-construct
         * slices may arrange frames explicitly to lower operand-position
         * identifiers against enclosing loop bindings.
         *
         */
        public ForEachFrame openForEachScope(String name) {
            Objects.requireNonNull(name, "name must not be null");
            BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
            ForEachFrame frame = new ForEachFrame(name, binding, INITIAL_LOOP_GENERATION);
            frames.add(0, frame);
            return frame;
        }

        /**
         * Closes the innermost for-of loop-binding frame (a producer
         * defect when no frame is open).
         *
         */
        public void closeForEachScope() {
            if (frames.isEmpty()) {
                throw new IllegalStateException(
                    "closeForEachScope without an open loop-binding frame (producer defect)");
            }
            frames.remove(0);
        }

        /**
         * Lowers the module's top-level statements through the
         * binding-core walk: first the module-init-top bindings — the
         * {@code int}/{@code number} intrinsic bindings (ALLOC + INIT at
         * module-init top, the retained per-module wrapper shape) and the
         * hoisted module-level function-name ALLOCs plus import-alias
         * ALLOCs in declaration order (B1) — then the statements in
         * source order.
         *
         */
        public void lowerBindingModule(List<StatementNode> statements) {
            if (!bindingCore) {
                throw new IllegalStateException(
                    "lowerBindingModule outside binding-core mode (producer defect)");
            }
            hoistModuleLevelAllocs(statements);
            statementWalk.walk(statements, true);
            finalizeCellKinds();
        }

        public void lowerGroupModule(List<StatementNode> statements) {
            if (!groupCore) {
                throw new IllegalStateException(
                    "lowerGroupModule outside group-core mode (producer defect)");
            }
            List<FunctionDeclaration> moduleDeclarations = new ArrayList<>();
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    moduleDeclarations.add(function);
                }
            }
            List<List<FunctionDeclaration>> sccs =
                partitionFunctionDeclarations(moduleDeclarations);
            Set<FunctionDeclaration> moduleGroupMembers =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            List<List<FunctionDeclaration>> moduleGroups = new ArrayList<>();
            for (List<FunctionDeclaration> scc : sccs) {
                if (scc.size() >= 2) {
                    moduleGroups.add(scc);
                    moduleGroupMembers.addAll(scc);
                }
            }
            hoistModuleLevelAllocs(statements, moduleGroupMembers);
            // Module-init-top groups in declaration order (B4).
            for (List<FunctionDeclaration> group : moduleGroups) {
                lowerGroup(group, true);
            }
            lowerBindingStatements(statements, true);
            finalizeCellKinds();
        }

        public void lowerProjectModule(List<StatementNode> statements) {
            Objects.requireNonNull(statements, "statements must not be null");
            if (!bindingCore || !closureCore || !groupCore || !fullProgram || !classCore) {
                throw new IllegalStateException("lowerProjectModule outside the project "
                    + "session mode (the unified walk requires the binding, closure, "
                    + "group, full-program, and class arms together; producer defect)");
            }
            List<FunctionDeclaration> moduleDeclarations = new ArrayList<>();
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    moduleDeclarations.add(function);
                }
            }
            List<List<FunctionDeclaration>> sccs =
                partitionFunctionDeclarations(moduleDeclarations);
            Set<FunctionDeclaration> moduleGroupMembers =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            List<List<FunctionDeclaration>> moduleGroups = new ArrayList<>();
            for (List<FunctionDeclaration> scc : sccs) {
                if (scc.size() >= 2) {
                    moduleGroups.add(scc);
                    moduleGroupMembers.addAll(scc);
                }
            }
            hoistModuleLevelAllocs(statements, moduleGroupMembers);
            // Module-init-top groups in declaration order (B4).
            for (List<FunctionDeclaration> group : moduleGroups) {
                lowerGroup(group, true);
            }
            statementWalk.walk(statements, true);
            emitE7Terminals();
            finalizeInvocationIdentities();
            finalizeCellKinds();
        }

        /**
         * The binding walk's complete fact surface: one
         * {@link BindingCoreBinding} per declared name in registration
         * order (partial when the walk failed mid-way).
         *
         */
        public BindingCoreFacts bindingFacts() {
            List<BindingCoreBinding> facts = new ArrayList<>();
            for (BindingCell cell : bindingCells) {
                List<BindingCoreIncarnation> derived = new ArrayList<>();
                for (BindingCoreIncarnation incarnation : cell.incarnations) {
                    derived.add(new BindingCoreIncarnation(incarnation.generation(),
                        incarnation.scope(), cellKinds.cellKindOf(incarnation),
                        incarnation.mutable(), incarnation.producer(),
                        incarnation.pinnedSharedCell()));
                }
                facts.add(new BindingCoreBinding(cell.name, cell.id, derived));
            }
            return new BindingCoreFacts(facts);
        }

        /**
         * The walk's parameter/import initialization facts (B1/B9) — the
         * exact facts the produced unit owns as its
         * {@code classBindingFacts} companion: the parameter and
         * import-alias bindings the session recorded during the walk, the
         * checker-fact arms
         * {@code BindingsProductionValidator.BINDING_INIT_ONCE}
         * consumes (the unit payload alone cannot distinguish these two
         * pinned-write cells).
         *
         */
        public ClassBindingFacts pinnedWriteFacts() {
            if (producedClassBindingFacts != null) {
                return producedClassBindingFacts;
            }
            return computePinnedWriteFacts();
        }

        /** The fresh parameter/import fact record of the live session state. */
        private ClassBindingFacts computePinnedWriteFacts() {
            return new ClassBindingFacts(parameterBindings,
                importAliasBindings);
        }

        /**
         * The closure walk's complete closure fact surface: one
         * {@link ClosureFacts} per produced {@code CLOSURE_NEW} in
         * creation order, each with its captures resolved at the
         * detaching op's creation site (partial when the walk failed
         * mid-way).
         *
         */
        public List<ClosureFacts> closureFacts() {
            return List.copyOf(closureFactsList);
        }

        /**
         * The group walk's complete recursive-group fact surface: one
         * {@link GroupFacts} per produced {@code RECURSIVE_GROUP_INIT}
         * in creation order, each with its declaration-ordered member
         * facts (partial when the walk failed mid-way).
         *
         */
        public List<GroupFacts> groupFacts() {
            return List.copyOf(groupFactsList);
        }

        /**
         * The proof walk's complete immutability fact surface
         * (proof-analysis mode): the derived {@link
         * deal.semantic.ir.BindingImmutabilityProof} records per
         * {@code {binding, generation}} over the binding-core incarnation
         * map in registration order, plus the resolved assignment facts in
         * walk order (partial when the walk failed mid-way; the empty
         * surface outside proof-analysis mode).
         *
         */
        public BindingImmutabilityAnalysis.BindingImmutabilityFacts proofFacts() {
            if (!proofAnalysis) {
                return BindingImmutabilityAnalysis.BindingImmutabilityFacts.empty();
            }
            return assignmentAnalysis.facts(bindingFacts().bindings());
        }

        /**
         * The creation-rule walk's complete classification fact surface
         * (creation-rule-analysis mode): one
         * {@link AdapterCreationRule.PositionClassification} per
         * classified function-typed position in walk order (partial
         * when the walk failed mid-way; the empty surface outside
         * creation-rule-analysis mode).
         *
         */
        public AdapterCreationRule.CreationRuleFacts creationRuleFacts() {
            if (!creationRuleAnalysis) {
                return AdapterCreationRule.CreationRuleFacts.empty();
            }
            return new AdapterCreationRule.CreationRuleFacts(creationClassifications);
        }

        /**
         * The shape-map walk's complete emission fact surface (shape-map
         * mode): one {@link AdapterEmission} per emitted
         * {@code FUNCTION_ADAPT} op in emission order (partial when the
         * walk failed mid-way; the empty surface outside shape-map
         * mode).
         *
         */
        public ShapeMapFacts shapeMapFacts() {
            if (!shapeMapAnalysis) {
                return ShapeMapFacts.empty();
            }
            return new ShapeMapFacts(shapeMapEmissions);
        }


/**
         * Hoists the module-level function-name ALLOCs and import-alias
         * ALLOCs to the top of the module-init block in declaration order
         * (B1: the retained per-scope pre-declaration shape; the
         * {@code CLOSURE_NEW} + {@code BINDING_INIT} stay at the
         * declaration position — the closure child's production). Only
         * top-level {@link FunctionDeclaration}/{@link ImportDeclaration}
         * statements hoist; nested-scope declarations allocate at their
         * position in the main walk. The group walk passes its module-
         * level group members so they register without an ALLOC op (the
         * group op's publication phase is their producing allocation).
         */
        private void hoistModuleLevelAllocs(List<StatementNode> statements) {
            hoistModuleLevelAllocs(statements, Set.of());
        }

        private void hoistModuleLevelAllocs(List<StatementNode> statements,
                                            Set<FunctionDeclaration> groupMembers) {
            // The module-init assigned-name scope (the bottom of the
            // function-body stack): every module-level name the module's
            // functions assign, so a module-level function-typed binding's
            // reads observe the current binding.
            if (assignedNameScopes.isEmpty()) {
                pushAssignedNameScope(statements);
            }
            hoistModuleLevelClassLayouts(statements);
            for (StatementNode statement : statements) {
                if (statement instanceof ExportDeclaration exportDeclaration
                        && exportDeclaration.declaration()
                            instanceof FunctionDeclaration exported) {
                    statement = exported;
                }
                if (statement instanceof FunctionDeclaration function) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    boolean grouped = groupMembers.contains(function);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, moduleInitBlock,
                        grouped ? BindingCellKind.SHARED_CELL : BindingCellKind.DIRECT,
                        true, grouped ? BindingProducer.RECURSIVE_GROUP_INIT
                            : BindingProducer.BINDING_ALLOC,
                        grouped);
                    registerBinding(function.name(), binding, incarnation);
                    if (fullProgram) {
                        // The carrier's per-function reservation (forward
                        // references resolve deterministically): the
                        // lowered identity, the body block, the exact
                        // signature, and the single return-boundary and
                        // call-site op identities are pre-allocated here
                        // and reused by the declaration arm.
                        FunctionId functionId = ids.nextFunctionId(module, nextOrdinal++, 0);
                        BlockId bodyBlock = allocateBlock();
                        RuntimeDescriptor.Func signature =
                            functionSignatureOf(function);
                        OpId returnBoundaryOpId = ids.nextOpId(module, nextOrdinal++, 0);
                        OpId callSiteOpId = ids.nextOpId(module, nextOrdinal++, 0);
                        FunctionContext context = new FunctionContext(functionId,
                            bodyBlock, signature, returnBoundaryOpId, callSiteOpId,
                            parameterTypeSpans(function.params()));
                        if (e7Calls && isExported(function.name())
                                && !"main".equals(function.name())) {
                            // The exported function's reserved shape op
                            // identity (its EXTERNAL_ENTRY or
                            // CALLBACK_INVOKE op) — the RETURN ops'
                            // enclosing invocation resolves forward to
                            // the reserved id.
                            OpId shapeOpId = ids.nextOpId(module, nextOrdinal++, 0);
                            InvocationShape shape =
                                callbackExports.contains(function.name())
                                    ? InvocationShape.CALLBACK_SHAPE
                                    : InvocationShape.EXTERNAL_ENTRY_SHAPE;
                            context.assignShape(shape, shapeOpId);
                            if (shape == InvocationShape.EXTERNAL_ENTRY_SHAPE) {
                                // The same-module entry realization's
                                // export key (see
                                // {@link #moduleEntryExportName}): a
                                // declaration-name or value-carried
                                // reference realizes the entry over the
                                // shared body.
                                moduleEntryExportNames.put(functionId,
                                    function.name());
                            }
                        }
                        functionContexts.put(incarnation, context);
                        moduleFunctionContexts.put(function.name(), context);
                        contextsByBindingId.put(binding, context);
                        contextsByFunctionId.put(functionId, context);
                        if (closureCore && grouped) {
                            // The pending-capture fact (B1/B3) registers for
                            // every module-level group member before any
                            // group body walk: a reference to a member whose
                            // own group walk has not opened yet is
                            // value-carried through the member's published
                            // carrier, so an earlier group's alias of a
                            // later group's member never re-resolves an
                            // unresolved creation-site incarnation at the
                            // call site. The module-init-top group cells
                            // (the emitters' and the oracle's pre-published
                            // member cells) make the captured cell
                            // identities exist before any module-init op
                            // runs.
                            bodiesPendingCaptures.add(functionId);
                        }
                    }
                    if (closureCore) {
                        // The function-allocation identity of the hoisted
                        // module-level function is pre-allocated here so a
                        // capture of a later-declared module function
                        // (B1; docs/spec-v1.5.md §Name resolution,
                        // Declaration order) — a
                        // function-typed load inside an earlier closure
                        // body — can publish the identity the cell will
                        // hold (loads preserve allocation identity; the
                        // declaration-position CLOSURE_NEW reuses this
                        // pre-allocated result identity, and a group
                        // member's publication phase publishes the same
                        // pre-assigned member identity).
                        ValueId closureIdentity = ids.nextValueId(module, nextOrdinal++, 0);
                        functionIdentity.put(incarnation, closureIdentity);
                    }
                    if (fullProgram) {
                        moduleFunctionIdentities.put(function.name(),
                            functionIdentity.get(incarnation));
                    }
                    if (grouped) {
                        // No BINDING_ALLOC for a group member: the
                        // RECURSIVE_GROUP_INIT op's publication phase
                        // publishes the member cell (B1/B4) — the closed
                        // payload records no cell-kind field, so the
                        // SHARED_CELL fact above is the only recording
                        // surface and the capture-driven rule yields
                        // SHARED_CELL for every member anyway (B2).
                        continue;
                    }
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, moduleInitBlock, true,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                        function.span(), FailurePolicyId.NO_DEAL_FAILURE);
                } else if (statement instanceof ImportDeclaration importDecl) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                        false, BindingProducer.BINDING_ALLOC, false);
                    registerBinding(importDecl.alias(), binding, incarnation);
                    importAliasBindings.add(binding);
                    importAliasCells.put(importDecl.alias(), binding);
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, moduleInitBlock, false,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                        importDecl.span(), FailurePolicyId.NO_DEAL_FAILURE);
                }
            }
            // The generated @jsonable helper names (R3): the declaring
            // module's exported helpers are module-level functions of the
            // module surface, so their names hoist exactly like declared
            // module-level functions — one binding cell at module-init
            // top, one pre-allocated closure identity, one reserved
            // invocation context — and the class-declaration walk reuses
            // them (the declaration-position CLOSURE_NEW + BINDING_INIT).
            // This pass runs only in the production project session (the
            // class-core-only seam keeps its landed three-op generated
            // bodies).
            if (fullProgram && classCore && e7Calls) {
                hoistJsonHelperAllocs(statements);
            }
            if (fullProgram && closureCore) {
                // The module-level declared bodies' execution bindings are
                // registered at the hoist (C10/K12: every lowered body owns
                // one resolvable invocation identity). A forward
                // function-value reference — an ordinary alias
                // ({@code let again = f} before {@code f}'s declaration), a
                // capture-carrying declared-name call routed through the
                // published carrier, or an await of either — resolves the
                // body from the hoist on, never only after the target's own
                // declaration arm walked. The declaration arm and the group
                // publication reuse this one registration through
                // {@link #registerDeclaredBody}.
                for (Map.Entry<String, FunctionContext> hoisted
                        : moduleFunctionContexts.entrySet()) {
                    ValueId identity = moduleFunctionIdentities.get(hoisted.getKey());
                    if (identity == null) {
                        throw new IllegalStateException("hoisted module-level "
                            + "allocation identity missing for '" + hoisted.getKey()
                            + "' (producer defect)");
                    }
                    FunctionContext context = hoisted.getValue();
                    registerDeclaredBody(new FunctionAllocationIdentity(identity.id()),
                        context.functionId, context.bodyBlock);
                }
            }
            hoistModuleLevelClassDeclarations(statements);
        }

        /**
         * Register module-level structural layouts, factories and JSON helpers
         * before body lowering, so forward class references see complete facts.
         * Registration executes no field expression. The declaration-position
         * walk emits nothing for an already hoisted declaration.
         */
        private void hoistModuleLevelClassDeclarations(List<StatementNode> statements) {
            if (!classCore || !fullProgram) {
                // The class-core-only seam keeps its declaration-position
                // walk: it carries no forward-reference contract and no
                // project-wide construction facts to order.
                return;
            }
            List<ClassDeclaration> declarations = new ArrayList<>();
            Set<ClassDeclaration> exportedDeclarations =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (StatementNode statement : statements) {
                if (statement instanceof ExportDeclaration exportDeclaration) {
                    if (exportDeclaration.declaration()
                            instanceof ClassDeclaration exportedClass) {
                        exportedDeclarations.add(exportedClass);
                        declarations.add(exportedClass);
                    }
                    continue;
                }
                if (statement instanceof ClassDeclaration classDeclaration) {
                    declarations.add(classDeclaration);
                }
            }

            for (ClassDeclaration declaration : declarations) {
                lowerClassDeclaration(declaration, exportedDeclarations.contains(declaration));
                hoistedClassDeclarations.add(declaration);
            }

        }

        /** Resolve a literal's exact local declaring provider, never a same-name sibling. */
        private boolean constructsLocalDeclaration(ObjectLiteralExpr literal,
                Type.Class classType, ClassId classId) {
            Symbol.ClassSymbol exact = ClassId.ERROR.equals(classId) ? null
                : exactReceiverDeclaration(literal);
            if (exact != null && classType.identity().equals(exact.identity())) {
                String declaring = exact.modulePath();
                return declaring == null || declaring.isEmpty()
                    || declaring.equals(module.path());
            }
            return !ambiguousFieldIdentity(classId)
                && classLayouts.containsKey(classId);
        }

        /**
         * Registers one declared body's execution binding exactly once:
         * the hoist's pre-registration, the declaration arm's
         * pre-walk registration, and the group publication all name the
         * identical {@code LoweredBody}. A second identical registration
         * is a no-op; a conflicting one is a producer defect (the closed
         * registry holds exactly one {@code FunctionExecutionBinding} per
         * producing allocation).
         */
        private void registerDeclaredBody(FunctionAllocationIdentity identity,
                                          FunctionId functionId, BlockId bodyBlock) {
            FunctionExecutionBinding produced =
                new FunctionExecutionBinding.LoweredBody(functionId, bodyBlock);
            FunctionExecutionBinding previous = registry.registrationOf(identity);
            if (previous == null) {
                registry.registerClosure(identity, functionId, bodyBlock);
                return;
            }
            if (!previous.equals(produced)) {
                throw new IllegalStateException("the declared body's allocation "
                    + "identity " + identity + " carries a different "
                    + "function-binding registration (" + previous + ") than "
                    + produced + " (producer defect)");
            }
        }

        /**
         * Hoists the generated {@code C$fromJson}/{@code C$toJson} names
         * of every module-level {@code @jsonable} class declaration (the
         * helper-family realization): the helper is a module-level export
         * function, so its name resolves through the same binding
         * environment a declared module function uses — the module-init
         * {@code BINDING_ALLOC}, the pre-allocated closure identity
         * ({@code functionIdentity}), the reserved body context
         * ({@code moduleFunctionContexts}) with its single return
         * boundary and call-site identity, the exported helper's
         * {@code EXTERNAL_ENTRY} shape, and the recorded module function
         * identity the E7 publication reads. A reference before the class
         * declaration therefore resolves, and the declaration-position
         * walk publishes the one identity the registration names (never a
         * second closure for one class).
         *
         * <p>The generated signatures flow from the checker's synthetic
         * {@code C$fromJson}/{@code C$toJson} root-scope function symbols
         * through the single {@code DescriptorService} producer. A
         * {@code @jsonable} class without the synthetic symbols is a
         * fact defect. The helper's declaration-owned parameter origin is
         * the class declaration's own span (the generated declaration's
         * site), recorded on the context so a caller's declared-parameter
         * cell reads a declaration-owned span, never the call site.</p>
         */
        private void hoistJsonHelperAllocs(List<StatementNode> statements) {
            SymbolTable scope = currentCheckerScope();
            for (StatementNode statement : statements) {
                StatementNode declaration = statement instanceof ExportDeclaration export
                    ? export.declaration() : statement;
                if (!(declaration instanceof ClassDeclaration classDeclaration)
                        || !classDeclaration.isJsonable()) {
                    continue;
                }
                for (boolean fromJson : new boolean[] {true, false}) {
                    String name = classDeclaration.name()
                        + (fromJson ? "$fromJson" : "$toJson");
                    Symbol symbol = scope == null ? null : scope.resolve(name);
                    if (!(symbol instanceof Symbol.FunctionSymbol functionSymbol)) {
                        throw new ConstructUnlowered("the @jsonable class '"
                            + classDeclaration.name() + "' has no checked " + name
                            + " function symbol (the checker defines the synthetic"
                            + " symbols for every @jsonable class — a fact defect)",
                                classDeclaration.span());
                    }
                    RuntimeDescriptor.Func signature =
                        jsonHelperSignatureOf(functionSymbol, classDeclaration);
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                        true, BindingProducer.BINDING_ALLOC, false);
                    registerBinding(name, binding, incarnation);
                    FunctionId functionId = ids.nextFunctionId(module, nextOrdinal++, 0);
                    BlockId bodyBlock = allocateBlock();
                    OpId returnBoundaryOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    OpId callSiteOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    FunctionContext context = new FunctionContext(functionId, bodyBlock,
                        signature, returnBoundaryOpId, callSiteOpId,
                        List.of(classDeclaration.span()));
                    // R3(b): the declaring module's own reference to the
                    // helper resolves the class's generated closure
                    // identity and calls it through the landed
                    // LoweredBody call shape (see lowerDirectCall).
                    context.generatedHelper = true;
                    if (isExported(name)) {
                        // The exported helper still records exactly one
                        // EXTERNAL_ENTRY (R3(a)): a cross-module
                        // reference realizes the closed CALL(EXTERNAL)
                        // SHARED_BODY cell over that entry, and the
                        // entry's cell keeps the body's return boundary
                        // kind (EXTERNAL_RETURN) even when the same
                        // module also calls the helper directly.
                        OpId shapeOpId = ids.nextOpId(module, nextOrdinal++, 0);
                        context.assignShape(InvocationShape.EXTERNAL_ENTRY_SHAPE,
                            shapeOpId);
                    }
                    functionContexts.put(incarnation, context);
                    moduleFunctionContexts.put(name, context);
                    contextsByBindingId.put(binding, context);
                    contextsByFunctionId.put(functionId, context);
                    ValueId closureIdentity = ids.nextValueId(module, nextOrdinal++, 0);
                    functionIdentity.put(incarnation, closureIdentity);
                    moduleFunctionIdentities.put(name, closureIdentity);
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, moduleInitBlock, true,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                        classDeclaration.span(), FailurePolicyId.NO_DEAL_FAILURE);
                }
            }
        }

        private void lowerBindingStatements(List<StatementNode> statements,
                                           boolean moduleLevel) {
            if (!groupCore) {
                for (StatementNode statement : statements) {
                    switch (statement) {
                        case FunctionDeclaration function ->
                            lowerBindingFunctionDecl(function, moduleLevel);
                        default -> lowerBindingStatement(statement);
                    }
                }
                return;
            }
            // Group-core mode: the per-scope SCC partition over function
            // declarations (name references in bodies, B4).
            Map<FunctionDeclaration, List<FunctionDeclaration>> memberGroups =
                groupMembership(statements);
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    if (skipGroupedFunctionDecl(function, memberGroups, moduleLevel)) {
                        continue;
                    }
                    lowerBindingFunctionDecl(function, moduleLevel);
                    continue;
                }
                lowerBindingStatement(statement);
            }
        }

        private Map<FunctionDeclaration, List<FunctionDeclaration>> groupMembership(
                List<StatementNode> statements) {
            if (!groupCore) {
                return Map.of();
            }
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    declarations.add(function);
                }
            }
            Map<FunctionDeclaration, List<FunctionDeclaration>> memberGroups =
                new IdentityHashMap<>();
            for (List<FunctionDeclaration> scc
                    : partitionFunctionDeclarations(declarations)) {
                if (scc.size() >= 2) {
                    for (FunctionDeclaration member : scc) {
                        memberGroups.put(member, scc);
                    }
                }
            }
            return memberGroups;
        }

        /**
         * Handles one function declaration that belongs to a size&gt;=2 SCC:
         * a nested-scope group emits its single {@code RECURSIVE_GROUP_INIT}
         * op at the first member's declaration position (B4) and every
         * other member is skipped (the op publishes all member bindings at
         * once); a module-level group was emitted at module-init top by the
         * walk entry, so its members are skipped here.
         *
         */
        private boolean skipGroupedFunctionDecl(FunctionDeclaration function,
                Map<FunctionDeclaration, List<FunctionDeclaration>> memberGroups,
                boolean moduleLevel) {
            List<FunctionDeclaration> group = memberGroups.get(function);
            if (group == null) {
                return false;
            }
            if (!moduleLevel && group.get(0) == function) {
                lowerGroup(group, false);
            }
            return true;
        }


        private void lowerBindingStatement(StatementNode statement) {
            switch (statement) {
                case VariableDeclaration decl -> lowerBindingVarDecl(decl);

                case ForOfStatement forOf -> lowerBindingForOf(forOf);
                case TryStatement tryStatement -> lowerBindingTry(tryStatement);
                case ImportDeclaration ignored -> {

                }
                case deal.ast.ExpressionStatement expressionStatement ->
                    lowerBindingExprStatement(expressionStatement);
                case Block block -> lowerBindingBlock(block);
                case ClassDeclaration classDeclaration -> {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeStatement(statement),
                            statement.span());
                    }
                    lowerClassDeclaration(classDeclaration, false);
                }
                case ExportDeclaration exportDeclaration -> {
                    if (!(classCore
                            && exportDeclaration.declaration()
                                instanceof ClassDeclaration classDeclaration)) {
                        throw new ConstructUnlowered(describeStatement(statement),
                            statement.span());
                    }
                    lowerClassDeclaration(classDeclaration, true);
                }
                case deal.ast.DeleteStatement delete -> {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeStatement(statement),
                            statement.span());
                    }
                    lowerDelete(delete);
                }
                default -> throw new ConstructUnlowered(describeStatement(statement),
                    statement.span());
            }
        }


        private void lowerBindingExprStatement(
                deal.ast.ExpressionStatement statement) {
            if (!(statement.expr() instanceof AssignmentExpr)) {
                throw new ConstructUnlowered("expression statement "
                    + describeExpression(statement.expr()) + " (DISCARD is E5's, "
                    + "ISSUE-0234; the binding walk admits assignment statements only)",
                        statement.span());
            }
            if (classCore) {
                // The class window's expression-statement arm (C-D8): the
                // committed value's producing ops already completed, then
                // one DISCARD audited in the op stream — never inferred
                // away (and the closed RETURN_EXPRESSION_STATEMENT
                // coverage row is satisfied through its pinned
                // DISCARD form).
                ValueId value = lowerExpression(statement.expr());
                emitNullOp(SemanticOpKind.DISCARD,
                    new KindPayload.DiscardPayload(value), statement.span(),
                    FailurePolicyId.NO_DEAL_FAILURE, SourceOriginKind.SYNTHETIC,
                    currentParent());
                return;
            }
            lowerExpression(statement.expr());
        }

        /**
         * One module-level class declaration's complete layout surface:
         * the {@link deal.semantic.ir.ClassLayout} the class identity
         * resolves to, the owning declaration, and the field descriptors
         * and exact declaring providers the declaration walk's default and
         * JSON arms consume. The layout is a declaration fact, not a body
         * fact, so it is complete before any function body lowers.
         */
        private record ClassLayoutPlan(
            deal.semantic.ir.ClassLayout layout,
            ClassDeclaration declaration,
            Map<String, RuntimeDescriptor> descriptors,
            Map<String, ModuleId> fieldProviders) {
        }

        /**
         * Registers every module-level class declaration's layout before
         * any body walk (docs/spec-v1.5.md §Name resolution, Declaration
         * order): a function body or class field may construct a class
         * declared later in the same module, so a class-literal in an
         * earlier function body must resolve the module's own layout even
         * though the declaration statement has not been walked yet. Only
         * the structural layout registers here — never a default
         * expression, a JSON helper, or a factory (registration executes
         * no defaults). The declaration walk emits those at the
         * declaration's own position and reuses the registered layout.
         */
        private void hoistModuleLevelClassLayouts(List<StatementNode> statements) {
            if (!classCore) {
                // Without the class arm a class declaration is the walk's
                // foreign construct, so no layout surface is owed.
                return;
            }
            for (StatementNode statement : statements) {
                if (statement instanceof ExportDeclaration exportDeclaration) {
                    statement = exportDeclaration.declaration();
                }
                if (!(statement instanceof ClassDeclaration declaration)) {
                    continue;
                }
                ClassId classId = classIdOf(declaration);
                if (classLayoutPlans.containsKey(classId)) {
                    throw new ConstructUnlowered("duplicate class declaration '"
                        + declaration.name() + "' (the checker rejects duplicate"
                        + " class names; a second layout for " + classId
                        + " is a fact defect)", declaration.span());
                }
                ClassLayoutPlan plan = computeClassLayout(classId, declaration);
                classLayoutPlans.put(classId, plan);
                classLayouts.put(classId, plan.layout());
                unitClassDeclarations.put(classId, declaration);
            }
        }

        /**
         * The structural layout of one class declaration: the ordered
         * field layouts with their descriptors and the fields' exact
         * declaring providers. Computed once per declaration, before any
         * body walk.
         */
        private ClassLayoutPlan computeClassLayout(ClassId classId,
                                                   ClassDeclaration declaration) {
            List<deal.semantic.ir.ClassLayout.FieldLayout> fields = new ArrayList<>();
            Map<String, RuntimeDescriptor> descriptors = new LinkedHashMap<>();
            Map<String, ModuleId> fieldProviders = new LinkedHashMap<>();
            for (ClassField field : declaration.fields()) {
                Type fieldType = fieldTypeOf(field.type());
                RuntimeDescriptor descriptor;
                try {
                    descriptor = DescriptorService.describe(fieldType);
                } catch (DescriptorService.Defect defect) {
                    throw new ConstructUnlowered("class field '" + declaration.name()
                        + "." + field.name() + "' of unrepresentable checked type "
                        + typeName(fieldType) + " (" + defect.getMessage() + ")",
                        field.span());
                }
                fields.add(new deal.semantic.ir.ClassLayout.FieldLayout(field.name(),
                    descriptor, !field.optional(), ConstructionProvider.LOCAL));
                descriptors.put(field.name(), descriptor);
                // The field's exact declaring declaration (the checked
                // annotation's own class symbol): a bare name is the
                // module's own declaration, a qualified name the
                // imported module's own declared symbol. The generated
                // JSON plan table is keyed by the canonical class
                // identity, so this recorded provider selects the exact
                // declaration of every nested JSON link.
                Symbol.ClassSymbol annotationSymbol =
                    deal.checker.NameResolver.classSymbolInScope(
                        currentCheckerScope(), field.type());
                if (annotationSymbol != null
                        && !annotationSymbol.modulePath().isEmpty()) {
                    fieldProviders.put(field.name(),
                        new ModuleId(annotationSymbol.modulePath()));
                }
            }
            return new ClassLayoutPlan(
                new deal.semantic.ir.ClassLayout(classId, fields), declaration,
                descriptors, fieldProviders);
        }

        private void lowerClassDeclaration(ClassDeclaration declaration, boolean exported) {
            if (hoistedClassDeclarations.contains(declaration)) {
                // The module-level declaration already lowered at the hoist
                // (its layout, defaults, helpers, and construction entry are
                // declaration facts): the walk position owns no emission.
                return;
            }
            ClassId classId = classIdOf(declaration);
            ClassLayoutPlan plan = classLayoutPlans.get(classId);
            if (plan != null && plan.declaration() != declaration) {
                throw new ConstructUnlowered("duplicate class declaration '"
                    + declaration.name() + "' (the checker rejects duplicate class names; "
                    + "a second layout for " + classId + " is a fact defect)",
                    declaration.span());
            }
            if (plan == null) {
                // A class declaration outside the module-level statement
                // list never reaches the walk in a checked project (a local
                // class declaration is the frontend's E1057); the lazy arm
                // keeps the layout surface complete for a directly walked
                // statement list.
                plan = computeClassLayout(classId, declaration);
                classLayoutPlans.put(classId, plan);
                classLayouts.put(classId, plan.layout());
                unitClassDeclarations.put(classId, declaration);
            }
            Map<String, RuntimeDescriptor> descriptors = plan.descriptors();
            Map<String, ModuleId> fieldProviders = plan.fieldProviders();
            deal.semantic.ir.ClassLayout layout = plan.layout();

            if (declaration.isJsonable()) {
                if (ambiguousJsonPlanClassIds.contains(classId)) {
                    throw new ConstructUnlowered("the @jsonable class '"
                        + declaration.name() + "' carries canonical class identity "
                        + classId + " together with another plan-bearing"
                        + " (@jsonable) declaration in the project (same-directory"
                        + " sibling declarations): the generated JSON plan table is"
                        + " keyed by the canonical identity, so the generated JSON"
                        + " plan key cannot select the exact declaration — the"
                        + " project fails closed with no partial project",
                        declaration.span());
                }
                lowerJsonFunctions(declaration, classId, layout, fieldProviders);
            }

            if (!exported) {
                return;
            }

            deal.semantic.ir.ClassInterface interfaceEntry = null;
            for (deal.semantic.ir.ClassInterface candidate : ownInterface.classes()) {
                if (candidate.classId().equals(classId)) {
                    interfaceEntry = candidate;
                    break;
                }
            }
            if (interfaceEntry == null) {
                throw new ConstructUnlowered("exported class " + classId
                    + " has no interface index entry (the index build derives one "
                    + "ClassInterface per exported class — a fact defect)", declaration.span());
            }
            OpId callerOpRef = ids.nextOpId(module, nextOrdinal++, 0);
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId,
                toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC, anchor, null);
            SemanticOp op = buildOp(opId, SemanticOpKind.CLASS_FACTORY,
                new KindPayload.ClassFactoryPayload(classId, callerOpRef),
                result, classDescriptorOf(declaration.name()),
                FailurePolicyId.CLASS_CONSTRUCTION, origin);
            // Detached emission: appended to the unit op list with no block
            // membership (the five-module-level-kind rule; the factory is
            // never part of the module-init flow).
            emitTarget().add(op);
            factoryRegistry.put(interfaceEntry.constructionEntry(), opId);
        }

        private void lowerJsonFunctions(ClassDeclaration declaration, ClassId classId,
                                        deal.semantic.ir.ClassLayout layout,
                                        Map<String, ModuleId> fieldProviders) {
            rejectAmbiguousJsonNestedClasses(declaration, layout, fieldProviders);
            // The generated signatures flow from the checker's synthetic
            // C$fromJson/C$toJson function symbols through the single
            // DescriptorService producer (the producer-singularity rule:
            // no RuntimeDescriptor is ever constructed directly here).
            SymbolTable scope = currentCheckerScope();
            Symbol fromSymbol = scope == null ? null
                : scope.resolve(declaration.name() + "$fromJson");
            Symbol toSymbol = scope == null ? null
                : scope.resolve(declaration.name() + "$toJson");
            if (!(fromSymbol instanceof Symbol.FunctionSymbol fromFunction)
                    || !(toSymbol instanceof Symbol.FunctionSymbol toFunction)) {
                throw new ConstructUnlowered("the @jsonable class '"
                    + declaration.name() + "' has no checked C$fromJson/C$toJson "
                    + "function symbols (the checker defines the synthetic symbols for "
                    + "every @jsonable class — a fact defect)", declaration.span());
            }
            RuntimeDescriptor.Func fromSignature =
                jsonHelperSignatureOf(fromFunction, declaration);
            RuntimeDescriptor.Func toSignature =
                jsonHelperSignatureOf(toFunction, declaration);
            RuntimeDescriptor fromResult = fromSignature.returnType();
            RuntimeDescriptor toResult = toSignature.returnType();
            RuntimeDescriptor fromParameter = fromSignature.paramTypes().get(0);
            RuntimeDescriptor toParameter = toSignature.paramTypes().get(0);

            // C$fromJson(s: string): C|null — structural decode, no initializer children.
            OpId fromJsonOpId = lowerJsonFunction(declaration, layout, fromSignature,
                fromParameter, fromResult, FailurePolicyId.JSON_FROM_NULL, true);

            // C$toJson(v: C): string.
            lowerJsonFunction(declaration, layout, toSignature, toParameter, toResult,
                FailurePolicyId.JSON_TO_ERROR, false);
        }

        /**
         * The fail-closed rejection of a generated JSON link whose nested
         * class identity carries more than one plan-bearing
         * ({@code @jsonable}) declaration in the project (a
         * harness-synthesized pair): the emitted plan table is keyed by
         * the canonical class identity, so two generated plans cannot
         * select one — a named E6005 with no partial project, never a first
         * alias or a sibling's plan. An identity whose only plan-bearing
         * declaration is unique keeps its nested link exact: a
         * same-identity declaration without a generated plan registers no
         * plan and cannot collide with it.
         */
        private void rejectAmbiguousJsonNestedClasses(ClassDeclaration declaration,
                                                      deal.semantic.ir.ClassLayout layout,
                                                      Map<String, ModuleId> fieldProviders) {
            for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
                ClassId nested = nestedClassIdOf(field.descriptor());
                if (nested == null) {
                    continue;
                }
                if (ambiguousJsonPlanClassIds.contains(nested)) {
                    throw new ConstructUnlowered("the @jsonable class '"
                        + declaration.name() + "' field '" + field.name()
                        + "' references nested class " + nested + " whose canonical"
                        + " identity carries more than one plan-bearing (@jsonable)"
                        + " declaration in the project (same-directory sibling"
                        + " declarations): the generated JSON plan key cannot select"
                        + " the exact declaration, so the link fails closed with no"
                        + " partial project",
                        declaration.span());
                }
                ModuleId owner = jsonPlanOwners.get(nested);
                if (owner == null) {
                    // A single declaration: the canonical plan key is exact.
                    continue;
                }
                ModuleId provider = fieldProviders.get(field.name());
                if (owner.equals(provider)) {
                    // The generated JSON plan table carries exactly this
                    // field's declaring declaration under the canonical key.
                    continue;
                }
                throw new ConstructUnlowered("the @jsonable class '"
                    + declaration.name() + "' field '" + field.name()
                    + "' references nested class " + nested + " whose generated"
                    + " JSON plan key carries the declaration of module '"
                    + owner.path() + "' while the field's declared type resolves"
                    + (provider == null
                        ? " no recorded declaring module"
                        : " the declaration of module '" + provider.path() + "'")
                    + " (same-directory sibling declarations): the generated JSON"
                    + " plan key cannot select the exact declaration, so the link"
                    + " fails closed with no partial project",
                    declaration.span());
            }
        }

        /**
         * The class identity of a JSON-walk descriptor, or {@code null} for
         * a non-class descriptor (the nullable/array inner forms recurse
         * like the generated walk).
         */
        private static ClassId nestedClassIdOf(RuntimeDescriptor descriptor) {
            return switch (descriptor) {
                case RuntimeDescriptor.Class cls -> cls.classId();
                case RuntimeDescriptor.Nullable nullable ->
                    nestedClassIdOf(nullable.inner());
                case RuntimeDescriptor.Array array ->
                    nestedClassIdOf(array.element());
                default -> null;
            };
        }

        /**
         * The checked signature of one generated {@code @jsonable} helper
         * ({@code C$fromJson}/{@code C$toJson}): the single
         * {@link ContainerPayloadDescriptors} bridge over the checker's
         * synthetic function symbol. The generated declaration's site is
         * the owning class declaration (D2), so a corrupted checked
         * signature fails closed at that declaration's own range — never
         * the canonical empty-file anchor — while an already attached
         * (inner) range always wins.
         */
        private RuntimeDescriptor.Func jsonHelperSignatureOf(
                Symbol.FunctionSymbol functionSymbol, ClassDeclaration declaration) {
            try {
                return (RuntimeDescriptor.Func)
                    ContainerPayloadDescriptors.resultDescriptorOf(
                        functionSymbol.funcType());
            } catch (ContainerPayloadDescriptors.Defect defect) {
                throw defect.withRange(declaration.span().range());
            }
        }

        /**
         * Emits one generated {@code @jsonable} function: the body block
         * (parameter {@code BINDING_ALLOC}, parameter {@code
         * BINDING_LOAD}, the single JSON op — the parameter load
         * result wired as the JSON payload's operand — and, in the
         * production project session, the body's single {@code RETURN}
         * of the JSON op's result with its return boundary), then the
         * {@code CLOSURE_NEW} op publishing the fresh function identity
         * with the {@code LoweredFunction} record and the
         * {@code LoweredBody} registration through the registry seam,
         * and finally the buffered body ops flushed after the closure
         * (the closure-expression precedent's pinned unit order). The
         * JSON op's id is returned for the helper body.
         *
         * <p><b>The production session reuses the hoisted helper
         * context (R3).</b> When the class walk runs inside the
         * production project session, the helper name was hoisted
         * ({@link #hoistJsonHelperAllocs}): the body block, the function
         * id, the closure identity, the single return boundary, and the
         * reserved invocation identity come from that hoist, so the
         * declaration position publishes exactly one closure identity
         * per class and a reference resolves the same body. The
         * class-core-only seam keeps its landed generated shape (a fresh
         * identity and the three-op body).</p>
         */
        private OpId lowerJsonFunction(ClassDeclaration declaration,
                                       deal.semantic.ir.ClassLayout layout,
                                       RuntimeDescriptor.Func signature,
                                       RuntimeDescriptor parameterDescriptor,
                                       RuntimeDescriptor jsonResultType,
                                       FailurePolicyId jsonPolicy,
                                       boolean fromJson) {
            String helperName = declaration.name()
                + (fromJson ? "$fromJson" : "$toJson");
            FunctionContext helperContext = fullProgram && classCore && e7Calls
                ? moduleFunctionContexts.get(helperName) : null;
            BlockId bodyBlock = helperContext != null
                ? helperContext.bodyBlock : allocateBlock();
            FunctionId functionId = helperContext != null
                ? helperContext.functionId : ids.nextFunctionId(module, nextOrdinal++, 0);
            ValueId closureIdentity;
            BindingId helperBinding = null;
            if (helperContext != null) {
                FrameEntry hoisted = frameEntryOf(helperName);
                if (hoisted == null) {
                    throw new IllegalStateException("hoisted @jsonable helper "
                        + "registration missing for '" + helperName
                        + "' (producer defect)");
                }
                helperBinding = hoisted.cell().id;
                closureIdentity = functionIdentity.get(hoisted.incarnation());
                if (closureIdentity == null) {
                    throw new IllegalStateException("pre-assigned @jsonable helper "
                        + "identity missing for '" + helperName + "' (producer defect)");
                }
            } else {
                closureIdentity = ids.nextValueId(module, nextOrdinal++, 0);
            }
            List<SemanticOp> bodyOps = new ArrayList<>();
            emitTargets.push(bodyOps);
            blockStack.push(bodyBlock);
            OpId jsonOpId;
            try {
                // The parameter ALLOC at the body entry (the pinned
                // parameter model: DIRECT, mutable, generation 0, the
                // body-root block).
                BindingId parameterBinding = ids.nextBindingId(module, nextOrdinal++, 0);
                BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                    true, BindingProducer.BINDING_ALLOC, false);
                parameterBindings.add(parameterBinding);
                AnchorId allocAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                OpId allocOpId = ids.nextOpId(module, nextOrdinal++, 0);
                SourceOrigin allocOrigin = new SourceOrigin(sourceId,
                    toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC,
                    allocAnchor, null);
                emit(buildOp(allocOpId, SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(parameterBinding, bodyBlock, true,
                        cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                    null, null, FailurePolicyId.NO_DEAL_FAILURE, allocOrigin));

                // The parameter load: the generated body's only operand
                // producer.
                ValueId loadValue = ids.nextValueId(module, nextOrdinal++, 0);
                AnchorId loadAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                OpId loadOpId = ids.nextOpId(module, nextOrdinal++, 0);
                SourceOrigin loadOrigin = new SourceOrigin(sourceId,
                    toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC,
                    loadAnchor, null);
                emit(buildOp(loadOpId, SemanticOpKind.BINDING_LOAD,
                    new KindPayload.BindingLoadPayload(parameterBinding,
                        INITIAL_LOOP_GENERATION),
                    loadValue, parameterDescriptor, FailurePolicyId.NO_DEAL_FAILURE,
                    loadOrigin));

                // The single JSON op of the body: payload-referenced
                // operand wiring (the FIELD_READ precedent), the pinned
                // policy and result type, a SYNTHETIC origin at the class
                // declaration span.
                ValueId jsonResult = ids.nextValueId(module, nextOrdinal++, 0);
                AnchorId jsonAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                jsonOpId = ids.nextOpId(module, nextOrdinal++, 0);
                SourceOrigin jsonOrigin = new SourceOrigin(sourceId,
                    toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC,
                    jsonAnchor, null);
                KindPayload payload = fromJson
                    ? new KindPayload.JsonFromClassPayload(layout, loadValue)
                    : new KindPayload.JsonToClassPayload(loadValue, layout);
                emit(buildOp(jsonOpId,
                    fromJson ? SemanticOpKind.JSON_FROM_CLASS : SemanticOpKind.JSON_TO_CLASS,
                    payload, jsonResult, jsonResultType, jsonPolicy, jsonOrigin));

                if (helperContext != null) {
                    // The generated body's single transfer of the JSON
                    // op's result through its declaration-owned return
                    // boundary (R3): the helper's JSON result is the
                    // function's returned value, so a caller observes the
                    // landed JSON_FROM_CLASS/JSON_TO_CLASS semantics.
                    AnchorId returnAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                    OpId returnOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    SourceOrigin returnOrigin = new SourceOrigin(sourceId,
                        toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC,
                        returnAnchor, null);
                    ensureReturnBoundary(helperContext, jsonResult, declaration.span(),
                        returnOpId);
                    emit(buildOp(returnOpId, SemanticOpKind.RETURN,
                        new KindPayload.ReturnPayload(jsonResult,
                            helperContext.functionId, helperContext.invocationOpId(),
                            helperContext.returnBoundaryOpId),
                        null, null, FailurePolicyId.NO_DEAL_FAILURE, returnOrigin));
                    terminateBlock();
                }
            } finally {
                blockStack.pop();
                emitTargets.pop();
            }
            // The CLOSURE_NEW producer: the fresh function identity, the
            // LoweredFunction record, and the LoweredBody registration
            // through the registry seam (generated synthetics are
            // ordinary CLOSURE_NEW producers).
            AnchorId closureAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId closureOpId = ids.nextOpId(module, nextOrdinal++, 0);
            FunctionExecutionBinding.LoweredBody binding =
                new FunctionExecutionBinding.LoweredBody(functionId, bodyBlock);
            SourceOrigin closureOrigin = new SourceOrigin(sourceId,
                toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC,
                closureAnchor, currentParent());
            emit(buildOp(closureOpId, SemanticOpKind.CLOSURE_NEW,
                new KindPayload.ClosureNewPayload(functionId, signature, List.of(), binding),
                closureIdentity, signature, FailurePolicyId.NO_DEAL_FAILURE,
                closureOrigin));
            if (helperBinding != null) {
                // The declaration-position initialization of the hoisted
                // helper binding (the module-function shape): the closure
                // identity is the cell's value from here on, and the E7
                // publication publishes exactly that identity.
                emitUserNullOp(SemanticOpKind.BINDING_INIT,
                    new KindPayload.BindingInitPayload(helperBinding,
                        INITIAL_LOOP_GENERATION, closureIdentity),
                    declaration.span(), FailurePolicyId.NO_DEAL_FAILURE);
            }
            functions.put(functionId, new LoweredFunction(functionId, signature, List.of(),
                bodyBlock, helperName));
            registerDeclaredBody(new FunctionAllocationIdentity(closureIdentity.id()),
                functionId, bodyBlock);
            emitTarget().addAll(bodyOps);
            return jsonOpId;
        }

        /**
         * The exact declaring provider's facts of one class literal,
         * selected by the recorded contextual provider alias: the alias
         * names the import whose exact declaration supplied the literal's
         * construction context, the import's resolved module names the
         * provider, and the provider's declared-class construction entry
         * selects the occurrence's facts. {@code null} when the recorder
         * records no contextual imported provider for the literal (a
         * same-module or non-contextual construction) or when the
         * recorded provider carries no accumulated facts (the fail-closed
         * deferral path).
         */
        private SharedFactoryFacts recordedProviderFacts(ObjectLiteralExpr literal,
                                                         ClassId classId) {
            if (recordedClassLiteralProviders.isEmpty()) {
                return null;
            }
            String alias = recordedClassLiteralProviders.get(literal.span().range());
            if (alias == null) {
                return null;
            }
            for (ResolvedImport importFact : moduleImports) {
                if (!importFact.alias().equals(alias)) {
                    continue;
                }
                Map<ClassId, deal.semantic.ir.ClassFactoryId> byClass =
                    providerConstructionEntries.get(importFact.resolvedModuleId());
                if (byClass == null) {
                    return null;
                }
                deal.semantic.ir.ClassFactoryId entry = byClass.get(classId);
                if (entry == null) {
                    return null;
                }
                return providerFactoryFacts.get(entry);
            }
            return null;
        }

        private ValueId lowerClassLiteral(ObjectLiteralExpr literal, ValueId slot) {
            Type type = checkedType(literal);
            if (!(type instanceof Type.Class classType)) {
                throw new ConstructUnlowered("class literal of non-class checked type "
                    + typeName(type) + " (a class-typed ObjectLiteralExpr must carry a "
                    + "Type.Class fact)", literal.span());
            }
            ClassId classId = new ClassId(
                DescriptorService.semanticModulePath(classType.identity()), classType.name());

            // The checker's recorded contextual declaration first: the
            // literal's own construction context (the enclosing
            // annotation's declaration, the callee parameter's declared
            // provider, or the declared return annotation's provider)
            // names the declaration the literal was laid out by, so a
            // same-named carrier of the same identity (a harness-synthesized
            // pair) never collapses into the unit-local layout or a first
            // alias. The compiler-owned builtin Error class carries the
            // builtin synthesis as its
            // symbol and constructs through its registration seed
            // (BUILTIN_ERROR), never through a module declaration.
            Symbol.ClassSymbol exact = ClassId.ERROR.equals(classId) ? null
                : exactReceiverDeclaration(literal);
            SharedFactoryFacts sharedFacts = null;
            deal.semantic.ir.ClassLayout layout = null;
            if (exact != null && classType.identity().equals(exact.identity())) {
                String declaring = exact.modulePath();
                if (declaring == null || declaring.isEmpty()
                        || declaring.equals(module.path())) {
                    // The unit's own declaration is the exact one: its own
                    // lowered layout supplies the construction.
                    layout = classLayouts.get(classId);
                    if (layout == null) {
                        throw new ConstructUnlowered("class literal of "
                            + classId + " is attributed to this module's own"
                            + " declared class, but the unit carries no lowered"
                            + " layout for it (a producer defect)",
                            literal.span());
                    }
                } else {
                    // The context names another module's declaration: only
                    // that declaring occurrence's registered construction
                    // entry and shared facts construct it, never the
                    // unit-local mask of a same-named sibling.
                    deal.semantic.ir.ClassFactoryId entry =
                        providerEntryOf(declaring, classId);
                    sharedFacts = entry == null ? null
                        : providerFactoryFacts.get(entry);
                    if (sharedFacts == null) {
                        if (ambiguousFieldIdentity(classId)) {
                            throw new ConstructUnlowered("class literal of "
                                + classId + " is attributed to the declaration of"
                                + " module '" + declaring + "', but the project"
                                + " carries no registered construction entry and"
                                + " shared facts for it (a required provider fact is"
                                + " missing; the exact declaration is never repaired"
                                + " by a first alias)", literal.span());
                        }
                        // A single declaration of the canonical identity:
                        // the identity-keyed route resolves it exactly (a
                        // symbol module-path spelling that differs from the
                        // session's, an unexported declaration the interface
                        // index does not carry, or a declaration-typed owner
                        // whose registration seed supplies the layout).
                        sharedFacts = recordedProviderFacts(literal, classId);
                        layout = classLayouts.get(classId);
                        if (sharedFacts == null && layout == null) {
                            sharedFacts = sharedFactories.get(classId);
                        }
                    }
                }
            } else {
                // No recorded construction context: the literal's
                // expectation named no declaration (a function-typed
                // callee value, an inferred position) or the context was
                // not resolvable. A multiple-declaration identity has no
                // selectable declaration then, so it fails closed even
                // when the unit carries a local layout (the unit-local
                // mask must not select the construction's declaration);
                // a single-declaration identity resolves by the identity-
                // keyed route.
                if (ambiguousFieldIdentity(classId)) {
                    throw new ConstructUnlowered("class literal of "
                        + classId + " resolves more than one declared provider"
                        + " sharing the canonical class identity (same-directory"
                        + " sibling declarations): no recorded construction"
                        + " context selects one and a first alias is never"
                        + " selected", literal.span());
                }
                // The recorded contextual provider alias (the identity-keyed
                // context route) names the imported declaration's
                // construction entry.
                sharedFacts = recordedProviderFacts(literal, classId);
                layout = classLayouts.get(classId);
                if (sharedFacts == null && layout == null) {
                    sharedFacts = sharedFactories.get(classId);
                }
            }
            ConstructionProvider constructionProvider = ConstructionProvider.LOCAL;
            deal.semantic.ir.ClassFactoryId classFactoryRef = null;
            if (sharedFacts != null) {
                layout = sharedFacts.layout();
                constructionProvider = ConstructionProvider.SHARED_FACTORY;
                classFactoryRef = sharedFacts.interfaceEntry().constructionEntry();
            } else if (layout == null) {
                ClassRegistrationSeeds.ClassRegistration seed =
                    registrationSeeds.registrationFor(classId);
                if (seed == null) {
                    throw new RetainedAbiDeferred(classId.text(), literal.span());
                }

                layout = seed.layout();
                constructionProvider = seed.owner();
            }


            List<KindPayload.ProvidedField> providedFields = new ArrayList<>();
            Map<String, ValueId> providedValues = new LinkedHashMap<>();
            Map<String, ExpressionNode> providedExpressions = new LinkedHashMap<>();
            Map<String, RuntimeDescriptor> fieldDescriptors = new LinkedHashMap<>();
            for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
                fieldDescriptors.put(field.name(), field.descriptor());
            }
            for (Property property : literal.properties()) {
                RuntimeDescriptor fieldDescriptor =
                    fieldDescriptors.get(property.name());
                ValueId value;
                if (fieldDescriptor != null
                        && positionAdapts(property.value(), fieldDescriptor)) {
                    // A supplied class field is a statically typed
                    // function-value position: exactly one arity-extension
                    // adapter over the supplied value, so the
                    // CLASS_LITERAL_FIELD cell validates the field's declared
                    // descriptor exactly and the instance stores the adapted
                    // value (docs/spec-v1.3.md Function type compatibility;
                    // Arity extension adapter).
                    value = lowerPositionAdapter(property.value(),
                        (RuntimeDescriptor.Func) fieldDescriptor,
                        () -> lowerExpression(property.value()));
                } else {
                    value = lowerExpression(property.value());
                }
                providedFields.add(new KindPayload.ProvidedField(property.name(), value));
                providedValues.put(property.name(), value);
                providedExpressions.put(property.name(), property.value());
            }
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);

            List<KindPayload.FieldBoundary> boundaries = new ArrayList<>();
            List<SemanticOp> children = new ArrayList<>();
            for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
                ValueId provided = providedValues.get(field.name());
                if (provided != null) {
                    SemanticOp child = buildFieldBoundary(BoundaryKind.CLASS_LITERAL_FIELD,
                        field.descriptor(), provided, literal.span(), opId);
                    recordPositionAdapterAt(provided, child.opId(),
                        BoundaryKind.CLASS_LITERAL_FIELD);
                    boundaries.add(new KindPayload.FieldBoundary(field.name(),
                        BoundaryKind.CLASS_LITERAL_FIELD, child.opId()));
                    children.add(child);
                    if (creationRuleAnalysis) {
                        recordPositionClassification(BoundaryKind.CLASS_LITERAL_FIELD,
                            producedType(providedExpressions.get(field.name())),
                            field.descriptor(), child.opId());
                    }
                } else if (field.required()) {
                    throw new ConstructUnlowered("omitted required-present field '"
                        + field.name() + "' of class " + classId + " (producer defect)",
                        literal.span());
                }
            }
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(literal.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.CLASS_NEW,
                new KindPayload.ClassNewPayload(classId, layout, providedFields,
                    constructionProvider, classFactoryRef, boundaries),
                result, ContainerPayloadDescriptors.resultDescriptorOf(classType),
                FailurePolicyId.CLASS_CONSTRUCTION, origin));
            for (SemanticOp child : children) {
                emit(child);
            }
            return result;
        }

        private SemanticOp buildFieldBoundary(BoundaryKind kind, RuntimeDescriptor descriptor,
                                              ValueId input, Span span, OpId classNewOpId) {
            return buildNullOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, descriptor, input,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                span, descriptorKindPolicy(descriptor), SourceOriginKind.SYNTHETIC,
                classNewOpId);
        }

        private ClassId classIdOf(ClassDeclaration declaration) {
            SymbolTable scope = currentCheckerScope();
            Symbol symbol = scope == null ? null : scope.resolve(declaration.name());
            if (!(symbol instanceof Symbol.ClassSymbol classSymbol)) {
                throw new ConstructUnlowered("class declaration '"
                    + declaration.name() + "' has no checked ClassSymbol (a missing "
                    + "checker fact is a producer defect)", declaration.span());
            }
            return new ClassId(
                DescriptorService.semanticModulePath(classSymbol.identity()),
                classSymbol.name());
        }

        /**
         * The class descriptor of a locally declared class (the factory's
         * result type): the checked {@code Type.Class} of the checker's
         * {@code ClassSymbol} rendered through the single
         * {@link DescriptorService} producer — never a hand-built
         * descriptor (the producer-singularity rule).
         */
        private RuntimeDescriptor classDescriptorOf(String name) {
            SymbolTable scope = currentCheckerScope();
            Symbol symbol = scope == null ? null : scope.resolve(name);
            if (!(symbol instanceof Symbol.ClassSymbol classSymbol)) {
                throw new ConstructUnlowered("class declaration '" + name
                    + "' has no checked ClassSymbol (a missing checker fact is a "
                    + "producer defect)");
            }
            try {
                return DescriptorService.describe(
                    deal.types.Types.classType(classSymbol.name(),
                        classSymbol.identity(),
                        deal.types.Types.providerModule(classSymbol.modulePath())));
            } catch (DescriptorService.Defect defect) {
                throw new ConstructUnlowered("class '" + name
                    + "' of unrepresentable checked type (" + defect.getMessage() + ")");
            }
        }

        private Type fieldTypeOf(deal.ast.TypeNode node) {
            return switch (node) {
                case deal.ast.NamedType named -> resolveNamedFieldType(named);
                case deal.ast.QualifiedType qualified -> resolveQualifiedFieldType(qualified);
                case deal.ast.ArrayType array -> deal.types.Types.array(
                    fieldTypeOf(array.elementType()));
                case deal.ast.NullableType nullable -> deal.types.Types.withNullish(
                    fieldTypeOf(nullable.innerType()), nullable.undefinedMember(), true);
                case deal.ast.FunctionType function -> {
                    List<Type> paramTypes = new ArrayList<>();
                    for (deal.ast.FunctionTypeParam parameter : function.params()) {
                        paramTypes.add(fieldTypeOf(parameter.type()));
                    }
                    yield new Type.Func(List.copyOf(paramTypes),
                        fieldTypeOf(function.returnType()), function.isAsync());
                }
            };
        }

        /** The checker-fact mirror of {@code NameResolver.resolveNamedType}. */
        private Type resolveNamedFieldType(deal.ast.NamedType named) {
            String name = named.name();
            return switch (name) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "Record<string, unknown>" -> Type.Table.INSTANCE;
                case "undefined" -> Type.Undefined.INSTANCE;
                case "unknown" -> Type.Unknown.INSTANCE;
                case "void" -> Type.Void.INSTANCE;
                case "Error" -> deal.types.Types.classType("Error",
                    deal.checker.NameResolver.intrinsicErrorIdentity());
                default -> {
                    Symbol symbol = resolveScopeName(name);
                    if (symbol instanceof Symbol.ClassSymbol classSymbol) {
                        yield deal.types.Types.classType(classSymbol.name(),
                            classSymbol.identity(),
                            deal.types.Types.providerModule(
                                classSymbol.modulePath()));
                    }
                    throw new ConstructUnlowered("class-field type annotation names unknown "
                        + "type '" + name + "' (a checked module cannot reach this arm — "
                        + "a fact defect, never an invented type)", named.span());
                }
            };
        }

        /** The checker-fact mirror of {@code NameResolver.resolveQualifiedType}. */
        private Type resolveQualifiedFieldType(deal.ast.QualifiedType qualified) {
            Symbol symbol = resolveScopeName(qualified.moduleName());
            if (!(symbol instanceof Symbol.ModuleSymbol moduleSymbol)) {
                throw new ConstructUnlowered("class-field type annotation qualifies unknown "
                    + "module '" + qualified.moduleName() + "' (a checked module cannot "
                    + "reach this arm — a fact defect)", qualified.span());
            }
            Type exportType = moduleSymbol.exports().get(qualified.typeName());
            if (exportType == null) {
                throw new ConstructUnlowered("class-field type annotation names unknown "
                    + "export '" + qualified.typeName() + "' of module '"
                    + qualified.moduleName() + "' (a fact defect)", qualified.span());
            }
            return exportType;
        }

        /** Resolves one annotation name against the current checker scope. */
        private Symbol resolveScopeName(String name) {
            SymbolTable scope = currentCheckerScope();
            return scope == null ? null : scope.resolve(name);
        }

        public deal.semantic.ir.ClassFactoryRegistry factoryRegistry() {
            if (producedClassFactories != null) {
                return producedClassFactories;
            }
            return computeFactoryRegistry();
        }

        /** The fresh construction-entry registry of the live session state. */
        private deal.semantic.ir.ClassFactoryRegistry computeFactoryRegistry() {
            return new deal.semantic.ir.ClassFactoryRegistry(
                new LinkedHashMap<>(factoryRegistry));
        }

        /**
         * The binding walk's {@code let} arm: one ALLOC (generation 0,
         * {@code DIRECT}, mutable — the closed default cell kind for
         * ordinary declarations), the initializer's value ops through the
         * value-expression seam, the declaration boundary for annotated
         * declarations ({@code VARIABLE_DECLARATION} with the declared
         * descriptor and the descriptor-kind policy; inferred
         * declarations carry no boundary and init directly — the Binding
         * lifecycle contract shape), then exactly one BINDING_INIT. The
         * binding is registered before the initializer lowers (the
         * checker defines the name before walking the initializer), so
         * pre-init stores resolve the generation-0 incarnation.
         */
        private void lowerBindingVarDecl(VariableDeclaration decl) {
            BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
            BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, currentBlock(), BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC, false);
            // v1.2 declaration order: the initializer is lowered before
            // the binding is registered, so a self/forward reference
            // resolves against the enclosing binding (never against the
            // cell being initialized) — the checker's non-hoisting rule.
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(binding, currentBlock(), true,
                    cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            lowerDeclaredInitializer(decl, binding, incarnation);
        }


        private void lowerDeclaredInitializer(VariableDeclaration decl, BindingId binding,
                                              BindingCoreIncarnation incarnation) {
            if ((shapeMapAnalysis || e7Calls) && declaredTypeOf(decl) instanceof Type.Func) {
                Type sourceType = producedType(decl.initializer());
                if (AdapterCreationRule.variableDisposition(sourceType,
                        declaredTypeOf(decl)) == AdapterCreationRule.Disposition.ADAPT) {
                    // The shape-map child's adapted declaration (B6-B9):
                    // exactly one FUNCTION_ADAPT op with its closed-map
                    // mode and per-mode payload from birth, produced
                    // before the position's boundary and wired as the
                    // direct input of exactly the position's own
                    // VARIABLE_DECLARATION boundary chain (an inferred
                    // declaration's result feeds its BINDING_INIT
                    // directly).
                    lowerAdaptedDeclInitializer(decl, binding, incarnation);
                    return;
                }
            }
            ValueId value = lowerExpression(decl.initializer());
            registerBinding(decl.name(), binding, incarnation);
            OpId declarationBoundary = null;
            if (decl.typeAnnotation().isPresent()) {
                RuntimeDescriptor descriptor = variableBoundaryDescriptor(decl);
                FailurePolicyId boundaryPolicy = descriptor instanceof RuntimeDescriptor.Func
                    ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
                // The materialization site's origin is the declared type
                // annotation's own span (M8 item 1: the retained
                // backends' declared-origin projection and the corpus
                // pins' coordinate), never the declaration's `let` span.
                declarationBoundary = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_DECLARATION,
                        descriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    decl.typeAnnotation().get().span(), boundaryPolicy,
                    SourceOriginKind.SYNTHETIC, null);
            }
            if (closureCore && descriptorOf(value) instanceof RuntimeDescriptor.Func) {
                // The initializer's function identity is the cell's
                // current value identity (loads preserve allocation
                // identity; R-FUNCTION-BINDING holds by construction).
                functionIdentity.put(incarnation, value);
            }
            OpId initOp = emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(binding, INITIAL_LOOP_GENERATION, value),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            if (creationRuleAnalysis) {
                classifyVarDeclPosition(decl, value, declarationBoundary, initOp);
            }
        }

        /**
         * The declared-type descriptor of one annotated let boundary: the
         * single {@link ContainerPayloadDescriptors} bridge over the
         * checked declared type. A corrupted checked type fails closed at
         * the declared type annotation's own range (D2) — the same span
         * the VARIABLE_DECLARATION boundary carries — while an already
         * attached (inner) range always wins.
         */
        private RuntimeDescriptor variableBoundaryDescriptor(VariableDeclaration decl) {
            try {
                return ContainerPayloadDescriptors.resultDescriptorOf(declaredTypeOf(decl));
            } catch (ContainerPayloadDescriptors.Defect defect) {
                throw defect.withRange(decl.typeAnnotation().get().span().range());
            }
        }

        /**
         * The VALUE arm's completed creation payload: the resolved
         * operand and the payload proof (present iff the operand is the
         * single proved binding load).
         */
        private record AdapterValueSource(ValueId operand,
                                          BindingImmutabilityProof proof) {
        }

        /**
         * Completes the adapted source's VALUE arm from the checked
         * shape (B7): a first-class intrinsic function value resolves
         * the seeded intrinsic identity; every other source lowers its
         * expression, and a binding reference requires the recorded
         * proof.
         */
        private AdapterValueSource completeAdapterValueSource(
                ExpressionNode value, AdapterShapeMap.SourceShape shape,
                Optional<BindingImmutabilityProof> proof) {
            ValueId operand = lowerExpression(value);
            BindingImmutabilityProof payloadProof = null;
            if (shape == AdapterShapeMap.SourceShape.BINDING_REFERENCE) {
                // B7 arm (b): the single proved BINDING_LOAD at the
                // proof's generation — the payload proof must name that
                // binding/generation (the load emission resolves the
                // dominant incarnation, the same resolution the proof
                // fact names).
                payloadProof = proof.orElseThrow(() ->
                    new IllegalStateException("the closed shape map selects "
                        + "VALUE over a binding only with the recorded proof "
                        + "(producer defect)"));
            }
            return new AdapterValueSource(operand, payloadProof);
        }

        /**
         * The SHARED_CELL arm's source reference (B8): resolves the
         * dominant incarnation at the adaptation position, registers
         * the B2 capture reference, and returns the shared-cell
         * reference.
         */
        private AdaptSourceRef.SharedCell lowerAdapterSharedCellSource(
                ExpressionNode value) {
            IdentifierExpr identifier = (IdentifierExpr) value;
            FrameResolution resolution = resolveFrame(identifier.name());
            if (resolution == null) {
                throw new ConstructUnlowered("the SHARED_CELL source '"
                    + identifier.name() + "' is not a declared binding of the "
                    + "walk's environment (B7: a binding source resolves its "
                    + "dominant incarnation at the adaptation position)", identifier.span());
            }
            // The B2 capture arm: the SharedCell source reference makes
            // the incarnation a shared cell (its cell is read per
            // invocation, so later writes are observed); inside a
            // detached body the enclosing closure must also capture the
            // binding (the detaching chain).
            maybeRegisterCapture(identifier.name(), resolution);
            cellKinds.registerCaptureReference(resolution.entry().incarnation());
            return new AdaptSourceRef.SharedCell(
                resolution.entry().cell().id,
                resolution.entry().incarnation().generation());
        }


        private void lowerAdaptedDeclInitializer(VariableDeclaration decl, BindingId binding,
                                                 BindingCoreIncarnation incarnation) {
            Type.Func sourceType = (Type.Func) producedType(decl.initializer());
            Type.Func targetType = (Type.Func) declaredTypeOf(decl);
            RuntimeDescriptor.Func sourceSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(sourceType);
            RuntimeDescriptor.Func targetSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(targetType);
            Optional<BindingImmutabilityProof> proof = sourceProofOf(decl.initializer());
            AdapterShapeMap.SourceShape shape = AdapterShapeMap.sourceShapeAtPosition(
                decl.initializer());
            CaptureMode mode = AdapterShapeMap.selectMode(shape, proof);
            ThunkSource thunk = null;
            ValueId operand = null;
            BindingImmutabilityProof payloadProof = null;
            AdaptSourceRef sourceRef;
            List<ValueId> operands;
            List<RuntimeDescriptor> operandTypes;
            switch (mode) {
                case VALUE -> {
                    AdapterValueSource source = completeAdapterValueSource(
                        decl.initializer(), shape, proof);
                    operand = source.operand();
                    payloadProof = source.proof();
                    sourceRef = new AdaptSourceRef.Value(operand);
                    operands = List.of(operand);
                    operandTypes = List.of(sourceSignature);
                }
                case SHARED_CELL -> {
                    sourceRef = lowerAdapterSharedCellSource(
                        decl.initializer());
                    operands = List.of();
                    operandTypes = List.of();
                }
                case REEVALUATE_THUNK -> {
                    thunk = lowerThunkSource(decl.initializer());
                    sourceRef = thunkSourceRef(thunk);
                    operands = List.of();
                    operandTypes = List.of();
                }
                default -> throw new IllegalStateException("unreachable mode " + mode);
            }
            // The binding enters the walk's environment after the
            // initializer's own lowering, exactly like the direct arm
            // (the checker's non-hoisting rule): the adapted declaration
            // must register its name too, so every later reference to it
            // resolves (ISSUE-0717 regression fix).
            registerBinding(decl.name(), binding, incarnation);
            EmittedAdapter adapter = emitFunctionAdapt(mode, sourceRef, sourceSignature,
                targetSignature, payloadProof, operands, operandTypes, decl.span());
            ValueId adapterIdentity = adapter.identity();
            OpId declarationBoundary = null;
            if (decl.typeAnnotation().isPresent()) {
                // The adapted arm's materialization site carries the same
                // declared-annotation origin as the direct arm.
                declarationBoundary = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_DECLARATION,
                        targetSignature, adapterIdentity,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    decl.typeAnnotation().get().span(), FailurePolicyId.FUNCTION_SIGNATURE,
                    SourceOriginKind.SYNTHETIC, null);
            }
            // The cell's tracked function identity becomes the adapter
            // identity — loads, reads, argument passing, and returns
            // preserve it (the adapter is an ordinary function value
            // after the commit).
            functionIdentity.put(incarnation, adapterIdentity);
            OpId initOp = emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(binding, INITIAL_LOOP_GENERATION,
                    adapterIdentity),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            OpId wiringTarget = declarationBoundary != null ? declarationBoundary : initOp;
            recordAdapterEmission(adapter.opId(), adapterIdentity, mode, sourceRef,
                sourceSignature, targetSignature, payloadProof, wiringTarget,
                BoundaryKind.VARIABLE_DECLARATION);
            if (thunk != null) {
                emitTarget().addAll(thunk.ops());
            }
            if (creationRuleAnalysis) {
                classifyVarDeclPosition(decl, adapterIdentity, declarationBoundary, initOp);
            }
        }

        /**
         * One detached thunk-block source of the shape-map child
         * (B8): the fresh block wrapping the source expression's
         * already-lowered ops in source order plus the collected
         * captures (the thunk's free bindings in first-reference
         * order).
         */
        private record ThunkSource(BlockId blockId, List<SemanticOp> ops,
                                   List<CapturedCell> captures) {
        }

        /**
         * Walks the source expression into a fresh detached thunk block
         * (B8): the expression's already-lowered ops emit into a buffer
         * whose members are the thunk block (the single emission path of
         * the walk's thunk production), the open-walk capture collector
         * records the thunk's free bindings in first-reference order
         * (the same registration surface the closure child uses — the
         * shape-map child's thunk capture arm joins the B2 cell-kind
         * derivation through it), and zero evaluation happens at the
         * creation site (the ops execute only when E7's protocol
         * re-executes the thunk).
         *
         */
        private ThunkSource lowerThunkSource(ExpressionNode source) {
            BlockId thunkBlock = allocateBlock();
            List<SemanticOp> thunkOps = new ArrayList<>();
            List<CapturedCell> captured = new ArrayList<>();
            emitTargets.push(thunkOps);
            // The thunk block is detached and declares no bindings of its
            // own: an empty binding frame is pushed so every reference
            // inside the walk resolves outside the thunk's own scope
            // chain and registers as a thunk capture (B9 R3 — the
            // capturedBindings pin the thunk's free bindings with their
            // generations). Enclosing detached-body walks keep receiving
            // the same references through the detaching chain (B9 R2).
            pushBindingFrame();
            captureBorders.push(bindingScopes.size());
            captureCollectors.push(captured);
            blockStack.push(thunkBlock);
            try {
                lowerExpression(source);
            } finally {
                blockStack.pop();
                captureCollectors.pop();
                captureBorders.pop();
                popBindingFrame();
                emitTargets.pop();
            }
            if (thunkOps.isEmpty()) {
                throw new IllegalStateException("the thunk source expression produced no "
                    + "op (producer defect)");
            }
            return new ThunkSource(thunkBlock, thunkOps, captured);
        }

        /**
         * Builds the closed {@link AdaptSourceRef.Thunk} of one thunk
         * source (B8): the generation-pinned {@code capturedBindings}
         * derive from the collected captures in first-reference order,
         * and the thunk builder is the single production path of
         * {@link AdaptSourceRef.Thunk} records (fail closed on a
         * malformed source op list).
         *
         */
        private AdaptSourceRef.Thunk thunkSourceRef(ThunkSource thunk) {
            List<BindingGeneration> pinned = new ArrayList<>();
            for (CapturedCell capture : thunk.captures()) {
                pinned.add(new BindingGeneration(capture.cell().id,
                    capture.incarnation().generation()));
            }
            return AdapterThunkConstruction.buildThunk(thunk.blockId(), thunk.ops(), pinned);
        }

        /**
         * Emits exactly one {@code FUNCTION_ADAPT} op carrying its
         * closed-map mode and per-mode payload from birth (B7/B8): a
         * fresh adapter allocation identity per creation (stable for the
         * run), the source/target signature pair of the adaptation
         * candidate, exactly the operand set of the mode (one operand
         * for VALUE; zero for SHARED_CELL and REEVALUATE_THUNK — zero
         * evaluation at creation), policy {@code NO_DEAL_FAILURE}
         * (creation is infallible), zero {@code BOUNDARY} children (the
         * adapter adds no second boundary), and the single
         * {@code AdapterBinding} registration through the registry
         * child's seam keyed by the adapter identity.
         *
         */
        private EmittedAdapter emitFunctionAdapt(CaptureMode mode, AdaptSourceRef sourceRef,
                                                 RuntimeDescriptor.Func sourceSignature,
                                                 RuntimeDescriptor.Func targetSignature,
                                                 BindingImmutabilityProof proof,
                                                 List<ValueId> operands,
                                                 List<RuntimeDescriptor> operandTypes,
                                                 Span span) {
            ValueId adapterIdentity = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.USER, anchor, currentParent());
            KindPayload.FunctionAdaptPayload payload = new KindPayload.FunctionAdaptPayload(
                sourceSignature, targetSignature, mode, sourceRef, proof);
            emit(buildOp(opId, SemanticOpKind.FUNCTION_ADAPT, payload, adapterIdentity,
                targetSignature, operands, operandTypes, FailurePolicyId.NO_DEAL_FAILURE,
                origin));
            registry.registerAdapter(new FunctionAllocationIdentity(adapterIdentity.id()),
                opId, mode, sourceRef, sourceSignature, targetSignature);
            return new EmittedAdapter(opId, adapterIdentity);
        }

        /** One emitted {@code FUNCTION_ADAPT} op (its op id plus its fresh identity). */
        private record EmittedAdapter(OpId opId, ValueId identity) {
        }

        /**
         * The recorded creation facts of the position adapters this walk
         * created, keyed by the adapter identity: a call-argument position
         * builds its own boundary after the argument list, so the
         * emission fact is recorded when that boundary is built (see
         * {@link #recordPositionAdapterAt}).
         */
        private final Map<Long, PositionAdapterFacts> positionAdapterFacts =
            new LinkedHashMap<>();

        /** One position adapter's creation facts (the emission-record inputs). */
        private record PositionAdapterFacts(OpId adaptOpId, CaptureMode mode,
                                            AdaptSourceRef sourceRef,
                                            RuntimeDescriptor.Func sourceSignature,
                                            RuntimeDescriptor.Func targetSignature,
                                            BindingImmutabilityProof proof) {
        }

        /**
         * True exactly when the given statically typed function-value
         * position adapts: the position adaptation runs (the E7 call and
         * project lowering), the position's checked target descriptor is
         * a function descriptor, the source expression produces a function
         * value ({@link #producedType(ExpressionNode)}) and the pair
         * re-derives as assignable-but-not-exact — identical async marker
         * and return type, {@code M < N} with exactly equal leading M
         * parameter types (docs/spec-v1.3.md Function type compatibility).
         * Any other pair is the position's own typed-boundary check's
         * (exact pass, or the recorded E8010 rejection).
         */
        private boolean positionAdapts(ExpressionNode value, RuntimeDescriptor target) {
            if (!(shapeMapAnalysis || e7Calls)
                    || !(target instanceof RuntimeDescriptor.Func targetSignature)) {
                return false;
            }
            if (!(producedType(value) instanceof Type.Func sourceType)) {
                return false;
            }
            // A binding whose tracked identity is already an adapter's is an
            // ordinary function value of its declared signature
            // (docs/spec-v1.3.md Function type compatibility): runtime
            // provenance as an adapter is not part of the type, so a
            // position whose target signature is wider adapts it like any
            // other compatible function value. The adapter protocol
            // resolves such a source through its own published invocation
            // at the same leading-M projection, never by stacking a second
            // unresolved wrapper.
            return ContainerPayloadDescriptors.resultDescriptorOf(sourceType)
                    instanceof RuntimeDescriptor.Func sourceSignature
                && AdapterCreationRule.assignableButNotExact(sourceSignature,
                    targetSignature);
        }

        /**
         * Emits exactly one {@code FUNCTION_ADAPT} op for one statically
         * typed function-value position (the spec's arity extension
         * adapter) over the position's own value: the closed position-mode
         * map selects the mode from checker facts (a binding reference
         * reads its dominant incarnation's cell per invocation; every
         * other source is the position's materialized value, evaluated
         * exactly once at its source position), and the adapter's result is
         * the value the position's own boundary validates and the consumer
         * observes. The adapter projects its leading M arguments, so the
         * ignored target parameters are never forwarded. The source shape is
         * the position's own classification: a lexically shadowing local,
         * parameter, enclosing-block or module-level binding precedes the
         * compiler intrinsics (docs/spec-v1.3.md §Name resolution), so the
         * adapted operand is the position's own value — the shadowing binding
         * for a shadowed {@code int}/{@code number}/{@code bytes} name, the
         * seeded intrinsic identity only for a genuinely unshadowed intrinsic
         * at this position.
         */
        private ValueId lowerPositionAdapter(ExpressionNode value,
                                             RuntimeDescriptor.Func targetSignature,
                                             java.util.function.Supplier<ValueId> lower) {
            return lowerPositionAdapter(value, targetSignature, lower, false);
        }

        private ValueId lowerPositionAdapter(ExpressionNode value,
                                             RuntimeDescriptor.Func targetSignature,
                                             java.util.function.Supplier<ValueId> lower,
                                             boolean materializedAssignment) {
            Type.Func sourceType = (Type.Func) producedType(value);
            RuntimeDescriptor.Func sourceSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(sourceType);
            Optional<BindingImmutabilityProof> proof = sourceProofOf(value);
            AdapterShapeMap.SourceShape shape = AdapterShapeMap.sourceShapeAtPosition(value);
            CaptureMode mode = materializedAssignment ? CaptureMode.VALUE
                : AdapterShapeMap.selectPositionMode(shape, proof);
            AdaptSourceRef sourceRef;
            List<ValueId> operands;
            List<RuntimeDescriptor> operandTypes;
            BindingImmutabilityProof payloadProof = null;
            if (mode == CaptureMode.SHARED_CELL) {
                // The binding arm: zero evaluation and zero loads at
                // creation; the adapter reads the dominant incarnation's
                // cell per invocation, exactly the declaration and
                // assignment positions' arm.
                sourceRef = lowerAdapterSharedCellSource(value);
                operands = List.of();
                operandTypes = List.of();
            } else {
                ValueId operand = lower.get();
                if (shape == AdapterShapeMap.SourceShape.BINDING_REFERENCE && !materializedAssignment) {
                    payloadProof = proof.orElseThrow(() ->
                        new IllegalStateException("the closed shape map selects VALUE "
                            + "over a binding only with the recorded proof (producer "
                            + "defect)"));
                }
                sourceRef = new AdaptSourceRef.Value(operand);
                operands = List.of(operand);
                operandTypes = List.of(sourceSignature);
            }
            EmittedAdapter adapter = emitFunctionAdapt(mode, sourceRef, sourceSignature,
                targetSignature, payloadProof, operands, operandTypes, value.span());
            positionAdapterFacts.put(adapter.identity().id(), new PositionAdapterFacts(
                adapter.opId(), mode, sourceRef, sourceSignature, targetSignature,
                payloadProof));
            return adapter.identity();
        }

        /**
         * Records one position adapter's emission fact at the position's
         * own boundary (its single consumer): the shaped fact surface's one
         * record per emitted adapter, in emission order. A value that is no
         * position adapter records nothing.
         */
        private void recordPositionAdapterAt(ValueId input, OpId boundaryOpId,
                                             BoundaryKind boundaryKind) {
            PositionAdapterFacts facts = positionAdapterFacts.remove(input.id());
            if (facts == null) {
                return;
            }
            recordAdapterEmission(facts.adaptOpId(), input, facts.mode(), facts.sourceRef(),
                facts.sourceSignature(), facts.targetSignature(), facts.proof(),
                boundaryOpId, boundaryKind);
        }

        /**
         * One call-argument position: a statically typed parameter position
         * whose argument is either the argument's own lowering
         * ({@code asCallArgument} keeps the declared-callee contextual-read
         * handling) or exactly one arity-extension adapter over it. The
         * adapter is created at the argument's own position, so the
         * argument's evaluation order and its once-only effects are
         * unchanged. The position is the callee's checked parameter
         * descriptor — the same descriptor a host-declared parameter cell
         * carries — so a compatible shorter DEAL function value adapts
         * before a host crossing exactly as it does at a same-module,
         * cross-module or stdlib parameter; the host parameter cell then
         * validates the adapter's exact target signature, and a dynamic
         * host- or table-produced value keeps that exact check.
         */
        private ValueId lowerParameterArgument(CallExpr call, int index,
                                               boolean asCallArgument) {
            ExpressionNode argument = call.args().get(index);
            RuntimeDescriptor target = callParameterDescriptor(call, index);
            if (target == null || !positionAdapts(argument, target)) {
                return asCallArgument ? lowerCallArgument(argument) : lowerExpression(argument);
            }
            return lowerPositionAdapter(argument, (RuntimeDescriptor.Func) target,
                () -> asCallArgument ? lowerCallArgument(argument)
                    : lowerExpression(argument));
        }

        /**
         * The checked callee signature's parameter descriptor at the given
         * argument index — the checker's own parameter type for the
         * position (the type every argument's assignability was decided
         * against) — or {@code null} when the position carries no
         * function-typed parameter descriptor.
         */
        private RuntimeDescriptor callParameterDescriptor(CallExpr call, int index) {
            Type calleeType = checkedType(call.callee());
            if (!(calleeType instanceof Type.Func)
                    || !(ContainerPayloadDescriptors.resultDescriptorOf(calleeType)
                        instanceof RuntimeDescriptor.Func signature)
                    || index >= signature.paramTypes().size()) {
                return null;
            }
            return signature.paramTypes().get(index);
        }

        /**
         * Records one adapter emission fact (the shape-map child's fact
         * surface): the emitted op's identity, the fresh adapter
         * allocation identity, the closed-map mode and source reference
         * (the payload from birth), the signatures, the proof, and the
         * consumed wiring point (the adapted position's own boundary
         * op or the inferred declaration's {@code BINDING_INIT}).
         */
        private void recordAdapterEmission(OpId adaptOpId, ValueId adapterIdentity,
                                           CaptureMode mode, AdaptSourceRef sourceRef,
                                           RuntimeDescriptor.Func sourceSignature,
                                           RuntimeDescriptor.Func targetSignature,
                                           BindingImmutabilityProof proof,
                                           OpId wiringTargetOpId,
                                           BoundaryKind wiringBoundaryKind) {
            shapeMapEmissions.add(new AdapterEmission(adaptOpId, adapterIdentity, mode,
                sourceRef, sourceSignature, targetSignature, proof, wiringTargetOpId,
                wiringBoundaryKind));
        }

        /**
         * The creation-rule classification of one function-typed
         * {@code let} declaration position (B6; creation-rule-analysis
         * mode): the closed variable-position classification over the
         * initializer's checked type (the source signature) and the
         * declared/inferred binding type (the target signature), the
         * recorded proof fact of an identifier binding source (B7), the
         * prepared wiring point — the position's own
         * {@code VARIABLE_DECLARATION} boundary op for annotated
         * declarations, the {@code BINDING_INIT} op for inferred
         * declarations (no boundary exists there) — and the registry
         * child's producer facts of a host/external function-value
         * source (T4). The classification emits zero ops and changes
         * nothing: exact positions store directly (this child's
         * production), adapt positions record exactly one candidate for
         * the shape-map child, and the produced unit contains zero
         * {@code FUNCTION_ADAPT} ops.
         */
        private void classifyVarDeclPosition(VariableDeclaration decl, ValueId value,
                                             OpId declarationBoundary, OpId initOp) {
            Type targetType = declaredTypeOf(decl);
            if (!(targetType instanceof Type.Func)) {
                // Not a function-typed position — the creation rule's
                // window (the ordinary flow's descriptor-kind rule is
                // the boundary machinery's).
                return;
            }
            Type sourceType = producedType(decl.initializer());
            Optional<BindingImmutabilityProof> proof = sourceProofOf(decl.initializer());
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts =
                sourceProducerFactsOf(value);
            AdapterCreationRule.WiringPoint wiringPoint = declarationBoundary != null
                ? new AdapterCreationRule.WiringPoint(declarationBoundary,
                    AdapterCreationRule.WiringTargetKind.VARIABLE_DECLARATION_BOUNDARY)
                : new AdapterCreationRule.WiringPoint(initOp,
                    AdapterCreationRule.WiringTargetKind.BINDING_INIT);
            creationClassifications.add(AdapterCreationRule.classifyAdaptationPosition(
                BoundaryKind.VARIABLE_DECLARATION, sourceType, targetType, proof,
                wiringPoint, producerFacts));
        }

        /**
         * The creation-rule classification of one function-typed
         * variable-assignment position (B6; creation-rule-analysis
         * mode): the closed variable-position classification over the
         * RHS's checked type (the source signature) and the target
         * binding's declared type (the target signature), the recorded
         * proof fact of an identifier binding source (B7), the prepared
         * wiring point (the position's own {@code VARIABLE_ASSIGNMENT}
         * boundary op), and the registry child's producer facts of a
         * host/external function-value source (T4). Zero emission: the
         * walk's {@code [valueOp, boundaryOp, commitOp]} chain is
         * unchanged and the unit contains zero {@code FUNCTION_ADAPT}
         * ops.
         */
        private void classifyVariableAssignPosition(AssignmentExpr assignment,
                                                    IdentifierExpr target, ValueId value,
                                                    OpId boundaryOp) {
            Type targetType = checkedType(target);
            if (!(targetType instanceof Type.Func)) {
                // Not a function-typed position — the creation rule's
                // window.
                return;
            }
            Type sourceType = producedType(assignment.value());
            Optional<BindingImmutabilityProof> proof = sourceProofOf(assignment.value());
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts =
                sourceProducerFactsOf(value);
            creationClassifications.add(AdapterCreationRule.classifyAdaptationPosition(
                BoundaryKind.VARIABLE_ASSIGNMENT, sourceType, targetType, proof,
                new AdapterCreationRule.WiringPoint(boundaryOp,
                    AdapterCreationRule.WiringTargetKind.VARIABLE_ASSIGNMENT_BOUNDARY),
                producerFacts));
        }

        /**
         * The closed typed-boundary-position classification record (B6;
         * creation-rule-analysis mode): a boundary position never
         * adapts — the classifier records the {@code BOUNDARY_DIRECT}
         * classification and the recorded E8010
         * {@code FUNCTION_SIGNATURE} expectation for a mismatched
         * function-typed pair; the boundary op, its function-descriptor
         * cell, and the executed check are E4's machinery. The walk's
         * direct value flow into the position's boundary slot is the
         * wiring E4's boundary producer consumes.
         */
        private void recordBoundaryClassification(BoundaryKind kind, Type sourceType,
                                                  Type targetType) {
            creationClassifications.add(
                AdapterCreationRule.classifyBoundaryPosition(kind, sourceType, targetType));
        }

        /**
         * The closed position classification of one statically typed
         * function-value position (creation-rule-analysis mode): the
         * adaptation-position classifier over the position's checked source
         * type and declared target type, with the position's own boundary op
         * as its wiring point (an exact pair records the direct store, an
         * assignable-but-not-exact pair the single candidate this walk
         * adapted). Zero emission.
         */
        private void recordPositionClassification(BoundaryKind kind, Type sourceType,
                                                  Type targetType, ValueId value,
                                                  OpId boundaryOpId) {
            if (!(targetType instanceof Type.Func)) {
                return;
            }
            creationClassifications.add(AdapterCreationRule.classifyAdaptationPosition(
                kind, sourceType, targetType, Optional.empty(),
                new AdapterCreationRule.WiringPoint(boundaryOpId,
                    AdapterCreationRule.WiringTargetKind.POSITION_BOUNDARY),
                sourceProducerFactsOf(value)));
        }

        /**
         * The closed position classification of one statically typed
         * function-value position whose declared target signature is carried
         * as a runtime descriptor (the class field/default and array write
         * cells; creation-rule-analysis mode).
         */
        private void recordPositionClassification(BoundaryKind kind, Type sourceType,
                                                  RuntimeDescriptor targetDescriptor,
                                                  OpId boundaryOpId) {
            if (!(targetDescriptor instanceof RuntimeDescriptor.Func targetSignature)
                    || !(sourceType instanceof Type.Func sourceTypeFunc)
                    || !(ContainerPayloadDescriptors.resultDescriptorOf(sourceTypeFunc)
                        instanceof RuntimeDescriptor.Func sourceSignature)) {
                return;
            }
            creationClassifications.add(AdapterCreationRule.classifyAdaptationPosition(
                kind, sourceSignature, targetSignature,
                new AdapterCreationRule.WiringPoint(boundaryOpId,
                    AdapterCreationRule.WiringTargetKind.POSITION_BOUNDARY)));
        }

        /**
         * The recorded proof fact of an identifier binding source (B7):
         * present exactly when the source is an {@code IdentifierExpr}
         * whose dominant incarnation at the position carries the
         * conservative {@code BindingImmutabilityProof} — the fact the
         * shape-map child's VALUE arm consumes (an unproven binding is
         * the SHARED_CELL arm's). Non-identifier sources carry no proof
         * fact (materialized operands and member reads are the
         * shape-map child's VALUE/REEVALUATE_THUNK arms — the closed
         * map's decision, not this child's).
         */
        private Optional<BindingImmutabilityProof> sourceProofOf(ExpressionNode source) {
            if (!(source instanceof IdentifierExpr identifier)) {
                return Optional.empty();
            }
            FrameResolution resolution = resolveFrame(identifier.name());
            if (resolution == null) {
                return Optional.empty();
            }
            return proofFacts().proofOf(resolution.entry().cell().id,
                resolution.entry().incarnation().generation());
        }

        /**
         * The registry child's producer facts of the source value's
         * producing allocation (the T4 host/external materialization
         * seam): present exactly when the source's allocation identity
         * has a recorded host/external function-value materialization —
         * the fact the adaptation candidate carries for the shape-map
         * child's import-read arm (a host/external import read is a
         * non-identifier member-read source → REEVALUATE_THUNK, B7; the
         * mode decision is the shape-map child's). Loads, reads,
         * argument passing, and returns preserve the allocation
         * identity, so the identity lookup resolves the producing
         * site's classification.
         */
        private Optional<FunctionBindingRegistry.FunctionValueMaterialization>
                sourceProducerFactsOf(ValueId value) {
            return registry.materializationOf(new FunctionAllocationIdentity(value.id()));
        }

        /**
         * The runtime descriptor of an already-lowered value (closure-core
         * mode): the result type of the value's producing op in the
         * current emission target. Used to classify the initializer's
         * value for the static function-identity tracking.
         */
        private RuntimeDescriptor descriptorOf(ValueId value) {
            List<SemanticOp> target = emitTarget();
            for (int i = target.size() - 1; i >= 0; i--) {
                SemanticOp op = target.get(i);
                if (value.equals(op.result())
                        && op.resultType() instanceof RuntimeDescriptor descriptor) {
                    return descriptor;
                }
            }
            throw new IllegalStateException("no produced op publishes value " + value
                + " (producer defect)");
        }

        /**
         * Registers one callee body's parameters at the body block's
         * entry: a generation-0 {@code DIRECT} incarnation and its
         * {@code BINDING_ALLOC} per parameter, in declaration order (the
         * invoking machinery's parameter-transfer write follows, E7).
         */
        private void lowerParameterBindings(BlockId bodyBlock,
                                            List<Parameter> params) {
            for (Parameter parameter : params) {
                BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                    true, BindingProducer.BINDING_ALLOC, false);
                registerBinding(parameter.name(), binding, incarnation);
                parameterBindings.add(binding);
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(binding, bodyBlock, true,
                        cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                    parameter.span(), FailurePolicyId.NO_DEAL_FAILURE);
            }
        }

        private void lowerBindingFunctionDecl(FunctionDeclaration function,
                                              boolean moduleLevel) {
            BindingCoreIncarnation nameIncarnation = null;
            BindingId nameBinding = null;
            if (!moduleLevel) {
                // Nested-scope declarations allocate their name binding at
                // the declaration position (B1: nested functions are
                // defined at their position, no hoisting).
                nameBinding = ids.nextBindingId(module, nextOrdinal++, 0);
                nameIncarnation = new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, currentBlock(), BindingCellKind.DIRECT,
                    true, BindingProducer.BINDING_ALLOC, false);
                registerBinding(function.name(), nameBinding, nameIncarnation);
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(nameBinding, currentBlock(), true,
                        cellKinds.cellKindOf(nameIncarnation), INITIAL_LOOP_GENERATION),
                    function.span(), FailurePolicyId.NO_DEAL_FAILURE);
            }
            if (!closureCore) {
                // Module-level name ALLOCs were hoisted (B1): no second
                // allocation here.
                BlockId bodyBlock = allocateBlock();
                checkerScopeNodes.push(function);
                pushBindingFrame();
                pushAssignedNameScope(function.body().statements());
                blockStack.push(bodyBlock);
                lowerParameterBindings(bodyBlock, function.params());
                checkerScopeNodes.push(function.body());
                try {
                    statementWalk.walk(function.body().statements(), false);
                } finally {
                    popAssignedNameScope();
                    checkerScopeNodes.pop();
                    blockStack.pop();
                    popBindingFrame();
                    checkerScopeNodes.pop();
                }
                return;
            }
            // --- Closure-core mode: CLOSURE_NEW + BINDING_INIT at the
            // declaration position over the buffered body walk. ---
            FrameEntry hoisted = null;
            if (moduleLevel) {
                hoisted = frameEntryOf(function.name());
                if (hoisted == null) {
                    throw new IllegalStateException("hoisted module-level function ALLOC "
                        + "missing for '" + function.name() + "' (producer defect)");
                }
                nameBinding = hoisted.cell().id;
                nameIncarnation = hoisted.incarnation();
            }
            FunctionContext reservedContext = fullProgram && moduleLevel
                ? functionContexts.get(nameIncarnation) : null;
            BlockId bodyBlock = reservedContext != null
                ? reservedContext.bodyBlock : allocateBlock();
            FunctionId functionId = reservedContext != null
                ? reservedContext.functionId : ids.nextFunctionId(module, nextOrdinal++, 0);
            if (reservedContext == null && fullProgram && !moduleLevel) {
                // The nested-declaration body-invocation arm: a function
                // declaration inside a function body owns its body context
                // like a function expression (one reserved return boundary
                // and one reserved call-site identity the CALL machine
                // resolves), and the context registers under the
                // declaration's name binding so a direct call (or await) of
                // the local declaration resolves it (K12: every lowered
                // body carries exactly one invocation identity).
                reservedContext = new FunctionContext(functionId, bodyBlock,
                    functionSignatureOf(function), ids.nextOpId(module, nextOrdinal++, 0),
                    ids.nextOpId(module, nextOrdinal++, 0),
                    parameterTypeSpans(function.params()));
                contextsByFunctionId.put(functionId, reservedContext);
                contextsByBindingId.put(nameBinding, reservedContext);
            }
            ValueId closureIdentity;
            if (functionIdentity.containsKey(nameIncarnation)) {
                // The hoist-time pre-allocated identity (B1: captures of
                // later-declared module functions resolve to the hoisted
                // ALLOC and publish this identity).
                closureIdentity = functionIdentity.get(nameIncarnation);
            } else {
                closureIdentity = ids.nextValueId(module, nextOrdinal++, 0);
                functionIdentity.put(nameIncarnation, closureIdentity);
            }
            RuntimeDescriptor.Func signature = functionSignatureOf(function);
            // The declared body's execution binding is pre-registered
            // before its own body walk (C10/K12: every lowered body owns
            // exactly one resolvable invocation identity, one return
            // cell, and one execution binding). A reference of the
            // declaration to its own pre-allocated identity — an ordinary
            // function-value call (`let again = f; again(...)`) or its
            // awaited twin — resolves through the registry while the walk
            // is still open instead of failing closed on a
            // not-yet-registered binding. The registration is exactly one
            // per allocation identity; {@link #emitClosureNew} below
            // reuses it instead of registering twice. The body's capture
            // list is finalized only after the walk, so a pending body's
            // value-carried classification is recorded until then
            // ({@link #bodiesPendingCaptures}).
            FunctionAllocationIdentity preRegisteredIdentity =
                new FunctionAllocationIdentity(closureIdentity.id());
            registerDeclaredBody(preRegisteredIdentity, functionId, bodyBlock);
            bodiesPendingCaptures.add(functionId);
            List<SemanticOp> bodyOps = new ArrayList<>();
            List<CapturedCell> captured = new ArrayList<>();
            emitTargets.push(bodyOps);
            captureBorders.push(bindingScopes.size());
            captureCollectors.push(captured);
            try {
                checkerScopeNodes.push(function);
                pushBindingFrame();
                pushAssignedNameScope(function.body().statements());
                blockStack.push(bodyBlock);
                lowerParameterBindings(bodyBlock, function.params());
                checkerScopeNodes.push(function.body());
                if (reservedContext != null) {
                    functionStack.push(reservedContext);
                }
                boolean bodyComplete = false;
                try {
                    statementWalk.walk(function.body().statements(), false);
                    bodyComplete = true;
                } finally {
                    if (reservedContext != null) {
                        if (bodyComplete) {
                            // The implicit trailing return of an
                            // unterminated null-returning body ("a
                            // function with return type null returns the
                            // null value through the boundary"). The
                            // closed terminator analysis decides: a body
                            // whose block is not OPEN (a leaf transfer or a
                            // terminating composite) carries no implicit
                            // return and never fails closed; only a
                            // genuinely unterminated non-null body keeps
                            // the fail-closed arm.
                            if (exitStateOf(bodyBlock) == BlockExitState.OPEN) {
                                if (!(reservedContext.signature.returnType()
                                        instanceof RuntimeDescriptor.Void)) {
                                    throw new ConstructUnlowered("function '"
                                        + function.name()
                                        + "' body is not terminated and its return type is "
                                        + "not null (the carrier slice lowers the pinned "
                                        + "implicit null return only)", function.span());
                                }
                                lowerImplicitReturn(reservedContext, function.body().span());
                            }
                        }
                        functionStack.pop();
                    }
                    checkerScopeNodes.pop();
                    blockStack.pop();
                    popBindingFrame();
                    popAssignedNameScope();
                    checkerScopeNodes.pop();
                }
            } finally {
                captureCollectors.pop();
                captureBorders.pop();
                emitTargets.pop();
            }
            List<BindingGeneration> captureIds = captureGenerations(captured);
            emitClosureNew(functionId, closureIdentity, signature, captureIds, bodyBlock,
                function.span(), captured, function.name());
            // The body's capture list is final from here: every later
            // reference classifies the value-carried invocation from the
            // recorded captures.
            bodiesPendingCaptures.remove(functionId);
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(nameBinding, INITIAL_LOOP_GENERATION,
                    closureIdentity),
                function.span(), FailurePolicyId.NO_DEAL_FAILURE);
            emitTarget().addAll(bodyOps);
        }

        private void lowerGroup(List<FunctionDeclaration> members, boolean moduleLevel) {
            List<BindingId> memberBindings = new ArrayList<>();
            List<FunctionId> memberFunctions = new ArrayList<>();
            List<ValueId> memberIdentities = new ArrayList<>();
            List<FrameEntry> memberEntries = new ArrayList<>();
            for (FunctionDeclaration member : members) {
                FrameEntry entry = null;
                ValueId identity = null;
                if (moduleLevel) {
                    entry = frameEntryOf(member.name());
                    if (entry == null) {
                        throw new IllegalStateException("hoisted module-level member "
                            + "registration missing for '" + member.name()
                            + "' (producer defect)");
                    }
                    identity = functionIdentity.get(entry.incarnation());
                    if (identity == null) {
                        throw new IllegalStateException("pre-assigned member identity "
                            + "missing for '" + member.name() + "' (producer defect)");
                    }
                } else {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, currentBlock(), BindingCellKind.SHARED_CELL,
                        true, BindingProducer.RECURSIVE_GROUP_INIT, true);
                    registerBinding(member.name(), binding, incarnation);
                    identity = ids.nextValueId(module, nextOrdinal++, 0);
                    functionIdentity.put(incarnation, identity);
                    entry = frameEntryOf(member.name());
                }
                memberBindings.add(entry.cell().id);
                memberIdentities.add(identity);
                memberEntries.add(entry);
            }
            // The member body contexts and their execution bindings, all
            // created and registered before any member body walk (C10/K12:
            // every lowered body owns exactly one invocation identity, one
            // return cell, and one resolvable execution binding). A
            // module-level member reuses the context the hoist reserved
            // (B1); a nested member's context is created here and
            // registered under its group binding, so a sibling direct call
            // resolves through contextsByBindingId and an explicit return
            // names the member's own FunctionId — never the enclosing
            // function's. Registering the member's LoweredBody here makes
            // the member's pre-assigned allocation identity resolvable from
            // inside every sibling body: a sibling reference through an
            // ordinary function value (a `let` alias) lowers its call
            // through the registry lookup, exactly like any other
            // declared-function value, with exactly one registration per
            // member identity. The member's capture list is finalized only
            // after its body walk, so a pending member's value-carried
            // classification is recorded until then
            // ({@link #bodiesPendingCaptures}).
            List<FunctionContext> memberContexts = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                FunctionDeclaration member = members.get(i);
                FunctionContext context = null;
                if (moduleLevel) {
                    if (fullProgram) {
                        context = functionContexts.get(memberEntries.get(i).incarnation());
                        if (context == null) {
                            throw new IllegalStateException("hoisted module-level "
                                + "member context missing for '" + member.name()
                                + "' (producer defect)");
                        }
                    }
                } else if (fullProgram) {
                    FunctionId memberFunctionId =
                        ids.nextFunctionId(module, nextOrdinal++, 0);
                    BlockId memberBodyBlock = allocateBlock();
                    context = new FunctionContext(memberFunctionId, memberBodyBlock,
                        functionSignatureOf(member),
                        ids.nextOpId(module, nextOrdinal++, 0),
                        ids.nextOpId(module, nextOrdinal++, 0),
                        parameterTypeSpans(member.params()));
                    contextsByFunctionId.put(memberFunctionId, context);
                    contextsByBindingId.put(memberBindings.get(i), context);
                    // The nested member's published carrier is the only
                    // invocation source whose creation-site captures name
                    // this group instance ({@link
                    // #nestedGroupMemberBindings}).
                    nestedGroupMemberBindings.add(memberBindings.get(i));
                }
                if (context != null) {
                    registerDeclaredBody(new FunctionAllocationIdentity(
                        memberIdentities.get(i).id()), context.functionId,
                        context.bodyBlock);
                    bodiesPendingCaptures.add(context.functionId);
                }
                memberContexts.add(context);
            }
            // Phase 1 walk: every member body lowers through the buffered
            // detached-body walk with capture collection (B3) — siblings
            // resolve because every member binding and its body context
            // registered before any body walk.
            List<List<SemanticOp>> bodyOpLists = new ArrayList<>();
            List<List<BindingGeneration>> captureIdLists = new ArrayList<>();
            List<BlockId> bodyBlocks = new ArrayList<>();
            List<RuntimeDescriptor.Func> signatures = new ArrayList<>();
            List<List<CapturedCell>> capturedLists = new ArrayList<>();
            for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                FunctionDeclaration member = members.get(memberIndex);
                FunctionContext reservedContext = memberContexts.get(memberIndex);
                BlockId bodyBlock = reservedContext != null
                    ? reservedContext.bodyBlock : allocateBlock();
                FunctionId functionId = reservedContext != null
                    ? reservedContext.functionId
                    : ids.nextFunctionId(module, nextOrdinal++, 0);
                RuntimeDescriptor.Func signature = functionSignatureOf(member);
                List<SemanticOp> bodyOps = new ArrayList<>();
                List<CapturedCell> captured = new ArrayList<>();
                emitTargets.push(bodyOps);
                captureBorders.push(bindingScopes.size());
                captureCollectors.push(captured);
                try {
                    checkerScopeNodes.push(member);
                    pushBindingFrame();
                    pushAssignedNameScope(member.body().statements());
                    blockStack.push(bodyBlock);
                    if (reservedContext != null) {
                        functionStack.push(reservedContext);
                    }
                    lowerParameterBindings(bodyBlock, member.params());
                    checkerScopeNodes.push(member.body());
                    boolean bodyComplete = false;
                    try {

                        statementWalk.walk(member.body().statements(), false);
                        bodyComplete = true;
                    } finally {
                        if (reservedContext != null) {
                            if (bodyComplete
                                    && exitStateOf(bodyBlock)
                                        == BlockExitState.OPEN) {
                                // The implicit trailing return of an
                                // unterminated null-returning member body
                                // (the closed terminator analysis decides:
                                // a non-OPEN body carries no implicit
                                // return and never fails closed).
                                if (!(reservedContext.signature.returnType()
                                        instanceof RuntimeDescriptor.Void)) {
                                    throw new ConstructUnlowered("group member '"
                                        + member.name() + "' body is not terminated"
                                        + " and its return type is not null", member.span());
                                }
                                lowerImplicitReturn(reservedContext,
                                    member.body().span());
                            }
                            functionStack.pop();
                        }
                        checkerScopeNodes.pop();
                        blockStack.pop();
                        popBindingFrame();
                        popAssignedNameScope();
                        checkerScopeNodes.pop();
                    }
                } finally {
                    captureCollectors.pop();
                    captureBorders.pop();
                    emitTargets.pop();
                }
                List<BindingGeneration> captureIds = captureGenerations(captured);
                memberFunctions.add(functionId);
                bodyOpLists.add(bodyOps);
                captureIdLists.add(captureIds);
                bodyBlocks.add(bodyBlock);
                signatures.add(signature);
                capturedLists.add(captured);
                if (reservedContext != null) {
                    // The member's capture list is fixed the moment its own
                    // walk ends: the LoweredFunction record is final from
                    // here, so every later reference classifies the
                    // value-carried invocation from the recorded captures.
                    functions.put(functionId, new LoweredFunction(functionId, signature,
                        captureIds, bodyBlock, member.name()));
                    bodiesPendingCaptures.remove(functionId);
                }
            }
            // Phase 2: exactly one RECURSIVE_GROUP_INIT op — the payload
            // pins the two-phase execution contract (identities allocated
            // first, then published atomically); the op carries no result
            // slot. The origin parentage mirrors the closure child's
            // CLOSURE_NEW (the enclosing structure op when one exists).
            Span groupSpan = members.get(0).span();
            AnchorId groupAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId groupOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin groupOrigin = new SourceOrigin(sourceId,
                toSourceSpan(groupSpan), SourceOriginKind.USER, groupAnchor,
                currentParent());
            emit(buildOp(groupOpId, SemanticOpKind.RECURSIVE_GROUP_INIT,
                new KindPayload.RecursiveGroupInitPayload(memberBindings, memberFunctions),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, groupOrigin));
            List<GroupMemberFacts> memberFacts = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                FunctionDeclaration member = members.get(i);
                FunctionId functionId = memberFunctions.get(i);
                List<BindingGeneration> captureIds = captureIdLists.get(i);
                BlockId bodyBlock = bodyBlocks.get(i);
                RuntimeDescriptor.Func signature = signatures.get(i);
                ValueId identity = memberIdentities.get(i);
                if (memberContexts.get(i) == null) {
                    // The slice arms (outside the full-program entry) keep
                    // registering the member's execution binding after the
                    // walk; the preallocation arm registered it before the
                    // first member body walk (exactly once per identity).
                    functions.put(functionId, new LoweredFunction(functionId, signature,
                        captureIds, bodyBlock, member.name()));
                    registry.registerGroupMember(new FunctionAllocationIdentity(identity.id()),
                        functionId, bodyBlock);
                }
                List<ClosureCapture> captureFacts = new ArrayList<>();
                for (CapturedCell capture : capturedLists.get(i)) {
                    captureFacts.add(new ClosureCapture(capture.cell().name,
                        capture.cell().id, capture.incarnation().generation(),
                        capture.incarnation().scope(), capture.incarnation().producer()));
                }
                memberFacts.add(new GroupMemberFacts(member.name(),
                    memberEntries.get(i).cell().id, functionId, identity, signature,
                    bodyBlock, captureFacts));
            }
            groupFactsList.add(new GroupFacts(groupOpId, memberFacts, currentBlock()));
            for (List<SemanticOp> bodyOps : bodyOpLists) {
                emitTarget().addAll(bodyOps);
            }
        }

        /**
         * The declared parameter type-annotation spans of one function
         * declaration or expression, in declaration order: the origin a
         * {@code FUNCTION_PARAMETER} cell reports when the cell fails
         * (the callee's own declaration site — the pinned corpus span).
         */
        private static List<Span> parameterTypeSpans(List<Parameter> parameters) {
            List<Span> spans = new ArrayList<>();
            for (Parameter parameter : parameters) {
                spans.add(parameter.type() == null ? null : parameter.type().span());
            }
            return spans;
        }

        /**
         * The exact function signature of a checked function declaration
         * (closure-core mode): the {@code Symbol.FunctionSymbol} fact of
         * the declaration, resolved through the checker's per-scope
         * symbol table (the exact enclosing declaration scope, excluding
         * shadowing parameters — never retaining a
         * {@code NameResolver} instance, D4).
         */
        private RuntimeDescriptor.Func functionSignatureOf(FunctionDeclaration function) {
            SymbolTable scope = checks.scopeMap().get(function);
            Symbol symbol = (scope == null ? checks.symbolTable() : scope.parent())
                .resolveLocal(function.name());
            if (symbol instanceof Symbol.FunctionSymbol functionSymbol) {
                try {
                    return (RuntimeDescriptor.Func)
                        ContainerPayloadDescriptors.resultDescriptorOf(functionSymbol.funcType());
                } catch (ContainerPayloadDescriptors.Defect defect) {
                    // The declaration owns the derivation: a corrupted
                    // checked signature fails closed at the function
                    // declaration's own range (D2), while an already
                    // attached (inner) range always wins.
                    throw defect.withRange(function.span().range());
                }
            }
            throw new ConstructUnlowered("function declaration '" + function.name()
                + "' without a checked FunctionSymbol fact (a missing checker fact is a "
                + "producer defect)", function.span());
        }


        private void lowerBindingForOf(ForOfStatement statement) {
            Type iterableType = checkedType(statement.iterable());
            if (iterableType instanceof Type.Array) {
                throw new ConstructUnlowered("array for-of (FOR_EACH(ARRAY_VALUES) is E5's, "
                    + "ISSUE-0234; an array-typed iterable reaching the binding-core walk "
                    + "fails hard)", statement.span());
            }
            if (!(iterableType instanceof Type.String)) {
                throw new ConstructUnlowered("for-of over a non-string, non-array iterable "
                    + typeName(iterableType) + " (this child lowers string iterables only)",
                        statement.span());
            }
            ValueId iterable = lowerExpression(statement.iterable());
            BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
            BlockId bodyBlock = allocateBlock();
            pushBindingFrame();
            registerBinding(statement.varName(), binding, new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.SHARED_CELL,
                true, BindingProducer.FOR_EACH, true));
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.FOR_EACH,
                new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, iterable,
                    binding, INITIAL_LOOP_GENERATION, bodyBlock),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, origin));
            checkerScopeNodes.push(statement.body());
            blockStack.push(bodyBlock);
            statementWalk.walk(statement.body().statements(), false);
            blockStack.pop();
            checkerScopeNodes.pop();
            popBindingFrame();
        }

        private void lowerBindingTry(TryStatement statement) {
            BlockId tryBlock = allocateBlock();
            BlockId catchBlock = allocateBlock();
            BindingId catchBinding = ids.nextBindingId(module, nextOrdinal++, 0);
            emitUserNullOp(SemanticOpKind.TRY_CATCH,
                new KindPayload.TryCatchPayload(tryBlock, catchBinding, catchBlock),
                statement.span(), FailurePolicyId.NO_DEAL_FAILURE);
            checkerScopeNodes.push(statement.tryBlock());
            pushBindingFrame();
            blockStack.push(tryBlock);
            statementWalk.walk(statement.tryBlock().statements(), false);
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
            checkerScopeNodes.push(statement);
            pushBindingFrame();
            blockStack.push(catchBlock);
            BindingCoreIncarnation catchIncarnation = new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, catchBlock, BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC, false);
            registerBinding(statement.catchVar(), catchBinding, catchIncarnation);
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(catchBinding, catchBlock, true,
                    cellKinds.cellKindOf(catchIncarnation), INITIAL_LOOP_GENERATION),
                statement.span(), FailurePolicyId.NO_DEAL_FAILURE);
            statementWalk.walk(statement.catchBlock().statements(), false);
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
        }

        /**
         * The binding walk's nested-block arm: one fresh block identity
         * plus a scope frame, then the block's statements (blocks are
         * the structured graph nodes the dominant-incarnation walk
         * resolves against, B9 R1).
         */
        private void lowerBindingBlock(Block block) {
            BlockId blockId = allocateBlock();
            checkerScopeNodes.push(block);
            pushBindingFrame();
            blockStack.push(blockId);
            statementWalk.walk(block.statements(), false);
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
        }

        /**
         * Partitions one scope's function declarations into SCCs over the
         * body-reference graph (B4): an edge {@code D -> D'} exists iff
         * D's body references D'.name as a free name — a reference
         * anywhere in the body tree (nested closures included, matching
         * the checker's scope-table resolution) that is not shadowed by
         * D's own scope chain (parameters, block declarations, nested
         * function names, loop/catch bindings). The returned SCCs carry
         * their members in declaration order and the SCC list is ordered
         * by first member's declaration order — deterministic,
         * byte-identical lowering.
         *
         */
        private static List<List<FunctionDeclaration>> partitionFunctionDeclarations(
                List<FunctionDeclaration> declarations) {
            List<List<FunctionDeclaration>> sccs = new ArrayList<>();
            if (declarations.isEmpty()) {
                return sccs;
            }
            Map<FunctionDeclaration, Integer> position = new IdentityHashMap<>();
            for (int i = 0; i < declarations.size(); i++) {
                position.put(declarations.get(i), i);
            }
            Set<String> names = new LinkedHashSet<>();
            for (FunctionDeclaration declaration : declarations) {
                names.add(declaration.name());
            }
            Map<FunctionDeclaration, LinkedHashSet<FunctionDeclaration>> edges =
                new IdentityHashMap<>();
            for (FunctionDeclaration declaration : declarations) {
                LinkedHashSet<FunctionDeclaration> targets = new LinkedHashSet<>();
                Set<String> referenced = freeNameReferences(declaration);
                for (String name : referenced) {
                    if (!names.contains(name) || name.equals(declaration.name())) {
                        continue;
                    }
                    for (FunctionDeclaration candidate : declarations) {
                        if (candidate.name().equals(name)) {
                            targets.add(candidate);
                        }
                    }
                }
                edges.put(declaration, targets);
            }
            // Tarjan's SCC algorithm over the identity-keyed adjacency
            // (edges iterated in declaration order — deterministic).
            IdentityHashMap<FunctionDeclaration, Integer> index =
                new IdentityHashMap<>();
            IdentityHashMap<FunctionDeclaration, Integer> lowlink =
                new IdentityHashMap<>();
            List<FunctionDeclaration> stack = new ArrayList<>();
            Set<FunctionDeclaration> onStack =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            int[] nextIndex = {0};
            for (FunctionDeclaration declaration : declarations) {
                if (!index.containsKey(declaration)) {
                    strongConnect(declaration, edges, index, lowlink, stack, onStack,
                        nextIndex, sccs);
                }
            }
            // Deterministic normalization: members in declaration order,
            // SCCs ordered by first member's declaration order.
            for (List<FunctionDeclaration> scc : sccs) {
                scc.sort(Comparator.comparingInt(position::get));
            }
            sccs.sort(Comparator.comparingInt(scc -> position.get(scc.get(0))));
            return sccs;
        }

        /** One Tarjan recursion step (deterministic edge iteration). */
        private static void strongConnect(FunctionDeclaration node,
                                          Map<FunctionDeclaration,
                                              LinkedHashSet<FunctionDeclaration>> edges,
                                          IdentityHashMap<FunctionDeclaration, Integer> index,
                                          IdentityHashMap<FunctionDeclaration, Integer> lowlink,
                                          List<FunctionDeclaration> stack,
                                          Set<FunctionDeclaration> onStack,
                                          int[] nextIndex,
                                          List<List<FunctionDeclaration>> sccs) {
            index.put(node, nextIndex[0]);
            lowlink.put(node, nextIndex[0]);
            nextIndex[0]++;
            stack.add(node);
            onStack.add(node);
            for (FunctionDeclaration target : edges.get(node)) {
                if (!index.containsKey(target)) {
                    strongConnect(target, edges, index, lowlink, stack, onStack,
                        nextIndex, sccs);
                    lowlink.put(node, Math.min(lowlink.get(node), lowlink.get(target)));
                } else if (onStack.contains(target)) {
                    lowlink.put(node, Math.min(lowlink.get(node), index.get(target)));
                }
            }
            if (lowlink.get(node).equals(index.get(node))) {
                List<FunctionDeclaration> scc = new ArrayList<>();
                FunctionDeclaration member;
                do {
                    member = stack.remove(stack.size() - 1);
                    onStack.remove(member);
                    scc.add(member);
                } while (member != node);
                sccs.add(scc);
            }
        }

        /**
         * The free name references of one function declaration's body:
         * every identifier name referenced anywhere in the body tree that
         * the declaration's own scope chain does not bind (parameters,
         * same-block declarations — position-independent per the
         * checker's scope-table resolution — nested function names, and
         * loop/catch bindings). Member accesses name only their object
         * part; object-literal keys and class/property names are never
         * identifiers.
         */
        private static Set<String> freeNameReferences(FunctionDeclaration declaration) {
            Set<String> references = new LinkedHashSet<>();
            Set<String> bound = new HashSet<>();
            for (Parameter parameter : declaration.params()) {
                bound.add(parameter.name());
            }
            walkReferenceBlock(declaration.body(), bound, references);
            return references;
        }

        /**
         * Walks one block for free-name references: the block's own
         * declarations (at any position — the checker's scope-table
         * resolution binds them position-independently) join the bound
         * set before the statements walk; nested blocks and function
         * bodies extend the bound set with their own declarations.
         */
        private static void walkReferenceBlock(Block block, Set<String> bound,
                                               Set<String> references) {
            Set<String> local = new HashSet<>(bound);
            for (StatementNode statement : block.statements()) {
                String declared = declaredNameOf(statement);
                if (declared != null) {
                    local.add(declared);
                }
            }
            for (StatementNode statement : block.statements()) {
                walkReferenceStatement(statement, local, references);
            }
        }

        /**
         * The declared name a statement binds in its enclosing block, or
         * {@code null}: let names and nested function names (checker
         * scope-table resolution binds them position-independently).
         * For-let loop variables are NOT block bindings — the checker
         * defines them in the loop's own child scope
         * ({@code NameResolver.walkFor}), so they never shadow sibling
         * function names outside the loop.
         */
        private static String declaredNameOf(StatementNode statement) {
            return switch (statement) {
                case VariableDeclaration decl -> decl.name();
                case FunctionDeclaration function -> function.name();
                default -> null;
            };
        }

        /** Walks one statement for free-name references. */
        private static void walkReferenceStatement(StatementNode statement,
                                                   Set<String> bound,
                                                   Set<String> references) {
            switch (statement) {
                case VariableDeclaration decl ->
                    walkReferenceExpr(decl.initializer(), bound, references);
                case FunctionDeclaration function -> {
                    Set<String> inner = new HashSet<>(bound);
                    for (Parameter parameter : function.params()) {
                        inner.add(parameter.name());
                    }
                    walkReferenceBlock(function.body(), inner, references);
                }
                case Block block -> walkReferenceBlock(block, bound, references);
                case IfStatement ifStatement -> {
                    walkReferenceExpr(ifStatement.condition(), bound, references);
                    walkReferenceBlock(ifStatement.thenBlock(), bound, references);
                    if (ifStatement.elseBranch().isPresent()) {
                        Either<IfStatement, Block> branch = ifStatement.elseBranch().get();
                        switch (branch) {
                            case Either.Left<IfStatement, Block> left ->
                                walkReferenceStatement(left.value(), bound, references);
                            case Either.Right<IfStatement, Block> right ->
                                walkReferenceBlock(right.value(), bound, references);
                        }
                    }
                }
                case WhileStatement whileStatement -> {
                    walkReferenceExpr(whileStatement.condition(), bound, references);
                    walkReferenceBlock(whileStatement.body(), bound, references);
                }

                case ForOfStatement forOf -> {
                    // The iterable resolves in the parent scope; the loop
                    // variable binds only inside the body's scope chain.
                    walkReferenceExpr(forOf.iterable(), bound, references);
                    Set<String> loopBound = new HashSet<>(bound);
                    loopBound.add(forOf.varName());
                    walkReferenceBlock(forOf.body(), loopBound, references);
                }
                case TryStatement tryStatement -> {
                    walkReferenceBlock(tryStatement.tryBlock(), bound, references);
                    Set<String> catchBound = new HashSet<>(bound);
                    catchBound.add(tryStatement.catchVar());
                    walkReferenceBlock(tryStatement.catchBlock(), catchBound, references);
                }
                case ExpressionStatement expressionStatement ->
                    walkReferenceExpr(expressionStatement.expr(), bound, references);
                case ReturnStatement returnStatement -> {
                    if (returnStatement.expr().isPresent()) {
                        walkReferenceExpr(returnStatement.expr().get(), bound, references);
                    }
                }
                case ThrowStatement throwStatement ->
                    walkReferenceExpr(throwStatement.expr(), bound, references);
                case DeleteStatement deleteStatement ->
                    walkReferenceExpr(deleteStatement.target(), bound, references);
                // Class declarations, break/continue, imports, and
                // exports reference no sibling function names for the
                // partition (class defaults are per-construction R4
                // references, not body references — B4's graph covers
                // name references in function-declaration bodies).
                case ClassDeclaration _, BreakStatement _, ContinueStatement _,
                     ImportDeclaration _, ExportDeclaration _ -> { }
                default -> { /* no further statement kinds */ }
            }
        }

        /** Walks one expression for free-name references. */
        private static void walkReferenceExpr(ExpressionNode expr, Set<String> bound,
                                              Set<String> references) {
            switch (expr) {
                case LiteralExpr ignored -> { }
                case IdentifierExpr identifier -> {
                    if (!bound.contains(identifier.name())) {
                        references.add(identifier.name());
                    }
                }
                case BinaryExpr binary -> {
                    walkReferenceExpr(binary.left(), bound, references);
                    walkReferenceExpr(binary.right(), bound, references);
                }
                case UnaryExpr unary -> walkReferenceExpr(unary.expr(), bound, references);
                case AssertionExpr assertion -> walkReferenceExpr(assertion.value(), bound, references);
                case InstanceofExpr test -> walkReferenceExpr(test.value(), bound, references);
                case CallExpr call -> {
                    walkReferenceExpr(call.callee(), bound, references);
                    for (ExpressionNode arg : call.args()) {
                        walkReferenceExpr(arg, bound, references);
                    }
                }
                case MemberAccessExpr access ->
                    walkReferenceExpr(access.object(), bound, references);
                case IndexExpr index -> {
                    walkReferenceExpr(index.array(), bound, references);
                    walkReferenceExpr(index.index(), bound, references);
                }
                case ArrayLiteralExpr array -> {
                    for (ExpressionNode element : array.elements()) {
                        walkReferenceExpr(element, bound, references);
                    }
                }
                case ObjectLiteralExpr object -> {
                    for (Property property : object.properties()) {
                        walkReferenceExpr(property.value(), bound, references);
                    }
                }
                case FunctionExpr functionExpr -> {
                    Set<String> inner = new HashSet<>(bound);
                    for (Parameter parameter : functionExpr.params()) {
                        inner.add(parameter.name());
                    }
                    walkReferenceBlock(functionExpr.body(), inner, references);
                }
                case HasExpr has -> { walkReferenceExpr(has.object(), bound, references); walkReferenceExpr(has.key(), bound, references); }
                case AssignmentExpr assignment -> {
                    walkReferenceExpr(assignment.target(), bound, references);
                    walkReferenceExpr(assignment.value(), bound, references);
                }
                case TemplateLiteralExpr template -> {
                    for (ExpressionNode part : template.parts()) {
                        walkReferenceExpr(part, bound, references);
                    }
                }
                case AwaitExpression awaitExpression ->
                    walkReferenceExpr(awaitExpression.callee(), bound, references);
            }
        }

        /**
         * The identifier names assigned anywhere in one function-body
         * subtree — nested blocks and nested function bodies included —
         * which is the mutable-name surface that body's bindings are read
         * against. Position-independent and deliberately scope-blind: a
         * shadowing declaration of the same name marks the name too, which
         * can only withhold a statically resolved callee, never select a
         * wrong one.
         */
        private static Set<String> assignedNamesIn(List<StatementNode> statements) {
            Set<String> assigned = new HashSet<>();
            collectAssignedNames(statements, assigned);
            return assigned;
        }

        private static void collectAssignedNames(List<StatementNode> statements,
                                                 Set<String> assigned) {
            for (StatementNode statement : statements) {
                collectAssignedNames(statement, assigned);
            }
        }

        private static void collectAssignedNames(StatementNode statement,
                                                 Set<String> assigned) {
            switch (statement) {
                case VariableDeclaration decl ->
                    collectAssignedNames(decl.initializer(), assigned);
                case FunctionDeclaration function ->
                    collectAssignedNames(function.body().statements(), assigned);
                case Block block -> collectAssignedNames(block.statements(), assigned);
                case IfStatement ifStatement -> {
                    collectAssignedNames(ifStatement.condition(), assigned);
                    collectAssignedNames(ifStatement.thenBlock().statements(), assigned);
                    if (ifStatement.elseBranch().isPresent()) {
                        switch (ifStatement.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left ->
                                collectAssignedNames(left.value(), assigned);
                            case Either.Right<IfStatement, Block> right ->
                                collectAssignedNames(right.value().statements(), assigned);
                        }
                    }
                }
                case WhileStatement whileStatement -> {
                    collectAssignedNames(whileStatement.condition(), assigned);
                    collectAssignedNames(whileStatement.body().statements(), assigned);
                }

                case ForOfStatement forOf -> {
                    collectAssignedNames(forOf.iterable(), assigned);
                    collectAssignedNames(forOf.body().statements(), assigned);
                }
                case TryStatement tryStatement -> {
                    collectAssignedNames(tryStatement.tryBlock().statements(), assigned);
                    collectAssignedNames(tryStatement.catchBlock().statements(), assigned);
                }
                case ExpressionStatement expressionStatement ->
                    collectAssignedNames(expressionStatement.expr(), assigned);
                case ReturnStatement returnStatement -> {
                    if (returnStatement.expr().isPresent()) {
                        collectAssignedNames(returnStatement.expr().get(), assigned);
                    }
                }
                case ThrowStatement throwStatement ->
                    collectAssignedNames(throwStatement.expr(), assigned);
                case DeleteStatement deleteStatement ->
                    collectAssignedNames(deleteStatement.target(), assigned);
                case ExportDeclaration exportDeclaration ->
                    collectAssignedNames(exportDeclaration.declaration(), assigned);
                case ClassDeclaration classDeclaration -> {
                    for (ClassField field : classDeclaration.fields()) {

                    }
                }
                case BreakStatement _, ContinueStatement _, ImportDeclaration _ -> { }
            }
        }

        private static void collectAssignedNames(ExpressionNode expr,
                                                 Set<String> assigned) {
            switch (expr) {
                case LiteralExpr ignored -> { }
                case IdentifierExpr ignored -> { }
                case BinaryExpr binary -> {
                    collectAssignedNames(binary.left(), assigned);
                    collectAssignedNames(binary.right(), assigned);
                }
                case UnaryExpr unary -> collectAssignedNames(unary.expr(), assigned);
                case AssertionExpr assertion -> collectAssignedNames(assertion.value(), assigned);
                case InstanceofExpr test -> collectAssignedNames(test.value(), assigned);
                case CallExpr call -> {
                    collectAssignedNames(call.callee(), assigned);
                    for (ExpressionNode arg : call.args()) {
                        collectAssignedNames(arg, assigned);
                    }
                }
                case MemberAccessExpr access ->
                    collectAssignedNames(access.object(), assigned);
                case IndexExpr index -> {
                    collectAssignedNames(index.array(), assigned);
                    collectAssignedNames(index.index(), assigned);
                }
                case ArrayLiteralExpr array -> {
                    for (ExpressionNode element : array.elements()) {
                        collectAssignedNames(element, assigned);
                    }
                }
                case ObjectLiteralExpr object -> {
                    for (Property property : object.properties()) {
                        collectAssignedNames(property.value(), assigned);
                    }
                }
                case FunctionExpr functionExpr ->
                    collectAssignedNames(functionExpr.body().statements(), assigned);
                case HasExpr has -> { collectAssignedNames(has.object(), assigned); collectAssignedNames(has.key(), assigned); }
                case AssignmentExpr assignment -> {
                    if (assignment.target() instanceof IdentifierExpr target) {
                        assigned.add(target.name());
                    }
                    collectAssignedNames(assignment.target(), assigned);
                    collectAssignedNames(assignment.value(), assigned);
                }
                case TemplateLiteralExpr template -> {
                    for (ExpressionNode part : template.parts()) {
                        collectAssignedNames(part, assigned);
                    }
                }
                case AwaitExpression awaitExpression ->
                    collectAssignedNames(awaitExpression.callee(), assigned);
            }
        }

        /**
         * The declared type of a let declaration (the annotated type for
         * annotated declarations, the inferred type otherwise): resolved
         * from the checker's per-scoped-statement symbol table of the
         * innermost enclosing scope (or the root table at module level)
         * so nested declarations resolve without retaining any
         * {@code NameResolver} instance (D4); falls back to the
         * initializer's checked type when no symbol fact exists.
         */
        private Type declaredTypeOf(VariableDeclaration decl) {
            SymbolTable scope = currentCheckerScope();
            if (scope != null) {
                Symbol symbol = scope.resolveLocal(decl.name());
                if (symbol instanceof Symbol.VariableSymbol variable
                        && variable.type() != null) {
                    return variable.type();
                }
            }
            return checkedType(decl.initializer());
        }


        /**
         * The innermost checker scope for declared-type resolution: the
         * scope-map table of the innermost scope-keyed node currently
         * being walked, or the root symbol table at module level.
         */
        private SymbolTable currentCheckerScope() {
            if (checkerScopeNodes.isEmpty()) {
                return checks.symbolTable();
            }
            return checks.scopeMap().get(checkerScopeNodes.peek());
        }

        /** The block identity binding ALLOCs in the current site carry as their scope. */
        private BlockId currentBlock() {
            return blockStack.peek();
        }

        /**
         * Opens one function-body walk's assigned-name scope: the
         * identifier names the body's whole subtree assigns. The scope is
         * a conservative over-approximation of the binding resolution (a
         * shadowing declaration of the same name counts), which can only
         * cost a statically resolved callee, never a wrong one.
         */
        private void pushAssignedNameScope(List<StatementNode> body) {
            assignedNameScopes.push(assignedNamesIn(body));
        }

        /** Closes the innermost function-body assigned-name scope. */
        private void popAssignedNameScope() {
            if (!assignedNameScopes.isEmpty()) {
                assignedNameScopes.pop();
            }
        }

        /**
         * True iff the resolved binding is assigned anywhere in its
         * declaring function body (nested bodies included): no read of it
         * may publish the statically tracked allocation identity, and no
         * call through it may name a statically resolved callee — the cell
         * holds whichever function value the current execution stored, as
         * the spec's capture-by-binding rule requires.
         */
        private boolean assignedFunctionBinding(FrameEntry entry) {
            return assignedBinding(entry.cell().id, entry.cell().name);
        }


        private boolean assignedBinding(BindingId binding, String name) {
            Set<String> assigned = assignedNamesOfBinding.get(binding);
            return assigned != null && assigned.contains(name);
        }

        /** Pushes one binding-environment scope frame (innermost first). */
        private void pushBindingFrame() {
            bindingScopes.add(0, new LinkedHashMap<>());
        }

        /** Pops the innermost binding-environment scope frame. */
        private void popBindingFrame() {
            if (bindingScopes.isEmpty()) {
                throw new IllegalStateException(
                    "popBindingFrame without an open binding frame (producer defect)");
            }
            bindingScopes.remove(0);
        }


        private void registerBinding(String name, BindingId binding,
                                     BindingCoreIncarnation incarnation) {
            BindingCell cell = cellsById.get(binding);
            if (cell == null) {
                cell = new BindingCell(name, binding);
                cellsById.put(binding, cell);
                bindingCells.add(cell);
            }
            Set<String> declaringAssignedNames = assignedNameScopes.peek();
            if (declaringAssignedNames != null) {
                assignedNamesOfBinding.putIfAbsent(binding, declaringAssignedNames);
            }
            Map<String, FrameEntry> frame = bindingScopes.get(0);
            frame.put(name, new FrameEntry(cell, incarnation));
            cell.incarnations.add(incarnation);
        }

        /**
         * The innermost frame entry of a declared name (the dominant
         * incarnation at the site), or {@code null}.
         */
        private FrameEntry frameEntryOf(String name) {
            FrameResolution resolution = resolveFrame(name);
            return resolution == null ? null : resolution.entry();
        }

        /**
         * The innermost frame entry plus its frame index (innermost
         * first) of a declared name, or {@code null}: the resolution
         * surface the closure child's load/store arms and capture
         * collection use (B9 R1/R2/R3 as the resolution model — the
         * frame index decides whether a detached-body reference resolves
         * inside the function's own scope chain or is a capture).
         */
        private FrameResolution resolveFrame(String name) {
            for (int i = 0; i < bindingScopes.size(); i++) {
                FrameEntry entry = bindingScopes.get(i).get(name);
                if (entry != null) {
                    return new FrameResolution(entry, i);
                }
            }
            return null;
        }

        /** The innermost emission target of the session (the op list or a body buffer). */
        private List<SemanticOp> emitTarget() {
            return emitTargets.peek();
        }

        /**
         * Registers a body reference as a capture of every open
         * detached-body walk whose capture border the resolution lies
         * outside (B3/B9 R2/R3): the resolved incarnation joins the B2
         * cell-kind derivation's capture-reference set once, and the
         * captured cell joins each such walk's collector in
         * first-reference order. A reference resolving outside the
         * innermost walk's border is therefore additionally a free
         * reference of every enclosing walk whose own scope chain
         * excludes the resolved frame — the doubly-nested detaching
         * chain of B9 R2(ii): a closure created inside another detached
         * body resolves its captures in that body's context first, and
         * the enclosing body's captures must record the same binding so
         * the chain closes through every intermediate body
         * ({@code nested-closure-mutation.deal}'s inner-body capture →
         * make's body capture → the closing R1 step).
         */
        private void maybeRegisterCapture(String name, FrameResolution resolution) {
            if (!closureCore || captureBorders.isEmpty()) {
                return;
            }
            int frameCount = bindingScopes.size();
            if (resolution.frameIndex() < frameCount - captureBorders.peek()) {
                return; // the innermost function's own scope chain — not a capture at all.
            }
            Iterator<Integer> borders = captureBorders.iterator();
            Iterator<List<CapturedCell>> collectors = captureCollectors.iterator();
            while (borders.hasNext() && collectors.hasNext()) {
                int border = borders.next();
                List<CapturedCell> collector = collectors.next();
                // Frames pushed since the walk's entry (its own body
                // frame included) occupy indices [0, frameCount - border);
                // a resolution at or beyond that range is outside the
                // walk's own scope chain and must be its capture too.
                if (resolution.frameIndex() < frameCount - border) {
                    continue;
                }
                registerCaptureReference(resolution.entry(), collector);
            }
        }

        /**
         * The single capture-reference registration of the walk (B2's
         * closure-capture arm for this child): the incarnation the
         * capture resolves to joins the cell-kind derivation, and the
         * captured cell joins the given collector once (first-reference
         * order — deterministic capture lists). One reference event may
         * register into several open walks' collectors (the detaching-
         * chain propagation of {@link #maybeRegisterCapture}); the
         * cell-kind derivation's reference set has identity semantics,
         * so repeated registrations of one incarnation stay idempotent.
         */
        private void registerCaptureReference(FrameEntry entry,
                                              List<CapturedCell> collector) {
            cellKinds.registerCaptureReference(entry.incarnation());
            if (collector == null) {
                return;
            }
            for (CapturedCell captured : collector) {
                if (captured.cell() == entry.cell()) {
                    return;
                }
            }
            collector.add(new CapturedCell(entry.cell(), entry.incarnation()));
        }

        /**
         * The generation-pinned capture entries of one closed detached-body
         * walk: each captured cell's identity paired with the incarnation the
         * capture resolved to at the creation site (the frame-level dominant
         * incarnation at the reference site — the same resolution the
         * walk-finalization cell-kind derivation uses). The emitted
         * {@code CLOSURE_NEW}/{@code LoweredFunction} pairs name the
         * creation-site incarnation, never generation 0 by construction.
         */
        private static List<BindingGeneration> captureGenerations(
                List<CapturedCell> captured) {
            List<BindingGeneration> captures = new ArrayList<>();
            for (CapturedCell capture : captured) {
                captures.add(new BindingGeneration(capture.cell().id,
                    capture.incarnation().generation()));
            }
            return captures;
        }

        /**
         * Emits one {@code CLOSURE_NEW} op publishing the given
         * function-allocation identity, registers the {@code LoweredBody}
         * execution binding through the registry seam and the
         * {@code LoweredFunction} record, and records the closure facts
         * (resolved captures) in creation order. The closure publishes a
         * fresh function identity per creation (execution contract);
         * creation evaluates nothing.
         */
        private void emitClosureNew(FunctionId functionId, ValueId result,
                                    RuntimeDescriptor.Func signature,
                                    List<BindingGeneration> captures, BlockId bodyBlock,
                                    Span span, List<CapturedCell> captured,
                                    String sourceName) {
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            FunctionExecutionBinding.LoweredBody binding =
                new FunctionExecutionBinding.LoweredBody(functionId, bodyBlock);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.CLOSURE_NEW,
                new KindPayload.ClosureNewPayload(functionId, signature, captures, binding),
                result, signature, FailurePolicyId.NO_DEAL_FAILURE, origin));
            functions.put(functionId, new LoweredFunction(functionId, signature, captures,
                bodyBlock, sourceName));
            // Exactly one registration per allocation identity: a
            // pre-registered declared body (the preallocation guarantee of
            // {@link #lowerBindingFunctionDecl}) holds an identical
            // LoweredBody already — re-registering is a producer defect,
            // never an overwrite.
            FunctionAllocationIdentity identity =
                new FunctionAllocationIdentity(result.id());
            FunctionExecutionBinding previous = registry.registrationOf(identity);
            if (previous == null) {
                registry.registerClosure(identity, functionId, bodyBlock);
            } else if (!previous.equals(binding)) {
                throw new IllegalStateException("the allocation identity " + result
                    + " carries a different function-binding registration (" + previous
                    + ") than the produced CLOSURE_NEW body " + binding
                    + " (producer defect)");
            }
            List<ClosureCapture> captureFacts = new ArrayList<>();
            for (CapturedCell capture : captured) {
                captureFacts.add(new ClosureCapture(capture.cell().name, capture.cell().id,
                    capture.incarnation().generation(), capture.incarnation().scope(),
                    capture.incarnation().producer()));
            }
            closureFactsList.add(new ClosureFacts(functionId, signature, bodyBlock,
                captureFacts));
        }

        /**
         * The walk-finalization re-derivation of the B2 cell kinds over
         * the complete capture-reference union (whole-scope analysis: a
         * closure anywhere in the enclosing scope referencing a binding
         * upgrades every incarnation of that binding that a capture
         * resolves to). Every {@code BINDING_ALLOC} payload whose derived
         * kind differs from its emission-time kind is rebuilt in place
         * with the re-derived payload and contract digest — the final
         * emitted cell kinds flow from exactly one derivation.
         */
        private void finalizeCellKinds() {
            for (int i = 0; i < ops.size(); i++) {
                SemanticOp op = ops.get(i);
                if (!(op.payload() instanceof KindPayload.BindingAllocPayload alloc)) {
                    continue;
                }
                BindingCell cell = cellsById.get(alloc.binding());
                if (cell == null) {
                    continue; // not a binding of this walk's environment.
                }
                BindingCoreIncarnation incarnation = null;
                for (BindingCoreIncarnation candidate : cell.incarnations) {
                    if (candidate.generation() == alloc.generation()
                            && candidate.scope().equals(alloc.scope())) {
                        incarnation = candidate;
                        break;
                    }
                }
                if (incarnation == null) {
                    throw new IllegalStateException("BINDING_ALLOC of " + alloc.binding()
                        + " generation " + alloc.generation() + " in " + alloc.scope()
                        + " matches no registered incarnation (producer defect)");
                }
                BindingCellKind finalKind = cellKinds.cellKindOf(incarnation);
                if (finalKind == alloc.cellKind()) {
                    continue;
                }
                ops.set(i, rebuildAllocOp(op, finalKind));
            }
        }

        /**
         * Rebuilds one {@code BINDING_ALLOC} op with the final derived
         * cell kind: same identity, origin, result shape, operands, and
         * policy; the payload's cell kind and the contract snapshot
         * digest re-derive from the final payload through the single
         * canonicalizer path.
         */
        private SemanticOp rebuildAllocOp(SemanticOp op, BindingCellKind finalKind) {
            KindPayload.BindingAllocPayload alloc =
                (KindPayload.BindingAllocPayload) op.payload();
            KindPayload.BindingAllocPayload rebuilt = new KindPayload.BindingAllocPayload(
                alloc.binding(), alloc.scope(), alloc.mutable(), finalKind,
                alloc.generation());
            OperationContractSnapshot placeholder = contractOf(op.kind(), rebuilt,
                op.resultType(), op.operandTypes(), op.failurePolicy(), "placeholder");
            String digest = ContractSnapshotCanonicalizer.digest(placeholder);
            OperationContractSnapshot contract = contractOf(op.kind(), rebuilt,
                op.resultType(), op.operandTypes(), op.failurePolicy(), digest);
            return new SemanticOp(op.opId(), op.kind(), op.origin(), op.result(),
                op.resultType(), op.operands(), op.operandTypes(), rebuilt,
                op.failurePolicy(), contract);
        }

        /** The pinned origin span of module-init-top synthetic ops (the program span). */
        private Span moduleInitSpan() {
            return programSpan;
        }

        // ---------------------------------------------------------------------
        // The expression arms (D1)
        // ---------------------------------------------------------------------

        /**
         * Lowers one checked expression through the D1 shape map,
         * appending the produced operations to the session and returning
         * the produced value. Each expression lowers exactly once; the
         * produced subexpression ops precede the consuming op in
         * {@link #ops()}.
         *
         */
        public ValueId lowerExpression(ExpressionNode expr) {
            return lowerExpression(expr, null);
        }


        public ValueId lowerExpression(ExpressionNode expr, ValueId slot) {
            Objects.requireNonNull(expr, "expr must not be null");
            try {
                return lowerExpressionCore(expr, slot);
            } catch (ContainerPayloadDescriptors.Defect defect) {
                // The expression's checked fact corrupted a descriptor
                // derivation: the expression being lowered is the
                // identifiable offending construct, and an already
                // attached (inner) range always wins over this one (D2).
                throw defect.withRange(expr.span().range());
            }
        }

        private ValueId lowerExpressionCore(ExpressionNode expr, ValueId slot) {
            return switch (expr) {
                case AssertionExpr assertion -> {
                    ValueId value = lowerExpression(assertion.value());
                    RuntimeDescriptor descriptor = valueDescriptorOf(checkedType(assertion));
                    ValueId result = emitValueOp(SemanticOpKind.BOUNDARY,
                        new KindPayload.BoundaryPayload(BoundaryKind.ASSERTION, descriptor, value,
                            new BoundaryRealization.RuntimeValidation(CANONICAL_RUNTIME_VALIDATION_ID)),
                        assertion.span(), descriptor, descriptorKindPolicy(descriptor), slot);
                    registerDynamicMaterialization(result, producerOpId(result), descriptor);
                    yield result;
                }
                case InstanceofExpr test -> {
                    Type target = checkedInstanceofTarget(test);
                    ValueId value = lowerExpression(test.value());
                    yield emitOperandOp(SemanticOpKind.UNARY,
                        new KindPayload.UnaryPayload(UnarySelector.INSTANCEOF, valueDescriptorOf(target)),
                        List.of(value), List.of(valueDescriptorOf(checkedType(test.value()))),
                        test.span(), RuntimeDescriptor.Boolean.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, slot);
                }
                case LiteralExpr literal -> lowerConst(literal, slot);
                case IdentifierExpr identifier -> lowerBindingLoad(identifier, slot);
                case FunctionExpr functionExpr -> {
                    if (closureCore) {
                        yield lowerClosureExpr(functionExpr, slot);
                    }
                    throw new ConstructUnlowered(describeExpression(expr), expr.span());
                }
                case ArrayLiteralExpr array -> lowerArrayNew(array, slot);
                case ObjectLiteralExpr object -> lowerTableNew(object, slot);
                case MemberAccessExpr access -> lowerMemberAccess(access, slot);
                case BinaryExpr binary -> lowerBinary(binary, slot);
                case TemplateLiteralExpr template -> lowerTemplate(template, slot);
                case UnaryExpr unary -> lowerUnary(unary, slot);
                case CallExpr call -> lowerCallSite(call, slot);
                case AwaitExpression awaitExpression -> {
                    if (e7Calls) {
                        yield lowerAwaitExpression(awaitExpression, slot);
                    }
                    throw new ConstructUnlowered(describeExpression(expr), expr.span());
                }
                case IndexExpr index -> lowerIndexRead(index, slot);
                case AssignmentExpr assignment -> lowerAssignment(assignment, slot);
                case HasExpr has -> {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeExpression(expr), expr.span());
                    }
                    yield lowerHasField(has, slot);
                }
                default -> throw new ConstructUnlowered(describeExpression(expr), expr.span());
            };
        }

        /**
         * Lowers one checked for-of statement with a string-typed
         * iterable through the D1 arm: the iterable prior step, the
         * {@code FOR_EACH} op, then the body block's statements under the
         * loop-binding frame. The iterable is lowered through
         * {@link #lowerOperand(ExpressionNode)}: a null-narrowing-refined
         * identifier read is admitted by its {@code NARROWED_READ} cell,
         * so a capturing closure that cleared the binding reports the
         * typed-boundary failure before the loop dereferences the
         * iterable — the {@code FOR_EACH} consumes the admitted value and
         * the iterable still completes exactly once. An array-typed
         * iterable fails hard with {@link ConstructUnlowered}
         * ({@code FOR_EACH(ARRAY_VALUES)} is E5's).
         *
         */
        public OpId lowerForOfStatement(ForOfStatement stmt) {
            Objects.requireNonNull(stmt, "stmt must not be null");
            Type iterableType = checkedType(stmt.iterable());
            if (iterableType instanceof Type.Array arrayType) {
                return lowerArrayForOf(stmt, arrayType);
            }
            if (!(iterableType instanceof Type.String)) {
                throw new ConstructUnlowered("for-of over a non-string, non-array iterable "
                    + typeName(iterableType) + " (this stage lowers string and array "
                    + "iterables only)", stmt.span());
            }
            BlockId bodyBlock = allocateBlock();
            ValueId iterable = lowerOperand(stmt.iterable());
            ForEachFrame frame = openForEachScope(stmt.varName());
            openForEachBindingFrame(stmt.varName(), frame, bodyBlock);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(stmt.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.FOR_EACH,
                new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, iterable,
                    frame.binding(), frame.generation(), bodyBlock),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, origin));
            pushBlockParent(opId);
            pushBlock(bodyBlock);
            pushLoopTarget(opId);
            try {
                statementWalk.walk(stmt.body().statements(), false);
            } finally {
                popLoopTarget();
                popBlock();
                popBlockParent();
                closeForEachBindingFrame();
                closeForEachScope();
            }
            return opId;
        }

        /**
         * Opens the iteration binding's environment frame of one for-of arm
         * in the closure-core walk: the binding joins the binding
         * environment as an ordinary frame entry whose producing allocation
         * is the {@code FOR_EACH} op and whose incarnation is the pinned
         * {@code SHARED_CELL} iteration cell. A detached body's reference to
         * the iteration binding therefore resolves through the same frame
         * walk as any other binding and registers a capture
         * ({@code maybeRegisterCapture}), so the emitted closure publishes
         * the creation-site iteration incarnation and the body reads its
         * capture (B3/B9 R2; R4 item 5). The non-closure windows keep the
         * loop-frame-only environment (E6's arm).
         */
        private void openForEachBindingFrame(String name, ForEachFrame frame,
                                            BlockId bodyBlock) {
            if (!closureCore) {
                return;
            }
            pushBindingFrame();
            BindingCoreIncarnation iteration = new BindingCoreIncarnation(
                frame.generation(), bodyBlock, BindingCellKind.SHARED_CELL, false,
                BindingProducer.FOR_EACH, true);
            registerBinding(name, frame.binding(), iteration);
        }

        /** Closes the iteration binding's environment frame (a no-op off closure-core). */
        private void closeForEachBindingFrame() {
            if (!closureCore) {
                return;
            }
            popBindingFrame();
        }

        /**
         * {@code FOR_EACH(ARRAY_VALUES)} — the array for-of arm (C-D5):
         * the iterable operand completes exactly once before START as one
         * prior step in the enclosing block; the op evaluates the iterable
         * once, retains its reference, and visits increasing live positions
         * with the op's own {@code TYPE_DESCRIPTOR} terminal check against
         * the element descriptor derived from the recorded array operand
         * type ({@code [T]} → element {@code T}): each step re-reads the
         * carrier's live element count, so a contiguous append is visited;
         * the first position that reads absent (a deleted slot or a
         * position past the live end) stops the iteration, and a present
         * element — including a wrong one — is checked, never skipped
         * (a wrong element fails E8001 at the {@code FOR_EACH} origin before
         * the body runs); then a fresh binding per iteration
         * (mechanics E6 — the payload pins the binding identity and the
         * initial generation) and the body block executes.
         * {@code BREAK}/{@code CONTINUE} target this op's {@code OpId}.
         * The element type is derived fail-closed through the descriptor
         * bridge; an unrepresentable element is a producer defect.
         */
        /**
         * The element descriptor of one container position, through the
         * single {@link ContainerPayloadDescriptors} checked-type bridge.
         */
        private RuntimeDescriptor containerElementDescriptor(Type element, Span offending) {
            // A corrupted checked fact fails closed at the container position.
            try {
                return ContainerPayloadDescriptors.elementDescriptorOf(element);
            } catch (ContainerPayloadDescriptors.Defect defect) {
                throw defect.withRange(offending.range());
            }
        }

        /**
         * The array for-of arm: the iterable is lowered through
         * {@link #lowerOperand(ExpressionNode)}, so a null-narrowing-refined
         * identifier read is admitted by its {@code NARROWED_READ} cell
         * before this arm dereferences the carrier — a capturing closure
         * that cleared the binding raises the typed-boundary failure with
         * zero body visits, the {@code FOR_EACH} consumes the admitted
         * value, and the iterable still completes exactly once.
         */
        private OpId lowerArrayForOf(ForOfStatement stmt, Type.Array arrayType) {
            containerElementDescriptor(arrayType.element(), stmt.iterable().span());
            BlockId bodyBlock = allocateBlock();
            ValueId iterable = lowerOperand(stmt.iterable());
            ForEachFrame frame = openForEachScope(stmt.varName());
            openForEachBindingFrame(stmt.varName(), frame, bodyBlock);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(stmt.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.FOR_EACH,
                new KindPayload.ForEachPayload(IterationMode.ARRAY_VALUES, iterable,
                    frame.binding(), frame.generation(), bodyBlock),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, origin));
            pushBlockParent(opId);
            pushBlock(bodyBlock);
            pushLoopTarget(opId);
            try {
                statementWalk.walk(stmt.body().statements(), false);
            } finally {
                popLoopTarget();
                popBlock();
                popBlockParent();
                closeForEachBindingFrame();
                closeForEachScope();
            }
            return opId;
        }

        // ---------------------------------------------------------------------
        // The address-chain arms (E5; assignment-delete-address-chains A-D9)
        // ---------------------------------------------------------------------

        /**
         * {@code ASSIGN} — the address-chain dispatch over the closed
         * A-D9 map: the target's checked shape selects exactly one chain
         * (VARIABLE, TABLE_SLOT member/index, ARRAY_SLOT, CLASS_FIELD).
         * Each chain is one {@code ASSIGN} op with the pinned child order
         * (A-D2), the closed boundary production (A-D4), the single-last
         * commit (A-D5), the committed-value result (A-D6), and single
         * evaluation (A-D8): every child records the chain op as its
         * {@code parentOpId} and each source subexpression appears
         * exactly once as a producing op.
         *
         */
        public ValueId lowerAssignment(AssignmentExpr assignment) {
            return lowerAssignment(assignment, null);
        }

        /**
         * Lowers one checked assignment with an explicit result slot:
         * the slot threads to the RHS expression's final producing op,
         * whose value identity the {@code ASSIGN} op publishes (A-D6).
         *
         */
        public ValueId lowerAssignment(AssignmentExpr assignment, ValueId slot) {
            Objects.requireNonNull(assignment, "assignment must not be null");
            ExpressionNode target = assignment.target();
            if (target instanceof IdentifierExpr identifier) {
                if (closureCore) {
                    // The closure walk's variable-assignment arm: the
                    // store commits the dominant incarnation and, inside
                    // a detached-body walk, registers the reference as a
                    // capture (stores reference cells — capture by
                    // binding). The closure walk threads no result slot
                    // (the slot is the E5 LOOP(FOR) condition
                    // re-production's; the binding walk re-produces the
                    // condition with a fresh value).
                    return lowerVariableAssignClosure(assignment, identifier);
                }
                return lowerVariableAssign(assignment, identifier, slot);
            }
            if (target instanceof MemberAccessExpr access) {
                Type objectType = checkedType(access.object());
                if (objectType instanceof Type.Table) {
                    return lowerTableMemberAssign(assignment, access, slot);
                }
                if (objectType instanceof Type.Class classType) {
                    return lowerClassFieldAssign(assignment, access, classType, slot);
                }
                throw new ConstructUnlowered("assignment target '" + access.field()
                    + "' on " + typeName(objectType) + " (no closed A-D9 chain shape for "
                    + "this receiver: module members are E10's, array members carry "
                    + "no write shape)", access.span());
            }
            if (target instanceof IndexExpr index) {
                Type containerType = checkedType(index.array());
                if (containerType instanceof Type.Table) {
                    return lowerTableIndexAssign(assignment, index, slot);
                }
                if (containerType instanceof Type.Array arrayType) {
                    return lowerArrayIndexAssign(assignment, index, arrayType, slot);
                }
                throw new ConstructUnlowered("assignment target index on "
                    + typeName(containerType) + " (no closed A-D9 chain shape for this "
                    + "container)", index.span());
            }
            throw new ConstructUnlowered("assignment target "
                + target.getClass().getSimpleName() + " (no closed A-D9 chain shape)",
                    target.span());
        }

        /**
         * {@code DELETE} — the address-chain dispatch over the closed
         * A-D9 delete map: TABLE_SLOT member/index, ARRAY_SLOT, or
         * CLASS_FIELD; no RHS. The chain is one {@code DELETE} op with
         * the pinned order receiver → key → normalize → [array bounds
         * boundary] → commit; the result is {@code none} (A-D6).
         *
         */
        public void lowerDelete(DeleteStatement delete) {
            Objects.requireNonNull(delete, "delete must not be null");
            ExpressionNode target = delete.target();
            if (target instanceof MemberAccessExpr access) {
                Type objectType = checkedType(access.object());
                if (objectType instanceof Type.Table) {
                    lowerTableMemberDelete(delete, access);
                    return;
                }
                if (objectType instanceof Type.Class classType) {
                    lowerClassFieldDelete(delete, access, classType);
                    return;
                }
                throw new ConstructUnlowered("delete target '" + access.field() + "' on "
                    + typeName(objectType) + " (no closed A-D9 delete shape for this "
                    + "receiver: module members are E10's, array members carry no "
                    + "delete shape)", access.span());
            }
            if (target instanceof IndexExpr index) {
                Type containerType = checkedType(index.array());
                if (containerType instanceof Type.Table) {
                    lowerTableIndexDelete(delete, index);
                    return;
                }
                if (containerType instanceof Type.Array arrayType) {
                    lowerArrayIndexDelete(delete, index, arrayType);
                    return;
                }
                throw new ConstructUnlowered("delete target index on " + typeName(containerType)
                    + " (no closed A-D9 delete shape for this container)", index.span());
            }
            throw new ConstructUnlowered("delete target " + target.getClass().getSimpleName()
                + " (no closed A-D9 delete shape)", target.span());
        }

        private ValueId lowerVariableAssign(AssignmentExpr assignment, IdentifierExpr target) {
            if (closureCore) {
                return lowerVariableAssignClosure(assignment, target);
            }
            return lowerVariableAssign(assignment, target, null);
        }

        private ValueId lowerVariableAssign(AssignmentExpr assignment, IdentifierExpr target,
                                            ValueId slot) {
            ForEachFrame frame = null;
            for (ForEachFrame candidate : frames) {
                if (candidate.name().equals(target.name())) {
                    frame = candidate;
                    break;
                }
            }
            BindingId binding;
            long generation;
            BindingSite site = bindingSiteResolver == null
                ? null : bindingSiteResolver.resolve(target.name());
            if (frame != null) {
                binding = frame.binding();
                generation = frame.generation();
            } else if (site != null) {

                binding = site.binding();
                generation = site.generation();
            } else if (checks.symbolTable().resolve(target.name())
                    instanceof Symbol.VariableSymbol variable) {
                binding = variableBindings.computeIfAbsent(variable,
                    v -> ids.nextBindingId(module, nextOrdinal++, 0));
                generation = INITIAL_LOOP_GENERATION;
            } else {
                throw new ConstructUnlowered("assignment target '" + target.name()
                    + "' is not a variable binding in this stage's window (binding "
                    + "allocation is E6's; only loop bindings and declared variables "
                    + "carry a store identity here)", target.span());
            }
            return emitVariableAssignChain(assignment, target, binding, generation, slot);
        }

        /**
         * ASSIGN VARIABLE — the closure walk's variable-assignment arm
         * (closure-core mode): the target must be a declared binding of
         * the walk's environment; the store commits the dominant
         * incarnation at the assignment site (B9 R1) and, inside a
         * detached-body walk, registers the reference as a capture of the
         * open {@code CLOSURE_NEW} (stores reference cells — capture
         * by binding). A store of a function-typed value updates the
         * incarnation's statically tracked function identity (identity
         * preservation).
         */
        private ValueId lowerVariableAssignClosure(AssignmentExpr assignment,
                                                   IdentifierExpr target) {
            FrameResolution resolution = resolveFrame(target.name());
            if (resolution == null) {
                throw new ConstructUnlowered("assignment target '" + target.name()
                    + "' is not a declared binding of the closure walk's environment "
                    + "(module members are E10's)", target.span());
            }
            maybeRegisterCapture(target.name(), resolution);
            ValueId value = emitVariableAssignChain(assignment, target,
                resolution.entry().cell().id, resolution.entry().incarnation().generation(),
                null);
            RuntimeDescriptor targetDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(target));
            if (targetDescriptor instanceof RuntimeDescriptor.Func) {
                functionIdentity.put(resolution.entry().incarnation(), value);
            }
            return value;
        }

        /**
         * ASSIGN VARIABLE chain emission (the shared closed shape
         * {@code [valueOp, boundaryOp(VARIABLE_ASSIGNMENT),
         * commitOp(BINDING_STORE)]}): the value child, then exactly one
         * {@code VARIABLE_ASSIGNMENT} boundary carrying the target
         * binding's declared descriptor and the descriptor-kind policy
         * with input = the committed value, then the
         * {@code BINDING_STORE} commit storing
         * {@code {binding, generation, committed value}} (A-D4/A-D5).
         * The {@code ASSIGN} result is the committed value with
         * {@code resultType} = the declared target descriptor (A-D6).
         */
        private ValueId emitVariableAssignChain(AssignmentExpr assignment,
                                                IdentifierExpr target, BindingId binding,
                                                long generation, ValueId slot) {
            if (proofAnalysis) {
                // B7: any resolved variable assignment defeats exactly the
                // incarnation it names — the dominant incarnation at the
                // assignment site (conservative: any assignment in the
                // enclosing scope after the declaration, regardless of its
                // position relative to an adaptation site).
                assignmentAnalysis.recordAssignment(target.name(), binding, generation);
            }
            Type targetType = checkedType(target);
            if ((shapeMapAnalysis || e7Calls) && targetType instanceof Type.Func) {
                Type sourceType = producedType(assignment.value());
                if (AdapterCreationRule.variableDisposition(sourceType, targetType)
                        == AdapterCreationRule.Disposition.ADAPT) {
                    // The shape-map child's adapted assignment (B6-B9):
                    // exactly one FUNCTION_ADAPT op with its closed-map
                    // mode and per-mode payload from birth, produced
                    // inside the position's own chain before its
                    // VARIABLE_ASSIGNMENT boundary and wired as the
                    // boundary's direct input (the chain's committed
                    // value).
                    return emitAdaptedVariableAssignChain(assignment, target, binding,
                        generation, (Type.Func) sourceType, (Type.Func) targetType);
                }
            }
            RuntimeDescriptor targetDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(targetType);
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId valueOp;
            OpId boundaryOp;
            OpId commitOp;
            try {
                value = lowerExpression(assignment.value(), slot);
                valueOp = chainChildOp(value);
                FailurePolicyId boundaryPolicy = targetDescriptor instanceof RuntimeDescriptor.Func
                    ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_ASSIGNMENT,
                        targetDescriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    target.span(), boundaryPolicy, SourceOriginKind.SYNTHETIC, chainOpId);
                commitOp = emitNullOp(SemanticOpKind.BINDING_STORE,
                    new KindPayload.BindingStorePayload(binding, generation, value),
                    target.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.VARIABLE,
                    List.of(valueOp, boundaryOp, commitOp)),
                value, valueDescriptorOf(checkedType(assignment)), FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (creationRuleAnalysis) {
                classifyVariableAssignPosition(assignment, target, value, boundaryOp);
            }
            return value;
        }

        /**
         * The adapted variable-assignment chain: {@code [sourceOp,
         * FUNCTION_ADAPT, VARIABLE_ASSIGNMENT boundary, BINDING_STORE]}.
         * The source completes once, including a mutable binding read.
         * VALUE captures that evaluated function, not the source binding
         * or a replay thunk. The boundary checks and the commit stores
         * the adapter; ASSIGN publishes the original RHS identity/type.
         *
         */
        private ValueId emitAdaptedVariableAssignChain(AssignmentExpr assignment,
                                                       IdentifierExpr target,
                                                       BindingId binding, long generation,
                                                       Type.Func sourceType,
                                                       Type.Func targetType) {
            RuntimeDescriptor.Func sourceSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(sourceType);
            RuntimeDescriptor.Func targetSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(targetType);
            CaptureMode mode = CaptureMode.VALUE;
            // Assignment is a once-evaluated value position. In particular,
            // adapting a call or nested assignment stores the materialized
            // RHS, never a thunk that would replay its effects.
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId valueOp;
            OpId boundaryOp;
            OpId commitOp;
            EmittedAdapter adapter;
            AdaptSourceRef sourceRef;
            ValueId originalRhs;
            try {
                originalRhs = lowerExpression(assignment.value());
                valueOp = producerOpId(originalRhs);
                sourceRef = new AdaptSourceRef.Value(originalRhs);
                adapter = emitFunctionAdapt(mode, sourceRef, sourceSignature,
                    targetSignature, null, List.of(originalRhs), List.of(sourceSignature),
                    assignment.value().span());
                value = adapter.identity();
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_ASSIGNMENT,
                        targetSignature, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    target.span(), FailurePolicyId.FUNCTION_SIGNATURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                commitOp = emitNullOp(SemanticOpKind.BINDING_STORE,
                    new KindPayload.BindingStorePayload(binding, generation, value),
                    target.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            List<OpId> children = List.of(valueOp, adapter.opId(), boundaryOp, commitOp);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId,
                toSourceSpan(assignment.span()), SourceOriginKind.USER, anchor,
                currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.VARIABLE, children),
                originalRhs, sourceSignature, FailurePolicyId.NO_DEAL_FAILURE, origin));
            recordAdapterEmission(adapter.opId(), adapter.identity(), mode, sourceRef,
                sourceSignature, targetSignature, null, boundaryOp,
                BoundaryKind.VARIABLE_ASSIGNMENT);
            if (creationRuleAnalysis) {
                classifyVariableAssignPosition(assignment, target, value, boundaryOp);
            }
            return originalRhs;
        }

        /**
         * ASSIGN TABLE_SLOT member write — {@code [containerOp, valueOp,
         * commitOp(MEMBER_WRITE)]}: the member key is a literal string
         * (no keyOp) and the shape carries zero write-check boundaries
         * (A-D4: the spec pins table writes unchecked).
         */
        private ValueId lowerTableMemberAssign(AssignmentExpr assignment,
                                               MemberAccessExpr access) {
            return lowerTableMemberAssign(assignment, access, null);
        }

        private ValueId lowerTableMemberAssign(AssignmentExpr assignment,
                                               MemberAccessExpr access, ValueId slot) {
            if (access.object() instanceof IdentifierExpr identifier
                    && checks.symbolTable().resolve(identifier.name())
                        instanceof Symbol.ModuleSymbol) {
                throw new ConstructUnlowered("module member assignment '" + identifier.name()
                    + "." + access.field() + "' (EXPORT_* is E10's)", access.span());
            }
            RuntimeDescriptor targetDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(access));
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId containerOp;
            OpId valueOp;
            OpId commitOp;
            try {
                ValueId container = lowerOperand(access.object());
                containerOp = chainChildOp(container);
                value = lowerExpression(assignment.value(), slot);
                valueOp = chainChildOp(value);
                commitOp = emitNullOp(SemanticOpKind.MEMBER_WRITE,
                    new KindPayload.MemberWritePayload(container, access.field(), value),
                    access.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.TABLE_SLOT,
                    List.of(containerOp, valueOp, commitOp)),
                value, valueDescriptorOf(checkedType(assignment)), FailurePolicyId.NO_DEAL_FAILURE, origin));
            return value;
        }

        /**
         * ASSIGN TABLE_SLOT index write — {@code [containerOp, keyOp,
         * valueOp, normalizeOp(INDEX_NORMALIZE TABLE_WRITE),
         * commitOp(INDEX_WRITE)]}: the key is statically {@code string}
         * by the checker's E3018 gate (A-D10), so the normalize is total
         * with no coercion; its unused {@code currentLength} operand
         * references the raw key identity (no length read for table
         * targets, A-D3).
         */
        private ValueId lowerTableIndexAssign(AssignmentExpr assignment, IndexExpr index) {
            return lowerTableIndexAssign(assignment, index, null);
        }

        private ValueId lowerTableIndexAssign(AssignmentExpr assignment, IndexExpr index,
                                              ValueId slot) {
            if (!(checkedType(index.index()) instanceof Type.String)) {
                throw new ConstructUnlowered("table index assignment key of checked type "
                    + typeName(checkedType(index.index())) + " (A-D10's E3018 checker gate "
                    + "pins every table index write key to static string — a non-string "
                    + "key reaching lowering is a producer defect)", index.span());
            }
            RuntimeDescriptor targetDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(index));
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId containerOp;
            OpId keyOp;
            OpId valueOp;
            OpId normalizeOp;
            OpId commitOp;
            try {
                ValueId container = lowerOperand(index.array());
                containerOp = chainChildOp(container);
                ValueId key = lowerOperand(index.index());
                keyOp = chainChildOp(key);
                value = lowerExpression(assignment.value(), slot);
                valueOp = chainChildOp(value);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.TABLE_WRITE, key, key),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(normalizedSlot);
                commitOp = emitNullOp(SemanticOpKind.INDEX_WRITE,
                    new KindPayload.IndexWritePayload(container, normalizedSlot, value),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.TABLE_SLOT,
                    List.of(containerOp, keyOp, valueOp, normalizeOp, commitOp)),
                value, valueDescriptorOf(checkedType(assignment)), FailurePolicyId.NO_DEAL_FAILURE, origin));
            return value;
        }

        /**
         * ASSIGN ARRAY_SLOT write — {@code [containerOp, keyOp, valueOp,
         * lengthOp(ARRAY_LENGTH), normalizeOp(INDEX_NORMALIZE
         * ARRAY_WRITE), boundaryOp(ARRAY_ELEMENT_ASSIGNMENT +
         * ARRAY_WRITE_BOUNDS_THEN_ELEMENT), commitOp(INDEX_WRITE)]}: the
         * length read pins at normalize time (after key and RHS), the
         * boundary enforces {@code <0}/{@code >length} then the element
         * descriptor with input = the checked RHS value, and the commit
         * appends exactly when the normalize computed
         * {@code index == length} (the append idiom's standard shape).
         * A function-typed element position whose RHS is assignable but
         * not exact commits the arity-extension adapter instead: the
         * materialized RHS keeps its own child position (evaluated after
         * the receiver and the key) and the adapter follows it
         * ({@code [containerOp, keyOp, rhsOp, FUNCTION_ADAPT adapterOp,
         * lengthOp, normalizeOp, boundaryOp, commitOp]}), so the boundary
         * input and the commit's stored value are the adapter's result; a
         * binding source adapts over its cell instead (zero evaluation at
         * the position) and the adapter is the value child itself
         * ({@code [containerOp, keyOp, FUNCTION_ADAPT adapterOp,
         * lengthOp, normalizeOp, boundaryOp, commitOp]}). Every child is
         * named through {@link #chainChildOp(ValueId)}, so a child
         * produced inside a selected block stays under its structure op.
         */
        private ValueId lowerArrayIndexAssign(AssignmentExpr assignment, IndexExpr index,
                                              Type.Array arrayType) {
            return lowerArrayIndexAssign(assignment, index, arrayType, null);
        }

        private ValueId lowerArrayIndexAssign(AssignmentExpr assignment, IndexExpr index,
                                              Type.Array arrayType, ValueId slot) {
            RuntimeDescriptor elementDescriptor =
                containerElementDescriptor(arrayType.element(), index.array().span());
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId containerOp;
            OpId keyOp;
            OpId valueOp;
            OpId lengthOp;
            OpId normalizeOp;
            OpId boundaryOp;
            OpId commitOp;
            OpId adapterOp = null;
            OpId rhsOp = null;
            ValueId originalRhs;
            try {
                ValueId container = lowerOperand(index.array());
                containerOp = chainChildOp(container);
                ValueId key = lowerOperand(index.index());
                keyOp = chainChildOp(key);
                originalRhs = lowerExpression(assignment.value(), slot);
                if (positionAdapts(assignment.value(), elementDescriptor)) {
                    // The array element write is a statically typed
                    // function-value position: the RHS value keeps its own
                    // chain position (it is evaluated after the receiver and
                    // the key), and exactly one arity-extension adapter over
                    // it is the chain's committed value, so the
                    // ARRAY_ELEMENT_ASSIGNMENT cell validates the element
                    // descriptor exactly and the array stores the adapted
                    // value (docs/spec-v1.3.md Function type compatibility;
                    // Arity extension adapter). A binding source adapts over
                    // its dominant incarnation's cell (zero evaluation at the
                    // position), so the chain then carries the adapter alone.
                    value = lowerPositionAdapter(assignment.value(),
                        (RuntimeDescriptor.Func) elementDescriptor, () -> originalRhs, true);
                    adapterOp = chainChildOp(value);
                    rhsOp = chainChildOp(originalRhs);
                } else {
                    value = originalRhs;
                }
                valueOp = chainChildOp(value);
                ValueId length = emitChainChildOp(SemanticOpKind.ARRAY_LENGTH,
                    new KindPayload.ArrayLengthPayload(container), index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                lengthOp = producerOpId(length);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_WRITE, key, length),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(normalizedSlot);
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
                        elementDescriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    index.span(), FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                if (adapterOp != null) {
                    recordPositionAdapterAt(value, boundaryOp,
                        BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT);
                }
                commitOp = emitNullOp(SemanticOpKind.INDEX_WRITE,
                    new KindPayload.IndexWritePayload(container, normalizedSlot, value),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            List<OpId> chainChildren;
            if (adapterOp != null && rhsOp != null) {
                // [receiver, key, RHS value, FUNCTION_ADAPT adapter,
                // length, normalize, ARRAY_ELEMENT_ASSIGNMENT, commit]
                chainChildren = List.of(containerOp, keyOp, rhsOp, adapterOp, lengthOp,
                    normalizeOp, boundaryOp, commitOp);
            } else {
                // [receiver, key, committed value (the adapter for a binding
                // source), length, normalize, ARRAY_ELEMENT_ASSIGNMENT,
                // commit]
                chainChildren = List.of(containerOp, keyOp, valueOp, lengthOp, normalizeOp,
                    boundaryOp, commitOp);
            }
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.ARRAY_SLOT, chainChildren),
                originalRhs, valueDescriptorOf(checkedType(assignment)), FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (creationRuleAnalysis) {
                recordPositionClassification(BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
                    producedType(assignment.value()), arrayType.element(), value, boundaryOp);
            }
            return originalRhs;
        }


        private ValueId lowerClassFieldAssign(AssignmentExpr assignment,
                                              MemberAccessExpr access, Type.Class classType) {
            return lowerClassFieldAssign(assignment, access, classType, null);
        }

        private ValueId lowerClassFieldAssign(AssignmentExpr assignment,
                                              MemberAccessExpr access, Type.Class classType,
                                              ValueId slot) {
            RuntimeDescriptor fieldDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(access));
            ClassId classId = new ClassId(DescriptorService.semanticModulePath(classType.identity()), classType.name());

            RuntimeDescriptor declaredDescriptor = null;
            RuntimeDescriptor classDescriptor = null;
            deal.semantic.ir.ClassFactoryId providerRef = null;
            if (classCore) {
                // The exact declaring provider of the receiver's checked
                // declaration (a harness-synthesized identity can carry two
                // declarations): the recorded declaration's module selects
                // the provider's registered facts, never the identity-keyed
                // first match and never a unit-local mask.
                FieldReceiverResolution resolution = resolveFieldReceiver(
                    access.object(), classId, "class field write '" + access.field() + "'",
                    access.span());
                providerRef = resolution.providerRef();
                deal.semantic.ir.ClassLayout layout = resolution.layout();
                if (layout == null) {
                    throw new ConstructUnlowered("class field write '" + access.field()
                        + "' on " + classId + " without a resolvable provider layout "
                        + "(the declared field descriptor comes from the unit's own "
                        + "classLayouts, the exact imported provider's shared-factory "
                        + "facts, or the declaration seeds; an unresolved required fact "
                        + "stays fail closed)", access.span());
                }
                deal.semantic.ir.ClassLayout.FieldLayout fieldLayout = null;
                for (deal.semantic.ir.ClassLayout.FieldLayout candidate : layout.fields()) {
                    if (candidate.name().equals(access.field())) {
                        fieldLayout = candidate;
                        break;
                    }
                }
                if (fieldLayout == null) {
                    throw new ConstructUnlowered("class field write '" + access.field()
                        + "' on " + classId + " names an undeclared field (the checker "
                        + "admits declared fields only — a fact defect)", access.span());
                }
                declaredDescriptor = fieldLayout.descriptor();
                try {
                    classDescriptor = DescriptorService.describe(classType);
                } catch (DescriptorService.Defect defect) {
                    throw new ConstructUnlowered("class field write '" + access.field()
                        + "' on " + classId + " of unrepresentable receiver type ("
                        + defect.getMessage() + ")", access.span());
                }
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId containerOp;
            OpId valueOp;
            OpId commitOp;
            OpId adapterOp = null;
            OpId rhsOp = null;
            OpId fieldBoundaryOp = null;
            ValueId originalRhs;
            try {
                ValueId container = lowerExpression(access.object());
                containerOp = chainChildOp(container);
                originalRhs = lowerExpression(assignment.value(), slot);
                if (classCore && positionAdapts(assignment.value(), declaredDescriptor)) {
                    // The class field write is a statically typed
                    // function-value position: exactly one arity-extension
                    // adapter over the RHS is the committed value, so the
                    // CLASS_FIELD_ASSIGNMENT cell validates the declared
                    // field descriptor exactly and the field stores the
                    // adapted value (docs/spec-v1.3.md Function type
                    // compatibility; Arity extension adapter). A binding
                    // source adapts over its dominant incarnation's cell
                    // (zero evaluation at the position), so the chain then
                    // carries the adapter alone.
                    value = lowerPositionAdapter(assignment.value(),
                        (RuntimeDescriptor.Func) declaredDescriptor, () -> originalRhs, true);
                    adapterOp = chainChildOp(value);
                    rhsOp = chainChildOp(originalRhs);
                } else {
                    value = originalRhs;
                }
                valueOp = chainChildOp(value);
                commitOp = emitNullOp(SemanticOpKind.FIELD_WRITE,
                    new KindPayload.FieldWritePayload(container, classId, access.field(),
                        value, providerRef),
                    access.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                if (classCore) {
                    emitNullOp(SemanticOpKind.BOUNDARY,
                        new KindPayload.BoundaryPayload(BoundaryKind.UNTYPED_CLASS_INPUT,
                            classDescriptor, container,
                            new BoundaryRealization.RuntimeValidation(
                                CANONICAL_RUNTIME_VALIDATION_ID)),
                        access.span(), descriptorKindPolicy(classDescriptor),
                        SourceOriginKind.SYNTHETIC, commitOp);
                    fieldBoundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                        new KindPayload.BoundaryPayload(BoundaryKind.CLASS_FIELD_ASSIGNMENT,
                            declaredDescriptor, value,
                            new BoundaryRealization.RuntimeValidation(
                                CANONICAL_RUNTIME_VALIDATION_ID)),
                        access.span(), descriptorKindPolicy(declaredDescriptor),
                        SourceOriginKind.SYNTHETIC, commitOp);
                    if (adapterOp != null) {
                        recordPositionAdapterAt(value, fieldBoundaryOp,
                            BoundaryKind.CLASS_FIELD_ASSIGNMENT);
                    }
                }
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            List<OpId> chainChildren;
            if (adapterOp != null && rhsOp != null) {
                // [receiver, RHS value, FUNCTION_ADAPT adapter, FIELD_WRITE commit]
                chainChildren = List.of(containerOp, rhsOp, adapterOp, commitOp);
            } else {
                // [receiver, committed value (the adapter for a binding
                // source), FIELD_WRITE commit]
                chainChildren = List.of(containerOp, valueOp, commitOp);
            }
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.CLASS_FIELD, chainChildren),
                originalRhs, valueDescriptorOf(checkedType(assignment)), FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (creationRuleAnalysis && classCore) {
                recordPositionClassification(BoundaryKind.CLASS_FIELD_ASSIGNMENT,
                    producedType(assignment.value()), declaredDescriptor,
                    fieldBoundaryOp != null ? fieldBoundaryOp : commitOp);
            }
            return originalRhs;
        }

        /**
         * DELETE TABLE_SLOT member — {@code [containerOp,
         * commitOp(MEMBER_DELETE)]}: the literal string key, no keyOp,
         * no normalize, no bounds boundary (A-D9).
         */
        private void lowerTableMemberDelete(DeleteStatement delete, MemberAccessExpr access) {
            if (access.object() instanceof IdentifierExpr identifier
                    && checks.symbolTable().resolve(identifier.name())
                        instanceof Symbol.ModuleSymbol) {
                throw new ConstructUnlowered("module member delete '" + identifier.name()
                    + "." + access.field() + "' (EXPORT_* is E10's)", access.span());
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId commitOp;
            try {
                ValueId container = lowerOperand(access.object());
                containerOp = chainChildOp(container);
                commitOp = emitNullOp(SemanticOpKind.MEMBER_DELETE,
                    new KindPayload.MemberDeletePayload(container, access.field()),
                    access.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.DELETE,
                new KindPayload.DeletePayload(DeleteTargetKind.TABLE_SLOT,
                    List.of(containerOp, commitOp)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        /**
         * DELETE TABLE_SLOT index — {@code [containerOp, keyOp,
         * normalizeOp(INDEX_NORMALIZE TABLE_WRITE),
         * commitOp(INDEX_DELETE)]}: the write-mode normalize (delete is a
         * mutation context; the closed {@link IndexMode} set is not
         * extended) with the statically-string key of A-D10's E3018 gate.
         */
        private void lowerTableIndexDelete(DeleteStatement delete, IndexExpr index) {
            if (!(checkedType(index.index()) instanceof Type.String)) {
                throw new ConstructUnlowered("table index delete key of checked type "
                    + typeName(checkedType(index.index())) + " (A-D10's E3018 checker gate "
                    + "pins every table index delete key to static string — a non-string "
                    + "key reaching lowering is a producer defect)", index.span());
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId keyOp;
            OpId normalizeOp;
            OpId commitOp;
            try {
                ValueId container = lowerOperand(index.array());
                containerOp = chainChildOp(container);
                ValueId key = lowerOperand(index.index());
                keyOp = chainChildOp(key);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.TABLE_WRITE, key, key),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(normalizedSlot);
                commitOp = emitNullOp(SemanticOpKind.INDEX_DELETE,
                    new KindPayload.IndexDeletePayload(container, normalizedSlot),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.DELETE,
                new KindPayload.DeletePayload(DeleteTargetKind.TABLE_SLOT,
                    List.of(containerOp, keyOp, normalizeOp, commitOp)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        /**
         * DELETE ARRAY_SLOT — {@code [containerOp, keyOp,
         * lengthOp(ARRAY_LENGTH), normalizeOp(INDEX_NORMALIZE
         * ARRAY_WRITE), boundaryOp(ARRAY_ELEMENT_DELETE +
         * ARRAY_DELETE_BOUNDS), commitOp(INDEX_DELETE)]}: exactly one
         * bounds boundary whose input is the normalized index (E8002
         * {@code array index out of bounds} for {@code <0}/{@code >length}
         * at the delete-target origin; {@code == length} is a permitted
         * no-op commit, A-D4).
         */
        private void lowerArrayIndexDelete(DeleteStatement delete, IndexExpr index,
                                           Type.Array arrayType) {
            RuntimeDescriptor elementDescriptor =
                containerElementDescriptor(arrayType.element(), index.array().span());
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId keyOp;
            OpId lengthOp;
            OpId normalizeOp;
            OpId boundaryOp;
            OpId commitOp;
            try {
                ValueId container = lowerOperand(index.array());
                containerOp = chainChildOp(container);
                ValueId key = lowerOperand(index.index());
                keyOp = chainChildOp(key);
                ValueId length = emitChainChildOp(SemanticOpKind.ARRAY_LENGTH,
                    new KindPayload.ArrayLengthPayload(container), index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                lengthOp = producerOpId(length);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_WRITE, key, length),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(normalizedSlot);
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_ELEMENT_DELETE,
                        elementDescriptor, normalizedSlot,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    index.span(), FailurePolicyId.ARRAY_DELETE_BOUNDS,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                commitOp = emitNullOp(SemanticOpKind.INDEX_DELETE,
                    new KindPayload.IndexDeletePayload(container, normalizedSlot),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.DELETE,
                new KindPayload.DeletePayload(DeleteTargetKind.ARRAY_SLOT,
                    List.of(containerOp, keyOp, lengthOp, normalizeOp, boundaryOp, commitOp)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        private void lowerClassFieldDelete(DeleteStatement delete, MemberAccessExpr access,
                                           Type.Class classType) {
            ClassId classId = new ClassId(DescriptorService.semanticModulePath(classType.identity()), classType.name());
            RuntimeDescriptor classDescriptor = null;
            deal.semantic.ir.ClassFactoryId providerRef = null;
            if (classCore) {
                // The exact declaring provider of the receiver's checked
                // declaration (a harness-synthesized identity can carry two
                // declarations): the required/optional decision belongs to
                // the declaring declaration only.
                providerRef = resolveFieldReceiver(access.object(), classId,
                    "class field delete '" + access.field() + "'",
                    access.span()).providerRef();
                try {
                    classDescriptor = DescriptorService.describe(classType);
                } catch (DescriptorService.Defect defect) {
                    throw new ConstructUnlowered("class field delete '" + access.field()
                        + "' on " + classId + " of unrepresentable receiver type ("
                        + defect.getMessage() + ")", access.span());
                }
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(access.object());
                containerOp = chainChildOp(container);
                commitOp = emitNullOp(SemanticOpKind.FIELD_DELETE,
                    new KindPayload.FieldDeletePayload(container, classId,
                        access.field(), providerRef),
                    access.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                if (classCore) {
                    emitNullOp(SemanticOpKind.BOUNDARY,
                        new KindPayload.BoundaryPayload(BoundaryKind.UNTYPED_CLASS_INPUT,
                            classDescriptor, container,
                            new BoundaryRealization.RuntimeValidation(
                                CANONICAL_RUNTIME_VALIDATION_ID)),
                        access.span(), descriptorKindPolicy(classDescriptor),
                        SourceOriginKind.SYNTHETIC, commitOp);
                }
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.DELETE,
                new KindPayload.DeletePayload(DeleteTargetKind.CLASS_FIELD,
                    List.of(containerOp, commitOp)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        /**
         * The producing op of a completed value: the last op in the
         * emitted list publishing that {@link ValueId} (the chain op
         * itself is emitted after its children, so a nested chain as the
         * value child resolves to the inner {@code ASSIGN} op, which
         * publishes the committed value).
         */
        private OpId producerOpId(ValueId value) {
            List<SemanticOp> target = emitTarget();
            for (int i = target.size() - 1; i >= 0; i--) {
                SemanticOp op = target.get(i);
                if (value.equals(op.result())) {
                    return op.opId();
                }
            }
            throw new IllegalStateException("no produced op publishes value " + value
                + " (producer defect)");
        }

        /**
         * The producer of one chain child's operand value at the chain's
         * own position (A-D2): the producing op when it is a member of
         * the emitting block, otherwise the outermost structure op whose
         * selected block contains it.
         *
         * <p>An {@code &&}/{@code ||} right operand is lowered into its
         * branch's selected block, so its value is published inside that
         * block while the branch op itself is a member of the enclosing
         * block. A chain executes its children in the enclosing block:
         * naming the block-internal producer as the child would flatten
         * the conditionally executed operand out of its block and execute
         * it unconditionally, skipping no exception and no effect. Naming
         * the owning branch instead keeps the operand inside the block the
         * chain reproduces ({@code BRANCH} and its selected block execute
         * together), so the short-circuit decision still guards the
         * operand's effects.</p>
         */
        private OpId chainChildOp(ValueId value) {
            OpId opId = producerOpId(value);
            BlockId enclosing = blockStack.peek();
            for (int steps = 0; steps < opBlocks.size(); steps++) {
                BlockId block = opBlocks.get(opId);
                if (block == null || block.equals(enclosing)) {
                    return opId;
                }
                OpId owner = blockOwners.get(block);
                if (owner == null) {
                    // A block no structure op owns (a function/module body)
                    // is never a selected block: keep the producer.
                    return opId;
                }
                opId = owner;
            }
            throw new IllegalStateException("the chain child value " + value
                + " has no enclosing structure op inside the emitting block"
                + " (producer defect)");
        }

        /**
         * Emits one value-producing chain child (the normalize-phase
         * machinery: {@code ARRAY_LENGTH} length reads and
         * {@code INDEX_NORMALIZE} slot computations) parented to the
         * innermost chain op (A-D2).
         */
        private ValueId emitChainChildOp(SemanticOpKind kind, KindPayload payload, Span span,
                                         RuntimeDescriptor resultType,
                                         FailurePolicyId policy) {
            OpId parent = chainParents.peek();
            if (parent == null) {
                throw new IllegalStateException("chain child emission outside an address "
                    + "chain (producer defect)");
            }
            ValueId value = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, parent);
            emit(buildOp(opId, kind, payload, value, resultType, List.of(), List.of(),
                policy, origin));
            return value;
        }

        // ---------------------------------------------------------------------
        // Unit production (S1)
        // ---------------------------------------------------------------------

        public LoweredModuleUnit buildUnit(Map<ConstructKind, List<SemanticOpKind>>
                                               constructCoverage,
                                           List<ModuleId> imports,
                                           SemanticProfile profile,
                                           String interfaceHash,
                                           String capabilityRegistryHash) {
            return buildUnit(constructCoverage, imports, profile, interfaceHash,
                capabilityRegistryHash, ContainerClaimingSeam.E5_GATE_ACTIVATION);
        }

        /**
         * Builds the validated unit under the named claiming-seam
         * activation state (the binding-core entry passes the pinned
         * E6-gate activation — {@code BINDINGS} activates for
         * {@code BINDING_LOAD} — while the E5 window derives the claim
         * set under the E5-gate activation:
         * {@code CONTAINERS_AND_STRINGS} and {@code EVALUATION_ORDER}
         * activate). The carried {@code profile} is the invocation's
         * active profile: it is recorded on the unit and derives the
         * unit's {@code loweringContextHash} — never a profile projected
         * from a hard-coded constant.
         */
        public LoweredModuleUnit buildUnit(Map<ConstructKind, List<SemanticOpKind>>
                                               constructCoverage,
                                           List<ModuleId> imports,
                                           SemanticProfile profile,
                                           String interfaceHash,
                                           String capabilityRegistryHash,
                                           Set<SemanticCapability> activation) {
            Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
            Objects.requireNonNull(imports, "imports must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
            Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
            Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
            Objects.requireNonNull(activation, "activation must not be null");
            EnumMap<ConstructKind, List<SemanticOpKind>> coverage =
                new EnumMap<>(ConstructKind.class);
            for (Map.Entry<ConstructKind, List<SemanticOpKind>> entry
                    : constructCoverage.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "constructCoverage keys must not be null");
                Objects.requireNonNull(entry.getValue(), "constructCoverage values must not be null");
                if (!entry.getValue().equals(entry.getKey().mappedOpKinds())) {
                    throw new IllegalArgumentException(
                        "constructCoverage rows carry the closed construct→op detector "
                            + "table's mapped op kinds verbatim (S4): row " + entry.getKey()
                            + " must be exactly " + entry.getKey().mappedOpKinds()
                            + ", got " + entry.getValue());
                }
                coverage.put(entry.getKey(), List.copyOf(entry.getValue()));
            }

            AnchorId moduleInitAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId moduleInitOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin moduleInitOrigin = new SourceOrigin(sourceId,
                toSourceSpan(moduleInitSpan()), SourceOriginKind.SYNTHETIC,
                moduleInitAnchor, null);
            emitUnattached(buildOp(moduleInitOpId, SemanticOpKind.MODULE_INIT,
                new KindPayload.ModuleInitPayload(module, List.copyOf(imports),
                    moduleInitBlock),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, moduleInitOrigin));
            List<SemanticOp> produced = List.copyOf(ops);
            Set<SemanticCapability> claims = ContainerClaimingSeam.deriveClaims(produced,
                activation);
            ContainerClaimingSeam.SeamResult seam = ContainerClaimingSeam.check(produced,
                activation, claims, module);
            if (seam.failure() != null) {
                throw new IllegalStateException(
                    "the claiming seam's derivation-invariant guard fired inside the unit "
                        + "producer while the unit claims exactly its derived claim set — a "
                        + "producer defect: " + seam.failure().origin());
            }
            this.producedBodyTable = computeBodyTable();
            this.producedClassFactories = computeFactoryRegistry();
            this.producedClassBindingFacts = computePinnedWriteFacts();
            return new LoweredModuleUnit(
                LoweredModuleUnit.FORMAT_VERSION,
                profile,
                module,
                interfaceHash,
                LoweringContextHash.of(profile, capabilityRegistryHash),
                claims,
                coverage,
                new LinkedHashMap<>(classLayouts),
                Map.copyOf(functions),
                new ModuleInitPlan(List.copyOf(imports), moduleInitBlock),
                ExportPlan.empty(),
                registry.bindings(),
                ops(),
                producedBodyTable,
                producedClassFactories,
                producedClassBindingFacts);
        }

        /**
         * Builds the produced {@link StructuredBodyTable} (C-D1): the
         * ordered ops per block and the inverse membership over every
         * allocated block of the session — empty child blocks included.
         * Every op emitted by the session is a member of exactly one
         * block; the module-init block is the root of the module-level
         * statements. The table is validated with the unit by
         * {@link ControlFlowValidator} at the unit-production seam
         * (C-D2).
         *
         */
        public StructuredBodyTable bodyTable() {
            if (producedBodyTable != null) {
                return producedBodyTable;
            }
            return computeBodyTable();
        }

        /** The fresh block-membership table of the live session state. */
        private StructuredBodyTable computeBodyTable() {
            Map<BlockId, List<OpId>> ordered = new java.util.LinkedHashMap<>();
            for (Map.Entry<BlockId, List<OpId>> entry : blockOps.entrySet()) {
                ordered.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            Map<OpId, BlockId> inverse = new java.util.LinkedHashMap<>();
            for (Map.Entry<OpId, BlockId> entry : opBlocks.entrySet()) {
                inverse.put(entry.getKey(), entry.getValue());
            }
            return new StructuredBodyTable(ordered, inverse);
        }

        /**
         * Installs the module's resolved import facts (the console-stdlib
         * detection surface of the full-program entry).
         */
        public void setModuleImports(List<ResolvedImport> imports) {
            this.moduleImports = List.copyOf(imports);
        }

        /**
         * Installs the project lowering's provider-resolution context: the
         * provider-keyed shared-factory facts of the preceding closure
         * modules, the declared-class construction entries of the project
         * interface index (declaring module, then canonical class
         * identity), and the module's recorded contextual class-literal
         * provider aliases (the literal's source range to the import
         * alias whose exact declaration supplied its construction
         * context).
         */
        public void setProviderFactoryFacts(
                Map<deal.semantic.ir.ClassFactoryId, SharedFactoryFacts>
                    providerFactoryFacts,
                Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>>
                    providerConstructionEntries,
                Map<DiagnosticRange, String> recordedClassLiteralProviders) {
            java.util.Objects.requireNonNull(providerFactoryFacts,
                "providerFactoryFacts must not be null");
            java.util.Objects.requireNonNull(providerConstructionEntries,
                "providerConstructionEntries must not be null");
            java.util.Objects.requireNonNull(recordedClassLiteralProviders,
                "recordedClassLiteralProviders must not be null");
            this.providerFactoryFacts = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(providerFactoryFacts));
            java.util.Map<ModuleId, Map<ClassId, deal.semantic.ir.ClassFactoryId>>
                copiedEntries = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<ModuleId,
                    Map<ClassId, deal.semantic.ir.ClassFactoryId>> entry
                    : providerConstructionEntries.entrySet()) {
                copiedEntries.put(entry.getKey(), java.util.Collections
                    .unmodifiableMap(new java.util.LinkedHashMap<>(
                        entry.getValue())));
            }
            this.providerConstructionEntries = java.util.Collections
                .unmodifiableMap(copiedEntries);
            this.recordedClassLiteralProviders = java.util.Collections
                .unmodifiableMap(new java.util.LinkedHashMap<>(
                    recordedClassLiteralProviders));
        }

        public void setRegistrationSeeds(ClassRegistrationSeeds seeds) {
            this.registrationSeeds = Objects.requireNonNull(seeds,
                "seeds must not be null");
        }

        /**
         * Installs the project-wide set of canonical class identities
         * declared by more than one module (the same-directory sibling
         * pair): a field operation or JSON link of such an identity
         * without a recorded exact declaring provider fails closed rather
         * than selecting a first alias.
         */
        public void setProjectAmbiguousClassIds(java.util.Set<ClassId> ambiguous) {
            java.util.Objects.requireNonNull(ambiguous,
                "ambiguous must not be null");
            this.projectAmbiguousClassIds = java.util.Collections
                .unmodifiableSet(new java.util.LinkedHashSet<>(ambiguous));
        }

        /**
         * Installs the project-wide set of canonical class identities
         * carrying more than one plan-bearing ({@code @jsonable})
         * declaration: only such an identity's generated JSON plan key is
         * ambiguous, so only its nested JSON links fail closed. A
         * same-identity declaration without a generated plan registers no
         * plan and keeps the single-plan link exact.
         */
        public void setAmbiguousJsonPlanClassIds(java.util.Set<ClassId> ambiguous) {
            java.util.Objects.requireNonNull(ambiguous,
                "ambiguous must not be null");
            this.ambiguousJsonPlanClassIds = java.util.Collections
                .unmodifiableSet(new java.util.LinkedHashSet<>(ambiguous));
        }

        /**
         * Installs the recorded plan owners of the project's
         * multiple-declaration class identities (the unique plan-bearing
         * declaration when one exists, the emission order's last
         * declaration otherwise) and the owner's construction entry when
         * exported: every nested JSON link and its validation resolves
         * the owner declaration's own layout.
         */
        public void setJsonPlanOwners(Map<ClassId, ModuleId> owners,
                Map<ClassId, deal.semantic.ir.ClassFactoryId> ownerEntries) {
            java.util.Objects.requireNonNull(owners,
                "owners must not be null");
            java.util.Objects.requireNonNull(ownerEntries,
                "ownerEntries must not be null");
            this.jsonPlanOwners = java.util.Collections
                .unmodifiableMap(new java.util.LinkedHashMap<>(owners));
            this.jsonPlanOwnerEntries = java.util.Collections
                .unmodifiableMap(new java.util.LinkedHashMap<>(ownerEntries));
        }

        /**
         * The checked exact declaration of one class-typed receiver
         * expression (the checker's per-expression record), or
         * {@code null} when the expression's class-typed declaration is
         * not recorded (a constructed literal, an array element or a
         * chain whose annotations resolve locally/imported, and the
         * binding annotations of every class-typed variable and field
         * are recorded).
         */
        private Symbol.ClassSymbol exactReceiverDeclaration(
                ExpressionNode receiver) {
            if (receiver == null || checks == null) {
                return null;
            }
            return checks.exactClassDeclarations().get(receiver);
        }

        /**
         * The declaring module's registered construction entry of one
         * canonical class identity, or {@code null} when the project
         * declares no exported class of that identity in the module.
         */
        private deal.semantic.ir.ClassFactoryId providerEntryOf(
                String declaringModulePath, ClassId classId) {
            if (declaringModulePath == null || declaringModulePath.isEmpty()) {
                return null;
            }
            Map<ClassId, deal.semantic.ir.ClassFactoryId> byClass =
                providerConstructionEntries.get(new ModuleId(declaringModulePath));
            return byClass == null ? null : byClass.get(classId);
        }

        /**
         * The identity-keyed field layout of one class: the unit's own
         * declaration, the accumulated imported provider's, or the
         * declaration seeds' (the builtin Error included). Never used for
         * an identity with more than one declaration — the exact
         * declaring provider route owns that selection.
         */
        private deal.semantic.ir.ClassLayout legacyFieldLayout(ClassId classId) {
            deal.semantic.ir.ClassLayout layout = classLayouts.get(classId);
            if (layout != null) {
                return layout;
            }
            SharedFactoryFacts shared = sharedFactories.get(classId);
            if (shared != null) {
                return shared.layout();
            }
            ClassRegistrationSeeds.ClassRegistration seed =
                registrationSeeds.registrationFor(classId);
            return seed == null ? null : seed.layout();
        }

        /**
         * Whether one canonical class identity carries more than one
         * declaration in the project or among the declarations visible to
         * this unit. A harness-synthesized identity can still name two
         * declarations, so such an identity has no single layout and no
         * single provider fact.
         */
        private boolean ambiguousFieldIdentity(ClassId classId) {
            if (projectAmbiguousClassIds.contains(classId)) {
                return true;
            }
            java.util.Set<deal.semantic.ir.ClassFactoryId> entries =
                new java.util.LinkedHashSet<>();
            for (SharedFactoryFacts facts : providerFactoryFacts.values()) {
                if (facts.classId().equals(classId)) {
                    entries.add(facts.interfaceEntry().constructionEntry());
                }
            }
            for (SharedFactoryFacts facts : sharedFactories.values()) {
                if (facts.classId().equals(classId)) {
                    entries.add(facts.interfaceEntry().constructionEntry());
                }
            }
            int count = entries.size();
            if (classLayouts.containsKey(classId)) {
                count++;
            }
            return count > 1;
        }

        /**
         * One field operation's exact declaring-provider resolution: the
         * declaring layout and the recorded provider reference
         * ({@code null} when the identity-keyed route is exact — a single
         * visible declaration, or a declaration-seeded class).
         */
        private record FieldReceiverResolution(
            deal.semantic.ir.ClassLayout layout,
            deal.semantic.ir.ClassFactoryId providerRef) {
        }

        /**
         * The exact declaring provider of one class-typed field
         * operation's receiver: the checker's recorded declaration of the
         * receiver expression names the declaring module, whose own layout
         * (this unit) or registered construction entry's shared facts (an
         * imported provider) then supply the operation's declared field
         * facts. An identity with more than one declaration and no
         * recorded exact declaration fails closed — never a first alias, a
         * unit-local mask or an identity-keyed first match.
         */
        private FieldReceiverResolution resolveFieldReceiver(
                ExpressionNode receiver, ClassId classId, String operation,
                Span span) {
            Symbol.ClassSymbol decl = exactReceiverDeclaration(receiver);
            String declaringModule = decl == null ? null : decl.modulePath();
            boolean imported = declaringModule != null
                && !declaringModule.isEmpty()
                && !declaringModule.equals(module.path());
            if (imported) {
                if (!ambiguousFieldIdentity(classId)) {
                    // One visible declaration of the identity: the
                    // identity-keyed route already selects it exactly.
                    return new FieldReceiverResolution(
                        legacyFieldLayout(classId), null);
                }
                deal.semantic.ir.ClassFactoryId entry =
                    providerEntryOf(declaringModule, classId);
                SharedFactoryFacts facts = entry == null ? null
                    : providerFactoryFacts.get(entry);
                if (facts == null) {
                    throw new ConstructUnlowered(operation + " on " + classId
                        + ": the receiver's checked declaration is declared by module '"
                        + declaringModule + "', but the project carries no registered"
                        + " construction entry and shared facts for it (a required"
                        + " provider fact is missing; the exact declaration is never"
                        + " repaired by a first alias)", span);
                }
                return new FieldReceiverResolution(facts.layout(), entry);
            }
            if (declaringModule != null && declaringModule.equals(module.path())) {
                deal.semantic.ir.ClassLayout layout = classLayouts.get(classId);
                if (layout == null) {
                    throw new ConstructUnlowered(operation + " on " + classId
                        + ": the receiver's checked declaration is this module's, but the"
                        + " unit carries no lowered layout for it (a producer defect)",
                        span);
                }
                // The unit's own declaration is the exact one for a local
                // receiver: no provider reference is recorded (the unit's
                // own classLayouts entry is the only declaration of the
                // identity inside this unit).
                return new FieldReceiverResolution(layout, null);
            }
            if (ambiguousFieldIdentity(classId)) {
                throw new ConstructUnlowered(operation + " on " + classId
                    + ": more than one declaration of the canonical class identity"
                    + " exists (same-directory sibling declarations) and the receiver's"
                    + " checked declaration records no exact declaring provider: the"
                    + " operation fails closed rather than selecting a first alias",
                    span);
            }
            return new FieldReceiverResolution(legacyFieldLayout(classId), null);
        }


/** The closed MODULE_IMPORT kind of a resolved import fact. */
        private static deal.semantic.ir.ModuleImportKind moduleImportKindOf(
                ResolvedImport importFact) {
            return switch (importFact.kind()) {
                case STDLIB -> deal.semantic.ir.ModuleImportKind.STDLIB;
                case HOST -> deal.semantic.ir.ModuleImportKind.HOST;
                default -> deal.semantic.ir.ModuleImportKind.COMPILED;
            };
        }

        /** The import fact bound to the given alias, or {@code null}. */
        private ResolvedImport importByAlias(String alias) {
            for (ResolvedImport importFact : moduleImports) {
                if (importFact.alias().equals(alias)) {
                    return importFact;
                }
            }
            return null;
        }

        /**
         * The closed descriptor-kind rule of the carrier slice: function
         * descriptors check under {@code FUNCTION_SIGNATURE}, every other
         * descriptor under {@code TYPE_DESCRIPTOR}.
         */
        private static FailurePolicyId descriptorKindPolicy(RuntimeDescriptor descriptor) {
            return descriptor instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
        }

        /**
         * The carrier's call-site dispatch: the pinned conversion
         * intrinsics, the cataloged stdlib calls, and direct user calls —
         * every other call shape (indirect callees, host/external calls,
         * async) is E7's and stays rejected. The stdlib branch runs the
         * closed checked-fact recognition predicate
         * ({@code StdlibCallRecognition} over the checker's
         * {@code ModuleSymbol} fact and the {@link
         * StdlibFunctionCatalog}): every recognized cataloged id lowers
         * through the single {@code STDLIB_CALL} arm, and no module/name
         * pair is interpreted as a stdlib algorithm here (D1).
         */
        private ValueId lowerCallSite(CallExpr call, ValueId slot) {
            Optional<StdlibFunctionCatalog.Entry> stdlibEntry =
                StdlibCallRecognition.recognize(call.callee(), currentCheckerScope(),
                    moduleImports);
            if (stdlibEntry.isPresent()) {
                return lowerStdlibCall(call, slot, stdlibEntry.get());
            }
            return lowerUserCall(call, slot);
        }

        /**
         * {@code STDLIB_CALL} — the carrier arm for every cataloged
         * stdlib id (D2): the checked member access's export read
         * precedes the call; the argument operands complete
         * left-to-right before the {@code STDLIB_CALL} START; the
         * payload carries {@code {function, args, effectCapability}}
         * with {@code args} in the same left-to-right order and
         * {@code effectCapability} = {@code STDLIB_SEMANTICS}; the
         * operand descriptors are the catalog entry's declared
         * parameter descriptors in order; one {@code STDLIB_PARAMETER}
         * boundary child per declared parameter in one-based order
         * (declared descriptor, descriptor-kind rule) and the single
         * {@code STDLIB_RETURN} boundary (declared return descriptor,
         * descriptor-kind rule) run by the call op itself after the
         * algorithm result — all children parented to the
         * {@code STDLIB_CALL} op. The op's {@code failurePolicy} is
         * stamped from {@link SemanticIrValidator#stdlibPolicy} — the
         * single closed algorithm→policy table, never a lowerer-local
         * copy.
         */
        private ValueId lowerStdlibCall(CallExpr call, ValueId slot,
                                        StdlibFunctionCatalog.Entry entry) {
            StdlibFunctionId function = entry.function();
            String field = entry.exportName();
            ModuleId stdlibModule = new ModuleId(entry.modulePath());
            // The member access's checked export read (the MEMBER_ACCESS
            // construct's pinned form for a module member): one
            // EXPORT_READ of the stdlib export before the STDLIB_CALL.
            RuntimeDescriptor.Func exportDescriptor =
                (RuntimeDescriptor.Func) ContainerPayloadDescriptors
                    .resultDescriptorOf(checkedType(call.callee()));
            ValueId exportValue = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId exportAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId exportOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin exportOrigin = new SourceOrigin(sourceId,
                toSourceSpan(call.callee().span()), SourceOriginKind.USER, exportAnchor,
                currentParent());
            emit(buildOp(exportOpId, SemanticOpKind.EXPORT_READ,
                new KindPayload.ExportReadPayload(stdlibModule, field,
                    exportDescriptor, exportValue),
                exportValue, exportDescriptor, List.of(), List.of(),
                FailurePolicyId.NO_DEAL_FAILURE, exportOrigin));
            // The export value's execution binding (R-FUNCTION-BINDING):
            // the stdlib module's export host function identity, recorded
            // through the registry child's host-export seam (B5 — the
            // same seam every function-typed host export read registers
            // through). The STDLIB surface is once-only per resolved module
            // (the closed catalog's callable is one value per module/name),
            // so no source-occurrence cell is recorded: the occurrence-owned
            // correlation is a HOST-module fact.
            registry.registerHostOrExternalImport(
                new FunctionAllocationIdentity(exportValue.id()),
                new KindPayload.ExportReadPayload(stdlibModule, field,
                    exportDescriptor, exportValue),
                new FunctionBindingRegistry.FunctionValueImportFacts(
                    stdlibModule, null, field, exportDescriptor));
            // The argument operands complete left-to-right before the
            // STDLIB_CALL START (parent D13 step 1); their descriptors
            // are the catalog entry's declared parameter descriptors in
            // order — the declared signature is the single authority for
            // operand and boundary descriptors alike.
            List<ValueId> args = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                // A stdlib parameter position is a statically typed
                // parameter position like any other: an assignable-but-
                // not-exact function argument takes its arity-extension
                // adapter before the parameter cell validates it.
                args.add(lowerParameterArgument(call, i, false));
            }
            List<RuntimeDescriptor> parameterDescriptors = entry.parameterDescriptors();
            if (args.size() != parameterDescriptors.size()) {
                throw new ConstructUnlowered("stdlib call " + function + " ("
                    + entry.modulePath() + "." + field + ") with " + args.size()
                    + " arguments for " + parameterDescriptors.size()
                    + " declared parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor resultType = entry.returnDescriptor();
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.STDLIB_CALL,
                new KindPayload.StdlibCallPayload(function, args,
                    SemanticCapability.STDLIB_SEMANTICS),
                result, resultType, args, parameterDescriptors,
                SemanticIrValidator.stdlibPolicy(function), origin));
            for (int i = 0; i < args.size(); i++) {
                emitChildBoundary(BoundaryKind.STDLIB_PARAMETER,
                    parameterDescriptors.get(i), args.get(i), call.span(), opId);
            }
            emitChildBoundary(BoundaryKind.STDLIB_RETURN, resultType, result, call.span(),
                opId);
            return result;
        }

        /** Builds (without emitting) one BOUNDARY child parented to the given op. */
        private SemanticOp buildChildBoundary(BoundaryKind kind, RuntimeDescriptor descriptor,
                                              ValueId input, Span span, OpId parent) {
            return buildChildBoundary(kind, descriptor, input, span, null, parent);
        }

        /**
         * Builds one BOUNDARY child whose origin carries the given source
         * id — the declaration-owned cross-module parameter cell's origin
         * is the callee's file (P3), while the op belongs to the caller's
         * unit. A {@code null} source id is the emitting module's own. A
         * function-typed parameter cell whose input is a position adapter
         * records that adapter's emission fact here — the position's own
         * boundary is the adapter's single consumer.
         */
        private SemanticOp buildChildBoundary(BoundaryKind kind, RuntimeDescriptor descriptor,
                                              ValueId input, Span span, String originSourceId,
                                              OpId parent) {
            FailurePolicyId policy = descriptorKindPolicy(descriptor);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            recordPositionAdapterAt(input, opId, kind);
            SourceOrigin origin = new SourceOrigin(
                originSourceId != null ? originSourceId : sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, parent);
            return buildOp(opId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, descriptor, input,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, policy, origin);
        }

        /** Emits one BOUNDARY child parented to the given op. */
        private void emitChildBoundary(BoundaryKind kind, RuntimeDescriptor descriptor,
                                       ValueId input, Span span, OpId parent) {
            emit(buildChildBoundary(kind, descriptor, input, span, parent));
        }

        /** Builds one BOUNDARY child with the pinned policy (the fixed-policy cells). */
        private SemanticOp buildChildBoundaryWithPolicy(BoundaryKind kind,
                RuntimeDescriptor descriptor, FailurePolicyId policy, ValueId input,
                Span span, OpId parent) {
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, parent);
            return buildOp(opId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, descriptor, input,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, policy, origin);
        }

        /** Emits one module-level op outside the block-membership table
         * ({@code EXTERNAL_ENTRY}/{@code CALLBACK_INVOKE} — executed only
         * by their triggering callers/host steps, never by the
         * module-init walk). */
        private void emitUnattached(SemanticOp op) {
            emitTarget().add(op);
        }

        private ValueId lowerUserCall(CallExpr call, ValueId slot) {
            if (call.callee() instanceof IdentifierExpr identifier) {
                BindingSite site = bindingSiteResolver == null
                    ? null : bindingSiteResolver.resolve(identifier.name());
                FunctionContext context = site == null
                    ? null : contextsByBindingId.get(site.binding());
                if (context == null) {
                    if (!e7Calls) {
                        throw new ConstructUnlowered("callee '" + identifier.name()
                            + "' is not a declared function binding of the carrier slice "
                            + "(function-typed parameters/loads and imported functions are "
                            + "E7's registry resolution)", identifier.span());
                    }
                    return lowerUserCallBinding(call, slot, identifier);
                }
                if (captureCarriedInvocation(site.binding(), context)) {
                    // A call of a capture-carrying declared body — a nested
                    // recursive-group member (the group publication wrote the
                    // member's closure carrier into the member cell) or any
                    // other declared-name call whose target owns creation-site
                    // captures: the published closure carrier holds the
                    // captured cells of the target's own creation instance
                    // (the alias/function-value arm's resolution). The static
                    // direct-call arm would rebuild the callee's factory at
                    // the call site and re-resolve the target's captures from
                    // the calling function's frame — exact only for
                    // single-incarnation module-level cells and for targets
                    // that are the caller's own captures. A sibling, self,
                    // enclosing-body, or escaped-instance call of a
                    // capture-carrying declaration therefore goes through the
                    // same value-carried resolution every ordinary
                    // function-value call uses, preserving the declaration's
                    // closure identity and single return cell
                    // ({@link #lowerIndirectCall}'s LoweredBody arm).
                    return lowerUserCallBinding(call, slot, identifier);
                }
                return lowerDirectCall(call, slot, identifier, context);
            }
            if (!e7Calls) {
                throw new ConstructUnlowered("call callee shape "
                    + call.callee().getClass().getSimpleName()
                    + " (the carrier slice lowers direct calls of declared function "
                    + "bindings; indirect/host/external/async calls are E7's)", call.span());
            }
            if (call.callee() instanceof MemberAccessExpr access
                    && access.object() instanceof IdentifierExpr alias
                    && isModuleSymbol(alias.name())) {
                return lowerUserCallImport(call, slot, access, alias);
            }

            ValueId dynamicCallee = lowerExpression(call.callee());
            FunctionExecutionBinding registration = registry.bindings().get(
                new FunctionAllocationIdentity(dynamicCallee.id()));
            if (registration != null
                    && !(registration instanceof FunctionExecutionBinding.LoweredBody)
                    && !(registration
                        instanceof FunctionExecutionBinding.DynamicFunctionValue)) {
                return lowerIndirectCall(call, slot, registration, dynamicCallee,
                    describeDynamicCallee(call.callee()), false);
            }
            return lowerDynamicCall(call, slot, describeDynamicCallee(call.callee()),
                dynamicCallee);
        }

        /**
         * Records the source-call invocation shape of one body invocation
         * through the closed single-shape guard. The generated
         * {@code @jsonable} helper (R3(b)) is the one body whose reserved
         * shape is never replaced: its declaration's reserved
         * {@code EXTERNAL_ENTRY} stays the cross-module invocation record,
         * and a same-module reference — a direct call
         * ({@link #lowerDirectCall}) or a call carried through a
         * function-typed binding ({@link #lowerIndirectCall}) — invokes the
         * declaration's generated closure through the landed
         * {@code LoweredBody} call shape while the body's single return
         * cell keeps the entry's kind. Every other body assigns
         * {@code SOURCE_CALL} through the guard, so a genuine two-shape
         * invocation still fails closed.
         */
        private void assignSourceCallShape(FunctionContext context) {
            if (!context.generatedHelper) {
                context.assignShape(InvocationShape.SOURCE_CALL, context.callSiteOpId);
            }
        }

        /**
         * The module's exported function name whose reserved invocation
         * shape is the declaration's {@code EXTERNAL_ENTRY}, or {@code null}
         * for every body whose same-module references are source
         * invocations. The generated {@code @jsonable} helper is excluded:
         * its reserved entry stays the cross-module invocation record while
         * its same-module references keep the landed {@code LoweredBody}
         * call shape (R3(b)).
         */
        private String moduleEntryExportName(FunctionContext context) {
            if (context == null || context.generatedHelper || !e7Calls
                    || context.shape != InvocationShape.EXTERNAL_ENTRY_SHAPE) {
                return null;
            }
            return moduleEntryExportNames.get(context.functionId);
        }

        private ValueId lowerDirectCall(CallExpr call, ValueId slot, IdentifierExpr identifier,
                                        FunctionContext context) {
            // The generated @jsonable helper's declaring module calls the
            // class's generated closure identity through the landed
            // LoweredBody call shape (R3(b)): the reserved EXTERNAL_ENTRY
            // stays the cross-module invocation record, so the helper
            // keeps its shape (and the body's EXTERNAL_RETURN cell the
            // entry names) while this call is a direct body call.
            boolean entryInvocation = !context.generatedHelper && e7Calls
                && context.shape == InvocationShape.EXTERNAL_ENTRY_SHAPE;
            if (e7Calls && !entryInvocation) {
                assignSourceCallShape(context);
            }
            // The audited callee evaluation: the identifier loads the
            // declared function binding (its result identity is the
            // closure identity — R-FUNCTION-BINDING) before the CALL.
            lowerExpression(identifier);
            return emitDeclaredBodyInvocation(call, slot, context, identifier.name(),
                entryInvocation);
        }

        /**
         * Emits one declared-body invocation over an already-evaluated
         * callee: the landed {@code CALL(DIRECT)} body call, or — when the
         * body's reserved invocation shape is its {@code EXTERNAL_ENTRY} —
         * the same-module entry realization: {@code CALL(EXTERNAL)} over the
         * declaration's {@code SHARED_BODY} record, naming the reserved
         * entry op and the declaration's single return cell. The two arms
         * differ only in the boundary kinds, the callee record, and the
         * recorded return cell; the declaration's own parameter spans, the
         * reserved call-site identity, and the result materialization are
         * shared. A same-module reference — the direct declared-name call
         * ({@link #lowerDirectCall}) or a value-carried reference through
         * the declaration's published closure carrier
         * ({@link #lowerIndirectCall}) — realizes the entry, never re-shapes
         * it into a source call.
         */
        private ValueId emitDeclaredBodyInvocation(CallExpr call, ValueId slot,
                                                   FunctionContext context,
                                                   String calleeName,
                                                   boolean entryInvocation) {
            String entryExportName = entryInvocation
                ? moduleEntryExportName(context) : null;
            if (entryInvocation && entryExportName == null) {
                throw new ConstructUnlowered("the exported entry invocation of '"
                    + calleeName + "' has no recorded module export name"
                    + " (producer defect)", call.span());
            }
            // The first call site reuses the function's reserved
            // canonical call-site identity (the RETURN ops' enclosing
            // invocation); later call sites allocate fresh op identities
            // while naming the same single return boundary — the closed
            // validator admits one return boundary per callee (D13's
            // single-return-boundary shape).
            OpId callOpId = context.callSiteUsed
                ? ids.nextOpId(module, nextOrdinal++, 0)
                : context.callSiteOpId;
            context.callSiteUsed = true;
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, true));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != context.signature.paramTypes().size()) {
                throw new ConstructUnlowered("call of '" + calleeName
                    + "' with " + args.size() + " arguments for "
                    + context.signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor resultType = context.signature.returnType();
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            // Parameter boundary children (built first so the CALL payload
            // names their op ids, then emitted as its children).
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                // The declared callee's own parameter annotation is the
                // parameter cell's origin (P3): the declaration-owned
                // convention, in the callee's file, never the call site.
                // The callee context carries the declared annotation for a
                // same-unit callee (module-level or local); the project-wide
                // declared-parameter index supplies the cross-module
                // declaration's span.
                SemanticOp boundary = buildChildBoundary(
                    entryInvocation ? BoundaryKind.EXTERNAL_PARAMETER
                        : BoundaryKind.FUNCTION_PARAMETER,
                    context.signature.paramTypes().get(i), args.get(i),
                    context.parameterSpan(i),
                    callOpId);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
            emit(buildOp(callOpId, SemanticOpKind.CALL,
                entryInvocation
                    ? new KindPayload.CallPayload(CallMode.EXTERNAL,
                        new KindPayload.CallCallee.Static(
                            new FunctionExecutionBinding.ExternalFunction(module,
                                entryExportName, context.signature,
                                ExternalExecutionOwner.SHARED_BODY)),
                        context.signature, parameterBoundaryIds, null, null, null,
                        context.shapeOpId)
                    : new KindPayload.CallPayload(CallMode.DIRECT,
                        new KindPayload.CallCallee.Static(
                            new FunctionExecutionBinding.LoweredBody(
                                context.functionId, context.bodyBlock)),
                        context.signature, parameterBoundaryIds,
                        context.returnBoundaryOpId, null, context.bodyBlock, null),
                result, resultType, args, argTypes,
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            // The producer rule's call arm: a declared-body invocation
            // result with a function-typed result registers exactly one
            // dynamic materialization keyed by the invocation's result
            // identity.
            registerDynamicMaterialization(result, callOpId, resultType);
            return result;
        }

        /**
         * {@code CALL(INDIRECT)} — the E7 arm for a function-typed
         * binding callee whose producing allocation identity resolves to
         * exactly one registered {@code FunctionExecutionBinding}: the
         * load publishes the tracked identity (loads preserve allocation
         * identity — R-FUNCTION-BINDING) and the call records the
         * resolved binding inline ({@code CallCallee.Static} — the
         * statically-resolved slice never defers binding resolution).
         * Parameter and return boundary cells follow the closed
         * boundary-assignment table per the binding shape: ×N
         * {@code FUNCTION_PARAMETER} target-signature boundaries (the
         * complete set) for bodies and adapters,
         * {@code DEAL_TO_HOST}+{@code HOST_PARAMETER} for host functions,
         * {@code EXTERNAL_PARAMETER} for external functions; the single
         * return boundary per the shape — the source body's
         * {@code FUNCTION_RETURN} run by its {@code RETURN},
         * {@code HOST_TO_DEAL}+{@code HOST_SYNC_RETURN} run by the call
         * op for a host function, {@code EXTERNAL_RETURN} per the
         * execution owner for an external function.
         */
        private ValueId lowerUserCallBinding(CallExpr call, ValueId slot,
                                             IdentifierExpr identifier) {
            FrameResolution resolution = resolveFrame(identifier.name());
            // A mutable function-typed binding (assigned anywhere in its
            // declaring body) carries no statically resolved callee: the
            // call dispatches on the value the load reads, exactly like
            // every other runtime-resolved callee (the spec's
            // capture-by-binding rule).
            ValueId identity = resolution == null
                    || assignedFunctionBinding(resolution.entry())
                ? null : functionIdentity.get(resolution.entry().incarnation());
            if (identity == null) {
                // The dynamic arm (the callee value has no statically
                // resolvable execution binding).
                return lowerDynamicCall(call, slot, identifier.name(),
                    lowerDynamicCalleeValue(identifier, resolution));
            }
            ValueId calleeValue = lowerExpression(identifier);
            FunctionExecutionBinding binding =
                registry.bindings().get(new FunctionAllocationIdentity(calleeValue.id()));
            if (binding == null) {
                throw new ConstructUnlowered("callee '" + identifier.name()
                    + "' identity " + calleeValue
                    + " has no registered FunctionExecutionBinding (producer defect)",
                        identifier.span());
            }
            if (binding instanceof FunctionExecutionBinding.DynamicFunctionValue) {

                return lowerDynamicCall(call, slot, identifier.name(), calleeValue);
            }
            return lowerIndirectCall(call, slot, binding, calleeValue, identifier.name(),
                valueCarriedClosure(binding));
        }

        private boolean valueCarriedClosure(FunctionExecutionBinding binding) {
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)) {
                return false;
            }
            LoweredFunction function = functions.get(body.functionId());
            if (function == null) {
                // A pre-registered group member whose own body walk is
                // still open: its capture list is not final yet, and the
                // value the binding holds is the member's published
                // closure, so the invocation is value-carried — the
                // creation-site captures travel with the carrier, never
                // a call-site re-resolution of an unresolved incarnation.
                return bodiesPendingCaptures.contains(body.functionId());
            }
            return !function.captures().isEmpty();
        }

        /**
         * Whether one declared-name invocation must be carried by the
         * declaration's published closure value instead of reconstructing
         * the callee's factory at the call site: a nested recursive-group
         * member (its carrier names its own group instance's cells) or any
         * declared body that owns creation-site captures, including a body
         * whose capture list is not final yet. A capture-free body rebuilds
         * identically, and every value-carried invocation keeps the
         * declaration's own closure identity, reserved call-site identity,
         * and single return cell ({@link #lowerIndirectCall}'s
         * {@code LoweredBody} arm) — the same classification the
         * binding-resolved arm applies at {@link #valueCarriedClosure}.
         */
        private boolean captureCarriedInvocation(BindingId binding,
                                                 FunctionContext context) {
            if (!context.generatedHelper && e7Calls
                    && context.shape == InvocationShape.EXTERNAL_ENTRY_SHAPE) {
                // The exported entry's reserved shape owns the invocation
                // record: a same-module reference realizes the entry's own
                // invocation over the shared body (the entry op's module-level
                // factory reconstruction, exact for a module-level
                // declaration's single-incarnation captures), so the reserved
                // shape is never re-shaped into a source call.
                return false;
            }
            if (nestedGroupMemberBindings.contains(binding)) {
                return true;
            }
            if (context.generatedHelper) {
                // The generated @jsonable helper carries no creation-site
                // captures by construction, so a same-module reference's
                // factory reconstruction is the same closure at every site.
                return false;
            }
            if (bodiesPendingCaptures.contains(context.functionId)) {
                return true;
            }
            LoweredFunction function = functions.get(context.functionId);
            // The pending-capture fact above covers every module-level group
            // member before any group body walk and every member of a group
            // whose walk is open; a finished capture-carrying record is the
            // carrier case the value-carried invocation exists for, and a
            // capture-free record rebuilds identically at every site.
            return function != null && !function.captures().isEmpty();
        }

        /**
         * The callee-value load of a dynamically resolved call: the
         * identifier's binding cell is read by an ordinary
         * {@code BINDING_LOAD} (the carrier read) whose result value is
         * the runtime carrier's own identity — the resolved execution
         * class is read from that carrier at execution, never from a
         * statically tracked allocation. A callee resolving outside the
         * walk's frame environment (a for-of or catch binding) loads
         * through the same identifier arm its own frame serves.
         */
        private ValueId lowerDynamicCalleeValue(IdentifierExpr identifier,
                                                FrameResolution resolution) {
            if (resolution == null) {
                return lowerExpression(identifier);
            }
            maybeRegisterCapture(identifier.name(), resolution);
            return emitResolvedLoad(identifier, checkedType(identifier),
                resolution.entry(), null);
        }

        private ValueId lowerDynamicCall(CallExpr call, ValueId slot, String calleeName,
                                         ValueId calleeValue) {
            RuntimeDescriptor.Func signature = dynamicCalleeSignature(call.callee(),
                "call of '" + calleeName + "'");
            if (signature.isAsync()) {
                throw new ConstructUnlowered("call of async callee '" + calleeName
                    + "' outside await (the checker's E3014 pins the shape; the await "
                    + "arm owns async starts)", call.span());
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, false));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != signature.paramTypes().size()) {
                throw new ConstructUnlowered("call of '" + calleeName + "' with "
                    + args.size() + " arguments for " + signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor resultType = signature.returnType();
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());

            FunctionContext calleeBody = sameWalkCalleeBody(calleeValue);
            OpId callOpId;
            OpId dealBodyBoundaryOpId;
            if (calleeBody != null) {
                calleeBody.assignShape(InvocationShape.SOURCE_CALL,
                    calleeBody.callSiteOpId);
                callOpId = calleeBody.callSiteUsed
                    ? ids.nextOpId(module, nextOrdinal++, 0) : calleeBody.callSiteOpId;
                calleeBody.callSiteUsed = true;
                dealBodyBoundaryOpId = calleeBody.returnBoundaryOpId;
            } else {
                callOpId = ids.nextOpId(module, nextOrdinal++, 0);
                // The call-owned cell carries the unattached record RETURN
                // the closed validator requires.
                dealBodyBoundaryOpId = emitCallOwnedReturnBoundary(signature, result,
                    call.span(), callOpId);
            }
            OpId hostBoundaryOpId = emitHostReturnBoundary(signature, result, call.span(),
                callOpId).opId();
            OpId externalBoundaryOpId = emitExternalReturnBoundary(signature, result,
                call.span(), callOpId);
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                SemanticOp boundary = buildChildBoundary(BoundaryKind.FUNCTION_PARAMETER,
                    signature.paramTypes().get(i), args.get(i), call.span(), callOpId);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
            emit(buildOp(callOpId, SemanticOpKind.CALL,
                new KindPayload.CallPayload(CallMode.INDIRECT,
                    new KindPayload.CallCallee.Dynamic(calleeValue), signature,
                    parameterBoundaryIds, null,
                    new KindPayload.DynamicReturnBoundary(dealBodyBoundaryOpId,
                        hostBoundaryOpId, externalBoundaryOpId),
                    null, null),
                result, resultType, args, argTypes, FailurePolicyId.NO_DEAL_FAILURE,
                origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            // The producer rule's call arm: a dynamically resolved call
            // result with a function-typed result registers exactly one
            // dynamic materialization keyed by the call's result identity.
            registerDynamicMaterialization(result, callOpId, resultType);
            return result;
        }

        private FunctionContext sameWalkCalleeBody(ValueId calleeValue) {
            FunctionExecutionBinding binding = registry.bindings().get(
                new FunctionAllocationIdentity(calleeValue.id()));
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)) {
                return null;
            }
            FunctionContext context = contextsByFunctionId.get(body.functionId());
            if (context == null || !context.returnCellReturnParented) {
                return null;
            }
            return context;
        }

        /**
         * The K12 form (b) call-owned DEAL-body return cell: one
         * {@code FUNCTION_RETURN} boundary on the declared return
         * descriptor parented to a {@code RETURN} op whose payload names
         * the dynamic invocation as its {@code enclosingInvocationOpId}
         * and records that boundary as its {@code returnBoundaryOpId}.
         * The invocation op executes the cell (the resolved class's own
         * return admits the value inside the callee first); the record is
         * not an op of the enclosing function's block flow and is never
         * executed by a block walk. The record's function position is a
         * reserved identity: the callee's body is runtime-resolved, so no
         * statically named body exists for the record.
         */
        private OpId emitCallOwnedReturnBoundary(RuntimeDescriptor.Func signature,
                                                  ValueId result, Span span, OpId callOpId) {
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId returnOpId = ids.nextOpId(module, nextOrdinal++, 0);
            OpId boundaryOpId = ids.nextOpId(module, nextOrdinal++, 0);
            AnchorId boundaryAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            FunctionId reservedRecordFunction = ids.nextFunctionId(module, nextOrdinal++, 0);
            SourceOrigin returnOrigin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, null);
            SourceOrigin boundaryOrigin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, boundaryAnchor, returnOpId);
            emitUnattached(buildOp(returnOpId, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(result, reservedRecordFunction, callOpId,
                    boundaryOpId),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, returnOrigin));
            emit(buildOp(boundaryOpId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.FUNCTION_RETURN,
                    signature.returnType(), result,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, descriptorKindPolicy(signature.returnType()), boundaryOrigin));
            return boundaryOpId;
        }

        /** The checked function descriptor of one callee expression. */
        private RuntimeDescriptor.Func dynamicCalleeSignature(ExpressionNode callee,
                                                              String site) {
            Type calleeType = checkedType(callee);
            if (!(calleeType instanceof Type.Func funcType)
                    || !(ContainerPayloadDescriptors.resultDescriptorOf(funcType)
                        instanceof RuntimeDescriptor.Func signature)) {
                throw new ConstructUnlowered(site + " without a function-typed checked "
                    + "descriptor (the checker admits function-typed callees only)", callee.span());
            }
            return signature;
        }

        /** The callee description of one dynamic site's diagnostics. */
        private static String describeDynamicCallee(ExpressionNode callee) {
            return switch (callee) {
                case IdentifierExpr identifier -> identifier.name();
                case MemberAccessExpr access -> access.field();
                default -> callee.getClass().getSimpleName();
            };
        }

        /** The declared parameter count of the resolved binding shape. */
        private int bindingParamCount(FunctionExecutionBinding binding) {
            return switch (binding) {
                case FunctionExecutionBinding.AdapterBinding adapter ->
                    adapter.targetSignature().paramTypes().size();
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext context = contextsByFunctionId.get(body.functionId());
                    if (context == null) {
                        throw new ConstructUnlowered("the LoweredBody binding's function "
                            + body.functionId().id() + " has no lowering context "
                            + "(producer defect)");
                    }
                    yield context.signature.paramTypes().size();
                }
                case FunctionExecutionBinding.HostFunction host ->
                    host.descriptor().paramTypes().size();
                case FunctionExecutionBinding.HostFunctionValue hostValue ->
                    hostValue.descriptor().paramTypes().size();
                case FunctionExecutionBinding.ExternalFunction external ->
                    external.descriptor().paramTypes().size();

                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "a statically classified call "
                        + "arity");
            };
        }

        /**
         * The pinned host-call parameter cells: DEAL_TO_HOST + HOST_PARAMETER.
         * A function-typed position adapter built for its argument records its
         * emission fact here: this cell is the adapted position's own
         * boundary (the invocation-projection agreement the bindings validator
         * admits), exactly like a {@code FUNCTION_PARAMETER} cell, so the
         * shape-map fact surface carries one record per emitted
         * {@code FUNCTION_ADAPT} op instead of leaving the position's pending
         * entry unclaimed.
         */
        private void hostParameterBoundaries(RuntimeDescriptor.Func descriptor,
                List<ValueId> args, CallExpr call, OpId callOpId,
                List<SemanticOp> parameterBoundaryOps, List<OpId> parameterBoundaryIds) {
            for (int i = 0; i < args.size(); i++) {
                SemanticOp boundary = buildChildBoundaryWithPolicy(
                    BoundaryKind.DEAL_TO_HOST, descriptor.paramTypes().get(i),
                    FailurePolicyId.HOST_PARAMETER, args.get(i), call.span(), callOpId);
                recordPositionAdapterAt(args.get(i), boundary.opId(),
                    BoundaryKind.DEAL_TO_HOST);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
        }

        /**
         * The pinned host sync-return cell run by the call op. Returns the
         * emitted boundary op: the producing crossing of a function-typed
         * host return registers its {@code HostFunctionValue} keyed by this
         * op and its boundary payload (M2 item 1).
         */
        private SemanticOp emitHostReturnBoundary(RuntimeDescriptor.Func descriptor,
                                                  ValueId result, Span span,
                                                  OpId callOpId) {
            SemanticOp boundary = buildChildBoundaryWithPolicy(BoundaryKind.HOST_TO_DEAL,
                descriptor.returnType(), FailurePolicyId.HOST_SYNC_RETURN, result, span,
                callOpId);
            emit(boundary);
            return boundary;
        }

        /** The retained-ABI external return cell run by the call op. */
        private OpId emitExternalReturnBoundary(RuntimeDescriptor.Func descriptor,
                                                ValueId result, Span span, OpId callOpId) {
            SemanticOp boundary = buildChildBoundary(BoundaryKind.EXTERNAL_RETURN,
                descriptor.returnType(), result, span, callOpId);
            emit(boundary);
            return boundary.opId();
        }

        /** The callee unit's recorded {@code EXTERNAL_ENTRY} op id of the export. */
        private OpId externalEntryRefOf(FunctionExecutionBinding.ExternalFunction external) {
            Map<String, OpId> entries = calleeExternalEntries.get(external.moduleId());
            OpId entry = entries == null ? null : entries.get(external.exportName());
            if (entry == null) {
                throw new ConstructUnlowered("the SHARED_BODY external call of '"
                    + external.moduleId() + "'." + external.exportName()
                    + " has no recorded EXTERNAL_ENTRY in the callee unit (lower the "
                    + "callee module first and pass its recorded entries)");
            }
            return entry;
        }

        private static ConstructUnlowered dynamicCarrierDefect(
                FunctionExecutionBinding.DynamicFunctionValue dynamic, String site) {
            return new ConstructUnlowered("the dynamic function value produced by op "
                + dynamic.materializingOpId() + " resolved by " + site + " has no "
                + "statically classified execution in this slice (its execution class "
                + "resolves from the runtime value's producing registration — the "
                + "function-typed-value child's; producer defect)");
        }

        /** The adapter's statically fixed source identity, or null (thunk sources). */
        private ValueId staticSourceIdentity(
                FunctionExecutionBinding.AdapterBinding adapter) {
            return switch (adapter.sourceRef()) {
                case AdaptSourceRef.SharedCell cell ->
                    identityOfIncarnation(cell.binding(), cell.generation());
                case AdaptSourceRef.Value value -> value.value();
                case AdaptSourceRef.Thunk ignored -> null;
            };
        }

        /** The adapter's statically fixed source binding, or null (thunk/call-result sources). */
        private FunctionExecutionBinding staticSourceBinding(
                FunctionExecutionBinding.AdapterBinding adapter) {
            ValueId identity = staticSourceIdentity(adapter);
            if (identity == null) {
                return null;
            }
            return registry.bindings().get(new FunctionAllocationIdentity(identity.id()));
        }

        /** The tracked function identity of one binding incarnation, or null. */
        private ValueId identityOfIncarnation(BindingId binding, long generation) {
            BindingCell cell = cellsById.get(binding);
            if (cell == null) {
                return null;
            }
            for (BindingCoreIncarnation incarnation : cell.incarnations) {
                if (incarnation.generation() == generation) {
                    return functionIdentity.get(incarnation);
                }
            }
            return null;
        }

        /**
         * {@code CALL(INDIRECT)} — the shared indirect-call emission over
         * the resolved binding (identifier and import-member sources).
         */
        private ValueId lowerIndirectCall(CallExpr call, ValueId slot,
                                          FunctionExecutionBinding binding,
                                          ValueId calleeValue, String calleeName,
                                          boolean valueCarried) {
            Type calleeCheckedType = checkedType(call.callee());
            if (!(calleeCheckedType instanceof Type.Func funcType)
                    || !(ContainerPayloadDescriptors.resultDescriptorOf(funcType)
                        instanceof RuntimeDescriptor.Func signature)) {
                throw new ConstructUnlowered("call of '" + calleeName
                    + "' without a function-typed checked descriptor (producer defect)",
                        call.span());
            }
            if (signature.isAsync()) {
                throw new ConstructUnlowered("call of async callee '" + calleeName
                    + "' outside await (the checker's E3014 pins the shape; the await "
                    + "arm owns async starts)", call.span());
            }
            if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                FunctionContext entryContext =
                    contextsByFunctionId.get(body.functionId());
                if (entryContext != null
                        && moduleEntryExportName(entryContext) != null) {
                    // The exported declaration's reserved entry shape owns
                    // the invocation record: a same-module value-carried
                    // reference — an alias or any other function-typed
                    // binding holding the declaration's published closure
                    // — realizes the declaration's own entry over the
                    // shared body, exactly like the direct declared-name
                    // call ({@link #lowerDirectCall}). The body identity
                    // and its single return cell stay the reservation's;
                    // the reserved entry is never re-shaped into a source
                    // call (the closed two-shape guard stays in force).
                    // The carrier read already ran at the call site.
                    return emitDeclaredBodyInvocation(call, slot, entryContext,
                        calleeName, true);
                }
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            boolean declaredBodyCallee =
                binding instanceof FunctionExecutionBinding.LoweredBody;
            for (int i = 0; i < call.args().size(); i++) {
                // A resolved declared body keeps the declared-callee
                // contextual-argument handling: the argument walk defers a
                // contextual table member read's kind check to the callee's
                // own parameter cell (whose origin is the declared
                // parameter annotation), exactly like {@link
                // #lowerDirectCall}. Every other binding kind resolves its
                // own parameter boundaries at the call site and keeps the
                // read's own check.
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, declaredBodyCallee));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            int expectedParams = bindingParamCount(binding);
            if (args.size() != expectedParams) {
                throw new ConstructUnlowered("call of '" + calleeName + "' with "
                    + args.size() + " arguments for " + expectedParams
                    + " parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor resultType = signature.returnType();
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            // The first call site of a body-source resolution reuses the
            // source function's reserved canonical call-site identity
            // (its RETURN ops name it); later sites allocate fresh op
            // identities naming the same single return boundary.
            OpId callOpId;
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext sourceContext =
                        contextsByFunctionId.get(body.functionId());
                    if (sourceContext == null) {
                        throw new ConstructUnlowered("the LoweredBody binding of '"
                            + calleeName + "' has no lowering context (producer defect)");
                    }
                    assignSourceCallShape(sourceContext);
                    callOpId = sourceContext.callSiteUsed
                        ? ids.nextOpId(module, nextOrdinal++, 0)
                        : sourceContext.callSiteOpId;
                    sourceContext.callSiteUsed = true;
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    FunctionExecutionBinding source = staticSourceBinding(adapter);
                    if (source instanceof FunctionExecutionBinding.LoweredBody body) {
                        FunctionContext sourceContext =
                            contextsByFunctionId.get(body.functionId());
                        if (sourceContext == null) {
                            throw new ConstructUnlowered("the adapter's body-source "
                                + "binding has no lowering context (producer defect)");
                        }
                        // The adapter's source invocation is not a second
                        // source-call shape: the exported declaration's
                        // reserved context is already the module's entry
                        // ({@link #moduleEntryExportName}), and that entry
                        // owns the body's single return cell. The D15
                        // protocol runs the published closure under the
                        // reserved identity (the target-parameter checks and
                        // the leading-argument projection are the adapter's
                        // own), so the reserved shape is never replaced.
                        if (moduleEntryExportName(sourceContext) == null) {
                            assignSourceCallShape(sourceContext);
                        }
                        callOpId = sourceContext.callSiteUsed
                            ? ids.nextOpId(module, nextOrdinal++, 0)
                            : sourceContext.callSiteOpId;
                        sourceContext.callSiteUsed = true;
                    } else {
                        callOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    }
                }
                default -> callOpId = ids.nextOpId(module, nextOrdinal++, 0);
            }
            // Parameter boundary cells per the closed table.
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            switch (binding) {
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    for (int i = 0; i < args.size(); i++) {
                        SemanticOp boundary = buildChildBoundary(
                            BoundaryKind.FUNCTION_PARAMETER,
                            adapter.targetSignature().paramTypes().get(i), args.get(i),
                            call.span(), callOpId);
                        parameterBoundaryOps.add(boundary);
                        parameterBoundaryIds.add(boundary.opId());
                    }
                }
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext bodyContext = contextsByFunctionId.get(
                        body.functionId());
                    for (int i = 0; i < args.size(); i++) {
                        SemanticOp boundary = buildChildBoundary(
                            BoundaryKind.FUNCTION_PARAMETER,
                            signature.paramTypes().get(i), args.get(i),
                            bodyContext == null ? call.span()
                                : bodyContext.parameterSpan(i),
                            callOpId);
                        parameterBoundaryOps.add(boundary);
                        parameterBoundaryIds.add(boundary.opId());
                    }
                }
                case FunctionExecutionBinding.HostFunction host ->
                    hostParameterBoundaries(host.descriptor(), args, call, callOpId,
                        parameterBoundaryOps, parameterBoundaryIds);
                case FunctionExecutionBinding.HostFunctionValue hostValue ->
                    hostParameterBoundaries(hostValue.descriptor(), args, call, callOpId,
                        parameterBoundaryOps, parameterBoundaryIds);
                case FunctionExecutionBinding.ExternalFunction external -> {
                    for (int i = 0; i < args.size(); i++) {
                        SemanticOp boundary = buildChildBoundary(
                            BoundaryKind.EXTERNAL_PARAMETER,
                            external.descriptor().paramTypes().get(i), args.get(i),
                            call.span(), callOpId);
                        parameterBoundaryOps.add(boundary);
                        parameterBoundaryIds.add(boundary.opId());
                    }
                }

                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the call of '" + calleeName + "'");
            }
            // The single return boundary per the closed table.
            OpId returnBoundaryOpId = null;
            OpId externalEntryRef = null;
            BlockId bodyBlock = null;
            // The runtime-resolved adapter source's closed return-cell set
            // (a REEVALUATE_THUNK source re-executes the source expression
            // per invocation, so the source's execution class is selected
            // at execution — never here).
            KindPayload.DynamicReturnBoundary dynamicReturnCells = null;
            // The producing host crossing of a function-typed return (M2 item
            // 1): its boundary op and owning host module register the result's
            // HostFunctionValue instead of the dynamic record.
            SemanticOp hostCrossing = null;
            ModuleId hostCrossingModule = null;
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext context = contextsByFunctionId.get(body.functionId());
                    if (context == null) {
                        throw new ConstructUnlowered("the LoweredBody binding of '"
                            + calleeName + "' has no lowering context (producer defect)");
                    }
                    assignSourceCallShape(context);
                    returnBoundaryOpId = context.returnBoundaryOpId;
                    bodyBlock = context.bodyBlock;
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    FunctionExecutionBinding source = staticSourceBinding(adapter);
                    switch (source) {
                        case null -> {
                            if (staticSourceIdentity(adapter) == null) {
                                // A call-result ({@code REEVALUATE_THUNK})
                                // source: the thunk re-executes the source
                                // expression on every invocation, so the
                                // source value's execution class is selected
                                // at execution. The closed dynamic
                                // return-cell set records exactly one
                                // caller-side cell per runtime resolution
                                // class (the callee runs its own cell, the
                                // host source its HOST_TO_DEAL cell, the
                                // retained-ABI external source its
                                // EXTERNAL_RETURN cell), and the invocation
                                // is the closed DYNAMIC callee arm over the
                                // adapter's published identity.
                                dynamicReturnCells =
                                    new KindPayload.DynamicReturnBoundary(
                                        emitCallOwnedReturnBoundary(signature, result,
                                            call.span(), callOpId),
                                        emitHostReturnBoundary(signature, result,
                                            call.span(), callOpId).opId(),
                                        emitExternalReturnBoundary(signature, result,
                                            call.span(), callOpId));
                            } else {
                                // A retained declaration-site operand (the
                                // {@code VALUE} capture mode — an awaited
                                // suspension's delivered function value):
                                // the operand is not a registered function
                                // identity, so the invocation is the recorded
                                // adapter call over the carrier (the emitters'
                                // D15 adapter protocol runs the retained
                                // source's own wrapper, whose single return
                                // check is the source signature's), and the
                                // recorded cell is the runtime host source's
                                // HOST_TO_DEAL family cell — the closed table
                                // admits every per-source return cell for an
                                // adapter. A DEAL-body source runs its own
                                // body's single return cell inside the body
                                // and no caller-side cell.
                                hostCrossing = emitHostReturnBoundary(
                                    adapter.sourceSignature(), result, call.span(),
                                    callOpId);
                                returnBoundaryOpId = hostCrossing.opId();
                            }
                        }
                        case FunctionExecutionBinding.LoweredBody body -> {
                            FunctionContext context =
                                contextsByFunctionId.get(body.functionId());
                            if (context == null) {
                                throw new ConstructUnlowered("the adapter's body-source "
                                    + "binding has no lowering context (producer defect)");
                            }
                            // The entry-shaped source keeps its reserved
                            // shape; its single return cell (the
                            // EXTERNAL_RETURN the entry names) stays the
                            // adapter invocation's recorded return boundary
                            // — the closed adapter table admits it.
                            if (moduleEntryExportName(context) == null) {
                                assignSourceCallShape(context);
                            }
                            returnBoundaryOpId = context.returnBoundaryOpId;
                        }
                        case FunctionExecutionBinding.HostFunction host -> {
                            hostCrossing = emitHostReturnBoundary(host.descriptor(), result,
                                call.span(), callOpId);
                            returnBoundaryOpId = hostCrossing.opId();
                            hostCrossingModule = host.hostModuleId();
                        }
                        case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                            hostCrossing = emitHostReturnBoundary(hostValue.descriptor(),
                                result, call.span(), callOpId);
                            returnBoundaryOpId = hostCrossing.opId();
                            hostCrossingModule = hostValue.hostModuleId();
                        }
                        case FunctionExecutionBinding.ExternalFunction external -> {
                            if (external.executionOwner()
                                    == ExternalExecutionOwner.SHARED_BODY) {
                                externalEntryRef = externalEntryRefOf(external);
                            } else {
                                returnBoundaryOpId = emitExternalReturnBoundary(
                                    external.descriptor(), result, call.span(), callOpId);
                            }
                        }

                        case FunctionExecutionBinding.DynamicFunctionValue ignored -> {
                            // The adapter's source class is runtime-resolved
                            // (the function-typed-value child's producing
                            // registration: a call/read result whose class is
                            // not statically tracked). The source value's own
                            // registration at execution selects the class
                            // path — the oracle's D15 source resolution and
                            // the emitters' adapter protocol both resolve it
                            // from the carrier — and the closed table admits
                            // every per-source return cell for an adapter, so
                            // the recorded cell is the runtime host source's
                            // HOST_TO_DEAL family cell; a DEAL-body source
                            // runs its own body's single return cell inside
                            // the body and no caller-side cell, exactly the
                            // static body-source shape.
                            hostCrossing = emitHostReturnBoundary(
                                adapter.sourceSignature(), result, call.span(), callOpId);
                            returnBoundaryOpId = hostCrossing.opId();
                        }
                        case FunctionExecutionBinding.AdapterBinding ignored -> {
                            // An adapter-valued source: runtime provenance as
                            // an adapter is not part of the type, so the
                            // outer adapter resolves to its own source's
                            // execution class at execution (the closed D15
                            // protocol descends the chain). The recorded cell
                            // is the host source's family cell, exactly the
                            // runtime-resolved source arm; a DEAL-body source
                            // runs its own single return cell inside the
                            // body and adds no caller-side cell.
                            hostCrossing = emitHostReturnBoundary(
                                adapter.sourceSignature(), result, call.span(), callOpId);
                            returnBoundaryOpId = hostCrossing.opId();
                        }
                    }
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    hostCrossing = emitHostReturnBoundary(host.descriptor(), result,
                        call.span(), callOpId);
                    returnBoundaryOpId = hostCrossing.opId();
                    hostCrossingModule = host.hostModuleId();
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    hostCrossing = emitHostReturnBoundary(hostValue.descriptor(),
                        result, call.span(), callOpId);
                    returnBoundaryOpId = hostCrossing.opId();
                    hostCrossingModule = hostValue.hostModuleId();
                }
                case FunctionExecutionBinding.ExternalFunction external -> {
                    if (external.executionOwner() == ExternalExecutionOwner.SHARED_BODY) {
                        externalEntryRef = externalEntryRefOf(external);
                    } else {
                        returnBoundaryOpId = emitExternalReturnBoundary(
                            external.descriptor(), result, call.span(), callOpId);
                    }
                }

                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the call of '" + calleeName + "'");
            }
            emit(buildOp(callOpId, SemanticOpKind.CALL,
                new KindPayload.CallPayload(CallMode.INDIRECT,
                    dynamicReturnCells != null
                        ? new KindPayload.CallCallee.Dynamic(calleeValue)
                        : (valueCarried
                            ? new KindPayload.CallCallee.Indirect(calleeValue)
                            : new KindPayload.CallCallee.Static(binding)),
                    signature, parameterBoundaryIds,
                    dynamicReturnCells != null ? null : returnBoundaryOpId,
                    dynamicReturnCells, bodyBlock,
                    externalEntryRef),
                result, resultType, args, argTypes,
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            // The producer rule's call arm: a host crossing's function-typed
            // return is the host-materialized value class and registers
            // exactly one HostFunctionValue at the producing crossing (M2 item
            // 1); every other call result whose class is not statically known
            // registers the closed dynamic record keyed by the call's result
            // identity (M2 item 2).
            registerCallResultMaterialization(result, callOpId, hostCrossing,
                hostCrossingModule, resultType);
            return result;
        }

        /**
         * One imported export materialization (the read and, for a
         * function descriptor, its registration): the produced read value,
         * the read's descriptor, and the registered binding
         * ({@code null} for a non-function descriptor — a non-function
         * read registers nothing).
         */
        private record ImportMaterialization(FunctionExecutionBinding binding,
                                             RuntimeDescriptor descriptor,
                                             ValueId value) {
        }

        /**
         * The one import-member read production (R1): the callee arms
         * ({@code alias.export(args)} on a host, compiled, or cataloged
         * stdlib import) and the value-position module-symbol arm of
         * {@link #lowerMemberAccess} both call this method, so one read is
         * produced per source occurrence and no later pass re-produces it.
         *
         * <p>The read is exactly one {@code EXPORT_READ} of the resolved
         * export — payload {@code {resolvedModule, exportName, descriptor}}
         * — with a {@code USER} origin at the access span and
         * {@code parentOpId} = the position's current parent. It publishes
         * the position's pre-allocated slot when the position threads one
         * ({@code slot != null ? slot : <fresh value>}), and the produced
         * read value is returned together with the descriptor and the
         * binding. For a function descriptor the read registers exactly
         * one {@code FunctionExecutionBinding} through the closed registry
         * seam, from the read's {@code FunctionValueImportFacts}: {@code
         * HostFunction(resolvedModule, exportName, descriptor, importCell)}
         * for a HOST-kind import, where {@code importCell} is the owning
         * source import occurrence's alias cell (the read's
         * {@code MODULE_IMPORT} completion cell), so every call form
         * selects that occurrence's typed surface; {@code
         * HostFunction(resolvedModule, exportName, descriptor)} with no
         * occurrence cell for a declared function export of a STDLIB-kind
         * import (its surface is once-only per resolved module); and
         * {@code ExternalFunction(resolvedModule, exportName, descriptor,
         * owner)} with the route-derived execution owner for an
         * IMPLEMENTATION-kind import — all keyed by the published cell. A
         * non-function descriptor produces the read with no
         * registration.</p>
         *
         * <p><b>The STDLIB branch (R2).</b> The closed
         * {@link StdlibFunctionCatalog} is the STDLIB kind's resolution
         * authority: {@code lookup(resolvedModulePath, field)}; an absent
         * row fails closed, and the row's declared descriptor is the
         * read's own — the checked descriptor must equal it, else the read
         * fails closed and no registration happens.</p>
         *
         * <p>Fail-closed guards (each E6005
         * {@code CONSTRUCT_UNLOWERED}, no unit): a member base that is not
         * a module alias, an alias without a resolved import fact, a
         * member name not declared by the resolved import (the fact channel
         * is the resolved alias's checker {@code Symbol.ModuleSymbol}
         * resolved in the current module's checker scope — the project
         * interface index's declared-export entries are not a session
         * input), an import kind outside the closed set, a STDLIB member
         * outside the closed catalog, a STDLIB descriptor mismatch, and a
         * read whose checked descriptor is a class descriptor (a class
         * used as a value is outside the closed runtime value domain and
         * never emits or registers). A missing callee-route record for an
         * IMPLEMENTATION-kind read is the same producer defect: the
         * realized drive installs the resolved import facts and the route
         * facts (the call-machine and project entries), and a route-less
         * carrier session fails closed.</p>
         *
         */
        private ImportMaterialization materializeImportRead(MemberAccessExpr access,
                                                            IdentifierExpr alias,
                                                            ValueId slot) {
            // The read resolves its alias in the current module's checker
            // scope, never a root-table-only fact: the declared-export fact
            // channel is the resolved Symbol.ModuleSymbol's exports() map.
            SymbolTable scope = currentCheckerScope();
            Symbol aliasSymbol = scope == null ? null : scope.resolve(alias.name());
            if (!(aliasSymbol instanceof Symbol.ModuleSymbol moduleSymbol)) {
                throw new ConstructUnlowered("member read '" + alias.name() + "."
                    + access.field() + "' has a non-module base '" + alias.name()
                    + "' (module member reads are EXPORT_READ shapes)", access.span());
            }
            ResolvedImport importFact = importByAlias(alias.name());
            if (importFact == null) {
                throw new ConstructUnlowered("import alias '" + alias.name()
                    + "' of '" + alias.name() + "." + access.field()
                    + "' without a resolved import fact (a missing checker fact is a "
                    + "producer defect)", access.span());
            }
            // The declared-export guard (G3): the member name must be a key
            // of the resolved alias's checker module symbol. The project
            // interface index's declared-export entries are not a session
            // input; the checker symbol stays the authority.
            if (!moduleSymbol.exports().containsKey(access.field())) {
                throw new ConstructUnlowered("module member '" + alias.name() + "."
                    + access.field() + "' is not a declared export of module '"
                    + importFact.resolvedModuleId().path()
                    + "' (the resolved alias's checker module symbol carries the "
                    + "declared-export facts; an undeclared member never lowers to an "
                    + "EXPORT_READ)", access.span());
            }
            RuntimeDescriptor checked = ContainerPayloadDescriptors
                .resultDescriptorOf(checkedType(access));
            // The class-descriptor guard (G7): a class used as a value is
            // outside the closed runtime value domain. The read never emits
            // an EXPORT_READ and never registers.
            if (checked instanceof RuntimeDescriptor.Class) {
                throw new ConstructUnlowered("module member '" + alias.name() + "."
                    + access.field() + "' of module '"
                    + importFact.resolvedModuleId().path()
                    + "' is a class-descriptor read (a class used as a value is "
                    + "outside the closed runtime value domain; the read never emits "
                    + "and never registers)", access.span());
            }
            RuntimeDescriptor descriptor;
            switch (importFact.kind()) {
                case HOST, IMPLEMENTATION -> descriptor = checked;
                case STDLIB -> {
                    StdlibFunctionCatalog.Entry row = StdlibFunctionCatalog
                        .lookup(importFact.resolvedModuleId().path(), access.field())
                        .orElseThrow(() -> new ConstructUnlowered("stdlib member '"
                            + alias.name() + "." + access.field() + "' of module '"
                            + importFact.resolvedModuleId().path()
                            + "' is outside the closed stdlib catalog (the catalog is "
                            + "the STDLIB kind's resolution authority; an unrecognized "
                            + "stdlib member is never a call)", access.span()));
                    descriptor = row.declaredDescriptor();
                    if (!descriptor.equals(checked)) {
                        throw new ConstructUnlowered("stdlib member '" + alias.name()
                            + "." + access.field() + "' has checked descriptor "
                            + checked.canonicalSpecText() + " but the closed stdlib "
                            + "catalog row for '" + importFact.resolvedModuleId().path()
                            + "' declares " + descriptor.canonicalSpecText()
                            + " (the row's declared descriptor is the EXPORT_READ's own)",
                                access.span());
                    }
                }
                default -> throw new ConstructUnlowered("import kind "
                    + importFact.kind() + " of module member '" + alias.name() + "."
                    + access.field() + "' of module '"
                    + importFact.resolvedModuleId().path()
                    + "' has no import-member read arm (the closed produced set is "
                    + "IMPLEMENTATION/STDLIB/HOST; the parent-pinned DECLARATION "
                    + "kind has no producer)", access.span());
            }
            FunctionBindingRegistry.FunctionValueImportFacts facts = null;
            if (descriptor instanceof RuntimeDescriptor.Func functionDescriptor) {
                // The owning source import occurrence (the alias cell of the
                // import declaration the read materialized through): the
                // recorded HostFunction binding carries it, so every direct,
                // stored, namespace, adapter and async call form selects this
                // occurrence's typed surface and never a module/name fallback.
                // A STDLIB read's surface is once-only per resolved module, so
                // it records no occurrence cell.
                BindingId importCell = importFact.kind() == ExternalModuleKind.HOST
                    ? importAliasCells.get(alias.name()) : null;
                if (importFact.kind() == ExternalModuleKind.HOST && importCell == null) {
                    throw new ConstructUnlowered("import alias '" + alias.name()
                        + "' of '" + alias.name() + "." + access.field()
                        + " without a hoisted alias cell (a missing hoist fact is a "
                        + "producer defect)", access.span());
                }
                facts = switch (importFact.kind()) {
                    case HOST -> new FunctionBindingRegistry
                        .FunctionValueImportFacts(importFact.resolvedModuleId(), null,
                            access.field(), functionDescriptor, importCell);
                    case STDLIB -> new FunctionBindingRegistry
                        .FunctionValueImportFacts(importFact.resolvedModuleId(), null,
                            access.field(), functionDescriptor);
                    case IMPLEMENTATION -> new FunctionBindingRegistry
                        .FunctionValueImportFacts(null,
                            importFact.resolvedModuleId(), access.field(),
                            functionDescriptor);
                    default -> throw new ConstructUnlowered("import kind "
                        + importFact.kind() + " has no import-member registration arm",
                            access.span());
                };
            }
            ValueId exportValue = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId exportAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId exportOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin exportOrigin = new SourceOrigin(sourceId,
                toSourceSpan(access.span()), SourceOriginKind.USER, exportAnchor,
                currentParent());
            KindPayload.ExportReadPayload exportPayload =
                new KindPayload.ExportReadPayload(importFact.resolvedModuleId(),
                    access.field(), descriptor, exportValue);
            emit(buildOp(exportOpId, SemanticOpKind.EXPORT_READ, exportPayload, exportValue,
                descriptor, List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE,
                exportOrigin));
            if (facts == null) {
                // A non-function descriptor produces the read with no
                // registration (R3).
                return new ImportMaterialization(null, descriptor, exportValue);
            }
            registry.registerHostOrExternalImport(
                new FunctionAllocationIdentity(exportValue.id()), exportPayload, facts);
            FunctionExecutionBinding binding = registry.bindings().get(
                new FunctionAllocationIdentity(exportValue.id()));
            if (binding == null) {
                throw new IllegalStateException("the import materialization of '"
                    + alias.name() + "." + access.field()
                    + "' registered no binding (producer defect)");
            }
            return new ImportMaterialization(binding, descriptor, exportValue);
        }

        /** The import-member sync call arm (CALL(HOST)/CALL(EXTERNAL)). */
        private ValueId lowerUserCallImport(CallExpr call, ValueId slot,
                                            MemberAccessExpr access, IdentifierExpr alias) {
            ImportMaterialization materialization = materializeImportRead(access, alias, null);
            if (!(materialization.descriptor() instanceof RuntimeDescriptor.Func descriptor)
                    || materialization.binding() == null) {
                throw new ConstructUnlowered("import call callee '" + alias.name()
                    + "." + access.field() + "' is not a function-typed export (the "
                    + "checker admits function-typed call callees only)", access.span());
            }
            return lowerImportCall(call, slot, materialization.binding(), descriptor);
        }

        /**
         * {@code CALL(HOST)}/{@code CALL(EXTERNAL)} — the imported-callee
         * arms: the parameter boundaries follow the pinned host/external
         * cells ({@code DEAL_TO_HOST}+{@code HOST_PARAMETER},
         * {@code EXTERNAL_PARAMETER}); the single return boundary follows
         * the owner — {@code HOST_TO_DEAL}+{@code HOST_SYNC_RETURN} run
         * by the call op for host functions, the callee
         * {@code EXTERNAL_ENTRY}'s {@code EXTERNAL_RETURN} for
         * {@code SHARED_BODY} externals (no caller-side boundary), and
         * the call-op {@code EXTERNAL_RETURN} for {@code RETAINED_ABI}
         * externals.
         */
        private ValueId lowerImportCall(CallExpr call, ValueId slot,
                                        FunctionExecutionBinding binding,
                                        RuntimeDescriptor.Func descriptor) {
            if (descriptor.isAsync()) {
                throw new ConstructUnlowered("async import call outside await (the "
                    + "checker's E3014 pins the shape; the await arm owns async starts)",
                        call.span());
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, true));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != descriptor.paramTypes().size()) {
                throw new ConstructUnlowered("import call with " + args.size()
                    + " arguments for " + descriptor.paramTypes().size()
                    + " parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor resultType = descriptor.returnType();
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            OpId callOpId = ids.nextOpId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            boolean host = binding instanceof FunctionExecutionBinding.HostFunction
                || binding instanceof FunctionExecutionBinding.HostFunctionValue;
            if (host) {
                hostParameterBoundaries(descriptor, args, call, callOpId,
                    parameterBoundaryOps, parameterBoundaryIds);
            } else {
                FunctionExecutionBinding.ExternalFunction callee =
                    (FunctionExecutionBinding.ExternalFunction) binding;
                for (int i = 0; i < args.size(); i++) {
                    // The cross-module declared callee's own parameter
                    // annotation, in the callee's file (P3).
                    DeclaredParameterOrigin declared = declaredParameterOrigin(
                        callee.moduleId(), callee.exportName(), args.size(), i);
                    SemanticOp boundary = buildChildBoundary(BoundaryKind.EXTERNAL_PARAMETER,
                        descriptor.paramTypes().get(i), args.get(i),
                        declared != null ? declared.span() : call.span(),
                        declared != null ? declared.sourceId() : null,
                        callOpId);
                    parameterBoundaryOps.add(boundary);
                    parameterBoundaryIds.add(boundary.opId());
                }
            }
            OpId returnBoundaryOpId = null;
            OpId externalEntryRef = null;
            SemanticOp hostCrossing = null;
            ModuleId hostCrossingModule = null;
            if (host) {
                hostCrossing = emitHostReturnBoundary(descriptor, result, call.span(),
                    callOpId);
                returnBoundaryOpId = hostCrossing.opId();
                hostCrossingModule = binding instanceof FunctionExecutionBinding.HostFunction
                    ? ((FunctionExecutionBinding.HostFunction) binding).hostModuleId()
                    : ((FunctionExecutionBinding.HostFunctionValue) binding).hostModuleId();
            } else if (binding instanceof FunctionExecutionBinding.ExternalFunction external) {
                if (external.executionOwner() == ExternalExecutionOwner.SHARED_BODY) {
                    externalEntryRef = externalEntryRefOf(external);
                } else {
                    returnBoundaryOpId = emitExternalReturnBoundary(descriptor, result,
                        call.span(), callOpId);
                }
            } else {
                throw new IllegalStateException("import call binding shape "
                    + binding.getClass().getSimpleName() + " (producer defect)");
            }
            CallMode mode = host ? CallMode.HOST : CallMode.EXTERNAL;
            emit(buildOp(callOpId, SemanticOpKind.CALL,
                new KindPayload.CallPayload(mode, new KindPayload.CallCallee.Static(binding),
                    descriptor, parameterBoundaryIds, returnBoundaryOpId, null, null,
                    externalEntryRef),
                result, resultType, args, argTypes,
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            // The producer rule's call arm: a host crossing's function-typed
            // return is the host-materialized value class and registers
            // exactly one HostFunctionValue at the producing crossing (M2 item
            // 1); every other imported call result registers the closed
            // dynamic record keyed by the call's result identity (M2 item 2).
            registerCallResultMaterialization(result, callOpId, hostCrossing,
                hostCrossingModule, resultType);
            return result;
        }

        /**
         * {@code ASYNC_START} + {@code AWAIT} — the E7 await arm: the
         * checked await callee is an async call (the checker's E3012/
         * E3013/E3014 pin the shape). The callee and every argument
         * operand complete left-to-right before the {@code ASYNC_START}
         * START; the parameter boundaries follow the closed table per the
         * resolved binding; the op publishes exactly one token per call
         * (canonical for DEAL bodies and async host operations, an alias
         * through {@code ExternalAsyncLink} for async externals, an
         * {@code ADAPTER_INNER} alias for adapter-over-async); the single
         * {@code AWAIT} runs exactly one {@code ASYNC_COMPLETION}
         * boundary at the await site.
         */
        private ValueId lowerAwaitExpression(AwaitExpression awaitExpression, ValueId slot) {
            if (!(awaitExpression.callee() instanceof CallExpr call)) {
                throw new ConstructUnlowered("await callee shape "
                    + awaitExpression.callee().getClass().getSimpleName()
                    + " (the parser's E1042 pins await over a call)", awaitExpression.span());
            }
            return lowerAwaitCall(call, slot, awaitExpression.span());
        }

        private ValueId lowerAwaitCall(CallExpr call, ValueId slot, Span awaitSpan) {
            if (call.callee() instanceof IdentifierExpr identifier) {
                BindingSite site = bindingSiteResolver == null
                    ? null : bindingSiteResolver.resolve(identifier.name());
                FunctionContext context = site == null
                    ? null : contextsByBindingId.get(site.binding());
                if (context != null) {
                    if (captureCarriedInvocation(site.binding(), context)) {
                        // A capture-carrying declared-body await — a
                        // nested-group member (the group publication wrote
                        // the member's closure carrier into the member cell)
                        // or any other declared-name await whose target owns
                        // creation-site captures. The published carrier holds
                        // the captured cells of the target's own creation
                        // instance; the static DEAL body arm would rebuild
                        // the target's factory at the await site and
                        // re-resolve the target's captures from the calling
                        // function's frame — exact only for single-incarnation
                        // module-level cells — so the awaited sibling, self,
                        // enclosing-body, or escaped-instance call goes
                        // through the value-carried `Indirect` shape, exactly
                        // like the synchronous capture-carrying call arm.
                        return lowerAwaitCarrier(call, slot, awaitSpan,
                            identifier);
                    }
                    return lowerAwaitDeclared(call, slot, awaitSpan, identifier, context);
                }
                FrameResolution resolution = resolveFrame(identifier.name());
                // The awaited twin of the call arm's mutable-binding rule:
                // an assigned async function-typed binding resolves its
                // callee class from the awaited value, never from the
                // declaration's initializer.
                ValueId identity = resolution == null
                        || assignedFunctionBinding(resolution.entry())
                    ? null : functionIdentity.get(resolution.entry().incarnation());
                if (identity == null) {
                    // The dynamic async arm (the awaited callee value has no
                    // statically resolvable execution binding).
                    return lowerDynamicAwait(call, slot, awaitSpan, identifier.name(),
                        lowerDynamicCalleeValue(identifier, resolution));
                }
                ValueId calleeValue = lowerExpression(identifier);
                FunctionExecutionBinding binding = registry.bindings().get(
                    new FunctionAllocationIdentity(calleeValue.id()));
                if (binding == null) {
                    throw new ConstructUnlowered("await callee '" + identifier.name()
                        + "' identity " + calleeValue
                        + " has no registered FunctionExecutionBinding (producer defect)",
                            identifier.span());
                }
                if (binding instanceof FunctionExecutionBinding.DynamicFunctionValue) {
                    // The producer rule's callee in await position: the
                    // dynamic registration takes the closed dynamic async
                    // arm over the same carrier read (the callee-position
                    // exclusivity admits it exactly there).
                    return lowerDynamicAwait(call, slot, awaitSpan, identifier.name(),
                        calleeValue);
                }
                if (binding instanceof FunctionExecutionBinding.AdapterBinding adapter) {
                    if (staticSourceIdentity(adapter) == null) {
                        // The REEVALUATE_THUNK adapter source: the thunk
                        // re-executes the source expression per invocation,
                        // so the source's execution class is selected at
                        // execution — the closed DYNAMIC async arm over the
                        // adapter's published identity records the single
                        // DEAL-body task cell and runs no statically selected
                        // source start.
                        return lowerDynamicAwait(call, slot, awaitSpan,
                            identifier.name(), calleeValue);
                    }
                    return lowerAdapterOverAsync(call, slot, awaitSpan, adapter,
                        identifier.name());
                }
                if (binding instanceof FunctionExecutionBinding.LoweredBody
                        && valueCarriedClosure(binding)) {
                    // An ordinary function-value alias of a capture-carrying
                    // declared body (a nested group member awaited through a
                    // `let`, the async twin of
                    // {@link #lowerUserCallBinding}'s value-carried call):
                    // the alias holds the published closure whose
                    // creation-site captured cells name its own group
                    // instance, so the await invokes that carrier instead of
                    // rebuilding the member's factory at the await site —
                    // the reconstruction re-resolves the target's captures
                    // from the calling member's frame and cannot name a
                    // sibling creation's cells. The pending-capture
                    // classification covers an alias of a member whose own
                    // body walk is still open. A capture-free body keeps the
                    // static shape: the await site's reconstruction is the
                    // same closure.
                    return lowerAsyncStart(call, slot, awaitSpan, binding, false,
                        calleeValue);
                }
                return lowerAsyncStart(call, slot, awaitSpan, binding, false);
            }
            if (call.callee() instanceof MemberAccessExpr access
                    && access.object() instanceof IdentifierExpr alias
                    && isModuleSymbol(alias.name())) {
                ImportMaterialization materialization =
                    materializeImportRead(access, alias, null);
                if (!(materialization.descriptor() instanceof RuntimeDescriptor.Func)
                        || materialization.binding() == null) {
                    throw new ConstructUnlowered("await callee '" + alias.name()
                        + "." + access.field() + "' is not a function-typed export (the "
                        + "checker admits function-typed callees only)", access.span());
                }
                return lowerAsyncStart(call, slot, awaitSpan, materialization.binding(),
                    false);
            }
            // The dynamic async arm over a callee expression that is neither
            // an identifier nor an import-member access. A callee expression
            // whose value carries a statically classified registration takes
            // the static/indirect arms of the closed table (the adapter arm
            // included) over the same evaluated value; a same-walk body and
            // the producer rule's dynamic record take the dynamic async arm.
            ValueId dynamicCallee = lowerExpression(call.callee());
            FunctionExecutionBinding registration = registry.bindings().get(
                new FunctionAllocationIdentity(dynamicCallee.id()));
            if (registration instanceof FunctionExecutionBinding.AdapterBinding
                    adapter) {
                if (staticSourceIdentity(adapter) == null) {
                    // The runtime-resolved adapter source (the sync arm's
                    // twin): the closed DYNAMIC async arm over the adapter's
                    // published identity.
                    return lowerDynamicAwait(call, slot, awaitSpan,
                        describeDynamicCallee(call.callee()), dynamicCallee);
                }
                return lowerAdapterOverAsync(call, slot, awaitSpan, adapter,
                    describeDynamicCallee(call.callee()));
            }
            if (registration != null
                    && !(registration instanceof FunctionExecutionBinding.LoweredBody)
                    && !(registration
                        instanceof FunctionExecutionBinding.DynamicFunctionValue)) {
                return lowerAsyncStart(call, slot, awaitSpan, registration, false);
            }
            return lowerDynamicAwait(call, slot, awaitSpan,
                describeDynamicCallee(call.callee()), dynamicCallee);
        }

        private ValueId lowerDynamicAwait(CallExpr call, ValueId slot, Span awaitSpan,
                                         String calleeName, ValueId calleeValue) {
            RuntimeDescriptor.Func signature = dynamicCalleeSignature(call.callee(),
                "await of '" + calleeName + "'");
            if (!signature.isAsync()) {
                throw new ConstructUnlowered("await of non-async callee '" + calleeName
                    + "' (the checker's E3013 pins the shape)", call.span());
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, false));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != signature.paramTypes().size()) {
                throw new ConstructUnlowered("async call of '" + calleeName + "' with "
                    + args.size() + " arguments for " + signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor completion = signature.returnType();
            ValueId awaitResult = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);

            FunctionContext calleeBody = sameWalkCalleeBody(calleeValue);
            OpId startOpId;
            OpId taskCellOpId;
            if (calleeBody != null) {
                calleeBody.assignShape(InvocationShape.SOURCE_ASYNC,
                    calleeBody.callSiteOpId);
                startOpId = calleeBody.callSiteUsed
                    ? ids.nextOpId(module, nextOrdinal++, 0) : calleeBody.callSiteOpId;
                calleeBody.callSiteUsed = true;
                taskCellOpId = calleeBody.returnBoundaryOpId;
            } else {
                startOpId = ids.nextOpId(module, nextOrdinal++, 0);
                taskCellOpId = emitCallOwnedReturnBoundary(signature, awaitResult,
                    call.span(), startOpId);
            }
            AsyncTokenId token = new AsyncTokenId.Canonical(
                ids.nextTokenId(module, nextOrdinal++, 0), AsyncTokenOwner.DEAL_BODY_TASK);
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                SemanticOp boundary = buildChildBoundary(BoundaryKind.FUNCTION_PARAMETER,
                    signature.paramTypes().get(i), args.get(i), call.span(), startOpId);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(startOpId, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Dynamic(calleeValue),
                    AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.RUN,
                    parameterBoundaryIds, completion, taskCellOpId, null, null),
                token, InternalResultType.INTERNAL_ASYNC, args, argTypes,
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            return lowerAwait(token, completion, awaitSpan, awaitResult);
        }

        /** The awaited declared-function arm: ASYNC_START(DEAL_BODY) + AWAIT. */
        private ValueId lowerAwaitDeclared(CallExpr call, ValueId slot, Span awaitSpan,
                                           IdentifierExpr identifier,
                                           FunctionContext context) {
            if (!context.signature.isAsync()) {
                throw new ConstructUnlowered("await of non-async function '"
                    + identifier.name() + "' (the checker's E3013 pins the shape)",
                        identifier.span());
            }
            String entryExportName = moduleEntryExportName(context);
            if (entryExportName != null) {
                // The exported declaration's reserved entry shape owns the
                // awaited invocation record: a same-module await realizes
                // the declaration's own entry — its body identity and
                // single return cell stay the reservation's — instead of
                // re-shaping the reservation into a source async start.
                lowerExpression(identifier);
                return lowerModuleEntryAsyncStart(call, slot, awaitSpan, context,
                    entryExportName);
            }
            context.assignShape(InvocationShape.SOURCE_ASYNC, context.callSiteOpId);
            lowerExpression(identifier);
            return lowerAsyncStart(call, slot, awaitSpan,
                new FunctionExecutionBinding.LoweredBody(context.functionId,
                    context.bodyBlock),
                false);
        }

        /**
         * The same-module entry realization of one awaited exported
         * declaration: the declaration's reserved {@code EXTERNAL_ENTRY}
         * owns the invocation record, so the call realizes the closed async
         * external link over the module's own entry — the caller token
         * aliases the entry's canonical {@code DEAL_BODY_TASK} token through
         * {@code ExternalAsyncLink} — and the entry runs the single
         * {@code FUNCTION_RETURN} cell. The parameter cells keep the
         * declaration-owned origins (P3). One realization serves the direct
         * declared-name await ({@link #lowerAwaitDeclared}) and every
         * value-carried reference through the declaration's published
         * closure carrier ({@link #lowerAsyncStart}'s {@code LoweredBody}
         * arm).
         */
        private ValueId lowerModuleEntryAsyncStart(CallExpr call, ValueId slot,
                                                   Span awaitSpan,
                                                   FunctionContext context,
                                                   String exportName) {
            RuntimeDescriptor.Func signature = context.signature;
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, true));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            OpId startOpId = context.callSiteUsed
                ? ids.nextOpId(module, nextOrdinal++, 0)
                : context.callSiteOpId;
            context.callSiteUsed = true;
            AsyncTokenId calleeToken = new AsyncTokenId.Canonical(
                context.shapeOpId.id(), AsyncTokenOwner.DEAL_BODY_TASK);
            AsyncTokenId token = new AsyncTokenId.Alias(
                ids.nextTokenId(module, nextOrdinal++, 0), calleeToken,
                AsyncLinkKind.EXTERNAL_LINK);
            ExternalAsyncLink externalAsyncLink = new ExternalAsyncLink(module,
                exportName, calleeToken);
            if (args.size() != signature.paramTypes().size()) {
                throw new ConstructUnlowered("async call of the exported '"
                    + exportName + "' with " + args.size() + " arguments for "
                    + signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)", call.span());
            }
            RuntimeDescriptor completion = signature.returnType();
            ValueId awaitResult = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                // The declared callee's own parameter annotation is the
                // parameter cell's origin (P3), in the callee's file: the
                // same declaration-owned origin the cross-module arm
                // resolves from the declared-parameter index.
                SemanticOp boundary = buildChildBoundary(
                    BoundaryKind.EXTERNAL_PARAMETER,
                    signature.paramTypes().get(i), args.get(i),
                    context.parameterSpan(i), startOpId);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(startOpId, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(
                        new FunctionExecutionBinding.ExternalFunction(module,
                            exportName, signature,
                            ExternalExecutionOwner.SHARED_BODY)),
                    AsyncStartSource.EXTERNAL, ParameterBoundaryMode.RUN,
                    parameterBoundaryIds, completion, null, null,
                    externalAsyncLink),
                token, InternalResultType.INTERNAL_ASYNC, args, argTypes,
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            return lowerAwait(token, completion, awaitSpan, awaitResult);
        }

        /**
         * The awaited capture-carrying declared-name arm: the declaration's
         * published closure carrier is the only invocation source whose
         * creation-site captures name the target's own instance (the async
         * twin of {@link #lowerUserCallBinding}'s value-carried
         * declared-name call). The declaration context's reserved call-site
         * identity and single return boundary stay the invocation's own
         * ({@link #lowerAsyncStart}'s {@code LoweredBody} arm), and the
         * carrier supplies the captured cells to the body.
         */
        private ValueId lowerAwaitCarrier(CallExpr call, ValueId slot,
                                          Span awaitSpan,
                                          IdentifierExpr identifier) {
            ValueId calleeValue = lowerExpression(identifier);
            FunctionExecutionBinding binding = registry.bindings().get(
                new FunctionAllocationIdentity(calleeValue.id()));
            if (binding == null) {
                throw new ConstructUnlowered("await callee '" + identifier.name()
                    + "' identity " + calleeValue
                    + " has no registered FunctionExecutionBinding (producer defect)",
                        identifier.span());
            }
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody)) {
                throw new ConstructUnlowered("await callee '" + identifier.name()
                    + "' identity " + calleeValue + " resolves the static class "
                    + binding.getClass().getSimpleName()
                    + " instead of the declaration's published body carrier "
                    + "(producer defect)", identifier.span());
            }
            return lowerAsyncStart(call, slot, awaitSpan, binding, false, calleeValue);
        }

        /** The statically resolved async start (no value-carried callee). */
        private ValueId lowerAsyncStart(CallExpr call, ValueId slot, Span awaitSpan,
                                        FunctionExecutionBinding binding, boolean nested) {
            return lowerAsyncStart(call, slot, awaitSpan, binding, nested, null);
        }

        /**
         * The shared {@code ASYNC_START}+{@code AWAIT} emission over the
         * resolved binding: the parameter-boundary cells per the closed
         * table, one token per call, and exactly one
         * {@code ASYNC_COMPLETION} boundary at the await site. The
         * arguments lower through {@link #lowerCallArgument}: a declared
         * callee's parameter cell performs the contextual member read's
         * kind check at its own origin, exactly as the synchronous
         * declared-callee arms do. A non-null carrier callee emits the
         * value-carried {@code CallCallee.Indirect} shape over the
         * published closure value (the awaited capture-carrying
         * declared-name arm, the awaited group-member carrier, and the
         * awaited alias of a capture-carrying body), so the task invokes
         * the carrier's own captured cells instead of reconstructing the
         * target's factory at the await site; the callee's reserved
         * call-site identity and single return boundary stay the invocation's
         * own either way.
         */
        private ValueId lowerAsyncStart(CallExpr call, ValueId slot, Span awaitSpan,
                                        FunctionExecutionBinding binding, boolean nested,
                                        ValueId carrierCallee) {
            if (carrierCallee != null
                    && !(binding instanceof FunctionExecutionBinding.LoweredBody)) {
                throw new ConstructUnlowered("the value-carried async start of '"
                    + describeDynamicCallee(call.callee()) + "' resolves the static class "
                    + binding.getClass().getSimpleName()
                    + " instead of a declared body carrier (producer defect)", call.span());
            }
            if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                FunctionContext entryContext =
                    contextsByFunctionId.get(body.functionId());
                String entryExportName = entryContext == null ? null
                    : moduleEntryExportName(entryContext);
                if (entryExportName != null) {
                    // The exported declaration's reserved entry shape owns
                    // the awaited invocation record: a same-module
                    // value-carried reference — an awaited alias or any
                    // other function-typed binding holding the
                    // declaration's published closure — realizes the
                    // declaration's own entry over the shared body,
                    // exactly like the direct declared-name await
                    // ({@link #lowerAwaitDeclared}); the carrier read
                    // already ran at the await site.
                    return lowerModuleEntryAsyncStart(call, slot, awaitSpan,
                        entryContext, entryExportName);
                }
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, true));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            RuntimeDescriptor.Func signature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(call.callee()));
            AsyncStartSource source;
            OpId startOpId;
            OpId returnBoundaryOpId = null;
            String hostOperationLabel = null;
            ExternalAsyncLink externalAsyncLink = null;
            AsyncTokenId token;
            FailurePolicyId policy = FailurePolicyId.NO_DEAL_FAILURE;
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext context = contextsByFunctionId.get(body.functionId());
                    if (context == null) {
                        throw new ConstructUnlowered("the async body binding's function "
                            + body.functionId().id()
                            + " has no lowering context (producer defect)", call.span());
                    }
                    context.assignShape(InvocationShape.SOURCE_ASYNC, context.callSiteOpId);
                    source = AsyncStartSource.DEAL_BODY;
                    startOpId = context.callSiteUsed
                        ? ids.nextOpId(module, nextOrdinal++, 0) : context.callSiteOpId;
                    context.callSiteUsed = true;
                    returnBoundaryOpId = context.returnBoundaryOpId;
                    token = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.DEAL_BODY_TASK);
                    if (args.size() != context.signature.paramTypes().size()) {
                        throw new ConstructUnlowered("async call with " + args.size()
                            + " arguments for " + context.signature.paramTypes().size()
                            + " parameters (the checker admits exact arity only)", call.span());
                    }
                    for (int i = 0; i < args.size(); i++) {
                        SemanticOp boundary = buildChildBoundary(
                            BoundaryKind.FUNCTION_PARAMETER,
                            context.signature.paramTypes().get(i), args.get(i),
                            context.parameterSpan(i), startOpId);
                        parameterBoundaryOps.add(boundary);
                        parameterBoundaryIds.add(boundary.opId());
                    }
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    source = AsyncStartSource.HOST;
                    startOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    hostOperationLabel = host.hostModuleId().path() + "." + host.exportName();
                    token = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.HOST_OPERATION);
                    policy = FailurePolicyId.ASYNC_OPERATION_HANDLE;
                    if (args.size() != host.descriptor().paramTypes().size()) {
                        throw new ConstructUnlowered("async host call with " + args.size()
                            + " arguments for " + host.descriptor().paramTypes().size()
                            + " parameters (the checker admits exact arity only)", call.span());
                    }
                    hostParameterBoundaries(host.descriptor(), args, call, startOpId,
                        parameterBoundaryOps, parameterBoundaryIds);
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    source = AsyncStartSource.HOST;
                    startOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    hostOperationLabel = hostValue.hostModuleId().path() + ".@value";
                    token = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.HOST_OPERATION);
                    policy = FailurePolicyId.ASYNC_OPERATION_HANDLE;
                    if (args.size() != hostValue.descriptor().paramTypes().size()) {
                        throw new ConstructUnlowered("async host-value call with "
                            + args.size() + " arguments for "
                            + hostValue.descriptor().paramTypes().size()
                            + " parameters (the checker admits exact arity only)", call.span());
                    }
                    hostParameterBoundaries(hostValue.descriptor(), args, call, startOpId,
                        parameterBoundaryOps, parameterBoundaryIds);
                }
                case FunctionExecutionBinding.ExternalFunction external -> {
                    source = AsyncStartSource.EXTERNAL;
                    startOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    Map<String, OpId> entries = calleeExternalEntries.get(external.moduleId());
                    OpId entryOp = entries == null ? null : entries.get(external.exportName());
                    if (entryOp == null) {
                        throw new ConstructUnlowered("the async external call of '"
                            + external.moduleId() + "'." + external.exportName()
                            + " has no recorded EXTERNAL_ENTRY in the callee unit", call.span());
                    }
                    AsyncTokenId calleeToken = new AsyncTokenId.Canonical(entryOp.id(),
                        AsyncTokenOwner.DEAL_BODY_TASK);
                    token = new AsyncTokenId.Alias(
                        ids.nextTokenId(module, nextOrdinal++, 0), calleeToken,
                        AsyncLinkKind.EXTERNAL_LINK);
                    externalAsyncLink = new ExternalAsyncLink(external.moduleId(),
                        external.exportName(), calleeToken);
                    if (args.size() != external.descriptor().paramTypes().size()) {
                        throw new ConstructUnlowered("async external call with "
                            + args.size() + " arguments for "
                            + external.descriptor().paramTypes().size()
                            + " parameters (the checker admits exact arity only)", call.span());
                    }
                    for (int i = 0; i < args.size(); i++) {
                        // The cross-module declared callee's own parameter
                        // annotation, in the callee's file (P3) — the same
                        // declaration-owned origin the synchronous
                        // import-call arm resolves.
                        DeclaredParameterOrigin declared = declaredParameterOrigin(
                            external.moduleId(), external.exportName(), args.size(), i);
                        SemanticOp boundary = buildChildBoundary(
                            BoundaryKind.EXTERNAL_PARAMETER,
                            external.descriptor().paramTypes().get(i), args.get(i),
                            declared != null ? declared.span() : call.span(),
                            declared != null ? declared.sourceId() : null,
                            startOpId);
                        parameterBoundaryOps.add(boundary);
                        parameterBoundaryIds.add(boundary.opId());
                    }
                }
                case FunctionExecutionBinding.AdapterBinding adapter ->
                    throw new ConstructUnlowered("adapter-over-async lower via "
                        + "lowerAdapterOverAsync (producer defect)", call.span());

                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the await call");
            }
            RuntimeDescriptor completion = signature.returnType();
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(startOpId, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    carrierCallee == null
                        ? new KindPayload.CallCallee.Static(binding)
                        : new KindPayload.CallCallee.Indirect(carrierCallee), source,
                    nested ? ParameterBoundaryMode.ELIDED_BY_ADAPTER : ParameterBoundaryMode.RUN,
                    parameterBoundaryIds, completion, returnBoundaryOpId,
                    hostOperationLabel, externalAsyncLink),
                token, InternalResultType.INTERNAL_ASYNC, args, argTypes, policy, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            return lowerAwait(token, completion, awaitSpan, slot);
        }

        /**
         * Adapter-over-async: the outer {@code ASYNC_START} carries the
         * {@code AdapterBinding} with the ×N target-signature parameter
         * boundaries (the complete set), an {@code ADAPTER_INNER} alias
         * token, and zero return boundaries; the nested source
         * {@code ASYNC_START} (parented to the outer op) carries
         * {@code ELIDED_BY_ADAPTER}, zero parameter boundaries, the
         * leading M argument operands, and its own per-resolution
         * terminal per the statically fixed source binding. A declared
         * body source's nested start is value-carried over the loaded D15
         * source carrier (the source's closure identity), so the
         * invocation runs the source declaration's own creation-site
         * captured cells.
         *
         * <p>A runtime-resolved source — a retained declaration-site
         * operand (the {@code VALUE} capture mode), a materialized source
         * whose producing registration is the producer rule's
         * {@code DynamicFunctionValue}, or an adapter-valued source (whose own
         * published invocation chain selects the execution class at each
         * invocation) — records the runtime-resolved nested shape instead: the
         * nested source start's callee is the {@code Dynamic} value identity
         * the adapter protocol resolves at
         * execution (the retained operand, or the scratch identity the outer
         * task publishes the resolved adapter source under), and its recorded
         * task cell is
         * the single caller-side {@code FUNCTION_RETURN} the closed dynamic
         * resolution applies to a resolved DEAL body. The source's own class
         * — a DEAL body, a host surface entry, a retained-ABI external, or a
         * nested adapter — is never guessed at the call site. A
         * {@code REEVALUATE_THUNK} source never reaches this arm: the closed
         * dynamic async arm over the adapter's published identity owns it
         * ({@link #lowerDynamicAwait}).</p>
         */
        private ValueId lowerAdapterOverAsync(CallExpr call, ValueId slot, Span awaitSpan,
                FunctionExecutionBinding.AdapterBinding adapter, String calleeName) {
            ValueId sourceIdentity = staticSourceIdentity(adapter);
            if (sourceIdentity == null) {
                throw new ConstructUnlowered("adapter-over-async of '" + calleeName
                    + "' whose source is a re-evaluated thunk (the closed dynamic"
                    + " async arm owns it; producer defect)", call.span());
            }
            FunctionExecutionBinding source = registry.bindings().get(
                new FunctionAllocationIdentity(sourceIdentity.id()));
            // The runtime-resolved source arm: the adapter protocol resolves
            // the source's execution class at each invocation, so the nested
            // source start names that runtime identity (never a static
            // body/token/return-cell shape it does not have). A retained
            // declaration-site operand (the {@code VALUE} capture mode) is
            // never a registered function identity, a materialized source
            // registers the producer rule's
            // {@code DynamicFunctionValue}, and an adapter-valued source
            // descends its own published invocation chain at execution
            // exactly like the synchronous D15 protocol (runtime provenance
            // as an adapter is not part of the value's type): its nested
            // start carries the scratch identity the outer task publishes the
            // resolved source under, because the adapter identity itself is a
            // statically classified callee the closed Dynamic-callee rule
            // reserves for the runtime arms.
            boolean nestedAdapterSource =
                source instanceof FunctionExecutionBinding.AdapterBinding;
            boolean runtimeResolved = source == null
                || source instanceof FunctionExecutionBinding.DynamicFunctionValue
                || nestedAdapterSource;
            ValueId runtimeSourceIdentity = !runtimeResolved ? null
                : nestedAdapterSource
                    ? ids.nextValueId(module, nextOrdinal++, 0)
                    : sourceIdentity;
            RuntimeDescriptor.Func target = adapter.targetSignature();
            RuntimeDescriptor.Func sourceSignature = adapter.sourceSignature();
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (int i = 0; i < call.args().size(); i++) {
                ExpressionNode argument = call.args().get(i);
                args.add(lowerParameterArgument(call, i, false));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != target.paramTypes().size()) {
                throw new ConstructUnlowered("adapter-over-async call of '" + calleeName
                    + "' with " + args.size() + " arguments for "
                    + target.paramTypes().size()
                    + " target parameters (the checker admits exact arity only)", call.span());
            }
            int m = sourceSignature.paramTypes().size();
            OpId outerOpId = ids.nextOpId(module, nextOrdinal++, 0);
            AnchorId outerAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            // The nested source op (its token pins the outer alias
            // referent and the source function's RETURN enclosing
            // invocation).
            OpId nestedOpId;
            AsyncTokenId sourceToken;
            OpId nestedReturnBoundary = null;
            String nestedHostLabel = null;
            ExternalAsyncLink nestedLink = null;
            AsyncStartSource nestedSource;
            // The nested source op's callee binding: the statically fixed
            // source, or the exported body's recorded entry protocol (the
            // same-module SHARED_BODY binding) when the source is an
            // entry-shaped declaration. A declared body source additionally
            // records its published closure identity: the nested source start
            // is value-carried over the loaded D15 source carrier, so the
            // invocation runs the source declaration's own creation-site
            // captured cells instead of reconstructing its factory from the
            // adapter caller's frame.
            FunctionExecutionBinding nestedBinding = source;
            ValueId nestedCalleeIdentity = null;
            FailurePolicyId nestedPolicy = FailurePolicyId.NO_DEAL_FAILURE;
            // The awaited completion's value identity: the runtime-resolved
            // arm records it as the single caller-side task cell's input
            // before the nested start emits, so the cell owns a produced
            // value exactly like a dynamic await's recorded cell.
            ValueId awaitResult = runtimeResolved
                ? (slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0))
                : slot;
            if (runtimeResolved) {
                // The runtime-resolved source: neither a static body shape nor
                // a static execution class exists at the call site, so the
                // nested start names the runtime identity the adapter
                // protocol resolves at execution (the retained operand, or the
                // producer rule's materialized dynamic source) as its
                // Dynamic callee. The single caller-side DEAL-body task cell
                // is the closed dynamic resolution's recorded FUNCTION_RETURN
                // on the source signature's return type; the HOST/EXTERNAL
                // resolutions run their own terminals. The cell adds no second
                // source declaration: the adapter carrier's own invocation runs
                // the resolved source, whose class selects the terminal.
                nestedOpId = ids.nextOpId(module, nextOrdinal++, 0);
                nestedReturnBoundary = emitCallOwnedReturnBoundary(
                    sourceSignature, awaitResult, call.span(), nestedOpId);
                nestedSource = AsyncStartSource.DEAL_BODY;
                sourceToken = new AsyncTokenId.Canonical(
                    ids.nextTokenId(module, nextOrdinal++, 0),
                    AsyncTokenOwner.DEAL_BODY_TASK);
            } else {
                switch (source) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext context = contextsByFunctionId.get(body.functionId());
                    if (context == null) {
                        throw new ConstructUnlowered("the adapter's body-source binding "
                            + "has no lowering context (producer defect)", call.span());
                    }
                    String entryExportName = moduleEntryExportName(context);
                    if (entryExportName == null) {
                        context.assignShape(InvocationShape.SOURCE_ASYNC,
                            context.callSiteOpId);
                        nestedOpId = context.callSiteUsed
                            ? ids.nextOpId(module, nextOrdinal++, 0)
                            : context.callSiteOpId;
                        context.callSiteUsed = true;
                        nestedReturnBoundary = context.returnBoundaryOpId;
                        nestedSource = AsyncStartSource.DEAL_BODY;
                        // The D15 source load is the invocation's callee: the
                        // loaded carrier holds the source declaration's own
                        // creation instance (its closure identity), so the
                        // nested body task installs that instance's captured
                        // cells — never a call-site factory reconstruction
                        // that re-resolves the source's captures from the
                        // adapter caller's frame. The source's reserved call
                        // shape, body identity, token owner, and single
                        // return cell stay the nested start's own.
                        nestedCalleeIdentity = sourceIdentity;
                        sourceToken = new AsyncTokenId.Canonical(
                            ids.nextTokenId(module, nextOrdinal++, 0),
                            AsyncTokenOwner.DEAL_BODY_TASK);
                    } else {
                        // The exported declaration's reserved entry shape
                        // owns the awaited invocation record: the nested
                        // source op realizes the declaration's own entry —
                        // its body identity and single return cell stay the
                        // reservation's — exactly like the awaited direct
                        // declared-name call ({@link #lowerModuleEntryAsyncStart})
                        // and the awaited alias of the declaration. The
                        // nested op's own token is the activation-bound
                        // awaiting identity (never the entry's shared static
                        // token), and the link names the entry's canonical
                        // body-task token; the outer ADAPTER_INNER alias
                        // resolves through it. The reserved entry shape is
                        // never re-shaped into a source async start.
                        nestedOpId = ids.nextOpId(module, nextOrdinal++, 0);
                        nestedBinding = new FunctionExecutionBinding.ExternalFunction(
                            module, entryExportName, context.signature,
                            ExternalExecutionOwner.SHARED_BODY);
                        nestedReturnBoundary = null;
                        nestedSource = AsyncStartSource.EXTERNAL;
                        AsyncTokenId calleeToken = new AsyncTokenId.Canonical(
                            context.shapeOpId.id(), AsyncTokenOwner.DEAL_BODY_TASK);
                        sourceToken = new AsyncTokenId.Canonical(
                            ids.nextTokenId(module, nextOrdinal++, 0),
                            AsyncTokenOwner.DEAL_BODY_TASK);
                        nestedLink = new ExternalAsyncLink(module, entryExportName,
                            calleeToken);
                    }
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    nestedOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    nestedHostLabel = host.hostModuleId().path() + "." + host.exportName();
                    nestedSource = AsyncStartSource.HOST;
                    nestedPolicy = FailurePolicyId.ASYNC_OPERATION_HANDLE;
                    sourceToken = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.HOST_OPERATION);
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    nestedOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    nestedHostLabel = hostValue.hostModuleId().path() + ".@value";
                    nestedSource = AsyncStartSource.HOST;
                    nestedPolicy = FailurePolicyId.ASYNC_OPERATION_HANDLE;
                    sourceToken = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.HOST_OPERATION);
                }
                case FunctionExecutionBinding.ExternalFunction external -> {
                    nestedOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    Map<String, OpId> entries = calleeExternalEntries.get(external.moduleId());
                    OpId entryOp = entries == null ? null : entries.get(external.exportName());
                    if (entryOp == null) {
                        throw new ConstructUnlowered("the adapter's async external source '"
                            + external.moduleId() + "'." + external.exportName()
                            + " has no recorded EXTERNAL_ENTRY in the callee unit", call.span());
                    }
                    AsyncTokenId calleeToken = new AsyncTokenId.Canonical(entryOp.id(),
                        AsyncTokenOwner.DEAL_BODY_TASK);
                    sourceToken = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.DEAL_BODY_TASK);
                    nestedLink = new ExternalAsyncLink(external.moduleId(),
                        external.exportName(), calleeToken);
                    nestedSource = AsyncStartSource.EXTERNAL;
                }
                case FunctionExecutionBinding.AdapterBinding ignored ->
                    // An adapter-valued source is the runtime-resolved arm: its
                    // own published invocation chain selects the execution
                    // class at each invocation, so the lowering never records a
                    // statically fixed nested-source shape for it.
                    throw new IllegalStateException("the adapter-valued async source of '"
                        + calleeName + "' reaches the statically fixed source arm"
                        + " (producer defect)");

                case null -> throw new ConstructUnlowered("the adapter-over-async"
                    + " source is absent (producer defect)", call.span());
                case FunctionExecutionBinding.DynamicFunctionValue runtime ->
                    throw new ConstructUnlowered("the adapter-over-async source"
                        + " produced by op " + runtime.materializingOpId()
                        + " reaches the statically fixed arm (producer defect)",
                        call.span());
                }
            }
            AsyncTokenId outerToken = new AsyncTokenId.Alias(
                ids.nextTokenId(module, nextOrdinal++, 0), sourceToken,
                AsyncLinkKind.ADAPTER_INNER);
            // The nested source op: ELIDED_BY_ADAPTER, zero parameter
            // boundaries, operands = the leading M argument values.
            List<ValueId> leadingArgs = List.copyOf(args.subList(0, m));
            List<RuntimeDescriptor> leadingTypes = List.copyOf(argTypes.subList(0, m));
            AnchorId nestedAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin nestedOrigin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.SYNTHETIC, nestedAnchor, outerOpId);
            emit(buildOp(nestedOpId, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    runtimeResolved
                        ? new KindPayload.CallCallee.Dynamic(runtimeSourceIdentity)
                        : nestedCalleeIdentity == null
                            ? new KindPayload.CallCallee.Static(nestedBinding)
                            : new KindPayload.CallCallee.Indirect(nestedCalleeIdentity),
                    nestedSource,
                    ParameterBoundaryMode.ELIDED_BY_ADAPTER, List.of(),
                    sourceSignature.returnType(), nestedReturnBoundary, nestedHostLabel,
                    nestedLink),
                sourceToken, InternalResultType.INTERNAL_ASYNC, leadingArgs, leadingTypes,
                nestedPolicy, nestedOrigin));
            // The outer op: the adapter binding, DEAL_BODY, RUN, ×N
            // target-signature FUNCTION_PARAMETER boundaries, zero return
            // boundaries, the ADAPTER_INNER alias token.
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                SemanticOp boundary = buildChildBoundary(BoundaryKind.FUNCTION_PARAMETER,
                    target.paramTypes().get(i), args.get(i), call.span(), outerOpId);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
            SourceOrigin outerOrigin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, outerAnchor, currentParent());
            emit(buildOp(outerOpId, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(adapter), AsyncStartSource.DEAL_BODY,
                    ParameterBoundaryMode.RUN, parameterBoundaryIds, target.returnType(),
                    null, null, null),
                outerToken, InternalResultType.INTERNAL_ASYNC, args, argTypes,
                FailurePolicyId.NO_DEAL_FAILURE, outerOrigin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            return lowerAwait(outerToken, target.returnType(), awaitSpan, awaitResult);
        }

        /** The single {@code AWAIT} with its {@code ASYNC_COMPLETION} boundary child. */
        private ValueId lowerAwait(AsyncTokenId token, RuntimeDescriptor completion,
                                   Span awaitSpan, ValueId slot) {
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId awaitAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId awaitOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin awaitOrigin = new SourceOrigin(sourceId, toSourceSpan(awaitSpan),
                SourceOriginKind.USER, awaitAnchor, currentParent());
            SemanticOp completionBoundary = buildChildBoundaryWithPolicy(
                BoundaryKind.ASYNC_COMPLETION, completion, FailurePolicyId.ASYNC_COMPLETION,
                result, awaitSpan, awaitOpId);
            emit(buildOp(awaitOpId, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(token, completion, completionBoundary.opId()),
                result, completion, List.of(), List.of(),
                FailurePolicyId.NO_DEAL_FAILURE, awaitOrigin));
            emit(completionBoundary);
            // The producer rule's awaited-completion arm: the AWAIT op is
            // the op that allocates and publishes the completion value
            // identity (the ASYNC_START result is the token), so a
            // function-typed completion descriptor registers exactly one
            // dynamic materialization keyed by the AWAIT result identity.
            registerDynamicMaterialization(result, awaitOpId, completion);
            return result;
        }

        /**
         * {@code RETURN} — the carrier arm (C-D6/D13): the optional value
         * operand completes before START; the function's single
         * {@code FUNCTION_RETURN} boundary (emitted once, parented to
         * this RETURN op, checking the declared return descriptor under
         * the descriptor-kind rule) executes as the RETURN's child and
         * publishes the checked value as the enclosing call's return
         * value; the RETURN transfers and terminates its block.
         */
        private void lowerReturn(ReturnStatement statement) {
            FunctionContext context = functionStack.peek();
            if (context == null) {
                throw new ConstructUnlowered("return statement outside a function body",
                    statement.span());
            }
            ValueId value;
            boolean adaptedPosition = false;
            if (statement.expr().isPresent()) {
                ExpressionNode expr = statement.expr().get();
                RuntimeDescriptor declaredReturn = context.signature.returnType();
                if (positionAdapts(expr, declaredReturn)) {
                    // The return position's arity extension: exactly one
                    // adapter over the returned value, so the function's
                    // single FUNCTION_RETURN boundary validates the
                    // adapted value's exact target signature and the
                    // caller observes a value of the declared return
                    // type.
                    adaptedPosition = true;
                    value = lowerPositionAdapter(expr,
                        (RuntimeDescriptor.Func) declaredReturn,
                        () -> lowerExpression(expr));
                } else {
                    value = lowerExpression(expr);
                }
            } else {
                if (!(context.signature.returnType() instanceof RuntimeDescriptor.Void)) {
                    throw new ConstructUnlowered("bare return requires a void result", statement.span());
                }
                value = emitValueOp(SemanticOpKind.CONST,
                    new KindPayload.ConstPayload(ScalarValue.Undefined.INSTANCE),
                    statement.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Undefined.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId returnOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            ensureReturnBoundary(context, value, statement.span(), returnOpId);
            if (adaptedPosition) {
                recordPositionAdapterAt(value, context.returnBoundaryOpId,
                    returnBoundaryKind(context));
            }
            emit(buildOp(returnOpId, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(value, context.functionId,
                    context.invocationOpId(), context.returnBoundaryOpId),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
            terminateBlock();
        }

        /**
         * Emits the function's single return boundary op exactly once
         * (parented to the given RETURN op; the declared return
         * descriptor under the descriptor-kind rule). The boundary kind
         * follows the function's single invocation shape (the closed
         * boundary-assignment table's per-shape cell):
         * {@code FUNCTION_RETURN} under a CALL/ASYNC_START body task,
         * {@code DEAL_TO_HOST} under a CALLBACK_INVOKE,
         * {@code EXTERNAL_RETURN} under a sync EXTERNAL_ENTRY, and
         * {@code FUNCTION_RETURN} under an async EXTERNAL_ENTRY (the
         * callee task's boundary).
         */
        private void ensureReturnBoundary(FunctionContext context, ValueId value, Span span,
                                          OpId returnOpId) {
            if (context.returnBoundaryEmitted) {
                return;
            }
            context.returnBoundaryEmitted = true;
            context.returnCellReturnParented = true;
            RuntimeDescriptor returnType = context.signature.returnType();
            FailurePolicyId policy = descriptorKindPolicy(returnType);
            BoundaryKind kind = returnBoundaryKind(context);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, returnOpId);
            emit(buildOp(context.returnBoundaryOpId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, returnType,
                    value, new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, policy, origin));
        }

        /** The function's single return boundary kind per its invocation shape. */
        private BoundaryKind returnBoundaryKind(FunctionContext context) {
            if (!e7Calls || context.shape == null
                    || context.shape == InvocationShape.SOURCE_CALL
                    || context.shape == InvocationShape.SOURCE_ASYNC) {
                return BoundaryKind.FUNCTION_RETURN;
            }
            return switch (context.shape) {
                case CALLBACK_SHAPE -> BoundaryKind.DEAL_TO_HOST;
                case EXTERNAL_ENTRY_SHAPE ->
                    context.signature.isAsync() ? BoundaryKind.FUNCTION_RETURN
                        : BoundaryKind.EXTERNAL_RETURN;
                default -> BoundaryKind.FUNCTION_RETURN;
            };
        }

        /**
         * The implicit trailing return of an unterminated null-returning
         * function body: one {@code CONST null} production, the single
         * {@code FUNCTION_RETURN} boundary, and the {@code RETURN}
         * transfer ("a function with return type null returns the null
         * value through the boundary").
         */
        private void lowerImplicitReturn(FunctionContext context, Span span) {
            ValueId value = emitValueOp(SemanticOpKind.CONST,
                new KindPayload.ConstPayload(ScalarValue.Undefined.INSTANCE), span,
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Undefined.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId returnOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, currentParent());
            ensureReturnBoundary(context, value, span, returnOpId);
            emit(buildOp(returnOpId, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(value, context.functionId,
                    context.invocationOpId(), context.returnBoundaryOpId),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
            terminateBlock();
        }

        private void lowerFullStatements(List<StatementNode> statements,
                                         boolean moduleLevel) {

            Map<FunctionDeclaration, List<FunctionDeclaration>> groupMembership =
                groupCore ? groupMembership(statements) : Map.of();
            for (StatementNode statement : statements) {
                if (statement instanceof VariableDeclaration decl) {
                    lowerBindingVarDecl(decl);
                    continue;
                }
                if (statement instanceof FunctionDeclaration function) {
                    if (skipGroupedFunctionDecl(function, groupMembership, moduleLevel)) {
                        continue;
                    }
                    lowerBindingFunctionDecl(function, moduleLevel);
                    continue;
                }
                if (statement instanceof ClassDeclaration classDeclaration) {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeStatement(statement),
                            statement.span());
                    }
                    lowerClassDeclaration(classDeclaration, false);
                    continue;
                }
                if (statement instanceof ExportDeclaration exportDeclaration) {
                    if (!e7Calls) {
                        throw new ConstructUnlowered(describeStatement(statement),
                            statement.span());
                    }
                    // The E7 surface unwraps exported function
                    // declarations (the declaration walks like any
                    // function body); the entry/callback/publish records
                    // emit at the unit terminal from the checked export
                    // facts — no position op exists for the export
                    // wrapper itself. An exported class declaration walks
                    // through the class arm (class-core mode).
                    if (exportDeclaration.declaration()
                            instanceof FunctionDeclaration exportedFunction) {
                        if (skipGroupedFunctionDecl(exportedFunction,
                                groupMembership, moduleLevel)) {
                            continue;
                        }
                        lowerBindingFunctionDecl(exportedFunction, moduleLevel);
                    } else if (exportDeclaration.declaration()
                            instanceof ClassDeclaration exportedClass) {
                        if (!classCore) {
                            throw new ConstructUnlowered(
                                describeStatement(statement), statement.span());
                        }
                        lowerClassDeclaration(exportedClass, true);
                    }
                    continue;
                }
                if (statement instanceof ImportDeclaration importDeclaration) {
                    // The alias ALLOC was hoisted to module-init top (B1);
                    // the carrier slice produces the pinned MODULE_IMPORT
                    // op (the load-once initialization record) for the
                    // resolved import target — the stdlib console module
                    // needs no further initialization for the tail. The
                    // payload names the import's ordered alias cells:
                    // the completion is the pinned initializing write of
                    // every named cell (an import alias never carries a
                    // BINDING_INIT).
                    ResolvedImport importFact = importByAlias(importDeclaration.alias());
                    if (importFact == null) {
                        throw new ConstructUnlowered("import '" + importDeclaration.alias()
                            + "' without a resolved import fact (a missing checker fact "
                            + "is a producer defect)", importDeclaration.span());
                    }
                    BindingId aliasCell = importAliasCells.get(importDeclaration.alias());
                    if (aliasCell == null) {
                        throw new ConstructUnlowered("import '" + importDeclaration.alias()
                            + "' without a hoisted alias cell (a missing hoist fact "
                            + "is a producer defect)", importDeclaration.span());
                    }
                    emitNullOp(SemanticOpKind.MODULE_IMPORT,
                        new KindPayload.ModuleImportPayload(importFact.modulePath(),
                            importFact.resolvedModuleId(), moduleImportKindOf(importFact),
                            List.of(aliasCell)),
                        importDeclaration.span(), FailurePolicyId.NO_DEAL_FAILURE,
                        SourceOriginKind.USER, currentParent());
                    continue;
                }
                if (statement instanceof ReturnStatement returnStatement) {
                    lowerReturn(returnStatement);
                    continue;
                }
                if (statement instanceof ForOfStatement forOf) {
                    lowerForOfStatement(forOf);
                    continue;
                }
                if (statement instanceof DeleteStatement delete) {
                    lowerDelete(delete);
                    continue;
                }
                if (statement instanceof Block block) {
                    // A nested block is its own lexical scope: pushing the
                    // block's checker scope node keeps declared-type
                    // resolution (the boundary descriptors of shadowing
                    // declarations) inside the block, never against the
                    // enclosing scope, and its binding frame keeps the
                    // block's declarations out of the enclosing binding
                    // environment after the block exits.
                    checkerScopeNodes.push(block);
                    pushBindingFrame();
                    try {
                        statementWalk.walk(block.statements(), false);
                    } finally {
                        popBindingFrame();
                        checkerScopeNodes.pop();
                    }
                    continue;
                }
                if (statement instanceof IfStatement ifStatement) {
                    lowerIfStatement(ifStatement);
                    continue;
                }
                if (statement instanceof WhileStatement whileStatement) {
                    lowerWhileStatement(whileStatement);
                    continue;
                }

                if (statement instanceof TryStatement tryStatement) {
                    lowerTryCatch(tryStatement);
                    continue;
                }
                if (statement instanceof ThrowStatement throwStatement) {
                    lowerThrow(throwStatement);
                    continue;
                }
                if (statement instanceof BreakStatement breakStatement) {
                    lowerBreak(breakStatement);
                    continue;
                }
                if (statement instanceof ContinueStatement continueStatement) {
                    lowerContinue(continueStatement);
                    continue;
                }
                if (statement instanceof ExpressionStatement expressionStatement) {
                    lowerDiscard(expressionStatement);
                    continue;
                }
                throw new ConstructUnlowered(describeStatement(statement), statement.span());
            }
        }

        /**
         * Installs the E7 call-machine facts and activates the E7
         * surface: the module's checked exports, the callee modules'
         * recorded {@code EXTERNAL_ENTRY} op ids, and the exported
         * callback function names.
         */
        void setE7Facts(List<ExportInterface> exports,
                        Map<ModuleId, Map<String, OpId>> calleeEntries,
                        Set<String> callbacks) {
            if (!fullProgram) {
                throw new IllegalStateException("the E7 surface requires the "
                    + "full-program session (producer defect)");
            }
            this.e7Calls = true;
            this.moduleExports = List.copyOf(exports);
            this.calleeExternalEntries = Map.copyOf(calleeEntries);
            this.callbackExports = Set.copyOf(callbacks);
        }

        /**
         * The declared-parameter-annotation index of the one lowering
         * (canonical failure-projection authority P3): the production entry
         * supplies it before the module walk, so a declaration-owned
         * parameter boundary reads the callee's declared type-annotation
         * span instead of the call site.
         */
        void setDeclaredParameterAnnotations(
                Map<ModuleId, DeclaredParameterAnnotations> index) {
            this.declaredParameterAnnotations = index;
        }

        /**
         * The declared parameter type-annotation span of one
         * declaration-owned parameter boundary (P3): the callee's
         * declaration, selected by module, declared function name, and
         * declared arity. A declared callee with no recorded annotation
         * fails closed — never a fallback to the call span.
         */
        private DeclaredParameterOrigin declaredParameterSpan(ModuleId calleeModule,
                                                              String functionName,
                                                              int arity,
                                                              int parameterIndex) {
            if (declaredParameterAnnotations == null) {
                return null;
            }
            DeclaredParameterAnnotations moduleIndex =
                declaredParameterAnnotations.get(calleeModule);
            if (moduleIndex == null) {
                // A callee outside the checked project's implementation
                // closure (a host declaration module): the host
                // cells' own convention applies, never a fabricated
                // annotation. The fail-closed rule below applies to the
                // closure's own declared callees.
                return null;
            }
            List<List<Span>> candidates = moduleIndex.byFunctionName().get(functionName);
            if (candidates == null) {
                throw new ConstructUnlowered("the declared callee '" + functionName
                    + "' of module '" + calleeModule.path() + "' has no recorded parameter"
                    + " annotation in the one lowering's declaration index (a declared"
                    + " callee without a recorded annotation is a fail-closed producer"
                    + " defect, never a fallback span)");
            }
            for (List<Span> declared : candidates) {
                if (declared.size() == arity && parameterIndex < declared.size()) {
                    Span span = declared.get(parameterIndex);
                    if (span == null) {
                        throw new ConstructUnlowered("the declared parameter "
                            + (parameterIndex + 1) + " of '" + functionName
                            + "' of module '" + calleeModule.path()
                            + "' has no recorded type annotation in the one"
                            + " lowering's declaration index (a declared callee without"
                            + " a recorded annotation is a fail-closed producer"
                            + " defect, never a fallback span)");
                    }
                    return new DeclaredParameterOrigin(moduleIndex.sourceId(), span);
                }
            }
            throw new ConstructUnlowered("the declared callee '" + functionName
                + "' of module '" + calleeModule.path() + "' has no declaration of arity "
                + arity + " in the one lowering's declaration index (fail-closed producer"
                + " defect, never a fallback span)");
        }

        /**
         * The declaration-owned origin of one declared parameter cell (P3):
         * the callee's declared annotation span in the callee's own file,
         * or {@code null} when no declaration-owned origin applies (a
         * callee outside the closure).
         */
        private DeclaredParameterOrigin declaredParameterOrigin(ModuleId calleeModule,
                                                                 String functionName,
                                                                 int arity,
                                                                 int parameterIndex) {
            return declaredParameterSpan(calleeModule, functionName, arity, parameterIndex);
        }

        /** The module's recorded {@code EXTERNAL_ENTRY} op ids by export name. */
        Map<String, OpId> recordedEntries() {
            return Map.copyOf(recordedEntries);
        }

        /** The module's recorded {@code CALLBACK_INVOKE} op ids by export name. */
        Map<String, OpId> recordedCallbacks() {
            return Map.copyOf(recordedCallbacks);
        }

        /** True iff the module's checked export facts name the given export. */
        private boolean isExported(String name) {
            for (ExportInterface export : moduleExports) {
                if (export.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        private void emitE7Terminals() {
            for (Map.Entry<String, FunctionContext> entry
                    : moduleFunctionContexts.entrySet()) {
                String name = entry.getKey();
                FunctionContext context = entry.getValue();
                if (!isExported(name)) {
                    continue;
                }
                emitExportPublish(name, context);
                if ("main".equals(name)) {
                    // The ENTRY_INVOKE delegation owns main's single
                    // invocation shape; no EXTERNAL_ENTRY/CALLBACK_INVOKE
                    // record for the entry function.
                    continue;
                }
                if (callbackExports.contains(name)) {
                    emitCallbackInvoke(name, context);
                } else {
                    emitExternalEntry(name, context);
                }
            }
            emitEntryInvokeDelegation();
            emitNeverReturningBodyBoundaries();
        }

        private void emitNeverReturningBodyBoundaries() {
            List<FunctionContext> pending = new ArrayList<>();
            for (FunctionContext context : contextsByFunctionId.values()) {
                if (!context.returnBoundaryEmitted && context.shapeOpId != null) {
                    pending.add(context);
                }
            }
            java.util.Collections.sort(pending,
                java.util.Comparator.comparingLong(c -> c.functionId.id()));
            for (FunctionContext context : pending) {
                emitNeverReturningBodyBoundary(context);
            }
        }

        /** One never-returning body's return cell, parented to its invocation op. */
        private void emitNeverReturningBodyBoundary(FunctionContext context) {
            context.returnBoundaryEmitted = true;
            RuntimeDescriptor returnType = context.signature.returnType();
            FailurePolicyId policy = descriptorKindPolicy(returnType);
            BoundaryKind kind = returnBoundaryKind(context);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            ValueId input = ids.nextValueId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, anchor, context.shapeOpId);
            emit(buildOp(context.returnBoundaryOpId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(kind, returnType, input,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, policy, origin));
        }

        private void finalizeInvocationIdentities() {
            Map<OpId, Integer> occurrences = new LinkedHashMap<>();
            for (SemanticOp op : ops) {
                occurrences.merge(op.opId(), 1, Integer::sum);
            }
            for (FunctionContext context : contextsByFunctionId.values()) {
                OpId identity = context.invocationOpId();
                int count = occurrences.getOrDefault(identity, 0);
                if (count == 1) {
                    continue;
                }
                if (count > 1) {
                    throw new ConstructUnlowered("function id "
                        + context.functionId.id() + " carries " + count
                        + " emitted ops under its reserved invocation identity "
                        + identity + " (a body's identity names exactly one"
                        + " invocation op)");
                }
                OpId materialized = materializeCreationIdentity(context);
                if (occurrences.getOrDefault(materialized, 0) != 1) {
                    throw new ConstructUnlowered("function id "
                        + context.functionId.id() + " carries no emitted op under"
                        + " its materialized invocation identity " + materialized
                        + " (producer defect)");
                }
            }
        }

        private OpId materializeCreationIdentity(FunctionContext context) {
            for (int i = 0; i < ops.size(); i++) {
                SemanticOp op = ops.get(i);
                if (op.kind() == SemanticOpKind.CLOSURE_NEW
                        && op.payload() instanceof KindPayload.ClosureNewPayload closure
                        && closure.function().equals(context.functionId)) {
                    rekeyReturnIdentity(context.functionId, op.opId());
                    return op.opId();
                }
                if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT
                        && op.payload()
                            instanceof KindPayload.RecursiveGroupInitPayload group
                        && group.functions().contains(context.functionId)) {
                    rekeyReturnIdentity(context.functionId, op.opId());
                    return op.opId();
                }
            }
            throw new ConstructUnlowered("function id " + context.functionId.id()
                + " is never called and carries no function-value creation op of its"
                + " own (the CLOSURE_NEW of its function id or the"
                + " RECURSIVE_GROUP_INIT publishing it); a lowered body always"
                + " carries exactly one (producer defect)");
        }

        private void rekeyReturnIdentity(FunctionId function, OpId identity) {
            for (int j = 0; j < ops.size(); j++) {
                SemanticOp candidate = ops.get(j);
                if (candidate.kind() != SemanticOpKind.RETURN
                        || !(candidate.payload()
                            instanceof KindPayload.ReturnPayload returned)
                        || !returned.function().equals(function)
                        || returned.enclosingInvocationOpId().equals(identity)) {
                    continue;
                }
                KindPayload.ReturnPayload rewritten =
                    new KindPayload.ReturnPayload(returned.value(),
                        returned.function(), identity, returned.returnBoundaryOpId());
                ops.set(j, buildOp(candidate.opId(), candidate.kind(), rewritten,
                    candidate.result(), candidate.resultType(),
                    candidate.operands(), candidate.operandTypes(),
                    candidate.failurePolicy(), candidate.origin()));
            }
        }

        /**
         * {@code EXPORT_PUBLISH} — the atomic publication record of one
         * exported function: the checked {@code MODULE_EXPORT} boundary
         * (descriptor-kind rule) precedes the publication of the
         * function value.
         */
        private void emitExportPublish(String name, FunctionContext context) {
            ValueId functionValue = moduleFunctionIdentities.get(name);
            if (functionValue == null) {
                throw new IllegalStateException("the exported function identity of '"
                    + name + "' was not recorded at hoist (producer defect)");
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId publishOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin publishOrigin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, anchor, null);
            emit(buildOp(publishOpId, SemanticOpKind.EXPORT_PUBLISH,
                new KindPayload.ExportPublishPayload(module, name, context.signature,
                    functionValue),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, publishOrigin));
            emit(buildChildBoundary(BoundaryKind.MODULE_EXPORT,
                context.signature, functionValue, programSpan, publishOpId));
        }

        /**
         * {@code EXTERNAL_ENTRY} — the callee-unit invocation record of a
         * function callable across a shared/shadow edge: one static op
         * per exported function. Sync: the body's {@code RETURN} runs the
         * single {@code EXTERNAL_RETURN} boundary; async: the entry
         * creates the canonical token and the body task, whose
         * {@code RETURN} runs the single {@code FUNCTION_RETURN}
         * boundary. The entry runs no parameter boundaries (the caller's
         * {@code EXTERNAL_PARAMETER} boundaries run exactly once).
         */
        private void emitExternalEntry(String name, FunctionContext context) {
            OpId entryOpId = context.shapeOpId;
            RuntimeDescriptor completion = context.signature.isAsync()
                ? context.signature.returnType() : null;
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, anchor, null);
            emitUnattached(buildOp(entryOpId, SemanticOpKind.EXTERNAL_ENTRY,
                new KindPayload.ExternalEntryPayload(name, context.functionId,
                    context.signature, context.signature.isAsync(),
                    context.returnBoundaryOpId, completion),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
            recordedEntries.put(name, entryOpId);
        }

        /**
         * {@code CALLBACK_INVOKE} — the host-driven top-level invocation
         * record of a statically resolved callback export: the export's
         * function identity, its descriptor, the {@code HOST_TO_DEAL}
         * parameter boundaries (descriptor-kind rule, one per declared
         * parameter in one-based order), and the single
         * {@code DEAL_TO_HOST} return boundary (run by the executed
         * body's {@code RETURN}).
         */
        private void emitCallbackInvoke(String name, FunctionContext context) {
            if (context.signature.isAsync()) {
                throw new ConstructUnlowered("callback export '" + name
                    + "' is async (a CALLBACK_INVOKE is sync only — the scenario "
                    + "schema pins FIXTURE_INVALID for async callbacks)");
            }
            OpId callbackOpId = context.shapeOpId;
            ValueId functionValue = moduleFunctionIdentities.get(name);
            if (functionValue == null) {
                throw new IllegalStateException("the exported function identity of '"
                    + name + "' was not recorded at hoist (producer defect)");
            }
            List<SemanticOp> parameterBoundaryOps = new ArrayList<>();
            List<OpId> parameterBoundaryIds = new ArrayList<>();
            for (int i = 0; i < context.signature.paramTypes().size(); i++) {
                // The callback argument slot: the scenario host fills the
                // slot with the host-supplied argument before the
                // boundary executes.
                ValueId argSlot = ids.nextValueId(module, nextOrdinal++, 0);
                SemanticOp boundary = buildChildBoundary(BoundaryKind.HOST_TO_DEAL,
                    context.signature.paramTypes().get(i), argSlot, programSpan,
                    callbackOpId);
                parameterBoundaryOps.add(boundary);
                parameterBoundaryIds.add(boundary.opId());
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, anchor, null);
            emitUnattached(buildOp(callbackOpId, SemanticOpKind.CALLBACK_INVOKE,
                new KindPayload.CallbackInvokePayload(functionValue, context.signature,
                    parameterBoundaryIds, context.returnBoundaryOpId),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp boundary : parameterBoundaryOps) {
                emit(boundary);
            }
            recordedCallbacks.put(name, callbackOpId);
        }

        /**
         * The shared entry-delegation precondition and reservation: main
         * absent returns null; the pinned signature gate and the
         * single-call-site gate fail closed; the delegation reserves
         * main's call site. {@code delegatedFrom} is the caller's
         * message clause naming the delegation site.
         */
        private FunctionContext entryMainForDelegation(String delegatedFrom) {
            FunctionContext main = moduleFunctionContexts.get("main");
            if (main == null) {
                return null;
            }
            if (!main.signature.paramTypes().isEmpty()
                    || !(main.signature.returnType() instanceof RuntimeDescriptor.Null)
                    || main.signature.isAsync()) {
                throw new ConstructUnlowered("entry main must have the pinned non-async "
                    + "signature '(): null' (the orchestrator's E2011 pins this)");
            }
            if (main.callSiteUsed) {
                throw new ConstructUnlowered("entry main is called from source and "
                    + "delegated from " + delegatedFrom);
            }
            main.callSiteUsed = true;
            return main;
        }

        /**
         * Emits main's reserved call-site {@code CALL(DIRECT main)} with
         * the caller's origin (the entry op's child or the module-init
         * tail).
         */
        private void emitEntryMainCallOp(FunctionContext main, ValueId result,
                                         SourceOrigin origin) {
            emit(buildOp(main.callSiteOpId, SemanticOpKind.CALL,
                new KindPayload.CallPayload(CallMode.DIRECT,
                    new KindPayload.CallCallee.Static(
                        new FunctionExecutionBinding.LoweredBody(main.functionId,
                            main.bodyBlock)),
                    main.signature, List.of(), main.returnBoundaryOpId, null,
                    main.bodyBlock, null),
                result, ContainerPayloadDescriptors.resultDescriptorOf(Type.Null.INSTANCE),
                List.of(), List.of(), FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        /**
         * {@code ENTRY_INVOKE} — the E7 entry delegation: exactly one
         * {@code CALL(DIRECT main)} (parented to the entry op, executed
         * once by the entry arm) and the entry terminal exits the
         * program; no separate boundary.
         */
        private void emitEntryInvokeDelegation() {
            FunctionContext main = entryMainForDelegation(
                "the entry (the ENTRY_INVOKE delegation is main's call site)");
            if (main == null) {
                return;
            }
            main.assignShape(InvocationShape.SOURCE_CALL, main.callSiteOpId);
            AnchorId entryAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId entryOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin entryOrigin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, entryAnchor, null);
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId callAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin callOrigin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, callAnchor, entryOpId);
            emit(buildOp(entryOpId, SemanticOpKind.ENTRY_INVOKE,
                new KindPayload.EntryInvokePayload(module, main.functionId),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, entryOrigin));
            emitEntryMainCallOp(main, result, callOrigin);
        }

        private void emitEntryMainCall() {
            FunctionContext main = entryMainForDelegation(
                "module init (the carrier slice admits exactly one CALL site"
                    + " per callee — the entry delegation is main's call site)");
            if (main == null) {
                return;
            }
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(programSpan),
                SourceOriginKind.SYNTHETIC, anchor, currentParent());
            emitEntryMainCallOp(main, result, origin);
            emitNullOp(SemanticOpKind.DISCARD, new KindPayload.DiscardPayload(result),
                programSpan, FailurePolicyId.NO_DEAL_FAILURE, SourceOriginKind.SYNTHETIC,
                null);
        }

        // ---------------------------------------------------------------------
        // Statement arms (the E5 positionable window)
        // ---------------------------------------------------------------------

        private void lowerStatements(List<StatementNode> statements) {
            for (StatementNode statement : statements) {
                if (statement instanceof ForOfStatement forOf) {
                    lowerForOfStatement(forOf);
                    continue;
                }
                if (statement instanceof DeleteStatement delete) {
                    lowerDelete(delete);
                    continue;
                }
                if (statement instanceof Block block) {
                    // A nested block is its own lexical scope: pushing the
                    // block's checker scope node keeps declared-type
                    // resolution (the boundary descriptors of shadowing
                    // declarations) inside the block, never against the
                    // enclosing scope.
                    checkerScopeNodes.push(block);
                    statementWalk.walk(block.statements(), false);
                    checkerScopeNodes.pop();
                    continue;
                }
                if (statement instanceof IfStatement ifStatement) {
                    lowerIfStatement(ifStatement);
                    continue;
                }
                if (statement instanceof WhileStatement whileStatement) {
                    lowerWhileStatement(whileStatement);
                    continue;
                }

                if (statement instanceof TryStatement tryStatement) {
                    lowerTryCatch(tryStatement);
                    continue;
                }
                if (statement instanceof ThrowStatement throwStatement) {
                    lowerThrow(throwStatement);
                    continue;
                }
                if (statement instanceof BreakStatement breakStatement) {
                    lowerBreak(breakStatement);
                    continue;
                }
                if (statement instanceof ContinueStatement continueStatement) {
                    lowerContinue(continueStatement);
                    continue;
                }
                if (statement instanceof ExpressionStatement expressionStatement) {
                    lowerDiscard(expressionStatement);
                    continue;
                }
                throw new ConstructUnlowered(describeStatement(statement), statement.span());
            }
        }

        // ---------------------------------------------------------------------
        // The E5 control-flow arms (control-flow-structures C-D3..C-D8)
        // ---------------------------------------------------------------------

        /**
         * Walks one control-flow body block with the block's own checker
         * scope pushed: declared-type resolution of shadowing declarations
         * inside the body must never resolve against the enclosing scope
         * (the boundary descriptor of a block-local declaration is the
         * block-local declared type).
         */
        private void walkScopedBody(Block body) {
            checkerScopeNodes.push(body);
            pushBindingFrame();
            try {
                statementWalk.walk(body.statements(), false);
            } finally {
                popBindingFrame();
                checkerScopeNodes.pop();
            }
        }

        /**
         * {@code BRANCH(IF)} — the if-statement arm (C-D3): the
         * condition's producing ops complete in the enclosing block
         * before the {@code BRANCH} op; exactly one of
         * {@code selectedBlock}/{@code alternateBlock} executes; the
         * {@code else} branch lowers inside {@code alternateBlock} (an
         * {@code else if} chain nests its {@code BRANCH} op there); an
         * absent {@code else} produces {@code alternateBlock = null};
         * SUCCESS publishes no result. Block ops record the
         * {@code BRANCH} as {@code parentOpId}. The closed terminator
         * analysis composes the enclosing block after both sub-block
         * walks: an {@code if}/{@code else} whose both branches cannot
         * complete normally terminates it, while a break/continue path of
         * either branch stays recorded as the composite's transfer facet
         * (so a later terminator in the same block classifies it
         * {@code TRANSFER}); an absent {@code else} can always complete
         * normally (the fall-through path) but still records the selected
         * branch's transfer facet. An {@code else if} chain composes
         * through the nested {@code BRANCH}'s marking of
         * {@code alternateBlock}.
         */
        private void lowerIfStatement(IfStatement statement) {
            ValueId condition = emitNarrowedConditionCheck(statement.condition(),
                lowerExpression(statement.condition()));
            BlockId selectedBlock = allocateBlock();
            boolean hasElse = statement.elseBranch().isPresent();
            BlockId alternateBlock = hasElse ? allocateBlock() : null;
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.BRANCH,
                new KindPayload.BranchPayload(ControlSelector.IF, condition, selectedBlock,
                    alternateBlock),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
            pushBlockParent(opId);
            pushBlock(selectedBlock);
            try {
                walkScopedBody(statement.thenBlock());
            } finally {
                popBlock();
            }
            if (hasElse) {
                pushBlock(alternateBlock);
                try {
                    switch (statement.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            lowerIfStatement(left.value());
                        case Either.Right<IfStatement, Block> right ->
                            walkScopedBody(right.value());
                    }
                } finally {
                    popBlock();
                }
                // The composite composition (D3): the enclosing block can
                // complete normally exactly when one branch can, and a
                // break/continue path of either branch escapes into it —
                // preserved even when one branch is open, so a following
                // terminator renders TRANSFER, never RETURN_OR_THROW.
                markCompositeExit(currentBlock(), selectedBlock, alternateBlock);
            } else {
                // The absent-else composition (D3): the fall-through path
                // completes normally, but the selected branch's
                // break/continue path still escapes into the enclosing
                // block.
                markStatementExit(currentBlock(), true,
                    exitOf(selectedBlock).transfers());
            }
            popBlockParent();
        }

        /**
         * {@code LOOP(WHILE)} — the while-statement arm (C-D4): the
         * per-iteration condition block is {@code initBlock} (the
         * condition's producing ops are explicit members of
         * {@code initBlock}, never an inferred subgraph);
         * {@code updateBlock = null}; execution repeats { execute
         * {@code initBlock}; evaluate the condition value; if false →
         * SUCCESS; execute {@code bodyBlock} }. The {@code LOOP} op
         * precedes its child block ops in the unit list (payload order:
         * init, body). No speculative body execution; conditions
         * re-evaluate per iteration. Block ops record the {@code LOOP}
         * as {@code parentOpId}.
         */
        private void lowerWhileStatement(WhileStatement statement) {
            BlockId initBlock = allocateBlock();
            BlockId bodyBlock = allocateBlock();
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            int mark = emitTarget().size();
            pushBlockParent(opId);
            pushBlock(initBlock);
            ValueId condition;
            try {
                condition = emitNarrowedConditionCheck(statement.condition(),
                    lowerExpression(statement.condition()));
            } finally {
                popBlock();
            }
            pushBlock(bodyBlock);
            pushLoopTarget(opId);
            try {
                walkScopedBody(statement.body());
            } finally {
                popLoopTarget();
                popBlock();
            }
            // The composite marking (D3): a literal-true loop whose body
            // cannot complete normally and has no break/continue path
            // exits only by return/throw, so it cannot complete normally
            // either. A TRANSFER body leaves the loop able to exit
            // normally (the enclosing block stays OPEN).
            if (isLiteralTrue(statement.condition())
                    && exitStateOf(bodyBlock) == BlockExitState.RETURN_OR_THROW) {
                markExit(currentBlock(), BlockExitState.RETURN_OR_THROW);
            }
            popBlockParent();
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emitAt(mark, buildOp(opId, SemanticOpKind.LOOP,
                new KindPayload.LoopPayload(ControlSelector.WHILE, initBlock, condition,
                    bodyBlock, null),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }


        private void lowerTryCatch(TryStatement statement) {
            BlockId tryBlock = allocateBlock();
            BlockId catchBlock = allocateBlock();
            BindingId catchBinding = ids.nextBindingId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            int mark = emitTarget().size();
            pushBlockParent(opId);
            pushBlock(tryBlock);
            try {
                walkScopedBody(statement.tryBlock());
            } finally {
                popBlock();
            }
            catchFrames.add(0, new CatchFrame(statement.catchVar(), catchBinding,
                bindingScopes.size()));
            // The catch variable is an ordinary mutable lexical binding of
            // the catch block's scope (docs/spec-v1.5.md §try / catch): it
            // enters the walk's binding environment in its own frame so a
            // direct assignment resolves its cell and a closure created in
            // the catch body registers it as a capture — the catch entry's
            // BINDING_ALLOC is the incarnation a capture resolves to, and
            // the capture-driven cell kind (B2) makes a captured catch
            // binding a shared cell whose fresh per-entry cell is the
            // TRY_CATCH arm's initializing write.
            BindingCoreIncarnation catchIncarnation = new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, catchBlock, BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC, false);
            if (bindingCore) {
                pushBindingFrame();
                registerBinding(statement.catchVar(), catchBinding, catchIncarnation);
            }
            pushBlock(catchBlock);
            try {
                emitNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(catchBinding, catchBlock, true,
                        cellKinds.cellKindOf(catchIncarnation), INITIAL_LOOP_GENERATION),
                    statement.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, currentParent());
                walkScopedBody(statement.catchBlock());
            } finally {
                popBlock();
                if (bindingCore) {
                    popBindingFrame();
                }
                catchFrames.remove(0);
            }
            // The composite composition (D3): the enclosing block can
            // complete normally exactly when the protected block or the
            // catch block can, and a break/continue path of either escapes
            // into it (the TRY_CATCH consumes no transfer — the landed
            // transfer protocol re-raises the marker through the protected
            // boundary).
            markCompositeExit(currentBlock(), tryBlock, catchBlock);
            popBlockParent();
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emitAt(mark, buildOp(opId, SemanticOpKind.TRY_CATCH,
                new KindPayload.TryCatchPayload(tryBlock, catchBinding, catchBlock),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        /**
         * {@code THROW} — the throw arm (C-D6): the operand completes
         * before START; the op never succeeds; policy
         * {@code THROW_TRANSFER} — code/message from the supplied
         * {@code Error} value's fields, origin = the THROW origin,
         * frames active; control transfers to the nearest enclosing
         * {@code TRY_CATCH}, else the error escapes as the host-visible
         * {@code DEALRuntimeError}. {@code THROW} terminates its block
         * (its exit state is closed for the implicit-return analysis).
         *
         * <p>The operand lowers through {@link #lowerOperand(ExpressionNode)}:
         * a null-narrowing-refined identifier read is a consumed value like
         * the arithmetic operand, condition, index receiver/key, table member
         * receiver, {@code .length} receiver, for-of iterable and {@code has}
         * receiver positions, so the {@code NARROWED_READ} admission validates
         * the read at its own origin before the throw machinery dereferences
         * the {@code Error} value's transport. docs/spec-v1.3.md Nullable
         * semantics keeps calls non-invalidating on purpose, so a capturing
         * closure call can leave the binding null while the refinement claim
         * still holds; without the admission the cleared binding reaches the
         * throw path and every backend dereferences the cleared value (a
         * native nil index error on LuaJIT, a host null dereference on the
         * JVM), escaping the enclosing DEAL {@code try}. The admission makes
         * the failure the standard typed-boundary projection (E8001 with the
         * class descriptor's expected/actual tuple) at the read, so it stays
         * a catchable DEAL error and no backend consumes the cleared value.</p>
         */
        private void lowerThrow(ThrowStatement statement) {
            ValueId errorValue = lowerOperand(statement.expr());
            emitNullOp(SemanticOpKind.THROW,
                new KindPayload.ThrowPayload(errorValue), statement.span(),
                FailurePolicyId.THROW_TRANSFER, SourceOriginKind.USER, currentParent());
            terminateBlock();
        }

        /**
         * {@code BREAK} — the break arm (C-D7): payload {@code loopId} =
         * the innermost enclosing loop op ({@code LOOP} or
         * {@code FOR_EACH}) recorded by the lowerer; BREAK exits the
         * target loop; effects completed before the transfer remain.
         * The checker's E2000 pins source-level loop placement, so a
         * missing target is a producer defect ({@code
         * CONSTRUCT_UNLOWERED}), never an invented target and never a
         * silent fallthrough. {@code BREAK} terminates its block.
         */
        private void lowerBreak(BreakStatement statement) {
            OpId target = loopTargets.peek();
            if (target == null) {
                throw new ConstructUnlowered("break without an enclosing loop target (the "
                    + "checker's E2000 pins source-level loop placement — a missing "
                    + "checker fact is a producer defect)", statement.span());
            }
            emitNullOp(SemanticOpKind.BREAK,
                new KindPayload.BreakPayload(target), statement.span(),
                FailurePolicyId.NO_DEAL_FAILURE, SourceOriginKind.USER, currentParent());
            transferBlock();
        }

        /**
         * {@code CONTINUE} — the continue arm (C-D7): payload
         * {@code loopId} = the innermost enclosing loop op; CONTINUE
         * proceeds to the target loop's next iteration — FOR: execute
         * {@code updateBlock} then re-test; WHILE: execute the condition
         * block ({@code initBlock}) then re-test; FOR_EACH: next slot
         * index. Transfer across an enclosing {@code TRY_CATCH} boundary
         * is legal. A missing target is a producer defect (see
         * {@link #lowerBreak}). {@code CONTINUE} terminates its block.
         */
        private void lowerContinue(ContinueStatement statement) {
            OpId target = loopTargets.peek();
            if (target == null) {
                throw new ConstructUnlowered("continue without an enclosing loop target (the "
                    + "checker's E2000 pins source-level loop placement — a missing "
                    + "checker fact is a producer defect)", statement.span());
            }
            emitNullOp(SemanticOpKind.CONTINUE,
                new KindPayload.ContinuePayload(target), statement.span(),
                FailurePolicyId.NO_DEAL_FAILURE, SourceOriginKind.USER, currentParent());
            transferBlock();
        }

        /**
         * {@code DISCARD} — the expression-statement arm (C-D8): the
         * value's producing ops already completed before the op; START →
         * SUCCESS with no result; origin kind {@code SYNTHETIC} — the
         * intentional discard is audited in the op stream and traces,
         * never inferred away.
         */
        private void lowerDiscard(ExpressionStatement statement) {
            ValueId value = lowerExpression(statement.expr());
            emitNullOp(SemanticOpKind.DISCARD,
                new KindPayload.DiscardPayload(value), statement.span(),
                FailurePolicyId.NO_DEAL_FAILURE, SourceOriginKind.SYNTHETIC,
                currentParent());
        }

        // ---------------------------------------------------------------------
        // The per-construct arms (D1)
        // ---------------------------------------------------------------------

        private ValueId lowerConst(LiteralExpr literal) {
            return lowerConst(literal, null);
        }

        private ValueId lowerConst(LiteralExpr literal, ValueId slot) {
            Type type = checkedType(literal);
            ScalarValue scalar = switch (literal.value()) {
                case LiteralValue.NullLiteral ignored -> ScalarValue.Null.INSTANCE;
                case LiteralValue.BooleanLiteral bool -> new ScalarValue.Boolean(bool.value());

                case LiteralValue.NumberLiteral number ->
                    new ScalarValue.Number(number.value());
                case LiteralValue.StringLiteral string ->
                    new ScalarValue.String(string.value());
            };
            return emitValueOp(SemanticOpKind.CONST, new KindPayload.ConstPayload(scalar),
                literal.span(), ContainerPayloadDescriptors.resultDescriptorOf(type),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        private ValueId lowerBindingLoad(IdentifierExpr identifier) {
            return lowerBindingLoad(identifier, null);
        }

        private ValueId lowerBindingLoad(IdentifierExpr identifier, ValueId slot) {
            Type type = checkedType(identifier);
            if (identifier.name().equals("undefined")) {
                return emitValueOp(SemanticOpKind.CONST, new KindPayload.ConstPayload(ScalarValue.Undefined.INSTANCE),
                    identifier.span(), RuntimeDescriptor.Undefined.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, slot);
            }
            for (ForEachFrame frame : frames) {
                if (frame.name().equals(identifier.name())) {
                    FrameResolution loopResolution = resolveFrame(frame.name());
                    if (loopResolution != null
                            && !loopResolution.entry().cell().id.equals(
                                frame.binding())) {
                        // A nearer enclosing-block declaration binds the
                        // name (the checker's non-hoisting rule gives a
                        // shadowing declaration priority over the loop
                        // binding it shadows): the regular resolution
                        // below owns the load, never the frame fast path.
                        break;
                    }
                    if (closureCore) {
                        // The iteration binding is a free reference of every
                        // open detached-body walk whose scope chain excludes
                        // it: register the capture through the same frame
                        // resolution the environment serves (R4 item 5).
                        if (loopResolution != null) {
                            maybeRegisterCapture(frame.name(), loopResolution);
                        }
                    }
                    return emitDynamicAwareLoad(
                        new KindPayload.BindingLoadPayload(frame.binding(),
                            frame.generation()),
                        identifier.span(),
                        ContainerPayloadDescriptors.resultDescriptorOf(type), slot);
                }
            }
            // The catch-binding frames of the residual E5 window (a
            // binding-core walk resolves the catch variable through its
            // binding frame, and a closure walk registers it as a capture
            // through the same frame): only the walk without a binding
            // environment reads the frame directly. A closure walk's own
            // resolution below owns the load, so the reference can become
            // the detached body's capture entry.
            if (!closureCore) {
                for (CatchFrame frame : catchFrames) {
                    if (frame.name().equals(identifier.name())) {
                        FrameResolution catchResolution = resolveFrame(frame.name());
                        int innerFrames = bindingScopes.size()
                            - frame.outerFrameCount();
                        if (catchResolution != null
                                && catchResolution.frameIndex() < innerFrames
                                && !catchResolution.entry().cell().id.equals(
                                    frame.binding())) {
                            // A declaration inside the catch body shadows the
                            // catch variable; an enclosing declaration does
                            // not (the catch variable is the nearer scope).
                            break;
                        }
                        return emitDynamicAwareLoad(
                            new KindPayload.BindingLoadPayload(frame.binding(),
                                INITIAL_LOOP_GENERATION),
                            identifier.span(),
                            ContainerPayloadDescriptors.resultDescriptorOf(type), slot);
                    }
                }
            }

            if (closureCore) {
                FrameResolution resolution = resolveFrame(identifier.name());
                if (resolution == null) {
                    throw new ConstructUnlowered("identifier '" + identifier.name()
                        + "' is not a declared binding of the closure walk's environment "
                        + "(class/module members are E9's/E10's)", identifier.span());
                }
                maybeRegisterCapture(identifier.name(), resolution);
                // The slot-threaded read (a declaration initializer or a
                // loop condition) publishes the caller's slot: a
                // LOOP(FOR) condition is read once per iteration from that
                // one identity, so both productions must write it.
                return emitResolvedLoad(identifier, type, resolution.entry(), slot);
            }

            if (bindingSiteResolver != null) {
                BindingSite site = bindingSiteResolver.resolve(identifier.name());
                if (site != null) {
                    return emitDynamicAwareLoad(
                        new KindPayload.BindingLoadPayload(site.binding(), site.generation()),
                        identifier.span(),
                        ContainerPayloadDescriptors.resultDescriptorOf(type), slot);
                }
            }
            throw new ConstructUnlowered("identifier '" + identifier.name()
                + "' is not a load of an enclosing for-of loop binding or catch "
                + "binding in this stage's window (binding allocation, non-loop "
                + "loads, and generation increments/stores are E6's, ISSUE-0235)",
                    identifier.span());
        }

        private ValueId emitResolvedLoad(IdentifierExpr identifier, Type type,
                                         FrameEntry entry) {
            return emitResolvedLoad(identifier, type, entry, null);
        }

        private ValueId emitResolvedLoad(IdentifierExpr identifier, Type type,
                                         FrameEntry entry, ValueId slot) {
            RuntimeDescriptor descriptor = ContainerPayloadDescriptors.resultDescriptorOf(type);
            ValueId result = null;
            if (descriptor instanceof RuntimeDescriptor.Func
                    && !assignedFunctionBinding(entry)) {
                result = functionIdentity.get(entry.incarnation());
            }
            boolean trackedIdentity = result != null;
            if (result == null) {
                result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(identifier.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.BINDING_LOAD,
                new KindPayload.BindingLoadPayload(entry.cell().id,
                    entry.incarnation().generation()),
                result, descriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (!trackedIdentity) {
                registerDynamicMaterialization(result, opId, descriptor);
            }
            return result;
        }

        /**
         * Registers one function-typed materialization whose execution
         * class is not statically known (the closed producer rule's
         * dynamic arms, M2 item 2): exactly one
         * {@code FunctionExecutionBinding.DynamicFunctionValue} keyed by
         * the producing op's result allocation identity, correlated to
         * that op — the validator's correlation clause resolves the named
         * op, its result identity, and its result descriptor. The
         * registration is the producing arm's own; a non-function
         * descriptor is not a function-typed materialization and registers
         * nothing, and a duplicate key is rejected at registration time
         * (the producing arm is reached exactly once per allocated
         * identity). No static producer is re-registered: a load/read that
         * republishes an already-allocated identity never reaches this
         * seam.
         *
         */
        private void registerDynamicMaterialization(ValueId result, OpId materializingOpId,
                                                    RuntimeDescriptor descriptor) {
            if (!(descriptor instanceof RuntimeDescriptor.Func funcDescriptor)) {
                return;
            }
            registry.registerDynamicFunctionValue(new FunctionAllocationIdentity(result.id()),
                materializingOpId, funcDescriptor);
        }

        /**
         * The producer rule's call-result arm: a call result whose declared
         * result is a function type registers exactly one binding keyed by
         * the call's result identity and correlated to its producing op.
         * When the call's returning crossing is a producing host crossing
         * whose descriptor is function-typed (a direct or indirect host
         * call, or an adapter over a host source), the materialized value is
         * the host-materialized value class and registers its
         * {@code HostFunctionValue} at the crossing (M2 item 1, the gate's
         * producing-crossing clause). Every other function-typed call result
         * — an external cross-module result, a dynamic callee's result — has
         * no statically known class and registers the closed dynamic record
         * (M2 item 2).
         *
         */
        private void registerCallResultMaterialization(ValueId result, OpId callOpId,
                SemanticOp hostCrossing, ModuleId hostCrossingModule,
                RuntimeDescriptor resultType) {
            if (hostCrossing != null && hostCrossingModule != null
                    && hostCrossing.payload() instanceof KindPayload.BoundaryPayload
                        boundary
                    && boundary.descriptor() instanceof RuntimeDescriptor.Func) {
                registry.registerHostFunctionValue(new FunctionAllocationIdentity(
                    result.id()), boundary, hostCrossing.opId(), hostCrossingModule);
                return;
            }
            registerDynamicMaterialization(result, callOpId, resultType);
        }

        /**
         * Emits one {@code BINDING_LOAD} of a frame-resolved binding (a
         * for-of iteration binding, a catch binding, or the binding-core
         * window's dominant-incarnation site) and registers the producer
         * rule's dynamic materialization when the load's result descriptor
         * is a function type: these cells' value identities are never
         * statically tracked, so the load allocates its own carrier
         * identity and registers exactly one
         * {@code DynamicFunctionValue} keyed by it (M2 item 2's iteration
         * and catch-binding families).
         */
        private ValueId emitDynamicAwareLoad(KindPayload payload, Span span,
                                             RuntimeDescriptor descriptor, ValueId slot) {
            ValueId result = emitValueOp(SemanticOpKind.BINDING_LOAD, payload, span,
                descriptor, FailurePolicyId.NO_DEAL_FAILURE, slot);
            if (descriptor instanceof RuntimeDescriptor.Func) {
                registerDynamicMaterialization(result, producerOpId(result), descriptor);
            }
            return result;
        }

        private ValueId lowerClosureExpr(FunctionExpr functionExpr) {
            return lowerClosureExpr(functionExpr, null);
        }

        private ValueId lowerClosureExpr(FunctionExpr functionExpr, ValueId slot) {
            BlockId bodyBlock = allocateBlock();
            FunctionId functionId = ids.nextFunctionId(module, nextOrdinal++, 0);
            Type checkedFunctionType = checkedType(functionExpr);
            if (!(checkedFunctionType instanceof Type.Func)) {
                throw new ConstructUnlowered("function expression of non-function checked "
                    + "type " + typeName(checkedFunctionType) + " (a checked FunctionExpr "
                    + "must be function-typed)", functionExpr.span());
            }
            RuntimeDescriptor.Func signature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(checkedFunctionType);
            List<SemanticOp> bodyOps = new ArrayList<>();
            List<CapturedCell> captured = new ArrayList<>();
            emitTargets.push(bodyOps);
            captureBorders.push(bindingScopes.size());
            captureCollectors.push(captured);
            try {
                checkerScopeNodes.push(functionExpr.body());
                pushBindingFrame();
                pushAssignedNameScope(functionExpr.body().statements());
                blockStack.push(bodyBlock);
                FunctionContext reservedClosureContext = null;
                if (e7Calls) {

                    reservedClosureContext = new FunctionContext(functionId, bodyBlock,
                        signature, ids.nextOpId(module, nextOrdinal++, 0),
                        ids.nextOpId(module, nextOrdinal++, 0),
                        parameterTypeSpans(functionExpr.params()));
                    contextsByFunctionId.put(functionId, reservedClosureContext);
                    functionStack.push(reservedClosureContext);
                }
                lowerParameterBindings(bodyBlock, functionExpr.params());
                boolean closureBodyComplete = false;
                try {
                    statementWalk.walk(functionExpr.body().statements(), false);
                    closureBodyComplete = true;
                } finally {
                    if (reservedClosureContext != null) {
                        if (closureBodyComplete
                                && exitStateOf(bodyBlock) == BlockExitState.OPEN) {
                            // The implicit trailing return of an
                            // unterminated null-returning closure body (the
                            // closed terminator analysis decides: a
                            // non-OPEN body carries no implicit return and
                            // never fails closed).
                            if (!(signature.returnType()
                                    instanceof RuntimeDescriptor.Void)) {
                                throw new ConstructUnlowered("closure body is not "
                                    + "terminated and its return type is not null "
                                    + "(the E7 slice lowers the pinned implicit null "
                                    + "return only)", functionExpr.span());
                            }
                            lowerImplicitReturn(reservedClosureContext,
                                functionExpr.body().span());
                        }
                        functionStack.pop();
                    }
                    blockStack.pop();
                    popBindingFrame();
                    popAssignedNameScope();
                    checkerScopeNodes.pop();
                }
            } finally {
                captureCollectors.pop();
                captureBorders.pop();
                emitTargets.pop();
            }
            List<BindingGeneration> captureIds = captureGenerations(captured);
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            emitClosureNew(functionId, result, signature, captureIds, bodyBlock,
                functionExpr.span(), captured, null);
            emitTarget().addAll(bodyOps);
            return result;
        }

        /** {@code ARRAY_NEW} — element prior steps, the op, then the boundary children. */
        private ValueId lowerArrayNew(ArrayLiteralExpr literal) {
            return lowerArrayNew(literal, null);
        }

        private ValueId lowerArrayNew(ArrayLiteralExpr literal, ValueId slot) {
            Type type = checkedType(literal);
            if (!(type instanceof Type.Array arrayType)) {
                throw new ConstructUnlowered("array literal of non-array checked type "
                    + typeName(type) + " (a checked ArrayLiteralExpr must be array-typed)",
                        literal.span());
            }
            RuntimeDescriptor elementDescriptor =
                containerElementDescriptor(arrayType.element(), literal.span());
            List<ValueId> values = new ArrayList<>();
            Type elementType = arrayType.element();
            for (ExpressionNode element : literal.elements()) {
                // An element position is a statically typed function-value
                // position: a compatible shorter function takes its
                // arity-extension adapter here, before the element cell
                // validates the element (docs/spec-v1.3.md Function type
                // compatibility), so the element boundary sees the exact
                // element descriptor and the array holds the adapted value.
                values.add(positionAdapts(element, elementDescriptor)
                    ? lowerPositionAdapter(element, (RuntimeDescriptor.Func) elementDescriptor,
                        () -> lowerExpression(element))
                    : lowerExpression(element));
            }
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            List<OpId> boundaryIds = new ArrayList<>();
            List<SemanticOp> children = new ArrayList<>();
            for (int i = 0; i < literal.elements().size(); i++) {
                SemanticOp child = buildNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_LITERAL_ELEMENT,
                        elementDescriptor, values.get(i),
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    literal.elements().get(i).span(), FailurePolicyId.ARRAY_ELEMENT_DESCRIPTOR,
                    SourceOriginKind.SYNTHETIC, opId);
                recordPositionAdapterAt(values.get(i), child.opId(),
                    BoundaryKind.ARRAY_LITERAL_ELEMENT);
                boundaryIds.add(child.opId());
                children.add(child);
            }
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(literal.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.ARRAY_NEW,
                new KindPayload.ArrayNewPayload(elementDescriptor, values, boundaryIds),
                result, ContainerPayloadDescriptors.resultDescriptorOf(arrayType),
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            for (SemanticOp child : children) {
                emit(child);
            }
            if (creationRuleAnalysis && elementDescriptor instanceof RuntimeDescriptor.Func) {
                // Function-typed array literal element positions: the
                // closed position classification over each element's
                // checked source type and the literal's element type (the
                // classifier records the adaptation candidate exactly for
                // the positions this walk adapted).
                for (int i = 0; i < literal.elements().size(); i++) {
                    recordPositionClassification(BoundaryKind.ARRAY_LITERAL_ELEMENT,
                        producedType(literal.elements().get(i)), elementType,
                        values.get(i), boundaryIds.get(i));
                }
            }
            return result;
        }

        /** {@code TABLE_NEW} — entry values in source order, then the op. */
        private ValueId lowerTableNew(ObjectLiteralExpr literal) {
            return lowerTableNew(literal, null);
        }

        private ValueId lowerTableNew(ObjectLiteralExpr literal, ValueId slot) {
            Type type = checkedType(literal);
            if (type instanceof Type.Class classType) {
                if (!classCore) {
                    throw new ConstructUnlowered("class-typed object literal "
                        + new ClassId(DescriptorService.semanticModulePath(
                            classType.identity()), classType.name()).text()
                        + " (class construction and CLASS_NEW are E9's)", literal.span());
                }
                return lowerClassLiteral(literal, slot);
            }
            if (!(type instanceof Type.Table)) {
                throw new ConstructUnlowered("object literal of non-table, non-class checked "
                    + "type " + typeName(type) + " (a checked ObjectLiteralExpr must be "
                    + "table- or class-typed)", literal.span());
            }
            List<KindPayload.TableEntry> entries = new ArrayList<>();
            for (Property property : literal.properties()) {
                ValueId value = lowerExpression(property.value());
                entries.add(new KindPayload.TableEntry(property.name(), value));
            }
            return emitValueOp(SemanticOpKind.TABLE_NEW,
                new KindPayload.TableNewPayload(entries), literal.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Table.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /** {@code ARRAY_LENGTH}/{@code MEMBER_READ} — the member-access dispatch. */

        private ValueId lowerIndexRead(IndexExpr index, ValueId slot) {
            Type objectType = checkedType(index.array());
            if (objectType instanceof Type.Table) {
                return lowerTableIndexRead(index, slot);
            }
            if (!(objectType instanceof Type.Array)) {
                throw new ConstructUnlowered("index read on " + typeName(objectType)
                    + " (the carrier slice lowers array index reads; table index reads "
                    + "are E2's)", index.span());
            }
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(index));
            ValueId container = lowerOperand(index.array());
            ValueId key = lowerOperand(index.index());
            ValueId length = emitValueOp(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(container), index.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE);
            RuntimeDescriptor intDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE);
            ValueId normalize = emitOperandOp(SemanticOpKind.INDEX_NORMALIZE,
                new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_READ, key, length),
                List.of(key, length), List.of(
                    ContainerPayloadDescriptors.resultDescriptorOf(
                        checkedType(index.index())),
                    intDescriptor),
                index.span(), intDescriptor,
                FailurePolicyId.NO_DEAL_FAILURE);
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            AnchorId boundaryAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId boundaryOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(index.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.INDEX_READ,
                new KindPayload.IndexReadPayload(container, normalize, boundaryOpId),
                result, resultType, List.of(container, normalize), List.of(
                    ContainerPayloadDescriptors.resultDescriptorOf(objectType),
                    intDescriptor),
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            SourceOrigin boundaryOrigin = new SourceOrigin(sourceId,
                toSourceSpan(index.span()), SourceOriginKind.SYNTHETIC, boundaryAnchor,
                opId);
            emit(buildOp(boundaryOpId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_ELEMENT_READ,
                    resultType, result,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, FailurePolicyId.ARRAY_READ_INDEX_THEN_DESCRIPTOR,
                boundaryOrigin));
            // The producer rule's index-read arm: an element read whose
            // checked result descriptor is a function type registers
            // exactly one dynamic materialization keyed by the read's
            // result identity (the read allocates it).
            registerDynamicMaterialization(result, opId, resultType);
            return result;
        }

        private ValueId lowerMemberAccess(MemberAccessExpr access) {
            return lowerMemberAccess(access, null);
        }

        private ValueId lowerMemberAccess(MemberAccessExpr access, ValueId slot) {
            // The module-symbol arm of the one import-member read production
            // (R1): the checker types a module-symbol object as `table`, so
            // the symbol fact of the access site's checker scope must win over
            // the type fact. The production emits exactly one EXPORT_READ per
            // source occurrence and, for a function descriptor, exactly one
            // FunctionExecutionBinding registration keyed by the published
            // cell (the position's threaded slot when it has one).
            if (access.object() instanceof IdentifierExpr identifier
                    && isModuleSymbol(identifier.name())) {
                return materializeImportRead(access, identifier, slot).value();
            }
            Type objectType = checkedType(access.object());
            if (objectType instanceof Type.Array && "length".equals(access.field())) {
                return lowerArrayLength(access, slot);
            }
            if (objectType instanceof Type.Table) {
                return lowerMemberRead(access, slot);
            }
            if (objectType instanceof Type.Class classType) {
                if (!classCore) {
                    throw new ConstructUnlowered("class member access "
                        + new ClassId(DescriptorService.semanticModulePath(
                            classType.identity()), classType.name()).text()
                        + "." + access.field()
                        + " (FIELD_READ is E9's)", access.span());
                }
                return lowerFieldRead(access, classType, slot);
            }
            if (classCore && objectType instanceof Type.Nullable nullable
                    && nullable.inner() instanceof Type.Class innerClassType) {

                return lowerFieldRead(access, innerClassType, slot);
            }
            throw new ConstructUnlowered("member access '" + access.field() + "' on "
                + typeName(objectType) + " (no member-access arm for this receiver shape "
                + "in this stage's window)", access.span());
        }

        private boolean isModuleSymbol(String name) {
            SymbolTable scope = currentCheckerScope();
            return scope != null && scope.resolve(name) instanceof Symbol.ModuleSymbol;
        }

        private ValueId lowerFieldRead(MemberAccessExpr access, Type.Class classType,
                                       ValueId slot) {
            ClassId classId = new ClassId(
                DescriptorService.semanticModulePath(classType.identity()), classType.name());
            RuntimeDescriptor classDescriptor;
            deal.semantic.ir.ClassFactoryId providerRef = null;
            try {
                classDescriptor = DescriptorService.describe(classType);
            } catch (DescriptorService.Defect defect) {
                throw new ConstructUnlowered("class member access "
                    + classId + "." + access.field() + " on an unrepresentable receiver "
                    + "type (" + defect.getMessage() + ")", access.span());
            }
            if (classCore) {
                // The exact declaring provider of the receiver's checked
                // declaration: the read's declared result descriptor comes
                // from the checker's exact declaration, and the recorded
                // provider reference keeps the field link coherent with the
                // declaring declaration's layout (a harness-synthesized
                // identity can still name two declarations).
                providerRef = resolveFieldReceiver(access.object(), classId,
                    "class field read '" + access.field() + "'",
                    access.span()).providerRef();
            }
            RuntimeDescriptor resultDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(access));
            ValueId receiver = lowerExpression(access.object());
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SemanticOp receiverBoundary = buildNullOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.UNTYPED_CLASS_INPUT,
                    classDescriptor, receiver,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                access.span(), descriptorKindPolicy(classDescriptor),
                SourceOriginKind.SYNTHETIC, opId);
            SemanticOp fieldBoundary = buildNullOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.OPTIONAL_FIELD_READ,
                    resultDescriptor, result,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                access.span(), descriptorKindPolicy(resultDescriptor),
                SourceOriginKind.SYNTHETIC, opId);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(access.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.FIELD_READ,
                new KindPayload.FieldReadPayload(receiver, classId, access.field(),
                    providerRef),
                result, resultDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            emit(receiverBoundary);
            emit(fieldBoundary);
            // The producer rule's field-read arm: a function-typed field
            // read registers exactly one dynamic materialization keyed by
            // the read's result identity (the read allocates it).
            registerDynamicMaterialization(result, opId, resultDescriptor);
            return result;
        }

        /**
         * {@code HAS_FIELD} — the presence boolean over the receiver's static
         * key, the receiver evaluated exactly once. The receiver is a value
         * consumer like an operator operand, an index chain's receiver/key, a
         * table member receiver, a {@code .length} receiver or a for-of
         * iterable, so it lowers through the one operand admission
         * ({@link #lowerOperand(ExpressionNode)}): a null-narrowing-refined
         * identifier read (declared {@code C | null}, read as {@code C}) is
         * admitted against the {@code NARROWED_READ} cell with its refined
         * class descriptor — the nominal receiver check — and the presence
         * read consumes the admitted value. A binding a capturing closure
         * cleared between the refinement and this read therefore reports the
         * typed-boundary tuple (E8001, expected class, actual null) at the
         * receiver instead of a raw backend presence read on the cleared
         * value, while the absent / present-null / present-false presence
         * semantics, the once-only receiver evaluation and the operand order
         * are unchanged.
         */
        private Type checkedInstanceofTarget(InstanceofExpr test) {
            Type.Class target = checks.instanceofTargets().get(test);
            if (target == null) throw new ConstructUnlowered("Missing instanceof class fact", test.span());
            return target;
        }

        private ValueId lowerHasField(HasExpr has, ValueId slot) {
            ValueId receiver = lowerOperand(has.object());
            ValueId key = has.field() == null ? lowerExpression(has.key()) : null;
            return emitValueOp(SemanticOpKind.HAS_FIELD,
                new KindPayload.HasFieldPayload(receiver, has.field(), key),
                has.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Boolean.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /**
         * {@code ARRAY_LENGTH} — the receiver is one prior step, never
         * re-evaluated. The {@code .length} receiver is a value consumer
         * like an operator operand or an index chain's receiver/key, so it
         * lowers through the one operand admission
         * ({@link #lowerOperand(ExpressionNode)}): a null-narrowing-refined
         * identifier read ({@code T[] | null} read as {@code T[]}) is admitted against the {@code NARROWED_READ}
         * cell, and the length read consumes the admitted value. A binding
         * a capturing closure cleared between the refinement and this read
         * therefore reports the typed-boundary tuple (E8001, expected
         * array, actual null) at the receiver instead of a raw backend
         * length read on the cleared value; the receiver's own evaluation
         * (one binding load) is unchanged.
         */
        private ValueId lowerArrayLength(MemberAccessExpr access) {
            return lowerArrayLength(access, null);
        }

        private ValueId lowerArrayLength(MemberAccessExpr access, ValueId slot) {
            ValueId receiver = lowerOperand(access.object());
            return emitValueOp(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(receiver), access.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Number.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /**
         * {@code MEMBER_READ} — the missing-aware read plus its contextual
         * child. A non-nullable read keeps the direct shape: one
         * {@code MEMBER_READ} publishing the read result plus one
         * {@code CONTEXTUAL_TABLE_READ} boundary child (missing →
         * nullable-null / E8001 via the contextual decision). A
         * missing-capable read (a nullable contextual type) lowers the
         * {@code OPTIONAL_READ} envelope: the {@code MEMBER_READ} op
         * publishes its result typed {@code INTERNAL_MISSING}, exactly one
         * {@code OPTIONAL_READ} op carries the raw value, the presence
         * marker, and the inner descriptor (missing → language null before
         * a present value is validated), and the
         * {@code CONTEXTUAL_TABLE_READ} boundary child validates the
         * optional read's result (present null passes the nullable
         * descriptor; a present wrong-kind value projects the pinned E8001).
         */
        private ValueId lowerMemberRead(MemberAccessExpr access) {
            return lowerMemberRead(access, null);
        }

        private ValueId lowerCallArgument(ExpressionNode argument) {
            if (!(argument instanceof MemberAccessExpr access)) {
                return lowerExpression(argument);
            }
            callArgumentReads.add(access);
            try {
                return lowerExpression(argument);
            } finally {
                callArgumentReads.remove(access);
            }
        }

        private ValueId lowerMemberRead(MemberAccessExpr access, ValueId slot) {
            return lowerTableRead(lowerOperand(access.object()), access.field(),
                access.span(), checkedType(access), slot, access);
        }

        /**
         * The computed table read ({@code t["key"]} / {@code t[k]}) — the
         * shared contextual-read form over the same {@code MEMBER_READ}
         * shape the static member read uses for a literal string key (a
         * table's string keys are the same keys in both spellings; the
         * checker's context algorithm admits both forms identically). A
         * non-literal key keeps the literal read's semantics through the
         * shared dynamic-key read seam: the receiver completes before
         * the key, {@code INDEX_NORMALIZE(TABLE_READ)} publishes the
         * evaluated key's closed table slot, and the {@code INDEX_READ}
         * carries the read's {@code CONTEXTUAL_TABLE_READ} boundary
         * child — the same contextual validation, execution order, and
         * key identity as the literal spelling.
         */
        private ValueId lowerTableIndexRead(IndexExpr index, ValueId slot) {
            if (index.index() instanceof LiteralExpr literal
                    && literal.value()
                        instanceof LiteralValue.StringLiteral stringLiteral) {
                // The literal-key optimization: the constant key needs no
                // evaluation step (the static member read's shape).
                return lowerTableRead(lowerOperand(index.array()),
                    stringLiteral.value(), index.span(), checkedType(index), slot,
                    null);
            }
            return lowerDynamicTableIndexRead(index, slot);
        }

        /**
         * The dynamic-key computed table read: {@code [containerOp,
         * keyOp, normalizeOp(INDEX_NORMALIZE TABLE_READ), INDEX_READ]}
         * with the read's {@code CONTEXTUAL_TABLE_READ} boundary child.
         * The key is statically {@code string} by the checker's
         * read-key gate, so the normalize is total with no coercion and
         * preserves key identity exactly.
         */
        private ValueId lowerDynamicTableIndexRead(IndexExpr index, ValueId slot) {
            Type contextualType = checkedType(index);
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(contextualType);
            RuntimeDescriptor tableDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Table.INSTANCE);
            RuntimeDescriptor stringDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE);
            // Receiver before key (the spec's evaluation order).
            ValueId container = lowerOperand(index.array());
            ValueId key = lowerOperand(index.index());
            ValueId normalizedSlot = emitOperandOp(SemanticOpKind.INDEX_NORMALIZE,
                new KindPayload.IndexNormalizePayload(IndexMode.TABLE_READ, key, key),
                List.of(key, key), List.of(stringDescriptor, stringDescriptor),
                index.span(), stringDescriptor, FailurePolicyId.NO_DEAL_FAILURE);
            ValueId result = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            AnchorId boundaryAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId boundaryOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(index.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.INDEX_READ,
                new KindPayload.IndexReadPayload(container, normalizedSlot, boundaryOpId),
                result, resultType, List.of(container, normalizedSlot), List.of(
                    tableDescriptor, stringDescriptor),
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            FailurePolicyId childPolicy = resultType instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
            SourceOrigin boundaryOrigin = new SourceOrigin(sourceId,
                toSourceSpan(index.span()), SourceOriginKind.SYNTHETIC, boundaryAnchor,
                opId);
            emit(buildOp(boundaryOpId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.CONTEXTUAL_TABLE_READ,
                    resultType, result,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, childPolicy, boundaryOrigin));
            if (creationRuleAnalysis && resultType instanceof RuntimeDescriptor.Func) {
                recordBoundaryClassification(BoundaryKind.CONTEXTUAL_TABLE_READ,
                    contextualType, contextualType);
            }
            // The producer rule's index-read arm: a function-typed element
            // read registers exactly one dynamic materialization.
            registerDynamicMaterialization(result, opId, resultType);
            return result;
        }

        /**
         * The shared {@code MEMBER_READ} emission of a table read: the
         * static member access ({@code t.key}) and the computed index
         * read ({@code t["key"]}) converge here.
         *
         * @param callArgumentAccess the static member-access node when
         *                           the read sits directly on a declared
         *                           callee's argument (the parameter
         *                           cell owns the kind check), or
         *                           {@code null} otherwise
         */
        private ValueId lowerTableRead(ValueId receiver, String key, Span readSpan,
                                       Type contextualType, ValueId slot,
                                       MemberAccessExpr callArgumentAccess) {
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(contextualType);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(readSpan),
                SourceOriginKind.USER, anchor, currentParent());
            FailurePolicyId childPolicy = resultType instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
            if (contextualType instanceof Type.Nullable nullable) {
                // The OPTIONAL_READ envelope (the CONTAINERS_AND_STRINGS
                // extras, parent E3): a missing-capable read — a table
                // member read whose checked contextual type is nullable —
                // lowers to the raw INTERNAL_MISSING read plus exactly one
                // OPTIONAL_READ op carrying the present value (or internal
                // missing) and the inner descriptor, with the present
                // branch validated by the CONTEXTUAL_TABLE_READ boundary
                // child per the closed table's optional-read rule (missing
                // → language null before a present value is validated).
                RuntimeDescriptor inner =
                    ContainerPayloadDescriptors.resultDescriptorOf(nullable.inner());
                ValueId readResult = ids.nextValueId(module, nextOrdinal++, 0);
                emit(buildOp(opId, SemanticOpKind.MEMBER_READ,
                    new KindPayload.MemberReadPayload(receiver, key),
                    readResult, InternalResultType.INTERNAL_MISSING,
                    FailurePolicyId.NO_DEAL_FAILURE, origin));
                ValueId result = slot != null ? slot
                    : ids.nextValueId(module, nextOrdinal++, 0);
                AnchorId optionalAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                OpId optionalOpId = ids.nextOpId(module, nextOrdinal++, 0);
                SourceOrigin optionalOrigin = new SourceOrigin(sourceId,
                    toSourceSpan(readSpan), SourceOriginKind.USER, optionalAnchor,
                    currentParent());
                SemanticOp optionalRead = buildOp(optionalOpId,
                    SemanticOpKind.OPTIONAL_READ,
                    new KindPayload.OptionalReadPayload(readResult, true, inner),
                    result, resultType, List.of(readResult), List.of(resultType),
                    FailurePolicyId.NO_DEAL_FAILURE, optionalOrigin);
                emit(optionalRead);
                emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.CONTEXTUAL_TABLE_READ,
                        resultType, result,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    readSpan, childPolicy, SourceOriginKind.SYNTHETIC, optionalOpId);
                if (creationRuleAnalysis && resultType instanceof RuntimeDescriptor.Func) {
                    recordBoundaryClassification(BoundaryKind.CONTEXTUAL_TABLE_READ,
                        contextualType, contextualType);
                }
                return result;
            }
            ValueId readResult = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);
            emit(buildOp(opId, SemanticOpKind.MEMBER_READ,
                new KindPayload.MemberReadPayload(receiver, key),
                readResult, resultType, FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (callArgumentAccess != null
                    && callArgumentReads.contains(callArgumentAccess)
                    && consumingCellOwnsKindCheck(resultType)) {
                // The declared callee's parameter cell performs the check at
                // the declaration-owned annotation origin (the unchanged
                // reference's deferral); the raw read composes no boundary.
                registerDynamicMaterialization(readResult, opId, resultType);
                return readResult;
            }
            emitNullOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.CONTEXTUAL_TABLE_READ,
                    resultType, readResult,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                readSpan, childPolicy, SourceOriginKind.SYNTHETIC, opId);
            if (creationRuleAnalysis && resultType instanceof RuntimeDescriptor.Func) {
                // A function-typed contextual table read: a typed
                // boundary position never adapts (B6) — the read's
                // direct result flow into the CONTEXTUAL_TABLE_READ
                // boundary slot is the wiring E4's boundary producer
                // consumes (the read publishes the contextual
                // descriptor itself, so the classifier records the
                // exact-direct classification).
                recordBoundaryClassification(BoundaryKind.CONTEXTUAL_TABLE_READ,
                    contextualType, contextualType);
            }
            // The producer rule's member-read arm: a function-typed member
            // read registers exactly one dynamic materialization keyed by
            // the read's result identity with the read's checked
            // descriptor (the read allocates it). A nullable contextual
            // type takes the OPTIONAL_READ envelope above and publishes a
            // nullable descriptor — not a function-typed result, so no
            // dynamic record is keyed by it (the guarded value
            // materializes at the value-position load that reads the
            // narrowed cell).
            registerDynamicMaterialization(readResult, opId, resultType);
            return readResult;
        }

        private static boolean consumingCellOwnsKindCheck(RuntimeDescriptor descriptor) {
            return !(descriptor instanceof RuntimeDescriptor.Func)
                && !(descriptor instanceof RuntimeDescriptor.Array)
;
        }

        /**
         * Lowers one operator operand, condition expression, index
         * receiver/key expression, table member receiver expression,
         * {@code .length} receiver expression, for-of iterable, {@code has}
         * receiver or {@code throw} operand: the operand's completed value
         * plus, for an identifier read whose checked type is a
         * null-narrowing refinement of the binding's declared nullable type
         * (declared {@code T | null}, read as {@code T}), the
         * {@code NARROWED_READ} validation of that read.
         *
         * <p>docs/spec-v1.5.md Nullable semantics keeps calls
         * non-invalidating on purpose, so a capturing closure call can
         * leave the binding null while the refinement claim still holds;
         * the consumer then receives a value its checked type does not
         * admit. The validation admits the read against the refined
         * descriptor at the read's own origin — the standard
         * typed-boundary projection (E8001, or the descriptor-kind family
         * for a function descriptor) — so no backend coerces the value and
         * none lets a raw arithmetic/index error escape a DEAL
         * {@code try}. It publishes the admitted value and the consumer
         * uses that.</p>
         *
         * <p>An index chain lowers its receiver and key through this
         * helper, so the chain's length/normalize/commit children and the
         * commit's resolved receiver/key reference the admitted value
         * (the chain's closed A-D9 child shape is unchanged: the
         * admission is the receiver/key child itself). An index read uses
         * the admitted value as its {@code INDEX_READ} container/key. A
         * table member read/write/delete lowers its receiver through this
         * helper too, so the {@code MEMBER_READ}/{@code MEMBER_WRITE}/
         * {@code MEMBER_DELETE} operation dereferences the admitted value
         * (the chain's container child is the admission) and never the
         * unadmitted read — the receiver is a consumed value like any
         * other, not a typed-boundary position. A {@code .length} receiver
         * lowers through this helper too, so the {@code ARRAY_LENGTH} op
         * (array) reads the admitted value and never the
         * unadmitted read. A for-of iterable lowers through this helper
         * too, in both the array and the string arm: the {@code FOR_EACH}
         * consumes the admitted value, so a capturing closure that cleared
         * the binding reports the typed-boundary failure before the loop's
         * initialization dereferences the carrier and the iterable still
         * completes exactly once. A {@code has} receiver lowers through this
         * helper too, so the {@code HAS_FIELD} presence read consumes the
         * admitted value — the refined class descriptor's nominal check —
         * instead of reading presence off the cleared binding. A
         * {@code throw} operand lowers through this helper too, so the
         * {@code THROW} op consumes the admitted {@code Error} value and the
         * throw machinery never dereferences a cleared value's transport (a
         * native nil index error on LuaJIT, a host null dereference on the
         * JVM) inside the enclosing DEAL {@code try}: the read's own origin
         * publishes the canonical catchable E8001 with the class descriptor's
         * expected/actual tuple, and a live operand keeps its reference
         * identity. Every other position (a declaration, assignment,
         * parameter, return, class/array construction or element write) is
         * a typed boundary owned by its own cell, which keeps its recorded
         * projection — the array-element E8003 arm included.</p>
         */
        private ValueId lowerOperand(ExpressionNode expr) {
            return emitNarrowedReadCheck(expr, lowerExpression(expr));
        }


        private ValueId lowerOperand(ExpressionNode expr, ValueId slot) {
            return emitNarrowedReadCheck(expr, lowerExpression(expr, slot), slot);
        }


        private ValueId emitNarrowedConditionCheck(ExpressionNode expr, ValueId value) {
            if (!checks.narrowedReads().contains(expr)) {
                return value;
            }
            RuntimeDescriptor descriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(expr));
            emit(buildNullOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.NARROWED_READ, descriptor,
                    value, new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                expr.span(), descriptorKindPolicy(descriptor),
                SourceOriginKind.USER, currentParent()));
            return value;
        }

        /**
         * Admits one lowered operand value against the {@code NARROWED_READ}
         * cell when its expression is a null-narrowing-refined identifier
         * read ({@code checks.narrowedReads()}); every other operand value
         * passes through unchanged. The admission publishes its own fresh
         * value (the chain/operand positions' consumer names it).
         */
        private ValueId emitNarrowedReadCheck(ExpressionNode expr, ValueId value) {
            return emitNarrowedReadCheck(expr, value, null);
        }


        private ValueId emitNarrowedReadCheck(ExpressionNode expr, ValueId value,
                                              ValueId slot) {
            if (!checks.narrowedReads().contains(expr)) {
                return value;
            }
            RuntimeDescriptor descriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(expr));
            if (descriptor instanceof RuntimeDescriptor.Func) {
                // A function-typed read is one allocated identity (the
                // function-binding registry keys it), so its validation
                // republishes that identity in place and never publishes a
                // second value: the reference selectors compare the one
                // allocated value, and no operator position can place a
                // function-typed operand inside an address chain (only the
                // reference equality rows consume one). The load itself
                // ignores the slot for a tracked function identity.
                return emitNarrowedConditionCheck(expr, value);
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(expr.span()),
                SourceOriginKind.USER, anchor, currentParent());
            ValueId admitted = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);
            emit(buildOp(opId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.NARROWED_READ, descriptor,
                    value, new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                admitted, descriptor, List.of(value), List.of(descriptor),
                descriptorKindPolicy(descriptor), origin));
            return admitted;
        }

        /** {@code STRING_CONCAT}/{@code BINARY} — the binary dispatch (I3 arithmetic). */
        private ValueId lowerBinary(BinaryExpr binary) {
            return lowerBinary(binary, null);
        }

        private ValueId lowerBinary(BinaryExpr binary, ValueId slot) {
            Type type = checkedType(binary);
            if (binary.op() == BinaryOp.ADD && type instanceof Type.String) {
                return lowerStringConcat(binary, slot);
            }
            if (binary.op() == BinaryOp.AND || binary.op() == BinaryOp.OR) {
                return lowerLogicalBranch(binary, slot);
            }
            if (isArithmeticOperator(binary.op())) {
                return lowerArithmeticBinary(binary, slot);
            }
            if (bindingCore && isComparisonOperator(binary.op())) {

                return lowerComparisonBinary(binary, slot);
            }
            throw new ConstructUnlowered("binary selector " + binary.op()
                + " of checked type " + typeName(type)
                + " (comparison selectors are the comparison producer "
                + "ComparisonSelectorLowering's; string + lowers to "
                + "STRING_CONCAT, never BINARY)", binary.span());
        }

        /** True iff the operator is one of the six comparison operators (B-D3). */
        private static boolean isComparisonOperator(BinaryOp op) {
            return switch (op) {
                case EQ, NEQ, LT, LTE, GT, GTE -> true;
                case ADD, SUB, MUL, DIV, POW, AND, OR -> false;
            };
        }

        /**
         * One {@code BINARY} comparison op through the single comparison
         * producer (B-D3): operands complete in source order (the
         * binding-environment loads included), the producer selects the
         * closed selector from the checked operand types, stamps the
         * snapshot digest, and allocates its result/op ids at the
         * session's next source ordinal — the produced op appends to the
         * session in source order.
         */
        private ValueId lowerComparisonBinary(BinaryExpr binary, ValueId slot) {
            ValueId left = lowerOperand(binary.left());
            ValueId right = lowerOperand(binary.right());
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(binary.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            SemanticOp comparison = ComparisonSelectorLowering.produce(module,
                binary.op(), checkedType(binary.left()), checkedType(binary.right()),
                left, right, origin, ids, nextOrdinal, 0);
            // The producer consumed exactly one source ordinal (VALUE, OP).
            nextOrdinal++;
            if (slot != null && !slot.equals(comparison.result())) {
                // The carrier's slot threading: the condition re-production
                // publishes the pinned condition slot; the result identity
                // is outside the operation-contract digest, so the digest
                // is unchanged.
                comparison = new SemanticOp(comparison.opId(), comparison.kind(),
                    comparison.origin(), slot, comparison.resultType(),
                    comparison.operands(), comparison.operandTypes(),
                    comparison.payload(), comparison.failurePolicy(),
                    comparison.contract());
            }
            emit(comparison);
            return (ValueId) comparison.result();
        }

        /**
         * {@code BRANCH(LOGICAL_AND/LOGICAL_OR)} — the logical short-circuit
         * arm (C-D3; never {@code BINARY}): the left operand completes
         * before START as the condition; the right operand's producing ops
         * live in {@code selectedBlock} and execute only when the left
         * value does not decide the result — AND: left false → result
         * false, block skipped; OR: left true → result true, block
         * skipped. The op's result {@code ValueId} is the right operand's
         * value identity (result type {@code D(boolean)} — a boolean
         * either way, checker-pinned boolean operands). Chained
         * {@code &&}/{@code ||} lower to nested {@code BRANCH}es in
         * source order. Block ops record the {@code BRANCH} as
         * {@code parentOpId}; the {@code BRANCH} op precedes its child
         * block ops in the unit list.
         */
        private ValueId lowerLogicalBranch(BinaryExpr binary, ValueId slot) {
            if (!(checkedType(binary.left()) instanceof Type.Boolean)
                    || !(checkedType(binary.right()) instanceof Type.Boolean)) {
                throw new ConstructUnlowered("logical operator " + binary.op()
                    + " over a non-boolean checked operand (the checker pins boolean "
                    + "operands — a missing checker fact is a producer defect)", binary.span());
            }
            ControlSelector selector = binary.op() == BinaryOp.AND
                ? ControlSelector.LOGICAL_AND : ControlSelector.LOGICAL_OR;
            ValueId left = lowerOperand(binary.left());
            BlockId selectedBlock = allocateBlock();
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            int mark = emitTarget().size();
            pushBlockParent(opId);
            pushBlock(selectedBlock);
            ValueId right;
            try {
                right = lowerOperand(binary.right(), slot);
            } finally {
                popBlock();
                popBlockParent();
            }
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(binary.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emitAt(mark, buildOp(opId, SemanticOpKind.BRANCH,
                new KindPayload.BranchPayload(selector, left, selectedBlock, null),
                right, ContainerPayloadDescriptors.resultDescriptorOf(Type.Boolean.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            return right;
        }

        /** {@code STRING_CONCAT} — the string-{@code +} arm; never {@code BINARY}. */
        private ValueId lowerStringConcat(BinaryExpr binary) {
            return lowerStringConcat(binary, null);
        }

        private ValueId lowerStringConcat(BinaryExpr binary, ValueId slot) {
            ValueId left = lowerOperand(binary.left());
            ValueId right = lowerOperand(binary.right());
            return emitValueOp(SemanticOpKind.STRING_CONCAT,
                new KindPayload.StringConcatPayload(List.of(left, right)),
                binary.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /** True iff the operator is one of the six arithmetic operators (I3). */
        private static boolean isArithmeticOperator(BinaryOp op) {
            return switch (op) {
                case ADD, SUB, MUL, DIV, POW -> true;
                case EQ, NEQ, LT, LTE, GT, GTE, AND, OR -> false;
            };
        }

        /**
         * {@code BINARY} — the I3 int32/number arithmetic arm: exactly one
         * closed selector from the operator plus the checked operand type
         * ({@code INT32_ADD/SUB/MUL/DIV_TRUNC} over int;
         * {@code NUMBER_ADD/SUB/MUL/DIV_IEEE} over
         * number), operands in source order (left then right), the policy
         * stamped from the single closed {@code binaryPolicy} table (never
         * a copy), and the result descriptor from the checked result type.
         * Any operator/operand shape outside the map is a producer defect
         * ({@link ConstructUnlowered}) — never a guessed selector.
         */
        private ValueId lowerArithmeticBinary(BinaryExpr binary) {
            return lowerArithmeticBinary(binary, null);
        }

        private ValueId lowerArithmeticBinary(BinaryExpr binary, ValueId slot) {
            Type leftType = checkedType(binary.left());
            BinarySelector selector;
            if (leftType instanceof Type.Number) {
                selector = switch (binary.op()) {
                    case ADD -> BinarySelector.NUMBER_ADD;
                    case SUB -> BinarySelector.NUMBER_SUB;
                    case MUL -> BinarySelector.NUMBER_MUL;
                    case DIV -> BinarySelector.NUMBER_DIV_IEEE;
                    default -> throw new ConstructUnlowered("arithmetic operator "
                        + binary.op() + " outside the I3 number rows (producer defect)",
                            binary.span());
                };
            } else {
                throw new ConstructUnlowered("arithmetic binary " + binary.op()
                    + " over non-int/number checked operand " + typeName(leftType)
                    + " (a checked arithmetic expression must be int- or number-typed)",
                        binary.span());
            }
            ValueId left = lowerOperand(binary.left());
            ValueId right = lowerOperand(binary.right());
            return emitOperandOp(SemanticOpKind.BINARY,
                new KindPayload.BinaryPayload(selector, null, null),
                List.of(left, right),
                List.of(valueDescriptorOf(leftType),
                    valueDescriptorOf(checkedType(binary.right()))),
                binary.span(), valueDescriptorOf(checkedType(binary)),
                SemanticIrValidator.binaryPolicy(selector), slot);
        }

        /**
         * {@code UNARY} — the I3 arm: exactly one closed selector from
         * the operator plus the operand's checked type ({@code BOOL_NOT}
         * over boolean; {@code INT32_NEG} over int; {@code NUMBER_NEG}
         * over number), the operand as one prior step (operands complete
         * left-to-right before {@code UNARY} START), the policy stamped
         * from the single closed {@code unaryPolicy} rule (never a copy),
         * and the result descriptor from the checked result type.
         */
        private ValueId lowerUnary(UnaryExpr unary) {
            return lowerUnary(unary, null);
        }

        private ValueId lowerUnary(UnaryExpr unary, ValueId slot) {
            Type operandType = checkedType(unary.expr());
            UnarySelector selector;
            switch (unary.op()) {
                case TYPEOF -> selector = UnarySelector.TYPEOF;
                case NOT -> {
                    if (!(operandType instanceof Type.Boolean)) {
                        throw new ConstructUnlowered("unary '!' over non-boolean checked "
                            + "operand " + typeName(operandType)
                            + " (a missing checker fact is a producer defect)", unary.span());
                    }
                    selector = UnarySelector.BOOL_NOT;
                }
                case NEG -> {
                    if (operandType instanceof Type.Number) {
                        selector = UnarySelector.NUMBER_NEG;
                    } else {
                        throw new ConstructUnlowered("unary '-' over non-int/number checked "
                            + "operand " + typeName(operandType)
                            + " (a missing checker fact is a producer defect)", unary.span());
                    }
                }
                default -> throw new ConstructUnlowered("unary operator " + unary.op()
                    + " outside the closed UnarySelector rows (producer defect)", unary.span());
            }
            ValueId operand = lowerOperand(unary.expr());
            return emitOperandOp(SemanticOpKind.UNARY,
                new KindPayload.UnaryPayload(selector),
                List.of(operand), List.of(valueDescriptorOf(operandType)),
                unary.span(), valueDescriptorOf(checkedType(unary)),
                SemanticIrValidator.unaryPolicy(selector), slot);
        }


/**
         * The conversion/allocation intrinsic kind one identifier
         * position resolves to, or {@code null} when the position's
         * binding is not that intrinsic — the position-resolved
         * classifier the intrinsic-call arm and the adapted-source shape
         * share. Resolution follows docs/spec-v1.5.md §Name resolution:
         * the walk's own binding environment is the declaration-order
         * exact per-position fact (a {@code let} is registered only after
         * its own initializer, so an initializer reading the shadowed
         * name still resolves the enclosing binding), and the checker's
         * per-scope table supplies the fact for the modes that do not
         * seed their intrinsic bindings (its parent chain reaches the
         * module root, so a nested binding shadows the enclosing scopes
         * and the root intrinsics exactly as the checker resolved the
         * occurrence).
         */


        /**
         * True iff the identifier position's resolution is one of the
         * conversion/allocation intrinsics — the shape map's
         * position-resolved intrinsic predicate
         * ({@link #conversionIntrinsicKindAt}).
         */

        /**
         * The innermost checker scope table of the current walk position:
         * the per-scope table pass 1 recorded for the innermost enclosing
         * scope-keyed node (a block, a function declaration or expression
         * body, a for head, a try statement), walking out to the next
         * scope-keyed node when the position's node records no table of
         * its own, and the module root table at module level. The table's
         * parent chain reaches the module root, so a nested binding
         * shadows an enclosing binding, a module-level declaration and the
         * root compiler intrinsics exactly as the checker resolved the
         * occurrence.
         */
        private SymbolTable siteCheckerScope() {
            for (StatementNode node : checkerScopeNodes) {
                SymbolTable table = checks.scopeMap().get(node);
                if (table != null) {
                    return table;
                }
            }
            return checks.symbolTable();
        }

        /**
         * The conversion intrinsics' name-to-kind map (the
         * seed's and the intrinsic-call classifier's single authority):
         * {@code int} is {@code INT_CONVERT}, {@code number} is
         * {@code NUMBER_CONVERT}; every other name — including the
         * {@code has} intrinsic — maps to no intrinsic kind.
         *
         */


        private static RuntimeDescriptor valueDescriptorOf(Type type) {
            try {
                return DescriptorService.describe(type);
            } catch (DescriptorService.Defect defect) {
                throw new ConstructUnlowered(
                    "value-slice descriptor position over unrepresentable checked type "
                        + typeName(type) + " (" + defect.getMessage() + ")");
            }
        }

        /** {@code STRING_CONCAT} — the template arm with fragment {@code CONST} steps. */
        private ValueId lowerTemplate(TemplateLiteralExpr template) {
            return lowerTemplate(template, null);
        }

        private ValueId lowerTemplate(TemplateLiteralExpr template, ValueId slot) {
            List<ValueId> fragments = new ArrayList<>();
            List<ExpressionNode> parts = template.parts();
            for (int i = 0; i < parts.size(); i++) {
                ExpressionNode part = parts.get(i);
                if (i % 2 == 0) {
                    if (!(part instanceof LiteralExpr literal)
                            || !(literal.value() instanceof LiteralValue.StringLiteral text)) {
                        throw new ConstructUnlowered("template literal part at index " + i
                            + " is not a literal string fragment (the parser alternates "
                            + "literal strings and interpolations — a parser defect)",
                            part.span());
                    }
                    fragments.add(lowerConst(literal));
                } else {
                    fragments.add(lowerOperand(part));
                }
            }
            return emitValueOp(SemanticOpKind.STRING_CONCAT,
                new KindPayload.StringConcatPayload(fragments),
                template.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        // ---------------------------------------------------------------------
        // Emission helpers (D1/D4/D8)
        // ---------------------------------------------------------------------

        /**
         * The checked type fact of an expression (D4: consulted only as a
         * copied checker fact). A missing fact is a producer defect —
         * never an invented op and never a crash.
         */
        private Type checkedType(ExpressionNode expr) {
            Type type = checks.typeMap().get(expr);
            if (type == null) {
                throw new ConstructUnlowered(expr.getClass().getSimpleName()
                    + " without a checked type (a missing checker fact is a producer "
                    + "defect — never an invented op)", expr.span());
            }
            return type;
        }

        /**
         * The checked type of the value one source expression actually
         * produces at a statically typed function-value position (the
         * signature the position's adapter records and validates the
         * consumed source against). Assignment expressions also produce
         * their checked RHS type and original RHS identity: an adapter is
         * committed only to storage, never yielded. A consuming position
         * therefore adapts the yielded RHS independently when necessary.
         */
        private Type producedType(ExpressionNode expr) {
            return checkedType(expr);
        }

        /**
         * Emits one result-less source-positioned USER op (a declaration
         * or structured op of the binding walk: {@code BINDING_ALLOC}/
         * {@code BINDING_INIT}/{@code LOOP}/{@code FOR_EACH}/
         * {@code TRY_CATCH}) and returns its op id — the binding walk's
         * counterpart of {@link #emitValueOp} for ops whose result slot
         * is {@code none}.
         */
        private OpId emitUserNullOp(SemanticOpKind kind, KindPayload payload, Span span,
                                    FailurePolicyId policy) {
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.USER, anchor, chainParents.peek());
            emit(buildOp(opId, kind, payload, null, null, policy, origin));
            return opId;
        }


        private ValueId emitSyntheticValueOp(SemanticOpKind kind, KindPayload payload,
                                             Span span, RuntimeDescriptor resultType,
                                             FailurePolicyId policy) {
            ValueId value = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.SYNTHETIC, anchor, null);
            emit(buildOp(opId, kind, payload, value, resultType, List.of(), List.of(),
                policy, origin));
            return value;
        }

        /** Emits one value-producing USER op without operands and returns its value id. */
        private ValueId emitValueOp(SemanticOpKind kind, KindPayload payload, Span span,
                                    RuntimeDescriptor resultType, FailurePolicyId policy) {
            return emitOperandOp(kind, payload, List.of(), List.of(), span, resultType,
                policy, null);
        }

        /**
         * Emits one value-producing USER op without operands publishing the
         * given result slot ({@code null} allocates one) and returns its
         * value id (the slot when given).
         */
        private ValueId emitValueOp(SemanticOpKind kind, KindPayload payload, Span span,
                                    RuntimeDescriptor resultType, FailurePolicyId policy,
                                    ValueId slot) {
            return emitOperandOp(kind, payload, List.of(), List.of(), span, resultType,
                policy, slot);
        }

        /**
         * Emits one value-producing USER op carrying completed operand
         * values/types in source order (I3: operands complete left-to-right
         * before the op START; the slice never evaluates or re-reads an
         * operand) and returns its value id.
         */
        private ValueId emitOperandOp(SemanticOpKind kind, KindPayload payload,
                                      List<ValueId> operands,
                                      List<RuntimeDescriptor> operandTypes, Span span,
                                      RuntimeDescriptor resultType, FailurePolicyId policy) {
            return emitOperandOp(kind, payload, operands, operandTypes, span, resultType,
                policy, null);
        }


        private ValueId emitOperandOp(SemanticOpKind kind, KindPayload payload,
                                      List<ValueId> operands,
                                      List<RuntimeDescriptor> operandTypes, Span span,
                                      RuntimeDescriptor resultType, FailurePolicyId policy,
                                      ValueId slot) {
            ValueId value = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, kind, payload, value, resultType, operands, operandTypes,
                policy, origin));
            return value;
        }

        /**
         * Emits one value-producing op of the given origin kind publishing
         * the given result slot ({@code null} allocates one) — the
         * test-less FOR condition {@code CONST true} production (a
         * {@code SYNTHETIC} op, the for-statement span).
         */
        private ValueId emitValueOpWith(SemanticOpKind kind, KindPayload payload, Span span,
                                        RuntimeDescriptor resultType, FailurePolicyId policy,
                                        ValueId slot, SourceOriginKind originKind) {
            ValueId value = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span), originKind,
                anchor, currentParent());
            emit(buildOp(opId, kind, payload, value, resultType, List.of(), List.of(),
                policy, origin));
            return value;
        }

        /** Emits one result-less child/terminal op and returns its op id. */
        private OpId emitNullOp(SemanticOpKind kind, KindPayload payload, Span span,
                                FailurePolicyId policy, SourceOriginKind originKind,
                                OpId parent) {
            SemanticOp op = buildNullOp(kind, payload, span, policy, originKind, parent);
            emit(op);
            return op.opId();
        }

        /** Builds (without emitting) one result-less child/terminal op. */
        private SemanticOp buildNullOp(SemanticOpKind kind, KindPayload payload, Span span,
                                       FailurePolicyId policy, SourceOriginKind originKind,
                                       OpId parent) {
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span), originKind,
                anchor, parent);
            return buildOp(opId, kind, payload, null, null, policy, origin);
        }

        /**
         * Builds one op with its wired contract snapshot: the digest is
         * the single canonicalizer's SHA-256 over the snapshot's eight
         * pinned fields (T3), the selector is extracted from
         * selector-carrying payloads, and the behavior-referenced ids
         * live inside the payload itself. Operandless ops (the payload-
         * referenced container shapes) delegate with empty operand lists.
         */
        private SemanticOp buildOp(OpId opId, SemanticOpKind kind, KindPayload payload,
                                   SemanticValue result, OpResultType resultType,
                                   FailurePolicyId policy, SourceOrigin origin) {
            return buildOp(opId, kind, payload, result, resultType, List.of(), List.of(),
                policy, origin);
        }

        /**
         * Builds one op with completed operands and their descriptors in
         * source order plus the wired contract snapshot (I3 value ops):
         * the operand types are part of the digest — a behavior-selecting
         * field, never omitted.
         */
        private SemanticOp buildOp(OpId opId, SemanticOpKind kind, KindPayload payload,
                                   SemanticValue result, OpResultType resultType,
                                   List<ValueId> operands,
                                   List<RuntimeDescriptor> operandTypes,
                                   FailurePolicyId policy, SourceOrigin origin) {
            OperationContractSnapshot placeholder = contractOf(kind, payload, resultType,
                operandTypes, policy, "placeholder");
            String digest = ContractSnapshotCanonicalizer.digest(placeholder);
            OperationContractSnapshot contract = contractOf(kind, payload, resultType,
                operandTypes, policy, digest);
            return new SemanticOp(opId, kind, origin, result, resultType, operands,
                operandTypes, payload, policy, contract);
        }

        private OperationContractSnapshot contractOf(SemanticOpKind kind, KindPayload payload,
                                                     OpResultType resultType,
                                                     List<RuntimeDescriptor> operandTypes,
                                                     FailurePolicyId policy, String digest) {
            return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind,
                resultType, operandTypes,
                payload instanceof KindPayload.SelectorCarrying carrying
                    ? carrying.selector() : null,
                payload, policy, List.of(), digest);
        }

        /** Copies the checked span into the schema-owned span (D4). */
        private SourceSpan toSourceSpan(Span span) {
            return new SourceSpan(span.file(), span.startLine(), span.startColumn(),
                span.endLine(), span.endColumn(), span.startScalarOffset(),
                span.endScalarOffset());
        }

        private static String typeName(Type type) {
            return switch (type) {
                case Type.Array array -> "[" + typeName(array.element()) + "]";
                case Type.Nullable nullable -> "?" + typeName(nullable.inner());
                case Type.Class classType -> new ClassId(
                    DescriptorService.semanticModulePath(classType.identity()),
                    classType.name()).text();
                case Type.Func func -> "function";
                default -> String.valueOf(type);
            };
        }

        /** The foreign-expression description of the E6005 origin. */
        private String describeExpression(ExpressionNode expr) {
            return switch (expr) {
                case deal.ast.CallExpr ignored ->
                    "call expression (only the int()/number() conversion "
                        + "intrinsic calls lower in this slice — "
                        + "INTRINSIC_CALL is the I3 terminal-check arm; the CALL "
                        + "machinery is E7's)";
                case deal.ast.IndexExpr ignored ->
                    "index access expression (INDEX_NORMALIZE/INDEX_READ are E5's, "
                        + "ISSUE-0234)";
                case deal.ast.FunctionExpr ignored ->
                    "function expression (CLOSURE_NEW is E6's, ISSUE-0235)";
                case deal.ast.HasExpr ignored ->
                    "has expression (HAS_FIELD is E9's, ISSUE-0238)";
                case deal.ast.AwaitExpression ignored ->
                    "await expression (ASYNC_START/AWAIT are E7's)";
                default -> expr.getClass().getSimpleName() + " expression";
            };
        }

        /** The foreign-statement description of the E6005 origin. */
        private String describeStatement(StatementNode statement) {
            return switch (statement) {
                case deal.ast.FunctionDeclaration ignored ->
                    "function declaration (CLOSURE_NEW/RECURSIVE_GROUP_INIT are E6's, "
                        + "ISSUE-0235)";
                case deal.ast.VariableDeclaration ignored ->
                    "variable declaration (BINDING_ALLOC/INIT are E6's, ISSUE-0235)";
                case deal.ast.ReturnStatement ignored ->
                    "return statement (RETURN is E7's)";
                case deal.ast.IfStatement ignored ->
                    "if statement (BRANCH(IF) is the E5 control-flow arm)";
                case deal.ast.WhileStatement ignored ->
                    "while statement (LOOP(WHILE) is the E5 control-flow arm)";

                case deal.ast.ExpressionStatement ignored ->
                    "expression statement (DISCARD is the E5 control-flow arm)";
                case deal.ast.ImportDeclaration ignored ->
                    "import declaration (IMPORT_EXPORT_ENTRY is E10's)";
                case deal.ast.ExportDeclaration ignored ->
                    "export declaration (EXPORT_* is E10's)";
                case deal.ast.ClassDeclaration ignored ->
                    "class declaration (class layouts and CLASS_NEW are E9's)";
                case deal.ast.TryStatement ignored ->
                    "try statement (TRY_CATCH is the E5 control-flow arm)";
                case deal.ast.ThrowStatement ignored ->
                    "throw statement (THROW is the E5 control-flow arm)";
                case deal.ast.BreakStatement ignored ->
                    "break statement (matching loop-ID transfer is the E5 control-flow arm)";
                case deal.ast.ContinueStatement ignored ->
                    "continue statement (matching loop-ID transfer is the E5 control-flow arm)";
                default -> statement.getClass().getSimpleName() + " statement";
            };
        }
    }
}
