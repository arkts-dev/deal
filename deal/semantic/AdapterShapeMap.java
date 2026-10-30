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
            case BINDING_REFERENCE -> CaptureMode.VALUE;
            case NON_IDENTIFIER_EXPRESSION -> {
                requireNoProof(proof, shape);
                yield CaptureMode.VALUE;
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
                + shape + " (producer defect — only binding loads admit proofs)");
        }
    }

    /** Every adaptation position retains its source expression's evaluated value. */
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

    /** Binding loads capture their evaluated value, with or without a proof. */
    public static CaptureMode selectModeOverBindingLoad(
            Optional<BindingImmutabilityProof> proof) {
        Objects.requireNonNull(proof, "proof must not be null");
        return CaptureMode.VALUE;
    }

    /** Every admitted producer materializes its source value before adaptation. */
    public static CaptureMode selectModeOverProducedSource(
            SemanticOpKind producingKind,
            Optional<FunctionBindingRegistry.FunctionValueMaterialization> producerFacts) {
        Objects.requireNonNull(producingKind, "producingKind must not be null");
        Objects.requireNonNull(producerFacts, "producerFacts must not be null");
        return switch (producingKind) {
            case CLOSURE_NEW -> CaptureMode.VALUE;
            case MEMBER_READ, EXPORT_READ, INDEX_READ, FIELD_READ, CALL, BINARY, BRANCH ->
                CaptureMode.VALUE;
            default -> throw new IllegalArgumentException("producing kind " + producingKind
                + " is not a closed function-value producing op of the produced-source "
                + "map (CLOSURE_NEW | MEMBER_READ | EXPORT_READ | INDEX_READ | "
                + "FIELD_READ | CALL | BINARY | BRANCH)");
        };
    }
}
