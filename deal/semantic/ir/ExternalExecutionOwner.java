package deal.semantic.ir;

/**
 * The closed execution-owner set of an {@code ExternalFunction} binding
 * (parent D13; schema S3): which side executes the callee body across a
 * module edge.
 *
 * <p>Closed set — exactly {@link #SHARED_BODY} and {@link #RETAINED_ABI};
 * no open or unknown fallback member and no external extension point
 * exist. A {@code SHARED_BODY} target executes the callee unit's
 * {@code EXTERNAL_ENTRY} (validator R-EXTERNAL-ENTRY requires the recorded
 * entry); a {@code RETAINED_ABI} target has no common body — the invoking
 * op performs the ABI invocation through the {@code TargetModuleAbi}
 * function entry and runs the single {@code EXTERNAL_RETURN} boundary
 * itself.</p>
 */
public enum ExternalExecutionOwner {

    /** The callee unit's shared body executes the call. */
    SHARED_BODY,

    /** The retained target ABI executes the call; no common callee body exists. */
    RETAINED_ABI
}
