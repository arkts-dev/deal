package deal.ast;

/** while (condition) body */
public record WhileStatement(
    Span span,
    ExpressionNode condition,
    Block body
) implements StatementNode {}
