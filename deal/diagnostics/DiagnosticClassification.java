package deal.diagnostics;

import java.util.Objects;

/** Registry-owned contract classification, not inferred causal blame or edit permission. */
public record DiagnosticClassification(DiagnosticCode.Phase phase, Category category,
                                       Responsibility responsibility) {
    public enum Category {
        SYNTAX, NAME_RESOLUTION, MODULE_RESOLUTION, CONFIGURATION, TYPE_CHECKING,
        CONSTRUCTION, CALL, CONTROL_FLOW, LOWERING, DECLARATION_FILE, RUNTIME_BOUNDARY
    }

    /** The contract to inspect; runtime codes alone cannot attribute source versus host blame. */
    public enum Responsibility { SOURCE_CONTRACT, COMPILER_CONTRACT, RUNTIME_CONTRACT }

    public DiagnosticClassification {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(responsibility, "responsibility");
    }

    /** Unknown extension/test codes remain unclassified rather than being guessed by prefix. */
    public static DiagnosticClassification forCode(String code) {
        DiagnosticCode registered = DiagnosticCode.fromCode(code);
        return registered == null ? null : registered.classification();
    }
}
