package deal.module;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

/**
 * The provisional runtime-resource occurrence record of the default
 * planning epic (ISSUE-0541, design source
 * {@code provider-versioned-default-plans} D4; the task-pinned
 * provisional shape): one imported runtime resource referenced by a
 * planned default, with everything the downstream epics need except the
 * provider digest.
 *
 * <pre>
 * DefaultResourceOccurrence(kind,
 *   fromSemanticModuleIdentity, toSemanticModuleIdentity,
 *   importAlias, semanticResourceIdentity, sourceRange)
 * </pre>
 *
 * <ul>
 *   <li>{@code kind} — {@link RuntimeResourceReference.Kind#IMPORTED_FUNCTION_WRAPPER}
 *       for a call whose callee resolves to a function declared in an
 *       imported module (call-site range) or an imported function
 *       referenced as a first-class value (member-access range), and
 *       {@link RuntimeResourceReference.Kind#IMPORTED_CLASS_DEFAULT_PLAN}
 *       for a contextual class literal typed as a plan-bearing class
 *       declared in an imported module (literal range).</li>
 *   <li>{@code fromSemanticModuleIdentity} — the consuming (importing)
 *       module's private semantic identity.</li>
 *   <li>{@code toSemanticModuleIdentity} — the provider (imported)
 *       module's private semantic identity.</li>
 *   <li>{@code importAlias} — the importer-local import alias spelling
 *       of the first occurrence.</li>
 *   <li>{@code semanticResourceIdentity} — the resolved imported
 *       resource's private semantic identity.</li>
 *   <li>{@code sourceRange} — the first occurrence's complete scalar
 *       reference range.</li>
 * </ul>
 *
 * <p><b>Boundary (task-pinned):</b> this record deliberately carries no
 * digest component. {@link RuntimeResourceReference} and
 * {@link RuntimeImportDependency} require a non-null provider digest, so
 * this epic constructs neither: the serializer epic (ISSUE-0542)
 * computes the provider digests while producing canonical content, and
 * the graph epic (ISSUE-0543) constructs the final digest-bearing
 * {@code RuntimeImportDependency} records from these occurrences.
 * Reusing the {@link RuntimeResourceReference.Kind} enum imports the
 * closed kind set without importing the digest-bearing carrier.</p>
 *
 * <p><b>Ordering and deduplication (producer obligations, documented
 * here):</b> the planning epics emit occurrences ordered by first
 * occurrence in the typed evaluator IR walk, deduplicated by semantic
 * resource identity with the first occurrence kept — the same ordering
 * rules the parent shapes pin for {@code runtimeResources} and
 * {@code runtimeDependencies}.</p>
 *
 * <p>Immutable and deterministic; identities are compiler-internal and
 * never appear in runtime descriptors, diagnostic type names, public
 * export keys, or source-language values.</p>
 *
 * @param kind                       the reference kind
 * @param fromSemanticModuleIdentity the consuming module's private
 *                                   semantic identity
 * @param toSemanticModuleIdentity   the provider module's private
 *                                   semantic identity
 * @param importAlias                the importer-local alias spelling
 * @param semanticResourceIdentity   the imported resource's private
 *                                   semantic identity
 * @param sourceRange                the first occurrence's complete
 *                                   scalar reference range
 */
public record DefaultResourceOccurrence(
    RuntimeResourceReference.Kind kind,
    SemanticModuleIdentity fromSemanticModuleIdentity,
    SemanticModuleIdentity toSemanticModuleIdentity,
    String importAlias,
    SemanticResourceIdentity semanticResourceIdentity,
    DiagnosticRange sourceRange
) {

    public DefaultResourceOccurrence {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fromSemanticModuleIdentity,
            "fromSemanticModuleIdentity");
        Objects.requireNonNull(toSemanticModuleIdentity,
            "toSemanticModuleIdentity");
        Objects.requireNonNull(importAlias, "importAlias");
        Objects.requireNonNull(semanticResourceIdentity,
            "semanticResourceIdentity");
        Objects.requireNonNull(sourceRange, "sourceRange");
    }
}
