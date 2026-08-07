package deal.ast;

/** expr (as a statement) */
public record ExpressionStatement(
    Span span,
    ExpressionNode expr
) implements StatementNode {}
