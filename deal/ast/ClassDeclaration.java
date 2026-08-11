package deal.ast;

import java.util.List;

/** class Name { fields... } */
public record ClassDeclaration(
    Span span,
    String name,
    List<ClassField> fields,
    boolean isJsonable
) implements StatementNode {

    /**
     * Convenience constructor for backward compatibility.
     * {@code isJsonable} defaults to {@code false}.
     */
    public ClassDeclaration(Span span, String name, List<ClassField> fields) {
        this(span, name, fields, false);
    }
}
