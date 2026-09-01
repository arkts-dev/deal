package deal.ast;

import java.util.List;
import java.util.Set;

/**
 * {@code class Name { fields... }}
 *
 * <p>Directive metadata is the {@code Set<DeclarationDirective>}
 * component (fixed-name-directive-events D5): {@link #isJsonable()} is a
 * derived accessor ({@code directives.contains(JSONABLE)}); the retired
 * boolean component no longer exists. Metadata is applied only on
 * bindings valid by construction (export-wrapped class in a
 * non-declaration file), so invalid metadata can never reach
 * generation.</p>
 */
public record ClassDeclaration(
    Span span,
    String name,
    List<ClassField> fields,
    Set<DeclarationDirective> directives
) implements StatementNode {

    public ClassDeclaration {
        if (fields == null) {
            throw new IllegalArgumentException("fields must not be null");
        }
        if (directives == null) {
            throw new IllegalArgumentException("directives must not be null");
        }
        directives = Set.copyOf(directives);
    }

    /**
     * Convenience constructor for backward compatibility.
     * {@code directives} defaults to the empty set.
     */
    public ClassDeclaration(Span span, String name, List<ClassField> fields) {
        this(span, name, fields, Set.of());
    }

    /**
     * The derived jsonable accessor: true iff the class carries the
     * {@code JSONABLE} declaration directive.
     */
    public boolean isJsonable() {
        return directives.contains(DeclarationDirective.JSONABLE);
    }
}
