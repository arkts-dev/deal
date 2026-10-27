package deal.semantic;

import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.util.Objects;

/**
 * The release-owned compiler invocation record (foundation F1; parent
 * D2): one project-wide semantic profile — the sole
 * {@code DEAL_V1_5_BINARY64} — one release state, the closed
 * capability-registry digest, and the derived release-state hash.
 *
 * <p>The selectable inputs are {@code semanticProfile}, {@code releaseState}
 * and {@code capabilityRegistryHash}; the profile is the single closed
 * member and {@code releaseStateHash} is a derived, recorded field —
 * computed by {@link CompilerProfileProvider} at invocation resolution as
 * {@code SHA-256(canonical JSON {releaseState, capabilityRegistryHash})}
 * through the single canonical JSON facility — never a selectable input.
 * Source and the public CLI select neither profile, state, nor hash; the
 * compact constructor rejects any recorded value that does not equal the
 * recomputed derivation, so the field is derived and recorded on every
 * invocation, verbose or not. The record is immutable: every component is
 * final and there is no mutator.</p>
 *
 * <p>There is exactly one executable semantic path. The record carries no
 * purpose, no alternate profile and no profile-selecting route: every
 * compiler entrypoint lowers and emits the one v1.5 production semantics
 * for its selected backend.</p>
 */
public record CompilerInvocation(
    SemanticProfile semanticProfile,
    ReleaseState releaseState,
    String capabilityRegistryHash,
    String releaseStateHash) {

    /**
     * Enforces the immutability, derived-field, and single-profile
     * invariants: every component is non-null, the recorded
     * {@code releaseStateHash} equals the recomputed pinned derivation
     * {@code SHA-256(canonical JSON {releaseState, capabilityRegistryHash})},
     * and the profile is the single closed member.
     */
    public CompilerInvocation {
        Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        Objects.requireNonNull(releaseState, "releaseState must not be null");
        Objects.requireNonNull(capabilityRegistryHash, "capabilityRegistryHash must not be null");
        Objects.requireNonNull(releaseStateHash, "releaseStateHash must not be null");
        String recomputed = CompilerProfileProvider.deriveReleaseStateHash(
            releaseState, capabilityRegistryHash);
        if (!recomputed.equals(releaseStateHash)) {
            throw new IllegalArgumentException(
                "releaseStateHash must equal SHA-256(canonical JSON {releaseState, "
                    + "capabilityRegistryHash}): the hash is derived and recorded by "
                    + "CompilerProfileProvider at invocation resolution, never a "
                    + "selectable input");
        }
    }
}
