package deal.module;

import deal.diagnostics.DiagnosticRange;
import deal.project.ProtectedPathOps;

import java.util.Objects;

/**
 * The parent-pinned runtime resource reference shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5):
 * {@code RuntimeResourceReference(kind, semanticResourceIdentity,
 * providerContractDigest, sourceRange)} — the compiler-internal versioned
 * edge from a consuming site to an imported runtime resource.
 *
 * <ul>
 *   <li>{@code kind} — {@link Kind#IMPORTED_FUNCTION_WRAPPER} or
 *       {@link Kind#IMPORTED_CLASS_DEFAULT_PLAN}.</li>
 *   <li>{@code semanticResourceIdentity} — the provider resource's
 *       private semantic identity.</li>
 *   <li>{@code providerContractDigest} — SHA-256 over the
 *       length-prefixed canonical provider content via
 *       {@link #providerContractDigestOf(String)}; see the digest-domain
 *       note below.</li>
 *   <li>{@code sourceRange} — the consuming site's complete
 *       decoded-Unicode-scalar source range through the complete-range
 *       carrier.</li>
 * </ul>
 *
 * <p><b>Digest domain and determinism.</b> The digest domain is fixed
 * here: SHA-256 over {@link ProtectedPathOps#lengthPrefixedUtf8(String)}
 * of the canonical provider content (the shared length-prefixed UTF-8
 * serialization). No address, timestamp, ordinal, or process state ever
 * enters the input, so identical canonical content always produces the
 * identical digest, across calls and across runs. What counts as
 * behavior-bearing canonical provider content is owned by the
 * default-plan and resource-planner epics; this carrier pins the digest
 * derivation and its determinism only.</p>
 *
 * <p><b>Privacy invariant:</b> the carried identities and digests are
 * compiler-internal (plans, graph edges, resolved-call/resource
 * identities, semantic digests, import wiring) and never appear in
 * runtime descriptors, diagnostic type names, public export keys, or
 * source-language values.</p>
 *
 * @param kind                      the reference kind
 * @param semanticResourceIdentity  the provider resource identity
 * @param providerContractDigest    the provider contract digest (64
 *                                  lowercase hex chars)
 * @param sourceRange               the consuming site's complete source
 *                                  scalar range
 */
public record RuntimeResourceReference(Kind kind,
                                       SemanticResourceIdentity semanticResourceIdentity,
                                       String providerContractDigest,
                                       DiagnosticRange sourceRange) {

    /**
     * The pinned runtime resource reference kinds.
     */
    public enum Kind {
        /** An imported function's runtime wrapper. */
        IMPORTED_FUNCTION_WRAPPER,
        /** An imported class's default plan. */
        IMPORTED_CLASS_DEFAULT_PLAN
    }

    public RuntimeResourceReference {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(semanticResourceIdentity, "semanticResourceIdentity");
        Objects.requireNonNull(providerContractDigest, "providerContractDigest");
        Objects.requireNonNull(sourceRange, "sourceRange");
    }

    /**
     * The pinned deterministic provider-contract digest derivation:
     * SHA-256 (rendered as 64 lowercase hex chars) over the shared
     * length-prefixed UTF-8 serialization of the canonical provider
     * content. Identical content always yields the identical digest;
     * no address, timestamp, ordinal, or process state enters the input.
     *
     * <p>The input must be a Unicode scalar sequence (no unpaired UTF-16
     * surrogates): such an input is not valid canonical provider content
     * and is rejected as a programming error rather than silently
     * replacing a character.</p>
     *
     * @param canonicalProviderContent the canonical (serialized) provider
     *                                 content whose digest to compute
     * @return the 64-lowercase-hex-char SHA-256 digest
     */
    public static String providerContractDigestOf(String canonicalProviderContent) {
        Objects.requireNonNull(canonicalProviderContent, "canonicalProviderContent");
        ProtectedPathOps.ByteResult framed =
            ProtectedPathOps.lengthPrefixedUtf8(canonicalProviderContent);
        if (framed instanceof ProtectedPathOps.ByteResult.Success success) {
            return IdentityDigests.sha256Hex(success.bytes());
        }
        throw new IllegalArgumentException(
            "canonical provider content is not a Unicode scalar sequence");
    }
}
