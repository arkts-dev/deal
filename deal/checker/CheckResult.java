package deal.checker;

import deal.ast.ExpressionNode;
import deal.lexer.Diagnostic;
import deal.types.Type;

import java.util.List;
import java.util.Map;

/**
 * Result of type-checking a module.
 *
 * @param typeMap     resolved type for each expression node
 * @param symbolTable the module-level symbol table after resolution
 * @param diagnostics type errors (and name-resolution errors) produced
 */
public record CheckResult(
    Map<ExpressionNode, Type> typeMap,
    SymbolTable symbolTable,
    List<Diagnostic> diagnostics
) {
    public CheckResult {
        if (typeMap == null) throw new IllegalArgumentException("typeMap must not be null");
        if (symbolTable == null) throw new IllegalArgumentException("symbolTable must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
    }

    /** Returns true if any error-level diagnostics were produced. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
