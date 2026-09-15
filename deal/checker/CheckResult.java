package deal.checker;

import deal.ast.ClassDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.StatementNode;
import deal.diagnostics.CompilerDiagnostic;
import deal.types.Type;

import java.util.List;
import java.util.Map;

/**
 * Result of type-checking a module.
 *
 * @param typeMap     resolved type for each expression node
 * @param symbolTable the module-level symbol table after resolution
 * @param scopeMap    the checker's per-scoped-statement symbol tables
 *                    (blocks, function declarations, for statements, try
 *                    statements — the resolution scopes each construct
 *                    created during pass 1); copied at lowering time so
 *                    the lowerer can resolve declared types of bindings
 *                    in nested scopes without retaining any
 *                    {@code NameResolver} instance (D4)
 * @param diagnostics type errors (and name-resolution errors) produced
 * @param classScopes the lexical scope in which each class declaration
 *                    was checked, in checker walk order (the
 *                    default-planning seam of ISSUE-0541: the planner
 *                    resolves every class default against exactly the
 *                    scope the checker used — the declaring lexical
 *                    context — without re-running resolution)
 */
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

    /** Returns true if any error-level diagnostics were produced. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
