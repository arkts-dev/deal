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
    boolean isDeclarationFile
) {

    public DefaultPlanImport {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(location, "location");
        exports = Map.copyOf(Objects.requireNonNull(exports, "exports"));
    }


    public boolean publishesClassPlans() {
        return !isDeclarationFile;
    }
}
