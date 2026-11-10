package deal.ast;

import java.util.List;

/**
 * Root of the AST: a sequence of zero or more statements with its source span.
 */
public record ProgramNode(Span span, List<StatementNode> statements) {

    public ProgramNode {
        if (statements == null) {
            throw new IllegalArgumentException("statements must not be null");
        }
    }
}
