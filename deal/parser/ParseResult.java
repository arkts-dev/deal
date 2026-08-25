package deal.parser;

import deal.ast.ProgramNode;
import deal.diagnostics.CompilerDiagnostic;

import java.util.List;

/**
 * Result of parsing: a {@link ProgramNode} AST and a list of ranged
 * {@link CompilerDiagnostic}s.
 */
public record ParseResult(ProgramNode program, List<CompilerDiagnostic> diagnostics) {

    public ParseResult {
        if (program == null) throw new IllegalArgumentException("program must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
    }

    /** Returns true if any error-level diagnostics were produced. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
