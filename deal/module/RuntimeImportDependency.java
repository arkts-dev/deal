package deal.module;

import deal.diagnostics.DiagnosticRange;

import java.util.Objects;

/**
 * The shared dependency-edge carrier, created here in exactly the
 * declarations-page shape (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D3;
 * {@code provider-versioned-default-plans} D7; carrier-shape domain
 * {@code default-plan-carriers} D2/D9):
 *
 * <pre>
 * RuntimeImportDependency(fromSemanticModuleIdentity,
 *   toSemanticModuleIdentity, importAlias, semanticResourceIdentity,
 *   providerContractDigest, sourceRange, reason)
 * </pre>
 *
 * <ul>
 *   <li>{@code fromSemanticModuleIdentity} — the consuming (importing)
 *       module's private semantic identity.</li>
 *   <li>{@code toSemanticModuleIdentity} — the provider (imported)
 *       module's private semantic identity.</li>
 *   <li>{@code importAlias} — the import alias of the first occurrence
 *       (importer-local spelling).</li>
 *   <li>{@code semanticResourceIdentity} — the resolved imported
 *       resource's private semantic identity (an imported function
 *       wrapper or imported class default plan, matching the
 *       {@link RuntimeResourceReference.Kind}).</li>
 *   <li>{@code providerContractDigest} — the provider's contract digest
 *       (SHA-256 over the length-prefixed canonical provider content via
 *       {@link RuntimeResourceReference#providerContractDigestOf}).
 *       Non-null by construction: a missing digest cannot be expressed,
 *       so only digest-bearing records exist.</li>
 *   <li>{@code sourceRange} — the first occurrence's complete scalar
 *       reference range.</li>
 *   <li>{@code reason} — the closed two-value edge reason
 *       ({@link Reason}).</li>
 * </ul>
 *
 * <p><b>Reason set ({@code provider-versioned-default-plans} D7,
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D3):</b>
 * {@link Reason#RUNTIME_USE} for ordinary runtime-use edges of the
 * importing module's executable code, and
 * {@link Reason#DEFERRED_DEFAULT_BINDING} for the planner's deferred
 * default edges. Exactly these two values exist; the graph epic
 * (ISSUE-0543) merges both edge sets over this one carrier.</p>
 *
 * <p><b>Producer obligations, documented here and never validated
 * ({@code default-plan-carriers} D5):</b> one dependency per imported
 * resource identity, ordered by first occurrence in the typed evaluator
 * IR walk (default edges) or by first occurrence in the importing
 * module's executable code (ordinary edges), each carrying the first
 * occurrence's reference range. The graph epic (ISSUE-0543) is the
 * sole constructor of these records in the production pipeline and
 * builds them only for acyclic compilations after the provider digests
 * exist. The carrier itself performs no validation beyond null
 * rejection.</p>
 *
 * <p>Immutable and deterministic; identities and digests are
 * compiler-internal and never appear in runtime descriptors, diagnostic
 * type names, public export keys, or source-language values.</p>
 *
 * @param fromSemanticModuleIdentity the consuming module's private
 *                                   semantic identity
 * @param toSemanticModuleIdentity   the provider module's private
 *                                   semantic identity
 * @param importAlias                the import alias of the first
 *                                   occurrence
 * @param semanticResourceIdentity   the imported resource's private
 *                                   semantic identity
 * @param providerContractDigest     the provider's contract digest
 * @param sourceRange                the first occurrence's complete
 *                                   scalar reference range
 * @param reason                     the closed edge reason
 */
public record RuntimeImportDependency(
    SemanticModuleIdentity fromSemanticModuleIdentity,
    SemanticModuleIdentity toSemanticModuleIdentity,
    String importAlias,
    SemanticResourceIdentity semanticResourceIdentity,
    String providerContractDigest,
    DiagnosticRange sourceRange,
    Reason reason
) {

    public RuntimeImportDependency {
        Objects.requireNonNull(fromSemanticModuleIdentity,
            "fromSemanticModuleIdentity");
        Objects.requireNonNull(toSemanticModuleIdentity,
            "toSemanticModuleIdentity");
        Objects.requireNonNull(importAlias, "importAlias");
        Objects.requireNonNull(semanticResourceIdentity,
            "semanticResourceIdentity");
        Objects.requireNonNull(providerContractDigest,
            "providerContractDigest");
        Objects.requireNonNull(sourceRange, "sourceRange");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * The closed two-value edge reason set
     * ({@code provider-versioned-default-plans} D7): ordinary runtime
     * use and deferred default binding over the one dependency-edge
     * carrier. No other reason value exists.
     */
    public enum Reason {
        /** An ordinary runtime-use edge of executable importing code. */
        RUNTIME_USE,
        /** A planner edge from a deferred default evaluator binding. */
        DEFERRED_DEFAULT_BINDING
    }
}
