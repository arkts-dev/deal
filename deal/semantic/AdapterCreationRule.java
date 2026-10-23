package deal.semantic;

import deal.semantic.ir.BindingImmutabilityProof;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOpKind;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AdapterCreationRule {

    /**
     * The closed creation-rule position kinds: the statically typed
     * function-value positions whose classification can admit the
     * adaptation arm (the two variable positions, the parameter and
     * return positions, the class field/default positions, and the array
     * literal/write positions) and the typed-boundary position (never
     * adapts).
     */
    public enum PositionKind {

        /** A {@code let} initializer ({@code VARIABLE_DECLARATION}). */
        VARIABLE_INITIALIZER,

        /** An assignment target ({@code VARIABLE_ASSIGNMENT}). */
        VARIABLE_ASSIGNMENT_TARGET,

        /**
         * A call-argument position ({@code FUNCTION_PARAMETER}, and the
         * cross-module {@code EXTERNAL_PARAMETER} and stdlib
         * {@code STDLIB_PARAMETER} cells).
         */
        PARAMETER,

        /** A {@code return} expression position ({@code FUNCTION_RETURN}). */
        RETURN,

        /**
         * A class field construction position ({@code CLASS_LITERAL_FIELD}
         * for a supplied field, {@code CLASS_DEFAULT_FIELD} for a
         * default).
         */
        CLASS_FIELD,

        /** An array element write ({@code ARRAY_ELEMENT_ASSIGNMENT}). */
        ARRAY_ELEMENT_WRITE,

        /** An array literal element position ({@code ARRAY_LITERAL_ELEMENT}). */
        ARRAY_LITERAL_ELEMENT,

        /** A class field write ({@code CLASS_FIELD_ASSIGNMENT}). */
        CLASS_FIELD_WRITE,

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
        BINDING_INIT,

        /**
         * The statically typed position's own boundary op (the parameter,
         * return, class field/default and array literal/write cells — no
         * variable chain exists there).
         */
        POSITION_BOUNDARY
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
     * The closed position-kind map over the 28 {@link BoundaryKind}
     * values: {@code VARIABLE_DECLARATION} → {@code VARIABLE_INITIALIZER},
     * {@code VARIABLE_ASSIGNMENT} → {@code VARIABLE_ASSIGNMENT_TARGET},
     * {@code FUNCTION_PARAMETER} → {@code PARAMETER},
     * {@code FUNCTION_RETURN} → {@code RETURN},
     * {@code CLASS_LITERAL_FIELD}/{@code CLASS_DEFAULT_FIELD} →
     * {@code CLASS_FIELD}, {@code ARRAY_ELEMENT_ASSIGNMENT} →
     * {@code ARRAY_ELEMENT_WRITE}, {@code ARRAY_LITERAL_ELEMENT} →
     * {@code ARRAY_LITERAL_ELEMENT}, {@code CLASS_FIELD_ASSIGNMENT} →
     * {@code CLASS_FIELD_WRITE}, and every other kind →
     * {@code TYPED_BOUNDARY}. The map is total and closed — an unknown
     * kind cannot be expressed and there is no fallback member.
     *
     * <p>The adaptation positions are exactly the statically typed
     * function-value positions of a checked program: the declaration and
     * assignment positions, the call-argument ({@code FUNCTION_PARAMETER},
     * {@code EXTERNAL_PARAMETER}, {@code STDLIB_PARAMETER}) and
     * {@code return} ({@code FUNCTION_RETURN}) positions, the class
     * field construction ({@code CLASS_LITERAL_FIELD},
     * {@code CLASS_DEFAULT_FIELD}) and write
     * ({@code CLASS_FIELD_ASSIGNMENT}) positions, and the array literal
     * ({@code ARRAY_LITERAL_ELEMENT}) and write
     * ({@code ARRAY_ELEMENT_ASSIGNMENT}) positions. Every other kind is a
     * crossing of a value that no checked signature pair describes — a
     * host/external/stdlib crossing, a runtime read, a JSON walk, a
     * refinement — and stays a typed boundary that validates the exact
     * descriptor.</p>
     *
     */
    public static PositionKind positionKindOf(BoundaryKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case VARIABLE_DECLARATION -> PositionKind.VARIABLE_INITIALIZER;
            case VARIABLE_ASSIGNMENT -> PositionKind.VARIABLE_ASSIGNMENT_TARGET;
            case FUNCTION_PARAMETER, EXTERNAL_PARAMETER, STDLIB_PARAMETER ->
                PositionKind.PARAMETER;
            case FUNCTION_RETURN -> PositionKind.RETURN;
            case CLASS_LITERAL_FIELD -> PositionKind.CLASS_FIELD;
            case ARRAY_ELEMENT_ASSIGNMENT -> PositionKind.ARRAY_ELEMENT_WRITE;
            case ARRAY_LITERAL_ELEMENT -> PositionKind.ARRAY_LITERAL_ELEMENT;
            case CLASS_FIELD_ASSIGNMENT -> PositionKind.CLASS_FIELD_WRITE;
            case ARRAY_ELEMENT_READ,
                 ARRAY_ELEMENT_DELETE, ASYNC_COMPLETION,
                 UNTYPED_CLASS_INPUT, OPTIONAL_FIELD_READ, CONTEXTUAL_TABLE_READ,
                 IMPORTED_MEMBER_READ, MODULE_EXPORT, HOST_TO_DEAL, DEAL_TO_HOST,
                 STDLIB_RETURN, EXTERNAL_RETURN,
                 JSON_FROM_FIELD, JSON_TO_FIELD, NARROWED_READ ->
                PositionKind.TYPED_BOUNDARY;
        };
    }

    /**
     * The parent-aware position-kind map: the same closed classifier with
     * the two shared cell kinds resolved by the cell's parent operation —
     * a return position is the cell its own {@code RETURN} runs
     * ({@code EXTERNAL_RETURN} for a sync {@code EXTERNAL_ENTRY} and for a
     * retained-ABI external call, {@code DEAL_TO_HOST} for a callback
     * entry), and a host-declared parameter position is the
     * {@code DEAL_TO_HOST} parameter cell its {@code CALL}/{@code
     * ASYNC_START} invocation runs. A statically typed return or call
     * position is decided by the source/target signature pair, never by the
     * boundary kind the invocation shape selected: a compatible shorter
     * function value adapts at an exported, cross-module or callback
     * return and at a host-declared call parameter exactly as it does at
     * the same-module cells, while the position's own cell keeps its exact
     * descriptor check. Every other kind keeps {@link #positionKindOf}.
     */
    public static PositionKind positionKindOf(BoundaryKind kind,
                                              SemanticOpKind parentOpKind) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(parentOpKind, "parentOpKind must not be null");
        return switch (kind) {
            case EXTERNAL_RETURN -> PositionKind.RETURN;
            case DEAL_TO_HOST -> switch (parentOpKind) {
                case RETURN -> PositionKind.RETURN;
                case CALL, ASYNC_START -> PositionKind.PARAMETER;
                default -> PositionKind.TYPED_BOUNDARY;
            };
            default -> positionKindOf(kind);
        };
    }

    /**
     * True exactly for the variable positions — the positions whose own
     * boundary belongs to a {@code [value, boundary, commit]} variable
     * chain.
     *
     */
    public static boolean variablePosition(BoundaryKind kind) {
        PositionKind positionKind = positionKindOf(kind);
        return positionKind == PositionKind.VARIABLE_INITIALIZER
            || positionKind == PositionKind.VARIABLE_ASSIGNMENT_TARGET;
    }

    /**
     * True iff the position's classification admits the adaptation arm:
     * the statically typed function-value positions of
     * {@link #positionKindOf} except {@link PositionKind#TYPED_BOUNDARY}
     * (a crossing no checked signature pair describes never adapts).
     *
     */
    public static boolean admitsAdaptation(BoundaryKind kind) {
        return positionKindOf(kind) != PositionKind.TYPED_BOUNDARY;
    }

    /**
     * True iff the parent-aware position kind admits the adaptation arm
     * (the same predicate over {@link #positionKindOf(BoundaryKind,
     * SemanticOpKind)}: a host-declared call parameter or an exported,
     * cross-module and callback return cell admits it, every other
     * host/external crossing does not).
     */
    public static boolean admitsAdaptation(BoundaryKind kind,
                                           SemanticOpKind parentOpKind) {
        return positionKindOf(kind, parentOpKind) != PositionKind.TYPED_BOUNDARY;
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
     * every adaptation position runs the closed position decision (exact
     * → direct store, assignable-but-not-exact → one adaptation candidate
     * with the derived signatures, the proof fact, the wiring point, and
     * the producer facts; non-assignable → the recorded E8010 failure
     * expectation); every other position is a typed boundary (never
     * adapts — direct flow with the recorded E8010 expectation for any
     * function-typed mismatch including {@code M < N}).
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
        if (admitsAdaptation(kind)) {
            return classifyAdaptationPosition(kind, sourceType, targetType, proof,
                wiringPoint, producerFacts);
        }
        return classifyBoundaryPosition(kind, sourceType, targetType);
    }

    /**
     * The closed adaptation-position classification (B6): the closed
     * decision plus, for the {@code ADAPT} arm, the single derived
     * adaptation candidate — the source signature from the source
     * expression's checked type, the target signature from the
     * position's declared signature, the recorded proof fact for the
     * shape-map child (B7), the prepared wiring point, and the producer
     * facts of a host/external source (the registry child's T4 seam) —
     * and, for the {@code NON_ASSIGNABLE} arm, the recorded
     * {@code FUNCTION_SIGNATURE} E8010 failure expectation at the
     * position's own boundary.
     *
     */
    public static PositionClassification classifyAdaptationPosition(
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
            throw new IllegalArgumentException("the adaptation-position classifier admits "
                + "every statically typed function-value position and no typed "
                + "boundary; got " + kind);
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
     * The closed adaptation-position classification over the position's
     * own signatures (the class field/default and array write positions
     * carry the declared target as a runtime descriptor rather than a
     * checked {@link Type}): the closed descriptor decision — exact →
     * {@link Disposition#DIRECT_STORE}, assignable-but-not-exact →
     * {@link Disposition#ADAPT} with the recorded candidate,
     * non-assignable → {@link Disposition#NON_ASSIGNABLE} with the
     * recorded E8010 expectation.
     *
     */
    public static PositionClassification classifyAdaptationPosition(
            BoundaryKind kind, RuntimeDescriptor.Func sourceSignature,
            RuntimeDescriptor.Func targetSignature, WiringPoint wiringPoint) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(sourceSignature, "sourceSignature must not be null");
        Objects.requireNonNull(targetSignature, "targetSignature must not be null");
        Objects.requireNonNull(wiringPoint, "wiringPoint must not be null");
        PositionKind positionKind = positionKindOf(kind);
        if (positionKind == PositionKind.TYPED_BOUNDARY) {
            throw new IllegalArgumentException("the adaptation-position classifier admits "
                + "every statically typed function-value position and no typed "
                + "boundary; got " + kind);
        }
        if (sourceSignature.equals(targetSignature)) {
            return new PositionClassification(positionKind, kind,
                Disposition.DIRECT_STORE, Optional.empty(), Optional.empty());
        }
        if (assignableButNotExact(sourceSignature, targetSignature)) {
            AdaptationCandidate candidate = new AdaptationCandidate(sourceSignature,
                targetSignature, Optional.empty(), wiringPoint, Optional.empty());
            return new PositionClassification(positionKind, kind, Disposition.ADAPT,
                Optional.of(candidate), Optional.empty());
        }
        return new PositionClassification(positionKind, kind, Disposition.NON_ASSIGNABLE,
            Optional.empty(), Optional.of(e8010Expectation(kind)));
    }

    /**
     * The closed typed-boundary-position classification (B6):
     * {@link Disposition#BOUNDARY_DIRECT} always — the classifier admits
     * no adaptation arm for any {@link PositionKind#TYPED_BOUNDARY}
     * position (a host/external/stdlib crossing, a runtime read, a JSON
     * walk or a refinement) — with the recorded
     * {@code FUNCTION_SIGNATURE} E8010 failure expectation for a
     * function-typed position whose source/target pair is any mismatch
     * including {@code M < N} (the executed rejection is E4's boundary
     * machinery — the closed boundary-assignment table's descriptor-kind
     * rule — pinned by the {@code jvm-fv-sig-check-return-error}
     * behavior; this classifier records the expectation only).
     *
     */
    public static PositionClassification classifyBoundaryPosition(BoundaryKind kind,
                                                                  Type sourceType,
                                                                  Type targetType) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (admitsAdaptation(kind)) {
            throw new IllegalArgumentException("the boundary-position classifier admits "
                + "every typed-boundary BoundaryKind and no adaptation position; got "
                + kind);
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
