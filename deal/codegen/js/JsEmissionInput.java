package deal.codegen.js;

import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NamespaceRegistrations;
import deal.semantic.ir.StructuredBodyTable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical rendering input of the JavaScript semantic-IR emitter
 * (design source {@code wiki:javascript-semantic-ir-emission},
 * "renderer_boundary"):
 *
 * <pre>{@code
 * JsEmissionInput {
 *   project: ExecutableLoweredProject,
 *   tables: Map<ModuleId, StructuredBodyTable>,
 *   registries: Map<ModuleId, ClassFactoryRegistry>,
 *   seeds: ClassRegistrationSeeds,
 *   namespaces: NamespaceRegistrations,
 *   declarations: RuntimeDeclarationSurface,
 *   sourceFiles: ordered Map<ModuleId, JsSourceFile>,
 *   sourceMap: boolean
 * }
 * }</pre>
 *
 * <p>{@code sourceMap} is the internal rendering selection, not a public
 * no-map mode: the v1.3 default debugging artifacts are always produced by
 * the public compile, and an ineffective selection is an internal
 * comparison rendering whose artifacts carry no source comment and no
 * mapping list while keeping every embedded runtime location.</p>
 *
 * <p>The seam carries exactly one validated, lowered, executable project
 * with its admitted structural companions. No AST, checker
 * {@code Type}/{@code Symbol}/{@code CheckResult}, frontend-identity map or
 * executable default-expression AST occurs in the input; seeds and
 * namespaces are admitted structural companions, not permission to pass
 * their producers. Every implementation unit of the project closure
 * carries its block-membership table, its class-factory registry, and its
 * authoritative source identity; declaration units have no artifact and
 * are not part of the project closure.</p>
 *
 */
public record JsEmissionInput(
        ExecutableLoweredProject project,
        Map<ModuleId, StructuredBodyTable> tables,
        Map<ModuleId, ClassFactoryRegistry> registries,
        ClassRegistrationSeeds seeds,
        NamespaceRegistrations namespaces,
        RuntimeDeclarationSurface declarations,
        Map<ModuleId, JsSourceFile> sourceFiles,
        boolean sourceMap) {

    public JsEmissionInput {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(seeds, "seeds must not be null");
        Objects.requireNonNull(namespaces, "namespaces must not be null");
        Objects.requireNonNull(declarations, "declarations must not be null");
        Objects.requireNonNull(sourceFiles, "sourceFiles must not be null");

        // Exactly one table, one registry and one authoritative source
        // identity per implementation unit of the closure, in project
        // dependency order (the result order is the project order, never an
        // unspecified map iteration order).
        Map<ModuleId, StructuredBodyTable> orderedTables = new LinkedHashMap<>();
        Map<ModuleId, ClassFactoryRegistry> orderedRegistries =
            new LinkedHashMap<>();
        Map<ModuleId, JsSourceFile> orderedSources = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                : project.modules().entrySet()) {
            ModuleId module = entry.getKey();
            StructuredBodyTable table = tables.get(module);
            if (table == null) {
                throw new IllegalArgumentException("the implementation unit '"
                    + module.path() + "' carries no block-membership table:"
                    + " every artifact needs its own produced table");
            }
            ClassFactoryRegistry registry = registries.get(module);
            if (registry == null) {
                throw new IllegalArgumentException("the implementation unit '"
                    + module.path() + "' carries no class-factory registry:"
                    + " every artifact needs its own produced registry");
            }
            JsSourceFile sourceFile = sourceFiles.get(module);
            if (sourceFile == null) {
                throw new IllegalArgumentException("the implementation unit '"
                    + module.path() + "' carries no authoritative source"
                    + " identity: every artifact maps to its own source file,"
                    + " empty units included");
            }
            orderedTables.put(module, table);
            orderedRegistries.put(module, registry);
            orderedSources.put(module, sourceFile);
        }
        if (tables.size() != orderedTables.size()
                || registries.size() != orderedRegistries.size()
                || sourceFiles.size() != orderedSources.size()) {
            throw new IllegalArgumentException("the emission input carries"
                + " table/registry/source entries outside the implementation"
                + " closure: an artifact exists per implementation unit only"
                + " and implementation units only (declaration units have no"
                + " member)");
        }
        tables = Collections.unmodifiableMap(orderedTables);
        registries = Collections.unmodifiableMap(orderedRegistries);
        sourceFiles = Collections.unmodifiableMap(orderedSources);
    }

    /** The entry module's authoritative source identity. */
    public JsSourceFile entrySourceFile() {
        return sourceFiles.get(project.entryModule());
    }
}
