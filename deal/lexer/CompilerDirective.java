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
 * @param eventIndex                    0-based per file, in scan order
 * @param name                          the fixed directive name, or null
 *                                      for an unknown directive (E1044 is
 *                                      emitted at scan time; raw and
 *                                      trimmed arguments are empty)
 * @param rawArgument                   every pre-terminator scalar after
 *                                      the fixed name; "" for unknown
 * @param trimmedArgument               raw with only U+0020 and U+0009
 *                                      stripped from both ends; "" for
 *                                      unknown
 * @param sourceRange                   complete directive comment: first
 *                                      '/' .. first terminator scalar or
 *                                      EOF, half-open
 * @param nameRange                     fixed-name range (excluding '@');
 *                                      recovered name range or
 *                                      '@'+adjacent run for unknown
 * @param precedingNonCommentTokenCount non-comment tokens emitted before
 *                                      this event
 * @param declarationAnchorTokenIndex   the index of the emitted token
 *                                      the event anchors to; null =
 *                                      unanchored
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
