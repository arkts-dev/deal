package deal.ast;

/**
 * try tryBlock catch (catchVar) catchBlock
 *
 * <p>{@code catchVarSpan} is the exact span of the catch variable
 * identifier, so a declaration-site diagnostic anchors on the identifier
 * rather than on the whole statement.</p>
 */
public record TryStatement(
    Span span,
    Block tryBlock,
    String catchVar,
    Span catchVarSpan,
    Block catchBlock
) implements StatementNode {}
