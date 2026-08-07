package deal.ast;

/** A variable or identifier reference. */
public record IdentifierExpr(Span span, String name) implements ExpressionNode {}
