package deal.semantic.ir;

import java.util.Objects;

/**
 * The pinned lowering-context digest helper of {@code deal.semantic-ir/1}
 * (schema S1): the single production implementation of the formula
 *
 * <pre>{@code loweringContextHash = SHA-256(canonical JSON {semanticProfile, capabilityRegistryHash})}</pre>
 *
 * computed at lowering start by the unit's producer over the invocation's
 * comparison facts and recomputed by the validator's R-PROFILE rule (T6).
 * The canonical JSON object has exactly the two keys in sorted order —
 * {@code capabilityRegistryHash} then {@code semanticProfile} — with the
 * profile serialized as its closed enum name (a
 * {@code LEGACY_SAFE_INT}-named input can never be confused with a
 * {@code DEAL_V1_2_INT32} one: the enum names differ and so do the
 * digests) and the registry hash carried verbatim as its hex string. The
 * digest flows through the single {@link CanonicalJson} facility (S2,
 * F8) — no component serializes canonical JSON for itself.
 *
 * <p>Reusable by synthetic unit builders (this epic), the validator's
 * R-PROFILE recomputation (T6), and the later lowering epics
 * (ISSUE-0231..0239), which compute it per this pinned formula at lowering
 * start.</p>
 */
public final class LoweringContextHash {

    private LoweringContextHash() {
        // Static utility; no instances.
    }

    /**
     * Computes the pinned lowering-context digest.
     *
     * @param semanticProfile        the invocation's semantic profile; non-null
     * @param capabilityRegistryHash the invocation's closed capability-registry
     *                               digest (hex string, carried verbatim); non-null
     * @return the lowercase 64-character hex digest
     */
    public static String of(SemanticProfile semanticProfile, String capabilityRegistryHash) {
        Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        CanonicalJson.Value json = CanonicalJson.obj(
            CanonicalJson.e("capabilityRegistryHash", CanonicalJson.str(capabilityRegistryHash)),
            CanonicalJson.e("semanticProfile", CanonicalJson.str(semanticProfile.name())));
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(json));
    }

    /**
     * Computes the pinned lowering-context digest from the schema-pinned
     * input record.
     *
     * @param context the digest input; non-null
     * @return the lowercase 64-character hex digest
     */
    public static String of(LoweringContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return of(context.semanticProfile(), context.capabilityRegistryHash());
    }
}
