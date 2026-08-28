package deal.semantic.ir;

/**
 * The closed assignment target-kind set of {@code deal.semantic-ir/1}
 * (parent D14; schema S3). {@code ASSIGN} carries the target kind plus the
 * ordered address-chain child op list
 * {@code [containerOp?, keyOp?, valueOp, normalizeOp?, boundaryOps…, commitOp]}
 * in the pinned D14 order: receiver → key → RHS → normalize → write check
 * → commit, with single evaluation.
 *
 * <p>Closed set — exactly {@link #VARIABLE}, {@link #TABLE_SLOT},
 * {@link #ARRAY_SLOT}, and {@link #CLASS_FIELD}; no open or unknown
 * fallback member and no external extension point exist.</p>
 */
public enum AssignTargetKind {

    /** Binding assignment: value → boundary ops → {@code BINDING_STORE}. */
    VARIABLE,

    /** Table slot assignment with a {@code MEMBER_WRITE} commit. */
    TABLE_SLOT,

    /** Array slot assignment with an {@code INDEX_WRITE} commit and an
     * {@code ARRAY_ELEMENT_ASSIGNMENT} write-check boundary. */
    ARRAY_SLOT,

    /** Class field assignment with a {@code FIELD_WRITE} commit. */
    CLASS_FIELD
}
