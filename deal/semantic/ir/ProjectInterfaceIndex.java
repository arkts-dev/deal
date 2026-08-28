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
}
