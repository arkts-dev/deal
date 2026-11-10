package deal.ast;

import java.util.List;

/**
 * {@code class Name { fields... }}
 */
public record ClassDeclaration(
    Span span,
    String name,
    List<ClassField> fields
) implements StatementNode {

    public ClassDeclaration {
        if (fields == null) {
            throw new IllegalArgumentException("fields must not be null");
        }
    }
}
