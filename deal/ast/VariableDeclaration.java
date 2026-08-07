package deal.ast;

import java.util.Optional;

/** let name: T = expr */
public record VariableDeclaration(
    Span span,
    String name,
    Optional<TypeNode> typeAnnotation,
    ExpressionNode initializer
) implements StatementNode {}
