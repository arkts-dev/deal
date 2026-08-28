package deal.semantic.ir;

/**
 * A stable function identity, globally unique within one
 * {@link ExecutableLoweredProject} (parent D4).
 *
 * <p>Immutable value record over a non-negative numeric id. IDs are
 * allocated in the pinned order (dependency order, source order, semantic
 * role, then synthetic ordinal); allocation is the
 * {@code SemanticIdAllocator}'s, uniqueness within a project is the
 * validator's.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record FunctionId(long id) implements SemanticId {

    public FunctionId {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "FunctionId(" + id + ")";
    }
}
