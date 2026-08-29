package deal.semantic;

import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.OpId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The boundary-realization report of a {@link ModuleEmissionResult}
 * (parent "Shared module emission and publication" contract): the
 * emitter's recorded realization per boundary operation of the emitted
 * unit — {@code RuntimeValidation(checkId)} or
 * {@code RepresentationProof(proofKind)} per parent D7.
 *
 * <p>The report is a pure carrier in this epic: boundary production and
 * realization completion are the construct epics' (ISSUE-0233..0236);
 * synthetic tests carry an empty report. The record is immutable and
 * copies its map defensively, preserving op order.</p>
 *
 * @param realizations the boundary realizations keyed by the boundary
 *                     op id; non-null
 */
public record BoundaryRealizationReport(Map<OpId, BoundaryRealization> realizations) {

    public BoundaryRealizationReport {
        Objects.requireNonNull(realizations, "realizations must not be null");
        Map<OpId, BoundaryRealization> copy = new LinkedHashMap<>();
        for (Map.Entry<OpId, BoundaryRealization> entry : realizations.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "realizations keys must not be null");
            Objects.requireNonNull(entry.getValue(), "realizations values must not be null");
            copy.put(entry.getKey(), entry.getValue());
        }
        realizations = Collections.unmodifiableMap(copy);
    }

    /** The empty report (no boundary realizations recorded). */
    public static BoundaryRealizationReport empty() {
        return new BoundaryRealizationReport(Map.of());
    }
}
