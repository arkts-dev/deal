package deal.semantic.ir;

import java.util.Objects;

/**
 * One function-execution-binding entry of the validator's raw intermediate
 * model (schema S2/S6): the allocation identity key plus the closed
 * binding-shape positions carried as raw strings ({@code captureMode},
 * {@code executionOwner}, and the shape tag itself), so out-of-set names
 * reach the validator's R-ENUM check byte-intact.
 *
 */
public record RawBinding(long allocationId, String shape, String modulePath, String exportName,
                         String executionOwner, String captureMode, String descriptor,
                         String targetSignature, OpId materializingBoundaryOpId,
                         String intrinsicKind) {

    public RawBinding {
        Objects.requireNonNull(shape, "shape must not be null");
    }

    /**
     * The nine-position constructor (the pre-intrinsic canonical shape):
     * a binding without an intrinsic kind — every binding shape of the
     * closed set except {@code intrinsicFunction}.
     *
     */
    public RawBinding(long allocationId, String shape, String modulePath, String exportName,
                      String executionOwner, String captureMode, String descriptor,
                      String targetSignature, OpId materializingBoundaryOpId) {
        this(allocationId, shape, modulePath, exportName, executionOwner, captureMode,
            descriptor, targetSignature, materializingBoundaryOpId, null);
    }

    public RawBinding(long allocationId, String shape, String modulePath, String exportName,
                      String executionOwner, String captureMode, String descriptor,
                      String targetSignature) {
        this(allocationId, shape, modulePath, exportName, executionOwner, captureMode,
            descriptor, targetSignature, null, null);
    }
}
