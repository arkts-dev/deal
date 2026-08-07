package deal.ast;

/** has(obj.field) — field presence test */
public record HasExpr(
    Span span,
    ExpressionNode object,
    String field
) implements ExpressionNode {}
