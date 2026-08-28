package deal.semantic.ir;

/**
 * A stable class-factory identity: the {@code ClassInterface.constructionEntry}
 * identifier of an exported class (parent D16; schema-referenced).
 *
 * <p>Immutable value record over a non-negative numeric id. A shared owner
 * registers its {@code CLASS_FACTORY} op under this pre-allocated id at
 * index-build time; a legacy owner resolves the retained target ABI
 * factory entry at staging. The identifier is route-independent and
 * deterministic.</p>
 *
 * @param id the numeric identity; non-negative
 */
public record ClassFactoryId(long id) implements SemanticId {

    public ClassFactoryId {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0, got " + id);
        }
    }

    @Override
    public String toString() {
        return "ClassFactoryId(" + id + ")";
    }
}
