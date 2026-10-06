package deal.semantic.ir;

import deal.diagnostics.DiagnosticCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BoundaryFailure(
    FailurePolicyId policy,
    DiagnosticCode code,
    String message,
    String expected,
    String actual,
    Map<String, String> metadata,
    BoundaryFailure cause
) {

    public BoundaryFailure {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Instantiates one pinned template of a registry row into a
     * {@code BoundaryFailure} — the arm-addressed entry point the executor
     * uses. The render resolves the arm bound to the retained template
     * position and enforces the arm's declaration: the arm's own template and
     * code (the multi-code {@code BYTES_WRITE} row's value-range arm pins
     * E8013), its parameter set, and its declared expected/actual shapes. A
     * retained template instantiated behind its arm's back is a fail-closed
     * producer defect, never a projection.
     *
     */
    public static BoundaryFailure fromRow(FailurePolicyRow row, int templateIndex,
                                          String expected, String actual,
                                          Map<String, String> metadata,
                                          BoundaryFailure cause) {
        Objects.requireNonNull(row, "row must not be null");
        List<String> templates = row.templates();
        if (templates.isEmpty() || templateIndex < 0 || templateIndex >= templates.size()) {
            throw new IllegalArgumentException(
                "template index " + templateIndex + " is outside the pinned templates of "
                    + row.policy());
        }
        return FailureContractRegistry.renderAtTemplate(row.policy(), templateIndex, expected,
            actual, metadata, cause);
    }
}
