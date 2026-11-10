package deal.lexer;

import deal.diagnostics.CompilerDiagnostic;

import java.util.List;

/** Lexer tokens and exact ranged diagnostics. Comments produce neither metadata nor events. */
public record LexResult(List<Token> tokens, List<CompilerDiagnostic> diagnostics) {

    public LexResult {
        if (tokens == null) throw new IllegalArgumentException("tokens must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
