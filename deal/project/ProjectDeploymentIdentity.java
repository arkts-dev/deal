package deal.project;

import java.util.Objects;

/**
 * The private deployment identity of one validated v1.2 project (design
 * source {@code strict-project-context-resolution-identity} D4,
 * {@code deal-v1.2-int32-and-bytes-architecture} D5).
 *
 * <p>Derived by {@link ProjectLocator} only after the manifest read,
 * strict UTF-8 decode, strict parse, protected conversions, and output
 * resolution all succeeded, from protected-resolved inputs only:</p>
 *
 * <ul>
 *   <li>{@code canonicalManifestUri} — the normalized, absolute,
 *       symlink-resolved {@code file:} URI of the manifest, obtained via
 *       {@link ProtectedPathOps#toFileUri(Path)} after protected
 *       validation and successful resolution. Equivalent spellings of one
 *       manifest — including symlinked spellings — yield one URI.</li>
 *   <li>{@code validatedManifestContentDigest} — SHA-256 over the
 *       <b>exact</b> manifest file bytes as read for parsing (the same
 *       bytes that passed the D1 step-3 strict decode), rendered as 64
 *       lowercase hex characters. Any manifest content change — including
 *       whitespace — changes the digest and thereby every derived
 *       semantic identity, preventing unsafe cache replay.</li>
 * </ul>
 *
 * <p><b>Privacy invariant (D4):</b> these values are compiler-internal
 * and must never appear in runtime descriptors, diagnostic type names,
 * public export keys, or source-language values. They are inputs to
 * compiler plans, graph edges, resolved-call/resource identities,
 * semantic digests, and the private {@code deploymentModuleId}; derived
 * digests serialize string inputs with
 * {@link ProtectedPathOps#lengthPrefixedUtf8(String)}.</p>
 *
 * <p>Deterministic: identical manifest bytes and filesystem state produce
 * identical values; no timestamp, ordinal, address, or process state
 * enters either field.</p>
 *
 * @param canonicalManifestUri           the symlink-resolved {@code file:}
 *                                       URI text of the validated manifest
 * @param validatedManifestContentDigest SHA-256 of the exact manifest
 *                                       bytes, 64 lowercase hex chars
 */
public record ProjectDeploymentIdentity(String canonicalManifestUri,
                                        String validatedManifestContentDigest) {

    public ProjectDeploymentIdentity {
        Objects.requireNonNull(canonicalManifestUri, "canonicalManifestUri");
        Objects.requireNonNull(validatedManifestContentDigest,
            "validatedManifestContentDigest");
    }
}
