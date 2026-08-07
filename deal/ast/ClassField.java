package deal.ast;

import java.util.Optional;

/**
 * A field declaration within a class body.
 */
public record ClassField(
    Span span,
    String name,
    boolean optional,
    boolean nullable,
    TypeNode type,
    Optional<ExpressionNode> defaultExpr
) {}
