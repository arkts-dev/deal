package deal.ast;

import java.util.Optional;

/**
 * if (condition) thenBlock (else (IfStatement | Block))?
 *
 * <p>elseBranch: absent = no else; Left(IfStatement) = else-if; Right(Block) = else-block.</p>
 */
public record IfStatement(
    Span span,
    ExpressionNode condition,
    Block thenBlock,
    Optional<Either<IfStatement, Block>> elseBranch
) implements StatementNode {}
