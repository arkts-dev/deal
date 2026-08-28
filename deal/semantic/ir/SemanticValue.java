package deal.semantic.ir;

/**
 * The result slot of a {@link SemanticOp}: exactly a {@link ValueId} or an
 * {@link AsyncTokenId} (mandatory op header; S2). A result slot of
 * {@code none} is the Java {@code null} reference.
 *
 * <p>Closed sealed family; no other result shape exists.</p>
 */
public sealed interface SemanticValue permits ValueId, AsyncTokenId {
}
