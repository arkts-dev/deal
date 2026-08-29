package deal.module;

import java.util.Objects;

/**
 * The parent-pinned semantic resource identity shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5):
 * {@code SemanticResourceIdentity(semanticModuleIdentity, resourceKind,
 * lexicalDeclarationIdentity)} — the private identity of one function or
 * class resource inside one resolved source module.
 *
 * <ul>
 *   <li>{@code semanticModuleIdentity} — the module's private semantic
 *       identity (the deployment identity plus the canonical resolved
 *       source URI).</li>
 *   <li>{@code resourceKind} — {@link
 *       LexicalDeclarationIdentity.DeclarationKind#FUNCTION} or
 *       {@code CLASS}, matching the lexical declaration's kind.</li>
 *   <li>{@code lexicalDeclarationIdentity} — the declaration's lexical
 *       identity (kind, name, enclosing lexical declaration path, and
 *       complete source scalar range).</li>
 * </ul>
 *
 * <p>Immutable and deterministic; compiler-internal only — never
 * descriptor text, a diagnostic type name, an export key, or a
 * source-language value.</p>
 *
 * @param semanticModuleIdentity     the private module identity
 * @param resourceKind               the resource kind
 * @param lexicalDeclarationIdentity the lexical declaration identity
 */
public record SemanticResourceIdentity(
    SemanticModuleIdentity semanticModuleIdentity,
    LexicalDeclarationIdentity.DeclarationKind resourceKind,
    LexicalDeclarationIdentity lexicalDeclarationIdentity
) {

    public SemanticResourceIdentity {
        Objects.requireNonNull(semanticModuleIdentity, "semanticModuleIdentity");
        Objects.requireNonNull(resourceKind, "resourceKind");
        Objects.requireNonNull(lexicalDeclarationIdentity, "lexicalDeclarationIdentity");
    }
}
