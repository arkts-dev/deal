package deal.semantic;

import deal.ast.ArrayLiteralExpr;
import deal.ast.AssignmentExpr;
import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.Block;
import deal.ast.CallExpr;
import deal.ast.DeleteStatement;
import deal.ast.ExpressionNode;
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.IdentifierExpr;
import deal.ast.ImportDeclaration;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Property;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.TryStatement;
import deal.ast.UnaryExpr;
import deal.ast.UnaryOp;
import deal.ast.VariableDeclaration;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.AddressChainProtocol;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.AssignTargetKind;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.DeleteTargetKind;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.IntrinsicKind;
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
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The common lowerer's per-construct shape map inside {@code
 * deal.semantic}: the container/string construct stage (ISSUE-0232
 * D1/D7; ISSUE-0386) producing the six container/string operations, plus
 * the value-operation slice (signed-int32 foundation I3; ISSUE-0395)
 * producing {@code CONST}/{@code UNARY}/{@code BINARY}/
 * {@code INTRINSIC_CALL} with the fixed selector→policy stamping.
 *
 * <p><b>The I3 value-operation slice (pinned).</b> Over checked facts,
 * the slice produces exactly the closed value operations — each with the
 * policy stamped from the single closed selector→policy tables of
 * {@link SemanticIrValidator} ({@code unaryPolicy}/{@code binaryPolicy}/
 * {@code intrinsicPolicy} — never a copy), a complete
 * {@code OperationContractSnapshot} with the T3 digest, and the
 * producing {@code SourceOrigin}:</p>
 *
 * <ul>
 *   <li>scalar {@link LiteralExpr} → one {@code CONST} (all five scalar
 *       kinds; an {@code IntLiteral} is signed32 by the I1 parser
 *       invariant);</li>
 *   <li>{@link UnaryExpr} → one {@code UNARY} with {@code BOOL_NOT} /
 *       {@code INT32_NEG} / {@code NUMBER_NEG} selected by the operator
 *       plus the operand's checked type (the checker's unary rules);
 *       policy {@code INT32_RESULT} for {@code INT32_NEG},
 *       {@code NO_DEAL_FAILURE} otherwise;</li>
 *   <li>{@link BinaryExpr} → one {@code BINARY} with the int32/number
 *       selector families selected by the operator plus the operand
 *       checked types ({@code INT32_ADD/SUB/MUL} →
 *       {@code INT32_RESULT}; {@code INT32_DIV_TRUNC/MOD_TRUNC} →
 *       {@code INT32_DIVISOR_THEN_RESULT}; {@code INT32_POW} →
 *       {@code INT32_EXPONENT_THEN_RESULT}; every {@code NUMBER_*}
 *       arithmetic incl. {@code NUMBER_POW_IEEE} →
 *       {@code NO_DEAL_FAILURE}). The other closed selector families
 *       ({@code STRING_*}, {@code BOOLEAN_*}, {@code NULL_*},
 *       {@code NULLABLE_*}, {@code REFERENCE_*} — the comparison
 *       producer {@link ComparisonSelectorLowering} — and the logical
 *       {@code BRANCH} operators) remain defined-but-not-produced by
 *       this slice; string {@code +} lowers to {@code STRING_CONCAT},
 *       never {@code BINARY};</li>
 *   <li>{@code int(…)}/{@code number(…)} intrinsic calls
 *       ({@code Symbol.IntrinsicSymbol}) → one {@code INTRINSIC_CALL}
 *       with {@code INT_CONVERT}/{@code NUMBER_CONVERT}, zero
 *       {@code BOUNDARY} children, and the conversion policy
 *       ({@code INT_CONVERSION}/{@code NUMBER_CONVERSION}) as the
 *       terminal check.</li>
 * </ul>
 *
 * <p><b>The E5 address-chain arms (pinned).</b> Over checked facts, the
 * arms lower every checked {@link AssignmentExpr}/{@link DeleteStatement}
 * target shape through the closed A-D9 map into one {@code ASSIGN}/
 * {@code DELETE} op with the pinned child order and trace roles (A-D2:
 * receiver → key → RHS → normalize → boundary → commit; VARIABLE: value
 * → boundary → commit; DELETE omits the RHS), the closed boundary
 * production (A-D4), the single-last commit structure (A-D5), the
 * committed-value result (A-D6), and single evaluation (A-D8):
 * {@code IdentifierExpr} targets → {@code ASSIGN VARIABLE}
 * {@code [valueOp, boundaryOp(VARIABLE_ASSIGNMENT),
 * commitOp(BINDING_STORE)]} with the declared target descriptor and the
 * descriptor-kind policy (the {@code FUNCTION_ADAPT} adapter slot stays
 * E6's — this epic produces no adapter children); {@code MemberAccessExpr}
 * on table → {@code TABLE_SLOT [containerOp, valueOp, MEMBER_WRITE]};
 * {@code IndexExpr} on table → {@code TABLE_SLOT [containerOp, keyOp,
 * valueOp, INDEX_NORMALIZE(TABLE_WRITE), INDEX_WRITE]} (the key is
 * statically {@code string} by the checker's E3018 gate);
 * {@code IndexExpr} on array → {@code ARRAY_SLOT [containerOp, keyOp,
 * valueOp, ARRAY_LENGTH, INDEX_NORMALIZE(ARRAY_WRITE),
 * ARRAY_ELEMENT_ASSIGNMENT, INDEX_WRITE]} (the length read pins at
 * normalize time; the append idiom lowers through this standard chain);
 * {@code MemberAccessExpr} on class → {@code CLASS_FIELD [containerOp,
 * valueOp, FIELD_WRITE]}; the four {@code DELETE} rows of A-D9 with the
 * {@code ARRAY_ELEMENT_DELETE + ARRAY_DELETE_BOUNDS} bounds boundary on
 * the array row. {@code ASSIGN} publishes the committed value with
 * {@code resultType} = the target position's checked descriptor;
 * {@code DELETE} publishes none. Every chain child records the chain op
 * as its {@code parentOpId}, nested chains record the enclosing chain
 * op, and the unit-production seam runs {@link AddressChainProtocol}
 * over the produced unit (A-D1).</p>
 *
 * <p><b>I3 profile guard.</b> {@link #lowerModule} refuses any lowering
 * request whose invocation profile is not
 * {@code DEAL_V1_2_INT32} with E6005 {@link #LOWER_LEGACY_PROFILE_REJECTED}
 * before any op is built — {@code LEGACY_SAFE_INT} is inspectable for
 * routing/regression but never lowered; the validator's R-PROFILE is the
 * backstop and {@link LoweredModuleUnit} admits only
 * {@code DEAL_V1_2_INT32} by construction.</p>
 *
 * <p><b>I3 scalar descriptor rows.</b> The value arms admit exactly
 * the pinned one-line scalar descriptor rows (int, number, boolean,
 * null, string, and the nullable rows over int/number/boolean — see
 * {@code ModuleLowerer#valueDescriptorOf}), realized through the single
 * {@code DescriptorService} producer (the verbatim D2 scalar table); no
 * structural descriptor service is built in this slice. The slice
 * performs no evaluation: {@code CONST} carries parser-guaranteed
 * scalars, {@code INTRINSIC_CALL} is a terminal check, and the
 * selector→policy stamping reads the closed table data.</p>
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
 *       (template literal parts reuse this exact arm). An
 *       {@code IntLiteral} whose value is outside signed32
 *       [-2147483648, 2147483647] raises {@link IntLiteralOutOfRange}
 *       — converted at the unit-production seam to E6005
 *       {@code INT32_LITERAL_OUT_OF_RANGE} (capability
 *       {@code SIGNED_INT32}), never a truncated scalar (the E1036
 *       signed32 literal gate is ISSUE-0111's frontend item, not yet
 *       satisfied by the checker);</li>
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
 * <p><b>Value-operation arms.</b> The I3 slice adds the value-operation
 * arms to the shape map ({@code CONST} of every scalar kind,
 * {@code UNARY}, {@code BINARY} of the int32/number arithmetic
 * selectors, and {@code INTRINSIC_CALL} of the two conversion
 * intrinsics); see the class-level I3 section. Operands complete
 * left-to-right and appear as the produced op's {@code operands}/
 * {@code operandTypes} in source order — the slice never evaluates,
 * re-emits, or re-reads an operand.</p>
 *
 * <p><b>Fail-closed arms (exactly one outcome each).</b> A construct
 * reaching an arm without a lowering arm raises {@link ConstructUnlowered}
 * — array for-of ({@code FOR_EACH(ARRAY_VALUES)} is E5's), class-typed
 * object literals ({@code CLASS_NEW} is E9's), class member access
 * ({@code FIELD_READ} is E9's), module member access ({@code EXPORT_READ}
 * is E10's), {@code .length} on bytes (ISSUE-0158), comparison and
 * logical binary operators (the comparison producer
 * {@link ComparisonSelectorLowering}'s and the selector-bearing
 * {@code BRANCH} of EVALUATION_ORDER), ordinary calls ({@code CALL} is
 * E7's), and every other foreign construct. An {@code IntLiteral}
 * outside signed32 at the
 * {@code CONST} arm raises {@link IntLiteralOutOfRange} — the same
 * fail-closed discipline for a scalar outside the closed scalar set
 * (the {@code CONST} contract's "a literal outside the closed scalar
 * set is a producer defect (E6005), never an invented op"), converted
 * at the same seam to E6005 {@code INT32_LITERAL_OUT_OF_RANGE} with
 * capability {@code SIGNED_INT32} — never a truncation. The unit-production
 * seam converts a {@code ConstructUnlowered} defect to the pinned E6005
 * {@code CONSTRUCT_UNLOWERED} diagnostic through
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
 * <p><b>The binding-core child (ISSUE-0444).</b> {@link
 * #lowerModuleBindingCore} drives the same session in binding-core mode:
 * one {@code BindingId} per declared name, static per-incarnation
 * generation ordinals, {@code BINDING_ALLOC/INIT/LOAD/STORE} with the
 * closed {@code DIRECT|SHARED_CELL} defaults and special cases
 * (for-let per-iteration incarnations and {@code FOR_EACH} iteration
 * bindings are {@code SHARED_CELL}), module-init-top intrinsic bindings,
 * hoisted module-level function ALLOCs, the two-incarnation for-let
 * {@code LOOP} shape, and {@code FOR_EACH} iteration-binding producing
 * allocations — with identifier loads and assignment stores resolving
 * the dominant incarnation through the installed
 * {@link BindingSiteResolver}. The walk consumes the value-expression
 * seam for initializer/assignment/condition value ops and the comparison
 * producer for comparison operands; it implements no expression-value
 * lowering of its own. Closure production, adapter creation, and adapter
 * invocation stay out of this child's window.</p>
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
 * <p><b>Claiming (D9 items 2–4, E3 window).</b> Units built by this
 * stage derive their claim set through {@link ContainerClaimingSeam} (the
 * unit-producer claiming seam, ISSUE-0387): the full-evidence claim
 * derivation over the produced ops and the then-active rows, checked with
 * the derived set as the unit's claims (the derivation-invariant guard —
 * the E6005 {@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} firing condition
 * can never trigger inside the producer because the unit claims exactly
 * its derived set). During E3's tail every produced op's home row is
 * inactive ({@link ContainerClaimingSeam#E3_WINDOW_ACTIVATION}), so the
 * tail units claim the empty capability set and every op is a recorded
 * staged hand-off. The manifest's plan-time claims are routing facts and
 * stay untouched.</p>
 */
public final class SemanticLowerer {

    /** The producer fact-defect identifier of the E6005 unlowered-construct arm (D7). */
    public static final String CONSTRUCT_UNLOWERED = "CONSTRUCT_UNLOWERED";

    /**
     * The producer fact-defect identifier of the E6005 out-of-signed32
     * int-literal arm (D1's CONST contract — a literal outside the closed
     * scalar set is a producer defect, never an invented op): the checked
     * frontend still admits out-of-int32 literals until the E1036
     * signed32 literal gate (ISSUE-0111) lands, so the {@code CONST} arm
     * fails closed instead of truncating.
     */
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

    /**
     * The pinned initial generation of every for-of loop binding (D1/D6),
     * of every variable-assignment store in this stage's E5 window, and
     * of every first-incarnation binding of the binding-core child (the
     * for-let per-iteration incarnation is the pinned generation 1 —
     * B1): the {@code FOR_EACH} payload's {@code generation} field and
     * the {@code BINDING_STORE} commit's {@code generation} field carry
     * this initial generation, never rewritten; loads of the loop binding
     * inside the body carry it and the body-runner resolves the effective
     * generation as initial + current iteration index at execution.
     * Binding allocation and generation ordinals are the binding-core
     * child's ({@link #lowerModuleBindingCore}, ISSUE-0444/ISSUE-0235);
     * this stage's E3/E5 window only pins the payload value.
     */
    public static final long INITIAL_LOOP_GENERATION = 0L;

    // =========================================================================
    // The binding-core child (ISSUE-0444): incarnations, generations, cell kinds
    // =========================================================================

    /**
     * The closed producing-allocation kinds of the binding-core child:
     * an incarnation is produced by its {@code BINDING_ALLOC} op or by
     * the {@code FOR_EACH} iteration op (the iteration binding's producing
     * allocation — {@code FOR_EACH} payloads record no cell kind because
     * iteration bindings are always {@code SHARED_CELL}, B2).
     */
    public enum BindingProducer {

        /** The incarnation's producing allocation is its {@code BINDING_ALLOC} op. */
        BINDING_ALLOC,

        /** The incarnation's producing allocation is the {@code FOR_EACH} iteration op. */
        FOR_EACH
    }

    /**
     * One incarnation fact of the binding-core walk: the static
     * per-binding generation ordinal (assigned in lowering order starting
     * at 0), the block the incarnation's producing allocation sits in,
     * the closed cell kind ({@code DIRECT} everywhere except the pinned
     * special cases — for-let per-iteration incarnations and
     * {@code FOR_EACH} iteration bindings are {@code SHARED_CELL}), the
     * reassignability fact recorded independently of the cell kind, and
     * the producing-allocation kind.
     */
    public record BindingCoreIncarnation(long generation, BlockId scope,
                                         BindingCellKind cellKind, boolean mutable,
                                         BindingProducer producer) {

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

    /**
     * The dominant-incarnation resolver of the binding environment
     * (ISSUE-0444 binding-core child): the identifier arm and the
     * variable-assignment arm consult this hook when installed so every
     * {@code BINDING_LOAD}/{@code BINDING_STORE} they emit names the
     * dominant incarnation at the site (B9 R1). Absent (the default in
     * this stage's E3/E5 window), the pre-existing frame/on-demand
     * resolution is unchanged.
     */
    @FunctionalInterface
    public interface BindingSiteResolver {

        /**
         * The dominant incarnation of the named declared binding at the
         * current site, or {@code null} when the name is not a declared
         * binding of the installed environment.
         *
         * @param name the source identifier name; non-null
         * @return the dominant incarnation, or {@code null}
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
     * An int literal whose value is outside the closed signed32 scalar
     * set (D1's CONST contract — "a literal outside the closed scalar
     * set is a producer defect (E6005), never an invented op"): the
     * checked frontend still admits out-of-int32 literals until the
     * E1036 signed32 literal gate (ISSUE-0111) lands, so the
     * {@code CONST} arm fails closed instead of truncating. Converted at
     * the unit-production seam to the pinned E6005
     * {@code INT32_LITERAL_OUT_OF_RANGE} diagnostic — a hard compile
     * failure in this stage's window, never a truncation, never an
     * invented scalar, and never a crash.
     */
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

    /**
     * The unit-production seam's failure carrier (D2/D7): maps a defect
     * raised by an arm to its exact {@link LoweringFailureDetail} —
     * {@code ConstructUnlowered} → E6005 {@code CONSTRUCT_UNLOWERED}
     * (capability {@code CONTAINERS_AND_STRINGS}, origin
     * {@code SemanticLowerer CONSTRUCT_UNLOWERED (construct)}),
     * {@link IntLiteralOutOfRange} → E6005
     * {@code INT32_LITERAL_OUT_OF_RANGE} (capability
     * {@code SIGNED_INT32}, origin
     * {@code SemanticLowerer INT32_LITERAL_OUT_OF_RANGE (defect message)}),
     * and a {@link ContainerPayloadDescriptors.Defect} → E6005
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
        if (defect instanceof IntLiteralOutOfRange outOfRange) {
            return new LoweringFailureDetail(module.path(),
                SemanticCapability.SIGNED_INT32, INT32_LITERAL_OUT_OF_RANGE,
                SemanticProfile.DEAL_V1_2_INT32, LoweredModuleUnit.FORMAT_VERSION,
                "SemanticLowerer " + INT32_LITERAL_OUT_OF_RANGE + " ("
                    + outOfRange.getMessage() + ")");
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
     * statement arms (for-of statements, the E5 delete arm — transparent
     * blocks recurse; every other statement is a foreign construct and
     * fails E6005 {@code CONSTRUCT_UNLOWERED}), the manifest's
     * construct-coverage rows are recorded onto the unit at lowering
     * start, descriptor defects convert to E6005
     * {@code DESCRIPTOR_UNREPRESENTABLE}, and the produced unit must pass
     * the closed validator plus the production-time address-chain
     * protocol (A-D1 — the first rejection is the returned diagnostic).
     * In E3's window the unit claims the empty capability set derived
     * through the claiming seam (D9 items 2/4).
     *
     * <p><b>I3 profile guard.</b> The invocation profile is a required
     * input: a lowering request whose profile is not
     * {@code DEAL_V1_2_INT32} is rejected with E6005
     * {@link #LOWER_LEGACY_PROFILE_REJECTED} before any op is built —
     * {@code LEGACY_SAFE_INT} is inspectable for routing/regression but
     * never lowered; the validator's R-PROFILE is the backstop.</p>
     *
     * @param module                the checked implementation module; non-null
     * @param profile               the invocation's semantic profile
     *                              (I3 guard: only
     *                              {@code DEAL_V1_2_INT32} is lowered);
     *                              non-null
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
        // I3 profile guard: the rejection runs before any op is built and
        // before any id is allocated — a LEGACY_SAFE_INT lowering request
        // produces no unit and no partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new LoweringResult(null, List.of(FailureContractRegistry.e6005(
                new LoweringFailureDetail(module.moduleId().path(),
                    SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                    profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer"))));
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator);
        try {
            lowerer.lowerStatements(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered))));
        } catch (IntLiteralOutOfRange outOfRange) {
            return new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange))));
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
        // E5 production-time check (A-D1): the closed address-chain
        // protocol runs after the foundation validator — every produced
        // ASSIGN/DELETE chain must match exactly one closed A-D9 shape
        // with single evaluation; the first violation is the returned
        // E6005 (ADDRESS_CHAIN_SHAPE | SINGLE_EVALUATION).
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new LoweringResult(null, List.of(chainShape.get()));
        }
        return new LoweringResult(unit, List.of());
    }

    /**
     * The binding-core child's public lowering entry point (ISSUE-0444
     * sequencing item 1): lowers one checked implementation module
     * through the binding walk — one {@code BindingId} per declared name,
     * static per-incarnation generation ordinals, {@code
     * BINDING_ALLOC/INIT/LOAD/STORE} production with the closed
     * {@code DIRECT|SHARED_CELL} defaults and special cases, hoisted
     * module-level function ALLOCs and module-init-top intrinsic
     * bindings, the two-incarnation for-let shape, and {@code FOR_EACH}
     * iteration-binding producing allocations — and produces the validated
     * unit plus the walk's binding facts.
     *
     * <p>The walk consumes the value-expression seam of
     * {@link ModuleLowerer} for initializer/assignment/condition value
     * ops (never re-implementing expression-value lowering); comparison
     * operands lower through the single comparison producer
     * {@link ComparisonSelectorLowering} (the values epic's producer),
     * and identifier loads plus variable-assignment stores resolve
     * through the installed {@link BindingSiteResolver} so every
     * {@code BINDING_LOAD}/{@code BINDING_STORE} payload names the
     * dominant incarnation at the site (B9 R1). The produced unit passes
     * the closed validator, the production-time address-chain protocol,
     * and the claiming seam under the pinned E6-gate activation
     * ({@code BINDINGS} activates for {@code BINDING_LOAD}; binding-core
     * units defer the row per unit because the full family set includes
     * the closure child's {@code RECURSIVE_GROUP_INIT}/
     * {@code CLOSURE_NEW}).</p>
     *
     * <p>This entry point is driven by the binding-core tests; no
     * production route change — retained/public compilation paths and
     * {@link #lowerModule} are untouched.</p>
     *
     * @param module                the checked implementation module; non-null
     * @param profile               the invocation's semantic profile
     *                              (I3 guard: only
     *                              {@code DEAL_V1_2_INT32} is lowered);
     *                              non-null
     * @param constructCoverage     the manifest's reachable-construct rows
     *                              recorded at lowering start (S1); non-null
     * @param interfaceHash         the interface index digest the unit is
     *                              checked against (R-PROFILE); non-null
     * @param capabilityRegistryHash the invocation's capability-registry
     *                              digest (R-PROFILE); non-null
     * @param allocator             the project's semantic-id allocator in
     *                              dependency order; non-null
     * @return the validated unit with the binding facts, or the first
     *         E6005 with the partial facts on failure
     */
    public static BindingCoreResult lowerModuleBindingCore(CheckedModuleInput module,
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
        // I3 profile guard: identical to lowerModule — a non-DEAL_V1_2_INT32
        // lowering request produces no unit and no partial session state.
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            return new BindingCoreResult(new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    new LoweringFailureDetail(module.moduleId().path(),
                        SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                        profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer")))),
                BindingCoreFacts.empty());
        }
        ModuleLowerer lowerer = new ModuleLowerer(module.moduleId(), module.sourceId(),
            module.checks(), allocator, true, module.ast().span());
        try {
            lowerer.lowerBindingModule(module.ast().statements());
        } catch (ConstructUnlowered unlowered) {
            return new BindingCoreResult(new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), unlowered)))),
                lowerer.bindingFacts());
        } catch (IntLiteralOutOfRange outOfRange) {
            return new BindingCoreResult(new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), outOfRange)))),
                lowerer.bindingFacts());
        } catch (ContainerPayloadDescriptors.Defect defect) {
            return new BindingCoreResult(new LoweringResult(null,
                List.of(FailureContractRegistry.e6005(
                    loweringFailureDetail(module.moduleId(), defect)))),
                lowerer.bindingFacts());
        } catch (ComparisonSelectorLowering.Defect defect) {
            return new BindingCoreResult(new LoweringResult(null,
                List.of(ComparisonSelectorLowering.e6005(module.moduleId(), defect))),
                lowerer.bindingFacts());
        }
        LoweredModuleUnit unit = lowerer.buildUnit(constructCoverage,
            module.imports().stream().map(ResolvedImport::resolvedModuleId).toList(),
            interfaceHash, capabilityRegistryHash,
            ContainerClaimingSeam.E6_GATE_ACTIVATION);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new BindingCoreResult(new LoweringResult(null, List.of(validation.get())),
                lowerer.bindingFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new BindingCoreResult(new LoweringResult(null, List.of(chainShape.get())),
                lowerer.bindingFacts());
        }
        return new BindingCoreResult(new LoweringResult(unit, List.of()), lowerer.bindingFacts());
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
        /**
         * The binding-core mode flag (ISSUE-0444 binding-core child):
         * {@code true} exactly when the session was created by
         * {@link SemanticLowerer#lowerModuleBindingCore} — the binding
         * walk's statement arms, the comparison-operand routing through
         * the comparison producer, and the binding-environment identifier
         * resolution are active; {@code false} (the default) preserves
         * the E3/E5 window behavior byte-for-byte.
         */
        private final boolean bindingCore;
        /**
         * The installed binding-environment resolver (ISSUE-0444): the
         * identifier arm and the variable-assignment arm consult it
         * (after the for-of frames, before the legacy on-demand path) so
         * every {@code BINDING_LOAD}/{@code BINDING_STORE} they emit
         * names the dominant incarnation at the site. {@code null} in
         * the E3/E5 window.
         */
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
            this(module, sourceId, checks, ids, false,
                new Span(sourceId, 1, 1, 1, 1, Span.UNKNOWN_OFFSET, Span.UNKNOWN_OFFSET));
        }

        /**
         * Creates one lowering session with the binding-core mode flag
         * (ISSUE-0444 binding-core child). In binding-core mode the
         * module scope frame is opened at construction and the
         * binding-environment resolver is installed, so the identifier
         * arm and the variable-assignment arm resolve declared bindings
         * against the walk's dominant incarnations.
         *
         * @param module      the module identity; non-null
         * @param sourceId    the stable source identity carried on every
         *                    op's origin; non-null
         * @param checks      the module's checked facts (read-only); non-null
         * @param ids         the project's allocator in dependency order;
         *                    non-null
         * @param bindingCore {@code true} to activate the binding walk's
         *                    arms and environment
         */
        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore, Span programSpan) {
            this.module = Objects.requireNonNull(module, "module must not be null");
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            this.checks = Objects.requireNonNull(checks, "checks must not be null");
            this.ids = Objects.requireNonNull(ids, "ids must not be null");
            this.bindingCore = bindingCore;
            this.programSpan = Objects.requireNonNull(programSpan,
                "programSpan must not be null");
            this.moduleInitBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            if (bindingCore) {
                blockStack.push(moduleInitBlock);
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

        /**
         * Installs the binding-environment resolver consulted by the
         * identifier arm and the variable-assignment arm (ISSUE-0444
         * binding-core child). Installing a resolver never changes the
         * E3/E5 window's pre-existing frame/on-demand resolution — the
         * resolver is consulted only after the for-of frames and only
         * when it resolves the name.
         *
         * @param resolver the dominant-incarnation resolver; non-null
         */
        public void installBindingSiteResolver(BindingSiteResolver resolver) {
            this.bindingSiteResolver = Objects.requireNonNull(resolver,
                "resolver must not be null");
        }

        /** The binding-core mode flag of this session. */
        public boolean bindingCore() {
            return bindingCore;
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
        // The binding-core walk (ISSUE-0444 binding-core child)
        // ---------------------------------------------------------------------

        /**
         * Lowers the module's top-level statements through the
         * binding-core walk: first the module-init-top bindings — the
         * {@code int}/{@code number} intrinsic bindings (ALLOC + INIT at
         * module-init top, the retained per-module wrapper shape,
         * {@code deal/codegen/lua/LuaBackend.java:668-674}) and the
         * hoisted module-level function-name ALLOCs plus import-alias
         * ALLOCs in declaration order (B1) — then the statements in
         * source order.
         *
         * @param statements the module's top-level statements; non-null
         * @throws IllegalStateException outside binding-core mode
         * @throws ConstructUnlowered    on a construct outside the
         *         binding-core window
         */
        public void lowerBindingModule(List<StatementNode> statements) {
            if (!bindingCore) {
                throw new IllegalStateException(
                    "lowerBindingModule outside binding-core mode (producer defect)");
            }
            seedIntrinsicBindings();
            hoistModuleLevelAllocs(statements);
            lowerBindingStatements(statements, true);
        }

        /**
         * The binding walk's complete fact surface: one
         * {@link BindingCoreBinding} per declared name in registration
         * order (partial when the walk failed mid-way).
         *
         * @return the recorded binding facts; non-null
         */
        public BindingCoreFacts bindingFacts() {
            List<BindingCoreBinding> facts = new ArrayList<>();
            for (BindingCell cell : bindingCells) {
                facts.add(new BindingCoreBinding(cell.name, cell.id, cell.incarnations));
            }
            return new BindingCoreFacts(facts);
        }

        /**
         * Seeds the {@code int}/{@code number} intrinsic bindings at
         * module-init top (B1): exactly the root
         * {@code Symbol.IntrinsicSymbol} bindings of those two names
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
         */
        private void seedIntrinsicBindings() {
            for (String name : List.of("int", "number")) {
                if (!(checks.symbolTable().resolve(name) instanceof Symbol.IntrinsicSymbol)) {
                    continue;
                }
                BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                registerBinding(name, binding, new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                    false, BindingProducer.BINDING_ALLOC));
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(binding, moduleInitBlock, false,
                        BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                    moduleInitSpan(), FailurePolicyId.NO_DEAL_FAILURE);
                ValueId intrinsicValue = ids.nextValueId(module, nextOrdinal++, 0);
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
         * position in the main walk.
         */
        private void hoistModuleLevelAllocs(List<StatementNode> statements) {
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    registerBinding(function.name(), binding, new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                        true, BindingProducer.BINDING_ALLOC));
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, moduleInitBlock, true,
                            BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                        function.span(), FailurePolicyId.NO_DEAL_FAILURE);
                } else if (statement instanceof ImportDeclaration importDecl) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    registerBinding(importDecl.alias(), binding, new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                        false, BindingProducer.BINDING_ALLOC));
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, moduleInitBlock, false,
                            BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                        importDecl.span(), FailurePolicyId.NO_DEAL_FAILURE);
                }
            }
        }

        /**
         * The binding-core statement walk (ISSUE-0444): exactly the
         * binding-relevant statement arms — let declarations, function
         * declarations (hoisted name ALLOCs plus parameter ALLOCs at the
         * body block's entry), the two-incarnation for-let shape, for-of
         * iteration bindings, try/catch bindings, import aliases (hoisted
         * — no position ops), and nested blocks. Every other statement is
         * a foreign construct in this child's window
         * ({@link ConstructUnlowered}).
         */
        private void lowerBindingStatements(List<StatementNode> statements,
                                           boolean moduleLevel) {
            for (StatementNode statement : statements) {
                switch (statement) {
                    case VariableDeclaration decl -> lowerBindingVarDecl(decl);
                    case FunctionDeclaration function ->
                        lowerBindingFunctionDecl(function, moduleLevel);
                    case ForStatement forStatement -> lowerBindingForLet(forStatement);
                    case ForOfStatement forOf -> lowerBindingForOf(forOf);
                    case TryStatement tryStatement -> lowerBindingTry(tryStatement);
                    case ImportDeclaration ignored -> {
                        // The alias ALLOC was hoisted to module-init top
                        // (B1); the MODULE_IMPORT completion write is the
                        // modules epic's (ISSUE-0239).
                    }
                    case deal.ast.ExpressionStatement expressionStatement ->
                        lowerBindingExprStatement(expressionStatement);
                    case Block block -> lowerBindingBlock(block);
                    default -> throw new ConstructUnlowered(describeStatement(statement));
                }
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
            lowerExpression(statement.expr());
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
            registerBinding(decl.name(), binding, new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, currentBlock(), BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC));
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(binding, currentBlock(), true,
                    BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId value = lowerExpression(decl.initializer());
            if (decl.typeAnnotation().isPresent()) {
                RuntimeDescriptor descriptor =
                    ContainerPayloadDescriptors.resultDescriptorOf(declaredTypeOf(decl));
                FailurePolicyId boundaryPolicy = descriptor instanceof RuntimeDescriptor.Func
                    ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
                emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.VARIABLE_DECLARATION,
                        descriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    decl.span(), boundaryPolicy, SourceOriginKind.SYNTHETIC, null);
            }
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(binding, INITIAL_LOOP_GENERATION, value),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
        }

        /**
         * The binding walk's function-declaration arm: the module-level
         * name ALLOC was hoisted (B1); a nested-scope declaration emits
         * its name ALLOC at the declaration position in the enclosing
         * block. The body then gets its block identity, the parameter
         * ALLOCs at the body block's entry (generation 0, {@code DIRECT},
         * no BINDING_INIT — the parameter-transfer write is the invoking
         * machinery's, E7), and the body statements through the binding
         * walk. {@code CLOSURE_NEW} + {@code BINDING_INIT} stay at the
         * declaration position for the closure child.
         */
        private void lowerBindingFunctionDecl(FunctionDeclaration function,
                                              boolean moduleLevel) {
            if (!moduleLevel) {
                // Nested-scope declarations allocate their name binding at
                // the declaration position (B1: nested functions are
                // defined at their position, no hoisting).
                BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                registerBinding(function.name(), binding, new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, currentBlock(), BindingCellKind.DIRECT,
                    true, BindingProducer.BINDING_ALLOC));
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(binding, currentBlock(), true,
                        BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                    function.span(), FailurePolicyId.NO_DEAL_FAILURE);
            }
            // Module-level name ALLOCs were hoisted (B1): no second
            // allocation here.
            BlockId bodyBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            checkerScopeNodes.push(function);
            pushBindingFrame();
            blockStack.push(bodyBlock);
            for (deal.ast.Parameter parameter : function.params()) {
                BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                registerBinding(parameter.name(), binding, new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                    true, BindingProducer.BINDING_ALLOC));
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(binding, bodyBlock, true,
                        BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                    parameter.span(), FailurePolicyId.NO_DEAL_FAILURE);
            }
            checkerScopeNodes.push(function.body());
            lowerBindingStatements(function.body().statements(), false);
            checkerScopeNodes.pop();
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
        }

        /**
         * The binding walk's for-let arm (B1): exactly two incarnations
         * of one {@link BindingId} — the counter (generation 0, ALLOC +
         * INIT in the {@code LOOP} init block with the first condition
         * production; condition/update references and the body-top INIT
         * operand name generation 0) and the per-iteration incarnation
         * (generation 1, {@code SHARED_CELL}, ALLOC at the top of the
         * body block, INIT from the generation-0 load; body references
         * resolve generation 1) — the retained per-iteration copy shape
         * ({@code deal/codegen/lua/LuaBackend.java:1489-1492}). The
         * {@code LOOP(FOR)} op carries
         * {@code {initBlock, condition, bodyBlock, updateBlock}} with the
         * init block = one-time init including the first condition
         * production and the update block = update ops + condition
         * re-production (the control-flow epic's placement contract,
         * {@code control-flow-structures} C-D4). A for without a let
         * initializer, without a condition, or with a non-assignment
         * update is outside this child's window.
         */
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
            BlockId initBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            BlockId bodyBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            BlockId updateBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            checkerScopeNodes.push(statement);
            pushBindingFrame();
            blockStack.push(initBlock);
            registerBinding(decl.name(), counter, new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, initBlock, BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC));
            // Init block: the counter ALLOC, the initializer value ops,
            // the counter INIT, then the first condition production
            // (init-block members — C-D4).
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(counter, initBlock, true,
                    BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId initializer = lowerExpression(decl.initializer());
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(counter, INITIAL_LOOP_GENERATION,
                    initializer),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId condition = lowerExpression(statement.condition().get());
            emitUserNullOp(SemanticOpKind.LOOP,
                new KindPayload.LoopPayload(ControlSelector.FOR, initBlock, condition,
                    bodyBlock, updateBlock),
                statement.span(), FailurePolicyId.NO_DEAL_FAILURE);
            blockStack.pop();
            // Body block: the per-iteration incarnation (generation 1,
            // SHARED_CELL) at the body top, INIT from the generation-0
            // load, then the body statements (dominant generation 1).
            blockStack.push(bodyBlock);
            checkerScopeNodes.push(statement.body());
            pushBindingFrame();
            registerBinding(decl.name(), counter, new BindingCoreIncarnation(
                1L, bodyBlock, BindingCellKind.SHARED_CELL, true,
                BindingProducer.BINDING_ALLOC));
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(counter, bodyBlock, true,
                    BindingCellKind.SHARED_CELL, 1L),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            ValueId carry = emitSyntheticValueOp(SemanticOpKind.BINDING_LOAD,
                new KindPayload.BindingLoadPayload(counter, INITIAL_LOOP_GENERATION),
                decl.span(), ContainerPayloadDescriptors.resultDescriptorOf(counterType),
                FailurePolicyId.NO_DEAL_FAILURE);
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(counter, 1L, carry),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
            lowerBindingStatements(statement.body().statements(), false);
            popBindingFrame();
            checkerScopeNodes.pop();
            blockStack.pop();
            // Update block: the update's assignment chain, then the
            // condition re-production (update-block members — C-D4;
            // both reference the generation-0 counter).
            blockStack.push(updateBlock);
            if (statement.update().isPresent()) {
                lowerExpression(statement.update().get());
            }
            lowerExpression(statement.condition().get());
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
        }

        /**
         * The binding walk's for-of arm: the iterable's prior steps, the
         * {@code FOR_EACH} op (the iteration binding's producing
         * allocation — no {@code BINDING_ALLOC} exists for it; the
         * payload's binding generation is the incarnation's ordinal, and
         * the fresh per-iteration cell comes from the op's per-iteration
         * allocation semantics, never a new ordinal), then the body
         * statements under the iteration-binding frame. The iteration
         * binding is classified {@code SHARED_CELL} (B2 — the
         * per-iteration cell exists precisely for capture semantics; the
         * payload records no cell kind). A string-typed iterable lowers
         * ({@code STRING_SCALARS}); an array iterable is the
         * control-flow epic's ({@code FOR_EACH(ARRAY_VALUES)}).
         */
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
            BlockId bodyBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            pushBindingFrame();
            registerBinding(statement.varName(), binding, new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.SHARED_CELL,
                true, BindingProducer.FOR_EACH));
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(statement.span()),
                SourceOriginKind.USER, anchor, null);
            ops.add(buildOp(opId, SemanticOpKind.FOR_EACH,
                new KindPayload.ForEachPayload(IterationMode.STRING_SCALARS, iterable,
                    binding, INITIAL_LOOP_GENERATION, bodyBlock),
                null, null, FailurePolicyId.TYPE_DESCRIPTOR, origin));
            checkerScopeNodes.push(statement.body());
            blockStack.push(bodyBlock);
            lowerBindingStatements(statement.body().statements(), false);
            blockStack.pop();
            checkerScopeNodes.pop();
            popBindingFrame();
        }

        /**
         * The binding walk's try/catch arm: the {@code TRY_CATCH} op
         * carrying the try block, the catch binding (this child's single
         * {@link BindingId} for the catch name), and the catch block;
         * the catch binding's ALLOC sits at the catch block's entry
         * (generation 0, {@code DIRECT}, mutable, no BINDING_INIT — the
         * catch-entry write is {@code TRY_CATCH}'s, ISSUE-0234). The
         * control-flow epic refines the op's execution; this child
         * supplies the binding model the structure operates on.
         */
        private void lowerBindingTry(TryStatement statement) {
            BlockId tryBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            BlockId catchBlock = ids.nextBlockId(module, nextOrdinal++, 0);
            BindingId catchBinding = ids.nextBindingId(module, nextOrdinal++, 0);
            emitUserNullOp(SemanticOpKind.TRY_CATCH,
                new KindPayload.TryCatchPayload(tryBlock, catchBinding, catchBlock),
                statement.span(), FailurePolicyId.NO_DEAL_FAILURE);
            checkerScopeNodes.push(statement.tryBlock());
            pushBindingFrame();
            blockStack.push(tryBlock);
            lowerBindingStatements(statement.tryBlock().statements(), false);
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
            checkerScopeNodes.push(statement);
            pushBindingFrame();
            blockStack.push(catchBlock);
            registerBinding(statement.catchVar(), catchBinding, new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, catchBlock, BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC));
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(catchBinding, catchBlock, true,
                    BindingCellKind.DIRECT, INITIAL_LOOP_GENERATION),
                statement.span(), FailurePolicyId.NO_DEAL_FAILURE);
            lowerBindingStatements(statement.catchBlock().statements(), false);
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
            BlockId blockId = ids.nextBlockId(module, nextOrdinal++, 0);
            checkerScopeNodes.push(block);
            pushBindingFrame();
            blockStack.push(blockId);
            lowerBindingStatements(block.statements(), false);
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
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
            for (Map<String, FrameEntry> frame : bindingScopes) {
                FrameEntry entry = frame.get(name);
                if (entry != null) {
                    return entry;
                }
            }
            return null;
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
                case UnaryExpr unary -> lowerUnary(unary);
                case CallExpr call -> lowerIntrinsicCall(call);
                case AssignmentExpr assignment -> lowerAssignment(assignment);
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
         * @param assignment the checked assignment expression; non-null
         * @return the committed value's {@link ValueId} (A-D6)
         * @throws ConstructUnlowered on a target shape outside the closed
         *         A-D9 map
         */
        public ValueId lowerAssignment(AssignmentExpr assignment) {
            Objects.requireNonNull(assignment, "assignment must not be null");
            ExpressionNode target = assignment.target();
            if (target instanceof IdentifierExpr identifier) {
                return lowerVariableAssign(assignment, identifier);
            }
            if (target instanceof MemberAccessExpr access) {
                Type objectType = checkedType(access.object());
                if (objectType instanceof Type.Table) {
                    return lowerTableMemberAssign(assignment, access);
                }
                if (objectType instanceof Type.Class classType) {
                    return lowerClassFieldAssign(assignment, access, classType);
                }
                throw new ConstructUnlowered("assignment target '" + access.field()
                    + "' on " + typeName(objectType) + " (no closed A-D9 chain shape for "
                    + "this receiver: module members are E10's, array/bytes members carry "
                    + "no write shape)");
            }
            if (target instanceof IndexExpr index) {
                Type containerType = checkedType(index.array());
                if (containerType instanceof Type.Table) {
                    return lowerTableIndexAssign(assignment, index);
                }
                if (containerType instanceof Type.Array arrayType) {
                    return lowerArrayIndexAssign(assignment, index, arrayType);
                }
                throw new ConstructUnlowered("assignment target index on "
                    + typeName(containerType) + " (no closed A-D9 chain shape for this "
                    + "container: bytes indexing is ISSUE-0158's)");
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
         * @param delete the checked delete statement; non-null
         * @throws ConstructUnlowered on a target shape outside the closed
         *         A-D9 map
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

        /**
         * ASSIGN VARIABLE — the single closed shape
         * {@code [valueOp, boundaryOp(VARIABLE_ASSIGNMENT),
         * commitOp(BINDING_STORE)]}: the value child, then exactly one
         * {@code VARIABLE_ASSIGNMENT} boundary carrying the target
         * binding's declared descriptor and the descriptor-kind policy
         * with input = the committed value, then the {@code BINDING_STORE}
         * commit storing {@code {binding, generation, committed value}}
         * (A-D4/A-D5). The optional {@code FUNCTION_ADAPT} adapter slot
         * between the value and the boundary is E6's creation rule — this
         * epic produces no adapter children. The {@code ASSIGN} result is
         * the committed value with {@code resultType} = the declared
         * target descriptor (A-D6).
         */
        private ValueId lowerVariableAssign(AssignmentExpr assignment, IdentifierExpr target) {
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
                // The binding-environment hook (ISSUE-0444 binding-core
                // child): the store commits the dominant incarnation at
                // the assignment site (B9 R1).
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
            Type targetType = checkedType(target);
            RuntimeDescriptor targetDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(targetType);
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId valueOp;
            OpId boundaryOp;
            OpId commitOp;
            try {
                value = lowerExpression(assignment.value());
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
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.VARIABLE,
                    List.of(valueOp, boundaryOp, commitOp)),
                value, targetDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
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
                value = lowerExpression(assignment.value());
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
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.ASSIGN,
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
                value = lowerExpression(assignment.value());
                valueOp = producerOpId(value);
                ValueId slot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.TABLE_WRITE, key, key),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(slot);
                commitOp = emitNullOp(SemanticOpKind.INDEX_WRITE,
                    new KindPayload.IndexWritePayload(container, slot, value),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.ASSIGN,
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
            RuntimeDescriptor elementDescriptor =
                ContainerPayloadDescriptors.elementDescriptorOf(arrayType.element());
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
                value = lowerExpression(assignment.value());
                valueOp = producerOpId(value);
                ValueId length = emitChainChildOp(SemanticOpKind.ARRAY_LENGTH,
                    new KindPayload.ArrayLengthPayload(container), index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                    FailurePolicyId.INT32_RESULT);
                lengthOp = producerOpId(length);
                ValueId slot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_WRITE, key, length),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(slot);
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
                        elementDescriptor, value,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    index.span(), FailurePolicyId.ARRAY_WRITE_BOUNDS_THEN_ELEMENT,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                commitOp = emitNullOp(SemanticOpKind.INDEX_WRITE,
                    new KindPayload.IndexWritePayload(container, slot, value),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.ASSIGN,
                new KindPayload.AssignPayload(AssignTargetKind.ARRAY_SLOT,
                    List.of(containerOp, keyOp, valueOp, lengthOp, normalizeOp, boundaryOp,
                        commitOp)),
                value, elementDescriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            return value;
        }

        /**
         * ASSIGN CLASS_FIELD write — {@code [containerOp, valueOp,
         * commitOp(FIELD_WRITE)]}: zero write-check boundaries
         * ({@code CLASS_FIELD_ASSIGNMENT} stays admissible but is
         * produced by E9, never here; A-D4).
         */
        private ValueId lowerClassFieldAssign(AssignmentExpr assignment,
                                              MemberAccessExpr access, Type.Class classType) {
            RuntimeDescriptor fieldDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(access));
            ClassId classId = new ClassId(classType.modulePath(), classType.name());
            OpId chainOpId = ids.nextOpId(module, nextOrdinal++, 0);
            chainParents.push(chainOpId);
            ValueId value;
            OpId containerOp;
            OpId valueOp;
            OpId commitOp;
            try {
                ValueId container = lowerExpression(access.object());
                containerOp = producerOpId(container);
                value = lowerExpression(assignment.value());
                valueOp = producerOpId(value);
                commitOp = emitNullOp(SemanticOpKind.FIELD_WRITE,
                    new KindPayload.FieldWritePayload(container, classId, access.field(),
                        value),
                    access.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(assignment.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.ASSIGN,
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
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.DELETE,
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
                ValueId slot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.TABLE_WRITE, key, key),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(slot);
                commitOp = emitNullOp(SemanticOpKind.INDEX_DELETE,
                    new KindPayload.IndexDeletePayload(container, slot),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.DELETE,
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
                ContainerPayloadDescriptors.elementDescriptorOf(arrayType.element());
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
                ValueId slot = emitChainChildOp(SemanticOpKind.INDEX_NORMALIZE,
                    new KindPayload.IndexNormalizePayload(IndexMode.ARRAY_WRITE, key, length),
                    index.span(),
                    ContainerPayloadDescriptors.resultDescriptorOf(Type.Int.INSTANCE),
                    FailurePolicyId.NO_DEAL_FAILURE);
                normalizeOp = producerOpId(slot);
                boundaryOp = emitNullOp(SemanticOpKind.BOUNDARY,
                    new KindPayload.BoundaryPayload(BoundaryKind.ARRAY_ELEMENT_DELETE,
                        elementDescriptor, slot,
                        new BoundaryRealization.RuntimeValidation(
                            CANONICAL_RUNTIME_VALIDATION_ID)),
                    index.span(), FailurePolicyId.ARRAY_DELETE_BOUNDS,
                    SourceOriginKind.SYNTHETIC, chainOpId);
                commitOp = emitNullOp(SemanticOpKind.INDEX_DELETE,
                    new KindPayload.IndexDeletePayload(container, slot),
                    index.span(), FailurePolicyId.NO_DEAL_FAILURE,
                    SourceOriginKind.SYNTHETIC, chainOpId);
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.DELETE,
                new KindPayload.DeletePayload(DeleteTargetKind.ARRAY_SLOT,
                    List.of(containerOp, keyOp, lengthOp, normalizeOp, boundaryOp, commitOp)),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, origin));
        }

        /**
         * DELETE CLASS_FIELD — {@code [containerOp,
         * commitOp(FIELD_DELETE)]}: no key, no normalize, no bounds
         * boundary (table and class targets run no bounds boundary;
         * A-D9).
         */
        private void lowerClassFieldDelete(DeleteStatement delete, MemberAccessExpr access,
                                           Type.Class classType) {
            ClassId classId = new ClassId(classType.modulePath(), classType.name());
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
            } finally {
                chainParents.pop();
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(delete.span()),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(chainOpId, SemanticOpKind.DELETE,
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
            for (int i = ops.size() - 1; i >= 0; i--) {
                SemanticOp op = ops.get(i);
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
            ops.add(buildOp(opId, kind, payload, value, resultType, List.of(), List.of(),
                policy, origin));
            return value;
        }

        // ---------------------------------------------------------------------
        // Unit production (S1)
        // ---------------------------------------------------------------------

        /**
         * Builds the immutable unit over the session's produced
         * operations: the manifest's construct-coverage rows recorded at
         * lowering start (each row must carry the closed construct→op
         * detector table verbatim, S4), the capability claim set derived
         * through the claiming seam's full-evidence derivation
         * ({@link ContainerClaimingSeam}) — the empty set during E3's
         * tail, where every produced op's home row is inactive (D9 items
         * 2/4) — the module-init plan over the session's init block, and
         * the produced operations in source order.
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
            return buildUnit(constructCoverage, imports, interfaceHash, capabilityRegistryHash,
                ContainerClaimingSeam.E3_WINDOW_ACTIVATION);
        }

        /**
         * Builds the validated unit under the named claiming-seam
         * activation state (the binding-core entry passes the pinned
         * E6-gate activation — {@code BINDINGS} activates for
         * {@code BINDING_LOAD} — while the E3/E5 window keeps the
         * E3-window activation).
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
                if (statement instanceof DeleteStatement delete) {
                    lowerDelete(delete);
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

        /**
         * {@code CONST} — the scalar-literal and template-fragment arm.
         * An {@code IntLiteral} outside signed32 [-2147483648,
         * 2147483647] raises {@link IntLiteralOutOfRange} (D1's CONST
         * contract — a literal outside the closed scalar set is a
         * producer defect, never an invented op): the checked frontend
         * still admits out-of-int32 literals until the E1036 signed32
         * literal gate (ISSUE-0111) lands, so this arm fails closed
         * instead of truncating — no {@code CONST} is produced for the
         * out-of-range value, and the in-range cast is exact.
         */
        private ValueId lowerConst(LiteralExpr literal) {
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
            // The binding-environment hook (ISSUE-0444 binding-core child):
            // every declared binding of the walk's environment resolves to
            // its dominant incarnation at the site, so the emitted load
            // names {binding, generation} of that incarnation (B9 R1).
            if (bindingSiteResolver != null) {
                BindingSite site = bindingSiteResolver.resolve(identifier.name());
                if (site != null) {
                    return emitValueOp(SemanticOpKind.BINDING_LOAD,
                        new KindPayload.BindingLoadPayload(site.binding(), site.generation()),
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
                SourceOriginKind.USER, anchor, chainParents.peek());
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
                SourceOriginKind.USER, anchor, chainParents.peek());
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

        /** {@code STRING_CONCAT}/{@code BINARY} — the binary dispatch (I3 arithmetic). */
        private ValueId lowerBinary(BinaryExpr binary) {
            Type type = checkedType(binary);
            if (binary.op() == BinaryOp.ADD && type instanceof Type.String) {
                return lowerStringConcat(binary);
            }
            if (isArithmeticOperator(binary.op())) {
                return lowerArithmeticBinary(binary);
            }
            if (bindingCore && isComparisonOperator(binary.op())) {
                // The binding walk consumes the single comparison producer
                // (the values epic's producer) for condition/initializer
                // comparison operands — this child never implements
                // comparison-selector lowering itself.
                return lowerComparisonBinary(binary);
            }
            throw new ConstructUnlowered("binary selector " + binary.op()
                + " of checked type " + typeName(type)
                + " (comparison selectors are the comparison producer "
                + "ComparisonSelectorLowering's; logical operators lower to "
                + "selector-bearing BRANCH — EVALUATION_ORDER; string + lowers to "
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
        private ValueId lowerComparisonBinary(BinaryExpr binary) {
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
            ops.add(comparison);
            return (ValueId) comparison.result();
        }

        /** {@code STRING_CONCAT} — the string-{@code +} arm; never {@code BINARY}. */
        private ValueId lowerStringConcat(BinaryExpr binary) {
            ValueId left = lowerExpression(binary.left());
            ValueId right = lowerExpression(binary.right());
            return emitValueOp(SemanticOpKind.STRING_CONCAT,
                new KindPayload.StringConcatPayload(List.of(left, right)),
                binary.span(),
                ContainerPayloadDescriptors.resultDescriptorOf(Type.String.INSTANCE),
                FailurePolicyId.NO_DEAL_FAILURE);
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
                SemanticIrValidator.binaryPolicy(selector));
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
                SemanticIrValidator.unaryPolicy(selector));
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
            IntrinsicKind kind = intrinsicKindOf(call);
            ExpressionNode argument = call.args().get(0);
            Type argumentType = checkedType(argument);
            ValueId input = lowerExpression(argument);
            return emitOperandOp(SemanticOpKind.INTRINSIC_CALL,
                new KindPayload.IntrinsicCallPayload(kind, input),
                List.of(input), List.of(valueDescriptorOf(argumentType)),
                call.span(), valueDescriptorOf(checkedType(call)),
                SemanticIrValidator.intrinsicPolicy(kind));
        }

        /**
         * The intrinsic-call classifier (I3): exactly {@code int(...)}
         * and {@code number(...)} calls of the root
         * {@code Symbol.IntrinsicSymbol} bindings lower; every other call
         * shape — including {@code bytes(...)}, {@code has(...)} call
         * fallbacks, and ordinary user calls — fails closed.
         */
        private IntrinsicKind intrinsicKindOf(CallExpr call) {
            if (call.args().size() == 1
                    && call.callee() instanceof IdentifierExpr identifier) {
                Symbol symbol = checks.symbolTable().resolve(identifier.name());
                if (symbol instanceof Symbol.IntrinsicSymbol intrinsic) {
                    if ("int".equals(intrinsic.name())) {
                        return IntrinsicKind.INT_CONVERT;
                    }
                    if ("number".equals(intrinsic.name())) {
                        return IntrinsicKind.NUMBER_CONVERT;
                    }
                }
            }
            throw new ConstructUnlowered("call expression (only int()/number() intrinsic "
                + "calls lower in this slice — INTRINSIC_CALL is the I3 terminal-check "
                + "arm; the CALL machinery is E7's and bytes() is the bytes exclusion)");
        }

        /**
         * The pinned one-line scalar descriptor rows of the value-operation
         * slice (I3): the slice admits exactly these rows —
         * null, boolean, signed32 int, number, string, and the nullable
         * rows over int/number/boolean (the nullable descriptor over the
         * same inner row) — for exactly
         * the descriptor positions its arms introduce (the conversion
         * intrinsics admit nullable int/number inputs). The rows realize
         * through the single {@link DescriptorService} producer (the
         * verbatim D2 scalar table — the slice builds no structural
         * descriptor service, ISSUE-0233 is excluded here); every other
         * checked type reaching a value-op descriptor position is a
         * producer defect ({@link ConstructUnlowered}).
         */
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
            ops.add(buildOp(opId, kind, payload, null, null, policy, origin));
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
            ops.add(buildOp(opId, kind, payload, value, resultType, List.of(), List.of(),
                policy, origin));
            return value;
        }

        /** Emits one value-producing USER op without operands and returns its value id. */
        private ValueId emitValueOp(SemanticOpKind kind, KindPayload payload, Span span,
                                    RuntimeDescriptor resultType, FailurePolicyId policy) {
            return emitOperandOp(kind, payload, List.of(), List.of(), span, resultType,
                policy);
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
            ValueId value = ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(span),
                SourceOriginKind.USER, anchor, chainParents.peek());
            ops.add(buildOp(opId, kind, payload, value, resultType, operands, operandTypes,
                policy, origin));
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
                case Type.Class classType -> "@" + classType.modulePath() + "/"
                    + classType.name();
                case Type.Func func -> "function";
                default -> String.valueOf(type);
            };
        }

        /** The foreign-expression description of the E6005 origin. */
        private String describeExpression(ExpressionNode expr) {
            return switch (expr) {
                case deal.ast.CallExpr ignored ->
                    "call expression (only int()/number() intrinsic calls lower in this "
                        + "slice — INTRINSIC_CALL is the I3 terminal-check arm; the CALL "
                        + "machinery is E7's)";
                case deal.ast.IndexExpr ignored ->
                    "index access expression (INDEX_NORMALIZE/INDEX_READ are E5's, "
                        + "ISSUE-0234)";
                case deal.ast.FunctionExpr ignored ->
                    "function expression (CLOSURE_NEW is E6's, ISSUE-0235)";
                case deal.ast.HasExpr ignored ->
                    "has expression (HAS_FIELD is E5's, ISSUE-0234)";
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
