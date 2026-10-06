package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.util.Objects;

public final class CompilerProfileProvider {

    private CompilerProfileProvider() {
        // Static factory surface only; no instances.
    }

    /**
     * The release-state → public-profile derivation (F1):
     * {@code PRE_ACTIVATION → LEGACY_SAFE_INT},
     * {@code V1_2_ACTIVE → DEAL_V1_2_INT32}.
     *
     */
    public static SemanticProfile publicProfile(ReleaseState releaseState) {
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        return switch (releaseState) {
            case PRE_ACTIVATION -> SemanticProfile.LEGACY_SAFE_INT;
            case V1_2_ACTIVE -> SemanticProfile.DEAL_V1_2_INT32;
        };
    }

    /**
     * Resolves the release-owned public-build invocation: purpose
     * {@code PUBLIC_BUILD} with the profile derived from the release
     * state and the derived release-state hash recorded on the record.
     * This is the only {@code PUBLIC_BUILD} constructor and the entry
     * point {@code deal/Main.java} uses; the public CLI gains no profile
     * or purpose surface.
     *
     */
    public static CompilerInvocation resolve(ReleaseState releaseState,
                                             CapabilityRegistry registry) {
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        return construct(InvocationPurpose.PUBLIC_BUILD, publicProfile(releaseState),
            releaseState, registry);
    }

    /**
     * The internal harness factory for shadow common builds (A1):
     * {@code COMMON_SHADOW} requires the explicitly passed profile
     * {@code DEAL_V1_2_INT32}; any release state resolves (pre- and
     * post-activation shadowing — the E11 pre-activation common-closure
     * evidence path). Never a CLI flag or source pragma.
     *
     */
    public static CompilerInvocation resolveCommonShadow(SemanticProfile profile,
                                                         ReleaseState releaseState,
                                                         CapabilityRegistry registry) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        if (profile != SemanticProfile.DEAL_V1_2_INT32) {
            throw new IllegalArgumentException(
                "COMMON_SHADOW requires DEAL_V1_2_INT32; got " + profile);
        }
        return construct(InvocationPurpose.COMMON_SHADOW, profile, releaseState,
            registry);
    }

    /**
     * The internal harness factory for legacy-profile regression builds
     * (A1): {@code LEGACY_REGRESSION} requires the explicitly passed
     * profile {@code LEGACY_SAFE_INT}; any release state resolves (the
     * retention window extends past activation). Never a CLI flag or
     * source pragma.
     *
     */
    public static CompilerInvocation resolveLegacyRegression(SemanticProfile profile,
                                                             ReleaseState releaseState,
                                                             CapabilityRegistry registry) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        if (profile != SemanticProfile.LEGACY_SAFE_INT) {
            throw new IllegalArgumentException(
                "LEGACY_REGRESSION requires LEGACY_SAFE_INT; got " + profile);
        }
        return construct(InvocationPurpose.LEGACY_REGRESSION, profile, releaseState,
            registry);
    }

    /**
     * The single construction path: builds the immutable invocation with
     * the recorded registry digest and the derived release-state hash.
     * The A1 matrix is re-enforced defensively by
     * {@link CompilerInvocation}'s compact constructor.
     */
    private static CompilerInvocation construct(InvocationPurpose purpose,
                                                SemanticProfile profile,
                                                ReleaseState releaseState,
                                                CapabilityRegistry registry) {
        String registryHash = registry.capabilityRegistryHash();
        return new CompilerInvocation(purpose, profile, releaseState,
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
