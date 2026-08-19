package deal.ast;

import java.util.List;

/**
 * {@code function(p1: T1, ..., pN: TN): R { body }} (function expression).
 *
 * <p>DEAL v1.2: no rest parameters.</p>
 */
public record FunctionExpr(
    Span span,
    List<Parameter> params,
    TypeNode returnType,
    Block body,
    boolean isAsync
) implements ExpressionNode {}
