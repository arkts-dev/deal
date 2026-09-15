package deal.module;

/**
 * The named forward seam for the declaring lexical context of a default
 * expression (design source {@code default-plan-carriers} D2/D7, the
 * carrier-shape domain of ISSUE-0540).
 *
 * <p>The parent shape
 * {@code ResolvedDefaultExpression(expressionAst, resolvedExpressionType,
 * sourceRange, declaringLexicalContext, ...)} pins this field's name and
 * position but no content grammar: the default planner resolves every
 * default in its declaring module's lexical/import context
 * ({@code provider-versioned-default-plans} D3), and the exact content
 * semantics of this seam are owned by the planner epic (ISSUE-0541,
 * {@code DefaultSemanticPlanner}/{@code DeclarationSemanticAnalyzer}).</p>
 *
 * <p>This epic pins only the type name and the immutability obligation:
 * every implementation of this interface must be immutable and
 * deterministic (no address, timestamp, ordinal, or process state), and
 * the carriers never mutate a context. This is a marker seam — no
 * members are declared here, so no consumer can depend on invented
 * planner content before the planner epic defines it.</p>
 */
public interface DeclaringLexicalContext {
}
