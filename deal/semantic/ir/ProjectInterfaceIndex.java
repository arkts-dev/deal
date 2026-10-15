package deal.semantic.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The project interface index of {@code deal.semantic-interface/1} (parent
 * D1/canonical surfaces; foundation F2): one immutable index per compile
 * covering every module in the dependency closure. The index is pure
 * copied data — no AST node, no checker fact, and no identity-keyed map;
 * a unit's {@code interfaceHash} equals the deterministic index digest
 * (validator R-PROFILE). The module map preserves insertion order
 * (dependency order), so repeated builds produce byte-identical
 * serializations.
 *
 * <p>The index serializes exclusively through the single canonical JSON
 * facility ({@link CanonicalJson}): {@link #toCanonicalJson()} is the one
 * mapping used for {@link #interfaceIndexDigest()}, so the digest is
 * fully determined and byte-identical across builds — STDLIB/HOST
 * declaration entries included (foundation F2).</p>
 *
 */
public record ProjectInterfaceIndex(String formatVersion, Map<ModuleId, ExternalModuleInterface> modules) {

    /** The pinned interface format version string. */
    public static final String FORMAT_VERSION = "deal.semantic-interface/1";

    public ProjectInterfaceIndex(String formatVersion, Map<ModuleId, ExternalModuleInterface> modules) {
        this.formatVersion = Objects.requireNonNull(formatVersion, "formatVersion must not be null");
        if (!FORMAT_VERSION.equals(formatVersion)) {
            throw new IllegalArgumentException(
                "formatVersion must be \"" + FORMAT_VERSION + "\", got \"" + formatVersion + "\"");
        }
        Objects.requireNonNull(modules, "modules must not be null");
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    /**
     * The single canonical JSON mapping of the index (dependency-ordered
     * module list; sorted object keys; closed enums as names) — the only
     * shape {@link #interfaceIndexDigest()} hashes. The module map key is
     * projected away by the value-only module list, so a key that
     * disagrees with the entry's own module identity is a producer defect
     * and fails encoding instead of being hidden by the projection.
     *
     */
    public CanonicalJson.Value toCanonicalJson() {
        List<CanonicalJson.Value> entries = new ArrayList<>();
        for (Map.Entry<ModuleId, ExternalModuleInterface> entry : modules.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().moduleId())) {
                throw new IllegalArgumentException(
                    "the interface index key " + entry.getKey()
                        + " does not match the entry's moduleId "
                        + entry.getValue().moduleId() + " (a producer defect)");
            }
            entries.add(entry.getValue().toCanonicalJson());
        }
        return CanonicalJson.obj(
            CanonicalJson.e("formatVersion", CanonicalJson.str(formatVersion)),
            CanonicalJson.e("modules", CanonicalJson.arr(entries)));
    }

    /**
     * The pinned interface index digest (foundation F2):
     * {@code SHA-256(canonical JSON of the index)} through the single
     * canonical JSON facility. Feeds the route plan's
     * {@code invocationHash} (F4) and the validator's R-PROFILE
     * {@code interfaceHash} comparison fact.
     *
     */
    public String interfaceIndexDigest() {
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(toCanonicalJson()));
    }
}
