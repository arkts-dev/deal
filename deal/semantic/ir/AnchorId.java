package deal.semantic.ir;

/**
 * A stable source-anchor identity, globally unique within one
 * {@link ExecutableLoweredProject} (parent D4).
 *
 * <p>Immutable value record over a non-negative numeric id. Every
 * {@link SourceOrigin} carries one anchor; the anchor links the semantic
 * op back to the copied source location without retaining any AST node.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record AnchorId(long id) implements SemanticId {

    public AnchorId {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "AnchorId(" + id + ")";
    }
}
