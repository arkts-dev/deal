package deal.ast;

import java.util.List;

/** class Name { fields... } */
public record ClassDeclaration(
    Span span,
    String name,
    List<ClassField> fields
) implements StatementNode {}
