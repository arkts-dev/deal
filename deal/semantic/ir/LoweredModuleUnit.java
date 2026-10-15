package deal.semantic.ir;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The lowered module unit of {@code deal.semantic-ir/2} (schema S1):
 *
 * <pre>{@code
 * LoweredModuleUnit {
 *   formatVersion: "deal.semantic-ir/2",
 *   semanticProfile: DEAL_V1_2_INT32,           // the only admitted value
 *   moduleId, interfaceHash, loweringContextHash,
 *   requiredCapabilities,
 *   constructCoverage: Map<ConstructKind, [SemanticOpKind]>,   // enum-keyed; recorded at lowering start
 *   classLayouts, functions, moduleInit, exportPlan,
 *   functionBindings: Map<FunctionAllocationIdentity, FunctionExecutionBinding>
 *   ops: [SemanticOp],                           // the unit's produced operations in source order
 *   bodyTable, classFactories, jsonDefaults, classBindingFacts
 * }
 * }</pre>
 *
 * <p>A unit contains no AST node, {@code SymbolTable}, {@code CheckResult},
 * or identity-keyed map; {@code constructCoverage} is enum-keyed — its keys
 * are closed {@link ConstructKind} enum values, never AST or checker
 * objects. Every semantic ID is globally unique within one
 * {@link ExecutableLoweredProject} (validator-checked). The
 * {@code constructCoverage} fact is recorded at lowering start by the unit
 * producer from the module's manifest reachable-construct rows and is
 * consumed by R-COVERAGE; the {@code std/time.nowMillis} row is an
 * ordinary key since K7 (its produced {@code STDLIB_CALL}/boundary ops
 * evidence it), and a module whose manifest claims
 * {@code STDLIB_TIME_CONFLICT} is never lowered at all.</p>
 *
 * <p>The unit is the single owner of the structural companions the
 * transport and the composed admission need: {@link #bodyTable()} (both
 * block-membership directions), {@link #classFactories()} (the
 * construction-entry → {@code CLASS_FACTORY} registry), {@link
 * #jsonDefaults()} (the per-site {@code JSON_FROM_CLASS} default children)
 * and {@link #classBindingFacts()} (the parameter/import initialization
 * facts). They are mandatory facts of every unit: a unit without class
 * content carries present-empty records, never null or absent. The
 * lowering-result table, registry and seed accessors are read-only views
 * of exactly these records, never independent authorities.</p>
 *
 * <p>{@code loweringContextHash} is the pinned digest
 * {@code SHA-256(canonical JSON {semanticProfile, capabilityRegistryHash})}
 * computed at lowering start over the invocation's facts (see
 * {@link LoweringContext}); the validator recomputes it from its
 * comparison facts and rejects a mismatch (R-PROFILE). The digest helper
 * implementing the formula lives with the canonicalizer (T3).</p>
 *
 * <p>{@code ops} is the unit's produced operations in source order — the
 * validator's pinned input (module order, then op order): R-COVERAGE
 * compares the produced op kinds against the recorded
 * {@code constructCoverage} obligations, and the per-op rules
 * (R-ENUM, R-POLICY-KIND, R-BOUNDARY-TRIPLE, R-ELIDED-PLACEMENT,
 * R-FUNCTION-BINDING, R-ALIAS-CYCLE, R-TOKEN-REUSE, R-PRIVATE-STEP,
 * R-RESERVED-NAME, R-DIGEST) walk exactly this list.</p>
 *
 */
public record LoweredModuleUnit(
    String formatVersion,
    SemanticProfile semanticProfile,
    ModuleId moduleId,
    String interfaceHash,
    String loweringContextHash,
    Set<SemanticCapability> requiredCapabilities,
    Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
    Map<ClassId, ClassLayout> classLayouts,
    Map<FunctionId, LoweredFunction> functions,
    ModuleInitPlan moduleInit,
    ExportPlan exportPlan,
    Map<FunctionAllocationIdentity, FunctionExecutionBinding> functionBindings,
    List<SemanticOp> ops,
    StructuredBodyTable bodyTable,
    ClassFactoryRegistry classFactories,
    JsonDefaultChildTable jsonDefaults,
    ClassBindingFacts classBindingFacts
) {

    /** The pinned IR format version string. */
    public static final String FORMAT_VERSION = "deal.semantic-ir/2";

    public LoweredModuleUnit(String formatVersion, SemanticProfile semanticProfile,
                             ModuleId moduleId, String interfaceHash,
                             String loweringContextHash,
                             Set<SemanticCapability> requiredCapabilities,
                             Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
                             Map<ClassId, ClassLayout> classLayouts,
                             Map<FunctionId, LoweredFunction> functions,
                             ModuleInitPlan moduleInit, ExportPlan exportPlan,
                             Map<FunctionAllocationIdentity, FunctionExecutionBinding> functionBindings,
                             List<SemanticOp> ops,
                             StructuredBodyTable bodyTable,
                             ClassFactoryRegistry classFactories,
                             JsonDefaultChildTable jsonDefaults,
                             ClassBindingFacts classBindingFacts) {
        this.formatVersion = Objects.requireNonNull(formatVersion, "formatVersion must not be null");
        if (!FORMAT_VERSION.equals(formatVersion)) {
            throw new IllegalArgumentException(
                "formatVersion must be \"" + FORMAT_VERSION + "\", got \"" + formatVersion + "\"");
        }
        this.semanticProfile = Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        if (semanticProfile != SemanticProfile.DEAL_V1_2_INT32) {
            throw new IllegalArgumentException(
                "LoweredModuleUnit admits only DEAL_V1_2_INT32; got " + semanticProfile
                    + " (LEGACY_SAFE_INT is never lowered)");
        }
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.interfaceHash = Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        this.loweringContextHash = Objects.requireNonNull(loweringContextHash, "loweringContextHash must not be null");

        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null");
        EnumSet<SemanticCapability> capabilitiesCopy = EnumSet.noneOf(SemanticCapability.class);
        capabilitiesCopy.addAll(requiredCapabilities);
        this.requiredCapabilities = Collections.unmodifiableSet(capabilitiesCopy);

        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Map<ConstructKind, List<SemanticOpKind>> coverageCopy = new EnumMap<>(ConstructKind.class);
        for (Map.Entry<ConstructKind, List<SemanticOpKind>> entry : constructCoverage.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "constructCoverage keys must not be null");
            coverageCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.constructCoverage = Collections.unmodifiableMap(coverageCopy);

        Objects.requireNonNull(classLayouts, "classLayouts must not be null");
        this.classLayouts = Collections.unmodifiableMap(new LinkedHashMap<>(classLayouts));

        Objects.requireNonNull(functions, "functions must not be null");
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));

        this.moduleInit = Objects.requireNonNull(moduleInit, "moduleInit must not be null");
        this.exportPlan = Objects.requireNonNull(exportPlan, "exportPlan must not be null");

        Objects.requireNonNull(functionBindings, "functionBindings must not be null");
        this.functionBindings = Collections.unmodifiableMap(new LinkedHashMap<>(functionBindings));

        Objects.requireNonNull(ops, "ops must not be null");
        this.ops = List.copyOf(ops);

        this.bodyTable = Objects.requireNonNull(bodyTable, "bodyTable must not be null");
        this.classFactories = Objects.requireNonNull(classFactories,
            "classFactories must not be null");
        this.jsonDefaults = Objects.requireNonNull(jsonDefaults,
            "jsonDefaults must not be null");
        this.classBindingFacts = Objects.requireNonNull(classBindingFacts,
            "classBindingFacts must not be null");
    }
}
