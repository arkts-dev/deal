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
        SemanticOp producing = sourceOps.get(sourceOps.size() - 1);
        for (int i = sourceOps.size() - 1; i >= 0; i--) {
            SemanticOp candidate = sourceOps.get(i);
            if (produced.equals(candidate.result())) {
                producing = candidate;
                break;
            }
        }
        if (!(producing.resultType() instanceof RuntimeDescriptor.Func)) {
            throw new IllegalArgumentException("the thunk's source expression is "
                + "function-typed: its final producing op " + producing.opId()
                + " carries result type " + producing.resultType()
                + " instead of a function descriptor (producer defect)");
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
     * The source expression's produced value: the result of the final
     * <em>producing</em> op — the value the thunk re-execution publishes
     * at every invocation (E7's protocol consumes it). A source
     * expression's lowering may end with non-producing companion ops
     * (for example a call's trailing return/boundary child, which the
     * invoking arm executes inline and which publishes no value); those
     * trailing companions never select the produced value.
     *
     */
    public static ValueId producedValueOf(List<SemanticOp> sourceOps) {
        Objects.requireNonNull(sourceOps, "sourceOps must not be null");
        if (sourceOps.isEmpty()) {
            throw new IllegalArgumentException("an empty thunk op list has no produced "
                + "value (producer defect)");
        }
        for (int i = sourceOps.size() - 1; i >= 0; i--) {
            SemanticOp op = sourceOps.get(i);
            if (op == null) {
                throw new IllegalArgumentException("a thunk source op must not be null");
            }
            if (op.result() instanceof ValueId value) {
                return value;
            }
        }
        throw new IllegalArgumentException("the thunk's source expression publishes no "
            + "value result (producer defect)");
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
