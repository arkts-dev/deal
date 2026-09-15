package deal.semantic.ir;

import java.util.Objects;

/**
 * The closed runtime return-boundary selection of a dynamically resolved
 * {@code CALL} (ISSUE-0531): the single recorded {@code BOUNDARY} cell
 * the runtime executes for the resolved {@link DynamicResolutionKind},
 * together with its execution owner (parent D13 — the return boundary is
 * checked exactly once per completed call).
 *
 * <p>Closed sealed family — exactly the three shapes below; no other
 * selection exists:</p>
 *
 * <ul>
 *   <li>{@link CalleeReturn} — the {@code FUNCTION_RETURN} cell of the
 *       {@code DEAL_BODY} resolution, executed by the callee's source
 *       {@code RETURN} (the recorded op is parented to that RETURN).</li>
 *   <li>{@link CallTerminal} — the caller-owned cell of the
 *       {@code HOST} ({@code HOST_TO_DEAL}) or {@code EXTERNAL}
 *       ({@code EXTERNAL_RETURN}) resolution, executed by the call op;
 *       the recorded kind is exactly the closed per-class kind.</li>
 *   <li>{@link None} — the {@code SHARED_BODY} resolution: zero
 *       caller-side return boundaries; the callee's {@code RETURN} under
 *       its {@code EXTERNAL_ENTRY} runs the single {@code EXTERNAL_RETURN}
 *       in the callee unit and the CALL terminal records the checked
 *       value without re-checking.</li>
 * </ul>
 */
public sealed interface ReturnBoundarySelection
    permits ReturnBoundarySelection.CalleeReturn, ReturnBoundarySelection.CallTerminal,
            ReturnBoundarySelection.None {

    /**
     * The DEAL-body cell: {@code FUNCTION_RETURN} executed by the
     * callee's source {@code RETURN}.
     *
     * @param boundaryOpId the recorded {@code FUNCTION_RETURN} boundary
     *                     op id; non-null
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
     * @param boundaryOpId the recorded return boundary op id; non-null
     * @param kind         the recorded cell's closed boundary kind;
     *                     exactly {@code HOST_TO_DEAL} or
     *                     {@code EXTERNAL_RETURN}
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
