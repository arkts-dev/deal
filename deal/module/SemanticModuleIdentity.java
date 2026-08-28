package deal.module;

import deal.project.ProjectDeploymentIdentity;

import java.util.Objects;

/**
 * The private semantic identity of one successfully resolved source
 * module (design source
 * {@code strict-project-context-resolution-identity} D4/D5,
 * {@code deal-v1.2-int32-and-bytes-architecture} D5):
 * {@code SemanticModuleIdentity(projectDeploymentIdentity,
 * canonicalResolvedSourceUri)}.
 *
 * <ul>
 *   <li>{@code projectDeploymentIdentity} — the private deployment
 *       identity of the validated project (canonical symlink-resolved
 *       manifest URI + SHA-256 over the exact manifest bytes). Any
 *       manifest content change — including whitespace — changes the
 *       deployment identity and thereby every derived semantic
 *       identity, preventing unsafe cache replay.</li>
 *   <li>{@code canonicalResolvedSourceUri} — the normalized, absolute,
 *       symlink-resolved {@code file:} URI of the resolved source,
 *       obtained only after protected validation and successful
 *       resolution. Equivalent spellings of one file — including
 *       symlinked spellings — yield one URI and therefore one semantic
 *       identity.</li>
 * </ul>
 *
 * <p><b>Privacy invariant (D4/D5):</b> these values are compiler-internal
 * and must never appear in runtime descriptors, diagnostic type names,
 * public export keys, or source-language values. They are inputs to
 * compiler plans, graph edges, resolved-call/resource identities, and
 * semantic digests only. A source relocation intentionally changes the
 * URI (and therefore the identity); deterministic inputs reproduce
 * equal values.</p>
 *
 * @param projectDeploymentIdentity the private deployment identity of the
 *                                  validated project
 * @param canonicalResolvedSourceUri the protected-resolved {@code file:}
 *                                   URI of the source module
 */
public record SemanticModuleIdentity(ProjectDeploymentIdentity projectDeploymentIdentity,
                                     String canonicalResolvedSourceUri) {

    public SemanticModuleIdentity {
        Objects.requireNonNull(projectDeploymentIdentity, "projectDeploymentIdentity");
        Objects.requireNonNull(canonicalResolvedSourceUri, "canonicalResolvedSourceUri");
    }
}
