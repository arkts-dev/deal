package deal.module;

import deal.ast.ProgramNode;
import deal.checker.ModuleResolver;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalModuleIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * The read-only per-module facts the
 * {@link DeclarationSemanticAnalyzer} consumes for one extern-C
 * declaration module (ISSUE-0541): the parsed declaration, its resolved
 * source location, its import surface, the compilation's canonical
 * descriptor encoder, the module-identity classification, and the
 * module resolver the analyzer builds its declaration-scope name
 * resolution on (hoisted declaration symbols, imports).
 *
 * @param sourcePath     the module's absolute normalized source path
 * @param modulePath     the module's dotted module path
 * @param program        the module's parsed declaration program
 * @param location       the module's resolved source location
 * @param imports        import alias &rarr; the provider snapshot,
 *                       insertion-ordered
 * @param descriptors    the compilation's canonical runtime descriptor
 *                       encoder
 * @param moduleResolver the compilation's module resolver (import and
 *                       class-symbol resolution for the declaration's
 *                       own scope)
 * @param classification dotted module path &rarr; canonical public
 *                       module identity (the identity layer's single
 *                       classification surface)
 */
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
