package deal.codegen.llvm;

import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.StructuredBodyTable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical rendering input of the native backend ({@code llvm-native-
 * codegen-cfg-ssa} C1): the one validated lowered project with its admitted
 * companions (per-unit body tables and class-factory registries) and the
 * project-relative display name of every constituent source.
 *
 * <p>The input carries no AST, no checker object and no default-expression
 * AST: {@code ExecutableLoweredProject} plus the lowering companions are the
 * whole semantic fact set the emitter consumes. The display names are the
 * one file spelling the debug metadata and the emitted origin records carry
 * (C8, {@code DiagnosticOriginProjection}).</p>
 */
public record LlvmEmissionInput(
        ExecutableLoweredProject project,
        Map<ModuleId, StructuredBodyTable> tables,
        Map<ModuleId, ClassFactoryRegistry> registries,
        Map<String, String> sourceDisplayNames) {

    public LlvmEmissionInput {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(sourceDisplayNames,
            "sourceDisplayNames must not be null");
        tables = Map.copyOf(tables);
        registries = Map.copyOf(registries);
        sourceDisplayNames = new LinkedHashMap<>(sourceDisplayNames);
        for (ModuleId module : project.modules().keySet()) {
            if (!tables.containsKey(module)) {
                throw new IllegalArgumentException(
                    "the emission input carries no body table for " + module);
            }
            if (!registries.containsKey(module)) {
                throw new IllegalArgumentException(
                    "the emission input carries no class-factory registry for "
                        + module);
            }
        }
    }

    /** The project-relative display name of one compiled source. */
    public String displayNameOf(String sourceId) {
        String display = sourceDisplayNames.get(sourceId);
        return display == null ? sourceId : display;
    }
}
