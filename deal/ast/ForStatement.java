package deal.ast;

import java.util.Optional;

/**
 * for (init?; condition?; update?) body
 *
 * <p>init: absent = no initializer; VarDecl = let-declared loop var;
 * AssignExpr = expression init.</p>
 */
public record ForStatement(
    Span span,
    Optional<ForInit> init,
    Optional<ExpressionNode> condition,
    Optional<ExpressionNode> update,
    Block body
) implements StatementNode {}
