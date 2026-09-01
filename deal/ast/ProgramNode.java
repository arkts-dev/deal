package deal.ast;

import java.util.List;

/**
 * Root of the AST. A program is a sequence of zero or more statements
 * plus its file-directive metadata (fixed-name-directive-events D5).
 */
public record ProgramNode(Span span, List<StatementNode> statements,
                          FileDirectives fileDirectives) {

    public ProgramNode {
        if (statements == null) {
            throw new IllegalArgumentException("statements must not be null");
        }
        if (fileDirectives == null) {
            throw new IllegalArgumentException("fileDirectives must not be null");
        }
    }

    /**
     * Convenience constructor for backward compatibility:
     * {@code fileDirectives} defaults to {@link FileDirectives#empty()}.
     */
    public ProgramNode(Span span, List<StatementNode> statements) {
        this(span, statements, FileDirectives.empty());
    }
}
