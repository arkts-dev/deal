package deal.ast;

/** delete target (member or index access) */
public record DeleteStatement(
    Span span,
    ExpressionNode target
) implements StatementNode {}
