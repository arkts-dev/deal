package deal.semantic.ir;

import java.util.Objects;

/**
 * A binding generation reference (parent D15): a {@link BindingId} paired
 * with the generation expected by a generation-checked binding load or
 * thunk capture. Generations are immutable numeric counters of a shared
 * cell; a stale generation is a validator error (R-* structural checks at
 * the production site).
 *
 * @param binding    the binding identity; non-null
 * @param generation the generation counter; non-negative
 */
public record BindingGeneration(BindingId binding, long generation) {

    public BindingGeneration {
        Objects.requireNonNull(binding, "binding must not be null");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0, got " + generation);
        }
    }
}
