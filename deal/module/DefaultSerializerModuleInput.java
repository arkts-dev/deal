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

/**
 * The read-only per-module facts the canonical serializer
 * {@link DefaultSemanticSerializer} consumes (ISSUE-0542, design
 * source {@code provider-versioned-default-plans} D5/D6/D9): the
 * module's parsed program, resolved location, checked facts (for
 * implementation modules), import surface, own export map, the
 * compilation's canonical descriptor encoder, the module-identity
 * classification, and the declaration-kind flags.
 *
 * <p>Checked facts and the name resolver are {@code null} exactly for
 * declaration modules (no phase-3 pass); the module resolver is
 * non-null exactly for declaration modules (the serializer rebuilds
 * the declaration-scope name resolver the analyzer uses, mirroring
 * {@link DeclarationSemanticAnalyzer}, for type-annotation
 * resolution inside nested function-expression bodies).</p>
 *
 * <p>The input is an immutable deterministic snapshot: the serializer
 * never mutates it and derives every canonical content and digest
 * exclusively from it plus the planner output.</p>
 *
 * @param sourcePath     the module's absolute normalized source path
 * @param modulePath     the module's dotted module path
 * @param program        the module's parsed program
 * @param location       the module's resolved source location
 * @param checkResult    the module's phase-3 check result, or null
 *                       for declaration modules
 * @param nameResolver   the module's phase-3 name resolver, or null
 *                       for declaration modules
 * @param imports        import alias &rarr; the provider snapshot,
 *                       insertion-ordered
 * @param exports        the module's own extracted export map
 *                       (name &rarr; resolved type)
 * @param descriptors    the compilation's canonical runtime descriptor
 *                       encoder
 * @param classification dotted module path &rarr; canonical public
 *                       module identity
 * @param moduleResolver the compilation's module resolver (declaration
 *                       modules only, null otherwise)
 * @param isDeclarationFile true when the module is a
 *                       {@code .d.deal} declaration module
 * @param isExternC      true when the module is an extern-C
 *                       declaration module
 */
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
