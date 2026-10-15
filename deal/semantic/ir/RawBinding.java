package deal.semantic.ir;

import java.util.Objects;

/**
 * One function-execution-binding entry of the validator's raw intermediate
 * model (schema S6): the allocation identity key plus the complete closed
 * binding-shape member set — the shape tag itself and every closed enum
 * position ({@code captureMode}, {@code executionOwner},
 * {@code intrinsicKind}) carried as raw strings, every semantic-ID
 * position carried as its structured id, and every descriptor position
 * carried as raw canonical text — so out-of-set names reach the
 * validator's R-ENUM check byte-intact and no non-null record component
 * of a binding variant disappears at its raw registry position. The
 * adapter {@code sourceRef} is a {@link RawSourceRef}: its discriminator
 * stays raw (an unknown shape reaches the R-ENUM clause with its members
 * intact) and its recognized shapes carry their structured members.
 *
 * <p>The variant member sets mirror
 * {@link ContractSnapshotCanonicalizer#bindingJson(FunctionExecutionBinding)}
 * (the typed mapping is also the complete raw registry mapping):</p>
 *
 * <ul>
 *   <li>{@code loweredBody}: {@code functionId}, {@code blockId}.</li>
 *   <li>{@code adapter}: {@code adaptOpId}, {@code captureMode},
 *       {@code sourceRef}, {@code sourceSignature},
 *       {@code targetSignature}.</li>
 *   <li>{@code hostFunction}: {@code hostModuleId} (as {@link #modulePath()}),
 *       {@code exportName}, {@code descriptor}.</li>
 *   <li>{@code hostFunctionValue}: {@code hostModuleId},
 *       {@code materializingBoundaryOpId}, {@code descriptor}.</li>
 *   <li>{@code externalFunction}: {@code moduleId} (as {@link #modulePath()}),
 *       {@code exportName}, {@code descriptor}, {@code executionOwner}.</li>
 *   <li>{@code intrinsicFunction}: {@code intrinsicKind},
 *       {@code descriptor}.</li>
 *   <li>{@code dynamicFunctionValue}: {@code materializingBoundaryOpId}
 *       (the preserved wire name of the typed {@code materializingOpId}),
 *       {@code descriptor}.</li>
 * </ul>
 *
 */
public record RawBinding(
    long allocationId,
    String shape,
    String modulePath,
    String exportName,
    String executionOwner,
    String captureMode,
    String descriptor,
    String sourceSignature,
    String targetSignature,
    OpId materializingBoundaryOpId,
    String intrinsicKind,
    FunctionId functionId,
    BlockId blockId,
    OpId adaptOpId,
    RawSourceRef sourceRef
) {

    public RawBinding {
        Objects.requireNonNull(shape, "shape must not be null");
    }
}
