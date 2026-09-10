package deal.semantic.ir;

/**
 * The closed call-mode set of {@code deal.semantic-ir/1} (parent D13;
 * schema S3).
 *
 * <p>Closed set — exactly {@link #DIRECT}, {@link #INDIRECT},
 * {@link #HOST}, and {@link #EXTERNAL}; no open or unknown fallback member
 * and no external extension point exist. The D13 machine assigns parameter
 * and return boundaries per mode from the closed boundary-assignment
 * table; consumers never infer a mode from target knowledge.</p>
 */
public enum CallMode {

    /** A call whose callee binding is recorded inline (static). */
    DIRECT,

    /**
     * A call resolving the callee {@code ValueId}'s allocation identity
     * at execution — the mode of both the statically registered
     * {@code Indirect} callee and the dynamically resolved
     * {@link KindPayload.CallCallee.Dynamic} callee (the latter is
     * admissible only under this mode).
     */
    INDIRECT,

    /** A call to an imported host function or host-materialized function value. */
    HOST,

    /** A call across a shared/shadow module edge (SHARED_BODY or RETAINED_ABI). */
    EXTERNAL
}
