package deal.ast;

import java.util.Optional;

/** return expr? */
public record ReturnStatement(
    Span span,
    Optional<ExpressionNode> expr
) implements StatementNode {}
