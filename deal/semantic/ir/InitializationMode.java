package deal.semantic.ir;

/**
 * The closed module-initialization mode of the
 * {@code deal.semantic-interface/1} index (parent canonical surfaces):
 * {@code ONCE_AFTER_DEPENDENCIES}.
 *
 * <p>Closed set — exactly the single value below; no open or unknown
 * fallback member and no external extension point exist. Every index entry
 * initializes once after its dependencies complete; the
 * {@code MODULE_INIT} op enforces the
 * {@code UNINITIALIZED→INITIALIZING→INITIALIZED} transition at execution.</p>
 */
public enum InitializationMode {

    /** Initialize exactly once after all dependencies initialize. */
    ONCE_AFTER_DEPENDENCIES
}
