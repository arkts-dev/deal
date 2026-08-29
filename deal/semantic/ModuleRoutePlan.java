package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ModuleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One deterministic per-target route plan (foundation F4; parent
 * canonical surfaces):
 *
 * <pre>{@code
 * ModuleRoutePlan {
 *   target: LUAJIT|JVM,
 *   entries: Map<ModuleId, LEGACY|SHARED>,   // implementation modules only, dependency order
 *   shadowModules: Set<ModuleId>,            // COMMON_SHADOW shadow SHARED entries
 *   abiEdges: [TargetModuleAbi],             // one plan-time record per legacy dependency of a shared module
 *   invocationHash, planId
 * }
 * }</pre>
 *
 * <p>{@code entries} covers implementation modules only — stdlib/host
 * declaration modules appear in the interface index, never as route
 * entries. {@code shadowModules} is the subset of {@code SHARED} entries
 * recorded as shadow SHARED entries under {@code COMMON_SHADOW}; shadow
 * entries never drive production publication. {@code abiEdges} carries
 * one plan-time {@link TargetModuleAbi} per legacy dependency of a
 * shared module in dependency order (first reference order), with the
 * planner-owned fields only (foundation F5).</p>
 *
 * <p><b>Hashes.</b> {@code invocationHash} is the pinned
 * {@code SHA-256(canonical JSON {purpose, semanticProfile, releaseState,
 * capabilityRegistryHash, interfaceIndexDigest, target})} computed by
 * {@link MigrationPlanner} through the single canonical JSON facility
 * (S2/F4); {@code planId = "plan-" + first 16 hex chars of
 * invocationHash}. The staging-tree nonce of the publication stage
 * exists only in on-disk tree names — never in this plan record, any
 * record, or any hash (F4/F6). The record is immutable, and the compact
 * constructor enforces the {@code planId} derivation, the hex shape of
 * {@code invocationHash}, and the {@code shadowModules} ⊆ SHARED-entries
 * invariant.</p>
 *
 * @param target         the plan target; non-null
 * @param entries        every implementation module routed, in dependency
 *                       order; non-null
 * @param shadowModules  the shadow SHARED entries; non-null
 * @param abiEdges       the plan-time ABI records in dependency order; non-null
 * @param invocationHash the pinned invocation hash; non-null
 * @param planId         the derived {@code plan-} prefixed id; non-null
 */
public record ModuleRoutePlan(
    Target target,
    Map<ModuleId, ModuleRoute> entries,
    Set<ModuleId> shadowModules,
    List<TargetModuleAbi> abiEdges,
    String invocationHash,
    String planId
) {

    public ModuleRoutePlan {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        Objects.requireNonNull(shadowModules, "shadowModules must not be null");
        shadowModules = Collections.unmodifiableSet(new LinkedHashSet<>(shadowModules));
        abiEdges = List.copyOf(abiEdges);
        Objects.requireNonNull(invocationHash, "invocationHash must not be null");
        if (!invocationHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "invocationHash must be the lowercase 64-character hex SHA-256 digest; got \""
                    + invocationHash + "\"");
        }
        Objects.requireNonNull(planId, "planId must not be null");
        if (!planId.equals("plan-" + invocationHash.substring(0, 16))) {
            throw new IllegalArgumentException(
                "planId must equal \"plan-\" + the first 16 hex chars of invocationHash "
                    + "(foundation F4); got \"" + planId + "\" for invocationHash \""
                    + invocationHash + "\"");
        }
        for (ModuleId shadow : shadowModules) {
            if (!entries.containsKey(shadow)) {
                throw new IllegalArgumentException(
                    "shadowModules covers route entries only; module '" + shadow
                        + "' has no entry");
            }
            if (entries.get(shadow) != ModuleRoute.SHARED) {
                throw new IllegalArgumentException(
                    "a shadow module is a shadow SHARED entry (foundation F4 rule 5); "
                        + "module '" + shadow + "' is routed " + entries.get(shadow));
            }
        }
    }

    /**
     * The single canonical JSON mapping of this plan (sorted object
     * keys; {@code entries} in dependency order, {@code shadowModules}
     * in dependency order, {@code abiEdges} in dependency order) — the
     * shape the byte-identical-plan gate compares.
     *
     * @return the canonical JSON object
     */
    public CanonicalJson.Value toCanonicalJson() {
        List<CanonicalJson.Value> entryValues = new ArrayList<>();
        for (Map.Entry<ModuleId, ModuleRoute> entry : entries.entrySet()) {
            entryValues.add(CanonicalJson.obj(
                CanonicalJson.e("moduleId",
                    ContractSnapshotCanonicalizer.semanticIdJson(entry.getKey())),
                CanonicalJson.e("route", CanonicalJson.str(entry.getValue().name()))));
        }
        List<CanonicalJson.Value> shadowValues = new ArrayList<>();
        for (ModuleId shadow : shadowModules) {
            shadowValues.add(ContractSnapshotCanonicalizer.semanticIdJson(shadow));
        }
        List<CanonicalJson.Value> abiValues = new ArrayList<>();
        for (TargetModuleAbi abi : abiEdges) {
            abiValues.add(abi.toCanonicalJson());
        }
        return CanonicalJson.obj(
            CanonicalJson.e("abiEdges", CanonicalJson.arr(abiValues)),
            CanonicalJson.e("entries", CanonicalJson.arr(entryValues)),
            CanonicalJson.e("invocationHash", CanonicalJson.str(invocationHash)),
            CanonicalJson.e("planId", CanonicalJson.str(planId)),
            CanonicalJson.e("shadowModules", CanonicalJson.arr(shadowValues)),
            CanonicalJson.e("target", CanonicalJson.str(target.name())));
    }

    /**
     * The deterministic canonical JSON text of this plan (UTF-8, the
     * single canonical JSON facility) — byte-identical across repeated
     * builds with stable {@code invocationHash}/{@code planId}, and never
     * containing the staging-tree nonce (the nonce exists only in on-disk
     * tree names, F4/F6).
     *
     * @return the canonical JSON text
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(toCanonicalJson());
    }
}
