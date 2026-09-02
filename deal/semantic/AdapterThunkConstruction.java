package deal.semantic;

import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.ValueId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code REEVALUATE_THUNK} payload-construction seam of the BINDINGS
 * capability (ISSUE-0450 shape-map child, sequencing item 7; design B8,
 * parent D15 "Creation" bullet): the thunk builder owns the
 * detached-block construction — a fresh {@link BlockId} wrapping the
 * source expression's already-lowered ops in source order, plus the
 * generation-pinned {@code capturedBindings} — and consumes the
 * expression-lowering seam for the source ops themselves.
 *
 * <p><b>Seam boundary (explicit).</b> Call-result, member-read,
 * conditional, and conversion source ops are lowered by the
 * values/calls epics' producers; this epic's own tests construct those
 * source ops at the IR level over the pinned {@code deal.semantic-ir/1}
 * schema and hand them to this builder, and source-level end-to-end
 * lowering of those positions completes through the declared consumed
 * seams (named prerequisites of the corresponding corpus positions,
 * never an open-ended deferral). The builder therefore accepts an
 * <em>already-lowered</em> ordered op list — it never re-implements
 * expression-value lowering, never re-orders ops, and never invents an
 * op.</p>
 *
 * <p><b>Closed construction (B8, exactly).</b> Creation evaluates
 * nothing: the thunk block is a fresh {@link BlockId}, the source ops
 * appear only inside the thunk block (zero evaluation at the adapter's
 * creation site), and the {@code capturedBindings} pin the thunk's free
 * bindings with their generations, ordered by first reference.
 * {@link #buildThunk} is the single production path of
 * {@link AdaptSourceRef.Thunk} records in this epic — the walk and the
 * IR-level tests call exactly this surface — and it fails closed on a
 * malformed input (a non-function-typed source value, an empty op list,
 * duplicate op ids, a duplicate or ill-formed capture entry) instead of
 * silently producing a thunk E7's protocol could not execute.</p>
 */
public final class AdapterThunkConstruction {

    private AdapterThunkConstruction() {
        // Static construction surface; no instances.
    }

    /**
     * Builds one {@link AdaptSourceRef.Thunk} over the source
     * expression's already-lowered ops (B8): the fresh {@link BlockId}
     * wraps the ops in the given source order, and the
     * {@code capturedBindings} pin the thunk's free bindings,
     * generation-pinned and ordered by first reference. Zero evaluation
     * occurs at construction — this builder emits nothing and executes
     * nothing.
     *
     * @param blockId          the fresh thunk block identity; non-null
     * @param sourceOps        the source expression's already-lowered
     *                         ops in source order (the expression-
     *                         lowering seam's production); non-empty,
     *                         non-null elements, distinct op ids
     * @param capturedBindings the thunk's free bindings,
     *                         generation-pinned, ordered by first
     *                         reference; non-null elements, no duplicate
     *                         {@code {binding, generation}} entries
     * @return the closed {@code Thunk {BlockId, capturedBindings}}
     *         source reference
     * @throws IllegalArgumentException on a malformed input (producer
     *                                  defect — fail closed, never a
     *                                  silently invalid thunk)
     */
    public static AdaptSourceRef.Thunk buildThunk(BlockId blockId,
                                                  List<SemanticOp> sourceOps,
                                                  List<BindingGeneration> capturedBindings) {
        Objects.requireNonNull(blockId, "blockId must not be null");
        Objects.requireNonNull(sourceOps, "sourceOps must not be null");
        Objects.requireNonNull(capturedBindings, "capturedBindings must not be null");
        if (sourceOps.isEmpty()) {
            throw new IllegalArgumentException("a thunk wraps the source expression's "
                + "already-lowered ops: an empty op list has no produced value "
                + "(producer defect)");
        }
        java.util.Set<OpId> seenOps = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        for (SemanticOp op : sourceOps) {
            Objects.requireNonNull(op, "a thunk source op must not be null");
            if (!seenOps.add(op.opId())) {
                throw new IllegalArgumentException("a thunk source op list carries a "
                    + "duplicate op " + op.opId() + " (producer defect)");
            }
        }
        SemanticOp last = sourceOps.get(sourceOps.size() - 1);
        ValueId produced = producedValueOf(sourceOps);
        if (!(last.resultType() instanceof RuntimeDescriptor.Func)) {
            throw new IllegalArgumentException("the thunk's source expression is "
                + "function-typed: its final producing op " + last.opId() + " carries "
                + "result type " + last.resultType() + " instead of a function "
                + "descriptor (producer defect)");
        }
        if (last.result() == null || !last.result().equals(produced)) {
            throw new IllegalArgumentException("the thunk's final op must produce the "
                + "source expression's value " + produced + "; got result "
                + last.result() + " (producer defect)");
        }
        java.util.Set<BindingGeneration> seenCaptures = new java.util.HashSet<>();
        for (BindingGeneration capture : capturedBindings) {
            Objects.requireNonNull(capture, "a thunk capture entry must not be null");
            if (!seenCaptures.add(capture)) {
                throw new IllegalArgumentException("the thunk's capturedBindings carry a "
                    + "duplicate " + capture + " (producer defect — captures are "
                    + "ordered by first reference with one entry per binding)");
            }
        }
        return new AdaptSourceRef.Thunk(blockId, capturedBindings);
    }

    /**
     * The source expression's produced value: the final producing op's
     * result — the value the thunk re-execution publishes at every
     * invocation (E7's protocol consumes it).
     *
     * @param sourceOps the already-lowered ops in source order;
     *                  non-empty
     * @return the final op's result {@link ValueId}
     * @throws IllegalArgumentException on an empty op list or a final op
     *                                  without a result
     */
    public static ValueId producedValueOf(List<SemanticOp> sourceOps) {
        Objects.requireNonNull(sourceOps, "sourceOps must not be null");
        if (sourceOps.isEmpty()) {
            throw new IllegalArgumentException("an empty thunk op list has no produced "
                + "value (producer defect)");
        }
        SemanticOp last = sourceOps.get(sourceOps.size() - 1);
        if (!(last.result() instanceof ValueId value)) {
            throw new IllegalArgumentException("the thunk's final producing op "
                + last.opId() + " publishes no value result (producer defect)");
        }
        return value;
    }

    /**
     * The block-membership record of the thunk construction (C-D1): every
     * source op is a member of exactly the thunk block. The walk's
     * session machinery records membership at emission; this surface is
     * the IR-level tests' membership record of the same fact (one op per
     * block, insertion order — the source order).
     *
     * @param blockId   the thunk block identity; non-null
     * @param sourceOps the already-lowered ops in source order; non-null
     * @return the ordered per-block op record ({@code {blockId -> [opIds]}})
     */
    public static Map<BlockId, List<OpId>> blockMembershipOf(BlockId blockId,
                                                             List<SemanticOp> sourceOps) {
        Objects.requireNonNull(blockId, "blockId must not be null");
        Objects.requireNonNull(sourceOps, "sourceOps must not be null");
        Map<BlockId, List<OpId>> membership = new LinkedHashMap<>();
        List<OpId> opIds = new java.util.ArrayList<>(sourceOps.size());
        for (SemanticOp op : sourceOps) {
            Objects.requireNonNull(op, "a thunk source op must not be null");
            opIds.add(op.opId());
        }
        membership.put(blockId, List.copyOf(opIds));
        return membership;
    }
}
