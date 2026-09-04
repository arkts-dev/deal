package deal.codegen.jvm;

import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.FunctionId;
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
 * The shared JVM emitter of the decomposition-tail integration
 * verification (ISSUE-0410): emits a real Java artifact from the
 * validated {@link LoweredModuleUnit} + {@link StructuredBodyTable} over
 * the EVALUATION_ORDER op set, realizing the pinned shared-emitter
 * obligations (the {@link deal.semantic.SharedEmitterRealizationContract}
 * rows; binary-comparison-selectors B-D6; control-flow-structures C-D9;
 * assignment-delete-address-chains A-D8):
 *
 * <ul>
 *   <li><b>Chains:</b> every chain child's result is materialized into a
 *       fresh local in payload order before the next child executes; the
 *       bounds check runs only from the boundary child's projection;
 *       receiver/key/RHS expressions are never re-emitted; the retained
 *       Lua {@code emitAssignment} double-evaluation shape never
 *       appears.</li>
 *   <li><b>Comparisons:</b> ints via primitive comparison; number EQ via
 *       IEEE {@code ==} (never {@code Double.compare}) and orderings via
 *       {@code <}/{@code <=}/{@code >}/{@code >=} predicates (NaN false);
 *       string order via code point comparison (never
 *       {@code String.compareTo}); reference identity via {@code ==}
 *       (never {@code equals()}).</li>
 *   <li><b>Control flow:</b> no speculative execution; condition ops
 *       emitted inside the loop structure and re-evaluated per
 *       iteration; the {@code FOR_EACH} iterable materialized into a
 *       local before the loop; the short-circuited block behind a guard;
 *       FOR's continue landing before the update; block code generated
 *       per the {@code StructuredBodyTable} membership; catches limited
 *       to DEAL errors ({@code DealError} — infrastructure failures are
 *       never caught or reified).</li>
 * </ul>
 *
 * <p>The artifact publishes its execution report on the dedicated trace
 * channel (stderr) through the
 * {@link deal.semantic.SemanticTraceProtocol} line grammar via
 * {@link JvmRuntime} — byte-identical to the semantic oracle's report —
 * and writes real console effect bytes to stdout.</p>
 */
public final class JvmSemanticEmitter {

    private JvmSemanticEmitter() {
    }

    /** The emitted Java artifact: the class name plus the source text. */
    public record EmissionResult(String className, String source) {
    }

    /** Emits the complete shared-JVM artifact for the validated unit. */
    public static EmissionResult emitModule(LoweredModuleUnit unit,
                                            StructuredBodyTable table) {
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
        /** The payload-owned children only (closure computation excludes them). */
        final java.util.Set<OpId> structuralOwned;
        final StringBuilder out = new StringBuilder();
        /** The enclosing TRY_CATCH depth: transfers inside a try body
         *  signal via JvmRuntime.Transfer and re-apply in the dispatch. */
        int tryDepth = 0;
        /** Structure ancestors are computed statically from the block tree
         *  per transfer (never a runtime-sensitive stack). */
        final String className;

        Session(LoweredModuleUnit unit, StructuredBodyTable table) {
            this.unit = unit;
            this.table = table;
            String path = unit.moduleId().path();
            StringBuilder name = new StringBuilder("SharedM");
            for (char c : path.toCharArray()) {
                name.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
            this.className = name.toString();
            for (SemanticOp op : unit.ops()) {
                opsById.put(op.opId(), op);
                if (op.kind() == SemanticOpKind.BINDING_ALLOC) {
                    KindPayload.BindingAllocPayload payload =
                        (KindPayload.BindingAllocPayload) op.payload();
                    cellKinds.putIfAbsent(payload.binding(), payload.cellKind());
                }
            }
            structuralOwned = ChainOperandCompletion.structuralOwners(unit);
            ownedChildren.addAll(structuralOwned);
            ChainOperandCompletion.registerChainOperandOwners(unit, structuralOwned,
                ownedChildren);
        }

        // -- naming ---------------------------------------------------------------

        String slot(ValueId id) {
            return "v" + id.id();
        }

        String cell(BindingId id, long generation) {
            return "b" + id.id() + "g" + generation;
        }

        String fnFactory(FunctionId id) {
            return "F" + id.id();
        }

        String loopLabel(OpId opId) {
            return "LOOP" + opId.id();
        }

        // -- static kinds -----------------------------------------------------------

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

        /** The runtime-side descriptor text for a boundary check. */
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

        // -- java literals -----------------------------------------------------------

        static String javaString(String text) {
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
                        if (c < 0x20 || c > 0x7e) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
            return sb.toString();
        }

        // -- emission ----------------------------------------------------------------

        EmissionResult emit() {
            out.append("import deal.codegen.jvm.JvmRuntime;\n");
            out.append("import java.util.List;\n");
            out.append("\npublic final class ").append(className).append(" {\n");
            out.append("  public static final String MODULE = ")
                .append(javaString(unit.moduleId().path())).append(";\n");
            // Slots and cells.
            java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
            for (SemanticOp op : unit.ops()) {
                if (op.result() instanceof ValueId valueId) {
                    fields.add(slot(valueId));
                }
                switch (op.payload()) {
                    case KindPayload.BindingAllocPayload payload ->
                        fields.add(cell(payload.binding(), payload.generation()));
                    case KindPayload.BindingInitPayload payload ->
                        fields.add(cell(payload.binding(), payload.generation()));
                    case KindPayload.BindingLoadPayload payload ->
                        fields.add(cell(payload.binding(), payload.generation()));
                    case KindPayload.BindingStorePayload payload ->
                        fields.add(cell(payload.binding(), payload.generation()));
                    case KindPayload.ForEachPayload payload ->
                        fields.add(cell(payload.binding(), payload.generation()));
                    case KindPayload.TryCatchPayload payload ->
                        fields.add(cell(payload.catchBinding(), 0));
                    default -> {
                    }
                }
            }
            for (String field : fields) {
                out.append("  static Object ").append(field).append(";\n");
            }
            // Function factories.
            for (LoweredFunction function : unit.functions().values()) {
                emitFunctionFactory(function);
            }
            // main.
            out.append("  public static void main(String[] args) {\n");
            out.append("    JvmRuntime.setModule(MODULE);\n");
            out.append("    try {\n");
            emitBlockOps(unit.moduleInit().initBlock(), 3);
            out.append("      System.err.println(\"R|success|null\");\n");
            out.append("      System.err.flush();\n");
            out.append("    } catch (JvmRuntime.DealError e) {\n");
            out.append("      System.err.println(\"R|failure|\" + JvmRuntime.errtext(e));\n");
            out.append("      System.err.flush();\n");
            out.append("    }\n");
            out.append("  }\n");
            out.append("}\n");
            return new EmissionResult(className, out.toString());
        }

        /** Emits one function factory: a FunctionValue over the passed capture cells. */
        private void emitFunctionFactory(LoweredFunction function) {
            FunctionId functionId = function.functionId();
            List<BindingId> captures = function.captures();
            StringBuilder params = new StringBuilder();
            for (BindingId captureId : captures) {
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append("Object c" + captureId.id());
            }
            out.append("  static JvmRuntime.FunctionValue ").append(fnFactory(functionId))
                .append('(').append(params).append(") {\n");
            out.append("    return new JvmRuntime.FunctionValue(args -> {\n");
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
                out.append("      ").append(cell(payload.binding(), payload.generation())).append(" = ")
                    .append(kind == BindingCellKind.SHARED_CELL
                        ? "new Object[]{args[" + i + "]}" : "args[" + i + "]")
                    .append(";\n");
            }
            for (int i = paramCount; i < bodyOps.size(); i++) {
                if (ownedChildren.contains(bodyOps.get(i))) {
                    continue;
                }
                emitOp(opsById.get(bodyOps.get(i)), 3);
            }
            out.append("    }, ")
                .append(javaString(descriptorText(function.descriptor()))).append(");\n");
            out.append("  }\n");
        }

        private void emitBlockOps(BlockId block, int indent) {
            for (OpId opId : table.blockOps().get(block)) {
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                emitOp(opsById.get(opId), indent);
            }
        }

        private String indent(int level) {
            return "  ".repeat(level);
        }

        // -- per-op emission ---------------------------------------------------------

        private void emitOp(SemanticOp op, int indent) {
            switch (op.kind()) {
                case CONST -> emitConst(op, indent);
                case UNARY -> emitUnary(op, indent);
                case BINARY -> emitBinary(op, indent);
                case STRING_CONCAT -> emitConcat(op, indent);
                case ARRAY_NEW -> emitArrayNew(op, indent);
                case TABLE_NEW -> emitTableNew(op, indent);
                case ARRAY_LENGTH -> emitArrayLength(op, indent);
                case MEMBER_READ -> emitMemberRead(op, indent);
                case MEMBER_WRITE -> emitMemberWrite(op, indent);
                case MEMBER_DELETE -> emitMemberDelete(op, indent);
                case INDEX_NORMALIZE -> emitNormalize(op, indent);
                case INDEX_READ -> emitIndexRead(op, indent);
                case INDEX_WRITE -> emitIndexWrite(op, indent);
                case INDEX_DELETE -> emitIndexDelete(op, indent);
                case BOUNDARY -> emitFreeBoundary(op, indent);
                case BINDING_ALLOC -> emitBindingAlloc(op, indent);
                case BINDING_INIT -> emitBindingInit(op, indent);
                case BINDING_LOAD -> emitBindingLoad(op, indent);
                case BINDING_STORE -> emitBindingStore(op, indent);
                case CLOSURE_NEW -> emitClosureNew(op, indent);
                case ASSIGN -> emitAssign(op, indent);
                case DELETE -> emitDelete(op, indent);
                case CALL -> emitCall(op, indent);
                case INTRINSIC_CALL -> emitIntrinsic(op, indent);
                case STDLIB_CALL -> emitStdlib(op, indent);
                case BRANCH -> emitBranch(op, indent);
                case LOOP -> emitLoop(op, indent);
                case FOR_EACH -> emitForEach(op, indent);
                case TRY_CATCH -> emitTryCatch(op, indent);
                case THROW -> emitThrow(op, indent);
                case RETURN -> emitReturn(op, indent);
                case BREAK -> emitBreak(op, indent);
                case CONTINUE -> emitContinue(op, indent);
                case DISCARD -> emitDiscard(op, indent);
                case MODULE_IMPORT -> emitModuleImport(op, indent);
                case EXPORT_READ -> emitExportRead(op, indent);
                default -> throw new IllegalStateException("op kind " + op.kind()
                    + " has no shared-JVM emission in this decomposition-tail domain");
            }
        }

        private String opKey(OpId id) {
            return id.module().path() + "#" + id.id();
        }

        private String parentKey(OpId parent) {
            return parent == null ? "-" : opKey(parent);
        }

        private void emitStart(SemanticOp op, int indent) {
            StringBuilder inputs = new StringBuilder();
            for (int i = 0; i < op.operands().size(); i++) {
                if (i > 0) {
                    inputs.append(", ");
                }
                inputs.append("JvmRuntime.atom(").append(slot(op.operands().get(i)))
                    .append(", ").append(javaString(staticKind(op.operandTypes().get(i))))
                    .append(")");
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(").append(inputs).append("), null, null);\n");
        }

        /** START with the closed slot atom for the second operand (slot ops). */
        private void emitSlotOperandStart(SemanticOp op, int indent) {
            StringBuilder inputs = new StringBuilder();
            inputs.append("JvmRuntime.atom(").append(slot(op.operands().get(0)))
                .append(", ").append(javaString(staticKind(op.operandTypes().get(0))))
                .append("), ");
            inputs.append("\"slot:\" + ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[0] + \"/\" + (((long[]) ").append(slot(op.operands().get(1)))
                .append(")[0] < ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[1]) + \"/\" + (((long[]) ").append(slot(op.operands().get(1)))
                .append(")[2] == 1 && ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[0] == ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[1])");
            for (int i = 2; i < op.operands().size(); i++) {
                inputs.append(", JvmRuntime.atom(").append(slot(op.operands().get(i)))
                    .append(", ").append(javaString(staticKind(op.operandTypes().get(i))))
                    .append(")");
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(").append(inputs).append("), null, null);\n");
        }

        private void emitBoundaryStart(SemanticOp boundary, String inputExpr,
                                       RuntimeDescriptor inputDescriptor, int indent) {
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(boundary.opId()))).append(", \"START\", ")
                .append("\"BOUNDARY\", ")
                .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(boundary.origin().parentOpId())))
                .append(", List.of(JvmRuntime.atom(").append(inputExpr).append(", ")
                .append(javaString(staticKind(inputDescriptor)))
                .append(")), null, null);\n");
        }

        private void emitBoundarySuccess(SemanticOp boundary, String valueExpr,
                                         RuntimeDescriptor descriptor, int indent) {
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(boundary.opId()))).append(", \"SUCCESS\", ")
                .append("\"BOUNDARY\", ")
                .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(boundary.origin().parentOpId())))
                .append(", List.of(), JvmRuntime.atom(").append(valueExpr).append(", ")
                .append(javaString(staticKind(descriptor))).append("), null);\n");
        }

        private void emitResultSuccess(SemanticOp op, String valueExpr,
                                       RuntimeDescriptor resultDescriptor, int indent) {
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), JvmRuntime.atom(").append(valueExpr).append(", ")
                .append(javaString(staticKind(resultDescriptor))).append("), null);\n");
        }

        private void emitPlainSuccess(SemanticOp op, int indent) {
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), null, null);\n");
        }

        /** The raw SUCCESS emission of a structure closed by a transfer. */
        private void emitClosedSuccess(SemanticOp op, int indent) {
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), null, null);\n");
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
                                          boolean stopAtTry, int indent) {
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
                emitClosedSuccess(ancestor, indent);
            }
        }

        private void emitFailureEvent(OpId id, String kindName, SemanticOp op,
                                      String errExpr, int indent) {
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(id))).append(", \"FAILURE\", ")
                .append(javaString(kindName)).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), null, ").append(errExpr).append(");\n");
        }

        static String numberLiteral(double value) {
            if (Double.isNaN(value)) {
                return "Double.valueOf(Double.NaN)";
            }
            if (Double.isInfinite(value)) {
                return value > 0 ? "Double.valueOf(Double.POSITIVE_INFINITY)"
                    : "Double.valueOf(Double.NEGATIVE_INFINITY)";
            }
            return "Double.valueOf(" + Double.toHexString(value) + ")";
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

        private void emitConst(SemanticOp op, int indent) {
            KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
            String literal = switch (payload.value()) {
                case ScalarValue.Null ignored -> "null";
                case ScalarValue.Boolean bool -> bool.value()
                    ? "Boolean.TRUE" : "Boolean.FALSE";
                case ScalarValue.Int intValue -> "Long.valueOf(" + intValue.value() + "L)";
                case ScalarValue.Number number -> numberLiteral(number.value());
                case ScalarValue.String string -> javaString(string.value());
            };
            emitStart(op, indent);
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(literal).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitUnary(SemanticOp op, int indent) {
            KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
            emitStart(op, indent);
            if (payload.selector() == deal.semantic.ir.UnarySelector.BOOL_NOT) {
                out.append(indent(indent)).append(slot((ValueId) op.result()))
                    .append(" = !((Boolean) ").append(slot(op.operands().get(0)))
                    .append(");\n");
            } else {
                out.append(indent(indent)).append(slot((ValueId) op.result()))
                    .append(" = JvmRuntime.unary(")
                    .append(javaString(payload.selector().name())).append(", ")
                    .append(slot(op.operands().get(0))).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(originOf(op))).append(");\n");
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitBinary(SemanticOp op, int indent) {
            KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
            emitStart(op, indent);
            boolean arithmetic = payload.selector().name().startsWith("INT32_")
                || payload.selector().name().startsWith("NUMBER_");
            boolean comparison = payload.selector().name().endsWith("_EQ")
                || payload.selector().name().endsWith("_NE")
                || payload.selector().name().endsWith("_LT")
                || payload.selector().name().endsWith("_LE")
                || payload.selector().name().endsWith("_GT")
                || payload.selector().name().endsWith("_GE");
            if (arithmetic && !comparison) {
                out.append(indent(indent)).append(slot((ValueId) op.result()))
                    .append(" = JvmRuntime.arith(")
                    .append(javaString(payload.selector().name())).append(", ")
                    .append(slot(op.operands().get(0))).append(", ")
                    .append(slot(op.operands().get(1))).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(originOf(op))).append(");\n");
                emitResultSuccess(op, slot((ValueId) op.result()),
                    (RuntimeDescriptor) op.resultType(), indent);
                return;
            }
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = JvmRuntime.cmp(")
                .append(javaString(payload.selector().name())).append(", ")
                .append(javaString(staticKind(op.operandTypes().get(0)))).append(", ")
                .append(slot(op.operands().get(0))).append(", ")
                .append(javaString(staticKind(op.operandTypes().get(1)))).append(", ")
                .append(slot(op.operands().get(1))).append(", ")
                .append(payload.side() == null ? "null"
                    : javaString(payload.side().name())).append(");\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitConcat(SemanticOp op, int indent) {
            KindPayload.StringConcatPayload payload =
                (KindPayload.StringConcatPayload) op.payload();
            emitStart(op, indent);
            StringBuilder expr = new StringBuilder();
            for (ValueId fragment : payload.fragments()) {
                if (expr.length() > 0) {
                    expr.append(" + ");
                }
                expr.append("((String) ").append(slot(fragment)).append(")");
            }
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(expr.length() == 0 ? "\"\"" : expr).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitArrayNew(SemanticOp op, int indent) {
            KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = new JvmRuntime.Array(")
                .append(payload.values().size()).append(");\n");
            for (int i = 0; i < payload.values().size(); i++) {
                OpId boundaryId = payload.elementBoundaryOpIds().get(i);
                SemanticOp boundary = opsById.get(boundaryId);
                ValueId input = payload.values().get(i);
                emitBoundaryStart(boundary, slot(input), payload.elementDescriptor(),
                    indent);
                out.append(indent(indent)).append("Object __be_").append(boundary.opId().id())
                    .append(" = JvmRuntime.bcheck(")
                    .append(javaString(descriptorText(payload.elementDescriptor())))
                    .append(", ")
                    .append(javaString(staticKind(payload.elementDescriptor())))
                    .append(", ").append(slot(input)).append(");\n");
                out.append(indent(indent)).append("((JvmRuntime.Array) ").append(target)
                    .append(").elements.add(__be_").append(boundary.opId().id())
                    .append(");\n");
                emitBoundarySuccess(boundary, "__be_" + boundary.opId().id(),
                    payload.elementDescriptor(), indent);
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitTableNew(SemanticOp op, int indent) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = new JvmRuntime.Table();\n");
            for (KindPayload.TableEntry entry : payload.entries()) {
                out.append(indent(indent)).append("((").append(
                        "JvmRuntime.Table) ").append(target).append(").write(")
                    .append(javaString(entry.key())).append(", ")
                    .append(slot(entry.value())).append(");\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitArrayLength(SemanticOp op, int indent) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = Long.valueOf(((").append("JvmRuntime.Array) ")
                .append(slot(payload.arrayValue())).append(").length);\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitMemberRead(SemanticOp op, int indent) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = ((")
                .append("JvmRuntime.Table) ").append(slot(payload.table())).append(").read(")
                .append(javaString(payload.key())).append(");\n");
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, target, boundaryPayload.descriptor(), indent);
                out.append(indent(indent)).append("Object __mr_").append(boundary.opId().id())
                    .append(" = JvmRuntime.bcheck(")
                    .append(javaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(javaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target).append(");\n");
                out.append(indent(indent)).append(target).append(" = __mr_")
                    .append(boundary.opId().id()).append(";\n");
                emitBoundarySuccess(boundary, target, boundaryPayload.descriptor(), indent);
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitMemberWrite(SemanticOp op, int indent) {
            KindPayload.MemberWritePayload payload =
                (KindPayload.MemberWritePayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("((").append("JvmRuntime.Table) ")
                .append(slot(payload.table())).append(").write(")
                .append(javaString(payload.key())).append(", ")
                .append(slot(payload.value())).append(");\n");
            emitPlainSuccess(op, indent);
        }

        private void emitMemberDelete(SemanticOp op, int indent) {
            KindPayload.MemberDeletePayload payload =
                (KindPayload.MemberDeletePayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("((").append("JvmRuntime.Table) ")
                .append(slot(payload.table())).append(").remove(")
                .append(javaString(payload.key())).append(");\n");
            emitPlainSuccess(op, indent);
        }

        private void emitNormalize(SemanticOp op, int indent) {
            KindPayload.IndexNormalizePayload payload =
                (KindPayload.IndexNormalizePayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            switch (payload.mode()) {
                case ARRAY_READ, ARRAY_WRITE -> {
                    boolean write = payload.mode() == deal.semantic.ir.IndexMode.ARRAY_WRITE;
                    out.append(indent(indent)).append(target)
                        .append(" = new long[]{((Long) ").append(slot(payload.rawKey()))
                        .append(").longValue(), ((Long) ")
                        .append(slot(payload.currentLength())).append(").longValue(), ")
                        .append(write ? "1L" : "0L").append("};\n");
                }
                case TABLE_READ, TABLE_WRITE ->
                    out.append(indent(indent)).append(target).append(" = new Object[]{")
                        .append("\"t\", ").append(slot(payload.rawKey())).append("};\n");
            }
            // SUCCESS with the closed slot atom.
            out.append(indent(indent)).append("if (").append(target)
                .append(" instanceof long[]) {\n");
            out.append(indent(indent)).append("  long[] __s = (long[]) ").append(target)
                .append(";\n");
            out.append(indent(indent)).append(
                "  JvmRuntime.ev(MODULE, ").append(javaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"INDEX_NORMALIZE\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), \"slot:\" + __s[0] + \"/\" + (__s[0] < __s[1]) "
                    + "+ \"/\" + (__s[2] == 1 && __s[0] == __s[1]), null);\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(target)
                .append(";\n");
            out.append(indent(indent)).append(
                "  JvmRuntime.ev(MODULE, ").append(javaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"INDEX_NORMALIZE\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), \"keyslot:\" + JvmRuntime.esc((String) __s[1]), "
                    + "null);\n");
            out.append(indent(indent)).append("}\n");
        }

        private void emitIndexRead(SemanticOp op, int indent) {
            KindPayload.IndexReadPayload payload = (KindPayload.IndexReadPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.elementBoundaryOpId());
            RuntimeDescriptor descriptor =
                ((KindPayload.BoundaryPayload) boundary.payload()).descriptor();
            boolean nullable = descriptor instanceof RuntimeDescriptor.Nullable;
            RuntimeDescriptor inner = nullable
                ? ((RuntimeDescriptor.Nullable) descriptor).inner() : descriptor;
            emitSlotOperandStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = JvmRuntime.arrayRead(")
                .append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(javaString(opKey(boundary.opId()))).append(", ")
                .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(boundary.origin().parentOpId()))).append(", ")
                .append(javaString(descriptorText(descriptor))).append(", ")
                .append(javaString(descriptorText(inner))).append(", (JvmRuntime.Array) ")
                .append(slot(payload.container())).append(", ((long[]) ")
                .append(slot(payload.slot())).append(")[0], ")
                .append(nullable ? "true" : "false").append(", ")
                .append(javaString(originOf(op))).append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitIndexWrite(SemanticOp op, int indent) {
            KindPayload.IndexWritePayload payload =
                (KindPayload.IndexWritePayload) op.payload();
            emitStart(op, indent);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            String value = slot(payload.value());
            out.append(indent(indent)).append("if (").append(slotName)
                .append(" instanceof long[]) {\n");
            out.append(indent(indent)).append("  long[] __s = (long[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  if (__s[2] == 1 && __s[0] == __s[1]) ((")
                .append("JvmRuntime.Array) ").append(container).append(").length++;\n");
            out.append(indent(indent)).append("  int __wi = (int) __s[0];\n");
            out.append(indent(indent)).append("  while (((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.size() <= __wi) ((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.add(null);\n");
            out.append(indent(indent)).append("  ((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.set(__wi, ").append(value).append(");\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  ((").append("JvmRuntime.Table) ")
                .append(container).append(").write((String) __s[1], ").append(value)
                .append(");\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
        }

        private void emitIndexDelete(SemanticOp op, int indent) {
            KindPayload.IndexDeletePayload payload =
                (KindPayload.IndexDeletePayload) op.payload();
            emitStart(op, indent);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            out.append(indent(indent)).append("if (").append(slotName)
                .append(" instanceof long[]) {\n");
            out.append(indent(indent)).append("  long[] __s = (long[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  if (__s[0] < ((")
                .append("JvmRuntime.Array) ").append(container).append(").length && __s[0] < ((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.size()) ((").append("JvmRuntime.Array) ")
                .append(container).append(").elements.set((int) __s[0], "
                    + "JvmRuntime.MISSING);\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  ((").append("JvmRuntime.Table) ")
                .append(container).append(").remove((String) __s[1]);\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
        }

        /** A free BOUNDARY op: the runtime check over the payload input. */
        private void emitFreeBoundary(SemanticOp op, int indent) {
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append("\"BOUNDARY\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), null, null);\n");
            out.append(indent(indent)).append("Object __fb_").append(op.opId().id())
                .append(" = JvmRuntime.bcheck(")
                .append(javaString(descriptorText(payload.descriptor()))).append(", ")
                .append(javaString(staticKind(payload.descriptor()))).append(", ")
                .append(slot(payload.input())).append(");\n");
            if (op.result() instanceof ValueId valueId) {
                out.append(indent(indent)).append(slot(valueId)).append(" = __fb_")
                    .append(op.opId().id()).append(";\n");
                emitBoundarySuccess(op, slot(valueId), payload.descriptor(), indent);
            } else {
                emitBoundarySuccess(op, "__fb_" + op.opId().id(), payload.descriptor(),
                    indent);
            }
        }

        private void emitBindingAlloc(SemanticOp op, int indent) {
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            emitStart(op, indent);
            switch (payload.cellKind()) {
                case DIRECT -> out.append(indent(indent))
                    .append(cell(payload.binding(), payload.generation())).append(" = null;\n");
                case SHARED_CELL -> out.append(indent(indent))
                    .append(cell(payload.binding(), payload.generation())).append(" = new Object[1];\n");
            }
            emitPlainSuccess(op, indent);
        }

        private void emitBindingInit(SemanticOp op, int indent) {
            KindPayload.BindingInitPayload payload =
                (KindPayload.BindingInitPayload) op.payload();
            emitStart(op, indent);
            BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                BindingCellKind.DIRECT);
            String valueExpr = hasProducer(payload.value())
                ? slot(payload.value()) : "new JvmRuntime.Intrinsic()";
            if (kind == BindingCellKind.SHARED_CELL) {
                out.append(indent(indent)).append("((").append("Object[]) ")
                    .append(cell(payload.binding(), payload.generation())).append(")[0] = ")
                    .append(valueExpr).append(";\n");
            } else {
                out.append(indent(indent)).append(cell(payload.binding(), payload.generation())).append(" = ")
                    .append(valueExpr).append(";\n");
            }
            emitPlainSuccess(op, indent);
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

        private void emitBindingLoad(SemanticOp op, int indent) {
            KindPayload.BindingLoadPayload payload =
                (KindPayload.BindingLoadPayload) op.payload();
            emitStart(op, indent);
            BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                BindingCellKind.DIRECT);
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(kind == BindingCellKind.SHARED_CELL
                    ? "((Object[]) " + cell(payload.binding(), payload.generation()) + ")[0]"
                    : cell(payload.binding(), payload.generation()))
                .append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitBindingStore(SemanticOp op, int indent) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            emitStart(op, indent);
            BindingCellKind kind = cellKinds.getOrDefault(payload.binding(),
                BindingCellKind.DIRECT);
            if (kind == BindingCellKind.SHARED_CELL) {
                out.append(indent(indent)).append("((").append("Object[]) ")
                    .append(cell(payload.binding(), payload.generation())).append(")[0] = ")
                    .append(slot(payload.value())).append(";\n");
            } else {
                out.append(indent(indent)).append(cell(payload.binding(), payload.generation())).append(" = ")
                    .append(slot(payload.value())).append(";\n");
            }
            emitPlainSuccess(op, indent);
        }

        private void emitClosureNew(SemanticOp op, int indent) {
            KindPayload.ClosureNewPayload payload =
                (KindPayload.ClosureNewPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            StringBuilder args = new StringBuilder();
            for (BindingId captureId : payload.captures()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(cell(captureId, 0));
            }
            out.append(indent(indent)).append(target).append(" = ")
                .append(fnFactory(payload.function())).append("(").append(args)
                .append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitAssign(SemanticOp op, int indent) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("try {\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child, indent + 1);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child,
                        (KindPayload.BoundaryPayload) child.payload(), op, indent + 1);
                } else {
                    emitOp(child, indent + 1);
                }
            }
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("}\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                producerResultType(op.result()), indent);
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

        private void emitDelete(SemanticOp op, int indent) {
            KindPayload.DeletePayload payload = (KindPayload.DeletePayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("try {\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child, indent + 1);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child,
                        (KindPayload.BoundaryPayload) child.payload(), op, indent + 1);
                } else {
                    emitOp(child, indent + 1);
                }
            }
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
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
        private void emitChainOperandProducers(SemanticOp child, int indent) {
            for (SemanticOp producer : ChainOperandCompletion.operandProducersOf(
                    child, unit, structuralOwned)) {
                emitOp(producer, indent);
            }
        }

        /**
         * A chain boundary child: the bounds check runs only from this
         * boundary's projection with the chain-supplied {index, length}
         * context (A-D8 — never a target-side re-check that can raise
         * twice).
         */
        private void emitChainBoundary(SemanticOp boundary,
                                       KindPayload.BoundaryPayload payload,
                                       SemanticOp chain, int indent) {
            String input = slot(payload.input());
            switch (payload.kind()) {
                case ARRAY_ELEMENT_ASSIGNMENT, ARRAY_ELEMENT_DELETE -> {
                    // JvmRuntime.arrayBounds emits the boundary START/terminal
                    // events (the bounds check runs only from this projection);
                    // the delete boundary's input operand is the normalized
                    // slot (the closed slot atom, never a value atom).
                    if (payload.kind() == BoundaryKind.ARRAY_ELEMENT_DELETE
                            && slotEqualsChainSlot(payload.input(), chain)) {
                        out.append(indent(indent))
                            .append("JvmRuntime.arrayBoundsSlot(")
                            .append(javaString(opKey(boundary.opId()))).append(", ")
                            .append(javaString(boundary.contract().canonicalDigest()))
                            .append(", ")
                            .append(javaString(parentKey(boundary.origin().parentOpId())))
                            .append(", (long[]) ").append(chainSlotExpr(chain))
                            .append(", ((Long) ").append(chainLengthExpr(chain))
                            .append(").longValue(), ")
                            .append(javaString(originOf(boundary))).append(");\n");
                    } else {
                    out.append(indent(indent)).append("JvmRuntime.arrayBounds(")
                        .append(javaString(opKey(boundary.opId()))).append(", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", ").append(input).append(", ((long[]) ")
                        .append(chainSlotExpr(chain)).append(")[0], ((Long) ")
                        .append(chainLengthExpr(chain)).append(").longValue(), ")
                        .append(javaString(descriptorText(payload.descriptor())))
                        .append(", ")
                        .append(javaString(staticKind(payload.descriptor())))
                        .append(", ")
                        .append(payload.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                            ? "true" : "false").append(", ")
                        .append(javaString(originOf(boundary))).append(");\n");
                    }
                }
                default -> {
                    emitBoundaryStart(boundary, input, payload.descriptor(), indent);
                    out.append(indent(indent)).append("Object __cb_")
                        .append(boundary.opId().id()).append(" = JvmRuntime.bcheck(")
                        .append(javaString(descriptorText(payload.descriptor())))
                        .append(", ")
                        .append(javaString(staticKind(payload.descriptor())))
                        .append(", ").append(input).append(");\n");
                    emitBoundarySuccess(boundary, "__cb_" + boundary.opId().id(),
                        payload.descriptor(), indent);
                }
            }
        }

        private List<OpId> chainChildOps(SemanticOp chain) {
            return switch (chain.payload()) {
                case KindPayload.AssignPayload assign -> assign.childOps();
                case KindPayload.DeletePayload delete -> delete.childOps();
                default -> List.of();
            };
        }

        /** True iff the boundary input value is the chain's normalize slot. */
        private boolean slotEqualsChainSlot(deal.semantic.ir.ValueId input,
                                            SemanticOp chain) {
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
            return "null";
        }

        private String chainLengthExpr(SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.ARRAY_LENGTH
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            return "null";
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

        private void emitCall(SemanticOp op, int indent) {
            KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
            FunctionId callee = ((deal.semantic.ir.FunctionExecutionBinding.LoweredBody)
                ((KindPayload.CallCallee.Static) payload.callee()).binding()).functionId();
            emitStart(op, indent);
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor(), indent);
                out.append(indent(indent)).append("Object __pb_").append(boundary.opId().id())
                    .append(" = JvmRuntime.bcheck(")
                    .append(javaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(javaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(boundaryPayload.input())).append(");\n");
                emitBoundarySuccess(boundary, "__pb_" + boundary.opId().id(),
                    boundaryPayload.descriptor(), indent);
            }
            out.append(indent(indent)).append("JvmRuntime.pushFrame(")
                .append(javaString(String.valueOf(callee.id()))).append(");\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent)).append("  ").append(slot((ValueId) op.result()))
                .append(" = ").append(fnFactory(callee)).append("(");
            deal.semantic.ir.LoweredFunction calleeFunction = unit.functions().get(callee);
            List<deal.semantic.ir.BindingId> captures = calleeFunction == null
                ? List.of() : calleeFunction.captures();
            for (int i = 0; i < captures.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(cell(captures.get(i), 0));
            }
            out.append(").fn.invoke(new Object[]{");
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                out.append(slot(((KindPayload.BoundaryPayload) boundary.payload()).input()));
            }
            out.append("});\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("} finally {\n");
            out.append(indent(indent)).append("  JvmRuntime.popFrame();\n");
            out.append(indent(indent)).append("}\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitIntrinsic(SemanticOp op, int indent) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            String input = slot(payload.input());
            String kind = staticKind(op.operandTypes().get(0));
            String origin = originOf(op);
            switch (payload.kind()) {
                case INT_CONVERT -> out.append(indent(indent)).append(target)
                    .append(" = JvmRuntime.intConv(").append(input).append(", ")
                    .append(javaString(kind)).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(origin)).append(");\n");
                case NUMBER_CONVERT -> out.append(indent(indent)).append(target)
                    .append(" = JvmRuntime.numConv(").append(input).append(", ")
                    .append(javaString(kind)).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(origin)).append(");\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitStdlib(SemanticOp op, int indent) {
            KindPayload.StdlibCallPayload payload =
                (KindPayload.StdlibCallPayload) op.payload();
            emitStart(op, indent);
            List<SemanticOp> paramBoundaries = stdlibParamBoundaries(op);
            for (SemanticOp boundary : paramBoundaries) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor(), indent);
                out.append(indent(indent)).append("Object __sb_").append(boundary.opId().id())
                    .append(" = JvmRuntime.bcheck(")
                    .append(javaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(javaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(boundaryPayload.input())).append(");\n");
                emitBoundarySuccess(boundary, "__sb_" + boundary.opId().id(),
                    boundaryPayload.descriptor(), indent);
            }
            SemanticOp returnBoundary = stdlibReturnBoundary(op);
            if (payload.function() == deal.semantic.ir.StdlibFunctionId.CONSOLE_LOG
                    || payload.function() == deal.semantic.ir.StdlibFunctionId.CONSOLE_ERROR) {
                StringBuilder textExpr = new StringBuilder();
                for (int i = 0; i < payload.args().size(); i++) {
                    if (i > 0) {
                        textExpr.append(" + \" \" + ");
                    }
                    textExpr.append("((String) ").append(slot(payload.args().get(i)))
                        .append(")");
                }
                if (textExpr.length() == 0) {
                    textExpr.append("\"\"");
                }
                out.append(indent(indent)).append("JvmRuntime.console(").append(textExpr)
                    .append(");\n");
            }
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = null;\n");
            if (returnBoundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                emitBoundaryStart(returnBoundary, target, boundaryPayload.descriptor(),
                    indent);
                out.append(indent(indent)).append("Object __rbc_")
                    .append(returnBoundary.opId().id()).append(" = JvmRuntime.bcheck(")
                    .append(javaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", \"null\", ").append(target).append(");\n");
                out.append(indent(indent)).append(target).append(" = __rbc_")
                    .append(returnBoundary.opId().id()).append(";\n");
                emitBoundarySuccess(returnBoundary, target, boundaryPayload.descriptor(),
                    indent);
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
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

        private void emitBranch(SemanticOp op, int indent) {
            KindPayload.BranchPayload payload = (KindPayload.BranchPayload) op.payload();
            emitStart(op, indent);
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case IF -> {
                    out.append(indent(indent)).append("if (((Boolean) ").append(condition)
                        .append(").booleanValue()) {\n");
                    emitBlockOps(payload.selectedBlock(), indent + 1);
                    out.append(indent(indent)).append("}");
                    if (payload.alternateBlock() != null) {
                        out.append(" else {\n");
                        emitBlockOps(payload.alternateBlock(), indent + 1);
                        out.append(indent(indent)).append("}");
                    }
                    out.append("\n");
                    emitPlainSuccess(op, indent);
                }
                case LOGICAL_AND, LOGICAL_OR -> {
                    String target = slot((ValueId) op.result());
                    boolean isAnd = payload.selector()
                        == deal.semantic.ir.ControlSelector.LOGICAL_AND;
                    if (isAnd) {
                        // AND: the block runs when the left value is true.
                        out.append(indent(indent)).append("if (((Boolean) ")
                            .append(condition).append(").booleanValue()) {\n");
                    } else {
                        // OR: the block runs when the left value is false.
                        out.append(indent(indent)).append("if (!((Boolean) ")
                            .append(condition).append(").booleanValue()) {\n");
                    }
                    emitBlockOps(payload.selectedBlock(), indent + 1);
                    out.append(indent(indent)).append("} else {\n");
                    out.append(indent(indent)).append("  ").append(target).append(" = ")
                        .append(condition).append(";\n");
                    out.append(indent(indent)).append("}\n");
                    emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(),
                        indent);
                }
                default -> throw new IllegalStateException("BRANCH selector "
                    + payload.selector());
            }
        }

        private void emitLoop(SemanticOp op, int indent) {
            KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
            emitStart(op, indent);
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case WHILE -> {
                    out.append(indent(indent)).append(loopLabel(op.opId()))
                        .append(": while (true) {\n");
                    emitBlockOps(payload.initBlock(), indent + 1);
                    out.append(indent(indent)).append("  if (!((Boolean) ").append(condition)
                        .append(").booleanValue()) break ").append(loopLabel(op.opId()))
                        .append(";\n");
                    emitBlockOps(payload.bodyBlock(), indent + 1);
                    out.append(indent(indent)).append("}\n");
                }
                case FOR -> {
                    emitBlockOps(payload.initBlock(), indent);
                    out.append(indent(indent)).append(loopLabel(op.opId()))
                        .append(": while (true) {\n");
                    out.append(indent(indent)).append("  if (!((Boolean) ").append(condition)
                        .append(").booleanValue()) break ").append(loopLabel(op.opId()))
                        .append(";\n");
                    out.append(indent(indent)).append("  CONT").append(op.opId().id())
                        .append(": do {\n");
                    emitBlockOps(payload.bodyBlock(), indent + 2);
                    out.append(indent(indent)).append("  } while (false);\n");
                    if (payload.updateBlock() != null) {
                        emitBlockOps(payload.updateBlock(), indent + 1);
                    }
                    out.append(indent(indent)).append("}\n");
                }
                default -> throw new IllegalStateException("LOOP selector "
                    + payload.selector());
            }
            emitPlainSuccess(op, indent);
        }

        private void emitForEach(SemanticOp op, int indent) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
            emitStart(op, indent);
            switch (payload.mode()) {
                case ARRAY_VALUES -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    out.append(indent(indent)).append("{\n");
                    out.append(indent(indent)).append("  JvmRuntime.Array __it = (JvmRuntime.Array) ")
                        .append(iterable).append(";\n");
                    out.append(indent(indent)).append("  int __itn = __it.length;\n");
                    out.append(indent(indent)).append("  FE").append(op.opId().id())
                        .append(": for (int __i = 0; __i < __itn; __i++) {\n");
                    out.append(indent(indent)).append("    Object __elem = JvmRuntime.MISSING;\n");
                    out.append(indent(indent)).append("    if (__i < __it.length && __i < __it.elements.size()) {\n");
                    out.append(indent(indent)).append("      __elem = __it.elements.get(__i);\n");
                    out.append(indent(indent)).append("    }\n");
                    out.append(indent(indent)).append("    JvmRuntime.foreachCheck(")
                        .append(javaString(opKey(op.opId()))).append(", ")
                        .append(javaString(op.contract().canonicalDigest())).append(", ")
                        .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                        .append(javaString(descriptorText(elementDescriptorOf(op))))
                        .append(", __elem, ").append(javaString(originOf(op))).append(");\n");
                    out.append(indent(indent)).append("    ").append(cellName)
                        .append(" = __elem;\n");
                    emitBlockOps(payload.body(), indent + 2);
                    out.append(indent(indent)).append("  }\n");
                    out.append(indent(indent)).append("}\n");
                }
                case STRING_SCALARS -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    out.append(indent(indent)).append("{\n");
                    out.append(indent(indent)).append("  String __it = (String) ")
                        .append(iterable).append(";\n");
                    out.append(indent(indent)).append("  int[] __cps = __it.codePoints().toArray();\n");
                    out.append(indent(indent)).append("  FE").append(op.opId().id())
                        .append(": for (int __cp : __cps) {\n");
                    out.append(indent(indent)).append("    ").append(cellName)
                        .append(" = new String(Character.toChars(__cp));\n");
                    emitBlockOps(payload.body(), indent + 2);
                    out.append(indent(indent)).append("  }\n");
                    out.append(indent(indent)).append("}\n");
                }
            }
            emitPlainSuccess(op, indent);
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

        private void emitTryCatch(SemanticOp op, int indent) {
            KindPayload.TryCatchPayload payload = (KindPayload.TryCatchPayload) op.payload();
            emitStart(op, indent);
            String cellName = cell(payload.catchBinding(), 0);
            tryDepth++;
            out.append(indent(indent)).append("try {\n");
            emitBlockOps(payload.tryBlock(), indent + 1);
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __terr) {\n");
            tryDepth--;
            out.append(indent(indent)).append("  ").append(cellName)
                .append(" = new JvmRuntime.ErrorValue(__terr.code, __terr.msg);\n");
            tryDepth++;
            out.append(indent(indent)).append("  try {\n");
            emitBlockOps(payload.catchBlock(), indent + 2);
            out.append(indent(indent)).append("  } catch (JvmRuntime.DealError __cerr) {\n");
            tryDepth--;
            out.append(indent(indent)).append("    JvmRuntime.DealError __wrapped = new "
                + "JvmRuntime.DealError(__cerr.code, __cerr.msg, __cerr.origin, "
                + "__cerr.expected, __cerr.actual, __cerr.frames, __terr);\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__wrapped)", indent + 2);
            out.append(indent(indent)).append("    throw __wrapped;\n");
            out.append(indent(indent)).append("  }\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.Transfer __tr) {\n");
            tryDepth--;
            emitJvmTransferDispatch(op, payload.tryBlock(), indent + 1);
            emitJvmTransferDispatch(op, payload.catchBlock(), indent + 1);
            out.append(indent(indent)).append("  throw __tr;\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
        }

        /** The transfer dispatch: each distinct BREAK/CONTINUE/RETURN of the
         *  block re-applies after emitting the TRY_CATCH SUCCESS. */
        private void emitJvmTransferDispatch(SemanticOp tryOp, BlockId block, int indent) {
            List<SemanticOp> transfers = new ArrayList<>();
            collectTransfers(block, transfers);
            for (SemanticOp transfer : transfers) {
                switch (transfer.kind()) {
                    case BREAK -> {
                        KindPayload.BreakPayload breakPayload =
                            (KindPayload.BreakPayload) transfer.payload();
                        out.append(indent(indent)).append("if (\"break\".equals(__tr.kind) ")
                            .append("&& __tr.id == ").append(breakPayload.loopId().id())
                            .append("L) {\n");
                        emitPlainSuccess(tryOp, indent + 1);
                        emitTransferClosures(tryOp, opsById.get(breakPayload.loopId()),
                            false, indent + 1);
                        out.append(indent(indent)).append("  break ")
                            .append(loopLabel(breakPayload.loopId())).append(";\n");
                        out.append(indent(indent)).append("}\n");
                    }
                    case CONTINUE -> {
                        KindPayload.ContinuePayload continuePayload =
                            (KindPayload.ContinuePayload) transfer.payload();
                        out.append(indent(indent)).append("if (\"continue\".equals(__tr.kind) ")
                            .append("&& __tr.id == ").append(continuePayload.loopId().id())
                            .append("L) {\n");
                        emitPlainSuccess(tryOp, indent + 1);
                        emitTransferClosures(tryOp, opsById.get(continuePayload.loopId()),
                            false, indent + 1);
                        SemanticOp target = opsById.get(continuePayload.loopId());
                        if (target != null && target.kind() == SemanticOpKind.LOOP
                                && ((KindPayload.LoopPayload) target.payload()).selector()
                                    == deal.semantic.ir.ControlSelector.FOR) {
                            out.append(indent(indent)).append("  continue CONT")
                                .append(continuePayload.loopId().id()).append(";\n");
                        } else {
                            out.append(indent(indent)).append("  continue ")
                                .append(loopLabel(continuePayload.loopId())).append(";\n");
                        }
                        out.append(indent(indent)).append("}\n");
                    }
                    case RETURN -> {
                        out.append(indent(indent)).append("if (\"return\".equals(__tr.kind)) {\n");
                        emitPlainSuccess(tryOp, indent + 1);
                        emitTransferClosures(tryOp, null, false, indent + 1);
                        out.append(indent(indent)).append("  return __tr.value;\n");
                        out.append(indent(indent)).append("}\n");
                    }
                    default -> {
                    }
                }
            }
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

        private void emitThrow(SemanticOp op, int indent) {
            KindPayload.ThrowPayload payload = (KindPayload.ThrowPayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("JvmRuntime.DealError __thrown = new "
                + "JvmRuntime.DealError(((").append("JvmRuntime.ErrorValue) ")
                .append(slot(payload.errorValue())).append(").code, ((")
                .append("JvmRuntime.ErrorValue) ").append(slot(payload.errorValue()))
                .append(").message, ").append(javaString(originOf(op)))
                .append(", null, null, JvmRuntime.framesText(), null);\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__thrown)", indent);
            out.append(indent(indent)).append("throw __thrown;\n");
        }

        private void emitReturn(SemanticOp op, int indent) {
            KindPayload.ReturnPayload payload = (KindPayload.ReturnPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitStart(op, indent);
            String value = payload.value() == null ? "null" : slot(payload.value());
            out.append(indent(indent)).append("Object __rv_").append(op.opId().id())
                .append(" = ").append(value).append(";\n");
            emitBoundaryStart(boundary, "__rv_" + op.opId().id(),
                boundaryPayload.descriptor(), indent);
            out.append(indent(indent)).append("Object __rvc_").append(op.opId().id())
                .append(" = JvmRuntime.bcheck(")
                .append(javaString(descriptorText(boundaryPayload.descriptor())))
                .append(", ")
                .append(javaString(staticKind(boundaryPayload.descriptor())))
                .append(", __rv_").append(op.opId().id()).append(");\n");
            emitBoundarySuccess(boundary, "__rvc_" + op.opId().id(),
                boundaryPayload.descriptor(), indent);
            emitPlainSuccess(op, indent);
            if (tryDepth > 0) {
                emitTransferClosures(op, null, true, indent);
                out.append(indent(indent))
                    .append("throw new JvmRuntime.Transfer(\"return\", 0L, __rvc_")
                    .append(op.opId().id()).append(");\n");
            } else {
                emitTransferClosures(op, null, false, indent);
                out.append(indent(indent)).append("return __rvc_").append(op.opId().id())
                    .append(";\n");
            }
        }

        private void emitBreak(SemanticOp op, int indent) {
            KindPayload.BreakPayload payload = (KindPayload.BreakPayload) op.payload();
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
            if (tryDepth > 0) {
                emitTransferClosures(op, opsById.get(payload.loopId()), true, indent);
                out.append(indent(indent)).append("throw new JvmRuntime.Transfer(\"break\", ")
                    .append(payload.loopId().id()).append("L, null);\n");
            } else {
                emitTransferClosures(op, opsById.get(payload.loopId()), false, indent);
                SemanticOp target = opsById.get(payload.loopId());
                if (target != null && target.kind() == SemanticOpKind.FOR_EACH) {
                    out.append(indent(indent)).append("break FE")
                        .append(payload.loopId().id()).append(";\n");
                } else {
                    out.append(indent(indent)).append("break ")
                        .append(loopLabel(payload.loopId())).append(";\n");
                }
            }
        }

        private void emitContinue(SemanticOp op, int indent) {
            KindPayload.ContinuePayload payload = (KindPayload.ContinuePayload) op.payload();
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
            if (tryDepth > 0) {
                emitTransferClosures(op, opsById.get(payload.loopId()), true, indent);
                out.append(indent(indent))
                    .append("throw new JvmRuntime.Transfer(\"continue\", ")
                    .append(payload.loopId().id()).append("L, null);\n");
            } else {
                emitTransferClosures(op, opsById.get(payload.loopId()), false, indent);
                SemanticOp target = opsById.get(payload.loopId());
                if (target != null && target.kind() == SemanticOpKind.FOR_EACH) {
                    out.append(indent(indent)).append("continue FE")
                        .append(payload.loopId().id()).append(";\n");
                } else if (target != null && target.kind() == SemanticOpKind.LOOP
                        && ((KindPayload.LoopPayload) target.payload()).selector()
                            == deal.semantic.ir.ControlSelector.FOR) {
                    out.append(indent(indent)).append("continue CONT")
                        .append(payload.loopId().id()).append(";\n");
                } else {
                    out.append(indent(indent)).append("continue ")
                        .append(loopLabel(payload.loopId())).append(";\n");
                }
            }
        }

        private void emitDiscard(SemanticOp op, int indent) {
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
        }

        private void emitModuleImport(SemanticOp op, int indent) {
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
        }

        private void emitExportRead(SemanticOp op, int indent) {
            emitStart(op, indent);
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = new JvmRuntime.Intrinsic();\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }
    }
}
