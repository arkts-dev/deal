package deal.diagnostics;

import java.util.List;
import java.util.Objects;

/** Producer-owned repair facts. Empty values mean unavailable, never inferred from prose. */
public record DiagnosticFacts(
        String reason,
        String operation,
        String expected,
        String actual,
        List<String> guidance) {
    public static final DiagnosticFacts EMPTY = new DiagnosticFacts("", "", "", "", List.of());

    public DiagnosticFacts {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        guidance = List.copyOf(guidance);
    }
}
