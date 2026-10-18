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
        ValueId produced = producedValueOf(sourceOps);
        SemanticOp producing = producingOpOf(sourceOps, produced);
        if (!(producing.resultType() instanceof RuntimeDescriptor.Func)) {
            throw new IllegalArgumentException("the thunk's source expression is "
                + "function-typed: its final producing op " + producing.opId() + " carries "
                + "result type " + producing.resultType() + " instead of a function "
                + "descriptor (producer defect)");
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
     * The source expression's produced value: the last op of the block's own
     * (top-level) op stream that publishes a value. The invocation's owned
     * child cells — the parameter boundaries a call emits after its own op —
     * are members of the same block but belong to their owning op, so they are
     * skipped exactly as the emitted re-executors skip them
     * ({@code thunkResultSlot}); a call-result source with arguments would
     * otherwise resolve a boundary cell that publishes no value.
     *
     */
    public static ValueId producedValueOf(List<SemanticOp> sourceOps) {
        Objects.requireNonNull(sourceOps, "sourceOps must not be null");
        if (sourceOps.isEmpty()) {
            throw new IllegalArgumentException("an empty thunk op list has no produced "
                + "value (producer defect)");
        }
        java.util.Set<OpId> members = new java.util.LinkedHashSet<>();
        for (SemanticOp op : sourceOps) {
            members.add(op.opId());
        }
        for (int i = sourceOps.size() - 1; i >= 0; i--) {
            SemanticOp candidate = sourceOps.get(i);
            OpId parent = candidate.origin() == null
                ? null : candidate.origin().parentOpId();
            if (parent != null && members.contains(parent)) {
                // An owned child cell of an invocation in this block.
                continue;
            }
            if (candidate.result() instanceof ValueId value) {
                return value;
            }
            throw new IllegalArgumentException("the thunk's final producing op "
                + candidate.opId() + " publishes no value result (producer defect)");
        }
        throw new IllegalArgumentException("the thunk op list carries no top-level "
            + "producing op (producer defect)");
    }

    /** The last op of the source op list that publishes the given value. */
    private static SemanticOp producingOpOf(List<SemanticOp> sourceOps, ValueId produced) {
        for (int i = sourceOps.size() - 1; i >= 0; i--) {
            SemanticOp op = sourceOps.get(i);
            if (produced.equals(op.result())) {
                return op;
            }
        }
        throw new IllegalArgumentException("the thunk's produced value " + produced
            + " is published by no op of the source op list (producer defect)");
    }

    /**
     * The block-membership record of the thunk construction (C-D1): every
     * source op is a member of exactly the thunk block. The walk's
     * session machinery records membership at emission; this surface is
     * the IR-level tests' membership record of the same fact (one op per
     * block, insertion order — the source order).
     *
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
