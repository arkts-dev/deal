package deal.module;

import java.util.Objects;

/**
 * The parent-pinned resolved-source location shape (design source
 * {@code strict-project-context-resolution-identity} D5/D6,
 * {@code deal-v1.2-int32-and-bytes-architecture} D6):
 * {@code SourceModuleLocation(normalizedSourcePath,
 * semanticModuleIdentity, deploymentModuleId, projectIdentity?)} plus the
 * module-level classification per D6's precedence, recorded on the
 * location by {@link SourceModuleResolver} when it publishes the source.
 *
 * <ul>
 *   <li>{@code normalizedSourcePath} — the absolute normalized (lexical)
 *       path of the resolved source file.</li>
 *   <li>{@code semanticModuleIdentity} — the private
 *       {@link SemanticModuleIdentity} every successfully resolved source
 *       receives. Equivalent import spellings of one resolved source
 *       yield one identity (memoized by canonical URI).</li>
 *   <li>{@code deploymentModuleId} — the private artifact/import-wiring
 *       name: {@code "m"} + the first 16 lowercase hex chars of
 *       SHA-256(length-prefixed deployment digest ‖ length-prefixed
 *       canonical source URI). Opaque and byte-stable for unchanged
 *       inputs; never descriptor text or an export key.</li>
 *   <li>{@code projectIdentity} — the optional {@link
 *       ProjectModuleIdentity} from {@link ModuleIdentityResolver}'s
 *       pure classifier (most-specific configured-root containment), or
 *       {@code null} for a source with no project identity.</li>
 *   <li>{@code moduleClassification} — the module-level classification
 *       per D6's precedence recorded from the same single classifier
 *       invocation ({@code BuiltinModule} for the six pinned stdlib
 *       files, {@code ExternalModule(rawImportSpecifier)} for an
 *       externals entry's declaration file, {@code ProjectModule} for a
 *       rooted {@code .deal} source, {@code null} otherwise).
 *       {@code CanonicalClassIdentity} assembly stays eligibility-gated
 *       at consumer time (the assembly half of the resolver, a later
 *       epic item).</li>
 * </ul>
 *
 * <p><b>Privacy invariant:</b> {@code deploymentModuleId}, the identity
 * URIs, and the digests never appear in runtime descriptors, diagnostic
 * type names, public export keys, or source-language values; they are
 * compiler-internal consumers' inputs only (plans, graph edges, resource
 * identities, semantic digests, import wiring).</p>
 *
 * @param normalizedSourcePath    the absolute normalized (lexical) path
 * @param semanticModuleIdentity  the private semantic identity
 * @param deploymentModuleId      the private deployment module id
 * @param projectIdentity         the optional project module identity,
 *                                or {@code null}
 * @param moduleClassification    the module-level D6 classification, or
 *                                {@code null} when no public module
 *                                identity applies
 */
public record SourceModuleLocation(String normalizedSourcePath,
                                   SemanticModuleIdentity semanticModuleIdentity,
                                   String deploymentModuleId,
                                   ProjectModuleIdentity projectIdentity,
                                   CanonicalModuleIdentity moduleClassification) {

    public SourceModuleLocation {
        Objects.requireNonNull(normalizedSourcePath, "normalizedSourcePath");
        Objects.requireNonNull(semanticModuleIdentity, "semanticModuleIdentity");
        Objects.requireNonNull(deploymentModuleId, "deploymentModuleId");
        // projectIdentity and moduleClassification may be null: a source
        // without a public module identity is a normal, class-free value.
    }
}
