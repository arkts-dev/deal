package deal.semantic.ir;

import java.util.Objects;

/**
 * The schema-owned source span of a {@link SourceOrigin} (parent D4;
 * schema S1).
 *
 * <p>Pure immutable data mirroring the checked span contract without
 * importing any frontend type: lines and columns are 1-based, end
 * line/column stay <b>inclusive</b>, and the scalar offsets are the
 * half-open decoded-Unicode-scalar range
 * {@code [startScalarOffset, endScalarOffset)} with
 * {@link #UNKNOWN_OFFSET} as the explicit "no offset information"
 * sentinel. The semantic IR retains no AST node; spans are copied values.</p>
 *
 * @param file              the source file identifier; non-null
 * @param startLine         1-based start line
 * @param startColumn       1-based start column
 * @param endLine           1-based inclusive end line
 * @param endColumn         1-based inclusive end column
 * @param startScalarOffset start scalar offset (inclusive) or {@link #UNKNOWN_OFFSET}
 * @param endScalarOffset   end scalar offset (exclusive) or {@link #UNKNOWN_OFFSET}
 */
public record SourceSpan(
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
     * Convenience constructor: no offset information. Both scalar offset
     * components are set to {@link #UNKNOWN_OFFSET}, never {@code 0}.
     */
    public SourceSpan(String file, int startLine, int startColumn, int endLine, int endColumn) {
        this(file, startLine, startColumn, endLine, endColumn, UNKNOWN_OFFSET, UNKNOWN_OFFSET);
    }

    public SourceSpan {
        Objects.requireNonNull(file, "file must not be null");
        if (startLine < 1) throw new IllegalArgumentException("startLine must be >= 1, got " + startLine);
        if (startColumn < 1) throw new IllegalArgumentException("startColumn must be >= 1, got " + startColumn);
        if (endLine < 1) throw new IllegalArgumentException("endLine must be >= 1, got " + endLine);
        if (endColumn < 1) throw new IllegalArgumentException("endColumn must be >= 1, got " + endColumn);
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            throw new IllegalArgumentException(
                "end position (" + endLine + ":" + endColumn +
                ") must not be before start (" + startLine + ":" + startColumn + ")");
        }
    }

    /** Returns true iff both scalar offset components are non-negative. */
    public boolean hasScalarOffsets() {
        return startScalarOffset >= 0 && endScalarOffset >= 0;
    }

    /**
     * Returns a synthetic span at (1,1)-(1,1) carrying
     * {@link #UNKNOWN_OFFSET} scalar offsets — no offset information.
     * Used for compiler-generated origins.
     */
    public static SourceSpan synthetic(String file) {
        return new SourceSpan(file, 1, 1, 1, 1);
    }

    @Override
    public String toString() {
        return file + ":" + startLine + ":" + startColumn;
    }
}
