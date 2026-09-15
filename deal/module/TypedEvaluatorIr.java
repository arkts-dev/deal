package deal.module;

/**
 * The named forward seam for the structured typed evaluator IR of a
 * default expression (design source {@code default-plan-carriers} D2/D7,
 * the carrier-shape domain of ISSUE-0540;
 * {@code provider-versioned-default-plans} D4/D6).
 *
 * <p>The parent shape {@code ResolvedDefaultExpression(..., semanticIr,
 * ...)} pins this field's name and position but no IR grammar: the
 * planner records each default as typed evaluator IR — structured nodes
 * over the checked expression with resolved types, resolved bindings,
 * and spans ({@code provider-versioned-default-plans} D4) — and the
 * canonical serializer ({@code DefaultSemanticSerializer}, ISSUE-0542)
 * serializes that IR into {@code canonicalSemanticContent}. The exact
 * IR root and node grammar are owned by the planner epic (ISSUE-0541).</p>
 *
 * <p>This epic pins only the type name and the immutability obligation:
 * every implementation of this interface must be immutable and
 * deterministic (no address, timestamp, ordinal, or process state), and
 * the carriers never mutate an IR. This is a marker seam — no members
 * are declared here, so no consumer can depend on invented planner
 * content before the planner epic defines it.</p>
 */
public interface TypedEvaluatorIr {
}
