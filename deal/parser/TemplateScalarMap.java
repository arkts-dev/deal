package deal.parser;

import java.util.List;

final class TemplateScalarMap {

    /** 1-based source line of the template literal (all positions). */
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

    /**
     * Per decoded scalar {@code d}: raw scalar index of the first raw
     * scalar that produced decoded scalar {@code d}. The last entry
     * ({@code d == decodedScalarCount}) is the expression-end raw scalar
     * index (past-end lookups clamp to it).
     */
    private final int[] rawStart;

    /**
     * Per decoded scalar {@code d}: raw scalar index just after the raw
     * run that produced decoded scalar {@code d}.
     */
    private final int[] rawEnd;

    /** Number of decoded Unicode scalars in the sub-lexed expression. */
    private final int decodedScalarCount;

    TemplateScalarMap(int baseLine, int baseCol, int templateTokenStartScalarOffset,
                      int expressionStartRawScalarIndex, int expressionEndRawScalarIndex,
                      List<Integer> rawStarts, List<Integer> rawEnds) {
        this.baseLine = baseLine;
        this.baseCol = baseCol;
        this.templateTokenStartScalarOffset = templateTokenStartScalarOffset;
        this.expressionStartRawScalarIndex = expressionStartRawScalarIndex;
        this.decodedScalarCount = rawEnds.size();
        this.rawStart = new int[decodedScalarCount + 1];
        for (int i = 0; i < decodedScalarCount; i++) {
            rawStart[i] = rawStarts.get(i);
        }
        rawStart[decodedScalarCount] = expressionEndRawScalarIndex;
        this.rawEnd = new int[decodedScalarCount];
        for (int i = 0; i < decodedScalarCount; i++) {
            rawEnd[i] = rawEnds.get(i);
        }
    }

    /** The template's 1-based source line. */
    int sourceLine() {
        return baseLine;
    }

    /** The 1-based source column of the opening backtick. */
    int templateBaseColumn() {
        return baseCol;
    }

    /**
     * The source scalar offset of the opening backtick, or
     * {@link deal.lexer.Token#UNKNOWN_OFFSET} when the template token
     * carries no offset information.
     */
    int templateTokenStartScalarOffset() {
        return templateTokenStartScalarOffset;
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
        return decodedScalarCount;
    }

    /**
     * Maps a decoded scalar index to the raw scalar index of its first raw
     * scalar. Negative indices clamp to the expression start; indices past
     * the decoded count clamp to the expression end. Never throws.
     */
    int decodedToRawStart(int decodedIndex) {
        if (decodedIndex <= 0) {
            return rawStart[0];
        }
        if (decodedIndex >= rawStart.length) {
            return rawStart[rawStart.length - 1];
        }
        return rawStart[decodedIndex];
    }

    /**
     * Maps a decoded scalar index to the raw scalar index just after the
     * raw run that produced it. Negative indices clamp to the expression
     * start; indices past the decoded count clamp to the expression end.
     * Never throws.
     */
    int decodedToRawEnd(int decodedIndex) {
        if (decodedIndex < 0) {
            return rawStart[0];
        }
        if (decodedIndex >= decodedScalarCount) {
            return rawStart[decodedScalarCount];
        }
        return rawEnd[decodedIndex];
    }

    /**
     * Maps a raw scalar index (into the template's raw content) to the
     * original source scalar offset of that raw scalar:
     * {@code templateTokenStartScalarOffset + 1 + rawScalarIndex}. When
     * the template token carries no offset information the result is
     * {@link deal.lexer.Token#UNKNOWN_OFFSET}.
     */
    int sourceOffsetAtRawScalar(int rawScalarIndex) {
        if (!hasTemplateScalarOffsets()) {
            return deal.lexer.Token.UNKNOWN_OFFSET;
        }
        return templateTokenStartScalarOffset + 1 + rawScalarIndex;
    }

    /**
     * Maps a raw scalar index (into the template's raw content) to the
     * original source column of that raw scalar:
     * {@code baseCol + 1 + rawScalarIndex}. Template literals are
     * single-line, so the line is {@link #sourceLine()} everywhere.
     */
    int sourceColumnAtRawScalar(int rawScalarIndex) {
        return baseCol + 1 + rawScalarIndex;
    }
}
