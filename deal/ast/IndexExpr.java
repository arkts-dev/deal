package deal.ast;

/** array[index] */
public record IndexExpr(
    Span span,
    ExpressionNode array,
    ExpressionNode index
) implements ExpressionNode {}
