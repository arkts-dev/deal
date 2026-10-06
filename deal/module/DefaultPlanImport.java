package deal.module;

import deal.ast.ProgramNode;
import deal.checker.SymbolTable;
import deal.types.Type;

import java.util.Map;
import java.util.Objects;

public record DefaultPlanImport(
    String sourcePath,
    String modulePath,
    ProgramNode program,
    SourceModuleLocation location,
    Map<String, Type> exports,
    SymbolTable symbolTable,
    boolean isDeclarationFile,
    boolean isExternC
) {

    public DefaultPlanImport {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(location, "location");
        exports = Map.copyOf(Objects.requireNonNull(exports, "exports"));
    }

    /**
     * True when the provider module is plan-bearing for class-default
     * references: implementation modules and extern-C declaration
     * modules publish class default plans; host-declared classes
     * produce no plan ({@code provider-versioned-default-plans} D2 —
     * the host supplies {@code <C>_defaults} at load).
     */
    public boolean publishesClassPlans() {
        return !isDeclarationFile || isExternC;
    }
}
