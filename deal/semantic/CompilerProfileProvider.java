package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.util.Objects;

/**
 * The single invocation-resolution component of the common lowering
 * foundation (F1/F8): owns the release-state → public-profile
 * derivation, the closed purpose/profile guard, and the derived
 * {@code releaseStateHash} recorded on the invocation. This class is the
 * only place {@link CompilerInvocation} records are constructed —
 * {@code deal/Main.java} resolves the public build through it, and the
 * two internal purposes exist only as harness factories, never as CLI
 * flags or source pragmas.
 *
 * <p>Resolution contract (F1, exact):</p>
 * <ul>
 *   <li>{@code PUBLIC_BUILD} — the profile derives from the release state:
 *       {@code PRE_ACTIVATION → LEGACY_SAFE_INT},
 *       {@code V1_2_ACTIVE → DEAL_V1_2_INT32}.</li>
 *   <li>{@code COMMON_SHADOW} — requires {@code DEAL_V1_2_INT32}
 *       (release state {@code V1_2_ACTIVE}); anything else is rejected at
 *       invocation resolution, before checking or lowering.</li>
 *   <li>{@code LEGACY_REGRESSION} — requires {@code LEGACY_SAFE_INT}
 *       (release state {@code PRE_ACTIVATION}); anything else is rejected
 *       at invocation resolution, before checking or lowering.</li>
 * </ul>
 *
 * <p>Profile mixing is impossible by construction: every invocation
 * carries exactly one immutable project-wide {@link SemanticProfile},
 * and the provider derives and records it in a single pass.
 * {@code LEGACY_SAFE_INT} is inspectable for routing/regression but is
 * never lowered; lowering admits only {@code DEAL_V1_2_INT32}.</p>
 */
public final class CompilerProfileProvider {

    private CompilerProfileProvider() {
        // Static factory surface only; no instances.
    }

    /**
     * The release-state → public-profile derivation (F1):
     * {@code PRE_ACTIVATION → LEGACY_SAFE_INT},
     * {@code V1_2_ACTIVE → DEAL_V1_2_INT32}.
     *
     * @param releaseState the release-owned release state; non-null
     * @return the derived public semantic profile
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
     * This is the entry point {@code deal/Main.java} uses; the public
     * CLI gains no profile or purpose surface.
     *
     * @param releaseState the release-owned release state; non-null
     * @param registry     the release-owned capability registry; non-null
     * @return the immutable invocation record
     */
    public static CompilerInvocation resolve(ReleaseState releaseState,
                                             CapabilityRegistry registry) {
        return resolve(InvocationPurpose.PUBLIC_BUILD, releaseState, registry);
    }

    /**
     * The internal harness factory for shadow common builds (F1):
     * {@code COMMON_SHADOW} requires {@code DEAL_V1_2_INT32}, so only
     * release state {@code V1_2_ACTIVE} resolves; anything else is
     * rejected before checking or lowering. Never a CLI flag or source
     * pragma.
     *
     * @param releaseState the release-owned release state; non-null
     * @param registry     the release-owned capability registry; non-null
     * @return the immutable invocation record
     * @throws IllegalArgumentException when the derived profile is not
     *         {@code DEAL_V1_2_INT32}
     */
    public static CompilerInvocation resolveCommonShadow(ReleaseState releaseState,
                                                         CapabilityRegistry registry) {
        return resolve(InvocationPurpose.COMMON_SHADOW, releaseState, registry);
    }

    /**
     * The internal harness factory for legacy-profile regression builds
     * (F1): {@code LEGACY_REGRESSION} requires {@code LEGACY_SAFE_INT},
     * so only release state {@code PRE_ACTIVATION} resolves; anything
     * else is rejected before checking or lowering. Never a CLI flag or
     * source pragma.
     *
     * @param releaseState the release-owned release state; non-null
     * @param registry     the release-owned capability registry; non-null
     * @return the immutable invocation record
     * @throws IllegalArgumentException when the derived profile is not
     *         {@code LEGACY_SAFE_INT}
     */
    public static CompilerInvocation resolveLegacyRegression(ReleaseState releaseState,
                                                             CapabilityRegistry registry) {
        return resolve(InvocationPurpose.LEGACY_REGRESSION, releaseState, registry);
    }

    /**
     * The full purpose × release-state resolution matrix: derives the
     * profile from the release state, applies the closed purpose guard,
     * and constructs the immutable invocation with the derived
     * release-state hash recorded.
     *
     * @param purpose      the invocation purpose; non-null
     * @param releaseState the release-owned release state; non-null
     * @param registry     the release-owned capability registry; non-null
     * @return the immutable invocation record
     * @throws IllegalArgumentException for a purpose/profile mismatch —
     *         rejected at invocation resolution, before checking or
     *         lowering (never a planner condition, never an E6005)
     */
    public static CompilerInvocation resolve(InvocationPurpose purpose,
                                             ReleaseState releaseState,
                                             CapabilityRegistry registry) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        SemanticProfile profile = publicProfile(releaseState);
        switch (purpose) {
            case PUBLIC_BUILD -> {
                // The derived public profile is always admitted: the
                // release state owns the public profile (F1).
            }
            case COMMON_SHADOW -> {
                if (profile != SemanticProfile.DEAL_V1_2_INT32) {
                    throw new IllegalArgumentException(
                        "COMMON_SHADOW requires DEAL_V1_2_INT32; release state "
                            + releaseState + " derives " + profile);
                }
            }
            case LEGACY_REGRESSION -> {
                if (profile != SemanticProfile.LEGACY_SAFE_INT) {
                    throw new IllegalArgumentException(
                        "LEGACY_REGRESSION requires LEGACY_SAFE_INT; release state "
                            + releaseState + " derives " + profile);
                }
            }
        }
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
     * @param releaseState           the release state; non-null
     * @param capabilityRegistryHash the closed capability-registry digest
     *                               (F7); non-null
     * @return the lowercase 64-character hex digest
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
