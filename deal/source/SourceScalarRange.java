package deal.source;

/**
 * A half-open UTF-16 source range. Lines/columns are 1-based and
 * {@code startScalarOffset}/{@code endScalarOffset} are 0-based UTF-16
 * offsets. The length is {@code endScalarOffset - startScalarOffset}.
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
 */
public record SourceScalarRange(int startLine, int startColumn, int endLine, int endColumn,
                                int startScalarOffset, int endScalarOffset) {

    /**
     * The UTF-16 length of the range:
     * {@code endScalarOffset - startScalarOffset}.
     */
    public int scalarLength() {
        return endScalarOffset - startScalarOffset;
    }
}
