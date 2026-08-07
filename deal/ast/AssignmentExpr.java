package deal.ast;

/** target = value */
public record AssignmentExpr(
    Span span,
    ExpressionNode target,
    ExpressionNode value
) implements ExpressionNode {}
