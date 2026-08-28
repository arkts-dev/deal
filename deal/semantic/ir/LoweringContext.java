package deal.semantic.ir;

/**
 * The pinned lowering-context digest input domain of
 * {@code deal.semantic-ir/1} (schema S1): the exact input of the pinned
 * formula
 *
 * <pre>{@code loweringContextHash = SHA-256(canonical JSON {semanticProfile, capabilityRegistryHash})}</pre>
 *
 * computed at lowering start by the unit's producer over the invocation's
 * comparison facts. The canonical JSON object has exactly the two keys
 * {@code capabilityRegistryHash} and {@code semanticProfile} in sorted
 * order (canonical JSON rule S2); the digest binds each
 * {@link LoweredModuleUnit} to the invocation context that produced it,
 * and the validator recomputes it from its comparison facts and rejects a
 * mismatch (R-PROFILE). The canonical-JSON digest helper implementing the
 * formula lands with the canonicalizer (T3); this record pins the input
 * domain only.
 *
 * @param semanticProfile       the invocation's semantic profile; non-null
 * @param capabilityRegistryHash the invocation's closed capability-registry
 *                              digest (foundation F1/F7); non-null
 */
public record LoweringContext(SemanticProfile semanticProfile, String capabilityRegistryHash) {

    /**
     * The exact canonical-JSON object keys of the pinned digest input, in
     * sorted (canonical) order: {@code capabilityRegistryHash} then
     * {@code semanticProfile}.
     */
    public static final java.util.List<String> CANONICAL_JSON_KEYS =
        java.util.List.of("capabilityRegistryHash", "semanticProfile");

    public LoweringContext {
        java.util.Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        java.util.Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
    }
}
