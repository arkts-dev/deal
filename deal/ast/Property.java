package deal.ast;

/** An object property with either a decoded static name or a computed key. */
public record Property(Span span, String name, ExpressionNode key, ExpressionNode value) {
    public Property(Span span, String name, ExpressionNode value) {
        this(span, name, null, value);
    }

    /** A decoded key known without evaluating user code, or null. */
    public String staticName() {
        if (name != null) return name;
        return key instanceof LiteralExpr literal
            && literal.value() instanceof LiteralValue.StringLiteral text ? text.value() : null;
    }
}
