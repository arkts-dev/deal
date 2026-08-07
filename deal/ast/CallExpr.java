package deal.ast;

import java.util.List;

/** callee(args...) */
public record CallExpr(
    Span span,
    ExpressionNode callee,
    List<ExpressionNode> args
) implements ExpressionNode {}
