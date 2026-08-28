package deal.semantic.ir;

/**
 * A stable function-allocation identity: the key of a unit's
 * {@code functionBindings} registration (parent canonical surfaces).
 *
 * <p>Immutable value record over a non-negative numeric id. Every
 * function-producing allocation ({@code CLOSURE_NEW},
 * {@code RECURSIVE_GROUP_INIT} members, {@code FUNCTION_ADAPT}, imported
 * host/external function values, and host-materialized function values)
 * registers exactly one {@link FunctionExecutionBinding} keyed by this
 * identity; loads, reads, argument passing, and returns preserve it.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record FunctionAllocationIdentity(long id) implements SemanticId {

    public FunctionAllocationIdentity {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "FunctionAllocationIdentity(" + id + ")";
    }
}
