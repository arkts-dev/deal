package deal.semantic.ir;

/**
 * The closed delete target-kind set of {@code deal.semantic-ir/1} (parent
 * D14; schema S3). {@code DELETE} carries the target kind plus the ordered
 * address-chain child op list
 * {@code [containerOp?, keyOp?, normalizeOp?, boundaryOp? (ARRAY_SLOT only), commitOp]}
 * in the pinned D14 order: receiver → key → normalize → [array bounds
 * boundary] → commit. The {@code ARRAY_SLOT} boundary is
 * {@code ARRAY_ELEMENT_DELETE} with {@code ARRAY_DELETE_BOUNDS} (index
 * {@code <0} or {@code >length} raises E8002 before the nil write);
 * table and class targets run no bounds boundary.
 *
 * <p>Closed set — exactly {@link #TABLE_SLOT}, {@link #ARRAY_SLOT}, and
 * {@link #CLASS_FIELD}; no open or unknown fallback member and no external
 * extension point exist. (A variable target cannot be deleted — the
 * checker admits only postfix member/index delete targets.)</p>
 */
public enum DeleteTargetKind {

    /** Table slot delete with a {@code MEMBER_DELETE} commit. */
    TABLE_SLOT,

    /** Array slot delete with the {@code ARRAY_ELEMENT_DELETE} bounds boundary. */
    ARRAY_SLOT,

    /** Class field delete with a {@code FIELD_DELETE} commit. */
    CLASS_FIELD
}
