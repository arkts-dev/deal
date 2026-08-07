package deal.lexer;

/**
 * A diagnostic produced during compilation.
 * Lexer errors use the E1xxx code range.
 */
public record Diagnostic(
    String code,
    String severity,
    String message,
    String file,
    int line,
    int column
) {
    public Diagnostic {
        if (code == null) throw new IllegalArgumentException("code must not be null");
        if (severity == null) throw new IllegalArgumentException("severity must not be null");
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (file == null) throw new IllegalArgumentException("file must not be null");
        if (line < 0) throw new IllegalArgumentException("line must be >= 0, got " + line);
        if (column < 0) throw new IllegalArgumentException("column must be >= 0, got " + column);
    }

    public static Diagnostic error(String code, String message, String file, int line, int column) {
        return new Diagnostic(code, "error", message, file, line, column);
    }

    @Override
    public String toString() {
        return String.format("%s: %s: %s (%s:%d:%d)",
            severity.toUpperCase(), code, message, file, line, column);
    }
}
