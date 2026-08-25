package deal.lexer;

import deal.diagnostics.CompilerDiagnostic;

import java.util.List;

/**
 * Result of lexing: a list of tokens and a list of ranged
 * {@link CompilerDiagnostic} diagnostics (errors).
 */
public record LexResult(List<Token> tokens, List<CompilerDiagnostic> diagnostics) {

    public LexResult {
        if (tokens == null) throw new IllegalArgumentException("tokens must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
    }

    /** Returns true if any error-level diagnostics were produced. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
