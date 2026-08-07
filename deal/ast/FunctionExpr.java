package deal.ast;

import java.util.List;
import java.util.Optional;

/** function(params...): R { body } (function expression) */
public record FunctionExpr(
    Span span,
    List<Parameter> params,
    Optional<Parameter> restParam,
    TypeNode returnType,
    Block body
) implements ExpressionNode {}
