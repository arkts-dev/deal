package deal.semantic.ir;

import java.util.List;
import java.util.Objects;

/**
 * One module entry of the {@code deal.semantic-interface/1} index (parent
 * canonical surfaces; foundation F2):
 *
 * <pre>{@code
 * ExternalModuleInterface {
 *   moduleId, kind: IMPLEMENTATION | DECLARATION | STDLIB | HOST,
 *   imports, exports, classes, initialization: ONCE_AFTER_DEPENDENCIES
 * }
 * }</pre>
 *
 * <p>STDLIB/HOST declaration entries are derived per the pinned
 * declaration-entry rule: exports in declaration order from the
 * declaration AST's export declarations plus the {@code @jsonable}
 * synthetic exports where the export map carries them; imports from the
 * orchestrator's resolved imports; classes from exported class
 * declarations with the derived route-independent {@code constructionEntry}.
 * The parent-pinned {@code DECLARATION} kind has no producer under the
 * current orchestrator classification.</p>
 *
 * @param moduleId       the module identity; non-null
 * @param kind           the closed external-module kind; non-null
 * @param imports        the resolved import entries; non-null
 * @param exports        the export entries in declaration order; non-null
 * @param classes        the class entries; non-null
 * @param initialization the pinned initialization mode; non-null
 */
public record ExternalModuleInterface(
    ModuleId moduleId,
    ExternalModuleKind kind,
    List<ImportInterface> imports,
    List<ExportInterface> exports,
    List<ClassInterface> classes,
    InitializationMode initialization
) {

    public ExternalModuleInterface(ModuleId moduleId, ExternalModuleKind kind,
                                   List<ImportInterface> imports, List<ExportInterface> exports,
                                   List<ClassInterface> classes, InitializationMode initialization) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.imports = List.copyOf(imports);
        this.exports = List.copyOf(exports);
        this.classes = List.copyOf(classes);
        this.initialization = Objects.requireNonNull(initialization, "initialization must not be null");
    }
}
