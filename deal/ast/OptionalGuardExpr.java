package deal.ast;

/** Receiver/callee immediately before a source ?. postfix part. */
public record OptionalGuardExpr(Span span, ExpressionNode value) implements ExpressionNode {}
