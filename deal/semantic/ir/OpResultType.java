package deal.semantic.ir;

/**
 * The result-type slot of a {@link SemanticOp} and of an
 * {@link OperationContractSnapshot}: a {@link RuntimeDescriptor}, the
 * internal sentinel {@link InternalResultType#INTERNAL_MISSING} or
 * {@link InternalResultType#INTERNAL_ASYNC}, or {@code none} (the Java
 * {@code null} reference).
 *
 * <p>Closed sealed family; no other result-type shape exists.</p>
 */
public sealed interface OpResultType permits RuntimeDescriptor, InternalResultType {
}
