package deal.ast;

import java.util.Objects;

/**
 * Source location for every AST node. Lines and columns are 1-based.
 * Immutable.
 *
 * <p>End line/column stay <b>inclusive</b> (the pinned AST contract).
 * {@code startScalarOffset} and {@code endScalarOffset} are the half-open
 * decoded-Unicode-scalar range {@code [startScalarOffset, endScalarOffset)}
 * covered by this span in its file. Both components use
 * {@link #UNKNOWN_OFFSET} as the explicit "no offset information" sentinel;
 * the convenience constructor sets them to {@link #UNKNOWN_OFFSET}, never
 * {@code 0}.</p>
 */
public record Span(
    String file,
    int startLine,
    int startColumn,
    int endLine,
    int endColumn,
    int startScalarOffset,
    int endScalarOffset
) {
    /**
     * Explicit sentinel for "no offset information". Never a valid scalar
     * offset (valid scalar offsets are non-negative).
     */
    public static final int UNKNOWN_OFFSET = -1;

    /**
     * Convenience constructor for backward compatibility: no offset
     * information. Both scalar offset components are set to
     * {@link #UNKNOWN_OFFSET}, never {@code 0}.
     */
    public Span(String file, int startLine, int startColumn, int endLine, int endColumn) {
        this(file, startLine, startColumn, endLine, endColumn,
            UNKNOWN_OFFSET, UNKNOWN_OFFSET);
    }

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
     * Returns true iff both scalar offset components are non-negative, i.e.
     * the span carries actual scalar offset information.
     */
    public boolean hasScalarOffsets() {
        return startScalarOffset >= 0 && endScalarOffset >= 0;
    }

    /**
     * Returns a synthetic span at (1,1)-(1,1) carrying
     * {@link #UNKNOWN_OFFSET} scalar offsets — no offset information.
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
