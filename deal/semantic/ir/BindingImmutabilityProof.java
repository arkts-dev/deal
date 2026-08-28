package deal.semantic.ir;

import java.util.Objects;

/**
 * A binding-immutability proof (parent D15): the recorded proof that a
 * binding is never reassigned within its enclosing scope, which makes
 * {@code FUNCTION_ADAPT(CaptureMode.VALUE)} admissible for that binding
 * source. Without the proof, a binding source must be
 * {@code SHARED_CELL} — the closed D15 lowering shape map.
 *
 * @param binding    the proven binding identity; non-null
 * @param generation the generation observed by the proof; non-negative
 */
public record BindingImmutabilityProof(BindingId binding, long generation) {

    public BindingImmutabilityProof {
        Objects.requireNonNull(binding, "binding must not be null");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0, got " + generation);
        }
    }
}
