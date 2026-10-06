package deal.lexer;

import deal.diagnostics.DiagnosticRange;

/**
 * One structured compiler directive event
 * (fixed-name-directive-events D1).
 *
 * <p>Events are immutable once emitted, except that the lexer assigns
 * the optional declaration anchor through {@link #withDeclarationAnchor}
 * at the ordered anchor-before-clear-before-emission transition (D3).</p>
 *
 */
public record CompilerDirective(
    int eventIndex,
    DirectiveName name,
    String rawArgument,
    String trimmedArgument,
    DiagnosticRange sourceRange,
    DiagnosticRange nameRange,
    int precedingNonCommentTokenCount,
    Integer declarationAnchorTokenIndex
) {

    public CompilerDirective {
        if (eventIndex < 0) {
            throw new IllegalArgumentException(
                "eventIndex must be >= 0, got " + eventIndex);
        }
        if (rawArgument == null) {
            throw new IllegalArgumentException("rawArgument must not be null");
        }
        if (trimmedArgument == null) {
            throw new IllegalArgumentException(
                "trimmedArgument must not be null");
        }
        if (sourceRange == null) {
            throw new IllegalArgumentException("sourceRange must not be null");
        }
        if (nameRange == null) {
            throw new IllegalArgumentException("nameRange must not be null");
        }
        if (precedingNonCommentTokenCount < 0) {
            throw new IllegalArgumentException(
                "precedingNonCommentTokenCount must be >= 0, got "
                    + precedingNonCommentTokenCount);
        }
        if (declarationAnchorTokenIndex != null
                && declarationAnchorTokenIndex < 0) {
            throw new IllegalArgumentException(
                "declarationAnchorTokenIndex must be >= 0 or null, got "
                    + declarationAnchorTokenIndex);
        }
    }

    /**
     * Returns a copy of this event with the declaration anchor assigned.
     * Only the lexer's ordered emission transition calls this (D3).
     */
    public CompilerDirective withDeclarationAnchor(int tokenIndex) {
        return new CompilerDirective(eventIndex, name, rawArgument,
            trimmedArgument, sourceRange, nameRange,
            precedingNonCommentTokenCount, tokenIndex);
    }

    /** True iff no declaration anchor has been assigned. */
    public boolean isUnanchored() {
        return declarationAnchorTokenIndex == null;
    }
}
