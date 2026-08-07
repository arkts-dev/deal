package deal.ast;

/** op expr (unary ! or -) */
public record UnaryExpr(
    Span span,
    UnaryOp op,
    ExpressionNode expr
) implements ExpressionNode {}
