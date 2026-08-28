package deal.semantic.ir;

/**
 * The closed control-selector set of {@code deal.semantic-ir/1} (parent
 * D13; schema S3).
 *
 * <p>Closed set — exactly {@link #IF}, {@link #LOGICAL_AND},
 * {@link #LOGICAL_OR}, {@link #WHILE}, {@link #FOR}, and
 * {@link #TRY_CATCH}; no open or unknown fallback member and no external
 * extension point exist. {@code BRANCH} carries {@code IF} or the two
 * logical selectors (short circuit is {@code BRANCH}, never
 * {@code BINARY}); {@code LOOP} carries {@code WHILE} or {@code FOR};
 * {@code TRY_CATCH} names its selector for the try/catch shape. The
 * per-kind admissible subsets are enforced by the validator over the
 * closed operation table.</p>
 */
public enum ControlSelector {

    /** If/then/else branch. */
    IF,

    /** Short-circuit logical and. */
    LOGICAL_AND,

    /** Short-circuit logical or. */
    LOGICAL_OR,

    /** While loop. */
    WHILE,

    /** For loop with init/condition/update blocks. */
    FOR,

    /** Try/catch structured exception shape. */
    TRY_CATCH
}
