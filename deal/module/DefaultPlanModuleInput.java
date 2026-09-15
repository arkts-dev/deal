package deal.module;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalModuleIdentity;

import java.util.Map;
import java.util.Objects;

/**
 * The read-only per-module facts the
 * {@link DefaultSemanticPlanner} consumes for one implementation module
 * (ISSUE-0541): the module's checked facts (phase-3 results — type
 * map, symbol tables, class declaration scopes), its parsed program,
 * its resolved source location, its import surface, the compilation's
 * canonical descriptor encoder, and the compilation's module-path
 * classification.
 *
 * <p>The input is an immutable deterministic snapshot: the planner
 * never mutates it and derives every plan and occurrence exclusively
 * from it.</p>
 *
 * @param sourcePath     the module's absolute normalized source path
 * @param modulePath     the module's dotted module path
 * @param program        the module's parsed program
 * @param location       the module's resolved source location
 * @param checkResult    the module's phase-3 check result (never null)
 * @param nameResolver   the module's phase-3 name resolver (never null)
 * @param imports        import alias &rarr; the provider snapshot,
 *                       insertion-ordered
 * @param descriptors    the compilation's canonical runtime descriptor
 *                       encoder
 * @param classification dotted module path &rarr; canonical public
 *                       module identity (the identity layer's single
 *                       classification surface)
 */
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
