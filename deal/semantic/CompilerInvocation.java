package deal.semantic;

import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.util.Objects;

/**
 * The release-owned compiler invocation record (foundation F1; parent
 * D2): exactly one purpose, one project-wide semantic profile, one
 * release state, the closed capability-registry digest, and the derived
 * release-state hash.
 *
 * <p>The four selectable inputs are {@code purpose},
 * {@code semanticProfile}, {@code releaseState}, and
 * {@code capabilityRegistryHash}; {@code releaseStateHash} is a derived,
 * recorded field — computed by {@link CompilerProfileProvider} at
 * invocation resolution as {@code SHA-256(canonical JSON {releaseState,
 * capabilityRegistryHash})} through the single canonical JSON facility —
 * never a selectable input. Source and the public CLI cannot select
 * profile, purpose, or the hash; the compact constructor rejects any
 * recorded value that does not equal the recomputed derivation, so the
 * field is derived and recorded on every invocation, verbose or not.
 * The record is immutable: every component is final and there is no
 * mutator.</p>
 *
 * @param purpose               the invocation purpose; non-null
 * @param semanticProfile       the single project-wide profile; non-null
 * @param releaseState          the release-owned release state; non-null
 * @param capabilityRegistryHash the closed capability-registry digest
 *                              (foundation F7); non-null
 * @param releaseStateHash      the derived release-state hash recorded at
 *                              resolution (F1); non-null
 */
public record CompilerInvocation(
    InvocationPurpose purpose,
    SemanticProfile semanticProfile,
    ReleaseState releaseState,
    String capabilityRegistryHash,
    String releaseStateHash) {

    /**
     * Enforces the immutability and derived-field invariants: every
     * component is non-null and the recorded {@code releaseStateHash}
     * equals the recomputed pinned derivation
     * {@code SHA-256(canonical JSON {releaseState, capabilityRegistryHash})}.
     *
     * @throws IllegalArgumentException when a component is null or the
     *         recorded hash differs from the recomputed derivation (the
     *         hash is derived by {@link CompilerProfileProvider}, never
     *         selectable)
     */
    public CompilerInvocation {
        Objects.requireNonNull(purpose, "purpose must not be null");
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
