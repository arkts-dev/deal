package deal.ast;

/** Runtime-checked TypeScript assertion. */
public record AssertionExpr(Span span, ExpressionNode value, TypeNode target) implements ExpressionNode {}
