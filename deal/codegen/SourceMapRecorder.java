package deal.codegen;

import deal.semantic.ir.ModuleId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The project-wide source-map recorder of the shared production emitters
 * (LuaJIT, JVM and JavaScript): one {@link SourceMapGenerator} per
 * constituent implementation module of the lowered project, so every emitted
 * artifact — the one shared project artifact of LuaJIT/JVM or the one
 * CommonJS artifact per implementation unit of JavaScript — maps back to its
 * own source file through that module's {@code <module>.deal.map.json}
 * sidecar.
 *
 * <p>The recorder also carries the per-module display source names that the
 * emitted source comments and the sidecars share: the project-relative
 * source path when the relativization stays inside the project, else the raw
 * source path (the {@link SourceMapPaths} contract). An op registered to a
 * module whose display name is unknown falls back to the origin's own file
 * string, so the comment and the sidecar never disagree — the recorder is
 * the single source-map authority of one production emission.</p>
 *
 * <p>The v1.4 default debugging artifacts install one recorder on every
 * successful production emission: the emitted source comments and the
 * staged sidecars come from this single recorder, and the accepted
 * {@code --source-map} request spelling selects nothing. Only a direct
 * emitter API call may pass no recorder (the recorder-less emission); no
 * product compile takes that route.</p>
 */
public final class SourceMapRecorder {

    private final Map<ModuleId, String> displaySourceNames = new LinkedHashMap<>();
    private final Map<ModuleId, SourceMapGenerator> perModule = new LinkedHashMap<>();

    /**
     * Creates one recorder over the project's display source names keyed by
     * module identity (dependency order preserved).
     */
    public SourceMapRecorder(Map<ModuleId, String> displaySourceNames) {
        Objects.requireNonNull(displaySourceNames,
            "displaySourceNames must not be null");
        this.displaySourceNames.putAll(displaySourceNames);
    }

    /**
     * The one display spelling of a module's source file used by both its
     * emitted source comments and its sidecar's {@code source} field: the
     * configured project-relative name, else the origin's own file string.
     */
    public String displayName(ModuleId module, String fallbackSourceId) {
        String display = module == null ? null : displaySourceNames.get(module);
        return display != null ? display : fallbackSourceId;
    }

    /** Records one generated position to source position mapping. */
    public void record(ModuleId module, int generatedLine, int generatedColumn,
                       int sourceLine, int sourceColumn) {
        Objects.requireNonNull(module, "module must not be null");
        perModule.computeIfAbsent(module, key -> new SourceMapGenerator())
            .emitStatement(generatedLine, generatedColumn,
                sourceLine, sourceColumn);
    }

    /**
     * The recorded generator of one module, or {@code null} when the module
     * emitted no mappable op (a clean module still receives its sidecar with
     * an empty mappings array).
     */
    public SourceMapGenerator generatorFor(ModuleId module) {
        return perModule.get(module);
    }

    /** The per-module generators recorded so far, in first-recorded order. */
    public Map<ModuleId, SourceMapGenerator> perModule() {
        return Collections.unmodifiableMap(perModule);
    }

    /** Whether any mapping was recorded. */
    public boolean isEmpty() {
        return perModule.isEmpty();
    }
}
