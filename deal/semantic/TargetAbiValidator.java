package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The stage-time mixed-edge validator (foundation F6): the single
 * consumer that enforces {@code TargetModuleAbi} record completeness and
 * consistency at validation time, after the retained side completed each
 * legacy dependency's realization fields from the actually staged
 * artifacts and before any dependent shared module's emission is
 * consumed.
 *
 * <pre>{@code
 * TargetAbiValidator.validate(stagedSet, routePlan, abiEdges, interfaceIndex, projectProfile)
 * }</pre>
 *
 * <p><b>Checked facts (closed).</b> The validator walks, in one
 * deterministic pass, first the mixed-edge coverage and then every ABI
 * record in {@code abiEdges} order:</p>
 *
 * <ol>
 *   <li><b>Mixed-edge coverage</b> — every SHARED-routed module's
 *       IMPLEMENTATION import resolving to a LEGACY-routed module is a
 *       mixed edge and must carry an ABI record
 *       ({@code ABI_EDGE_MISSING});</li>
 *   <li>the record's module has an interface-index entry
 *       ({@code ABI_MODULE_NOT_IN_INDEX}) and a route entry
 *       ({@code ABI_MODULE_NOT_ROUTED});</li>
 *   <li><b>artifactOwner matches the route entry</b> — a
 *       {@code RETAINED_LUAJIT}/{@code RETAINED_JVM} record requires a
 *       LEGACY route on the plan's target, a {@code SHARED} record
 *       requires a SHARED route, and the record's target must equal the
 *       plan's target ({@code ABI_OWNER_ROUTE_MISMATCH});</li>
 *   <li><b>semanticProfile equals the project profile</b>
 *       ({@code ABI_PROFILE_MISMATCH});</li>
 *   <li><b>loadKey</b> present ({@code ABI_MISSING_LOAD_KEY}) and
 *       resolving to a staged artifact of the staged set
 *       ({@code ABI_LOAD_KEY_UNRESOLVED});</li>
 *   <li><b>initializationEntry</b> present
 *       ({@code ABI_MISSING_INITIALIZATION_ENTRY});</li>
 *   <li><b>exportedDescriptors</b> present with one entry per imported
 *       export whose keys equal the index's export names
 *       ({@code ABI_DESCRIPTOR_MISMATCH});</li>
 *   <li><b>syncInvocationEntries</b> — one entry per imported export,
 *       keys equal to the index's export names
 *       ({@code ABI_MISSING_SYNC_INVOCATION_ENTRY} for a missing entry,
 *       {@code ABI_SYNC_ENTRY_CONTRADICTS_INDEX} for an entry naming an
 *       export the index does not carry);</li>
 *   <li><b>functionWrapperAbi</b> — present per imported function
 *       ({@code ABI_MISSING_FUNCTION_WRAPPER}) with no wrapper naming a
 *       non-export ({@code ABI_WRAPPER_CONTRADICTS_INDEX});</li>
 *   <li><b>asyncLinkageRecords</b> — one entry per async export invoked
 *       across the shared edge ({@code ABI_MISSING_ASYNC_LINKAGE}), each
 *       consistent: {@code calleeModuleId} equals the record's module,
 *       {@code exportName} is an async export of that module, and no
 *       export is linked twice ({@code ABI_ASYNC_LINKAGE_MISMATCH});</li>
 *   <li><b>class factory/layout ABI</b> — present for each exported
 *       class and matching {@code ClassInterface}: the factory map must
 *       carry every class with the T8-derived {@code constructionEntry}
 *       identifier ({@code ABI_CLASS_FACTORY_MISMATCH}) and the layout
 *       map must carry every class with the declaration-order
 *       {@code FieldInterface} list ({@code ABI_CLASS_LAYOUT_MISMATCH}).</li>
 * </ol>
 *
 * <p>Any missing or mismatched field raises E6005 through
 * {@link FailureContractRegistry} with
 * {@code LoweringFailureDetail{module, capability: MODULES, validatorRule,
 * semanticProfile, irVersion, origin}} — ABI mismatch after compatibility
 * was claimed (parent D11; the registry's named producer for that
 * coverage item). The first failure in deterministic order (edge
 * coverage in plan dependency order, then records in list order, then
 * checks in the pinned order above) is reported; validation is a single
 * deterministic pass with no mutation and no retry.</p>
 *
 * <p>Function/async classification of an index export is the pinned
 * top-level canonical-type-text grammar (foundation F2): an export's
 * {@code declaredType} starting with {@code "("} is a sync function form
 * and one starting with {@code "async"} is an async function form; every
 * other export is not a function (export declarations are function or
 * class declarations, so top-level array/nullable forms never occur).
 * The interface index's initialization is not re-checked here — the
 * plan-time index-fact checks (F4) already pinned it.</p>
 */
public final class TargetAbiValidator {

    private TargetAbiValidator() {
        // Static validator; no instances.
    }

    /** The pinned IR version carried by every ABI-failure detail. */
    public static final String IR_VERSION = "deal.semantic-ir/1";

    /** A mixed edge (SHARED module importing a LEGACY implementation module) without an ABI record. */
    public static final String ABI_EDGE_MISSING = "ABI_EDGE_MISSING";
    /** An ABI record whose module has no interface-index entry. */
    public static final String ABI_MODULE_NOT_IN_INDEX = "ABI_MODULE_NOT_IN_INDEX";
    /** An ABI record whose module has no route entry. */
    public static final String ABI_MODULE_NOT_ROUTED = "ABI_MODULE_NOT_ROUTED";
    /** An ABI record whose target or artifact owner contradicts the route entry. */
    public static final String ABI_OWNER_ROUTE_MISMATCH = "ABI_OWNER_ROUTE_MISMATCH";
    /** An ABI record whose semantic profile differs from the project profile. */
    public static final String ABI_PROFILE_MISMATCH = "ABI_PROFILE_MISMATCH";
    /** An ABI record with no load key. */
    public static final String ABI_MISSING_LOAD_KEY = "ABI_MISSING_LOAD_KEY";
    /** An ABI record whose load key names no staged artifact. */
    public static final String ABI_LOAD_KEY_UNRESOLVED = "ABI_LOAD_KEY_UNRESOLVED";
    /** An ABI record with no initialization entry. */
    public static final String ABI_MISSING_INITIALIZATION_ENTRY =
        "ABI_MISSING_INITIALIZATION_ENTRY";
    /** An ABI record whose exported descriptors do not equal the index's export names. */
    public static final String ABI_DESCRIPTOR_MISMATCH = "ABI_DESCRIPTOR_MISMATCH";
    /** An ABI record missing a sync-invocation entry for an imported export. */
    public static final String ABI_MISSING_SYNC_INVOCATION_ENTRY =
        "ABI_MISSING_SYNC_INVOCATION_ENTRY";
    /** An ABI record carrying a sync-invocation entry the index does not name. */
    public static final String ABI_SYNC_ENTRY_CONTRADICTS_INDEX =
        "ABI_SYNC_ENTRY_CONTRADICTS_INDEX";
    /** An ABI record missing a wrapper/signature entry for an imported function. */
    public static final String ABI_MISSING_FUNCTION_WRAPPER = "ABI_MISSING_FUNCTION_WRAPPER";
    /** An ABI record carrying a wrapper entry the index does not name. */
    public static final String ABI_WRAPPER_CONTRADICTS_INDEX =
        "ABI_WRAPPER_CONTRADICTS_INDEX";
    /** An ABI record missing an async-linkage entry for an async export. */
    public static final String ABI_MISSING_ASYNC_LINKAGE = "ABI_MISSING_ASYNC_LINKAGE";
    /** An ABI record whose async-linkage entry is inconsistent with the record/callee. */
    public static final String ABI_ASYNC_LINKAGE_MISMATCH = "ABI_ASYNC_LINKAGE_MISMATCH";
    /** An ABI record whose class factory map contradicts the index's ClassInterface records. */
    public static final String ABI_CLASS_FACTORY_MISMATCH = "ABI_CLASS_FACTORY_MISMATCH";
    /** An ABI record whose class layout map contradicts the index's ClassInterface records. */
    public static final String ABI_CLASS_LAYOUT_MISMATCH = "ABI_CLASS_LAYOUT_MISMATCH";

    /**
     * Validates every mixed edge of one staged publication: the edge
     * coverage and every ABI record against the staged artifact set, the
     * route plan, the interface index, and the project profile.
     *
     * @param stagedSet     the staged artifact set the records' load keys
     *                      resolve against; non-null
     * @param routePlan     the deterministic route plan of the invocation;
     *                      non-null
     * @param abiEdges      the complete ABI records — plan-time records
     *                      completed during staging plus emitted
     *                      SHARED-owner manifests; non-null
     * @param interfaceIndex the project interface index; non-null
     * @param projectProfile the invocation's project semantic profile; non-null
     * @return empty on pass; otherwise the first E6005 in deterministic
     *         order (capability MODULES)
     */
    public static Optional<CompilerDiagnostic> validate(
            StagedArtifactSet stagedSet,
            ModuleRoutePlan routePlan,
            List<TargetModuleAbi> abiEdges,
            ProjectInterfaceIndex interfaceIndex,
            SemanticProfile projectProfile) {
        Objects.requireNonNull(stagedSet, "stagedSet must not be null");
        Objects.requireNonNull(routePlan, "routePlan must not be null");
        Objects.requireNonNull(abiEdges, "abiEdges must not be null");
        Objects.requireNonNull(interfaceIndex, "interfaceIndex must not be null");
        Objects.requireNonNull(projectProfile, "projectProfile must not be null");

        // 1. Mixed-edge coverage, in plan dependency order.
        for (Map.Entry<ModuleId, ModuleRoute> entry : routePlan.entries().entrySet()) {
            if (entry.getValue() != ModuleRoute.SHARED) {
                continue;
            }
            ExternalModuleInterface sharedEntry = interfaceIndex.modules().get(entry.getKey());
            if (sharedEntry == null) {
                return Optional.of(fail(entry.getKey().toString(), ABI_MODULE_NOT_IN_INDEX,
                    projectProfile));
            }
            for (ResolvedImport resolvedImport : sharedEntry.imports()) {
                if (resolvedImport.kind() != ExternalModuleKind.IMPLEMENTATION) {
                    continue; // stdlib/host entries are index entries, never route entries
                }
                if (routePlan.entries().get(resolvedImport.resolvedModuleId()) != ModuleRoute.LEGACY) {
                    continue; // shared-to-shared edges resolve through EXTERNAL_ENTRY, no ABI record
                }
                if (!hasRecord(abiEdges, resolvedImport.resolvedModuleId())) {
                    return Optional.of(fail(entry.getKey().toString(), ABI_EDGE_MISSING,
                        projectProfile));
                }
            }
        }

        // 2. Per-record checks in abiEdges order.
        for (TargetModuleAbi abi : abiEdges) {
            Objects.requireNonNull(abi, "abiEdges entries must not be null");
            Optional<CompilerDiagnostic> failure = validateRecord(
                stagedSet, routePlan, abi, interfaceIndex, projectProfile);
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    private static boolean hasRecord(List<TargetModuleAbi> abiEdges, ModuleId moduleId) {
        for (TargetModuleAbi abi : abiEdges) {
            if (abi.moduleId().equals(moduleId)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<CompilerDiagnostic> validateRecord(
            StagedArtifactSet stagedSet,
            ModuleRoutePlan routePlan,
            TargetModuleAbi abi,
            ProjectInterfaceIndex interfaceIndex,
            SemanticProfile projectProfile) {
        String module = abi.moduleId().toString();

        ExternalModuleInterface indexEntry = interfaceIndex.modules().get(abi.moduleId());
        if (indexEntry == null) {
            return Optional.of(fail(module, ABI_MODULE_NOT_IN_INDEX, projectProfile));
        }

        ModuleRoute route = routePlan.entries().get(abi.moduleId());
        if (route == null) {
            return Optional.of(fail(module, ABI_MODULE_NOT_ROUTED, projectProfile));
        }

        if (abi.target() != routePlan.target()
                || !ownerMatchesRoute(abi.artifactOwner(), route)) {
            return Optional.of(fail(module, ABI_OWNER_ROUTE_MISMATCH, projectProfile));
        }

        if (abi.semanticProfile() != projectProfile) {
            return Optional.of(fail(module, ABI_PROFILE_MISMATCH, projectProfile));
        }

        if (abi.loadKey() == null) {
            return Optional.of(fail(module, ABI_MISSING_LOAD_KEY, projectProfile));
        }
        if (!stagedSet.contains(abi.loadKey())) {
            return Optional.of(fail(module, ABI_LOAD_KEY_UNRESOLVED, projectProfile));
        }

        if (abi.initializationEntry() == null) {
            return Optional.of(fail(module, ABI_MISSING_INITIALIZATION_ENTRY, projectProfile));
        }

        Set<String> exportNames = exportNames(indexEntry);
        Set<String> asyncExportNames = new LinkedHashSet<>();

        if (!new LinkedHashSet<>(abi.exportedDescriptors().keySet()).equals(exportNames)) {
            return Optional.of(fail(module, ABI_DESCRIPTOR_MISMATCH, projectProfile));
        }

        Set<String> syncNames = abi.syncInvocationEntries() == null
            ? Set.of() : abi.syncInvocationEntries().keySet();
        for (String exportName : exportNames) {
            if (!syncNames.contains(exportName)) {
                return Optional.of(fail(module, ABI_MISSING_SYNC_INVOCATION_ENTRY,
                    projectProfile));
            }
        }
        if (!syncNames.equals(exportNames)) {
            return Optional.of(fail(module, ABI_SYNC_ENTRY_CONTRADICTS_INDEX, projectProfile));
        }

        Set<String> wrapperNames = abi.functionWrapperAbi() == null
            ? Set.of() : abi.functionWrapperAbi().keySet();
        for (String exportName : exportNames) {
            String declaredType = declaredType(indexEntry, exportName);
            if (isFunctionForm(declaredType) && !wrapperNames.contains(exportName)) {
                return Optional.of(fail(module, ABI_MISSING_FUNCTION_WRAPPER, projectProfile));
            }
            if (isAsyncForm(declaredType)) {
                asyncExportNames.add(exportName);
            }
        }
        for (String wrapperName : wrapperNames) {
            if (!exportNames.contains(wrapperName)) {
                return Optional.of(fail(module, ABI_WRAPPER_CONTRADICTS_INDEX, projectProfile));
            }
        }

        List<ExternalAsyncLink> links = abi.asyncLinkageRecords() == null
            ? List.of() : abi.asyncLinkageRecords();
        Set<String> linkedExports = new LinkedHashSet<>();
        for (ExternalAsyncLink link : links) {
            if (!link.calleeModuleId().equals(abi.moduleId())
                    || !asyncExportNames.contains(link.exportName())
                    || !linkedExports.add(link.exportName())) {
                return Optional.of(fail(module, ABI_ASYNC_LINKAGE_MISMATCH, projectProfile));
            }
        }
        for (String asyncExport : asyncExportNames) {
            if (!linkedExports.contains(asyncExport)) {
                return Optional.of(fail(module, ABI_MISSING_ASYNC_LINKAGE, projectProfile));
            }
        }

        Set<ClassId> indexClasses = new LinkedHashSet<>();
        for (ClassInterface classInterface : indexEntry.classes()) {
            indexClasses.add(classInterface.classId());
        }
        Set<ClassId> factoryClasses = new LinkedHashSet<>(abi.classFactoryAbi().keySet());
        if (!factoryClasses.equals(indexClasses)) {
            return Optional.of(fail(module, ABI_CLASS_FACTORY_MISMATCH, projectProfile));
        }
        for (ClassInterface classInterface : indexEntry.classes()) {
            ClassFactoryId expected = classInterface.constructionEntry();
            if (!abi.classFactoryAbi().get(classInterface.classId()).equals(expected)) {
                return Optional.of(fail(module, ABI_CLASS_FACTORY_MISMATCH, projectProfile));
            }
        }

        Set<ClassId> layoutClasses = new LinkedHashSet<>(abi.classLayoutAbi().keySet());
        if (!layoutClasses.equals(indexClasses)) {
            return Optional.of(fail(module, ABI_CLASS_LAYOUT_MISMATCH, projectProfile));
        }
        for (ClassInterface classInterface : indexEntry.classes()) {
            List<FieldInterface> expected = classInterface.fields();
            if (!abi.classLayoutAbi().get(classInterface.classId()).equals(expected)) {
                return Optional.of(fail(module, ABI_CLASS_LAYOUT_MISMATCH, projectProfile));
            }
        }

        return Optional.empty();
    }

    private static boolean ownerMatchesRoute(ArtifactOwner owner, ModuleRoute route) {
        return switch (owner) {
            case RETAINED_LUAJIT, RETAINED_JVM -> route == ModuleRoute.LEGACY;
            case SHARED -> route == ModuleRoute.SHARED;
        };
    }

    private static Set<String> exportNames(ExternalModuleInterface indexEntry) {
        Set<String> names = new LinkedHashSet<>();
        for (ExportInterface export : indexEntry.exports()) {
            names.add(export.name());
        }
        return names;
    }

    private static String declaredType(ExternalModuleInterface indexEntry, String exportName) {
        for (ExportInterface export : indexEntry.exports()) {
            if (export.name().equals(exportName)) {
                return export.declaredType();
            }
        }
        return "";
    }

    /** The pinned top-level function-form grammar: {@code (T1, …, TN) => R}. */
    static boolean isFunctionForm(String declaredType) {
        return declaredType.startsWith("(");
    }

    /** The pinned top-level async function-form grammar: {@code async (T1, …, TN) => R}. */
    static boolean isAsyncForm(String declaredType) {
        return declaredType.startsWith("async");
    }

    private static CompilerDiagnostic fail(String module, String rule, SemanticProfile profile) {
        return FailureContractRegistry.e6005(new LoweringFailureDetail(
            module, SemanticCapability.MODULES, rule, profile, IR_VERSION, "TargetAbiValidator"));
    }
}
