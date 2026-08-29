package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.FieldInterface;
import deal.semantic.ir.FunctionSignatureAbi;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SyncInvocationEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One mixed-edge ABI record of a route plan (foundation F5; parent D1) —
 * the single record type serving the planner, the emitters, and the
 * stage validator:
 *
 * <pre>{@code
 * TargetModuleAbi {
 *   moduleId, target: LUAJIT|JVM,
 *   artifactOwner: SHARED | RETAINED_LUAJIT | RETAINED_JVM,
 *   semanticProfile,                                    // the project profile
 *   // planner-owned plan-time fields — index facts only, derived at plan time:
 *   classFactoryAbi: Map<ClassId, ClassFactoryId>,      // constructionEntry per exported class
 *   classLayoutAbi: Map<ClassId, [FieldInterface]>,     // declaration-order layout (declared types)
 *   // DescriptorService-owned (ISSUE-0233) — RuntimeDescriptor production; absent at plan time in this epic:
 *   exportedDescriptors: Map<exportName, RuntimeDescriptor>,
 *   // emission-owned realization fields — completed during staging, mandatory at validation:
 *   loadKey,                                            // resolves to a staged artifact at validation time
 *   initializationEntry,                                // module initialization entry
 *   functionWrapperAbi: Map<exportName, FunctionSignatureAbi>,   // wrapper/signature ABI
 *   syncInvocationEntries: Map<exportName, SyncInvocationEntry>, // one per imported export
 *   asyncLinkageRecords: [ExternalAsyncLink],           // one per async export invoked across a shared edge
 * }
 * }</pre>
 *
 * <p><b>Field split (pinned).</b> At plan time {@link MigrationPlanner}
 * derives only the planner-owned fields from the interface index — for a
 * legacy dependency of a shared module: {@code moduleId}, {@code target},
 * {@code artifactOwner: RETAINED_LUAJIT|RETAINED_JVM}, the project
 * {@code semanticProfile}, and {@code classFactoryAbi}/
 * {@code classLayoutAbi} copied from the module's {@link
 * deal.semantic.ir.ClassInterface} records ({@code constructionEntry}
 * values — the deterministic route-independent identifiers of foundation
 * F2 — and {@code FieldInterface} lists in declaration order).
 * {@code exportedDescriptors} is <b>empty at plan time in this epic</b> —
 * Type→{@code RuntimeDescriptor} production is the DescriptorService's
 * (ISSUE-0233), which fills the field before stage validation when that
 * arm activates; this epic's stage tests complete the field
 * synthetically. The emission-owned realization fields are
 * <b>absent ({@code null}) at plan time</b> and completed during staging
 * by the retained target ABI adapters from the actually emitted retained
 * artifacts (ISSUE-0239); the planner never invents retained wrapper
 * names, sync-invocation entry names, async-handle protocol records, or
 * load keys. {@code TargetAbiValidator} (foundation F6) is the single
 * consumer that enforces record completeness and consistency at
 * validation time.</p>
 *
 * <p>The record is immutable: planner-owned and DescriptorService-owned
 * collections are copied into unmodifiable maps preserving insertion
 * order (class declaration order for the class ABI maps), and the
 * emission-owned collections are copied the same way whenever present.
 * The canonical serialization flows through the single canonical JSON
 * facility (S2), so plan records serialize byte-identically across
 * repeated builds.</p>
 *
 * @param moduleId               the module identity; non-null
 * @param target                 the plan target; non-null
 * @param artifactOwner          the artifact owner; non-null
 * @param semanticProfile        the project profile; non-null
 * @param classFactoryAbi        constructionEntry per exported class; non-null
 * @param classLayoutAbi         declaration-order field layouts; non-null
 * @param exportedDescriptors     descriptor per export (ISSUE-0233); non-null, empty at plan time
 * @param loadKey                emission-owned; null (absent) at plan time
 * @param initializationEntry     emission-owned; null (absent) at plan time
 * @param functionWrapperAbi      emission-owned; null (absent) at plan time
 * @param syncInvocationEntries   emission-owned; null (absent) at plan time
 * @param asyncLinkageRecords     emission-owned; null (absent) at plan time
 */
public record TargetModuleAbi(
    ModuleId moduleId,
    Target target,
    ArtifactOwner artifactOwner,
    SemanticProfile semanticProfile,
    Map<ClassId, ClassFactoryId> classFactoryAbi,
    Map<ClassId, List<FieldInterface>> classLayoutAbi,
    Map<String, RuntimeDescriptor> exportedDescriptors,
    String loadKey,
    String initializationEntry,
    Map<String, FunctionSignatureAbi> functionWrapperAbi,
    Map<String, SyncInvocationEntry> syncInvocationEntries,
    List<ExternalAsyncLink> asyncLinkageRecords
) {

    public TargetModuleAbi {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(artifactOwner, "artifactOwner must not be null");
        Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");

        Objects.requireNonNull(classFactoryAbi, "classFactoryAbi must not be null");
        classFactoryAbi = copyChecked(classFactoryAbi);

        Objects.requireNonNull(classLayoutAbi, "classLayoutAbi must not be null");
        Map<ClassId, List<FieldInterface>> layoutCopy = new LinkedHashMap<>();
        for (Map.Entry<ClassId, List<FieldInterface>> entry : classLayoutAbi.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "classLayoutAbi keys must not be null");
            Objects.requireNonNull(entry.getValue(), "classLayoutAbi values must not be null");
            layoutCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        classLayoutAbi = Collections.unmodifiableMap(layoutCopy);

        Objects.requireNonNull(exportedDescriptors, "exportedDescriptors must not be null");
        Map<String, RuntimeDescriptor> descriptorsCopy = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeDescriptor> entry : exportedDescriptors.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "exportedDescriptors keys must not be null");
            Objects.requireNonNull(entry.getValue(),
                "exportedDescriptors values must not be null");
            descriptorsCopy.put(entry.getKey(), entry.getValue());
        }
        exportedDescriptors = Collections.unmodifiableMap(descriptorsCopy);

        // Emission-owned realization fields: absent at plan time, completed
        // during staging (ISSUE-0239). Null is the declared absent state;
        // every non-null value is copied defensively.
        if (functionWrapperAbi != null) {
            Map<String, FunctionSignatureAbi> wrapperCopy = new LinkedHashMap<>();
            for (Map.Entry<String, FunctionSignatureAbi> entry : functionWrapperAbi.entrySet()) {
                Objects.requireNonNull(entry.getKey(),
                    "functionWrapperAbi keys must not be null");
                Objects.requireNonNull(entry.getValue(),
                    "functionWrapperAbi values must not be null");
                wrapperCopy.put(entry.getKey(), entry.getValue());
            }
            functionWrapperAbi = Collections.unmodifiableMap(wrapperCopy);
        }
        if (syncInvocationEntries != null) {
            Map<String, SyncInvocationEntry> syncCopy = new LinkedHashMap<>();
            for (Map.Entry<String, SyncInvocationEntry> entry
                    : syncInvocationEntries.entrySet()) {
                Objects.requireNonNull(entry.getKey(),
                    "syncInvocationEntries keys must not be null");
                Objects.requireNonNull(entry.getValue(),
                    "syncInvocationEntries values must not be null");
                syncCopy.put(entry.getKey(), entry.getValue());
            }
            syncInvocationEntries = Collections.unmodifiableMap(syncCopy);
        }
        if (asyncLinkageRecords != null) {
            asyncLinkageRecords = List.copyOf(asyncLinkageRecords);
        }
    }

    private static Map<ClassId, ClassFactoryId> copyChecked(
            Map<ClassId, ClassFactoryId> source) {
        Map<ClassId, ClassFactoryId> copy = new LinkedHashMap<>();
        for (Map.Entry<ClassId, ClassFactoryId> entry : source.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "classFactoryAbi keys must not be null");
            Objects.requireNonNull(entry.getValue(), "classFactoryAbi values must not be null");
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * The single canonical JSON mapping of this ABI record (sorted
     * object keys; class ABI entries in class declaration order;
     * explicit {@code null}s for every emission-owned field absent at
     * plan time) — the shape the route plan's {@code abiEdges} array
     * serializes.
     *
     * @return the canonical JSON object
     */
    public CanonicalJson.Value toCanonicalJson() {
        List<CanonicalJson.Value> factoryEntries = new ArrayList<>();
        for (Map.Entry<ClassId, ClassFactoryId> entry : classFactoryAbi.entrySet()) {
            factoryEntries.add(CanonicalJson.obj(
                CanonicalJson.e("classId",
                    ContractSnapshotCanonicalizer.semanticIdJson(entry.getKey())),
                CanonicalJson.e("factoryId",
                    ContractSnapshotCanonicalizer.semanticIdJson(entry.getValue()))));
        }
        List<CanonicalJson.Value> layoutEntries = new ArrayList<>();
        for (Map.Entry<ClassId, List<FieldInterface>> entry : classLayoutAbi.entrySet()) {
            layoutEntries.add(CanonicalJson.obj(
                CanonicalJson.e("classId",
                    ContractSnapshotCanonicalizer.semanticIdJson(entry.getKey())),
                CanonicalJson.e("fields", CanonicalJson.arr(
                    entry.getValue().stream()
                        .map(FieldInterface::toCanonicalJson)
                        .toList()))));
        }
        List<CanonicalJson.Entry> descriptorEntries = new ArrayList<>();
        for (Map.Entry<String, RuntimeDescriptor> entry : exportedDescriptors.entrySet()) {
            descriptorEntries.add(CanonicalJson.e(entry.getKey(),
                CanonicalJson.str(entry.getValue().canonicalSpecText())));
        }
        List<CanonicalJson.Entry> wrapperEntries = new ArrayList<>();
        if (functionWrapperAbi != null) {
            for (Map.Entry<String, FunctionSignatureAbi> entry
                    : functionWrapperAbi.entrySet()) {
                wrapperEntries.add(CanonicalJson.e(entry.getKey(),
                    entry.getValue().toCanonicalJson()));
            }
        }
        List<CanonicalJson.Entry> syncEntries = new ArrayList<>();
        if (syncInvocationEntries != null) {
            for (Map.Entry<String, SyncInvocationEntry> entry
                    : syncInvocationEntries.entrySet()) {
                syncEntries.add(CanonicalJson.e(entry.getKey(),
                    entry.getValue().toCanonicalJson()));
            }
        }
        CanonicalJson.Value asyncValue = asyncLinkageRecords == null
            ? CanonicalJson.nullValue()
            : CanonicalJson.arr(asyncLinkageRecords.stream()
                .map(ContractSnapshotCanonicalizer::externalAsyncLinkJson)
                .toList());

        return CanonicalJson.obj(
            CanonicalJson.e("artifactOwner", CanonicalJson.str(artifactOwner.name())),
            CanonicalJson.e("asyncLinkageRecords", asyncValue),
            CanonicalJson.e("classFactoryAbi", CanonicalJson.arr(factoryEntries)),
            CanonicalJson.e("classLayoutAbi", CanonicalJson.arr(layoutEntries)),
            CanonicalJson.e("exportedDescriptors", CanonicalJson.obj(descriptorEntries)),
            CanonicalJson.e("functionWrapperAbi", functionWrapperAbi == null
                ? CanonicalJson.nullValue() : CanonicalJson.obj(wrapperEntries)),
            CanonicalJson.e("initializationEntry", initializationEntry == null
                ? CanonicalJson.nullValue() : CanonicalJson.str(initializationEntry)),
            CanonicalJson.e("loadKey", loadKey == null
                ? CanonicalJson.nullValue() : CanonicalJson.str(loadKey)),
            CanonicalJson.e("moduleId",
                ContractSnapshotCanonicalizer.semanticIdJson(moduleId)),
            CanonicalJson.e("semanticProfile", CanonicalJson.str(semanticProfile.name())),
            CanonicalJson.e("syncInvocationEntries", syncInvocationEntries == null
                ? CanonicalJson.nullValue() : CanonicalJson.obj(syncEntries)),
            CanonicalJson.e("target", CanonicalJson.str(target.name())));
    }

    /**
     * The deterministic canonical JSON text of this record (UTF-8, the
     * single canonical JSON facility) — byte-identical across repeated
     * builds.
     *
     * @return the canonical JSON text
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(toCanonicalJson());
    }
}
