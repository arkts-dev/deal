package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * The closed adaptation source reference of {@code FUNCTION_ADAPT}
 * (parent D15): exactly one shape per capture mode.
 *
 * <ul>
 *   <li>{@link SharedCell} ({@code SHARED_CELL}) — a reassignable
 *       function-typed binding: every invocation performs a
 *       generation-checked load of the binding's current value, so later
 *       reassignment retargets the adapter.</li>
 *   <li>{@link Thunk} ({@code REEVALUATE_THUNK}) — a non-identifier
 *       expression recorded as a lowered thunk block with generation-pinned
 *       captures: every invocation re-executes the thunk, so side effects
 *       repeat and a fresh closure produced per run has fresh identity.</li>
 *   <li>{@link Value} ({@code VALUE}) — an already-materialized function
 *       value operand retained for the adapter lifetime (admissible for a
 *       binding only with a recorded {@link BindingImmutabilityProof}).</li>
 * </ul>
 *
 * <p>Closed sealed family; no other source shape exists.</p>
 */
public sealed interface AdaptSourceRef
    permits AdaptSourceRef.SharedCell, AdaptSourceRef.Thunk, AdaptSourceRef.Value {

    /** SHARED_CELL: load the binding's current value per invocation. */
    record SharedCell(BindingId binding, long generation) implements AdaptSourceRef {

        public SharedCell {
            Objects.requireNonNull(binding, "binding must not be null");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must be >= 0, got " + generation);
            }
        }
    }

    /** REEVALUATE_THUNK: re-execute the recorded thunk block per invocation. */
    record Thunk(BlockId blockId, List<BindingGeneration> capturedBindings) implements AdaptSourceRef {

        public Thunk(BlockId blockId, List<BindingGeneration> capturedBindings) {
            this.blockId = Objects.requireNonNull(blockId, "blockId must not be null");
            this.capturedBindings = List.copyOf(capturedBindings);
        }
    }

    /** VALUE: retain the materialized function value identity. */
    record Value(ValueId value) implements AdaptSourceRef {

        public Value {
            Objects.requireNonNull(value, "value must not be null");
        }
    }
}
