package deal.semantic.ir;

import java.util.Objects;

/**
 * Optional evidence that a binding is never reassigned within its enclosing
 * scope. Adapter value capture does not require this proof.
 *
 */
public record BindingImmutabilityProof(BindingId binding, long generation) {

    public BindingImmutabilityProof {
        Objects.requireNonNull(binding, "binding must not be null");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0, got " + generation);
        }
    }
}
