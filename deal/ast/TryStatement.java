package deal.ast;

/** try tryBlock catch (catchVar) catchBlock */
public record TryStatement(
    Span span,
    Block tryBlock,
    String catchVar,
    Block catchBlock
) implements StatementNode {}
