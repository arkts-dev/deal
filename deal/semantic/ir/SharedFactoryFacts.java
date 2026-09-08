package deal.semantic.ir;

import java.util.Objects;

/**
 * The imported-construction lowering facts of {@code deal.semantic-ir/1}
 * (class-construction-jsonable-operations K-D2/K-D4/K-D5; ISSUE-0514):
 * one imported class's shared-factory context, carrying the owner's
 * factory facts — the pre-allocated route-independent
 * {@link ClassInterface#constructionEntry()} {@link ClassFactoryId}, the
 * owner unit's registered {@code CLASS_FACTORY} op id (the
 * {@link ClassFactoryRegistry} binding), and the factory op's result
 * {@link ValueId} (the {@code CLASS_DEFAULT_FIELD} boundary input of the
 * caller's {@code CLASS_NEW}, the K-D4 cross-unit D4-global reference) —
 * plus the two layout-resolution facts the caller's literal arm consumes:
 * the owner unit's {@link ClassLayout} record (the payload layout and the
 * declaration-order field descriptors) and the interface index's
 * {@link ClassInterface} entry (the {@code constructionEntry} authority
 * and the per-field {@code hasDefault} facts).
 *
 * <p><b>Production shape (K-D2/K-D4).</b> A caller's
 * {@code CLASS_NEW} of an imported class carries
 * {@code defaultOwner: SHARED_FACTORY}, {@code classFactoryRef} = the
 * interface's {@code constructionEntry}, empty {@code classDefaultOpIds},
 * and {@code CLASS_DEFAULT_FIELD} boundaries wired to the owner factory
 * op's result {@code ValueId}. The lowering seam
 * ({@code SemanticLowerer.lowerModuleClassCore}) receives these facts
 * through a {@code Map<ClassId, SharedFactoryFacts>} so the reference is
 * emitted deterministically (byte-identical repeats) and is resolvable by
 * the executor through the layout-resolution context (the unit's
 * {@code classLayouts} plus the interface facts). An imported class
 * without facts — an owner not on the shared route — defers to E10
 * (the retained ABI transport) and is never silently emitted as
 * {@code SHARED_FACTORY} and never executed here.</p>
 *
 * <p><b>Ownership.</b> This is construct-epic production data — no
 * schema-owned record changes and the foundation validator's closed
 * 14-condition rule set is untouched. The record is pure immutable
 * data; the {@code ClassFactoryRegistry} remains the unit-side
 * id&#8594;op binding carrier and this record is the per-class context
 * bundle handed across the lowering seam.</p>
 *
 * @param classId          the imported class identity; non-null
 * @param interfaceEntry   the interface index entry of the imported
 *                         class (the {@code constructionEntry} authority
 *                         and the per-field {@code hasDefault} facts);
 *                         non-null, must carry this classId
 * @param layout           the owner unit's {@code classLayouts} record of
 *                         the imported class (declaration order, exact
 *                         descriptors, required markers); non-null, must
 *                         carry this classId
 * @param factoryOpId      the owner's {@code CLASS_FACTORY} op id
 *                         registered under {@code constructionEntry} in
 *                         the owner's {@link ClassFactoryRegistry};
 *                         non-null
 * @param factoryResult    the owner factory op's result {@link ValueId}
 *                         (the {@code CLASS_DEFAULT_FIELD} boundary input
 *                         wiring of the caller's {@code CLASS_NEW});
 *                         non-null
 */
public record SharedFactoryFacts(
    ClassId classId,
    ClassInterface interfaceEntry,
    ClassLayout layout,
    OpId factoryOpId,
    ValueId factoryResult
) {

    public SharedFactoryFacts {
        Objects.requireNonNull(classId, "classId must not be null");
        Objects.requireNonNull(interfaceEntry, "interfaceEntry must not be null");
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(factoryOpId, "factoryOpId must not be null");
        Objects.requireNonNull(factoryResult, "factoryResult must not be null");
        if (!interfaceEntry.classId().equals(classId)) {
            throw new IllegalArgumentException(
                "interfaceEntry must carry classId " + classId + ", got "
                    + interfaceEntry.classId());
        }
        if (!layout.classId().equals(classId)) {
            throw new IllegalArgumentException(
                "layout must carry classId " + classId + ", got " + layout.classId());
        }
    }

    /** The declared-default fact of one interface field, fail-closed on absence. */
    public boolean hasDeclaredDefault(String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        for (FieldInterface field : interfaceEntry.fields()) {
            if (field.name().equals(fieldName)) {
                return field.hasDefault();
            }
        }
        throw new IllegalArgumentException(
            "field '" + fieldName + "' is not declared in the interface entry of "
                + classId + " (a fact defect, never inferred)");
    }
}
