package deal.semantic.ir;

import java.util.Objects;

/**
 * One function-execution-binding entry of the validator's raw intermediate
 * model (schema S2/S6): the allocation identity key plus the closed
 * binding-shape positions carried as raw strings ({@code captureMode},
 * {@code executionOwner}, and the shape tag itself), so out-of-set names
 * reach the validator's R-ENUM check byte-intact.
 *
 * @param allocationId             the allocation identity key; non-negative
 * @param shape                    the closed binding shape tag ({@code loweredBody},
 *                                 {@code adapter}, {@code hostFunction},
 *                                 {@code hostFunctionValue}, {@code externalFunction})
 * @param modulePath               the owning module path (host/external); {@code null} otherwise
 * @param exportName               the export name (host/external); {@code null} otherwise
 * @param executionOwner           the raw execution owner (external); {@code null} otherwise
 * @param captureMode              the raw capture mode (adapter); {@code null} otherwise
 * @param descriptor               the raw declared descriptor text (host/external); {@code null} otherwise
 * @param targetSignature          the raw adapter target signature text (adapter); {@code null} otherwise
 * @param materializingBoundaryOpId the materializing {@code HOST_TO_DEAL} boundary op id
 *                                 ({@code hostFunctionValue} — the producing host crossing);
 *                                 {@code null} otherwise
 */
public record RawBinding(long allocationId, String shape, String modulePath, String exportName,
                         String executionOwner, String captureMode, String descriptor,
                         String targetSignature, OpId materializingBoundaryOpId) {

    public RawBinding {
        Objects.requireNonNull(shape, "shape must not be null");
    }

    /**
     * The legacy eight-position constructor (schema S2/S6 pre-ISSUE-0531):
     * a binding without a materializing boundary op — admissible only for
     * the four shapes that carry no correlation id.
     *
     * @param allocationId    the allocation identity key; non-negative
     * @param shape           the closed binding shape tag
     * @param modulePath      the owning module path (host/external); {@code null} otherwise
     * @param exportName      the export name (host/external); {@code null} otherwise
     * @param executionOwner  the raw execution owner (external); {@code null} otherwise
     * @param captureMode     the raw capture mode (adapter); {@code null} otherwise
     * @param descriptor      the raw declared descriptor text (host/external); {@code null} otherwise
     * @param targetSignature the raw adapter target signature text (adapter); {@code null} otherwise
     */
    public RawBinding(long allocationId, String shape, String modulePath, String exportName,
                      String executionOwner, String captureMode, String descriptor,
                      String targetSignature) {
        this(allocationId, shape, modulePath, exportName, executionOwner, captureMode,
            descriptor, targetSignature, null);
    }
}
