package deal.semantic;

import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.util.Objects;

public final class ContainerPayloadDescriptors {

    public static final String DESCRIPTOR_UNREPRESENTABLE = "DESCRIPTOR_UNREPRESENTABLE";

    private ContainerPayloadDescriptors() {
        // Static surface only; no instances and no state.
    }

    /**
     * A descriptor-production fact defect of the E3 payload bridge:
     * {@link Type.Error} — at any depth of a supported variant —
     * reached an E3 descriptor-payload position. The unit-production
     * seam owns the conversion into E6005
     * ({@code DESCRIPTOR_UNREPRESENTABLE} via
     * {@link #loweringFailureDetail(ModuleId, Defect)}); this exception
     * is internal control flow, never a crash and never an invented
     * descriptor.
     */
    public static final class Defect extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Defect(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Derives the array-element descriptor of an {@code ARRAY_NEW}
     * payload position (D1/D2): the verbatim D2 table over the element
     * type, recursively. Deterministic and total over the eleven
     * supported variants; {@link Type.Error} — at any depth —
     * raises {@link Defect} (fail closed, never an invented descriptor).
     *
     */
    public static RuntimeDescriptor elementDescriptorOf(Type elementType) {
        Objects.requireNonNull(elementType, "elementType must not be null");
        try {
            return DescriptorService.describe(elementType);
        } catch (DescriptorService.Defect defect) {
            throw new Defect("ContainerPayloadDescriptors.elementDescriptorOf: "
                + defect.getMessage(), defect);
        }
    }

    /**
     * Derives the result descriptor of a table-read contextual type or of
     * the fixed {@code string}/{@code int}/{@code table} result types
     * (D1/D2): the verbatim D2 table over the result type, recursively.
     * Deterministic and total over the ten supported variants;
     * {@link Type.Error} — at any depth — raises
     * {@link Defect} (fail closed, never an invented descriptor).
     *
     */
    public static RuntimeDescriptor resultDescriptorOf(Type resultType) {
        Objects.requireNonNull(resultType, "resultType must not be null");
        try {
            return DescriptorService.describe(resultType);
        } catch (DescriptorService.Defect defect) {
            throw new Defect("ContainerPayloadDescriptors.resultDescriptorOf: "
                + defect.getMessage(), defect);
        }
    }

    /**
     * The pinned failure-carrier seam (D2): produces the named
     * {@code DESCRIPTOR_UNREPRESENTABLE} failure carrying the exact
     * {@link LoweringFailureDetail} fields for a {@link Defect} raised by
     * one of the two derivations — {@code module}, {@code capability
     * CONTAINERS_AND_STRINGS}, {@code validatorRule
     * DESCRIPTOR_UNREPRESENTABLE}, {@code semanticProfile
     * DEAL_V1_2_INT32}, {@code irVersion deal.semantic-ir/1}, and the
     * {@code ContainerPayloadDescriptors} origin. The unit-production
     * seam (the stage's claiming seam, C5) converts the returned detail
     * into the E6005 diagnostic through
     * {@code FailureContractRegistry.e6005(detail)}; this bridge
     * constructs no diagnostic and no descriptor on this path.
     *
     */
    public static LoweringFailureDetail loweringFailureDetail(ModuleId module, Defect defect) {
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(defect, "defect must not be null");
        return new LoweringFailureDetail(
            module.path(),
            SemanticCapability.CONTAINERS_AND_STRINGS,
            DESCRIPTOR_UNREPRESENTABLE,
            SemanticProfile.DEAL_V1_2_INT32,
            LoweredModuleUnit.FORMAT_VERSION,
            "ContainerPayloadDescriptors " + DESCRIPTOR_UNREPRESENTABLE
                + " (" + defect.getMessage() + ")");
    }
}
