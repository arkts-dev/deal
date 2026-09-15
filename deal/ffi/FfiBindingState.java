package deal.ffi;

/**
 * The pinned forward-binding state machine
 * {@code UNBOUND -> BINDING -> READY | FAILED} (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S6): cells are created
 * UNBOUND before evaluator lowering, move to BINDING only after every
 * symbol/wrapper of the module exists, and become READY (wrapper
 * installed) or FAILED (cached initialization error retained) exactly
 * once — the terminal states never leave.
 */
public enum FfiBindingState {
    /** The cell exists and no binding work has started. */
    UNBOUND,
    /** Every module symbol resolved; the wrapper is being built. */
    BINDING,
    /** The typed wrapper is installed; invocation dereferences it. */
    READY,
    /** Initialization failed; the cached error is re-raised. */
    FAILED
}
