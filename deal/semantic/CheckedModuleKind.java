package deal.semantic;

/**
 * The closed input-kind of a {@link CheckedModuleInput} (parent canonical
 * surfaces; foundation F2): an input entry is an implementation module or
 * a declaration module.
 *
 * <p>Closed set — exactly {@link #IMPLEMENTATION} and
 * {@link #DECLARATION}; no open or unknown fallback member and no
 * extension point exist. Under the current orchestrator classification
 * every input entry is {@code IMPLEMENTATION} with its {@code checks}
 * present by construction: declaration files are never input entries, so
 * the parent-pinned {@code DECLARATION} input kind has no producer in
 * this epic and appears in no record this epic produces.</p>
 */
public enum CheckedModuleKind {

    /** A type-checked implementation module carrying its {@code checks}. */
    IMPLEMENTATION,

    /** A declaration module (parent-pinned; no producer in this epic). */
    DECLARATION
}
