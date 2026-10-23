package deal.parser;

import deal.source.ScalarSourceCursor;

/**
 * The scalar rebasing map of one template interpolation.
 *
 * <p>An interpolation expression is sub-lexed verbatim from the template's
 * raw content (v1.4 already forbids escape processing in TemplateText and
 * admits the normal quoted-string rules inside the interpolation), so the
 * sub-lexed decoded scalar {@code d} is exactly raw scalar
 * {@code expressionStartRawScalarIndex + d}. The map owns that identity
 * relation plus the source position of every raw scalar — line and column
 * are computed with the same CRLF rule as {@link ScalarSourceCursor}, so an
 * interpolation containing whitespace line terminators still rebases every
 * sub-lexed range to its exact original source position.</p>
 *
 * <p>All lookups clamp instead of throwing: negative and past-end indices
 * resolve to the expression start and the expression end respectively.</p>
 */
final class TemplateScalarMap {

    /** 1-based source line of the template literal's opening backtick. */
    private final int baseLine;

    /** 1-based source column of the opening backtick. */
    private final int baseCol;

    /**
     * Source scalar offset of the opening backtick, or
     * {@link deal.lexer.Token#UNKNOWN_OFFSET} when the template token
     * carries no offset information (defensive/test-only inputs).
     */
    private final int templateTokenStartScalarOffset;

    /** Raw scalar index of the expression's first raw scalar. */
    private final int expressionStartRawScalarIndex;

    /** Raw scalar index just past the expression's last raw scalar. */
    private final int expressionEndRawScalarIndex;

    /** Per raw scalar index (0..rawScalarCount): 1-based source line. */
    private final int[] rawLine;

    /** Per raw scalar index (0..rawScalarCount): 1-based source column. */
    private final int[] rawColumn;

    /**
     * Builds the map for one interpolation of {@code raw}: the raw content
     * between the template's backticks.
     */
    TemplateScalarMap(String raw, int baseLine, int baseCol,
                      int templateTokenStartScalarOffset,
                      int expressionStartRawScalarIndex,
                      int expressionEndRawScalarIndex) {
        this.baseLine = baseLine;
        this.baseCol = baseCol;
        this.templateTokenStartScalarOffset = templateTokenStartScalarOffset;
        this.expressionStartRawScalarIndex = expressionStartRawScalarIndex;
        this.expressionEndRawScalarIndex = Math.max(
            expressionStartRawScalarIndex, expressionEndRawScalarIndex);

        String content = raw == null ? "" : raw;
        int total = ScalarSourceCursor.scalarCount(content);
        this.rawLine = new int[total + 1];
        this.rawColumn = new int[total + 1];
        ScalarSourceCursor cursor = new ScalarSourceCursor(content);
        for (int i = 0; i <= total; i++) {
            rawLine[i] = baseLine + cursor.line() - 1;
            rawColumn[i] = cursor.line() == 1
                ? baseCol + cursor.column()
                : cursor.column();
            if (i < total) {
                cursor.advance();
            }
        }
    }

    /** The template's 1-based source line (its opening backtick). */
    int sourceLine() {
        return baseLine;
    }

    /** True iff the template token carries computed scalar offsets. */
    boolean hasTemplateScalarOffsets() {
        return templateTokenStartScalarOffset >= 0;
    }

    /** Raw scalar index of the expression's first raw scalar. */
    int expressionStartRawScalarIndex() {
        return expressionStartRawScalarIndex;
    }

    /** Number of decoded Unicode scalars in the sub-lexed expression. */
    int decodedScalarCount() {
        return expressionEndRawScalarIndex - expressionStartRawScalarIndex;
    }

    /**
     * Maps a decoded scalar index to its raw scalar index. Negative indices
     * clamp to the expression start; indices past the decoded count clamp
     * to the expression end.
     */
    int decodedToRawStart(int decodedIndex) {
        int d = Math.max(0, Math.min(decodedIndex, decodedScalarCount()));
        return expressionStartRawScalarIndex + d;
    }

    /**
     * Maps a decoded scalar index to the raw scalar index just after it.
     * Negative indices clamp to the expression start; indices at or past
     * the decoded count clamp to the expression end.
     */
    int decodedToRawEnd(int decodedIndex) {
        int d = Math.max(0, Math.min(decodedIndex,
            Math.max(0, decodedScalarCount() - 1)));
        return expressionStartRawScalarIndex + d + 1;
    }

    /**
     * Maps a raw scalar index to the original source scalar offset of that
     * raw scalar: {@code templateTokenStartScalarOffset + 1 + rawIndex}.
     * When the template token carries no offset information the result is
     * {@link deal.lexer.Token#UNKNOWN_OFFSET}.
     */
    int sourceOffsetAtRawScalar(int rawScalarIndex) {
        if (!hasTemplateScalarOffsets()) {
            return deal.lexer.Token.UNKNOWN_OFFSET;
        }
        return templateTokenStartScalarOffset + 1 + clampRaw(rawScalarIndex);
    }

    /** The 1-based source line of one raw scalar index. */
    int sourceLineAtRawScalar(int rawScalarIndex) {
        return rawLine[clampRaw(rawScalarIndex)];
    }

    /** The 1-based source column of one raw scalar index. */
    int sourceColumnAtRawScalar(int rawScalarIndex) {
        return rawColumn[clampRaw(rawScalarIndex)];
    }

    /** Clamps a raw scalar index into the table's range. */
    private int clampRaw(int rawScalarIndex) {
        return Math.max(0, Math.min(rawScalarIndex, rawLine.length - 1));
    }

    /**
     * The source position {@code {line, column}} of one raw scalar index
     * inside {@code raw}, whose first scalar sits at
     * {@code (baseLine, baseCol + 1)}. Never throws; the index clamps into
     * {@code [0, scalarCount(raw)]}.
     */
    static int[] positionAt(String raw, int baseLine, int baseCol,
                            int rawScalarIndex) {
        String content = raw == null ? "" : raw;
        int total = ScalarSourceCursor.scalarCount(content);
        int index = Math.max(0, Math.min(rawScalarIndex, total));
        ScalarSourceCursor cursor = new ScalarSourceCursor(content);
        for (int i = 0; i < index; i++) {
            cursor.advance();
        }
        int line = baseLine + cursor.line() - 1;
        int column = cursor.line() == 1
            ? baseCol + cursor.column()
            : cursor.column();
        return new int[]{line, column};
    }
}
