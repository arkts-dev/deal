package deal.semantic;

import deal.ast.ArrayLiteralExpr;
import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.Block;
import deal.ast.ExpressionNode;
import deal.ast.ForOfStatement;
import deal.ast.IdentifierExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Property;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
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
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The container/string construct stage of the common lowerer (ISSUE-0232
 * D1/D7; ISSUE-0386): the per-construct shape map producing the six
 * container/string operations plus the operand-producer arms from checked
 * source, inside {@code deal.semantic}.
 *
 * <p><b>The D1 shape map (pinned).</b> Every arm below produces exactly
 * the design's closed shape — op kind, payload fields, failure policy,
 * result/operand types, child order, {@code parentOpId}, and origin span.
 * Payload-referenced values are prior ordered steps in source order and
 * these ops carry empty operand lists (schema-pinned); every source
 * subexpression appears exactly once as a producing op; no consumer may
 * infer an omitted source step:</p>
 *
 * <ul>
 *   <li>scalar {@link LiteralExpr} → one {@code CONST
 *       {value: ScalarValue}}, result type {@code D(checked type)},
 *       policy {@code NO_DEAL_FAILURE}, origin = the literal span
 *       (template literal parts reuse this exact arm);</li>
 *   <li>{@link IdentifierExpr} in an operand position → one
 *       {@code BINDING_LOAD {binding, generation}} per the loop-binding
 *       load-resolution rule: a load of an enclosing for-of loop binding
 *       carries that {@code FOR_EACH} payload's initial generation
 *       ({@link #INITIAL_LOOP_GENERATION}); the payload is never
 *       rewritten per iteration and the body-runner substitutes
 *       initial + iteration index at execution. Every other identifier
 *       is a foreign construct in this stage's window — an unresolvable
 *       identifier is a producer defect (E6005), never an invented op;
 *       binding allocation, non-loop loads, and generation
 *       increments/stores are E6's;</li>
 *   <li>{@link ArrayLiteralExpr} with checked {@code Type.Array(T)} → one
 *       {@code ARRAY_NEW {elementDescriptor: D(T), values: element
 *       ValueIds in source order, elementBoundaryOpIds: child op ids in
 *       the same order}} plus one {@code BOUNDARY} child per element in
 *       the same source order — kind {@code ARRAY_LITERAL_ELEMENT},
 *       descriptor {@code D(T)}, policy
 *       {@code ARRAY_ELEMENT_DESCRIPTOR}, input = the element
 *       {@code ValueId}, realization
 *       {@code RuntimeValidation("runtime-validation")}
 *       ({@link #CANONICAL_RUNTIME_VALIDATION_ID}), {@code parentOpId} =
 *       the {@code ARRAY_NEW} op id, origin = the element expression
 *       span. An empty literal produces zero values and zero children;</li>
 *   <li>{@link ObjectLiteralExpr} with checked {@code Type.Table} → one
 *       {@code TABLE_NEW {entries: [(key, value ValueId) in source
 *       order]}}; keys are the identifier property names; duplicate keys
 *       stay in source order in the payload — the executor's
 *       {@code SemanticTable.put} pins the later value and the first
 *       position;</li>
 *   <li>{@link MemberAccessExpr} {@code .length} on an array-typed
 *       object → one {@code ARRAY_LENGTH {arrayValue}}, result type
 *       {@code int}, policy {@code INT32_RESULT}; the receiver is one
 *       prior step and is never re-evaluated;</li>
 *   <li>{@link MemberAccessExpr} on a table-typed object in a checked
 *       read context → one {@code MEMBER_READ {table, key}} (result type
 *       {@code D(contextual type)}, policy {@code NO_DEAL_FAILURE}, the
 *       receiver {@code ValueId} exactly once, the key the constant
 *       identifier string, never evaluated) plus exactly one
 *       {@code BOUNDARY} child — kind {@code CONTEXTUAL_TABLE_READ},
 *       descriptor {@code D(contextual type)}, policy by the
 *       descriptor-kind rule ({@code TYPE_DESCRIPTOR} for non-function
 *       descriptors, {@code FUNCTION_SIGNATURE} for function
 *       descriptors), input = the {@code MEMBER_READ} result
 *       {@code ValueId}, realization
 *       {@code RuntimeValidation("runtime-validation")},
 *       {@code parentOpId} = the {@code MEMBER_READ} op id, origin = the
 *       member-access span;</li>
 *   <li>{@link BinaryExpr} with {@code BinaryOp.ADD} and checked
 *       {@code Type.String} → one {@code STRING_CONCAT {fragments: [left
 *       ValueId, right ValueId] in source order}} — string {@code +}
 *       never lowers to {@code BINARY};</li>
 *   <li>{@link TemplateLiteralExpr} → per part in source order: each
 *       literal part (even index) is one {@code CONST
 *       {value: ScalarValue.String(part text)}} step and each
 *       interpolation (odd index, string-typed per E3016) is the
 *       interpolation expression's producing op chain; then one
 *       {@code STRING_CONCAT {fragments: [part ValueIds in source
 *       order]}}. A template with no interpolations still produces
 *       {@code STRING_CONCAT} with one fragment — no folding;</li>
 *   <li>{@link ForOfStatement} with a {@code string}-typed iterable →
 *       one {@code FOR_EACH {mode: STRING_SCALARS, iterable, binding,
 *       generation, body}}, result none, policy
 *       {@code TYPE_DESCRIPTOR}, origin = the for-of span; the iterable
 *       expression is one prior step. The body block is lowered
 *       recursively under the loop-binding frame; every statement other
 *       than a for-of (or a transparent block of them) reaching the body
 *       arm fails hard.</li>
 * </ul>
 *
 * <p><b>Fail-closed arms (exactly one outcome each).</b> A construct
 * reaching an arm without a lowering arm raises {@link ConstructUnlowered}
 * — array for-of ({@code FOR_EACH(ARRAY_VALUES)} is E5's), class-typed
 * object literals ({@code CLASS_NEW} is E9's), class member access
 * ({@code FIELD_READ} is E9's), module member access ({@code EXPORT_READ}
 * is E10's), {@code .length} on bytes (ISSUE-0158), numeric selectors
 * (ISSUE-0231's {@code UNARY}/{@code BINARY} arms), and every other
 * foreign construct. The unit-production seam converts the defect to the
 * pinned E6005 {@code CONSTRUCT_UNLOWERED} diagnostic through
 * {@link FailureContractRegistry} with the exact
 * {@link LoweringFailureDetail} — a hard compile failure in this stage's
 * window, never a reroute; LEGACY routing for modules containing
 * unlowerable constructs is the plan-time capability-gating consequence,
 * never caused by this E6005. Descriptor positions use
 * {@link ContainerPayloadDescriptors}; a {@code Type.Bytes}/{@code
 * Type.Error} derivation converts at the same seam to E6005
 * {@code DESCRIPTOR_UNREPRESENTABLE} — never an invented descriptor,
 * never a crash.</p>
 *
 * <p><b>IDs and determinism (D4/D8/D10).</b> Every id is allocated
 * through the project's {@link SemanticIdAllocator} in the pinned order —
 * dependency order, source order, semantic role, then synthetic ordinal —
 * realized here as one strictly increasing per-module source-ordinal
 * sequence handed out in the pinned emission order (element prior steps
 * before the consuming op; boundary children after their parent op;
 * iterable prior steps before the {@code FOR_EACH} op; body ops after
 * it), with the closed role per id kind. The same checked source lowered
 * twice through fresh allocators produces byte-identical validated units
 * and dumps.</p>
 *
 * <p><b>Claiming (D9 item 4, E3 window).</b> Units built by this stage
 * claim the empty capability set: every produced op's home row is
 * inactive during E3's tail and the recorded staged hand-offs are the
 * claiming seam's (C5) surface, not these arms'. The manifest's plan-time
 * claims are routing facts and stay untouched.</p>
 */
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

    /**
     * The pinned initial generation of every for-of loop binding (D1/D6):
     * the {@code FOR_EACH} payload's {@code generation} field is this
     * initial generation, never rewritten per iteration; loads of the
     * loop binding inside the body carry it and the body-runner resolves
     * the effective generation as initial + current iteration index at
     * execution. Binding allocation and generation increments/stores are
     * E6's (ISSUE-0235); this stage only pins the payload value.
     */
    public static final long INITIAL_LOOP_GENERATION = 0L;

    private SemanticLowerer() {
        // Static entry points plus the per-module lowering session; no instances.
    }

    /**
     * A construct reaching an arm of this stage without a lowering arm
     * (D7): internal control flow, converted at the unit-production seam
     * to the pinned E6005 {@code CONSTRUCT_UNLOWERED} diagnostic — a hard
     * compile failure in this stage's window, never a reroute and never a
     * crash.
     */
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

    /**
     * The unit-production seam's failure carrier (D2/D7): maps a defect
     * raised by an arm to its exact {@link LoweringFailureDetail} —
     * {@code ConstructUnlowered} → E6005 {@code CONSTRUCT_UNLOWERED}
     * (capability {@code CONTAINERS_AND_STRINGS}, origin
     * {@code SemanticLowerer CONSTRUCT_UNLOWERED (construct)}), and a
     * {@link ContainerPayloadDescriptors.Defect} → E6005
     * {@code DESCRIPTOR_UNREPRESENTABLE} through the bridge's pinned
     * carrier. The caller converts the returned detail into the E6005
     * diagnostic through {@code FailureContractRegistry.e6005(detail)};
     * this seam constructs no diagnostic itself.
     *
     * @param module the module whose unit-production seam hit the defect;
     *               non-null
     * @param defect the defect raised by an arm; non-null
     * @return the exact {@code LoweringFailureDetail} of the named failure
     * @throws IllegalArgumentException on a defect kind this stage has no
     *         E6005 projection for (a producer defect)
     */
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
        if (defect instanceof ContainerPayloadDescriptors.Defect descriptorDefect) {
            return ContainerPayloadDescriptors.loweringFailureDetail(module, descriptorDefect);
        }
        throw new IllegalArgumentException(
            "a defect kind without an E6005 projection in this stage reached the seam: "
                + defect.getClass().getSimpleName());
    }

    /**
     * The result of the module-level unit production: the validated unit,
     * or {@code null} with exactly the first E6005 diagnostic on failure
     * (a lowered-away construct, an unrepresentable descriptor, or a
     * validator rejection of the produced unit).
     *
     * @param unit        the validated {@link LoweredModuleUnit}, or
     *                    {@code null} on failure
     * @param diagnostics empty on success, otherwise the failure
     *                    diagnostics
     */
    public record LoweringResult(LoweredModuleUnit unit, List<CompilerDiagnostic> diagnostics) {

        public LoweringResult {
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            diagnostics = List.copyOf(diagnostics);
        }

        /** True iff the lowering failed (no unit was produced). */
        public boolean hasErrors() {
            return !diagnostics.isEmpty();
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
     * Lowers one checked implementation module through this stage and
     * produces the validated unit (the unit-production seam, S1): the
     * module's top-level statements lower through the positionable
     * statement arms (for-of statements — transparent blocks recurse;
     * every other statement is a foreign construct and fails
     * E6005 {@code CONSTRUCT_UNLOWERED}), the manifest's
     * construct-coverage rows are recorded onto the unit at lowering
     * start, descriptor defects convert to E6005
     * {@code DESCRIPTOR_UNREPRESENTABLE}, and the produced unit must pass
     * the closed validator (the first rejection is the returned
     * diagnostic). In E3's window the unit claims the empty capability
     * set (D9 item 4).
     *
     * @param module                the checked implementation module; non-null
     * @param constructCoverage     the manifest's reachable-construct rows
     *                              recorded at lowering start (S1); non-null
     * @param interfaceHash         the interface index digest the unit is
     *                              checked against (R-PROFILE); non-null
     * @param capabilityRegistryHash the invocation's capability-registry
     *                              digest (R-PROFILE); non-null
     * @param allocator             the project's semantic-id allocator in
     *                              dependency order; non-null
     * @return the validated unit, or the first E6005 on failure
     */
    public static LoweringResult lowerModule(CheckedModuleInput module,
                                             Map<ConstructKind, List<SemanticOpKind>>
                                                 constructCoverage,
                                             String interfaceHash,
                                             String capabilityRegistryHash,
                                             SemanticIdAllocator allocator) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator);
        try {
            lowerer.lowerStatements(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered))));
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect))));
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new LoweringResult(null, List.of(validation.get()));
        }
        return new LoweringResult(unit, List.of());
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
     * {@code FOR_EACH} op, then the body ops — so {@link #ops()} is the
     * unit's produced-operation list in source order and the id
     * allocation sequence follows the same order.</p>
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
         * Creates one lowering session. The module-init block is the
         * session's first allocation (role {@code BLOCK}).
         *
         * @param module   the module identity; non-null
         * @param sourceId the stable source identity carried on every
         *                 op's origin; non-null
         * @param checks   the module's checked facts (read-only); non-null
         * @param ids      the project's allocator in dependency order;
         *                 non-null
         */
        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids) {
            this.module = Objects.requireNonNull(module, "module must not be null");
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            this.checks = Objects.requireNonNull(checks, "checks must not be null");
            this.ids = Objects.requireNonNull(ids, "ids must not be null");
            this.moduleInitBlock = ids.nextBlockId(module, nextOrdinal++, 0);
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
         * @param name the loop-binding name; non-null
         * @return the opened frame
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
         * @throws IllegalStateException when no frame is open
         */
        public void closeForEachScope() {
            if (frames.isEmpty()) {
                throw new IllegalStateException(
                    "closeForEachScope without an open loop-binding frame (producer defect)");
            }
            frames.remove(0);
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
         * @param expr the checked expression; non-null
         * @return the produced {@link ValueId}
         * @throws ConstructUnlowered on a construct without an arm in this
         *         stage's window
         */
        public ValueId lowerExpression(ExpressionNode expr) {
            Objects.requireNonNull(expr, "expr must not be null");
            return switch (expr) {
                case LiteralExpr literal -> lowerConst(literal);
                case IdentifierExpr identifier -> lowerBindingLoad(identifier);
                case ArrayLiteralExpr array -> lowerArrayNew(array);
                case ObjectLiteralExpr object -> lowerTableNew(object);
                case MemberAccessExpr access -> lowerMemberAccess(access);
                case BinaryExpr binary -> lowerBinary(binary);
                case TemplateLiteralExpr template -> lowerTemplate(template);
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
         * @param stmt the checked for-of statement; non-null
         * @return the produced {@code FOR_EACH} op id
         * @throws ConstructUnlowered on a non-string iterable or on a
         *         body statement without an arm
         */
        public OpId lowerForOfStatement(ForOfStatement stmt) {
            Objects.requireNonNull(stmt, "stmt must not be null");
            Type iterableType = checkedType(stmt.iterable());
            if (iterableType instanceof Type.Array) {
                throw new ConstructUnlowered("array for-of (FOR_EACH(ARRAY_VALUES) is E5's, "
                    + "ISSUE-0234; an array-typed iterable reaching an E3 arm fails hard)");
            }
            if (!(iterableType instanceof Type.String)) {
                throw new ConstructUnlowered("for-of over a non-string, non-array iterable "
                    + typeName(iterableType) + " (this stage lowers string iterables only)");
            }
            BlockId bodyBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            ValueId iterable = lowerExpression(stmt.iterable());
            ForEachFrame frame = openForEachScope(stmt.varName());
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(stmt.span()),
                SourceOriginKind.USER, anchor, null);
            ops.add(buildOp(opId, SemanticOpKind.FOR_EACH,
                new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, iterable,
                    frame.binding(), frame.generation(), bodyBlock),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, origin));
            try {
                lowerStatements(stmt.body().statements());
            } finally {
                closeForEachScope();
            }
            return opId;
        }

        // ---------------------------------------------------------------------
        // Unit production (S1)
        // ---------------------------------------------------------------------

        /**
         * Builds the immutable unit over the session's produced
         * operations: the manifest's construct-coverage rows recorded at
         * lowering start (each row must carry the closed construct→op
         * detector table verbatim, S4), the empty capability claim set
         * (D9 item 4 — E3's tail units claim ∅), the module-init plan
         * over the session's init block, and the produced operations in
         * source order.
         *
         * @param constructCoverage      the manifest's reachable-construct
         *                               rows; non-null
         * @param imports                the module's resolved imports in
         *                               dependency order; non-null
         * @param interfaceHash          the interface index digest
         *                               (R-PROFILE); non-null
         * @param capabilityRegistryHash the invocation's
         *                               capability-registry digest
         *                               (R-PROFILE); non-null
         * @return the immutable unit
         */
        public LoweredModuleUnit buildUnit(Map<ConstructKind, List<SemanticOpKind>>
                                               constructCoverage,
                                           List<ModuleId> imports,
                                           String interfaceHash,
                                           String capabilityRegistryHash) {
            Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
            Objects.requireNonNull(imports, "imports must not be null");
            Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
            Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
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
            return new LoweredModuleUnit(
                LoweredModuleUnit.FORMAT_VERSION,
                SemanticProfile.DEAL_V1_2_INT32,
                module,
                interfaceHash,
                LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32,
                    capabilityRegistryHash),
                EnumSet.noneOf(SemanticCapability.class),
                coverage,
                Map.of(),
                Map.of(),
                new ModuleInitPlan(List.copyOf(imports), moduleInitBlock),
                ExportPlan.empty(),
                Map.of(),
                ops());
        }

        // ---------------------------------------------------------------------
        // Statement arms (E3's positionable window)
        // ---------------------------------------------------------------------

        private void lowerStatements(List<StatementNode> statements) {
            for (StatementNode statement : statements) {
                if (statement instanceof ForOfStatement forOf) {
                    lowerForOfStatement(forOf);
                    continue;
                }
                if (statement instanceof Block block) {
                    lowerStatements(block.statements());
                    continue;
                }
                throw new ConstructUnlowered(describeStatement(statement));
            }
        }

        // ---------------------------------------------------------------------
        // The per-construct arms (D1)
        // ---------------------------------------------------------------------

        /** {@code CONST} — the scalar-literal and template-fragment arm. */
        private ValueId lowerConst(LiteralExpr literal) {
            Type type = checkedType(literal);
            ScalarValue scalar = switch (literal.value()) {
                case LiteralValue.NullLiteral ignored -> ScalarValue.Null.INSTANCE;
                case LiteralValue.BooleanLiteral bool -> new ScalarValue.Boolean(bool.value());
                case LiteralValue.IntLiteral integer -> new ScalarValue.Int((int) integer.value());
                case LiteralValue.NumberLiteral number ->
                    new ScalarValue.Number(number.value());
                case LiteralValue.StringLiteral string ->
                    new ScalarValue.String(string.value());
            };
            return emitValueOp(SemanticOpKind.CONST, new KindPayload.ConstPayload(scalar),
                literal.span(), ContainerPayloadDescriptors.resultDescriptorOf(type),
                FailurePolicyId.NO_DEAL_FAILURE);
        }

        /**
         * {@code BINDING_LOAD} — the identifier arm with the loop-binding
         * load-resolution rule: the load carries the innermost matching
         * enclosing {@code FOR_EACH} payload's initial generation; any
         * other identifier is a foreign construct (E6005).
         */
        private ValueId lowerBindingLoad(IdentifierExpr identifier) {
            Type type = checkedType(identifier);
            for (ForEachFrame frame : frames) {
                if (frame.name().equals(identifier.name())) {
                    return emitValueOp(SemanticOpKind.BINDING_LOAD,
                        new KindPayload.BindingLoadPayload(frame.binding(), frame.generation()),
                        identifier.span(), ContainerPayloadDescriptors.resultDescriptorOf(type),
                        FailurePolicyId.NO_DEAL_FAILURE);
                }
            }
            throw new ConstructUnlowered("identifier '" + identifier.name()
                + "' is not a load of an enclosing for-of loop binding in this stage's "
                + "window (binding allocation, non-loop loads, and generation "
                + "increments/stores are E6's, ISSUE-0235)");
        }

        /** {@code ARRAY_NEW} — element prior steps, the op, then the boundary children. */
        private ValueId lowerArrayNew(ArrayLiteralExpr literal) {
            Type type = checkedType(literal);
            if (!(type instanceof Type.Array arrayType)) {
                throw new ConstructUnlowered("array literal of non-array checked type "
                    + typeName(type) + " (a checked ArrayLiteralExpr must be array-typed)");
            }
            RuntimeDescriptor elementDescriptor =
                ContainerPayloadDescriptors.elementDescriptorOf(arrayType.element());
            List<ValueId> values = new ArrayList<>();
            for (ExpressionNode element : literal.elements()) {
                values.add(lowerExpression(element));
            }
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
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
                SourceOriginKind.USER, anchor, null);
            ops.add(buildOp(opId, SemanticOpKind.ARRAY_NEW,
                new KindPayload.ArrayNewPayload(elementDescriptor, values, boundaryIds),
                result, ContainerPayloadDescriptors.resultDescriptorOf(arrayType),
                FailurePolicyId.NO_DEAL_FAILURE, origin));
            ops.addAll(children);
            return result;
        }

        /** {@code TABLE_NEW} — entry values in source order, then the op. */
        private ValueId lowerTableNew(ObjectLiteralExpr literal) {
            Type type = checkedType(literal);
            if (type instanceof Type.Class classType) {
                throw new ConstructUnlowered("class-typed object literal "
                    + classType.modulePath() + "/" + classType.name()
                    + " (class construction and CLASS_NEW are E9's)");
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
                FailurePolicyId.NO_DEAL_FAILURE);
        }

        /** {@code ARRAY_LENGTH}/{@code MEMBER_READ} — the member-access dispatch. */
        private ValueId lowerMemberAccess(MemberAccessExpr access) {
            // Module member access first: the checker types a module-symbol
            // object as `table`, so the symbol fact must win over the type
            // fact (EXPORT_READ is E10's).
            if (access.object() instanceof IdentifierExpr identifier
                    && checks.symbolTable().resolve(identifier.name())
                        instanceof Symbol.ModuleSymbol) {
                throw new ConstructUnlowered("module member access '" + identifier.name()
                    + "." + access.field() + "' (EXPORT_READ is E10's)");
            }
            Type objectType = checkedType(access.object());
            if (objectType instanceof Type.Array && "length".equals(access.field())) {
                return lowerArrayLength(access);
            }
            if (objectType instanceof Type.Table) {
                return lowerMemberRead(access);
            }
            if (objectType instanceof Type.Class classType) {
                throw new ConstructUnlowered("class member access " + classType.modulePath()
                    + "/" + classType.name() + "." + access.field()
                    + " (FIELD_READ is E9's)");
            }
            if (objectType instanceof Type.Bytes) {
                throw new ConstructUnlowered("member access '" + access.field()
                    + "' on bytes (bytes value semantics are ISSUE-0158's; a bytes member "
                    + "access reaching an E3 arm fails hard)");
            }
            throw new ConstructUnlowered("member access '" + access.field() + "' on "
                + typeName(objectType) + " (no member-access arm for this receiver shape "
                + "in this stage's window)");
        }

        /** {@code ARRAY_LENGTH} — the receiver is one prior step, never re-evaluated. */
        private ValueId lowerArrayLength(MemberAccessExpr access) {
            ValueId receiver = lowerExpression(access.object());
            return emitValueOp(SemanticOpKind.ARRAY_LENGTH,
                new KindPayload.ArrayLengthPayload(receiver), access.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                FailurePolicyId.INT32_RESULT);
        }

        /** {@code MEMBER_READ} — the missing-aware read plus its contextual child. */
        private ValueId lowerMemberRead(MemberAccessExpr access) {
            Type contextualType = checkedType(access);
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(contextualType);
            ValueId receiver = lowerExpression(access.object());
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(access.span()),
                SourceOriginKind.USER, anchor, null);
            ops.add(buildOp(opId, SemanticOpKind.MEMBER_READ,
                new KindPayload.MemberReadPayload(receiver, access.field()),
                result, resultType, FailurePolicyId.NO_DEAL_FAILURE, origin));
            FailurePolicyId childPolicy = resultType instanceof RuntimeDescriptor.Func
                ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
            emitNullOp(SemanticOpKind.BOUNDARY,
                new KindPayload.BoundaryPayload(BoundaryKind.CONTEXTUAL_TABLE_READ,
                    resultType, result,
                    new BoundaryRealization.RuntimeValidation(
                        CANONICAL_RUNTIME_VALIDATION_ID)),
                access.span(), childPolicy, SourceOriginKind.SYNTHETIC, opId);
            return result;
        }

        /** {@code STRING_CONCAT} — the string-{@code +} arm; never {@code BINARY}. */
        private ValueId lowerBinary(BinaryExpr binary) {
            Type type = checkedType(binary);
            if (binary.op() != BinaryOp.ADD || !(type instanceof Type.String)) {
                throw new ConstructUnlowered("binary selector " + binary.op()
                    + " of checked type " + typeName(type)
                    + " (numeric selectors are ISSUE-0231's UNARY/BINARY arms; string "
                    + "+ lowers to STRING_CONCAT, never BINARY)");
            }
            ValueId left = lowerExpression(binary.left());
            ValueId right = lowerExpression(binary.right());
            return emitValueOp(SemanticOpKind.STRING_CONCAT,
                new KindPayload.StringConcatPayload(List.of(left, right)),
                binary.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE);
        }

        /** {@code STRING_CONCAT} — the template arm with fragment {@code CONST} steps. */
        private ValueId lowerTemplate(TemplateLiteralExpr template) {
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
                FailurePolicyId.NO_DEAL_FAILURE);
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

        /** Emits one value-producing USER op and returns its value id. */
        private ValueId emitValueOp(SemanticOpKind kind, KindPayload payload, Span span,
                                    RuntimeDescriptor resultType, FailurePolicyId policy) {
            ValueId value = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.USER, anchor, null);
            ops.add(buildOp(opId, kind, payload, value, resultType, policy, origin));
            return value;
        }

        /** Emits one result-less child/terminal op and returns its op id. */
        private OpId emitNullOp(SemanticOpKind kind, KindPayload payload, Span span,
                                FailurePolicyId policy, SourceOriginKind originKind,
                                OpId parent) {
            SemanticOp op = buildNullOp(kind, payload, span, policy, originKind, parent);
            ops.add(op);
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
         * live inside the payload itself.
         */
        private SemanticOp buildOp(OpId opId, SemanticOpKind kind, KindPayload payload,
                                   SemanticValue result, OpResultType resultType,
                                   FailurePolicyId policy, SourceOrigin origin) {
            OperationContractSnapshot placeholder = contractOf(kind, payload, resultType,
                policy, "placeholder");
            String digest = ContractSnapshotCanonicalizer.digest(placeholder);
            OperationContractSnapshot contract = contractOf(kind, payload, resultType,
                policy, digest);
            return new SemanticOp(opId, kind, origin, result, resultType, List.of(),
                List.of(), payload, policy, contract);
        }

        private OperationContractSnapshot contractOf(SemanticOpKind kind, KindPayload payload,
                                                     OpResultType resultType,
                                                     FailurePolicyId policy, String digest) {
            return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind,
                resultType, List.of(),
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

        private String typeName(Type type) {
            return switch (type) {
                case Type.Array array -> "[" + typeName(array.element()) + "]";
                case Type.Nullable nullable -> "?" + typeName(nullable.inner());
                case Type.Class classType -> "@" + classType.modulePath() + "/"
                    + classType.name();
                case Type.Func func -> "function";
                default -> String.valueOf(type);
            };
        }

        /** The foreign-expression description of the E6005 origin. */
        private String describeExpression(ExpressionNode expr) {
            return switch (expr) {
                case deal.ast.UnaryExpr ignored ->
                    "unary selector expression (ISSUE-0231's UNARY arm owns numeric "
                        + "selectors; this stage lowers operand positions only)";
                case deal.ast.CallExpr ignored ->
                    "call expression (the CALL machinery is E7's)";
                case deal.ast.IndexExpr ignored ->
                    "index access expression (INDEX_NORMALIZE/INDEX_READ are E5's, "
                        + "ISSUE-0234)";
                case deal.ast.FunctionExpr ignored ->
                    "function expression (CLOSURE_NEW is E6's, ISSUE-0235)";
                case deal.ast.HasExpr ignored ->
                    "has expression (HAS_FIELD is E5's, ISSUE-0234)";
                case deal.ast.AssignmentExpr ignored ->
                    "assignment expression (the ASSIGN address chain is E5's, ISSUE-0234)";
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
                    "if statement (BRANCH(IF) is E5's, ISSUE-0234)";
                case deal.ast.WhileStatement ignored ->
                    "while statement (LOOP(WHILE) is E5's, ISSUE-0234)";
                case deal.ast.ForStatement ignored ->
                    "for statement (LOOP(FOR) is E5's, ISSUE-0234)";
                case deal.ast.ExpressionStatement ignored ->
                    "expression statement (DISCARD is E5's, ISSUE-0234)";
                case deal.ast.ImportDeclaration ignored ->
                    "import declaration (IMPORT_EXPORT_ENTRY is E10's)";
                case deal.ast.ExportDeclaration ignored ->
                    "export declaration (EXPORT_* is E10's)";
                case deal.ast.ClassDeclaration ignored ->
                    "class declaration (class layouts and CLASS_NEW are E9's)";
                case deal.ast.DeleteStatement ignored ->
                    "delete statement (the DELETE address chain is E5's, ISSUE-0234)";
                case deal.ast.TryStatement ignored ->
                    "try statement (TRY_CATCH is E5's, ISSUE-0234)";
                case deal.ast.ThrowStatement ignored ->
                    "throw statement (THROW is E5's, ISSUE-0234)";
                case deal.ast.BreakStatement ignored ->
                    "break statement (matching loop-ID transfer is E5's, ISSUE-0234)";
                case deal.ast.ContinueStatement ignored ->
                    "continue statement (matching loop-ID transfer is E5's, ISSUE-0234)";
                default -> statement.getClass().getSimpleName() + " statement";
            };
        }
    }
}
