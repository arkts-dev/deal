package deal.ast;

import java.util.Optional;

/** let/const name: T = expr; const freezes only the binding. */
public record VariableDeclaration(
    Span span,
    String name,
    Optional<TypeNode> typeAnnotation,
    ExpressionNode initializer,
    boolean isConst
) implements StatementNode {
    public VariableDeclaration(Span span, String name, Optional<TypeNode> typeAnnotation,
                               ExpressionNode initializer) {
        this(span, name, typeAnnotation, initializer, false);
    }
}
