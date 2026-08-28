package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * A class layout of {@code deal.semantic-ir/1} (parent D16; schema S2):
 * the structural field layout a {@code CLASS_NEW}/{@code CLASS_FACTORY}
 * and the {@code JSON_FROM_CLASS}/{@code JSON_TO_CLASS} conversions carry.
 * Fields are in declaration order with presence and default ownership;
 * default expressions themselves are never part of the layout (an
 * external class interface exposes layout and owner factory, not another
 * module's default-expression AST).
 *
 * @param classId the class identity; non-null
 * @param fields  the field layouts in declaration order; non-null
 */
public record ClassLayout(ClassId classId, List<FieldLayout> fields) {

    /**
     * One field layout entry: name, declared descriptor, required-present
     * marker, and the default ownership used when the field is omitted.
     */
    public record FieldLayout(
        String name,
        RuntimeDescriptor descriptor,
        boolean required,
        DefaultOwner defaultOwner
    ) {

        public FieldLayout {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(defaultOwner, "defaultOwner must not be null");
        }
    }

    public ClassLayout(ClassId classId, List<FieldLayout> fields) {
        this.classId = Objects.requireNonNull(classId, "classId must not be null");
        this.fields = List.copyOf(fields);
    }
}
