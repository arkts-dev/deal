package deal.semantic.ir;

import java.util.Objects;

/**
 * One function-execution-binding entry of the validator's raw intermediate
 * model (schema S2/S6): the allocation identity key plus the closed
 * binding-shape positions carried as raw strings ({@code captureMode},
 * {@code executionOwner}, and the shape tag itself), so out-of-set names
 * reach the validator's R-ENUM check byte-intact.
 *
 * @param allocationId    the allocation identity key; non-negative
 * @param shape           the closed binding shape tag ({@code loweredBody},
 *                        {@code adapter}, {@code hostFunction},
 *                        {@code hostFunctionValue}, {@code externalFunction})
 * @param modulePath      the owning module path (host/external); {@code null} otherwise
 * @param exportName      the export name (host/external); {@code null} otherwise
 * @param executionOwner  the raw execution owner (external); {@code null} otherwise
 * @param captureMode     the raw capture mode (adapter); {@code null} otherwise
 * @param descriptor      the raw declared descriptor text (host/external); {@code null} otherwise
 * @param targetSignature the raw adapter target signature text (adapter); {@code null} otherwise
 */
public record RawBinding(long allocationId, String shape, String modulePath, String exportName,
                         String executionOwner, String captureMode, String descriptor,
                         String targetSignature) {

    public RawBinding {
        Objects.requireNonNull(shape, "shape must not be null");
    }
}
