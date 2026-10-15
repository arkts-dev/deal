package deal.semantic;

import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringFailureDetail;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RawBinding;
import deal.semantic.ir.RawOp;
import deal.semantic.ir.RawUnit;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The block-tree and exit authority of {@code deal.semantic-ir/2}
 * (C-D1/C-D2): one semantic rule set over the complete unit facts, shared
 * by the typed record surface and the raw surface.
 *
 * <p>The owner consumes {@link #validate(LoweredModuleUnit,
 * StructuredBodyTable)} (the typed production path) and {@link
 * #validate(RawUnit)} (the complete raw facts of the common admission, so
 * the text surface applies exactly the same predicates to the same
 * content). Both entries build one neutral fact view — op order, payload
 * block positions, roots, function bodies, lowered-body bindings, and the
 * dynamic-invocation cell records — and run the same checks with the same
 * messages, so a membership mutation cannot disappear in a typed/raw
 * projection.</p>
 *
 * <p>The checks are unchanged: the exact-inverse membership of
 * {@code bodyTable.blockOps}/{@code opBlocks}, the produced-op and unique
 * ordered membership rules, the function/init/control/default root
 * existence rules, the tree shape and acyclicity rules, the control
 * ownership/exits rules, and the two exemptions the pinned contract keeps
 * — the five module-level kinds ({@code MODULE_INIT|EXTERNAL_ENTRY|
 * CLASS_FACTORY|CALLBACK_INVOKE|ENTRY_INVOKE}) and the invocation-owned
 * RETURN records of a dynamic call's recorded DEAL-body cell.</p>
 */
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

    // =========================================================================
    // Public surface
    // =========================================================================

    /**
     * Validates one typed unit plus its block-membership table against the
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
        return validate(Facts.ofTyped(unit, table));
    }

    /**
     * Validates one complete raw unit — the shared fact model of the typed
     * and text admission surfaces — against exactly the same block-tree and
     * exit checks as the typed entry. The raw view extracts the payload
     * block positions, roots, function bodies, lowered-body bindings and
     * dynamic-invocation cell records from the transported content; a
     * mutation a typed projection could hide reaches the same rule with the
     * same message and range here.
     *
     * <p>The composed admission runs this owner after the closed rule
     * engine, so a unit whose carried profile is outside the closed set has
     * already failed R-ENUM/R-PROFILE: this owner then reports no control
     * failure rather than naming a profile it cannot truthfully derive.</p>
     *
     */
    public static Optional<CompilerDiagnostic> validate(RawUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        SemanticProfile profile = profileByName(unit.semanticProfile());
        if (profile == null) {
            return Optional.empty();
        }
        return validate(Facts.ofRaw(unit, profile));
    }

    private static Optional<CompilerDiagnostic> validate(Facts facts) {
        Optional<CompilerDiagnostic> failure = checkBlockTree(facts);
        if (failure.isPresent()) {
            return failure;
        }
        return checkExits(facts);
    }

    // =========================================================================
    // E6005 construction (registry-owned; no hand-crafted message)
    // =========================================================================

    private static Optional<CompilerDiagnostic> fail(Facts ctx, String rule, String what) {
        return fail(ctx, rule, what, null);
    }

    /**
     * One failing rule naming an offending op: the E6005 diagnostic carries
     * that op's own source origin as its range (never a guessed anchor); a
     * rule with no offending op keeps the canonical synthetic range.
     */
    private static Optional<CompilerDiagnostic> fail(Facts ctx, String rule, String what,
                                                     OpView op) {
        LoweringFailureDetail detail = new LoweringFailureDetail(
            ctx.modulePath,
            SemanticCapability.EVALUATION_ORDER,
            rule,
            ctx.profile,
            LoweredModuleUnit.FORMAT_VERSION,
            "ControlFlowValidator " + rule + " (" + what + ")");
        return Optional.of(op == null
            ? FailureContractRegistry.e6005(detail)
            : FailureContractRegistry.e6005(detail, op.range()));
    }

    // =========================================================================
    // The neutral fact view: typed and raw units feed these same fields
    // =========================================================================

    /**
     * One op's membership-relevant facts: the semantic link the tree
     * consumes ({@code parentOpId}), the payload block positions, the
     * retained origin range, and the exit facts (loop target, RETURN
     * function and enclosing invocation) as closed/raw positions.
     */
    private record OpView(
        OpId opId,
        SemanticOpKind kind,
        OpId parentOpId,
        List<BlockId> controlPositions,
        List<BlockId> foreignPositions,
        DiagnosticRange range,
        OpId loopId,
        FunctionId returnFunction,
        OpId enclosingInvocationOpId,
        OpId dynamicCellOpId
    ) {

        OpView {
            Objects.requireNonNull(opId, "opId must not be null");
            Objects.requireNonNull(controlPositions, "controlPositions must not be null");
            Objects.requireNonNull(foreignPositions, "foreignPositions must not be null");
            Objects.requireNonNull(range, "range must not be null");
        }
    }

    /** One lowered-body binding's function identity and body block. */
    private record BodyRef(FunctionId functionId, BlockId blockId) {

        BodyRef {
            Objects.requireNonNull(functionId, "functionId must not be null");
            Objects.requireNonNull(blockId, "blockId must not be null");
        }
    }

    private static final class Facts {

        final String modulePath;
        final SemanticProfile profile;
        final StructuredBodyTable table;
        final List<OpView> ops;
        final Map<OpId, OpView> unitOps;
        final Set<BlockId> roots;
        final BlockId initBlock;
        final Map<FunctionId, BlockId> functionBodies;
        final List<BodyRef> loweredBodies;
        final Set<BlockId> foreignReferenced;
        final Map<BlockId, OpView> referencing = new LinkedHashMap<>();
        final Map<BlockId, Integer> referenceCount = new LinkedHashMap<>();
        final Map<BlockId, List<OpView>> enclosingCache = new LinkedHashMap<>();
        final Map<BlockId, BlockId> terminalCache = new LinkedHashMap<>();
        final Set<OpId> callOwnedReturns;

        Facts(String modulePath, SemanticProfile profile, StructuredBodyTable table,
              List<OpView> ops, Set<BlockId> roots, BlockId initBlock,
              Map<FunctionId, BlockId> functionBodies,
              List<BodyRef> loweredBodies, Set<BlockId> foreignReferenced) {
            this.modulePath = modulePath;
            this.profile = profile;
            this.table = table;
            this.ops = List.copyOf(ops);
            this.roots = Set.copyOf(roots);
            this.initBlock = initBlock;
            this.functionBodies = Map.copyOf(functionBodies);
            this.loweredBodies = List.copyOf(loweredBodies);
            Map<OpId, OpView> byId = new LinkedHashMap<>();
            for (OpView op : this.ops) {
                byId.put(op.opId(), op);
            }
            this.unitOps = byId;
            Set<BlockId> referenced = new LinkedHashSet<>(foreignReferenced);
            for (OpView op : this.ops) {
                referenced.addAll(op.foreignPositions());
            }
            this.foreignReferenced = referenced;
            this.callOwnedReturns = callOwnedReturnRecords(this.unitOps);
        }

        static Facts ofTyped(LoweredModuleUnit unit, StructuredBodyTable table) {
            List<OpView> ops = new ArrayList<>();
            for (SemanticOp op : unit.ops()) {
                ops.add(typedView(op));
            }
            Set<BlockId> roots = new LinkedHashSet<>();
            Map<FunctionId, BlockId> functionBodies = new LinkedHashMap<>();
            for (Map.Entry<FunctionId, LoweredFunction> entry : unit.functions().entrySet()) {
                LoweredFunction function = entry.getValue();
                roots.add(function.body());
                functionBodies.put(entry.getKey(), function.body());
            }
            roots.add(unit.moduleInit().initBlock());
            List<BodyRef> loweredBodies = new ArrayList<>();
            Set<BlockId> foreignReferenced = new LinkedHashSet<>();
            for (FunctionExecutionBinding binding : unit.functionBindings().values()) {
                if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                    loweredBodies.add(new BodyRef(body.functionId(), body.blockId()));
                    foreignReferenced.add(body.blockId());
                }
            }
            return new Facts(unit.moduleId().path(), unit.semanticProfile(), table, ops,
                roots, unit.moduleInit().initBlock(), functionBodies, loweredBodies,
                foreignReferenced);
        }

        static Facts ofRaw(RawUnit unit, SemanticProfile profile) {
            List<OpView> ops = new ArrayList<>();
            for (RawOp op : unit.ops()) {
                ops.add(rawView(op));
            }
            Set<BlockId> roots = new LinkedHashSet<>();
            Map<FunctionId, BlockId> functionBodies = new LinkedHashMap<>();
            for (RawUnit.RawFunction function : unit.functions()) {
                roots.add(function.body());
                functionBodies.put(function.key(), function.body());
            }
            roots.add(unit.moduleInit().initBlock());
            List<BodyRef> loweredBodies = new ArrayList<>();
            Set<BlockId> foreignReferenced = new LinkedHashSet<>();
            for (RawBinding binding : unit.bindings()) {
                if ("loweredBody".equals(binding.shape()) && binding.functionId() != null
                        && binding.blockId() != null) {
                    loweredBodies.add(new BodyRef(binding.functionId(), binding.blockId()));
                    foreignReferenced.add(binding.blockId());
                }
            }
            return new Facts(unit.modulePath(), profile, unit.bodyTable(), ops, roots,
                unit.moduleInit().initBlock(), functionBodies, loweredBodies, foreignReferenced);
        }

        /** The typed op's membership-relevant facts (the existing producers' records). */
        private static OpView typedView(SemanticOp op) {
            OpId loopId = null;
            FunctionId returnFunction = null;
            OpId enclosingInvocationOpId = null;
            switch (op.payload()) {
                case KindPayload.BreakPayload payload -> loopId = payload.loopId();
                case KindPayload.ContinuePayload payload -> loopId = payload.loopId();
                case KindPayload.ReturnPayload payload -> {
                    returnFunction = payload.function();
                    enclosingInvocationOpId = payload.enclosingInvocationOpId();
                }
                default -> {
                    // No exit positions.
                }
            }
            return new OpView(op.opId(), op.kind(), op.origin().parentOpId(),
                controlPositionsOf(op), foreignPositionsOf(op), op.origin().span().range(),
                loopId, returnFunction, enclosingInvocationOpId, dynamicCellOf(op));
        }

        /** The dynamic invocation's recorded DEAL-body cell, or {@code null}. */
        private static OpId dynamicCellOf(SemanticOp op) {
            if (op.payload() instanceof KindPayload.CallPayload call
                    && call.callee() instanceof KindPayload.CallCallee.Dynamic
                    && call.dynamicReturnBoundary() != null) {
                return call.dynamicReturnBoundary().dealBodyBoundaryOpId();
            }
            if (op.payload() instanceof KindPayload.AsyncStartPayload start
                    && start.callee() instanceof KindPayload.CallCallee.Dynamic
                    && start.returnBoundaryOpId() != null) {
                return start.returnBoundaryOpId();
            }
            return null;
        }

        /** The raw op's membership-relevant facts (the transported closed payload mapping). */
        private static OpView rawView(RawOp op) {
            SemanticOpKind kind = enumByName(SemanticOpKind.class, op.kind());
            CanonicalJson.Obj payload = op.payload();
            List<BlockId> control = new ArrayList<>(2);
            List<BlockId> foreign = new ArrayList<>(1);
            OpId loopId = null;
            FunctionId returnFunction = null;
            OpId enclosingInvocationOpId = null;
            if (kind == SemanticOpKind.BRANCH) {
                addOptional(control, payload, "selectedBlock");
                addOptional(control, payload, "alternateBlock");
            } else if (kind == SemanticOpKind.LOOP) {
                addOptional(control, payload, "initBlock");
                addOptional(control, payload, "bodyBlock");
                addOptional(control, payload, "updateBlock");
            } else if (kind == SemanticOpKind.FOR_EACH) {
                addOptional(control, payload, "body");
            } else if (kind == SemanticOpKind.TRY_CATCH) {
                addOptional(control, payload, "tryBlock");
                addOptional(control, payload, "catchBlock");
            } else if (kind == SemanticOpKind.BINDING_ALLOC) {
                addOptional(foreign, payload, "scope");
            } else if (kind == SemanticOpKind.CALL) {
                addOptional(foreign, payload, "bodyBlock");
            } else if (kind == SemanticOpKind.CLASS_DEFAULT) {
                addOptional(foreign, payload, "defaultBlock");
            } else if (kind == SemanticOpKind.FUNCTION_ADAPT) {
                CanonicalJson.Value source = ContractSnapshotCanonicalizer
                    .payloadValue(payload, "source");
                if (source instanceof CanonicalJson.Obj sourceObj
                        && "thunk".equals(ContractSnapshotCanonicalizer
                            .optionalString(sourceObj, "type"))) {
                    addOptional(foreign, sourceObj, "blockId");
                }
            } else if (kind == SemanticOpKind.MODULE_INIT) {
                addOptional(foreign, payload, "initBlock");
            } else if (kind == SemanticOpKind.BREAK || kind == SemanticOpKind.CONTINUE) {
                loopId = ContractSnapshotCanonicalizer.parseOptionalOpId(payload, "loopId");
            } else if (kind == SemanticOpKind.RETURN) {
                returnFunction = parseFunctionId(payload, "function");
                enclosingInvocationOpId = ContractSnapshotCanonicalizer
                    .parseOptionalOpId(payload, "enclosingInvocationOpId");
            }
            return new OpView(op.opId(), kind, op.parentOpId(), control, foreign,
                op.origin().diagnosticRange(), loopId, returnFunction,
                enclosingInvocationOpId, dynamicCellOf(op));
        }

        /** The dynamic invocation's recorded DEAL-body cell, or {@code null}. */
        private static OpId dynamicCellOf(RawOp op) {
            SemanticOpKind kind = enumByName(SemanticOpKind.class, op.kind());
            CanonicalJson.Obj payload = op.payload();
            if (kind == SemanticOpKind.CALL && isDynamicCallee(payload)) {
                CanonicalJson.Value boundary = ContractSnapshotCanonicalizer
                    .payloadValue(payload, "dynamicReturnBoundary");
                if (boundary instanceof CanonicalJson.Obj record) {
                    return ContractSnapshotCanonicalizer.parseOptionalOpId(record,
                        "dealBodyBoundaryOpId");
                }
                return null;
            }
            if (kind == SemanticOpKind.ASYNC_START && isDynamicCallee(payload)) {
                return ContractSnapshotCanonicalizer.parseOptionalOpId(payload,
                    "returnBoundaryOpId");
            }
            return null;
        }

        private static boolean isDynamicCallee(CanonicalJson.Obj payload) {
            CanonicalJson.Value callee = ContractSnapshotCanonicalizer
                .payloadValue(payload, "callee");
            return callee instanceof CanonicalJson.Obj calleeObj
                && "dynamic".equals(ContractSnapshotCanonicalizer
                    .optionalString(calleeObj, "type"));
        }

        /** Parses one optional block position ({@code null} when absent or malformed). */
        private static void addOptional(List<BlockId> blocks, CanonicalJson.Obj payload,
                                        String key) {
            BlockId block = parseOptionalBlockId(payload, key);
            if (block != null) {
                blocks.add(block);
            }
        }

        private static BlockId parseOptionalBlockId(CanonicalJson.Obj payload, String key) {
            CanonicalJson.Value value = ContractSnapshotCanonicalizer.payloadValue(payload, key);
            if (!(value instanceof CanonicalJson.Obj idObj)) {
                return null;
            }
            try {
                return ContractSnapshotCanonicalizer.parseBlockId(idObj);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static FunctionId parseFunctionId(CanonicalJson.Obj payload, String key) {
            CanonicalJson.Value value = ContractSnapshotCanonicalizer
                .payloadValue(payload, key);
            if (!(value instanceof CanonicalJson.Obj idObj)) {
                return null;
            }
            try {
                return ContractSnapshotCanonicalizer.parseFunctionId(idObj);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /**
         * The structure ops enclosing a block in the control tree, from
         * the innermost (the op directly referencing the block) to the
         * outermost, memoized per block (linear across all queries).
         * A root or foreign-owned block has an empty list.
         */
        List<OpView> enclosingOps(BlockId block) {
            List<OpView> cached = enclosingCache.get(block);
            if (cached != null) {
                return cached;
            }
            List<BlockId> path = new ArrayList<>();
            BlockId current = block;
            while (!enclosingCache.containsKey(current)) {
                OpView ref = referencing.get(current);
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
            List<OpView> acc = enclosingCache.get(current);
            if (acc == null) {
                acc = List.of();
            }
            List<OpView> result = acc;
            for (int i = path.size() - 1; i >= 0; i--) {
                BlockId onPath = path.get(i);
                List<OpView> accCopy = new ArrayList<>(acc);
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
                OpView ref = referencing.get(current);
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

    private static Set<OpId> callOwnedReturnRecords(Map<OpId, OpView> unitOps) {
        Set<OpId> records = new LinkedHashSet<>();
        for (OpView op : unitOps.values()) {
            if (op.dynamicCellOpId() == null) {
                continue;
            }
            collectCallOwnedReturn(records, unitOps, op.opId(), op.dynamicCellOpId());
        }
        return records;
    }

    /** Adds one dynamic invocation's recorded cell's parent RETURN record, when it matches. */
    private static void collectCallOwnedReturn(Set<OpId> records,
                                               Map<OpId, OpView> unitOps,
                                               OpId invocationOpId, OpId cellOpId) {
        OpView cell = cellOpId == null ? null : unitOps.get(cellOpId);
        if (cell == null || cell.parentOpId() == null) {
            return;
        }
        OpView parent = unitOps.get(cell.parentOpId());
        if (parent == null || parent.kind() != SemanticOpKind.RETURN
                || !invocationOpId.equals(parent.enclosingInvocationOpId())) {
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

    private static Optional<CompilerDiagnostic> checkBlockTree(Facts ctx) {
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
                        + first + " and " + entry.getKey(), ctx.unitOps.get(op));
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
                        + entry.getKey() + " but has no inverse membership entry",
                        ctx.unitOps.get(op));
                }
                if (!inverse.equals(entry.getKey())) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "inverse-map conflict: op " + op
                        + " is listed in block " + entry.getKey()
                        + " but its inverse entry maps it to block " + inverse,
                        ctx.unitOps.get(op));
                }
            }
        }
        for (Map.Entry<OpId, BlockId> entry : ctx.table.opBlocks().entrySet()) {
            if (!seen.containsKey(entry.getKey())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "inverse-map conflict: op " + entry.getKey()
                    + " maps to block " + entry.getValue() + " but is listed in no block",
                    ctx.unitOps.get(entry.getKey()));
            }
        }

        // Root existence: unit-record blocks are blocks of the table.
        for (Map.Entry<FunctionId, BlockId> entry : ctx.functionBodies.entrySet()) {
            if (!ctx.table.blockOps().containsKey(entry.getValue())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "the body block " + entry.getValue()
                    + " of function " + entry.getKey() + " is not a block of the table");
            }
        }
        if (!ctx.table.blockOps().containsKey(ctx.initBlock)) {
            return fail(ctx, CONTROL_BLOCK_TREE, "the module init block "
                + ctx.initBlock + " is not a block of the table");
        }
        for (BodyRef body : ctx.loweredBodies) {
            if (!ctx.table.blockOps().containsKey(body.blockId())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "the body block " + body.blockId()
                    + " of binding " + body.functionId() + " is not a block of the table");
            }
        }

        // Payload-block existence: every payload-referenced BlockId exists.
        for (OpView op : ctx.ops) {
            for (BlockId block : op.controlPositions()) {
                if (!ctx.table.blockOps().containsKey(block)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "payload of op " + op.opId()
                        + " references block " + block + " which is not a block of the table",
                        op);
                }
            }
            for (BlockId block : op.foreignPositions()) {
                if (!ctx.table.blockOps().containsKey(block)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "payload of op " + op.opId()
                        + " references block " + block + " which is not a block of the table",
                        op);
                }
            }
        }

        // Tree shape: structure-op membership, root references, double references.
        for (OpView op : ctx.ops) {
            for (BlockId child : op.controlPositions()) {
                if (!ctx.table.opBlocks().containsKey(op.opId())) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "control structure op " + op.opId()
                        + " referencing child block " + child + " is not a member of any block",
                        op);
                }
                if (ctx.roots.contains(child)) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "root block " + child
                        + " is referenced by a control payload position of op " + op.opId(),
                        op);
                }
                Integer count = ctx.referenceCount.get(child);
                if (count != null) {
                    return fail(ctx, CONTROL_BLOCK_TREE, "block " + child
                        + " is referenced by more than one control payload position (op "
                        + ctx.referencing.get(child).opId() + " and op " + op.opId() + ")",
                        op);
                }
                ctx.referenceCount.put(child, 1);
                ctx.referencing.put(child, op);
            }
        }

        for (OpView op : ctx.ops) {
            if (op.kind() == null) {
                // R-ENUM owns an out-of-set op kind; the control owner reports
                // no membership failure for a kind it cannot classify.
                continue;
            }
            if (!MODULE_LEVEL_KINDS.contains(op.kind())
                    && !ctx.callOwnedReturns.contains(op.opId())
                    && !ctx.table.opBlocks().containsKey(op.opId())) {
                return fail(ctx, CONTROL_BLOCK_TREE, "op " + op.opId()
                    + " is a member of no block (every op of a lowered function must"
                    + " belong to exactly one block; only the module-level kinds"
                    + " MODULE_INIT|EXTERNAL_ENTRY|CLASS_FACTORY|CALLBACK_INVOKE|ENTRY_INVOKE"
                    + " may be absent from the table)", op);
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
                OpView ref = ctx.referencing.get(current);
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

    private static Optional<CompilerDiagnostic> checkExits(Facts ctx) {
        for (OpView op : ctx.ops) {
            if (op.kind() == SemanticOpKind.BREAK || op.kind() == SemanticOpKind.CONTINUE) {
                Optional<CompilerDiagnostic> failure = checkLoopTarget(ctx, op, op.loopId());
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
                Optional<CompilerDiagnostic> failure =
                    checkReturnTarget(ctx, op, op.returnFunction());
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CompilerDiagnostic> checkLoopTarget(Facts ctx, OpView op,
                                                                OpId loopId) {
        OpView target = loopId == null ? null : ctx.unitOps.get(loopId);
        if (target == null || (target.kind() != SemanticOpKind.LOOP
                && target.kind() != SemanticOpKind.FOR_EACH)) {
            return fail(ctx, CONTROL_EXIT, "transfer op " + op.opId() + " targets " + loopId
                + ", which is not a LOOP or FOR_EACH op of the unit", op);
        }
        BlockId block = ctx.table.opBlocks().get(op.opId());
        boolean enclosed = false;
        if (block != null) {
            for (OpView structure : ctx.enclosingOps(block)) {
                if (structure.opId().equals(loopId)) {
                    enclosed = true;
                    break;
                }
            }
        }
        if (!enclosed) {
            return fail(ctx, CONTROL_EXIT, "transfer op " + op.opId() + " targets loop " + loopId
                + ", which does not enclose its block"
                + (block == null ? " (the op is a member of no block)" : " " + block), op);
        }
        return Optional.empty();
    }

    private static Optional<CompilerDiagnostic> checkReturnTarget(Facts ctx, OpView op,
                                                                  FunctionId function) {
        if (function == null) {
            // The typed ReturnPayload enforces the identity, so only raw
            // content can carry an absent or malformed function position;
            // this rule owns it (the lookup below is a map against the
            // declared functions, never a null query).
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId()
                + " names no function (the payload position is absent or malformed)", op);
        }
        BlockId body = ctx.functionBodies.get(function);
        if (body == null) {
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId() + " names function " + function
                + ", which is not a function of the unit", op);
        }
        BlockId block = ctx.table.opBlocks().get(op.opId());
        if (block == null) {
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId()
                + " is a member of no block and therefore has no containing function", op);
        }
        BlockId terminal = ctx.terminalOf(block);
        if (!terminal.equals(body)) {
            return fail(ctx, CONTROL_EXIT, "RETURN op " + op.opId() + " names function " + function
                + " (body " + body + ") but its block " + block
                + " belongs to the block tree rooted at " + terminal, op);
        }
        return Optional.empty();
    }

    // =========================================================================
    // Small shared helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> E enumByName(Class<?> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return (E) Enum.valueOf((Class<E>) type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SemanticProfile profileByName(String name) {
        return enumByName(SemanticProfile.class, name);
    }
}
