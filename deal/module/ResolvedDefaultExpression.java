package deal.module;

import deal.ast.ExpressionNode;
import deal.checker.Symbol;
import deal.diagnostics.DiagnosticRange;
import deal.types.Type;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ResolvedDefaultExpression(
    ExpressionNode expressionAst,
    Type resolvedExpressionType,
    DiagnosticRange sourceRange,
    DeclaringLexicalContext declaringLexicalContext,
    Map<String, Symbol> resolvedBindings,
    TypedEvaluatorIr semanticIr,
    String canonicalSemanticContent,
    String semanticDigest,
    Set<RuntimeResourceReference> runtimeResources
) {

    public ResolvedDefaultExpression {
        Objects.requireNonNull(expressionAst, "expressionAst");
        Objects.requireNonNull(resolvedExpressionType, "resolvedExpressionType");
        Objects.requireNonNull(sourceRange, "sourceRange");
        Objects.requireNonNull(declaringLexicalContext, "declaringLexicalContext");
        resolvedBindings =
            CarrierCollections.bindingsCopy(resolvedBindings, "resolvedBindings");
        Objects.requireNonNull(semanticIr, "semanticIr");
        runtimeResources =
            CarrierCollections.orderedSetCopy(runtimeResources, "runtimeResources");
    }

    /**
     * The serializer-owned completion seam ({@code default-plan-carriers}
     * D6): returns a new instance carrying the completed canonical
     * content, semantic digest, and ordered runtime resources, with
     * every other field byte-identical. This is the only mutation
     * surface of the record.
     *
     * <p>The completed {@code runtimeResources} may be any size
     * including the empty set — a default whose typed evaluator IR walk
     * visits no runtime resource completes with an empty set. Null
     * arguments and duplicate elements are rejected; insertion order is
     * preserved.</p>
     *
     */
    public ResolvedDefaultExpression withCanonicalSemantics(
            String newCanonicalSemanticContent,
            String newSemanticDigest,
            Set<RuntimeResourceReference> newRuntimeResources) {
        Objects.requireNonNull(newCanonicalSemanticContent,
            "canonicalSemanticContent");
        Objects.requireNonNull(newSemanticDigest, "semanticDigest");
        Objects.requireNonNull(newRuntimeResources, "runtimeResources");
        return new ResolvedDefaultExpression(
            expressionAst, resolvedExpressionType, sourceRange,
            declaringLexicalContext, resolvedBindings, semanticIr,
            newCanonicalSemanticContent, newSemanticDigest,
            newRuntimeResources);
    }
}
