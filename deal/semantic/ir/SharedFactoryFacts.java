package deal.semantic.ir;

import java.util.Objects;

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
