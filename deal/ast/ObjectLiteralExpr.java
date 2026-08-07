package deal.ast;

import java.util.List;

/** { prop: value, ... } */
public record ObjectLiteralExpr(
    Span span,
    List<Property> properties
) implements ExpressionNode {}
