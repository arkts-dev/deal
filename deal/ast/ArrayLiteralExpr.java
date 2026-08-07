package deal.ast;

import java.util.List;

/** [elem1, elem2, ...] */
public record ArrayLiteralExpr(
    Span span,
    List<ExpressionNode> elements
) implements ExpressionNode {}
