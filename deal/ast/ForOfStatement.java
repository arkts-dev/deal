package deal.ast;

/**
 * for (let varName: varType of iterable) body
 *
 * <p>The iterable must be {@code T[]} (array) or {@code string}.
 * The loop variable is a fresh binding per iteration.</p>
 */
public record ForOfStatement(
    Span span,
    String varName,
    TypeNode varType,
    ExpressionNode iterable,
    Block body
) implements StatementNode {}
