package deal.semantic.ir;

import java.util.Objects;

/**
 * The source origin of a {@link SemanticOp} (mandatory op header; S2):
 *
 * <pre>{@code SourceOrigin = {sourceId, span, USER|SYNTHETIC, anchorId, parentOpId?}}</pre>
 *
 * <p>{@code opId}, source coordinates, and trace phase are outside the
 * operation-contract snapshot digest; this record carries them as pure
 * copied data. Nested child ops record their enclosing op as
 * {@code parentOpId}; {@code EXTERNAL_ENTRY} and {@code CLASS_FACTORY}
 * record their triggering caller op as {@code parentOpId} at execution
 * (cross-unit references are valid because {@link OpId} carries its
 * module).</p>
 *
 * @param sourceId   the stable source identifier; non-null
 * @param span       the copied source span; non-null
 * @param kind       {@code USER} or {@code SYNTHETIC}; non-null
 * @param anchorId   the source-anchor identity; non-null
 * @param parentOpId the enclosing/triggering op identity, or {@code null}
 */
public record SourceOrigin(
    String sourceId,
    SourceSpan span,
    SourceOriginKind kind,
    AnchorId anchorId,
    OpId parentOpId
) {

    public SourceOrigin {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(span, "span must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(anchorId, "anchorId must not be null");
    }
}
