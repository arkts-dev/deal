package deal.module;

import deal.ast.ExpressionNode;
import deal.checker.Symbol;
import deal.diagnostics.DiagnosticRange;
import deal.types.Type;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The parent-pinned resolved default expression shape (design source
 * {@code deal-v1.2-int32-and-bytes-architecture} D5, adopted verbatim;
 * carrier-shape domain {@code default-plan-carriers} D2):
 *
 * <pre>
 * ResolvedDefaultExpression(expressionAst, resolvedExpressionType,
 *   sourceRange, declaringLexicalContext, resolvedBindings,
 *   semanticIr, canonicalSemanticContent, semanticDigest,
 *   runtimeResources: ordered Set&lt;RuntimeResourceReference&gt;)
 * </pre>
 *
 * <ul>
 *   <li>{@code expressionAst} — the checked default expression AST node
 *       (the sealed {@link ExpressionNode} root over the fourteen
 *       {@code deal.ast} expression kinds).</li>
 *   <li>{@code resolvedExpressionType} — the expression's resolved type
 *       (checked against the field's resolved type; E3001 at the default
 *       range is the producer-side gate).</li>
 *   <li>{@code sourceRange} — the default expression's complete scalar
 *       range through the complete-range carrier (the E3001 anchor).</li>
 *   <li>{@code declaringLexicalContext} — the forward seam for the
 *       declaring lexical/import context the default resolves in;
 *       content owned by the planner epic (ISSUE-0541).</li>
 *   <li>{@code resolvedBindings} — the per-name resolution records for
 *       non-imported targets (local/lexical bindings, same-module
 *       functions and classes, intrinsics) as the checker's sealed
 *       immutable {@link Symbol} records, insertion-ordered and
 *       unmodifiable; the exact key semantics are owned by the planner
 *       epic (ISSUE-0541). Imported targets are not recorded here —
 *       they publish {@link RuntimeResourceReference} entries in
 *       {@code runtimeResources} instead
 *       ({@code provider-versioned-default-plans} D3/D4).</li>
 *   <li>{@code semanticIr} — the forward seam for the structured typed
 *       evaluator IR; content owned by the planner epic (ISSUE-0541)
 *       and consumed by the serializer (ISSUE-0542).</li>
 *   <li>{@code canonicalSemanticContent} — the canonical serialization
 *       of the typed semantics, {@code null} until the serializer epic
 *       completes it through {@link #withCanonicalSemantics}; equality
 *       over canonical content is byte-for-byte.</li>
 *   <li>{@code semanticDigest} — SHA-256 over the length-prefixed UTF-8
 *       of the canonical content, an index only, {@code null} until the
 *       serializer epic completes it through
 *       {@link #withCanonicalSemantics}.</li>
 *   <li>{@code runtimeResources} — the ordered set of imported runtime
 *       resource references, insertion-ordered and unmodifiable.</li>
 * </ul>
 *
 * <p><b>Ordering and deduplication are producer obligations, documented
 * here and never validated ({@code default-plan-carriers} D5):</b>
 * {@code runtimeResources} is ordered by first occurrence in the typed
 * evaluator IR walk and deduplicated by semantic resource identity (the
 * first occurrence kept); reference ranges are the consuming
 * expression's complete scalar ranges. The carrier only enforces set
 * semantics — duplicate elements (structural record equality) are
 * rejected at construction and completion.</p>
 *
 * <p><b>Completion lifecycle ({@code default-plan-carriers} D6):</b> the
 * planner epic (ISSUE-0541) constructs this record with
 * {@code canonicalSemanticContent == null}, {@code semanticDigest ==
 * null}, and an empty {@code runtimeResources}. The serializer epic
 * (ISSUE-0542) is the sole producer of completed instances through
 * {@link #withCanonicalSemantics} — the only mutation surface; every
 * other field is fixed at construction. An empty completed
 * {@code runtimeResources} is the correct state for defaults whose IR
 * walk visits no runtime resource (e.g. a literal default with no
 * imported reference).</p>
 *
 * <p>Immutable and deterministic: no address, timestamp, ordinal, or
 * process state enters any field, so byte-equal inputs produce equal
 * instances. Identities, digests, and canonical content are
 * compiler-internal and never appear in runtime descriptors, diagnostic
 * type names, public export keys, or source-language values.</p>
 *
 * @param expressionAst             the checked default expression AST
 * @param resolvedExpressionType    the expression's resolved type
 * @param sourceRange               the default expression's complete
 *                                  scalar range
 * @param declaringLexicalContext   the declaring lexical context seam
 * @param resolvedBindings          the insertion-ordered per-name
 *                                  resolution records
 * @param semanticIr                the typed evaluator IR seam
 * @param canonicalSemanticContent  the canonical semantic content, or
 *                                  {@code null} before serializer
 *                                  completion
 * @param semanticDigest            the semantic digest, or {@code null}
 *                                  before serializer completion
 * @param runtimeResources          the ordered imported runtime
 *                                  resource references
 */
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
     * @param newCanonicalSemanticContent the completed canonical
     *                                    semantic content
     * @param newSemanticDigest           the completed semantic digest
     * @param newRuntimeResources         the completed ordered runtime
     *                                    resources (possibly empty)
     * @return a new completed instance; this instance is unchanged
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
