package deal.source;

/**
 * A half-open range of decoded Unicode scalar positions.
 *
 * <p>Lines and columns are 1-based decoded Unicode scalar positions;
 * {@code startScalarOffset} and {@code endScalarOffset} are 0-based decoded
 * scalar offsets with {@code [start, end)} half-open semantics: the first
 * scalar of the range is at {@code (startLine, startColumn)} and
 * {@code (endLine, endColumn)} is the position of the first scalar after
 * the range. The scalar length of the range is therefore always
 * {@code endScalarOffset - startScalarOffset}.
 *
 * <p>This record is the {@code deal.source}-local (JDK-only) range carrier:
 * it deliberately carries no {@code file} component, because content-only
 * scanners such as {@link JsonRangeLexer} receive a content string but no
 * path. Consumers that publish ranges into diagnostic carriers supply the
 * most specific known path themselves when converting.
 *
 * <p>Every field is a concrete value computed by {@link ScalarSourceCursor};
 * the record performs no validation and never throws.
 *
 * @param startLine         1-based line of the first scalar in the range
 * @param startColumn       1-based scalar column of the first scalar
 * @param endLine           1-based line of the first scalar after the range
 * @param endColumn         1-based scalar column of the first scalar after the range
 * @param startScalarOffset 0-based decoded scalar offset of the range start (inclusive)
 * @param endScalarOffset   0-based decoded scalar offset of the range end (exclusive)
 */
public record SourceScalarRange(int startLine, int startColumn, int endLine, int endColumn,
                                int startScalarOffset, int endScalarOffset) {

    /**
     * The decoded scalar length of the range:
     * {@code endScalarOffset - startScalarOffset}.
     */
    public int scalarLength() {
        return endScalarOffset - startScalarOffset;
    }
}
