package deal.module;

import deal.semantic.ir.CanonicalJson;

/**
 * One child of the serializer-owned canonical grammar (ISSUE-0542,
 * design source {@code provider-versioned-default-plans} D6/D9): an
 * expression child is a {@link CanonicalNode}; a statement child is a
 * {@link CanonicalStatement}.
 *
 * <p>Statement children occur exactly under statement nodes and under
 * {@code FUNCTION_EXPRESSION} nodes — a function-expression body nested
 * in a default serializes through the statement grammar (D6). Every
 * other child of a node is a {@link CanonicalNode} in evaluation
 * order; block children of statements preserve source order and
 * expression children preserve evaluation order (D9).</p>
 */
public sealed interface CanonicalChild
    permits CanonicalNode, CanonicalStatement {

    /**
     * The child's canonical JSON value — deterministic and ordered
     * (the single canonical JSON facility,
     * {@link deal.semantic.ir.CanonicalJson}).
     *
     * @return the canonical value, never null
     */
    CanonicalJson.Value canonicalJson();
}
