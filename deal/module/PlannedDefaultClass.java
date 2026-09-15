package deal.module;

import deal.ast.ClassDeclaration;

import java.util.List;
import java.util.Objects;

/**
 * One planned class of the default planning epic (ISSUE-0541): the
 * parent-pinned {@link CompilerClassDefaultPlan} — constructed with an
 * empty {@code runtimeDependencies} (plans stay compiler-internal until
 * the dependency graph succeeds, {@code provider-versioned-default-plans}
 * D1; the graph epic completes the field) — plus the plan's ordered,
 * deduplicated provisional {@link DefaultResourceOccurrence} list.
 *
 * <p>The occurrence list is the task-pinned provisional data the graph
 * epic (ISSUE-0543) consumes: ordered by first occurrence in the typed
 * evaluator IR walk across the plan's defaults (fields in class source
 * order, each default's walk in evaluation order), deduplicated by
 * semantic resource identity with the first occurrence kept. It carries
 * no digest component and constructs no
 * {@link RuntimeImportDependency} record.</p>
 *
 * <p>The {@code declaration} component is the declaration-distinguishing
 * key: a module may legally declare same-named classes in distinct
 * function/block scopes (the checker rejects duplicates only at module
 * level), and their {@code CompilerClassDefaultPlan}s share the same
 * name-based {@code classIdentity}. Consumers that must distinguish
 * same-named plans — the serializer's class memo, digest keys,
 * declaration-scope lookup, and inline nesting resolution — key by the
 * exact declaration (its complete scalar range), never by declared name
 * alone, so two same-named classes never collapse into one
 * serialization.</p>
 *
 * @param plan        the planned class's compiler plan (empty
 *                    {@code runtimeDependencies})
 * @param declaration the class declaration the plan derives from (the
 *                    declaration-distinguishing key)
 * @param occurrences the ordered, deduplicated provisional occurrence
 *                    records (possibly empty)
 */
public record PlannedDefaultClass(
    CompilerClassDefaultPlan plan,
    ClassDeclaration declaration,
    List<DefaultResourceOccurrence> occurrences
) {

    public PlannedDefaultClass {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(declaration, "declaration");
        occurrences = List.copyOf(Objects.requireNonNull(occurrences,
            "occurrences"));
    }
}
