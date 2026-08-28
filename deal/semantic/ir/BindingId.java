package deal.semantic.ir;

/**
 * A stable binding identity, globally unique within one
 * {@link ExecutableLoweredProject} (parent D4).
 *
 * <p>Immutable value record over a non-negative numeric id. Bindings are
 * copied from checked {@code Symbol} facts at bridge time; the semantic
 * unit retains no checker object.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record BindingId(long id) implements SemanticId {

    public BindingId {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "BindingId(" + id + ")";
    }
}
