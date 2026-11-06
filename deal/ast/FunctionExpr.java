package deal.ast;

import java.util.List;

/**
 * A function expression or explicitly typed arrow. Expression arrow bodies
 * use an ordinary body block, retaining their source form for void-result typing.
 */
public record FunctionExpr(
    Span span,
    List<Parameter> params,
    TypeNode returnType,
    Block body,
    boolean isAsync,
    boolean expressionBody
) implements ExpressionNode {
    public FunctionExpr(Span span, List<Parameter> params, TypeNode returnType,
                        Block body, boolean isAsync) {
        this(span, params, returnType, body, isAsync, false);
    }
}
