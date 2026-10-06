package deal.diagnostics;

/**
 * A diagnostic note attached to a {@link CompilerDiagnostic}.
 *
 * <p>The range is nullable: a message-only note (e.g. the
 * {@code internal range defect: ...} normalization notes and the
 * synthetic anchor notes) carries {@code null}, while a secondary-anchor
 * note carries the note's own range.</p>
 *
 */
public record DiagnosticNote(String message, DiagnosticRange range) {
    public DiagnosticNote {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        // range may be null: a message-only note.
    }
}
