package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * One operation of the validator's raw intermediate model (schema S2/S6).
 * Enum positions (kind, policies, selectors, payload leaves) carry raw
 * strings; descriptors carry raw canonical texts; {@code payload} is the
 * raw canonical JSON object of the closed kind shape (the pinned
 * {@link ContractSnapshotCanonicalizer#payloadJson} mapping on the typed
 * surface and the parsed payload object on the text surface);
 * {@code snapshot} is the raw 8-field operation-contract object without
 * {@code canonicalDigest}, which is carried separately.
 *
 * @param opId          the operation identity; non-null
 * @param kind          the raw operation-kind name; non-null
 * @param failurePolicy the raw failure-policy name; non-null
 * @param parentOpId    the enclosing/triggering op identity, or {@code null}
 * @param resultValue   the result value identity, or {@code null}
 * @param resultToken   the result token identity, or {@code null}
 * @param resultType    the raw result-type text (descriptor text or
 *                      internal sentinel name), or {@code null} for none
 * @param operands      the completed operand value identities in order; non-null
 * @param operandTypes  the raw operand descriptor texts in order; non-null
 * @param payload       the raw payload object; non-null
 * @param selector      the raw snapshot selector name, or {@code null}
 * @param canonicalDigest the carried contract digest; non-null
 * @param snapshot      the raw 8-field contract object (no canonicalDigest); non-null
 */
public record RawOp(
    OpId opId,
    String kind,
    String failurePolicy,
    OpId parentOpId,
    ValueId resultValue,
    AsyncTokenId resultToken,
    String resultType,
    List<ValueId> operands,
    List<String> operandTypes,
    CanonicalJson.Obj payload,
    String selector,
    String canonicalDigest,
    CanonicalJson.Obj snapshot
) {

    public RawOp {
        Objects.requireNonNull(opId, "opId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands must not be null"));
        operandTypes = List.copyOf(Objects.requireNonNull(operandTypes, "operandTypes must not be null"));
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(canonicalDigest, "canonicalDigest must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
