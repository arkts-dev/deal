package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ControlFlowValidator {

    /** The block-tree/membership rule (C-D2 a). */
    public static final String CONTROL_BLOCK_TREE = "CONTROL_BLOCK_TREE";

    /** The exit rule (C-D2 c). */
    public static final String CONTROL_EXIT = "CONTROL_EXIT";

    /**
     * The five module-level kinds that are not ops of a lowered function
     * and may therefore be absent from the block-membership table (the
     * C-D1/C-D2 completeness exemption).
     */
    private static final Set<SemanticOpKind> MODULE_LEVEL_KINDS = Set.of(
        SemanticOpKind.MODULE_INIT,
        SemanticOpKind.EXTERNAL_ENTRY,
        SemanticOpKind.CLASS_FACTORY,
        SemanticOpKind.CALLBACK_INVOKE,
        SemanticOpKind.ENTRY_INVOKE);
    private ControlFlowValidator() {
        // Static surface; no instances.
    }

    /**
     * Validates one unit plus its block-membership table against the
     * pinned block-tree and exit checks. Returns empty on
     * pass and exactly one E6005 diagnostic naming the first failing rule
     * on failure. No mutation; deterministic; linear in ops plus block
     * edges.
     *
     */
    public static Optional<CompilerDiagnostic> validate(LoweredModuleUnit unit,
                                                        StructuredBodyTable table) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Context context = Context.build(unit, table);
        Optional<CompilerDiagnostic> failure = checkBlockTree(context);
        if (failure.isPresent()) {
            return failure;
        }
        return checkExits(context);
    }

    // =========================================================================
    // E6005 construction (registry-owned; no hand-crafted message)
    // =========================================================================

    private static Optional<CompilerDiagnostic> fail(Context ctx, String rule, String what) {
        LoweringFailureDetail detail = new LoweringFailureDetail(
            ctx.unit.moduleId().path(),
            SemanticCapability.EVALUATION_ORDER,
            rule,
            ctx.unit.semanticProfile(),
            LoweredModuleUnit.FORMAT_VERSION,
            "ControlFlowValidator " + rule + " (" + what + ")");
        return Optional.of(FailureContractRegistry.e6005(detail));
    }

    // =========================================================================
    // Context: the deterministic per-pass view of unit + table
    // =========================================================================

    private static final class Context {

        final LoweredModuleUnit unit;
        final StructuredBodyTable table;
        final Map<OpId, SemanticOp> unitOps;
        final Set<BlockId> roots;
        final Map<OpId, List<BlockId>> controlPositions;
        final Map<OpId, List<BlockId>> foreignPositions;
        final Set<BlockId> foreignReferenced;
        final Map<BlockId, SemanticOp> referencing;
        final Map<BlockId, Integer> referenceCount;
        final Map<BlockId, List<SemanticOp>> enclosingCache;
        final Map<BlockId, BlockId> terminalCache;
        final Set<OpId> callOwnedReturns;

        Context(LoweredModuleUnit unit, StructuredBodyTable table, Map<OpId, SemanticOp> unitOps,
                Set<BlockId> roots, Map<OpId, List<BlockId>> controlPositions,
                Map<OpId, List<BlockId>> foreignPositions, Set<BlockId> foreignReferenced,
                Map<BlockId, SemanticOp> referencing, Map<BlockId, Integer> referenceCount,
                Map<BlockId, List<SemanticOp>> enclosingCache,
                Map<BlockId, BlockId> terminalCache, Set<OpId> callOwnedReturns) {
            this.unit = unit;
            this.table = table;
            this.unitOps = unitOps;
            this.roots = roots;
            this.controlPositions = controlPositions;
            this.foreignPositions = foreignPositions;
            this.foreignReferenced = foreignReferenced;
            this.referencing = referencing;
            this.referenceCount = referenceCount;
            this.enclosingCache = enclosingCache;
            this.terminalCache = terminalCache;
            this.callOwnedReturns = callOwnedReturns;
        }

        static Context build(LoweredModuleUnit unit, StructuredBodyTable table) {
            Map<OpId, SemanticOp> unitOps = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                unitOps.put(op.opId(), op);
            }
            Set<BlockId> roots = new LinkedHashSet<>();
            for (LoweredFunction function : unit.functions().values()) {
                roots.add(function.body());
            }
            roots.add(unit.moduleInit().initBlock());

            Map<OpId, List<BlockId>> controlPositions = new LinkedHashMap<>();
            Map<OpId, List<BlockId>> foreignPositions = new LinkedHashMap<>();
            Set<BlockId> foreignReferenced = new LinkedHashSet<>();
            for (SemanticOp op : unit.ops()) {
                controlPositions.put(op.opId(), List.copyOf(controlPositionsOf(op)));
                List<BlockId> foreign = foreignPositionsOf(op);
                foreignPositions.put(op.opId(), List.copyOf(foreign));
                foreignReferenced.addAll(foreign);
            }
            for (FunctionExecutionBinding binding : unit.functionBindings().values()) {
                if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                    foreignReferenced.add(body.blockId());
                }
            }
            Set<OpId> callOwnedReturns = callOwnedReturnRecords(unitOps);
            return new Context(unit, table, unitOps, roots, controlPositions, foreignPositions,
                foreignReferenced, new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), callOwnedReturns);
        }

        /**
         * The structure ops enclosing a block in the control tree, from
         * the innermost (the op directly referencing the block) to the
         * outermost, memoized per block (linear across all queries).
         * A root or foreign-owned block has an empty list.
         */
        List<SemanticOp> enclosingOps(BlockId block) {
            List<SemanticOp> cached = enclosingCache.get(block);
            if (cached != null) {
                return cached;
            }
            List<BlockId> path = new ArrayList<>();
            BlockId current = block;
            while (!enclosingCache.containsKey(current)) {
                SemanticOp ref = referencing.get(current);
                if (ref == null) {
                    break;
                }
                path.add(current);
                BlockId parent = table.opBlocks().get(ref.opId());
                if (parent == null) {
                    break; // defensive; unreachable after the block-tree checks
                }
                current = parent;
            }
            List<SemanticOp> acc = enclosingCache.get(current);
            if (acc == null) {
                acc = List.of();
            }
            List<SemanticOp> result = acc;
            for (int i = path.size() - 1; i >= 0; i--) {
                BlockId onPath = path.get(i);
                List<SemanticOp> accCopy = new ArrayList<>(acc);
                accCopy.add(referencing.get(onPath));
                acc = accCopy;
                enclosingCache.put(onPath, List.copyOf(acc));
                if (onPath.equals(block)) {
                    result = acc;
                }
            }
            if (path.isEmpty()) {
                enclosingCache.put(block, List.copyOf(result));
            }
            return List.copyOf(result);
        }

        /**
         * The terminal block of a block's parent chain — the root the
         * block belongs to (a function body or the module init block), or
         * the block itself when the chain terminates in a foreign-owned
         * block. Memoized per block.
         */
        BlockId terminalOf(BlockId block) {
            BlockId cached = terminalCache.get(block);
            if (cached != null) {
                return cached;
            }
            List<BlockId> path = new ArrayList<>();
            BlockId current = block;
            while (terminalCache.get(current) == null) {
                SemanticOp ref = referencing.get(current);
                if (ref == null) {
                    break;
                }
                BlockId parent = table.opBlocks().get(ref.opId());
                if (parent == null) {
                    break; // defensive; unreachable after the block-tree checks
                }
                path.add(current);
                current = parent;
            }
            BlockId terminal = terminalCache.get(current);
            if (terminal == null) {
                terminal = current;
            }
            terminalCache.put(current, terminal);
            for (BlockId onPath : path) {
                terminalCache.put(onPath, terminal);
            }
            terminalCache.put(block, terminal);
            return terminal;
        }
    }

    private static Set<OpId> callOwnedReturnRecords(Map<OpId, SemanticOp> unitOps) {
        Set<OpId> records = new LinkedHashSet<>();
        for (SemanticOp op : unitOps.values()) {
            switch (op.payload()) {
                case KindPayload.CallPayload call -> {
                    if (call.callee() instanceof KindPayload.CallCallee.Dynamic
                            && call.dynamicReturnBoundary() != null) {
                        collectCallOwnedReturn(records, unitOps, op.opId(),
                            call.dynamicReturnBoundary().dealBodyBoundaryOpId());
                    }
                }
                case KindPayload.AsyncStartPayload start -> {
                    if (start.callee() instanceof KindPayload.CallCallee.Dynamic) {
                        collectCallOwnedReturn(records, unitOps, op.opId(),
                            start.returnBoundaryOpId());
                    }
                }
                default -> {
                    // Not a dynamic invocation shape.
                }
            }
        }
        return records;
    }

    /** Adds one dynamic invocation's recorded cell's parent RETURN record, when it matches. */
    private static void collectCallOwnedReturn(Set<OpId> records,
                                               Map<OpId, SemanticOp> unitOps,
                                               OpId invocationOpId, OpId cellOpId) {
        SemanticOp cell = cellOpId == null ? null : unitOps.get(cellOpId);
        if (cell == null || cell.origin() == null || cell.origin().parentOpId() == null) {
            return;
        }
        SemanticOp parent = unitOps.get(cell.origin().parentOpId());
        if (parent == null || parent.kind() != SemanticOpKind.RETURN
                || !(parent.payload() instanceof KindPayload.ReturnPayload returned)
                || !invocationOpId.equals(returned.enclosingInvocationOpId())) {
            return;
        }
        records.add(parent.opId());
    }

    /** The control-tree child positions of a structure op (payload order, nulls skipped). */
    private static List<BlockId> controlPositionsOf(SemanticOp op) {
        List<BlockId> blocks = new ArrayList<>(2);
        switch (op.kind()) {
            case BRANCH -> {
                KindPayload.BranchPayload payload = (KindPayload.BranchPayload) op.payload();
                blocks.add(payload.selectedBlock());
                if (payload.alternateBlock() != null) {
                    blocks.add(payload.alternateBlock());
                }
            }
            case LOOP -> {
                KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
                if (payload.initBlock() != null) {
                    blocks.add(payload.initBlock());
                }
                blocks.add(payload.bodyBlock());
                if (payload.updateBlock() != null) {
                    blocks.add(payload.updateBlock());
                }
            }
            case FOR_EACH -> blocks.add(((KindPayload.ForEachPayload) op.payload()).body());
            case TRY_CATCH -> {
                KindPayload.TryCatchPayload payload = (KindPayload.TryCatchPayload) op.payload();
                blocks.add(payload.tryBlock());
                blocks.add(payload.catchBlock());
            }
            default -> {
                // No control-tree child positions.
            }
        }
        return blocks;
    }

    private static List<BlockId> foreignPositionsOf(SemanticOp op) {
        List<BlockId> blocks = new ArrayList<>(1);
        switch (op.kind()) {
            case BINDING_ALLOC ->
                blocks.add(((KindPayload.BindingAllocPayload) op.payload()).scope());
            case CALL -> {
                BlockId body = ((KindPayload.CallPayload) op.payload()).bodyBlock();
                if (body != null) {
                    blocks.add(body);
                }
            }
            case CLASS_DEFAULT ->
                blocks.add(((KindPayload.ClassDefaultPayload) op.payload()).defaultBlock());
            case FUNCTION_ADAPT -> {
                AdaptSourceRef source = ((KindPayload.FunctionAdaptPayload) op.payload()).source();
                if (source instanceof AdaptSourceRef.Thunk thunk) {
                    blocks.add(thunk.blockId());
                }
            }
            case MODULE_INIT ->
                blocks.add(((KindPayload.ModuleInitPayload) op.payload()).initBlock());
            default -> {
                // No foreign block positions.
            }
        }
        return blocks;
    }

    // =========================================================================
    // Block tree (C-D2 a): membership + tree shape
    // =========================================================================

    private static Optional<CompilerDiagnostic> checkBlockTree(Context ctx) {
        Optional<CompilerDiagnostic> failure;

        // Membership: every op listed in the table is a produced op of the unit.
        for (Map.Entry<BlockId, List<OpId>> entry : ctx.table.blockOps().entrySet()) {
            for (OpId op : entry.getValue()) {
                if (!ctx.unitOps.containsKey(op)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "op " + op + " listed in block "
                        + entry.getKey() + " is not a produced op of the unit");
                }
            }
        }
        for (OpId op : ctx.table.opBlocks().keySet()) {
            if (!ctx.unitOps.containsKey(op)) {
                return fail(ctx, CONTROL_BLOCK_TREE, "op " + op
                    + " in the inverse membership map is not a produced op of the unit");
            }
        }

        // Single membership: every op appears in at most one block.
        Map<OpId, BlockId> seen = new LinkedHashMap<>();
        for (Map.Entry<BlockId, List<OpId>> entry : ctx.table.blockOps().entrySet()) {
            for (OpId op : entry.getValue()) {
                BlockId first = seen.get(op);
                if (first != null) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "op " + op
                        + " is a member of more than one block (or listed twice in one block): "
                        + first + " and " + entry.getKey());
                }
                seen.put(op, entry.getKey());
            }
        }

        // Inverse consistency: the inverse map is exactly the inverse of the block lists.
        for (Map.Entry<BlockId, List<OpId>> entry : ctx.table.blockOps().entrySet()) {
            for (OpId op : entry.getValue()) {
                BlockId inverse = ctx.table.opBlocks().get(op);
                if (inverse == null) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "op " + op + " is listed in block "
                        + entry.getKey() + " but has no inverse membership entry");
                }
                if (!inverse.equals(entry.getKey())) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "inverse-map conflict: op " + op
                        + " is listed in block " + entry.getKey()
                        + " but its inverse entry maps it to block " + inverse);
                }
            }
        }
        for (Map.Entry<OpId, BlockId> entry : ctx.table.opBlocks().entrySet()) {
            if (!seen.containsKey(entry.getKey())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "inverse-map conflict: op " + entry.getKey()
                    + " maps to block " + entry.getValue() + " but is listed in no block");
            }
        }

        // Root existence: unit-record blocks are blocks of the table.
        for (LoweredFunction function : ctx.unit.functions().values()) {
            if (!ctx.table.blockOps().containsKey(function.body())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "the body block " + function.body()
                    + " of function " + function.functionId() + " is not a block of the table");
            }
        }
        if (!ctx.table.blockOps().containsKey(ctx.unit.moduleInit().initBlock())) {
            return fail(ctx, CONTROL_BLOCK_TREE, "the module init block "
                + ctx.unit.moduleInit().initBlock() + " is not a block of the table");
        }
        for (FunctionExecutionBinding binding : ctx.unit.functionBindings().values()) {
            if (binding instanceof FunctionExecutionBinding.LoweredBody body
                    && !ctx.table.blockOps().containsKey(body.blockId())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "the body block " + body.blockId()
                    + " of binding " + body.functionId() + " is not a block of the table");
            }
        }

        // Payload-block existence: every payload-referenced BlockId exists.
        for (SemanticOp op : ctx.unit.ops()) {
            for (BlockId block : ctx.controlPositions.get(op.opId())) {
                if (!ctx.table.blockOps().containsKey(block)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "payload of op " + op.opId()
                        + " references block " + block + " which is not a block of the table");
                }
            }
            for (BlockId block : ctx.foreignPositions.get(op.opId())) {
                if (!ctx.table.blockOps().containsKey(block)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "payload of op " + op.opId()
                        + " references block " + block + " which is not a block of the table");
                }
            }
        }

        // Tree shape: structure-op membership, root references, double references.
        for (SemanticOp op : ctx.unit.ops()) {
            for (BlockId child : ctx.controlPositions.get(op.opId())) {
                if (!ctx.table.opBlocks().containsKey(op.opId())) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "control structure op " + op.opId()
                        + " referencing child block " + child + " is not a member of any block");
                }
                if (ctx.roots.contains(child)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "root block " + child
                        + " is referenced by a control payload position of op " + op.opId());
                }
                Integer count = ctx.referenceCount.get(child);
                if (count != null) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "block " + child
                        + " is referenced by more than one control payload position (op "
                        + ctx.referencing.get(child).opId() + " and op " + op.opId() + ")");
                }
                ctx.referenceCount.put(child, 1);
                ctx.referencing.put(child, op);
            }
        }

        for (SemanticOp op : ctx.unit.ops()) {
            if (!MODULE_LEVEL_KINDS.contains(op.kind())
                    && !ctx.callOwnedReturns.contains(op.opId())
                    && !ctx.table.opBlocks().containsKey(op.opId())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "op " + op.opId()
                    + " is a member of no block (every op of a lowered function must"
                    + " belong to exactly one block; only the module-level kinds"
                    + " MODULE_INIT|EXTERNAL_ENTRY|CLASS_FACTORY|CALLBACK_INVOKE|ENTRY_INVOKE"
                    + " may be absent from the table)");
            }
        }

        // Orphan blocks: not a root and referenced by no payload position of any kind.
        for (BlockId block : ctx.table.blockOps().keySet()) {
            if (!ctx.roots.contains(block) && !ctx.referenceCount.containsKey(block)
                    && !ctx.foreignReferenced.contains(block)) {
                return fail(ctx, CONTROL_BLOCK_TREE, "orphan block " + block
                    + ": not a root block and referenced by no payload position");
            }
        }

        // Acyclicity: every parent chain terminates without revisiting a block.
        Map<BlockId, Integer> status = new LinkedHashMap<>();
        for (BlockId start : ctx.referenceCount.keySet()) {
            if (status.getOrDefault(start, 0) != 0) {
                continue;
            }
            List<BlockId> path = new ArrayList<>();
            BlockId current = start;
            while (status.getOrDefault(current, 0) == 0) {
                SemanticOp ref = ctx.referencing.get(current);
                if (ref == null) {
                    // A terminal block (a root or a foreign-owned leaf): no edge.
                    status.put(current, 2);
                    break;
                }
                status.put(current, 1);
                path.add(current);
                current = ctx.table.opBlocks().get(ref.opId()); // non-null after the tree checks
            }
            if (status.getOrDefault(current, 0) == 1) {
                return fail(ctx, CONTROL_BLOCK_TREE, "cyclic block nesting: block " + current
                    + " is an ancestor of itself through control payload references");
            }
            for (BlockId block : path) {
                status.put(block, 2);
            }
        }

        return Optional.empty();
    }

    // =========================================================================
    // Exits (C-D2 c): loop targets and RETURN function containment
    // =========================================================================

    private static Optional<CompilerDiagnostic> checkExits(Context ctx) {
        for (SemanticOp op : ctx.unit.ops()) {
            if (op.kind() == SemanticOpKind.BREAK || op.kind() == SemanticOpKind.CONTINUE) {
                OpId loopId = op.kind() == SemanticOpKind.BREAK
                    ? ((KindPayload.BreakPayload) op.payload()).loopId()
                    : ((KindPayload.ContinuePayload) op.payload()).loopId();
                Optional<CompilerDiagnostic> failure = checkLoopTarget(ctx, op, loopId);
                if (failure.isPresent()) {
                    return failure;
                }
            } else if (op.kind() == SemanticOpKind.RETURN) {
                if (ctx.callOwnedReturns.contains(op.opId())
                        && !ctx.table.opBlocks().containsKey(op.opId())) {
                    // The invocation-owned record: not an op of a lowered
                    // function, so it has no containing function to check.
                    continue;
                }
                FunctionId function = ((KindPayload.ReturnPayload) op.payload()).function();
                Optional<CompilerDiagnostic> failure = checkReturnTarget(ctx, op, function);
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CompilerDiagnostic> checkLoopTarget(Context ctx, SemanticOp op,
                                                                OpId loopId) {
        SemanticOp target = ctx.unitOps.get(loopId);
        if (target == null || (target.kind() != SemanticOpKind.LOOP
                && target.kind() != SemanticOpKind.FOR_EACH)) {
            return fail(ctx, CONTROL_EXIT, "transfer op " + op.opId() + " targets " + loopId
                + ", which is not a LOOP or FOR_EACH op of the unit");
        }
        BlockId block = ctx.table.opBlocks().get(op.opId());
        boolean enclosed = false;
        if (block != null) {
            for (SemanticOp structure : ctx.enclosingOps(block)) {
                if (structure.opId().equals(loopId)) {
                    enclosed = true;
                    break;
                }
            }
        }
        if (!enclosed) {
            return fail(ctx, CONTROL_EXIT, "transfer op " + op.opId() + " targets loop " + loopId
                + ", which does not enclose its block"
                + (block == null ? " (the op is a member of no block)" : " " + block));
        }
        return Optional.empty();
    }

    private static Optional<CompilerDiagnostic> checkReturnTarget(Context ctx, SemanticOp op,
                                                                  FunctionId function) {
        LoweredFunction lowered = ctx.unit.functions().get(function);
        if (lowered == null) {
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId() + " names function " + function
                + ", which is not a function of the unit");
        }
        BlockId block = ctx.table.opBlocks().get(op.opId());
        if (block == null) {
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId()
                + " is a member of no block and therefore has no containing function");
        }
        BlockId terminal = ctx.terminalOf(block);
        if (!terminal.equals(lowered.body())) {
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId() + " names function " + function
                + " (body " + lowered.body() + ") but its block " + block
                + " belongs to the block tree rooted at " + terminal);
        }
        return Optional.empty();
    }
}
