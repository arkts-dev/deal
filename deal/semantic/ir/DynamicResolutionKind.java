package deal.semantic.ir;

public enum DynamicResolutionKind {

    /**
     * A DEAL body executes the call ({@code LoweredBody}, or an adapter
     * whose resolved source is a DEAL body): the recorded
     * {@code FUNCTION_RETURN} cell executes by the callee's source
     * {@code RETURN}.
     */
    DEAL_BODY,

    /**
     * A host function executes the call ({@code HostFunction},
     * {@code HostFunctionValue}, or an adapter whose resolved source is
     * host): the recorded {@code HOST_TO_DEAL} + {@code HOST_SYNC_RETURN}
     * cell executes by the call op.
     */
    HOST,

    /**
     * A retained-ABI external function executes the call
     * ({@code ExternalFunction} with {@code RETAINED_ABI}, or an adapter
     * whose resolved source is retained-ABI external): the recorded
     * {@code EXTERNAL_RETURN} cell executes by the call op.
     */
    EXTERNAL,

    /**
     * A shared-body external function executes the call
     * ({@code ExternalFunction} with {@code SHARED_BODY}, or an adapter
     * whose resolved source is shared-body external): zero caller-side
     * return boundaries — the callee's {@code RETURN} under its
     * {@code EXTERNAL_ENTRY} runs the single {@code EXTERNAL_RETURN} in
     * the callee unit and the CALL terminal records the checked value
     * without re-checking.
     */
    SHARED_BODY
}
