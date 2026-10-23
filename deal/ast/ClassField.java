package deal.ast;

/** A structural class field declaration, with no initializer syntax. */
public record ClassField(
    Span span,
    String name,
    boolean optional,
    boolean nullable,
    TypeNode type
) {}
