package deal.test.feature;

import java.util.Set;

/**
 * The closed ISSUE-0111 feature-id set (declarations page D12, the
 * architecture-owned {@code FeatureBackendMatrix} key space).
 *
 * <p>The ids are normative and fixed: an unknown feature id in a sidecar
 * is a catalog failure, never a typo-tolerant match. The set deliberately
 * has no extensibility surface — adding a feature requires editing both
 * this enum and {@link FeatureBackendMatrix} in one coupled change.</p>
 */
public enum FeatureId {

    /** Signed-int32 literals/arithmetic/conversions (int32 page D1-D2). */
    SIGNED_INT32,

    /** Bytes allocation/length/mutation/identity core (int32 page D3-D4). */
    BYTES_CORE,

    /** Per-attempt bytes-bearing defaults and phase order (int32 page D5). */
    BYTES_DEFAULTS,

    /** Canonical recursive descriptors carrying bytes (int32 page D7). */
    BYTES_DESCRIPTORS,

    /** First-class sync bytes-bearing function values (int32 page D8). */
    BYTES_SYNC_FUNCTION,

    /** First-class async bytes-bearing values under async-export (D8-D9). */
    BYTES_ASYNC_FUNCTION,

    /** Fixed-name directives, anchoring, version rules (declarations D1-D2). */
    DIRECTIVES,

    /** Non-lossy full scalar diagnostic ranges (declarations D6). */
    DIAGNOSTIC_RANGE,

    /** Strict project discovery/configuration (declarations D5, D10-D11). */
    PROJECT_CONFIG,

    /** LuaJIT production C FFI runtime records (declarations D12). */
    C_FFI,

    /** C FFI declaration-policy compile errors on both pipelines (D12). */
    C_FFI_DECLARATION_ERROR;

    /** The canonical sidecar spelling of every id (the {@code feature} field). */
    public String canonicalName() {
        return name().replace('_', '-').toLowerCase();
    }

    /** Parses a canonical feature id; null for an unknown spelling. */
    public static FeatureId parseCanonical(String text) {
        for (FeatureId id : values()) {
            if (id.canonicalName().equals(text)) {
                return id;
            }
        }
        return null;
    }

    /** Every id, as a canonical-spelling set (sidecar validation). */
    public static Set<String> canonicalNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (FeatureId id : values()) {
            names.add(id.canonicalName());
        }
        return names;
    }
}
