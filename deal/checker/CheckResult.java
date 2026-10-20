package deal.checker;

import deal.ast.ClassDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.StatementNode;
import deal.diagnostics.CompilerDiagnostic;
import deal.types.Type;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CheckResult(
    Map<ExpressionNode, Type> typeMap,
    SymbolTable symbolTable,
    Map<StatementNode, SymbolTable> scopeMap,
    List<CompilerDiagnostic> diagnostics,
    Map<ClassDeclaration, SymbolTable> classScopes,
    Map<ExpressionNode, ClassDeclaration> constructedClassDecls,
    Map<ExpressionNode, Symbol.ClassSymbol> exactClassDeclarations,
    Set<ExpressionNode> narrowedReads
) {

    /**
     * Convenience constructor for callers without the narrowing facts
     * (synthetic checked facts, retained test harnesses): no identifier
     * read is a null-narrowing refinement, so the lowering emits no
     * refined-operand validation.
     */
    public CheckResult(Map<ExpressionNode, Type> typeMap, SymbolTable symbolTable,
                       Map<StatementNode, SymbolTable> scopeMap,
                       List<CompilerDiagnostic> diagnostics,
                       Map<ClassDeclaration, SymbolTable> classScopes,
                       Map<ExpressionNode, ClassDeclaration> constructedClassDecls,
                       Map<ExpressionNode, Symbol.ClassSymbol> exactClassDeclarations) {
        this(typeMap, symbolTable, scopeMap, diagnostics, classScopes,
            constructedClassDecls, exactClassDeclarations, Set.of());
    }

    /**
     * Convenience constructor for callers without a pass-1 scope map
     * (synthetic checked facts, retained test harnesses): the scope map
     * and the class-declaration scope map are empty and declared-type
     * resolution falls back to the root symbol table.
     */
    public CheckResult(Map<ExpressionNode, Type> typeMap, SymbolTable symbolTable,
                       List<CompilerDiagnostic> diagnostics) {
        this(typeMap, symbolTable, Map.of(), diagnostics, Map.of(), Map.of(),
            Map.of(), Set.of());
    }

    /**
     * Convenience constructor for callers that carry the scope maps but
     * no construction-provenance facts (synthetic checked facts, retained
     * test harnesses): the {@link #constructedClassDecls()} map is empty,
     * so consumers fall back to the name-based construction resolution.
     */
    public CheckResult(Map<ExpressionNode, Type> typeMap, SymbolTable symbolTable,
                       Map<StatementNode, SymbolTable> scopeMap,
                       List<CompilerDiagnostic> diagnostics,
                       Map<ClassDeclaration, SymbolTable> classScopes) {
        this(typeMap, symbolTable, scopeMap, diagnostics, classScopes, Map.of(),
            Map.of(), Set.of());
    }

    /**
     * Convenience constructor for callers that carry the construction
     * facts but no expression-level exact class declaration facts
     * (synthetic checked facts, retained test harnesses): the one
     * lowering keeps the identity-keyed field provider route then.
     */
    public CheckResult(Map<ExpressionNode, Type> typeMap, SymbolTable symbolTable,
                       Map<StatementNode, SymbolTable> scopeMap,
                       List<CompilerDiagnostic> diagnostics,
                       Map<ClassDeclaration, SymbolTable> classScopes,
                       Map<ExpressionNode, ClassDeclaration> constructedClassDecls) {
        this(typeMap, symbolTable, scopeMap, diagnostics, classScopes,
            constructedClassDecls, Map.of(), Set.of());
    }

    public CheckResult {
        if (typeMap == null) throw new IllegalArgumentException("typeMap must not be null");
        if (symbolTable == null) throw new IllegalArgumentException("symbolTable must not be null");
        if (scopeMap == null) throw new IllegalArgumentException("scopeMap must not be null");
        if (diagnostics == null) throw new IllegalArgumentException("diagnostics must not be null");
        if (classScopes == null) throw new IllegalArgumentException("classScopes must not be null");
        if (constructedClassDecls == null) {
            throw new IllegalArgumentException("constructedClassDecls must not be null");
        }
        if (exactClassDeclarations == null) {
            throw new IllegalArgumentException(
                "exactClassDeclarations must not be null");
        }
        narrowedReads = Set.copyOf(
            Objects.requireNonNull(narrowedReads, "narrowedReads must not be null"));
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> "error".equals(d.severity()));
    }
}
