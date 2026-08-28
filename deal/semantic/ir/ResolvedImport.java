package deal.semantic.ir;

import java.util.Objects;

/**
 * One resolved import entry of {@code deal.semantic-interface/1}
 * (foundation F2): the import alias, the raw import specifier as written,
 * the resolved target's dotted module path, and the resolution target's
 * closed index kind. Carried by {@code CheckedModuleInput.imports} and
 * {@link ExternalModuleInterface#imports()} alike — STDLIB/HOST
 * declaration entries derive it in source order from the declaration
 * AST's import declarations resolved through the orchestrator's import
 * resolution ({@code resolveImportPath} plus the externals maps). Pure
 * immutable data of {@code deal.semantic-interface/1}.
 *
 * <p>{@code kind} carries the resolution target's kind across the closed
 * four-value set; under the current orchestrator classification only
 * {@code IMPLEMENTATION|STDLIB|HOST} are produced and the parent-pinned
 * {@code DECLARATION} value has no producer.</p>
 *
 * @param alias            the import alias bound at the importing module; non-null
 * @param modulePath       the raw import specifier as written; non-null
 * @param resolvedModuleId the resolved target's dotted module path; non-null
 * @param kind             the resolved target's index kind; non-null
 */
public record ResolvedImport(String alias, String modulePath, ModuleId resolvedModuleId,
                             ExternalModuleKind kind) {

    public ResolvedImport {
        Objects.requireNonNull(alias, "alias must not be null");
        Objects.requireNonNull(modulePath, "modulePath must not be null");
        Objects.requireNonNull(resolvedModuleId, "resolvedModuleId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }

    /** The canonical JSON object of this import entry (sorted keys). */
    CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("alias", CanonicalJson.str(alias)),
            CanonicalJson.e("modulePath", CanonicalJson.str(modulePath)),
            CanonicalJson.e("resolvedModuleId",
                ContractSnapshotCanonicalizer.semanticIdJson(resolvedModuleId)),
            CanonicalJson.e("kind", CanonicalJson.str(kind.name())));
    }
}
