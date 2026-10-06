package deal.semantic.ir;

import java.util.Objects;

public final class DynamicReturnBoundaryProtocol {

    private DynamicReturnBoundaryProtocol() {
        // Static surface; no instances.
    }

    /**
     * Classifies a resolved execution binding into the closed runtime
     * resolution class.
     *
     */
    public static DynamicResolutionKind kindOf(FunctionExecutionBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        return switch (binding) {
            case FunctionExecutionBinding.LoweredBody ignored -> DynamicResolutionKind.DEAL_BODY;
            case FunctionExecutionBinding.HostFunction ignored -> DynamicResolutionKind.HOST;
            case FunctionExecutionBinding.HostFunctionValue ignored -> DynamicResolutionKind.HOST;
            case FunctionExecutionBinding.ExternalFunction external ->
                external.executionOwner() == ExternalExecutionOwner.SHARED_BODY
                    ? DynamicResolutionKind.SHARED_BODY
                    : DynamicResolutionKind.EXTERNAL;
            case FunctionExecutionBinding.IntrinsicFunction ignored ->
                DynamicResolutionKind.HOST;
            case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                throw new IllegalArgumentException("a DynamicFunctionValue registration "
                    + "names no execution class (the producing op "
                    + dynamic.materializingOpId() + " materialized a value whose class is "
                    + "resolved from the runtime value's producing registration); "
                    + "kindOf(sourceBinding) applies after that resolution");
            case FunctionExecutionBinding.AdapterBinding ignored ->
                throw new IllegalArgumentException("an AdapterBinding resolution derives its "
                    + "class from the D15-resolved source binding (kindOf(sourceBinding)); "
                    + "the adapter carries no source kind of its own");
        };
    }

    /**
     * Selects the single recorded return-boundary cell the runtime
     * executes for the resolved class.
     *
     */
    public static ReturnBoundarySelection select(KindPayload.DynamicReturnBoundary boundary,
                                                 DynamicResolutionKind kind) {
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case DEAL_BODY -> new ReturnBoundarySelection.CalleeReturn(
                boundary.dealBodyBoundaryOpId());
            case HOST -> new ReturnBoundarySelection.CallTerminal(
                boundary.hostBoundaryOpId(), BoundaryKind.HOST_TO_DEAL);
            case EXTERNAL -> new ReturnBoundarySelection.CallTerminal(
                boundary.externalBoundaryOpId(), BoundaryKind.EXTERNAL_RETURN);
            case SHARED_BODY -> ReturnBoundarySelection.None.INSTANCE;
        };
    }
}
