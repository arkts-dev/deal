package deal.codegen.lua;

import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The shared LuaJIT emitter of the decomposition-tail integration
 * verification (ISSUE-0410): emits a real LuaJIT artifact from the
 * validated {@link LoweredModuleUnit} + {@link StructuredBodyTable} over
 * the EVALUATION_ORDER op set (assignment-delete-address-chains A-D8,
 * binary-comparison-selectors B-D6, and control-flow-structures C-D9):
 *
 * <ul>
 *   <li><b>Chains:</b> every chain child's result is materialized into a
 *       fresh local in payload order before the next child executes; the
 *       bounds check runs only from the boundary child's projection
 *       (never a target-side re-check that can raise twice); no
 *       receiver/key/RHS expression is re-emitted or re-evaluated; the
 *       retained {@code emitAssignment} double-evaluation shape
 *       ({@code LuaBackend.java:2366-2406}) never appears.</li>
 *   <li><b>Comparisons:</b> native {@code ==}/{@code ~=}/{@code <}/
 *       {@code <=}/{@code >}/{@code >=} realize the closed B-D2 table —
 *       IEEE semantics on numbers, scalar-lexicographic order on
 *       validated scalar strings (UTF-8 byte order equals code point
 *       order), native identity on tables/functions; missing compares as
 *       language null at both operand positions.</li>
 *   <li><b>Control flow:</b> no speculative execution; condition ops are
 *       emitted inside the loop structure and re-evaluated per
 *       iteration; the {@code FOR_EACH} iterable is materialized into a
 *       local once before the loop; the short-circuited block sits
 *       behind a guard so its effects cannot run when skipped; FOR's
 *       continue landing is before the update; block code follows the
 *       {@code StructuredBodyTable} membership; catches are limited to
 *       DEAL errors (an infrastructure failure is never caught or
 *       reified).</li>
 * </ul>
 *
 * <p>The artifact publishes its execution report on the dedicated trace
 * channel (stderr) through the
 * {@link deal.semantic.SemanticTraceProtocol} line grammar —
 * byte-identical to the semantic oracle's report — and writes real
 * console effect bytes to stdout. Every event carries the validated
 * operation's contract digest and structural parent, so the differential
 * harness validates each event against the exact IR op before the
 * three-way comparison: a duplicated evaluation, a wrong selector, or a
 * missing boundary fails even when printed output coincides.</p>
 */
public final class LuaSemanticEmitter {

    private LuaSemanticEmitter() {
    }

    /** Emits the complete LuaJIT module artifact for the validated unit. */
    public static String emitModule(LoweredModuleUnit unit, StructuredBodyTable table) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return new Session(unit, table, true, true).emit();
    }

    /**
     * Emits the production LuaJIT module artifact for the validated unit
     * (ISSUE-0239 E10): the conformance trace protocol is suppressed, a
     * DEAL failure publishes the retained {@code DEAL_ERROR_CODE: <code>}
     * line on stdout and exits 1, the chunk returns its export table
     * (the retained-caller ABI surface), and the {@code ENTRY_INVOKE}
     * delegation executes only for the entry module.
     *
     * @param unit        the validated lowered module unit; non-null
     * @param table       the unit's produced block-membership table; non-null
     * @param entryModule whether this module is the selected entry module
     *                    (runs the {@code ENTRY_INVOKE} delegation)
     * @return the production artifact source text
     */
    public static String emitProductionModule(LoweredModuleUnit unit,
                                              StructuredBodyTable table,
                                              boolean entryModule) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return new Session(unit, table, false, entryModule).emit();
    }

    // =========================================================================
    // Session
    // =========================================================================

    private static final class Session {
        final LoweredModuleUnit unit;
        final StructuredBodyTable table;
        final Map<OpId, SemanticOp> opsById = new HashMap<>();
        final Map<BindingId, BindingCellKind> cellKinds = new HashMap<>();
        final java.util.Set<OpId> ownedChildren = new java.util.HashSet<>();
        /** The payload-owned children only (closure computation excludes them). */
        final java.util.Set<OpId> structuralOwned;
        /** Production mode: no trace protocol, DEAL_ERROR_CODE terminal. */
        final boolean trace;
        /** The selected entry module runs the ENTRY_INVOKE delegation. */
        final boolean entryModule;
        /** Ops the block walk skips (the entry delegation of a non-entry module). */
        final java.util.Set<OpId> skippedOps = new java.util.HashSet<>();
        final StringBuilder out = new StringBuilder();
        /** The enclosing TRY_CATCH depth: transfers inside a pcall body
         *  must signal instead of goto/return (Lua closures cannot jump
         *  to the enclosing function's labels). */
        int tryDepth = 0;
        /** The function id of the factory currently being emitted (the
         *  per-function return-trampoline label suffix): every RETURN of
         *  the body jumps to the function's trampoline label — the last
         *  statement of the closure body — because a raw Lua return
         *  followed by a structure's closing label (a return inside a
         *  branch/loop/for-each block) or by the trampoline label itself
         *  (a top-level return before the tail) is invalid Lua. */
        FunctionId currentFunctionId = null;
        /** Structure ancestors are computed statically from the block tree
         *  per transfer (never a runtime-sensitive stack). */

        Session(LoweredModuleUnit unit, StructuredBodyTable table, boolean trace,
                boolean entryModule) {
            this.unit = unit;
            this.table = table;
            this.trace = trace;
            this.entryModule = entryModule;
            for (SemanticOp op : unit.ops()) {
                opsById.put(op.opId(), op);
                if (op.kind() == SemanticOpKind.BINDING_ALLOC) {
                    KindPayload.BindingAllocPayload payload =
                        (KindPayload.BindingAllocPayload) op.payload();
                    cellKinds.putIfAbsent(payload.binding(), payload.cellKind());
                }
                if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT) {
                    // B2: every group member cell is SHARED_CELL by
                    // construction (the closed payload records no
                    // cell-kind field and members carry no separate
                    // ALLOC) — the publication and the member-body
                    // loads both resolve through this fact.
                    KindPayload.RecursiveGroupInitPayload payload =
                        (KindPayload.RecursiveGroupInitPayload) op.payload();
                    for (BindingId binding : payload.bindings()) {
                        cellKinds.put(binding, BindingCellKind.SHARED_CELL);
                    }
                }
            }
            // Payload-owned children are emitted exactly once by their owner
            // arms; the block walk skips them (a double emission would
            // duplicate effects and events).
            structuralOwned = ChainOperandCompletion.structuralOwners(unit);
            ownedChildren.addAll(structuralOwned);
            ChainOperandCompletion.registerChainOperandOwners(unit, structuralOwned,
                ownedChildren);
            // The nested source ASYNC_START of an adapter-over-async task
            // executes under its outer op's arm, never at its flat
            // block-list position (the oracle's UnitState rule).
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.ASYNC_START) {
                    for (SemanticOp candidate : unit.ops()) {
                        if (candidate.kind() == SemanticOpKind.ASYNC_START
                                && op.opId().equals(candidate.origin().parentOpId())) {
                            ownedChildren.add(candidate.opId());
                        }
                    }
                }
            }
            if (!entryModule) {
                // A non-entry module never runs its ENTRY_INVOKE delegation
                // (the retained emitter invokes main() only from the entry
                // module): skip the entry op and its delegated CALL.
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() != SemanticOpKind.ENTRY_INVOKE) {
                        continue;
                    }
                    skippedOps.add(op.opId());
                    for (SemanticOp candidate : unit.ops()) {
                        if (op.opId().equals(candidate.origin().parentOpId())) {
                            skippedOps.add(candidate.opId());
                        }
                    }
                }
            }
        }

        // -- naming ---------------------------------------------------------------

        String slot(ValueId id) {
            return "S.v" + id.id();
        }

        String cell(BindingId id, long generation) {
            return "S.b" + id.id() + "g" + generation;
        }

        String label(BlockId id) {
            return "L" + id.id();
        }

        String loopExit(OpId loopOp) {
            return "X" + loopOp.id();
        }

        String loopCont(OpId loopOp) {
            return "Y" + loopOp.id();
        }

        String fnFactory(FunctionId id) {
            return "F" + id.id();
        }

        String returnLabel(FunctionId id) {
            return "__ret" + id.id();
        }

        // -- static kinds -----------------------------------------------------------

        /**
         * The static runtime kind of a descriptor for atomization/checks:
         * {@code null}, {@code bool}, {@code int}, {@code number},
         * {@code string}, {@code table}, {@code array}, {@code function},
         * {@code err}, or {@code nullable:&lt;inner&gt;}.
         */
        static String staticKind(RuntimeDescriptor descriptor) {
            if (descriptor == null) {
                return "ref";
            }
            if (descriptor instanceof RuntimeDescriptor.Null) {
                return "null";
            }
            if (descriptor instanceof RuntimeDescriptor.Boolean) {
                return "bool";
            }
            if (descriptor instanceof RuntimeDescriptor.Int) {
                return "int";
            }
            if (descriptor instanceof RuntimeDescriptor.Number) {
                return "number";
            }
            if (descriptor instanceof RuntimeDescriptor.String) {
                return "string";
            }
            if (descriptor instanceof RuntimeDescriptor.Table) {
                return "table";
            }
            if (descriptor instanceof RuntimeDescriptor.Array) {
                return "array";
            }
            if (descriptor instanceof RuntimeDescriptor.Func) {
                return "function";
            }
            if (descriptor instanceof RuntimeDescriptor.Class) {
                return "err";
            }
            if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
                return "nullable:" + staticKind(nullable.inner());
            }
            return "ref";
        }

        /** The prelude-side descriptor text for a boundary check. */
        static String descriptorText(RuntimeDescriptor descriptor) {
            if (descriptor instanceof RuntimeDescriptor.Null) {
                return "null";
            }
            if (descriptor instanceof RuntimeDescriptor.Boolean) {
                return "boolean";
            }
            if (descriptor instanceof RuntimeDescriptor.Int) {
                return "int";
            }
            if (descriptor instanceof RuntimeDescriptor.Number) {
                return "number";
            }
            if (descriptor instanceof RuntimeDescriptor.String) {
                return "string";
            }
            if (descriptor instanceof RuntimeDescriptor.Table) {
                return "table";
            }
            if (descriptor instanceof RuntimeDescriptor.Array array) {
                return "array(" + descriptorText(array.element()) + ")";
            }
            if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
                return "nullable(" + descriptorText(nullable.inner()) + ")";
            }
            if (descriptor instanceof RuntimeDescriptor.Func func) {
                StringBuilder params = new StringBuilder();
                for (RuntimeDescriptor param : func.paramTypes()) {
                    if (params.length() > 0) {
                        params.append(',');
                    }
                    params.append(descriptorText(param));
                }
                return "function(" + params + ";" + descriptorText(func.returnType()) + ")";
            }
            return "unknown";
        }

        // -- lua literals -----------------------------------------------------------

        static String luaString(String text) {
            StringBuilder sb = new StringBuilder();
            sb.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\r' -> sb.append("\\r");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\%03d", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
            return sb.toString();
        }

        static String luaNumber(double value) {
            if (Double.isNaN(value)) {
                return "(0/0)";
            }
            if (Double.isInfinite(value)) {
                return value > 0 ? "(1/0)" : "(-1/0)";
            }
            if (value == 0.0 && Double.doubleToRawLongBits(value) < 0) {
                return "-0x0.0p0";
            }
            return Double.toHexString(value);
        }

        // -- emission ----------------------------------------------------------------

        String emit() {
            out.append("-- deal.semantic-ir/1 shared LuaJIT artifact (ISSUE-0410 "
                + "decomposition tail)\n");
            // The shared run state (frames, sequence, allocation ids, the
            // async task registry and FIFO queue) is chunk-global: a
            // multi-module drive runs several artifacts in one process,
            // and the execution state is one — exactly the oracle's one
            // execution per run. __module stays chunk-local (every chunk
            // names its own module in its events).
            out.append("__frames = __frames or {}\n");
            out.append("__seq = __seq or 0\n");
            out.append("local __module\n");
            out.append("__allocIds = __allocIds or {}\n");
            out.append("__allocNext = __allocNext or 1\n");
            out.append(PRELUDE);
            if (!trace) {
                // Production: the event helpers are no-ops.
                out.append("__ev = function() end\n");
                out.append("__normalizeEvent = function() end\n");
            }
            // The export-publication helper and the export table are
            // declared in both modes: every E7-lowered unit with exports
            // carries EXPORT_PUBLISH ops (SemanticLowerer's emitE7Terminals)
            // whose publication emission references both names — a
            // trace-mode session over such a unit must emit valid Lua too
            // (production-only is the `return __exports` terminal, not the
            // declaration).
            out.append("local __exports = {}\n");
            // The host-driven callback dispatch table (CALLBACK_INVOKE):
            // a chunk-global in both modes — the per-unit dispatch entries
            // and the two host-seam helpers are the scenario host's
            // invocation surface.
            out.append("__callbacks = {}\n");
            out.append("__callbacks.__hostAtom = __hostAtom\n");
            out.append("__callbacks.__errtext = __errtext\n");
            // The async host seam defaults: the scenario host adapter
            // overrides both entries before any drive; an async host
            // operation without the override is a producer defect, never
            // a silent projection.
            out.append("__callbacks.__hostStartAsync = function(label, ...)\n");
            out.append("  error(\"an async host start has no deterministic host seam "
                + "(the scenario host drives it)\", 0)\n");
            out.append("end\n");
            out.append("__callbacks.__hostCompleteAsync = function(label)\n");
            out.append("  error(\"an async host completion has no deterministic host "
                + "seam (the scenario host drives it)\", 0)\n");
            out.append("end\n");
            // The host-driven async-entry dispatch table (async
            // EXTERNAL_ENTRY records): one entry per recorded async
            // export keyed by module#export; shared across chunks in a
            // multi-module drive (never wiped by a later chunk).
            out.append("__asyncEntries = __asyncEntries or {}\n");
            out.append("\n__module = ").append(luaString(unit.moduleId().path()))
                .append("\n");
            // One env table carries every slot and cell (LuaJIT's upvalue
            // limit never binds the function bodies).
            out.append("local S = {}\n");
            // Hoisted shared temps (goto can never jump into a local's
            // scope; every check/return temp is a top-level assignment).
            out.append("local __chk, __rvT, __rvcT, __okT, __resT, __terrT, "
                + "__cerrT, __wrappedT, __itT, __itnT, __elemT, __okB, __chkB\n");

            // Function factories first (capture cells are factory
            // arguments); the local names are pre-declared so bodies can
            // reference factories declared later in source order.
            List<String> factoryNames = new ArrayList<>();
            for (LoweredFunction function : unit.functions().values()) {
                factoryNames.add(fnFactory(function.functionId()));
            }
            if (!factoryNames.isEmpty()) {
                out.append("local ").append(String.join(", ", factoryNames))
                    .append("\n");
            }
            for (LoweredFunction function : unit.functions().values()) {
                emitFunctionFactory(function);
            }

            // The REEVALUATE_THUNK re-executors: one detached thunk
            // function per FUNCTION_ADAPT op with a thunk source. The
            // thunk ops are members only of the detached thunk block (the
            // lowerer's single-membership rule), so the module walk never
            // executes them; each invocation re-executes them here.
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.FUNCTION_ADAPT) {
                    continue;
                }
                KindPayload.FunctionAdaptPayload payload =
                    (KindPayload.FunctionAdaptPayload) op.payload();
                if (payload.source() instanceof AdaptSourceRef.Thunk thunk) {
                    emitThunkFunction(op, thunk.blockId());
                }
            }

            // The module-init block (ends with the entry delegation) inside a
            // pcall wrapper so uncaught DEAL failures publish R|failure.
            // The walk is exposed as the deferred-main entry (the scenario
            // host drives it explicitly under the defer flag — module
            // initialization before an async-entry invocation, or the entry
            // module's walk in a multi-module drive); the conformance
            // artifact skips the walk under the callback-only drive flag
            // (the semantic oracle's invokeCallback surface never runs the
            // module-init block).
            out.append("__dealMain = function()\n");
            out.append("  local __mainOk, __mainErr = pcall(function()\n");
            emitBlockOps(unit.moduleInit().initBlock());
            out.append("  end)\n");
            out.append("  if __mainOk then return true, nil end\n");
            out.append("  return false, __mainErr\n");
            out.append("end\n");
            out.append("if os.getenv(\"DEAL_DEFER_MAIN\") ~= \"1\" and "
                + "os.getenv(\"DEAL_CALLBACK_ONLY\") ~= \"1\" then\n");
            out.append("local __mainOk, __mainErr = __dealMain()\n");
            if (trace) {
                out.append("if __mainOk then\n");
                out.append("  io.stderr:write(\"R|success|null\\n\")\n");
                out.append("else\n");
                out.append("  io.stderr:write(\"R|failure|\"..__errtext(__mainErr)..\"\\n\")\n");
                out.append("end\n");
                out.append("io.stderr:flush()\n");
            } else {
                // Production terminal: a DEAL failure publishes the
                // retained DEAL_ERROR_CODE line on stdout and exits 1; a
                // non-DEAL failure rethrows; success returns the exports.
                out.append("if not __mainOk then\n");
                out.append("  if type(__mainErr) == \"table\" and __mainErr.__d then\n");
                out.append("    print(\"DEAL_ERROR_CODE: \"..__mainErr.code)\n");
                out.append("  else\n");
                out.append("    error(__mainErr, 0)\n");
                out.append("  end\n");
                out.append("  os.exit(1)\n");
                out.append("end\n");
            }
            out.append("end\n");

            // The host-driven callback dispatch entries (CALLBACK_INVOKE):
            // one per-unit entry per recorded invocation, defined after the
            // module-init walk in both modes (the block walk never runs the
            // unattached records themselves).
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.CALLBACK_INVOKE) {
                    emitCallbackInvoke(op);
                }
            }

            // The host-driven async-entry dispatch entries (async
            // EXTERNAL_ENTRY): one per-unit entry per recorded async
            // export, keyed by module#export — the scenario host adapter's
            // invocation surface (the E6 dispatch-entry pattern). The
            // entry creates the callee's canonical task; the drive flag
            // makes the top-level scenario invocation drain it and return
            // the completion, while a cross-module caller passes the drive
            // flag false (its AWAIT drains).
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && ((KindPayload.ExternalEntryPayload) op.payload()).async()) {
                    emitAsyncEntry(op);
                }
            }
            if (!trace) {
                out.append("return __exports\n");
            }
            return out.toString();
        }

        /**
         * Emits one detached thunk re-executor for a FUNCTION_ADAPT op
         * with a REEVALUATE_THUNK source: the thunk block's ops run in
         * order (payload-owned children included through their owner
         * arms) and the function returns the final producing op's result
         * — the source value the invocation consumes.
         */
        private void emitThunkFunction(SemanticOp adaptOp, BlockId block) {
            List<OpId> ops = table.blockOps().get(block);
            if (ops == null) {
                throw new IllegalStateException("the adapter thunk block " + block
                    + " has no membership row (producer defect)");
            }
            out.append("local function ").append(thunkFn(adaptOp.opId())).append("()\n");
            emitBlockOps(block);
            ValueId produced = null;
            for (int i = ops.size() - 1; i >= 0; i--) {
                OpId opId = ops.get(i);
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op != null && op.result() instanceof ValueId valueId) {
                    produced = valueId;
                }
                break;
            }
            if (produced == null) {
                throw new IllegalStateException("the adapter thunk block " + block
                    + " has no final producing value (producer defect)");
            }
            out.append("  return ").append(slot(produced)).append("\n");
            out.append("end\n");
        }

        /** One thunk re-executor name of an adapter op. */
        private String thunkFn(OpId adaptOp) {
            return "T" + adaptOp.id();
        }

        /** Emits one function factory: a closure over the passed capture cells. */
        private void emitFunctionFactory(LoweredFunction function) {
            FunctionId functionId = function.functionId();
            List<BindingId> captures = function.captures();
            StringBuilder params = new StringBuilder();
            for (BindingId captureId : captures) {
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append("c" + captureId.id());
            }
            out.append(fnFactory(functionId)).append(" = function(")
                .append(params).append(")\n");
            out.append("  return function(...)\n");
            out.append("    local __args = {...}\n");
            int argIndex = 1;
            List<OpId> bodyOps = table.blockOps().get(function.body());
            currentFunctionId = functionId;
            int paramCount = function.descriptor().paramTypes().size();
            for (int i = 0; i < paramCount && i < bodyOps.size(); i++) {
                SemanticOp op = opsById.get(bodyOps.get(i));
                if (op.kind() != SemanticOpKind.BINDING_ALLOC) {
                    break;
                }
                KindPayload.BindingAllocPayload payload =
                    (KindPayload.BindingAllocPayload) op.payload();
                BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                    BindingCellKind.DIRECT);
                out.append("    ").append(cell(payload.binding(), payload.generation())).append(" = ")
                    .append(kind == BindingCellKind.SHARED_CELL
                        ? "{__args[" + argIndex + "]}" : "__args[" + argIndex + "]")
                    .append("\n");
                argIndex++;
            }
            for (int i = paramCount; i < bodyOps.size(); i++) {
                if (ownedChildren.contains(bodyOps.get(i))) {
                    continue;
                }
                emitOp(opsById.get(bodyOps.get(i)));
            }
            // The function's return trampoline: every RETURN of the
            // body jumps here (a raw Lua return inside a structure block
            // would be followed by the structure's closing label, and a
            // trailing label after a raw top-level return is equally
            // invalid — one uniform trampoline keeps every shape valid).
            out.append("::").append(returnLabel(functionId)).append("::\n");
            out.append("  return __rvcT\n");
            out.append("  end\n");
            out.append("end\n");
            currentFunctionId = null;
        }

        /** Emits the ops of one block inline. */
        private void emitBlockOps(BlockId block) {
            for (OpId opId : table.blockOps().get(block)) {
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                if (skippedOps.contains(opId)) {
                    continue;
                }
                emitOp(opsById.get(opId));
            }
        }

        /** Emits a block's ops under a label section. */
        private void emitLabeledBlock(BlockId block) {
            out.append("::").append(label(block)).append("::\n");
            emitBlockOps(block);
        }

        // -- per-op emission ---------------------------------------------------------

        private void emitOp(SemanticOp op) {
            switch (op.kind()) {
                case CONST -> emitConst(op);
                case UNARY -> emitUnary(op);
                case BINARY -> emitBinary(op);
                case STRING_CONCAT -> emitConcat(op);
                case ARRAY_NEW -> emitArrayNew(op);
                case TABLE_NEW -> emitTableNew(op);
                case ARRAY_LENGTH -> emitArrayLength(op);
                case MEMBER_READ -> emitMemberRead(op);
                case MEMBER_WRITE -> emitMemberWrite(op);
                case MEMBER_DELETE -> emitMemberDelete(op);
                case INDEX_NORMALIZE -> emitNormalize(op);
                case INDEX_READ -> emitIndexRead(op);
                case INDEX_WRITE -> emitIndexWrite(op);
                case INDEX_DELETE -> emitIndexDelete(op);
                case OPTIONAL_READ -> emitOptionalRead(op);
                case HAS_FIELD -> emitHasField(op);
                case BOUNDARY -> emitFreeBoundary(op);
                case BINDING_ALLOC -> emitBindingAlloc(op);
                case BINDING_INIT -> emitBindingInit(op);
                case BINDING_LOAD -> emitBindingLoad(op);
                case BINDING_STORE -> emitBindingStore(op);
                case CLOSURE_NEW -> emitClosureNew(op);
                case RECURSIVE_GROUP_INIT -> emitRecursiveGroupInit(op);
                case FUNCTION_ADAPT -> emitFunctionAdapt(op);
                case CALLBACK_INVOKE -> emitCallbackInvoke(op);
                case ASYNC_START -> emitAsyncStart(op);
                case AWAIT -> emitAwait(op);
                case ASSIGN -> emitAssign(op);
                case DELETE -> emitDelete(op);
                case CALL -> emitCall(op);
                case INTRINSIC_CALL -> emitIntrinsic(op);
                case STDLIB_CALL -> emitStdlib(op);
                case BRANCH -> emitBranch(op);
                case LOOP -> emitLoop(op);
                case FOR_EACH -> emitForEach(op);
                case TRY_CATCH -> emitTryCatch(op);
                case THROW -> emitThrow(op);
                case RETURN -> emitReturn(op);
                case BREAK -> emitBreak(op);
                case CONTINUE -> emitContinue(op);
                case DISCARD -> emitDiscard(op);
                case MODULE_IMPORT -> emitModuleImport(op);
                case EXPORT_READ -> emitExportRead(op);
                case EXPORT_PUBLISH -> emitExportPublish(op);
                case EXTERNAL_ENTRY -> emitExternalEntryRecord(op);
                case ENTRY_INVOKE -> emitEntryInvoke(op);
                default -> throw new IllegalStateException("op kind " + op.kind()
                    + " has no shared-LuaJIT emission in this decomposition-tail "
                    + "domain");
            }
        }

        private String opKey(OpId id) {
            return id.module().path() + "#" + id.id();
        }

        private String parentKey(OpId parent) {
            return parent == null ? "-" : opKey(parent);
        }

        private void emitStart(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId()))).append(", \"START\", ")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", {");
            for (int i = 0; i < op.operands().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append("__atom(")
                    .append(luaString(staticKind(op.operandTypes().get(i))))
                    .append(", ").append(slot(op.operands().get(i))).append(")");
            }
            out.append("}, nil, nil)\n");
        }

        /** A boundary START event with its input atom. */
        /** START with the closed slot atom for the second operand (slot ops). */
        private void emitSlotOperandStart(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId()))).append(", \"START\", ")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", {");
            out.append("__atom(").append(luaString(staticKind(op.operandTypes().get(0))))
                .append(", ").append(slot(op.operands().get(0))).append(")");
            out.append(", __slotAtom(").append(slot(op.operands().get(1))).append(")");
            for (int i = 2; i < op.operands().size(); i++) {
                out.append(", __atom(")
                    .append(luaString(staticKind(op.operandTypes().get(i))))
                    .append(", ").append(slot(op.operands().get(i))).append(")");
            }
            out.append("}, nil, nil)\n");
        }

        private void emitBoundaryStart(SemanticOp boundary, String inputExpr,
                                       RuntimeDescriptor inputDescriptor) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {__atom(").append(luaString(staticKind(inputDescriptor)))
                .append(", ").append(inputExpr).append(")}, nil, nil)\n");
        }

        private void emitBoundarySuccess(SemanticOp boundary, String valueExpr,
                                         RuntimeDescriptor descriptor) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"SUCCESS\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {}, __atom(").append(luaString(staticKind(descriptor)))
                .append(", ").append(valueExpr).append("), nil)\n");
        }

        /** SUCCESS with the produced result atom. */
        private void emitResultSuccess(SemanticOp op, String valueExpr,
                                       RuntimeDescriptor resultDescriptor) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, __atom(").append(luaString(staticKind(resultDescriptor)))
                .append(", ").append(valueExpr).append("), nil)\n");
        }

        private void emitPlainSuccess(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, nil)\n");
        }

        /** The raw SUCCESS emission of a structure closed by a transfer. */
        private void emitClosedSuccess(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, nil)\n");
        }

        /** The structure op whose payload references the given block, or null. */
        private SemanticOp structureReferencing(BlockId block) {
            for (SemanticOp op : opsById.values()) {
                switch (op.payload()) {
                    case KindPayload.BranchPayload payload -> {
                        if (block.equals(payload.selectedBlock())
                                || block.equals(payload.alternateBlock())) {
                            return op;
                        }
                    }
                    case KindPayload.LoopPayload payload -> {
                        if (block.equals(payload.initBlock())
                                || block.equals(payload.bodyBlock())
                                || block.equals(payload.updateBlock())) {
                            return op;
                        }
                    }
                    case KindPayload.ForEachPayload payload -> {
                        if (block.equals(payload.body())) {
                            return op;
                        }
                    }
                    case KindPayload.TryCatchPayload payload -> {
                        if (block.equals(payload.tryBlock())
                                || block.equals(payload.catchBlock())) {
                            return op;
                        }
                    }
                    default -> {
                    }
                }
            }
            return null;
        }

        /** The structure ancestors of a block, innermost first (static). */
        private List<SemanticOp> structureAncestors(BlockId block) {
            List<SemanticOp> result = new ArrayList<>();
            BlockId current = block;
            while (current != null) {
                SemanticOp enclosing = structureReferencing(current);
                if (enclosing == null) {
                    break;
                }
                result.add(enclosing);
                current = table.opBlocks().get(enclosing.opId());
            }
            return result;
        }

        /** Emits the transfer closures of the ancestors down to (and including)
         *  the target loop; stops at an enclosing TRY_CATCH when requested
         *  (its dispatch closes it). */
        private void emitTransferClosures(SemanticOp transferOp, SemanticOp targetLoop,
                                          boolean stopAtTry) {
            BlockId block = table.opBlocks().get(transferOp.opId());
            for (SemanticOp ancestor : structureAncestors(block)) {
                if (stopAtTry && ancestor.kind() == SemanticOpKind.TRY_CATCH) {
                    return;
                }
                // The target loop itself is NOT closed here: a BREAK/CONTINUE
                // exits or re-enters it and its normal success line runs at
                // the loop exit (the oracle's loop wrapper emits it once).
                if (targetLoop != null && ancestor.opId().equals(targetLoop.opId())) {
                    return;
                }
                emitClosedSuccess(ancestor);
            }
        }

        private void emitFailureEvent(OpId id, String kindName, SemanticOp op,
                                      String errExpr) {
            out.append("__ev(").append(luaString(opKey(id))).append(", \"FAILURE\", ")
                .append(luaString(kindName)).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, ").append(errExpr).append(")\n");
        }

        private String originOf(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                return "-";
            }
            return op.origin().sourceId() + ":" + span.startLine() + ":"
                + span.startColumn();
        }

        // -- ops ----------------------------------------------------------------

        private void emitConst(SemanticOp op) {
            KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
            String literal = switch (payload.value()) {
                case ScalarValue.Null ignored -> "nil";
                case ScalarValue.Boolean bool -> bool.value() ? "true" : "false";
                case ScalarValue.Int intValue -> String.valueOf(intValue.value());
                case ScalarValue.Number number -> luaNumber(number.value());
                case ScalarValue.String string -> luaString(string.value());
            };
            emitStart(op);
            out.append(slot((ValueId) op.result())).append(" = ").append(literal)
                .append("\n");
            emitResultSuccess(op, slot((ValueId) op.result()), op.resultType()
                instanceof RuntimeDescriptor descriptor ? descriptor : null);
        }

        private void emitUnary(SemanticOp op) {
            KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
            ValueId input = op.operands().get(0);
            emitStart(op);
            if (payload.selector() == deal.semantic.ir.UnarySelector.BOOL_NOT) {
                out.append(slot((ValueId) op.result())).append(" = not ")
                    .append(slot(input)).append("\n");
            } else {
                out.append(slot((ValueId) op.result())).append(" = __unary(")
                    .append(luaString(payload.selector().name())).append(", ")
                    .append(slot(input)).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(originOf(op))).append(")\n");
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitBinary(SemanticOp op) {
            KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
            emitStart(op);
            boolean arithmetic = payload.selector().name().startsWith("INT32_")
                || payload.selector().name().startsWith("NUMBER_");
            boolean comparison = payload.selector().name().endsWith("_EQ")
                || payload.selector().name().endsWith("_NE")
                || payload.selector().name().endsWith("_LT")
                || payload.selector().name().endsWith("_LE")
                || payload.selector().name().endsWith("_GT")
                || payload.selector().name().endsWith("_GE");
            if (arithmetic && !comparison) {
                out.append(slot((ValueId) op.result())).append(" = __arith(")
                    .append(luaString(payload.selector().name())).append(", ")
                    .append(slot(op.operands().get(0))).append(", ")
                    .append(slot(op.operands().get(1))).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(originOf(op))).append(")\n");
                emitResultSuccess(op, slot((ValueId) op.result()),
                    (RuntimeDescriptor) op.resultType());
                return;
            }
            out.append(slot((ValueId) op.result())).append(" = __cmp(")
                .append(luaString(payload.selector().name())).append(", ")
                .append(luaString(staticKind(op.operandTypes().get(0)))).append(", ")
                .append(slot(op.operands().get(0))).append(", ")
                .append(luaString(staticKind(op.operandTypes().get(1)))).append(", ")
                .append(slot(op.operands().get(1))).append(", ")
                .append(payload.side() == null ? "nil"
                    : luaString(payload.side().name())).append(")\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitConcat(SemanticOp op) {
            KindPayload.StringConcatPayload payload =
                (KindPayload.StringConcatPayload) op.payload();
            emitStart(op);
            StringBuilder expr = new StringBuilder();
            for (ValueId fragment : payload.fragments()) {
                if (expr.length() > 0) {
                    expr.append("..");
                }
                expr.append("(").append(slot(fragment)).append(" or \"\")");
            }
            out.append(slot((ValueId) op.result())).append(" = ")
                .append(expr.length() == 0 ? "\"\"" : expr).append("\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitArrayNew(SemanticOp op) {
            KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = {__a = true, __n = ")
                .append(payload.values().size()).append("}\n");
            for (int i = 0; i < payload.values().size(); i++) {
                OpId boundaryId = payload.elementBoundaryOpIds().get(i);
                SemanticOp boundary = opsById.get(boundaryId);
                ValueId input = payload.values().get(i);
                emitBoundaryStart(boundary, slot(input), payload.elementDescriptor());
                out.append("__chk = __bcheck(")
                    .append(luaString(descriptorText(payload.elementDescriptor())))
                    .append(", ").append(luaString(staticKind(payload.elementDescriptor())))
                    .append(", ").append(slot(input)).append(")\n");
                out.append(target).append("[").append(i + 1)
                    .append("] = __chk == nil and __NULL or __chk\n");
                emitBoundarySuccess(boundary, "__chk", payload.elementDescriptor());
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitTableNew(SemanticOp op) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = {__t = true, __keys = {}}\n");
            for (KindPayload.TableEntry entry : payload.entries()) {
                out.append(target).append("[").append(luaString(entry.key()))
                    .append("] = ").append(slot(entry.value())).append("\n");
                out.append(target).append(".__keys[").append(luaString(entry.key()))
                    .append("] = true\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitArrayLength(SemanticOp op) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            emitStart(op);
            out.append(slot((ValueId) op.result())).append(" = ")
                .append(slot(payload.arrayValue())).append(".__n\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitMemberRead(SemanticOp op) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __member(").append(slot(payload.table()))
                .append(", ").append(luaString(payload.key())).append(")\n");
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, target, boundaryPayload.descriptor());
                out.append("__chk = __bcheck(")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ").append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target).append(")\n");
                out.append(target).append(" = __chk\n");
                emitBoundarySuccess(boundary, target, boundaryPayload.descriptor());
                emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
                return;
            }
            // The OPTIONAL_READ envelope shape: the raw read publishes the
            // internal missing or the present value (present null included)
            // and its SUCCESS atom renders the actual value kind —
            // missing → "missing", null → "null", else the actual-kind
            // atom, exactly the oracle's publish (a wrong-kind present
            // value atomizes as its own kind, never the declared kind).
            SemanticOp optional = optionalReadOf((ValueId) op.result());
            RuntimeDescriptor inner = optional == null
                ? null
                : ((KindPayload.OptionalReadPayload) optional.payload()).descriptor();
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, __rawAtom(")
                .append(luaString(inner == null ? "ref" : "nullable:" + staticKind(inner)))
                .append(", ").append(target).append("), nil)\n");
        }

        /**
         * The OPTIONAL_READ op consuming this result value, or null — the
         * envelope shape of a missing-capable table member read.
         */
        private SemanticOp optionalReadOf(ValueId value) {
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.OPTIONAL_READ
                        && value.equals(((KindPayload.OptionalReadPayload) candidate.payload())
                            .value())) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * OPTIONAL_READ: the internal missing pre-maps to language null
         * before the present branch is validated (the closed table's
         * optional-read rule); the CONTEXTUAL_TABLE_READ boundary child
         * validates the pre-mapped result.
         */
        private void emitOptionalRead(SemanticOp op) {
            KindPayload.OptionalReadPayload payload =
                (KindPayload.OptionalReadPayload) op.payload();
            if (payload.value() == null) {
                emitStart(op);
            } else {
                // Custom START: the raw operand atom renders the actual
                // value kind (a wrong-kind present value atomizes as its
                // own kind, exactly the oracle's publish).
                out.append("__ev(").append(luaString(opKey(op.opId())))
                    .append(", \"START\", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(op.contract().canonicalDigest()))
                    .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                    .append(", {__rawAtom(")
                    .append(luaString("nullable:" + staticKind(payload.descriptor())))
                    .append(", ").append(slot(payload.value()))
                    .append(")}, nil, nil)\n");
            }
            String target = slot((ValueId) op.result());
            if (payload.value() == null) {
                out.append(target).append(" = nil\n");
            } else {
                String source = slot(payload.value());
                out.append("if ").append(source).append(" == __MISSING then\n");
                out.append("  ").append(target).append(" = nil\n");
                out.append("else\n");
                out.append("  ").append(target).append(" = ").append(source).append("\n");
                out.append("end\n");
            }
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                // Custom boundary START: the input atom renders the
                // actual value kind (identical for a passing value,
                // actual-kind for a wrong-kind present value).
                out.append("__ev(").append(luaString(opKey(boundary.opId())))
                    .append(", \"START\", \"BOUNDARY\", ")
                    .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(boundary.origin().parentOpId())))
                    .append(", {__rawAtom(")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target).append(")}, nil, nil)\n");
                // The present branch is validated per the closed table's
                // optional-read rule; the boundary failure publishes the
                // boundary FAILURE and the op FAILURE events with the
                // boundary origin (a wrong present kind fails the
                // differential verdict even with coincidental output).
                out.append("__okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target).append(")\n");
                out.append("if not __okB then\n");
                out.append("  __chkB.o = ").append(luaString(originOf(boundary)))
                    .append("\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "__errtext(__chkB)");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__chkB)");
                out.append("  error(__chkB, 0)\n");
                out.append("end\n");
                out.append(target).append(" = __chkB\n");
                emitBoundarySuccess(boundary, target, boundaryPayload.descriptor());
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        /**
         * HAS_FIELD: the presence boolean over one checked receiver key —
         * the member-read helper's present/absent split (present null
         * included is present). The class-instance presence map is the
         * CLASSES family's realization.
         */
        private void emitHasField(SemanticOp op) {
            KindPayload.HasFieldPayload payload =
                (KindPayload.HasFieldPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = (__member(").append(slot(payload.receiver()))
                .append(", ").append(luaString(payload.key())).append(") ~= __MISSING)\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitMemberWrite(SemanticOp op) {
            KindPayload.MemberWritePayload payload =
                (KindPayload.MemberWritePayload) op.payload();
            emitStart(op);
            out.append(slot(payload.table())).append("[")
                .append(luaString(payload.key())).append("] = ")
                .append(slot(payload.value())).append("\n");
            out.append(slot(payload.table())).append(".__keys[")
                .append(luaString(payload.key())).append("] = true\n");
            emitPlainSuccess(op);
        }

        private void emitMemberDelete(SemanticOp op) {
            KindPayload.MemberDeletePayload payload =
                (KindPayload.MemberDeletePayload) op.payload();
            emitStart(op);
            out.append(slot(payload.table())).append("[")
                .append(luaString(payload.key())).append("] = nil\n");
            out.append(slot(payload.table())).append(".__keys[")
                .append(luaString(payload.key())).append("] = nil\n");
            emitPlainSuccess(op);
        }

        private void emitNormalize(SemanticOp op) {
            KindPayload.IndexNormalizePayload payload =
                (KindPayload.IndexNormalizePayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            switch (payload.mode()) {
                case ARRAY_READ, ARRAY_WRITE -> {
                    boolean write = payload.mode() == deal.semantic.ir.IndexMode.ARRAY_WRITE;
                    out.append(target).append(" = {slot = \"a\", i = ")
                        .append(slot(payload.rawKey())).append(", n = ")
                        .append(slot(payload.currentLength())).append(", a = ")
                        .append(write).append("}\n");
                }
                case TABLE_READ, TABLE_WRITE ->
                    out.append(target).append(" = {slot = \"t\", k = ")
                        .append(slot(payload.rawKey())).append("}\n");
            }
            out.append("__normalizeEvent(")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(target).append(")\n");
        }

        private void emitIndexRead(SemanticOp op) {
            KindPayload.IndexReadPayload payload = (KindPayload.IndexReadPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.elementBoundaryOpId());
            RuntimeDescriptor descriptor =
                ((KindPayload.BoundaryPayload) boundary.payload()).descriptor();
            boolean nullable = descriptor instanceof RuntimeDescriptor.Nullable;
            RuntimeDescriptor inner = nullable
                ? ((RuntimeDescriptor.Nullable) descriptor).inner() : descriptor;
            emitSlotOperandStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __arrayRead(")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(luaString(opKey(boundary.opId()))).append(", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId()))).append(", ")
                .append(luaString(descriptorText(descriptor))).append(", ")
                .append(luaString(descriptorText(inner))).append(", ")
                .append(slot(payload.container())).append(", ")
                .append(slot(payload.slot())).append(", ")
                .append(nullable ? "true" : "false").append(", ")
                .append(luaString(originOf(op))).append(")\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitIndexWrite(SemanticOp op) {
            KindPayload.IndexWritePayload payload =
                (KindPayload.IndexWritePayload) op.payload();
            emitStart(op);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            String value = slot(payload.value());
            out.append("if ").append(slotName).append(".slot == \"a\" then\n");
            out.append("  if ").append(slotName).append(".i == ").append(slotName)
                .append(".n then\n");
            out.append("    ").append(container).append(".__n = ").append(container)
                .append(".__n + 1\n");
            out.append("  end\n");
            out.append("  ").append(container).append("[").append(slotName)
                .append(".i + 1] = ").append(value)
                .append(" == nil and __NULL or ").append(value).append("\n");
            out.append("else\n");
            out.append("  ").append(container).append("[").append(slotName)
                .append(".k] = ").append(value).append("\n");
            out.append("  ").append(container).append(".__keys[").append(slotName)
                .append(".k] = true\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        private void emitIndexDelete(SemanticOp op) {
            KindPayload.IndexDeletePayload payload =
                (KindPayload.IndexDeletePayload) op.payload();
            emitStart(op);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            out.append("if ").append(slotName).append(".slot == \"a\" then\n");
            out.append("  if ").append(slotName).append(".i < ").append(container)
                .append(".__n then\n");
            out.append("    ").append(container).append("[").append(slotName)
                .append(".i + 1] = nil\n");
            out.append("  end\n");
            out.append("else\n");
            out.append("  ").append(container).append("[").append(slotName)
                .append(".k] = nil\n");
            out.append("  ").append(container).append(".__keys[").append(slotName)
                .append(".k] = nil\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        /** A free BOUNDARY op: the prelude check over the payload input. */
        private void emitFreeBoundary(SemanticOp op) {
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, nil)\n");
            out.append("__chk = __bcheck(")
                .append(luaString(descriptorText(payload.descriptor()))).append(", ")
                .append(luaString(staticKind(payload.descriptor()))).append(", ")
                .append(slot(payload.input())).append(")\n");
            if (op.result() instanceof ValueId valueId) {
                out.append(slot(valueId)).append(" = __chk\n");
                emitBoundarySuccess(op, slot(valueId), payload.descriptor());
            } else {
                emitBoundarySuccess(op, "__chk", payload.descriptor());
            }
        }

        private void emitBindingAlloc(SemanticOp op) {
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            emitStart(op);
            switch (payload.cellKind()) {
                case DIRECT -> out.append(cell(payload.binding(), payload.generation())).append(" = nil\n");
                case SHARED_CELL -> out.append(cell(payload.binding(), payload.generation())).append(" = {}\n");
            }
            emitPlainSuccess(op);
        }

        private void emitBindingInit(SemanticOp op) {
            KindPayload.BindingInitPayload payload =
                (KindPayload.BindingInitPayload) op.payload();
            emitStart(op);
            BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                BindingCellKind.DIRECT);
            String valueExpr = hasProducer(payload.value())
                ? slot(payload.value()) : "__intrinsicFn()";
            if (kind == BindingCellKind.SHARED_CELL) {
                // In-place publication: the cell table's identity is held
                // by captures and adapters, so the commit writes the cell
                // slot (never a replacement — the oracle's Cell.value
                // mutation).
                out.append(cell(payload.binding(), payload.generation()))
                    .append("[1] = ").append(valueExpr).append("\n");
            } else {
                out.append(cell(payload.binding(), payload.generation()))
                    .append(" = ").append(valueExpr).append("\n");
            }
            emitPlainSuccess(op);
        }

        /** True iff the value slot is an op result in this unit. */
        private boolean hasProducer(ValueId valueId) {
            for (SemanticOp op : unit.ops()) {
                if (valueId.equals(op.result())) {
                    return true;
                }
            }
            return false;
        }

        private void emitBindingLoad(SemanticOp op) {
            KindPayload.BindingLoadPayload payload =
                (KindPayload.BindingLoadPayload) op.payload();
            emitStart(op);
            BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                BindingCellKind.DIRECT);
            out.append(slot((ValueId) op.result())).append(" = ")
                .append(kind == BindingCellKind.SHARED_CELL
                    ? cell(payload.binding(), payload.generation()) + "[1]" : cell(payload.binding(), payload.generation()))
                .append("\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitBindingStore(SemanticOp op) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            emitStart(op);
            BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                BindingCellKind.DIRECT);
            if (kind == BindingCellKind.SHARED_CELL) {
                // In-place publication: captures and adapters hold the
                // cell table by identity (the oracle's Cell.value
                // mutation — never a replacement).
                out.append(cell(payload.binding(), payload.generation()))
                    .append("[1] = ").append(slot(payload.value())).append("\n");
            } else {
                out.append(cell(payload.binding(), payload.generation()))
                    .append(" = ").append(slot(payload.value())).append("\n");
            }
            emitPlainSuccess(op);
        }

        private void emitClosureNew(SemanticOp op) {
            KindPayload.ClosureNewPayload payload =
                (KindPayload.ClosureNewPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            StringBuilder args = new StringBuilder();
            for (BindingId captureId : payload.captures()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(cell(captureId, 0));
            }
            out.append(target).append(" = {__fn = ").append(fnFactory(payload.function()))
                .append("(").append(args).append("), __sig = ")
                .append(luaString(descriptorText(payload.signature())))
                .append(", __csig = ")
                .append(luaString(payload.signature().canonicalSpecText()))
                .append(", __fid = ")
                .append(payload.function().id()).append("}\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        /**
         * FUNCTION_ADAPT (E6, D15 creation): a wrapper function value
         * carrying the closed capture mode — VALUE retains the
         * creation-time source identity; SHARED_CELL records the
         * generation cell re-read per invocation (live reassignment
         * observed); REEVALUATE_THUNK records the detached thunk
         * re-executor. Creation evaluates no thunk and reads no binding
         * (VALUE's single operand already completed); the wrapper's
         * invocation (the {@code __fn} protocol) runs the D15 sequence at
         * every call site with the invoking op's origin.
         */
        private void emitFunctionAdapt(SemanticOp op) {
            KindPayload.FunctionAdaptPayload payload =
                (KindPayload.FunctionAdaptPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = {__mode = ");
            switch (payload.mode()) {
                case VALUE -> out.append("0");
                case SHARED_CELL -> out.append("1");
                case REEVALUATE_THUNK -> out.append("2");
            }
            out.append(", __m = ").append(payload.sourceSignature().paramTypes().size())
                .append(", __csrc = ")
                .append(luaString(payload.sourceSignature().canonicalSpecText()))
                .append(", __sig = ")
                .append(luaString(descriptorText(payload.targetSignature())))
                .append(", __csig = ")
                .append(luaString(payload.targetSignature().canonicalSpecText()))
                .append(", __fid = nil");
            switch (payload.source()) {
                case AdaptSourceRef.Value value ->
                    out.append(", __value = ")
                        .append(hasProducer(value.value()) ? slot(value.value())
                            : "__intrinsicFn()");
                case AdaptSourceRef.SharedCell cell ->
                    out.append(", __cell = ")
                        .append(cell(cell.binding(), cell.generation()));
                case AdaptSourceRef.Thunk thunk ->
                    out.append(", __thunk = ").append(thunkFn(op.opId()));
            }
            out.append("}\n");
            out.append(target).append(".__fn = __adaptInvoke\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        /**
         * RECURSIVE_GROUP_INIT (E8, atomic publication): phase 1
         * allocates every member's SHARED_CELL (a fresh table per
         * execution); phase 2 allocates every member identity into a
         * temporary — the per-member factory invocation over the member's
         * capture cells (the {@code CLOSURE_NEW} wrapper shape carrying
         * the exact signature); phase 3 publishes the member wrappers to
         * the member cells in declaration order in one ordered sequence.
         * The publication writes into the already-allocated cell tables
         * (never a replacement — a sibling's capture holds the cell
         * table by identity), and no member observes a partially
         * initialized group: no member body runs at group execution and
         * every identity is allocated before the first publication.
         */
        private void emitRecursiveGroupInit(SemanticOp op) {
            KindPayload.RecursiveGroupInitPayload payload =
                (KindPayload.RecursiveGroupInitPayload) op.payload();
            emitStart(op);
            for (BindingId binding : payload.bindings()) {
                out.append(cell(binding, 0)).append(" = {}\n");
            }
            for (int i = 0; i < payload.functions().size(); i++) {
                FunctionId functionId = payload.functions().get(i);
                LoweredFunction function = unit.functions().get(functionId);
                if (function == null) {
                    throw new IllegalStateException("group member " + functionId
                        + " has no LoweredFunction record (producer defect)");
                }
                StringBuilder args = new StringBuilder();
                for (BindingId captureId : function.captures()) {
                    if (args.length() > 0) {
                        args.append(", ");
                    }
                    args.append(cell(captureId, 0));
                }
                out.append(groupTemp(op, i)).append(" = {__fn = ")
                    .append(fnFactory(functionId)).append("(").append(args)
                    .append("), __sig = ")
                    .append(luaString(descriptorText(function.descriptor())))
                    .append(", __csig = ")
                    .append(luaString(function.descriptor().canonicalSpecText()))
                    .append(", __fid = ")
                    .append(functionId.id()).append("}\n");
            }
            for (int i = 0; i < payload.bindings().size(); i++) {
                out.append(cell(payload.bindings().get(i), 0)).append("[1] = ")
                    .append(groupTemp(op, i)).append("\n");
            }
            emitPlainSuccess(op);
        }

        /** One member identity temporary of the group op (S-table held). */
        private String groupTemp(SemanticOp op, int member) {
            return "S.__gv" + op.opId().id() + "_" + member;
        }

        private void emitAssign(SemanticOp op) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            emitStart(op);
            out.append("__okT, __resT = pcall(function()\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child, (KindPayload.BoundaryPayload) child.payload(),
                        op);
                } else {
                    emitOp(child);
                }
            }
            out.append("end)\n");
            out.append("if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            RuntimeDescriptor committedKind = producerResultType(op.result());
            emitResultSuccess(op, slot((ValueId) op.result()), committedKind);
        }

        /** The committed value's static kind: its producing op's result type. */
        private RuntimeDescriptor producerResultType(deal.semantic.ir.SemanticValue value) {
            if (value instanceof ValueId valueId) {
                for (SemanticOp producer : opsById.values()) {
                    if (valueId.equals(producer.result())
                            && producer.kind() != SemanticOpKind.ASSIGN
                            && producer.kind() != SemanticOpKind.DELETE
                            && producer.resultType() instanceof RuntimeDescriptor descriptor) {
                        return descriptor;
                    }
                }
            }
            return RuntimeDescriptor.Int.INSTANCE;
        }

        private void emitDelete(SemanticOp op) {
            KindPayload.DeletePayload payload = (KindPayload.DeletePayload) op.payload();
            emitStart(op);
            out.append("__okT, __resT = pcall(function()\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child, (KindPayload.BoundaryPayload) child.payload(),
                        op);
                } else {
                    emitOp(child);
                }
            }
            out.append("end)\n");
            out.append("if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        /**
         * A-D2 ("each child's operands complete before that child's
         * START"): the child's transitive operand-producing closure is
         * emitted at the child's position inside the chain — the
         * receiver/key/RHS operand effects interleave with the children
         * exactly as the hoisted-operand parity fixtures pin (an
         * operand's nested side-effecting argument completes before the
         * operand call's own effect, and the RHS operand effects run
         * only after the key child completed). The block walk skips
         * these ops (they are registered in the chain-owned set), so
         * each is emitted exactly once, here.
         */
        private void emitChainOperandProducers(SemanticOp child) {
            for (SemanticOp producer : ChainOperandCompletion.operandProducersOf(
                    child, unit, structuralOwned)) {
                emitOp(producer);
            }
        }

        /** A chain boundary child (bounds check only from its projection, A-D8). */
        private void emitChainBoundary(SemanticOp boundary,
                                       KindPayload.BoundaryPayload payload,
                                       SemanticOp chain) {
            String input = slot(payload.input());
            switch (payload.kind()) {
                case ARRAY_ELEMENT_ASSIGNMENT, ARRAY_ELEMENT_DELETE -> {
                    // __arrayBounds emits the boundary START/terminal events
                    // (the bounds check runs only from this projection); the
                    // delete boundary's input operand is the normalized slot.
                    if (payload.kind() == BoundaryKind.ARRAY_ELEMENT_DELETE
                            && slotEqualsChainSlot(payload.input(), chain)) {
                        out.append("__arrayBoundsSlot(")
                            .append(luaString(opKey(boundary.opId()))).append(", ")
                            .append(luaString(boundary.contract().canonicalDigest()))
                            .append(", ")
                            .append(luaString(parentKey(boundary.origin().parentOpId())))
                            .append(", ").append(chainSlotExpr(chain)).append(", ")
                            .append(chainLengthExpr(chain)).append(", ")
                            .append(payload.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                                ? "true" : "false").append(", ")
                            .append(luaString(originOf(boundary))).append(")\n");
                    } else {
                    out.append("__arrayBounds(")
                        .append(luaString(opKey(boundary.opId()))).append(", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", ").append(input).append(", ")
                        .append(chainSlotExpr(chain)).append(", ")
                        .append(chainLengthExpr(chain)).append(", ")
                        .append(luaString(descriptorText(payload.descriptor()))).append(", ")
                        .append(luaString(staticKind(payload.descriptor()))).append(", ")
                        .append(payload.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                            ? "true" : "false").append(", ")
                        .append(luaString(originOf(boundary))).append(")\n");
                    }
                }
                default -> {
                    emitBoundaryStart(boundary, input, payload.descriptor());
                    out.append("__chk = __bcheck(")
                        .append(luaString(descriptorText(payload.descriptor())))
                        .append(", ")
                        .append(luaString(staticKind(payload.descriptor())))
                        .append(", ").append(input).append(")\n");
                    emitBoundarySuccess(boundary, "__chk", payload.descriptor());
                }
            }
        }

        /** True iff the boundary input value is the chain's normalize slot. */
        private boolean slotEqualsChainSlot(ValueId input, SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && input.equals(child.result())) {
                    return true;
                }
            }
            return false;
        }

        /** The chain's normalize-slot local (found among its children). */
        private String chainSlotExpr(SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            return "nil";
        }

        private String chainLengthExpr(SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.ARRAY_LENGTH
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            return "nil";
        }

        private List<OpId> chainChildOps(SemanticOp chain) {
            return switch (chain.payload()) {
                case KindPayload.AssignPayload assign -> assign.childOps();
                case KindPayload.DeletePayload delete -> delete.childOps();
                default -> List.of();
            };
        }

        /** The single BOUNDARY child parented to the given op, or null. */
        private SemanticOp boundaryChildOf(SemanticOp op) {
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    return candidate;
                }
            }
            return null;
        }

        private void emitCall(SemanticOp op) {
            KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
            FunctionExecutionBinding binding = callBinding(payload);
            emitStart(op);
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor());
                out.append("__chk = __bcheck(")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(boundaryPayload.input())).append(")\n");
                emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
            }
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionId callee = body.functionId();
                    out.append("table.insert(__frames, 1, ")
                        .append(luaString(String.valueOf(callee.id()))).append(")\n");
                    out.append("__okT, __resT = pcall(").append(fnFactory(callee))
                        .append("(");
                    LoweredFunction calleeFunction = unit.functions().get(callee);
                    List<BindingId> captures = calleeFunction == null
                        ? List.of() : calleeFunction.captures();
                    for (int i = 0; i < captures.size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        out.append(cell(captures.get(i), 0));
                    }
                    out.append(")");
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        out.append(", ");
                        SemanticOp boundary =
                            opsById.get(payload.parameterBoundaryOpIds().get(i));
                        out.append(slot(((KindPayload.BoundaryPayload) boundary.payload())
                            .input()));
                    }
                    out.append(")\n");
                    out.append("table.remove(__frames, 1)\n");
                    out.append("if not __okT then\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__resT)");
                    out.append("  error(__resT, 0)\n");
                    out.append("end\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    // The D15 invocation protocol: resolve the source per
                    // the recorded capture mode, the source-signature
                    // check (E8010 at this CALL's origin), then the
                    // source invocation with the leading M arguments
                    // only — every N target-signature parameter boundary
                    // already ran above. The adapter protocol pushes the
                    // source body's frame itself; the identical
                    // completion error propagates unchanged.
                    String adapterSlot =
                        slot((ValueId) opsById.get(adapter.adaptOpId()).result());
                    StringBuilder args = new StringBuilder();
                    for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                        if (args.length() > 0) {
                            args.append(", ");
                        }
                        SemanticOp boundary = opsById.get(boundaryId);
                        args.append(slot(((KindPayload.BoundaryPayload) boundary.payload())
                            .input()));
                    }
                    out.append("__okT, __resT = pcall(").append(adapterSlot)
                        .append(".__fn, ").append(adapterSlot).append(", ")
                        .append(luaString(originOf(op)));
                    if (args.length() > 0) {
                        out.append(", ").append(args);
                    }
                    out.append(")\n");
                    out.append("if not __okT then\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__resT)");
                    out.append("  error(__resT, 0)\n");
                    out.append("end\n");
                }
                default -> throw new IllegalStateException("CALL " + op.opId()
                    + " resolves a binding outside the statically-resolved slice: "
                    + binding);
            }
            out.append(slot((ValueId) op.result())).append(" = __resT\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        /**
         * The statically resolved execution binding of one CALL: the
         * inline Static binding or the unit's registered binding of an
         * Indirect callee identity (the same registration the semantic
         * oracle re-resolves at execution). A Dynamic callee is the
         * runtime-resolution slice (ISSUE-0531) and fails closed here.
         */
        private FunctionExecutionBinding callBinding(KindPayload.CallPayload payload) {
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    unit.functionBindings().get(
                        new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (binding == null) {
                throw new IllegalStateException("CALL " + payload
                    + " resolves no FunctionExecutionBinding (producer defect)");
            }
            return binding;
        }

        private void emitIntrinsic(SemanticOp op) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            String input = slot(payload.input());
            String kind = staticKind(op.operandTypes().get(0));
            String origin = originOf(op);
            switch (payload.kind()) {
                case INT_CONVERT -> out.append(target).append(" = __intConv(")
                    .append(input).append(", ").append(luaString(kind)).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(origin)).append(")\n");
                case NUMBER_CONVERT -> out.append(target).append(" = __numConv(")
                    .append(input).append(", ").append(luaString(kind)).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(origin)).append(")\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitStdlib(SemanticOp op) {
            KindPayload.StdlibCallPayload payload =
                (KindPayload.StdlibCallPayload) op.payload();
            emitStart(op);
            List<SemanticOp> paramBoundaries = stdlibParamBoundaries(op);
            for (SemanticOp boundary : paramBoundaries) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor());
                out.append("__chk = __bcheck(")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(boundaryPayload.input())).append(")\n");
                emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
            }
            SemanticOp returnBoundary = stdlibReturnBoundary(op);
            if (payload.function() == deal.semantic.ir.StdlibFunctionId.CONSOLE_LOG
                    || payload.function() == deal.semantic.ir.StdlibFunctionId.CONSOLE_ERROR) {
                StringBuilder textExpr = new StringBuilder();
                for (int i = 0; i < payload.args().size(); i++) {
                    if (i > 0) {
                        textExpr.append("..\" \"..");
                    }
                    textExpr.append(slot(payload.args().get(i)));
                }
                if (textExpr.length() == 0) {
                    textExpr.append("\"\"");
                }
                out.append("io.write(").append(textExpr).append("..\"\\n\")\n");
                out.append("io.stdout:flush()\n");
                if (trace) {
                    out.append("io.stderr:write(\"F|CONSOLE_WRITE|\"..__esc(")
                        .append(textExpr).append(")..\"\\n\")\n");
                    out.append("io.stderr:flush()\n");
                }
            }
            String target = slot((ValueId) op.result());
            out.append(target).append(" = nil\n");
            if (returnBoundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                emitBoundaryStart(returnBoundary, target, boundaryPayload.descriptor());
                out.append("__chk = __bcheck(")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ").append(luaString("null")).append(", ").append(target)
                    .append(")\n");
                out.append(target).append(" = __chk\n");
                emitBoundarySuccess(returnBoundary, target, boundaryPayload.descriptor());
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private List<SemanticOp> stdlibParamBoundaries(SemanticOp op) {
            List<SemanticOp> result = new ArrayList<>();
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())
                        && ((KindPayload.BoundaryPayload) candidate.payload()).kind()
                            == BoundaryKind.STDLIB_PARAMETER) {
                    result.add(candidate);
                }
            }
            return result;
        }

        private SemanticOp stdlibReturnBoundary(SemanticOp op) {
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())
                        && ((KindPayload.BoundaryPayload) candidate.payload()).kind()
                            == BoundaryKind.STDLIB_RETURN) {
                    return candidate;
                }
            }
            return null;
        }

        private void emitBranch(SemanticOp op) {
            KindPayload.BranchPayload payload = (KindPayload.BranchPayload) op.payload();
            emitStart(op);
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case IF -> {
                    String selected = label(payload.selectedBlock());
                    String after = label(payload.selectedBlock()) + "A";
                    if (payload.alternateBlock() != null) {
                        String alternate = label(payload.alternateBlock());
                        out.append("if ").append(condition).append(" then goto ")
                            .append(selected).append(" else goto ").append(alternate)
                            .append(" end\n");
                        emitLabeledBlock(payload.selectedBlock());
                        out.append("goto ").append(after).append("\n");
                        emitLabeledBlock(payload.alternateBlock());
                        out.append("::").append(after).append("::\n");
                    } else {
                        out.append("if ").append(condition).append(" then goto ")
                            .append(selected).append(" end\n");
                        out.append("goto ").append(after).append("\n");
                        emitLabeledBlock(payload.selectedBlock());
                        out.append("::").append(after).append("::\n");
                    }
                    emitPlainSuccess(op);
                }
                case LOGICAL_AND, LOGICAL_OR -> {
                    String selected = label(payload.selectedBlock());
                    String after = label(payload.selectedBlock()) + "A";
                    String target = slot((ValueId) op.result());
                    boolean isAnd = payload.selector()
                        == deal.semantic.ir.ControlSelector.LOGICAL_AND;
                    if (isAnd) {
                        // AND: the block runs when the left value is true.
                        out.append("if ").append(condition).append(" then goto ")
                            .append(selected).append(" end\n");
                    } else {
                        // OR: the block runs when the left value is false.
                        out.append("if not ").append(condition).append(" then goto ")
                            .append(selected).append(" end\n");
                    }
                    out.append(target).append(" = ").append(condition).append("\n");
                    out.append("goto ").append(after).append("\n");
                    emitLabeledBlock(payload.selectedBlock());
                    out.append("::").append(after).append("::\n");
                    emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
                }
                default -> throw new IllegalStateException("BRANCH selector "
                    + payload.selector());
            }
        }

        private void emitLoop(SemanticOp op) {
            KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
            emitStart(op);
            String exit = loopExit(op.opId());
            String cont = loopCont(op.opId());
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case WHILE -> {
                    out.append("::").append(cont).append("::\n");
                    emitLabeledBlock(payload.initBlock());
                    out.append("if not ").append(condition).append(" then goto ")
                        .append(exit).append(" end\n");
                    emitLabeledBlock(payload.bodyBlock());
                    out.append("goto ").append(cont).append("\n");
                    out.append("::").append(exit).append("::\n");
                }
                case FOR -> {
                    emitLabeledBlock(payload.initBlock());
                    out.append("::").append(cont).append("T::\n");
                    out.append("if not ").append(condition).append(" then goto ")
                        .append(exit).append(" end\n");
                    emitLabeledBlock(payload.bodyBlock());
                    out.append("::").append(cont).append("::\n");
                    if (payload.updateBlock() != null) {
                        emitLabeledBlock(payload.updateBlock());
                    }
                    out.append("goto ").append(cont).append("T\n");
                    out.append("::").append(exit).append("::\n");
                }
                default -> throw new IllegalStateException("LOOP selector "
                    + payload.selector());
            }
            emitPlainSuccess(op);
        }

        private void emitForEach(SemanticOp op) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
            emitStart(op);
            switch (payload.mode()) {
                case ARRAY_VALUES -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    String cont = loopCont(op.opId());
                    out.append("do\n");
                    out.append("  __itT = ").append(iterable).append("\n");
                    out.append("  __itnT = __itT.__n\n");
                    out.append("  for __i = 0, __itnT - 1 do\n");
                    out.append("    __elemT = __MISSING\n");
                    out.append("    if __i < __itT.__n then\n");
                    out.append("      __elemT = __itT[__i + 1]\n");
                    out.append("      if __elemT == __NULL then __elemT = nil\n");
                    out.append("      elseif __elemT == nil then __elemT = __MISSING end\n");
                    out.append("    end\n");
                    out.append("    __foreachCheck(")
                        .append(luaString(opKey(op.opId()))).append(", ")
                        .append(luaString(op.contract().canonicalDigest())).append(", ")
                        .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                        .append(luaString(descriptorText(elementDescriptorOf(op))))
                        .append(", __elemT, ").append(luaString(originOf(op))).append(")\n");
                    out.append("    ").append(cellName).append(" = __elemT\n");
                    emitLabeledBlock(payload.body());
                    out.append("    ::").append(cont).append("::\n");
                    out.append("  end\n");
                    out.append("end\n");
                }
                case STRING_SCALARS -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    String cont = loopCont(op.opId());
                    out.append("do\n");
                    out.append("  __itT = ").append(iterable).append("\n");
                    out.append("  for __i = 1, #__itT do\n");
                    out.append("    ").append(cellName)
                        .append(" = __u8sub(__itT, __i, __i)\n");
                    emitLabeledBlock(payload.body());
                    out.append("    ::").append(cont).append("::\n");
                    out.append("  end\n");
                    out.append("end\n");
                }
            }
            emitPlainSuccess(op);
        }

        private RuntimeDescriptor elementDescriptorOf(SemanticOp op) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
            for (SemanticOp producer : opsById.values()) {
                if (payload.iterable().equals(producer.result())) {
                    if (producer.resultType() instanceof RuntimeDescriptor.Array array) {
                        return array.element();
                    }
                    return producer.resultType() instanceof RuntimeDescriptor descriptor
                        ? descriptor : RuntimeDescriptor.String.INSTANCE;
                }
            }
            return RuntimeDescriptor.String.INSTANCE;
        }

        private void emitTryCatch(SemanticOp op) {
            KindPayload.TryCatchPayload payload = (KindPayload.TryCatchPayload) op.payload();
            emitStart(op);
            String cellName = cell(payload.catchBinding(), 0);
            tryDepth++;
            out.append("__okT, __resT = pcall(function()\n");
            emitBlockOps(payload.tryBlock());
            out.append("end)\n");
            tryDepth--;
            out.append("if not __okT then\n");
            emitTransferDispatch(op, payload.tryBlock(), "  ");
            out.append("  if type(__resT) == \"table\" and __resT.__d then\n");
            out.append("    ").append(cellName).append(" = {__e = true, code = __resT.code, "
                + "m = __resT.m}\n");
            tryDepth++;
            out.append("    __okT, __terrT = pcall(function()\n");
            emitBlockOps(payload.catchBlock());
            out.append("    end)\n");
            tryDepth--;
            out.append("    if not __okT then\n");
            emitTransferDispatch(op, payload.catchBlock(), "      ");
            out.append("      __wrappedT = {__d = true, code = __terrT.code, "
                + "m = __terrT.m, o = __terrT.o, e = __terrT.e, a = __terrT.a, "
                + "f = __terrT.f, cause = __resT}\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__wrappedT)");
            out.append("      error(__wrappedT, 0)\n");
            out.append("    end\n");
            out.append("  else\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("    error(__resT, 0)\n");
            out.append("  end\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        /**
         * The transfer dispatch after a pcall-wrapped block: the block's
         * BREAK/CONTINUE/RETURN ops (recursively) signalled through the
         * pcall boundary re-apply their static transfers here.
         */
        private void emitTransferDispatch(SemanticOp tryOp, BlockId block, String pad) {
            List<SemanticOp> transfers = new ArrayList<>();
            collectTransfers(block, transfers);
            if (transfers.isEmpty()) {
                return;
            }
            out.append(pad).append("if type(__resT) == \"table\" and __resT.__tr then\n");
            for (SemanticOp transfer : transfers) {
                switch (transfer.kind()) {
                    case BREAK -> {
                        KindPayload.BreakPayload breakPayload =
                            (KindPayload.BreakPayload) transfer.payload();
                        out.append(pad).append("  if __resT.t == \"break\" and __resT.id == ")
                            .append(breakPayload.loopId().id()).append(" then\n");
                        emitPlainSuccess(tryOp);
                        emitTransferClosures(tryOp, opsById.get(breakPayload.loopId()),
                            false);
                        out.append(pad).append("    goto ")
                            .append(loopExit(breakPayload.loopId())).append("\n");
                        out.append(pad).append("  end\n");
                    }
                    case CONTINUE -> {
                        KindPayload.ContinuePayload continuePayload =
                            (KindPayload.ContinuePayload) transfer.payload();
                        out.append(pad)
                            .append("  if __resT.t == \"continue\" and __resT.id == ")
                            .append(continuePayload.loopId().id()).append(" then\n");
                        emitPlainSuccess(tryOp);
                        emitTransferClosures(tryOp, opsById.get(continuePayload.loopId()),
                            false);
                        out.append(pad).append("    goto ")
                            .append(loopCont(continuePayload.loopId())).append("\n");
                        out.append(pad).append("  end\n");
                    }
                    case RETURN -> {
                        out.append(pad)
                            .append("  if __resT.t == \"return\" then\n");
                        emitPlainSuccess(tryOp);
                        emitTransferClosures(tryOp, null, false);
                        out.append(pad).append("    return __resT.v\n");
                        out.append(pad).append("  end\n");
                    }
                    default -> {
                    }
                }
            }
            out.append(pad).append("  error(__resT, 0)\n");
            out.append(pad).append("end\n");
        }

        /** Collects the transfer ops of a block recursively through structure payloads. */
        private void collectTransfers(BlockId block, List<SemanticOp> transfers) {
            for (OpId opId : table.blockOps().get(block)) {
                SemanticOp op = opsById.get(opId);
                switch (op.kind()) {
                    case BREAK, CONTINUE, RETURN -> transfers.add(op);
                    case BRANCH -> {
                        KindPayload.BranchPayload payload =
                            (KindPayload.BranchPayload) op.payload();
                        collectTransfers(payload.selectedBlock(), transfers);
                        if (payload.alternateBlock() != null) {
                            collectTransfers(payload.alternateBlock(), transfers);
                        }
                    }
                    case LOOP -> {
                        KindPayload.LoopPayload payload =
                            (KindPayload.LoopPayload) op.payload();
                        if (payload.initBlock() != null) {
                            collectTransfers(payload.initBlock(), transfers);
                        }
                        collectTransfers(payload.bodyBlock(), transfers);
                        if (payload.updateBlock() != null) {
                            collectTransfers(payload.updateBlock(), transfers);
                        }
                    }
                    case FOR_EACH -> {
                        KindPayload.ForEachPayload payload =
                            (KindPayload.ForEachPayload) op.payload();
                        collectTransfers(payload.body(), transfers);
                    }
                    case TRY_CATCH -> {
                        KindPayload.TryCatchPayload payload =
                            (KindPayload.TryCatchPayload) op.payload();
                        collectTransfers(payload.tryBlock(), transfers);
                        collectTransfers(payload.catchBlock(), transfers);
                    }
                    default -> {
                    }
                }
            }
        }

        private void emitThrow(SemanticOp op) {
            KindPayload.ThrowPayload payload = (KindPayload.ThrowPayload) op.payload();
            emitStart(op);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "__errtext({__d = true, code = " + slot(payload.errorValue())
                    + ".code, m = " + slot(payload.errorValue()) + ".m, o = "
                    + luaString(originOf(op)) + ", e = nil, a = nil, f = __framesText(), "
                    + "cause = nil})");
            out.append("error({__d = true, code = ").append(slot(payload.errorValue()))
                .append(".code, m = ").append(slot(payload.errorValue()))
                .append(".m, o = ").append(luaString(originOf(op)))
                .append(", e = nil, a = nil, f = __framesText(), cause = nil}, 0)\n");
        }

        private void emitReturn(SemanticOp op) {
            KindPayload.ReturnPayload payload = (KindPayload.ReturnPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitStart(op);
            String value = payload.value() == null ? "nil" : slot(payload.value());
            out.append("__rvT = ").append(value).append("\n");
            emitBoundaryStart(boundary, "__rvT", boundaryPayload.descriptor());
            out.append("__rvcT = __bcheck(")
                .append(luaString(descriptorText(boundaryPayload.descriptor())))
                .append(", ").append(luaString(staticKind(boundaryPayload.descriptor())))
                .append(", __rvT)\n");
            emitBoundarySuccess(boundary, "__rvcT", boundaryPayload.descriptor());
            emitPlainSuccess(op);
            if (tryDepth > 0) {
                emitTransferClosures(op, null, true);
                out.append("error({__tr = true, t = \"return\", v = __rvcT}, 0)\n");
            } else {
                emitTransferClosures(op, null, false);
                if (currentFunctionId != null) {
                    // Jump to the function's return trampoline — the last
                    // statement of the closure body. A raw Lua return
                    // would be followed by a structure's closing label
                    // (a return inside a branch/loop/for-each block) or by
                    // the trampoline label itself (a top-level return
                    // before the tail) — both invalid Lua.
                    out.append("goto ").append(returnLabel(currentFunctionId))
                        .append("\n");
                } else {
                    // Defensive: a RETURN outside any factory emission
                    // (never produced by the walk) keeps the plain form.
                    out.append("return __rvcT\n");
                }
            }
        }

        private void emitBreak(SemanticOp op) {
            KindPayload.BreakPayload payload = (KindPayload.BreakPayload) op.payload();
            emitStart(op);
            emitPlainSuccess(op);
            if (tryDepth > 0) {
                emitTransferClosures(op, opsById.get(payload.loopId()), true);
                out.append("error({__tr = true, t = \"break\", id = ")
                    .append(payload.loopId().id()).append("}, 0)\n");
            } else {
                emitTransferClosures(op, opsById.get(payload.loopId()), false);
                out.append("goto ").append(loopExit(payload.loopId())).append("\n");
            }
        }

        private void emitContinue(SemanticOp op) {
            KindPayload.ContinuePayload payload = (KindPayload.ContinuePayload) op.payload();
            emitStart(op);
            emitPlainSuccess(op);
            if (tryDepth > 0) {
                emitTransferClosures(op, opsById.get(payload.loopId()), true);
                out.append("error({__tr = true, t = \"continue\", id = ")
                    .append(payload.loopId().id()).append("}, 0)\n");
            } else {
                emitTransferClosures(op, opsById.get(payload.loopId()), false);
                out.append("goto ").append(loopCont(payload.loopId())).append("\n");
            }
        }

        private void emitDiscard(SemanticOp op) {
            emitStart(op);
            emitPlainSuccess(op);
        }

        /**
         * {@code EXPORT_PUBLISH} — the checked {@code MODULE_EXPORT}
         * boundary (its owned child) then the export-table publication of
         * the callable function value (the wrapper's closure, unwrapped
         * so a retained caller can invoke it directly).
         */
        private void emitExportPublish(SemanticOp op) {
            KindPayload.ExportPublishPayload payload =
                (KindPayload.ExportPublishPayload) op.payload();
            emitStart(op);
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(payload.value()),
                    boundaryPayload.descriptor());
                out.append("__chk = __bcheck(")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(payload.value())).append(")\n");
                emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
            }
            out.append("__exports[").append(luaString(payload.name()))
                .append("] = {__kind = \"function\", sig = ")
                .append(luaString(payload.descriptor().canonicalSpecText()))
                .append(", f = __unfn(").append(slot(payload.value()))
                .append(")}\n");
            emitPlainSuccess(op);
        }

        /**
         * {@code EXTERNAL_ENTRY} — the callee-unit invocation record of a
         * function callable across a shared/shadow edge: the exports table
         * already publishes the callable value under the export name, so
         * the record needs no runtime action.
         */
        private void emitExternalEntryRecord(SemanticOp op) {
            emitStart(op);
            emitPlainSuccess(op);
        }

        /**
         * CALLBACK_INVOKE (E6, D13 host-driven dispatch): one per-unit
         * entry function the scenario host invokes top-level with
         * scripted arguments. The entry emits its own START (the
         * scripted argument inputs, no parentOpId — the scenario step
         * triggers it), runs the {@code HOST_TO_DEAL} parameter
         * boundaries in one-based order, executes the bound
         * {@link FunctionExecutionBinding} (a DEAL body, or the D15
         * adapter protocol with the leading-M projection), and closes
         * with the op's terminal (SUCCESS with the checked value, or
         * FAILURE with the propagated error). The single
         * {@code DEAL_TO_HOST} return boundary runs by the executed
         * body's {@code RETURN} (its payload names this op as the
         * enclosing invocation). The block walk never runs the entry.
         */
        private void emitCallbackInvoke(SemanticOp op) {
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) op.payload();
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new FunctionAllocationIdentity(payload.function().id()));
            if (binding == null) {
                throw new IllegalStateException("CALLBACK_INVOKE " + op.opId()
                    + " resolves no FunctionExecutionBinding (producer defect)");
            }
            String entry = "cb" + op.opId().id();
            out.append("__callbacks[").append(luaString(entry))
                .append("] = function(...)\n");
            out.append("  local __cargs = {...}\n");
            if (trace) {
                StringBuilder inputs = new StringBuilder();
                for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                    if (i > 0) {
                        inputs.append(", ");
                    }
                    inputs.append("__hostAtom(__cargs[").append(i + 1).append("])");
                }
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"START\", ")
                    .append(luaString(op.kind().name())).append(", ")
                    .append(luaString(op.contract().canonicalDigest()))
                    .append(", \"-\", {").append(inputs).append("}, nil, nil)\n");
            }
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (trace) {
                    out.append("  __ev(").append(luaString(opKey(boundary.opId())))
                        .append(", \"START\", \"BOUNDARY\", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", {__hostAtom(__cargs[").append(i + 1)
                        .append("])}, nil, nil)\n");
                }
                out.append("  __okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", __cargs[").append(i + 1).append("])\n");
                out.append("  if not __okB then\n");
                out.append("    __chkB.o = ")
                    .append(luaString(originOf(boundary))).append("\n");
                if (trace) {
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "__errtext(__chkB)");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__chkB)");
                }
                out.append("    error(__chkB, 0)\n");
                out.append("  end\n");
                if (trace) {
                    out.append("  __ev(").append(luaString(opKey(boundary.opId())))
                        .append(", \"SUCCESS\", \"BOUNDARY\", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", {}, __atom(")
                        .append(luaString(staticKind(boundaryPayload.descriptor())))
                        .append(", __chkB), nil)\n");
                }
                out.append("  __cargs[").append(i + 1).append("] = __chkB\n");
            }
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    LoweredFunction function = unit.functions().get(body.functionId());
                    List<BindingId> captures = function == null
                        ? List.of() : function.captures();
                    StringBuilder caps = new StringBuilder();
                    for (BindingId captureId : captures) {
                        if (caps.length() > 0) {
                            caps.append(", ");
                        }
                        caps.append(cell(captureId, 0));
                    }
                    out.append("  table.insert(__frames, 1, ")
                        .append(luaString(String.valueOf(body.functionId().id())))
                        .append(")\n");
                    out.append("  __okT, __resT = pcall(")
                        .append(fnFactory(body.functionId())).append("(")
                        .append(caps).append(")");
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        out.append(", __cargs[").append(i + 1).append("]");
                    }
                    out.append(")\n");
                    out.append("  table.remove(__frames, 1)\n");
                    out.append("  if not __okT then\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "__errtext(__resT)");
                    }
                    out.append("    error(__resT, 0)\n");
                    out.append("  end\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    String adapterSlot =
                        slot((ValueId) opsById.get(adapter.adaptOpId()).result());
                    out.append("  __okT, __resT = pcall(").append(adapterSlot)
                        .append(".__fn, ").append(adapterSlot).append(", ")
                        .append(luaString(originOf(op)));
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        out.append(", __cargs[").append(i + 1).append("]");
                    }
                    out.append(")\n");
                    out.append("  if not __okT then\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "__errtext(__resT)");
                    }
                    out.append("    error(__resT, 0)\n");
                    out.append("  end\n");
                }
                default -> throw new IllegalStateException("CALLBACK_INVOKE "
                    + op.opId() + " resolves a binding outside the statically-resolved "
                    + "slice: " + binding);
            }
            if (trace) {
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"SUCCESS\", ")
                    .append(luaString(op.kind().name())).append(", ")
                    .append(luaString(op.contract().canonicalDigest()))
                    .append(", \"-\", {}, __atom(")
                    .append(luaString(staticKind(payload.descriptor().returnType())))
                    .append(", __resT), nil)\n");
            }
            out.append("  return __resT\n");
            out.append("end\n");
        }

        // -- async (E4, D13) ----------------------------------------------------------

        /** The canonical referent token identity (alias chains resolve transitively). */
        private long canonicalReferent(AsyncTokenId token) {
            AsyncTokenId current = token;
            while (current instanceof AsyncTokenId.Alias alias) {
                current = alias.referent();
            }
            return current.tokenId();
        }

        /** The canonical referent's closed owner (statically resolved). */
        private AsyncTokenOwner canonicalOwnerOf(AsyncTokenId token) {
            AsyncTokenId current = token;
            while (current instanceof AsyncTokenId.Alias alias) {
                current = alias.referent();
            }
            return ((AsyncTokenId.Canonical) current).owner();
        }

        /** The canonical token atom of an ASYNC_START/EXTERNAL_ENTRY SUCCESS. */
        private String tokenAtom(AsyncTokenId token) {
            return switch (token) {
                case AsyncTokenId.Canonical canonical -> "tok:" + canonical.tokenId()
                    + ":" + canonical.owner().name();
                case AsyncTokenId.Alias alias -> "alias:" + alias.tokenId() + "->"
                    + canonicalReferent(alias.referent());
            };
        }

        /** SUCCESS with a raw output atom expression (token atoms). */
        private void emitTokenSuccess(SemanticOp op, String atomExpr) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, ").append(atomExpr).append(", nil)\n");
        }

        /** A boundary START event with a raw input atom expression. */
        private void emitBoundaryStartAtom(SemanticOp boundary, String atomExpr) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {").append(atomExpr).append("}, nil, nil)\n");
        }

        /** The nested source ASYNC_START parented to an adapter-over-async op. */
        private SemanticOp nestedAsyncStartOf(SemanticOp outer) {
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.ASYNC_START
                        && outer.opId().equals(candidate.origin().parentOpId())) {
                    return candidate;
                }
            }
            throw new IllegalStateException("the adapter-over-async task has no nested "
                + "source ASYNC_START (producer defect)");
        }

        /**
         * ASYNC_START (E4, D13 task creation): the parameter boundaries
         * (the complete xN set under RUN — zero under
         * ELIDED_BY_ADAPTER), then exactly one task record per call. A
         * DEAL body task is a coroutine wrapping the body-task closure
         * (the canonical {@code AsyncTokenId} is the task record); an
         * adapter-over-async task resolves the D15 source, checks the
         * source signature, and executes the nested source op; a host
         * operation starts through the artifact's host-seam dispatch
         * entry (bad handle → the op's own E8010
         * {@code ASYNC_OPERATION_HANDLE}); an external operation starts
         * through the callee artifact's async-entry dispatch entry. The
         * op's terminal publishes the canonical/alias token atom.
         */
        private void emitAsyncStart(SemanticOp op) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            AsyncTokenId token = (AsyncTokenId) op.result();
            emitStart(op);
            // The argument carrier: the boundary-checked values (RUN) or
            // the raw operand values (ELIDED_BY_ADAPTER) — a fresh table
            // per execution, captured by the task record.
            if (payload.parameterBoundaryMode() == ParameterBoundaryMode.RUN) {
                out.append("S.__sa").append(op.opId().id()).append(" = {}\n");
                for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                    SemanticOp boundary = opsById.get(boundaryId);
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) boundary.payload();
                    emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                        boundaryPayload.descriptor());
                    out.append("__chk = __bcheck(")
                        .append(luaString(descriptorText(boundaryPayload.descriptor())))
                        .append(", ")
                        .append(luaString(staticKind(boundaryPayload.descriptor())))
                        .append(", ").append(slot(boundaryPayload.input()))
                        .append(")\n");
                    emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
                    out.append("S.__sa").append(op.opId().id())
                        .append("[#S.__sa").append(op.opId().id())
                        .append(" + 1] = __chk\n");
                }
            } else {
                out.append("S.__sa").append(op.opId().id()).append(" = {");
                for (int i = 0; i < op.operands().size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(slot(op.operands().get(i)));
                }
                out.append("}\n");
            }
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                default -> throw new IllegalStateException("ASYNC_START " + op.opId()
                    + " resolves a callee outside the statically-resolved slice: "
                    + payload.callee());
            };
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    LoweredFunction function = unit.functions().get(body.functionId());
                    List<BindingId> captures = function == null
                        ? List.of() : function.captures();
                    StringBuilder caps = new StringBuilder();
                    for (BindingId captureId : captures) {
                        if (caps.length() > 0) {
                            caps.append(", ");
                        }
                        caps.append(cell(captureId, 0));
                    }
                    out.append("__asyncStartTask(").append(token.tokenId())
                        .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
                    out.append("  table.insert(__frames, 1, ")
                        .append(luaString(String.valueOf(body.functionId().id())))
                        .append(")\n");
                    out.append("  local __okA, __resA = pcall(")
                        .append(fnFactory(body.functionId())).append("(").append(caps)
                        .append("), unpack(S.__sa").append(op.opId().id())
                        .append(", 1, #S.__sa").append(op.opId().id()).append("))\n");
                    out.append("  table.remove(__frames, 1)\n");
                    out.append("  if not __okA then error(__resA, 0) end\n");
                    out.append("  return __resA\n");
                    out.append("end), S.__sa").append(op.opId().id()).append(")\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    // The outer adapter-over-async task (zero return
                    // boundaries of its own): the D15 source resolution
                    // and source-signature check, then the nested source
                    // op's full emission (its task queues under the FIFO
                    // drain — the outer task completes after it).
                    SemanticOp nested = nestedAsyncStartOf(op);
                    out.append("__asyncStartTask(").append(token.tokenId())
                        .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
                    out.append("  local __srcA = __adaptSource(")
                        .append(slot((ValueId) opsById.get(adapter.adaptOpId()).result()))
                        .append(")\n");
                    out.append("  __fncheck(__srcA, ")
                        .append(luaString(adapter.sourceSignature().canonicalSpecText()))
                        .append(", ").append(luaString(originOf(op))).append(")\n");
                    emitAsyncStart(nested);
                    out.append("  return nil\n");
                    out.append("end), S.__sa").append(op.opId().id()).append(")\n");
                }
                case FunctionExecutionBinding.HostFunction host ->
                    emitAsyncHostStart(op, token, host.hostModuleId().path(),
                        host.exportName());
                case FunctionExecutionBinding.HostFunctionValue hostValue ->
                    emitAsyncHostStart(op, token, hostValue.hostModuleId().path(),
                        "@value");
                case FunctionExecutionBinding.ExternalFunction external ->
                    emitAsyncExternalStart(op, token, payload.externalAsyncLink());
            }
            emitTokenSuccess(op, luaString(tokenAtom(token)));
        }

        /** The ASYNC_START(HOST) terminal: the seam start + the bad-handle check. */
        private void emitAsyncHostStart(SemanticOp op, AsyncTokenId token,
                                        String module, String export) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            String label = payload.hostOperationLabel();
            if (trace) {
                out.append("io.stderr:write(\"F|ASYNC_START_OP|\"..__esc(")
                    .append(luaString(label)).append(")..\"\\n\")\n");
                out.append("io.stderr:flush()\n");
            }
            out.append("local __handleH = __callbacks.__hostStartAsync(")
                .append(luaString(label)).append(", ")
                .append(luaString(module)).append(", ")
                .append(luaString(export)).append(", unpack(S.__sa")
                .append(op.opId().id()).append(", 1, #S.__sa")
                .append(op.opId().id()).append("))\n");
            out.append("if __handleH == nil then\n");
            out.append("  local __eH = __failExpr(\"E8010\", "
                + "\"async operation mismatch: expected async-operation, got nothing\", ")
                .append(luaString(originOf(op)))
                .append(", \"async-operation\", \"nothing\")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__eH)");
            out.append("  error(__eH, 0)\n");
            out.append("end\n");
            out.append("__asyncStartHost(").append(token.tokenId()).append(", ")
                .append(luaString(label)).append(")\n");
        }

        /** The ASYNC_START(EXTERNAL) terminal: the callee artifact's async-entry dispatch. */
        private void emitAsyncExternalStart(SemanticOp op, AsyncTokenId token,
                                            ExternalAsyncLink link) {
            if (link == null) {
                throw new IllegalStateException("ASYNC_START(EXTERNAL) without "
                    + "its ExternalAsyncLink (producer defect)");
            }
            out.append("__asyncEntries[")
                .append(luaString(link.calleeModuleId().path() + "#"
                    + link.exportName())).append("](")
                .append(luaString(opKey(op.opId()))).append(", false, unpack(S.__sa")
                .append(op.opId().id()).append(", 1, #S.__sa")
                .append(op.opId().id()).append("))\n");
        }

        /**
         * AWAIT — the completion position (D13 step 6): the deterministic
         * FIFO drain first, then the canonical referent's completion. A
         * pending host operation completes through the host seam (the
         * ordered ASYNC_COMPLETE_* effects); a failed operation publishes
         * the identical error — never a re-check or a synthesized copy —
         * and a completed value crosses the single {@code ASYNC_COMPLETION}
         * boundary at the await site.
         */
        private void emitAwait(SemanticOp op) {
            KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) op.payload();
            long canonicalId = canonicalReferent(payload.token());
            SemanticOp boundary = opsById.get(payload.completionBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitStart(op);
            out.append("__asyncDrain()\n");
            out.append("local __tA = __tasks[").append(canonicalId).append("]\n");
            out.append("if __tA == nil then\n");
            out.append("  error(\"AWAIT consumes an unbound token ")
                .append(payload.token()).append(" (producer defect)\", 0)\n");
            out.append("end\n");
            out.append("if __tA.status == 2 then\n");
            out.append("  local __oA = __callbacks.__hostCompleteAsync(__tA.label)\n");
            out.append("  if __oA.ok then\n");
            if (trace) {
                out.append("    io.stderr:write(\"F|ASYNC_COMPLETE_RETURN|\""
                    + "..__esc(__tA.label..\"=\"..__hostAtom(__oA.v))..\"\\n\")\n");
                out.append("    io.stderr:flush()\n");
            }
            out.append("    __tA.value = __oA.v\n");
            out.append("  else\n");
            if (trace) {
                out.append("    io.stderr:write(\"F|ASYNC_COMPLETE_THROW|\""
                    + "..__esc(__tA.label..\"!\"..__oA.code)..\"\\n\")\n");
                out.append("    io.stderr:flush()\n");
            }
            out.append("    __tA.err = {__d = true, code = __oA.code, m = __oA.m, o = ")
                .append(luaString(originOf(op)))
                .append(", e = nil, a = nil, f = __framesText(), cause = nil}\n");
            out.append("  end\n");
            out.append("  __tA.status = 1\n");
            out.append("end\n");
            out.append("if __tA.err ~= nil then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__tA.err)");
            out.append("  error(__tA.err, 0)\n");
            out.append("end\n");
            // The single ASYNC_COMPLETION boundary at the await site: a
            // host-scripted completion atomizes by its runtime carrier; a
            // DEAL body value atomizes by the declared descriptor.
            if (canonicalOwnerOf(payload.token()) == AsyncTokenOwner.HOST_OPERATION) {
                emitBoundaryStartAtom(boundary, "__hostAtom(__tA.value)");
            } else {
                emitBoundaryStart(boundary, "__tA.value", boundaryPayload.descriptor());
            }
            out.append("__chk = __bcheck(")
                .append(luaString(descriptorText(boundaryPayload.descriptor())))
                .append(", ")
                .append(luaString(staticKind(boundaryPayload.descriptor())))
                .append(", __tA.value)\n");
            emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
            out.append(slot((ValueId) op.result())).append(" = __chk\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        /**
         * The async EXTERNAL_ENTRY dispatch entry (the E6 dispatch-entry
         * pattern): the scenario host adapter invokes it top-level with
         * scripted arguments (drive flag on — the entry drains and
         * returns the completion), and a cross-module caller invokes it
         * with the drive flag off (its AWAIT drains). The entry emits the
         * callee record's START/SUCCESS under the passed parent key and
         * creates exactly one canonical task wrapping the entry function's
         * body-task closure.
         */
        private void emitAsyncEntry(SemanticOp op) {
            KindPayload.ExternalEntryPayload payload =
                (KindPayload.ExternalEntryPayload) op.payload();
            LoweredFunction function = unit.functions().get(payload.function());
            List<BindingId> captures = function == null
                ? List.of() : function.captures();
            StringBuilder caps = new StringBuilder();
            for (BindingId captureId : captures) {
                if (caps.length() > 0) {
                    caps.append(", ");
                }
                caps.append(cell(captureId, 0));
            }
            out.append("__asyncEntries[")
                .append(luaString(unit.moduleId().path() + "#" + payload.exportName()))
                .append("] = function(__parentKey, __drive, ...)\n");
            out.append("  local __eargs = {...}\n");
            if (trace) {
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"START\", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(op.contract().canonicalDigest()))
                    .append(", __parentKey, {}, nil, nil)\n");
            }
            out.append("  __asyncStartTask(").append(op.opId().id())
                .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
            out.append("    table.insert(__frames, 1, ")
                .append(luaString(String.valueOf(payload.function().id())))
                .append(")\n");
            out.append("    local __okA, __resA = pcall(")
                .append(fnFactory(payload.function())).append("(").append(caps)
                .append("), unpack(__eargs, 1, #__eargs))\n");
            out.append("    table.remove(__frames, 1)\n");
            out.append("    if not __okA then error(__resA, 0) end\n");
            out.append("    return __resA\n");
            out.append("  end), __eargs)\n");
            if (trace) {
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(op.contract().canonicalDigest()))
                    .append(", __parentKey, {}, \"tok:").append(op.opId().id())
                    .append(":DEAL_BODY_TASK\", nil)\n");
            }
            out.append("  if __drive then\n");
            out.append("    __asyncDrain()\n");
            out.append("    local __tE = __tasks[").append(op.opId().id()).append("]\n");
            out.append("    if __tE.err ~= nil then error(__tE.err, 0) end\n");
            out.append("    return __tE.value\n");
            out.append("  end\n");
            out.append("end\n");
        }

        /**
         * ENTRY_INVOKE — delegates exactly one CALL(DIRECT) to main
         * (its owned child) and exits after the terminal.
         */
        private void emitEntryInvoke(SemanticOp op) {
            emitStart(op);
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.CALL
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    emitCall(candidate);
                }
            }
            emitPlainSuccess(op);
        }

        private void emitModuleImport(SemanticOp op) {
            emitStart(op);
            emitPlainSuccess(op);
        }

        private void emitExportRead(SemanticOp op) {
            emitStart(op);
            out.append(slot((ValueId) op.result())).append(" = __intrinsicFn()\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }
    }

    // =========================================================================
    // The Lua runtime prelude (byte-identical protocol output)
    // =========================================================================

    private static final String PRELUDE = """
-- ==== shared runtime prelude ====
local __MISSING = setmetatable({}, {__tostring = function() return "missing" end})
local __NULL = setmetatable({}, {__tostring = function() return "null" end})
-- The member-read helper (ISSUE-0239 E10): a present key yields the
-- stored value (a present null is the plain nil stored by the write
-- paths); an absent key yields the internal MISSING sentinel — exactly
-- JvmRuntime.Table.read's present/absent split, so the contextual
-- table-read boundary decides null-vs-error identically on both
-- targets. The __keys marker sub-table is the same one the TABLE_NEW /
-- MEMBER_WRITE / MEMBER_DELETE / INDEX_WRITE / INDEX_DELETE paths
-- maintain, so read and write presence stay consistent.
local function __member(t, k)
  if t.__keys[k] then return t[k] end
  return __MISSING
end
local function __esc(s)
  if s == nil then return "" end
  local out = {}
  for i = 1, #s do
    local c = string.sub(s, i, i)
    local b = string.byte(c)
    if c == "\\\\" then out[#out + 1] = "\\\\\\\\"
    elseif c == "\\n" then out[#out + 1] = "\\\\n"
    elseif c == "\\t" then out[#out + 1] = "\\\\t"
    elseif c == "\\r" then out[#out + 1] = "\\\\r"
    elseif c == ";" then out[#out + 1] = "\\\\u003b"
    elseif c == "|" then out[#out + 1] = "\\\\u007c"
    elseif b < 32 or b == 127 then
      out[#out + 1] = string.format("\\\\u%04x", b)
    else out[#out + 1] = c end
  end
  return table.concat(out)
end
local function __framesText()
  if #__frames == 0 then return "-" end
  return table.concat(__frames, ",")
end
local function __allocId(v)
  local id = __allocIds[v]
  if id == nil then
    id = __allocNext
    __allocNext = __allocNext + 1
    __allocIds[v] = id
  end
  return id
end
local function __intrinsicFn()
  return {__f = true}
end
local function __atom(kind, v)
  if v == __MISSING then return "missing" end
  if kind == "null" then return "null" end
  if kind == "missing" then return "missing" end
  if kind == "bool" then return "bool:"..tostring(v) end
  if kind == "int" then return "int:"..tostring(v) end
  if kind == "number" then
    if v ~= v then return "num:nan" end
    if v == 0 and 1 / v < 0 then return "num:-0x0.0p0" end
    local hex = string.format("%a", v)
    hex = string.gsub(hex, "p(%+)(%d)", "p%2")
    if not string.find(hex, ".", 1, true) then
      hex = string.gsub(hex, "^(%-?0x[0-9a-f]+)p", "%1.0p")
    end
    return "num:"..hex
  end
  if kind == "string" then
    if v == nil then return "str:" end
    return "str:"..__esc(v)
  end
  if kind == "err" then
    return "err:"..v.code..":"..__esc(v.m or "")
  end
  if kind == "ref" or kind == "table" or kind == "array" or kind == "function" then
    return "ref:"..__allocId(v)
  end
  if string.sub(kind, 1, 9) == "nullable:" then
    if v == nil then return "null" end
    return __atom(string.sub(kind, 10), v)
  end
  return "missing"
end
local function __errtext(e)
  if type(e) == "table" and e.__d then
    local parts = {e.code, __esc(e.m or ""), e.o or "-", e.e or "-", e.a or "-",
                   e.f or "-", e.cause and __errtext(e.cause) or "-"}
    return table.concat(parts, ";")
  end
  return "E9999;"..__esc(tostring(e))..";-;-;-;-;-"
end
local function __ev(op, phase, kind, digest, parent, inputs, output, errtext)
  local parts = {__seq, __module, op, phase, kind, digest, parent}
  if inputs then
    for _, inp in ipairs(inputs) do parts[#parts + 1] = inp end
  end
  local line = "T|"..table.concat(parts, "|")
  if output then line = line.."|=>"..output end
  if errtext then line = line.."|!"..errtext end
  io.stderr:write(line.."\\n")
  io.stderr:flush()
  __seq = __seq + 1
end
local function __actualOf(staticKind, v)
  if v == __MISSING then return "missing" end
  if v == nil then return "null" end
  local t = type(v)
  if t == "boolean" then return "boolean" end
  if t == "number" then
    local inner = staticKind
    if string.sub(staticKind, 1, 9) == "nullable:" then inner = string.sub(staticKind, 10) end
    if inner == "int" then return "int" end
    return "number"
  end
  if t == "string" then return "string" end
  if t == "table" then
    if staticKind == "err" then return "class:@builtin/Error" end
    if staticKind == "table" then return "table" end
    if staticKind == "array" then return "array" end
    return "table"
  end
  if t == "function" then return "function" end
  return staticKind
end
-- The raw read atom of the OPTIONAL_READ envelope: the value's actual
-- runtime kind — missing → "missing", null → "null", else the actual
-- kind's atom (a wrong-kind present value atomizes as its own kind,
-- exactly the oracle's publish). Numbers render through the declared
-- inner kind (Lua numbers carry no int/number distinction).
local function __rawAtom(kind, v)
  if v == __MISSING then return "missing" end
  if v == nil then return "null" end
  if type(v) == "boolean" then return "bool:"..tostring(v) end
  if type(v) == "number" then
    local inner = kind
    if string.sub(kind, 1, 9) == "nullable:" then inner = string.sub(kind, 10) end
    if inner == "int" then return "int:"..tostring(v) end
    return __atom("number", v)
  end
  if type(v) == "string" then return "str:"..__esc(v) end
  if type(v) == "table" then
    if v.__d then return "err:"..v.code..":"..__esc(v.m or "") end
    return "ref:"..__allocId(v)
  end
  if type(v) == "function" then return "ref:"..__allocId(v) end
  return __atom(kind, v)
end
local function __failExpr(code, msg, o, e, a)
  return {__d = true, code = code, m = msg, o = o, e = e, a = a, f = __framesText(),
          cause = nil}
end
local function __bcheck(desc, staticKind, v)
  local actual = __actualOf(staticKind, v)
  local function fail(expected)
    return error(__failExpr("E8001", "expected "..expected..", got "..actual,
      "-", expected, actual), 0)
  end
  if desc == "null" then
    if v == nil then return v end
    return fail("null")
  elseif desc == "boolean" then
    if type(v) == "boolean" then return v end
    return fail("boolean")
  elseif desc == "int" then
    if type(v) == "number" and v % 1 == 0 then
      if v ~= v then return fail("int") end
      if v == math.huge or v == -math.huge then return fail("int") end
      if v >= -2147483648 and v <= 2147483647 then return v end
      return error(__failExpr("E8004", "int out of range", "-", "int", actual), 0)
    end
    return fail("int")
  elseif desc == "number" then
    if type(v) == "number" then return v end
    return fail("number")
  elseif desc == "string" then
    if type(v) == "string" then return v end
    return fail("string")
  elseif desc == "table" then
    if type(v) == "table" and v.__t then return v end
    return fail("table")
  elseif string.sub(desc, 1, 6) == "array(" then
    if type(v) == "table" and v.__a then
      local inner = string.sub(desc, 7, -2)
      for i = 1, v.__n do
        local elem = v[i]
        if elem == __NULL then elem = nil
        elseif elem == nil then elem = __MISSING end
        local ok, checked = pcall(__bcheck, inner,
          (elem == __MISSING) and "missing" or inner, elem)
        if not ok then
          local expected = inner
          local actualKind = __actualOf((elem == __MISSING) and "missing" or inner, elem)
          return error(__failExpr("E8003",
            "array element "..i.." type mismatch", "-", expected, actualKind), 0)
        end
      end
      return v
    end
    return fail("array")
  elseif string.sub(desc, 1, 9) == "nullable(" then
    if v == nil or v == __MISSING then return nil end
    return __bcheck(string.sub(desc, 10, -2), staticKind, v)
  elseif string.sub(desc, 1, 9) == "function(" then
    if type(v) == "function" then
      local carried = v.__sig or ""
      if carried == desc then return v end
      return error(__failExpr("E8010",
        "function signature mismatch: expected "..desc..", got "..carried,
        "-", desc, carried), 0)
    end
    if type(v) == "table" and v.__fn ~= nil then
      local carried = v.__sig or ""
      if carried == desc then return v end
      return error(__failExpr("E8010",
        "function signature mismatch: expected "..desc..", got "..carried,
        "-", desc, carried), 0)
    end
    return fail("function")
  end
  return v
end
local function __cmp(selector, kindL, l, kindR, r, side)
  local lnil = (l == nil or l == __MISSING)
  local rnil = (r == nil or r == __MISSING)
  if lnil or rnil then
    if selector == "NULLABLE_NULL_EQ" then
      local named = (side == "LEFT") and l or r
      return (named == nil or named == __MISSING)
    elseif selector == "NULLABLE_NULL_NE" then
      local named = (side == "LEFT") and l or r
      return not (named == nil or named == __MISSING)
    end
    local both = lnil and rnil
    if string.sub(selector, -3) == "_EQ" then return both end
    if string.sub(selector, -3) == "_NE" then return not both end
    return false
  end
  if selector == "INT32_EQ" then return l == r end
  if selector == "INT32_NE" then return l ~= r end
  if selector == "INT32_LT" then return l < r end
  if selector == "INT32_LE" then return l <= r end
  if selector == "INT32_GT" then return l > r end
  if selector == "INT32_GE" then return l >= r end
  if selector == "NUMBER_EQ" then return l == r end
  if selector == "NUMBER_NE" then return l ~= r end
  if selector == "NUMBER_LT" then return l < r end
  if selector == "NUMBER_LE" then return l <= r end
  if selector == "NUMBER_GT" then return l > r end
  if selector == "NUMBER_GE" then return l >= r end
  if selector == "STRING_EQ" then return l == r end
  if selector == "STRING_NE" then return l ~= r end
  if selector == "STRING_LT" then return l < r end
  if selector == "STRING_LE" then return l <= r end
  if selector == "STRING_GT" then return l > r end
  if selector == "STRING_GE" then return l >= r end
  if selector == "BOOLEAN_EQ" then return l == r end
  if selector == "BOOLEAN_NE" then return l ~= r end
  if selector == "NULL_EQ" then return true end
  if selector == "NULL_NE" then return false end
  if selector == "NULLABLE_EQ" then return l == r end
  if selector == "NULLABLE_NE" then return l ~= r end
  if selector == "REFERENCE_EQ" then return l == r end
  if selector == "REFERENCE_NE" then return l ~= r end
  return false
end
local function __unary(selector, v, opKey, digest, parent, origin)
  if selector == "BOOL_NOT" then return not v end
  if selector == "INT32_NEG" then
    local r = -v
    if r < -2147483648 or r > 2147483647 then
      local e = __failExpr("E8004", "int out of range", origin, nil, nil)
      __ev(opKey, "FAILURE", "UNARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return r
  end
  return -v
end
local function __arith(selector, l, r, opKey, digest, parent, origin)
  local function rng(v)
    if v < -2147483648 or v > 2147483647 then
      local e = __failExpr("E8004", "int out of range", origin, nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return v
  end
  if selector == "INT32_ADD" then return rng(l + r) end
  if selector == "INT32_SUB" then return rng(l - r) end
  if selector == "INT32_MUL" then return rng(l * r) end
  if selector == "INT32_DIV_TRUNC" then
    if r == 0 then
      local e = __failExpr("E8005", "integer division by zero", origin, nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    local q = l / r
    local t = math.floor(math.abs(q))
    if q < 0 then t = -t end
    return rng(t)
  end
  if selector == "INT32_MOD_TRUNC" then
    if r == 0 then
      local e = __failExpr("E8005", "integer division by zero", origin, nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return rng(l % r)
  end
  if selector == "INT32_POW" then
    if r < 0 then
      local e = __failExpr("E8006", "integer exponent must be non-negative", origin,
        nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    local result = 1
    for _ = 1, r do result = result * l end
    return rng(result)
  end
  if selector == "NUMBER_ADD" then return l + r end
  if selector == "NUMBER_SUB" then return l - r end
  if selector == "NUMBER_MUL" then return l * r end
  if selector == "NUMBER_DIV_IEEE" then return l / r end
  if selector == "NUMBER_MOD_FLOOR" then return l - math.floor(l / r) * r end
  if selector == "NUMBER_POW_IEEE" then return l ^ r end
  return 0
end
local function __intConv(v, kind, opKey, digest, parent, origin)
  if v == nil then
    local e = __failExpr("E8001", "cannot convert null to int", origin, "int", "null")
    __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  if type(v) == "number" then
    if v ~= v then
      local e = __failExpr("E8001", "expected int, got NaN", origin, "int", "NaN")
      __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    if v == math.huge or v == -math.huge then
      local e = __failExpr("E8001", "expected int, got infinity", origin, "int",
        "infinity")
      __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    if v % 1 ~= 0 then
      local e = __failExpr("E8001", "expected int, got non-integer number", origin,
        "int", "non-integer number")
      __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    if v < -2147483648 or v > 2147483647 then
      local e = __failExpr("E8004", "int out of range", origin, "int", "number")
      __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return v
  end
  local e = __failExpr("E8001", "expected int, got "..__actualOf(kind, v), origin,
    "int", __actualOf(kind, v))
  __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
  error(e, 0)
end
local function __numConv(v, kind, opKey, digest, parent, origin)
  if v == nil then
    local e = __failExpr("E8001", "cannot convert null to number", origin, "number",
      "null")
    __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  if type(v) == "number" then return v end
  local e = __failExpr("E8001", "expected number, got "..__actualOf(kind, v), origin,
    "number", __actualOf(kind, v))
  __ev(opKey, "FAILURE", "INTRINSIC_CALL", digest, parent, {}, nil, __errtext(e))
  error(e, 0)
end
local function __arrayRead(opKey, digest, parent, bKey, bDigest, bParent, desc, inner,
                           container, slotName, nullable, origin)
  local index = slotName.i
  local elem = __MISSING
  if index < 0 then
    __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom("missing", __MISSING)},
      nil, nil)
    local e = __failExpr("E8002", "negative array index", origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    __ev(opKey, "FAILURE", "INDEX_READ", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  if index < container.__n then
    elem = container[index + 1]
    if elem == __NULL then elem = nil
    elseif elem == nil then elem = __MISSING end
  end
  local elemKind
  if elem == __MISSING then elemKind = "missing"
  elseif elem == nil then elemKind = "null"
  else elemKind = inner end
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom(elemKind, elem)},
    nil, nil)
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __atom(elemKind, elem),
    nil)
  if elem == __MISSING then
    if nullable then
      return nil
    end
    return __MISSING
  end
  return elem
end
local function __arrayBounds(bKey, bDigest, bParent, input, slotName, lengthSlot,
                             desc, staticKind, isAssign, origin)
  local index = slotName.i
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom(staticKind, input)},
    nil, nil)
  if index < 0 or index > lengthSlot then
    local e = __failExpr("E8002", "array index out of bounds", origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    error(e, 0)
  end
  if isAssign then
    local ok, checked = pcall(__bcheck, desc, staticKind, input)
    if not ok then
      __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(checked))
      error(checked, 0)
    end
  end
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __atom(staticKind, input),
    nil)
end
local function __slotAtom(s)
  if s.slot == "a" then
    return "slot:"..s.i.."/"..tostring(s.i < s.n).."/"..tostring(s.a and (s.i == s.n))
  end
  return "keyslot:"..__esc(s.k)
end
local function __arrayBoundsSlot(bKey, bDigest, bParent, slotName, lengthSlot,
                                    isAssign, origin)
  local index = slotName.i
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__slotAtom(slotName)}, nil, nil)
  if index < 0 or index > lengthSlot then
    local e = __failExpr("E8002", "array index out of bounds", origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    error(e, 0)
  end
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __slotAtom(slotName), nil)
end
local function __foreachCheck(opKey, digest, parent, desc, elem, origin)
  if elem == __MISSING then
    local e = __failExpr("E8001", "expected "..desc..", got missing", origin, desc,
      "missing")
    __ev(opKey, "FAILURE", "FOR_EACH", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  local ok, checked = pcall(__bcheck, desc, desc, elem)
  if not ok then
    local e = checked
    __ev(opKey, "FAILURE", "FOR_EACH", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
end
local function __normalizeEvent(opKey, digest, parent, slotName)
  local atom
  if slotName.slot == "a" then
    atom = "slot:"..slotName.i.."/"..tostring(slotName.i < slotName.n).."/"
      ..tostring(slotName.a and (slotName.i == slotName.n))
  else
    atom = "keyslot:"..__esc(slotName.k)
  end
  __ev(opKey, "SUCCESS", "INDEX_NORMALIZE", digest, parent, {}, atom, nil)
end
local function __u8sub(s, i, j)
  return string.sub(s, i, j)
end
-- The wrapper unwrapper: a table carrying an __fn field exposes the
-- underlying callable (the closure or the adapter protocol).
local function __unfn(v)
  if type(v) == "table" and v.__fn ~= nil then return v.__fn end
  return v
end
-- The actual-kind atom of one host-supplied argument (the callback
-- dispatch surface): null/boolean/int/number/string by the runtime
-- carrier — identical to the semantic oracle's scripted-argument
-- atomization.
local function __hostAtom(v)
  if v == nil then return "null" end
  local t = type(v)
  if t == "boolean" then return "bool:"..tostring(v) end
  if t == "number" then
    if v % 1 == 0 then return "int:"..tostring(v) end
    return __atom("number", v)
  end
  if t == "string" then return "str:"..__esc(v) end
  return "ref:"..__allocId(v)
end
-- The adapter's source-signature check (D15): the resolved source's
-- carried canonical spec text must equal the recorded source signature;
-- a mismatch is E8010 FUNCTION_SIGNATURE at the invoking op's origin
-- with the active frames (the check runs before any source-frame push).
local function __fncheck(v, expected, origin)
  local carried = ""
  if type(v) == "table" then carried = v.__csig or "" end
  if type(v) == "function" then carried = v.__csig or "" end
  if carried == expected then return v end
  return error(__failExpr("E8010", "function signature mismatch: expected "..expected
    ..", got "..carried, origin, expected, carried), 0)
end
-- The adapter's source resolution per the closed capture mode
-- (D15 creation half): 0 VALUE, 1 SHARED_CELL, 2 REEVALUATE_THUNK.
local function __adaptSource(w)
  if w.__mode == 0 then return w.__value
  elseif w.__mode == 1 then return w.__cell[1]
  else return w.__thunk() end
end
-- The D15 adapter invocation protocol: resolve the source per the
-- closed capture mode (0 VALUE, 1 SHARED_CELL, 2 REEVALUATE_THUNK),
-- the source-signature check, then the source invocation with the
-- leading M arguments only. A DEAL-body source pushes its function id
-- onto the active frames for the invocation (popped on every path); the
-- identical completion error propagates unchanged.
local function __adaptInvoke(w, origin, ...)
  local __cargs = {...}
  local src = __adaptSource(w)
  __fncheck(src, w.__csrc, origin)
  local __fid = nil
  if type(src) == "table" then __fid = src.__fid end
  if type(src) == "function" then __fid = src.__fid end
  local __pushed = false
  if __fid ~= nil then
    table.insert(__frames, 1, tostring(__fid))
    __pushed = true
  end
  local __okA, __vA = pcall(__unfn(src), unpack(__cargs, 1, w.__m))
  if __pushed then table.remove(__frames, 1) end
  if not __okA then error(__vA, 0) end
  return __vA
end
-- ==== the D13 async machine (ASYNC_START/AWAIT) ====
-- The canonical-token task registry and the deterministic FIFO queue —
-- chunk-global (one execution state across a multi-module drive).
__tasks = __tasks or {}
__ready = __ready or {}
-- One DEAL body task record: the coroutine wrapping the body-task
-- closure (the canonical AsyncTokenId is the task record).
local function __asyncStartTask(tokenId, owner, co, args)
  __tasks[tokenId] = {token = tokenId, owner = owner, co = co, args = args,
                      status = 0, value = nil, err = nil, label = nil}
  table.insert(__ready, tokenId)
  return tokenId
end
-- One async host operation record: its completion arrives through the
-- host seam at the awaiting site (never through the body drain).
local function __asyncStartHost(tokenId, label)
  __tasks[tokenId] = {token = tokenId, owner = "HOST_OPERATION", co = nil,
                      args = nil, status = 2, value = nil, err = nil,
                      label = label}
  table.insert(__ready, tokenId)
  return tokenId
end
-- The deterministic FIFO drain (the oracle's drainReadyTasks): every
-- pending body task resumes to completion in submission order (a task
-- body's nested starts re-evaluate the queue length — the while form
-- drains them too). A DEAL failure completes the task record; an
-- infrastructure failure is never reified. Host operations complete
-- only at their own AWAIT through the seam.
local function __asyncDrain()
  local i = 1
  while i <= #__ready do
    local id = __ready[i]
    local t = __tasks[id]
    if t ~= nil and t.status == 0 then
      t.status = 1
      local ok, v = coroutine.resume(t.co, unpack(t.args, 1, #t.args))
      if not ok then
        if type(v) == "table" and v.__d then
          t.err = v
        else
          error(v, 0)
        end
      else
        t.value = v
      end
    end
    i = i + 1
  end
  __ready = {}
end
""";
}
