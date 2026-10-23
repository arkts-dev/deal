package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.util.Objects;

public final class CompilerProfileProvider {

    private CompilerProfileProvider() {
        // Static factory surface only; no instances.
    }

    /**
     * The sole invocation-resolution entry: {@code PUBLIC_BUILD} semantics
     * with the single {@link SemanticProfile#DEAL_V1_4_BINARY64} profile, the
     * release-owned state, the recorded registry digest, and the derived
     * release-state hash. This is the entry point {@code deal/Main.java}
     * and the harnesses use; no caller can select a profile, a purpose or
     * an alternate semantic arm.
     */
    public static CompilerInvocation resolve(ReleaseState releaseState,
                                             CapabilityRegistry registry) {
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        return construct(SemanticProfile.DEAL_V1_4_BINARY64, releaseState, registry);
    }

    /**
     * The single construction path: builds the immutable invocation with
     * the recorded registry digest and the derived release-state hash.
     */
    private static CompilerInvocation construct(SemanticProfile profile,
                                                ReleaseState releaseState,
                                                CapabilityRegistry registry) {
        String registryHash = registry.capabilityRegistryHash();
        return new CompilerInvocation(profile, releaseState,
            registryHash, deriveReleaseStateHash(releaseState, registryHash));
    }

    /**
     * The pinned release-state-hash derivation (F1):
     * {@code SHA-256(canonical JSON {releaseState,
     * capabilityRegistryHash})} over the single canonical JSON facility
     * (S2). The canonical object keys serialize in sorted order —
     * {@code capabilityRegistryHash} before {@code releaseState} — with
     * the release state rendered as its closed enum name and the registry
     * hash carried verbatim as its hex string. Computed at invocation
     * resolution and recorded on the invocation; never a selectable
     * input.
     *
     */
    public static String deriveReleaseStateHash(ReleaseState releaseState,
                                                String capabilityRegistryHash) {
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(capabilityRegistryHash,
            "capabilityRegistryHash must not be null");
        CanonicalJson.Value json = CanonicalJson.obj(
            CanonicalJson.e("capabilityRegistryHash",
                CanonicalJson.str(capabilityRegistryHash)),
            CanonicalJson.e("releaseState", CanonicalJson.str(releaseState.name())));
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(json));
    }
}
