package deal.semantic.ir;

/**
 * A stable value identity, globally unique within one
 * {@link ExecutableLoweredProject} (parent D4).
 *
 * <p>Immutable value record over a non-negative numeric id. A
 * {@code ValueId} names a completed operand/result value. Loads, reads,
 * argument passing, and returns preserve allocation identity, so a
 * function-typed value's producing allocation is always resolvable
 * through its {@link FunctionAllocationIdentity} registration.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record ValueId(long id) implements SemanticId, SemanticValue {

    public ValueId {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "ValueId(" + id + ")";
    }
}
