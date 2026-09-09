package deal.module;

import deal.identity.CanonicalClassIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The parent-pinned compiler-side class default plan shape (design
 * source {@code deal-v1.2-int32-and-bytes-architecture} D5, adopted
 * verbatim; carrier-shape domain {@code default-plan-carriers} D2):
 *
 * <pre>
 * CompilerClassDefaultPlan(classIdentity, declaringSemanticModuleIdentity,
 *   orderedFields: List&lt;CompilerClassDefaultEntry&gt;,
 *   runtimeDependencies: ordered Set&lt;RuntimeImportDependency&gt;)
 * </pre>
 *
 * <ul>
 *   <li>{@code classIdentity} — the class's canonical public identity
 *       ({@code deal.identity.CanonicalClassIdentity}).</li>
 *   <li>{@code declaringSemanticModuleIdentity} — the declaring
 *       module's private semantic identity.</li>
 *   <li>{@code orderedFields} — the field entries in class source order
 *       with unique names; the list is insertion-ordered and
 *       unmodifiable, and duplicate names are rejected at
 *       construction.</li>
 *   <li>{@code runtimeDependencies} — the ordered imported runtime
 *       dependency edges, insertion-ordered and unmodifiable.</li>
 * </ul>
 *
 * <p><b>Producer obligations, documented here and never validated
 * ({@code default-plan-carriers} D5):</b> {@code orderedFields} is in
 * class source order; {@code runtimeDependencies} is ordered by first
 * occurrence in the typed evaluator IR walk with one dependency per
 * imported resource identity, each carrying the first occurrence's
 * reference range. The graph epic (ISSUE-0543) is the sole constructor
 * of {@link RuntimeImportDependency} records and only builds
 * digest-bearing records for acyclic compilations; it completes
 * {@code runtimeDependencies} through
 * {@link #withRuntimeDependencies}. The carrier only enforces set
 * semantics — duplicate elements (structural record equality) are
 * rejected at construction and completion.</p>
 *
 * <p><b>Completion lifecycle ({@code default-plan-carriers} D6):</b> the
 * planner/serializer epics construct this record with an empty
 * {@code runtimeDependencies} (plans stay compiler-internal until the
 * dependency graph succeeds;
 * {@code provider-versioned-default-plans} D1). The graph epic
 * (ISSUE-0543) is the sole producer of completed instances through
 * {@link #withRuntimeDependencies} — the only mutation surface; every
 * other field is fixed at construction.</p>
 *
 * <p>Immutable and deterministic; identities and digests are
 * compiler-internal and never appear in runtime descriptors, diagnostic
 * type names, public export keys, or source-language values.</p>
 *
 * @param classIdentity                  the class's canonical public
 *                                       identity
 * @param declaringSemanticModuleIdentity the declaring module's private
 *                                       semantic identity
 * @param orderedFields                  the field entries in class
 *                                       source order with unique names
 * @param runtimeDependencies            the ordered imported runtime
 *                                       dependency edges
 */
public record CompilerClassDefaultPlan(
    CanonicalClassIdentity classIdentity,
    SemanticModuleIdentity declaringSemanticModuleIdentity,
    List<CompilerClassDefaultEntry> orderedFields,
    Set<RuntimeImportDependency> runtimeDependencies
) {

    public CompilerClassDefaultPlan {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(declaringSemanticModuleIdentity,
            "declaringSemanticModuleIdentity");
        orderedFields =
            CarrierCollections.orderedListCopy(orderedFields, "orderedFields");
        CarrierCollections.rejectDuplicateNames(
            orderedFields.stream().map(CompilerClassDefaultEntry::name).toList());
        runtimeDependencies = CarrierCollections.orderedSetCopy(
            runtimeDependencies, "runtimeDependencies");
    }

    /**
     * The graph-owned completion seam ({@code default-plan-carriers} D6):
     * returns a new plan carrying the completed ordered dependency
     * edges, with every other field byte-identical. This is the only
     * mutation surface of the record.
     *
     * <p>The completed set may be any size including empty (a plan with
     * no imported runtime dependencies completes with an empty set).
     * Null and duplicate elements are rejected; insertion order is
     * preserved.</p>
     *
     * @param newRuntimeDependencies the completed ordered dependency
     *                               edges (possibly empty)
     * @return a new completed plan; this plan is unchanged
     */
    public CompilerClassDefaultPlan withRuntimeDependencies(
            Set<RuntimeImportDependency> newRuntimeDependencies) {
        Objects.requireNonNull(newRuntimeDependencies, "runtimeDependencies");
        return new CompilerClassDefaultPlan(
            classIdentity, declaringSemanticModuleIdentity, orderedFields,
            newRuntimeDependencies);
    }
}
