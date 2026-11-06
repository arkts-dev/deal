package deal.ast;

/**
 * for (let/const varName of iterable) body
 *
 * <p>The iterable must be {@code T[]} (array) or {@code string}.
 * The loop variable is a fresh binding per iteration.</p>
 */
public record ForOfStatement(
    Span span,
    String varName,
    TypeNode varType,
    ExpressionNode iterable,
    Block body,
    boolean isConst
) implements StatementNode {
    public ForOfStatement(Span span, String varName, TypeNode varType,
                          ExpressionNode iterable, Block body) {
        this(span, varName, varType, iterable, body, false);
    }
}
