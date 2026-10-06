package deal.module;

import deal.types.Type;

import java.util.Objects;

public record CompilerClassDefaultEntry(
    String name,
    Type resolvedFieldType,
    String runtimeTypeDescriptor,
    boolean optional,
    ResolvedDefaultExpression defaultExpression
) {

    public CompilerClassDefaultEntry {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        Objects.requireNonNull(resolvedFieldType, "resolvedFieldType");
        Objects.requireNonNull(runtimeTypeDescriptor, "runtimeTypeDescriptor");
        if (optional && defaultExpression != null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is optional but carries a default expression"
                    + " (an optional entry must not carry one)");
        }
        if (!optional && defaultExpression == null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is required-present but carries no default"
                    + " expression (a required-present entry must carry one)");
        }
    }
}
