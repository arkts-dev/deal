package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The parameter/import initialization facts of one lowered unit: the
 * bindings introduced by function parameters and by import aliases — the
 * two checker-fact sets of {@code BINDING_INIT_ONCE} that the unit payload
 * (ops, capture and binding records) cannot distinguish from ordinary
 * declarations.
 *
 * <p>The record is the unit's owned {@code classBindingFacts} companion:
 * it is a mandatory fact of every unit, present empty for a unit without
 * parameters or import aliases, and it is the exact fact set the bindings
 * production validator consumes. Both sets are immutable copies; insertion
 * order is the session's recording order so repeated canonical encodings
 * are byte-identical.</p>
 */
public record ClassBindingFacts(
    Set<BindingId> parameters,
    Set<BindingId> importAliases
) {

    /** The empty fact set: a unit with no parameters and no import aliases. */
    public static ClassBindingFacts empty() {
        return new ClassBindingFacts(Set.of(), Set.of());
    }

    public ClassBindingFacts {
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(importAliases, "importAliases must not be null");
        parameters = Collections.unmodifiableSet(new LinkedHashSet<>(parameters));
        importAliases = Collections.unmodifiableSet(
            new LinkedHashSet<>(importAliases));
    }
}
