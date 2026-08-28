package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The operation-contract snapshot attached to every {@link SemanticOp}
 * (parent D12; schema S2):
 *
 * <pre>{@code
 * OperationContractSnapshot {
 *   version: 1,
 *   opKind, resultType, operandTypes,
 *   selector?,                  // exact closed selector
 *   payload,                    // canonical behavior fields
 *   failurePolicy,
 *   referencedSemanticIds,      // class/function/binding/block/module IDs
 *   canonicalDigest             // SHA-256 of the canonical JSON of the fields above
 * }
 * }</pre>
 *
 * <p>Canonical JSON rules (S2): UTF-8; sorted object keys; decimal
 * integers for signed32; IEEE-754 hex floats for numbers; explicit nulls;
 * ordered arrays. {@code canonicalDigest = SHA-256(canonical JSON bytes)}
 * over exactly {@code {version, opKind, resultType, operandTypes,
 * selector?, payload, failurePolicy, referencedSemanticIds}} — behavior-
 * referenced IDs are inside the digest, while {@code opId}, source
 * coordinates, and trace phase are outside. Exactly one
 * {@code ContractSnapshotCanonicalizer} implementation serves the
 * validator, dumper, and trace harness; its digest computation is T3's —
 * this record defines the fields and the contract wiring only. A snapshot
 * whose recomputed digest differs from {@code canonicalDigest} fails
 * R-DIGEST (validator, T6).</p>
 *
 * <p>{@code version} is a first-class field (version strings are
 * first-class schema fields); it must equal {@link #VERSION}.</p>
 *
 * @param version               the snapshot format version; exactly {@link #VERSION}
 * @param opKind                the closed operation kind; non-null
 * @param resultType            the op result type (descriptor, internal
 *                              sentinel, or {@code null} for none)
 * @param operandTypes          the operand descriptors in left-to-right order; non-null
 * @param selector              the exact closed selector, or {@code null}
 * @param payload               the closed kind payload; non-null
 * @param failurePolicy         the closed failure policy; non-null
 * @param referencedSemanticIds the behavior-referenced semantic IDs
 *                              (class/function/binding/block/module IDs); non-null
 * @param canonicalDigest       the SHA-256 canonical-JSON digest of the
 *                              fields above (computed by T3's canonicalizer)
 */
public record OperationContractSnapshot(
    int version,
    SemanticOpKind opKind,
    OpResultType resultType,
    List<RuntimeDescriptor> operandTypes,
    ClosedSelector selector,
    KindPayload payload,
    FailurePolicyId failurePolicy,
    List<SemanticId> referencedSemanticIds,
    String canonicalDigest
) {

    /** The only admissible snapshot version of {@code deal.semantic-ir/1}. */
    public static final int VERSION = 1;

    public OperationContractSnapshot(int version, SemanticOpKind opKind, OpResultType resultType,
                                     List<RuntimeDescriptor> operandTypes, ClosedSelector selector,
                                     KindPayload payload, FailurePolicyId failurePolicy,
                                     List<SemanticId> referencedSemanticIds, String canonicalDigest) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                "version must be " + VERSION + " in deal.semantic-ir/1, got " + version);
        }
        this.version = version;
        this.opKind = Objects.requireNonNull(opKind, "opKind must not be null");
        this.resultType = resultType;
        this.operandTypes = List.copyOf(operandTypes);
        this.selector = selector;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        this.referencedSemanticIds = List.copyOf(referencedSemanticIds);
        this.canonicalDigest = Objects.requireNonNull(canonicalDigest, "canonicalDigest must not be null");
    }

    /**
     * The contract wiring: derive this op's snapshot from the op's own
     * fields. {@code opKind}, {@code resultType}, {@code operandTypes},
     * {@code payload}, and {@code failurePolicy} are copied verbatim; the
     * {@code selector} is extracted from a selector-carrying payload
     * ({@code UNARY}/{@code BINARY}/{@code STDLIB_CALL}) and is
     * {@code null} otherwise. {@link SemanticOp} enforces this wiring at
     * construction, so an op can never carry a snapshot that disagrees
     * with its own fields.
     *
     * @param op                    the op whose fields the snapshot mirrors; non-null
     * @param referencedSemanticIds the behavior-referenced semantic IDs; non-null
     * @param canonicalDigest       the digest computed by the canonicalizer; non-null
     * @return the wired snapshot
     */
    public static OperationContractSnapshot of(SemanticOp op, List<SemanticId> referencedSemanticIds,
                                               String canonicalDigest) {
        Objects.requireNonNull(op, "op must not be null");
        ClosedSelector selector = op.payload() instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector()
            : null;
        return new OperationContractSnapshot(VERSION, op.kind(), op.resultType(), op.operandTypes(),
            selector, op.payload(), op.failurePolicy(), referencedSemanticIds, canonicalDigest);
    }
}
