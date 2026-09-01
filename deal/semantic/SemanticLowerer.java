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
import deal.semantic.ir.ControlSelector;
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
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.ValueId;
import deal.types.Type;

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
 *       recursively under the loop-binding frame;</li>
 *   <li>{@link ForOfStatement} with an array-typed iterable
 *       ({@code [T]}) → one {@code FOR_EACH {mode: ARRAY_VALUES,
 *       iterable, binding, generation, body}} (control-flow-structures
 *       C-D5: the iterable completes exactly once before START; the op
 *       snapshots the array reference and the initial length and visits
 *       slot indices {@code 0..initialLength-1} in increasing order with
 *       the op's own {@code TYPE_DESCRIPTOR} terminal check against the
 *       element descriptor derived from the recorded array operand type,
 *       {@code [T]} → element {@code T}; a fresh binding per iteration,
 *       mechanics E6). The element type is derived fail-closed through
 *       the {@code ContainerPayloadDescriptors} bridge — an
 *       unrepresentable element ({@code bytes}) is E6005
 *       {@code DESCRIPTOR_UNREPRESENTABLE}, never an invented
 *       descriptor.</li>
 * </ul>
 *
 * <p><b>The E5 control-flow arms (pinned; ISSUE-0409).</b> Over checked
 * facts, the arms lower every checked control-flow construct into the
 * pinned structured ops of control-flow-structures C-D3..C-D8 with the
 * per-op execution contracts recorded for consumers, and produce the
 * {@link StructuredBodyTable} block-membership record for the unit
 * (C-D1):</p>
 *
 * <ul>
 *   <li>{@link IfStatement} → {@code BRANCH(IF) {selector: IF,
 *       condition, selectedBlock, alternateBlock}}: the condition's
 *       producing ops complete in the enclosing block before the
 *       {@code BRANCH} op; exactly one of {@code selectedBlock}/
 *       {@code alternateBlock} executes; the {@code else} branch is
 *       {@code alternateBlock} (an {@code else if} chain nests its
 *       {@code BRANCH} op inside the alternate block); an absent
 *       {@code else} produces {@code alternateBlock = null}; SUCCESS
 *       publishes no result;</li>
 *   <li>{@link BinaryExpr} with {@code &&}/{@code ||} → {@code
 *       BRANCH(LOGICAL_AND/LOGICAL_OR)} (never {@code BINARY}): the
 *       left operand completes before START as the condition; the right
 *       operand's producing ops live in {@code selectedBlock} and
 *       execute only when the left value does not decide the result
 *       (AND: left false → result false, block skipped; OR: left true →
 *       result true, block skipped). The op's result {@code ValueId} is
 *       the right operand's value identity (result type {@code
 *       D(boolean)} — a boolean either way, checker-pinned boolean
 *       operands). Chained {@code &&}/{@code ||} lower to nested
 *       {@code BRANCH}es in source order;</li>
 *   <li>{@link WhileStatement} → {@code LOOP(WHILE) {selector: WHILE,
 *       initBlock = the per-iteration condition block, condition,
 *       bodyBlock, updateBlock = null}}: repeat { execute
 *       {@code initBlock}; evaluate the condition value; if false →
 *       SUCCESS; execute {@code bodyBlock} }; the condition ops are
 *       explicit members of {@code initBlock} (C-D1), never an inferred
 *       subgraph; no speculative body execution;</li>
 *   <li>{@link ForStatement} → {@code LOOP(FOR) {selector: FOR,
 *       initBlock = one-time init including the first condition
 *       production, condition, bodyBlock, updateBlock = [update ops,
 *       condition-producing ops]}}: execute {@code initBlock} once;
 *       repeat { evaluate the condition value; if false → SUCCESS;
 *       execute {@code bodyBlock}; execute {@code updateBlock} }. The
 *       condition {@code ValueId} is produced once in {@code initBlock}
 *       and re-produced by the {@code updateBlock} production (the most
 *       recently produced value of the condition {@code ValueId} wins —
 *       one value identity, re-produced per iteration). A test-less
 *       {@code for (;;)} produces exactly one {@code CONST} op with the
 *       boolean value {@code true} in {@code initBlock} as the
 *       condition production, and {@code updateBlock} carries only the
 *       update ops (no condition production). A {@code let}-declared
 *       for-initializer is E6's {@code BINDING_ALLOC} — fail closed;</li>
 *   <li>{@link TryStatement} → {@code TRY_CATCH {tryBlock,
 *       catchBinding, catchBlock}} (C-D6: execute {@code tryBlock};
 *       success → SUCCESS, catch skipped; a DEAL failure raised inside
 *       {@code tryBlock} is reified as an {@code Error} value bound to
 *       {@code catchBinding} — binding init mechanics E6 — and
 *       {@code catchBlock} executes; a failure raised from
 *       {@code catchBlock} becomes the {@code TRY_CATCH} FAILURE with
 *       its own code/message/origin preserved and {@code cause} = the
 *       original caught failure snapshot). The catch binding identity
 *       is allocated by this stage and a load of the catch variable
 *       inside {@code catchBlock} lowers to {@code BINDING_LOAD}
 *       carrying that binding with the pinned initial generation;</li>
 *   <li>{@link ThrowStatement} → {@code THROW {errorValue}}: the operand
 *       completes before START; the op never succeeds; policy
 *       {@code THROW_TRANSFER} — code/message from the supplied
 *       {@code Error} value's fields, origin = the THROW origin, frames
 *       active; control transfers to the nearest enclosing
 *       {@code TRY_CATCH}, else the error escapes as the host-visible
 *       {@code DEALRuntimeError};</li>
 *   <li>{@link BreakStatement}/{@link ContinueStatement} →
 *       {@code BREAK}/{@code CONTINUE {loopId = the innermost enclosing
 *       loop op ({@code LOOP} or {@code FOR_EACH}) recorded by the
 *       lowerer}} (C-D7: BREAK exits the target loop; CONTINUE — FOR:
 *       execute {@code updateBlock} then re-test; WHILE: execute the
 *       condition block ({@code initBlock}) then re-test; FOR_EACH:
 *       next slot index; transfer across an enclosing {@code TRY_CATCH}
 *       boundary is legal). The checker's E2000 pins source-level loop
 *       placement, so a missing target is a producer defect;</li>
 *   <li>{@link ExpressionStatement} → {@code DISCARD {value}}: the
 *       value's producing ops already completed before the op; START →
 *       SUCCESS with no result; origin kind {@code SYNTHETIC} (C-D8 —
 *       the intentional discard is audited in the op stream and traces,
 *       never inferred away).</li>
 * </ul>
 *
 * <p><b>Block membership (C-D1).</b> Every op the session produces is a
 * member of exactly one block (the block stack of the session); the
 * module-init block is the root block of the unit's module-level
 * statements; every payload-referenced {@code BlockId} exists in the
 * produced {@link StructuredBodyTable} (empty child blocks included);
 * block ops record the enclosing structure op as {@code parentOpId}.
 * The unit-production seam validates the produced unit plus table
 * through {@link ControlFlowValidator} (C-D2 — block tree, dominance,
 * exits; violations are E6005 {@code CONTROL_BLOCK_TREE}/
 * {@code CONTROL_EXIT}). A source statement following a terminator
 * ({@code THROW}/{@code BREAK}/{@code CONTINUE}) in the same block is
 * unreachable and fails closed ({@code CONSTRUCT_UNLOWERED}) — the
 * validated block model never admits an op after a terminator in its
 * block. The condition of a {@code LOOP(FOR)} is re-produced in
 * {@code updateBlock} publishing the same condition {@code ValueId}
 * (the slot-threaded expression lowering below).</p>
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
 * — class-typed object literals ({@code CLASS_NEW} is E9's), class
 * member access ({@code FIELD_READ} is E9's), module member access
 * ({@code EXPORT_READ} is E10's), {@code .length} on bytes
 * (ISSUE-0158), comparison binary operators (the comparison producer
 * {@link ComparisonSelectorLowering}'s), ordinary calls ({@code CALL}
 * is E7's), and every other foreign construct. An {@code IntLiteral}
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
 * <p><b>The closure child (ISSUE-0445).</b> {@link
 * #lowerModuleClosureCore} drives the same session in closure-core mode
 * (the binding walk plus the closure arms): {@code CLOSURE_NEW}/
 * {@code LoweredFunction} for every function expression and every size-1
 * non-group function declaration (the group child partitions SCCs),
 * capture-by-binding with the capture set collected in first-reference
 * order during the buffered detached-body walk (the free bindings of the
 * body resolved through the dominant frame entries at the creation site —
 * B3/B9 R2/R3 as the resolution model, so doubly-nested captures resolve
 * transitively along the detaching chain), captures of later-declared
 * module functions resolving to the hoisted module-init ALLOC (B1), the
 * closure-capture arm of the B2 {@code SHARED_CELL} cell-kind upgrade
 * (whole-scope: the finalization re-derivation over the capture-reference
 * union rewrites every affected {@code BINDING_ALLOC} payload through the
 * single {@link CellKindDerivation} — no production path writes a
 * cell-kind literal outside that derivation, and the thunk/adapter arms
 * are registered by the shape-map child, T7), the {@code LoweredBody}
 * {@code functionBindings} registrations through the registry child's
 * registration seam (B5), and static function-identity preservation on
 * function-typed loads ({@code R-FUNCTION-BINDING} holds by construction;
 * dynamic function values — parameters, catch bindings, iteration
 * bindings — fail closed as the registry child's resolution). Adapter
 * creation and invocation stay out of this child's window.</p>
 *
 * <p><b>The recursive-group child (ISSUE-0446).</b> {@link
 * #lowerModuleGroupCore} drives the same session in group-core mode
 * (the binding walk plus the closure arms plus the group arms): within
 * one scope the lowerer builds the reference graph over function
 * declarations (name references in bodies) and partitions it into SCCs.
 * An SCC of size >= 2 lowers as exactly one {@code RECURSIVE_GROUP_INIT}
 * op with the declaration-ordered member {@code {bindings, functions}}
 * payload — phase 1 allocates a fresh {@code FunctionAllocationIdentity}
 * per member (pre-assigned at lowering in declaration order, unique per
 * member, deterministic) and registers each member's
 * {@code LoweredBody}; phase 2 publishes every member binding cell
 * atomically (the op has no result slot — the bindings are the observable
 * effect). Member cells are {@code SHARED_CELL} by construction (B2:
 * the closed group payload records no cell-kind field, SCC mutual capture
 * makes every member captured, and no member carries a separate
 * {@code BINDING_ALLOC}/{@code BINDING_INIT}); member loads resolve to
 * the group op as the producing allocation at generation 0 (B1 —
 * {@code BindingProducer.RECURSIVE_GROUP_INIT}). Module-level groups
 * execute at module-init top in declaration order (B4); nested-scope
 * groups execute at the first member's declaration position. Member
 * bodies lower like any function body with captures per the closure
 * contract (B3). A size-1 SCC — including a self-recursive declaration —
 * lowers as {@code CLOSURE_NEW} + {@code BINDING_INIT} at the
 * declaration position (the closure child's arm); no group op is
 * produced. Group execution (allocation/publication) is realized by the
 * oracle/emitters — this child produces the op, the member identities,
 * and the payload; {@code GROUP_SHAPE} validation and member invocation
 * stay out of this child's window.</p>
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
 * <p><b>Claiming (D9 items 2–4, at E5's gate).</b> Units built by this
 * stage derive their claim set through {@link ContainerClaimingSeam} (the
 * unit-producer claiming seam, ISSUE-0387): the full-evidence claim
 * derivation over the produced ops and the then-active rows — at E5's
 * gate the active rows are
 * {@link ContainerClaimingSeam#E5_GATE_ACTIVATION} — checked with
 * the derived set as the unit's claims (the derivation-invariant guard —
 * the E6005 {@code OPERATION_OUTSIDE_CLAIMED_CAPABILITY} firing condition
 * can never trigger inside the producer because the unit claims exactly
 * its derived set). A unit fully evidencing an active row claims it; an
 * under-evidenced row defers per unit and the still-staged rows record
 * staged hand-offs. The manifest's plan-time claims are routing facts and
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
     * an incarnation is produced by its {@code BINDING_ALLOC} op, by
     * the {@code FOR_EACH} iteration op (the iteration binding's producing
     * allocation — {@code FOR_EACH} payloads record no cell kind because
     * iteration bindings are always {@code SHARED_CELL}, B2), or by the
     * {@code RECURSIVE_GROUP_INIT} group op (the recursive-group child,
     * ISSUE-0446: a group member's producing allocation is the group op's
     * publication phase — members have no separate
     * {@code BINDING_ALLOC}/{@code BINDING_INIT}, B1/B4, and member cells
     * are {@code SHARED_CELL} by construction, B2).
     */
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

    /**
     * The single cell-kind derivation of the BINDINGS capability's
     * capture-driven cell-kind rule (B2): the final cell kind of every
     * binding incarnation is derived exactly here, over the union of the
     * pinned special cases ({@code FOR_EACH} iteration bindings and
     * for-let per-iteration incarnations — the binding-core defaults)
     * and the registered capture references. The complete closed iff
     * ("{@code SHARED_CELL} iff any function capture resolves to the
     * incarnation") is enforced by construction: cell kinds are emitted
     * only from this derivation, never from a literal outside it.
     *
     * <p><b>Ownership split (B2's three capture arms).</b> The closure
     * child (ISSUE-0445) registers the {@code CLOSURE_NEW}/
     * {@code LoweredFunction.captures} arm through
     * {@link #registerCaptureReference} and re-derives every
     * {@code BINDING_ALLOC} payload cell kind after the walk (whole-scope
     * analysis: a closure anywhere in the enclosing scope referencing the
     * binding upgrades every incarnation of that binding that a capture
     * resolves to). The shape-map child (T7) registers the two remaining
     * arms — {@code REEVALUATE_THUNK} {@code capturedBindings} entries and
     * {@code FUNCTION_ADAPT(SHARED_CELL)} {@code SharedCell} source
     * references — through the same registration surface and runs the
     * final derivation after registering them; this child emits and
     * claims neither arm.</p>
     *
     * <p>Capture references are registered per incarnation instance
     * (identity semantics): two equal-shaped incarnations of different
     * bindings never alias in the reference set.</p>
     */
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
         * @param incarnation the resolved incarnation; non-null
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
         * @param incarnation the incarnation; non-null
         * @return the derived {@code DIRECT|SHARED_CELL} kind
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

    /**
     * One closure's complete fact record (ISSUE-0445 closure child): the
     * function identity, the exact signature, the body block identity,
     * and the resolved captures in first-reference order.
     */
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

    /**
     * One group member's complete fact record (ISSUE-0446 recursive-group
     * child): the declared name, the member's single {@link BindingId}
     * (no separate {@code BINDING_ALLOC}/{@code BINDING_INIT} — the
     * group op's publication phase is the producing allocation), the
     * member function identity, the pre-assigned member allocation
     * identity (unique per member, declaration order — the key the
     * registry's {@code LoweredBody} registration consumes, B4/B5), the
     * exact signature, the body block identity, and the member body's
     * captures resolved at the group op's creation site (B3).
     */
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

    /**
     * One produced {@code RECURSIVE_GROUP_INIT} op's complete fact
     * record (ISSUE-0446 recursive-group child): the op identity, the
     * declaration-ordered member facts, and the block the group op sits
     * in (module-init top for module-level groups; the first member's
     * enclosing block for nested-scope groups — B4).
     */
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
     * The result of the module-level unit production: the validated unit
     * plus its produced {@link StructuredBodyTable} (C-D1), or
     * {@code null}/{@code null} with exactly the first E6005 diagnostic
     * on failure (a lowered-away construct, an unrepresentable
     * descriptor, a validator rejection of the produced unit, an
     * address-chain protocol violation, or a control-flow validation
     * rejection).
     *
     * @param unit        the validated {@link LoweredModuleUnit}, or
     *                    {@code null} on failure
     * @param table       the produced block-membership table of the unit
     *                    (C-D1), or {@code null} on failure
     * @param diagnostics empty on success, otherwise the failure
     *                    diagnostics
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
     * One catch-binding frame of the lowering environment (C-D6): the
     * enclosing {@code TRY_CATCH}'s catch-variable name and its
     * producer-allocated {@link BindingId}. A {@code BINDING_LOAD} of
     * the frame's name carries the frame's binding with the pinned
     * initial generation; the try/catch arm pushes exactly one frame
     * while lowering the catch block. Binding init mechanics are E6's
     * (ISSUE-0235) — this stage pins the binding identity and the load
     * resolution only.
     */
    public record CatchFrame(String name, BindingId binding) {

        public CatchFrame {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(binding, "binding must not be null");
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
     * the closed validator, the production-time address-chain protocol
     * (A-D1), and the production-time control-flow validation of
     * {@link ControlFlowValidator} over the unit plus its produced
     * {@link StructuredBodyTable} (C-D2 — block tree, dominance, exits;
     * the first rejection is the returned diagnostic). At E5's gate the
     * unit's capability claim set is derived through the claiming seam
     * under {@link ContainerClaimingSeam#E5_GATE_ACTIVATION} (D9 item
     * 5(c) — {@code CONTAINERS_AND_STRINGS} and {@code EVALUATION_ORDER}
     * activate).
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
     * @return the validated unit plus its block-membership table, or
     *         the first E6005 on failure
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
            return new LoweringResult(null, null, List.of(FailureContractRegistry.e6005(
                new LoweringFailureDetail(module.moduleId().path(),
                    SemanticCapability.FOUNDATION_VALUES, LOWER_LEGACY_PROFILE_REJECTED,
                    profile, LoweredModuleUnit.FORMAT_VERSION, "SemanticLowerer"))));
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
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new LoweringResult(null, null, List.of(validation.get()));
        }
        // E5 production-time check (A-D1): the closed address-chain
        // protocol runs after the foundation validator — every produced
        // ASSIGN/DELETE chain must match exactly one closed A-D9 shape
        // with single evaluation; the first violation is the returned
        // E6005 (ADDRESS_CHAIN_SHAPE | SINGLE_EVALUATION).
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new LoweringResult(null, null, List.of(chainShape.get()));
        }
        // E5 production-time check (C-D2): the produced block-membership
        // table of the unit must pass the control-flow validator — block
        // tree, dominance, and exit checks; the first violation is the
        // returned E6005 (CONTROL_BLOCK_TREE | CONTROL_EXIT).
        StructuredBodyTable table = lowerer.bodyTable();
        Optional<CompilerDiagnostic> controlFlow = ControlFlowValidator.validate(unit, table);
        if (controlFlow.isPresent()) {
            return new LoweringResult(null, null, List.of(controlFlow.get()));
        }
        return new LoweringResult(unit, table, List.of());
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
            return new BindingCoreResult(new LoweringResult(null, null,
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
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(unit,
            new SemanticIrValidator.ComparisonFacts(interfaceHash,
                SemanticProfile.DEAL_V1_2_INT32, capabilityRegistryHash));
        if (validation.isPresent()) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(validation.get())), lowerer.bindingFacts());
        }
        Optional<CompilerDiagnostic> chainShape = AddressChainProtocol.validate(unit);
        if (chainShape.isPresent()) {
            return new BindingCoreResult(new LoweringResult(null, null,
                List.of(chainShape.get())), lowerer.bindingFacts());
        }
        return new BindingCoreResult(new LoweringResult(unit, lowerer.bodyTable(), List.of()),
            lowerer.bindingFacts());
    }

    /**
     * The closure child's public lowering entry point (ISSUE-0445
     * sequencing item 2): lowers one checked implementation module
     * through the binding walk plus the closure arms — {@code
     * CLOSURE_NEW}/{@code LoweredFunction} for every function expression
     * and every size-1 non-group function declaration, capture-by-binding
     * resolution at the detaching op's creation site recursively along
     * the detaching chain, the closure-capture arm of the B2
     * {@code SHARED_CELL} cell-kind upgrade, and the {@code LoweredBody}
     * {@code functionBindings} registrations through the registry child's
     * registration seam — and produces the validated unit plus the
     * binding facts (final derived cell kinds) and the closure capture
     * facts.
     *
     * <p>The walk consumes the binding-core walk's statement and
     * value-expression seams (never re-implementing them); function
     * bodies lower through the same arms with capture collection active
     * during body walks (B9 R2/R3 as the resolution model: a reference
     * inside a detached body resolves the innermost frame entry — the
     * function's own scope chain first, outer frames as captures — and
     * the capture's producing allocation is the dominant incarnation at
     * the creation site). Function-typed {@code BINDING_LOAD}s preserve
     * allocation identity statically: a load of a function-typed binding
     * publishes the producing allocation identity its cell currently
     * holds (the pre-allocated identity of a hoisted module-level
     * function, the tracked identity of an initializer/store) so the
     * schema-level {@code R-FUNCTION-BINDING} rule holds; a function-typed
     * load whose cell value identity is not statically known (parameters,
     * catch bindings, iteration bindings) fails closed as the registry
     * child's resolution (B5).</p>
     *
     * <p>This entry point is driven by the closure tests; no production
     * route change — retained/public compilation paths and
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
     * @return the validated unit with the binding and closure facts, or
     *         the first E6005 with the partial facts on failure
     */
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

    /**
     * The recursive-group child's public lowering entry point (ISSUE-0446
     * sequencing item 3): lowers one checked implementation module
     * through the binding walk plus the closure arms plus the group arms
     * — per-scope SCC partition over function declarations (name
     * references in bodies), one {@code RECURSIVE_GROUP_INIT} op per
     * size>=2 SCC with the declaration-ordered member
     * {@code {bindings, functions}} payload and pre-assigned unique
     * member allocation identities (the keys the registry's one
     * {@code LoweredBody} per member consumes, B4/B5), module-init-top
     * placement for module-level groups and first-member-position
     * placement for nested-scope groups, member cells
     * {@code SHARED_CELL} by construction with no separate
     * {@code BINDING_ALLOC}/{@code BINDING_INIT} (B1/B2), member bodies
     * lowering like any function body with captures per the closure
     * contract (B3), and size-1 SCCs — self-recursive declarations
     * included — lowering as {@code CLOSURE_NEW} + {@code BINDING_INIT}
     * at the declaration position (the closure child's arm) — and
     * produces the validated unit plus the binding facts, the closure
     * capture facts, and the group member facts.
     *
     * <p>Group-core mode implies closure-core and binding-core mode: the
     * walk consumes the binding walk's statement and value-expression
     * seams and the closure child's buffered detached-body walk with
     * capture collection. The group op has no result slot (the member
     * bindings are the observable effect); member {@code BINDING_LOAD}s
     * resolve to the group op as the producing allocation at generation
     * 0 and publish the member's pre-assigned allocation identity, so
     * the schema-level {@code R-FUNCTION-BINDING} rule holds by
     * construction for the one {@code LoweredBody} registration per
     * member. Group execution (phase 1 identity allocation, phase 2
     * atomic publication) is realized by the oracle/emitters — this
     * child produces the op, the member identities, and the payload;
     * {@code GROUP_SHAPE} validation (the validation child) and member
     * invocation (E7) stay out of this child's window.</p>
     *
     * <p>This entry point is driven by the recursive-group tests; no
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
     * @return the validated unit with the binding, closure, and group
     *         facts, or the first E6005 with the partial facts on failure
     */
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
         * The per-block termination flags (C-D2 dominance): a
         * {@code THROW}/{@code BREAK}/{@code CONTINUE} terminates its
         * block; a following statement in the same block is unreachable
         * source and fails closed — the validated block model never
         * admits an op after a terminator in its block.
         */
        private final java.util.LinkedHashMap<BlockId, Boolean> blockTerminated =
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
         * The closure-core mode flag (ISSUE-0445 closure child):
         * {@code true} exactly when the session was created by
         * {@link SemanticLowerer#lowerModuleClosureCore} — the closure
         * arms ({@code CLOSURE_NEW} for every function expression and
         * size-1 non-group function declaration), capture collection
         * during detached-body walks, and the closure-capture arm of the
         * B2 cell-kind upgrade are active. Closure-core mode implies
         * binding-core mode (the closure walk is the binding walk plus
         * the closure arms).
         */
        private final boolean closureCore;
        /**
         * The group-core mode flag (ISSUE-0446 recursive-group child):
         * {@code true} exactly when the session was created by
         * {@link SemanticLowerer#lowerModuleGroupCore} — the group arms
         * (per-scope SCC partition over function declarations and one
         * {@code RECURSIVE_GROUP_INIT} op per size>=2 SCC with
         * pre-assigned member identities, module-init-top placement for
         * module-level groups and first-member-position placement for
         * nested-scope groups) are active on top of the closure arms.
         * Group-core mode implies closure-core and binding-core mode (the
         * group walk is the closure walk plus the group arms).
         */
        private final boolean groupCore;
        /**
         * The produced recursive groups' member facts in creation order
         * (group-core mode): the fact surface backing
         * {@link #groupFacts()}.
         */
        private final List<GroupFacts> groupFactsList = new ArrayList<>();
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
         * The produced function-execution-bindings registry keyed by
         * {@link FunctionAllocationIdentity} (the unit's
         * {@code functionBindings} map; the registry child's registration
         * seam, B5 — closure-core mode).
         */
        private final Map<FunctionAllocationIdentity, FunctionExecutionBinding>
            functionBindings = new LinkedHashMap<>();
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
            this(module, sourceId, checks, ids, bindingCore, false, programSpan);
        }

        /**
         * Creates one lowering session with the binding-core mode flag
         * and the closure-core mode flag (ISSUE-0444 binding-core child;
         * ISSUE-0445 closure child). Closure-core mode implies
         * binding-core mode (the closure walk is the binding walk plus
         * the closure arms).
         *
         * @param module      the module identity; non-null
         * @param sourceId    the stable source identity carried on every
         *                    op's origin; non-null
         * @param checks      the module's checked facts (read-only); non-null
         * @param ids         the project's allocator in dependency order;
         *                    non-null
         * @param bindingCore {@code true} to activate the binding walk's
         *                    arms and environment
         * @param closureCore {@code true} to activate the closure arms on
         *                    top of the binding walk
         * @param programSpan the checked program's span; non-null
         */
        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, Span programSpan) {
            this(module, sourceId, checks, ids, bindingCore, closureCore, false, programSpan);
        }

        /**
         * Creates one lowering session with the binding-core, closure-core,
         * and group-core mode flags (ISSUE-0444 binding-core child;
         * ISSUE-0445 closure child; ISSUE-0446 recursive-group child).
         * Group-core mode implies closure-core and binding-core mode (the
         * group walk is the closure walk plus the group arms).
         *
         * @param module      the module identity; non-null
         * @param sourceId    the stable source identity carried on every
         *                    op's origin; non-null
         * @param checks      the module's checked facts (read-only); non-null
         * @param ids         the project's allocator in dependency order;
         *                    non-null
         * @param bindingCore {@code true} to activate the binding walk's
         *                    arms and environment
         * @param closureCore {@code true} to activate the closure arms on
         *                    top of the binding walk
         * @param groupCore   {@code true} to activate the group arms on
         *                    top of the closure walk
         * @param programSpan the checked program's span; non-null
         */
        public ModuleLowerer(ModuleId module, String sourceId, CheckResult checks,
                             SemanticIdAllocator ids, boolean bindingCore,
                             boolean closureCore, boolean groupCore, Span programSpan) {
            this.module = Objects.requireNonNull(module, "module must not be null");
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            this.checks = Objects.requireNonNull(checks, "checks must not be null");
            this.ids = Objects.requireNonNull(ids, "ids must not be null");
            this.bindingCore = bindingCore;
            this.closureCore = closureCore && bindingCore;
            this.groupCore = groupCore && this.closureCore;
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
         * @return the fresh block identity
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
         * Marks the current emission block terminated after a
         * {@code THROW}/{@code BREAK}/{@code CONTINUE} emission (C-D2
         * dominance: no op may follow a terminator in its block).
         */
        private void terminateBlock() {
            blockTerminated.put(blockStack.peek(), true);
        }

        /**
         * Fails closed when the current emission block already carries a
         * terminator: a source statement following a
         * {@code THROW}/{@code BREAK}/{@code CONTINUE} in the same block
         * is unreachable and not representable in the validated block
         * model — {@code CONSTRUCT_UNLOWERED}, never a silent drop and
         * never an inferred block.
         */
        private void ensureBlockOpen() {
            if (Boolean.TRUE.equals(blockTerminated.get(blockStack.peek()))) {
                throw new ConstructUnlowered("statement after a terminator "
                    + "(THROW/BREAK/CONTINUE) in the same block — unreachable source "
                    + "statements are not representable in the validated block model");
            }
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
            finalizeCellKinds();
        }

        /**
         * The group walk's module entry point (ISSUE-0446 recursive-group
         * child): the intrinsic bindings and the hoisted module-level
         * names first (group members register without an ALLOC op and
         * pre-assign their member allocation identities — B1/B4), then
         * the module-level {@code RECURSIVE_GROUP_INIT} ops at
         * module-init top in declaration order (B4: module top level
         * admits only imports, function declarations, class declarations,
         * and exports — hoisting the group op is observationally
         * equivalent to declaration-position execution and places every
         * member's producing allocation where it dominates every closure
         * site, B3), then the statements in source order (group members
         * skip — their bodies were walked at group emission), then the
         * cell-kind finalization.
         *
         * @param statements the module's top-level statements; non-null
         * @throws IllegalStateException outside group-core mode
         * @throws ConstructUnlowered    on a construct outside the
         *         group-core window
         */
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
         * The closure walk's complete closure fact surface: one
         * {@link ClosureFacts} per produced {@code CLOSURE_NEW} in
         * creation order, each with its captures resolved at the
         * detaching op's creation site (partial when the walk failed
         * mid-way).
         *
         * @return the recorded closure facts; non-null
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
         * @return the recorded group facts; non-null
         */
        public List<GroupFacts> groupFacts() {
            return List.copyOf(groupFactsList);
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
                BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                    INITIAL_LOOP_GENERATION, moduleInitBlock, BindingCellKind.DIRECT,
                    false, BindingProducer.BINDING_ALLOC, false);
                registerBinding(name, binding, incarnation);
                emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                    new KindPayload.BindingAllocPayload(binding, moduleInitBlock, false,
                        cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                    moduleInitSpan(), FailurePolicyId.NO_DEAL_FAILURE);
                ValueId intrinsicValue = ids.nextValueId(module, nextOrdinal++, 0);
                // No static function-identity tracking for intrinsics: a
                // first-class intrinsic value has no closed
                // FunctionExecutionBinding shape, so a function-typed
                // load of an intrinsic fails closed as the registry
                // child's resolution (B5) — this child never emits an
                // unvalidatable function-typed load.
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

        /**
         * Hoists the module-level names with the given module-level group
         * members excluded from {@code BINDING_ALLOC} emission (ISSUE-0446
         * group walk): a group member's name still registers — one
         * {@link BindingId} per declared name (B1) — with the
         * {@code RECURSIVE_GROUP_INIT} producing-allocation kind, the
         * pinned {@code SHARED_CELL} cell kind (B2: the closed group
         * payload records no cell-kind field, SCC mutual capture makes
         * every member captured, and members have no separate ALLOC/INIT),
         * and the member's pre-assigned allocation identity (the key the
         * registry's one {@code LoweredBody} per member consumes, B4/B5).
         */
        private void hoistModuleLevelAllocs(List<StatementNode> statements,
                                            Set<FunctionDeclaration> groupMembers) {
            for (StatementNode statement : statements) {
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
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, moduleInitBlock, false,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
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
         *
         * <p><b>Group-core mode (ISSUE-0446).</b> The walk partitions the
         * scope's function declarations into SCCs over the body-reference
         * graph first (B4): a size>=2 SCC lowers as one
         * {@code RECURSIVE_GROUP_INIT} op — nested-scope groups at the
         * first member's declaration position, module-level groups at
         * module-init top (already emitted by
         * {@link #lowerGroupModule}, so the walk skips every member) —
         * and a size-1 SCC — self-recursive declarations included —
         * lowers as {@code CLOSURE_NEW} + {@code BINDING_INIT} at the
         * declaration position (the closure child's arm).</p>
         */
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
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    declarations.add(function);
                }
            }
            List<List<FunctionDeclaration>> sccs =
                partitionFunctionDeclarations(declarations);
            Map<FunctionDeclaration, List<FunctionDeclaration>> memberGroups =
                new IdentityHashMap<>();
            for (List<FunctionDeclaration> scc : sccs) {
                if (scc.size() >= 2) {
                    for (FunctionDeclaration member : scc) {
                        memberGroups.put(member, scc);
                    }
                }
            }
            for (StatementNode statement : statements) {
                if (statement instanceof FunctionDeclaration function) {
                    List<FunctionDeclaration> group = memberGroups.get(function);
                    if (group == null) {
                        // Size-1 SCC: CLOSURE_NEW + BINDING_INIT at the
                        // declaration position (B4).
                        lowerBindingFunctionDecl(function, moduleLevel);
                    } else if (!moduleLevel && group.get(0) == function) {
                        // Nested-scope group: the op executes at the
                        // first member's declaration position (B4).
                        lowerGroup(group, false);
                    }
                    // Module-level groups were emitted at module-init top
                    // (lowerGroupModule); non-first nested members were
                    // lowered at the first member's position.
                    continue;
                }
                lowerBindingStatement(statement);
            }
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
            BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, currentBlock(), BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC, false);
            registerBinding(decl.name(), binding, incarnation);
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(binding, currentBlock(), true,
                    cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
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
            if (closureCore && descriptorOf(value) instanceof RuntimeDescriptor.Func) {
                // The initializer's function identity is the cell's
                // current value identity (loads preserve allocation
                // identity; R-FUNCTION-BINDING holds by construction).
                functionIdentity.put(incarnation, value);
            }
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(binding, INITIAL_LOOP_GENERATION, value),
                decl.span(), FailurePolicyId.NO_DEAL_FAILURE);
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
         * The binding walk's function-declaration arm: the module-level
         * name ALLOC was hoisted (B1); a nested-scope declaration emits
         * its name ALLOC at the declaration position in the enclosing
         * block. The body then gets its block identity, the parameter
         * ALLOCs at the body block's entry (generation 0, {@code DIRECT},
         * no BINDING_INIT — the parameter-transfer write is the invoking
         * machinery's, E7), and the body statements through the binding
         * walk.
         *
         * <p><b>Closure-core mode (ISSUE-0445).</b> Every size-1
         * non-group declaration additionally produces {@code CLOSURE_NEW}
         * + {@code BINDING_INIT} at the declaration position (B4): the
         * body walks first into a buffer with capture collection active
         * (the capture set is the body's free bindings in first-reference
         * order, resolved at the creation site through the dominant frame
         * entries — B3/B9 R2), then the {@code CLOSURE_NEW} op publishes
         * the pre-allocated function-allocation identity (module-level
         * names reuse the hoist-time identity so captures of
         * later-declared module functions resolve to the hoisted ALLOC —
         * B1; nested names pre-allocate at the declaration), the
         * {@code BINDING_INIT} commits it to the name binding as the
         * immediate commit after the closure creation, and the buffered
         * body ops flush after the pair.</p>
         */
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
                for (deal.ast.Parameter parameter : function.params()) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                        true, BindingProducer.BINDING_ALLOC, false);
                    registerBinding(parameter.name(), binding, incarnation);
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, bodyBlock, true,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                        parameter.span(), FailurePolicyId.NO_DEAL_FAILURE);
                }
                checkerScopeNodes.push(function.body());
                lowerBindingStatements(function.body().statements(), false);
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
            BlockId bodyBlock = allocateBlock();
            FunctionId functionId = ids.nextFunctionId(module, nextOrdinal++, 0);
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
                for (deal.ast.Parameter parameter : function.params()) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                        true, BindingProducer.BINDING_ALLOC, false);
                    registerBinding(parameter.name(), binding, incarnation);
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, bodyBlock, true,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                        parameter.span(), FailurePolicyId.NO_DEAL_FAILURE);
                }
                checkerScopeNodes.push(function.body());
                lowerBindingStatements(function.body().statements(), false);
                checkerScopeNodes.pop();
                blockStack.pop();
                popBindingFrame();
                checkerScopeNodes.pop();
            } finally {
                captureCollectors.pop();
                captureBorders.pop();
                emitTargets.pop();
            }
            List<BindingId> captureIds = new ArrayList<>();
            for (CapturedCell capture : captured) {
                captureIds.add(capture.cell().id);
            }
            emitClosureNew(functionId, closureIdentity, signature, captureIds, bodyBlock,
                function.span(), captured);
            emitUserNullOp(SemanticOpKind.BINDING_INIT,
                new KindPayload.BindingInitPayload(nameBinding, INITIAL_LOOP_GENERATION,
                    closureIdentity),
                function.span(), FailurePolicyId.NO_DEAL_FAILURE);
            emitTarget().addAll(bodyOps);
        }

        /**
         * Lowers one size>=2 SCC of function declarations as exactly one
         * {@code RECURSIVE_GROUP_INIT} op (ISSUE-0446 recursive-group
         * child, B4): the payload carries the declaration-ordered member
         * {@code {bindings, functions}} lists; the op has no result slot
         * (the member bindings are the observable effect).
         *
         * <p><b>Identities first (phase 1).</b> Every member's
         * allocation identity is pre-assigned at lowering — unique per
         * member, deterministic, declaration order — before any member
         * body walks, so a sibling reference inside a member body
         * resolves to the sibling's incarnation (generation 0, producing
         * allocation = the group op) and publishes the pre-assigned
         * member identity; the one {@code LoweredBody} registration per
         * member is keyed by that identity (B4/B5, the keys the registry
         * consumes). Module-level members reuse the hoist-time
         * registration and pre-assigned identity (B1); nested-scope
         * members register at the group position with no
         * {@code BINDING_ALLOC}/{@code BINDING_INIT} — the publication
         * phase (phase 2) publishes every member binding cell atomically,
         * all-or-nothing, after every member identity is allocated.</p>
         *
         * <p>Member bodies lower like any function body with captures per
         * the closure contract (B3): the buffered detached-body walk with
         * capture collection resolves each body's free bindings at the
         * group op's creation site. Member cells are {@code SHARED_CELL}
         * by construction (B2). The group op is emitted at the given
         * site — module-init top for module-level groups, the first
         * member's declaration position for nested-scope groups (B4) —
         * and the member body ops flush after it in declaration order.
         * Group execution (allocation/publication) is realized by the
         * oracle/emitters.</p>
         *
         * @param members     the SCC's members in declaration order
         *                    (size >= 2); non-null
         * @param moduleLevel {@code true} for a module-level group whose
         *                    op sits at module-init top
         */
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
            List<List<BindingId>> captureIdLists = new ArrayList<>();
            List<BlockId> bodyBlocks = new ArrayList<>();
            List<RuntimeDescriptor.Func> signatures = new ArrayList<>();
            List<List<CapturedCell>> capturedLists = new ArrayList<>();
            for (FunctionDeclaration member : members) {
                BlockId bodyBlock = allocateBlock();
                FunctionId functionId = ids.nextFunctionId(module, nextOrdinal++, 0);
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
                    for (deal.ast.Parameter parameter : member.params()) {
                        BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                        BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                            INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                            true, BindingProducer.BINDING_ALLOC, false);
                        registerBinding(parameter.name(), binding, incarnation);
                        emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                            new KindPayload.BindingAllocPayload(binding, bodyBlock, true,
                                cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                            parameter.span(), FailurePolicyId.NO_DEAL_FAILURE);
                    }
                    checkerScopeNodes.push(member.body());
                    lowerBindingStatements(member.body().statements(), false);
                    checkerScopeNodes.pop();
                    blockStack.pop();
                    popBindingFrame();
                    checkerScopeNodes.pop();
                } finally {
                    captureCollectors.pop();
                    captureBorders.pop();
                    emitTargets.pop();
                }
                List<BindingId> captureIds = new ArrayList<>();
                for (CapturedCell capture : captured) {
                    captureIds.add(capture.cell().id);
                }
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
                List<BindingId> captureIds = captureIdLists.get(i);
                BlockId bodyBlock = bodyBlocks.get(i);
                RuntimeDescriptor.Func signature = signatures.get(i);
                ValueId identity = memberIdentities.get(i);
                functions.put(functionId, new LoweredFunction(functionId, signature,
                    captureIds, bodyBlock));
                registerFunctionBinding(new FunctionAllocationIdentity(identity.id()),
                    new FunctionExecutionBinding.LoweredBody(functionId, bodyBlock));
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
            BlockId tryBlock = allocateBlock();
            BlockId catchBlock = allocateBlock();
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
            BindingCoreIncarnation catchIncarnation = new BindingCoreIncarnation(
                INITIAL_LOOP_GENERATION, catchBlock, BindingCellKind.DIRECT,
                true, BindingProducer.BINDING_ALLOC, false);
            registerBinding(statement.catchVar(), catchBinding, catchIncarnation);
            emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(catchBinding, catchBlock, true,
                    cellKinds.cellKindOf(catchIncarnation), INITIAL_LOOP_GENERATION),
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
            BlockId blockId = allocateBlock();
            checkerScopeNodes.push(block);
            pushBindingFrame();
            blockStack.push(blockId);
            lowerBindingStatements(block.statements(), false);
            blockStack.pop();
            popBindingFrame();
            checkerScopeNodes.pop();
        }

        // ---------------------------------------------------------------------
        // The recursive-group SCC partition (ISSUE-0446, B4)
        // ---------------------------------------------------------------------

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
         * @param declarations the scope's function declarations in
         *                     declaration order; non-null
         * @return the SCCs (size-1 self-recursive SCCs included)
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
         * The registry child's registration seam (B5, closure-core mode):
         * exactly one {@link FunctionExecutionBinding} per function
         * allocation identity, recorded in the unit's
         * {@code functionBindings}. A duplicate registration is a
         * producer defect (fail closed, never overwritten).
         */
        private void registerFunctionBinding(FunctionAllocationIdentity identity,
                                             FunctionExecutionBinding binding) {
            FunctionExecutionBinding previous = functionBindings.putIfAbsent(identity,
                binding);
            if (previous != null) {
                throw new IllegalStateException("duplicate function-binding registration "
                    + "for " + identity + " (producer defect)");
            }
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
                                    List<BindingId> captures, BlockId bodyBlock, Span span,
                                    List<CapturedCell> captured) {
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
            registerFunctionBinding(new FunctionAllocationIdentity(result.id()), binding);
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
         * @param expr the checked expression; non-null
         * @return the produced {@link ValueId}
         * @throws ConstructUnlowered on a construct without an arm in this
         *         stage's window
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
         * @param expr the checked expression; non-null
         * @param slot the result slot of the final producing op, or
         *             {@code null} to allocate a fresh value
         * @return the produced {@link ValueId} (the slot when given)
         * @throws ConstructUnlowered on a construct without an arm in this
         *         stage's window
         */
        public ValueId lowerExpression(ExpressionNode expr, ValueId slot) {
            Objects.requireNonNull(expr, "expr must not be null");
            return switch (expr) {
                case LiteralExpr literal -> lowerConst(literal, slot);
                case IdentifierExpr identifier -> lowerBindingLoad(identifier, slot);
                case FunctionExpr functionExpr -> {
                    if (closureCore) {
                        yield lowerClosureExpr(functionExpr);
                    }
                    throw new ConstructUnlowered(describeExpression(expr));
                }
                case ArrayLiteralExpr array -> lowerArrayNew(array, slot);
                case ObjectLiteralExpr object -> lowerTableNew(object, slot);
                case MemberAccessExpr access -> lowerMemberAccess(access, slot);
                case BinaryExpr binary -> lowerBinary(binary, slot);
                case TemplateLiteralExpr template -> lowerTemplate(template, slot);
                case UnaryExpr unary -> lowerUnary(unary, slot);
                case CallExpr call -> lowerIntrinsicCall(call, slot);
                case AssignmentExpr assignment -> lowerAssignment(assignment, slot);
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
                lowerStatements(stmt.body().statements());
            } finally {
                popLoopTarget();
                popBlock();
                popBlockParent();
                closeForEachScope();
            }
            return opId;
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
        private OpId lowerArrayForOf(ForOfStatement stmt, Type.Array arrayType) {
            ContainerPayloadDescriptors.elementDescriptorOf(arrayType.element());
            BlockId bodyBlock = allocateBlock();
            ValueId iterable = lowerExpression(stmt.iterable());
            ForEachFrame frame = openForEachScope(stmt.varName());
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
                lowerStatements(stmt.body().statements());
            } finally {
                popLoopTarget();
                popBlock();
                popBlockParent();
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
            return lowerAssignment(assignment, null);
        }

        /**
         * Lowers one checked assignment with an explicit result slot:
         * the slot threads to the RHS expression's final producing op,
         * whose value identity the {@code ASSIGN} op publishes (A-D6).
         *
         * @param assignment the checked assignment expression; non-null
         * @param slot       the committed-value slot, or {@code null} to
         *                   allocate fresh
         * @return the committed value's {@link ValueId}
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
            return lowerClassFieldAssign(assignment, access, classType, null);
        }

        private ValueId lowerClassFieldAssign(AssignmentExpr assignment,
                                              MemberAccessExpr access, Type.Class classType,
                                              ValueId slot) {
            RuntimeDescriptor fieldDescriptor =
                ContainerPayloadDescriptors.resultDescriptorOf(checkedType(access));
            ClassId classId = new ClassId(DescriptorService.semanticModulePath(classType.identity()), classType.name());
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

        /**
         * DELETE CLASS_FIELD — {@code [containerOp,
         * commitOp(FIELD_DELETE)]}: no key, no normalize, no bounds
         * boundary (table and class targets run no bounds boundary;
         * A-D9).
         */
        private void lowerClassFieldDelete(DeleteStatement delete, MemberAccessExpr access,
                                           Type.Class classType) {
            ClassId classId = new ClassId(DescriptorService.semanticModulePath(classType.identity()), classType.name());
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

        /**
         * Builds the immutable unit over the session's produced
         * operations: the manifest's construct-coverage rows recorded at
         * lowering start (each row must carry the closed construct→op
         * detector table verbatim, S4), the capability claim set derived
         * through the claiming seam's full-evidence derivation under
         * {@link ContainerClaimingSeam#E5_GATE_ACTIVATION} (D9 item 5(c):
         * {@code CONTAINERS_AND_STRINGS} and {@code EVALUATION_ORDER}
         * activate at E5's gate — this epic's gate; a unit fully
         * evidencing an active row claims it, an under-evidenced row
         * defers per unit), the module-init plan over the session's init
         * block, and the produced operations in source order.
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
                Map.copyOf(functions),
                new ModuleInitPlan(List.copyOf(imports), moduleInitBlock),
                ExportPlan.empty(),
                Map.copyOf(functionBindings),
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
         * @return the produced block-membership table (defensively
         *         copied by the record)
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

        // ---------------------------------------------------------------------
        // Statement arms (the E5 positionable window)
        // ---------------------------------------------------------------------

        private void lowerStatements(List<StatementNode> statements) {
            for (StatementNode statement : statements) {
                ensureBlockOpen();
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
         * {@code BRANCH} as {@code parentOpId}.
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
                lowerStatements(statement.thenBlock().statements());
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
                            lowerStatements(right.value().statements());
                    }
                } finally {
                    popBlock();
                }
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
                lowerStatements(statement.body().statements());
            } finally {
                popLoopTarget();
                popBlock();
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
                lowerStatements(statement.body().statements());
            } finally {
                popLoopTarget();
                popBlock();
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

        /**
         * {@code TRY_CATCH} — the try/catch arm (C-D6): execute
         * {@code tryBlock}; success → SUCCESS (catch skipped, no
         * result); a DEAL failure (an E8 error) raised inside
         * {@code tryBlock} is reified as an {@code Error} value
         * ({@code {code, message}}) bound to {@code catchBinding}
         * (binding init mechanics E6 — this stage allocates the binding
         * identity) and {@code catchBlock} executes; a failure raised
         * from {@code catchBlock} becomes the {@code TRY_CATCH} FAILURE
         * with its own code/message/origin preserved and
         * {@code cause} = the original caught failure snapshot. Only
         * DEAL failures are catchable. A load of the catch variable
         * inside {@code catchBlock} lowers to {@code BINDING_LOAD}
         * carrying the catch binding with the pinned initial
         * generation. The {@code TRY_CATCH} op precedes its child block
         * ops in the unit list (payload order: try, catch). Block ops
         * record the {@code TRY_CATCH} as {@code parentOpId}.
         */
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
                lowerStatements(statement.tryBlock().statements());
            } finally {
                popBlock();
            }
            catchFrames.add(0, new CatchFrame(statement.catchVar(), catchBinding));
            pushBlock(catchBlock);
            try {
                lowerStatements(statement.catchBlock().statements());
            } finally {
                popBlock();
                catchFrames.remove(0);
            }
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
         * (C-D2 dominance).
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
            terminateBlock();
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
            terminateBlock();
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

        /**
         * {@code BINDING_LOAD} — the identifier arm with the loop-binding
         * load-resolution rule: the load carries the innermost matching
         * enclosing {@code FOR_EACH} payload's initial generation; any
         * other identifier is a foreign construct (E6005).
         *
         * <p><b>Closure-core mode (ISSUE-0445).</b> The load resolves
         * the declared binding of the walk's environment through the
         * frame walk (the function's own scope chain first — B9 R2), and
         * a reference resolving outside the innermost open detached-body
         * walk registers the capture (B3: the capture set is the body's
         * free bindings in first-reference order). A load of a
         * function-typed binding publishes the incarnation's statically
         * tracked function identity (loads preserve allocation identity);
         * a function-typed load whose identity is not statically known
         * (parameters, catch bindings, iteration bindings — dynamic
         * function values) fails closed as the registry child's
         * resolution (B5).</p>
         */
        private ValueId lowerBindingLoad(IdentifierExpr identifier) {
            return lowerBindingLoad(identifier, null);
        }

        private ValueId lowerBindingLoad(IdentifierExpr identifier, ValueId slot) {
            Type type = checkedType(identifier);
            for (ForEachFrame frame : frames) {
                if (frame.name().equals(identifier.name())) {
                    return emitValueOp(SemanticOpKind.BINDING_LOAD,
                        new KindPayload.BindingLoadPayload(frame.binding(), frame.generation()),
                        identifier.span(), ContainerPayloadDescriptors.resultDescriptorOf(type),
                        FailurePolicyId.NO_DEAL_FAILURE, slot);
                }
            }
            for (CatchFrame frame : catchFrames) {
                if (frame.name().equals(identifier.name())) {
                    return emitValueOp(SemanticOpKind.BINDING_LOAD,
                        new KindPayload.BindingLoadPayload(frame.binding(),
                            INITIAL_LOOP_GENERATION),
                        identifier.span(), ContainerPayloadDescriptors.resultDescriptorOf(type),
                        FailurePolicyId.NO_DEAL_FAILURE, slot);
                }
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
                + "' is not a load of an enclosing for-of loop binding or catch "
                + "binding in this stage's window (binding allocation, non-loop "
                + "loads, and generation increments/stores are E6's, ISSUE-0235)");
        }

        /**
         * Emits one resolved {@code BINDING_LOAD} (closure-core mode):
         * the payload names the dominant incarnation's
         * {@code {binding, generation}}; the result publishes the
         * incarnation's statically tracked function identity for
         * function-typed loads (identity preservation) or a fresh
         * {@code ValueId} otherwise.
         */
        private ValueId emitResolvedLoad(IdentifierExpr identifier, Type type,
                                         FrameEntry entry) {
            RuntimeDescriptor descriptor = ContainerPayloadDescriptors.resultDescriptorOf(type);
            ValueId result = null;
            if (descriptor instanceof RuntimeDescriptor.Func) {
                result = functionIdentity.get(entry.incarnation());
                if (result == null) {
                    throw new ConstructUnlowered("function-typed load of '"
                        + identifier.name() + "' whose cell value identity is not "
                        + "statically tracked (dynamic function values — parameters, "
                        + "catch bindings, iteration bindings, and first-class "
                        + "intrinsics, whose closed binding shape is the registry "
                        + "child's — are the registry child's resolution, B5)");
                }
            }
            if (result == null) {
                result = ids.nextValueId(module, nextOrdinal++, 0);
            }
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(identifier.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.BINDING_LOAD,
                new KindPayload.BindingLoadPayload(entry.cell().id,
                    entry.incarnation().generation()),
                result, descriptor, FailurePolicyId.NO_DEAL_FAILURE, origin));
            return result;
        }

        /**
         * {@code CLOSURE_NEW} — the function-expression arm (closure-core
         * mode, ISSUE-0445): every function expression produces exactly
         * one {@code CLOSURE_NEW} publishing a fresh function identity
         * with the function expression's exact checked signature, the
         * body's captures in first-reference order (collected during the
         * buffered body walk — B3), and the {@code LoweredBody} binding.
         * The body ops flush after the {@code CLOSURE_NEW} op (the
         * buffered walk runs first so the captures are known when the
         * payload is built).
         */
        private ValueId lowerClosureExpr(FunctionExpr functionExpr) {
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
                for (deal.ast.Parameter parameter : functionExpr.params()) {
                    BindingId binding = ids.nextBindingId(module, nextOrdinal++, 0);
                    BindingCoreIncarnation incarnation = new BindingCoreIncarnation(
                        INITIAL_LOOP_GENERATION, bodyBlock, BindingCellKind.DIRECT,
                        true, BindingProducer.BINDING_ALLOC, false);
                    registerBinding(parameter.name(), binding, incarnation);
                    emitUserNullOp(SemanticOpKind.BINDING_ALLOC,
                        new KindPayload.BindingAllocPayload(binding, bodyBlock, true,
                            cellKinds.cellKindOf(incarnation), INITIAL_LOOP_GENERATION),
                        parameter.span(), FailurePolicyId.NO_DEAL_FAILURE);
                }
                lowerBindingStatements(functionExpr.body().statements(), false);
                blockStack.pop();
                popBindingFrame();
                checkerScopeNodes.pop();
            } finally {
                captureCollectors.pop();
                captureBorders.pop();
                emitTargets.pop();
            }
            List<BindingId> captureIds = new ArrayList<>();
            for (CapturedCell capture : captured) {
                captureIds.add(capture.cell().id);
            }
            ValueId result = ids.nextValueId(module, nextOrdinal++, 0);
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
                ContainerPayloadDescriptors.elementDescriptorOf(arrayType.element());
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
            return result;
        }

        /** {@code TABLE_NEW} — entry values in source order, then the op. */
        private ValueId lowerTableNew(ObjectLiteralExpr literal) {
            return lowerTableNew(literal, null);
        }

        private ValueId lowerTableNew(ObjectLiteralExpr literal, ValueId slot) {
            Type type = checkedType(literal);
            if (type instanceof Type.Class classType) {
                throw new ConstructUnlowered("class-typed object literal "
                    + DescriptorService.semanticModulePath(classType.identity())
                    + "/" + classType.name()
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
                FailurePolicyId.NO_DEAL_FAILURE, slot);
        }

        /** {@code ARRAY_LENGTH}/{@code MEMBER_READ} — the member-access dispatch. */
        private ValueId lowerMemberAccess(MemberAccessExpr access) {
            return lowerMemberAccess(access, null);
        }

        private ValueId lowerMemberAccess(MemberAccessExpr access, ValueId slot) {
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
                return lowerArrayLength(access, slot);
            }
            if (objectType instanceof Type.Table) {
                return lowerMemberRead(access, slot);
            }
            if (objectType instanceof Type.Class classType) {
                throw new ConstructUnlowered("class member access "
                    + DescriptorService.semanticModulePath(classType.identity())
                    + "/" + classType.name() + "." + access.field()
                    + " (FIELD_READ is E9's)");
            }
            if (objectType instanceof Type.Bytes) {
                throw new ConstructUnlowered("member access '" + access.field()
                    + "' on bytes (bytes value semantics are ISSUE-0158's; a bytes member "
                    + "access reaching an E5 arm fails hard)");
            }
            throw new ConstructUnlowered("member access '" + access.field() + "' on "
                + typeName(objectType) + " (no member-access arm for this receiver shape "
                + "in this stage's window)");
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

        /** {@code MEMBER_READ} — the missing-aware read plus its contextual child. */
        private ValueId lowerMemberRead(MemberAccessExpr access) {
            return lowerMemberRead(access, null);
        }

        private ValueId lowerMemberRead(MemberAccessExpr access, ValueId slot) {
            Type contextualType = checkedType(access);
            RuntimeDescriptor resultType =
                ContainerPayloadDescriptors.resultDescriptorOf(contextualType);
            ValueId receiver = lowerExpression(access.object());
            ValueId result = slot != null ? slot : ids.nextValueId(module, nextOrdinal++, 0);
            AnchorId anchor = ids.nextAnchorId(module, nextOrdinal++, 0);
            OpId opId = ids.nextOpId(module, nextOrdinal++, 0);
            SourceOrigin origin = new SourceOrigin(sourceId, toSourceSpan(access.span()),
                SourceOriginKind.USER, anchor, currentParent());
            emit(buildOp(opId, SemanticOpKind.MEMBER_READ,
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
                // The binding walk consumes the single comparison producer
                // (the values epic's producer) for condition/initializer
                // comparison operands — this child never implements
                // comparison-selector lowering itself.
                return lowerComparisonBinary(binary);
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
            return emitOperandOp(SemanticOpKind.INTRINSIC_CALL,
                new KindPayload.IntrinsicCallPayload(kind, input),
                List.of(input), List.of(valueDescriptorOf(argumentType)),
                call.span(), valueDescriptorOf(checkedType(call)),
                SemanticIrValidator.intrinsicPolicy(kind), slot);
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
