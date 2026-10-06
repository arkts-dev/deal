package deal.module;

import deal.identity.CanonicalClassIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Set;

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
     */
    public CompilerClassDefaultPlan withRuntimeDependencies(
            Set<RuntimeImportDependency> newRuntimeDependencies) {
        Objects.requireNonNull(newRuntimeDependencies, "runtimeDependencies");
        return new CompilerClassDefaultPlan(
            classIdentity, declaringSemanticModuleIdentity, orderedFields,
            newRuntimeDependencies);
    }
}
