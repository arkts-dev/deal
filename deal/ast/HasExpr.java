package deal.ast;

/** Object.hasOwn(receiver, key), with optional literal key for class checking. */
public record HasExpr(
    Span span,
    ExpressionNode object,
    String field,
    ExpressionNode key
) implements ExpressionNode {
    public HasExpr(Span span, ExpressionNode object, String field) {
        this(span, object, field, new LiteralExpr(span, new LiteralValue.StringLiteral(field)));
    }
}
