package deal.semantic.ir;

import java.util.Objects;

public sealed interface ReturnBoundarySelection
    permits ReturnBoundarySelection.CalleeReturn, ReturnBoundarySelection.CallTerminal,
            ReturnBoundarySelection.None {

    /**
     * The DEAL-body cell: {@code FUNCTION_RETURN} executed by the
     * callee's source {@code RETURN}.
     *
     */
    record CalleeReturn(OpId boundaryOpId) implements ReturnBoundarySelection {

        public CalleeReturn {
            Objects.requireNonNull(boundaryOpId, "boundaryOpId must not be null");
        }
    }

    /**
     * A caller-owned cell executed by the call op: exactly
     * {@code HOST_TO_DEAL} for the {@code HOST} resolution or
     * {@code EXTERNAL_RETURN} for the {@code EXTERNAL} resolution
     * (construction-checked — no other kind is selectable).
     *
     */
    record CallTerminal(OpId boundaryOpId, BoundaryKind kind) implements ReturnBoundarySelection {

        public CallTerminal {
            Objects.requireNonNull(boundaryOpId, "boundaryOpId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            if (kind != BoundaryKind.HOST_TO_DEAL && kind != BoundaryKind.EXTERNAL_RETURN) {
                throw new IllegalArgumentException("a call-terminal return boundary is "
                    + "exactly HOST_TO_DEAL or EXTERNAL_RETURN, got " + kind);
            }
        }
    }

    /**
     * The SHARED_BODY selection: zero caller-side return boundaries.
     */
    enum None implements ReturnBoundarySelection {

        /** The single closed instance. */
        INSTANCE
    }
}
