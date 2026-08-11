package deal.lexer;

import deal.diagnostics.DiagnosticCode;

/**
 * A diagnostic produced during compilation.
 *
 * <p>The {@code diagnosticCode} field is the authoritative classification.
 * The {@code code} string is derived from {@link DiagnosticCode#code()}.
 * Every diagnostic emitted by the compiler must carry a registered
 * {@link DiagnosticCode}.
 */
public record Diagnostic(
    String code,
    String severity,
    String message,
    String file,
    int line,
    int column,
    DiagnosticCode diagnosticCode
) {
    public Diagnostic {
        if (code == null) throw new IllegalArgumentException("code must not be null");
        if (severity == null) throw new IllegalArgumentException("severity must not be null");
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (file == null) throw new IllegalArgumentException("file must not be null");
        if (line < 0) throw new IllegalArgumentException("line must be >= 0, got " + line);
        if (column < 0) throw new IllegalArgumentException("column must be >= 0, got " + column);
        // diagnosticCode may be null for backward compatibility (e.g. test-only codes)
    }

    /**
     * Creates a Diagnostic from a {@link DiagnosticCode} enum value.
     * This is the preferred factory method.
     */
    public static Diagnostic error(DiagnosticCode dCode, String message, String file, int line, int column) {
        return new Diagnostic(dCode.code(), "error", message, file, line, column, dCode);
    }

    /**
     * Creates a Diagnostic from a string code.
     *
     * @deprecated Use {@link #error(DiagnosticCode, String, String, int, int)} instead.
     *             This overload is kept for backward compatibility with test-only
     *             pseudo codes (e.g. E9999 in ConformanceTest).
     */
    @Deprecated
    public static Diagnostic error(String code, String message, String file, int line, int column) {
        DiagnosticCode dc = DiagnosticCode.fromCode(code);
        return new Diagnostic(code, "error", message, file, line, column, dc);
    }

    @Override
    public String toString() {
        return String.format("%s: %s: %s (%s:%d:%d)",
            severity.toUpperCase(), code, message, file, line, column);
    }
}
