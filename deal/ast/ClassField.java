package deal.ast;

/** A structural class field declaration, with no initializer syntax. */
public record ClassField(
    Span span,
    String name,
    boolean optional,
    boolean nullable,
    TypeNode type,
    boolean readonly
) {
    public ClassField(Span span, String name, boolean optional, boolean nullable, TypeNode type) {
        this(span, name, optional, nullable, type, false);
    }
}
