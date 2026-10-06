package deal.ast;

import java.util.Optional;

public record ReturnStatement(
    Span span,
    Optional<ExpressionNode> expr
) implements StatementNode {}
