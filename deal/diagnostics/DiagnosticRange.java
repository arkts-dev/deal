package deal.diagnostics;

/**
 * A complete immutable half-open decoded-Unicode-scalar source range.
 *
 * <p>The range covers the decoded Unicode scalars
 * {@code [startScalarOffset, endScalarOffset)} in {@code file}; the
 * documented invariant is
 * {@code scalarLength == endScalarOffset - startScalarOffset}.
 * {@code endLine}/{@code endColumn} denote the position of the first
 * scalar after the range (half-open). Positions and offsets are measured
 * in decoded Unicode scalars: CRLF counts as two scalars, a tab as one,
 * and a supplementary code point as one.</p>
 *
 * <p>This record performs no validation and never throws for any input —
 * including nulls, non-positive positions, and negative or inverted
 * offsets. Defensive normalization of malformed ranges is owned by
 * {@link CompilerDiagnostic} (D9); negative scalar offset components are
 * treated as "no offset information" (UNKNOWN) by that normalization.</p>
 *
 * <p>The origin separates computed source ranges from synthetic anchors:
 * a {@link RangeOrigin#SOURCE} range always carries exact computed scalar
 * offsets, while a {@link RangeOrigin#SYNTHETIC} range uses the canonical
 * zero-length shape {@code (file,1,1,1,1,0,0,0,SYNTHETIC)} and carries an
 * anchor note naming the missing anchor.</p>
 *
 * @param file              the most specific known source path (module
 *                          path, or the empty string when none)
 * @param startLine         1-based start line
 * @param startColumn       1-based start column in decoded Unicode scalars
 * @param endLine           1-based line of the first scalar after the range
 * @param endColumn         1-based column of the first scalar after the range
 * @param startScalarOffset 0-based start scalar offset (inclusive), or a
 *                          negative value for "no offset information"
 * @param endScalarOffset   0-based end scalar offset (exclusive), or a
 *                          negative value for "no offset information"
 * @param scalarLength      decoded Unicode scalar count of the range; must
 *                          equal {@code endScalarOffset - startScalarOffset}
 *                          for a well-formed range
 * @param origin            {@link RangeOrigin#SOURCE} or
 *                          {@link RangeOrigin#SYNTHETIC}
 */
public record DiagnosticRange(
    String file,
    int startLine,
    int startColumn,
    int endLine,
    int endColumn,
    int startScalarOffset,
    int endScalarOffset,
    int scalarLength,
    RangeOrigin origin
) {
    /**
     * The canonical synthetic range
     * {@code (file,1,1,1,1,0,0,0,SYNTHETIC)}: a zero-length range at
     * position (1,1) with equal known offsets. A {@code null} file becomes
     * the empty string (no path known). Never throws.
     */
    public static DiagnosticRange synthetic(String file) {
        return new DiagnosticRange(file == null ? "" : file,
            1, 1, 1, 1, 0, 0, 0, RangeOrigin.SYNTHETIC);
    }

    /**
     * Returns true iff both scalar offset components are non-negative,
     * i.e. the range carries actual computed scalar offset information.
     */
    public boolean hasScalarOffsets() {
        return startScalarOffset >= 0 && endScalarOffset >= 0;
    }

    /**
     * Returns true iff this range is the canonical synthetic shape
     * {@code (file,1,1,1,1,0,0,0,SYNTHETIC)}: positions (1,1)-(1,1),
     * offsets (0,0), zero scalar length, SYNTHETIC origin.
     */
    public boolean isCanonicalSynthetic() {
        return origin == RangeOrigin.SYNTHETIC
            && startLine == 1 && startColumn == 1
            && endLine == 1 && endColumn == 1
            && startScalarOffset == 0 && endScalarOffset == 0
            && scalarLength == 0;
    }
}
