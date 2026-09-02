package deal.codegen.lua;

import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.OpId;
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
 * the EVALUATION_ORDER op set, realizing the pinned shared-emitter
 * obligations (assignment-delete-address-chains A-D8, binary-comparison-
 * selectors B-D6, control-flow-structures C-D9, and the
 * {@link deal.semantic.SharedEmitterRealizationContract} rows):
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
        return new Session(unit, table).emit();
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
        final StringBuilder out = new StringBuilder();
        /** The enclosing TRY_CATCH depth: transfers inside a pcall body
         *  must signal instead of goto/return (Lua closures cannot jump
         *  to the enclosing function's labels). */
        int tryDepth = 0;
        /** Structure ancestors are computed statically from the block tree
         *  per transfer (never a runtime-sensitive stack). */

        Session(LoweredModuleUnit unit, StructuredBodyTable table) {
            this.unit = unit;
            this.table = table;
            for (SemanticOp op : unit.ops()) {
                opsById.put(op.opId(), op);
                if (op.kind() == SemanticOpKind.BINDING_ALLOC) {
                    KindPayload.BindingAllocPayload payload =
                        (KindPayload.BindingAllocPayload) op.payload();
                    cellKinds.putIfAbsent(payload.binding(), payload.cellKind());
                }
            }
            // Payload-owned children are emitted exactly once by their owner
            // arms; the block walk skips them (a double emission would
            // duplicate effects and events).
            for (SemanticOp op : unit.ops()) {
                switch (op.payload()) {
                    case KindPayload.AssignPayload assign ->
                        ownedChildren.addAll(assign.childOps());
                    case KindPayload.DeletePayload delete ->
                        ownedChildren.addAll(delete.childOps());
                    case KindPayload.ArrayNewPayload array ->
                        ownedChildren.addAll(array.elementBoundaryOpIds());
                    case KindPayload.CallPayload call -> {
                        ownedChildren.addAll(call.parameterBoundaryOpIds());
                        if (call.returnBoundaryOpId() != null) {
                            ownedChildren.add(call.returnBoundaryOpId());
                        }
                    }
                    case KindPayload.IndexReadPayload read ->
                        ownedChildren.add(read.elementBoundaryOpId());
                    case KindPayload.ReturnPayload ret ->
                        ownedChildren.add(ret.returnBoundaryOpId());
                    case KindPayload.StdlibCallPayload ignored -> {
                        for (SemanticOp candidate : opsById.values()) {
                            if (candidate.kind() == SemanticOpKind.BOUNDARY
                                    && op.opId().equals(candidate.origin().parentOpId())) {
                                ownedChildren.add(candidate.opId());
                            }
                        }
                    }
                    case KindPayload.MemberReadPayload ignored -> {
                        for (SemanticOp candidate : opsById.values()) {
                            if (candidate.kind() == SemanticOpKind.BOUNDARY
                                    && op.opId().equals(candidate.origin().parentOpId())) {
                                ownedChildren.add(candidate.opId());
                            }
                        }
                    }
                    default -> {
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
            // Upvalue pre-declarations the prelude functions reference.
            out.append("local __frames, __seq, __module, __allocIds, __allocNext = {}, "
                + "0, nil, {}, 1\n");
            out.append(PRELUDE);
            out.append("\n__module = ").append(luaString(unit.moduleId().path()))
                .append("\n");
            // One env table carries every slot and cell (LuaJIT's upvalue
            // limit never binds the function bodies).
            out.append("local S = {}\n");
            // Hoisted shared temps (goto can never jump into a local's
            // scope; every check/return temp is a top-level assignment).
            out.append("local __chk, __rvT, __rvcT, __okT, __resT, __terrT, "
                + "__cerrT, __wrappedT, __itT, __itnT, __elemT\n");

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

            // The module-init block (ends with the entry delegation) inside a
            // pcall wrapper so uncaught DEAL failures publish R|failure.
            out.append("local __mainOk, __mainErr = pcall(function()\n");
            emitBlockOps(unit.moduleInit().initBlock());
            out.append("end)\n");
            out.append("if __mainOk then\n");
            out.append("  io.stderr:write(\"R|success|null\\n\")\n");
            out.append("else\n");
            out.append("  io.stderr:write(\"R|failure|\"..__errtext(__mainErr)..\"\\n\")\n");
            out.append("end\n");
            out.append("io.stderr:flush()\n");
            return out.toString();
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
            out.append("  end\n");
            out.append("end\n");
        }

        /** Emits the ops of one block inline. */
        private void emitBlockOps(BlockId block) {
            for (OpId opId : table.blockOps().get(block)) {
                if (ownedChildren.contains(opId)) {
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
                case BOUNDARY -> emitFreeBoundary(op);
                case BINDING_ALLOC -> emitBindingAlloc(op);
                case BINDING_INIT -> emitBindingInit(op);
                case BINDING_LOAD -> emitBindingLoad(op);
                case BINDING_STORE -> emitBindingStore(op);
                case CLOSURE_NEW -> emitClosureNew(op);
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
                out.append(target).append("[").append(i + 1).append("] = __chk\n");
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
            }
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
                .append(".i + 1] = ").append(value).append("\n");
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
            out.append(cell(payload.binding(), payload.generation())).append(" = ")
                .append(kind == BindingCellKind.SHARED_CELL
                    ? "{" + valueExpr + "}" : valueExpr)
                .append("\n");
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
            out.append(cell(payload.binding(), payload.generation())).append(" = ")
                .append(kind == BindingCellKind.SHARED_CELL
                    ? "{" + slot(payload.value()) + "}" : slot(payload.value()))
                .append("\n");
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
                .append(luaString(descriptorText(payload.signature()))).append("}\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitAssign(SemanticOp op) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            emitStart(op);
            out.append("__okT, __resT = pcall(function()\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
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
            FunctionId callee = ((deal.semantic.ir.FunctionExecutionBinding.LoweredBody)
                ((KindPayload.CallCallee.Static) payload.callee()).binding()).functionId();
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
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                out.append(slot(((KindPayload.BoundaryPayload) boundary.payload()).input()));
            }
            out.append(")\n");
            out.append("table.remove(__frames, 1)\n");
            out.append("if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            out.append(slot((ValueId) op.result())).append(" = __resT\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
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
                out.append("io.stderr:write(\"F|CONSOLE_WRITE|\"..__esc(")
                    .append(textExpr).append(")..\"\\n\")\n");
                out.append("io.stderr:flush()\n");
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
                    out.append("      if __elemT == nil then __elemT = __MISSING end\n");
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
                out.append("return __rvcT\n");
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
  if staticKind == "null" then return "null" end
  if staticKind == "bool" then return "boolean" end
  if staticKind == "int" then return "int" end
  if staticKind == "number" then return "number" end
  if staticKind == "string" then return "string" end
  if staticKind == "table" then return "table" end
  if staticKind == "array" then return "array" end
  if staticKind == "function" then return "function" end
  if staticKind == "err" then return "class:@builtin/Error" end
  if string.sub(staticKind, 1, 9) == "nullable:" then
    if v == nil then return "null" end
    return __actualOf(string.sub(staticKind, 10), v)
  end
  return "missing"
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
        if elem == nil then elem = __MISSING end
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
    if elem == nil then elem = __MISSING end
  end
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent,
    {__atom((elem == __MISSING) and "missing" or inner, elem)}, nil, nil)
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {},
    __atom((elem == __MISSING) and "missing" or inner, elem), nil)
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
""";
}
