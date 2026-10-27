package deal.ast;

/** Nominal runtime tag test, never structural construction. */
public record InstanceofExpr(Span span, ExpressionNode value, TypeNode target) implements ExpressionNode {}
