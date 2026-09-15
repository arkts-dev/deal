package deal.ffi;

import java.util.Objects;

/**
 * One forward function cell of the settled binding seam (design source
 * {@code deal-v1.2-directives-and-c-ffi-declarations} D7,
 * {@code luajit-ffi-generated-content-seam} S6):
 * {@code ForwardFunctionCell.get() -> typed DEAL wrapper | module
 * initialization error}.
 *
 * <p>The cell is created {@code UNBOUND} by the generator <b>before</b>
 * any evaluator lowering — evaluators close over cells, never over a
 * not-yet-published export table. The transitions are guarded and
 * total:</p>
 *
 * <ul>
 *   <li>{@code UNBOUND -> BINDING} exactly once, after every module
 *       symbol exists (the runtime's all-symbols-resolved step);</li>
 *   <li>{@code BINDING -> READY} exactly once, installing the typed
 *       wrapper key;</li>
 *   <li>{@code BINDING -> FAILED} exactly once, retaining the cached
 *       initialization error key;</li>
 *   <li>{@code READY}/{@code FAILED} are terminal — every further
 *       transition attempt is an invariant violation (never silently
 *       overwritten).</li>
 * </ul>
 *
 * <p>{@link #get()} returns the wrapper key at {@code READY} and raises
 * the cached initialization error at {@code FAILED}; {@code UNBOUND}/
 * {@code BINDING} raise the cached-module-initialization signal (the
 * runtime seam maps it to {@code FFI_LIBRARY_LOAD} and invokes no
 * wrapper). Compile-side, the wrapper/error values are opaque
 * serialization keys owned by the lowering child; this carrier pins the
 * state discipline only.</p>
 */
public final class ForwardFunctionCell {

    private final String exportName;
    private FfiBindingState state = FfiBindingState.UNBOUND;
    private String wrapperKey;
    private String errorValueKey;

    /**
     * Creates one UNBOUND cell for an exported FFI function.
     *
     * @param exportName the exported DEAL (and C symbol) name
     */
    public ForwardFunctionCell(String exportName) {
        Objects.requireNonNull(exportName, "exportName");
        if (exportName.isEmpty()) {
            throw new IllegalArgumentException("exportName must not be empty");
        }
        this.exportName = exportName;
    }

    /** The cell's exported name, immutable since construction. */
    public String exportName() {
        return exportName;
    }

    /** The current binding state. */
    public synchronized FfiBindingState state() {
        return state;
    }

    /**
     * The guarded {@code UNBOUND -> BINDING} transition.
     *
     * @throws IllegalStateException when the cell is not UNBOUND
     */
    public synchronized void markBinding() {
        requireState(FfiBindingState.UNBOUND, "markBinding");
        state = FfiBindingState.BINDING;
    }

    /**
     * The guarded {@code BINDING -> READY} transition installing the
     * typed wrapper key.
     *
     * @param wrapperKey the installed typed wrapper serialization key
     * @throws IllegalStateException when the cell is not BINDING
     */
    public synchronized void markReady(String wrapperKey) {
        requireState(FfiBindingState.BINDING, "markReady");
        Objects.requireNonNull(wrapperKey, "wrapperKey");
        this.wrapperKey = wrapperKey;
        state = FfiBindingState.READY;
    }

    /**
     * The guarded {@code BINDING -> FAILED} transition retaining the
     * cached initialization error key.
     *
     * @param errorValueKey the cached initialization error key
     * @throws IllegalStateException when the cell is not BINDING
     */
    public synchronized void markFailed(String errorValueKey) {
        requireState(FfiBindingState.BINDING, "markFailed");
        Objects.requireNonNull(errorValueKey, "errorValueKey");
        this.errorValueKey = errorValueKey;
        state = FfiBindingState.FAILED;
    }

    /**
     * The seam accessor: the installed typed wrapper key at
     * {@code READY}, else the module-initialization error carrying the
     * cached failure key at {@code FAILED} and the not-yet-bound signal
     * for {@code UNBOUND}/{@code BINDING}.
     */
    public synchronized String get() {
        return switch (state) {
            case READY -> Objects.requireNonNull(wrapperKey,
                "READY cell without a wrapper (internal invariant)");
            case FAILED -> throw new FfiCellInitializationError(
                "forward cell '" + exportName + "' is FAILED: "
                    + errorValueKey);
            case UNBOUND, BINDING -> throw new FfiCellInitializationError(
                "forward cell '" + exportName + "' is not READY ("
                    + state + "): module initialization has not completed");
        };
    }

    private void requireState(FfiBindingState expected, String transition) {
        if (state != expected) {
            throw new IllegalStateException(
                "illegal " + transition + " transition for cell '"
                    + exportName + "': expected " + expected + ", is "
                    + state);
        }
    }

    /**
     * The cell-level initialization error: a distinct signal (never a
     * raw exception class of another phase) carrying the cached failure
     * key or the not-ready state.
     */
    public static final class FfiCellInitializationError
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public FfiCellInitializationError(String message) {
            super(message);
        }
    }
}
