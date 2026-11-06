package deal.ast;

/** A boolean condition and two lazily selected, right-associative branches. */
public record ConditionalExpr(Span span, ExpressionNode condition,
                              ExpressionNode consequent, ExpressionNode alternate)
        implements ExpressionNode {}
