package deal.semantic.ir;

import java.util.Objects;

public final class LoweringContextHash {

    private LoweringContextHash() {
        // Static utility; no instances.
    }

    /**
     * Computes the pinned lowering-context digest.
     *
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
     */
    public static String of(LoweringContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return of(context.semanticProfile(), context.capabilityRegistryHash());
    }
}
