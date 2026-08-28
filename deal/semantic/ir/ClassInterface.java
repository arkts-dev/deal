package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * One class entry of an {@link ExternalModuleInterface} (parent canonical
 * surfaces): the class identity, the field interfaces in declaration
 * order, and the deterministic route-independent
 * {@code constructionEntry} {@link ClassFactoryId} (a shared owner
 * registers its {@code CLASS_FACTORY} op under the pre-allocated id; a
 * legacy owner resolves the retained target ABI factory entry at
 * staging). An external class interface exposes layout and owner factory,
 * never another module's default-expression AST. Pure immutable data of
 * {@code deal.semantic-interface/1}.
 *
 * @param classId           the class identity; non-null
 * @param fields            the field interfaces in declaration order; non-null
 * @param constructionEntry the route-independent construction entry; non-null
 */
public record ClassInterface(ClassId classId, List<FieldInterface> fields,
                             ClassFactoryId constructionEntry) {

    public ClassInterface(ClassId classId, List<FieldInterface> fields, ClassFactoryId constructionEntry) {
        this.classId = Objects.requireNonNull(classId, "classId must not be null");
        this.fields = List.copyOf(fields);
        this.constructionEntry = Objects.requireNonNull(constructionEntry, "constructionEntry must not be null");
    }
}
