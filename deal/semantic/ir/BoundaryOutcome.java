package deal.semantic.ir;

import java.util.Objects;

/**
 * The outcome of one boundary execution (ISSUE-0233 design D3):
 * {@code Pass | Fail(BoundaryFailure)}.
 *
 * <p>{@link Pass} publishes the boundary's output value as its closed
 * view — the same view instance the check received (the executor never
 * copies or converts values) except the pinned missing→null mapping
 * ({@code CONTEXTUAL_TABLE_READ}/{@code OPTIONAL_FIELD_READ} with a
 * nullable descriptor) and the {@code JSON_FROM_NULL} swallow, which
 * publish a language-null view. {@link Fail} carries the structured
 * {@link BoundaryFailure} projection of the registry row.</p>
 */
public sealed interface BoundaryOutcome
    permits BoundaryOutcome.Pass, BoundaryOutcome.Fail {

    /**
     * The boundary passed; {@code value} is the closed view of the
     * published value (the input view unchanged, or a null view for the
     * named missing→null mappings).
     */
    record Pass(BoundaryValueView value) implements BoundaryOutcome {

        public Pass {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /** The boundary failed; {@code failure} is the registry-row projection. */
    record Fail(BoundaryFailure failure) implements BoundaryOutcome {

        public Fail {
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }
}
