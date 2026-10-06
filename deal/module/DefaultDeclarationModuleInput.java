package deal.module;

import deal.ast.ProgramNode;
import deal.checker.ModuleResolver;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalModuleIdentity;

import java.util.Map;
import java.util.Objects;

public record DefaultDeclarationModuleInput(
    String sourcePath,
    String modulePath,
    ProgramNode program,
    SourceModuleLocation location,
    Map<String, DefaultPlanImport> imports,
    CanonicalRuntimeTypeDescriptor descriptors,
    ModuleResolver moduleResolver,
    Map<String, CanonicalModuleIdentity> classification
) {

    public DefaultDeclarationModuleInput {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(location, "location");
        imports = Map.copyOf(Objects.requireNonNull(imports, "imports"));
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(moduleResolver, "moduleResolver");
        classification = Map.copyOf(Objects.requireNonNull(
            classification, "classification"));
    }
}
