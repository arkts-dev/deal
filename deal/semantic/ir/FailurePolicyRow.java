package deal.semantic.ir;

import deal.diagnostics.DiagnosticCode;

import java.util.List;
import java.util.Objects;

/**
 * One immutable row of the closed failure-policy table (schema S5;
 * parent "Closed failure policies and canonical visible errors").
 *
 * <p>{@link FailureContractRegistry} owns exactly one row per
 * {@link FailurePolicyId} — no more, no fewer, no fallback — and every
 * row's data is the parent page's closed row data reproduced verbatim.
 * Consumers receive this resolved record (never select messages
 * themselves), and rows are immutable: the template/metadata lists are
 * copied and unmodifiable, and all rule texts are fixed strings.</p>
 *
 * <p>Field contract:</p>
 * <ul>
 *   <li>{@code policy} — the closed policy this row resolves; non-null.</li>
 *   <li>{@code code} — the row's fixed DEAL-visible error code, or
 *       {@code null} exactly when the parent table pins no fixed
 *       DEAL-visible code ({@code NO_DEAL_FAILURE} propagates a
 *       child/operand failure, {@code JSON_FROM_NULL} returns language
 *       null, {@code THROW_TRANSFER} preserves the supplied Error, and
 *       {@code INFRASTRUCTURE_ONLY} is not a DEAL error).</li>
 *   <li>{@code templates} — every exact visible-error message template
 *       the row pins, primary projection first, in the parent's order;
 *       empty exactly when the row pins no fixed template.</li>
 *   <li>{@code metadataKeys} — the row's placeholder keys in order of
 *       first appearance across its templates (e.g.
 *       {@code oneBasedByteOffset}, {@code reason} for
 *       {@code JSON_PARSE_SYNTAX}; {@code oneBasedIndex} for
 *       {@code ARRAY_ELEMENT_DESCRIPTOR}); empty when the row has none.</li>
 *   <li>{@code originRule} — the parent's pinned origin rule; the
 *       default is "the operation origin" (the parent: "The operation
 *       origin is used unless the row names another origin").</li>
 *   <li>{@code causeRule} — the parent's pinned cause rule; the default
 *       is "no cause".</li>
 *   <li>{@code frameRule} — the parent's pinned frame rule; the default
 *       is "active DEAL calls from innermost to outermost".</li>
 *   <li>{@code precedence} — the parent's pinned check/defect order for
 *       compound rows (normative orders such as zero-divisor-first or
 *       null→NaN→infinity→fractional are verbatim); rows without a
 *       pinned compound order state their single check.</li>
 * </ul>
 */
public record FailurePolicyRow(
    FailurePolicyId policy,
    DiagnosticCode code,
    List<String> templates,
    List<String> metadataKeys,
    String originRule,
    String causeRule,
    String frameRule,
    String precedence
) {

    public FailurePolicyRow {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(templates, "templates must not be null");
        Objects.requireNonNull(metadataKeys, "metadataKeys must not be null");
        Objects.requireNonNull(originRule, "originRule must not be null");
        Objects.requireNonNull(causeRule, "causeRule must not be null");
        Objects.requireNonNull(frameRule, "frameRule must not be null");
        Objects.requireNonNull(precedence, "precedence must not be null");
        templates = List.copyOf(templates);
        metadataKeys = List.copyOf(metadataKeys);
    }

    /**
     * The row's primary visible-error template, or {@code null} exactly
     * when the parent table pins no fixed template for the row
     * ({@code templates()} is then empty).
     */
    public String template() {
        return templates.isEmpty() ? null : templates.get(0);
    }
}
