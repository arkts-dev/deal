package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The executable lowered project of {@code deal.semantic-ir/1} (schema S1):
 *
 * <pre>{@code
 * ExecutableLoweredProject {
 *   semanticProfile: DEAL_V1_2_INT32,
 *   interfaceIndex, modules: Map<ModuleId, LoweredModuleUnit>,   // complete implementation closure
 *   entryModule
 * }
 * }</pre>
 *
 * <p>The project admits only {@code DEAL_V1_2_INT32} — no constructor path
 * admits any other value; {@code LEGACY_SAFE_INT} is never lowered. The
 * modules map is the complete implementation closure in dependency order
 * (insertion order is preserved, so repeated dumps are byte-identical);
 * every semantic ID is globally unique within the project
 * (validator-checked, including cross-unit {@code OpId} module tags). A
 * legacy implementation dependency never appears as executable common IR —
 * it is represented to shared dependants by {@link ExternalModuleInterface}
 * plus the target ABI record (foundation F5/F6).</p>
 *
 * @param semanticProfile must be {@link SemanticProfile#DEAL_V1_2_INT32}
 * @param interfaceIndex  the project interface index; non-null
 * @param modules         the complete implementation closure keyed by
 *                        {@link ModuleId}, in dependency order; non-null
 * @param entryModule     the entry module identity (present in
 *                        {@code modules}); non-null
 */
public record ExecutableLoweredProject(
    SemanticProfile semanticProfile,
    ProjectInterfaceIndex interfaceIndex,
    Map<ModuleId, LoweredModuleUnit> modules,
    ModuleId entryModule
) {

    public ExecutableLoweredProject(SemanticProfile semanticProfile,
                                    ProjectInterfaceIndex interfaceIndex,
                                    Map<ModuleId, LoweredModuleUnit> modules,
                                    ModuleId entryModule) {
        this.semanticProfile = Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        if (semanticProfile != SemanticProfile.DEAL_V1_2_INT32) {
            throw new IllegalArgumentException(
                "ExecutableLoweredProject admits only DEAL_V1_2_INT32; got " + semanticProfile
                    + " (LEGACY_SAFE_INT is never lowered)");
        }
        this.interfaceIndex = Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
        Objects.requireNonNull(modules, "modules must not be null");
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
        this.entryModule = Objects.requireNonNull(entryModule, "entryModule must not be null");
        if (!this.modules.containsKey(entryModule)) {
            throw new IllegalArgumentException(
                "entryModule " + entryModule + " must be present in the complete implementation closure");
        }
    }
}
