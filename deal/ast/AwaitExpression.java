package deal.ast;

/**
 * Await expression: {@code await callExpr}.
 *
 * <p>The callee must be a {@link CallExpr}.  The parser enforces this
 * with E1042.  If the parser constructs an {@code AwaitExpression}
 * with a non-{@code CallExpr} callee during error recovery, downstream
 * code must guard with {@code instanceof} before casting.</p>
 */
public record AwaitExpression(
    Span span,
    ExpressionNode callee
) implements ExpressionNode {}
