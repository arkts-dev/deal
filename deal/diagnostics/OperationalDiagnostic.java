package deal.diagnostics;

import java.util.Objects;

/** A producer-classified non-language failure; never assigned an invented language error code. */
public record OperationalDiagnostic(String reason, String phase, String category, String responsibility,
                                    String message, DiagnosticFacts facts) {
    public OperationalDiagnostic {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(responsibility, "responsibility");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(facts, "facts");
    }
}
