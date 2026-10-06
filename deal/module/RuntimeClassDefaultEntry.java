package deal.module;

import java.util.Objects;

public record RuntimeClassDefaultEntry(
    String name,
    String runtimeTypeDescriptor,
    boolean optional,
    RuntimeDefaultEvaluator defaultEvaluator
) {

    public RuntimeClassDefaultEntry {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        Objects.requireNonNull(runtimeTypeDescriptor, "runtimeTypeDescriptor");
        if (optional && defaultEvaluator != null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is optional but carries a default evaluator"
                    + " (an optional entry must never carry one)");
        }
        if (!optional && defaultEvaluator == null) {
            throw new IllegalArgumentException(
                "field '" + name
                    + "' is required-present but carries no default"
                    + " evaluator (a required-present entry must carry one)");
        }
    }
}
