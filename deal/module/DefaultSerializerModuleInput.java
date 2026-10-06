package deal.module;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalModuleIdentity;
import deal.types.Type;

import java.util.Map;
import java.util.Objects;

public record DefaultSerializerModuleInput(
    String sourcePath,
    String modulePath,
    ProgramNode program,
    SourceModuleLocation location,
    CheckResult checkResult,
    NameResolver nameResolver,
    Map<String, DefaultPlanImport> imports,
    Map<String, Type> exports,
    CanonicalRuntimeTypeDescriptor descriptors,
    Map<String, CanonicalModuleIdentity> classification,
    ModuleResolver moduleResolver,
    boolean isDeclarationFile,
    boolean isExternC
) {

    public DefaultSerializerModuleInput {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(location, "location");
        imports = Map.copyOf(Objects.requireNonNull(imports, "imports"));
        exports = Map.copyOf(Objects.requireNonNull(exports, "exports"));
        Objects.requireNonNull(descriptors, "descriptors");
        classification = Map.copyOf(Objects.requireNonNull(
            classification, "classification"));
        if (isDeclarationFile != (checkResult == null)) {
            throw new IllegalArgumentException(
                "checkResult is null exactly for declaration files");
        }
        if (isDeclarationFile != (nameResolver == null)) {
            throw new IllegalArgumentException(
                "nameResolver is null exactly for declaration files");
        }
        if (isDeclarationFile != (moduleResolver != null)) {
            throw new IllegalArgumentException(
                "moduleResolver is non-null exactly for declaration"
                    + " files");
        }
    }
}
