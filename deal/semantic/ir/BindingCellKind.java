package deal.semantic.ir;

/**
 * The closed binding-cell kind of {@code BINDING_ALLOC} (parent closed
 * operation table): a binding allocates either a {@code DIRECT} cell or a
 * {@code SHARED_CELL} (the reassignable cell observed by closure capture
 * and {@code FUNCTION_ADAPT(SHARED_CELL)} generation-checked loads).
 *
 * <p>Closed set — exactly the two values below; no open or unknown
 * fallback member and no external extension point exist.</p>
 */
public enum BindingCellKind {

    /** A direct binding cell. */
    DIRECT,

    /** A shared cell observed by closures and shared-cell adapters. */
    SHARED_CELL
}
