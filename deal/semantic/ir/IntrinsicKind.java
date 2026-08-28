package deal.semantic.ir;

/**
 * The closed intrinsic-conversion set of {@code deal.semantic-ir/1}
 * (parent closed operation table; schema S3): the two conversion
 * intrinsics {@code int} and {@code number} usable as first-class function
 * values.
 *
 * <p>Closed set — exactly {@link #INT_CONVERT} and
 * {@link #NUMBER_CONVERT}; no open or unknown fallback member and no
 * external extension point exist. {@code INTRINSIC_CALL} has zero
 * {@code BOUNDARY} child ops of any kind: its single input operand
 * completes before START and the conversion policy
 * ({@code INT_CONVERSION}/{@code NUMBER_CONVERSION}) is its only terminal
 * check. The conversions are payload fields, not snapshot selectors.</p>
 */
public enum IntrinsicKind {

    /** The {@code int} conversion intrinsic. */
    INT_CONVERT,

    /** The {@code number} conversion intrinsic. */
    NUMBER_CONVERT
}
