package deal.semantic.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The block-membership production record of {@code deal.semantic-ir/1}
 * (control-flow-structures decision C-D1; ISSUE-0408): one table per
 * lowered unit recording, for each block, its ordered ops and, inversely,
 * the block each op belongs to.
 *
 * <pre>{@code
 * StructuredBodyTable {
 *   blockOps: Map<BlockId, List<OpId>>,   // ordered ops per block
 *   opBlocks: Map<OpId, BlockId>          // inverse membership
 * }
 * }</pre>
 *
 * <p><b>Pinned invariants (C-D1).</b> Every op of a lowered function
 * belongs to exactly one block; the function's root block is its
 * {@link LoweredFunction#body()}; every {@link BlockId} referenced by any
 * payload exists in the table. Those invariants relate the table to a
 * {@link LoweredModuleUnit} and are production-time validator checks
 * (control-flow-structures C-D2 — {@code ControlFlowValidator} in
 * {@code deal.semantic}), not construction checks: this record is the
 * inert membership carrier the lowerer produces with the unit, so the
 * maps are copied defensively and cross-map consistency (an op listed in
 * two blocks, a conflicting inverse entry, an unreferenced block) is
 * admitted here and rejected by the validator.</p>
 *
 * <p><b>Ownership.</b> This is construct-epic production data — no
 * schema-owned record changes and the foundation validator's closed
 * 14-condition rule set is untouched. The emission carrier that hands
 * the unit plus this table to the shared emitters is ISSUE-0239's seam;
 * the record and its contract are this epic's. Block <em>execution</em>
 * is realized by each consumer (oracle, shared emitters) from the
 * validated payload plus table; this record defines no execution.</p>
 *
 * <p>Immutability: both maps are defensively copied into insertion-ordered
 * unmodifiable maps and every per-block op list is copied, so later
 * mutation of the constructor arguments cannot change the record. Map
 * iteration order (the producer's block declaration order) is the
 * validator's deterministic traversal order.</p>
 *
 * @param blockOps the ordered ops per block; non-null (keys, values, and
 *                 listed ops must be non-null)
 * @param opBlocks the inverse block membership; non-null (keys and values
 *                 must be non-null)
 */
public record StructuredBodyTable(
    Map<BlockId, List<OpId>> blockOps,
    Map<OpId, BlockId> opBlocks
) {

    public StructuredBodyTable {
        Objects.requireNonNull(blockOps, "blockOps must not be null");
        Objects.requireNonNull(opBlocks, "opBlocks must not be null");
        Map<BlockId, List<OpId>> orderedOps = new LinkedHashMap<>();
        for (Map.Entry<BlockId, List<OpId>> entry : blockOps.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "blockOps keys must not be null");
            orderedOps.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        blockOps = Collections.unmodifiableMap(orderedOps);
        Map<OpId, BlockId> inverse = new LinkedHashMap<>();
        for (Map.Entry<OpId, BlockId> entry : opBlocks.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "opBlocks keys must not be null");
            Objects.requireNonNull(entry.getValue(), "opBlocks values must not be null");
            inverse.put(entry.getKey(), entry.getValue());
        }
        opBlocks = Collections.unmodifiableMap(inverse);
    }
}
