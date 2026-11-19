package deal.ast;

/** Assignment syntax; compound targets are read and written without reevaluation. */
public record AssignmentExpr(
    Span span,
    ExpressionNode target,
    ExpressionNode value,
    AssignmentOperator operator
) implements ExpressionNode {
    public AssignmentExpr(Span span, ExpressionNode target, ExpressionNode value) {
        this(span, target, value, AssignmentOperator.ASSIGN);
    }
}
