package deal.module;

import deal.semantic.ir.CanonicalJson;

public sealed interface CanonicalChild
    permits CanonicalNode, CanonicalStatement {

    /**
     * The child's canonical JSON value — deterministic and ordered
     * (the single canonical JSON facility,
     * {@link deal.semantic.ir.CanonicalJson}).
     *
     */
    CanonicalJson.Value canonicalJson();
}
