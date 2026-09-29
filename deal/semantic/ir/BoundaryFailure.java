package deal.semantic.ir;

import deal.diagnostics.DiagnosticCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The structured boundary-failure projection of the closed failure-policy
 * rows (ISSUE-0233 design D3):
 * {@code {policy, code, message, expected, actual, metadata…, cause?}}.
 *
 * <p>The executor never selects message text. Every failure is built from
 * the pinned {@link FailureContractRegistry} arm bound to its retained
 * template position ({@link #fromRow(FailurePolicyRow, int, String,
 * String, Map, BoundaryFailure)} → {@code FailureContractRegistry.render
 * / renderAtTemplate}): {@code policy} is the row's policy, {@code code}
 * the arm's own pinned DEAL-visible code (its row's, or the multi-code
 * {@code BYTES_WRITE} value-range arm's E8013), {@code message} the arm's
 * own template instantiated with its named parameters, {@code expected}
 * the arm's declared expected token (or the element descriptor for E8003),
 * and {@code actual} the arm's declared actual projection — the
 * typed-boundary token, the carrier-kind token, or a pinned refinement
 * ({@code NaN}, {@code infinity}, {@code non-integer number} — the spec
 * pinned check order has no canonical-kind member for them) — and
 * {@code cause} the leaf failure where the row pins one (E8003). A
 * template whose placeholder stays unbound after instantiation fails
 * closed ({@link BoundaryExecutor.Defect}) — a broken projection never
 * renders.</p>
 */
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
     * @param row           the registry row owning the projection; non-null
     * @param templateIndex the row's template list position
     * @param expected      the canonical expected text, or {@code null} when
     *                      the arm declares no expected field
     * @param actual        the canonical actual text, or {@code null} when
     *                      the arm declares no actual field
     * @param metadata      the row's pinned metadata values
     *                      ({@code index}, {@code oneBasedIndex},
     *                      {@code fieldPath}); may be empty, never null
     * @param cause         the leaf failure, or {@code null}
     * @return the structured failure whose {@code policy} is the row's
     * @throws NullPointerException     if {@code row} is null
     * @throws IndexOutOfBoundsException if {@code templateIndex} is outside
     *                                   the row's template list
     * @throws BoundaryExecutor.Defect  if the render is not the bound arm's
     *                                  own declaration (fail closed)
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
