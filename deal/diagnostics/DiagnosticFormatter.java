package deal.diagnostics;

import java.util.Objects;

/**
 * Canonical human diagnostic formatter (D8).
 *
 * <p>One diagnostic renders as one main line plus one note line per
 * attached note, in note order:</p>
 *
 * <pre>
 * &lt;file&gt;:&lt;startLine&gt;:&lt;startColumn&gt;-&lt;endLine&gt;:&lt;endColumn&gt;: &lt;SEVERITY&gt; &lt;code&gt;: &lt;message&gt; [span &lt;scalarLength&gt;]
 *     note: &lt;noteMessage&gt;
 *     note: &lt;noteMessage&gt; (at &lt;file&gt;:&lt;sL&gt;:&lt;sC&gt;-&lt;eL&gt;:&lt;eC&gt;, span &lt;n&gt;)
 * </pre>
 *
 * <p>The main line carries the complete range — file, start and end
 * positions (half-open end), and the {@code [span N]} decoded-Unicode-
 * scalar length suffix. A message-only note renders its message alone; a
 * secondary-range note appends its own range positions and scalar length.
 * The severity renders upper case ({@code ERROR} / {@code WARNING}).</p>
 *
 * <p>The formatter consumes the already-normalized carrier (D9): range
 * file, code, severity, and message are non-null, and scalar offsets and
 * lengths are consistent. This class is JDK-only and performs no
 * validation of its own; a null diagnostic is a programmer error.</p>
 */
public final class DiagnosticFormatter {

    /** No instances: the formatter is a pure static utility. */
    private DiagnosticFormatter() {
    }

    /**
     * Formats one diagnostic in the canonical human shape (D8).
     *
     * <p>Both note renderings are produced: a message-only note
     * ({@code range() == null}) renders
     * {@code     note: <noteMessage>}, and a secondary-range note renders
     * {@code     note: <noteMessage> (at <file>:<sL>:<sC>-<eL>:<eC>, span <n>)}.
     * Notes render in list order, one per line, after the main line.</p>
     *
     * @param diagnostic the normalized diagnostic to render; must not be
     *                   null
     * @return the canonical human rendering: one main line plus one
     *         indented note line per note
     * @throws NullPointerException if {@code diagnostic} is null
     */
    public static String format(CompilerDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic must not be null");
        DiagnosticRange range = diagnostic.range();
        StringBuilder sb = new StringBuilder();
        appendRangeHeader(sb, range, diagnostic.severity().toUpperCase(),
            diagnostic.code(), diagnostic.message());
        for (DiagnosticNote note : diagnostic.notes()) {
            sb.append('\n').append("    note: ").append(note.message());
            DiagnosticRange noteRange = note.range();
            if (noteRange != null) {
                sb.append(" (at ");
                appendRangePositions(sb, noteRange);
                sb.append(", span ").append(noteRange.scalarLength()).append(')');
            }
        }
        return sb.toString();
    }

    /**
     * Appends the main line:
     * {@code <file>:<startLine>:<startColumn>-<endLine>:<endColumn>: <SEVERITY> <code>: <message> [span <scalarLength>]}.
     */
    private static void appendRangeHeader(StringBuilder sb, DiagnosticRange range,
                                          String severity, String code, String message) {
        appendRangePositions(sb, range);
        sb.append(": ").append(severity)
            .append(' ').append(code)
            .append(": ").append(message)
            .append(" [span ").append(range.scalarLength()).append(']');
    }

    /** Appends {@code <file>:<startLine>:<startColumn>-<endLine>:<endColumn>}. */
    private static void appendRangePositions(StringBuilder sb, DiagnosticRange range) {
        sb.append(range.file())
            .append(':').append(range.startLine())
            .append(':').append(range.startColumn())
            .append('-').append(range.endLine())
            .append(':').append(range.endColumn());
    }
}
