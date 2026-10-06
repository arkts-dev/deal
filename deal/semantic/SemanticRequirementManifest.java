package deal.semantic;

import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOpKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SemanticRequirementManifest(
    ModuleId moduleId,
    Set<SemanticCapability> capabilities,
    Map<ConstructKind, List<SemanticOpKind>> constructCoverage,
    boolean bytesBearing
) {

    public SemanticRequirementManifest {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("a manifest carries at least one capability");
        }
        if (!capabilities.contains(SemanticCapability.FOUNDATION_VALUES)) {
            throw new IllegalArgumentException(
                "every implementation module claims FOUNDATION_VALUES (foundation F3); "
                    + "a manifest without it is a producer defect");
        }
        EnumSet<SemanticCapability> capabilitiesCopy = EnumSet.noneOf(SemanticCapability.class);
        capabilitiesCopy.addAll(capabilities);
        capabilities = Collections.unmodifiableSet(capabilitiesCopy);

        Objects.requireNonNull(constructCoverage, "constructCoverage must not be null");
        Map<ConstructKind, List<SemanticOpKind>> coverageCopy = new EnumMap<>(ConstructKind.class);
        for (Map.Entry<ConstructKind, List<SemanticOpKind>> entry : constructCoverage.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "constructCoverage keys must not be null");
            Objects.requireNonNull(entry.getValue(), "constructCoverage values must not be null");
            if (!entry.getValue().equals(entry.getKey().mappedOpKinds())) {
                throw new IllegalArgumentException(
                    "constructCoverage rows carry the closed construct\u2192op detector "
                        + "table's mapped op kinds verbatim (S4): row " + entry.getKey()
                        + " must be exactly " + entry.getKey().mappedOpKinds()
                        + ", got " + entry.getValue());
            }
            coverageCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        constructCoverage = Collections.unmodifiableMap(coverageCopy);
    }

    /**
     * The single canonical JSON mapping of this manifest (capabilities in
     * {@code SemanticCapability} declaration order, coverage rows keyed by
     * {@code ConstructKind} name, module id through the canonicalizer's
     * semantic-ID mapping) — the deterministic serialization repeated
     * builds compare for byte-identity (F3).
     *
     */
    public CanonicalJson.Value toCanonicalJson() {
        List<CanonicalJson.Value> caps = new ArrayList<>();
        for (SemanticCapability capability : capabilities) {
            caps.add(CanonicalJson.str(capability.name()));
        }
        List<CanonicalJson.Entry> coverageEntries = new ArrayList<>();
        for (Map.Entry<ConstructKind, List<SemanticOpKind>> entry : constructCoverage.entrySet()) {
            List<CanonicalJson.Value> kinds = new ArrayList<>();
            for (SemanticOpKind kind : entry.getValue()) {
                kinds.add(CanonicalJson.str(kind.name()));
            }
            coverageEntries.add(CanonicalJson.e(entry.getKey().name(),
                CanonicalJson.arr(kinds)));
        }
        return CanonicalJson.obj(
            CanonicalJson.e("bytesBearing", CanonicalJson.bool(bytesBearing)),
            CanonicalJson.e("capabilities", CanonicalJson.arr(caps)),
            CanonicalJson.e("constructCoverage", CanonicalJson.obj(coverageEntries)),
            CanonicalJson.e("moduleId",
                ContractSnapshotCanonicalizer.semanticIdJson(moduleId)));
    }

    /**
     * The deterministic canonical JSON text of this manifest (UTF-8, the
     * single canonical JSON facility) — byte-identical across repeated
     * computation over the same checked project (F3 determinism).
     *
     */
    public String canonicalText() {
        return CanonicalJson.serializeText(toCanonicalJson());
    }
}
