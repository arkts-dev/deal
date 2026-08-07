package deal.ast;

/** throw expr */
public record ThrowStatement(
    Span span,
    ExpressionNode expr
) implements StatementNode {}
