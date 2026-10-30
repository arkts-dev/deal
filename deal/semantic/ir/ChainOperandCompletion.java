package deal.semantic.ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completes ASSIGN/DELETE child operands at their consuming child's position,
 * preserving receiver → key → RHS effects and selected-block ownership.
 * Consumers share one index per immutable unit, including during repeated
 * Oracle execution; the index contains IR facts, never runtime values.
 */
public final class ChainOperandCompletion {
    private ChainOperandCompletion() {}

    /** Builds the unit-local execution plan shared by skip registration and execution. */
    public static Index index(LoweredModuleUnit unit) {
        return new Index(unit, structuralOwners(unit));
    }

    /** Payload-owned ops execute under their owner, never at their flat position. */
    public static Set<OpId> structuralOwners(LoweredModuleUnit unit) {
        Set<OpId> owned = new HashSet<>();
        Map<OpId, List<SemanticOp>> children = new HashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.origin().parentOpId() != null) {
                children.computeIfAbsent(op.origin().parentOpId(), ignored -> new ArrayList<>())
                    .add(op);
            }
        }
        for (SemanticOp op : unit.ops()) {
            switch (op.payload()) {
                case KindPayload.AssignPayload assign -> owned.addAll(assign.childOps());
                case KindPayload.DeletePayload delete -> owned.addAll(delete.childOps());
                case KindPayload.ArrayNewPayload array -> owned.addAll(array.elementBoundaryOpIds());
                case KindPayload.CallPayload call -> {
                    owned.addAll(call.parameterBoundaryOpIds());
                    if (call.returnBoundaryOpId() != null) owned.add(call.returnBoundaryOpId());
                    if (call.dynamicReturnBoundary() != null) {
                        owned.add(call.dynamicReturnBoundary().dealBodyBoundaryOpId());
                        owned.add(call.dynamicReturnBoundary().hostBoundaryOpId());
                        owned.add(call.dynamicReturnBoundary().externalBoundaryOpId());
                    }
                }
                case KindPayload.AsyncStartPayload start -> {
                    owned.addAll(start.parameterBoundaryOpIds());
                    if (start.returnBoundaryOpId() != null) owned.add(start.returnBoundaryOpId());
                }
                case KindPayload.CallbackInvokePayload callback -> {
                    owned.addAll(callback.parameterBoundaryOpIds());
                    owned.add(callback.returnBoundaryOpId());
                }
                case KindPayload.ExternalEntryPayload entry -> owned.add(entry.returnBoundaryOpId());
                case KindPayload.AwaitPayload await -> owned.add(await.completionBoundaryOpId());
                case KindPayload.IndexReadPayload read -> owned.add(read.elementBoundaryOpId());
                case KindPayload.ReturnPayload ret -> owned.add(ret.returnBoundaryOpId());
                case KindPayload.StdlibCallPayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.ExportPublishPayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.MemberReadPayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.OptionalReadPayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.FieldReadPayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.FieldWritePayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.FieldDeletePayload ignored -> addChildren(children, op, owned, SemanticOpKind.BOUNDARY);
                case KindPayload.EntryInvokePayload ignored -> addChildren(children, op, owned, SemanticOpKind.CALL);
                case KindPayload.ClassNewPayload classNew -> {
                    for (KindPayload.FieldBoundary boundary : classNew.fieldBoundaries()) {
                        owned.add(boundary.boundaryOpId());
                    }
                }
                default -> {}
            }
        }
        return owned;
    }

    private static void addChildren(Map<OpId, List<SemanticOp>> children,
                                    SemanticOp owner, Set<OpId> owned, SemanticOpKind kind) {
        for (SemanticOp child : children.getOrDefault(owner.opId(), List.of())) {
            if (child.kind() == kind) owned.add(child.opId());
        }
    }

    /** Convenience surface for isolated queries; repeated consumers retain an {@link Index}. */
    public static List<SemanticOp> operandProducersOf(SemanticOp child,
            LoweredModuleUnit unit, Set<OpId> structuralOwned) {
        return new Index(unit, structuralOwned).operandProducersOf(child);
    }

    /** Convenience surface for isolated skip-set construction. */
    public static void registerChainOperandOwners(LoweredModuleUnit unit,
            Set<OpId> structuralOwned, Set<OpId> skipSet) {
        new Index(unit, structuralOwned).registerChainOperandOwners(skipSet);
    }

    /**
     * Unit-local producer histories, membership, ordering and cached operand closures.
     * Histories preserve the last producer strictly before the child: an enclosing
     * assignment or later identity-preserving load cannot replace an earlier source.
     */
    public static final class Index {
        private final LoweredModuleUnit unit;
        private final Set<OpId> structuralOwned;
        private final Map<OpId, SemanticOp> opsById = new HashMap<>();
        private final Map<OpId, Integer> order = new HashMap<>();
        private final Map<ValueId, List<SemanticOp>> producers = new HashMap<>();
        private final Map<OpId, BlockId> opBlocks;
        private final Map<BlockId, SemanticOp> blockOwners = new HashMap<>();
        private final Map<Long, SemanticOp> starts = new HashMap<>();
        private final Map<OpId, List<SemanticOp>> closures = new HashMap<>();

        private Index(LoweredModuleUnit unit, Set<OpId> structuralOwned) {
            this.unit = unit;
            this.structuralOwned = Set.copyOf(structuralOwned);
            opBlocks = unit.bodyTable() == null || unit.bodyTable().opBlocks() == null
                ? Map.of() : unit.bodyTable().opBlocks();
            List<SemanticOp> ops = unit.ops();
            for (int i = 0; i < ops.size(); i++) {
                SemanticOp op = ops.get(i);
                opsById.putIfAbsent(op.opId(), op);
                order.putIfAbsent(op.opId(), i);
                if (op.result() instanceof ValueId value) {
                    producers.computeIfAbsent(value, ignored -> new ArrayList<>()).add(op);
                }
                if (op.kind() == SemanticOpKind.ASYNC_START
                        && op.result() instanceof AsyncTokenId.Canonical token) {
                    starts.putIfAbsent(token.tokenId(), op);
                }
                switch (op.payload()) {
                    case KindPayload.BranchPayload branch -> {
                        blockOwners.put(branch.selectedBlock(), op);
                        if (branch.alternateBlock() != null) blockOwners.put(branch.alternateBlock(), op);
                    }
                    case KindPayload.LoopPayload loop -> {
                        if (loop.initBlock() != null) blockOwners.put(loop.initBlock(), op);
                        blockOwners.put(loop.bodyBlock(), op);
                        if (loop.updateBlock() != null) blockOwners.put(loop.updateBlock(), op);
                    }
                    case KindPayload.ForEachPayload forEach -> blockOwners.put(forEach.body(), op);
                    case KindPayload.TryCatchPayload tryCatch -> {
                        blockOwners.put(tryCatch.tryBlock(), op);
                        blockOwners.put(tryCatch.catchBlock(), op);
                    }
                    default -> {}
                }
            }
        }

        public Set<OpId> structuralOwners() {
            return structuralOwned;
        }

        public void registerChainOperandOwners(Set<OpId> skipSet) {
            for (SemanticOp op : unit.ops()) {
                List<OpId> children = switch (op.payload()) {
                    case KindPayload.AssignPayload assign -> assign.childOps();
                    case KindPayload.DeletePayload delete -> delete.childOps();
                    default -> List.of();
                };
                for (OpId id : children) {
                    SemanticOp child = opsById.get(id);
                    if (child != null) {
                        for (SemanticOp producer : operandProducersOf(child)) {
                            skipSet.add(producer.opId());
                        }
                    }
                }
            }
        }

        /** Operand closure in unit order, retaining selected-block execution. */
        public List<SemanticOp> operandProducersOf(SemanticOp child) {
            return closures.computeIfAbsent(child.opId(), ignored -> computeClosure(child));
        }

        private SemanticOp precedingProducer(ValueId value, int before) {
            List<SemanticOp> history = producers.get(value);
            if (history == null) return null;
            int low = 0, high = history.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (order.get(history.get(mid).opId()) < before) low = mid + 1;
                else high = mid;
            }
            return low == 0 ? null : history.get(low - 1);
        }

        private List<SemanticOp> computeClosure(SemanticOp child) {
            int before = order.getOrDefault(child.opId(), Integer.MAX_VALUE);
            BlockId childBlock = opBlocks.get(child.opId());
            List<SemanticOp> closure = new ArrayList<>();
            Set<ValueId> done = new HashSet<>();
            Deque<ValueId> work = new ArrayDeque<>();
            // AWAIT's token is not an operand. Its publishing start and arguments
            // nevertheless complete at the RHS position, after receiver and key.
            SemanticOp start = child.payload() instanceof KindPayload.AwaitPayload await
                ? starts.get(canonicalToken(await.token()).tokenId()) : null;
            if (start != null && !structuralOwned.contains(start.opId())) {
                closure.add(start);
                for (ValueId operand : start.operands()) {
                    if (done.add(operand)) work.add(operand);
                }
            }
            for (ValueId operand : operandsIncludingCondition(child)) {
                if (done.add(operand)) work.add(operand);
            }
            while (!work.isEmpty()) {
                SemanticOp producer = precedingProducer(work.poll(), before);
                if (producer == null) continue;
                SemanticOp execution = executedAt(producer, childBlock);
                if (structuralOwned.contains(execution.opId())) continue;
                closure.add(execution);
                for (ValueId operand : operandsIncludingCondition(execution)) {
                    if (done.add(operand)) work.add(operand);
                }
            }
            closure.sort((a, b) -> Integer.compare(order.get(a.opId()), order.get(b.opId())));
            return List.copyOf(closure);
        }

        /** Keep guarded producers under the outermost structure in the child's block. */
        private SemanticOp executedAt(SemanticOp producer, BlockId childBlock) {
            SemanticOp current = producer;
            for (int steps = 0; steps <= blockOwners.size(); steps++) {
                BlockId block = opBlocks.get(current.opId());
                if (block == null || childBlock == null || block.equals(childBlock)) return current;
                SemanticOp owner = blockOwners.get(block);
                if (owner == null) return producer;
                current = owner;
            }
            return producer;
        }
    }

    /** Branch conditions are payload values, not ordinary operands. */
    private static List<ValueId> operandsIncludingCondition(SemanticOp op) {
        if (op.payload() instanceof KindPayload.BranchPayload branch) {
            List<ValueId> values = new ArrayList<>(op.operands());
            values.add(branch.condition());
            return values;
        }
        return op.operands();
    }

    private static AsyncTokenId.Canonical canonicalToken(AsyncTokenId token) {
        AsyncTokenId current = token;
        while (current instanceof AsyncTokenId.Alias alias) current = alias.referent();
        return (AsyncTokenId.Canonical) current;
    }
}
