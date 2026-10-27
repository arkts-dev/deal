package deal.semantic.ir;

import deal.semantic.ClassRegistrationSeeds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The executable lowered project of {@code deal.semantic-ir/2} (schema S1):
 *
 * <pre>{@code
 * ExecutableLoweredProject {
 *   semanticProfile: DEAL_V1_5_BINARY64,
 *   interfaceIndex, modules: Map<ModuleId, LoweredModuleUnit>,   // complete implementation closure
 *   entryModule, classSeeds
 * }
 * }</pre>
 *
 * <p>The project carries the single {@code DEAL_V1_5_BINARY64} profile — no
 * constructor path admits any other value. The
 * modules map is the complete implementation closure in dependency order
 * (insertion order is preserved, so repeated dumps are byte-identical);
 * every semantic ID is globally unique within the project
 * (validator-checked, including cross-unit {@code OpId} module tags). Every
 * implementation dependency of the complete closure is lowered; no legacy
 * ABI record exists.</p>
 *
 * <p>{@code classSeeds} is the project's class registration seed set: the
 * compiler-owned builtin {@code Error} entry plus one declaration class
 * registration per declared class of every declaration module. The seeds
 * stay separate from every unit's own {@code classLayouts} — a seed layout
 * is never merged into a unit's local layouts to obtain a coverage waiver
 * — and {@link ClassRegistrationSeeds.ClassRegistration} keeps the
 * registration key and the contained layout {@code classId} as separate
 * facts. The lowering result's seed accessor is a read-only view of
 * exactly this record.</p>
 *
 */
public record ExecutableLoweredProject(
    SemanticProfile semanticProfile,
    ProjectInterfaceIndex interfaceIndex,
    Map<ModuleId, LoweredModuleUnit> modules,
    ModuleId entryModule,
    ClassRegistrationSeeds classSeeds
) {

    public ExecutableLoweredProject(SemanticProfile semanticProfile,
                                    ProjectInterfaceIndex interfaceIndex,
                                    Map<ModuleId, LoweredModuleUnit> modules,
                                    ModuleId entryModule,
                                    ClassRegistrationSeeds classSeeds) {
        this.semanticProfile = Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        this.interfaceIndex = Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
        Objects.requireNonNull(modules, "modules must not be null");
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
        this.entryModule = Objects.requireNonNull(entryModule, "entryModule must not be null");
        if (!this.modules.containsKey(entryModule)) {
            throw new IllegalArgumentException(
                "entryModule " + entryModule + " must be present in the complete implementation closure");
        }
        this.classSeeds = Objects.requireNonNull(classSeeds, "classSeeds must not be null");
    }
}
