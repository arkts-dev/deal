package deal.lexer;

import deal.diagnostics.CompilerDiagnostic;

import java.util.List;

/**
 * Result of lexing: a list of tokens, a list of ranged
 * {@link CompilerDiagnostic} diagnostics (errors), and the structured
 * directive events ordered by {@link CompilerDirective#eventIndex()}
 * (fixed-name-directive-events D1).
 */
public record LexResult(List<Token> tokens, List<CompilerDiagnostic> diagnostics,
                        List<CompilerDirective> directiveEvents) {

    public LexResult {
        if (tokens == null) throw new IllegalArgumentException("tokens must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
        if (directiveEvents == null) throw new IllegalArgumentException("directiveEvents must not be null");
    }

    /**
     * Backward-compatible constructor yielding an empty event list for
     * defensive/test-only inputs (D1).
     */
    public LexResult(List<Token> tokens, List<CompilerDiagnostic> diagnostics) {
        this(tokens, diagnostics, List.of());
    }

    /** Returns true if any error-level diagnostics were produced. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
