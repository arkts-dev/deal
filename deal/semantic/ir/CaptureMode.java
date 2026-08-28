package deal.semantic.ir;

/**
 * The closed capture-mode set of {@code deal.semantic-ir/1} (parent D15;
 * schema S3).
 *
 * <p>Closed set — exactly {@link #VALUE}, {@link #SHARED_CELL}, and
 * {@link #REEVALUATE_THUNK}; no open or unknown fallback member and no
 * external extension point exist. The lowerer maps every adaptation source
 * to exactly one mode from checker facts (the closed D15 shape map):
 * plain reassignable binding → {@code SHARED_CELL}; non-identifier
 * expression → {@code REEVALUATE_THUNK}; already-materialized function
 * value operand, or binding with a {@link BindingImmutabilityProof} →
 * {@code VALUE}. The lowerer never chooses a mode from target knowledge.</p>
 */
public enum CaptureMode {

    /** Retain one source function identity for the adapter lifetime. */
    VALUE,

    /** Load the binding's current value with generation-checked semantics per invocation. */
    SHARED_CELL,

    /** Re-execute the recorded thunk block per invocation. */
    REEVALUATE_THUNK
}
