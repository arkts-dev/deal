package deal.ast;

import java.util.Objects;

/**
 * Source location for every AST node. Lines and columns are 1-based.
 * Immutable.
 */
public record Span(
    String file,
    int startLine,
    int startColumn,
    int endLine,
    int endColumn
) {
    public Span {
        Objects.requireNonNull(file, "file must not be null");
        if (startLine < 1) throw new IllegalArgumentException("startLine must be >= 1, got " + startLine);
        if (startColumn < 1) throw new IllegalArgumentException("startColumn must be >= 1, got " + startColumn);
        if (endLine < 1) throw new IllegalArgumentException("endLine must be >= 1, got " + endLine);
        if (endColumn < 1) throw new IllegalArgumentException("endColumn must be >= 1, got " + endColumn);
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            throw new IllegalArgumentException(
                "end position (" + endLine + ":" + endColumn +
                ") must not be before start (" + startLine + ":" + startColumn + ")"
            );
        }
    }

    /**
     * Returns a synthetic span with all positions set to 0.
     * Used for compiler-generated nodes (e.g., arity adapters).
     */
    public static Span synthetic(String file) {
        return new Span(file, 1, 1, 1, 1);
    }

    @Override
    public String toString() {
        return file + ":" + startLine + ":" + startColumn;
    }
}
