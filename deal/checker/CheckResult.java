package deal.checker;

import deal.ast.ClassDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.StatementNode;
import deal.diagnostics.CompilerDiagnostic;
import deal.types.Type;

import java.util.List;
import java.util.Map;

public record CheckResult(
    Map<ExpressionNode, Type> typeMap,
    SymbolTable symbolTable,
    Map<StatementNode, SymbolTable> scopeMap,
    List<CompilerDiagnostic> diagnostics,
    Map<ClassDeclaration, SymbolTable> classScopes
) {

    /**
     * Convenience constructor for callers without a pass-1 scope map
     * (synthetic checked facts, retained test harnesses): the scope map
     * and the class-declaration scope map are empty and declared-type
     * resolution falls back to the root symbol table.
     */
    public CheckResult(Map<ExpressionNode, Type> typeMap, SymbolTable symbolTable,
                       List<CompilerDiagnostic> diagnostics) {
        this(typeMap, symbolTable, Map.of(), diagnostics, Map.of());
    }

    public CheckResult {
        if (typeMap == null) throw new IllegalArgumentException("typeMap must not be null");
        if (symbolTable == null) throw new IllegalArgumentException("symbolTable must not be null");
        if (scopeMap == null) throw new IllegalArgumentException("scopeMap must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
        if (classScopes == null) throw new IllegalArgumentException("classScopes must not be null");
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
