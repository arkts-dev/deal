package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * @param formatVersion the pinned {@link #FORMAT_VERSION}; non-null
 * @param modules       every module in the dependency closure keyed by
 *                      {@link ModuleId}; non-null
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
     * shape {@link #interfaceIndexDigest()} hashes.
     *
     * @return the canonical JSON object
     */
    public CanonicalJson.Value toCanonicalJson() {
        return CanonicalJson.obj(
            CanonicalJson.e("formatVersion", CanonicalJson.str(formatVersion)),
            CanonicalJson.e("modules", CanonicalJson.arr(
                modules.values().stream()
                    .map(ExternalModuleInterface::toCanonicalJson)
                    .toList())));
    }

    /**
     * The pinned interface index digest (foundation F2):
     * {@code SHA-256(canonical JSON of the index)} through the single
     * canonical JSON facility. Feeds the route plan's
     * {@code invocationHash} (F4) and the validator's R-PROFILE
     * {@code interfaceHash} comparison fact.
     *
     * @return the lowercase 64-character hex digest
     */
    public String interfaceIndexDigest() {
        return CanonicalJson.sha256Hex(CanonicalJson.serializeBytes(toCanonicalJson()));
    }
}
