package deal.module;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The deterministic digest projections of the private semantic
 * identities for canonical plan content (the serializer epic
 * ISSUE-0542; the derivation mirrors the pinned FFI-domain
 * {@code semanticResourceIdentityDigest} rule,
 * {@code deal/ffi/FfiDeclarationValidator.java}): canonical content
 * that later flows into artifacts never carries raw deployment URIs —
 * identities participate through deterministic SHA-256 digests over
 * canonical length-separated (NUL-joined) components only.
 *
 * <p>Deterministic: no address, timestamp, ordinal, or process state
 * enters any input; identical identities always produce identical
 * digests.</p>
 */
final class SemanticIdentityContent {

    private SemanticIdentityContent() {
    }

    /**
     * SHA-256 (64 lowercase hex chars) over the canonical
     * NUL-separated serialization of one private semantic module
     * identity.
     */
    static String moduleIdentityDigest(SemanticModuleIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        String canonical = "SEMANTIC_MODULE\0"
            + identity.projectDeploymentIdentity().canonicalManifestUri()
            + "\0"
            + identity.projectDeploymentIdentity()
                .validatedManifestContentDigest() + "\0"
            + identity.canonicalResolvedSourceUri();
        return digestOf(canonical);
    }

    /**
     * SHA-256 (64 lowercase hex chars) over the canonical
     * NUL-separated serialization of one private semantic resource
     * identity (module deployment identity, canonical source URI,
     * declaration kind/name/path/range) — the exact component set of
     * the FFI-domain derivation.
     */
    static String resourceIdentityDigest(
            SemanticResourceIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        SemanticModuleIdentity module = identity.semanticModuleIdentity();
        String canonical = "SEMANTIC_RESOURCE\0"
            + module.projectDeploymentIdentity().canonicalManifestUri()
            + "\0"
            + module.projectDeploymentIdentity()
                .validatedManifestContentDigest() + "\0"
            + module.canonicalResolvedSourceUri() + "\0"
            + identity.resourceKind().name() + "\0"
            + identity.lexicalDeclarationIdentity().declaredName() + "\0"
            + identity.lexicalDeclarationIdentity()
                .enclosingLexicalDeclarationPath() + "\0"
            + identity.lexicalDeclarationIdentity().sourceScalarRange()
                .startScalarOffset() + "\0"
            + identity.lexicalDeclarationIdentity().sourceScalarRange()
                .endScalarOffset();
        return digestOf(canonical);
    }

    private static String digestOf(String canonical) {
        return IdentityDigests.sha256Hex(
            canonical.getBytes(StandardCharsets.UTF_8));
    }
}
