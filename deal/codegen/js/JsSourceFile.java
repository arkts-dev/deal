package deal.codegen.js;

import java.util.Objects;

/**
 * One implementation unit's authoritative source identity on the
 * canonical {@code JsEmissionInput} rendering seam
 * (design source {@code wiki:javascript-semantic-ir-emission},
 * "map_path_ownership"): the source id the module's origins are keyed by
 * and the project display name the renderer writes into the emitted
 * source comment. The record carries strings only — the renderer never
 * reads the filesystem, re-derives a path, or rediscovers a module name.
 *
 * @param sourceId    the module's recorded origin source id (the
 *                    compilation's checked source fact); non-null
 * @param displayName the project display name of the same source; non-null
 *
 */
public record JsSourceFile(String sourceId, String displayName) {

    public JsSourceFile {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}
