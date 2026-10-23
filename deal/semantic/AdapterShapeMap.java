package deal.semantic;

import deal.ast.AwaitExpression;
import deal.ast.ExpressionNode;
import deal.ast.FunctionExpr;
import deal.ast.IdentifierExpr;
import deal.checker.Symbol;
import deal.semantic.ir.BindingImmutabilityProof;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.SemanticOpKind;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Backend-neutral capture classification for function arity adaptation. */
public final class AdapterShapeMap {
    public enum SourceShape {
        FUNCTION_EXPRESSION,
        BINDING_REFERENCE,
        AWAITED_RESULT,
        NON_IDENTIFIER_EXPRESSION
    }

    private AdapterShapeMap() {}

    public static SourceShape sourceShapeOf(ExpressionNode source,
                                            Function<String, Symbol> resolve) {
        Objects.requireNonNull(resolve, "resolve must not be null");
        return sourceShapeAtPosition(source);
    }

    /** Classification depends on expression shape, never identifier spelling. */
    public static SourceShape sourceShapeAtPosition(ExpressionNode source) {
        Objects.requireNonNull(source, "source must not be null");
        if (source instanceof AwaitExpression) return SourceShape.AWAITED_RESULT;
        if (source instanceof FunctionExpr) return SourceShape.FUNCTION_EXPRESSION;
        if (source instanceof IdentifierExpr) return SourceShape.BINDING_REFERENCE;
        return SourceShape.NON_IDENTIFIER_EXPRESSION;
    }

    /** Only a binding reference may carry an immutability proof. */
    public static CaptureMode selectMode(SourceShape shape,
                                         Optional<BindingImmutabilityProof> proof) {
        Objects.requireNonNull(shape, "shape must not be null");
        Objects.requireNonNull(proof, "proof must not be null");
        return switch (shape) {
            case FUNCTION_EXPRESSION -> {
                requireNoProof(proof, shape);
                yield CaptureMode.VALUE;
            }
            case AWAITED_RESULT -> {
                requireNoProof(proof, shape);
                yield CaptureMode.VALUE;
            }
            case BINDING_REFERENCE -> proof.isPresent()
                ? CaptureMode.VALUE : CaptureMode.SHARED_CELL;
            case NON_IDENTIFIER_EXPRESSION -> {
                requireNoProof(proof, shape);
                yield CaptureMode.REEVALUATE_THUNK;
            }
        };
    }

    /**
     * The proof-admissibility guard: VALUE's proof exists only for a binding source.
     */
    private static void requireNoProof(Optional<BindingImmutabilityProof> proof,
                                       SourceShape shape) {
        if (proof.isPresent()) {
            throw new IllegalArgumentException("the closed shape map records a "
                + "BindingImmutabilityProof only for a binding source; got a proof for "
                + shape + " (producer defect — the payload's proof is present iff "
                + "VALUE's operand is a binding load)");
        }
        return shape == SourceShape.NON_IDENTIFIER_EXPRESSION
            ? CaptureMode.REEVALUATE_THUNK : CaptureMode.VALUE;
    }

    /**
     * The closed position-mode map (the shape map at a statically typed
     * function-value position): exactly the {@link #selectMode} arms except
     * the non-identifier arm, which is the materialized {@code VALUE}. A
     * parameter, return, class field/default or array literal/write
     * position is a value position: its expression is evaluated exactly
     * once, at its source position, so the adapted source is that
     * materialized value. A {@code REEVALUATE_THUNK} source there would
     * re-execute the position's expression at every adapter invocation and
     * break the once-only effect and evaluation-order contract those
     * positions carry; the definition positions ({@code let} initializers
     * and variable assignments) keep the thunk arm of {@link #selectMode}.
     *
     */
    public static CaptureMode selectPositionMode(SourceShape shape,
                                                  Optional<BindingImmutabilityProof> proof) {
        Objects.requireNonNull(shape, "shape must not be null");
        Objects.requireNonNull(proof, "proof must not be null");
        if (shape == SourceShape.NON_IDENTIFIER_EXPRESSION) {
            requireNoProof(proof, shape);
            return CaptureMode.VALUE;
        }
        return selectMode(shape, proof);
    }

    /**
     * The map's binding-load arm over the IR-level proof fact (the
     * produced-source surface): a proved load → VALUE, an unproven load
     * → SHARED_CELL — the same closed fallback arm as the checker-fact
     * surface, so the two surfaces never disagree.
     *
     */
    public static CaptureMode selectModeOverBindingLoad(
            Optional<BindingImmutabilityProof> proof) {
        Objects.requireNonNull(proof, "proof must not be null");
        return proof.isPresent() ? CaptureMode.VALUE : CaptureMode.SHARED_CELL;
    }

    /** Composite sources re-execute; closure values materialize at declaration. */
    public static CaptureMode selectModeOverProducedSource(
            SemanticOpKind producingKind,
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts) {
        Objects.requireNonNull(producingKind, "producingKind must not be null");
        Objects.requireNonNull(producerFacts, "producerFacts must not be null");
        return switch (producingKind) {
            case CLOSURE_NEW -> CaptureMode.VALUE;
            case MEMBER_READ, EXPORT_READ, INDEX_READ, FIELD_READ, CALL, BINARY, BRANCH ->
                CaptureMode.REEVALUATE_THUNK;
            default -> throw new IllegalArgumentException("producing kind " + producingKind
                + " is not a closed function-value producing op of the produced-source "
                + "map (CLOSURE_NEW | MEMBER_READ | EXPORT_READ | INDEX_READ | "
                + "FIELD_READ | CALL | BINARY | BRANCH)");
        };
    }
}
