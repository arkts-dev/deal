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
 * The address-chain operand-completion rule (A-D2: "each child's
 * operands complete before that child's START").
 *
 * <p>A chain ({@code ASSIGN}/{@code DELETE}) executes its children in
 * payload order. The ops that produce a child's operands are ordinary
 * unit ops, and the lowerer emits them in the enclosing block at their
 * source positions — but the block walk would execute them at their flat
 * list positions, i.e. before the chain op itself, collapsing the pinned
 * interleaving. The hoisted-operand parity pins
 * ({@code jvm-arr-eval-order-hoisted},
 * {@code jvm-arr-eval-order-write-hoisted-parity}: an operand's nested
 * side-effecting argument completes before the operand call's own
 * effect) require the receiver/key/RHS operand effects to run at their
 * consuming child's position: the index operand's effects after the
 * receiver child, the RHS operand's effects after the key child.
 *
 * <p>This helper is the single authority the three consumers (the
 * semantic oracle, the shared LuaJIT emitter, the shared JVM emitter)
 * use so their execution orders stay identical:
 *
 * <ul>
 * <li>{@link #structuralOwners(LoweredModuleUnit)} — the payload-owned
 * children (chain children, boundary children of {@code ARRAY_NEW}/
 * {@code CALL}/{@code STDLIB_CALL}/{@code MEMBER_READ}/{@code INDEX_READ}/
 * {@code RETURN}), executed exactly once by their owner arm.</li>
 * <li>{@link #operandProducersOf(SemanticOp, LoweredModuleUnit, Set)} —
 * the transitive operand-producing closure of one chain child, in unit
 * list order, excluding structurally-owned ops (those execute under
 * their own owner).</li>
 * <li>{@link #registerChainOperandOwners(LoweredModuleUnit, Set, Set)} —
 * adds every chain child's operand closure to the consumer's skip set,
 * so the block walk never executes those ops and the chain arm executes
 * them at their consuming child's position.</li>
 * </ul>
 *
 * <p>Trace-visible: the closure ops record the chain op as their
 * {@code parentOpId} (the lowerer pushes the chain parent while
 * lowering the whole chain), so their events nest under the chain
 * exactly like the children's.</p>
 */
public final class ChainOperandCompletion {

    private ChainOperandCompletion() {
    }

    /**
     * The payload-owned children of every structure op: ops referenced
     * by an owner payload's child/boundary id lists, plus the
     * {@code BOUNDARY} children of {@code STDLIB_CALL}/{@code MEMBER_READ}
     * (identified by their recorded {@code parentOpId}). The block walk
     * skips them; their owner arm executes each exactly once in payload
     * order (a double execution would duplicate effects and events).
     */
    public static Set<OpId> structuralOwners(LoweredModuleUnit unit) {
        Set<OpId> owned = new HashSet<>();
        for (SemanticOp op : unit.ops()) {
            switch (op.payload()) {
                case KindPayload.AssignPayload assign ->
                    owned.addAll(assign.childOps());
                case KindPayload.DeletePayload delete ->
                    owned.addAll(delete.childOps());
                case KindPayload.ArrayNewPayload array ->
                    owned.addAll(array.elementBoundaryOpIds());
                case KindPayload.CallPayload call -> {
                    owned.addAll(call.parameterBoundaryOpIds());
                    if (call.returnBoundaryOpId() != null) {
                        owned.add(call.returnBoundaryOpId());
                    }
                }
                case KindPayload.IndexReadPayload read ->
                    owned.add(read.elementBoundaryOpId());
                case KindPayload.ReturnPayload ret ->
                    owned.add(ret.returnBoundaryOpId());
                case KindPayload.StdlibCallPayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                case KindPayload.MemberReadPayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                default -> {
                }
            }
        }
        return owned;
    }

    private static void addBoundaryChildren(LoweredModuleUnit unit, SemanticOp owner,
                                            Set<OpId> owned) {
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.kind() == SemanticOpKind.BOUNDARY
                    && owner.opId().equals(candidate.origin().parentOpId())) {
                owned.add(candidate.opId());
            }
        }
    }

    /**
     * The transitive operand-producing closure of one chain child, in
     * unit list order: every op whose result feeds the child's operands,
     * plus the producers of those ops' own operands, recursively.
     * Structurally-owned ops are excluded — they execute exactly once
     * under their own owner arm (e.g. a nested chain child, a boundary
     * child) and must never be re-executed here.
     *
     * @param child           the chain child whose operands must complete
     *                        before its START
     * @param unit            the validated lowered unit
     * @param structuralOwned the payload-owned children (see
     *                        {@link #structuralOwners(LoweredModuleUnit)})
     * @return the closure ops in unit list order (each appears at most once)
     */
    public static List<SemanticOp> operandProducersOf(SemanticOp child,
                                                      LoweredModuleUnit unit,
                                                      Set<OpId> structuralOwned) {
        Map<ValueId, SemanticOp> producers = new HashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.result() instanceof ValueId valueId) {
                producers.put(valueId, op);
            }
        }
        List<SemanticOp> closure = new ArrayList<>();
        Set<ValueId> done = new HashSet<>();
        Deque<ValueId> work = new ArrayDeque<>();
        for (ValueId operand : child.operands()) {
            if (done.add(operand)) {
                work.add(operand);
            }
        }
        while (!work.isEmpty()) {
            ValueId value = work.poll();
            SemanticOp producer = producers.get(value);
            if (producer == null || structuralOwned.contains(producer.opId())) {
                continue;
            }
            closure.add(producer);
            for (ValueId operand : producer.operands()) {
                if (done.add(operand)) {
                    work.add(operand);
                }
            }
        }
        // Unit list order: the lowerer emits operand producers before
        // their consumers (single-assignment order), so this sort keeps
        // each producer ahead of its own operand consumers.
        Map<OpId, Integer> index = new HashMap<>();
        List<SemanticOp> ops = unit.ops();
        for (int i = 0; i < ops.size(); i++) {
            index.putIfAbsent(ops.get(i).opId(), i);
        }
        closure.sort((a, b) -> Integer.compare(
            index.getOrDefault(a.opId(), Integer.MAX_VALUE),
            index.getOrDefault(b.opId(), Integer.MAX_VALUE)));
        return closure;
    }

    /**
     * Registers every {@code ASSIGN}/{@code DELETE} chain child's operand
     * closure in the consumer's skip set: those ops execute at their
     * consuming child's position inside the chain (never at their flat
     * block-list position), completing the A-D2 operand-completion rule.
     *
     * @param unit            the validated lowered unit
     * @param structuralOwned the payload-owned children (see
     *                        {@link #structuralOwners(LoweredModuleUnit)});
     *                        closures are computed against this set so a
     *                        nested chain child is never pulled into an
     *                        outer closure
     * @param skipSet         the set the block walk consults; the closure
     *                        ops are added here
     */
    public static void registerChainOperandOwners(LoweredModuleUnit unit,
                                                  Set<OpId> structuralOwned,
                                                  Set<OpId> skipSet) {
        for (SemanticOp op : unit.ops()) {
            List<OpId> childOps = switch (op.payload()) {
                case KindPayload.AssignPayload assign -> assign.childOps();
                case KindPayload.DeletePayload delete -> delete.childOps();
                default -> List.of();
            };
            for (OpId childId : childOps) {
                SemanticOp child = byId(unit, childId);
                if (child == null) {
                    continue;
                }
                for (SemanticOp producer : operandProducersOf(child, unit, structuralOwned)) {
                    skipSet.add(producer.opId());
                }
            }
        }
    }

    private static SemanticOp byId(LoweredModuleUnit unit, OpId id) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(id)) {
                return op;
            }
        }
        return null;
    }
}
