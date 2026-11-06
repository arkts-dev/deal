package deal.semantic.ir;

/**
 * The closed control-selector set of {@code deal.semantic-ir/2} (parent
 * D13; schema S3).
 *
 * <p>Closed set — exactly {@link #IF}, {@link #CONDITIONAL}, {@link #LOGICAL_AND},
 * {@link #LOGICAL_OR}, {@link #WHILE}, and
 * {@link #TRY_CATCH}; no open or unknown fallback member and no external
 * extension point exist. {@code BRANCH} carries {@code IF}, {@code CONDITIONAL} or the two
 * logical selectors (short circuit is {@code BRANCH}, never
 * {@code BINARY}); {@code LOOP} carries {@code WHILE};
 * {@code TRY_CATCH} names its selector for the try/catch shape. The
 * per-kind admissible subsets are enforced by the validator over the
 * closed operation table.</p>
 */
public enum ControlSelector {

    /** If/then/else branch. */
    IF,

    /** Value-bearing boolean conditional with two mandatory branches. */
    CONDITIONAL,

    /** Short-circuit logical and. */
    LOGICAL_AND,

    /** Short-circuit logical or. */
    LOGICAL_OR,

    /** While loop. */
    WHILE,

    /** Try/catch structured exception shape. */
    TRY_CATCH
}
