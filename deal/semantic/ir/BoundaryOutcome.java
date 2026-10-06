package deal.semantic.ir;

import java.util.Objects;

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
