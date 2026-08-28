package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The mandatory operation record of {@code deal.semantic-ir/1} (parent
 * "Mandatory operation header and contract snapshot"; schema S2):
 *
 * <pre>{@code
 * SemanticOp {
 *   opId: OpId, kind: SemanticOpKind,
 *   origin: SourceOrigin,                       // {sourceId, span, USER|SYNTHETIC, anchorId, parentOpId?}
 *   result: ValueId|AsyncTokenId|none,
 *   resultType: RuntimeDescriptor|INTERNAL_MISSING|INTERNAL_ASYNC|none,
 *   operands: [ValueId], operandTypes: [RuntimeDescriptor],
 *   payload: KindPayload,                       // closed by kind
 *   failurePolicy: FailurePolicyId,
 *   contract: OperationContractSnapshot
 * }
 * }</pre>
 *
 * <p>Operands and operand types are ordered left-to-right and complete
 * before operation START. A structured op runs its declared child ops in
 * payload order after START; nested child ops record the enclosing op as
 * {@code parentOpId} in their origin. A started operation ends exactly
 * once; mutation commits immediately before SUCCESS and a local failure
 * commits no local mutation.</p>
 *
 * <p>Construction enforces the closed typed surface: the payload must be
 * an instance of {@link SemanticOpKind#payloadClass()} and the
 * {@code contract} snapshot must be wired from the op's own fields
 * ({@code opKind}, {@code payload}, {@code resultType},
 * {@code operandTypes}, {@code failurePolicy}). The digest itself is
 * opaque here — computed by the canonicalizer (T3) and checked by the
 * validator (R-DIGEST, T6).</p>
 *
 * @param opId          the globally unique operation identity carrying its module; non-null
 * @param kind          the closed operation kind; non-null
 * @param origin        the source origin; non-null
 * @param result        the result slot ({@link ValueId} or {@link AsyncTokenId}), or {@code null} for none
 * @param resultType    the result type (descriptor or internal sentinel), or {@code null} for none
 * @param operands      the completed operand values in left-to-right order; non-null
 * @param operandTypes  the operand descriptors in left-to-right order (same length as operands); non-null
 * @param payload       the closed kind payload; non-null
 * @param failurePolicy the closed failure policy; non-null
 * @param contract      the wired operation-contract snapshot; non-null
 */
public record SemanticOp(
    OpId opId,
    SemanticOpKind kind,
    SourceOrigin origin,
    SemanticValue result,
    OpResultType resultType,
    List<ValueId> operands,
    List<RuntimeDescriptor> operandTypes,
    KindPayload payload,
    FailurePolicyId failurePolicy,
    OperationContractSnapshot contract
) {

    public SemanticOp(OpId opId, SemanticOpKind kind, SourceOrigin origin, SemanticValue result,
                      OpResultType resultType, List<ValueId> operands,
                      List<RuntimeDescriptor> operandTypes, KindPayload payload,
                      FailurePolicyId failurePolicy, OperationContractSnapshot contract) {
        this.opId = Objects.requireNonNull(opId, "opId must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.origin = Objects.requireNonNull(origin, "origin must not be null");
        this.result = result;
        this.resultType = resultType;
        this.operands = List.copyOf(operands);
        this.operandTypes = List.copyOf(operandTypes);
        if (this.operands.size() != this.operandTypes.size()) {
            throw new IllegalArgumentException(
                "operands (" + this.operands.size() + ") and operandTypes ("
                    + this.operandTypes.size() + ") must have the same length");
        }
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        this.contract = Objects.requireNonNull(contract, "contract must not be null");

        if (!kind.payloadClass().isInstance(payload)) {
            throw new IllegalArgumentException(
                "kind " + kind + " requires payload shape " + kind.payloadClass().getSimpleName()
                    + ", got " + payload.getClass().getSimpleName());
        }
        if (contract.opKind() != kind) {
            throw new IllegalArgumentException(
                "contract opKind " + contract.opKind() + " must equal op kind " + kind);
        }
        if (contract.payload() != payload) {
            throw new IllegalArgumentException(
                "contract payload must be the op's own payload instance");
        }
        if (contract.resultType() != resultType) {
            throw new IllegalArgumentException(
                "contract resultType must equal the op's resultType");
        }
        if (!contract.operandTypes().equals(this.operandTypes)) {
            throw new IllegalArgumentException(
                "contract operandTypes must equal the op's operandTypes");
        }
        if (contract.failurePolicy() != failurePolicy) {
            throw new IllegalArgumentException(
                "contract failurePolicy must equal the op's failurePolicy");
        }
    }
}
