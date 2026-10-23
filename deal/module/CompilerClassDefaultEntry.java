package deal.module;

import deal.types.Type;

import java.util.Objects;

public record CompilerClassDefaultEntry(
    String name,
    Type resolvedFieldType,
    String runtimeTypeDescriptor,
    boolean optional
) {

    public CompilerClassDefaultEntry {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        Objects.requireNonNull(resolvedFieldType, "resolvedFieldType");
        Objects.requireNonNull(runtimeTypeDescriptor, "runtimeTypeDescriptor");
    }
}
