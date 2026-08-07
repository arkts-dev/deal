package deal.ast;

import java.util.List;

/** { statements... } */
public record Block(
    Span span,
    List<StatementNode> statements
) implements StatementNode {}
