package deal.ast;

import java.util.List;

/**
 * Root of the AST. A program is a sequence of zero or more statements.
 */
public record ProgramNode(Span span, List<StatementNode> statements) {

    public ProgramNode {
        if (statements == null) {
            throw new IllegalArgumentException("statements must not be null");
        }
    }
}
