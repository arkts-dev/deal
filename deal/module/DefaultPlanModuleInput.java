package deal.module;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalModuleIdentity;

import java.util.Map;
import java.util.Objects;

public record DefaultPlanModuleInput(
    String sourcePath,
    String modulePath,
    ProgramNode program,
    SourceModuleLocation location,
    CheckResult checkResult,
    NameResolver nameResolver,
    Map<String, DefaultPlanImport> imports,
    CanonicalRuntimeTypeDescriptor descriptors,
    Map<String, CanonicalModuleIdentity> classification
) {

    public DefaultPlanModuleInput {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(checkResult, "checkResult");
        Objects.requireNonNull(nameResolver, "nameResolver");
        imports = Map.copyOf(Objects.requireNonNull(imports, "imports"));
        Objects.requireNonNull(descriptors, "descriptors");
        classification = Map.copyOf(Objects.requireNonNull(
            classification, "classification"));
    }
}
