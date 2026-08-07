package deal.ast;

/** left op right */
public record BinaryExpr(
    Span span,
    ExpressionNode left,
    BinaryOp op,
    ExpressionNode right
) implements ExpressionNode {}
