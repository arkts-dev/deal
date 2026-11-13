package deal.ast;

/** A complete optional postfix chain; parentheses terminate this node. */
public record OptionalChainExpr(Span span, ExpressionNode expression) implements ExpressionNode {}
