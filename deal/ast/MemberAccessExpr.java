package deal.ast;

/** object.field */
public record MemberAccessExpr(
    Span span,
    ExpressionNode object,
    String field
) implements ExpressionNode {}
