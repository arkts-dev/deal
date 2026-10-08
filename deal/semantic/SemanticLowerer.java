package deal.semantic;

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
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
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
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalModuleIdentity;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.ExternalAsyncLink;
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
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.DeleteTargetKind;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.IntrinsicKind;
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

    public static final String INT32_LITERAL_OUT_OF_RANGE = "INT32_LITERAL_OUT_OF_RANGE";

    /**
     * The fact-defect identifier of the E6005 profile guard (I3): a
     * lowering request whose invocation profile is not
     * {@code DEAL_V1_2_INT32} is rejected before any op is built —
     * {@code LEGACY_SAFE_INT} is inspectable for routing/regression but
     * never lowered (the validator's R-PROFILE and
     * {@link LoweredModuleUnit}'s constructor guard are the backstops).
     * The detail carries the offending profile, capability
     * {@code FOUNDATION_VALUES}, and the {@code SemanticLowerer} origin.
     */
    public static final String LOWER_LEGACY_PROFILE_REJECTED =
        "LOWER_LEGACY_PROFILE_REJECTED";

    /**
     * The pinned canonical realization id of every lowerer-created
     * boundary child (D3): {@code RuntimeValidation("runtime-validation")}
     * — a pinned constant inside the contract digest, so units and dumps
     * are byte-identical, and E4's report-completion predicate carries it
     * unchanged.
     */
    public static final String CANONICAL_RUNTIME_VALIDATION_ID = "runtime-validation";

    public static final long INITIAL_LOOP_GENERATION = 0L;

    public static final String CLASS_DEFAULT_CAPTURE = "CLASS_DEFAULT_CAPTURE";

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

    /**
     * One incarnation fact of the binding-core walk: the static
     * per-binding generation ordinal (assigned in lowering order starting
     * at 0), the block the incarnation's producing allocation sits in,
     * the default cell kind ({@code DIRECT} everywhere except the pinned
     * special cases — for-let per-iteration incarnations and
     * {@code FOR_EACH} iteration bindings are {@code SHARED_CELL}), the
     * reassignability fact recorded independently of the cell kind, the
     * producing-allocation kind, and the pinned-shared-cell marker of the
     * closed special cases. The <em>final</em> cell kind of an incarnation
     * is derived by {@link CellKindDerivation} over the union of the
     * pinned special cases and the registered capture references — every
     * {@code BINDING_ALLOC} payload and every facts-surface cell kind
     * flows through that one derivation.
     */
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

    public static final class ConstructUnlowered extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The human-readable construct description (pinned in the diagnostic's origin). */
        private final String construct;

        public ConstructUnlowered(String construct) {
            super("a construct without a lowering arm reached this stage: " + construct);
            this.construct = Objects.requireNonNull(construct, "construct must not be null");
        }

        /** The construct description carried into the E6005 origin. */
        public String construct() {
            return construct;
        }
    }

    public static final class ClassDefaultCapture extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The referenced enclosing-region name carried into the E6005 origin. */
        private final String name;

        public ClassDefaultCapture(String name) {
            super("default-block free reference '" + name
                + "' resolves to an enclosing-region binding (a detached "
                + "CLASS_DEFAULT block admits only block-internal allocations and "
                + "module-level bindings resolved through the module-init block; the "
                + "closed payload records no captures)");
            this.name = Objects.requireNonNull(name, "name must not be null");
        }

        /** The referenced enclosing-region name carried into the E6005 origin. */
        public String name() {
            return name;
        }
    }

    public static final class RetainedAbiDeferred extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The imported class identity carried into the E6005 origin. */
        private final String classId;

        public RetainedAbiDeferred(String classId) {
            super("class-typed literal of imported class " + classId
                + " whose owner carries no shared factory facts (the "
                + "RETAINED_ABI derivation is E10's, ISSUE-0239)");
            this.classId = Objects.requireNonNull(classId, "classId must not be null");
        }

        /** The imported class identity carried into the E6005 origin. */
        public String classId() {
            return classId;
        }
    }

    public static final class IntLiteralOutOfRange extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The out-of-range literal value carried into the E6005 origin. */
        private final long value;

        public IntLiteralOutOfRange(long value) {
            super("int literal " + value
                + " outside signed32 [-2147483648, 2147483647] (the E1036 "
                + "signed32 literal gate is ISSUE-0111's frontend item; this "
                + "stage fails closed, never truncates)");
            this.value = value;
        }

        /** The out-of-range literal value carried into the E6005 origin. */
        public long value() {
            return value;
        }
    }

    public static LoweringFailureDetail loweringFailureDetail(ModuleId module,
                                                              RuntimeException defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        if (defect instanceof ConstructUnlowered unlowered) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.CONTAINERS_AND_STRINGS, CONSTRUCT_UNLOWERED,
                SemanticProfile.DEAL_V1_2_INT32, LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer " + CONSTRUCT_UNLOWERED + " (" + unlowered.construct() + ")");
        }
        if (defect instanceof IntLiteralOutOfRange outOfRange) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.SIGNED_INT32, INT32_LITERAL_OUT_OF_RANGE,
                SemanticProfile.DEAL_V1_2_INT32, LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer " + INT32_LITERAL_OUT_OF_RANGE + " ("
                    + outOfRange.getMessage() + ")");
        }
        if (defect instanceof ClassDefaultCapture capture) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.CLASSES, CLASS_DEFAULT_CAPTURE,
                SemanticProfile.DEAL_V1_2_INT32, LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer " + CLASS_DEFAULT_CAPTURE + " (" + capture.getMessage() + ")");
        }
        if (defect instanceof RetainedAbiDeferred deferred) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.CLASSES, RETAINED_ABI_DEFERRED,
                SemanticProfile.DEAL_V1_2_INT32, LoweredModuleUnit.FORMAT_VERSION,
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
     * The I3 profile guard's rejection result: a non-DEAL_V1_2_INT32
     * lowering request produces no unit and no partial session state.
     */
    private static LoweringResult legacyProfileRejection(
            CheckedModuleInput module, SemanticProfile profile) {
        return new LoweringResult(null, null, List.of(FailureContractRegistry.e6005(
            new LoweringFailureDetail(module.moduleId().path(),
                SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer"))));
    }

    /**
     * The first two closed gates of a produced unit (the 14-rule unit
     * validator and the address-chain protocol, in order): empty on
     * pass, otherwise the first E6005 as a failed {@link LoweringResult}.
     */
    private static Optional<LoweringResult> validateUnitGates(
            LoweredModuleUnit unit, String interfaceHash,
            String capabilityRegistryHash) {
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
                                                 String interfaceHash,
                                                 String capabilityRegistryHash) {
        Optional<LoweringResult> gates = validateUnitGates(unit, interfaceHash,
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
     */
    public record LoweringResult(LoweredModuleUnit unit, StructuredBodyTable table,
                                 List<CompilerDiagnostic> diagnostics) {

        public LoweringResult {
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
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
                                             deal.semantic.ir.ClassFactoryRegistry registry,
                                             deal.semantic.ir.JsonDefaultChildTable jsonDefaults) {

        public ClassDeclarationCoreResult {
            Objects.requireNonNull(lowering, "lowering must not be null");
            Objects.requireNonNull(registry, "registry must not be null");
            Objects.requireNonNull(jsonDefaults, "jsonDefaults must not be null");
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

    public record CatchFrame(String name, BindingId binding) {

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
        // I3 profile guard: the rejection runs before any op is built and
        // before any id is allocated — a LEGACY_SAFE_INT lowering request
        // produces no unit and no partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return legacyProfileRejection(module, profile);
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator);
        try {
            lowerer.lowerStatements(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered))));
        } catch (IntLiteralOutOfRange outOfRange) {
            return new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange))));
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect))));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash);
        // E5 production-time checks (A-D1/C-D2): the closed validator,
        // the address-chain protocol, and the block-membership table's
        // control-flow validator, in order; the first violation is the
        // returned E6005.
        return finishLowering(lowerer, unit, interfaceHash, capabilityRegistryHash);
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
        // I3 profile guard: identical to lowerModule — a non-DEAL_V1_2_INT32
        // lowering request produces no unit and no partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new BindingCoreResult(legacyProfileRejection(module, profile),
                BindingCoreFacts.empty());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, module.ast().span());
        try {
            lowerer.lowerBindingModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
                lowerer.bindingFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<LoweringResult> gates = validateUnitGates(unit, interfaceHash,
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
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return legacyProfileRejection(module, profile);
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, module.ast().span(), true);
        lowerer.setModuleImports(module.imports());
        try {
            lowerer.seedIntrinsicBindings();
            lowerer.hoistModuleLevelAllocs(module.ast().statements());
            lowerer.statementWalk.walk(module.ast().statements(), true);
            lowerer.emitEntryMainCall();

            lowerer.finalizeInvocationIdentities();
            lowerer.finalizeCellKinds();
        } catch (ConstructUnlowered unlowered) {
            return new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered))));
        } catch (IntLiteralOutOfRange outOfRange) {
            return new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange))));
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect))));
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect)));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        return finishLowering(lowerer, unit, interfaceHash, capabilityRegistryHash);
    }

    public static FullProgramE7Result lowerModuleFullProgramE7(CheckedModuleInput module,
            SemanticProfile profile,
            Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
            String interfaceHash, String capabilityRegistryHash,
            SemanticIdAllocator allocator, Map<ModuleId, ModuleRoute> calleeRoutes,
            Map<ModuleId, Map<String, OpId>> calleeExternalEntries,
            Set<String> callbackExports) {
        requireLoweringInputs(module, profile, constructCoverage, interfaceHash,
            capabilityRegistryHash, allocator);
        Objects.requireNonNull(calleeRoutes, "calleeRoutes must not be null");
        Objects.requireNonNull(calleeExternalEntries, "calleeExternalEntries must not be null");
        Objects.requireNonNull(callbackExports, "callbackExports must not be null");
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new FullProgramE7Result(legacyProfileRejection(module, profile),
                Map.of(), Map.of());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, module.ast().span(), true);
        lowerer.setModuleImports(module.imports());
        lowerer.setE7Facts(module.exports(), calleeRoutes, calleeExternalEntries,
            callbackExports);
        try {
            lowerer.seedIntrinsicBindings();
            lowerer.hoistModuleLevelAllocs(module.ast().statements());
            lowerer.statementWalk.walk(module.ast().statements(), true);
            lowerer.emitE7Terminals();

            lowerer.finalizeInvocationIdentities();
            lowerer.finalizeCellKinds();
        } catch (ConstructUnlowered unlowered) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                Map.of(), Map.of());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                Map.of(), Map.of());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
                Map.of(), Map.of());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new FullProgramE7Result(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                Map.of(), Map.of());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        return new FullProgramE7Result(
            finishLowering(lowerer, unit, interfaceHash, capabilityRegistryHash),
            lowerer.recordedEntries(), lowerer.recordedCallbacks());
    }

    public static final String DECLARATION_SURFACE_INCOMPLETE =
        "DECLARATION_SURFACE_INCOMPLETE";

    public static final String DECLARATION_KIND_MISMATCH =
        "DECLARATION_KIND_MISMATCH";

    public static final String PROJECT_INPUT_INCOMPLETE =
        "PROJECT_INPUT_INCOMPLETE";

    public static ProjectLoweringResult lowerProject(
            CompilerInvocation invocation,
            CheckedProjectInput checkedProject,
            deal.semantic.ir.ProjectInterfaceIndex interfaceIndex,
            List<SemanticRequirementManifest> requirementManifests,
            HostDeclarationSurface declarationSurface,
            Map<ModuleId, CanonicalModuleIdentity> declarationModuleIdentities,
            Map<ModuleId, FfiGeneratedModule> externCModules,
            BuiltinErrorDeclaration builtinError,
            List<IntrinsicKind> conversionIntrinsics,
            Set<String> callbackExports) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(checkedProject, "checkedProject must not be null");
        Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
        Objects.requireNonNull(requirementManifests,
            "requirementManifests must not be null");
        Objects.requireNonNull(declarationSurface, "declarationSurface must not be null");
        Objects.requireNonNull(declarationModuleIdentities,
            "declarationModuleIdentities must not be null");
        Objects.requireNonNull(externCModules, "externCModules must not be null");
        Objects.requireNonNull(builtinError, "builtinError must not be null");
        Objects.requireNonNull(conversionIntrinsics,
            "conversionIntrinsics must not be null");
        Objects.requireNonNull(callbackExports, "callbackExports must not be null");

        // (1) The profile guard: before any id is allocated and before any
        // op is built. A non-DEAL_V1_2_INT32 invocation fails closed here.
        if (invocation.semanticProfile() != SemanticProfile.DEAL_V1_2_INT32) {
            return projectFailure(checkedProject.entryModule(),
                SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                invocation.semanticProfile(), "SemanticLowerer.lowerProject");
        }
        String interfaceHash = interfaceIndex.interfaceIndexDigest();
        String capabilityRegistryHash = invocation.capabilityRegistryHash();
        SemanticIrValidator.ComparisonFacts comparisonFacts =
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash);

        // (2) The declaration-fact agreement: every declaration import of
        // the closure is covered by the surface (a declaration module's
        // class literal would otherwise defer silently) and the surface's
        // declaration kind agrees with the extern-C generated metadata (the
        // seeds' owner member derives from exactly that pair).
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
        for (ModuleId declarationModule : declarationSurface.moduleIds()) {
            HostDeclarationSurface.DeclarationFacts facts =
                declarationSurface.require(declarationModule);
            boolean externC = externCModules.containsKey(declarationModule);
            if (externC
                    != (facts.kind()
                        == HostDeclarationSurface.DeclarationKind.EXTERN_C)) {
                return projectFailure(declarationModule,
                    SemanticCapability.CLASSES, DECLARATION_KIND_MISMATCH,
                    invocation.semanticProfile(),
                    "the declaration surface classifies '" + declarationModule.path()
                        + "' as " + facts.kind() + " but the extern-C generated"
                        + " metadata " + (externC ? "is present" : "is absent")
                        + " for it (the seeds' owner member derives from exactly"
                        + " this pair)");
            }
        }

        // (2) The class registration seeds, produced before the first unit
        // walk: one layout per declared class of every declaration module
        // plus the compiler-owned builtin Error entry.
        ClassRegistrationSeeds.Production seeds = ClassRegistrationSeeds.produce(
            declarationSurface, declarationModuleIdentities, externCModules,
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
        Map<ModuleId, ModuleRoute> closureRoutes = new LinkedHashMap<>();
        for (ModuleId module : closureOrder) {
            closureRoutes.put(module, ModuleRoute.SHARED);
        }

        Map<ModuleId, LoweredModuleUnit> units = new LinkedHashMap<>();
        Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
        Map<ModuleId, deal.semantic.ir.ClassFactoryRegistry> registries =
            new LinkedHashMap<>();
        Map<ModuleId, Map<String, OpId>> calleeExternalEntries = new LinkedHashMap<>();

        Map<ClassId, SharedFactoryFacts> inProjectFactoryFacts =
            new LinkedHashMap<>();

        // (4b) The declared-parameter-annotation index of the one lowering
        // (canonical failure-projection authority P3): built once from the
        // checked project's declarations before the module walk, so every
        // declaration-owned parameter boundary carries the callee's declared
        // annotation span rather than the call site.
        Map<ModuleId, DeclaredParameterAnnotations> declaredParameterAnnotations =
            declaredParameterAnnotationIndex(checkedProject);

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
                interfaceHash, capabilityRegistryHash, allocator, closureRoutes,
                calleeExternalEntries, callbackExports, seeds.seeds(),
                conversionIntrinsics, ownInterface, comparisonFacts,
                inProjectFactoryFacts, declaredParameterAnnotations);
            if (lowered.hasErrors()) {
                return projectFailureResult(lowered.diagnostics());
            }
            Optional<CompilerDiagnostic> namespaceFailure =
                namespaceRecorder.record(lowered.unit());
            if (namespaceFailure.isPresent()) {
                return projectFailureResult(List.of(namespaceFailure.get()));
            }
            units.put(module.moduleId(), lowered.unit());
            tables.put(module.moduleId(), lowered.table());
            registries.put(module.moduleId(), lowered.registry());
            if (!lowered.externalEntries().isEmpty()) {
                calleeExternalEntries.put(module.moduleId(), lowered.externalEntries());
            }
            accumulateInProjectFactoryFacts(ownInterface, lowered,
                inProjectFactoryFacts);
        }

        // (5) The project-form gate over the complete closure.
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, interfaceIndex, units,
            checkedProject.entryModule());
        Optional<CompilerDiagnostic> projectGate = SemanticIrValidator.validate(
            project, comparisonFacts);
        if (projectGate.isPresent()) {
            return projectFailureResult(List.of(projectGate.get()));
        }
        NamespaceRegistrations.Assembly namespaceAssembly = namespaceRecorder.build();
        return new ProjectLoweringResult(project, tables, registries, seeds.seeds(),
            namespaceAssembly.registrations(), List.of());
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
            String interfaceHash,
            String capabilityRegistryHash,
            SemanticIdAllocator allocator,
            Map<ModuleId, ModuleRoute> closureRoutes,
            Map<ModuleId, Map<String, OpId>> calleeExternalEntries,
            Set<String> callbackExports,
            ClassRegistrationSeeds seeds,
            List<IntrinsicKind> conversionIntrinsics,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            SemanticIrValidator.ComparisonFacts comparisonFacts,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ModuleId, DeclaredParameterAnnotations> declaredParameterAnnotations) {
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, false, false, false,
            module.ast().span(), true, true, ownInterface,
            Map.copyOf(sharedFactories));
        lowerer.setDeclaredParameterAnnotations(declaredParameterAnnotations);
        lowerer.setModuleImports(module.imports());
        lowerer.setRegistrationSeeds(seeds);
        lowerer.setDeclaredConversionIntrinsics(conversionIntrinsics);
        lowerer.setE7Facts(module.exports(), closureRoutes, calleeExternalEntries,
            callbackExports);
        try {
            lowerer.lowerProjectModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return LoweredProjectModule.failure(List.of(FailureContractRegistry.e6005(
                loweringFailureDetail(module.moduleId(), unlowered))));
        } catch (IntLiteralOutOfRange outOfRange) {
            return LoweredProjectModule.failure(List.of(FailureContractRegistry.e6005(
                loweringFailureDetail(module.moduleId(), outOfRange))));
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return LoweredProjectModule.failure(List.of(FailureContractRegistry.e6005(
                loweringFailureDetail(module.moduleId(), defect))));
        } catch (ComparisonSelectorLowering.Defect defect) {
            return LoweredProjectModule.failure(List.of(
                ComparisonSelectorLowering.e6005(module.moduleId(), defect)));
        } catch (ClassDefaultCapture capture) {
            return LoweredProjectModule.failure(List.of(FailureContractRegistry.e6005(
                loweringFailureDetail(module.moduleId(), capture))));
        } catch (RetainedAbiDeferred deferred) {
            return LoweredProjectModule.failure(List.of(FailureContractRegistry.e6005(
                loweringFailureDetail(module.moduleId(), deferred))));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(manifest.constructCoverage(),
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E9_GATE_ACTIVATION);
        StructuredBodyTable table = lowerer.bodyTable();
        Optional<CompilerDiagnostic> unitChain = validateProjectUnit(unit, table,
            comparisonFacts, lowerer.pinnedWriteFacts(), lowerer.factoryRegistry(),
            lowerer.jsonDefaultChildren(), ownInterface, Map.copyOf(sharedFactories),
            seeds.registrations());
        if (unitChain.isPresent()) {
            return LoweredProjectModule.failure(List.of(unitChain.get()));
        }
        return new LoweredProjectModule(unit, table, lowerer.factoryRegistry(),
            lowerer.jsonDefaultChildren(), lowerer.recordedEntries(),
            lowerer.pinnedWriteFacts(), List.of());
    }

    private static void accumulateInProjectFactoryFacts(
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            LoweredProjectModule lowered,
            Map<ClassId, SharedFactoryFacts> accumulated) {
        for (deal.semantic.ir.ClassInterface classEntry : ownInterface.classes()) {
            deal.semantic.ir.ClassLayout layout =
                lowered.unit().classLayouts().get(classEntry.classId());
            if (layout == null) {
                continue;
            }
            OpId factoryOpId =
                lowered.registry().factoryFor(classEntry.constructionEntry());
            if (factoryOpId == null) {
                continue;
            }
            SemanticOp factoryOp = opOf(lowered.unit(), factoryOpId);
            if (factoryOp == null || !(factoryOp.result() instanceof ValueId result)) {
                continue;
            }
            accumulated.put(classEntry.classId(), new SharedFactoryFacts(
                classEntry.classId(), classEntry, layout, factoryOpId, result));
        }
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
            BindingsProductionValidator.PinnedWriteFacts pinnedWrites,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.JsonDefaultChildTable jsonDefaults,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories) {
        return validateProjectUnit(unit, table, facts, pinnedWrites, factories,
            jsonDefaults, ownInterface, sharedFactories, Map.of());
    }

    public static Optional<CompilerDiagnostic> validateProjectUnit(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            SemanticIrValidator.ComparisonFacts facts,
            BindingsProductionValidator.PinnedWriteFacts pinnedWrites,
            deal.semantic.ir.ClassFactoryRegistry factories,
            deal.semantic.ir.JsonDefaultChildTable jsonDefaults,
            deal.semantic.ir.ExternalModuleInterface ownInterface,
            Map<ClassId, SharedFactoryFacts> sharedFactories,
            Map<ClassId, ClassRegistrationSeeds.ClassRegistration> declarationClasses) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(facts, "facts must not be null");
        Objects.requireNonNull(pinnedWrites, "pinnedWrites must not be null");
        Objects.requireNonNull(factories, "factories must not be null");
        Objects.requireNonNull(jsonDefaults, "jsonDefaults must not be null");
        Objects.requireNonNull(ownInterface, "ownInterface must not be null");
        Objects.requireNonNull(sharedFactories, "sharedFactories must not be null");
        Objects.requireNonNull(declarationClasses,
            "declarationClasses must not be null");
        Optional<CompilerDiagnostic> validation =
            SemanticIrValidator.validate(unit, facts);
        if (validation.isPresent()) {
            return validation;
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return chainShape;
        }
        Optional<CompilerDiagnostic> controlFlow =
            ControlFlowValidator.validate(unit, table);
        if (controlFlow.isPresent()) {
            return controlFlow;
        }
        Optional<CompilerDiagnostic> bindings = BindingsProductionValidator.validate(
            unit, table, pinnedWrites);
        if (bindings.isPresent()) {
            return bindings;
        }
        return ClassConstructionValidator.validate(unit, table, factories,
            jsonDefaults, ownInterface, sharedFactories, declarationClasses);
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
            tables = Map.copyOf(tables);
            registries = Map.copyOf(registries);
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

    private record LoweredProjectModule(
            LoweredModuleUnit unit,
            StructuredBodyTable table,
            deal.semantic.ir.ClassFactoryRegistry registry,
            deal.semantic.ir.JsonDefaultChildTable jsonDefaults,
            Map<String, OpId> externalEntries,
            BindingsProductionValidator.PinnedWriteFacts pinnedWrites,
            List<CompilerDiagnostic> diagnostics) {

        LoweredProjectModule {
            diagnostics = List.copyOf(diagnostics);
        }

        boolean hasErrors() {
            return !diagnostics.isEmpty();
        }

        static LoweredProjectModule failure(List<CompilerDiagnostic> diagnostics) {
            return new LoweredProjectModule(null, null, null, null, Map.of(),
                BindingsProductionValidator.PinnedWriteFacts.empty(), diagnostics);
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
        // I3 profile guard: identical to lowerModuleBindingCore — a
        // non-DEAL_V1_2_INT32 lowering request produces no unit and no
        // partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty(), List.of());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, module.ast().span());
        try {
            lowerer.lowerBindingModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ClosureCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
        // I3 profile guard: identical to lowerModuleClosureCore — a
        // non-DEAL_V1_2_INT32 lowering request produces no unit and no
        // partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty(), List.of(), List.of());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new GroupCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
        // I3 profile guard: identical to lowerModuleGroupCore — a
        // non-DEAL_V1_2_INT32 lowering request produces no unit and no
        // partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty(), List.of(), List.of(),
                BindingImmutabilityAnalysis.BindingImmutabilityFacts.empty());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new ImmutabilityCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
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
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
        // I3 profile guard: identical to lowerModuleImmutabilityCore — a
        // non-DEAL_V1_2_INT32 lowering request produces no unit and no
        // partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty(), List.of(), List.of(),
                BindingImmutabilityAnalysis.BindingImmutabilityFacts.empty(),
                AdapterCreationRule.CreationRuleFacts.empty());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, true, module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new CreationRuleCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
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
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
        // I3 profile guard: identical to lowerModuleCreationRuleCore — a
        // non-DEAL_V1_2_INT32 lowering request produces no unit and no
        // partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty(), List.of(), List.of(),
                BindingImmutabilityAnalysis.BindingImmutabilityFacts.empty(),
                AdapterCreationRule.CreationRuleFacts.empty(), ShapeMapFacts.empty());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, true, true,
            module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new ShapeMapCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
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
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
        // I3 profile guard: identical to lowerModuleShapeMapCore — a
        // non-DEAL_V1_2_INT32 lowering request produces no unit and no
        // partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty(), List.of(), List.of(),
                BindingImmutabilityAnalysis.BindingImmutabilityFacts.empty(),
                AdapterCreationRule.CreationRuleFacts.empty(), ShapeMapFacts.empty());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, true, true, true,
            module.ast().span());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts(), lowerer.closureFacts(), lowerer.groupFacts(),
                lowerer.proofFacts(), lowerer.creationRuleFacts(), lowerer.shapeMapFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new ValidationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
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
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
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
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, true, true, false, false, false,
            module.ast().span(), false, true, ownInterface, sharedFactories);
        lowerer.setModuleImports(module.imports());
        try {
            lowerer.lowerGroupModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        } catch (IntLiteralOutOfRange outOfRange) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        } catch (ClassDefaultCapture capture) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), capture)))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        } catch (RetainedAbiDeferred deferred) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), deferred)))),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E9_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(validation.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }
        Optional<CompilerDiagnostic> controlFlow =
            ControlFlowValidator.validate(unit, lowerer.bodyTable());
        if (controlFlow.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(controlFlow.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }
        Optional<CompilerDiagnostic> bindings =
            BindingsProductionValidator.validate(unit, lowerer.bodyTable(),
                lowerer.pinnedWriteFacts());
        if (bindings.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(bindings.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }

        Optional<CompilerDiagnostic> construction =
            ClassConstructionValidator.validate(unit, lowerer.bodyTable(),
                lowerer.factoryRegistry(), lowerer.jsonDefaultChildren(),
                ownInterface, sharedFactories);
        if (construction.isPresent()) {
            return new ClassDeclarationCoreResult(new LoweringResult(null, null,
                List.of(construction.get())),
                new deal.semantic.ir.ClassFactoryRegistry(Map.of()),
                new deal.semantic.ir.JsonDefaultChildTable(Map.of()));
        }
        return new ClassDeclarationCoreResult(
            new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.factoryRegistry(),
            lowerer.jsonDefaultChildren());
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
         * {@code TRY_CATCH} catch variables. A {@code BINDING_LOAD} of a
         * frame's name carries the frame's binding and the pinned
         * initial generation; the try/catch arm pushes exactly one frame
         * while lowering the catch block (binding init mechanics E6).
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
         * The seeded first-class intrinsic function-value identities by
         * intrinsic name ({@code int}/{@code number} — the values the
         * intrinsic bindings' INIT operands commit at module-init top):
         * the VALUE-over-intrinsic operand of the shape-map child (B7
         * arm (a) — an intrinsic function value is a materialized
         * function-value operand; the adapter retains that identity and
         * the adapter creation site emits no function-typed load of the
         * intrinsic binding, because the intrinsic arm selects VALUE over
         * the seeded identity itself).
         */
        private final Map<String, ValueId> intrinsicIdentities = new LinkedHashMap<>();
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

        private ClassRegistrationSeeds registrationSeeds =
            ClassRegistrationSeeds.builtinErrorOnly();

        private List<IntrinsicKind> declaredConversionIntrinsics = List.of();

        private final java.util.LinkedHashMap<ClassId, deal.semantic.ir.ClassLayout>
            classLayouts = new java.util.LinkedHashMap<>();

        private final java.util.LinkedHashMap<deal.semantic.ir.ClassFactoryId, OpId>
            factoryRegistry = new java.util.LinkedHashMap<>();

        private final java.util.LinkedHashMap<OpId, List<OpId>> jsonDefaultChildren =
            new java.util.LinkedHashMap<>();

        private record ClassDefaultFact(OpId opId, ValueId result) {

            private ClassDefaultFact {
                Objects.requireNonNull(opId, "opId must not be null");
                Objects.requireNonNull(result, "result must not be null");
            }
        }

        private final java.util.LinkedHashMap<ClassId,
            java.util.LinkedHashMap<String, ClassDefaultFact>> classDefaults =
            new java.util.LinkedHashMap<>();

        private record DefaultContext(BlockId block, Set<BlockId> internalBlocks,
                                      int entryCaptureDepth) {

            private DefaultContext {
                Objects.requireNonNull(block, "block must not be null");
                Objects.requireNonNull(internalBlocks, "internalBlocks must not be null");
                if (entryCaptureDepth < 0) {
                    throw new IllegalArgumentException(
                        "entryCaptureDepth must be >= 0, got " + entryCaptureDepth);
                }
            }
        }
        /**
         * The open default-block walks of the session, innermost first
         * (class-core mode).
         */
        private final ArrayDeque<DefaultContext> defaultContexts = new ArrayDeque<>();
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
        private Map<ModuleId, ModuleRoute> calleeRoutes = Map.of();
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
        /**
         * The binding environment's scope frames (binding-core mode),
         * innermost first: each frame maps a declared name to the
         * incarnation dominant <em>within that frame</em> — the frame
         * entry snapshots the dominant incarnation of the name at frame
         * entry, a registration during the frame replaces the entry, and
         * resolution walks the frames innermost-first. This keeps the
         * for-let's update block resolving the generation-0 counter after
         * the body frame (whose entry names generation 1) is popped.
         * Every cell ever registered stays recorded in
         * {@link #bindingCells}.
         */
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
        /**
         * The registered binding cells by {@link BindingId}: a binding's
         * multiple incarnations (the for-let counter and its per-iteration
         * incarnation share one identity) append to exactly one cell.
         */
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
            if (!defaultContexts.isEmpty()) {

                defaultContexts.peek().internalBlocks().add(block);
            }
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

        /**
         * Whether one expression is the literal boolean {@code true} —
         * the source {@code while (true)} and {@code for (; true; …)}
         * condition of the closed terminator analysis. A test-less
         * {@code for (;;)} carries no condition expression; its synthetic
         * {@code CONST true} production makes it literal-true by
         * construction (see {@link #lowerForStatement}).
         */
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
            seedIntrinsicBindings();
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
            seedIntrinsicBindings();
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
            seedIntrinsicBindings();
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
         * The walk's pinned-write binding facts (B1/B9): the parameter
         * and import-alias bindings the session recorded during the
         * walk — the checker-fact arms
         * {@code BindingsProductionValidator.BINDING_INIT_ONCE}
         * consumes (the unit payload alone cannot distinguish these two
         * pinned-write cells).
         *
         */
        public BindingsProductionValidator.PinnedWriteFacts pinnedWriteFacts() {
            return new BindingsProductionValidator.PinnedWriteFacts(parameterBindings,
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
         * Seeds the {@code int}/{@code number}/{@code bytes} intrinsic
         * bindings at module-init top (B1): exactly the root
         * {@code Symbol.IntrinsicSymbol} bindings of those three names
         * (shadowed-by-declaration names resolve to the user declaration,
         * never the intrinsic — the checker removes the root binding
         * before defining the shadow, so the symbol fact decides). Each
         * intrinsic binding gets one ALLOC (generation 0, {@code DIRECT},
         * not mutable) and one INIT whose operand is the pinned
         * intrinsic-function-value identity: a dedicated {@code ValueId}
         * allocated in the pinned order. No closed op produces an
         * intrinsic function value — the values seam has no
         * intrinsic-value arm and this child never implements
         * expression-value lowering — so the identity is wired as the
         * INIT operand and materialized by the invocation layer (E7); the
         * child emits no placeholder op, never a {@code CONST}, never a
         * closure.
         *
         * <p>The seeded identity also carries the closed
         * {@code IntrinsicFunction} registration ({@code kind} plus the
         * intrinsic's declared signature) through the registry child's
         * seed entry point, so the key is registered exactly once and
         * the bindings production validator's closed admission admits
         * exactly the seed's {@code BINDING_INIT} as its producing
         * position. The seed's incarnation is added to the static
         * function-identity tracking (J1), so a function-typed load of
         * the intrinsic binding publishes the producer-less seeded
         * identity unchanged — the same identity-preservation rule every
         * function-typed load follows — and every alias load republishes
         * the same identity. No further registration exists for that
         * load: its registration is the seed's own
         * {@code IntrinsicFunction}, keyed by the identity the load
         * republishes.</p>
         */
        private void seedIntrinsicBindings() {

            List<IntrinsicKind> declared = declaredConversionIntrinsics.isEmpty()
                ? List.of(IntrinsicKind.values()) : declaredConversionIntrinsics;
            for (String name : List.of("int", "number", "bytes")) {
                if (!(checks.symbolTable().resolve(name)
                        instanceof Symbol.IntrinsicSymbol intrinsic)) {
                    continue;
                }
                IntrinsicKind kind = conversionIntrinsicKind(intrinsic.name());
                if (kind == null) {
                    throw new IllegalStateException("the intrinsic seed resolved '"
                        + name + "' to the non-conversion intrinsic '"
                        + intrinsic.name() + "' (producer defect)");
                }
                if (!declared.contains(kind)) {
                    throw new ConstructUnlowered("the module's seeded intrinsic '"
                        + name + "' is not part of the compilation's declared"
                        + " intrinsic set " + declared + " (a mismatch between"
                        + " the module's checker facts and the compiler constants is a"
                        + " producer defect)");
                }
                BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                    false, BindingProducer.BINDING_ALLOC, false);
                registerBinding(name, binding, incarnation);
                if (proofAnalysis) {
                    // B7: intrinsic bindings (int/number/bytes) always carry
                    // the proof — builtin, unassignable — regardless of
                    // any assignment fact.
                    assignmentAnalysis.markIntrinsic(binding);
                }
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(binding, moduleInitBlock, false,
                        cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                    moduleInitSpan(), FailurePolicyId.NO_DEAL_FAILURE);
                ValueId intrinsicValue = ids.nextValueId(module, nextOrdinal++, 0);
                // The seeded intrinsic identity is the shape-map child's
                // VALUE-over-intrinsic operand (B7 arm (a)); it is
                // retained here so the adapted emission wires the exact
                // identity the intrinsic binding's cell holds.
                intrinsicIdentities.put(name, intrinsicValue);
                // The seed's closed registration: exactly one
                // IntrinsicFunction binding keyed by the seeded
                // identity, with the intrinsic's declared signature as
                // the checker's own symbol declares it (the closed gate
                // pins it against the kind's declared signature).
                registry.registerIntrinsic(
                    new FunctionAllocationIdentity(intrinsicValue.id()), kind,
                    (RuntimeDescriptor.Func) DescriptorService.describe(intrinsic.type()));

                functionIdentity.put(incarnation, intrinsicValue);
                emitUserNullOp(SemanticOpKind.BINDING_INIT,
                    new KindPayload.BindingInitPayload(binding, INITIAL_LOOP_GENERATION,
                        intrinsicValue),
                    moduleInitSpan(), FailurePolicyId.NO_DEAL_FAILURE);
            }
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
                            context.assignShape(
                                callbackExports.contains(function.name())
                                    ? InvocationShape.CALLBACK_SHAPE
                                    : InvocationShape.EXTERNAL_ENTRY_SHAPE,
                                shapeOpId);
                        }
                        functionContexts.put(incarnation, context);
                        moduleFunctionContexts.put(function.name(), context);
                        contextsByBindingId.put(binding, context);
                        contextsByFunctionId.put(functionId, context);
                    }
                    if (closureCore) {
                        // The function-allocation identity of the hoisted
                        // module-level function is pre-allocated here so a
                        // capture of a later-declared module function
                        // (B1; docs/spec-v1.2.md:1217-1221) — a
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

        /**
         * The binding walk's non-function statement arms (shared by the
         * binding/closure walk and the group walk): let declarations,
         * the two-incarnation for-let shape, for-of iteration bindings,
         * try/catch bindings, import aliases (hoisted — no position
         * ops), nested blocks, and assignment expression statements.
         * Every other statement is a foreign construct in this child's
         * window ({@link ConstructUnlowered}).
         */
        private void lowerBindingStatement(StatementNode statement) {
            switch (statement) {
                case VariableDeclaration decl -> lowerBindingVarDecl(decl);
                case ForStatement forStatement -> lowerBindingForLet(forStatement);
                case ForOfStatement forOf -> lowerBindingForOf(forOf);
                case TryStatement tryStatement -> lowerBindingTry(tryStatement);
                case ImportDeclaration ignored -> {

                }
                case deal.ast.ExpressionStatement expressionStatement ->
                    lowerBindingExprStatement(expressionStatement);
                case Block block -> lowerBindingBlock(block);
                case ClassDeclaration classDeclaration -> {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeStatement(statement));
                    }
                    lowerClassDeclaration(classDeclaration, false);
                }
                case ExportDeclaration exportDeclaration -> {
                    if (!(classCore
                            && exportDeclaration.declaration()
                                instanceof ClassDeclaration classDeclaration)) {
                        throw new ConstructUnlowered(describeStatement(statement));
                    }
                    lowerClassDeclaration(classDeclaration, true);
                }
                case deal.ast.DeleteStatement delete -> {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeStatement(statement));
                    }
                    lowerDelete(delete);
                }
                default -> throw new ConstructUnlowered(describeStatement(statement));
            }
        }

        /**
         * The binding walk's expression-statement arm: exactly assignment
         * statements lower (the variable-assignment {@code BINDING_STORE}
         * commit naming the dominant incarnation is this child's op; the
         * chain's committed-value result is dropped exactly like the
         * for-let update block's — no {@code DISCARD}, which is E5's).
         * Every other expression statement is a foreign construct.
         */
        private void lowerBindingExprStatement(
                deal.ast.ExpressionStatement statement) {
            if (!(statement.expr() instanceof AssignmentExpr)) {
                throw new ConstructUnlowered("expression statement "
                    + describeExpression(statement.expr()) + " (DISCARD is E5's, "
                    + "ISSUE-0234; the binding walk admits assignment statements only)");
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

        private void lowerClassDeclaration(ClassDeclaration declaration, boolean exported) {
            ClassId classId = classIdOf(declaration);
            if (classLayouts.containsKey(classId)) {
                throw new ConstructUnlowered("duplicate class declaration '"
                    + declaration.name() + "' (the checker rejects duplicate class names; "
                    + "a second layout for " + classId + " is a fact defect)");
            }

            List<deal.semantic.ir.ClassLayout.FieldLayout> fields = new ArrayList<>();
            Map<String, RuntimeDescriptor> descriptors = new LinkedHashMap<>();
            for (ClassField field : declaration.fields()) {
                Type fieldType = fieldTypeOf(field.type());
                RuntimeDescriptor descriptor;
                try {
                    descriptor = DescriptorService.describe(fieldType);
                } catch (DescriptorService.Defect defect) {
                    throw new ConstructUnlowered("class field '" + declaration.name()
                        + "." + field.name() + "' of unrepresentable checked type "
                        + typeName(fieldType) + " (" + defect.getMessage() + ")");
                }
                fields.add(new deal.semantic.ir.ClassLayout.FieldLayout(field.name(),
                    descriptor, !field.optional(), DefaultOwner.LOCAL));
                descriptors.put(field.name(), descriptor);
            }
            deal.semantic.ir.ClassLayout layout =
                new deal.semantic.ir.ClassLayout(classId, fields);
            classLayouts.put(classId, layout);

            List<OpId> classDefaultOpIds = new ArrayList<>();
            LinkedHashMap<String, ClassDefaultFact> defaults = new LinkedHashMap<>();
            for (ClassField field : declaration.fields()) {
                if (field.defaultExpr().isEmpty()) {
                    continue;
                }
                ExpressionNode defaultExpr = field.defaultExpr().get();
                BlockId defaultBlock = allocateBlock();
                ValueId slot = ids.nextValueId(module, nextOrdinal++, 0);
                AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                OpId opId = ids.nextOpId(module, nextOrdinal++, 0);

                SourceOrigin origin = new SourceOrigin(sourceId,
                    toSourceSpan(defaultExpr.span()), SourceOriginKind.SYNTHETIC,
                    anchor, null);
                SemanticOp op = buildOp(opId, SemanticOpKind.CLASS_DEFAULT,
                    new KindPayload.ClassDefaultPayload(classId, field.name(), defaultBlock),
                    slot, descriptors.get(field.name()), FailurePolicyId.NO_DEAL_FAILURE,
                    origin);
                pushBlock(defaultBlock);
                pushBlockParent(opId);
                Set<BlockId> internalBlocks =
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                DefaultContext context = new DefaultContext(defaultBlock, internalBlocks,
                    captureCollectors.size());
                defaultContexts.push(context);
                ValueId produced;
                try {
                    emit(op);

                    produced = lowerExpression(defaultExpr, slot);
                    if (!produced.equals(slot)) {
                        op = buildOp(opId, SemanticOpKind.CLASS_DEFAULT,
                            new KindPayload.ClassDefaultPayload(classId, field.name(),
                                defaultBlock),
                            produced, descriptors.get(field.name()),
                            FailurePolicyId.NO_DEAL_FAILURE, origin);
                        List<SemanticOp> target = emitTarget();
                        for (int i = target.size() - 1; i >= 0; i--) {
                            if (target.get(i).opId().equals(opId)) {
                                target.set(i, op);
                                break;
                            }
                        }
                    }
                } finally {
                    defaultContexts.pop();
                    popBlockParent();
                    popBlock();
                }
                if (!field.optional()) {
                    classDefaultOpIds.add(opId);
                }
                defaults.put(field.name(), new ClassDefaultFact(opId, produced));
            }
            classDefaults.put(classId, defaults);

            if (declaration.isJsonable()) {
                lowerJsonFunctions(declaration, classId, layout, classDefaultOpIds);
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
                    + "ClassInterface per exported class — a fact defect)");
            }
            OpId callerOpRef = ids.nextOpId(module, nextOrdinal++, 0);
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId,
                toSourceSpan(declaration.span()), SourceOriginKind.SYNTHETIC, anchor, null);
            SemanticOp op = buildOp(opId, SemanticOpKind.CLASS_FACTORY,
                new KindPayload.ClassFactoryPayload(classId, List.copyOf(classDefaultOpIds),
                    callerOpRef),
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
                                        List<OpId> classDefaultOpIds) {
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
                    + "every @jsonable class — a fact defect)");
            }
            RuntimeDescriptor.Func fromSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(fromFunction.funcType());
            RuntimeDescriptor.Func toSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(toFunction.funcType());
            RuntimeDescriptor fromResult = fromSignature.returnType();
            RuntimeDescriptor toResult = toSignature.returnType();
            RuntimeDescriptor fromParameter = fromSignature.paramTypes().get(0);
            RuntimeDescriptor toParameter = toSignature.paramTypes().get(0);

            // C$fromJson(s: string): C|null — the walk records the
            // class's per-site CLASS_DEFAULT children (declaration
            // order) in the produced JsonDefaultChildTable record.
            OpId fromJsonOpId = lowerJsonFunction(declaration, layout, fromSignature,
                fromParameter, fromResult, FailurePolicyId.JSON_FROM_NULL, true);
            jsonDefaultChildren.put(fromJsonOpId, List.copyOf(classDefaultOpIds));

            // C$toJson(v: C): string.
            lowerJsonFunction(declaration, layout, toSignature, toParameter, toResult,
                FailurePolicyId.JSON_TO_ERROR, false);
        }

        /**
         * Emits one generated {@code @jsonable} function: the body block
         * (parameter {@code BINDING_ALLOC}, parameter {@code
         * BINDING_LOAD}, the single JSON op — the parameter load
         * result wired as the JSON payload's operand), then the
         * {@code CLOSURE_NEW} op publishing the fresh function identity
         * with the {@code LoweredFunction} record and the
         * {@code LoweredBody} registration through the registry seam,
         * and finally the buffered body ops flushed after the closure
         * (the closure-expression precedent's pinned unit order). The
         * JSON op's id is returned (the {@code JsonDefaultChildTable}
         * key of the {@code fromJson} arm).
         */
        private OpId lowerJsonFunction(ClassDeclaration declaration,
                                       deal.semantic.ir.ClassLayout layout,
                                       RuntimeDescriptor.Func signature,
                                       RuntimeDescriptor parameterDescriptor,
                                       RuntimeDescriptor jsonResultType,
                                       FailurePolicyId jsonPolicy,
                                       boolean fromJson) {
            BlockId bodyBlock = allocateBlock();
            FunctionId functionId = ids.nextFunctionId(module, nextOrdinal++, 0);
            ValueId closureIdentity = ids.nextValueId(module, nextOrdinal++, 0);
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
            functions.put(functionId, new LoweredFunction(functionId, signature, List.of(),
                bodyBlock));
            registry.registerClosure(new FunctionAllocationIdentity(closureIdentity.id()),
                functionId, bodyBlock);
            emitTarget().addAll(bodyOps);
            return jsonOpId;
        }

        private ValueId lowerClassLiteral(ObjectLiteralExpr literal, ValueId slot) {
            Type type = checkedType(literal);
            if (!(type instanceof Type.Class classType)) {
                throw new ConstructUnlowered("class literal of non-class checked type "
                    + typeName(type) + " (a class-typed ObjectLiteralExpr must carry a "
                    + "Type.Class fact)");
            }
            ClassId classId = new ClassId(
                DescriptorService.semanticModulePath(classType.identity()), classType.name());

            deal.semantic.ir.ClassLayout layout = classLayouts.get(classId);
            DefaultOwner defaultOwner = DefaultOwner.LOCAL;
            deal.semantic.ir.ClassFactoryId classFactoryRef = null;
            ValueId sharedFactoryResult = null;
            SharedFactoryFacts sharedFacts = null;
            if (layout == null) {
                sharedFacts = sharedFactories.get(classId);
                if (sharedFacts != null) {
                    layout = sharedFacts.layout();
                    defaultOwner = DefaultOwner.SHARED_FACTORY;
                    classFactoryRef = sharedFacts.interfaceEntry().constructionEntry();
                    sharedFactoryResult = sharedFacts.factoryResult();
                } else {
                    ClassRegistrationSeeds.ClassRegistration seed =
                        registrationSeeds.registrationFor(classId);
                    if (seed == null) {
                        throw new RetainedAbiDeferred(classId.text());
                    }

                    layout = seed.layout();
                    defaultOwner = seed.owner();
                }
            }

            List<KindPayload.ProvidedField> providedFields = new ArrayList<>();
            Map<String, ValueId> providedValues = new LinkedHashMap<>();
            Map<String, Span> providedSpans = new LinkedHashMap<>();
            for (Property property : literal.properties()) {
                ValueId value = lowerExpression(property.value());
                providedFields.add(new KindPayload.ProvidedField(property.name(), value));
                providedValues.put(property.name(), value);
                providedSpans.put(property.name(), property.span());
            }
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);

            List<OpId> classDefaultOpIds = new ArrayList<>();
            List<KindPayload.FieldBoundary> boundaries = new ArrayList<>();
            List<SemanticOp> children = new ArrayList<>();
            Map<String, ClassDefaultFact> facts = classDefaults.get(classId);
            for (deal.semantic.ir.ClassLayout.FieldLayout field : layout.fields()) {
                ValueId provided = providedValues.get(field.name());
                if (provided != null) {
                    SemanticOp child = buildFieldBoundary(BoundaryKind.CLASS_LITERAL_FIELD,
                        field.descriptor(), provided, providedSpans.get(field.name()), opId);
                    boundaries.add(new KindPayload.FieldBoundary(field.name(),
                        BoundaryKind.CLASS_LITERAL_FIELD, child.opId()));
                    children.add(child);
                    continue;
                }
                if (defaultOwner == DefaultOwner.SHARED_FACTORY) {

                    if (field.required()) {
                        if (!sharedFacts.hasDeclaredDefault(field.name())) {
                            throw new ConstructUnlowered("omitted required-present field '"
                                + field.name() + "' of imported class " + classId
                                + " without a declared default (the checker's E4001 "
                                + "rejects the shape — a fact defect, never an invented "
                                + "default)");
                        }
                        SemanticOp child = buildFieldBoundary(
                            BoundaryKind.CLASS_DEFAULT_FIELD, field.descriptor(),
                            sharedFactoryResult, literal.span(), opId);
                        boundaries.add(new KindPayload.FieldBoundary(field.name(),
                            BoundaryKind.CLASS_DEFAULT_FIELD, child.opId()));
                        children.add(child);
                    }
                    // An omitted optional field stays missing: no boundary.
                    continue;
                }
                ClassDefaultFact fact = facts == null ? null : facts.get(field.name());
                if (fact != null && field.required()) {
                    SemanticOp child = buildFieldBoundary(BoundaryKind.CLASS_DEFAULT_FIELD,
                        field.descriptor(), fact.result(), literal.span(), opId);
                    classDefaultOpIds.add(fact.opId());
                    boundaries.add(new KindPayload.FieldBoundary(field.name(),
                        BoundaryKind.CLASS_DEFAULT_FIELD, child.opId()));
                    children.add(child);
                }
                // An omitted optional field stays missing: no boundary.
            }
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(literal.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.CLASS_NEW,
                new KindPayload.ClassNewPayload(classId, layout, providedFields,
                    defaultOwner, classDefaultOpIds, classFactoryRef, boundaries),
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
                    + "checker fact is a producer defect)");
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
                    deal.types.Types.classType(classSymbol.name(), classSymbol.identity()));
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
                case deal.ast.NullableType nullable -> deal.types.Types.nullable(
                    fieldTypeOf(nullable.innerType()));
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
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> Type.Table.INSTANCE;
                case "Error" -> deal.types.Types.classType("Error",
                    deal.checker.NameResolver.intrinsicErrorIdentity());
                case "bytes" -> {
                    Symbol symbol = resolveScopeName(name);
                    if (symbol instanceof Symbol.ClassSymbol classSymbol) {
                        yield deal.types.Types.classType(classSymbol.name(),
                            classSymbol.identity());
                    }
                    yield Type.Bytes.INSTANCE;
                }
                default -> {
                    Symbol symbol = resolveScopeName(name);
                    if (symbol instanceof Symbol.ClassSymbol classSymbol) {
                        yield deal.types.Types.classType(classSymbol.name(),
                            classSymbol.identity());
                    }
                    throw new ConstructUnlowered("class-field type annotation names unknown "
                        + "type '" + name + "' (a checked module cannot reach this arm — "
                        + "a fact defect, never an invented type)");
                }
            };
        }

        /** The checker-fact mirror of {@code NameResolver.resolveQualifiedType}. */
        private Type resolveQualifiedFieldType(deal.ast.QualifiedType qualified) {
            Symbol symbol = resolveScopeName(qualified.moduleName());
            if (!(symbol instanceof Symbol.ModuleSymbol moduleSymbol)) {
                throw new ConstructUnlowered("class-field type annotation qualifies unknown "
                    + "module '" + qualified.moduleName() + "' (a checked module cannot "
                    + "reach this arm — a fact defect)");
            }
            Type exportType = moduleSymbol.exports().get(qualified.typeName());
            if (exportType == null) {
                throw new ConstructUnlowered("class-field type annotation names unknown "
                    + "export '" + qualified.typeName() + "' of module '"
                    + qualified.moduleName() + "' (a fact defect)");
            }
            return exportType;
        }

        /** Resolves one annotation name against the current checker scope. */
        private Symbol resolveScopeName(String name) {
            SymbolTable scope = currentCheckerScope();
            return scope == null ? null : scope.resolve(name);
        }

        public deal.semantic.ir.ClassFactoryRegistry factoryRegistry() {
            return new deal.semantic.ir.ClassFactoryRegistry(
                new LinkedHashMap<>(factoryRegistry));
        }

        public deal.semantic.ir.JsonDefaultChildTable jsonDefaultChildren() {
            return new deal.semantic.ir.JsonDefaultChildTable(
                new LinkedHashMap<>(jsonDefaultChildren));
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
            registerBinding(decl.name(), binding, incarnation);
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(binding, currentBlock(), true,
                    cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            if ((shapeMapAnalysis || e7Calls) && declaredTypeOf(decl) instanceof Type.Func) {
                Type sourceType = checkedType(decl.initializer());
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
                    lowerAdaptedVarDecl(decl, binding, incarnation);
                    return;
                }
            }
            ValueId value = lowerExpression(decl.initializer());
            OpId declarationBoundary = null;
            if (decl.typeAnnotation().isPresent()) {
                RuntimeDescriptor descriptor =
                    ContainerPayloadDescriptors.resultDescriptorOf(declaredTypeOf(decl));
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
            if (shape == AdapterShapeMap.SourceShape.INTRINSIC_FUNCTION_VALUE) {
                // B7 arm (a): a first-class intrinsic function value —
                // the seeded intrinsic identity is the materialized
                // operand; no function-typed load of the intrinsic
                // binding is emitted (a first-class intrinsic value has
                // no closed FunctionExecutionBinding shape, B5).
                String name = ((IdentifierExpr) value).name();
                ValueId operand = intrinsicIdentities.get(name);
                if (operand == null) {
                    throw new IllegalStateException("the intrinsic identity of '"
                        + name + "' was not seeded at module-init top (producer "
                        + "defect)");
                }
                return new AdapterValueSource(operand, null);
            }
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
                    + "dominant incarnation at the adaptation position)");
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

        /**
         * The shape-map child's adapted {@code let} declaration (B6-B9):
         * the closed mode map selects exactly one mode from checker
         * facts (B7), the per-mode payload is built from birth (B8),
         * and exactly one {@code FUNCTION_ADAPT} op is emitted before
         * the position's boundary — its result wired as the direct
         * input of exactly the position's own
         * {@code VARIABLE_DECLARATION} boundary chain (the boundary
         * observes an exact-signature adapter value and passes), or as
         * the {@code BINDING_INIT} operand of an inferred declaration
         * (no boundary op exists there). The adapter emits zero
         * {@code BOUNDARY} ops of its own; its fresh allocation
         * identity registers exactly one {@code AdapterBinding}
         * through the registry child's seam; the cell's tracked
         * function identity becomes the adapter identity (loads/reads/
         * passes/returns preserve it).
         *
         * <p><b>Per-mode creation (B8, exactly).</b> VALUE completes its
         * single operand at creation — the {@code CLOSURE_NEW} result,
         * the intrinsic function-value identity, or the single proved
         * {@code BINDING_LOAD} at the proof's generation — and retains
         * that identity; the payload's {@code proof} is present iff the
         * operand is a binding load. SHARED_CELL evaluates nothing and
         * records {@code SharedCell {binding, generation}} naming the
         * dominant incarnation at the position; the incarnation joins
         * the B2 cell-kind derivation's capture-reference union (the
         * shape-map child's SHARED_CELL arm). REEVALUATE_THUNK
         * evaluates nothing at the creation site: the source
         * expression's already-lowered ops live only inside a fresh
         * detached thunk block whose {@code capturedBindings} pin the
         * thunk's free bindings, generation-pinned, ordered by first
         * reference (the thunk builder's production; the captures join
         * the B2 derivation through the walk's registration surface —
         * the shape-map child's thunk arm).</p>
         *
         */
        private void lowerAdaptedVarDecl(VariableDeclaration decl, BindingId binding,
                                         BindingCoreIncarnation incarnation) {
            Type.Func sourceType = (Type.Func) checkedType(decl.initializer());
            Type.Func targetType = (Type.Func) declaredTypeOf(decl);
            RuntimeDescriptor.Func sourceSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(sourceType);
            RuntimeDescriptor.Func targetSignature = (RuntimeDescriptor.Func)
                ContainerPayloadDescriptors.resultDescriptorOf(targetType);
            Optional<BindingImmutabilityProof> proof = sourceProofOf(decl.initializer());
            AdapterShapeMap.SourceShape shape = AdapterShapeMap.sourceShapeOf(
                decl.initializer(), name -> checks.symbolTable().resolve(name));
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
            Type sourceType = checkedType(decl.initializer());
            Optional<BindingImmutabilityProof> proof = sourceProofOf(decl.initializer());
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts =
                sourceProducerFactsOf(value);
            AdapterCreationRule.WiringPoint wiringPoint = declarationBoundary != null
                ? new AdapterCreationRule.WiringPoint(declarationBoundary,
                    AdapterCreationRule.WiringTargetKind.VARIABLE_DECLARATION_BOUNDARY)
                : new AdapterCreationRule.WiringPoint(initOp,
                    AdapterCreationRule.WiringTargetKind.BINDING_INIT);
            creationClassifications.add(AdapterCreationRule.classifyVariablePosition(
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
            Type sourceType = checkedType(assignment.value());
            Optional<BindingImmutabilityProof> proof = sourceProofOf(assignment.value());
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts =
                sourceProducerFactsOf(value);
            creationClassifications.add(AdapterCreationRule.classifyVariablePosition(
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
                blockStack.push(bodyBlock);
                lowerParameterBindings(bodyBlock, function.params());
                checkerScopeNodes.push(function.body());
                statementWalk.walk(function.body().statements(), false);
                checkerScopeNodes.pop();
                blockStack.pop();
                popBindingFrame();
                checkerScopeNodes.pop();
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
            List<SemanticOp> bodyOps = new ArrayList<>();
            List<CapturedCell> captured = new ArrayList<>();
            emitTargets.push(bodyOps);
            captureBorders.push(bindingScopes.size());
            captureCollectors.push(captured);
            try {
                checkerScopeNodes.push(function);
                pushBindingFrame();
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
                                        instanceof RuntimeDescriptor.Null)) {
                                    throw new ConstructUnlowered("function '"
                                        + function.name()
                                        + "' body is not terminated and its return type is "
                                        + "not null (the carrier slice lowers the pinned "
                                        + "implicit null return only)");
                                }
                                lowerImplicitReturn(reservedContext, function.body().span());
                            }
                        }
                        functionStack.pop();
                    }
                    checkerScopeNodes.pop();
                    blockStack.pop();
                    popBindingFrame();
                    checkerScopeNodes.pop();
                }
            } finally {
                captureCollectors.pop();
                captureBorders.pop();
                emitTargets.pop();
            }
            List<BindingGeneration> captureIds = captureGenerations(captured);
            emitClosureNew(functionId, closureIdentity, signature, captureIds, bodyBlock,
                function.span(), captured);
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
            // Phase 1 walk: every member body lowers through the buffered
            // detached-body walk with capture collection (B3) — siblings
            // resolve because every member binding registered before any
            // body walk.
            List<List<SemanticOp>> bodyOpLists = new ArrayList<>();
            List<List<BindingGeneration>> captureIdLists = new ArrayList<>();
            List<BlockId> bodyBlocks = new ArrayList<>();
            List<RuntimeDescriptor.Func> signatures = new ArrayList<>();
            List<List<CapturedCell>> capturedLists = new ArrayList<>();
            for (FunctionDeclaration member : members) {

                FrameEntry hoistedEntry = moduleLevel ? frameEntryOf(member.name()) : null;
                FunctionContext reservedContext = fullProgram && moduleLevel
                        && hoistedEntry != null
                    ? functionContexts.get(hoistedEntry.incarnation()) : null;
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
                                        instanceof RuntimeDescriptor.Null)) {
                                    throw new ConstructUnlowered("group member '"
                                        + member.name() + "' body is not terminated"
                                        + " and its return type is not null");
                                }
                                lowerImplicitReturn(reservedContext,
                                    member.body().span());
                            }
                            functionStack.pop();
                        }
                        checkerScopeNodes.pop();
                        blockStack.pop();
                        popBindingFrame();
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
                functions.put(functionId, new LoweredFunction(functionId, signature,
                    captureIds, bodyBlock));
                registry.registerGroupMember(new FunctionAllocationIdentity(identity.id()),
                    functionId, bodyBlock);
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
         * symbol table (the scope chain walks to the enclosing scope and
         * the hoisted module root — never retaining a
         * {@code NameResolver} instance, D4).
         */
        private RuntimeDescriptor.Func functionSignatureOf(FunctionDeclaration function) {
            SymbolTable scope = checks.scopeMap().get(function);
            Symbol symbol = (scope == null ? checks.symbolTable() : scope)
                .resolve(function.name());
            if (symbol instanceof Symbol.FunctionSymbol functionSymbol) {
                return (RuntimeDescriptor.Func)
                    ContainerPayloadDescriptors.resultDescriptorOf(functionSymbol.funcType());
            }
            throw new ConstructUnlowered("function declaration '" + function.name()
                + "' without a checked FunctionSymbol fact (a missing checker fact is a "
                + "producer defect)");
        }

        private void lowerBindingForLet(ForStatement statement) {
            if (statement.init().isEmpty()
                    || !(statement.init().get() instanceof ForInit.VarDecl varDecl)) {
                throw new ConstructUnlowered("for statement without a let initializer "
                    + "(the binding-core child lowers exactly the for-let shape; a "
                    + "non-let for is the control-flow epic's)");
            }
            if (statement.condition().isEmpty()) {
                throw new ConstructUnlowered("for-let without a condition (the LOOP payload "
                    + "carries the condition value — an unconditional for is outside this "
                    + "child's window)");
            }
            if (statement.update().isPresent()
                    && !(statement.update().get() instanceof AssignmentExpr)) {
                throw new ConstructUnlowered("for-let update expression "
                    + statement.update().get().getClass().getSimpleName()
                    + " (the pinned shape is the assignment chain in the update block; "
                    + "another update shape is outside this child's window)");
            }
            VariableDeclaration decl = varDecl.decl();
            BindingId counter = ids.nextBindingId(module, nextOrdinal++, 0);
            Type counterType = counterTypeOf(statement, decl);
            if (closureCore
                    && ContainerPayloadDescriptors.resultDescriptorOf(counterType)
                        instanceof RuntimeDescriptor.Func) {
                throw new ConstructUnlowered("function-typed for-let counter (the "
                    + "per-iteration carry load's function identity is the registry "
                    + "child's resolution, B5)");
            }
            BlockId initBlock = allocateBlock();
            BlockId bodyBlock = allocateBlock();
            BlockId updateBlock = allocateBlock();
            ValueId conditionSlot = null;
            OpId forLetLoopOpId = null;
            if (fullProgram) {
                // The LOOP op lives in the ENCLOSING block (the validator's
                // block tree admits no self-reference) and the condition
                // value slot is allocated upfront: both the init-block
                // first production and the update-block re-production
                // publish this one slot (C-D4).
                conditionSlot = ids.nextValueId(module, nextOrdinal++, 0);
                AnchorId loopAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                forLetLoopOpId = ids.nextOpId(module, nextOrdinal++, 0);
                SourceOrigin loopOrigin = new SourceOrigin(sourceId,
                    toSourceSpan(statement.span()), SourceOriginKind.USER, loopAnchor,
                    currentParent());
                emit(buildOp(forLetLoopOpId, SemanticOpKind.LOOP,
                    new KindPayload.LoopPayload(ControlSelector.FOR, initBlock,
                        conditionSlot, bodyBlock, updateBlock),
                    null, null, FailurePolicyId.NO_DEAL_FAILURE, loopOrigin));
                pushLoopTarget(forLetLoopOpId);
            }
            checkerScopeNodes.push(statement);
            pushBindingFrame();
            blockStack.push(initBlock);
            BindingCoreIncarnation counterIncarnation = new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, initBlock, BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC, false);
            registerBinding(decl.name(), counter, counterIncarnation);
            // Init block: the counter ALLOC, the initializer value ops,
            // the counter INIT, then the first condition production
            // (init-block members — C-D4).
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(counter, initBlock, true,
                    cellKinds.cellKindOf(counterIncarnation), INITIAL_LOOP_GENERATION),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId initializer = lowerExpression(decl.initializer());
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(counter, INITIAL_LOOP_GENERATION,
                    initializer),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId condition = fullProgram
                ? lowerExpression(statement.condition().get(), conditionSlot)
                : lowerExpression(statement.condition().get());

            blockStack.pop();
            if (!fullProgram) {
                emitUserNullOp(SemanticOpKind.LOOP,
                    new KindPayload.LoopPayload(ControlSelector.FOR, initBlock, condition,
                        bodyBlock, updateBlock),
                    statement.span(), FailurePolicyId.NO_DEAL_FAILURE);
            }
            // Body block: the per-iteration incarnation (generation 1,
            // SHARED_CELL) at the body top, INIT from the generation-0
            // load, then the body statements (dominant generation 1).
            blockStack.push(bodyBlock);
            checkerScopeNodes.push(statement.body());
            pushBindingFrame();
            BindingCoreIncarnation perIteration = new BindingCoreIncarnation(
                1L, bodyBlock, BindingCellKind.SHARED_CELL, true,
                BindingProducer.BINDING_ALLOC, true);
            registerBinding(decl.name(), counter, perIteration);
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(counter, bodyBlock, true,
                    cellKinds.cellKindOf(perIteration), 1L),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId carry = emitSyntheticValueOp(SemanticOpKind.BINDING_LOAD,
                new KindPayload.BindingLoadPayload(counter, INITIAL_LOOP_GENERATION),
                decl.span(), ContainerPayloadDescriptors.resultDescriptorOf(counterType),
                FailurePolicyId.NO_DEAL_FAILURE);
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(counter, 1L, carry),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            statementWalk.walk(statement.body().statements(), false);
            // The composite marking of the for-let loop arm (D3): a
            // literal-true condition whose body exits only by
            // return/throw makes the loop non-completing, exactly like the
            // plain for/while arms (the body block's state is read before
            // the pops; the enclosing block is marked after).
            BlockExitState forLetBodyExit = exitStateOf(bodyBlock);
            boolean forLetLiteralTrue = isLiteralTrue(statement.condition().get());
            if (forLetLoopOpId != null) {
                popLoopTarget();
            }
            popBindingFrame();
            checkerScopeNodes.pop();
            blockStack.pop();
            if (forLetLiteralTrue
                    && forLetBodyExit == BlockExitState.RETURN_OR_THROW) {
                markExit(currentBlock(), BlockExitState.RETURN_OR_THROW);
            }
            // Update block: the update's assignment chain, then the
            // condition re-production (update-block members — C-D4;
            // both reference the generation-0 counter).
            blockStack.push(updateBlock);
            if (statement.update().isPresent()) {
                lowerExpression(statement.update().get());
            }
            if (fullProgram) {
                // The update-block condition re-production publishes the
                // one condition slot (C-D4).
                lowerExpression(statement.condition().get(), conditionSlot);
            } else {
                lowerExpression(statement.condition().get());
            }
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
        }

        private void lowerBindingForOf(ForOfStatement statement) {
            Type iterableType = checkedType(statement.iterable());
            if (iterableType instanceof Type.Array) {
                throw new ConstructUnlowered("array for-of (FOR_EACH(ARRAY_VALUES) is E5's, "
                    + "ISSUE-0234; an array-typed iterable reaching the binding-core walk "
                    + "fails hard)");
            }
            if (!(iterableType instanceof Type.String)) {
                throw new ConstructUnlowered("for-of over a non-string, non-array iterable "
                    + typeName(iterableType) + " (this child lowers string iterables only)");
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
                case ForStatement forStatement -> {
                    Set<String> loopBound = bound;
                    if (forStatement.init().isPresent()
                            && forStatement.init().get() instanceof ForInit.VarDecl varDecl) {
                        // The checker defines the loop variable in the
                        // loop's own child scope before walking the
                        // initializer — it binds throughout the loop,
                        // never in the enclosing block.
                        loopBound = new HashSet<>(bound);
                        loopBound.add(varDecl.decl().name());
                    }
                    if (forStatement.init().isPresent()) {
                        ForInit init = forStatement.init().get();
                        switch (init) {
                            case ForInit.VarDecl varDecl ->
                                walkReferenceExpr(varDecl.decl().initializer(), loopBound,
                                    references);
                            case ForInit.AssignExpr assignExpr ->
                                walkReferenceExpr(assignExpr.expr(), loopBound, references);
                        }
                    }
                    if (forStatement.condition().isPresent()) {
                        walkReferenceExpr(forStatement.condition().get(), loopBound,
                            references);
                    }
                    if (forStatement.update().isPresent()) {
                        walkReferenceExpr(forStatement.update().get(), loopBound,
                            references);
                    }
                    walkReferenceBlock(forStatement.body(), loopBound, references);
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
                case HasExpr has -> walkReferenceExpr(has.object(), bound, references);
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
         * The for-let counter's declared type: resolved from the
         * checker's for-scope symbol table (the counter symbol, inferred
         * type included), falling back to the initializer's checked type.
         */
        private Type counterTypeOf(ForStatement statement, VariableDeclaration decl) {
            Map<StatementNode, SymbolTable> scopeMap = checks.scopeMap();
            SymbolTable scope = scopeMap.get(statement);
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

        /**
         * Registers one incarnation of a declared name: the first
         * registration of a {@link BindingId} allocates its cell; later
         * incarnations of the same identity (the for-let counter's
         * per-iteration incarnation) append to the same cell, and a
         * shadowing redeclaration (a new {@link BindingId}) creates a new
         * cell and replaces the innermost frame entry.
         */
        private void registerBinding(String name, BindingId binding,
                                     BindingCoreIncarnation incarnation) {
            BindingCell cell = cellsById.get(binding);
            if (cell == null) {
                cell = new BindingCell(name, binding);
                cellsById.put(binding, cell);
                bindingCells.add(cell);
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
                                    Span span, List<CapturedCell> captured) {
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
                bodyBlock));
            registry.registerClosure(new FunctionAllocationIdentity(result.id()),
                functionId, bodyBlock);
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

        /**
         * Lowers one checked expression through the D1 shape map with an
         * explicit result slot: the expression's final producing op
         * publishes {@code slot} instead of a fresh {@link ValueId}
         * ({@code null} allocates one). The pinned use is the
         * {@code LOOP(FOR)} condition re-production — the
         * {@code updateBlock} condition production re-publishes the
         * {@code initBlock} production's condition {@code ValueId} (one
         * value identity, the most recently produced value of the
         * condition {@code ValueId} wins at execution). Only the final
         * producing op takes the slot; every intermediate op allocates
         * its own value.
         *
         */
        public ValueId lowerExpression(ExpressionNode expr, ValueId slot) {
            Objects.requireNonNull(expr, "expr must not be null");
            return switch (expr) {
                case LiteralExpr literal -> lowerConst(literal, slot);
                case IdentifierExpr identifier -> lowerBindingLoad(identifier, slot);
                case FunctionExpr functionExpr -> {
                    if (closureCore) {
                        yield lowerClosureExpr(functionExpr, slot);
                    }
                    throw new ConstructUnlowered(describeExpression(expr));
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
                    throw new ConstructUnlowered(describeExpression(expr));
                }
                case IndexExpr index -> lowerIndexRead(index, slot);
                case AssignmentExpr assignment -> lowerAssignment(assignment, slot);
                case HasExpr has -> {
                    if (!classCore) {
                        throw new ConstructUnlowered(describeExpression(expr));
                    }
                    yield lowerHasField(has, slot);
                }
                default -> throw new ConstructUnlowered(describeExpression(expr));
            };
        }

        /**
         * Lowers one checked for-of statement with a string-typed
         * iterable through the D1 arm: the iterable prior step, the
         * {@code FOR_EACH} op, then the body block's statements under the
         * loop-binding frame. An array-typed iterable fails hard with
         * {@link ConstructUnlowered} ({@code FOR_EACH(ARRAY_VALUES)} is
         * E5's).
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
                    + "iterables only)");
            }
            BlockId bodyBlock = allocateBlock();
            ValueId iterable = lowerExpression(stmt.iterable());
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
         * prior step in the enclosing block; the op snapshots the array
         * reference and the initial length and visits slot indices
         * {@code 0..initialLength-1} in increasing order with the op's
         * own {@code TYPE_DESCRIPTOR} terminal check against the element
         * descriptor derived from the recorded array operand type
         * ({@code [T]} → element {@code T}) — a missing element fails
         * E8001 {@code expected {T}, got missing} at the {@code FOR_EACH}
         * origin before the body runs; then a fresh binding per iteration
         * (mechanics E6 — the payload pins the binding identity and the
         * initial generation) and the body block executes.
         * {@code BREAK}/{@code CONTINUE} target this op's {@code OpId}.
         * The element type is derived fail-closed through the descriptor
         * bridge — an unrepresentable element ({@code bytes}) is a
         * producer defect, never an invented descriptor.
         */
        /**
         * The element descriptor of one container position (K6 item 8):
         * the single {@link ContainerPayloadDescriptors} bridge over the
         * checked element type, bytes included at every depth.
         */
        private RuntimeDescriptor containerElementDescriptor(Type element) {
            // K6 item 8: a container element position whose checked type
            // contains bytes derives its descriptor through the single
            // DescriptorService bridge (the bytes descriptor member is
            // representable at every depth); the lowered bytes value is
            // carried by the target runtime's bytes representation.
            return ContainerPayloadDescriptors.elementDescriptorOf(element);
        }

        private OpId lowerArrayForOf(ForOfStatement stmt, Type.Array arrayType) {
            containerElementDescriptor(arrayType.element());
            BlockId bodyBlock = allocateBlock();
            ValueId iterable = lowerExpression(stmt.iterable());
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
                    + "this receiver: module members are E10's, array/bytes members carry "
                    + "no write shape)");
            }
            if (target instanceof IndexExpr index) {
                Type containerType = checkedType(index.array());
                if (containerType instanceof Type.Table) {
                    return lowerTableIndexAssign(assignment, index, slot);
                }
                if (containerType instanceof Type.Array arrayType) {
                    return lowerArrayIndexAssign(assignment, index, arrayType, slot);
                }
                if (containerType instanceof Type.Bytes) {
                    return lowerBytesIndexAssign(assignment, index, slot);
                }
                throw new ConstructUnlowered("assignment target index on "
                    + typeName(containerType) + " (no closed A-D9 chain shape for this "
                    + "container)");
            }
            throw new ConstructUnlowered("assignment target "
                + target.getClass().getSimpleName() + " (no closed A-D9 chain shape)");
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
                    + "receiver: module members are E10's, array/bytes members carry no "
                    + "delete shape)");
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
                    + " (no closed A-D9 delete shape for this container: bytes indexing is "
                    + "ISSUE-0158's)");
            }
            throw new ConstructUnlowered("delete target " + target.getClass().getSimpleName()
                + " (no closed A-D9 delete shape)");
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
                    + "carry a store identity here)");
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
                    + "(module members are E10's)");
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
                Type sourceType = checkedType(assignment.value());
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
                valueOp = producerOpId(value);
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
                value, targetDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (creationRuleAnalysis) {
                classifyVariableAssignPosition(assignment, target, value, boundaryOp);
            }
            return value;
        }

        /**
         * The shape-map child's adapted variable-assignment chain (B6-B9):
         * the closed mode map selects exactly one mode from checker
         * facts (B7) and the per-mode payload is built from birth (B8).
         * The chain's committed value is the adapter result: the closed
         * chain shapes are {@code [sourceOp, FUNCTION_ADAPT,
         * VARIABLE_ASSIGNMENT boundaryOp, BINDING_STORE commitOp]}
         * (VALUE — the single operand completes at creation as the
         * chain's RHS child, exactly once) and {@code [FUNCTION_ADAPT,
         * VARIABLE_ASSIGNMENT boundaryOp, BINDING_STORE commitOp]}
         * (SHARED_CELL/REEVALUATE_THUNK — zero evaluation at the
         * creation site; the thunk ops live only inside the detached
         * thunk block, whose captures are generation-pinned in
         * first-reference order). The adapter op emits zero
         * {@code BOUNDARY} children; the position's own
         * {@code VARIABLE_ASSIGNMENT} boundary takes the adapter result
         * as its direct input (exact-signature pass-through), the
         * commit stores it, the {@code ASSIGN} result is the committed
         * adapter identity, and the cell's tracked function identity
         * becomes the adapter identity (loads/reads/passes/returns
         * preserve it — the adapter is an ordinary function value after
         * the commit).
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
            Optional<BindingImmutabilityProof> proof = sourceProofOf(assignment.value());
            AdapterShapeMap.SourceShape shape = AdapterShapeMap.sourceShapeOf(
                assignment.value(), name -> checks.symbolTable().resolve(name));
            CaptureMode mode = AdapterShapeMap.selectMode(shape, proof);
            // REEVALUATE_THUNK: the detached thunk walks before the
            // chain opens — the thunk ops execute only at invocation
            // (E7) and must not be chain children.
            ThunkSource thunk = mode == CaptureMode.REEVALUATE_THUNK
                ? lowerThunkSource(assignment.value()) : null;
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId valueOp = null;
            OpId boundaryOp;
            OpId commitOp;
            EmittedAdapter adapter;
            AdaptSourceRef sourceRef = null;
            BindingImmutabilityProof payloadProof = null;
            try {
                ValueId operand = null;
                List<ValueId> operands;
                List<RuntimeDescriptor> operandTypes;
                switch (mode) {
                    case VALUE -> {
                        AdapterValueSource source = completeAdapterValueSource(
                            assignment.value(), shape, proof);
                        operand = source.operand();
                        payloadProof = source.proof();
                        if (shape
                                != AdapterShapeMap.SourceShape
                                    .INTRINSIC_FUNCTION_VALUE) {
                            valueOp = producerOpId(operand);
                        }
                        sourceRef = new AdaptSourceRef.Value(operand);
                        operands = List.of(operand);
                        operandTypes = List.of(sourceSignature);
                    }
                    case SHARED_CELL -> {
                        sourceRef = lowerAdapterSharedCellSource(
                            assignment.value());
                        operands = List.of();
                        operandTypes = List.of();
                    }
                    case REEVALUATE_THUNK -> {
                        sourceRef = thunkSourceRef(thunk);
                        operands = List.of();
                        operandTypes = List.of();
                    }
                    default -> throw new IllegalStateException("unreachable mode " + mode);
                }
                adapter = emitFunctionAdapt(mode, sourceRef, sourceSignature,
                    targetSignature, payloadProof, operands, operandTypes,
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
            List<OpId> children = valueOp != null
                ? List.of(valueOp, adapter.opId(), boundaryOp, commitOp)
                : List.of(adapter.opId(), boundaryOp, commitOp);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId,
                toSourceSpan(assignment.span()), SourceOriginKind.USER, anchor,
                currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.VARIABLE, children),
                value, targetSignature, FailurePolicyId.NO_DEAL_FAILURE, origin));
            recordAdapterEmission(adapter.opId(), adapter.identity(), mode, sourceRef,
                sourceSignature, targetSignature, payloadProof, boundaryOp,
                BoundaryKind.VARIABLE_ASSIGNMENT);
            if (thunk != null) {
                emitTarget().addAll(thunk.ops());
            }
            if (creationRuleAnalysis) {
                classifyVariableAssignPosition(assignment, target, value, boundaryOp);
            }
            return value;
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
                    + "." + access.field() + "' (EXPORT_* is E10's)");
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
                ValueId container = lowerExpression(access.object());
                containerOp = producerOpId(container);
                value = lowerExpression(assignment.value(), slot);
                valueOp = producerOpId(value);
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
                value, targetDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
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
                    + "key reaching lowering is a producer defect)");
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
                ValueId container = lowerExpression(index.array());
                containerOp = producerOpId(container);
                ValueId key = lowerExpression(index.index());
                keyOp = producerOpId(key);
                value = lowerExpression(assignment.value(), slot);
                valueOp = producerOpId(value);
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
                value, targetDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
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
         */
        private ValueId lowerArrayIndexAssign(AssignmentExpr assignment, IndexExpr index,
                                              Type.Array arrayType) {
            return lowerArrayIndexAssign(assignment, index, arrayType, null);
        }

        private ValueId lowerArrayIndexAssign(AssignmentExpr assignment, IndexExpr index,
                                              Type.Array arrayType, ValueId slot) {
            RuntimeDescriptor elementDescriptor =
                containerElementDescriptor(arrayType.element());
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
            try {
                ValueId container = lowerExpression(index.array());
                containerOp = producerOpId(container);
                ValueId key = lowerExpression(index.index());
                keyOp = producerOpId(key);
                value = lowerExpression(assignment.value(), slot);
                valueOp = producerOpId(value);
                ValueId length = emitChainChildOp(SemanticOpKind.ARRAY_LENGTH,
                    new KindPayload.ArrayLengthPayload(container), index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                    FailurePolicyId.INT32_RESULT);
                lengthOp = producerOpId(length);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_WRITE, key, length),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(normalizedSlot);
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
                        elementDescriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    index.span(), FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
                    SourceOriginKind.SYNTHETIC, chainOpId);
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
                new KindPayload.AssignPayload(AssignTargetKind.ARRAY_SLOT,
                    List.of(containerOp, keyOp, valueOp, lengthOp, normalizeOp, boundaryOp,
                        commitOp)),
                value, elementDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (creationRuleAnalysis && elementDescriptor instanceof RuntimeDescriptor.Func) {
                // A function-typed array element position: a typed
                // boundary position never adapts (B6) — the walk's
                // direct value flow into the ARRAY_ELEMENT_ASSIGNMENT
                // boundary slot is the wiring E4's boundary producer
                // consumes; the classifier records the closed
                // classification (and the E8010 expectation for a
                // mismatched pair — the executed check is E4's).
                recordBoundaryClassification(BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
                    checkedType(assignment.value()), arrayType.element());
            }
            return value;
        }

        /**
         * ASSIGN BYTES_SLOT write (K6 item 4): the array chain's exact
         * mirror — {@code [containerOp, keyOp, valueOp,
         * lengthOp(ARRAY_LENGTH over the byte receiver),
         * normalizeOp(INDEX_NORMALIZE BYTES_WRITE, rawKey = the key child's
         * result, currentLength = the length child's result),
         * boundaryOp(BYTE_ELEMENT_ASSIGNMENT + BYTES_WRITE, input = the
         * checked RHS value),
         * commitOp(INDEX_WRITE)]}. The boundary enforces the pinned E8012
         * bounds at the index expression ({@code index < 0} or
         * {@code index >= b.length}); the commit enforces the E8013 value
         * range (0..255) at the assignment expression and runs the single
         * mutation — a failed write changes no storage. There is no bytes
         * delete shape ({@code delete b[i]} is the checker's E3007
         * rejection).
         */
        private ValueId lowerBytesIndexAssign(AssignmentExpr assignment, IndexExpr index,
                                              ValueId slot) {
            RuntimeDescriptor elementDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE);
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
            try {
                ValueId container = lowerExpression(index.array());
                containerOp = producerOpId(container);
                ValueId key = lowerExpression(index.index());
                keyOp = producerOpId(key);
                value = lowerExpression(assignment.value(), slot);
                valueOp = producerOpId(value);
                ValueId length = emitChainChildOp(SemanticOpKind.ARRAY_LENGTH,
                    new KindPayload.ArrayLengthPayload(container), index.span(),
                    elementDescriptor, FailurePolicyId.INT32_RESULT);
                lengthOp = producerOpId(length);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.BYTES_WRITE, key,
                        length),
                    index.span(), elementDescriptor,
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(normalizedSlot);
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.BYTE_ELEMENT_ASSIGNMENT,
                        elementDescriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    index.span(), FailurePolicyId.BYTES_WRITE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                // The commit carries the assignment expression's origin:
                // its E8013 value-range projection is the commit's own
                // (K6 item 4).
                commitOp = emitNullOp(SemanticOpKind.INDEX_WRITE,
                    new KindPayload.IndexWritePayload(container, normalizedSlot, value),
                    assignment.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.BYTES_SLOT,
                    List.of(containerOp, keyOp, valueOp, lengthOp, normalizeOp, boundaryOp,
                        commitOp)),
                value, elementDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            return value;
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
            if (classCore) {
                deal.semantic.ir.ClassLayout layout = classLayouts.get(classId);
                if (layout == null) {
                    // The registration seeds (the compiler-owned builtin
                    // Error included, K13's field surface): a seeded class's
                    // declared field descriptor comes from the seed layout
                    // exactly like a locally declared class's; an unseeded
                    // unresolvable class stays fail closed.
                    ClassRegistrationSeeds.ClassRegistration seed =
                        registrationSeeds.registrationFor(classId);
                    if (seed != null) {
                        layout = seed.layout();
                    }
                }
                if (layout == null) {
                    throw new ConstructUnlowered("class field write '" + access.field()
                        + "' on " + classId + " without a local layout (the declared field "
                        + "descriptor comes from the unit's classLayouts; an imported "
                        + "class's field write is outside this epic's unit-level window)");
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
                        + "admits declared fields only — a fact defect)");
                }
                declaredDescriptor = fieldLayout.descriptor();
                try {
                    classDescriptor = DescriptorService.describe(classType);
                } catch (DescriptorService.Defect defect) {
                    throw new ConstructUnlowered("class field write '" + access.field()
                        + "' on " + classId + " of unrepresentable receiver type ("
                        + defect.getMessage() + ")");
                }
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId containerOp;
            OpId valueOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(access.object());
                containerOp = producerOpId(container);
                value = lowerExpression(assignment.value(), slot);
                valueOp = producerOpId(value);
                commitOp = emitNullOp(SemanticOpKind.FIELD_WRITE,
                    new KindPayload.FieldWritePayload(container, classId, access.field(),
                        value),
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
                    emitNullOp(SemanticOpKind.BOUNDARY,
                        new KindPayload.BoundaryPayload(BoundaryKind.CLASS_FIELD_ASSIGNMENT,
                            declaredDescriptor, value,
                            new BoundaryRealization.RuntimeValidation(
                                CANONICAL_RUNTIME_VALIDATION_ID)),
                        access.span(), descriptorKindPolicy(declaredDescriptor),
                        SourceOriginKind.SYNTHETIC, commitOp);
                }
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.CLASS_FIELD,
                    List.of(containerOp, valueOp, commitOp)),
                value, fieldDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            return value;
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
                    + "." + access.field() + "' (EXPORT_* is E10's)");
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(access.object());
                containerOp = producerOpId(container);
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
                    + "key reaching lowering is a producer defect)");
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId keyOp;
            OpId normalizeOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(index.array());
                containerOp = producerOpId(container);
                ValueId key = lowerExpression(index.index());
                keyOp = producerOpId(key);
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
                containerElementDescriptor(arrayType.element());
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId keyOp;
            OpId lengthOp;
            OpId normalizeOp;
            OpId boundaryOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(index.array());
                containerOp = producerOpId(container);
                ValueId key = lowerExpression(index.index());
                keyOp = producerOpId(key);
                ValueId length = emitChainChildOp(SemanticOpKind.ARRAY_LENGTH,
                    new KindPayload.ArrayLengthPayload(container), index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                    FailurePolicyId.INT32_RESULT);
                lengthOp = producerOpId(length);
                ValueId normalizedSlot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_WRITE, key, length),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
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
            if (classCore) {
                try {
                    classDescriptor = DescriptorService.describe(classType);
                } catch (DescriptorService.Defect defect) {
                    throw new ConstructUnlowered("class field delete '" + access.field()
                        + "' on " + classId + " of unrepresentable receiver type ("
                        + defect.getMessage() + ")");
                }
            }
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            OpId containerOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(access.object());
                containerOp = producerOpId(container);
                commitOp = emitNullOp(SemanticOpKind.FIELD_DELETE,
                    new KindPayload.FieldDeletePayload(container, classId, access.field()),
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
                                           String interfaceHash,
                                           String capabilityRegistryHash) {
            return buildUnit(constructCoverage, imports, interfaceHash, capabilityRegistryHash,
                ContainerClaimingSeam.E5_GATE_ACTIVATION);
        }

        /**
         * Builds the validated unit under the named claiming-seam
         * activation state (the binding-core entry passes the pinned
         * E6-gate activation — {@code BINDINGS} activates for
         * {@code BINDING_LOAD} — while the E5 window derives the claim
         * set under the E5-gate activation:
         * {@code CONTAINERS_AND_STRINGS} and {@code EVALUATION_ORDER}
         * activate).
         */
        public LoweredModuleUnit buildUnit(Map<ConstructKind, List<SemanticOpKind>>
                                               constructCoverage,
                                           List<ModuleId> imports,
                                           String interfaceHash,
                                           String capabilityRegistryHash,
                                           Set<SemanticCapability> activation) {
            Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
            Objects.requireNonNull(imports, "imports must not be null");
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
            return new LoweredModuleUnit(
                LoweredModuleUnit.FORMAT_VERSION,
                SemanticProfile.DEAL_V1_2_INT32,
                module,
                interfaceHash,
                LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                    capabilityRegistryHash),
                claims,
                coverage,
                new LinkedHashMap<>(classLayouts),
                Map.copyOf(functions),
                new ModuleInitPlan(List.copyOf(imports), moduleInitBlock),
                ExportPlan.empty(),
                registry.bindings(),
                ops());
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

        public void setRegistrationSeeds(ClassRegistrationSeeds seeds) {
            this.registrationSeeds = Objects.requireNonNull(seeds,
                "seeds must not be null");
        }

        public void setDeclaredConversionIntrinsics(List<IntrinsicKind> kinds) {
            Objects.requireNonNull(kinds, "kinds must not be null");
            this.declaredConversionIntrinsics = List.copyOf(kinds);
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
            try {
                return lowerIntrinsicCall(call, slot);
            } catch (ConstructUnlowered notIntrinsic) {
                // Fall through: the classifier threw before any emission.
            }
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
            // through).
            registry.registerHostOrExternalImport(
                new FunctionAllocationIdentity(exportValue.id()),
                new KindPayload.ExportReadPayload(stdlibModule, field,
                    exportDescriptor, exportValue),
                new FunctionBindingRegistry.FunctionValueImportFacts(
                    stdlibModule, null, field, exportDescriptor),
                (ModuleRoutePlan) null);
            // The argument operands complete left-to-right before the
            // STDLIB_CALL START (parent D13 step 1); their descriptors
            // are the catalog entry's declared parameter descriptors in
            // order — the declared signature is the single authority for
            // operand and boundary descriptors alike.
            List<ValueId> args = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerExpression(argument));
            }
            List<RuntimeDescriptor> parameterDescriptors = entry.parameterDescriptors();
            if (args.size() != parameterDescriptors.size()) {
                throw new ConstructUnlowered("stdlib call " + function + " ("
                    + entry.modulePath() + "." + field + ") with " + args.size()
                    + " arguments for " + parameterDescriptors.size()
                    + " declared parameters (the checker admits exact arity only)");
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
         * unit. A {@code null} source id is the emitting module's own.
         */
        private SemanticOp buildChildBoundary(BoundaryKind kind, RuntimeDescriptor descriptor,
                                              ValueId input, Span span, String originSourceId,
                                              OpId parent) {
            FailurePolicyId policy = descriptorKindPolicy(descriptor);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
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
                            + "E7's registry resolution)");
                    }
                    return lowerUserCallBinding(call, slot, identifier);
                }
                return lowerDirectCall(call, slot, identifier, context);
            }
            if (!e7Calls) {
                throw new ConstructUnlowered("call callee shape "
                    + call.callee().getClass().getSimpleName()
                    + " (the carrier slice lowers direct calls of declared function "
                    + "bindings; indirect/host/external/async calls are E7's)");
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

        private ValueId lowerDirectCall(CallExpr call, ValueId slot, IdentifierExpr identifier,
                                        FunctionContext context) {
            boolean entryInvocation = e7Calls
                && context.shape == InvocationShape.EXTERNAL_ENTRY_SHAPE;
            if (e7Calls && !entryInvocation) {
                context.assignShape(InvocationShape.SOURCE_CALL, context.callSiteOpId);
            }
            // The audited callee evaluation: the identifier loads the
            // declared function binding (its result identity is the
            // closure identity — R-FUNCTION-BINDING) before the CALL.
            lowerExpression(identifier);
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
            for (ExpressionNode argument : call.args()) {
                args.add(lowerCallArgument(argument));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != context.signature.paramTypes().size()) {
                throw new ConstructUnlowered("call of '" + identifier.name()
                    + "' with " + args.size() + " arguments for "
                    + context.signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)");
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
                                identifier.name(), context.signature,
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
            // The producer rule's call arm: a direct call result with a
            // function-typed result registers exactly one dynamic
            // materialization keyed by the call's result identity.
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
            ValueId identity = resolution == null
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
                    + " has no registered FunctionExecutionBinding (producer defect)");
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
                    + "arm owns async starts)");
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerExpression(argument));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != signature.paramTypes().size()) {
                throw new ConstructUnlowered("call of '" + calleeName + "' with "
                    + args.size() + " arguments for " + signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)");
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
                    + "descriptor (the checker admits function-typed callees only)");
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
                case FunctionExecutionBinding.IntrinsicFunction intrinsic ->
                    intrinsic.descriptor().paramTypes().size();
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "a statically classified call "
                        + "arity");
            };
        }

        /** The pinned host-call parameter cells: DEAL_TO_HOST + HOST_PARAMETER. */
        private void hostParameterBoundaries(RuntimeDescriptor.Func descriptor,
                List<ValueId> args, CallExpr call, OpId callOpId,
                List<SemanticOp> parameterBoundaryOps, List<OpId> parameterBoundaryIds) {
            for (int i = 0; i < args.size(); i++) {
                SemanticOp boundary = buildChildBoundaryWithPolicy(
                    BoundaryKind.DEAL_TO_HOST, descriptor.paramTypes().get(i),
                    FailurePolicyId.HOST_PARAMETER, args.get(i), call.span(), callOpId);
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

        /** The adapter's statically fixed source binding, or null (thunk/call-result sources). */
        private FunctionExecutionBinding staticSourceBinding(
                FunctionExecutionBinding.AdapterBinding adapter) {
            ValueId identity = switch (adapter.sourceRef()) {
                case AdaptSourceRef.SharedCell cell ->
                    identityOfIncarnation(cell.binding(), cell.generation());
                case AdaptSourceRef.Value value -> value.value();
                case AdaptSourceRef.Thunk ignored -> null;
            };
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
                    + "' without a function-typed checked descriptor (producer defect)");
            }
            if (signature.isAsync()) {
                throw new ConstructUnlowered("call of async callee '" + calleeName
                    + "' outside await (the checker's E3014 pins the shape; the await "
                    + "arm owns async starts)");
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerExpression(argument));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            int expectedParams = bindingParamCount(binding);
            if (args.size() != expectedParams) {
                throw new ConstructUnlowered("call of '" + calleeName + "' with "
                    + args.size() + " arguments for " + expectedParams
                    + " parameters (the checker admits exact arity only)");
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
                    sourceContext.assignShape(InvocationShape.SOURCE_CALL,
                        sourceContext.callSiteOpId);
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
                        sourceContext.assignShape(InvocationShape.SOURCE_CALL,
                            sourceContext.callSiteOpId);
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
                case FunctionExecutionBinding.IntrinsicFunction intrinsic ->

                    hostParameterBoundaries(intrinsic.descriptor(), args, call,
                        callOpId, parameterBoundaryOps, parameterBoundaryIds);
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the call of '" + calleeName + "'");
            }
            // The single return boundary per the closed table.
            OpId returnBoundaryOpId = null;
            OpId externalEntryRef = null;
            BlockId bodyBlock = null;
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
                    context.assignShape(InvocationShape.SOURCE_CALL, context.callSiteOpId);
                    returnBoundaryOpId = context.returnBoundaryOpId;
                    bodyBlock = context.bodyBlock;
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    FunctionExecutionBinding source = staticSourceBinding(adapter);
                    if (source == null) {
                        throw new ConstructUnlowered("adapter invocation of '" + calleeName
                            + "' whose source identity is not statically fixed "
                            + "(REEVALUATE_THUNK call-result sources are ISSUE-0531's)");
                    }
                    switch (source) {
                        case FunctionExecutionBinding.LoweredBody body -> {
                            FunctionContext context =
                                contextsByFunctionId.get(body.functionId());
                            if (context == null) {
                                throw new ConstructUnlowered("the adapter's body-source "
                                    + "binding has no lowering context (producer defect)");
                            }
                            context.assignShape(InvocationShape.SOURCE_CALL,
                                context.callSiteOpId);
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
                        case FunctionExecutionBinding.IntrinsicFunction intrinsic ->

                            returnBoundaryOpId = emitHostReturnBoundary(
                                intrinsic.descriptor(), result, call.span(),
                                callOpId).opId();
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
                        case FunctionExecutionBinding.AdapterBinding nested ->
                            throw new ConstructUnlowered("nested adapter source of '"
                                + calleeName + "' (adapter-of-adapter invocation is "
                                + "ISSUE-0531's)");
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
                case FunctionExecutionBinding.IntrinsicFunction intrinsic ->
                    // The single HOST_TO_DEAL + HOST_SYNC_RETURN return cell,
                    // run by the call op (the intrinsic's class is HOST,
                    // {@link DynamicReturnBoundaryProtocol#kindOf}).
                    returnBoundaryOpId = emitHostReturnBoundary(intrinsic.descriptor(),
                        result, call.span(), callOpId).opId();
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the call of '" + calleeName + "'");
            }
            emit(buildOp(callOpId, SemanticOpKind.CALL,
                new KindPayload.CallPayload(CallMode.INDIRECT,
                    valueCarried
                        ? new KindPayload.CallCallee.Indirect(calleeValue)
                        : new KindPayload.CallCallee.Static(binding),
                    signature, parameterBoundaryIds, returnBoundaryOpId, null, bodyBlock,
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
         * seam — {@code HostFunction(resolvedModule, exportName, descriptor)}
         * for a HOST-kind import and for a declared function export of a
         * STDLIB-kind import, {@code ExternalFunction(resolvedModule,
         * exportName, descriptor, owner)} with the route-derived execution
         * owner for an IMPLEMENTATION-kind import — keyed by the published
         * cell. A non-function descriptor produces the read with no
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
                    + "' (module member reads are EXPORT_READ shapes)");
            }
            ResolvedImport importFact = importByAlias(alias.name());
            if (importFact == null) {
                throw new ConstructUnlowered("import alias '" + alias.name()
                    + "' of '" + alias.name() + "." + access.field()
                    + "' without a resolved import fact (a missing checker fact is a "
                    + "producer defect)");
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
                    + "EXPORT_READ)");
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
                    + "and never registers)");
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
                            + "stdlib member is never a call)"));
                    descriptor = row.declaredDescriptor();
                    if (!descriptor.equals(checked)) {
                        throw new ConstructUnlowered("stdlib member '" + alias.name()
                            + "." + access.field() + "' has checked descriptor "
                            + checked.canonicalSpecText() + " but the closed stdlib "
                            + "catalog row for '" + importFact.resolvedModuleId().path()
                            + "' declares " + descriptor.canonicalSpecText()
                            + " (the row's declared descriptor is the EXPORT_READ's own)");
                    }
                }
                default -> throw new ConstructUnlowered("import kind "
                    + importFact.kind() + " of module member '" + alias.name() + "."
                    + access.field() + "' of module '"
                    + importFact.resolvedModuleId().path()
                    + "' has no import-member read arm (the closed produced set is "
                    + "IMPLEMENTATION/STDLIB/HOST; the parent-pinned DECLARATION "
                    + "kind has no producer)");
            }
            FunctionBindingRegistry.FunctionValueImportFacts facts = null;
            if (descriptor instanceof RuntimeDescriptor.Func functionDescriptor) {
                facts = switch (importFact.kind()) {
                    case HOST, STDLIB -> new FunctionBindingRegistry
                        .FunctionValueImportFacts(importFact.resolvedModuleId(), null,
                            access.field(), functionDescriptor);
                    case IMPLEMENTATION -> {
                        if (!calleeRoutes.containsKey(importFact.resolvedModuleId())) {
                            throw new ConstructUnlowered("compiled import member read '"
                                + alias.name() + "." + access.field()
                                + "' has no callee-route record for module '"
                                + importFact.resolvedModuleId().path()
                                + "' (the realized drive installs the session's "
                                + "resolved import facts and callee-route facts; a "
                                + "route-less carrier session fails closed)");
                        }
                        yield new FunctionBindingRegistry.FunctionValueImportFacts(null,
                            importFact.resolvedModuleId(), access.field(),
                            functionDescriptor);
                    }
                    default -> throw new ConstructUnlowered("import kind "
                        + importFact.kind() + " has no import-member registration arm");
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
            registry.registerHostOrExternalImportWithRoutes(
                new FunctionAllocationIdentity(exportValue.id()), exportPayload, facts,
                calleeRoutes);
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
                    + "checker admits function-typed call callees only)");
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
                    + "checker's E3014 pins the shape; the await arm owns async starts)");
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerCallArgument(argument));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != descriptor.paramTypes().size()) {
                throw new ConstructUnlowered("import call with " + args.size()
                    + " arguments for " + descriptor.paramTypes().size()
                    + " parameters (the checker admits exact arity only)");
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
                    + " (the parser's E1042 pins await over a call)");
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
                    return lowerAwaitDeclared(call, slot, awaitSpan, identifier, context);
                }
                FrameResolution resolution = resolveFrame(identifier.name());
                ValueId identity = resolution == null
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
                        + " has no registered FunctionExecutionBinding (producer defect)");
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
                    return lowerAdapterOverAsync(call, slot, awaitSpan, adapter,
                        identifier.name());
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
                        + "checker admits function-typed callees only)");
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
                    + "' (the checker's E3013 pins the shape)");
            }
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerExpression(argument));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != signature.paramTypes().size()) {
                throw new ConstructUnlowered("async call of '" + calleeName + "' with "
                    + args.size() + " arguments for " + signature.paramTypes().size()
                    + " parameters (the checker admits exact arity only)");
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
                    + identifier.name() + "' (the checker's E3013 pins the shape)");
            }
            context.assignShape(InvocationShape.SOURCE_ASYNC, context.callSiteOpId);
            lowerExpression(identifier);
            return lowerAsyncStart(call, slot, awaitSpan,
                new FunctionExecutionBinding.LoweredBody(context.functionId,
                    context.bodyBlock),
                false);
        }

        /**
         * The shared {@code ASYNC_START}+{@code AWAIT} emission over the
         * resolved binding: the parameter-boundary cells per the closed
         * table, one token per call, and exactly one
         * {@code ASYNC_COMPLETION} boundary at the await site. The
         * arguments lower through {@link #lowerCallArgument}: a declared
         * callee's parameter cell performs the contextual member read's
         * kind check at its own origin, exactly as the synchronous
         * declared-callee arms do.
         */
        private ValueId lowerAsyncStart(CallExpr call, ValueId slot, Span awaitSpan,
                                        FunctionExecutionBinding binding, boolean nested) {
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerCallArgument(argument));
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
                            + " has no lowering context (producer defect)");
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
                            + " parameters (the checker admits exact arity only)");
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
                            + " parameters (the checker admits exact arity only)");
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
                            + " parameters (the checker admits exact arity only)");
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
                            + " has no recorded EXTERNAL_ENTRY in the callee unit");
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
                            + " parameters (the checker admits exact arity only)");
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
                        + "lowerAdapterOverAsync (producer defect)");
                case FunctionExecutionBinding.IntrinsicFunction intrinsic -> {

                    source = AsyncStartSource.DEAL_BODY;
                    startOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    token = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.DEAL_BODY_TASK);
                    if (args.size() != intrinsic.descriptor().paramTypes().size()) {
                        throw new ConstructUnlowered("async intrinsic call with "
                            + args.size() + " arguments for the '" + intrinsic.kind()
                            + "' intrinsic's "
                            + intrinsic.descriptor().paramTypes().size()
                            + " declared parameter(s) (producer defect)");
                    }
                    for (int i = 0; i < args.size(); i++) {
                        SemanticOp boundary = buildChildBoundary(
                            BoundaryKind.FUNCTION_PARAMETER,
                            intrinsic.descriptor().paramTypes().get(i), args.get(i),
                            call.span(), startOpId);
                        parameterBoundaryOps.add(boundary);
                        parameterBoundaryIds.add(boundary.opId());
                    }
                }
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the await call");
            }
            RuntimeDescriptor completion = signature.returnType();
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(call.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(startOpId, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(binding), source,
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
         * terminal per the statically fixed source binding.
         */
        private ValueId lowerAdapterOverAsync(CallExpr call, ValueId slot, Span awaitSpan,
                FunctionExecutionBinding.AdapterBinding adapter, String calleeName) {
            FunctionExecutionBinding source = staticSourceBinding(adapter);
            if (source == null) {
                throw new ConstructUnlowered("adapter-over-async of '" + calleeName
                    + "' whose source identity is not statically fixed "
                    + "(REEVALUATE_THUNK call-result sources are ISSUE-0531's)");
            }
            RuntimeDescriptor.Func target = adapter.targetSignature();
            RuntimeDescriptor.Func sourceSignature = adapter.sourceSignature();
            List<ValueId> args = new ArrayList<>();
            List<RuntimeDescriptor> argTypes = new ArrayList<>();
            for (ExpressionNode argument : call.args()) {
                args.add(lowerExpression(argument));
                argTypes.add(ContainerPayloadDescriptors.resultDescriptorOf(
                    checkedType(argument)));
            }
            if (args.size() != target.paramTypes().size()) {
                throw new ConstructUnlowered("adapter-over-async call of '" + calleeName
                    + "' with " + args.size() + " arguments for "
                    + target.paramTypes().size()
                    + " target parameters (the checker admits exact arity only)");
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
            FailurePolicyId nestedPolicy = FailurePolicyId.NO_DEAL_FAILURE;
            switch (source) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionContext context = contextsByFunctionId.get(body.functionId());
                    if (context == null) {
                        throw new ConstructUnlowered("the adapter's body-source binding "
                            + "has no lowering context (producer defect)");
                    }
                    context.assignShape(InvocationShape.SOURCE_ASYNC, context.callSiteOpId);
                    nestedOpId = context.callSiteUsed
                        ? ids.nextOpId(module, nextOrdinal++, 0) : context.callSiteOpId;
                    context.callSiteUsed = true;
                    nestedReturnBoundary = context.returnBoundaryOpId;
                    nestedSource = AsyncStartSource.DEAL_BODY;
                    sourceToken = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.DEAL_BODY_TASK);
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
                            + " has no recorded EXTERNAL_ENTRY in the callee unit");
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
                case FunctionExecutionBinding.AdapterBinding nestedAdapter ->
                    throw new ConstructUnlowered("nested adapter source of '" + calleeName
                        + "' (adapter-of-adapter invocation is ISSUE-0531's)");
                case FunctionExecutionBinding.IntrinsicFunction intrinsic -> {

                    if (sourceSignature.paramTypes().size()
                            != intrinsic.descriptor().paramTypes().size()) {
                        throw new ConstructUnlowered("the adapter-over-async source of '"
                            + calleeName + "' records "
                            + sourceSignature.paramTypes().size()
                            + " source parameter(s) for the '" + intrinsic.kind()
                            + "' intrinsic's "
                            + intrinsic.descriptor().paramTypes().size()
                            + " declared parameter(s) (producer defect)");
                    }
                    nestedOpId = ids.nextOpId(module, nextOrdinal++, 0);
                    nestedSource = AsyncStartSource.DEAL_BODY;
                    sourceToken = new AsyncTokenId.Canonical(
                        ids.nextTokenId(module, nextOrdinal++, 0),
                        AsyncTokenOwner.DEAL_BODY_TASK);
                }
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw dynamicCarrierDefect(dynamic, "the adapter-over-async call of '"
                        + calleeName + "'");
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
                    new KindPayload.CallCallee.Static(source), nestedSource,
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
            return lowerAwait(outerToken, target.returnType(), awaitSpan, slot);
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
                throw new ConstructUnlowered("return statement outside a function body");
            }
            ValueId value;
            if (statement.expr().isPresent()) {
                value = lowerExpression(statement.expr().get());
            } else {
                if (!(context.signature.returnType() instanceof RuntimeDescriptor.Null)) {
                    throw new ConstructUnlowered("bare return in a function whose return "
                        + "type is not null (the checker requires the value)");
                }
                value = emitValueOp(SemanticOpKind.CONST,
                    new KindPayload.ConstPayload(ScalarValue.Null.INSTANCE),
                    statement.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Null.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId returnOpId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            ensureReturnBoundary(context, value, statement.span(), returnOpId);
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
                new KindPayload.ConstPayload(ScalarValue.Null.INSTANCE), span,
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Null.INSTANCE),
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
                        throw new ConstructUnlowered(describeStatement(statement));
                    }
                    lowerClassDeclaration(classDeclaration, false);
                    continue;
                }
                if (statement instanceof ExportDeclaration exportDeclaration) {
                    if (!e7Calls) {
                        throw new ConstructUnlowered(describeStatement(statement));
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
                                describeStatement(statement));
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
                            + "is a producer defect)");
                    }
                    BindingId aliasCell = importAliasCells.get(importDeclaration.alias());
                    if (aliasCell == null) {
                        throw new ConstructUnlowered("import '" + importDeclaration.alias()
                            + "' without a hoisted alias cell (a missing hoist fact "
                            + "is a producer defect)");
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
                    statementWalk.walk(block.statements(), false);
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
                if (statement instanceof ForStatement forStatement) {
                    if (forStatement.init().isPresent()
                            && forStatement.init().get() instanceof ForInit.VarDecl) {
                        lowerBindingForLet(forStatement);
                    } else {
                        lowerForStatement(forStatement);
                    }
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
                throw new ConstructUnlowered(describeStatement(statement));
            }
        }

        /**
         * Installs the E7 call-machine facts and activates the E7
         * surface: the module's checked exports, the callee-module route
         * facts, the callee modules' recorded {@code EXTERNAL_ENTRY} op
         * ids, and the exported callback function names.
         */
        void setE7Facts(List<ExportInterface> exports, Map<ModuleId, ModuleRoute> routes,
                        Map<ModuleId, Map<String, OpId>> calleeEntries,
                        Set<String> callbacks) {
            if (!fullProgram) {
                throw new IllegalStateException("the E7 surface requires the "
                    + "full-program session (producer defect)");
            }
            this.e7Calls = true;
            this.moduleExports = List.copyOf(exports);
            this.calleeRoutes = Map.copyOf(routes);
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
                // closure (a host/extern-C declaration module): the host
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
                    statementWalk.walk(block.statements(), false);
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
                if (statement instanceof ForStatement forStatement) {
                    lowerForStatement(forStatement);
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
                throw new ConstructUnlowered(describeStatement(statement));
            }
        }

        // ---------------------------------------------------------------------
        // The E5 control-flow arms (control-flow-structures C-D3..C-D8)
        // ---------------------------------------------------------------------

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
            ValueId condition = lowerExpression(statement.condition());
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
                statementWalk.walk(statement.thenBlock().statements(), false);
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
                            statementWalk.walk(right.value().statements(), false);
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
                condition = lowerExpression(statement.condition());
            } finally {
                popBlock();
            }
            pushBlock(bodyBlock);
            pushLoopTarget(opId);
            try {
                statementWalk.walk(statement.body().statements(), false);
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

        /**
         * {@code LOOP(FOR)} — the for-statement arm (C-D4): the
         * one-time {@code initBlock} carries the init ops (an
         * assignment-expression initializer lowers through the address
         * chain; a {@code let}-declared initializer is E6's
         * {@code BINDING_ALLOC} and fails closed) and the first
         * condition production; {@code updateBlock} carries the update
         * ops then the condition-producing ops (the condition
         * {@code ValueId} is produced once in {@code initBlock} and
         * re-produced by the {@code updateBlock} production — one value
         * identity, the most recently produced value wins at
         * execution). Execution: {@code initBlock} once; repeat {
         * condition; body; updateBlock }. A test-less {@code for (;;)}
         * produces exactly one {@code CONST} op with the boolean value
         * {@code true} in {@code initBlock} as the condition production
         * ({@code SYNTHETIC}, the for-statement span) and
         * {@code updateBlock} carries only the update ops — a constant
         * needs no per-iteration re-production. The {@code LOOP} op
         * precedes its child block ops in the unit list (payload order:
         * init, body, update). Block ops record the {@code LOOP} as
         * {@code parentOpId}.
         */
        private void lowerForStatement(ForStatement statement) {
            BlockId initBlock = allocateBlock();
            BlockId bodyBlock = allocateBlock();
            BlockId updateBlock = allocateBlock();
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            ValueId condition = statement.condition().isPresent()
                ? ids.nextValueId(module, nextOrdinal++, 0) : null;
            int mark = emitTarget().size();
            pushBlockParent(opId);
            pushBlock(initBlock);
            try {
                statement.init().ifPresent(init -> {
                    switch (init) {
                        case ForInit.AssignExpr assign -> {
                            lowerExpression(assign.expr());
                        }
                        case ForInit.VarDecl decl -> throw new ConstructUnlowered(
                            "for-loop let-declared initializer (BINDING_ALLOC is E6's, "
                                + "ISSUE-0235)");
                    }
                });
                if (statement.condition().isPresent()) {
                    lowerExpression(statement.condition().get(), condition);
                } else {
                    // The test-less FOR row: exactly one CONST true in
                    // initBlock as the condition production.
                    condition = emitValueOpWith(SemanticOpKind.CONST,
                        new KindPayload.ConstPayload(new ScalarValue.Boolean(true)),
                        statement.span(),
                        ContainerPayloadDescriptors.resultDescriptorOf(
                            Type.Boolean.INSTANCE),
                        FailurePolicyId.NO_DEAL_FAILURE, null, SourceOriginKind.SYNTHETIC);
                }
            } finally {
                popBlock();
            }
            pushBlock(bodyBlock);
            pushLoopTarget(opId);
            try {
                statementWalk.walk(statement.body().statements(), false);
            } finally {
                popLoopTarget();
                popBlock();
            }
            // The composite marking (D3): a literal-true loop (a source
            // `true` condition or the test-less row's synthetic CONST
            // true) whose body exits only by return/throw cannot complete
            // normally; a TRANSFER body (a break path) leaves the loop
            // able to exit normally, so the enclosing block stays OPEN.
            if ((statement.condition().isEmpty()
                    || isLiteralTrue(statement.condition().get()))
                    && exitStateOf(bodyBlock) == BlockExitState.RETURN_OR_THROW) {
                markExit(currentBlock(), BlockExitState.RETURN_OR_THROW);
            }
            pushBlock(updateBlock);
            try {
                statement.update().ifPresent(update -> lowerExpression(update));
                if (statement.condition().isPresent()) {
                    // The per-iteration condition re-production: the same
                    // condition ValueId, re-produced by the updateBlock
                    // production (most recently produced value wins).
                    lowerExpression(statement.condition().get(), condition);
                }
            } finally {
                popBlock();
            }
            popBlockParent();
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emitAt(mark, buildOp(opId, SemanticOpKind.LOOP,
                new KindPayload.LoopPayload(ControlSelector.FOR, initBlock, condition,
                    bodyBlock, updateBlock),
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
                statementWalk.walk(statement.tryBlock().statements(), false);
            } finally {
                popBlock();
            }
            catchFrames.add(0, new CatchFrame(statement.catchVar(), catchBinding));
            pushBlock(catchBlock);
            try {

                BindingCoreIncarnation catchIncarnation = new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, catchBlock, BindingCellKind.DIRECT,
                    true, BindingProducer.BINDING_ALLOC, false);
                emitNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(catchBinding, catchBlock, true,
                        cellKinds.cellKindOf(catchIncarnation), INITIAL_LOOP_GENERATION),
                    statement.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, currentParent());
                statementWalk.walk(statement.catchBlock().statements(), false);
            } finally {
                popBlock();
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
         */
        private void lowerThrow(ThrowStatement statement) {
            ValueId errorValue = lowerExpression(statement.expr());
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
                    + "checker fact is a producer defect)");
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
                    + "checker fact is a producer defect)");
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
                case LiteralValue.IntLiteral integer -> {
                    long value = integer.value();
                    if (value < -2147483648L || value > 2147483647L) {
                        throw new IntLiteralOutOfRange(value);
                    }
                    yield new ScalarValue.Int((int) value);
                }
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
            for (ForEachFrame frame : frames) {
                if (frame.name().equals(identifier.name())) {
                    if (closureCore) {
                        // The iteration binding is a free reference of every
                        // open detached-body walk whose scope chain excludes
                        // it: register the capture through the same frame
                        // resolution the environment serves (R4 item 5).
                        FrameResolution loopResolution = resolveFrame(frame.name());
                        if (loopResolution != null
                                && loopResolution.entry().cell().id.equals(
                                    frame.binding())) {
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
            for (CatchFrame frame : catchFrames) {
                if (frame.name().equals(identifier.name())) {
                    return emitDynamicAwareLoad(
                        new KindPayload.BindingLoadPayload(frame.binding(),
                            INITIAL_LOOP_GENERATION),
                        identifier.span(),
                        ContainerPayloadDescriptors.resultDescriptorOf(type), slot);
                }
            }
            if (!defaultContexts.isEmpty()) {

                DefaultContext context = defaultContexts.peek();
                FrameResolution resolution = resolveFrame(identifier.name());
                if (resolution == null) {
                    throw new ConstructUnlowered("identifier '" + identifier.name()
                        + "' is not a declared binding of the class walk's "
                        + "environment (class/module members are E9's/E10's)");
                }
                BlockId producing = resolution.entry().incarnation().scope();
                if (!producing.equals(moduleInitBlock)
                        && !producing.equals(context.block())
                        && !context.internalBlocks().contains(producing)) {
                    throw new ClassDefaultCapture(identifier.name());
                }
                if (captureCollectors.size() <= context.entryCaptureDepth()) {

                    return emitResolvedLoad(identifier, type, resolution.entry(), slot);
                }
                // A nested detached walk's reference (a closure body
                // inside the default): the closure walk's own capture
                // business (B3) under the admission set checked above;
                // no slot is threaded below the default expression's
                // top level.
                maybeRegisterCapture(identifier.name(), resolution);
                return emitResolvedLoad(identifier, type, resolution.entry(), null);
            }
            if (closureCore) {
                FrameResolution resolution = resolveFrame(identifier.name());
                if (resolution == null) {
                    throw new ConstructUnlowered("identifier '" + identifier.name()
                        + "' is not a declared binding of the closure walk's environment "
                        + "(class/module members are E9's/E10's)");
                }
                maybeRegisterCapture(identifier.name(), resolution);
                return emitResolvedLoad(identifier, type, resolution.entry());
            }

            if (bindingSiteResolver != null) {
                BindingSite site = bindingSiteResolver.resolve(identifier.name());
                if (site != null) {
                    return emitDynamicAwareLoad(
                        new KindPayload.BindingLoadPayload(site.binding(), site.generation()),
                        identifier.span(),
                        ContainerPayloadDescriptors.resultDescriptorOf(type), null);
                }
            }
            throw new ConstructUnlowered("identifier '" + identifier.name()
                + "' is not a load of an enclosing for-of loop binding or catch "
                + "binding in this stage's window (binding allocation, non-loop "
                + "loads, and generation increments/stores are E6's, ISSUE-0235)");
        }

        private ValueId emitResolvedLoad(IdentifierExpr identifier, Type type,
                                         FrameEntry entry) {
            return emitResolvedLoad(identifier, type, entry, null);
        }

        private ValueId emitResolvedLoad(IdentifierExpr identifier, Type type,
                                         FrameEntry entry, ValueId slot) {
            RuntimeDescriptor descriptor = ContainerPayloadDescriptors.resultDescriptorOf(type);
            ValueId result = null;
            if (descriptor instanceof RuntimeDescriptor.Func) {
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
                    + "must be function-typed)");
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
                                    instanceof RuntimeDescriptor.Null)) {
                                throw new ConstructUnlowered("closure body is not "
                                    + "terminated and its return type is not null "
                                    + "(the E7 slice lowers the pinned implicit null "
                                    + "return only)");
                            }
                            lowerImplicitReturn(reservedClosureContext,
                                functionExpr.body().span());
                        }
                        functionStack.pop();
                    }
                    blockStack.pop();
                    popBindingFrame();
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
                functionExpr.span(), captured);
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
                    + typeName(type) + " (a checked ArrayLiteralExpr must be array-typed)");
            }
            RuntimeDescriptor elementDescriptor =
                containerElementDescriptor(arrayType.element());
            List<ValueId> values = new ArrayList<>();
            for (ExpressionNode element : literal.elements()) {
                values.add(lowerExpression(element));
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
                // Function-typed array literal element positions: typed
                // boundary positions never adapt (B6) — the direct
                // value flow into each ARRAY_LITERAL_ELEMENT boundary
                // slot is the wiring E4's boundary producer consumes;
                // the classifier records the closed classifications in
                // element order.
                for (int i = 0; i < literal.elements().size(); i++) {
                    recordBoundaryClassification(BoundaryKind.ARRAY_LITERAL_ELEMENT,
                        checkedType(literal.elements().get(i)), arrayType.element());
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
                        + DescriptorService.semanticModulePath(classType.identity())
                        + "/" + classType.name()
                        + " (class construction and CLASS_NEW are E9's)");
                }
                return lowerClassLiteral(literal, slot);
            }
            if (!(type instanceof Type.Table)) {
                throw new ConstructUnlowered("object literal of non-table, non-class checked "
                    + "type " + typeName(type) + " (a checked ObjectLiteralExpr must be "
                    + "table- or class-typed)");
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
        /**
         * {@code INDEX_READ} (array targets) — the carrier's read arm:
         * the container and the key complete as prior steps;
         * {@code INDEX_NORMALIZE(ARRAY_READ)} computes the slot from the
         * normalize-time {@code ARRAY_LENGTH} read; the
         * {@code ARRAY_ELEMENT_READ} boundary child (policy
         * {@code ARRAY_READ_INDEX_THEN_DESCRIPTOR}) enforces the
         * negative-index E8002 before any storage access and passes a
         * missing (past-end) read through to the consuming contextual
         * boundary/operand — the comparison operands' missing≡null rule
         * and the declaration boundary's E8001 projection decide.
         * Table index reads stay E2's (the carrier slice lowers array
         * index reads only).
         */

        private ValueId lowerBytesIndexRead(IndexExpr index, ValueId slot) {
            RuntimeDescriptor intDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE);
            RuntimeDescriptor resultType = intDescriptor;
            ValueId container = lowerExpression(index.array());
            ValueId key = lowerExpression(index.index());
            ValueId length = emitValueOp(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(container), index.span(),
                intDescriptor, FailurePolicyId.INT32_RESULT);
            ValueId normalize = emitOperandOp(SemanticOpKind.INDEX_NORMALIZE,
                new KindPayload.IndexNormalizePayload(IndexMode.BYTES_READ, key, length),
                List.of(key, length), List.of(intDescriptor, intDescriptor),
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
                    RuntimeDescriptor.Bytes.INSTANCE, intDescriptor),
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            SourceOrigin boundaryOrigin = new SourceOrigin(sourceId,
                toSourceSpan(index.span()), SourceOriginKind.SYNTHETIC, boundaryAnchor,
                opId);
            emit(buildOp(boundaryOpId, SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.BYTE_ELEMENT_READ,
                    resultType, result,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                null, null, FailurePolicyId.BYTES_READ,
                boundaryOrigin));
            // The producer rule's index-read arm: a function-typed element
            // read registers exactly one dynamic materialization; a bytes
            // element read is an int, so the registration never applies.
            return result;
        }

        private ValueId lowerIndexRead(IndexExpr index, ValueId slot) {
            Type objectType = checkedType(index.array());
            if (objectType instanceof Type.Bytes) {
                return lowerBytesIndexRead(index, slot);
            }
            if (!(objectType instanceof Type.Array)) {
                throw new ConstructUnlowered("index read on " + typeName(objectType)
                    + " (the carrier slice lowers array index reads; table index reads "
                    + "are E2's)");
            }
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(index));
            ValueId container = lowerExpression(index.array());
            ValueId key = lowerExpression(index.index());
            ValueId length = emitValueOp(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(container), index.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                FailurePolicyId.INT32_RESULT);
            RuntimeDescriptor intDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE);
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
                        + DescriptorService.semanticModulePath(classType.identity())
                        + "/" + classType.name() + "." + access.field()
                        + " (FIELD_READ is E9's)");
                }
                return lowerFieldRead(access, classType, slot);
            }
            if (classCore && objectType instanceof Type.Nullable nullable
                    && nullable.inner() instanceof Type.Class innerClassType) {

                return lowerFieldRead(access, innerClassType, slot);
            }
            if (objectType instanceof Type.Bytes) {
                if ("length".equals(access.field())) {
                    // K6 item 7: b.length reads the fixed logical length
                    // through the existing ARRAY_LENGTH op with an int
                    // result and the INT32_RESULT terminal.
                    return lowerArrayLength(access, slot);
                }
                throw new ConstructUnlowered("member access '" + access.field()
                    + "' on bytes (the checker admits .length only; a bytes member "
                    + "access reaching an E5 arm fails hard)");
            }
            throw new ConstructUnlowered("member access '" + access.field() + "' on "
                + typeName(objectType) + " (no member-access arm for this receiver shape "
                + "in this stage's window)");
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
            try {
                classDescriptor = DescriptorService.describe(classType);
            } catch (DescriptorService.Defect defect) {
                throw new ConstructUnlowered("class member access "
                    + classId + "." + access.field() + " on an unrepresentable receiver "
                    + "type (" + defect.getMessage() + ")");
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
                new KindPayload.FieldReadPayload(receiver, classId, access.field()),
                result, resultDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            emit(receiverBoundary);
            emit(fieldBoundary);
            // The producer rule's field-read arm: a function-typed field
            // read registers exactly one dynamic materialization keyed by
            // the read's result identity (the read allocates it).
            registerDynamicMaterialization(result, opId, resultDescriptor);
            return result;
        }

        private ValueId lowerHasField(HasExpr has, ValueId slot) {
            ValueId receiver = lowerExpression(has.object());
            return emitValueOp(SemanticOpKind.HAS_FIELD,
                new KindPayload.HasFieldPayload(receiver, has.field()),
                has.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Boolean.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /** {@code ARRAY_LENGTH} — the receiver is one prior step, never re-evaluated. */
        private ValueId lowerArrayLength(MemberAccessExpr access) {
            return lowerArrayLength(access, null);
        }

        private ValueId lowerArrayLength(MemberAccessExpr access, ValueId slot) {
            ValueId receiver = lowerExpression(access.object());
            return emitValueOp(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(receiver), access.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                FailurePolicyId.INT32_RESULT, slot);
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
            Type contextualType = checkedType(access);
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(contextualType);
            ValueId receiver = lowerExpression(access.object());
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(access.span()),
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
                    new KindPayload.MemberReadPayload(receiver, access.field()),
                    readResult, InternalResultType.INTERNAL_MISSING,
                    FailurePolicyId.NO_DEAL_FAILURE, origin));
                ValueId result = slot != null ? slot
                    : ids.nextValueId(module, nextOrdinal++, 0);
                AnchorId optionalAnchor = ids.nextAnchorId(module, nextOrdinal++, 0);
                OpId optionalOpId = ids.nextOpId(module, nextOrdinal++, 0);
                SourceOrigin optionalOrigin = new SourceOrigin(sourceId,
                    toSourceSpan(access.span()), SourceOriginKind.USER, optionalAnchor,
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
                    access.span(), childPolicy, SourceOriginKind.SYNTHETIC, optionalOpId);
                if (creationRuleAnalysis && resultType instanceof RuntimeDescriptor.Func) {
                    recordBoundaryClassification(BoundaryKind.CONTEXTUAL_TABLE_READ,
                        contextualType, contextualType);
                }
                return result;
            }
            ValueId readResult = slot != null ? slot
                : ids.nextValueId(module, nextOrdinal++, 0);
            emit(buildOp(opId, SemanticOpKind.MEMBER_READ,
                new KindPayload.MemberReadPayload(receiver, access.field()),
                readResult, resultType, FailurePolicyId.NO_DEAL_FAILURE, origin));
            if (callArgumentReads.contains(access)
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
                access.span(), childPolicy, SourceOriginKind.SYNTHETIC, opId);
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
                && !(descriptor instanceof RuntimeDescriptor.Bytes);
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
                + "STRING_CONCAT, never BINARY)");
        }

        /** True iff the operator is one of the six comparison operators (B-D3). */
        private static boolean isComparisonOperator(BinaryOp op) {
            return switch (op) {
                case EQ, NEQ, LT, LTE, GT, GTE -> true;
                case ADD, SUB, MUL, DIV, MOD, POW, AND, OR -> false;
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
            ValueId left = lowerExpression(binary.left());
            ValueId right = lowerExpression(binary.right());
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
                    + "operands — a missing checker fact is a producer defect)");
            }
            ControlSelector selector = binary.op() == BinaryOp.AND
                ? ControlSelector.LOGICAL_AND : ControlSelector.LOGICAL_OR;
            ValueId left = lowerExpression(binary.left());
            BlockId selectedBlock = allocateBlock();
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            int mark = emitTarget().size();
            pushBlockParent(opId);
            pushBlock(selectedBlock);
            ValueId right;
            try {
                right = lowerExpression(binary.right(), slot);
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
            ValueId left = lowerExpression(binary.left());
            ValueId right = lowerExpression(binary.right());
            return emitValueOp(SemanticOpKind.STRING_CONCAT,
                new KindPayload.StringConcatPayload(List.of(left, right)),
                binary.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /** True iff the operator is one of the six arithmetic operators (I3). */
        private static boolean isArithmeticOperator(BinaryOp op) {
            return switch (op) {
                case ADD, SUB, MUL, DIV, MOD, POW -> true;
                case EQ, NEQ, LT, LTE, GT, GTE, AND, OR -> false;
            };
        }

        /**
         * {@code BINARY} — the I3 int32/number arithmetic arm: exactly one
         * closed selector from the operator plus the checked operand type
         * ({@code INT32_ADD/SUB/MUL/DIV_TRUNC/MOD_TRUNC/POW} over int;
         * {@code NUMBER_ADD/SUB/MUL/DIV_IEEE/MOD_FLOOR/POW_IEEE} over
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
            if (leftType instanceof Type.Int) {
                selector = switch (binary.op()) {
                    case ADD -> BinarySelector.INT32_ADD;
                    case SUB -> BinarySelector.INT32_SUB;
                    case MUL -> BinarySelector.INT32_MUL;
                    case DIV -> BinarySelector.INT32_DIV_TRUNC;
                    case MOD -> BinarySelector.INT32_MOD_TRUNC;
                    case POW -> BinarySelector.INT32_POW;
                    default -> throw new ConstructUnlowered("arithmetic operator "
                        + binary.op() + " outside the I3 int32 rows (producer defect)");
                };
            } else if (leftType instanceof Type.Number) {
                selector = switch (binary.op()) {
                    case ADD -> BinarySelector.NUMBER_ADD;
                    case SUB -> BinarySelector.NUMBER_SUB;
                    case MUL -> BinarySelector.NUMBER_MUL;
                    case DIV -> BinarySelector.NUMBER_DIV_IEEE;
                    case MOD -> BinarySelector.NUMBER_MOD_FLOOR;
                    case POW -> BinarySelector.NUMBER_POW_IEEE;
                    default -> throw new ConstructUnlowered("arithmetic operator "
                        + binary.op() + " outside the I3 number rows (producer defect)");
                };
            } else {
                throw new ConstructUnlowered("arithmetic binary " + binary.op()
                    + " over non-int/number checked operand " + typeName(leftType)
                    + " (a checked arithmetic expression must be int- or number-typed)");
            }
            ValueId left = lowerExpression(binary.left());
            ValueId right = lowerExpression(binary.right());
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
                case NOT -> {
                    if (!(operandType instanceof Type.Boolean)) {
                        throw new ConstructUnlowered("unary '!' over non-boolean checked "
                            + "operand " + typeName(operandType)
                            + " (a missing checker fact is a producer defect)");
                    }
                    selector = UnarySelector.BOOL_NOT;
                }
                case NEG -> {
                    if (operandType instanceof Type.Int) {
                        selector = UnarySelector.INT32_NEG;
                    } else if (operandType instanceof Type.Number) {
                        selector = UnarySelector.NUMBER_NEG;
                    } else {
                        throw new ConstructUnlowered("unary '-' over non-int/number checked "
                            + "operand " + typeName(operandType)
                            + " (a missing checker fact is a producer defect)");
                    }
                }
                default -> throw new ConstructUnlowered("unary operator " + unary.op()
                    + " outside the closed UnarySelector rows (producer defect)");
            }
            ValueId operand = lowerExpression(unary.expr());
            return emitOperandOp(SemanticOpKind.UNARY,
                new KindPayload.UnaryPayload(selector),
                List.of(operand), List.of(valueDescriptorOf(operandType)),
                unary.span(), valueDescriptorOf(checkedType(unary)),
                SemanticIrValidator.unaryPolicy(selector), slot);
        }

        /**
         * {@code INTRINSIC_CALL} — the I3 arm: a call of the {@code int}
         * or {@code number} intrinsic ({@code Symbol.IntrinsicSymbol})
         * lowers to one {@code INTRINSIC_CALL} with
         * {@code INT_CONVERT}/{@code NUMBER_CONVERT}, zero {@code BOUNDARY}
         * children, the single input as one prior step, and the conversion
         * policy ({@code INT_CONVERSION}/{@code NUMBER_CONVERSION} — the
         * single closed intrinsic rule) as the terminal check. Every other
         * call is a foreign construct ({@code CALL} is E7's); {@code
         * bytes(...)} is the bytes exclusion.
         */
        private ValueId lowerIntrinsicCall(CallExpr call) {
            return lowerIntrinsicCall(call, null);
        }

        private ValueId lowerIntrinsicCall(CallExpr call, ValueId slot) {
            IntrinsicKind kind = intrinsicKindOf(call);
            ExpressionNode argument = call.args().get(0);
            Type argumentType = checkedType(argument);
            ValueId input = lowerExpression(argument);
            if (kind == IntrinsicKind.BYTES_NEW) {
                // K6 item 1: the allocation intrinsic's pinned int input
                // descriptor and bytes result descriptor, with the
                // BYTES_ALLOCATE terminal policy at the call expression.
                RuntimeDescriptor intDescriptor =
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE);
                return emitOperandOp(SemanticOpKind.INTRINSIC_CALL,
                    new KindPayload.IntrinsicCallPayload(kind, input),
                    List.of(input), List.of(intDescriptor),
                    call.span(), RuntimeDescriptor.Bytes.INSTANCE,
                    SemanticIrValidator.intrinsicPolicy(kind), slot);
            }
            return emitOperandOp(SemanticOpKind.INTRINSIC_CALL,
                new KindPayload.IntrinsicCallPayload(kind, input),
                List.of(input), List.of(valueDescriptorOf(argumentType)),
                call.span(), valueDescriptorOf(checkedType(call)),
                SemanticIrValidator.intrinsicPolicy(kind), slot);
        }

        /**
         * The intrinsic-call classifier (I3): exactly {@code int(...)},
         * {@code number(...)}, and {@code bytes(...)} calls of the root
         * {@code Symbol.IntrinsicSymbol} bindings lower; every other call
         * shape — including {@code has(...)} call fallbacks and ordinary
         * user calls — fails closed.
         */
        private IntrinsicKind intrinsicKindOf(CallExpr call) {
            if (call.args().size() == 1
                    && call.callee() instanceof IdentifierExpr identifier) {
                Symbol symbol = checks.symbolTable().resolve(identifier.name());
                if (symbol instanceof Symbol.IntrinsicSymbol intrinsic) {
                    IntrinsicKind kind = conversionIntrinsicKind(intrinsic.name());
                    if (kind != null) {
                        return kind;
                    }
                }
            }
            throw new ConstructUnlowered("call expression (only the int()/number() "
                + "conversion and bytes() allocation intrinsic calls lower to "
                + "INTRINSIC_CALL; the CALL machinery is the calls slice's)");
        }

        /**
         * The conversion/allocation intrinsics' name-to-kind map (the
         * seed's and the intrinsic-call classifier's single authority):
         * {@code int} is {@code INT_CONVERT}, {@code number} is
         * {@code NUMBER_CONVERT}, and {@code bytes} is
         * {@code BYTES_NEW}; every other name — including the
         * {@code has} intrinsic — maps to no intrinsic kind.
         *
         */
        private static IntrinsicKind conversionIntrinsicKind(String name) {
            return switch (name) {
                case "int" -> IntrinsicKind.INT_CONVERT;
                case "number" -> IntrinsicKind.NUMBER_CONVERT;
                case "bytes" -> IntrinsicKind.BYTES_NEW;
                default -> null;
            };
        }

        private static RuntimeDescriptor valueDescriptorOf(Type type) {
            boolean admissible = switch (type) {
                case Type.Null ignored -> true;
                case Type.Boolean ignored -> true;
                case Type.Int ignored -> true;
                case Type.Number ignored -> true;
                case Type.String ignored -> true;
                case Type.Nullable nullable -> switch (nullable.inner()) {
                    case Type.Boolean ignored -> true;
                    case Type.Int ignored -> true;
                    case Type.Number ignored -> true;
                    default -> false;
                };
                default -> false;
            };
            if (!admissible) {
                throw new ConstructUnlowered(
                    "value-slice descriptor position over non-scalar checked type "
                        + typeName(type) + " (the slice's pinned one-line scalar rows "
                        + "only; the structural DescriptorService is ISSUE-0233's)");
            }
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
                            + "literal strings and interpolations — a parser defect)");
                    }
                    fragments.add(lowerConst(literal));
                } else {
                    fragments.add(lowerExpression(part));
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
                    + "defect — never an invented op)");
            }
            return type;
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

        /**
         * Emits one value-producing SYNTHETIC op without operands (the
         * for-let body-top generation-0 carry load — a synthetic step
         * with no source expression of its own) and returns its value id.
         */
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

        /**
         * Emits one value-producing USER op with the given result slot:
         * the op publishes {@code slot} instead of a fresh value
         * ({@code null} allocates one) — the pinned mechanism of the
         * {@code LOOP(FOR)} condition re-production (one condition value
         * identity, re-produced by each block execution).
         */
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
                case Type.Class classType -> "@"
                    + DescriptorService.semanticModulePath(classType.identity())
                    + "/" + classType.name();
                case Type.Func func -> "function";
                default -> String.valueOf(type);
            };
        }

        /** The foreign-expression description of the E6005 origin. */
        private String describeExpression(ExpressionNode expr) {
            return switch (expr) {
                case deal.ast.CallExpr ignored ->
                    "call expression (only the int()/number() conversion and bytes() "
                        + "allocation intrinsic calls lower in this slice — "
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
                case deal.ast.ForStatement ignored ->
                    "for statement (LOOP(FOR) is the E5 control-flow arm)";
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
