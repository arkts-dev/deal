package deal.semantic;

import deal.ast.BinaryOp;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.ValueId;
import deal.types.Type;
import deal.types.Types;

import java.util.List;
import java.util.Objects;

public final class ComparisonSelectorLowering {

    public static final String COMPARISON_SELECTOR = "COMPARISON_SELECTOR";

    private ComparisonSelectorLowering() {
        // Static surface only; no instances and no state.
    }

    /**
     * A comparison-production fact defect: a checked operand pair without
     * a closed B-D3 selector row reached the comparison producer — a
     * {@code Type.Error} pair, a mixed pair, a relational over a
     * non-orderable type, or an operator outside the six comparison
     * operators ({@code &&}/{@code ||}, arithmetic). The unit-production
     * seam owns the conversion into E6005 ({@code COMPARISON_SELECTOR}
     * via {@link #loweringFailureDetail(ModuleId, Defect)}); this
     * exception is internal control flow, never a crash and never a
     * guessed selector.
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message) {
            super(message);
        }

        public Defect(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =========================================================================
    // B-D3 selector map
    // =========================================================================

    /**
     * Selects exactly one closed comparison selector from the checked
     * operand types and the operator (B-D3), producing the exact
     * {@code BinaryPayload}: {@code innerDescriptor} carries the shared
     * checked descriptor for {@code REFERENCE_*} and {@code BYTES_*} and
     * the inner descriptor for {@code NULLABLE_*}/{@code
     * NULLABLE_NULL_*}; {@code side} is carried only for the nullable
     * selectors and is {@code null} for every other selector.
     *
     * <p>Deterministic and total over lowering-reachable comparisons
     * (B-D3/B-D7). Every pair outside the map — a {@code Type.Error}
     * pair, a mixed pair, a relational over a non-orderable type, or an
     * operator outside {@code EQ}/{@code NEQ}/{@code LT}/{@code LTE}/
     * {@code GT}/{@code GTE} — raises {@link Defect} (fail closed, never
     * an invented selector).</p>
     *
     */
    public static KindPayload.BinaryPayload payloadOf(BinaryOp op, Type leftType, Type rightType) {
        Objects.requireNonNull(op, "op must not be null");
        Objects.requireNonNull(leftType, "leftType must not be null");
        Objects.requireNonNull(rightType, "rightType must not be null");

        if (leftType == Type.Error.INSTANCE || rightType == Type.Error.INSTANCE) {
            throw new Defect("a comparison operand's checked type is the internal Error sentinel: "
                + "no closed comparison selector row exists (COMPARISON_SELECTOR)");
        }

        switch (op) {
            case EQ -> {
                return equalityPayload(leftType, rightType, false);
            }
            case NEQ -> {
                return equalityPayload(leftType, rightType, true);
            }
            case LT -> {
                return relationalPayload(leftType, rightType, BinarySelector.STRING_LT,
                    BinarySelector.NUMBER_LT, BinarySelector.INT32_LT);
            }
            case LTE -> {
                return relationalPayload(leftType, rightType, BinarySelector.STRING_LE,
                    BinarySelector.NUMBER_LE, BinarySelector.INT32_LE);
            }
            case GT -> {
                return relationalPayload(leftType, rightType, BinarySelector.STRING_GT,
                    BinarySelector.NUMBER_GT, BinarySelector.INT32_GT);
            }
            case GTE -> {
                return relationalPayload(leftType, rightType, BinarySelector.STRING_GE,
                    BinarySelector.NUMBER_GE, BinarySelector.INT32_GE);
            }
            case AND, OR -> throw new Defect(
                "the logical operator " + op + " reached the comparison producer: '&&'/'||' "
                    + "never lower to BINARY (they lower to selector-bearing BRANCH — "
                    + "COMPARISON_SELECTOR)");
            case ADD, SUB, MUL, DIV, MOD, POW -> throw new Defect(
                "the arithmetic operator " + op + " reached the comparison producer: "
                    + "arithmetic selectors are the signed-int32/containers epics' domain "
                    + "(COMPARISON_SELECTOR)");
        }
        throw new Defect("unreachable: every BinaryOp value is handled above");
    }

    /**
     * The equality row of B-D3 (EQ/NEQ over equal types, nullable-vs-null
     * in either direction).
     */
    private static KindPayload.BinaryPayload equalityPayload(Type leftType, Type rightType,
                                                             boolean negated) {
        if (Types.equals(leftType, rightType)) {
            BinarySelector selector = switch (leftType) {
                case Type.Int ignored -> negated ? BinarySelector.INT32_NE
                    : BinarySelector.INT32_EQ;
                case Type.Number ignored -> negated ? BinarySelector.NUMBER_NE
                    : BinarySelector.NUMBER_EQ;
                case Type.String ignored -> negated ? BinarySelector.STRING_NE
                    : BinarySelector.STRING_EQ;
                case Type.Boolean ignored -> negated ? BinarySelector.BOOLEAN_NE
                    : BinarySelector.BOOLEAN_EQ;
                case Type.Null ignored -> negated ? BinarySelector.NULL_NE
                    : BinarySelector.NULL_EQ;
                case Type.Nullable ignored -> {
                    // Equal nullable types never use a reference selector:
                    // null is not a reference (B-D3); the inner descriptor
                    // is derived once in payloadFor.
                    yield negated ? BinarySelector.NULLABLE_NE : BinarySelector.NULLABLE_EQ;
                }
                case Type.Array a ->
                    negated ? BinarySelector.REFERENCE_NE : BinarySelector.REFERENCE_EQ;
                case Type.Table t ->
                    negated ? BinarySelector.REFERENCE_NE : BinarySelector.REFERENCE_EQ;
                case Type.Class c ->
                    negated ? BinarySelector.REFERENCE_NE : BinarySelector.REFERENCE_EQ;
                case Type.Func f ->
                    negated ? BinarySelector.REFERENCE_NE : BinarySelector.REFERENCE_EQ;
                case Type.Bytes b -> negated ? BinarySelector.BYTES_NE
                    : BinarySelector.BYTES_EQ;
                case Type.Error e ->
                    throw new Defect("unreachable: Error pairs are rejected before "
                        + "the selector map");
            };
            return payloadFor(selector, leftType, rightType, null);
        }
        // T|null vs null literal: side names the nullable operand.
        if (leftType instanceof Type.Nullable && rightType instanceof Type.Null) {
            return payloadFor(negated ? BinarySelector.NULLABLE_NULL_NE
                    : BinarySelector.NULLABLE_NULL_EQ, leftType, rightType, NullableSide.LEFT);
        }
        if (rightType instanceof Type.Nullable && leftType instanceof Type.Null) {
            return payloadFor(negated ? BinarySelector.NULLABLE_NULL_NE
                    : BinarySelector.NULLABLE_NULL_EQ, leftType, rightType, NullableSide.RIGHT);
        }
        throw new Defect("mixed comparison pair with no closed equality row (the checker "
            + "rejects it with E3006 before lowering): " + typeText(leftType) + " vs "
            + typeText(rightType) + " (COMPARISON_SELECTOR)");
    }

    /**
     * The relational row of B-D3 (equal int/number/string types only).
     */
    private static KindPayload.BinaryPayload relationalPayload(Type leftType, Type rightType,
                                                               BinarySelector stringSelector,
                                                               BinarySelector numberSelector,
                                                               BinarySelector intSelector) {
        if (!Types.equals(leftType, rightType)) {
            throw new Defect("mixed relational pair with no closed comparison row (the checker "
                + "rejects it with E3007 before lowering): " + typeText(leftType) + " vs "
                + typeText(rightType) + " (COMPARISON_SELECTOR)");
        }
        BinarySelector selector = switch (leftType) {
            case Type.Int ignored -> intSelector;
            case Type.Number ignored -> numberSelector;
            case Type.String ignored -> stringSelector;
            default -> throw new Defect(
                "relational comparison over a non-orderable type: " + typeText(leftType)
                    + " (the checker rejects it with E3007 before lowering — "
                    + "COMPARISON_SELECTOR)");
        };
        return payloadFor(selector, leftType, rightType, null);
    }

    // =========================================================================
    // Payload assembly
    // =========================================================================

    /**
     * Assembles the exact {@code BinaryPayload} of the selected row:
     * {@code innerDescriptor} carries the shared checked descriptor for
     * {@code REFERENCE_*} and {@code BYTES_*} (the equal checked type —
     * same structural descriptor on both sides) and the inner descriptor
     * for {@code NULLABLE_*} (side {@code BOTH}) and {@code NULLABLE_NULL_*}
     * (side {@code LEFT}/{@code RIGHT}); {@code side} and
     * {@code innerDescriptor} are {@code null} for every other selector.
     */
    private static KindPayload.BinaryPayload payloadFor(BinarySelector selector, Type leftType,
                                                        Type rightType, NullableSide side) {
        RuntimeDescriptor innerDescriptor = null;
        NullableSide effectiveSide = null;
        switch (selector) {
            case NULLABLE_EQ, NULLABLE_NE -> {
                // Equal nullable types: side BOTH, inner = the shared
                // inner type (B-D3 — never a reference selector).
                Type inner = ((Type.Nullable) leftType).inner();
                innerDescriptor = describeInner(inner);
                effectiveSide = NullableSide.BOTH;
            }
            case NULLABLE_NULL_EQ, NULLABLE_NULL_NE -> {
                Type nullableSide = side == NullableSide.LEFT ? leftType : rightType;
                Type inner = ((Type.Nullable) nullableSide).inner();
                innerDescriptor = describeInner(inner);
                effectiveSide = side;
            }
            case REFERENCE_EQ, REFERENCE_NE, BYTES_EQ, BYTES_NE -> {
                // One equal checked descriptor (B-D3): the shared
                // structural descriptor of the equal checked types.
                innerDescriptor = describeShared(leftType);
            }
            default -> {
                // INT32_*/NUMBER_*/STRING_*/BOOLEAN_*/NULL_* carry no
                // inner descriptor and no side.
            }
        }
        return new KindPayload.BinaryPayload(selector, innerDescriptor, effectiveSide);
    }

    /** Derives the inner descriptor of a {@code NULLABLE_*} row through the single producer. */
    private static RuntimeDescriptor describeInner(Type inner) {
        try {
            return DescriptorService.describe(inner);
        } catch (DescriptorService.Defect defect) {
            throw new Defect("the NULLABLE_* inner type is not descriptor-representable: "
                + defect.getMessage(), defect);
        }
    }

    /** Derives the shared checked descriptor of a {@code REFERENCE_*}/{@code BYTES_*} row. */
    private static RuntimeDescriptor describeShared(Type shared) {
        try {
            return DescriptorService.describe(shared);
        } catch (DescriptorService.Defect defect) {
            throw new Defect("the REFERENCE_*/BYTES_* checked type is not descriptor-representable: "
                + defect.getMessage(), defect);
        }
    }

    // =========================================================================
    // One BINARY comparison op
    // =========================================================================

    /**
     * Produces exactly one {@code BINARY} comparison op for one checked
     * comparison (B-D3): the B-D3 selector/payload, operands in source
     * order (left then right), operand types via the single descriptor
     * producer, result type {@code boolean}, failure policy
     * {@code NO_DEAL_FAILURE} (validator-pinned), a fresh {@code ValueId}
     * result and {@code OpId} allocated at the caller's coordinates
     * ({@code sourceOrdinal}, {@code syntheticOrdinal}) through the
     * caller's {@link SemanticIdAllocator}, the caller's
     * {@code SourceOrigin} verbatim (no AST identity is retained), and a
     * contract snapshot with the T3 digest computed by
     * {@code ContractSnapshotCanonicalizer}.
     *
     * <p>The two operand {@code ValueId}s are the completed operand
     * values of the source operands — the enclosing lowerer evaluates
     * each source operand exactly once and passes the completed values
     * (B-D1: operands complete left-to-right before {@code BINARY}
     * START). This producer never evaluates, re-emits, or re-reads an
     * operand.</p>
     *
     */
    public static SemanticOp produce(
        ModuleId module,
        BinaryOp op,
        Type leftType,
        Type rightType,
        ValueId leftOperand,
        ValueId rightOperand,
        SourceOrigin origin,
        SemanticIdAllocator allocator,
        long sourceOrdinal,
        long syntheticOrdinal
    ) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(leftOperand, "leftOperand must not be null");
        Objects.requireNonNull(rightOperand, "rightOperand must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(allocator, "allocator must not be null");
        if (sourceOrdinal < 0) {
            throw new IllegalArgumentException("sourceOrdinal must be >= 0, got " + sourceOrdinal);
        }
        if (syntheticOrdinal < 0) {
            throw new IllegalArgumentException(
                "syntheticOrdinal must be >= 0, got " + syntheticOrdinal);
        }

        KindPayload.BinaryPayload payload = payloadOf(op, leftType, rightType);
        RuntimeDescriptor leftDescriptor = describeOperand(leftType);
        RuntimeDescriptor rightDescriptor = describeOperand(rightType);
        RuntimeDescriptor booleanDescriptor = DescriptorService.describe(Type.Boolean.INSTANCE);

        ValueId result = allocator.nextValueId(module, sourceOrdinal, syntheticOrdinal);
        OpId opId = allocator.nextOpId(module, sourceOrdinal, syntheticOrdinal);

        List<ValueId> operands = List.of(leftOperand, rightOperand);
        List<RuntimeDescriptor> operandTypes = List.of(leftDescriptor, rightDescriptor);

        OperationContractSnapshot contract =
            contractFor(SemanticOpKind.BINARY, payload, booleanDescriptor, operandTypes,
                FailurePolicyId.NO_DEAL_FAILURE, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(SemanticOpKind.BINARY, payload, booleanDescriptor, operandTypes,
            FailurePolicyId.NO_DEAL_FAILURE, digest);

        return new SemanticOp(opId, SemanticOpKind.BINARY, origin, result, booleanDescriptor,
            operands, operandTypes, payload, FailurePolicyId.NO_DEAL_FAILURE, contract);
    }

    /** Derives an operand descriptor through the single producer (total after the map). */
    private static RuntimeDescriptor describeOperand(Type operandType) {
        try {
            return DescriptorService.describe(operandType);
        } catch (DescriptorService.Defect defect) {
            throw new Defect("a comparison operand type is not descriptor-representable: "
                + defect.getMessage(), defect);
        }
    }

    /**
     * The two-phase contract wiring (T3): build with a placeholder
     * digest, recompute, carry the recomputed digest.
     */
    private static OperationContractSnapshot contractFor(SemanticOpKind kind,
                                                         KindPayload payload,
                                                         RuntimeDescriptor resultType,
                                                         List<RuntimeDescriptor> operandTypes,
                                                         FailurePolicyId policy, String digest) {
        deal.semantic.ir.ClosedSelector selector =
            payload instanceof KindPayload.SelectorCarrying carrying
                ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    // =========================================================================
    // E6005 COMPARISON_SELECTOR failure carrier
    // =========================================================================

    /**
     * The pinned failure-carrier seam (B-D3/B-D7): produces the named
     * {@code COMPARISON_SELECTOR} failure carrying the exact
     * {@link LoweringFailureDetail} fields for a {@link Defect} raised by
     * {@link #payloadOf(BinaryOp, Type, Type)} or
     * {@link #produce} — {@code module}, {@code capability
     * EVALUATION_ORDER}, {@code validatorRule COMPARISON_SELECTOR},
     * {@code semanticProfile DEAL_V1_2_INT32}, {@code irVersion
     * deal.semantic-ir/1}, and the {@code ComparisonSelectorLowering}
     * origin. The unit-production seam converts the returned detail into
     * the E6005 diagnostic through
     * {@code FailureContractRegistry.e6005(detail)}; this producer
     * constructs no diagnostic and no selector on this path.
     *
     */
    public static LoweringFailureDetail loweringFailureDetail(ModuleId module, Defect defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        return new LoweringFailureDetail(
            module.path(),
            SemanticCapability.EVALUATION_ORDER,
            COMPARISON_SELECTOR,
            SemanticProfile.DEAL_V1_2_INT32,
            LoweredModuleUnit.FORMAT_VERSION,
            "ComparisonSelectorLowering " + COMPARISON_SELECTOR
                + " (" + defect.getMessage() + ")");
    }

    /**
     * The pinned E6005 conversion seam (B-D3/B-D7): builds the
     * {@code COMPARISON_SELECTOR} E6005 diagnostic for a {@link Defect}
     * raised by {@link #payloadOf(BinaryOp, Type, Type)} or
     * {@link #produce} through
     * {@code FailureContractRegistry.e6005(loweringFailureDetail(...))} —
     * the registry owns the message construction, never this producer.
     * The diagnostic carries code E6005, {@code BACKEND_LOWERING} phase,
     * error severity, and the canonical synthetic range, exactly like
     * every other E6005 (parent D11).
     *
     */
    public static CompilerDiagnostic e6005(ModuleId module, Defect defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        return FailureContractRegistry.e6005(loweringFailureDetail(module, defect));
    }

    /** A short canonical spelling of a checked type for defect messages. */
    private static String typeText(Type type) {
        return TypeChecker.typeName(type);
    }
}
