package deal.ast;

/** A literal value: null, boolean, int, number, or string. */
public record LiteralExpr(Span span, LiteralValue value) implements ExpressionNode {}
