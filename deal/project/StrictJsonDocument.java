package deal.project;

import deal.source.SourceScalarRange;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The strict walk's intermediate model (design source
 * {@code strict-project-context-resolution-identity} D2): the strictly
 * decoded root value plus the half-open decoded-scalar range of every
 * value in the tree.
 *
 * <p>{@code valueRanges} is keyed by object <i>identity</i>, not by
 * structural equality, because equal values (for example two identical
 * strings) are distinct positions in the document. Use
 * {@link #rangeOf(StrictJsonValue)} for lookups.
 *
 * <p>This model is produced by {@link StrictManifestParser}'s strict walk
 * and consumed by {@link ProjectConfigValidator}'s post-walk
 * field-schema validations; both are content-only — no filesystem access,
 * no byte-level decoding, and no {@code java.nio.file} or
 * {@code java.io} types.
 *
 * @param root        the strictly decoded root value, or null when the
 *                    input contains no value at all (empty or
 *                    whitespace-only text)
 * @param valueRanges identity-keyed map from each value in the tree to
 *                    its half-open decoded-scalar range
 */
public record StrictJsonDocument(
    StrictJsonValue root,
    Map<StrictJsonValue, SourceScalarRange> valueRanges
) {

    public StrictJsonDocument {
        valueRanges = Collections.unmodifiableMap(new IdentityHashMap<>(valueRanges));
    }

    /**
     * The half-open decoded-scalar range of {@code value} in the document,
     * or null when the value does not belong to this document.
     */
    public SourceScalarRange rangeOf(StrictJsonValue value) {
        return valueRanges.get(value);
    }
}
