package deal.semantic;

import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.util.Objects;

/**
 * The pre-E4 descriptor bridge of the container/string construct stage
 * (ISSUE-0232 D2): the single E3-owned {@code Type}→{@link
 * RuntimeDescriptor} derivation for exactly the descriptor positions the
 * stage's payload shapes introduce —
 *
 * <ul>
 *   <li>{@link #elementDescriptorOf(Type)} — the element descriptor of an
 *       array-typed literal's {@code ARRAY_NEW} payload (one derivation
 *       per element type, shared by every element boundary child);</li>
 *   <li>{@link #resultDescriptorOf(Type)} — the result descriptor of a
 *       table-read's contextual type ({@code MEMBER_READ} +
 *       {@code CONTEXTUAL_TABLE_READ} child) and of the fixed
 *       {@code string}/{@code int}/{@code table} result types of the
 *       stage's operations.</li>
 * </ul>
 *
 * <p>These are exactly the two derivation positions of this component —
 * nothing else: there is no third {@code Type}→{@link RuntimeDescriptor}
 * derivation surface here, and no general-purpose describe entry point
 * exists on this bridge.</p>
 *
 * <p><b>Mapping (D2, the verbatim DescriptorService table).</b> Each
 * derivation produces exactly the table below, recursively — no added
 * row, no removed row, no folding and no invented spelling:</p>
 *
 * <ul>
 *   <li>{@link Type.Null} → the null descriptor;</li>
 *   <li>{@link Type.Boolean} → the boolean descriptor;</li>
 *   <li>{@link Type.Int} → the signed32 int descriptor;</li>
 *   <li>{@link Type.Number} → the number descriptor;</li>
 *   <li>{@link Type.String} → the string descriptor;</li>
 *   <li>{@link Type.Table} → the table descriptor;</li>
 *   <li>{@code Class(name, modulePath)} → the class descriptor with
 *       {@code new ClassId(modulePath, name)};</li>
 *   <li>{@code Array(T)} → the array descriptor with the recursively
 *       derived element descriptor;</li>
 *   <li>{@code Nullable(T)} → the nullable descriptor with the
 *       recursively derived inner descriptor (both type hierarchies
 *       enforce the same invariants);</li>
 *   <li>{@code Func(paramTypes, returnType, isAsync)} → the function
 *       descriptor with parameter descriptors in source order, the
 *       recursively derived return descriptor, and the async marker.</li>
 * </ul>
 *
 * <p>The bridge realizes the table by delegating to {@link
 * DescriptorService#describe(Type)} — the one production component that
 * owns the table — rather than duplicating the mapping (D2 explicitly
 * rejects duplicating an E4-owned component). The observable mapping of
 * the two positions is therefore the verbatim DescriptorService table by
 * construction.</p>
 *
 * <p><b>Declared pre-E4 bridge (D2).</b> This component is <em>not</em>
 * the descriptor service and is explicitly declared a pre-E4 bridge: it
 * makes no singular-producer claim, owns no canonical-text authority
 * (canonical descriptor text is schema-owned — {@link
 * RuntimeDescriptor#canonicalSpecText()}), completes no
 * {@code exportedDescriptors} ABI fields, and serves only
 * E3's payload positions.
 * The recorded E4 retirement hand-off is
 * {@link #E4_RETIREMENT_HANDOFF}.</p>
 *
 * <p><b>Fail closed (D2).</b> {@link Type.Bytes} and {@link Type.Error}
 * — at any depth of a supported variant — have no descriptor member in
 * {@code deal.semantic-ir/1} and must never be represented. Both
 * derivations never invent a descriptor and never crash: they raise
 * {@link Defect} (internal control flow), and
 * {@link #loweringFailureDetail(ModuleId, Defect)} produces the named
 * {@code DESCRIPTOR_UNREPRESENTABLE} failure carrying the exact
 * {@link LoweringFailureDetail} fields — {@code module}, {@code
 * capability CONTAINERS_AND_STRINGS}, {@code validatorRule
 * DESCRIPTOR_UNREPRESENTABLE}, {@code semanticProfile DEAL_V1_2_INT32},
 * {@code irVersion deal.semantic-ir/1}, {@code origin} — and nothing
 * else. The conversion of that detail into the E6005 diagnostic through
 * {@code FailureContractRegistry.e6005} happens at the unit-production
 * seam (the stage's claiming seam, C5, wires it); this bridge constructs
 * no diagnostic itself.</p>
 *
 * <p>The component is static, pure, deterministic, stateless, and
 * executes no host code.</p>
 */
public final class ContainerPayloadDescriptors {

    /**
     * The fact-defect identifier carried in the {@code validatorRule}
     * field of the E6005 diagnostic for an unrepresentable type
     * ({@link Type.Bytes}/{@link Type.Error}).
     */
    public static final String DESCRIPTOR_UNREPRESENTABLE = "DESCRIPTOR_UNREPRESENTABLE";

    /**
     * The recorded E4 retirement hand-off (D2, recorded in this
     * component's declaration): from E4's gate onward
     * {@code DescriptorService} is the only {@code Type}→descriptor
     * producer for common units and this bridge is retired;
     * E4's producer-singularity test is the mechanical enforcement (the
     * singularity pin fails while any other {@code Type}→descriptor
     * call site exists).
     */
    public static final String E4_RETIREMENT_HANDOFF =
        "From E4's gate onward DescriptorService is the only Type->descriptor producer for "
            + "common units and ContainerPayloadDescriptors is retired; E4's "
            + "producer-singularity test is the mechanical enforcement (the singularity pin "
            + "fails while any other Type->descriptor call site exists).";

    private ContainerPayloadDescriptors() {
        // Static surface only; no instances and no state.
    }

    /**
     * A descriptor-production fact defect of the E3 payload bridge:
     * {@link Type.Bytes} or {@link Type.Error} — at any depth of a
     * supported variant — reached an E3 descriptor-payload position. The
     * unit-production seam owns the conversion into E6005
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
     * type, recursively. Deterministic and total over the ten supported
     * variants; {@link Type.Bytes} and {@link Type.Error} — at any
     * depth — raise {@link Defect} (fail closed, never an invented
     * descriptor).
     *
     * @param elementType the checked array element type; non-null
     * @return the mapped {@code RuntimeDescriptor} (D2 table); its
     *         {@link RuntimeDescriptor#canonicalSpecText()} is the
     *         schema-owned canonical text
     * @throws Defect when {@code elementType} is, or contains,
     *         {@link Type.Bytes} or {@link Type.Error}
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
     * {@link Type.Bytes} and {@link Type.Error} — at any depth — raise
     * {@link Defect} (fail closed, never an invented descriptor).
     *
     * @param resultType the checked result type; non-null
     * @return the mapped {@code RuntimeDescriptor} (D2 table); its
     *         {@link RuntimeDescriptor#canonicalSpecText()} is the
     *         schema-owned canonical text
     * @throws Defect when {@code resultType} is, or contains,
     *         {@link Type.Bytes} or {@link Type.Error}
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
     * @param module the module whose unit-production seam hit the defect;
     *               non-null
     * @param defect the defect raised by {@link #elementDescriptorOf(Type)}
     *               or {@link #resultDescriptorOf(Type)}; non-null
     * @return the exact {@code LoweringFailureDetail} of the
     *         {@code DESCRIPTOR_UNREPRESENTABLE} failure
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
