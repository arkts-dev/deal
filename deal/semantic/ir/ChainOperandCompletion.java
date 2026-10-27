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
 * {@code RETURN}/{@code ASYNC_START}/{@code CALLBACK_INVOKE}/
 * {@code EXTERNAL_ENTRY}/{@code AWAIT}/{@code OPTIONAL_READ}/
 * {@code FIELD_READ}/{@code FIELD_WRITE}/{@code FIELD_DELETE}, and the
 * delegated {@code CALL} of {@code ENTRY_INVOKE}), executed exactly once by
 * their owner arm.</li>
 * <li>{@link #operandProducersOf(SemanticOp, LoweredModuleUnit, Set)} —
 * the transitive operand-producing closure of one chain child, in unit
 * list order, excluding structurally-owned ops (those execute under
 * their own owner) and keeping every selected-block producer under the
 * structure op that owns its block.</li>
 * <li>{@link #registerChainOperandOwners(LoweredModuleUnit, Set, Set)} —
 * adds every chain child's operand closure to the consumer's skip set,
 * so the block walk never executes those ops and the chain arm executes
 * them at their consuming child's position.</li>
 * </ul>
 *
 * <p><b>Selected-block producers keep their branch ownership.</b> An
 * {@code &&}/{@code ||} right operand is lowered into its branch's
 * selected block, so its value is published by an op that is a member of
 * that block, not of the enclosing block the chain executes in. The
 * branch op is the member of the enclosing block. Executing the
 * block-internal producer at the child's position would flatten the
 * conditional operand out of its block and run it even when the
 * short-circuit skips it (the skipped operand's exception and effects
 * would execute). The closure therefore substitutes the outermost
 * structure op that owns the producer's block whenever that block is not
 * the child's own block: the structure op (and its block) then execute at
 * the child's position, and the conditional operand executes only when the
 * short-circuit selects it.</p>
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
     * (identified by their recorded {@code parentOpId}) and the delegated
     * {@code CALL} child of {@code ENTRY_INVOKE} (identified by its
     * recorded {@code parentOpId}). The block walk skips them; their
     * owner arm executes each exactly once in payload order (a double
     * execution would duplicate effects and events).
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

                    if (call.dynamicReturnBoundary() != null) {
                        owned.add(call.dynamicReturnBoundary().dealBodyBoundaryOpId());
                        owned.add(call.dynamicReturnBoundary().hostBoundaryOpId());
                        owned.add(call.dynamicReturnBoundary().externalBoundaryOpId());
                    }
                }
                case KindPayload.AsyncStartPayload start -> {
                    owned.addAll(start.parameterBoundaryOpIds());
                    if (start.returnBoundaryOpId() != null) {
                        owned.add(start.returnBoundaryOpId());
                    }
                }
                case KindPayload.CallbackInvokePayload callback -> {
                    owned.addAll(callback.parameterBoundaryOpIds());
                    owned.add(callback.returnBoundaryOpId());
                }
                case KindPayload.ExternalEntryPayload entry ->
                    owned.add(entry.returnBoundaryOpId());
                case KindPayload.AwaitPayload await ->
                    owned.add(await.completionBoundaryOpId());
                case KindPayload.IndexReadPayload read ->
                    owned.add(read.elementBoundaryOpId());
                case KindPayload.ReturnPayload ret ->
                    owned.add(ret.returnBoundaryOpId());
                case KindPayload.StdlibCallPayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                case KindPayload.ExportPublishPayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                case KindPayload.MemberReadPayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                // The OPTIONAL_READ envelope (step 1 of the shared-emission
                // cutover): the read's CONTEXTUAL_TABLE_READ boundary child
                // is parented to the OPTIONAL_READ op, whose arm executes it
                // exactly once — the block walk skips it like every other
                // parented boundary child.
                case KindPayload.OptionalReadPayload ignored ->
                    addBoundaryChildren(unit, op, owned);

                case KindPayload.FieldReadPayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                case KindPayload.FieldWritePayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                case KindPayload.FieldDeletePayload ignored ->
                    addBoundaryChildren(unit, op, owned);
                case KindPayload.EntryInvokePayload ignored ->
                    addEntryCallChildren(unit, op, owned);
                // The CLASS_NEW field boundaries (step 8 of the
                // shared-emission cutover): the construction's
                // declaration-order {@code CLASS_LITERAL_FIELD} boundary children are
                // payload-owned — the CLASS_NEW arm executes each
                // exactly once in payload order; the block walk skips
                // them like every other payload-referenced boundary
                // child (a double execution would duplicate events).
                case KindPayload.ClassNewPayload classNew -> {
                    for (KindPayload.FieldBoundary boundary
                            : classNew.fieldBoundaries()) {
                        owned.add(boundary.boundaryOpId());
                    }
                }
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
     * The delegated {@code CALL(DIRECT main)} of an {@code ENTRY_INVOKE}:
     * the exactly one {@code CALL} op parented to the entry op executes
     * once under the entry arm, never at its flat block-list position.
     */
    private static void addEntryCallChildren(LoweredModuleUnit unit, SemanticOp entry,
                                             Set<OpId> owned) {
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.kind() == SemanticOpKind.CALL
                    && entry.opId().equals(candidate.origin().parentOpId())) {
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
     * <p>A producer that is a member of a block other than the child's own
     * block is replaced by the outermost structure op that owns that block
     * (the {@code BRANCH} of a short-circuit right operand), so the
     * conditional operand executes with its block at the child's position
     * instead of being flattened out of the block the short-circuit guards.
     * The structure op's own condition value (the branch payload's
     * condition, which is not an operand) joins the closure too.</p>
     */
    public static List<SemanticOp> operandProducersOf(SemanticOp child,
                                                      LoweredModuleUnit unit,
                                                      Set<OpId> structuralOwned) {
        Map<ValueId, SemanticOp> producers = new HashMap<>();
        for (SemanticOp op : unit.ops()) {
            // Identity-preserving loads and enclosing ASSIGNs may publish
            // the same ValueId later. They cannot produce an earlier
            // child's operands: selecting one would move a future binding
            // read before its initialization (or re-enter the owning chain).
            if (op.opId().equals(child.opId())) break;
            if (op.result() instanceof ValueId valueId) {
                producers.put(valueId, op);
            }
        }
        Map<OpId, BlockId> opBlocks = opBlocksOf(unit);
        Map<BlockId, SemanticOp> blockOwners = blockOwnersOf(unit);
        BlockId childBlock = opBlocks.get(child.opId());
        List<SemanticOp> closure = new ArrayList<>();
        Set<ValueId> done = new HashSet<>();
        Deque<ValueId> work = new ArrayDeque<>();
        // An awaited RHS is the chain's value child: the ASYNC_START that
        // publishes the awaited token belongs to the RHS position (the
        // RHS operand completes after the receiver/key children), so the
        // start and its own operand closure join this child's closure and
        // the flat block walk never executes the start early (A-D2
        // receiver → key → RHS). The start is not an operand of the
        // AWAIT (its published token is not a value); the token identity
        // links the two ops.
        SemanticOp start = publishingStartOf(child, unit);
        if (start != null && !structuralOwned.contains(start.opId())) {
            closure.add(start);
            for (ValueId operand : start.operands()) {
                if (done.add(operand)) {
                    work.add(operand);
                }
            }
        }
        for (ValueId operand : operandsIncludingCondition(child)) {
            if (done.add(operand)) {
                work.add(operand);
            }
        }
        while (!work.isEmpty()) {
            ValueId value = work.poll();
            SemanticOp producer = producers.get(value);
            if (producer == null) {
                continue;
            }
            SemanticOp execution = executedAt(producer, childBlock, opBlocks,
                blockOwners);
            if (structuralOwned.contains(execution.opId())) {
                continue;
            }
            closure.add(execution);
            for (ValueId operand : operandsIncludingCondition(execution)) {
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
     * The values one closure op consumes: its operands plus, for a
     * structure op, the branch condition the payload carries (a
     * {@code BRANCH}'s condition is a payload field, never an operand, so
     * the condition's producers must still complete at the structure op's
     * position).
     */
    private static List<ValueId> operandsIncludingCondition(SemanticOp op) {
        if (op.payload() instanceof KindPayload.BranchPayload branch) {
            List<ValueId> values = new ArrayList<>(op.operands());
            values.add(branch.condition());
            return values;
        }
        return op.operands();
    }

    /**
     * The op that executes one discovered producer at the child's own
     * position: the producer itself when it is a member of the child's
     * block, otherwise the outermost structure op that owns the producing
     * block (see the class comment). A producer whose block chain reaches
     * no structure op inside the child's block keeps itself — no
     * structure owns it, so nothing can be re-parented.
     */
    private static SemanticOp executedAt(SemanticOp producer, BlockId childBlock,
                                        Map<OpId, BlockId> opBlocks,
                                        Map<BlockId, SemanticOp> blockOwners) {
        SemanticOp current = producer;
        for (int steps = 0; steps <= blockOwners.size(); steps++) {
            BlockId block = opBlocks.get(current.opId());
            if (block == null || childBlock == null || block.equals(childBlock)) {
                return current;
            }
            SemanticOp owner = blockOwners.get(block);
            if (owner == null) {
                return producer;
            }
            current = owner;
        }
        return producer;
    }

    /** The block membership of every op in one unit's own table. */
    private static Map<OpId, BlockId> opBlocksOf(LoweredModuleUnit unit) {
        Map<OpId, BlockId> opBlocks = new HashMap<>();
        if (unit.bodyTable() == null || unit.bodyTable().opBlocks() == null) {
            return opBlocks;
        }
        opBlocks.putAll(unit.bodyTable().opBlocks());
        return opBlocks;
    }

    /**
     * The structure op that owns each child block: {@code BRANCH}'s
     * selected/alternate blocks, {@code LOOP}'s init/body/update blocks,
     * {@code FOR_EACH}'s body and {@code TRY_CATCH}'s try/catch blocks.
     */
    private static Map<BlockId, SemanticOp> blockOwnersOf(LoweredModuleUnit unit) {
        Map<BlockId, SemanticOp> owners = new HashMap<>();
        for (SemanticOp op : unit.ops()) {
            switch (op.payload()) {
                case KindPayload.BranchPayload branch -> {
                    owners.put(branch.selectedBlock(), op);
                    if (branch.alternateBlock() != null) {
                        owners.put(branch.alternateBlock(), op);
                    }
                }
                case KindPayload.LoopPayload loop -> {
                    if (loop.initBlock() != null) {
                        owners.put(loop.initBlock(), op);
                    }
                    owners.put(loop.bodyBlock(), op);
                    if (loop.updateBlock() != null) {
                        owners.put(loop.updateBlock(), op);
                    }
                }
                case KindPayload.ForEachPayload forEach -> owners.put(forEach.body(), op);
                case KindPayload.TryCatchPayload tryCatch -> {
                    owners.put(tryCatch.tryBlock(), op);
                    owners.put(tryCatch.catchBlock(), op);
                }
                default -> {
                }
            }
        }
        return owners;
    }

    /**
     * The {@code ASYNC_START} op that publishes one awaited child's token,
     * or {@code null} for every non-await child (and for an await whose
     * token no start in this unit publishes — a cross-unit external
     * async link, which carries no caller-side start op).
     */
    private static SemanticOp publishingStartOf(SemanticOp child,
                                                LoweredModuleUnit unit) {
        if (!(child.payload() instanceof KindPayload.AwaitPayload await)) {
            return null;
        }
        AsyncTokenId.Canonical canonical = canonicalToken(await.token());
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.ASYNC_START
                    && op.result() instanceof AsyncTokenId.Canonical token
                    && token.tokenId() == canonical.tokenId()) {
                return op;
            }
        }
        return null;
    }

    /** The canonical token one token identity resolves to (alias chains). */
    private static AsyncTokenId.Canonical canonicalToken(AsyncTokenId token) {
        AsyncTokenId current = token;
        while (current instanceof AsyncTokenId.Alias alias) {
            current = alias.referent();
        }
        return (AsyncTokenId.Canonical) current;
    }

    /**
     * Registers every {@code ASSIGN}/{@code DELETE} chain child's operand
     * closure in the consumer's skip set: those ops execute at their
     * consuming child's position inside the chain (never at their flat
     * block-list position), completing the A-D2 operand-completion rule.
     *
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
