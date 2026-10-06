package deal.semantic;

import deal.semantic.ir.BindingImmutabilityProof;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AdapterCreationRule {

    /**
     * The closed creation-rule position kinds: the two variable
     * positions (the only positions whose classification can admit the
     * adaptation arm) and the typed-boundary position (never adapts).
     */
    public enum PositionKind {

        /** A {@code let} initializer ({@code VARIABLE_DECLARATION}). */
        VARIABLE_INITIALIZER,

        /** An assignment target ({@code VARIABLE_ASSIGNMENT}). */
        VARIABLE_ASSIGNMENT_TARGET,

        /** Every other {@link BoundaryKind} — never adapts. */
        TYPED_BOUNDARY
    }

    /**
     * The closed classification dispositions of the creation rule
     * (B6/D15): what the lowerer does at the position before choosing
     * any shape.
     */
    public enum Disposition {

        /** Exact signature — store the value directly; no {@code FUNCTION_ADAPT}. */
        DIRECT_STORE,

        /** Assignable-but-not-exact — exactly one adaptation candidate. */
        ADAPT,

        /**
         * Non-assignable — never reaches lowering from checked source
         * (E3001/E5004 frontend); a host-materialized wrong signature
         * fails the position's own boundary (E4's machinery) and the
         * classifier records that failure expectation.
         */
        NON_ASSIGNABLE,

        /** The position is not function-typed — the creation rule does not apply. */
        NOT_FUNCTION_FLOW,

        /** A typed boundary position — direct flow only; never adapts. */
        BOUNDARY_DIRECT
    }

    /**
     * The prepared wiring point of an adaptation candidate (B6/B9's
     * creation-wiring target): the adapted position's own
     * {@code VARIABLE_DECLARATION}/{@code VARIABLE_ASSIGNMENT} boundary
     * chain into which the adapter result will be wired as its direct
     * input — the {@code VARIABLE_DECLARATION} boundary op for an
     * annotated declaration, the {@code VARIABLE_ASSIGNMENT} boundary op
     * for an assignment target, or the {@code BINDING_INIT} op for an
     * inferred declaration (no boundary op exists there — B9).
     */
    public enum WiringTargetKind {

        /** The position's own {@code VARIABLE_DECLARATION} boundary op. */
        VARIABLE_DECLARATION_BOUNDARY,

        /** The position's own {@code VARIABLE_ASSIGNMENT} boundary op. */
        VARIABLE_ASSIGNMENT_BOUNDARY,

        /** The inferred declaration's {@code BINDING_INIT} op (no boundary). */
        BINDING_INIT
    }

    /**
     * One prepared wiring point: the op id plus its closed chain-role
     * tag.
     */
    public record WiringPoint(OpId opId, WiringTargetKind targetKind) {

        public WiringPoint {
            Objects.requireNonNull(opId, "opId must not be null");
            Objects.requireNonNull(targetKind, "targetKind must not be null");
        }
    }

    /**
     * The recorded failure expectation of a classified position: the
     * closed {@code FUNCTION_SIGNATURE} E8010 projection the position's
     * boundary raises when the executed check fails. The boundary op
     * and its executed check are E4's machinery — this record is the
     * classification-level expectation only, never a substitution for
     * the executed check (the {@code jvm-fv-sig-check-return-error}
     * behavior is driven by the integration child through E4's
     * realization component).
     *
     */
    public record FailureExpectation(String code, FailurePolicyId policy,
                                     BoundaryKind boundaryKind,
                                     String messageTemplate) {

        public FailureExpectation {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(policy, "policy must not be null");
            Objects.requireNonNull(boundaryKind, "boundaryKind must not be null");
            Objects.requireNonNull(messageTemplate, "messageTemplate must not be null");
        }
    }

    /**
     * One derived adaptation candidate of an assignable-but-not-exact
     * position (B6): the derived source/target signature pair (which
     * re-derives as assignable-but-not-exact), the recorded proof fact
     * for the shape-map child (present exactly when the source is a
     * binding identifier whose dominant incarnation carries the
     * conservative {@code BindingImmutabilityProof} — B7), the prepared
     * wiring point, and the registry child's producer facts when the
     * source is a host/external function value (T4 seam). This child
     * emits no {@code FUNCTION_ADAPT} and chooses no mode — the
     * candidate is the position fact the shape-map child consumes.
     */
    public record AdaptationCandidate(
            RuntimeDescriptor.Func sourceSignature,
            RuntimeDescriptor.Func targetSignature,
            Optional<BindingImmutabilityProof> proof,
            WiringPoint wiringPoint,
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts) {

        public AdaptationCandidate {
            Objects.requireNonNull(sourceSignature, "sourceSignature must not be null");
            Objects.requireNonNull(targetSignature, "targetSignature must not be null");
            Objects.requireNonNull(proof, "proof must not be null");
            Objects.requireNonNull(wiringPoint, "wiringPoint must not be null");
            Objects.requireNonNull(producerFacts, "producerFacts must not be null");
        }

        /**
         * The closed pair re-derivation (B6/{@code ADAPTER_PAIR}):
         * identical async marker, identical return type, {@code M <= N}
         * with exactly equal leading M parameter types and {@code M < N}.
         *
         */
        public boolean reDerivesAsAssignableButNotExact() {
            return assignableButNotExact(sourceSignature, targetSignature);
        }
    }

    /**
     * One classified position: the closed position kind, the boundary
     * kind of the position's boundary chain, the closed disposition,
     * the single adaptation candidate (exactly when the disposition is
     * {@code ADAPT}), and the recorded failure expectation (exactly
     * when the classification records one).
     */
    public record PositionClassification(
            PositionKind positionKind,
            BoundaryKind boundaryKind,
            Disposition disposition,
            Optional<AdaptationCandidate> candidate,
            Optional<FailureExpectation> failureExpectation) {

        public PositionClassification {
            Objects.requireNonNull(positionKind, "positionKind must not be null");
            Objects.requireNonNull(boundaryKind, "boundaryKind must not be null");
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(failureExpectation, "failureExpectation must not be null");
            if (candidate.isPresent() != (disposition == Disposition.ADAPT)) {
                throw new IllegalArgumentException(
                    "a position classification carries its candidate exactly when the "
                        + "disposition is ADAPT; got " + disposition + " with candidate="
                        + candidate.isPresent());
            }
        }
    }

    /**
     * The creation-rule child's complete fact surface of one module
     * lowering: the position classifications in walk order (one per
     * classified function-typed position). Partial on a failed walk,
     * complete on success.
     */
    public record CreationRuleFacts(List<PositionClassification> classifications) {

        /** The empty fact set (the failure-path and off-mode value). */
        public static CreationRuleFacts empty() {
            return new CreationRuleFacts(List.of());
        }

        public CreationRuleFacts {
            Objects.requireNonNull(classifications, "classifications must not be null");
            classifications = List.copyOf(classifications);
        }

        /**
         * The recorded classifications with exactly the given
         * disposition, in record order.
         *
         */
        public List<PositionClassification> ofDisposition(Disposition disposition) {
            Objects.requireNonNull(disposition, "disposition must not be null");
            List<PositionClassification> matches = new ArrayList<>();
            for (PositionClassification classification : classifications) {
                if (classification.disposition() == disposition) {
                    matches.add(classification);
                }
            }
            return matches;
        }

        /**
         * The recorded adaptation candidates in record order (exactly
         * the {@code ADAPT} classifications' candidates).
         *
         */
        public List<AdaptationCandidate> candidates() {
            List<AdaptationCandidate> candidates = new ArrayList<>();
            for (PositionClassification classification : classifications) {
                classification.candidate().ifPresent(candidates::add);
            }
            return candidates;
        }
    }

    private AdapterCreationRule() {
        // Static closed classifier; no instances.
    }

    // =========================================================================
    // The closed position set (B6: the only adaptation positions)
    // =========================================================================

    /**
     * The closed position-kind map over the 27 {@link BoundaryKind}
     * values: {@code VARIABLE_DECLARATION} → {@code VARIABLE_INITIALIZER},
     * {@code VARIABLE_ASSIGNMENT} → {@code VARIABLE_ASSIGNMENT_TARGET},
     * and every other kind → {@code TYPED_BOUNDARY}. The map is total
     * and closed — an unknown kind cannot be expressed and there is no
     * fallback member.
     *
     */
    public static PositionKind positionKindOf(BoundaryKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case VARIABLE_DECLARATION -> PositionKind.VARIABLE_INITIALIZER;
            case VARIABLE_ASSIGNMENT -> PositionKind.VARIABLE_ASSIGNMENT_TARGET;
            case CLASS_FIELD_ASSIGNMENT, ARRAY_ELEMENT_ASSIGNMENT, BYTE_ELEMENT_ASSIGNMENT,
                 ARRAY_ELEMENT_READ, BYTE_ELEMENT_READ,
                 ARRAY_ELEMENT_DELETE, ARRAY_LITERAL_ELEMENT, FUNCTION_PARAMETER,
                 FUNCTION_RETURN, ASYNC_COMPLETION, CLASS_LITERAL_FIELD, CLASS_DEFAULT_FIELD,
                 UNTYPED_CLASS_INPUT, OPTIONAL_FIELD_READ, CONTEXTUAL_TABLE_READ,
                 IMPORTED_MEMBER_READ, MODULE_EXPORT, HOST_TO_DEAL, DEAL_TO_HOST,
                 STDLIB_PARAMETER, STDLIB_RETURN, EXTERNAL_PARAMETER, EXTERNAL_RETURN,
                 JSON_FROM_FIELD, JSON_TO_FIELD -> PositionKind.TYPED_BOUNDARY;
        };
    }

    /**
     * True exactly for the two variable positions — the only positions
     * whose classification admits the adaptation arm (B6: boundaries
     * never adapt).
     *
     */
    public static boolean variablePosition(BoundaryKind kind) {
        return positionKindOf(kind) != PositionKind.TYPED_BOUNDARY;
    }

    /**
     * True iff the position's classification admits the adaptation arm:
     * {@code VARIABLE_DECLARATION} and {@code VARIABLE_ASSIGNMENT} only
     * (B6 — the classifier admits no adaptation arm for any other
     * {@link BoundaryKind}).
     *
     */
    public static boolean admitsAdaptation(BoundaryKind kind) {
        return variablePosition(kind);
    }

    // =========================================================================
    // The closed creation-rule decision
    // =========================================================================

    /**
     * The closed variable-position decision (B6/D15) over checker facts:
     * exact signature → {@link Disposition#DIRECT_STORE};
     * assignable-but-not-exact → {@link Disposition#ADAPT};
     * non-assignable → {@link Disposition#NON_ASSIGNABLE}; a
     * non-function-typed target → {@link Disposition#NOT_FUNCTION_FLOW}.
     *
     */
    public static Disposition variableDisposition(Type sourceType, Type targetType) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (!(targetType instanceof Type.Func targetFunc)) {
            return Disposition.NOT_FUNCTION_FLOW;
        }
        if (!(sourceType instanceof Type.Func sourceFunc)) {
            return Disposition.NON_ASSIGNABLE;
        }
        if (Types.equals(sourceFunc, targetFunc)) {
            return Disposition.DIRECT_STORE;
        }
        if (Types.isAssignable(sourceFunc, targetFunc)) {
            return Disposition.ADAPT;
        }
        return Disposition.NON_ASSIGNABLE;
    }

    /**
     * The closed assignable-but-not-exact re-derivation over checked
     * function types (B6/{@code ADAPTER_PAIR}): identical async marker,
     * identical return type, {@code M <= N} with exactly equal leading M
     * parameter types and {@code M < N}.
     *
     */
    public static boolean assignableButNotExact(Type.Func source, Type.Func target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (source.isAsync() != target.isAsync()) {
            return false;
        }
        if (!Types.equals(source.returnType(), target.returnType())) {
            return false;
        }
        int sourceCount = source.paramTypes().size();
        int targetCount = target.paramTypes().size();
        if (sourceCount >= targetCount) {
            return false;
        }
        for (int i = 0; i < sourceCount; i++) {
            if (!Types.equals(source.paramTypes().get(i), target.paramTypes().get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The closed assignable-but-not-exact re-derivation over
     * {@link RuntimeDescriptor.Func} signatures (the candidate's
     * signatures): identical async marker, identical return type,
     * {@code M <= N} with exactly equal leading M parameter types and
     * {@code M < N}.
     *
     */
    public static boolean assignableButNotExact(RuntimeDescriptor.Func source,
                                                RuntimeDescriptor.Func target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (source.isAsync() != target.isAsync()) {
            return false;
        }
        if (!source.returnType().equals(target.returnType())) {
            return false;
        }
        int sourceCount = source.paramTypes().size();
        int targetCount = target.paramTypes().size();
        if (sourceCount >= targetCount) {
            return false;
        }
        for (int i = 0; i < sourceCount; i++) {
            if (!source.paramTypes().get(i).equals(target.paramTypes().get(i))) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Position classification
    // =========================================================================

    /**
     * Classifies one function-typed position of the closed position set:
     * the two variable positions run the closed variable decision (B6 —
     * exact → direct store, assignable-but-not-exact → one adaptation
     * candidate with the derived signatures, the proof fact, the wiring
     * point, and the producer facts; non-assignable → the recorded
     * E8010 failure expectation); every other position is a typed
     * boundary (never adapts — direct flow with the recorded E8010
     * expectation for any function-typed mismatch including
     * {@code M < N}).
     *
     */
    public static PositionClassification classify(BoundaryKind kind, Type sourceType,
                                                  Type targetType,
                                                  Optional<BindingImmutabilityProof> proof,
                                                  WiringPoint wiringPoint,
                                                  Optional<FunctionBindingRegistry
                                                      .FunctionValueMaterialization>
                                                      producerFacts) {
        Objects.requireNonNull(kind, "kind must not be null");
        if (variablePosition(kind)) {
            return classifyVariablePosition(kind, sourceType, targetType, proof,
                wiringPoint, producerFacts);
        }
        return classifyBoundaryPosition(kind, sourceType, targetType);
    }

    /**
     * The closed variable-position classification (B6): the closed
     * decision plus, for the {@code ADAPT} arm, the single derived
     * adaptation candidate — the source signature from the source
     * expression's checked type, the target signature from the declared
     * binding signature, the recorded proof fact for the shape-map
     * child (B7), the prepared wiring point, and the producer facts of
     * a host/external source (the registry child's T4 seam) — and, for
     * the {@code NON_ASSIGNABLE} arm, the recorded
     * {@code FUNCTION_SIGNATURE} E8010 failure expectation at the
     * position's own boundary.
     *
     */
    public static PositionClassification classifyVariablePosition(
            BoundaryKind kind, Type sourceType, Type targetType,
            Optional<BindingImmutabilityProof> proof, WiringPoint wiringPoint,
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(proof, "proof must not be null");
        Objects.requireNonNull(wiringPoint, "wiringPoint must not be null");
        Objects.requireNonNull(producerFacts, "producerFacts must not be null");
        PositionKind positionKind = positionKindOf(kind);
        if (positionKind == PositionKind.TYPED_BOUNDARY) {
            throw new IllegalArgumentException("the variable-position classifier admits "
                + "exactly VARIABLE_DECLARATION and VARIABLE_ASSIGNMENT; got " + kind);
        }
        Disposition disposition = variableDisposition(sourceType, targetType);
        return switch (disposition) {
            case ADAPT -> {
                if (sourceType instanceof Type.Func sourceFunc
                        && targetType instanceof Type.Func targetFunc
                        && !assignableButNotExact(sourceFunc, targetFunc)) {
                    throw new IllegalStateException("the ADAPT arm requires an "
                        + "assignable-but-not-exact pair; got source "
                        + sourceFunc.paramTypes() + " target " + targetFunc.paramTypes()
                        + " (producer defect)");
                }
                AdaptationCandidate candidate = new AdaptationCandidate(
                    funcDescriptorOf(sourceType), funcDescriptorOf(targetType), proof,
                    wiringPoint, producerFacts);
                yield new PositionClassification(positionKind, kind, disposition,
                    Optional.of(candidate), Optional.empty());
            }
            case NON_ASSIGNABLE -> new PositionClassification(positionKind, kind,
                disposition, Optional.empty(), Optional.of(e8010Expectation(kind)));
            default -> new PositionClassification(positionKind, kind, disposition,
                Optional.empty(), Optional.empty());
        };
    }

    /**
     * The closed typed-boundary-position classification (B6):
     * {@link Disposition#BOUNDARY_DIRECT} always — the classifier admits
     * no adaptation arm for any {@link BoundaryKind} other than
     * {@code VARIABLE_DECLARATION}/{@code VARIABLE_ASSIGNMENT} — with
     * the recorded {@code FUNCTION_SIGNATURE} E8010 failure expectation
     * for a function-typed position whose source/target pair is any
     * mismatch including {@code M < N} (the executed rejection is E4's
     * boundary machinery — the closed boundary-assignment table's
     * descriptor-kind rule — pinned by the
     * {@code jvm-fv-sig-check-return-error} behavior; this classifier
     * records the expectation only).
     *
     */
    public static PositionClassification classifyBoundaryPosition(BoundaryKind kind,
                                                                  Type sourceType,
                                                                  Type targetType) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (variablePosition(kind)) {
            throw new IllegalArgumentException("the boundary-position classifier admits "
                + "every BoundaryKind except VARIABLE_DECLARATION and "
                + "VARIABLE_ASSIGNMENT; got " + kind);
        }
        boolean expectation = targetType instanceof Type.Func
            && !Types.equals(sourceType, targetType);
        return new PositionClassification(PositionKind.TYPED_BOUNDARY, kind,
            Disposition.BOUNDARY_DIRECT, Optional.empty(),
            expectation ? Optional.of(e8010Expectation(kind)) : Optional.empty());
    }

    // =========================================================================
    // Derivation and expectation helpers
    // =========================================================================

    /**
     * The single descriptor producer of the classifier (the verbatim D2
     * table): the source/target signatures the adaptation candidate
     * carries derive from the checked types through exactly
     * {@link DescriptorService#describe(Type)}. A defect is a
     * producer-defect failure — never an invented signature.
     */
    private static RuntimeDescriptor.Func funcDescriptorOf(Type type) {
        try {
            return (RuntimeDescriptor.Func) DescriptorService.describe(type);
        } catch (DescriptorService.Defect defect) {
            throw new IllegalStateException("the creation-rule classifier derives "
                + "function signatures from checked types through DescriptorService "
                + "only: " + defect.getMessage(), defect);
        }
    }

    /**
     * The recorded {@code FUNCTION_SIGNATURE} E8010 failure expectation
     * at the given position's boundary: the closed code, policy, and
     * canonical primary message template come from the closed
     * failure-contract registry's {@code FUNCTION_SIGNATURE} row — the
     * same row E4's boundary machinery executes. This is the
     * classification-level expectation only; the boundary op and its
     * executed check are E4's.
     *
     */
    public static FailureExpectation e8010Expectation(BoundaryKind boundaryKind) {
        Objects.requireNonNull(boundaryKind, "boundaryKind must not be null");
        deal.semantic.ir.FailurePolicyRow row =
            FailureContractRegistry.row(FailurePolicyId.FUNCTION_SIGNATURE);
        if (row.code() == null || row.template() == null) {
            throw new IllegalStateException("the closed FUNCTION_SIGNATURE row pins the "
                + "E8010 code and the canonical template; a broken row is a producer "
                + "defect");
        }
        return new FailureExpectation(row.code().code(), FailurePolicyId.FUNCTION_SIGNATURE,
            boundaryKind, row.template());
    }
}
